import { createHash, randomUUID } from 'node:crypto'
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import {
  expect,
  test,
  type Page,
  type Request,
} from '@playwright/test'
import { requireAiLiveIsolation } from './ai-live-isolation'
import {
  requireVisualCredentials,
  sanitizeDiagnostic,
  visualBackendEnv,
  visualBackendURL,
  visualBaseURL,
  visualDirectory,
  visualOutputName,
} from './live-visual-settings'

const evidenceDirectory = resolve(visualDirectory, 'stage4-repair-notice')
const manifestPath = resolve(evidenceDirectory, 'manifest.json')
const repairNoticeRedisKeyPrefix = process.env.AI_LIVE_REPAIR_NOTICE_STAGE4_REDIS_KEY_PREFIX?.trim()
  || `ai-live:repair-notice:stage4:${visualOutputName.replaceAll('.', '-')}`
const aiLiveIsolation = requireAiLiveIsolation({
  ...visualBackendEnv,
  AI_REDIS_KEY_PREFIX: repairNoticeRedisKeyPrefix,
}, { requireOutputBoundRedisPrefix: true })
const unsafeMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

interface PageResponse<T> {
  records: T[]
  total: number
  page: number
  pageSize: number
}

interface UserSession {
  id: number
  roleCodes: string[]
  permissions: string[]
}

interface RoleOption {
  code: string
}

interface UserAccount {
  id: number
  displayName: string
  enabled: boolean
  roles: RoleOption[]
}

interface RepairOrder {
  id: number
  code: string
  status: '待处理' | '处理中' | '已完成'
  description?: string
  assigneeUserId?: number
}

interface Notice {
  id: number
}

interface BackendRuntimeReadiness {
  masterEnabled: boolean
  providerAlias: string
  streamingEnabled: boolean
  writeExecutionEnabled: boolean
  controls: Record<string, string>
}

interface RunAcceptedResponse {
  runId: string
  eventsUrl: string
}

interface RunSnapshot {
  id: string
  capability: string
  state: string
  failureCode?: string | null
}

interface ProposalCitation {
  id: string
  label: string
  locator: string
  version: string
  access: string
}

interface ProposalSnapshot {
  id: string
  runId?: string | null
  actionType: 'REPAIR_ASSIGN' | 'NOTICE_CREATE_DRAFT'
  proposedValue: string
  requiredPermission: string
  payloadHash: string
  businessSnapshotHash: string
  version: number
  expiresAt: string
  riskLevel: string
  evidence: {
    basis: string
    asOf: string
    citations: ProposalCitation[]
    grounded: boolean
  }
  state: string
  auditAvailable: boolean
  executionState?: string | null
  executionId?: string | null
}

interface ObservedRequest {
  method: string
  path: string
}

interface ObservedResponse extends ObservedRequest {
  status: number
}

interface RequestFailure extends ObservedRequest {
  errorText: string
}

interface RequestedRuntimeContract {
  suite: string
  clientMode: string
  provider: string
  streamingEnabled: boolean
  aiWriteExecutionEnabled: boolean
  demoDataEnabled: boolean
  database: string
  databaseHost: string
  databasePort: string
  redisHost: string
  redisPort: string
  redisDatabase: string
  redisKeyPrefix: string
  frontendOrigin: string
  frontendPort: string
  backendOrigin: string
  backendPort: string
}

interface SourceBinding {
  file: string
  bytes: number
  sha256: string
}

interface ReadableResponse {
  status(): number
  text(): Promise<string>
}

class BlockedInputError extends Error {
  readonly blockerCode: string

  constructor(blockerCode: string, message: string) {
    super(message)
    this.name = 'BlockedInputError'
    this.blockerCode = blockerCode
  }
}

function fileBinding(file: string, path: string): SourceBinding {
  const bytes = readFileSync(path)
  return {
    file,
    bytes: bytes.length,
    sha256: createHash('sha256').update(bytes).digest('hex').toUpperCase(),
  }
}

