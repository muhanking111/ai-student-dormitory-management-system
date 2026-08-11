import { expect, test } from '@playwright/test'
import { mockApi } from './mock-api'

async function loginWithKeyboard(page: import('@playwright/test').Page, username: string) {
  await page.goto('/login')
  const usernameInput = page.getByLabel('用户名')
  const passwordInput = page.getByLabel('密码')

  await usernameInput.focus()
  await page.keyboard.type(username)
  await page.keyboard.press('Tab')
  await expect(passwordInput).toBeFocused()
  await page.keyboard.type('test-password-123')
  await page.keyboard.press('Enter')
}

test('维修人员直接访问未授权 URL 时回到可访问模块并获得提示', async ({ page }) => {
  await mockApi(page, false)
  await loginWithKeyboard(page, 'repairer')
  await expect(page).toHaveURL(/\/repairs(?:\?|$)/)

  await page.goto('/payments')

  await expect(page).toHaveURL(/\/repairs\?forbidden=1$/)
  await expect(page.getByText('当前账号无权访问该页面，已跳转到可访问模块')).toBeVisible()
})

test('没有任何业务权限的账号显示真实 403 页面', async ({ page }) => {
  await mockApi(page, false)
  await loginWithKeyboard(page, 'noaccess')

  await expect(page).toHaveURL(/\/access-denied\?forbidden=1$/)
  const alert = page.getByRole('alert')
  await expect(alert).toContainText('403')
  await expect(alert).toContainText('当前账号暂无可访问模块')
})

test('后端返回 409 时页面呈现可操作的冲突原因', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/payments')

  const paymentRow = page.getByRole('row').filter({ hasText: '已入住学生' })
  await paymentRow.getByRole('button', { name: '缴费' }).click()
  const dialog = page.getByRole('dialog', { name: '登记缴费' })
  await expect(dialog.getByLabel('缴费金额')).toHaveValue('500.00')
  await dialog.getByRole('button', { name: '确认缴费' }).click()

  await expect(page.getByText('缴费金额超过待缴金额')).toBeVisible()
  await expect(dialog).toBeVisible()
})

test('Esc 可关闭弹窗且不会提交数据', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/repairs')

  const opener = page.getByRole('button', { name: /新增报修/ })
  await opener.click()
  const dialog = page.getByRole('dialog', { name: '新增报修' })
  await expect(dialog).toBeVisible()
  await dialog.getByLabel('报修人').focus()
  await page.keyboard.press('Escape')

  await expect(dialog).toBeHidden()
  await expect(opener).toBeFocused()
  await expect(page.getByText('报修单已创建')).toHaveCount(0)
})

test('登录表单支持 Tab 移动焦点和 Enter 提交', async ({ page }) => {
  await mockApi(page, false)
  await loginWithKeyboard(page, 'admin')

  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('heading', { name: '首页' })).toBeVisible()
})
