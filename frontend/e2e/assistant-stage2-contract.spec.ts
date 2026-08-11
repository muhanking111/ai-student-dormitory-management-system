import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type Browser, type Locator, type Page } from '@playwright/test'
import { mockApi } from './mock-api'

const baseURL = 'http://127.0.0.1:5174'
const outputName = (process.env.VISUAL_OUTPUT_NAME?.trim() || 'stage2-assistant-20260728-a')
  .replace(/[^A-Za-z0-9._-]/g, '_')
const outputDirectory = resolve(process.cwd(), 'test-results', outputName, 'fixtures')
const capturesDirectory = resolve(outputDirectory, 'captures')
const records: Array<Record<string, unknown>> = []
const computedEvidence: Record<string, unknown> = {}

const viewports = [
  { name: '1920x1080', width: 1920, height: 1080 },
  { name: '1366x768', width: 1366, height: 768 },
  { name: '1586x992', width: 1586, height: 992 },
  { name: '1536x1024', width: 1536, height: 1024 },
  { name: '1505x1045', width: 1505, height: 1045 },
  { name: '390x844', width: 390, height: 844 },
] as const

type DemoScenario = 'success' | 'streaming' | 'low-confidence' | 'no-source'
  | 'revoked' | 'degraded' | 'failed' | 'timed-out'

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
      text: element.textContent?.trim().slice(0, 120) ?? '',
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
      flexDirection: style.flexDirection,
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
  await mockApi(page, true)
  return { context, page }
}

async function openAssistant(page: Page, scenario: DemoScenario, send = true) {
  await page.goto(`/?assistantFixture=${scenario}`)
  await expect(page.getByRole('heading', { name: '首页', exact: true })).toBeVisible()
  await page.locator('[data-ai-assistant-trigger]').first().click()
  const assistant = page.getByRole('dialog', { name: '智能助手' })
  await expect(assistant).toBeVisible()
  await expect(assistant).toBeFocused()
  if (send) {
    await assistant.getByRole('textbox', { name: '向智能助手提问' }).fill('维修申请的处理时限是什么？')
    await assistant.getByRole('button', { name: '发送问题' }).click()
  }
  return assistant
}

async function expectNoOverflow(page: Page, assistant: Locator, label: string) {
  const geometry = await page.evaluate(() => {
    const root = document.documentElement
    const body = document.body
    const drawer = document.querySelector<HTMLElement>('.ai-assistant')
    const messages = document.querySelector<HTMLElement>('.assistant-messages')
    return {
      viewportWidth: root.clientWidth,
      documentWidth: Math.max(root.scrollWidth, body.scrollWidth),
      drawerClientWidth: drawer?.clientWidth ?? 0,
      drawerScrollWidth: drawer?.scrollWidth ?? 0,
      messagesClientWidth: messages?.clientWidth ?? 0,
      messagesScrollWidth: messages?.scrollWidth ?? 0,
    }
  })
  expect(geometry.documentWidth, `${label} 页面横向溢出`).toBeLessThanOrEqual(geometry.viewportWidth + 1)
  expect(geometry.drawerScrollWidth, `${label} Assistant 横向溢出`).toBeLessThanOrEqual(geometry.drawerClientWidth + 1)
  expect(geometry.messagesScrollWidth, `${label} 消息区横向溢出`).toBeLessThanOrEqual(geometry.messagesClientWidth + 1)
  await expect(assistant).toBeVisible()
  return geometry
}

async function recordScreenshot(page: Page, file: string, meta: Record<string, unknown>) {
  const path = resolve(capturesDirectory, file)
  await page.screenshot({ path, animations: 'disabled', caret: 'hide' })
  const bytes = readFileSync(path)
  records.push({
    file: `captures/${file}`,
    bytes: statSync(path).size,
    sha256: createHash('sha256').update(bytes).digest('hex').toUpperCase(),
    ...meta,
  })
}

