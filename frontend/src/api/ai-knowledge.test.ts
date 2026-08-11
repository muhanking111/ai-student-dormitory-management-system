import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './client'
import { HttpAiClient } from './ai-http'

vi.mock('./client', async (importOriginal) => ({
  ...await importOriginal<typeof import('./client')>(),
  apiRequest: vi.fn(),
}))

describe('HttpAiClient knowledge contract', () => {
  beforeEach(() => vi.mocked(apiRequest).mockReset())

  it('按固定知识资源端点完成 source/list/detail/upload/version/job/activate/retire', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest)
      .mockResolvedValueOnce({ records: [], total: 0, page: 1, pageSize: 20 } as never)
      .mockResolvedValueOnce({ id: 'source-1' } as never)
      .mockResolvedValueOnce({ id: 'source-1' } as never)
      .mockResolvedValueOnce({ id: 'upload-1', state: 'CREATED', mimeType: 'text/plain', sizeBytes: 6, sha256: 'a'.repeat(64), expiresAt: 't', uploadTarget: '/api/ai/knowledge/uploads/upload-1/content' } as never)
      .mockResolvedValueOnce(undefined as never)
      .mockResolvedValueOnce({ id: 'upload-1', state: 'FINALIZED' } as never)
      .mockResolvedValueOnce({ versionId: 'version-1', jobId: 'job-1', state: 'QUEUED' } as never)
      .mockResolvedValueOnce({ id: 'version-1' } as never)
      .mockResolvedValueOnce({ id: 'job-1' } as never)
      .mockResolvedValueOnce(undefined as never)
      .mockResolvedValueOnce(undefined as never)

    await client.listKnowledgeSources({ page: 1, pageSize: 20 })
    await client.createKnowledgeSource({ name: '宿管制度', classification: 'L1', matchMode: 'ANY', permissions: ['notice:read'] })
    await client.getKnowledgeSource('source-1')
    const upload = await client.createKnowledgeUpload('source-1', { mimeType: 'text/plain', sizeBytes: 6, sha256: 'a'.repeat(64) })
    await client.putKnowledgeUploadContent(upload, '制度')
    await client.finalizeKnowledgeUpload('upload-1')
    await client.createKnowledgeVersion('source-1', { uploadSessionId: 'upload-1', externalKey: 'policy-1', title: '宿管制度', version: 'v1' })
    await client.getKnowledgeVersion('version-1')
    await client.getKnowledgeJob('job-1')
    await client.activateKnowledgeVersion('version-1')
    await client.retireKnowledgeVersion('version-1')

    expect(apiRequest).toHaveBeenCalledWith('/api/ai/knowledge/sources?page=1&pageSize=20')
    expect(apiRequest).toHaveBeenCalledWith('/api/ai/knowledge/uploads/upload-1/content', expect.objectContaining({
      method: 'PUT', body: '制度', headers: { 'Content-Type': 'text/plain' },
    }))
    expect(apiRequest).toHaveBeenCalledWith('/api/ai/knowledge/versions/version-1/activate', expect.objectContaining({ method: 'POST' }))
    expect(apiRequest).toHaveBeenCalledWith('/api/ai/knowledge/versions/version-1/retire', expect.objectContaining({ method: 'POST' }))
  })

  it('拒绝非纯文本、超限、size 不一致和跨源 upload target', async () => {
    const client = new HttpAiClient()
    await expect(client.createKnowledgeUpload('source-1', { mimeType: 'application/pdf' as never, sizeBytes: 4, sha256: 'a'.repeat(64) }))
      .rejects.toThrow('仅支持经过批准的纯文本')
    await expect(client.createKnowledgeUpload('source-1', { mimeType: 'text/plain', sizeBytes: 20 * 1024 * 1024 + 1, sha256: 'a'.repeat(64) }))
      .rejects.toThrow('大小')
    await expect(client.putKnowledgeUploadContent({ id: 'upload-1', state: 'CREATED', mimeType: 'text/plain', sizeBytes: 4, sha256: 'a'.repeat(64), expiresAt: 't', uploadTarget: '/api/other' }, '制度'))
      .rejects.toThrow('上传地址不合法')
    expect(apiRequest).not.toHaveBeenCalled()
  })

  it('以 ACL CAS PATCH 更新来源，并通过 step-up 批准固定 L0 版本且读取 citation 详情', async () => {
    const client = new HttpAiClient()
    const updated = {
      id: 'source-1', name: '公开制度', sourceType: 'UPLOAD', ownerUserId: 9,
      classification: 'L0', matchMode: 'ALL', aclVersion: 2, status: 'ACTIVE',
      permissions: ['ai:knowledge:read'],
    }
    vi.mocked(apiRequest)
      .mockResolvedValueOnce(updated as never)
      .mockResolvedValueOnce({
        id: 'citation-1', type: 'KNOWLEDGE', sourceId: 'source-1', documentId: 'document-1',
        documentVersionId: 'version-1', chunkId: 'chunk-1', metricId: null, rank: 1,
        score: 0.91, quote: '允许展示的脱敏片段', locator: '第 1 段', contentHash: 'a'.repeat(64),
        createdAt: '2026-07-12T10:00:00Z',
      } as never)
      .mockResolvedValueOnce({ proof: 'single-use-step-up' } as never)
      .mockResolvedValueOnce(undefined as never)

    await expect(client.updateKnowledgeSource('source-1', {
      expectedAclVersion: 1, name: '公开制度', classification: 'L0', matchMode: 'ALL',
      status: 'ACTIVE', permissions: ['ai:knowledge:read'],
    })).resolves.toEqual(updated)
    await expect(client.getCitation('citation-1')).resolves.toMatchObject({
      id: 'citation-1', quote: '允许展示的脱敏片段', sourceId: 'source-1',
    })
    await client.approvePublicKnowledgeVersion('version-1', {
      contentHash: 'b'.repeat(64), approvalSnapshotHash: 'c'.repeat(64), password: 'current-password',
    })

    expect(apiRequest).toHaveBeenNthCalledWith(1, '/api/ai/knowledge/sources/source-1', expect.objectContaining({
      method: 'PATCH',
      body: JSON.stringify({ expectedAclVersion: 1, name: '公开制度', classification: 'L0',
        matchMode: 'ALL', status: 'ACTIVE', permissions: ['ai:knowledge:read'] }),
    }))
    const stepUpBody = JSON.parse(String(vi.mocked(apiRequest).mock.calls[2]?.[1]?.body))
    expect(stepUpBody).toMatchObject({
      actionCode: 'KNOWLEDGE_PUBLIC_APPROVE', resourcePublicId: 'version-1', password: 'current-password',
    })
    expect(stepUpBody.requestHash).toMatch(/^[0-9a-f]{64}$/)
    expect(apiRequest).toHaveBeenNthCalledWith(4,
      '/api/ai/knowledge/versions/version-1/approve-public', expect.objectContaining({
        method: 'POST',
        headers: expect.objectContaining({
          'Idempotency-Key': expect.any(String), 'X-Step-Up-Proof': 'single-use-step-up',
        }),
        body: JSON.stringify({ contentHash: 'b'.repeat(64), approvalSnapshotHash: 'c'.repeat(64) }),
      }))
  })

  it('拒绝非法 ACL CAS 版本和缺失公开审批快照，不发网络请求', async () => {
    const client = new HttpAiClient()
    await expect(client.updateKnowledgeSource('source-1', {
      expectedAclVersion: 0, permissions: [],
    })).rejects.toThrow('ACL 版本')
    await expect(client.approvePublicKnowledgeVersion('version-1', {
      contentHash: 'b'.repeat(64), approvalSnapshotHash: '', password: 'current-password',
    })).rejects.toThrow('审批快照')
    expect(apiRequest).not.toHaveBeenCalled()
  })
})
