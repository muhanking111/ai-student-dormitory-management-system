import { writeFileSync } from 'node:fs'
import type { Page, Request } from '@playwright/test'
import { expect, test } from '@playwright/test'
import {
  requireVisualCredentials,
  sanitizeDiagnostic,
} from './live-visual-settings'

interface ObservedRequest {
  method: string
  path: string
}

interface KnowledgeSourceSnapshot {
  id: string
  aclVersion: number
  status: string
}

interface ConversationCitationSnapshot {
  id: string
  label: string
  locator: string
  version: string
  access: 'available' | 'denied' | 'retired'
}

interface ConversationMessageSnapshot {
  id: string
  role: 'USER' | 'ASSISTANT'
  text: string
  runId?: string | null
  runState?: string | null
  grounded?: boolean | null
  citations: ConversationCitationSnapshot[]
}

interface ConversationDetailSnapshot {
  id: string
  messages: ConversationMessageSnapshot[]
}

interface ConversationListSnapshot {
  records: Array<{ id: string }>
}

interface ApiEnvelope<T> {
  data?: T
}

const unsafeMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

function observeLiveAiBoundary(page: Page) {
  const aiRequests: ObservedRequest[] = []
  const businessWrites: ObservedRequest[] = []
  const apiResponses: Array<ObservedRequest & { status: number }> = []
  const failedApiResponses: Array<ObservedRequest & { status: number }> = []
  const requestFailures: Array<ObservedRequest & { errorText: string }> = []

  const requestListener = (request: Request) => {
    const url = new URL(request.url())
    const observed = { method: request.method(), path: url.pathname }
    if (url.pathname.startsWith('/api/ai/')) aiRequests.push(observed)
    if (
      url.pathname.startsWith('/api/')
      && unsafeMethods.has(request.method())
      && !url.pathname.startsWith('/api/ai/')
      && !url.pathname.startsWith('/api/auth/')
      && !url.pathname.startsWith('/api/security/')
    ) businessWrites.push(observed)
  }
  page.on('request', requestListener)
  page.on('response', (response) => {
    const url = new URL(response.url())
    if (url.pathname.startsWith('/api/')) {
      const observed = { method: response.request().method(), path: url.pathname, status: response.status() }
      apiResponses.push(observed)
      if (response.status() >= 400) failedApiResponses.push(observed)
    }
  })
  page.on('requestfailed', (request) => {
    const url = new URL(request.url())
    if (url.pathname.startsWith('/api/')) {
      requestFailures.push({
        method: request.method(),
        path: url.pathname,
        errorText: sanitizeDiagnostic(request.failure()?.errorText ?? 'unknown'),
      })
    }
  })
  return { aiRequests, businessWrites, apiResponses, failedApiResponses, requestFailures }
}

async function loginThroughUi(page: Page) {
  const credentials = requireVisualCredentials()
  await page.goto('/login')
  await page.getByRole('textbox', { name: '用户名' }).fill(credentials.username)
  await page.getByLabel('密码').fill(credentials.password)
  const loginResponsePromise = page.waitForResponse((response) => new URL(response.url()).pathname === '/api/auth/login'
    && response.request().method() === 'POST')
  const initialDashboardResponsePromise = page.waitForResponse(
    (response) => new URL(response.url()).pathname === '/api/ai/dashboard/queries'
      && response.request().method() === 'POST',
    { timeout: 30_000 },
  ).catch(() => undefined)
  await page.getByRole('button', { name: '登录' }).click()
  const loginResponse = await loginResponsePromise
  if (loginResponse.status() !== 200) {
    const detail = sanitizeDiagnostic(await loginResponse.text().catch(() => 'unreadable response'))
    throw new Error(`登录失败：HTTP ${loginResponse.status()} ${detail}`)
  }
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('heading', { name: '首页', exact: true })).toBeVisible()
  const dashboardResponse = await initialDashboardResponsePromise
  if (!dashboardResponse) {
    throw new Error('登录成功后 30 秒内未观察到初始 AI Dashboard command')
  }
  const payload = await dashboardResponse.json() as { data?: { runId?: string } }
  expect(
    dashboardResponse.status(),
    `初始 AI Dashboard command 失败：${sanitizeDiagnostic(JSON.stringify(payload))}`,
  ).toBe(202)
  expect(payload.data?.runId).toMatch(/^[0-9a-f-]{36}$/i)
  return payload.data!.runId!
}

