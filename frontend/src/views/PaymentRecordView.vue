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
    <DataTable :columns="columns" :rows="store.paymentRecords" :loading="store.loading"
      :pagination="pagination" :scroll="{ x: 980 }" @change="handleTableChange">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'amount'">{{ Number(record.amount).toFixed(2) }}</template>
        <template v-else-if="column.key === 'operator'">{{ record.operatorName || (record.operatorUserId ? `用户 #${record.operatorUserId}` : '-') }}</template>
        <template v-else-if="column.key === 'paidAt'">{{ formatDateTime(record.paidAt) }}</template>
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
const searchFields: SearchField[] = [{ key: 'keyword', type: 'input', placeholder: '搜索学号、姓名、费用类型或缴费方式' }]
const columns: TableColumnsType = [
  { title: '学号', dataIndex: 'studentNo', key: 'studentNo', width: 140 },
  { title: '姓名', dataIndex: 'name', key: 'name', width: 110 },
  { title: '费用类型', dataIndex: 'type', key: 'type', width: 120 },
  { title: '实缴金额', key: 'amount', width: 120 },
  { title: '缴费方式', dataIndex: 'method', key: 'method', width: 120 },
  { title: '操作人', key: 'operator', width: 130 },
  { title: '缴费时间', key: 'paidAt', width: 170 },
]
const query = computed(() => ({ page: currentPage.value, pageSize: pageSize.value, keyword: keyword.value || undefined }))
const pagination = computed<TablePaginationConfig>(() => ({ current: currentPage.value, pageSize: pageSize.value,
  total: store.paymentRecordTotal, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }))
async function load() { try { await store.loadPaymentRecords(query.value) } catch (error) { message.error(error instanceof Error ? error.message : '收费记录加载失败') } }
function search(values: Record<string, string | undefined>) { keyword.value = values.keyword ?? ''; currentPage.value = 1; void load() }
function reset() { keyword.value = ''; currentPage.value = 1; void load() }
function handleTableChange(page: TablePaginationConfig) { currentPage.value = page.current ?? 1; pageSize.value = page.pageSize ?? 10; void load() }
function formatDateTime(value?: string) { return value ? value.replace('T', ' ').slice(0, 16) : '-' }
onMounted(load)
</script>
