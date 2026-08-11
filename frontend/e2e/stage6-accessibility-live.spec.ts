import { createHash } from 'node:crypto'
import {
  existsSync,
  mkdirSync,
  readFileSync,
  readdirSync,
  statSync,
  writeFileSync,
} from 'node:fs'
import { basename, relative, resolve } from 'node:path'
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

declare global {
  interface Document {
    getAnimations(options: { subtree: boolean }): Animation[]
  }
}

export const STAGE6_ACCESSIBILITY_ROUTES = [
  { path: '/', title: '首页', slug: 'dashboard' },
  { path: '/system/users', title: '用户管理', slug: 'users' },
  { path: '/system/roles', title: '权限管理', slug: 'roles' },
  { path: '/dormitories', title: '宿舍列表', slug: 'dormitories' },
  { path: '/buildings', title: '楼栋管理', slug: 'buildings' },
  { path: '/beds', title: '床位管理', slug: 'beds' },
  { path: '/students', title: '学生信息', slug: 'students' },
  { path: '/applications', title: '入住申请', slug: 'applications' },
  { path: '/assignments', title: '宿舍分配', slug: 'assignments' },
  { path: '/checkouts', title: '退宿管理', slug: 'checkouts' },
  { path: '/repairs', title: '维修智能分诊', slug: 'repairs' },
  { path: '/repairs/records', title: '维修记录', slug: 'repair-records' },
  { path: '/payments', title: '费用列表', slug: 'payments' },
  { path: '/payments/records', title: '收费记录', slug: 'payment-records' },
  { path: '/hygiene', title: '卫生检查', slug: 'hygiene' },
  { path: '/hygiene/records', title: '检查记录', slug: 'hygiene-records' },
  { path: '/notices', title: '公告列表', slug: 'notices' },
  { path: '/notices/create', title: '公告 AI 起草', slug: 'notice-create' },
  { path: '/ai/knowledge', title: '知识管理', slug: 'ai-knowledge' },
  { path: '/ai/risks', title: '智能风险中心', slug: 'ai-risks' },
  { path: '/ai/approvals', title: '待审批', slug: 'ai-approvals' },
  { path: '/ai/audit', title: '运行审计', slug: 'ai-audit' },
] as const

export const STAGE6_ACCESSIBILITY_PROFILES = [
  { name: '200pct-1920x1080', width: 960, height: 540, touch: false },
  { name: 'touch-390x844', width: 390, height: 844, touch: true },
] as const

type Rgba = { red: number; green: number; blue: number; alpha: number }

export interface ContrastSampleInput {
  selector: string
  text: string
  color: string
  backgroundLayers: string[]
  fontSizePx: number
  fontWeight: number
}

export interface FocusIndicatorInput {
  focusVisible: boolean
  outlineStyle: string
  outlineWidth: string
  outlineColor: string
  boxShadow: string
}

export interface AnimationRecord {
  target: string
  playState: string
  durationMs: number
  name: string
}

export interface TextClipInput {
  selector: string
  text: string
  scrollWidth: number
  clientWidth: number
  scrollHeight: number
  clientHeight: number
  title: string
  ariaLabel: string
}

export interface TouchTargetInput {
  selector: string
  tag: string
  width: number
  height: number
  compositeWidth?: number
  compositeHeight?: number
  display: string
  accessibleName: string
  parentTag: string
  hasGraphic: boolean
  controlType: string
  labelWidth: number
  labelHeight: number
}

interface FileBinding {
  file: string
  bytes: number
  sha256: string
  mtimeUtc: string
}

interface AccessibilityViolation {
  profile: string
  route: string
  kind: string
  detail: string
  selector?: string
}

interface AccessibilityException {
  profile: string
  route: string
  kind: 'INLINE_PROSE_LINK'
  detail: string
  selector: string
}

interface RuntimeReadinessObservation {
  profile: string
  observedAt: string
  source: 'GET /api/ai/operations/readiness'
  providerAlias: string
  writeExecutionEnabled: boolean
  masterEnabled: boolean
  streamingEnabled: boolean
}

interface BrowserTextSample extends ContrastSampleInput {
  backgroundImage: string
  scrollWidth: number
  clientWidth: number
  scrollHeight: number
  clientHeight: number
  title: string
  ariaLabel: string
  overflowX: string
  overflowY: string
  textOverflow: string
  lineClamp: string
}

interface BrowserTouchSample extends TouchTargetInput {
  disabled: boolean
}

interface RouteBrowserSnapshot {
  viewportWidth: number
  documentWidth: number
  reducedMotionMatches: boolean
  animations: AnimationRecord[]
  textSamples: BrowserTextSample[]
  touchSamples: BrowserTouchSample[]
}

const unsafeMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])
const representativeRoutes = new Set([
  '/',
  '/repairs',
  '/notices/create',
  '/ai/risks',
  '/ai/approvals',
  '/ai/audit',
])
const repositoryDirectory = resolve(visualDirectory, '..', '..', '..')
const requestedOutputName = process.env.STAGE6_ACCESSIBILITY_OUTPUT_NAME
  ?? `${basename(visualDirectory)}-accessibility`
const sanitizedOutputName = requestedOutputName.replace(/[^A-Za-z0-9._-]/g, '_')
const outputName = sanitizedOutputName === '.' || sanitizedOutputName === '..'
  ? 'stage6-accessibility'
  : sanitizedOutputName || 'stage6-accessibility'
