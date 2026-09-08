import type {
  AiAuditCost,
  AiAuditRun,
  AiAuditRunContent,
  AiAuditRunDetail,
  AiAuditStep,
  AiProposalPreview,
  AiProposalState,
  AiRiskCase,
  AiRiskSeverity,
  AiRiskState,
} from '../src/types/ai'

export const stage5ReferenceInstant = '2026-08-02T10:30:00+08:00'
export const stage5AsOf = stage5ReferenceInstant

const millisecondsPerDay = 24 * 60 * 60 * 1000
const asiaShanghaiOffsetMilliseconds = 8 * 60 * 60 * 1000
const stage5InstantAtDayOffset = (days: number) => new Date(
  Date.parse(stage5ReferenceInstant) + days * millisecondsPerDay + asiaShanghaiOffsetMilliseconds,
).toISOString().replace(/Z$/, '+08:00')
const stage5ExpiredProposalInstant = stage5InstantAtDayOffset(-3)
const stage5ActiveProposalExpiryInstant = stage5InstantAtDayOffset(10)

const hash64 = (value: number) => value.toString(16).padStart(64, '0')

const severitySignals: Record<AiRiskSeverity, AiRiskCase['signalEvidence']['severity']> = {
  low: 'LOW',
  medium: 'MEDIUM',
  high: 'HIGH',
}

const riskStates = ['open', 'acknowledged', 'resolved', 'dismissed'] as const satisfies readonly AiRiskState[]
const riskSeverities = ['high', 'medium', 'low'] as const satisfies readonly AiRiskSeverity[]
const riskMonthDistributions = [
  { month: '2026-07', counts: [32, 18, 27, 21] },
  { month: '2026-06', counts: [18, 24, 19, 25] },
  { month: '2026-05', counts: [13, 16, 15, 14] },
  { month: '2026-04', counts: [17, 13, 15, 16] },
  { month: '2026-03', counts: [14, 11, 13, 15] },
  { month: '2026-02', counts: [12, 10, 14, 12] },
] as const

const riskTemplates = [
  {
    type: '入住风险',
    riskType: 'long-pending-operation',
    subjectType: 'CHECK_IN_APPLICATION',
    ruleVersion: 'long-pending-operation.v1',
    summary: '入住申请达到人工复核阈值',
    facts: { ageHours: 28, thresholdHours: 24 },
  },
  {
    type: '维修风险',
    riskType: 'repair-backlog',
    subjectType: 'REPAIR_ORDER',
    ruleVersion: 'repair-backlog.v1',
    summary: '维修工单超过规则处置阈值',
    facts: { ageHours: 54, thresholdHours: 48 },
  },
  {
    type: '卫生风险',
    riskType: 'failed-hygiene-check',
    subjectType: 'DORMITORY',
    ruleVersion: 'failed-hygiene-check.v1',
    summary: '卫生检查结果需要人工复核',
    facts: { checkCount: 2, latestScore: 58 },
  },
  {
    type: '欠费风险',
    riskType: 'overdue-payment',
    subjectType: 'PAYMENT',
    ruleVersion: 'overdue-payment.v1',
    summary: '账单状态超过规则核验阈值',
    facts: { ageDays: 5, thresholdDays: 3 },
  },
] as const satisfies ReadonlyArray<{
  type: AiRiskCase['type']
  riskType: string
  subjectType: AiRiskCase['businessSnapshot']['subjectType']
  ruleVersion: string
  summary: string
  facts: Record<string, string | number | boolean>
}>

function riskDisposition(state: AiRiskState) {
  if (state === 'open') return null
  return {
    acknowledged: { type: 'ACKNOWLEDGED', detail: 'review_token_started' },
    resolved: { type: 'RESOLVED', detail: 'review_token_resolved' },
    dismissed: { type: 'DISMISSED', detail: 'review_token_dismissed' },
  }[state]
}

