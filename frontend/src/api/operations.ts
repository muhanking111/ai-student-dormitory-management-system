import { apiRequest } from './client'
import type { PageResponse } from './rbac'
import type { HygieneCheck, Notice, Payment, PaymentRecord, RepairOrder, RepairRecord } from '../types/dormitory'

export interface OperationQuery {
  page?: number
  pageSize?: number
  keyword?: string
  type?: string
  status?: string
  result?: string
}

export interface RepairOrderInput {
  reporter: string
  location: string
  type: string
  description?: string
  assigneeUserId?: number
}

export interface RepairRecordInput {
  handler?: string
  content: string
  cost: number
  status: '处理中' | '已完成'
}

export interface RepairRecordQuery extends OperationQuery {
  repairOrderId?: number
}

export interface PaymentRecordQuery extends OperationQuery {
  paymentId?: number
}

export interface PaymentBillInput {
  studentNo: string
  name: string
  type: string
  amountDue: number
  deadline: string
}

export interface PaymentRecordInput {
  amount: number
  method: string
}

export interface HygieneCheckInput {
  dormitory: string
  building: string
  inspector: string
  score: number
  remark?: string
}

export interface NoticeInput {
  title: string
  type: string
  publisher: string
  status: '草稿' | '已发布'
  content?: string
}

function toQueryString(query: object) {
  const values = query as Record<string, string | number | undefined>
  const params = new URLSearchParams()
  params.set('page', String(values.page ?? 1))
  params.set('pageSize', String(values.pageSize ?? 10))
  Object.entries(values).forEach(([key, value]) => {
    if (key !== 'page' && key !== 'pageSize' && value !== undefined && value !== '') params.set(key, String(value))
  })
  return params.toString()
}

export function fetchRepairOrders(query: OperationQuery = {}) {
  return apiRequest<PageResponse<RepairOrder>>(`/api/repair-orders?${toQueryString(query)}`)
}

export function createRepairOrder(input: RepairOrderInput) {
  return apiRequest<RepairOrder>('/api/repair-orders', { method: 'POST', body: JSON.stringify(input) })
}

export function addRepairRecord(id: number, input: RepairRecordInput) {
  return apiRequest<RepairOrder>(`/api/repair-orders/${id}/records`, { method: 'POST', body: JSON.stringify(input) })
}

export function assignRepairOrder(id: number, assigneeUserId: number) {
  return apiRequest<RepairOrder>(`/api/repair-orders/${id}/assignee`, {
    method: 'PATCH',
    body: JSON.stringify({ assigneeUserId }),
  })
}

export function fetchRepairRecords(query: RepairRecordQuery = {}) {
  return apiRequest<PageResponse<RepairRecord>>(`/api/repair-records?${toQueryString(query)}`)
}

export function fetchPaymentBills(query: OperationQuery = {}) {
  return apiRequest<PageResponse<Payment>>(`/api/payment-bills?${toQueryString(query)}`)
}

export function createPaymentBill(input: PaymentBillInput) {
  return apiRequest<Payment>('/api/payment-bills', { method: 'POST', body: JSON.stringify(input) })
}

export function payPaymentBill(id: number, input: PaymentRecordInput) {
  return apiRequest<Payment>(`/api/payment-bills/${id}/payments`, { method: 'POST', body: JSON.stringify(input) })
}

export function fetchPaymentRecords(query: PaymentRecordQuery = {}) {
  return apiRequest<PageResponse<PaymentRecord>>(`/api/payment-records?${toQueryString(query)}`)
}

export function fetchHygieneChecks(query: OperationQuery = {}) {
  return apiRequest<PageResponse<HygieneCheck>>(`/api/hygiene-checks?${toQueryString(query)}`)
}

export function createHygieneCheck(input: HygieneCheckInput) {
  return apiRequest<HygieneCheck>('/api/hygiene-checks', { method: 'POST', body: JSON.stringify(input) })
}

export function updateHygieneCheck(id: number, input: HygieneCheckInput) {
  return apiRequest<HygieneCheck>(`/api/hygiene-checks/${id}`, { method: 'PATCH', body: JSON.stringify(input) })
}

export function deleteHygieneCheck(id: number) {
  return apiRequest<void>(`/api/hygiene-checks/${id}`, { method: 'DELETE' })
}

export function fetchNotices(query: OperationQuery = {}) {
  return apiRequest<PageResponse<Notice>>(`/api/notices?${toQueryString(query)}`)
}

export function createNotice(input: NoticeInput) {
  return apiRequest<Notice>('/api/notices', { method: 'POST', body: JSON.stringify(input) })
}

export function updateNotice(id: number, input: NoticeInput) {
  return apiRequest<Notice>(`/api/notices/${id}`, { method: 'PATCH', body: JSON.stringify(input) })
}

export function deleteNotice(id: number) {
  return apiRequest<void>(`/api/notices/${id}`, { method: 'DELETE' })
}
