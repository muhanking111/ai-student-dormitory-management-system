<template>
  <a-layout-sider
    class="admin-sider"
    :width="208"
    :collapsed-width="52"
    :collapsed="collapsed"
    :trigger="null"
    collapsible
    breakpoint="lg"
    @collapse="handleResponsiveCollapse"
  >
    <div class="brand" title="学生宿舍管理系统">
      <div class="brand-mark"><span aria-hidden="true">A</span></div>
      <span>学生宿舍管理系统</span>
    </div>
    <a-menu
      ref="menuRef"
      :selected-keys="selectedKeys"
      :open-keys="openKeys"
      mode="inline"
      theme="dark"
      trigger-sub-menu-action="click"
      class="side-menu"
      aria-label="主导航"
      tabindex="0"
      @click="handleMenuClick"
      @openChange="handleOpenChange"
    >
      <a-menu-item v-if="auth.hasPermission('dashboard:read')" key="/">
        <template #icon><HomeOutlined /></template>
        首页
      </a-menu-item>
      <a-sub-menu v-if="auth.hasPermission('dormitory:read')" key="dormitory" popup-class-name="side-menu-popup" :class="{ 'side-menu-submenu--route-active': activeParentKey === 'dormitory' }">
        <template #icon><ApartmentOutlined /></template>
        <template #title>宿舍管理</template>
        <a-menu-item key="/dormitories">宿舍列表</a-menu-item>
        <a-menu-item key="/buildings">楼栋管理</a-menu-item>
        <a-menu-item key="/beds">床位管理</a-menu-item>
        <a-menu-item v-if="auth.hasPermission('checkin:read')" key="/assignments">宿舍分配</a-menu-item>
      </a-sub-menu>
      <a-sub-menu v-if="auth.hasPermission('student:read') || auth.hasPermission('checkin:read')" key="student" popup-class-name="side-menu-popup" :class="{ 'side-menu-submenu--route-active': activeParentKey === 'student' }">
        <template #icon><TeamOutlined /></template>
        <template #title>学生管理</template>
        <a-menu-item v-if="auth.hasPermission('student:read')" key="/students">学生信息</a-menu-item>
        <a-menu-item v-if="auth.hasPermission('checkin:read')" key="/applications">入住申请</a-menu-item>
        <a-menu-item v-if="auth.hasPermission('checkin:read')" key="/checkouts">退宿管理</a-menu-item>
      </a-sub-menu>
      <a-sub-menu v-if="auth.hasPermission('repair:read')" key="repair" popup-class-name="side-menu-popup" :class="{ 'side-menu-submenu--route-active': activeParentKey === 'repair' }">
        <template #icon><ToolOutlined /></template>
        <template #title>维修管理</template>
        <a-menu-item key="/repairs">报修列表</a-menu-item>
        <a-menu-item key="/repairs/records">维修记录</a-menu-item>
      </a-sub-menu>
      <a-sub-menu v-if="auth.hasPermission('payment:read')" key="payment" popup-class-name="side-menu-popup" :class="{ 'side-menu-submenu--route-active': activeParentKey === 'payment' }">
        <template #icon><WalletOutlined /></template>
        <template #title>费用管理</template>
        <a-menu-item key="/payments">缴费管理</a-menu-item>
        <a-menu-item key="/payments/records">收费记录</a-menu-item>
      </a-sub-menu>
      <a-sub-menu v-if="auth.hasPermission('hygiene:read')" key="hygiene" popup-class-name="side-menu-popup" :class="{ 'side-menu-submenu--route-active': activeParentKey === 'hygiene' }">
        <template #icon><SafetyCertificateOutlined /></template>
        <template #title>检查管理</template>
        <a-menu-item key="/hygiene">卫生检查</a-menu-item>
        <a-menu-item key="/hygiene/records">检查记录</a-menu-item>
      </a-sub-menu>
      <a-sub-menu v-if="auth.hasPermission('notice:read')" key="notice" popup-class-name="side-menu-popup" :class="{ 'side-menu-submenu--route-active': activeParentKey === 'notice' }">
        <template #icon><NotificationOutlined /></template>
        <template #title>通知管理</template>
        <a-menu-item key="/notices">公告列表</a-menu-item>
        <a-menu-item v-if="auth.hasPermission('notice:write')" key="/notices/create">发布公告</a-menu-item>
      </a-sub-menu>
      <a-sub-menu v-if="canAccessSystem" key="system" popup-class-name="side-menu-popup" :class="{ 'side-menu-submenu--route-active': activeParentKey === 'system' }">
        <template #icon><SettingOutlined /></template>
        <template #title>系统管理</template>
        <a-menu-item v-if="auth.hasPermission('system:user:read')" key="/system/users">用户管理</a-menu-item>
        <a-menu-item v-if="auth.hasPermission('system:role:read')" key="/system/roles">权限管理</a-menu-item>
      </a-sub-menu>
      <a-menu-item-group v-if="canAccessAi" key="ai">
        <template #title><span class="side-section-title">AI 能力</span></template>
        <a-menu-item v-if="auth.hasPermission('ai:knowledge:read')" key="/ai/knowledge">
          <template #icon><ReadOutlined /></template>
          知识管理
        </a-menu-item>
        <a-menu-item v-if="auth.hasPermission('ai:risk:read')" key="/ai/risks">
          <template #icon><AlertOutlined /></template>
          智能风险中心
        </a-menu-item>
        <a-menu-item v-if="canAccessGovernance" :key="governanceMenuTarget">
          <template #icon><AuditOutlined /></template>
          审批与审计
        </a-menu-item>
      </a-menu-item-group>
    </a-menu>
    <button
      type="button"
      class="sidebar-collapse-trigger"
      :aria-label="collapsed ? '展开菜单' : '收起菜单'"
      @click="emit('update:collapsed', !collapsed)"
    >
      <MenuUnfoldOutlined v-if="collapsed" />
      <MenuFoldOutlined v-else />
      <span>{{ collapsed ? '展开菜单' : '收起菜单' }}</span>
    </button>
  </a-layout-sider>
