import type { Page, Request } from '@playwright/test'
import { expect, test } from '@playwright/test'
import { mockApi } from './mock-api'

const aiPermissionCodes = [
  'ai:assistant:use',
  'ai:dashboard:query',
  'ai:knowledge:read',
  'ai:knowledge:manage',
  'ai:knowledge:publish-public',
  'ai:repair:triage',
  'ai:notice:draft',
  'ai:risk:read',
  'ai:risk:manage',
  'ai:approval:review',
  'ai:audit:read',
  'ai:audit:content:read',
  'ai:config:manage',
  'ai:eval:run',
] as const

interface ObservedRequest {
  method: string
  path: string
}

function observeAiSafetyBoundary(page: Page) {
  const businessWrites: ObservedRequest[] = []
  const aiRequests: ObservedRequest[] = []
  const unsafeMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

  const listener = (request: Request) => {
    const url = new URL(request.url())
    const observed = { method: request.method(), path: url.pathname }

    if (url.pathname.startsWith('/api/ai/')) aiRequests.push(observed)
    if (
      url.pathname.startsWith('/api/')
      && unsafeMethods.has(request.method())
      && !url.pathname.startsWith('/api/ai/')
      && !url.pathname.startsWith('/api/auth/')
      && !url.pathname.startsWith('/api/security/')
    ) {
      businessWrites.push(observed)
    }
  }

  page.on('request', listener)
  return {
    aiRequests,
    businessWrites,
    stop: () => page.off('request', listener),
  }
}

async function expectNoNetworkBypass(
  page: Page,
  observed: ReturnType<typeof observeAiSafetyBoundary>,
) {
  await page.waitForLoadState('networkidle')
  expect(observed.businessWrites, 'AI 演示不得调用原业务写接口').toEqual([])
  expect(observed.aiRequests, '前端演示模式不得请求 /api/ai/**').toEqual([])
}

test.beforeEach(async ({ page }) => {
  await mockApi(page, true)
})

test('ADMIN mock 显式包含全部 14 个 AI 权限', async ({ page }) => {
  await page.goto('/')

  const permissions = await page.evaluate(async () => {
    const response = await fetch('/api/auth/me')
    const body = await response.json() as { data: { permissions: string[] } }
    return body.data.permissions
  })

  expect(aiPermissionCodes).toHaveLength(14)
  expect(permissions.filter((permission) => permission.startsWith('ai:')).sort())
    .toEqual([...aiPermissionCodes].sort())
})

test('AI 只读 mock 与 CSRF token 是确定性契约，未知端点不会静默返回空数组', async ({ page }) => {
  await page.goto('/')

  const result = await page.evaluate(async () => {
    const read = async (path: string) => {
      const response = await fetch(path)
      return { status: response.status, body: await response.json() }
    }
    const paths = [
      '/api/ai/risk-cases',
      '/api/ai/proposals',
      '/api/ai/audit/runs',
      '/api/ai/audit/costs',
    ]
    const first = await Promise.all(paths.map(read))
    const second = await Promise.all(paths.map(read))
    return {
      first,
      second,
      csrf: await read('/api/security/csrf'),
      unknown: await read('/api/ai/not-configured'),
    }
  })

  expect(result.first).toEqual(result.second)
  expect(result.first.every(({ status }) => status === 200)).toBe(true)
  expect(result.first.slice(0, 3).every(({ body }) => Array.isArray(body.data.records))).toBe(true)
  expect(result.first[0].body.data.records[0]).toMatchObject({
    type: '维修风险',
    severity: 'high',
    state: 'open',
    ruleVersion: 'repair-backlog.v1',
  })
  expect(result.first[1].body.data.records[0]).toMatchObject({
    actionType: 'REPAIR_ASSIGN',
    state: 'pending_approval',
    auditAvailable: true,
    evidence: { grounded: true },
  })
  expect(result.first[2].body.data.records[0]).toMatchObject({
    capability: 'repair-triage',
    state: 'succeeded',
    modelAlias: 'demo-local',
  })
  expect(result.csrf).toEqual({ status: 200, body: { token: 'mock-csrf-token' } })
  expect(result.unknown).toEqual({
    status: 404,
    body: { code: 404, message: '未配置的 AI mock 端点：GET /api/ai/not-configured', data: null },
  })
})

