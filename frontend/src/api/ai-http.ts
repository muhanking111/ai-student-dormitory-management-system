import type { AiClient, AiProposalQuery } from './ai'
import { ApiError, apiRequest } from './client'
import { openAiEventStream } from './ai-sse'
import { visualEvidenceMode } from '../utils/visual-evidence-mode'
import type {
  AiAuditCost,
  AiAuditRunContent,
  AiAuditRunDetail,
  AiAuditRun,
  AiAuditRunQuery,
  AiAuditRetrievalTrace,
  AiAuditToolCall,
  AiAuditCitation,
  AiAuditProposal,
  AiAuditApproval,
  AiAuditExecution,
  AiAuditUsage,
  AiAuditHashEvent,
  AiCitationDetail,
  AiConversationDetail,
  AiFeedbackInput,
  AiPersistedMessage,
  AiConversationSummary,
  AiConversationInput,
  AiDashboardInput,
  AiDashboardIntent,
  AiDashboardInsight,
  AiDashboardMetricValue,
  AiDashboardQueryParameters,
  AiEvidenceMeta,
  AiNoticeDraftInput,
  AiNoticeDraftResult,
  AiPage,
  AiProposalPreview,
  AiExecutionReconfirmInput,
  AiExecutionReconfirmResult,
  AiRepairTriageInput,
  AiRepairTriageResult,
  AiRiskCase,
  AiRiskDispositionInput,
  AiRunEvent,
  AiKnowledgeJob,
  AiKnowledgeSource,
  AiKnowledgeUpload,
  AiKnowledgeVersion,
} from '../types/ai'

interface AiRunAcceptedResponse {
  runId: string
  eventsUrl: string
}

class AiRunTerminalError extends Error {}

type JsonRecord = Record<string, unknown>

function object(value: unknown, label: string): JsonRecord {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`${label}响应合同不合法`)
  return value as JsonRecord
}

function string(value: unknown, label: string, allowEmpty = false) {
  if (typeof value !== 'string' || (!allowEmpty && !value.trim())) throw new Error(`${label}响应合同不合法`)
  return value
}

function number(value: unknown, label: string) {
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new Error(`${label}响应合同不合法`)
  return value
}

function nullableNumber(value: unknown, label: string) {
  return value == null ? null : number(value, label)
}

function array(value: unknown, label: string): unknown[] {
  if (!Array.isArray(value)) throw new Error(`${label}响应合同不合法`)
  return value
}

const riskTypes = new Set(['入住风险', '维修风险', '卫生风险', '欠费风险'])
const riskSeverities = new Set(['low', 'medium', 'high'])
const riskStates = new Set(['open', 'acknowledged', 'resolved', 'dismissed'])
const riskSignalSeverities = new Set(['LOW', 'MEDIUM', 'HIGH', 'CRITICAL'])
const riskSubjectTypes = new Set(['REPAIR_ORDER', 'DORMITORY', 'CHECK_IN_APPLICATION', 'PAYMENT', 'OPERATION_TASK'])
const riskExplanationBases = new Set(['model', 'deterministic_degraded'])

function riskFacts(value: unknown, label: string) {
  const raw = object(value, label)
  if (!Object.keys(raw).length || Object.values(raw).some((fact) => !['string', 'number', 'boolean'].includes(typeof fact))) {
    throw new Error(`${label}响应合同不合法`)
  }
  return raw as Record<string, string | number | boolean>
}

function riskEvent(value: unknown) {
  const raw = object(value, 'Risk event ')
  return {
    id: string(raw.id, 'Risk event '),
    type: string(raw.type, 'Risk event '),
    actor: string(raw.actor, 'Risk event '),
    detail: string(raw.detail, 'Risk event ', true),
    occurredAt: string(raw.occurredAt, 'Risk event '),
  }
}

function nullablePositiveInteger(value: unknown, label: string): number | null {
  if (value == null) return null
  const parsed = number(value, label)
  if (!Number.isSafeInteger(parsed) || parsed < 1) throw new Error(`${label}响应合同不合法`)
  return parsed
}

function nullableRiskString(value: unknown, label: string) {
  return value == null ? undefined : string(value, label)
}

function mapRiskCase(value: unknown): AiRiskCase {
  const raw = object(value, 'Risk case ')
  const layers = object(raw.evidenceLayers, 'Risk evidence layers ')
  const signal = object(layers.signal, 'Risk signal ')
  const signalSeverity = string(signal.severity, 'Risk signal ')
  if (!riskSignalSeverities.has(signalSeverity)) throw new Error('Risk signal 响应合同不合法')
  const signalEvidence = {
    riskType: string(signal.riskType, 'Risk signal '),
    policyVersion: string(signal.policyVersion, 'Risk signal '),
    severity: signalSeverity as AiRiskCase['signalEvidence']['severity'],
    observedAt: string(signal.observedAt, 'Risk signal '),
    facts: riskFacts(signal.facts, 'Risk signal facts '),
  }
  const business = object(layers.businessSnapshot, 'Risk business snapshot ')
  const subjectType = string(business.subjectType, 'Risk business snapshot ')
  if (!riskSubjectTypes.has(subjectType)) throw new Error('Risk business snapshot 响应合同不合法')
  const businessSnapshot = {
    subjectType: subjectType as AiRiskCase['businessSnapshot']['subjectType'],
    subjectToken: string(business.subjectToken, 'Risk business snapshot '),
    capturedAt: string(business.capturedAt, 'Risk business snapshot '),
    facts: riskFacts(business.facts, 'Risk business facts '),
  }
  const explanation = object(layers.explanation, 'Risk explanation ')
  const basis = string(explanation.basis, 'Risk explanation ')
  if (!riskExplanationBases.has(basis) || explanation.confidence != null) {
    throw new Error('Risk explanation 响应合同不合法')
  }
  const runId = nullableRiskString(explanation.runId, 'Risk explanation ')
  const degraded = explanation.degraded
  if (typeof degraded !== 'boolean'
    || (basis === 'model' && (!runId || degraded))
    || (basis === 'deterministic_degraded' && (!degraded || runId))) {
    throw new Error('Risk explanation 响应合同不合法')
  }
  const explanationEvidence = {
    text: string(explanation.text, 'Risk explanation '),
    basis: basis as AiRiskCase['explanationEvidence']['basis'],
    policyVersion: string(explanation.policyVersion, 'Risk explanation '),
    ...(runId ? { runId } : {}),
    confidence: undefined,
    degraded,
  }
  const human = object(layers.human, 'Risk human evidence ')
  const assigneeUserId = nullablePositiveInteger(human.assigneeUserId, 'Risk human evidence ')
  const dueAt = human.dueAt == null ? null : string(human.dueAt, 'Risk human evidence ')
  const events = array(human.events, 'Risk human evidence ').map(riskEvent)
  const type = string(raw.type, 'Risk case ')
  const severity = string(raw.severity, 'Risk case ')
  const state = string(raw.state, 'Risk case ')
  const caseVersion = number(raw.caseVersion, 'Risk case ')
  if (!riskTypes.has(type) || !riskSeverities.has(severity) || !riskStates.has(state)
    || !Number.isSafeInteger(caseVersion) || caseVersion < 1
    || string(raw.subjectToken, 'Risk case ') !== businessSnapshot.subjectToken
    || raw.confidence != null) throw new Error('Risk case 响应合同不合法')
  const topRunId = nullableRiskString(raw.explanationRunId, 'Risk case ')
  if (topRunId !== runId || nullablePositiveInteger(raw.assigneeUserId, 'Risk case ') !== assigneeUserId
    || (raw.dueAt == null ? null : string(raw.dueAt, 'Risk case ')) !== dueAt) {
    throw new Error('Risk case 响应合同不合法')
  }
  return {
    id: string(raw.id, 'Risk case '), caseVersion,
    subjectToken: businessSnapshot.subjectToken,
    type: type as AiRiskCase['type'], severity: severity as AiRiskCase['severity'],
    ruleVersion: string(raw.ruleVersion, 'Risk case '),
    evidenceSummary: string(raw.evidenceSummary, 'Risk case '),
    explanation: explanationEvidence.text, confidence: undefined,
    assignee: string(raw.assignee, 'Risk case '), sla: string(raw.sla, 'Risk case '),
    state: state as AiRiskCase['state'], asOf: string(raw.asOf, 'Risk case '), events,
    degraded, ...(runId ? { explanationRunId: runId } : {}), assigneeUserId, dueAt,
    signalEvidence, businessSnapshot, explanationEvidence,
    humanEvidence: { assigneeUserId, dueAt, events },
  }
}