</template>

<script setup lang="ts">
import {
  AlertOutlined,
  ApartmentOutlined,
  AuditOutlined,
  HomeOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  NotificationOutlined,
  ReadOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  TeamOutlined,
  ToolOutlined,
  WalletOutlined,
} from '@ant-design/icons-vue'
import type { MenuProps } from 'ant-design-vue'
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { isAiSurfaceEnabled } from '../api/ai-client'

const props = withDefaults(defineProps<{ collapsed?: boolean }>(), { collapsed: false })
const emit = defineEmits<{ 'update:collapsed': [value: boolean] }>()
const collapsed = computed(() => props.collapsed)

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const canAccessSystem = computed(() =>
  auth.hasPermission('system:user:read') || auth.hasPermission('system:role:read'),
)
const canAccessGovernance = computed(() =>
  auth.hasPermission('ai:approval:review') || auth.hasPermission('ai:audit:read'),
)
const governanceMenuTarget = computed(() =>
  auth.hasPermission('ai:approval:review') ? '/ai/approvals' : '/ai/audit',
)
const canAccessAi = computed(() => isAiSurfaceEnabled() && (
  ['ai:knowledge:read', 'ai:risk:read'].some((permission) => auth.hasPermission(permission))
  || canAccessGovernance.value
))
const selectedKeys = computed(() => {
  if (canAccessGovernance.value && ['/ai/approvals', '/ai/audit'].includes(route.path)) {
    return [governanceMenuTarget.value]
  }
  return [route.path]
})

const rootMenuKeys = ['dormitory', 'student', 'repair', 'payment', 'hygiene', 'notice', 'system'] as const
type RootMenuKey = typeof rootMenuKeys[number]

const parentMenuKeyForPath = (path: string): RootMenuKey | null => {
  if (['/dormitories', '/buildings', '/beds', '/assignments'].includes(path)) return 'dormitory'
  if (['/students', '/applications', '/checkouts'].includes(path)) return 'student'
  if (path.startsWith('/repairs')) return 'repair'
  if (path.startsWith('/payments')) return 'payment'
  if (path.startsWith('/hygiene')) return 'hygiene'
  if (path.startsWith('/notices')) return 'notice'
  if (path.startsWith('/system')) return 'system'
  return null
}