async function waitForScenario(assistant: Locator, scenario: Exclude<DemoScenario, 'streaming'>) {
  const answer = assistant.locator('[data-assistant-answer]').last()
  if (scenario === 'revoked') await expect(answer.locator('[data-assistant-state="citation-denied"]')).toBeVisible()
  else {
    const expected = {
      success: 'succeeded',
      'low-confidence': 'low-confidence',
      'no-source': 'no-grounded',
      degraded: 'degraded',
      failed: 'failed',
      'timed-out': 'timed-out',
    }[scenario]
    await expect(answer).toHaveAttribute('data-assistant-content-state', expected)
  }
  return answer
}

test.beforeAll(() => mkdirSync(capturesDirectory, { recursive: true }))

test.afterAll(() => {
  writeFileSync(resolve(outputDirectory, 'computed-styles.json'), `${JSON.stringify(computedEvidence, null, 2)}\n`)
  writeFileSync(resolve(outputDirectory, 'manifest.json'), `${JSON.stringify({
    generatedAt: new Date().toISOString(),
    fixtureMode: 'DemoAiClient conversation/run/event contract; VITE_VISUAL_EVIDENCE_ENABLED only',
    viewports: viewports.map(({ name }) => name),
    screenshots: records,
  }, null, 2)}\n`)
})

test('Assistant 成功态通过六正式视口、computed style、触控和对比度合同', async ({ browser }) => {
  for (const viewport of viewports) {
    const { context, page } = await createPage(browser, viewport)
    try {
      const assistant = await openAssistant(page, 'success')
      const answer = await waitForScenario(assistant, 'success')
      const layout = await expectNoOverflow(page, assistant, viewport.name)
      const styles = {
        drawer: await elementStyle(assistant),
        header: await elementStyle(assistant.locator('.assistant-header')),
        title: await elementStyle(assistant.locator('.assistant-title h2')),
        scope: await elementStyle(assistant.locator('.scope-chip')),
        messages: await elementStyle(assistant.locator('.assistant-messages')),
        user: await elementStyle(assistant.locator('.user-turn p').last()),
        answer: await elementStyle(answer.locator('.answer-card')),
        answerText: await elementStyle(answer.locator('.answer-card__text')),
        answerMeta: await elementStyle(answer.locator('.answer-meta')),
        citation: await elementStyle(answer.locator('.citation-card')),
        citationRow: await elementStyle(answer.locator('.citation-card li').first()),
        action: await elementStyle(answer.locator('.answer-actions button').first()),
        composer: await elementStyle(assistant.locator('.assistant-composer')),
        textarea: await elementStyle(assistant.getByRole('textbox', { name: '向智能助手提问' })),
        quickPrompt: await elementStyle(assistant.getByRole('button', { name: '查询入住率' })),
        privacy: await elementStyle(assistant.locator('.privacy-note')),
      }
      computedEvidence[viewport.name] = { layout, styles }

      if (viewport.width <= 768) {
        expect(styles.drawer.width).toBe(viewport.width)
        expect(styles.header.height, '移动 Header 不应挤压消息区').toBeLessThanOrEqual(58)
        expect(styles.composer.height, '移动 Composer 不应挤压消息区').toBeLessThanOrEqual(160)
      } else {
        expect(styles.drawer.width).toBeGreaterThanOrEqual(440)
        expect(styles.drawer.width).toBeLessThanOrEqual(480)
      }
      expect(styles.action.flexDirection).toBe('row')

      const requiredText = assistant.locator([
        '.assistant-title h2', '.assistant-title p', '.scope-chip', '.user-turn time', '.user-turn p',
        '.ai-run-status__message', '.answer-card__text', '.answer-meta > span',
        '.citation-card header strong', '.citation-card header small', '.citation-row__copy strong',
        '.citation-row__copy small', '.answer-actions button', '.quick-prompts button', '.privacy-note',
      ].join(', '))
      for (let index = 0; index < await requiredText.count(); index += 1) {
        const style = await elementStyle(requiredText.nth(index))
        expect(Number.parseFloat(style.fontSize), `${viewport.name} 必要文字小于 12px：${JSON.stringify(style)}`).toBeGreaterThanOrEqual(12)
      }

      const contrastPairs = [
        ['.assistant-title h2', '.assistant-header'],
        ['.scope-chip', '.scope-chip'],
        ['.user-turn p', '.user-turn p'],
        ['.answer-card__text', '.answer-card'],
        ['.citation-row__copy strong', '.citation-card'],
        ['.answer-actions button', '.answer-actions button'],
        ['.privacy-note', '.assistant-composer'],
      ] as const
      for (const [foregroundSelector, backgroundSelector] of contrastPairs) {
        const foreground = await elementStyle(assistant.locator(foregroundSelector).first())
        const background = await elementStyle(assistant.locator(backgroundSelector).first())
        expect(
          contrastRatio(foreground.color, background.backgroundColor),
          `${viewport.name} 对比度不足：${foregroundSelector}`,
        ).toBeGreaterThanOrEqual(4.5)
      }

      if (viewport.width <= 768) {
        const targets = assistant.locator('button:visible, textarea:visible')
        for (let index = 0; index < await targets.count(); index += 1) {
          const style = await elementStyle(targets.nth(index))
          expect(style.width, `${viewport.name} 触控目标宽度不足：${JSON.stringify(style)}`).toBeGreaterThanOrEqual(44)
          expect(style.height, `${viewport.name} 触控目标高度不足：${JSON.stringify(style)}`).toBeGreaterThanOrEqual(44)
        }
      }

      await recordScreenshot(page, `assistant-success-${viewport.name}.png`, {
        state: 'success', viewport: viewport.name, width: viewport.width, height: viewport.height,
      })
    } finally {
      await context.close()
    }
  }
})

