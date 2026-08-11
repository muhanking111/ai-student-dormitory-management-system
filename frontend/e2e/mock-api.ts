import type { Page } from '@playwright/test'

export type MockApiControl = {
  setSessionPermissions: (permissionCodes: string[]) => void
}

const dashboardTrend = Array.from({ length: 30 }, (_, index) => {
  const date = new Date(Date.UTC(2026, 5, 12 + index))
  return {
    date: date.toISOString().slice(5, 10),
    value: [0, 1, 0, 2, 1, 3, 0][index % 7],
  }
})
const pendingProposalExpiry = new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString()

function matchesKeyword(keyword: string | null, ...values: unknown[]) {
  const normalized = keyword?.trim().toLocaleLowerCase()
  return !normalized || values.some((value) => String(value ?? '').toLocaleLowerCase().includes(normalized))
}

function pageResponse<T>(url: URL, rows: T[]) {
  const page = Math.max(1, Number(url.searchParams.get('page')) || 1)
  const pageSize = Math.max(1, Number(url.searchParams.get('pageSize')) || 10)
  const offset = (page - 1) * pageSize
  return { records: rows.slice(offset, offset + pageSize), total: rows.length, page, pageSize }
}

const dataByPath: Record<string, unknown> = {
  '/api/dashboard/statistics': [
    { title: '宿舍总数', value: 4, unit: '间', change: '实时数据', color: 'blue', icon: 'home' },
    { title: '学生入住人数', value: 18, unit: '人', change: '实时数据', color: 'green', icon: 'team' },
    { title: '空余床位', value: 6, unit: '个', change: '实时数据', color: 'purple', icon: 'bed' },
    { title: '待维修数量', value: 2, unit: '条', change: '待处理', color: 'orange', icon: 'tool' },
    { title: '今日申请', value: 1, unit: '条', change: '实时数据', color: 'cyan', icon: 'form' },
    { title: '卫生检查', value: 12, unit: '次', change: '累计记录', color: 'blue', icon: 'safety' },
  ],
  '/api/dashboard/check-in-trend': dashboardTrend,
  '/api/dormitories': [
    { id: 1, name: '1号宿舍', type: '男生宿舍', buildingId: 1, building: '1号楼', beds: 6, occupied: 5, vacant: 1, status: '入住中' },
  ],
  '/api/hygiene-checks': [],
  '/api/notices': [
    {
      id: 1,
      title: '宿舍安全用电通知',
      type: '安全卫生',
      date: '2026-07-10',
      publisher: '管理员',
      status: '已发布',
      content: '请规范使用宿舍电器，离开时关闭非必要电源。',
    },
  ],
}

const aiRiskCases = [
  {
    id: 'risk-repair-backlog-001',
    caseVersion: 1,
    type: '维修风险',
    severity: 'high',
    subjectToken: 'scope-repair-zone-a',
    evidenceSummary: '待处理维修单连续 2 个统计周期高于规则阈值',
    explanation: '建议先核验积压原因并调整值班安排，不自动改变维修状态。',
    assignee: '宿管值班组',
    sla: '4 小时内人工核验',
    state: 'open',
    ruleVersion: 'repair-backlog.v1',
    asOf: '2026-07-11T08:00:00+08:00',
    confidence: 0.92,
    events: [{ id: 'risk-event-001', type: 'OPENED', actor: '规则引擎', occurredAt: '2026-07-11T08:00:00+08:00', detail: '规则信号已创建' }],
  },
  {
    id: 'risk-hygiene-review-001',
    caseVersion: 2,
    type: '卫生风险',
    severity: 'medium',
    subjectToken: 'scope-building-b',
    evidenceSummary: '卫生检查结果达到人工复核阈值',
    explanation: '该信号仅用于运营排查，不代表对学生或宿舍的评价。',
    assignee: '卫生巡检组',
    sla: '1 个工作日内人工核验',
    state: 'acknowledged',
    ruleVersion: 'hygiene-review.v1',
    asOf: '2026-07-11T08:00:00+08:00',
    confidence: 0.81,
    events: [{ id: 'risk-event-002', type: 'ACKNOWLEDGED', actor: '当前用户', occurredAt: '2026-07-11T09:00:00+08:00', detail: '值班人员已确认复核' }],
  },
]

