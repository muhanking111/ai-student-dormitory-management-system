<template>
  <form
    class="ai-command-bar"
    :class="{ 'ai-command-bar--disabled': disabled, 'ai-command-bar--loading': loading }"
    role="search"
    :aria-label="label"
    :aria-busy="loading"
    @submit.prevent="submit"
  >
    <span class="ai-command-bar__mark" aria-hidden="true"><RobotOutlined /></span>
    <label class="sr-only" :for="inputId">{{ label }}</label>
    <input
      :id="inputId"
      ref="inputRef"
      class="ai-command-bar__input"
      type="text"
      :value="question"
      :aria-label="label"
      :placeholder="placeholder"
      :disabled="disabled"
      :readonly="loading"
      :maxlength="maxLength"
      @input="updateQuestion"
    />
    <button
      class="ai-command-bar__button"
      type="submit"
      :aria-label="loading ? loadingText : buttonText"
      :aria-busy="loading"
      :disabled="disabled || loading || !question.trim()"
    >
      <LoadingOutlined v-if="loading" spin aria-hidden="true" />
      <SendOutlined v-else aria-hidden="true" />
      <span>{{ loading ? loadingText : buttonText }}</span>
    </button>
  </form>
</template>

<script setup lang="ts">
import { LoadingOutlined, RobotOutlined, SendOutlined } from '@ant-design/icons-vue'
import { ref, useId, watch } from 'vue'

const props = withDefaults(defineProps<{
  modelValue?: string
  label?: string
  placeholder?: string
  buttonText?: string
  loadingText?: string
  maxLength?: number
  loading?: boolean
  disabled?: boolean
}>(), {
  modelValue: undefined,
  label: '自然语言查询',
  placeholder: '向 AI 提问：本周有哪些运营风险？',
  buttonText: '查询',
  loadingText: '查询中…',
  maxLength: 500,
  loading: false,
  disabled: false,
})
const emit = defineEmits<{
  'update:modelValue': [value: string]
  submit: [question: string]
}>()
const question = ref(props.modelValue ?? '')
const inputRef = ref<HTMLInputElement | null>(null)
const inputId = `ai-command-${useId()}`

watch(() => props.modelValue, (value) => {
  if (value !== undefined && value !== question.value) question.value = value
})

function updateQuestion(event: Event) {
  question.value = (event.target as HTMLInputElement).value
  emit('update:modelValue', question.value)
}

function submit() {
  const value = question.value.trim()
  if (!value || props.disabled || props.loading) return
  emit('submit', value)
}

function focus() {
  inputRef.value?.focus()
}

defineExpose({ focus })
</script>

<style scoped>
.ai-command-bar {
  width: 100%;
  min-width: 0;
}

.ai-command-bar:focus-within {
  border-color: #2563eb;
  box-shadow: 0 0 0 3px rgb(37 99 235 / 16%);
}

.ai-command-bar--disabled {
  background: #f5f7fa;
}

.ai-command-bar__mark {
  flex: 0 0 auto;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.ai-command-bar__input {
  width: 100%;
  min-width: 0;
  text-overflow: ellipsis;
}

.ai-command-bar__button {
  flex: 0 0 auto;
  min-width: 96px;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  white-space: nowrap;
}

.ai-command-bar__button:focus-visible {
  outline: 3px solid var(--primary);
  outline-offset: 2px;
}
</style>
