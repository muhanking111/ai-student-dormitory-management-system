export type StatusTone = 'success' | 'warning' | 'danger' | 'info' | 'default'

export interface StatisticCard {
  title: string
  value: number
  unit: string
  change: string
  color: 'blue' | 'green' | 'purple' | 'orange' | 'cyan'
  icon: string
}

export interface CheckInTrendPoint {
  date: string
  value: number
}

export interface TableColumn {
  key: string
  title: string
  width?: number
}

export interface Dormitory {
  id: number
  name: string
  type: string
  buildingId?: number
  building: string
  beds: number
  occupied: number
  vacant: number
  status: '入住中' | '已满'
}

export interface Student {
  id: number
  studentNo: string
  name: string
  gender: string
  college: string
  grade: string
  phone: string
  checkInStatus: '已入住' | '未入住'
}

export interface CheckInApplication {
  id: number
  studentId?: number
  studentNo: string
  studentName?: string
  name?: string
  dormitoryId?: number
  dormitoryName?: string
  dormitory?: string
  buildingName?: string
  date: string
  status: '待审核' | '已通过' | '已拒绝'
  bedId?: number
  bedNo?: string
  applyRemark?: string
  reviewRemark?: string
  createdByUserId?: number
  appliedAt?: string
  reviewerUserId?: number
  reviewedAt?: string
}

export interface CheckInRecord {
  id: number
  studentId: number
  studentNo: string
  studentName: string
  bedId: number
  bedNo: string
  dormitoryId: number
  dormitoryName: string
  buildingName: string
  applicationId?: number
  checkInDate: string
  checkOutDate?: string
  status: '在住' | '已退宿'
  remark?: string
  checkInOperatorUserId?: number
  checkOutOperatorUserId?: number
}

export interface RepairOrder {
  id: number
  code: string
  reporter: string
  location: string
  type: string
  date: string
  status: '待处理' | '处理中' | '已完成'
  description?: string
  assigneeUserId?: number
}

export interface RepairRecord {
  id: number
  repairOrderId: number
  location?: string
  handler: string
  content: string
  cost: number
  status: '处理中' | '已完成'
  handledAt: string
  operatorUserId?: number
}

export interface Payment {
  id: number
  studentNo: string
  name: string
  type: string
  amountDue: number
  amountPaid: number
  status: '已缴' | '部分缴' | '未缴'
  deadline: string
}

export interface PaymentRecord {
  id: number
  paymentId: number
  studentNo?: string
  name?: string
  type?: string
  amount: number
  method: string
  paidAt: string
  operatorUserId?: number
  operatorName?: string
}

export interface HygieneCheck {
  id: number
  dormitory: string
  building: string
  date: string
  inspector: string
  score: number
  result: '优秀' | '良好' | '一般' | '不合格'
  remark?: string
}

export interface Notice {
  id: number
  title: string
  type: string
  date: string
  publisher: string
  status: '已发布' | '草稿' | '已撤回'
  content?: string
}
