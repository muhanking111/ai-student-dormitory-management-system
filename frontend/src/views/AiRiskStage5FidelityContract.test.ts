import { flushPromises, shallowMount } from '@vue/test-utils'
import { createPinia, setActivePinia } from 'pinia'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import VChart from 'vue-echarts'
import { DemoAiClient } from '../api/ai-demo'
import { setAiClient } from '../api/ai-client'
import { useAuthStore } from '../stores/auth'
import type { AiRiskCase } from '../types/ai'
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
  useAuthStore().$patch({
    initialized: true,
    user: {
      id: 7,
      userName: 'RiskStage5Tester',
      roleCode: 'ADMIN',
      roleCodes: ['ADMIN'],
      permissions,
    },
  })
}

function riskPage(records: AiRiskCase[], input: { page?: number; pageSize?: number } = {}) {
  return {
    records,
    total: records.length,
    page: input.page ?? 1,
    pageSize: input.pageSize ?? 10,
  }
}

describe('AiRiskView stage 5 fidelity contract', () => {
  beforeEach(() => {
    const pinia = createPinia()
    setActivePinia(pinia)
    setAiClient(new DemoAiClient())
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('uses a legal dense demo fixture with six non-flat months and complete governance states', async () => {
    const records = (await new DemoAiClient().listRiskCases({ page: 1, pageSize: 100 })).records
    const monthCounts = new Map<string, number>()
    for (const risk of records) {
      const month = risk.asOf.slice(0, 7)
      monthCounts.set(month, (monthCounts.get(month) ?? 0) + 1)
    }

    expect(records.length).toBeGreaterThanOrEqual(20)
    expect(new Set(records.map((risk) => risk.type))).toEqual(new Set(['入住风险', '维修风险', '卫生风险', '欠费风险']))
    expect(new Set(records.map((risk) => risk.severity))).toEqual(new Set(['low', 'medium', 'high']))
    expect(new Set(records.map((risk) => risk.state))).toEqual(new Set(['open', 'acknowledged', 'resolved', 'dismissed']))
    expect(new Set(records.map((risk) => risk.explanationEvidence.basis))).toEqual(new Set(['model', 'deterministic_degraded']))
    expect(monthCounts.size).toBe(6)
    expect(new Set(monthCounts.values()).size).toBeGreaterThan(1)
  })

  it('keeps overview loading honest instead of falling back to the current page totals', async () => {
    authorize(['ai:risk:read', 'ai:risk:manage'])
    const record = (await new DemoAiClient().listRiskCases()).records[0]!
    let resolveOverview!: (value: ReturnType<typeof riskPage>) => void
    const overview = new Promise<ReturnType<typeof riskPage>>((resolve) => { resolveOverview = resolve })
    setAiClient({
      listRiskCases: vi.fn((input = {}) => input.pageSize === 100
        ? overview
        : Promise.resolve(riskPage([record], input))),
    })

    const wrapper = shallowMount(AiRiskView, {
      global: { plugins: [createPinia()], stubs: globalStubs },
    })
    await flushPromises()

    expect(wrapper.get('[data-testid="risk-overview-loading"][role="status"]').text()).toContain('正在加载风险总览')
    expect(wrapper.findAll('[data-testid="risk-summary-card"]').every((card) => card.attributes('aria-busy') === 'true')).toBe(true)
    expect(wrapper.findAll('[data-testid="risk-summary-card"] strong').every((node) => node.text().includes('—'))).toBe(true)

    resolveOverview(riskPage([record], { page: 1, pageSize: 100 }))
    await flushPromises()
    expect(wrapper.find('[data-testid="risk-overview-loading"]').exists()).toBe(false)
  })

  it('renders zero month-over-month change with a neutral state instead of an upward danger signal', async () => {
    authorize(['ai:risk:read'])
    const base = (await new DemoAiClient().listRiskCases()).records[0]!
    const records = [
      { ...base, id: 'risk-flat-current', asOf: '2026-07-11T10:30:00+08:00' },
      { ...base, id: 'risk-flat-previous', asOf: '2026-06-11T10:30:00+08:00' },
    ]
    setAiClient({ listRiskCases: vi.fn(async (input = {}) => riskPage(records, input)) })

    const wrapper = shallowMount(AiRiskView, {
      global: { plugins: [createPinia()], stubs: globalStubs },
    })
    await flushPromises()

    const card = wrapper.findAll('[data-testid="risk-summary-card"]')
      .find((node) => node.text().includes('维修风险'))
    expect(card).toBeDefined()
    expect(card!.get('.summary-change').classes()).toContain('is-flat')
    expect(card!.get('.summary-change').text()).toContain('(0%)')
  })

  it('keeps the detail workbench stable for empty data and exposes an explicit read-only state', async () => {
    authorize(['ai:risk:read'])
    setAiClient({ listRiskCases: vi.fn(async (input = {}) => riskPage([], input)) })
    const emptyWrapper = shallowMount(AiRiskView, {
      global: { plugins: [createPinia()], stubs: globalStubs },
    })
    await flushPromises()

    expect(emptyWrapper.find('.risk-detail-panel').exists()).toBe(true)
    expect(emptyWrapper.get('[data-testid="risk-detail-empty"]').text()).toContain('选择一条风险线索')

    const pinia = createPinia()
    setActivePinia(pinia)
    authorize(['ai:risk:read'])
    setAiClient(new DemoAiClient())
    const readOnlyWrapper = shallowMount(AiRiskView, {
      global: { plugins: [pinia], stubs: globalStubs },
    })
    await flushPromises()
    expect(readOnlyWrapper.find('[data-testid="risk-action-dock"]').exists()).toBe(false)
    expect(readOnlyWrapper.get('[data-testid="risk-readonly-note"]').text()).toContain('只读权限')
  })

  it('disables chart motion when the operating system requests reduced motion', async () => {
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({
      matches: true,
      media: '(prefers-reduced-motion: reduce)',
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      addListener: vi.fn(),
      removeListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
    authorize(['ai:risk:read'])
    const wrapper = shallowMount(AiRiskView, {
      global: { plugins: [createPinia()], stubs: globalStubs },
    })
    await flushPromises()

    const charts = wrapper.findAllComponents(VChart)
    expect(charts).toHaveLength(2)
    expect(charts.every((chart) => {
      const option = chart.props('option') as { animationDuration?: number } | undefined
      return option?.animationDuration === 0
    })).toBe(true)
  })

  it('uses readable desktop type, 44px targets and a mobile card list without table scrolling', () => {
    const desktopStart = aiRiskViewSource.indexOf('@media (min-width: 1281px) {')
    const mobileStart = aiRiskViewSource.lastIndexOf('@media (max-width: 760px) {')
    const desktopCss = aiRiskViewSource.slice(desktopStart, mobileStart)
    const mobileCss = aiRiskViewSource.slice(mobileStart)

    expect(aiRiskViewSource).toContain('data-testid="risk-mobile-list"')
    expect(aiRiskViewSource).toMatch(/\.risk-table\s*\{[^}]*font-size:\s*12px;/s)
    expect(aiRiskViewSource).toMatch(/\.view-button\s*\{[^}]*min-height:\s*var\(--touch-target\);/s)
    expect(desktopCss).toMatch(/\.risk-mobile-list\s*\{[^}]*display:\s*none;/s)
    expect(mobileCss).toMatch(/\.risk-table-wrap\s*\{[^}]*display:\s*none;/s)
    expect(mobileCss).toMatch(/\.risk-mobile-list\s*\{[^}]*display:\s*grid;/s)
    expect(mobileCss).toMatch(/\.risk-mobile-open\s*\{[^}]*min-height:\s*var\(--touch-target\);/s)
  })
})