function evidenceBindings() {
  const frontend = process.cwd()
  const backend = resolve(frontend, '../backend/src/main/java/com/example/dormitory')
  return [
    fileBinding('frontend/src/api/ai-client.ts', resolve(frontend, 'src/api/ai-client.ts')),
    fileBinding('frontend/src/api/ai-http.ts', resolve(frontend, 'src/api/ai-http.ts')),
    fileBinding('frontend/src/views/RepairManagementView.vue', resolve(frontend, 'src/views/RepairManagementView.vue')),
    fileBinding('frontend/src/views/NoticeManagementView.vue', resolve(frontend, 'src/views/NoticeManagementView.vue')),
    fileBinding('backend/src/main/java/com/example/dormitory/ai/api/AiFeatureCommandController.java', resolve(backend, 'ai/api/AiFeatureCommandController.java')),
    fileBinding('backend/src/main/java/com/example/dormitory/ai/api/AiProposalController.java', resolve(backend, 'ai/api/AiProposalController.java')),
    fileBinding('backend/src/main/java/com/example/dormitory/ai/repair/RepairTriageService.java', resolve(backend, 'ai/repair/RepairTriageService.java')),
    fileBinding('backend/src/main/java/com/example/dormitory/ai/notice/NoticeDraftService.java', resolve(backend, 'ai/notice/NoticeDraftService.java')),
    fileBinding('backend/src/main/java/com/example/dormitory/security/CsrfOriginInterceptor.java', resolve(backend, 'security/CsrfOriginInterceptor.java')),
    fileBinding('backend/src/main/java/com/example/dormitory/ai/infrastructure/business/ApprovedDormitoryBusinessActionAdapter.java', resolve(backend, 'ai/infrastructure/business/ApprovedDormitoryBusinessActionAdapter.java')),
    fileBinding('backend/src/main/java/com/example/dormitory/ai/infrastructure/runtime/RestrictedFakeModelRuntimeAdapter.java', resolve(backend, 'ai/infrastructure/runtime/RestrictedFakeModelRuntimeAdapter.java')),
    fileBinding('frontend/e2e/ai-live-global-setup.ts', resolve(frontend, 'e2e/ai-live-global-setup.ts')),
    fileBinding('frontend/e2e/ai-live-isolation.ts', resolve(frontend, 'e2e/ai-live-isolation.ts')),
    fileBinding('frontend/e2e/live-visual-settings.ts', resolve(frontend, 'e2e/live-visual-settings.ts')),
    fileBinding('frontend/e2e/ai-live-repair-notice-stage4.spec.ts', resolve(frontend, 'e2e/ai-live-repair-notice-stage4.spec.ts')),
    fileBinding('frontend/playwright.ai-live-repair-notice-stage4.config.ts', resolve(frontend, 'playwright.ai-live-repair-notice-stage4.config.ts')),
  ]
}

async function apiData<T>(response: ReadableResponse, expectedStatus: number, label: string) {
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

async function readAllPages<T>(page: Page, path: string, query: Record<string, string> = {}) {
  const records: T[] = []
  let expectedTotal = 0
  for (let pageNumber = 1; pageNumber <= 100; pageNumber += 1) {
    const params = new URLSearchParams({ page: String(pageNumber), pageSize: '100', ...query })
    const result = await apiData<PageResponse<T>>(
      await page.request.get(`${path}?${params.toString()}`),
      200,
      `读取 ${path} 第 ${pageNumber} 页`,
    )
    expectedTotal = result.total
    records.push(...result.records)
    if (records.length >= result.total) return { records, total: result.total }
    if (result.records.length === 0) break
  }
  throw new Error(`读取 ${path} 分页不完整：${records.length}/${expectedTotal}`)
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
  const initialDashboardResponsePromise = page.waitForResponse((response) => (
    new URL(response.url()).pathname === '/api/ai/dashboard/queries'
    && response.request().method() === 'POST'
  ), { timeout: 60_000 })
  await page.getByRole('button', { name: '登录' }).click()
  const loginResponse = await loginResponsePromise
  if (loginResponse.status() !== 200) {
    const detail = sanitizeDiagnostic(await loginResponse.text().catch(() => 'unreadable response'))
    throw new Error(`登录失败：HTTP ${loginResponse.status()} ${detail}`)
  }
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('heading', { name: '首页', exact: true })).toBeVisible()
  await acceptedRun(await initialDashboardResponsePromise, '登录后的 Dashboard command')
  await expect(page.locator('[data-dashboard-section="brief"]'))
    .toHaveAttribute('aria-busy', 'false', { timeout: 60_000 })
  await page.waitForLoadState('networkidle')
  return apiData<UserSession>(await page.request.get('/api/auth/me'), 200, '读取当前会话')
}

async function observeBackendRuntime(page: Page, label: string) {
  const readiness = await apiData<BackendRuntimeReadiness>(
    await page.request.get('/api/ai/operations/readiness'),
    200,
    `${label} 读取 AI readiness`,
  )
  expect(readiness).toMatchObject({
    masterEnabled: true,
    providerAlias: 'fake',
    streamingEnabled: true,
    writeExecutionEnabled: false,
  })
  return {
    label,
    observedAt: new Date().toISOString(),
    source: 'GET /api/ai/operations/readiness',
    masterEnabled: readiness.masterEnabled,
    provider: readiness.providerAlias,
    streamingEnabled: readiness.streamingEnabled,
    aiWriteExecutionEnabled: readiness.writeExecutionEnabled,
    controls: readiness.controls,
  }
}

