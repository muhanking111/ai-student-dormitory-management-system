import { randomUUID } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import {
  expect,
  test,
  type BrowserContext,
  type Page,
} from '@playwright/test'
import { requireAiLiveIsolation } from './ai-live-isolation'
import {
  requireVisualCredentials,
  sanitizeDiagnostic,
  visualBackendEnv,
  visualBackendURL,
  visualBaseURL,
  visualDirectory,
  visualOutputName,
} from './live-visual-settings'

const stageDirectory = resolve(visualDirectory, 'stage4')
const viewport = { width: 1536, height: 1024 }
const startedAt = new Date()
const stage4RedisKeyPrefix = process.env.AI_LIVE_STAGE4_REDIS_KEY_PREFIX?.trim()
  || `ai-live:stage4:${visualOutputName.replaceAll('.', '-')}`
const aiLiveIsolation = requireAiLiveIsolation({
  ...visualBackendEnv,
  AI_REDIS_KEY_PREFIX: stage4RedisKeyPrefix,
}, { requireOutputBoundRedisPrefix: true })

type JsonObject = Record<string, unknown>

interface ApiEnvelope<T> {
  code?: number
  message?: string
  data?: T
}

interface ScreenshotRecord {
  surface: 'risks' | 'approvals' | 'audit'
  file: string
  width: number
  height: number
  bytes: number
  mtime: string
  mtimeMs: number
  capturedAt: string
}

interface RuntimeErrorRecord {
  type: string
  method?: string
  path?: string
  status?: number
  message: string
}

interface Stage4Manifest {
  status: 'passed' | 'failed'
  generatedAt: string
  startedAt: string
  baseURL: string
  backendURL: string
  viewport: typeof viewport
  infrastructure: {
    services: 'real'
    modelProvider: 'fake'
    writeExecutionEnabled: false
    database: string
    redisDatabase: string
    redisKeyPrefix: string
  }
  dataConstruction: {
    scenarioWrites: 'authenticated-http-only'
    globalSetup: 'control-plane-bootstrap-only'
    endpoints: string[]
  }
  screenshots: ScreenshotRecord[]
  risk: {
    marker: string
    scanId: string
    scanState: string
    signalCount: number
    caseCount: number
    visibleCaseCount: number
    hygieneCaseCount: number
    paymentCaseCount: number
  }
  proposal: {
    id: string
    state: string
    actionType: string
    screenshot: boolean
    actionsBottom: number
  }
  audit: {
    selectedRunId: string
    state: string
    hash: string
    detailSteps: number
    evidenceGroups: number
    evidenceWorkbenchBottom: number
    contentPermissionGranted: boolean
  }
  geometry: {
    viewportHeight: number
    riskTableClientWidth: number
    riskTableScrollWidth: number
    riskCompleteRows: number
    riskPaginationBottom: number
    riskActionDockBottom: number
  }
  errors: {
    api: RuntimeErrorRecord[]
    console: RuntimeErrorRecord[]
    page: RuntimeErrorRecord[]
    request: RuntimeErrorRecord[]
  }
}

interface PageObservation {
  api: RuntimeErrorRecord[]
  console: RuntimeErrorRecord[]
  page: RuntimeErrorRecord[]
  request: RuntimeErrorRecord[]
  successfulApi: Set<string>
  stopped: boolean
}

interface HttpResponse {
  status(): number
  text(): Promise<string>
}

function pngDimensions(path: string) {
  const bytes = readFileSync(path)
  if (bytes.length < 24 || bytes.subarray(1, 4).toString('ascii') !== 'PNG') {
    throw new Error(`${path} 不是有效 PNG`)
  }
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) }
}