test('Assistant 完整终态与撤权引用均由确定性 run event fixture 呈现', async ({ browser }) => {
  const scenarios = [
    'low-confidence', 'no-source', 'revoked', 'degraded', 'failed', 'timed-out',
  ] as const
  for (const scenario of scenarios) {
    const { context, page } = await createPage(browser, { width: 1366, height: 768 })
    try {
      const assistant = await openAssistant(page, scenario)
      const answer = await waitForScenario(assistant, scenario)
      const stateTarget = scenario === 'revoked'
        ? answer.locator('[data-assistant-state="citation-denied"]')
        : answer.locator(`[data-assistant-state="${scenario === 'no-source' ? 'no-grounded' : scenario}"]`).last()
      if (await stateTarget.count()) await stateTarget.scrollIntoViewIfNeeded()
      if (scenario === 'failed' || scenario === 'timed-out') {
        await expect(assistant.locator('[data-assistant-state="error"]')).toHaveCount(0)
        await expect(answer.locator(`[data-assistant-state="${scenario}"]`)).toHaveCount(1)
        await expect(page.locator('.dashboard-ai-state[data-state="FAILED"]')).toHaveCount(0)
      }
      await expectNoOverflow(page, assistant, scenario)
      await recordScreenshot(page, `assistant-state-${scenario}.png`, {
        state: scenario, viewport: '1366x768', width: 1366, height: 768,
      })
    } finally {
      await context.close()
    }
  }
})

test('Assistant 授权引用可用键盘展开纯文本详情，撤权引用保持不可操作', async ({ browser }) => {
  const { context, page } = await createPage(browser, { width: 1366, height: 768 })
  try {
    const assistant = await openAssistant(page, 'success')
    const answer = await waitForScenario(assistant, 'success')
    const availableCitation = answer.getByRole('button', { name: '查看引用详情：宿舍维修管理办法' })
    await availableCitation.focus()
    await page.keyboard.press('Enter')
    await expect(availableCitation).toHaveAttribute('aria-expanded', 'true')
    const detail = answer.locator('[data-citation-detail]')
    await expect(detail).toBeVisible()
    await expect(detail).toContainText('演示脱敏片段')
    await expect(detail).not.toContainText(/PERSON_NAME|Digest/)
    await expect(answer.locator('[data-assistant-state="citation-denied"] button')).toHaveCount(0)
    await recordScreenshot(page, 'assistant-state-citation-detail.png', {
      state: 'citation-detail', viewport: '1366x768', width: 1366, height: 768,
    })
  } finally {
    await context.close()
  }
})