test('公告 mock 的 seed、创建、更新支持可选纯文本正文，AI 草稿不含 PII', async ({ page }) => {
  await page.goto('/')

  const result = await page.evaluate(async () => {
    const json = async (path: string, init?: RequestInit) => {
      const response = await fetch(path, init)
      return await response.json()
    }
    const headers = {
      'Content-Type': 'application/json',
      'X-CSRF-Token': 'mock-csrf-token',
    }
    const seeded = await json('/api/notices?page=1&pageSize=10')
    const created = await json('/api/notices', {
      method: 'POST',
      headers,
      body: JSON.stringify({
        title: '正文合同测试公告',
        type: '安全卫生',
        publisher: '管理员',
        status: '草稿',
        content: '这是创建时保存的纯文本正文。',
      }),
    })
    const updated = await json('/api/notices/1', {
      method: 'PATCH',
      headers,
      body: JSON.stringify({ content: '这是更新后的纯文本正文。' }),
    })
    const proposals = await json('/api/ai/proposals')
    return { seeded, created, updated, proposals }
  })

  expect(result.seeded.data.records[0].content).toBe('请规范使用宿舍电器，离开时关闭非必要电源。')
  expect(result.created.data.content).toBe('这是创建时保存的纯文本正文。')
  expect(result.updated.data.content).toBe('这是更新后的纯文本正文。')

  const noticeProposal = result.proposals.data.records.find(
    (proposal: { actionType: string }) => proposal.actionType === 'NOTICE_CREATE_DRAFT',
  )
  expect(noticeProposal.proposedValue).not.toMatch(/<[^>]+>/)
  expect(noticeProposal.proposedValue).not.toMatch(/1[3-9]\d{9}|\b\d{8,18}\b/)
})

test('Dashboard AI 查询和全局助手只生成本地演示结果', async ({ page }) => {
  const observed = observeAiSafetyBoundary(page)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '首页', exact: true })).toBeVisible()

  const query = page.getByRole('textbox', {
    name: /自然语言查询|宿舍运营问题|AI 查询/,
  }).first()
  await query.fill('过去 30 天入住趋势有什么变化？')
  await page.getByRole('button', { name: /查询|生成简报/ }).first().click()
  await expect(page.getByText(/AI 运营简报|智能简报|暂无可靠来源/).first()).toBeVisible()

  const assistantTrigger = page.getByRole('button', { name: /智能助手/ }).first()
  await assistantTrigger.click()
  const assistant = page.getByRole('dialog', { name: /智能助手/ })
  await expect(assistant).toBeVisible()
  const assistantInput = assistant.getByRole('textbox', {
    name: /向智能助手提问|智能助手输入|问题/,
  })
  await assistantInput.fill('请说明今天最需要关注的运营事项')
  await assistant.getByRole('button', { name: /发送/ }).click()
  await expect(assistant.getByText(/正在检索|基于授权资料|可靠来源|无法确认|已完成/).first()).toBeVisible()

  await expectNoNetworkBypass(page, observed)
  observed.stop()
})

test('维修 AI 分诊生成的后端提案可进入审批页且不会直接改派维修单', async ({ page }) => {
  const observed = observeAiSafetyBoundary(page)
  await page.goto('/repairs')
  await expect(page.getByRole('heading', { name: '维修智能分诊', exact: true })).toBeVisible()

  if ((page.viewportSize()?.width ?? 0) <= 768) {
    await page.getByRole('button', { name: /WX20260710001/ }).click()
  } else {
    const pendingRepair = page.getByRole('row').filter({ hasText: 'WX20260710001' })
    await pendingRepair.getByRole('button', { name: /选择工单/ }).click()
    await page.getByRole('region', { name: '工单详情' }).getByRole('button', { name: 'AI 分诊' }).click()
  }
  const triage = page.getByRole('region', { name: /维修智能分诊|AI 分诊/ })
  await expect(triage).toBeVisible()
  await triage.getByRole('button', { name: /生成.*建议|开始.*分诊/ }).click()
  await expect(triage.getByText(/建议维修员|变更预览|人工审批/).first()).toBeVisible()
  await page.getByRole('region', { name: '维修审批流程' }).getByRole('button', { name: /查看审批提案/ }).click()
  await expect(page).toHaveURL(/\/ai\/approvals/)
  await expect(page.getByRole('heading', { name: '待审批', exact: true })).toBeVisible()

  await expectNoNetworkBypass(page, observed)
  expect(observed.businessWrites.filter(({ path }) => /\/api\/repair-orders\/\d+\/assignee/.test(path)))
    .toEqual([])
  observed.stop()
})

