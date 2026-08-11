<template>
  <section class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <div class="operations-filters operations-filters--compact">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索宿舍或楼栋" @press-enter="search" />
        <a-select v-model:value="filters.result" allow-clear placeholder="检查结果">
          <a-select-option value="优秀">优秀</a-select-option>
          <a-select-option value="良好">良好</a-select-option>
          <a-select-option value="一般">一般</a-select-option>
          <a-select-option value="不合格">不合格</a-select-option>
        </a-select>
        <a-button type="primary" @click="search"><template #icon><SearchOutlined /></template>查询</a-button>
        <a-button @click="reset"><template #icon><ReloadOutlined /></template>重置</a-button>
      </div>
    </div>
    <a-table row-key="id" :columns="columns" :data-source="store.hygieneChecks" :loading="store.loading"
      :pagination="pagination" :scroll="{ x: 1050 }" @change="handleTableChange">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'result'"><a-tag :color="resultColor(record.result)">{{ record.result }}</a-tag></template>
        <template v-else-if="column.key === 'remark'">{{ record.remark || '-' }}</template>
      </template>
    </a-table>
  </section>
</template>

<script setup lang="ts">
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import type { TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import type { OperationQuery } from '../api/operations'
import { useOperationsStore } from '../stores/operations'
import type { HygieneCheck } from '../types/dormitory'

const store = useOperationsStore()
const currentPage = ref(1)
const pageSize = ref(10)
const filters = reactive<Pick<OperationQuery, 'keyword' | 'result'>>({})
const columns: TableColumnsType = [
  { title: '宿舍名称', dataIndex: 'dormitory', key: 'dormitory', width: 160 },
  { title: '所属楼栋', dataIndex: 'building', key: 'building', width: 130 },
  { title: '检查时间', dataIndex: 'date', key: 'date', width: 130 },
  { title: '检查人员', dataIndex: 'inspector', key: 'inspector', width: 130 },
  { title: '卫生评分', dataIndex: 'score', key: 'score', width: 110 },
  { title: '检查结果', key: 'result', width: 110 },
  { title: '检查备注', key: 'remark', width: 260 },
]
const query = computed<OperationQuery>(() => ({ page: currentPage.value, pageSize: pageSize.value, ...filters }))
const pagination = computed<TablePaginationConfig>(() => ({
  current: currentPage.value,
  pageSize: pageSize.value,
  total: store.hygieneTotal,
  showSizeChanger: true,
  showTotal: (total) => `共 ${total} 条`,
}))
function resultColor(result: HygieneCheck['result']) { return ({ 优秀: 'green', 良好: 'blue', 一般: 'orange', 不合格: 'red' } as const)[result] }
async function load() { try { await store.loadHygieneChecks(query.value) } catch (error) { message.error(error instanceof Error ? error.message : '检查记录加载失败') } }
function search() { currentPage.value = 1; void load() }
function reset() { Object.assign(filters, { keyword: undefined, result: undefined }); currentPage.value = 1; void load() }
function handleTableChange(page: TablePaginationConfig) { currentPage.value = page.current ?? 1; pageSize.value = page.pageSize ?? 10; void load() }
onMounted(load)
</script>
