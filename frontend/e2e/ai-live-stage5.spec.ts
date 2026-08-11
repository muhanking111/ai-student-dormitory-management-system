import { mkdirSync, statSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import {
  requireVisualCredentials,
  sanitizeDiagnostic,
  visualBaseURL,
  visualDirectory,
} from './live-visual-settings'
import {
  isVisualLoginServiceNotReady,
  retryVisualLogin,
  type VisualLoginAttemptResult,
} from './live-visual-login-retry'

const stage5Directory = resolve(visualDirectory, 'stage5')
const stage5ManifestPath = resolve(stage5Directory, 'manifest.json')

interface RuntimeIssue {
  kind: string
  route: string
  detail: string
}

interface LayoutCheck {
  route: string
  viewport: string
  viewportWidth: number
  documentWidth: number
  overflow: number
  offenders: string[]
}

interface TouchCheck {
  route: string
  viewport: string
  selector: string
  visibleCount: number
  minimumWidth: number | null
  minimumHeight: number | null
  failures: Array<{ tag: string; label: string; width: number; height: number }>
}

const touchRoutes = [
  {
    path: '/',
    selector: '.dashboard-command .ai-command-bar__input, .dashboard-command .ai-command-bar__button, [data-ai-assistant-trigger]',
  },
  {
    path: '/repairs',
    selector: '.repair-toolbar button, .operations-filters > *, .repair-mobile-list button, .repair-mobile-actions button',
  },
  {
    path: '/notices/create',
    selector: '.notice-primary-button, .notice-disabled-button, .notice-submit-actions button',
  },
  {
    path: '/ai/knowledge',
    selector: '.ai-knowledge-source, .ai-knowledge-ingest .ant-input, .ai-knowledge-ingest textarea, .ai-knowledge-ingest button, .ai-knowledge-create .ant-input, .ai-knowledge-create button, .ai-knowledge-version button',
  },
  {
    path: '/ai/risks',
    selector: '.risk-filters input, .risk-filters select, .risk-filters button, .icon-button, .page-controls button, .action-buttons button',
  },
  {
    path: '/ai/approvals',
    selector: '.governance-tabs a, .approval-actions button, .ai-reconfirm button',
  },
  {
    path: '/ai/audit',
    selector: '.governance-tabs a, .audit-filter-bar input, .audit-filter-bar select, .audit-filter-bar button, .run-pagination button, .refresh-button, .metrics-heading button, .audit-content-actions button',
  },
] as const

async function login(context: BrowserContext) {
  const credentials = requireVisualCredentials()
  await retryVisualLogin(async ({ remainingMs }): Promise<VisualLoginAttemptResult> => {
    try {
      const response = await context.request.post(`${visualBaseURL}/api/auth/login`, {
        data: credentials,
        timeout: Math.max(1, Math.min(3_000, remainingMs)),
      })
      return {
        status: response.status(),
        detail: response.ok() ? '' : await response.text().catch(() => 'unreadable response'),
      }
    } catch (error) {
      if (isVisualLoginServiceNotReady(error)) return { status: 'service-not-ready', detail: error }
      throw error
    }
  }, {
    timeoutMs: 180_000,
    intervalMs: 250,
    sanitize: sanitizeDiagnostic,
  })

  const state = await context.request.storageState()
  if (!state.cookies.some((cookie) => cookie.name === 'Authorization')) {
    throw new Error('Stage 5 真实登录成功但未返回 Authorization Cookie')
  }
  await context.addCookies(state.cookies)
}

async function prepareGroundedKnowledge(page: Page, marker: string) {
  await page.goto('/ai/knowledge')
  await expect(page.getByRole('heading', { name: '知识管理', exact: true })).toBeVisible()

  const create = page.getByRole('region', { name: '创建知识来源' })
  await create.getByRole('textbox', { name: '来源名称' }).fill(`全量视觉知识源 ${marker}`)
  await create.getByRole('textbox', { name: '知识来源权限' }).fill('ai:knowledge:read')
  const sourceResponse = page.waitForResponse((response) => (
    new URL(response.url()).pathname === '/api/ai/knowledge/sources'
      && response.request().method() === 'POST'
  ))
  await create.getByRole('button', { name: '创建来源' }).click()
  expect((await sourceResponse).status()).toBe(201)

  const ingest = page.locator('.ai-knowledge-ingest')
  await ingest.getByRole('textbox', { name: '文档标题' }).fill(`维修时限 ${marker}`)
  await ingest.getByRole('textbox', { name: '外部键' }).fill(`stage5-${marker.toLowerCase()}`)
  await ingest.getByRole('textbox', { name: '版本' }).fill('v1')
  await ingest.getByRole('textbox', { name: '纯文本正文' }).fill(
    `知识标识 ${marker}：宿舍普通维修应在受理后 2 个工作日内完成，特殊情况最长不超过 5 个工作日。`,
  )
  const uploadResponse = page.waitForResponse((response) => (
    /^\/api\/ai\/knowledge\/uploads\/[^/]+\/content$/.test(new URL(response.url()).pathname)
      && response.request().method() === 'PUT'
  ))
  await ingest.getByRole('button', { name: '创建版本并摄取' }).click()
  expect((await uploadResponse).status()).toBe(204)

  const version = page.getByRole('region', { name: '知识版本与摄取任务' })
  await expect(version).toBeVisible({ timeout: 60_000 })
  await expect.poll(async () => {
    await version.getByRole('button', { name: '刷新任务' }).click()
    return version.textContent()
  }, { timeout: 60_000 }).toMatch(/SUCCEEDED[\s\S]*READY|READY[\s\S]*SUCCEEDED/)
  const activateResponse = page.waitForResponse((response) => (
    /^\/api\/ai\/knowledge\/versions\/[^/]+\/activate$/.test(new URL(response.url()).pathname)
      && response.request().method() === 'POST'
  ))
  await version.getByRole('button', { name: '激活版本' }).click()
  expect((await activateResponse).status()).toBe(204)
  await expect(version).toContainText('ACTIVE')
}

function observeRuntime(page: Page, runtimeIssues: RuntimeIssue[], toleratedIssues: RuntimeIssue[]) {
  const successfulApiResponses = new WeakSet()
  const successfulApiKeys = new Set<string>()
  page.on('console', (message) => {
    if (message.type() === 'error') {
      runtimeIssues.push({ kind: 'console', route: page.url(), detail: sanitizeDiagnostic(message.text()) })
    }
  })
  page.on('pageerror', (error) => {
    runtimeIssues.push({ kind: 'page', route: page.url(), detail: sanitizeDiagnostic(error) })
  })
  page.on('response', async (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/') && response.status() >= 200 && response.status() < 400) {
      successfulApiResponses.add(response.request())
      successfulApiKeys.add(`${response.request().method()} ${url.pathname}`)
    }
    if (url.pathname.startsWith('/api/') && response.status() >= 400) {
      runtimeIssues.push({
        kind: `api-${response.status()}`,
        route: page.url(),
        detail: `${response.request().method()} ${url.pathname}: ${sanitizeDiagnostic(await response.text().catch(() => 'unreadable response'))}`,
      })
    }
  })
  page.on('requestfailed', (request) => {
    const url = new URL(request.url())
    if (!url.pathname.startsWith('/api/')) return
    const issue = {
      kind: 'request',
      route: page.url(),
      detail: `${request.method()} ${url.pathname}: ${sanitizeDiagnostic(request.failure()?.errorText ?? 'unknown failure')}`,
    }
    const completedConnectionClose = issue.detail.includes('ERR_ABORTED')
      && successfulApiResponses.has(request)
    const repeatedSuccessfulPathClose = issue.detail.includes('ERR_ABORTED')
      && successfulApiKeys.has(`${request.method()} ${url.pathname}`)
    if (completedConnectionClose
      || repeatedSuccessfulPathClose
      || (issue.detail.includes('ERR_ABORTED') && issue.detail.includes('/events'))) toleratedIssues.push(issue)
    else runtimeIssues.push(issue)
  })
}

