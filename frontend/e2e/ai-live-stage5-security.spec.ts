import { createHash, randomUUID } from 'node:crypto'
import { spawnSync } from 'node:child_process'
import { mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import {
  expect,
  test,
  type APIResponse,
  type BrowserContext,
  type Page,
  type Request,
} from '@playwright/test'
import { requireAiLiveIsolation } from './ai-live-isolation'
import {
  requireVisualCredentials,
  sanitizeDiagnostic,
  visualBackendEnv,
  visualBaseURL,
  visualDirectory,
  visualOutputName,
} from './live-visual-settings'
import {
  isVisualLoginServiceNotReady,
  retryVisualLogin,
  type VisualLoginAttemptResult,
} from './live-visual-login-retry'

type JsonObject = Record<string, unknown>

interface ApiEnvelope<T> {
  code?: number
  message?: string
  data?: T
}

interface ChainEvidence {
  chain: string
  status: 'passed' | 'failed' | 'pending'
  evidence: JsonObject
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

interface NetworkObservation {
  aiRequests: ObservedRequest[]
  businessWrites: ObservedRequest[]
  apiResponses: ObservedResponse[]
  failedApiResponses: ObservedResponse[]
  requestFailures: RequestFailure[]
}

const e2eDirectory = fileURLToPath(new URL('.', import.meta.url))
const frontendDirectory = resolve(e2eDirectory, '..')
const repositoryDirectory = resolve(frontendDirectory, '..')
const stage5Directory = resolve(visualDirectory, 'stage5')
const securityManifestPath = resolve(stage5Directory, 'security-manifest.json')
const stage5RedisKeyPrefix = process.env.AI_LIVE_STAGE5_REDIS_KEY_PREFIX?.trim()
  || `ai-live:stage5:${visualOutputName.replaceAll('.', '-')}`
const stage5Isolation = requireAiLiveIsolation({
  ...visualBackendEnv,
  AI_REDIS_KEY_PREFIX: stage5RedisKeyPrefix,
}, { requireOutputBoundRedisPrefix: true })
const mysqlExecutable = process.env.AI_LIVE_MYSQL_EXECUTABLE || 'mysql'
const unsafeMethods = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])

function delay(milliseconds: number) {
  return new Promise((done) => setTimeout(done, milliseconds))
}

function sha256(value: string | Buffer) {
  return createHash('sha256').update(value).digest('hex')
}

function createNetworkObservation(): NetworkObservation {
  return {
    aiRequests: [],
    businessWrites: [],
    apiResponses: [],
    failedApiResponses: [],
    requestFailures: [],
  }
}

function observeNetwork(page: Page, observed: NetworkObservation) {
  page.on('request', (request: Request) => {
    const path = new URL(request.url()).pathname
    const entry = { method: request.method(), path }
    if (path.startsWith('/api/ai/')) observed.aiRequests.push(entry)
    if (path.startsWith('/api/')
      && unsafeMethods.has(request.method())
      && !path.startsWith('/api/ai/')
      && !path.startsWith('/api/auth/')
      && !path.startsWith('/api/security/')) {
      observed.businessWrites.push(entry)
    }
  })
  page.on('response', (response) => {
    const path = new URL(response.url()).pathname
    if (!path.startsWith('/api/')) return
    const entry = { method: response.request().method(), path, status: response.status() }
    observed.apiResponses.push(entry)
    if (response.status() >= 400) observed.failedApiResponses.push(entry)
  })
  page.on('requestfailed', (request) => {
    const path = new URL(request.url()).pathname
    if (!path.startsWith('/api/')) return
    observed.requestFailures.push({
      method: request.method(),
      path,
      errorText: sanitizeDiagnostic(request.failure()?.errorText ?? 'unknown'),
    })
  })
}

function framed(value: string) {
  return `${value.length}:${value}|`
}

function sqlString(value: string) {
  return `'${value.replaceAll("'", "''")}'`
}

function runMysql(sql: string) {
  const connection = stage5Isolation.connection
  const result = spawnSync(mysqlExecutable, [
    '--protocol=tcp',
    `--host=${connection.hostname}`,
    `--port=${connection.port}`,
    `--user=${connection.username}`,
    `--database=${connection.database}`,
    '--default-character-set=utf8mb4',
    '--batch',
    '--skip-column-names',
  ], {
    encoding: 'utf8',
    env: { ...process.env, MYSQL_PWD: connection.password },
    input: sql,
    timeout: 30_000,
    windowsHide: true,
  })
  if (result.error || result.status !== 0) {
    const detail = result.error ?? result.stderr ?? `mysql exit ${result.status}`
    throw new Error(`Stage 5 security MySQL 执行失败：${sanitizeDiagnostic(detail)}`)
  }
  return result.stdout.trim()
}

function mysqlScalar(sql: string) {
  return runMysql(sql).split(/\r?\n/).filter(Boolean).at(-1) ?? ''
}

