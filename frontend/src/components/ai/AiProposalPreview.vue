<template>
  <section class="ai-proposal-preview" :aria-label="label" :aria-busy="loading">
    <div class="ai-flow" role="list" aria-label="AI 建议到人工审批流程">
      <template v-for="(step, index) in steps" :key="step.id">
        <div
          class="ai-flow__step"
          :class="`ai-flow__step--${step.state}`"
          role="listitem"
          data-proposal-step
          :data-state="step.state"
          :aria-current="step.state === 'current' ? 'step' : undefined"
        >
          <span class="ai-flow__icon" aria-hidden="true"><component :is="step.icon" /></span>
          <span>{{ step.label }}</span>
        </div>
        <ArrowRightOutlined v-if="index < steps.length - 1" class="ai-flow__arrow" aria-hidden="true" />
      </template>
    </div>
    <dl class="ai-value-diff">
      <div class="ai-value-diff__current"><dt>{{ currentLabel }}</dt><dd>{{ currentValue }}</dd></div>
      <div class="ai-value-diff__proposed"><dt>{{ proposedLabel }}</dt><dd>{{ proposedValue }}</dd></div>
    </dl>
    <p v-if="impact" class="ai-proposal-preview__impact">
      <InfoCircleOutlined aria-hidden="true" />{{ impactLabel }}：{{ impact }}
    </p>
    <button
      v-if="actionLabel"
      type="button"
      class="ai-proposal-preview__action"
      :disabled="disabled || loading"
      :aria-busy="loading"
      @click="emit('action')"
    >
      <LoadingOutlined v-if="loading" spin aria-hidden="true" />
      <UserOutlined v-else aria-hidden="true" />
      <span>{{ loading ? loadingText : actionLabel }}</span>
    </button>
  </section>
</template>

<script setup lang="ts">
import {
  ArrowRightOutlined,
  EyeOutlined,
  InfoCircleOutlined,
  LoadingOutlined,
  RobotOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { computed } from 'vue'

type AiProposalStage = 'suggestion' | 'preview' | 'approval'
type AiProposalStepState = 'completed' | 'current' | 'pending'

const props = withDefaults(defineProps<{
  currentValue: string
  proposedValue: string
  impact?: string
  label?: string
  currentLabel?: string
  proposedLabel?: string
  impactLabel?: string
  stage?: AiProposalStage
  actionLabel?: string
  loadingText?: string
  loading?: boolean
  disabled?: boolean
}>(), {
  impact: '',
  label: '变更预览',
  currentLabel: '当前值',
  proposedLabel: '建议值',
  impactLabel: '影响',
  stage: 'preview',
  actionLabel: '',
  loadingText: '处理中…',
  loading: false,
  disabled: false,
})
const emit = defineEmits<{ action: [] }>()
const stageOrder: AiProposalStage[] = ['suggestion', 'preview', 'approval']
const stepDefinitions = [
  { id: 'suggestion' as const, label: 'AI 建议', icon: RobotOutlined },
  { id: 'preview' as const, label: '变更预览', icon: EyeOutlined },
  { id: 'approval' as const, label: '人工审批', icon: UserOutlined },
]
const steps = computed(() => {
  const currentIndex = stageOrder.indexOf(props.stage)
  return stepDefinitions.map((step, index) => ({
    ...step,
    state: (index < currentIndex ? 'completed' : index === currentIndex ? 'current' : 'pending') as AiProposalStepState,
  }))
})
</script>

<style scoped>
.ai-proposal-preview {
  min-width: 0;
}

.ai-flow {
  min-width: 0;
}

.ai-flow__step {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #64748b;
  overflow-wrap: anywhere;
}

.ai-flow__step--completed,
.ai-flow__step--current {
  color: #3730a3;
}

.ai-flow__step--current .ai-flow__icon {
  outline: 2px solid #6366f1;
  outline-offset: 3px;
}

.ai-flow__icon {
  flex: 0 0 auto;
  width: 28px;
  height: 28px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 50%;
  background: #fff;
}

.ai-flow__arrow {
  flex: 0 0 auto;
}

.ai-value-diff {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.ai-value-diff > div {
  min-width: 0;
}

.ai-value-diff dd,
.ai-proposal-preview__impact {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.ai-value-diff__current {
  border-color: #fecaca !important;
  background: #fffafa !important;
}

.ai-value-diff__proposed {
  border-color: #a7f3d0 !important;
  background: #f5fffb !important;
}

.ai-proposal-preview__impact {
  display: flex;
  align-items: flex-start;
  gap: 7px;
}

.ai-proposal-preview__action {
  width: 100%;
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: 1px solid #2563eb;
  border-radius: 7px;
  color: #fff;
  background: #2563eb;
  font-weight: 600;
  cursor: pointer;
}

.ai-proposal-preview__action:disabled {
  border-color: #cbd5e1;
  color: #94a3b8;
  background: #e2e8f0;
  cursor: not-allowed;
}

.ai-proposal-preview__action:focus-visible {
  outline: 3px solid rgb(37 99 235 / 28%);
  outline-offset: 2px;
}

@media (max-width: 640px) {
  .ai-flow {
    justify-content: flex-start;
  }

  .ai-flow__step {
    flex: 1 1 86px;
    justify-content: center;
  }

  .ai-value-diff {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
