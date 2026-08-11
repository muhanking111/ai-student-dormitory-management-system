import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { expect, test, type Page } from '@playwright/test'
import { captureFreshAssistantTurnEvidence } from '../src/utils/assistant-visual-evidence'
import { requireAiLiveIsolation } from './ai-live-isolation'
import {
  requireVisualCredentials,
  sanitizeDiagnostic,
  visualBackendEnv,
  visualBaseURL,
  visualDirectory,
} from './live-visual-settings'

const stageOutputName = (process.env.VISUAL_STAGE2_OUTPUT_DIR?.trim() || 'stage2')
  .replace(/[^A-Za-z0-9._-]/g, '_')
const stageDirectory = resolve(visualDirectory, stageOutputName)
void requireAiLiveIsolation(visualBackendEnv)

const viewports = [
  { name: '1586x992', width: 1586, height: 992, dashboardFile: 'stage2-dashboard-desktop.png', assistantFile: 'stage2-assistant-desktop.png' },
  { name: '390x844', width: 390, height: 844, dashboardFile: 'stage2-dashboard-mobile.png', assistantFile: 'stage2-assistant-mobile.png' },
] as const

type ViewportName = typeof viewports[number]['name']

interface KnowledgeSourceSnapshot {
  id: string
  aclVersion: number
  status: string
}

interface ScreenshotRecord {
  viewport: ViewportName
  kind: 'dashboard' | 'assistant'
  file: string
  width: number
  height: number
  bytes: number
}

interface Stage2Manifest {
  generatedAt: string
  screenshots: ScreenshotRecord[]
  knowledgeSourceId: string
  knowledgeMarker: string
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
  await expect(page.getByRole('heading', { name: '首页', exact: true })).toBeVisible()
}

async function ensureDashboardReady(page: Page) {
  await expect(page.getByRole('article', { name: '今日 AI 运营简报' })).toBeVisible({ timeout: 60_000 })
  await expect(page.locator('[data-dashboard-metric="dormitory"]')).toContainText('宿舍总数')
  await expect(page.locator('[data-dashboard-metric="students"]')).toContainText('入住人数')
  await expect(page.locator('[data-dashboard-metric="vacant"]')).toContainText('空余床位')
  await expect(page.locator('[data-dashboard-metric="repairs"]')).toContainText('待维修')
  await expect(page.locator('.risk-row')).toHaveCount(4)
  await expect(page.locator('[aria-label="待处理事项"]')).toContainText('人工审批')
}

async function createGroundedKnowledge(page: Page, knowledgeMarker: string) {
  await page.goto('/ai/knowledge')
  await expect(page.getByRole('heading', { name: '知识管理', exact: true })).toBeVisible()

  const create = page.getByRole('region', { name: '创建知识来源' })
  await create.getByRole('textbox', { name: '来源名称' }).fill(`阶段2视觉知识源 ${knowledgeMarker}`)
  await create.getByRole('textbox', { name: '知识来源权限' }).fill('ai:knowledge:read')
  const sourceResponsePromise = page.waitForResponse((response) => {
    const url = new URL(response.url())
    return url.pathname === '/api/ai/knowledge/sources' && response.request().method() === 'POST'
  })
  await create.getByRole('button', { name: '创建来源' }).click()
  const sourceResponse = await sourceResponsePromise
  expect(sourceResponse.status()).toBe(201)
  const sourcePayload = await sourceResponse.json() as { data?: KnowledgeSourceSnapshot }
  const sourceId = sourcePayload.data?.id ?? ''
  expect(sourceId).toMatch(/^[0-9a-f-]{36}$/i)

  const ingest = page.locator('.ai-knowledge-ingest')
  await ingest.getByRole('textbox', { name: '文档标题' }).fill(`维修时限 ${knowledgeMarker}`)
  await ingest.getByRole('textbox', { name: '外部键' }).fill(`stage2-${knowledgeMarker.toLowerCase()}`)
  await ingest.getByRole('textbox', { name: '版本' }).fill('v1')
  await ingest.getByRole('textbox', { name: '纯文本正文' }).fill(
    `知识标识 ${knowledgeMarker}：宿舍普通维修应在受理后 2 个工作日内完成，特殊情况最长不超过 5 个工作日。`,
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

  return { sourceId }
}

async function openAssistantWithGroundedAnswer(
  page: Page,
  knowledgeMarker: string,
  captureScreenshot: () => Promise<void>,
) {
  // The production drawer is global even when the Dashboard remains visible
  // underneath; this verifies the route-context mapping and the RAG path.
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '首页', exact: true })).toBeVisible()
  await page.getByRole('button', { name: /智能助手/ }).first().click()
  const assistant = page.getByRole('dialog', { name: '智能助手' })
  await expect(assistant).toBeVisible()
  await expect(assistant).toBeFocused()

  const groundedQuestion = `请检索知识标识 ${knowledgeMarker}`
  const userMessages = assistant.locator('[data-assistant-user-message]')
  const answers = assistant.locator('[data-assistant-answer]')
  await captureFreshAssistantTurnEvidence({
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
    },
    readMessageCounts: async () => ({
      user: await userMessages.count(),
      assistant: await answers.count(),
    }),
    sendQuestion: async () => {
      await assistant.getByRole('textbox', { name: '向智能助手提问' }).fill(groundedQuestion)
      await assistant.getByRole('button', { name: '发送' }).click()
    },
    waitForMessageCounts: async (expectedCounts) => {
      await expect(userMessages).toHaveCount(expectedCounts.user)
      await expect(answers).toHaveCount(expectedCounts.assistant, { timeout: 60_000 })
    },
    assertLatestTurn: async () => {
      const userMessage = userMessages.last()
      const answer = answers.last()
      await expect(userMessage).toContainText(groundedQuestion)
      await expect(answer).toContainText(knowledgeMarker, { timeout: 60_000 })
      await expect(answer).toContainText(/引用来源 \d+/)
      await expect(answer).toContainText(/工作日|维修/)
      await expect(answer.getByRole('button', { name: /引用来源/ })).toBeVisible()
    },
    scrollLatestAnswer: async () => { await answers.last().scrollIntoViewIfNeeded() },
    captureScreenshot,
  })
  return assistant
}

