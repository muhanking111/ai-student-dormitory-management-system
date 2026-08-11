import type {
  AiAuditRun,
  AiAuditRunQuery,
  AiAuditCost,
  AiCitationDetail,
  AiConversationDetail,
  AiConversationSummary,
  AiAuditRunContent,
  AiAuditRunDetail,
  AiConversationInput,
  AiFeedbackInput,
  AiDashboardInput,
  AiDashboardInsight,
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

export type AiProposalActionType = AiProposalPreview['actionType']

export interface AiProposalQuery {
  page?: number
  pageSize?: number
  state?: string
  actionType?: AiProposalActionType
}

export interface AiClient {
  queryDashboard?(input: AiDashboardInput, signal?: AbortSignal): Promise<AiDashboardInsight>
  startConversation?(input: AiConversationInput, signal?: AbortSignal): AsyncIterable<AiRunEvent>
  listConversations?(input?: { page?: number; pageSize?: number }): Promise<AiPage<AiConversationSummary>>
  getConversation?(id: string): Promise<AiConversationDetail>
  submitFeedback?(messageId: string, input: AiFeedbackInput): Promise<void>
  cancelRun?(id: string): Promise<void>
  retryRun?(id: string, signal?: AbortSignal): AsyncIterable<AiRunEvent>
  getCitation?(id: string): Promise<AiCitationDetail>
  triageRepair?(input: AiRepairTriageInput, signal?: AbortSignal): Promise<AiRepairTriageResult>
  draftNotice?(input: AiNoticeDraftInput, signal?: AbortSignal): Promise<AiNoticeDraftResult>
  listRiskCases?(input?: { page?: number; pageSize?: number; type?: string; state?: string; keyword?: string }): Promise<AiPage<AiRiskCase>>
  updateRiskCase?(id: string, action: 'acknowledge' | 'resolve' | 'dismiss', input: AiRiskDispositionInput): Promise<AiRiskCase>
  listProposals?(input?: AiProposalQuery): Promise<AiPage<AiProposalPreview>>
  getProposal?(id: string): Promise<AiProposalPreview>
  approveProposal?(id: string, input: { version: number; payloadHash: string; businessSnapshotHash: string; comment?: string; password?: string }): Promise<AiProposalPreview>
  rejectProposal?(id: string, input: { version: number; comment?: string }): Promise<void>
  listAuditRuns?(input?: AiAuditRunQuery): Promise<AiPage<AiAuditRun>>
  getAuditRun?(id: string): Promise<AiAuditRunDetail>
  getAuditContent?(id: string, reason: string, proof: string): Promise<AiAuditRunContent>
  readAuditContent?(id: string, reason: string, password: string): Promise<AiAuditRunContent>
  getAuditCosts?(input?: { page?: number; pageSize?: number }): Promise<AiPage<AiAuditCost>>
  reconfirmExecution?(id: string, input: AiExecutionReconfirmInput): Promise<AiExecutionReconfirmResult>
  listKnowledgeSources?(input?: { page?: number; pageSize?: number }): Promise<AiPage<AiKnowledgeSource>>
  createKnowledgeSource?(input: { name: string; ownerUserId?: number; classification: 'L0' | 'L1' | 'L2'; matchMode: 'ANY' | 'ALL'; permissions: string[] }): Promise<AiKnowledgeSource>
  getKnowledgeSource?(id: string): Promise<AiKnowledgeSource>
  updateKnowledgeSource?(id: string, input: { expectedAclVersion: number; name?: string; classification?: 'L0' | 'L1' | 'L2'; matchMode?: 'ANY' | 'ALL'; status?: 'ACTIVE' | 'PAUSED'; permissions?: string[] }): Promise<AiKnowledgeSource>
  createKnowledgeUpload?(sourceId: string, input: { mimeType: 'text/plain'; sizeBytes: number; sha256: string }): Promise<AiKnowledgeUpload>
  putKnowledgeUploadContent?(upload: AiKnowledgeUpload, content: string): Promise<void>
  finalizeKnowledgeUpload?(id: string): Promise<{ id: string; state: string; observedSha256: string; observedSizeBytes: number }>
  createKnowledgeVersion?(sourceId: string, input: { uploadSessionId: string; externalKey: string; title: string; version: string }): Promise<{ versionId: string; jobId: string; state: string }>
  getKnowledgeVersion?(id: string): Promise<AiKnowledgeVersion>
  getKnowledgeJob?(id: string): Promise<AiKnowledgeJob>
  activateKnowledgeVersion?(id: string): Promise<void>
  retireKnowledgeVersion?(id: string): Promise<void>
  approvePublicKnowledgeVersion?(id: string, input: { contentHash: string; approvalSnapshotHash: string; password: string }): Promise<void>
}

export class AiDisabledError extends Error {
  constructor() {
    super('AI 能力当前未启用')
    this.name = 'AiDisabledError'
  }
}
