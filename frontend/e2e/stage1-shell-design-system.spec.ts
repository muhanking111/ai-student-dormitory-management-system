import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type Locator, type Page } from '@playwright/test'
import { mockApi } from './mock-api'
import { designSystemGalleryTarget } from './live-visual-contracts'

const outputDirectory = resolve(process.cwd(), 'test-results', 'stage1-shell-20260727-o')
const capturesDirectory = resolve(outputDirectory, 'captures')
const viewports = [
  { name: '1920x1080', width: 1920, height: 1080 },
  { name: '1366x768', width: 1366, height: 768 },
  { name: '1586x992', width: 1586, height: 992 },
  { name: '1536x1024', width: 1536, height: 1024 },
  { name: '1505x1045', width: 1505, height: 1045 },
  { name: '390x844', width: 390, height: 844 },
  { name: '200pct-1920x1080', width: 960, height: 540 },
] as const

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
      className: element.className,
      ancestors: Array.from({ length: 4 }, (_, index) => {
        let ancestor: HTMLElement | null = element
        for (let depth = 0; depth <= index; depth += 1) ancestor = ancestor?.parentElement ?? null
        return ancestor ? `${ancestor.tagName.toLowerCase()}.${ancestor.className}` : ''
      }),
      backgroundColor: style.backgroundColor,
      color: style.color,
      fontFamily: style.fontFamily,
      fontSize: style.fontSize,
      fontWeight: style.fontWeight,
      lineHeight: style.lineHeight,
      outlineStyle: style.outlineStyle,
      outlineWidth: style.outlineWidth,
      boxShadow: style.boxShadow,
      transitionDuration: style.transitionDuration,
      animationDuration: style.animationDuration,
      width: bounds.width,
      height: bounds.height,
      x: bounds.x,
      y: bounds.y,
      right: bounds.right,
      bottom: bounds.bottom,
    }
  })
}

async function expectNoPageOverflow(page: Page, label: string) {
  const layout = await page.evaluate(() => ({
    viewportWidth: document.documentElement.clientWidth,
    documentWidth: Math.max(document.documentElement.scrollWidth, document.body.scrollWidth),
  }))
  expect(layout.documentWidth, `${label} 页面出现横向滚动`).toBeLessThanOrEqual(layout.viewportWidth + 1)
  return layout
}

test.beforeAll(() => {
  mkdirSync(capturesDirectory, { recursive: true })
})

