export type AiRunState =
  | 'idle' | 'accepted' | 'queued' | 'running' | 'streaming' | 'succeeded'
  | 'degraded' | 'cancelled' | 'failed' | 'timed_out' | 'needs_reconciliation'

export type AiProposalState =
  | 'draft' | 'pending_approval' | 'approved' | 'rejected' | 'stale' | 'needs_review'
  | 'expired' | 'executing' | 'succeeded' | 'failed' | 'cancelled'

export type AiRiskState = 'open' | 'acknowledged' | 'resolved' | 'dismissed'
export type AiRiskSeverity = 'low' | 'medium' | 'high'

export interface AiCitation {
  id: string
  label: string
  locator: string
  version: string
  access: 'available' | 'denied' | 'retired'
}

export interface AiCitationDetail {
  id: string
  type: 'KNOWLEDGE' | 'METRIC'
  sourceId?: string | null
  documentId?: string | null
  documentVersionId?: string | null
  chunkId?: string | null
  metricId?: string | null
  rank: number
  score?: number | null
  quote: string
  locator: string
  contentHash: string
  createdAt: string
}

export interface AiEvidenceMeta {
  basis?: 'deterministic' | 'model' | 'unverified'
  confidence?: number
  asOf: string
  citations: AiCitation[]
  grounded: boolean
}

export interface AiRunEvent<T extends Record<string, unknown> = Record<string, unknown>> {
  eventId?: string
  runId: string
  sequence: number
  type:
    | 'run.accepted' | 'run.queued' | 'run.started' | 'message.delta' | 'citation.added'
    | 'tool.started' | 'tool.completed' | 'proposal.created' | 'usage.updated'
    | 'run.degraded' | 'run.completed' | 'run.failed' | 'run.cancelled' | 'run.timed_out'
    | 'heartbeat' | string
  timestamp: string
  payload: T
}

export interface AiDashboardInput {
  question: string
  intentSchemaVersion?: 'DashboardQueryIntent.v1'
}

export type AiDashboardDatePreset = 'TODAY' | 'LAST_7_DAYS' | 'LAST_30_DAYS'
export type AiDashboardPresentationHint = 'CARD' | 'TABLE' | 'LINE' | 'BAR'

export interface AiDashboardIntent {
  metricIds: string[]
  dateRange: { preset: AiDashboardDatePreset }
  dimensions: Array<'buildingId' | 'repairType'>
  filters: Record<string, string[]>
  presentationHint: AiDashboardPresentationHint
}

export interface AiDashboardMetricValue {
  value: number
  unit: string
  metricVersion: string
  definition: string
  rows: Array<{ dimensions: Record<string, string>; value: number }>
}

export interface AiDashboardQueryParameters {
  dateRange: { preset: AiDashboardDatePreset; from: string; to: string }
  dimensions: Array<'buildingId' | 'repairType'>
  filters: Record<string, string[]>
}

export interface AiDashboardInsight {
  id: string
  summary: string
  metricVersion: string
  riskCounts: Array<{ type: string; count: number; severity: AiRiskSeverity }>
  pendingApprovals: number
  evidence: AiEvidenceMeta
  state: AiRunState
  intent: AiDashboardIntent
  metrics: Record<string, AiDashboardMetricValue>
  queryParameters: AiDashboardQueryParameters
  intentSource?: string
  modelUsed?: boolean
}

export interface AiRepairCandidate {
  userId: number
  displayName: string
}

export interface AiRepairTriageInput {
  repairId: number
  status: string
  descriptionRedacted: string
  candidates: AiRepairCandidate[]
}

export interface AiRepairTriageResult {
  repairId: number
  category: string
  urgency: 'LOW' | 'MEDIUM' | 'HIGH'
  recommendedTeam: string
  missingInformation: string[]
  reasoningSummary: string
  assignmentCandidateUserId: number | null
  assignmentCandidateName: string | null
  slaSuggestion: string
  evidence: AiEvidenceMeta
  state: AiRunState
  proposalId?: string
  proposalState?: AiProposalState
  /** 服务端提案过期时间；前端仅用于失效提示，审批仍由服务端重验。 */
  proposalExpiresAt?: string
}

export interface AiNoticeDraftInput {
  points: string
  type: string
  tone: '正式' | '温和' | '紧急'
  audience: string
}

export interface AiNoticeDraftResult {
  title: string
  type: string
  publisher: string
  status: '草稿'
  content: string
  blocked: boolean
  safetyMessages: string[]
  version: string
  evidence: AiEvidenceMeta
  state: AiRunState
  proposalId?: string
  proposalState?: AiProposalState
  /** 服务端提案过期时间；前端仅用于失效提示，审批仍由服务端重验。 */
  proposalExpiresAt?: string
}

export interface AiConversationInput {
  text: string
  surface: string
  contextType?: string
  contextId?: number
  conversationId?: string
  onConversationReady?: (conversationId: string) => void
}

export interface AiConversationSummary {
  id: string
  surface: string
  contextType: string
  contextId?: number | null
  status: string
  title?: string | null
  lastMessageAt?: string | null
  createdAt: string
}

