import { describe, expect, it } from 'vitest'
import { editableNoticeStatuses } from './notices'

describe('公告可编辑状态', () => {
  it('新公告和草稿允许选择草稿或发布', () => {
    expect(editableNoticeStatuses()).toEqual(['草稿', '已发布'])
    expect(editableNoticeStatuses('草稿')).toEqual(['草稿', '已发布'])
  })

  it('已发布公告不能在编辑时退回草稿', () => {
    expect(editableNoticeStatuses('已发布')).toEqual(['已发布'])
  })

  it('已撤回公告不提供可编辑状态', () => {
    expect(editableNoticeStatuses('已撤回')).toEqual([])
  })
})
