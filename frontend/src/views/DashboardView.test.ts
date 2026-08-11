import { flushPromises, mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useDormitoryStore } from '../stores/dormitory'
import { useAiStore } from '../stores/ai'
import { useAiApprovalStore } from '../stores/aiApproval'
import type { AiDashboardInsight, AiProposalPreview } from '../types/ai'
import DashboardView from './DashboardView.vue'

const dashboardViewSource = readFileSync(resolve(process.cwd(), 'src/views/DashboardView.vue'), 'utf8')
const globalStylesSource = readFileSync(resolve(process.cwd(), 'src/style.css'), 'utf8')
const aiCommandBarSource = readFileSync(resolve(process.cwd(), 'src/components/ai/AiCommandBar.vue'), 'utf8')

const { ariaComponent, authState, messageError, registeredEchartsModules } = vi.hoisted(() => ({
  ariaComponent: { name: 'AriaComponent' },
  authState: {
    user: {
      userName: '管理员',
      roleCode: 'ADMIN',
      roleCodes: ['ADMIN'],
      permissions: ['*'],
    },
    hasPermission: vi.fn((_permission: string) => true),
  },
  messageError: vi.fn(),
  registeredEchartsModules: [] as unknown[][],
}))

vi.mock('echarts/charts', () => ({ BarChart: {}, LineChart: {} }))
vi.mock('echarts/components', () => ({ AriaComponent: ariaComponent, GridComponent: {}, LegendComponent: {}, TooltipComponent: {} }))
vi.mock('echarts/core', () => ({ use: (modules: unknown[]) => registeredEchartsModules.push(modules) }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))
vi.mock('vue-echarts', () => ({ default: { name: 'VChart', props: ['option'], template: '<div class="v-chart-stub" />' } }))
vi.mock('ant-design-vue', () => ({ message: { error: messageError } }))
vi.mock('../stores/auth', () => ({ useAuthStore: () => authState }))
vi.mock('../components/business/StatisticCard.vue', () => ({
  default: { name: 'StatisticCard', template: '<div class="statistic-card-stub" />' },
}))

async function createDashboardRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: ['/', '/repairs', '/applications', '/payments', '/notices', '/notices/create', '/ai/risks', '/ai/approvals'].map((path) => ({
      path,
      component: { template: '<div />' },
    })),
  })
  await router.push('/')
  await router.isReady()
  return router
}

function createProposal(overrides: Partial<AiProposalPreview> = {}): AiProposalPreview {
  return {
    id: 'proposal-repair-dashboard',
    actionType: 'REPAIR_ASSIGN',
    title: '维修指派建议',
    target: '维修单 R-17',
    currentValue: '未指派',
    proposedValue: '维修员 A',
    impact: '仅变更维修负责人，不改变工单状态',
    requiredPermission: 'ai:approval:review + ADMIN + repair:write',
    payloadHash: 'a'.repeat(64),
    businessSnapshotHash: 'b'.repeat(64),
    version: 1,
    expiresAt: '2099-08-10T12:00:00+08:00',
    riskLevel: 'high',
    evidence: {
      basis: 'deterministic',
      confidence: 0.88,
      asOf: '2026-08-09T10:15:00+08:00',
      citations: [{ id: 'repair-17', label: '维修单快照', locator: 'REPAIR_ORDER:17', version: 'v1', access: 'available' }],
      grounded: true,
    },
    state: 'pending_approval',
    auditAvailable: true,
    executionState: 'pending',
    ...overrides,
  }
}

function createInsight(overrides: Partial<AiDashboardInsight> = {}): AiDashboardInsight {
  return {
    id: 'dashboard-1',
    summary: '整体运营平稳，维修积压需要人工核验。',
    metricVersion: 'v1',
    riskCounts: [
      { type: '入住风险', count: 1, severity: 'medium' },
      { type: '维修风险', count: 2, severity: 'high' },
      { type: '卫生风险', count: 1, severity: 'medium' },
      { type: '欠费风险', count: 2, severity: 'high' },
    ],
    pendingApprovals: 3,
    evidence: {
      confidence: 0.92,
      asOf: '2026-07-11T10:30:00',
      citations: [{
        id: 'metric-1', label: '宿舍运营指标', locator: 'dashboard:v1', version: 'v1', access: 'available',
      }],
      grounded: true,
    },
    state: 'succeeded',
    intent: {
      metricIds: [], dateRange: { preset: 'LAST_7_DAYS' }, dimensions: [], filters: {}, presentationHint: 'CARD',
    },
    metrics: {},
    queryParameters: {
      dateRange: { preset: 'LAST_7_DAYS', from: '2026-07-05', to: '2026-07-11' }, dimensions: [], filters: {},
    },
    ...overrides,
  }
}

