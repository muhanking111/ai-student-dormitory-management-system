import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AiClient } from '../api/ai'
import { setAiClient } from '../api/ai-client'
import { ApiError } from '../api/client'
import { useAiApprovalStore } from '../stores/aiApproval'
import { useAuthStore } from '../stores/auth'
import type { AiAuditRun, AiAuditRunContent, AiAuditRunDetail, AiProposalPreview } from '../types/ai'
import AiApprovalView from './AiApprovalView.vue'
import AiAuditView from './AiAuditView.vue'

const { messageError, messageSuccess } = vi.hoisted(() => ({
  messageError: vi.fn(),
  messageSuccess: vi.fn(),
}))

vi.mock('ant-design-vue', () => ({
  message: { error: messageError, success: messageSuccess, info: vi.fn() },
}))

vi.mock('../api/ai-client', async () => {
  const actual = await vi.importActual<typeof import('../api/ai-client')>('../api/ai-client')
  return {
    ...actual,
    getAiClientMode: () => 'http',
    isAiSurfaceEnabled: () => true,
  }
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
  inheritAttrs: false,
  props: { open: { type: Boolean, default: false } },
  emits: ['ok', 'cancel', 'update:open'],
  setup(_props, { slots }) {
    return () => h('div', { class: 'modal-stub' }, slots.default?.())
  },
})

const auditRun: AiAuditRun = {
  id: 'run-sensitive',
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
  occurredAt: '2026-07-14T00:00:00Z',
  steps: [],
}

const auditDetail: AiAuditRunDetail = {
  run: auditRun, steps: [], retrievals: [], tools: [], citations: [], proposals: [], approvals: [], executions: [], usage: [], hashChain: [],
}
const sensitiveContent: AiAuditRunContent = {
  runId: auditRun.id,
  messages: [{
    id: 'message-sensitive',
    role: 'USER',
    content: '仅限授权查看的审计正文',
    classification: 'L2',
    createdAt: '2026-07-14T00:00:00Z',
  }],
}

const needsReviewProposal: AiProposalPreview = {
  id: 'proposal-needs-review',
  actionType: 'REPAIR_ASSIGN',
  title: '维修指派待对账',
  target: '维修单 R-1',
  currentValue: '未指派',
  proposedValue: '维修员 A',
  impact: '仅变更负责人',
  requiredPermission: 'repair:write',
  payloadHash: 'a'.repeat(64),
  businessSnapshotHash: 'b'.repeat(64),
  version: 3,
  expiresAt: '2099-07-14T01:00:00Z',
  riskLevel: 'high',
  evidence: {
    basis: 'deterministic',
    asOf: '2026-07-14T00:00:00Z',
    citations: [{ id: 'citation-test', label: 'test', locator: 'RUN:test', version: 'v1', access: 'available' }],
    grounded: true,
  },
  state: 'needs_review',
  auditAvailable: true,
  executionState: 'needs_review',
  executionId: 'execution-needs-review',
}
const pendingApprovalProposal: AiProposalPreview = {
  ...needsReviewProposal,
  id: 'proposal-pending',
  state: 'pending_approval',
  executionState: 'pending',
  executionId: 'execution-pending',
}

const viewStubs = {
  AInputPassword: PasswordInputStub,
  ATextarea: TextareaStub,
  ASelect: true,
  ASegmented: true,
  AModal: ModalStub,
  AiSafetyState: true,
  AiEvidenceMeta: true,
  AiProposalPreview: true,
}

function authorizedUser(permissions: string[]) {
  return {
    id: 7,
    userName: 'Auditor',
    roleCode: 'ADMIN',
    roleCodes: ['ADMIN'],
    permissions,
  }
}

function mountAuditView(pinia: ReturnType<typeof createPinia>) {
  return shallowMount(AiAuditView, {
    global: { plugins: [pinia], stubs: viewStubs },
  })
}

function mountApprovalView(pinia: ReturnType<typeof createPinia>) {
  const auth = useAuthStore()
  if (!auth.user) {
    auth.$patch({
      user: authorizedUser(['ai:approval:review', 'repair:write', 'notice:write']),
      initialized: true,
    })
  }
  return shallowMount(AiApprovalView, {
    global: { plugins: [pinia], stubs: viewStubs },
  })
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((fulfill) => { resolve = fulfill })
  return { promise, resolve }
}

async function startAuditContentRead(wrapper: ReturnType<typeof mountAuditView>) {
  wrapper.getComponent({ name: 'AModal' }).vm.$emit('ok')
  await nextTick()
}

