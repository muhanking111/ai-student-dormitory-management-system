<template>
  <Teleport to="body">
    <aside
      v-if="enabled && store.assistantOpen"
      ref="dialogRef"
      class="ai-assistant"
      role="dialog"
      aria-modal="true"
      aria-labelledby="ai-assistant-title"
      tabindex="-1"
      @keydown="handleKeydown"
    >
      <header class="assistant-header">
        <button type="button" class="header-icon-button mobile-back" aria-label="返回并关闭智能助手" @click="store.requestCloseAssistant">
          <ArrowLeftOutlined />
        </button>
        <div class="assistant-title">
          <div><h2 id="ai-assistant-title">智能助手</h2><span class="scope-chip">{{ scopeLabel }}</span></div>
          <p>{{ scopeDescription }}</p>
        </div>
        <nav class="assistant-header__actions" aria-label="会话操作">
          <button type="button" class="header-action new-session" aria-label="新建会话" @click="startNewConversation">
            <PlusOutlined /><span>新会话</span>
          </button>
          <button
            ref="historyToggleRef"
            type="button"
            class="header-action"
            aria-label="查看会话历史"
            aria-controls="ai-assistant-history"
            aria-haspopup="dialog"
            :aria-expanded="historyOpen"
            @click="toggleHistory"
          >
            <HistoryOutlined /><span>历史会话</span>
          </button>
          <button type="button" class="header-icon-button" aria-label="关闭智能助手" @click="store.requestCloseAssistant">
            <CloseOutlined />
          </button>
        </nav>
      </header>

      <section
        v-if="historyOpen"
        id="ai-assistant-history"
        ref="historyPanelRef"
        class="history-panel"
        role="dialog"
        aria-label="会话历史"
        aria-labelledby="ai-assistant-history-title"
        tabindex="-1"
      >
        <header>
          <strong id="ai-assistant-history-title">会话历史</strong>
          <div class="history-panel__actions">
            <button type="button" aria-label="新建会话" @click="startNewConversation"><PlusOutlined /></button>
            <button type="button" aria-label="关闭会话历史" @click="closeHistory"><CloseOutlined /></button>
          </div>
        </header>
        <p v-if="store.conversationsLoading">正在加载会话…</p>
        <ul v-else-if="store.conversations.length">
          <li v-for="item in store.conversations" :key="item.id" :class="{ active: item.id === store.conversationId }">
            <button
              type="button"
              :aria-label="`切换到会话：${conversationTitle(item)}`"
              :aria-current="item.id === store.conversationId ? 'true' : undefined"
              @click="switchConversation(item.id)"
            >
              <HistoryOutlined />
              <span><strong>{{ conversationTitle(item) }}</strong><small>{{ conversationScope(item) }}</small></span>
            </button>
          </li>
        </ul>
        <p v-else>暂无历史会话</p>
      </section>

      <main class="assistant-messages" aria-label="智能助手会话内容" tabindex="0">
        <section v-if="store.messages.length === 0" class="assistant-empty">
          <RobotOutlined />
          <h3>有什么可以帮你？</h3>
          <p>可以询问维修时限、入住口径或已发布公告。</p>
          <small><LockOutlined /> 仅检索当前账号已授权资料</small>
        </section>

        <template v-for="message in store.messages" :key="message.id">
          <article
            v-if="message.role === 'user'"
            class="user-turn"
            data-assistant-user-message
            :data-assistant-message-id="message.id"
            data-assistant-content-state="user"
          >
            <div class="user-turn__meta">
              <time>{{ formatMessageTime(message.createdAt) }}</time>
              <span class="user-avatar" aria-hidden="true"><UserOutlined /></span>
            </div>
            <p>{{ safeDisplayText(message.text) }}</p>
          </article>

          <article
            v-else
            class="assistant-turn"
            data-assistant-answer
            :data-assistant-message-id="message.id"
            :data-assistant-run-id="message.runId || undefined"
            :data-assistant-run-state="messageState(message)"
            :data-assistant-content-state="messageContentState(message)"
            :data-assistant-partial="isPartialStreaming(message) ? 'true' : undefined"
            :data-assistant-evidence-phase="messageEvidencePhase(message)"
          >
            <div class="assistant-progress">
              <span class="assistant-mark" aria-hidden="true">
                <StarFilled class="assistant-mark__primary" />
                <StarOutlined class="assistant-mark__secondary" />
              </span>
              <AiRunStatus v-if="message.id === lastAssistantMessage?.id" :state="store.runState" />
              <span v-else>{{ statusLabel(message.runState) }}</span>
              <button
                v-if="isGenerating && message.id === lastAssistantMessage?.id"
                type="button"
                class="stop-button"
                data-assistant-state="streaming-stop"
                aria-label="停止生成"
                @click="store.stopAssistant"
              ><StopOutlined /> 停止生成</button>
            </div>

            <div class="answer-card">
              <p class="answer-card__text"><span class="sr-only">AI 助手回答：</span>{{ safeDisplayText(message.text) || '正在检索授权资料…' }}</p>
              <div v-if="message.grounded !== undefined && !isInFlightMessage(message)" class="answer-meta" aria-label="回答依据摘要">
                <span data-answer-meta><SafetyCertificateOutlined />{{ confidenceLabel(message.confidence) }}</span>
                <span data-answer-meta><FileSearchOutlined />引用来源 {{ availableCitationCount(message) }}</span>
                <span data-answer-meta><HistoryOutlined />数据截至 {{ formatAsOf(message.asOf) }}</span>
              </div>
            </div>

            <section v-if="message.citations.length" class="citation-card">
              <header>
                <button
                  type="button"
                  :aria-label="citationsExpanded(message.id) ? '收起引用来源' : '展开引用来源'"
                  :aria-expanded="citationsExpanded(message.id)"
                  @click="toggleCitations(message.id)"
                >
                  <span><strong>引用来源</strong><small>{{ message.citations.length }} 项</small></span>
                  <UpOutlined v-if="citationsExpanded(message.id)" />
                  <DownOutlined v-else />
                </button>
              </header>
              <ul v-if="citationsExpanded(message.id)" role="region" aria-label="引用来源列表">
                <li
                  v-for="citation in message.citations"
                  :key="citation.id"
                  :class="{ 'citation-row--denied': citation.access !== 'available' }"
                  :data-assistant-state="citation.access !== 'available' ? 'citation-denied' : 'citation-available'"
                >
                  <button
                    v-if="citation.access === 'available'"
                    type="button"
                    class="citation-row__button"
                    :aria-label="`查看引用详情：${safeDisplayText(citation.label)}`"
                    :aria-expanded="citationDetailExpanded(citation.id)"
                    :aria-controls="`citation-detail-${citation.id}`"
                    @click="toggleCitationDetail(citation.id)"
                  >
                    <span class="citation-row__icon" aria-hidden="true">
                      <BookOutlined v-if="citation.label.includes('手册')" />
                      <FileTextOutlined v-else />
                    </span>
                    <span class="citation-row__copy">
                      <strong>{{ safeDisplayText(citation.label) }}</strong>
                      <small>{{ safeDisplayText(citation.locator) }}<template v-if="citation.version"> · {{ safeDisplayText(citation.version) }}</template></small>
                    </span>
                    <DownOutlined v-if="citationDetailExpanded(citation.id)" aria-hidden="true" />
                    <ArrowRightOutlined v-else aria-hidden="true" />
                  </button>
                  <div v-else class="citation-row__static">
                    <span class="citation-row__icon" aria-hidden="true"><LockOutlined /></span>
                    <span class="citation-row__copy">
                      <strong>{{ citation.access === 'retired' ? '来源已退役' : '无权限查看此来源' }}</strong>
                      <small>{{ citation.access === 'retired' ? '历史正文不可查看' : '当前账号无权查看详情' }}</small>
                    </span>
                  </div>
                  <div
                    v-if="citation.access === 'available' && citationDetailExpanded(citation.id)"
                    :id="`citation-detail-${citation.id}`"
                    class="citation-detail"
                    :data-citation-detail="citation.id"
                    role="region"
                    :aria-label="`引用详情：${safeDisplayText(citation.label)}`"
                  >
                    <p v-if="citationDetailLoading(citation.id)">正在核验引用权限…</p>
                    <p v-else-if="citationDetailError(citation.id)" role="alert">{{ citationDetailError(citation.id) }}</p>
                    <template v-else-if="citationDetail(citation.id)">
                      <p>{{ safeDisplayText(citationDetail(citation.id)?.quote) }}</p>
                      <small>{{ safeDisplayText(citationDetail(citation.id)?.locator) }}</small>
                    </template>
                  </div>
                </li>
              </ul>
            </section>

            <div class="answer-actions" aria-label="回答操作">
              <button type="button" aria-label="重试此回答" :disabled="!message.runId || isGenerating" @click="retry(message.runId)"><ReloadOutlined />重试</button>
              <button type="button" aria-label="复制此回答" :disabled="!message.text" @click="copyAnswer(message.id, message.text)"><CopyOutlined />{{ copiedMessageId === message.id ? '已复制' : '复制' }}</button>
              <button
                type="button"
                aria-label="赞同此回答"
                :disabled="!isServerMessageId(message.id) || feedbackSubmitting(message.id)"
                :class="{ active: store.feedbackByMessage[message.id]?.rating === 1 }"
                @click="likeAnswer(message.id)"
              ><LikeOutlined />赞同</button>
              <button
                type="button"
                aria-label="反馈此回答"
                :disabled="!isServerMessageId(message.id) || feedbackSubmitting(message.id)"
                :class="{ active: feedbackMessageId === message.id || store.feedbackByMessage[message.id]?.rating === -1 }"
                @click="openFeedback(message.id)"
              ><MessageOutlined />反馈</button>
            </div>
            <form v-if="feedbackMessageId === message.id" class="feedback-form" @submit.prevent="submitFeedback(message.id)">
              <label :for="`assistant-feedback-${message.id}`">反馈说明</label>
              <textarea
                :id="`assistant-feedback-${message.id}`"
                v-model="feedbackComment"
                aria-label="反馈说明"
                maxlength="500"
                rows="3"
                placeholder="请说明需要复核的内容"
              />
              <div><button type="button" @click="closeFeedback">取消</button><button type="button" aria-label="提交反馈" :disabled="!feedbackComment.trim()" @click="submitFeedback(message.id)">提交反馈</button></div>
            </form>
            <p
              v-if="store.feedbackByMessage[message.id]?.message"
              class="feedback-status"
              role="status"
            >{{ safeDisplayText(store.feedbackByMessage[message.id]?.message) }}</p>
            <div class="message-guardrails">
              <AiSafetyState
                v-if="isPartialStreaming(message) && availableCitationCount(message) > 0"
                data-assistant-state="streaming-guardrail"
                message="生成完成前不得作为最终结论，正文与引用仍在核验"
                tone="info"
              />
              <AiSafetyState v-if="message.degradedReason" data-assistant-state="degraded" :message="safeDisplayText(message.degradedReason)" tone="warning" />
              <AiSafetyState v-if="messageState(message) === 'cancelled'" data-assistant-state="cancelled" message="生成已停止，已保留当前内容" tone="info" />
              <AiSafetyState v-if="messageState(message) === 'timed_out'" data-assistant-state="timed-out" :message="safeDisplayText(message.failureMessage) || '生成超时，可重试本次请求'" tone="danger" />
              <AiSafetyState v-else-if="messageState(message) === 'failed'" data-assistant-state="failed" :message="safeDisplayText(message.failureMessage) || 'AI 生成失败'" tone="danger" />
              <AiSafetyState v-if="messageState(message) === 'needs_reconciliation'" data-assistant-state="needs-reconciliation" message="历史运行状态需要人工对账，请刷新会话或联系管理员复核" tone="warning" />
              <AiSafetyState v-if="!isInFlightMessage(message) && typeof message.confidence === 'number' && message.confidence < 0.65" data-assistant-state="low-confidence" message="置信度较低，请人工核验" tone="warning" />
              <AiSafetyState v-if="!isInFlightMessage(message) && message.grounded === false" data-assistant-state="no-grounded" message="暂无可靠来源，无法确认" tone="info" />
            </div>
          </article>
        </template>

        <AiSafetyState
          v-if="unscopedAssistantError"
          :data-assistant-state="isPermissionError(unscopedAssistantError) ? 'no-permission' : 'error'"
          :state="isPermissionError(unscopedAssistantError) ? 'NO_PERMISSION' : undefined"
          :message="unscopedAssistantError"
          tone="danger"
        />
        <AiSafetyState v-if="lastAssistantMessage?.grounded === true && !isInFlightMessage(lastAssistantMessage)" message="关键结论必须有可靠来源，否则不执行" tone="info" />
      </main>

      <footer class="assistant-composer">
        <div class="composer-field">
          <label class="sr-only" for="ai-assistant-input">向智能助手提问</label>
          <textarea
            id="ai-assistant-input"
            :value="question"
            aria-label="向智能助手提问"
            rows="2"
            maxlength="500"
            placeholder="输入问题，Enter 发送"
            @input="handleQuestionInput"
            @keydown.enter.exact.prevent="send"
          />
          <span class="composer-count">{{ question.length }}/500</span>
          <button
            type="button"
            class="send-button"
            aria-label="发送问题"
            :disabled="!question.trim() || isGenerating"
            @click="send"
          ><SendOutlined /></button>
        </div>
        <div class="quick-prompts" aria-label="快捷提问">
          <button type="button" aria-label="查询入住率" @click="fillQuestion('查询当前宿舍入住率')">查询入住率</button>
          <button type="button" aria-label="查看待处理维修" @click="fillQuestion('查看当前待处理维修')">查看待处理维修</button>
        </div>
        <p class="privacy-note"><LockOutlined />请勿输入姓名、学号或手机号</p>
      </footer>

      <div
        v-if="store.closeConfirmationOpen"
        ref="closeConfirmRef"
        class="close-confirm"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="ai-assistant-close-title"
        aria-describedby="ai-assistant-close-description"
        tabindex="-1"
      >
        <h3 id="ai-assistant-close-title">停止生成并关闭？</h3>
        <p id="ai-assistant-close-description">当前回答仍在生成，关闭后本次生成将停止。</p>
        <div>
          <button type="button" @click="store.closeConfirmationOpen = false">继续查看</button>
          <button type="button" class="danger" @click="store.confirmStopAndClose">停止并关闭</button>
        </div>
      </div>
    </aside>
  </Teleport>
