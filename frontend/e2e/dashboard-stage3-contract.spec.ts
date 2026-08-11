import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type Browser, type Locator, type Page } from '@playwright/test'
import { mockApi, type MockApiControl } from './mock-api'

const baseURL = 'http://127.0.0.1:5174'
const outputName = (process.env.VISUAL_OUTPUT_NAME?.trim() || 'stage3-dashboard-20260728-a')
  .replace(/[^A-Za-z0-9._-]/g, '_')
const outputDirectory = resolve(process.cwd(), 'test-results', outputName, 'fixtures')
const capturesDirectory = resolve(outputDirectory, 'captures')
const screenshots: Array<Record<string, unknown>> = []
const computedEvidence: Record<string, unknown> = {}

function fileBinding(file: string, path: string) {
  const bytes = readFileSync(path)
  return {
    file,
    bytes: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex').toUpperCase(),
  }
}

function evidenceBindings() {
  return [
    fileBinding('frontend/src/views/DashboardView.vue', resolve(process.cwd(), 'src/views/DashboardView.vue')),
    fileBinding('frontend/src/views/DashboardView.test.ts', resolve(process.cwd(), 'src/views/DashboardView.test.ts')),
    fileBinding('frontend/src/stores/ai.ts', resolve(process.cwd(), 'src/stores/ai.ts')),
    fileBinding('frontend/src/stores/ai-domain.test.ts', resolve(process.cwd(), 'src/stores/ai-domain.test.ts')),
    fileBinding('frontend/src/api/ai-client.ts', resolve(process.cwd(), 'src/api/ai-client.ts')),
    fileBinding('frontend/src/api/ai-demo.ts', resolve(process.cwd(), 'src/api/ai-demo.ts')),
    fileBinding('frontend/src/style.css', resolve(process.cwd(), 'src/style.css')),
    fileBinding('frontend/src/components/ai/AiCommandBar.vue', resolve(process.cwd(), 'src/components/ai/AiCommandBar.vue')),
    fileBinding('frontend/src/components/ai/AiSafetyState.vue', resolve(process.cwd(), 'src/components/ai/AiSafetyState.vue')),
    fileBinding('frontend/e2e/dashboard-stage3-contract.spec.ts', resolve(process.cwd(), 'e2e/dashboard-stage3-contract.spec.ts')),
    fileBinding('frontend/e2e/mock-api.ts', resolve(process.cwd(), 'e2e/mock-api.ts')),
    fileBinding('frontend/playwright.stage3-dashboard.config.ts', resolve(process.cwd(), 'playwright.stage3-dashboard.config.ts')),
    fileBinding(
      '.planning/20260727-ui-prototype-texture-reassessment/scripts/compare_visuals.py',
      resolve(process.cwd(), '../.planning/20260727-ui-prototype-texture-reassessment/scripts/compare_visuals.py'),
    ),
  ]
}

const viewports = [
  { name: '1920x1080', width: 1920, height: 1080 },
  { name: '1366x768', width: 1366, height: 768 },
  { name: '1586x992', width: 1586, height: 992 },
  { name: '1536x1024', width: 1536, height: 1024 },
  { name: '1505x1045', width: 1505, height: 1045 },
  { name: '390x844', width: 390, height: 844 },
] as const

type DashboardScenario = 'success' | 'low-confidence' | 'no-source' | 'degraded'
  | 'failed' | 'timed-out' | 'loading'

function pngDimensions(path: string) {
  const bytes = readFileSync(path)
  if (bytes.length < 24 || bytes.subarray(1, 4).toString('ascii') !== 'PNG') {
    throw new Error(`${path} 不是有效 PNG`)
  }
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) }
}

function channel(value: number) {
  const normalized = value / 255
  return normalized <= 0.03928 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4
}

function luminance(color: string) {
  const channels = color.match(/[\d.]+/g)?.slice(0, 3).map(Number)
  if (!channels || channels.length !== 3) throw new Error(`无法解析颜色 ${color}`)
  return 0.2126 * channel(channels[0]) + 0.7152 * channel(channels[1]) + 0.0722 * channel(channels[2])
}

function contrastRatio(foreground: string, background: string) {
  const light = Math.max(luminance(foreground), luminance(background))
  const dark = Math.min(luminance(foreground), luminance(background))
  return (light + 0.05) / (dark + 0.05)
}

async function elementStyle(locator: Locator) {
  return locator.evaluate((element) => {
    const style = getComputedStyle(element)
    const bounds = element.getBoundingClientRect()
    return {
      tagName: element.tagName.toLowerCase(),
      className: element.getAttribute('class') ?? '',
      text: element.textContent?.trim().slice(0, 160) ?? '',
      fontFamily: style.fontFamily,
      fontSize: style.fontSize,
      fontWeight: style.fontWeight,
      lineHeight: style.lineHeight,
      color: style.color,
      backgroundColor: style.backgroundColor,
      border: style.border,
      borderRadius: style.borderRadius,
      boxShadow: style.boxShadow,
      padding: style.padding,
      gap: style.gap,
      width: bounds.width,
      height: bounds.height,
      x: bounds.x,
      y: bounds.y,
      right: bounds.right,
      bottom: bounds.bottom,
    }
  })
}

async function createPage(browser: Browser, viewport: { width: number; height: number }) {
  const context = await browser.newContext({
    baseURL,
    viewport,
    deviceScaleFactor: 1,
    reducedMotion: 'reduce',
  })
  const page = await context.newPage()
  const mockApiControl = await mockApi(page, true)
  const browserErrors: string[] = []
  page.on('pageerror', (error) => browserErrors.push(`pageerror: ${error.message}`))
  page.on('console', (message) => {
    if (message.type() === 'error') browserErrors.push(`console: ${message.text()}`)
  })
  return { context, page, browserErrors, mockApiControl }
}

async function setSessionPermissions(page: Page, mockApiControl: MockApiControl, permissions: string[]) {
  mockApiControl.setSessionPermissions(permissions)
  await page.route('**/api/auth/me', async (route) => route.fulfill({
    json: {
      code: 0,
      message: 'success',
      data: {
        id: 1,
        username: 'stage3-reviewer',
        userName: '阶段三验收用户',
        roleCode: 'STAGE3_REVIEWER',
        roleCodes: ['STAGE3_REVIEWER'],
        permissions,
      },
    },
  }))
}

type ProtectedFixtureRequest = {
  key: string
  path: string
  method?: 'GET' | 'POST'
  body?: Record<string, unknown>
}

