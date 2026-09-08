import { existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { createHash, randomUUID } from 'node:crypto'
import { relative, resolve } from 'node:path'
import { setTimeout as delay } from 'node:timers/promises'
import { expect, test, type BrowserContext, type Page, type Request } from '@playwright/test'
import {
  captureFreshAssistantTurnEvidence,
  validateVisualManifestEvidence,
  type FreshAssistantTurnEvidence,
  type VisualContentStateEvidence,
  type VisualDesignSystemGalleryCaptureEvidence,
  type VisualEvidenceSource,
} from '../src/utils/assistant-visual-evidence'
import {
  requireVisualCredentials,
  requireVisualReferenceInstant,
  sanitizeDiagnostic,
  visualBackendEnv,
  visualBackendURL,
  visualBaseURL,
  visualDirectory,
  visualManifestPath,
} from './live-visual-settings'
import { requireAiLiveIsolation } from './ai-live-isolation'
import {
  isVisualLoginServiceNotReady,
  retryVisualLogin,
  type VisualLoginAttemptResult,
} from './live-visual-login-retry'
import {
  designSystemPanelSelector,
  designSystemEvidenceContract,
  designSystemGalleryTarget,
  noticePrototypePoints,
  prototypeCaptureTargets,
  prototypeContracts,
} from './live-visual-contracts'

const routes = [
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
  { path: '/notices/create', title: '公告 AI 起草', slug: 'notice-create', region: '公告 AI 起草工作台' },
  { path: '/ai/knowledge', title: '知识管理', slug: 'ai-knowledge' },
  { path: '/ai/risks', title: '智能风险中心', slug: 'ai-risks' },
  { path: '/ai/approvals', title: '待审批', slug: 'ai-approvals' },
  { path: '/ai/audit', title: '运行审计', slug: 'ai-audit' },
] as const

const viewports = [
  { name: '1920x1080', width: 1920, height: 1080 },
  { name: '1366x768', width: 1366, height: 768 },
  { name: '1586x992', width: 1586, height: 992 },
  { name: '1536x1024', width: 1536, height: 1024 },
  { name: '1505x1045', width: 1505, height: 1045 },
  { name: '390x844', width: 390, height: 844 },
] as const

const repositoryDirectory = resolve(visualDirectory, '..', '..', '..')
const prototypeDirectory = resolve(repositoryDirectory, 'design', 'ai-prototypes')
const aiLiveIsolation = requireAiLiveIsolation(
  visualBackendEnv,
  { requireOutputBoundRedisPrefix: true },
)
const visualReferenceInstant = requireVisualReferenceInstant()
const visualBusinessTimeZone = 'Asia/Shanghai'
const assistantEvidenceViewports = ['1586x992', '1366x768', '390x844'] as const
const knowledgeSourceLabel = '宿舍维修管理制度'
const knowledgeQuestion = '维修申请的处理时限是什么？'
const repairCaptureDescription = '插座冒烟并伴随火花，已切断相关电源；夜间用电负载较高，请优先检查线路和空气开关。'
const requiredContentStateIds = [
  'repair-populated-triage',
  'notice-generated-draft',
  'approval-pending-proposal',
  'risk-populated-detail',
  'audit-populated-run',
] as const
const assistantCompleteEvidenceViewports = ['1586x992', '390x844'] as const
const requiredAssistantEvidenceStates = [
  'streaming',
  'succeeded',
  'citation-denied',
  'low-confidence',
  'no-grounded',
  'failed',
  'canceled',
  'timed_out',
  'history',
  'empty',
  'input',
] as const
const requiredAssistantPrototypeStates = ['streaming'] as const
const forbiddenAssistantPrototypeStates = ['no-permission'] as const
const assistantRuntimeScreenshotKinds = [
  'streaming',
  'open',
  'history',
  'empty',
  'input',
] as const
const assistantFixtureScreenshotKinds = [
  'safety-states',
  'failed',
  'canceled',
  'timed-out',
] as const
const expectedAssistantScreenshotFiles = [
  'live-assistant-open-1366x768.png',
  ...assistantCompleteEvidenceViewports.flatMap((viewport) => (
    [
      ...assistantRuntimeScreenshotKinds.map((kind) => `live-assistant-${kind}-${viewport}.png`),
      ...assistantFixtureScreenshotKinds.map((kind) => `fixture-assistant-${kind}-${viewport}.png`),
    ]
  )),
] as const

const backendPom = readFileSync(resolve(repositoryDirectory, 'backend', 'pom.xml'), 'utf8')
const springBootVersion = backendPom.match(
  /<parent>[\s\S]*?<artifactId>spring-boot-starter-parent<\/artifactId>[\s\S]*?<version>([^<]+)<\/version>/,
)?.[1]?.trim() ?? 'unknown'
const visualLoginTimeoutMs = 180_000
const visualLoginRetryIntervalMs = 250
const visualLoginRequestTimeoutMs = 3_000
const assistantTerminalEvidenceTimeoutMs = 35_000
const unsafeMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

interface RuntimeIssue {
  viewport: string
  route: string
  kind: string
  detail: string
  status?: number
}

interface ObservedApiRequestFailure {
  issue: RuntimeIssue
  method: string
  path: string
  failureText: string
}

interface LayoutCheck {
  viewport: string
  route: string
  viewportWidth: number
  documentWidth: number
  overflowBy: number
  overflowElements: string[]
}

interface ShellCheck {
  viewport: string
  route: string
  sidebarWidth: number
  brand: string
  brandMark: string
  brandHomeIconCount: number
  aiMenuLabels: string[]
  selectedMenuLabel: string
}

interface CanvasCheck {
  viewport: string
  canvasCount: number
  renderedCanvasCount: number
}

interface BusinessDateCheck {
  viewport: string
  route: string
  surface: string
  text: string
  dates: string[]
  referenceInstant: string
  referenceDate: string
  timeZone: string
}

interface DashboardMobileGeometryCheck {
  viewport: '390x844'
  route: '/'
  pendingTop: number
  documentHeight: number
  viewportWidth: number
  documentWidth: number
  riskTop: number
  trendTop: number
  trendLegendCount: number
  trendLegendTexts: string[]
  trendLegendsOverlap: boolean
  vacancyDuplicateVisible: boolean
  sparseStateVisible: boolean
  degradedStateVisible: boolean
}

interface FileBinding {
  file: string
  bytes: number
  sha256: string
  mtimeUtc: string
}

interface ObservedBusinessWrite {
  viewport: string
  route: string
  source: 'page' | 'navigation' | 'service-worker' | 'unknown'
  method: string
  path: string
}

interface ObservedServiceWorker {
  viewport: string
  url: string
}

interface BackendRuntimeReadiness {
  masterEnabled: boolean
  providerAlias: string
  streamingEnabled: boolean
  writeExecutionEnabled: boolean
  controls: Record<string, string>
}

interface RuntimeReadinessObservation {
  viewport: string
  phase: string
  observedAt: string
  source: 'GET /api/ai/operations/readiness'
  masterEnabled: boolean
  providerAlias: string
  streamingEnabled: boolean
  writeExecutionEnabled: boolean
  controls: Record<string, string>
}

interface BindingVerificationSection {
  count: number
  violations: string[]
}

interface PersistedBindingVerification {
  verifiedAt: string
  sourceBindings: BindingVerificationSection
  sourceBindingsAtEnd: BindingVerificationSection
  prototypeBindings: BindingVerificationSection
  prototypeBindingsAtEnd: BindingVerificationSection
  screenshotDetails: BindingVerificationSection
  assistantScreenshotDetails: BindingVerificationSection
  prototypeCaptures: BindingVerificationSection
  designSystemGalleryCapture: BindingVerificationSection
  pngFileSet: BindingVerificationSection
}

interface PrototypeCaptureRecord extends FileBinding {
  prototype: string
  contractSurface: string
  route: string
  viewport: string
  contentStateIds: string[]
}

interface DesignSystemCheck {
  route: string
  viewport: string
  tokens: Record<string, string>
  computedStyles: Record<string, string>
  stateSemantics: Record<string, boolean>
  sharedComponents: Record<string, boolean>
}

type JsonObject = Record<string, unknown>

interface ApiEnvelope<T> {
  code?: number
  message?: string
  data?: T
}

interface HttpResponse {
  status(): number
  text(): Promise<string>
}

function formatDateInTimeZone(instant: Date, timeZone: string) {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(instant)
  const values = Object.fromEntries(parts.map((part) => [part.type, part.value]))
  return `${values.year}-${values.month}-${values.day}`
}

const visualReferenceBusinessDate = formatDateInTimeZone(
  visualReferenceInstant,
  visualBusinessTimeZone,
)

function businessDateCheck(input: Pick<BusinessDateCheck, 'viewport' | 'route' | 'surface' | 'text'>) {
  const dates = input.text.match(/\b\d{4}-\d{2}-\d{2}\b/g) ?? []
  expect(dates.length, `${input.viewport} ${input.surface} 缺少可验证业务日期`).toBeGreaterThan(0)
  expect(
    dates.filter((date) => date > visualReferenceBusinessDate),
    `${input.viewport} ${input.surface} 可见业务日期晚于正式 reference instant`,
  ).toEqual([])
  return {
    ...input,
    dates,
    referenceInstant: visualReferenceInstant.toISOString(),
    referenceDate: visualReferenceBusinessDate,
    timeZone: visualBusinessTimeZone,
  } satisfies BusinessDateCheck
}

function pngDimensions(path: string) {
  const bytes = readFileSync(path)
  if (bytes.length < 24 || bytes.subarray(1, 4).toString('ascii') !== 'PNG') {
    throw new Error(`${path} 不是有效 PNG`)
  }
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) }
}

function fileBinding(file: string, path: string): FileBinding {
  const bytes = readFileSync(path)
  const stats = statSync(path)
  return {
    file,
    bytes: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex').toUpperCase(),
    mtimeUtc: stats.mtime.toISOString(),
  }
}

function verifyBindingSection(
  label: string,
  bindings: Array<Pick<FileBinding, 'file' | 'bytes' | 'sha256'> & Partial<Pick<FileBinding, 'mtimeUtc'>>>,
  baseDirectory: string,
): BindingVerificationSection {
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
    const actual = fileBinding(binding.file, path)
    if (actual.bytes !== binding.bytes) {
      violations.push(`${label}: bytes ${binding.file} expected=${binding.bytes} actual=${actual.bytes}`)
    }
    if (actual.sha256 !== binding.sha256) {
      violations.push(`${label}: sha256 ${binding.file} expected=${binding.sha256} actual=${actual.sha256}`)
    }
    if (binding.mtimeUtc && actual.mtimeUtc !== binding.mtimeUtc) {
      violations.push(`${label}: mtime ${binding.file} expected=${binding.mtimeUtc} actual=${actual.mtimeUtc}`)
    }
  }
  return { count: bindings.length, violations }
}

function compareBindingSnapshots(label: string, before: FileBinding[], after: FileBinding[]) {
  const violations: string[] = []
  const beforeByFile = new Map(before.map((binding) => [binding.file, binding]))
  const afterByFile = new Map(after.map((binding) => [binding.file, binding]))
  for (const file of new Set([...beforeByFile.keys(), ...afterByFile.keys()])) {
    const start = beforeByFile.get(file)
    const end = afterByFile.get(file)
    if (!start) {
      violations.push(`${label}: added during run ${file}`)
      continue
    }
    if (!end) {
      violations.push(`${label}: removed during run ${file}`)
      continue
    }
    if (start.bytes !== end.bytes || start.sha256 !== end.sha256 || start.mtimeUtc !== end.mtimeUtc) {
      violations.push(`${label}: changed during run ${file}`)
    }
  }
  return violations
}

function verifyPersistedBindings(manifest: {
  sourceBindings: FileBinding[]
  sourceBindingsAtEnd: FileBinding[]
  prototypeBindings: FileBinding[]
  prototypeBindingsAtEnd: FileBinding[]
  screenshotDetails: Array<FileBinding & { route: string; viewport: string }>
  assistantScreenshotDetails: Array<FileBinding & { route: string; viewport: string }>
  prototypeCaptures: PrototypeCaptureRecord[]
  designSystemGalleryCapture: (VisualDesignSystemGalleryCaptureEvidence & Pick<FileBinding, 'mtimeUtc'>) | null
}) {
  const sourceBindings = verifyBindingSection('sourceBindings', manifest.sourceBindings, repositoryDirectory)
  const sourceBindingsAtEnd = verifyBindingSection(
    'sourceBindingsAtEnd',
    manifest.sourceBindingsAtEnd,
    repositoryDirectory,
  )
  const prototypeBindings = verifyBindingSection(
    'prototypeBindings',
    manifest.prototypeBindings,
    prototypeDirectory,
  )
  const prototypeBindingsAtEnd = verifyBindingSection(
    'prototypeBindingsAtEnd',
    manifest.prototypeBindingsAtEnd,
    prototypeDirectory,
  )
  const screenshotDetails = verifyBindingSection('screenshotDetails', manifest.screenshotDetails, visualDirectory)
  const assistantScreenshotDetails = verifyBindingSection(
    'assistantScreenshotDetails',
    manifest.assistantScreenshotDetails,
    visualDirectory,
  )
  const prototypeCaptures = verifyBindingSection('prototypeCaptures', manifest.prototypeCaptures, visualDirectory)
  const designSystemGalleryCapture = verifyBindingSection(
    'designSystemGalleryCapture',
    manifest.designSystemGalleryCapture ? [manifest.designSystemGalleryCapture] : [],
    visualDirectory,
  )
  const expectedPngFiles = new Set([
    ...manifest.screenshotDetails.map(({ file }) => file),
    ...manifest.assistantScreenshotDetails.map(({ file }) => file),
    ...manifest.prototypeCaptures.map(({ file }) => file),
    ...(manifest.designSystemGalleryCapture ? [manifest.designSystemGalleryCapture.file] : []),
  ])
  const actualPngFiles = collectFiles(visualDirectory)
    .filter((path) => path.toLowerCase().endsWith('.png'))
    .map((path) => relative(visualDirectory, path).replaceAll('\\', '/'))
    .sort((left, right) => left.localeCompare(right))
  const pngViolations = [
    ...[...expectedPngFiles].filter((file) => !actualPngFiles.includes(file)).map((file) => `pngFileSet: missing ${file}`),
    ...actualPngFiles.filter((file) => !expectedPngFiles.has(file)).map((file) => `pngFileSet: unbound ${file}`),
  ]
  return {
    sourceBindings,
    sourceBindingsAtEnd,
    prototypeBindings,
    prototypeBindingsAtEnd,
    screenshotDetails,
    assistantScreenshotDetails,
    prototypeCaptures,
    designSystemGalleryCapture,
    pngFileSet: { count: actualPngFiles.length, violations: pngViolations },
  }
}

function collectFiles(directory: string): string[] {
  return readdirSync(directory, { withFileTypes: true }).flatMap((entry) => {
    const path = resolve(directory, entry.name)
    return entry.isDirectory() ? collectFiles(path) : [path]
  })
}

