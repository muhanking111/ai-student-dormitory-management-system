import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setAiClient } from '../api/ai-client'
import { DemoAiClient } from '../api/ai-demo'
import { fetchUsers } from '../api/rbac'
import { useAuthStore } from '../stores/auth'
import { useOperationsStore } from '../stores/operations'
import RepairManagementView from './RepairManagementView.vue'
import repairViewSource from './RepairManagementView.vue?raw'
import routerSource from '../router/index.ts?raw'

vi.mock('ant-design-vue', () => ({ message: { error: vi.fn(), success: vi.fn(), warning: vi.fn() } }))
vi.mock('../api/rbac', () => ({ fetchUsers: vi.fn(async () => ({ records: [], total: 0, page: 1, pageSize: 100 })) }))
vi.mock('../api/ai-client', async () => {
  const actual = await vi.importActual<typeof import('../api/ai-client')>('../api/ai-client')
  return { ...actual, isAiSurfaceEnabled: () => true }
})

const stubs = {
  AAlert: true,
  AButton: true,
  AEmpty: true,
  AForm: true,
  AFormItem: true,
  AInput: true,
  AInputNumber: true,
  AModal: true,
  ASelect: true,
  ASelectOption: true,
  ATable: true,
  ATag: true,
  ATextarea: true,
  AiEvidenceMeta: true,
  AiSafetyState: true,
}

function triageResult(overrides: Record<string, unknown> = {}) {
  return {
    repairId: 1,
    category: '水电维修',
    urgency: 'HIGH',
    recommendedTeam: '水电维修组',
    missingInformation: [],
    reasoningSummary: '依据授权维修快照生成建议。',
    assignmentCandidateUserId: 2,
    assignmentCandidateName: '维修员乙',
    slaSuggestion: '2 小时响应',
    evidence: {
      basis: 'deterministic',
      asOf: '2026-07-18T10:00:00Z',
      citations: [{ id: 'repair-snapshot', label: '维修单快照', locator: 'REPAIR_ORDER:1', version: 'v1', access: 'available' }],
      grounded: true,
    },
    state: 'succeeded',
    proposalId: 'proposal-repair-test',
    proposalState: 'pending_approval',
    ...overrides,
  }
}