describe('AI audit sensitive content lifecycle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setAiClient(undefined)
  })

  it('读取正文后离开页面再返回，不显示共享 store 中的缓存正文', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.selectedAuditId = auditRun.id
    store.auditDetail = auditDetail
    setAiClient({
      readAuditContent: vi.fn().mockResolvedValue(sensitiveContent),
    })

    const firstVisit = mountAuditView(pinia)
    await startAuditContentRead(firstVisit)
    await flushPromises()
    expect(firstVisit.text()).toContain('仅限授权查看的审计正文')

    firstVisit.unmount()
    const returnVisit = mountAuditView(pinia)
    await nextTick()

    expect(returnVisit.text()).not.toContain('仅限授权查看的审计正文')
  })

  it('撤销 ai:audit:content:read 后立即停止渲染并清空正文', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.selectedAuditId = auditRun.id
    store.auditDetail = auditDetail
    setAiClient({ readAuditContent: vi.fn().mockResolvedValue(sensitiveContent) })
    const wrapper = mountAuditView(pinia)
    await startAuditContentRead(wrapper)
    await flushPromises()
    expect(wrapper.text()).toContain('仅限授权查看的审计正文')
    const reason = wrapper.get<HTMLTextAreaElement>('[aria-label="审计正文读取理由"]')
    await reason.setValue('复核 run-sensitive 的安全事件')

    auth.user = authorizedUser(['ai:audit:read'])
    await nextTick()

    expect(wrapper.text()).not.toContain('仅限授权查看的审计正文')
    expect(reason.element.value).toBe('')
  })

  it('正文请求 pending 时离页，响应随后到达也不能回填到返回页面', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.selectedAuditId = auditRun.id
    store.auditDetail = auditDetail
    const pending = deferred<AiAuditRunContent>()
    const readAuditContent = vi.fn().mockReturnValue(pending.promise)
    setAiClient({ readAuditContent })
    const firstVisit = mountAuditView(pinia)

    await startAuditContentRead(firstVisit)
    expect(readAuditContent).toHaveBeenCalledOnce()
    firstVisit.unmount()
    pending.resolve(sensitiveContent)
    await flushPromises()

    const returnVisit = mountAuditView(pinia)
    await nextTick()
    expect(returnVisit.text()).not.toContain('仅限授权查看的审计正文')
  })

  it('正文请求 pending 时撤权，响应到达后再恢复权限也不能重显', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.selectedAuditId = auditRun.id
    store.auditDetail = auditDetail
    const pending = deferred<AiAuditRunContent>()
    const readAuditContent = vi.fn().mockReturnValue(pending.promise)
    setAiClient({ readAuditContent })
    const wrapper = mountAuditView(pinia)

    await startAuditContentRead(wrapper)
    expect(readAuditContent).toHaveBeenCalledOnce()
    auth.user = authorizedUser(['ai:audit:read'])
    await nextTick()
    pending.resolve(sensitiveContent)
    await flushPromises()
    auth.user = authorizedUser(['ai:audit:read', 'ai:audit:content:read'])
    await nextTick()

    expect(wrapper.text()).not.toContain('仅限授权查看的审计正文')
  })

  it('正文请求 pending 时权限撤销后恢复，旧响应也不能回填', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.selectedAuditId = auditRun.id
    store.auditDetail = auditDetail
    const pending = deferred<AiAuditRunContent>()
    const readAuditContent = vi.fn().mockReturnValue(pending.promise)
    setAiClient({ readAuditContent })
    const wrapper = mountAuditView(pinia)

    await startAuditContentRead(wrapper)
    expect(readAuditContent).toHaveBeenCalledOnce()
    auth.user = authorizedUser(['ai:audit:read'])
    await nextTick()
    auth.user = authorizedUser(['ai:audit:read', 'ai:audit:content:read'])
    await nextTick()
    pending.resolve(sensitiveContent)
    await flushPromises()

    expect(wrapper.text()).not.toContain('仅限授权查看的审计正文')
  })

  it('正文请求 pending 时同 tick 撤权并恢复，旧响应也不能回填', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.selectedAuditId = auditRun.id
    store.auditDetail = auditDetail
    const pending = deferred<AiAuditRunContent>()
    setAiClient({ readAuditContent: vi.fn().mockReturnValue(pending.promise) })
    const wrapper = mountAuditView(pinia)

    await startAuditContentRead(wrapper)
    auth.user = authorizedUser(['ai:audit:read'])
    auth.user = authorizedUser(['ai:audit:read', 'ai:audit:content:read'])
    pending.resolve(sensitiveContent)
    await flushPromises()

    expect(wrapper.text()).not.toContain('仅限授权查看的审计正文')
  })

  it('正文请求 pending 时审计选择从 A 切到 B 再切回 A，旧响应也不能回填', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.selectedAuditId = auditRun.id
    store.auditDetail = auditDetail
    const pending = deferred<AiAuditRunContent>()
    const readAuditContent = vi.fn().mockReturnValue(pending.promise)
    setAiClient({ readAuditContent })
    const wrapper = mountAuditView(pinia)

    await startAuditContentRead(wrapper)
    expect(readAuditContent).toHaveBeenCalledOnce()
    store.selectedAuditId = 'run-other'
    await nextTick()
    store.selectedAuditId = auditRun.id
    await nextTick()
    pending.resolve(sensitiveContent)
    await flushPromises()

    expect(wrapper.text()).not.toContain('仅限授权查看的审计正文')
  })

  it('正文响应的 runId 与请求审计不一致时拒绝渲染', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.selectedAuditId = auditRun.id
    store.auditDetail = auditDetail
    setAiClient({ readAuditContent: vi.fn().mockResolvedValue({ ...sensitiveContent, runId: 'run-other' }) })
    const wrapper = mountAuditView(pinia)

    await startAuditContentRead(wrapper)
    await flushPromises()

    expect(wrapper.text()).not.toContain('仅限授权查看的审计正文')
  })

  it('当前审计详情与请求审计不一致时拒绝渲染正文', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.selectedAuditId = auditRun.id
    store.auditDetail = {
      ...auditDetail,
      run: { ...auditDetail.run, id: 'run-other' },
    }
    setAiClient({ readAuditContent: vi.fn().mockResolvedValue(sensitiveContent) })
    const wrapper = mountAuditView(pinia)

    await startAuditContentRead(wrapper)
    await flushPromises()

    expect(wrapper.text()).not.toContain('仅限授权查看的审计正文')
  })

  it('取消审计正文弹窗时立即清空当前密码', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.selectedAuditId = auditRun.id
    store.auditDetail = auditDetail
    const wrapper = mountAuditView(pinia)
    const password = wrapper.get<HTMLInputElement>('[aria-label="当前密码"]')
    const reason = wrapper.get<HTMLTextAreaElement>('[aria-label="审计正文读取理由"]')
    await password.setValue('one-time-password')
    await reason.setValue('复核 run-sensitive 的安全事件')

    wrapper.getComponent({ name: 'AModal' }).vm.$emit('cancel')
    await nextTick()

    expect(password.element.value).toBe('')
    expect(reason.element.value).toBe('')
  })

  it('正文请求 pending 时取消弹窗，旧响应不能继续回填', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.selectedAuditId = auditRun.id
    store.auditDetail = auditDetail
    const pending = deferred<AiAuditRunContent>()
    setAiClient({ readAuditContent: vi.fn().mockReturnValue(pending.promise) })
    const wrapper = mountAuditView(pinia)

    await startAuditContentRead(wrapper)
    wrapper.getComponent({ name: 'AModal' }).vm.$emit('cancel')
    await nextTick()
    pending.resolve(sensitiveContent)
    await flushPromises()

    expect(wrapper.text()).not.toContain('仅限授权查看的审计正文')
  })

  it('切换审计选择时立即清空正文读取密码', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.selectedAuditId = auditRun.id
    store.auditDetail = auditDetail
    const wrapper = mountAuditView(pinia)
    const password = wrapper.get<HTMLInputElement>('[aria-label="当前密码"]')
    const reason = wrapper.get<HTMLTextAreaElement>('[aria-label="审计正文读取理由"]')
    await password.setValue('one-time-password')
    await reason.setValue('复核 run-sensitive 的安全事件')

    store.selectedAuditId = 'run-other'
    await nextTick()

    expect(password.element.value).toBe('')
    expect(reason.element.value).toBe('')
  })

  it('同一审计重新选择时立即关闭弹窗并使 pending 正文响应失效', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.audits = [auditRun]
    store.selectedAuditId = auditRun.id
    store.auditDetail = auditDetail
    const pending = deferred<AiAuditRunContent>()
    setAiClient({
      getAuditRun: vi.fn().mockResolvedValue(auditDetail),
      readAuditContent: vi.fn().mockReturnValue(pending.promise),
    })
    const wrapper = mountAuditView(pinia)
    const openButton = wrapper.findAll('button').find((button) => button.text() === '读取审计正文')
    const selectButton = wrapper.findAll('button').find((button) => button.text() === '查看运行详情')
    expect(openButton).toBeDefined()
    expect(selectButton).toBeDefined()
    await openButton!.trigger('click')
    const password = wrapper.get<HTMLInputElement>('[aria-label="当前密码"]')
    const reason = wrapper.get<HTMLTextAreaElement>('[aria-label="审计正文读取理由"]')
    await password.setValue('one-time-password')
    await reason.setValue('复核 run-sensitive 的安全事件')
    await startAuditContentRead(wrapper)

    await selectButton!.trigger('click')
    await flushPromises()

    expect(wrapper.getComponent({ name: 'AModal' }).props('open')).toBe(false)
    expect(password.element.value).toBe('')
    expect(reason.element.value).toBe('')
    pending.resolve(sensitiveContent)
    await flushPromises()
    expect(wrapper.text()).not.toContain('仅限授权查看的审计正文')
  })

  it('离开审计页面时清空仍被组件持有的当前密码', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ user: authorizedUser(['ai:audit:read', 'ai:audit:content:read']), initialized: true })
    const store = useAiApprovalStore()
    store.selectedAuditId = auditRun.id
    store.auditDetail = auditDetail
    const wrapper = mountAuditView(pinia)
    await wrapper.get<HTMLInputElement>('[aria-label="当前密码"]').setValue('one-time-password')
    await wrapper.get<HTMLTextAreaElement>('[aria-label="审计正文读取理由"]').setValue('复核 run-sensitive 的安全事件')
    const setupState = (wrapper.vm.$ as unknown as { setupState: Record<string, unknown> }).setupState
    expect(setupState.currentPassword).toBe('one-time-password')
    expect(setupState.contentReason).toBe('复核 run-sensitive 的安全事件')

    wrapper.unmount()

    expect(setupState.currentPassword).toBe('')
    expect(setupState.contentReason).toBe('')
  })
})

