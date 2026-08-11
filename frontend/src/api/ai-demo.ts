import type { AiClient, AiProposalQuery } from './ai'
import { isProposalEvidenceConfirmable } from '../utils/ai-approval'
import { visualEvidenceMode } from '../utils/visual-evidence-mode'
import type {
  AiAuditRun,
  AiAuditRunQuery,
  AiCitation,
  AiConversationDetail,
  AiConversationInput,
  AiConversationSummary,
  AiDashboardInsight,
  AiDashboardInput,
  AiNoticeDraftResult,
  AiPage,
  AiProposalPreview,
  AiRepairTriageResult,
  AiRiskCase,
  AiRiskDispositionInput,
  AiRunEvent,
  AiFeedbackInput,
  AiPersistedMessage,
  AiKnowledgeSource,
  AiKnowledgeUpload,
  AiKnowledgeVersion,
  AiKnowledgeJob,
} from '../types/ai'

const citations: AiCitation[] = [
  { id: 'citation-policy', label: '宿舍维修管理办法', locator: '第 3.2 条', version: '2026.1', access: 'available' },
  { id: 'citation-duty', label: '后勤值班手册', locator: '第 2.1 节', version: '2026.1', access: 'available' },
]
const revokedCitation: AiCitation = {
  id: 'citation-revoked',
  label: '值班交接记录',
  locator: '内部资料',
  version: '2026.1',
  access: 'denied',
}
export type DemoAssistantScenario =
  | 'success' | 'streaming' | 'low-confidence' | 'no-source'
  | 'revoked' | 'degraded' | 'failed' | 'timed-out'
const demoAssistantScenarios = new Set<DemoAssistantScenario>([
  'success', 'streaming', 'low-confidence', 'no-source',
  'revoked', 'degraded', 'failed', 'timed-out',
])
export type DemoDashboardScenario =
  | 'success' | 'low-confidence' | 'no-source' | 'degraded'
  | 'failed' | 'timed-out' | 'loading'
const demoDashboardScenarios = new Set<DemoDashboardScenario>([
  'success', 'low-confidence', 'no-source', 'degraded',
  'failed', 'timed-out', 'loading',
])
const asOf = '2026-07-11T10:30:00+08:00'
const pendingProposalExpiry = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString()
const dashboardCitations: AiCitation[] = [
  { id: 'metric-repair-pending-v1', label: '维修工单统计', locator: 'repair.pending.count', version: 'v1', access: 'available' },
  { id: 'metric-hygiene-failed-v1', label: '卫生检查统计', locator: 'hygiene.failed.count', version: 'v1', access: 'available' },
  { id: 'metric-payment-unpaid-v1', label: '缴费账单统计', locator: 'payment.unpaid.count', version: 'v1', access: 'available' },
]

function page<T>(records: T[], input?: { page?: number; pageSize?: number }): AiPage<T> {
  return { records, total: records.length, page: input?.page ?? 1, pageSize: input?.pageSize ?? 10 }
}

function wait(ms: number, signal?: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    if (signal?.aborted) return reject(new DOMException('The operation was aborted', 'AbortError'))
    const timer = setTimeout(resolve, ms)
    signal?.addEventListener('abort', () => {
      clearTimeout(timer)
      reject(new DOMException('The operation was aborted', 'AbortError'))
    }, { once: true })
  })
}

function deterministicRisk(input: {
  id: string; token: string; type: AiRiskCase['type']; severity: AiRiskCase['severity']; riskType: string
  subjectType: AiRiskCase['businessSnapshot']['subjectType']; ruleVersion: string; summary: string
  facts: Record<string, string | number | boolean>; state?: AiRiskCase['state']; assigneeUserId?: number
  asOf?: string; explanationBasis?: AiRiskCase['explanationEvidence']['basis']; confidence?: number
}): AiRiskCase {
  const observedAt = input.asOf ?? asOf
  const state = input.state ?? 'open'
  const events = [{ id: `${input.id}-opened`, type: 'OPENED', actor: '规则引擎',
    detail: '确定性规则命中', occurredAt: observedAt }]
  const disposition = state === 'open' ? undefined : {
    acknowledged: { type: 'ACKNOWLEDGED', detail: '已开始人工核查' },
    resolved: { type: 'RESOLVED', detail: '人工核验后已记录处置完成' },
    dismissed: { type: 'DISMISSED', detail: '人工核验后确认该信号不成立' },
  }[state]
  if (disposition) events.push({ id: `${input.id}-${state}`, ...disposition, actor: '值班管理员', occurredAt: observedAt })
  const assigneeUserId = input.assigneeUserId ?? null
  const explanationBasis = input.explanationBasis ?? 'deterministic_degraded'
  const degraded = explanationBasis === 'deterministic_degraded'
  const explanation = degraded
    ? '确定性规则证据已命中；模型解释当前不可用，请人工核验业务事实后再记录处置结论。'
    : '受控模型仅基于当前可见规则证据给出排序解释，最终处置仍由人工核验业务事实。'
  return {
    id: input.id, caseVersion: events.length, subjectToken: input.token,
    type: input.type, severity: input.severity, ruleVersion: input.ruleVersion,
    evidenceSummary: input.summary, explanation, assignee: assigneeUserId ? `用户 #${assigneeUserId}` : '待人工分配',
    sla: '按运营规则人工核验', state, asOf: observedAt, events, degraded, confidence: input.confidence,
    assigneeUserId, dueAt: null,
    signalEvidence: { riskType: input.riskType, policyVersion: input.ruleVersion,
      severity: input.severity.toUpperCase() as 'LOW' | 'MEDIUM' | 'HIGH', observedAt, facts: input.facts },
    businessSnapshot: { subjectType: input.subjectType, subjectToken: input.token, capturedAt: observedAt, facts: input.facts },
    explanationEvidence: { text: explanation, basis: explanationBasis,
      policyVersion: degraded ? 'risk-explanation-deterministic.v1' : 'risk-explanation-model.v1',
      confidence: input.confidence, degraded },
    humanEvidence: { assigneeUserId, dueAt: null, events: [...events] },
  }
}