export interface AiPersistedMessage {
  id: string
  role: 'USER' | 'ASSISTANT'
  text: string
  classification: string
  createdAt: string
  citations: AiCitation[]
  grounded?: boolean
  asOf?: string
  runId?: string
  runState?: AiRunState
}

export interface AiConversationDetail {
  conversation: AiConversationSummary
  messages: AiPersistedMessage[]
}

export interface AiConversationMessage {
  id: string
  role: 'user' | 'assistant'
  text: string
  citations: AiCitation[]
  classification?: string
  createdAt?: string
  confidence?: number
  grounded?: boolean
  asOf?: string
  runId?: string
  parentRunId?: string
  runState?: AiRunState
  failureMessage?: string
  degradedReason?: string
}

export interface AiFeedbackInput {
  rating: -1 | 1
  tags?: string[]
  comment?: string
}

export interface AiFeedbackStatus {
  rating: -1 | 1
  state: 'submitting' | 'succeeded' | 'failed'
  message?: string
}

export interface AiAssistantRun {
  id: string
  parentRunId?: string
  state: AiRunState
  failureCode?: string
}

export interface AiRiskEvent {
  id: string
  type: string
  actor: string
  detail: string
  occurredAt: string
}

export type AiRiskFact = string | number | boolean

export interface AiRiskSignalEvidence {
  riskType: string
  policyVersion: string
  severity: 'LOW' | 'MEDIUM' | 'HIGH' | 'CRITICAL'
  observedAt: string
  facts: Record<string, AiRiskFact>
}

export interface AiRiskBusinessSnapshot {
  subjectType: 'REPAIR_ORDER' | 'DORMITORY' | 'CHECK_IN_APPLICATION' | 'PAYMENT' | 'OPERATION_TASK'
  subjectToken: string
  capturedAt: string
  facts: Record<string, AiRiskFact>
}

export interface AiRiskExplanationEvidence {
  text: string
  basis: 'model' | 'deterministic_degraded'
  policyVersion: string
  runId?: string
  confidence?: number
  degraded: boolean
}

export interface AiRiskHumanEvidence {
  assigneeUserId: number | null
  dueAt: string | null
  events: AiRiskEvent[]
}

export interface AiRiskCase {
  id: string
  caseVersion: number
  subjectToken: string
  type: '入住风险' | '维修风险' | '卫生风险' | '欠费风险'
  severity: AiRiskSeverity
  ruleVersion: string
  evidenceSummary: string
  explanation: string
  confidence?: number
  assignee: string
  sla: string
  state: AiRiskState
  asOf: string
  events: AiRiskEvent[]
  degraded?: boolean
  explanationRunId?: string
  assigneeUserId: number | null
  dueAt: string | null
  signalEvidence: AiRiskSignalEvidence
  businessSnapshot: AiRiskBusinessSnapshot
  explanationEvidence: AiRiskExplanationEvidence
  humanEvidence: AiRiskHumanEvidence
}

export interface AiRiskDispositionInput {
  caseVersion: number
  detail: string
  dueAt?: string
}

export interface AiProposalPreview {
  id: string
  runId?: string
  actionType: 'REPAIR_ASSIGN' | 'NOTICE_CREATE_DRAFT'
  title: string
  target: string
  currentValue: string
  proposedValue: string
  impact: string
  requiredPermission: string
  payloadHash: string
  businessSnapshotHash: string
  version: number
  expiresAt: string
  riskLevel: AiRiskSeverity
  evidence: AiEvidenceMeta
  state: AiProposalState
  auditAvailable: boolean
  executionState?: 'pending' | 'running' | 'succeeded' | 'failed' | 'needs_review'
  executionId?: string
}

export type AiExecutionReconfirmResolution =
  | 'RESULT_CONFIRMED' | 'PROVEN_NOT_EXECUTED' | 'CONFLICT' | 'UNKNOWN'

export interface AiExecutionReconfirmInput {
  version: number
  payloadHash: string
  businessSnapshotHash: string
  resolution: AiExecutionReconfirmResolution
  comment: string
  password: string
}

export interface AiExecutionReconfirmResult {
  proposalId: string
  executionId: string
  state: 'SUCCEEDED' | 'FAILED' | 'NEEDS_REVIEW' | 'EXECUTING'
  version: number
  resultHash: string | null
}

export interface AiAuditStep {
  id: string
  type: string
  label: string
  status: string
  occurredAt: string
  metadata: Record<string, string | number>
}

export interface AiAuditRun {
  costStatus?: string | null
  id: string
  parentRunId?: string | null
  capability: string
  state: AiRunState
  modelAlias: string
  promptVersion: string
  citationCount: number
  durationMs: number
  inputTokens: number
  outputTokens: number
  estimatedCost: number
  currency: string
  chainHash: string | null
  occurredAt: string
  steps: AiAuditStep[]
}

