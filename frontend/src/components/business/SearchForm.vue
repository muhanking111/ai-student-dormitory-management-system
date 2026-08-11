<template>
  <div :class="containerClass ?? 'search-form'">
    <template v-for="field in fields" :key="field.key">
      <a-input
        v-if="field.type === 'input'"
        v-model:value="model[field.key]"
        :aria-label="field.placeholder"
        :placeholder="field.placeholder"
        allow-clear
        @press-enter="emitSearch"
      />
      <a-select v-else v-model:value="model[field.key]" :placeholder="field.placeholder" allow-clear>
        <a-select-option v-for="option in field.options" :key="option" :value="option">
          {{ option }}
        </a-select-option>
      </a-select>
    </template>
    <a-button type="primary" @click="emitSearch"><template #icon><SearchOutlined /></template>查询</a-button>
    <a-button @click="handleReset"><template #icon><ReloadOutlined /></template>重置</a-button>
  </div>
</template>

<script setup lang="ts">
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { reactive } from 'vue'

export interface SearchField {
  key: string
  type: 'input' | 'select'
  placeholder: string
  options?: string[]
}

defineProps<{ fields: SearchField[]; containerClass?: string }>()
const emit = defineEmits<{
  search: [values: Record<string, string | undefined>]
  reset: []
}>()
const model = reactive<Record<string, string | undefined>>({})

const emitSearch = () => emit('search', { ...model })

const handleReset = () => {
  for (const key of Object.keys(model)) delete model[key]
  emit('reset')
}
</script>
