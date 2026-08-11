import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setAiClient } from '../api/ai-client'
import { useAiApprovalStore } from '../stores/aiApproval'
import { useAuthStore } from '../stores/auth'
import type { AiAuditRun, AiProposalPreview } from '../types/ai'
import AiApprovalView from './AiApprovalView.vue'
import approvalViewSource from './AiApprovalView.vue?raw'

vi.mock('ant-design-vue', () => ({
  message: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}))

vi.mock('../api/ai-client', async () => {
  const actual = await vi.importActual<typeof import('../api/ai-client')>('../api/ai-client')
  return {
    ...actual,
    getAiClientMode: () => 'http',
    isAiSurfaceEnabled: () => true,
  }
})

const globalStubs = {
  AInputPassword: true,
  ATextarea: true,
  ASelect: true,
  ASegmented: true,
  AModal: true,
  AiSafetyState: true,
  AiEvidenceMeta: true,
}

type ProposalWithRun = AiProposalPreview & { runId?: string }

function proposal(id: string, runId: string, title = id): ProposalWithRun {
  return {
    id,
    actionType: 'REPAIR_ASSIGN',
    title,
    target: `维修单 ${id}`,
    currentValue: '未指派',
    proposedValue: '维修员 A',
    impact: '仅变更负责人',
    requiredPermission: 'ai:approval:review + ADMIN + repair:write',
    payloadHash: 'a'.repeat(64),
    businessSnapshotHash: 'b'.repeat(64),
    version: 2,
    expiresAt: '2099-07-21T09:00:00Z',
    riskLevel: 'high',
    evidence: {
      basis: 'deterministic',
      asOf: '2026-07-20T09:00:00Z',
      citations: [{ id: `${id}-citation`, label: '规则命中', locator: 'RUN:test', version: 'v1', access: 'available' }],
      grounded: true,
    },
    state: 'pending_approval',
    auditAvailable: true,
    executionState: 'pending',
    executionId: `execution-${id}`,
    runId,
  }
}

function auditRun(id: string, modelAlias: string): AiAuditRun {
  return {
    id,
    capability: 'REPAIR',
    state: 'succeeded',
    modelAlias,
    promptVersion: `${modelAlias}-prompt`,
    citationCount: 2,
    durationMs: 1800,
    inputTokens: 200,
    outputTokens: 80,
    estimatedCost: 0.0026,
    currency: 'CNY',
    chainHash: `chain-${id}`,
    occurredAt: '2026-07-20T09:00:00Z',
    steps: [],
  }
}

function authorize(permissions: string[]) {
  const auth = useAuthStore()
  auth.$patch({
    initialized: true,
    user: {
      id: 7,
      userName: 'Stage4Tester',
      roleCode: 'ADMIN',
      roleCodes: ['ADMIN'],
      permissions,
    },
  })
}