function createRiskCase(month: string, monthIndex: number, templateIndex: number, instanceIndex: number, ordinal: number): AiRiskCase {
  const template = riskTemplates[templateIndex]!
  const paddedOrdinal = String(ordinal).padStart(3, '0')
  const state = monthIndex === 0
    ? templateIndex === 0 || templateIndex === 2
      ? riskStates[instanceIndex % 2]!
      : riskStates[2 + (instanceIndex % 2)]!
    : riskStates[(monthIndex + templateIndex + instanceIndex) % riskStates.length]!
  const severity = riskSeverities[(monthIndex + templateIndex) % riskSeverities.length]!
  const observedDay = Math.max(1, 27 - templateIndex * 5 - (instanceIndex % 5))
  const observedAt = `${month}-${String(observedDay).padStart(2, '0')}T${String(9 + (instanceIndex % 8)).padStart(2, '0')}:15:00+08:00`
  const subjectToken = `subject_token_stage5_${paddedOrdinal}`
  const assigneeUserId = state === 'open' ? null : 900_000 + ordinal
  const dueAt = state === 'open' || state === 'acknowledged'
    ? `${month}-${String(28 - templateIndex * 5).padStart(2, '0')}T18:00:00+08:00`
    : null
  const explanationBasis = ordinal % 2 === 0 ? 'model' : 'deterministic_degraded'
  const degraded = explanationBasis === 'deterministic_degraded'
  const explanation = degraded
    ? '规则证据已命中；解释服务降级，需按 business_snapshot_token 人工核验。'
    : '受控模型仅基于当前规则证据排序，最终结论由 reviewer_token 人工确认。'
  const events: AiRiskCase['events'] = [{
    id: `risk_event_token_${paddedOrdinal}_opened`,
    type: 'OPENED',
    actor: 'rule_engine_token_stage5',
    detail: 'deterministic_rule_token_matched',
    occurredAt: observedAt,
  }]
  const disposition = riskDisposition(state)
  if (disposition) {
    events.push({
      id: `risk_event_token_${paddedOrdinal}_${state}`,
      ...disposition,
      actor: `reviewer_token_${paddedOrdinal}`,
      occurredAt: observedAt,
    })
  }

  return {
    id: `risk_case_token_${paddedOrdinal}`,
    caseVersion: events.length,
    subjectToken,
    type: template.type,
    severity,
    ruleVersion: template.ruleVersion,
    evidenceSummary: `${template.summary}：${subjectToken}`,
    explanation,
    confidence: degraded ? undefined : 0.76 + (ordinal % 5) * 0.04,
    assignee: assigneeUserId === null ? 'assignee_token_unassigned' : `assignee_token_${paddedOrdinal}`,
    sla: 'sla_token_governance_72h',
    state,
    asOf: observedAt,
    events,
    degraded,
    explanationRunId: degraded ? undefined : `run_token_risk_explanation_${paddedOrdinal}`,
    assigneeUserId,
    dueAt,
    signalEvidence: {
      riskType: template.riskType,
      policyVersion: template.ruleVersion,
      severity: severitySignals[severity],
      observedAt,
      facts: { ...template.facts, synthetic: true, fixtureOrdinal: ordinal },
    },
    businessSnapshot: {
      subjectType: template.subjectType,
      subjectToken,
      capturedAt: observedAt,
      facts: { ...template.facts, snapshotToken: `business_snapshot_token_${paddedOrdinal}` },
    },
    explanationEvidence: {
      text: explanation,
      basis: explanationBasis,
      policyVersion: degraded ? 'risk-explanation-deterministic.v1' : 'risk-explanation-model.v1',
      runId: degraded ? undefined : `run_token_risk_explanation_${paddedOrdinal}`,
      confidence: degraded ? undefined : 0.76 + (ordinal % 5) * 0.04,
      degraded,
    },
    humanEvidence: {
      assigneeUserId,
      dueAt,
      events: events.map((event) => ({ ...event })),
    },
  }
}