const outputDirectory = resolve(visualDirectory, '..', outputName)
const screenshotsDirectory = resolve(outputDirectory, 'screenshots')
const manifestPath = resolve(outputDirectory, 'manifest.json')
const computedRecordsPath = resolve(outputDirectory, 'computed-records.json')
const violationsPath = resolve(outputDirectory, 'violations.json')
const exceptionsPath = resolve(outputDirectory, 'exceptions.json')

function clampChannel(value: number) {
  return Math.max(0, Math.min(255, value))
}

function parseCssChannel(value: string) {
  return value.endsWith('%')
    ? clampChannel(Number.parseFloat(value) * 2.55)
    : clampChannel(Number.parseFloat(value))
}

function parseCssColor(value: string): Rgba | null {
  const normalized = value.trim().toLowerCase()
  if (normalized === 'transparent') return { red: 0, green: 0, blue: 0, alpha: 0 }

  const hex = normalized.match(/^#([0-9a-f]{3,8})$/i)?.[1]
  if (hex) {
    const expanded = hex.length === 3 || hex.length === 4
      ? [...hex].map((digit) => `${digit}${digit}`).join('')
      : hex
    const hasAlpha = expanded.length === 8
    return {
      red: Number.parseInt(expanded.slice(0, 2), 16),
      green: Number.parseInt(expanded.slice(2, 4), 16),
      blue: Number.parseInt(expanded.slice(4, 6), 16),
      alpha: hasAlpha ? Number.parseInt(expanded.slice(6, 8), 16) / 255 : 1,
    }
  }

  const rgb = normalized.match(
    /^rgba?\(\s*([+-]?[\d.]+%?)\s*[, ]\s*([+-]?[\d.]+%?)\s*[, ]\s*([+-]?[\d.]+%?)(?:\s*[,/]\s*([+-]?[\d.]+%?))?\s*\)$/,
  )
  if (!rgb) return null
  const alphaValue = rgb[4]
  const alpha = alphaValue
    ? Math.max(0, Math.min(1, alphaValue.endsWith('%')
      ? Number.parseFloat(alphaValue) / 100
      : Number.parseFloat(alphaValue)))
    : 1
  return {
    red: parseCssChannel(rgb[1]),
    green: parseCssChannel(rgb[2]),
    blue: parseCssChannel(rgb[3]),
    alpha,
  }
}

function composite(over: Rgba, under: Rgba): Rgba {
  const alpha = over.alpha + under.alpha * (1 - over.alpha)
  if (alpha <= 0) return { red: 0, green: 0, blue: 0, alpha: 0 }
  return {
    red: (over.red * over.alpha + under.red * under.alpha * (1 - over.alpha)) / alpha,
    green: (over.green * over.alpha + under.green * under.alpha * (1 - over.alpha)) / alpha,
    blue: (over.blue * over.alpha + under.blue * under.alpha * (1 - over.alpha)) / alpha,
    alpha,
  }
}

function relativeLuminance(color: Rgba) {
  const linear = [color.red, color.green, color.blue].map((channel) => {
    const value = channel / 255
    return value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * linear[0] + 0.7152 * linear[1] + 0.0722 * linear[2]
}

function ratioBetween(left: Rgba, right: Rgba) {
  const bright = Math.max(relativeLuminance(left), relativeLuminance(right))
  const dark = Math.min(relativeLuminance(left), relativeLuminance(right))
  return (bright + 0.05) / (dark + 0.05)
}

export function analyzeContrastSample(sample: ContrastSampleInput) {
  const foreground = parseCssColor(sample.color)
  const background = sample.backgroundLayers.reduceRight<Rgba>((under, layer) => {
    const parsed = parseCssColor(layer)
    return parsed ? composite(parsed, under) : under
  }, { red: 255, green: 255, blue: 255, alpha: 1 })
  const isLarge = sample.fontSizePx >= 24
    || (sample.fontSizePx >= 18.6667 && sample.fontWeight >= 700)
  const threshold = isLarge ? 3 : 4.5
  if (!foreground) {
    return {
      selector: sample.selector,
      ratio: 0,
      threshold,
      violation: `${sample.selector} contrast color parsing failed: ${sample.color}`,
    }
  }
  const effectiveForeground = composite(foreground, background)
  const ratio = ratioBetween(effectiveForeground, background)
  return {
    selector: sample.selector,
    ratio,
    threshold,
    violation: ratio + 0.001 < threshold
      ? `${sample.selector} contrast ${ratio.toFixed(2)}:1 is below ${threshold}:1`
      : null,
  }
}

export function hasVisibleFocusIndicator(input: FocusIndicatorInput) {
  if (!input.focusVisible) return false
  const outlineWidth = Number.parseFloat(input.outlineWidth)
  const outlineColor = parseCssColor(input.outlineColor)
  const visibleOutline = Number.isFinite(outlineWidth)
    && outlineWidth >= 1
    && !['none', 'hidden'].includes(input.outlineStyle)
    && Boolean(outlineColor && outlineColor.alpha > 0)
  if (visibleOutline) return true
  if (!input.boxShadow || input.boxShadow === 'none') return false
  const shadowColors = input.boxShadow.match(/rgba?\([^)]*\)|#[0-9a-f]{3,8}/gi) ?? []
  return shadowColors.length === 0 || shadowColors.some((color) => (parseCssColor(color)?.alpha ?? 0) > 0)
}

export function findActiveAnimationViolations(records: AnimationRecord[]) {
  return records
    .filter((record) => record.playState === 'running' && record.durationMs > 0)
    .map((record) => (
      `${record.target} animation ${record.name || '<anonymous>'} remains running for ${record.durationMs}ms`
    ))
}

function normalizedText(value: string) {
  return value.replace(/\s+/g, ' ').trim()
}

export function textClipViolation(input: TextClipInput) {
  const text = normalizedText(input.text)
  if (!text) return null
  const alternative = [input.title, input.ariaLabel]
    .map(normalizedText)
    .some((value) => value === text || value.includes(text))
  if (alternative) return null
  const directions = [
    ...(input.scrollWidth > input.clientWidth + 1 ? ['horizontal'] : []),
    ...(input.scrollHeight > input.clientHeight + 1 ? ['vertical'] : []),
  ]
  return directions.length > 0
    ? `${input.selector} ${directions.join(' and ')} text clipping has no auditable full-text alternative`
    : null
}

function formatPixels(value: number) {
  const rounded = Math.round(value * 100) / 100
  return Number.isInteger(rounded) ? String(rounded) : rounded.toFixed(2).replace(/0+$/, '').replace(/\.$/, '')
}

export function touchTargetAssessment(input: TouchTargetInput) {
  const labelledControl = ['checkbox', 'radio'].includes(input.controlType.toLowerCase())
  const effectiveWidth = Math.max(
    input.width,
    input.compositeWidth ?? 0,
    labelledControl ? input.labelWidth : 0,
  )
  const effectiveHeight = Math.max(
    input.height,
    input.compositeHeight ?? 0,
    labelledControl ? input.labelHeight : 0,
  )
  if (!normalizedText(input.accessibleName)) {
    return { violation: `${input.selector} has no accessible name`, exception: null }
  }
  if (effectiveWidth >= 44 && effectiveHeight >= 44) return { violation: null, exception: null }
  const inlineProseLink = input.tag.toUpperCase() === 'A'
    && input.display.startsWith('inline')
    && ['P', 'LI'].includes(input.parentTag.toUpperCase())
    && !input.hasGraphic
  if (inlineProseLink) {
    return {
      violation: null,
      exception: `INLINE_PROSE_LINK: ${input.selector} (${formatPixels(effectiveWidth)}x${formatPixels(effectiveHeight)}px)`,
    }
  }
  return {
    violation: `${input.selector} effective target is ${formatPixels(effectiveWidth)}x${formatPixels(effectiveHeight)}px`,
    exception: null,
  }
}

export function sha256Hex(bytes: Uint8Array | string) {
  return createHash('sha256').update(bytes).digest('hex').toUpperCase()
}

export function bindingMatches(
  binding: Pick<FileBinding, 'bytes' | 'sha256'> & Partial<Pick<FileBinding, 'file'>>,
  bytes: Uint8Array,
) {
  return binding.bytes === bytes.byteLength && binding.sha256 === sha256Hex(bytes)
}

function collectFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = resolve(directory, entry.name)
    return entry.isDirectory() ? collectFiles(path) : [path]
  })
}