function observePage(page: Page): PageObservation {
  const observation: PageObservation = {
    api: [],
    console: [],
    page: [],
    request: [],
    successfulApi: new Set<string>(),
    stopped: false,
  }

  page.on('response', (response) => {
    if (observation.stopped) return
    const url = new URL(response.url())
    if (!url.pathname.startsWith('/api/')) return
    const key = `${response.request().method()} ${url.pathname}`
    if (response.status() >= 200 && response.status() < 400) observation.successfulApi.add(key)
    if (response.status() >= 400) {
      observation.api.push({
        type: 'response',
        method: response.request().method(),
        path: url.pathname,
        status: response.status(),
        message: `${response.status()} ${response.statusText()}`,
      })
    }
  })
  page.on('console', (message) => {
    if (observation.stopped) return
    if (message.type() !== 'error') return
    observation.console.push({ type: 'console', message: sanitizeDiagnostic(message.text()) })
  })
  page.on('pageerror', (error) => {
    if (observation.stopped) return
    observation.page.push({ type: 'pageerror', message: sanitizeDiagnostic(error) })
  })
  page.on('requestfailed', (request) => {
    if (observation.stopped) return
    const url = new URL(request.url())
    if (!url.pathname.startsWith('/api/')) return
    const key = `${request.method()} ${url.pathname}`
    const errorText = request.failure()?.errorText ?? 'unknown request failure'
    // Event-stream connections can be intentionally aborted during navigation;
    // a completed 2xx response proves that the API call itself succeeded.
    if (errorText.includes('ERR_ABORTED') && observation.successfulApi.has(key)) return
    observation.request.push({
      type: 'request',
      method: request.method(),
      path: url.pathname,
      message: sanitizeDiagnostic(errorText),
    })
  })
  return observation
}

async function apiData<T>(response: HttpResponse, expectedStatus: number, label: string): Promise<T> {
  const raw = await response.text()
  let payload: ApiEnvelope<T>
  try {
    payload = JSON.parse(raw) as ApiEnvelope<T>
  } catch {
    throw new Error(`${label} 返回非 JSON：HTTP ${response.status()} ${sanitizeDiagnostic(raw)}`)
  }
  expect(response.status(), `${label}：${sanitizeDiagnostic(payload.message ?? '')}`).toBe(expectedStatus)
  expect(payload.code ?? 0, `${label}：${sanitizeDiagnostic(payload.message ?? '')}`).toBe(0)
  if (payload.data === undefined) throw new Error(`${label} 缺少 data`)
  return payload.data
}

function commandHeaders(token: string, refererPath: string, idempotencyKey?: string) {
  return {
    'X-CSRF-Token': token,
    Origin: visualBaseURL,
    Referer: `${visualBaseURL}${refererPath}`,
    ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
  }
}

async function loginThroughUi(page: Page) {
  const credentials = requireVisualCredentials()
  await page.goto('/login')
  await page.getByRole('textbox', { name: '用户名' }).fill(credentials.username)
  await page.getByLabel('密码').fill(credentials.password)
  const loginResponsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/auth/login' && response.request().method() === 'POST'
  })
  await page.getByRole('button', { name: '登录' }).click()
  const loginResponse = await loginResponsePromise
  if (loginResponse.status() !== 200) {
    throw new Error(`登录失败：HTTP ${loginResponse.status()} ${sanitizeDiagnostic(await loginResponse.text())}`)
  }
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('heading', { name: '首页', exact: true })).toBeVisible()
}

async function csrfToken(context: BrowserContext, refererPath: string) {
  return apiData<{ token: string }>(
    await context.request.get('/api/security/csrf'),
    200,
    `获取 ${refererPath} CSRF token`,
  )
}

async function createRepeatRepairOrders(context: BrowserContext, token: string, marker: string) {
  const location = `阶段四风险验收-${marker}`
  for (let index = 0; index < 3; index += 1) {
    await apiData<{ id: number; code: string }>(
      await context.request.post('/api/repair-orders', {
        headers: commandHeaders(token, '/repairs'),
        data: {
          reporter: `阶段四验收-${marker}`,
          location,
          type: '水电维修',
          description: `阶段四重复报修风险造数 ${marker}-${index + 1}，仅用于受控规则扫描。`,
          assigneeUserId: null,
        },
      }),
      201,
      `创建阶段四维修工单 ${index + 1}`,
    )
  }
  return location
}

