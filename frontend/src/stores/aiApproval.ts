import { defineStore } from 'pinia'
import { getAiClient } from '../api/ai-client'
import type { AiProposalQuery } from '../api/ai'
import type {
  AiAuditCost, AiAuditRun, AiAuditRunDetail,
  AiExecutionReconfirmResolution, AiProposalPreview,
  AiAuditRunQuery,
} from '../types/ai'

const auditSelectionEpochs = new WeakMap<object, number>()
const auditLoadEpochs = new WeakMap<object, number>()
const proposalLoadEpochs = new WeakMap<object, number>()
const proposalSelectionEpochs = new WeakMap<object, number>()
const sessionEpochs = new WeakMap<object, number>()
const proposalMutationEpochs = new WeakMap<object, number>()
const costLoadEpochs = new WeakMap<object, number>()
type ProposalWithBinding = AiProposalPreview & { runId?: string }
type ProposalMutation = 'approve' | 'reject' | 'refresh' | 'reconfirm'
type ProposalSyncOptions = { syncAudit?: boolean }
type ProposalLoadOptions = ProposalSyncOptions & { reconcileSelection?: boolean }

function nextEpoch(epochs: WeakMap<object, number>, store: object) {
  const epoch = (epochs.get(store) ?? 0) + 1
  epochs.set(store, epoch)
  return epoch
}

function isCurrentOperation(
  store: object,
  sessionEpoch: number,
  operationEpochs: WeakMap<object, number>,
  operationEpoch: number,
) {
  return (sessionEpochs.get(store) ?? 0) === sessionEpoch
    && operationEpochs.get(store) === operationEpoch
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof Error ? error.message : fallback
}

