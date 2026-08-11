<template>
  <section class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <div class="checkin-filters">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索学号、姓名或宿舍" @press-enter="search" />
        <a-select v-model:value="filters.dormitoryId" allow-clear placeholder="申请宿舍">
          <a-select-option v-for="dormitory in dormitories" :key="dormitory.id" :value="dormitory.id">
            {{ dormitory.building }} · {{ dormitory.name }}
          </a-select-option>
        </a-select>
        <a-select v-model:value="filters.status" allow-clear placeholder="申请状态">
          <a-select-option value="待审核">待审核</a-select-option>
          <a-select-option value="已通过">已通过</a-select-option>
          <a-select-option value="已拒绝">已拒绝</a-select-option>
        </a-select>
        <a-button type="primary" @click="search"><template #icon><SearchOutlined /></template>查询</a-button>
        <a-button @click="resetFilters"><template #icon><ReloadOutlined /></template>重置</a-button>
      </div>
      <a-button v-if="auth.hasPermission('checkin:review')" type="primary" @click="openCreate">
        <template #icon><PlusOutlined /></template>新建申请
      </a-button>
    </div>

    <a-table
      row-key="id"
      :columns="columns"
      :data-source="store.applications"
      :loading="store.loading"
      :pagination="pagination"
      :scroll="{ x: 1100 }"
      @change="handleTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'studentName'">{{ record.studentName || record.name || '-' }}</template>
        <template v-else-if="column.key === 'dormitoryName'">
          {{ record.buildingName ? `${record.buildingName} · ` : '' }}{{ record.dormitoryName || record.dormitory || '-' }}
        </template>
        <template v-else-if="column.key === 'status'">
          <a-tag :color="applicationColor(record.status)">{{ record.status }}</a-tag>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-button type="link" size="small" @click="showDetail(record)">查看</a-button>
          <a-button
            v-if="record.status === '待审核' && record.studentId && record.dormitoryId && auth.hasPermission('checkin:review')"
            type="link"
            size="small"
            @click="openReview(record)"
          >审核</a-button>
        </template>
      </template>
    </a-table>

    <a-modal
      v-model:open="createOpen"
      title="新建入住申请"
      :confirm-loading="store.saving"
      ok-text="提交申请"
      cancel-text="取消"
      destroy-on-close
      @ok="submitApplication"
    >
      <a-form ref="createFormRef" :model="createForm" :rules="createRules" layout="vertical">
        <a-form-item label="申请学生" name="studentId">
          <a-select v-model:value="createForm.studentId" show-search option-filter-prop="label" placeholder="请选择未入住学生">
            <a-select-option
              v-for="student in availableStudents"
              :key="student.id"
              :value="student.id"
              :label="`${student.studentNo} ${student.name}`"
            >{{ student.studentNo }} · {{ student.name }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="申请宿舍" name="dormitoryId">
          <a-select v-model:value="createForm.dormitoryId" show-search option-filter-prop="label" placeholder="请选择宿舍">
            <a-select-option
              v-for="dormitory in dormitories"
              :key="dormitory.id"
              :value="dormitory.id"
              :label="`${dormitory.building} ${dormitory.name}`"
            >{{ dormitory.building }} · {{ dormitory.name }}（空床 {{ dormitory.vacant }}）</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="申请备注" name="remark"><a-textarea v-model:value="createForm.remark" :rows="3" :maxlength="255" show-count /></a-form-item>
      </a-form>
    </a-modal>

    <a-modal v-model:open="reviewOpen" title="审核入住申请" :confirm-loading="store.saving" destroy-on-close>
      <a-descriptions v-if="reviewing" :column="1" size="small" bordered class="review-summary">
        <a-descriptions-item label="学生">{{ reviewing.studentNo }} · {{ reviewing.studentName || reviewing.name }}</a-descriptions-item>
        <a-descriptions-item label="申请宿舍">{{ reviewing.buildingName }} · {{ reviewing.dormitoryName || reviewing.dormitory }}</a-descriptions-item>
        <a-descriptions-item label="申请时间">{{ reviewing.date }}</a-descriptions-item>
      </a-descriptions>
      <a-form layout="vertical">
        <a-form-item label="分配床位" required>
          <a-select v-model:value="reviewForm.bedId" :loading="bedsLoading" placeholder="请选择该宿舍的空闲床位">
            <a-select-option v-for="bed in availableBeds" :key="bed.id" :value="bed.id">{{ bed.bedNo }} 号床</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="审核备注"><a-textarea v-model:value="reviewForm.remark" :rows="3" :maxlength="255" show-count /></a-form-item>
      </a-form>
      <template #footer>
        <a-button @click="reviewOpen = false">取消</a-button>
        <a-button danger :loading="store.saving" @click="reject">拒绝</a-button>
        <a-button type="primary" :loading="store.saving" @click="approve">通过并分配</a-button>
      </template>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import type { FormInstance, TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { fetchStudents } from '../api/checkin'
import type { CheckInApplicationQuery } from '../api/checkin'
import { fetchDormitoryPage } from '../api/dormitory'
import { fetchBeds } from '../api/resource'
import type { Bed } from '../api/resource'
import { useAuthStore } from '../stores/auth'
import { useCheckInStore } from '../stores/checkin'
import type { CheckInApplication, Dormitory, Student } from '../types/dormitory'

const auth = useAuthStore()
const store = useCheckInStore()
const currentPage = ref(1)
const pageSize = ref(10)
const createOpen = ref(false)
const reviewOpen = ref(false)
const createFormRef = ref<FormInstance>()
const reviewing = ref<CheckInApplication | null>(null)
const dormitories = ref<Dormitory[]>([])
const availableStudents = ref<Student[]>([])
const availableBeds = ref<Bed[]>([])
const bedsLoading = ref(false)
const filters = reactive<Omit<CheckInApplicationQuery, 'page' | 'pageSize'>>({})
const createForm = reactive<{ studentId?: number; dormitoryId?: number; remark?: string }>({})
const reviewForm = reactive<{ bedId?: number; remark?: string }>({})

const columns: TableColumnsType = [
  { title: '申请编号', dataIndex: 'id', key: 'id', width: 105 },
  { title: '学号', dataIndex: 'studentNo', key: 'studentNo', width: 140 },
  { title: '姓名', key: 'studentName', width: 110 },
  { title: '申请宿舍', key: 'dormitoryName', width: 210 },
  { title: '申请时间', dataIndex: 'date', key: 'date', width: 130 },
  { title: '申请状态', key: 'status', width: 110 },
  { title: '操作', key: 'actions', width: 150, fixed: 'right' },
]
const createRules = {
  studentId: [{ required: true, message: '请选择申请学生' }],
  dormitoryId: [{ required: true, message: '请选择申请宿舍' }],
}
const query = computed<CheckInApplicationQuery>(() => ({ page: currentPage.value, pageSize: pageSize.value, ...filters }))
const pagination = computed<TablePaginationConfig>(() => ({
  current: currentPage.value,
  pageSize: pageSize.value,
  total: store.applicationTotal,
  showSizeChanger: true,
  showTotal: (total) => `共 ${total} 条`,
}))

function applicationColor(status: CheckInApplication['status']) {
  return ({ 待审核: 'orange', 已通过: 'green', 已拒绝: 'red' } as const)[status]
}
async function load() {
  try { await store.loadApplications(query.value) }
  catch (error) { message.error(error instanceof Error ? error.message : '入住申请加载失败') }
}
async function loadOptions() {
  try {
    const [studentPage, dormitoryPage] = await Promise.all([
      fetchStudents({ page: 1, pageSize: 100, checkInStatus: '未入住' }),
      fetchDormitoryPage({ page: 1, pageSize: 100 }),
    ])
    availableStudents.value = studentPage.records
    dormitories.value = dormitoryPage.records
  } catch (error) {
    message.error(error instanceof Error ? error.message : '申请选项加载失败')
  }
}
function search() { currentPage.value = 1; void load() }
function resetFilters() {
  Object.assign(filters, { keyword: undefined, status: undefined, dormitoryId: undefined })
  currentPage.value = 1
  void load()
}
function handleTableChange(page: TablePaginationConfig) {
  currentPage.value = page.current ?? 1
  pageSize.value = page.pageSize ?? 10
  void load()
}
function openCreate() {
  Object.assign(createForm, { studentId: undefined, dormitoryId: undefined, remark: undefined })
  createOpen.value = true
}
async function submitApplication() {
  await createFormRef.value?.validate()
  try {
    await store.submitApplication(createForm.studentId!, createForm.dormitoryId!, createForm.remark, query.value)
    createOpen.value = false
    await loadOptions()
    message.success('入住申请已提交')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '入住申请提交失败')
  }
}
async function openReview(application: CheckInApplication) {
  reviewing.value = application
  Object.assign(reviewForm, { bedId: undefined, remark: undefined })
  availableBeds.value = []
  reviewOpen.value = true
  bedsLoading.value = true
  try {
    const page = await fetchBeds({ page: 1, pageSize: 100, dormitoryId: application.dormitoryId, status: '空闲' })
    availableBeds.value = page.records
  } catch (error) {
    message.error(error instanceof Error ? error.message : '空闲床位加载失败')
  } finally {
    bedsLoading.value = false
  }
}
async function approve() {
  if (!reviewing.value || !reviewForm.bedId) { message.warning('请选择空闲床位'); return }
  try {
    await store.approveApplication(reviewing.value.id, reviewForm.bedId, reviewForm.remark, query.value)
    reviewOpen.value = false
    await loadOptions()
    message.success('申请已通过并完成床位分配')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '申请审核失败')
  }
}
async function reject() {
  if (!reviewing.value) return
  try {
    await store.rejectApplication(reviewing.value.id, reviewForm.remark, query.value)
    reviewOpen.value = false
    await loadOptions()
    message.success('申请已拒绝')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '申请审核失败')
  }
}
function showDetail(application: CheckInApplication) {
  const bed = application.bedNo ? `，床位 ${application.bedNo}` : ''
  const remark = application.reviewRemark || application.applyRemark || '无备注'
  Modal.info({
    title: `入住申请 #${application.id}`,
    content: `${application.studentNo} · ${application.studentName || application.name}，${application.buildingName || ''} ${application.dormitoryName || application.dormitory}${bed}，${application.status}。备注：${remark}`,
    okText: '关闭',
  })
}

onMounted(() => { void Promise.all([load(), loadOptions()]) })
</script>
