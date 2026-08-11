import { randomUUID } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type APIResponse, type BrowserContext, type Page } from '@playwright/test'
import { requireAiLiveIsolation } from './ai-live-isolation'
import {
  requireVisualCredentials,
  sanitizeDiagnostic,
  visualBackendEnv,
  visualBaseURL,
  visualDirectory,
} from './live-visual-settings'

const stageDirectory = resolve(visualDirectory, 'stage3')
void requireAiLiveIsolation(visualBackendEnv)

interface ScreenshotRecord {
  surface: 'repair' | 'notice'
  file: string
  width: number
  height: number
  bytes: number
}

interface Stage3Manifest {
  generatedAt: string
  screenshots: ScreenshotRecord[]
  repairOrderCode: string
  repairType: string
  noticeTitle: string
  noticeProposalState: string
  repairCitationCount: number
  repairConfidence: string
  repairProposalLoaded: boolean
  noticeProposalLoaded: boolean
  repairRowHeight: number
  repairDocumentHeight: number
  repairViewportHeight: number
  repairScreenshotScrollY: number
}

interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

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
  const loginResponse = await loginResponsePromise
  if (loginResponse.status() !== 200) {
    const detail = sanitizeDiagnostic(await loginResponse.text().catch(() => 'unreadable response'))
    throw new Error(`登录失败：HTTP ${loginResponse.status()} ${detail}`)
  }
  await expect(page).toHaveURL(/\/$/)
}

async function apiData<T>(response: APIResponse, expectedStatus: number, label: string) {
  const raw = await response.text()
  let payload: ApiEnvelope<T>
  try {
    payload = JSON.parse(raw) as ApiEnvelope<T>
  } catch {
    throw new Error(`${label} 返回非 JSON：HTTP ${response.status()} ${sanitizeDiagnostic(raw)}`)
  }
  expect(response.status(), `${label}：${sanitizeDiagnostic(payload.message)}`).toBe(expectedStatus)
  expect(payload.code, `${label}：${sanitizeDiagnostic(payload.message)}`).toBe(0)
  return payload.data
}

async function prepareRepairScenario(context: BrowserContext) {
  const csrf = await apiData<{ token: string }>(
    await context.request.get('/api/security/csrf'),
    200,
    '获取 CSRF token',
  )
  const headers = {
    'X-CSRF-Token': csrf.token,
    Origin: visualBaseURL,
    Referer: `${visualBaseURL}/repairs`,
  }
  const roles = await apiData<Array<{ id: number; code: string }>>(
    await context.request.get('/api/roles/options'),
    200,
    '读取角色选项',
  )
  const repairerRole = roles.find((role) => role.code === 'REPAIRER')
  expect(repairerRole, '专用 E2E 库缺少 REPAIRER 内置角色').toBeDefined()

  const marker = `${Date.now().toString(36)}${randomUUID().slice(0, 4)}`
  await apiData(
    await context.request.post('/api/users', {
      headers,
      data: {
        username: `stage3_rep_${marker}`.slice(0, 32),
        displayName: '阶段三维修人员',
        password: `S3!${randomUUID()}aA1`,
        enabled: true,
        roleIds: [repairerRole!.id],
      },
    }),
    201,
    '创建维修人员',
  )

  return apiData<{ id: number; code: string }>(
    await context.request.post('/api/repair-orders', {
      headers,
      data: {
        reporter: '阶段三验收同学',
        location: '宿舍楼 B-2-验收房间',
        type: '水电维修',
        description: '插座冒烟并伴随火花，已切断相关电源，夜间用电负载较高。',
        assigneeUserId: null,
      },
    }),
    201,
    '创建维修工单',
  )
}

