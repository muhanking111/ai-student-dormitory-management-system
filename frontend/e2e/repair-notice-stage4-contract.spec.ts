import { createHash } from 'node:crypto'
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type Browser, type Locator, type Page } from '@playwright/test'
import type { AiNoticeDraftResult, AiRepairTriageResult } from '../src/types/ai'
import { mockApi, type MockApiControl } from './mock-api'

const baseURL = 'http://127.0.0.1:5177'
const outputName = (process.env.VISUAL_OUTPUT_NAME?.trim() || 'stage4-repair-notice-20260801-j')
  .replace(/[^A-Za-z0-9._-]/g, '_')
const outputDirectory = resolve(process.cwd(), 'test-results', outputName, 'fixtures')
const capturesDirectory = resolve(outputDirectory, 'captures')
const screenshots: Array<Record<string, unknown>> = []
const computedEvidence: Record<string, unknown> = {}
const stage4ReferenceInstant = '2026-08-01T10:30:00+08:00'
const millisecondsPerDay = 24 * 60 * 60 * 1000
const asiaShanghaiOffsetMilliseconds = 8 * 60 * 60 * 1000
const stage4InstantAtDayOffset = (days: number) => new Date(
  Date.parse(stage4ReferenceInstant) + days * millisecondsPerDay + asiaShanghaiOffsetMilliseconds,
).toISOString().replace(/Z$/, '+08:00')
const stage4ExpiredProposalInstant = stage4InstantAtDayOffset(-1)
const stage4ActiveProposalExpiryInstant = stage4InstantAtDayOffset(10)

const viewports = [
  { name: '1920x1080', width: 1920, height: 1080 },
  { name: '1366x768', width: 1366, height: 768 },
  { name: '1586x992', width: 1586, height: 992 },
  { name: '1536x1024', width: 1536, height: 1024 },
  { name: '1505x1045', width: 1505, height: 1045 },
  { name: '390x844', width: 390, height: 844 },
] as const

const repairFixtureOrders = Array.from({ length: 10 }, (_, index) => {
  const number = index + 1
  const completed = number === 10
  const types = ['水电维修', '门窗维修', '家具维修'] as const
  return {
    id: number,
    code: `WX-STAGE4-COMMON-PREFIX-${String(number).padStart(6, '0')}`,
    reporter: `合成报修人 ${String(number).padStart(2, '0')}`,
    location: `${(number % 4) + 1}号楼-${100 + number}宿舍`,
    type: types[index % types.length],
    date: `2026-08-01 ${String(8 + Math.floor(index / 2)).padStart(2, '0')}:${index % 2 === 0 ? '10' : '40'}`,
    status: completed ? '已完成' : number % 3 === 0 ? '处理中' : '待处理',
    description: completed
      ? '合成 fixture：门锁维修已完成，用于验证已完成工单不可再次分诊。'
      : `合成 fixture：第 ${number} 条维修描述，用于验证长前缀工单号的末尾辨识与十行列表密度。`,
    assigneeUserId: completed || number % 3 === 0 ? 2 : undefined,
  }
})
const firstRepairFixture = repairFixtureOrders[0]
const completedRepairFixture = repairFixtureOrders.at(-1)!
const noticeDraftContent = `各住宿同学：

为加强学生宿舍安全管理，学校将于本周五开展宿舍安全检查。检查内容包括违规电器、插线板使用、私拉电线、消防通道、公共区域堆放和门窗设施等情况。

请各宿舍提前整理室内与公共区域，关闭离开房间时不需要使用的电源，妥善保管个人物品，并配合工作人员完成核验。如发现设施异常，请通过现有报修流程提交，不要自行拆卸或维修。

具体检查时段以各楼栋后续通知为准。如因课程安排无法在场，请提前向楼栋值班岗位说明。检查结果仅用于宿舍安全整改，请根据反馈及时整改并保持疏散通道畅通。

学生宿舍管理中心
安全检查工作组`
const publishedNoticeFixture = {
  id: 1,
  title: '宿舍安全用电通知',
  type: '安全卫生',
  date: '2026-08-01',
  publisher: '学生宿舍管理中心',
  status: '已发布',
  content: '请规范使用宿舍电器，离开房间时关闭非必要电源，并保持公共区域通道畅通。',
}

type RepairScenario = 'success' | 'low-confidence' | 'no-candidate' | 'no-source'
  | 'failed' | 'timed-out' | 'degraded' | 'stale' | 'expired'
type NoticeScenario = 'success' | 'no-source' | 'degraded' | 'failed' | 'timed-out'
  | 'stale' | 'expired'
type SnapshotScenario = 'success' | 'loading' | 'empty' | 'error'

function repairResult(scenario: RepairScenario = 'success', repairId = 1): AiRepairTriageResult {
  const result: AiRepairTriageResult = {
    repairId,
    category: '水电',
    urgency: 'HIGH',
    recommendedTeam: '水电维修组',
    missingInformation: ['是否已经关闭相关水阀'],
    reasoningSummary: '工单描述涉及持续漏水，建议优先人工核验并安排值班维修员。',
    assignmentCandidateUserId: 2,
    assignmentCandidateName: '维修员',
    slaSuggestion: '2 小时响应',
    evidence: {
      basis: 'deterministic',
      confidence: 0.92,
      asOf: '2026-08-01T10:30:00+08:00',
      grounded: true,
      citations: [{
        id: 'stage4-repair-citation',
        label: '维修分诊规则',
        locator: 'rule:repair-triage.v1',
        version: 'v1',
        access: 'available',
      }],
    },
    state: 'succeeded',
    proposalId: 'proposal-repair-001',
    proposalState: 'pending_approval',
    proposalExpiresAt: stage4ActiveProposalExpiryInstant,
  }
  if (scenario === 'low-confidence') result.evidence.confidence = 0.52
  if (scenario === 'no-candidate') {
    result.assignmentCandidateUserId = null
    result.assignmentCandidateName = null
    result.proposalId = undefined
    result.proposalState = undefined
  }
  if (scenario === 'no-source') {
    result.evidence = { basis: 'unverified', confidence: 0.64, asOf: '', grounded: false, citations: [] }
  }
  if (scenario === 'failed') result.state = 'failed'
  if (scenario === 'timed-out') result.state = 'timed_out'
  if (scenario === 'degraded') result.state = 'degraded'
  if (scenario === 'stale') result.proposalState = 'stale'
  if (scenario === 'expired') {
    result.proposalState = 'expired'
    result.proposalExpiresAt = stage4ExpiredProposalInstant
  }
  return result
}

function noticeResult(scenario: NoticeScenario = 'success'): AiNoticeDraftResult {
  const result: AiNoticeDraftResult = {
    title: '关于开展学生宿舍安全检查的通知',
    type: '安全卫生',
    publisher: '由审批时当前用户重写',
    status: '草稿',
    content: noticeDraftContent,
    blocked: false,
    safetyMessages: [],
    version: 'notice-draft.v1',
    evidence: {
      basis: 'deterministic',
      confidence: 0.91,
      asOf: '2026-08-01T10:30:00+08:00',
      grounded: true,
      citations: [{
        id: 'stage4-notice-citation',
        label: '公告模板规范',
        locator: 'knowledge:notice-template.v1',
        version: 'v1',
        access: 'available',
      }],
    },
    state: 'succeeded',
    proposalId: 'proposal-notice-001',
    proposalState: 'pending_approval',
    proposalExpiresAt: stage4ActiveProposalExpiryInstant,
  }
  if (scenario === 'no-source') {
    result.evidence = { basis: 'unverified', confidence: 0.61, asOf: '', grounded: false, citations: [] }
  }
  if (scenario === 'degraded') result.state = 'degraded'
  if (scenario === 'failed') result.state = 'failed'
  if (scenario === 'timed-out') result.state = 'timed_out'
  if (scenario === 'stale') result.proposalState = 'stale'
  if (scenario === 'expired') {
    result.proposalState = 'expired'
    result.proposalExpiresAt = stage4ExpiredProposalInstant
  }
  return result
}

function pngDimensions(path: string) {
  const bytes = readFileSync(path)
  if (bytes.length < 24 || bytes.subarray(1, 4).toString('ascii') !== 'PNG') {
    throw new Error(`${path} 不是有效 PNG`)
  }
  return { width: bytes.readUInt32BE(16), height: bytes.readUInt32BE(20) }
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
    fileBinding('frontend/src/views/RepairManagementView.vue', resolve(process.cwd(), 'src/views/RepairManagementView.vue')),
    fileBinding('frontend/src/views/RepairVisualContract.test.ts', resolve(process.cwd(), 'src/views/RepairVisualContract.test.ts')),
    fileBinding('frontend/src/views/NoticeManagementView.vue', resolve(process.cwd(), 'src/views/NoticeManagementView.vue')),
    fileBinding('frontend/src/views/NoticeManagementView.test.ts', resolve(process.cwd(), 'src/views/NoticeManagementView.test.ts')),
    fileBinding('frontend/src/stores/ai.ts', resolve(process.cwd(), 'src/stores/ai.ts')),
    fileBinding('frontend/src/stores/ai-domain.test.ts', resolve(process.cwd(), 'src/stores/ai-domain.test.ts')),
    fileBinding('frontend/src/api/ai-client.ts', resolve(process.cwd(), 'src/api/ai-client.ts')),
    fileBinding('frontend/src/api/ai-demo.ts', resolve(process.cwd(), 'src/api/ai-demo.ts')),
    fileBinding('frontend/e2e/mock-api.ts', resolve(process.cwd(), 'e2e/mock-api.ts')),
    fileBinding('frontend/e2e/repair-notice-stage4-contract.spec.ts', resolve(process.cwd(), 'e2e/repair-notice-stage4-contract.spec.ts')),
    fileBinding('frontend/playwright.stage4-repair-notice.config.ts', resolve(process.cwd(), 'playwright.stage4-repair-notice.config.ts')),
    fileBinding(
      '.planning/20260727-ui-prototype-texture-reassessment/scripts/compare_visuals.py',
      resolve(process.cwd(), '../.planning/20260727-ui-prototype-texture-reassessment/scripts/compare_visuals.py'),
    ),
  ]
}

