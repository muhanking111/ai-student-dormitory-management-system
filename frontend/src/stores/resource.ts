import { defineStore } from 'pinia'
import { createDormitory, deleteDormitory, fetchDormitoryPage, updateDormitory } from '../api/dormitory'
import type { DormitoryInput, DormitoryQuery } from '../api/dormitory'
import {
  createBuilding,
  deleteBuilding,
  fetchBeds,
  fetchBuildingOptions,
  fetchBuildings,
  updateBedStatus,
  updateBuilding,
} from '../api/resource'
import type { Bed, Building, BuildingInput, ResourceQuery } from '../api/resource'
import type { Dormitory } from '../types/dormitory'

interface ResourceState {
  buildings: Building[]
  buildingOptions: Building[]
  buildingTotal: number
  dormitories: Dormitory[]
  dormitoryTotal: number
  beds: Bed[]
  bedTotal: number
  loading: boolean
  saving: boolean
  error: string | null
}

export const useResourceStore = defineStore('resource', {
  state: (): ResourceState => ({
    buildings: [], buildingOptions: [], buildingTotal: 0,
    dormitories: [], dormitoryTotal: 0,
    beds: [], bedTotal: 0,
    loading: false, saving: false, error: null,
  }),
  actions: {
    async loadBuildings(query: ResourceQuery = {}) {
      this.loading = true
      this.error = null
      try {
        const [page, options] = await Promise.all([fetchBuildings(query), fetchBuildingOptions()])
        this.buildings = page.records
        this.buildingTotal = page.total
        this.buildingOptions = options
      } catch (error) {
        this.error = error instanceof Error ? error.message : '楼栋数据加载失败'
        throw error
      } finally { this.loading = false }
    },
    async loadDormitories(query: DormitoryQuery = {}) {
      this.loading = true
      this.error = null
      try {
        const [page, options] = await Promise.all([fetchDormitoryPage(query), fetchBuildingOptions()])
        this.dormitories = page.records
        this.dormitoryTotal = page.total
        this.buildingOptions = options
      } catch (error) {
        this.error = error instanceof Error ? error.message : '宿舍数据加载失败'
        throw error
      } finally { this.loading = false }
    },
    async loadBeds(query: ResourceQuery = {}) {
      this.loading = true
      this.error = null
      try {
        const [page, buildings, dormitories] = await Promise.all([
          fetchBeds(query),
          fetchBuildingOptions(),
          fetchDormitoryPage({ page: 1, pageSize: 100, buildingId: query.buildingId }),
        ])
        this.beds = page.records
        this.bedTotal = page.total
        this.buildingOptions = buildings
        this.dormitories = dormitories.records
      } catch (error) {
        this.error = error instanceof Error ? error.message : '床位数据加载失败'
        throw error
      } finally { this.loading = false }
    },
    async saveBuilding(id: number | null, input: BuildingInput, query: ResourceQuery) {
      this.saving = true
      try {
        if (id) await updateBuilding(id, input)
        else await createBuilding(input)
        await this.loadBuildings(query)
      } finally { this.saving = false }
    },
    async removeBuilding(id: number, query: ResourceQuery) {
      await deleteBuilding(id)
      await this.loadBuildings(query)
    },
    async saveDormitory(id: number | null, input: DormitoryInput, query: DormitoryQuery) {
      this.saving = true
      try {
        if (id) await updateDormitory(id, input)
        else await createDormitory(input)
        await this.loadDormitories(query)
      } finally { this.saving = false }
    },
    async removeDormitory(id: number, query: DormitoryQuery) {
      await deleteDormitory(id)
      await this.loadDormitories(query)
    },
    async setBedStatus(id: number, status: Bed['status'], query: ResourceQuery) {
      await updateBedStatus(id, status)
      await this.loadBeds(query)
    },
  },
})
