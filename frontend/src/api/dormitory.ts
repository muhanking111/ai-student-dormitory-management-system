import type {
  CheckInApplication,
  CheckInTrendPoint,
  Dormitory,
  HygieneCheck,
  Notice,
  Payment,
  RepairOrder,
  StatisticCard,
  Student,
} from '../types/dormitory'
import { apiRequest } from './client'
import type { PageResponse } from './rbac'

export interface DormitoryData {
  statistics: StatisticCard[]
  checkInTrend: CheckInTrendPoint[]
  dormitories: Dormitory[]
  students: Student[]
  applications: CheckInApplication[]
  repairs: RepairOrder[]
  payments: Payment[]
  hygieneChecks: HygieneCheck[]
  notices: Notice[]
  pendingCounts: PendingCounts
}

export interface PendingCounts {
  repairs: number
  applications: number
  payments: number
}

export interface DormitoryInput {
  name: string
  type: string
  buildingId?: number
  building?: string
  beds: number
  occupied: number
}

export interface DormitoryQuery {
  page?: number
  pageSize?: number
  keyword?: string
  buildingId?: number
  type?: string
  status?: string
}

function toQueryString(query: DormitoryQuery) {
  const params = new URLSearchParams()
  params.set('page', String(query.page ?? 1))
  params.set('pageSize', String(query.pageSize ?? 10))
  if (query.keyword) params.set('keyword', query.keyword)
  if (query.buildingId) params.set('buildingId', String(query.buildingId))
  if (query.type) params.set('type', query.type)
  if (query.status) params.set('status', query.status)
  return params.toString()
}

export function fetchDormitoryPage(query: DormitoryQuery = {}) {
  return apiRequest<PageResponse<Dormitory>>(`/api/dormitories?${toQueryString(query)}`)
}

function fetchPage<T>(path: string, query: DormitoryQuery = {}) {
  return apiRequest<PageResponse<T>>(`${path}?${toQueryString(query)}`)
}

async function fetchAllDormitories() {
  const pageSize = 100
  const firstPage = await fetchDormitoryPage({ page: 1, pageSize })
  const pageCount = Math.ceil(firstPage.total / pageSize)
  if (pageCount <= 1) return firstPage.records

  const remainingPages = await Promise.all(
    Array.from({ length: pageCount - 1 }, (_, index) =>
      fetchDormitoryPage({ page: index + 2, pageSize })),
  )
  return [firstPage, ...remainingPages].flatMap((page) => page.records)
}

async function fetchPendingCounts(allowed: (permission: string) => boolean): Promise<PendingCounts> {
  const total = <T>(path: string, status: string) =>
    fetchPage<T>(path, { page: 1, pageSize: 1, status }).then((page) => page.total)
  const [pendingRepairs, processingRepairs, pendingApplications, unpaidPayments, partialPayments] = await Promise.all([
    allowed('repair:read') ? total<RepairOrder>('/api/repair-orders', '待处理') : Promise.resolve(0),
    allowed('repair:read') ? total<RepairOrder>('/api/repair-orders', '处理中') : Promise.resolve(0),
    allowed('checkin:read') ? total<CheckInApplication>('/api/check-in-applications', '待审核') : Promise.resolve(0),
    allowed('payment:read') ? total<Payment>('/api/payment-bills', '未缴') : Promise.resolve(0),
    allowed('payment:read') ? total<Payment>('/api/payment-bills', '部分缴') : Promise.resolve(0),
  ])
  return {
    repairs: pendingRepairs + processingRepairs,
    applications: pendingApplications,
    payments: unpaidPayments + partialPayments,
  }
}

export async function fetchDormitoryData(permissions: string[] = []): Promise<DormitoryData> {
  const allowed = (permission: string) => permissions.includes('*') || permissions.includes(permission)
  const [statistics, checkInTrend, dormitories, students, applications, repairs, payments, hygieneChecks, notices, pendingCounts] = await Promise.all([
    allowed('dashboard:read') ? apiRequest<StatisticCard[]>('/api/dashboard/statistics') : Promise.resolve([]),
    allowed('dashboard:read') ? apiRequest<CheckInTrendPoint[]>('/api/dashboard/check-in-trend') : Promise.resolve([]),
    allowed('dormitory:read') ? fetchAllDormitories() : Promise.resolve([]),
    allowed('student:read') ? fetchPage<Student>('/api/students', { page: 1, pageSize: 100 }).then((page) => page.records) : Promise.resolve([]),
    allowed('checkin:read') ? fetchPage<CheckInApplication>('/api/check-in-applications', { page: 1, pageSize: 100 }).then((page) => page.records) : Promise.resolve([]),
    allowed('repair:read') ? fetchPage<RepairOrder>('/api/repair-orders', { page: 1, pageSize: 100 }).then((page) => page.records) : Promise.resolve([]),
    allowed('payment:read') ? fetchPage<Payment>('/api/payment-bills', { page: 1, pageSize: 100 }).then((page) => page.records) : Promise.resolve([]),
    allowed('hygiene:read') ? fetchPage<HygieneCheck>('/api/hygiene-checks', { page: 1, pageSize: 100 }).then((page) => page.records) : Promise.resolve([]),
    allowed('notice:read') ? fetchPage<Notice>('/api/notices', { page: 1, pageSize: 5, status: '已发布' }).then((page) => page.records) : Promise.resolve([]),
    fetchPendingCounts(allowed),
  ])
  return { statistics, checkInTrend, dormitories, students, applications, repairs, payments, hygieneChecks, notices, pendingCounts }
}

export function createDormitory(input: DormitoryInput) {
  return apiRequest<Dormitory>('/api/dormitories', {
    method: 'POST',
    body: JSON.stringify(input),
  })
}

export function updateDormitory(id: number, input: DormitoryInput) {
  return apiRequest<Dormitory>(`/api/dormitories/${id}`, {
    method: 'PUT',
    body: JSON.stringify(input),
  })
}

export function deleteDormitory(id: number) {
  return apiRequest<void>(`/api/dormitories/${id}`, { method: 'DELETE' })
}
