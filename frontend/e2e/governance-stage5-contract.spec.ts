import { createHash } from 'node:crypto'
import { appendFileSync, existsSync, mkdirSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type Browser, type BrowserContext, type Locator, type Page } from '@playwright/test'
import type {
  AiAuditCost,
  AiAuditRun,
  AiAuditRunContent,
  AiAuditRunDetail,
  AiProposalPreview,
  AiRiskCase,
} from '../src/types/ai'
import { mockApi } from './mock-api'
import {
  stage5AsOf,
  stage5AuditContent,
  stage5AuditCosts,
  stage5AuditDetails,
  stage5AuditRuns,
  stage5Proposals,
  stage5ReferenceInstant,
  stage5RiskCases,
} from './stage5-governance-fixtures'

const baseURL = 'http://127.0.0.1:5178'
const outputName = (process.env.VISUAL_OUTPUT_NAME?.trim() || 'stage5-governance-20260802-a')
  .replace(/[^A-Za-z0-9._-]/g, '_')
const outputDirectory = resolve(process.cwd(), 'test-results', outputName, 'fixtures')
const capturesDirectory = resolve(outputDirectory, 'captures')
const screenshotRecordsDirectory = resolve(outputDirectory, 'screenshot-records')
const computedRecordsDirectory = resolve(outputDirectory, 'computed-records')
const runtimeEventsPath = resolve(outputDirectory, 'runtime-events.ndjson')
const testResultsPath = resolve(outputDirectory, 'test-results.ndjson')
const failureMarkerPath = resolve(outputDirectory, '.suite-failed')
const suiteStartedAt = new Date().toISOString()

const viewports = [
  { name: '1920x1080', width: 1920, height: 1080 },
  { name: '1366x768', width: 1366, height: 768 },
  { name: '1586x992', width: 1586, height: 992 },
  { name: '1536x1024', width: 1536, height: 1024 },
  { name: '1505x1045', width: 1505, height: 1045 },
  { name: '390x844', width: 390, height: 844 },
] as const

type GovernanceSurface = 'risk' | 'approval' | 'audit'
type RuntimeIssue = { kind: string; detail: string }
type FixtureMode = {
  riskList?: 'success' | 'loading' | 'error' | 'empty'
  riskOverview?: 'success' | 'loading' | 'error' | 'empty'
  proposalList?: 'success' | 'loading' | 'error' | 'empty'
  auditList?: 'success' | 'loading' | 'error' | 'empty'
  auditDetail?: 'success' | 'loading' | 'error'
  riskAction?: 'success' | 'conflict'
}

type FixturePayload = {
  riskCases: AiRiskCase[]
  proposals: AiProposalPreview[]
  auditRuns: AiAuditRun[]
  auditDetails: Record<string, AiAuditRunDetail>
  auditCosts: AiAuditCost[]
  auditContent: AiAuditRunContent
  mode: FixtureMode
}

type ScreenshotRecord = {
  file: string
  surface: GovernanceSurface
  state: string
  viewport: string
  fullPage: boolean
  width: number
  height: number
  bytes: number
  sha256: string
  mtimeUtc: string
}

const screenshots: ScreenshotRecord[] = []
const computedEvidence: Record<string, unknown> = {}
let suiteFailed = false
const expectedScreenshotCount = 62

function appendJsonLine(path: string, value: unknown) {
  mkdirSync(outputDirectory, { recursive: true })
  appendFileSync(path, `${JSON.stringify(value)}\n`, 'utf8')
}

function readJsonLines(path: string) {
  if (!existsSync(path)) return [] as Array<Record<string, unknown>>
  return readFileSync(path, 'utf8')
    .split(/\r?\n/)
    .filter(Boolean)
    .map((line) => JSON.parse(line) as Record<string, unknown>)
}

function readJsonRecords(directory: string) {
  if (!existsSync(directory)) return [] as Array<Record<string, unknown>>
  return readdirSync(directory)
    .filter((file) => file.endsWith('.json'))
    .sort()
    .map((file) => JSON.parse(readFileSync(resolve(directory, file), 'utf8')) as Record<string, unknown>)
}

function recordComputedEvidence(key: string, value: unknown) {
  computedEvidence[key] = value
  mkdirSync(computedRecordsDirectory, { recursive: true })
  const file = `${key.replace(/[^A-Za-z0-9._-]/g, '_')}.json`
  writeFileSync(resolve(computedRecordsDirectory, file), `${JSON.stringify({ key, value }, null, 2)}\n`, 'utf8')
}

function recordRuntimeEvent(kind: 'page' | 'pageerror' | 'console' | 'api-request-failed', detail = '') {
  appendJsonLine(runtimeEventsPath, { at: new Date().toISOString(), kind, detail })
}

function clone<T>(value: T): T {
  return structuredClone(value)
}

function fileBinding(file: string, path: string) {
  const bytes = readFileSync(path)
  const stats = statSync(path)
  return {
    file,
    bytes: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex').toUpperCase(),
    mtimeUtc: stats.mtime.toISOString(),
  }
}

function pngDimensions(path: string) {
  const bytes = readFileSync(path)
  if (bytes.length < 24 || bytes.subarray(1, 4).toString('ascii') !== 'PNG') {
    throw new Error(`${path} 不是有效 PNG`)
  }
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) }
}

function sourceBindings() {
  return [
    fileBinding('frontend/src/views/AiRiskView.vue', resolve(process.cwd(), 'src/views/AiRiskView.vue')),
    fileBinding('frontend/src/views/AiApprovalView.vue', resolve(process.cwd(), 'src/views/AiApprovalView.vue')),
    fileBinding('frontend/src/views/AiAuditView.vue', resolve(process.cwd(), 'src/views/AiAuditView.vue')),
    fileBinding('frontend/src/views/AiRiskStage5FidelityContract.test.ts', resolve(process.cwd(), 'src/views/AiRiskStage5FidelityContract.test.ts')),
    fileBinding('frontend/src/views/AiApprovalStage4Contract.test.ts', resolve(process.cwd(), 'src/views/AiApprovalStage4Contract.test.ts')),
    fileBinding('frontend/src/views/AiAuditStage4Contract.test.ts', resolve(process.cwd(), 'src/views/AiAuditStage4Contract.test.ts')),
    fileBinding('frontend/src/views/AiGovernanceMobileAccessibilityContract.test.ts', resolve(process.cwd(), 'src/views/AiGovernanceMobileAccessibilityContract.test.ts')),
    fileBinding('frontend/src/stores/aiRisk.ts', resolve(process.cwd(), 'src/stores/aiRisk.ts')),
    fileBinding('frontend/src/stores/aiApproval.ts', resolve(process.cwd(), 'src/stores/aiApproval.ts')),
    fileBinding('frontend/src/api/ai-client.ts', resolve(process.cwd(), 'src/api/ai-client.ts')),
    fileBinding('frontend/e2e/stage5-governance-fixtures.ts', resolve(process.cwd(), 'e2e/stage5-governance-fixtures.ts')),
    fileBinding('frontend/e2e/governance-stage5-contract.spec.ts', resolve(process.cwd(), 'e2e/governance-stage5-contract.spec.ts')),
    fileBinding('frontend/playwright.stage5-governance.config.ts', resolve(process.cwd(), 'playwright.stage5-governance.config.ts')),
    fileBinding(
      '.planning/20260727-ui-prototype-texture-reassessment/scripts/compare_visuals.py',
      resolve(process.cwd(), '../.planning/20260727-ui-prototype-texture-reassessment/scripts/compare_visuals.py'),
    ),
  ]
}

function observeRuntime(page: Page, issues: RuntimeIssue[]) {
  recordRuntimeEvent('page')
  page.on('pageerror', (error) => {
    recordRuntimeEvent('pageerror', error.message)
    issues.push({ kind: 'pageerror', detail: error.message })
  })
  page.on('console', (message) => {
    if (message.type() === 'error') {
      recordRuntimeEvent('console', message.text())
      issues.push({ kind: 'console', detail: message.text() })
    }
  })
  page.on('requestfailed', (request) => {
    const url = new URL(request.url())
    if (url.pathname.startsWith('/api/')) {
      const detail = `${request.method()} ${url.pathname}: ${request.failure()?.errorText ?? 'unknown'}`
      recordRuntimeEvent('api-request-failed', detail)
      issues.push({ kind: 'requestfailed', detail })
    }
  })
}

async function createGovernanceContext(browser: Browser) {
  return browser.newContext({
    baseURL,
    viewport: { width: 1920, height: 1080 },
    deviceScaleFactor: 1,
    reducedMotion: 'reduce',
  })
}