const protectedFixtureRequests: ProtectedFixtureRequest[] = [
  { key: 'risk-list', path: '/api/ai/risk-cases' },
  { key: 'risk-detail', path: '/api/ai/risk-cases/risk-repair-backlog-001' },
  { key: 'proposal-list', path: '/api/ai/proposals' },
  { key: 'proposal-detail', path: '/api/ai/proposals/proposal-repair-001' },
  { key: 'audit-list', path: '/api/ai/audit/runs' },
  { key: 'audit-detail', path: '/api/ai/audit/runs/run-demo-001' },
  { key: 'audit-costs', path: '/api/ai/audit/costs' },
  { key: 'users-read', path: '/api/users' },
  {
    key: 'users-write',
    path: '/api/users',
    method: 'POST',
    body: {
      username: 'stage3-denied-user',
      displayName: '阶段三拒绝写入样本',
      password: 'Fixture-only-password-1!',
      enabled: true,
      roleIds: [4],
    },
  },
  { key: 'roles-read', path: '/api/roles' },
  { key: 'role-options', path: '/api/roles/options' },
  { key: 'permissions-read', path: '/api/permissions' },
]

async function protectedFixtureStatuses(page: Page) {
  return page.evaluate(async (requests) => Object.fromEntries(await Promise.all(requests.map(async (request) => {
    const response = await fetch(request.path, {
      method: request.method ?? 'GET',
      headers: request.body ? { 'Content-Type': 'application/json' } : undefined,
      body: request.body ? JSON.stringify(request.body) : undefined,
    })
    return [request.key, response.status]
  }))), protectedFixtureRequests)
}

async function setEmptyBusinessData(page: Page) {
  for (const path of ['/api/dashboard/statistics', '/api/dashboard/check-in-trend']) {
    await page.route(`**${path}`, async (route) => route.fulfill({
      json: { code: 0, message: 'success', data: [] },
    }))
  }
}

async function openDashboard(page: Page, scenario: DashboardScenario) {
  await page.goto(`/?dashboardFixture=${scenario}`)
  await expect(page.locator('[data-dashboard-metric="dormitory"]')).toBeVisible()
  if (scenario === 'loading') {
    await expect(page.locator('.ai-brief--loading')).toContainText('正在生成运营简报')
  } else if (scenario === 'failed' || scenario === 'timed-out') {
    await expect(page.locator('.dashboard-ai-state[data-state="FAILED"]')).toBeVisible()
  } else {
    await expect(page.getByRole('article', { name: '今日 AI 运营简报' })).toBeVisible()
    await expect(page.locator('[data-brief-meta="as-of"]'))
      .toHaveAttribute('aria-label', /数据截至（北京时间） \d{4}-\d{2}-\d{2} \d{2}:\d{2}/)
  }
}

async function recordScreenshot(
  page: Page,
  file: string,
  meta: Record<string, unknown>,
  fullPage = false,
) {
  const path = resolve(capturesDirectory, file)
  let captureMode = 'viewport'
  const originalViewport = page.viewportSize()
  if (fullPage && originalViewport) {
    captureMode = 'expanded-viewport'
    const documentHeight = await page.evaluate(() => Math.max(
      document.documentElement.scrollHeight,
      document.body.scrollHeight,
    ))
    try {
      await page.setViewportSize({ width: originalViewport.width, height: documentHeight })
      await page.evaluate(() => new Promise<void>((resolve) => {
        window.dispatchEvent(new Event('resize'))
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
      }))
      const chartCanvas = page.locator('.dashboard-trend-chart canvas')
      if (await chartCanvas.isVisible().catch(() => false)) {
        await expect.poll(async () => (await inspectCanvas(page)).lineRightInk, {
          message: `${file} 全页截图前趋势图必须完成横向重绘`,
        }).toBeGreaterThan(20)
      }
      await page.screenshot({ path, animations: 'disabled', caret: 'hide', fullPage: false })
    } finally {
      await page.setViewportSize(originalViewport)
      await page.evaluate(() => new Promise<void>((resolve) => {
        window.dispatchEvent(new Event('resize'))
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
      }))
    }
  } else {
    await page.screenshot({ path, animations: 'disabled', caret: 'hide', fullPage })
  }
  const bytes = readFileSync(path)
  const dimensions = pngDimensions(path)
  screenshots.push({
    file: `captures/${file}`,
    bytes: statSync(path).size,
    sha256: createHash('sha256').update(bytes).digest('hex').toUpperCase(),
    width: dimensions.width,
    height: dimensions.height,
    fullPage,
    captureMode,
    ...meta,
  })
}

async function inspectCanvas(page: Page) {
  return page.locator('.dashboard-trend-chart canvas').evaluate((canvasElement) => {
    const canvas = canvasElement as HTMLCanvasElement
    const context = canvas.getContext('2d')
    if (!context) return {
      nonBlank: 0, xAxisInk: 0, yAxisInk: 0, width: 0, height: 0,
      firstInkY: -1, lastInkY: -1, rowBands: [] as number[], ariaLabel: '', lineRightInk: 0,
    }
    const { width, height } = canvas
    const pixels = context.getImageData(0, 0, width, height).data
    let nonBlank = 0
    let xAxisInk = 0
    let yAxisInk = 0
    let firstInkY = -1
    let lastInkY = -1
    let lineRightInk = 0
    const rowBands = Array.from({ length: 10 }, () => 0)
    for (let y = 0; y < height; y += 1) {
      for (let x = 0; x < width; x += 1) {
        const alpha = pixels[(y * width + x) * 4 + 3]
        if (alpha < 16) continue
        nonBlank += 1
        if (firstInkY < 0) firstInkY = y
        lastInkY = y
        rowBands[Math.min(9, Math.floor((y / Math.max(1, height)) * 10))] += 1
        if (y >= height * 0.70) xAxisInk += 1
        if (x <= width * 0.16) yAxisInk += 1
        const red = pixels[(y * width + x) * 4]
        const green = pixels[(y * width + x) * 4 + 1]
        const blue = pixels[(y * width + x) * 4 + 2]
        if (x >= width * 0.62 && red < 80 && green >= 60 && green < 160 && blue > 170) {
          lineRightInk += 1
        }
      }
    }
    return {
      nonBlank,
      xAxisInk,
      yAxisInk,
      width,
      height,
      firstInkY,
      lastInkY,
      rowBands,
      lineRightInk,
      ariaLabel: canvas.getAttribute('aria-label')
        ?? canvas.parentElement?.getAttribute('aria-label')
        ?? canvas.closest('.dashboard-trend-chart')?.getAttribute('aria-label')
        ?? '',
    }
  })
}