describe('AiApproval stage4 contract', () => {
  beforeEach(() => {
    const pinia = createPinia()
    setActivePinia(pinia)
    setAiClient({})
    window.history.replaceState({}, '', '/ai/approvals')
  })

  it('从 Dashboard 深链进入时按 proposal 公共 ID 重新读取服务端详情', async () => {
    const originalScrollIntoView = HTMLElement.prototype.scrollIntoView
    const scrollIntoView = vi.fn()
    HTMLElement.prototype.scrollIntoView = scrollIntoView
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review', 'repair:write'])
    window.history.replaceState({}, '', '/ai/approvals?proposal=proposal-deep-link&mode=approve')
    const store = useAiApprovalStore()
    const loadProposals = vi.spyOn(store, 'loadProposals').mockImplementation(async () => {
      store.proposals = [proposal('proposal-list-default', 'run-list-default')]
      store.selectedId = 'proposal-list-default'
    })
    const selectProposal = vi.spyOn(store, 'selectProposal').mockImplementation(async (id: string) => {
      store.proposals.unshift(proposal(id, 'run-deep-link', '深链方案'))
      store.selectedId = id
    })
    vi.spyOn(store, 'loadAudits').mockResolvedValue(undefined)

    const host = document.createElement('div')
    document.body.appendChild(host)
    const wrapper = shallowMount(AiApprovalView, {
      attachTo: host,
      global: { plugins: [pinia], stubs: globalStubs },
    })
    try {
      await flushPromises()

      expect(loadProposals).toHaveBeenCalledWith({}, { reconcileSelection: false, syncAudit: false })
      expect(selectProposal).toHaveBeenCalledWith('proposal-deep-link', { syncAudit: false })
      expect(loadProposals.mock.invocationCallOrder[0]).toBeLessThan(selectProposal.mock.invocationCallOrder[0])
      expect(store.selectedId).toBe('proposal-deep-link')
      expect(wrapper.get('[data-testid="approval-preview-panel"]').text()).toContain('深链方案')
      const attestation = wrapper.get<HTMLInputElement>('.approval-attestation input')
      expect(document.activeElement).toBe(attestation.element)
      expect(attestation.element.checked).toBe(false)
      expect(scrollIntoView).toHaveBeenCalledWith({ behavior: 'auto', block: 'center', inline: 'nearest' })
    } finally {
      wrapper.unmount()
      host.remove()
      HTMLElement.prototype.scrollIntoView = originalScrollIntoView
    }
  })

  it('缺少 ai:audit:read 时不加载或回显同会话遗留的审计缓存', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review', 'repair:write'])
    const store = useAiApprovalStore()
    store.proposals = [proposal('proposal-no-audit-acl', 'run-private-audit', '无审计权限方案')]
    store.selectedId = 'proposal-no-audit-acl'
    store.audits = [auditRun('run-private-audit', 'private-model-alias')]
    store.selectedAuditId = 'run-private-audit'
    const loadProposals = vi.spyOn(store, 'loadProposals').mockResolvedValue(undefined)
    const loadAudits = vi.spyOn(store, 'loadAudits').mockResolvedValue(undefined)

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    expect(loadProposals).toHaveBeenCalledWith({}, { reconcileSelection: false, syncAudit: false })
    expect(loadAudits).not.toHaveBeenCalled()
    expect(wrapper.find('[data-testid="approval-runtime-rail"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('private-model-alias')
    expect(store.audits).toEqual([])
    expect(store.selectedAuditId).toBe('')
  })

  it('同会话撤回 ai:audit:read 后立即隐藏并清除已加载的审计缓存', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review', 'ai:audit:read', 'repair:write'])
    const auth = useAuthStore()
    const store = useAiApprovalStore()
    const selectedRun = auditRun('run-revoked-audit', 'revoked-model-alias')
    store.proposals = [proposal('proposal-revoked-audit', selectedRun.id, '撤权方案')]
    store.selectedId = 'proposal-revoked-audit'
    store.audits = [selectedRun]
    store.selectedAuditId = selectedRun.id
    store.auditDetail = {
      run: selectedRun,
      steps: [],
      retrievals: [],
      tools: [],
      citations: [],
      proposals: [],
      approvals: [],
      executions: [],
      usage: [],
      hashChain: [],
    }
    vi.spyOn(store, 'loadProposals').mockResolvedValue(undefined)
    const loadAudits = vi.spyOn(store, 'loadAudits').mockResolvedValue(undefined)

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    expect(loadAudits).toHaveBeenCalledOnce()
    expect(wrapper.get('[data-testid="approval-runtime-rail"]').text()).toContain('revoked-model-alias')

    auth.$patch({
      user: auth.user ? { ...auth.user, permissions: ['ai:approval:review', 'repair:write'] } : null,
    })
    await flushPromises()

    expect(wrapper.find('[data-testid="approval-runtime-rail"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('revoked-model-alias')
    expect(store.audits).toEqual([])
    expect(store.selectedAuditId).toBe('')
    expect(store.auditDetail).toBeNull()
  })

  it('右侧运行审计绑定当前提案的 run，而不是列表第一条', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review', 'ai:audit:read'])
    const store = useAiApprovalStore()
    store.proposals = [
      proposal('proposal-a', 'run-a', '提案 A'),
      proposal('proposal-b', 'run-b', '提案 B'),
    ]
    store.selectedId = 'proposal-b'
    store.audits = [
      auditRun('run-a', 'model-alpha'),
      auditRun('run-b', 'model-beta'),
    ]
    store.selectedAuditId = 'run-a'

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('model-beta')
    expect(wrapper.text()).not.toContain('model-alpha')
  })

  it('提案缺少 run 绑定时不借用审计列表的任意运行', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review', 'ai:audit:read'])
    const store = useAiApprovalStore()
    store.proposals = [{ ...proposal('proposal-unbound', 'run-unused'), runId: undefined }]
    store.selectedId = 'proposal-unbound'
    store.audits = [auditRun('run-unrelated', 'model-must-not-leak')]
    store.selectedAuditId = 'run-unrelated'

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    expect(wrapper.text()).toContain('待生成')
    expect(wrapper.text()).not.toContain('model-must-not-leak')
  })

  it('三步审批流随待审批、拒绝、过期和成功状态变化，不静态停留在当前步骤', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review', 'ai:audit:read'])
    const store = useAiApprovalStore()
    store.proposals = [proposal('proposal-flow-state', 'run-flow-state', '审批流状态合同')]
    store.selectedId = 'proposal-flow-state'

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    const scenarios = [
      { state: 'pending_approval', cssClass: 'current', label: '当前步骤' },
      { state: 'rejected', cssClass: 'failure', label: '已拒绝' },
      { state: 'expired', cssClass: 'warning', label: '已过期' },
      { state: 'succeeded', cssClass: 'done', label: '已通过' },
    ] as const

    for (const scenario of scenarios) {
      store.proposals[0]!.state = scenario.state
      await nextTick()
      const approvalStep = wrapper.get('[data-flow-stage="approval"]')
      expect(approvalStep.classes()).toContain(scenario.cssClass)
      expect(approvalStep.text()).toContain(scenario.label)
      if (scenario.cssClass !== 'done') expect(approvalStep.classes()).not.toContain('done')
    }
  })

  it('审计详情缺少工具事实时不得从提案类型推导工具调用成功', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review', 'ai:audit:read'])
    const store = useAiApprovalStore()
    const selected = proposal('proposal-no-tool-audit', 'run-no-tool-audit', '工具审计缺失合同')
    const run = auditRun('run-no-tool-audit', 'model-audited')
    store.proposals = [selected]
    store.selectedId = selected.id
    store.audits = [run]
    store.selectedAuditId = run.id
    store.auditDetail = {
      run,
      steps: [],
      retrievals: [],
      tools: [],
      citations: [],
      proposals: [],
      approvals: [],
      executions: [],
      usage: [],
      hashChain: [],
    }

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    const toolStep = wrapper.findAll('.runtime-timeline li')
      .find((step) => step.text().includes('工具调用'))
    expect(toolStep).toBeDefined()
    expect(toolStep!.classes()).not.toContain('done')
    expect(toolStep!.text()).toContain('审计事实缺失')
    expect(toolStep!.text()).not.toContain('SUCCESS')
  })

  it('缺少工具审计事实时保持待记录，不伪装为 SUCCESS', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review', 'ai:audit:read'])
    const store = useAiApprovalStore()
    store.proposals = [proposal('proposal-no-tool', 'run-no-tool', '无工具事实提案')]
    store.selectedId = 'proposal-no-tool'
    store.audits = [auditRun('run-no-tool', 'model-without-tool-audit')]

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    const toolStep = wrapper.findAll('.runtime-timeline li')
      .find((item) => item.text().includes('工具调用（预览）'))
    expect(toolStep).toBeDefined()
    expect(toolStep!.classes()).toContain('pending')
    expect(toolStep!.text()).toContain('审计事实缺失')
    expect(toolStep!.text()).not.toContain('SUCCESS')
  })

  it('存在下一页时，点击分页要真实请求下一页方案', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review'])
    const store = useAiApprovalStore()
    store.proposals = [proposal('proposal-a', 'run-a', '提案 A')]
    store.selectedId = 'proposal-a'
    store.proposalTotal = 41
    store.proposalPage = 1
    store.proposalPageSize = 20
    store.audits = [auditRun('run-a', 'model-alpha')]
    store.selectedAuditId = 'run-a'

    const loadProposals = vi.spyOn(store, 'loadProposals').mockResolvedValue(undefined)
    const loadAudits = vi.spyOn(store, 'loadAudits').mockResolvedValue(undefined)

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()
    loadProposals.mockClear()
    loadAudits.mockClear()

    const nextButton = wrapper.get('[aria-label="下一页"]')
    expect(nextButton.attributes('disabled')).toBeUndefined()

    await nextButton.trigger('click')

    expect(loadProposals).toHaveBeenCalledWith(
      { page: 2, pageSize: 20 },
      { reconcileSelection: true, syncAudit: false },
    )
  })

  it('两类 hash 使用原生键盘披露入口展示完整值', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review'])
    const store = useAiApprovalStore()
    store.proposals = [proposal('proposal-hash', 'run-hash', 'Hash 几何合同')]
    store.selectedId = 'proposal-hash'

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    const hashDisclosures = wrapper.findAll('.proposal-facts details.hash-disclosure')
    expect(hashDisclosures).toHaveLength(2)
    for (const disclosure of hashDisclosures) {
      expect(disclosure.get('summary').text()).toContain('查看完整值')
      expect(disclosure.get('code.hash-full-value').text()).toHaveLength(64)
    }

    expect(approvalViewSource).toMatch(/\.hash-disclosure\s*summary\s*\{[^}]*cursor:\s*pointer;/s)
    expect(approvalViewSource).not.toMatch(/\.proposal-facts \.fact-hash\s*\{[^}]*text-overflow:\s*ellipsis;/s)
  })

  it('必要元数据不低于 12px，主要扫描标签不低于 14px', () => {
    const styles = approvalViewSource.slice(approvalViewSource.indexOf('<style scoped>'))
    const undersizedDeclarations = [...styles.matchAll(/font-size:\s*(\d+(?:\.\d+)?)px/g)]
      .map((match) => Number(match[1]))
      .filter((size) => size < 12)

    expect(undersizedDeclarations).toEqual([])
    for (const selector of [
      '.proposal-title-row strong',
      '.tool-preview h3',
      '.approval-flow strong',
      '.runtime-label strong',
      '.cost-card h3',
    ]) {
      const escapedSelector = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      expect(styles, `${selector} 必须使用 14px 主扫描字号`).toMatch(
        new RegExp(`${escapedSelector}\\s*\\{[^}]*font-size:\\s*(?:14px|var\\(--font-size-body\\))`, 's'),
      )
    }
  })

  it('移动与 200% 重排保留三步文字，主要操作全视口保持 44px', () => {
    const styles = approvalViewSource.slice(approvalViewSource.indexOf('<style scoped>'))
    const mobileStyles = styles.slice(styles.lastIndexOf('@media (max-width: 768px)'))

    expect(mobileStyles).not.toMatch(/\.approval-flow p\s*\{[^}]*display:\s*none;/s)
    expect(mobileStyles).toMatch(/\.approval-flow p\s*\{[^}]*display:\s*grid;/s)
    expect(styles).toMatch(/\.approval-actions button\s*\{[^}]*min-height:\s*(?:44px|var\(--touch-target\));/s)
    expect(styles).toMatch(/\.ai-reconfirm button\s*\{[^}]*min-height:\s*(?:44px|var\(--touch-target\));/s)
  })

  it('1536x1024 正式视口压缩非交互留白，同时保留 44px 核心审批操作', () => {
    const styles = approvalViewSource.slice(approvalViewSource.indexOf('<style scoped>'))
    const compactDesktop = styles.slice(
      styles.indexOf('@media (min-width: 1321px) and (min-height: 900px)'),
      styles.indexOf('@media (max-width: 1320px)'),
    )

    expect(compactDesktop).toMatch(/\.value-diff > \*\s*\{[^}]*padding:\s*5px 8px;/s)
    expect(compactDesktop).toMatch(/\.proposal-facts div\s*\{[^}]*min-height:\s*31px;/s)
    expect(compactDesktop).toMatch(/\.proposal-facts dt,\s*\.proposal-facts dd\s*\{[^}]*padding:\s*5px 8px;/s)
    expect(compactDesktop).toMatch(/\.approval-attestation\s*\{[^}]*padding:\s*6px 8px;/s)
    expect(compactDesktop).toMatch(/\.confidence-note\s*\{[^}]*margin-top:\s*4px;[^}]*padding:\s*5px 8px;/s)
    expect(styles).toMatch(/\.approval-actions button\s*\{[^}]*min-height:\s*var\(--touch-target\);/s)
  })

  it('diff、阻断卡和右栏审计使用可扫描的原型层级', () => {
    const styles = approvalViewSource.slice(approvalViewSource.indexOf('<style scoped>'))

    expect(styles).toMatch(/\.tool-preview\s*\{[^}]*background:\s*var\(--surface-muted\);/s)
    expect(styles).toMatch(/\.value-diff \.current-value\s*\{[^}]*box-shadow:\s*inset 3px 0 0 var\(--danger\);/s)
    expect(styles).toMatch(/\.value-diff \.proposed-value\s*\{[^}]*box-shadow:\s*inset 3px 0 0 var\(--success\);/s)
    expect(styles).toMatch(/\.guard-grid span\s*\{[^}]*min-height:\s*(?:44px|var\(--touch-target\));[^}]*font-size:\s*var\(--font-size-caption\);/s)
    expect(styles).toMatch(/\.runtime-timeline li\.current\s*\{[^}]*background:\s*var\(--surface-selected\);/s)
  })

  it('批准执行必须由当前提案的显式人工确认解锁', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review', 'repair:write'])
    const store = useAiApprovalStore()
    store.proposals = [proposal('proposal-attestation', 'run-attestation', '人工确认合同')]
    store.selectedId = 'proposal-attestation'

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    const approveButton = wrapper.findAll('button').find((button) => button.text() === '批准执行')
    expect(approveButton).toBeDefined()
    expect(wrapper.get<HTMLInputElement>('.approval-attestation input').element.checked).toBe(false)
    const attestation = wrapper.get('.approval-attestation').text()
    expect(attestation).toContain('我已核对变更及影响')
    expect(attestation).toContain('提交审批决定，通过门槛后才受控执行')
    expect(attestation).toContain('只有 SUCCEEDED 代表成功')
    expect(approveButton!.attributes('disabled')).toBeDefined()

    await wrapper.get<HTMLInputElement>('.approval-attestation input').setValue(true)

    expect(approveButton!.attributes('disabled')).toBeUndefined()
  })

  it.each([
    ['缺少业务权限', { requiredPermission: 'ai:approval:review + ADMIN + repair:write' }, ['ai:approval:review']],
    ['到期时间已过', { expiresAt: '2000-01-01T00:00:00Z' }, ['ai:approval:review', 'repair:write']],
    ['审批 hash 不完整', { payloadHash: '' }, ['ai:approval:review', 'repair:write']],
  ] as const)('%s时即使勾选人工确认也不能批准', async (_label, override, permissions) => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize([...permissions])
    const store = useAiApprovalStore()
    store.proposals = [{ ...proposal('proposal-blocked', 'run-blocked', '阻断合同'), ...override }]
    store.selectedId = 'proposal-blocked'

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()
    await wrapper.get<HTMLInputElement>('.approval-attestation input').setValue(true)

    const approveButton = wrapper.findAll('button').find((button) => button.text() === '批准执行')
    expect(approveButton).toBeDefined()
    expect(approveButton!.attributes('disabled')).toBeDefined()
  })

  it('真实 HTTP 模式拒绝提案时不提交演示方案文案', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review'])
    const store = useAiApprovalStore()
    store.proposals = [proposal('proposal-reject', 'run-reject', '拒绝文案合同')]
    store.selectedId = 'proposal-reject'
    const reject = vi.spyOn(store, 'reject').mockResolvedValue(true)

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    const rejectButton = wrapper.findAll('button').find((button) => button.text() === '拒绝')
    expect(rejectButton).toBeDefined()
    await rejectButton!.trigger('click')

    expect(reject).toHaveBeenCalledWith('人工拒绝方案', { syncAudit: false })
    expect(reject.mock.calls.flat().join(' ')).not.toContain('演示方案')
  })

  it('提案 mutation pending 时锁定审批操作并就近展示失败状态', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:approval:review', 'repair:write'])
    const store = useAiApprovalStore()
    store.proposals = [proposal('proposal-pending', 'run-pending', '异步操作合同')]
    store.selectedId = 'proposal-pending'
    ;(store as unknown as { proposalMutation: string | null }).proposalMutation = 'reject'
    ;(store as unknown as { mutationError: string | null }).mutationError = '拒绝接口失败'

    const wrapper = shallowMount(AiApprovalView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()

    for (const label of ['批准执行', '拒绝', '刷新预览']) {
      const button = wrapper.findAll('button').find((candidate) => candidate.text() === label)
      expect(button, `${label} 按钮缺失`).toBeDefined()
      expect(button!.attributes('disabled'), `${label} 未在 mutation pending 时锁定`).toBeDefined()
    }
    expect(wrapper.html()).toContain('拒绝接口失败')
  })
})
