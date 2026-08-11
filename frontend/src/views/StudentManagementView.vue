<template>
  <section class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <div class="checkin-filters checkin-filters--students">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索学号或姓名" @press-enter="search" />
        <a-input v-model:value="filters.college" allow-clear placeholder="所属学院" @press-enter="search" />
        <a-input v-model:value="filters.grade" allow-clear placeholder="年级" @press-enter="search" />
        <a-select v-model:value="filters.checkInStatus" allow-clear placeholder="入住状态">
          <a-select-option value="已入住">已入住</a-select-option>
          <a-select-option value="未入住">未入住</a-select-option>
        </a-select>
        <a-button type="primary" @click="search"><template #icon><SearchOutlined /></template>查询</a-button>
        <a-button @click="resetFilters"><template #icon><ReloadOutlined /></template>重置</a-button>
      </div>
      <a-button v-if="auth.hasPermission('student:write')" type="primary" @click="openCreate">
        <template #icon><PlusOutlined /></template>新增学生
      </a-button>
    </div>

    <a-table
      row-key="id"
      :columns="columns"
      :data-source="store.students"
      :loading="store.loading"
      :pagination="pagination"
      :scroll="{ x: 1050 }"
      @change="handleTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'checkInStatus'">
          <a-tag :color="record.checkInStatus === '已入住' ? 'green' : 'default'">{{ record.checkInStatus }}</a-tag>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-button type="link" size="small" @click="showDetail(record)">查看</a-button>
          <template v-if="auth.hasPermission('student:write')">
            <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
            <a-button type="link" size="small" danger @click="confirmDelete(record)">删除</a-button>
          </template>
        </template>
      </template>
    </a-table>

    <a-modal
      v-model:open="modalOpen"
      :title="editingId ? '编辑学生' : '新增学生'"
      :confirm-loading="store.saving"
      ok-text="确定"
      cancel-text="取消"
      destroy-on-close
      @ok="submit"
    >
      <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
        <div class="role-form-grid">
          <a-form-item label="学号" name="studentNo"><a-input v-model:value="form.studentNo" aria-label="学号" /></a-form-item>
          <a-form-item label="姓名" name="name"><a-input v-model:value="form.name" aria-label="姓名" /></a-form-item>
          <a-form-item label="性别" name="gender">
            <a-segmented v-model:value="form.gender" :options="['男', '女']" block />
          </a-form-item>
          <a-form-item label="年级" name="grade"><a-input v-model:value="form.grade" aria-label="年级" /></a-form-item>
          <a-form-item label="学院" name="college"><a-input v-model:value="form.college" aria-label="学院" /></a-form-item>
          <a-form-item label="手机号" name="phone"><a-input v-model:value="form.phone" aria-label="手机号" /></a-form-item>
        </div>
      </a-form>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import type { FormInstance, TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import type { StudentInput, StudentQuery } from '../api/checkin'
import { useAuthStore } from '../stores/auth'
import { useCheckInStore } from '../stores/checkin'
import type { Student } from '../types/dormitory'

const auth = useAuthStore()
const store = useCheckInStore()
const currentPage = ref(1)
const pageSize = ref(10)
const modalOpen = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const filters = reactive<Omit<StudentQuery, 'page' | 'pageSize'>>({})
const form = reactive<StudentInput>({ studentNo: '', name: '', gender: '男', college: '', grade: '', phone: '' })

const columns: TableColumnsType = [
  { title: '学号', dataIndex: 'studentNo', key: 'studentNo', width: 140 },
  { title: '姓名', dataIndex: 'name', key: 'name', width: 110 },
  { title: '性别', dataIndex: 'gender', key: 'gender', width: 80 },
  { title: '学院', dataIndex: 'college', key: 'college', width: 180 },
  { title: '年级', dataIndex: 'grade', key: 'grade', width: 100 },
  { title: '手机号', dataIndex: 'phone', key: 'phone', width: 150 },
  { title: '入住状态', key: 'checkInStatus', width: 110 },
  { title: '操作', key: 'actions', width: 180, fixed: 'right' },
]
const rules = {
  studentNo: [{ required: true, message: '请输入学号' }, { pattern: /^[A-Za-z0-9-]{2,32}$/, message: '学号格式不合法' }],
  name: [{ required: true, message: '请输入姓名' }],
  gender: [{ required: true, message: '请选择性别' }],
  college: [{ required: true, message: '请输入学院' }],
  grade: [{ required: true, message: '请输入年级' }],
  phone: [{ required: true, message: '请输入手机号' }, { pattern: /^[0-9+ -]{6,32}$/, message: '手机号格式不合法' }],
}
const query = computed<StudentQuery>(() => ({ page: currentPage.value, pageSize: pageSize.value, ...filters }))
const pagination = computed<TablePaginationConfig>(() => ({
  current: currentPage.value,
  pageSize: pageSize.value,
  total: store.studentTotal,
  showSizeChanger: true,
  showTotal: (total) => `共 ${total} 条`,
}))

async function load() {
  try { await store.loadStudents(query.value) }
  catch (error) { message.error(error instanceof Error ? error.message : '学生数据加载失败') }
}
function search() { currentPage.value = 1; void load() }
function resetFilters() {
  Object.assign(filters, { keyword: undefined, college: undefined, grade: undefined, checkInStatus: undefined })
  currentPage.value = 1
  void load()
}
function handleTableChange(page: TablePaginationConfig) {
  currentPage.value = page.current ?? 1
  pageSize.value = page.pageSize ?? 10
  void load()
}
function openCreate() {
  editingId.value = null
  Object.assign(form, { studentNo: '', name: '', gender: '男', college: '', grade: '', phone: '' })
  modalOpen.value = true
}
function openEdit(student: Student) {
  editingId.value = student.id
  Object.assign(form, student)
  modalOpen.value = true
}
function showDetail(student: Student) {
  Modal.info({
    title: `${student.name} · ${student.studentNo}`,
    content: `${student.college} / ${student.grade} 级 / ${student.gender} / ${student.phone} / ${student.checkInStatus}`,
    okText: '关闭',
  })
}
async function submit() {
  await formRef.value?.validate()
  try {
    await store.saveStudent(editingId.value, { ...form }, query.value)
    modalOpen.value = false
    message.success(editingId.value ? '学生信息已更新' : '学生已新增')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '学生信息保存失败')
  }
}
function confirmDelete(student: Student) {
  Modal.confirm({
    title: `确认删除 ${student.name}？`,
    content: '存在入住申请或入住记录时不能删除。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      try {
        await store.removeStudent(student.id, query.value)
        message.success('学生已删除')
      } catch (error) {
        message.error(error instanceof Error ? error.message : '学生删除失败')
      }
    },
  })
}

onMounted(load)
</script>
