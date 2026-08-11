<template>
  <section class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <div class="checkin-filters checkin-filters--records">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索学号或姓名" @press-enter="search" />
        <a-select v-model:value="filters.dormitoryId" allow-clear placeholder="所属宿舍">
          <a-select-option v-for="dormitory in dormitories" :key="dormitory.id" :value="dormitory.id">
            {{ dormitory.building }} · {{ dormitory.name }}
          </a-select-option>
        </a-select>
        <a-button type="primary" @click="search"><template #icon><SearchOutlined /></template>查询</a-button>
        <a-button @click="resetFilters"><template #icon><ReloadOutlined /></template>重置</a-button>
      </div>
      <a-button v-if="auth.hasPermission('checkin:review')" type="primary" @click="openAssignment">
        <template #icon><PlusOutlined /></template>分配床位
      </a-button>
    </div>

    <a-table
      row-key="id"
      :columns="columns"
      :data-source="store.records"
      :loading="store.loading"
      :pagination="pagination"
      :scroll="{ x: 1050 }"
      @change="handleTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'location'">{{ record.buildingName }} · {{ record.dormitoryName }} · {{ record.bedNo }} 号床</template>
        <template v-else-if="column.key === 'source'">{{ record.applicationId ? `申请 #${record.applicationId}` : '手工分配' }}</template>
        <template v-else-if="column.key === 'checkInDate'">{{ formatDateTime(record.checkInDate) }}</template>
        <template v-else-if="column.key === 'status'"><a-tag color="green">{{ record.status }}</a-tag></template>
        <template v-else-if="column.key === 'actions'"><a-button type="link" size="small" @click="showDetail(record)">查看</a-button></template>
      </template>
    </a-table>

    <a-modal
      v-model:open="assignmentOpen"
      title="分配宿舍床位"
      :confirm-loading="store.saving"
      ok-text="确认分配"
      cancel-text="取消"
      destroy-on-close
      @ok="submitAssignment"
    >
      <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
        <a-form-item label="学生" name="studentId">
          <a-select v-model:value="form.studentId" show-search option-filter-prop="label" placeholder="请选择未入住学生">
            <a-select-option
              v-for="student in availableStudents"
              :key="student.id"
              :value="student.id"
              :label="`${student.studentNo} ${student.name}`"
            >{{ student.studentNo }} · {{ student.name }} · {{ student.gender }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="宿舍" name="dormitoryId">
          <a-select v-model:value="form.dormitoryId" show-search option-filter-prop="label" placeholder="请选择宿舍" @change="dormitoryChanged">
            <a-select-option
              v-for="dormitory in dormitories"
              :key="dormitory.id"
              :value="dormitory.id"
              :label="`${dormitory.building} ${dormitory.name}`"
            >{{ dormitory.building }} · {{ dormitory.name }}（空床 {{ dormitory.vacant }}）</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="床位" name="bedId">
          <a-select v-model:value="form.bedId" :loading="bedsLoading" placeholder="请先选择宿舍">
            <a-select-option v-for="bed in availableBeds" :key="bed.id" :value="bed.id">{{ bed.bedNo }} 号床</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="分配备注"><a-textarea v-model:value="form.remark" :rows="3" :maxlength="255" show-count /></a-form-item>
      </a-form>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import type { FormInstance, TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import { fetchStudents } from '../api/checkin'
import type { CheckInRecordQuery } from '../api/checkin'
import { fetchDormitoryPage } from '../api/dormitory'
import { fetchBeds } from '../api/resource'
import type { Bed } from '../api/resource'
import { useAuthStore } from '../stores/auth'
import { useCheckInStore } from '../stores/checkin'
import type { CheckInRecord, Dormitory, Student } from '../types/dormitory'

const auth = useAuthStore()
const store = useCheckInStore()
const currentPage = ref(1)
const pageSize = ref(10)
const assignmentOpen = ref(false)
const formRef = ref<FormInstance>()
const dormitories = ref<Dormitory[]>([])
const availableStudents = ref<Student[]>([])
const availableBeds = ref<Bed[]>([])
const bedsLoading = ref(false)
const filters = reactive<{ keyword?: string; dormitoryId?: number }>({})
const form = reactive<{ studentId?: number; dormitoryId?: number; bedId?: number; remark?: string }>({})

const columns: TableColumnsType = [
  { title: '学号', dataIndex: 'studentNo', key: 'studentNo', width: 140 },
  { title: '姓名', dataIndex: 'studentName', key: 'studentName', width: 110 },
  { title: '分配床位', key: 'location', width: 260 },
  { title: '分配来源', key: 'source', width: 130 },
  { title: '入住时间', key: 'checkInDate', width: 170 },
  { title: '状态', key: 'status', width: 100 },
  { title: '操作', key: 'actions', width: 100, fixed: 'right' },
]
const rules = {
  studentId: [{ required: true, message: '请选择学生' }],
  dormitoryId: [{ required: true, message: '请选择宿舍' }],
  bedId: [{ required: true, message: '请选择床位' }],
}
const query = computed<CheckInRecordQuery>(() => ({
  page: currentPage.value,
  pageSize: pageSize.value,
  status: '在住',
  ...filters,
}))
const pagination = computed<TablePaginationConfig>(() => ({
  current: currentPage.value,
  pageSize: pageSize.value,
  total: store.recordTotal,
  showSizeChanger: true,
  showTotal: (total) => `共 ${total} 条`,
}))

async function load() {
  try { await store.loadRecords(query.value) }
  catch (error) { message.error(error instanceof Error ? error.message : '分配记录加载失败') }
}
async function loadOptions() {
  try {
    const [students, dormitoryPage] = await Promise.all([
      fetchStudents({ page: 1, pageSize: 100, checkInStatus: '未入住' }),
      fetchDormitoryPage({ page: 1, pageSize: 100 }),
    ])
    availableStudents.value = students.records
    dormitories.value = dormitoryPage.records
  } catch (error) {
    message.error(error instanceof Error ? error.message : '分配选项加载失败')
  }
}
function search() { currentPage.value = 1; void load() }
function resetFilters() { Object.assign(filters, { keyword: undefined, dormitoryId: undefined }); currentPage.value = 1; void load() }
function handleTableChange(page: TablePaginationConfig) {
  currentPage.value = page.current ?? 1
  pageSize.value = page.pageSize ?? 10
  void load()
}
function openAssignment() {
  Object.assign(form, { studentId: undefined, dormitoryId: undefined, bedId: undefined, remark: undefined })
  availableBeds.value = []
  assignmentOpen.value = true
}
async function dormitoryChanged(dormitoryId: number) {
  form.bedId = undefined
  bedsLoading.value = true
  try {
    const beds = await fetchBeds({ page: 1, pageSize: 100, dormitoryId, status: '空闲' })
    availableBeds.value = beds.records
  } catch (error) {
    message.error(error instanceof Error ? error.message : '空闲床位加载失败')
  } finally {
    bedsLoading.value = false
  }
}
async function submitAssignment() {
  await formRef.value?.validate()
  try {
    await store.assign(form.studentId!, form.bedId!, form.remark, query.value)
    assignmentOpen.value = false
    await loadOptions()
    message.success('床位分配成功')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '床位分配失败')
  }
}
function formatDateTime(value?: string) { return value ? value.replace('T', ' ').slice(0, 16) : '-' }
function showDetail(record: CheckInRecord) {
  Modal.info({
    title: `${record.studentName} · ${record.studentNo}`,
    content: `${record.buildingName} · ${record.dormitoryName} · ${record.bedNo} 号床，入住时间 ${formatDateTime(record.checkInDate)}。${record.remark || '无备注'}`,
    okText: '关闭',
  })
}

onMounted(() => { void Promise.all([load(), loadOptions()]) })
</script>