test('公告 AI 起草生成的后端提案可进入审批页且不会保存或发布公告', async ({ page }) => {
  const observed = observeAiSafetyBoundary(page)
  await page.goto('/notices/create')
  await expect(page.getByRole('heading', { name: '公告 AI 起草', exact: true, level: 1 })).toBeVisible()

  const draftingRegion = page.getByRole('region', { name: '公告 AI 起草工作台' })
  await expect(draftingRegion).toBeVisible()
  await page.waitForLoadState('networkidle')
  const keyPoints = draftingRegion.getByRole('textbox', { name: /公告要点|起草要求/ })
  await keyPoints.fill('周五开展宿舍安全用电巡检，请同学提前整理公共区域。')
  await draftingRegion.getByRole('button', { name: /生成.*草稿|AI 起草/ }).click()
  await expect(page.getByRole('region', { name: 'AI 草稿' })).toBeVisible()
  await expect(draftingRegion.getByRole('region', { name: '变更预览' })).toBeVisible()
  await draftingRegion.getByRole('button', { name: /查看审批提案/ }).click()
  await expect(page).toHaveURL(/\/ai\/approvals/)
  await expect(page.getByRole('heading', { name: '待审批', exact: true })).toBeVisible()

  await expectNoNetworkBypass(page, observed)
  expect(observed.businessWrites.filter(({ path }) => /^\/api\/notices(?:\/|$)/.test(path)))
    .toEqual([])
  observed.stop()
})

test('风险、审批和运行审计使用三个独立路由，演示批准不执行真实业务', async ({ page }) => {
  const observed = observeAiSafetyBoundary(page)
  const routes = [
    { path: '/ai/knowledge', heading: '知识管理' },
    { path: '/ai/risks', heading: '智能风险中心' },
    { path: '/ai/approvals', heading: '待审批' },
    { path: '/ai/audit', heading: '运行审计' },
  ]

  for (const route of routes) {
    await test.step(route.heading, async () => {
      await page.goto(route.path)
      await expect(page).toHaveURL(new RegExp(`${route.path.replaceAll('/', '\\/')}(?:\\?|$)`))
      await expect(page.getByRole('heading', { name: route.heading, exact: true })).toBeVisible()
    })
  }

  await page.goto('/ai/risks')
  const acknowledge = page.getByRole('button', { name: '确认风险', exact: true })
  await expect(acknowledge).toBeEnabled()
  await acknowledge.click()
  const riskDialog = page.getByRole('dialog', { name: '确认风险处置' })
  const detail = riskDialog.getByRole('textbox', { name: '风险处置说明' })
  const submitRisk = riskDialog.getByRole('button', { name: '确认风险', exact: true })
  await expect(submitRisk).toBeDisabled()
  await detail.fill('已核验维修积压并通知值班组跟进')
  await expect(submitRisk).toBeEnabled()
  await submitRisk.click()
  await expect(page.getByText('已核验维修积压并通知值班组跟进')).toBeVisible()

  await page.goto('/ai/approvals')
  await page.getByRole('button', { name: /查看.*提案|查看详情/ }).first().click()
  await expect(page.getByText(/当前值|建议值/).first()).toBeVisible()
  const approve = page.getByRole('button', { name: /批准执行/ })
  await expect(approve).toBeDisabled()
  await page.getByRole('checkbox', { name: /提交审批决定/ }).check()
  await expect(approve).toBeEnabled()
  await approve.click()
  const confirm = page.getByRole('dialog', { name: /确认批准|批准执行/ })
  await expect(confirm).toBeVisible()
  await confirm.getByRole('button', { name: /确认批准/ }).click()
  await expect(page.getByText(/演示结果.*未执行真实业务/)).toBeVisible()

  await expectNoNetworkBypass(page, observed)
  observed.stop()
})

test('390 × 844 下智能助手覆盖完整视口且可关闭返回', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/')

  const trigger = page.getByRole('button', { name: /智能助手/ }).first()
  await trigger.click()
  const assistant = page.getByRole('dialog', { name: /智能助手/ })
  await expect(assistant).toBeVisible()

  const bounds = await assistant.boundingBox()
  expect(bounds).not.toBeNull()
  expect(bounds!.x).toBeLessThanOrEqual(1)
  expect(bounds!.width).toBeGreaterThanOrEqual(389)
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(390)

  await assistant.getByRole('button', { name: /关闭智能助手|关闭/ }).first().click()
  await expect(assistant).toBeHidden()
  await expect(trigger).toBeFocused()
})