async function gotoApp(page: Page, path: string) {
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await page.locator('.admin-content').waitFor({ state: 'visible' })
  await page.waitForTimeout(300)
}

async function inspectLayout(page: Page, route: string, viewport: string): Promise<LayoutCheck> {
  const result = await page.evaluate(() => {
    const viewportWidth = window.innerWidth
    const documentWidth = Math.max(
      document.documentElement.scrollWidth,
      document.body?.scrollWidth ?? 0,
    )
    const offenders = Array.from(document.querySelectorAll<HTMLElement>('body *'))
      .flatMap((element) => {
        const rect = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        if (style.display === 'none' || style.visibility === 'hidden' || rect.width < 1 || rect.height < 1) return []
        if (rect.left >= -1 && rect.right <= viewportWidth + 1) return []
        return [`${element.tagName.toLowerCase()}.${element.className.toString().split(/\s+/).filter(Boolean).slice(0, 2).join('.')}:${Math.round(rect.left)}..${Math.round(rect.right)}`]
      })
      .slice(0, 12)
    return { viewportWidth, documentWidth, offenders }
  })
  return {
    route,
    viewport,
    ...result,
    overflow: Math.max(0, result.documentWidth - result.viewportWidth),
  }
}

async function inspectTouchTargets(
  page: Page,
  route: string,
  viewport: string,
  selector: string,
): Promise<TouchCheck> {
  const targets = page.locator(selector)
  const measurements: Array<{ tag: string; label: string; width: number; height: number }> = []
  for (let index = 0; index < await targets.count(); index += 1) {
    const target = targets.nth(index)
    if (!await target.isVisible()) continue
    const box = await target.boundingBox()
    if (!box) continue
    measurements.push({
      tag: await target.evaluate((element) => element.tagName.toLowerCase()),
      label: sanitizeDiagnostic((await target.getAttribute('aria-label')) ?? await target.textContent() ?? '').slice(0, 80),
      width: Math.round(box.width * 100) / 100,
      height: Math.round(box.height * 100) / 100,
    })
  }
  return {
    route,
    viewport,
    selector,
    visibleCount: measurements.length,
    minimumWidth: measurements.length ? Math.min(...measurements.map(({ width }) => width)) : null,
    minimumHeight: measurements.length ? Math.min(...measurements.map(({ height }) => height)) : null,
    failures: measurements.filter(({ width, height }) => width < 43.5 || height < 43.5),
  }
}

