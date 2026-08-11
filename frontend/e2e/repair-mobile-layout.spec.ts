import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test } from '@playwright/test'
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

const viewport = { width: 390, height: 844 }
const selectors = [
  'html',
  'body',
  '.admin-main',
  '.admin-content',
  '.repair-page',
  '.repair-toolbar',
  '.repair-toolbar .operations-filters',
  '.repair-toolbar .ant-input-affix-wrapper',
  '.repair-workspace-grid',
  '.repair-list-panel',
  '.repair-mobile-list',
  '.repair-mobile-list button',
  '.repair-mobile-list button strong',
  '.repair-mobile-list button > span',
  '.repair-detail-stack',
  '.repair-detail-panel',
  '.repair-ai-panel',
  '.repair-ai-empty',
  '.repair-flow-panel',
  '.repair-safety-panel',
] as const

test('390x844 维修工作台父链与内容均不得撑宽视口', async ({ browser }) => {
  const credentials = requireVisualCredentials()
  const context = await browser.newContext({
    baseURL: visualBaseURL,
    viewport,
    deviceScaleFactor: 1,
    reducedMotion: 'reduce',
  })

  try {
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
        if (isVisualLoginServiceNotReady(error)) {
          return { status: 'service-not-ready', detail: error }
        }
        throw error
      }
    }, {
      timeoutMs: 180_000,
      intervalMs: 250,
      sanitize: sanitizeDiagnostic,
    })

    const loginState = await context.request.storageState()
    expect(loginState.cookies.some((cookie) => cookie.name === 'Authorization')).toBe(true)
    await context.addCookies(loginState.cookies)

    const page = await context.newPage()
    await page.goto('/repairs')
    await expect(page.getByRole('region', { name: '报修列表' })).toBeVisible()
    const firstOrder = page.getByLabel('移动端报修列表', { exact: true }).getByRole('button').first()
    await expect(firstOrder).toBeVisible()
    await firstOrder.click()

    const diagnostic = await page.evaluate((targets) => {
      const inspect = (element: HTMLElement) => {
        const bounds = element.getBoundingClientRect()
        const style = getComputedStyle(element)
        return {
          tag: element.tagName.toLowerCase(),
          className: element.className,
          text: (element.textContent ?? '').replace(/\s+/g, ' ').trim().slice(0, 80),
          bounds: {
            left: bounds.left,
            x: bounds.x,
            right: bounds.right,
            width: bounds.width,
          },
          clientWidth: element.clientWidth,
          scrollWidth: element.scrollWidth,
          offsetWidth: element.offsetWidth,
          css: {
            display: style.display,
            width: style.width,
            minWidth: style.minWidth,
            maxWidth: style.maxWidth,
            gridTemplateColumns: style.gridTemplateColumns,
            flex: style.flex,
            overflowX: style.overflowX,
            whiteSpace: style.whiteSpace,
            overflowWrap: style.overflowWrap,
            wordBreak: style.wordBreak,
          },
        }
      }

      const nodes = targets.map((selector) => {
        const element = document.querySelector<HTMLElement>(selector)
        return { selector, element: element ? inspect(element) : null }
      })
      const overflowElements = Array.from(document.querySelectorAll<HTMLElement>('body *'))
        .map((element) => inspect(element))
        .filter((entry) => entry.bounds.left < -1 || entry.bounds.right > document.documentElement.clientWidth + 1)
        .sort((left, right) => right.bounds.right - left.bounds.right)
        .slice(0, 30)

      return {
        viewportWidth: document.documentElement.clientWidth,
        documentWidth: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth),
        nodes,
        overflowElements,
      }
    }, selectors)

    mkdirSync(visualDirectory, { recursive: true })
    writeFileSync(
      resolve(visualDirectory, 'repair-mobile-layout-diagnostic.json'),
      `${JSON.stringify(diagnostic, null, 2)}\n`,
      'utf8',
    )
    await page.screenshot({
      path: resolve(visualDirectory, 'repair-mobile-layout-diagnostic.png'),
      fullPage: true,
      animations: 'disabled',
      caret: 'hide',
    })

    expect(diagnostic.documentWidth, '维修工作台存在全页横向溢出').toBeLessThanOrEqual(diagnostic.viewportWidth)
    for (const node of diagnostic.nodes) {
      if (!node.element) continue
      expect(node.element.bounds.right, `${node.selector} 右边界超出视口`).toBeLessThanOrEqual(diagnostic.viewportWidth + 1)
    }
  } finally {
    await context.close()
  }
})
