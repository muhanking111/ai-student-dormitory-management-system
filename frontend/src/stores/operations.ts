import { defineStore } from 'pinia'
import {
  addRepairRecord,
  assignRepairOrder,
  createHygieneCheck,
  createNotice,
  createPaymentBill,
  createRepairOrder,
  deleteHygieneCheck,
  deleteNotice,
  fetchHygieneChecks,
  fetchNotices,
  fetchPaymentBills,
  fetchPaymentRecords,
  fetchRepairRecords,
  fetchRepairOrders,
  payPaymentBill,
  updateHygieneCheck,
  updateNotice,
} from '../api/operations'
import type {
  HygieneCheckInput,
  NoticeInput,
  OperationQuery,
  PaymentBillInput,
  PaymentRecordInput,
  PaymentRecordQuery,
  RepairOrderInput,
  RepairRecordInput,
  RepairRecordQuery,
} from '../api/operations'
import type { HygieneCheck, Notice, Payment, PaymentRecord, RepairOrder, RepairRecord } from '../types/dormitory'

interface OperationsState {
  repairOrders: RepairOrder[]
  repairTotal: number
  repairRecords: RepairRecord[]
  repairRecordTotal: number
  paymentBills: Payment[]
  paymentTotal: number
  paymentRecords: PaymentRecord[]
  paymentRecordTotal: number
  hygieneChecks: HygieneCheck[]
  hygieneTotal: number
  notices: Notice[]
  noticeTotal: number
  loading: boolean
  saving: boolean
  error: string | null
}

export const useOperationsStore = defineStore('operations', {
  state: (): OperationsState => ({
    repairOrders: [], repairTotal: 0,
    repairRecords: [], repairRecordTotal: 0,
    paymentBills: [], paymentTotal: 0,
    paymentRecords: [], paymentRecordTotal: 0,
    hygieneChecks: [], hygieneTotal: 0,
    notices: [], noticeTotal: 0,
    loading: false, saving: false, error: null,
  }),
  actions: {
    async loadRepairOrders(query: OperationQuery = {}) {
      return this.withLoading(async () => {
        const page = await fetchRepairOrders(query)
        this.repairOrders = page.records
        this.repairTotal = page.total
      }, '报修数据加载失败')
    },
    async createRepair(input: RepairOrderInput, query: OperationQuery) {
      return this.withSaving(async () => {
        await createRepairOrder(input)
        await this.loadRepairOrders(query)
      })
    },
    async assignRepair(id: number, assigneeUserId: number, query: OperationQuery) {
      return this.withSaving(async () => {
        await assignRepairOrder(id, assigneeUserId)
        await this.loadRepairOrders(query)
      })
    },
    async handleRepair(id: number, input: RepairRecordInput, query: OperationQuery) {
      return this.withSaving(async () => {
        await addRepairRecord(id, input)
        await this.loadRepairOrders(query)
      })
    },
    async loadRepairRecords(query: RepairRecordQuery = {}) {
      return this.withLoading(async () => {
        const page = await fetchRepairRecords(query)
        this.repairRecords = page.records
        this.repairRecordTotal = page.total
      }, '维修记录加载失败')
    },
    async loadPaymentBills(query: OperationQuery = {}) {
      return this.withLoading(async () => {
        const page = await fetchPaymentBills(query)
        this.paymentBills = page.records
        this.paymentTotal = page.total
      }, '费用数据加载失败')
    },
    async createPayment(input: PaymentBillInput, query: OperationQuery) {
      return this.withSaving(async () => {
        await createPaymentBill(input)
        await this.loadPaymentBills(query)
      })
    },
    async payBill(id: number, input: PaymentRecordInput, query: OperationQuery) {
      return this.withSaving(async () => {
        await payPaymentBill(id, input)
        await this.loadPaymentBills(query)
      })
    },
    async loadPaymentRecords(query: PaymentRecordQuery = {}) {
      return this.withLoading(async () => {
        const page = await fetchPaymentRecords(query)
        this.paymentRecords = page.records
        this.paymentRecordTotal = page.total
      }, '收费记录加载失败')
    },
    async loadHygieneChecks(query: OperationQuery = {}) {
      return this.withLoading(async () => {
        const page = await fetchHygieneChecks(query)
        this.hygieneChecks = page.records
        this.hygieneTotal = page.total
      }, '卫生检查加载失败')
    },
    async saveHygiene(id: number | null, input: HygieneCheckInput, query: OperationQuery) {
      return this.withSaving(async () => {
        if (id) await updateHygieneCheck(id, input)
        else await createHygieneCheck(input)
        await this.loadHygieneChecks(query)
      })
    },
    async removeHygiene(id: number, query: OperationQuery) {
      await deleteHygieneCheck(id)
      await this.loadHygieneChecks(query)
    },
    async loadNotices(query: OperationQuery = {}) {
      return this.withLoading(async () => {
        const page = await fetchNotices(query)
        this.notices = page.records
        this.noticeTotal = page.total
      }, '公告数据加载失败')
    },
    async saveNotice(id: number | null, input: NoticeInput, query: OperationQuery) {
      return this.withSaving(async () => {
        if (id) await updateNotice(id, input)
        else await createNotice(input)
        await this.loadNotices(query)
      })
    },
    async removeNotice(id: number, query: OperationQuery) {
      await deleteNotice(id)
      await this.loadNotices(query)
    },
    async withLoading(action: () => Promise<void>, fallbackMessage: string) {
      this.loading = true
      this.error = null
      try { await action() }
      catch (error) {
        this.error = error instanceof Error ? error.message : fallbackMessage
        throw error
      } finally { this.loading = false }
    },
    async withSaving(action: () => Promise<void>) {
      this.saving = true
      try { await action() }
      finally { this.saving = false }
    },
  },
})