async function openChartTooltip(page: Page) {
  const chart = page.locator('.dashboard-trend-chart')
  const bounds = await chart.boundingBox()
  expect(bounds, '趋势图必须具有可测量布局').not.toBeNull()
  const xFractions = [0.18, 0.3, 0.42, 0.54, 0.66, 0.78]
  const yFractions = [0.42, 0.58, 0.72, 0.82]
  for (const xFraction of xFractions) {
    for (const yFraction of yFractions) {
      await page.mouse.move(
        bounds!.x + bounds!.width * xFraction,
        bounds!.y + bounds!.height * yFraction,
      )
      const tooltip = await chart.evaluate((element) => {
        const candidates = Array.from(element.querySelectorAll<HTMLElement>('div'))
        const visible = candidates.find((candidate) => {
          const style = getComputedStyle(candidate)
          const box = candidate.getBoundingClientRect()
          return style.position === 'absolute'
            && style.display !== 'none'
            && style.visibility !== 'hidden'
            && box.width > 20
            && box.height > 10
            && /入住办理|累计入住/.test(candidate.textContent ?? '')
        })
        return visible?.textContent?.replace(/\s+/g, ' ').trim() ?? ''
      })
      if (tooltip) return tooltip
    }
  }
  return ''
}

async function expectNoHorizontalOverflow(page: Page, label: string) {
  const geometry = await page.evaluate(() => ({
    viewportWidth: document.documentElement.clientWidth,
    documentWidth: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth),
    documentHeight: Math.max(document.documentElement.scrollHeight, document.body.scrollHeight),
    viewportHeight: window.innerHeight,
  }))
  expect(geometry.documentWidth, `${label} 页面横向溢出`).toBeLessThanOrEqual(geometry.viewportWidth + 1)
  return geometry
}

test.describe.configure({ mode: 'serial' })

test.beforeAll(() => mkdirSync(capturesDirectory, { recursive: true }))

test.afterAll(() => {
  const computedStylesPath = resolve(outputDirectory, 'computed-styles.json')
  writeFileSync(computedStylesPath, `${JSON.stringify(computedEvidence, null, 2)}\n`)
  writeFileSync(resolve(outputDirectory, 'manifest.json'), `${JSON.stringify({
    generatedAt: new Date().toISOString(),
    fixtureMode: 'DemoAiClient typed dashboard fixture; query selection requires VITE_VISUAL_EVIDENCE_ENABLED=true',
    businessDataMode: 'mockApi deterministic local HTTP contracts; no production writes',
    viewports: viewports.map(({ name }) => name),
    capturePreferences: {
      reducedMotion: 'reduce',
      rationale: 'All deterministic fixture captures disable motion so ECharts and UI transitions settle before evidence is recorded.',
    },
    zoomEvidence: '960x540 CSS viewport is the 200% reflow equivalent of 1920x1080',
    evidenceBindings: evidenceBindings(),
    computedStylesBinding: fileBinding('fixtures/computed-styles.json', computedStylesPath),
    screenshots,
  }, null, 2)}\n`)
})