export interface AiAuditRunQuery {
  costStatus?: 'RESERVED' | 'ESTIMATED' | 'FINAL' | 'RELEASED' | 'UNKNOWN' | 'NEEDS_RECONCILIATION'
  page?: number
  pageSize?: number
  from?: string
  to?: string
  capability?: 'ASSISTANT' | 'KNOWLEDGE' | 'DASHBOARD' | 'REPAIR' | 'NOTICE' | 'RISK' | 'EVALUATION'
  state?: 'ACCEPTED' | 'QUEUED' | 'RUNNING' | 'STREAMING' | 'SUCCEEDED' | 'DEGRADED' | 'FAILED' | 'TIMED_OUT' | 'CANCELLED' | 'NEEDS_RECONCILIATION'
  provider?: string
}

export interface AiAuditRunDetail {
  run: AiAuditRun
  steps: AiAuditStep[]
  retrievals: AiAuditRetrievalTrace[]
  tools: AiAuditToolCall[]
  citations: AiAuditCitation[]
  proposals: AiAuditProposal[]
  approvals: AiAuditApproval[]
  executions: AiAuditExecution[]
  usage: AiAuditUsage[]
  hashChain: AiAuditHashEvent[]
}

export interface AiAuditToolCall {
  id: string
  sequence: number
  toolName: string
  toolVersion: string
  authorizationDecision: string
  state: string
  errorCode: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface AiAuditCitation {
  id: string
  citationType: string
  documentVersionId: string | null
  chunkId: string | null
  metricId: string | null
  rank: number
  score: number | null
  contentHash: string
  createdAt: string
}

export interface AiAuditProposal {
  id: string
  actionType: string
  targetType: string
  payloadHash: string
  businessSnapshotHash: string
  approvalPolicyVersion: string
  requiredApprovalCount: number
  approvedCount: number
  riskLevel: string
  state: string
  proposerUserId: number
  expiresAt: string
  createdAt: string
}

export interface AiAuditApproval {
  proposalId: string
  proposalVersion: number
  decision: string
  reviewerUserId: number
  payloadHash: string
  businessSnapshotHash: string
  createdAt: string
}

export interface AiAuditExecution {
  id: string
  proposalId: string
  state: string
  version: number
  handlerName: string
  executedByUserId: number
  reconfirmedByUserId: number | null
  resultResourceType: string | null
  errorCode: string | null
  startedAt: string | null
  finishedAt: string | null
}

export interface AiAuditUsage {
  requestSequence: number
  attempt: number
  requestKind: string
  actorKind: string
  actorUserId: number | null
  servicePrincipalCode: string | null
  initiatedByUserId: number | null
  capability: string
  providerCode: string
  modelName: string
  inputTokens: number
  outputTokens: number
  costAmount: number
  currency: string
  usageSource: string
  occurredAt: string
}

export interface AiAuditHashEvent {
  id: string
  chainScope: string
  aggregateType: string
  aggregatePublicId: string
  sequence: number
  eventType: string
  actorKind: string
  actorUserId: number | null
  servicePrincipalCode: string | null
  initiatedByUserId: number | null
  effectiveSubjectUserId: number | null
  payloadHash: string
  previousEventHash: string | null
  eventHash: string
  integrityAlgorithm: string
  integrityKeyVersion: number
  canonicalizationVersion: string
  correlationId: string
  occurredAt: string
}

export interface AiAuditRetrievalTrace {
  id: string
  queryHash: string
  retrievalPolicyVersion: string
  retrievalMode: string
  indexCode: string
  indexVersion: string
  embeddingModelVersion: string
  topK: number
  aclPreFilterCount: number
  aclPostFilterCount: number
  returnedCount: number
  latencyMs: number
  state: string
  occurredAt: string
}

export interface AiAuditContentMessage {
  id: string
  role: string
  content: string
  classification: string
  createdAt: string
}

export interface AiAuditRunContent {
  runId: string
  messages: AiAuditContentMessage[]
}

export interface AiAuditCost {
  id: string
  capability: string
  providerAlias: string
  inputTokens: number
  outputTokens: number
  estimatedCost: number
  currency: string
  occurredAt: string
}

export interface AiKnowledgeSource {
  id: string
  name: string
  sourceType: string
  ownerUserId: number
  classification: 'L0' | 'L1' | 'L2'
  matchMode: 'ANY' | 'ALL'
  aclVersion: number
  status: string
  permissions: string[]
}

export interface AiKnowledgeUpload {
  id: string
  state: string
  mimeType: 'text/plain'
  sizeBytes: number
  sha256: string
  expiresAt: string
  uploadTarget: string
}

export interface AiKnowledgeVersion {
  id: string
  documentId: string
  sourceId: string
  version: string
  contentHash: string
  visibility: string
  status: string
  sizeBytes: number
  aclVersion: number
  approvalSnapshotHash?: string
}

export interface AiKnowledgeJob {
  id: string
  versionId: string
  state: string
  attempt: number
  errorCode?: string
  availableAt?: string
  startedAt?: string
  finishedAt?: string
}

export interface AiPage<T> {
  records: T[]
  total: number
  page: number
  pageSize: number
}