async function createPage(browser: Browser, viewport: { width: number; height: number }) {
  const context = await browser.newContext({
    baseURL,
    viewport,
    deviceScaleFactor: 1,
    reducedMotion: 'reduce',
  })
  const page = await context.newPage()
  await page.clock.setFixedTime(new Date(stage4ReferenceInstant))
  await page.addInitScript(() => {
    const original = Element.prototype.scrollIntoView
    Element.prototype.scrollIntoView = function scrollIntoView(options?: boolean | ScrollIntoViewOptions) {
      const record = window as typeof window & { __stage4ScrollIntoViewCalls?: Array<boolean | ScrollIntoViewOptions | undefined> }
      record.__stage4ScrollIntoViewCalls ??= []
      record.__stage4ScrollIntoViewCalls.push(options)
      return original.call(this, options)
    }
  })
  const mockApiControl = await mockApi(page, true)
  await routeRepairOrders(page)
  await routePublishedNotice(page)
  const browserErrors: string[] = []
  page.on('pageerror', (error) => browserErrors.push(`pageerror: ${error.message}`))
  page.on('console', (message) => {
    if (message.type() === 'error') browserErrors.push(`console: ${message.text()}`)
  })
  return { context, page, mockApiControl, browserErrors }
}

async function routePublishedNotice(page: Page) {
  await page.route('**/api/notices?*', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.fallback()
      return
    }
    const url = new URL(route.request().url())
    if (url.searchParams.get('status') !== '已发布') {
      await route.fallback()
      return
    }
    await route.fulfill({
      json: {
        code: 0,
        message: 'success',
        data: { records: [publishedNoticeFixture], total: 1, page: 1, pageSize: 1 },
      },
    })
  })
}

async function routeRepairOrders(page: Page) {
  await page.route('**/api/repair-orders?*', async (route) => {
    if (route.request().method() !== 'GET') {
      await route.fallback()
      return
    }
    const url = new URL(route.request().url())
    const keyword = url.searchParams.get('keyword')?.trim().toLocaleLowerCase()
    const type = url.searchParams.get('type')
    const status = url.searchParams.get('status')
    const pageNumber = Math.max(1, Number(url.searchParams.get('page')) || 1)
    const pageSize = Math.max(1, Number(url.searchParams.get('pageSize')) || 10)
    const filtered = repairFixtureOrders.filter((order) => (
      (!keyword || [order.code, order.reporter, order.location]
        .some((value) => value.toLocaleLowerCase().includes(keyword)))
      && (!type || order.type === type)
      && (!status || order.status === status)
    ))
    const offset = (pageNumber - 1) * pageSize
    await route.fulfill({
      json: {
        code: 0,
        message: 'success',
        data: {
          records: filtered.slice(offset, offset + pageSize),
          total: filtered.length,
          page: pageNumber,
          pageSize,
        },
      },
    })
  })
}

async function setSessionPermissions(page: Page, mockApiControl: MockApiControl, permissions: string[]) {
  mockApiControl.setSessionPermissions(permissions)
  await page.route('**/api/auth/me', async (route) => route.fulfill({
    json: {
      code: 0,
      message: 'success',
      data: {
        id: 1,
        username: 'stage4-reviewer',
        userName: '阶段四验收用户',
        roleCode: 'STAGE4_REVIEWER',
        roleCodes: ['STAGE4_REVIEWER'],
        permissions,
      },
    },
  }))
}

async function injectAiClient(
  page: Page,
  options: { repair?: AiRepairTriageResult | 'pending'; notice?: AiNoticeDraftResult | 'pending' },
) {
  await page.evaluate(async (fixture) => {
    const browserWindow = window as typeof window & {
      __stage4AiCalls?: { repair: number; notice: number }
    }
    browserWindow.__stage4AiCalls = { repair: 0, notice: 0 }
    const never = () => new Promise<never>(() => undefined)
    const client = {
      triageRepair: async () => {
        browserWindow.__stage4AiCalls!.repair += 1
        if (fixture.repair === 'pending') return never()
        if (!fixture.repair) throw new Error('Stage 4 fixture 未配置维修结果')
        return fixture.repair
      },
      draftNotice: async () => {
        browserWindow.__stage4AiCalls!.notice += 1
        if (fixture.notice === 'pending') return never()
        if (!fixture.notice) throw new Error('Stage 4 fixture 未配置公告结果')
        return fixture.notice
      },
    }
    const modulePath = '/src/api/ai-client.ts'
    const { setAiClient } = await import(modulePath)
    setAiClient(client)
  }, options)
}

async function aiCallCounts(page: Page) {
  return page.evaluate(() => (window as typeof window & {
    __stage4AiCalls?: { repair: number; notice: number }
  }).__stage4AiCalls ?? { repair: 0, notice: 0 })
}

async function seedRepairResult(page: Page, result: AiRepairTriageResult) {
  await page.evaluate(async (fixture) => {
    const modulePath = '/src/stores/ai.ts'
    const { useAiStore } = await import(modulePath)
    const store = useAiStore()
    store.repairTriage[fixture.repairId] = fixture
  }, result)
}

async function routeNoticeSnapshot(page: Page, scenario: SnapshotScenario) {
  if (scenario === 'success') return
  await page.route('**/api/notices?*', async (route) => {
    const url = new URL(route.request().url())
    if (url.searchParams.get('status') !== '已发布') {
      await route.fallback()
      return
    }
    if (scenario === 'loading') {
      await new Promise<void>(() => undefined)
      return
    }
    if (scenario === 'error') {
      await route.fulfill({
        status: 500,
        json: { code: 500, message: 'Stage 4 最近公告读取失败', data: null },
      })
      return
    }
    await route.fulfill({
      json: { code: 0, message: 'success', data: { records: [], total: 0, page: 1, pageSize: 1 } },
    })
  })
}

async function openRepair(page: Page) {
  await page.goto('/repairs')
  await expect(page.getByRole('region', { name: '报修列表' })).toBeVisible()
  await expect(page.getByRole('region', { name: '报修列表' })).toContainText('共 10 条')
  const detailCode = page.locator('.repair-detail-grid dd[aria-label]').first()
  await expect(detailCode).toHaveAttribute('aria-label', firstRepairFixture.code)
  await expect(detailCode).toContainText(firstRepairFixture.code.slice(-6))
}

async function expectRepairFixtureDensity(page: Page, mobile: boolean, label: string) {
  await expect(page.locator('.repair-row-selector')).toHaveCount(10)
  await expect(page.locator('.repair-mobile-list button')).toHaveCount(10)
  const visibleCodes = await page.locator(mobile
    ? '.repair-mobile-list button strong'
    : '.repair-desktop-table .repair-code').allTextContents()
  expect(visibleCodes, `${label} 应展示 10 个可辨识的压缩工单号`).toHaveLength(10)
  expect(new Set(visibleCodes).size, `${label} 长前缀工单号末尾必须保持可辨`).toBe(10)
  for (const [index, code] of visibleCodes.entries()) {
    expect(code, `${label} 第 ${index + 1} 个工单号应保留唯一末尾`)
      .toMatch(new RegExp(`${String(index + 1).padStart(6, '0')}$`))
  }
  return visibleCodes
}