test('Dashboard 成功态通过六正式视口、Canvas、排版、对比度与滚动合同', async ({ browser }) => {
  for (const viewport of viewports) {
    const { context, page, browserErrors } = await createPage(browser, viewport)
    try {
      await openDashboard(page, 'success')
      const brief = page.getByRole('article', { name: '今日 AI 运营简报' })
      await expect(brief.locator('[data-brief-meta="confidence"]')).toHaveAttribute('aria-label', '置信度 92%')
      await expect(page.locator('[data-risk-category]')).toHaveCount(4)
      const activeRiskStatus = page.locator('[data-risk-state="active"] .risk-row__status').first()
      await expect(activeRiskStatus).toBeVisible()
      await expect(activeRiskStatus).toContainText(/高风险|中风险|低风险/)
      await expect(activeRiskStatus).toContainText(/[1-9]\d* 项风险/)
      await expect(page.getByRole('region', { name: '入住办理趋势' })).toBeVisible()
      await expect(page.locator('.trend-meta')).toContainText('入住办理（人次）')
      await expect(page.locator('.trend-meta')).toContainText('累计入住（人次）')

      if (viewport.width <= 768) {
        await page.locator('.trend-panel').scrollIntoViewIfNeeded()
      }
      await expect.poll(async () => (await inspectCanvas(page)).nonBlank, {
        message: `${viewport.name} Canvas 应完成非空绘制`,
      }).toBeGreaterThan(100)
      const canvas = await inspectCanvas(page)
      expect(canvas.xAxisInk, `${viewport.name} Canvas 应包含横轴刻度或标签像素：${JSON.stringify(canvas)}`).toBeGreaterThan(20)
      expect(canvas.yAxisInk, `${viewport.name} Canvas 应包含纵轴刻度或标签像素：${JSON.stringify(canvas)}`).toBeGreaterThan(20)
      expect(canvas.lineRightInk, `${viewport.name} 折线应完成到图表右侧：${JSON.stringify(canvas)}`).toBeGreaterThan(20)
      expect(canvas.ariaLabel, `${viewport.name} 图表应提供可读 ARIA 描述`).toMatch(/每日入住办理人次与累计入住人次/)
      await expect(page.locator('.dashboard-trend-chart')).toHaveAttribute('role', 'img')
      await expect(page.locator('.dashboard-trend-chart')).toHaveAttribute('aria-label', /每日入住办理人次与累计入住人次/)
      const approvalFlowSizes = await page.locator('.approval-flow').evaluateAll((flows) => (
        flows.map((flow) => flow.children.length)
      ))
      expect(approvalFlowSizes, `${viewport.name} 审批流程只能暴露三个真实步骤`).toEqual([3, 3])
      const accessibleTrendTable = page.locator('[data-trend-accessible-table]')
      await expect(accessibleTrendTable).toHaveCount(1)
      await expect(accessibleTrendTable.locator('tbody tr')).toHaveCount(7)
      const geometry = await expectNoHorizontalOverflow(page, viewport.name)
      const visibleMetrics = page.locator('[data-dashboard-metric]:visible')
      await expect(visibleMetrics).toHaveCount(viewport.width <= 768 ? 4 : 5)
      await expect(page.locator('[data-dashboard-metric="dormitory"] .metric-card__copy strong'))
        .toContainText('4')
      if (viewport.width > 1100) {
        await expect(page.locator('[data-dashboard-metric="approvals"] .metric-card__copy strong'))
          .toContainText('2')
      }
      await expect(page.locator('[data-dashboard-metric="dormitory"] .anticon-bank')).toBeVisible()
      await expect(page.locator('[data-dashboard-metric="vacant"] .anticon-appstore')).toBeVisible()
      if (viewport.width <= 768) {
        await expect(page.locator('.brief-document-icon.anticon-file-text')).toBeVisible()
        await expect(page.locator('.brief-ai-icon.anticon-robot')).toBeHidden()
      } else {
        await expect(page.locator('.brief-ai-icon.anticon-robot')).toBeVisible()
        await expect(page.locator('.brief-document-icon.anticon-file-text')).toBeHidden()
      }

      const metricGeometry = await visibleMetrics.evaluateAll((elements) => elements.map((element) => {
        const bounds = element.getBoundingClientRect()
        return { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height }
      }))
      expect(
        Math.max(...metricGeometry.map(({ width }) => width)) - Math.min(...metricGeometry.map(({ width }) => width)),
        `${viewport.name} KPI 应保持等宽`,
      ).toBeLessThanOrEqual(8)

      const styles = {
        view: await elementStyle(page.locator('.dashboard-view')),
        command: await elementStyle(page.locator('.dashboard-command .ai-command-bar')),
        metric: await elementStyle(page.locator('.metric-card').first()),
        metricTitle: await elementStyle(page.locator('.metric-card__copy > span').first()),
        metricValue: await elementStyle(page.locator('.metric-card__copy strong').first()),
        brief: await elementStyle(brief),
        briefTitle: await elementStyle(brief.locator('h2')),
        briefSummary: await elementStyle(brief.locator('.ai-brief__summary')),
        briefFactLabel: await elementStyle(brief.locator('.ai-brief__fact-label').first()),
        briefFactValue: await elementStyle(brief.locator('.ai-brief__fact-value').first()),
        riskPanel: await elementStyle(page.locator('.risk-panel')),
        riskTitle: await elementStyle(page.locator('.risk-row__label strong').first()),
        trendPanel: await elementStyle(page.locator('.trend-panel')),
        pendingPanel: await elementStyle(page.locator('.pending-panel')),
      }
      computedEvidence[viewport.name] = { geometry, canvas, metricGeometry, styles }

      const mobileCoreLabelMinimum = viewport.width <= 768 ? 14 : 12
      for (const [selector, minimum] of [
        ['.metric-card__copy > span', mobileCoreLabelMinimum],
        ['.metric-card__copy strong', 22],
        ['.panel-heading h2', 16],
        ['.ai-brief__fact-label', mobileCoreLabelMinimum],
        ['.ai-brief__fact-value', 12],
        ['.risk-row__label strong', 14],
        ['.risk-row__status', 14],
        ['.risk-row__status small:visible', 14],
        ['.trend-window select', mobileCoreLabelMinimum],
        ['.trend-meta', mobileCoreLabelMinimum],
        ['.pending-mobile-list strong:visible', 14],
        ['.pending-mobile-list small:visible', 14],
        ['.ai-brief__warning:visible', 14],
        ['.pending-panel__mobile-safety .ai-safety-state:visible', 14],
      ] as const) {
        const targets = page.locator(selector)
        for (let index = 0; index < await targets.count(); index += 1) {
          const style = await elementStyle(targets.nth(index))
          expect(Number.parseFloat(style.fontSize), `${viewport.name} ${selector} 字号过小`).toBeGreaterThanOrEqual(minimum)
        }
      }
      expect(Number.parseFloat(styles.briefSummary.fontSize)).toBeGreaterThanOrEqual(14)
      expect(contrastRatio(styles.metricTitle.color, styles.metric.backgroundColor), `${viewport.name} KPI 标题对比度`).toBeGreaterThanOrEqual(4.5)
      expect(contrastRatio(styles.briefSummary.color, styles.brief.backgroundColor), `${viewport.name} 简报正文对比度`).toBeGreaterThanOrEqual(4.5)
      expect(contrastRatio(styles.riskTitle.color, styles.riskPanel.backgroundColor), `${viewport.name} 风险标题对比度`).toBeGreaterThanOrEqual(4.5)
      const legendBar = await elementStyle(page.locator('.legend-bar'))
      expect(
        contrastRatio(legendBar.backgroundColor, styles.trendPanel.backgroundColor),
        `${viewport.name} 趋势柱图与图例非文本对比度不足`,
      ).toBeGreaterThanOrEqual(3)

      if (viewport.width <= 768) {
        const riskBounds = await page.locator('.risk-panel').boundingBox()
        const trendBounds = await page.locator('.trend-panel').boundingBox()
        const pending = page.locator('[data-dashboard-section="pending"]')
        const pendingTop = await pending.evaluate((element) => (
          element.getBoundingClientRect().top + window.scrollY
        ))
        expect(riskBounds!.y, '移动端风险概览应先于趋势图').toBeLessThan(trendBounds!.y)
        expect(pendingTop, '390x844 首屏底部应露出待处理事项，形成与原型一致的信息连续性')
          .toBeLessThanOrEqual(viewport.height - 20)
        expect(geometry.documentHeight, '移动端完整驾驶舱不应因过度留白拉长')
          .toBeLessThanOrEqual(1320)
        expect(geometry.documentHeight, '移动端仍应保留自然纵向滚动，不靠缩小字号硬塞进首屏')
          .toBeGreaterThan(viewport.height + 300)
        const citationLabel = await elementStyle(page.locator('.ai-brief__citations .ai-brief__fact-label'))
        expect(citationLabel.width, '移动端“引用来源”应保持横向可读').toBeGreaterThanOrEqual(48)
        expect(citationLabel.height, '移动端“引用来源”不应逐字竖排').toBeLessThanOrEqual(22)
        const riskLayout = await page.locator('.risk-row').evaluateAll((elements) => elements.map((element) => {
          const row = element.getBoundingClientRect()
          const label = element.querySelector<HTMLElement>('.risk-row__label')!.getBoundingClientRect()
          const status = element.querySelector<HTMLElement>('.risk-row__status')!.getBoundingClientRect()
          const overlaps = label.left < status.right && label.right > status.left
            && label.top < status.bottom && label.bottom > status.top
          return {
            category: element.getAttribute('data-risk-category'),
            overlaps,
            contained: status.left >= row.left - 1 && status.right <= row.right + 1,
            iconColor: getComputedStyle(element.querySelector<HTMLElement>('.risk-row__icon')!).color,
          }
        }))
        expect(riskLayout.filter(({ overlaps, contained }) => overlaps || !contained), '移动风险类别/状态不得重叠或越界').toEqual([])
        expect(new Set(riskLayout.map(({ iconColor }) => iconColor)).size, '四类风险应有独立语义图标色').toBe(4)
        const riskRows = await page.locator('.risk-row').evaluateAll((elements) => elements.map((element) => {
          const bounds = element.getBoundingClientRect()
          return { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height }
        }))
        expect(Math.max(...riskRows.map(({ y }) => y)) - Math.min(...riskRows.map(({ y }) => y)), '移动四类风险应在同一行').toBeLessThanOrEqual(2)
        expect(riskRows.every(({ width, height }) => width >= 44 && height >= 44), '移动风险卡应保持触控目标').toBe(true)
        const riskCountContrast = await page.locator('.risk-row').evaluateAll((elements) => elements.map((element) => {
          const count = element.querySelector<HTMLElement>('.risk-row__status strong')!
          return {
            category: element.getAttribute('data-risk-category'),
            foreground: getComputedStyle(count).color,
            background: getComputedStyle(element).backgroundColor,
          }
        }))
        for (const item of riskCountContrast) {
          expect(
            contrastRatio(item.foreground, item.background),
            `移动风险数量对比度不足：${item.category}`,
          ).toBeGreaterThanOrEqual(4.5)
        }
        const touchTargets = [
          page.locator('[data-ai-assistant-trigger]'),
          page.locator('.notification-button'),
          page.locator('.profile-button'),
          page.locator('.dashboard-command .ai-command-bar__button'),
          page.locator('.trend-window select'),
          page.locator('.risk-panel .panel-heading a'),
          page.locator('[data-mobile-cta]').first(),
          page.locator('[data-mobile-pending-toggle]'),
        ]
        for (const target of touchTargets) {
          const targetStyle = await elementStyle(target)
          expect(targetStyle.width, `${viewport.name} 触控目标宽度不足`).toBeGreaterThanOrEqual(44)
          expect(targetStyle.height, `${viewport.name} 触控目标高度不足`).toBeGreaterThanOrEqual(44)
        }
        await pending.scrollIntoViewIfNeeded()
        await expect(pending).toBeVisible()
        await expect(page.locator('[data-mobile-cta]:visible')).toHaveCount(2)
        await expect(page.locator('[data-mobile-cta="approval"]')).toBeVisible()
        await expect(page.locator('[data-mobile-cta="suggestions"]')).toBeVisible()
        await expect(page.locator('[data-mobile-cta="repair"]')).toHaveCount(0)
        await expect(pending.locator('.pending-panel__heading')).toContainText('（5 条）')
        await expect(pending.locator('.pending-panel__heading')).toContainText('（5 类）')
        await expect(pending.locator('.pending-panel__count--desktop')).toBeHidden()
        await expect(pending.locator('.pending-panel__count--mobile')).toBeVisible()
        const mobileToggle = pending.locator('[data-mobile-pending-toggle]')
        await expect(mobileToggle).toHaveAttribute('aria-expanded', 'false')
        await expect(mobileToggle).toContainText('查看全部 5 类')
        await mobileToggle.click()
        await expect(page.locator('[data-mobile-cta]:visible')).toHaveCount(5)
        await expect(page.locator('[data-mobile-cta="repair"]')).toBeVisible()
        await expect(page.locator('[data-mobile-cta="payment"]')).toBeVisible()
        await expect(mobileToggle).toHaveAttribute('aria-expanded', 'true')
        await mobileToggle.click()
        await expect(page.locator('[data-mobile-cta]:visible')).toHaveCount(2)
        await expect(mobileToggle).toHaveAttribute('aria-expanded', 'false')
        const pendingLink = page.locator('[data-mobile-cta]').first()
        await pendingLink.focus()
        await expect(pendingLink).toBeFocused()
        expect(await page.evaluate(() => window.scrollY), '移动端后续区块应通过自然纵向滚动到达').toBeGreaterThan(0)
        await page.locator('[data-dashboard-section="guardrails"]').scrollIntoViewIfNeeded()
        await expect(page.locator('[data-dashboard-section="guardrails"] [data-state]')).toHaveCount(2)
        await page.locator('[data-dashboard-section="approval-flow"]').scrollIntoViewIfNeeded()
        await expect(page.locator('.approval-flow--mobile')).toBeVisible()
        await page.evaluate(() => window.scrollTo(0, 0))
      } else {
        await expect(page.locator('.pending-table')).toBeVisible()
        await expect(page.locator('.pending-reflow-list')).toBeHidden()
        await expect(page.locator('[data-pending-group]')).toHaveCount(2)
        const summaryGeometry = await page.locator(
          '[data-pending-column="recommendation"], [data-pending-column="preview"]',
        ).evaluateAll((elements) => elements.map((element) => {
          const target = element as HTMLElement
          return {
            text: target.textContent?.trim() ?? '',
            clippedHorizontally: target.scrollWidth > target.clientWidth + 1,
            clippedVertically: target.scrollHeight > target.clientHeight + 1,
            whiteSpace: getComputedStyle(target).whiteSpace,
          }
        }))
        expect(summaryGeometry.length, `${viewport.name} 应呈现桌面待办摘要`).toBeGreaterThan(0)
        expect(summaryGeometry.filter(({ clippedHorizontally, clippedVertically }) => (
          clippedHorizontally || clippedVertically
        )), `${viewport.name} 待办关键摘要不得被裁切`).toEqual([])
        expect(summaryGeometry.every(({ whiteSpace }) => whiteSpace !== 'nowrap'), `${viewport.name} 待办摘要必须允许换行`).toBe(true)
        const pendingRows = page.locator('[data-pending-row]')
        await expect(pendingRows).toHaveCount(5)
        await expect(pendingRows.nth(0)).toHaveAttribute('data-pending-kind', 'ai')
        await expect(pendingRows.nth(1)).toHaveAttribute('data-pending-kind', 'ai')
        await expect(page.locator('.pending-table thead th')).toHaveCount(7)
        await expect(page.locator('.pending-table__actions [data-pending-view]:visible')).toHaveCount(5)
        await expect(page.locator('.pending-table__actions [data-pending-approval]:visible')).toHaveCount(2)
        await expect(page.locator('.pending-panel__count--desktop')).toBeVisible()
        await expect(page.locator('.pending-panel__count--mobile')).toBeHidden()
        const firstProposalRow = pendingRows.nth(0)
        await expect(firstProposalRow.locator('[data-pending-view]')).toHaveAttribute(
          'href',
          '/ai/approvals?proposal=proposal-repair-001&mode=view',
        )
        await expect(firstProposalRow.locator('[data-pending-approval]')).toHaveAttribute(
          'href',
          '/ai/approvals?proposal=proposal-repair-001&mode=approve',
        )
        await expect(firstProposalRow.locator('[data-pending-view]')).toHaveAttribute('aria-label', /方案编号 proposal-repair-001/)
        await expect(firstProposalRow.locator('[data-pending-approval]')).toHaveAttribute('aria-label', /方案编号 proposal-repair-001/)
        const desktopActions = page.locator('.pending-table__actions a:visible')
        await expect(desktopActions).toHaveCount(7)
        for (let index = 0; index < await desktopActions.count(); index += 1) {
          const targetStyle = await elementStyle(desktopActions.nth(index))
          expect(targetStyle.width, `${viewport.name} 桌面待办操作宽度不足`).toBeGreaterThanOrEqual(44)
          expect(targetStyle.height, `${viewport.name} 桌面待办操作高度不足`).toBeGreaterThanOrEqual(44)
        }
        if (viewport.name === '1366x768') {
          expect(geometry.documentHeight, '1366px 待办区不应发生卡片断点导致的密度断崖').toBeLessThanOrEqual(1200)
          const pendingPanel = await page.locator('.pending-panel').boundingBox()
          expect(pendingPanel!.height, '1366px 待办面板应保持紧凑表格密度').toBeLessThanOrEqual(520)
        }
        if (viewport.name === '1586x992') {
          const lastPendingRow = await pendingRows.last().boundingBox()
          expect(lastPendingRow!.y + lastPendingRow!.height, '1586x992 应完整显示五行待办')
            .toBeLessThanOrEqual(viewport.height + 1)
          const pendingPanel = await page.locator('.pending-panel').boundingBox()
          const guardrail = await page.locator('.pending-panel__guardrail').boundingBox()
          expect(pendingPanel!.y + pendingPanel!.height, '1586x992 应完整显示待办面板')
            .toBeLessThanOrEqual(viewport.height + 1)
          expect(guardrail!.y + guardrail!.height, '1586x992 应完整显示人工审批守护栏')
            .toBeLessThanOrEqual(viewport.height + 1)
        }
      }

      await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur())
      await recordScreenshot(page, `dashboard-success-${viewport.name}.png`, {
        state: 'success', viewport: viewport.name,
      })
      await recordScreenshot(page, `dashboard-success-${viewport.name}-full.png`, {
        state: 'success', viewport: viewport.name,
      }, true)
      if (viewport.name === '1366x768') {
        await page.goto('/ai/approvals?proposal=proposal-repair-001&mode=approve')
        await expect(page.locator('[data-testid="approval-preview-panel"]')).toContainText('proposal-repair-001')
        const attestation = page.locator('[data-testid="approval-attestation"] input')
        await expect(attestation).toBeFocused()
        await expect(attestation).not.toBeChecked()
        const approvalLanding = await page.evaluate(() => ({
          scrollY: window.scrollY,
          activeTestId: (document.activeElement?.closest('[data-testid]') as HTMLElement | null)?.dataset.testid ?? '',
        }))
        expect(approvalLanding.scrollY, '进入审批应把人工确认区带入视口').toBeGreaterThan(0)
        expect(approvalLanding.activeTestId).toBe('approval-attestation')
      }
      expect(browserErrors, `${viewport.name} 浏览器错误`).toEqual([])
    } finally {
      await context.close()
    }
  }
})