test('Assistant 流式、停止、关闭确认、历史、空态、输入态与 PII 映射可操作', async ({ browser }) => {
  const { context, page } = await createPage(browser, { width: 390, height: 844 })
  try {
    const assistant = await openAssistant(page, 'streaming')
    const answer = assistant.locator('[data-assistant-answer]').last()
    await expect(answer).toHaveAttribute('data-assistant-content-state', 'streaming')
    await expect(assistant.getByRole('button', { name: '停止生成' })).toBeVisible()
    await recordScreenshot(page, 'assistant-state-streaming.png', {
      state: 'streaming', viewport: '390x844', width: 390, height: 844,
    })

    await assistant.getByRole('button', { name: '关闭智能助手', exact: true }).click()
    const confirmation = assistant.getByRole('alertdialog')
    await expect(confirmation).toBeVisible()
    await expect(confirmation.getByRole('button', { name: '继续查看' })).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(confirmation).toBeHidden()

    await assistant.getByRole('button', { name: '停止生成' }).click()
    await expect(answer).toHaveAttribute('data-assistant-content-state', 'cancelled')
    await expect(answer.locator('[data-assistant-state="cancelled"]')).toBeVisible()
    await answer.locator('[data-assistant-state="cancelled"]').scrollIntoViewIfNeeded()
    await recordScreenshot(page, 'assistant-state-cancelled.png', {
      state: 'cancelled', viewport: '390x844', width: 390, height: 844,
    })

    await assistant.getByRole('button', { name: '查看会话历史' }).click()
    const history = assistant.getByRole('dialog', { name: '会话历史' })
    await expect(history).toBeVisible()
    await expect(history.getByRole('button', { name: '关闭会话历史' })).toBeFocused()
    await recordScreenshot(page, 'assistant-state-history.png', {
      state: 'history', viewport: '390x844', width: 390, height: 844,
    })
    await history.getByRole('button', { name: '新建会话' }).click()
    await expect(assistant.locator('.assistant-empty')).toBeVisible()
    await recordScreenshot(page, 'assistant-state-empty.png', {
      state: 'empty', viewport: '390x844', width: 390, height: 844,
    })

    const input = assistant.getByRole('textbox', { name: '向智能助手提问' })
    await input.fill('请查询 [PERSON_NAME:v1:PersonDigest01] 的维修时限')
    await expect(input).toHaveValue('请查询 某位同学（已脱敏） 的维修时限')
    await recordScreenshot(page, 'assistant-state-input.png', {
      state: 'input', viewport: '390x844', width: 390, height: 844,
    })
    await assistant.getByRole('button', { name: '发送问题' }).click()
    await expect(assistant.locator('[data-assistant-user-message]').last()).toContainText('某位同学（已脱敏）')
    await expect(assistant.locator('[data-assistant-user-message]').last()).not.toContainText('PERSON_NAME')
    await assistant.getByRole('button', { name: '查看会话历史' }).click()
    await expect(assistant.getByRole('dialog', { name: '会话历史' })).not.toContainText('PERSON_NAME')
  } finally {
    await context.close()
  }
})

test('Assistant 200% 等效视口保持可滚动、无横向溢出且尊重 reduced motion', async ({ browser }) => {
  const { context, page } = await createPage(browser, { width: 960, height: 540 })
  try {
    const assistant = await openAssistant(page, 'success')
    await waitForScenario(assistant, 'success')
    const layout = await expectNoOverflow(page, assistant, '200pct-1920x1080')
    const motion = await assistant.evaluate((element) => {
      const style = getComputedStyle(element)
      const childStyle = getComputedStyle(element.querySelector('button')!)
      return {
        animationDuration: style.animationDuration,
        transitionDuration: childStyle.transitionDuration,
        reduced: matchMedia('(prefers-reduced-motion: reduce)').matches,
      }
    })
    expect(motion.reduced).toBe(true)
    expect(motion.animationDuration).toMatch(/^(0s|0ms)$/)
    expect(motion.transitionDuration).toMatch(/^(0s|0ms)$/)
    computedEvidence['200pct-1920x1080'] = { layout, motion }
    await recordScreenshot(page, 'assistant-success-200pct-1920x1080.png', {
      state: 'success', viewport: '200pct-1920x1080', width: 960, height: 540,
    })
  } finally {
    await context.close()
  }
})