let riskOrdinal = 0
export const stage5RiskCases: AiRiskCase[] = riskMonthDistributions.flatMap(({ month, counts }, monthIndex) =>
  Array.from({ length: Math.max(...counts) }, (_, instanceIndex) =>
    counts.flatMap((count, templateIndex) => instanceIndex < count
      ? [createRiskCase(month, monthIndex, templateIndex, instanceIndex, ++riskOrdinal)]
      : []),
  ).flat(),
)

const proposalStates = [
  'pending_approval',
  'expired',
  'rejected',
  'approved',
  'executing',
  'succeeded',
  'failed',
  'needs_review',
] as const satisfies readonly AiProposalState[]

const proposalExecutionStates: Record<AiProposalState, NonNullable<AiProposalPreview['executionState']>> = {
  draft: 'pending',
  pending_approval: 'pending',
  approved: 'pending',
  rejected: 'pending',
  stale: 'pending',
  needs_review: 'needs_review',
  expired: 'pending',
  executing: 'running',
  succeeded: 'succeeded',
  failed: 'failed',
  cancelled: 'failed',
}

function createProposal(state: AiProposalState, index: number): AiProposalPreview {
  const ordinal = index + 1
  const paddedOrdinal = String(ordinal).padStart(3, '0')
  const isRepair = index % 2 === 0
  const actionType = isRepair ? 'REPAIR_ASSIGN' : 'NOTICE_CREATE_DRAFT'
  const targetToken = isRepair
    ? `repair_order_token_stage5_${paddedOrdinal}`
    : `notice_scope_token_stage5_${paddedOrdinal}`

  return {
    id: `proposal_token_stage5_${paddedOrdinal}`,
    runId: `run_token_stage5_${paddedOrdinal}`,
    actionType,
    title: isRepair ? `维修指派建议 ${paddedOrdinal}` : `公告草稿建议 ${paddedOrdinal}`,
    target: targetToken,
    currentValue: isRepair ? 'assignee_token_unassigned' : 'draft_token_absent',
    proposedValue: isRepair ? `assignee_token_queue_${paddedOrdinal}` : `notice_draft_token_${paddedOrdinal}`,
    impact: isRepair ? '仅更新受控指派字段，不改变工单状态' : '仅创建纯文本草稿，不发布公告',
    requiredPermission: isRepair
      ? 'ai:approval:review + repair:write + object_scope'
      : 'ai:approval:review + notice:write',
    payloadHash: hash64(1_000 + ordinal),
    businessSnapshotHash: hash64(2_000 + ordinal),
    version: ordinal,
    expiresAt: state === 'expired' ? stage5ExpiredProposalInstant : stage5ActiveProposalExpiryInstant,
    riskLevel: riskSeverities[index % riskSeverities.length]!,
    evidence: {
      basis: 'deterministic',
      confidence: 0.82 + (index % 4) * 0.03,
      asOf: stage5AsOf,
      citations: [{
        id: `citation_token_stage5_${paddedOrdinal}`,
        label: `policy_token_stage5_${paddedOrdinal}`,
        locator: `section_token_${paddedOrdinal}`,
        version: 'v1',
        access: 'available',
      }],
      grounded: true,
    },
    state,
    auditAvailable: true,
    executionState: proposalExecutionStates[state],
    executionId: `execution_token_stage5_${paddedOrdinal}`,
  }
}

export const stage5Proposals: AiProposalPreview[] = proposalStates.map(createProposal)

const auditCapabilities = ['REPAIR', 'NOTICE', 'RISK', 'ASSISTANT', 'KNOWLEDGE', 'DASHBOARD', 'REPAIR', 'NOTICE'] as const
const auditRunStates: AiAuditRun['state'][] = [
  'succeeded',
  'degraded',
  'succeeded',
  'succeeded',
  'running',
  'succeeded',
  'failed',
  'needs_reconciliation',
]
const auditOccurredAt = [
  '2026-08-02T10:22:00+08:00',
  '2026-08-02T09:56:00+08:00',
  '2026-08-01T16:40:00+08:00',
  '2026-08-01T14:18:00+08:00',
  '2026-07-31T11:05:00+08:00',
  '2026-07-30T15:32:00+08:00',
  '2026-07-29T13:47:00+08:00',
  '2026-07-28T08:26:00+08:00',
] as const

