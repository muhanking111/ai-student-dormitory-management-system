<template>
  <section class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <div class="management-filters">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索楼栋编码、名称或管理员" @press-enter="search" />
        <a-select v-model:value="filters.status" allow-clear placeholder="楼栋状态">
          <a-select-option value="启用">启用</a-select-option>
          <a-select-option value="停用">停用</a-select-option>
        </a-select>
        <a-button type="primary" @click="search"><template #icon><SearchOutlined /></template>查询</a-button>
        <a-button @click="resetFilters"><template #icon><ReloadOutlined /></template>重置</a-button>
      </div>
      <a-button v-if="auth.hasPermission('dormitory:write')" type="primary" @click="openCreate">
        <template #icon><PlusOutlined /></template>新增楼栋
      </a-button>
    </div>

    <a-table row-key="id" :columns="columns" :data-source="store.buildings" :loading="store.loading"
      :pagination="pagination" :scroll="{ x: 900 }" @change="handleTableChange">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <a-badge :status="record.status === '启用' ? 'success' : 'default'" :text="record.status" />
        </template>
        <template v-else-if="column.key === 'actions'">
          <template v-if="auth.hasPermission('dormitory:write')">
            <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
            <a-button type="link" size="small" danger @click="confirmDelete(record)">删除</a-button>
          </template>
          <span v-else>-</span>
        </template>
      </template>
    </a-table>

    <ModalForm
      :open="modalOpen"
      :title="editingId ? '编辑楼栋' : '新增楼栋'"
      :model="form"
      :rules="rules"
      :loading="store.saving"
      @cancel="modalOpen = false"
      @submit="submit"
    >
        <div class="role-form-grid">
          <a-form-item label="楼栋编码" name="code">
            <a-input v-model:value="form.code" aria-label="楼栋编码" placeholder="例如 B01" />
          </a-form-item>
          <a-form-item label="楼栋名称" name="name">
            <a-input v-model:value="form.name" aria-label="楼栋名称" placeholder="例如 1号楼" />
          </a-form-item>
          <a-form-item label="住宿类型" name="genderType">
            <a-select v-model:value="form.genderType" aria-label="住宿类型">
              <a-select-option value="男生宿舍">男生宿舍</a-select-option>
              <a-select-option value="女生宿舍">女生宿舍</a-select-option>
              <a-select-option value="混合宿舍">混合宿舍</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="楼层数" name="floors">
            <a-input-number v-model:value="form.floors" aria-label="楼层数" :min="1" :max="50" class="modal-number-input" />
          </a-form-item>
        </div>
        <a-form-item label="楼栋管理员" name="manager">
          <a-input v-model:value="form.manager" aria-label="楼栋管理员" placeholder="可稍后设置" />
        </a-form-item>
        <a-form-item label="楼栋状态" name="status">
          <a-radio-group v-model:value="form.status">
            <a-radio value="启用">启用</a-radio><a-radio value="停用">停用</a-radio>
          </a-radio-group>
        </a-form-item>
    </ModalForm>
  </section>
</template>

<script setup lang="ts">
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import type { FormProps, TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import type { Building, BuildingInput } from '../api/resource'
import ModalForm from '../components/business/ModalForm.vue'
import { useAuthStore } from '../stores/auth'
import { useResourceStore } from '../stores/resource'

const auth = useAuthStore()
const store = useResourceStore()
const currentPage = ref(1)
const pageSize = ref(10)
const modalOpen = ref(false)
const editingId = ref<number | null>(null)
const filters = reactive<{ keyword?: string; status?: string }>({})
const form = reactive<BuildingInput>({ code: '', name: '', genderType: '男生宿舍', floors: 6, manager: '', status: '启用' })

const columns: TableColumnsType = [
  { title: '楼栋编码', dataIndex: 'code', key: 'code', width: 130 },
  { title: '楼栋名称', dataIndex: 'name', key: 'name', width: 160 },
  { title: '住宿类型', dataIndex: 'genderType', key: 'genderType', width: 140 },
  { title: '楼层数', dataIndex: 'floors', key: 'floors', width: 100 },
  { title: '楼栋管理员', dataIndex: 'manager', key: 'manager' },
  { title: '状态', key: 'status', width: 100 },
  { title: '操作', key: 'actions', width: 140, fixed: 'right' },
]
const rules: FormProps['rules'] = {
  code: [{ required: true, message: '请输入楼栋编码' }, { pattern: /^[A-Za-z0-9-]{2,32}$/, message: '使用字母、数字或连字符' }],
  name: [{ required: true, message: '请输入楼栋名称' }],
  genderType: [{ required: true, message: '请选择住宿类型' }],
  floors: [{ required: true, type: 'number', min: 1, max: 50, message: '楼层数范围为 1-50' }],
}
const query = computed(() => ({ page: currentPage.value, pageSize: pageSize.value, ...filters }))
const pagination = computed<TablePaginationConfig>(() => ({ current: currentPage.value, pageSize: pageSize.value,
  total: store.buildingTotal, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }))

async function load() { try { await store.loadBuildings(query.value) } catch (error) { message.error(error instanceof Error ? error.message : '楼栋数据加载失败') } }
function search() { currentPage.value = 1; void load() }
function resetFilters() { filters.keyword = undefined; filters.status = undefined; currentPage.value = 1; void load() }
function handleTableChange(page: TablePaginationConfig) { currentPage.value = page.current ?? 1; pageSize.value = page.pageSize ?? 10; void load() }
function resetForm() { Object.assign(form, { code: '', name: '', genderType: '男生宿舍', floors: 6, manager: '', status: '启用' }) }
function openCreate() { editingId.value = null; resetForm(); modalOpen.value = true }
function openEdit(building: Building) { editingId.value = building.id; Object.assign(form, building); modalOpen.value = true }
async function submit() {
  try { await store.saveBuilding(editingId.value, { ...form }, query.value); modalOpen.value = false; message.success(editingId.value ? '楼栋已更新' : '楼栋已新增') }
  catch (error) { message.error(error instanceof Error ? error.message : '楼栋保存失败') }
}
function confirmDelete(building: Building) {
  Modal.confirm({ title: `确认删除${building.name}？`, content: '楼栋下仍有宿舍时不能删除。', okText: '删除', okType: 'danger', cancelText: '取消',
    async onOk() { await store.removeBuilding(building.id, query.value); message.success('楼栋已删除') } })
}
onMounted(load)
</script>