</template>

<script setup lang="ts">
import {
  ArrowLeftOutlined,
  ArrowRightOutlined,
  BookOutlined,
  CloseOutlined,
  CopyOutlined,
  DownOutlined,
  FileSearchOutlined,
  FileTextOutlined,
  HistoryOutlined,
  LikeOutlined,
  LockOutlined,
  MessageOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SendOutlined,
  StarFilled,
  StarOutlined,
  StopOutlined,
  UpOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { computed, inject, nextTick, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { routerKey, type RouteLocationNormalizedLoaded } from 'vue-router'
import { getAiClient, isAiSurfaceEnabled } from '../../api/ai-client'
import { useAiStore } from '../../stores/ai'
import type { AiCitationDetail, AiConversationMessage, AiConversationSummary, AiRunState } from '../../types/ai'
import AiRunStatus from './AiRunStatus.vue'
import AiSafetyState from './AiSafetyState.vue'

const store = useAiStore()
const appRouter = inject(routerKey, null)
const enabled = isAiSurfaceEnabled()
const question = ref('')
const dialogRef = ref<HTMLElement>()
const closeConfirmRef = ref<HTMLElement>()
const historyPanelRef = ref<HTMLElement>()
const historyToggleRef = ref<HTMLButtonElement>()
const historyOpen = ref(false)
const copiedMessageId = ref('')
const feedbackMessageId = ref('')
const feedbackComment = ref('')
const expandedCitations = reactive<Record<string, boolean>>({})
const citationDetails = reactive<Record<string, {
  detail?: AiCitationDetail
  error?: string
  expanded: boolean
  loading: boolean
}>>({})
const isGenerating = computed(() => ['accepted', 'queued', 'running', 'streaming'].includes(store.runState))
const lastAssistantMessage = computed(() => [...store.messages].reverse().find((message) => message.role === 'assistant'))
const unscopedAssistantError = computed(() => {
  const error = safeDisplayText(store.assistantError).trim()
  if (!error) return ''
  const message = lastAssistantMessage.value
  const state = message ? messageState(message) : undefined
  const messageError = safeDisplayText(message?.failureMessage).trim()
  return (state === 'failed' || state === 'timed_out') && messageError === error ? '' : error
})
const routeContext = ref(currentRouteContext(appRouter?.currentRoute.value))
const scopeLabel = computed(() => scopeName(store.currentConversation?.surface ?? routeContext.value.surface))
const scopeDescription = computed(() => store.currentConversation?.surface === 'GLOBAL'
  ? '全局知识问答'
  : `${scopeLabel.value}授权上下文`)
const piiPlaceholders: Record<string, string> = {
  PERSON_NAME: '某位同学（已脱敏）',
  STUDENT_NO: '学号（已脱敏）',
  PHONE: '手机号（已脱敏）',
  NATIONAL_ID: '身份证号（已脱敏）',
  LOCATION: '位置（已脱敏）',
}
const piiTokenPattern = /\[(PERSON_NAME|STUDENT_NO|PHONE|NATIONAL_ID|LOCATION)(?::[^\]\r\n]*)?(?:\]|(?=\r?\n|$))/g
let previousBodyOverflow = ''
let scrollLocked = false
let assistantWasOpen = false
let assistantReturnFocus: HTMLElement | null = null
let closeConfirmationReturnFocus: HTMLElement | null = null

watch(() => appRouter?.currentRoute.value.fullPath, refreshRouteContext)

watch(() => store.assistantOpen, async (open) => {
  if (open) {
    const activeElement = document.activeElement
    if (activeElement instanceof HTMLElement && activeElement !== document.body && !dialogRef.value?.contains(activeElement)) {
      assistantReturnFocus = activeElement
    }
    assistantWasOpen = true
    refreshRouteContext()
    if (!scrollLocked) {
      previousBodyOverflow = document.body.style.overflow
      document.body.style.overflow = 'hidden'
      scrollLocked = true
    }
    await store.restoreAssistantSession()
    await nextTick()
    const currentFocus = document.activeElement
    if (!(currentFocus instanceof HTMLElement) || !dialogRef.value?.contains(currentFocus)) dialogRef.value?.focus()
  } else {
    if (!assistantWasOpen) return
    assistantWasOpen = false
    historyOpen.value = false
    restoreBodyScroll()
    const returnFocus = assistantReturnFocus
    assistantReturnFocus = null
    await nextTick()
    if (returnFocus?.isConnected && !returnFocus.hasAttribute('disabled')) returnFocus.focus()
    else document.querySelector<HTMLElement>('[data-ai-assistant-trigger]')?.focus()
  }
}, { immediate: true })

watch(() => store.closeConfirmationOpen, async (open) => {
  if (open) {
    closeConfirmationReturnFocus = document.activeElement instanceof HTMLElement ? document.activeElement : null
    await nextTick()
    focusableElements(closeConfirmRef.value)[0]?.focus()
  } else if (store.assistantOpen) {
    await nextTick()
    closeConfirmationReturnFocus?.focus()
    closeConfirmationReturnFocus = null
  }
})

onBeforeUnmount(restoreBodyScroll)

function send() {
  const value = safeDisplayText(question.value).trim()
  if (!value || isGenerating.value) return
  question.value = ''
  const nextRouteContext = refreshRouteContext()
  const current = store.currentConversation
  if (current && (current.surface !== nextRouteContext.surface
    || current.contextType !== (nextRouteContext.contextType ?? 'NONE')
    || (current.contextId ?? undefined) !== nextRouteContext.contextId)) {
    store.startNewConversation()
  }
  void store.askAssistant(value, nextRouteContext)
}

function safeDisplayText(value?: string | null) {
  if (!value) return ''
  return value.replace(piiTokenPattern, (_token, kind: string) => piiPlaceholders[kind] ?? '敏感信息（已脱敏）')
}

function handleQuestionInput(event: Event) {
  const input = event.target as HTMLTextAreaElement
  const safeValue = safeDisplayText(input.value).slice(0, 500)
  question.value = safeValue
  if (input.value !== safeValue) input.value = safeValue
}

function fillQuestion(value: string) {
  question.value = value
  nextTick(() => document.querySelector<HTMLTextAreaElement>('#ai-assistant-input')?.focus())
}

async function startNewConversation() {
  question.value = ''
  historyOpen.value = false
  feedbackMessageId.value = ''
  store.startNewConversation()
  await nextTick()
  document.querySelector<HTMLTextAreaElement>('#ai-assistant-input')?.focus()
}

function retry(runId?: string) {
  if (runId) void store.retryAssistant(runId)
}

async function copyAnswer(messageId: string, text: string) {
  if (!navigator.clipboard || typeof navigator.clipboard.writeText !== 'function') {
    copiedMessageId.value = ''
    return
  }
  try {
    await navigator.clipboard.writeText(safeDisplayText(text))
    copiedMessageId.value = messageId
  } catch {
    copiedMessageId.value = ''
  }
}

function availableCitationCount(message: AiConversationMessage) {
  return message.citations.filter((citation) => citation.access === 'available').length
}

function formatAsOf(value?: string) {
  return value ? value.replace('T', ' ').replace(/Z$/, '').slice(0, 16) : '-'
}

function messageState(message: AiConversationMessage): AiRunState | undefined {
  if (message.id === lastAssistantMessage.value?.id && store.runState !== 'idle') return store.runState
  return message.runState
}

function messageContentState(message: AiConversationMessage) {
  const state = messageState(message)
  if (state === 'streaming' || state === 'accepted' || state === 'queued' || state === 'running') return 'streaming'
  if (state === 'cancelled') return 'cancelled'
  if (state === 'timed_out') return 'timed-out'
  if (state === 'failed') return 'failed'
  if (state === 'needs_reconciliation') return 'needs-reconciliation'
  if (message.grounded === false) return 'no-grounded'
  if (typeof message.confidence === 'number' && message.confidence < 0.65) return 'low-confidence'
  if (message.degradedReason || state === 'degraded') return 'degraded'
  return state ?? 'unknown'
}

function isInFlightMessage(message: AiConversationMessage) {
  return ['accepted', 'queued', 'running', 'streaming'].includes(messageState(message) ?? '')
}

function isPartialStreaming(message: AiConversationMessage) {
  return isInFlightMessage(message) && safeDisplayText(message.text).trim().length > 0
}

function messageEvidencePhase(message: AiConversationMessage) {
  if (isPartialStreaming(message)) return 'provisional'
  return messageState(message) === 'succeeded' ? 'final' : undefined
}

function isPermissionError(value: string) {
  return /无权限|权限不足|禁止|forbidden|403/i.test(value)
}

function formatMessageTime(value?: string) {
  const date = value ? new Date(value) : new Date()
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false }).format(date)
}