function approvalStatus(state: AiProposalState) {
  if (state === 'pending_approval') return 'PENDING_APPROVAL'
  if (state === 'expired') return 'EXPIRED'
  if (state === 'rejected') return 'REJECTED'
  return 'APPROVED'
}

function executionStatus(state: AiProposalState) {
  return {
    draft: 'PENDING',
    pending_approval: 'PENDING',
    approved: 'PENDING',
    rejected: 'SKIPPED',
    stale: 'SKIPPED',
    needs_review: 'NEEDS_REVIEW',
    expired: 'SKIPPED',
    executing: 'EXECUTING',
    succeeded: 'SUCCEEDED',
    failed: 'FAILED',
    cancelled: 'CANCELLED',
  }[state]
}

function resultStatus(state: AiProposalState) {
  if (state === 'succeeded') return 'SUCCEEDED'
  if (state === 'failed') return 'FAILED'
  if (state === 'needs_review') return 'NEEDS_REVIEW'
  if (state === 'executing') return 'RUNNING'
  return 'PENDING'
}

function createAuditSteps(runId: string, proposal: AiProposalPreview, occurredAt: string): AiAuditStep[] {
  const executionState = executionStatus(proposal.state)
  return [
    { id: `${runId}_step_model`, type: 'model', label: '模型', status: 'SUCCEEDED', occurredAt, metadata: { model: 'model_token_stage5' } },
    { id: `${runId}_step_retrieval`, type: 'retrieval', label: '授权检索', status: 'SUCCEEDED', occurredAt, metadata: { citationCount: 1 } },
    { id: `${runId}_step_tool`, type: 'tool', label: '工具调用', status: 'SUCCEEDED', occurredAt, metadata: { tool: proposal.actionType === 'REPAIR_ASSIGN' ? 'repair.propose_assignment.v1' : 'notice.propose_draft.v1' } },
    { id: `${runId}_step_proposal`, type: 'proposal', label: '生成提案', status: 'SUCCEEDED', occurredAt, metadata: { proposalId: proposal.id } },
    { id: `${runId}_step_approval`, type: 'approval', label: '人工审批', status: approvalStatus(proposal.state), occurredAt, metadata: { proposalVersion: proposal.version } },
    { id: `${runId}_step_execution`, type: 'execution', label: '受控执行', status: executionState, occurredAt, metadata: { executionId: proposal.executionId! } },
    { id: `${runId}_step_result`, type: 'result', label: '执行结果', status: resultStatus(proposal.state), occurredAt, metadata: { resultToken: `result_token_${proposal.version}` } },
  ]
}

export const stage5AuditRuns: AiAuditRun[] = stage5Proposals.map((proposal, index) => {
  const occurredAt = auditOccurredAt[index]!
  const runId = proposal.runId!
  const inputTokens = 680 + index * 47
  const outputTokens = 220 + index * 29
  return {
    id: runId,
    parentRunId: index === 7 ? stage5Proposals[6]!.runId! : null,
    capability: auditCapabilities[index]!,
    state: auditRunStates[index]!,
    modelAlias: 'model_token_stage5_governance',
    promptVersion: `governance-prompt.v${index + 1}`,
    citationCount: 1,
    durationMs: 1_240 + index * 315,
    inputTokens,
    outputTokens,
    estimatedCost: Number(((inputTokens + outputTokens) * 0.000_004).toFixed(6)),
    currency: 'CNY',
    chainHash: hash64(3_000 + index),
    occurredAt,
    steps: createAuditSteps(runId, proposal, occurredAt),
  }
})

