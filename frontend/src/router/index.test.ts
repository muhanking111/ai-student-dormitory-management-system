import { describe, expect, it } from 'vitest'
import { firstAccessibleRouteName } from './index'
import { protectedRoutes } from './index'

describe('路由权限回退', () => {
  it('优先进入 Dashboard 权限对应的首页', () => {
    expect(firstAccessibleRouteName(['dashboard:read'])).toBe('dashboard')
    expect(firstAccessibleRouteName(['*'])).toBe('dashboard')
  })

  it('没有 Dashboard 权限时进入首个可读业务路由', () => {
    expect(firstAccessibleRouteName(['repair:read'])).toBe('repairs')
    expect(firstAccessibleRouteName(['system:user:read'])).toBe('users')
  })

  it('没有任何可展示路由时进入无权限页而不是循环重定向', () => {
    expect(firstAccessibleRouteName(['repair:write'])).toBe('accessDenied')
    expect(firstAccessibleRouteName([])).toBe('accessDenied')
  })

  it('把知识管理与三个独立 AI 路由追加在 18 个既有业务路由之后并绑定独立权限', () => {
    expect(protectedRoutes.slice(0, 18).map((route) => route.name)).toEqual(expect.arrayContaining(['dashboard', 'noticeCreate', 'roles']))
    expect(protectedRoutes.slice(18).map((route) => [route.path, route.meta?.permission])).toEqual([
      ['ai/knowledge', 'ai:knowledge:read'],
      ['ai/risks', 'ai:risk:read'],
      ['ai/approvals', 'ai:approval:review'],
      ['ai/audit', 'ai:audit:read'],
    ])
  })
})
