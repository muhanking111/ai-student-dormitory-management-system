<template>
  <section class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <div class="resource-filters">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索宿舍名称" @press-enter="search" />
        <a-select v-model:value="filters.buildingId" allow-clear placeholder="所属楼栋">
          <a-select-option v-for="building in store.buildingOptions" :key="building.id" :value="building.id">{{ building.name }}</a-select-option>
        </a-select>
        <a-select v-model:value="filters.type" allow-clear placeholder="宿舍类型">
          <a-select-option value="男生宿舍">男生宿舍</a-select-option><a-select-option value="女生宿舍">女生宿舍</a-select-option><a-select-option value="混合宿舍">混合宿舍</a-select-option>
        </a-select>
        <a-select v-model:value="filters.status" allow-clear placeholder="入住状态">
          <a-select-option value="入住中">入住中</a-select-option><a-select-option value="已满">已满</a-select-option>
        </a-select>
        <a-button type="primary" @click="search"><template #icon><SearchOutlined /></template>查询</a-button>
        <a-button @click="resetFilters"><template #icon><ReloadOutlined /></template>重置</a-button>
      </div>
      <a-button v-if="auth.hasPermission('dormitory:write')" type="primary" @click="openCreate"><template #icon><PlusOutlined /></template>新增宿舍</a-button>
    </div>

    <a-table row-key="id" :columns="columns" :data-source="store.dormitories" :loading="store.loading"
      :pagination="pagination" :scroll="{ x: 980 }" @change="handleTableChange">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'"><a-tag :color="record.status === '已满' ? 'red' : 'green'">{{ record.status }}</a-tag></template>
        <template v-else-if="column.key === 'actions'">
          <a-button type="link" size="small" @click="showDetail(record)">查看</a-button>
          <template v-if="auth.hasPermission('dormitory:write')">
            <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
            <a-button type="link" size="small" danger @click="confirmDelete(record)">删除</a-button>
          </template>
        </template>
      </template>
    </a-table>

    <ModalForm
      :open="modalOpen"
      :title="editingId ? '编辑宿舍' : '新增宿舍'"
      :model="form"
      :rules="rules"
      :loading="store.saving"
      @cancel="modalOpen = false"
      @submit="submit"
    >
        <a-form-item label="宿舍名称" name="name"><a-input v-model:value="form.name" aria-label="宿舍名称" /></a-form-item>
        <div class="role-form-grid">
          <a-form-item label="所属楼栋" name="buildingId">
            <a-select v-model:value="form.buildingId" placeholder="请选择楼栋">
              <a-select-option v-for="building in store.buildingOptions" :key="building.id" :value="building.id">{{ building.name }}</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="宿舍类型" name="type">
            <a-select v-model:value="form.type" placeholder="请选择宿舍类型">
              <a-select-option value="男生宿舍">男生宿舍</a-select-option><a-select-option value="女生宿舍">女生宿舍</a-select-option><a-select-option value="混合宿舍">混合宿舍</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="床位数量" name="beds"><a-input-number v-model:value="form.beds" aria-label="床位数量" :min="1" :max="20" class="modal-number-input" /></a-form-item>
          <a-form-item label="已入住人数">
            <a-input :value="String(form.occupied)" aria-label="已入住人数" disabled />
            <div class="form-hint">入住人数仅由入住与退宿流程维护</div>
          </a-form-item>
        </div>
    </ModalForm>
  </section>
</template>

<script setup lang="ts">
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import type { FormProps, TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import type { DormitoryInput } from '../api/dormitory'
import ModalForm from '../components/business/ModalForm.vue'
import type { Dormitory } from '../types/dormitory'
import { useAuthStore } from '../stores/auth'
import { useDormitoryStore } from '../stores/dormitory'
import { useResourceStore } from '../stores/resource'

const auth = useAuthStore(); const store = useResourceStore(); const dashboardStore = useDormitoryStore()
const currentPage = ref(1); const pageSize = ref(10); const modalOpen = ref(false); const editingId = ref<number | null>(null)
const filters = reactive<{ keyword?: string; buildingId?: number; type?: string; status?: string }>({})
const form = reactive<DormitoryInput>({ name: '', type: '男生宿舍', buildingId: undefined, beds: 6, occupied: 0 })
const columns: TableColumnsType = [
  { title: '宿舍名称', dataIndex: 'name', key: 'name', width: 150 }, { title: '宿舍类型', dataIndex: 'type', key: 'type', width: 130 },
  { title: '所属楼栋', dataIndex: 'building', key: 'building', width: 130 }, { title: '床位数', dataIndex: 'beds', key: 'beds', width: 90 },
  { title: '已入住', dataIndex: 'occupied', key: 'occupied', width: 90 }, { title: '空床位', dataIndex: 'vacant', key: 'vacant', width: 90 },
  { title: '状态', key: 'status', width: 100 }, { title: '操作', key: 'actions', width: 180, fixed: 'right' },
]
const rules: FormProps['rules'] = { name: [{ required: true, message: '请输入宿舍名称' }], buildingId: [{ required: true, message: '请选择所属楼栋' }], type: [{ required: true, message: '请选择宿舍类型' }], beds: [{ required: true, type: 'number', min: 1, message: '床位数至少为 1' }] }
const query = computed(() => ({ page: currentPage.value, pageSize: pageSize.value, ...filters }))
const pagination = computed<TablePaginationConfig>(() => ({ current: currentPage.value, pageSize: pageSize.value, total: store.dormitoryTotal, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }))
async function load() { try { await store.loadDormitories(query.value) } catch (error) { message.error(error instanceof Error ? error.message : '宿舍数据加载失败') } }
function search() { currentPage.value = 1; void load() }
function resetFilters() { Object.assign(filters, { keyword: undefined, buildingId: undefined, type: undefined, status: undefined }); currentPage.value = 1; void load() }
function handleTableChange(page: TablePaginationConfig) { currentPage.value = page.current ?? 1; pageSize.value = page.pageSize ?? 10; void load() }
function openCreate() { editingId.value = null; Object.assign(form, { name: '', type: '男生宿舍', buildingId: store.buildingOptions[0]?.id, beds: 6, occupied: 0 }); modalOpen.value = true }
function openEdit(item: Dormitory) { editingId.value = item.id; Object.assign(form, { name: item.name, type: item.type, buildingId: item.buildingId ?? store.buildingOptions.find((b) => b.name === item.building)?.id, beds: item.beds, occupied: item.occupied }); modalOpen.value = true }
function showDetail(item: Dormitory) { Modal.info({ title: `${item.building} · ${item.name}`, content: `共 ${item.beds} 个床位，已入住 ${item.occupied} 人，当前空闲 ${item.vacant} 个。`, okText: '关闭' }) }
async function submit() {
  if (form.occupied > form.beds) { message.error('已入住人数不能超过床位数'); return }
  try { await store.saveDormitory(editingId.value, { ...form }, query.value); modalOpen.value = false; void dashboardStore.loadAll(true, auth.user?.permissions ?? []); message.success(editingId.value ? '宿舍已更新' : '宿舍已新增') }
  catch (error) { message.error(error instanceof Error ? error.message : '宿舍保存失败') }
}
function confirmDelete(item: Dormitory) { Modal.confirm({ title: `确认删除${item.name}？`, content: '存在已占用床位时不能删除。', okText: '删除', okType: 'danger', cancelText: '取消', async onOk() { await store.removeDormitory(item.id, query.value); void dashboardStore.loadAll(true, auth.user?.permissions ?? []); message.success('宿舍已删除') } }) }
onMounted(load)
</script>