test('1505x1045 Design System 状态画廊完整且使用真实共享组件', async ({ page }) => {
  await page.setViewportSize({ width: 1505, height: 1045 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto(designSystemGalleryTarget.route)
  await expect(page.getByRole('heading', { name: 'AI 智能宿舍设计系统', exact: true })).toBeVisible()
  await expect(page.locator('[data-gallery-section]')).toHaveCount(6)

  const states = await page.locator('[data-control-state]').evaluateAll((elements) => (
    Array.from(new Set(elements.map((element) => element.getAttribute('data-control-state')).filter(Boolean)))
  ))
  expect(states).toEqual(expect.arrayContaining(['default', 'hover', 'focus', 'active', 'disabled', 'loading', 'error']))
  for (const selector of ['.ai-command-bar', '.ai-evidence', '.ai-proposal-preview', '.ai-run-status', '.ai-safety-state']) {
    await expect(page.locator(selector).first(), `${selector} 未使用真实共享组件`).toBeVisible()
  }
  await expect(page.locator('[data-citation-card]')).toHaveCount(3)
  for (const kind of ['primary', 'secondary', 'text']) {
    await expect(page.locator(`[data-button-kind="${kind}"] [data-control-state]`)).toHaveCount(7)
  }

  const specimenStyles = {
    pageTitle: await elementStyle(page.locator('.type-page-title')),
    cardTitle: await elementStyle(page.locator('.type-card-title')),
    body: await elementStyle(page.locator('.type-body')),
    caption: await elementStyle(page.locator('.type-caption')),
  }
  expect(specimenStyles.pageTitle.fontSize).toBe('32px')
  expect(specimenStyles.pageTitle.fontWeight).toBe('700')
  expect(specimenStyles.cardTitle.fontSize).toBe('20px')
  expect(specimenStyles.cardTitle.fontWeight).toBe('600')
  expect(specimenStyles.body.fontSize).toBe('14px')
  expect(specimenStyles.caption.fontSize).toBe('12px')

  const interactiveTargets = page.locator('[data-design-system-gallery] button, [data-design-system-gallery] input')
  for (let index = 0; index < await interactiveTargets.count(); index += 1) {
    const style = await elementStyle(interactiveTargets.nth(index))
    expect(style.width, `画廊交互目标宽度不足：${JSON.stringify(style)}`).toBeGreaterThanOrEqual(44)
    expect(style.height, `画廊交互目标高度不足：${JSON.stringify(style)}`).toBeGreaterThanOrEqual(44)
  }

  const requiredTextSelectors = [
    '.color-list small', '.type-samples code', '.type-samples code small', '.safety-tag',
    '.gallery-specimen-label', '.gallery-suggestion__title', '.gallery-suggestion p',
    '.gallery-evidence-summary', '.gallery-citation-stack .ai-citation-link',
    '.gallery-citation-stack .ai-citation-denied', '.gallery-ai .ai-flow',
    '.gallery-ai .ai-value-diff > div', '.gallery-ai .ai-proposal-preview__impact',
    '.gallery-run-row .ai-run-status', '.gallery-run-row .ai-run-status__action',
    '.state-block > strong', '.state-column-labels span:not(:first-child)',
    '.button-state-row > span', '.gallery-button', '.input-state-label',
    '.input-samples input', '.switch-samples label > span',
  ]
  const requiredText = page.locator(requiredTextSelectors.join(', '))
  for (let index = 0; index < await requiredText.count(); index += 1) {
    const style = await elementStyle(requiredText.nth(index))
    expect(Number.parseFloat(style.fontSize), `必要文字小于 12px：${JSON.stringify(style)}`).toBeGreaterThanOrEqual(12)
  }

  const contrastSamples = [
    { foreground: '.gallery-suggestion__title', background: '.gallery-suggestion' },
    { foreground: '.gallery-evidence-summary', background: '.gallery-suggestion' },
    { foreground: '.gallery-button--error', background: '.gallery-button--error' },
    { foreground: '.safety-tag--danger', background: '.safety-tag--danger' },
    { foreground: '.safety-tag--warning', background: '.safety-tag--warning' },
    { foreground: '.safety-tag--success', background: '.safety-tag--success' },
    { foreground: '.safety-tag--info', background: '.safety-tag--info' },
    { foreground: '.safety-tag--ai', background: '.safety-tag--ai' },
    { foreground: '.safety-tag--muted', background: '.safety-tag--muted' },
  ]
  for (const sample of contrastSamples) {
    const foreground = await elementStyle(page.locator(sample.foreground).first())
    const background = await elementStyle(page.locator(sample.background).first())
    expect(
      contrastRatio(foreground.color, background.backgroundColor),
      `必要文字对比度不足：${JSON.stringify({ sample, foreground, background })}`,
    ).toBeGreaterThanOrEqual(4.5)
  }

  const expectedPanelBounds = [
    { selector: '.gallery-colors', x: 34, y: 118, width: 420, height: 475 },
    { selector: '.gallery-typography', x: 470, y: 118, width: 553, height: 475 },
    { selector: '.gallery-tokens', x: 1039, y: 118, width: 432, height: 475 },
    { selector: '.gallery-safety', x: 34, y: 609, width: 420, height: 411 },
    { selector: '.gallery-ai', x: 470, y: 609, width: 553, height: 411 },
    { selector: '.gallery-states', x: 1039, y: 609, width: 432, height: 411 },
  ]
  for (const expectedBounds of expectedPanelBounds) {
    const style = await elementStyle(page.locator(expectedBounds.selector))
    for (const property of ['x', 'y', 'width', 'height'] as const) {
      expect(Math.abs(style[property] - expectedBounds[property]), `${expectedBounds.selector} ${property} 偏离原型`).toBeLessThanOrEqual(2)
    }
  }

  const layout = await expectNoPageOverflow(page, 'Design System')
  const galleryHeight = await page.locator('[data-design-system-gallery]').evaluate((element) => element.scrollHeight)
  expect(galleryHeight, '状态画廊超出 1505x1045 首屏').toBeLessThanOrEqual(1045)
  const rootTokens = await page.evaluate(() => {
    const style = getComputedStyle(document.documentElement)
    return Object.fromEntries([
      '--primary', '--sidebar', '--touch-target', '--radius-control', '--radius-card',
      '--font-size-page-title', '--font-size-card-title', '--font-size-body', '--font-size-caption',
    ].map((token) => [token, style.getPropertyValue(token).trim()]))
  })
  expect(rootTokens).toMatchObject({
    '--primary': '#2563eb',
    '--sidebar': '#163b83',
    '--touch-target': '44px',
    '--radius-control': '8px',
    '--radius-card': '12px',
    '--font-size-page-title': '32px',
    '--font-size-card-title': '20px',
    '--font-size-body': '14px',
    '--font-size-caption': '12px',
  })
  const computed = {
    target: designSystemGalleryTarget,
    viewport: '1505x1045',
    layout,
    rootTokens,
    fontFamily: await page.locator('[data-design-system-gallery]').evaluate((element) => getComputedStyle(element).fontFamily),
    sectionCount: await page.locator('[data-gallery-section]').count(),
    states,
    specimenStyles,
    panelBounds: await Promise.all(expectedPanelBounds.map(async ({ selector }) => ({ selector, style: await elementStyle(page.locator(selector)) }))),
  }
  writeFileSync(resolve(outputDirectory, 'design-system-gallery-computed.json'), `${JSON.stringify(computed, null, 2)}\n`)
  await page.screenshot({ path: resolve(capturesDirectory, `${designSystemGalleryTarget.slug}-1505x1045.png`), animations: 'disabled', caret: 'hide' })
})

for (const viewport of viewports) {
  test(`共享壳层 ${viewport.name} 运行态合同`, async ({ page }) => {
    await page.setViewportSize({ width: viewport.width, height: viewport.height })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await mockApi(page, true)
    await page.goto('/repairs')
    await expect(page.getByRole('heading', { name: '维修智能分诊', exact: true })).toBeVisible()
    await expect(page.locator('.ant-spin-spinning')).toHaveCount(0)
    expect(await page.evaluate(() => matchMedia('(prefers-reduced-motion: reduce)').matches)).toBe(true)

    const menu = page.locator('.side-menu')
    const repairGroup = page.locator('.side-menu > .ant-menu-submenu').filter({ hasText: '维修管理' }).first()
    const repairTitle = repairGroup.locator(':scope > .ant-menu-submenu-title')
    const compact = viewport.width <= 1100
    await expect(repairGroup).toBeVisible()
    if (compact) {
      await expect(repairTitle).toHaveAttribute('aria-expanded', 'false')
      await expect(repairGroup).toHaveClass(/side-menu-submenu--route-active/)
    } else {
      await expect(repairTitle).toHaveAttribute('aria-expanded', 'true')
      await expect(page.locator('.side-menu .ant-menu-item-selected')).toBeVisible()
    }
    const parentAutoExpanded = await repairTitle.getAttribute('aria-expanded')
    await expect(menu).toHaveAttribute('tabindex', '0')

    const titleStyle = await elementStyle(repairTitle)
    let selectedStyle: Awaited<ReturnType<typeof elementStyle>>
    expect(titleStyle.fontFamily).toContain('Inter')
    expect(Number(titleStyle.fontWeight)).toBe(600)
    expect(titleStyle.height).toBeGreaterThanOrEqual(44)
    if (!compact) expect(contrastRatio(titleStyle.color, 'rgb(22, 59, 131)')).toBeGreaterThanOrEqual(7)
    await page.screenshot({ path: resolve(capturesDirectory, `shell-repairs-${viewport.name}.png`), animations: 'disabled', caret: 'hide' })

    await menu.focus()
    await expect(menu).toBeFocused()
    const focusedMenuStyle = await elementStyle(menu)
    expect(focusedMenuStyle.outlineStyle).not.toBe('none')
    expect(Number.parseFloat(focusedMenuStyle.outlineWidth)).toBeGreaterThanOrEqual(2)
    await page.keyboard.press('ArrowDown')
    const keyboardTarget = page.locator(':focus')
    await expect(keyboardTarget).toHaveClass(/ant-menu-(item|submenu-title)/)
    const keyboardTargetStyle = await elementStyle(keyboardTarget)
    expect(keyboardTargetStyle.outlineStyle).not.toBe('none')
    await page.screenshot({ path: resolve(capturesDirectory, `shell-repairs-focus-${viewport.name}.png`), animations: 'disabled', caret: 'hide' })
    if (compact) {
      const popup = page.locator('.side-menu-popup:visible')
      const rootTargets = page.locator('.side-menu .ant-menu-item:visible, .side-menu .ant-menu-submenu-title:visible')

      await page.keyboard.press('Home')
      await expect(rootTargets.first()).toBeFocused()
      await page.keyboard.press('ArrowUp')
      await expect(rootTargets.last()).toBeFocused()
      await page.keyboard.press('ArrowDown')
      await expect(rootTargets.first()).toBeFocused()
      await page.keyboard.press('End')
      await expect(rootTargets.last()).toBeFocused()

      await repairTitle.focus()
      await page.keyboard.press('ArrowRight')
      await expect(popup).toHaveCount(1)
      const popupItems = popup.locator('.ant-menu-item')
      await expect(popupItems.first()).toBeFocused()
      await page.keyboard.press('End')
      await expect(popupItems.last()).toBeFocused()
      await page.keyboard.press('Home')
      await expect(popupItems.first()).toBeFocused()
      await page.keyboard.press('ArrowUp')
      await expect(popupItems.last()).toBeFocused()
      await page.keyboard.press('ArrowDown')
      await expect(popupItems.first()).toBeFocused()
      await page.keyboard.press('ArrowLeft')
      await expect(popup).toHaveCount(0)
      await expect(repairTitle).toBeFocused()

      await page.keyboard.press('Space')
      await expect(popup).toHaveCount(1)
      await expect(popupItems.first()).toBeFocused()
      await page.keyboard.press('ArrowDown')
      await expect(popupItems.nth(1)).toBeFocused()
      await page.keyboard.press('Space')
      await expect.poll(() => new URL(page.url()).pathname).toBe('/repairs/records')
      await expect(popup).toHaveCount(0)
      await expect(repairTitle).toBeFocused()

      await repairTitle.press('Enter')
      await expect(popup).toHaveCount(1)
      await expect(popupItems.first()).toBeFocused()
      await page.keyboard.press('Enter')
      await expect.poll(() => new URL(page.url()).pathname).toBe('/repairs')
      await expect(popup).toHaveCount(0)
      await expect(repairTitle).toBeFocused()

      await repairTitle.press('Enter')
      await expect(popup).toHaveCount(1)
      await expect(popupItems.first()).toBeFocused()
      await page.keyboard.press('Escape')
      await expect(popup).toHaveCount(0)
      await expect(repairTitle).toBeFocused()

      const studentTitle = page.locator('.side-menu > .ant-menu-submenu').filter({ hasText: '学生管理' }).first().locator(':scope > .ant-menu-submenu-title')
      await studentTitle.click()
      await expect(page.locator('.side-menu-popup:visible')).toHaveCount(1)
      await repairTitle.click()
      await expect(page.locator('.side-menu-popup:visible')).toHaveCount(1)
      await expect(page.locator('.side-menu-popup:visible .ant-menu-item-selected')).toHaveText('报修列表')
      await expect(page.locator('.side-menu-popup:visible .ant-menu-item').first()).toBeFocused()
      await page.keyboard.press('Escape')
      await expect(page.locator('.side-menu-popup:visible')).toHaveCount(0)
      await expect(repairTitle).toBeFocused()
    }
    else {
      await page.keyboard.press('Enter')
      expect(new URL(page.url()).pathname).toBe('/repairs')
    }

    const header = page.locator('.admin-header')
    const headerStyle = await elementStyle(header)
    const headerControls = page.locator('.admin-header button:visible')
    for (let index = 0; index < await headerControls.count(); index += 1) {
      const controlStyle = await elementStyle(headerControls.nth(index))
      expect(controlStyle.height, `${viewport.name} Header 控件高度不足`).toBeGreaterThanOrEqual(44)
      expect(controlStyle.y, `${viewport.name} Header 控件越过顶部`).toBeGreaterThanOrEqual(headerStyle.y - 1)
      expect(controlStyle.bottom, `${viewport.name} Header 控件越过底部`).toBeLessThanOrEqual(headerStyle.bottom + 1)
    }
    const accountButton = page.getByRole('button', { name: /账户菜单/ })
    await accountButton.click()
    const logoutItem = page.getByRole('menuitem', { name: '退出登录' })
    await expect(logoutItem).toBeVisible()
    const logoutItemStyle = await elementStyle(logoutItem)
    expect(logoutItemStyle.height, `${viewport.name} 账户菜单触控高度不足：${JSON.stringify(logoutItemStyle)}`).toBeGreaterThanOrEqual(44)
    await accountButton.click()
    await expect(logoutItem).toBeHidden()

    let submenuEvidence: Record<string, unknown>
    if (compact) {
      expect(titleStyle.width, `${viewport.name} 折叠菜单触控宽度不足`).toBeGreaterThanOrEqual(44)
      await repairTitle.click()
      const popup = page.locator('.side-menu-popup:visible')
      await expect(popup).toBeVisible()
      const popupMenu = popup.locator('.ant-menu').first()
      const popupItem = popup.locator('.ant-menu-item-selected')
      const popupOrdinaryItem = popup.locator('.ant-menu-item:not(.ant-menu-item-selected)').first()
      const popupStyle = await elementStyle(popupMenu)
      const popupItemStyle = await elementStyle(popupItem)
      const popupOrdinaryStyle = await elementStyle(popupOrdinaryItem)
      selectedStyle = popupItemStyle
      expect(popupStyle.backgroundColor).toBe('rgb(22, 59, 131)')
      expect(popupStyle.backgroundColor).not.toBe('rgb(0, 12, 23)')
      expect(popupItemStyle.height).toBeGreaterThanOrEqual(44)
      expect(contrastRatio(popupOrdinaryStyle.color, popupStyle.backgroundColor)).toBeGreaterThanOrEqual(7)
      expect(popupStyle.transitionDuration).toMatch(/^(0s|0ms)(, (0s|0ms))*$/)
      submenuEvidence = { kind: 'popup', popupStyle, popupItemStyle, popupOrdinaryStyle }
    } else {
      selectedStyle = await elementStyle(page.locator('.side-menu .ant-menu-item-selected'))
      const inlineMenu = repairGroup.locator('.ant-menu-sub.ant-menu-inline')
      const inlineStyle = await elementStyle(inlineMenu)
      expect(inlineStyle.backgroundColor).not.toBe('rgb(0, 12, 23)')
      const studentGroup = page.locator('.side-menu > .ant-menu-submenu').filter({ hasText: '学生管理' }).first()
      const studentTitle = studentGroup.locator(':scope > .ant-menu-submenu-title')
      await studentTitle.click()
      await expect(studentTitle).toHaveAttribute('aria-expanded', 'true')
      await expect(repairTitle).toHaveAttribute('aria-expanded', 'false')
      await expect(repairGroup).toHaveClass(/side-menu-submenu--route-active/)
      const routeActiveStyle = await elementStyle(repairTitle)
      expect(routeActiveStyle.backgroundColor).not.toBe('rgba(0, 0, 0, 0)')
      expect(routeActiveStyle.backgroundColor).not.toBe('rgb(22, 59, 131)')
      expect(routeActiveStyle.boxShadow).not.toBe('none')
      submenuEvidence = { kind: 'inline', inlineStyle }
    }

    expect(Number(selectedStyle.fontWeight)).toBe(500)
    expect(selectedStyle.height).toBeGreaterThanOrEqual(44)

    expect(titleStyle.transitionDuration).toMatch(/^(0s|0ms)(, (0s|0ms))*$/)
    expect(titleStyle.animationDuration).toMatch(/^(0s|0ms)(, (0s|0ms))*$/)
    const layout = await expectNoPageOverflow(page, `${viewport.name} /repairs`)
    const evidence = {
      viewport: viewport.name,
      layout,
      titleStyle,
      selectedStyle,
      focusedMenuStyle,
      keyboardTargetStyle,
      header: { style: headerStyle, controlCount: await headerControls.count(), logoutItemStyle },
      submenu: submenuEvidence,
      parentAutoExpanded,
    }
    writeFileSync(resolve(outputDirectory, `shell-${viewport.name}-computed.json`), `${JSON.stringify(evidence, null, 2)}\n`)
    const interactionState = compact ? 'popup' : 'accordion'
    await page.screenshot({ path: resolve(capturesDirectory, `shell-repairs-${interactionState}-${viewport.name}.png`), animations: 'disabled', caret: 'hide' })
  })
}