test('真实 HttpAiClient 串联 Dashboard、助手 SSE/历史/反馈/撤权、公告提案与知识摄取，且不绕过审批写业务', async ({ page }, testInfo) => {
  const observed = observeLiveAiBoundary(page)
  const initialDashboardRunId = await loginThroughUi(page)
  const knowledgeSuffix = Date.now().toString(36)
  const knowledgeMarker = `LIVE${knowledgeSuffix.toUpperCase()}`
  const sourceName = `AI live E2E ${knowledgeSuffix}`
  let knowledgeSourceId = ''
  let knowledgeSourceAclVersion = 0
  let groundedConversationId = ''
  let groundedMessageId = ''
  let originalAnswerText = ''
  let originalCitationLabel = ''
  let originalCitationLocator = ''
  let originalCitationVersion = ''

  await test.step('Dashboard 通过真实 command + SSE 返回确定性指标', async () => {
    await expect.poll(async () => {
      const response = await page.request.get(`/api/ai/runs/${initialDashboardRunId}`)
      const payload = await response.json() as { data?: { state?: string } }
      return payload.data?.state
    }, { timeout: 30_000 }).toMatch(/FAILED|SUCCEEDED/)
    const command = page.getByRole('search', { name: '自然语言查询' })
    await command.getByRole('textbox', { name: '自然语言查询' }).fill('本周待维修工单有多少？')
    await command.getByRole('button', { name: /查询/ }).click()
    const brief = page.getByRole('article', { name: '今日 AI 运营简报' })
    await expect(brief).toBeVisible()
    await expect(brief).toContainText('口径')
    await expect(brief).toContainText('数据截至')
  })

  await test.step('全局助手在没有可靠来源时通过真实 SSE 确定性拒答', async () => {
    await page.getByRole('button', { name: /智能助手/ }).first().click()
    const assistant = page.getByRole('dialog', { name: '智能助手' })
    await expect(assistant).toBeVisible()
    await expect(assistant).toBeFocused()
    await assistant.getByRole('button', { name: '新建会话' }).click()
    await assistant.getByRole('textbox', { name: '向智能助手提问' })
      .fill(`请查找不存在的知识标识 NOSOURCE-${knowledgeSuffix}`)
    await assistant.getByRole('button', { name: '发送' }).click()
    const answer = assistant.getByRole('main', { name: '智能助手会话内容' })
      .getByRole('article').last()
    await expect(answer).toContainText(/暂无可靠来源[，,]无法确认/, { timeout: 60_000 })
    await expect(answer).toContainText('引用来源 0')
    await expect(answer).toHaveAttribute('data-assistant-content-state', 'no-grounded')
    await expect(assistant.getByText('已完成')).toBeVisible()
    await assistant.getByRole('button', { name: '关闭智能助手' }).click()
  })

  await test.step('公告 AI 只创建提案，不保存或发布公告', async () => {
    await page.goto('/notices/create')
    const region = page.getByRole('region', { name: '公告 AI 起草工作台' })
    await expect(region).toBeVisible()
    await region.getByRole('textbox', { name: '公告要点' }).fill('周五检查公共区域安全用电，请提前整理。')
    await region.getByRole('button', { name: '生成 AI 草稿' }).click()
    await expect(page.getByRole('region', { name: 'AI 草稿' })).toBeVisible({ timeout: 60_000 })
    await expect(region.getByRole('region', { name: '变更预览' })).toBeVisible()
    await region.getByRole('button', { name: '查看审批提案' }).click()
    await expect(page).toHaveURL(/\/ai\/approvals/)
    await expect(page.getByRole('heading', { name: '待审批', exact: true })).toBeVisible()
    const proposal = page.getByRole('main', { name: '提案详情' })
    await expect(proposal).toContainText('待审批')
    await expect(proposal.getByRole('region', { name: '工具调用预览' })).toBeVisible()
    await expect(proposal.getByRole('button', { name: '批准执行' })).toBeVisible()
  })

  await test.step('知识管理走真实隔离上传、finalize、版本和异步摄取链', async () => {
    await page.goto('/ai/knowledge')
    await expect(page.getByRole('heading', { name: '知识管理', exact: true })).toBeVisible()
    const create = page.getByRole('region', { name: '创建知识来源' })
    await create.getByRole('textbox', { name: '来源名称' }).fill(sourceName)
    await create.getByRole('textbox', { name: '知识来源权限' }).fill('ai:knowledge:read')
    const sourceResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === '/api/ai/knowledge/sources' && response.request().method() === 'POST'
    })
    await create.getByRole('button', { name: '创建来源' }).click()
    const sourceResponse = await sourceResponsePromise
    const sourcePayload = await sourceResponse.json() as ApiEnvelope<KnowledgeSourceSnapshot>
    expect(sourceResponse.status()).toBe(201)
    expect(sourcePayload.data?.id).toMatch(/^[0-9a-f-]{36}$/i)
    expect(sourcePayload.data?.aclVersion).toBe(1)
    knowledgeSourceId = sourcePayload.data?.id ?? ''
    knowledgeSourceAclVersion = sourcePayload.data?.aclVersion ?? 0
    await expect(page.getByRole('heading', { name: sourceName })).toBeVisible()

    const ingest = page.locator('.ai-knowledge-ingest')
    await ingest.getByRole('textbox', { name: '文档标题' }).fill(`维修时限 ${knowledgeSuffix}`)
    await ingest.getByRole('textbox', { name: '外部键' }).fill(`ai-live-${knowledgeSuffix}`)
    await ingest.getByRole('textbox', { name: '版本' }).fill('v1')
    await ingest.getByRole('textbox', { name: '纯文本正文' })
      .fill(`知识标识 ${knowledgeMarker}：宿舍普通维修应在受理后及时分派，所有处理必须由人工确认。`)
    await ingest.getByRole('button', { name: '创建版本并摄取' }).click()
    const version = page.getByRole('region', { name: '知识版本与摄取任务' })
    await expect(version).toBeVisible({ timeout: 60_000 })
    await expect.poll(async () => {
      await version.getByRole('button', { name: '刷新任务' }).click()
      return version.textContent()
    }, { timeout: 60_000 }).toMatch(/SUCCEEDED[\s\S]*READY|READY[\s\S]*SUCCEEDED/)
    await version.getByRole('button', { name: '激活版本' }).click()
    await expect(version).toContainText('ACTIVE')
  })

  await test.step('摄取完成后全局助手只返回当前授权知识并展示引用', async () => {
    await page.getByRole('button', { name: /智能助手/ }).first().click()
    const assistant = page.getByRole('dialog', { name: '智能助手' })
    await expect(assistant).toBeVisible()
    await assistant.getByRole('textbox', { name: '向智能助手提问' })
      .fill(`请检索知识标识 ${knowledgeMarker}`)
    const conversationResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === '/api/ai/conversations' && response.request().method() === 'POST'
    })
    const messageResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return /\/api\/ai\/conversations\/[^/]+\/messages$/.test(url.pathname)
        && response.request().method() === 'POST'
    })
    await assistant.getByRole('button', { name: '发送' }).click()
    const conversationResponse = await conversationResponsePromise
    const conversationPayload = await conversationResponse.json() as ApiEnvelope<{ id: string }>
    expect(conversationResponse.status()).toBe(201)
    expect(conversationPayload.data?.id).toMatch(/^[0-9a-f-]{36}$/i)
    groundedConversationId = conversationPayload.data?.id ?? ''
    const messageResponse = await messageResponsePromise
    expect(messageResponse.status()).toBe(202)
    expect(new URL(messageResponse.url()).pathname)
      .toBe(`/api/ai/conversations/${groundedConversationId}/messages`)
    const answer = assistant.getByRole('main', { name: '智能助手会话内容' })
      .getByRole('article').last()
    await expect(answer).toContainText('引用来源 1', { timeout: 60_000 })
    await expect(answer).toContainText(knowledgeMarker)
    await expect(answer).toContainText(sourceName)
    await expect(answer).not.toContainText(/暂无可靠来源[，,]无法确认/)
    await expect(assistant.getByText('已完成')).toBeVisible()
    await assistant.getByRole('button', { name: '关闭智能助手' }).click()
  })

  await test.step('重载后通过真实会话详情恢复 messageId、runState 和授权引用', async () => {
    await page.reload()
    await expect(page.getByRole('heading', { name: '知识管理', exact: true })).toBeVisible()
    const detailResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === `/api/ai/conversations/${groundedConversationId}`
        && response.request().method() === 'GET'
    })
    await page.getByRole('button', { name: /智能助手/ }).first().click()
    const detailResponse = await detailResponsePromise
    const detailPayload = await detailResponse.json() as ApiEnvelope<ConversationDetailSnapshot>
    expect(detailResponse.status()).toBe(200)
    expect(detailPayload.data?.id).toBe(groundedConversationId)
    const groundedMessage = [...(detailPayload.data?.messages ?? [])].reverse().find((message) => (
      message.role === 'ASSISTANT' && message.citations.some((citation) => citation.access === 'available')
    ))
    expect(groundedMessage).toBeTruthy()
    if (!groundedMessage) throw new Error('会话详情未恢复 grounded assistant message')
    expect(groundedMessage.id).toMatch(/^[0-9a-f-]{36}$/i)
    expect(groundedMessage.runId).toMatch(/^[0-9a-f-]{36}$/i)
    expect(groundedMessage.runState).toMatch(/^(SUCCEEDED|DEGRADED)$/)
    expect(groundedMessage.grounded).toBe(true)
    const citation = groundedMessage.citations.find((item) => item.access === 'available')
    expect(citation).toBeTruthy()
    if (!citation) throw new Error('会话详情未恢复当前授权 citation')
    expect(citation.id).toMatch(/^[0-9a-f-]{36}$/i)
    expect(citation.label).not.toBe('')
    expect(citation.locator).not.toBe('')

    groundedMessageId = groundedMessage.id
    originalAnswerText = groundedMessage.text
    originalCitationLabel = citation.label
    originalCitationLocator = citation.locator
    originalCitationVersion = citation.version

    const assistant = page.getByRole('dialog', { name: '智能助手' })
    await expect(assistant).toBeFocused()
    const restoredAnswer = assistant.locator('[data-assistant-answer]').last()
    await expect(restoredAnswer).toContainText(knowledgeMarker)
    await expect(restoredAnswer).toContainText(/(已完成|降级完成)/)
    await expect(restoredAnswer.getByRole('button', { name: /赞同此回答|有帮助/ })).toBeEnabled()
  })

  await test.step('通过真实 UI 提交有帮助与无帮助反馈', async () => {
    const assistant = page.getByRole('dialog', { name: '智能助手' })
    const answer = assistant.locator('[data-assistant-answer]').last()
    const feedbackPath = `/api/ai/messages/${groundedMessageId}/feedback`
    const helpfulResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === feedbackPath && response.request().method() === 'POST'
    })
    await answer.getByRole('button', { name: /赞同此回答|有帮助/ }).click()
    const helpfulResponse = await helpfulResponsePromise
    expect(helpfulResponse.status()).toBe(204)
    expect(helpfulResponse.request().postDataJSON()).toEqual(expect.objectContaining({
      rating: 1,
      tags: ['helpful'],
    }))
    await expect(answer.locator('.feedback-status')).toContainText('反馈已记录')

    await answer.getByRole('button', { name: /反馈此回答|无帮助/ }).click()
    await answer.getByRole('textbox', { name: '反馈说明' }).fill('引用不足，请人工复核')
    const unhelpfulResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === feedbackPath && response.request().method() === 'POST'
    })
    await answer.getByRole('button', { name: '提交反馈' }).click()
    const unhelpfulResponse = await unhelpfulResponsePromise
    expect(unhelpfulResponse.status()).toBe(204)
    expect(unhelpfulResponse.request().postDataJSON()).toEqual(expect.objectContaining({
      rating: -1,
      tags: ['needs-review'],
      comment: '引用不足，请人工复核',
    }))
    await expect(answer.locator('.feedback-status')).toContainText('反馈已记录')
    await assistant.getByRole('button', { name: '关闭智能助手' }).click()
  })

  await test.step('暂停知识来源后，历史会话固定撤权正文与 denied 引用均不泄露原内容', async () => {
    const sourceList = page.getByRole('region', { name: '知识来源列表' })
    await sourceList.getByRole('button').filter({ hasText: sourceName }).first().click()
    await expect(page.getByRole('heading', { name: sourceName })).toBeVisible()
    const governance = page.locator('form.ai-knowledge-ingest').filter({ hasText: '来源与 ACL 治理' })
    await governance.getByText('PAUSED', { exact: true }).click()
    const pauseResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === `/api/ai/knowledge/sources/${knowledgeSourceId}`
        && response.request().method() === 'PATCH'
    })
    await governance.getByRole('button', { name: '保存来源治理' }).click()
    const pauseResponse = await pauseResponsePromise
    const pausePayload = await pauseResponse.json() as ApiEnvelope<KnowledgeSourceSnapshot>
    expect(pauseResponse.status()).toBe(200)
    expect(pausePayload.data?.status).toBe('PAUSED')
    expect(pausePayload.data?.aclVersion).toBeGreaterThan(knowledgeSourceAclVersion)
    await expect(page.getByRole('region', { name: '知识来源详情' })).toContainText('PAUSED')

    await page.getByRole('button', { name: /智能助手/ }).first().click()
    const assistant = page.getByRole('dialog', { name: '智能助手' })
    await expect(assistant).toBeVisible()
    await assistant.getByRole('button', { name: '新建会话' }).click()
    const listResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === '/api/ai/conversations' && response.request().method() === 'GET'
    })
    await assistant.getByRole('button', { name: '查看会话历史' }).click()
    const listResponse = await listResponsePromise
    const listPayload = await listResponse.json() as ApiEnvelope<ConversationListSnapshot>
    expect(listResponse.status()).toBe(200)
    const conversationIndex = listPayload.data?.records.findIndex(({ id }) => id === groundedConversationId) ?? -1
    expect(conversationIndex, '本轮 grounded conversation 必须存在于真实历史列表').toBeGreaterThanOrEqual(0)
    const historyItem = assistant.locator('button[aria-label^="切换到会话："]').nth(conversationIndex)
    await expect(historyItem).toBeVisible()
    const revokedDetailResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url())
      return url.pathname === `/api/ai/conversations/${groundedConversationId}`
        && response.request().method() === 'GET'
    })
    await historyItem.click()
    const revokedDetailResponse = await revokedDetailResponsePromise
    const revokedPayload = await revokedDetailResponse.json() as ApiEnvelope<ConversationDetailSnapshot>
    expect(revokedDetailResponse.status()).toBe(200)
    const revokedMessage = revokedPayload.data?.messages.find((message) => message.id === groundedMessageId)
    expect(revokedMessage).toBeTruthy()
    if (!revokedMessage) throw new Error('撤权后会话详情缺少原 assistant message')
    expect(revokedMessage.text).toBe('历史回答的授权来源已不可访问，请重新提问或联系管理员。')
    expect(revokedMessage.text).not.toBe(originalAnswerText)
    expect(revokedMessage.text).not.toContain(knowledgeMarker)
    expect(revokedMessage.runState).toMatch(/^(SUCCEEDED|DEGRADED)$/)
    expect(revokedMessage.grounded).toBe(false)
    expect(revokedMessage.citations.length).toBeGreaterThan(0)
    expect(revokedMessage.citations.every((citation) => citation.access === 'denied')).toBe(true)
    for (const citation of revokedMessage.citations) {
      expect(citation.label).not.toBe(originalCitationLabel)
      expect(citation.locator).not.toBe(originalCitationLocator)
      expect(citation.version).not.toBe(originalCitationVersion || 'v1')
      expect(JSON.stringify(citation)).not.toContain(sourceName)
      expect(JSON.stringify(citation)).not.toContain(knowledgeMarker)
    }

    const revokedAnswer = assistant.locator('[data-assistant-answer]').last()
    await expect(revokedAnswer).toContainText('历史回答的授权来源已不可访问，请重新提问或联系管理员。')
    await expect(revokedAnswer).toContainText('引用来源 0')
    await expect(revokedAnswer).toContainText('无权限查看此来源')
    await expect(revokedAnswer.locator('[data-assistant-state="citation-denied"]')).toHaveCount(1)
    await expect(revokedAnswer).not.toContainText(knowledgeMarker)
    await expect(revokedAnswer).not.toContainText(sourceName)
    await expect(revokedAnswer).not.toContainText(originalCitationLabel)
    await expect(revokedAnswer).not.toContainText(originalCitationLocator)
    if (originalCitationVersion) await expect(revokedAnswer).not.toContainText(originalCitationVersion)
  })

  const aiPaths = observed.aiRequests.map(({ path }) => path)
  expect(aiPaths).toContain('/api/ai/dashboard/queries')
  expect(aiPaths).toContain('/api/ai/conversations')
  expect(aiPaths.some((path) => /\/api\/ai\/conversations\/[^/]+\/messages$/.test(path))).toBe(true)
  expect(aiPaths.some((path) => /\/api\/ai\/runs\/[^/]+\/events$/.test(path))).toBe(true)
  expect(observed.aiRequests.some(({ method, path }) => method === 'GET'
    && path === `/api/ai/conversations/${groundedConversationId}`)).toBe(true)
  expect(observed.aiRequests.filter(({ method, path }) => method === 'POST'
    && path === `/api/ai/messages/${groundedMessageId}/feedback`)).toHaveLength(2)
  expect(observed.aiRequests.some(({ method, path }) => method === 'PATCH'
    && path === `/api/ai/knowledge/sources/${knowledgeSourceId}`)).toBe(true)
  expect(aiPaths).toContain('/api/ai/notices/drafts')
  expect(aiPaths).toContain('/api/ai/knowledge/sources')
  expect(aiPaths.some((path) => /\/api\/ai\/knowledge\/uploads\/[^/]+\/content$/.test(path))).toBe(true)
  expect(aiPaths.some((path) => /\/api\/ai\/knowledge\/sources\/[^/]+\/versions$/.test(path))).toBe(true)
  expect(observed.businessWrites, 'AI 预览和提案阶段不得调用原业务写接口').toEqual([])
  expect(observed.failedApiResponses, '真实 AI 链不得出现 HTTP 4xx/5xx').toEqual([])
  const unexpectedRequestFailures = observed.requestFailures.filter((failure) => {
    if (!failure.errorText.includes('ERR_ABORTED')) return true
    return !observed.apiResponses.some((response) => response.method === failure.method
      && response.path === failure.path && response.status >= 200 && response.status < 400)
  })
  expect(unexpectedRequestFailures, '除已收到成功响应后的连接关闭外，真实 AI 链不得出现网络失败').toEqual([])

  const networkEvidencePath = testInfo.outputPath('network-evidence.json')
  writeFileSync(networkEvidencePath, JSON.stringify({
    aiRequests: observed.aiRequests,
    businessWrites: observed.businessWrites,
    aiResponses: observed.apiResponses.filter(({ path }) => path.startsWith('/api/ai/')),
    toleratedCompletedConnectionClosures: observed.requestFailures.filter((item) => !unexpectedRequestFailures.includes(item)),
  }, null, 2), 'utf8')
  await page.screenshot({ path: testInfo.outputPath('ai-live-contract-success.png'), fullPage: true })
})