function fileBinding(file: string, path: string): FileBinding {
  const bytes = readFileSync(path)
  return {
    file,
    bytes: bytes.byteLength,
    sha256: sha256Hex(bytes),
    mtimeUtc: statSync(path).mtime.toISOString(),
  }
}

function collectSourceBindings() {
  const sourceFiles = [
    ...collectFiles(resolve(repositoryDirectory, 'frontend', 'src')),
    ...collectFiles(resolve(repositoryDirectory, 'backend', 'src')),
  ].filter((path) => /\.(?:css|html|java|json|sql|ts|vue|xml|ya?ml)$/i.test(path))
  const harnessFiles = [
    resolve(repositoryDirectory, 'frontend', 'e2e', 'stage6-accessibility-live.spec.ts'),
    resolve(repositoryDirectory, 'frontend', 'src', 'utils', 'stage6-accessibility-contracts.test.ts'),
    resolve(repositoryDirectory, 'frontend', 'playwright.stage6-accessibility.config.ts'),
    resolve(repositoryDirectory, 'frontend', 'playwright.visual.config.ts'),
    resolve(repositoryDirectory, 'frontend', 'e2e', 'live-visual-settings.ts'),
    resolve(repositoryDirectory, 'frontend', 'e2e', 'live-visual-login-retry.ts'),
    resolve(repositoryDirectory, 'frontend', 'package.json'),
    resolve(repositoryDirectory, 'frontend', 'package-lock.json'),
    resolve(repositoryDirectory, 'backend', 'pom.xml'),
  ].filter(existsSync)
  return Array.from(new Set([...sourceFiles, ...harnessFiles]))
    .sort((left, right) => left.localeCompare(right))
    .map((path) => fileBinding(relative(repositoryDirectory, path).replaceAll('\\', '/'), path))
}

function verifyBindings(bindings: FileBinding[], baseDirectory: string, label: string) {
  const violations: string[] = []
  const seen = new Set<string>()
  for (const binding of bindings) {
    if (seen.has(binding.file)) violations.push(`${label}: duplicate ${binding.file}`)
    seen.add(binding.file)
    const path = resolve(baseDirectory, binding.file)
    if (!existsSync(path)) {
      violations.push(`${label}: missing ${binding.file}`)
      continue
    }
    const bytes = readFileSync(path)
    if (!bindingMatches(binding, bytes)) {
      violations.push(`${label}: bytes/sha256 mismatch ${binding.file}`)
    }
    const mtimeUtc = statSync(path).mtime.toISOString()
    if (mtimeUtc !== binding.mtimeUtc) violations.push(`${label}: mtime mismatch ${binding.file}`)
  }
  return violations
}