const openKeys = ref<RootMenuKey[]>([])
const activeParentKey = computed(() => parentMenuKeyForPath(route.path))
const menuRef = ref<{ $el?: HTMLElement } | null>(null)
let menuRoot: HTMLElement | null = null
let popupTrigger: HTMLElement | null = null
let focusStabilizationToken = 0

const visibleMenuTargets = () => menuRoot
  ? Array.from(menuRoot.querySelectorAll<HTMLElement>('.ant-menu-item, .ant-menu-submenu-title'))
      .filter((element) => element.getClientRects().length > 0 && !element.matches('[aria-disabled="true"], .ant-menu-item-disabled'))
  : []

const visiblePopupMenu = () => Array.from(document.querySelectorAll<HTMLElement>('.side-menu-popup .ant-menu'))
  .find((element) => element.getClientRects().length > 0) ?? null

const visiblePopupItems = () => {
  const popup = visiblePopupMenu()
  return popup
    ? Array.from(popup.querySelectorAll<HTMLElement>('.ant-menu-item'))
        .filter((element) => element.getClientRects().length > 0 && !element.matches('[aria-disabled="true"], .ant-menu-item-disabled'))
    : []
}

const stabilizeFocus = (
  resolveTarget: () => HTMLElement | null | undefined,
  preventScroll: boolean,
) => {
  const token = ++focusStabilizationToken
  let attemptCount = 0
  let stableAttemptCount = 0
  const focusWhenStable = () => {
    if (token !== focusStabilizationToken) return
    const target = resolveTarget()
    if (target && document.activeElement !== target) {
      target.focus({ preventScroll })
      stableAttemptCount = 0
    } else if (target) {
      stableAttemptCount += 1
    }
    attemptCount += 1
    if (
      (!target || document.activeElement !== target || stableAttemptCount < 8)
      && attemptCount < 24
    ) {
      window.setTimeout(focusWhenStable, 16)
    }
  }
  window.setTimeout(focusWhenStable, 0)
}

const cancelFocusStabilization = () => {
  focusStabilizationToken += 1
}

const focusPopupItem = async (index = 0) => {
  await nextTick()
  stabilizeFocus(() => visiblePopupItems()[index], false)
}

const closePopupAndRestoreFocus = async () => {
  const trigger = popupTrigger
  openKeys.value = []
  await nextTick()
  stabilizeFocus(() => trigger?.isConnected ? trigger : null, true)
}

const handleMenuPointerDown = (event: PointerEvent) => {
  if (!collapsed.value || !menuRoot) return
  const target = (event.target as HTMLElement | null)?.closest<HTMLElement>('.ant-menu-submenu-title')
  if (target && menuRoot.contains(target)) {
    cancelFocusStabilization()
    popupTrigger = target
  }
}

