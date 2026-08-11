import { defineStore } from 'pinia'
import {
  approveCheckInApplication,
  checkoutRecord,
  createCheckInApplication,
  createCheckInRecord,
  createStudent,
  deleteStudent,
  fetchCheckInApplications,
  fetchCheckInRecords,
  fetchStudents,
  rejectCheckInApplication,
  updateStudent,
} from '../api/checkin'
import type {
  CheckInApplicationQuery,
  CheckInRecordQuery,
  StudentInput,
  StudentQuery,
} from '../api/checkin'
import type { CheckInApplication, CheckInRecord, Student } from '../types/dormitory'

interface CheckInState {
  students: Student[]
  studentTotal: number
  applications: CheckInApplication[]
  applicationTotal: number
  records: CheckInRecord[]
  recordTotal: number
  loading: boolean
  saving: boolean
  error: string | null
}

export const useCheckInStore = defineStore('checkin', {
  state: (): CheckInState => ({
    students: [],
    studentTotal: 0,
    applications: [],
    applicationTotal: 0,
    records: [],
    recordTotal: 0,
    loading: false,
    saving: false,
    error: null,
  }),
  actions: {
    async loadStudents(query: StudentQuery = {}) {
      return this.withLoading(async () => {
        const page = await fetchStudents(query)
        this.students = page.records
        this.studentTotal = page.total
      }, '学生数据加载失败')
    },
    async saveStudent(id: number | null, input: StudentInput, query: StudentQuery) {
      this.saving = true
      try {
        if (id) await updateStudent(id, input)
        else await createStudent(input)
        await this.loadStudents(query)
      } finally {
        this.saving = false
      }
    },
    async removeStudent(id: number, query: StudentQuery) {
      await deleteStudent(id)
      await this.loadStudents(query)
    },
    async loadApplications(query: CheckInApplicationQuery = {}) {
      return this.withLoading(async () => {
        const page = await fetchCheckInApplications(query)
        this.applications = page.records
        this.applicationTotal = page.total
      }, '入住申请加载失败')
    },
    async submitApplication(studentId: number, dormitoryId: number, remark: string | undefined, query: CheckInApplicationQuery) {
      this.saving = true
      try {
        await createCheckInApplication({ studentId, dormitoryId, remark })
        await this.loadApplications(query)
      } finally {
        this.saving = false
      }
    },
    async approveApplication(id: number, bedId: number, remark: string | undefined, query: CheckInApplicationQuery) {
      this.saving = true
      try {
        await approveCheckInApplication(id, { bedId, remark })
        await this.loadApplications(query)
      } finally {
        this.saving = false
      }
    },
    async rejectApplication(id: number, remark: string | undefined, query: CheckInApplicationQuery) {
      this.saving = true
      try {
        await rejectCheckInApplication(id, { remark })
        await this.loadApplications(query)
      } finally {
        this.saving = false
      }
    },
    async loadRecords(query: CheckInRecordQuery = {}) {
      return this.withLoading(async () => {
        const page = await fetchCheckInRecords(query)
        this.records = page.records
        this.recordTotal = page.total
      }, '入住记录加载失败')
    },
    async assign(studentId: number, bedId: number, remark: string | undefined, query: CheckInRecordQuery) {
      this.saving = true
      try {
        await createCheckInRecord({ studentId, bedId, remark })
        await this.loadRecords(query)
      } finally {
        this.saving = false
      }
    },
    async checkout(id: number, remark: string | undefined, query: CheckInRecordQuery) {
      this.saving = true
      try {
        await checkoutRecord(id, { remark })
        await this.loadRecords(query)
      } finally {
        this.saving = false
      }
    },
    async withLoading(action: () => Promise<void>, fallbackMessage: string) {
      this.loading = true
      this.error = null
      try {
        await action()
      } catch (error) {
        this.error = error instanceof Error ? error.message : fallbackMessage
        throw error
      } finally {
        this.loading = false
      }
    },
  },
})