export const useAiApprovalStore = defineStore('aiApproval', {
  state: () => ({
    proposals: [] as AiProposalPreview[], selectedId: '' as string, audits: [] as AiAuditRun[],
    selectedAuditId: '' as string, auditDetail: null as AiAuditRunDetail | null,
    costs: [] as AiAuditCost[],
    proposalTotal: 0, proposalPage: 1, proposalPageSize: 20,
    auditTotal: 0, auditPage: 1, auditPageSize: 20,
    loading: false, error: null as string | null,
    proposalMutation: null as ProposalMutation | null,
    mutationError: null as string | null,
    costsLoading: false,
    costsError: null as string | null,
  }),
  getters: { selected: (state) => state.proposals.find((item) => item.id === state.selectedId) ?? null },
  actions: {
    async loadProposals(
      input: AiProposalQuery = {},
      options: ProposalLoadOptions = {},
    ) {
      const requestEpoch = (proposalLoadEpochs.get(this) ?? 0) + 1
      proposalLoadEpochs.set(this, requestEpoch)
      const client = getAiClient()
      this.error = null
      if (!client.listProposals) {
        this.loading = false
        return
      }
      this.loading = true
      try {
        const result = await client.listProposals(input)
        if (proposalLoadEpochs.get(this) !== requestEpoch) return
        this.proposals = result.records
        this.proposalTotal = result.total
        this.proposalPage = result.page
        this.proposalPageSize = result.pageSize
        this.selectedId = options.reconcileSelection === true
          ? (this.proposals.some((item) => item.id === this.selectedId) ? this.selectedId : this.proposals[0]?.id ?? '')
          : (this.selectedId || this.proposals[0]?.id || '')
        if (options.syncAudit === true) await this.syncSelectedAuditToProposal()
      } catch (error) {
        if (proposalLoadEpochs.get(this) === requestEpoch) {
          this.error = error instanceof Error ? error.message : '审批方案加载失败'
        }
      } finally {
        if (proposalLoadEpochs.get(this) === requestEpoch) this.loading = false
      }
    },
    async approve(comment = '', password = '', options: ProposalSyncOptions = {}) {
      const proposal = this.selected
      const client = getAiClient()
      if (!proposal || !client.approveProposal || this.proposalMutation) return false
      const sessionEpoch = sessionEpochs.get(this) ?? 0
      const mutationEpoch = nextEpoch(proposalMutationEpochs, this)
      this.proposalMutation = 'approve'
      this.mutationError = null
      try {
        const changed = await client.approveProposal(proposal.id, { version: proposal.version, payloadHash: proposal.payloadHash, businessSnapshotHash: proposal.businessSnapshotHash, comment, password })
        if (!isCurrentOperation(this, sessionEpoch, proposalMutationEpochs, mutationEpoch)) return false
        if (changed.id !== proposal.id) throw new Error('批准响应与提案不匹配')
        this.replace(changed)
        if (options.syncAudit === true && this.selectedId === proposal.id) await this.syncSelectedAuditToProposal()
        return true
      } catch (error) {
        if (!isCurrentOperation(this, sessionEpoch, proposalMutationEpochs, mutationEpoch)) return false
        this.mutationError = errorMessage(error, '审批失败')
        throw error
      } finally {
        if (isCurrentOperation(this, sessionEpoch, proposalMutationEpochs, mutationEpoch)) {
          this.proposalMutation = null
        }
      }
    },
    async selectProposal(id: string, options: ProposalSyncOptions = {}) {
      const selectionEpoch = (proposalSelectionEpochs.get(this) ?? 0) + 1
      proposalSelectionEpochs.set(this, selectionEpoch)
      const client = getAiClient()
      this.error = null
      if (!client.getProposal) {
        this.loading = false
        return
      }
      this.loading = true
      try {
        const proposal = await client.getProposal(id)
        if (proposalSelectionEpochs.get(this) !== selectionEpoch) return
        if (proposal.id !== id) throw new Error('提案详情响应与选择不匹配')
        const index = this.proposals.findIndex((item) => item.id === id)
        if (index >= 0) this.proposals[index] = proposal
        else this.proposals.unshift(proposal)
        this.selectedId = id
        if (options.syncAudit === true) await this.syncSelectedAuditToProposal()
      } catch (error) {
        if (proposalSelectionEpochs.get(this) !== selectionEpoch) return
        this.error = error instanceof Error ? error.message : '提案详情加载失败'
        throw error
      } finally {
        if (proposalSelectionEpochs.get(this) === selectionEpoch) this.loading = false
      }
    },
    async reject(comment = '', options: ProposalSyncOptions = {}) {
      const proposal = this.selected
      const client = getAiClient()
      if (!proposal || !client.rejectProposal || this.proposalMutation) return false
      const sessionEpoch = sessionEpochs.get(this) ?? 0
      const mutationEpoch = nextEpoch(proposalMutationEpochs, this)
      this.proposalMutation = 'reject'
      this.mutationError = null
      try {
        await client.rejectProposal(proposal.id, { version: proposal.version, comment })
        if (!isCurrentOperation(this, sessionEpoch, proposalMutationEpochs, mutationEpoch)) return false
        this.replace({ ...proposal, state: 'rejected' })
        if (options.syncAudit === true && this.selectedId === proposal.id) await this.syncSelectedAuditToProposal()
        return true
      } catch (error) {
        if (!isCurrentOperation(this, sessionEpoch, proposalMutationEpochs, mutationEpoch)) return false
        this.mutationError = errorMessage(error, '拒绝提案失败')
        throw error
      } finally {
        if (isCurrentOperation(this, sessionEpoch, proposalMutationEpochs, mutationEpoch)) {
          this.proposalMutation = null
        }
      }
    },
    async refresh(options: ProposalSyncOptions = {}) {
      const proposal = this.selected
      const client = getAiClient()
      if (!proposal || !client.getProposal || this.proposalMutation) return false
      const sessionEpoch = sessionEpochs.get(this) ?? 0
      const mutationEpoch = nextEpoch(proposalMutationEpochs, this)
      this.proposalMutation = 'refresh'
      this.mutationError = null
      try {
        const changed = await client.getProposal(proposal.id)
        if (!isCurrentOperation(this, sessionEpoch, proposalMutationEpochs, mutationEpoch)) return false
        if (changed.id !== proposal.id) throw new Error('刷新响应与提案不匹配')
        this.replace(changed)
        if (options.syncAudit === true && this.selectedId === proposal.id) await this.syncSelectedAuditToProposal()
        return true
      } catch (error) {
        if (!isCurrentOperation(this, sessionEpoch, proposalMutationEpochs, mutationEpoch)) return false
        this.mutationError = errorMessage(error, '刷新提案失败')
        throw error
      } finally {
        if (isCurrentOperation(this, sessionEpoch, proposalMutationEpochs, mutationEpoch)) {
          this.proposalMutation = null
        }
      }
    },
    async loadAudits(input: AiAuditRunQuery = {}, options: { reconcileSelection?: boolean } = {}) {
      const requestEpoch = (auditLoadEpochs.get(this) ?? 0) + 1
      auditLoadEpochs.set(this, requestEpoch)
      const client = getAiClient()
      if (!client.listAuditRuns) return
      try {
        const result = await client.listAuditRuns(input)
        if (auditLoadEpochs.get(this) !== requestEpoch) return
        let nextSelectedAuditId = this.selectedAuditId
        if (options.reconcileSelection !== false) {
          nextSelectedAuditId = result.records.some((item) => item.id === this.selectedAuditId)
            ? this.selectedAuditId
            : result.records[0]?.id ?? ''
          if (nextSelectedAuditId !== this.selectedAuditId || this.auditDetail?.run.id !== nextSelectedAuditId) {
            auditSelectionEpochs.set(this, (auditSelectionEpochs.get(this) ?? 0) + 1)
            this.auditDetail = null
          }
        } else if (!nextSelectedAuditId) {
          nextSelectedAuditId = result.records[0]?.id ?? ''
        }
        this.audits = result.records
        this.auditTotal = result.total
        this.auditPage = result.page
        this.auditPageSize = result.pageSize
        this.selectedAuditId = nextSelectedAuditId
        await this.syncSelectedAuditToProposal()
      } catch (error) {
        if (auditLoadEpochs.get(this) === requestEpoch) this.clearAuditCache()
        throw error
      }
    },
    async selectAudit(id: string) {
      const client = getAiClient()
      if (!client.getAuditRun) {
        const run = this.audits.find((item) => item.id === id)
        this.$patch({
          selectedAuditId: id,
          auditDetail: run ? {
            run,
            steps: run.steps,
            retrievals: [],
            tools: [],
            citations: [],
            proposals: [],
            approvals: [],
            executions: [],
            usage: [],
            hashChain: [],
          } : null,
        })
        return
      }
      const selectionEpoch = (auditSelectionEpochs.get(this) ?? 0) + 1
      auditSelectionEpochs.set(this, selectionEpoch)
      this.$patch({ selectedAuditId: id, auditDetail: null })
      const auditDetail = await client.getAuditRun(id)
      if (auditSelectionEpochs.get(this) !== selectionEpoch || this.selectedAuditId !== id) return
      if (auditDetail.run.id !== id) throw new Error('审计详情响应与选择不匹配')
      this.$patch({ selectedAuditId: id, auditDetail })
    },
    async loadAuditContent(reason: string, password: string) {
      const client = getAiClient()
      if (!this.selectedAuditId || !client.readAuditContent) return
      return client.readAuditContent(this.selectedAuditId, reason, password)
    },
    async loadCosts() {
      const client = getAiClient()
      if (!client.getAuditCosts || this.costsLoading) return false
      const sessionEpoch = sessionEpochs.get(this) ?? 0
      const requestEpoch = nextEpoch(costLoadEpochs, this)
      this.costsLoading = true
      this.costsError = null
      try {
        const result = await client.getAuditCosts({ page: 1, pageSize: 50 })
        if (!isCurrentOperation(this, sessionEpoch, costLoadEpochs, requestEpoch)) return false
        this.costs = result.records
        return true
      } catch (error) {
        if (!isCurrentOperation(this, sessionEpoch, costLoadEpochs, requestEpoch)) return false
        this.costsError = errorMessage(error, '成本明细加载失败')
        throw error
      } finally {
        if (isCurrentOperation(this, sessionEpoch, costLoadEpochs, requestEpoch)) {
          this.costsLoading = false
        }
      }
    },
    async reconfirm(
      resolution: AiExecutionReconfirmResolution,
      comment: string,
      password: string,
      options: ProposalSyncOptions = {},
    ) {
      const proposal = this.selected
      const client = getAiClient()
      if (!proposal || !client.reconfirmExecution || !client.getProposal || this.proposalMutation) return false
      if (!proposal.executionId) throw new Error('执行记录缺失，无法人工对账')
      const sessionEpoch = sessionEpochs.get(this) ?? 0
      const mutationEpoch = nextEpoch(proposalMutationEpochs, this)
      this.proposalMutation = 'reconfirm'
      this.mutationError = null
      try {
        const result = await client.reconfirmExecution(proposal.executionId, {
          version: proposal.version,
          payloadHash: proposal.payloadHash,
          businessSnapshotHash: proposal.businessSnapshotHash,
          resolution,
          comment,
          password,
        })
        if (!isCurrentOperation(this, sessionEpoch, proposalMutationEpochs, mutationEpoch)) return false
        if (result.proposalId !== proposal.id) throw new Error('人工对账响应与提案不匹配')
        if (result.executionId !== proposal.executionId) throw new Error('人工对账响应与执行记录不匹配')
        const changed = await client.getProposal(proposal.id)
        if (!isCurrentOperation(this, sessionEpoch, proposalMutationEpochs, mutationEpoch)) return false
        if (changed.id !== proposal.id) throw new Error('人工对账后的提案响应不匹配')
        this.replace(changed)
        if (options.syncAudit === true && this.selectedId === proposal.id) await this.syncSelectedAuditToProposal()
        return true
      } catch (error) {
        if (!isCurrentOperation(this, sessionEpoch, proposalMutationEpochs, mutationEpoch)) return false
        this.mutationError = errorMessage(error, '人工对账失败')
        throw error
      } finally {
        if (isCurrentOperation(this, sessionEpoch, proposalMutationEpochs, mutationEpoch)) {
          this.proposalMutation = null
        }
      }
    },
    async syncSelectedAuditToProposal() {
      const proposal = this.selected as ProposalWithBinding | null
      const runId = proposal?.runId?.trim()
      if (!runId) return
      if (this.auditDetail?.run.id === runId) {
        this.selectedAuditId = runId
        return
      }
      if (this.audits.some((item) => item.id === runId)) {
        this.selectedAuditId = runId
        await this.selectAudit(runId)
        return
      }
      const client = getAiClient()
      if (!client.getAuditRun) return
      const selectionEpoch = (auditSelectionEpochs.get(this) ?? 0) + 1
      const expectedRunId = runId
      auditSelectionEpochs.set(this, selectionEpoch)
      const auditDetail = await client.getAuditRun(expectedRunId)
      const currentProposal = this.selected as ProposalWithBinding | null
      if (auditSelectionEpochs.get(this) !== selectionEpoch || currentProposal?.runId?.trim() !== expectedRunId) return
      if (auditDetail.run.id !== expectedRunId) throw new Error('审计详情响应与选择不匹配')
      if (!this.audits.some((item) => item.id === auditDetail.run.id)) {
        this.audits = [auditDetail.run, ...this.audits]
      }
      this.selectedAuditId = auditDetail.run.id
      this.auditDetail = auditDetail
    },
    replace(proposal: AiProposalPreview) {
      const index = this.proposals.findIndex((item) => item.id === proposal.id)
      if (index >= 0) this.proposals[index] = proposal
    },
    clearAuditCache() {
      auditLoadEpochs.set(this, (auditLoadEpochs.get(this) ?? 0) + 1)
      auditSelectionEpochs.set(this, (auditSelectionEpochs.get(this) ?? 0) + 1)
      nextEpoch(costLoadEpochs, this)
      this.$patch({
        audits: [],
        selectedAuditId: '',
        auditDetail: null,
        costs: [],
        auditTotal: 0,
        auditPage: 1,
        costsLoading: false,
        costsError: null,
      })
    },
    resetSession() {
      nextEpoch(sessionEpochs, this)
      proposalLoadEpochs.set(this, (proposalLoadEpochs.get(this) ?? 0) + 1)
      proposalSelectionEpochs.set(this, (proposalSelectionEpochs.get(this) ?? 0) + 1)
      auditLoadEpochs.set(this, (auditLoadEpochs.get(this) ?? 0) + 1)
      auditSelectionEpochs.set(this, (auditSelectionEpochs.get(this) ?? 0) + 1)
      nextEpoch(proposalMutationEpochs, this)
      nextEpoch(costLoadEpochs, this)
      this.$reset()
    },
  },
})