async function responseEnvelope<T>(response: APIResponse, label: string): Promise<ApiEnvelope<T>> {
  const raw = await response.text()
  try {
    return JSON.parse(raw) as ApiEnvelope<T>
  } catch {
    throw new Error(`${label} 返回非 JSON：HTTP ${response.status()} ${sanitizeDiagnostic(raw)}`)
  }
}

async function apiData<T>(response: APIResponse, expectedStatus: number, label: string): Promise<T> {
  const payload = await responseEnvelope<T>(response, label)
  expect(response.status(), `${label}：${sanitizeDiagnostic(payload.message ?? '')}`).toBe(expectedStatus)
  expect(payload.code ?? 0, `${label}：${sanitizeDiagnostic(payload.message ?? '')}`).toBe(0)
  if (payload.data === undefined) throw new Error(`${label} 缺少 data`)
  return payload.data
}

async function expectApiError(
  response: APIResponse,
  expectedStatus: number,
  expectedErrorCode: string,
  label: string,
) {
  const payload = await responseEnvelope<{ errorCode?: string }>(response, label)
  expect(response.status(), `${label}：${sanitizeDiagnostic(payload.message ?? '')}`).toBe(expectedStatus)
  expect(payload.data?.errorCode, `${label} 未返回预期错误码`).toBe(expectedErrorCode)
  return payload
}

function commandHeaders(token: string, refererPath: string, idempotencyKey?: string) {
  return {
    'X-CSRF-Token': token,
    Origin: visualBaseURL,
    Referer: `${visualBaseURL}${refererPath}`,
    ...(idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : {}),
  }
}