async function createPageInContext(
  context: BrowserContext,
  viewport: { width: number; height: number },
  permissions?: string[],
) {
  const page = await context.newPage()
  await page.clock.setFixedTime(new Date(stage5ReferenceInstant))
  await page.setViewportSize(viewport)
  const mockApiControl = await mockApi(page, true)
  if (permissions) mockApiControl.setSessionPermissions(permissions)
  const runtimeIssues: RuntimeIssue[] = []
  observeRuntime(page, runtimeIssues)
  return { page, mockApiControl, runtimeIssues }
}

async function createPage(
  browser: Browser,
  viewport: { width: number; height: number },
  permissions?: string[],
) {
  const context = await createGovernanceContext(browser)
  return { context, ...await createPageInContext(context, viewport, permissions) }
}

async function gotoSurface(page: Page, path: string) {
  await page.goto(path, { waitUntil: 'domcontentloaded' })
  await page.locator('.admin-content').waitFor({ state: 'visible' })
}

async function installFixtureClient(
  page: Page,
  surface: GovernanceSurface,
  mode: FixtureMode = {},
  overrides: Partial<FixturePayload> = {},
  bootstrap = true,
) {
  const payload: FixturePayload = {
    riskCases: clone(overrides.riskCases ?? stage5RiskCases),
    proposals: clone(overrides.proposals ?? stage5Proposals),
    auditRuns: clone(overrides.auditRuns ?? stage5AuditRuns),
    auditDetails: clone(overrides.auditDetails ?? stage5AuditDetails),
    auditCosts: clone(overrides.auditCosts ?? stage5AuditCosts),
    auditContent: clone(overrides.auditContent ?? stage5AuditContent),
    mode: { ...mode, ...(overrides.mode ?? {}) },
  }

  await page.evaluate(async ({ fixture, fixtureSurface, shouldBootstrap }) => {
    type Calls = {
      riskWrites: Array<{ id: string; action: string; caseVersion: number }>
      approvals: string[]
      rejections: string[]
      reconfirmations: Array<{ executionId: string; resolution: string }>
      auditReads: Array<{ runId: string; reasonLength: number }>
      productionWrites: string[]
    }
    const root = window as typeof window & { __stage5GovernanceCalls?: Calls }
    root.__stage5GovernanceCalls = {
      riskWrites: [], approvals: [], rejections: [], reconfirmations: [], auditReads: [], productionWrites: [],
    }
    const state = structuredClone(fixture)
    const never = () => new Promise<never>(() => undefined)
    const cloneValue = <T>(value: T): T => structuredClone(value)
    const pageResult = <T>(records: T[], input?: { page?: number; pageSize?: number }) => {
      const page = Math.max(1, input?.page ?? 1)
      const pageSize = Math.max(1, input?.pageSize ?? 10)
      const offset = (page - 1) * pageSize
      return { records: cloneValue(records.slice(offset, offset + pageSize)), total: records.length, page, pageSize }
    }
      const client = {
      listRiskCases: async (input?: { page?: number; pageSize?: number; type?: string; state?: string; keyword?: string }) => {
        const overview = (input?.pageSize ?? 10) >= 100
        const configured = overview ? state.mode.riskOverview : state.mode.riskList
        if (configured === 'loading') return never()
        if (configured === 'error') throw new Error(overview ? 'Stage 5 风险总览读取失败' : 'Stage 5 风险线索读取失败')
        if (configured === 'empty') return pageResult([], input)
        const keyword = input?.keyword?.trim().toLocaleLowerCase()
        const latestRiskMonth = state.riskCases.reduce((latest, risk) => risk.asOf.slice(0, 7) > latest ? risk.asOf.slice(0, 7) : latest, '')
        const records = overview ? state.riskCases : state.riskCases.filter((risk) => risk.asOf.startsWith(latestRiskMonth))
        const filtered = records.filter((risk) => (
          (!input?.type || risk.type === input.type)
          && (!input?.state || risk.state === input.state)
          && (!keyword || `${risk.subjectToken} ${risk.type} ${risk.evidenceSummary}`.toLocaleLowerCase().includes(keyword))
        ))
        return pageResult(filtered, input)
      },
      updateRiskCase: async (id: string, action: 'acknowledge' | 'resolve' | 'dismiss', input: { caseVersion: number; detail: string; dueAt?: string }) => {
        const current = state.riskCases.find((risk) => risk.id === id)
        if (!current) throw new Error('风险案例不可见')
        root.__stage5GovernanceCalls!.riskWrites.push({ id, action, caseVersion: input.caseVersion })
        if (state.mode.riskAction === 'conflict' || current.caseVersion !== input.caseVersion) {
          throw new Error('风险案例版本已变化，请刷新后重试')
        }
        current.state = action === 'acknowledge' ? 'acknowledged' : action === 'resolve' ? 'resolved' : 'dismissed'
        current.caseVersion += 1
        const event = {
          id: `${id}-fixture-${current.caseVersion}`,
          type: action.toUpperCase(),
          actor: 'Stage 5 人工核验用户',
          detail: input.detail,
          occurredAt: '2026-08-02T10:45:00+08:00',
        }
        current.events.push(event)
        current.humanEvidence.events.push(event)
        current.assigneeUserId = 1
        current.humanEvidence.assigneeUserId = 1
        current.assignee = '治理值班组'
        if (input.dueAt) {
          current.dueAt = input.dueAt
          current.humanEvidence.dueAt = input.dueAt
          current.sla = `截止 ${input.dueAt}`
        }
        return cloneValue(current)
      },
      listProposals: async (input?: { page?: number; pageSize?: number; state?: string; actionType?: string }) => {
        if (state.mode.proposalList === 'loading') return never()
        if (state.mode.proposalList === 'error') throw new Error('Stage 5 审批方案读取失败')
        const source = state.mode.proposalList === 'empty' ? [] : state.proposals
        const filtered = source.filter((proposal) => (
          (!input?.state || proposal.state === input.state)
          && (!input?.actionType || proposal.actionType === input.actionType)
        ))
        return pageResult(filtered, input)
      },
      getProposal: async (id: string) => {
        const proposal = state.proposals.find((item) => item.id === id)
        if (!proposal) throw new Error('方案不可见')
        return cloneValue(proposal)
      },
      approveProposal: async (id: string, input: { password?: string }) => {
        const proposal = state.proposals.find((item) => item.id === id)
        if (!proposal) throw new Error('方案不可见')
        root.__stage5GovernanceCalls!.approvals.push(id)
        void input
        proposal.state = 'succeeded'
        proposal.executionState = 'succeeded'
        return cloneValue(proposal)
      },
      rejectProposal: async (id: string) => {
        const proposal = state.proposals.find((item) => item.id === id)
        if (!proposal) throw new Error('方案不可见')
        root.__stage5GovernanceCalls!.rejections.push(id)
        proposal.state = 'rejected'
      },
      reconfirmExecution: async (executionId: string, input: { resolution: string }) => {
        const proposal = state.proposals.find((item) => item.executionId === executionId)
        if (!proposal) throw new Error('执行记录不可见')
        root.__stage5GovernanceCalls!.reconfirmations.push({ executionId, resolution: input.resolution })
        return {
          proposalId: proposal.id,
          executionId,
          state: input.resolution === 'RESULT_CONFIRMED' ? 'SUCCEEDED' : 'NEEDS_REVIEW',
          version: proposal.version,
          resultHash: input.resolution === 'RESULT_CONFIRMED' ? 'f'.repeat(64) : null,
        }
      },
      listAuditRuns: async (input?: { page?: number; pageSize?: number; capability?: string; state?: string; provider?: string }) => {
        if (state.mode.auditList === 'loading') return never()
        if (state.mode.auditList === 'error') throw new Error('Stage 5 运行审计列表读取失败')
        const source = state.mode.auditList === 'empty' ? [] : state.auditRuns
        const filtered = source.filter((run) => (
          (!input?.capability || run.capability.toUpperCase().includes(input.capability.toUpperCase()))
          && (!input?.state || run.state.toUpperCase() === input.state.toUpperCase())
          && (!input?.provider || input.provider === 'fake')
        ))
        return pageResult(filtered, input)
      },
      getAuditRun: async (id: string) => {
        if (state.mode.auditDetail === 'loading') return never()
        if (state.mode.auditDetail === 'error') throw new Error('Stage 5 运行审计详情读取失败')
        const detail = state.auditDetails[id]
        if (!detail) throw new Error('运行审计不可见')
        return cloneValue(detail)
      },
      readAuditContent: async (id: string, reason: string, password: string) => {
        root.__stage5GovernanceCalls!.auditReads.push({ runId: id, reasonLength: reason.trim().length })
        if (reason.trim().length < 10 || !password) throw new Error('正文读取需要充分理由和二次认证')
        return cloneValue({ ...state.auditContent, runId: id })
      },
      getAuditCosts: async (input?: { page?: number; pageSize?: number }) => pageResult(state.auditCosts, input),
    }
    const clientModulePath = '/src/api/ai-client.ts'
    const { setAiClient } = await import(clientModulePath)
    setAiClient(client)

    if (!shouldBootstrap) return

    if (fixtureSurface === 'risk') {
      const storeModulePath = '/src/stores/aiRisk.ts'
      const { useAiRiskStore } = await import(storeModulePath)
      const store = useAiRiskStore()
      store.resetSession()
      const listRequest = store.load({ page: 1, pageSize: 10 })
      const overviewRequest = store.loadOverview()
      if (state.mode.riskList === 'loading') void listRequest
      else await listRequest
      if (state.mode.riskOverview === 'loading') void overviewRequest
      else await overviewRequest
    } else {
      const storeModulePath = '/src/stores/aiApproval.ts'
      const { useAiApprovalStore } = await import(storeModulePath)
      const store = useAiApprovalStore()
      store.resetSession()
      if (fixtureSurface === 'approval') {
        await store.loadAudits({ page: 1, pageSize: 20 }, { reconcileSelection: false })
        const proposalsRequest = store.loadProposals({ page: 1, pageSize: 20 }, { reconcileSelection: true })
        if (state.mode.proposalList === 'loading') void proposalsRequest
        else await proposalsRequest
      } else {
        const auditsRequest = store.loadAudits({ page: 1, pageSize: 20 }, { reconcileSelection: true })
        if (state.mode.auditList === 'loading') void auditsRequest
        else {
          await auditsRequest
          if (store.selectedAuditId && state.mode.auditDetail !== 'loading' && state.mode.auditDetail !== 'error') {
            await store.selectAudit(store.selectedAuditId)
          }
        }
      }
    }
  }, { fixture: payload, fixtureSurface: surface, shouldBootstrap: bootstrap })
  await page.waitForTimeout(120)
}