function proposalIdentity(value: unknown) {
  if (value == null) return {}
  const proposal = object(value, 'Proposal ')
  const id = string(proposal.publicId, 'Proposal ')
  const rawState = typeof proposal.state === 'string' ? proposal.state.toLowerCase() : ''
  const states = new Set(['draft', 'pending_approval', 'approved', 'rejected', 'stale', 'needs_review', 'expired', 'executing', 'succeeded', 'failed', 'cancelled'])
  if (rawState && !states.has(rawState)) throw new Error('Proposal 响应合同不合法')
  const expiresAt = proposal.expiresAt == null ? undefined : string(proposal.expiresAt, 'Proposal ')
  return {
    proposalId: id,
    ...(rawState ? { proposalState: rawState as AiRepairTriageResult['proposalState'] } : {}),
    ...(expiresAt ? { proposalExpiresAt: expiresAt } : {}),
  }
}

function proposalEvidence(value: unknown, label: string): AiEvidenceMeta | undefined {
  if (value == null) return undefined
  const proposal = object(value, `${label} proposal `)
  if (proposal.preview == null) return undefined
  const preview = object(proposal.preview, `${label} preview `)
  const asOf = preview.asOf == null ? '' : string(preview.asOf, `${label} preview `, true)
  const basisValue = preview.evidenceBasis == null ? 'unverified' : string(preview.evidenceBasis, `${label} preview `).toLowerCase()
  const basis = basisValue === 'deterministic' || basisValue === 'model' || basisValue === 'unverified'
    ? basisValue as AiEvidenceMeta['basis'] : 'unverified'
  const confidence = preview.confidence == null ? undefined : number(preview.confidence, `${label} preview `)
  if (confidence !== undefined && (confidence < 0 || confidence > 1)) throw new Error(`${label} preview 响应合同不合法`)
  const citations = array(preview.citations ?? [], `${label} preview `).map((item, index) => {
    const citation = object(item, `${label} preview citation ${index} `)
    const locator = string(citation.sourceRef, `${label} preview citation ${index} `)
    const citationLabel = string(citation.label, `${label} preview citation ${index} `)
    const contentHash = citation.contentHash == null ? '' : string(citation.contentHash, `${label} preview citation ${index} `, true)
    return {
      id: `${locator}#${index + 1}`,
      label: citationLabel,
      locator,
      version: contentHash ? contentHash.slice(0, 12) : 'proposal-preview.v1',
      access: 'available' as const,
    }
  })
  return { ...(basis ? { basis } : {}), ...(confidence === undefined ? {} : { confidence }), asOf, citations, grounded: Boolean(asOf && citations.length) }
}

function mergeProposalEvidence(
  direct: AiEvidenceMeta | undefined,
  proposal: unknown,
  label: string,
): AiEvidenceMeta {
  const fromProposal = proposalEvidence(proposal, label)
  if (!direct) return fromProposal ?? { asOf: '', citations: [], grounded: false, basis: 'unverified' }
  if (!fromProposal) return direct
  const asOf = direct.asOf || fromProposal.asOf
  const citations = direct.citations.length ? direct.citations : fromProposal.citations
  const directHasEvidence = Boolean(direct.asOf || direct.citations.length)
  return {
    basis: direct.basis ?? fromProposal.basis,
    ...(direct.confidence !== undefined
      ? { confidence: direct.confidence }
      : fromProposal.confidence !== undefined ? { confidence: fromProposal.confidence } : {}),
    asOf,
    citations,
    grounded: (directHasEvidence ? direct.grounded : fromProposal.grounded) && Boolean(asOf && citations.length),
  }
}

function evidence(value: unknown, label: string) {
  const raw = object(value, `${label} `)
  return {
    ...(raw.confidence === undefined ? {} : { confidence: number(raw.confidence, `${label} `) }),
    asOf: string(raw.asOf, `${label} `, true),
    citations: array(raw.citations, `${label} `) as AiDashboardInsight['evidence']['citations'],
    grounded: typeof raw.grounded === 'boolean' ? raw.grounded : (() => { throw new Error(`${label}响应合同不合法`) })(),
  }
}

const dashboardMetricIds = new Set([
  'dormitory.total', 'student.checked-in.count', 'bed.available.count',
  'repair.pending.count', 'hygiene.failed.count', 'payment.unpaid.count',
])
const dashboardDatePresets = new Set(['TODAY', 'LAST_7_DAYS', 'LAST_30_DAYS'])
const dashboardDimensions = new Set(['buildingId', 'repairType'])
const dashboardPresentations = new Set(['CARD', 'TABLE', 'LINE', 'BAR'])

function dashboardStringArray(value: unknown, label: string) {
  return array(value, label).map((item) => string(item, label))
}

function dashboardFilters(value: unknown) {
  const raw = object(value, 'Dashboard filters ')
  const result: Record<string, string[]> = {}
  Object.entries(raw).forEach(([key, values]) => {
    if (!['buildingIds', 'repairTypes'].includes(key)) throw new Error('Dashboard 响应合同不合法')
    result[key] = dashboardStringArray(values, 'Dashboard filters ')
  })
  return result
}

function dashboardIntent(value: unknown): AiDashboardIntent {
  const raw = object(value, 'Dashboard intent ')
  const metricIds = dashboardStringArray(raw.metricIds, 'Dashboard intent ')
  const dateRange = object(raw.dateRange, 'Dashboard intent ')
  const preset = string(dateRange.preset, 'Dashboard intent ')
  const dimensions = dashboardStringArray(raw.dimensions, 'Dashboard intent ')
  const presentationHint = string(raw.presentationHint, 'Dashboard intent ')
  if (!metricIds.length || metricIds.length > 6 || new Set(metricIds).size !== metricIds.length
    || metricIds.some((id) => !dashboardMetricIds.has(id))
    || !dashboardDatePresets.has(preset)
    || dimensions.some((dimension) => !dashboardDimensions.has(dimension))
    || !dashboardPresentations.has(presentationHint)) throw new Error('Dashboard 响应合同不合法')
  return {
    metricIds,
    dateRange: { preset: preset as AiDashboardIntent['dateRange']['preset'] },
    dimensions: dimensions as AiDashboardIntent['dimensions'],
    filters: dashboardFilters(raw.filters),
    presentationHint: presentationHint as AiDashboardIntent['presentationHint'],
  }
}

function dashboardMetrics(value: unknown, intent: AiDashboardIntent) {
  const raw = object(value, 'Dashboard metrics ')
  if (Object.keys(raw).length !== intent.metricIds.length
    || intent.metricIds.some((id) => !Object.prototype.hasOwnProperty.call(raw, id))) {
    throw new Error('Dashboard 响应合同不合法')
  }
  const metrics: Record<string, AiDashboardMetricValue> = {}
  Object.entries(raw).forEach(([metricId, value]) => {
    if (!intent.metricIds.includes(metricId)) throw new Error('Dashboard 响应合同不合法')
    const metric = object(value, 'Dashboard metric ')
    const rows = array(metric.rows, 'Dashboard metric ').map((rowValue) => {
      const row = object(rowValue, 'Dashboard metric row ')
      const dimensions = object(row.dimensions, 'Dashboard metric row ')
      if (Object.keys(dimensions).some((key) => !intent.dimensions.includes(key as never))) {
        throw new Error('Dashboard 响应合同不合法')
      }
      return {
        dimensions: Object.fromEntries(Object.entries(dimensions)
          .map(([key, dimension]) => [key, string(dimension, 'Dashboard metric row ')])),
        value: number(row.value, 'Dashboard metric row '),
      }
    })
    metrics[metricId] = {
      value: number(metric.value, 'Dashboard metric '),
      unit: string(metric.unit, 'Dashboard metric '),
      metricVersion: string(metric.metricVersion, 'Dashboard metric '),
      definition: string(metric.definition, 'Dashboard metric '),
      rows,
    }
  })
  return metrics
}