function textMetadata(text: string) {
  const normalized = normalizedText(text)
  return { textLength: normalized.length, textSha256: sha256Hex(normalized) }
}

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

async function observeReadiness(context: BrowserContext, profile: string) {
  const response = await context.request.get('/api/ai/operations/readiness')
  const raw = await response.text()
  let envelope: { data?: Record<string, unknown>; message?: string }
  try {
    envelope = JSON.parse(raw) as typeof envelope
  } catch {
    throw new Error(`${profile} readiness returned non-JSON: ${sanitizeDiagnostic(raw)}`)
  }
  if (response.status() !== 200 || !envelope.data) {
    throw new Error(`${profile} readiness HTTP ${response.status()}: ${sanitizeDiagnostic(envelope.message ?? raw)}`)
  }
  const readiness = envelope.data
  expect(readiness.providerAlias, `${profile} provider must remain fake`).toBe('fake')
  expect(readiness.writeExecutionEnabled, `${profile} AI write execution must remain disabled`).toBe(false)
  return {
    profile,
    observedAt: new Date().toISOString(),
    source: 'GET /api/ai/operations/readiness' as const,
    providerAlias: String(readiness.providerAlias ?? ''),
    writeExecutionEnabled: readiness.writeExecutionEnabled === true,
    masterEnabled: readiness.masterEnabled === true,
    streamingEnabled: readiness.streamingEnabled === true,
  }
}

async function focusFirstVisibleInteractive(page: Page) {
  await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur())
  for (let attempt = 0; attempt < 16; attempt += 1) {
    await page.keyboard.press('Tab')
    const focus = await page.evaluate(() => {
      const element = document.activeElement as HTMLElement | null
      if (!element || element === document.body || element === document.documentElement) return null
      const bounds = element.getBoundingClientRect()
      const style = getComputedStyle(element)
      const visible = bounds.width > 0
        && bounds.height > 0
        && style.display !== 'none'
        && style.visibility !== 'hidden'
        && Number.parseFloat(style.opacity || '1') > 0
      if (!visible) return null
      const selector = element.id
        ? `#${CSS.escape(element.id)}`
        : `${element.tagName.toLowerCase()}${Array.from(element.classList).slice(0, 2)
            .map((name) => `.${CSS.escape(name)}`).join('')}`
      return {
        selector,
        tag: element.tagName,
        focusVisible: element.matches(':focus-visible'),
        outlineStyle: style.outlineStyle,
        outlineWidth: style.outlineWidth,
        outlineColor: style.outlineColor,
        boxShadow: style.boxShadow,
      }
    })
    if (focus) return focus
  }
  return null
}

