<template>
  <section class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <div class="management-filters">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索角色编码或名称" @press-enter="search" />
        <a-select v-model:value="filters.enabled" allow-clear placeholder="角色状态">
          <a-select-option :value="true">已启用</a-select-option>
          <a-select-option :value="false">已停用</a-select-option>
        </a-select>
        <a-button type="primary" @click="search"><template #icon><SearchOutlined /></template>查询</a-button>
        <a-button @click="resetFilters"><template #icon><ReloadOutlined /></template>重置</a-button>
      </div>
      <a-button v-if="auth.hasPermission('system:role:write')" type="primary" @click="openCreate">
        <template #icon><PlusOutlined /></template>
        新增角色
      </a-button>
    </div>

    <a-table
      row-key="id"
      :columns="columns"
      :data-source="store.roles"
      :loading="store.loading"
      :pagination="pagination"
      :scroll="{ x: 980 }"
      @change="handleTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'name'">
          <span class="role-name">{{ record.name }}</span>
          <a-tag v-if="record.builtIn">内置角色</a-tag>
        </template>
        <template v-else-if="column.key === 'permissionCount'">
          {{ record.permissionIds.length }} 项权限
        </template>
        <template v-else-if="column.key === 'enabled'">
          <a-badge :status="record.enabled ? 'success' : 'default'" :text="record.enabled ? '已启用' : '已停用'" />
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-button type="link" size="small" @click="record.builtIn || !auth.hasPermission('system:role:write') ? showPermissions(record) : openEdit(record)">
            {{ record.builtIn || !auth.hasPermission('system:role:write') ? '查看权限' : '编辑' }}
          </a-button>
          <a-button v-if="!record.builtIn && auth.hasPermission('system:role:write')" type="link" size="small" danger @click="confirmDelete(record)">删除</a-button>
        </template>
      </template>
    </a-table>

    <a-modal
      v-model:open="modalOpen"
      :title="editingId ? '编辑角色' : '新增角色'"
      :confirm-loading="store.saving"
      width="680px"
      ok-text="确定"
      cancel-text="取消"
      destroy-on-close
      @ok="submit"
    >
      <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
        <div class="role-form-grid">
          <a-form-item label="角色编码" name="code">
            <a-input id="role-code" v-model:value="form.code" :disabled="Boolean(editingId)" placeholder="例如 COUNSELOR" />
          </a-form-item>
          <a-form-item label="角色名称" name="name">
            <a-input id="role-name" v-model:value="form.name" />
          </a-form-item>
        </div>
        <a-form-item label="角色说明" name="description">
          <a-textarea id="role-description" v-model:value="form.description" :rows="2" />
        </a-form-item>
        <a-form-item label="角色状态" name="enabled">
          <a-switch v-model:checked="form.enabled" checked-children="启用" un-checked-children="停用" />
        </a-form-item>
        <a-form-item label="权限范围" name="permissionIds">
          <div class="permission-grid">
            <section v-for="group in permissionGroups" :key="group.module" class="permission-group">
              <h3>{{ group.label }}</h3>
              <a-checkbox-group v-model:value="form.permissionIds">
                <a-checkbox v-for="permission in group.items" :key="permission.id" :value="permission.id">
                  {{ permission.name }}
                </a-checkbox>
              </a-checkbox-group>
            </section>
          </div>
        </a-form-item>
      </a-form>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { PlusOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import type { FormInstance, TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import type { Role, RoleInput } from '../api/rbac'
import { useAuthStore } from '../stores/auth'
import { useRbacStore } from '../stores/rbac'

const auth = useAuthStore()
const store = useRbacStore()
const currentPage = ref(1)
const pageSize = ref(10)
const modalOpen = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const filters = reactive<{ keyword?: string; enabled?: boolean }>({})
const form = reactive({ code: '', name: '', description: '', enabled: true, permissionIds: [] as number[] })

const moduleLabels: Record<string, string> = {
  dashboard: '数据驾驶舱', dormitory: '宿舍资源', student: '学生管理', checkin: '入住管理',
  repair: '维修管理', payment: '费用管理', hygiene: '卫生检查', notice: '公告通知', system: '系统管理',
  ai: 'AI 能力',
}
const columns: TableColumnsType = [
  { title: '角色名称', key: 'name', width: 200 },
  { title: '角色编码', dataIndex: 'code', key: 'code', width: 170 },
  { title: '角色说明', dataIndex: 'description', key: 'description', ellipsis: true },
  { title: '权限数量', key: 'permissionCount', width: 120 },
  { title: '状态', key: 'enabled', width: 110 },
  { title: '操作', key: 'actions', width: 150, fixed: 'right' },
]
const rules = {
  code: [{ required: true, message: '请输入角色编码' }, { pattern: /^[A-Z][A-Z0-9_]{2,31}$/, message: '使用大写字母、数字或下划线' }],
  name: [{ required: true, message: '请输入角色名称' }],
  permissionIds: [{ type: 'array', required: true, min: 1, message: '至少选择一个权限' }],
}
const query = computed(() => ({ page: currentPage.value, pageSize: pageSize.value, ...filters }))
const pagination = computed<TablePaginationConfig>(() => ({
  current: currentPage.value, pageSize: pageSize.value, total: store.roleTotal,
  showSizeChanger: true, showTotal: (total) => `共 ${total} 条`,
}))
const permissionGroups = computed(() => {
  const groups = store.permissions.reduce<Record<string, typeof store.permissions>>((result, permission) => {
    result[permission.module] ??= []
    result[permission.module].push(permission)
    return result
  }, {})
  return Object.entries(groups).map(([module, items]) => ({
    module,
    label: moduleLabels[module] ?? module,
    items,
  }))
})

async function load() {
  try { await store.loadRoles(query.value) }
  catch (error) { message.error(error instanceof Error ? error.message : '角色权限加载失败') }
}
function search() { currentPage.value = 1; void load() }
function resetFilters() { filters.keyword = undefined; filters.enabled = undefined; currentPage.value = 1; void load() }
function handleTableChange(page: TablePaginationConfig) {
  currentPage.value = page.current ?? 1
  pageSize.value = page.pageSize ?? 10
  void load()
}
function resetForm() {
  form.code = ''; form.name = ''; form.description = ''; form.enabled = true; form.permissionIds = []
}
function openCreate() { editingId.value = null; resetForm(); modalOpen.value = true }
function openEdit(role: Role) {
  editingId.value = role.id
  form.code = role.code; form.name = role.name; form.description = role.description
  form.enabled = role.enabled; form.permissionIds = [...role.permissionIds]
  modalOpen.value = true
}
async function submit() {
  await formRef.value?.validate()
  const input: RoleInput = {
    name: form.name, description: form.description, enabled: form.enabled,
    permissionIds: form.permissionIds, ...(editingId.value ? {} : { code: form.code }),
  }
  try {
    await store.saveRole(editingId.value, input, query.value)
    modalOpen.value = false
    message.success(editingId.value ? '角色已更新' : '角色已创建')
  } catch (error) { message.error(error instanceof Error ? error.message : '角色保存失败') }
}
function showPermissions(role: Role) {
  const names = store.permissions.filter((permission) => role.permissionIds.includes(permission.id)).map((item) => item.name)
  Modal.info({ title: `${role.name}权限`, content: names.join('、') || '暂无权限', okText: '关闭' })
}
function confirmDelete(role: Role) {
  Modal.confirm({
    title: `确认删除角色 ${role.name}？`, content: '仍被用户使用的角色不能删除。',
    okText: '删除', okType: 'danger', cancelText: '取消',
    async onOk() { await store.removeRole(role.id, query.value); message.success('角色已删除') },
  })
}
onMounted(load)
</script>