const initialRisks: AiRiskCase[] = [
  deterministicRisk({ id: 'risk-001', token: 'risk_demo_repair_17', type: '维修风险', severity: 'high',
    riskType: 'repair-backlog', subjectType: 'REPAIR_ORDER', ruleVersion: 'repair-backlog.v1',
    summary: '报修后超过 48 小时仍未处理', facts: { ageHours: 72, ruleThreshold: 48 } }),
  deterministicRisk({ id: 'risk-002', token: 'risk_demo_dorm_03', type: '入住风险', severity: 'medium',
    riskType: 'resource-checkin-inconsistency', subjectType: 'DORMITORY', ruleVersion: 'capacity-consistency.v1',
    summary: '资源与入住汇总存在 1 条差异', facts: { configuredOccupied: 6, occupiedBeds: 5 },
    state: 'acknowledged', assigneeUserId: 1 }),
  deterministicRisk({ id: 'risk-003', token: 'risk_demo_checkin_11', type: '入住风险', severity: 'low',
    riskType: 'long-pending-operation', subjectType: 'CHECK_IN_APPLICATION', ruleVersion: 'long-pending-operation.v1',
    summary: '入住申请临近处置时限', facts: { ageHours: 20, ruleThreshold: 24 } }),
  deterministicRisk({ id: 'risk-004', token: 'risk_demo_dorm_08', type: '卫生风险', severity: 'high',
    riskType: 'failed-hygiene-check', subjectType: 'DORMITORY', ruleVersion: 'failed-hygiene-check.v1',
    summary: '最近一次卫生检查不合格', facts: { count: 1, score: 55, result: '不合格' } }),
  deterministicRisk({ id: 'risk-005', token: 'risk_demo_payment_21', type: '欠费风险', severity: 'high',
    riskType: 'overdue-payment', subjectType: 'PAYMENT', ruleVersion: 'overdue-payment.v1',
    summary: '账单已超过截止日 5 天', facts: { ageDays: 5, status: '部分缴' } }),
  deterministicRisk({ id: 'risk-006', token: 'risk_demo_repair_26', type: '维修风险', severity: 'low',
    riskType: 'repeat-repair', subjectType: 'REPAIR_ORDER', ruleVersion: 'repeat-repair.v1',
    summary: '同类报修已由人工复核并关闭', facts: { repeatCount: 2, status: 'COMPLETED' },
    state: 'resolved', assigneeUserId: 1, explanationBasis: 'model', confidence: 0.88 }),
  deterministicRisk({ id: 'risk-007', token: 'risk_demo_payment_27', type: '欠费风险', severity: 'medium',
    riskType: 'overdue-payment', subjectType: 'PAYMENT', ruleVersion: 'overdue-payment.v1',
    summary: '账单状态同步延迟，人工核验后驳回信号', facts: { ageDays: 1, status: '已核销' },
    state: 'dismissed', assigneeUserId: 1, explanationBasis: 'model', confidence: 0.82 }),
  deterministicRisk({ id: 'risk-008', token: 'risk_demo_dorm_28', type: '入住风险', severity: 'medium',
    riskType: 'resource-checkin-inconsistency', subjectType: 'DORMITORY', ruleVersion: 'capacity-consistency.v1',
    summary: '入住汇总与资源快照存在差异', facts: { configuredOccupied: 5, occupiedBeds: 4 },
    asOf: '2026-06-18T09:20:00+08:00', state: 'acknowledged', assigneeUserId: 1 }),
  deterministicRisk({ id: 'risk-009', token: 'risk_demo_checkin_29', type: '入住风险', severity: 'low',
    riskType: 'long-pending-operation', subjectType: 'CHECK_IN_APPLICATION', ruleVersion: 'long-pending-operation.v1',
    summary: '入住申请接近人工处理时限', facts: { ageHours: 18, ruleThreshold: 24 },
    asOf: '2026-06-16T11:10:00+08:00', explanationBasis: 'model', confidence: 0.91 }),
  deterministicRisk({ id: 'risk-010', token: 'risk_demo_repair_30', type: '维修风险', severity: 'high',
    riskType: 'repair-backlog', subjectType: 'REPAIR_ORDER', ruleVersion: 'repair-backlog.v1',
    summary: '维修工单超过 48 小时未更新', facts: { ageHours: 56, ruleThreshold: 48 },
    asOf: '2026-06-12T15:40:00+08:00' }),
  deterministicRisk({ id: 'risk-011', token: 'risk_demo_hygiene_31', type: '卫生风险', severity: 'medium',
    riskType: 'failed-hygiene-check', subjectType: 'DORMITORY', ruleVersion: 'failed-hygiene-check.v1',
    summary: '卫生检查整改记录尚待人工确认', facts: { count: 1, score: 68, result: '待整改' },
    asOf: '2026-06-08T08:50:00+08:00', state: 'resolved', assigneeUserId: 1 }),
  deterministicRisk({ id: 'risk-012', token: 'risk_demo_payment_32', type: '欠费风险', severity: 'high',
    riskType: 'overdue-payment', subjectType: 'PAYMENT', ruleVersion: 'overdue-payment.v1',
    summary: '账单超过截止日 3 天', facts: { ageDays: 3, status: '未缴' },
    asOf: '2026-06-03T13:30:00+08:00' }),
  deterministicRisk({ id: 'risk-013', token: 'risk_demo_dorm_33', type: '入住风险', severity: 'low',
    riskType: 'resource-checkin-inconsistency', subjectType: 'DORMITORY', ruleVersion: 'capacity-consistency.v1',
    summary: '床位汇总存在一条待核对记录', facts: { configuredOccupied: 4, occupiedBeds: 3 },
    asOf: '2026-05-22T10:00:00+08:00', state: 'dismissed', assigneeUserId: 1 }),
  deterministicRisk({ id: 'risk-014', token: 'risk_demo_repair_34', type: '维修风险', severity: 'medium',
    riskType: 'repeat-repair', subjectType: 'REPAIR_ORDER', ruleVersion: 'repeat-repair.v1',
    summary: '同一位置一周内重复报修', facts: { repeatCount: 3, periodDays: 7 },
    asOf: '2026-05-15T14:30:00+08:00', explanationBasis: 'model', confidence: 0.84 }),
  deterministicRisk({ id: 'risk-015', token: 'risk_demo_hygiene_35', type: '卫生风险', severity: 'high',
    riskType: 'failed-hygiene-check', subjectType: 'DORMITORY', ruleVersion: 'failed-hygiene-check.v1',
    summary: '连续两次卫生检查未通过', facts: { count: 2, score: 52, result: '不合格' },
    asOf: '2026-05-06T09:45:00+08:00', state: 'acknowledged', assigneeUserId: 1 }),
  deterministicRisk({ id: 'risk-016', token: 'risk_demo_checkin_36', type: '入住风险', severity: 'medium',
    riskType: 'long-pending-operation', subjectType: 'CHECK_IN_APPLICATION', ruleVersion: 'long-pending-operation.v1',
    summary: '入住申请超过人工复核阈值', facts: { ageHours: 26, ruleThreshold: 24 },
    asOf: '2026-04-24T10:10:00+08:00' }),
  deterministicRisk({ id: 'risk-017', token: 'risk_demo_repair_37', type: '维修风险', severity: 'high',
    riskType: 'repair-backlog', subjectType: 'REPAIR_ORDER', ruleVersion: 'repair-backlog.v1',
    summary: '维修工单积压超过规则阈值', facts: { ageHours: 64, ruleThreshold: 48 },
    asOf: '2026-04-18T16:20:00+08:00', state: 'resolved', assigneeUserId: 1 }),
  deterministicRisk({ id: 'risk-018', token: 'risk_demo_hygiene_38', type: '卫生风险', severity: 'low',
    riskType: 'failed-hygiene-check', subjectType: 'DORMITORY', ruleVersion: 'failed-hygiene-check.v1',
    summary: '整改回查记录缺少签收', facts: { count: 1, score: 72, result: '待签收' },
    asOf: '2026-04-12T08:30:00+08:00', explanationBasis: 'model', confidence: 0.9 }),
  deterministicRisk({ id: 'risk-019', token: 'risk_demo_payment_39', type: '欠费风险', severity: 'medium',
    riskType: 'overdue-payment', subjectType: 'PAYMENT', ruleVersion: 'overdue-payment.v1',
    summary: '账单超过截止日 2 天', facts: { ageDays: 2, status: '未缴' },
    asOf: '2026-04-03T12:00:00+08:00' }),
  deterministicRisk({ id: 'risk-020', token: 'risk_demo_dorm_40', type: '入住风险', severity: 'high',
    riskType: 'resource-checkin-inconsistency', subjectType: 'DORMITORY', ruleVersion: 'capacity-consistency.v1',
    summary: '资源快照与入住汇总存在两条差异', facts: { configuredOccupied: 6, occupiedBeds: 4 },
    asOf: '2026-03-20T10:30:00+08:00', state: 'acknowledged', assigneeUserId: 1 }),
  deterministicRisk({ id: 'risk-021', token: 'risk_demo_repair_41', type: '维修风险', severity: 'medium',
    riskType: 'repeat-repair', subjectType: 'REPAIR_ORDER', ruleVersion: 'repeat-repair.v1',
    summary: '同类报修在七天内重复出现', facts: { repeatCount: 2, periodDays: 7 },
    asOf: '2026-03-12T11:25:00+08:00', explanationBasis: 'model', confidence: 0.8 }),
  deterministicRisk({ id: 'risk-022', token: 'risk_demo_payment_42', type: '欠费风险', severity: 'low',
    riskType: 'overdue-payment', subjectType: 'PAYMENT', ruleVersion: 'overdue-payment.v1',
    summary: '账单状态待同步核验', facts: { ageDays: 1, status: '同步中' },
    asOf: '2026-03-04T09:00:00+08:00', state: 'dismissed', assigneeUserId: 1 }),
  deterministicRisk({ id: 'risk-023', token: 'risk_demo_dorm_43', type: '入住风险', severity: 'medium',
    riskType: 'long-pending-operation', subjectType: 'CHECK_IN_APPLICATION', ruleVersion: 'long-pending-operation.v1',
    summary: '入住申请达到人工复核提醒阈值', facts: { ageHours: 24, ruleThreshold: 24 },
    asOf: '2026-02-16T10:00:00+08:00' }),
  deterministicRisk({ id: 'risk-024', token: 'risk_demo_hygiene_44', type: '卫生风险', severity: 'high',
    riskType: 'failed-hygiene-check', subjectType: 'DORMITORY', ruleVersion: 'failed-hygiene-check.v1',
    summary: '卫生检查结果不合格', facts: { count: 1, score: 58, result: '不合格' },
    asOf: '2026-02-05T08:20:00+08:00', state: 'resolved', assigneeUserId: 1 }),
]