async function createHygieneAndPaymentRisks(context: BrowserContext, token: string, marker: string) {
  const dormitories = await apiData<{ records: JsonObject[]; total: number }>(
    await context.request.get('/api/dormitories?page=1&pageSize=1'),
    200,
    '读取卫生风险宿舍范围',
  )
  const dormitory = dormitories.records[0]
  const dormitoryName = String(dormitory?.name ?? '')
  const building = String(dormitory?.building ?? '')
  expect(dormitoryName, '真实卫生风险造数需要一个可授权宿舍').not.toBe('')
  expect(building, '真实卫生风险造数需要宿舍楼栋').not.toBe('')

  await apiData<JsonObject>(
    await context.request.post('/api/hygiene-checks', {
      headers: commandHeaders(token, '/hygiene'),
      data: {
        dormitory: dormitoryName,
        building,
        inspector: '阶段四验收',
        score: 45,
        remark: `阶段四卫生风险造数 ${marker}`,
      },
    }),
    201,
    '创建阶段四不合格卫生检查',
  )

  await apiData<JsonObject>(
    await context.request.post('/api/payment-bills', {
      headers: commandHeaders(token, '/payments'),
      data: {
        studentNo: marker,
        name: `阶段四验收-${marker}`,
        type: '住宿费',
        amountDue: 880,
        deadline: '2000-01-01',
      },
    }),
    201,
    '创建阶段四逾期账单',
  )
}

async function runRiskScan(context: BrowserContext, token: string, marker: string) {
  const scan = await apiData<{ id: string; state: string }>(
    await context.request.post('/api/ai/risk-scans', {
      headers: commandHeaders(token, '/ai/risks', `stage4-risk-${marker}`),
    }),
    202,
    '请求风险扫描',
  )
  let latest: JsonObject = scan as unknown as JsonObject
  const deadline = Date.now() + 90_000
  while (Date.now() < deadline) {
    latest = await apiData<JsonObject>(
      await context.request.get(`/api/ai/risk-scans/${encodeURIComponent(scan.id)}`),
      200,
      '读取风险扫描状态',
    )
    const state = String(latest.state ?? '').toUpperCase()
    if (['SUCCEEDED', 'PARTIAL', 'FAILED', 'NEEDS_REVIEW'].includes(state)) break
    await delay(1_000)
  }
  const state = String(latest.state ?? '').toUpperCase()
  expect(state, `真实风险扫描未在限定时间内完成：${JSON.stringify(latest)}`).toMatch(/^(SUCCEEDED|PARTIAL)$/)
  const signalCount = Number(latest.signalCount ?? 0)
  const caseCount = Number(latest.caseCount ?? 0)
  expect(signalCount, `真实风险规则未命中，重复维修造数可能未进入授权 scope：${JSON.stringify(latest)}`).toBeGreaterThan(0)
  expect(caseCount, `真实风险扫描未产生风险案例：${JSON.stringify(latest)}`).toBeGreaterThan(0)
  const visible = await apiData<{ records: JsonObject[]; total: number }>(
    await context.request.get('/api/ai/risk-cases?page=1&pageSize=100'),
    200,
    '读取真实风险案例',
  )
  expect(visible.records.length, '风险扫描返回案例但风险列表不可见，疑似对象授权或持久化异常').toBeGreaterThan(0)
  const hygiene = await apiData<{ records: JsonObject[]; total: number }>(
    await context.request.get(`/api/ai/risk-cases?page=1&pageSize=100&type=${encodeURIComponent('卫生风险')}`),
    200,
    '读取真实卫生风险案例',
  )
  const payment = await apiData<{ records: JsonObject[]; total: number }>(
    await context.request.get(`/api/ai/risk-cases?page=1&pageSize=100&type=${encodeURIComponent('欠费风险')}`),
    200,
    '读取真实欠费风险案例',
  )
  expect(hygiene.total, '真实不合格卫生检查未产生卫生风险案例').toBeGreaterThan(0)
  expect(hygiene.records.length, '卫生风险案例存在但当前授权列表不可见').toBeGreaterThan(0)
  expect(payment.total, '真实逾期账单未产生欠费风险案例').toBeGreaterThan(0)
  expect(payment.records.length, '欠费风险案例存在但当前授权列表不可见').toBeGreaterThan(0)
  return {
    scanId: scan.id,
    scanState: state,
    signalCount,
    caseCount,
    visibleCaseCount: visible.records.length,
    hygieneCaseCount: hygiene.total,
    paymentCaseCount: payment.total,
  }
}