async function expectRepairDesktopColumnsContained(page: Page, label: string) {
  const rows = await page.locator('.repair-desktop-table .ant-table-tbody > tr').evaluateAll((elements) => elements.map((row) => {
    const code = row.querySelector<HTMLElement>('.repair-code')
    const date = row.querySelector<HTMLElement>('.repair-date')
    const action = row.querySelector<HTMLButtonElement>('.repair-row-actions button')
    const codeCell = code?.closest<HTMLElement>('td')
    const nextCell = codeCell?.nextElementSibling as HTMLElement | null
    const actionCell = action?.closest<HTMLElement>('td')
    if (!code || !date || !action || !codeCell || !nextCell || !actionCell) throw new Error('维修表格行缺少工单号、日期、操作按钮或相邻列')
    const codeBounds = code.getBoundingClientRect()
    const cellBounds = codeCell.getBoundingClientRect()
    const nextBounds = nextCell.getBoundingClientRect()
    const actionBounds = action.getBoundingClientRect()
    const actionCellBounds = actionCell.getBoundingClientRect()
    const actionRange = document.createRange()
    actionRange.selectNodeContents(action)
    const actionTextBounds = actionRange.getBoundingClientRect()
    const style = getComputedStyle(code)
    return {
      code: code.getAttribute('aria-label') ?? code.textContent?.trim() ?? '',
      renderedCode: code.textContent?.trim() ?? '',
      renderedDate: date.textContent?.trim() ?? '',
      codeLeft: codeBounds.left,
      codeRight: codeBounds.right,
      codeWidth: codeBounds.width,
      cellLeft: cellBounds.left,
      cellRight: cellBounds.right,
      cellWidth: cellBounds.width,
      nextCellLeft: nextBounds.left,
      overflow: style.overflow,
      textOverflow: style.textOverflow,
      whiteSpace: style.whiteSpace,
      maxWidth: style.maxWidth,
      rowHeight: row.getBoundingClientRect().height,
      actionText: action.textContent?.trim() ?? '',
      actionLeft: actionBounds.left,
      actionRight: actionBounds.right,
      actionCellLeft: actionCellBounds.left,
      actionCellRight: actionCellBounds.right,
      actionTextLeft: actionTextBounds.left,
      actionTextRight: actionTextBounds.right,
      actionClientWidth: action.clientWidth,
      actionScrollWidth: action.scrollWidth,
    }
  }))
  expect(rows, `${label} 应检查 10 行维修表格`).toHaveLength(10)
  for (const [index, row] of rows.entries()) {
    expect(row.codeLeft, `${label} 第 ${index + 1} 行工单号不得越出本列左侧`).toBeGreaterThanOrEqual(row.cellLeft - 0.5)
    expect(row.codeRight, `${label} 第 ${index + 1} 行工单号不得越出本列右侧`).toBeLessThanOrEqual(row.cellRight + 0.5)
    expect(row.codeRight, `${label} 第 ${index + 1} 行工单号不得覆盖相邻位置列`).toBeLessThanOrEqual(row.nextCellLeft + 0.5)
    expect(row.renderedDate, `${label} 第 ${index + 1} 行日期应使用 MM-DD HH:mm`).toMatch(/^\d{2}-\d{2} \d{2}:\d{2}$/)
    expect(row.overflow).toBe('hidden')
    expect(row.textOverflow).toBe('ellipsis')
    expect(row.whiteSpace).toBe('nowrap')
    expect(row.rowHeight, `${label} 第 ${index + 1} 行应接近原型的稳定纵向节奏`).toBeGreaterThanOrEqual(48)
    expect(row.actionText, `${label} 第 ${index + 1} 行操作文案必须完整`).toBe('查看')
    expect(row.actionLeft, `${label} 第 ${index + 1} 行操作按钮不得越出单元格左侧`).toBeGreaterThanOrEqual(row.actionCellLeft - 0.5)
    expect(row.actionRight, `${label} 第 ${index + 1} 行操作按钮不得越出单元格右侧`).toBeLessThanOrEqual(row.actionCellRight + 0.5)
    expect(row.actionTextLeft, `${label} 第 ${index + 1} 行操作文本不得越出单元格左侧`).toBeGreaterThanOrEqual(row.actionCellLeft - 0.5)
    expect(row.actionTextRight, `${label} 第 ${index + 1} 行操作文本不得越出单元格右侧`).toBeLessThanOrEqual(row.actionCellRight + 0.5)
    expect(row.actionScrollWidth, `${label} 第 ${index + 1} 行操作按钮不得内部裁切`).toBeLessThanOrEqual(row.actionClientWidth)
  }
  return rows
}

async function expectRepairDatesFullyVisible(page: Page, label: string) {
  const rows = await page.locator('.repair-desktop-table .repair-date').evaluateAll((elements) => elements.map((date) => {
    const cell = date.closest<HTMLElement>('td')
    if (!cell) throw new Error('维修日期缺少所属表格列')
    const bounds = date.getBoundingClientRect()
    const cellBounds = cell.getBoundingClientRect()
    const range = document.createRange()
    range.selectNodeContents(date)
    const rangeBounds = range.getBoundingClientRect()
    const style = getComputedStyle(date)
    const cellStyle = getComputedStyle(cell)
    const canvas = document.createElement('canvas')
    const context = canvas.getContext('2d')
    if (!context) throw new Error('无法创建日期文本测量上下文')
    context.font = style.font
    const text = date.textContent?.trim() ?? ''
    const measuredTextWidth = context.measureText(text).width
    const availableCellWidth = cellBounds.width
      - Number.parseFloat(cellStyle.paddingLeft)
      - Number.parseFloat(cellStyle.paddingRight)
    return {
      text,
      clientWidth: date.clientWidth,
      scrollWidth: date.scrollWidth,
      elementWidth: bounds.width,
      rangeTextWidth: rangeBounds.width,
      measuredTextWidth,
      cellWidth: cellBounds.width,
      availableCellWidth,
      overflow: style.overflow,
      textOverflow: style.textOverflow,
      whiteSpace: style.whiteSpace,
    }
  }))
  expect(rows, `${label} 应检查 10 个维修日期`).toHaveLength(10)
  const evidence = rows.map((row) => ({
    ...row,
    scrollOverflowPx: row.scrollWidth - row.clientWidth,
    rangeOverflowPx: row.rangeTextWidth - row.elementWidth,
    measuredOverflowPx: row.measuredTextWidth - row.elementWidth,
  }))
  const maxOverflowPx = Math.max(...evidence.map((row) => Math.max(
    row.scrollOverflowPx,
    row.rangeOverflowPx,
    row.measuredOverflowPx,
  )))
  for (const [index, row] of evidence.entries()) {
    expect(row.text, `${label} 第 ${index + 1} 行日期必须为完整 MM-DD HH:mm`).toMatch(/^\d{2}-\d{2} \d{2}:\d{2}$/)
    expect(
      row.scrollWidth,
      `${label} 第 ${index + 1} 行日期被 text-overflow 截断：${JSON.stringify({ row, maxOverflowPx })}`,
    ).toBeLessThanOrEqual(row.clientWidth + 1)
    expect(
      Math.max(row.rangeTextWidth, row.measuredTextWidth),
      `${label} 第 ${index + 1} 行日期文本宽度超过元素：${JSON.stringify({ row, maxOverflowPx })}`,
    ).toBeLessThanOrEqual(row.elementWidth + 1)
  }
  return { rows: evidence, maxOverflowPx }
}

async function expectNoticeDraftDensity(
  page: Page,
  label: string,
  requireEditorFullyVisible: boolean,
  requireExpandedSuggestion: boolean,
) {
  const editor = page.getByRole('textbox', { name: 'AI 草稿正文' })
  const editorValue = await editor.inputValue()
  expect(editorValue, `${label} 主编辑器必须保留完整确定性草稿`).toBe(noticeDraftContent)
  expect(editorValue.length, `${label} AI 草稿字数应接近原型密度`).toBeGreaterThanOrEqual(220)
  expect(editorValue.length, `${label} AI 草稿字数应接近原型密度`).toBeLessThanOrEqual(260)
  expect(editorValue).not.toMatch(/(?:1[3-9]\d{9}|\b\d{8,18}\b|<[^>]+>|system prompt|忽略|绕过)/i)

  const diffArticles = page.locator('.notice-diff-panel article')
  const currentArticle = diffArticles.nth(0)
  const suggestionArticle = diffArticles.nth(1)
  const currentText = (await currentArticle.locator('pre').textContent())?.trim() ?? ''
  const suggestionText = (await suggestionArticle.locator('pre').textContent()) ?? ''
  expect(currentText).toBe(publishedNoticeFixture.content)
  expect(currentText.length, `${label} 当前内容应保持紧凑基线`).toBeGreaterThanOrEqual(25)
  expect(currentText.length, `${label} 当前内容应保持紧凑基线`).toBeLessThanOrEqual(45)
  expect(suggestionText, `${label} 下方 AI 建议必须与主编辑器一致`).toBe(noticeDraftContent)

  await expect(page.locator('.notice-draft-footer > span').last())
    .toHaveText(`字数：${noticeDraftContent.length}`)
  await expect(currentArticle.locator(':scope > span'))
    .toContainText(`字数：${publishedNoticeFixture.content.length}`)
  await expect(suggestionArticle.locator(':scope > span'))
    .toContainText(`字数：${noticeDraftContent.length}`)
  await expect(suggestionArticle.locator(':scope > span'))
    .toContainText(`较当前内容 +${noticeDraftContent.length - publishedNoticeFixture.content.length}`)

  const geometry = await page.evaluate(() => {
    const editorElement = document.querySelector<HTMLTextAreaElement>('textarea[aria-label="AI 草稿正文"]')
    const diffPanel = document.querySelector<HTMLElement>('.notice-diff-panel')
    const suggestion = document.querySelectorAll<HTMLElement>('.notice-diff-panel article')[1]
    const suggestionPre = suggestion?.querySelector<HTMLElement>('pre')
    if (!editorElement || !diffPanel || !suggestion || !suggestionPre) {
      throw new Error('公告草稿密度合同缺少编辑器或变更预览')
    }
    const editorStyle = getComputedStyle(editorElement)
    const suggestionStyle = getComputedStyle(suggestionPre)
    const panelBounds = diffPanel.getBoundingClientRect()
    const articleBounds = suggestion.getBoundingClientRect()
    const suggestionBounds = suggestionPre.getBoundingClientRect()
    return {
      editor: {
        valueLength: editorElement.value.length,
        clientWidth: editorElement.clientWidth,
        scrollWidth: editorElement.scrollWidth,
        clientHeight: editorElement.clientHeight,
        scrollHeight: editorElement.scrollHeight,
        scrollTop: editorElement.scrollTop,
        overflowX: editorStyle.overflowX,
        overflowY: editorStyle.overflowY,
      },
      suggestion: {
        clientWidth: suggestionPre.clientWidth,
        scrollWidth: suggestionPre.scrollWidth,
        clientHeight: suggestionPre.clientHeight,
        scrollHeight: suggestionPre.scrollHeight,
        overflowX: suggestionStyle.overflowX,
        overflowY: suggestionStyle.overflowY,
        left: suggestionBounds.left,
        right: suggestionBounds.right,
        top: suggestionBounds.top,
        bottom: suggestionBounds.bottom,
      },
      article: { left: articleBounds.left, right: articleBounds.right },
      panel: { left: panelBounds.left, right: panelBounds.right },
    }
  })
  expect(geometry.editor.valueLength).toBe(noticeDraftContent.length)
  expect(geometry.editor.scrollWidth, `${label} 主编辑器不得横向裁切`).toBeLessThanOrEqual(geometry.editor.clientWidth + 1)
  if (requireEditorFullyVisible) {
    expect(
      geometry.editor.scrollHeight,
      `${label} 主编辑器必须完整显示正文：${JSON.stringify(geometry.editor)}`,
    ).toBeLessThanOrEqual(geometry.editor.clientHeight + 1)
    expect(geometry.editor.scrollTop, `${label} 主编辑器必须从正文顶部显示`).toBe(0)
  }
  expect(geometry.suggestion.scrollWidth, `${label} 下方 AI 建议不得横向裁切`).toBeLessThanOrEqual(geometry.suggestion.clientWidth + 1)
  if (requireExpandedSuggestion) {
    expect(geometry.suggestion.overflowY, `${label} 移动端 AI 建议不得使用隐藏嵌套滚动`).toBe('visible')
    expect(geometry.suggestion.scrollHeight, `${label} 移动端 AI 建议必须完整展开`).toBeLessThanOrEqual(geometry.suggestion.clientHeight + 1)
  } else {
    expect(geometry.suggestion.overflowY, `${label} 桌面 AI 建议应允许自身滚动`).toBe('auto')
  }
  expect(geometry.suggestion.left, `${label} 下方 AI 建议不得越出文章左侧`).toBeGreaterThanOrEqual(geometry.article.left - 1)
  expect(geometry.suggestion.right, `${label} 下方 AI 建议不得越出文章右侧`).toBeLessThanOrEqual(geometry.article.right + 1)
  expect(geometry.suggestion.left, `${label} 下方 AI 建议不得越出面板左侧`).toBeGreaterThanOrEqual(geometry.panel.left - 1)
  expect(geometry.suggestion.right, `${label} 下方 AI 建议不得越出面板右侧`).toBeLessThanOrEqual(geometry.panel.right + 1)
  return { draftLength: noticeDraftContent.length, currentLength: publishedNoticeFixture.content.length, geometry }
}

