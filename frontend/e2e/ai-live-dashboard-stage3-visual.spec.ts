import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type Page } from '@playwright/test'
import { requireAiLiveIsolation } from './ai-live-isolation'
import {
  requireVisualCredentials,
  sanitizeDiagnostic,
  visualBackendEnv,
  visualBaseURL,
  visualDirectory,
} from './live-visual-settings'

const liveDirectory = resolve(visualDirectory, 'live')
const aiLiveIsolation = requireAiLiveIsolation(visualBackendEnv)

interface ApiEnvelope<T> {
  data?: T
}

interface BackendRuntimeReadiness {
  masterEnabled: boolean
  providerAlias: string
  streamingEnabled: boolean
  writeExecutionEnabled: boolean
  controls: Record<string, string>
}

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
    fileBinding('frontend/src/api/ai-http.ts', resolve(process.cwd(), 'src/api/ai-http.ts')),
    fileBinding('frontend/src/style.css', resolve(process.cwd(), 'src/style.css')),
    fileBinding('frontend/src/components/ai/AiCommandBar.vue', resolve(process.cwd(), 'src/components/ai/AiCommandBar.vue')),
    fileBinding('frontend/src/components/ai/AiSafetyState.vue', resolve(process.cwd(), 'src/components/ai/AiSafetyState.vue')),
    fileBinding('frontend/e2e/dashboard-stage3-contract.spec.ts', resolve(process.cwd(), 'e2e/dashboard-stage3-contract.spec.ts')),
    fileBinding('frontend/e2e/ai-live-dashboard-stage3-visual.spec.ts', resolve(process.cwd(), 'e2e/ai-live-dashboard-stage3-visual.spec.ts')),
    fileBinding('frontend/e2e/ai-live-global-setup.ts', resolve(process.cwd(), 'e2e/ai-live-global-setup.ts')),
    fileBinding('frontend/e2e/ai-live-isolation.ts', resolve(process.cwd(), 'e2e/ai-live-isolation.ts')),
    fileBinding('frontend/e2e/live-visual-settings.ts', resolve(process.cwd(), 'e2e/live-visual-settings.ts')),
    fileBinding('frontend/playwright.ai-live.config.ts', resolve(process.cwd(), 'playwright.ai-live.config.ts')),
    fileBinding('frontend/playwright.ai-live-dashboard-stage3.config.ts', resolve(process.cwd(), 'playwright.ai-live-dashboard-stage3.config.ts')),
    fileBinding(
      '.planning/20260727-ui-prototype-texture-reassessment/scripts/compare_visuals.py',
      resolve(process.cwd(), '../.planning/20260727-ui-prototype-texture-reassessment/scripts/compare_visuals.py'),
    ),
  ]
}

const viewports = [
  { name: '1586x992', width: 1586, height: 992, file: 'stage3-dashboard-desktop.png' },
  { name: '390x844', width: 390, height: 844, file: 'stage3-dashboard-mobile.png' },
] as const

function pngDimensions(path: string) {
  const bytes = readFileSync(path)
  if (bytes.length < 24 || bytes.subarray(1, 4).toString('ascii') !== 'PNG') {
    throw new Error(`${path} 不是有效 PNG`)
  }
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) }
}

async function loginThroughUi(page: Page) {
  const credentials = requireVisualCredentials()
  await page.goto('/login')
  await page.getByRole('textbox', { name: '用户名' }).fill(credentials.username)
  await page.getByLabel('密码').fill(credentials.password)
  const loginResponsePromise = page.waitForResponse((response) => (
    new URL(response.url()).pathname === '/api/auth/login'
    && response.request().method() === 'POST'
  ))
  await page.getByRole('button', { name: '登录' }).click()
  const response = await loginResponsePromise
  if (response.status() !== 200) {
    throw new Error(`登录失败：HTTP ${response.status()} ${sanitizeDiagnostic(await response.text())}`)
  }
  await expect(page).toHaveURL(/\/$/)
}

