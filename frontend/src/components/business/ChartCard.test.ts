import { mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { describe, expect, it } from 'vitest'
import ChartCard from './ChartCard.vue'

describe('ChartCard', () => {
  it('moreTo 存在时将更多渲染为路由链接', async () => {
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: '/', component: { template: '<div />' } },
        { path: '/notices', component: { template: '<div />' } },
      ],
    })
    await router.push('/')
    await router.isReady()

    const wrapper = mount(ChartCard, {
      props: { title: '最新通知', more: true, moreTo: '/notices' },
      global: { plugins: [router] },
    })

    expect(wrapper.get('a').text()).toContain('更多')
    expect(wrapper.get('a').attributes('href')).toBe('/notices')
  })

  it('未提供 moreTo 时保留非链接的更多提示', () => {
    const wrapper = mount(ChartCard, { props: { title: '待处理事项', more: true } })

    expect(wrapper.find('a').exists()).toBe(false)
    expect(wrapper.get('.more-label').text()).toContain('更多')
  })
})