async function createNoticeProposal(page: Page, marker: string) {
  await page.goto('/notices/create')
  const region = page.getByRole('region', { name: '公告 AI 起草工作台' })
  await expect(region).toBeVisible()
  await region.getByRole('textbox', { name: '公告要点' }).fill(
    `阶段四宿舍安全巡查公告 ${marker}：检查公共区域用电与消防通道，请按流程配合。`,
  )
  const draftResponsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/ai/notices/drafts' && response.request().method() === 'POST'
  })
  await region.getByRole('button', { name: '生成 AI 草稿' }).click()
  const draftResponse = await draftResponsePromise
  expect(draftResponse.status(), '公告草稿必须通过真实 AI command 创建').toBe(202)
  await expect(page.getByRole('region', { name: 'AI 草稿' })).toContainText('AI 草稿已生成', { timeout: 60_000 })
  const proposalButton = region.getByRole('button', { name: /查看审批提案/ })
  await expect(proposalButton).toBeEnabled()
  const proposalResponsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return /^\/api\/ai\/proposals\/[^/]+$/.test(url.pathname) && response.request().method() === 'GET'
  })
  await proposalButton.click()
  const proposalResponse = await proposalResponsePromise
  const proposal = await apiData<JsonObject>(proposalResponse, 200, '读取公告审批提案')
  const proposalId = new URL(proposalResponse.url()).pathname.split('/').pop() ?? ''
  expect(proposalId).not.toBe('')
  expect(String(proposal.state ?? '')).toBe('pending_approval')
  return { id: proposalId, state: String(proposal.state), actionType: String(proposal.actionType ?? '') }
}

async function captureScreenshot(
  page: Page,
  surface: ScreenshotRecord['surface'],
  file: string,
) {
  mkdirSync(stageDirectory, { recursive: true })
  const path = resolve(stageDirectory, file)
  await page.screenshot({ path, animations: 'disabled', caret: 'hide' })
  const stats = statSync(path)
  const dimensions = pngDimensions(path)
  expect(dimensions.width, `${file} 宽度必须与阶段四视口一致`).toBe(viewport.width)
  expect(dimensions.height, `${file} 高度必须与阶段四视口一致`).toBe(viewport.height)
  expect(stats.size, `${file} 文件过小`).toBeGreaterThan(1_024)
  expect(stats.mtimeMs, `${file} 不是本次运行新鲜生成`).toBeGreaterThanOrEqual(startedAt.getTime() - 1_000)
  return {
    surface,
    file,
    width: dimensions.width,
    height: dimensions.height,
    bytes: stats.size,
    mtime: new Date(stats.mtimeMs).toISOString(),
    mtimeMs: stats.mtimeMs,
    capturedAt: new Date().toISOString(),
  } satisfies ScreenshotRecord
}

async function assertRiskCharts(page: Page) {
  const charts = page.locator('.risk-trend-chart canvas, .risk-distribution-chart canvas')
  await expect(charts).toHaveCount(2)
  for (let index = 0; index < 2; index += 1) {
    const canvas = charts.nth(index)
    await expect.poll(async () => canvas.evaluate((element) => {
      const node = element as HTMLCanvasElement
      if (!node.width || !node.height) return 0
      const context = node.getContext('2d')
      if (!context) return 0
      const pixels = context.getImageData(0, 0, node.width, node.height).data
      let colored = 0
      for (let cursor = 0; cursor < pixels.length; cursor += 16) {
        const alpha = pixels[cursor + 3] ?? 0
        if (alpha > 0 && ((pixels[cursor] ?? 255) < 245 || (pixels[cursor + 1] ?? 255) < 245 || (pixels[cursor + 2] ?? 255) < 245)) colored += 1
      }
      return colored
    }), { timeout: 30_000 }).toBeGreaterThan(20)
  }
}

