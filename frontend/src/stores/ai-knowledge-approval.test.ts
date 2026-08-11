import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AiClient } from '../api/ai'
import { setAiClient } from '../api/ai-client'
import type {
  AiAuditCost,
  AiAuditRun,
  AiAuditRunContent,
  AiAuditRunDetail,
  AiKnowledgeJob,
  AiKnowledgeSource,
  AiKnowledgeUpload,
  AiKnowledgeVersion,
  AiProposalPreview,
} from '../types/ai'
import { useAiApprovalStore } from './aiApproval'
import { useAiKnowledgeStore } from './aiKnowledge'

const source = (id = 'source-1'): AiKnowledgeSource => ({
  id,
  name: `知识源 ${id}`,
  sourceType: 'UPLOAD',
  ownerUserId: 1,
  classification: 'L1',
  matchMode: 'ANY',
  aclVersion: 1,
  status: 'ACTIVE',
  permissions: ['ai:knowledge:read'],
})

const upload: AiKnowledgeUpload = {
  id: 'upload-1',
  state: 'CREATED',
  mimeType: 'text/plain',
  sizeBytes: 12,
  sha256: '0'.repeat(64),
  expiresAt: '2026-07-12T12:00:00Z',
  uploadTarget: '/api/ai/knowledge/uploads/upload-1/content',
}

const version = (status = 'READY'): AiKnowledgeVersion => ({
  id: 'version-1',
  documentId: 'document-1',
  sourceId: 'source-1',
  version: 'v1',
  contentHash: '1'.repeat(64),
  visibility: 'AUTHORIZED',
  status,
  sizeBytes: 12,
  aclVersion: 1,
  approvalSnapshotHash: '2'.repeat(64),
})

const job = (state = 'SUCCEEDED'): AiKnowledgeJob => ({
  id: 'job-1',
  versionId: 'version-1',
  state,
  attempt: 1,
})

const proposal = (id = 'proposal-1', state: AiProposalPreview['state'] = 'pending_approval'): AiProposalPreview => ({
  id,
  actionType: 'REPAIR_ASSIGN',
  title: `提案 ${id}`,
  target: '维修单 R-1',
  currentValue: '未指派',
  proposedValue: '维修员 A',
  impact: '仅变更负责人',
  requiredPermission: 'repair:write',
  payloadHash: 'payload-hash',
  businessSnapshotHash: 'snapshot-hash',
  version: 1,
  expiresAt: '2026-07-12T12:00:00Z',
  riskLevel: 'medium',
  evidence: { asOf: '2026-07-12T10:00:00Z', citations: [], grounded: true },
  state,
  auditAvailable: true,
  executionState: 'pending',
  executionId: `execution-${id}`,
})

const auditRun: AiAuditRun = {
  id: 'run-1',
  capability: 'ASSISTANT',
  state: 'succeeded',
  modelAlias: 'fake',
  promptVersion: 'v1',
  citationCount: 0,
  durationMs: 10,
  inputTokens: 1,
  outputTokens: 1,
  estimatedCost: 0,
  currency: 'CNY',
  chainHash: 'chain-hash',
  occurredAt: '2026-07-12T10:00:00Z',
  steps: [],
}

const auditDetail: AiAuditRunDetail = {
  run: auditRun, steps: [], retrievals: [], tools: [], citations: [], proposals: [], approvals: [], executions: [], usage: [], hashChain: [],
}
const auditContent: AiAuditRunContent = { runId: auditRun.id, messages: [] }
const auditCost: AiAuditCost = {
  id: 'cost-1', capability: 'ASSISTANT', providerAlias: 'fake', inputTokens: 1,
  outputTokens: 1, estimatedCost: 0, currency: 'CNY', occurredAt: '2026-07-12T10:00:00Z',
}

