import { defineStore } from 'pinia'
import { getAiClient } from '../api/ai-client'
import type { AiRiskCase } from '../types/ai'

const OVERVIEW_PAGE_SIZE = 100
const MAX_OVERVIEW_PAGES = 100
const loadEpochs = new WeakMap<object, number>()
const overviewRequestEpochs = new WeakMap<object, number>()
const actionEpochs = new WeakMap<object, Map<string, number>>()

function currentEpoch(epochs: WeakMap<object, number>, target: object) {
  return epochs.get(target) ?? 0
}

function nextEpoch(epochs: WeakMap<object, number>, target: object) {
  const next = currentEpoch(epochs, target) + 1
  epochs.set(target, next)
  return next
}

function nextActionEpoch(target: object, id: string) {
  const epochs = actionEpochs.get(target) ?? new Map<string, number>()
  const next = (epochs.get(id) ?? 0) + 1
  epochs.set(id, next)
  actionEpochs.set(target, epochs)
  return next
}

function currentActionEpoch(target: object, id: string) {
  return actionEpochs.get(target)?.get(id) ?? 0
}

export const useAiRiskStore = defineStore('aiRisk', {
  state: () => ({
    cases: [] as AiRiskCase[],
    overviewCases: [] as AiRiskCase[],
    total: 0,
    page: 1,
    pageSize: 10,
    selectedId: '' as string,
    loading: false,
    overviewLoading: false,
    error: null as string | null,
    overviewError: null as string | null,
    sessionEpoch: 0,
  }),
  getters: { selected: (state) => state.cases.find((item) => item.id === state.selectedId) ?? null },
  actions: {
    async load(filters: { page?: number; pageSize?: number; type?: string; state?: string; keyword?: string } = {}) {
      const client = getAiClient()
      if (!client.listRiskCases) return
      const sessionEpoch = this.sessionEpoch
      const requestEpoch = nextEpoch(loadEpochs, this)
      this.loading = true
      this.error = null
      try {
        const result = await client.listRiskCases(filters)
        if (this.sessionEpoch !== sessionEpoch || currentEpoch(loadEpochs, this) !== requestEpoch) return
        this.cases = result.records
        this.total = result.total
        this.page = result.page
        this.pageSize = result.pageSize
        if (!this.cases.some((item) => item.id === this.selectedId)) {
          this.selectedId = this.cases[0]?.id ?? ''
        }
      } catch (error) {
        if (this.sessionEpoch !== sessionEpoch || currentEpoch(loadEpochs, this) !== requestEpoch) return
        this.error = error instanceof Error ? error.message : '风险数据加载失败'
      } finally {
        if (this.sessionEpoch === sessionEpoch && currentEpoch(loadEpochs, this) === requestEpoch) this.loading = false
      }
    },
    async loadOverview() {
      const client = getAiClient()
      if (!client.listRiskCases) {
        this.overviewCases = []
        this.overviewError = '当前客户端不支持风险总览'
        return
      }
      const epoch = (overviewRequestEpochs.get(this) ?? 0) + 1
      overviewRequestEpochs.set(this, epoch)
      const sessionEpoch = this.sessionEpoch
      this.overviewLoading = true
      this.overviewError = null
      try {
        const first = await client.listRiskCases({ page: 1, pageSize: OVERVIEW_PAGE_SIZE })
        if (this.sessionEpoch !== sessionEpoch || currentEpoch(overviewRequestEpochs, this) !== epoch) return
        const pageCount = Math.max(1, Math.ceil(first.total / OVERVIEW_PAGE_SIZE))
        if (pageCount > MAX_OVERVIEW_PAGES) throw new Error('风险总览数据超出安全读取范围')
        const records = [...first.records]
        for (let page = 2; page <= pageCount; page += 1) {
          const next = await client.listRiskCases({ page, pageSize: OVERVIEW_PAGE_SIZE })
          if (this.sessionEpoch !== sessionEpoch || currentEpoch(overviewRequestEpochs, this) !== epoch) return
          if (next.total !== first.total || next.page !== page || next.pageSize !== OVERVIEW_PAGE_SIZE) {
            throw new Error('风险总览分页快照已变化，请重试')
          }
          records.push(...next.records)
        }
        if (records.length !== first.total) throw new Error('风险总览分页结果不完整，请重试')
        if (this.sessionEpoch === sessionEpoch && currentEpoch(overviewRequestEpochs, this) === epoch) this.overviewCases = records
      } catch (error) {
        if (this.sessionEpoch === sessionEpoch && currentEpoch(overviewRequestEpochs, this) === epoch) {
          this.overviewCases = []
          this.overviewError = error instanceof Error ? error.message : '风险总览加载失败'
        }
      } finally {
        if (this.sessionEpoch === sessionEpoch && currentEpoch(overviewRequestEpochs, this) === epoch) this.overviewLoading = false
      }
    },
    async act(id: string, action: 'acknowledge' | 'resolve' | 'dismiss', detail: string, dueAt?: string) {
      const client = getAiClient()
      if (!client.updateRiskCase) return
      const current = this.cases.find((item) => item.id === id)
      if (!current) throw new Error('风险案例不可见')
      const normalizedDetail = detail.trim()
      if (!normalizedDetail) throw new Error('处置说明不能为空')
      const sessionEpoch = this.sessionEpoch
      const actionEpoch = nextActionEpoch(this, id)
      const changed = await client.updateRiskCase(id, action, {
        caseVersion: current.caseVersion,
        detail: normalizedDetail,
        ...(dueAt ? { dueAt } : {}),
      })
      if (this.sessionEpoch !== sessionEpoch || currentActionEpoch(this, id) !== actionEpoch) return
      if (changed.id !== id) throw new Error('风险处置响应与案例不匹配')
      const index = this.cases.findIndex((item) => item.id === id)
      if (index >= 0) this.cases[index] = changed
      const overviewIndex = this.overviewCases.findIndex((item) => item.id === id)
      if (overviewIndex >= 0) this.overviewCases[overviewIndex] = changed
    },
    resetSession() {
      const nextSessionEpoch = this.sessionEpoch + 1
      nextEpoch(loadEpochs, this)
      nextEpoch(overviewRequestEpochs, this)
      actionEpochs.set(this, new Map())
      this.$reset()
      this.sessionEpoch = nextSessionEpoch
    },
  },
})