test('阶段 3 维修分诊与公告起草真实内容态桌面截图', async ({ browser }) => {
  mkdirSync(stageDirectory, { recursive: true })
  const screenshotRecords: ScreenshotRecord[] = []
  let repairOrderCode: string
  let repairType: string
  let noticeTitle: string
  let noticeProposalState: string
  let repairCitationCount: number
  let repairConfidence: string
  let repairProposalLoaded: boolean
  let noticeProposalLoaded: boolean
  let repairRowHeight: number
  let repairDocumentHeight: number
  let repairViewportHeight: number
  let repairScreenshotScrollY: number

  const repairContext = await browser.newContext({
    baseURL: visualBaseURL,
    viewport: { width: 1586, height: 992 },
    deviceScaleFactor: 1,
    reducedMotion: 'reduce',
  })
  const repairPage = await repairContext.newPage()
  try {
    await loginThroughUi(repairPage)

    const repairScenario = await prepareRepairScenario(repairContext)
    repairOrderCode = repairScenario.code

    await repairPage.goto('/repairs')
    await expect(repairPage.getByRole('heading', { name: '维修智能分诊', exact: true })).toBeVisible()
    const repairRow = repairPage.getByRole('row').filter({ hasText: repairOrderCode })
    await expect(repairRow).toBeVisible()
    repairRowHeight = (await repairRow.boundingBox())?.height ?? 0
    const repairRowGeometry = await repairRow.evaluate((row) => ({
      height: row.getBoundingClientRect().height,
      cells: Array.from(row.querySelectorAll('td')).map((cell) => {
        const style = getComputedStyle(cell)
        const box = cell.getBoundingClientRect()
        return {
          text: cell.textContent?.trim().slice(0, 40),
          width: box.width,
          height: box.height,
          whiteSpace: style.whiteSpace,
          overflow: style.overflow,
          textOverflow: style.textOverflow,
          lineHeight: style.lineHeight,
          paddingBlock: `${style.paddingTop}/${style.paddingBottom}`,
        }
      }),
      buttons: Array.from(row.querySelectorAll('button')).map((button) => ({
        text: button.textContent?.trim(),
        width: button.getBoundingClientRect().width,
        height: button.getBoundingClientRect().height,
      })),
    }))
    const repairRowDiagnostic = JSON.stringify(repairRowGeometry)
    expect(repairRowHeight, `长工单号不应撑高桌面表格行：${repairRowDiagnostic}`).toBeGreaterThan(0)
    expect(repairRowHeight, `桌面数据行应保持与原型一致的紧凑密度：${repairRowDiagnostic}`).toBeLessThanOrEqual(52)
    await repairRow.getByRole('button', { name: /AI 分诊/ }).click()
    const triage = repairPage.getByRole('region', { name: /维修智能分诊|AI 分诊/ })
    await expect(triage).toBeVisible()
    await triage.getByRole('button', { name: /生成.*建议|开始.*分诊/ }).click()
    await expect(triage).toContainText(/建议维修员|变更预览|人工审批/, { timeout: 60_000 })
    await expect(triage).toContainText('引用来源 2')
    await expect(triage.getByTestId('repair-confidence')).toHaveText('规则确定')
    const triageSummary = await triage.locator('.repair-section-title span').textContent() ?? ''
    repairCitationCount = Number(triageSummary.match(/引用来源\s+(\d+)/)?.[1] ?? 0)
    repairConfidence = (await triage.getByTestId('repair-confidence').textContent())?.trim() ?? ''
    repairType = await triage.locator('.repair-ai-metrics strong').first().textContent() ?? '水电维修'
    const repairProposalButton = repairPage.getByRole('region', { name: '维修审批流程' })
      .getByRole('button', { name: '查看审批提案' })
    await expect(repairProposalButton).toBeEnabled()
    await expect(repairProposalButton).toBeVisible()
    const repairProposalBox = await repairProposalButton.boundingBox()
    const repairWorkspaceBox = await repairPage.locator('.repair-workspace-grid').boundingBox()
    expect(repairProposalBox, '维修审批入口必须可测量').not.toBeNull()
    expect(repairWorkspaceBox, '维修工作区必须可测量').not.toBeNull()
    expect(
      repairProposalBox!.y + repairProposalBox!.height,
      `维修审批入口不应被工作区裁切：button=${JSON.stringify(repairProposalBox)}, workspace=${JSON.stringify(repairWorkspaceBox)}`,
    ).toBeLessThanOrEqual(repairWorkspaceBox!.y + repairWorkspaceBox!.height + 1)
    const repairFlowBox = await repairPage.getByRole('region', { name: '维修审批流程' }).boundingBox()
    expect(repairFlowBox, '维修审批流程区域必须可测量').not.toBeNull()
    expect(
      repairFlowBox!.y + repairFlowBox!.height,
      `维修审批流程区域不应被工作区裁切：${JSON.stringify(repairFlowBox)}`,
    ).toBeLessThanOrEqual(992)
    await repairPage.evaluate(() => window.scrollTo(0, 0))
    await expect.poll(() => repairPage.evaluate(() => window.scrollY)).toBe(0)
    const repairViewportGeometry = await repairPage.evaluate(() => ({
      documentHeight: document.documentElement.scrollHeight,
      viewportHeight: window.innerHeight,
      scrollY: window.scrollY,
    }))
    repairDocumentHeight = repairViewportGeometry.documentHeight
    repairViewportHeight = repairViewportGeometry.viewportHeight
    repairScreenshotScrollY = repairViewportGeometry.scrollY
    expect(
      repairDocumentHeight,
      `维修桌面应在原型视口内完整呈现：document=${repairDocumentHeight}, viewport=${repairViewportHeight}`,
    ).toBeLessThanOrEqual(repairViewportHeight)
    const repairFile = resolve(stageDirectory, 'stage3-repair-triage-desktop.png')
    await repairPage.screenshot({ path: repairFile, animations: 'disabled', caret: 'hide' })
    const repairStats = statSync(repairFile)
    const repairDims = pngDimensions(repairFile)
    screenshotRecords.push({
      surface: 'repair',
      file: 'stage3-repair-triage-desktop.png',
      width: repairDims.width,
      height: repairDims.height,
      bytes: repairStats.size,
    })
    const proposalResponsePromise = repairPage.waitForResponse((response) => (
      /^\/api\/ai\/proposals\/[^/]+$/.test(new URL(response.url()).pathname)
      && response.request().method() === 'GET'
    ))
    await repairProposalButton.click()
    expect((await proposalResponsePromise).status()).toBe(200)
    await expect(repairPage).toHaveURL(/\/ai\/approvals$/)
    repairProposalLoaded = true
  } finally {
    await repairPage.close()
    await repairContext.close()
  }

  const noticeContext = await browser.newContext({
    baseURL: visualBaseURL,
    viewport: { width: 1536, height: 1024 },
    deviceScaleFactor: 1,
    reducedMotion: 'reduce',
  })
  const noticePage = await noticeContext.newPage()
  try {
    await loginThroughUi(noticePage)

    await noticePage.goto('/notices/create')
    await expect(noticePage.getByRole('region', { name: '公告 AI 起草工作台' })).toBeVisible()
    const draftingRegion = noticePage.getByRole('region', { name: '公告 AI 起草工作台' })
    await draftingRegion.getByRole('textbox', { name: '公告要点' }).fill('开展宿舍安全检查，排查违规电器、私拉电线等安全隐患，请同学配合。')
    await draftingRegion.getByRole('button', { name: '生成 AI 草稿' }).click()
    await expect(noticePage.getByRole('region', { name: 'AI 草稿' })).toContainText('AI 草稿已生成', { timeout: 60_000 })
    await expect(noticePage.getByRole('textbox', { name: '公告标题' })).not.toHaveValue('')
    await expect(noticePage.getByRole('textbox', { name: 'AI 草稿正文' })).not.toHaveValue('')
    await expect(noticePage.getByRole('region', { name: '内容检查' })).toContainText(/通过|置信度|引用来源/, { timeout: 60_000 })
    noticeTitle = (await noticePage.getByRole('textbox', { name: '公告标题' }).inputValue()).trim()
    noticeProposalState = (await noticePage.getByText(/待审批|未提交/).first().textContent())?.trim() ?? '待审批'
    const noticeFile = resolve(stageDirectory, 'stage3-notice-drafting-desktop.png')
    await noticePage.screenshot({ path: noticeFile, animations: 'disabled', caret: 'hide' })
    const noticeStats = statSync(noticeFile)
    const noticeDims = pngDimensions(noticeFile)
    screenshotRecords.push({
      surface: 'notice',
      file: 'stage3-notice-drafting-desktop.png',
      width: noticeDims.width,
      height: noticeDims.height,
      bytes: noticeStats.size,
    })
    const noticeProposalButton = noticePage.getByRole('button', { name: '查看审批提案，提交审批' })
    await expect(noticeProposalButton).toBeEnabled()
    const proposalResponsePromise = noticePage.waitForResponse((response) => (
      /^\/api\/ai\/proposals\/[^/]+$/.test(new URL(response.url()).pathname)
      && response.request().method() === 'GET'
    ))
    await noticeProposalButton.click()
    expect((await proposalResponsePromise).status()).toBe(200)
    await expect(noticePage).toHaveURL(/\/ai\/approvals$/)
    noticeProposalLoaded = true
  } finally {
    await noticePage.close()
    await noticeContext.close()
  }

  const manifest: Stage3Manifest = {
    generatedAt: new Date().toISOString(),
    screenshots: screenshotRecords,
    repairOrderCode,
    repairType,
    noticeTitle,
    noticeProposalState,
    repairCitationCount,
    repairConfidence,
    repairProposalLoaded,
    noticeProposalLoaded,
    repairRowHeight,
    repairDocumentHeight,
    repairViewportHeight,
    repairScreenshotScrollY,
  }
  writeFileSync(resolve(stageDirectory, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')

  expect(screenshotRecords).toHaveLength(2)
  expect(repairCitationCount).toBe(2)
  expect(repairConfidence).toBe('规则确定')
  expect(repairProposalLoaded).toBe(true)
  expect(noticeProposalLoaded).toBe(true)
  for (const record of screenshotRecords) {
    expect(record.bytes, `${record.file} 文件过小`).toBeGreaterThan(1_024)
    expect(existsSync(resolve(stageDirectory, record.file)), `${record.file} 未生成`).toBe(true)
  }
})
