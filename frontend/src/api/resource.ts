import { apiRequest } from './client'
import type { Dormitory } from '../types/dormitory'
import type { PageResponse } from './rbac'

export interface Building {
  id: number
  code: string
  name: string
  genderType: string
  floors: number
  manager: string
  status: '启用' | '停用'
}

export interface Bed {
  id: number
  bedNo: string
  status: '空闲' | '已占用' | '维修中' | '停用'
  studentId?: number
  studentName?: string
  dormitoryId: number
  dormitoryName: string
  buildingId: number
  buildingName: string
}

export type BuildingInput = Omit<Building, 'id'>

export interface ResourceQuery {
  page?: number
  pageSize?: number
  keyword?: string
  status?: string
  buildingId?: number
  dormitoryId?: number
}

function toQueryString(query: ResourceQuery) {
  const params = new URLSearchParams()
  params.set('page', String(query.page ?? 1))
  params.set('pageSize', String(query.pageSize ?? 10))
  if (query.keyword) params.set('keyword', query.keyword)
  if (query.status) params.set('status', query.status)
  if (query.buildingId) params.set('buildingId', String(query.buildingId))
  if (query.dormitoryId) params.set('dormitoryId', String(query.dormitoryId))
  return params.toString()
}

export function fetchBuildings(query: ResourceQuery = {}) {
  return apiRequest<PageResponse<Building>>(`/api/buildings?${toQueryString(query)}`)
}

export function fetchBuildingOptions() {
  return apiRequest<Building[]>('/api/buildings/options')
}

export function createBuilding(input: BuildingInput) {
  return apiRequest<Building>('/api/buildings', { method: 'POST', body: JSON.stringify(input) })
}

export function updateBuilding(id: number, input: BuildingInput) {
  return apiRequest<Building>(`/api/buildings/${id}`, { method: 'PUT', body: JSON.stringify(input) })
}

export function deleteBuilding(id: number) {
  return apiRequest<void>(`/api/buildings/${id}`, { method: 'DELETE' })
}

export function fetchBeds(query: ResourceQuery = {}) {
  return apiRequest<PageResponse<Bed>>(`/api/beds?${toQueryString(query)}`)
}

export function updateBedStatus(id: number, status: Bed['status']) {
  return apiRequest<Bed>(`/api/beds/${id}/status`, { method: 'PATCH', body: JSON.stringify({ status }) })
}

export type { Dormitory }