const aiProposals = [
  {
    id: 'proposal-repair-001',
    actionType: 'REPAIR_ASSIGN',
    title: '维修指派建议',
    target: '维修工单 WX20260710001',
    currentValue: '未指派',
    proposedValue: '维修员（值班组）',
    impact: '仅在真实审批与执行成功后改变指派人',
    requiredPermission: 'ai:approval:review + ADMIN + repair:write + 对象范围',
    state: 'pending_approval',
    version: 1,
    payloadHash: 'a'.repeat(64),
    businessSnapshotHash: 'b'.repeat(64),
    expiresAt: pendingProposalExpiry,
    riskLevel: 'high',
    evidence: {
      basis: 'deterministic',
      confidence: 0.94,
      asOf: '2026-07-11T08:00:00+08:00',
      grounded: true,
      citations: [{ id: 'citation-proposal-001', label: '维修分诊规则', locator: 'rule:repair-triage.v1', version: '1', access: 'available' }],
    },
    auditAvailable: true,
    executionState: 'pending',
  },
  {
    id: 'proposal-notice-001',
    actionType: 'NOTICE_CREATE_DRAFT',
    title: '公告草稿',
    target: '公告草稿',
    currentValue: '无草稿',
    proposedValue: '各住宿单元：周五开展安全用电巡检，请提前整理公共区域。',
    impact: '仅创建草稿，不发布公告',
    requiredPermission: 'ai:approval:review + notice:write',
    state: 'pending_approval',
    version: 1,
    payloadHash: 'c'.repeat(64),
    businessSnapshotHash: 'd'.repeat(64),
    expiresAt: pendingProposalExpiry,
    riskLevel: 'medium',
    evidence: {
      basis: 'deterministic',
      confidence: 0.91,
      asOf: '2026-07-11T08:00:00+08:00',
      grounded: true,
      citations: [{ id: 'citation-proposal-002', label: '公告模板规范', locator: 'knowledge:notice-template.v1', version: '1', access: 'available' }],
    },
    auditAvailable: true,
    executionState: 'pending',
  },
]

const aiAuditRuns = [
  {
    id: 'run-demo-001',
    capability: 'repair-triage',
    state: 'succeeded',
    modelAlias: 'demo-local',
    promptVersion: 'repair-triage.v1',
    citationCount: 1,
    durationMs: 428,
    inputTokens: 0,
    outputTokens: 0,
    estimatedCost: 0,
    currency: 'CNY',
    chainHash: 'sha256:demo-chain-001',
    occurredAt: '2026-07-11T08:10:00+08:00',
    steps: [
      { id: 'audit-event-001', type: 'run', label: '运行已创建', status: 'SUCCEEDED', occurredAt: '2026-07-11T08:10:00+08:00', metadata: { sequence: 1 } },
      { id: 'audit-event-002', type: 'tool', label: '已读取授权范围内的维修上下文', status: 'SUCCEEDED', occurredAt: '2026-07-11T08:10:00+08:00', metadata: { tool: 'repair.get_context.v1' } },
      { id: 'audit-event-003', type: 'proposal', label: '已创建待审批提案', status: 'PENDING_APPROVAL', occurredAt: '2026-07-11T08:10:01+08:00', metadata: { action: 'REPAIR_ASSIGN' } },
    ],
  },
]

const permissions = [
  { id: 1, code: 'dashboard:read', name: '查看数据驾驶舱', module: 'dashboard' },
  { id: 2, code: 'dormitory:read', name: '查看宿舍资源', module: 'dormitory' },
  { id: 3, code: 'dormitory:write', name: '管理宿舍资源', module: 'dormitory' },
  { id: 4, code: 'system:user:read', name: '查看用户', module: 'system' },
  { id: 5, code: 'system:user:write', name: '管理用户', module: 'system' },
  { id: 6, code: 'system:role:read', name: '查看角色权限', module: 'system' },
  { id: 7, code: 'system:role:write', name: '管理角色权限', module: 'system' },
  { id: 8, code: 'student:read', name: '查看学生信息', module: 'student' },
  { id: 9, code: 'student:write', name: '管理学生信息', module: 'student' },
  { id: 10, code: 'checkin:read', name: '查看入住业务', module: 'checkin' },
  { id: 11, code: 'checkin:review', name: '审核入住业务', module: 'checkin' },
  { id: 12, code: 'repair:read', name: '查看维修业务', module: 'repair' },
  { id: 13, code: 'repair:write', name: '处理维修业务', module: 'repair' },
  { id: 14, code: 'payment:read', name: '查看费用业务', module: 'payment' },
  { id: 15, code: 'payment:write', name: '管理费用业务', module: 'payment' },
  { id: 16, code: 'hygiene:read', name: '查看卫生检查', module: 'hygiene' },
  { id: 17, code: 'hygiene:write', name: '管理卫生检查', module: 'hygiene' },
  { id: 18, code: 'notice:read', name: '查看公告', module: 'notice' },
  { id: 19, code: 'notice:write', name: '管理公告', module: 'notice' },
  { id: 20, code: 'ai:assistant:use', name: '使用 AI 助手', module: 'ai' },
  { id: 21, code: 'ai:dashboard:query', name: '使用自然语言驾驶舱', module: 'ai' },
  { id: 22, code: 'ai:knowledge:read', name: '读取 AI 知识库', module: 'ai' },
  { id: 23, code: 'ai:knowledge:manage', name: '管理 AI 知识库', module: 'ai' },
  { id: 24, code: 'ai:knowledge:publish-public', name: '发布公共 AI 知识', module: 'ai' },
  { id: 25, code: 'ai:repair:triage', name: '使用维修智能分诊', module: 'ai' },
  { id: 26, code: 'ai:notice:draft', name: '使用公告 AI 起草', module: 'ai' },
  { id: 27, code: 'ai:risk:read', name: '查看智能风险', module: 'ai' },
  { id: 28, code: 'ai:risk:manage', name: '处置智能风险', module: 'ai' },
  { id: 29, code: 'ai:approval:review', name: '复核 AI 提案', module: 'ai' },
  { id: 30, code: 'ai:audit:read', name: '查看 AI 运行审计', module: 'ai' },
  { id: 31, code: 'ai:audit:content:read', name: '查看 AI 审计正文', module: 'ai' },
  { id: 32, code: 'ai:config:manage', name: '管理 AI 配置', module: 'ai' },
  { id: 33, code: 'ai:eval:run', name: '运行 AI 评测', module: 'ai' },
]
const roleOptions = [
  { id: 1, code: 'ADMIN', name: '系统管理员', enabled: true, builtIn: true },
  { id: 2, code: 'DORM_MANAGER', name: '宿舍管理员', enabled: true, builtIn: true },
  { id: 3, code: 'REPAIRER', name: '维修人员', enabled: true, builtIn: true },
  { id: 4, code: 'NO_ACCESS', name: '无业务权限角色', enabled: true, builtIn: false },
]
const roles = [
  { ...roleOptions[0], description: '拥有全部系统权限', permissionIds: permissions.map((item) => item.id), permissionCodes: permissions.map((item) => item.code) },
  { ...roleOptions[1], description: '负责住宿资源和日常宿管业务', permissionIds: [1, 2, 3], permissionCodes: ['dashboard:read', 'dormitory:read', 'dormitory:write'] },
  { ...roleOptions[2], description: '处理已指派的维修任务', permissionIds: [12, 13], permissionCodes: ['repair:read', 'repair:write'] },
  { ...roleOptions[3], description: '用于验证无业务权限时的 403 页面', permissionIds: [], permissionCodes: [] },
]
const buildings = [
  { id: 1, code: 'B01', name: '1号楼', genderType: '男生宿舍', floors: 6, manager: '宿舍管理员', status: '启用' },
]
const seedBeds = [
  { id: 1, bedNo: '01', status: '空闲', dormitoryId: 1, dormitoryName: '1号宿舍', buildingId: 1, buildingName: '1号楼' },
  { id: 2, bedNo: '02', status: '已占用', studentId: 2, studentName: '已入住学生', dormitoryId: 1, dormitoryName: '1号宿舍', buildingId: 1, buildingName: '1号楼' },
  { id: 3, bedNo: '03', status: '空闲', dormitoryId: 1, dormitoryName: '1号宿舍', buildingId: 1, buildingName: '1号楼' },
]

