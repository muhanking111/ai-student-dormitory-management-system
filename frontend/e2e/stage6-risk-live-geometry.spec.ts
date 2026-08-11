import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type BrowserContext } from '@playwright/test'
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

const viewport = { name: '1536x1024', width: 1536, height: 1024 } as const

async function login(context: BrowserContext) {
  const credentials = requireVisualCredentials()
  await retryVisualLogin(async ({ remainingMs }): Promise<VisualLoginAttemptResult> => {
    try {
      const response = await context.request.post(`${visualBaseURL}/api/auth/login`, {
        data: { username: credentials.username, password: credentials.password },
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
}

test('Stage 6 风险真实数据的详情与人工处置动作完整进入 1536x1024 首屏', async ({ browser }) => {
  mkdirSync(visualDirectory, { recursive: true })
  const context = await browser.newContext({
    baseURL: visualBaseURL,
    viewport: { width: viewport.width, height: viewport.height },
    deviceScaleFactor: 1,
    reducedMotion: 'reduce',
  })

  try {
    await login(context)
    const page = await context.newPage()
    await page.goto('/ai/risks')
    await page.waitForLoadState('networkidle')
    const rows = page.locator('.risk-table tbody tr').filter({ has: page.locator('td') })
    await expect(rows).not.toHaveCount(0)
    await rows.first().click()

    const detail = page.locator('[aria-label="风险案例详情"]')
    const actionDock = detail.locator('[data-testid="risk-action-dock"]')
    await expect(detail).toBeVisible()
    await expect(actionDock).toBeVisible()

    const geometry = await page.evaluate(() => {
      const selectors = {
        riskCenter: '.risk-center',
        summaryGrid: '.risk-summary-grid',
        analytics: '.risk-analytics',
        workbench: '.risk-workbench',
        detailPanel: '.risk-detail-panel',
        detailHeading: '.detail-heading',
        metrics: '.detail-metrics',
        policy: '.risk-policy-note',
        detailScroll: '[data-testid="risk-detail-scroll-region"]',
        detailCore: '.risk-detail-core',
        evidence: '.risk-detail-core__evidence',
        human: '.risk-detail-core__human',
        timeline: '.human-timeline',
        safety: '[data-testid="risk-detail-safety"]',
        actions: '[data-testid="risk-action-dock"]',
      } as const
      const box = (selector: string) => {
        const element = document.querySelector<HTMLElement>(selector)
        if (!element) return null
        const bounds = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        return {
          top: bounds.top,
          right: bounds.right,
          bottom: bounds.bottom,
          left: bounds.left,
          width: bounds.width,
          height: bounds.height,
          scrollHeight: element.scrollHeight,
          clientHeight: element.clientHeight,
          overflowY: style.overflowY,
          gap: style.gap,
          marginTop: style.marginTop,
          marginBottom: style.marginBottom,
          paddingTop: style.paddingTop,
          paddingBottom: style.paddingBottom,
          gridTemplateRows: style.gridTemplateRows,
          gridTemplateColumns: style.gridTemplateColumns,
        }
      }
      return {
        viewport: { width: window.innerWidth, height: window.innerHeight },
        document: {
          width: document.documentElement.scrollWidth,
          height: document.documentElement.scrollHeight,
          scrollY: window.scrollY,
        },
        boxes: Object.fromEntries(Object.entries(selectors).map(([name, selector]) => [name, box(selector)])),
        timelineItems: document.querySelectorAll('.human-timeline > li').length,
        detailScrollTop: document.querySelector<HTMLElement>('[data-testid="risk-detail-scroll-region"]')?.scrollTop ?? -1,
        detailMetrics: Array.from(document.querySelectorAll<HTMLElement>('.detail-metrics dd')).map((element) => ({
          text: element.textContent?.trim() ?? '',
          scrollWidth: element.scrollWidth,
          clientWidth: element.clientWidth,
        })),
        actionTargetHeights: Array.from(document.querySelectorAll<HTMLElement>('[data-testid="risk-action-dock"] button'))
          .map((element) => element.getBoundingClientRect().height),
      }
    })

    writeFileSync(
      resolve(visualDirectory, 'risk-geometry-1536x1024.json'),
      `${JSON.stringify(geometry, null, 2)}\n`,
      'utf8',
    )
    await page.screenshot({
      path: resolve(visualDirectory, 'risk-geometry-1536x1024.png'),
      fullPage: true,
      animations: 'disabled',
      caret: 'hide',
    })

    expect(geometry.detailScrollTop, '风险详情截图前不得预先滚动').toBe(0)
    expect(geometry.actionTargetHeights.every((height) => height >= 44), '风险动作目标不得低于 44px').toBe(true)
    expect(
      geometry.detailMetrics.filter((metric) => metric.scrollWidth > metric.clientWidth + 1),
      '风险详情指标不得横向裁切',
    ).toEqual([])
    expect(geometry.boxes.actions?.bottom, '风险人工处置动作缺少可测量布局').not.toBeNull()
    expect(geometry.boxes.actions!.bottom, '风险人工处置动作未进入 1536x1024 原生首屏')
      .toBeLessThanOrEqual(viewport.height)
  } finally {
    await context.close()
  }
})