describe('维修分诊高保真工作台', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setAiClient(new DemoAiClient())
  })

  it('同时呈现工单列表、详情、AI 建议、审批流程和安全状态，不使用 AI 弹窗', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({
      initialized: true,
      user: { id: 7, userName: '管理员', roleCode: 'ADMIN', roleCodes: ['ADMIN'], permissions: ['*'] },
    })
    const operations = useOperationsStore()
    operations.$patch({
      repairTotal: 1,
      repairOrders: [{
        id: 1,
        code: 'WX20260715001',
        reporter: '测试同学',
        location: '2号楼-201宿舍',
        type: '水电维修',
        description: '插座无法通电',
        date: '2026-07-15 10:30',
        status: '待处理',
      }],
    })
    vi.spyOn(operations, 'loadRepairOrders').mockResolvedValue(undefined)
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/repairs', component: { template: '<div />' } },
        { path: '/ai/approvals', component: { template: '<div />' } },
      ],
    })
    await router.push('/repairs')
    await router.isReady()

    const wrapper = shallowMount(RepairManagementView, {
      global: { plugins: [pinia, router], stubs },
    })
    await flushPromises()

    expect(wrapper.find('[aria-label="维修工单工作台"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="报修列表"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="工单详情"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="维修智能分诊"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="维修审批流程"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="安全状态提示"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('WX20260715001')
    expect(wrapper.text()).toContain('2号楼-201宿舍')
    expect(wrapper.find('a-modal[title="维修智能分诊"]').exists()).toBe(false)
  })

  it('发送分诊请求前按稳定顺序筛选维修候选并脱敏手机号、学号和报修人姓名', async () => {
    const client = new DemoAiClient()
    const triage = vi.spyOn(client, 'triageRepair').mockResolvedValue(triageResult() as never)
    setAiClient(client)
    vi.mocked(fetchUsers).mockResolvedValueOnce({
      records: [
        { id: 3, username: 'repair-z', displayName: '维修员乙', enabled: true, roles: [{ id: 3, code: 'REPAIRER', name: '维修员', enabled: true, builtIn: true }] },
        { id: 2, username: 'repair-a', displayName: '维修员甲', enabled: true, roles: [{ id: 3, code: 'REPAIRER', name: '维修员', enabled: true, builtIn: true }] },
      ], total: 2, page: 1, pageSize: 100,
    })
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ initialized: true, user: { id: 7, userName: '管理员', roleCode: 'ADMIN', roleCodes: ['ADMIN'], permissions: ['*'] } })
    const operations = useOperationsStore()
    operations.$patch({ repairTotal: 1, repairOrders: [{
      id: 1, code: 'WX-1', reporter: '张三', location: '2号楼-201', type: '水电维修',
      description: '张三 13812345678 学号 202012345678 插座无法通电', date: '2026-07-18 10:00', status: '待处理',
    }] })
    vi.spyOn(operations, 'loadRepairOrders').mockResolvedValue(undefined)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/repairs', component: { template: '<div />' } }, { path: '/ai/approvals', component: { template: '<div />' } }] })
    await router.push('/repairs'); await router.isReady()
    const wrapper = shallowMount(RepairManagementView, { global: { plugins: [pinia, router], stubs } })
    await flushPromises()

    await wrapper.get('button').trigger('click')
    await flushPromises()
    const generate = wrapper.find('button.ai-button')
    expect(generate.exists()).toBe(true)
    await generate.trigger('click')
    await flushPromises()

    expect(triage).toHaveBeenCalledWith(expect.objectContaining({
      repairId: 1,
      candidates: [
        { userId: 2, displayName: '维修员甲' },
        { userId: 3, displayName: '维修员乙' },
      ],
      descriptionRedacted: expect.not.stringContaining('13812345678'),
    }))
    const input = triage.mock.calls[0]?.[0]
    expect(input?.descriptionRedacted).not.toContain('202012345678')
    expect(input?.descriptionRedacted).not.toContain('张三')
  })

  it.each(['stale', 'expired'] as const)('提案 %s 时禁止进入审批并明确失效状态', async (proposalState) => {
    const client = new DemoAiClient()
    vi.spyOn(client, 'triageRepair').mockResolvedValue(triageResult({ proposalState }) as never)
    setAiClient(client)
    const pinia = createPinia(); setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ initialized: true, user: { id: 7, userName: '管理员', roleCode: 'ADMIN', roleCodes: ['ADMIN'], permissions: ['*'] } })
    const operations = useOperationsStore()
    operations.$patch({ repairTotal: 1, repairOrders: [{ id: 1, code: 'WX-1', reporter: '张三', location: '2号楼-201', type: '水电维修', description: '插座无法通电', date: '2026-07-18 10:00', status: '待处理' }] })
    vi.spyOn(operations, 'loadRepairOrders').mockResolvedValue(undefined)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/repairs', component: { template: '<div />' } }, { path: '/ai/approvals', component: { template: '<div />' } }] })
    await router.push('/repairs'); await router.isReady()
    const wrapper = shallowMount(RepairManagementView, { global: { plugins: [pinia, router], stubs } })
    await flushPromises()
    await wrapper.find('button.ai-button').trigger('click')
    await flushPromises()
    expect(wrapper.get('[data-testid="repair-confidence"]').text()).toBe('规则确定')
    const proposal = wrapper.find('button.ai-button:not(:disabled)')
    expect(wrapper.find('button.ai-button:disabled').exists()).toBe(true)
    expect(wrapper.text()).toContain(proposalState === 'stale' ? '失效' : '过期')
    expect(proposal.exists()).toBe(false)
  })

  it('分诊失败时保留人工流程并在页面内显示安全错误', async () => {
    const client = new DemoAiClient()
    vi.spyOn(client, 'triageRepair').mockRejectedValue(new Error('AI 分诊服务暂不可用'))
    setAiClient(client)
    const pinia = createPinia(); setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ initialized: true, user: { id: 7, userName: '管理员', roleCode: 'ADMIN', roleCodes: ['ADMIN'], permissions: ['*'] } })
    const operations = useOperationsStore()
    operations.$patch({ repairTotal: 1, repairOrders: [{ id: 1, code: 'WX-1', reporter: '张三', location: '2号楼-201', type: '水电维修', description: '插座异常', date: '2026-07-18 10:00', status: '待处理' }] })
    vi.spyOn(operations, 'loadRepairOrders').mockResolvedValue(undefined)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/repairs', component: { template: '<div />' } }, { path: '/ai/approvals', component: { template: '<div />' } }] })
    await router.push('/repairs'); await router.isReady()
    const wrapper = shallowMount(RepairManagementView, { global: { plugins: [pinia, router], stubs } })
    await flushPromises()
    await wrapper.find('button.ai-button').trigger('click')
    await flushPromises()

    expect(wrapper.get('.repair-page-alert[role="alert"]').text()).toContain('AI 分诊服务暂不可用')
    expect(wrapper.find('[aria-label="工单详情"]').exists()).toBe(true)
  })

  it('失败终态不继续展示为当前有效分诊结果，且异常原因只出现一次', async () => {
    const client = new DemoAiClient()
    vi.spyOn(client, 'triageRepair').mockResolvedValue(triageResult({ state: 'failed' }) as never)
    setAiClient(client)
    const pinia = createPinia(); setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ initialized: true, user: { id: 7, userName: '管理员', roleCode: 'ADMIN', roleCodes: ['ADMIN'], permissions: ['*'] } })
    const operations = useOperationsStore()
    operations.$patch({ repairTotal: 1, repairOrders: [{ id: 1, code: 'WX-1', reporter: '张三', location: '2号楼-201', type: '水电维修', description: '插座异常', date: '2026-07-18 10:00', status: '待处理' }] })
    vi.spyOn(operations, 'loadRepairOrders').mockResolvedValue(undefined)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/repairs', component: { template: '<div />' } }, { path: '/ai/approvals', component: { template: '<div />' } }] })
    await router.push('/repairs'); await router.isReady()
    const wrapper = shallowMount(RepairManagementView, { global: { plugins: [pinia, router], stubs } })
    await flushPromises()
    await wrapper.find('button.ai-button').trigger('click')
    await flushPromises()

    expect(wrapper.get('.repair-ai-run-invalid').text()).toContain('本次分诊未生成可依赖建议')
    expect(wrapper.find('.repair-ai-metrics').exists()).toBe(false)
    expect(wrapper.findAll('.repair-ai-panel .repair-triage-blocker')).toHaveLength(0)
    expect(wrapper.get('[aria-label="维修审批流程"]').text()).toContain('无有效建议')
    expect(wrapper.find('button.ai-button:disabled').exists()).toBe(true)
  })

  it('移动端筛选工具栏收敛为单列且子控件允许压缩', () => {
    const mobileStyles = repairViewSource.slice(repairViewSource.lastIndexOf('@media (max-width: 768px)'))

    expect(mobileStyles).toMatch(/\.repair-toolbar\s*\{[^}]*width:\s*100%;[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.repair-toolbar \.operations-filters\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.repair-toolbar \.operations-filters > \*\s*\{[^}]*width:\s*100%;[^}]*min-width:\s*0;[^}]*min-height:\s*44px;/s)
    expect(mobileStyles).toMatch(/\.repair-toolbar \.operations-filters :deep\(\.ant-input-affix-wrapper\),\s*\.repair-toolbar \.operations-filters :deep\(\.ant-select-selector\)\s*\{[^}]*min-height:\s*44px;[^}]*align-items:\s*center;/s)
  })

  it('移动端根网格、工单卡和 AI 空态不会用内在宽度撑破视口', () => {
    const mobileStyles = repairViewSource.slice(repairViewSource.lastIndexOf('@media (max-width: 768px)'))

    expect(mobileStyles).toMatch(/\.repair-page\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.repair-workspace-grid,\s*\.repair-detail-stack\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.repair-mobile-list button\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\) auto;[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.repair-mobile-list button strong\s*\{[^}]*grid-column:\s*1\s*\/\s*-1;[^}]*min-width:\s*0;[^}]*overflow-wrap:\s*anywhere;/s)
    expect(mobileStyles).toMatch(/\.repair-ai-empty\s*\{[^}]*flex-direction:\s*column;[^}]*min-width:\s*0;/s)
  })

  it('待处理标签和移动工单次要文字使用可访问语义前景色', () => {
    const mobileStyles = repairViewSource.slice(repairViewSource.lastIndexOf('@media (max-width: 768px)'))

    expect(repairViewSource).toMatch(/\.repair-page\s+:deep\(\.ant-tag-orange\)\s*\{[^}]*color:\s*var\(--warning-strong\)\s*!important;/s)
    expect(mobileStyles).toMatch(/\.repair-mobile-list button > span:not\(\.ant-tag\)\s*\{[^}]*color:\s*var\(--text-muted\);/s)
  })

  it('桌面工作区约束长工单内容，列表列宽不触发横向滚动', () => {
    expect(repairViewSource).toMatch(/\.repair-workspace-grid\s*\{[^}]*min-width:\s*0;/s)
    expect(repairViewSource).toMatch(/\.repair-workspace-grid\s*>\s*\*\s*\{[^}]*min-width:\s*0;/s)
    expect(repairViewSource).toMatch(/\.repair-detail-panel\s*\{[^}]*min-width:\s*0;/s)
    expect(repairViewSource).toMatch(/dataIndex:\s*'code',[^\n]*width:\s*132/s)
    expect(repairViewSource).toMatch(/dataIndex:\s*'date',[^\n]*width:\s*86/s)
    expect(repairViewSource).toMatch(/key:\s*'actions',[^\n]*width:\s*76,[^\n]*className:\s*'repair-action-column'/s)
  })

  it('桌面详情栈保留防御性滚动且为证据区预留足够宽度，关键区域恢复原型纵向重量', () => {
    const desktopStyles = repairViewSource.slice(
      repairViewSource.indexOf('@media (min-width: 1441px)'),
      repairViewSource.indexOf('@media (max-width: 768px)'),
    )

    expect(desktopStyles).toMatch(/\.repair-detail-stack\s*\{[^}]*overflow-y:\s*auto;[^}]*scrollbar-gutter:\s*stable;/s)
    expect(desktopStyles).toMatch(/\.repair-ai-meta-row \.repair-missing-information\s*\{[^}]*flex:\s*1\s+1\s+35%;/s)
    expect(desktopStyles).toMatch(/\.repair-ai-meta-row :deep\(\.ai-evidence\)\s*\{[^}]*flex:\s*1\s+1\s+65%;/s)
    expect(desktopStyles).toMatch(/@media \(min-width:\s*1441px\) and \(max-height:\s*1050px\)/)
    expect(desktopStyles).toMatch(/\.repair-detail-panel\s*\{[^}]*min-height:\s*230px;[^}]*padding-block:\s*8px;/s)
    expect(desktopStyles).toMatch(/\.repair-flow-panel\s*\{[^}]*padding-block:\s*3px;/s)
    expect(desktopStyles).toMatch(/\.repair-flow article\s*\{[^}]*min-height:\s*96px;[^}]*padding:\s*5px\s+8px;/s)
    expect(desktopStyles).toMatch(/\.repair-flow article button\s*\{[^}]*margin-top:\s*2px;[^}]*padding-inline:\s*8px;[^}]*white-space:\s*nowrap;/s)
    expect(repairViewSource).toMatch(/\.repair-attachment-state\s*\{[^}]*min-height:\s*56px;/s)
    expect(repairViewSource).toMatch(/\.repair-ai-metrics > div\s*\{[^}]*min-height:\s*64px;/s)
    expect(repairViewSource).toMatch(/\.repair-safety-grid article\s*\{[^}]*min-height:\s*88px;/s)
    expect(desktopStyles).not.toMatch(/\.repair-attachment-state\s*\{[^}]*min-height:\s*44px;/s)
    expect(desktopStyles).not.toMatch(/\.repair-safety-grid article\s*\{[^}]*min-height:\s*72px;/s)
  })

  it('维修跳转到分诊区域时尊重 prefers-reduced-motion', () => {
    expect(repairViewSource).toMatch(/matchMedia\('\(prefers-reduced-motion: reduce\)'\)\.matches\s*\?\s*'auto'\s*:\s*'smooth'/)
    expect(repairViewSource).toMatch(/scrollIntoView\(\{\s*behavior:\s*scrollBehavior,\s*block:\s*'center'\s*\}\)/)
  })

  it('原型桌面视口压缩详情与证据节奏，同时保留 44px 引用入口', () => {
    const desktopStyles = repairViewSource.slice(
      repairViewSource.indexOf('@media (min-width: 1441px)'),
      repairViewSource.indexOf('@media (max-width: 768px)'),
    )

    expect(repairViewSource).toContain('class="repair-detail-footer"')
    expect(repairViewSource).toMatch(/\.repair-detail-footer\s*\{[^}]*display:\s*flex;[^}]*align-items:\s*stretch;/s)
    expect(desktopStyles).toMatch(/\.repair-detail-grid \.repair-detail-wide\s*\{[^}]*grid-column:\s*auto;/s)
    expect(repairViewSource).toMatch(/\.repair-ai-panel :deep\(\.ai-evidence\)\s*\{[^}]*min-height:\s*44px;[^}]*display:\s*flex;[^}]*align-items:\s*center;/s)
    expect(repairViewSource).toMatch(/\.repair-ai-panel :deep\(\.ai-citations summary\)\s*\{[^}]*min-height:\s*44px;/s)
    expect(repairViewSource).toMatch(/\.repair-ai-panel :deep\(\.ai-citation-link\)\s*\{[^}]*min-height:\s*44px;/s)
  })

  it('桌面详情完整展示关键字段，并明确附件字段尚未纳入业务合同', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ initialized: true, user: { id: 7, userName: '管理员', roleCode: 'ADMIN', roleCodes: ['ADMIN'], permissions: ['*'] } })
    const operations = useOperationsStore()
    operations.$patch({ repairTotal: 1, repairOrders: [{
      id: 1,
      code: 'WX-DETAIL-1',
      reporter: '夜班宿管',
      location: '宿舍楼 B-2-201 室',
      type: '水电维修',
      description: '插座冒烟并伴随火花，已切断相关电源；夜间用电负载较高，请优先检查线路。',
      date: '2026-07-23 21:30',
      status: '待处理',
    }] })
    vi.spyOn(operations, 'loadRepairOrders').mockResolvedValue(undefined)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/repairs', component: { template: '<div />' } }, { path: '/ai/approvals', component: { template: '<div />' } }] })
    await router.push('/repairs'); await router.isReady()

    const wrapper = shallowMount(RepairManagementView, { global: { plugins: [pinia, router], stubs } })
    await flushPromises()

    expect(wrapper.get('.repair-detail-grid').text()).toContain('宿舍楼 B-2-201 室')
    expect(wrapper.get('.repair-description').text()).toContain('夜间用电负载较高，请优先检查线路')
    const attachmentState = wrapper.get('[data-testid="repair-attachment-empty"]')
    expect(attachmentState.attributes('role')).toBe('status')
    expect(attachmentState.text()).toContain('当前业务合同未提供附件数据')
    expect(attachmentState.attributes('aria-label')).toContain('附件数据未提供')
    expect(wrapper.find('[data-testid="repair-attachment-upload"]').exists()).toBe(false)
    const recordEntry = wrapper.get('.repair-attachment-entry')
    expect(recordEntry.text()).toContain('补充工单记录')
    expect(recordEntry.attributes('aria-describedby')).toBe('repair-attachment-help')
    expect(wrapper.get('#repair-attachment-help').text()).toContain('没有附件上传接口')
    expect(repairViewSource).toMatch(/\.repair-detail-grid dd\s*\{[^}]*white-space:\s*normal;[^}]*overflow-wrap:\s*anywhere;/s)
    expect(repairViewSource).toMatch(/\.repair-description\s*\{[^}]*white-space:\s*normal;[^}]*overflow-wrap:\s*anywhere;/s)
  })

  it('仅在正式视觉证据模式展示两张非业务附件缩略图，并保留生产空态合同', () => {
    expect(repairViewSource).toContain("import { visualEvidenceMode } from '../utils/visual-evidence-mode'")
    expect(repairViewSource).toContain('const visualEvidenceAttachments = ref<VisualEvidenceAttachment[]>([])')
    expect(repairViewSource).toContain('visualEvidenceAttachments.value = [')
    expect(repairViewSource).toContain('void loadVisualEvidenceAttachments()')
    expect(repairViewSource).not.toContain('import.meta.env.VITE_VISUAL_EVIDENCE_ENABLED')
    expect(repairViewSource).toContain('visualEvidenceAttachments.length')
    expect(repairViewSource).toContain('data-testid="repair-attachment-thumbnail"')
    expect(repairViewSource).toContain('视觉证据附件样例，不代表生产工单记录')
    expect(repairViewSource).toContain("label: '插座近照'")
    expect(repairViewSource).toContain("label: '配电箱状态'")
    expect(repairViewSource).not.toContain("label: '视觉样例")
    expect(repairViewSource).toMatch(/v-else[\s\S]*data-testid="repair-attachment-empty"/s)
    expect(repairViewSource).toMatch(/\.repair-attachment-preview-grid\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\);/s)
    expect(repairViewSource).toMatch(/\.repair-attachment-thumbnail img\s*\{[^}]*object-fit:\s*cover;/s)
  })

  it('以维修智能分诊作为页面身份，并把列表选择与业务动作分层', () => {
    expect(routerSource).toMatch(/path:\s*'repairs'[\s\S]*title:\s*'维修智能分诊'/)
    expect(repairViewSource).toContain("column.key === 'selection'")
    expect(repairViewSource).toContain('class="repair-row-selector"')
    expect(repairViewSource).toContain('class="repair-detail-actions"')
    expect(repairViewSource).toMatch(/\.repair-row-selector\s*\{[^}]*min-width:\s*44px;[^}]*min-height:\s*44px;/s)
    expect(repairViewSource).toMatch(/\.repair-detail-actions[\s\S]*:deep\(\.ant-btn\)\s*\{[^}]*min-height:\s*44px;/s)
    expect(repairViewSource).toMatch(/\.repair-ai-panel \.ai-button,[\s\S]*\.repair-flow \.ai-button\s*\{[^}]*min-height:\s*44px;/s)
  })

  it('长工单号保留可辨识后缀，桌面表格与附件空态满足可读性合同', () => {
    expect(repairViewSource).toContain('compactRepairCode(record.code)')
    expect(repairViewSource).toMatch(/function compactRepairCode\([\s\S]*slice\(0,\s*6\)[\s\S]*slice\(-6\)/)
    expect(repairViewSource).toContain("column.key === 'date'")
    expect(repairViewSource).toContain('compactRepairDate(record.date)')
    expect(repairViewSource).toMatch(/function compactRepairDate\([\s\S]*\$\{match\[1\]\}-\$\{match\[2\]\} \$\{match\[3\]\}:\$\{match\[4\]\}/)
    expect(repairViewSource).toMatch(/\.repair-code\s*\{[^}]*overflow:\s*hidden;[^}]*text-overflow:\s*ellipsis;/s)
    expect(repairViewSource).toMatch(/\.repair-attachment-state small\s*\{[^}]*font-size:\s*12px;/s)
    expect(repairViewSource).toMatch(/\.repair-attachment-entry\s*\{[^}]*min-width:\s*112px;[^}]*min-height:\s*44px;[^}]*white-space:\s*nowrap;/s)
    expect(repairViewSource).toMatch(/\.repair-desktop-table \.ant-table-tbody > tr > td\)\s*\{[^}]*height:\s*50px;/s)
  })

  it('维修工作区在原型视口接近均分，并在 1366 视口改为单列避免裁切', () => {
    expect(repairViewSource).toMatch(/\.repair-workspace-grid\s*\{[^}]*grid-template-columns:\s*minmax\(560px,\s*1fr\) minmax\(520px,\s*1fr\);/s)
    expect(repairViewSource).toMatch(/@media \(max-width:\s*1440px\)[\s\S]*\.repair-workspace-grid\s*\{[^}]*grid-template-columns:\s*1fr;/s)
  })

  it('分诊紧急度映射为中文语义而不是直接暴露服务端枚举', async () => {
    const client = new DemoAiClient()
    vi.spyOn(client, 'triageRepair').mockResolvedValue(triageResult({ urgency: 'HIGH' }) as never)
    setAiClient(client)
    const pinia = createPinia(); setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ initialized: true, user: { id: 7, userName: '管理员', roleCode: 'ADMIN', roleCodes: ['ADMIN'], permissions: ['*'] } })
    const operations = useOperationsStore()
    operations.$patch({ repairTotal: 1, repairOrders: [{ id: 1, code: 'WX-1', reporter: '值班宿管', location: '2号楼-201', type: '水电维修', description: '插座异常', date: '2026-07-18 10:00', status: '待处理' }] })
    vi.spyOn(operations, 'loadRepairOrders').mockResolvedValue(undefined)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/repairs', component: { template: '<div />' } }, { path: '/ai/approvals', component: { template: '<div />' } }] })
    await router.push('/repairs'); await router.isReady()
    const wrapper = shallowMount(RepairManagementView, { global: { plugins: [pinia, router], stubs } })
    await flushPromises()
    await wrapper.find('button.ai-button').trigger('click')
    await flushPromises()

    const urgency = wrapper.get('[data-testid="repair-urgency"]')
    expect(urgency.text()).toBe('高')
    expect(urgency.text()).not.toContain('HIGH')
  })

  it('三个业务 Modal 关闭后恢复到各自真实触发器', () => {
    expect(repairViewSource).toContain(':after-close="() => restoreModalFocus(\'create\')"')
    expect(repairViewSource).toContain(':after-close="() => restoreModalFocus(\'handle\')"')
    expect(repairViewSource).toContain(':after-close="() => restoreModalFocus(\'assign\')"')
    expect(repairViewSource).toMatch(/function rememberModalTrigger\([\s\S]*event\?\.currentTarget[\s\S]*document\.activeElement/)
    expect(repairViewSource).toMatch(/if \(target\?\.isConnected && !target\.hasAttribute\('disabled'\)\) target\.focus\(\)/)
  })
})