function createAuditDetail(run: AiAuditRun, proposal: AiProposalPreview, index: number): AiAuditRunDetail {
  const ordinal = index + 1
  const paddedOrdinal = String(ordinal).padStart(3, '0')
  const syntheticActorId = 900_100 + ordinal
  const firstEventHash = hash64(6_000 + index * 2)
  const secondEventHash = hash64(6_001 + index * 2)
  const terminalExecution = ['SUCCEEDED', 'FAILED', 'NEEDS_REVIEW', 'CANCELLED'].includes(executionStatus(proposal.state))

  return {
    run,
    steps: run.steps.map((step) => ({ ...step, metadata: { ...step.metadata } })),
    retrievals: [{
      id: `retrieval_trace_token_${paddedOrdinal}`,
      queryHash: hash64(4_000 + ordinal),
      retrievalPolicyVersion: 'retrieval-policy-token.v1',
      retrievalMode: 'HYBRID',
      indexCode: 'knowledge_index_token_stage5',
      indexVersion: 'index_version_token_v1',
      embeddingModelVersion: 'embedding_model_token_v1',
      topK: 5,
      aclPreFilterCount: 8,
      aclPostFilterCount: 3,
      returnedCount: 1,
      latencyMs: 42 + index,
      state: 'SUCCEEDED',
      occurredAt: run.occurredAt,
    }],
    tools: [{
      id: `tool_call_token_${paddedOrdinal}`,
      sequence: 1,
      toolName: proposal.actionType === 'REPAIR_ASSIGN' ? 'repair.propose_assignment' : 'notice.propose_draft',
      toolVersion: 'v1',
      authorizationDecision: 'ALLOW_TOKEN_SCOPED',
      state: 'SUCCEEDED',
      errorCode: null,
      startedAt: run.occurredAt,
      finishedAt: run.occurredAt,
    }],
    citations: [{
      id: `audit_citation_token_${paddedOrdinal}`,
      citationType: 'KNOWLEDGE',
      documentVersionId: `document_version_token_${paddedOrdinal}`,
      chunkId: `chunk_token_${paddedOrdinal}`,
      metricId: null,
      rank: 1,
      score: 0.91,
      contentHash: hash64(4_500 + ordinal),
      createdAt: run.occurredAt,
    }],
    proposals: [{
      id: proposal.id,
      actionType: proposal.actionType,
      targetType: proposal.actionType === 'REPAIR_ASSIGN' ? 'REPAIR_ORDER' : 'NOTICE_DRAFT',
      payloadHash: proposal.payloadHash,
      businessSnapshotHash: proposal.businessSnapshotHash,
      approvalPolicyVersion: 'approval-policy-token.v1',
      requiredApprovalCount: 1,
      approvedCount: ['approved', 'executing', 'succeeded', 'failed', 'needs_review'].includes(proposal.state) ? 1 : 0,
      riskLevel: proposal.riskLevel.toUpperCase(),
      state: proposal.state.toUpperCase(),
      proposerUserId: syntheticActorId,
      expiresAt: proposal.expiresAt,
      createdAt: run.occurredAt,
    }],
    approvals: [{
      proposalId: proposal.id,
      proposalVersion: proposal.version,
      decision: approvalStatus(proposal.state),
      reviewerUserId: syntheticActorId + 100,
      payloadHash: proposal.payloadHash,
      businessSnapshotHash: proposal.businessSnapshotHash,
      createdAt: run.occurredAt,
    }],
    executions: [{
      id: proposal.executionId!,
      proposalId: proposal.id,
      state: executionStatus(proposal.state),
      version: proposal.version,
      handlerName: proposal.actionType === 'REPAIR_ASSIGN' ? 'RepairAssignmentHandler' : 'NoticeDraftHandler',
      executedByUserId: syntheticActorId + 200,
      reconfirmedByUserId: proposal.state === 'needs_review' ? syntheticActorId + 300 : null,
      resultResourceType: terminalExecution
        ? (proposal.actionType === 'REPAIR_ASSIGN' ? 'REPAIR_ORDER' : 'NOTICE_DRAFT')
        : null,
      errorCode: proposal.state === 'failed' ? 'EXECUTION_TOKEN_FAILED' : proposal.state === 'needs_review' ? 'RESULT_TOKEN_UNCERTAIN' : null,
      startedAt: ['executing', 'succeeded', 'failed', 'needs_review'].includes(proposal.state) ? run.occurredAt : null,
      finishedAt: terminalExecution ? run.occurredAt : null,
    }],
    usage: [{
      requestSequence: 1,
      attempt: 1,
      requestKind: 'GOVERNANCE_PREVIEW',
      actorKind: 'USER_TOKEN',
      actorUserId: syntheticActorId,
      servicePrincipalCode: null,
      initiatedByUserId: syntheticActorId,
      capability: run.capability,
      providerCode: 'provider_token_fake',
      modelName: run.modelAlias,
      inputTokens: run.inputTokens,
      outputTokens: run.outputTokens,
      costAmount: run.estimatedCost,
      currency: run.currency,
      usageSource: 'FIXTURE_TOKEN',
      occurredAt: run.occurredAt,
    }],
    hashChain: [
      {
        id: `hash_event_token_${paddedOrdinal}_01`,
        chainScope: 'AI_RUN',
        aggregateType: 'AI_RUN',
        aggregatePublicId: run.id,
        sequence: 1,
        eventType: 'RUN_ACCEPTED',
        actorKind: 'USER_TOKEN',
        actorUserId: syntheticActorId,
        servicePrincipalCode: null,
        initiatedByUserId: syntheticActorId,
        effectiveSubjectUserId: null,
        payloadHash: hash64(5_000 + index * 2),
        previousEventHash: null,
        eventHash: firstEventHash,
        integrityAlgorithm: 'HMAC-SHA256',
        integrityKeyVersion: 1,
        canonicalizationVersion: 'canonical-json-token.v1',
        correlationId: `correlation_token_${paddedOrdinal}`,
        occurredAt: run.occurredAt,
      },
      {
        id: `hash_event_token_${paddedOrdinal}_02`,
        chainScope: 'AI_RUN',
        aggregateType: 'AI_RUN',
        aggregatePublicId: run.id,
        sequence: 2,
        eventType: 'PROPOSAL_RECORDED',
        actorKind: 'SERVICE_TOKEN',
        actorUserId: null,
        servicePrincipalCode: 'service_principal_token_stage5',
        initiatedByUserId: syntheticActorId,
        effectiveSubjectUserId: null,
        payloadHash: hash64(5_001 + index * 2),
        previousEventHash: firstEventHash,
        eventHash: secondEventHash,
        integrityAlgorithm: 'HMAC-SHA256',
        integrityKeyVersion: 1,
        canonicalizationVersion: 'canonical-json-token.v1',
        correlationId: `correlation_token_${paddedOrdinal}`,
        occurredAt: run.occurredAt,
      },
    ],
  }
}