const initialProposals: AiProposalPreview[] = [
  { id: 'proposal-repair-001', actionType: 'REPAIR_ASSIGN', title: '维修指派建议', target: '维修单 token R-17', currentValue: '未指派', proposedValue: '维修员 A', impact: '仅变更维修负责人，不改变工单状态', requiredPermission: 'ai:approval:review + ADMIN + repair:write + 对象范围', payloadHash: 'a'.repeat(64), businessSnapshotHash: 'b'.repeat(64), version: 1, expiresAt: pendingProposalExpiry, riskLevel: 'high', evidence: { basis: 'deterministic', asOf, citations, grounded: true }, state: 'pending_approval', auditAvailable: true, executionState: 'pending' },
  { id: 'proposal-notice-001', actionType: 'NOTICE_CREATE_DRAFT', title: '公告草稿', target: '宿舍安全检查通知', currentValue: '无草稿', proposedValue: '创建纯文本草稿', impact: '仅创建草稿，不发布公告', requiredPermission: 'ai:approval:review + notice:write', payloadHash: 'c'.repeat(64), businessSnapshotHash: 'd'.repeat(64), version: 1, expiresAt: pendingProposalExpiry, riskLevel: 'medium', evidence: { basis: 'deterministic', asOf, citations, grounded: true }, state: 'pending_approval', auditAvailable: true, executionState: 'pending' },
  { id: 'proposal-expired-001', actionType: 'REPAIR_ASSIGN', title: '已过期维修建议', target: '维修单 token R-09', currentValue: '未指派', proposedValue: '维修员 B', impact: '数据已过期，必须刷新', requiredPermission: 'ai:approval:review + ADMIN + repair:write + 对象范围', payloadHash: 'expired', businessSnapshotHash: 'stale', version: 2, expiresAt: '2026-07-10T10:00:00+08:00', riskLevel: 'medium', evidence: { basis: 'unverified', asOf, citations: [], grounded: false }, state: 'expired', auditAvailable: true },
]

