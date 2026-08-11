import { mount } from '@vue/test-utils'
import { describe, expect, it } from 'vitest'
import AiCommandBar from './AiCommandBar.vue'
import AiProposalPreview from './AiProposalPreview.vue'
import AiRunStatus from './AiRunStatus.vue'
import AiSafetyState from './AiSafetyState.vue'

describe('AI 共用组件合同', () => {
  it('命令栏使用受控值并在 loading/disabled 时保持明确语义', async () => {
    const longQuestion = `请核对${'宿舍运营风险与授权引用'.repeat(18)}`
    const wrapper = mount(AiCommandBar, {
      props: {
        modelValue: longQuestion,
        label: '查询宿舍运营风险',
        loading: true,
      },
    })

    const form = wrapper.get('form')
    const input = wrapper.get('input')
    const button = wrapper.get('button[type="submit"]')
    expect(form.attributes('aria-busy')).toBe('true')
    expect(input.element.value).toBe(longQuestion)
    expect(input.attributes('readonly')).toBeDefined()
    expect(button.attributes('disabled')).toBeDefined()
    expect(button.text()).toContain('查询中')

    await wrapper.setProps({ loading: false })
    await input.setValue('  本周有哪些运营风险？  ')
    expect(wrapper.emitted('update:modelValue')?.at(-1)).toEqual(['  本周有哪些运营风险？  '])
    await form.trigger('submit')
    expect(wrapper.emitted('submit')).toEqual([['本周有哪些运营风险？']])

    await wrapper.setProps({ disabled: true })
    expect(input.attributes('disabled')).toBeDefined()
    expect(form.classes()).toContain('ai-command-bar--disabled')
  })

  it('审批预览稳定呈现三段流程、长中文和受控动作', async () => {
    const currentValue = `当前值：${'尚未指派，等待人工核对；'.repeat(12)}`
    const proposedValue = `建议值：${'由维修组再次确认后提交；'.repeat(12)}`
    const wrapper = mount(AiProposalPreview, {
      props: {
        currentValue,
        proposedValue,
        impact: '仅变更维修负责人，不自动改变工单状态。',
        stage: 'approval',
        actionLabel: '提交人工审批',
      },
    })

    const steps = wrapper.findAll('[data-proposal-step]')
    expect(steps).toHaveLength(3)
    expect(steps.map((step) => step.text())).toEqual(expect.arrayContaining(['AI 建议', '变更预览', '人工审批']))
    expect(steps.map((step) => step.attributes('data-state'))).toEqual(['completed', 'completed', 'current'])
    expect(wrapper.text()).toContain(currentValue)
    expect(wrapper.text()).toContain(proposedValue)

    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('action')).toEqual([[]])

    await wrapper.setProps({ loading: true })
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(wrapper.get('button').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('button').text()).toContain('处理中')
  })

  it('运行状态为流式和失败状态提供可访问的停止与重试事件', async () => {
    const wrapper = mount(AiRunStatus, { props: { state: 'streaming', showAction: true } })

    expect(wrapper.get('[role="status"]').attributes('aria-busy')).toBe('true')
    expect(wrapper.text()).toContain('正在生成')
    expect(wrapper.get('button').text()).toContain('停止生成')
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('cancel')).toEqual([[]])

    await wrapper.setProps({ state: 'failed', loading: true })
    expect(wrapper.get('[role="alert"]').attributes('aria-busy')).toBe('true')
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(wrapper.get('button').text()).toContain('重试中')

    await wrapper.setProps({ loading: false })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('retry')).toEqual([[]])
  })

  it.each([
    ['NO_PERMISSION', '无权限使用此能力'],
    ['NO_GROUNDED', '暂无可靠来源'],
    ['STALE', '业务数据已变化'],
    ['EXPIRED', '方案已过期'],
    ['DEGRADED', '当前为降级结果'],
    ['FAILED', '操作失败'],
  ] as const)('安全状态 %s 同时提供图标、文字和 ARIA', (state, text) => {
    const wrapper = mount(AiSafetyState, { props: { state } })

    expect(wrapper.attributes('data-state')).toBe(state)
    expect(wrapper.text()).toContain(text)
    expect(wrapper.find('[data-state-icon]').exists()).toBe(true)
    expect(wrapper.attributes('aria-label')).toContain(text)
    expect(['status', 'alert']).toContain(wrapper.attributes('role'))
  })

  it('安全状态动作由父层控制并暴露 disabled/loading', async () => {
    const wrapper = mount(AiSafetyState, {
      props: { state: 'STALE', actionLabel: '刷新预览', loading: true },
    })

    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(wrapper.get('button').attributes('aria-busy')).toBe('true')
    await wrapper.setProps({ loading: false })
    await wrapper.get('button').trigger('click')
    expect(wrapper.emitted('action')).toEqual([[]])
  })
})