function observeNetwork(page: Page) {
  const aiRequests: ObservedRequest[] = []
  const businessWrites: ObservedRequest[] = []
  const apiResponses: ObservedResponse[] = []
  const failedApiResponses: ObservedResponse[] = []
  const requestFailures: RequestFailure[] = []
  const pendingAuditRequests = new Set<Request>()

  page.on('request', (request: Request) => {
    const path = new URL(request.url()).pathname
    const observed = { method: request.method(), path }
    if (request.method() === 'GET' && /^\/api\/ai\/audit\/runs\/[^/]+$/.test(path)) {
      pendingAuditRequests.add(request)
    }
    if (path.startsWith('/api/ai/')) aiRequests.push(observed)
    if (path.startsWith('/api/')
      && unsafeMethods.has(request.method())
      && !path.startsWith('/api/ai/')
      && !path.startsWith('/api/auth/')
      && !path.startsWith('/api/security/')) {
      businessWrites.push(observed)
    }
  })
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname
    if (!path.startsWith('/api/')) return
    const observed = { method: response.request().method(), path, status: response.status() }
    apiResponses.push(observed)
    if (response.status() >= 400) failedApiResponses.push(observed)
  })
  page.on('requestfailed', (request) => {
    pendingAuditRequests.delete(request)
    const path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return
    requestFailures.push({
      method: request.method(),
      path,
      errorText: sanitizeDiagnostic(request.failure()?.errorText ?? 'unknown'),
    })
  })
  page.on('requestfinished', (request) => pendingAuditRequests.delete(request))

  return { aiRequests, businessWrites, apiResponses, failedApiResponses, requestFailures, pendingAuditRequests }
}

async function acceptedRun(response: ReadableResponse, label: string) {
  const accepted = await apiData<RunAcceptedResponse>(response, 202, label)
  expect(accepted.runId).toMatch(/^[0-9a-f-]{36}$/i)
  expect(accepted.eventsUrl).toBe(`/api/ai/runs/${accepted.runId}/events`)
  return accepted
}

async function completedRun(page: Page, runId: string, capability: string) {
  await expect.poll(async () => {
    const response = await page.request.get(`/api/ai/runs/${runId}`)
    if (response.status() !== 200) return `HTTP_${response.status()}`
    const payload = JSON.parse(await response.text()) as ApiEnvelope<RunSnapshot>
    return payload.data?.state ?? 'MISSING_STATE'
  }, { timeout: 30_000, message: `${capability} run 未进入 SUCCEEDED` }).toBe('SUCCEEDED')
  const run = await apiData<RunSnapshot>(
    await page.request.get(`/api/ai/runs/${runId}`),
    200,
    `读取 ${capability} run`,
  )
  expect(run.id).toBe(runId)
  expect(run.capability).toBe(capability)
  expect(run.failureCode ?? null).toBeNull()
  return run
}

function assertProposal(proposal: ProposalSnapshot, actionType: ProposalSnapshot['actionType'], runId: string) {
  expect(proposal.id).toMatch(/^[0-9a-f-]{36}$/i)
  expect(proposal.runId).toBe(runId)
  expect(proposal.actionType).toBe(actionType)
  expect(proposal.state).toBe('pending_approval')
  expect(proposal.payloadHash).toMatch(/^[0-9a-f]{64}$/i)
  expect(proposal.businessSnapshotHash).toMatch(/^[0-9a-f]{64}$/i)
  expect(proposal.version).toBeGreaterThanOrEqual(0)
  expect(Date.parse(proposal.expiresAt)).toBeGreaterThan(Date.now())
  expect(proposal.auditAvailable).toBe(true)
  expect(proposal.executionState ?? null).toBeNull()
  expect(proposal.executionId ?? null).toBeNull()
  expect(proposal.evidence).toMatchObject({ basis: 'deterministic', grounded: true })
  expect(proposal.evidence.asOf).not.toBe('')
  expect(proposal.evidence.citations.length).toBeGreaterThan(0)
  for (const citation of proposal.evidence.citations) {
    expect(citation.id).toMatch(/^[0-9a-f]{64}$/i)
    expect(citation.access).toBe('available')
    expect(citation.label).not.toBe('')
    expect(citation.locator).not.toBe('')
    expect(citation.version).not.toBe('')
  }
}

function proposalManifest(proposal: ProposalSnapshot | null) {
  if (!proposal) return null
  return {
    id: proposal.id,
    runId: proposal.runId,
    actionType: proposal.actionType,
    state: proposal.state,
    payloadHash: proposal.payloadHash,
    businessSnapshotHash: proposal.businessSnapshotHash,
    version: proposal.version,
    expiresAt: proposal.expiresAt,
    riskLevel: proposal.riskLevel,
    citationCount: proposal.evidence.citations.length,
    citationIds: proposal.evidence.citations.map((citation) => citation.id),
    grounded: proposal.evidence.grounded,
    auditAvailable: proposal.auditAvailable,
    executionState: proposal.executionState ?? null,
    executionId: proposal.executionId ?? null,
  }
}