const audits: AiAuditRun[] = [
  { id: 'run-audit-001', capability: 'repair.triage', state: 'succeeded', modelAlias: 'demo-model-disabled-provider', promptVersion: 'repair-triage.v1', citationCount: 2, durationMs: 1820, inputTokens: 842, outputTokens: 404, estimatedCost: 0.0062, currency: 'CNY', chainHash: 'c9e21bf0…76aa128e', occurredAt: asOf, steps: [
    { id: 'step-1', type: 'retrieval', label: '授权检索', status: 'SUCCEEDED', occurredAt: asOf, metadata: { citationCount: 2 } },
    { id: 'step-2', type: 'tool', label: '只读工具', status: 'SUCCEEDED', occurredAt: asOf, metadata: { tool: 'repair.get_context.v1' } },
    { id: 'step-3', type: 'proposal', label: '创建提案', status: 'PENDING_APPROVAL', occurredAt: asOf, metadata: { action: 'REPAIR_ASSIGN' } },
  ] },
]

const demoKnowledgeSources: AiKnowledgeSource[] = [{
  id: 'knowledge-source-demo', name: '宿舍管理制度（演示）', sourceType: 'UPLOAD', ownerUserId: 1,
  classification: 'L1', matchMode: 'ANY', aclVersion: 1, status: 'ENABLED', permissions: ['ai:knowledge:read'],
}]

