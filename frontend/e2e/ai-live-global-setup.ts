import { spawnSync } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { request } from '@playwright/test'
import { setTimeout as delay } from 'node:timers/promises'
import {
  sanitizeDiagnostic,
  visualBackendEnv,
  visualBackendURL,
  visualBaseURL,
  requireVisualCredentials,
} from './live-visual-settings'
import { requireAiLiveIsolation } from './ai-live-isolation'
import {
  AI_LIVE_RUNTIME_SWITCH_CONFLICT_QUERY,
  assertNoConflictingRuntimeSwitches,
} from './ai-live-runtime-switch-preflight'
import {
  AI_LIVE_UNSIGNED_TOOL_FACT_QUERY,
  assertNoUnsignedHistoricalToolFacts,
} from './ai-live-audit-preflight'

const runtimePrompts = [
  'assistant.system',
  'dashboard.system',
  'knowledge.system',
  'notice.system',
  'repair.system',
  'risk.system',
] as const

const runtimePromptReadinessQuery = `
SELECT COUNT(*)
FROM ai_prompt_version
WHERE prompt_key IN (${runtimePrompts.map((key) => `'${key}'`).join(', ')})
  AND version = 'v1';
`

function seedSql() {
  const promptKeys = runtimePrompts.map((key) => `'${key}'`).join(', ')
  return `
START TRANSACTION;
UPDATE ai_document_version v
JOIN ai_document d ON d.id = v.document_id
JOIN ai_knowledge_source s ON s.id = d.source_id
SET v.status = 'RETIRED', v.updated_at = CURRENT_TIMESTAMP
WHERE (
  s.name LIKE 'AI live E2E %'
  OR s.name LIKE '全量视觉知识源 %'
) AND v.status = 'ACTIVE';
UPDATE ai_knowledge_source
SET status = 'PAUSED', updated_at = CURRENT_TIMESTAMP
WHERE (
  name LIKE 'AI live E2E %'
  OR name LIKE '全量视觉知识源 %'
) AND status = 'ACTIVE';
UPDATE ai_prompt_version
SET status = 'DRAFT', active_slot_key = NULL, activated_at = NULL
WHERE prompt_key IN (${promptKeys}) AND active_slot_key IS NOT NULL;
UPDATE ai_prompt_version
SET status = 'ACTIVE', active_slot_key = prompt_key, activated_at = CURRENT_TIMESTAMP
WHERE prompt_key IN (${promptKeys}) AND version = 'v1';

INSERT INTO ai_quota_policy
  (scope_type, scope_key, capability, daily_token_limit, monthly_cost_limit,
   concurrent_run_limit, status, effective_from, created_at, updated_at)
VALUES
  ('ROLE', 'ADMIN', 'ASSISTANT', 1000000000, 1000000, 10, 'ACTIVE',
   '2000-01-01 00:00:00', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
  daily_token_limit = GREATEST(daily_token_limit, 1000000000),
  monthly_cost_limit = GREATEST(monthly_cost_limit, 1000000),
  concurrent_run_limit = GREATEST(concurrent_run_limit, 10),
  status = 'ACTIVE', updated_at = CURRENT_TIMESTAMP;
SET @ai_live_quota_id = (
  SELECT id FROM ai_quota_policy
  WHERE scope_type = 'ROLE' AND scope_key = 'ADMIN' AND capability = 'ASSISTANT'
    AND effective_from = '2000-01-01 00:00:00'
  LIMIT 1
);
INSERT INTO ai_budget_bucket
  (quota_policy_id, scope_type, scope_key, capability, provider_code, period_type,
   period_start, period_end, token_limit, cost_limit, reserved_tokens, committed_tokens,
   reserved_cost, committed_cost, currency, version, created_at, updated_at)
VALUES
  (@ai_live_quota_id, 'ROLE', 'ADMIN', 'ASSISTANT', 'fake', 'TEST',
   '2000-01-01 00:00:00', '2037-12-31 23:59:59', 1000000000, 1000000,
   0, 0, 0, 0, 'CNY', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
  token_limit = GREATEST(token_limit, 1000000000),
  cost_limit = GREATEST(cost_limit, 1000000),
  period_end = '2037-12-31 23:59:59', updated_at = CURRENT_TIMESTAMP;
COMMIT;
`
}