function collectSourceBindings() {
  const sourceFiles = [
    ...collectFiles(resolve(repositoryDirectory, 'frontend', 'src')),
    ...collectFiles(resolve(repositoryDirectory, 'backend', 'src')),
  ].filter((path) => /\.(?:css|html|java|json|jsonl|md|sql|ts|vue|xml|ya?ml)$/i.test(path))
  const harnessFiles = [
    resolve(repositoryDirectory, 'frontend', 'index.html'),
    resolve(repositoryDirectory, 'frontend', 'vite.config.ts'),
    resolve(repositoryDirectory, 'frontend', 'tsconfig.json'),
    resolve(repositoryDirectory, 'frontend', 'tsconfig.app.json'),
    resolve(repositoryDirectory, 'frontend', 'tsconfig.node.json'),
    resolve(repositoryDirectory, 'frontend', 'e2e', 'live-visual.spec.ts'),
    resolve(repositoryDirectory, 'frontend', 'e2e', 'live-visual-contracts.ts'),
    resolve(repositoryDirectory, 'frontend', 'e2e', 'live-visual-settings.ts'),
    resolve(repositoryDirectory, 'frontend', 'e2e', 'live-visual-login-retry.ts'),
    resolve(repositoryDirectory, 'frontend', 'e2e', 'ai-live-isolation.ts'),
    resolve(repositoryDirectory, 'frontend', 'e2e', 'ai-live-global-setup.ts'),
    resolve(repositoryDirectory, 'frontend', 'e2e', 'ai-live-audit-preflight.ts'),
    resolve(repositoryDirectory, 'frontend', 'e2e', 'ai-live-runtime-switch-preflight.ts'),
    resolve(repositoryDirectory, 'frontend', 'e2e', 'stage6-accessibility-live.spec.ts'),
    resolve(repositoryDirectory, 'frontend', 'e2e', 'stage6-risk-live-geometry.spec.ts'),
    resolve(repositoryDirectory, 'frontend', 'playwright.visual.config.ts'),
    resolve(repositoryDirectory, 'frontend', 'playwright.stage6-accessibility.config.ts'),
    resolve(repositoryDirectory, 'frontend', 'playwright.stage6-risk-geometry.config.ts'),
    resolve(repositoryDirectory, 'frontend', 'package.json'),
    resolve(repositoryDirectory, 'frontend', 'package-lock.json'),
    resolve(repositoryDirectory, 'backend', 'pom.xml'),
    resolve(repositoryDirectory, '.planning', '20260727-ui-prototype-texture-reassessment', 'scripts', 'compare_visuals.py'),
  ].filter(existsSync)

  return Array.from(new Set([...sourceFiles, ...harnessFiles]))
    .sort((left, right) => left.localeCompare(right))
    .map((path) => fileBinding(relative(repositoryDirectory, path).replaceAll('\\', '/'), path))
}

function collectPrototypeBindings() {
  return prototypeContracts
    .map(({ file }) => fileBinding(file, resolve(prototypeDirectory, file)))
    .sort((left, right) => left.file.localeCompare(right.file))
}

async function gotoVisualRoute(page: Page, path: string) {
  // Edge can transiently suspend a loopback navigation while the long visual
  // run creates/closes pages. Retry only that browser transport error; a
  // second failure still bubbles up and is recorded as a real run error.
  for (let attempt = 0; attempt < 3; attempt += 1) {
    try {
      return await page.goto(path, { waitUntil: 'domcontentloaded' })
    } catch (error) {
      const detail = String(error)
      if (!/ERR_NETWORK_IO_SUSPENDED/i.test(detail) || attempt === 2) throw error
      await delay(250 * (attempt + 1))
    }
  }
  return null
}

async function prepareGroundedKnowledge(page: Page, marker: string) {
  await page.goto('/ai/knowledge')
  await expect(page.getByRole('heading', { name: '知识管理', exact: true })).toBeVisible()

  const create = page.getByRole('region', { name: '创建知识来源' })
  await create.getByRole('textbox', { name: '来源名称' }).fill(knowledgeSourceLabel)
  await create.getByRole('textbox', { name: '知识来源权限' }).fill('ai:knowledge:read')
  const sourceResponsePromise = page.waitForResponse((response) => (
    new URL(response.url()).pathname === '/api/ai/knowledge/sources'
      && response.request().method() === 'POST'
  ))
  await create.getByRole('button', { name: '创建来源' }).click()
  expect((await sourceResponsePromise).status()).toBe(201)

  const ingest = page.locator('.ai-knowledge-ingest')
  await ingest.getByRole('textbox', { name: '文档标题' }).fill('维修申请处理时限')
  await ingest.getByRole('textbox', { name: '外部键' }).fill(`visual-${marker.toLowerCase()}`)
  await ingest.getByRole('textbox', { name: '版本' }).fill('v1')
  await ingest.getByRole('textbox', { name: '纯文本正文' }).fill(
    '宿舍普通维修应在受理后 2 个工作日内完成；遇到停电、漏水等紧急情况，应在 4 小时内到场处理；特殊情况最长不超过 5 个工作日，并记录延期原因。',
  )
  await ingest.getByRole('button', { name: '创建版本并摄取' }).click()

  const version = page.getByRole('region', { name: '知识版本与摄取任务' })
  await expect(version).toBeVisible({ timeout: 60_000 })
  await expect.poll(async () => {
    await version.getByRole('button', { name: '刷新任务' }).click()
    return version.textContent()
  }, { timeout: 60_000 }).toMatch(/SUCCEEDED[\s\S]*READY|READY[\s\S]*SUCCEEDED/)
  await version.getByRole('button', { name: '激活版本' }).click()
  await expect(version).toContainText('ACTIVE')
}

type AssistantDeterministicFixture = 'safety' | 'failed' | 'canceled' | 'timed_out'

async function applyAssistantDeterministicFixture(
  page: Page,
  runId: string,
  fixtureState: AssistantDeterministicFixture,
) {
  return page.evaluate(async ({ targetRunId, targetFixtureState }) => {
    const modulePath = '/src/stores/ai.ts'
    const storeModule = await import(/* @vite-ignore */ modulePath) as {
      useAiStore: () => {
        messages: Array<{
          id: string
          role: string
          text: string
          citations: Array<Record<string, unknown>>
          confidence?: number
          grounded?: boolean
          runId?: string
          runState?: string
          failureMessage?: string
          degradedReason?: string
        }>
        runState: string
      }
    }
    const store = storeModule.useAiStore()
    const answer = [...store.messages].reverse().find((message) => (
      message.role === 'assistant' && message.runId === targetRunId
    ))
    if (!answer) throw new Error('助手确定性状态 fixture 找不到目标真实回答')

    delete answer.failureMessage
    delete answer.degradedReason
    delete answer.confidence
    delete answer.grounded
    answer.citations = []

    if (targetFixtureState === 'safety') {
      answer.text = '历史回答的授权来源已不可访问，请重新提问或联系管理员。'
      answer.citations = [{
        id: `visual-denied-${targetRunId}`,
        label: '已撤权来源',
        locator: 'redacted',
        version: 'redacted',
        access: 'denied',
      }]
      answer.confidence = 0.52
      answer.grounded = false
      answer.runState = 'succeeded'
    } else if (targetFixtureState === 'failed') {
      answer.text = '本次未生成可用回答。'
      answer.failureMessage = '生成失败，请重试或改用人工查询。'
      answer.runState = 'failed'
    } else if (targetFixtureState === 'canceled') {
      answer.text = '生成已停止，已保留当前可见内容。'
      answer.runState = 'cancelled'
    } else {
      answer.text = '本次生成未在限定时间内完成。'
      answer.failureMessage = '生成超时，可重试本次请求。'
      answer.runState = 'timed_out'
    }
    store.runState = answer.runState
    return { messageId: answer.id, runId: answer.runId, fixtureState: targetFixtureState }
  }, { targetRunId: runId, targetFixtureState: fixtureState })
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

async function observeBackendRuntime(
  context: BrowserContext,
  viewport: string,
  phase: string,
): Promise<RuntimeReadinessObservation> {
  const readiness = await apiData<BackendRuntimeReadiness>(
    await context.request.get('/api/ai/operations/readiness'),
    200,
    `${viewport} 后端 AI 运行时诊断`,
  )
  expect(readiness).toMatchObject({
    masterEnabled: true,
    providerAlias: 'fake',
    streamingEnabled: true,
    writeExecutionEnabled: false,
  })
  return {
    viewport,
    phase,
    observedAt: new Date().toISOString(),
    source: 'GET /api/ai/operations/readiness',
    masterEnabled: readiness.masterEnabled,
    providerAlias: readiness.providerAlias,
    streamingEnabled: readiness.streamingEnabled,
    writeExecutionEnabled: readiness.writeExecutionEnabled,
    controls: readiness.controls,
  }
}

function observeContextWriteBoundary(
  context: BrowserContext,
  viewport: string,
  businessWrites: ObservedBusinessWrite[],
  serviceWorkers: ObservedServiceWorker[],
) {
  context.on('serviceworker', (worker) => {
    serviceWorkers.push({ viewport, url: worker.url() })
  })
  context.on('request', (request: Request) => {
    const url = new URL(request.url())
    const method = request.method()
    if (
      !url.pathname.startsWith('/api/')
      || !unsafeMethods.has(method)
      || url.pathname.startsWith('/api/ai/')
      || url.pathname.startsWith('/api/auth/')
      || url.pathname.startsWith('/api/security/')
    ) return

    const serviceWorker = request.serviceWorker()
    let route: string
    let source: ObservedBusinessWrite['source'] = 'unknown'
    if (serviceWorker) {
      route = serviceWorker.url()
      source = 'service-worker'
    } else if (request.isNavigationRequest()) {
      route = url.pathname
      source = 'navigation'
    } else {
      try {
        route = new URL(request.frame().url()).pathname
        source = 'page'
      } catch {
        route = 'unknown'
      }
    }
    businessWrites.push({ viewport, route, source, method, path: url.pathname })
  })
}

async function authenticateVisualContext(
  context: BrowserContext,
  credentials: ReturnType<typeof requireVisualCredentials>,
) {
  await retryVisualLogin(async ({ remainingMs }): Promise<VisualLoginAttemptResult> => {
    try {
      const loginResponse = await context.request.post(`${visualBaseURL}/api/auth/login`, {
        data: { username: credentials.username, password: credentials.password },
        timeout: Math.max(1, Math.min(visualLoginRequestTimeoutMs, remainingMs)),
      })
      return {
        status: loginResponse.status(),
        detail: loginResponse.ok()
          ? ''
          : await loginResponse.text().catch(() => 'unreadable response'),
      }
    } catch (error) {
      if (isVisualLoginServiceNotReady(error)) {
        return { status: 'service-not-ready', detail: error }
      }
      throw error
    }
  }, {
    timeoutMs: visualLoginTimeoutMs,
    intervalMs: visualLoginRetryIntervalMs,
    sanitize: sanitizeDiagnostic,
  })
  const loginState = await context.request.storageState()
  const sessionCookie = loginState.cookies.find((cookie) => cookie.name === 'Authorization')
  if (!sessionCookie) throw new Error('真实服务登录成功但未返回 Authorization Cookie')
  await context.addCookies(loginState.cookies)
  const browserSessionCookie = (await context.cookies(visualBaseURL))
    .find((cookie) => cookie.name === 'Authorization')
  if (!browserSessionCookie) {
    throw new Error('真实服务登录成功但 Authorization Cookie 未进入浏览器上下文')
  }
}

function commandHeaders(token: string, refererPath: string, idempotencyKey?: string) {
  return {
    'X-CSRF-Token': token,
    Origin: visualBaseURL,
    Referer: `${visualBaseURL}${refererPath}`,
    ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
  }
}

async function csrfToken(context: BrowserContext, refererPath: string) {
  return apiData<{ token: string }>(
    await context.request.get('/api/security/csrf'),
    200,
    `获取 ${refererPath} CSRF token`,
  )
}

async function createRepairCaptureScenario(
  context: BrowserContext,
  token: string,
  _marker: string,
): Promise<{ id: number; code: string }> {
  const roles = await apiData<Array<{ id: number; code: string }>>(
    await context.request.get('/api/roles/options'),
    200,
    '读取视觉维修人员角色',
  )
  const repairerRole = roles.find((role) => role.code === 'REPAIRER')
  expect(repairerRole, '视觉维修分诊需要 REPAIRER 角色').toBeDefined()

  const userMarker = `${Date.now().toString(36)}${randomUUID().replaceAll('-', '').slice(0, 8)}`
  await apiData<JsonObject>(
    await context.request.post('/api/users', {
      headers: commandHeaders(token, '/system/users'),
      data: {
        username: `visual_rep_${userMarker}`.slice(0, 32),
        displayName: '维修员 周师傅',
        password: `S6!${randomUUID()}aA1`,
        enabled: true,
        roleIds: [repairerRole!.id],
      },
    }),
    201,
    '创建视觉维修人员',
  )

  // 先造足够的真实列表密度，再把高紧急度工单放在最新一条，保证截图中的选中详情可复现。
  const fillerTypes = ['家具维修', '门窗维修', '水电维修', '家具维修', '门窗维修', '水电维修', '家具维修']
  for (const [index, type] of fillerTypes.entries()) {
    await apiData<JsonObject>(
      await context.request.post('/api/repair-orders', {
        headers: commandHeaders(token, '/repairs'),
        data: {
          reporter: '值班宿管',
          location: `宿舍楼 ${String.fromCharCode(65 + (index % 4))}-${index + 1}01 室`,
          type,
          description: `宿舍设施报修 ${index + 1}，已登记并等待人工处理。`,
          assigneeUserId: null,
        },
      }),
      201,
      `创建视觉维修列表工单 ${index + 1}`,
    )
  }

  return apiData<{ id: number; code: string }>(
    await context.request.post('/api/repair-orders', {
      headers: commandHeaders(token, '/repairs'),
      data: {
        reporter: '夜班宿管',
        location: '宿舍楼 B-2-201 室',
        type: '水电维修',
        description: repairCaptureDescription,
        assigneeUserId: null,
      },
    }),
    201,
    '创建视觉分诊目标工单',
  )
}

async function createPublishedNoticeCaptureScenario(context: BrowserContext, token: string) {
  const created = await apiData<{ id: number }>(
    await context.request.post('/api/notices', {
      headers: commandHeaders(token, '/notices'),
      data: {
        title: '宿舍安全检查与整改通知',
        type: '安全卫生',
        publisher: '学生宿舍管理中心',
        status: '草稿',
        content: noticePrototypePoints('本轮真实检查'),
      },
    }),
    201,
    '创建视觉当前公告草稿',
  )
  await apiData<JsonObject>(
    await context.request.patch(`/api/notices/${created.id}`, {
      headers: commandHeaders(token, '/notices'),
      data: {
        title: '宿舍安全检查与整改通知',
        type: '安全卫生',
        publisher: '学生宿舍管理中心',
        status: '已发布',
        content: noticePrototypePoints('本轮真实检查'),
      },
    }),
    200,
    '发布视觉当前公告',
  )
  return created.id
}

async function createRepeatRepairOrders(context: BrowserContext, token: string, _marker: string) {
  const location = '宿舍楼 C-3 公共走廊'
  for (let index = 0; index < 3; index += 1) {
    await apiData<{ id: number; code: string }>(
      await context.request.post('/api/repair-orders', {
        headers: commandHeaders(token, '/repairs'),
        data: {
          reporter: '值班宿管',
          location,
          type: '水电维修',
          description: `公共走廊照明重复报修 ${index + 1}，请按受控规则核验。`,
          assigneeUserId: null,
        },
      }),
      201,
      `创建全量视觉维修工单 ${index + 1}`,
    )
  }
}

async function createHygieneAndPaymentRisks(context: BrowserContext, token: string, _marker: string) {
  const dormitories = await apiData<{ records: JsonObject[]; total: number }>(
    await context.request.get('/api/dormitories?page=1&pageSize=1'),
    200,
    '读取视觉风险宿舍范围',
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
        inspector: '全量视觉验收',
        score: 45,
        remark: '本次检查发现地面与公共区域卫生不合格，需限期复查。',
      },
    }),
    201,
    '创建全量视觉不合格卫生检查',
  )

  await apiData<JsonObject>(
    await context.request.post('/api/payment-bills', {
      headers: commandHeaders(token, '/payments'),
      data: {
        studentNo: Date.now().toString().slice(-12),
        name: '王同学',
        type: '住宿费',
        amountDue: 880,
        deadline: '2000-01-01',
      },
    }),
    201,
    '创建全量视觉逾期账单',
  )
}

