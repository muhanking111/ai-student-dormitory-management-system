import { expect, test } from '@playwright/test'
import { mockApi } from './mock-api'

test('首页展示宿舍管理驾驶舱', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/')

  await expect(page.getByRole('heading', { name: '首页', exact: true })).toBeVisible()
  await expect(page.getByLabel('今日 AI 运营简报')).toBeVisible()
  await expect(page.getByRole('heading', { name: '入住办理趋势', exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '智能风险概览', exact: true })).toBeVisible()
  if ((page.viewportSize()?.width ?? 0) > 1100) {
    await expect(page.getByText('学生宿舍管理系统')).toBeVisible()
  }
})

test('桌面侧栏可在 208 与 52 像素间折叠', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/')

  const header = page.locator('.admin-header')
  const sider = page.locator('.admin-sider')
  if ((page.viewportSize()?.width ?? 0) <= 1100) {
    await expect(header.getByRole('button', { name: /收起菜单|展开菜单/ })).toHaveCount(0)
    return
  }

  await expect.poll(async () => Math.round((await sider.boundingBox())?.width ?? 0)).toBe(208)
  await header.getByRole('button', { name: '收起菜单' }).click()
  await expect(header.getByRole('button', { name: '展开菜单' })).toBeVisible()
  await expect.poll(async () => Math.round((await sider.boundingBox())?.width ?? 0)).toBe(52)
  await header.getByRole('button', { name: '展开菜单' }).click()
  await expect.poll(async () => Math.round((await sider.boundingBox())?.width ?? 0)).toBe(208)
})

test('可以进入宿舍列表并看到表格操作', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/dormitories')

  await expect(page.getByRole('heading', { name: '宿舍列表' })).toBeVisible()
  await expect(page.getByText('新增宿舍')).toBeVisible()
  await expect(page.getByText('1号宿舍')).toBeVisible()
  await expect(page.getByText('编辑').first()).toBeVisible()
})

test('管理员可以新增宿舍', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/dormitories')
  await page.getByRole('button', { name: /新增宿舍/ }).click()

  await page.getByLabel('宿舍名称').fill('新宿舍-202')
  await page.getByLabel('床位数量').fill('6')
  await page.getByRole('button', { name: /确\s*定/ }).click()

  await expect(page.getByText('新宿舍-202')).toBeVisible()
})