describe('AI approval password lifecycle', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setAiClient(undefined)
  })

  it.each([
    ['Promise reject', new Error('network unavailable')],
    ['HTTP 403', new ApiError(403, 403, 'forbidden')],
    ['HTTP 409', new ApiError(409, 409, 'conflict')],
  ])('%s 后始终清空密码 ref 和输入框', async (_label, failure) => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useAiApprovalStore()
    store.proposals = [{ ...needsReviewProposal }]
    store.selectedId = needsReviewProposal.id
    const reconfirmExecution = vi.fn().mockRejectedValue(failure)
    const client: AiClient = {
      reconfirmExecution,
      getProposal: vi.fn().mockResolvedValue(needsReviewProposal),
    }
    setAiClient(client)
    const wrapper = mountApprovalView(pinia)
    const comment = wrapper.get<HTMLTextAreaElement>('[aria-label="人工对账说明"]')
    const password = wrapper.get<HTMLInputElement>('[aria-label="人工对账当前密码"]')
    await comment.setValue('人工确认仍需复核业务事实')
    await password.setValue('one-time-password')

    await wrapper.get('form.ai-reconfirm').trigger('submit')
    await flushPromises()

    expect(reconfirmExecution).toHaveBeenCalledOnce()
    expect(password.element.value).toBe('')
  })

  it('取消批准确认弹窗时立即清空当前密码', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useAiApprovalStore()
    store.proposals = [{ ...pendingApprovalProposal }]
    store.selectedId = pendingApprovalProposal.id
    const wrapper = mountApprovalView(pinia)
    const password = wrapper.get<HTMLInputElement>('[aria-label="当前密码"]')
    await password.setValue('one-time-password')

    wrapper.getComponent({ name: 'AModal' }).vm.$emit('cancel')
    await nextTick()

    expect(password.element.value).toBe('')
  })

  it('切换提案选择时立即清空人工对账密码', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useAiApprovalStore()
    const otherProposal = {
      ...needsReviewProposal,
      id: 'proposal-other',
      executionId: 'execution-other',
    }
    store.proposals = [{ ...needsReviewProposal }, otherProposal]
    store.selectedId = needsReviewProposal.id
    setAiClient({})
    const wrapper = mountApprovalView(pinia)
    const password = wrapper.get<HTMLInputElement>('[aria-label="人工对账当前密码"]')
    const comment = wrapper.get<HTMLTextAreaElement>('[aria-label="人工对账说明"]')
    await password.setValue('one-time-password')
    await comment.setValue('提案 A 的人工对账说明')
    wrapper.getComponent({ name: 'ASelect' }).vm.$emit('update:value', 'PROVEN_NOT_EXECUTED')
    const setupState = (wrapper.vm.$ as unknown as { setupState: Record<string, unknown> }).setupState
    setupState.resultMessage = '提案 A 的旧结果'

    store.selectedId = otherProposal.id
    await nextTick()

    expect(wrapper.get<HTMLInputElement>('[aria-label="人工对账当前密码"]').element.value).toBe('')
    expect(wrapper.get<HTMLTextAreaElement>('[aria-label="人工对账说明"]').element.value).toBe('')
    expect(setupState.reconfirmResolution).toBe('UNKNOWN')
    expect(setupState.resultMessage).toBe('')
  })

  it('切换提案选择时清空批准密码并关闭确认弹窗', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useAiApprovalStore()
    const otherProposal = {
      ...pendingApprovalProposal,
      id: 'proposal-pending-other',
      executionId: 'execution-pending-other',
    }
    store.proposals = [{ ...pendingApprovalProposal }, otherProposal]
    store.selectedId = pendingApprovalProposal.id
    setAiClient({})
    const wrapper = mountApprovalView(pinia)
    const approveButton = wrapper.findAll('button').find((button) => button.text() === '批准执行')
    expect(approveButton).toBeDefined()
    await wrapper.get<HTMLInputElement>('.approval-attestation input').setValue(true)
    await approveButton!.trigger('click')
    const password = wrapper.get<HTMLInputElement>('[aria-label="当前密码"]')
    await password.setValue('one-time-password')
    expect(wrapper.getComponent({ name: 'AModal' }).props('open')).toBe(true)

    store.selectedId = otherProposal.id
    await nextTick()

    expect(wrapper.get<HTMLInputElement>('[aria-label="当前密码"]').element.value).toBe('')
    expect(wrapper.getComponent({ name: 'AModal' }).props('open')).toBe(false)
  })

  it('离开审批页面时清空批准和人工对账密码 ref', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useAiApprovalStore()
    store.proposals = [{ ...needsReviewProposal }]
    store.selectedId = needsReviewProposal.id
    setAiClient({})
    const wrapper = mountApprovalView(pinia)
    await wrapper.get<HTMLInputElement>('[aria-label="当前密码"]').setValue('approval-password')
    await wrapper.get<HTMLInputElement>('[aria-label="人工对账当前密码"]').setValue('reconfirm-password')
    const setupState = (wrapper.vm.$ as unknown as { setupState: Record<string, unknown> }).setupState
    expect(setupState.currentPassword).toBe('approval-password')
    expect(setupState.reconfirmPassword).toBe('reconfirm-password')

    wrapper.unmount()

    expect(setupState.currentPassword).toBe('')
    expect(setupState.reconfirmPassword).toBe('')
  })

  it('B 提案详情 pending 时为 A 打开的批准确认不能在 B 落地后批准 B', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useAiApprovalStore()
    const proposalA = { ...pendingApprovalProposal, id: 'proposal-a', title: '待批准提案 A', executionId: 'execution-a' }
    const proposalB = { ...pendingApprovalProposal, id: 'proposal-b', title: '待批准提案 B', executionId: 'execution-b' }
    store.proposals = [proposalA, proposalB]
    store.selectedId = proposalA.id
    const pendingB = deferred<AiProposalPreview>()
    const approveProposal = vi.fn().mockImplementation(async (id: string) => ({
      ...(id === proposalA.id ? proposalA : proposalB),
      state: 'approved' as const,
      executionState: 'succeeded' as const,
    }))
    setAiClient({
      getProposal: vi.fn().mockReturnValue(pendingB.promise),
      approveProposal,
    })
    const wrapper = mountApprovalView(pinia)
    const proposalBRow = wrapper.findAll('article.ai-proposal-row').find((row) => row.text().includes('待批准提案 B'))
    expect(proposalBRow).toBeDefined()
    await proposalBRow!.get('button').trigger('click')
    const approveButton = wrapper.findAll('button').find((button) => button.text() === '批准执行')
    expect(approveButton).toBeDefined()
    await wrapper.get<HTMLInputElement>('.approval-attestation input').setValue(true)
    await approveButton!.trigger('click')
    const password = wrapper.get<HTMLInputElement>('[aria-label="当前密码"]')
    await password.setValue('password-entered-for-a')

    pendingB.resolve(proposalB)
    await flushPromises()

    expect(store.selectedId).toBe(proposalB.id)
    expect(wrapper.getComponent({ name: 'AModal' }).props('open')).toBe(false)
    expect(wrapper.get<HTMLInputElement>('[aria-label="当前密码"]').element.value).toBe('')
    await wrapper.get<HTMLInputElement>('[aria-label="当前密码"]').setValue('password-after-selection-change')
    wrapper.getComponent({ name: 'AModal' }).vm.$emit('ok')
    await flushPromises()

    expect(approveProposal).not.toHaveBeenCalled()
    expect(messageError).toHaveBeenCalledWith('提案已变化，请重新确认')
  })
})