function assertThreeColumnWorkbench(page: Page, selectors: string[]) {
  return Promise.all(selectors.map((selector) => page.locator(selector).boundingBox())).then((boxes) => {
    expect(boxes.every(Boolean), `三栏工作区存在不可测量列：${JSON.stringify(boxes)}`).toBe(true)
    const measured = boxes as Array<{ x: number; y: number; width: number; height: number }>
    expect(measured[0]!.x).toBeLessThan(measured[1]!.x)
    expect(measured[1]!.x).toBeLessThan(measured[2]!.x)
    expect(measured[2]!.y).toBeLessThanOrEqual(measured[0]!.y + 12)
    expect(measured[1]!.y).toBeLessThanOrEqual(measured[0]!.y + 12)
  })
}

async function requireBoxWithinViewport(page: Page, selector: string, label: string, bottomInset = 16) {
  const box = await page.locator(selector).boundingBox()
  expect(box, `${label}必须可测量`).not.toBeNull()
  expect(box!.x, `${label}左边界不得越出视口`).toBeGreaterThanOrEqual(0)
  expect(box!.y, `${label}上边界不得越出视口`).toBeGreaterThanOrEqual(0)
  expect(box!.x + box!.width, `${label}右边界不得越出视口`).toBeLessThanOrEqual(viewport.width)
  expect(box!.y + box!.height, `${label}底边不得被首屏裁切`).toBeLessThanOrEqual(viewport.height - bottomInset)
  return box
}

