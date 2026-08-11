import { describe, expect, it } from 'vitest'
import type { AiEvidenceMeta } from '../types/ai'
import { isProposalEvidenceConfirmable } from './ai-approval'

const citation = { id: 'hash', label: '业务快照', locator: 'REPAIR_ORDER:1', version: 'BUSINESS_SNAPSHOT', access: 'available' as const }
const base: AiEvidenceMeta = {
  basis: 'deterministic', asOf: '2026-07-12T10:00:00Z', citations: [citation], grounded: true,
}

describe('proposal evidence fail-closed gate', () => {
  it('允许有真实引用的确定性预览且不伪造模型置信度', () => {
    expect(isProposalEvidenceConfirmable(base)).toBe(true)
  })

  it('拒绝无来源、低置信模型和未标注证据语义', () => {
    expect(isProposalEvidenceConfirmable({ ...base, citations: [], grounded: false })).toBe(false)
    expect(isProposalEvidenceConfirmable({ ...base, basis: 'model', confidence: 0.69 })).toBe(false)
    expect(isProposalEvidenceConfirmable({ ...base, basis: 'unverified' })).toBe(false)
    expect(isProposalEvidenceConfirmable({ ...base, basis: undefined, confidence: 0.99 })).toBe(false)
    expect(isProposalEvidenceConfirmable({ ...base, asOf: '' })).toBe(false)
  })
})
