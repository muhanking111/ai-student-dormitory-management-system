import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  addRepairRecord,
  assignRepairOrder,
  createNotice,
  createPaymentBill,
  createRepairOrder,
  deleteHygieneCheck,
  deleteNotice,
  fetchPaymentBills,
  fetchPaymentRecords,
  fetchRepairOrders,
  fetchRepairRecords,
  fetchNotices,
  payPaymentBill,
  createHygieneCheck,
  fetchHygieneChecks,
  updateHygieneCheck,
  updateNotice,
} from '../api/operations'
import { useOperationsStore } from '../stores/operations'

vi.mock('../api/operations', () => ({
  fetchRepairOrders: vi.fn(),
  createRepairOrder: vi.fn(),
  addRepairRecord: vi.fn(),
  assignRepairOrder: vi.fn(),
  fetchRepairRecords: vi.fn(),
  fetchPaymentBills: vi.fn(),
  fetchPaymentRecords: vi.fn(),
  createPaymentBill: vi.fn(),
  payPaymentBill: vi.fn(),
  fetchHygieneChecks: vi.fn(),
  createHygieneCheck: vi.fn(),
  updateHygieneCheck: vi.fn(),
  deleteHygieneCheck: vi.fn(),
  fetchNotices: vi.fn(),
  createNotice: vi.fn(),
  updateNotice: vi.fn(),
  deleteNotice: vi.fn(),
}))