async function openNotice(page: Page, waitForSnapshot = true) {
  await page.goto('/notices/create', { waitUntil: 'domcontentloaded' })
  await page.waitForURL((url) => url.pathname === '/notices/create')
  const workbench = page.locator('.notice-create-page[role="region"][aria-label="公告 AI 起草工作台"]')
  await workbench.waitFor({ state: 'visible' })
  await expect(workbench.getByRole('dialog', { name: '公告 AI 起草' })).toBeVisible()
  if (waitForSnapshot) await expect(page.locator('.notice-diff-status')).not.toContainText('正在读取')
}

function colorChannels(color: string) {
  const values = color.match(/[\d.]+/g)?.slice(0, 3).map(Number)
  if (!values || values.length !== 3) throw new Error(`无法解析颜色 ${color}`)
  return values
}

function linearChannel(value: number) {
  const normalized = value / 255
  return normalized <= 0.03928 ? normalized / 12.92 : ((normalized + 0.055) / 1.055) ** 2.4
}

function luminance(color: string) {
  const [red, green, blue] = colorChannels(color)
  return 0.2126 * linearChannel(red) + 0.7152 * linearChannel(green) + 0.0722 * linearChannel(blue)
}

function contrastRatio(foreground: string, background: string) {
  const lighter = Math.max(luminance(foreground), luminance(background))
  const darker = Math.min(luminance(foreground), luminance(background))
  return (lighter + 0.05) / (darker + 0.05)
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
      outlineColor: style.outlineColor,
      outlineWidth: style.outlineWidth,
      width: bounds.width,
      height: bounds.height,
      x: bounds.x,
      y: bounds.y,
      right: bounds.right,
      bottom: bounds.bottom,
    }
  })
}

async function expectNoHorizontalOverflow(page: Page, label: string) {
  const geometry = await page.evaluate(() => ({
    viewportWidth: document.documentElement.clientWidth,
    rootScrollWidth: document.documentElement.scrollWidth,
    bodyScrollWidth: document.body.scrollWidth,
    scrollHeight: Math.max(document.documentElement.scrollHeight, document.body.scrollHeight),
  }))
  expect(
    Math.max(geometry.rootScrollWidth, geometry.bodyScrollWidth),
    `${label} 不得发生页面级横向溢出：${JSON.stringify(geometry)}`,
  ).toBeLessThanOrEqual(geometry.viewportWidth + 1)
  return geometry
}

async function expectPanelsWithinViewport(page: Page, selectors: string[], label: string) {
  const viewportWidth = await page.evaluate(() => document.documentElement.clientWidth)
  for (const selector of selectors) {
    const locator = page.locator(selector)
    await expect(locator, `${label} 缺少 ${selector}`).toBeVisible()
    const bounds = await locator.boundingBox()
    expect(bounds, `${label} 的 ${selector} 必须具有布局`).not.toBeNull()
    expect(bounds!.x, `${label} 的 ${selector} 左侧越界`).toBeGreaterThanOrEqual(-1)
    expect(bounds!.x + bounds!.width, `${label} 的 ${selector} 右侧越界`).toBeLessThanOrEqual(viewportWidth + 1)
  }
}

async function expectMinimumTarget(locator: Locator, label: string) {
  const bounds = await locator.boundingBox()
  expect(bounds, `${label} 必须具有布局`).not.toBeNull()
  expect(bounds!.height, `${label} 高度不得小于 44px`).toBeGreaterThanOrEqual(44)
  expect(bounds!.width, `${label} 宽度不得小于 44px`).toBeGreaterThanOrEqual(44)
  return bounds!
}

async function expectReadableText(text: Locator, surface: Locator, label: string, minimum = 4.5) {
  const colors = await Promise.all([
    text.evaluate((element) => getComputedStyle(element).color),
    surface.evaluate((element) => getComputedStyle(element).backgroundColor),
  ])
  expect(
    contrastRatio(colors[0], colors[1]),
    `${label} 对比度不足：${JSON.stringify({ foreground: colors[0], background: colors[1] })}`,
  ).toBeGreaterThanOrEqual(minimum)
  return { foreground: colors[0], background: colors[1], ratio: contrastRatio(colors[0], colors[1]) }
}

async function expectKeyboardFocus(locator: Locator, label: string) {
  await locator.focus()
  await expect(locator, `${label} 应可通过键盘聚焦`).toBeFocused()
  const appearance = await locator.evaluate((element) => {
    const style = getComputedStyle(element)
    return {
      outlineColor: style.outlineColor,
      outlineWidth: style.outlineWidth,
      backgroundColor: style.backgroundColor,
    }
  })
  expect(Number.parseFloat(appearance.outlineWidth), `${label} 应显示 outline`).toBeGreaterThanOrEqual(2)
  expect(
    contrastRatio(appearance.outlineColor, appearance.backgroundColor),
    `${label} 的焦点指示器对比度不足：${JSON.stringify(appearance)}`,
  ).toBeGreaterThanOrEqual(3)
  return appearance
}

async function resetCaptureScroll(page: Page, surface: 'repair' | 'notice') {
  const scrollState = await page.evaluate(async (fixtureSurface) => {
    window.scrollTo(0, 0)
    document.documentElement.scrollTop = 0
    document.body.scrollTop = 0
    document.scrollingElement?.scrollTo(0, 0)
    for (const element of document.querySelectorAll<HTMLElement>('body *')) {
      if (element.scrollTop !== 0) element.scrollTop = 0
      if (element.scrollLeft !== 0) element.scrollLeft = 0
    }
    await new Promise<void>((resolve) => {
      requestAnimationFrame(() => requestAnimationFrame(() => resolve()))
    })
    const nonZeroScrollers = Array.from(document.querySelectorAll<HTMLElement>('body *'))
      .filter((element) => element.scrollTop !== 0 || element.scrollLeft !== 0)
      .map((element) => ({
        tag: element.tagName.toLowerCase(),
        className: element.className,
        scrollTop: element.scrollTop,
        scrollLeft: element.scrollLeft,
      }))
    const repairInternal = fixtureSurface === 'repair'
      ? Array.from(document.querySelectorAll<HTMLElement>(
          '.repair-detail-stack, .repair-list-panel, .repair-desktop-table .ant-table-body',
        )).map((element) => ({
          className: element.className,
          scrollTop: element.scrollTop,
          scrollLeft: element.scrollLeft,
          clientHeight: element.clientHeight,
          scrollHeight: element.scrollHeight,
        }))
      : []
    return {
      surface: fixtureSurface,
      windowScrollX: window.scrollX,
      windowScrollY: window.scrollY,
      documentScrollTop: document.scrollingElement?.scrollTop ?? 0,
      nonZeroScrollers,
      repairInternal,
    }
  }, surface)
  expect(scrollState.windowScrollX, `${surface} 截图前 window.scrollX 必须为 0`).toBe(0)
  expect(scrollState.windowScrollY, `${surface} 截图前 window.scrollY 必须为 0`).toBe(0)
  expect(scrollState.documentScrollTop, `${surface} 截图前文档 scrollTop 必须为 0`).toBe(0)
  expect(scrollState.nonZeroScrollers, `${surface} 截图前所有内部滚动位置必须归零`).toEqual([])
  return scrollState
}

