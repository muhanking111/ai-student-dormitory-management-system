import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter, RouterLink } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { message } from 'ant-design-vue'
import { DemoAiClient } from '../api/ai-demo'
import { setAiClient } from '../api/ai-client'
import { useAiApprovalStore } from '../stores/aiApproval'
import { useAiRiskStore } from '../stores/aiRisk'
import { useAuthStore } from '../stores/auth'
import type { AiAuditRun, AiAuditRunQuery, AiProposalPreview } from '../types/ai'
import AiApprovalView from './AiApprovalView.vue'
import AiAuditView from './AiAuditView.vue'
import AiRiskView from './AiRiskView.vue'

vi.mock('ant-design-vue', () => ({
  message: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}))

vi.mock('../api/ai-client', async () => {
  const actual = await vi.importActual<typeof import('../api/ai-client')>('../api/ai-client')
  return {
    ...actual,
    getAiClientMode: () => 'demo',
    isAiSurfaceEnabled: () => true,
  }
})

const globalStubs = {
  AInputPassword: true,
  ATextarea: {
    props: ['value'],
    emits: ['update:value'],
    template: '<textarea :value="value" @input="$emit(\'update:value\', $event.target.value)" />',
  },
  ASelect: true,
  ASegmented: true,
  AModal: true,
  AiSafetyState: true,
  AiEvidenceMeta: true,
  AiProposalPreview: true,
  VChart: true,
}

const riskModalStub = {
  props: ['open', 'title'],
  template: '<div v-if="open" role="dialog" :aria-label="title"><slot /><slot name="footer" /></div>',
}

function authorize(permissions: string[]) {
  const auth = useAuthStore()
  auth.$patch({
    initialized: true,
    user: {
      id: 7,
      userName: 'GovernanceTester',
      roleCode: 'ADMIN',
      roleCodes: ['ADMIN'],
      permissions,
    },
  })
}

function proposal(id: string, actionType: AiProposalPreview['actionType'] = 'REPAIR_ASSIGN'): AiProposalPreview {
  return {
    id,
    actionType,
    title: id,
    target: `target-${id}`,
    currentValue: '当前值',
    proposedValue: '建议值',
    impact: '仅影响当前目标',
    requiredPermission: 'ai:approval:review',
    payloadHash: `payload-${id}`,
    businessSnapshotHash: `snapshot-${id}`,
    version: 1,
    expiresAt: '2099-07-21T10:00:00+08:00',
    riskLevel: 'medium',
    evidence: { basis: 'deterministic', asOf: '2026-07-20T10:00:00+08:00', citations: [], grounded: true },
    state: 'pending_approval',
    auditAvailable: true,
    executionState: 'pending',
  }
}

function auditRun(id: string): AiAuditRun {
  return {
    id,
    capability: 'REPAIR',
    state: 'succeeded',
    modelAlias: 'fake-model',
    promptVersion: 'v1',
    citationCount: 1,
    durationMs: 120,
    inputTokens: 20,
    outputTokens: 10,
    estimatedCost: 0.001,
    currency: 'CNY',
    chainHash: `chain-${id}`,
    occurredAt: '2026-07-20T10:00:00+08:00',
    steps: [],
  }
}

async function governanceRouter(path: '/ai/approvals' | '/ai/audit') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/ai/approvals', component: { template: '<div />' } },
      { path: '/ai/audit', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  return router
}