function confidenceLabel(value?: number) {
  return typeof value === 'number' ? `置信度 ${Math.round(value * 100)}%` : '未提供置信度'
}

function statusLabel(state?: AiRunState) {
  return state === 'cancelled' ? '已停止'
    : state === 'failed' ? '生成失败'
      : state === 'timed_out' ? '生成超时'
        : state === 'needs_reconciliation' ? '需要人工对账'
          : state === 'degraded' ? '降级完成' : '已完成'
}

function citationsExpanded(messageId: string) {
  return expandedCitations[messageId] !== false
}

function toggleCitations(messageId: string) {
  expandedCitations[messageId] = !citationsExpanded(messageId)
}

function citationDetail(citationId: string) {
  return citationDetails[citationId]?.detail
}

function citationDetailError(citationId: string) {
  return citationDetails[citationId]?.error ?? ''
}

function citationDetailExpanded(citationId: string) {
  return citationDetails[citationId]?.expanded === true
}

function citationDetailLoading(citationId: string) {
  return citationDetails[citationId]?.loading === true
}

async function toggleCitationDetail(citationId: string) {
  if (!citationDetails[citationId]) {
    citationDetails[citationId] = { expanded: false, loading: false }
  }
  const state = citationDetails[citationId]!
  if (state.expanded) {
    state.expanded = false
    return
  }

  state.expanded = true
  state.loading = true
  delete state.detail
  delete state.error
  try {
    const client = getAiClient()
    if (!client.getCitation) throw new Error('引用详情暂不可用，请稍后重试')
    state.detail = await client.getCitation(citationId)
  } catch (error) {
    state.error = safeDisplayText(error instanceof Error ? error.message : '引用详情加载失败，请稍后重试')
  } finally {
    state.loading = false
  }
}

