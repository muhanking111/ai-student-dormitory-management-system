import { flushPromises, shallowMount } from '@vue/test-utils'
import { message } from 'ant-design-vue'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setAiClient } from '../api/ai-client'
import { useAiApprovalStore } from '../stores/aiApproval'
import { useAuthStore } from '../stores/auth'
import type { AiAuditRun, AiAuditRunContent, AiAuditRunDetail, AiAuditRunQuery } from '../types/ai'
import AiAuditView from './AiAuditView.vue'
import AiAuditViewSource from './AiAuditView.vue?raw'

vi.mock('ant-design-vue', () => ({
  message: { error: vi.fn(), success: vi.fn(), info: vi.fn(), destroy: vi.fn() },
}))

vi.mock('../api/ai-client', async () => {
  const actual = await vi.importActual<typeof import('../api/ai-client')>('../api/ai-client')
  return { ...actual, getAiClientMode: () => 'http', isAiSurfaceEnabled: () => true }
})

const PasswordInputStub = defineComponent({
  name: 'AInputPassword',
  inheritAttrs: false,
  props: { value: { type: String, default: '' } },
  emits: ['update:value'],
  setup(props, { attrs, emit }) {
    return () => h('input', {
      ...attrs,
      type: 'password',
      value: props.value,
      onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value),
    })
  },
})

const TextareaStub = defineComponent({
  name: 'ATextarea',
  inheritAttrs: false,
  props: { value: { type: String, default: '' } },
  emits: ['update:value'],
  setup(props, { attrs, emit }) {
    return () => h('textarea', {
      ...attrs,
      value: props.value,
      onInput: (event: Event) => emit('update:value', (event.target as HTMLTextAreaElement).value),
    })
  },
})

const ModalStub = defineComponent({
  name: 'AModal',
  props: {
    open: { type: Boolean, default: false },
    okButtonProps: { type: Object, default: () => ({}) },
    confirmLoading: { type: Boolean, default: false },
  },
  emits: ['ok', 'cancel', 'update:open'],
  setup(_props, { slots }) {
    return () => h('div', { class: 'modal-stub' }, slots.default?.())
  },
})

const viewStubs = {
  AInputPassword: PasswordInputStub,
  ATextarea: TextareaStub,
  AModal: ModalStub,
  AiSafetyState: true,
}

function run(id: string): AiAuditRun {
  return {
    id,
    capability: 'REPAIR',
    state: 'succeeded',
    modelAlias: 'repair-safe-model',
    promptVersion: 'repair-triage-v3',
    citationCount: 2,
    durationMs: 1800,
    inputTokens: 900,
    outputTokens: 346,
    estimatedCost: 0.0062,
    currency: 'CNY',
    chainHash: `sha256:${id}:7f8c`,
    occurredAt: '2026-07-20T10:30:12+08:00',
    steps: [],
  }
}