test('阶段 4 风险中心、审批与运行审计真实服务桌面验收', async ({ browser }) => {
  mkdirSync(stageDirectory, { recursive: true })
  const screenshots: ScreenshotRecord[] = []
  const marker = `S4-${Date.now().toString(36).toUpperCase()}-${randomUUID().slice(0, 6).toUpperCase()}`
  const risk = {
    marker,
    scanId: '',
    scanState: '',
    signalCount: 0,
    caseCount: 0,
    visibleCaseCount: 0,
    hygieneCaseCount: 0,
    paymentCaseCount: 0,
  }
  const proposal = { id: '', state: '', actionType: '', screenshot: false, actionsBottom: 0 }
  const audit = {
    selectedRunId: '',
    state: '',
    hash: '',
    detailSteps: 0,
    evidenceGroups: 0,
    evidenceWorkbenchBottom: 0,
    contentPermissionGranted: false,
  }
  const geometry = {
    viewportHeight: viewport.height,
    riskTableClientWidth: 0,
    riskTableScrollWidth: 0,
    riskCompleteRows: 0,
    riskPaginationBottom: 0,
    riskActionDockBottom: 0,
  }
  let observation: PageObservation | undefined
  let context: BrowserContext | undefined
  let passed = false

  try {
    context = await browser.newContext({
      baseURL: visualBaseURL,
      viewport,
      deviceScaleFactor: 1,
      reducedMotion: 'reduce',
    })
    const page = await context.newPage()
    observation = observePage(page)
    await loginThroughUi(page)

    const token = await csrfToken(context, '/repairs')
    await createRepeatRepairOrders(context, token.token, marker)
    await createHygieneAndPaymentRisks(context, token.token, marker)
    Object.assign(risk, await runRiskScan(context, token.token, marker))

    const createdProposal = await createNoticeProposal(page, marker)
    Object.assign(proposal, createdProposal)

    const approvalPage = page.getByRole('main', { name: '提案详情' })
    await expect(page).toHaveURL(/\/ai\/approvals$/)
    await expect(approvalPage).toBeVisible()
    await expect(page.locator('[data-testid="approval-proposal-rail"]')).toBeVisible()
    await expect(page.locator('.ai-proposal-row.selected')).toHaveCount(1)
    await expect(approvalPage.locator('.value-diff')).toBeVisible()
    await expect(approvalPage.getByText('当前值（将被替换）')).toBeVisible()
    await expect(approvalPage.getByText('建议值（执行后）')).toBeVisible()
    await expect(approvalPage.locator('.guard-grid > span')).toHaveCount(4)
    await expect(approvalPage.getByRole('button', { name: '批准执行' })).toBeVisible()
    await expect(approvalPage.getByRole('button', { name: '拒绝' })).toBeVisible()
    await expect(approvalPage.getByRole('button', { name: '刷新预览' })).toBeVisible()
    expect(proposal.actionType, '阶段四公告提案必须暴露可筛选的方案类型').toBe('NOTICE_CREATE_DRAFT')
    const proposalTypeFilter = page.getByRole('combobox', { name: '方案类型筛选' })
    const filteredProposalResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === '/api/ai/proposals'
        && response.request().method() === 'GET'
        && url.searchParams.get('actionType') === proposal.actionType
    })
    await proposalTypeFilter.selectOption(proposal.actionType)
    const filteredProposalResponse = await filteredProposalResponsePromise
    const filteredProposalUrl = new URL(filteredProposalResponse.url())
    expect(filteredProposalUrl.searchParams.getAll('actionType'), '方案类型筛选请求必须且只能携带当前 actionType').toEqual([proposal.actionType])
    expect(filteredProposalResponse.status(), '方案类型筛选必须由真实服务端分页接口成功响应').toBe(200)
    await expect(proposalTypeFilter).toHaveValue(proposal.actionType)
    await expect(page.locator('.ai-proposal-row')).not.toHaveCount(0)

    const unfilteredProposalResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === '/api/ai/proposals'
        && response.request().method() === 'GET'
        && !url.searchParams.has('actionType')
    })
    await proposalTypeFilter.selectOption('all')
    const unfilteredProposalResponse = await unfilteredProposalResponsePromise
    expect(unfilteredProposalResponse.status(), '恢复全部方案列表必须成功').toBe(200)
    await expect(proposalTypeFilter).toHaveValue('all')
    await expect(page.locator('.ai-proposal-row.selected')).toHaveCount(1)
    await assertThreeColumnWorkbench(page, [
      '[data-testid="approval-proposal-rail"]',
      '[data-testid="approval-preview-panel"]',
      '[data-testid="approval-runtime-rail"]',
    ])
    const approvalActionsBox = await requireBoxWithinViewport(page, '.approval-actions', '审批操作区', 20)
    proposal.actionsBottom = Math.round(approvalActionsBox.y + approvalActionsBox.height)
    screenshots.push(await captureScreenshot(page, 'approvals', 'stage4-approvals-desktop.png'))
    proposal.screenshot = true

    await page.goto('/ai/risks')
    await expect(page.getByRole('heading', { name: '智能风险中心', exact: true })).toBeVisible()
    await expect(page.locator('[data-testid="risk-summary-card"]')).toHaveCount(4)
    const summarySparklines = page.locator('[data-testid="risk-summary-sparkline"]')
    await expect(summarySparklines).toHaveCount(4)
    for (let index = 0; index < 4; index += 1) {
      const sparkline = summarySparklines.nth(index)
      await expect(sparkline.locator('polyline')).toHaveAttribute('points', /\d/)
      const sparklineBox = await sparkline.boundingBox()
      expect(sparklineBox, `风险概览微趋势 ${index + 1} 必须可测量`).not.toBeNull()
      expect(sparklineBox!.x + sparklineBox!.width, `风险概览微趋势 ${index + 1} 右边界不得越出视口`).toBeLessThanOrEqual(viewport.width)
    }
    await assertRiskCharts(page)
    await expect(page.getByRole('heading', { name: '规则状态', exact: true })).toBeVisible()
    await expect(page.locator('.rule-list > li')).toHaveCount(4)
    await expect(page.getByText('规则信号，不代表学生评价', { exact: true })).toBeVisible()
    const riskRows = page.locator('.risk-table tbody tr').filter({ has: page.locator('td') })
    await expect(riskRows).not.toHaveCount(0)
    await riskRows.first().click()
    await expect(page.locator('.risk-table tbody tr.selected')).toHaveCount(1)
    const riskDetail = page.locator('[aria-label="风险案例详情"]')
    await expect(riskDetail).toBeVisible()
    await expect(riskDetail.getByRole('heading', { name: '确定性证据', exact: true })).toBeVisible()
    await expect(riskDetail.getByRole('heading', { name: '授权业务快照', exact: true })).toBeVisible()
    await expect(riskDetail.getByRole('heading', { name: 'AI 解释', exact: true })).toBeVisible()
    await expect(riskDetail.getByRole('heading', { name: '人工处置时间线', exact: true })).toBeVisible()
    const riskListBox = await page.locator('[aria-label="风险案例列表"]').boundingBox()
    const riskDetailBox = await riskDetail.boundingBox()
    expect(riskListBox, '风险列表区域必须可测量').not.toBeNull()
    expect(riskDetailBox, '风险详情区域必须可测量').not.toBeNull()
    expect(riskDetailBox!.x).toBeGreaterThan(riskListBox!.x)
    expect(riskDetailBox!.y).toBeLessThanOrEqual(riskListBox!.y + 12)
    const riskTableGeometry = await page.locator('[data-testid="risk-table-scroll-region"]').evaluate((element) => ({
      clientWidth: element.clientWidth,
      scrollWidth: element.scrollWidth,
    }))
    geometry.riskTableClientWidth = riskTableGeometry.clientWidth
    geometry.riskTableScrollWidth = riskTableGeometry.scrollWidth
    expect(riskTableGeometry.scrollWidth, '1536 桌面风险表格不得出现横向滚动').toBeLessThanOrEqual(riskTableGeometry.clientWidth + 1)
    const completeRiskRows = await riskRows.evaluateAll((rows, viewportHeight) => rows.filter((row) => {
      const box = row.getBoundingClientRect()
      const scrollRegion = row.closest('[data-testid="risk-table-scroll-region"]')?.getBoundingClientRect()
      return Boolean(scrollRegion)
        && box.top >= scrollRegion!.top
        && box.bottom <= scrollRegion!.bottom
        && box.top >= 0
        && box.bottom <= Number(viewportHeight) - 16
    }).length, viewport.height)
    geometry.riskCompleteRows = completeRiskRows
    expect(completeRiskRows, '1536x1024 首屏必须完整显示至少 5 条风险线索').toBeGreaterThanOrEqual(5)
    const riskPaginationBox = await requireBoxWithinViewport(page, '.risk-pagination', '风险分页', 16)
    geometry.riskPaginationBottom = Math.round(riskPaginationBox.y + riskPaginationBox.height)
    const riskActionDockBox = await requireBoxWithinViewport(page, '[data-testid="risk-action-dock"]', '风险人工处置区', 16)
    geometry.riskActionDockBottom = Math.round(riskActionDockBox.y + riskActionDockBox.height)
    screenshots.push(await captureScreenshot(page, 'risks', 'stage4-risks-desktop.png'))

    await page.goto('/ai/audit')
    await expect(page.getByRole('heading', { name: '运行审计', exact: true })).toBeVisible()
    await expect(page.getByRole('form', { name: '运行审计筛选' })).toBeVisible()
    await expect(page.getByRole('form', { name: '运行审计筛选' }).locator('select')).toHaveCount(3)
    await expect(page.getByRole('form', { name: '运行审计筛选' }).locator('input[type="datetime-local"]')).toHaveCount(2)
    await expect(page.locator('[data-testid="audit-run-rail"]')).toBeVisible()
    await expect(page.locator('[data-testid="audit-detail-panel"]')).toBeVisible()
    await expect(page.locator('[data-testid="audit-metrics-rail"]')).toBeVisible()
    await assertThreeColumnWorkbench(page, [
      '[data-testid="audit-run-rail"]',
      '[data-testid="audit-detail-panel"]',
      '[data-testid="audit-metrics-rail"]',
    ])
    await expect(page.locator('.ai-audit-run.selected')).toHaveCount(1)
    await expect(page.locator('.hash-chain code')).not.toHaveText('')
    audit.hash = (await page.locator('.hash-chain code').textContent() ?? '').trim()
    await expect(page.locator('.audit-timeline li')).not.toHaveCount(0)
    audit.detailSteps = await page.locator('.audit-timeline li').count()
    await expect(page.locator('.metric-grid article')).toHaveCount(4)
    const auditEvidenceGroups = page.locator('[data-testid="audit-evidence-workbench"] .audit-evidence-group')
    await expect(auditEvidenceGroups).toHaveCount(7)
    const evidenceKinds = await auditEvidenceGroups.evaluateAll((groups) => groups.map((group) => group.getAttribute('data-evidence-kind')))
    expect(new Set(evidenceKinds).size, '审计证据工作台必须覆盖 7 类互不重复的真实事实').toBe(7)
    audit.evidenceGroups = evidenceKinds.length
    const auditEvidenceBox = await requireBoxWithinViewport(page, '[data-testid="audit-evidence-workbench"]', '审计证据工作台', 16)
    audit.evidenceWorkbenchBottom = Math.round(auditEvidenceBox.y + auditEvidenceBox.height)

    const session = await apiData<JsonObject>(
      await context.request.get('/api/auth/me'),
      200,
      '读取当前真实会话权限',
    )
    const permissions = Array.isArray(session.permissions) ? session.permissions.map(String) : []
    audit.contentPermissionGranted = permissions.includes('ai:audit:content:read') || permissions.includes('*')
    expect(audit.contentPermissionGranted, '阶段四正文权限门应保持关闭，不能把 ADMIN 默认升级为 break-glass').toBe(false)
    await expect(page.getByRole('button', { name: '读取审计正文' })).toHaveCount(0)
    await expect(page.getByText('正文读取属于 break-glass 操作', { exact: false })).toBeVisible()
    const selectedRunText = await page.locator('.ai-audit-run.selected small').textContent()
    audit.selectedRunId = selectedRunText?.trim() ?? ''
    audit.state = (await page.locator('.detail-heading i').textContent() ?? '').trim()
    expect(audit.selectedRunId).not.toBe('')
    screenshots.push(await captureScreenshot(page, 'audit', 'stage4-audit-desktop.png'))

    const errors = observation
      ? [...observation.api, ...observation.console, ...observation.page, ...observation.request]
      : []
    expect(errors, `阶段四真实服务页面存在运行时错误：${JSON.stringify(errors)}`).toEqual([])
    passed = true
  } finally {
    if (observation) observation.stopped = true
    if (context) await context.close()
    const errors = observation
      ? { api: observation.api, console: observation.console, page: observation.page, request: observation.request }
      : { api: [], console: [], page: [], request: [] }
    const manifest: Stage4Manifest = {
      status: passed ? 'passed' : 'failed',
      generatedAt: new Date().toISOString(),
      startedAt: startedAt.toISOString(),
      baseURL: visualBaseURL,
      backendURL: visualBackendURL,
      viewport,
      infrastructure: {
        services: 'real',
        modelProvider: 'fake',
        writeExecutionEnabled: false,
        database: aiLiveIsolation.connection.database,
        redisDatabase: String(aiLiveIsolation.redis.database),
        redisKeyPrefix: aiLiveIsolation.redis.keyPrefix,
      },
      dataConstruction: {
        scenarioWrites: 'authenticated-http-only',
        globalSetup: 'control-plane-bootstrap-only',
        endpoints: [
          'POST /api/repair-orders',
          'POST /api/hygiene-checks',
          'POST /api/payment-bills',
          'POST /api/ai/risk-scans',
          'POST /api/ai/notices/drafts',
          'GET /api/ai/proposals/{id}',
          'GET /api/ai/audit/runs/{id}',
        ],
      },
      screenshots,
      risk,
      proposal,
      audit,
      geometry,
      errors,
    }
    mkdirSync(stageDirectory, { recursive: true })
    writeFileSync(resolve(stageDirectory, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
    expect(existsSync(resolve(stageDirectory, 'manifest.json'))).toBe(true)
  }
})