describe('operations store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('保留报修服务端分页总数', async () => {
    vi.mocked(fetchRepairOrders).mockResolvedValue({
      records: [{ id: 1, code: 'WX1', reporter: '张同学', location: '1号楼', type: '水电维修', date: '2026-07-10', status: '待处理' }],
      total: 9,
      page: 1,
      pageSize: 10,
    })
    const store = useOperationsStore()

    await store.loadRepairOrders({ status: '待处理' })

    expect(fetchRepairOrders).toHaveBeenCalledWith({ status: '待处理' })
    expect(store.repairOrders).toHaveLength(1)
    expect(store.repairTotal).toBe(9)
  })

  it('登记缴费后刷新账单列表', async () => {
    vi.mocked(payPaymentBill).mockResolvedValue({ id: 1, amountPaid: 800, status: '已缴' } as never)
    vi.mocked(fetchPaymentBills).mockResolvedValue({ records: [], total: 0, page: 1, pageSize: 10 })
    const store = useOperationsStore()

    await store.payBill(1, { amount: 500, method: '现金' }, { status: '未缴' })

    expect(payPaymentBill).toHaveBeenCalledWith(1, { amount: 500, method: '现金' })
    expect(fetchPaymentBills).toHaveBeenCalledWith({ status: '未缴' })
  })

  it('新增卫生检查后刷新检查列表', async () => {
    vi.mocked(createHygieneCheck).mockResolvedValue({ id: 2, result: '优秀' } as never)
    vi.mocked(fetchHygieneChecks).mockResolvedValue({ records: [], total: 0, page: 1, pageSize: 10 })
    const store = useOperationsStore()

    await store.saveHygiene(null, { dormitory: '101宿舍', building: '1号楼', inspector: '管理员', score: 90, remark: '保持整洁' }, { result: '优秀' })

    expect(createHygieneCheck).toHaveBeenCalledWith({ dormitory: '101宿舍', building: '1号楼', inspector: '管理员', score: 90, remark: '保持整洁' })
    expect(fetchHygieneChecks).toHaveBeenCalledWith({ result: '优秀' })
  })

  it('支持创建和处理报修单', async () => {
    vi.mocked(createRepairOrder).mockResolvedValue({ id: 2 } as never)
    vi.mocked(addRepairRecord).mockResolvedValue({ id: 2, status: '已完成' } as never)
    vi.mocked(fetchRepairOrders).mockResolvedValue({ records: [], total: 0, page: 1, pageSize: 10 })
    const store = useOperationsStore()

    await store.createRepair({ reporter: '张同学', location: '1号楼', type: '水电维修' }, { status: '待处理' })
    await store.handleRepair(2, { handler: '维修员', content: '已完成', cost: 20, status: '已完成' }, { status: '待处理' })

    expect(createRepairOrder).toHaveBeenCalledOnce()
    expect(addRepairRecord).toHaveBeenCalledOnce()
    expect(fetchRepairOrders).toHaveBeenCalledTimes(2)
  })

  it('加载维修和收费历史并支持指派维修员', async () => {
    vi.mocked(fetchRepairRecords).mockResolvedValue({
      records: [{ id: 1, repairOrderId: 2, location: '1号楼', handler: '维修员', content: '完成', cost: 20, status: '已完成', handledAt: '2026-07-11T10:00:00', operatorUserId: 8 }],
      total: 4, page: 1, pageSize: 10,
    })
    vi.mocked(fetchPaymentRecords).mockResolvedValue({
      records: [{ id: 3, paymentId: 6, studentNo: '20260001', name: '测试学生', type: '住宿费', amount: 300, method: '现金', paidAt: '2026-07-11T11:00:00', operatorName: '管理员' }],
      total: 5, page: 1, pageSize: 10,
    })
    vi.mocked(assignRepairOrder).mockResolvedValue({ id: 2, assigneeUserId: 8 } as never)
    vi.mocked(fetchRepairOrders).mockResolvedValue({ records: [], total: 0, page: 1, pageSize: 10 })
    const store = useOperationsStore()

    await store.loadRepairRecords({ keyword: '完成' })
    await store.loadPaymentRecords({ paymentId: 6 })
    await store.assignRepair(2, 8, { status: '待处理' })

    expect(store.repairRecordTotal).toBe(4)
    expect(store.paymentRecordTotal).toBe(5)
    expect(fetchRepairRecords).toHaveBeenCalledWith({ keyword: '完成' })
    expect(fetchPaymentRecords).toHaveBeenCalledWith({ paymentId: 6 })
    expect(assignRepairOrder).toHaveBeenCalledWith(2, 8)
  })

  it('支持创建账单并加载费用分页', async () => {
    vi.mocked(createPaymentBill).mockResolvedValue({ id: 3 } as never)
    vi.mocked(fetchPaymentBills).mockResolvedValue({ records: [], total: 6, page: 1, pageSize: 10 })
    const store = useOperationsStore()
    const input = { studentNo: '20262001', name: '费用学生', type: '住宿费', amountDue: 800, deadline: '2026-08-31' }

    await store.createPayment(input, { status: '未缴' })

    expect(createPaymentBill).toHaveBeenCalledWith(input)
    expect(store.paymentTotal).toBe(6)
  })

  it('支持更新删除卫生检查和公告全流程', async () => {
    vi.mocked(updateHygieneCheck).mockResolvedValue({ id: 1 } as never)
    vi.mocked(deleteHygieneCheck).mockResolvedValue(undefined)
    vi.mocked(fetchHygieneChecks).mockResolvedValue({ records: [], total: 0, page: 1, pageSize: 10 })
    vi.mocked(createNotice).mockResolvedValue({ id: 2 } as never)
    vi.mocked(updateNotice).mockResolvedValue({ id: 2 } as never)
    vi.mocked(deleteNotice).mockResolvedValue(undefined)
    vi.mocked(fetchNotices).mockResolvedValue({ records: [], total: 0, page: 1, pageSize: 10 })
    const store = useOperationsStore()
    const hygiene = { dormitory: '101宿舍', building: '1号楼', inspector: '管理员', score: 88 }
    const notice = { title: '测试公告', type: '宿舍通知', publisher: '管理员', status: '已发布' as const }

    await store.saveHygiene(1, hygiene, {})
    await store.removeHygiene(1, {})
    await store.saveNotice(null, notice, {})
    await store.saveNotice(2, notice, {})
    await store.removeNotice(2, {})

    expect(updateHygieneCheck).toHaveBeenCalledWith(1, hygiene)
    expect(deleteHygieneCheck).toHaveBeenCalledWith(1)
    expect(createNotice).toHaveBeenCalledWith(notice)
    expect(updateNotice).toHaveBeenCalledWith(2, notice)
    expect(deleteNotice).toHaveBeenCalledWith(2)
  })
})
