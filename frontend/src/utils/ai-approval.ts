import type { AiEvidenceMeta } from '../types/ai'

export function isProposalEvidenceConfirmable(evidence: AiEvidenceMeta | null | undefined) {
  if (!evidence?.grounded || evidence.citations.length === 0 || Number.isNaN(Date.parse(evidence.asOf))) return false
  if (evidence.basis === 'deterministic') return true
  return evidence.basis === 'model'
    && Number.isFinite(evidence.confidence)
    && (evidence.confidence ?? 0) >= 0.7
}