async function expectRepairFirstViewportComplete(page: Page, label: string) {
  const scrollState = await resetCaptureScroll(page, 'repair')
  const geometry = await page.evaluate(() => {
    const stack = document.querySelector<HTMLElement>('.repair-detail-stack')
    const selectors = ['.repair-detail-panel', '.repair-ai-panel', '.repair-flow-panel']
    const listPanel = document.querySelector<HTMLElement>('.repair-list-panel')
    const safetyPanel = document.querySelector<HTMLElement>('.repair-safety-panel')
    const attachmentEntry = document.querySelector<HTMLButtonElement>('.repair-attachment-entry')
    const attachmentSurface = document.querySelector<HTMLElement>('.repair-attachment-preview, .repair-attachment-state')
    if (!stack || !listPanel || !safetyPanel || !attachmentEntry || !attachmentSurface) throw new Error('缺少维修详情、列表、安全带或附件表面')
    const stackBounds = stack.getBoundingClientRect()
    const listBounds = listPanel.getBoundingClientRect()
    const safetyBounds = safetyPanel.getBoundingClientRect()
    const attachmentStyle = getComputedStyle(attachmentEntry)
    return {
      viewportHeight: document.documentElement.clientHeight,
      stack: {
        top: stackBounds.top,
        bottom: stackBounds.bottom,
        height: stackBounds.height,
        scrollTop: stack.scrollTop,
        clientHeight: stack.clientHeight,
        scrollHeight: stack.scrollHeight,
      },
      regions: selectors.map((selector) => {
        const element = document.querySelector<HTMLElement>(selector)
        if (!element) throw new Error(`缺少 ${selector}`)
        const bounds = element.getBoundingClientRect()
        return { selector, top: bounds.top, bottom: bounds.bottom, height: bounds.height }
      }),
      list: { top: listBounds.top, bottom: listBounds.bottom, height: listBounds.height },
      safety: { top: safetyBounds.top, bottom: safetyBounds.bottom, height: safetyBounds.height },
      attachmentEntry: {
        clientHeight: attachmentEntry.clientHeight,
        scrollHeight: attachmentEntry.scrollHeight,
        whiteSpace: attachmentStyle.whiteSpace,
      },
      attachmentThumbnails: Array.from(document.querySelectorAll<HTMLImageElement>('[data-testid="repair-attachment-thumbnail"]')).map((image) => {
        const bounds = image.getBoundingClientRect()
        return {
          width: bounds.width,
          height: bounds.height,
          naturalWidth: image.naturalWidth,
          naturalHeight: image.naturalHeight,
          objectFit: getComputedStyle(image).objectFit,
          clipped: image.scrollWidth > image.clientWidth + 1 || image.scrollHeight > image.clientHeight + 1,
        }
      }),
      prototypeWeight: Object.fromEntries([
        ['attachment', '.repair-attachment-preview, .repair-attachment-state'],
        ['metric', '.repair-ai-metrics > div'],
        ['flow', '.repair-flow article'],
        ['safety', '.repair-safety-grid article'],
      ].map(([name, selector]) => {
        const element = document.querySelector<HTMLElement>(selector)
        if (!element) throw new Error(`缺少 ${selector}`)
        return [name, element.getBoundingClientRect().height]
      })),
    }
  })
  expect(geometry.stack.scrollTop, `${label} 详情 scroller 必须位于顶部`).toBe(0)
  for (const region of geometry.regions) {
    expect(region.top, `${label} ${region.selector} 顶部被内部 scroller 裁切`).toBeGreaterThanOrEqual(geometry.stack.top - 1)
    expect(region.bottom, `${label} ${region.selector} 底部被内部 scroller 裁切`).toBeLessThanOrEqual(geometry.stack.bottom + 1)
    expect(region.bottom, `${label} ${region.selector} 未完整进入首屏`).toBeLessThanOrEqual(geometry.viewportHeight + 1)
  }
  expect(geometry.prototypeWeight.attachment, `${label} 附件媒体区纵向重量不足`).toBeGreaterThanOrEqual(56)
  expect(geometry.attachmentThumbnails, `${label} 视觉证据附件必须覆盖两张缩略图`).toHaveLength(2)
  for (const thumbnail of geometry.attachmentThumbnails) {
    expect(thumbnail.naturalWidth, `${label} 附件缩略图资源未加载`).toBeGreaterThan(0)
    expect(thumbnail.naturalHeight, `${label} 附件缩略图资源未加载`).toBeGreaterThan(0)
    expect(thumbnail.width, `${label} 附件缩略图宽度不足`).toBeGreaterThanOrEqual(72)
    expect(thumbnail.height, `${label} 附件缩略图高度不足`).toBeGreaterThanOrEqual(50)
    expect(thumbnail.objectFit, `${label} 附件缩略图未使用稳定裁切`).toBe('cover')
    expect(thumbnail.clipped, `${label} 附件缩略图内部发生溢出`).toBe(false)
  }
  expect(geometry.prototypeWeight.metric, `${label} AI 指标纵向重量不足`).toBeGreaterThanOrEqual(64)
  expect(geometry.prototypeWeight.flow, `${label} 审批流程卡纵向重量不足`).toBeGreaterThanOrEqual(88)
  expect(geometry.prototypeWeight.safety, `${label} 安全状态卡纵向重量不足`).toBeGreaterThanOrEqual(88)
  expect(geometry.list.bottom, `${label} 左侧列表底边应与右侧工作栈收敛`).toBeGreaterThanOrEqual(geometry.stack.bottom - 8)
  expect(geometry.safety.top, `${label} 安全状态带应在原型位置进入首屏`).toBeLessThanOrEqual(832)
  expect(geometry.safety.height, `${label} 安全状态带纵向重量不足`).toBeGreaterThanOrEqual(156)
  expect(geometry.safety.bottom, `${label} 安全状态带应贴近正式视口底边`).toBeGreaterThanOrEqual(989)
  expect(geometry.regions[0]?.height, `${label} 工单详情媒体与操作区过度压缩`).toBeGreaterThanOrEqual(230)
  expect(geometry.regions[1]?.height, `${label} AI 建议区不应挤占详情和流程`).toBeLessThanOrEqual(258)
  expect(geometry.regions[2]?.height, `${label} 执行流程应保持原型式紧凑单行节奏`).toBeLessThanOrEqual(158)
  expect(geometry.attachmentEntry.whiteSpace, `${label} 补充工单记录必须保持单行`).toBe('nowrap')
  expect(geometry.attachmentEntry.scrollHeight, `${label} 补充工单记录不得内部换行裁切`).toBeLessThanOrEqual(geometry.attachmentEntry.clientHeight)
  return { scrollState, geometry }
}

async function expectRepairDetailStackUnclipped(page: Page, label: string) {
  const geometry = await page.evaluate(() => {
    const stack = document.querySelector<HTMLElement>('.repair-detail-stack')
    const approvalButton = document.querySelector<HTMLButtonElement>('.repair-flow article:last-of-type button')
    const safetyPanel = document.querySelector<HTMLElement>('.repair-safety-panel')
    if (!stack || !approvalButton || !safetyPanel) throw new Error('缺少维修详情栈、审批按钮或安全状态带')

    const stackBounds = stack.getBoundingClientRect()
    const approvalBounds = approvalButton.getBoundingClientRect()
    const safetyBounds = safetyPanel.getBoundingClientRect()
    return {
      stack: {
        top: stackBounds.top,
        bottom: stackBounds.bottom,
        clientHeight: stack.clientHeight,
        scrollHeight: stack.scrollHeight,
      },
      approval: { top: approvalBounds.top, bottom: approvalBounds.bottom, height: approvalBounds.height },
      safety: { top: safetyBounds.top, bottom: safetyBounds.bottom },
    }
  })

  expect(
    geometry.stack.scrollHeight,
    `${label} 右侧详情栈不得依赖隐藏的内部滚动：${JSON.stringify(geometry)}`,
  ).toBeLessThanOrEqual(geometry.stack.clientHeight + 1)
  expect(geometry.approval.bottom, `${label} 审批入口不得被详情栈底部裁切`).toBeLessThanOrEqual(geometry.stack.bottom - 1)
  expect(geometry.approval.bottom, `${label} 审批入口与安全状态带之间应保留至少 8px`).toBeLessThanOrEqual(geometry.safety.top - 8)
  return geometry
}

async function recordScreenshot(page: Page, file: string, meta: Record<string, unknown>, fullPage = false) {
  const surface = meta.surface === 'repair' ? 'repair' : 'notice'
  const scrollOrigin = await resetCaptureScroll(page, surface)
  const path = resolve(capturesDirectory, file)
  await page.screenshot({ path, animations: 'disabled', caret: 'hide', fullPage })
  const bytes = readFileSync(path)
  const dimensions = pngDimensions(path)
  screenshots.push({
    file: `captures/${file}`,
    bytes: statSync(path).size,
    sha256: createHash('sha256').update(bytes).digest('hex').toUpperCase(),
    width: dimensions.width,
    height: dimensions.height,
    fullPage,
    scrollOrigin,
    ...meta,
  })
}

test.beforeAll(() => mkdirSync(capturesDirectory, { recursive: true }))