export const stage5AuditDetails: Record<string, AiAuditRunDetail> = Object.fromEntries(
  stage5AuditRuns.map((run, index) => [run.id, createAuditDetail(run, stage5Proposals[index]!, index)]),
)

export const stage5AuditCosts: AiAuditCost[] = stage5AuditRuns.map((run, index) => ({
  id: `cost_token_stage5_${String(index + 1).padStart(3, '0')}`,
  capability: run.capability,
  providerAlias: 'provider_token_fake',
  inputTokens: run.inputTokens,
  outputTokens: run.outputTokens,
  estimatedCost: run.estimatedCost,
  currency: run.currency,
  occurredAt: run.occurredAt,
}))

export const stage5AuditContent: AiAuditRunContent = {
  runId: stage5AuditRuns[0]!.id,
  messages: [
    {
      id: 'content_message_token_stage5_001',
      role: 'USER',
      content: '请对 repair_order_token_stage5_001 生成受控治理预览。',
      classification: 'L1',
      createdAt: '2026-08-02T10:21:30+08:00',
    },
    {
      id: 'content_message_token_stage5_002',
      role: 'ASSISTANT',
      content: '已基于 policy_token_stage5_001 生成 proposal_token_stage5_001，等待人工审批。',
      classification: 'L1',
      createdAt: '2026-08-02T10:22:00+08:00',
    },
  ],
}