function detail(auditRun: AiAuditRun): AiAuditRunDetail {
  return {
    run: auditRun,
    steps: [
      { id: '1', type: 'model.completed', label: 'model.completed', status: 'SUCCEEDED', occurredAt: auditRun.occurredAt, metadata: { sequence: 1 } },
      { id: '2', type: 'retrieval.completed', label: 'retrieval.completed', status: 'SUCCEEDED', occurredAt: auditRun.occurredAt, metadata: { sequence: 2, returnedCount: 2, requestHash: 'query-hash', promptText: '不得展示的原始提示词' } },
      { id: '3', type: 'tool.call.succeeded', label: 'tool.call.succeeded', status: 'SUCCEEDED', occurredAt: auditRun.occurredAt, metadata: { sequence: 3, toolName: 'repair.propose_assignment.v1', rawArguments: '{"studentName":"敏感值"}' } },
      { id: '4', type: 'proposal.created', label: 'proposal.created', status: 'RECORDED', occurredAt: auditRun.occurredAt, metadata: { sequence: 4, proposalId: 'proposal-17' } },
      { id: '5', type: 'approval.approved', label: 'approval.approved', status: 'SUCCEEDED', occurredAt: auditRun.occurredAt, metadata: { sequence: 5, approvalState: 'APPROVED' } },
      { id: '6', type: 'execution.succeeded', label: 'execution.succeeded', status: 'SUCCEEDED', occurredAt: auditRun.occurredAt, metadata: { sequence: 6, executionId: 'execution-17', resultHash: 'result-hash' } },
      { id: '7', type: 'run.completed', label: 'run.completed', status: 'SUCCEEDED', occurredAt: auditRun.occurredAt, metadata: { sequence: 7 } },
    ],
    retrievals: [{
      id: 'retrieval-1', queryHash: 'query-hash', retrievalPolicyVersion: 'acl-v2', retrievalMode: 'HYBRID',
      indexCode: 'repair-knowledge', indexVersion: '2026-07-20', embeddingModelVersion: 'embed-v2', topK: 5,
      aclPreFilterCount: 8, aclPostFilterCount: 3, returnedCount: 2, latencyMs: 38, state: 'SUCCEEDED',
      occurredAt: auditRun.occurredAt,
    }],
    tools: [{
      id: 'tool-17', sequence: 3, toolName: 'repair.propose_assignment.v1', toolVersion: 'v2.0',
      authorizationDecision: 'ALLOW_SCOPE_REPAIR', state: 'SUCCEEDED', errorCode: null,
      startedAt: auditRun.occurredAt, finishedAt: auditRun.occurredAt,
    }],
    citations: [{
      id: 'citation-17', citationType: 'RULE', documentVersionId: 'document-v17', chunkId: null,
      metricId: null, rank: 1, score: 0.89, contentHash: 'citation-content-hash', createdAt: auditRun.occurredAt,
    }],
    proposals: [{
      id: 'proposal-17', actionType: 'REPAIR_ASSIGN', targetType: 'REPAIR_ORDER', payloadHash: 'payload-hash',
      businessSnapshotHash: 'snapshot-hash', approvalPolicyVersion: 'approval-v2', requiredApprovalCount: 1,
      approvedCount: 1, riskLevel: 'HIGH', state: 'APPROVED', proposerUserId: 7,
      expiresAt: '2026-07-21T10:30:12+08:00', createdAt: auditRun.occurredAt,
    }],
    approvals: [{
      proposalId: 'proposal-17', proposalVersion: 2, decision: 'APPROVED', reviewerUserId: 9,
      payloadHash: 'payload-hash', businessSnapshotHash: 'snapshot-hash', createdAt: auditRun.occurredAt,
    }],
    executions: [{
      id: 'execution-17', proposalId: 'proposal-17', state: 'SUCCEEDED', version: 2,
      handlerName: 'repairAssignmentHandler', executedByUserId: 9, reconfirmedByUserId: null,
      resultResourceType: 'REPAIR_ORDER', errorCode: null, startedAt: auditRun.occurredAt, finishedAt: auditRun.occurredAt,
    }],
    usage: [{
      requestSequence: 1, attempt: 1, requestKind: 'PRIMARY', actorKind: 'USER', actorUserId: 7,
      servicePrincipalCode: null, initiatedByUserId: 7, capability: 'REPAIR', providerCode: 'fake',
      modelName: 'repair-safe-model', inputTokens: 900, outputTokens: 346, costAmount: 0.0062,
      currency: 'CNY', usageSource: 'PROVIDER', occurredAt: auditRun.occurredAt,
    }],
    hashChain: [{
      id: 'hash-17', chainScope: 'RUN', aggregateType: 'AI_RUN', aggregatePublicId: auditRun.id,
      sequence: 7, eventType: 'RUN_SUCCEEDED', actorKind: 'SYSTEM', actorUserId: null,
      servicePrincipalCode: 'ai-runtime', initiatedByUserId: 7, effectiveSubjectUserId: 7,
      payloadHash: 'event-payload-hash', previousEventHash: 'previous-event-hash', eventHash: 'event-hash-17',
      integrityAlgorithm: 'HMAC-SHA256', integrityKeyVersion: 1, canonicalizationVersion: 'JCS-1',
      correlationId: 'correlation-17', occurredAt: auditRun.occurredAt,
    }],
  }
}