export class DemoAiClient implements AiClient {
  private readonly assistantScenario?: DemoAssistantScenario
  private readonly dashboardScenario?: DemoDashboardScenario
  private risks = structuredClone(initialRisks)
  private proposals = structuredClone(initialProposals)
  private demoUploadSize = 0
  private demoUploadSha = ''
  private publicIdSequence = 1
  private lastConversationId = ''
  private conversations: Array<{ conversation: AiConversationSummary; messages: AiPersistedMessage[] }> = []
  private feedback = new Map<string, AiFeedbackInput>()

  constructor(assistantScenario?: DemoAssistantScenario, dashboardScenario?: DemoDashboardScenario) {
    this.assistantScenario = assistantScenario
    this.dashboardScenario = dashboardScenario
  }

  private nextPublicId() {
    const suffix = String(this.publicIdSequence++).padStart(12, '0')
    return `00000000-0000-4000-8000-${suffix}`
  }

  private resolvedAssistantScenario(): DemoAssistantScenario {
    if (this.assistantScenario) return this.assistantScenario
    if (!visualEvidenceMode || typeof window === 'undefined') return 'success'
    const requested = new URLSearchParams(window.location.search).get('assistantFixture') as DemoAssistantScenario | null
    return requested && demoAssistantScenarios.has(requested) ? requested : 'success'
  }

  private resolvedDashboardScenario(): DemoDashboardScenario | undefined {
    if (this.dashboardScenario) return this.dashboardScenario
    if (!visualEvidenceMode || typeof window === 'undefined') return undefined
    const requested = new URLSearchParams(window.location.search).get('dashboardFixture') as DemoDashboardScenario | null
    return requested && demoDashboardScenarios.has(requested) ? requested : undefined
  }

  async queryDashboard(_input: AiDashboardInput): Promise<AiDashboardInsight> {
    const scenario = this.resolvedDashboardScenario()
    if (scenario === 'loading') return new Promise<AiDashboardInsight>(() => {})
    if (scenario === 'failed') throw new Error('AI 驾驶舱查询失败，请稍后重试')
    if (scenario === 'timed-out') throw new Error('AI 驾驶舱查询超时，请稍后重试')
    const intent = {
      metricIds: ['repair.pending.count', 'hygiene.failed.count', 'payment.unpaid.count'],
      dateRange: { preset: 'LAST_7_DAYS' as const }, dimensions: [], filters: {}, presentationHint: 'TABLE' as const,
    }
    const noSource = scenario === 'no-source'
    const confidence = scenario === 'success'
      ? 0.92
      : scenario === 'low-confidence' ? 0.58 : scenario === 'degraded' ? 0.72 : undefined
    return {
      id: 'dashboard-demo-001', summary: '整体运营平稳；维修积压与资源一致性需要人工核验。',
      metricVersion: 'dashboard-metrics.v1',
      riskCounts: noSource ? [] : [{ type: '入住风险', count: 1, severity: 'medium' }, { type: '维修风险', count: 2, severity: 'high' }, { type: '卫生风险', count: 1, severity: 'medium' }, { type: '欠费风险', count: 2, severity: 'high' }],
      pendingApprovals: noSource ? 0 : 3,
      evidence: {
        basis: noSource ? 'unverified' : 'deterministic',
        asOf,
        citations: noSource ? [] : dashboardCitations,
        grounded: !noSource,
        ...(confidence === undefined ? {} : { confidence }),
      },
      state: scenario === 'degraded' ? 'degraded' : 'succeeded', intent,
      metrics: {
        'repair.pending.count': { value: 2, unit: '项', metricVersion: 'v1', definition: '待维修工单数', rows: [] },
        'hygiene.failed.count': { value: 1, unit: '项', metricVersion: 'v1', definition: '卫生待整改数', rows: [] },
        'payment.unpaid.count': { value: 2, unit: '项', metricVersion: 'v1', definition: '未缴账单数', rows: [] },
      },
      queryParameters: {
        dateRange: { preset: 'LAST_7_DAYS', from: '2026-07-05', to: '2026-07-11' }, dimensions: [], filters: {},
      },
      intentSource: 'deterministic-demo.v1', modelUsed: false,
    }
  }

