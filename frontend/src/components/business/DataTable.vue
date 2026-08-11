<template>
  <a-table
    :row-key="rowKey"
    :columns="columns"
    :data-source="rows"
    :loading="loading"
    :pagination="pagination"
    :scroll="scroll"
    @change="handleChange"
  >
    <template #bodyCell="scope">
      <slot name="bodyCell" v-bind="scope">
        {{ scope.text }}
      </slot>
    </template>
  </a-table>
</template>

<script setup lang="ts">
import type { TableColumnsType, TablePaginationConfig, TableProps } from 'ant-design-vue'

withDefaults(defineProps<{
  columns: TableColumnsType
  rows: unknown[]
  loading?: boolean
  pagination?: TableProps['pagination']
  scroll?: TableProps['scroll']
  rowKey?: string
}>(), { rowKey: 'id' })

const emit = defineEmits<{
  change: [pagination: TablePaginationConfig]
}>()

const handleChange = (pagination: TablePaginationConfig) => emit('change', pagination)
</script>
