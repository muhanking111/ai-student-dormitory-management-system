export const AI_LIVE_RUNTIME_SWITCH_CONFLICT_QUERY = `
SELECT CONCAT(scope_type, ':', scope_key)
FROM ai_runtime_switch
WHERE disabled = TRUE
  AND (
    (scope_type = 'MASTER' AND scope_key = '*')
    OR (scope_type = 'CAPABILITY' AND scope_key IN ('ASSISTANT', 'DASHBOARD', 'KNOWLEDGE', 'NOTICE'))
    OR (scope_type = 'PROVIDER' AND scope_key = 'fake')
  )
ORDER BY scope_type, scope_key;
`

export function assertNoConflictingRuntimeSwitches(
  rawOutput: string,
  sanitize: (value: unknown) => string = String,
) {
  const conflicts = rawOutput.split(/\r?\n/)
    .map((value) => value.trim())
    .filter(Boolean)
  if (conflicts.length === 0) return

  throw new Error(
    `AI live E2E 检测到会阻断本用例的持久 Kill Switch：${sanitize(conflicts.join(', '))}；`
    + '套件不会自动清除持久 Kill Switch，请改用隔离测试库或按审批流程恢复后重试。',
  )
}
