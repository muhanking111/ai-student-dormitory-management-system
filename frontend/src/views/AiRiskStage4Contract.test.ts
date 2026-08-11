import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { DemoAiClient } from '../api/ai-demo'
import { setAiClient } from '../api/ai-client'
import { useAuthStore } from '../stores/auth'
import AiRiskView from './AiRiskView.vue'
import aiRiskViewSource from './AiRiskView.vue?raw'

vi.mock('ant-design-vue', () => ({
  message: { error: vi.fn(), success: vi.fn(), info: vi.fn() },
}))

vi.mock('../api/ai-client', async () => {
  const actual = await vi.importActual<typeof import('../api/ai-client')>('../api/ai-client')
  return {
    ...actual,
    getAiClientMode: () => 'demo',
    isAiSurfaceEnabled: () => true,
  }
})

const globalStubs = {
  ATextarea: true,
  AiSafetyState: true,
  VChart: true,
}

function authorize(permissions: string[]) {
  const auth = useAuthStore()
  auth.$patch({
    initialized: true,
    user: {
      id: 7,
      userName: 'RiskTester',
      roleCode: 'ADMIN',
      roleCodes: ['ADMIN'],
      permissions,
    },
  })
}

describe('AiRiskView stage 4 contract', () => {
  beforeEach(() => {
    const pinia = createPinia()
    setActivePinia(pinia)
    setAiClient(new DemoAiClient())
  })

  it('exposes all four risk filters and shows case public id instead of subject token in details', async () => {
    authorize(['ai:risk:read', 'ai:risk:manage'])
    const wrapper = shallowMount(AiRiskView, {
      global: { plugins: [createPinia()], stubs: globalStubs },
    })

    await flushPromises()

    expect(wrapper.find('option[value="入住风险"]').exists()).toBe(true)
    expect(wrapper.find('option[value="维修风险"]').exists()).toBe(true)
    expect(wrapper.find('option[value="卫生风险"]').exists()).toBe(true)
    expect(wrapper.find('option[value="欠费风险"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('线索 ID：risk-001')
    expect(wrapper.text()).not.toContain('线索 ID：risk_demo_repair_17')
  })

  it('distinguishes a risk service failure from an empty result and offers retry', async () => {
    authorize(['ai:risk:read', 'ai:risk:manage'])
    setAiClient({
      listRiskCases: vi.fn().mockRejectedValue(new Error('风险服务暂时不可用')),
    })
    const wrapper = shallowMount(AiRiskView, {
      global: { plugins: [createPinia()], stubs: globalStubs },
    })

    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toContain('风险服务暂时不可用')
    expect(wrapper.find('[aria-label="重新加载风险线索"]').exists()).toBe(true)
    expect(wrapper.find('.empty-cell').exists()).toBe(false)
  })

  it('shows an independent overview failure instead of presenting partial totals as success', async () => {
    authorize(['ai:risk:read', 'ai:risk:manage'])
    const records = (await new DemoAiClient().listRiskCases()).records
    setAiClient({
      listRiskCases: vi.fn(async (input = {}) => {
        if (input.pageSize === 100) throw new Error('风险总览暂时不可用')
        return { records, total: records.length, page: input.page ?? 1, pageSize: input.pageSize ?? 10 }
      }),
    })
    const wrapper = shallowMount(AiRiskView, {
      global: { plugins: [createPinia()], stubs: globalStubs },
    })

    await flushPromises()

    expect(wrapper.get('.risk-overview-error[role="alert"]').text()).toContain('风险总览暂时不可用')
    expect(wrapper.findAll('[data-testid="risk-summary-card"] strong').every((node) => node.text().includes('—'))).toBe(true)
  })

  it('keeps every desktop table column visible while pagination and progressive risk actions stay reachable', async () => {
    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:risk:read', 'ai:risk:manage'])
    const wrapper = shallowMount(AiRiskView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })

    await flushPromises()

    const listPanel = wrapper.get('.risk-list-panel')
    const tableRegion = listPanel.get('[data-testid="risk-table-scroll-region"]')
    expect(tableRegion.attributes('tabindex')).toBe('0')
    expect(tableRegion.findAll('th').map((cell) => cell.text())).toEqual([
      '严重度', '风险类型', '规则证据摘要', 'AI 解释', '责任人', 'SLA', '状态', '操作',
    ])
    expect(tableRegion.find('.risk-pagination').exists()).toBe(false)
    expect(listPanel.get('.risk-pagination').element.parentElement).toBe(listPanel.element)

    const detailPanel = wrapper.get('.risk-detail-panel')
    const detailRegion = detailPanel.get('[data-testid="risk-detail-scroll-region"]')
    expect(detailRegion.attributes('tabindex')).toBeUndefined()
    expect(detailRegion.text()).toContain('确定性证据')
    expect(detailRegion.text()).toContain('授权业务快照')
    expect(detailRegion.findAll('h3').map((heading) => heading.text())).toEqual([
      '确定性证据',
      'AI 解释',
      '人工处置时间线',
    ])
    expect(detailRegion.get('.snapshot-strip > strong').text()).toBe('授权业务快照')
    expect(detailRegion.get('.fact-disclosure summary').attributes('aria-label')).toContain('查看完整证据事实')
    expect(detailRegion.text()).toContain('人工处置时间线')
    expect(detailRegion.get('[data-testid="risk-detail-safety"]').text()).toContain('不自动处分或改写业务事实')
    expect(detailPanel.get('[data-testid="risk-action-dock"]').element.parentElement).toBe(detailPanel.element)
    expect(detailPanel.get('[data-testid="risk-action-dock"]').text()).toContain('确认风险')
    expect(aiRiskViewSource).toContain('data-testid="risk-action-form"')

    const ruleVersion = wrapper.get('.rule-list small')
    expect(ruleVersion.attributes('title')).toBe(ruleVersion.text())
    expect(aiRiskViewSource).toMatch(/\.rule-list li > div\s*\{[^}]*min-width:\s*0;/s)
    expect(aiRiskViewSource).toMatch(/\.rule-list small\s*\{[^}]*overflow:\s*hidden;[^}]*text-overflow:\s*ellipsis;[^}]*white-space:\s*nowrap;/s)
  })

  it('binds the tall desktop workbench to the viewport without weakening mobile overflow', () => {
    const desktopStart = aiRiskViewSource.indexOf('@media (min-width: 1281px) {')
    const tallDesktopStart = aiRiskViewSource.indexOf('@media (min-width: 1281px) and (min-height: 900px) {')
    const responsiveStart = aiRiskViewSource.indexOf('@media (max-width: 1280px) {')

    expect(desktopStart).toBeGreaterThan(-1)
    expect(tallDesktopStart).toBeGreaterThan(desktopStart)
    expect(responsiveStart).toBeGreaterThan(tallDesktopStart)

    const desktopCss = aiRiskViewSource.slice(desktopStart, tallDesktopStart)
    const tallDesktopCss = aiRiskViewSource.slice(tallDesktopStart, responsiveStart)
    const responsiveCss = aiRiskViewSource.slice(responsiveStart)

    expect(desktopCss).toMatch(/\.risk-table-wrap\s*\{[^}]*overflow-x:\s*hidden;/s)
    expect(desktopCss).toMatch(/\.risk-table\s*\{[^}]*min-width:\s*0;/s)
    expect(aiRiskViewSource).toMatch(/\.table-cell-clamp\s*\{[^}]*-webkit-line-clamp:\s*2;/s)
    expect(tallDesktopCss).toMatch(/\.risk-workbench\s*\{[^}]*height:\s*clamp\([^;]+100dvh[^;]+\);/s)
    expect(tallDesktopCss).toMatch(/\.risk-detail-scroll\s*\{[^}]*overflow:\s*visible;/s)
    expect(tallDesktopCss).not.toMatch(/\.risk-detail-scroll\s*\{[^}]*overflow-y:\s*auto;/s)
    expect(tallDesktopCss).toMatch(/\.risk-detail-panel\s*\{[^}]*padding:\s*6px\s+12px\s+4px;/s)
    expect(tallDesktopCss).toMatch(/\.detail-metrics\s*\{[^}]*margin-top:\s*4px;/s)
    expect(tallDesktopCss).toMatch(/\.risk-policy-note\s*\{[^}]*margin-top:\s*4px;/s)
    expect(tallDesktopCss).toMatch(/\.risk-detail-core\s*\{[^}]*margin-top:\s*4px;/s)
    expect(tallDesktopCss).toMatch(/\.risk-actions\s*\{[^}]*margin-top:\s*4px;[^}]*padding-top:\s*4px;/s)
    expect(aiRiskViewSource).toMatch(/\.risk-workbench\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1\.15fr\)\s*minmax\(430px,\s*1fr\);/s)
    expect(aiRiskViewSource).toMatch(/\.risk-detail-core\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1\.08fr\)\s*minmax\(0,\s*\.92fr\);/s)
    expect(aiRiskViewSource).toMatch(/\.detail-metrics\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1\.4fr\)\s*minmax\(0,\s*1\.2fr\)\s*minmax\(0,\s*\.7fr\)\s*minmax\(0,\s*\.7fr\);/s)
    expect(aiRiskViewSource).toMatch(/\.detail-metrics dd\s*\{[^}]*overflow-wrap:\s*anywhere;[^}]*white-space:\s*normal;/s)
    expect(aiRiskViewSource).toMatch(/\.fact-disclosure summary\s*\{[^}]*min-height:\s*var\(--touch-target\);/s)
    expect(aiRiskViewSource).toContain('data-testid="risk-detail-safety"')
    expect(responsiveCss).toMatch(/\.risk-detail-scroll\s*\{[^}]*overflow:\s*visible;/s)
    expect(responsiveCss).toMatch(/\.risk-detail-core\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);/s)
  })
})