  async *startConversation(input: AiConversationInput, signal?: AbortSignal): AsyncGenerator<AiRunEvent> {
    const question = input.text.trim()
    const scenario = this.resolvedAssistantScenario()
    let entry = input.conversationId
      ? this.conversations.find((item) => item.conversation.id === input.conversationId)
      : undefined
    if (input.conversationId && !entry) throw new Error('演示会话不可见')
    if (!entry) {
      entry = {
        conversation: {
          id: this.nextPublicId(),
          surface: input.surface,
          contextType: input.contextType ?? 'NONE',
          contextId: input.contextId ?? null,
          status: 'ACTIVE',
          title: question.slice(0, 36) || '新会话',
          lastMessageAt: asOf,
          createdAt: asOf,
        },
        messages: [],
      }
      this.conversations.unshift(entry)
    }
    this.lastConversationId = entry.conversation.id
    input.onConversationReady?.(entry.conversation.id)
    entry.messages.push({
      id: this.nextPublicId(), role: 'USER', text: question, classification: 'L1', createdAt: asOf, citations: [],
    })
    entry.conversation.lastMessageAt = asOf
    const assistantMessageId = this.nextPublicId()
    const base = { runId: this.nextPublicId(), timestamp: asOf }
    yield { ...base, sequence: 1, type: 'run.started', payload: { modelAlias: 'demo-local', promptVersion: 'assistant.v1' } }
    await wait(60, signal)
    yield { ...base, sequence: 2, type: 'tool.started', payload: { redactedSummary: '正在检索授权资料' } }
    await wait(60, signal)
    const answerText = scenario === 'low-confidence'
      ? '当前授权资料对特殊情况说明有限，一般维修可先按 2 个工作日口径人工核验。'
      : scenario === 'no-source'
        ? '当前授权资料中未找到可确认的处理时限，暂不提供确定性结论。'
        : scenario === 'degraded'
          ? '当前模型不可用，已返回基于已授权规则的确定性说明：一般维修按 2 个工作日人工核验。'
          : scenario === 'failed'
            ? '未能完成授权资料检索。'
            : scenario === 'timed-out'
              ? '检索时间较长，尚未形成可确认结论。'
              : scenario === 'streaming'
                ? '根据当前授权资料，维修申请一般应在受理后 2 个工作日内'
                : '根据当前授权资料，维修申请一般应在受理后 2 个工作日内完成处理。'
    yield { ...base, sequence: 3, type: 'message.delta', payload: { textDelta: answerText } }

    if (scenario === 'streaming') await wait(30_000, signal)
    if (scenario === 'failed') {
      yield { ...base, sequence: 4, type: 'run.failed', payload: {
        errorCode: 'DEMO_RETRIEVAL_FAILED', safeMessage: '授权资料检索失败，请稍后重试',
      } }
      return
    }
    if (scenario === 'timed-out') {
      yield { ...base, sequence: 4, type: 'run.timed_out', payload: {
        errorCode: 'DEMO_TIMED_OUT', safeMessage: '生成超时，可重试本次请求',
      } }
      return
    }

    let sequence = 4
    if (scenario === 'degraded') {
      yield { ...base, sequence: sequence++, type: 'run.degraded', payload: {
        reasonCode: 'DEMO_PROVIDER_UNAVAILABLE', safeMessage: '模型不可用，已降级为确定性规则回答',
      } }
    }
    const scenarioCitations = scenario === 'no-source'
      ? [] : scenario === 'revoked' ? [...citations, revokedCitation] : citations
    for (const citation of scenarioCitations) {
      yield { ...base, sequence: sequence++, type: 'citation.added', payload: { citation } }
    }
    const grounded = scenario !== 'no-source'
    entry.messages.push({
      id: assistantMessageId,
      role: 'ASSISTANT',
      text: answerText,
      classification: 'L1',
      createdAt: asOf,
      citations: structuredClone(scenarioCitations),
      grounded,
      asOf,
      runId: base.runId,
      runState: scenario === 'degraded' ? 'degraded' : 'succeeded',
    })
    yield { ...base, sequence, type: 'run.completed', payload: {
      messageId: assistantMessageId, basis: 'deterministic', grounded, asOf,
      ...(scenario === 'low-confidence' ? { confidence: 0.52 } : {}),
    } }
  }

  async *retryRun(parentRunId: string, signal?: AbortSignal): AsyncGenerator<AiRunEvent> {
    const base = { runId: this.nextPublicId(), timestamp: asOf }
    const messageId = this.nextPublicId()
    yield { ...base, sequence: 1, type: 'run.queued', payload: { parentRunId } }
    await wait(40, signal)
    yield { ...base, sequence: 2, type: 'run.started', payload: { modelAlias: 'demo-local' } }
    yield { ...base, sequence: 3, type: 'message.delta', payload: { textDelta: '已按当前权限与上下文重新生成回答。' } }
    const entry = this.conversations.find((item) => item.conversation.id === this.lastConversationId)
    entry?.messages.push({ id: messageId, role: 'ASSISTANT', text: '已按当前权限与上下文重新生成回答。',
      classification: 'L1', createdAt: asOf, citations: [], grounded: false, asOf, runId: base.runId,
      runState: 'succeeded' })
    yield { ...base, sequence: 4, type: 'run.completed', payload: {
      messageId, basis: 'deterministic', grounded: false, asOf,
    } }
  }

