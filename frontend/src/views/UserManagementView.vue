<template>
  <section class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <div class="management-filters">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索用户名或显示名称" @press-enter="search" />
        <a-select v-model:value="filters.enabled" allow-clear placeholder="账号状态">
          <a-select-option :value="true">已启用</a-select-option>
          <a-select-option :value="false">已停用</a-select-option>
        </a-select>
        <a-button type="primary" @click="search">
          <template #icon><SearchOutlined /></template>
          查询
        </a-button>
        <a-button @click="resetFilters">
          <template #icon><ReloadOutlined /></template>
          重置
        </a-button>
      </div>
      <a-button v-if="auth.hasPermission('system:user:write')" type="primary" @click="openCreate">
        <template #icon><UserAddOutlined /></template>
        新增用户
      </a-button>
    </div>

    <a-table
      row-key="id"
      :columns="columns"
      :data-source="store.users"
      :loading="store.loading"
      :pagination="pagination"
      :scroll="{ x: 860 }"
      size="middle"
      @change="handleTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'roles'">
          <a-tag v-for="role in record.roles" :key="role.id" color="blue">{{ role.name }}</a-tag>
        </template>
        <template v-else-if="column.key === 'enabled'">
          <a-badge :status="record.enabled ? 'success' : 'default'" :text="record.enabled ? '已启用' : '已停用'" />
        </template>
        <template v-else-if="column.key === 'actions'">
          <template v-if="auth.hasPermission('system:user:write')">
            <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
            <a-button type="link" size="small" danger :disabled="record.id === auth.user?.id" @click="confirmDelete(record)">
              删除
            </a-button>
          </template>
          <span v-else>-</span>
        </template>
      </template>
    </a-table>

    <a-modal
      v-model:open="modalOpen"
      :title="editingId ? '编辑用户' : '新增用户'"
      :confirm-loading="store.saving"
      ok-text="确定"
      cancel-text="取消"
      destroy-on-close
      @ok="submit"
    >
      <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
        <a-form-item label="用户名" name="username">
          <a-input v-model:value="form.username" aria-label="用户名" :disabled="Boolean(editingId)" autocomplete="off" />
        </a-form-item>
        <a-form-item label="显示名称" name="displayName">
          <a-input v-model:value="form.displayName" aria-label="显示名称" />
        </a-form-item>
        <a-form-item label="登录密码" name="password">
          <a-input-password v-model:value="form.password" aria-label="登录密码" autocomplete="new-password" :placeholder="editingId ? '留空则不修改密码' : '至少 8 位'" />
        </a-form-item>
        <a-form-item label="角色" name="roleIds">
          <a-select v-model:value="form.roleIds" aria-label="角色" mode="multiple" placeholder="请选择角色">
            <a-select-option v-for="role in enabledRoleOptions" :key="role.id" :value="role.id">{{ role.name }}</a-select-option>
          </a-select>
        </a-form-item>
        <a-form-item label="账号状态" name="enabled">
          <a-switch v-model:checked="form.enabled" checked-children="启用" un-checked-children="停用" />
        </a-form-item>
      </a-form>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { ReloadOutlined, SearchOutlined, UserAddOutlined } from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import type { FormInstance, TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref } from 'vue'
import type { UserAccount, UserInput } from '../api/rbac'
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
const form = reactive({ username: '', displayName: '', password: '', enabled: true, roleIds: [] as number[] })

const columns: TableColumnsType = [
  { title: '用户名', dataIndex: 'username', key: 'username', width: 170 },
  { title: '显示名称', dataIndex: 'displayName', key: 'displayName', width: 170 },
  { title: '角色', key: 'roles', width: 240 },
  { title: '状态', key: 'enabled', width: 120 },
  { title: '操作', key: 'actions', width: 150, fixed: 'right' },
]

const rules = {
  username: [{ required: true, message: '请输入用户名' }, { min: 3, max: 32, message: '用户名长度需为 3-32 位' }],
  displayName: [{ required: true, message: '请输入显示名称' }],
  password: [{ validator: (_rule: unknown, value: string) => (!editingId.value && !value) || (value && value.length < 8) ? Promise.reject(new Error('密码长度至少 8 位')) : Promise.resolve() }],
  roleIds: [{ type: 'array', required: true, min: 1, message: '至少选择一个角色' }],
}

const query = computed(() => ({ page: currentPage.value, pageSize: pageSize.value, ...filters }))
const pagination = computed<TablePaginationConfig>(() => ({
  current: currentPage.value,
  pageSize: pageSize.value,
  total: store.userTotal,
  showSizeChanger: true,
  showTotal: (total) => `共 ${total} 条`,
}))
const enabledRoleOptions = computed(() => store.roleOptions.filter((role) => role.enabled))

async function load() {
  try {
    await Promise.all([store.loadUsers(query.value), store.loadRoleOptions()])
  } catch (error) {
    message.error(error instanceof Error ? error.message : '用户数据加载失败')
  }
}

function search() {
  currentPage.value = 1
  void load()
}

function resetFilters() {
  filters.keyword = undefined
  filters.enabled = undefined
  currentPage.value = 1
  void load()
}

function handleTableChange(page: TablePaginationConfig) {
  currentPage.value = page.current ?? 1
  pageSize.value = page.pageSize ?? 10
  void load()
}

function resetForm() {
  form.username = ''
  form.displayName = ''
  form.password = ''
  form.enabled = true
  form.roleIds = []
}

function openCreate() {
  editingId.value = null
  resetForm()
  modalOpen.value = true
}

function openEdit(user: UserAccount) {
  editingId.value = user.id
  form.username = user.username
  form.displayName = user.displayName
  form.password = ''
  form.enabled = user.enabled
  form.roleIds = user.roles.map((role) => role.id)
  modalOpen.value = true
}

async function submit() {
  await formRef.value?.validate()
  const input: UserInput = {
    displayName: form.displayName,
    enabled: form.enabled,
    roleIds: form.roleIds,
    ...(editingId.value ? {} : { username: form.username }),
    ...(form.password ? { password: form.password } : {}),
  }
  try {
    await store.saveUser(editingId.value, input, query.value)
    modalOpen.value = false
    message.success(editingId.value ? '用户已更新' : '用户已创建')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '用户保存失败')
  }
}

function confirmDelete(user: UserAccount) {
  Modal.confirm({
    title: `确认删除用户 ${user.username}？`,
    content: '删除后该账号将立即失去系统访问权限。',
    okText: '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      await store.removeUser(user.id, query.value)
      message.success('用户已删除')
    },
  })
}

onMounted(load)
</script>
