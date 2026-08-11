import { afterEach, describe, expect, it, vi } from 'vitest'
import { DemoAiClient } from './ai-demo'

describe('deterministic AI demo client', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('返回带口径和引用的驾驶舱结果', async () => {
    const result = await new DemoAiClient().queryDashboard({ question: '本周有哪些运营风险？' })
    expect(result.evidence.grounded).toBe(true)
    expect(result.evidence.basis).toBe('deterministic')
    expect(result.evidence.confidence).toBeUndefined()
    expect(result.evidence.citations.length).toBeGreaterThan(0)
    expect(result.metricVersion).toBe('dashboard-metrics.v1')
    expect(result.intent).toEqual({
      metricIds: ['repair.pending.count', 'hygiene.failed.count', 'payment.unpaid.count'],
      dateRange: { preset: 'LAST_7_DAYS' },
      dimensions: [],
      filters: {},
      presentationHint: 'TABLE',
    })
    expect(result.queryParameters.dateRange).toMatchObject({ preset: 'LAST_7_DAYS' })
    expect(result.metrics['repair.pending.count']).toMatchObject({ metricVersion: 'v1', value: 2 })
  })

  it.each([
    ['success', { state: 'succeeded', grounded: true, confidence: 0.92, risks: 4 }],
    ['low-confidence', { state: 'succeeded', grounded: true, confidence: 0.58, risks: 4 }],
    ['no-source', { state: 'succeeded', grounded: false, confidence: undefined, risks: 0 }],
    ['degraded', { state: 'degraded', grounded: true, confidence: 0.72, risks: 4 }],
  ] as const)('Dashboard 视觉 fixture %s 保持 typed 结果与可信证据边界', async (scenario, expected) => {
    const result = await new DemoAiClient(undefined, scenario).queryDashboard({ question: '本周风险' })
    expect(result).toMatchObject({ state: expected.state })
    expect(result.evidence).toMatchObject({ grounded: expected.grounded })
    expect(result.evidence.confidence).toBe(expected.confidence)
    expect(result.riskCounts).toHaveLength(expected.risks)
    if (!expected.grounded) {
      expect(result.evidence.citations).toHaveLength(0)
      expect(result.pendingApprovals).toBe(0)
    }
  })

  it.each([
    ['failed', 'AI 驾驶舱查询失败，请稍后重试'],
    ['timed-out', 'AI 驾驶舱查询超时，请稍后重试'],
  ] as const)('Dashboard 视觉 fixture %s 通过 client 失败合同进入错误态', async (scenario, message) => {
    await expect(new DemoAiClient(undefined, scenario).queryDashboard({ question: '本周风险' }))
      .rejects.toThrow(message)
  })

  it('维修候选仅从调用方提供的确定性列表选择', async () => {
    const result = await new DemoAiClient().triageRepair({
      repairId: 12,
      status: '待处理',
      descriptionRedacted: '插座无电',
      candidates: [{ userId: 9, displayName: '维修员 A' }],
    })
    expect(result.assignmentCandidateUserId).toBe(9)
    expect(result.assignmentCandidateName).toBe('维修员 A')
  })

  it('检测手机号后阻断公告正文且不发起网络请求', async () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)
    const result = await new DemoAiClient().draftNotice({
      points: '如有疑问请联系 13800000000',
      type: '安全卫生',
      tone: '正式',
      audience: '全体学生',
    })
    expect(result.blocked).toBe(true)
    expect(result.content).toBe('')
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it('公告正常生成强制为草稿，并阻断 HTML 内容', async () => {
    const client = new DemoAiClient()
    const draft = await client.draftNotice({ points: '周五开展安全巡检', type: '安全卫生', tone: '温和', audience: '全体学生' })
    const blocked = await client.draftNotice({ points: '<script>alert(1)</script>', type: '安全卫生', tone: '紧急', audience: '全体学生' })
    expect(draft).toMatchObject({ status: '草稿', blocked: false, state: 'succeeded' })
    expect(draft.evidence).toMatchObject({ basis: 'deterministic', grounded: true })
    expect(draft.evidence.confidence).toBeUndefined()
    expect(draft.content).toContain('周五开展安全巡检')
    expect(blocked).toMatchObject({ blocked: true, content: '', state: 'failed' })
  })

  it('助手输出完整流式、引用和完成事件，并支持取消', async () => {
    const client = new DemoAiClient()
    const types: string[] = []
    let completed: Record<string, unknown> | undefined
    for await (const event of client.startConversation({ text: '维修时限', surface: 'GLOBAL' })) {
      types.push(event.type)
      if (event.type === 'run.completed') completed = event.payload
    }
    expect(types).toEqual(['run.started', 'tool.started', 'message.delta', 'citation.added', 'citation.added', 'run.completed'])
    expect(completed).toMatchObject({
      basis: 'deterministic', grounded: true,
      messageId: expect.stringMatching(/^[0-9a-f-]{36}$/),
    })
    expect(completed?.confidence).toBeUndefined()

    const controller = new AbortController()
    controller.abort()
    const cancelled = async () => {
      for await (const _event of client.startConversation({ text: '取消', surface: 'GLOBAL' }, controller.signal)) { /* consume */ }
    }
    await expect(cancelled()).rejects.toMatchObject({ name: 'AbortError' })
  })

  it.each([
    ['low-confidence', 'run.completed', { grounded: true, confidence: 0.52 }],
    ['no-source', 'run.completed', { grounded: false }],
    ['revoked', 'run.completed', { grounded: true }],
    ['degraded', 'run.completed', { grounded: true }],
    ['failed', 'run.failed', { safeMessage: '授权资料检索失败，请稍后重试' }],
    ['timed-out', 'run.timed_out', { safeMessage: '生成超时，可重试本次请求' }],
  ] as const)('视觉 fixture %s 仍通过真实 run event 合同产生状态', async (scenario, terminalType, terminalPayload) => {
    const events = []
    for await (const event of new DemoAiClient(scenario).startConversation({
      text: '维修申请的处理时限是什么？', surface: 'GLOBAL',
    })) events.push(event)

    expect(events[0]?.type).toBe('run.started')
    expect(events.at(-1)?.type).toBe(terminalType)
    expect(events.at(-1)?.payload).toMatchObject(terminalPayload)
    if (scenario === 'revoked') {
      expect(events.filter((event) => event.type === 'citation.added').map((event) => event.payload.citation))
        .toContainEqual(expect.objectContaining({ access: 'denied' }))
    }
    if (scenario === 'degraded') expect(events.map((event) => event.type)).toContain('run.degraded')
  })

  it('streaming 视觉 fixture 可在 message.delta 后由 AbortSignal 停止', async () => {
    const controller = new AbortController()
    const types: string[] = []
    const consume = async () => {
      for await (const event of new DemoAiClient('streaming').startConversation({
        text: '维修申请的处理时限是什么？', surface: 'GLOBAL',
      }, controller.signal)) {
        types.push(event.type)
        if (event.type === 'message.delta') controller.abort()
      }
    }

    await expect(consume()).rejects.toMatchObject({ name: 'AbortError' })
    expect(types).toEqual(['run.started', 'tool.started', 'message.delta'])
  })

  it('演示会话也支持真实列表、切换恢复和稳定 messageId 反馈语义', async () => {
    const client = new DemoAiClient()
    let conversationId = ''
    let messageId = ''
    for await (const event of client.startConversation({
      text: '维修时限', surface: 'GLOBAL',
      onConversationReady: (id) => { conversationId = id },
    })) {
      if (event.type === 'run.completed') messageId = String(event.payload.messageId ?? '')
    }

    const conversations = await client.listConversations({ page: 1, pageSize: 20 })
    const detail = await client.getConversation(conversationId)
    await expect(client.submitFeedback(messageId, {
      rating: 1, tags: ['helpful'], comment: undefined,
    })).resolves.toBeUndefined()

    expect(conversations.records[0]).toMatchObject({ id: conversationId, title: '维修时限' })
    expect(detail.conversation.id).toBe(conversationId)
    expect(detail.messages.at(-1)?.id).toBe(messageId)
  })

  it('确定性维修结果不伪造模型置信度', async () => {
    const result = await new DemoAiClient().triageRepair({ repairId: 1, status: '待处理', descriptionRedacted: '家具损坏', candidates: [] })
    expect(result).toMatchObject({ category: '综合维修', assignmentCandidateUserId: null, assignmentCandidateName: null })
    expect(result.evidence).toMatchObject({ basis: 'deterministic', grounded: true })
    expect(result.evidence.confidence).toBeUndefined()
  })

  it('风险列表筛选、人工事件追加且未知 ID 拒绝', async () => {
    const client = new DemoAiClient()
    const list = await client.listRiskCases({ type: '维修风险', state: 'open' })
    expect(list.total).toBe(4)
    expect(list.records.map((risk) => risk.id)).toEqual([
      'risk-001',
      'risk-010',
      'risk-014',
      'risk-021',
    ])
    expect(list.records.every((risk) => risk.type === '维修风险' && risk.state === 'open')).toBe(true)
    const keywordResult = await client.listRiskCases({ keyword: '卫生检查不合格' })
    expect(keywordResult.records.map((risk) => risk.type)).toEqual(['卫生风险'])
    const acknowledged = await client.updateRiskCase(list.records[0]!.id, 'acknowledge', {
      caseVersion: list.records[0]!.caseVersion,
      detail: '已核验维修积压并通知值班组跟进',
    })
    expect(acknowledged.events.filter((event) => event.detail === '已核验维修积压并通知值班组跟进')).toHaveLength(1)
    expect(acknowledged.humanEvidence.events.filter((event) => event.detail === '已核验维修积压并通知值班组跟进')).toHaveLength(1)
    const resolved = await client.updateRiskCase(list.records[0]!.id, 'resolve')
    const dismissed = await client.updateRiskCase(list.records[0]!.id, 'dismiss')
    expect(acknowledged.state).toBe('acknowledged')
    expect(resolved.state).toBe('resolved')
    expect(dismissed.state).toBe('dismissed')
    expect(dismissed.events.at(-1)?.detail).toContain('未写入真实业务系统')
    await expect(client.updateRiskCase('missing', 'resolve')).rejects.toThrow('风险案例不可见')
  })

  it('提案可查询、批准、拒绝、重新读取，非法方案安全失败', async () => {
    const client = new DemoAiClient()
    const list = await client.listProposals({ state: 'pending_approval' })
    expect(list.records).toHaveLength(2)
    const notices = await client.listProposals({
      state: 'pending_approval',
      actionType: 'NOTICE_CREATE_DRAFT',
    })
    expect(notices.records).toHaveLength(1)
    expect(notices.total).toBe(1)
    expect(notices.records[0]?.actionType).toBe('NOTICE_CREATE_DRAFT')
    const first = await client.getProposal('proposal-repair-001')
    expect(first.id).toBe('proposal-repair-001')
    expect(first.evidence).toMatchObject({ basis: 'deterministic', grounded: true })
    expect(first.evidence.confidence).toBeUndefined()
    const approved = await client.approveProposal(first.id)
    expect(approved).toMatchObject({ state: 'succeeded', executionState: 'succeeded' })

    await client.rejectProposal('proposal-notice-001')
    expect((await client.getProposal('proposal-notice-001')).state).toBe('rejected')
    const refreshed = await client.getProposal('proposal-expired-001')
    expect(refreshed).toMatchObject({ state: 'expired', version: 2 })
    expect(refreshed.evidence).toMatchObject({ basis: 'unverified', grounded: false })
    expect(refreshed.evidence.confidence).toBeUndefined()

    await expect(client.approveProposal(first.id)).rejects.toThrow('当前方案不可批准')
    await expect(client.getProposal('missing')).rejects.toThrow('方案不可见')
    await expect(client.rejectProposal('missing')).rejects.toThrow('方案不可见')
  })

  it('返回脱敏审计元数据分页', async () => {
    const result = await new DemoAiClient().listAuditRuns({ page: 2, pageSize: 5 })
    expect(result).toMatchObject({ total: 1, page: 2, pageSize: 5 })
    expect(result.records[0]).toMatchObject({ modelAlias: 'demo-model-disabled-provider', chainHash: expect.any(String) })
  })
})
