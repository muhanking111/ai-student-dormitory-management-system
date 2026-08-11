import { expect, test } from '@playwright/test'
import { mockApi } from './mock-api'

test('管理员可以新增学生', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/students')

  await expect(page.getByRole('heading', { name: '学生信息' })).toBeVisible()
  await page.getByRole('button', { name: /新增学生/ }).click()
  await page.getByLabel('学号').fill('20261009')
  await page.getByLabel('姓名').fill('新增测试学生')
  await page.getByLabel('年级').fill('2026')
  await page.getByLabel('学院').fill('计算机学院')
  await page.getByLabel('手机号').fill('13800001009')
  await page.getByRole('button', { name: /确\s*定/ }).click()

  await expect(page.getByText('新增测试学生')).toBeVisible()
  await expect(page.getByText('学生已新增')).toBeVisible()
})

test('管理员可以审核入住申请并分配空床', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/applications')

  await expect(page.getByRole('heading', { name: '入住申请' })).toBeVisible()
  await expect(page.getByText('待入住学生')).toBeVisible()
  await page.getByRole('button', { name: '审核' }).click()
  const modal = page.locator('.ant-modal').filter({ hasText: '审核入住申请' })
  await modal.getByRole('combobox').click()
  await page.getByText('01 号床', { exact: true }).click()
  await modal.getByRole('button', { name: '通过并分配' }).click()

  await expect(page.getByText('申请已通过并完成床位分配')).toBeVisible()
  await expect(page.getByText('已通过', { exact: true })).toBeVisible()
})

test('管理员可以手工分配宿舍床位', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/assignments')

  await expect(page.getByRole('heading', { name: '宿舍分配' })).toBeVisible()
  await page.getByRole('button', { name: /分配床位/ }).click()
  const modal = page.locator('.ant-modal').filter({ hasText: '分配宿舍床位' })
  const selects = modal.getByRole('combobox')
  await selects.nth(0).click()
  await page.getByText(/20261003.*分配测试学生/).click()
  await selects.nth(1).click()
  await page.getByText(/1号楼.*1号宿舍.*空床/).click()
  await selects.nth(2).click()
  await page.getByText('01 号床', { exact: true }).click()
  await modal.getByRole('button', { name: '确认分配' }).click()

  await expect(page.getByText('床位分配成功')).toBeVisible()
  await expect(page.getByRole('cell', { name: '分配测试学生' })).toBeVisible()
})

test('管理员办理退宿后在住列表立即移除记录', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/checkouts')

  await expect(page.getByRole('heading', { name: '退宿管理' })).toBeVisible()
  await expect(page.getByText('已入住学生')).toBeVisible()
  await page.getByRole('button', { name: '办理退宿' }).click()
  const modal = page.locator('.ant-modal').filter({ hasText: '办理退宿' })
  await modal.getByRole('button', { name: '确认退宿' }).click()

  await expect(page.getByText('退宿办理完成，床位已释放')).toBeVisible()
  await expect(page.getByText('暂无数据')).toBeVisible()
})
