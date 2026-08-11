import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DemoAiClient } from '../api/ai-demo'
import { setAiClient } from '../api/ai-client'
import { useAuthStore } from '../stores/auth'
import { useAiStore } from '../stores/ai'
import { useAiApprovalStore } from '../stores/aiApproval'
import { useOperationsStore } from '../stores/operations'
import type { Notice } from '../types/dormitory'
import NoticeManagementView from './NoticeManagementView.vue'
import noticeViewSource from './NoticeManagementView.vue?raw'

const { modalInfo, modalConfirm, messageError, fetchNoticesMock } = vi.hoisted(() => ({
  modalInfo: vi.fn(),
  modalConfirm: vi.fn(),
  messageError: vi.fn(),
  fetchNoticesMock: vi.fn(),
}))

vi.mock('ant-design-vue', () => ({
  Modal: { info: modalInfo, confirm: modalConfirm },
  message: { error: messageError, success: vi.fn(), warning: vi.fn() },
}))

vi.mock('../api/operations', async () => {
  const actual = await vi.importActual<typeof import('../api/operations')>('../api/operations')
  return { ...actual, fetchNotices: fetchNoticesMock }
})

const stubs = {
  AAlert: { template: '<div><slot /></div>' },
  AButton: { template: '<button><slot name="icon" /><slot /></button>' },
  AForm: { methods: { validate: vi.fn() }, template: '<form><slot /></form>' },
  AFormItem: { template: '<label><slot /></label>' },
  AInput: {
    props: ['value'],
    emits: ['update:value'],
    template: '<input :value="value" @input="$emit(\'update:value\', $event.target.value)" />',
  },
  AModal: { template: '<div data-testid="notice-edit-modal"><slot /></div>' },
  ASelect: {
    props: ['value'],
    emits: ['update:value'],
    template: '<select :value="value" @change="$emit(\'update:value\', $event.target.value)"><slot /></select>',
  },
  ASelectOption: { props: ['value'], template: '<option :value="value"><slot /></option>' },
  ASegmented: { template: '<div><slot /></div>' },
  ATable: { template: '<div data-testid="notice-list-table"><slot /></div>' },
  ATag: { template: '<span><slot /></span>' },
  ATextarea: {
    props: ['value', 'autoSize'],
    emits: ['update:value'],
    template: '<textarea :value="value" :data-auto-size-min-rows="autoSize?.minRows" :data-auto-size-max-rows="autoSize?.maxRows" @input="$emit(\'update:value\', $event.target.value)" />',
  },
  AiEvidenceMeta: true,
  AiProposalPreview: true,
  AiSafetyState: true,
}