async function ensureDashboardReady(page: Page) {
  await expect(page.getByRole('article', { name: '今日 AI 运营简报' })).toBeVisible({ timeout: 60_000 })
  await expect(page.locator('[data-dashboard-metric="dormitory"]')).toBeVisible()
  await expect(page.locator('[data-dashboard-metric="students"]')).toBeVisible()
  await expect(page.locator('[data-dashboard-metric="vacant"]')).toBeVisible()
  await expect(page.locator('[data-dashboard-metric="repairs"]')).toBeVisible()
  await expect(page.locator('[data-risk-category]')).toHaveCount(4)
  await expect(page.locator('[data-brief-meta="as-of"]')).toHaveAttribute('aria-label', /北京时间/)
  await expect(page.locator('.ai-brief__as-of-mobile')).toHaveText(/\d{2}-\d{2} \d{2}:\d{2}|-/)
  const briefWarning = page.locator('.ai-brief__warning')
  if (await briefWarning.count()) await expect(briefWarning).toBeVisible()
  const riskRows = page.locator('[data-risk-category]')
  for (let index = 0; index < await riskRows.count(); index += 1) {
    const risk = riskRows.nth(index)
    const state = await risk.getAttribute('data-risk-state')
    if (state === 'empty') {
      await expect(risk).toContainText('未发现')
      await expect(risk).toContainText('0 项')
      await expect(risk).not.toContainText(/高风险|中风险|低风险|0 项风险/)
    } else if (state === 'unavailable') {
      await expect(risk).toContainText('暂无数据')
      await expect(risk).not.toContainText('0 项风险')
    } else {
      expect(state).toBe('active')
      const status = risk.locator('.risk-row__status')
      await expect(status).toBeVisible()
      await expect(status).toContainText(/高风险|中风险|低风险/)
      await expect(status).toContainText(/[1-9]\d* 项风险/)
    }
  }
  await expect(page.locator('.ai-brief__summary')).not.toContainText(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z/)
  await expect.poll(async () => page.locator('.dashboard-trend-chart canvas').evaluate((element) => {
    const canvas = element as HTMLCanvasElement
    const context = canvas.getContext('2d')
    if (!context) return 0
    const data = context.getImageData(0, 0, canvas.width, canvas.height).data
    let painted = 0
    for (let index = 3; index < data.length; index += 4) if (data[index] > 16) painted += 1
    return painted
  }), { timeout: 20_000, message: '真实 Dashboard Canvas 未完成绘制' }).toBeGreaterThan(100)
}

async function observeBackendRuntime(page: Page, viewport: string) {
  const response = await page.request.get('/api/ai/operations/readiness')
  expect(response.status(), `${viewport} 后端运行时诊断必须可读`).toBe(200)
  const envelope = await response.json() as ApiEnvelope<BackendRuntimeReadiness>
  expect(envelope.data, `${viewport} 后端运行时诊断缺少 data`).toBeDefined()
  const readiness = envelope.data!
  expect(readiness).toMatchObject({
    masterEnabled: true,
    providerAlias: 'fake',
    streamingEnabled: true,
    writeExecutionEnabled: false,
  })
  return {
    viewport,
    observedAt: new Date().toISOString(),
    source: 'GET /api/ai/operations/readiness',
    masterEnabled: readiness.masterEnabled,
    provider: readiness.providerAlias,
    streamingEnabled: readiness.streamingEnabled,
    aiWriteExecutionEnabled: readiness.writeExecutionEnabled,
    controls: readiness.controls,
  }
}

