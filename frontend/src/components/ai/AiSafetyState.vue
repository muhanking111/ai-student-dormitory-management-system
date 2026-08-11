<template>
  <div
    class="ai-safety-state"
    :class="`ai-safety-state--${resolvedTone}`"
    :role="resolvedTone === 'danger' ? 'alert' : 'status'"
    :aria-live="resolvedTone === 'danger' ? 'assertive' : 'polite'"
    aria-atomic="true"
    :aria-label="displayMessage"
    :aria-busy="loading"
    :data-state="resolvedState"
  >
    <span class="ai-safety-state__icon" data-state-icon aria-hidden="true"><component :is="icon" /></span>
    <span class="ai-safety-state__message">{{ displayMessage }}</span>
    <button
      v-if="actionLabel"
      type="button"
      class="ai-safety-state__action"
      :disabled="disabled || loading"
      :aria-busy="loading"
      @click="emit('action')"
    >
      <LoadingOutlined v-if="loading" spin aria-hidden="true" />
      <ReloadOutlined v-else aria-hidden="true" />
      <span>{{ loading ? loadingText : actionLabel }}</span>
    </button>
  </div>
</template>

<script setup lang="ts">
import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  LoadingOutlined,
  LockOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons-vue'
import { computed } from 'vue'

type AiSafetyTone = 'info' | 'warning' | 'danger' | 'success'
type AiSafetyCode =
  | 'INFO' | 'WARNING' | 'SUCCESS'
  | 'NO_PERMISSION' | 'NO_GROUNDED' | 'NO_GROUNDED_ANSWER'
  | 'STALE' | 'EXPIRED' | 'DEGRADED' | 'FAILED'

const props = withDefaults(defineProps<{
  state?: AiSafetyCode
  message?: string
  tone?: AiSafetyTone
  actionLabel?: string
  loadingText?: string
  loading?: boolean
  disabled?: boolean
}>(), {
  state: undefined,
  message: '',
  tone: undefined,
  actionLabel: '',
  loadingText: '处理中…',
  loading: false,
  disabled: false,
})
const emit = defineEmits<{ action: [] }>()

const stateMeta: Record<AiSafetyCode, { message: string; tone: AiSafetyTone; icon: typeof InfoCircleOutlined }> = {
  INFO: { message: '请核对当前信息。', tone: 'info', icon: InfoCircleOutlined },
  WARNING: { message: '请人工核验后继续。', tone: 'warning', icon: ExclamationCircleOutlined },
  SUCCESS: { message: '操作已完成。', tone: 'success', icon: CheckCircleOutlined },
  NO_PERMISSION: { message: '无权限使用此能力，相关对象和引用内容不会展示。', tone: 'danger', icon: LockOutlined },
  NO_GROUNDED: { message: '暂无可靠来源，无法确认。', tone: 'warning', icon: SafetyCertificateOutlined },
  NO_GROUNDED_ANSWER: { message: '暂无可靠来源，无法确认。', tone: 'warning', icon: SafetyCertificateOutlined },
  STALE: { message: '业务数据已变化，当前预览已失效。', tone: 'danger', icon: ExclamationCircleOutlined },
  EXPIRED: { message: '方案已过期，请刷新业务事实后重新预览。', tone: 'danger', icon: ClockCircleOutlined },
  DEGRADED: { message: '当前为降级结果，请人工核验后继续。', tone: 'warning', icon: ExclamationCircleOutlined },
  FAILED: { message: '操作失败，请核对业务事实后重试。', tone: 'danger', icon: CloseCircleOutlined },
}

const resolvedState = computed<AiSafetyCode>(() => props.state ?? ({
  info: 'INFO', warning: 'WARNING', danger: 'FAILED', success: 'SUCCESS',
}[props.tone ?? 'info'] as AiSafetyCode))
const resolvedTone = computed(() => props.tone ?? stateMeta[resolvedState.value].tone)
const displayMessage = computed(() => props.message || stateMeta[resolvedState.value].message)
const icon = computed(() => stateMeta[resolvedState.value].icon)
</script>

<style scoped>
.ai-safety-state {
  min-width: 0;
}

.ai-safety-state__icon {
  flex: 0 0 auto;
  display: inline-flex;
  margin-top: 2px;
}

.ai-safety-state__message {
  min-width: 0;
  flex: 1 1 auto;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.ai-safety-state__action {
  flex: 0 0 auto;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 0 10px;
  border: 1px solid currentColor;
  border-radius: var(--radius-control);
  color: inherit;
  background: var(--surface);
  white-space: nowrap;
  cursor: pointer;
}

.ai-safety-state__action:disabled {
  opacity: 0.58;
  cursor: not-allowed;
}

.ai-safety-state__action:focus-visible {
  outline: 0;
  box-shadow: var(--focus-ring);
}

@media (max-width: 480px) {
  .ai-safety-state {
    flex-wrap: wrap;
  }

  .ai-safety-state__action {
    width: 100%;
  }
}
</style>
