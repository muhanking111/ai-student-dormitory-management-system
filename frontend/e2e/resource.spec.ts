import { expect, test } from '@playwright/test'
import { mockApi } from './mock-api'

test('管理员可以新增楼栋', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/buildings')

  await expect(page.getByRole('heading', { name: '楼栋管理' })).toBeVisible()
  await page.getByRole('button', { name: /新增楼栋/ }).click()
  await page.getByLabel('楼栋编码').fill('B02')
  await page.getByLabel('楼栋名称').fill('2号楼')
  await page.getByRole('button', { name: /确\s*定/ }).click()

  await expect(page.getByText('B02')).toBeVisible()
  await expect(page.getByText('2号楼')).toBeVisible()
})

test('床位管理展示真实资源关系', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/beds')

  await expect(page.getByRole('heading', { name: '床位管理' })).toBeVisible()
  await expect(page.getByText('1号楼').first()).toBeVisible()
  await expect(page.getByText('1号宿舍').first()).toBeVisible()
  await expect(page.getByText('01').first()).toBeVisible()
  await expect(page.getByText('空闲').first()).toBeVisible()
})
