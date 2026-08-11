<template>
  <section class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <SearchForm
        :fields="searchFields"
        container-class="operations-filters operations-filters--compact"
        @search="search"
        @reset="reset"
      />
    </div>
    <DataTable :columns="columns" :rows="store.repairRecords" :loading="store.loading"
      :pagination="pagination" :scroll="{ x: 980 }" @change="handleTableChange">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'cost'">{{ Number(record.cost).toFixed(2) }}</template>
        <template v-else-if="column.key === 'status'"><a-tag color="green">{{ record.status }}</a-tag></template>
        <template v-else-if="column.key === 'handledAt'">{{ formatDateTime(record.handledAt) }}</template>
      </template>
    </DataTable>
  </section>
</template>

<script setup lang="ts">
import { message } from 'ant-design-vue'
import type { TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, ref } from 'vue'
import DataTable from '../components/business/DataTable.vue'
import SearchForm from '../components/business/SearchForm.vue'
import type { SearchField } from '../components/business/SearchForm.vue'
import { useOperationsStore } from '../stores/operations'

const store = useOperationsStore()
const keyword = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const searchFields: SearchField[] = [{ key: 'keyword', type: 'input', placeholder: '搜索位置、处理人或维修内容' }]
const columns: TableColumnsType = [
  { title: '宿舍位置', dataIndex: 'location', key: 'location', width: 180 },
  { title: '维修人员', dataIndex: 'handler', key: 'handler', width: 130 },
  { title: '维修内容', dataIndex: 'content', key: 'content', width: 260 },
  { title: '维修费用', key: 'cost', width: 110 },
  { title: '状态', key: 'status', width: 100 },
  { title: '完成/记录时间', key: 'handledAt', width: 170 },
]
const query = computed(() => ({ page: currentPage.value, pageSize: pageSize.value, keyword: keyword.value || undefined }))
const pagination = computed<TablePaginationConfig>(() => ({ current: currentPage.value, pageSize: pageSize.value,
  total: store.repairRecordTotal, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }))
async function load() { try { await store.loadRepairRecords(query.value) } catch (error) { message.error(error instanceof Error ? error.message : '维修记录加载失败') } }
function search(values: Record<string, string | undefined>) { keyword.value = values.keyword ?? ''; currentPage.value = 1; void load() }
function reset() { keyword.value = ''; currentPage.value = 1; void load() }
function handleTableChange(page: TablePaginationConfig) { currentPage.value = page.current ?? 1; pageSize.value = page.pageSize ?? 10; void load() }
function formatDateTime(value?: string) { return value ? value.replace('T', ' ').slice(0, 16) : '-' }
onMounted(load)
</script>
