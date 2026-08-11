import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DemoAiClient } from '../api/ai-demo'
import { setAiClient } from '../api/ai-client'
import type { AiRepairTriageResult, AiRunEvent } from '../types/ai'
import { useAiStore } from './ai'
import { useAiApprovalStore } from './aiApproval'
import { useAiRiskStore } from './aiRisk'

function stream(events: AiRunEvent[], failure?: Error) {
  return async function* () {
    for (const event of events) yield event
    if (failure) throw failure
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((fulfill, fail) => { resolve = fulfill; reject = fail })
  return { promise, resolve, reject }
}

const baseEvent = { runId: 'run', timestamp: 't' }

describe('AI application stores', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    setAiClient(new DemoAiClient())
  })

  it('助手处理 delta、引用去重、完成和关闭确认', async () => {
    const citation = { id: 'c1', label: '制度', locator: '第 1 条', version: 'v1', access: 'available' as const }
    const events: AiRunEvent[] = [
      { ...baseEvent, sequence: 1, type: 'run.started', payload: {} },
      { ...baseEvent, sequence: 2, type: 'message.delta', payload: { textDelta: '安全回答' } },
      { ...baseEvent, sequence: 3, type: 'citation.added', payload: { citation } },
      { ...baseEvent, sequence: 4, type: 'citation.added', payload: { citation } },
      { ...baseEvent, sequence: 5, type: 'run.completed', payload: { confidence: 0.9, grounded: true, asOf: '2026-07-11' } },
    ]
    setAiClient({ startConversation: stream(events) })
    const store = useAiStore()
    store.openAssistant()
    await store.askAssistant('问题')

    expect(store.runState).toBe('succeeded')
    expect(store.messages.at(-1)).toMatchObject({ text: '安全回答', confidence: 0.9, grounded: true })
    expect(store.messages.at(-1)?.citations).toHaveLength(1)
    store.requestCloseAssistant()
    expect(store.assistantOpen).toBe(false)
  })

  it('助手接受后端 citation.added 最小扁平契约且不要求正文', async () => {
    setAiClient({ startConversation: stream([
      { ...baseEvent, sequence: 1, type: 'message.delta', payload: { textDelta: '有依据的回答' } },
      { ...baseEvent, sequence: 2, type: 'citation.added', payload: {
        citationId: 'server-citation-1', label: '维修制度', locator: '第 1 段', rank: 1,
      } },
      { ...baseEvent, sequence: 3, type: 'run.completed', payload: { grounded: true } },
    ]) })
    const store = useAiStore()

    await store.askAssistant('维修时限是什么？')

    expect(store.messages.at(-1)?.citations).toEqual([{
      id: 'server-citation-1', label: '维修制度', locator: '第 1 段', version: '', access: 'available',
    }])
    expect(store.messages.at(-1)?.text).toBe('有依据的回答')
  })

  it('助手覆盖 degraded、run.failed、异常和空问题', async () => {
    const store = useAiStore()
    setAiClient({ startConversation: stream([
      { ...baseEvent, sequence: 1, type: 'run.degraded', payload: {} },
      { ...baseEvent, sequence: 2, type: 'run.failed', payload: { safeMessage: '安全失败原因' } },
    ]) })
    await store.askAssistant('降级问题')
    expect(store.runState).toBe('failed')
    expect(store.assistantError).toBe('安全失败原因')
    expect(store.error).toBeNull()

    const before = store.messages.length
    await store.askAssistant('  ')
    expect(store.messages).toHaveLength(before)
    setAiClient({ startConversation: stream([], new Error('供应商不可用')) })
    await store.askAssistant('异常问题')
    expect(store.assistantError).toBe('供应商不可用')
    expect(store.error).toBeNull()
  })

  it('运行中关闭先确认，再停止并关闭', async () => {
    const store = useAiStore()
    store.openAssistant()
    store.runState = 'streaming'
    store.requestCloseAssistant()
    expect(store.closeConfirmationOpen).toBe(true)
    expect(store.assistantOpen).toBe(true)
    store.confirmStopAndClose()
    expect(store.runState).toBe('cancelled')
    expect(store.assistantOpen).toBe(false)
  })

  it('Dashboard、维修、公告及演示提案均走独立 AI client', async () => {
    const client = new DemoAiClient()
    setAiClient(client)
    const store = useAiStore()
    await store.loadDashboard('本周风险')
    expect(store.dashboardInsight?.metricVersion).toBe('dashboard-metrics.v1')
    const triage = await store.triage({ repairId: 8, status: '待处理', descriptionRedacted: '插座异常', candidates: [{ userId: 2, displayName: '维修员' }] })
    expect(triage?.assignmentCandidateUserId).toBe(2)
    const draft = await store.draftNotice({ points: '周五巡检', type: '通知', tone: '正式', audience: '全体' })
    expect(draft?.status).toBe('草稿')
    store.submitDemoProposal('repair')
    expect(store.demoProposalMessage).toContain('维修指派')
    store.submitDemoProposal('notice')
    expect(store.demoProposalMessage).toContain('未写入或发布公告')
  })

  it('缺失能力与 Dashboard 错误给出稳定状态', async () => {
    const store = useAiStore()
    setAiClient({ queryDashboard: async () => { throw new Error('审计不可用') } })
    await store.loadDashboard()
    expect(store.dashboardError).toBe('审计不可用')
    expect(store.error).toBeNull()
    setAiClient({})
    await expect(store.triage({ repairId: 1, status: '待处理', descriptionRedacted: '', candidates: [] })).rejects.toThrow('不支持维修分诊')
    await expect(store.draftNotice({ points: 'x', type: '通知', tone: '正式', audience: '全体' })).rejects.toThrow('不支持公告起草')
  })

  it('维修与公告请求不会占用或覆盖 Dashboard 请求状态', async () => {
    const store = useAiStore()
    const triageInput = {
      repairId: 8,
      status: '待处理',
      descriptionRedacted: '插座异常',
      candidates: [{ userId: 2, displayName: '维修员' }],
    }
    const triageResult = await new DemoAiClient().triageRepair(triageInput)
    const pendingTriage = deferred<AiRepairTriageResult>()
    setAiClient({ triageRepair: () => pendingTriage.promise })

    const triageRequest = store.triage(triageInput)
    await Promise.resolve()
    expect(store.loading).toBe(true)
    expect(store.dashboardLoading).toBe(false)
    expect(store.dashboardError).toBeNull()
    pendingTriage.resolve(triageResult)
    await triageRequest

    setAiClient({ draftNotice: async () => { throw new Error('公告起草失败') } })
    await expect(store.draftNotice({
      points: '周五巡检', type: '通知', tone: '正式', audience: '全体',
    })).rejects.toThrow('公告起草失败')
    expect(store.error).toBe('公告起草失败')
    expect(store.dashboardLoading).toBe(false)
    expect(store.dashboardError).toBeNull()
    expect(store.noticeDraft).toBeNull()
  })

  it('风险 Store 加载、筛选、处置、错误与 reset', async () => {
    const store = useAiRiskStore()
    await store.load({ type: '维修风险' })
    expect(store.total).toBe(6)
    expect(store.cases.map((risk) => risk.id)).toEqual([
      'risk-001',
      'risk-006',
      'risk-010',
      'risk-014',
      'risk-017',
      'risk-021',
    ])
    expect(store.cases.every((risk) => risk.type === '维修风险')).toBe(true)
    expect(store.selected?.id).toBe('risk-001')
    expect(store.selected?.type).toBe('维修风险')
    const version = store.selected!.caseVersion
    await store.act(store.selected!.id, 'resolve', '已完成现场复核')
    expect(store.selected?.state).toBe('resolved')
    expect(store.selected?.caseVersion).toBe(version + 1)
    setAiClient({ listRiskCases: async () => { throw new Error('风险接口失败') } })
    await store.load()
    expect(store.error).toBe('风险接口失败')
    store.resetSession()
    expect(store.cases).toEqual([])
  })

  it('风险总览按服务端 total 拉取全部分页并独立暴露失败', async () => {
    const template = (await new DemoAiClient().listRiskCases()).records[0]!
    const allCases = Array.from({ length: 101 }, (_, index) => ({
      ...structuredClone(template),
      id: `risk-overview-${index + 1}`,
      subjectToken: `risk_overview_token_${index + 1}`,
      businessSnapshot: {
        ...structuredClone(template.businessSnapshot),
        subjectToken: `risk_overview_token_${index + 1}`,
      },
    }))
    const requestedPages: number[] = []
    setAiClient({
      listRiskCases: async (input = {}) => {
        const page = input.page ?? 1
        const pageSize = input.pageSize ?? 100
        requestedPages.push(page)
        const offset = (page - 1) * pageSize
        return { records: allCases.slice(offset, offset + pageSize), total: allCases.length, page, pageSize }
      },
    })
    const store = useAiRiskStore()

    await store.loadOverview()

    expect(requestedPages).toEqual([1, 2])
    expect(store.overviewCases).toHaveLength(101)
    expect(store.overviewError).toBeNull()

    setAiClient({ listRiskCases: async () => { throw new Error('风险总览接口失败') } })
    await store.loadOverview()
    expect(store.overviewCases).toEqual([])
    expect(store.overviewError).toBe('风险总览接口失败')
  })

  it('风险处置成功后同步当前页、总览和选中案例', async () => {
    const initial = structuredClone((await new DemoAiClient().listRiskCases({ type: '维修风险' })).records[0]!)
    const changed = structuredClone(initial)
    changed.state = 'acknowledged'
    changed.caseVersion += 1
    setAiClient({
      listRiskCases: async (input = {}) => ({
        records: [structuredClone(initial)],
        total: 1,
        page: input.page ?? 1,
        pageSize: input.pageSize ?? 10,
      }),
      updateRiskCase: async () => structuredClone(changed),
    })
    const store = useAiRiskStore()

    await store.load()
    await store.loadOverview()
    expect(store.cases[0]).not.toBe(store.overviewCases[0])

    await store.act(initial.id, 'acknowledge', '已完成现场核验')

    expect(store.cases.find((item) => item.id === initial.id)).toEqual(changed)
    expect(store.overviewCases.find((item) => item.id === initial.id)).toEqual(changed)
    expect(store.selected).toEqual(changed)
  })

  it('风险 Store 对缺失能力、非 Error 失败、不可见案例和空说明安全失败', async () => {
    const store = useAiRiskStore()
    expect(store.selected).toBeNull()

    setAiClient({})
    await store.load()
    await expect(store.act('missing', 'acknowledge', '不会执行')).resolves.toBeUndefined()
    expect(store.loading).toBe(false)

    setAiClient({ listRiskCases: async () => { throw '非 Error 失败' } })
    await store.load()
    expect(store.error).toBe('风险数据加载失败')

    setAiClient({ listRiskCases: async () => ({ records: [], total: 0, page: 1, pageSize: 20 }) })
    store.selectedId = ''
    await store.load()
    expect(store.selectedId).toBe('')

    setAiClient(new DemoAiClient())
    await store.load()
    const visible = store.cases[0]!
    store.selectedId = 'missing'
    expect(store.selected).toBeNull()
    await expect(store.act('missing', 'resolve', '人工复核')).rejects.toThrow('风险案例不可见')
    await expect(store.act(visible.id, 'resolve', '   ')).rejects.toThrow('处置说明不能为空')

    let observed: { caseVersion: number; detail: string; dueAt?: string } | undefined
    const changed = JSON.parse(JSON.stringify(visible)) as typeof visible
    changed.state = 'acknowledged'
    setAiClient({
      updateRiskCase: async (_id, _action, input) => {
        observed = input
        return changed
      },
    })
    await store.act(visible.id, 'acknowledge', '  已现场核验  ', '2026-07-13T08:00:00+08:00')
    expect(observed).toEqual({
      caseVersion: visible.caseVersion,
      detail: '已现场核验',
      dueAt: '2026-07-13T08:00:00+08:00',
    })
    expect(store.cases.find((item) => item.id === visible.id)?.state).toBe('acknowledged')

    store.cases = [visible]
    setAiClient({
      updateRiskCase: async () => {
        store.cases = []
        return changed
      },
    })
    await store.act(visible.id, 'resolve', '再次核验')
    expect(store.cases).toEqual([])
  })

  it('风险列表采用最后请求优先，且会话重置后不回灌迟到响应', async () => {
    const first = deferred<{ records: Array<{ id: string }>; total: number; page: number; pageSize: number }>()
    const second = deferred<{ records: Array<{ id: string }>; total: number; page: number; pageSize: number }>()
    setAiClient({
      listRiskCases: vi.fn().mockImplementation((input = {}) => input.keyword === 'A' ? first.promise : second.promise),
    })
    const store = useAiRiskStore()

    const loadFirst = store.load({ keyword: 'A' })
    const loadSecond = store.load({ keyword: 'B' })
    second.resolve({ records: [{ id: 'risk-b' }], total: 1, page: 1, pageSize: 10 })
    await loadSecond
    first.resolve({ records: [{ id: 'risk-a' }], total: 1, page: 1, pageSize: 10 })
    await loadFirst
    expect(store.cases.map((item) => item.id)).toEqual(['risk-b'])
    expect(store.page).toBe(1)
    expect(store.loading).toBe(false)

    const stale = deferred<{ records: Array<{ id: string }>; total: number; page: number; pageSize: number }>()
    setAiClient({ listRiskCases: vi.fn().mockReturnValue(stale.promise) })
    const pending = store.load({ keyword: 'old-account' })
    store.resetSession()
    stale.resolve({ records: [{ id: 'old-risk' }], total: 1, page: 1, pageSize: 10 })
    await pending
    expect(store.cases).toEqual([])
    expect(store.selectedId).toBe('')
    expect(store.loading).toBe(false)
  })

  it('风险处置响应在会话重置后不写回旧案例', async () => {
    const current = (await new DemoAiClient().listRiskCases({ type: '维修风险' })).records[0]!
    const pending = deferred<typeof current>()
    setAiClient({ updateRiskCase: vi.fn().mockReturnValue(pending.promise) })
    const store = useAiRiskStore()
    store.cases = [current]
    store.selectedId = current.id

    const action = store.act(current.id, 'acknowledge', '已复核')
    store.resetSession()
    pending.resolve({ ...current, state: 'acknowledged', caseVersion: current.caseVersion + 1 })
    await action
    expect(store.cases).toEqual([])
    expect(store.overviewCases).toEqual([])
  })

  it('审批 Store 加载、批准、拒绝、刷新和审计', async () => {
    const store = useAiApprovalStore()
    await store.loadProposals()
    expect(store.proposals.length).toBeGreaterThan(0)
    await store.approve('确认')
    expect(store.selected?.executionState).toBe('succeeded')

    store.selectedId = 'proposal-notice-001'
    await store.reject('拒绝')
    expect(store.selected?.state).toBe('rejected')
    store.selectedId = 'proposal-expired-001'
    await store.refresh()
    expect(store.selected?.state).toBe('expired')
    await store.loadAudits()
    expect(store.audits).toHaveLength(1)
    store.resetSession()
    expect(store.proposals).toEqual([])
  })

  it('审批 Store 记录加载错误，缺失方法安全返回', async () => {
    const store = useAiApprovalStore()
    setAiClient({ listProposals: async () => { throw new Error('审批接口失败') } })
    await store.loadProposals()
    expect(store.error).toBe('审批接口失败')
    setAiClient({})
    await store.loadProposals()
    await store.approve()
    await store.reject()
    await store.refresh()
    await store.loadAudits()
    expect(store.loading).toBe(false)
  })

  it('审批 Store 审计加载失败后清空旧审计缓存', async () => {
    const store = useAiApprovalStore()
    const audit = (await new DemoAiClient().listAuditRuns({ page: 1, pageSize: 20 })).records[0]!
    store.audits = [audit]
    store.selectedAuditId = audit.id
    store.auditDetail = {
      run: audit,
      steps: audit.steps,
      retrievals: [],
      tools: [],
      citations: [],
      proposals: [],
      approvals: [],
      executions: [],
      usage: [],
      hashChain: [],
    }
    setAiClient({ listAuditRuns: async () => { throw new Error('审计接口失败') } })

    await expect(store.loadAudits()).rejects.toThrow('审计接口失败')

    expect(store.audits).toEqual([])
    expect(store.selectedAuditId).toBe('')
    expect(store.auditDetail).toBeNull()
    expect(store.auditTotal).toBe(0)
  })
})