async function runRiskScan(context: BrowserContext, token: string, marker: string) {
  const scan = await apiData<{ id: string; state: string }>(
    await context.request.post('/api/ai/risk-scans', {
      headers: commandHeaders(token, '/ai/risks', `visual-risk-${marker}`),
    }),
    202,
    '请求全量视觉风险扫描',
  )
  let latest: JsonObject = scan as unknown as JsonObject
  const deadline = Date.now() + 90_000
  while (Date.now() < deadline) {
    latest = await apiData<JsonObject>(
      await context.request.get(`/api/ai/risk-scans/${encodeURIComponent(scan.id)}`),
      200,
      '读取全量视觉风险扫描状态',
    )
    const state = String(latest.state ?? '').toUpperCase()
    if (['SUCCEEDED', 'PARTIAL', 'FAILED', 'NEEDS_REVIEW'].includes(state)) break
    await delay(1_000)
  }
  const state = String(latest.state ?? '').toUpperCase()
  expect(state, `风险扫描未在限定时间内完成：${JSON.stringify(latest)}`).toMatch(/^(SUCCEEDED|PARTIAL)$/)
  expect(Number(latest.signalCount ?? 0), '全量视觉风险规则未命中').toBeGreaterThan(0)
  expect(Number(latest.caseCount ?? 0), '全量视觉风险扫描未产生风险案例').toBeGreaterThan(0)
  return {
    id: scan.id,
    state,
    signalCount: Number(latest.signalCount ?? 0),
    caseCount: Number(latest.caseCount ?? 0),
  }
}

async function generateNoticeDraft(page: Page, _marker: string) {
  await page.goto('/notices/create')
  const region = page.getByRole('region', { name: '公告 AI 起草工作台' })
  await expect(region).toBeVisible()
  const points = region.getByRole('textbox', { name: '公告要点' })
  await points.fill(
    '近期开展学生宿舍安全检查。检查范围包括公共区域用电、消防通道、插线板和易燃物品；检查时间另行通知。请同学们提前自查并保持通道畅通，对发现的问题按要求限期整改。',
  )
  const draftResponsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/ai/notices/drafts' && response.request().method() === 'POST'
  })
  await region.getByRole('button', { name: '生成 AI 草稿' }).click()
  const draftResponse = await draftResponsePromise
  const accepted = await apiData<JsonObject>(draftResponse, 202, '公告草稿必须通过真实 AI command 创建')
  const runId = String(accepted.runId ?? '')
  expect(runId, '公告草稿响应必须绑定真实 runId').not.toBe('')
  await expect(page.getByRole('region', { name: 'AI 草稿' })).toContainText('AI 草稿已生成', { timeout: 60_000 })
  const title = page.getByRole('textbox', { name: '公告标题' })
  const body = page.getByRole('textbox', { name: 'AI 草稿正文' })
  await expect(title).not.toHaveValue('')
  await expect(body).not.toHaveValue('')
  await expect.poll(async () => (await body.inputValue()).trim().length, {
    message: '公告原型内容态需要完整多句正文',
  }).toBeGreaterThanOrEqual(100)
  await expect(page.getByRole('region', { name: '内容检查' })).toContainText(/通过|置信度|引用来源/, { timeout: 60_000 })
  const proposalButton = region.getByRole('button', { name: /查看审批提案/ })
  await expect(proposalButton).toBeEnabled()
  await points.evaluate((element) => { element.scrollTop = 0 })
  expect(await points.evaluate((element) => element.scrollTop), '公告原型截图前要点文本域必须从首行开始').toBe(0)
  return { runId, proposalButton }
}

async function createNoticeProposal(page: Page, marker: string) {
  const { proposalButton } = await generateNoticeDraft(page, marker)
  const proposalResponsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return /^\/api\/ai\/proposals\/[^/]+$/.test(url.pathname) && response.request().method() === 'GET'
  })
  await proposalButton.click()
  const proposalResponse = await proposalResponsePromise
  const proposal = await apiData<JsonObject>(proposalResponse, 200, '读取全量视觉审批提案')
  const proposalId = new URL(proposalResponse.url()).pathname.split('/').pop() ?? ''
  expect(proposalId).not.toBe('')
  expect(String(proposal.state ?? ''), '公告提案必须保持待审批').toBe('pending_approval')
  return {
    id: proposalId,
    actionType: String(proposal.actionType ?? ''),
    state: String(proposal.state ?? ''),
  }
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