  async listConversations(input?: { page?: number; pageSize?: number }) {
    return page(this.conversations.map((item) => structuredClone(item.conversation)), input)
  }

  async getConversation(id: string): Promise<AiConversationDetail> {
    const entry = this.conversations.find((item) => item.conversation.id === id)
    if (!entry) throw new Error('演示会话不可见')
    return structuredClone(entry)
  }

  async submitFeedback(messageId: string, input: AiFeedbackInput) {
    const visible = this.conversations.some((item) => item.messages.some((message) => (
      message.id === messageId && message.role === 'ASSISTANT'
    )))
    if (!visible) throw new Error('演示回答不可见')
    if (input.rating !== -1 && input.rating !== 1) throw new Error('反馈 rating 不合法')
    this.feedback.set(messageId, structuredClone(input))
  }

  async triageRepair(input: { repairId: number; status: string; descriptionRedacted: string; candidates: Array<{ userId: number; displayName: string }> }): Promise<AiRepairTriageResult> {
    const candidate = input.candidates[0]
    return { repairId: input.repairId, category: input.descriptionRedacted.includes('插座') ? '水电' : '综合维修', urgency: 'HIGH', recommendedTeam: '水电维修组', missingInformation: ['是否已经断电'], reasoningSummary: '描述涉及用电异常，建议优先人工核验。', assignmentCandidateUserId: candidate?.userId ?? null, assignmentCandidateName: candidate?.displayName ?? null, slaSuggestion: '2 小时响应', evidence: { basis: 'deterministic', asOf, citations, grounded: true }, state: 'succeeded', proposalId: candidate ? 'proposal-repair-001' : undefined, proposalState: candidate ? 'pending_approval' : undefined }
  }

  async draftNotice(input: { points: string; type: string; tone: string; audience: string }): Promise<AiNoticeDraftResult> {
    const containsPii = /(?:1[3-9]\d{9}|\b\d{8,18}\b)/.test(input.points)
    const containsMarkup = /<\/?[a-z][^>]*>/i.test(input.points)
    const blocked = containsPii || containsMarkup
    return { title: blocked ? '' : `关于${input.points.slice(0, 18)}的通知`, type: input.type, publisher: '由审批时当前用户重写', status: '草稿', content: blocked ? '' : `${input.audience}：\n\n${input.points}\n\n请按要求落实并关注后续通知。`, blocked, safetyMessages: [containsPii ? '检测到手机号或学号，已阻止生成' : '', containsMarkup ? '检测到 HTML/脚本样式内容，已阻止生成' : ''].filter(Boolean), version: 'notice-draft.v1', evidence: { basis: blocked ? 'unverified' : 'deterministic', asOf, citations: blocked ? [] : citations, grounded: !blocked }, state: blocked ? 'failed' : 'succeeded', proposalId: blocked ? undefined : 'proposal-notice-001', proposalState: blocked ? undefined : 'pending_approval' }
  }

  async listKnowledgeSources(input?: { page?: number; pageSize?: number }) { return page(structuredClone(demoKnowledgeSources), input) }
  async getKnowledgeSource(id: string) {
    const source = demoKnowledgeSources.find((item) => item.id === id)
    if (!source) throw new Error('知识来源不可见')
    return structuredClone(source)
  }
  async createKnowledgeSource(input: { name: string; ownerUserId?: number; classification: 'L0' | 'L1' | 'L2'; matchMode: 'ANY' | 'ALL'; permissions: string[] }) {
    const created = { id: `knowledge-source-${demoKnowledgeSources.length + 1}`, sourceType: 'UPLOAD', ownerUserId: input.ownerUserId ?? 1, aclVersion: 1, status: 'ACTIVE', ...input } as AiKnowledgeSource
    demoKnowledgeSources.unshift(created)
    return structuredClone(created)
  }
  async updateKnowledgeSource(id: string, input: { expectedAclVersion: number; name?: string; classification?: 'L0' | 'L1' | 'L2'; matchMode?: 'ANY' | 'ALL'; status?: 'ACTIVE' | 'PAUSED'; permissions?: string[] }) {
    const index = demoKnowledgeSources.findIndex((item) => item.id === id)
    if (index < 0) throw new Error('知识来源不存在')
    const current = demoKnowledgeSources[index]!
    if (current.aclVersion !== input.expectedAclVersion) throw new Error('知识来源 ACL 版本冲突')
    const updated = { ...current, ...input, aclVersion: current.aclVersion + 1 }
    demoKnowledgeSources[index] = updated
    return structuredClone(updated)
  }
  async createKnowledgeUpload(_sourceId: string, input: { mimeType: 'text/plain'; sizeBytes: number; sha256: string }): Promise<AiKnowledgeUpload> {
    this.demoUploadSize = input.sizeBytes
    this.demoUploadSha = input.sha256
    return { id: 'knowledge-upload-demo', state: 'CREATED', expiresAt: asOf, uploadTarget: '/api/ai/knowledge/uploads/knowledge-upload-demo/content', ...input }
  }
  async putKnowledgeUploadContent() { return undefined }
  async finalizeKnowledgeUpload(id: string) { return { id, state: 'FINALIZED', observedSha256: this.demoUploadSha, observedSizeBytes: this.demoUploadSize } }
  async createKnowledgeVersion() { return { versionId: 'knowledge-version-demo', jobId: 'knowledge-job-demo', state: 'QUEUED' } }
  async getKnowledgeVersion(id: string): Promise<AiKnowledgeVersion> { return { id, documentId: 'document-demo', sourceId: 'knowledge-source-demo', version: 'v1', contentHash: 'demo', visibility: 'EXPLICIT_ACL', status: 'READY', sizeBytes: 0, aclVersion: 1 } }
  async getKnowledgeJob(id: string): Promise<AiKnowledgeJob> { return { id, versionId: 'knowledge-version-demo', state: 'SUCCEEDED', attempt: 1 } }
  async activateKnowledgeVersion() { return undefined }
  async retireKnowledgeVersion() { return undefined }
  async approvePublicKnowledgeVersion() { return undefined }
  async getCitation(id: string) {
    const citation = citations.find((item) => item.id === id)
    if (!citation) throw new Error('citation 不可见')
    return { id, type: 'KNOWLEDGE' as const, sourceId: 'demo-source', documentId: 'demo-document',
      documentVersionId: citation.version, chunkId: 'demo-chunk', metricId: null, rank: 1, score: 1,
      quote: `${citation.label}（演示脱敏片段）`, locator: citation.locator,
      contentHash: 'd'.repeat(64), createdAt: asOf }
  }