async function login(
  context: BrowserContext,
  credentials: { username: string; password: string } = requireVisualCredentials(),
) {
  await retryVisualLogin(async ({ remainingMs }): Promise<VisualLoginAttemptResult> => {
    try {
      const response = await context.request.post('/api/auth/login', {
        data: credentials,
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
  const state = await context.request.storageState()
  if (!state.cookies.some((cookie) => cookie.name === 'Authorization')) {
    throw new Error(`Stage 5 security 用户 ${credentials.username} 登录后缺少 Authorization Cookie`)
  }
  await context.addCookies(state.cookies)
}

async function csrfToken(context: BrowserContext) {
  return (await apiData<{ token: string }>(
    await context.request.get('/api/security/csrf'),
    200,
    '获取 CSRF token',
  )).token
}

async function waitForRun(context: BrowserContext, runId: string, label: string) {
  const deadline = Date.now() + 90_000
  let latest: JsonObject = {}
  while (Date.now() < deadline) {
    latest = await apiData<JsonObject>(
      await context.request.get(`/api/ai/runs/${encodeURIComponent(runId)}`),
      200,
      `读取${label}运行状态`,
    )
    const state = String(latest.state ?? '').toUpperCase()
    if (['SUCCEEDED', 'PARTIAL', 'FAILED', 'NEEDS_REVIEW', 'CANCELLED', 'TIMED_OUT'].includes(state)) break
    await delay(500)
  }
  expect(String(latest.state ?? '').toUpperCase(), `${label}未成功完成：${JSON.stringify(latest)}`)
    .toMatch(/^(SUCCEEDED|PARTIAL)$/)
  return latest
}

async function listRiskCases(context: BrowserContext, type = '维修风险') {
  return apiData<{ records: JsonObject[]; total: number }>(
    await context.request.get(`/api/ai/risk-cases?page=1&pageSize=100&type=${encodeURIComponent(type)}`),
    200,
    `读取${type}列表`,
  )
}

async function createFreshRepairRisk(context: BrowserContext, token: string, marker: string) {
  const before = await listRiskCases(context)
  const beforeIds = new Set(before.records.map((record) => String(record.id ?? '')))
  const location = `阶段五安全链-${marker}`
  for (let index = 0; index < 3; index += 1) {
    await apiData<JsonObject>(
      await context.request.post('/api/repair-orders', {
        headers: commandHeaders(token, '/repairs'),
        data: {
          reporter: `阶段五安全链-${marker}`,
          location,
          type: '水电维修',
          description: `阶段五重复维修风险造数 ${marker}-${index + 1}`,
          assigneeUserId: null,
        },
      }),
      201,
      `创建风险造数维修工单 ${index + 1}`,
    )
  }

  const scan = await apiData<{ id: string; state: string }>(
    await context.request.post('/api/ai/risk-scans', {
      headers: commandHeaders(token, '/ai/risks', `stage5-security-risk-${marker}`),
    }),
    202,
    '启动 Stage 5 风险扫描',
  )
  const scanDeadline = Date.now() + 90_000
  let scanState = String(scan.state ?? '').toUpperCase()
  while (Date.now() < scanDeadline
    && !['SUCCEEDED', 'PARTIAL', 'FAILED', 'NEEDS_REVIEW'].includes(scanState)) {
    const latest = await apiData<JsonObject>(
      await context.request.get(`/api/ai/risk-scans/${encodeURIComponent(scan.id)}`),
      200,
      '读取 Stage 5 风险扫描状态',
    )
    scanState = String(latest.state ?? '').toUpperCase()
    if (!['SUCCEEDED', 'PARTIAL', 'FAILED', 'NEEDS_REVIEW'].includes(scanState)) await delay(500)
  }
  expect(scanState, 'Stage 5 风险扫描未成功完成').toMatch(/^(SUCCEEDED|PARTIAL)$/)
  const after = await listRiskCases(context)
  const created = after.records.find((record) => (
    !beforeIds.has(String(record.id ?? '')) && String(record.state ?? '').toLowerCase() === 'open'
  ))
  expect(created, `风险扫描未生成新的维修风险：${JSON.stringify(after)}`).toBeDefined()
  return apiData<JsonObject>(
    await context.request.get(`/api/ai/risk-cases/${encodeURIComponent(String(created!.id))}`),
    200,
    '读取新生成维修风险详情',
  )
}

async function createNoticeProposal(
  context: BrowserContext,
  token: string,
  marker: string,
) {
  const accepted = await apiData<{ runId: string; eventsUrl: string }>(
    await context.request.post('/api/ai/notices/drafts', {
      headers: commandHeaders(token, '/notices/create', `stage5-security-notice-${marker}`),
      data: {
        points: `Stage 5 安全链公告 ${marker}：检查公共区域用电与消防通道。`,
        type: '安全卫生',
        tone: '正式',
        audience: '全体学生',
      },
    }),
    202,
    '创建真实公告 AI 提案',
  )
  await waitForRun(context, accepted.runId, '公告提案')

  const page = await apiData<{ records: JsonObject[]; total: number }>(
    await context.request.get('/api/ai/proposals?page=1&pageSize=100&actionType=NOTICE_CREATE_DRAFT'),
    200,
    '读取公告提案列表',
  )
  const record = page.records.find((candidate) => String(candidate.runId ?? '') === accepted.runId)
  expect(record, `公告 run ${accepted.runId} 未绑定审批提案`).toBeDefined()
  const proposal = await apiData<JsonObject>(
    await context.request.get(`/api/ai/proposals/${encodeURIComponent(String(record!.id))}`),
    200,
    '读取公告提案详情',
  )
  expect(String(proposal.state ?? '')).toBe('pending_approval')
  return { runId: accepted.runId, proposal }
}

async function provisionRepairer(context: BrowserContext, token: string, marker: string) {
  const roles = await apiData<Array<{ id: number; code: string }>>(
    await context.request.get('/api/roles/options'),
    200,
    '读取维修员角色选项',
  )
  const repairerRole = roles.find((role) => role.code === 'REPAIRER')
  expect(repairerRole, '专用 Stage 5 E2E 库缺少 REPAIRER 内置角色').toBeDefined()
  const usernameMarker = marker.replace(/[^a-z0-9]/gi, '').toLowerCase()
  const username = `s5_rep_${usernameMarker}_${randomUUID().slice(0, 4)}`.slice(0, 32)
  await apiData<{ id: number; username: string }>(
    await context.request.post('/api/users', {
      headers: commandHeaders(token, '/users'),
      data: {
        username,
        displayName: '阶段五维修人员',
        password: `S5!${randomUUID()}aA1`,
        enabled: true,
        roleIds: [repairerRole!.id],
      },
    }),
    201,
    '创建 Stage 5 维修人员',
  )
}

async function createRepairProposal(
  context: BrowserContext,
  token: string,
  marker: string,
) {
  await provisionRepairer(context, token, marker)
  const repair = await apiData<{ id: number; code: string }>(
    await context.request.post('/api/repair-orders', {
      headers: commandHeaders(token, '/repairs'),
      data: {
        reporter: `S5 对账 ${marker}`,
        location: `Stage 5 execution lease ${marker}`,
        type: '水电维修',
        description: `Stage 5 PROVEN_NOT_EXECUTED 对账维修工单 ${marker}`,
        assigneeUserId: null,
      },
    }),
    201,
    '创建 execution lease 维修工单',
  )
  const accepted = await apiData<{ runId: string; eventsUrl: string }>(
    await context.request.post(`/api/ai/repairs/${repair.id}/triage`, {
      headers: commandHeaders(token, '/repairs', `stage5-security-repair-${marker}`),
      data: {},
    }),
    202,
    '创建真实维修指派提案',
  )
  await waitForRun(context, accepted.runId, '维修指派提案')

  const page = await apiData<{ records: JsonObject[]; total: number }>(
    await context.request.get('/api/ai/proposals?page=1&pageSize=100&actionType=REPAIR_ASSIGN'),
    200,
    '读取维修指派提案列表',
  )
  const record = page.records.find((candidate) => String(candidate.runId ?? '') === accepted.runId)
  expect(record, `维修 run ${accepted.runId} 未绑定审批提案`).toBeDefined()
  const proposal = await apiData<JsonObject>(
    await context.request.get(`/api/ai/proposals/${encodeURIComponent(String(record!.id))}`),
    200,
    '读取维修指派提案详情',
  )
  expect(String(proposal.state ?? '')).toBe('pending_approval')
  return { repair, runId: accepted.runId, proposal }
}

async function auditDetail(context: BrowserContext, runId: string) {
  return apiData<JsonObject>(
    await context.request.get(`/api/ai/audit/runs/${encodeURIComponent(runId)}`),
    200,
    '读取运行审计详情',
  )
}

function arrayField(value: JsonObject, field: string) {
  return Array.isArray(value[field]) ? value[field] as JsonObject[] : []
}

async function provisionPrincipal(
  context: BrowserContext,
  token: string,
  options: {
    suffix: string
    rolePrefix: string
    usernamePrefix: string
    displayName: string
    password: string
    permissions: string[]
  },
) {
  const permissions = await apiData<Array<{ id: number; code: string }>>(
    await context.request.get('/api/permissions'),
    200,
    '读取 RBAC 权限目录',
  )
  const requested = options.permissions.map((code) => {
    const permission = permissions.find((candidate) => candidate.code === code)
    expect(permission, `权限目录缺少 ${code}`).toBeDefined()
    return permission!
  })
  const roleCode = `${options.rolePrefix}_${options.suffix}`.slice(0, 32)
  const username = `${options.usernamePrefix}-${options.suffix.toLowerCase()}`.slice(0, 32)
  const role = await apiData<{ id: number; code: string }>(
    await context.request.post('/api/roles', {
      headers: commandHeaders(token, '/roles'),
      data: {
        code: roleCode,
        name: options.displayName,
        description: '仅用于本机隔离 Stage 5 live E2E',
        enabled: true,
        permissionIds: requested.map(({ id }) => id),
      },
    }),
    201,
    `创建 ${options.displayName}角色`,
  )
  await apiData<{ id: number; username: string }>(
    await context.request.post('/api/users', {
      headers: commandHeaders(token, '/users'),
      data: {
        username,
        displayName: options.displayName,
        password: options.password,
        enabled: true,
        roleIds: [role.id],
      },
    }),
    201,
    `创建 ${options.displayName}用户`,
  )
  return { username, password: options.password, roleCode, permissionCodes: requested.map(({ code }) => code) }
}

function seedNeedsReviewExecution(proposal: JsonObject) {
  const proposalId = String(proposal.id ?? '')
  const executionId = randomUUID()
  const executionKey = sha256(`execution|${proposalId}`)
  const leaseTokenHash = sha256(`lease|${proposalId}`)
  runMysql(`
START TRANSACTION;
UPDATE ai_action_proposal
SET state='NEEDS_REVIEW', approved_count=1, risk_level='HIGH', version=4,
    updated_at=CURRENT_TIMESTAMP(6)
WHERE public_id=${sqlString(proposalId)};
INSERT INTO ai_action_execution
  (public_id,proposal_id,state,version,handler_name,execution_key,lease_token_hash,
   executed_by_user_id,result_resource_type,result_resource_id,response_redacted,started_at,
   error_code,error_summary,created_operator_user_id,updated_operator_user_id,created_at,updated_at)
SELECT ${sqlString(executionId)},id,'NEEDS_REVIEW',1,action_type,${sqlString(executionKey)},
       ${sqlString(leaseTokenHash)},proposer_user_id,NULL,NULL,NULL,
       CURRENT_TIMESTAMP(6)-INTERVAL 5 MINUTE,'AI_EXECUTION_OUTCOME_UNKNOWN','等待人工对账',
       proposer_user_id,proposer_user_id,CURRENT_TIMESTAMP(6),CURRENT_TIMESTAMP(6)
FROM ai_action_proposal WHERE public_id=${sqlString(proposalId)};
COMMIT;
`)
  return executionId
}

function executionCount(proposalId: string) {
  return Number(mysqlScalar(
    `SELECT COUNT(*) FROM ai_action_execution WHERE proposal_id=(SELECT id FROM ai_action_proposal WHERE public_id=${sqlString(proposalId)});`,
  ))
}

function executionSnapshot(proposalId: string) {
  const [publicId, state, version, leaseTokenHash, reconfirmedByUserId = ''] = runMysql(
    `SELECT public_id,state,version,lease_token_hash,COALESCE(reconfirmed_by_user_id,'') FROM ai_action_execution WHERE proposal_id=(SELECT id FROM ai_action_proposal WHERE public_id=${sqlString(proposalId)});`,
  ).split('\t')
  return {
    publicId,
    state,
    version: Number(version),
    leaseTokenHash,
    reconfirmedByUserId,
  }
}

function sourceBindings() {
  const relativePaths = [
    'frontend/e2e/ai-live-stage5-security.spec.ts',
    'frontend/e2e/ai-live-stage5.spec.ts',
    'frontend/e2e/ai-live-isolation.ts',
    'frontend/e2e/ai-live-global-setup.ts',
    'frontend/e2e/live-visual-settings.ts',
    'frontend/playwright.ai-live-stage5.config.ts',
    'frontend/src/views/AiRiskView.vue',
    'backend/src/main/java/com/example/dormitory/ai/api/AiRiskController.java',
    'backend/src/main/java/com/example/dormitory/ai/api/AiProposalController.java',
    'backend/src/main/java/com/example/dormitory/ai/api/AiExecutionController.java',
    'backend/src/main/java/com/example/dormitory/ai/api/AiAuditController.java',
    'backend/src/main/java/com/example/dormitory/ai/api/AiExceptionHandler.java',
    'backend/src/main/java/com/example/dormitory/controller/StepUpController.java',
    'backend/src/main/java/com/example/dormitory/ai/application/run/AiRunService.java',
    'backend/src/main/java/com/example/dormitory/ai/approval/ActionProposalService.java',
    'backend/src/main/java/com/example/dormitory/ai/security/RecentAuthenticationPolicy.java',
    'backend/src/main/java/com/example/dormitory/ai/security/StepUpProofCrypto.java',
    'backend/src/main/java/com/example/dormitory/ai/risk/RiskCaseService.java',
    'backend/src/main/java/com/example/dormitory/ai/infrastructure/persistence/JdbcRiskCaseRepository.java',
    'backend/src/main/resources/ai-schema.sql',
  ]
  return relativePaths.map((relativePath) => {
    const absolutePath = resolve(repositoryDirectory, relativePath)
    const contents = readFileSync(absolutePath)
    const stats = statSync(absolutePath)
    return {
      relativePath,
      sha256: sha256(contents),
      bytes: stats.size,
      mtimeMs: stats.mtimeMs,
    }
  })
}

test('阶段 5 五条真实治理安全链保持服务端约束', async ({ browser }) => {
  mkdirSync(stage5Directory, { recursive: true })
  const startedAt = new Date()
  const suffix = Date.now().toString(36).toUpperCase()
  const chains: ChainEvidence[] = [
    { chain: 'risk-stale-cas', status: 'pending', evidence: {} },
    { chain: 'approval-step-up', status: 'pending', evidence: {} },
    { chain: 'execution-reconfirm-single-row', status: 'pending', evidence: {} },
    { chain: 'audit-content-break-glass', status: 'pending', evidence: {} },
    { chain: 'risk-readonly-boundary', status: 'pending', evidence: {} },
  ]
  const uiNetwork = createNetworkObservation()
  const contexts: BrowserContext[] = []
  let failure: unknown

  try {
    const adminContext = await browser.newContext({ baseURL: visualBaseURL })
    contexts.push(adminContext)
    await login(adminContext)
    const adminCsrf = await csrfToken(adminContext)

    const risk = await createFreshRepairRisk(adminContext, adminCsrf, suffix)
    const riskId = String(risk.id ?? '')
    const initialVersion = Number(risk.caseVersion ?? 0)
    expect(riskId).toMatch(/^[0-9a-f-]{36}$/i)
    expect(initialVersion).toBeGreaterThanOrEqual(1)
    const acknowledgeResponse = await adminContext.request.post(
      `/api/ai/risk-cases/${encodeURIComponent(riskId)}/acknowledge`,
      {
        headers: commandHeaders(adminCsrf, '/ai/risks', randomUUID()),
        data: { caseVersion: initialVersion, detail: '开始人工核验 Stage 5 风险线索' },
      },
    )
    expect(acknowledgeResponse.status()).toBe(204)
    const acknowledged = await apiData<JsonObject>(
      await adminContext.request.get(`/api/ai/risk-cases/${encodeURIComponent(riskId)}`),
      200,
      '读取已确认风险详情',
    )
    const staleSnapshot = {
      state: String(acknowledged.state ?? ''),
      caseVersion: Number(acknowledged.caseVersion ?? 0),
      events: Array.isArray(acknowledged.events) ? acknowledged.events.length : -1,
    }
    await expectApiError(
      await adminContext.request.post(`/api/ai/risk-cases/${encodeURIComponent(riskId)}/resolve`, {
        headers: commandHeaders(adminCsrf, '/ai/risks', randomUUID()),
        data: { caseVersion: initialVersion, detail: '使用旧版本尝试解决 Stage 5 风险' },
      }),
      409,
      'AI_RISK_CASE_VERSION_CONFLICT',
      '风险旧版本写入',
    )
    const afterStale = await apiData<JsonObject>(
      await adminContext.request.get(`/api/ai/risk-cases/${encodeURIComponent(riskId)}`),
      200,
      '读取旧版本拒绝后的风险详情',
    )
    const afterStaleSnapshot = {
      state: String(afterStale.state ?? ''),
      caseVersion: Number(afterStale.caseVersion ?? 0),
      events: Array.isArray(afterStale.events) ? afterStale.events.length : -1,
    }
    expect(afterStaleSnapshot).toEqual(staleSnapshot)
    chains[0] = {
      chain: 'risk-stale-cas',
      status: 'passed',
      evidence: {
        riskId,
        rejectedStatus: 409,
        errorCode: 'AI_RISK_CASE_VERSION_CONFLICT',
        before: staleSnapshot,
        after: afterStaleSnapshot,
      },
    }

    const approvalFixture = await createNoticeProposal(adminContext, adminCsrf, `APP-${suffix}`)
    const proposalBefore = approvalFixture.proposal
    const proposalId = String(proposalBefore.id ?? '')
    const approvalBody = {
      version: Number(proposalBefore.version ?? 0),
      payloadHash: String(proposalBefore.payloadHash ?? ''),
      businessSnapshotHash: String(proposalBefore.businessSnapshotHash ?? ''),
      comment: 'Stage 5 服务端 step-up 拒绝验证',
    }
    const auditBeforeApproval = await auditDetail(adminContext, approvalFixture.runId)
    const approvalCountBefore = arrayField(auditBeforeApproval, 'approvals').length
    await expectApiError(
      await adminContext.request.post(`/api/ai/proposals/${encodeURIComponent(proposalId)}/approve`, {
        headers: commandHeaders(adminCsrf, '/ai/approvals', randomUUID()),
        data: approvalBody,
      }),
      400,
      'AI_INVALID_REQUEST',
      '审批缺少 step-up proof',
    )
    await expectApiError(
      await adminContext.request.post(`/api/ai/proposals/${encodeURIComponent(proposalId)}/approve`, {
        headers: {
          ...commandHeaders(adminCsrf, '/ai/approvals', randomUUID()),
          'X-Step-Up-Proof': 'invalid-proof-stage5',
        },
        data: approvalBody,
      }),
      403,
      'AI_STEP_UP_PROOF_INVALID',
      '审批无效 step-up proof',
    )
    const proposalAfter = await apiData<JsonObject>(
      await adminContext.request.get(`/api/ai/proposals/${encodeURIComponent(proposalId)}`),
      200,
      '读取 step-up 拒绝后的提案',
    )
    const auditAfterApproval = await auditDetail(adminContext, approvalFixture.runId)
    const approvalBefore = {
      state: proposalBefore.state,
      version: proposalBefore.version,
      approvals: approvalCountBefore,
    }
    const approvalAfter = {
      state: proposalAfter.state,
      version: proposalAfter.version,
      approvals: arrayField(auditAfterApproval, 'approvals').length,
    }
    expect(approvalAfter).toEqual(approvalBefore)
    chains[1] = {
      chain: 'approval-step-up',
      status: 'passed',
      evidence: {
        proposalId,
        missingProof: { status: 400, errorCode: 'AI_INVALID_REQUEST' },
        invalidProof: { status: 403, errorCode: 'AI_STEP_UP_PROOF_INVALID' },
        before: approvalBefore,
        after: approvalAfter,
      },
    }

    const repairFixture = await createRepairProposal(adminContext, adminCsrf, `EXEC-${suffix}`)
    const repairProposal = repairFixture.proposal
    const repairProposalId = String(repairProposal.id ?? '')
    const executionId = seedNeedsReviewExecution(repairProposal)
    const needsReview = await apiData<JsonObject>(
      await adminContext.request.get(`/api/ai/proposals/${encodeURIComponent(repairProposalId)}`),
      200,
      '读取 NEEDS_REVIEW 提案',
    )
    expect(String(needsReview.executionId ?? '')).toBe(executionId)
    const reconfirmBody = {
      version: Number(needsReview.version ?? 0),
      payloadHash: String(needsReview.payloadHash ?? ''),
      businessSnapshotHash: String(needsReview.businessSnapshotHash ?? ''),
      resolution: 'PROVEN_NOT_EXECUTED',
      comment: '已核对原业务事实，确认原维修指派事务未执行',
    }
    const reconfirmRequestHash = sha256(
      `execution-reconfirm.v1|${framed(executionId)}${framed(String(reconfirmBody.version))}`
      + `${framed(reconfirmBody.payloadHash.toLowerCase())}`
      + `${framed(reconfirmBody.businessSnapshotHash.toLowerCase())}`
      + `${framed(reconfirmBody.resolution)}${framed(reconfirmBody.comment.trim())}`,
    )
    const rowsBefore = executionCount(repairProposalId)
    const executionBefore = executionSnapshot(repairProposalId)
    await expectApiError(
      await adminContext.request.post(`/api/ai/executions/${encodeURIComponent(executionId)}/reconfirm`, {
        headers: {
          ...commandHeaders(adminCsrf, '/ai/approvals', randomUUID()),
          'X-Step-Up-Proof': 'invalid-proof-stage5-reconfirm',
        },
        data: reconfirmBody,
      }),
      403,
      'AI_STEP_UP_PROOF_INVALID',
      'execution reconfirm 无效 step-up proof',
    )
    expect(executionSnapshot(repairProposalId)).toEqual(executionBefore)

    const proof = await apiData<{ proof: string }>(
      await adminContext.request.post('/api/security/step-up', {
        headers: commandHeaders(adminCsrf, '/ai/approvals'),
        data: {
          password: requireVisualCredentials().password,
          actionCode: 'PROPOSAL_RECONFIRM',
          resourcePublicId: executionId,
          requestHash: reconfirmRequestHash,
        },
      }),
      200,
      '签发 execution reconfirm step-up proof',
    )
    await expectApiError(
      await adminContext.request.post(`/api/ai/executions/${encodeURIComponent(executionId)}/reconfirm`, {
        headers: {
          ...commandHeaders(adminCsrf, '/ai/approvals', randomUUID()),
          'X-Step-Up-Proof': proof.proof,
        },
        data: reconfirmBody,
      }),
      403,
      'AI_PERMISSION_DENIED',
      '写开关关闭时的 PROVEN_NOT_EXECUTED execution reconfirm',
    )
    const rowsAfter = executionCount(repairProposalId)
    const executionAfter = executionSnapshot(repairProposalId)
    expect(rowsBefore).toBe(1)
    expect(rowsAfter).toBe(1)
    expect(executionAfter.publicId).toBe(executionBefore.publicId)
    expect(executionAfter.version).toBeGreaterThan(executionBefore.version)
    expect(executionAfter.leaseTokenHash).not.toBe(executionBefore.leaseTokenHash)
    expect(executionAfter.reconfirmedByUserId).not.toBe('')
    expect(executionAfter.state).toBe('FAILED')
    const reconfirmAudit = await auditDetail(adminContext, repairFixture.runId)
    expect(arrayField(reconfirmAudit, 'executions').filter((entry) => String(entry.id ?? '') === executionId)).toHaveLength(1)
    chains[2] = {
      chain: 'execution-reconfirm-single-row',
      status: 'passed',
      evidence: {
        proposalId: repairProposalId,
        repairOrderId: repairFixture.repair.id,
        executionId,
        resolution: 'PROVEN_NOT_EXECUTED',
        invalidProof: { status: 403, errorCode: 'AI_STEP_UP_PROOF_INVALID' },
        writeKillSwitch: { enabled: false, status: 403, errorCode: 'AI_PERMISSION_DENIED' },
        rowsBefore,
        rowsAfter,
        executionBefore,
        executionAfter,
      },
    }

    const auditPrincipal = await provisionPrincipal(adminContext, adminCsrf, {
      suffix,
      rolePrefix: 'S5_AUDIT',
      usernamePrefix: 's5-audit',
      displayName: 'Stage 5 审计正文用户',
      password: `S5-Audit!${randomUUID()}aA1`,
      permissions: [
        'notice:read',
        'notice:write',
        'ai:notice:draft',
        'ai:approval:review',
        'ai:audit:read',
        'ai:audit:content:read',
      ],
    })
    const auditContext = await browser.newContext({ baseURL: visualBaseURL })
    contexts.push(auditContext)
    await login(auditContext, auditPrincipal)
    const auditCsrf = await csrfToken(auditContext)
    const ownedNotice = await createNoticeProposal(auditContext, auditCsrf, `AUD-${suffix}`)
    const ownedAuditBefore = await auditDetail(auditContext, ownedNotice.runId)
    const readCountBefore = arrayField(ownedAuditBefore, 'hashChain')
      .filter((entry) => String(entry.eventType ?? '') === 'AUDIT_CONTENT_READ').length
    await expectApiError(
      await auditContext.request.get(`/api/ai/audit/runs/${encodeURIComponent(ownedNotice.runId)}/content`, {
        headers: { 'X-Audit-Reason': 'short' },
      }),
      400,
      'AI_AUDIT_REASON_REQUIRED',
      '审计正文短理由',
    )
    const validReason = 'Stage 5 security incident audit review'
    await expectApiError(
      await auditContext.request.get(`/api/ai/audit/runs/${encodeURIComponent(ownedNotice.runId)}/content`, {
        headers: { 'X-Audit-Reason': validReason },
      }),
      403,
      'AI_STEP_UP_PROOF_INVALID',
      '审计正文缺少 step-up proof',
    )
    const ownedAuditAfter = await auditDetail(auditContext, ownedNotice.runId)
    const readCountAfter = arrayField(ownedAuditAfter, 'hashChain')
      .filter((entry) => String(entry.eventType ?? '') === 'AUDIT_CONTENT_READ').length
    expect(readCountAfter).toBe(readCountBefore)
    chains[3] = {
      chain: 'audit-content-break-glass',
      status: 'passed',
      evidence: {
        runId: ownedNotice.runId,
        shortReason: { status: 400, errorCode: 'AI_AUDIT_REASON_REQUIRED' },
        missingProof: { status: 403, errorCode: 'AI_STEP_UP_PROOF_INVALID' },
        auditContentReadEventsBefore: readCountBefore,
        auditContentReadEventsAfter: readCountAfter,
      },
    }

    const readonlyPrincipal = await provisionPrincipal(adminContext, adminCsrf, {
      suffix,
      rolePrefix: 'S5_RISK_READ',
      usernamePrefix: 's5-risk',
      displayName: 'Stage 5 风险只读用户',
      password: `S5-Risk!${randomUUID()}aA1`,
      permissions: ['ai:risk:read', 'repair:read'],
    })
    const readonlyContext = await browser.newContext({
      baseURL: visualBaseURL,
      viewport: { width: 390, height: 844 },
      reducedMotion: 'reduce',
    })
    contexts.push(readonlyContext)
    await login(readonlyContext, readonlyPrincipal)
    const readonlyPage = await readonlyContext.newPage()
    observeNetwork(readonlyPage, uiNetwork)
    await readonlyPage.goto('/ai/risks', { waitUntil: 'domcontentloaded' })
    await readonlyPage.locator('.admin-content').waitFor({ state: 'visible' })
    await expect(readonlyPage.locator('[data-testid="risk-readonly-note"]')).toBeVisible({ timeout: 30_000 })
    await expect(readonlyPage.locator('[data-testid="risk-action-dock"]')).toHaveCount(0)
    await delay(250)
    const readonlyWrites = uiNetwork.aiRequests
      .filter((request) => (
        request.method === 'POST'
        && /^\/api\/ai\/risk-cases\/[^/]+\/(acknowledge|resolve|dismiss)$/.test(request.path)
      ))
      .map((request) => `${request.method} ${request.path}`)
    expect(readonlyWrites).toEqual([])
    expect(uiNetwork.businessWrites, 'Stage 5 只读治理 UI 不得触发非 AI 业务写接口').toEqual([])
    expect(uiNetwork.failedApiResponses, 'Stage 5 只读治理 UI 不得出现 HTTP 4xx/5xx').toEqual([])
    expect(uiNetwork.requestFailures, 'Stage 5 只读治理 UI 不得出现 API 网络失败').toEqual([])

    const readonlyCsrf = await csrfToken(readonlyContext)
    const beforeReadonlyWrite = await apiData<JsonObject>(
      await readonlyContext.request.get(`/api/ai/risk-cases/${encodeURIComponent(riskId)}`),
      200,
      '只读用户读取风险详情',
    )
    await expectApiError(
      await readonlyContext.request.post(`/api/ai/risk-cases/${encodeURIComponent(riskId)}/resolve`, {
        headers: commandHeaders(readonlyCsrf, '/ai/risks', randomUUID()),
        data: {
          caseVersion: Number(beforeReadonlyWrite.caseVersion ?? 0),
          detail: '只读用户不得提交风险处置',
        },
      }),
      403,
      'AI_PERMISSION_DENIED',
      '只读风险用户直写',
    )
    const afterReadonlyWrite = await apiData<JsonObject>(
      await readonlyContext.request.get(`/api/ai/risk-cases/${encodeURIComponent(riskId)}`),
      200,
      '只读用户读取拒绝后的风险详情',
    )
    const readonlyBefore = {
      state: beforeReadonlyWrite.state,
      caseVersion: beforeReadonlyWrite.caseVersion,
      events: Array.isArray(beforeReadonlyWrite.events) ? beforeReadonlyWrite.events.length : -1,
    }
    const readonlyAfter = {
      state: afterReadonlyWrite.state,
      caseVersion: afterReadonlyWrite.caseVersion,
      events: Array.isArray(afterReadonlyWrite.events) ? afterReadonlyWrite.events.length : -1,
    }
    expect(readonlyAfter).toEqual(readonlyBefore)
    chains[4] = {
      chain: 'risk-readonly-boundary',
      status: 'passed',
      evidence: {
        riskId,
        roleCode: readonlyPrincipal.roleCode,
        permissions: readonlyPrincipal.permissionCodes,
        uiWriteRequests: readonlyWrites,
        uiBusinessWrites: uiNetwork.businessWrites,
        uiFailedApiResponses: uiNetwork.failedApiResponses,
        uiRequestFailures: uiNetwork.requestFailures,
        directWrite: { status: 403, errorCode: 'AI_PERMISSION_DENIED' },
        before: readonlyBefore,
        after: readonlyAfter,
      },
    }
  } catch (error) {
    failure = error
    const pending = chains.find((chain) => chain.status === 'pending')
    if (pending) {
      pending.status = 'failed'
      pending.evidence = { error: sanitizeDiagnostic(error) }
    }
  } finally {
    for (const context of contexts.reverse()) await context.close().catch(() => undefined)
    const manifest = {
      generatedAt: new Date().toISOString(),
      startedAt: startedAt.toISOString(),
      status: failure ? 'failed' : 'passed',
      isolation: {
        database: stage5Isolation.connection.database,
        hostname: stage5Isolation.connection.hostname,
        serverAddress: stage5Isolation.backendEnv.SERVER_ADDRESS,
        redisDatabase: stage5Isolation.redis.database,
        redisKeyPrefix: stage5Isolation.redis.keyPrefix,
        dashboardCacheKey: stage5Isolation.redis.dashboardCacheKey,
        aiWriteExecutionEnabled: false,
      },
      chains,
      aiRequests: uiNetwork.aiRequests,
      aiResponses: uiNetwork.apiResponses.filter((response) => response.path.startsWith('/api/ai/')),
      businessWrites: uiNetwork.businessWrites,
      failedApiResponses: uiNetwork.failedApiResponses,
      requestFailures: uiNetwork.requestFailures,
      sourceBindings: sourceBindings(),
      failure: failure ? sanitizeDiagnostic(failure) : null,
    }
    writeFileSync(securityManifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8')
  }

  if (failure) throw failure
  expect(chains.every((chain) => chain.status === 'passed')).toBe(true)
})
