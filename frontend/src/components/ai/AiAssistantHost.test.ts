import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { useAiStore } from '../../stores/ai'
import AiAssistantHost from './AiAssistantHost.vue'

const assistantSource = readFileSync(resolve(process.cwd(), 'src/components/ai/AiAssistantHost.vue'), 'utf8')

const { client } = vi.hoisted(() => ({
  client: {
    listConversations: vi.fn().mockResolvedValue({ records: [], page: 1, pageSize: 1, total: 0 }),
    getConversation: vi.fn(),
    getCitation: vi.fn().mockResolvedValue({
      id: 'citation-1', type: 'KNOWLEDGE', sourceId: 'source-1', documentId: 'document-1',
      documentVersionId: 'version-1', chunkId: 'chunk-1', metricId: null, rank: 1, score: 1,
      quote: '维修结果涉及 [PERSON_NAME:v1:PersonDigest01]，应在 2 个工作日内完成。',
      locator: '第 3.2 条', contentHash: 'a'.repeat(64), createdAt: '2026-07-11T10:30:00Z',
    }),
    submitFeedback: vi.fn().mockResolvedValue(undefined),
  },
}))

vi.mock('../../api/ai-client', () => ({
  isAiSurfaceEnabled: () => true,
  getAiClient: () => client,
}))