describe('AI governance high-fidelity surface contracts', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    const pinia = createPinia()
    setActivePinia(pinia)
    setAiClient(new DemoAiClient())
  })

  it('风险中心提供四类总览、图表、规则状态、筛选分页和四层证据', async () => {
    authorize(['ai:risk:read', 'ai:risk:manage'])
    const wrapper = shallowMount(AiRiskView, {
      global: { plugins: [createPinia()], stubs: globalStubs },
    })
    await flushPromises()

    expect(wrapper.findAll('[data-testid="risk-summary-card"]')).toHaveLength(4)
    expect(wrapper.findAll('[data-testid="risk-summary-sparkline"]')).toHaveLength(4)
    expect(wrapper.findAll('.summary-rate')).toHaveLength(4)
    expect(wrapper.findAll('[data-testid="risk-summary-sparkline"] polyline')
      .every((line) => Boolean(line.attributes('points')))).toBe(true)
    expect(wrapper.text()).toContain('风险趋势')
    expect(wrapper.text()).toContain('风险分布')
    expect(wrapper.text()).toContain('规则状态')
    expect(wrapper.find('[aria-label="风险线索筛选"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="风险线索分页"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('确定性证据')
    expect(wrapper.text()).toContain('授权业务快照')
    expect(wrapper.text()).toContain('AI 解释')
    expect(wrapper.text()).toContain('人工处置时间线')
  })

  it('风险趋势仅有一个可见月份时明确说明历史基线不足', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:risk:read', 'ai:risk:manage'])
    const wrapper = shallowMount(AiRiskView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    const store = useAiRiskStore()
    const seed = store.overviewCases[0]
    expect(seed).toBeDefined()
    store.overviewCases = [0, 1, 2].map((index) => ({
      ...seed!,
      id: `risk-sparse-${index}`,
      asOf: new Date().toISOString(),
    }))
    await wrapper.vm.$nextTick()

    const sparseState = wrapper.get('[data-risk-trend-sparse-state="limited"]')
    expect(sparseState.text()).toContain('历史基线不足')
    expect(sparseState.text()).toContain('仅 1 个月')
    expect(sparseState.text()).toContain('3 条')
    expect(wrapper.get('.risk-trend-chart').attributes('aria-label')).toContain('历史基线不足')
  })

  it('风险处置失败会保留输入并显示可访问的页面内错误', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:risk:read', 'ai:risk:manage'])
    const client = new DemoAiClient()
    const updateRiskCase = vi.spyOn(client, 'updateRiskCase').mockRejectedValue(new Error('风险版本已变化，请刷新'))
    setAiClient(client)
    const wrapper = shallowMount(AiRiskView, {
      global: { plugins: [pinia], stubs: { ...globalStubs, AModal: riskModalStub } },
    })
    await flushPromises()
    const selectedId = useAiRiskStore().selected?.id
    expect(selectedId).toBeTruthy()

    const resolve = wrapper.findAll('button').find((button) => button.text() === '标记已解决')
    expect(resolve).toBeDefined()
    await resolve!.trigger('click')

    const detail = wrapper.get<HTMLTextAreaElement>('[aria-label="风险处置说明"]')
    await detail.setValue('已人工核验当前业务事实')
    await wrapper.get('[data-testid="risk-action-submit"]').trigger('click')
    await flushPromises()

    expect(updateRiskCase).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-testid="risk-action-error"][role="alert"]').text()).toContain('风险版本已变化，请刷新')
    expect(detail.element.value).toBe('已人工核验当前业务事实')
    expect(message.error).toHaveBeenCalledWith({
      key: 'ai-risk-action',
      content: `线索 ${selectedId}：标记已解决失败。风险版本已变化，请刷新`,
    })
  })

  it('审批页形成提案列表、变更预览和运行审计三栏工作台', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review', 'ai:audit:read'])
    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="approval-proposal-rail"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="approval-preview-panel"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="approval-runtime-rail"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('当前值（将被替换）')
    expect(wrapper.text()).toContain('建议值（执行后）')
    expect(wrapper.text()).toContain('工具调用预览')
    expect(wrapper.text()).toContain('Token / 成本估算')
    expect(wrapper.text()).toContain('人工审批')
  })

  it('运行审计路由保留筛选并提供列表、详情和成本三层信息', async () => {
    authorize(['ai:audit:read'])
    const wrapper = shallowMount(AiAuditView, {
      global: { plugins: [createPinia()], stubs: globalStubs },
    })
    await flushPromises()

    expect(wrapper.find('[aria-label="运行审计筛选"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="audit-run-rail"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="audit-detail-panel"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="audit-metrics-rail"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('运行时间线')
    expect(wrapper.text()).toContain('Token / 成本')
    expect(wrapper.text()).toContain('脱敏 hash 链')
  })

  it('治理标签使用 RouterLink 在审批与审计路由间进行 SPA 导航', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const router = await governanceRouter('/ai/approvals')
    const approval = shallowMount(AiApprovalView, {
      global: { plugins: [pinia, router], stubs: { ...globalStubs, RouterLink: false } },
    })

    const approvalLinks = approval.findAllComponents(RouterLink)
    expect(approvalLinks.map((link) => link.props('to'))).toEqual(['/ai/approvals', '/ai/audit'])
    await approvalLinks[1]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/ai/audit')
    approval.unmount()

    const audit = shallowMount(AiAuditView, {
      global: { plugins: [pinia, router], stubs: { ...globalStubs, RouterLink: false } },
    })
    const auditLinks = audit.findAllComponents(RouterLink)
    expect(auditLinks.map((link) => link.props('to'))).toEqual(['/ai/approvals', '/ai/audit'])
    await auditLinks[0]!.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.path).toBe('/ai/approvals')
  })

  it('审批分页由服务端按类型筛选并让 records 与 total 保持一致', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review'])
    const first = proposal('proposal-page-1')
    const second = proposal('proposal-page-2')
    const listProposals = vi.fn(async (input: {
      page?: number
      pageSize?: number
      state?: string
      actionType?: 'REPAIR_ASSIGN' | 'NOTICE_CREATE_DRAFT'
    } = {}) => ({
      records: input.actionType === 'REPAIR_ASSIGN' ? (input.page === 2 ? [second] : [first]) : [],
      total: input.actionType === 'REPAIR_ASSIGN' ? 21 : 0,
      page: input.page ?? 1,
      pageSize: input.pageSize ?? 20,
    }))
    setAiClient({
      listProposals,
      listAuditRuns: vi.fn(async () => ({ records: [], total: 0, page: 1, pageSize: 20 })),
    })
    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()
    const store = useAiApprovalStore()
    const filter = wrapper.get<HTMLSelectElement>('[aria-label="方案类型筛选"]')
    await filter.setValue('REPAIR_ASSIGN')
    await flushPromises()

    expect(listProposals).toHaveBeenLastCalledWith({
      page: 1,
      pageSize: 20,
      actionType: 'REPAIR_ASSIGN',
    })
    expect(store.proposalTotal).toBe(21)

    const previous = wrapper.get<HTMLButtonElement>('[aria-label="上一页"]')
    const next = wrapper.get<HTMLButtonElement>('[aria-label="下一页"]')
    expect(previous.attributes('disabled')).toBeDefined()
    expect(next.attributes('disabled')).toBeUndefined()
    await next.trigger('click')
    await flushPromises()

    expect(listProposals).toHaveBeenLastCalledWith({
      page: 2,
      pageSize: 20,
      actionType: 'REPAIR_ASSIGN',
    })
    expect(filter.element.value).toBe('REPAIR_ASSIGN')
    expect(store.selectedId).toBe(second.id)
    expect(store.selected?.id).toBe(second.id)
    expect(wrapper.text()).toContain(second.id)
    expect(wrapper.text()).not.toContain(first.id)
    expect(previous.attributes('disabled')).toBeUndefined()
    expect(next.attributes('disabled')).toBeDefined()
  })

  it('审批列表加载失败显示可重试错误且成功重试会清除旧错误', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review'])
    let rejectInitialLoad!: (reason?: unknown) => void
    const pendingInitialLoad = new Promise<never>((_resolve, reject) => { rejectInitialLoad = reject })
    const recovered = proposal('proposal-recovered')
    const listProposals = vi.fn()
      .mockReturnValueOnce(pendingInitialLoad)
      .mockResolvedValueOnce({ records: [recovered], total: 1, page: 1, pageSize: 20 })
    setAiClient({
      listProposals,
      listAuditRuns: vi.fn(async () => ({ records: [], total: 0, page: 1, pageSize: 20 })),
    })
    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await wrapper.vm.$nextTick()
    expect(wrapper.text()).toContain('正在加载待审方案')

    rejectInitialLoad(new Error('审批列表暂不可用'))
    await flushPromises()
    const errorState = wrapper.findAllComponents({ name: 'AiSafetyState' })
      .find((state) => state.props('message') === '审批列表暂不可用')
    expect(errorState).toBeDefined()

    errorState!.vm.$emit('action')
    await flushPromises()
    expect(listProposals).toHaveBeenCalledTimes(2)
    expect(useAiApprovalStore().error).toBeNull()
    expect(wrapper.text()).toContain(recovered.id)
  })

  it('审批页审计列表失败只在运行审计栏显示可重试错误', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review', 'ai:audit:read'])
    const selected = proposal('proposal-with-audit-error')
    const listAuditRuns = vi.fn()
      .mockRejectedValueOnce(new Error('运行审计暂不可用'))
      .mockResolvedValueOnce({ records: [], total: 0, page: 1, pageSize: 20 })
    setAiClient({
      listProposals: vi.fn().mockResolvedValue({ records: [selected], total: 1, page: 1, pageSize: 20 }),
      listAuditRuns,
    })

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    const runtimeRail = wrapper.get('[data-testid="approval-runtime-rail"]')
    const errorState = runtimeRail.findComponent({ name: 'AiSafetyState' })
    expect(errorState.props('message')).toBe('运行审计暂不可用')
    expect(errorState.props('actionLabel')).toBe('重新加载')

    errorState.vm.$emit('action')
    await flushPromises()
    expect(listAuditRuns).toHaveBeenCalledTimes(2)
    expect(runtimeRail.findComponent({ name: 'AiSafetyState' }).exists()).toBe(false)
  })

  it('审计分页复用已应用筛选并将详情切换到新页可见运行', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:audit:read'])
    const first = auditRun('audit-page-1')
    const second = auditRun('audit-page-2')
    const listAuditRuns = vi.fn(async (input: AiAuditRunQuery = {}) => ({
      records: input.page === 2 ? [second] : [first],
      total: 21,
      page: input.page ?? 1,
      pageSize: input.pageSize ?? 20,
    }))
    const getAuditRun = vi.fn(async (id: string) => {
      const run = id === second.id ? second : first
      return {
        run, steps: [], retrievals: [], tools: [], citations: [], proposals: [], approvals: [], executions: [], usage: [], hashChain: [],
      }
    })
    setAiClient({ listAuditRuns, getAuditRun })
    const wrapper = shallowMount(AiAuditView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    await wrapper.get<HTMLSelectElement>('select').setValue('REPAIR')
    await wrapper.findAll<HTMLSelectElement>('select')[1]!.setValue('SUCCEEDED')
    await wrapper.findAll<HTMLSelectElement>('select')[2]!.setValue('fake')
    await wrapper.get('form[aria-label="运行审计筛选"]').trigger('submit')
    await flushPromises()
    listAuditRuns.mockClear()

    const previous = wrapper.get<HTMLButtonElement>('[aria-label="上一页"]')
    const next = wrapper.get<HTMLButtonElement>('[aria-label="下一页"]')
    expect(previous.attributes('disabled')).toBeDefined()
    expect(next.attributes('disabled')).toBeUndefined()
    await next.trigger('click')
    await flushPromises()

    expect(listAuditRuns).toHaveBeenCalledOnce()
    expect(listAuditRuns).toHaveBeenCalledWith({
      page: 2,
      pageSize: 20,
      capability: 'REPAIR',
      state: 'SUCCEEDED',
      provider: 'fake',
      from: undefined,
      to: undefined,
    })
    const store = useAiApprovalStore()
    expect(store.selectedAuditId).toBe(second.id)
    expect(store.auditDetail?.run.id).toBe(second.id)
    expect(wrapper.text()).toContain(second.id)
    expect(wrapper.text()).not.toContain(first.id)
    expect(previous.attributes('disabled')).toBeUndefined()
    expect(next.attributes('disabled')).toBeDefined()
  })
})