export default async function aiLiveGlobalSetup() {
  const { connection } = requireAiLiveIsolation(visualBackendEnv)
  const mysqlExecutable = process.env.AI_LIVE_MYSQL_EXECUTABLE || 'mysql'
  const mysqlArgs = [
    '--protocol=tcp',
    `--host=${connection.hostname}`,
    `--port=${connection.port}`,
    `--user=${connection.username}`,
    `--database=${connection.database}`,
    '--default-character-set=utf8mb4',
    '--batch',
    '--skip-column-names',
  ]
  const mysqlOptions = {
    encoding: 'utf8' as const,
    env: { ...process.env, MYSQL_PWD: connection.password },
    windowsHide: true,
    timeout: 30_000,
  }
  let promptsReady = false
  let readinessDetail: unknown = 'prompt bootstrap not observed'
  const readinessDeadline = Date.now() + 30_000
  while (Date.now() < readinessDeadline) {
    const readiness = spawnSync(mysqlExecutable, mysqlArgs, {
      ...mysqlOptions,
      input: runtimePromptReadinessQuery,
    })
    if (!readiness.error && readiness.status === 0
        && Number.parseInt(readiness.stdout.trim(), 10) === runtimePrompts.length) {
      promptsReady = true
      break
    }
    readinessDetail = readiness.error ?? readiness.stderr ?? `mysql exit ${readiness.status}`
    await delay(250)
  }
  if (!promptsReady) {
    throw new Error(`AI live E2E 等待运行 prompt 初始化超时：${sanitizeDiagnostic(readinessDetail)}`)
  }
  const auditCompatibilityCheck = spawnSync(mysqlExecutable, mysqlArgs, {
    ...mysqlOptions,
    input: AI_LIVE_UNSIGNED_TOOL_FACT_QUERY,
  })
  if (auditCompatibilityCheck.error || auditCompatibilityCheck.status !== 0) {
    const detail = auditCompatibilityCheck.error ?? auditCompatibilityCheck.stderr
      ?? `mysql exit ${auditCompatibilityCheck.status}`
    throw new Error(`AI live E2E 审计兼容性检查失败：${sanitizeDiagnostic(detail)}`)
  }
  assertNoUnsignedHistoricalToolFacts(auditCompatibilityCheck.stdout)

  const runtimeSwitchCheck = spawnSync(mysqlExecutable, mysqlArgs, {
    ...mysqlOptions,
    input: AI_LIVE_RUNTIME_SWITCH_CONFLICT_QUERY,
  })
  if (runtimeSwitchCheck.error || runtimeSwitchCheck.status !== 0) {
    const detail = runtimeSwitchCheck.error ?? runtimeSwitchCheck.stderr
      ?? `mysql exit ${runtimeSwitchCheck.status}`
    throw new Error(`AI live E2E Kill Switch 前置检查失败：${sanitizeDiagnostic(detail)}`)
  }
  assertNoConflictingRuntimeSwitches(runtimeSwitchCheck.stdout, sanitizeDiagnostic)

  const result = spawnSync(mysqlExecutable, mysqlArgs, {
    ...mysqlOptions,
    input: seedSql(),
  })
  if (result.error || result.status !== 0) {
    const detail = result.error ?? result.stderr ?? `mysql exit ${result.status}`
    throw new Error(`AI live E2E 控制面初始化失败：${sanitizeDiagnostic(detail)}`)
  }
  // 目录使用真实治理接口激活，沿用标准 manifest、step-up、CAS、审计与 outbox。
  const context = await request.newContext({ baseURL: visualBackendURL,
    extraHTTPHeaders: { Origin: visualBaseURL, Referer: `${visualBaseURL}/` } })
  try {
    const login = await context.post('/api/auth/login', { data: requireVisualCredentials() })
    if (!login.ok()) throw new Error(`ToolCatalog bootstrap login HTTP ${login.status()}`)
    const catalogsResponse = await context.get('/api/ai/tool-catalogs')
    if (!catalogsResponse.ok()) throw new Error(`ToolCatalog list HTTP ${catalogsResponse.status()}`)
    const catalogs = (await catalogsResponse.json()).data as Array<{
      id: string; version: string; manifestHash: string; active: boolean; toolIds: string[]
    }>
    const target = catalogs.find((row) => row.version === 'v2' && row.toolIds.length === 7)
    if (!target) throw new Error('当前标准 v2 ToolCatalog 不可用')
    if (!target.active) {
      const expectedActiveId = catalogs.find((row) => row.active)?.id ?? ''
      const csrf = await context.get('/api/security/csrf')
      if (!csrf.ok()) throw new Error(`ToolCatalog CSRF HTTP ${csrf.status()}`)
      const headers = { 'X-CSRF-Token': (await csrf.json()).data.token as string }
      const requestHash = createHash('sha256').update(JSON.stringify({
        catalogId: target.id, expectedActiveId, manifestHash: target.manifestHash, version: target.version,
      })).digest('hex')
      const proofResponse = await context.post('/api/security/step-up', { headers, data: {
        password: requireVisualCredentials().password, actionCode: 'CONFIG_ACTIVATE',
        resourcePublicId: target.id, requestHash,
      } })
      if (!proofResponse.ok()) throw new Error(`ToolCatalog step-up HTTP ${proofResponse.status()}`)
      const activated = await context.post(`/api/ai/tool-catalogs/${target.id}/activate`, {
        headers: { ...headers, 'Idempotency-Key': randomUUID(),
          'X-Step-Up-Proof': (await proofResponse.json()).data.proof as string },
        data: { version: target.version, manifestHash: target.manifestHash, expectedActiveId },
      })
      if (activated.status() !== 204) throw new Error(`ToolCatalog activation HTTP ${activated.status()}`)
    }
  } finally {
    await context.dispose()
  }
}
