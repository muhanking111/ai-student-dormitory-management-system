import { defineStore } from 'pinia'
import { getAiClient } from '../api/ai-client'
import type {
  AiAssistantRun,
  AiCitation,
  AiConversationDetail,
  AiConversationMessage,
  AiConversationSummary,
  AiDashboardInsight,
  AiNoticeDraftInput,
  AiNoticeDraftResult,
  AiRepairTriageInput,
  AiRepairTriageResult,
  AiRunState,
  AiRunEvent,
  AiFeedbackStatus,
} from '../types/ai'

interface AiState {
  assistantOpen: boolean
  closeConfirmationOpen: boolean
  conversationId: string
  conversations: AiConversationSummary[]
  conversationsLoading: boolean
  conversationLoading: boolean
  sessionRestored: boolean
  restoringSession: boolean
  accountSessionEpoch: number
  sessionOperationEpoch: number
  requestEpoch: number
  runState: AiRunState
  activeRunId: string
  runs: AiAssistantRun[]
  messages: AiConversationMessage[]
  feedbackByMessage: Record<string, AiFeedbackStatus>
  dashboardInsight: AiDashboardInsight | null
  dashboardLoading: boolean
  dashboardError: string | null
  repairTriage: Record<number, AiRepairTriageResult>
  noticeDraft: AiNoticeDraftResult | null
  demoProposalMessage: string
  loading: boolean
  loadingCount: number
  assistantError: string | null
  error: string | null
}

let activeController: AbortController | undefined

const conversationListEpochs = new WeakMap<object, number>()
const dashboardEpochs = new WeakMap<object, number>()
const noticeEpochs = new WeakMap<object, number>()
const feedbackEpochs = new WeakMap<object, Map<string, number>>()
const triageEpochs = new WeakMap<object, Map<number, number>>()

function currentEpoch(epochs: WeakMap<object, number>, target: object) {
  return epochs.get(target) ?? 0
}

function nextEpoch(epochs: WeakMap<object, number>, target: object) {
  const next = currentEpoch(epochs, target) + 1
  epochs.set(target, next)
  return next
}

function nextKeyedEpoch<T>(epochs: WeakMap<object, Map<T, number>>, target: object, key: T) {
  const values = epochs.get(target) ?? new Map<T, number>()
  const next = (values.get(key) ?? 0) + 1
  values.set(key, next)
  epochs.set(target, values)
  return next
}

function currentKeyedEpoch<T>(epochs: WeakMap<object, Map<T, number>>, target: object, key: T) {
  return epochs.get(target)?.get(key) ?? 0
}

function beginLoading(state: { loading: boolean; loadingCount: number }) {
  state.loadingCount += 1
  state.loading = true
}

function endLoading(state: { loading: boolean; loadingCount: number }) {
  state.loadingCount = Math.max(0, state.loadingCount - 1)
  state.loading = state.loadingCount > 0
}

const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const persistedInFlightStates: AiRunState[] = ['accepted', 'queued', 'running', 'streaming']

function reconciledPersistedState(state?: AiRunState): AiRunState | undefined {
  return state && persistedInFlightStates.includes(state) ? 'needs_reconciliation' : state
}

function persistedMessages(detail: AiConversationDetail): AiConversationMessage[] {
  return detail.messages.map((message) => {
    const runState = reconciledPersistedState(message.runState)
    return {
      id: message.id,
      role: message.role === 'USER' ? 'user' : 'assistant',
      text: message.text,
      citations: message.citations ?? [],
      classification: message.classification,
      createdAt: message.createdAt,
      ...(typeof message.grounded === 'boolean' ? { grounded: message.grounded } : {}),
      ...(message.asOf ? { asOf: message.asOf } : {}),
      ...(message.runId ? { runId: message.runId } : {}),
      ...(runState ? { runState } : {}),
    }
  })
}

function persistedRunState(messages: AiConversationMessage[]): AiRunState {
  return [...messages].reverse().find((message) => message.role === 'assistant')?.runState ?? 'idle'
}