function authorize(permissions: string[]) {
  useAuthStore().$patch({
    initialized: true,
    user: { id: 7, userName: 'Auditor', roleCode: 'ADMIN', roleCodes: ['ADMIN'], permissions },
  })
}

function mountView(pinia: ReturnType<typeof createPinia>) {
  return shallowMount(AiAuditView, { global: { plugins: [pinia], stubs: viewStubs } })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((fulfill) => { resolve = fulfill })
  return { promise, resolve }
}

describe('AI audit stage 4 contract', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setAiClient(undefined)
  })

  it('以紧凑审计工作台展示全链路阶段，并只呈现脱敏 metadata 白名单', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:audit:read'])
    const auditRun = run('run-stage4')
    setAiClient({
      listAuditRuns: vi.fn().mockResolvedValue({ records: [auditRun], total: 1, page: 1, pageSize: 20 }),
      getAuditRun: vi.fn().mockResolvedValue(detail(auditRun)),
    })

    const wrapper = mountView(pinia)
    await flushPromises()
    await wrapper.findAll('button').find((button) => button.text() === '技术')!.trigger('click')

    expect(wrapper.find('[data-testid="audit-stage-strip"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('模型处理')
    expect(wrapper.text()).toContain('授权检索')
    expect(wrapper.text()).toContain('工具调用')
    expect(wrapper.text()).toContain('提案生成')
    expect(wrapper.text()).toContain('人工审批')
    expect(wrapper.text()).toContain('受控执行')
    expect(wrapper.text()).toContain('运行结果')
    expect(wrapper.text()).toContain('repair.propose_assignment.v1')
    expect(wrapper.text()).toContain('proposal-17')
    expect(wrapper.text()).toContain('execution-17')
    expect(wrapper.text()).toContain('result-hash')
    expect(wrapper.get('[data-testid="audit-stage-strip"]').text()).toContain('已完成')
    const linkedArtifacts = wrapper.get('[data-testid="audit-linked-artifacts"]')
    expect(linkedArtifacts.text()).toContain('ALLOW_SCOPE_REPAIR')
    expect(linkedArtifacts.text()).toContain('document-v17')
    expect(linkedArtifacts.text()).toContain('repairAssignmentHandler')
    expect(linkedArtifacts.text()).toContain('event-hash-17')
    expect(wrapper.text()).not.toContain('不得展示的原始提示词')
    expect(wrapper.text()).not.toContain('敏感值')
    expect(wrapper.text()).not.toContain('用户 9')
  })

  it('阶段条区分待审批、需关注、跳过、失败与真实业务成功，不把存在记录等同于已完成', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:audit:read'])
    const auditRun = run('run-stage-semantics')
    const pendingDetail = detail(auditRun)
    pendingDetail.steps[4] = { ...pendingDetail.steps[4]!, status: 'PENDING_APPROVAL' }
    pendingDetail.steps[5] = { ...pendingDetail.steps[5]!, status: 'PENDING' }
    pendingDetail.steps[6] = { ...pendingDetail.steps[6]!, status: 'PENDING' }
    setAiClient({
      listAuditRuns: vi.fn().mockResolvedValue({ records: [auditRun], total: 1, page: 1, pageSize: 20 }),
      getAuditRun: vi.fn().mockResolvedValue(pendingDetail),
    })

    const wrapper = mountView(pinia)
    await flushPromises()
    const stage = (label: string) => wrapper.findAll('[data-testid="audit-stage-strip"] > li')
      .find((item) => item.text().includes(label))!

    expect(stage('人工审批').classes()).toContain('current')
    expect(stage('人工审批').classes()).not.toContain('done')
    expect(stage('人工审批').text()).toContain('进行中')
    expect(stage('受控执行').classes()).toContain('pending')
    expect(stage('运行结果').classes()).toContain('pending')
    expect(stage('受控执行').classes()).not.toContain('business-success')
    expect(stage('运行结果').classes()).not.toContain('business-success')

    const store = useAiApprovalStore()
    const exceptionalDetail = detail(auditRun)
    exceptionalDetail.steps[4] = { ...exceptionalDetail.steps[4]!, status: 'EXPIRED' }
    exceptionalDetail.steps[5] = { ...exceptionalDetail.steps[5]!, status: 'SKIPPED' }
    exceptionalDetail.steps[6] = { ...exceptionalDetail.steps[6]!, status: 'FAILED' }
    store.$patch({ auditDetail: exceptionalDetail })
    await nextTick()

    expect(stage('人工审批').classes()).toContain('warning')
    expect(stage('受控执行').classes()).toContain('skipped')
    expect(stage('运行结果').classes()).toContain('failure')

    const succeededDetail = detail(auditRun)
    store.$patch({ auditDetail: succeededDetail })
    await nextTick()

    expect(stage('人工审批').classes()).toContain('done')
    expect(stage('人工审批').classes()).not.toContain('business-success')
    expect(stage('受控执行').classes()).toEqual(expect.arrayContaining(['done', 'business-success']))
    expect(stage('运行结果').classes()).toEqual(expect.arrayContaining(['done', 'business-success']))
  })

  it('默认业务视图按模型、检索、工具、引用、审批、执行与结果组织真实审计证据', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:audit:read'])
    const auditRun = run('run-evidence-workbench')
    setAiClient({
      listAuditRuns: vi.fn().mockResolvedValue({ records: [auditRun], total: 1, page: 1, pageSize: 20 }),
      getAuditRun: vi.fn().mockResolvedValue(detail(auditRun)),
    })

    const wrapper = mountView(pinia)
    await flushPromises()

    const evidenceWorkbench = wrapper.get('[data-testid="audit-evidence-workbench"]')
    expect(evidenceWorkbench.findAll('.audit-evidence-group')).toHaveLength(7)
    expect(evidenceWorkbench.text()).toContain('模型')
    expect(evidenceWorkbench.text()).toContain('检索')
    expect(evidenceWorkbench.text()).toContain('工具')
    expect(evidenceWorkbench.text()).toContain('引用')
    expect(evidenceWorkbench.text()).toContain('人工审批')
    expect(evidenceWorkbench.text()).toContain('受控执行')
    expect(evidenceWorkbench.text()).toContain('运行结果')
    expect(evidenceWorkbench.text()).toContain('repair-safe-model')
    expect(evidenceWorkbench.text()).toContain('HYBRID')
    expect(evidenceWorkbench.text()).toContain('repair.propose_assignment.v1')
    expect(evidenceWorkbench.text()).toContain('document-v17')
    expect(evidenceWorkbench.text()).toContain('APPROVED')
    expect(evidenceWorkbench.text()).toContain('execution-17')
    expect(evidenceWorkbench.text()).toContain('event-hash-17')
    expect(evidenceWorkbench.text()).not.toContain('不得展示的原始提示词')
    expect(evidenceWorkbench.text()).not.toContain('敏感值')
    expect(evidenceWorkbench.text()).not.toContain('用户 9')
  })

  it('稀疏审计详情对缺失制品显示缺失态且不补造运行事件', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:audit:read'])
    const auditRun = run('run-sparse-detail')
    const sparseDetail = detail(auditRun)
    sparseDetail.steps = sparseDetail.steps.slice(0, 4)
    sparseDetail.retrievals = []
    sparseDetail.tools = []
    sparseDetail.citations = []
    sparseDetail.proposals = []
    sparseDetail.approvals = []
    sparseDetail.executions = []
    setAiClient({
      listAuditRuns: vi.fn().mockResolvedValue({ records: [auditRun], total: 1, page: 1, pageSize: 20 }),
      getAuditRun: vi.fn().mockResolvedValue(sparseDetail),
    })

    const wrapper = mountView(pinia)
    await flushPromises()

    const evidenceWorkbench = wrapper.get('[data-testid="audit-evidence-workbench"]')
    expect(evidenceWorkbench.findAll('.audit-evidence-group')).toHaveLength(7)
    for (const key of ['retrieval', 'tool', 'citation', 'approval', 'execution']) {
      expect(evidenceWorkbench.get(`[data-evidence-kind="${key}"]`).text()).toContain('暂无可核验记录')
    }
    expect(wrapper.get('[data-testid="audit-stage-strip"]').text()).toContain('暂无记录')
    expect(wrapper.get('[data-testid="audit-stage-strip"]').text()).toContain('待处理')
    expect(wrapper.get('.audit-timeline').findAll('li:not(.empty-timeline)')).toHaveLength(4)
    expect(wrapper.text()).not.toContain('execution-17')
  })

  it('关键扫描字号、阶段条布局与移动控件尺寸满足 Stage 5 审计页合同', () => {
    expect(AiAuditViewSource).toMatch(/\.run-title strong\s*\{[^}]*font-size:\s*14px;/)
    expect(AiAuditViewSource).toMatch(/\.audit-stage-strip strong\s*\{[^}]*font-size:\s*14px;/)
    expect(AiAuditViewSource).toMatch(/\.audit-stage-strip small\s*\{[^}]*font-size:\s*12px;/)
    expect(AiAuditViewSource).toMatch(/\.timeline-status\s*\{[^}]*font-size:\s*12px;/)
    expect(AiAuditViewSource).toMatch(/\.artifact-group h4\s*\{[^}]*font-size:\s*14px;/)
    expect(AiAuditViewSource).toMatch(/\.audit-stage-strip\s*\{[^}]*grid-template-columns:\s*repeat\(4,\s*minmax\(0,\s*1fr\)\);/)
    expect(AiAuditViewSource).not.toMatch(/\.audit-stage-strip\s*\{[^}]*overflow-x:\s*auto;/)
    expect(AiAuditViewSource).toMatch(/@media \(max-width: 768px\)\s*\{[\s\S]*?\.audit-stage-strip\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\);/)
    expect(AiAuditViewSource).not.toContain('grid-template-columns: repeat(7,92px);')
    expect(AiAuditViewSource).toMatch(/\.governance-tabs a\s*\{[^}]*min-height:\s*44px;/)
    expect(AiAuditViewSource).toMatch(/\.view-toggle button\s*\{[^}]*min-height:\s*44px;/)
    expect(AiAuditViewSource).toMatch(/\.run-pagination button\s*\{[^}]*width:\s*44px;[^}]*height:\s*44px;/)
    expect(AiAuditViewSource).toMatch(/\.refresh-button,[\s\S]*?\.audit-content-actions button\s*\{[^}]*min-height:\s*44px;/)
  })

  it('移动端选择运行后滚动并聚焦详情标题，旧请求完成后不再回填聚焦动作', async () => {
    const originalMatchMedia = window.matchMedia
    const matchMediaMock = vi.fn().mockReturnValue({
      matches: true,
      media: '(max-width: 768px)',
      onchange: null,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })
    Object.defineProperty(window, 'matchMedia', { configurable: true, writable: true, value: matchMediaMock })

    const originalScrollIntoView = HTMLElement.prototype.scrollIntoView
    const originalFocus = HTMLElement.prototype.focus
    const scrollIntoViewSpy = vi.fn()
    const focusSpy = vi.fn()
    HTMLElement.prototype.scrollIntoView = scrollIntoViewSpy
    HTMLElement.prototype.focus = focusSpy

    try {
      const pinia = createPinia()
      setActivePinia(pinia)
      authorize(['ai:audit:read'])
      const firstRun = run('run-a')
      const secondRun = { ...run('run-b'), capability: 'NOTICE' as const }
      const pendingFirst = deferred<AiAuditRunDetail>()
      let firstRequestCount = 0
      setAiClient({
        listAuditRuns: vi.fn().mockResolvedValue({ records: [firstRun, secondRun], total: 2, page: 1, pageSize: 20 }),
        getAuditRun: vi.fn().mockImplementation((id: string) => {
          if (id === firstRun.id) {
            firstRequestCount += 1
            return firstRequestCount === 1 ? Promise.resolve(detail(firstRun)) : pendingFirst.promise
          }
          return Promise.resolve(detail(secondRun))
        }),
      })

      const wrapper = mountView(pinia)
      await flushPromises()
      scrollIntoViewSpy.mockClear()
      focusSpy.mockClear()

      const setupState = (wrapper.vm.$ as unknown as { setupState: Record<string, unknown> }).setupState
      const selectFirst = (setupState.selectAudit as (id: string, options?: { focusDetail?: boolean }) => Promise<void>)(firstRun.id, { focusDetail: true })
      const selectSecond = (setupState.selectAudit as (id: string, options?: { focusDetail?: boolean }) => Promise<void>)(secondRun.id, { focusDetail: true })
      await selectSecond
      pendingFirst.resolve(detail(firstRun))
      await selectFirst
      await flushPromises()

      const store = useAiApprovalStore()
      expect(store.selectedAuditId).toBe(secondRun.id)
      expect(store.auditDetail?.run.id).toBe(secondRun.id)
      expect(wrapper.get('[data-testid="audit-detail-heading"]').attributes('tabindex')).toBe('-1')
      expect(matchMediaMock).toHaveBeenCalledWith('(max-width: 768px)')
      expect(scrollIntoViewSpy).toHaveBeenCalledTimes(1)
      expect(scrollIntoViewSpy).toHaveBeenCalledWith({ block: 'start', inline: 'nearest' })
      expect(focusSpy).toHaveBeenCalledTimes(1)
    } finally {
      Object.defineProperty(window, 'matchMedia', { configurable: true, writable: true, value: originalMatchMedia })
      HTMLElement.prototype.scrollIntoView = originalScrollIntoView
      HTMLElement.prototype.focus = originalFocus
    }
  })

  it('分页复用已应用的真实筛选，并选择新页首条运行', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:audit:read'])
    const first = run('run-page-1')
    const second = run('run-page-2')
    const listAuditRuns = vi.fn(async (input: AiAuditRunQuery = {}) => ({
      records: input.page === 2 ? [second] : [first], total: 21, page: input.page ?? 1, pageSize: input.pageSize ?? 20,
    }))
    setAiClient({
      listAuditRuns,
      getAuditRun: vi.fn(async (id: string) => detail(id === second.id ? second : first)),
    })

    const wrapper = mountView(pinia)
    await flushPromises()
    await wrapper.get<HTMLSelectElement>('[aria-label="按能力筛选"]').setValue('REPAIR')
    await wrapper.get<HTMLSelectElement>('[aria-label="按状态筛选"]').setValue('SUCCEEDED')
    await wrapper.get<HTMLSelectElement>('[aria-label="按 Provider 筛选"]').setValue('fake')
    await wrapper.get('form[aria-label="运行审计筛选"]').trigger('submit')
    await flushPromises()
    listAuditRuns.mockClear()

    await wrapper.get<HTMLButtonElement>('[aria-label="下一页"]').trigger('click')
    await flushPromises()

    expect(listAuditRuns).toHaveBeenCalledWith({
      page: 2, pageSize: 20, capability: 'REPAIR', state: 'SUCCEEDED', provider: 'fake', from: undefined, to: undefined,
    })
    expect(wrapper.text()).toContain(second.id)
    expect(wrapper.get<HTMLButtonElement>('[aria-label="上一页"]').attributes('disabled')).toBeUndefined()
    expect(wrapper.get<HTMLButtonElement>('[aria-label="下一页"]').attributes('disabled')).toBeDefined()
  })

  it('重新加载时保留仍在当前页的运行选择', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:audit:read'])
    const first = run('run-first')
    const current = run('run-current')
    const store = useAiApprovalStore()
    store.selectedAuditId = current.id
    const getAuditRun = vi.fn(async (id: string) => detail(id === current.id ? current : first))
    setAiClient({
      listAuditRuns: vi.fn().mockResolvedValue({ records: [first, current], total: 2, page: 1, pageSize: 20 }),
      getAuditRun,
    })

    mountView(pinia)
    await flushPromises()

    expect(store.selectedAuditId).toBe(current.id)
    expect(store.auditDetail?.run.id).toBe(current.id)
    expect(getAuditRun).toHaveBeenCalledOnce()
    expect(getAuditRun).toHaveBeenCalledWith(current.id)
  })

  it('重新加载审计列表时暴露 busy 状态并把失败收敛为可重试错误', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:audit:read'])
    const auditRun = run('run-load-error')
    const listAuditRuns = vi.fn().mockResolvedValue({ records: [auditRun], total: 1, page: 1, pageSize: 20 })
    setAiClient({
      listAuditRuns,
      getAuditRun: vi.fn().mockResolvedValue(detail(auditRun)),
    })
    const wrapper = mountView(pinia)
    await flushPromises()

    let rejectReload!: (reason?: unknown) => void
    const pendingReload = new Promise<never>((_resolve, reject) => { rejectReload = reject })
    listAuditRuns.mockReturnValueOnce(pendingReload)
    const setupState = (wrapper.vm.$ as unknown as { setupState: Record<string, unknown> }).setupState
    const reloadPromise = (setupState.initialize as () => Promise<void>)()
    await nextTick()
    expect(wrapper.get('.audit-workbench').attributes('aria-busy')).toBe('true')

    rejectReload(new Error('审计列表暂不可用'))
    await expect(reloadPromise).resolves.toBeUndefined()
    await nextTick()
    const errorState = wrapper.findAllComponents({ name: 'AiSafetyState' })
      .find((state) => state.props('message') === '审计列表暂不可用')
    expect(errorState).toBeDefined()
    expect(wrapper.get('.audit-workbench').attributes('aria-busy')).toBe('false')
  })

  it.each(['RISK', 'risk', 'RISK ', ' KNOWLEDGE', 'Evaluation', 'evaluation '])(
    '%s 运行不提供正文读取入口，也不会诱导二次认证或触发读取请求',
    async (capability) => {
      const pinia = createPinia()
      setActivePinia(pinia)
      authorize(['ai:audit:read', 'ai:audit:content:read'])
      const auditRun = run(`run-content-unavailable-${capability.trim().toLowerCase()}`)
      auditRun.capability = capability
      const readAuditContent = vi.fn().mockResolvedValue(undefined)
      setAiClient({
        listAuditRuns: vi.fn().mockResolvedValue({ records: [auditRun], total: 1, page: 1, pageSize: 20 }),
        getAuditRun: vi.fn().mockResolvedValue(detail(auditRun)),
        readAuditContent,
      })

      const wrapper = mountView(pinia)
      await flushPromises()

      const contentActions = wrapper.get('.audit-content-actions')
      expect(contentActions.text()).toContain('该能力运行不提供审计正文读取')
      expect(contentActions.text()).toContain('无法安全重建底层正文授权范围')
      expect(wrapper.get('.audit-safety-note').text()).toContain('当前能力运行不提供正文读取')
      expect(wrapper.get('.audit-safety-note').text()).not.toContain('额外授权和二次认证后短暂显示')
      expect(contentActions.findAll('button')).toHaveLength(0)
      expect(wrapper.find('[aria-label="审计正文读取理由"]').exists()).toBe(false)
      expect(wrapper.find('[aria-label="当前密码"]').exists()).toBe(false)
      expect(wrapper.findComponent(ModalStub).exists()).toBe(false)

      const setupState = (wrapper.vm.$ as unknown as { setupState: Record<string, unknown> }).setupState
      await (setupState.readContent as () => Promise<void>)()
      expect(readAuditContent).not.toHaveBeenCalled()
    },
  )

  it('break-glass 要求有效理由和当前密码，正文只按纯文本短暂呈现', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:audit:read', 'ai:audit:content:read'])
    const auditRun = run('run-content')
    const content: AiAuditRunContent = {
      runId: auditRun.id,
      messages: [{ id: 'message-1', role: 'USER', content: '<img src=x onerror=alert(1)>复核正文', classification: 'L2', createdAt: auditRun.occurredAt }],
    }
    const readAuditContent = vi.fn().mockResolvedValue(content)
    setAiClient({
      listAuditRuns: vi.fn().mockResolvedValue({ records: [auditRun], total: 1, page: 1, pageSize: 20 }),
      getAuditRun: vi.fn().mockResolvedValue(detail(auditRun)),
      readAuditContent,
    })
    const wrapper = mountView(pinia)
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '读取审计正文')!.trigger('click')
    const modal = wrapper.getComponent(ModalStub)
    expect((modal.props('okButtonProps') as { disabled?: boolean }).disabled).toBe(true)
    await wrapper.get<HTMLTextAreaElement>('[aria-label="审计正文读取理由"]').setValue('复核异常')
    await wrapper.get<HTMLInputElement>('[aria-label="当前密码"]').setValue('current-password')
    expect((modal.props('okButtonProps') as { disabled?: boolean }).disabled).toBe(true)
    await wrapper.get<HTMLTextAreaElement>('[aria-label="审计正文读取理由"]').setValue('复核运行异常与授权访问范围')
    await nextTick()
    expect((modal.props('okButtonProps') as { disabled?: boolean }).disabled).toBe(false)

    modal.vm.$emit('ok')
    await flushPromises()
    expect(readAuditContent).toHaveBeenCalledWith(auditRun.id, '复核运行异常与授权访问范围', 'current-password')
    expect(wrapper.find('.ai-audit-content img').exists()).toBe(false)
    expect(wrapper.text()).toContain('<img src=x onerror=alert(1)>复核正文')

    useAuthStore().user = { id: 7, userName: 'Auditor', roleCode: 'ADMIN', roleCodes: ['ADMIN'], permissions: ['ai:audit:read'] }
    await nextTick()
    expect(wrapper.text()).not.toContain('复核正文')
    expect(modal.props('open')).toBe(false)
  })

  it('正文读取提示绑定当前运行，切换到不可读取运行时销毁旧成功提示', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:audit:read', 'ai:audit:content:read'])
    const readableRun = run('run-content-readable')
    const unavailableRun = { ...run('run-content-unavailable'), capability: 'RISK' as const }
    const content: AiAuditRunContent = {
      runId: readableRun.id,
      messages: [{ id: 'message-1', role: 'USER', content: '仅属于首个运行的正文', classification: 'L2', createdAt: readableRun.occurredAt }],
    }
    setAiClient({
      listAuditRuns: vi.fn().mockResolvedValue({ records: [readableRun, unavailableRun], total: 2, page: 1, pageSize: 20 }),
      getAuditRun: vi.fn().mockImplementation((id: string) => Promise.resolve(detail(id === readableRun.id ? readableRun : unavailableRun))),
      readAuditContent: vi.fn().mockResolvedValue(content),
    })
    const wrapper = mountView(pinia)
    await flushPromises()

    await wrapper.findAll('button').find((button) => button.text() === '读取审计正文')!.trigger('click')
    const modal = wrapper.getComponent(ModalStub)
    await wrapper.get<HTMLTextAreaElement>('[aria-label="审计正文读取理由"]').setValue('复核运行异常与授权访问范围')
    await wrapper.get<HTMLInputElement>('[aria-label="当前密码"]').setValue('current-password')
    modal.vm.$emit('ok')
    await flushPromises()

    expect(message.success).toHaveBeenCalledWith({
      key: 'ai-audit-content-read',
      content: `运行 ${readableRun.id}：审计正文已按授权读取`,
    })
    expect(wrapper.text()).toContain('仅属于首个运行的正文')
    vi.mocked(message.destroy).mockClear()

    await wrapper.findAll('.run-select').find((button) => button.text().includes(unavailableRun.id))!.trigger('click')
    await flushPromises()

    expect(message.destroy).toHaveBeenCalledWith('ai-audit-content-read')
    expect(wrapper.text()).not.toContain('仅属于首个运行的正文')
    expect(wrapper.text()).toContain('该能力运行不提供审计正文读取')
  })
})