async function mountAt(path: '/notices' | '/notices/create', publishedNotices: Notice[] = []) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.$patch({
    initialized: true,
    user: {
      id: 1,
      userName: '管理员',
      roleCode: 'ADMIN',
      roleCodes: ['ADMIN'],
      permissions: ['notice:read', 'notice:write', 'ai:notice:draft', 'ai:approval:review'],
    },
  })
  const store = useOperationsStore()
  const loadNotices = vi.spyOn(store, 'loadNotices').mockResolvedValue(undefined)
  fetchNoticesMock.mockResolvedValue({
    records: publishedNotices.filter((notice) => notice.status === '已发布'),
    total: publishedNotices.filter((notice) => notice.status === '已发布').length,
    page: 1,
    pageSize: 1,
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/notices', name: 'notices', component: { template: '<div />' } },
      { path: '/notices/create', name: 'noticeCreate', component: { template: '<div />' } },
      { path: '/ai/approvals', name: 'aiApprovals', component: { template: '<div />' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  const wrapper = shallowMount(NoticeManagementView, {
    global: { plugins: [pinia, router], stubs },
  })
  await flushPromises()
  return { wrapper, router, ai: useAiStore(), approval: useAiApprovalStore(), store, loadNotices }
}

describe('NoticeManagementView high-fidelity workspace', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    setAiClient(new DemoAiClient())
  })

  it('在发布路由渲染全页三栏起草、内容检查、差异和人工审批流程', async () => {
    const { wrapper } = await mountAt('/notices/create')

    const workspace = wrapper.get('[role="region"][aria-label="公告 AI 起草工作台"]')
    expect(workspace.find('[role="region"][aria-label="公告要点"]').exists()).toBe(true)
    expect(workspace.find('[role="region"][aria-label="AI 草稿"]').exists()).toBe(true)
    expect(workspace.find('[role="region"][aria-label="内容检查"]').exists()).toBe(true)
    expect(workspace.find('[role="region"][aria-label="变更预览"]').exists()).toBe(true)
    expect(workspace.text()).toContain('敏感词/PII 检查')
    expect(workspace.text()).toContain('当前内容')
    expect(workspace.text()).toContain('AI 建议')
    expect(workspace.find('[aria-label="AI 建议 → 变更预览 → 人工审批"]').exists()).toBe(true)
    expect(workspace.text()).toContain('保存为草稿')
    expect(workspace.text()).toContain('提交审批')
    expect(wrapper.find('[data-testid="notice-edit-modal"]').exists()).toBe(false)
  })

  it('页面只保留共享 Header 主标题，工作区从面包屑直接进入三栏主体', async () => {
    const { wrapper } = await mountAt('/notices/create')

    expect(wrapper.find('.notice-create-heading h2').exists()).toBe(false)
    expect(wrapper.find('.notice-create-heading .notice-breadcrumb').exists()).toBe(true)
    expect(noticeViewSource).not.toContain('<h2>公告 AI 起草</h2>')
  })

  it('公告核心文字与辅助文字分别满足 14px 和 12px 可读性下限', () => {
    expect(noticeViewSource).toMatch(/\.notice-points-panel :deep\(\.ant-form-item-label > label\)\s*\{[^}]*font-size:\s*14px;/s)
    expect(noticeViewSource).toMatch(/\.notice-content-field :deep\(textarea\.ant-input\)\s*\{[^}]*font-size:\s*15px;/s)
    expect(noticeViewSource).toMatch(/\.notice-check-list dt,[\s\S]*\.notice-check-list dd\s*\{[^}]*font-size:\s*14px;/s)
    expect(noticeViewSource).toMatch(/\.notice-diff-panel pre\s*\{[^}]*font-size:\s*14px;/s)
    expect(noticeViewSource).toMatch(/\.notice-approval-flow strong\s*\{[^}]*font-size:\s*14px;/s)
    expect(noticeViewSource).toMatch(/\.notice-draft-footer\s*\{[^}]*font-size:\s*12px;/s)
    expect(noticeViewSource).toMatch(/\.notice-approval-flow small\s*\{[^}]*font-size:\s*12px;/s)
    expect(noticeViewSource).toMatch(/\.notice-diff-panel article > span\s*\{[^}]*font-size:\s*12px;/s)
  })

  it('公告自定义按钮、语气选项和恢复入口均保持至少 44px 触控尺寸', () => {
    expect(noticeViewSource).toMatch(/\.notice-snapshot-alert button\s*\{[^}]*min-height:\s*44px;/s)
    expect(noticeViewSource).toMatch(/\.notice-tone-options button\s*\{[^}]*min-height:\s*44px;/s)
    expect(noticeViewSource).toMatch(/\.notice-primary-button,[\s\S]*\.notice-disabled-button\s*\{[^}]*min-height:\s*44px;/s)
  })

  it('检查区、变更箭头和审批步骤使用稳定图标容器建立原型扫描层级', () => {
    expect(noticeViewSource).toMatch(/\.notice-check-list dt svg\s*\{[^}]*font-size:\s*20px;/s)
    expect(noticeViewSource).toMatch(/\.notice-diff-arrow\s*\{[^}]*width:\s*44px;[^}]*height:\s*44px;/s)
    expect(noticeViewSource).toMatch(/\.notice-approval-flow li > span\s*\{[^}]*width:\s*48px;[^}]*height:\s*48px;/s)
  })

  it('公告编辑器保持原型式一体化正文层级，移动变更预览不使用隐藏嵌套滚动', () => {
    expect(noticeViewSource).toContain('class="notice-editor-frame"')
    expect(noticeViewSource).toMatch(/\.notice-title-field :deep\(\.ant-input\)\s*\{[^}]*border:\s*0;[^}]*border-bottom:/s)
    expect(noticeViewSource).toMatch(/\.notice-content-field :deep\(textarea\.ant-input\)\s*\{[^}]*border:\s*0;[^}]*font-size:\s*15px;/s)
    const mobileStyles = noticeViewSource.slice(noticeViewSource.lastIndexOf('@media (max-width: 768px)'))
    expect(mobileStyles).toMatch(/\.notice-diff-panel pre\s*\{[^}]*max-height:\s*none;[^}]*overflow:\s*visible;/s)
  })

  it('AI 草稿正文按内容自适应高度且不保留固定高编辑区', async () => {
    const { wrapper } = await mountAt('/notices/create')

    const editor = wrapper.get('textarea[aria-label="AI 草稿正文"]')
    expect(editor.attributes('data-auto-size-min-rows')).toBe('8')
    expect(editor.attributes('data-auto-size-max-rows')).toBe('18')
    expect(noticeViewSource).not.toMatch(/\.notice-content-field :deep\(textarea\.ant-input\)\s*\{[^}]*height:\s*100%\s*!important/s)
    expect(noticeViewSource).not.toMatch(/\.notice-content-field :deep\(textarea\.ant-input\)\s*\{[^}]*min-height:\s*410px/s)
    expect(noticeViewSource).not.toMatch(/\.notice-content-field :deep\(textarea\.ant-input\)\s*\{[^}]*min-height:\s*(?:320|520)px/s)
  })

  it('失败结果不覆盖人工内容，也不伪装为已生成或可提交的当前 AI 草稿', async () => {
    const client = new DemoAiClient()
    const result = await client.draftNotice({
      points: '周五开展安全巡检',
      type: '安全卫生',
      tone: '正式',
      audience: '全体学生',
    })
    result.state = 'failed'
    result.evidence.confidence = 0.91
    result.proposalId = 'proposal-notice-failed'
    result.proposalState = 'pending_approval'
    vi.spyOn(client, 'draftNotice').mockResolvedValue(result)
    setAiClient(client)
    const { wrapper, ai } = await mountAt('/notices/create')

    await wrapper.get('input[aria-label="公告标题"]').setValue('人工保留标题')
    await wrapper.get('textarea[aria-label="AI 草稿正文"]').setValue('人工保留正文')
    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周五开展安全巡检')
    await wrapper.get('.notice-generate-button').trigger('click')
    await flushPromises()

    expect((wrapper.get('input[aria-label="公告标题"]').element as HTMLInputElement).value).toBe('人工保留标题')
    expect((wrapper.get('textarea[aria-label="AI 草稿正文"]').element as HTMLTextAreaElement).value).toBe('人工保留正文')
    expect(ai.noticeDraft?.proposalId).toBeUndefined()
    expect(wrapper.get('[role="region"][aria-label="AI 草稿"]').text()).toContain('本次生成失败')
    expect(wrapper.get('[role="region"][aria-label="AI 草稿"]').text()).not.toContain('AI 草稿已生成，可继续人工编辑')
    expect(wrapper.get('[role="region"][aria-label="内容检查"]').text()).not.toContain('91%')
    expect(wrapper.get('.notice-approval-flow li').classes()).toContain('is-invalid')
    expect(wrapper.get('.notice-approval-flow li').classes()).not.toContain('complete')
    expect(wrapper.get('[aria-label="公告审批操作"]').text()).toContain('生成失败')
    expect(wrapper.get('button[aria-label="查看审批提案，提交审批"]').attributes('disabled')).toBeDefined()
  })

  it('超时结果使用独立恢复语义，不与普通生成失败混淆', async () => {
    const client = new DemoAiClient()
    const result = await client.draftNotice({
      points: '周五开展安全巡检',
      type: '安全卫生',
      tone: '正式',
      audience: '全体学生',
    })
    result.state = 'timed_out'
    result.proposalId = 'proposal-notice-timeout'
    result.proposalState = 'pending_approval'
    vi.spyOn(client, 'draftNotice').mockResolvedValue(result)
    setAiClient(client)
    const { wrapper, ai } = await mountAt('/notices/create')

    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周五开展安全巡检')
    await wrapper.get('.notice-generate-button').trigger('click')
    await flushPromises()

    expect(ai.noticeDraft?.proposalId).toBeUndefined()
    expect(wrapper.get('[role="region"][aria-label="AI 草稿"]').text()).toContain('本次生成超时')
    expect(wrapper.get('[data-testid="notice-proposal-safety"]').text()).toContain('生成超时，请重试或改用人工起草')
    expect(wrapper.get('[aria-label="公告审批操作"]').text()).toContain('生成超时')
    expect(wrapper.get('[aria-label="公告审批操作"]').text()).toContain('重试或改用人工起草')
    expect(wrapper.get('button[aria-label="查看审批提案，提交审批"]').attributes('disabled')).toBeDefined()
  })

  it('以最近已发布公告作为真实当前内容快照，并保持公告 AI 起草语境', async () => {
    const latestPublished: Notice = {
      id: 9,
      title: '最近发布的宿舍安全通知',
      type: '安全卫生',
      date: '2026-07-22 18:00',
      publisher: '学生宿舍管理中心',
      status: '已发布',
      content: [
        '近期将开展宿舍安全检查，请保持公共区域畅通。',
        '一、检查时间：具体安排以现场通知为准。',
        '二、工作要求：请提前自查用电、消防通道和公共区域，不得堆放杂物。',
        '三、整改要求：对检查发现的问题按时完成整改，并保留复核记录，发现隐患后请及时向宿管反馈，各楼层值班人员请协助提醒。',
      ].join('\n'),
    }
    const { wrapper } = await mountAt('/notices/create', [latestPublished])

    expect(fetchNoticesMock).toHaveBeenCalledWith({ page: 1, pageSize: 1, status: '已发布' })
    expect(wrapper.find('.notice-create-heading :is(h1, h2)').exists()).toBe(false)
    expect(wrapper.find('[role="region"][aria-label="公告 AI 起草工作台"]').exists()).toBe(true)
    expect(wrapper.get('.notice-breadcrumb').text()).toContain('通知管理')
    expect(wrapper.get('.notice-breadcrumb').text()).toContain('发布公告')
    expect(wrapper.get('[aria-label="变更预览"]').text()).toContain(latestPublished.title)
    const snapshot = wrapper.get('.notice-diff-panel article:first-child pre').text()
    expect(snapshot).toBe(latestPublished.content)
    expect(snapshot.length).toBeGreaterThan(120)
    expect(snapshot.split('\n')).toHaveLength(4)

    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周五开展安全巡检')
    await wrapper.get('.notice-generate-button').trigger('click')
    await flushPromises()

    expect(wrapper.get('.notice-diff-panel article:first-child pre').text()).toBe(latestPublished.content)
  })

  it('快照加载状态不污染页面标题的可访问名称', async () => {
    const { wrapper } = await mountAt('/notices/create')

    expect(wrapper.findAll('h1, h2, h3').some((heading) => heading.text().includes('发布公告'))).toBe(false)
    expect(wrapper.get('.notice-diff-status').text()).toContain('已发布公告')
  })

  it('保留公告列表和编辑弹窗，并从发布按钮进入全页工作台', async () => {
    const { wrapper, router } = await mountAt('/notices')

    expect(wrapper.find('[data-testid="notice-list-table"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="notice-edit-modal"]').exists()).toBe(true)
    expect(wrapper.find('[aria-label="公告 AI 起草工作台"]').exists()).toBe(false)

    const createButton = wrapper.findAll('button').find((button) => button.text().includes('发布公告'))
    expect(createButton).toBeDefined()
    await createButton?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.fullPath).toBe('/notices/create')
  })

  it.each([
    ['公告标题', 'input[aria-label="公告标题"]', '人工修改后的标题'],
    ['公告正文', 'textarea[aria-label="AI 草稿正文"]', ''],
    ['公告类型', 'select[aria-label="公告类型"]', '宿舍通知'],
    ['授权来源', 'select[aria-label="授权来源"]', '后勤管理处'],
  ])('AI 生成后修改%s会立即作废旧提案', async (_field, selector, value) => {
    const { wrapper, router, ai, approval } = await mountAt('/notices/create')
    const selectProposal = vi.spyOn(approval, 'selectProposal')

    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周五开展安全巡检')
    await wrapper.get('.notice-generate-button').trigger('click')
    await flushPromises()

    const submitButton = wrapper.get('button[aria-label="查看审批提案，提交审批"]')
    expect(ai.noticeDraft?.proposalId).toBe('proposal-notice-001')
    expect(submitButton.attributes('disabled')).toBeUndefined()

    await wrapper.get(selector).setValue(value)
    await flushPromises()

    expect(ai.noticeDraft?.proposalId).toBeUndefined()
    expect(wrapper.get('[aria-label="公告审批操作"]').text()).toContain('未提交')
    expect(submitButton.attributes('disabled')).toBeDefined()

    await submitButton.trigger('click')
    await flushPromises()
    expect(selectProposal).not.toHaveBeenCalled()
    expect(router.currentRoute.value.fullPath).toBe('/notices/create')
  })

  it.each([
    ['公告标题', 'input[aria-label="公告标题"]', '请求期间人工修改的标题'],
    ['公告正文', 'textarea[aria-label="AI 草稿正文"]', '请求期间人工修改的正文'],
    ['公告类型', 'select[aria-label="公告类型"]', '宿舍通知'],
    ['授权来源', 'select[aria-label="授权来源"]', '后勤管理处'],
  ])('AI 请求期间修改%s不会激活按旧上下文返回的提案', async (_field, selector, value) => {
    const client = new DemoAiClient()
    const result = await client.draftNotice({
      points: '周五开展安全巡检',
      type: '安全卫生',
      tone: '正式',
      audience: '全体学生',
    })
    let releaseDraft!: (draft: typeof result) => void
    const pendingDraft = new Promise<typeof result>((resolve) => { releaseDraft = resolve })
    const draftNotice = vi.spyOn(client, 'draftNotice').mockReturnValue(pendingDraft)
    setAiClient(client)
    const { wrapper, ai } = await mountAt('/notices/create')

    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周五开展安全巡检')
    await wrapper.get('.notice-generate-button').trigger('click')
    expect(draftNotice).toHaveBeenCalledWith(expect.objectContaining({ type: '安全卫生' }))

    await wrapper.get(selector).setValue(value)
    releaseDraft(result)
    await flushPromises()

    const submitButton = wrapper.get('button[aria-label="查看审批提案，提交审批"]')
    expect(ai.noticeDraft?.proposalId).toBeUndefined()
    expect((wrapper.get(selector).element as HTMLInputElement).value).toBe(value)
    expect(wrapper.get('[aria-label="公告审批操作"]').text()).toContain('未提交')
    expect(submitButton.attributes('disabled')).toBeDefined()
  })

  it('离开并重新进入发布页后会丢弃上一访问的迟到 AI 响应', async () => {
    const client = new DemoAiClient()
    const result = await client.draftNotice({
      points: '周五开展安全巡检',
      type: '安全卫生',
      tone: '正式',
      audience: '全体学生',
    })
    let releaseDraft!: (draft: typeof result) => void
    const pendingDraft = new Promise<typeof result>((resolve) => { releaseDraft = resolve })
    vi.spyOn(client, 'draftNotice').mockReturnValue(pendingDraft)
    setAiClient(client)
    const { wrapper, router, ai } = await mountAt('/notices/create')

    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周五开展安全巡检')
    await wrapper.get('.notice-generate-button').trigger('click')
    await router.push('/notices')
    await router.push('/notices/create')
    await flushPromises()

    releaseDraft(result)
    await flushPromises()

    expect((wrapper.get('input[aria-label="公告标题"]').element as HTMLInputElement).value).toBe('')
    expect((wrapper.get('textarea[aria-label="AI 草稿正文"]').element as HTMLTextAreaElement).value).toBe('')
    expect(ai.noticeDraft?.proposalId).toBeUndefined()
    expect(wrapper.get('[aria-label="公告审批操作"]').text()).toContain('未提交')
  })

  it('离开并重新进入发布页后不会显示上一访问的迟到失败', async () => {
    const client = new DemoAiClient()
    let rejectDraft!: (reason: Error) => void
    const pendingDraft = new Promise<never>((_resolve, reject) => { rejectDraft = reject })
    vi.spyOn(client, 'draftNotice').mockReturnValue(pendingDraft)
    setAiClient(client)
    const { wrapper, router, ai } = await mountAt('/notices/create')

    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周五开展安全巡检')
    await wrapper.get('.notice-generate-button').trigger('click')
    await router.push('/notices')
    await router.push('/notices/create')
    await flushPromises()
    messageError.mockClear()

    rejectDraft(new Error('上一访问的起草请求失败'))
    await flushPromises()

    expect(ai.error).toBeNull()
    expect(messageError).not.toHaveBeenCalled()
    expect(wrapper.find('.notice-page-alert[role="alert"]').exists()).toBe(false)
  })

  it('审批提案加载期间表单变化会中止导航并清除迟到的选择', async () => {
    const { wrapper, router, ai, approval } = await mountAt('/notices/create')
    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周五开展安全巡检')
    await wrapper.get('.notice-generate-button').trigger('click')
    await flushPromises()
    expect(ai.noticeDraft?.proposalId).toBe('proposal-notice-001')

    let releaseSelection!: () => void
    const pendingSelection = new Promise<void>((resolve) => { releaseSelection = resolve })
    const selectProposal = vi.spyOn(approval, 'selectProposal').mockImplementation(async (id) => {
      await pendingSelection
      approval.selectedId = id
    })
    const submitButton = wrapper.get('button[aria-label="查看审批提案，提交审批"]')
    await submitButton.trigger('click')
    await flushPromises()
    expect(selectProposal).toHaveBeenCalledWith('proposal-notice-001')

    await wrapper.get('input[aria-label="公告标题"]').setValue('提交期间修改标题')
    releaseSelection()
    await flushPromises()

    expect(ai.noticeDraft?.proposalId).toBeUndefined()
    expect(approval.selectedId).toBe('')
    expect(router.currentRoute.value.fullPath).toBe('/notices/create')
  })

  it('生成请求挂起时方法级拒绝重复生成并保留首个结果', async () => {
    const client = new DemoAiClient()
    const result = await client.draftNotice({
      points: '周五开展安全巡检',
      type: '安全卫生',
      tone: '正式',
      audience: '全体学生',
    })
    let releaseDraft!: (draft: typeof result) => void
    const pendingDraft = new Promise<typeof result>((resolve) => { releaseDraft = resolve })
    const draftNotice = vi.spyOn(client, 'draftNotice').mockReturnValue(pendingDraft)
    setAiClient(client)
    const { wrapper, ai } = await mountAt('/notices/create')
    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周五开展安全巡检')
    const vm = wrapper.vm as unknown as { generateDraft: () => Promise<void> }

    const firstGeneration = vm.generateDraft()
    const duplicateGeneration = vm.generateDraft()

    expect(draftNotice).toHaveBeenCalledTimes(1)
    releaseDraft(result)
    await Promise.all([firstGeneration, duplicateGeneration])
    await flushPromises()

    expect(ai.noticeDraft?.proposalId).toBe('proposal-notice-001')
    expect((wrapper.get('input[aria-label="公告标题"]').element as HTMLInputElement).value).toBe(result.title)
    expect(wrapper.get('button[aria-label="查看审批提案，提交审批"]').attributes('disabled')).toBeUndefined()
  })

  it.each([
    ['公告要点', 'points', '请求期间修改后的公告要点'],
    ['目标范围', 'audience', '全体住宿学生'],
    ['语气', 'tone', '紧急'],
  ] as const)('AI 请求期间修改%s会丢弃按旧输入返回的提案', async (_label, field, value) => {
    const client = new DemoAiClient()
    const result = await client.draftNotice({
      points: '周五开展安全巡检',
      type: '安全卫生',
      tone: '正式',
      audience: '全体学生',
    })
    let releaseDraft!: (draft: typeof result) => void
    const pendingDraft = new Promise<typeof result>((resolve) => { releaseDraft = resolve })
    const draftNotice = vi.spyOn(client, 'draftNotice').mockReturnValue(pendingDraft)
    setAiClient(client)
    const { wrapper, ai } = await mountAt('/notices/create')
    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周五开展安全巡检')

    await wrapper.get('.notice-generate-button').trigger('click')
    expect(draftNotice).toHaveBeenCalledWith(expect.objectContaining({
      points: '周五开展安全巡检',
      audience: '全体学生',
      tone: '正式',
    }))

    if (field === 'points') {
      await wrapper.get('textarea[aria-label="公告要点"]').setValue(value)
    } else if (field === 'audience') {
      await wrapper.get('select[aria-label="目标范围"]').setValue(value)
    } else {
      const toneButton = wrapper.findAll('.notice-tone-options button')
        .find((button) => button.text().includes(value))
      expect(toneButton).toBeDefined()
      await toneButton?.trigger('click')
    }

    releaseDraft(result)
    await flushPromises()

    expect(ai.noticeDraft?.proposalId).toBeUndefined()
    expect(wrapper.get('[aria-label="公告审批操作"]').text()).toContain('未提交')
    expect(wrapper.get('button[aria-label="查看审批提案，提交审批"]').attributes('disabled')).toBeDefined()
    if (field === 'points') {
      expect((wrapper.get('textarea[aria-label="公告要点"]').element as HTMLTextAreaElement).value).toBe(value)
    } else if (field === 'audience') {
      expect((wrapper.get('select[aria-label="目标范围"]').element as HTMLSelectElement).value).toBe(value)
    } else {
      const activeTone = wrapper.findAll('.notice-tone-options button')
        .find((button) => button.attributes('aria-pressed') === 'true')
      expect(activeTone?.text()).toContain(value)
    }
  })

  it.each([
    ['纯邮箱', '请联系 dorm.manager@example.edu 获取检查安排', '检测到邮箱地址'],
    ['混合 PII', '请联系 dorm.manager@example.edu 或 13812345678 获取检查安排', '检测到邮箱地址'],
  ])('公告要点含%s时在客户端阻断请求并显示安全原因', async (_label, points, expectedMessage) => {
    const client = new DemoAiClient()
    const draftNotice = vi.spyOn(client, 'draftNotice')
    setAiClient(client)
    const { wrapper } = await mountAt('/notices/create')

    await wrapper.get('textarea[aria-label="公告要点"]').setValue(points)
    await wrapper.get('.notice-generate-button').trigger('click')
    await flushPromises()

    expect(draftNotice).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"][data-testid="notice-input-safety"]').text()).toContain(expectedMessage)
  })

  it('普通 @ 符号和楼栋编号不误判为邮箱 PII', async () => {
    const client = new DemoAiClient()
    const draftNotice = vi.spyOn(client, 'draftNotice')
    setAiClient(client)
    const { wrapper } = await mountAt('/notices/create')

    await wrapper.get('textarea[aria-label="公告要点"]').setValue('请在 2 号楼 A 区公告栏 @全体同学 完成安全检查')
    await wrapper.get('.notice-generate-button').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"][data-testid="notice-input-safety"]').exists()).toBe(false)
    expect(draftNotice).toHaveBeenCalledOnce()
  })

  it.each(['stale', 'expired'] as const)('公告提案为 %s 时提交审批按钮保持禁用并显示失效状态', async (proposalState) => {
    const client = new DemoAiClient()
    vi.spyOn(client, 'draftNotice').mockResolvedValue({
      title: '安全巡检通知', type: '安全卫生', publisher: '审批执行人', status: '草稿', content: '正文',
      blocked: false, safetyMessages: [], version: 'notice-draft.v1',
      evidence: { basis: 'deterministic', asOf: '2026-07-18T10:00:00Z', grounded: true, citations: [{ id: 'c1', label: '来源', locator: 'RUN:1', version: 'v1', access: 'available' }] },
      state: 'succeeded', proposalId: 'proposal-stale', proposalState,
    } as never)
    setAiClient(client)
    const { wrapper } = await mountAt('/notices/create')
    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周五开展安全巡检')
    await wrapper.get('.notice-generate-button').trigger('click')
    await flushPromises()

    const submit = wrapper.get('button[aria-label="查看审批提案，提交审批"]')
    expect(submit.attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain(proposalState === 'stale' ? '失效' : '过期')
  })

  it('没有审批或公告写权限时仍可查看草稿，但不能提交提案', async () => {
    const pinia = createPinia(); setActivePinia(pinia)
    const auth = useAuthStore()
    auth.$patch({ initialized: true, user: { id: 1, userName: '只读用户', roleCode: 'STAFF', roleCodes: ['STAFF'], permissions: ['notice:read', 'ai:notice:draft'] } })
    const store = useOperationsStore(); vi.spyOn(store, 'loadNotices').mockResolvedValue(undefined)
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/notices/create', name: 'noticeCreate', component: { template: '<div />' } }] })
    await router.push('/notices/create'); await router.isReady()
    const wrapper = shallowMount(NoticeManagementView, { global: { plugins: [pinia, router], stubs } })
    await flushPromises()
    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周五开展安全巡检')
    await wrapper.get('.notice-generate-button').trigger('click')
    await flushPromises()

    expect(wrapper.get('button[aria-label="查看审批提案，提交审批"]').attributes('disabled')).toBeDefined()
    expect(wrapper.find('[data-testid="notice-approval-permission"]').exists()).toBe(true)
  })

  it('无可靠来源的草稿和运行失败都不会伪装为可审批状态', async () => {
    const client = new DemoAiClient()
    vi.spyOn(client, 'draftNotice')
      .mockResolvedValueOnce({
        title: '待核验通知', type: '安全卫生', publisher: '审批执行人', status: '草稿', content: '正文',
        blocked: false, safetyMessages: [], version: 'notice-draft.v1', state: 'succeeded', proposalId: 'proposal-unverified',
        evidence: { basis: 'unverified', asOf: '', grounded: false, citations: [] },
      } as never)
      .mockRejectedValueOnce(new Error('公告起草服务暂不可用'))
    setAiClient(client)
    const { wrapper } = await mountAt('/notices/create')
    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周五开展安全巡检')
    await wrapper.get('.notice-generate-button').trigger('click')
    await flushPromises()

    expect(wrapper.get('button[aria-label="查看审批提案，提交审批"]').attributes('disabled')).toBeDefined()
    expect(wrapper.get('[data-testid="notice-proposal-safety"]').text()).toContain('暂无可靠来源')

    await wrapper.get('textarea[aria-label="公告要点"]').setValue('周六开展安全巡检')
    await wrapper.get('.notice-generate-button').trigger('click')
    await flushPromises()
    expect(wrapper.get('.notice-page-alert[role="alert"]').text()).toContain('公告起草服务暂不可用')
  })
})
