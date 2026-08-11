<template>
  <section class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <div class="checkin-filters">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索学号或姓名" @press-enter="search" />
        <a-select v-model:value="filters.dormitoryId" allow-clear placeholder="所属宿舍">
          <a-select-option v-for="dormitory in dormitories" :key="dormitory.id" :value="dormitory.id">
            {{ dormitory.building }} · {{ dormitory.name }}
          </a-select-option>
        </a-select>
        <a-select v-model:value="filters.status" allow-clear placeholder="入住状态">
          <a-select-option value="在住">在住</a-select-option>
          <a-select-option value="已退宿">已退宿</a-select-option>
        </a-select>
        <a-button type="primary" @click="search"><template #icon><SearchOutlined /></template>查询</a-button>
        <a-button @click="resetFilters"><template #icon><ReloadOutlined /></template>重置</a-button>
      </div>
    </div>

    <a-table
      row-key="id"
      :columns="columns"
      :data-source="store.records"
      :loading="store.loading"
      :pagination="pagination"
      :scroll="{ x: 1150 }"
      @change="handleTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'location'">{{ record.buildingName }} · {{ record.dormitoryName }} · {{ record.bedNo }} 号床</template>
        <template v-else-if="column.key === 'checkInDate'">{{ formatDateTime(record.checkInDate) }}</template>
        <template v-else-if="column.key === 'checkOutDate'">{{ formatDateTime(record.checkOutDate) }}</template>
        <template v-else-if="column.key === 'status'"><a-tag :color="record.status === '在住' ? 'green' : 'default'">{{ record.status }}</a-tag></template>
        <template v-else-if="column.key === 'actions'">
          <a-button type="link" size="small" @click="showDetail(record)">查看</a-button>
          <a-button
            v-if="record.status === '在住' && auth.hasPermission('checkin:review')"
            type="link"
            size="small"
            danger
            @click="openCheckout(record)"
          >办理退宿</a-button>
        </template>
      </template>
    </a-table>

    <a-modal
      v-model:open="checkoutOpen"
      title="办理退宿"
      :confirm-loading="store.saving"
      ok-text="确认退宿"
      ok-type="danger"
      cancel-text="取消"
      destroy-on-close
      @ok="submitCheckout"
    >
      <a-alert
        v-if="checkingOut"
        :message="`${checkingOut.studentName} · ${checkingOut.buildingName} ${checkingOut.dormitoryName} ${checkingOut.bedNo} 号床`"
        description="确认后将立即释放床位，并把学生状态更新为未入住。"
        type="warning"
        show-icon
        class="checkout-alert"
      />
      <a-form layout="vertical">
        <a-form-item label="退宿备注"><a-textarea v-model:value="checkoutRemark" :rows="3" :maxlength="255" show-count /></a-form-item>
      </a-form>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import type { TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import type { CheckInRecordQuery } from '../api/checkin'
import { fetchDormitoryPage } from '../api/dormitory'
import { useAuthStore } from '../stores/auth'
import { useCheckInStore } from '../stores/checkin'
import type { CheckInRecord, Dormitory } from '../types/dormitory'

const auth = useAuthStore()
const store = useCheckInStore()
const currentPage = ref(1)
const pageSize = ref(10)
const checkoutOpen = ref(false)
const checkingOut = ref<CheckInRecord | null>(null)
const checkoutRemark = ref('')
const dormitories = ref<Dormitory[]>([])
const filters = reactive<Omit<CheckInRecordQuery, 'page' | 'pageSize'>>({ status: '在住' })

const columns: TableColumnsType = [
  { title: '学号', dataIndex: 'studentNo', key: 'studentNo', width: 140 },
  { title: '姓名', dataIndex: 'studentName', key: 'studentName', width: 110 },
  { title: '当前床位', key: 'location', width: 250 },
  { title: '入住时间', key: 'checkInDate', width: 170 },
  { title: '退宿时间', key: 'checkOutDate', width: 170 },
  { title: '状态', key: 'status', width: 100 },
  { title: '操作', key: 'actions', width: 170, fixed: 'right' },
]
const query = computed<CheckInRecordQuery>(() => ({ page: currentPage.value, pageSize: pageSize.value, ...filters }))
const pagination = computed<TablePaginationConfig>(() => ({
  current: currentPage.value,
  pageSize: pageSize.value,
  total: store.recordTotal,
  showSizeChanger: true,
  showTotal: (total) => `共 ${total} 条`,
}))

async function load() {
  try { await store.loadRecords(query.value) }
  catch (error) { message.error(error instanceof Error ? error.message : '退宿记录加载失败') }
}
async function loadDormitories() {
  try { dormitories.value = (await fetchDormitoryPage({ page: 1, pageSize: 100 })).records }
  catch (error) { message.error(error instanceof Error ? error.message : '宿舍选项加载失败') }
}
function search() { currentPage.value = 1; void load() }
function resetFilters() {
  Object.assign(filters, { keyword: undefined, dormitoryId: undefined, status: '在住' })
  currentPage.value = 1
  void load()
}
function handleTableChange(page: TablePaginationConfig) {
  currentPage.value = page.current ?? 1
  pageSize.value = page.pageSize ?? 10
  void load()
}
function openCheckout(record: CheckInRecord) {
  checkingOut.value = record
  checkoutRemark.value = ''
  checkoutOpen.value = true
}
async function submitCheckout() {
  if (!checkingOut.value) return
  try {
    await store.checkout(checkingOut.value.id, checkoutRemark.value || undefined, query.value)
    checkoutOpen.value = false
    message.success('退宿办理完成，床位已释放')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '退宿办理失败')
  }
}
function formatDateTime(value?: string) { return value ? value.replace('T', ' ').slice(0, 16) : '-' }
function showDetail(record: CheckInRecord) {
  Modal.info({
    title: `${record.studentName} · ${record.studentNo}`,
    content: `${record.buildingName} · ${record.dormitoryName} · ${record.bedNo} 号床，${record.status}。入住：${formatDateTime(record.checkInDate)}，退宿：${formatDateTime(record.checkOutDate)}。${record.remark || '无备注'}`,
    okText: '关闭',
  })
}

onMounted(() => { void Promise.all([load(), loadDormitories()]) })
</script>
