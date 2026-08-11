<template>
  <div
    class="ai-run-status"
    :class="`ai-run-status--${state}`"
    :role="isError ? 'alert' : 'status'"
    aria-live="polite"
    aria-atomic="true"
    :aria-label="displayMessage"
    :aria-busy="isBusy"
    :data-state="state"
  >
    <span class="ai-run-status__icon" data-state-icon aria-hidden="true">
      <component :is="statusIcon" :spin="isBusy" />
    </span>
    <span class="ai-run-status__message">{{ displayMessage }}</span>
    <button
      v-if="showAction && action"
      type="button"
      class="ai-run-status__action"
      :disabled="disabled || loading"
      :aria-busy="loading"
      @click="runAction"
    >
      <LoadingOutlined v-if="loading" spin aria-hidden="true" />
      <StopOutlined v-else-if="action === 'cancel'" aria-hidden="true" />
      <ReloadOutlined v-else aria-hidden="true" />
      <span>{{ actionText }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  LoadingOutlined,
  PauseCircleOutlined,
  ReloadOutlined,
  StopOutlined,
} from '@ant-design/icons-vue'
import { computed } from 'vue'
import type { AiRunState } from '../../types/ai'

const props = withDefaults(defineProps<{
  state: AiRunState
  message?: string
  showAction?: boolean
  loading?: boolean
  disabled?: boolean
}>(), {
  message: '',
  showAction: false,
  loading: false,
  disabled: false,
})
const emit = defineEmits<{ cancel: []; retry: [] }>()
const labels: Record<AiRunState, string> = {
  idle: '等待提问',
  accepted: '请求已受理',
  queued: '正在排队',
  running: '正在检索授权资料',
  streaming: '正在生成',
  succeeded: '已完成',
  degraded: '已降级为确定性结果',
  cancelled: '已停止生成',
  failed: '生成失败',
  timed_out: '生成超时',
  needs_reconciliation: '需要人工对账',
}
const busyStates: AiRunState[] = ['accepted', 'queued', 'running', 'streaming']
const isBusy = computed(() => props.loading || busyStates.includes(props.state))
const isError = computed(() => props.state === 'failed' || props.state === 'timed_out')
const displayMessage = computed(() => props.message || labels[props.state])
const action = computed<'cancel' | 'retry' | null>(() => {
  if (busyStates.includes(props.state)) return 'cancel'
  if (props.state === 'failed' || props.state === 'timed_out') return 'retry'
  return null
})
const actionText = computed(() => {
  if (props.loading) return action.value === 'cancel' ? '停止中…' : '重试中…'
  return action.value === 'cancel' ? '停止生成' : '重试'
})
const statusIcon = computed(() => {
  if (isBusy.value) return LoadingOutlined
  return {
    idle: ClockCircleOutlined,
    succeeded: CheckCircleOutlined,
    degraded: ExclamationCircleOutlined,
    cancelled: PauseCircleOutlined,
    failed: CloseCircleOutlined,
    timed_out: ClockCircleOutlined,
    needs_reconciliation: ExclamationCircleOutlined,
    accepted: LoadingOutlined,
    queued: LoadingOutlined,
    running: LoadingOutlined,
    streaming: LoadingOutlined,
  }[props.state]
})

function runAction() {
  if (!action.value || props.disabled || props.loading) return
  if (action.value === 'cancel') emit('cancel')
  else emit('retry')
}
</script>

<style scoped>
.ai-run-status {
  min-width: 0;
}

.ai-run-status__icon {
  flex: 0 0 auto;
  display: inline-flex;
  color: var(--text-muted);
}

.ai-run-status--accepted .ai-run-status__icon,
.ai-run-status--queued .ai-run-status__icon,
.ai-run-status--running .ai-run-status__icon,
.ai-run-status--streaming .ai-run-status__icon {
  color: var(--primary);
}

.ai-run-status--succeeded .ai-run-status__icon {
  color: var(--success);
}

.ai-run-status--degraded .ai-run-status__icon {
  color: var(--warning);
}

.ai-run-status--needs_reconciliation .ai-run-status__icon {
  color: var(--warning-strong);
}

.ai-run-status--failed .ai-run-status__icon,
.ai-run-status--timed_out .ai-run-status__icon {
  color: var(--danger);
}

.ai-run-status__message {
  min-width: 0;
  overflow-wrap: anywhere;
}

.ai-run-status__action {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  margin-left: auto;
  padding: 0 10px;
  border: 1px solid var(--border);
  border-radius: var(--radius-control);
  color: var(--text);
  background: var(--surface);
  white-space: nowrap;
  cursor: pointer;
}

.ai-run-status__action:disabled {
  color: var(--text-muted);
  background: var(--surface-muted);
  cursor: not-allowed;
}

.ai-run-status__action:focus-visible {
  outline: 0;
  box-shadow: var(--focus-ring);
}
</style>