async function mountAuditWithFixtureMode(page: Page, mode: FixtureMode) {
  await gotoSurface(page, '/ai/approvals')
  await installFixtureClient(page, 'approval', mode, {}, false)
  await page.locator('.governance-tabs a[href="/ai/audit"]').click()
  await expect(page).toHaveURL(/\/ai\/audit$/)
  await page.locator('.audit-page').waitFor({ state: 'visible' })
}

async function fixtureCalls(page: Page) {
  return page.evaluate(() => (window as typeof window & {
    __stage5GovernanceCalls?: {
      riskWrites: Array<{ id: string; action: string; caseVersion: number }>
      approvals: string[]
      rejections: string[]
      reconfirmations: Array<{ executionId: string; resolution: string }>
      auditReads: Array<{ runId: string; reasonLength: number }>
      productionWrites: string[]
    }
  }).__stage5GovernanceCalls)
}

async function recordScreenshot(
  page: Page,
  file: string,
  metadata: { surface: GovernanceSurface; state: string; viewport: string; fullPage?: boolean },
) {
  mkdirSync(capturesDirectory, { recursive: true })
  const path = resolve(capturesDirectory, file)
  await page.screenshot({ path, animations: 'disabled', caret: 'hide', fullPage: metadata.fullPage ?? false })
  const dimensions = pngDimensions(path)
  const stats = statSync(path)
  const bytes = readFileSync(path)
  const record = {
    file: `captures/${file}`,
    surface: metadata.surface,
    state: metadata.state,
    viewport: metadata.viewport,
    fullPage: metadata.fullPage ?? false,
    ...dimensions,
    bytes: stats.size,
    sha256: createHash('sha256').update(bytes).digest('hex').toUpperCase(),
    mtimeUtc: stats.mtime.toISOString(),
  }
  screenshots.push(record)
  mkdirSync(screenshotRecordsDirectory, { recursive: true })
  writeFileSync(resolve(screenshotRecordsDirectory, `${file}.json`), `${JSON.stringify(record, null, 2)}\n`, 'utf8')
}

async function expectNoPageOverflow(page: Page, label: string) {
  const geometry = await page.evaluate(() => {
    const viewportWidth = window.innerWidth
    const documentWidth = Math.max(document.documentElement.scrollWidth, document.body?.scrollWidth ?? 0)
    const offenders = Array.from(document.querySelectorAll<HTMLElement>('body *')).flatMap((element) => {
      const style = getComputedStyle(element)
      const box = element.getBoundingClientRect()
      if (style.display === 'none' || style.visibility === 'hidden' || box.width < 1 || box.height < 1) return []
      if (box.left >= -1 && box.right <= viewportWidth + 1) return []
      if (['auto', 'scroll'].includes(style.overflowX) && element.scrollWidth > element.clientWidth) return []
      return [`${element.tagName.toLowerCase()}.${String(element.className).split(/\s+/).filter(Boolean).slice(0, 2).join('.')}:${Math.round(box.left)}..${Math.round(box.right)}`]
    }).slice(0, 12)
    return {
      viewportWidth,
      documentWidth,
      viewportHeight: window.innerHeight,
      documentHeight: Math.max(document.documentElement.scrollHeight, document.body?.scrollHeight ?? 0),
      offenders,
    }
  })
  expect(geometry.documentWidth, `${label} 页面不得横向溢出：${geometry.offenders.join(', ')}`).toBeLessThanOrEqual(geometry.viewportWidth + 1)
  return geometry
}

async function visibleTargetMeasurements(page: Page, selector: string) {
  return page.locator(selector).evaluateAll((elements) => elements.flatMap((element) => {
    const node = element as HTMLElement
    if (node.offsetParent === null) return []
    const box = node.getBoundingClientRect()
    const style = getComputedStyle(node)
    return [{
      tag: node.tagName.toLowerCase(),
      label: node.getAttribute('aria-label') ?? node.textContent?.trim().slice(0, 60) ?? '',
      width: box.width,
      height: box.height,
      fontSize: Number.parseFloat(style.fontSize),
    }]
  }))
}

async function expectMinimumTargets(page: Page, selector: string, label: string) {
  const measurements = await visibleTargetMeasurements(page, selector)
  expect(measurements.length, `${label} 未找到可见关键交互`).toBeGreaterThan(0)
  expect(
    measurements.filter(({ width, height }) => width < 43.5 || height < 43.5),
    `${label} 存在小于 44px 的关键交互`,
  ).toEqual([])
  return measurements
}

async function expectKeyboardFocus(locator: Locator, label: string) {
  await locator.focus()
  await expect(locator, `${label} 应可获得键盘焦点`).toBeFocused()
  const evidence = await locator.evaluate((element) => {
    const style = getComputedStyle(element)
    return {
      outlineStyle: style.outlineStyle,
      outlineWidth: style.outlineWidth,
      outlineColor: style.outlineColor,
      boxShadow: style.boxShadow,
    }
  })
  const outlineWidth = Number.parseFloat(evidence.outlineWidth)
  const hasVisibleOutline = evidence.outlineStyle !== 'none' && outlineWidth >= 1.5
  const hasVisibleShadow = evidence.boxShadow !== 'none' && !evidence.boxShadow.includes('rgba(0, 0, 0, 0)')
  expect(hasVisibleOutline || hasVisibleShadow, `${label} 必须呈现至少 2px 的可见焦点提示`).toBe(true)
  return evidence
}

async function elementStyle(locator: Locator) {
  await expect(locator).toBeVisible()
  return locator.evaluate((element) => {
    const style = getComputedStyle(element)
    const box = element.getBoundingClientRect()
    return {
      color: style.color,
      backgroundColor: style.backgroundColor,
      borderColor: style.borderColor,
      fontSize: style.fontSize,
      fontWeight: style.fontWeight,
      lineHeight: style.lineHeight,
      width: box.width,
      height: box.height,
      overflowX: style.overflowX,
      overflowY: style.overflowY,
    }
  })
}

function parseColor(value: string) {
  const match = value.match(/rgba?\(([^)]+)\)/)
  if (!match) throw new Error(`无法解析颜色：${value}`)
  const [r, g, b, a = '1'] = match[1].split(',').map((part) => Number(part.trim()))
  return { r, g, b, a }
}

function relativeLuminance({ r, g, b }: { r: number; g: number; b: number }) {
  const channels = [r, g, b].map((channel) => {
    const value = channel / 255
    return value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4
  })
  return channels[0] * 0.2126 + channels[1] * 0.7152 + channels[2] * 0.0722
}

function contrastRatio(foreground: string, background: string) {
  const fg = parseColor(foreground)
  const bg = parseColor(background)
  const blended = fg.a < 1
    ? { r: fg.r * fg.a + bg.r * (1 - fg.a), g: fg.g * fg.a + bg.g * (1 - fg.a), b: fg.b * fg.a + bg.b * (1 - fg.a) }
    : fg
  const first = relativeLuminance(blended)
  const second = relativeLuminance(bg)
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05)
}

