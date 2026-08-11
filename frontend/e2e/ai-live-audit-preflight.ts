export const AI_LIVE_UNSIGNED_TOOL_FACT_QUERY = `
SELECT COUNT(*)
FROM ai_tool_call t
JOIN ai_run r ON r.id = t.run_id
LEFT JOIN (
  SELECT aggregate_public_id, correlation_id, COUNT(*) AS signature_count
  FROM ai_audit_event
  WHERE chain_scope = 'RUN'
    AND aggregate_type = 'RUN'
    AND event_type = 'TOOL_CALL_RECORDED'
  GROUP BY aggregate_public_id, correlation_id
) signatures ON signatures.aggregate_public_id = r.public_id
  AND signatures.correlation_id = t.public_id
WHERE signatures.signature_count IS NULL OR signatures.signature_count <> 1;
`

export function assertNoUnsignedHistoricalToolFacts(rawOutput: string) {
  const value = rawOutput.trim()
  if (!/^\d+$/.test(value)) {
    throw new Error('AI live E2E 审计兼容性查询结果不合法，已拒绝继续运行。')
  }

  const count = Number.parseInt(value, 10)
  if (count === 0) return

  throw new Error(
    `AI live E2E 专用库包含 ${count} 条旧版未签名工具事实；`
    + '套件不会自动删除或伪造回填审计证据，请保留旧库并改用新的专用 *_e2e 数据库。',
  )
}
