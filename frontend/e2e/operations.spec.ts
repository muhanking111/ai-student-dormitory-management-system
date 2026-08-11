import { expect, test } from '@playwright/test'
import { mockApi } from './mock-api'

test('管理员按相邻状态处理报修且处理人由后端记录', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/repairs')

  await expect(page.getByRole('heading', { name: '维修智能分诊', exact: true })).toBeVisible()
  await page.getByRole('button', { name: /新增报修/ }).click()
  await page.getByLabel('报修人').fill('阶段四报修人')
  await page.getByLabel('报修位置').fill('测试楼-101宿舍')
  await page.getByRole('button', { name: /提\s*交/ }).click()
  await expect(page.getByText('报修单已创建')).toBeVisible()
  const isMobile = (page.viewportSize()?.width ?? 0) <= 768
  const repairRecord = isMobile
    ? page.getByRole('button', { name: /测试楼-101宿舍/ })
    : page.getByRole('row').filter({ hasText: '测试楼-101宿舍' })
  await expect(repairRecord).toContainText('待处理')
  const selectRepair = async () => {
    if (isMobile) await repairRecord.click()
    else await repairRecord.getByRole('button', { name: /选择工单/ }).click()
  }
  await selectRepair()
  const repairActions = page.getByRole('region', { name: '工单详情' })

  await repairActions.getByRole('button', { name: /处\s*理/ }).click()
  let modal = page.getByRole('dialog', { name: '处理报修' })
  await expect(modal.getByText('处理人将自动记录为当前登录用户')).toBeVisible()
  await expect(modal.getByLabel('处理人')).toHaveCount(0)
  await expect(modal.getByLabel('本次状态')).toHaveValue('处理中')
  await modal.getByLabel('处理内容').fill('已开始现场处理')
  await modal.getByRole('button', { name: '保存处理' }).click()
  await expect(page.getByText('报修处理已保存').last()).toBeVisible()
  await expect(repairRecord).toContainText('处理中')

  await selectRepair()
  await repairActions.getByRole('button', { name: /处\s*理/ }).click()
  modal = page.getByRole('dialog', { name: '处理报修' })
  await expect(modal.getByLabel('本次状态')).toHaveValue('已完成')
  await modal.getByLabel('处理内容').fill('已完成现场处理')
  await modal.getByRole('button', { name: '保存处理' }).click()

  await expect(page.getByText('报修处理已保存').last()).toBeVisible()
  await expect(repairRecord).toContainText('已完成')
  await selectRepair()
  await expect(repairActions.getByRole('button', { name: '补充记录' })).toBeVisible()
})

test('管理员可以新增费用账单并登记缴费', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/payments')

  await expect(page.getByRole('heading', { name: '费用列表' })).toBeVisible()
  await page.getByRole('button', { name: /新增账单/ }).click()
  await page.getByLabel('学号').fill('20269901')
  await page.getByLabel('姓名').fill('费用测试学生')
  await page.getByRole('button', { name: /确\s*定/ }).click()
  await expect(page.getByText('账单已创建')).toBeVisible()
  await expect(page.getByText('费用测试学生')).toBeVisible()

  const paymentRow = page.getByRole('row').filter({ hasText: '费用测试学生' })
  await paymentRow.getByRole('button', { name: '缴费' }).click()
  await page.locator('.ant-modal').filter({ hasText: '登记缴费' }).getByRole('button', { name: '确认缴费' }).click()
  await expect(page.getByText('缴费已登记')).toBeVisible()
})

test('管理员可以新增卫生检查并看到自动评级', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/hygiene')

  await expect(page.getByRole('heading', { name: '卫生检查' })).toBeVisible()
  await page.getByRole('button', { name: /新增检查/ }).click()
  await page.getByLabel('宿舍名称').fill('测试卫生宿舍')
  await page.getByLabel('楼栋').fill('测试楼')
  await page.getByLabel('检查人').fill('管理员')
  await page.getByLabel('卫生评分').fill('58')
  await page.getByRole('button', { name: /确\s*定/ }).click()

  await expect(page.getByText('卫生检查已新增')).toBeVisible()
  await expect(page.getByText('测试卫生宿舍')).toBeVisible()
  await expect(page.getByText('不合格')).toBeVisible()
})

test('管理员可以发布公告', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/notices/create')

  await expect(page.getByRole('heading', { name: '公告 AI 起草', exact: true, level: 1 })).toBeVisible()
  const workspace = page.getByRole('region', { name: '公告 AI 起草工作台' })
  await expect(workspace).toBeVisible()
  await workspace.getByLabel('公告标题').fill('阶段四公告测试')
  await workspace.getByRole('button', { name: '保存为草稿' }).click()

  await expect(page.getByText('公告已保存')).toBeVisible()
  await expect(page.getByText('阶段四公告测试')).toBeVisible()
})