test.afterAll(() => {
  const computedStylesPath = resolve(outputDirectory, 'computed-styles.json')
  writeFileSync(computedStylesPath, `${JSON.stringify(computedEvidence, null, 2)}\n`)
  writeFileSync(resolve(outputDirectory, 'manifest.json'), `${JSON.stringify({
    generatedAt: new Date().toISOString(),
    fixtureReferenceInstant: stage4ReferenceInstant,
    fixtureMode: 'mockApi business HTTP fixture + typed deterministic AiClient injection through the real page and Pinia actions',
    productionWrites: false,
    visualFidelityDecision: 'NOT_ASSERTED',
    differenceUse: 'hotspot-localization-only',
    reducedMotion: true,
    deviceScaleFactor: 1,
    viewports: viewports.map(({ name }) => name),
    zoomEquivalent: { viewport: '960x540', sourceViewport: '1920x1080', scale: '200%' },
    repairBusinessFixture: {
      transport: 'HTTP-shaped Playwright route layered over mockApi',
      total: repairFixtureOrders.length,
      commonPrefix: 'WX-STAGE4-COMMON-PREFIX-',
      firstCode: firstRepairFixture.code,
      completedCode: completedRepairFixture.code,
    },
    noticeBusinessFixture: {
      transport: 'HTTP-shaped published notice snapshot + typed deterministic AiClient draft',
      currentContentLength: publishedNoticeFixture.content.length,
      draftContentLength: noticeDraftContent.length,
      piiFree: true,
    },
    stateMatrix: {
      repair: ['success', 'low-confidence', 'no-candidate', 'no-permission', 'completed', 'no-source', 'failed', 'timed-out', 'degraded'],
      notice: ['idle', 'loading', 'success', 'pii-blocked', 'no-source', 'degraded', 'failed', 'timed-out', 'stale', 'expired', 'no-approval-permission', 'snapshot-loading', 'snapshot-empty', 'snapshot-error'],
    },
    fixtureBoundaries: [
      'Business data is served through the real HTTP client and mockApi route contract.',
      'Repair list density uses a ten-row HTTP-shaped route with common long prefixes and unique visible suffixes.',
      'Notice density uses a compact HTTP published snapshot and a safe 220-260 character deterministic draft.',
      'AI results are injected as a typed deterministic AiClient and consumed by real Pinia actions.',
      'The fixture never calls a production provider and never executes a business write.',
      'Completed and permission-blocked states do not bypass their disabled UI actions.',
    ],
    sourceBindings: evidenceBindings(),
    screenshots,
    computedStylesBinding: fileBinding('fixtures/computed-styles.json', computedStylesPath),
  }, null, 2)}\n`)
})

test('维修成功态通过真实生成按钮进入可审批状态', async ({ browser }) => {
  const { context, page, browserErrors } = await createPage(browser, { width: 1505, height: 1045 })
  try {
    await openRepair(page)
    const result = repairResult()
    const referenceTime = Date.parse(stage4ReferenceInstant)
    expect(await page.evaluate(() => Date.now())).toBe(referenceTime)
    expect(Date.parse(result.proposalExpiresAt ?? '')).toBeGreaterThan(referenceTime)
    expect(Date.parse(result.proposalExpiresAt ?? '') - referenceTime).toBeLessThanOrEqual(14 * millisecondsPerDay)
    expect(result.proposalExpiresAt).toMatch(/\+08:00$/)
    await injectAiClient(page, { repair: result })
    await page.getByRole('button', { name: '生成分诊建议' }).click()
    await expect(page.locator('[data-testid="repair-confidence"]')).toHaveText('92%')
    await expect(page.getByRole('button', { name: '查看审批提案' })).toBeEnabled()
    expect(await aiCallCounts(page)).toEqual({ repair: 1, notice: 0 })
    computedEvidence.repairSuccess = {
      page: await elementStyle(page.locator('.repair-page')),
      aiPanel: await elementStyle(page.getByRole('region', { name: '维修智能分诊' })),
      approvalButton: await elementStyle(page.getByRole('button', { name: '查看审批提案' })),
    }
    await recordScreenshot(page, 'repair-success-smoke-1505x1045.png', {
      surface: 'repair', state: 'success', viewport: '1505x1045', smoke: true,
    })
    expect(browserErrors).toEqual([])
  } finally {
    await context.close()
  }
})

test('公告成功态通过真实生成按钮进入可审批状态', async ({ browser }) => {
  const { context, page, browserErrors } = await createPage(browser, { width: 1505, height: 1045 })
  try {
    await openNotice(page)
    const result = noticeResult()
    const referenceTime = Date.parse(stage4ReferenceInstant)
    expect(await page.evaluate(() => Date.now())).toBe(referenceTime)
    expect(Date.parse(result.proposalExpiresAt ?? '')).toBeGreaterThan(referenceTime)
    expect(Date.parse(result.proposalExpiresAt ?? '') - referenceTime).toBeLessThanOrEqual(14 * millisecondsPerDay)
    expect(result.proposalExpiresAt).toMatch(/\+08:00$/)
    await injectAiClient(page, { notice: result })
    await page.getByRole('textbox', { name: '公告要点' }).fill('本周五开展宿舍安全检查，请提前整理公共区域')
    await page.getByRole('button', { name: '生成 AI 草稿' }).click()
    await expect(page.getByRole('textbox', { name: '公告标题' })).toHaveValue('关于开展学生宿舍安全检查的通知')
    await expect(page.getByRole('button', { name: '查看审批提案，提交审批' })).toBeEnabled()
    expect(await aiCallCounts(page)).toEqual({ repair: 0, notice: 1 })
    computedEvidence.noticeSuccess = {
      page: await elementStyle(page.getByRole('region', { name: '公告 AI 起草工作台' })),
      draftPanel: await elementStyle(page.getByRole('region', { name: 'AI 草稿' })),
      approvalButton: await elementStyle(page.getByRole('button', { name: '查看审批提案，提交审批' })),
    }
    await recordScreenshot(page, 'notice-success-smoke-1505x1045.png', {
      surface: 'notice', state: 'success', viewport: '1505x1045', smoke: true,
    })
    expect(browserErrors).toEqual([])
  } finally {
    await context.close()
  }
})

test('维修成功态覆盖六正式视口、移动列表、键盘、对比度与几何合同', async ({ browser }) => {
  test.slow()
  for (const viewport of viewports) {
    const { context, page, browserErrors } = await createPage(browser, viewport)
    try {
      await openRepair(page)
      await page.getByRole('button', { name: 'AI 分诊' }).click()
      const scrollIntoViewCall = await page.evaluate(() => {
        const record = window as typeof window & { __stage4ScrollIntoViewCalls?: Array<boolean | ScrollIntoViewOptions | undefined> }
        return record.__stage4ScrollIntoViewCalls?.at(-1)
      })
      expect(scrollIntoViewCall).toMatchObject({ behavior: 'auto', block: 'center' })
      await resetCaptureScroll(page, 'repair')
      await injectAiClient(page, { repair: repairResult() })
      await page.getByRole('button', { name: '生成分诊建议' }).click()
      await expect(page.locator('[data-testid="repair-confidence"]')).toHaveText('92%')
      const approvalButton = page.getByRole('button', { name: '查看审批提案' })
      await expect(approvalButton).toBeEnabled()

      const geometry = await expectNoHorizontalOverflow(page, `repair-success-${viewport.name}`)
      await expectPanelsWithinViewport(page, [
        '.repair-list-panel', '.repair-detail-panel', '.repair-ai-panel', '.repair-flow-panel', '.repair-safety-panel',
      ], `repair-success-${viewport.name}`)
      if (viewport.width <= 768) {
        await expect(page.locator('.repair-desktop-table')).toBeHidden()
        await expect(page.locator('.repair-mobile-list')).toBeVisible()
        const pageFontSize = Number.parseFloat((await elementStyle(page.locator('.repair-page'))).fontSize)
        expect(pageFontSize, `${viewport.name} 维修核心正文不得小于 14px`).toBeGreaterThanOrEqual(14)
        await expectMinimumTarget(page.locator('.repair-mobile-list button').first(), `${viewport.name} 移动工单`)
      } else {
        await expect(page.locator('.repair-desktop-table')).toBeVisible()
        await expect(page.locator('.repair-mobile-list')).toBeHidden()
      }
      const visibleCodes = await expectRepairFixtureDensity(page, viewport.width <= 768, viewport.name)
      const desktopColumnEvidence = viewport.width > 768
        ? await expectRepairDesktopColumnsContained(page, viewport.name)
        : undefined
      const dateVisibilityEvidence = viewport.name === '1586x992'
        ? await expectRepairDatesFullyVisible(page, '1586x992 维修日期')
        : undefined
      const firstViewportEvidence = viewport.name === '1586x992'
        ? await expectRepairFirstViewportComplete(page, '1586x992 维修首屏')
        : undefined
      const detailStackEvidence = viewport.width > 1440
        ? await expectRepairDetailStackUnclipped(page, `${viewport.name} 维修详情栈`)
        : undefined
      const approvalBounds = await expectMinimumTarget(approvalButton, `${viewport.name} 维修审批按钮`)
      const focus = await expectKeyboardFocus(approvalButton, `${viewport.name} 维修审批按钮`)
      const textContrast = await expectReadableText(
        page.locator('.repair-description'), page.locator('.repair-detail-panel'), `${viewport.name} 维修详情正文`,
      )
      const motion = await page.evaluate(() => ({
        reduced: matchMedia('(prefers-reduced-motion: reduce)').matches,
        scrollBehavior: getComputedStyle(document.documentElement).scrollBehavior,
      }))
      expect(motion.reduced).toBe(true)
      await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur())
      const captureScrollOrigin = await resetCaptureScroll(page, 'repair')
      computedEvidence[`repair-success-${viewport.name}`] = {
        geometry, approvalBounds, focus, textContrast, motion, visibleCodes,
        desktopColumnEvidence, dateVisibilityEvidence, firstViewportEvidence, detailStackEvidence,
        captureScrollOrigin, scrollIntoViewCall,
        workspace: await elementStyle(page.locator('.repair-workspace-grid')),
        aiPanel: await elementStyle(page.locator('.repair-ai-panel')),
      }
      await recordScreenshot(page, `repair-success-${viewport.name}.png`, {
        surface: 'repair', state: 'success', viewport: viewport.name,
      })
      await recordScreenshot(page, `repair-success-${viewport.name}-full.png`, {
        surface: 'repair', state: 'success', viewport: viewport.name,
      }, true)
      expect(browserErrors, `${viewport.name} 维修浏览器错误`).toEqual([])
    } finally {
      await context.close()
    }
  }
})

