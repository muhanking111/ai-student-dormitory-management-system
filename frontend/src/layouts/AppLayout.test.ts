/// <reference types="node" />

import { flushPromises, mount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { useAuthStore } from '../stores/auth'
import AppHeader from './AppHeader.vue'
import AppLayout from './AppLayout.vue'
import AppSidebar from './AppSidebar.vue'

const globalStyles = readFileSync(resolve(process.cwd(), 'src/style.css'), 'utf8')

function createTestRouter() {
  return createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' }, meta: { title: '首页' } },
      { path: '/students', component: { template: '<div />' }, meta: { title: '学生信息' } },
      { path: '/repairs', component: { template: '<div />' }, meta: { title: '报修列表' } },
      { path: '/notices/create', component: { template: '<div />' }, meta: { title: '公告 AI 起草' } },
      { path: '/ai/knowledge', component: { template: '<div />' }, meta: { title: '知识管理' } },
      { path: '/ai/risks', component: { template: '<div />' }, meta: { title: '智能风险中心' } },
      { path: '/ai/approvals', component: { template: '<div />' }, meta: { title: '待审批' } },
      { path: '/ai/audit', component: { template: '<div />' }, meta: { title: '运行审计' } },
      { path: '/login', component: { template: '<div />' }, meta: { title: '登录' } },
    ],
  })
}

function createAuthenticatedPinia(permissions: string[] = ['*']) {
  const pinia = createPinia()
  setActivePinia(pinia)
  const auth = useAuthStore()
  auth.user = {
    id: 1,
    userName: '系统管理员',
    roleCode: 'ADMIN',
    roleCodes: ['ADMIN'],
    permissions,
  }
  return pinia
}

const sidebarStubs = {
  ALayoutSider: {
    props: ['width', 'collapsedWidth', 'breakpoint'],
    template: '<aside data-testid="sider" :data-width="width" :data-collapsed-width="collapsedWidth" :data-breakpoint="breakpoint"><slot /></aside>',
  },
  AMenu: {
    name: 'AMenu',
    props: {
      selectedKeys: { type: Array, default: () => [] },
      openKeys: { type: Array, default: () => [] },
      triggerSubMenuAction: { type: String, default: 'hover' },
    },
    emits: ['click', 'openChange'],
    template: '<nav data-testid="side-menu" :data-selected-keys="selectedKeys.join(\',\')" :data-open-keys="openKeys.join(\',\')"><slot /></nav>',
  },
  AMenuItem: { name: 'AMenuItem', template: '<a><slot name="icon" /><slot /></a>' },
  AMenuItemGroup: { name: 'AMenuItemGroup', template: '<section><slot name="title" /><slot /></section>' },
  ASubMenu: {
    name: 'ASubMenu',
    props: ['popupClassName'],
    template: '<section :data-popup-class-name="popupClassName"><slot name="icon" /><slot name="title" /><slot /></section>',
  },
}

async function mountSidebarAt(path: string, permissions: string[]) {
  const pinia = createAuthenticatedPinia(permissions)
  const router = createTestRouter()
  await router.push(path)
  await router.isReady()
  const wrapper = mount(AppSidebar, {
    global: {
      plugins: [pinia, router],
      stubs: sidebarStubs,
    },
  })
  return { wrapper, router }
}

function aiMenuLabels(wrapper: ReturnType<typeof mount>) {
  const group = wrapper.getComponent({ name: 'AMenuItemGroup' })
  return group.findAllComponents({ name: 'AMenuItem' }).map((item) => item.text().trim())
}

function menuItemKey(wrapper: ReturnType<typeof mount>, label: string) {
  const item = wrapper.findAllComponents({ name: 'AMenuItem' }).find((candidate) => candidate.text().trim() === label)
  if (!item) throw new Error(`未找到侧栏菜单：${label}`)
  return item.vm.$.vnode.key
}

