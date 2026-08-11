import { expect, test } from '@playwright/test'
import { mockApi } from './mock-api'

test('管理员可以进入用户管理并创建账号', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/system/users')

  await expect(page.getByRole('heading', { name: '用户管理' })).toBeVisible()
  await page.getByRole('button', { name: /新增用户/ }).click()
  await page.getByLabel('用户名').fill('new-manager')
  await page.getByLabel('显示名称').fill('新宿舍管理员')
  await page.getByLabel('登录密码').fill('Manager-password-123')
  await page.getByRole('combobox', { name: /角色/ }).click()
  await page.getByText('宿舍管理员').click()
  await page.keyboard.press('Tab')
  await expect(page.locator('.ant-select-dropdown:visible')).toHaveCount(0)
  await page.getByRole('button', { name: /确\s*定/ }).click()

  await expect(page.getByText('new-manager')).toBeVisible()
  await expect(page.getByText('新宿舍管理员')).toBeVisible()
})

test('管理员可以查看角色权限列表', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/system/roles')

  await expect(page.getByRole('heading', { name: '权限管理' })).toBeVisible()
  await expect(page.getByText('系统管理员')).toBeVisible()
  await expect(page.getByText('宿舍管理员')).toBeVisible()
  await expect(page.getByText('内置角色').first()).toBeVisible()
})

test('维修人员登录后首落报修页且看不到首页入口', async ({ page }) => {
  await mockApi(page, false)
  await page.goto('/')

  await expect(page).toHaveURL(/\/login/)
  await page.getByLabel('用户名').fill('repairer')
  await page.getByLabel('密码').fill('test-password-123')
  await page.getByRole('button', { name: '登录' }).click()

  await expect(page).toHaveURL(/\/repairs(?:\?|$)/)
  await expect(page.getByRole('heading', { name: '维修智能分诊', exact: true })).toBeVisible()
  await expect(page.getByRole('menuitem', { name: '首页' })).toHaveCount(0)
})