export async function mockApi(page: Page, initiallyAuthenticated: boolean): Promise<MockApiControl> {
  let authenticated = initiallyAuthenticated
  const dormitories = [...(dataByPath['/api/dormitories'] as Array<Record<string, unknown>>)]
  const beds = seedBeds.map((item) => ({ ...item }))
  const students = [
    { id: 1, studentNo: '20261001', name: '待入住学生', gender: '男', college: '计算机学院', grade: '2026', phone: '13800001001', checkInStatus: '未入住' },
    { id: 2, studentNo: '20261002', name: '已入住学生', gender: '男', college: '计算机学院', grade: '2026', phone: '13800001002', checkInStatus: '已入住' },
    { id: 3, studentNo: '20261003', name: '分配测试学生', gender: '男', college: '信息学院', grade: '2026', phone: '13800001003', checkInStatus: '未入住' },
  ]
  const applications = [
    { id: 1, studentId: 1, studentNo: '20261001', studentName: '待入住学生', dormitoryId: 1, dormitoryName: '1号宿舍', buildingName: '1号楼', date: '2026-07-10', status: '待审核', applyRemark: '测试申请' },
  ]
  const records = [
    { id: 1, studentId: 2, studentNo: '20261002', studentName: '已入住学生', bedId: 2, bedNo: '02', dormitoryId: 1, dormitoryName: '1号宿舍', buildingName: '1号楼', checkInDate: '2026-07-01T09:00:00', status: '在住', remark: '测试入住' },
  ]
  const repairOrders = [
    { id: 1, code: 'WX20260710001', reporter: '张同学', location: '1号楼-101宿舍', type: '水电维修', date: '2026-07-10', status: '待处理', description: '水龙头漏水', assigneeUserId: undefined as number | undefined },
    { id: 2, code: 'WX20260709001', reporter: '李同学', location: '1号楼-102宿舍', type: '门窗维修', date: '2026-07-09', status: '已完成', description: '宿舍门锁损坏', assigneeUserId: 2 as number | undefined },
  ]
  const repairRecords = [
    { id: 1, repairOrderId: 2, location: '1号楼-102宿舍', handler: '维修员', content: '已更换门锁', cost: 80, status: '已完成', handledAt: '2026-07-09T16:30:00', operatorUserId: 2 },
  ]
  const paymentBills = [
    { id: 1, studentNo: '20261002', name: '已入住学生', type: '住宿费', amountDue: 800, amountPaid: 300, status: '部分缴', deadline: '2026-08-31' },
  ]
  const paymentRecords = [
    { id: 1, paymentId: 1, studentNo: '20261002', name: '已入住学生', type: '住宿费', amount: 300, method: '现金', paidAt: '2026-07-10T10:30:00', operatorUserId: 1, operatorName: '管理员' },
  ]
  const hygieneChecks = [
    { id: 1, dormitory: '1号宿舍', building: '1号楼', date: '2026-07-10', inspector: '管理员', score: 78, result: '良好' },
  ]
  const notices = [
    {
      id: 1,
      title: '宿舍安全用电通知',
      type: '安全卫生',
      date: '2026-07-10',
      publisher: '管理员',
      status: '已发布',
      content: '请规范使用宿舍电器，离开时关闭非必要电源。',
    },
  ]
  const users = [
    { id: 1, username: 'admin', displayName: '管理员', enabled: true, roles: [roleOptions[0]] },
    { id: 2, username: 'repairer', displayName: '维修员', enabled: true, roles: [roleOptions[2]] },
    { id: 3, username: 'noaccess', displayName: '无权限用户', enabled: true, roles: [roleOptions[3]] },
  ]
  const adminSession = {
    id: 1,
    username: 'admin',
    userName: '管理员',
    roleCode: 'ADMIN',
    roleCodes: ['ADMIN'],
    permissions: permissions.map((item) => item.code),
  }
  const repairerSession = {
    id: 2,
    username: 'repairer',
    userName: '维修员',
    roleCode: 'REPAIRER',
    roleCodes: ['REPAIRER'],
    permissions: ['repair:read', 'repair:write'],
  }
  const noAccessSession = {
    id: 3,
    username: 'noaccess',
    userName: '无权限用户',
    roleCode: 'NO_ACCESS',
    roleCodes: ['NO_ACCESS'],
    permissions: [] as string[],
  }
  let session = adminSession

  await page.route(/^https?:\/\/[^/]+\/api\//, async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const path = url.pathname

    if (path === '/api/auth/login') {
      const input = request.postDataJSON() as { username?: string }
      session = input.username === 'repairer'
        ? repairerSession
        : input.username === 'noaccess' ? noAccessSession : adminSession
      authenticated = true
      await route.fulfill({ json: { code: 0, message: 'success', data: session } })
      return
    }
    if (path === '/api/auth/logout') {
      authenticated = false
      await route.fulfill({ json: { code: 0, message: 'success', data: null } })
      return
    }
    if (path === '/api/auth/me') {
      if (!authenticated) {
        await route.fulfill({ status: 401, json: { code: 401, message: '未登录或登录已过期', data: null } })
        return
      }
      await route.fulfill({ json: { code: 0, message: 'success', data: session } })
      return
    }

    if (path === '/api/security/csrf') {
      await route.fulfill({ json: { token: 'mock-csrf-token' } })
      return
    }

    if (!authenticated) {
      await route.fulfill({ status: 401, json: { code: 401, message: '未登录或登录已过期', data: null } })
      return
    }
    const denyWithoutPermissions = async (required: string[], mode: 'all' | 'any' = 'all') => {
      const granted = mode === 'all'
        ? required.every((permission) => session.permissions.includes(permission))
        : required.some((permission) => session.permissions.includes(permission))
      if (granted) return false
      await route.fulfill({ status: 403, json: { code: 403, message: '无权限访问当前资源', data: null } })
      return true
    }
    if (path === '/api/ai/risk-cases' && request.method() === 'GET') {
      if (await denyWithoutPermissions(['ai:risk:read'])) return
      const state = url.searchParams.get('state')
      const type = url.searchParams.get('type')
      const filtered = aiRiskCases.filter((item) => (!state || item.state === state)
        && (!type || item.type === type))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (/^\/api\/ai\/risk-cases\/[^/]+$/.test(path) && request.method() === 'GET') {
      if (await denyWithoutPermissions(['ai:risk:read'])) return
      const item = aiRiskCases.find((candidate) => candidate.id === path.split('/').at(-1))
      await route.fulfill(item
        ? { json: { code: 0, message: 'success', data: item } }
        : { status: 404, json: { code: 404, message: '风险案例不存在', data: null } })
      return
    }
    if (path === '/api/ai/proposals' && request.method() === 'GET') {
      if (await denyWithoutPermissions(['ai:approval:review'])) return
      const state = url.searchParams.get('state')
      const actionType = url.searchParams.get('actionType')
      const filtered = aiProposals.filter((item) => (!state || item.state === state)
        && (!actionType || item.actionType === actionType))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (/^\/api\/ai\/proposals\/[^/]+$/.test(path) && request.method() === 'GET') {
      if (await denyWithoutPermissions(['ai:approval:review'])) return
      const item = aiProposals.find((candidate) => candidate.id === path.split('/').at(-1))
      await route.fulfill(item
        ? { json: { code: 0, message: 'success', data: item } }
        : { status: 404, json: { code: 404, message: '提案不存在', data: null } })
      return
    }
    if (path === '/api/ai/audit/runs' && request.method() === 'GET') {
      if (await denyWithoutPermissions(['ai:audit:read'])) return
      const state = url.searchParams.get('state')
      const capability = url.searchParams.get('capability')
      const filtered = aiAuditRuns.filter((item) => (!state || item.state === state)
        && (!capability || item.capability === capability))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (/^\/api\/ai\/audit\/runs\/[^/]+$/.test(path) && request.method() === 'GET') {
      if (await denyWithoutPermissions(['ai:audit:read'])) return
      const item = aiAuditRuns.find((candidate) => candidate.id === path.split('/').at(-1))
      await route.fulfill(item
        ? { json: { code: 0, message: 'success', data: item } }
        : { status: 404, json: { code: 404, message: '运行审计不存在', data: null } })
      return
    }
    if (path === '/api/ai/audit/costs' && request.method() === 'GET') {
      if (await denyWithoutPermissions(['ai:audit:read'])) return
      await route.fulfill({
        json: {
          code: 0,
          message: 'success',
          data: { currency: 'CNY', estimatedCost: 0, inputTokens: 0, outputTokens: 0, asOf: '2026-07-11T08:00:00+08:00' },
        },
      })
      return
    }
    if (path.startsWith('/api/ai/')) {
      await route.fulfill({
        status: 404,
        json: { code: 404, message: `未配置的 AI mock 端点：${request.method()} ${path}`, data: null },
      })
      return
    }
    if (path === '/api/dormitories' && request.method() === 'POST') {
      const input = request.postDataJSON() as Record<string, unknown>
      const building = buildings.find((item) => item.id === input.buildingId)
      const created = { id: 2, ...input, building: building?.name ?? '1号楼', vacant: Number(input.beds) - Number(input.occupied), status: '入住中' }
      dormitories.push(created)
      await route.fulfill({ status: 201, json: { code: 0, message: 'success', data: created } })
      return
    }
    if (path === '/api/dormitories') {
      const keyword = url.searchParams.get('keyword')
      const buildingId = url.searchParams.get('buildingId')
      const type = url.searchParams.get('type')
      const status = url.searchParams.get('status')
      const filtered = dormitories.filter((item) => matchesKeyword(keyword, item.name, item.building)
        && (!buildingId || item.buildingId === Number(buildingId))
        && (!type || item.type === type)
        && (!status || item.status === status))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/students' && request.method() === 'POST') {
      const input = request.postDataJSON() as Record<string, unknown>
      const created = { id: students.length + 1, ...input, checkInStatus: '未入住' }
      students.push(created as (typeof students)[number])
      await route.fulfill({ status: 201, json: { code: 0, message: 'success', data: created } })
      return
    }
    if (/^\/api\/students\/\d+$/.test(path) && request.method() === 'PATCH') {
      const id = Number(path.split('/').at(-1))
      const index = students.findIndex((item) => item.id === id)
      const updated = { ...students[index], ...(request.postDataJSON() as Record<string, unknown>) }
      students[index] = updated as (typeof students)[number]
      await route.fulfill({ json: { code: 0, message: 'success', data: updated } })
      return
    }
    if (/^\/api\/students\/\d+$/.test(path) && request.method() === 'DELETE') {
      const id = Number(path.split('/').at(-1))
      const index = students.findIndex((item) => item.id === id)
      if (index >= 0) students.splice(index, 1)
      await route.fulfill({ status: 204, body: '' })
      return
    }
    if (path === '/api/students') {
      const status = url.searchParams.get('checkInStatus')
      const keyword = url.searchParams.get('keyword')
      const filtered = students.filter((item) => (!status || item.checkInStatus === status)
        && matchesKeyword(keyword, item.studentNo, item.name, item.college))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/check-in-applications' && request.method() === 'POST') {
      const input = request.postDataJSON() as { studentId: number; dormitoryId: number; remark?: string }
      const student = students.find((item) => item.id === input.studentId)!
      const dormitory = dormitories.find((item) => item.id === input.dormitoryId)!
      const created = { id: applications.length + 1, studentId: student.id, studentNo: student.studentNo,
        studentName: student.name, dormitoryId: dormitory.id as number, dormitoryName: dormitory.name as string,
        buildingName: dormitory.building as string, date: '2026-07-10', status: '待审核', applyRemark: input.remark }
      applications.push(created as (typeof applications)[number])
      await route.fulfill({ status: 201, json: { code: 0, message: 'success', data: created } })
      return
    }
    if (/^\/api\/check-in-applications\/\d+\/approve$/.test(path)) {
      const id = Number(path.split('/')[3])
      const input = request.postDataJSON() as { bedId: number; remark?: string }
      const application = applications.find((item) => item.id === id)!
      const student = students.find((item) => item.id === application.studentId)!
      const bed = beds.find((item) => item.id === input.bedId)!
      application.status = '已通过'
      Object.assign(application, { bedId: bed.id, bedNo: bed.bedNo, reviewRemark: input.remark })
      Object.assign(bed, { status: '已占用', studentId: student.id, studentName: student.name })
      student.checkInStatus = '已入住'
      records.push({ id: records.length + 1, studentId: student.id, studentNo: student.studentNo,
        studentName: student.name, bedId: bed.id, bedNo: bed.bedNo, dormitoryId: bed.dormitoryId,
        dormitoryName: bed.dormitoryName, buildingName: bed.buildingName, checkInDate: '2026-07-10T10:00:00',
        status: '在住', remark: input.remark } as (typeof records)[number])
      await route.fulfill({ json: { code: 0, message: 'success', data: application } })
      return
    }
    if (/^\/api\/check-in-applications\/\d+\/reject$/.test(path)) {
      const id = Number(path.split('/')[3])
      const application = applications.find((item) => item.id === id)!
      application.status = '已拒绝'
      await route.fulfill({ json: { code: 0, message: 'success', data: application } })
      return
    }
    if (path === '/api/check-in-applications') {
      const status = url.searchParams.get('status')
      const keyword = url.searchParams.get('keyword')
      const dormitoryId = url.searchParams.get('dormitoryId')
      const studentId = url.searchParams.get('studentId')
      const filtered = applications.filter((item) => (!status || item.status === status)
        && (!dormitoryId || item.dormitoryId === Number(dormitoryId))
        && (!studentId || item.studentId === Number(studentId))
        && matchesKeyword(keyword, item.studentNo, item.studentName, item.dormitoryName))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/check-in-records' && request.method() === 'POST') {
      const input = request.postDataJSON() as { studentId: number; bedId: number; remark?: string }
      const student = students.find((item) => item.id === input.studentId)!
      const bed = beds.find((item) => item.id === input.bedId)!
      Object.assign(bed, { status: '已占用', studentId: student.id, studentName: student.name })
      student.checkInStatus = '已入住'
      const created = { id: records.length + 1, studentId: student.id, studentNo: student.studentNo,
        studentName: student.name, bedId: bed.id, bedNo: bed.bedNo, dormitoryId: bed.dormitoryId,
        dormitoryName: bed.dormitoryName, buildingName: bed.buildingName, checkInDate: '2026-07-10T11:00:00',
        status: '在住', remark: input.remark }
      records.push(created as (typeof records)[number])
      await route.fulfill({ status: 201, json: { code: 0, message: 'success', data: created } })
      return
    }
    if (/^\/api\/check-in-records\/\d+\/checkout$/.test(path)) {
      const id = Number(path.split('/')[3])
      const record = records.find((item) => item.id === id)!
      const student = students.find((item) => item.id === record.studentId)!
      const bed = beds.find((item) => item.id === record.bedId)!
      Object.assign(record, { status: '已退宿', checkOutDate: '2026-07-10T12:00:00' })
      Object.assign(bed, { status: '空闲', studentId: undefined, studentName: undefined })
      student.checkInStatus = '未入住'
      await route.fulfill({ json: { code: 0, message: 'success', data: record } })
      return
    }
    if (path === '/api/check-in-records') {
      const status = url.searchParams.get('status')
      const keyword = url.searchParams.get('keyword')
      const filtered = records.filter((item) => (!status || item.status === status)
        && (!url.searchParams.get('dormitoryId') || item.dormitoryId === Number(url.searchParams.get('dormitoryId')))
        && matchesKeyword(keyword, item.studentNo, item.studentName))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/repair-orders' && request.method() === 'POST') {
      const input = request.postDataJSON() as Record<string, unknown>
      const created = {
        id: Math.max(...repairOrders.map((item) => item.id)) + 1,
        code: `WX20260710${String(repairOrders.length + 1).padStart(3, '0')}`,
        reporter: String(input.reporter),
        location: String(input.location),
        type: String(input.type),
        date: '2026-07-10',
        status: '待处理',
        description: input.description ? String(input.description) : '',
        assigneeUserId: session.roleCode === 'REPAIRER'
          ? session.id
          : input.assigneeUserId ? Number(input.assigneeUserId) : undefined,
      }
      repairOrders.push(created as (typeof repairOrders)[number])
      await route.fulfill({ status: 201, json: { code: 0, message: 'success', data: created } })
      return
    }
    if (/^\/api\/repair-orders\/\d+\/assignee$/.test(path) && request.method() === 'PATCH') {
      const id = Number(path.split('/')[3])
      const input = request.postDataJSON() as { assigneeUserId: number }
      const order = repairOrders.find((item) => item.id === id)!
      order.assigneeUserId = Number(input.assigneeUserId)
      await route.fulfill({ json: { code: 0, message: 'success', data: order } })
      return
    }
    if (/^\/api\/repair-orders\/\d+\/records$/.test(path) && request.method() === 'POST') {
      const id = Number(path.split('/')[3])
      const input = request.postDataJSON() as { content: string; cost: number; status: '处理中' | '已完成' }
      const order = repairOrders.find((item) => item.id === id)!
      if (order.status !== '已完成') order.status = input.status
      repairRecords.unshift({
        id: Math.max(0, ...repairRecords.map((item) => item.id)) + 1,
        repairOrderId: order.id,
        location: order.location,
        handler: session.userName,
        content: input.content,
        cost: Number(input.cost),
        status: input.status,
        handledAt: `2026-07-11T${String(10 + repairRecords.length).padStart(2, '0')}:00:00`,
        operatorUserId: session.id,
      })
      await route.fulfill({ status: 201, json: { code: 0, message: 'success', data: order } })
      return
    }
    if (path === '/api/repair-records' && request.method() === 'GET') {
      const keyword = url.searchParams.get('keyword')
      const repairOrderId = url.searchParams.get('repairOrderId')
      const filtered = repairRecords.filter((record) => {
        const order = repairOrders.find((item) => item.id === record.repairOrderId)
        return (!repairOrderId || record.repairOrderId === Number(repairOrderId))
          && (session.roleCode !== 'REPAIRER' || order?.assigneeUserId === session.id)
          && matchesKeyword(keyword, record.location, record.handler, record.content, record.status,
            order?.code, order?.reporter, order?.type)
      })
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/repair-orders') {
      const status = url.searchParams.get('status')
      const type = url.searchParams.get('type')
      const keyword = url.searchParams.get('keyword')
      const filtered = repairOrders.filter((item) => (!status || item.status === status)
        && (!type || item.type === type)
        && (session.roleCode !== 'REPAIRER' || item.assigneeUserId === session.id)
        && matchesKeyword(keyword, item.code, item.reporter, item.location))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/payment-bills' && request.method() === 'POST') {
      const input = request.postDataJSON() as Record<string, unknown>
      const created = { id: paymentBills.length + 1, ...input, amountPaid: 0, status: '未缴' }
      paymentBills.push(created as (typeof paymentBills)[number])
      await route.fulfill({ status: 201, json: { code: 0, message: 'success', data: created } })
      return
    }
    if (/^\/api\/payment-bills\/\d+\/payments$/.test(path) && request.method() === 'POST') {
      const id = Number(path.split('/')[3])
      const input = request.postDataJSON() as { amount: number; method: string }
      const bill = paymentBills.find((item) => item.id === id)!
      if (id === 1) {
        await route.fulfill({
          status: 409,
          json: { code: 409, message: '缴费金额超过待缴金额', data: null },
        })
        return
      }
      const remaining = Number(bill.amountDue) - Number(bill.amountPaid)
      if (Number(input.amount) > remaining) {
        await route.fulfill({
          status: 409,
          json: { code: 409, message: '缴费金额超过待缴金额', data: null },
        })
        return
      }
      bill.amountPaid = Number(bill.amountPaid) + Number(input.amount)
      bill.status = bill.amountPaid >= bill.amountDue ? '已缴' : bill.amountPaid > 0 ? '部分缴' : '未缴'
      paymentRecords.unshift({
        id: Math.max(0, ...paymentRecords.map((item) => item.id)) + 1,
        paymentId: bill.id,
        studentNo: bill.studentNo,
        name: bill.name,
        type: bill.type,
        amount: Number(input.amount),
        method: input.method,
        paidAt: `2026-07-11T${String(10 + paymentRecords.length).padStart(2, '0')}:30:00`,
        operatorUserId: session.id,
        operatorName: session.userName,
      })
      await route.fulfill({ status: 201, json: { code: 0, message: 'success', data: bill } })
      return
    }
    if (path === '/api/payment-records' && request.method() === 'GET') {
      const keyword = url.searchParams.get('keyword')
      const paymentId = url.searchParams.get('paymentId')
      const filtered = paymentRecords.filter((record) => (!paymentId || record.paymentId === Number(paymentId))
        && matchesKeyword(keyword, record.studentNo, record.name, record.type, record.method))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/payment-bills') {
      const status = url.searchParams.get('status')
      const type = url.searchParams.get('type')
      const keyword = url.searchParams.get('keyword')
      const filtered = paymentBills.filter((item) => (!status || item.status === status)
        && (!type || item.type === type)
        && matchesKeyword(keyword, item.studentNo, item.name))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/hygiene-checks' && request.method() === 'POST') {
      const input = request.postDataJSON() as Record<string, unknown>
      const score = Number(input.score)
      const result = score >= 85 ? '优秀' : score >= 75 ? '良好' : score >= 60 ? '一般' : '不合格'
      const created = { id: hygieneChecks.length + 1, ...input, date: '2026-07-10', result }
      hygieneChecks.push(created as (typeof hygieneChecks)[number])
      await route.fulfill({ status: 201, json: { code: 0, message: 'success', data: created } })
      return
    }
    if (/^\/api\/hygiene-checks\/\d+$/.test(path) && request.method() === 'PATCH') {
      const id = Number(path.split('/').at(-1))
      const index = hygieneChecks.findIndex((item) => item.id === id)
      const input = request.postDataJSON() as Record<string, unknown>
      const score = Number(input.score)
      const result = score >= 85 ? '优秀' : score >= 75 ? '良好' : score >= 60 ? '一般' : '不合格'
      hygieneChecks[index] = { ...hygieneChecks[index], ...input, result } as (typeof hygieneChecks)[number]
      await route.fulfill({ json: { code: 0, message: 'success', data: hygieneChecks[index] } })
      return
    }
    if (/^\/api\/hygiene-checks\/\d+$/.test(path) && request.method() === 'DELETE') {
      const id = Number(path.split('/').at(-1))
      const index = hygieneChecks.findIndex((item) => item.id === id)
      if (index >= 0) hygieneChecks.splice(index, 1)
      await route.fulfill({ status: 204, body: '' })
      return
    }
    if (path === '/api/hygiene-checks') {
      const result = url.searchParams.get('result')
      const keyword = url.searchParams.get('keyword')
      const filtered = hygieneChecks.filter((item) => (!result || item.result === result)
        && matchesKeyword(keyword, item.dormitory, item.building))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/notices' && request.method() === 'POST') {
      const input = request.postDataJSON() as Record<string, unknown>
      const { content, ...fields } = input
      const created = {
        id: notices.length + 1,
        ...fields,
        ...(typeof content === 'string' ? { content } : {}),
        date: '2026-07-10',
      }
      notices.push(created as (typeof notices)[number])
      await route.fulfill({ status: 201, json: { code: 0, message: 'success', data: created } })
      return
    }
    if (/^\/api\/notices\/\d+$/.test(path) && request.method() === 'PATCH') {
      const id = Number(path.split('/').at(-1))
      const index = notices.findIndex((item) => item.id === id)
      const input = request.postDataJSON() as Record<string, unknown>
      const { content, ...fields } = input
      notices[index] = {
        ...notices[index],
        ...fields,
        ...(typeof content === 'string' ? { content } : {}),
      } as (typeof notices)[number]
      await route.fulfill({ json: { code: 0, message: 'success', data: notices[index] } })
      return
    }
    if (/^\/api\/notices\/\d+$/.test(path) && request.method() === 'DELETE') {
      const id = Number(path.split('/').at(-1))
      const index = notices.findIndex((item) => item.id === id)
      if (index >= 0) notices.splice(index, 1)
      await route.fulfill({ status: 204, body: '' })
      return
    }
    if (path === '/api/notices') {
      const status = url.searchParams.get('status')
      const type = url.searchParams.get('type')
      const keyword = url.searchParams.get('keyword')
      const filtered = notices.filter((item) => (!status || item.status === status)
        && (!type || item.type === type)
        && matchesKeyword(keyword, item.title))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/buildings' && request.method() === 'POST') {
      const input = request.postDataJSON() as Record<string, unknown>
      const created = { id: buildings.length + 1, ...input }
      buildings.push(created as (typeof buildings)[number])
      await route.fulfill({ status: 201, json: { code: 0, message: 'success', data: created } })
      return
    }
    if (path === '/api/buildings') {
      const status = url.searchParams.get('status')
      const keyword = url.searchParams.get('keyword')
      const filtered = buildings.filter((item) => (!status || item.status === status)
        && matchesKeyword(keyword, item.code, item.name, item.manager))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/buildings/options') {
      await route.fulfill({ json: { code: 0, message: 'success', data: buildings.filter((item) => item.status === '启用') } })
      return
    }
    if (path === '/api/beds') {
      const status = url.searchParams.get('status')
      const dormitoryId = url.searchParams.get('dormitoryId')
      const buildingId = url.searchParams.get('buildingId')
      const keyword = url.searchParams.get('keyword')
      const filtered = beds.filter((item) => (!status || item.status === status)
        && (!dormitoryId || item.dormitoryId === Number(dormitoryId))
        && (!buildingId || item.buildingId === Number(buildingId))
        && matchesKeyword(keyword, item.bedNo))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/users' && request.method() === 'POST') {
      if (await denyWithoutPermissions(['system:user:write'])) return
      const input = request.postDataJSON() as Record<string, unknown>
      const roleId = (input.roleIds as number[])[0]
      const created = {
        id: users.length + 1,
        username: input.username,
        displayName: input.displayName,
        enabled: input.enabled,
        roles: roleOptions.filter((role) => role.id === roleId),
      }
      users.push(created as (typeof users)[number])
      await route.fulfill({ status: 201, json: { code: 0, message: 'success', data: created } })
      return
    }
    if (path === '/api/users' && request.method() === 'GET') {
      if (await denyWithoutPermissions(['system:user:read'])) return
      const enabled = url.searchParams.get('enabled')
      const keyword = url.searchParams.get('keyword')
      const filtered = users.filter((item) => (enabled === null || item.enabled === (enabled === 'true'))
        && matchesKeyword(keyword, item.username, item.displayName))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/roles' && request.method() === 'GET') {
      if (await denyWithoutPermissions(['system:role:read'])) return
      const enabled = url.searchParams.get('enabled')
      const keyword = url.searchParams.get('keyword')
      const filtered = roles.filter((item) => (enabled === null || item.enabled === (enabled === 'true'))
        && matchesKeyword(keyword, item.code, item.name))
      await route.fulfill({ json: { code: 0, message: 'success', data: pageResponse(url, filtered) } })
      return
    }
    if (path === '/api/roles/options' && request.method() === 'GET') {
      if (await denyWithoutPermissions(['system:user:read', 'system:role:read'], 'any')) return
      await route.fulfill({ json: { code: 0, message: 'success', data: roleOptions } })
      return
    }
    if (path === '/api/permissions' && request.method() === 'GET') {
      if (await denyWithoutPermissions(['system:role:read'])) return
      await route.fulfill({ json: { code: 0, message: 'success', data: permissions } })
      return
    }
    const data = dataByPath[path] ?? []
    await route.fulfill({ json: { code: 0, message: 'success', data } })
  })

  return {
    setSessionPermissions(permissionCodes) {
      session = { ...session, permissions: [...permissionCodes] }
    },
  }
}
