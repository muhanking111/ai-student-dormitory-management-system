import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { visualEvidenceMode } from '../utils/visual-evidence-mode'

export const protectedRoutes: RouteRecordRaw[] = [
  { path: '', name: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: '首页', permission: 'dashboard:read' } },
  { path: 'dormitories', name: 'dormitories', component: () => import('../views/DormitoryManagementView.vue'), meta: { title: '宿舍列表', permission: 'dormitory:read' } },
  { path: 'buildings', name: 'buildings', component: () => import('../views/BuildingManagementView.vue'), meta: { title: '楼栋管理', permission: 'dormitory:read' } },
  { path: 'beds', name: 'beds', component: () => import('../views/BedManagementView.vue'), meta: { title: '床位管理', permission: 'dormitory:read' } },
  { path: 'assignments', name: 'assignments', component: () => import('../views/DormitoryAssignmentView.vue'), meta: { title: '宿舍分配', permission: 'checkin:read' } },
  { path: 'students', name: 'students', component: () => import('../views/StudentManagementView.vue'), meta: { title: '学生信息', permission: 'student:read' } },
  { path: 'applications', name: 'applications', component: () => import('../views/CheckInApplicationView.vue'), meta: { title: '入住申请', permission: 'checkin:read' } },
  { path: 'checkouts', name: 'checkouts', component: () => import('../views/CheckoutManagementView.vue'), meta: { title: '退宿管理', permission: 'checkin:read' } },
  { path: 'repairs', name: 'repairs', component: () => import('../views/RepairManagementView.vue'), meta: { title: '维修智能分诊', permission: 'repair:read' } },
  { path: 'repairs/records', name: 'repairRecords', component: () => import('../views/RepairRecordView.vue'), meta: { title: '维修记录', permission: 'repair:read' } },
  { path: 'payments', name: 'payments', component: () => import('../views/PaymentManagementView.vue'), meta: { title: '费用列表', permission: 'payment:read' } },
  { path: 'payments/records', name: 'paymentRecords', component: () => import('../views/PaymentRecordView.vue'), meta: { title: '收费记录', permission: 'payment:read' } },
  { path: 'hygiene', name: 'hygiene', component: () => import('../views/HygieneManagementView.vue'), meta: { title: '卫生检查', permission: 'hygiene:read' } },
  { path: 'hygiene/records', name: 'hygieneRecords', component: () => import('../views/HygieneRecordView.vue'), meta: { title: '检查记录', permission: 'hygiene:read' } },
  { path: 'notices', name: 'notices', component: () => import('../views/NoticeManagementView.vue'), meta: { title: '公告列表', permission: 'notice:read' } },
  { path: 'notices/create', name: 'noticeCreate', component: () => import('../views/NoticeManagementView.vue'), meta: { title: '公告 AI 起草', permission: 'notice:write' } },
  { path: 'system/users', name: 'users', component: () => import('../views/UserManagementView.vue'), meta: { title: '用户管理', permission: 'system:user:read' } },
  { path: 'system/roles', name: 'roles', component: () => import('../views/RoleManagementView.vue'), meta: { title: '权限管理', permission: 'system:role:read' } },
  { path: 'ai/knowledge', name: 'aiKnowledge', component: () => import('../views/AiKnowledgeView.vue'), meta: { title: '知识管理', permission: 'ai:knowledge:read' } },
  { path: 'ai/risks', name: 'aiRisks', component: () => import('../views/AiRiskView.vue'), meta: { title: '智能风险中心', permission: 'ai:risk:read' } },
  { path: 'ai/approvals', name: 'aiApprovals', component: () => import('../views/AiApprovalView.vue'), meta: { title: '待审批', permission: 'ai:approval:review' } },
  { path: 'ai/audit', name: 'aiAudit', component: () => import('../views/AiAuditView.vue'), meta: { title: '运行审计', permission: 'ai:audit:read' } },
]

export const visualEvidenceRoutes: RouteRecordRaw[] = import.meta.env.DEV && visualEvidenceMode
  ? [{
      path: '/__visual/design-system',
      name: 'designSystemGallery',
      component: () => import('../views/DesignSystemGalleryView.vue'),
      meta: { public: true, title: 'AI 智能宿舍设计系统' },
    }]
  : []

export function firstAccessibleRouteName(permissions: string[]): string {
  const allowed = (permission: string) => permissions.includes('*') || permissions.includes(permission)
  const route = protectedRoutes.find((item) => typeof item.meta?.permission === 'string' && allowed(item.meta.permission))
  return String(route?.name ?? 'accessDenied')
}

export const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true, title: '登录' } },
  ...visualEvidenceRoutes,
  {
    path: '/',
    component: () => import('../layouts/AppLayout.vue'),
    children: [
      ...protectedRoutes,
      { path: 'access-denied', name: 'accessDenied', component: () => import('../views/AccessDeniedView.vue'), meta: { title: '无访问权限' } },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  const auth = useAuthStore()
  try {
    if (await auth.ensureSession()) {
      const permission = to.meta.permission
      if (permission && !auth.hasPermission(String(permission))) {
        return { name: firstAccessibleRouteName(auth.user?.permissions ?? []), query: { forbidden: '1' } }
      }
      return true
    }
  } catch {
    return { name: 'login', query: { redirect: to.fullPath, unavailable: '1' } }
  }
  return { name: 'login', query: { redirect: to.fullPath } }
})