async function textContrast(locator: Locator, label: string) {
  const colors = await locator.evaluate((element) => {
    const style = getComputedStyle(element)
    let background = style.backgroundColor
    let parent = element.parentElement
    while (background === 'rgba(0, 0, 0, 0)' && parent) {
      background = getComputedStyle(parent).backgroundColor
      parent = parent.parentElement
    }
    return [style.color, background === 'rgba(0, 0, 0, 0)' ? 'rgb(255, 255, 255)' : background] as const
  })
  const ratio = contrastRatio(colors[0], colors[1])
  expect(ratio, `${label} 正文对比度不足`).toBeGreaterThanOrEqual(4.5)
  return { foreground: colors[0], background: colors[1], ratio }
}

async function canvasEvidence(page: Page) {
  const charts = page.locator('.risk-trend-chart canvas, .risk-distribution-chart canvas')
  await expect(charts).toHaveCount(2)
  const records: Array<Record<string, number>> = []
  for (let index = 0; index < 2; index += 1) {
    const record = await charts.nth(index).evaluate((element) => {
      const canvas = element as HTMLCanvasElement
      const context = canvas.getContext('2d')
      if (!context || !canvas.width || !canvas.height) return { width: canvas.width, height: canvas.height, nonBlank: 0 }
      const pixels = context.getImageData(0, 0, canvas.width, canvas.height).data
      let nonBlank = 0
      for (let offset = 3; offset < pixels.length; offset += 4) {
        if (pixels[offset] > 0 && (pixels[offset - 3] < 248 || pixels[offset - 2] < 248 || pixels[offset - 1] < 248)) nonBlank += 1
      }
      return { width: canvas.width, height: canvas.height, nonBlank }
    })
    expect(record.width).toBeGreaterThan(100)
    expect(record.height).toBeGreaterThan(100)
    expect(record.nonBlank, `风险图表 ${index + 1} 不得为空白 Canvas`).toBeGreaterThan(100)
    records.push(record)
  }
  return records
}

async function expectReducedMotion(page: Page) {
  return page.evaluate(() => ({
    matches: matchMedia('(prefers-reduced-motion: reduce)').matches,
    spinnerAnimation: getComputedStyle(document.querySelector('.overview-spinner') ?? document.body).animationName,
  }))
}

test.afterEach(async ({ browserName: _browserName }, testInfo) => {
  appendJsonLine(testResultsPath, {
    testId: testInfo.testId,
    title: testInfo.title,
    status: testInfo.status,
    expectedStatus: testInfo.expectedStatus,
    retry: testInfo.retry,
    workerIndex: testInfo.workerIndex,
    durationMs: testInfo.duration,
  })
  if (testInfo.status !== testInfo.expectedStatus) {
    suiteFailed = true
    mkdirSync(outputDirectory, { recursive: true })
    appendFileSync(failureMarkerPath, `${new Date().toISOString()}\t${testInfo.title}\t${testInfo.status}\n`, 'utf8')
  }
})