async function toggleHistory() {
  if (historyOpen.value) {
    await closeHistory()
    return
  }
  historyOpen.value = true
  try {
    await store.loadConversations()
  } finally {
    await nextTick()
    historyPanelRef.value?.querySelector<HTMLButtonElement>('button[aria-label="关闭会话历史"]')?.focus()
  }
}

async function closeHistory() {
  if (!historyOpen.value) return
  historyOpen.value = false
  await nextTick()
  if (store.assistantOpen) historyToggleRef.value?.focus()
}

async function switchConversation(id: string) {
  if (isGenerating.value) return
  await store.selectConversation(id)
  await closeHistory()
}

function conversationTitle(item: AiConversationSummary) {
  return safeDisplayText(item.title?.trim()) || '未命名会话'
}

function conversationScope(item: AiConversationSummary) {
  return `${scopeName(item.surface)} · ${formatMessageTime(item.lastMessageAt ?? item.createdAt)}`
}

function scopeName(surface: string) {
  return ({
    GLOBAL: '全局', DASHBOARD: '驾驶舱', KNOWLEDGE: '知识库', REPAIR: '维修',
    NOTICE: '公告', RISK: '风险中心', APPROVAL: '审批', AUDIT: '审计',
  } as Record<string, string>)[surface] ?? '全局'
}