function dashboardQueryParameters(value: unknown, intent: AiDashboardIntent): AiDashboardQueryParameters {
  const raw = object(value, 'Dashboard parameters ')
  const dateRange = object(raw.dateRange, 'Dashboard parameters ')
  const preset = string(dateRange.preset, 'Dashboard parameters ')
  const dimensions = dashboardStringArray(raw.dimensions, 'Dashboard parameters ')
  const filters = dashboardFilters(raw.filters)
  if (preset !== intent.dateRange.preset
    || JSON.stringify(dimensions) !== JSON.stringify(intent.dimensions)
    || JSON.stringify(filters) !== JSON.stringify(intent.filters)) throw new Error('Dashboard 响应合同不合法')
  return {
    dateRange: {
      preset: preset as AiDashboardQueryParameters['dateRange']['preset'],
      from: string(dateRange.from, 'Dashboard parameters '),
      to: string(dateRange.to, 'Dashboard parameters '),
    },
    dimensions: dimensions as AiDashboardQueryParameters['dimensions'],
    filters,
  }
}

function dashboardDataCitations(value: unknown, metrics: Record<string, AiDashboardMetricValue>) {
  const seen = new Set<string>()
  const citations = array(value, 'Dashboard citations ').map((citationValue) => {
    const citation = object(citationValue, 'Dashboard citation ')
    const metricId = string(citation.metricId, 'Dashboard citation ')
    const version = string(citation.version, 'Dashboard citation ')
    if (!metrics[metricId] || metrics[metricId].metricVersion !== version
      || string(citation.locator, 'Dashboard citation ') !== metricId
      || citation.access !== 'available' || seen.has(metricId)) throw new Error('Dashboard 响应合同不合法')
    seen.add(metricId)
    string(citation.asOf, 'Dashboard citation ')
    return {
      id: string(citation.id, 'Dashboard citation '),
      label: string(citation.label, 'Dashboard citation '),
      locator: metricId,
      version,
      access: 'available' as const,
    }
  })
  if (seen.size !== Object.keys(metrics).length) throw new Error('Dashboard 响应合同不合法')
  return citations
}

function mapDashboardResult(value: unknown, runId: string): AiDashboardInsight {
  const raw = object(value, 'Dashboard ')
  if ('summary' in raw) {
    const intent = dashboardIntent(raw.intent)
    return {
      id: string(raw.id, 'Dashboard '), summary: string(raw.summary, 'Dashboard '),
      metricVersion: string(raw.metricVersion, 'Dashboard '),
      riskCounts: array(raw.riskCounts, 'Dashboard ') as AiDashboardInsight['riskCounts'],
      pendingApprovals: number(raw.pendingApprovals, 'Dashboard '),
      evidence: evidence(raw.evidence, 'Dashboard evidence '),
      state: string(raw.state, 'Dashboard ') as AiDashboardInsight['state'],
      intent,
      metrics: dashboardMetrics(raw.metrics, intent),
      queryParameters: dashboardQueryParameters(raw.queryParameters, intent),
      ...(typeof raw.intentSource === 'string' ? { intentSource: raw.intentSource } : {}),
      ...(typeof raw.modelUsed === 'boolean' ? { modelUsed: raw.modelUsed } : {}),
    }
  }
  const result = object(raw.result, 'Dashboard ')
  if (string(raw.intentSchemaVersion, 'Dashboard ') !== 'DashboardQueryIntent.v1') {
    throw new Error('Dashboard 响应合同不合法')
  }
  const intent = dashboardIntent(raw.intent)
  const metrics = dashboardMetrics(result.metrics, intent)
  const queryParameters = dashboardQueryParameters(result.queryParameters, intent)
  const citations = dashboardDataCitations(result.dataCitations, metrics)
  const riskMetric: Record<string, { type: string; severity: 'medium' | 'high' }> = {
    'repair.pending.count': { type: '维修风险', severity: 'high' },
    'hygiene.failed.count': { type: '卫生风险', severity: 'medium' },
    'payment.unpaid.count': { type: '欠费风险', severity: 'high' },
  }
  const riskCounts = Object.entries(metrics).flatMap(([id, metricValue]) => {
    const mapped = riskMetric[id]
    if (!mapped) return []
    return [{ ...mapped, count: metricValue.value }]
  })
  return {
    id: runId,
    summary: string(raw.explanation, 'Dashboard '),
    metricVersion: string(result.catalogVersion, 'Dashboard '),
    riskCounts,
    pendingApprovals: 0,
    evidence: { asOf: string(result.asOf, 'Dashboard '), citations, grounded: true },
    state: raw.degraded === true ? 'degraded' : 'succeeded',
    intent,
    metrics,
    queryParameters,
    intentSource: string(raw.intentSource, 'Dashboard '),
    modelUsed: typeof raw.modelUsed === 'boolean' ? raw.modelUsed : (() => { throw new Error('Dashboard 响应合同不合法') })(),
  }
}

function mapRepairResult(value: unknown): AiRepairTriageResult {
  const raw = object(value, '维修分诊 ')
  if ('evidence' in raw) {
    const directEvidence = evidence(raw.evidence, '维修分诊 evidence ')
    return {
      ...(raw as unknown as AiRepairTriageResult),
      evidence: mergeProposalEvidence(directEvidence, raw.proposal, '维修分诊'),
      ...proposalIdentity(raw.proposal),
    }
  }
  const degraded = raw.degraded === true
  return {
    repairId: number(raw.repairOrderId, '维修分诊 '),
    category: string(raw.category, '维修分诊 '),
    urgency: string(raw.urgency, '维修分诊 ') as AiRepairTriageResult['urgency'],
    recommendedTeam: string(raw.recommendedTeam, '维修分诊 '),
    missingInformation: array(raw.missingInformation, '维修分诊 ').map((item) => string(item, '维修分诊 ')),
    reasoningSummary: string(raw.reasoningSummary, '维修分诊 '),
    assignmentCandidateUserId: raw.assignmentCandidateUserId == null ? null : number(raw.assignmentCandidateUserId, '维修分诊 '),
    assignmentCandidateName: raw.assignmentCandidateName == null ? null : string(raw.assignmentCandidateName, '维修分诊 '),
    slaSuggestion: string(raw.slaSuggestion, '维修分诊 '),
    evidence: mergeProposalEvidence(
      { asOf: string(raw.asOf, '维修分诊 ', true), citations: [], grounded: !degraded, basis: degraded ? 'unverified' : 'deterministic' },
      raw.proposal,
      '维修分诊',
    ),
    state: degraded ? 'degraded' : 'succeeded',
    ...proposalIdentity(raw.proposal),
  }
}

