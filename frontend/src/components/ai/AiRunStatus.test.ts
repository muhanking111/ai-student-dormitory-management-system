import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AiRunStatus from './AiRunStatus.vue'

describe('AiRunStatus', () => {
  it('对账态使用稳定中文文案且不暴露取消或重试动作', () => {
    const wrapper = mount(AiRunStatus, {
      props: { state: 'needs_reconciliation' as never, showAction: true },
    })

    expect(wrapper.attributes('data-state')).toBe('needs_reconciliation')
    expect(wrapper.attributes('role')).toBe('status')
    expect(wrapper.text()).toContain('需要人工对账')
    expect(wrapper.find('button').exists()).toBe(false)
  })
})