test('关键业务列表支持关键字与状态组合筛选', async ({ page }) => {
  await mockApi(page, true)
  const cases = [
    {
      path: '/dormitories',
      heading: '宿舍列表',
      apiPath: '/api/dormitories',
      inputPlaceholder: '搜索宿舍名称',
      keyword: '1号宿舍',
      statusPlaceholder: '入住状态',
      status: '入住中',
      expectedCell: '1号宿舍',
    },
    {
      path: '/repairs',
      heading: '维修智能分诊',
      apiPath: '/api/repair-orders',
      inputPlaceholder: '搜索单号、报修人或位置',
      keyword: '1号楼-101宿舍',
      statusPlaceholder: '报修状态',
      status: '待处理',
      expectedCell: '1号楼-101宿舍',
    },
    {
      path: '/payments',
      heading: '费用列表',
      apiPath: '/api/payment-bills',
      inputPlaceholder: '搜索学号或姓名',
      keyword: '已入住学生',
      statusPlaceholder: '缴费状态',
      status: '部分缴',
      expectedCell: '已入住学生',
    },
    {
      path: '/notices',
      heading: '公告列表',
      apiPath: '/api/notices',
      inputPlaceholder: '搜索公告标题',
      keyword: '宿舍安全用电通知',
      statusPlaceholder: '公告状态',
      status: '已发布',
      expectedCell: '宿舍安全用电通知',
    },
  ]

  for (const item of cases) {
    await test.step(`${item.heading}组合筛选`, async () => {
      await page.goto(item.path)
      await expect(page.getByRole('heading', { name: item.heading, exact: true })).toBeVisible()
      const expectedRecord = item.path === '/repairs' && (page.viewportSize()?.width ?? 0) <= 768
        ? page.getByRole('button', { name: new RegExp(item.expectedCell) })
        : page.getByRole('cell', { name: item.expectedCell, exact: true })
      await expect(expectedRecord).toBeVisible()
      await page.getByPlaceholder(item.inputPlaceholder).fill(item.keyword)
      const statusSelect = page.getByText(item.statusPlaceholder, { exact: true })
        .locator('xpath=ancestor::*[contains(concat(" ", normalize-space(@class), " "), " ant-select ")][1]')
      await statusSelect.getByRole('combobox').click()
      await page.locator('.ant-select-dropdown:visible').getByText(item.status, { exact: true }).last().click()
      await expect(page.locator('.ant-select-dropdown:visible')).toHaveCount(0)

      const responsePromise = page.waitForResponse((response) => {
        const url = new URL(response.url())
        return url.pathname === item.apiPath
          && url.searchParams.get('keyword') === item.keyword
          && url.searchParams.get('status') === item.status
      })
      await page.getByRole('button', { name: /查询/ }).click()
      await expect(responsePromise).resolves.toBeTruthy()
      await expect(expectedRecord).toBeVisible()

      await page.getByPlaceholder(item.inputPlaceholder).fill(`不存在-${item.keyword}`)
      await page.getByRole('button', { name: /查询/ }).click()
      await expect(page.locator('.ant-empty:visible').getByText('暂无数据', { exact: true }).first()).toBeVisible()
    })
  }
})

test('维修、收费与卫生记录使用独立路由和表格', async ({ page }) => {
  await mockApi(page, true)
  const recordPages = [
    { path: '/repairs/records', heading: '维修记录', column: '维修内容' },
    { path: '/payments/records', heading: '收费记录', column: '实缴金额' },
    { path: '/hygiene/records', heading: '检查记录', column: '卫生评分' },
  ]

  for (const item of recordPages) {
    await test.step(item.heading, async () => {
      await page.goto(item.path)
      await expect(page.getByRole('heading', { name: item.heading })).toBeVisible()
      await expect(page.getByRole('columnheader', { name: item.column })).toBeVisible()
    })
  }
})

test('已发布公告编辑时不能退回草稿', async ({ page }) => {
  await mockApi(page, true)
  await page.goto('/notices')

  const noticeRow = page.getByRole('row').filter({ hasText: '宿舍安全用电通知' })
  await noticeRow.getByRole('button', { name: '编辑' }).click()
  const modal = page.getByRole('dialog', { name: '编辑公告' })

  await expect(modal.getByText('已发布', { exact: true })).toBeVisible()
  await expect(modal.getByText('草稿', { exact: true })).toHaveCount(0)
})