function mapNoticeResult(value: unknown): AiNoticeDraftResult {
  const raw = object(value, '公告草稿 ')
  if ('evidence' in raw) {
    const directEvidence = evidence(raw.evidence, '公告草稿 evidence ')
    return {
      ...(raw as unknown as AiNoticeDraftResult),
      evidence: mergeProposalEvidence(directEvidence, raw.proposal, '公告草稿'),
      ...proposalIdentity(raw.proposal),
    }
  }
  const blocked = raw.blocked === true
  const proposal = proposalIdentity(raw.proposal)
  return {
    title: string(raw.title, '公告草稿 ', blocked),
    type: string(raw.type, '公告草稿 '),
    publisher: string(raw.publisher, '公告草稿 '),
    status: string(raw.status, '公告草稿 ') as '草稿',
    content: string(raw.content, '公告草稿 ', blocked),
    blocked,
    safetyMessages: array(raw.safetyMessages, '公告草稿 ').map((item) => string(item, '公告草稿 ')),
    version: string(raw.version, '公告草稿 '),
    evidence: mergeProposalEvidence(
      { asOf: '', citations: [], grounded: false, basis: blocked ? 'unverified' : 'deterministic' },
      raw.proposal,
      '公告草稿',
    ),
    state: blocked ? 'failed' : 'succeeded',
    ...proposal,
  }
}

function mapPage<T>(value: unknown, label: string, mapper: (item: unknown) => T): AiPage<T> {
  const raw = object(value, `${label} `)
  return {
    records: array(raw.records, `${label} `).map(mapper),
    total: number(raw.total, `${label} `),
    page: number(raw.page, `${label} `),
    pageSize: number(raw.pageSize, `${label} `),
  }
}

function nullableString(value: unknown, label: string) {
  return value == null ? null : string(value, label, true)
}

function mapConversationSummary(value: unknown): AiConversationSummary {
  const raw = object(value, 'AI conversation ')
  const contextId = raw.contextId == null ? null : number(raw.contextId, 'AI conversation ')
  if (contextId != null && (!Number.isSafeInteger(contextId) || contextId < 1)) {
    throw new Error('AI conversation 响应合同不合法')
  }
  return {
    id: string(raw.id, 'AI conversation '),
    surface: string(raw.surface, 'AI conversation '),
    contextType: string(raw.contextType, 'AI conversation '),
    contextId,
    status: string(raw.status, 'AI conversation '),
    title: nullableString(raw.title, 'AI conversation '),
    lastMessageAt: nullableString(raw.lastMessageAt, 'AI conversation '),
    createdAt: string(raw.createdAt, 'AI conversation '),
  }
}

function mapPersistedMessage(value: unknown): AiPersistedMessage {
  const raw = object(value, 'AI message ')
  const role = string(raw.role, 'AI message ')
  if (role !== 'USER' && role !== 'ASSISTANT') throw new Error('AI message 响应合同不合法')
  const citations = raw.citations == null ? [] : array(raw.citations, 'AI message citations ').map((item) => {
    const citation = object(item, 'AI message citation ')
    const access = citation.access == null ? 'available' : string(citation.access, 'AI message citation ')
    if (!['available', 'denied', 'retired'].includes(access)) throw new Error('AI message citation 响应合同不合法')
    return {
      id: string(citation.id, 'AI message citation '),
      label: string(citation.label, 'AI message citation '),
      locator: string(citation.locator, 'AI message citation '),
      version: citation.version == null ? '' : string(citation.version, 'AI message citation ', true),
      access: access as AiPersistedMessage['citations'][number]['access'],
    }
  })
  if (raw.grounded != null && typeof raw.grounded !== 'boolean') throw new Error('AI message 响应合同不合法')
  const runState = raw.runState == null ? undefined : string(raw.runState, 'AI message ').toLowerCase()
  if (runState && !['accepted', 'queued', 'running', 'streaming', 'succeeded', 'degraded', 'cancelled', 'failed', 'timed_out', 'needs_reconciliation'].includes(runState)) {
    throw new Error('AI message 响应合同不合法')
  }
  return {
    id: string(raw.id, 'AI message '),
    role,
    text: string(raw.text, 'AI message ', true),
    classification: string(raw.classification, 'AI message '),
    createdAt: string(raw.createdAt, 'AI message '),
    citations,
    ...(typeof raw.grounded === 'boolean' ? { grounded: raw.grounded } : {}),
    ...(typeof raw.asOf === 'string' ? { asOf: raw.asOf } : {}),
    ...(typeof raw.runId === 'string' ? { runId: raw.runId } : {}),
    ...(runState ? { runState: runState as AiPersistedMessage['runState'] } : {}),
  }
}

function mapConversationDetail(value: unknown): AiConversationDetail {
  const raw = object(value, 'AI conversation detail ')
  return {
    conversation: mapConversationSummary(raw),
    messages: array(raw.messages, 'AI conversation detail ').map(mapPersistedMessage),
  }
}

function requireUuid(value: string, label: string) {
  const normalized = value.trim()
  if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(normalized)) {
    throw new Error(`${label}不合法`)
  }
  return normalized
}