function repairManifest(order: RepairOrder | null) {
  if (!order) return null
  return {
    id: order.id,
    code: order.code,
    status: order.status,
    assigneeUserId: order.assigneeUserId ?? null,
  }
}

function compactRepairCode(value: string) {
  return value.length <= 18 ? value : `${value.slice(0, 10)}…${value.slice(-7)}`
}

test('Stage 4 真实 HttpAiClient 覆盖维修分诊与公告起草，且不绕过安全和业务写边界', async ({ browser, page }, testInfo) => {
  mkdirSync(evidenceDirectory, { recursive: true })
  const startedAt = new Date().toISOString()
  const observed = observeNetwork(page)
  const runtimeObservations: Array<Awaited<ReturnType<typeof observeBackendRuntime>>> = []
  const bindings = evidenceBindings()
  let status: 'completed' | 'blocked' | 'failed' = 'failed'
  let blocker: { code: string; message: string } | null = null
  let failureMessage: string | null = null
  let pendingError: unknown
  let session: UserSession | null = null
  let repairBefore: RepairOrder | null = null
  let repairAfter: RepairOrder | null = null
  let enabledRepairerCount = 0
  let unfinishedRepairCount = 0
  let noticeIdsBefore: number[] = []
  let noticeIdsAfter: number[] = []
  let csrfDenialStatus: number | null = null
  let piiBlockedWithoutRequest = false
  let repairAccepted: RunAcceptedResponse | null = null
  let noticeAccepted: RunAcceptedResponse | null = null
  let repairRun: RunSnapshot | null = null
  let noticeRun: RunSnapshot | null = null
  let repairProposal: ProposalSnapshot | null = null
  let noticeProposal: ProposalSnapshot | null = null
  let anonymousProposalReadStatus: number | null = null

  const requestedRuntimeContract = testInfo.config.metadata.aiLiveRequestedRuntimeContract as RequestedRuntimeContract | undefined

  try {
    expect(requestedRuntimeContract).toMatchObject({
      suite: 'repair-notice-stage4',
      clientMode: 'HttpAiClient',
      provider: 'fake',
      streamingEnabled: true,
      aiWriteExecutionEnabled: false,
      demoDataEnabled: false,
      database: aiLiveIsolation.connection.database,
      databaseHost: aiLiveIsolation.connection.hostname,
    })
    if (!requestedRuntimeContract) throw new Error('Playwright metadata 缺少 aiLiveRequestedRuntimeContract')
    expect(requestedRuntimeContract.database).toMatch(/_e2e$/i)
    expect(requestedRuntimeContract.frontendOrigin).toBe(visualBaseURL)
    expect(requestedRuntimeContract.backendOrigin).toBe(visualBackendURL)
    expect(requestedRuntimeContract.redisHost).toMatch(/^(?:127\.0\.0\.1|localhost|::1)$/i)
    expect(requestedRuntimeContract.redisPort).toMatch(/^\d+$/)
    expect(requestedRuntimeContract.redisDatabase).toBe('11')
    expect(requestedRuntimeContract.redisKeyPrefix)
      .toBe(aiLiveIsolation.redis.keyPrefix)
    for (const binding of bindings) {
      expect(binding.bytes, `${binding.file} 为空`).toBeGreaterThan(0)
      expect(binding.sha256, `${binding.file} SHA-256 不合法`).toMatch(/^[0-9A-F]{64}$/)
    }

    session = await loginThroughUi(page)
    runtimeObservations.push(await observeBackendRuntime(page, 'before-workflows'))
    const requiredPermissions = [
      'repair:read',
      'repair:write',
      'system:user:read',
      'notice:read',
      'notice:write',
      'ai:repair:triage',
      'ai:notice:draft',
      'ai:approval:review',
    ]
    const hasWildcard = session.permissions.includes('*')
    const missingPermissions = requiredPermissions.filter((permission) => (
      !hasWildcard && !session!.permissions.includes(permission)
    ))
    if (!session.roleCodes.includes('ADMIN') || missingPermissions.length > 0) {
      throw new BlockedInputError(
        'MISSING_REQUIRED_RBAC',
        `真实链路账号缺少 ADMIN 或权限：${missingPermissions.join(', ') || 'ADMIN'}`,
      )
    }
    const repairs = await readAllPages<RepairOrder>(page, '/api/repair-orders')
    const repairers = await readAllPages<UserAccount>(page, '/api/users', { enabled: 'true' })
    const notices = await readAllPages<Notice>(page, '/api/notices')
    const eligibleRepairers = repairers.records.filter((user) => (
      user.enabled && user.roles.some((role) => role.code === 'REPAIRER')
    ))
    const unfinishedRepairs = repairs.records.filter((repair) => repair.status !== '已完成')
    const safeRepairs = unfinishedRepairs.filter((repair) => (
      !/(忽略|绕过|执行命令|系统提示|system prompt)/i.test(repair.description ?? '')
    ))
    enabledRepairerCount = eligibleRepairers.length
    unfinishedRepairCount = unfinishedRepairs.length
    noticeIdsBefore = notices.records.map((notice) => notice.id).sort((left, right) => left - right)

    if (unfinishedRepairs.length === 0) {
      runtimeObservations.push(await observeBackendRuntime(page, 'blocked-no-unfinished-repair'))
      throw new BlockedInputError('NO_UNFINISHED_REPAIR_ORDER', '专用 *_e2e 库缺少现存未完成维修单')
    }
    if (safeRepairs.length === 0) {
      runtimeObservations.push(await observeBackendRuntime(page, 'blocked-no-safe-repair'))
      throw new BlockedInputError('NO_ELIGIBLE_REPAIR_ORDER', '现存未完成维修单均触发提示注入预筛，不能作为合法输入')
    }
    if (eligibleRepairers.length === 0) {
      runtimeObservations.push(await observeBackendRuntime(page, 'blocked-no-repairer'))
      throw new BlockedInputError('NO_ENABLED_REPAIRER', '专用 *_e2e 库缺少已启用 REPAIRER')
    }

    repairBefore = safeRepairs
      .slice()
      .sort((left, right) => Number(Boolean(left.assigneeUserId)) - Number(Boolean(right.assigneeUserId)) || left.id - right.id)[0]!

    const csrfResponse = await page.request.post(`/api/ai/repairs/${repairBefore.id}/triage`, {
      headers: {
        Origin: visualBaseURL,
        Referer: `${visualBaseURL}/repairs`,
        'Idempotency-Key': `csrf-negative-${randomUUID()}`,
      },
      data: {},
    })
    csrfDenialStatus = csrfResponse.status()
    expect(csrfDenialStatus, 'Cookie 鉴权的维修分诊缺少 CSRF token 必须被拒绝').toBe(403)

    await page.goto('/repairs')
    await expect(page.getByRole('heading', { name: '维修智能分诊', exact: true })).toBeVisible()
    const searchInput = page.getByPlaceholder('搜索单号、报修人或位置')
    await searchInput.fill(repairBefore.code)
    const repairSearchResponsePromise = page.waitForResponse((response) => (
      new URL(response.url()).pathname === '/api/repair-orders'
      && response.request().method() === 'GET'
    ))
    await searchInput.press('Enter')
    expect((await repairSearchResponsePromise).status()).toBe(200)
    const repairRow = page.getByRole('row').filter({ hasText: compactRepairCode(repairBefore.code) })
    await expect(repairRow).toBeVisible()
    await repairRow.getByRole('button', { name: `选择工单 ${repairBefore.code}` }).click()
    await expect(page.getByRole('region', { name: '工单详情' })).toContainText(repairBefore.code)
    await page.getByRole('region', { name: '工单详情' }).getByRole('button', { name: 'AI 分诊' }).click()
    const triageRegion = page.getByRole('region', { name: '维修智能分诊' })
    await expect(triageRegion.getByRole('button', { name: '生成分诊建议' })).toBeEnabled()
    const repairAcceptedResponsePromise = page.waitForResponse((response) => (
      new URL(response.url()).pathname === `/api/ai/repairs/${repairBefore!.id}/triage`
      && response.request().method() === 'POST'
    ))
    await triageRegion.getByRole('button', { name: '生成分诊建议' }).click()
    repairAccepted = await acceptedRun(await repairAcceptedResponsePromise, '维修 AI 分诊 command')
    await expect(triageRegion).toContainText(/建议维修员|变更预览|人工审批/, { timeout: 60_000 })
    await expect(triageRegion).toContainText('引用来源 2')
    await expect(triageRegion.getByTestId('repair-confidence')).toHaveText('规则确定')
    repairRun = await completedRun(page, repairAccepted.runId, 'REPAIR')
    await expect.poll(() => observed.apiResponses.some((response) => (
      response.method === 'GET'
      && response.path === repairAccepted!.eventsUrl
      && response.status === 200
    )), { message: '维修 run 未观察到成功 SSE 响应' }).toBe(true)

    const repairProposalButton = page.getByRole('region', { name: '维修审批流程' })
      .getByRole('button', { name: '查看审批提案' })
    if (await repairProposalButton.isDisabled()) {
      const blockerText = await triageRegion.getByRole('status').allTextContents().catch(() => [])
      throw new BlockedInputError(
        'NO_AUTHORIZED_REPAIR_ASSIGNMENT_CANDIDATE',
        `维修分诊未产生可读审批提案：${sanitizeDiagnostic(blockerText.join(' '))}`,
      )
    }
    const repairProposalResponsePromise = page.waitForResponse((response) => (
      /^\/api\/ai\/proposals\/[^/]+$/.test(new URL(response.url()).pathname)
      && response.request().method() === 'GET'
    ))
    await repairProposalButton.click()
    const repairProposalResponse = await repairProposalResponsePromise
    repairProposal = await apiData<ProposalSnapshot>(repairProposalResponse, 200, '读取维修审批提案')
    expect(repairProposalResponse.headers()['cache-control']).toContain('private')
    expect(repairProposalResponse.headers()['cache-control']).toContain('no-store')
    assertProposal(repairProposal, 'REPAIR_ASSIGN', repairAccepted.runId)
    expect(repairProposal.requiredPermission).toContain('repair:write')
    expect(repairProposal.evidence.citations).toHaveLength(2)
    expect(eligibleRepairers.some((repairer) => (
      repairProposal!.proposedValue === `建议维修负责人：维修人员 #${repairer.id}`
    )), '维修建议人员必须来自只读查询得到的已启用 REPAIRER 脱敏标识').toBe(true)
    await expect(page).toHaveURL(/\/ai\/approvals$/)

    // 等待审批侧栏真正读取完整运行审计，再硬导航；不能由测试主动取消仍在途的 GET。
    const repairAuditPath = `/api/ai/audit/runs/${repairAccepted.runId}`
    await expect.poll(() => observed.aiRequests.some((request) => request.path === repairAuditPath))
      .toBe(true)
    await expect.poll(() => observed.pendingAuditRequests.size, { message: '审批运行审计仍在读取' }).toBe(0)
    await expect(page.getByTestId('approval-runtime-rail')).toContainText('SUCCEEDED')

    await page.goto('/notices/create')
    const draftingRegion = page.getByRole('region', { name: '公告 AI 起草工作台' })
    await expect(draftingRegion).toBeVisible()
    const pointsInput = draftingRegion.getByRole('textbox', { name: '公告要点' })
    const generateNoticeButton = draftingRegion.getByRole('button', { name: '生成 AI 草稿' })
    const noticeRequestCountBeforePii = observed.aiRequests.filter((request) => (
      request.method === 'POST' && request.path === '/api/ai/notices/drafts'
    )).length
    await pointsInput.fill('宿舍检查联系人邮箱 dorm.manager@example.edu，请写入公告。')
    await expect(page.getByTestId('notice-input-safety')).toContainText('检测到邮箱地址')
    await expect(generateNoticeButton).toBeDisabled()
    expect(observed.aiRequests.filter((request) => (
      request.method === 'POST' && request.path === '/api/ai/notices/drafts'
    ))).toHaveLength(noticeRequestCountBeforePii)
    piiBlockedWithoutRequest = true

    await pointsInput.fill('开展宿舍公共区域用电安全检查，核对违规电器和私拉电线，按楼栋分批完成并反馈。')
    await expect(page.getByTestId('notice-input-safety')).toHaveCount(0)
    await expect(generateNoticeButton).toBeEnabled()
    const noticeAcceptedResponsePromise = page.waitForResponse((response) => (
      new URL(response.url()).pathname === '/api/ai/notices/drafts'
      && response.request().method() === 'POST'
    ))
    await generateNoticeButton.click()
    noticeAccepted = await acceptedRun(await noticeAcceptedResponsePromise, '公告 AI 起草 command')
    const noticeDraftRegion = page.getByRole('region', { name: 'AI 草稿' })
    await expect(noticeDraftRegion).toContainText('AI 草稿已生成', { timeout: 60_000 })
    const noticeTitle = await page.getByRole('textbox', { name: '公告标题' }).inputValue()
    const noticeContent = await page.getByRole('textbox', { name: 'AI 草稿正文' }).inputValue()
    expect(noticeTitle.trim()).not.toBe('')
    expect(noticeContent.trim()).not.toBe('')
    expect(noticeContent).not.toMatch(/[<>]/)
    await expect(page.getByRole('region', { name: '内容检查' })).toContainText(/通过|置信度|引用来源/)
    noticeRun = await completedRun(page, noticeAccepted.runId, 'NOTICE')
    await expect.poll(() => observed.apiResponses.some((response) => (
      response.method === 'GET'
      && response.path === noticeAccepted!.eventsUrl
      && response.status === 200
    )), { message: '公告 run 未观察到成功 SSE 响应' }).toBe(true)

    const noticeProposalButton = page.getByRole('button', { name: '查看审批提案，提交审批' })
    await expect(noticeProposalButton).toBeEnabled()
    const noticeProposalResponsePromise = page.waitForResponse((response) => (
      /^\/api\/ai\/proposals\/[^/]+$/.test(new URL(response.url()).pathname)
      && response.request().method() === 'GET'
    ))
    await noticeProposalButton.click()
    const noticeProposalResponse = await noticeProposalResponsePromise
    noticeProposal = await apiData<ProposalSnapshot>(noticeProposalResponse, 200, '读取公告审批提案')
    expect(noticeProposalResponse.headers()['cache-control']).toContain('private')
    expect(noticeProposalResponse.headers()['cache-control']).toContain('no-store')
    assertProposal(noticeProposal, 'NOTICE_CREATE_DRAFT', noticeAccepted.runId)
    expect(noticeProposal.requiredPermission).toContain('notice:write')
    expect(noticeProposal.proposedValue).toContain('创建可编辑纯文本公告草稿')
    await expect(page).toHaveURL(/\/ai\/approvals$/)

    const anonymousContext = await browser.newContext()
    try {
      const anonymousResponse = await anonymousContext.request.get(
        `${visualBackendURL}/api/ai/proposals/${noticeProposal.id}`,
      )
      anonymousProposalReadStatus = anonymousResponse.status()
      expect(anonymousProposalReadStatus, '匿名请求不得读取提案预览和引用').toBe(401)
    } finally {
      await anonymousContext.close()
    }

    const repairsAfter = await readAllPages<RepairOrder>(page, '/api/repair-orders')
    const noticesAfter = await readAllPages<Notice>(page, '/api/notices')
    repairAfter = repairsAfter.records.find((repair) => repair.id === repairBefore!.id) ?? null
    noticeIdsAfter = noticesAfter.records.map((notice) => notice.id).sort((left, right) => left - right)
    expect(repairAfter, 'AI 分诊后原维修单必须仍可读取').not.toBeNull()
    expect(repairAfter?.status, 'AI 分诊不得修改维修状态').toBe(repairBefore.status)
    expect(repairAfter?.assigneeUserId ?? null, 'AI 分诊不得执行维修指派').toBe(repairBefore.assigneeUserId ?? null)
    expect(noticeIdsAfter, '公告提案不得创建或发布业务公告').toEqual(noticeIdsBefore)

    runtimeObservations.push(await observeBackendRuntime(page, 'after-workflows'))
    expect(runtimeObservations).toHaveLength(2)
    for (const observation of runtimeObservations) {
      expect(observation).toMatchObject({
        provider: requestedRuntimeContract.provider,
        streamingEnabled: requestedRuntimeContract.streamingEnabled,
        aiWriteExecutionEnabled: requestedRuntimeContract.aiWriteExecutionEnabled,
      })
    }

    const repairPath = `/api/ai/repairs/${repairBefore.id}/triage`
    expect(observed.apiResponses.filter((response) => (
      response.method === 'POST' && response.path === repairPath && response.status === 202
    )), '必须且只能有一条成功接收的维修分诊 command').toHaveLength(1)
    expect(observed.apiResponses.filter((response) => (
      response.method === 'POST' && response.path === '/api/ai/notices/drafts' && response.status === 202
    )), '必须且只能有一条成功接收的公告起草 command').toHaveLength(1)

    const allowedUnsafeAiPaths = new Set([
      '/api/ai/dashboard/queries',
      repairPath,
      '/api/ai/notices/drafts',
    ])
    const unexpectedAiWrites = observed.aiRequests.filter((request) => (
      unsafeMethods.has(request.method) && !allowedUnsafeAiPaths.has(request.path)
    ))
    const approvalWrites = observed.aiRequests.filter((request) => (
      unsafeMethods.has(request.method)
      && (/^\/api\/ai\/proposals\/[^/]+\/(approve|reject)$/.test(request.path)
        || request.path.startsWith('/api/ai/executions/'))
    ))
    const unexpectedRequestFailures = observed.requestFailures.filter((requestFailure) => {
      if (!requestFailure.errorText.includes('ERR_ABORTED')) return true
      return !observed.apiResponses.some((response) => (
        response.method === requestFailure.method
        && response.path === requestFailure.path
        && response.status >= 200
        && response.status < 400
      ))
    })
    expect(observed.businessWrites, '维修/公告 AI 预览链不得调用原业务写接口').toEqual([])
    expect(unexpectedAiWrites, '除允许的 AI command 外不得触发其他 AI 写端点').toEqual([])
    expect(approvalWrites, '本套件不得批准、拒绝或执行提案').toEqual([])
    expect(observed.failedApiResponses, '浏览器真实链不得出现 HTTP 4xx/5xx').toEqual([])
    expect(unexpectedRequestFailures, '除成功响应后的 SSE 连接关闭外不得出现网络失败').toEqual([])
    status = 'completed'
  } catch (error) {
    pendingError = error
    if (error instanceof BlockedInputError) {
      status = 'blocked'
      blocker = { code: error.blockerCode, message: sanitizeDiagnostic(error.message) }
    } else {
      status = 'failed'
      failureMessage = sanitizeDiagnostic(error)
    }
  } finally {
    if (session && runtimeObservations.length === 1) {
      try {
        runtimeObservations.push(await observeBackendRuntime(page, `${status}-final-observation`))
      } catch (error) {
        failureMessage ??= `第二次 readiness 观测失败：${sanitizeDiagnostic(error)}`
      }
    }

    const requested = requestedRuntimeContract ?? null
    const firstReadiness = runtimeObservations[0]
    const runtimeContract = requested && firstReadiness ? {
      clientMode: requested.clientMode,
      provider: firstReadiness.provider,
      streamingEnabled: firstReadiness.streamingEnabled,
      aiWriteExecutionEnabled: firstReadiness.aiWriteExecutionEnabled,
      evidenceSource: firstReadiness.source,
      database: requested.database,
      databaseHost: requested.databaseHost,
      databasePort: requested.databasePort,
      redisHost: requested.redisHost,
      redisPort: requested.redisPort,
      redisDatabase: requested.redisDatabase,
      redisKeyPrefix: requested.redisKeyPrefix,
      frontendOrigin: requested.frontendOrigin,
      frontendPort: requested.frontendPort,
      backendOrigin: requested.backendOrigin,
      backendPort: requested.backendPort,
    } : null
    const repairPath = repairBefore ? `/api/ai/repairs/${repairBefore.id}/triage` : null
    const allowedUnsafeAiPaths = new Set([
      '/api/ai/dashboard/queries',
      '/api/ai/notices/drafts',
      ...(repairPath ? [repairPath] : []),
    ])
    const unexpectedAiWrites = observed.aiRequests.filter((request) => (
      unsafeMethods.has(request.method) && !allowedUnsafeAiPaths.has(request.path)
    ))
    const approvalWrites = observed.aiRequests.filter((request) => (
      unsafeMethods.has(request.method)
      && (/^\/api\/ai\/proposals\/[^/]+\/(approve|reject)$/.test(request.path)
        || request.path.startsWith('/api/ai/executions/'))
    ))
    const unexpectedRequestFailures = observed.requestFailures.filter((requestFailure) => {
      if (!requestFailure.errorText.includes('ERR_ABORTED')) return true
      return !observed.apiResponses.some((response) => (
        response.method === requestFailure.method
        && response.path === requestFailure.path
        && response.status >= 200
        && response.status < 400
      ))
    })

    writeFileSync(manifestPath, `${JSON.stringify({
      schemaVersion: 'ai-live-repair-notice-stage4.v1',
      generatedAt: startedAt,
      finishedAt: new Date().toISOString(),
      status,
      blocker,
      failure: failureMessage,
      requestedRuntimeContract: requested,
      runtimeContract,
      runtimeObservations,
      rbac: session ? {
        userId: session.id,
        roleCodes: session.roleCodes,
        permissionsChecked: [
          'repair:read', 'repair:write', 'system:user:read', 'notice:read', 'notice:write',
          'ai:repair:triage', 'ai:notice:draft', 'ai:approval:review',
        ],
      } : null,
      inputReadiness: {
        unfinishedRepairCount,
        enabledRepairerCount,
        repairBefore: repairManifest(repairBefore),
        noticeIdsBefore,
      },
      securityChecks: {
        csrfDenialStatus,
        piiBlockedWithoutRequest,
        anonymousProposalReadStatus,
        approvalWrites,
        aiWriteExecutionEnabled: runtimeContract?.aiWriteExecutionEnabled ?? requested?.aiWriteExecutionEnabled ?? null,
      },
      runs: {
        repair: repairAccepted && repairRun ? {
          runId: repairAccepted.runId,
          eventsUrl: repairAccepted.eventsUrl,
          state: repairRun.state,
          capability: repairRun.capability,
        } : null,
        notice: noticeAccepted && noticeRun ? {
          runId: noticeAccepted.runId,
          eventsUrl: noticeAccepted.eventsUrl,
          state: noticeRun.state,
          capability: noticeRun.capability,
        } : null,
      },
      proposals: {
        repair: proposalManifest(repairProposal),
        notice: proposalManifest(noticeProposal),
      },
      businessStateAfter: {
        repairAfter: repairManifest(repairAfter),
        noticeIdsAfter,
      },
      aiRequests: observed.aiRequests,
      aiResponses: observed.apiResponses.filter((response) => response.path.startsWith('/api/ai/')),
      businessWrites: observed.businessWrites,
      unexpectedAiWrites,
      approvalWrites,
      failedApiResponses: observed.failedApiResponses,
      unexpectedRequestFailures,
      toleratedCompletedConnectionClosures: observed.requestFailures.filter((failure) => (
        !unexpectedRequestFailures.includes(failure)
      )),
      evidenceBindings: bindings,
    }, null, 2)}\n`, 'utf8')
  }

  if (pendingError) throw pendingError
})
