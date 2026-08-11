import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createDormitory, deleteDormitory, fetchDormitoryData, updateDormitory } from '../api/dormitory'
import { useDormitoryStore } from '../stores/dormitory'

vi.mock('../api/dormitory', () => ({
  fetchDormitoryData: vi.fn(),
  createDormitory: vi.fn(),
  updateDormitory: vi.fn(),
  deleteDormitory: vi.fn(),
}))

describe('dormitory store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('只在接口成功后写入业务数据并统计床位', async () => {
    vi.mocked(fetchDormitoryData).mockResolvedValue({
      statistics: [{ title: '宿舍总数', value: 1, unit: '间', change: '实时数据', color: 'blue', icon: 'home' }],
      checkInTrend: [{ date: '07-10', value: 1 }],
      dormitories: [{ id: 1, name: '测试宿舍', type: '男生宿舍', building: '1号楼', beds: 4, occupied: 2, vacant: 2, status: '入住中' }],
      students: [{ id: 1, studentNo: '2026001', name: '测试学生', gender: '男', college: '计算机学院', grade: '2026', phone: '13800000000', checkInStatus: '已入住' }],
      applications: [],
      repairs: [],
      payments: [],
      hygieneChecks: [],
      notices: [],
      pendingCounts: { repairs: 8, applications: 3, payments: 5 },
    })
    const store = useDormitoryStore()

    expect(store.dormitories).toHaveLength(0)
    await store.loadAll()

    expect(store.totalBeds).toBe(4)
    expect(store.occupiedBeds).toBe(2)
    expect(store.searchStudents('测试学生')).toHaveLength(1)
    expect(store.pendingCounts).toEqual({ repairs: 8, applications: 3, payments: 5 })
  })

  it('新增宿舍后强制刷新接口数据', async () => {
    const store = useDormitoryStore()
    vi.mocked(createDormitory).mockResolvedValue({ id: 2, name: '新宿舍', type: '女生宿舍', building: '2号楼', beds: 6, occupied: 0, vacant: 6, status: '入住中' })
    vi.mocked(fetchDormitoryData).mockResolvedValue({
      statistics: [],
      checkInTrend: [],
      dormitories: [{ id: 2, name: '新宿舍', type: '女生宿舍', building: '2号楼', beds: 6, occupied: 0, vacant: 6, status: '入住中' }],
      students: [], applications: [], repairs: [], payments: [], hygieneChecks: [], notices: [],
      pendingCounts: { repairs: 0, applications: 0, payments: 0 },
    })

    await store.createDormitory({ name: '新宿舍', type: '女生宿舍', building: '2号楼', beds: 6, occupied: 0 })

    expect(createDormitory).toHaveBeenCalledOnce()
    expect(store.dormitories[0]?.name).toBe('新宿舍')
  })

  it('已加载的数据不会重复请求，除非显式强制刷新', async () => {
    const store = useDormitoryStore()
    vi.mocked(fetchDormitoryData).mockResolvedValue({
      statistics: [], checkInTrend: [], dormitories: [], students: [],
      applications: [], repairs: [], payments: [], hygieneChecks: [], notices: [],
      pendingCounts: { repairs: 0, applications: 0, payments: 0 },
    })

    await store.loadAll()
    await store.loadAll()
    await store.loadAll(true)

    expect(fetchDormitoryData).toHaveBeenCalledTimes(2)
  })

  it('接口失败时保留错误状态并复位加载状态', async () => {
    const store = useDormitoryStore()
    const failure = new Error('网络异常')
    vi.mocked(fetchDormitoryData).mockRejectedValue(failure)

    await expect(store.loadAll()).rejects.toThrow('网络异常')

    expect(store.error).toBe('网络异常')
    expect(store.loading).toBe(false)
    expect(store.loaded).toBe(false)
  })

  it('更新和删除宿舍后都会强制刷新数据', async () => {
    const store = useDormitoryStore()
    vi.mocked(updateDormitory).mockResolvedValue({
      id: 1, name: '更新宿舍', type: '男生宿舍', building: '1号楼', beds: 4, occupied: 1, vacant: 3, status: '入住中',
    })
    vi.mocked(deleteDormitory).mockResolvedValue(undefined)
    vi.mocked(fetchDormitoryData).mockResolvedValue({
      statistics: [], checkInTrend: [], dormitories: [], students: [],
      applications: [], repairs: [], payments: [], hygieneChecks: [], notices: [],
      pendingCounts: { repairs: 0, applications: 0, payments: 0 },
    })

    await store.updateDormitory(1, { name: '更新宿舍', type: '男生宿舍', building: '1号楼', beds: 4, occupied: 1 })
    await store.deleteDormitory(1)

    expect(updateDormitory).toHaveBeenCalledWith(1, {
      name: '更新宿舍', type: '男生宿舍', building: '1号楼', beds: 4, occupied: 1,
    })
    expect(deleteDormitory).toHaveBeenCalledWith(1)
    expect(fetchDormitoryData).toHaveBeenCalledTimes(2)
  })
})