test('Dashboard 图表 tooltip 与键盘引用交互可验证', async ({ browser }) => {
  const { context, page, browserErrors } = await createPage(browser, { width: 1586, height: 992 })
  try {
    await openDashboard(page, 'success')
    const tooltip = await openChartTooltip(page)
    expect(tooltip, '趋势图 hover 应展示含系列名称的 tooltip').toMatch(/入住办理|累计入住/)

    const trendRange = page.locator('.trend-window select')
    await trendRange.focus()
    await expect(trendRange).toBeFocused()
    await page.keyboard.press('End')
    await expect(trendRange).toHaveValue('30')
    await expect(page.locator('[data-trend-accessible-table] tbody tr')).toHaveCount(30)

    const commandInput = page.locator('.dashboard-command .ai-command-bar__input')
    const placeholderAppearance = await commandInput.evaluate((element) => {
      const placeholder = getComputedStyle(element, '::placeholder')
      const surface = getComputedStyle(element.closest('.ai-command-bar') ?? element).backgroundColor
      return { color: placeholder.color, opacity: placeholder.opacity, surface }
    })
    expect(
      contrastRatio(placeholderAppearance.color, placeholderAppearance.surface),
      `查询框 placeholder 对比度不足：${JSON.stringify(placeholderAppearance)}`,
    ).toBeGreaterThanOrEqual(4.5)
    await commandInput.fill('检查焦点可辨识度')
    const commandButton = page.locator('.dashboard-command .ai-command-bar__button')
    await commandButton.focus()
    await expect(commandButton).toBeFocused()
    const commandFocusAppearance = await commandButton.evaluate((element) => {
      const style = getComputedStyle(element)
      const surface = getComputedStyle(element.closest('.ai-command-bar') ?? element).backgroundColor
      return { outlineColor: style.outlineColor, surface }
    })
    expect(
      contrastRatio(commandFocusAppearance.outlineColor, commandFocusAppearance.surface),
      `命令按钮焦点指示器对比度不足：${JSON.stringify(commandFocusAppearance)}`,
    ).toBeGreaterThanOrEqual(3)

    const citations = page.locator('.ai-brief__citations')
    const summary = citations.locator('summary')
    await summary.focus()
    await expect(summary).toBeFocused()
    const focusAppearance = await summary.evaluate((element) => {
      const style = getComputedStyle(element)
      const surface = getComputedStyle(element.closest('[data-brief-meta]') ?? element).backgroundColor
      return { outlineColor: style.outlineColor, surface }
    })
    expect(
      contrastRatio(focusAppearance.outlineColor, focusAppearance.surface),
      `引用来源焦点指示器对比度不足：${JSON.stringify(focusAppearance)}`,
    ).toBeGreaterThanOrEqual(3)
    await page.keyboard.press('Enter')
    await expect(citations).toHaveAttribute('open', '')
    await expect(citations.locator('li')).toHaveCount(3)
    await recordScreenshot(page, 'dashboard-state-citations-keyboard.png', {
      state: 'citations-keyboard', viewport: '1586x992', tooltip,
    })
    expect(browserErrors).toEqual([])
  } finally {
    await context.close()
  }
})