function refreshRouteContext() {
  const nextRouteContext = currentRouteContext(appRouter?.currentRoute.value)
  routeContext.value = nextRouteContext
  return nextRouteContext
}

function currentRouteContext(route?: Pick<RouteLocationNormalizedLoaded, 'path' | 'query'>): { surface: string; contextType?: string; contextId?: number } {
  const path = (route?.path ?? window.location.pathname).replace(/\/$/, '') || '/'
  const rawQueryId = route?.query.aiContextId
  const queryId = Number(route
    ? (Array.isArray(rawQueryId) ? rawQueryId[0] : rawQueryId)
    : new URLSearchParams(window.location.search).get('aiContextId'))
  const scopedElement = document.querySelector<HTMLElement>('[data-ai-context-type][data-ai-context-id]')
  const elementId = Number(scopedElement?.dataset.aiContextId)
  const contextId = Number.isSafeInteger(queryId) && queryId > 0
    ? queryId : Number.isSafeInteger(elementId) && elementId > 0 ? elementId : undefined
  if (path.startsWith('/repairs') && contextId) return { surface: 'REPAIR', contextType: 'REPAIR', contextId }
  if (path.startsWith('/notices') && contextId) return { surface: 'NOTICE', contextType: 'NOTICE', contextId }
  // Dashboard is the underlying page, but the assistant drawer is a global
  // scope. Keeping NONE here lets the server authorize knowledge/capacity
  // tools instead of constraining every question to dashboard metrics.
  if (path === '/') return { surface: 'GLOBAL', contextType: 'NONE' }
  if (path === '/ai/knowledge') return { surface: 'KNOWLEDGE', contextType: 'KNOWLEDGE' }
  if (path === '/ai/risks') return { surface: 'RISK' }
  if (path === '/ai/approvals') return { surface: 'APPROVAL' }
  if (path === '/ai/audit') return { surface: 'AUDIT' }
  return { surface: 'GLOBAL' }
}

function isServerMessageId(messageId: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(messageId)
}

function feedbackSubmitting(messageId: string) {
  return store.feedbackByMessage[messageId]?.state === 'submitting'
}

async function likeAnswer(messageId: string) {
  try { await store.submitAssistantFeedback(messageId, 1, ['helpful']) }
  catch { /* 状态由 Store 以安全消息呈现。 */ }
}

function openFeedback(messageId: string) {
  feedbackMessageId.value = messageId
  feedbackComment.value = ''
  nextTick(() => document.querySelector<HTMLTextAreaElement>(`#assistant-feedback-${messageId}`)?.focus())
}

function closeFeedback() {
  feedbackMessageId.value = ''
  feedbackComment.value = ''
}

async function submitFeedback(messageId: string) {
  const comment = feedbackComment.value.trim()
  if (!comment) return
  try {
    await store.submitAssistantFeedback(messageId, -1, ['needs-review'], comment)
    closeFeedback()
  } catch { /* 状态由 Store 以安全消息呈现。 */ }
}

function restoreBodyScroll() {
  if (!scrollLocked) return
  document.body.style.overflow = previousBodyOverflow
  scrollLocked = false
}

function handleKeydown(event: KeyboardEvent) {
  if (store.closeConfirmationOpen) {
    if (event.key === 'Escape') {
      event.preventDefault()
      store.closeConfirmationOpen = false
      return
    }
    if (event.key === 'Tab') trapFocus(event, closeConfirmRef.value)
    return
  }
  if (event.key === 'Escape') {
    event.preventDefault()
    if (historyOpen.value) void closeHistory()
    else store.requestCloseAssistant()
    return
  }
  if (event.key === 'Tab') trapFocus(event, historyOpen.value ? historyPanelRef.value : dialogRef.value)
}

function focusableElements(root?: HTMLElement) {
  if (!root) return []
  return Array.from(root.querySelectorAll<HTMLElement>('button:not([disabled]), textarea:not([disabled]), input:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'))
    .filter((element) => {
      if (element.hidden || element.closest('[hidden], [inert], [aria-hidden="true"]')) return false
      const style = window.getComputedStyle(element)
      return style.display !== 'none' && style.visibility !== 'hidden'
    })
}

function trapFocus(event: KeyboardEvent, root?: HTMLElement) {
  const focusable = focusableElements(root)
  if (!focusable.length) return
  const first = focusable[0]
  const last = focusable[focusable.length - 1]
  if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
  else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
}
</script>

<style scoped>
.ai-assistant {
  position: fixed;
  inset: 0 0 0 auto;
  z-index: 2147483000;
  width: min(var(--ai-drawer-width), 100vw);
  height: 100vh;
  height: 100dvh;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  color: var(--text);
  background: var(--surface);
  border-left: 1px solid var(--border);
  box-shadow: var(--shadow-overlay);
  font-family: var(--font-family-ui);
  isolation: isolate;
}