function safeFailure(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}

function citationFromEvent(payload: Record<string, unknown>): AiCitation | undefined {
  const nested = payload.citation
  if (nested && typeof nested === 'object' && !Array.isArray(nested)) {
    const candidate = nested as Record<string, unknown>
    if (typeof candidate.id !== 'string' || typeof candidate.label !== 'string'
      || typeof candidate.locator !== 'string') return undefined
    const access = candidate.access === 'denied' || candidate.access === 'retired'
      ? candidate.access : 'available'
    return {
      id: candidate.id,
      label: candidate.label,
      locator: candidate.locator,
      version: typeof candidate.version === 'string' ? candidate.version : '',
      access,
    }
  }
  if (typeof payload.citationId !== 'string' || typeof payload.label !== 'string'
    || typeof payload.locator !== 'string') return undefined
  return {
    id: payload.citationId,
    label: payload.label,
    locator: payload.locator,
    version: typeof payload.version === 'string' ? payload.version : '',
    access: 'available',
  }
}

export const useAiStore = defineStore('ai', {
  state: (): AiState => ({
    assistantOpen: false,
    closeConfirmationOpen: false,
    conversationId: '',
    conversations: [],
    conversationsLoading: false,
    conversationLoading: false,
    sessionRestored: false,
    restoringSession: false,
    accountSessionEpoch: 0,
    sessionOperationEpoch: 0,
    requestEpoch: 0,
    runState: 'idle',
    activeRunId: '',
    runs: [],
    messages: [],
    feedbackByMessage: {},
    dashboardInsight: null,
    dashboardLoading: false,
    dashboardError: null,
    repairTriage: {},
    noticeDraft: null,
    demoProposalMessage: '',
    loading: false,
    loadingCount: 0,
    assistantError: null,
    error: null,
  }),
  getters: {
    currentConversation: (state) => state.conversations.find((item) => item.id === state.conversationId) ?? null,
  },
  actions: {
    recordRunEvent(event: AiRunEvent, parentRunId?: string) {
      const existing = this.runs.find((run) => run.id === event.runId)
      const failureCode = typeof event.payload.errorCode === 'string' ? event.payload.errorCode : existing?.failureCode
      const state: AiRunState = event.type === 'run.accepted' ? 'accepted'
        : event.type === 'run.queued' ? 'queued'
          : event.type === 'run.started' ? 'running'
            : event.type === 'message.delta' ? 'streaming'
              : event.type === 'run.degraded' ? 'degraded'
                : event.type === 'run.completed' ? 'succeeded'
                  : event.type === 'run.cancelled' ? 'cancelled'
                    : event.type === 'run.timed_out' || (event.type === 'run.failed' && /TIM(?:E|ED).?OUT/i.test(failureCode ?? ''))
                      ? 'timed_out'
                      : event.type === 'run.failed' ? 'failed' : this.runState
      const next: AiAssistantRun = {
        id: event.runId,
        parentRunId: existing?.parentRunId ?? parentRunId,
        state,
        failureCode,
      }
      this.runs = existing
        ? this.runs.map((run) => run.id === event.runId ? next : run)
        : [...this.runs, next]
      this.runState = state
      this.activeRunId = ['succeeded', 'failed', 'cancelled', 'timed_out'].includes(state)
        ? '' : event.runId
    },
    applyAssistantEvent(event: AiRunEvent, assistant: AiConversationMessage, parentRunId?: string) {
      this.recordRunEvent(event, parentRunId)
      assistant.runId = event.runId
      assistant.parentRunId = parentRunId
      assistant.runState = this.runState
      if (event.type === 'message.delta') assistant.text += String(event.payload.textDelta ?? '')
      if (event.type === 'citation.added') {
        const citation = citationFromEvent(event.payload)
        if (citation && !assistant.citations.some((item) => item.id === citation.id)) assistant.citations.push(citation)
      }
      if (event.type === 'run.completed') {
        const confidence = event.payload.confidence
        if (typeof confidence === 'number' && Number.isFinite(confidence) && confidence >= 0 && confidence <= 1) {
          assistant.confidence = confidence
        }
        if (typeof event.payload.grounded === 'boolean') assistant.grounded = event.payload.grounded
        if (typeof event.payload.asOf === 'string') assistant.asOf = event.payload.asOf
        if (typeof event.payload.messageId === 'string' && uuidPattern.test(event.payload.messageId)) {
          assistant.id = event.payload.messageId
        }
        if (assistant.degradedReason) {
          assistant.runState = 'degraded'
          this.runState = 'degraded'
          this.runs = this.runs.map((run) => run.id === event.runId ? { ...run, state: 'degraded' } : run)
        }
      }
      if (event.type === 'run.degraded') {
        assistant.degradedReason = typeof event.payload.safeMessage === 'string'
          ? event.payload.safeMessage
          : typeof event.payload.reasonCode === 'string' ? event.payload.reasonCode : 'AI 已降级为安全回答'
      }
      if (event.type === 'run.failed' || event.type === 'run.timed_out') {
        assistant.failureMessage = String(event.payload.safeMessage ?? (this.runState === 'timed_out' ? 'AI 生成超时' : 'AI 生成失败'))
        this.assistantError = assistant.failureMessage
      }
    },
    openAssistant() {
      this.assistantOpen = true
      this.closeConfirmationOpen = false
    },
    requestCloseAssistant() {
      if (['accepted', 'queued', 'running', 'streaming'].includes(this.runState)) {
        this.closeConfirmationOpen = true
        return
      }
      this.assistantOpen = false
    },
    confirmStopAndClose() {
      this.stopAssistant()
      this.closeConfirmationOpen = false
      this.assistantOpen = false
    },
    async loadConversations() {
      const client = getAiClient()
      if (!client.listConversations) return
      const accountSessionEpoch = this.accountSessionEpoch
      const requestEpoch = nextEpoch(conversationListEpochs, this)
      this.conversationsLoading = true
      try {
        const result = await client.listConversations({ page: 1, pageSize: 20 })
        if (this.accountSessionEpoch !== accountSessionEpoch
          || currentEpoch(conversationListEpochs, this) !== requestEpoch) return
        this.conversations = result.records
      } catch (error) {
        if (this.accountSessionEpoch !== accountSessionEpoch
          || currentEpoch(conversationListEpochs, this) !== requestEpoch) return
        this.assistantError = safeFailure(error, 'AI 会话列表加载失败')
      } finally {
        if (this.accountSessionEpoch === accountSessionEpoch
          && currentEpoch(conversationListEpochs, this) === requestEpoch) this.conversationsLoading = false
      }
    },
    async selectConversation(id: string) {
      const normalized = id.trim()
      if (!normalized || normalized === this.conversationId && this.messages.length) return
      const client = getAiClient()
      if (!client.getConversation) return
      const operationEpoch = ++this.sessionOperationEpoch
      this.stopAssistant()
      this.conversationLoading = true
      this.restoringSession = false
      this.sessionRestored = true
      this.assistantError = null
      try {
        const detail = await client.getConversation(normalized)
        if (operationEpoch !== this.sessionOperationEpoch) return
        this.conversationId = detail.conversation.id
        this.messages = persistedMessages(detail)
        this.runState = persistedRunState(this.messages)
        this.activeRunId = ''
        const index = this.conversations.findIndex((item) => item.id === detail.conversation.id)
        if (index < 0) this.conversations.unshift(detail.conversation)
        else this.conversations[index] = detail.conversation
      } catch (error) {
        if (operationEpoch === this.sessionOperationEpoch) {
          this.assistantError = safeFailure(error, 'AI 会话切换失败')
        }
      } finally {
        if (operationEpoch === this.sessionOperationEpoch) this.conversationLoading = false
      }
    },
    startNewConversation() {
      this.sessionOperationEpoch++
      this.stopAssistant()
      this.conversationId = ''
      this.messages = []
      this.runs = []
      this.runState = 'idle'
      this.activeRunId = ''
      this.assistantError = null
      this.closeConfirmationOpen = false
      this.conversationLoading = false
      this.restoringSession = false
      this.sessionRestored = true
    },
    async restoreAssistantSession() {
      if (this.sessionRestored || this.restoringSession || this.messages.length) return
      const client = getAiClient()
      if (!client.listConversations || !client.getConversation) {
        this.sessionRestored = true
        return
      }
      const restoreEpoch = this.sessionOperationEpoch
      this.restoringSession = true
      try {
        const page = await client.listConversations({ page: 1, pageSize: 20 })
        if (restoreEpoch !== this.sessionOperationEpoch) return
        this.conversations = page.records
        const latest = page.records.find((conversation) => conversation.status === 'ACTIVE')
        if (!latest) return
        const detail = await client.getConversation(latest.id)
        if (restoreEpoch !== this.sessionOperationEpoch) return
        this.conversationId = detail.conversation.id
        this.messages = persistedMessages(detail)
        this.runState = persistedRunState(this.messages)
        this.activeRunId = ''
      } catch (error) {
        if (restoreEpoch === this.sessionOperationEpoch) {
          this.assistantError = safeFailure(error, 'AI 会话恢复失败')
        }
      } finally {
        if (restoreEpoch === this.sessionOperationEpoch) {
          this.sessionRestored = true
          this.restoringSession = false
        }
      }
    },
    async askAssistant(text: string, context: { surface?: string; contextType?: string; contextId?: number } = {}) {
      const question = text.trim()
      if (!question) return
      if (this.restoringSession) {
        this.sessionOperationEpoch++
        this.restoringSession = false
        this.sessionRestored = true
      }
      this.stopAssistant()
      const requestEpoch = this.requestEpoch
      const controller = new AbortController()
      activeController = controller
      const isCurrentRequest = () => this.requestEpoch === requestEpoch && activeController === controller
      this.assistantError = null
      this.runState = 'accepted'
      const askedAt = new Date().toISOString()
      this.messages.push({ id: `user-${Date.now()}`, role: 'user', text: question, citations: [], createdAt: askedAt })
      this.messages.push({
        id: `assistant-${Date.now()}`, role: 'assistant', text: '', citations: [], createdAt: askedAt,
        runState: 'accepted',
      })
      const assistant = this.messages[this.messages.length - 1]!
      const client = getAiClient()
      if (!client.startConversation) {
        if (isCurrentRequest()) {
          this.runState = 'failed'
          this.assistantError = '当前 AI client 不支持对话'
        }
        if (activeController === controller) activeController = undefined
        return
      }
      try {
        for await (const event of client.startConversation({
          text: question,
          surface: context.surface ?? 'GLOBAL',
          contextType: context.contextType,
          contextId: context.contextId,
          conversationId: this.conversationId || undefined,
          onConversationReady: (id) => {
            if (!isCurrentRequest()) return
            this.conversationId = id
            if (!this.conversations.some((item) => item.id === id)) {
              this.conversations.unshift({
                id,
                surface: context.surface ?? 'GLOBAL',
                contextType: context.contextType ?? 'NONE',
                contextId: context.contextId ?? null,
                status: 'ACTIVE',
                title: question.slice(0, 36),
                lastMessageAt: askedAt,
                createdAt: askedAt,
              })
            }
          },
        }, controller.signal)) {
          if (!isCurrentRequest()) break
          this.applyAssistantEvent(event, assistant)
        }
      } catch (error) {
        if (!isCurrentRequest()) return
        if (error instanceof DOMException && error.name === 'AbortError') {
          this.runState = 'cancelled'
          assistant.runState = 'cancelled'
        }
        else {
          const message = safeFailure(error, 'AI 生成失败')
          this.runState = /TIM(?:E|ED).?OUT|超时/i.test(message) ? 'timed_out' : 'failed'
          assistant.runState = this.runState
          assistant.failureMessage = message
          this.assistantError = message
        }
      } finally {
        if (activeController === controller) activeController = undefined
      }
    },
    async retryAssistant(parentRunId: string) {
      const normalizedParent = parentRunId.trim()
      if (!normalizedParent) return
      this.stopAssistant()
      const requestEpoch = this.requestEpoch
      const controller = new AbortController()
      activeController = controller
      const isCurrentRequest = () => this.requestEpoch === requestEpoch && activeController === controller
      this.assistantError = null
      this.runState = 'accepted'
      this.messages.push({
        id: `assistant-retry-${Date.now()}`, role: 'assistant', text: '', citations: [],
        parentRunId: normalizedParent, runState: 'accepted', createdAt: new Date().toISOString(),
      })
      const assistant = this.messages[this.messages.length - 1]!
      const client = getAiClient()
      if (!client.retryRun) {
        if (isCurrentRequest()) {
          this.runState = 'failed'
          this.assistantError = '当前 AI client 不支持显式重试'
        }
        if (activeController === controller) activeController = undefined
        return
      }
      try {
        for await (const event of client.retryRun(normalizedParent, controller.signal)) {
          if (!isCurrentRequest()) break
          this.applyAssistantEvent(event, assistant, normalizedParent)
        }
      } catch (error) {
        if (!isCurrentRequest()) return
        if (error instanceof DOMException && error.name === 'AbortError') {
          this.runState = 'cancelled'
          assistant.runState = 'cancelled'
        }
        else {
          const message = safeFailure(error, 'AI 重试失败')
          this.runState = /TIM(?:E|ED).?OUT|超时/i.test(message) ? 'timed_out' : 'failed'
          assistant.runState = this.runState
          assistant.failureMessage = message
          this.assistantError = message
        }
      } finally {
        if (activeController === controller) activeController = undefined
      }
    },
    stopAssistant() {
      this.requestEpoch += 1
      const controller = activeController
      activeController = undefined
      controller?.abort()
      if (['accepted', 'queued', 'running', 'streaming'].includes(this.runState)) {
        this.runState = 'cancelled'
        if (this.activeRunId) {
          this.runs = this.runs.map((run) => run.id === this.activeRunId
            ? { ...run, state: 'cancelled' } : run)
          const activeMessage = [...this.messages].reverse().find((message) => message.runId === this.activeRunId)
          if (activeMessage) activeMessage.runState = 'cancelled'
          this.activeRunId = ''
        }
      }
    },
    async submitAssistantFeedback(messageId: string, rating: -1 | 1, tags: string[] = [], comment?: string) {
      const client = getAiClient()
      if (!client.submitFeedback) throw new Error('当前 AI client 不支持回答反馈')
      const accountSessionEpoch = this.accountSessionEpoch
      const requestEpoch = nextKeyedEpoch(feedbackEpochs, this, messageId)
      const normalizedComment = comment?.trim() || undefined
      this.feedbackByMessage[messageId] = { rating, state: 'submitting' }
      try {
        await client.submitFeedback(messageId, { rating, tags, comment: normalizedComment })
        if (this.accountSessionEpoch !== accountSessionEpoch
          || currentKeyedEpoch(feedbackEpochs, this, messageId) !== requestEpoch) return
        this.feedbackByMessage[messageId] = { rating, state: 'succeeded', message: '反馈已记录' }
      } catch (error) {
        if (this.accountSessionEpoch !== accountSessionEpoch
          || currentKeyedEpoch(feedbackEpochs, this, messageId) !== requestEpoch) return
        const message = safeFailure(error, '反馈提交失败')
        this.feedbackByMessage[messageId] = { rating, state: 'failed', message }
        throw error
      }
    },
    async loadDashboard(question = '本周有哪些运营风险？') {
      const client = getAiClient()
      if (!client.queryDashboard) return
      const accountSessionEpoch = this.accountSessionEpoch
      const requestEpoch = nextEpoch(dashboardEpochs, this)
      this.dashboardLoading = true
      this.dashboardError = null
      try {
        const result = await client.queryDashboard({ question })
        if (this.accountSessionEpoch === accountSessionEpoch && currentEpoch(dashboardEpochs, this) === requestEpoch) {
          this.dashboardInsight = result
        }
      } catch (error) {
        if (this.accountSessionEpoch === accountSessionEpoch && currentEpoch(dashboardEpochs, this) === requestEpoch) {
          this.dashboardError = error instanceof Error ? error.message : 'AI 简报加载失败'
        }
      } finally {
        if (this.accountSessionEpoch === accountSessionEpoch && currentEpoch(dashboardEpochs, this) === requestEpoch) {
          this.dashboardLoading = false
        }
      }
    },
    async triage(input: AiRepairTriageInput) {
      const client = getAiClient()
      if (!client.triageRepair) throw new Error('当前 AI client 不支持维修分诊')
      const accountSessionEpoch = this.accountSessionEpoch
      const requestEpoch = nextKeyedEpoch(triageEpochs, this, input.repairId)
      beginLoading(this)
      this.error = null
      try {
        const result = await client.triageRepair(input)
        if (this.accountSessionEpoch === accountSessionEpoch
          && currentKeyedEpoch(triageEpochs, this, input.repairId) === requestEpoch) {
          this.repairTriage[input.repairId] = result
        }
        return result
      } catch (error) {
        if (this.accountSessionEpoch !== accountSessionEpoch
          || currentKeyedEpoch(triageEpochs, this, input.repairId) !== requestEpoch) return
        this.error = safeFailure(error, '维修分诊失败')
        throw error
      } finally {
        if (this.accountSessionEpoch === accountSessionEpoch) endLoading(this)
      }
    },
    async draftNotice(input: AiNoticeDraftInput) {
      const client = getAiClient()
      if (!client.draftNotice) throw new Error('当前 AI client 不支持公告起草')
      const accountSessionEpoch = this.accountSessionEpoch
      const requestEpoch = nextEpoch(noticeEpochs, this)
      beginLoading(this)
      this.error = null
      try {
        const result = await client.draftNotice(input)
        if (this.accountSessionEpoch === accountSessionEpoch && currentEpoch(noticeEpochs, this) === requestEpoch) {
          this.noticeDraft = result
        }
        return result
      } catch (error) {
        if (this.accountSessionEpoch !== accountSessionEpoch
          || currentEpoch(noticeEpochs, this) !== requestEpoch) return
        this.error = safeFailure(error, '公告起草失败')
        throw error
      } finally {
        if (this.accountSessionEpoch === accountSessionEpoch) endLoading(this)
      }
    },
    invalidateNoticeRequests() {
      nextEpoch(noticeEpochs, this)
    },
    submitDemoProposal(kind: 'repair' | 'notice') {
      this.demoProposalMessage = kind === 'repair'
        ? '维修指派建议已进入演示审批队列，未调用原业务写接口'
        : '公告草稿已进入演示审批队列，未写入或发布公告'
    },
    resetSession() {
      const nextAccountSessionEpoch = this.accountSessionEpoch + 1
      const nextSessionOperationEpoch = this.sessionOperationEpoch + 1
      const nextRequestEpoch = this.requestEpoch + 1
      activeController?.abort()
      activeController = undefined
      nextEpoch(conversationListEpochs, this)
      nextEpoch(dashboardEpochs, this)
      nextEpoch(noticeEpochs, this)
      feedbackEpochs.set(this, new Map())
      triageEpochs.set(this, new Map())
      this.$reset()
      this.accountSessionEpoch = nextAccountSessionEpoch
      this.sessionOperationEpoch = nextSessionOperationEpoch
      this.requestEpoch = nextRequestEpoch
    },
  },
})
