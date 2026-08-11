import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiRequest } from './client'
import { openAiEventStream } from './ai-sse'
import { HttpAiClient } from './ai-http'

vi.mock('./client', async (importOriginal) => ({
  ...await importOriginal<typeof import('./client')>(),
  apiRequest: vi.fn(),
}))

vi.mock('./ai-sse', () => ({
  openAiEventStream: vi.fn(),
}))

function eventStream(events: Array<{ sequence: number; type: string; payload?: Record<string, unknown> }>) {
  return (async function* () {
    for (const event of events) {
      yield { runId: 'run-1', timestamp: 't', payload: {}, ...event }
    }
  })()
}

const accepted = { runId: 'run-1', eventsUrl: '/api/ai/runs/run-1/events' }
const dashboardResult = {
  id: 'dashboard', summary: '本周平稳', metricVersion: 'v1', riskCounts: [],
  pendingApprovals: 0, evidence: { asOf: 't', citations: [], grounded: true }, state: 'succeeded',
  intent: {
    metricIds: ['repair.pending.count'], dateRange: { preset: 'LAST_7_DAYS' },
    dimensions: [], filters: {}, presentationHint: 'CARD',
  },
  metrics: {
    'repair.pending.count': { value: 1, unit: '项', metricVersion: 'v1', definition: '待维修工单数', rows: [] },
  },
  queryParameters: {
    dateRange: { preset: 'LAST_7_DAYS', from: '2026-07-06', to: '2026-07-12' },
    dimensions: [], filters: {},
  },
}
const repairResult = {
  repairId: 9, category: '水电', urgency: 'HIGH', recommendedTeam: '水电组',
  missingInformation: [], reasoningSummary: '需优先核验', assignmentCandidateUserId: null,
  assignmentCandidateName: null, slaSuggestion: '2 小时',
  evidence: { asOf: 't', citations: [], grounded: true }, state: 'succeeded',
}
const noticeResult = {
  title: '巡检通知', type: '安全卫生', publisher: '当前用户', status: '草稿', content: '正文',
  blocked: false, safetyMessages: [], version: 'v1',
  evidence: { asOf: 't', citations: [], grounded: true }, state: 'succeeded',
}