button { color: inherit; font: inherit; }
.assistant-header { min-height: 104px; flex: 0 0 auto; display: flex; align-items: center; gap: var(--space-3); padding: var(--space-4); border-bottom: 1px solid var(--border); background: var(--surface); }
.assistant-title { min-width: 0; flex: 1; }
.assistant-title > div { min-width: 0; display: flex; align-items: center; gap: var(--space-2); }
.assistant-title h2 { margin: 0; color: var(--text-title); font-size: 20px; font-weight: var(--font-weight-bold); line-height: 32px; white-space: nowrap; }
.assistant-title p { margin: 2px 0 0; color: var(--text-muted); font-size: var(--font-size-caption); line-height: 1.5; }
.scope-chip { display: inline-flex; align-items: center; min-height: 28px; padding: 0 var(--space-2); border: 1px solid var(--primary-border); border-radius: var(--radius-control); color: var(--primary); background: var(--primary-soft); font-size: var(--font-size-caption); font-weight: var(--font-weight-medium); }
.assistant-header__actions { flex: 0 0 auto; display: flex; align-items: center; gap: var(--space-1); }
.header-action, .header-icon-button { min-width: var(--touch-target); min-height: var(--touch-target); display: inline-flex; align-items: center; justify-content: center; gap: 6px; border: 0; border-radius: var(--radius-control); color: var(--text-title); background: transparent; cursor: pointer; }
.header-action { padding: 0 var(--space-2); font-size: var(--font-size-caption); white-space: nowrap; }
.new-session { border: 1px solid var(--border); }
.header-icon-button { width: var(--touch-target); padding: 0; font-size: 18px; }
.header-action:hover, .header-icon-button:hover { color: var(--primary); background: var(--surface-hover); }
.header-action:focus-visible, .header-icon-button:focus-visible { color: var(--primary); outline: 0; box-shadow: var(--focus-ring); }
.mobile-back { display: none; }

.history-panel { position: absolute; inset: 92px var(--space-3) auto; z-index: 4; max-height: 320px; overflow: auto; padding: var(--space-3); border: 1px solid var(--border); border-radius: var(--radius-card); background: var(--surface-elevated); box-shadow: var(--shadow-overlay); }
.history-panel header { display: flex; align-items: center; justify-content: space-between; padding: 2px 2px 10px; }
.history-panel__actions { display: flex; align-items: center; gap: 4px; }
.history-panel header button { width: var(--touch-target); height: var(--touch-target); border: 0; border-radius: var(--radius-control); background: transparent; cursor: pointer; }
.history-panel ul { margin: 0; padding: 0; list-style: none; }
.history-panel li { border-top: 1px solid var(--border); color: var(--text); }
.history-panel li.active { background: var(--surface-selected); }
.history-panel li > button { width: 100%; min-height: 48px; display: grid; grid-template-columns: 24px minmax(0, 1fr); align-items: center; gap: 9px; padding: 7px; border: 0; background: transparent; text-align: left; cursor: pointer; }
.history-panel li > button:focus-visible { outline: 0; box-shadow: inset var(--focus-ring); }
.history-panel li > button > span { min-width: 0; display: grid; gap: 3px; }
.history-panel li strong, .history-panel li small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-panel li strong { color: var(--text-title); font-size: var(--font-size-body); }
.history-panel li small { color: var(--text-muted); font-size: var(--font-size-caption); }
.history-panel p { margin: 12px 2px; color: var(--text-muted); }

.assistant-messages { min-width: 0; min-height: 0; flex: 1 1 auto; overflow-y: auto; overscroll-behavior: contain; scroll-padding-block-end: 28px; padding: 18px var(--space-4) var(--space-5); background: var(--surface); }
.assistant-messages:focus-visible { outline: 0; box-shadow: inset var(--focus-ring); }
.assistant-empty { min-height: 230px; display: grid; place-content: center; justify-items: center; padding: var(--space-6); color: var(--text-muted); text-align: center; }
.assistant-empty > svg { color: var(--ai-accent); font-size: 40px; }
.assistant-empty h3 { margin: 15px 0 6px; color: var(--text-title); font-size: 18px; font-weight: var(--font-weight-bold); }
.assistant-empty p { margin: 0; line-height: 1.7; }
.assistant-empty small { display: inline-flex; align-items: center; gap: 6px; margin-top: 14px; font-size: var(--font-size-caption); }

.user-turn { min-width: 0; display: grid; justify-items: end; margin-bottom: 18px; }
.user-turn__meta { display: flex; align-items: center; justify-content: flex-end; gap: var(--space-2); margin-bottom: 6px; }
.user-turn time { color: var(--text-muted); font-size: var(--font-size-caption); }
.user-avatar { width: 30px; height: 30px; display: grid; place-items: center; border-radius: 50%; color: var(--surface); background: var(--primary); font-size: 16px; }
.user-turn p { min-width: 0; max-width: 84%; margin: 0; padding: 12px 15px; overflow-wrap: anywhere; border: 1px solid var(--primary-border); border-radius: var(--radius-control) var(--radius-control) 2px var(--radius-control); color: var(--text-title); background: var(--primary-muted); font-size: var(--font-size-body); line-height: 1.65; }

.assistant-turn { display: grid; gap: 10px; margin-bottom: 18px; }
.assistant-progress { min-height: var(--touch-target); display: flex; align-items: center; gap: var(--space-2); color: var(--text-strong); font-size: var(--font-size-caption); }
.assistant-mark { position: relative; width: 32px; height: 32px; flex: 0 0 auto; display: block; border: 1px solid var(--primary-border); border-radius: 50%; color: var(--ai-accent); background: var(--ai-soft); }
.assistant-mark__primary { position: absolute; top: 6px; left: 7px; font-size: 16px; }
.assistant-mark__secondary { position: absolute; right: 5px; bottom: 5px; color: var(--primary); font-size: 9px; }
.assistant-progress :deep(.ai-run-status) { flex: 1; }
.stop-button { min-height: var(--touch-target); display: inline-flex; align-items: center; gap: 7px; padding: 0 10px; border: 1px solid var(--danger); border-radius: var(--radius-control); color: var(--danger-strong); background: var(--surface); cursor: pointer; }