test('Dashboard 低置信、无来源、降级、失败、超时与加载态均诚实呈现', async ({ browser }) => {
  const scenarios = ['low-confidence', 'no-source', 'degraded', 'failed', 'timed-out', 'loading'] as const
  for (const scenario of scenarios) {
    const { context, page, browserErrors } = await createPage(browser, { width: 1366, height: 768 })
    try {
      await openDashboard(page, scenario)
      if (scenario === 'low-confidence') {
        await expect(page.locator('.ai-brief [data-state="WARNING"]')).toContainText('置信度较低')
      } else if (scenario === 'no-source') {
        await expect(page.locator('.ai-brief [data-state="NO_GROUNDED"]')).toContainText('暂无可靠来源')
        const unavailableRisks = page.locator('[data-risk-state="unavailable"]')
        await expect(unavailableRisks).toHaveCount(4)
        for (let index = 0; index < await unavailableRisks.count(); index += 1) {
          await expect(unavailableRisks.nth(index)).toContainText('暂无数据')
          await expect(unavailableRisks.nth(index)).not.toContainText('0 项风险')
        }
      } else if (scenario === 'degraded') {
        await expect(page.locator('.ai-brief [data-state="DEGRADED"]')).toContainText('降级')
      } else if (scenario === 'failed') {
        const failure = page.locator('.dashboard-ai-state[data-state="FAILED"]')
        await expect(failure).toContainText('查询失败')
        expect(Number.parseFloat((await elementStyle(failure)).fontSize), '失败态正文不得小于 14px').toBeGreaterThanOrEqual(14)
      } else if (scenario === 'timed-out') {
        const failure = page.locator('.dashboard-ai-state[data-state="FAILED"]')
        await expect(failure).toContainText('查询超时')
        expect(Number.parseFloat((await elementStyle(failure)).fontSize), '超时态正文不得小于 14px').toBeGreaterThanOrEqual(14)
      } else {
        await expect(page.locator('.ai-brief--loading')).toHaveAttribute('aria-busy', 'true')
      }
      await expectNoHorizontalOverflow(page, scenario)
      await recordScreenshot(page, `dashboard-state-${scenario}.png`, {
        state: scenario, viewport: '1366x768',
      })
      expect(browserErrors, `${scenario} 浏览器错误`).toEqual([])
    } finally {
      await context.close()
    }
  }
})