  async listRiskCases(input?: { page?: number; pageSize?: number; type?: string; state?: string; keyword?: string }) {
    const keyword = input?.keyword?.trim().toLowerCase()
    const filtered = this.risks.filter((risk) => {
      const matchesKeyword = !keyword || `${risk.subjectToken} ${risk.type} ${risk.evidenceSummary}`.toLowerCase().includes(keyword)
      return (!input?.type || risk.type === input.type) && (!input?.state || risk.state === input.state) && matchesKeyword
    })
    const currentPage = input?.page ?? 1
    const currentPageSize = input?.pageSize ?? 10
    const start = Math.max(0, (currentPage - 1) * currentPageSize)
    return {
      records: filtered.slice(start, start + currentPageSize),
      total: filtered.length,
      page: currentPage,
      pageSize: currentPageSize,
    }
  }

  async updateRiskCase(id: string, action: 'acknowledge' | 'resolve' | 'dismiss', input?: AiRiskDispositionInput) {
    const risk = this.risks.find((item) => item.id === id)
    if (!risk) throw new Error('风险案例不可见')
    if (input && input.caseVersion !== risk.caseVersion) throw new Error('风险案例版本已变化，请刷新后重试')
    risk.state = action === 'acknowledge' ? 'acknowledged' : action === 'resolve' ? 'resolved' : 'dismissed'
    risk.caseVersion += 1
    const event = { id: `event-${risk.events.length + 1}`, type: action.toUpperCase(), actor: '当前用户', detail: input?.detail ?? '演示处置，未写入真实业务系统', occurredAt: asOf }
    risk.events.push(event)
    risk.humanEvidence.events.push(event)
    risk.assigneeUserId = 1
    risk.humanEvidence.assigneeUserId = 1
    risk.assignee = '用户 #1'
    if (input?.dueAt) {
      risk.dueAt = input.dueAt
      risk.humanEvidence.dueAt = input.dueAt
      risk.sla = `截止 ${input.dueAt}`
    }
    return structuredClone(risk)
  }

  async listProposals(input?: AiProposalQuery) {
    return page(this.proposals.filter((proposal) =>
      (!input?.state || proposal.state === input.state)
      && (!input?.actionType || proposal.actionType === input.actionType)), input)
  }

  async getProposal(id: string) {
    const proposal = this.proposals.find((item) => item.id === id)
    if (!proposal) throw new Error('方案不可见')
    return structuredClone(proposal)
  }

  async approveProposal(id: string) {
    const proposal = this.proposals.find((item) => item.id === id)
    if (!proposal) throw new Error('方案不可见')
    if (proposal.state !== 'pending_approval' || !proposal.auditAvailable
      || !isProposalEvidenceConfirmable(proposal.evidence)) throw new Error('当前方案不可批准')
    proposal.state = 'succeeded'
    proposal.executionState = 'succeeded'
    return structuredClone(proposal)
  }

  async rejectProposal(id: string) {
    const proposal = this.proposals.find((item) => item.id === id)
    if (!proposal) throw new Error('方案不可见')
    proposal.state = 'rejected'
  }

  async listAuditRuns(input?: AiAuditRunQuery) {
    return page(structuredClone(audits), input)
  }
}