function appendLayoutViolations(check: LayoutCheck, violations: string[]) {
  if (check.overflow > 0) {
    violations.push(`${check.viewport} ${check.route} 横向溢出 ${check.overflow}px: ${check.offenders.join(', ')}`)
  }
}

function appendTouchViolations(check: TouchCheck, violations: string[]) {
  if (check.visibleCount === 0) violations.push(`${check.viewport} ${check.route} 未找到可见关键交互控件`)
  for (const failure of check.failures) {
    violations.push(`${check.viewport} ${check.route} ${failure.tag}「${failure.label}」仅 ${failure.width}x${failure.height}px`)
  }
}

test('阶段 5 移动端、键盘焦点与 200% 缩放真实服务验收', async ({ browser }) => {
  mkdirSync(stage5Directory, { recursive: true })
  const startedAt = Date.now()
  const runtimeIssues: RuntimeIssue[] = []
  const toleratedIssues: RuntimeIssue[] = []
  const layoutChecks: LayoutCheck[] = []
  const touchChecks: TouchCheck[] = []
  const violations: string[] = []
  const screenshots: Array<{ file: string; bytes: number; viewport: string; route: string }> = []
  const knowledgeMarker = `STAGE5-${Date.now().toString(36).toUpperCase()}`

  const mobileContext = await browser.newContext({
    baseURL: visualBaseURL,
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 1,
    reducedMotion: 'reduce',
  })
  try {
    await login(mobileContext)
    const page = await mobileContext.newPage()
    observeRuntime(page, runtimeIssues, toleratedIssues)

    await prepareGroundedKnowledge(page, knowledgeMarker)

    await gotoApp(page, '/')
    await expect(page.locator('[data-dashboard-metric]:visible')).toHaveCount(4)
    await expect(page.locator('.ai-brief')).toBeVisible({ timeout: 60_000 })
    await expect(page.locator('.risk-row:visible')).toHaveCount(4)

    const dashboardGeometry = await page.evaluate(() => {
      const boxes = (selector: string) => Array.from(document.querySelectorAll<HTMLElement>(selector))
        .filter((element) => element.offsetParent !== null)
        .map((element) => element.getBoundingClientRect())
      const command = document.querySelector<HTMLElement>('.dashboard-command .ai-command-bar')?.getBoundingClientRect()
      const metrics = boxes('[data-dashboard-metric]')
      const facts = boxes('[data-brief-meta]')
      const risks = boxes('.risk-row')
      return {
        commandHeight: command?.height ?? 0,
        metricHeights: metrics.map(({ height }) => height),
        factHeights: facts.map(({ height }) => height),
        riskHeights: risks.map(({ height }) => height),
        trendBottom: document.querySelector<HTMLElement>('.trend-panel')?.getBoundingClientRect().bottom ?? 0,
        riskBottom: Math.max(0, ...risks.map(({ bottom }) => bottom)),
        briefWarningVisible: document.querySelector<HTMLElement>('.ai-brief__warning')?.offsetParent !== null,
        viewportHeight: window.innerHeight,
      }
    })
    if (dashboardGeometry.commandHeight < 51.5 || dashboardGeometry.commandHeight > 52.5) {
      violations.push(`390x844 Dashboard 命令栏高度 ${dashboardGeometry.commandHeight}px，不在 Stage 3 的 52px 合同容差内`)
    }
    if (dashboardGeometry.metricHeights.some((height) => height < 69.5 || height > 76)) {
      violations.push(`390x844 Dashboard KPI 高度漂移: ${dashboardGeometry.metricHeights.join(', ')}`)
    }
    if (dashboardGeometry.factHeights.length !== 3 || dashboardGeometry.factHeights.some((height) => height < 63.5 || height > 76)) {
      violations.push(`390x844 Dashboard 简报事实区不完整: ${dashboardGeometry.factHeights.join(', ')}`)
    }
    if (dashboardGeometry.riskHeights.some((height) => height < 91.5 || height > 96)) {
      violations.push(`390x844 Dashboard 风险卡高度漂移: ${dashboardGeometry.riskHeights.join(', ')}`)
    }
    if (dashboardGeometry.riskBottom > dashboardGeometry.viewportHeight + 1) {
      violations.push(`390x844 Dashboard 四类风险未完整入首屏，底边 ${dashboardGeometry.riskBottom}px`)
    }
    const trendBottomAllowance = dashboardGeometry.briefWarningVisible ? 64 : 1
    if (dashboardGeometry.trendBottom > dashboardGeometry.viewportHeight + trendBottomAllowance) {
      violations.push(
        `390x844 Dashboard 趋势面板延展过长，底边 ${dashboardGeometry.trendBottom}px，`
        + `警示态容差 ${trendBottomAllowance}px`,
      )
    }

    const dashboardLayout = await inspectLayout(page, '/', '390x844')
    layoutChecks.push(dashboardLayout)
    appendLayoutViolations(dashboardLayout, violations)
    const dashboardFile = 'stage5-dashboard-mobile.png'
    const dashboardPath = resolve(stage5Directory, dashboardFile)
    await page.screenshot({ path: dashboardPath, animations: 'disabled', caret: 'hide' })
    screenshots.push({ file: dashboardFile, bytes: statSync(dashboardPath).size, viewport: '390x844', route: '/' })

    const trigger = page.locator('[data-ai-assistant-trigger]')
    await trigger.focus()
    await page.keyboard.press('Enter')
    const assistant = page.getByRole('dialog', { name: '智能助手' })
    await expect(assistant).toBeVisible()
    await expect.poll(() => assistant.evaluate((element) => element.contains(document.activeElement))).toBe(true)
    const assistantBox = await assistant.boundingBox()
    if (!assistantBox || assistantBox.x > 1 || assistantBox.y > 1
      || assistantBox.width < 389 || assistantBox.height < 843) {
      violations.push(`390x844 助手未形成全屏 Sheet: ${JSON.stringify(assistantBox)}`)
    }

    const historyButton = assistant.getByRole('button', { name: /会话历史/ })
    await historyButton.click()
    const historyDialog = page.getByRole('dialog', { name: '会话历史' })
    await expect(historyDialog).toBeVisible()
    await historyDialog.getByRole('button', { name: '新建会话' }).click()
    await expect(historyDialog).toBeHidden()
    await expect(assistant.locator('[data-assistant-user-message]')).toHaveCount(0)
    const assistantInput = assistant.getByRole('textbox', { name: '向智能助手提问' })
    await expect(assistantInput).toBeFocused()
    const groundedQuestion = `请检索知识标识 ${knowledgeMarker} 并说明维修处理时限`
    await assistantInput.fill(groundedQuestion)
    await assistant.getByRole('button', { name: '发送问题' }).click()
    const userMessage = assistant.locator('[data-assistant-user-message]').last()
    const answer = assistant.locator('[data-assistant-answer]').last()
    await expect(userMessage).toContainText(groundedQuestion)
    await expect(answer).toContainText(knowledgeMarker, { timeout: 60_000 })
    await expect(answer).toContainText(/工作日|维修/)
    await expect(answer.locator('[data-answer-meta]')).toHaveCount(3)
    await expect(answer.locator('[aria-label="回答操作"] button')).toHaveCount(4)
    const citationToggle = answer.getByRole('button', { name: /引用来源/ })
    await expect(citationToggle).toBeVisible()
    if (await citationToggle.getAttribute('aria-expanded') === 'false') await citationToggle.click()
    const citationList = answer.getByRole('region', { name: '引用来源列表' })
    await expect(citationList).toBeVisible()
    const citationItems = citationList.getByRole('listitem')
    expect(await citationItems.count(), 'Stage 5 助手至少需要 1 个真实引用').toBeGreaterThanOrEqual(1)
    expect((await citationItems.allTextContents()).some((text) => text.includes(knowledgeMarker)),
      'Stage 5 助手引用必须绑定本轮唯一知识来源').toBe(true)

    const assistantText = await assistant.textContent() ?? ''
    expect(assistantText, '助手不得显示 command conversation 标题或原始 Dashboard payload')
      .not.toMatch(/COMMAND:|intentSchemaVersion|DashboardQueryIntent|\{\s*"question"\s*:/i)
    const assistantGeometry = await assistant.evaluate((element) => {
      const root = element.getBoundingClientRect()
      const messages = element.querySelector<HTMLElement>('.assistant-messages')
      const selectors = '[data-assistant-user-message] p, .answer-card, .citation-card, .answer-actions'
      const boxes = Array.from(element.querySelectorAll<HTMLElement>(selectors)).map((item) => {
        const box = item.getBoundingClientRect()
        return { selector: item.className, left: box.left, right: box.right, width: box.width }
      })
      return {
        rootLeft: root.left,
        rootRight: root.right,
        messagesClientWidth: messages?.clientWidth ?? 0,
        messagesScrollWidth: messages?.scrollWidth ?? 0,
        boxes,
      }
    })
    if (assistantGeometry.messagesScrollWidth > assistantGeometry.messagesClientWidth + 1) {
      violations.push(`390x844 助手消息区内部横向溢出 ${assistantGeometry.messagesScrollWidth - assistantGeometry.messagesClientWidth}px`)
    }
    for (const box of assistantGeometry.boxes) {
      if (box.left < assistantGeometry.rootLeft - 1 || box.right > assistantGeometry.rootRight + 1) {
        violations.push(`390x844 助手内容被裁切: ${JSON.stringify(box)}`)
      }
    }
    const mobileHistoryLabel = assistant.getByRole('button', { name: /会话历史/ }).locator('span:not(.anticon)')
    await expect(mobileHistoryLabel).toBeVisible()
    await expect(mobileHistoryLabel).toHaveText('历史会话')
    const assistantTouch = await inspectTouchTargets(page, 'assistant', '390x844', '.ai-assistant button, .ai-assistant textarea')
    touchChecks.push(assistantTouch)
    appendTouchViolations(assistantTouch, violations)
    const assistantFile = 'stage5-assistant-mobile.png'
    const assistantPath = resolve(stage5Directory, assistantFile)
    await page.screenshot({ path: assistantPath, animations: 'disabled', caret: 'hide' })
    screenshots.push({ file: assistantFile, bytes: statSync(assistantPath).size, viewport: '390x844', route: '/' })

    await historyButton.focus()
    await page.keyboard.press('Enter')
    await expect(historyDialog).toBeVisible()
    await expect.poll(() => historyDialog.evaluate((element) => element.contains(document.activeElement))).toBe(true)
    await page.keyboard.press('Escape')
    await expect(historyDialog).toBeHidden()
    await expect(historyButton).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(assistant).toBeHidden()
    await expect(trigger).toBeFocused()

    for (const route of touchRoutes) {
      await gotoApp(page, route.path)
      const layout = await inspectLayout(page, route.path, '390x844')
      const touch = await inspectTouchTargets(page, route.path, '390x844', route.selector)
      layoutChecks.push(layout)
      touchChecks.push(touch)
      appendLayoutViolations(layout, violations)
      appendTouchViolations(touch, violations)
    }

    await gotoApp(page, '/repairs')
    const repairOpener = page.getByRole('button', { name: /新增报修/ }).first()
    await repairOpener.focus()
    await page.keyboard.press('Enter')
    const repairDialog = page.getByRole('dialog', { name: '新增报修' })
    await expect(repairDialog).toBeVisible()
    await repairDialog.getByLabel('报修人').focus()
    await page.keyboard.press('Escape')
    await expect(repairDialog).toBeHidden()
    if (!await repairOpener.evaluate((element) => element === document.activeElement)) {
      violations.push('维修新增弹窗关闭后未将焦点恢复到真实触发按钮')
    }

    await gotoApp(page, '/ai/approvals')
    const auditTab = page.locator('.governance-tabs a[href="/ai/audit"]')
    await auditTab.focus()
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/\/ai\/audit$/)
    await page.close()
  } finally {
    await mobileContext.close()
  }

  const zoomContext = await browser.newContext({
    baseURL: visualBaseURL,
    // 640 CSS px at DPR 2 produces a 1280 px surface, equivalent to 200% browser zoom.
    viewport: { width: 640, height: 450 },
    deviceScaleFactor: 2,
    reducedMotion: 'reduce',
  })
  try {
    await login(zoomContext)
    const page = await zoomContext.newPage()
    observeRuntime(page, runtimeIssues, toleratedIssues)
    for (const route of touchRoutes) {
      await gotoApp(page, route.path)
      const layout = await inspectLayout(page, route.path, '1280x900@200%')
      const touch = await inspectTouchTargets(page, route.path, '1280x900@200%', route.selector)
      layoutChecks.push(layout)
      touchChecks.push(touch)
      appendLayoutViolations(layout, violations)
      appendTouchViolations(touch, violations)
    }
    const zoomFile = 'stage5-audit-zoom-200.png'
    const zoomPath = resolve(stage5Directory, zoomFile)
    await page.screenshot({ path: zoomPath, animations: 'disabled', caret: 'hide' })
    screenshots.push({ file: zoomFile, bytes: statSync(zoomPath).size, viewport: '1280x900@200%', route: '/ai/audit' })
    await page.close()
  } finally {
    await zoomContext.close()
  }

  await new Promise((resolveDelay) => setTimeout(resolveDelay, 250))
  for (const screenshot of screenshots) {
    if (screenshot.bytes <= 1_024) violations.push(`${screenshot.file} 是空截图`)
  }
  for (const issue of runtimeIssues) violations.push(`${issue.kind} ${issue.route}: ${issue.detail}`)

  const manifest = {
    generatedAt: new Date().toISOString(),
    startedAt: new Date(startedAt).toISOString(),
    status: violations.length ? 'failed' : 'passed',
    zoomContract: { physicalViewport: '1280x900', cssViewport: '640x450', deviceScaleFactor: 2 },
    screenshots,
    layoutChecks,
    touchChecks,
    runtimeIssues,
    toleratedIssues,
    violations,
  }
  writeFileSync(stage5ManifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')

  expect(violations, `Stage 5 验收失败，详见 ${stage5ManifestPath}`).toEqual([])
})