const handleMenuKeydown = (event: KeyboardEvent) => {
  if (!menuRoot) return
  const eventTarget = event.target as HTMLElement | null
  const popup = visiblePopupMenu()
  if (popup?.contains(eventTarget) || menuRoot.contains(eventTarget)) cancelFocusStabilization()
  if (popup?.contains(eventTarget)) {
    const items = visiblePopupItems()
    const currentIndex = items.indexOf(document.activeElement as HTMLElement)
    if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      if (!items.length) return
      let nextIndex: number
      if (event.key === 'Home') nextIndex = 0
      else if (event.key === 'End') nextIndex = items.length - 1
      else if (currentIndex < 0) nextIndex = 0
      else if (event.key === 'ArrowDown') nextIndex = (currentIndex + 1) % items.length
      else nextIndex = (currentIndex - 1 + items.length) % items.length
      event.preventDefault()
      event.stopPropagation()
      items[nextIndex]?.focus({ preventScroll: false })
      return
    }
    if (event.key === 'Escape' || event.key === 'ArrowLeft') {
      event.preventDefault()
      event.stopPropagation()
      void closePopupAndRestoreFocus()
      return
    }
    if (event.key === 'Enter' || event.key === ' ') {
      const item = eventTarget?.closest<HTMLElement>('.ant-menu-item')
      if (!item) return
      event.preventDefault()
      event.stopPropagation()
      item.click()
    }
    return
  }

  if (!menuRoot.contains(eventTarget)) return
  if (['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
    const targets = visibleMenuTargets()
    if (!targets.length) return
    const currentIndex = targets.indexOf(document.activeElement as HTMLElement)
    const selectedTarget = targets.findIndex((target) => target.classList.contains('ant-menu-item-selected'))
    const routeTarget = selectedTarget >= 0
      ? selectedTarget
      : targets.findIndex((target) => target.parentElement?.classList.contains('side-menu-submenu--route-active'))
    let nextIndex: number
    if (event.key === 'Home') nextIndex = 0
    else if (event.key === 'End') nextIndex = targets.length - 1
    else if (currentIndex < 0) nextIndex = routeTarget >= 0 ? routeTarget : 0
    else if (event.key === 'ArrowDown') nextIndex = (currentIndex + 1) % targets.length
    else nextIndex = (currentIndex - 1 + targets.length) % targets.length
    event.preventDefault()
    event.stopPropagation()
    targets[nextIndex]?.focus({ preventScroll: false })
    return
  }

  if (event.key === 'ArrowRight' && collapsed.value) {
    const target = eventTarget?.closest<HTMLElement>('.ant-menu-submenu-title')
    if (!target || !menuRoot.contains(target)) return
    event.preventDefault()
    event.stopPropagation()
    popupTrigger = target
    if (target.getAttribute('aria-expanded') !== 'true') target.click()
    void focusPopupItem()
    return
  }

  if (event.key === 'Enter' || event.key === ' ') {
    const target = eventTarget?.closest<HTMLElement>('.ant-menu-item, .ant-menu-submenu-title')
    if (!target || !menuRoot.contains(target)) return
    event.preventDefault()
    event.stopPropagation()
    const opensPopup = collapsed.value && target.classList.contains('ant-menu-submenu-title')
    if (opensPopup) popupTrigger = target
    target.click()
    if (opensPopup) void focusPopupItem()
  }
}

onMounted(async () => {
  // Ant Menu consumes tabindex and does not expose a root-level roving focus entry.
  await nextTick()
  menuRoot = menuRef.value?.$el ?? null
  menuRoot?.setAttribute('tabindex', '0')
  menuRoot?.addEventListener('pointerdown', handleMenuPointerDown, true)
  document.addEventListener('keydown', handleMenuKeydown, true)
})

onBeforeUnmount(() => {
  menuRoot?.removeEventListener('pointerdown', handleMenuPointerDown, true)
  document.removeEventListener('keydown', handleMenuKeydown, true)
})

watch(
  () => route.path,
  (path) => {
    if (collapsed.value) {
      openKeys.value = []
      return
    }
    const parentKey = parentMenuKeyForPath(path)
    openKeys.value = parentKey ? [parentKey] : []
  },
  { immediate: true },
)

watch(collapsed, (value) => {
  popupTrigger = null
  openKeys.value = value
    ? []
    : activeParentKey.value
      ? [activeParentKey.value]
      : []
})

const handleOpenChange: MenuProps['onOpenChange'] = (keys) => {
  const nextKeys = keys.map(String).filter((key): key is RootMenuKey => (
    rootMenuKeys.includes(key as RootMenuKey)
  ))
  const newlyOpened = nextKeys.find((key) => !openKeys.value.includes(key))
  openKeys.value = newlyOpened ? [newlyOpened] : []
  if (collapsed.value && newlyOpened && popupTrigger) void focusPopupItem()
}

const handleMenuClick: MenuProps['onClick'] = async ({ key }) => {
  const shouldClosePopup = collapsed.value && visiblePopupMenu() !== null
  try {
    await router.push(String(key))
  } finally {
    if (shouldClosePopup) await closePopupAndRestoreFocus()
  }
}

const handleResponsiveCollapse = (value: boolean) => {
  if (value !== collapsed.value) emit('update:collapsed', value)
}
</script>