test('Dashboard 移动端在简报上下文保留低置信、无来源与降级警示', async ({ browser }) => {
  const scenarios = [
    { scenario: 'low-confidence', state: 'WARNING', text: '置信度较低' },
    { scenario: 'no-source', state: 'NO_GROUNDED', text: '暂无可靠来源' },
    { scenario: 'degraded', state: 'DEGRADED', text: '降级' },
  ] as const

  for (const item of scenarios) {
    const { context, page, browserErrors } = await createPage(browser, { width: 390, height: 844 })
    try {
      await openDashboard(page, item.scenario)
      const warning = page.locator(`.ai-brief__warning[data-state="${item.state}"]`)
      await expect(warning).toBeVisible()
      await expect(warning).toContainText(item.text)
      await recordScreenshot(page, `dashboard-state-${item.scenario}-mobile.png`, {
        state: item.scenario, viewport: '390x844', warningInBrief: true,
      })
      expect(browserErrors).toEqual([])
    } finally {
      await context.close()
    }
  }
})

test('Dashboard 成功后刷新失败时将旧简报和风险标记为上一版只读结果', async ({ browser }) => {
  const { context, page, browserErrors } = await createPage(browser, { width: 1366, height: 768 })
  try {
    await openDashboard(page, 'success')
    await page.evaluate(() => window.history.replaceState({}, '', '/?dashboardFixture=failed'))
    await page.locator('.dashboard-command .ai-command-bar__input').fill('刷新本周运营风险')
    await page.locator('.dashboard-command .ai-command-bar__button').click()

    const brief = page.locator('[data-dashboard-section="brief"]')
    const risks = page.locator('[data-dashboard-section="risks"]')
    await expect(brief).toHaveAttribute('data-result-freshness', 'stale')
    await expect(brief.locator('.ai-brief__warning[data-state="STALE"]')).toContainText('上一版只读结果')
    await expect(brief.locator('.ai-brief__summary')).toContainText('整体运营平稳')
    await expect(risks).toHaveAttribute('data-result-freshness', 'stale')
    await expect(risks.locator('.risk-panel__stale[data-state="STALE"]')).toContainText('上一版只读结果')
    await expect(risks.locator('[data-risk-category]')).toHaveCount(4)
    await expect(page.locator('.dashboard-ai-state[data-state="FAILED"]')).toHaveCount(0)
    await expect.poll(async () => (await inspectCanvas(page)).lineRightInk, {
      message: '刷新失败后保留的趋势图仍应完整绘制到右侧',
    }).toBeGreaterThan(20)
    const staleCanvas = await inspectCanvas(page)
    await expectNoHorizontalOverflow(page, 'refresh-failed-stale')
    await recordScreenshot(page, 'dashboard-state-refresh-failed-stale.png', {
      state: 'refresh-failed-stale', viewport: '1366x768', staleResultRetained: true, staleCanvas,
    })
    expect(browserErrors).toEqual([])
  } finally {
    await context.close()
  }
})

