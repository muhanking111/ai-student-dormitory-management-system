<template>
  <div class="ai-evidence" :aria-label="label">
    <span v-if="confidencePercent !== null" class="ai-confidence" :class="confidenceClass">
      <SafetyCertificateOutlined aria-hidden="true" />
      置信度 {{ confidencePercent }}%
    </span>
    <span class="ai-evidence__meta">
      <LinkOutlined aria-hidden="true" />引用来源 {{ availableCitations.length }} / {{ evidence.citations.length }}
    </span>
    <span class="ai-evidence__meta">
      <ClockCircleOutlined aria-hidden="true" />数据截至 {{ formatAsOf(evidence.asOf) }}
    </span>
    <p v-if="!evidence.grounded" class="ai-evidence__warning" role="alert">
      <WarningOutlined aria-hidden="true" />
      <span>暂无可靠来源，无法确认</span>
    </p>
    <details v-if="evidence.citations.length" class="ai-citations" :open="openCitations">
      <summary>查看引用来源</summary>
      <ul>
        <li
          v-for="citation in evidence.citations"
          :key="citation.id"
          data-citation-card
          :data-access="isAvailable(citation.id, citation.access) ? 'available' : 'denied'"
        >
          <template v-if="isAvailable(citation.id, citation.access)">
            <button
              type="button"
              class="ai-citation-link"
              :aria-expanded="isExpanded(citation.id)"
              :aria-controls="detailId(citation.id)"
              :aria-busy="isLoading(citation.id)"
              :disabled="disabled || isLoading(citation.id)"
              @click="toggleCitation(citation.id)"
            >
              <LoadingOutlined v-if="isLoading(citation.id)" spin aria-hidden="true" />
              <FileTextOutlined v-else aria-hidden="true" />
              <span><strong>{{ citation.label }}</strong> · {{ citation.locator }} · {{ citation.version }}</span>
            </button>
            <div
              v-if="isExpanded(citation.id) && citationDetails[citation.id]"
              :id="detailId(citation.id)"
              class="ai-citation-detail"
              aria-live="polite"
            >
              <small>
                {{ citationDetails[citation.id]?.locator }} · 内容哈希 {{ shortHash(citationDetails[citation.id]?.contentHash) }}
              </small>
              <pre>{{ citationDetails[citation.id]?.quote }}</pre>
            </div>
          </template>
          <span v-else class="ai-citation-denied"><LockOutlined aria-hidden="true" />无权限查看此来源</span>
        </li>
      </ul>
    </details>
  </div>
</template>

<script setup lang="ts">
import {
  ClockCircleOutlined,
  FileTextOutlined,
  LinkOutlined,
  LoadingOutlined,
  LockOutlined,
  SafetyCertificateOutlined,
  WarningOutlined,
} from '@ant-design/icons-vue'
import { computed, ref, useId, watch } from 'vue'
import type { AiCitation, AiCitationDetail, AiEvidenceMeta } from '../../types/ai'

const props = withDefaults(defineProps<{
  evidence: AiEvidenceMeta
  label?: string
  citationDetails?: Partial<Record<string, AiCitationDetail>>
  expandedCitationIds?: readonly string[]
  deniedCitationIds?: readonly string[]
  loadingCitationIds?: readonly string[]
  disabled?: boolean
  openCitations?: boolean
}>(), {
  label: 'AI 依据',
  citationDetails: () => ({}),
  deniedCitationIds: () => [],
  loadingCitationIds: () => [],
  disabled: false,
  openCitations: false,
})
const emit = defineEmits<{
  'request-citation': [citationId: string]
  'toggle-citation': [citationId: string, expanded: boolean]
}>()
const componentId = useId()
const localExpanded = ref(new Set(props.expandedCitationIds ?? []))

watch(() => props.expandedCitationIds, (ids) => {
  if (ids) localExpanded.value = new Set(ids)
})

const availableCitations = computed(() => props.evidence.citations
  .filter((item) => isAvailable(item.id, item.access)))
const confidencePercent = computed(() => {
  const value = props.evidence.confidence
  return value === undefined || !Number.isFinite(value)
    ? null
    : Math.round(Math.min(1, Math.max(0, value)) * 100)
})
const confidenceClass = computed(() => {
  const value = props.evidence.confidence ?? 0
  return value >= 0.8 ? 'ai-confidence--high' : value >= 0.65 ? 'ai-confidence--medium' : 'ai-confidence--low'
})

function formatAsOf(value: string) {
  return value ? value.replace('T', ' ').replace(/Z$/, '').slice(0, 16) : '-'
}

function isAvailable(id: string, access: AiCitation['access']) {
  return access === 'available' && !props.deniedCitationIds.includes(id)
}

function isLoading(id: string) {
  return props.loadingCitationIds.includes(id)
}

function isExpanded(id: string) {
  return props.expandedCitationIds?.includes(id) ?? localExpanded.value.has(id)
}

function toggleCitation(id: string) {
  const expanded = !isExpanded(id)
  if (props.expandedCitationIds === undefined) {
    const next = new Set(localExpanded.value)
    if (expanded) next.add(id)
    else next.delete(id)
    localExpanded.value = next
  }
  emit('toggle-citation', id, expanded)
  if (expanded && !props.citationDetails[id]) emit('request-citation', id)
}

function detailId(id: string) {
  return `ai-citation-${componentId}-${id.replace(/[^a-zA-Z0-9_-]/g, '-')}`
}

function shortHash(value?: string) {
  return value ? `${value.slice(0, 12)}…` : '-'
}
</script>

<style scoped>
.ai-evidence {
  min-width: 0;
}

.ai-confidence,
.ai-evidence__meta,
.ai-evidence__warning,
.ai-citation-denied {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.ai-evidence__warning {
  overflow-wrap: anywhere;
}

.ai-citations summary {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  border-radius: 4px;
}

.ai-citations summary:focus-visible,
.ai-citation-link:focus-visible {
  outline: 3px solid rgb(37 99 235 / 28%);
  outline-offset: 2px;
}

.ai-citation-link {
  width: 100%;
  min-width: 0;
  min-height: 44px;
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 7px 0;
  border: 0;
  color: #2563eb;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.ai-citation-link > span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.ai-citation-link:disabled {
  color: #94a3b8;
  cursor: not-allowed;
}

.ai-citation-detail {
  min-width: 0;
  margin-top: 6px;
  padding: 10px;
  border: 1px solid #e7edf6;
  border-radius: 7px;
  background: #f8fafc;
}

.ai-citation-detail pre {
  max-width: 100%;
  margin: 7px 0 0;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font: inherit;
}
</style>