.answer-card { overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius-card); background: var(--surface-elevated); box-shadow: var(--shadow-card); }
.answer-card__text { margin: 0; padding: var(--space-4); color: var(--text-title); font-size: 15px; line-height: 1.75; white-space: pre-wrap; overflow-wrap: anywhere; }
.answer-meta { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); border-top: 1px solid var(--border); background: var(--surface-muted); }
.answer-meta > span { min-height: var(--touch-target); display: flex; align-items: center; justify-content: center; gap: 6px; padding: 6px; border-right: 1px solid var(--border); color: var(--text-strong); font-size: var(--font-size-caption); text-align: center; }
.answer-meta > span:last-child { border-right: 0; }
.answer-meta svg { flex: 0 0 auto; color: var(--primary); font-size: 16px; }

.citation-card { overflow: hidden; border: 1px solid var(--border); border-radius: var(--radius-card); background: var(--surface); }
.citation-card > header { min-height: 48px; display: flex; align-items: center; justify-content: space-between; padding: 0 14px; border-bottom: 1px solid var(--border); }
.citation-card header > button { width: 100%; min-height: 46px; display: flex; align-items: center; justify-content: space-between; border: 0; background: transparent; cursor: pointer; }
.citation-card header > button:focus-visible { outline: 0; box-shadow: var(--focus-ring); }
.citation-card header button > span { display: flex; align-items: center; gap: 8px; }
.citation-card header strong { color: var(--text-title); font-size: var(--font-size-body); }
.citation-card header small { color: var(--text-muted); font-size: var(--font-size-caption); font-weight: var(--font-weight-regular); }
.citation-card ul { margin: 0; padding: 0; list-style: none; }
.citation-card li { min-height: 62px; display: grid; grid-template-columns: 32px minmax(0, 1fr) 16px; border-bottom: 1px solid var(--border); }
.citation-card li:last-child { border-bottom: 0; }
.citation-row__button, .citation-row__static { width: 100%; min-height: 62px; grid-column: 1 / -1; display: grid; grid-template-columns: 32px minmax(0, 1fr) 16px; align-items: center; gap: 10px; padding: 8px 14px; border: 0; color: inherit; background: transparent; text-align: left; }
.citation-row__button { cursor: pointer; }
.citation-row__button:hover { background: var(--surface-hover); }
.citation-row__button:focus-visible { outline: 0; box-shadow: inset var(--focus-ring); }
.citation-row__static { grid-template-columns: 32px minmax(0, 1fr); }
.citation-row__icon { width: 32px; height: 32px; display: grid; place-items: center; border-radius: var(--radius-control); color: var(--primary); background: var(--primary-soft); }
.citation-row__copy { min-width: 0; display: grid; gap: 3px; }
.citation-row__copy strong { overflow: hidden; color: var(--text-title); font-size: var(--font-size-body); text-overflow: ellipsis; white-space: nowrap; }
.citation-row__copy small { color: var(--text-muted); font-size: var(--font-size-caption); }
.citation-row__button > svg { color: var(--text-muted); font-size: 14px; }
.citation-row--denied { color: var(--text-muted); background: var(--surface-muted); }
.citation-row--denied .citation-row__icon { color: var(--text-muted); background: var(--surface-hover); }
.citation-row--denied .citation-row__copy strong, .citation-row--denied .citation-row__copy small { color: var(--text-muted); }
.citation-detail { grid-column: 1 / -1; display: grid; gap: 4px; padding: 10px 14px 12px 56px; border-top: 1px solid var(--border); background: var(--surface-muted); }
.citation-detail p { margin: 0; color: var(--text-strong); font-size: var(--font-size-body); line-height: 1.6; white-space: pre-wrap; overflow-wrap: anywhere; }
.citation-detail small { color: var(--text-muted); font-size: var(--font-size-caption); }

.assistant-turn :deep(.ai-safety-state) { min-height: var(--touch-target); font-size: var(--font-size-caption); }
.answer-actions { display: grid; grid-template-columns: repeat(4, 1fr); gap: 7px; }
.answer-actions button { min-width: 0; min-height: var(--touch-target); display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 0 6px; border: 1px solid var(--border); border-radius: var(--radius-control); color: var(--text-strong); background: var(--surface); cursor: pointer; font-size: var(--font-size-caption); white-space: nowrap; }
.answer-actions button:hover, .answer-actions button.active { border-color: var(--primary-border); color: var(--primary); background: var(--primary-soft); }
.answer-actions button:focus-visible { border-color: var(--primary); color: var(--primary); outline: 0; box-shadow: var(--focus-ring); }
.answer-actions button:disabled { color: var(--text-muted); background: var(--surface-muted); cursor: not-allowed; }
.feedback-form { display: grid; gap: var(--space-2); padding: var(--space-3); border: 1px solid var(--border); border-radius: var(--radius-card); background: var(--surface-muted); }
.feedback-form label { color: var(--text-title); font-size: var(--font-size-caption); font-weight: var(--font-weight-bold); }
.feedback-form textarea { width: 100%; min-height: 72px; resize: vertical; padding: 9px 10px; border: 1px solid var(--border); border-radius: var(--radius-control); color: var(--text-title); background: var(--surface); font-size: var(--font-size-body); line-height: 1.5; }
.feedback-form textarea:focus-visible { border-color: var(--primary); outline: 0; box-shadow: var(--focus-ring); }
.feedback-form > div { display: flex; justify-content: flex-end; gap: 8px; }
.feedback-form button { min-height: var(--touch-target); padding: 0 12px; border: 1px solid var(--border); border-radius: var(--radius-control); background: var(--surface); cursor: pointer; }
.feedback-form button[aria-label="提交反馈"] { border-color: var(--primary); color: var(--surface); background: var(--primary); }
.feedback-form button:disabled { opacity: .5; cursor: not-allowed; }
.feedback-status { margin: 0; color: var(--success-strong); font-size: var(--font-size-caption); }
.message-guardrails { display: grid; gap: 8px; }
.message-guardrails:empty { display: none; }
.assistant-guardrails { display: grid; gap: 8px; margin-top: 14px; }
.assistant-guardrails :deep(.ai-safety-state) { min-height: var(--touch-target); align-items: center; font-size: var(--font-size-caption); }

