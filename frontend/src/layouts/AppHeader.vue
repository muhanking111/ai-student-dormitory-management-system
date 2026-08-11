<template>
  <a-layout-header class="admin-header">
    <div class="header-context">
      <button
        type="button"
        class="sidebar-toggle-button"
        :aria-label="sidebarCollapsed ? '展开菜单' : '收起菜单'"
        @click="emit('toggle-sidebar')"
      >
        <MenuUnfoldOutlined v-if="sidebarCollapsed" />
        <MenuFoldOutlined v-else />
      </button>
      <h1 :title="displayTitle" :aria-label="pageTitle">{{ displayTitle }}</h1>
      <span class="header-divider" aria-hidden="true"></span>
      <a-breadcrumb class="header-breadcrumb">
        <a-breadcrumb-item>{{ sectionTitle }}</a-breadcrumb-item>
        <a-breadcrumb-item>{{ pageTitle }}</a-breadcrumb-item>
      </a-breadcrumb>
    </div>
    <div class="header-actions">
      <button
        v-if="canUseAssistant"
        type="button"
        class="ai-assistant-trigger"
        data-ai-assistant-trigger
        aria-label="智能助手"
        @click="ai.openAssistant"
      >
        <BulbOutlined />
        <span>智能助手</span>
      </button>
      <button class="header-action-button notification-button" type="button" aria-label="通知">
        <BellOutlined class="header-icon" />
        <span>通知</span>
      </button>
      <a-dropdown :trigger="['click']" placement="bottomRight" overlay-class-name="profile-dropdown">
        <button
          class="profile-button"
          type="button"
          aria-haspopup="menu"
          :aria-label="`${auth.user?.userName ?? '管理员'}账户菜单`"
        >
          <a-avatar :size="30" class="profile-avatar">{{ userInitial }}</a-avatar>
          <span>{{ auth.user?.userName ?? '管理员' }}</span>
          <DownOutlined />
        </button>
        <template #overlay>
          <a-menu>
            <a-menu-item key="logout" @click="handleLogout">退出登录</a-menu-item>
          </a-menu>
        </template>
      </a-dropdown>
    </div>
  </a-layout-header>
</template>

<script setup lang="ts">
import { BellOutlined, BulbOutlined, DownOutlined, MenuFoldOutlined, MenuUnfoldOutlined } from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { computed, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { useAiStore } from '../stores/ai'
import { isAiSurfaceEnabled } from '../api/ai-client'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const ai = useAiStore()
withDefaults(defineProps<{ sidebarCollapsed?: boolean }>(), { sidebarCollapsed: false })
const emit = defineEmits<{ 'toggle-sidebar': [] }>()
const canUseAssistant = computed(() => isAiSurfaceEnabled() && auth.hasPermission('ai:assistant:use'))

const pageTitle = computed(() => String(route.meta.title ?? '首页'))
const displayTitle = computed(() => route.path === '/' ? 'AI 智能驾驶舱' : pageTitle.value)
const sectionTitle = computed(() => {
  const path = route.path
  if (path.startsWith('/students') || path.startsWith('/applications') || path.startsWith('/checkouts')) return '学生管理'
  if (path.startsWith('/repairs')) return '维修管理'
  if (path.startsWith('/payments')) return '费用管理'
  if (path.startsWith('/hygiene')) return '检查管理'
  if (path.startsWith('/notices')) return '通知管理'
  if (path.startsWith('/system')) return '系统管理'
  if (path.startsWith('/ai/')) return 'AI 能力'
  return path === '/' ? '数据驾驶舱' : '宿舍管理'
})
const userInitial = computed(() => auth.user?.userName?.slice(0, 1) ?? '管')

watch(
  () => route.query.forbidden,
  (forbidden) => {
    if (forbidden === '1') message.warning('当前账号无权访问该页面，已跳转到可访问模块')
  },
  { immediate: true },
)

const handleLogout = async () => {
  try {
    await auth.logout()
  } catch {
    message.warning('服务端登出未确认，本地会话已清除')
  } finally {
    await router.replace('/login')
  }
}
</script>