test('阶段 2 Dashboard 与 AI 助手真实内容态桌面/移动截图', async ({ browser }) => {
  mkdirSync(stageDirectory, { recursive: true })
  const knowledgeMarker = `STAGE2-${Date.now().toString(36).toUpperCase()}`
  const screenshotRecords: ScreenshotRecord[] = []
  let firstSourceId = ''

  for (const viewport of viewports) {
    const context = await browser.newContext({
      baseURL: visualBaseURL,
      viewport: { width: viewport.width, height: viewport.height },
      deviceScaleFactor: 1,
      reducedMotion: 'reduce',
    })
    const page = await context.newPage()
    try {
      await loginThroughUi(page)
      if (!firstSourceId) {
        const source = await createGroundedKnowledge(page, knowledgeMarker)
        firstSourceId = source.sourceId
      }

      await page.goto('/')
      await ensureDashboardReady(page)
      const dashboardFile = resolve(stageDirectory, viewport.dashboardFile)
      await page.screenshot({ path: dashboardFile, animations: 'disabled', caret: 'hide' })
      const dashboardStats = statSync(dashboardFile)
      const dashboardDims = pngDimensions(dashboardFile)
      screenshotRecords.push({
        viewport: viewport.name,
        kind: 'dashboard',
        file: viewport.dashboardFile,
        width: dashboardDims.width,
        height: dashboardDims.height,
        bytes: dashboardStats.size,
      })

      const assistantFile = resolve(stageDirectory, viewport.assistantFile)
      const assistant = await openAssistantWithGroundedAnswer(
        page,
        knowledgeMarker,
        async () => { await page.screenshot({ path: assistantFile, animations: 'disabled', caret: 'hide' }) },
      )
      const assistantStats = statSync(assistantFile)
      const assistantDims = pngDimensions(assistantFile)
      screenshotRecords.push({
        viewport: viewport.name,
        kind: 'assistant',
        file: viewport.assistantFile,
        width: assistantDims.width,
        height: assistantDims.height,
        bytes: assistantStats.size,
      })
      await assistant.getByRole('button', { name: /关闭智能助手|关闭/ }).first().click()
      await expect(assistant).toBeHidden()

      const overflow = await page.evaluate(() => ({
        viewportWidth: document.documentElement.clientWidth,
        scrollWidth: Math.max(document.documentElement.scrollWidth, document.body?.scrollWidth ?? 0),
      }))
      expect(overflow.scrollWidth, `${viewport.name} 不应出现横向滚动`).toBeLessThanOrEqual(overflow.viewportWidth + 1)
    } finally {
      await page.close()
      await context.close()
    }
  }

  const manifest: Stage2Manifest = {
    generatedAt: new Date().toISOString(),
    screenshots: screenshotRecords,
    knowledgeSourceId: firstSourceId,
    knowledgeMarker,
  }
  writeFileSync(resolve(stageDirectory, 'manifest.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')

  expect(firstSourceId, '阶段 2 知识来源未创建成功').not.toBe('')
  expect(screenshotRecords).toHaveLength(4)
  for (const record of screenshotRecords) {
    expect(record.bytes, `${record.file} 文件过小`).toBeGreaterThan(1_024)
    expect(record.width).toBe(record.viewport === '1586x992' ? 1586 : 390)
    expect(record.height).toBe(record.viewport === '1586x992' ? 992 : 844)
    const path = resolve(stageDirectory, record.file)
    expect(existsSync(path), `${record.file} 未生成`).toBe(true)
    expect(statSync(path).mtimeMs).toBeGreaterThan(0)
  }
  expect(existsSync(resolve(stageDirectory, 'manifest.json'))).toBe(true)
})