test('维修低置信、无候选、撤权、已完成、无来源、失败、超时与降级态均诚实呈现', async ({ browser }) => {
  test.slow()
  const generatedScenarios = [
    { scenario: 'low-confidence', state: 'WARNING', text: '置信度较低，请人工核验' },
    { scenario: 'no-candidate', state: 'WARNING', text: '暂无合适维修人员' },
    { scenario: 'no-source', state: 'NO_GROUNDED', text: '暂无可靠来源或数据时间，不能提交审批' },
    { scenario: 'failed', state: 'FAILED', text: '分诊运行失败，请重试或改用人工流程' },
    { scenario: 'timed-out', state: 'FAILED', text: '分诊运行失败，请重试或改用人工流程' },
    { scenario: 'degraded', state: 'DEGRADED', text: '分诊已降级，请人工核验后重新获取可靠来源' },
  ] as const

  for (const item of generatedScenarios) {
    const { context, page, browserErrors } = await createPage(browser, { width: 1366, height: 768 })
    try {
      await openRepair(page)
      await injectAiClient(page, { repair: repairResult(item.scenario) })
      await page.getByRole('button', { name: '生成分诊建议' }).click()
      const runInvalid = item.scenario === 'failed' || item.scenario === 'timed-out'
      const blocker = runInvalid
        ? page.locator('.repair-ai-run-invalid')
        : page.locator(`.repair-triage-blocker[data-state="${item.state}"]`)
      await expect(blocker).toContainText(item.text)
      await expect(page.getByRole('button', { name: '查看审批提案' })).toBeDisabled()
      await expect(page.locator('.repair-ai-panel').getByText(item.text, { exact: true })).toHaveCount(1)
      if (runInvalid) {
        await expect(page.locator('.repair-ai-run-invalid')).toContainText('本次分诊未生成可依赖建议')
        await expect(page.locator('.repair-triage-blocker[data-state="FAILED"]')).toHaveCount(0)
        await expect(page.locator('.repair-ai-metrics')).toHaveCount(0)
        await expect(page.getByRole('region', { name: '维修审批流程' })).toContainText('无有效建议')
      }
      expect(await aiCallCounts(page)).toEqual({ repair: 1, notice: 0 })
      await expectNoHorizontalOverflow(page, `repair-${item.scenario}`)
      await recordScreenshot(page, `repair-state-${item.scenario}.png`, {
        surface: 'repair', state: item.scenario, viewport: '1366x768',
      }, true)
      expect(browserErrors, `${item.scenario} 维修浏览器错误`).toEqual([])
    } finally {
      await context.close()
    }
  }

  {
    const { context, page, mockApiControl, browserErrors } = await createPage(browser, { width: 390, height: 844 })
    try {
      await setSessionPermissions(page, mockApiControl, ['repair:read'])
      await openRepair(page)
      await injectAiClient(page, { repair: repairResult() })
      const blocker = page.locator('.repair-triage-blocker').first()
      await expect(blocker).toContainText('当前账号无权使用维修 AI 分诊')
      await expect(page.getByRole('button', { name: '生成分诊建议' })).toHaveCount(0)
      expect(await aiCallCounts(page)).toEqual({ repair: 0, notice: 0 })
      await expectNoHorizontalOverflow(page, 'repair-no-permission')
      await recordScreenshot(page, 'repair-state-no-permission-mobile.png', {
        surface: 'repair', state: 'no-permission', viewport: '390x844',
      })
      await recordScreenshot(page, 'repair-state-no-permission-mobile-full.png', {
        surface: 'repair', state: 'no-permission', viewport: '390x844',
      }, true)
      expect(browserErrors).toEqual([])
    } finally {
      await context.close()
    }
  }

  {
    const { context, page, browserErrors } = await createPage(browser, { width: 1366, height: 768 })
    try {
      await openRepair(page)
      await page.getByRole('button', { name: `选择工单 ${completedRepairFixture.code}` }).click()
      await expect(page.getByRole('region', { name: '工单详情' })).toContainText('已完成')
      await seedRepairResult(page, repairResult('success', completedRepairFixture.id))
      const blocker = page.locator('.repair-triage-blocker[data-state="WARNING"]')
      await expect(blocker).toContainText('已完成工单不可再次分诊')
      await expect(page.getByRole('button', { name: '查看审批提案' })).toBeDisabled()
      await expectNoHorizontalOverflow(page, 'repair-completed')
      await recordScreenshot(page, 'repair-state-completed.png', {
        surface: 'repair', state: 'completed', viewport: '1366x768', storeSeededBecauseActionDisabled: true,
      }, true)
      expect(browserErrors).toEqual([])
    } finally {
      await context.close()
    }
  }
})

test('公告成功态覆盖六正式视口、键盘、对比度与几何合同', async ({ browser }) => {
  test.slow()
  for (const viewport of viewports) {
    const { context, page, browserErrors } = await createPage(browser, viewport)
    try {
      await openNotice(page)
      await injectAiClient(page, { notice: noticeResult() })
      await page.getByRole('textbox', { name: '公告要点' }).fill('本周五开展宿舍安全检查，请提前整理公共区域')
      await page.getByRole('button', { name: '生成 AI 草稿' }).click()
      await expect(page.getByRole('textbox', { name: '公告标题' })).toHaveValue('关于开展学生宿舍安全检查的通知')
      const approvalButton = page.getByRole('button', { name: '查看审批提案，提交审批' })
      await expect(approvalButton).toBeEnabled()

      const geometry = await expectNoHorizontalOverflow(page, `notice-success-${viewport.name}`)
      await expectPanelsWithinViewport(page, [
        '.notice-points-panel', '.notice-draft-panel', '.notice-check-panel', '.notice-diff-panel', '.notice-action-panel',
      ], `notice-success-${viewport.name}`)
      const pageFontSize = Number.parseFloat((await elementStyle(page.locator('.notice-create-page'))).fontSize)
      expect(pageFontSize, `${viewport.name} 公告核心正文不得小于 14px`).toBeGreaterThanOrEqual(14)
      const draftDensity = await expectNoticeDraftDensity(
        page,
        `${viewport.name} 公告草稿`,
        viewport.name === '1536x1024' || viewport.name === '1586x992',
        viewport.width <= 768,
      )
      const typography = await page.evaluate(() => {
        const heading = document.querySelector<HTMLElement>('.notice-draft-panel h2')
        const editor = document.querySelector<HTMLTextAreaElement>('textarea[aria-label="AI 草稿正文"]')
        if (!heading || !editor) throw new Error('公告排版合同缺少标题或正文编辑器')
        return {
          heading: Number.parseFloat(getComputedStyle(heading).fontSize),
          editor: Number.parseFloat(getComputedStyle(editor).fontSize),
        }
      })
      expect(typography.heading, `${viewport.name} 公告卡片标题视觉重量不足`).toBeGreaterThanOrEqual(18)
      expect(typography.editor, `${viewport.name} 公告主正文视觉重量不足`).toBeGreaterThanOrEqual(15)
      const approvalBounds = await expectMinimumTarget(approvalButton, `${viewport.name} 公告审批按钮`)
      const focus = await expectKeyboardFocus(approvalButton, `${viewport.name} 公告审批按钮`)
      const textContrast = await expectReadableText(
        page.getByRole('textbox', { name: 'AI 草稿正文' }), page.locator('.notice-draft-panel'), `${viewport.name} 公告草稿正文`,
      )
      const motion = await page.evaluate(() => ({
        reduced: matchMedia('(prefers-reduced-motion: reduce)').matches,
        scrollBehavior: getComputedStyle(document.documentElement).scrollBehavior,
      }))
      expect(motion.reduced).toBe(true)
      await page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur())
      const captureScrollOrigin = await resetCaptureScroll(page, 'notice')
      computedEvidence[`notice-success-${viewport.name}`] = {
        geometry, approvalBounds, focus, textContrast, motion, draftDensity, typography, captureScrollOrigin,
        workspace: await elementStyle(page.locator('.notice-workspace-grid')),
        actionPanel: await elementStyle(page.locator('.notice-action-panel')),
      }
      await recordScreenshot(page, `notice-success-${viewport.name}.png`, {
        surface: 'notice', state: 'success', viewport: viewport.name,
      })
      await recordScreenshot(page, `notice-success-${viewport.name}-full.png`, {
        surface: 'notice', state: 'success', viewport: viewport.name,
      }, true)
      expect(browserErrors, `${viewport.name} 公告浏览器错误`).toEqual([])
    } finally {
      await context.close()
    }
  }
})

