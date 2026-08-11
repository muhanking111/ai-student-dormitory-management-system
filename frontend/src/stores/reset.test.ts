import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it } from 'vitest'
import { useDormitoryStore } from './dormitory'
import { useOperationsStore } from './operations'
import { resetBusinessStores } from './reset'
import { resetAiStores } from './reset'
import { useAiKnowledgeStore } from './aiKnowledge'

describe('会话失效业务状态清理', () => {
  it('清空已加载的 Dashboard 和运营数据', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const dashboard = useDormitoryStore()
    const operations = useOperationsStore()
    dashboard.notices = [{ id: 1, title: '过期公告', type: '通知', date: '2026-07-11', publisher: '管理员', status: '已发布' }]
    dashboard.loaded = true
    operations.repairOrders = [{ id: 1, code: 'WX-OLD', reporter: '旧用户', location: '1号楼', type: '水电维修', date: '2026-07-11', status: '待处理' }]

    resetBusinessStores(pinia)

    expect(dashboard.notices).toEqual([])
    expect(dashboard.loaded).toBe(false)
    expect(operations.repairOrders).toEqual([])
  })
})

describe('知识管理会话状态清理', () => {
  it('清空来源、版本、摄取任务和上传正文引用', () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    const knowledge = useAiKnowledgeStore()
    knowledge.sources = [{ id: 'source-1', name: '旧账号知识', sourceType: 'UPLOAD', ownerUserId: 1, classification: 'L1', matchMode: 'ANY', aclVersion: 1, status: 'ENABLED', permissions: [] }]
    knowledge.selectedSourceId = 'source-1'
    knowledge.uploadText = '旧账号正文'

    resetAiStores(pinia)

    expect(knowledge.sources).toEqual([])
    expect(knowledge.selectedSourceId).toBe('')
    expect(knowledge.uploadText).toBe('')
  })
})