test(`真实服务 ${routes.length} 路由 × ${viewports.length} 视口生成并校验永久视觉证据`, async ({ browser }) => {
  const startedAt = Date.now()
  const sourceBindingsAtStart = collectSourceBindings()
  const prototypeBindingsAtStart = collectPrototypeBindings()
  const credentials = requireVisualCredentials()
  const screenshots: string[] = []
  const screenshotDetails: Array<FileBinding & { route: string; viewport: string }> = []
  const assistantScreenshots: string[] = []
  const assistantScreenshotDetails: Array<FileBinding & {
    route: string
    viewport: string
    evidenceSource: VisualEvidenceSource
  }> = []
  const assistantTurnEvidence: FreshAssistantTurnEvidence[] = []
  const contentStateChecks: VisualContentStateEvidence[] = []
  const prototypeCaptures: PrototypeCaptureRecord[] = []
  const designSystemChecks: DesignSystemCheck[] = []
  let designSystemGalleryCapture:
    (VisualDesignSystemGalleryCaptureEvidence & Pick<FileBinding, 'mtimeUtc'>) | null = null
  const apiErrors: RuntimeIssue[] = []
  const apiRequestFailures: RuntimeIssue[] = []
  const toleratedApiRequestFailures: RuntimeIssue[] = []
  const observedApiRequestFailures: ObservedApiRequestFailure[] = []
  const successfulApiKeys = new Set<string>()
  const successfulApiPaths = new Set<string>()
  const consoleErrors: RuntimeIssue[] = []
  const pageErrors: RuntimeIssue[] = []
  const layoutChecks: LayoutCheck[] = []
  const shellChecks: ShellCheck[] = []
  const canvasChecks: CanvasCheck[] = []
  const businessDateChecks: BusinessDateCheck[] = []
  const dashboardMobileGeometryChecks: DashboardMobileGeometryCheck[] = []
  const runtimeReadiness: RuntimeReadinessObservation[] = []
  const businessWrites: ObservedBusinessWrite[] = []
  const observedServiceWorkers: ObservedServiceWorker[] = []
  const knowledgeMarker = `VISUAL-${Date.now().toString(36).toUpperCase()}`
  const riskMarker = `${knowledgeMarker}-RISK`
  const approvalMarker = `${knowledgeMarker}-APPROVAL`
  const auditMarker = `${knowledgeMarker}-AUDIT`
  const repairMarker = `${knowledgeMarker}-REPAIR`
  let repairCaptureOrder: { id: number; code: string } | null = null
  let runError = ''

  const collectDesignSystemCheck = async (page: Page, route: string, viewport: string) => {
    const designSystem = await page.evaluate(([tokenNames, panelSelector]) => {
      const root = getComputedStyle(document.documentElement)
      const tokens = Object.fromEntries(tokenNames.map((name) => [name, root.getPropertyValue(name).trim()]))
      const sidebar = document.querySelector<HTMLElement>('.admin-sider')
      const panel = document.querySelector<HTMLElement>(panelSelector)
      const touchTarget = Array.from(document.querySelectorAll<HTMLElement>('button, [role="button"], input'))
        .find((element) => element.getBoundingClientRect().height >= 44)
      const safetyState = document.querySelector<HTMLElement>('.ai-safety-state')
      const sharedComponents = {
        AiCommandBar: Boolean(document.querySelector('.ai-command-bar[role="search"]')),
        AiEvidenceMeta: Boolean(document.querySelector('.ai-evidence[aria-label]')),
        AiRunStatus: Boolean(document.querySelector('.ai-run-status[role="status"]')),
        AiSafetyState: Boolean(safetyState),
      }
      return {
        tokens,
        computedStyles: {
          fontFamily: root.fontFamily.trim(),
          sidebarBackground: sidebar ? getComputedStyle(sidebar).backgroundColor : '',
          panelBorderRadius: panel ? getComputedStyle(panel).borderRadius : '',
          touchTargetHeight: touchTarget ? `${Math.round(touchTarget.getBoundingClientRect().height)}px` : '',
        },
        stateSemantics: {
          icon: Boolean(safetyState?.querySelector('[data-state-icon] svg')),
          text: Boolean(safetyState?.querySelector('.ai-safety-state__message')?.textContent?.trim()),
          ariaLive: safetyState?.getAttribute('aria-live') === 'polite'
            || safetyState?.getAttribute('aria-live') === 'assertive',
        },
        sharedComponents,
      }
    }, [[...designSystemEvidenceContract.tokens], designSystemPanelSelector] as const)
    designSystemChecks.push({ route, viewport, ...designSystem })
  }

  mkdirSync(visualDirectory, { recursive: true })
  const captureAssistantScreenshot = async (
    page: Page,
    viewport: string,
    kind: string,
    evidenceSource: VisualEvidenceSource,
  ) => {
    const filePrefix = evidenceSource === 'runtime' ? 'live' : 'fixture'
    const file = `${filePrefix}-assistant-${kind}-${viewport}.png`
    const path = resolve(visualDirectory, file)
    await page.screenshot({ path, animations: 'disabled', caret: 'hide' })
    const binding = fileBinding(file, path)
    expect(assistantScreenshots, `${file} 助手截图重复登记`).not.toContain(file)
    assistantScreenshots.push(file)
    assistantScreenshotDetails.push({ ...binding, route: '/', viewport, evidenceSource })
    return binding
  }

  try {
    for (const viewport of viewports) {
      const context = await browser.newContext({
        baseURL: visualBaseURL,
        viewport: { width: viewport.width, height: viewport.height },
        deviceScaleFactor: 1,
        reducedMotion: 'reduce',
      })
      observeContextWriteBoundary(context, viewport.name, businessWrites, observedServiceWorkers)

      try {
        await authenticateVisualContext(context, credentials)
        runtimeReadiness.push(await observeBackendRuntime(context, viewport.name, 'before-routes'))

        if (viewport === viewports[0]) {
          const setupPage = await context.newPage()
          try {
            await prepareGroundedKnowledge(setupPage, knowledgeMarker)
            const token = await csrfToken(context, '/repairs')
            repairCaptureOrder = await createRepairCaptureScenario(context, token.token, repairMarker)
            await createPublishedNoticeCaptureScenario(context, token.token)
          } finally {
            await setupPage.close()
          }
        }

        for (const route of routes) {
          const page = await context.newPage()
          try {
            page.on('response', async (response) => {
              const url = new URL(response.url())
              if (url.pathname.startsWith('/api/') && response.status() >= 200 && response.status() < 400) {
                successfulApiKeys.add(`${response.request().method()} ${url.pathname}`)
                successfulApiPaths.add(url.pathname)
              }
              if (url.pathname.startsWith('/api/') && response.status() >= 400) {
                const detail = sanitizeDiagnostic(await response.text().catch(() => 'unreadable response'))
                // Playwright's headers() intentionally omits security-sensitive headers such as Cookie.
                // Use allHeaders() so the runtime manifest does not report a false missing session.
                const requestHeaders = await response.request().allHeaders()
                const headerSummary = JSON.stringify({
                  origin: requestHeaders.origin,
                  referer: requestHeaders.referer,
                  csrf: requestHeaders['x-csrf-token'] ? 'present' : 'missing',
                  cookie: requestHeaders.cookie ? 'present' : 'missing',
                })
                apiErrors.push({
                  viewport: viewport.name,
                  route: route.path,
                  kind: 'api-response',
                  detail: `${response.request().method()} ${url.pathname} ${headerSummary} ${detail}`,
                  status: response.status(),
                })
              }
            })
            page.on('requestfailed', (request) => {
              const url = new URL(request.url())
              if (url.pathname.startsWith('/api/')) {
                const issue = {
                  viewport: viewport.name,
                  route: route.path,
                  kind: 'api-request-failed',
                  detail: `${request.method()} ${url.pathname}: ${sanitizeDiagnostic(request.failure()?.errorText ?? '未知网络错误')}`,
                }
                const failureText = request.failure()?.errorText ?? '未知网络错误'
                observedApiRequestFailures.push({
                  issue,
                  method: request.method(),
                  path: url.pathname,
                  failureText,
                })
              }
            })
            page.on('console', (message) => {
              if (message.type() !== 'error') return
              const location = message.location()
              const source = location.url ? new URL(location.url).pathname : ''
              const text = message.text()
              const isToleratedApiNoise = source.startsWith('/api/')
                && successfulApiPaths.has(source)
                && /Failed to load resource: net::ERR_(ABORTED|NO_BUFFER_SPACE)/.test(text)
              if (isToleratedApiNoise) return
              consoleErrors.push({
                viewport: viewport.name,
                route: route.path,
                kind: 'console-error',
                detail: sanitizeDiagnostic(`${text}${source ? ` @ ${source}:${location.lineNumber ?? 0}` : ''}`),
              })
            })
            page.on('pageerror', (error) => {
              pageErrors.push({
                viewport: viewport.name,
                route: route.path,
                kind: 'page-error',
                detail: sanitizeDiagnostic(error),
              })
            })

            await gotoVisualRoute(page, route.path)
            await page.waitForLoadState('networkidle')
            await expect(page.locator('.ant-spin-spinning')).toHaveCount(0)
            await expect(page.getByRole('heading', { name: route.title, exact: true }).first()).toBeVisible()
            expect(new URL(page.url()).pathname).toBe(route.path)
            if ('dialog' in route && route.dialog) {
              await expect(page.getByRole('dialog', { name: route.title })).toBeVisible()
            }
            if ('region' in route && route.region) {
              await expect(page.getByRole('region', { name: route.region })).toBeVisible()
            }
            await page.evaluate(() => document.fonts.ready)

            const shell = await page.evaluate(() => {
              const sidebar = document.querySelector<HTMLElement>('.admin-sider')
              const brand = document.querySelector<HTMLElement>('.brand')
              const brandMark = brand?.querySelector<HTMLElement>('.brand-mark')
              const aiMenuItems = Array.from(document.querySelectorAll<HTMLElement>(
                '.side-menu .ant-menu-item-group .ant-menu-item',
              ))
              const selected = document.querySelector<HTMLElement>('.side-menu .ant-menu-item-selected')
              return {
                sidebarWidth: sidebar?.getBoundingClientRect().width ?? 0,
                brand: brand?.getAttribute('title')?.trim() ?? '',
                brandMark: brandMark?.textContent?.trim() ?? '',
                brandHomeIconCount: brandMark?.querySelectorAll('.anticon-home').length ?? 0,
                aiMenuLabels: aiMenuItems.map((item) => item.textContent?.trim() ?? '').filter(Boolean),
                selectedMenuLabel: selected?.textContent?.trim() ?? '',
              }
            })
            const expectedSidebarWidth = viewport.width <= 768 ? 52 : 208
            expect(
              Math.abs(shell.sidebarWidth - expectedSidebarWidth),
              `${viewport.name} ${route.path} 未复用 ${expectedSidebarWidth}px 共享 Sidebar`,
            ).toBeLessThanOrEqual(2)
            expect(shell.brand, `${viewport.name} ${route.path} 品牌标题漂移`).toBe('学生宿舍管理系统')
            expect(shell.brandMark, `${viewport.name} ${route.path} 折叠品牌标记漂移`).toBe('A')
            expect(shell.brandHomeIconCount, `${viewport.name} ${route.path} 品牌位不得复用首页房屋图标`).toBe(0)
            expect(shell.aiMenuLabels, `${viewport.name} ${route.path} AI 权限菜单漂移`).toEqual([
              '知识管理',
              '智能风险中心',
              '审批与审计',
            ])
            if (route.path === '/ai/approvals' || route.path === '/ai/audit') {
              expect(shell.selectedMenuLabel, `${viewport.name} ${route.path} 未保持治理统一选中态`).toBe('审批与审计')
            }
            shellChecks.push({ viewport: viewport.name, route: route.path, ...shell })

            if (route.path === '/') {
              const command = page.getByRole('search', { name: '自然语言查询' })
              await expect(command).toBeVisible()
              await expect(page.locator('.ai-brief--loading')).toHaveCount(0, { timeout: 60_000 })
              await command.getByRole('textbox', { name: '自然语言查询' }).fill('本周待维修工单有多少？')
              await command.getByRole('button', { name: /查询/ }).click()
              // 刷新保留上一版 summary，不会出现 --loading 类；必须等真实刷新结束。
              await expect(page.locator('[data-dashboard-section="brief"]'))
                .toHaveAttribute('aria-busy', 'false', { timeout: 60_000 })
              await expect(command.getByRole('button', { name: /查询/ })).toBeEnabled()
              const brief = page.getByRole('article', { name: '今日 AI 运营简报' })
              await expect(brief).toBeVisible()
              await expect(brief).toContainText('口径', { timeout: 60_000 })
              await expect(brief).toContainText('数据截至', { timeout: 60_000 })
              await expect(brief.locator('[data-brief-meta]')).toHaveCount(3, { timeout: 60_000 })
              const dashboardAsOfText = await brief.locator('[data-brief-meta="as-of"]').getAttribute('aria-label')
              expect(dashboardAsOfText, `${viewport.name} Dashboard 缺少数据截至标签`).not.toBeNull()
              businessDateChecks.push(businessDateCheck({
                viewport: viewport.name,
                route: route.path,
                surface: 'Dashboard 数据截至',
                text: dashboardAsOfText!,
              }))
              await expect(brief.locator('.ai-brief__summary')).not.toContainText('运营简报尚未完成。')
              await expect(brief.locator('.ai-brief__summary')).not.toContainText('暂无可靠来源，不生成结论。')
              await expect(page.locator('.risk-row')).toHaveCount(4)
            }

            const layout = await page.evaluate(() => {
              const viewportWidth = document.documentElement.clientWidth
              const documentWidth = Math.max(
                document.documentElement.scrollWidth,
                document.body?.scrollWidth ?? 0,
              )
              const overflowBy = Math.max(0, documentWidth - viewportWidth)
              const overflowElements = overflowBy > 1
                ? Array.from(document.querySelectorAll<HTMLElement>('body *'))
                    .filter((element) => {
                      const bounds = element.getBoundingClientRect()
                      return bounds.width > 0 && (bounds.left < -1 || bounds.right > viewportWidth + 1)
                    })
                    .slice(0, 8)
                    .map((element) => {
                      const bounds = element.getBoundingClientRect()
                      const style = getComputedStyle(element)
                      const id = element.id ? `#${element.id}` : ''
                      const classes = Array.from(element.classList).slice(0, 3).map((name) => `.${name}`).join('')
                      const label = element.getAttribute('aria-label')
                      const geometry = `x=${bounds.x.toFixed(1)},right=${bounds.right.toFixed(1)},width=${bounds.width.toFixed(1)}`
                      const sizing = `cssWidth=${style.width},marginInline=${style.marginInline},flex=${style.flex}`
                      return `${element.tagName.toLowerCase()}${id}${classes}${label ? `[aria-label="${label}"]` : ''}{${geometry};${sizing}}`
                    })
                : []
              return { viewportWidth, documentWidth, overflowBy, overflowElements }
            })
            layoutChecks.push({ viewport: viewport.name, route: route.path, ...layout })

            if (route.path === '/') {
              await expect(page.locator('canvas').first()).toBeVisible()
              let canvasEvidence = { canvasCount: 0, renderedCanvasCount: 0 }
              await expect.poll(async () => {
                canvasEvidence = await page.locator('canvas').evaluateAll((canvases) => {
                  const visible = canvases.filter((canvas) => {
                    const rect = canvas.getBoundingClientRect()
                    return rect.width > 0 && rect.height > 0
                  }) as HTMLCanvasElement[]
                  const renderedCanvasCount = visible.filter((canvas) => {
                    const blank = document.createElement('canvas')
                    blank.width = canvas.width
                    blank.height = canvas.height
                    return canvas.toDataURL('image/png') !== blank.toDataURL('image/png')
                  }).length
                  return { canvasCount: visible.length, renderedCanvasCount }
                })
                return canvasEvidence.renderedCanvasCount
              }, { message: `Dashboard Canvas 未在 ${viewport.name} 完成绘制` }).toBeGreaterThan(0)
              canvasChecks.push({ viewport: viewport.name, ...canvasEvidence })

              const commandBounds = await page.getByRole('search', { name: '自然语言查询' }).boundingBox()
              const headerActionsBounds = await page.locator('.header-actions').boundingBox()
              expect(commandBounds, `${viewport.name} Dashboard 快捷提问栏缺少可测量布局`).not.toBeNull()
              expect(headerActionsBounds, `${viewport.name} Header 操作区缺少可测量布局`).not.toBeNull()
              const overlapsHeaderActions = commandBounds!.x < headerActionsBounds!.x + headerActionsBounds!.width
                && commandBounds!.x + commandBounds!.width > headerActionsBounds!.x
                && commandBounds!.y < headerActionsBounds!.y + headerActionsBounds!.height
                && commandBounds!.y + commandBounds!.height > headerActionsBounds!.y
              expect(overlapsHeaderActions, `${viewport.name} Dashboard 快捷提问栏遮挡 Header 操作区`).toBe(false)

              const visibleMetrics = page.locator('[data-dashboard-metric]:visible')
              await expect(visibleMetrics).toHaveCount(viewport.width <= 768 ? 4 : 5)
              const metricGeometry = await visibleMetrics.evaluateAll((elements) => elements.map((element) => {
                const bounds = element.getBoundingClientRect()
                return { x: bounds.x, y: bounds.y, width: bounds.width, height: bounds.height }
              }))
              expect(
                Math.max(...metricGeometry.map(({ width }) => width))
                  - Math.min(...metricGeometry.map(({ width }) => width)),
                `${viewport.name} Dashboard KPI 未保持等宽`,
              ).toBeLessThanOrEqual(8)
              if (viewport.width <= 768) {
                const metricRows = new Set(metricGeometry.map(({ y }) => Math.round(y)))
                expect(metricRows.size, `${viewport.name} Dashboard KPI 应为 2 x 2`).toBe(2)
              } else {
                expect(
                  Math.max(...metricGeometry.map(({ y }) => y))
                    - Math.min(...metricGeometry.map(({ y }) => y)),
                  `${viewport.name} Dashboard 5 个 KPI 顶边未对齐`,
                ).toBeLessThanOrEqual(2)
              }

              const briefFacts = page.locator('.ai-brief__facts')
              await expect(briefFacts).toBeVisible()
              await expect(briefFacts.locator('[data-brief-meta]')).toHaveCount(3)
              let trendBounds = await page.locator('.trend-panel').boundingBox()
              let riskBounds = await page.locator('.risk-panel').boundingBox()
              expect(trendBounds, `${viewport.name} 趋势面板缺少可测量布局`).not.toBeNull()
              expect(riskBounds, `${viewport.name} 风险面板缺少可测量布局`).not.toBeNull()
              if (viewport.width <= 768) {
                expect(riskBounds!.y, `${viewport.name} 风险概览应先于趋势图进入移动首屏`).toBeLessThan(trendBounds!.y)
                if (viewport.name === '390x844') {
                  await page.evaluate(() => window.scrollTo(0, 0))
                  const pendingHeading = page.locator('.pending-panel__heading')
                  const sparseState = page.locator('.trend-sparse-state')
                  const degradedState = page.locator('.ai-brief__warning')
                  await expect(pendingHeading, '移动 Dashboard 缺少待处理事项标题').toBeVisible()
                  await expect(sparseState, '移动 Dashboard 真实稀疏趋势说明不可见').toBeVisible()
                  await expect(degradedState, '移动 Dashboard 真实降级说明不可见').toBeVisible()
                  const mobileGeometry = await page.evaluate(() => {
                    const documentTop = (selector: string) => {
                      const bounds = document.querySelector<HTMLElement>(selector)?.getBoundingClientRect()
                      return bounds ? bounds.top + window.scrollY : Number.NaN
                    }
                    const viewportWidth = document.documentElement.clientWidth
                    const documentWidth = Math.max(
                      document.documentElement.scrollWidth,
                      document.body?.scrollWidth ?? 0,
                    )
                    const trendLegends = Array.from(document.querySelectorAll<HTMLElement>('.trend-meta > span'))
                      .filter((element) => element.offsetParent !== null)
                    const trendLegendBounds = trendLegends.map((element) => element.getBoundingClientRect())
                    const trendLegendsOverlap = trendLegendBounds.some((bounds, index) => (
                      trendLegendBounds.slice(index + 1).some((other) => (
                        bounds.left < other.right
                        && bounds.right > other.left
                        && bounds.top < other.bottom
                        && bounds.bottom > other.top
                      ))
                    ))
                    return {
                      viewportWidth,
                      documentWidth,
                      documentHeight: Math.max(
                        document.documentElement.scrollHeight,
                        document.body?.scrollHeight ?? 0,
                      ),
                      riskTop: documentTop('.risk-panel'),
                      trendTop: documentTop('.trend-panel'),
                      pendingTop: documentTop('.pending-panel__heading'),
                      trendLegendCount: trendLegends.length,
                      trendLegendTexts: trendLegends.map((element) => element.textContent?.trim() ?? ''),
                      trendLegendsOverlap,
                      vacancyDuplicateVisible: Boolean(document.querySelector<HTMLElement>('.trend-meta > strong')?.offsetParent),
                      sparseStateVisible: Boolean(document.querySelector<HTMLElement>('.trend-sparse-state')?.offsetParent),
                      degradedStateVisible: Boolean(document.querySelector<HTMLElement>('.ai-brief__warning')?.offsetParent),
                    }
                  })
                  expect(mobileGeometry.riskTop, 'Dashboard 风险区缺少文档坐标').not.toBeNaN()
                  expect(mobileGeometry.trendTop, 'Dashboard 趋势区缺少文档坐标').not.toBeNaN()
                  expect(mobileGeometry.pendingTop, 'Dashboard 待办区缺少文档坐标').not.toBeNaN()
                  expect(mobileGeometry.riskTop, 'Dashboard 风险、趋势与待办信息顺序不连续')
                    .toBeLessThan(mobileGeometry.trendTop)
                  expect(mobileGeometry.trendTop, 'Dashboard 风险、趋势与待办信息顺序不连续')
                    .toBeLessThan(mobileGeometry.pendingTop)
                  expect(mobileGeometry.pendingTop, 'Dashboard 待办标题未在 824px 内进入连续信息流')
                    .toBeLessThanOrEqual(824)
                  expect(mobileGeometry.documentHeight, 'Dashboard 移动文档高度超过 1320px')
                    .toBeLessThanOrEqual(1320)
                  expect(mobileGeometry.documentWidth, 'Dashboard 移动页面出现横向滚动')
                    .toBeLessThanOrEqual(mobileGeometry.viewportWidth)
                  expect(mobileGeometry.trendLegendCount, 'Dashboard 移动趋势图例应保留两项真实序列').toBe(2)
                  expect(mobileGeometry.trendLegendTexts, 'Dashboard 移动趋势图例语义漂移').toEqual([
                    '入住办理（人次）',
                    '累计入住（人次）',
                  ])
                  expect(mobileGeometry.trendLegendsOverlap, 'Dashboard 移动趋势图例发生重叠').toBe(false)
                  expect(mobileGeometry.vacancyDuplicateVisible, 'Dashboard 移动端重复显示当前空床元信息').toBe(false)
                  expect(mobileGeometry.sparseStateVisible, 'Dashboard 稀疏说明被隐藏').toBe(true)
                  expect(mobileGeometry.degradedStateVisible, 'Dashboard 降级说明被隐藏').toBe(true)
                  dashboardMobileGeometryChecks.push({
                    viewport: viewport.name,
                    route: route.path,
                    ...mobileGeometry,
                  })
                  const mobileSections = [
                    {
                      id: 'pending',
                      locator: page.locator('[data-dashboard-section="pending"] > .pending-table-wrap'),
                    },
                    {
                      id: 'guardrails',
                      locator: page.locator('[data-dashboard-section="guardrails"]'),
                    },
                    {
                      id: 'approval-flow',
                      locator: page.locator('[data-dashboard-section="approval-flow"]'),
                    },
                  ] as const
                  const initialScrollY = await page.evaluate(() => window.scrollY)
                  let previousBottom = trendBounds!.y + initialScrollY + trendBounds!.height
                  for (const { id: sectionId, locator: section } of mobileSections) {
                    await section.scrollIntoViewIfNeeded()
                    await expect(section, `移动 Dashboard 缺少 ${sectionId} 区域`).toBeVisible()
                    const bounds = await section.boundingBox()
                    expect(bounds, `移动 Dashboard ${sectionId} 缺少可测量布局`).not.toBeNull()
                    const scrollY = await page.evaluate(() => window.scrollY)
                    const documentTop = bounds!.y + scrollY
                    expect(
                      documentTop,
                      `移动 Dashboard ${sectionId} 顺序错误或不可滚动到达`,
                    ).toBeGreaterThanOrEqual(previousBottom - 2)
                    previousBottom = documentTop + bounds!.height
                  }
                  await expect(page.locator('[data-dashboard-section="guardrails"] [data-state]'))
                    .toHaveCount(2)
                  await page.evaluate(() => window.scrollTo(0, 0))
                }
              } else {
                await expect.poll(async () => {
                  trendBounds = await page.locator('.trend-panel').boundingBox()
                  riskBounds = await page.locator('.risk-panel').boundingBox()
                  if (!trendBounds || !riskBounds) return Number.POSITIVE_INFINITY
                  return Math.abs(trendBounds.y - riskBounds.y)
                }, { timeout: 10_000, message: `${viewport.name} 趋势/风险最终稳定顶边未对齐` })
                  .toBeLessThanOrEqual(2)
                const widthRatio = trendBounds!.width / riskBounds!.width
                expect(widthRatio, `${viewport.name} 趋势/风险双栏比例偏离 Dashboard PNG`).toBeGreaterThan(0.85)
                expect(widthRatio, `${viewport.name} 趋势/风险双栏比例偏离 Dashboard PNG`).toBeLessThan(1.25)
              }

              if (viewport.name === '1586x992') {
                // Dashboard 原型把简报压缩为一条摘要带，并让待处理表格和审批护栏在原生首屏完整可见。
                const briefBounds = await page.locator('.ai-brief').boundingBox()
                const analysisBounds = await page.locator('.dashboard-analysis').boundingBox()
                const pendingBounds = await page.locator('.pending-panel').boundingBox()
                const pendingRows = page.locator('.pending-table tbody tr')
                const guardrailBounds = await page.locator('.pending-panel__guardrail').boundingBox()
                expect(briefBounds, 'Dashboard 简报缺少首屏几何').not.toBeNull()
                expect(analysisBounds, 'Dashboard 趋势/风险区缺少首屏几何').not.toBeNull()
                expect(pendingBounds, 'Dashboard 待处理区缺少首屏几何').not.toBeNull()
                expect(guardrailBounds, 'Dashboard 审批护栏缺少首屏几何').not.toBeNull()
                expect(briefBounds!.height, 'Dashboard 简报高度偏离原型紧凑摘要带').toBeLessThanOrEqual(145)
                expect(analysisBounds!.y, 'Dashboard 趋势/风险区被简报垂直推离原生首屏').toBeLessThan(430)
                expect(pendingBounds!.y, 'Dashboard 待处理表格未进入原生首屏').toBeLessThan(750)
                await expect(pendingRows.first(), 'Dashboard 待处理表格缺少真实行').toBeVisible()
                const lastPendingRowBounds = await pendingRows.last().boundingBox()
                expect(lastPendingRowBounds, 'Dashboard 待处理表格最后一行缺少几何').not.toBeNull()
                expect(lastPendingRowBounds!.y + lastPendingRowBounds!.height, 'Dashboard 待处理表格底部被首屏裁切')
                  .toBeLessThanOrEqual(viewport.height)
                expect(guardrailBounds!.y + guardrailBounds!.height, 'Dashboard 审批护栏被首屏裁切')
                  .toBeLessThanOrEqual(viewport.height)
              }
            }

            if (route.path === '/repairs') {
              await expect(page.getByRole('region', { name: '报修列表' })).toBeVisible()
              await expect(page.getByRole('region', { name: '工单详情' })).toBeVisible()
              await expect(page.getByRole('region', { name: '维修智能分诊' })).toBeVisible()
              if (viewport.name === '1586x992' && repairCaptureOrder) {
                // The prototype capture must show the real triage result, not the initial empty state.
                const targetRow = page.getByRole('row', { name: new RegExp(repairCaptureOrder.code) }).first()
                await expect(targetRow, '视觉维修目标工单未出现在列表').toBeVisible()
                await targetRow.click()
                await expect(page.locator('.repair-description'), '维修详情未展示完整问题描述')
                  .toContainText(repairCaptureDescription)
                const attachmentThumbnails = page.locator('img[data-testid="repair-attachment-thumbnail"]')
                await expect(attachmentThumbnails, '维修原型内容态必须展示两张附件缩略图').toHaveCount(2)
                const attachmentGeometry = await attachmentThumbnails.evaluateAll((images) => images.map((element) => {
                  const image = element as HTMLImageElement
                  const bounds = image.getBoundingClientRect()
                  const surface = image.closest<HTMLElement>('.repair-attachment-thumbnail')
                  const surfaceBounds = surface?.getBoundingClientRect()
                  const style = getComputedStyle(image)
                  return {
                    complete: image.complete,
                    naturalWidth: image.naturalWidth,
                    naturalHeight: image.naturalHeight,
                    width: bounds.width,
                    height: bounds.height,
                    objectFit: style.objectFit,
                    insideSurface: Boolean(surfaceBounds)
                      && bounds.left >= surfaceBounds!.left - 1
                      && bounds.top >= surfaceBounds!.top - 1
                      && bounds.right <= surfaceBounds!.right + 1
                      && bounds.bottom <= surfaceBounds!.bottom + 1,
                    insideViewport: bounds.left >= 0
                      && bounds.top >= 0
                      && bounds.right <= window.innerWidth
                      && bounds.bottom <= window.innerHeight,
                  }
                }))
                expect(
                  attachmentGeometry.every((item) => (
                    item.complete
                    && item.naturalWidth > 0
                    && item.naturalHeight > 0
                    && item.width >= 64
                    && item.height >= 48
                  )),
                  '附件缩略图必须加载完成且具有可测量尺寸',
                ).toBe(true)
                expect(
                  attachmentGeometry.every((item) => item.insideSurface && item.insideViewport),
                  '附件缩略图媒体表面不得被容器或正式视口裁切',
                ).toBe(true)
                const triageButton = page.getByRole('button', { name: '生成分诊建议' })
                await expect(triageButton, '维修分诊内容态缺少生成入口').toBeVisible()
                await triageButton.click()
                await expect(page.locator('[data-testid="repair-confidence"]'), '维修分诊结果未在截图前生成')
                  .toBeVisible({ timeout: 60_000 })
                for (const selector of ['.repair-ai-metrics', '.repair-ai-reason', '.repair-ai-panel .ai-evidence']) {
                  await expect(page.locator(selector), `维修分诊内容态缺少 ${selector}`).toBeVisible()
                }
                await expect(page.locator('[data-testid="repair-confidence"]'), '维修分诊必须展示确定性置信依据')
                  .toHaveText('规则确定')
                await expect(page.getByRole('region', { name: '维修智能分诊' }), '维修分诊必须绑定两条授权引用')
                  .toContainText('引用来源 2')
                const repairFlow = page.getByRole('region', { name: '维修审批流程' })
                await expect(repairFlow).toContainText('AI 建议')
                await expect(repairFlow).toContainText('变更预览')
                await expect(repairFlow).toContainText('人工审批')
                const repairProposalButton = repairFlow.getByRole('button', { name: '查看审批提案' })
                await expect(repairProposalButton, '维修提案必须可进入人工审批').toBeEnabled()
                await expect(repairProposalButton, '维修提案入口必须完整显示').toBeVisible()
                const repairProposalBounds = await repairProposalButton.boundingBox()
                const repairWorkspaceBounds = await page.locator('.repair-workspace-grid').boundingBox()
                expect(repairProposalBounds, '维修提案入口缺少可测量边界').not.toBeNull()
                expect(repairWorkspaceBounds, '维修工作区缺少可测量边界').not.toBeNull()
                expect(
                  repairProposalBounds!.y + repairProposalBounds!.height,
                  '维修提案入口底边超出工作区',
                ).toBeLessThanOrEqual(repairWorkspaceBounds!.y + repairWorkspaceBounds!.height + 1)
                expect(
                  repairProposalBounds!.y + repairProposalBounds!.height,
                  '维修提案入口底边超出目标视口',
                ).toBeLessThanOrEqual(viewport.height)
                const triageState = (await page.locator('.repair-ai-panel').textContent()) ?? ''
                expect(triageState).toMatch(/分类|紧急度|建议维修组|建议维修员/)
                expect(triageState).toMatch(/SLA|置信度|理由/)
                contentStateChecks.push({
                  id: 'repair-populated-triage',
                  route: route.path,
                  viewport: viewport.name,
                  entityId: String(repairCaptureOrder.id),
                  state: 'succeeded',
                  assertions: [
                    '真实维修工单被选中',
                    '分类、紧急度、维修组和维修员可见',
                    'SLA、规则确定置信度、理由和两条授权引用可见',
                    '两张附件缩略图媒体表面完整可见且无裁切',
                    'AI 建议、变更预览和人工审批三段流程可见且提案入口可用',
                  ],
                })
              }
              if (viewport.width <= 768) {
                const mobileOrder = page.getByLabel('移动端报修列表', { exact: true }).getByRole('button').first()
                await expect(mobileOrder).toBeVisible()
                await expect(mobileOrder).toBeEnabled()
                await mobileOrder.click()
                await expect(mobileOrder).toHaveAttribute('aria-pressed', 'true')
              } else {
                await expect(page.getByRole('columnheader', { name: '报修单号' })).toBeVisible()
                await expect(page.getByRole('columnheader', { name: '报修位置' })).toBeVisible()
              }
            }

            if (route.path === '/notices/create') {
              for (const name of ['公告要点', 'AI 草稿', '内容检查']) {
                await expect(page.getByRole('region', { name })).toBeVisible()
              }
              const noticeSnapshotStatus = page.locator('.notice-diff-status')
              await expect(noticeSnapshotStatus).toContainText('最近已发布：', { timeout: 60_000 })
              businessDateChecks.push(businessDateCheck({
                viewport: viewport.name,
                route: route.path,
                surface: '公告最近已发布',
                text: await noticeSnapshotStatus.innerText(),
              }))
              if (viewport.name === '1536x1024') {
                const draft = await generateNoticeDraft(page, approvalMarker)
                await expect(page.getByRole('textbox', { name: '公告标题' })).not.toHaveValue('')
                await expect(page.getByRole('textbox', { name: 'AI 草稿正文' })).not.toHaveValue('')
                contentStateChecks.push({
                  id: 'notice-generated-draft',
                  route: route.path,
                  viewport: viewport.name,
                  entityId: draft.runId,
                  state: 'succeeded',
                  assertions: [
                    '公告草稿 command 返回 202 并绑定 runId',
                    'AI 草稿标题与正文可见',
                    '内容检查结果可见',
                  ],
                })
              }
              const titleBounds = await page.getByRole('textbox', { name: '公告标题' }).boundingBox()
              const draftBounds = await page.getByRole('textbox', { name: 'AI 草稿正文' }).boundingBox()
              expect(titleBounds, `${viewport.name} 公告标题输入框缺少可测量布局`).not.toBeNull()
              expect(draftBounds, `${viewport.name} 公告正文文本域缺少可测量布局`).not.toBeNull()
              const draftLayout = await page.locator('.notice-content-field').evaluate((root) =>
                ['.notice-content-field', '.ant-form-item-row', '.ant-form-item-label', '.ant-form-item-control', 'textarea']
                  .map((selector) => selector === '.notice-content-field' ? root : root.querySelector<HTMLElement>(selector))
                  .filter((element): element is HTMLElement => element instanceof HTMLElement)
                  .map((element) => {
                    const bounds = element.getBoundingClientRect()
                    const style = getComputedStyle(element)
                    return `${element.className || element.tagName}:x=${bounds.x.toFixed(1)},w=${bounds.width.toFixed(1)},display=${style.display},direction=${style.flexDirection},flex=${style.flex},margin=${style.margin},position=${style.position},left=${style.left},insetInline=${style.insetInlineStart},transform=${style.transform},alignSelf=${style.alignSelf}`
                  }),
              )
              const noticeGeometry = `title=x${titleBounds!.x.toFixed(1)}/w${titleBounds!.width.toFixed(1)} draft=x${draftBounds!.x.toFixed(1)}/w${draftBounds!.width.toFixed(1)} ${draftLayout.join(' | ')}`
              expect(Math.abs(draftBounds!.x - titleBounds!.x), `${viewport.name} 公告正文左边界未与标题对齐: ${noticeGeometry}`)
                .toBeLessThanOrEqual(1)
              expect(Math.abs(draftBounds!.width - titleBounds!.width), `${viewport.name} 公告正文宽度未与标题对齐: ${noticeGeometry}`)
                .toBeLessThanOrEqual(1)
            }

            if (route.path === '/ai/approvals') {
              if (viewport.name === '1536x1024') {
                const proposal = await createNoticeProposal(page, approvalMarker)
                await expect(page).toHaveURL(/\/ai\/approvals$/)
                const approvalPage = page.getByRole('main', { name: '提案详情' })
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
                const approvalActionButtons = page.locator('.approval-actions button')
                await expect(approvalActionButtons).toHaveCount(3)
                const approvalActionBounds = await approvalActionButtons.evaluateAll((buttons) => buttons.map((button) => {
                  const bounds = button.getBoundingClientRect()
                  return {
                    label: button.textContent?.trim() ?? '',
                    left: bounds.left,
                    top: bounds.top,
                    right: bounds.right,
                    bottom: bounds.bottom,
                    width: bounds.width,
                    height: bounds.height,
                  }
                }))
                expect(
                  approvalActionBounds.every((bounds) => (
                    bounds.left >= 0
                    && bounds.top >= 0
                    && bounds.right <= viewport.width
                    && bounds.bottom <= viewport.height - 8
                    && bounds.width > 0
                    && bounds.height >= 44
                  )),
                  '审批操作按钮必须完整落在正式视口内并保留至少 8px 底部间距',
                ).toBe(true)
                await expect(page.locator('.ai-proposal-row')).not.toHaveCount(0)
                await expect(page.getByText(proposal.actionType, { exact: false })).toHaveCount(0)
                contentStateChecks.push({
                  id: 'approval-pending-proposal',
                  route: route.path,
                  viewport: viewport.name,
                  entityId: proposal.id,
                  state: proposal.state,
                  assertions: [
                    '真实提案被选中',
                    '值差异与四项 guard 可见',
                    '批准、拒绝和刷新预览操作完整落在正式视口内',
                  ],
                })
              } else {
                await expect(page.locator('[data-testid="approval-proposal-rail"]')).toBeVisible()
              }
            }

            if (route.path === '/ai/risks') {
              await expect(page.getByRole('form', { name: '风险线索筛选' })).toBeVisible()
              await expect(page.getByRole('navigation', { name: '风险线索分页' })).toBeVisible()
              if (viewport.name === '1536x1024') {
                const token = await csrfToken(context, '/ai/risks')
                await createRepeatRepairOrders(context, token.token, riskMarker)
                await createHygieneAndPaymentRisks(context, token.token, riskMarker)
                const scan = await runRiskScan(context, token.token, riskMarker)
                await page.goto('/ai/risks')
                await page.waitForLoadState('networkidle')
                await expect(page.locator('[data-testid="risk-summary-card"]')).toHaveCount(4)
                await assertRiskCharts(page)
                await expect(page.locator('.rule-list > li')).toHaveCount(4)
                const riskRows = page.locator('.risk-table tbody tr').filter({ has: page.locator('td') })
                await expect(riskRows).not.toHaveCount(0)
                await riskRows.first().click()
                await expect(page.locator('.risk-table tbody tr.selected')).toHaveCount(1)
                const riskDetail = page.locator('[aria-label="风险案例详情"]')
                await expect(riskDetail).toBeVisible()
                for (const heading of ['确定性证据', 'AI 解释', '人工处置时间线']) {
                  await expect(riskDetail.getByRole('heading', { name: heading, exact: true })).toBeVisible()
                }
                await expect(riskDetail.locator('.snapshot-strip > strong')).toHaveText('授权业务快照')
                const timelineHeading = riskDetail.getByRole('heading', { name: '人工处置时间线', exact: true })
                const safetyState = riskDetail.locator('[data-testid="risk-detail-safety"]')
                const factDisclosure = riskDetail.locator('.fact-disclosure > summary')
                const actionDock = riskDetail.locator('[data-testid="risk-action-dock"]')
                const detailScroll = riskDetail.locator('[data-testid="risk-detail-scroll-region"]')
                await expect(safetyState).toBeVisible()
                await expect(factDisclosure).toBeVisible()
                await expect(actionDock).toBeVisible()
                expect(await detailScroll.evaluate((element) => element.scrollTop), 'Risk 详情截图前不得预先滚动').toBe(0)
                for (const [label, locator] of [
                  ['人工处置时间线', timelineHeading],
                  ['安全提示', safetyState],
                  ['完整证据入口', factDisclosure],
                  ['人工处置动作', actionDock],
                ] as const) {
                  const bounds = await locator.boundingBox()
                  expect(bounds, `Risk ${label} 缺少可测量布局`).not.toBeNull()
                  expect(bounds!.y + bounds!.height, `Risk ${label} 未进入 1536x1024 原生首屏`)
                    .toBeLessThanOrEqual(viewport.height)
                }
                const metricOverflow = await riskDetail.locator('.detail-metrics dd').evaluateAll((elements) => elements.map((element) => ({
                  text: element.textContent?.trim() ?? '',
                  scrollWidth: (element as HTMLElement).scrollWidth,
                  clientWidth: (element as HTMLElement).clientWidth,
                })))
                expect(metricOverflow.filter((metric) => metric.scrollWidth > metric.clientWidth + 1), 'Risk 详情指标存在横向裁切')
                  .toEqual([])
                contentStateChecks.push({
                  id: 'risk-populated-detail',
                  route: route.path,
                  viewport: viewport.name,
                  entityId: scan.id,
                  state: scan.state,
                  assertions: [
                    `真实规则信号 ${scan.signalCount}`,
                    `真实风险案例 ${scan.caseCount}`,
                    '概览、图表、规则和详情四区可见',
                    '时间线、安全提示与完整证据入口均在原生首屏',
                  ],
                })
              }
            }

            if (route.path === '/ai/audit') {
              if (viewport.name === '1536x1024') {
                const proposal = await createNoticeProposal(page, auditMarker)
                const auditDetailResponsePromise = page.waitForResponse((response) => {
                  const url = new URL(response.url())
                  return response.request().method() === 'GET'
                    && /^\/api\/ai\/audit\/runs\/[^/]+$/.test(url.pathname)
                    && response.status() === 200
                })
                await page.goto('/ai/audit')
                const auditDetailResponse = await auditDetailResponsePromise
                await page.waitForLoadState('networkidle')
                await expect(page.getByRole('form', { name: '运行审计筛选' })).toBeVisible()
                await expect(page.locator('[data-testid="audit-run-rail"]')).toBeVisible()
                const auditDetailPanel = page.locator('[data-testid="audit-detail-panel"]')
                const auditRunId = decodeURIComponent(new URL(auditDetailResponse.url()).pathname.split('/').at(-1) ?? '')
                await expect(page.locator('.audit-workbench')).toHaveAttribute('aria-busy', 'false')
                await expect(auditDetailPanel).toBeVisible()
                await expect(auditDetailPanel).toContainText(auditRunId)
                await expect(page.locator('[data-testid="audit-metrics-rail"]')).toBeVisible()
                await expect(page.locator('.ai-audit-run.selected')).toHaveCount(1)
                await expect(page.locator('.hash-chain code')).not.toHaveText('')
                await expect(page.locator('.audit-timeline li')).not.toHaveCount(0)
                await expect(page.locator('.metric-grid article')).toHaveCount(4)
                await expect(page.locator('[data-testid="audit-evidence-workbench"] .audit-evidence-group')).toHaveCount(7)
                contentStateChecks.push({
                  id: 'audit-populated-run',
                  route: route.path,
                  viewport: viewport.name,
                  entityId: proposal.id,
                  state: proposal.state,
                  assertions: [
                    '真实 proposal run 被选中',
                    'hash chain 与时间线非空',
                    '四项指标和七类事实证据可见',
                  ],
                })
              } else {
                await expect(page.getByRole('form', { name: '运行审计筛选' })).toBeVisible()
              }
            }

            const collectDesignSystem = (route.path === '/ai/knowledge' && viewport.name === '1505x1045')
              || (route.path === '/' && viewport.name === '1586x992')
              || (route.path === '/repairs' && viewport.name === '1586x992')
              || (route.path === '/ai/approvals' && viewport.name === '1536x1024')
            if (collectDesignSystem) await collectDesignSystemCheck(page, route.path, viewport.name)

            // 避免上一交互留下的桌面鼠标 hover 污染移动视口永久基线（例如用户菜单浮层）。
            await page.keyboard.press('Escape')
            await page.mouse.move(Math.max(0, viewport.width - 2), Math.max(0, viewport.height - 2))
            await expect(page.locator('.ant-dropdown:not(.ant-dropdown-hidden)')).toHaveCount(0)

            const file = `live-${route.slug}-${viewport.name}.png`
            const path = resolve(visualDirectory, file)
            await page.screenshot({ path, fullPage: true, animations: 'disabled', caret: 'hide' })
            screenshots.push(file)
            screenshotDetails.push({ ...fileBinding(file, path), route: route.path, viewport: viewport.name })

            const prototypeTarget = prototypeCaptureTargets.find((target) =>
              target.route === route.path && target.viewport === viewport.name)
            if (prototypeTarget) {
              const prototypeFile = `fidelity-${prototypeTarget.slug}-${viewport.name}.png`
              const prototypePath = resolve(visualDirectory, prototypeFile)
              await page.screenshot({ path: prototypePath, fullPage: false, animations: 'disabled', caret: 'hide' })
              prototypeCaptures.push({
                ...fileBinding(prototypeFile, prototypePath),
                prototype: prototypeTarget.prototype,
                contractSurface: prototypeTarget.contractSurface,
                route: route.path,
                viewport: viewport.name,
                contentStateIds: contentStateChecks
                  .filter((check) => check.route === route.path && check.viewport === viewport.name)
                  .map((check) => check.id),
              })
            }

            if (route.path === '/' && ['1586x992', '1366x768', '390x844'].includes(viewport.name)) {
              const assistantTrigger = page.getByRole('button', { name: /智能助手/ }).first()
              await assistantTrigger.click({ timeout: 20_000 })
              const assistant = page.getByRole('dialog', { name: /智能助手/ })
              await expect(assistant).toBeVisible()

              const assistantBounds = await assistant.boundingBox()
              expect(assistantBounds, `${viewport.name} 助手缺少可测量布局`).not.toBeNull()

              if (viewport.name === '390x844') {
                expect(assistantBounds!.x).toBeLessThanOrEqual(1)
                expect(assistantBounds!.width).toBeGreaterThanOrEqual(389)
                expect(assistantBounds!.height).toBeGreaterThanOrEqual(843)
              } else {
                expect(assistantBounds!.width, `${viewport.name} 助手 Drawer 过窄`).toBeGreaterThanOrEqual(440)
                expect(assistantBounds!.width, `${viewport.name} 助手 Drawer 过宽`).toBeLessThanOrEqual(480)
              }

              const groundedQuestion = knowledgeQuestion
              const userMessages = assistant.locator('[data-assistant-user-message]')
              const answers = assistant.locator('[data-assistant-answer]')
              const assistantFile = `live-assistant-open-${viewport.name}.png`
              const streamingAssistantFile = `live-assistant-streaming-${viewport.name}.png`
              const collectCompleteStateEvidence = assistantCompleteEvidenceViewports
                .includes(viewport.name as (typeof assistantCompleteEvidenceViewports)[number])
              let emptyScreenshotFile = ''
              let inputScreenshotFile = ''
              let streamingScreenshotFile = ''
              const assistantEvidence = await captureFreshAssistantTurnEvidence({
                resetConversation: async () => {
                  const newConversation = assistant.locator('.assistant-header').getByRole('button', { name: '新建会话' })
                  if (await newConversation.isVisible()) {
                    await newConversation.click()
                    return
                  }
                  await assistant.getByRole('button', { name: /会话历史/ }).click()
                  const history = assistant.getByRole('dialog', { name: '会话历史' })
                  await expect(history).toBeVisible()
                  await history.getByRole('button', { name: '新建会话' }).click()
                  await expect(history).toBeHidden()
                },
                assertEmptyConversation: async () => {
                  await expect(userMessages).toHaveCount(0)
                  await expect(answers).toHaveCount(0)
                  if (collectCompleteStateEvidence) {
                    await expect(assistant.locator('.assistant-empty'), '助手新会话必须呈现真实空态').toBeVisible()
                    emptyScreenshotFile = (await captureAssistantScreenshot(page, viewport.name, 'empty', 'runtime')).file
                  }
                },
                readMessageCounts: async () => ({
                  user: await userMessages.count(),
                  assistant: await answers.count(),
                }),
                sendQuestion: async () => {
                  const input = assistant.getByRole('textbox', { name: '向智能助手提问' })
                  await input.fill(groundedQuestion)
                  if (collectCompleteStateEvidence) {
                    await expect(input).toHaveValue(groundedQuestion)
                    inputScreenshotFile = (await captureAssistantScreenshot(page, viewport.name, 'input', 'runtime')).file
                  }
                  await assistant.getByRole('button', { name: /发送问题|发送/ }).click()
                },
                observeStopControl: async () => {
                  await expect(assistant.getByRole('button', { name: '停止生成' }), '助手真实生成中未出现停止控件')
                    .toBeVisible({ timeout: 20_000 })
                  await expect(assistant.getByRole('button', { name: '停止生成' }))
                    .toHaveAttribute('data-assistant-state', 'streaming-stop')
                  const streamingAnswer = answers.last()
                  await expect(streamingAnswer, '助手后段流式证据必须绑定真实 streaming DOM 状态')
                    .toHaveAttribute('data-assistant-run-state', 'streaming', { timeout: 20_000 })
                  await expect(streamingAnswer, '助手后段流式证据必须绑定真实内容状态')
                    .toHaveAttribute('data-assistant-content-state', 'streaming')
                  await expect(streamingAnswer).toHaveAttribute('data-assistant-partial', 'true')
                  await expect(streamingAnswer).toHaveAttribute('data-assistant-evidence-phase', 'provisional')
                  await expect(streamingAnswer.locator('.answer-card__text'))
                    .not.toHaveText('正在检索授权资料…')
                  expect((await streamingAnswer.locator('.answer-card__text').innerText()).trim().length,
                    '助手后段流式证据必须包含非占位部分正文').toBeGreaterThan(20)
                  await expect.poll(
                    async () => streamingAnswer.locator('[data-assistant-state="citation-available"]').count(),
                    { message: '助手后段流式证据必须包含至少一个授权引用', timeout: 20_000 },
                  ).toBeGreaterThan(0)
                  await expect(streamingAnswer.locator('[data-assistant-state="streaming-guardrail"]'),
                    '助手后段流式证据必须声明仍非最终结论').toBeVisible()
                  if (collectCompleteStateEvidence) {
                    streamingScreenshotFile = (await captureAssistantScreenshot(page, viewport.name, 'streaming', 'runtime')).file
                    expect(streamingScreenshotFile).toBe(streamingAssistantFile)
                  }
                  return true
                },
                waitForMessageCounts: async (expectedCounts) => {
                  await expect(userMessages).toHaveCount(expectedCounts.user)
                  await expect(answers).toHaveCount(expectedCounts.assistant, { timeout: 60_000 })
                },
                assertLatestTurn: async () => {
                  const userMessage = userMessages.last()
                  const answer = answers.last()
                  await expect(userMessage).toContainText(groundedQuestion)
                  await expect(answer).toContainText(/工作日|维修/, { timeout: 20_000 })
                  await expect(answer, '助手成功态必须绑定真实 succeeded DOM 状态')
                    .toHaveAttribute('data-assistant-run-state', 'succeeded', {
                      timeout: assistantTerminalEvidenceTimeoutMs,
                    })
                  await expect(answer, '助手成功态必须绑定真实内容状态')
                    .toHaveAttribute('data-assistant-content-state', 'succeeded', {
                      timeout: assistantTerminalEvidenceTimeoutMs,
                    })
                  const answerMeta = answer.locator('[data-answer-meta]')
                  const answerActions = answer.locator('[aria-label="回答操作"] button')
                  await expect(answerMeta).toHaveCount(3, { timeout: 20_000 })
                  await expect(answerActions).toHaveCount(4, { timeout: 20_000 })
                  const answerMetaCount = await answerMeta.count()
                  const answerActionCount = await answerActions.count()

                  const citationToggle = answer.getByRole('button', { name: /引用来源/ })
                  await expect(citationToggle).toBeVisible()
                  if (await citationToggle.getAttribute('aria-expanded') === 'false') await citationToggle.click()
                  const citationList = answer.getByRole('region', { name: '引用来源列表' })
                  await expect(citationList).toBeVisible()
                  const citationItems = citationList.getByRole('listitem')
                  const citationCount = await citationItems.count()
                  expect(citationCount, '助手原型内容态至少需要 1 个真实引用').toBeGreaterThanOrEqual(1)
                  const groundedText = `${await answer.textContent()} ${await citationList.textContent()}`
                  const citationTexts = await citationItems.allTextContents()
                  const groundedMarkers = citationTexts.some((text) => text.includes(knowledgeSourceLabel))
                    ? [knowledgeSourceLabel]
                    : []
                  expect(groundedMarkers, '助手引用必须绑定本轮自然语言知识来源')
                    .toEqual([knowledgeSourceLabel])
                  const internalPayloadExposed = /COMMAND:|intentSchemaVersion|DashboardQueryIntent|\{\s*"question"\s*:/i
                    .test(groundedText)
                  expect(groundedText, '助手不得显示 command conversation 标题或内部意图 payload')
                    .not.toMatch(/COMMAND:|intentSchemaVersion|DashboardQueryIntent|\{\s*"question"\s*:/i)
                  expect(groundedText, '最终用户态不得显示视觉测试 marker').not.toMatch(/VISUAL-[A-Z0-9]+/)
                  expect(
                    citationTexts.some((text) => text.includes(knowledgeSourceLabel)),
                    '助手至少一个真实引用必须绑定本轮知识来源',
                  ).toBe(true)

                  const messagesBounds = await assistant.getByRole('main', { name: '智能助手会话内容' }).boundingBox()
                  const composerBounds = await assistant.locator('.assistant-composer').boundingBox()
                  expect(messagesBounds, `${viewport.name} 助手消息区缺少可测量布局`).not.toBeNull()
                  expect(composerBounds, `${viewport.name} 助手输入区缺少可测量布局`).not.toBeNull()
                  expect(messagesBounds!.y + messagesBounds!.height, `${viewport.name} 助手消息区遮挡输入区`)
                    .toBeLessThanOrEqual(composerBounds!.y + 1)
                  expect(Math.abs(
                    composerBounds!.y + composerBounds!.height
                      - (assistantBounds!.y + assistantBounds!.height),
                  ),
                    `${viewport.name} 助手输入区未固定到底部`).toBeLessThanOrEqual(2)

                  return {
                    citationCount,
                    groundingMarkers: groundedMarkers,
                    expectedAnswerTextMatched: groundedText.includes(knowledgeSourceLabel) && /工作日|维修/.test(groundedText),
                    internalPayloadExposed,
                    answerMetaCount,
                    answerActionCount,
                  }
                },
                scrollLatestAnswer: async () => { await answers.last().scrollIntoViewIfNeeded() },
                captureScreenshot: async () => {
                  const binding = await captureAssistantScreenshot(page, viewport.name, 'open', 'runtime')
                  return { file: binding.file, bytes: binding.bytes }
                },
              }, {
                viewport: viewport.name,
                question: groundedQuestion,
                expectedGroundingMarker: knowledgeSourceLabel,
              })
              assistantTurnEvidence.push(assistantEvidence)
              const assistantRunId = await answers.last().getAttribute('data-assistant-run-id')
              expect(assistantRunId, `${viewport.name} 助手回答缺少真实 runId`).not.toBeNull()
              const succeededStateId = `assistant-${assistantRunId}-succeeded`
              const streamingStateId = `assistant-${assistantRunId}-streaming`
              contentStateChecks.push({
                id: succeededStateId,
                route: route.path,
                viewport: viewport.name,
                entityId: assistantRunId!,
                state: 'succeeded',
                assertions: ['自然语言问题、回答、元数据和授权引用可见', '用户态不含视觉测试 marker'],
                evidenceSource: 'runtime',
                screenshotFile: assistantFile,
              })

              if (collectCompleteStateEvidence) {
                expect(emptyScreenshotFile, `${viewport.name} 助手空态截图未生成`).not.toBe('')
                expect(inputScreenshotFile, `${viewport.name} 助手输入态截图未生成`).not.toBe('')
                expect(streamingScreenshotFile, `${viewport.name} 助手流式态截图未生成`).not.toBe('')
                contentStateChecks.push(
                  {
                    id: streamingStateId, route: route.path, viewport: viewport.name,
                    entityId: assistantRunId!, state: 'streaming',
                    assertions: ['真实后段 streaming 同时展示停止生成、部分正文、授权引用和 provisional 安全护栏'],
                    evidenceSource: 'runtime',
                    screenshotFile: streamingScreenshotFile,
                  },
                  {
                    id: `assistant-${assistantRunId}-empty`, route: route.path, viewport: viewport.name,
                    entityId: assistantRunId!, state: 'empty',
                    assertions: ['真实新会话消息列表为空且空态说明可见'],
                    evidenceSource: 'runtime',
                    screenshotFile: emptyScreenshotFile,
                  },
                  {
                    id: `assistant-${assistantRunId}-input`, route: route.path, viewport: viewport.name,
                    entityId: assistantRunId!, state: 'input',
                    assertions: ['真实 Composer 已填入本轮自然语言问题且尚未发送'],
                    evidenceSource: 'runtime',
                    screenshotFile: inputScreenshotFile,
                  },
                )

                prototypeCaptures.push({
                  ...fileBinding(streamingAssistantFile, resolve(visualDirectory, streamingAssistantFile)),
                  prototype: viewport.name === '1586x992'
                    ? 'ai-assistant-desktop.png'
                    : 'ai-assistant-mobile.png',
                  contractSurface: 'assistant',
                  route: route.path,
                  viewport: viewport.name,
                  contentStateIds: [streamingStateId],
                })

                const historyToggle = assistant.getByRole('button', { name: '查看会话历史' })
                await historyToggle.click()
                const history = assistant.getByRole('dialog', { name: '会话历史' })
                await expect(history, '助手真实会话历史面板未打开').toBeVisible()
                await expect(history).toContainText(/会话历史|暂无历史会话/)
                const historyFile = (await captureAssistantScreenshot(page, viewport.name, 'history', 'runtime')).file
                contentStateChecks.push({
                  id: `assistant-${assistantRunId}-history`, route: route.path, viewport: viewport.name,
                  entityId: assistantRunId!, state: 'history',
                  assertions: ['通过真实会话历史按钮打开只读历史面板'],
                  evidenceSource: 'runtime',
                  screenshotFile: historyFile,
                })
                await history.getByRole('button', { name: '关闭会话历史' }).click()
                await expect(history).toBeHidden()

                const answer = answers.last()
                const safetyFixture = await applyAssistantDeterministicFixture(page, assistantRunId!, 'safety')
                expect(safetyFixture.runId, '助手安全状态 fixture 必须绑定本轮真实 runId').toBe(assistantRunId)
                await expect(answer.locator('[data-assistant-state="citation-denied"]'), '助手缺少无权限引用状态').toBeVisible()
                await expect(assistant.locator('[data-assistant-state="no-permission"]'), '引用撤权 fixture 不应泄漏页面级权限错误').toHaveCount(0)
                await expect(answer.locator('[data-assistant-state="low-confidence"]'), '助手缺少低置信状态').toBeVisible()
                await expect(answer.locator('[data-assistant-state="no-grounded"]'), '助手缺少无可靠来源状态').toBeVisible()
                await expect(answer).toHaveAttribute('data-assistant-content-state', 'no-grounded')
                await assistant.getByRole('main', { name: '智能助手会话内容' }).evaluate((element) => {
                  element.scrollTop = element.scrollHeight
                })
                const safetyFile = (await captureAssistantScreenshot(
                  page,
                  viewport.name,
                  'safety-states',
                  'deterministic-fixture',
                )).file
                const safetyStates = ['citation-denied', 'low-confidence', 'no-grounded'] as const
                const assertionsByState: Record<(typeof safetyStates)[number], string> = {
                  'citation-denied': '已撤权引用以锁定行呈现且不显示来源正文',
                  'low-confidence': '低置信提示要求人工核验',
                  'no-grounded': '无可靠来源状态明确拒绝确定性结论',
                }
                contentStateChecks.push(...safetyStates.map((state) => ({
                  id: `assistant-${assistantRunId}-${state}`,
                  route: route.path,
                  viewport: viewport.name,
                  entityId: assistantRunId!,
                  state,
                  assertions: [assertionsByState[state], '状态由确定性 UI fixture 驱动，不冒充真实模型输出'],
                  evidenceSource: 'deterministic-fixture' as const,
                  screenshotFile: safetyFile,
                })))

                await applyAssistantDeterministicFixture(page, assistantRunId!, 'failed')
                await expect(answer).toHaveAttribute('data-assistant-content-state', 'failed')
                await expect(answer.locator('[data-assistant-state="failed"]'), '助手缺少失败态安全提示').toBeVisible()
                const failedFile = (await captureAssistantScreenshot(
                  page,
                  viewport.name,
                  'failed',
                  'deterministic-fixture',
                )).file
                contentStateChecks.push({
                  id: `assistant-${assistantRunId}-failed`, route: route.path, viewport: viewport.name,
                  entityId: assistantRunId!, state: 'failed',
                  assertions: ['确定性失败态展示安全消息和重试入口', '不冒充真实模型失败'],
                  evidenceSource: 'deterministic-fixture',
                  screenshotFile: failedFile,
                })

                await applyAssistantDeterministicFixture(page, assistantRunId!, 'canceled')
                await expect(answer).toHaveAttribute('data-assistant-content-state', 'cancelled')
                await expect(answer.locator('[data-assistant-state="cancelled"]'), '助手缺少取消态安全提示').toBeVisible()
                const canceledFile = (await captureAssistantScreenshot(
                  page,
                  viewport.name,
                  'canceled',
                  'deterministic-fixture',
                )).file
                contentStateChecks.push({
                  id: `assistant-${assistantRunId}-canceled`, route: route.path, viewport: viewport.name,
                  entityId: assistantRunId!, state: 'canceled',
                  assertions: ['确定性取消态展示已停止且保留当前内容', '不冒充真实模型取消'],
                  evidenceSource: 'deterministic-fixture',
                  screenshotFile: canceledFile,
                })

                await applyAssistantDeterministicFixture(page, assistantRunId!, 'timed_out')
                await expect(answer).toHaveAttribute('data-assistant-content-state', 'timed-out')
                await expect(answer.locator('[data-assistant-state="timed-out"]'), '助手缺少超时态安全提示').toBeVisible()
                const timedOutFile = (await captureAssistantScreenshot(
                  page,
                  viewport.name,
                  'timed-out',
                  'deterministic-fixture',
                )).file
                contentStateChecks.push({
                  id: `assistant-${assistantRunId}-timed-out`, route: route.path, viewport: viewport.name,
                  entityId: assistantRunId!, state: 'timed_out',
                  assertions: ['确定性超时态展示安全消息和重试入口', '不冒充真实模型超时'],
                  evidenceSource: 'deterministic-fixture',
                  screenshotFile: timedOutFile,
                })
              }
              await collectDesignSystemCheck(page, route.path, viewport.name)

              await assistant.getByRole('button', { name: /关闭智能助手|关闭/ }).first().click()
              await expect(assistant).toBeHidden()
              await expect(assistantTrigger).toBeFocused()
            }
            runtimeReadiness.push(await observeBackendRuntime(
              context,
              viewport.name,
              `after-route:${route.path}`,
            ))
          } finally {
            await page.close()
          }
        }
        runtimeReadiness.push(await observeBackendRuntime(context, viewport.name, 'after-routes'))
      } finally {
        await context.close()
      }
    }

    const galleryContext = await browser.newContext({
      baseURL: visualBaseURL,
      viewport: { width: 1505, height: 1045 },
      deviceScaleFactor: 1,
      reducedMotion: 'reduce',
    })
    observeContextWriteBoundary(
      galleryContext,
      designSystemGalleryTarget.viewport,
      businessWrites,
      observedServiceWorkers,
    )
    try {
      await authenticateVisualContext(galleryContext, credentials)
      runtimeReadiness.push(await observeBackendRuntime(
        galleryContext,
        designSystemGalleryTarget.viewport,
        'gallery-before',
      ))
      const galleryPage = await galleryContext.newPage()
      galleryPage.on('console', (message) => {
        if (message.type() !== 'error') return
        consoleErrors.push({
          viewport: designSystemGalleryTarget.viewport,
          route: designSystemGalleryTarget.route,
          kind: 'console-error',
          detail: sanitizeDiagnostic(message.text()),
        })
      })
      galleryPage.on('pageerror', (error) => {
        pageErrors.push({
          viewport: designSystemGalleryTarget.viewport,
          route: designSystemGalleryTarget.route,
          kind: 'page-error',
          detail: sanitizeDiagnostic(error),
        })
      })
      await gotoVisualRoute(galleryPage, designSystemGalleryTarget.route)
      await galleryPage.waitForLoadState('networkidle')
      await galleryPage.evaluate(() => document.fonts.ready)
      await expect(galleryPage.getByRole('heading', { name: 'AI 智能宿舍设计系统', exact: true })).toBeVisible()
      const gallery = galleryPage.locator('[data-design-system-gallery]')
      await expect(gallery).toBeVisible()
      await expect(galleryPage.locator('[data-gallery-section]')).toHaveCount(6)
      for (const selector of ['.ai-command-bar', '.ai-evidence', '.ai-proposal-preview', '.ai-run-status', '.ai-safety-state']) {
        await expect(galleryPage.locator(selector).first(), `${selector} 未使用真实共享组件`).toBeVisible()
      }
      const galleryEvidence = await galleryPage.evaluate((tokenNames) => {
        const root = getComputedStyle(document.documentElement)
        const galleryElement = document.querySelector<HTMLElement>('[data-design-system-gallery]')
        const targets = Array.from(document.querySelectorAll<HTMLElement>(
          '[data-design-system-gallery] button, [data-design-system-gallery] input',
        )).map((element) => element.getBoundingClientRect())
        const states = Array.from(new Set(Array.from(document.querySelectorAll<HTMLElement>('[data-control-state]'))
          .map((element) => element.dataset.controlState)
          .filter((state): state is string => Boolean(state))))
        return {
          viewportWidth: document.documentElement.clientWidth,
          documentWidth: Math.max(document.documentElement.scrollWidth, document.body?.scrollWidth ?? 0),
          galleryHeight: galleryElement?.scrollHeight ?? 0,
          minimumTargetWidth: targets.length > 0 ? Math.min(...targets.map((target) => target.width)) : 0,
          minimumTargetHeight: targets.length > 0 ? Math.min(...targets.map((target) => target.height)) : 0,
          sectionCount: document.querySelectorAll('[data-gallery-section]').length,
          states,
          fontFamily: galleryElement ? getComputedStyle(galleryElement).fontFamily : '',
          tokens: Object.fromEntries(tokenNames.map((name) => [name, root.getPropertyValue(name).trim()])),
        }
      }, [...designSystemEvidenceContract.tokens])
      const galleryFile = `fidelity-${designSystemGalleryTarget.slug}-${designSystemGalleryTarget.viewport}.png`
      const galleryPath = resolve(visualDirectory, galleryFile)
      await galleryPage.screenshot({ path: galleryPath, fullPage: false, animations: 'disabled', caret: 'hide' })
      const galleryDimensions = pngDimensions(galleryPath)
      designSystemGalleryCapture = {
        ...fileBinding(galleryFile, galleryPath),
        prototype: designSystemGalleryTarget.prototype,
        contractSurface: designSystemGalleryTarget.contractSurface,
        route: designSystemGalleryTarget.route,
        viewport: designSystemGalleryTarget.viewport,
        width: galleryDimensions.width,
        height: galleryDimensions.height,
        ...galleryEvidence,
      }
      runtimeReadiness.push(await observeBackendRuntime(
        galleryContext,
        designSystemGalleryTarget.viewport,
        'gallery-after',
      ))
    } finally {
      await galleryContext.close()
    }
  } catch (error) {
    runError = sanitizeDiagnostic(error)
  }

  for (const failure of observedApiRequestFailures) {
    const isRepeatedSuccessfulPath = successfulApiKeys.has(`${failure.method} ${failure.path}`)
    const isCompletedConnectionClose = failure.failureText === 'net::ERR_ABORTED' && isRepeatedSuccessfulPath
    const isRepeatedSuccessfulPathNoBuffer = failure.failureText === 'net::ERR_NO_BUFFER_SPACE' && isRepeatedSuccessfulPath
    if (isCompletedConnectionClose || isRepeatedSuccessfulPathNoBuffer) {
      toleratedApiRequestFailures.push(failure.issue)
    } else {
      apiRequestFailures.push(failure.issue)
    }
  }

  const overflowErrors = layoutChecks
    .filter((check) => check.overflowBy > 1)
    .map((check) => ({
      viewport: check.viewport,
      route: check.route,
      kind: 'full-page-overflow',
      detail: `document=${check.documentWidth}px viewport=${check.viewportWidth}px overflow=${check.overflowBy}px elements=${check.overflowElements.join(',')}`,
    }))
  const sourceBindingsAtEnd = collectSourceBindings()
  const prototypeBindingsAtEnd = collectPrototypeBindings()
  const sourceStabilityViolations = compareBindingSnapshots(
    'sourceBindings',
    sourceBindingsAtStart,
    sourceBindingsAtEnd,
  )
  const prototypeStabilityViolations = compareBindingSnapshots(
    'prototypeBindings',
    prototypeBindingsAtStart,
    prototypeBindingsAtEnd,
  )
  const bindingStabilityErrors = [
    ...sourceStabilityViolations,
    ...prototypeStabilityViolations,
  ].map((detail) => ({ viewport: 'run', route: 'run', kind: 'binding-stability', detail }))
  const runtimeErrors = [
    ...apiErrors,
    ...apiRequestFailures,
    ...consoleErrors,
    ...pageErrors,
    ...overflowErrors,
    ...bindingStabilityErrors,
    ...(runError ? [{ viewport: 'run', route: 'run', kind: 'run-error', detail: runError }] : []),
  ]
  const manifest = {
    generatedAt: new Date().toISOString(),
    referenceInstant: visualReferenceInstant.toISOString(),
    referenceBusinessDate: visualReferenceBusinessDate,
    businessTimeZone: visualBusinessTimeZone,
    backendVersion: `Spring Boot ${springBootVersion}`,
    baseURL: visualBaseURL,
    backend: visualBackendURL,
    isolation: aiLiveIsolation.evidence,
    viewports,
    routes,
    sourceBindings: sourceBindingsAtStart,
    sourceBindingsAtEnd,
    sourceStabilityViolations,
    prototypeContracts,
    prototypeBindings: prototypeBindingsAtStart,
    prototypeBindingsAtEnd,
    prototypeStabilityViolations,
    prototypeCaptures,
    designSystemChecks,
    designSystemGalleryCapture,
    screenshotCount: screenshots.length,
    screenshots,
    screenshotDetails,
    assistantScreenshots,
    assistantScreenshotDetails,
    assistantTurnEvidence,
    contentStateChecks,
    apiErrors,
    apiRequestFailures,
    toleratedApiRequestFailures,
    consoleErrors,
    pageErrors,
    layoutChecks,
    shellChecks,
    canvasChecks,
    businessDateChecks,
    dashboardMobileGeometryChecks,
    runtimeReadiness,
    businessWrites,
    observedServiceWorkers,
    runtimeErrors,
  }
  writeFileSync(visualManifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')

  const initiallyPersisted = JSON.parse(readFileSync(visualManifestPath, 'utf8')) as typeof manifest
  const persistedBindingVerification: PersistedBindingVerification = {
    verifiedAt: new Date().toISOString(),
    ...verifyPersistedBindings(initiallyPersisted),
  }
  const manifestWithVerification = { ...initiallyPersisted, persistedBindingVerification }
  writeFileSync(visualManifestPath, `${JSON.stringify(manifestWithVerification, null, 2)}\n`, 'utf8')

  const persisted = JSON.parse(readFileSync(visualManifestPath, 'utf8')) as typeof manifestWithVerification
  const recomputedPersistedBindings = verifyPersistedBindings(persisted)
  const expectedScreenshotCount = routes.length * viewports.length
  expect(runError, '真实视觉套件中途失败，详情已脱敏写入 manifest').toBe('')
  expect(validateVisualManifestEvidence(persisted, {
    assistantViewports: assistantEvidenceViewports,
    requiredContentStateIds,
    requiredAssistantPrototypeStates,
    forbiddenAssistantPrototypeStates,
    assistantEvidenceStateViewports: assistantCompleteEvidenceViewports,
    requiredAssistantEvidenceStates,
    expectedAssistantScreenshotFiles,
  }), '结构化视觉证据合同失败').toEqual([])
  expect(persisted.sourceStabilityViolations, '正式截图期间源码发生漂移').toEqual([])
  expect(persisted.prototypeStabilityViolations, '正式截图期间原型 PNG 发生漂移').toEqual([])
  expect(persisted.prototypeBindings).toHaveLength(prototypeContracts.length)
  expect(persisted.prototypeBindingsAtEnd).toHaveLength(prototypeContracts.length)
  expect(new Set(persisted.prototypeBindings.map(({ file }) => file)))
    .toEqual(new Set(prototypeContracts.map(({ file }) => file)))
  expect(persisted.screenshotCount).toBe(expectedScreenshotCount)
  expect(persisted.screenshots).toHaveLength(expectedScreenshotCount)
  expect(new Set(persisted.screenshots).size).toBe(expectedScreenshotCount)
  expect(persisted.screenshotDetails).toHaveLength(expectedScreenshotCount)
  expect(new Set(persisted.screenshotDetails.map(({ file }) => file))).toEqual(new Set(persisted.screenshots))
  for (const file of persisted.screenshots) {
    const path = resolve(visualDirectory, file)
    expect(existsSync(path), `${file} 未生成`).toBe(true)
    const stats = statSync(path)
    expect(stats.size, `${file} 是空文件`).toBeGreaterThan(1_024)
    expect(stats.mtimeMs, `${file} 未在本次运行中更新`).toBeGreaterThanOrEqual(startedAt - 2_000)
  }
  expect([...persisted.assistantScreenshots].sort(), '助手截图精确集合与正式状态合同不一致')
    .toEqual([...expectedAssistantScreenshotFiles].sort())
  expect(new Set(persisted.assistantScreenshots).size).toBe(expectedAssistantScreenshotFiles.length)
  expect(persisted.assistantScreenshotDetails).toHaveLength(expectedAssistantScreenshotFiles.length)
  expect(new Set(persisted.assistantScreenshotDetails.map(({ file }) => file)))
    .toEqual(new Set(persisted.assistantScreenshots))
  for (const file of persisted.assistantScreenshots) {
    const path = resolve(visualDirectory, file)
    expect(existsSync(path), `${file} 未生成`).toBe(true)
    const stats = statSync(path)
    expect(stats.size, `${file} 是空文件`).toBeGreaterThan(1_024)
    expect(stats.mtimeMs, `${file} 未在本次运行中更新`).toBeGreaterThanOrEqual(startedAt - 2_000)
  }
  expect(persisted.prototypeCaptures).toHaveLength(prototypeCaptureTargets.length + 2)
  expect(new Set(persisted.prototypeCaptures.map((capture) => capture.file)).size)
    .toBe(prototypeCaptureTargets.length + 2)
  expect(new Set(persisted.prototypeCaptures.map((capture) => capture.prototype)))
    .toEqual(new Set(prototypeContracts
      .filter((contract) => contract.evidenceMode === 'screenshot-and-content-state')
      .map((contract) => contract.file)))
  const captureStateIds = (route: string, viewport: string) => persisted.prototypeCaptures
    .find((capture) => capture.route === route && capture.viewport === viewport)?.contentStateIds ?? []
  expect(captureStateIds('/repairs', '1586x992'), '维修原型截图未绑定真实分诊内容态')
    .toContain('repair-populated-triage')
  expect(captureStateIds('/notices/create', '1536x1024'), '公告原型截图未绑定真实草稿内容态')
    .toContain('notice-generated-draft')
  for (const contract of prototypeContracts) {
    const path = resolve(prototypeDirectory, contract.file)
    expect(existsSync(path), `${contract.file} 视觉合同不存在`).toBe(true)
    expect(statSync(path).size, `${contract.file} 视觉合同为空`).toBeGreaterThan(1_024)
    expect(pngDimensions(path), `${contract.file} 原生尺寸漂移`).toEqual({
      width: contract.nativeWidth,
      height: contract.nativeHeight,
    })
  }
  for (const capture of persisted.prototypeCaptures) {
    const path = resolve(visualDirectory, capture.file)
    expect(existsSync(path), `${capture.file} 未生成`).toBe(true)
    expect(statSync(path).size, `${capture.file} 是空文件`).toBeGreaterThan(1_024)
    expect(statSync(path).mtimeMs, `${capture.file} 未在本次运行中更新`).toBeGreaterThanOrEqual(startedAt - 2_000)
    const viewport = viewports.find((candidate) => candidate.name === capture.viewport)
    expect(viewport, `${capture.file} 的视口未登记`).toBeDefined()
    expect(pngDimensions(path), `${capture.file} 首屏尺寸不等于逻辑视口`).toEqual({
      width: viewport!.width,
      height: viewport!.height,
    })
  }
  expect(persisted.designSystemGalleryCapture, '缺少独立 Design System 状态画廊截图').not.toBeNull()
  const galleryCapture = persisted.designSystemGalleryCapture!
  const galleryCapturePath = resolve(visualDirectory, galleryCapture.file)
  expect(existsSync(galleryCapturePath), `${galleryCapture.file} 未生成`).toBe(true)
  expect(statSync(galleryCapturePath).size, `${galleryCapture.file} 是空文件`).toBeGreaterThan(1_024)
  expect(statSync(galleryCapturePath).mtimeMs, `${galleryCapture.file} 未在本次运行中更新`)
    .toBeGreaterThanOrEqual(startedAt - 2_000)
  expect(pngDimensions(galleryCapturePath)).toEqual({ width: 1505, height: 1045 })
  expect(persisted.prototypeCaptures.some((capture) => capture.prototype === 'ai-design-system.png')).toBe(false)
  expect(persisted.designSystemChecks.length, '缺少代表性页面的设计系统运行证据').toBeGreaterThanOrEqual(5)
  expect(persisted.designSystemChecks.every((check) => (
    designSystemEvidenceContract.tokens.every((token) => Boolean(check.tokens[token]?.trim()))
    && designSystemEvidenceContract.computedStyles.every((name) => Boolean(check.computedStyles[name]?.trim()))
  ))).toBe(true)
  for (const semantic of designSystemEvidenceContract.stateSemantics) {
    expect(persisted.designSystemChecks.some((check) => check.stateSemantics[semantic] === true),
      `缺少状态语义 ${semantic} 的真实浏览器证据`).toBe(true)
  }
  for (const component of designSystemEvidenceContract.sharedComponents) {
    expect(persisted.designSystemChecks.some((check) => check.sharedComponents[component] === true),
      `缺少共用 AI 组件 ${component} 的真实浏览器证据`).toBe(true)
  }
  expect(persisted.apiErrors, '真实 API 出现 4xx/5xx').toEqual([])
  expect(persisted.apiRequestFailures, '真实 API 出现网络失败').toEqual([])
  expect(persisted.consoleErrors, '页面出现 console.error').toEqual([])
  expect(persisted.pageErrors, '页面出现 pageerror').toEqual([])
  expect(overflowErrors, '存在全页水平溢出').toEqual([])
  expect(persisted.layoutChecks).toHaveLength(expectedScreenshotCount)
  expect(persisted.shellChecks).toHaveLength(expectedScreenshotCount)
  expect(persisted.shellChecks.every((check) => check.brand === '学生宿舍管理系统')).toBe(true)
  expect(persisted.canvasChecks).toHaveLength(viewports.length)
  expect(persisted.canvasChecks.every((check) => check.canvasCount > 0 && check.renderedCanvasCount > 0)).toBe(true)
  expect(persisted.referenceInstant).toBe(visualReferenceInstant.toISOString())
  expect(persisted.referenceBusinessDate).toBe(visualReferenceBusinessDate)
  expect(persisted.businessTimeZone).toBe(visualBusinessTimeZone)
  expect(persisted.businessDateChecks).toHaveLength(viewports.length * 2)
  expect(persisted.businessDateChecks.every((check) => (
    check.referenceInstant === visualReferenceInstant.toISOString()
    && check.referenceDate === visualReferenceBusinessDate
    && check.timeZone === visualBusinessTimeZone
    && check.dates.length > 0
    && check.dates.every((date) => date <= visualReferenceBusinessDate)
  )), '正式视觉存在 reference instant 之后的可见业务日期').toBe(true)
  expect(persisted.dashboardMobileGeometryChecks).toEqual([
    expect.objectContaining({
      viewport: '390x844',
      route: '/',
      sparseStateVisible: true,
      degradedStateVisible: true,
    }),
  ])
  const dashboardMobileGeometry = persisted.dashboardMobileGeometryChecks[0]!
  expect(dashboardMobileGeometry.pendingTop).toBeLessThanOrEqual(824)
  expect(dashboardMobileGeometry.documentHeight).toBeLessThanOrEqual(1320)
  expect(dashboardMobileGeometry.documentWidth).toBeLessThanOrEqual(dashboardMobileGeometry.viewportWidth)
  expect(dashboardMobileGeometry.trendLegendCount).toBe(2)
  expect(dashboardMobileGeometry.trendLegendTexts).toEqual(['入住办理（人次）', '累计入住（人次）'])
  expect(dashboardMobileGeometry.trendLegendsOverlap).toBe(false)
  expect(dashboardMobileGeometry.vacancyDuplicateVisible).toBe(false)
  const expectedReadinessObservationCount = viewports.length * (routes.length + 2) + 2
  expect(persisted.runtimeReadiness).toHaveLength(expectedReadinessObservationCount)
  expect(persisted.runtimeReadiness.every((observation) => (
    observation.providerAlias === 'fake'
    && observation.writeExecutionEnabled === false
    && observation.masterEnabled === true
    && observation.streamingEnabled === true
  ))).toBe(true)
  for (const viewport of viewports) {
    const phases = persisted.runtimeReadiness
      .filter((observation) => observation.viewport === viewport.name)
      .map((observation) => observation.phase)
    expect(phases).toContain('before-routes')
    expect(phases).toContain('after-routes')
    for (const route of routes) expect(phases).toContain(`after-route:${route.path}`)
  }
  expect(persisted.runtimeReadiness.some((observation) => observation.phase === 'gallery-before')).toBe(true)
  expect(persisted.runtimeReadiness.some((observation) => observation.phase === 'gallery-after')).toBe(true)
  expect(persisted.businessWrites, '正式视觉浏览过程不得触发非 AI 业务写').toEqual([])
  expect(persisted.observedServiceWorkers, '正式视觉浏览过程不得注册未审计 Service Worker').toEqual([])
  expect(recomputedPersistedBindings).toEqual({
    sourceBindings: persisted.persistedBindingVerification.sourceBindings,
    sourceBindingsAtEnd: persisted.persistedBindingVerification.sourceBindingsAtEnd,
    prototypeBindings: persisted.persistedBindingVerification.prototypeBindings,
    prototypeBindingsAtEnd: persisted.persistedBindingVerification.prototypeBindingsAtEnd,
    screenshotDetails: persisted.persistedBindingVerification.screenshotDetails,
    assistantScreenshotDetails: persisted.persistedBindingVerification.assistantScreenshotDetails,
    prototypeCaptures: persisted.persistedBindingVerification.prototypeCaptures,
    designSystemGalleryCapture: persisted.persistedBindingVerification.designSystemGalleryCapture,
    pngFileSet: persisted.persistedBindingVerification.pngFileSet,
  })
  expect(Object.values(recomputedPersistedBindings).flatMap(({ violations }) => violations),
    'manifest 落盘后的源码与 PNG 绑定复算失败').toEqual([])
  expect(persisted.runtimeErrors).toEqual([])
})