describe('AppLayout 应用框架复用', () => {
  it('由独立 Sidebar 和 Header 组件组成', () => {
    const wrapper = mount(AppLayout, {
      global: {
        stubs: {
          ALayout: { template: '<div><slot /></div>' },
          ALayoutContent: { template: '<main><slot /></main>' },
          AppSidebar: { name: 'AppSidebar', template: '<aside data-testid="app-sidebar" />' },
          AppHeader: { name: 'AppHeader', template: '<header data-testid="app-header" />' },
          AiAssistantHost: { name: 'AiAssistantHost', template: '<div data-testid="ai-assistant-host" />' },
          RouterView: true,
        },
      },
    })

    expect(wrapper.find('[data-testid="app-sidebar"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="app-header"]').exists()).toBe(true)
    expect(wrapper.find('.admin-main').exists()).toBe(true)
  })

  it('固定桌面侧栏、移动侧栏和页头尺寸合同', async () => {
    const pinia = createAuthenticatedPinia()
    const router = createTestRouter()
    await router.push('/')
    await router.isReady()

    const wrapper = mount(AppSidebar, {
      global: {
        plugins: [pinia, router],
        stubs: {
          ALayoutSider: {
            props: ['width', 'collapsedWidth', 'breakpoint'],
            template: '<aside data-testid="sider" :data-width="width" :data-collapsed-width="collapsedWidth" :data-breakpoint="breakpoint"><slot /></aside>',
          },
          AMenu: { template: '<nav><slot /></nav>' },
          AMenuItem: { template: '<a><slot name="icon" /><slot /></a>' },
          AMenuItemGroup: { template: '<section><slot name="title" /><slot /></section>' },
          ASubMenu: { template: '<section><slot name="icon" /><slot name="title" /><slot /></section>' },
        },
      },
    })

    const sider = wrapper.get('[data-testid="sider"]')
    expect(sider.attributes('data-width')).toBe('208')
    expect(sider.attributes('data-collapsed-width')).toBe('52')
    expect(sider.attributes('data-breakpoint')).toBe('lg')
    const collapseButton = wrapper.get('button[aria-label="收起菜单"]')
    await collapseButton.trigger('click')
    expect(wrapper.emitted('update:collapsed')).toEqual([[true]])
  })

  it('所有路由共用固定品牌且 AI 菜单不随当前页面切换', async () => {
    const { wrapper, router } = await mountSidebarAt('/ai/risks', [
      'ai:knowledge:read',
      'ai:risk:read',
      'ai:approval:review',
      'ai:audit:read',
    ])

    expect(wrapper.get('.brand').attributes('title')).toBe('学生宿舍管理系统')
    expect(wrapper.get('.brand').text()).toContain('学生宿舍管理系统')
    expect(wrapper.get('.brand-mark').text()).toBe('A')
    expect(wrapper.get('.brand-mark').find('.anticon-home').exists()).toBe(false)
    expect(aiMenuLabels(wrapper)).toEqual(['知识管理', '智能风险中心', '审批与审计'])

    await router.push('/ai/audit')
    await wrapper.vm.$nextTick()

    expect(wrapper.get('.brand').text()).toContain('学生宿舍管理系统')
    expect(aiMenuLabels(wrapper)).toEqual(['知识管理', '智能风险中心', '审批与审计'])
  })

  it('根据当前子路由自动展开父组，并把根级展开限制为单组', async () => {
    const permissions = ['student:read', 'checkin:read', 'repair:read', 'notice:read', 'notice:write']
    const { wrapper, router } = await mountSidebarAt('/repairs', permissions)
    const menu = wrapper.getComponent({ name: 'AMenu' })

    expect(wrapper.get('[data-testid="side-menu"]').attributes('data-open-keys')).toBe('repair')

    menu.vm.$emit('openChange', ['repair', 'student'])
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="side-menu"]').attributes('data-open-keys')).toBe('student')

    await router.push('/notices/create')
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="side-menu"]').attributes('data-open-keys')).toBe('notice')
  })

  it('让主导航可由键盘进入，并为所有 portal 子菜单绑定同一视觉合同', async () => {
    const { wrapper } = await mountSidebarAt('/repairs', [
      'dormitory:read',
      'student:read',
      'checkin:read',
      'repair:read',
      'payment:read',
      'hygiene:read',
      'notice:read',
      'system:user:read',
    ])

    expect(wrapper.get('[data-testid="side-menu"]').attributes('tabindex')).toBe('0')
    expect(wrapper.getComponent({ name: 'AMenu' }).props('triggerSubMenuAction')).toBe('click')
    const submenus = wrapper.findAllComponents({ name: 'ASubMenu' })
    expect(submenus.length).toBeGreaterThan(0)
    expect(submenus.every((submenu) => submenu.props('popupClassName') === 'side-menu-popup')).toBe(true)
  })

  it('审批与审计双权限共用审批入口，并在审计页保持统一入口选中', async () => {
    const { wrapper } = await mountSidebarAt('/ai/audit', ['ai:approval:review', 'ai:audit:read'])

    expect(aiMenuLabels(wrapper)).toEqual(['审批与审计'])
    expect(menuItemKey(wrapper, '审批与审计')).toBe('/ai/approvals')
    expect(wrapper.get('[data-testid="side-menu"]').attributes('data-selected-keys')).toBe('/ai/approvals')
  })

  it('仅审计权限用户仍通过统一入口进入审计路由', async () => {
    const { wrapper, router } = await mountSidebarAt('/', ['ai:audit:read'])

    expect(aiMenuLabels(wrapper)).toEqual(['审批与审计'])
    expect(menuItemKey(wrapper, '审批与审计')).toBe('/ai/audit')

    wrapper.getComponent({ name: 'AMenu' }).vm.$emit('click', { key: '/ai/audit' })
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/ai/audit')
    expect(wrapper.get('[data-testid="side-menu"]').attributes('data-selected-keys')).toBe('/ai/audit')
  })

  it('账户菜单仅由点击触发，避免悬停浮层污染截图', async () => {
    const pinia = createAuthenticatedPinia()
    const router = createTestRouter()
    await router.push('/ai/risks')
    await router.isReady()

    const wrapper = mount(AppHeader, {
      global: {
        plugins: [pinia, router],
        stubs: {
          ALayoutHeader: { template: '<header><slot /></header>' },
          ABreadcrumb: { template: '<nav><slot /></nav>' },
          ABreadcrumbItem: { template: '<span><slot /></span>' },
          ABadge: { template: '<span><slot /></span>' },
          AAvatar: { template: '<span><slot /></span>' },
          ADropdown: {
            props: {
              trigger: { type: Array, default: () => [] },
              overlayClassName: { type: String, default: '' },
            },
            template: '<div data-testid="account-dropdown" :data-trigger="trigger.join(\',\')" :data-overlay-class="overlayClassName"><slot /><slot name="overlay" /></div>',
          },
          AMenu: { template: '<div><slot /></div>' },
          AMenuItem: { template: '<button><slot /></button>' },
        },
      },
    })

    const dropdown = wrapper.get('[data-testid="account-dropdown"]')
    expect(dropdown.attributes('data-trigger')).toBe('click')
    expect(dropdown.attributes('data-overlay-class')).toBe('profile-dropdown')
    expect(wrapper.get('h1').text()).toBe('智能风险中心')
    expect(wrapper.find('.header-context').exists()).toBe(true)
    expect(wrapper.find('button[aria-label="通知"]').exists()).toBe(true)
    const sidebarToggle = wrapper.get('button[aria-label="收起菜单"]')
    await sidebarToggle.trigger('click')
    expect(wrapper.emitted('toggle-sidebar')).toHaveLength(1)

    await router.push('/')
    await wrapper.vm.$nextTick()
    expect(wrapper.get('h1').attributes('aria-label')).toBe('首页')
    expect(wrapper.get('h1').text()).toBe('AI 智能驾驶舱')
  })

  it('退出接口失败时仍清理本地会话并跳转登录页', async () => {
    const pinia = createAuthenticatedPinia()
    const auth = useAuthStore(pinia)
    const router = createTestRouter()
    await router.push('/ai/risks')
    await router.isReady()
    vi.spyOn(auth, 'logout').mockImplementation(async () => {
      auth.expireSession()
      throw new Error('logout network failure')
    })

    const wrapper = mount(AppHeader, {
      global: {
        plugins: [pinia, router],
        stubs: {
          ALayoutHeader: { template: '<header><slot /></header>' },
          ABreadcrumb: { template: '<nav><slot /></nav>' },
          ABreadcrumbItem: { template: '<span><slot /></span>' },
          ABadge: { template: '<span><slot /></span>' },
          AAvatar: { template: '<span><slot /></span>' },
          ADropdown: { template: '<div><slot /><slot name="overlay" /></div>' },
          AMenu: { template: '<div><slot /></div>' },
          AMenuItem: { template: '<button><slot /></button>' },
        },
      },
    })

    const logout = wrapper.findAll('button').find((button) => button.text().trim() === '退出登录')
    expect(logout).toBeDefined()
    await logout!.trigger('click')
    await flushPromises()

    expect(auth.user).toBeNull()
    expect(router.currentRoute.value.path).toBe('/login')
  })

  it('落实设计系统的尺寸、字阶、间距和低阴影令牌', () => {
    for (const contract of [
      '--sidebar-width: 208px',
      '--sidebar-collapsed-width: 52px',
      '--header-height: 64px',
      '--touch-target: 44px',
      '--font-size-page-title: 32px',
      '--font-size-card-title: 20px',
      '--font-size-body: 14px',
      '--font-size-caption: 12px',
      '--space-1: 4px',
      '--space-2: 8px',
      '--safe-area-top: env(safe-area-inset-top, 0px)',
      '--safe-area-bottom: env(safe-area-inset-bottom, 0px)',
    ]) {
      expect(globalStyles).toContain(contract)
    }

    expect(globalStyles).toMatch(/--shadow-card:\s*0 1px 2px [^;]+;/)
    expect(globalStyles).toMatch(/\.admin-sider\s*\{[^}]*background:\s*var\(--sidebar\)\s*!important;/s)
    expect(globalStyles).not.toMatch(/font-weight:\s*(?:550|650|750)\b/)
  })

  it('显式覆盖暗色菜单的子菜单、文字、图标和焦点状态', () => {
    expect(globalStyles).toMatch(/\.side-menu\.ant-menu-dark \.ant-menu-sub\.ant-menu-inline\s*\{[^}]*background:\s*transparent\s*!important;/s)
    expect(globalStyles).toMatch(/\.side-menu\.ant-menu-dark[^}]*\.ant-menu-title-content[^}]*\{[^}]*color:\s*var\(--sidebar-text\)\s*!important;/s)
    expect(globalStyles).toMatch(/\.side-menu \.ant-menu-item \.anticon,[^}]*\.ant-menu-submenu-title \.anticon\s*\{[^}]*font-size:\s*18px;/s)
    expect(globalStyles).toMatch(/\.side-menu[^}]*:focus-visible\s*\{[^}]*outline:\s*2px solid var\(--sidebar-focus\)/s)
    expect(globalStyles).toMatch(/\.side-menu-popup[^}]*\.ant-menu[^}]*\{[^}]*background:\s*var\(--sidebar\)\s*!important;/s)
    expect(globalStyles).toMatch(/\.side-menu-popup[^}]*\.ant-menu-item[^}]*\{[^}]*min-height:\s*var\(--touch-target\);/s)
    expect(globalStyles).toMatch(/\.sidebar-collapse-trigger:focus-visible\s*\{[^}]*outline:\s*2px solid var\(--sidebar-focus\);/s)
    expect(globalStyles).toMatch(/\.side-menu\s*>\s*\.side-menu-submenu--route-active[^}]*>\s*\.ant-menu-submenu-title\s*\{[^}]*box-shadow:[^}]*inset/s)
  })

  it('在减少动态偏好下关闭共享壳层和弹层的动画', () => {
    const reducedMotionStyles = globalStyles.slice(globalStyles.indexOf('@media (prefers-reduced-motion: reduce)'))
    expect(reducedMotionStyles).toMatch(/\.admin-sider,[^}]*\.side-menu-popup[^}]*\{[^}]*transition:\s*none\s*!important;[^}]*animation:\s*none\s*!important;/s)
  })

  it('保证壳层操作目标、移动 Header 和安全区合同', () => {
    expect(globalStyles).toMatch(/\.sidebar-toggle-button\s*\{[^}]*width:\s*var\(--touch-target\);[^}]*height:\s*var\(--touch-target\);/s)
    expect(globalStyles).toMatch(/\.header-action-button,[^}]*\.profile-button,[^}]*\.ai-assistant-trigger\s*\{[^}]*min-height:\s*var\(--touch-target\);/s)
    expect(globalStyles).toMatch(/\.header-action-button,[^}]*\.profile-button,[^}]*\.ai-assistant-trigger\s*\{[^}]*height:\s*var\(--touch-target\);[^}]*line-height:\s*1;/s)

    const compactStyles = globalStyles.slice(globalStyles.indexOf('@media (max-width: 1100px)'))
    expect(compactStyles).toMatch(/\.side-menu\.ant-menu-inline-collapsed[^}]*\{[^}]*width:\s*var\(--touch-target\);[^}]*margin-inline:\s*4px;/s)

    const mobileStyles = globalStyles.slice(globalStyles.indexOf('@media (max-width: 768px)'))
    expect(mobileStyles).toMatch(/\.profile-button\s*\{[^}]*display:\s*inline-flex;[^}]*width:\s*var\(--touch-target\);[^}]*min-width:\s*var\(--touch-target\);/s)
    expect(mobileStyles).toMatch(/\.profile-button\s*>\s*:not\(\.profile-avatar\)\s*\{[^}]*display:\s*none;/s)
    expect(mobileStyles).toMatch(/\.admin-header h1\s*\{[^}]*white-space:\s*normal;[^}]*overflow-wrap:\s*anywhere;[^}]*font-size:\s*16px;/s)
    expect(mobileStyles).not.toMatch(/\.admin-header h1\s*\{[^}]*text-overflow:\s*ellipsis;/s)
    expect(mobileStyles).toMatch(/\.admin-shell \.admin-header\.ant-layout-header\s*\{[^}]*padding-top:\s*var\(--safe-area-top\);/s)
    expect(mobileStyles).toMatch(/\.admin-content\s*\{[^}]*padding-bottom:\s*calc\([^)]*var\(--safe-area-bottom\)[^)]*\);/s)
    expect(mobileStyles).not.toContain('.ai-assistant-trigger > span:not(.anticon)')
    expect(mobileStyles).not.toContain('.notification-button > span:not(.anticon)')
  })
})