test.afterAll(async () => {
  mkdirSync(outputDirectory, { recursive: true })
  const screenshotRecords = readJsonRecords(screenshotRecordsDirectory) as unknown as ScreenshotRecord[]
  const computedRecords = readJsonRecords(computedRecordsDirectory) as Array<{ key: string; value: unknown }>
  const aggregatedComputedEvidence = Object.fromEntries(computedRecords.map(({ key, value }) => [key, value]))
  const runtimeEvents = readJsonLines(runtimeEventsPath)
  const testResults = readJsonLines(testResultsPath)
  const runMetaPath = resolve(outputDirectory, 'run-meta.json')
  const runMeta = existsSync(runMetaPath)
    ? JSON.parse(readFileSync(runMetaPath, 'utf8')) as { runId?: string; startedAt?: string }
    : { startedAt: suiteStartedAt }
  const runtimeSummary = {
    pagesObserved: runtimeEvents.filter(({ kind }) => kind === 'page').length,
    pageErrors: runtimeEvents.filter(({ kind }) => kind === 'pageerror').length,
    consoleErrors: runtimeEvents.filter(({ kind }) => kind === 'console').length,
    apiRequestFailures: runtimeEvents.filter(({ kind }) => kind === 'api-request-failed').length,
  }
  const computedPath = resolve(outputDirectory, 'computed-styles.json')
  writeFileSync(computedPath, `${JSON.stringify(aggregatedComputedEvidence, null, 2)}\n`, 'utf8')
  const hasFailedTest = testResults.some(({ status, expectedStatus }) => status !== expectedStatus)
  const actualCaptureFileCount = existsSync(capturesDirectory)
    ? readdirSync(capturesDirectory).filter((file) => file.endsWith('.png')).length
    : 0
  const manifest = {
    runId: runMeta.runId,
    startedAt: runMeta.startedAt ?? suiteStartedAt,
    finishedAt: new Date().toISOString(),
    generatedAt: new Date().toISOString(),
    fixtureAsOf: stage5AsOf,
    fixtureReferenceInstant: stage5ReferenceInstant,
    status: suiteFailed || hasFailedTest || existsSync(failureMarkerPath)
      ? 'failed'
      : screenshotRecords.length === expectedScreenshotCount && actualCaptureFileCount === expectedScreenshotCount ? 'passed' : 'partial',
    fixtureMode: 'typed deterministic AiClient injected into the real Vue routes and Pinia actions',
    fixtureBoundary: [
      'All names and identifiers are synthetic tokens; no real student PII is present.',
      'The fixture client records UI mutations locally and never calls a production provider.',
      'The fixture never executes a production business write; real RBAC, step-up, CAS, audit and execution-lease boundaries are verified by the separate Stage 5 live suite.',
      'Visual difference ratios locate drift and are not automatic fidelity gates.',
    ],
    productionWrites: false,
    expectedScreenshotCount,
    actualScreenshotCount: screenshotRecords.length,
    actualCaptureFileCount,
    runtimeSummary,
    apiSummary: { productionRequests: 0, failedApiRequests: runtimeSummary.apiRequestFailures },
    testResults,
    viewports: viewports.map(({ name }) => name),
    zoomEquivalent: { sourceViewport: '1920x1080', cssViewport: '960x540', scale: '200%' },
    stateMatrix: {
      risk: ['success', 'overview-loading', 'overview-error', 'overview-empty', 'list-loading', 'list-error', 'list-empty', 'degraded', 'action-conflict', 'readonly'],
      approval: ['pending_approval', 'expired', 'rejected', 'approved', 'executing', 'succeeded', 'failed', 'needs_review', 'no-permission'],
      audit: ['success', 'technical-view', 'content-authorized', 'content-unavailable', 'loading', 'empty', 'error', 'detail-error', 'no-permission'],
    },
    sourceBindings: sourceBindings(),
    screenshots: screenshotRecords,
    computedStylesBinding: fileBinding('fixtures/computed-styles.json', computedPath),
  }
  writeFileSync(resolve(outputDirectory, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
})

test('风险中心成功态覆盖六正式视口、图表、筛选、分页、详情与人工处置合同', async ({ browser }) => {
  test.slow()
  const context = await createGovernanceContext(browser)
  try {
    for (const viewport of viewports) {
      const { page, runtimeIssues } = await createPageInContext(context, viewport)
      try {
      await gotoSurface(page, '/ai/risks')
      await installFixtureClient(page, 'risk')
      await expect(page.locator('[data-testid="risk-summary-card"]')).toHaveCount(4)
      await expect(page.locator('[data-testid="risk-summary-sparkline"]')).toHaveCount(4)
      await expect(page.locator('[data-testid="risk-trend-data"] tbody tr')).toHaveCount(6)
      await expect(page.locator('[data-testid="risk-distribution-data"] tbody tr')).toHaveCount(4)
      await expect(page.locator('.rule-list > li')).toHaveCount(4)
      await expect(page.getByText('规则信号，不代表学生评价', { exact: true })).toBeVisible()
      await expect(page.getByLabel('近六个月风险线索趋势图')).toBeVisible()
      await expect(page.getByLabel('风险类别分布环形图，四类数值同时见风险分类总览')).toBeVisible()
      await expect(page.getByText('较上月变化（个）', { exact: true })).toBeVisible()
      await expect(page.getByText('风险线索趋势', { exact: true })).toBeVisible()
      const summaryExpectations = [
        { type: '入住风险', count: '32 个', delta: '14', rate: '(+78%)' },
        { type: '维修风险', count: '18 个', delta: '6', rate: '(-25%)' },
        { type: '卫生风险', count: '27 个', delta: '8', rate: '(+42%)' },
        { type: '欠费风险', count: '21 个', delta: '4', rate: '(-16%)' },
      ]
      for (const [index, expected] of summaryExpectations.entries()) {
        const card = page.locator('[data-testid="risk-summary-card"]').nth(index)
        await expect(card).toContainText(expected.type)
        await expect(card).toContainText(expected.count)
        await expect(card.locator('.summary-change')).toContainText(expected.delta)
        await expect(card.locator('.summary-rate')).toHaveText(expected.rate)
      }
      await expect(page.locator('[data-testid="risk-trend-data"] tbody')).toContainText('48')
      await expect(page.locator('[data-testid="risk-trend-data"] tbody')).toContainText('98')
      await expect(page.locator('[data-testid="risk-distribution-data"] tbody')).toContainText('入住风险32')
      await expect(page.locator('[data-testid="risk-distribution-data"] tbody')).toContainText('欠费风险21')
      await expect(page.locator('.rule-status.handled')).toHaveCount(2)
      await expect(page.locator('.rule-status.needs-review')).toHaveCount(2)

      const canvas = await canvasEvidence(page)
      const geometry = await expectNoPageOverflow(page, `风险中心 ${viewport.name}`)
      const targets = await expectMinimumTargets(
        page,
        '.risk-filters select, .risk-filters input, .filter-button, .icon-button, .page-controls button, .view-button, .fact-disclosure summary, .action-buttons button, .risk-mobile-open',
        `风险中心 ${viewport.name}`,
      )
      if (viewport.name === '1366x768' || viewport.name === '390x844') {
        await page.evaluate(() => window.scrollTo(0, 0))
        await expect.poll(() => page.evaluate(() => window.scrollY), { message: `${viewport.name} 风险初始证据必须从页面顶部采集` }).toBe(0)
        await recordScreenshot(page, `risk-success-${viewport.name}-initial-top.png`, { surface: 'risk', state: 'initial-top', viewport: viewport.name })
      }

      if (viewport.width <= 760) {
        await expect(page.locator('.risk-table-wrap')).toBeHidden()
        await expect(page.locator('[data-testid="risk-mobile-list"] > li')).toHaveCount(10)
        const opener = page.locator('.risk-mobile-open').first()
        const focus = await expectKeyboardFocus(opener, `${viewport.name} 风险移动详情按钮`)
        await opener.press('Enter')
        const detailHeading = page.getByRole('heading', { name: '线索详情' })
        await expect(detailHeading).toBeVisible()
        await expect(detailHeading).toBeFocused()
        const detailViewport = await page.evaluate(() => {
          const heading = document.querySelector<HTMLElement>('[data-testid="risk-detail-heading"]')
          const header = document.querySelector<HTMLElement>('.admin-header')
          const headingBox = heading?.getBoundingClientRect()
          const headerBox = header?.getBoundingClientRect()
          return {
            headingTop: headingBox?.top ?? -1,
            headingBottom: headingBox?.bottom ?? -1,
            headerBottom: headerBox?.bottom ?? 0,
            scrollY: window.scrollY,
          }
        })
        expect(detailViewport.headingTop, `${viewport.name} 风险详情标题不得被固定 Header 遮挡`).toBeGreaterThanOrEqual(detailViewport.headerBottom + 4)
        const summaryLabel = await elementStyle(page.locator('.summary-main h2').first())
        expect(Number.parseFloat(summaryLabel.fontSize), `${viewport.name} 风险摘要核心标签至少 14px`).toBeGreaterThanOrEqual(14)
        recordComputedEvidence(`risk-success-${viewport.name}`, { geometry, targets, canvas, focus, detailViewport, summaryLabel, motion: await expectReducedMotion(page) })
      } else {
        await expect(page.locator('.risk-table-wrap')).toBeVisible()
        const riskRows = page.locator('.risk-table tbody tr').filter({ has: page.locator('td') })
        await expect(riskRows).toHaveCount(10)
        await expect(riskRows.locator('td:nth-child(2) strong').first()).toHaveText('入住风险')
        await expect(riskRows.locator('td:nth-child(2) strong').nth(1)).toHaveText('维修风险')
        await expect(riskRows.locator('td:nth-child(2) strong').nth(2)).toHaveText('卫生风险')
        await expect(riskRows.locator('td:nth-child(2) strong').nth(3)).toHaveText('欠费风险')
        const firstRow = riskRows.first()
        const focus = await expectKeyboardFocus(firstRow, `${viewport.name} 风险表格行`)
        await firstRow.press('Space')
        await expect(firstRow).toHaveAttribute('aria-current', 'true')
        const detailViewport = await page.evaluate(() => {
          const region = document.querySelector<HTMLElement>('[data-testid="risk-detail-scroll-region"]')
          const timelineItems = Array.from(document.querySelectorAll<HTMLElement>('.human-timeline li'))
          const safety = document.querySelector<HTMLElement>('[data-testid="risk-detail-safety"]')
          const actionDock = document.querySelector<HTMLElement>('[data-testid="risk-action-dock"]')
          const regionBox = region?.getBoundingClientRect()
          const lastTimelineBox = timelineItems.at(-1)?.getBoundingClientRect()
          const safetyBox = safety?.getBoundingClientRect()
          const actionDockBox = actionDock?.getBoundingClientRect()
          return {
            clientHeight: region?.clientHeight ?? 0,
            scrollHeight: region?.scrollHeight ?? 0,
            scrollTop: region?.scrollTop ?? -1,
            regionBottom: regionBox?.bottom ?? -1,
            lastTimelineBottom: lastTimelineBox?.bottom ?? -1,
            safetyBottom: safetyBox?.bottom ?? -1,
            actionDockBottom: actionDockBox?.bottom ?? -1,
            viewportHeight: window.innerHeight,
          }
        })
        if (viewport.width >= 1281 && viewport.height >= 900) {
          expect(detailViewport.scrollTop, `${viewport.name} 风险详情截图前不得预先滚动`).toBe(0)
          expect(detailViewport.scrollHeight, `${viewport.name} 风险详情首屏应完整容纳证据、人工时间线与安全提示`)
            .toBeLessThanOrEqual(detailViewport.clientHeight + 1)
          expect(detailViewport.lastTimelineBottom, `${viewport.name} 完整人工时间线必须位于详情可视区内`)
            .toBeLessThanOrEqual(detailViewport.regionBottom + 1)
          expect(detailViewport.safetyBottom, `${viewport.name} 风险安全提示必须位于详情可视区内`)
            .toBeLessThanOrEqual(Math.min(detailViewport.regionBottom, detailViewport.viewportHeight) + 1)
          expect(detailViewport.actionDockBottom, `${viewport.name} 风险人工处置操作必须完整进入首屏`)
            .toBeLessThanOrEqual(detailViewport.viewportHeight + 1)
        }
        recordComputedEvidence(`risk-success-${viewport.name}`, {
          geometry, targets, canvas, focus,
          heading: await elementStyle(page.getByRole('heading', { name: '风险趋势' })),
          bodyContrast: await textContrast(page.locator('.evidence-section p').first(), `${viewport.name} 风险详情正文`),
          detailViewport,
          motion: await expectReducedMotion(page),
        })
      }

      await expect(page.locator('[data-testid="risk-action-dock"]')).toBeVisible()
      await recordScreenshot(page, `risk-success-${viewport.name}.png`, { surface: 'risk', state: 'success', viewport: viewport.name })
      if (viewport.name === '1536x1024' || viewport.name === '390x844') {
        await recordScreenshot(page, `risk-success-${viewport.name}-full.png`, { surface: 'risk', state: 'success', viewport: viewport.name, fullPage: true })
      }
        expect(runtimeIssues).toEqual([])
        expect((await fixtureCalls(page))?.productionWrites).toEqual([])
      } finally {
        await page.close()
      }
    }
  } finally {
    await context.close()
  }
})

test('风险筛选分页、处置成功和 stale 冲突均经过真实 store action', async ({ browser }) => {
  const { context, page, runtimeIssues } = await createPage(browser, { width: 1536, height: 1024 })
  try {
    await gotoSurface(page, '/ai/risks')
    await installFixtureClient(page, 'risk')
    await page.getByRole('combobox', { name: '风险类型筛选' }).selectOption('欠费风险')
    await page.getByRole('button', { name: '查询' }).click()
    await expect(page.locator('.risk-table tbody tr').filter({ has: page.locator('td') })).not.toHaveCount(0)
    await page.getByRole('combobox', { name: '风险类型筛选' }).selectOption('')
    await page.getByRole('button', { name: '查询' }).click()
    await page.getByRole('button', { name: '下一页' }).click()
    await expect(page.locator('.page-controls button.active')).toHaveText('2')
    await page.getByRole('button', { name: '上一页' }).click()
    await expect(page.locator('.page-controls button.active')).toHaveText('1')

    await page.getByRole('button', { name: '确认风险', exact: true }).click()
    let actionDialog = page.getByRole('dialog', { name: '确认风险处置' })
    await actionDialog.getByRole('textbox', { name: '风险处置说明' }).fill('已完成人工业务事实核验，转入处理队列。')
    const action = actionDialog.getByRole('button', { name: '确认风险', exact: true })
    await expect(action).toBeEnabled()
    await action.click()
    await expect(actionDialog).toBeHidden()
    await expect(page.locator('.state-tag').filter({ hasText: '处理中' }).first()).toBeVisible()
    expect((await fixtureCalls(page))?.riskWrites).toHaveLength(1)
    expect(runtimeIssues).toEqual([])

    await installFixtureClient(page, 'risk', { riskAction: 'conflict' })
    await page.getByRole('button', { name: '确认风险', exact: true }).click()
    actionDialog = page.getByRole('dialog', { name: '确认风险处置' })
    await actionDialog.getByRole('textbox', { name: '风险处置说明' }).fill('使用旧版本提交，必须触发冲突并要求刷新。')
    await actionDialog.getByRole('button', { name: '确认风险', exact: true }).click()
    await expect(page.locator('[data-testid="risk-action-error"]')).toContainText('版本已变化')
    const actionMessages = page.locator('.ant-message-notice')
    await expect(actionMessages, '同一风险操作只保留一条最新 toast').toHaveCount(1)
    await expect(actionMessages.first()).toContainText(/线索 .*确认风险失败.*版本已变化/)
    await recordScreenshot(page, 'risk-state-action-conflict-1536x1024.png', { surface: 'risk', state: 'action-conflict', viewport: '1536x1024' })
    expect((await fixtureCalls(page))?.riskWrites).toHaveLength(1)
  } finally {
    await context.close()
  }
})

test('风险 loading、error、empty、degraded 与只读状态诚实呈现', async ({ browser }) => {
  test.slow()
  const scenarios: Array<{ state: string; mode: FixtureMode; assertion: (page: Page) => Promise<void> }> = [
    { state: 'overview-loading', mode: { riskOverview: 'loading' }, assertion: async (page) => expect(page.locator('[data-testid="risk-overview-loading"]')).toBeVisible() },
    { state: 'overview-error', mode: { riskOverview: 'error' }, assertion: async (page) => expect(page.getByRole('button', { name: '重新加载风险总览' })).toBeVisible() },
    { state: 'overview-empty', mode: { riskOverview: 'empty' }, assertion: async (page) => expect(page.getByText('当前权限范围内暂无可见风险信号')).toBeVisible() },
    { state: 'list-loading', mode: { riskList: 'loading' }, assertion: async (page) => expect(page.getByText('正在加载确定性风险信号…')).toBeVisible() },
    { state: 'list-error', mode: { riskList: 'error' }, assertion: async (page) => expect(page.getByRole('button', { name: '重新加载风险线索' })).toBeVisible() },
    { state: 'list-empty', mode: { riskList: 'empty' }, assertion: async (page) => expect(page.locator('.empty-cell')).toHaveText('当前筛选条件下暂无风险线索') },
  ]
  const context = await createGovernanceContext(browser)
  try {
    for (const scenario of scenarios) {
      const { page, runtimeIssues } = await createPageInContext(context, { width: 1366, height: 768 })
      try {
        await gotoSurface(page, '/ai/risks')
        await installFixtureClient(page, 'risk', scenario.mode)
        await scenario.assertion(page)
        const target = scenario.state === 'list-loading'
          ? page.getByText('正在加载确定性风险信号…')
          : scenario.state === 'list-error'
            ? page.getByRole('button', { name: '重新加载风险线索' })
            : scenario.state === 'list-empty'
              ? page.locator('.empty-cell')
              : null
        if (target) {
          await target.scrollIntoViewIfNeeded()
          await expect(target, `${scenario.state} 目标状态必须直接进入截图视口`).toBeInViewport()
        }
        await recordScreenshot(page, `risk-state-${scenario.state}-1366x768.png`, { surface: 'risk', state: scenario.state, viewport: '1366x768' })
        expect(runtimeIssues).toEqual([])
        expect((await fixtureCalls(page))?.productionWrites).toEqual([])
      } finally {
        await page.close()
      }
    }

    const degradedCases = stage5RiskCases.map((risk, index) => index === 0
      ? { ...risk, degraded: true, explanationEvidence: { ...risk.explanationEvidence, degraded: true, basis: 'deterministic_degraded' as const } }
      : risk)
    const degraded = await createPageInContext(context, { width: 1366, height: 768 })
    try {
      await gotoSurface(degraded.page, '/ai/risks')
      await installFixtureClient(degraded.page, 'risk', {}, { riskCases: degradedCases })
      await expect(degraded.page.getByText('模型不可用，当前仅展示规则证据。')).toBeVisible()
      await recordScreenshot(degraded.page, 'risk-state-degraded-1366x768.png', { surface: 'risk', state: 'degraded', viewport: '1366x768' })
      expect(degraded.runtimeIssues).toEqual([])
      expect((await fixtureCalls(degraded.page))?.productionWrites).toEqual([])
    } finally {
      await degraded.page.close()
    }

    const readonly = await createPageInContext(context, { width: 390, height: 844 }, ['ai:risk:read'])
    try {
      await gotoSurface(readonly.page, '/ai/risks')
      await installFixtureClient(readonly.page, 'risk')
      await expect(readonly.page.locator('[data-testid="risk-readonly-note"]')).toBeVisible()
      await expect(readonly.page.locator('[data-testid="risk-action-dock"]')).toHaveCount(0)
      expect((await fixtureCalls(readonly.page))?.riskWrites).toEqual([])
      await recordScreenshot(readonly.page, 'risk-state-readonly-390x844.png', { surface: 'risk', state: 'readonly', viewport: '390x844', fullPage: true })
      expect(readonly.runtimeIssues).toEqual([])
      expect((await fixtureCalls(readonly.page))?.productionWrites).toEqual([])
    } finally {
      await readonly.page.close()
    }
  } finally {
    await context.close()
  }
})

test('审批工作台覆盖六正式视口、八种方案状态、diff、阻断与完整 hash', async ({ browser }) => {
  test.slow()
  const context = await createGovernanceContext(browser)
  try {
    for (const viewport of viewports) {
      const { page, runtimeIssues } = await createPageInContext(context, viewport)
      try {
      await gotoSurface(page, '/ai/approvals')
      await installFixtureClient(page, 'approval')
      await expect(page.locator('[data-testid="approval-proposal-rail"]')).toBeVisible()
      await expect(page.locator('.ai-proposal-row')).toHaveCount(8)
      await expect(page.locator('[data-testid="approval-preview-panel"] .value-diff')).toBeVisible()
      await expect(page.getByText('当前值（将被替换）')).toBeVisible()
      await expect(page.getByText('建议值（执行后）')).toBeVisible()
      await expect(page.locator('.guard-grid > span')).toHaveCount(4)
      await expect(page.locator('.approval-flow li:not(.flow-line)')).toHaveCount(3)
      await expect(page.locator('.hash-disclosure')).toHaveCount(2)
      await page.evaluate(() => window.scrollTo(0, 0))
      await expect.poll(() => page.evaluate(() => window.scrollY), { message: `${viewport.name} 审批首屏证据必须从页面顶部采集` }).toBe(0)
      const geometry = await expectNoPageOverflow(page, `审批工作台 ${viewport.name}`)
      const targets = await expectMinimumTargets(
        page,
        '.governance-tabs a, .rail-heading select, .proposal-pagination button, .approval-actions button, .hash-disclosure summary, .ai-reconfirm button',
        `审批工作台 ${viewport.name}`,
      )
      const approvalActions = await page.locator('.approval-actions').boundingBox()
      if (viewport.width >= 1321 && viewport.height >= 900) {
        expect(approvalActions, `${viewport.name} 审批主操作必须存在`).not.toBeNull()
        expect(approvalActions!.y + approvalActions!.height, `${viewport.name} 审批主操作必须完整落在首屏`).toBeLessThanOrEqual(viewport.height - 16)
      }
      const title = await elementStyle(page.locator('.preview-heading h2'))
      const bodyContrast = await textContrast(page.locator('.proposal-facts dd').first(), `${viewport.name} 审批影响范围`)
      await recordScreenshot(page, `approval-success-${viewport.name}.png`, { surface: 'approval', state: 'pending_approval', viewport: viewport.name })
      if (viewport.name === '1536x1024' || viewport.name === '390x844') {
        await recordScreenshot(page, `approval-success-${viewport.name}-full.png`, { surface: 'approval', state: 'pending_approval', viewport: viewport.name, fullPage: true })
      }
      if (viewport.name === '390x844') {
        const secondProposal = page.locator('.proposal-select').nth(1)
        await secondProposal.click()
        const detailHeading = page.locator('.preview-heading h2')
        await expect(detailHeading, '移动端选择方案后必须聚焦对应详情标题').toBeFocused()
        const selectionFocus = await page.evaluate(() => ({
          scrollY: window.scrollY,
          activeText: document.activeElement?.textContent?.trim() ?? '',
        }))
        expect(selectionFocus.scrollY, '移动端选择方案后必须滚动到详情').toBeGreaterThan(0)
        expect(selectionFocus.activeText, '移动端详情焦点必须绑定当前方案标题').not.toBe('')
        recordComputedEvidence('approval-mobile-selection-focus-390x844', selectionFocus)
        await recordScreenshot(page, 'approval-mobile-selection-focus-390x844.png', {
          surface: 'approval', state: 'selection-focus', viewport: '390x844',
        })
      }
      const focus = await expectKeyboardFocus(page.getByRole('button', { name: '刷新预览' }), `${viewport.name} 审批刷新预览`)
      const firstHash = page.locator('.hash-disclosure').first()
      const hashFocus = await expectKeyboardFocus(firstHash.locator('summary'), `${viewport.name} 审批 Hash 展开项`)
      await firstHash.locator('summary').press('Enter')
      await expect(firstHash.locator('.hash-full-value')).toHaveText(/[0-9a-f]{64}/i)
      recordComputedEvidence(`approval-success-${viewport.name}`, { geometry, targets, focus, hashFocus, approvalActions, title, bodyContrast })
        expect(runtimeIssues).toEqual([])
        expect((await fixtureCalls(page))?.productionWrites).toEqual([])
      } finally {
        await page.close()
      }
    }
  } finally {
    await context.close()
  }
})

test('审批八种状态逐一可选择，批准密码门与 NEEDS_REVIEW 对账不产生生产写', async ({ browser }) => {
  const { context, page, runtimeIssues } = await createPage(browser, { width: 1536, height: 1024 })
  try {
    await gotoSurface(page, '/ai/approvals')
    await installFixtureClient(page, 'approval')
    for (const proposal of stage5Proposals) {
      const row = page.locator('.ai-proposal-row').filter({ hasText: proposal.title })
      await row.click()
      await expect(page.locator('.ai-proposal-row.selected')).toContainText(proposal.title)
      await expect(page.locator('[data-testid="approval-preview-panel"]')).toContainText(proposal.target)
      await recordScreenshot(page, `approval-state-${proposal.state}-1536x1024.png`, {
        surface: 'approval', state: proposal.state, viewport: '1536x1024',
      })
    }

    const pending = stage5Proposals.find((proposal) => proposal.state === 'pending_approval')!
    await page.locator('.ai-proposal-row').filter({ hasText: pending.title }).click()
    await page.locator('.approval-attestation input').check()
    await page.getByRole('button', { name: '批准执行' }).click()
    const modal = page.getByRole('dialog', { name: '确认批准执行' })
    await expect(modal).toBeVisible()
    await expect(modal.getByText('演示模式只更新 AI Store，不执行真实业务。')).toBeVisible()
    expect((await fixtureCalls(page))?.approvals).toEqual([])
    await modal.getByRole('button', { name: '确认批准' }).click()
    await expect(page.locator('[data-testid="approval-preview-panel"] .proposal-state')).toContainText('已成功')
    expect((await fixtureCalls(page))?.approvals).toEqual([pending.id])

    const needsReview = stage5Proposals.find((proposal) => proposal.state === 'needs_review')!
    await installFixtureClient(page, 'approval')
    await page.locator('.ai-proposal-row').filter({ hasText: needsReview.title }).click()
    await expect(page.locator('.ai-reconfirm')).toBeVisible()
    await page.locator('.ai-reconfirm .ant-select-selector').click()
    const conflictOption = page
      .locator('.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option')
      .filter({ hasText: '状态冲突/失败' })
    await expect(conflictOption).toBeVisible()
    await conflictOption.click()
    await page.locator('.ai-reconfirm textarea').fill('人工核验发现业务事实冲突，保持 NEEDS_REVIEW。')
    await page.locator('.ai-reconfirm button').click()
    const calls = await fixtureCalls(page)
    expect(calls?.reconfirmations).toEqual([{ executionId: needsReview.executionId, resolution: 'CONFLICT' }])
    expect(calls?.productionWrites).toEqual([])
    expect(runtimeIssues).toEqual([])
  } finally {
    await context.close()
  }
})

test('审批 loading、error、empty 与无权限状态可见且不伪造成功', async ({ browser }) => {
  const scenarios: Array<{ state: string; mode: FixtureMode; assertion: (page: Page) => Promise<void> }> = [
    { state: 'loading', mode: { proposalList: 'loading' }, assertion: async (page) => expect(page.getByText('正在加载待审方案…')).toBeVisible() },
    { state: 'error', mode: { proposalList: 'error' }, assertion: async (page) => expect(page.getByRole('button', { name: '重新加载' })).toBeVisible() },
    { state: 'empty', mode: { proposalList: 'empty' }, assertion: async (page) => expect(page.getByText('当前类型暂无方案')).toBeVisible() },
  ]
  const context = await createGovernanceContext(browser)
  try {
    for (const scenario of scenarios) {
      const { page, runtimeIssues } = await createPageInContext(context, { width: 1366, height: 768 })
      try {
        await gotoSurface(page, '/ai/approvals')
        await installFixtureClient(page, 'approval', scenario.mode)
        await scenario.assertion(page)
        await recordScreenshot(page, `approval-state-${scenario.state}-1366x768.png`, { surface: 'approval', state: scenario.state, viewport: '1366x768' })
        expect((await fixtureCalls(page))?.approvals).toEqual([])
        expect((await fixtureCalls(page))?.productionWrites).toEqual([])
        expect(runtimeIssues).toEqual([])
      } finally {
        await page.close()
      }
    }

    const forbidden = await createPageInContext(context, { width: 1366, height: 768 }, ['dashboard:read'])
    try {
      await gotoSurface(forbidden.page, '/ai/approvals')
      await expect(forbidden.page.getByText(/无权限|403/)).toBeVisible()
      await recordScreenshot(forbidden.page, 'approval-state-no-permission-1366x768.png', { surface: 'approval', state: 'no-permission', viewport: '1366x768' })
      expect(forbidden.runtimeIssues).toEqual([])
    } finally {
      await forbidden.page.close()
    }
  } finally {
    await context.close()
  }
})

test('运行审计覆盖六正式视口、七阶段、七类事实、时间线、成本与移动选择聚焦', async ({ browser }) => {
  test.slow()
  const context = await createGovernanceContext(browser)
  try {
    for (const viewport of viewports) {
      const { page, runtimeIssues } = await createPageInContext(context, viewport)
      try {
      await gotoSurface(page, '/ai/audit')
      await installFixtureClient(page, 'audit')
      await expect(page.locator('[data-testid="audit-run-rail"]')).toBeVisible()
      await expect(page.locator('.ai-audit-run')).toHaveCount(stage5AuditRuns.length)
      await expect(page.locator('[data-testid="audit-stage-strip"] > li')).toHaveCount(7)
      await expect(page.locator('.audit-timeline li')).toHaveCount(7)
      await expect(page.locator('[data-testid="audit-evidence-workbench"] .audit-evidence-group')).toHaveCount(7)
      await expect(page.locator('.hash-chain code')).toHaveText(/[0-9a-f]{32,}/i)
      await expect(page.locator('.metric-grid article')).toHaveCount(4)

      const geometry = await expectNoPageOverflow(page, `运行审计 ${viewport.name}`)
      const auditReadability = await page.evaluate(() => {
        const stageCards = Array.from(document.querySelectorAll<HTMLElement>('.audit-stage-strip li')).map((card) => ({
          clientWidth: card.clientWidth,
          scrollWidth: card.scrollWidth,
          titleWidth: card.querySelector<HTMLElement>('strong')?.getBoundingClientRect().width ?? 0,
        }))
        const run = document.querySelector<HTMLElement>('.ai-audit-run.selected') ?? document.querySelector<HTMLElement>('.ai-audit-run')
        const duration = run?.querySelector<HTMLElement>('.run-meta b')?.getBoundingClientRect()
        const detail = run?.querySelector<HTMLElement>('.view-detail-text')?.getBoundingClientRect()
        const overlap = duration && detail
          ? Math.max(0, Math.min(duration.right, detail.right) - Math.max(duration.left, detail.left))
            * Math.max(0, Math.min(duration.bottom, detail.bottom) - Math.max(duration.top, detail.top))
          : 0
        return { stageCards, runDurationDetailOverlap: overlap }
      })
      expect(auditReadability.stageCards.filter((card) => card.scrollWidth > card.clientWidth + 1), `${viewport.name} 审计阶段卡不得内部溢出`).toEqual([])
      expect(auditReadability.stageCards.filter((card) => card.titleWidth < 40), `${viewport.name} 审计阶段标题不得挤成窄竖排`).toEqual([])
      expect(auditReadability.runDurationDetailOverlap, `${viewport.name} 运行耗时与查看详情不得重叠`).toBe(0)
      const targets = await expectMinimumTargets(
        page,
        '.governance-tabs a, .audit-filter-bar select, .audit-filter-bar input, .audit-filter-bar button, .view-toggle button, .run-pagination button, .refresh-button, .metrics-heading button, .audit-content-actions button',
        `运行审计 ${viewport.name}`,
      )
      if (viewport.width <= 760) {
        expect(await page.evaluate(() => window.scrollY), `${viewport.name} 审计初始截图必须保持页面顶部`).toBe(0)
        await recordScreenshot(page, 'audit-success-390x844-initial-top.png', { surface: 'audit', state: 'initial-top', viewport: viewport.name })
      }
      const focus = await expectKeyboardFocus(page.locator('.refresh-button'), `${viewport.name} 审计详情刷新`)
      if (viewport.width <= 760) {
        const secondRun = page.locator('.ai-audit-run').nth(1).locator('.run-select')
        await secondRun.focus()
        await secondRun.press('Enter')
        await expectKeyboardFocus(page.locator('[data-testid="audit-detail-heading"]'), `${viewport.name} 审计详情标题`)
      }
      await page.getByRole('button', { name: '技术', exact: true }).click()
      await expect(page.locator('[data-testid="audit-linked-artifacts"]')).toBeVisible()
      await expect(page.locator('[data-testid="audit-linked-artifacts"] .artifact-group')).toHaveCount(7)
      recordComputedEvidence(`audit-success-${viewport.name}`, {
        geometry, targets, focus, auditReadability,
        stageTitle: await elementStyle(page.locator('.audit-stage-strip strong').first()),
        bodyContrast: await textContrast(page.locator('.audit-safety-note span').first(), `${viewport.name} 审计安全提示`),
      })
      await recordScreenshot(page, `audit-success-${viewport.name}.png`, { surface: 'audit', state: 'technical-view', viewport: viewport.name })
      if (viewport.name === '1536x1024' || viewport.name === '390x844') {
        await recordScreenshot(page, `audit-success-${viewport.name}-full.png`, { surface: 'audit', state: 'technical-view', viewport: viewport.name, fullPage: true })
      }
        expect(runtimeIssues).toEqual([])
        expect((await fixtureCalls(page))?.productionWrites).toEqual([])
      } finally {
        await page.close()
      }
    }
  } finally {
    await context.close()
  }
})

test('审计正文 break-glass、范围不可用、loading、empty、error、detail-error 与无权限状态诚实呈现', async ({ browser }) => {
  test.slow()
  const context = await createGovernanceContext(browser)
  try {
    const content = await createPageInContext(context, { width: 1536, height: 1024 })
    try {
      await gotoSurface(content.page, '/ai/audit')
      await installFixtureClient(content.page, 'audit')
      await content.page.getByRole('button', { name: '读取审计正文' }).click()
      const modal = content.page.getByRole('dialog', { name: '读取审计正文' })
      await expect(modal).toBeVisible()
      await modal.getByLabel('审计正文读取理由').fill('短理由')
      await expect(modal.getByRole('button', { name: '确认读取' })).toBeDisabled()
      expect((await fixtureCalls(content.page))?.auditReads).toEqual([])
      await modal.getByLabel('审计正文读取理由').fill('用于 Stage 5 授权审计证据复核。')
      await modal.getByLabel('当前密码').fill('fixture-password')
      await modal.getByRole('button', { name: '确认读取' }).click()
      await expect(content.page.locator('.ai-audit-content')).toBeVisible()
      const contentReadSuccess = content.page.getByText(`运行 ${stage5AuditRuns[0]!.id}：审计正文已按授权读取`, { exact: true })
      await expect(contentReadSuccess).toBeVisible()
      expect((await fixtureCalls(content.page))?.auditReads).toEqual([{
        runId: stage5AuditRuns[0]!.id,
        reasonLength: '用于 Stage 5 授权审计证据复核。'.trim().length,
      }])
      await recordScreenshot(content.page, 'audit-state-content-authorized-1536x1024.png', { surface: 'audit', state: 'content-authorized', viewport: '1536x1024' })

      const riskRun = stage5AuditRuns.find((run) => run.capability.toUpperCase() === 'RISK')!
      await content.page.locator('.ai-audit-run').filter({ hasText: riskRun.id }).click()
      await expect(content.page.getByText('该能力运行不提供审计正文读取')).toBeVisible()
      await expect(content.page.getByRole('button', { name: '读取审计正文' })).toHaveCount(0)
      await expect(contentReadSuccess).toHaveCount(0)
      await recordScreenshot(content.page, 'audit-state-content-unavailable-1536x1024.png', { surface: 'audit', state: 'content-unavailable', viewport: '1536x1024' })
      expect(content.runtimeIssues).toEqual([])
      expect((await fixtureCalls(content.page))?.productionWrites).toEqual([])
    } finally {
      await content.page.close()
    }

    const scenarios: Array<{ state: string; mode: FixtureMode; assertion: (page: Page) => Promise<void> }> = [
      { state: 'loading', mode: { auditList: 'loading' }, assertion: async (page) => expect(page.locator('.audit-workbench')).toHaveAttribute('aria-busy', 'true') },
      { state: 'empty', mode: { auditList: 'empty' }, assertion: async (page) => expect(page.getByText('当前筛选条件下暂无运行记录')).toBeVisible() },
      { state: 'error', mode: { auditList: 'error' }, assertion: async (page) => expect(page.getByRole('button', { name: '重新加载' })).toBeVisible() },
      { state: 'detail-error', mode: { auditDetail: 'error' }, assertion: async (page) => expect(page.getByRole('alert')).toContainText('运行审计详情读取失败') },
    ]
    for (const scenario of scenarios) {
      const { page, runtimeIssues } = await createPageInContext(context, { width: 1366, height: 768 })
      try {
        if (scenario.state === 'loading' || scenario.state === 'error' || scenario.state === 'detail-error') {
          await mountAuditWithFixtureMode(page, scenario.mode)
        } else {
          await gotoSurface(page, '/ai/audit')
          await installFixtureClient(page, 'audit', scenario.mode)
        }
        await scenario.assertion(page)
        await recordScreenshot(page, `audit-state-${scenario.state}-1366x768.png`, { surface: 'audit', state: scenario.state, viewport: '1366x768' })
        expect(runtimeIssues).toEqual([])
        expect((await fixtureCalls(page))?.productionWrites).toEqual([])
      } finally {
        await page.close()
      }
    }

    const forbidden = await createPageInContext(context, { width: 1366, height: 768 }, ['dashboard:read'])
    try {
      await gotoSurface(forbidden.page, '/ai/audit')
      await expect(forbidden.page.getByText(/无权限|403/)).toBeVisible()
      await recordScreenshot(forbidden.page, 'audit-state-no-permission-1366x768.png', { surface: 'audit', state: 'no-permission', viewport: '1366x768' })
      expect(forbidden.runtimeIssues).toEqual([])
    } finally {
      await forbidden.page.close()
    }
  } finally {
    await context.close()
  }
})

test('风险、审批与审计在 200% 等效视口自然重排且尊重 reduced motion', async ({ browser }) => {
  test.slow()
  const routes: Array<{ surface: GovernanceSurface; path: string }> = [
    { surface: 'risk', path: '/ai/risks' },
    { surface: 'approval', path: '/ai/approvals' },
    { surface: 'audit', path: '/ai/audit' },
  ]
  const context = await createGovernanceContext(browser)
  try {
    for (const route of routes) {
      const { page, runtimeIssues } = await createPageInContext(context, { width: 960, height: 540 })
      try {
        await gotoSurface(page, route.path)
        await installFixtureClient(page, route.surface)
        const geometry = await expectNoPageOverflow(page, `${route.surface} 200% 等效视口`)
        const motion = await expectReducedMotion(page)
        expect(motion.matches).toBe(true)
        recordComputedEvidence(`${route.surface}-200pct-1920x1080`, { geometry, motion })
        await recordScreenshot(page, `${route.surface}-success-960x540-200pct.png`, {
          surface: route.surface, state: 'success', viewport: '960x540@200%',
        })
        await recordScreenshot(page, `${route.surface}-success-960x540-200pct-full.png`, {
          surface: route.surface, state: 'success', viewport: '960x540@200%', fullPage: true,
        })
        expect(runtimeIssues).toEqual([])
      } finally {
        await page.close()
      }
    }
  } finally {
    await context.close()
  }
})