describe('AiAssistantHost', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    window.history.replaceState({}, '', '/')
    Object.defineProperty(window.navigator, 'clipboard', { configurable: true, value: undefined })
    document.body.style.overflow = ''
    document.body.innerHTML = '<button data-ai-assistant-trigger>打开助手</button>'
  })

  function openAssistant(router?: Router) {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useAiStore()
    store.$patch({
      assistantOpen: true,
      sessionRestored: true,
      runState: 'succeeded',
      conversationId: 'conversation-1',
      conversations: [{
        id: 'conversation-1', surface: 'GLOBAL', contextType: 'NONE', status: 'ACTIVE',
        title: '维修申请的处理时限是什么？', createdAt: '2026-07-11T10:30:00Z',
      }],
      messages: [
        { id: 'user-1', role: 'user', text: '维修申请的处理时限是什么？', citations: [] },
        {
          id: '123e4567-e89b-42d3-a456-426614174002', role: 'assistant', text: '一般维修应在受理后 2 个工作日内完成。',
          citations: [
            { id: 'citation-1', label: '宿舍维修管理办法', locator: '第 3.2 条', version: '2026.01', access: 'available' },
            { id: 'citation-2', label: '值班交接记录', locator: '内部资料', version: '2026.01', access: 'denied' },
          ],
          confidence: 0.86, grounded: true, asOf: '2026-07-11T10:30:00', runId: 'run-1',
        },
      ],
    })
    const target = document.createElement('div')
    document.body.appendChild(target)
    const wrapper = mount(AiAssistantHost, {
      attachTo: target,
      global: { plugins: router ? [pinia, router] : [pinia], stubs: { teleport: true } },
    })
    return { wrapper, store }
  }

  it('按照高保真结构展示会话、授权引用、安全元数据和快捷操作', async () => {
    const { wrapper } = openAssistant()
    await flushPromises()

    expect(wrapper.get('[role="dialog"]').text()).toContain('智能助手')
    expect(wrapper.get('[aria-label="智能助手会话内容"]').attributes('aria-live')).toBeUndefined()
    expect(wrapper.get('[role="dialog"]').text()).toContain('全局')
    expect(wrapper.get('[data-assistant-user-message]').text()).toContain('维修申请的处理时限是什么？')
    expect(wrapper.get('[data-assistant-answer]').text()).toContain('一般维修应在受理后 2 个工作日内完成')
    expect(wrapper.get('[data-assistant-answer]').attributes('data-assistant-run-id')).toBe('run-1')
    expect(wrapper.get('[data-assistant-answer]').attributes('data-assistant-message-id')).toBe('123e4567-e89b-42d3-a456-426614174002')
    expect(wrapper.get('[data-assistant-answer]').attributes('data-assistant-run-state')).toBe('succeeded')
    expect(wrapper.get('[data-assistant-answer]').attributes('data-assistant-content-state')).toBe('succeeded')
    expect(wrapper.get('[data-answer-meta]').text()).not.toContain('Z')
    expect(wrapper.findAll('[data-answer-meta]')).toHaveLength(3)
    expect(wrapper.get('[aria-label="智能助手会话内容"]').text()).toContain('一般维修应在受理后 2 个工作日内完成')
    expect(wrapper.get('[aria-label="智能助手会话内容"]').text()).toContain('置信度 86%')
    expect(wrapper.get('[aria-label="智能助手会话内容"]').text()).toContain('宿舍维修管理办法')
    expect(wrapper.get('[aria-label="智能助手会话内容"]').text()).toContain('无权限查看此来源')
    expect(wrapper.find('[data-assistant-state="citation-denied"]').exists()).toBe(true)
    expect(wrapper.findAll('[aria-label="回答操作"] button').map((button) => button.text())).toEqual(['重试', '复制', '赞同', '反馈'])
    expect(wrapper.findAll('[aria-label="快捷提问"] button').map((button) => button.text())).toEqual(['查询入住率', '查看待处理维修'])
  })

  it('历史会话与新会话按钮更新真实界面状态', async () => {
    const { wrapper, store } = openAssistant()
    const loadConversations = vi.spyOn(store, 'loadConversations').mockResolvedValue(undefined)
    const selectConversation = vi.spyOn(store, 'selectConversation').mockResolvedValue(undefined)

    await wrapper.get('button[aria-label="查看会话历史"]').trigger('click')
    expect(loadConversations).toHaveBeenCalledOnce()
    expect(wrapper.get('[aria-label="会话历史"]').text()).toContain('维修申请的处理时限是什么？')
    await wrapper.get('button[aria-label="切换到会话：维修申请的处理时限是什么？"]').trigger('click')
    expect(selectConversation).toHaveBeenCalledWith('conversation-1')

    await wrapper.get('button[aria-label="新建会话"]').trigger('click')
    expect(store.messages).toHaveLength(0)
    expect(store.assistantOpen).toBe(true)
    expect(wrapper.text()).toContain('可以询问维修时限')
  })

  it('快捷提问写入输入框且发送按钮调用真实 store action', async () => {
    const { wrapper, store } = openAssistant()
    const ask = vi.spyOn(store, 'askAssistant').mockResolvedValue(undefined)

    await wrapper.get('button[aria-label="查询入住率"]').trigger('click')
    expect((wrapper.get('textarea').element as HTMLTextAreaElement).value).toBe('查询当前宿舍入住率')
    await wrapper.get('button[aria-label="发送问题"]').trigger('click')

    expect(ask).toHaveBeenCalledWith('查询当前宿舍入住率', {
      surface: 'GLOBAL', contextType: 'NONE',
    })
  })

  it('Dashboard 底层的助手使用全局授权上下文而不是锁定为 Dashboard 工具上下文', async () => {
    window.history.replaceState({}, '', '/')
    const { wrapper, store } = openAssistant()
    const ask = vi.spyOn(store, 'askAssistant').mockResolvedValue(undefined)

    await wrapper.get('textarea[aria-label="向智能助手提问"]').setValue('维修申请的处理时限是什么？')
    await wrapper.get('button[aria-label="发送问题"]').trigger('click')

    expect(ask).toHaveBeenCalledWith('维修申请的处理时限是什么？', {
      surface: 'GLOBAL', contextType: 'NONE',
    })
    expect(wrapper.get('[role="dialog"]').text()).toContain('全局')
  })

  it('按当前路由发送受控上下文参数，不提交页面正文或内部工具参数', async () => {
    window.history.replaceState({}, '', '/ai/knowledge')
    const { wrapper, store } = openAssistant()
    const ask = vi.spyOn(store, 'askAssistant').mockResolvedValue(undefined)
    await wrapper.get('textarea[aria-label="向智能助手提问"]').setValue('制度有哪些？')
    await wrapper.get('button[aria-label="发送问题"]').trigger('click')

    expect(ask).toHaveBeenCalledWith('制度有哪些？', {
      surface: 'KNOWLEDGE', contextType: 'KNOWLEDGE',
    })
    expect(JSON.stringify(ask.mock.calls[0])).not.toMatch(/tool|sql|正文/i)
  })

  it('助手打开期间跨路由后使用新页面 surface 和 context 发送', async () => {
    window.history.replaceState({}, '', '/ai/knowledge')
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/ai/knowledge', component: { template: '<div />' } },
        { path: '/ai/approvals', component: { template: '<div />' } },
      ],
    })
    await router.push('/ai/knowledge')
    await router.isReady()
    const { wrapper, store } = openAssistant(router)
    const ask = vi.spyOn(store, 'askAssistant').mockResolvedValue(undefined)

    await router.push('/ai/approvals')
    await wrapper.get('textarea[aria-label="向智能助手提问"]').setValue('审批页有哪些待办？')
    await wrapper.get('button[aria-label="发送问题"]').trigger('click')

    expect(ask).toHaveBeenCalledWith('审批页有哪些待办？', {
      surface: 'APPROVAL',
    })
    expect(wrapper.get('[role="dialog"]').text()).toContain('审批')
  })

  it('引用来源可折叠，拒绝来源不泄露正文', async () => {
    const { wrapper } = openAssistant()
    const toggle = wrapper.get('button[aria-label="收起引用来源"]')
    expect(wrapper.get('[aria-label="引用来源列表"]').text()).toContain('无权限查看此来源')
    const denied = wrapper.get('.citation-row--denied')
    expect(denied.text()).not.toContain('值班交接记录')
    expect(denied.text()).not.toContain('内部资料')
    expect(denied.text()).not.toContain('2026.01')
    expect(wrapper.get('[aria-label="引用来源列表"]').attributes('role')).toBe('region')
    await toggle.trigger('click')
    expect(wrapper.find('[aria-label="引用来源列表"]').exists()).toBe(false)
    expect(wrapper.get('button[aria-label="展开引用来源"]').attributes('aria-expanded')).toBe('false')
  })

  it('赞同与文字反馈调用真实反馈接口，并展示提交状态', async () => {
    const { wrapper } = openAssistant()
    await wrapper.get('button[aria-label="赞同此回答"]').trigger('click')
    await flushPromises()
    expect(client.submitFeedback).toHaveBeenCalledWith('123e4567-e89b-42d3-a456-426614174002', {
      rating: 1, tags: ['helpful'], comment: undefined,
    })

    await wrapper.get('button[aria-label="反馈此回答"]').trigger('click')
    await wrapper.get('textarea[aria-label="反馈说明"]').setValue('引用不足，请人工复核')
    await wrapper.get('button[aria-label="提交反馈"]').trigger('click')
    await flushPromises()
    expect(client.submitFeedback).toHaveBeenLastCalledWith('123e4567-e89b-42d3-a456-426614174002', {
      rating: -1, tags: ['needs-review'], comment: '引用不足，请人工复核',
    })
    expect(wrapper.text()).toContain('反馈已记录')
  })

  it('生成中保留停止生成与关闭确认安全流程', async () => {
    const { wrapper, store } = openAssistant()
    store.runState = 'streaming'
    await flushPromises()
    const stop = vi.spyOn(store, 'stopAssistant')

    expect(wrapper.get('button[aria-label="停止生成"]').attributes('data-assistant-state')).toBe('streaming-stop')
    await wrapper.get('button[aria-label="停止生成"]').trigger('click')
    expect(stop).toHaveBeenCalledOnce()

    store.runState = 'streaming'
    await wrapper.get('button[aria-label="关闭智能助手"]').trigger('click')
    expect(store.closeConfirmationOpen).toBe(true)
    expect(wrapper.get('[role="alertdialog"]').text()).toContain('停止生成并关闭')
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('[role="alertdialog"] button').element)

    const first = wrapper.get('[role="alertdialog"] button')
    ;(first.element as HTMLButtonElement).focus()
    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(wrapper.findAll('[role="alertdialog"] button').at(-1)?.element)

    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(store.closeConfirmationOpen).toBe(false)
    expect(store.assistantOpen).toBe(true)
  })

  it('后段流式态展示部分正文、临时引用和非最终安全护栏', async () => {
    const { wrapper, store } = openAssistant()
    store.runState = 'streaming'
    store.messages.at(-1)!.runState = 'streaming'
    await flushPromises()

    const answer = wrapper.get('[data-assistant-answer]')
    expect(answer.attributes('data-assistant-content-state')).toBe('streaming')
    expect(answer.attributes('data-assistant-partial')).toBe('true')
    expect(answer.attributes('data-assistant-evidence-phase')).toBe('provisional')
    expect(answer.text()).toContain('一般维修应在受理后 2 个工作日内完成')
    expect(answer.findAll('[data-assistant-state="citation-available"]')).not.toHaveLength(0)
    expect(answer.get('[data-assistant-state="streaming-guardrail"]').text())
      .toContain('生成完成前不得作为最终结论')
  })

  it('低置信和无可靠来源使用可追踪状态标识且不伪装成成功依据', async () => {
    const { wrapper, store } = openAssistant()
    const message = store.messages.at(-1)!
    message.confidence = 0.52
    message.grounded = false
    await flushPromises()

    expect(wrapper.get('[data-assistant-state="low-confidence"]').text()).toContain('置信度较低')
    expect(wrapper.get('[data-assistant-state="no-grounded"]').text()).toContain('暂无可靠来源')
    expect(wrapper.get('[data-assistant-answer]').attributes('data-assistant-content-state')).toBe('no-grounded')
  })

  it('失败和超时由消息级状态唯一呈现，不重复显示全局错误', async () => {
    const { wrapper, store } = openAssistant()
    const message = store.messages.at(-1)!
    const failure = '授权资料检索失败，请稍后重试'
    store.runState = 'failed'
    store.assistantError = failure
    message.runState = 'failed'
    message.failureMessage = failure
    await flushPromises()

    expect(wrapper.text().match(new RegExp(failure, 'g'))).toHaveLength(1)
    expect(wrapper.findAll('[data-assistant-state="failed"]')).toHaveLength(1)
    expect(wrapper.find('[data-assistant-state="error"]').exists()).toBe(false)
  })

  it('授权引用使用键盘可达按钮按 ACL 加载纯文本详情，撤权引用保持静态', async () => {
    const { wrapper } = openAssistant()
    const citation = wrapper.get('button[aria-label="查看引用详情：宿舍维修管理办法"]')
    expect(citation.attributes('aria-expanded')).toBe('false')

    await citation.trigger('click')
    await flushPromises()

    expect(client.getCitation).toHaveBeenCalledWith('citation-1')
    expect(wrapper.get('button[aria-label="查看引用详情：宿舍维修管理办法"]').attributes('aria-expanded')).toBe('true')
    expect(wrapper.get('[data-citation-detail="citation-1"]').text()).toContain('某位同学（已脱敏）')
    expect(wrapper.get('[data-citation-detail="citation-1"]').text()).not.toContain('PERSON_NAME')
    expect(wrapper.get('[data-assistant-state="citation-denied"]').find('button').exists()).toBe(false)
  })

  it('越权错误以明确的无权限状态呈现，不冒充普通生成失败', async () => {
    const { wrapper, store } = openAssistant()
    store.assistantError = '当前账号无权限查看该会话'
    await flushPromises()

    const safety = wrapper.get('[data-assistant-state="no-permission"]')
    expect(safety.attributes('aria-live')).toBe('assertive')
    expect(safety.text()).toContain('当前账号无权限')
  })

  it('历史对账态只读展示人工复核护栏，不显示停止生成', async () => {
    const { wrapper, store } = openAssistant()
    store.runState = 'needs_reconciliation' as never
    store.messages.at(-1)!.runState = 'needs_reconciliation' as never
    await flushPromises()

    expect(wrapper.find('button[aria-label="停止生成"]').exists()).toBe(false)
    expect(wrapper.get('[data-assistant-answer]').text()).toContain('需要人工对账')
    expect(wrapper.get('[data-assistant-answer]').text()).toContain('刷新会话或联系管理员复核')
  })

  it('Clipboard API 缺失时复制按钮不误报成功', async () => {
    const { wrapper } = openAssistant()
    const copy = wrapper.get('button[aria-label="复制此回答"]')

    await copy.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(wrapper.get('button[aria-label="复制此回答"]').text()).toBe('复制')
  })

  it('Clipboard 写入被拒绝时复制按钮保持失败状态', async () => {
    const writeText = vi.fn().mockRejectedValue(new DOMException('Not allowed', 'NotAllowedError'))
    Object.defineProperty(window.navigator, 'clipboard', {
      configurable: true, value: { writeText },
    })
    const { wrapper } = openAssistant()
    const copy = wrapper.get('button[aria-label="复制此回答"]')

    await copy.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(writeText).toHaveBeenCalledWith('一般维修应在受理后 2 个工作日内完成。')
    expect(wrapper.get('button[aria-label="复制此回答"]').text()).toBe('复制')
  })

  it('Clipboard 真实写入成功后才显示已复制', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(window.navigator, 'clipboard', {
      configurable: true, value: { writeText },
    })
    const { wrapper } = openAssistant()
    const copy = wrapper.get('button[aria-label="复制此回答"]')

    await copy.trigger('click')
    await flushPromises()
    await wrapper.vm.$nextTick()

    expect(writeText).toHaveBeenCalledWith('一般维修应在受理后 2 个工作日内完成。')
    expect(wrapper.get('button[aria-label="复制此回答"]').text()).toBe('已复制')
  })

  it('内部 PII token 仅以安全占位文案显示和复制，Store 原始值保持不变', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(window.navigator, 'clipboard', {
      configurable: true, value: { writeText },
    })
    const { wrapper, store } = openAssistant()
    const ask = vi.spyOn(store, 'askAssistant').mockResolvedValue(undefined)
    const rawUser = '请查询 [PERSON_NAME:v1:PersonDigest01] 的 [STUDENT_NO:v1:StudentDigest01]'
    const rawAnswer = '已联系 [PHONE:v1:PhoneDigest01]，位置为 [LOCATION:v1:LocationDigest01]。'
    const input = wrapper.get('textarea[aria-label="向智能助手提问"]')
    await input.setValue(rawUser)
    expect((input.element as HTMLTextAreaElement).value).toBe('请查询 某位同学（已脱敏） 的 学号（已脱敏）')
    await wrapper.get('button[aria-label="发送问题"]').trigger('click')
    expect(ask).toHaveBeenCalledWith('请查询 某位同学（已脱敏） 的 学号（已脱敏）', {
      surface: 'GLOBAL', contextType: 'NONE',
    })
    store.messages[0]!.text = rawUser
    store.messages[1]!.text = rawAnswer
    store.conversations[0]!.title = rawUser
    store.assistantError = '无法处理 [NATIONAL_ID:v1:NationalDigest01]'
    await flushPromises()

    const visibleText = wrapper.get('[role="dialog"]').text()
    expect(visibleText).not.toMatch(/\[(?:PERSON_NAME|STUDENT_NO|PHONE|LOCATION|NATIONAL_ID):/)
    expect(visibleText).not.toContain('Digest01')
    expect(visibleText).toContain('某位同学（已脱敏）')
    expect(visibleText).toContain('学号（已脱敏）')
    expect(visibleText).toContain('手机号（已脱敏）')
    expect(visibleText).toContain('位置（已脱敏）')
    expect(visibleText).toContain('身份证号（已脱敏）')

    await wrapper.get('button[aria-label="复制此回答"]').trigger('click')
    await flushPromises()
    expect(writeText).toHaveBeenCalledWith('已联系 手机号（已脱敏），位置为 位置（已脱敏）。')
    expect(store.messages[0]!.text).toBe(rawUser)
    expect(store.messages[1]!.text).toBe(rawAnswer)
  })

  it('不把 Dashboard 等其他能力错误冒充为 Assistant 会话错误', async () => {
    const { wrapper, store } = openAssistant()
    store.error = 'Dashboard 简报加载失败'
    await flushPromises()

    expect(wrapper.find('[data-assistant-state="error"]').exists()).toBe(false)
    expect(wrapper.get('[role="dialog"]').text()).not.toContain('Dashboard 简报加载失败')
  })

  it('统一消费全局语义 Token 且必要文字不使用 10px 或 11px', () => {
    expect(assistantSource).not.toMatch(/--assistant-/)
    expect(assistantSource).not.toMatch(/font-size:\s*(?:10|11)px/)
    expect(assistantSource).toMatch(/width:\s*min\(var\(--ai-drawer-width\),\s*100vw\)/)
    expect(assistantSource).toMatch(/color:\s*var\(--text-title\)/)
    expect(assistantSource).toMatch(/background:\s*var\(--surface\)/)
  })

  it('移动端使用原型式紧凑 Header、居中时间和四项横向操作', () => {
    expect(assistantSource).toMatch(/@media\s*\(max-width:\s*768px\)[\s\S]*?\.assistant-header\s*\{[^}]*min-height:\s*56px;/)
    expect(assistantSource).toMatch(/@media\s*\(max-width:\s*768px\)[\s\S]*?\.user-turn__meta\s*\{[^}]*width:\s*100%;[^}]*justify-content:\s*center;/)
    expect(assistantSource).toMatch(/@media\s*\(max-width:\s*768px\)[\s\S]*?\.user-avatar\s*\{[^}]*display:\s*none;/)
    expect(assistantSource).toMatch(/@media\s*\(max-width:\s*768px\)[\s\S]*?\.assistant-composer\s*\{[^}]*max-height:\s*min\(42dvh,\s*300px\);/)
    expect(assistantSource).toMatch(/@media\s*\(max-width:\s*768px\)[\s\S]*?\.answer-actions button\s*\{[^}]*flex-direction:\s*row;/)
    expect(assistantSource).not.toContain('<span class="assistant-mark" aria-hidden="true">✦</span>')
    expect(assistantSource).toContain('<StarFilled class="assistant-mark__primary" />')
    expect(assistantSource).toContain('<StarOutlined class="assistant-mark__secondary" />')
  })

  it('Escape 关闭后焦点返回触发按钮，Tab 在助手内闭环', async () => {
    const { wrapper, store } = openAssistant()
    const dialog = wrapper.get('[role="dialog"]')
    const firstButton = wrapper.get('button[aria-label="新建会话"]')
    ;(firstButton.element as HTMLButtonElement).focus()
    await dialog.trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).not.toBe(document.body)

    await dialog.trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(store.assistantOpen).toBe(false)
    expect(document.activeElement).toBe(document.querySelector('[data-ai-assistant-trigger]'))
  })

  it('返回本次打开助手的真实触发元素，不被同页其他入口抢走焦点', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const store = useAiStore()
    store.$patch({ assistantOpen: false, sessionRestored: true, runState: 'succeeded' })
    const secondTrigger = document.createElement('button')
    secondTrigger.dataset.aiAssistantTrigger = ''
    secondTrigger.textContent = '当前页助手入口'
    document.body.appendChild(secondTrigger)
    const target = document.createElement('div')
    document.body.appendChild(target)
    const wrapper = mount(AiAssistantHost, { attachTo: target, global: { plugins: [pinia], stubs: { teleport: true } } })
    await flushPromises()

    secondTrigger.focus()
    store.assistantOpen = true
    await flushPromises()
    expect(document.activeElement).toBe(wrapper.get('[role="dialog"]').element)

    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(store.assistantOpen).toBe(false)
    expect(document.activeElement).toBe(secondTrigger)
  })

  it('焦点闭环忽略当前响应式下隐藏的控件', async () => {
    const { wrapper } = openAssistant()
    await flushPromises()
    const dialog = wrapper.get('[role="dialog"]')
    const hiddenBack = wrapper.get('button[aria-label="返回并关闭智能助手"]')
    ;(hiddenBack.element as HTMLButtonElement).hidden = true
    const firstVisible = wrapper.get('button[aria-label="新建会话"]')
    const lastVisible = wrapper.findAll('[aria-label="快捷提问"] button').at(-1)!

    ;(firstVisible.element as HTMLButtonElement).focus()
    await dialog.trigger('keydown', { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(lastVisible.element)

    ;(lastVisible.element as HTMLButtonElement).focus()
    await dialog.trigger('keydown', { key: 'Tab' })
    expect(document.activeElement).toBe(firstVisible.element)
  })

  it('历史会话弹层有完整可访问名称，Escape 收起后返回触发按钮', async () => {
    const { wrapper, store } = openAssistant()
    vi.spyOn(store, 'loadConversations').mockResolvedValue(undefined)
    const historyTrigger = wrapper.get('button[aria-label="查看会话历史"]')

    expect(wrapper.get('[role="dialog"]').attributes('aria-labelledby')).toBe('ai-assistant-title')
    expect(wrapper.get('[aria-label="智能助手会话内容"]').attributes('tabindex')).toBe('0')
    expect(historyTrigger.attributes('aria-controls')).toBe('ai-assistant-history')

    await historyTrigger.trigger('click')
    await flushPromises()
    const history = wrapper.get('#ai-assistant-history')
    expect(history.attributes('role')).toBe('dialog')
    expect(history.attributes('aria-labelledby')).toBe('ai-assistant-history-title')
    expect(document.activeElement).toBe(wrapper.get('button[aria-label="关闭会话历史"]').element)

    await wrapper.get('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    await flushPromises()
    expect(wrapper.find('#ai-assistant-history').exists()).toBe(false)
    expect(document.activeElement).toBe(wrapper.get('button[aria-label="查看会话历史"]').element)
  })

  it('移动端可从会话历史面板开始新会话', async () => {
    const { wrapper, store } = openAssistant()
    vi.spyOn(store, 'loadConversations').mockResolvedValue(undefined)

    await wrapper.get('button[aria-label="查看会话历史"]').trigger('click')
    await flushPromises()
    const history = wrapper.get('#ai-assistant-history')
    await history.get('button[aria-label="新建会话"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('#ai-assistant-history').exists()).toBe(false)
    expect(store.messages).toHaveLength(0)
    expect(store.conversationId).toBe('')
    expect(document.activeElement).toBe(wrapper.get('#ai-assistant-input').element)
  })

  it('移动全屏与长回答保持独立滚动，引用、操作和输入区不互相遮挡', () => {
    expect(assistantSource).toMatch(/\.assistant-messages\s*\{[^}]*min-height:\s*0;[^}]*overflow-y:\s*auto;[^}]*scroll-padding-block-end:/s)
    expect(assistantSource).toMatch(/\.user-turn\s*\{[^}]*min-width:\s*0;/s)
    expect(assistantSource).toMatch(/\.user-turn p\s*\{[^}]*min-width:\s*0;[^}]*max-width:\s*84%;[^}]*overflow-wrap:\s*anywhere;/s)
    expect(assistantSource).toMatch(/\.assistant-composer\s*\{[^}]*position:\s*relative;[^}]*flex:\s*0\s+0\s+auto;[^}]*overflow-y:\s*auto;/s)
    expect(assistantSource).toMatch(/\.quick-prompts\s*\{[^}]*flex-wrap:\s*wrap;/s)
    expect(assistantSource).toMatch(/@media\s*\(max-width:\s*280px\)[\s\S]*?\.answer-meta\s*\{[^}]*grid-template-columns:\s*1fr;/)
    expect(assistantSource).toMatch(/@media\s*\(max-width:\s*768px\)[\s\S]*?\.ai-assistant\s*\{[^}]*height:\s*100dvh;/)
    expect(assistantSource).toMatch(/@media\s*\(max-width:\s*768px\)[\s\S]*?\.header-action, \.header-icon-button\s*\{[^}]*min-width:\s*44px;[^}]*min-height:\s*44px;/)
    expect(assistantSource).toMatch(/@media\s*\(max-width:\s*768px\)[\s\S]*?\.answer-actions button\s*\{[^}]*min-height:\s*44px;/)
    expect(assistantSource).toMatch(/\.answer-card__text\s*\{[^}]*overflow-wrap:\s*anywhere;/s)
    expect(assistantSource).toMatch(/\.citation-row__button, \.citation-row__static\s*\{[^}]*grid-template-columns:\s*32px minmax\(0, 1fr\) 16px;/s)
    expect(assistantSource).toMatch(/@media\s*\(max-width:\s*420px\)[\s\S]*?\.header-action\[aria-label="查看会话历史"\] span\s*\{[^}]*display:\s*inline;/)
  })
})
