import { defineStore } from 'pinia'
import {
  createDormitory as createDormitoryRequest,
  deleteDormitory as deleteDormitoryRequest,
  fetchDormitoryData,
  updateDormitory as updateDormitoryRequest,
} from '../api/dormitory'
import type { DormitoryInput, PendingCounts } from '../api/dormitory'
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
import { filterByKeyword } from '../utils/filters'

interface DormitoryState {
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
  loading: boolean
  error: string | null
  loaded: boolean
}

export const useDormitoryStore = defineStore('dormitory', {
  state: (): DormitoryState => ({
    statistics: [],
    checkInTrend: [],
    dormitories: [],
    students: [],
    applications: [],
    repairs: [],
    payments: [],
    hygieneChecks: [],
    notices: [],
    pendingCounts: { repairs: 0, applications: 0, payments: 0 },
    loading: false,
    error: null,
    loaded: false,
  }),
  getters: {
    totalBeds: (state) => state.dormitories.reduce((sum, item) => sum + item.beds, 0),
    occupiedBeds: (state) => state.dormitories.reduce((sum, item) => sum + item.occupied, 0),
  },
  actions: {
    async loadAll(force = false, permissions: string[] = []) {
      if (this.loaded && !force) return
      this.loading = true
      this.error = null
      try {
        const data = await fetchDormitoryData(permissions)
        this.$patch({ ...data, loaded: true })
      } catch (error) {
        this.error = error instanceof Error ? error.message : '数据加载失败'
        throw error
      } finally {
        this.loading = false
      }
    },
    async createDormitory(input: DormitoryInput) {
      await createDormitoryRequest(input)
      await this.loadAll(true)
    },
    async updateDormitory(id: number, input: DormitoryInput) {
      await updateDormitoryRequest(id, input)
      await this.loadAll(true)
    },
    async deleteDormitory(id: number) {
      await deleteDormitoryRequest(id)
      await this.loadAll(true)
    },
    searchStudents(keyword: string) {
      return filterByKeyword(this.students, keyword, ['studentNo', 'name', 'college'])
    },
  },
})
