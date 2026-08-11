import { createPinia, setActivePinia } from 'pinia'
import { nextTick, watch } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AiClient } from '../api/ai'
import { setAiClient } from '../api/ai-client'
import type { AiConversationDetail, AiDashboardInsight, AiNoticeDraftResult, AiRepairTriageResult } from '../types/ai'
import { useAiStore } from './ai'
import { resetAiStores } from './reset'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((fulfill, fail) => { resolve = fulfill; reject = fail })
  return { promise, resolve, reject }
}

describe('AI session stores', () => {
  beforeEach(() => setActivePinia(createPinia()))

  it('停止生成时 abort，并在会话清理时移除全部正文和引用', async () => {
    let observedSignal: AbortSignal | undefined
    const client: AiClient = {
      async *startConversation(_input, signal) {
        observedSignal = signal
        yield { runId: 'run-1', sequence: 1, type: 'run.started', timestamp: 't', payload: {} }
        await new Promise<void>((resolve) => {
          if (signal?.aborted) resolve()
          else signal?.addEventListener('abort', () => resolve(), { once: true })
        })
      },
    }
    setAiClient(client)
    const store = useAiStore()
    const pending = store.askAssistant('测试问题')
    await Promise.resolve()

    store.stopAssistant()
    expect(observedSignal?.aborted).toBe(true)
    store.messages.push({ id: 'm1', role: 'assistant', text: '敏感上下文', citations: [] })
    resetAiStores()
    expect(store.messages).toEqual([])
    expect(store.dashboardInsight).toBeNull()
    await pending
  })

  it('账号切换清理不会把 AI 状态混入业务 Store', () => {
    const store = useAiStore()
    store.messages.push({ id: 'm1', role: 'user', text: '旧账号问题', citations: [] })
    store.runs.push({ id: 'old-run', state: 'failed' })
    store.resetSession()
    expect(store.$id).toBe('ai')
    expect(store.messages).toEqual([])
    expect(store.runs).toEqual([])
  })

  it('显式重试保留旧 run 和旧回答，只追加带 parentRunId 的 child run', async () => {
    const retryRun = vi.fn(async function* () {
      yield { runId: 'child-run', sequence: 1, type: 'run.queued', timestamp: 't', payload: {} }
      yield { runId: 'child-run', sequence: 2, type: 'message.delta', timestamp: 't', payload: { textDelta: '新回答' } }
      yield { runId: 'child-run', sequence: 3, type: 'run.completed', timestamp: 't', payload: {} }
    })
    setAiClient({ retryRun })
    const store = useAiStore()
    store.runs.push({ id: 'parent-run', state: 'failed', failureCode: 'AI_PROVIDER_UNAVAILABLE' })
    store.messages.push({ id: 'old-answer', role: 'assistant', text: '旧回答', citations: [], runId: 'parent-run' })

    await store.retryAssistant('parent-run')

    expect(retryRun).toHaveBeenCalledWith('parent-run', expect.any(AbortSignal))
    expect(store.runs).toEqual([
      expect.objectContaining({ id: 'parent-run', state: 'failed' }),
      expect.objectContaining({ id: 'child-run', parentRunId: 'parent-run', state: 'succeeded' }),
    ])
    expect(store.messages.map((message) => message.text)).toEqual(['旧回答', '新回答'])
  })

  it('缺少对话 client 时进入失败终态并释放当前请求控制器', async () => {
    setAiClient({})
    const store = useAiStore()

    await store.askAssistant('暂不支持的问题')

    expect(store.runState).toBe('failed')
    expect(store.assistantError).toBe('当前 AI client 不支持对话')
    expect(store.error).toBeNull()
    expect(store.messages.at(-1)).toMatchObject({ role: 'assistant', runState: 'accepted' })
  })

  it('对话流抛出普通错误时保留可读失败信息', async () => {
    setAiClient({
      async *startConversation() {
        yield await Promise.reject(new Error('上游连接失败'))
      },
    })
    const store = useAiStore()

    await store.askAssistant('触发错误')

    expect(store.runState).toBe('failed')
    expect(store.assistantError).toBe('上游连接失败')
    expect(store.error).toBeNull()
    expect(store.messages.at(-1)).toMatchObject({ runState: 'failed', failureMessage: '上游连接失败' })
  })

  it('缺少重试 client 与 AbortError 都映射为受控状态', async () => {
    const store = useAiStore()
    store.runs.push({ id: 'parent-missing', state: 'failed' })
    setAiClient({})

    await store.retryAssistant('parent-missing')
    expect(store.runState).toBe('failed')
    expect(store.assistantError).toBe('当前 AI client 不支持显式重试')
    expect(store.error).toBeNull()

    setAiClient({
      async *retryRun() {
        yield await Promise.reject(new DOMException('aborted', 'AbortError'))
      },
    })
    await store.retryAssistant('parent-aborted')
    expect(store.runState).toBe('cancelled')
    expect(store.messages.at(-1)).toMatchObject({ runState: 'cancelled', parentRunId: 'parent-aborted' })
  })

  it('恢复当前账号最近会话并复用 conversationId，避免刷新页面后丢失上下文', async () => {
    const startConversation = vi.fn(async function* (input) {
      input.onConversationReady?.(input.conversationId ?? 'conversation-new')
      yield { runId: 'run-2', sequence: 1, type: 'run.completed', timestamp: 't', payload: { grounded: false } }
    })
    const client: AiClient = {
      listConversations: vi.fn().mockResolvedValue({ records: [{
        id: 'conversation-1', surface: 'GLOBAL', contextType: 'NONE', status: 'ACTIVE',
        title: '维修时限', createdAt: '2026-07-12T10:00:00Z',
      }], total: 1, page: 1, pageSize: 1 }),
      getConversation: vi.fn().mockResolvedValue({
        conversation: { id: 'conversation-1', surface: 'GLOBAL', contextType: 'NONE', status: 'ACTIVE',
          title: '维修时限', createdAt: '2026-07-12T10:00:00Z' },
        messages: [
          { id: 'message-1', role: 'USER', text: '维修时限？', classification: 'L1', createdAt: '2026-07-12T10:00:00Z' },
          { id: 'message-2', role: 'ASSISTANT', text: '两个工作日。', classification: 'L1', createdAt: '2026-07-12T10:00:01Z' },
        ],
      }),
      startConversation,
    }
    setAiClient(client)
    const store = useAiStore()

    await store.restoreAssistantSession()
    expect(store.conversationId).toBe('conversation-1')
    expect(store.messages.map((message) => message.text)).toEqual(['维修时限？', '两个工作日。'])

    await store.askAssistant('如何升级？')
    expect(startConversation).toHaveBeenCalledWith(expect.objectContaining({
      conversationId: 'conversation-1', text: '如何升级？',
    }), expect.any(AbortSignal))
  })

  it('新建会话使迟到的历史恢复失效，不得重新灌回旧消息', async () => {
    const conversationId = '123e4567-e89b-42d3-a456-426614174041'
    let resolveDetail!: (detail: AiConversationDetail) => void
    const getConversation = vi.fn(() => new Promise<AiConversationDetail>((resolve) => {
      resolveDetail = resolve
    }))
    setAiClient({
      listConversations: vi.fn().mockResolvedValue({
        records: [{
          id: conversationId,
          surface: 'GLOBAL',
          contextType: 'NONE',
          status: 'ACTIVE',
          title: '旧会话',
          createdAt: '2026-07-12T10:00:00Z',
        }],
        total: 1,
        page: 1,
        pageSize: 20,
      }),
      getConversation,
    })
    const store = useAiStore()

    const restoring = store.restoreAssistantSession()
    await vi.waitFor(() => expect(getConversation).toHaveBeenCalledWith(conversationId))
    store.startNewConversation()
    resolveDetail({
      conversation: {
        id: conversationId,
        surface: 'GLOBAL',
        contextType: 'NONE',
        status: 'ACTIVE',
        title: '旧会话',
        createdAt: '2026-07-12T10:00:00Z',
      },
      messages: [{
        id: '123e4567-e89b-42d3-a456-426614174042',
        role: 'ASSISTANT',
        text: '不应回填的旧回答',
        citations: [],
        classification: 'L1',
        createdAt: '2026-07-12T10:00:01Z',
      }],
    })
    await restoring

    expect(store.conversationId).toBe('')
    expect(store.messages).toEqual([])
    expect(store.sessionRestored).toBe(true)
    expect(store.restoringSession).toBe(false)
  })

  it('恢复详情挂起时直接发问使旧恢复失效，并保留新会话的问题与回答', async () => {
    const oldConversationId = '123e4567-e89b-42d3-a456-426614174051'
    const newConversationId = '123e4567-e89b-42d3-a456-426614174052'
    let resolveDetail!: (detail: AiConversationDetail) => void
    const getConversation = vi.fn(() => new Promise<AiConversationDetail>((resolve) => {
      resolveDetail = resolve
    }))
    const startConversation = vi.fn(async function* (input) {
      input.onConversationReady?.(newConversationId)
      yield {
        runId: 'run-new-question',
        sequence: 1,
        type: 'message.delta' as const,
        timestamp: 't',
        payload: { textDelta: '新回答' },
      }
      yield {
        runId: 'run-new-question',
        sequence: 2,
        type: 'run.completed' as const,
        timestamp: 't',
        payload: {},
      }
    })
    setAiClient({
      listConversations: vi.fn().mockResolvedValue({
        records: [{
          id: oldConversationId,
          surface: 'GLOBAL',
          contextType: 'NONE',
          status: 'ACTIVE',
          title: '旧会话',
          createdAt: '2026-07-12T10:00:00Z',
        }],
        total: 1,
        page: 1,
        pageSize: 20,
      }),
      getConversation,
      startConversation,
    })
    const store = useAiStore()

    const restoring = store.restoreAssistantSession()
    await vi.waitFor(() => expect(getConversation).toHaveBeenCalledWith(oldConversationId))
    await store.askAssistant('新问题')
    const restoringAfterAsk = store.restoringSession
    resolveDetail({
      conversation: {
        id: oldConversationId,
        surface: 'GLOBAL',
        contextType: 'NONE',
        status: 'ACTIVE',
        title: '旧会话',
        createdAt: '2026-07-12T10:00:00Z',
      },
      messages: [{
        id: '123e4567-e89b-42d3-a456-426614174053',
        role: 'ASSISTANT',
        text: '不应覆盖新回答的旧内容',
        citations: [],
        classification: 'L1',
        createdAt: '2026-07-12T10:00:01Z',
      }],
    })
    await restoring

    expect(startConversation).toHaveBeenCalledWith(expect.objectContaining({
      text: '新问题',
      conversationId: undefined,
    }), expect.any(AbortSignal))
    expect(restoringAfterAsk).toBe(false)
    expect(store.conversationId).toBe(newConversationId)
    expect(store.messages.map((message) => message.text)).toEqual(['新问题', '新回答'])
    expect(store.sessionRestored).toBe(true)
    expect(store.restoringSession).toBe(false)
  })

  it('新请求开始后忽略迟到的旧 SSE 事件，不污染当前会话状态', async () => {
    let releaseOld!: () => void
    let releaseNew!: () => void
    let newSignal: AbortSignal | undefined
    const oldReleased = new Promise<void>((resolve) => { releaseOld = resolve })
    const newReleased = new Promise<void>((resolve) => { releaseNew = resolve })
    const startConversation = vi.fn((input: { text: string; onConversationReady?: (id: string) => void }, signal?: AbortSignal) => input.text === '旧问题'
      ? (async function* () {
        yield { runId: 'old-run', sequence: 1, type: 'run.started' as const, timestamp: 't', payload: {} }
        await oldReleased
        // 模拟 abort 后 transport 仍交付的迟到事件。
        input.onConversationReady?.('old-conversation')
        yield { runId: 'old-run', sequence: 2, type: 'message.delta' as const, timestamp: 't', payload: { textDelta: '旧回答不应出现' } }
        yield { runId: 'old-run', sequence: 3, type: 'run.completed' as const, timestamp: 't', payload: {} }
      })()
      : (async function* () {
        newSignal = signal
        input.onConversationReady?.('new-conversation')
        yield { runId: 'new-run', sequence: 1, type: 'message.delta' as const, timestamp: 't', payload: { textDelta: '新回答' } }
        await newReleased
        yield { runId: 'new-run', sequence: 2, type: 'run.completed' as const, timestamp: 't', payload: {} }
      })())
    setAiClient({ startConversation })
    const store = useAiStore()

    const oldRequest = store.askAssistant('旧问题')
    await vi.waitFor(() => expect(store.runs).toEqual([expect.objectContaining({ id: 'old-run', state: 'running' })]))

    store.startNewConversation()
    const newRequest = store.askAssistant('新问题')
    await vi.waitFor(() => expect(store.messages.at(-1)?.text).toBe('新回答'))
    releaseOld()
    await oldRequest

    expect(store.conversationId).toBe('new-conversation')
    store.stopAssistant()
    expect(newSignal?.aborted).toBe(true)
    releaseNew()
    await newRequest

    expect(store.messages.map((message) => message.text)).toEqual(['新问题', '新回答'])
    expect(store.runs).toEqual([expect.objectContaining({ id: 'new-run', state: 'cancelled' })])
    expect(store.runState).toBe('cancelled')
  })

  it('重试请求被新请求取代后忽略迟到的旧 SSE 事件', async () => {
    let releaseRetry!: () => void
    const retryReleased = new Promise<void>((resolve) => { releaseRetry = resolve })
    const retryRun = vi.fn(async function* () {
      yield { runId: 'retry-run', sequence: 1, type: 'run.started' as const, timestamp: 't', payload: {} }
      await retryReleased
      yield { runId: 'retry-run', sequence: 2, type: 'message.delta' as const, timestamp: 't', payload: { textDelta: '迟到重试内容' } }
      yield { runId: 'retry-run', sequence: 3, type: 'run.completed' as const, timestamp: 't', payload: {} }
    })
    const startConversation = vi.fn(async function* () {
      yield { runId: 'replacement-run', sequence: 1, type: 'message.delta' as const, timestamp: 't', payload: { textDelta: '替代回答' } }
      yield { runId: 'replacement-run', sequence: 2, type: 'run.completed' as const, timestamp: 't', payload: {} }
    })
    setAiClient({ retryRun, startConversation })
    const store = useAiStore()
    store.runs.push({ id: 'parent-run', state: 'failed' })
    store.messages.push({ id: 'old-answer', role: 'assistant', text: '旧回答', citations: [], runId: 'parent-run' })

    const retryRequest = store.retryAssistant('parent-run')
    await vi.waitFor(() => expect(store.runs).toEqual([expect.objectContaining({ id: 'parent-run', state: 'failed' }), expect.objectContaining({ id: 'retry-run', state: 'running' })]))

    const replacementRequest = store.askAssistant('替代问题')
    await replacementRequest
    releaseRetry()
    await retryRequest

    expect(store.messages.map((message) => message.text)).toEqual(['旧回答', '', '替代问题', '替代回答'])
    expect(store.messages.find((message) => message.parentRunId === 'parent-run')?.text).toBe('')
    expect(store.runs).toEqual([
      expect.objectContaining({ id: 'parent-run', state: 'failed' }),
      expect.objectContaining({ id: 'retry-run', state: 'cancelled' }),
      expect.objectContaining({ id: 'replacement-run', state: 'succeeded' }),
    ])
  })

  it.each(['accepted', 'queued', 'running', 'streaming'] as const)(
    '恢复或切换历史 %s 状态时降为只读对账态，不伪造可停止的 live run',
    async (persistedState) => {
      const firstId = '123e4567-e89b-42d3-a456-426614174031'
      const secondId = '123e4567-e89b-42d3-a456-426614174032'
      const detail = (id: string) => ({
        conversation: {
          id, surface: 'GLOBAL', contextType: 'NONE', status: 'ACTIVE',
          title: '待对账会话', createdAt: '2026-07-12T10:00:00Z',
        },
        messages: [{
          id: '123e4567-e89b-42d3-a456-426614174033', role: 'ASSISTANT' as const,
          text: '历史生成尚未确认终态', classification: 'L1', createdAt: '2026-07-12T10:00:01Z',
          runId: '123e4567-e89b-42d3-a456-426614174034', runState: persistedState,
          citations: [],
        }],
      })
      const getConversation = vi.fn(async (id: string) => detail(id))
      setAiClient({
        listConversations: vi.fn().mockResolvedValue({
          records: [detail(firstId).conversation], total: 1, page: 1, pageSize: 20,
        }),
        getConversation,
      })
      const store = useAiStore()

      await store.restoreAssistantSession()
      expect(store.runState).toBe('needs_reconciliation')
      expect(store.messages.at(-1)?.runState).toBe('needs_reconciliation')
      expect(store.activeRunId).toBe('')

      store.startNewConversation()
      await store.selectConversation(secondId)
      expect(store.runState).toBe('needs_reconciliation')
      expect(store.messages.at(-1)?.runState).toBe('needs_reconciliation')
      expect(store.activeRunId).toBe('')
    },
  )

  it('完成事件用服务端 messageId 替换临时 ID，并以该 ID 提交真实反馈', async () => {
    const messageId = '123e4567-e89b-42d3-a456-426614174002'
    const submitFeedback = vi.fn().mockResolvedValue(undefined)
    setAiClient({
      async *startConversation(input) {
        input.onConversationReady?.('123e4567-e89b-42d3-a456-426614174001')
        yield { runId: 'run-1', sequence: 1, type: 'message.delta', timestamp: 't', payload: { textDelta: '有依据的回答' } }
        yield { runId: 'run-1', sequence: 2, type: 'run.completed', timestamp: 't', payload: {
          messageId, grounded: true, confidence: 0.86, asOf: '2026-07-12T10:00:00Z',
        } }
      },
      submitFeedback,
    })
    const store = useAiStore()

    await store.askAssistant('维修时限？')
    const answer = store.messages.at(-1)!
    expect(answer).toMatchObject({ id: messageId, text: '有依据的回答', grounded: true, confidence: 0.86 })

    await store.submitAssistantFeedback(answer.id, 1, ['helpful'])
    expect(submitFeedback).toHaveBeenCalledWith(messageId, { rating: 1, tags: ['helpful'], comment: undefined })
    expect(store.feedbackByMessage[messageId]).toMatchObject({ rating: 1, state: 'succeeded' })
  })

  it('citation 事件在终态到达前立即触发消息引用的响应式更新', async () => {
    const citationObserved = deferred<void>()
    const releaseTerminal = deferred<void>()
    setAiClient({
      async *startConversation() {
        yield {
          runId: 'run-streaming-citation', sequence: 1, type: 'message.delta', timestamp: 't',
          payload: { textDelta: '维修处理时限以授权制度为准。' },
        }
        yield {
          runId: 'run-streaming-citation', sequence: 2, type: 'citation.added', timestamp: 't',
          payload: {
            citationId: '123e4567-e89b-42d3-a456-426614174031',
            label: '宿舍维修管理制度',
            locator: '段落 1',
            rank: 1,
          },
        }
        citationObserved.resolve()
        await releaseTerminal.promise
        yield {
          runId: 'run-streaming-citation', sequence: 3, type: 'run.completed', timestamp: 't',
          payload: { messageId: '123e4567-e89b-42d3-a456-426614174032', grounded: true },
        }
      },
    })
    const store = useAiStore()
    const observedCitationCounts: number[] = []
    const stopWatching = watch(
      () => store.messages.at(-1)?.citations.length ?? 0,
      (count) => observedCitationCounts.push(count),
      { flush: 'sync' },
    )
    const pending = store.askAssistant('维修多久处理？')

    try {
      await citationObserved.promise
      await nextTick()
      expect(store.runState).toBe('streaming')
      expect(observedCitationCounts).toContain(1)
    } finally {
      releaseTerminal.resolve()
      await pending
      stopWatching()
    }
  })

  it('降级、超时与取消保留安全终态，后端未给置信度时不在前端伪造', async () => {
    const completedId = '123e4567-e89b-42d3-a456-426614174021'
    setAiClient({
      async *startConversation() {
        yield { runId: 'run-degraded', sequence: 1, type: 'run.degraded', timestamp: 't', payload: { reasonCode: 'AI_PROVIDER_FALLBACK' } }
        yield { runId: 'run-degraded', sequence: 2, type: 'message.delta', timestamp: 't', payload: { textDelta: '确定性降级回答' } }
        yield { runId: 'run-degraded', sequence: 3, type: 'run.completed', timestamp: 't', payload: { messageId: completedId, grounded: true } }
      },
    })
    const store = useAiStore()
    await store.askAssistant('降级问题')
    expect(store.messages.at(-1)).toMatchObject({
      id: completedId, runState: 'degraded', degradedReason: 'AI_PROVIDER_FALLBACK', grounded: true,
    })
    expect(store.messages.at(-1)?.confidence).toBeUndefined()

    setAiClient({
      async *startConversation() {
        yield { runId: 'run-timeout', sequence: 1, type: 'run.failed', timestamp: 't', payload: {
          errorCode: 'AI_RUN_TIMED_OUT', safeMessage: '生成超时，请稍后重试',
        } }
      },
    })
    await store.askAssistant('超时问题')
    expect(store.messages.at(-1)).toMatchObject({ runState: 'timed_out', failureMessage: '生成超时，请稍后重试' })

    setAiClient({
      async *startConversation() {
        yield { runId: 'run-cancelled', sequence: 1, type: 'run.cancelled', timestamp: 't', payload: {} }
      },
    })
    await store.askAssistant('取消问题')
    expect(store.messages.at(-1)?.runState).toBe('cancelled')
  })

  it('加载完整会话列表并可切换历史会话，服务端消息 ID 在恢复后保持不变', async () => {
    const firstId = '123e4567-e89b-42d3-a456-426614174011'
    const secondId = '123e4567-e89b-42d3-a456-426614174012'
    const assistantId = '123e4567-e89b-42d3-a456-426614174013'
    const listConversations = vi.fn().mockResolvedValue({ records: [
      { id: firstId, surface: 'GLOBAL', contextType: 'NONE', status: 'ACTIVE', title: '维修时限', createdAt: '2026-07-12T10:00:00Z' },
      { id: secondId, surface: 'DASHBOARD', contextType: 'DASHBOARD', status: 'ACTIVE', title: '入住率', createdAt: '2026-07-11T10:00:00Z' },
    ], total: 2, page: 1, pageSize: 20 })
    const getConversation = vi.fn().mockImplementation(async (id: string) => ({
      conversation: { id, surface: id === secondId ? 'DASHBOARD' : 'GLOBAL', contextType: id === secondId ? 'DASHBOARD' : 'NONE', status: 'ACTIVE', title: id === secondId ? '入住率' : '维修时限', createdAt: '2026-07-12T10:00:00Z' },
      messages: [{
        id: assistantId,
        role: 'ASSISTANT',
        text: id === secondId ? '当前入住率为 75%。' : '两个工作日。',
        classification: 'L1',
        createdAt: '2026-07-12T10:00:01Z',
        citations: [{ id: 'citation-1', label: '入住汇总', locator: 'dashboard', version: 'v1', access: 'available' }],
        grounded: true,
        asOf: '2026-07-12T09:59:00Z',
        runId: '123e4567-e89b-42d3-a456-426614174014',
        runState: 'succeeded',
      }],
    }))
    setAiClient({ listConversations, getConversation })
    const store = useAiStore()

    await store.loadConversations()
    await store.selectConversation(secondId)

    expect(listConversations).toHaveBeenCalledWith({ page: 1, pageSize: 20 })
    expect(getConversation).toHaveBeenCalledWith(secondId)
    expect(store.conversationId).toBe(secondId)
    expect(store.messages).toEqual([expect.objectContaining({
      id: assistantId,
      text: '当前入住率为 75%。',
      grounded: true,
      asOf: '2026-07-12T09:59:00Z',
      runId: '123e4567-e89b-42d3-a456-426614174014',
      runState: 'succeeded',
      citations: [expect.objectContaining({ id: 'citation-1' })],
    })])
    expect(store.runState).toBe('succeeded')
    expect(store.currentConversation).toMatchObject({ id: secondId, surface: 'DASHBOARD' })
  })

  it('新会话只清理助手会话，不清除 Dashboard 等独立 AI 结果', () => {
    const store = useAiStore()
    store.conversationId = 'conversation-old'
    store.messages.push({ id: 'old', role: 'user', text: '旧问题', citations: [] })
    store.dashboardInsight = { id: 'dashboard', summary: '保留', metricVersion: 'v1', riskCounts: [],
      pendingApprovals: 0, evidence: { asOf: 't', citations: [], grounded: true }, state: 'succeeded',
      intent: { metricIds: ['repair.pending.count'], dateRange: { preset: 'TODAY' }, dimensions: [], filters: {}, presentationHint: 'CARD' },
      metrics: {}, queryParameters: { dateRange: { preset: 'TODAY', from: 't', to: 't' }, dimensions: [], filters: {} } }

    store.startNewConversation()

    expect(store.conversationId).toBe('')
    expect(store.messages).toEqual([])
    expect(store.dashboardInsight?.summary).toBe('保留')
  })

  it('账号会话重置后，迟到的列表、Dashboard、分诊、公告和反馈响应不得回灌', async () => {
    const conversations = deferred<{ records: never[]; total: number; page: number; pageSize: number }>()
    const dashboard = deferred<AiDashboardInsight>()
    const triage = deferred<AiRepairTriageResult>()
    const notice = deferred<AiNoticeDraftResult>()
    const feedback = deferred<void>()
    setAiClient({
      listConversations: vi.fn().mockReturnValue(conversations.promise),
      queryDashboard: vi.fn().mockReturnValue(dashboard.promise),
      triageRepair: vi.fn().mockReturnValue(triage.promise),
      draftNotice: vi.fn().mockReturnValue(notice.promise),
      submitFeedback: vi.fn().mockReturnValue(feedback.promise),
    })
    const store = useAiStore()
    store.feedbackByMessage['old-message'] = { rating: 1, state: 'succeeded' }

    const list = store.loadConversations()
    const dashboardRequest = store.loadDashboard('旧账号简报')
    const triageRequest = store.triage({ repairId: 9, status: '待处理', descriptionRedacted: '旧上下文', candidates: [] })
    const noticeRequest = store.draftNotice({ points: '旧公告', type: '通知', tone: '正式', audience: '全体' })
    const feedbackRequest = store.submitAssistantFeedback('old-message', -1)
    store.resetSession()

    conversations.resolve({ records: [], total: 0, page: 1, pageSize: 20 })
    dashboard.resolve({ id: 'old-dashboard' } as AiDashboardInsight)
    triage.resolve({ repairId: 9 } as AiRepairTriageResult)
    notice.resolve({ title: '旧公告' } as AiNoticeDraftResult)
    feedback.resolve()
    await Promise.all([list, dashboardRequest, triageRequest, noticeRequest, feedbackRequest])

    expect(store.conversations).toEqual([])
    expect(store.dashboardInsight).toBeNull()
    expect(store.repairTriage).toEqual({})
    expect(store.noticeDraft).toBeNull()
    expect(store.feedbackByMessage).toEqual({})
    expect(store.loading).toBe(false)
  })
})