test('阶段 3 Dashboard 真实 HttpAiClient 桌面/移动视觉证据', async ({ browser }, testInfo) => {
  mkdirSync(liveDirectory, { recursive: true })
  const screenshots: Array<Record<string, unknown>> = []
  const businessWrites: string[] = []
  const runtimeObservations: Array<Awaited<ReturnType<typeof observeBackendRuntime>>> = []

  for (const viewport of viewports) {
    const context = await browser.newContext({
      baseURL: visualBaseURL,
      viewport: { width: viewport.width, height: viewport.height },
      deviceScaleFactor: 1,
      reducedMotion: 'reduce',
    })
    const page = await context.newPage()
    page.on('request', (request) => {
      const method = request.method()
      const path = new URL(request.url()).pathname
      if (!['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) return
      if (path === '/api/auth/login' || path === '/api/ai/dashboard/queries') return
      businessWrites.push(`${viewport.name} ${method} ${path}`)
    })
    try {
      await loginThroughUi(page)
      runtimeObservations.push(await observeBackendRuntime(page, viewport.name))
      await ensureDashboardReady(page)
      const command = page.getByRole('search', { name: '自然语言查询' })
      await command.getByRole('textbox', { name: '自然语言查询' }).fill('本周待维修工单有多少？')
      await command.getByRole('button', { name: /查询/ }).click()
      await expect(page.locator('[data-dashboard-section="brief"]')).toHaveAttribute('aria-busy', 'false', { timeout: 60_000 })
      await expect(command.getByRole('button', { name: /查询/ })).toBeEnabled()
      await ensureDashboardReady(page)
      const geometry = await page.evaluate(() => {
        const documentTop = (selector: string) => {
          const bounds = document.querySelector<HTMLElement>(selector)?.getBoundingClientRect()
          return bounds ? bounds.top + window.scrollY : Number.NaN
        }
        return {
          viewportWidth: document.documentElement.clientWidth,
          documentWidth: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth),
          documentHeight: Math.max(document.documentElement.scrollHeight, document.body.scrollHeight),
          pendingTop: documentTop('.pending-panel__heading'),
          sparseStateVisible: Boolean(document.querySelector<HTMLElement>('.trend-sparse-state')?.offsetParent),
          degradedStateVisible: Boolean(document.querySelector<HTMLElement>('.ai-brief__warning')?.offsetParent),
          sections: ['.dashboard-command', '.stats-grid', '.metric-grid', '.ai-brief', '.ai-brief__warning', '.risk-panel', '.trend-panel', '.trend-meta', '.trend-sparse-state'].map((selector) => {
            const element = document.querySelector<HTMLElement>(selector)
            const bounds = element?.getBoundingClientRect()
            return { selector, top: bounds?.top, height: bounds?.height, text: element?.innerText }
          }),
        }
      })
      // 失败也保留同次几何与截图，便于定位真实内容态，而非仅剩一个越界数字。
      writeFileSync(resolve(liveDirectory, `${viewport.name}-geometry.json`), JSON.stringify(geometry, null, 2))
      await page.screenshot({ path: resolve(liveDirectory, `${viewport.name}-diagnostic.png`), animations: 'disabled', caret: 'hide' })
      expect(geometry.documentWidth).toBeLessThanOrEqual(geometry.viewportWidth + 1)
      if (viewport.name === '390x844') {
        expect(geometry.pendingTop, 'Dashboard 待办标题未在 824px 内进入连续信息流')
          .toBeLessThanOrEqual(824)
        expect(geometry.documentHeight, 'Dashboard 移动文档高度超过 1320px')
          .toBeLessThanOrEqual(1320)
        expect(geometry.sparseStateVisible, 'Dashboard 稀疏说明被隐藏').toBe(true)
        expect(geometry.degradedStateVisible, 'Dashboard 降级说明被隐藏').toBe(true)
      }

      const filePath = resolve(liveDirectory, viewport.file)
      await page.screenshot({ path: filePath, animations: 'disabled', caret: 'hide' })
      const bytes = readFileSync(filePath)
      const dimensions = pngDimensions(filePath)
      screenshots.push({
        file: viewport.file,
        viewport: viewport.name,
        width: dimensions.width,
        height: dimensions.height,
        bytes: statSync(filePath).size,
        sha256: createHash('sha256').update(bytes).digest('hex').toUpperCase(),
        geometry,
      })
    } finally {
      await page.close()
      await context.close()
    }
  }

  const requestedRuntimeContract = testInfo.config.metadata.aiLiveRequestedRuntimeContract
  expect(requestedRuntimeContract).toMatchObject({
    clientMode: 'HttpAiClient',
    provider: 'fake',
    aiWriteExecutionEnabled: false,
    database: aiLiveIsolation.connection.database,
  })
  expect(runtimeObservations).toHaveLength(viewports.length)
  for (const observation of runtimeObservations) {
    expect(observation).toMatchObject({
      provider: requestedRuntimeContract.provider,
      aiWriteExecutionEnabled: requestedRuntimeContract.aiWriteExecutionEnabled,
    })
  }
  const firstObservation = runtimeObservations[0]
  const runtimeContract = {
    clientMode: requestedRuntimeContract.clientMode,
    provider: firstObservation.provider,
    aiWriteExecutionEnabled: firstObservation.aiWriteExecutionEnabled,
    masterEnabled: firstObservation.masterEnabled,
    streamingEnabled: firstObservation.streamingEnabled,
    evidenceSource: firstObservation.source,
    database: requestedRuntimeContract.database,
    databaseHost: requestedRuntimeContract.databaseHost,
    redisDatabase: requestedRuntimeContract.redisDatabase,
    redisKeyPrefix: requestedRuntimeContract.redisKeyPrefix,
    frontendOrigin: requestedRuntimeContract.frontendOrigin,
    backendOrigin: requestedRuntimeContract.backendOrigin,
  }

  writeFileSync(resolve(liveDirectory, 'manifest.json'), `${JSON.stringify({
    generatedAt: new Date().toISOString(),
    runtimeContract,
    requestedRuntimeContract,
    runtimeObservations,
    evidenceBindings: evidenceBindings(),
    screenshots,
    businessWrites,
  }, null, 2)}\n`, 'utf8')

  expect(screenshots).toHaveLength(2)
  expect(businessWrites, 'Dashboard 真实视觉链路不得触发业务写').toEqual([])
  for (const screenshot of screenshots) {
    expect(screenshot.bytes).toBeGreaterThan(1_024)
  }
})