async function collectRouteSnapshot(page: Page): Promise<RouteBrowserSnapshot> {
  return page.evaluate(() => {
    const visible = (element: HTMLElement) => {
      const bounds = element.getBoundingClientRect()
      if (bounds.width <= 0 || bounds.height <= 0 || element.closest('[aria-hidden="true"]')) return false
      const ownStyle = getComputedStyle(element)
      const visuallyClipped = bounds.width <= 1
        && bounds.height <= 1
        && (ownStyle.clip !== 'auto' || ownStyle.clipPath !== 'none')
      if (visuallyClipped) return false
      let current: HTMLElement | null = element
      while (current) {
        const style = getComputedStyle(current)
        if (style.display === 'none' || style.visibility === 'hidden' || Number.parseFloat(style.opacity || '1') <= 0) {
          return false
        }
        current = current.parentElement
      }
      return true
    }
    const selectorSegment = (element: HTMLElement) => {
      if (element.id) return `#${CSS.escape(element.id)}`
      const testId = element.dataset.testid
      if (testId && /^[A-Za-z0-9._-]{1,80}$/.test(testId)) return `[data-testid="${testId}"]`
      const classes = Array.from(element.classList)
        .filter((name) => /^[A-Za-z0-9_-]{1,60}$/.test(name) && !name.startsWith('css-dev-only-'))
        .slice(0, 2)
      const siblings = element.parentElement
        ? Array.from(element.parentElement.children).filter((candidate) => candidate.tagName === element.tagName)
        : []
      const position = siblings.indexOf(element) + 1
      return `${element.tagName.toLowerCase()}${classes.map((name) => `.${name}`).join('')}`
        + `:nth-of-type(${Math.max(1, position)})`
    }
    const selectorFor = (element: HTMLElement) => {
      const segments: string[] = []
      let current: HTMLElement | null = element
      for (let depth = 0; current && current !== document.body && depth < 4; depth += 1) {
        const segment = selectorSegment(current)
        segments.unshift(segment)
        if (segment.startsWith('#') || segment.startsWith('[data-testid=')) break
        current = current.parentElement
      }
      return segments.join(' > ')
    }
    const ownText = (element: HTMLElement) => {
      if (element instanceof HTMLInputElement || element instanceof HTMLTextAreaElement) {
        return element.value || element.placeholder || ''
      }
      if (element instanceof HTMLSelectElement) return element.selectedOptions[0]?.textContent ?? ''
      return Array.from(element.childNodes)
        .filter((node) => node.nodeType === Node.TEXT_NODE)
        .map((node) => node.textContent ?? '')
        .join(' ')
        .replace(/\s+/g, ' ')
        .trim()
    }
    const describedText = (element: HTMLElement) => (element.getAttribute('aria-describedby') ?? '')
      .split(/\s+/)
      .map((id) => document.getElementById(id)?.textContent?.trim() ?? '')
      .filter(Boolean)
      .join(' ')
    const inactiveAncestor = (element: HTMLElement) => Boolean(element.closest(
      'button:disabled, input:disabled, select:disabled, textarea:disabled, [aria-disabled="true"], '
      + '.ant-select-disabled, .ant-picker-disabled, .ant-input-disabled',
    ))
    const textSamples = Array.from(document.body.querySelectorAll<HTMLElement>('*')).flatMap((element) => {
      if (!visible(element) || ['SCRIPT', 'STYLE', 'NOSCRIPT', 'SVG', 'PATH'].includes(element.tagName)) return []
      if (element instanceof HTMLInputElement && element.type === 'hidden') return []
      if (inactiveAncestor(element)) return []
      const text = ownText(element)
      if (!text) return []
      const symbolOnly = /^[\p{P}\p{S}\s]+$/u.test(text)
      if (symbolOnly) return []
      const style = getComputedStyle(element)
      const backgrounds: string[] = []
      let current: HTMLElement | null = element
      for (let depth = 0; current && depth < 20; depth += 1) {
        backgrounds.push(getComputedStyle(current).backgroundColor)
        current = current.parentElement
      }
      const weight = style.fontWeight === 'bold' ? 700 : Number.parseInt(style.fontWeight, 10) || 400
      return [{
        selector: selectorFor(element),
        text,
        color: style.color,
        backgroundLayers: backgrounds,
        backgroundImage: style.backgroundImage,
        fontSizePx: Number.parseFloat(style.fontSize) || 0,
        fontWeight: weight,
        scrollWidth: element.scrollWidth,
        clientWidth: element.clientWidth,
        scrollHeight: element.scrollHeight,
        clientHeight: element.clientHeight,
        title: element.title,
        ariaLabel: [element.getAttribute('aria-label') ?? '', describedText(element)].filter(Boolean).join(' '),
        overflowX: style.overflowX,
        overflowY: style.overflowY,
        textOverflow: style.textOverflow,
        lineClamp: style.webkitLineClamp,
      }]
    })
    const interactiveSelector = 'button, a[href], input, select, textarea, summary, [role="button"]'
    const touchSamples = Array.from(document.querySelectorAll<HTMLElement>(interactiveSelector)).flatMap((element) => {
      if (!visible(element)) return []
      if (element instanceof HTMLInputElement && element.type === 'hidden') return []
      const disabled = ('disabled' in element && Boolean((element as HTMLButtonElement).disabled))
        || element.getAttribute('aria-disabled') === 'true'
      if (disabled) return []
      const bounds = element.getBoundingClientRect()
      const style = getComputedStyle(element)
      const input = element instanceof HTMLInputElement ? element : null
      const labels = input?.labels ? Array.from(input.labels) : []
      const labelBounds = labels.map((label) => label.getBoundingClientRect())
      const labelWidth = labelBounds.length > 0 ? Math.max(...labelBounds.map((box) => box.width)) : 0
      const labelHeight = labelBounds.length > 0 ? Math.max(...labelBounds.map((box) => box.height)) : 0
      const labelText = labels.map((label) => label.textContent?.trim() ?? '').filter(Boolean).join(' ')
      const compositeTarget = input?.closest<HTMLElement>(
        '.ant-input-affix-wrapper, .ant-input-number, .ant-picker, .ant-select-selector',
      ) ?? null
      const compositeBounds = compositeTarget?.getBoundingClientRect()
      const descendantAccessibleName = Array.from(element.querySelectorAll<HTMLElement>('[aria-label]'))
        .map((candidate) => candidate.getAttribute('aria-label')?.trim() ?? '')
        .find(Boolean) ?? ''
      const accessibleName = [
        element.getAttribute('aria-label') ?? '',
        labelText,
        element.getAttribute('title') ?? '',
        descendantAccessibleName,
        element.innerText,
        input?.value ?? '',
        input?.placeholder ?? '',
      ].find((value) => value.trim()) ?? ''
      const proseAncestor = element.closest('p, li')
      return [{
        selector: selectorFor(element),
        tag: element.tagName,
        width: bounds.width,
        height: bounds.height,
        compositeWidth: compositeBounds?.width ?? 0,
        compositeHeight: compositeBounds?.height ?? 0,
        display: style.display,
        accessibleName,
        parentTag: proseAncestor?.tagName ?? element.parentElement?.tagName ?? '',
        hasGraphic: Boolean(element.querySelector('svg, img, .anticon')),
        controlType: input?.type ?? '',
        labelWidth,
        labelHeight,
        disabled,
      }]
    })
    const animations = document.getAnimations({ subtree: true }).map((animation) => {
      const target = animation.effect instanceof KeyframeEffect && animation.effect.target instanceof HTMLElement
        ? animation.effect.target
        : null
      const timing = animation.effect?.getComputedTiming()
      return {
        target: target ? selectorFor(target) : '<document>',
        playState: animation.playState,
        durationMs: typeof timing?.duration === 'number' ? timing.duration : 0,
        name: animation instanceof CSSAnimation ? animation.animationName : animation.id || '<transition>',
      }
    })
    return {
      viewportWidth: document.documentElement.clientWidth,
      documentWidth: Math.max(document.documentElement.scrollWidth, document.body?.scrollWidth ?? 0),
      reducedMotionMatches: matchMedia('(prefers-reduced-motion: reduce)').matches,
      animations,
      textSamples,
      touchSamples,
    }
  })
}