function auditDetailFor(id: string, chainHash = `chain-${id}`): AiAuditRunDetail {
  return {
    run: { ...auditRun, id, chainHash }, steps: [], retrievals: [], tools: [], citations: [], proposals: [], approvals: [], executions: [], usage: [], hashChain: [],
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((fulfill, fail) => { resolve = fulfill; reject = fail })
  return { promise, resolve, reject }
}

function completeKnowledgeClient(overrides: AiClient = {}): AiClient {
  return {
    listKnowledgeSources: vi.fn().mockResolvedValue({ records: [source()], total: 1, page: 1, pageSize: 20 }),
    getKnowledgeSource: vi.fn().mockImplementation(async (id: string) => source(id)),
    createKnowledgeSource: vi.fn().mockImplementation(async (input) => ({ ...source('source-new'), ...input })),
    updateKnowledgeSource: vi.fn().mockImplementation(async (id, input) => ({
      ...source(id), ...input, aclVersion: input.expectedAclVersion + 1,
    })),
    createKnowledgeUpload: vi.fn().mockResolvedValue(upload),
    putKnowledgeUploadContent: vi.fn().mockResolvedValue(undefined),
    finalizeKnowledgeUpload: vi.fn().mockResolvedValue({
      id: upload.id, state: 'FINALIZED', observedSha256: upload.sha256, observedSizeBytes: 18,
    }),
    createKnowledgeVersion: vi.fn().mockResolvedValue({ versionId: 'version-1', jobId: 'job-1', state: 'PENDING' }),
    getKnowledgeVersion: vi.fn().mockResolvedValue(version()),
    getKnowledgeJob: vi.fn().mockResolvedValue(job()),
    activateKnowledgeVersion: vi.fn().mockResolvedValue(undefined),
    retireKnowledgeVersion: vi.fn().mockResolvedValue(undefined),
    approvePublicKnowledgeVersion: vi.fn().mockResolvedValue(undefined),
    ...overrides,
  }
}

describe('AI knowledge store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    setAiClient(undefined)
  })

  it('完成来源选择、纯文本摄取、任务刷新、激活、退役和会话清理', async () => {
    const client = completeKnowledgeClient()
    setAiClient(client)
    const store = useAiKnowledgeStore()

    await store.loadSources(2, 5)
    expect(client.listKnowledgeSources).toHaveBeenCalledWith({ page: 2, pageSize: 5 })
    expect(store.selectedSourceId).toBe('source-1')
    expect(store.loading).toBe(false)

    const created = await store.createSource({
      name: '新知识源', classification: 'L2', matchMode: 'ALL', permissions: ['notice:read'],
    })
    expect(created?.id).toBe('source-new')
    expect(store.sources[0]?.id).toBe('source-new')
    expect(store.total).toBe(2)

    const updated = await store.updateSelectedSource({
      name: '公开制度', classification: 'L0', matchMode: 'ALL', status: 'ACTIVE',
      permissions: ['ai:knowledge:read'],
    })
    expect(updated).toMatchObject({ classification: 'L0', matchMode: 'ALL', aclVersion: 2 })
    expect(client.updateKnowledgeSource).toHaveBeenCalledWith('source-new', expect.objectContaining({
      expectedAclVersion: 1,
    }))

    store.uploadText = '维修处理要求'
    const scheduled = await store.ingestText({ externalKey: 'repair-policy', title: '维修制度', version: 'v1' })
    expect(scheduled).toMatchObject({ versionId: 'version-1', jobId: 'job-1' })
    expect(client.createKnowledgeUpload).toHaveBeenCalledWith('source-new', expect.objectContaining({
      mimeType: 'text/plain', sizeBytes: 18, sha256: expect.stringMatching(/^[0-9a-f]{64}$/),
    }))
    expect(store.uploadText).toBe('')
    expect(store.saving).toBe(false)

    await store.approvePublic('current-password')
    expect(client.approvePublicKnowledgeVersion).toHaveBeenCalledWith('version-1', {
      contentHash: '1'.repeat(64), approvalSnapshotHash: '2'.repeat(64), password: 'current-password',
    })

    await store.refreshJob()
    await store.activate()
    expect(store.version?.status).toBe('ACTIVE')
    await store.retire()
    expect(store.version?.status).toBe('RETIRED')

    store.resetSession()
    expect(store.sources).toEqual([])
    expect(store.version).toBeNull()
  })

  it('对缺失能力、必填项和来源加载异常 fail closed', async () => {
    const store = useAiKnowledgeStore()
    setAiClient({})

    await expect(store.loadSources()).rejects.toThrow('不支持知识管理')
    await expect(store.selectSource('source-1')).rejects.toThrow('不支持知识详情')
    await expect(store.createSource({ name: 'x', classification: 'L1', matchMode: 'ANY', permissions: [] }))
      .rejects.toThrow('不支持创建知识来源')
    await expect(store.ingestText({ externalKey: 'x', title: 'x', version: 'v1' }))
      .rejects.toThrow('请先选择知识来源')

    store.selectedSourceId = 'source-1'
    await expect(store.ingestText({ externalKey: 'x', title: 'x', version: 'v1' }))
      .rejects.toThrow('知识正文不能为空')
    store.uploadText = '正文'
    await expect(store.ingestText({ externalKey: 'x', title: 'x', version: 'v1' }))
      .rejects.toThrow('不支持知识摄取')

    setAiClient({ listKnowledgeSources: async () => { throw new Error('来源接口失败') } })
    await expect(store.loadSources()).rejects.toThrow('来源接口失败')
    expect(store.error).toBe('来源接口失败')
    expect(store.loading).toBe(false)

    setAiClient({ listKnowledgeSources: async () => { throw '非 Error 失败' } })
    let caught: unknown
    try { await store.loadSources() } catch (error) { caught = error }
    expect(caught).toBe('非 Error 失败')
    expect(store.error).toBe('知识来源加载失败')
  })

  it('拒绝上传完成证明不一致并始终恢复 saving 状态', async () => {
    const store = useAiKnowledgeStore()
    store.selectedSourceId = 'source-1'
    store.uploadText = '维修处理要求'

    setAiClient(completeKnowledgeClient({
      finalizeKnowledgeUpload: vi.fn().mockResolvedValue({
        id: upload.id, state: 'QUARANTINED', observedSha256: upload.sha256, observedSizeBytes: 12,
      }),
    }))
    await expect(store.ingestText({ externalKey: 'x', title: 'x', version: 'v1' }))
      .rejects.toThrow('知识上传完成证明不一致')
    expect(store.saving).toBe(false)
    expect(store.error).toBe('知识上传完成证明不一致')

    setAiClient(completeKnowledgeClient({
      finalizeKnowledgeUpload: vi.fn().mockResolvedValue({
        id: upload.id, state: 'FINALIZED', observedSha256: upload.sha256, observedSizeBytes: 1,
      }),
    }))
    await expect(store.ingestText({ externalKey: 'x', title: 'x', version: 'v1' }))
      .rejects.toThrow('知识上传完成证明不一致')

    setAiClient(completeKnowledgeClient({
      createKnowledgeUpload: vi.fn().mockRejectedValue('非 Error 失败'),
    }))
    let caught: unknown
    try { await store.ingestText({ externalKey: 'x', title: 'x', version: 'v1' }) } catch (error) { caught = error }
    expect(caught).toBe('非 Error 失败')
    expect(store.error).toBe('知识摄取失败')
  })

  it('来源重新选择清空摄取状态，刷新与版本动作在前置条件不足时安全返回', async () => {
    const store = useAiKnowledgeStore()
    const client = completeKnowledgeClient()
    setAiClient(client)
    store.upload = upload
    store.version = version()
    store.job = job()
    store.uploadText = '旧正文'

    await store.selectSource('source-2')
    expect(store.selectedSource?.id).toBe('source-2')
    expect(store.upload).toBeNull()
    expect(store.version).toBeNull()
    expect(store.job).toBeNull()
    expect(store.uploadText).toBe('')

    await store.refreshJob()
    await store.activate()
    await store.retire()
    setAiClient({})
    store.job = job()
    store.version = version()
    await store.refreshJob()
    await store.activate()
    await store.retire()
    expect(store.version.status).toBe('READY')
  })

  it('刷新任务在等待和失败时暴露稳定状态，并恢复 loading', async () => {
    const store = useAiKnowledgeStore()
    const pending = deferred<AiKnowledgeJob>()
    store.job = job()
    store.version = version()
    setAiClient({
      getKnowledgeJob: vi.fn().mockReturnValue(pending.promise),
      getKnowledgeVersion: vi.fn().mockResolvedValue(version()),
    })

    const refresh = store.refreshJob()
    expect((store.$state as unknown as Record<string, unknown>).jobRefreshing).toBe(true)
    pending.reject(new Error('任务查询失败'))

    await expect(refresh).rejects.toThrow('任务查询失败')
    expect(store.error).toBe('任务查询失败')
    expect((store.$state as unknown as Record<string, unknown>).jobRefreshing).toBe(false)
  })

  it('会话重置后忽略刷新任务的迟到错误并清理 loading', async () => {
    const store = useAiKnowledgeStore()
    const pending = deferred<AiKnowledgeJob>()
    store.job = job()
    store.version = version()
    setAiClient({ getKnowledgeJob: vi.fn().mockReturnValue(pending.promise) })

    const refresh = store.refreshJob()
    store.resetSession()
    pending.reject(new Error('旧任务查询失败'))

    await expect(refresh).resolves.toBeUndefined()
    expect(store.error).toBeNull()
    expect((store.$state as unknown as Record<string, unknown>).jobRefreshing).toBe(false)
  })

  it('创建来源失败仍释放 saving，已有选择时加载列表不覆盖选择', async () => {
    const store = useAiKnowledgeStore()
    store.selectedSourceId = 'source-existing'
    setAiClient(completeKnowledgeClient({
      createKnowledgeSource: vi.fn().mockRejectedValue(new Error('创建失败')),
    }))
    await store.loadSources()
    expect(store.selectedSourceId).toBe('source-existing')
    await expect(store.createSource({ name: 'x', classification: 'L1', matchMode: 'ANY', permissions: [] }))
      .rejects.toThrow('创建失败')
    expect(store.saving).toBe(false)

    setAiClient(completeKnowledgeClient({
      listKnowledgeSources: vi.fn().mockResolvedValue({ records: [], total: 0, page: 1, pageSize: 20 }),
    }))
    store.selectedSourceId = ''
    await store.loadSources()
    expect(store.selectedSource).toBeNull()
  })

  it('来源 A/B 乱序选择只落地最后一次响应，并忽略旧选择失败', async () => {
    const store = useAiKnowledgeStore()
    const first = deferred<AiKnowledgeSource>()
    const second = deferred<AiKnowledgeSource>()
    const getKnowledgeSource = vi.fn().mockImplementation((id: string) => (
      id === 'source-a' ? first.promise : second.promise
    ))
    setAiClient({ getKnowledgeSource })

    const selectFirst = store.selectSource('source-a')
    const selectSecond = store.selectSource('source-b')
    expect(store.selectedSourceId).toBe('source-b')

    second.resolve(source('source-b'))
    await selectSecond
    first.reject(new Error('旧来源失败'))
    await expect(selectFirst).resolves.toBeUndefined()

    expect(store.selectedSourceId).toBe('source-b')
    expect(store.selectedSource?.id).toBe('source-b')
    expect(store.error).toBeNull()
    expect(store.selecting).toBe(false)
  })

  it('来源详情响应身份不匹配时 fail closed', async () => {
    const store = useAiKnowledgeStore()
    setAiClient({ getKnowledgeSource: vi.fn().mockResolvedValue(source('source-other')) })

    await expect(store.selectSource('source-requested')).rejects.toThrow('知识来源详情响应与选择不匹配')
    expect(store.selectedSourceId).toBe('source-requested')
    expect(store.selectedSource).toBeNull()
  })

  it('会话重置使迟到的来源列表和详情响应失效', async () => {
    const store = useAiKnowledgeStore()
    const listPending = deferred<{ records: AiKnowledgeSource[]; total: number; page: number; pageSize: number }>()
    setAiClient({
      listKnowledgeSources: vi.fn().mockReturnValue(listPending.promise),
      getKnowledgeSource: vi.fn().mockResolvedValue(source('source-old')),
    })

    const load = store.loadSources()
    store.resetSession()
    listPending.resolve({ records: [source('source-old')], total: 1, page: 1, pageSize: 20 })
    await load
    expect(store.sources).toEqual([])
    expect(store.selectedSource).toBeNull()
    expect(store.loading).toBe(false)

    const detailPending = deferred<AiKnowledgeSource>()
    setAiClient({ getKnowledgeSource: vi.fn().mockReturnValue(detailPending.promise) })
    const select = store.selectSource('source-old')
    store.resetSession()
    detailPending.resolve(source('source-old'))
    await select
    expect(store.selectedSourceId).toBe('')
    expect(store.selectedSource).toBeNull()
    expect(store.selecting).toBe(false)
  })

  it('来源更新绑定提交时的 source 与 ACL，迟到响应不覆盖新选择', async () => {
    const store = useAiKnowledgeStore()
    const updatePending = deferred<AiKnowledgeSource>()
    const updateKnowledgeSource = vi.fn().mockReturnValue(updatePending.promise)
    setAiClient({ updateKnowledgeSource })
    store.sources = [source('source-a'), source('source-b')]
    store.selectedSourceId = 'source-a'
    store.selectedSource = source('source-a')

    const update = store.updateSelectedSource({ name: '来源 A 已更新' })
    store.selectedSourceId = 'source-b'
    store.selectedSource = source('source-b')
    updatePending.resolve({ ...source('source-a'), name: '来源 A 已更新', aclVersion: 2 })
    await update

    expect(updateKnowledgeSource).toHaveBeenCalledWith('source-a', {
      expectedAclVersion: 1,
      name: '来源 A 已更新',
    })
    expect(store.sources.find((item) => item.id === 'source-a')?.name).toBe('来源 A 已更新')
    expect(store.selectedSourceId).toBe('source-b')
    expect(store.selectedSource?.id).toBe('source-b')
  })

  it('摄取全链固定使用启动时的来源，切换后不把版本状态回填到新来源', async () => {
    const store = useAiKnowledgeStore()
    const uploadPending = deferred<AiKnowledgeUpload>()
    const createKnowledgeUpload = vi.fn().mockReturnValue(uploadPending.promise)
    const createKnowledgeVersion = vi.fn().mockResolvedValue({ versionId: 'version-a', jobId: 'job-a', state: 'PENDING' })
    setAiClient(completeKnowledgeClient({
      createKnowledgeUpload,
      createKnowledgeVersion,
      getKnowledgeVersion: vi.fn().mockResolvedValue({ ...version(), id: 'version-a', sourceId: 'source-a' }),
      getKnowledgeJob: vi.fn().mockResolvedValue({ ...job(), id: 'job-a', versionId: 'version-a' }),
    }))
    store.selectedSourceId = 'source-a'
    store.selectedSource = source('source-a')
    store.uploadText = '维修处理要求'

    const ingest = store.ingestText({ externalKey: 'repair', title: '维修制度', version: 'v1' })
    await vi.waitFor(() => expect(createKnowledgeUpload).toHaveBeenCalled())
    store.selectedSourceId = 'source-b'
    store.selectedSource = source('source-b')
    uploadPending.resolve(upload)
    await ingest

    expect(createKnowledgeVersion).toHaveBeenCalledWith('source-a', {
      uploadSessionId: 'upload-1', externalKey: 'repair', title: '维修制度', version: 'v1',
    })
    expect(store.selectedSource?.id).toBe('source-b')
    expect(store.upload).toBeNull()
    expect(store.version).toBeNull()
    expect(store.job).toBeNull()
  })

  it.each([
    ['activate', 'activateKnowledgeVersion', 'ACTIVE'],
    ['retire', 'retireKnowledgeVersion', 'RETIRED'],
  ] as const)('%s 响应不修改切换后的版本', async (action, clientMethod, _status) => {
    const store = useAiKnowledgeStore()
    const pending = deferred<void>()
    const client = completeKnowledgeClient({ [clientMethod]: vi.fn().mockReturnValue(pending.promise) })
    setAiClient(client)
    store.selectedSourceId = 'source-a'
    store.selectedSource = source('source-a')
    store.version = { ...version(), id: 'version-a', sourceId: 'source-a', status: action === 'activate' ? 'READY' : 'ACTIVE' }

    const mutation = store[action]()
    store.selectedSourceId = 'source-b'
    store.selectedSource = source('source-b')
    store.version = { ...version(), id: 'version-b', sourceId: 'source-b', status: 'READY' }
    pending.resolve()
    await mutation

    expect(client[clientMethod]).toHaveBeenCalledWith('version-a')
    expect(store.version).toMatchObject({ id: 'version-b', sourceId: 'source-b', status: 'READY' })
  })

  it('公开审批绑定提交时的固定版本，切换后不会刷新其他版本', async () => {
    const store = useAiKnowledgeStore()
    const approvePending = deferred<void>()
    const approvePublicKnowledgeVersion = vi.fn().mockReturnValue(approvePending.promise)
    const getKnowledgeVersion = vi.fn().mockImplementation(async (id: string) => ({
      ...version(), id, sourceId: id === 'version-a' ? 'source-a' : 'source-b',
    }))
    setAiClient(completeKnowledgeClient({ approvePublicKnowledgeVersion, getKnowledgeVersion }))
    store.selectedSourceId = 'source-a'
    store.selectedSource = source('source-a')
    store.version = { ...version(), id: 'version-a', sourceId: 'source-a' }

    const approve = store.approvePublic('current-password')
    store.selectedSourceId = 'source-b'
    store.selectedSource = source('source-b')
    store.version = { ...version(), id: 'version-b', sourceId: 'source-b' }
    approvePending.resolve()
    await approve

    expect(approvePublicKnowledgeVersion).toHaveBeenCalledWith('version-a', expect.objectContaining({
      password: 'current-password',
    }))
    expect(getKnowledgeVersion).toHaveBeenCalledWith('version-a')
    expect(store.version).toMatchObject({ id: 'version-b', sourceId: 'source-b' })
  })
})