test('公告 idle、loading、PII、来源、降级、失败、超时、stale、expired 与审批撤权态均诚实呈现', async ({ browser }) => {
  test.slow()
  {
    const { context, page, browserErrors } = await createPage(browser, { width: 1366, height: 768 })
    try {
      await openNotice(page)
      await injectAiClient(page, { notice: noticeResult() })
      await expect(page.getByRole('region', { name: 'AI 草稿' })).toContainText('等待生成或人工输入')
      await expect(page.getByRole('region', { name: '公告审批操作' })).toContainText('未提交')
      await recordScreenshot(page, 'notice-state-idle.png', {
        surface: 'notice', state: 'idle', viewport: '1366x768',
      }, true)
      expect(browserErrors).toEqual([])
    } finally {
      await context.close()
    }
  }

  {
    const { context, page, browserErrors } = await createPage(browser, { width: 1366, height: 768 })
    try {
      await openNotice(page)
      await injectAiClient(page, { notice: 'pending' })
      await page.getByRole('textbox', { name: '公告要点' }).fill('本周五开展宿舍安全检查')
      await page.getByRole('button', { name: '生成 AI 草稿' }).click()
      await expect(page.getByRole('button', { name: '正在生成…' })).toBeDisabled()
      expect(await aiCallCounts(page)).toEqual({ repair: 0, notice: 1 })
      await recordScreenshot(page, 'notice-state-loading.png', {
        surface: 'notice', state: 'loading', viewport: '1366x768',
      }, true)
      expect(browserErrors).toEqual([])
    } finally {
      await context.close()
    }
  }

  {
    const { context, page, browserErrors } = await createPage(browser, { width: 390, height: 844 })
    try {
      await openNotice(page)
      await injectAiClient(page, { notice: noticeResult() })
      await page.getByRole('textbox', { name: '公告要点' }).fill('请联系 dorm.manager@example.edu 确认安全检查安排')
      const safety = page.locator('[data-testid="notice-input-safety"]')
      await expect(safety).toContainText('检测到邮箱地址，已阻止进入 AI 起草请求')
      await expect(page.getByRole('button', { name: '生成 AI 草稿' })).toBeDisabled()
      expect(await aiCallCounts(page)).toEqual({ repair: 0, notice: 0 })
      await expectNoHorizontalOverflow(page, 'notice-pii-blocked')
      await recordScreenshot(page, 'notice-state-pii-blocked-mobile.png', {
        surface: 'notice', state: 'pii-blocked', viewport: '390x844', clientCalls: 0,
      })
      await recordScreenshot(page, 'notice-state-pii-blocked-mobile-full.png', {
        surface: 'notice', state: 'pii-blocked', viewport: '390x844', clientCalls: 0,
      }, true)
      expect(browserErrors).toEqual([])
    } finally {
      await context.close()
    }
  }

  const generatedScenarios = [
    { scenario: 'no-source', text: '暂无可靠来源或数据时间' },
    { scenario: 'degraded', text: '草稿结果已降级' },
    { scenario: 'failed', text: '草稿生成失败' },
    { scenario: 'timed-out', text: '生成超时' },
    { scenario: 'stale', text: '业务数据已变化' },
    { scenario: 'expired', text: '公告提案已过期' },
  ] as const
  for (const item of generatedScenarios) {
    const { context, page, browserErrors } = await createPage(browser, { width: 1366, height: 768 })
    try {
      await openNotice(page)
      await injectAiClient(page, { notice: noticeResult(item.scenario) })
      await page.getByRole('textbox', { name: '公告要点' }).fill(`Stage 4 ${item.scenario} 公告状态验证`)
      await page.getByRole('button', { name: '生成 AI 草稿' }).click()
      await expect(page.locator('[data-testid="notice-proposal-safety"]')).toContainText(item.text)
      await expect(page.getByRole('button', { name: '查看审批提案，提交审批' })).toBeDisabled()
      if (item.scenario === 'failed' || item.scenario === 'timed-out') {
        await expect(page.getByRole('region', { name: 'AI 草稿' })).toContainText(item.scenario === 'timed-out' ? '本次生成超时' : '本次生成失败')
        await expect(page.getByRole('region', { name: 'AI 草稿' })).not.toContainText('AI 草稿已生成，可继续人工编辑')
        await expect(page.getByRole('textbox', { name: '公告标题' })).toHaveValue('')
        await expect(page.getByRole('textbox', { name: 'AI 草稿正文' })).toHaveValue('')
        await expect(page.getByRole('region', { name: '内容检查' })).not.toContainText('91%')
        await expect(page.locator('.notice-approval-flow li').first()).toHaveClass(/is-invalid/)
        await expect(page.locator('.notice-approval-flow li').first()).not.toHaveClass(/complete/)
        await expect(page.getByRole('region', { name: '公告审批操作' })).toContainText(item.scenario === 'timed-out' ? '生成超时' : '生成失败')
      }
      expect(await aiCallCounts(page)).toEqual({ repair: 0, notice: 1 })
      await expectNoHorizontalOverflow(page, `notice-${item.scenario}`)
      await recordScreenshot(page, `notice-state-${item.scenario}.png`, {
        surface: 'notice', state: item.scenario, viewport: '1366x768',
      }, true)
      expect(browserErrors, `${item.scenario} 公告浏览器错误`).toEqual([])
    } finally {
      await context.close()
    }
  }

  {
    const { context, page, mockApiControl, browserErrors } = await createPage(browser, { width: 1366, height: 768 })
    try {
      await setSessionPermissions(page, mockApiControl, ['notice:read', 'notice:write', 'ai:notice:draft'])
      await openNotice(page)
      await injectAiClient(page, { notice: noticeResult() })
      await page.getByRole('textbox', { name: '公告要点' }).fill('本周五开展宿舍安全检查')
      await page.getByRole('button', { name: '生成 AI 草稿' }).click()
      await expect(page.locator('[data-testid="notice-approval-permission"]')).toContainText('当前账号无权提交审批')
      await expect(page.locator('[data-testid="notice-proposal-safety"]')).toContainText('当前账号无权提交审批')
      await expect(page.getByRole('button', { name: '查看审批提案，提交审批' })).toBeDisabled()
      await recordScreenshot(page, 'notice-state-no-approval-permission.png', {
        surface: 'notice', state: 'no-approval-permission', viewport: '1366x768',
      }, true)
      expect(browserErrors).toEqual([])
    } finally {
      await context.close()
    }
  }
})

test('公告最近已发布内容的 loading、empty 与 error 状态来自真实 HTTP fixture', async ({ browser }) => {
  test.slow()
  for (const scenario of ['loading', 'empty', 'error'] as const) {
    const { context, page, browserErrors } = await createPage(browser, { width: 390, height: 844 })
    try {
      await routeNoticeSnapshot(page, scenario)
      await openNotice(page, scenario !== 'loading')
      if (scenario === 'loading') {
        await expect(page.locator('.notice-diff-status')).toContainText('正在读取最近已发布公告')
        await expect(page.getByRole('button', { name: '读取当前公告…' })).toBeDisabled()
      } else if (scenario === 'empty') {
        await expect(page.locator('.notice-diff-status')).toContainText('暂无已发布公告')
        await expect(page.getByRole('region', { name: '变更预览' })).toContainText('最近已发布公告暂无正文')
      } else {
        await expect(page.locator('.notice-snapshot-alert')).toContainText('最近已发布公告加载失败')
        await expect(page.getByRole('region', { name: '变更预览' })).toContainText('当前内容暂不可用')
      }
      await expectNoHorizontalOverflow(page, `notice-snapshot-${scenario}`)
      await recordScreenshot(page, `notice-state-snapshot-${scenario}-mobile.png`, {
        surface: 'notice', state: `snapshot-${scenario}`, viewport: '390x844', httpFixture: true,
      })
      await recordScreenshot(page, `notice-state-snapshot-${scenario}-mobile-full.png`, {
        surface: 'notice', state: `snapshot-${scenario}`, viewport: '390x844', httpFixture: true,
      }, true)
      if (scenario === 'error') {
        expect(browserErrors.some((entry) => entry.includes('500')), '500 快照失败应保留浏览器网络错误证据').toBe(true)
      } else {
        expect(browserErrors).toEqual([])
      }
    } finally {
      await context.close()
    }
  }
})

test('维修与公告在 200% 等效视口自然重排、可滚动到审批区并尊重 reduced motion', async ({ browser }) => {
  for (const surface of ['repair', 'notice'] as const) {
    const { context, page, browserErrors } = await createPage(browser, { width: 960, height: 540 })
    try {
      if (surface === 'repair') {
        await openRepair(page)
        await injectAiClient(page, { repair: repairResult() })
        await page.getByRole('button', { name: '生成分诊建议' }).click()
        await page.getByRole('region', { name: '维修审批流程' }).scrollIntoViewIfNeeded()
        await expect(page.getByRole('region', { name: '维修审批流程' })).toBeVisible()
        const workspaceStyle = await elementStyle(page.locator('.repair-workspace-grid'))
        expect(workspaceStyle.width).toBeLessThanOrEqual(960)
      } else {
        await openNotice(page)
        await injectAiClient(page, { notice: noticeResult() })
        await page.getByRole('textbox', { name: '公告要点' }).fill('本周五开展宿舍安全检查')
        await page.getByRole('button', { name: '生成 AI 草稿' }).click()
        await page.getByRole('region', { name: '公告审批操作' }).scrollIntoViewIfNeeded()
        await expect(page.getByRole('region', { name: '公告审批操作' })).toBeVisible()
        const workspaceStyle = await elementStyle(page.locator('.notice-workspace-grid'))
        expect(workspaceStyle.width).toBeLessThanOrEqual(960)
      }
      const geometry = await expectNoHorizontalOverflow(page, `${surface}-200pct-1920x1080`)
      const motion = await page.evaluate(() => ({
        reduced: matchMedia('(prefers-reduced-motion: reduce)').matches,
        scrollY: window.scrollY,
        scrollHeight: Math.max(document.documentElement.scrollHeight, document.body.scrollHeight),
      }))
      expect(motion.reduced).toBe(true)
      expect(motion.scrollHeight, `${surface} 200% 应保留自然纵向滚动`).toBeGreaterThan(540)
      computedEvidence[`${surface}-200pct-1920x1080`] = { geometry, motion }
      await recordScreenshot(page, `${surface}-success-200pct-1920x1080.png`, {
        surface, state: 'success', viewport: '960x540', zoomEquivalent: '200%',
      })
      await page.evaluate(() => window.scrollTo(0, 0))
      await recordScreenshot(page, `${surface}-success-200pct-1920x1080-full.png`, {
        surface, state: 'success', viewport: '960x540', zoomEquivalent: '200%',
      }, true)
      expect(browserErrors).toEqual([])
    } finally {
      await context.close()
    }
  }
})
