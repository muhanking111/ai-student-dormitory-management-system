import { expect, test } from '@playwright/test'
import { mockApi } from './mock-api'

test('未登录用户登录后进入管理后台', async ({ page }) => {
  await mockApi(page, false)
  await page.goto('/dormitories')

  await expect(page).toHaveURL(/\/login/)
  await page.getByLabel('用户名').fill('admin')
  await page.getByLabel('密码').fill('test-password-123')
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page).toHaveURL(/\/dormitories/)
  await expect(page.getByRole('heading', { name: '宿舍列表' })).toBeVisible()
  await expect(page.getByText('1号宿舍')).toBeVisible()
})

test('已登录用户退出后回到登录页', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/')

  await expect(page.getByRole('heading', { name: '首页' })).toBeVisible()
  await page.getByRole('button', { name: /账户菜单/ }).click()
  await page.getByRole('menuitem', { name: '退出登录' }).click()

  await expect(page).toHaveURL(/\/login$/)
  await expect(page.getByRole('heading', { name: '账号登录' })).toBeVisible()
})

test('业务请求返回 401 时清理会话并跳转登录页', async ({ page }) => {
  await mockApi(page, true)
  await page.route('**/api/payment-bills*', async (route) => {
    await route.fulfill({
      status: 401,
      json: { code: 401, message: '未登录或登录已过期', data: null },
    })
  }, { times: 1 })

  await page.goto('/payments')

  await expect(page).toHaveURL(/\/login\?.*expired=1/)
  await expect(page.getByText('登录已过期，请重新登录')).toBeVisible()
})
