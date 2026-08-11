START TRANSACTION;

UPDATE ai_prompt_version
SET status = 'DRAFT', active_slot_key = NULL, activated_at = NULL
WHERE prompt_key IN (
  'assistant.system', 'dashboard.system', 'knowledge.system',
  'notice.system', 'repair.system', 'risk.system'
) AND active_slot_key IS NOT NULL;

UPDATE ai_prompt_version
SET status = 'ACTIVE', active_slot_key = prompt_key, activated_at = CURRENT_TIMESTAMP
WHERE prompt_key IN (
  'assistant.system', 'dashboard.system', 'knowledge.system',
  'notice.system', 'repair.system', 'risk.system'
) AND version = 'v1';

UPDATE ai_tool_catalog_version
SET status = 'DRAFT', active_slot_key = NULL, activated_at = NULL
WHERE active_slot_key = 'runtime';

INSERT INTO ai_tool_catalog_version
  (version, manifest_text, manifest_hash, status, active_slot_key, activated_at, created_at, updated_at)
VALUES
  (
    'local-demo-v1',
    '{"version":"local-demo-v1","tools":["knowledge.search.v1","dashboard.query_metric.v1","repair.get_context.v1","dormitory.get_capacity_summary.v1","notice.list_published.v1"]}',
    SHA2('{"version":"local-demo-v1","tools":["knowledge.search.v1","dashboard.query_metric.v1","repair.get_context.v1","dormitory.get_capacity_summary.v1","notice.list_published.v1"]}', 256),
    'ACTIVE', 'runtime', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
  )
ON DUPLICATE KEY UPDATE
  manifest_text = VALUES(manifest_text),
  manifest_hash = VALUES(manifest_hash),
  status = 'ACTIVE',
  active_slot_key = 'runtime',
  activated_at = CURRENT_TIMESTAMP,
  updated_at = CURRENT_TIMESTAMP;

INSERT INTO ai_quota_policy
  (scope_type, scope_key, capability, daily_token_limit, monthly_cost_limit,
   concurrent_run_limit, status, effective_from, created_at, updated_at)
VALUES
  ('GLOBAL', '*', 'ASSISTANT', 1000000, 1000, 5, 'ACTIVE',
   '2000-01-01 00:00:00', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
  daily_token_limit = GREATEST(daily_token_limit, 1000000),
  monthly_cost_limit = GREATEST(monthly_cost_limit, 1000),
  concurrent_run_limit = GREATEST(concurrent_run_limit, 5),
  status = 'ACTIVE',
  updated_at = CURRENT_TIMESTAMP;

SET @demo_quota_id = (
  SELECT id FROM ai_quota_policy
  WHERE scope_type = 'GLOBAL' AND scope_key = '*' AND capability = 'ASSISTANT'
    AND effective_from = '2000-01-01 00:00:00'
  LIMIT 1
);

INSERT INTO ai_budget_bucket
  (quota_policy_id, scope_type, scope_key, capability, provider_code, period_type,
   period_start, period_end, token_limit, cost_limit, reserved_tokens, committed_tokens,
   reserved_cost, committed_cost, currency, version, created_at, updated_at)
VALUES
  (@demo_quota_id, 'GLOBAL', '*', 'ASSISTANT', 'fake', 'DEMO',
   '2000-01-01 00:00:00', '2037-12-31 23:59:59', 1000000, 1000,
   0, 0, 0, 0, 'CNY', 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
ON DUPLICATE KEY UPDATE
  token_limit = GREATEST(token_limit, 1000000),
  cost_limit = GREATEST(cost_limit, 1000),
  period_end = '2037-12-31 23:59:59',
  updated_at = CURRENT_TIMESTAMP;

COMMIT;