describe('AI approval and audit store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    setAiClient(undefined)
  })

  it('覆盖提案审批、详情插入/替换、审计正文、成本和人工对账全链', async () => {
    const pending = proposal()
    const approved = { ...pending, state: 'approved' as const, executionState: 'succeeded' as const }
    let reconfirmed = false
    const client: AiClient = {
      listProposals: vi.fn().mockResolvedValue({ records: [pending], total: 1, page: 1, pageSize: 20 }),
      getProposal: vi.fn().mockImplementation(async (id: string) => reconfirmed
        ? { ...proposal(id), state: 'failed' as const, executionState: 'failed' as const }
        : proposal(id)),
      approveProposal: vi.fn().mockResolvedValue(approved),
      rejectProposal: vi.fn().mockResolvedValue(undefined),
      listAuditRuns: vi.fn().mockResolvedValue({ records: [auditRun], total: 1, page: 1, pageSize: 20 }),
      getAuditRun: vi.fn().mockResolvedValue(auditDetail),
      readAuditContent: vi.fn().mockResolvedValue(auditContent),
      getAuditCosts: vi.fn().mockResolvedValue({ records: [auditCost], total: 1, page: 1, pageSize: 50 }),
      reconfirmExecution: vi.fn().mockImplementation(async (id: string) => {
        reconfirmed = true
        return { proposalId: id.replace('execution-', ''), executionId: id,
          state: 'FAILED' as const, version: 2, resultHash: null }
      }),
    }
    setAiClient(client)
    const store = useAiApprovalStore()

    await store.loadProposals()
    expect(store.selectedId).toBe(pending.id)
    await store.approve('同意', 'step-up')
    expect(store.selected?.state).toBe('approved')

    await store.selectProposal(pending.id)
    expect(store.proposals).toHaveLength(1)
    await store.selectProposal('proposal-new')
    expect(store.proposals[0]?.id).toBe('proposal-new')

    await store.reject('证据不足')
    expect(store.selected?.state).toBe('rejected')
    await store.refresh()
    expect(store.selected?.state).toBe('pending_approval')

    await store.loadAudits()
    await store.selectAudit(auditRun.id)
    expect(store.auditDetail).toEqual(auditDetail)
    expect(await store.loadAuditContent('事故复核', 'step-up')).toEqual(auditContent)
    expect('auditContent' in store.$state).toBe(false)
    await store.loadCosts()
    expect(store.costs).toEqual([auditCost])
    const reconfirmTarget = store.selected!
    await store.reconfirm('CONFLICT', '业务侧确认状态冲突', 'current-password')
    expect(store.selected?.executionState).toBe('failed')
    expect(client.reconfirmExecution).toHaveBeenCalledWith(reconfirmTarget.executionId, expect.objectContaining({
      payloadHash: reconfirmTarget.payloadHash,
      businessSnapshotHash: reconfirmTarget.businessSnapshotHash,
      resolution: 'CONFLICT',
      password: 'current-password',
    }))

    store.resetSession()
    expect(store.proposals).toEqual([])
    expect(store.auditDetail).toBeNull()
  })

  it('提案 mutation pending 时阻止重复拒绝，并保留可见失败状态', async () => {
    const store = useAiApprovalStore()
    const target = proposal('proposal-reject-lock')
    const pending = deferred<void>()
    const rejectProposal = vi.fn().mockReturnValue(pending.promise)
    store.proposals = [target]
    store.selectedId = target.id
    setAiClient({ rejectProposal })

    const first = store.reject('证据不足')
    const duplicate = store.reject('重复点击')

    expect(rejectProposal).toHaveBeenCalledTimes(1)
    expect((store.$state as unknown as Record<string, unknown>).proposalMutation).toBe('reject')

    pending.reject(new Error('拒绝接口失败'))
    const results = await Promise.allSettled([first, duplicate])

    expect(results[0]).toMatchObject({ status: 'rejected' })
    expect(results[1]).toMatchObject({ status: 'fulfilled', value: false })
    expect(store.selected?.state).toBe('pending_approval')
    expect((store.$state as unknown as Record<string, unknown>).proposalMutation).toBeNull()
    expect((store.$state as unknown as Record<string, unknown>).mutationError).toBe('拒绝接口失败')
  })

  it('刷新失败不覆盖当前提案，并暴露稳定失败状态', async () => {
    const store = useAiApprovalStore()
    const target = proposal('proposal-refresh-error')
    store.proposals = [target]
    store.selectedId = target.id
    setAiClient({ getProposal: vi.fn().mockRejectedValue(new Error('刷新接口失败')) })

    await expect(store.refresh()).rejects.toThrow('刷新接口失败')

    expect(store.selected).toEqual(target)
    expect((store.$state as unknown as Record<string, unknown>).proposalMutation).toBeNull()
    expect((store.$state as unknown as Record<string, unknown>).mutationError).toBe('刷新接口失败')
  })

  it('会话重置使迟到的批准响应不能覆盖新会话中的同 ID 提案', async () => {
    const store = useAiApprovalStore()
    const target = proposal('proposal-approve-session')
    const pending = deferred<AiProposalPreview>()
    store.proposals = [target]
    store.selectedId = target.id
    setAiClient({ approveProposal: vi.fn().mockReturnValue(pending.promise) })

    const action = store.approve('同意', 'step-up')
    store.resetSession()
    const current = { ...target, title: '新会话提案' }
    store.proposals = [current]
    store.selectedId = current.id
    pending.resolve({ ...target, title: '迟到批准结果', state: 'approved' })
    await action

    expect(store.selected).toEqual(current)
  })

  it('会话重置使迟到的拒绝响应不能修改新会话中的同 ID 提案', async () => {
    const store = useAiApprovalStore()
    const target = proposal('proposal-reject-session')
    const pending = deferred<void>()
    store.proposals = [target]
    store.selectedId = target.id
    setAiClient({ rejectProposal: vi.fn().mockReturnValue(pending.promise) })

    const action = store.reject('拒绝')
    store.resetSession()
    const current = { ...target, title: '新会话提案' }
    store.proposals = [current]
    store.selectedId = current.id
    pending.resolve()
    await action

    expect(store.selected).toEqual(current)
  })

  it('会话重置使迟到的刷新响应不能覆盖新会话中的同 ID 提案', async () => {
    const store = useAiApprovalStore()
    const target = proposal('proposal-refresh-session')
    const pending = deferred<AiProposalPreview>()
    store.proposals = [target]
    store.selectedId = target.id
    setAiClient({ getProposal: vi.fn().mockReturnValue(pending.promise) })

    const action = store.refresh()
    store.resetSession()
    const current = { ...target, title: '新会话提案' }
    store.proposals = [current]
    store.selectedId = current.id
    pending.resolve({ ...target, title: '迟到刷新结果', state: 'expired' })
    await action

    expect(store.selected).toEqual(current)
  })

  it('会话重置后迟到的人工对账不会继续读取或覆盖提案', async () => {
    const store = useAiApprovalStore()
    const target = proposal('proposal-reconfirm-session')
    const pending = deferred<{
      proposalId: string
      executionId: string
      state: 'FAILED'
      version: number
      resultHash: null
    }>()
    const getProposal = vi.fn().mockResolvedValue({ ...target, state: 'failed' as const })
    store.proposals = [target]
    store.selectedId = target.id
    setAiClient({ reconfirmExecution: vi.fn().mockReturnValue(pending.promise), getProposal })

    const action = store.reconfirm('CONFLICT', '业务事实已确认冲突', 'current-password')
    store.resetSession()
    const current = { ...target, title: '新会话提案' }
    store.proposals = [current]
    store.selectedId = current.id
    pending.resolve({
      proposalId: target.id,
      executionId: target.executionId!,
      state: 'FAILED',
      version: 2,
      resultHash: null,
    })
    await action

    expect(getProposal).not.toHaveBeenCalled()
    expect(store.selected).toEqual(current)
  })

  it('会话重置使迟到的成本响应失效', async () => {
    const store = useAiApprovalStore()
    const pending = deferred<{ records: AiAuditCost[]; total: number; page: number; pageSize: number }>()
    setAiClient({ getAuditCosts: vi.fn().mockReturnValue(pending.promise) })

    const action = store.loadCosts()
    store.resetSession()
    pending.resolve({ records: [auditCost], total: 1, page: 1, pageSize: 50 })
    await action

    expect(store.costs).toEqual([])
  })

  it('错误使用稳定消息，缺失 client 能力和前置状态时不产生副作用', async () => {
    const store = useAiApprovalStore()
    setAiClient({ listProposals: async () => { throw new Error('审批接口失败') } })
    await store.loadProposals()
    expect(store.error).toBe('审批接口失败')
    expect(store.loading).toBe(false)

    setAiClient({ listProposals: async () => { throw '非 Error 失败' } })
    await store.loadProposals()
    expect(store.error).toBe('审批方案加载失败')

    setAiClient({})
    await store.loadProposals()
    await store.approve()
    await store.selectProposal('missing')
    await store.reject()
    await store.refresh()
    await store.loadAudits()
    await store.selectAudit('missing')
    await store.loadAuditContent('reason', 'proof')
    await store.loadCosts()
    await store.reconfirm('UNKNOWN', '确认仍需人工复核', 'password')
    store.replace(proposal('unknown'))
    expect(store.proposals).toEqual([])

    store.selectedAuditId = 'run-1'
    await store.loadAuditContent('reason', 'proof')
    store.proposals = [proposal()]
    store.selectedId = 'proposal-1'
    await store.approve()
    await store.reject()
    await store.refresh()
    await store.reconfirm('CONFLICT', '确认业务状态冲突', 'password')
    expect(store.proposals[0]?.state).toBe('pending_approval')
  })

  it('空列表不选择提案，预先选择的提案不会被 load 覆盖', async () => {
    const store = useAiApprovalStore()
    setAiClient({
      listProposals: vi.fn()
        .mockResolvedValueOnce({ records: [], total: 0, page: 1, pageSize: 20 })
        .mockResolvedValueOnce({ records: [proposal('proposal-2')], total: 1, page: 1, pageSize: 20 }),
    })
    await store.loadProposals()
    expect(store.selectedId).toBe('')
    store.selectedId = 'proposal-existing'
    await store.loadProposals()
    expect(store.selectedId).toBe('proposal-existing')
  })

  it('提案列表请求乱序完成时只保留最后一次请求的列表、总数和选择', async () => {
    const store = useAiApprovalStore()
    const stale = deferred<{ records: AiProposalPreview[]; total: number; page: number; pageSize: number }>()
    const latest = deferred<{ records: AiProposalPreview[]; total: number; page: number; pageSize: number }>()
    setAiClient({
      listProposals: vi.fn().mockImplementation((input = {}) => input.page === 1 ? stale.promise : latest.promise),
    })

    const staleLoad = store.loadProposals({ page: 1, pageSize: 20 }, { reconcileSelection: true })
    const latestLoad = store.loadProposals({ page: 2, pageSize: 20 }, { reconcileSelection: true })
    latest.resolve({ records: [proposal('proposal-latest')], total: 21, page: 2, pageSize: 20 })
    await latestLoad

    expect(store.proposals.map((item) => item.id)).toEqual(['proposal-latest'])
    expect(store.proposalTotal).toBe(21)
    expect(store.selectedId).toBe('proposal-latest')

    stale.resolve({ records: [proposal('proposal-stale')], total: 1, page: 1, pageSize: 20 })
    await staleLoad

    expect(store.proposals.map((item) => item.id)).toEqual(['proposal-latest'])
    expect(store.proposalTotal).toBe(21)
    expect(store.selectedId).toBe('proposal-latest')
  })

  it('提案详情 A/B 请求乱序完成时迟到 A 不能覆盖最新 B', async () => {
    const store = useAiApprovalStore()
    const pendingA = deferred<AiProposalPreview>()
    const pendingB = deferred<AiProposalPreview>()
    const summaryA = proposal('proposal-a')
    const summaryB = proposal('proposal-b')
    store.proposals = [summaryA, summaryB]
    store.selectedId = summaryA.id
    setAiClient({
      getProposal: vi.fn().mockImplementation((id: string) => id === summaryA.id ? pendingA.promise : pendingB.promise),
    })

    const selectA = store.selectProposal(summaryA.id)
    const selectB = store.selectProposal(summaryB.id)
    pendingB.resolve({ ...summaryB, title: '最新提案 B', state: 'approved' })
    await selectB

    expect(store.selectedId).toBe(summaryB.id)
    expect(store.selected).toMatchObject({ id: summaryB.id, title: '最新提案 B', state: 'approved' })
    expect(store.loading).toBe(false)

    pendingA.resolve({ ...summaryA, title: '迟到提案 A', state: 'expired' })
    await selectA

    expect(store.selectedId).toBe(summaryB.id)
    expect(store.selected).toMatchObject({ id: summaryB.id, title: '最新提案 B', state: 'approved' })
    expect(store.proposals.find((item) => item.id === summaryA.id)?.title).toBe(summaryA.title)
    expect(store.loading).toBe(false)
    expect(store.error).toBeNull()
  })

  it('提案详情最新请求失败时保留原选择，迟到成功不能清除错误或提前结束 loading', async () => {
    const store = useAiApprovalStore()
    const pendingA = deferred<AiProposalPreview>()
    const pendingB = deferred<AiProposalPreview>()
    const summaryA = proposal('proposal-a')
    const summaryB = proposal('proposal-b')
    store.proposals = [summaryA, summaryB]
    store.selectedId = summaryA.id
    store.error = '旧错误'
    setAiClient({
      getProposal: vi.fn().mockImplementation((id: string) => id === summaryA.id ? pendingA.promise : pendingB.promise),
    })

    const selectA = store.selectProposal(summaryA.id)
    const selectB = store.selectProposal(summaryB.id)
    expect(store.loading).toBe(true)
    expect(store.error).toBeNull()

    pendingA.resolve({ ...summaryA, title: '迟到提案 A' })
    await selectA
    expect(store.loading).toBe(true)
    expect(store.selectedId).toBe(summaryA.id)

    pendingB.reject(new Error('提案 B 加载失败'))
    await expect(selectB).rejects.toThrow('提案 B 加载失败')

    expect(store.loading).toBe(false)
    expect(store.selectedId).toBe(summaryA.id)
    expect(store.proposals.find((item) => item.id === summaryA.id)?.title).toBe(summaryA.title)
    expect(store.error).toBe('提案 B 加载失败')
  })

  it('提案详情响应的 id 与请求选择不一致时拒绝落地', async () => {
    const store = useAiApprovalStore()
    const requested = proposal('proposal-requested')
    store.proposals = [requested]
    store.selectedId = requested.id
    setAiClient({ getProposal: vi.fn().mockResolvedValue(proposal('proposal-other')) })

    await expect(store.selectProposal(requested.id)).rejects.toThrow('提案详情响应与选择不匹配')

    expect(store.selectedId).toBe(requested.id)
    expect(store.proposals).toEqual([requested])
    expect(store.loading).toBe(false)
    expect(store.error).toBe('提案详情响应与选择不匹配')
  })

  it('审计列表 A/B 请求乱序完成时迟到 A 不能覆盖最新 B 的分页和选择', async () => {
    const store = useAiApprovalStore()
    const pendingA = deferred<{ records: AiAuditRun[]; total: number; page: number; pageSize: number }>()
    const pendingB = deferred<{ records: AiAuditRun[]; total: number; page: number; pageSize: number }>()
    const detailB = auditDetailFor('run-b')
    store.audits = [detailB.run]
    store.selectedAuditId = detailB.run.id
    store.auditDetail = detailB
    setAiClient({
      listAuditRuns: vi.fn().mockImplementation((input = {}) => input.page === 1 ? pendingA.promise : pendingB.promise),
    })

    const loadA = store.loadAudits({ page: 1, pageSize: 10 }, { reconcileSelection: true })
    const loadB = store.loadAudits({ page: 2, pageSize: 5 }, { reconcileSelection: true })
    pendingB.resolve({ records: [detailB.run], total: 42, page: 2, pageSize: 5 })
    await loadB

    expect(store.audits.map((item) => item.id)).toEqual(['run-b'])
    expect(store.auditTotal).toBe(42)
    expect(store.auditPage).toBe(2)
    expect(store.auditPageSize).toBe(5)
    expect(store.selectedAuditId).toBe('run-b')
    expect(store.auditDetail).toEqual(detailB)

    pendingA.resolve({ records: [auditDetailFor('run-a').run], total: 1, page: 1, pageSize: 10 })
    await loadA

    expect(store.audits.map((item) => item.id)).toEqual(['run-b'])
    expect(store.auditTotal).toBe(42)
    expect(store.auditPage).toBe(2)
    expect(store.auditPageSize).toBe(5)
    expect(store.selectedAuditId).toBe('run-b')
    expect(store.auditDetail).toEqual(detailB)
  })

  it('会话重置后迟到的审计列表响应不得重新灌入状态', async () => {
    const store = useAiApprovalStore()
    const pending = deferred<{ records: AiAuditRun[]; total: number; page: number; pageSize: number }>()
    setAiClient({ listAuditRuns: vi.fn().mockReturnValue(pending.promise) })

    const load = store.loadAudits({ page: 3, pageSize: 5 })
    store.resetSession()
    pending.resolve({ records: [auditDetailFor('run-stale').run], total: 11, page: 3, pageSize: 5 })
    await load

    expect(store.audits).toEqual([])
    expect(store.auditTotal).toBe(0)
    expect(store.auditPage).toBe(1)
    expect(store.auditPageSize).toBe(20)
    expect(store.selectedAuditId).toBe('')
    expect(store.auditDetail).toBeNull()
  })

  it('审计详情 A/B 请求乱序完成时只保留最新选择及其详情', async () => {
    const store = useAiApprovalStore()
    const pendingA = deferred<AiAuditRunDetail>()
    const pendingB = deferred<AiAuditRunDetail>()
    const detailA = auditDetailFor('run-a')
    const detailB = auditDetailFor('run-b')
    setAiClient({
      getAuditRun: vi.fn().mockImplementation((id: string) => id === 'run-a' ? pendingA.promise : pendingB.promise),
    })

    const selectA = store.selectAudit('run-a')
    const selectB = store.selectAudit('run-b')
    pendingB.resolve(detailB)
    await selectB
    expect(store.selectedAuditId).toBe('run-b')
    expect(store.auditDetail).toEqual(detailB)

    pendingA.resolve(detailA)
    await selectA
    expect(store.selectedAuditId).toBe('run-b')
    expect(store.auditDetail).toEqual(detailB)
  })

  it('提案绑定审计 A/B 请求乱序完成时旧请求不能覆盖当前提案', async () => {
    const store = useAiApprovalStore()
    const pendingA = deferred<AiAuditRunDetail>()
    const pendingB = deferred<AiAuditRunDetail>()
    const detailA = auditDetailFor('run-a')
    const detailB = auditDetailFor('run-b')
    store.proposals = [
      { ...proposal('proposal-a'), runId: 'run-a' },
      { ...proposal('proposal-b'), runId: 'run-b' },
    ]
    setAiClient({
      getAuditRun: vi.fn().mockImplementation((id: string) => id === 'run-a' ? pendingA.promise : pendingB.promise),
    })

    store.selectedId = 'proposal-a'
    const syncA = store.syncSelectedAuditToProposal()
    store.selectedId = 'proposal-b'
    const syncB = store.syncSelectedAuditToProposal()

    pendingB.resolve(detailB)
    await syncB
    expect(store.selectedAuditId).toBe('run-b')
    expect(store.auditDetail).toEqual(detailB)

    pendingA.resolve(detailA)
    await syncA
    expect(store.selectedId).toBe('proposal-b')
    expect(store.selectedAuditId).toBe('run-b')
    expect(store.auditDetail).toEqual(detailB)
    expect(store.audits.map((item) => item.id)).toEqual(['run-b'])
  })

  it('审计详情选择 A→B→A 时旧 A 响应不能覆盖最新 A 详情', async () => {
    const store = useAiApprovalStore()
    const firstA = deferred<AiAuditRunDetail>()
    const pendingB = deferred<AiAuditRunDetail>()
    const secondA = deferred<AiAuditRunDetail>()
    const staleA = auditDetailFor('run-a', 'chain-stale-a')
    const detailB = auditDetailFor('run-b')
    const latestA = auditDetailFor('run-a', 'chain-latest-a')
    let aRequestCount = 0
    setAiClient({
      getAuditRun: vi.fn().mockImplementation((id: string) => {
        if (id === 'run-b') return pendingB.promise
        aRequestCount += 1
        return aRequestCount === 1 ? firstA.promise : secondA.promise
      }),
    })

    const selectFirstA = store.selectAudit('run-a')
    const selectB = store.selectAudit('run-b')
    const selectSecondA = store.selectAudit('run-a')
    secondA.resolve(latestA)
    await selectSecondA
    pendingB.resolve(detailB)
    await selectB
    firstA.resolve(staleA)
    await selectFirstA

    expect(store.selectedAuditId).toBe('run-a')
    expect(store.auditDetail).toEqual(latestA)
  })

  it('审计详情响应的 run.id 与请求选择不一致时拒绝落地', async () => {
    const store = useAiApprovalStore()
    setAiClient({ getAuditRun: vi.fn().mockResolvedValue(auditDetailFor('run-b')) })

    await expect(store.selectAudit('run-a')).rejects.toThrow('审计详情响应与选择不匹配')

    expect(store.selectedAuditId).toBe('run-a')
    expect(store.auditDetail).toBeNull()
  })
})
