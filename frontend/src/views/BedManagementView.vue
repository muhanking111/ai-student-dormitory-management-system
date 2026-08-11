<template>
  <section class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <div class="resource-filters resource-filters--beds">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索床位号" @press-enter="search" />
        <a-select v-model:value="filters.buildingId" allow-clear placeholder="所属楼栋" @change="buildingChanged">
          <a-select-option v-for="building in store.buildingOptions" :key="building.id" :value="building.id">{{ building.name }}</a-select-option>
        </a-select>
        <a-select v-model:value="filters.dormitoryId" allow-clear placeholder="所属宿舍">
          <a-select-option v-for="dormitory in store.dormitories" :key="dormitory.id" :value="dormitory.id">{{ dormitory.name }}</a-select-option>
        </a-select>
        <a-select v-model:value="filters.status" allow-clear placeholder="床位状态">
          <a-select-option v-for="status in statuses" :key="status" :value="status">{{ status }}</a-select-option>
        </a-select>
        <a-button type="primary" @click="search"><template #icon><SearchOutlined /></template>查询</a-button>
        <a-button @click="resetFilters"><template #icon><ReloadOutlined /></template>重置</a-button>
      </div>
    </div>
    <a-table row-key="id" :columns="columns" :data-source="store.beds" :loading="store.loading" :pagination="pagination" :scroll="{ x: 900 }" @change="handleTableChange">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'studentName'">{{ record.studentName || (record.status === '已占用' ? '待关联' : '-') }}</template>
        <template v-else-if="column.key === 'status'"><a-tag :color="statusColor(record.status)">{{ record.status }}</a-tag></template>
        <template v-else-if="column.key === 'actions'">
          <a-button v-if="record.status !== '已占用' && auth.hasPermission('dormitory:write')" type="link" size="small" @click="openStatus(record)">变更状态</a-button>
          <span v-else>-</span>
        </template>
      </template>
    </a-table>
    <a-modal v-model:open="statusOpen" title="变更床位状态" :confirm-loading="saving" ok-text="确定" cancel-text="取消" @ok="saveStatus">
      <a-form layout="vertical"><a-form-item label="床位状态"><a-segmented v-model:value="nextStatus" :options="['空闲', '维修中', '停用']" /></a-form-item></a-form>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import type { TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import type { Bed } from '../api/resource'
import { useAuthStore } from '../stores/auth'
import { useResourceStore } from '../stores/resource'

const auth = useAuthStore(); const store = useResourceStore(); const statuses = ['空闲', '已占用', '维修中', '停用']
const currentPage = ref(1); const pageSize = ref(10); const statusOpen = ref(false); const editingBed = ref<Bed | null>(null); const nextStatus = ref<Bed['status']>('空闲'); const saving = ref(false)
const filters = reactive<{ keyword?: string; buildingId?: number; dormitoryId?: number; status?: string }>({})
const columns: TableColumnsType = [
  { title: '所属楼栋', dataIndex: 'buildingName', key: 'buildingName', width: 150 }, { title: '所属宿舍', dataIndex: 'dormitoryName', key: 'dormitoryName', width: 150 },
  { title: '床位号', dataIndex: 'bedNo', key: 'bedNo', width: 100 }, { title: '入住学生', key: 'studentName' },
  { title: '状态', key: 'status', width: 110 }, { title: '操作', key: 'actions', width: 130, fixed: 'right' },
]
const query = computed(() => ({ page: currentPage.value, pageSize: pageSize.value, ...filters }))
const pagination = computed<TablePaginationConfig>(() => ({ current: currentPage.value, pageSize: pageSize.value, total: store.bedTotal, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }))
async function load() { try { await store.loadBeds(query.value) } catch (error) { message.error(error instanceof Error ? error.message : '床位数据加载失败') } }
function search() { currentPage.value = 1; void load() }
function resetFilters() { Object.assign(filters, { keyword: undefined, buildingId: undefined, dormitoryId: undefined, status: undefined }); currentPage.value = 1; void load() }
function buildingChanged() { filters.dormitoryId = undefined; currentPage.value = 1; void load() }
function handleTableChange(page: TablePaginationConfig) { currentPage.value = page.current ?? 1; pageSize.value = page.pageSize ?? 10; void load() }
function statusColor(status: Bed['status']) { return ({ 空闲: 'green', 已占用: 'blue', 维修中: 'orange', 停用: 'default' } as const)[status] }
function openStatus(bed: Bed) { editingBed.value = bed; nextStatus.value = bed.status; statusOpen.value = true }
async function saveStatus() { if (!editingBed.value) return; saving.value = true; try { await store.setBedStatus(editingBed.value.id, nextStatus.value, query.value); statusOpen.value = false; message.success('床位状态已更新') } catch (error) { message.error(error instanceof Error ? error.message : '状态更新失败') } finally { saving.value = false } }
onMounted(load)
</script>