test('Dashboard 真实业务状态与 AI 细粒度权限均诚实呈现', async ({ browser }) => {
  const states = ['business-empty', 'business-error', 'business-pending-no-ai-governance', 'permission-revoked'] as const
  for (const state of states) {
    const { context, page, browserErrors, mockApiControl } = await createPage(browser, { width: 390, height: 844 })
    try {
      if (state === 'business-empty') {
        await setSessionPermissions(page, mockApiControl, ['dashboard:read', 'ai:dashboard:query', 'ai:risk:read'])
        await setEmptyBusinessData(page)
        await openDashboard(page, 'no-source')
        await expect(page.locator('.trend-panel .panel-empty')).toContainText('暂无趋势数据')
        await expect(page.locator('[data-dashboard-metric="dormitory"]')).toContainText('0')
        await expect(page.locator('.pending-panel')).toContainText('暂无待处理事项')
        const emptyRisks = page.locator('[data-risk-category]')
        await expect(emptyRisks).toHaveCount(4)
        for (let index = 0; index < await emptyRisks.count(); index += 1) {
          const text = (await emptyRisks.nth(index).innerText()).match(/暂无/g) ?? []
          expect(text, `业务空态风险卡 ${index + 1} 不得重复“暂无”`).toHaveLength(1)
          await expect(emptyRisks.nth(index)).toHaveAttribute('data-risk-state', 'unavailable')
          await expect(emptyRisks.nth(index)).not.toContainText('0 项风险')
          const row = await emptyRisks.nth(index).boundingBox()
          const count = await emptyRisks.nth(index).locator('.risk-row__status').boundingBox()
          expect(row).not.toBeNull()
          expect(count).not.toBeNull()
          expect(count!.x + count!.width).toBeLessThanOrEqual(row!.x + row!.width + 1)
        }
      } else if (state === 'business-error') {
        await setSessionPermissions(page, mockApiControl, ['dashboard:read', 'ai:dashboard:query'])
        await page.route('**/api/dashboard/statistics', async (route) => route.fulfill({
          status: 500,
          json: { code: 500, message: '阶段三业务指标读取失败', data: null },
        }))
        await page.goto('/?dashboardFixture=success')
        const businessFailure = page.locator('.dashboard-business-state[data-state="FAILED"]')
        await expect(businessFailure).toContainText('阶段三业务指标读取失败')
        await expect(businessFailure.getByRole('button', { name: '重新加载' })).toBeVisible()
        expect(Number.parseFloat((await elementStyle(businessFailure)).fontSize), '业务错误正文不得小于 14px').toBeGreaterThanOrEqual(14)
        const unavailableMetrics = page.locator('.metric-card:not(.metric-card--approval) .metric-card__copy strong')
        await expect(unavailableMetrics).toHaveCount(4)
        for (let index = 0; index < await unavailableMetrics.count(); index += 1) {
          await expect(unavailableMetrics.nth(index)).toContainText('--')
        }
        await expect(page.locator('.trend-panel .panel-empty')).toContainText('趋势数据读取失败')
        await expect(page.locator('.pending-panel .panel-empty')).toContainText('待处理事项读取失败')
      } else if (state === 'business-pending-no-ai-governance') {
        await setSessionPermissions(page, mockApiControl, [
          'dashboard:read', 'ai:dashboard:query', 'repair:read', 'checkin:read', 'payment:read',
        ])
        await openDashboard(page, 'success')
        await expect(page.locator('.pending-panel__heading')).toContainText('（3 条）')
        await expect(page.locator('.pending-panel__heading')).toContainText('（3 类）')
        await expect(page.locator('[data-pending-row]')).toHaveCount(3)
        const mobilePendingItems = page.locator('.pending-mobile-list article')
        await expect(mobilePendingItems).toHaveCount(2)
        await expect(page.locator('[data-mobile-cta="repair"]')).toBeVisible()
        await expect(page.locator('[data-mobile-cta="application"]')).toBeVisible()
        await expect(page.locator('[data-mobile-cta="payment"]')).toHaveCount(0)
        await expect(page.locator('[data-mobile-cta="suggestions"]')).toHaveCount(0)
        await expect(page.locator('[data-mobile-cta="approval"]')).toHaveCount(0)
        const mobileToggle = page.locator('[data-mobile-pending-toggle]')
        await expect(mobileToggle).toContainText('查看全部 3 类')
        await mobileToggle.click()
        await expect(mobilePendingItems).toHaveCount(3)
        await expect(page.locator('[data-mobile-cta="payment"]')).toBeVisible()
      } else {
        await setSessionPermissions(page, mockApiControl, ['dashboard:read'])
        await page.goto('/?dashboardFixture=success')
        const permissionState = page.locator('.dashboard-ai-state[data-state="NO_PERMISSION"]')
        await expect(permissionState).toContainText('无权限')
        expect(Number.parseFloat((await elementStyle(permissionState)).fontSize), '撤权正文不得小于 14px').toBeGreaterThanOrEqual(14)
        await expect(page.locator('[data-dashboard-section="command"]')).toHaveCount(0)
        await expect(page.locator('[data-dashboard-section="brief"]')).toHaveCount(0)
      }
      await expectNoHorizontalOverflow(page, state)
      await recordScreenshot(page, `dashboard-state-${state}.png`, {
        state, viewport: '390x844',
      }, true)
      if (state === 'business-error') {
        expect(browserErrors).toEqual([
          'console: Failed to load resource: the server responded with a status of 500 (Internal Server Error)',
        ])
      } else {
        expect(browserErrors, `${state} 浏览器错误`).toEqual([])
      }
    } finally {
      await context.close()
    }
  }
})

test('Dashboard fixture 在网络边界对撤权与未登录请求返回 403/401', async ({ browser }) => {
  const { context, page, mockApiControl } = await createPage(browser, { width: 390, height: 844 })
  try {
    await setSessionPermissions(page, mockApiControl, ['dashboard:read'])
    await page.goto('/?dashboardFixture=success')

    expect(await protectedFixtureStatuses(page)).toEqual(Object.fromEntries(
      protectedFixtureRequests.map(({ key }) => [key, 403]),
    ))

    const logoutStatus = await page.evaluate(async () => (await fetch('/api/auth/logout', { method: 'POST' })).status)
    expect(logoutStatus).toBe(200)
    expect(await protectedFixtureStatuses(page)).toEqual(Object.fromEntries(
      protectedFixtureRequests.map(({ key }) => [key, 401]),
    ))
  } finally {
    await context.close()
  }
})

test('Dashboard 200% 等效重排无横向溢出并尊重 reduced motion', async ({ browser }) => {
  const { context, page, browserErrors } = await createPage(browser, { width: 960, height: 540 })
  try {
    await openDashboard(page, 'success')
    const geometry = await expectNoHorizontalOverflow(page, '200pct-1920x1080')
    const motion = await page.evaluate(() => ({
      reduced: matchMedia('(prefers-reduced-motion: reduce)').matches,
      htmlScrollBehavior: getComputedStyle(document.documentElement).scrollBehavior,
    }))
    expect(motion.reduced).toBe(true)
    computedEvidence['200pct-1920x1080'] = { geometry, motion }
    await page.locator('.pending-panel').scrollIntoViewIfNeeded()
    await expect(page.locator('.pending-panel')).toBeVisible()
    await expect(page.locator('.pending-table')).toBeHidden()
    const reflowItems = page.locator('[data-pending-reflow-item]')
    await expect(reflowItems).toHaveCount(5)
    const repairItem = page.locator('[data-pending-reflow-item="repair"]')
    await expect(repairItem).toBeVisible()
    await expect(repairItem.locator('[data-pending-detail="recommendation"]')).toContainText('人工核对')
    await expect(repairItem.locator('[data-pending-detail="preview"]')).toContainText('不自动派单')
    await expect(repairItem.locator('[data-pending-detail="confidence"]')).toContainText('规则确定')
    await expect(repairItem.locator('[data-pending-detail="citations"]')).toContainText('业务数据')
    await expect(repairItem.locator('[data-pending-detail="suggested-at"]')).toContainText('实时')
    const repairAction = repairItem.getByRole('link', { name: '查看维修' })
    await expect(repairAction).toBeVisible()
    const repairActionBox = await repairAction.boundingBox()
    expect(repairActionBox).not.toBeNull()
    expect(repairActionBox!.height).toBeGreaterThanOrEqual(44)
    await recordScreenshot(page, 'dashboard-success-200pct-1920x1080.png', {
      state: 'success', viewport: '960x540', zoomEquivalent: '200%',
    })
    await page.evaluate(() => window.scrollTo(0, 0))
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0)
    await recordScreenshot(page, 'dashboard-success-200pct-1920x1080-full.png', {
      state: 'success', viewport: '960x540', zoomEquivalent: '200%',
    }, true)
    expect(browserErrors).toEqual([])
  } finally {
    await context.close()
  }
})