describe('HttpAiClient', () => {
  beforeEach(() => {
    vi.mocked(apiRequest).mockReset()
    vi.mocked(openAiEventStream).mockReset()
    vi.mocked(apiRequest).mockResolvedValue({ records: [], total: 0, page: 1, pageSize: 10 } as never)
  })

  it.each([
    ['Dashboard', (client: HttpAiClient, signal: AbortSignal) => client.queryDashboard({ question: '本周风险' }, signal), '/api/ai/dashboard/queries', dashboardResult],
    ['维修分诊', (client: HttpAiClient, signal: AbortSignal) => client.triageRepair({ repairId: 9, status: '待处理', descriptionRedacted: '插座异常', candidates: [] }, signal), '/api/ai/repairs/9/triage', repairResult],
    ['公告草稿', (client: HttpAiClient, signal: AbortSignal) => client.draftNotice({ points: '安全巡检', type: '安全卫生', tone: '正式', audience: '全体学生' }, signal), '/api/ai/notices/drafts', noticeResult],
  ])('%s command 接受 202 run，并只从 SSE 完成事件聚合 typed 终态', async (_name, invoke, path, result) => {
    const client = new HttpAiClient()
    const signal = new AbortController().signal
    vi.mocked(apiRequest).mockResolvedValueOnce(accepted as never)
    vi.mocked(openAiEventStream).mockImplementationOnce(() => eventStream([
      { sequence: 1, type: 'run.started' },
      { sequence: 2, type: 'usage.updated', payload: { inputTokens: 5 } },
      { sequence: 3, type: 'run.completed', payload: { result } },
    ]))

    await expect(invoke(client, signal)).resolves.toEqual(result)
    expect(apiRequest).toHaveBeenCalledWith(path, expect.objectContaining({
      method: 'POST', signal, headers: expect.objectContaining({ 'Idempotency-Key': expect.any(String) }),
    }))
    expect(openAiEventStream).toHaveBeenCalledWith(accepted.eventsUrl, expect.objectContaining({ signal }))
  })

  it('把后端领域 command DTO 显式映射为页面 DTO，并保留真实 proposal ID', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest).mockResolvedValue(accepted as never)
    vi.mocked(openAiEventStream)
      .mockImplementationOnce(() => eventStream([{ sequence: 1, type: 'run.completed', payload: { result: {
        intent: {
          metricIds: ['repair.pending.count', 'payment.unpaid.count'],
          dateRange: { preset: 'LAST_7_DAYS' }, dimensions: [], filters: {}, presentationHint: 'TABLE',
        },
        result: {
          metrics: {
            'repair.pending.count': { value: 4, unit: '项', metricVersion: 'v1', definition: '待维修', rows: [] },
            'payment.unpaid.count': { value: 2, unit: '项', metricVersion: 'v1', definition: '未缴账单', rows: [] },
          },
          asOf: '2026-07-11T10:00:00Z',
          catalogVersion: 'dashboard-metrics.v1',
          queryParameters: {
            dateRange: { preset: 'LAST_7_DAYS', from: '2026-07-05', to: '2026-07-11' },
            dimensions: [], filters: {},
          },
          dataCitations: [{
            id: 'metric-repair-pending-v1', label: '维修工单统计', locator: 'repair.pending.count',
            version: 'v1', access: 'available', metricId: 'repair.pending.count',
            asOf: '2026-07-11T10:00:00Z',
          }, {
            id: 'metric-payment-unpaid-v1', label: '缴费账单统计', locator: 'payment.unpaid.count',
            version: 'v1', access: 'available', metricId: 'payment.unpaid.count',
            asOf: '2026-07-11T10:00:00Z',
          }],
        },
        explanation: '待维修：4 项；未缴账单：2 项。',
        modelUsed: false,
        degraded: true,
        intentSource: 'deterministic-fallback.v1',
        intentSchemaVersion: 'DashboardQueryIntent.v1',
      } } }]))
      .mockImplementationOnce(() => eventStream([{ sequence: 1, type: 'run.completed', payload: { result: {
        repairOrderId: 9,
        category: '水电维修', urgency: 'HIGH', recommendedTeam: '水电维修组',
        missingInformation: ['是否断电'], reasoningSummary: '固定规则建议人工优先核验',
        assignmentCandidateUserId: 3, assignmentCandidateName: '维修员 A', slaSuggestion: '2 小时',
        asOf: '2026-07-11T10:01:00Z', degraded: false,
        proposal: { publicId: 'proposal-repair-live', state: 'PENDING_APPROVAL' },
      } } }]))
      .mockImplementationOnce(() => eventStream([{ sequence: 1, type: 'run.completed', payload: { result: {
        title: '安全巡检通知', type: '安全卫生', publisher: '由审批执行人重写', status: '草稿',
        content: '全体学生：\n\n周五巡检', blocked: false, safetyMessages: [], version: 'notice-draft.v1',
        proposal: { publicId: 'proposal-notice-live', state: 'PENDING_APPROVAL', expiresAt: '2026-07-11T10:12:00Z' },
      } } }]))

    await expect(client.queryDashboard({ question: '风险' })).resolves.toMatchObject({
      summary: '待维修：4 项；未缴账单：2 项。',
      metricVersion: 'dashboard-metrics.v1',
      riskCounts: [
        { type: '维修风险', count: 4, severity: 'high' },
        { type: '欠费风险', count: 2, severity: 'high' },
      ],
      evidence: {
        asOf: '2026-07-11T10:00:00Z', grounded: true,
        citations: [{
          id: 'metric-repair-pending-v1', label: '维修工单统计', locator: 'repair.pending.count',
          version: 'v1', access: 'available',
        }, {
          id: 'metric-payment-unpaid-v1', label: '缴费账单统计', locator: 'payment.unpaid.count',
          version: 'v1', access: 'available',
        }],
      },
      state: 'degraded',
      intent: {
        metricIds: ['repair.pending.count', 'payment.unpaid.count'],
        dateRange: { preset: 'LAST_7_DAYS' }, dimensions: [], filters: {}, presentationHint: 'TABLE',
      },
      queryParameters: {
        dateRange: { preset: 'LAST_7_DAYS', from: '2026-07-05', to: '2026-07-11' },
        dimensions: [], filters: {},
      },
    })
    expect(JSON.parse(String(vi.mocked(apiRequest).mock.calls[0]?.[1]?.body))).toEqual({
      question: '风险', intentSchemaVersion: 'DashboardQueryIntent.v1',
    })
    await expect(client.triageRepair({ repairId: 9, status: '伪造状态', descriptionRedacted: '客户端快照', candidates: [] }))
      .resolves.toMatchObject({ repairId: 9, proposalId: 'proposal-repair-live', evidence: { grounded: true } })
    await expect(client.draftNotice({ points: '周五巡检', type: '安全卫生', tone: '正式', audience: '全体学生' }))
      .resolves.toMatchObject({ proposalId: 'proposal-notice-live', evidence: { grounded: false } })
  })

  it('从后端 ProposalPreview 保留维修/公告的 asOf、citation、置信度和提案失效状态', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest).mockResolvedValue(accepted as never)
    vi.mocked(openAiEventStream)
      .mockImplementationOnce(() => eventStream([{ sequence: 1, type: 'run.completed', payload: { result: {
        repairOrderId: 9, category: '水电维修', urgency: 'HIGH', recommendedTeam: '水电维修组',
        missingInformation: [], reasoningSummary: '固定规则', assignmentCandidateUserId: 3,
        assignmentCandidateName: '维修员 A', slaSuggestion: '2 小时', asOf: '2026-07-18T10:00:00Z', degraded: false,
        proposal: {
          publicId: 'proposal-repair-evidence', state: 'PENDING_APPROVAL', expiresAt: '2026-07-18T10:10:00Z',
          preview: {
            asOf: '2026-07-18T10:00:00Z', evidenceBasis: 'DETERMINISTIC', confidence: 0.92,
            citations: [{ type: 'BUSINESS_SNAPSHOT', sourceRef: 'REPAIR_ORDER:9', label: '维修单授权快照', contentHash: 'a'.repeat(64) }],
          },
        },
      } } }]))
      .mockImplementationOnce(() => eventStream([{ sequence: 1, type: 'run.completed', payload: { result: {
        title: '安全巡检通知', type: '安全卫生', publisher: '审批执行人', status: '草稿', content: '正文',
        blocked: false, safetyMessages: [], version: 'notice-draft.v1',
        proposal: {
          publicId: 'proposal-notice-evidence', state: 'EXPIRED', expiresAt: '2026-07-18T09:00:00Z',
          preview: {
            asOf: '2026-07-18T08:00:00Z', evidenceBasis: 'DETERMINISTIC',
            citations: [{ type: 'USER_COMMAND', sourceRef: 'RUN:notice-1', label: '起草命令', contentHash: 'b'.repeat(64) }],
          },
        },
      } } }]))

    await expect(client.triageRepair({ repairId: 9, status: '待处理', descriptionRedacted: 'x', candidates: [] }))
      .resolves.toMatchObject({
        proposalId: 'proposal-repair-evidence', proposalState: 'pending_approval', proposalExpiresAt: '2026-07-18T10:10:00Z',
        evidence: {
          basis: 'deterministic', confidence: 0.92, asOf: '2026-07-18T10:00:00Z', grounded: true,
          citations: [{ locator: 'REPAIR_ORDER:9', label: '维修单授权快照', access: 'available' }],
        },
      })
    await expect(client.draftNotice({ points: '巡检', type: '安全卫生', tone: '正式', audience: '全体学生' }))
      .resolves.toMatchObject({
        proposalId: 'proposal-notice-evidence', proposalState: 'expired', proposalExpiresAt: '2026-07-18T09:00:00Z',
        evidence: { asOf: '2026-07-18T08:00:00Z', grounded: true, citations: [{ locator: 'RUN:notice-1', label: '起草命令' }] },
      })
  })

  it('领域响应缺少必填字段时 fail-closed，不以类型断言伪造成功', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest).mockResolvedValue(accepted as never)
    vi.mocked(openAiEventStream).mockImplementation(() => eventStream([
      { sequence: 1, type: 'run.completed', payload: { result: { explanation: '缺少指标事实' } } },
    ]))
    await expect(client.queryDashboard({ question: '风险' })).rejects.toThrow('Dashboard 响应合同不合法')
  })

  it('风险案例把 signal/business/explanation/human 四层证据映射为 typed DTO 且处置携带 dueAt', async () => {
    const client = new HttpAiClient()
    const rawRisk = {
      id: 'risk-1', caseVersion: 1, subjectToken: 'risk_token_1', type: '维修风险', severity: 'high',
      ruleVersion: 'repair-backlog.v1', evidenceSummary: '积压 72 小时',
      explanation: '确定性降级解释', confidence: null, assignee: '待人工分配', sla: '待设置',
      state: 'open', asOf: '2026-07-12T00:00:00Z', degraded: true,
      explanationRunId: null, assigneeUserId: null, dueAt: null,
      evidenceLayers: {
        signal: { riskType: 'repair-backlog', policyVersion: 'repair-backlog.v1', severity: 'HIGH', observedAt: '2026-07-12T00:00:00Z', facts: { ageHours: 72 } },
        businessSnapshot: { subjectType: 'REPAIR_ORDER', subjectToken: 'risk_token_1', capturedAt: '2026-07-12T00:00:00Z', facts: { status: '待处理' } },
        explanation: { text: '确定性降级解释', basis: 'deterministic_degraded', policyVersion: 'risk-explanation-deterministic.v1', runId: null, confidence: null, degraded: true },
        human: { assigneeUserId: null, dueAt: null, events: [{ id: 'event-1', type: 'OPENED', actor: '规则引擎', detail: '规则命中', occurredAt: '2026-07-12T00:00:00Z' }] },
      },
      events: [{ id: 'event-1', type: 'OPENED', actor: '规则引擎', detail: '规则命中', occurredAt: '2026-07-12T00:00:00Z' }],
    }
    vi.mocked(apiRequest)
      .mockResolvedValueOnce({ records: [rawRisk], total: 1, page: 1, pageSize: 20 } as never)
      .mockResolvedValueOnce(undefined as never)
      .mockResolvedValueOnce({ ...rawRisk, caseVersion: 2, state: 'acknowledged', assigneeUserId: 7,
        dueAt: '2026-07-14T00:00:00Z', evidenceLayers: { ...rawRisk.evidenceLayers,
          human: { ...rawRisk.evidenceLayers.human, assigneeUserId: 7, dueAt: '2026-07-14T00:00:00Z' } } } as never)

    const page = await client.listRiskCases()
    expect(page.records[0]).toMatchObject({
      signalEvidence: { policyVersion: 'repair-backlog.v1', facts: { ageHours: 72 } },
      businessSnapshot: { subjectType: 'REPAIR_ORDER', subjectToken: 'risk_token_1' },
      explanationEvidence: { basis: 'deterministic_degraded', confidence: undefined, degraded: true },
      humanEvidence: { assigneeUserId: null, dueAt: null },
    })
    await client.updateRiskCase('risk-1', 'acknowledge', {
      caseVersion: 1, detail: '已人工确认', dueAt: '2026-07-14T00:00:00Z',
    })
    expect(JSON.parse(String(vi.mocked(apiRequest).mock.calls[1]?.[1]?.body))).toMatchObject({
      caseVersion: 1, detail: '已人工确认', dueAt: '2026-07-14T00:00:00Z',
    })
  })

  it('欠费风险接受 token 化 PAYMENT 业务快照且不要求学生 PII', async () => {
    const client = new HttpAiClient()
    const rawRisk = {
      id: 'risk-payment-1', caseVersion: 1, subjectToken: 'risk_payment_token_1', type: '欠费风险', severity: 'high',
      ruleVersion: 'overdue-payment.v1', evidenceSummary: '账单已逾期 5 天',
      explanation: '确定性降级解释', confidence: null, assignee: '待人工分配', sla: '待设置',
      state: 'open', asOf: '2026-07-12T00:00:00Z', degraded: true,
      explanationRunId: null, assigneeUserId: null, dueAt: null,
      evidenceLayers: {
        signal: { riskType: 'overdue-payment', policyVersion: 'overdue-payment.v1', severity: 'HIGH', observedAt: '2026-07-12T00:00:00Z', facts: { ageDays: 5, status: '部分缴' } },
        businessSnapshot: { subjectType: 'PAYMENT', subjectToken: 'risk_payment_token_1', capturedAt: '2026-07-12T00:00:00Z', facts: { ageDays: 5, status: '部分缴' } },
        explanation: { text: '确定性降级解释', basis: 'deterministic_degraded', policyVersion: 'risk-explanation-deterministic.v1', runId: null, confidence: null, degraded: true },
        human: { assigneeUserId: null, dueAt: null, events: [{ id: 'event-payment-1', type: 'OPENED', actor: '规则引擎', detail: '规则命中', occurredAt: '2026-07-12T00:00:00Z' }] },
      },
      events: [{ id: 'event-payment-1', type: 'OPENED', actor: '规则引擎', detail: '规则命中', occurredAt: '2026-07-12T00:00:00Z' }],
    }
    vi.mocked(apiRequest).mockResolvedValueOnce({ records: [rawRisk], total: 1, page: 1, pageSize: 20 } as never)

    await expect(client.listRiskCases({ type: '欠费风险' })).resolves.toMatchObject({
      records: [{ id: 'risk-payment-1', type: '欠费风险', subjectToken: 'risk_payment_token_1', businessSnapshot: { subjectType: 'PAYMENT' } }],
    })
  })

  it('run.failed 使用安全消息失败，流提前结束或终态缺 result 也拒绝伪造成功', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest).mockResolvedValue(accepted as never)
    vi.mocked(openAiEventStream).mockImplementationOnce(() => eventStream([
      { sequence: 1, type: 'run.failed', payload: { safeMessage: '指标权限不足', errorCode: 'AI_FORBIDDEN' } },
    ]))
    await expect(client.queryDashboard({ question: 'x' })).rejects.toThrow('指标权限不足')

    vi.mocked(openAiEventStream).mockImplementationOnce(() => eventStream([{ sequence: 1, type: 'run.started' }]))
    await expect(client.queryDashboard({ question: 'x' })).rejects.toThrow('未返回终态')

    vi.mocked(openAiEventStream).mockImplementationOnce(() => eventStream([
      { sequence: 1, type: 'run.completed', payload: {} },
    ]))
    await expect(client.queryDashboard({ question: 'x' })).rejects.toThrow('缺少结果')
  })

  it('run.timed_out 作为 command 合法终态返回安全超时消息', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest).mockResolvedValueOnce(accepted as never)
    vi.mocked(openAiEventStream).mockImplementationOnce(() => eventStream([
      { sequence: 1, type: 'run.timed_out', payload: {
        safeMessage: 'AI 运行超时，请稍后重试', errorCode: 'AI_RUN_TIMED_OUT',
      } },
    ]))

    await expect(client.queryDashboard({ question: 'x' }))
      .rejects.toThrow('AI 运行超时，请稍后重试')
    expect(apiRequest).toHaveBeenCalledTimes(1)
  })

  it('同一 command 网络重试复用同一 Idempotency-Key 与请求体', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest)
      .mockRejectedValueOnce(new TypeError('network reset'))
      .mockResolvedValueOnce(accepted as never)
    vi.mocked(openAiEventStream).mockImplementationOnce(() => eventStream([
      { sequence: 1, type: 'run.completed', payload: { result: dashboardResult } },
    ]))

    await client.queryDashboard({ question: '本周风险' })

    const first = vi.mocked(apiRequest).mock.calls[0]!
    const retry = vi.mocked(apiRequest).mock.calls[1]!
    expect(retry[0]).toBe(first[0])
    expect(new Headers(retry[1]?.headers).get('Idempotency-Key'))
      .toBe(new Headers(first[1]?.headers).get('Idempotency-Key'))
    expect(retry[1]?.body).toBe(first[1]?.body)
  })

  it('创建 conversation/run 后流式读取事件，消息重试复用 Idempotency-Key 和 clientRequestId', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest)
      .mockResolvedValueOnce({ id: 'conversation-1' } as never)
      .mockRejectedValueOnce(new TypeError('response lost'))
      .mockResolvedValueOnce(accepted as never)
    vi.mocked(openAiEventStream).mockImplementation(() => eventStream([
      { sequence: 1, type: 'run.started' },
      { sequence: 2, type: 'run.completed' },
    ]))

    const events = []
    for await (const event of client.startConversation({ text: '你好', surface: 'GLOBAL' })) events.push(event)

    expect(events.map((item) => item.sequence)).toEqual([1, 2])
    const initial = vi.mocked(apiRequest).mock.calls[1]!
    const retry = vi.mocked(apiRequest).mock.calls[2]!
    expect(new Headers(retry[1]?.headers).get('Idempotency-Key'))
      .toBe(new Headers(initial[1]?.headers).get('Idempotency-Key'))
    expect(JSON.parse(String(retry[1]?.body)).clientRequestId)
      .toBe(JSON.parse(String(initial[1]?.body)).clientRequestId)
    expect(JSON.parse(String(initial[1]?.body)).clientRequestId)
      .toBe(new Headers(initial[1]?.headers).get('Idempotency-Key'))
  })

  it('把后端扁平 conversation detail 显式映射为前端会话与消息，并保留服务端消息 ID', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest).mockResolvedValueOnce({
      id: '123e4567-e89b-42d3-a456-426614174001',
      surface: 'GLOBAL',
      contextType: 'NONE',
      contextId: null,
      status: 'ACTIVE',
      title: '维修时限',
      lastMessageAt: '2026-07-12T10:00:01Z',
      createdAt: '2026-07-12T10:00:00Z',
      messages: [{
        id: '123e4567-e89b-42d3-a456-426614174002',
        role: 'ASSISTANT',
        text: '两个工作日。',
        classification: 'L1',
        createdAt: '2026-07-12T10:00:01Z',
      }, {
        id: '123e4567-e89b-42d3-a456-426614174003',
        role: 'ASSISTANT',
        text: '有授权引用的回答。',
        classification: 'L1',
        createdAt: '2026-07-12T10:00:02Z',
        citations: [{ id: 'citation-1', label: '维修管理办法', locator: '第 3.2 条', version: 'v1', access: 'available' }],
        grounded: true,
        asOf: '2026-07-12T09:59:00Z',
        runId: '123e4567-e89b-42d3-a456-426614174004',
        runState: 'SUCCEEDED',
      }],
    } as never)

    await expect(client.getConversation('123e4567-e89b-42d3-a456-426614174001')).resolves.toEqual({
      conversation: {
        id: '123e4567-e89b-42d3-a456-426614174001',
        surface: 'GLOBAL',
        contextType: 'NONE',
        contextId: null,
        status: 'ACTIVE',
        title: '维修时限',
        lastMessageAt: '2026-07-12T10:00:01Z',
        createdAt: '2026-07-12T10:00:00Z',
      },
      messages: [{
        id: '123e4567-e89b-42d3-a456-426614174002',
        role: 'ASSISTANT',
        text: '两个工作日。',
        classification: 'L1',
        createdAt: '2026-07-12T10:00:01Z',
        citations: [],
      }, {
        id: '123e4567-e89b-42d3-a456-426614174003',
        role: 'ASSISTANT',
        text: '有授权引用的回答。',
        classification: 'L1',
        createdAt: '2026-07-12T10:00:02Z',
        citations: [{ id: 'citation-1', label: '维修管理办法', locator: '第 3.2 条', version: 'v1', access: 'available' }],
        grounded: true,
        asOf: '2026-07-12T09:59:00Z',
        runId: '123e4567-e89b-42d3-a456-426614174004',
        runState: 'succeeded',
      }],
    })
    expect(apiRequest).toHaveBeenCalledWith('/api/ai/conversations/123e4567-e89b-42d3-a456-426614174001')
  })

  it('历史消息 runState 只接受已知服务端枚举，未知状态 fail-closed', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest).mockResolvedValueOnce({
      id: '123e4567-e89b-42d3-a456-426614174001', surface: 'GLOBAL', contextType: 'NONE',
      contextId: null, status: 'ACTIVE', title: null, lastMessageAt: null, createdAt: '2026-07-12T10:00:00Z',
      messages: [{
        id: '123e4567-e89b-42d3-a456-426614174002', role: 'ASSISTANT', text: '异常状态',
        classification: 'L1', createdAt: '2026-07-12T10:00:01Z', runState: 'SECRET_INTERNAL_STATE',
      }],
    } as never)

    await expect(client.getConversation('123e4567-e89b-42d3-a456-426614174001'))
      .rejects.toThrow('AI message 响应合同不合法')
  })

  it('OpenAPI NEEDS_RECONCILIATION 状态映射为前端只读对账态', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest).mockResolvedValueOnce({
      id: '123e4567-e89b-42d3-a456-426614174001', surface: 'GLOBAL', contextType: 'NONE',
      contextId: null, status: 'ACTIVE', title: null, lastMessageAt: null, createdAt: '2026-07-12T10:00:00Z',
      messages: [{
        id: '123e4567-e89b-42d3-a456-426614174002', role: 'ASSISTANT', text: '等待人工对账',
        classification: 'L1', createdAt: '2026-07-12T10:00:01Z',
        runId: '123e4567-e89b-42d3-a456-426614174003', runState: 'NEEDS_RECONCILIATION',
      }],
    } as never)

    await expect(client.getConversation('123e4567-e89b-42d3-a456-426614174001'))
      .resolves.toMatchObject({ messages: [{ runState: 'needs_reconciliation' }] })
  })

  it('反馈使用服务端 assistant message ID 调用固定端点且不发送内部运行参数', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest).mockResolvedValueOnce(undefined as never)

    await client.submitFeedback('123e4567-e89b-42d3-a456-426614174002', {
      rating: -1,
      tags: ['needs-review'],
      comment: '引用不足，请人工复核',
    })

    expect(apiRequest).toHaveBeenCalledWith(
      '/api/ai/messages/123e4567-e89b-42d3-a456-426614174002/feedback',
      {
        method: 'POST',
        body: JSON.stringify({ rating: -1, tags: ['needs-review'], comment: '引用不足，请人工复核' }),
      },
    )
  })

  it('用户显式重试终态 run 时创建 child run，并用单一 Idempotency-Key 读取新事件流', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest).mockResolvedValueOnce({
      runId: 'child-run', eventsUrl: '/api/ai/runs/child-run/events',
    } as never)
    vi.mocked(openAiEventStream).mockImplementationOnce(() => eventStream([
      { sequence: 1, type: 'run.queued' },
      { sequence: 2, type: 'run.completed' },
    ]))

    const events = []
    for await (const event of client.retryRun('parent-run')) events.push(event)

    expect(events.map((event) => event.type)).toEqual(['run.queued', 'run.completed'])
    expect(apiRequest).toHaveBeenCalledWith('/api/ai/runs/parent-run/retry', expect.objectContaining({
      method: 'POST', headers: expect.objectContaining({ 'Idempotency-Key': expect.any(String) }),
    }))
    expect(openAiEventStream).toHaveBeenCalledWith('/api/ai/runs/child-run/events', expect.any(Object))
  })

  it('run.timed_out 是对话与重试的合法终态，不在服务端超时后误发 cancel', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest).mockImplementation((path) => {
      if (path === '/api/ai/conversations') return Promise.resolve({ id: 'conversation-1' } as never)
      if (path === '/api/ai/conversations/conversation-1/messages') return Promise.resolve(accepted as never)
      if (path === '/api/ai/runs/parent-run/retry') {
        return Promise.resolve({ runId: 'child-run', eventsUrl: '/api/ai/runs/child-run/events' } as never)
      }
      return Promise.resolve(undefined as never)
    })
    vi.mocked(openAiEventStream)
      .mockImplementationOnce(() => eventStream([{ sequence: 1, type: 'run.timed_out', payload: {
        safeMessage: '对话生成超时', errorCode: 'AI_RUN_TIMED_OUT',
      } }]))
      .mockImplementationOnce(() => eventStream([{ sequence: 1, type: 'run.timed_out', payload: {
        safeMessage: '重试生成超时', errorCode: 'AI_RUN_TIMED_OUT',
      } }]))

    const conversationEvents = []
    for await (const event of client.startConversation({ text: '超时测试', surface: 'GLOBAL' })) {
      conversationEvents.push(event.type)
    }
    const retryEvents = []
    for await (const event of client.retryRun('parent-run')) retryEvents.push(event.type)

    expect(conversationEvents).toEqual(['run.timed_out'])
    expect(retryEvents).toEqual(['run.timed_out'])
    expect(vi.mocked(apiRequest).mock.calls.map(([path]) => path)).toEqual([
      '/api/ai/conversations',
      '/api/ai/conversations/conversation-1/messages',
      '/api/ai/runs/parent-run/retry',
    ])
  })

  it('SSE 首次网络错误时使用最后序号重连一次', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest)
      .mockResolvedValueOnce({ id: 'conversation-1' } as never)
      .mockResolvedValueOnce(accepted as never)
    vi.mocked(openAiEventStream)
      .mockImplementationOnce(() => (async function* () {
        yield { runId: 'r', sequence: 4, type: 'message.delta', timestamp: 't', payload: {} }
        throw new Error('断线')
      })())
      .mockImplementationOnce(() => eventStream([{ sequence: 5, type: 'run.completed' }]))

    const sequences = []
    for await (const event of client.startConversation({ text: 'x', surface: 'GLOBAL' })) sequences.push(event.sequence)

    expect(sequences).toEqual([4, 5])
    expect(vi.mocked(openAiEventStream).mock.calls[1]?.[1]).toMatchObject({ lastSequence: 4 })
  })

  it('用户停止 SSE 后调用服务端 cancel，而不只是在浏览器本地 abort', async () => {
    const client = new HttpAiClient()
    const controller = new AbortController()
    vi.mocked(apiRequest)
      .mockResolvedValueOnce({ id: 'conversation-1' } as never)
      .mockResolvedValueOnce(accepted as never)
      .mockResolvedValueOnce(undefined as never)
    vi.mocked(openAiEventStream).mockImplementationOnce((_url, options) => (async function* () {
      yield { runId: 'run-1', sequence: 1, type: 'run.started', timestamp: 't', payload: {} }
      if (options?.signal?.aborted) throw new DOMException('Aborted', 'AbortError')
      await new Promise<void>((_resolve, reject) => options?.signal?.addEventListener('abort',
        () => reject(new DOMException('Aborted', 'AbortError')), { once: true }))
    })())

    const stream = client.startConversation({ text: '停止测试', surface: 'GLOBAL' }, controller.signal)
    await expect(stream.next()).resolves.toMatchObject({ value: { type: 'run.started' } })
    controller.abort()
    await expect(stream.next()).rejects.toMatchObject({ name: 'AbortError' })
    await vi.waitFor(() => expect(apiRequest).toHaveBeenCalledWith('/api/ai/runs/run-1/cancel',
      expect.objectContaining({ method: 'POST' })))
  })

  it('风险处置携带实际 caseVersion 与必填 detail，随后读取权威详情', async () => {
    const client = new HttpAiClient()
    const event = { id: 'event-1', type: 'RESOLVED', actor: '当前用户', detail: '已完成现场复核并恢复供电', occurredAt: '2026-07-12T00:00:00Z' }
    const changed = {
      id: 'risk-1', caseVersion: 8, subjectToken: 'risk_token_1', type: '维修风险', severity: 'high',
      ruleVersion: 'repair-backlog.v1', evidenceSummary: '积压 72 小时', explanation: '确定性降级解释',
      confidence: null, assignee: '用户 #7', sla: '按运营规则人工核验', state: 'resolved',
      asOf: '2026-07-12T00:00:00Z', events: [event], degraded: true,
      explanationRunId: null, assigneeUserId: 7, dueAt: null,
      evidenceLayers: {
        signal: { riskType: 'repair-backlog', policyVersion: 'repair-backlog.v1', severity: 'HIGH', observedAt: '2026-07-12T00:00:00Z', facts: { ageHours: 72 } },
        businessSnapshot: { subjectType: 'REPAIR_ORDER', subjectToken: 'risk_token_1', capturedAt: '2026-07-12T00:00:00Z', facts: { status: '待处理' } },
        explanation: { text: '确定性降级解释', basis: 'deterministic_degraded', policyVersion: 'risk-explanation-deterministic.v1', runId: null, confidence: null, degraded: true },
        human: { assigneeUserId: 7, dueAt: null, events: [event] },
      },
    }
    vi.mocked(apiRequest)
      .mockResolvedValueOnce(undefined as never)
      .mockResolvedValueOnce(changed as never)

    await expect(client.updateRiskCase('risk-1', 'resolve', { caseVersion: 7, detail: '已完成现场复核并恢复供电' }))
      .resolves.toMatchObject({ id: 'risk-1', caseVersion: 8, state: 'resolved', assigneeUserId: 7 })

    expect(apiRequest).toHaveBeenNthCalledWith(1, '/api/ai/risk-cases/risk-1/resolve', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ caseVersion: 7, detail: '已完成现场复核并恢复供电' }),
    }))
    expect(apiRequest).toHaveBeenNthCalledWith(2, '/api/ai/risk-cases/risk-1')
  })

  it('拒绝空风险处置说明，且不再调用未定义 refresh-preview', async () => {
    const client = new HttpAiClient()
    await expect(client.updateRiskCase('risk-1', 'resolve', { caseVersion: 0, detail: '已核验' }))
      .rejects.toThrow('风险案例版本无效')
    await expect(client.updateRiskCase('risk-1', 'dismiss', { caseVersion: 2, detail: '  ' }))
      .rejects.toThrow('处置说明不能为空')

    await client.listProposals({ pageSize: 20, state: 'pending_approval' })
    await client.getProposal('proposal-1')
    const urls = vi.mocked(apiRequest).mock.calls.map(([url]) => url)
    expect(urls).not.toContain('/api/ai/proposals/proposal-1/refresh-preview')
  })

  it('提案分页查询会将 allowlist actionType 作为服务端筛选条件', async () => {
    const client = new HttpAiClient()

    await client.listProposals({
      page: 2,
      pageSize: 20,
      state: 'pending_approval',
      actionType: 'REPAIR_ASSIGN',
    })

    expect(apiRequest).toHaveBeenCalledWith(
      '/api/ai/proposals?page=2&pageSize=20&state=pending_approval&actionType=REPAIR_ASSIGN',
    )
  })

  it('批准提案先获取目标绑定 step-up proof，并在单次审批请求中携带 proof', async () => {
    const client = new HttpAiClient()
    const changed = { id: '123e4567-e89b-12d3-a456-426614174000', state: 'approved' }
    vi.mocked(apiRequest)
      .mockResolvedValueOnce({ proof: 'single-use-proof' } as never)
      .mockResolvedValueOnce(changed as never)

    await expect(client.approveProposal(changed.id, {
      version: 3,
      payloadHash: 'a'.repeat(64),
      businessSnapshotHash: 'b'.repeat(64),
      comment: '已人工核对',
      password: 'current-password',
    })).resolves.toEqual(changed)

    expect(apiRequest).toHaveBeenNthCalledWith(1, '/api/security/step-up', expect.objectContaining({
      method: 'POST',
      body: expect.stringContaining('"actionCode":"PROPOSAL_APPROVE"'),
    }))
    const stepUpBody = JSON.parse(vi.mocked(apiRequest).mock.calls[0]![1]!.body as string)
    expect(stepUpBody.resourcePublicId).toBe(changed.id)
    expect(stepUpBody.requestHash).toBe('d6503c6f37d5af312614c8eb70b37e9175840efc675951dc6055b6504f20b110')
    expect(apiRequest).toHaveBeenNthCalledWith(2, `/api/ai/proposals/${changed.id}/approve`, expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'X-Step-Up-Proof': 'single-use-proof' }),
    }))
  })

  it('保留服务端真实审计引用、链 hash 与完整治理关系', async () => {
    const client = new HttpAiClient()
    const chainHash = 'c'.repeat(64)
    const payloadHash = 'a'.repeat(64)
    const snapshotHash = 'b'.repeat(64)
    const run = {
      id: 'run-stage4',
      parentRunId: null,
      capability: 'REPAIR',
      state: 'SUCCEEDED',
      providerAlias: 'deterministic',
      promptVersion: 'repair.v2',
      inputTokens: 12,
      outputTokens: 8,
      estimatedCost: 0.002,
      citationCount: 1,
      chainHash,
      createdAt: '2026-07-20T10:00:00Z',
      finishedAt: '2026-07-20T10:00:01Z',
    }
    vi.mocked(apiRequest)
      .mockResolvedValueOnce({ records: [run], total: 1, page: 1, pageSize: 20 } as never)
      .mockResolvedValueOnce({
        run,
        steps: [{ sequence: 1, type: 'RUN_CREATED', occurredAt: '2026-07-20T10:00:00Z' }],
        retrievals: [],
        tools: [{ id: 'tool-1', sequence: 2, toolName: 'AssignMaintainer', toolVersion: 'v1',
          authorizationDecision: 'ALLOW', state: 'SUCCEEDED', errorCode: null,
          startedAt: '2026-07-20T10:00:00Z', finishedAt: '2026-07-20T10:00:01Z' }],
        citations: [{ id: 'citation-1', citationType: 'BUSINESS_SNAPSHOT', documentVersionId: null,
          chunkId: null, metricId: null, rank: 1, score: null, contentHash: payloadHash,
          createdAt: '2026-07-20T10:00:00Z' }],
        proposals: [{ id: 'proposal-1', actionType: 'REPAIR_ASSIGN', targetType: 'REPAIR_ORDER',
          payloadHash, businessSnapshotHash: snapshotHash, approvalPolicyVersion: 'approval.v1',
          requiredApprovalCount: 1, approvedCount: 1, riskLevel: 'HIGH', state: 'SUCCEEDED',
          proposerUserId: 7, expiresAt: '2026-07-20T11:00:00Z', createdAt: '2026-07-20T10:00:00Z' }],
        approvals: [{ proposalId: 'proposal-1', proposalVersion: 1, decision: 'APPROVED',
          reviewerUserId: 7, payloadHash, businessSnapshotHash: snapshotHash,
          createdAt: '2026-07-20T10:00:01Z' }],
        executions: [{ id: 'execution-1', proposalId: 'proposal-1', state: 'SUCCEEDED', version: 1,
          handlerName: 'repairAssign', executedByUserId: 7, reconfirmedByUserId: null,
          resultResourceType: 'REPAIR_ORDER', errorCode: null,
          startedAt: '2026-07-20T10:00:01Z', finishedAt: '2026-07-20T10:00:01Z' }],
        usage: [{ requestSequence: 1, attempt: 1, requestKind: 'MODEL', actorKind: 'USER', actorUserId: 7,
          servicePrincipalCode: null, initiatedByUserId: 7, capability: 'REPAIR', providerCode: 'fake',
          modelName: 'deterministic', inputTokens: 12, outputTokens: 8, costAmount: 0.002,
          currency: 'CNY', usageSource: 'PROVIDER', occurredAt: '2026-07-20T10:00:01Z' }],
        hashChain: [{ id: 'hash-1', chainScope: 'RUN', aggregateType: 'RUN',
          aggregatePublicId: 'run-stage4', sequence: 1, eventType: 'RUN_CREATED', actorKind: 'USER',
          actorUserId: 7, servicePrincipalCode: null, initiatedByUserId: 7, effectiveSubjectUserId: 7,
          payloadHash, previousEventHash: null, eventHash: chainHash, integrityAlgorithm: 'HMAC-SHA256',
          integrityKeyVersion: 1, canonicalizationVersion: 'v1', correlationId: 'correlation-1',
          occurredAt: '2026-07-20T10:00:00Z' }],
      } as never)

    const page = await client.listAuditRuns({ page: 1, pageSize: 20 })
    const detail = await client.getAuditRun('run-stage4')

    expect(page.records[0]).toMatchObject({ citationCount: 1, chainHash })
    expect(detail.run).toMatchObject({ citationCount: 1, chainHash, parentRunId: null })
    expect(detail.tools[0]).toMatchObject({ id: 'tool-1', toolName: 'AssignMaintainer' })
    expect(detail.citations[0]).toMatchObject({ id: 'citation-1', contentHash: payloadHash })
    expect(detail.proposals[0]).toMatchObject({ id: 'proposal-1', payloadHash })
    expect(detail.approvals[0]).toMatchObject({ proposalId: 'proposal-1', decision: 'APPROVED' })
    expect(detail.executions[0]).toMatchObject({ id: 'execution-1', state: 'SUCCEEDED' })
    expect(detail.usage[0]).toMatchObject({ inputTokens: 12, currency: 'CNY' })
    expect(detail.hashChain[0]).toMatchObject({ eventHash: chainHash, sequence: 1 })
  })

  it('审批详情、审计详情/正文/成本与 NEEDS_REVIEW reconfirm 使用固定端点', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest)
      .mockResolvedValueOnce({
        run: { id: 'run-1', parentRunId: null, capability: 'REPAIR', state: 'SUCCEEDED', providerAlias: 'deterministic', promptVersion: 'v1', inputTokens: 1, outputTokens: 2, estimatedCost: 0, citationCount: 0, chainHash: null, createdAt: '2026-07-11T10:00:00Z', finishedAt: '2026-07-11T10:00:01Z' },
        steps: [{ sequence: 1, type: 'RUN_CREATED', occurredAt: '2026-07-11T10:00:00Z' }],
        retrievals: [],
        tools: [],
        citations: [],
        proposals: [],
        approvals: [],
        executions: [],
        usage: [],
        hashChain: [],
      } as never)
      .mockResolvedValueOnce({ id: 'content' } as never)
      .mockResolvedValueOnce({ records: [], total: 0, page: 2, pageSize: 10 } as never)
      .mockResolvedValueOnce({ proof: 'reconfirm-proof' } as never)
      .mockResolvedValueOnce({ proposalId: 'proposal-1', executionId: 'execution-1',
        state: 'NEEDS_REVIEW', version: 3, resultHash: null } as never)

    await client.getAuditRun('run-1')
    await client.getAuditContent('run-1', '因安全事件复核本次运行正文', 'proof')
    await client.getAuditCosts({ page: 2, pageSize: 10 })
    await client.reconfirmExecution('execution-1', {
      version: 3,
      payloadHash: 'a'.repeat(64),
      businessSnapshotHash: 'b'.repeat(64),
      resolution: 'UNKNOWN',
      comment: '已核对原业务事实',
      password: 'current-password',
    })

    expect(apiRequest).toHaveBeenCalledWith('/api/ai/audit/runs/run-1')
    expect(apiRequest).toHaveBeenCalledWith('/api/ai/audit/runs/run-1/content', expect.objectContaining({
      headers: { 'X-Audit-Reason': '因安全事件复核本次运行正文', 'X-Step-Up-Proof': 'proof' },
    }))
    expect(apiRequest).toHaveBeenCalledWith('/api/ai/audit/costs?page=2&pageSize=10')
    expect(apiRequest).toHaveBeenCalledWith('/api/security/step-up', expect.objectContaining({
      method: 'POST', body: expect.stringContaining('"actionCode":"PROPOSAL_RECONFIRM"'),
    }))
    expect(apiRequest).toHaveBeenCalledWith('/api/ai/executions/execution-1/reconfirm', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'X-Step-Up-Proof': 'reconfirm-proof' }),
      body: expect.stringContaining('"resolution":"UNKNOWN"'),
    }))
    const reconfirmCall = vi.mocked(apiRequest).mock.calls.find(([url]) => url.includes('/reconfirm'))!
    expect(JSON.parse(reconfirmCall[1]!.body as string)).toEqual({
      version: 3,
      payloadHash: 'a'.repeat(64),
      businessSnapshotHash: 'b'.repeat(64),
      resolution: 'UNKNOWN',
      comment: '已核对原业务事实',
    })
  })

  it('break-glass 正文读取先签发与理由绑定的单次 step-up proof', async () => {
    const client = new HttpAiClient()
    vi.mocked(apiRequest)
      .mockResolvedValueOnce({ proof: 'audit-proof' } as never)
      .mockResolvedValueOnce({ runId: 'run-1', messages: [] } as never)

    await client.readAuditContent('run-1', '因安全事件复核本次运行正文', 'current-password')

    expect(apiRequest).toHaveBeenNthCalledWith(1, '/api/security/step-up', expect.objectContaining({
      method: 'POST', body: expect.stringContaining('"actionCode":"AUDIT_CONTENT_READ"'),
    }))
    expect(apiRequest).toHaveBeenNthCalledWith(2, '/api/ai/audit/runs/run-1/content', expect.objectContaining({
      headers: { 'X-Audit-Reason': '因安全事件复核本次运行正文', 'X-Step-Up-Proof': 'audit-proof' },
    }))
  })
})
