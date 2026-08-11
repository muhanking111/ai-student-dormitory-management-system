import { apiRequest } from './client'
import type { PageResponse } from './rbac'
import type { CheckInApplication, CheckInRecord, Student } from '../types/dormitory'

export interface StudentInput {
  studentNo: string
  name: string
  gender: string
  college: string
  grade: string
  phone: string
}

export interface StudentQuery {
  page?: number
  pageSize?: number
  keyword?: string
  college?: string
  grade?: string
  checkInStatus?: string
}

export interface CheckInApplicationQuery {
  page?: number
  pageSize?: number
  keyword?: string
  status?: string
  studentId?: number
  dormitoryId?: number
}

export interface CheckInRecordQuery {
  page?: number
  pageSize?: number
  keyword?: string
  status?: string
  dormitoryId?: number
}

function toQueryString(query: object) {
  const values = query as Record<string, string | number | undefined>
  const params = new URLSearchParams()
  params.set('page', String(values.page ?? 1))
  params.set('pageSize', String(values.pageSize ?? 10))
  Object.entries(values).forEach(([key, value]) => {
    if (key !== 'page' && key !== 'pageSize' && value !== undefined && value !== '') {
      params.set(key, String(value))
    }
  })
  return params.toString()
}

export function fetchStudents(query: StudentQuery = {}) {
  return apiRequest<PageResponse<Student>>(`/api/students?${toQueryString(query)}`)
}

export function createStudent(input: StudentInput) {
  return apiRequest<Student>('/api/students', { method: 'POST', body: JSON.stringify(input) })
}

export function updateStudent(id: number, input: StudentInput) {
  return apiRequest<Student>(`/api/students/${id}`, { method: 'PATCH', body: JSON.stringify(input) })
}

export function deleteStudent(id: number) {
  return apiRequest<void>(`/api/students/${id}`, { method: 'DELETE' })
}

export function fetchCheckInApplications(query: CheckInApplicationQuery = {}) {
  return apiRequest<PageResponse<CheckInApplication>>(`/api/check-in-applications?${toQueryString(query)}`)
}

export function createCheckInApplication(input: { studentId: number; dormitoryId: number; remark?: string }) {
  return apiRequest<CheckInApplication>('/api/check-in-applications', { method: 'POST', body: JSON.stringify(input) })
}

export function approveCheckInApplication(id: number, input: { bedId: number; remark?: string }) {
  return apiRequest<CheckInApplication>(`/api/check-in-applications/${id}/approve`, { method: 'POST', body: JSON.stringify(input) })
}

export function rejectCheckInApplication(id: number, input: { remark?: string }) {
  return apiRequest<CheckInApplication>(`/api/check-in-applications/${id}/reject`, { method: 'POST', body: JSON.stringify(input) })
}

export function fetchCheckInRecords(query: CheckInRecordQuery = {}) {
  return apiRequest<PageResponse<CheckInRecord>>(`/api/check-in-records?${toQueryString(query)}`)
}

export function createCheckInRecord(input: { studentId: number; bedId: number; remark?: string }) {
  return apiRequest<CheckInRecord>('/api/check-in-records', { method: 'POST', body: JSON.stringify(input) })
}

export function checkoutRecord(id: number, input: { remark?: string }) {
  return apiRequest<CheckInRecord>(`/api/check-in-records/${id}/checkout`, { method: 'POST', body: JSON.stringify(input) })
}