function clippingAssessment(sample: BrowserTextSample) {
  const horizontalCandidate = sample.scrollWidth > sample.clientWidth + 1
    && (['hidden', 'clip'].includes(sample.overflowX) || sample.textOverflow === 'ellipsis')
  const verticalCandidate = sample.scrollHeight > sample.clientHeight + 1
    && (['hidden', 'clip'].includes(sample.overflowY) || sample.lineClamp !== 'none')
  if (!horizontalCandidate && !verticalCandidate) return null
  return textClipViolation(sample)
}

if (!process.env.VITEST) {
  test('Stage 6 22 路由的 200% 与触控无障碍合同', async ({ browser }) => {
    const startedAt = Date.now()
    if (existsSync(outputDirectory) && readdirSync(outputDirectory).length > 0) {
      throw new Error(`Stage 6 无障碍证据目录必须全新且为空: ${outputDirectory}`)
    }
    mkdirSync(screenshotsDirectory, { recursive: true })

    const computedRecords: Array<Record<string, unknown>> = []
    const violations: AccessibilityViolation[] = []
    const explicitExceptions: AccessibilityException[] = []
    const screenshotDetails: Array<FileBinding & { profile: string; route: string }> = []
    const runtimeReadiness: RuntimeReadinessObservation[] = []
    const businessWrites: Array<{ profile: string; route: string; method: string; path: string }> = []
    const aiReadCommands: Array<{ profile: string; route: string; method: string; path: string }> = []
    const successfulApiKeys = new Set<string>()
    const successfulApiPaths = new Set<string>()
    const observedApiRequestFailures: Array<{
      profile: string
      route: string
      method: string
      path: string
      failureText: string
    }> = []
    const toleratedApiRequestFailures: typeof observedApiRequestFailures = []

    for (const profile of STAGE6_ACCESSIBILITY_PROFILES) {
      const context = await browser.newContext({
        baseURL: visualBaseURL,
        viewport: { width: profile.width, height: profile.height },
        deviceScaleFactor: 1,
        hasTouch: profile.touch,
        reducedMotion: 'reduce',
      })
      try {
        await login(context)
        runtimeReadiness.push(await observeReadiness(context, profile.name))

        for (const route of STAGE6_ACCESSIBILITY_ROUTES) {
          const page = await context.newPage()
          try {
            page.on('request', (request) => {
              const url = new URL(request.url())
              const method = request.method()
              if (!url.pathname.startsWith('/api/') || !unsafeMethods.has(method)) return
              if (url.pathname.startsWith('/api/ai/')) {
                aiReadCommands.push({ profile: profile.name, route: route.path, method, path: url.pathname })
                return
              }
              if (url.pathname.startsWith('/api/auth/') || url.pathname.startsWith('/api/security/')) return
              businessWrites.push({ profile: profile.name, route: route.path, method, path: url.pathname })
            })
            page.on('response', (response) => {
              const url = new URL(response.url())
              if (url.pathname.startsWith('/api/') && response.status() >= 200 && response.status() < 400) {
                successfulApiKeys.add(`${response.request().method()} ${url.pathname}`)
                successfulApiPaths.add(url.pathname)
              }
              if (url.pathname.startsWith('/api/') && response.status() >= 400) {
                violations.push({
                  profile: profile.name,
                  route: route.path,
                  kind: 'API_RESPONSE',
                  detail: `${response.request().method()} ${url.pathname} returned HTTP ${response.status()}`,
                })
              }
            })
            page.on('requestfailed', (request) => {
              const url = new URL(request.url())
              if (!url.pathname.startsWith('/api/')) return
              observedApiRequestFailures.push({
                profile: profile.name,
                route: route.path,
                method: request.method(),
                path: url.pathname,
                failureText: request.failure()?.errorText ?? 'unknown request failure',
              })
            })
            page.on('console', (message) => {
              if (message.type() !== 'error') return
              const source = message.location().url ? new URL(message.location().url).pathname : ''
              const knownCompletedStreamClose = source.startsWith('/api/')
                && successfulApiPaths.has(source)
                && /Failed to load resource: net::ERR_(?:ABORTED|NO_BUFFER_SPACE)/.test(message.text())
              if (knownCompletedStreamClose) return
              violations.push({
                profile: profile.name,
                route: route.path,
                kind: 'CONSOLE_ERROR',
                detail: sanitizeDiagnostic(message.text()),
              })
            })
            page.on('pageerror', (error) => {
              violations.push({
                profile: profile.name,
                route: route.path,
                kind: 'PAGE_ERROR',
                detail: sanitizeDiagnostic(error),
              })
            })

            await page.goto(route.path, { waitUntil: 'domcontentloaded' })
            await page.waitForLoadState('networkidle')
            await expect(page.locator('.ant-spin-spinning')).toHaveCount(0, { timeout: 60_000 })
            await expect(page.getByRole('heading', { name: route.title, exact: true }).first()).toBeVisible()
            await page.evaluate(() => document.fonts.ready)

            const focus = await focusFirstVisibleInteractive(page)
            if (!focus) {
              violations.push({
                profile: profile.name,
                route: route.path,
                kind: 'KEYBOARD_REACHABILITY',
                detail: 'Tab did not reach a visible interactive element within 16 attempts',
              })
            } else if (!hasVisibleFocusIndicator(focus)) {
              violations.push({
                profile: profile.name,
                route: route.path,
                kind: 'FOCUS_VISIBLE',
                selector: focus.selector,
                detail: `${focus.selector} has no visible :focus-visible outline or shadow`,
              })
            }

            await page.waitForTimeout(500)
            const snapshot = await collectRouteSnapshot(page)
            if (snapshot.documentWidth > snapshot.viewportWidth + 1) {
              violations.push({
                profile: profile.name,
                route: route.path,
                kind: 'HORIZONTAL_OVERFLOW',
                detail: `document=${snapshot.documentWidth}px viewport=${snapshot.viewportWidth}px`,
              })
            }
            if (!snapshot.reducedMotionMatches) {
              violations.push({
                profile: profile.name,
                route: route.path,
                kind: 'REDUCED_MOTION_MEDIA',
                detail: 'prefers-reduced-motion did not match reduce',
              })
            }
            for (const detail of findActiveAnimationViolations(snapshot.animations)) {
              violations.push({
                profile: profile.name,
                route: route.path,
                kind: 'ACTIVE_ANIMATION',
                detail,
              })
            }

            const contrastRecords = snapshot.textSamples.map((sample) => {
              const analysis = analyzeContrastSample(sample)
              if (analysis.violation) {
                violations.push({
                  profile: profile.name,
                  route: route.path,
                  kind: 'TEXT_CONTRAST',
                  selector: sample.selector,
                  detail: analysis.violation,
                })
              }
              const clipping = clippingAssessment(sample)
              if (clipping) {
                violations.push({
                  profile: profile.name,
                  route: route.path,
                  kind: 'TEXT_CLIPPING',
                  selector: sample.selector,
                  detail: clipping,
                })
              }
              return {
                selector: sample.selector,
                ...textMetadata(sample.text),
                color: sample.color,
                backgroundLayers: sample.backgroundLayers,
                backgroundImage: sample.backgroundImage,
                fontSizePx: sample.fontSizePx,
                fontWeight: sample.fontWeight,
                ratio: analysis.ratio,
                threshold: analysis.threshold,
                geometry: {
                  scrollWidth: sample.scrollWidth,
                  clientWidth: sample.clientWidth,
                  scrollHeight: sample.scrollHeight,
                  clientHeight: sample.clientHeight,
                },
                fullTextAlternative: Boolean(sample.title.trim() || sample.ariaLabel.trim()),
              }
            })

            const touchRecords = snapshot.touchSamples.map((sample) => {
              const assessment = touchTargetAssessment(sample)
              if (profile.touch && assessment.violation) {
                violations.push({
                  profile: profile.name,
                  route: route.path,
                  kind: 'TOUCH_TARGET',
                  selector: sample.selector,
                  detail: assessment.violation,
                })
              }
              if (profile.touch && assessment.exception) {
                explicitExceptions.push({
                  profile: profile.name,
                  route: route.path,
                  kind: 'INLINE_PROSE_LINK',
                  selector: sample.selector,
                  detail: assessment.exception,
                })
              }
              return {
                selector: sample.selector,
                tag: sample.tag,
                width: sample.width,
                height: sample.height,
                compositeWidth: sample.compositeWidth ?? 0,
                compositeHeight: sample.compositeHeight ?? 0,
                labelWidth: sample.labelWidth,
                labelHeight: sample.labelHeight,
                controlType: sample.controlType,
                display: sample.display,
                hasGraphic: sample.hasGraphic,
                accessibleNamePresent: Boolean(normalizedText(sample.accessibleName)),
                accessibleNameSha256: sha256Hex(normalizedText(sample.accessibleName)),
                assessment,
              }
            })

            computedRecords.push({
              profile: profile.name,
              route: route.path,
              capturedAt: new Date().toISOString(),
              layout: {
                viewportWidth: snapshot.viewportWidth,
                documentWidth: snapshot.documentWidth,
              },
              focus,
              reducedMotionMatches: snapshot.reducedMotionMatches,
              animations: snapshot.animations,
              contrast: {
                inspectedCount: contrastRecords.length,
                minimumRatio: contrastRecords.length > 0
                  ? Math.min(...contrastRecords.map((record) => record.ratio))
                  : null,
                records: contrastRecords,
              },
              textClipping: {
                inspectedCount: snapshot.textSamples.length,
                candidateCount: snapshot.textSamples.filter((sample) => clippingAssessment(sample) !== null).length,
              },
              touchTargets: {
                applicable: profile.touch,
                inspectedCount: profile.touch ? touchRecords.length : 0,
                records: profile.touch ? touchRecords : [],
              },
            })

            if (representativeRoutes.has(route.path)) {
              await page.evaluate(() => window.scrollTo(0, 0))
              const file = `screenshots/${route.slug}-${profile.name}.png`
              const path = resolve(outputDirectory, file)
              await page.screenshot({ path, fullPage: false, animations: 'disabled', caret: 'hide' })
              screenshotDetails.push({
                ...fileBinding(file, path),
                profile: profile.name,
                route: route.path,
              })
            }
          } catch (error) {
            violations.push({
              profile: profile.name,
              route: route.path,
              kind: 'ROUTE_EXECUTION',
              detail: sanitizeDiagnostic(error),
            })
          } finally {
            await page.close()
          }
        }
      } finally {
        await context.close()
      }
    }

    for (const failure of observedApiRequestFailures) {
      const previouslySuccessful = successfulApiKeys.has(`${failure.method} ${failure.path}`)
      const completedStreamClose = previouslySuccessful
        && ['net::ERR_ABORTED', 'net::ERR_NO_BUFFER_SPACE'].includes(failure.failureText)
      if (completedStreamClose) {
        toleratedApiRequestFailures.push(failure)
      } else {
        violations.push({
          profile: failure.profile,
          route: failure.route,
          kind: 'API_REQUEST_FAILED',
          detail: `${failure.method} ${failure.path}: ${sanitizeDiagnostic(failure.failureText)}`,
        })
      }
    }

    writeFileSync(computedRecordsPath, `${JSON.stringify(computedRecords, null, 2)}\n`, 'utf8')
    writeFileSync(violationsPath, `${JSON.stringify(violations, null, 2)}\n`, 'utf8')
    writeFileSync(exceptionsPath, `${JSON.stringify(explicitExceptions, null, 2)}\n`, 'utf8')

    const sourceBindings = collectSourceBindings()
    const artifactBindings = [computedRecordsPath, violationsPath, exceptionsPath]
      .map((path) => fileBinding(relative(outputDirectory, path).replaceAll('\\', '/'), path))
    const manifest = {
      generatedAt: new Date().toISOString(),
      startedAt: new Date(startedAt).toISOString(),
      baseURL: visualBaseURL,
      profiles: STAGE6_ACCESSIBILITY_PROFILES,
      routes: STAGE6_ACCESSIBILITY_ROUTES,
      routeProfileCount: computedRecords.length,
      representativeScreenshotCount: screenshotDetails.length,
      computedRecordsFile: relative(outputDirectory, computedRecordsPath).replaceAll('\\', '/'),
      violationsFile: relative(outputDirectory, violationsPath).replaceAll('\\', '/'),
      exceptionsFile: relative(outputDirectory, exceptionsPath).replaceAll('\\', '/'),
      runtimeReadiness,
      businessWrites,
      aiReadCommands,
      toleratedApiRequestFailures,
      sourceBindings,
      screenshotDetails,
      artifactBindings,
      violationCount: violations.length,
      exceptionCount: explicitExceptions.length,
      persistedBindingViolations: [] as string[],
    }
    writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')

    const persisted = JSON.parse(readFileSync(manifestPath, 'utf8')) as typeof manifest
    const persistedBindingViolations = [
      ...verifyBindings(persisted.sourceBindings, repositoryDirectory, 'sourceBindings'),
      ...verifyBindings(persisted.screenshotDetails, outputDirectory, 'screenshotDetails'),
      ...verifyBindings(persisted.artifactBindings, outputDirectory, 'artifactBindings'),
    ]
    const verifiedManifest = { ...persisted, persistedBindingViolations }
    writeFileSync(manifestPath, `${JSON.stringify(verifiedManifest, null, 2)}\n`, 'utf8')

    const rereadManifest = JSON.parse(readFileSync(manifestPath, 'utf8')) as typeof verifiedManifest
    const rereadComputed = JSON.parse(readFileSync(computedRecordsPath, 'utf8')) as typeof computedRecords
    const rereadViolations = JSON.parse(readFileSync(violationsPath, 'utf8')) as typeof violations
    const rereadExceptions = JSON.parse(readFileSync(exceptionsPath, 'utf8')) as typeof explicitExceptions
    const recomputedBindingViolations = [
      ...verifyBindings(rereadManifest.sourceBindings, repositoryDirectory, 'sourceBindings'),
      ...verifyBindings(rereadManifest.screenshotDetails, outputDirectory, 'screenshotDetails'),
      ...verifyBindings(rereadManifest.artifactBindings, outputDirectory, 'artifactBindings'),
    ]

    expect(rereadManifest.routeProfileCount).toBe(
      STAGE6_ACCESSIBILITY_ROUTES.length * STAGE6_ACCESSIBILITY_PROFILES.length,
    )
    expect(rereadComputed).toHaveLength(
      STAGE6_ACCESSIBILITY_ROUTES.length * STAGE6_ACCESSIBILITY_PROFILES.length,
    )
    expect(rereadManifest.representativeScreenshotCount).toBe(representativeRoutes.size * 2)
    expect(rereadManifest.screenshotDetails).toHaveLength(representativeRoutes.size * 2)
    expect(rereadManifest.runtimeReadiness).toHaveLength(STAGE6_ACCESSIBILITY_PROFILES.length)
    expect(rereadManifest.runtimeReadiness.every((item) => (
      item.providerAlias === 'fake' && item.writeExecutionEnabled === false
    ))).toBe(true)
    expect(rereadManifest.businessWrites, 'Stage 6 accessibility browsing must remain read-only').toEqual([])
    expect(rereadExceptions.every((item) => item.kind === 'INLINE_PROSE_LINK')).toBe(true)
    expect(rereadManifest.persistedBindingViolations).toEqual([])
    expect(recomputedBindingViolations).toEqual([])
    expect(rereadViolations, 'Stage 6 accessibility violations must be empty').toEqual([])
  })
}