function idempotencyKey() {
  return globalThis.crypto?.randomUUID?.() ?? `ai-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function sha256(value: string) {
  const bytes = new TextEncoder().encode(value)
  const digest = await globalThis.crypto.subtle.digest('SHA-256', bytes)
  return [...new Uint8Array(digest)].map((byte) => byte.toString(16).padStart(2, '0')).join('')
}

function abortError() {
  return new DOMException('The operation was aborted', 'AbortError')
}

function isAbort(error: unknown, signal?: AbortSignal) {
  return signal?.aborted || (error instanceof DOMException && error.name === 'AbortError')
}

function queryString(input: object = {}) {
  const params = new URLSearchParams()
  Object.entries(input).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value))
  })
  const value = params.toString()
  return value ? `?${value}` : ''
}

async function requestWithNetworkRetry<T>(
  path: string,
  init: RequestInit,
  signal?: AbortSignal,
): Promise<T> {
  for (let attempt = 0; attempt < 2; attempt += 1) {
    if (signal?.aborted) throw abortError()
    try {
      return await apiRequest<T>(path, init)
    } catch (error) {
      if (isAbort(error, signal)) throw abortError()
      if (error instanceof ApiError || attempt === 1) throw error
    }
  }
  throw new Error('AI 请求重试失败')
}

function command<T>(path: string, body: unknown, method = 'POST', signal?: AbortSignal) {
  const key = idempotencyKey()
  const init: RequestInit = {
    method,
    headers: { 'Idempotency-Key': key },
    body: JSON.stringify(body),
    signal,
  }
  return requestWithNetworkRetry<T>(path, init, signal)
}

async function* resilientRunEvents(
  accepted: AiRunAcceptedResponse,
  signal?: AbortSignal,
): AsyncGenerator<AiRunEvent> {
  let lastEventId: string | undefined
  let lastSequence = 0
  for (let attempt = 0; attempt < 2; attempt += 1) {
    try {
      for await (const event of openAiEventStream(accepted.eventsUrl, {
        signal,
        lastEventId,
        lastSequence,
        onEventId: (id) => { lastEventId = id },
      })) {
        lastSequence = event.sequence
        yield event
      }
      return
    } catch (error) {
      if (isAbort(error, signal)) throw abortError()
      if (error instanceof AiRunTerminalError || attempt === 1) throw error
    }
  }
}

const visualEvidenceTerminalHoldMs = 30_000

async function holdCompletedConversationForVisualEvidence(
  observedPartialText: boolean,
  observedCitation: boolean,
  signal?: AbortSignal,
) {
  if (!visualEvidenceMode || !observedPartialText || !observedCitation) return
  await new Promise<void>((resolve, reject) => {
    if (signal?.aborted) {
      reject(abortError())
      return
    }
    const timer = globalThis.setTimeout(() => {
      signal?.removeEventListener('abort', onAbort)
      resolve()
    }, visualEvidenceTerminalHoldMs)
    const onAbort = () => {
      globalThis.clearTimeout(timer)
      reject(abortError())
    }
    signal?.addEventListener('abort', onAbort, { once: true })
  })
}

function terminalMessage(event: AiRunEvent) {
  const safeMessage = event.payload.safeMessage
  const errorCode = event.payload.errorCode
  if (typeof safeMessage === 'string' && safeMessage.trim()) return safeMessage
  if (typeof errorCode === 'string' && errorCode.trim()) return `AI 运行失败（${errorCode}）`
  return 'AI 运行失败'
}

async function executeRunCommand<T>(
  path: string,
  body: unknown,
  mapResult: (value: unknown, runId: string) => T,
  signal?: AbortSignal,
): Promise<T> {
  const accepted = await command<AiRunAcceptedResponse>(path, body, 'POST', signal)
  if (!accepted?.runId || !accepted.eventsUrl) throw new Error('AI command 未返回有效运行地址')
  for await (const event of resilientRunEvents(accepted, signal)) {
    if (event.type === 'run.failed') throw new AiRunTerminalError(terminalMessage(event))
    if (event.type === 'run.timed_out') {
      const safeMessage = typeof event.payload.safeMessage === 'string'
        ? event.payload.safeMessage.trim() : ''
      throw new AiRunTerminalError(safeMessage || 'AI 运行超时，请稍后重试')
    }
    if (event.type === 'run.cancelled') throw new AiRunTerminalError('AI 运行已取消')
    if (event.type === 'run.completed') {
      if (!Object.prototype.hasOwnProperty.call(event.payload, 'result')) {
        throw new AiRunTerminalError('AI 完成事件缺少结果')
      }
      return mapResult(event.payload.result, accepted.runId)
    }
  }
  throw new Error('AI 事件流未返回终态')
}

export class HttpAiClient implements AiClient {
  queryDashboard(input: AiDashboardInput, signal?: AbortSignal) {
    return executeRunCommand<AiDashboardInsight>('/api/ai/dashboard/queries', {
      question: input.question,
      intentSchemaVersion: input.intentSchemaVersion ?? 'DashboardQueryIntent.v1',
    }, mapDashboardResult, signal)
  }

  async *startConversation(input: AiConversationInput, signal?: AbortSignal) {
    const conversation = input.conversationId
      ? { id: input.conversationId }
      : await command<{ id: string }>('/api/ai/conversations', {
        surface: input.surface,
        contextType: input.contextType,
        contextId: input.contextId,
      }, 'POST', signal)
    input.onConversationReady?.(conversation.id)
    const requestId = idempotencyKey()
    const requestInit: RequestInit = {
      method: 'POST',
      headers: { 'Idempotency-Key': requestId },
      body: JSON.stringify({ text: input.text, clientRequestId: requestId }),
      signal,
    }
    let run: AiRunAcceptedResponse | undefined
    let terminal = false
    let observedPartialText = false
    let observedCitation = false
    try {
      run = await requestWithNetworkRetry<AiRunAcceptedResponse>(
        `/api/ai/conversations/${conversation.id}/messages`, requestInit, signal,
      )
      for await (const event of resilientRunEvents(run, signal)) {
        if (event.type === 'message.delta' && String(event.payload.textDelta ?? '').trim()) {
          observedPartialText = true
        }
        if (event.type === 'citation.added') observedCitation = true
        if (event.type === 'run.completed') {
          await holdCompletedConversationForVisualEvidence(observedPartialText, observedCitation, signal)
        }
        if (['run.completed', 'run.failed', 'run.cancelled', 'run.timed_out'].includes(event.type)) terminal = true
        yield event
      }
    } finally {
      if (run && !terminal) {
        // AbortSignal 已终止，cancel 必须使用新的请求上下文通知服务端释放 provider/tool 资源。
        await this.cancelRun(run.runId).catch(() => undefined)
      }
    }
  }

  listConversations(input: { page?: number; pageSize?: number } = {}) {
    return apiRequest<unknown>(`/api/ai/conversations${queryString(input)}`)
      .then((value) => mapPage(value, 'AI conversation page', mapConversationSummary))
  }

  async getConversation(id: string) {
    const value = await apiRequest<unknown>(`/api/ai/conversations/${encodeURIComponent(id)}`)
    return mapConversationDetail(value)
  }

  submitFeedback(messageId: string, input: AiFeedbackInput) {
    const id = requireUuid(messageId, 'AI message ID ')
    if (input.rating !== -1 && input.rating !== 1) return Promise.reject(new Error('反馈 rating 不合法'))
    const tags = input.tags?.map((tag) => tag.trim())
    if ((tags?.length ?? 0) > 10 || tags?.some((tag) => !/^[a-zA-Z0-9_-]{1,32}$/.test(tag))) {
      return Promise.reject(new Error('反馈标签不合法'))
    }
    const comment = input.comment?.trim()
    if ((comment?.length ?? 0) > 500) return Promise.reject(new Error('反馈说明不能超过 500 字'))
    return apiRequest<void>(`/api/ai/messages/${encodeURIComponent(id)}/feedback`, {
      method: 'POST',
      body: JSON.stringify({ rating: input.rating, ...(tags ? { tags } : {}), ...(comment ? { comment } : {}) }),
    })
  }

  cancelRun(id: string) {
    return apiRequest<void>(`/api/ai/runs/${encodeURIComponent(id)}/cancel`, { method: 'POST' })
  }

  async *retryRun(id: string, signal?: AbortSignal): AsyncGenerator<AiRunEvent> {
    const key = idempotencyKey()
    let run: AiRunAcceptedResponse | undefined
    let terminal = false
    try {
      run = await requestWithNetworkRetry<AiRunAcceptedResponse>(
        `/api/ai/runs/${encodeURIComponent(id)}/retry`,
        { method: 'POST', headers: { 'Idempotency-Key': key }, signal },
        signal,
      )
      if (!run?.runId || !run.eventsUrl) throw new Error('AI retry 未返回有效运行地址')
      for await (const event of resilientRunEvents(run, signal)) {
        if (['run.completed', 'run.failed', 'run.cancelled', 'run.timed_out'].includes(event.type)) terminal = true
        yield event
      }
    } finally {
      if (run && !terminal) await this.cancelRun(run.runId).catch(() => undefined)
    }
  }

  getCitation(id: string) {
    return apiRequest<AiCitationDetail>(`/api/ai/citations/${encodeURIComponent(id)}`)
  }

  triageRepair(input: AiRepairTriageInput, signal?: AbortSignal) {
    return executeRunCommand<AiRepairTriageResult>(`/api/ai/repairs/${input.repairId}/triage`, {}, mapRepairResult, signal)
  }

  draftNotice(input: AiNoticeDraftInput, signal?: AbortSignal) {
    return executeRunCommand<AiNoticeDraftResult>('/api/ai/notices/drafts', input, mapNoticeResult, signal)
  }

  async listRiskCases(input: { page?: number; pageSize?: number; type?: string; state?: string; keyword?: string } = {}) {
    const raw = object(await apiRequest<unknown>(`/api/ai/risk-cases${queryString(input)}`), 'Risk page ')
    const page = number(raw.page, 'Risk page ')
    const pageSize = number(raw.pageSize, 'Risk page ')
    const total = number(raw.total, 'Risk page ')
    if (![page, pageSize, total].every(Number.isSafeInteger) || page < 1 || pageSize < 1 || total < 0) {
      throw new Error('Risk page 响应合同不合法')
    }
    return { records: array(raw.records, 'Risk page ').map(mapRiskCase), total, page, pageSize }
  }

  async updateRiskCase(
    id: string,
    action: 'acknowledge' | 'resolve' | 'dismiss',
    input: AiRiskDispositionInput,
  ) {
    if (!Number.isSafeInteger(input.caseVersion) || input.caseVersion < 1) {
      throw new Error('风险案例版本无效')
    }
    const detail = input.detail.trim()
    if (!detail) throw new Error('处置说明不能为空')
    if (input.dueAt && Number.isNaN(Date.parse(input.dueAt))) throw new Error('风险截止时间无效')
    await command<void>(`/api/ai/risk-cases/${encodeURIComponent(id)}/${action}`, {
      caseVersion: input.caseVersion,
      detail,
      ...(input.dueAt ? { dueAt: input.dueAt } : {}),
    })
    return mapRiskCase(await apiRequest<unknown>(`/api/ai/risk-cases/${encodeURIComponent(id)}`))
  }

  listProposals(input: AiProposalQuery = {}) {
    return apiRequest<AiPage<AiProposalPreview>>(`/api/ai/proposals${queryString(input)}`)
  }

  getProposal(id: string) {
    return apiRequest<AiProposalPreview>(`/api/ai/proposals/${id}`)
  }

  async approveProposal(id: string, input: {
    version: number
    payloadHash: string
    businessSnapshotHash: string
    comment?: string
    password?: string
  }) {
    const password = input.password ?? ''
    if (!password) throw new Error('批准真实执行前必须重新输入当前密码')
    const approval = {
      version: input.version,
      payloadHash: input.payloadHash,
      businessSnapshotHash: input.businessSnapshotHash,
      comment: input.comment,
    }
    const comment = input.comment?.trim() ?? ''
    const framed = (value: string) => `${value.length}:${value}|`
    const requestHash = await sha256(`proposal-approve.v1|${framed(id)}${framed(String(input.version))}`
      + `${framed(input.payloadHash.toLowerCase())}${framed(input.businessSnapshotHash.toLowerCase())}${framed(comment)}`)
    const issued = await apiRequest<{ proof: string }>('/api/security/step-up', {
      method: 'POST',
      body: JSON.stringify({
        password,
        actionCode: 'PROPOSAL_APPROVE',
        resourcePublicId: id,
        requestHash,
      }),
    })
    if (!issued.proof) throw new Error('step-up 未返回有效单次证明')
    // 单次 proof 消费后不可做透明网络重试；响应丢失时应刷新提案和业务事实后人工对账。
    return apiRequest<AiProposalPreview>(`/api/ai/proposals/${id}/approve`, {
      method: 'POST',
      headers: {
        'Idempotency-Key': idempotencyKey(),
        'X-Step-Up-Proof': issued.proof,
      },
      body: JSON.stringify(approval),
    })
  }

  rejectProposal(id: string, input: { version: number; comment?: string }) {
    return command<void>(`/api/ai/proposals/${id}/reject`, input)
  }

  listAuditRuns(input: AiAuditRunQuery = {}) {
    return apiRequest<unknown>(`/api/ai/audit/runs${queryString(input)}`).then((value) => mapPage(value, '审计列表', mapAuditRun))
  }

  async getAuditRun(id: string): Promise<AiAuditRunDetail> {
    const raw = object(await apiRequest<unknown>(`/api/ai/audit/runs/${encodeURIComponent(id)}`), '审计详情 ')
    const steps = array(raw.steps, '审计详情 ').map(mapAuditStep)
    const retrievals = array(raw.retrievals, '审计详情 ').map(mapAuditRetrieval)
    const tools = array(raw.tools, '审计详情 ').map(mapAuditToolCall)
    const citations = array(raw.citations, '审计详情 ').map(mapAuditCitation)
    const proposals = array(raw.proposals, '审计详情 ').map(mapAuditProposal)
    const approvals = array(raw.approvals, '审计详情 ').map(mapAuditApproval)
    const executions = array(raw.executions, '审计详情 ').map(mapAuditExecution)
    const usage = array(raw.usage, '审计详情 ').map(mapAuditUsage)
    const hashChain = array(raw.hashChain, '审计详情 ').map(mapAuditHashEvent)
    const retrievalSteps = retrievals.map((trace) => ({
      id: `retrieval-${trace.id}`,
      type: 'retrieval',
      label: '授权检索',
      status: trace.state,
      occurredAt: trace.occurredAt,
      metadata: {
        topK: trace.topK,
        aclPreFilterCount: trace.aclPreFilterCount,
        aclPostFilterCount: trace.aclPostFilterCount,
        returnedCount: trace.returnedCount,
        latencyMs: trace.latencyMs,
        retrievalMode: trace.retrievalMode,
        retrievalPolicyVersion: trace.retrievalPolicyVersion,
        indexVersion: trace.indexVersion,
        embeddingModelVersion: trace.embeddingModelVersion,
      },
    }))
    return {
      run: mapAuditRun(raw.run),
      steps: [...steps, ...retrievalSteps],
      retrievals,
      tools,
      citations,
      proposals,
      approvals,
      executions,
      usage,
      hashChain,
    }
  }

  getAuditContent(id: string, reason: string, proof: string) {
    if (reason.trim().length < 10) return Promise.reject(new Error('审计正文读取理由至少 10 个字符'))
    if (!proof.trim()) return Promise.reject(new Error('审计正文读取需要 step-up proof'))
    return apiRequest<AiAuditRunContent>(`/api/ai/audit/runs/${encodeURIComponent(id)}/content`, {
      headers: { 'X-Audit-Reason': reason.trim(), 'X-Step-Up-Proof': proof },
    })
  }

  async readAuditContent(id: string, reason: string, password: string) {
    const normalizedReason = reason.trim()
    if (normalizedReason.length < 10) throw new Error('审计正文读取理由至少 10 个字符')
    if (!password) throw new Error('读取审计正文前必须重新输入当前密码')
    const requestHash = await sha256(`audit-content.v1|${id}|${normalizedReason}`)
    const issued = await apiRequest<{ proof: string }>('/api/security/step-up', {
      method: 'POST',
      body: JSON.stringify({ password, actionCode: 'AUDIT_CONTENT_READ', resourcePublicId: id, requestHash }),
    })
    if (!issued.proof) throw new Error('step-up 未返回有效单次证明')
    return this.getAuditContent(id, normalizedReason, issued.proof)
  }

  getAuditCosts(input: { page?: number; pageSize?: number } = {}) {
    return apiRequest<AiPage<AiAuditCost>>(`/api/ai/audit/costs${queryString(input)}`)
  }

  async reconfirmExecution(id: string, input: AiExecutionReconfirmInput): Promise<AiExecutionReconfirmResult> {
    const comment = input.comment.trim()
    if (comment.length < 6 || comment.length > 500) throw new Error('人工对账说明需为 6-500 个字符')
    if (!input.password) throw new Error('人工对账前必须重新输入当前密码')
    if (!Number.isSafeInteger(input.version) || input.version < 0
      || !/^[0-9a-f]{64}$/.test(input.payloadHash)
      || !/^[0-9a-f]{64}$/.test(input.businessSnapshotHash)) {
      throw new Error('人工对账提案版本或 hash 不合法')
    }
    const body = {
      version: input.version,
      payloadHash: input.payloadHash,
      businessSnapshotHash: input.businessSnapshotHash,
      resolution: input.resolution,
      comment,
    }
    const framed = (value: string) => `${value.length}:${value}|`
    const requestHash = await sha256(`execution-reconfirm.v1|${framed(id)}${framed(String(input.version))}`
      + `${framed(input.payloadHash.toLowerCase())}${framed(input.businessSnapshotHash.toLowerCase())}`
      + `${framed(input.resolution)}${framed(comment)}`)
    const issued = await apiRequest<{ proof: string }>('/api/security/step-up', {
      method: 'POST',
      body: JSON.stringify({
        password: input.password,
        actionCode: 'PROPOSAL_RECONFIRM',
        resourcePublicId: id,
        requestHash,
      }),
    })
    if (!issued.proof) throw new Error('step-up 未返回有效单次证明')
    return apiRequest<AiExecutionReconfirmResult>(`/api/ai/executions/${encodeURIComponent(id)}/reconfirm`, {
      method: 'POST',
      headers: { 'Idempotency-Key': idempotencyKey(), 'X-Step-Up-Proof': issued.proof },
      body: JSON.stringify(body),
    })
  }

  listKnowledgeSources(input: { page?: number; pageSize?: number } = {}) {
    return apiRequest<AiPage<AiKnowledgeSource>>(`/api/ai/knowledge/sources${queryString(input)}`)
  }

  createKnowledgeSource(input: { name: string; ownerUserId?: number; classification: 'L0' | 'L1' | 'L2'; matchMode: 'ANY' | 'ALL'; permissions: string[] }) {
    return apiRequest<AiKnowledgeSource>('/api/ai/knowledge/sources', { method: 'POST', body: JSON.stringify(input) })
  }

  getKnowledgeSource(id: string) {
    return apiRequest<AiKnowledgeSource>(`/api/ai/knowledge/sources/${encodeURIComponent(id)}`)
  }

  updateKnowledgeSource(id: string, input: {
    expectedAclVersion: number
    name?: string
    classification?: 'L0' | 'L1' | 'L2'
    matchMode?: 'ANY' | 'ALL'
    status?: 'ACTIVE' | 'PAUSED'
    permissions?: string[]
  }) {
    if (!Number.isSafeInteger(input.expectedAclVersion) || input.expectedAclVersion < 1) {
      return Promise.reject(new Error('知识来源 ACL 版本无效'))
    }
    return apiRequest<AiKnowledgeSource>(`/api/ai/knowledge/sources/${encodeURIComponent(id)}`, {
      method: 'PATCH', body: JSON.stringify(input),
    })
  }

  createKnowledgeUpload(sourceId: string, input: { mimeType: 'text/plain'; sizeBytes: number; sha256: string }) {
    if (input.mimeType !== 'text/plain') return Promise.reject(new Error('首期仅支持经过批准的纯文本'))
    if (!Number.isSafeInteger(input.sizeBytes) || input.sizeBytes < 1 || input.sizeBytes > 20 * 1024 * 1024) {
      return Promise.reject(new Error('知识文件大小必须为 1 B 至 20 MiB'))
    }
    if (!/^[0-9a-f]{64}$/i.test(input.sha256)) return Promise.reject(new Error('知识文件 SHA-256 不合法'))
    return command<AiKnowledgeUpload>(`/api/ai/knowledge/sources/${encodeURIComponent(sourceId)}/uploads`, input)
  }

  putKnowledgeUploadContent(upload: AiKnowledgeUpload, content: string) {
    const expected = `/api/ai/knowledge/uploads/${encodeURIComponent(upload.id)}/content`
    if (upload.uploadTarget !== expected) return Promise.reject(new Error('知识上传地址不合法'))
    if (upload.mimeType !== 'text/plain') return Promise.reject(new Error('首期仅支持经过批准的纯文本'))
    if (new TextEncoder().encode(content).byteLength !== upload.sizeBytes) return Promise.reject(new Error('知识正文大小与上传会话不一致'))
    return apiRequest<void>(expected, { method: 'PUT', headers: { 'Content-Type': 'text/plain' }, body: content })
  }

  finalizeKnowledgeUpload(id: string) {
    return command<{ id: string; state: string; observedSha256: string; observedSizeBytes: number }>(
      `/api/ai/knowledge/uploads/${encodeURIComponent(id)}/finalize`, {})
  }

  createKnowledgeVersion(sourceId: string, input: { uploadSessionId: string; externalKey: string; title: string; version: string }) {
    return command<{ versionId: string; jobId: string; state: string }>(
      `/api/ai/knowledge/sources/${encodeURIComponent(sourceId)}/versions`, input)
  }

  getKnowledgeVersion(id: string) {
    return apiRequest<AiKnowledgeVersion>(`/api/ai/knowledge/versions/${encodeURIComponent(id)}`)
  }

  getKnowledgeJob(id: string) {
    return apiRequest<AiKnowledgeJob>(`/api/ai/knowledge/jobs/${encodeURIComponent(id)}`)
  }

  activateKnowledgeVersion(id: string) {
    return apiRequest<void>(`/api/ai/knowledge/versions/${encodeURIComponent(id)}/activate`, { method: 'POST' })
  }

  retireKnowledgeVersion(id: string) {
    return apiRequest<void>(`/api/ai/knowledge/versions/${encodeURIComponent(id)}/retire`, { method: 'POST' })
  }

  async approvePublicKnowledgeVersion(id: string, input: {
    contentHash: string
    approvalSnapshotHash: string
    password: string
  }) {
    if (!/^[0-9a-f]{64}$/i.test(input.contentHash)) throw new Error('知识版本内容哈希不合法')
    if (!/^[0-9a-f]{64}$/i.test(input.approvalSnapshotHash)) throw new Error('知识公开审批快照不合法')
    if (!input.password) throw new Error('知识公开审批需要重新输入当前密码')
    const framed = (value: string) => `${value.length}:${value}|`
    const requestHash = await sha256(`knowledge-public-approve.v1|${framed(id)}${framed(input.contentHash.toLowerCase())}${framed(input.approvalSnapshotHash.toLowerCase())}`)
    const issued = await apiRequest<{ proof: string }>('/api/security/step-up', {
      method: 'POST',
      body: JSON.stringify({ password: input.password, actionCode: 'KNOWLEDGE_PUBLIC_APPROVE',
        resourcePublicId: id, requestHash }),
    })
    if (!issued.proof) throw new Error('step-up 未返回有效单次证明')
    const key = idempotencyKey()
    await requestWithNetworkRetry<void>(`/api/ai/knowledge/versions/${encodeURIComponent(id)}/approve-public`, {
      method: 'POST',
      headers: { 'Idempotency-Key': key, 'X-Step-Up-Proof': issued.proof },
      body: JSON.stringify({ contentHash: input.contentHash, approvalSnapshotHash: input.approvalSnapshotHash }),
    })
  }
}

function mapAuditRun(value: unknown): AiAuditRun {
  const raw = object(value, '审计运行 ')
  if ('modelAlias' in raw) return raw as unknown as AiAuditRun
  const createdAt = string(raw.createdAt, '审计运行 ')
  const finishedAt = typeof raw.finishedAt === 'string' ? raw.finishedAt : createdAt
  const durationMs = Math.max(0, Date.parse(finishedAt) - Date.parse(createdAt))
  const citationCount = number(raw.citationCount, '审计运行 ')
  if (!Number.isSafeInteger(citationCount) || citationCount < 0) throw new Error('审计运行 响应合同不合法')
  return {
    id: string(raw.id, '审计运行 '),
    parentRunId: nullableString(raw.parentRunId, '审计运行 '),
    costStatus: nullableString(raw.costStatus, '审计成本状态 '),
    capability: string(raw.capability, '审计运行 '),
    state: string(raw.state, '审计运行 ').toLowerCase() as AiAuditRun['state'],
    modelAlias: string(raw.providerAlias, '审计运行 '),
    promptVersion: string(raw.promptVersion, '审计运行 ', true),
    citationCount,
    durationMs: Number.isFinite(durationMs) ? durationMs : 0,
    inputTokens: number(raw.inputTokens, '审计运行 '),
    outputTokens: number(raw.outputTokens, '审计运行 '),
    estimatedCost: number(raw.estimatedCost, '审计运行 '),
    currency: 'CNY',
    chainHash: nullableString(raw.chainHash, '审计运行 '),
    occurredAt: createdAt,
    steps: [],
  }
}

function mapAuditToolCall(value: unknown): AiAuditToolCall {
  const raw = object(value, '审计工具调用 ')
  return {
    id: string(raw.id, '审计工具调用 '),
    sequence: number(raw.sequence, '审计工具调用 '),
    toolName: string(raw.toolName, '审计工具调用 '),
    toolVersion: string(raw.toolVersion, '审计工具调用 '),
    authorizationDecision: string(raw.authorizationDecision, '审计工具调用 '),
    state: string(raw.state, '审计工具调用 '),
    errorCode: nullableString(raw.errorCode, '审计工具调用 '),
    startedAt: nullableString(raw.startedAt, '审计工具调用 '),
    finishedAt: nullableString(raw.finishedAt, '审计工具调用 '),
  }
}

function mapAuditCitation(value: unknown): AiAuditCitation {
  const raw = object(value, '审计引用 ')
  return {
    id: string(raw.id, '审计引用 '),
    citationType: string(raw.citationType, '审计引用 '),
    documentVersionId: nullableString(raw.documentVersionId, '审计引用 '),
    chunkId: nullableString(raw.chunkId, '审计引用 '),
    metricId: nullableString(raw.metricId, '审计引用 '),
    rank: number(raw.rank, '审计引用 '),
    score: nullableNumber(raw.score, '审计引用 '),
    contentHash: string(raw.contentHash, '审计引用 '),
    createdAt: string(raw.createdAt, '审计引用 '),
  }
}

function mapAuditProposal(value: unknown): AiAuditProposal {
  const raw = object(value, '审计提案 ')
  return {
    id: string(raw.id, '审计提案 '),
    actionType: string(raw.actionType, '审计提案 '),
    targetType: string(raw.targetType, '审计提案 '),
    payloadHash: string(raw.payloadHash, '审计提案 '),
    businessSnapshotHash: string(raw.businessSnapshotHash, '审计提案 '),
    approvalPolicyVersion: string(raw.approvalPolicyVersion, '审计提案 '),
    requiredApprovalCount: number(raw.requiredApprovalCount, '审计提案 '),
    approvedCount: number(raw.approvedCount, '审计提案 '),
    riskLevel: string(raw.riskLevel, '审计提案 '),
    state: string(raw.state, '审计提案 '),
    proposerUserId: number(raw.proposerUserId, '审计提案 '),
    expiresAt: string(raw.expiresAt, '审计提案 '),
    createdAt: string(raw.createdAt, '审计提案 '),
  }
}

function mapAuditApproval(value: unknown): AiAuditApproval {
  const raw = object(value, '审计审批 ')
  return {
    proposalId: string(raw.proposalId, '审计审批 '),
    proposalVersion: number(raw.proposalVersion, '审计审批 '),
    decision: string(raw.decision, '审计审批 '),
    reviewerUserId: number(raw.reviewerUserId, '审计审批 '),
    payloadHash: string(raw.payloadHash, '审计审批 '),
    businessSnapshotHash: string(raw.businessSnapshotHash, '审计审批 '),
    createdAt: string(raw.createdAt, '审计审批 '),
  }
}

function mapAuditExecution(value: unknown): AiAuditExecution {
  const raw = object(value, '审计执行 ')
  return {
    id: string(raw.id, '审计执行 '),
    proposalId: string(raw.proposalId, '审计执行 '),
    state: string(raw.state, '审计执行 '),
    version: number(raw.version, '审计执行 '),
    handlerName: string(raw.handlerName, '审计执行 '),
    executedByUserId: number(raw.executedByUserId, '审计执行 '),
    reconfirmedByUserId: nullableNumber(raw.reconfirmedByUserId, '审计执行 '),
    resultResourceType: nullableString(raw.resultResourceType, '审计执行 '),
    errorCode: nullableString(raw.errorCode, '审计执行 '),
    startedAt: nullableString(raw.startedAt, '审计执行 '),
    finishedAt: nullableString(raw.finishedAt, '审计执行 '),
  }
}

function mapAuditUsage(value: unknown): AiAuditUsage {
  const raw = object(value, '审计用量 ')
  return {
    requestSequence: number(raw.requestSequence, '审计用量 '),
    attempt: number(raw.attempt, '审计用量 '),
    requestKind: string(raw.requestKind, '审计用量 '),
    actorKind: string(raw.actorKind, '审计用量 '),
    actorUserId: nullableNumber(raw.actorUserId, '审计用量 '),
    servicePrincipalCode: nullableString(raw.servicePrincipalCode, '审计用量 '),
    initiatedByUserId: nullableNumber(raw.initiatedByUserId, '审计用量 '),
    capability: string(raw.capability, '审计用量 '),
    providerCode: string(raw.providerCode, '审计用量 '),
    modelName: string(raw.modelName, '审计用量 '),
    inputTokens: number(raw.inputTokens, '审计用量 '),
    outputTokens: number(raw.outputTokens, '审计用量 '),
    costAmount: number(raw.costAmount, '审计用量 '),
    currency: string(raw.currency, '审计用量 '),
    usageSource: string(raw.usageSource, '审计用量 '),
    occurredAt: string(raw.occurredAt, '审计用量 '),
  }
}

function mapAuditHashEvent(value: unknown): AiAuditHashEvent {
  const raw = object(value, '审计 hash 链 ')
  return {
    id: string(raw.id, '审计 hash 链 '),
    chainScope: string(raw.chainScope, '审计 hash 链 '),
    aggregateType: string(raw.aggregateType, '审计 hash 链 '),
    aggregatePublicId: string(raw.aggregatePublicId, '审计 hash 链 '),
    sequence: number(raw.sequence, '审计 hash 链 '),
    eventType: string(raw.eventType, '审计 hash 链 '),
    actorKind: string(raw.actorKind, '审计 hash 链 '),
    actorUserId: nullableNumber(raw.actorUserId, '审计 hash 链 '),
    servicePrincipalCode: nullableString(raw.servicePrincipalCode, '审计 hash 链 '),
    initiatedByUserId: nullableNumber(raw.initiatedByUserId, '审计 hash 链 '),
    effectiveSubjectUserId: nullableNumber(raw.effectiveSubjectUserId, '审计 hash 链 '),
    payloadHash: string(raw.payloadHash, '审计 hash 链 '),
    previousEventHash: nullableString(raw.previousEventHash, '审计 hash 链 '),
    eventHash: string(raw.eventHash, '审计 hash 链 '),
    integrityAlgorithm: string(raw.integrityAlgorithm, '审计 hash 链 '),
    integrityKeyVersion: number(raw.integrityKeyVersion, '审计 hash 链 '),
    canonicalizationVersion: string(raw.canonicalizationVersion, '审计 hash 链 '),
    correlationId: string(raw.correlationId, '审计 hash 链 '),
    occurredAt: string(raw.occurredAt, '审计 hash 链 '),
  }
}

function mapAuditStep(value: unknown, index: number): import('../types/ai').AiAuditStep {
  const raw = object(value, '审计步骤 ')
  return {
    id: `${raw.sequence ?? index + 1}`,
    type: string(raw.type, '审计步骤 '),
    label: string(raw.type, '审计步骤 '),
    status: 'RECORDED',
    occurredAt: string(raw.occurredAt, '审计步骤 '),
    metadata: { sequence: number(raw.sequence, '审计步骤 ') },
  }
}

function mapAuditRetrieval(value: unknown): AiAuditRetrievalTrace {
  const raw = object(value, '检索轨迹 ')
  return {
    id: string(raw.id, '检索轨迹 '),
    queryHash: string(raw.queryHash, '检索轨迹 '),
    retrievalPolicyVersion: string(raw.retrievalPolicyVersion, '检索轨迹 '),
    retrievalMode: string(raw.retrievalMode, '检索轨迹 '),
    indexCode: string(raw.indexCode, '检索轨迹 '),
    indexVersion: string(raw.indexVersion, '检索轨迹 '),
    embeddingModelVersion: string(raw.embeddingModelVersion, '检索轨迹 '),
    topK: number(raw.topK, '检索轨迹 '),
    aclPreFilterCount: number(raw.aclPreFilterCount, '检索轨迹 '),
    aclPostFilterCount: number(raw.aclPostFilterCount, '检索轨迹 '),
    returnedCount: number(raw.returnedCount, '检索轨迹 '),
    latencyMs: number(raw.latencyMs, '检索轨迹 '),
    state: string(raw.state, '检索轨迹 '),
    occurredAt: string(raw.occurredAt, '检索轨迹 '),
  }
}