describe('DashboardView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    authState.hasPermission.mockReturnValue(true)
  })

  it('使用 store 待办总数并渲染所有业务导航链接', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    const ai = useAiStore()
    const approval = useAiApprovalStore()
    store.$patch({
      statistics: [
        { title: '宿舍总数', value: 4, unit: '间', icon: 'building', color: 'blue', change: '实时数据' },
        { title: '学生入住人数', value: 18, unit: '人', icon: 'team', color: 'green', change: '实时数据' },
        { title: '空余床位', value: 6, unit: '个', icon: 'bed', color: 'purple', change: '实时数据' },
        { title: '待维修数量', value: 2, unit: '条', icon: 'tool', color: 'orange', change: '待处理' },
      ],
      pendingCounts: { repairs: 10, applications: 4, payments: 7 },
      notices: [{ id: 1, title: '最新通知', type: '宿舍通知', date: '2026-07-11', publisher: '管理员', status: '已发布' }],
      dormitories: [
        { id: 1, name: '男生宿舍', type: '男生宿舍', building: '1号楼', beds: 4, occupied: 2, vacant: 2, status: '入住中' },
        { id: 2, name: '女生宿舍', type: '女生宿舍', building: '2号楼', beds: 4, occupied: 3, vacant: 1, status: '入住中' },
        { id: 3, name: '混合宿舍', type: '混合宿舍', building: '3号楼', beds: 4, occupied: 1, vacant: 3, status: '入住中' },
      ],
      checkInTrend: [{ date: '07-11', value: 6 }],
    })
    ai.$patch({ dashboardInsight: createInsight() })
    approval.$patch({
      proposals: [
        createProposal(),
        createProposal({
          id: 'proposal-notice-dashboard',
          actionType: 'NOTICE_CREATE_DRAFT',
          title: '公告草稿建议',
          target: '宿舍安全检查通知',
          currentValue: '无草稿',
          proposedValue: '创建纯文本草稿',
          impact: '仅创建草稿，不发布公告',
          requiredPermission: 'ai:approval:review + notice:write',
          payloadHash: 'c'.repeat(64),
          businessSnapshotHash: 'd'.repeat(64),
          riskLevel: 'medium',
        }),
      ],
      proposalTotal: 2,
    })
    vi.spyOn(approval, 'loadProposals').mockResolvedValue(undefined)
    const loadAll = vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, {
      global: {
        plugins: [pinia, router],
        stubs: { StatisticCard: true },
      },
    })
    await flushPromises()

    expect(loadAll).toHaveBeenCalledWith(true, ['*'])
    expect(wrapper.findAll('[data-dashboard-metric]')).toHaveLength(5)
    expect(wrapper.findAll('[data-brief-meta]')).toHaveLength(3)
    expect(wrapper.get('.ai-brief__heading').findAll('[data-brief-meta]')).toHaveLength(3)
    expect(wrapper.get('.ai-brief__heading').find('.ai-brief__citations').exists()).toBe(true)
    expect(wrapper.get('.ai-brief__meta').text()).not.toContain('引用来源')
    expect(wrapper.get('[aria-label="核心运营指标"]').text()).toContain('宿舍总数')
    expect(wrapper.get('[aria-label="核心运营指标"]').text()).toContain('待审批')
    expect(wrapper.get('[data-dashboard-metric="approvals"]').text()).toContain('2 项')
    expect(wrapper.get('[data-dashboard-metric="approvals"]').text()).not.toContain('3 项')
    expect(wrapper.get('[data-brief-meta="confidence"]').attributes('aria-label')).toBe('置信度 92%')
    expect(wrapper.get('[data-brief-meta="citations"] summary').attributes('aria-label')).toBe('引用来源 1')
    expect(wrapper.get('[data-brief-meta="as-of"]').text()).toContain('2026-07-11 10:30')
    expect(wrapper.get('[data-brief-meta="as-of"]').attributes('aria-label')).toBe('数据截至（北京时间） 2026-07-11 10:30')
    expect(wrapper.get('.ai-brief__as-of-mobile').text()).toBe('07-11 10:30')
    expect(wrapper.get('.ai-brief__version').text()).toBe('运营指标口径 v1')
    expect(wrapper.get('.ai-brief__version').attributes('title')).toBe('指标口径：v1')
    expect(wrapper.get('[aria-label="智能风险概览"]').text()).toContain('维修风险')
    expect(wrapper.findAll('[data-risk-category]')).toHaveLength(4)
    expect(wrapper.get('[data-risk-category="入住风险"]').classes()).toContain('risk-row--checkin')
    expect(wrapper.get('[data-risk-category="维修风险"]').classes()).toContain('risk-row--repair')
    expect(wrapper.get('[data-risk-category="卫生风险"]').classes()).toContain('risk-row--hygiene')
    expect(wrapper.get('[data-risk-category="欠费风险"]').classes()).toContain('risk-row--payment')
    expect(wrapper.get('[data-risk-category="维修风险"] .risk-row__status').text()).toContain('2 项风险')
    expect(wrapper.find('[data-risk-category="入住风险"] .anticon-team').exists()).toBe(true)
    expect(wrapper.find('[data-risk-category="维修风险"] .anticon-tool').exists()).toBe(true)
    expect(wrapper.find('[data-risk-category="卫生风险"] .anticon-safety-certificate').exists()).toBe(true)
    expect(wrapper.find('[data-risk-category="欠费风险"] .anticon-pay-circle').exists()).toBe(true)
    expect(wrapper.get('[aria-label="入住办理趋势"]').findComponent({ name: 'VChart' }).exists()).toBe(true)
    expect(wrapper.get('[aria-label="入住办理趋势"]').text()).toContain('当前空床 6 个')
    expect(wrapper.get('[data-dashboard-metric="dormitory"]').find('.anticon-bank').exists()).toBe(true)
    expect(wrapper.get('[data-dashboard-metric="vacant"]').find('.anticon-appstore').exists()).toBe(true)
    expect(wrapper.get('[data-dashboard-section="brief"]').find('.brief-ai-icon.anticon-robot').exists()).toBe(true)
    expect(wrapper.get('[data-dashboard-section="brief"]').find('.brief-document-icon.anticon-file-text').exists()).toBe(true)
    expect(dashboardViewSource).not.toContain('metric-card__building-icon')
    expect(dashboardViewSource).not.toContain('metric-card__bed-icon')
    expect(dashboardViewSource).not.toContain('sparkles-icon')
    expect(registeredEchartsModules.flat()).toContain(ariaComponent)
    const trendChart = wrapper.get('.dashboard-trend-chart')
    expect(trendChart.attributes('role')).toBe('img')
    expect(trendChart.attributes('aria-label')).toContain('每日入住办理人次与累计入住人次')
    const accessibleTrendTable = wrapper.get('[data-trend-accessible-table]')
    expect(accessibleTrendTable.get('caption').text()).toContain('入住办理趋势详细数据')
    expect(accessibleTrendTable.findAll('thead th').map((header) => header.text()))
      .toEqual(['日期', '入住办理（人次）', '累计入住（人次）'])
    expect(accessibleTrendTable.findAll('tbody tr')).toHaveLength(1)
    expect(accessibleTrendTable.get('tbody tr').text()).toContain('07-11')
    expect(accessibleTrendTable.get('tbody tr').text()).toContain('6')
    expect(wrapper.get('[aria-label="待处理事项"]').text()).toContain('人工审批')
    expect(wrapper.get('[aria-label="待处理事项"]').text()).toContain('10 项')
    expect(wrapper.get('[aria-label="待处理事项"]').text()).toContain('4 项')
    expect(wrapper.get('[aria-label="待处理事项"]').text()).toContain('7 项')
    const pendingHeaders = wrapper.findAll('.pending-table thead th')
    expect(pendingHeaders).toHaveLength(7)
    expect(pendingHeaders.map((header) => header.text())).toEqual([
      '事项类型', 'AI 建议（摘要）', '置信度', '变更预览（摘要）', '引用来源', '建议时间', '操作',
    ])
    const repairRow = wrapper.findAll('[data-pending-row]')
      .find((row) => row.attributes('data-pending-kind') === 'business' && row.text().includes('维修申请'))
    expect(repairRow?.get('[data-pending-column="recommendation"]').text()).toContain('人工核对')
    expect(repairRow?.get('[data-pending-column="preview"]').text()).toContain('不自动')
    expect(wrapper.find('[data-pending-column="confidence"]').exists()).toBe(true)
    expect(wrapper.find('[data-pending-column="citations"]').exists()).toBe(true)
    expect(wrapper.find('[data-pending-column="suggested-at"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-pending-row]').map((row) => row.attributes('data-pending-kind')))
      .toEqual(['ai', 'ai', 'business', 'business', 'business'])
    expect(wrapper.findAll('.pending-table__kind').map((kind) => kind.text()))
      .toEqual(['AI 建议', 'AI 建议', '业务待办', '业务待办', '业务待办'])
    const links = wrapper.findAll('a').map((link) => ({ text: link.text(), href: link.attributes('href') }))
    expect(links).toEqual(expect.arrayContaining([
      expect.objectContaining({ text: '查看维修', href: '/repairs' }),
      expect.objectContaining({ text: '进入审核', href: '/applications' }),
      expect.objectContaining({ text: '查看账单', href: '/payments' }),
    ]))
    const repairProposalRow = wrapper.get('[data-pending-approval-id="proposal-repair-dashboard"]')
    expect(repairProposalRow.get('[data-pending-column="recommendation"]').text()).toContain('维修指派建议')
    expect(repairProposalRow.get('[data-pending-column="confidence"]').text()).toBe('88%')
    expect(repairProposalRow.get('[data-pending-column="preview"]').text()).toContain('未指派 → 维修员 A')
    expect(repairProposalRow.get('[data-pending-column="citations"]').text()).toBe('1 条')
    expect(repairProposalRow.get('[data-pending-column="suggested-at"]').text()).toBe('08-09 10:15')
    expect(repairProposalRow.findAll('a').map((link) => link.text())).toEqual(['查看建议', '进入审批'])
    expect(repairProposalRow.findAll('a').map((link) => link.attributes('href'))).toEqual([
      '/ai/approvals?proposal=proposal-repair-dashboard&mode=view',
      '/ai/approvals?proposal=proposal-repair-dashboard&mode=approve',
    ])
    expect(repairProposalRow.get('[data-pending-view]').attributes('aria-label'))
      .toContain('维修单 R-17，建议时间 08-09 10:15，方案编号 proposal-repair-dashboard')
    expect(repairProposalRow.get('[data-pending-approval]').attributes('aria-label'))
      .toContain('维修单 R-17，建议时间 08-09 10:15，方案编号 proposal-repair-dashboard')
    await repairProposalRow.get('[data-pending-approval]').trigger('click')
    expect(approval.selectedId).toBe('proposal-repair-dashboard')
    expect(wrapper.get('.pending-panel__mobile-safety').text()).toContain('关键结论必须有可靠来源，否则不执行')
    expect(wrapper.get('.pending-panel__mobile-safety').text()).not.toContain('暂无可靠来源')
    const trendOption = wrapper.get('[aria-label="入住办理趋势"]').findComponent({ name: 'VChart' }).props('option') as {
      series: Array<{ name: string; itemStyle?: { color?: string } }>
    }
    expect(trendOption.series.some((series) => series.name === '入住办理')).toBe(true)
    expect(trendOption.series.find((series) => series.name === '入住办理')?.itemStyle?.color).toBe('#5b8ff9')
    expect(wrapper.findAll('[data-mobile-cta]').map((link) => link.attributes('data-mobile-cta')))
      .toEqual(['approval', 'suggestions'])
    const mobileToggle = wrapper.get('[data-mobile-pending-toggle]')
    expect(mobileToggle.text()).toContain('查看全部 5 类')
    expect(mobileToggle.attributes('aria-expanded')).toBe('false')
    await mobileToggle.trigger('click')
    expect(wrapper.findAll('[data-mobile-cta]').map((link) => link.attributes('data-mobile-cta')))
      .toEqual(['approval', 'suggestions', 'repair', 'application', 'payment'])
    expect(mobileToggle.text()).toContain('收起待办')
    expect(mobileToggle.attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('.pending-panel__heading').text()).toContain('（5 条）')
    expect(wrapper.get('.pending-panel__heading').text()).toContain('（5 类）')
    expect(wrapper.findAll('[data-dashboard-section]').map((section) => section.attributes('data-dashboard-section')))
      .toEqual(expect.arrayContaining(['command', 'metrics', 'brief', 'analysis', 'risks', 'trend', 'pending', 'guardrails', 'approval-flow']))
    expect(wrapper.get('[data-dashboard-section="guardrails"]').findAll('[data-state]')).toHaveLength(2)
    expect(wrapper.get('[data-dashboard-section="guardrails"]').text()).toContain('处理前需人工核验')
    expect(wrapper.get('[data-dashboard-section="guardrails"]').text()).toContain('必须有可靠来源')
  })

  it('入住趋势在真实全零数据下保留图表并明确解释稀疏状态', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    const ai = useAiStore()
    store.$patch({
      statistics: [
        { title: '空余床位', value: 6, unit: '个', icon: 'bed', color: 'purple', change: '实时数据' },
      ],
      checkInTrend: [
        { date: '07-28', value: 0 },
        { date: '07-29', value: 0 },
        { date: '07-30', value: 0 },
        { date: '07-31', value: 0 },
        { date: '08-01', value: 0 },
        { date: '08-02', value: 0 },
        { date: '08-03', value: 0 },
      ],
    })
    ai.$patch({ dashboardInsight: createInsight() })
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, {
      global: { plugins: [pinia, router], stubs: { StatisticCard: true } },
    })
    await flushPromises()

    const trend = wrapper.get('[aria-label="入住办理趋势"]')
    expect(trend.classes()).toContain('trend-panel--sparse')
    expect(trend.findComponent({ name: 'VChart' }).exists()).toBe(true)
    const sparseState = trend.get('[data-trend-sparse-state="zero"]')
    expect(sparseState.text()).toContain('所选周期无入住办理记录')
    expect(sparseState.text()).toContain('保留真实零值')
    expect(trend.get('.dashboard-trend-chart').attributes('aria-label')).toContain('所选周期无入住办理记录')
  })

  it('固定展示四类风险并区分不可用、已检查无风险和活跃风险', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const ai = useAiStore()
    ai.$patch({ dashboardInsight: createInsight({
      riskCounts: [
        { type: '入住风险', count: 0, severity: 'high' },
        { type: '维修风险', count: 2, severity: 'high' },
        { type: '运营风险', count: 1, severity: 'low' },
      ],
    }) })
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.findAll('[data-risk-category]')).toHaveLength(4)
    const emptyRisk = wrapper.get('[data-risk-category="入住风险"]')
    expect(emptyRisk.attributes('data-risk-state')).toBe('empty')
    expect(emptyRisk.text()).toContain('未发现')
    expect(emptyRisk.text()).toContain('0 项')
    expect(emptyRisk.text()).not.toMatch(/高风险|中风险|低风险|0 项风险/)
    expect(emptyRisk.get('.risk-row__icon').classes()).toContain('risk-row__icon--checkin')
    const activeRisk = wrapper.get('[data-risk-category="维修风险"]')
    expect(activeRisk.attributes('data-risk-state')).toBe('active')
    expect(activeRisk.text()).toContain('高风险')
    expect(activeRisk.text()).toContain('2 项风险')
    expect(activeRisk.get('.risk-row__status').text()).toContain('2 项风险')
    const unavailableRisk = wrapper.get('[data-risk-category="卫生风险"]')
    expect(unavailableRisk.attributes('data-risk-state')).toBe('unavailable')
    expect(unavailableRisk.text()).toContain('暂无数据')
    expect(unavailableRisk.text()).not.toContain('0 项风险')
    expect(unavailableRisk.attributes('aria-label')).toBe('卫生风险，暂无数据')
    expect(unavailableRisk.get('.risk-row__icon').classes()).toContain('risk-row__icon--hygiene')
    expect(wrapper.get('[data-risk-unmapped]').text()).toContain('另有 1 项运营风险')
    expect(dashboardViewSource).not.toMatch(/\.risk-row--empty\s+\.risk-row__icon/)
    expect(dashboardViewSource).toMatch(/\.risk-row__status\s*\{[^}]*font-size:\s*14px;/s)
    expect(dashboardViewSource).toMatch(/\.risk-row__status small\s*\{[^}]*color:\s*inherit;[^}]*font-size:\s*inherit;/s)
    expect(dashboardViewSource).toMatch(/\.risk-row__status--unavailable\s*\{[^}]*color:\s*#52617a;/s)
  })

  it('proposal 到期后审批入口会随响应式时钟立即失效', async () => {
    vi.useFakeTimers()
    vi.setSystemTime('2026-08-09T02:00:00.000Z')
    try {
      const pinia = createPinia()
      setActivePinia(pinia)
      const store = useDormitoryStore()
      const ai = useAiStore()
      const approval = useAiApprovalStore()
      vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
      ai.$patch({ dashboardInsight: createInsight({ pendingApprovals: 1 }) })
      approval.$patch({
        proposals: [createProposal({ expiresAt: '2026-08-09T02:00:01.000Z' })],
        proposalTotal: 1,
      })
      vi.spyOn(approval, 'loadProposals').mockResolvedValue(undefined)
      const router = await createDashboardRouter()

      const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
      await flushPromises()
      expect(wrapper.find('[data-pending-approval]').exists()).toBe(true)

      await vi.advanceTimersByTimeAsync(1_100)
      await flushPromises()
      expect(wrapper.find('[data-pending-approval]').exists()).toBe(false)
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('审批入口必须同时满足审批权限、目标业务权限和有效 proposal 合同', async () => {
    authState.hasPermission.mockImplementation((permission: string) => permission !== 'repair:write')
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    const ai = useAiStore()
    const approval = useAiApprovalStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    ai.$patch({ dashboardInsight: createInsight({ pendingApprovals: 1 }) })
    approval.$patch({ proposals: [createProposal()], proposalTotal: 1 })
    vi.spyOn(approval, 'loadProposals').mockResolvedValue(undefined)
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    const row = wrapper.get('[data-pending-approval-id="proposal-repair-dashboard"]')
    expect(row.get('[data-pending-view]').text()).toBe('查看建议')
    expect(row.find('[data-pending-approval]').exists()).toBe(false)
  })

  it('Dashboard 汇总数为零时仍按审批权限加载真实待审批 proposal', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    const ai = useAiStore()
    const approval = useAiApprovalStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    ai.$patch({ dashboardInsight: createInsight({ pendingApprovals: 0 }) })
    const loadProposals = vi.spyOn(approval, 'loadProposals').mockImplementation(async () => {
      approval.$patch({ proposals: [createProposal()], proposalTotal: 1 })
    })
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(loadProposals).toHaveBeenCalledWith(
      { page: 1, pageSize: 5, state: 'pending_approval' },
      { reconcileSelection: false },
    )
    const row = wrapper.get('[data-pending-approval-id="proposal-repair-dashboard"]')
    expect(row.get('[data-pending-view]').text()).toBe('查看建议')
    expect(row.get('[data-pending-approval]').text()).toBe('进入审批')
  })

  it('无可靠来源时拒绝展示生成摘要并要求人工核验', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const ai = useAiStore()
    ai.$patch({ dashboardInsight: createInsight({
      summary: '这段没有依据的结论不得展示',
      evidence: { confidence: 0.9, asOf: '2026-07-11T10:30:00', citations: [], grounded: false },
    }) })
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    const brief = wrapper.get('[aria-label="今日 AI 运营简报"]')
    expect(brief.text()).not.toContain('这段没有依据的结论不得展示')
    expect(brief.text()).toContain('暂无可靠来源，不生成结论')
    expect(brief.find('[data-state="NO_GROUNDED"]').exists()).toBe(true)
    expect(wrapper.findAll('[data-risk-category]').every((risk) => (
      risk.attributes('data-risk-state') === 'unavailable'
      && risk.text().includes('暂无数据')
      && !risk.text().includes('0 项风险')
    ))).toBe(true)
    expect(wrapper.get('[data-dashboard-metric="approvals"]').text()).toContain('2 项')
    expect(wrapper.find('[data-mobile-cta="suggestions"]').exists()).toBe(false)
  })

  it('引用无权限时只展示拒绝状态且不泄露来源元数据', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const ai = useAiStore()
    ai.$patch({ dashboardInsight: createInsight({
      evidence: {
        confidence: 0.92,
        asOf: '2026-07-11T10:30:00',
        grounded: true,
        citations: [{
          id: 'denied-1', label: '敏感来源名称', locator: 'secret:locator', version: 'v9', access: 'denied',
        }],
      },
    }) })
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    const brief = wrapper.get('[aria-label="今日 AI 运营简报"]')
    expect(brief.get('[data-brief-meta="citations"] summary').attributes('aria-label')).toBe('引用来源 0 / 1')
    expect(brief.text()).toContain('无权限查看此来源')
    expect(brief.text()).toContain('暂无可靠来源，不生成结论')
    expect(brief.text()).not.toContain('敏感来源名称')
    expect(brief.text()).not.toContain('secret:locator')
    expect(wrapper.findAll('[data-risk-category]').every((risk) => (
      risk.attributes('data-risk-state') === 'unavailable'
      && !risk.text().includes('0 项风险')
    ))).toBe(true)
    expect(wrapper.get('[data-dashboard-metric="approvals"]').text()).toContain('2 项')
  })

  it('混合可用与受限引用时拒绝整份 AI 聚合结论', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const ai = useAiStore()
    const approval = useAiApprovalStore()
    vi.spyOn(approval, 'loadProposals').mockImplementation(async () => {
      approval.$patch({ proposals: [], proposalTotal: 0 })
    })
    ai.$patch({ dashboardInsight: createInsight({
      summary: '这段由受限来源支撑的运营结论不得展示',
      riskCounts: [
        { type: '维修风险', count: 7, severity: 'high' },
      ],
      pendingApprovals: 9,
      evidence: {
        confidence: 0.92,
        asOf: '2026-07-11T10:30:00',
        grounded: true,
        citations: [
          {
            id: 'available-1', label: '公开运营指标', locator: 'dashboard:v1', version: 'v1', access: 'available',
          },
          {
            id: 'denied-1', label: '敏感来源名称', locator: 'secret:locator', version: 'v9', access: 'denied',
          },
        ],
      },
    }) })
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    const brief = wrapper.get('[aria-label="今日 AI 运营简报"]')
    expect(brief.get('[data-brief-meta="citations"] summary').attributes('aria-label')).toBe('引用来源 1 / 2')
    expect(brief.text()).toContain('公开运营指标')
    expect(brief.text()).toContain('无权限查看此来源')
    expect(brief.text()).not.toContain('敏感来源名称')
    expect(brief.text()).not.toContain('secret:locator')
    expect(brief.text()).not.toContain('这段由受限来源支撑的运营结论不得展示')
    expect(brief.text()).toContain('暂无可靠来源，不生成结论')
    expect(wrapper.findAll('[data-risk-category]').every((risk) => (
      risk.attributes('data-risk-state') === 'unavailable'
      && !risk.text().includes('7 项风险')
    ))).toBe(true)
    expect(wrapper.get('[data-dashboard-metric="approvals"]').text()).toContain('0 项')
    expect(wrapper.find('[data-mobile-cta="suggestions"]').exists()).toBe(false)
    expect(wrapper.find('[data-mobile-cta="approval"]').exists()).toBe(false)
  })

  it('低置信和降级结果分别显示清晰的安全状态', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const ai = useAiStore()
    ai.$patch({ dashboardInsight: createInsight({
      state: 'degraded',
      evidence: {
        confidence: 0.58,
        asOf: '2026-07-11T10:30:00',
        citations: [{
          id: 'metric-low-confidence', label: '宿舍运营指标', locator: 'dashboard:v1', version: 'v1', access: 'available',
        }],
        grounded: true,
      },
    }) })
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.get('[aria-label="今日 AI 运营简报"]').text()).toContain('置信度较低')
    expect(wrapper.get('[aria-label="今日 AI 运营简报"] [data-state="DEGRADED"]').text()).toContain('降级')
  })

  it.each([
    ['cancelled', '查询已取消'],
    ['timed_out', '查询已超时'],
    ['failed', '查询失败'],
  ] as const)('结果状态为 %s 时不展示未完成摘要', async (state, message) => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const ai = useAiStore()
    ai.$patch({ dashboardInsight: createInsight({ state, summary: '未完成摘要不可展示' }) })
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    const brief = wrapper.get('[aria-label="今日 AI 运营简报"]')
    expect(brief.text()).not.toContain('未完成摘要不可展示')
    expect(brief.text()).toContain(message)
  })

  it('AI 查询权限撤销时隐藏命令和结果并显示越权状态', async () => {
    authState.hasPermission.mockImplementation((permission: string) => permission !== 'ai:dashboard:query')
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const ai = useAiStore()
    const loadDashboard = vi.spyOn(ai, 'loadDashboard').mockResolvedValue(undefined)
    ai.$patch({ dashboardInsight: createInsight() })
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.find('[aria-label="AI 智能驾驶舱"]').exists()).toBe(false)
    expect(wrapper.find('[aria-label="今日 AI 运营简报"]').exists()).toBe(false)
    expect(wrapper.get('[data-state="NO_PERMISSION"]').text()).toContain('无权限')
    expect(loadDashboard).not.toHaveBeenCalled()
  })

  it('AI 简报加载中时呈现稳定的忙碌状态', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const ai = useAiStore()
    vi.spyOn(ai, 'loadDashboard').mockResolvedValue(undefined)
    ai.dashboardLoading = true
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    const brief = wrapper.get('[aria-label="今日 AI 运营简报"]')
    expect(brief.attributes('aria-busy')).toBe('true')
    expect(brief.text()).toContain('正在生成运营简报')
    expect(wrapper.get('.risk-panel .panel-empty').text()).toContain('正在加载风险数据')
  })

  it('AI 请求失败时显示可重试状态且不触发业务写操作', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const ai = useAiStore()
    ai.dashboardError = 'AI 服务暂不可用'
    const loadDashboard = vi.spyOn(ai, 'loadDashboard').mockResolvedValue(undefined)
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    loadDashboard.mockClear()

    const failure = wrapper.get('.dashboard-ai-state[data-state="FAILED"]')
    expect(failure.text()).toContain('AI 服务暂不可用')
    expect(wrapper.get('.risk-panel .panel-empty').text()).toContain('风险数据加载失败')
    await failure.get('button').trigger('click')
    expect(loadDashboard).toHaveBeenCalledTimes(1)
  })

  it('刷新失败时保留旧简报和风险并明确标记为上一版只读结果', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const ai = useAiStore()
    ai.$patch({
      dashboardInsight: createInsight(),
      dashboardError: 'AI 驾驶舱刷新失败，请稍后重试',
    })
    const loadDashboard = vi.spyOn(ai, 'loadDashboard').mockResolvedValue(undefined)
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()
    loadDashboard.mockClear()

    const brief = wrapper.get('[data-dashboard-section="brief"]')
    expect(brief.attributes('data-result-freshness')).toBe('stale')
    const briefWarning = brief.get('.ai-brief__warning[data-state="STALE"]')
    expect(briefWarning.text()).toContain('刷新失败')
    expect(briefWarning.text()).toContain('上一版只读结果')
    expect(brief.text()).toContain('整体运营平稳')

    const risks = wrapper.get('[data-dashboard-section="risks"]')
    expect(risks.attributes('data-result-freshness')).toBe('stale')
    expect(risks.get('.risk-panel__stale[data-state="STALE"]').text()).toContain('上一版只读结果')
    expect(risks.text()).toContain('维修风险')
    expect(wrapper.find('.dashboard-ai-state[data-state="FAILED"]').exists()).toBe(false)

    await briefWarning.get('button').trigger('click')
    expect(loadDashboard).toHaveBeenCalledTimes(1)
  })

  it('Assistant 失败不会污染 Dashboard 简报错误状态', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const ai = useAiStore()
    ai.assistantError = '授权资料检索失败，请稍后重试'
    vi.spyOn(ai, 'loadDashboard').mockResolvedValue(undefined)
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.find('.dashboard-ai-state[data-state="FAILED"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('授权资料检索失败，请稍后重试')
  })

  it('维修或公告请求状态不会污染 Dashboard 的刷新与陈旧状态', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const ai = useAiStore()
    ai.$patch({
      dashboardInsight: createInsight(),
      loading: true,
      error: '维修分诊失败，请稍后重试',
    })
    vi.spyOn(ai, 'loadDashboard').mockResolvedValue(undefined)
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    const brief = wrapper.get('[data-dashboard-section="brief"]')
    expect(brief.attributes('aria-busy')).toBe('false')
    expect(brief.attributes('data-result-freshness')).toBe('current')
    expect(brief.find('[data-state="STALE"]').exists()).toBe(false)
    expect(brief.text()).not.toContain('正在刷新运营简报')
    expect(brief.text()).not.toContain('维修分诊失败')
    expect(wrapper.find('.dashboard-ai-state[data-state="FAILED"]').exists()).toBe(false)
    await wrapper.get('.dashboard-command input').setValue('重新查询本周风险')
    expect(wrapper.get('.dashboard-command button').attributes('disabled')).toBeUndefined()
  })

  it('没有待办时显示空状态且不生成处理链接', async () => {
    authState.hasPermission.mockImplementation((permission: string) => (
      !['ai:risk:read', 'ai:approval:review'].includes(permission)
    ))
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.get('[aria-label="待处理事项"]').text()).toContain('暂无待处理事项')
    expect(wrapper.findAll('a').some((link) => {
      const href = link.attributes('href')
      return href !== undefined && ['/repairs', '/applications', '/payments'].includes(href)
    })).toBe(false)
  })

  it('移动待办在无风险和审批权限时仍显示真实业务事项', async () => {
    authState.hasPermission.mockImplementation((permission: string) => (
      !['ai:risk:read', 'ai:approval:review'].includes(permission)
    ))
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    store.$patch({ pendingCounts: { repairs: 2, applications: 1, payments: 3 } })
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(wrapper.get('.pending-panel__heading').text()).toContain('（3 条）')
    expect(wrapper.get('.pending-panel__heading').text()).toContain('（3 类）')
    expect(wrapper.findAll('.pending-mobile-list article')).toHaveLength(2)
    expect(wrapper.get('[data-mobile-cta="repair"]').element.parentElement?.textContent)
      .toContain('共有 2 项待人工处理')
    expect(wrapper.get('[data-mobile-cta="application"]').element.parentElement?.textContent)
      .toContain('共有 1 项待人工处理')
    expect(wrapper.find('[data-mobile-cta="suggestions"]').exists()).toBe(false)
    expect(wrapper.find('[data-mobile-cta="approval"]').exists()).toBe(false)
    const mobileToggle = wrapper.get('[data-mobile-pending-toggle]')
    expect(mobileToggle.text()).toContain('查看全部 3 类')
    await mobileToggle.trigger('click')
    expect(wrapper.findAll('[data-mobile-cta]').map((link) => link.attributes('data-mobile-cta')))
      .toEqual(['repair', 'application', 'payment'])
  })

  it('业务指标加载失败时显示不可用占位、单一内联错误并支持重载', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    const loadAll = vi.spyOn(store, 'loadAll')
      .mockRejectedValueOnce(new Error('仪表盘接口失败'))
      .mockResolvedValueOnce(undefined)
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(messageError).not.toHaveBeenCalled()
    const failure = wrapper.get('.dashboard-business-state[data-state="FAILED"]')
    expect(failure.text()).toContain('仪表盘接口失败')
    expect(failure.get('button').text()).toContain('重新加载')
    expect(wrapper.findAll('.metric-card:not(.metric-card--approval) .metric-card__copy strong')
      .every((value) => value.text().includes('--'))).toBe(true)
    expect(wrapper.get('[aria-label="核心运营指标"]').text()).not.toContain('0 间')
    expect(wrapper.get('.trend-panel .panel-empty').text()).toContain('趋势数据读取失败')
    expect(wrapper.get('.pending-panel .panel-empty').text()).toContain('待处理事项读取失败')

    await failure.get('button').trigger('click')
    await flushPromises()
    expect(loadAll).toHaveBeenCalledTimes(2)
    expect(wrapper.find('.dashboard-business-state[data-state="FAILED"]').exists()).toBe(false)
  })

  it('简报 UTC 时间转换为带日期的北京时间并处理跨日', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useDormitoryStore()
    vi.spyOn(store, 'loadAll').mockResolvedValue(undefined)
    const ai = useAiStore()
    ai.$patch({ dashboardInsight: createInsight({
      summary: '数据时间：2026-07-27T22:03:48.862371700Z。',
      evidence: {
        confidence: 0.92,
        asOf: '2026-07-27T22:03:48.862371700Z',
        citations: [{
          id: 'metric-1', label: '宿舍运营指标', locator: 'dashboard:v1', version: 'v1', access: 'available',
        }],
        grounded: true,
      },
    }) })
    const router = await createDashboardRouter()

    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    const summary = wrapper.get('.ai-brief__summary').text()
    expect(summary).toContain('数据时间：2026-07-28 06:03。')
    expect(summary).not.toMatch(/T22:03:48|862371700Z/)
    expect(wrapper.get('[data-brief-meta="as-of"]').attributes('aria-label'))
      .toBe('数据截至（北京时间） 2026-07-28 06:03')
    expect(wrapper.get('.ai-brief__as-of-mobile').text()).toBe('07-28 06:03')
  })

  it('桌面简报保留完整可读摘要并让待处理区进入首屏', () => {
    const desktopStyles = dashboardViewSource.slice(
      dashboardViewSource.indexOf('@media (min-width: 769px)'),
      dashboardViewSource.indexOf('@media (max-width: 1100px)'),
    )

    expect(desktopStyles).toMatch(/\.ai-brief__summary\s*\{[^}]*white-space:\s*normal;[^}]*overflow-wrap:\s*anywhere/s)
    expect(desktopStyles).toMatch(/\.ai-brief__facts\s*\{[^}]*min-height:\s*40px/s)
    expect(desktopStyles).toMatch(/\.ai-brief__warning\s*\{[^}]*min-height:\s*36px;[^}]*font-size:\s*14px/s)
    expect(dashboardViewSource).toMatch(/\.pending-table\s*\{[^}]*font-size:\s*14px/s)
    expect(dashboardViewSource).toMatch(/td\[data-pending-column=['"]recommendation['"]\][^}]*white-space:\s*normal/s)
    expect(dashboardViewSource).toMatch(/td\[data-pending-column=['"]preview['"]\][^}]*white-space:\s*normal/s)
    expect(dashboardViewSource).toMatch(/\.pending-table__actions a\s*\{[^}]*min-height:\s*44px/s)
    expect(dashboardViewSource).toMatch(/@media \(max-width:\s*1100px\)\s*\{[^]*?\.pending-table\s*\{[^}]*display:\s*none/s)
    expect(dashboardViewSource).not.toMatch(/@media \(max-width:\s*1399px\)\s*\{[^]*?\.pending-table\s*\{[^}]*display:\s*none/s)
    expect(dashboardViewSource).toMatch(/\.pending-reflow-list dd\s*\{[^}]*overflow-wrap:\s*anywhere/s)
    expect(dashboardViewSource).toMatch(/\.dashboard-ai-state,\s*\.dashboard-business-state\s*\{[^}]*font-size:\s*14px/s)
    expect(desktopStyles).toMatch(/\.brief-document-icon\s*\{[^}]*display:\s*none/s)
  })

  it('移动端保持原型信息层级且不靠小字号把全部内容硬塞进首屏', () => {
    const mobileStyles = dashboardViewSource.slice(dashboardViewSource.lastIndexOf('@media (max-width: 768px)'))

    expect(mobileStyles).toMatch(/\.dashboard-view\s*\{[^}]*gap:\s*4px/)
    expect(mobileStyles).toMatch(/\.dashboard-command :deep\(\.ai-command-bar\)\s*\{[^}]*min-height:\s*52px/)
    expect(mobileStyles).toMatch(/\.dashboard-command :deep\(\.ai-command-bar__input\)\s*\{[^}]*height:\s*52px;[^}]*font-size:\s*16px/)
    expect(mobileStyles).toMatch(/\.dashboard-command :deep\(\.ai-command-bar__button\)\s*\{[^}]*width:\s*44px;[^}]*min-width:\s*44px;[^}]*min-height:\s*44px/)
    expect(mobileStyles).toMatch(/\.metric-grid\s*\{[^}]*gap:\s*8px/)
    expect(mobileStyles).toMatch(/\.metric-card\s*\{[^}]*min-height:\s*70px/)
    expect(mobileStyles).toMatch(/\.metric-card__copy > span\s*\{[^}]*font-size:\s*14px/)
    expect(mobileStyles).toMatch(/\.metric-card__copy strong\s*\{[^}]*font-size:\s*26px/)
    expect(mobileStyles).toMatch(/\.metric-card__trend\s*\{[^}]*position:\s*absolute/)
    expect(mobileStyles).toMatch(/\.ai-brief__facts > \[data-brief-meta\]\s*\{[^}]*min-height:\s*52px/)
    expect(mobileStyles).toMatch(/\.ai-brief__facts > \.ai-brief__citations\s*\{[^}]*display:\s*block/)
    expect(mobileStyles).toMatch(/\.ai-brief__warning\s*\{[^}]*min-height:\s*44px;[^}]*display:\s*flex/)
    expect(mobileStyles).not.toMatch(/\.ai-brief__warning\s*\{[^}]*display:\s*none/)
    expect(mobileStyles).toMatch(/\.ai-brief__fact-label\s*\{[^}]*font-size:\s*14px/)
    expect(mobileStyles).toMatch(/\.ai-brief__fact-value\s*\{[^}]*font-size:\s*22px/)
    expect(mobileStyles).toMatch(/\.risk-list\s*\{[^}]*grid-template-columns:\s*repeat\(4,\s*minmax\(0,\s*1fr\)\);[^}]*padding:\s*4px 8px 6px/s)
    expect(mobileStyles).toMatch(/\.risk-row\s*\{[^}]*min-height:\s*100px/)
    expect(mobileStyles).not.toMatch(/\.risk-row\s*\{[^}]*height:\s*44px/)
    expect(mobileStyles).toMatch(/\.risk-row__label strong\s*\{[^}]*font-size:\s*14px/)
    expect(mobileStyles).toMatch(/\.risk-row__status\s*\{[^}]*font-size:\s*14px/)
    expect(mobileStyles).toMatch(/\.risk-row__description,\s*\.risk-row__arrow\s*\{[^}]*display:\s*none/)
    expect(mobileStyles).toMatch(/\.trend-panel\s*\{[^}]*min-height:\s*200px/)
    expect(mobileStyles).toMatch(/\.trend-window select\s*\{[^}]*font-size:\s*14px/)
    expect(mobileStyles).toMatch(/\.trend-meta\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\);[^}]*font-size:\s*14px/s)
    expect(mobileStyles).toMatch(/\.trend-meta strong\s*\{[^}]*display:\s*none/s)
    expect(mobileStyles).toMatch(/\.dashboard-trend-chart\s*\{[^}]*height:\s*125px/)
    expect(mobileStyles).toMatch(/\.risk-panel\s*\{[^}]*order:\s*-1;[^}]*min-height:\s*auto/)
    expect(dashboardViewSource).not.toContain('.risk-row__count small')
    expect(dashboardViewSource).toMatch(/\.risk-row__description\s*\{[^}]*font-size:\s*13px/)
    expect(dashboardViewSource).toMatch(/\.pending-table__count\s*\{[^}]*font-size:\s*12px/)
    expect(dashboardViewSource).toContain('animation: !prefersReducedMotion')
    expect(mobileStyles).toMatch(/\.pending-panel__mobile-safety\s*\{[^}]*display:\s*grid/)
    expect(mobileStyles).toMatch(/\.approval-flow--mobile\s*\{[^}]*display:\s*flex/)
    expect(mobileStyles).toMatch(/\.pending-mobile-list article\s*\{[^}]*min-height:\s*62px/)
    expect(mobileStyles).toMatch(/\.pending-panel__mobile-toggle\s*\{[^}]*min-height:\s*44px/)
    expect(mobileStyles).toMatch(/\.pending-mobile-list strong\s*\{[^}]*font-size:\s*14px/)
    expect(mobileStyles).toMatch(/\.pending-mobile-list small\s*\{[^}]*font-size:\s*14px/)
    expect(mobileStyles).toMatch(/\.pending-panel__mobile-safety :deep\(\.ai-safety-state\)\s*\{[^}]*min-height:\s*44px;[^}]*font-size:\s*14px/)
    expect(mobileStyles).toMatch(/\.pending-panel__guardrail\s*\{[^}]*min-height:\s*80px;[^}]*gap:\s*6px;[^}]*font-size:\s*14px/)
    expect(mobileStyles).toMatch(/\.ai-brief__warning\s*\{[^}]*font-size:\s*14px/)
    expect(mobileStyles).toMatch(/\.brief-ai-icon\s*\{[^}]*display:\s*none/)
    expect(mobileStyles).toMatch(/\.brief-document-icon\s*\{[^}]*display:\s*inline-flex/)
    expect(dashboardViewSource).toMatch(/\.pending-reflow-list dd\s*\{[^}]*font-size:\s*14px/)
    expect(dashboardViewSource).toMatch(/\.panel-heading a\s*\{[^}]*min-height:\s*44px/)
    expect(dashboardViewSource).toMatch(/\.risk-row__status\s*\{[^}]*display:\s*inline-flex/)
    expect(dashboardViewSource).toContain('class="ai-brief__as-of-mobile"')
    expect(dashboardViewSource).toContain('class="ai-brief__fact-label"')
    expect(dashboardViewSource).toContain('class="ai-brief__fact-value"')
  })

  it('移动稀疏与降级组合使用专用密度并保留完整状态语义', () => {
    const mobileStyles = dashboardViewSource.slice(dashboardViewSource.lastIndexOf('@media (max-width: 768px)'))

    expect(mobileStyles).toMatch(/\.dashboard-analysis\s*\{[^}]*gap:\s*6px/)
    expect(mobileStyles).toMatch(/\.ai-brief__warning\s*\{[^}]*min-height:\s*44px;[^}]*margin:\s*4px 10px 4px/s)
    expect(mobileStyles).toMatch(/\.trend-panel--sparse\s*\{[^}]*min-height:\s*160px/)
    expect(mobileStyles).toMatch(/\.trend-panel--sparse \.dashboard-trend-stage,\s*\.trend-panel--sparse \.dashboard-trend-chart\s*\{[^}]*height:\s*84px/)
    expect(mobileStyles).toMatch(/\.trend-panel--sparse \.trend-sparse-state\s*\{[^}]*top:\s*5px;[^}]*padding:\s*5px 8px/s)
    expect(mobileStyles).not.toMatch(/\.trend-panel--sparse[^}]*display:\s*none/)
  })

  it('1586 桌面密度保留 44px 操作目标并压入完整待办守护栏', () => {
    expect(dashboardViewSource).toMatch(/\.dashboard-view\s*\{[^}]*gap:\s*10px/)
    expect(dashboardViewSource).toMatch(/\.pending-panel__heading\s*\{[^}]*min-height:\s*44px/)
    expect(dashboardViewSource).toMatch(/\.pending-table th\s*\{[^}]*height:\s*34px/)
    expect(dashboardViewSource).toMatch(/\.pending-table td\s*\{[^}]*height:\s*48px/)
    expect(dashboardViewSource).toMatch(/\.pending-panel__guardrail\s*\{[^}]*min-height:\s*44px/)
    expect(dashboardViewSource).toMatch(/\.pending-table__actions a\s*\{[^}]*min-height:\s*44px/)
    expect(dashboardViewSource).toMatch(/@media \(min-width:\s*1101px\) and \(max-height:\s*1000px\)[\s\S]*\.dashboard-view\s*\{[^}]*gap:\s*7px;[\s\S]*\.dashboard-trend-stage\s*\{[^}]*height:\s*204px;[\s\S]*\.pending-panel__guardrail\s*\{[^}]*min-height:\s*32px;/)
  })

  it('焦点指示器达到 3:1 可辨识度且审批流程只暴露三个真实步骤', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    vi.spyOn(useDormitoryStore(), 'loadAll').mockResolvedValue(undefined)
    useAiStore().$patch({ dashboardInsight: createInsight() })
    const router = await createDashboardRouter()
    const wrapper = mount(DashboardView, { global: { plugins: [pinia, router] } })
    await flushPromises()

    expect(globalStylesSource).toMatch(/--focus-ring:\s*0 0 0 3px #2563eb;/)
    expect(dashboardViewSource).toMatch(/summary:focus-visible\s*\{[^}]*outline:\s*3px solid var\(--primary\)/s)
    expect(aiCommandBarSource).toMatch(/\.ai-command-bar__button:focus-visible\s*\{[^}]*outline:\s*3px solid var\(--primary\)/s)
    expect(aiCommandBarSource).not.toContain('rgb(37 99 235 / 28%)')
    expect(dashboardViewSource).toMatch(/\.legend-bar\s*\{[^}]*background:\s*#5b8ff9/s)
    for (const flow of wrapper.findAll('.approval-flow')) {
      expect(flow.element.children).toHaveLength(3)
    }
  })
})