.assistant-composer { position: relative; z-index: 2; flex: 0 0 auto; max-height: min(46dvh, 320px); overflow-y: auto; overscroll-behavior: contain; padding: 10px var(--space-4) calc(12px + var(--safe-area-bottom)); border-top: 1px solid var(--border); background: var(--surface); box-shadow: var(--shadow-sticky); }
.composer-field { position: relative; min-height: 78px; border: 1px solid var(--primary); border-radius: var(--radius-control); background: var(--surface); }
.composer-field:focus-within { box-shadow: var(--focus-ring); }
.composer-field textarea { width: 100%; min-height: 76px; padding: 12px 58px 24px 12px; resize: none; border: 0; outline: 0; color: var(--text-title); background: transparent; font-size: var(--font-size-body); line-height: 1.5; }
.composer-field textarea::placeholder { color: var(--text-muted); opacity: 1; }
.composer-count { position: absolute; right: 58px; bottom: 7px; color: var(--text-muted); font-size: var(--font-size-caption); }
.send-button { position: absolute; right: 8px; bottom: 8px; width: var(--touch-target); height: var(--touch-target); display: grid; place-items: center; border: 0; border-radius: var(--radius-control); color: var(--surface); background: var(--primary); cursor: pointer; font-size: 18px; }
.send-button:focus-visible { outline: 0; box-shadow: var(--focus-ring); }
.send-button:disabled { color: var(--text-muted); background: var(--surface-hover); cursor: not-allowed; }
.quick-prompts { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 9px; }
.quick-prompts button { max-width: 100%; min-height: var(--touch-target); padding: 0 10px; overflow-wrap: anywhere; border: 1px solid var(--primary-border); border-radius: var(--radius-control); color: var(--primary); background: var(--surface); cursor: pointer; font-size: var(--font-size-caption); }
.quick-prompts button:hover { background: var(--primary-soft); }
.quick-prompts button:focus-visible { outline: 0; box-shadow: var(--focus-ring); }
.privacy-note { display: flex; align-items: center; justify-content: center; gap: 6px; margin: 8px 0 0; color: var(--text-muted); font-size: var(--font-size-caption); }

.close-confirm { position: absolute; inset: auto var(--space-4) 18px; z-index: 5; padding: 18px; border: 1px solid var(--warning); border-radius: var(--radius-card); background: var(--surface-warning); box-shadow: var(--shadow-overlay); }
.close-confirm h3 { margin: 0 0 6px; font-size: 16px; }
.close-confirm p { margin: 0 0 14px; color: var(--text); font-size: 13px; line-height: 1.6; }
.close-confirm > div { display: flex; justify-content: flex-end; gap: 8px; }
.close-confirm button { min-height: var(--touch-target); padding: 0 12px; border: 1px solid var(--border); border-radius: var(--radius-control); background: var(--surface); cursor: pointer; }
.close-confirm button:focus-visible { outline: 0; box-shadow: var(--focus-ring); }
.close-confirm button.danger { border-color: var(--danger); color: var(--danger-strong); }

@media (max-width: 768px) {
  .ai-assistant { inset: 0; width: 100vw; max-width: 100vw; height: 100vh; height: 100dvh; border: 0; }
  .assistant-header { min-height: 56px; align-items: center; gap: var(--space-1); padding: calc(5px + var(--safe-area-top)) 8px 5px; }
  .mobile-back { display: inline-flex; }
  .assistant-title { display: flex; align-items: center; }
  .assistant-title > div { gap: 6px; }
  .assistant-title h2 { font-size: 20px; }
  .assistant-title p { display: none; }
  .scope-chip { min-height: 28px; font-size: var(--font-size-caption); }
  .new-session { display: none; }
  .assistant-header__actions { gap: 2px; }
  .header-action { padding: 0 5px; font-size: 0; }
  .header-action, .header-icon-button { min-width: 44px; min-height: 44px; }
  .header-action svg { font-size: 19px; }
  .header-action[aria-label="查看会话历史"] span { display: inline; font-size: 13px; }
  .history-panel { inset: calc(56px + var(--safe-area-top)) 10px auto; max-height: 48vh; }
  .history-panel header button { width: 44px; height: 44px; }

  .assistant-messages { padding: 14px 14px 16px; }
  .user-turn { margin-bottom: 14px; }
  .user-turn__meta { width: 100%; justify-content: center; }
  .user-avatar { display: none; }
  .user-turn p { max-width: 88%; padding: 12px 14px; font-size: 14px; }
  .assistant-progress { margin-top: 2px; }
  .answer-card__text { padding: 15px; font-size: 14px; }
  .answer-meta > span { min-height: 48px; flex-direction: column; gap: 3px; font-size: var(--font-size-caption); }
  .citation-card li { min-height: 62px; }
  .answer-actions { gap: 6px; }
  .answer-actions button { min-height: 44px; flex-direction: row; gap: 4px; font-size: var(--font-size-caption); }
  .stop-button { min-height: 44px; }

  .assistant-composer { max-height: min(42dvh, 300px); padding: 5px 14px calc(5px + var(--safe-area-bottom)); }
  .composer-field { min-height: 64px; }
  .composer-field textarea { min-height: 62px; padding: 10px 58px 10px 12px; font-size: 16px; }
  .composer-count { display: none; }
  .send-button { width: 44px; height: 44px; border-radius: 8px; }
  .quick-prompts { margin-top: 5px; }
  .quick-prompts button { min-height: 44px; font-size: 12px; }
  .privacy-note { margin-top: 4px; }
}

@media (max-width: 420px) {
  .assistant-title h2 { font-size: 19px; }
  .header-action[aria-label="查看会话历史"] span { display: inline; font-size: 12px; }
  .assistant-mark { width: 30px; height: 30px; }
}

@media (max-width: 280px) {
  .assistant-header { gap: 4px; padding-inline: 8px; }
  .mobile-back, .scope-chip { display: none; }
  .assistant-title h2 { font-size: 18px; }
  .answer-meta { grid-template-columns: 1fr; }
  .answer-meta > span { min-height: 44px; flex-direction: row; border-right: 0; border-bottom: 1px solid var(--border); }
  .answer-meta > span:last-child { border-bottom: 0; }
  .answer-actions { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .quick-prompts { display: grid; grid-template-columns: minmax(0, 1fr); }
  .quick-prompts button { width: 100%; }
  .close-confirm { inset: auto 8px 8px; }
}

@media (prefers-reduced-motion: reduce) {
  .ai-assistant, .ai-assistant * { scroll-behavior: auto !important; transition: none !important; animation: none !important; }
}
</style>
