import type { Notice } from '../types/dormitory'

export type EditableNoticeStatus = '草稿' | '已发布'

export function editableNoticeStatuses(status?: Notice['status']): EditableNoticeStatus[] {
  if (status === '已撤回') return []
  if (status === '已发布') return ['已发布']
  return ['草稿', '已发布']
}
