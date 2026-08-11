<template>
  <section class="risk-center" aria-label="智能风险内容">
    <AiSafetyState v-if="!enabled" message="AI 能力当前关闭，原业务不受影响。" tone="warning" />

    <nav class="risk-breadcrumb" aria-label="面包屑">AI 能力 <span>/</span> 智能风险中心</nav>

    <div class="risk-summary-grid" aria-label="风险分类总览">
      <article
        v-for="card in summaryCards"
        :key="card.type"
        class="risk-summary-card"
        data-testid="risk-summary-card"
        :aria-busy="store.overviewLoading"
      >
        <div class="summary-main">
          <span class="summary-icon" :style="{ color: card.color, backgroundColor: card.softColor }">
            <component :is="card.icon" />
          </span>
          <div>
            <h2>{{ card.type }}</h2>
            <strong>{{ store.overviewLoading || store.overviewError ? '—' : card.count }}<small v-if="!store.overviewLoading && !store.overviewError"> 个</small></strong>
          </div>
        </div>
        <div v-if="!store.overviewLoading && !store.overviewError" class="summary-change" :class="deltaTone(card.delta)">
          <span class="summary-change-metric">
            <span class="summary-period">较上月</span>
            <component :is="deltaIcon(card.delta)" />
            <b>{{ Math.abs(card.delta) }}</b>
            <em class="summary-rate">{{ card.rateLabel }}</em>
          </span>
          <svg
            class="summary-sparkline"
            data-testid="risk-summary-sparkline"
            viewBox="0 0 76 30"
            role="img"
            :aria-label="`${card.type}近六个月趋势：${card.history.join('、')}`"
            :style="{ color: card.color }"
          >
            <title>{{ card.type }}近六个月可见规则线索趋势</title>
            <polyline :points="card.sparkline" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </div>
        <div v-else-if="store.overviewLoading" class="summary-change unavailable"><span>总览加载中</span></div>
        <div v-else class="summary-change unavailable"><span>统计暂不可用</span></div>
      </article>
    </div>

    <div v-if="store.overviewLoading" class="risk-overview-state risk-overview-loading" data-testid="risk-overview-loading" role="status">
      <ReloadOutlined class="overview-spinner" />
      <span>正在加载风险总览，当前分页数据不会冒充完整统计。</span>
    </div>
    <div v-else-if="store.overviewError" class="risk-overview-state risk-overview-error" role="alert">
      <InfoCircleFilled />
      <span>{{ store.overviewError }}，风险案例列表仍可继续使用。</span>
      <button type="button" aria-label="重新加载风险总览" @click="retryOverview"><ReloadOutlined />重新加载</button>
    </div>
    <div v-else-if="!overviewCases.length" class="risk-overview-state risk-overview-empty" role="status">
      <InfoCircleFilled />
      <span>当前权限范围内暂无可见风险信号。</span>
    </div>

    <div class="risk-analytics" :aria-busy="store.overviewLoading">
      <section class="risk-panel trend-panel" aria-labelledby="risk-trend-title">
        <header class="panel-heading">
          <div>
            <h2 id="risk-trend-title">风险趋势</h2>
            <p>近 6 个月可见规则线索</p>
          </div>
          <span class="period-badge">近 6 个月</span>
        </header>
        <div class="chart-legend" aria-hidden="true">
          <span><i class="legend-bar"></i>较上月变化（个）</span>
          <span><i class="legend-line"></i>风险线索趋势</span>
        </div>
        <div class="risk-trend-chart-stage">
          <v-chart class="risk-trend-chart" :option="trendOption" autoresize :aria-label="trendAriaLabel" />
          <div
            v-if="trendSparseState"
            class="risk-trend-sparse-state"
            :data-risk-trend-sparse-state="trendSparseState.kind"
            role="status"
          >
            <InfoCircleFilled aria-hidden="true" />
            <span>
              <strong>{{ trendSparseState.title }}</strong>
              <small>{{ trendSparseState.detail }}</small>
            </span>
          </div>
        </div>
        <table class="sr-only" data-testid="risk-trend-data">
          <caption>近六个月风险线索趋势数据</caption>
          <thead><tr><th>月份</th><th>风险线索</th><th>较上月变化</th></tr></thead>
          <tbody><tr v-for="row in trendDataRows" :key="row.month"><td>{{ row.month }}</td><td>{{ row.value }}</td><td>{{ row.delta }}</td></tr></tbody>
        </table>
      </section>

      <section class="risk-panel distribution-panel" aria-labelledby="risk-distribution-title">
        <header class="panel-heading">
          <div><h2 id="risk-distribution-title">风险分布</h2><p>按运营信号类别</p></div>
        </header>
        <v-chart class="risk-distribution-chart" :option="distributionOption" autoresize aria-label="风险类别分布环形图，四类数值同时见风险分类总览" />
        <table class="sr-only" data-testid="risk-distribution-data">
          <caption>当前月份风险类别分布数据</caption>
          <thead><tr><th>风险类别</th><th>线索数量</th></tr></thead>
          <tbody><tr v-for="card in summaryCards" :key="card.type"><td>{{ card.type }}</td><td>{{ card.count }}</td></tr></tbody>
        </table>
      </section>

      <section class="risk-panel rule-panel" aria-labelledby="rule-state-title">
        <header class="panel-heading">
          <div><h2 id="rule-state-title">规则状态</h2><p>当前版本与命中情况</p></div>
        </header>
        <ul class="rule-list">
          <li v-for="rule in ruleRows" :key="rule.type">
            <span class="rule-state-icon" :class="rule.tone" :aria-label="rule.label">
              <component :is="rule.icon" />
            </span>
            <div><strong>{{ rule.type }}规则集</strong><small :title="rule.version">{{ rule.version }}</small></div>
            <span class="rule-status" :class="rule.tone">{{ rule.label }}</span>
          </li>
        </ul>
      </section>
    </div>

    <div class="risk-workbench">
      <section class="risk-panel risk-list-panel" aria-label="风险案例列表">
        <header class="panel-heading list-heading">
          <div><h2>风险线索列表</h2><p>共 {{ store.total }} 条，AI 仅解释与排序</p></div>
          <button type="button" class="icon-button" title="刷新风险线索" aria-label="刷新风险线索" @click="refresh">
            <ReloadOutlined />
          </button>
        </header>

        <form class="risk-filters" aria-label="风险线索筛选" @submit.prevent="applyFilters">
          <label>
            <span>风险类型</span>
            <select v-model="typeFilter" aria-label="风险类型筛选">
              <option value="">全部风险类型</option>
              <option v-for="type in riskTypeOptions" :key="type" :value="type">{{ type }}</option>
            </select>
          </label>
          <label>
            <span>处置状态</span>
            <select v-model="stateFilter" aria-label="风险状态筛选">
              <option value="">全部状态</option>
              <option value="open">待确认</option>
              <option value="acknowledged">处理中</option>
              <option value="resolved">已解决</option>
              <option value="dismissed">已驳回</option>
            </select>
          </label>
          <label class="keyword-field">
            <span>线索检索</span>
            <span class="search-input">
              <SearchOutlined />
              <input v-model.trim="keyword" aria-label="搜索 token 或线索摘要" placeholder="搜索 token 或线索摘要" />
            </span>
          </label>
          <button type="submit" class="filter-button">查询</button>
        </form>

        <div v-if="store.error" class="risk-error" role="alert">
          <span>{{ store.error }}</span>
          <button type="button" aria-label="重新加载风险线索" @click="retryLoad"><ReloadOutlined />重新加载</button>
        </div>
        <div v-else-if="store.loading" class="risk-loading" role="status">正在加载确定性风险信号…</div>
        <div
          v-else
          class="risk-table-wrap"
          data-testid="risk-table-scroll-region"
          role="region"
          aria-label="风险线索表格"
          tabindex="0"
        >
          <table class="risk-table">
            <thead>
              <tr>
                <th>严重度</th><th>风险类型</th><th>规则证据摘要</th><th>AI 解释</th>
                <th>责任人</th><th>SLA</th><th>状态</th><th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="risk in filteredCases"
                :key="risk.id"
                :class="{ selected: store.selectedId === risk.id }"
                tabindex="0"
                :aria-current="store.selectedId === risk.id ? 'true' : undefined"
                @click="selectRisk(risk.id)"
                @keydown.enter="selectRisk(risk.id)"
                @keydown.space.prevent="selectRisk(risk.id)"
              >
                <td><span class="severity-tag" :class="`severity-${risk.severity}`">{{ severityLabel(risk.severity) }}</span></td>
                <td><strong>{{ risk.type }}</strong><small :title="risk.subjectToken">{{ risk.subjectToken }}</small></td>
                <td class="evidence-cell"><span class="table-cell-clamp" :title="risk.evidenceSummary">{{ risk.evidenceSummary }}</span></td>
                <td class="explanation-cell"><span class="table-cell-clamp" :title="risk.explanationEvidence.text">{{ risk.explanationEvidence.text }}</span></td>
                <td><span class="table-cell-clamp" :title="risk.assignee">{{ risk.assignee }}</span></td>
                <td><span class="table-cell-clamp" :title="risk.sla">{{ risk.sla }}</span></td>
                <td><span class="state-tag" :class="`state-${risk.state}`">{{ stateLabel(risk.state) }}</span></td>
                <td>
                  <button type="button" class="view-button" :aria-label="`查看${risk.subjectToken}详情`" @click.stop="selectRisk(risk.id)">
                    <EyeOutlined /> 查看
                  </button>
                </td>
              </tr>
              <tr v-if="!filteredCases.length"><td colspan="8" class="empty-cell">当前筛选条件下暂无风险线索</td></tr>
            </tbody>
          </table>
        </div>
        <ul v-if="!store.error && !store.loading" class="risk-mobile-list" data-testid="risk-mobile-list" aria-label="风险线索移动列表">
          <li v-for="risk in filteredCases" :key="risk.id" :class="{ selected: store.selectedId === risk.id }">
            <div class="risk-mobile-heading">
              <span class="severity-tag" :class="`severity-${risk.severity}`">{{ severityLabel(risk.severity) }}风险</span>
              <span class="state-tag" :class="`state-${risk.state}`">{{ stateLabel(risk.state) }}</span>
            </div>
            <strong>{{ risk.type }}</strong>
            <p>{{ risk.evidenceSummary }}</p>
            <dl>
              <div><dt>责任人</dt><dd>{{ risk.assignee }}</dd></div>
              <div><dt>SLA</dt><dd>{{ risk.sla }}</dd></div>
            </dl>
            <button type="button" class="risk-mobile-open" :aria-label="`查看${risk.subjectToken}详情`" @click="selectRisk(risk.id, { focusDetail: true })">
              <EyeOutlined />查看线索详情<RightOutlined />
            </button>
          </li>
          <li v-if="!filteredCases.length" class="risk-mobile-empty">当前筛选条件下暂无风险线索</li>
        </ul>

        <nav class="risk-pagination" aria-label="风险线索分页">
          <span>共 {{ store.total }} 条</span>
          <div class="page-controls">
            <button type="button" aria-label="上一页" :disabled="store.page <= 1" @click="goPage(store.page - 1)"><LeftOutlined /></button>
            <button type="button" class="active" :aria-current="true">{{ store.page }}</button>
            <span>/ {{ totalPages }}</span>
            <button type="button" aria-label="下一页" :disabled="store.page >= totalPages" @click="goPage(store.page + 1)"><RightOutlined /></button>
          </div>
          <label><span class="sr-only">每页条数</span>
            <select v-model.number="pageSize" aria-label="每页条数" @change="changePageSize">
              <option :value="5">5 条/页</option><option :value="10">10 条/页</option><option :value="20">20 条/页</option>
            </select>
          </label>
        </nav>
      </section>

      <aside class="risk-panel risk-detail-panel" aria-label="风险案例详情">
        <template v-if="store.selected">
        <header class="detail-heading">
          <div><h2 ref="detailHeadingRef" data-testid="risk-detail-heading" tabindex="-1">线索详情</h2><p>线索 ID：{{ store.selected.id }}</p></div>
          <span class="severity-tag" :class="`severity-${store.selected.severity}`">{{ severityLabel(store.selected.severity) }}风险</span>
        </header>

        <div
          class="risk-detail-scroll"
          data-testid="risk-detail-scroll-region"
          role="region"
          aria-label="风险详情证据与人工处置时间线"
        >
          <dl class="detail-metrics">
            <div><dt>规则版本</dt><dd :title="store.selected.ruleVersion">{{ store.selected.ruleVersion }}</dd></div>
            <div><dt>数据截至时间</dt><dd :title="formatTime(store.selected.asOf)">{{ formatTime(store.selected.asOf) }}</dd></div>
            <div><dt>引用来源</dt><dd :title="`${factEntries(store.selected.signalEvidence.facts).length} 项`">{{ factEntries(store.selected.signalEvidence.facts).length }} 项</dd></div>
            <div><dt>信号性质</dt><dd :title="confidenceLabel(store.selected.confidence)">{{ confidenceLabel(store.selected.confidence) }}</dd></div>
          </dl>

          <div class="risk-policy-note"><InfoCircleFilled /><strong>规则信号，不代表学生评价</strong></div>

          <div class="risk-detail-core">
            <div class="risk-detail-core__evidence">
              <section class="evidence-section evidence-summary-section">
                <h3>确定性证据</h3>
                <p>{{ store.selected.evidenceSummary }}</p>
                <div class="snapshot-strip" :title="`${subjectTypeLabel(store.selected.businessSnapshot.subjectType)} · ${store.selected.businessSnapshot.subjectToken} · 捕获时间 ${formatTime(store.selected.businessSnapshot.capturedAt)}`">
                  <strong>授权业务快照</strong>
                  <span>{{ subjectTypeLabel(store.selected.businessSnapshot.subjectType) }} · {{ store.selected.businessSnapshot.subjectToken }} · {{ formatTime(store.selected.businessSnapshot.capturedAt) }}</span>
                </div>
                <details class="fact-disclosure">
                  <summary :aria-label="`查看完整证据事实，共 ${factEntries(store.selected.signalEvidence.facts).length} 项`">完整事实（{{ factEntries(store.selected.signalEvidence.facts).length }}）</summary>
                  <dl class="fact-grid">
                    <div v-for="fact in factEntries(store.selected.signalEvidence.facts)" :key="fact[0]">
                      <dt>{{ fact[0] }}</dt><dd>{{ fact[1] }}</dd>
                    </div>
                  </dl>
                </details>
                <div class="ai-explanation-block">
                  <h3>AI 解释</h3>
                  <p class="ai-explanation-text" :title="store.selected.explanationEvidence.text">{{ store.selected.explanationEvidence.text }}</p>
                  <small class="ai-explanation-meta" :title="`依据：${explanationBasisLabel(store.selected.explanationEvidence.basis)} · ${store.selected.explanationEvidence.policyVersion}`">依据：{{ explanationBasisLabel(store.selected.explanationEvidence.basis) }} · {{ store.selected.explanationEvidence.policyVersion }}</small>
                  <div v-if="store.selected.degraded" class="ai-degraded-inline" role="status"><InfoCircleFilled />模型不可用，当前仅展示规则证据。</div>
                </div>
              </section>
            </div>

            <div class="risk-detail-core__human">
              <section class="evidence-section timeline-section">
                <h3>人工处置时间线</h3>
                <ol class="human-timeline">
                  <li v-for="event in store.selected.humanEvidence.events" :key="event.id">
                    <span class="timeline-dot"></span>
                    <div><strong>{{ humanEventLabel(event.type) }}</strong><p :title="event.detail">{{ event.detail }}</p><small>{{ event.actor }} · {{ formatTime(event.occurredAt) }}</small></div>
                  </li>
                  <li v-if="!store.selected.humanEvidence.events.length" class="timeline-empty">尚无人工处置记录</li>
                </ol>
              </section>

              <div class="detail-safety" data-testid="risk-detail-safety">
                <div><SafetyCertificateOutlined /><p><strong>安全提示</strong><span>AI 仅用于解释与排序，不自动处分或改写业务事实。</span></p></div>
              </div>
            </div>
          </div>
        </div>

        <div v-if="canManage" class="risk-actions" data-testid="risk-action-dock" :aria-busy="actionLoading" aria-label="风险人工处置操作">
          <div class="action-buttons">
            <button v-if="store.selected.state === 'open'" type="button" aria-label="确认风险" :disabled="actionLoading" @click="openRiskAction('acknowledge')"><SafetyCertificateOutlined aria-hidden="true" />确认风险</button>
            <button v-if="['open', 'acknowledged'].includes(store.selected.state)" type="button" aria-label="标记已解决" :disabled="actionLoading" @click="openRiskAction('resolve')"><CheckCircleFilled aria-hidden="true" />标记已解决</button>
            <button v-if="['open', 'acknowledged'].includes(store.selected.state)" type="button" class="secondary" aria-label="驳回信号" :disabled="actionLoading" @click="openRiskAction('dismiss')"><CloseCircleOutlined aria-hidden="true" />驳回信号</button>
          </div>
          <p v-if="!['open', 'acknowledged'].includes(store.selected.state)" class="risk-action-complete">当前线索已完成人工处置，完整记录见上方时间线。</p>
        </div>
        <div v-else class="risk-readonly-note" data-testid="risk-readonly-note">
          <SafetyCertificateOutlined />
          <p><strong>只读权限</strong><span>你可以核验规则证据与人工时间线，但不能提交风险处置。</span></p>
        </div>
        </template>
        <div v-else class="risk-detail-empty" data-testid="risk-detail-empty" role="status">
          <SafetyCertificateOutlined />
          <div><strong>选择一条风险线索</strong><p>详情区将展示规则版本、确定性证据、AI 解释和人工处置时间线。</p></div>
        </div>
      </aside>
    </div>

    <a-modal
      v-if="canManage && store.selected"
      v-model:open="actionOpen"
      :title="actionTitle"
      :mask-closable="!actionLoading"
      :keyboard="!actionLoading"
      :width="560"
      destroy-on-close
      @cancel="cancelRiskAction"
    >
      <div class="risk-action-form" data-testid="risk-action-form" :aria-busy="actionLoading">
        <div class="risk-action-context">
          <InfoCircleFilled />
          <p><strong>{{ actionSubmitLabel }}</strong><span>线索 {{ store.selected.id }} · {{ store.selected.type }}。提交后将写入人工处置时间线并受版本冲突校验保护。</span></p>
        </div>
        <div v-if="actionError" class="risk-action-error" data-testid="risk-action-error" role="alert" aria-live="assertive">
          <span>{{ actionError }}</span>
          <button type="button" :disabled="actionLoading" @click="refreshActionContext"><ReloadOutlined />刷新风险事实</button>
        </div>
        <label class="risk-action-field">
          <span>人工处置说明 <strong aria-hidden="true">*</strong></span>
          <a-textarea v-model:value="actionDetail" aria-label="风险处置说明" :maxlength="500" show-count placeholder="请输入人工核验结论或处置依据" />
        </label>
        <label class="risk-due">处置截止时间（可选）
          <input v-model="dueAtLocal" type="datetime-local" aria-label="风险处置截止时间" />
        </label>
      </div>
      <template #footer>
        <div class="risk-action-modal-footer">
          <button type="button" class="secondary" :disabled="actionLoading" @click="cancelRiskAction">取消</button>
          <button type="button" data-testid="risk-action-submit" :disabled="actionLoading || !actionDetail.trim()" @click="submitRiskAction">
            <ReloadOutlined v-if="actionLoading" class="overview-spinner" />{{ actionLoading ? '提交中' : actionSubmitLabel }}
          </button>
        </div>
      </template>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { BarChart, LineChart, PieChart } from 'echarts/charts'
import { GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'
import {
  ArrowDownOutlined, ArrowUpOutlined, CheckCircleFilled, ClearOutlined, ClockCircleFilled, CloseCircleOutlined,
  DollarCircleOutlined, EyeOutlined, HomeOutlined, InfoCircleFilled, LeftOutlined,
  MinusOutlined, ReloadOutlined, RightOutlined, SafetyCertificateOutlined, SearchOutlined, ToolOutlined,
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { isAiSurfaceEnabled } from '../api/ai-client'
import AiSafetyState from '../components/ai/AiSafetyState.vue'
import { useAuthStore } from '../stores/auth'
import { useAiRiskStore } from '../stores/aiRisk'
import type { AiRiskCase, AiRiskSeverity, AiRiskState } from '../types/ai'

use([CanvasRenderer, BarChart, LineChart, PieChart, GridComponent, TooltipComponent, LegendComponent])

const auth = useAuthStore()
const store = useAiRiskStore()
const enabled = isAiSurfaceEnabled()
const actionDetail = ref('')
const dueAtLocal = ref('')
const actionError = ref('')
const actionLoading = ref(false)
type RiskAction = 'acknowledge' | 'resolve' | 'dismiss'
const actionOpen = ref(false)
const pendingAction = ref<RiskAction | null>(null)
const detailHeadingRef = ref<HTMLElement | null>(null)
let actionRequestEpoch = 0
const typeFilter = ref('')
const stateFilter = ref('')
const keyword = ref('')
const pageSize = ref(10)
const riskTypeOptions: AiRiskCase['type'][] = ['入住风险', '维修风险', '卫生风险', '欠费风险']
const canManage = computed(() => auth.hasPermission('ai:risk:manage'))
const actionLabels: Record<RiskAction, string> = {
  acknowledge: '确认风险',
  resolve: '标记已解决',
  dismiss: '驳回信号',
}
const riskActionMessageKey = 'ai-risk-action'
const actionTitle = computed(() => pendingAction.value ? `${actionLabels[pendingAction.value]}处置` : '人工风险处置')
const actionSubmitLabel = computed(() => pendingAction.value ? actionLabels[pendingAction.value] : '提交处置')
const overviewCases = computed(() => store.overviewCases)
const chartAnimationDuration = typeof window !== 'undefined'
  && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 0 : 250
const categoryDefinitions = [
  { type: '入住风险', icon: HomeOutlined, color: '#2563eb', softColor: '#e8efff', matches: (risk: AiRiskCase) => risk.type === '入住风险' },
  { type: '维修风险', icon: ToolOutlined, color: '#10b981', softColor: '#e4f8f1', matches: (risk: AiRiskCase) => risk.type === '维修风险' },
  { type: '卫生风险', icon: ClearOutlined, color: '#7c3aed', softColor: '#f0e8ff', matches: (risk: AiRiskCase) => risk.signalEvidence.riskType.includes('hygiene') },
  { type: '欠费风险', icon: DollarCircleOutlined, color: '#f59e0b', softColor: '#fff3df', matches: (risk: AiRiskCase) => risk.signalEvidence.riskType.includes('payment') },
]

const latestMonth = computed(() => {
  const timestamps = overviewCases.value.map((item) => Date.parse(item.asOf)).filter(Number.isFinite)
  return new Date(timestamps.length ? Math.max(...timestamps) : Date.now())
})
const monthKey = (date: Date) => `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}`
const relativeMonth = (offset: number) => new Date(latestMonth.value.getFullYear(), latestMonth.value.getMonth() + offset, 1)
const countFor = (matches: (risk: AiRiskCase) => boolean, offset: number) => {
  const key = monthKey(relativeMonth(offset))
  return overviewCases.value.filter((risk) => matches(risk) && monthKey(new Date(risk.asOf)) === key).length
}

function sparklinePoints(values: number[]) {
  const width = 76
  const height = 30
  const padding = 3
  const minimum = Math.min(...values, 0)
  const maximum = Math.max(...values, 1)
  const range = Math.max(1, maximum - minimum)
  return values.map((value, index) => {
    const x = padding + (index * (width - padding * 2)) / Math.max(1, values.length - 1)
    const y = height - padding - ((value - minimum) / range) * (height - padding * 2)
    return `${x.toFixed(1)},${y.toFixed(1)}`
  }).join(' ')
}

const summaryCards = computed(() => categoryDefinitions.map((category) => {
  const history = Array.from({ length: 6 }, (_, index) => countFor(category.matches, index - 5))
  const current = history.at(-1) ?? 0
  const previous = history.at(-2) ?? 0
  const delta = current - previous
  const rateLabel = previous > 0
    ? delta === 0 ? '(0%)' : `(${delta > 0 ? '+' : ''}${Math.round((delta / previous) * 100)}%)`
    : current > 0 ? '(新增)' : '(0%)'
  return { ...category, count: current, delta, rateLabel, history, sparkline: sparklinePoints(history) }
}))

const ruleRows = computed(() => categoryDefinitions.map((category) => {
  const currentMonth = monthKey(latestMonth.value)
  const matching = overviewCases.value.filter((item) => category.matches(item) && monthKey(new Date(item.asOf)) === currentMonth)
  const versions = [...new Set(matching.map((item) => item.ruleVersion))]
  const pending = matching.filter((item) => item.state === 'open' || item.state === 'acknowledged').length
  if (!matching.length) {
    return {
      type: category.type.replace('风险', ''), version: '当前无可见版本', tone: 'no-hit',
      label: '暂无可见命中', icon: ClockCircleFilled,
    }
  }
  if (pending) {
    return {
      type: category.type.replace('风险', ''), version: versions.join(' / '), tone: 'needs-review',
      label: `${pending} 条待人工复核`, icon: InfoCircleFilled,
    }
  }
  return {
    type: category.type.replace('风险', ''), version: versions.join(' / '), tone: 'handled',
    label: `${matching.length} 条本期已处置`, icon: CheckCircleFilled,
  }
}))

const filteredCases = computed(() => store.cases)

const totalPages = computed(() => Math.max(1, Math.ceil(store.total / store.pageSize)))
const trendSeries = computed(() => {
  const months = Array.from({ length: 6 }, (_, index) => relativeMonth(index - 5))
  const values = months.map((month) => overviewCases.value.filter((risk) => monthKey(new Date(risk.asOf)) === monthKey(month)).length)
  const deltas = values.map((value, index) => index === 0 ? 0 : value - values[index - 1]!)
  return { months, values, deltas }
})
const trendDataRows = computed(() => trendSeries.value.months.map((month, index) => ({
  month: `${month.getFullYear()}年${month.getMonth() + 1}月`,
  value: trendSeries.value.values[index] ?? 0,
  delta: trendSeries.value.deltas[index] ?? 0,
})))
const trendSparseState = computed(() => {
  const activeMonths = trendSeries.value.values.filter((value) => value > 0).length
  const total = trendSeries.value.values.reduce((sum, value) => sum + value, 0)
  if (activeMonths === 0) {
    return {
      kind: 'zero',
      title: '当前无可见历史线索',
      detail: '图表保留近 6 个月真实零值，不填充模拟数据。',
    } as const
  }
  if (activeMonths === 1) {
    return {
      kind: 'limited',
      title: '历史基线不足',
      detail: `近 6 个月仅 1 个月有可见线索，共 ${total} 条；暂不解读升降趋势。`,
    } as const
  }
  return null
})
const trendAriaLabel = computed(() => {
  const base = '近六个月风险线索趋势图'
  return trendSparseState.value
    ? `${base}。${trendSparseState.value.title}；${trendSparseState.value.detail}`
    : base
})

const trendOption = computed(() => ({
  animationDuration: chartAnimationDuration,
  color: ['#9cc7f5', '#2563eb'],
  tooltip: { trigger: 'axis' },
  grid: { top: 22, left: 42, right: 38, bottom: 34 },
  xAxis: { type: 'category', data: trendSeries.value.months.map((item) => `${item.getMonth() + 1}月`), axisTick: { show: false }, axisLine: { lineStyle: { color: '#dce5f1' } }, axisLabel: { color: '#64748b' } },
  yAxis: [
    { type: 'value', minInterval: 1, axisLabel: { color: '#64748b' }, splitLine: { lineStyle: { color: '#edf2f8' } } },
    { type: 'value', minInterval: 1, axisLabel: { color: '#64748b' }, splitLine: { show: false } },
  ],
  series: [
    { name: '较上月变化', type: 'bar', barWidth: 22, data: trendSeries.value.deltas, itemStyle: { borderRadius: [3, 3, 0, 0] } },
    { name: '风险线索趋势', type: 'line', yAxisIndex: 1, smooth: true, symbolSize: 7, lineStyle: { width: 3 }, data: trendSeries.value.values },
  ],
}))

const distributionOption = computed(() => {
  const data = summaryCards.value.map((card) => ({ value: card.count, name: card.type }))
  const total = data.reduce((sum, item) => sum + item.value, 0)
  return {
    animationDuration: chartAnimationDuration,
    color: categoryDefinitions.map((item) => item.color),
    tooltip: { trigger: 'item' },
    legend: { orient: 'vertical', right: 4, top: 'center', itemWidth: 9, itemHeight: 9, textStyle: { color: '#64748b', fontSize: 12 } },
    series: [{
      type: 'pie', radius: ['50%', '70%'], center: ['34%', '52%'], avoidLabelOverlap: true,
      label: { show: true, position: 'center', formatter: `{total|${total}}\n{label|总计}`, rich: { total: { color: '#111827', fontSize: 24, fontWeight: 700, lineHeight: 30 }, label: { color: '#64748b', fontSize: 12 } } },
      data: data.some((item) => item.value) ? data : [{ value: 1, name: '暂无风险线索', itemStyle: { color: '#e7edf6' } }],
    }],
  }
})

const severityLabel = (value: AiRiskSeverity) => ({ low: '低', medium: '中', high: '高' })[value]
const stateLabel = (value: AiRiskState) => ({ open: '待确认', acknowledged: '处理中', resolved: '已解决', dismissed: '已驳回' })[value]
const deltaTone = (value: number) => value > 0 ? 'is-up' : value < 0 ? 'is-down' : 'is-flat'
const deltaIcon = (value: number) => value > 0 ? ArrowUpOutlined : value < 0 ? ArrowDownOutlined : MinusOutlined
const formatTime = (value: string) => value.replace('T', ' ').replace(/Z$/, '').slice(0, 16)
const factEntries = (facts: Record<string, string | number | boolean>) => Object.entries(facts)
const explanationBasisLabel = (basis: 'model' | 'deterministic_degraded') => basis === 'model' ? '受控模型 run' : '确定性降级解释'
const confidenceLabel = (confidence?: number) => confidence === undefined ? '规则确定' : `${Math.round(confidence * 100)}%`
const subjectTypeLabel = (value: AiRiskCase['businessSnapshot']['subjectType']) => ({
  REPAIR_ORDER: '维修工单', DORMITORY: '宿舍资源', CHECK_IN_APPLICATION: '入住申请', PAYMENT: '缴费账单', OPERATION_TASK: '运营任务',
})[value]
const humanEventLabel = (value: string) => ({ OPENED: 'AI 识别并生成线索', ACKNOWLEDGED: '人工确认并开始处理', RESOLVE: '人工标记已解决', RESOLVED: '人工标记已解决', DISMISS: '人工驳回信号', DISMISSED: '人工驳回信号' })[value] ?? value

function currentQuery(page = store.page) {
  return {
    page,
    pageSize: pageSize.value,
    type: typeFilter.value || undefined,
    state: stateFilter.value || undefined,
    keyword: keyword.value || undefined,
  }
}
async function applyFilters() { await store.load(currentQuery(1)) }
async function retryLoad() { await store.load(currentQuery()) }
async function retryOverview() { await store.loadOverview() }
async function refresh() {
  await Promise.all([store.load(currentQuery()), store.loadOverview()])
  if (store.error || store.overviewError) message.error(store.error ?? store.overviewError ?? '风险数据刷新失败')
  else message.success('风险线索已刷新')
}
async function goPage(page: number) { if (page >= 1 && page <= totalPages.value) await store.load(currentQuery(page)) }
async function changePageSize() { await store.load(currentQuery(1)) }
async function selectRisk(id: string, options: { focusDetail?: boolean } = {}) {
  store.selectedId = id
  if (!options.focusDetail || !window.matchMedia('(max-width: 760px)').matches) return
  await nextTick()
  if (store.selectedId !== id) return
  detailHeadingRef.value?.scrollIntoView({ behavior: 'auto', block: 'start', inline: 'nearest' })
  detailHeadingRef.value?.focus()
}
function openRiskAction(action: RiskAction) {
  if (actionLoading.value) return
  pendingAction.value = action
  actionError.value = ''
  actionOpen.value = true
}

function cancelRiskAction() {
  if (actionLoading.value) return
  actionOpen.value = false
  pendingAction.value = null
  actionDetail.value = ''
  dueAtLocal.value = ''
  actionError.value = ''
}

async function submitRiskAction() {
  if (!pendingAction.value || !actionDetail.value.trim()) return
  await act(pendingAction.value)
}

async function act(action: RiskAction) {
  if (!store.selected || actionLoading.value) return
  const selectedId = store.selected.id
  const actionContext = `线索 ${selectedId}：${actionLabels[action]}`
  const requestEpoch = ++actionRequestEpoch
  const dueAt = dueAtLocal.value ? new Date(dueAtLocal.value).toISOString() : undefined
  actionError.value = ''
  actionLoading.value = true
  try {
    await store.act(selectedId, action, actionDetail.value, dueAt)
    if (requestEpoch !== actionRequestEpoch || store.selectedId !== selectedId) return
    actionOpen.value = false
    pendingAction.value = null
    actionDetail.value = ''
    dueAtLocal.value = ''
    message.success({ key: riskActionMessageKey, content: `${actionContext}已记录` })
  } catch (error) {
    if (requestEpoch !== actionRequestEpoch || store.selectedId !== selectedId) return
    actionError.value = error instanceof Error ? error.message : '风险处置失败，请刷新事实后重试'
    message.error({ key: riskActionMessageKey, content: `${actionContext}失败。${actionError.value}` })
  } finally {
    if (requestEpoch === actionRequestEpoch) actionLoading.value = false
  }
}

async function refreshActionContext() {
  if (actionLoading.value) return
  const requestEpoch = ++actionRequestEpoch
  actionLoading.value = true
  await store.load(currentQuery())
  if (requestEpoch === actionRequestEpoch) {
    actionError.value = store.error ?? ''
    actionLoading.value = false
  }
}

watch(() => store.selectedId, () => {
  actionRequestEpoch += 1
  actionOpen.value = false
  pendingAction.value = null
  actionDetail.value = ''
  dueAtLocal.value = ''
  actionError.value = ''
  actionLoading.value = false
})
onMounted(() => { if (enabled) void Promise.all([store.load({ page: 1, pageSize: pageSize.value }), store.loadOverview()]) })
</script>

<style scoped>
.risk-center {
  display: grid;
  gap: 12px;
  color: var(--text);
  font-family: var(--font-family-ui);
  font-size: var(--font-size-body);
}

.risk-breadcrumb { color: var(--text-muted); font-size: 13px; }
.risk-breadcrumb span { margin: 0 8px; color: #c2cbd8; }

.risk-summary-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.risk-summary-card,
.risk-panel { border: 1px solid var(--border); border-radius: var(--radius-card); background: var(--surface); box-shadow: var(--shadow-card); }
.risk-summary-card { padding: 14px 16px 12px; min-width: 0; }
.risk-summary-card[aria-busy="true"] .summary-sparkline { opacity: .2; }
.summary-main { display: flex; align-items: center; gap: 13px; }
.summary-icon { display: grid; place-items: center; width: 48px; height: 48px; border-radius: var(--radius-control); font-size: 25px; flex: 0 0 auto; }
.summary-main h2 { margin: 0 0 2px; color: var(--text-title); font-size: 15px; font-weight: var(--font-weight-bold); }
.summary-main strong { color: #0f1f45; font-size: 28px; line-height: 1; }
.summary-main strong small { font-size: var(--font-size-caption); font-weight: var(--font-weight-medium); }
.summary-change { display: flex; align-items: center; justify-content: space-between; gap: 10px; min-height: 30px; margin-top: 8px; font-size: 12px; }
.summary-period { color: var(--text-muted); }
.summary-change-metric { display: inline-flex; align-items: center; gap: 4px; min-width: 0; white-space: nowrap; }
.summary-change-metric > :not(span) { flex: 0 0 auto; }
.summary-rate { font-size: 12px; font-style: normal; font-weight: var(--font-weight-medium); }
.summary-sparkline { width: 76px; height: 30px; flex: 0 0 76px; overflow: visible; }
.summary-change.is-up { color: var(--danger-strong); }
.summary-change.is-down { color: var(--success-strong); }
.summary-change.is-flat { color: var(--text-muted); }
.summary-change.unavailable { grid-template-columns: 1fr; color: var(--warning-strong); }

.risk-overview-state { display: flex; align-items: center; gap: 9px; min-height: var(--touch-target); padding: 8px 12px; border: 1px solid var(--border); border-radius: var(--radius-control); font-size: var(--font-size-caption); }
.risk-overview-state span { flex: 1; }
.risk-overview-loading { color: var(--primary-dark); border-color: var(--primary-border); background: var(--primary-soft); }
.risk-overview-error { color: var(--warning-strong); border-color: #fdba74; background: var(--surface-warning); }
.risk-overview-empty { color: var(--text-muted); background: var(--surface-muted); }
.risk-overview-state button { display: inline-flex; align-items: center; gap: 5px; min-height: var(--touch-target); padding: 0 12px; border: 1px solid currentColor; border-radius: var(--radius-control); color: inherit; background: var(--surface); cursor: pointer; }
.overview-spinner { animation: overview-spin .8s linear infinite; }

.risk-analytics { display: grid; grid-template-columns: minmax(0, 1.55fr) minmax(280px, .95fr) minmax(250px, .82fr); gap: 12px; }
.risk-panel { min-width: 0; overflow: hidden; }
.trend-panel,
.distribution-panel,
.rule-panel { min-height: 238px; padding: 12px 16px; }
.panel-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.panel-heading h2 { margin: 0; color: var(--text-title); font-size: 16px; font-weight: var(--font-weight-bold); }
.panel-heading p { margin: 3px 0 0; color: var(--text-muted); font-size: var(--font-size-caption); }
.period-badge { padding: 6px 10px; border: 1px solid var(--border); border-radius: var(--radius-control); color: var(--text-muted); background: var(--surface-muted); font-size: var(--font-size-caption); }
.chart-legend { display: flex; gap: 18px; margin-top: 10px; color: var(--text-muted); font-size: var(--font-size-caption); }
.chart-legend span { display: inline-flex; align-items: center; gap: 6px; }
.legend-bar { width: 15px; height: 8px; border-radius: 2px; background: #9cc7f5; }
.legend-line { position: relative; width: 16px; height: 2px; background: #2563eb; }
.legend-line::after { position: absolute; top: -3px; left: 6px; width: 6px; height: 6px; border: 2px solid #2563eb; border-radius: 50%; background: #fff; content: ""; }
.risk-trend-chart-stage { position: relative; width: 100%; height: 174px; }
.risk-trend-chart { width: 100%; height: 100%; }
.risk-trend-sparse-state {
  position: absolute;
  top: 16px;
  left: 16px;
  max-width: min(310px, calc(100% - 112px));
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 9px 10px;
  border: 1px solid #cfe0f8;
  border-radius: 6px;
  color: #27466f;
  background: rgba(247, 250, 255, .96);
  box-shadow: 0 4px 12px rgba(37, 99, 235, .08);
  pointer-events: none;
}
.risk-trend-sparse-state > svg { flex: 0 0 auto; margin-top: 2px; color: #2563eb; font-size: 15px; }
.risk-trend-sparse-state span { min-width: 0; display: grid; gap: 2px; }
.risk-trend-sparse-state strong { color: #17345c; font-size: 13px; font-weight: 600; line-height: 18px; }
.risk-trend-sparse-state small { color: #526987; font-size: 12px; line-height: 17px; }
.risk-distribution-chart { width: 100%; height: 188px; }
.rule-list { margin: 12px 0 0; padding: 0; list-style: none; }
.rule-list li { display: grid; grid-template-columns: 24px minmax(0, 1fr) auto; align-items: center; gap: 9px; min-height: 52px; border-top: 1px solid var(--border); }
.rule-list li:first-child { border-top: 0; }
.rule-list li > div { min-width: 0; }
.rule-list strong,
.rule-list small { display: block; }
.rule-list strong { color: var(--text-title); font-size: 14px; }
.rule-list small { margin-top: 2px; overflow: hidden; color: var(--text-muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.rule-state-icon.handled,
.rule-status.handled { color: var(--success-strong); }
.rule-state-icon.needs-review,
.rule-status.needs-review { color: var(--warning-strong); }
.rule-state-icon.no-hit,
.rule-status.no-hit { color: var(--text-muted); }
.rule-status { font-size: 12px; font-weight: var(--font-weight-medium); white-space: nowrap; }

@keyframes overview-spin { to { transform: rotate(360deg); } }

.risk-workbench { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(430px, 1fr); gap: 12px; align-items: start; min-height: 0; }
.risk-list-panel { display: grid; grid-template-rows: auto auto minmax(0, 1fr) auto; padding: 14px 0 0; }
.list-heading { padding: 0 16px; }
.icon-button { display: grid; place-items: center; width: var(--touch-target); height: var(--touch-target); border: 1px solid var(--border); border-radius: var(--radius-control); color: var(--primary-dark); background: var(--surface); cursor: pointer; }
.risk-filters { display: grid; grid-template-columns: 150px 140px minmax(190px, 1fr) auto; gap: 10px; align-items: end; padding: 12px 16px 10px; }
.risk-filters label { display: grid; gap: 4px; color: var(--text-muted); font-size: var(--font-size-caption); }
.risk-filters select,
.risk-filters input,
.risk-pagination select,
.risk-due input { width: 100%; min-height: var(--touch-target); padding: 8px 10px; border: 1px solid var(--border); border-radius: var(--radius-control); color: var(--text); background: var(--surface); font: inherit; }
.search-input { display: flex; align-items: center; min-height: var(--touch-target); padding: 0 10px; border: 1px solid var(--border); border-radius: var(--radius-control); color: var(--text-muted); background: var(--surface); }
.search-input input { min-height: var(--touch-target); padding: 0 0 0 7px; border: 0; outline: 0; }
.filter-button { min-height: var(--touch-target); padding: 0 18px; border: 1px solid var(--primary); border-radius: var(--radius-control); color: #fff; background: var(--primary); cursor: pointer; font-weight: var(--font-weight-semibold); }
.risk-loading,
.empty-cell { padding: 32px 16px; color: var(--text-muted); text-align: center; }
.risk-error { display: flex; align-items: center; justify-content: center; gap: 12px; min-height: 96px; padding: 20px 16px; border-top: 1px solid var(--border); color: var(--danger-strong); background: var(--surface-danger); }
.risk-error button { display: inline-flex; align-items: center; gap: 6px; min-height: var(--touch-target); padding: 0 12px; border: 1px solid #fecaca; border-radius: var(--radius-control); color: var(--danger-strong); background: var(--surface); cursor: pointer; }
.risk-table-wrap { min-height: 0; overflow: auto; border-top: 1px solid var(--border); overscroll-behavior: contain; }
.risk-table-wrap:focus-visible { outline: 2px solid var(--primary); outline-offset: -2px; }
.risk-table { width: 100%; min-width: 720px; border-collapse: collapse; table-layout: fixed; font-size: 12px; }
.risk-table thead { position: sticky; top: 0; z-index: 1; }
.risk-table th { padding: 10px 8px; color: var(--text-strong); background: var(--surface-muted); font-weight: var(--font-weight-semibold); text-align: left; }
.risk-table td { padding: 10px 8px; border-top: 1px solid var(--border); vertical-align: middle; }
.risk-table tbody tr { cursor: pointer; transition: background .15s ease; }
.risk-table tbody tr:hover,
.risk-table tbody tr.selected { background: var(--surface-selected); }
.risk-table tbody tr:focus-visible { outline: 2px solid var(--primary); outline-offset: -2px; }
.risk-table th:nth-child(1) { width: 62px; }
.risk-table th:nth-child(2) { width: 95px; }
.risk-table th:nth-child(3) { width: 160px; }
.risk-table th:nth-child(4) { width: 180px; }
.risk-table th:nth-child(5) { width: 95px; }
.risk-table th:nth-child(6) { width: 92px; }
.risk-table th:nth-child(7) { width: 72px; }
.risk-table th:nth-child(8) { width: 66px; }
.risk-table td strong,
.risk-table td small { display: block; }
.risk-table td small { margin-top: 3px; overflow: hidden; color: var(--text-muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.evidence-cell,
.explanation-cell { color: var(--text-strong); line-height: 1.5; overflow-wrap: anywhere; }
.table-cell-clamp {
  display: -webkit-box;
  overflow: hidden;
  line-height: 1.35;
  overflow-wrap: anywhere;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.severity-tag,
.state-tag { display: inline-flex; align-items: center; justify-content: center; min-height: 26px; padding: 3px 8px; border: 1px solid transparent; border-radius: 5px; font-size: 12px; font-weight: var(--font-weight-medium); white-space: nowrap; }
.severity-high { color: #dc2626; border-color: #fecaca; background: #fff1f2; }
.severity-medium { color: #d97706; border-color: #fde68a; background: #fffbeb; }
.severity-low { color: #059669; border-color: #a7f3d0; background: #ecfdf5; }
.state-open { color: #2563eb; border-color: #bfdbfe; background: #eff6ff; }
.state-acknowledged { color: #d97706; border-color: #fde68a; background: #fffbeb; }
.state-resolved { color: #059669; border-color: #a7f3d0; background: #ecfdf5; }
.state-dismissed { color: #64748b; border-color: #dbe3ed; background: #f8fafc; }
.view-button { display: inline-flex; align-items: center; justify-content: center; gap: 5px; min-height: var(--touch-target); padding: 0 8px; border: 0; border-radius: var(--radius-control); color: var(--primary); background: transparent; cursor: pointer; }
.view-button:hover { background: var(--primary-soft); }
.risk-mobile-list { display: none; }
.risk-pagination { display: flex; align-items: center; justify-content: space-between; gap: 12px; min-height: 62px; padding: 9px 16px; border-top: 1px solid var(--border); color: var(--text-muted); font-size: 12px; }
.page-controls { display: flex; align-items: center; gap: 5px; }
.page-controls button { display: grid; place-items: center; min-width: var(--touch-target); height: var(--touch-target); border: 1px solid var(--border); border-radius: var(--radius-control); color: var(--text-strong); background: var(--surface); }
.page-controls button.active { border-color: var(--primary); color: #fff; background: var(--primary); }
.page-controls button:disabled { color: #94a3b8; background: var(--surface-muted); }

.risk-detail-panel { padding: 10px 12px 8px; }
.detail-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.detail-heading > div { display: flex; min-width: 0; align-items: baseline; gap: 8px; }
.detail-heading h2 { margin: 0; scroll-margin-top: calc(var(--header-height) + 12px); color: var(--text-title); font-size: 16px; }
.detail-heading h2:focus { outline: 2px solid var(--primary); outline-offset: 3px; }
.detail-heading p { min-width: 0; margin: 0; overflow: hidden; color: var(--text-muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.risk-detail-scroll { min-width: 0; }
.detail-metrics { display: grid; grid-template-columns: minmax(0, 1.4fr) minmax(0, 1.2fr) minmax(0, .7fr) minmax(0, .7fr); margin: 7px 0 0; border: 1px solid var(--border); border-radius: var(--radius-control); }
.detail-metrics div { min-width: 0; padding: 6px 7px; border-left: 1px solid var(--border); text-align: center; }
.detail-metrics div:first-child { border-left: 0; }
.detail-metrics dt { color: var(--text-muted); font-size: 12px; }
.detail-metrics dd {
  min-width: 0;
  margin: 2px 0 0;
  color: #172554;
  font-size: 13px;
  font-weight: var(--font-weight-medium);
  font-variant-numeric: tabular-nums;
  overflow-wrap: anywhere;
  word-break: break-word;
  white-space: normal;
}
.risk-policy-note { display: flex; align-items: center; gap: 7px; min-height: 34px; margin-top: 6px; padding: 5px 8px; border: 1px solid #f4d28c; border-radius: var(--radius-control); color: var(--warning-strong); background: var(--surface-warning); font-size: 12px; }
.risk-detail-core { display: grid; grid-template-columns: minmax(0, 1.08fr) minmax(0, .92fr); gap: 8px; margin-top: 6px; }
.risk-detail-core__evidence,
.risk-detail-core__human { min-width: 0; display: grid; align-content: start; gap: 6px; }
.evidence-section { min-width: 0; padding: 8px; border: 1px solid var(--border); border-radius: var(--radius-control); background: var(--surface); }
.evidence-section h3 { margin: 0 0 4px; color: #172554; font-size: 13px; }
.evidence-section p { margin: 0; color: var(--text); font-size: 13px; line-height: 1.38; overflow-wrap: anywhere; }
.evidence-section small { display: block; margin-top: 3px; color: var(--text-muted); font-size: 12px; line-height: 1.3; }
.evidence-summary-section { position: relative; }
.evidence-summary-section > h3 { display: flex; align-items: flex-start; min-height: var(--touch-target); padding: 2px 132px 0 0; }
.fact-disclosure { margin: 0; }
.fact-disclosure summary { position: absolute; top: 6px; right: 6px; min-height: var(--touch-target); display: flex; align-items: center; padding: 0 10px; border: 1px solid var(--primary-border); border-radius: var(--radius-control); color: var(--primary); background: var(--primary-soft); cursor: pointer; font-size: 12px; white-space: nowrap; }
.fact-disclosure summary:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; }
.fact-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; margin: 5px 0 0; }
.fact-grid div { display: flex; justify-content: space-between; gap: 8px; padding: 8px; border-radius: var(--radius-control); background: var(--surface-muted); }
.fact-grid dt { color: var(--text-muted); font-size: 12px; overflow-wrap: anywhere; }
.fact-grid dd { margin: 0; color: #172554; font-size: 12px; font-weight: var(--font-weight-semibold); overflow-wrap: anywhere; }
.snapshot-strip { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 7px; align-items: baseline; margin-top: 5px; padding: 5px 7px; border-radius: var(--radius-control); background: var(--primary-soft); }
.snapshot-strip strong { color: var(--primary-dark); font-size: 12px; white-space: nowrap; }
.snapshot-strip span { min-width: 0; overflow: hidden; color: var(--text-strong); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.ai-explanation-block { margin-top: 6px; padding-top: 6px; border-top: 1px solid var(--border); }
.ai-explanation-text { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.ai-explanation-meta { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ai-degraded-inline { display: flex; align-items: center; gap: 5px; min-height: 26px; margin-top: 4px; padding: 3px 6px; border: 1px solid #f4d28c; border-radius: var(--radius-control); color: var(--warning-strong); background: var(--surface-warning); font-size: 12px; }
.human-timeline { margin: 0; padding: 0; list-style: none; }
.human-timeline li { position: relative; display: grid; grid-template-columns: 14px minmax(0, 1fr); gap: 7px; padding-bottom: 5px; }
.human-timeline li:not(:last-child)::after { position: absolute; top: 12px; bottom: 0; left: 5px; width: 1px; background: #dce5f1; content: ""; }
.timeline-dot { z-index: 1; width: 11px; height: 11px; margin-top: 4px; border: 2px solid var(--primary); border-radius: 50%; background: var(--surface); }
.human-timeline strong { color: var(--text-title); font-size: 13px; }
.human-timeline p { overflow: hidden; margin-top: 1px; font-size: 12px; line-height: 1.3; text-overflow: ellipsis; white-space: nowrap; }
.human-timeline small { overflow: hidden; margin-top: 1px; text-overflow: ellipsis; white-space: nowrap; }
.detail-safety { padding: 7px 8px; border: 1px solid var(--primary-border); border-radius: var(--radius-control); color: var(--primary); background: var(--primary-soft); }
.detail-safety > div { display: flex; align-items: flex-start; gap: 8px; }
.detail-safety p { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 2px 7px; margin: 0; font-size: 12px; }
.detail-safety span { color: var(--text-strong); line-height: 1.35; }
.risk-actions { display: grid; gap: 7px; margin-top: 6px; padding-top: 6px; border-top: 1px solid #edf2f8; background: #fff; }
.risk-action-error { display: flex; align-items: center; gap: 8px; padding: 8px 10px; border: 1px solid #fecaca; border-radius: var(--radius-control); color: var(--danger-strong); background: var(--surface-danger); font-size: 12px; }
.risk-action-error span { min-width: 0; flex: 1; overflow-wrap: anywhere; }
.risk-action-error button { display: inline-flex; align-items: center; justify-content: center; gap: 5px; min-height: var(--touch-target); padding: 0 10px; border: 1px solid var(--danger); border-radius: var(--radius-control); color: var(--danger-strong); background: var(--surface); cursor: pointer; white-space: nowrap; }
.risk-action-error button:disabled { opacity: .58; cursor: not-allowed; }
.risk-action-form { display: grid; gap: 14px; }
.risk-action-context { display: flex; align-items: flex-start; gap: 9px; padding: 10px 12px; border: 1px solid var(--primary-border); border-radius: var(--radius-control); color: var(--primary); background: var(--primary-soft); }
.risk-action-context p { display: grid; gap: 3px; margin: 0; }
.risk-action-context span { color: var(--text-strong); font-size: 13px; line-height: 1.5; overflow-wrap: anywhere; }
.risk-action-field { display: grid; gap: 6px; color: var(--text-strong); font-size: 13px; font-weight: var(--font-weight-medium); }
.risk-action-field > span strong { color: var(--danger-strong); }
.risk-due { display: grid; gap: 5px; color: var(--text-strong); font-size: 13px; font-weight: var(--font-weight-medium); }
.action-buttons { display: grid; grid-template-columns: repeat(3, 1fr); gap: 8px; }
.action-buttons button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: var(--touch-target); border: 1px solid var(--primary); border-radius: var(--radius-control); color: #fff; background: var(--primary); cursor: pointer; font-weight: var(--font-weight-semibold); }
.action-buttons button.secondary { color: var(--primary); background: var(--surface); }
.action-buttons button:disabled { border-color: var(--border); color: #94a3b8; background: var(--surface-muted); cursor: not-allowed; }
.risk-action-complete { margin: 0; color: var(--text-muted); font-size: 12px; line-height: var(--line-height-body); text-align: center; }
.risk-action-modal-footer { display: flex; justify-content: flex-end; gap: 8px; }
.risk-action-modal-footer button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-width: 108px; min-height: var(--touch-target); padding: 0 16px; border: 1px solid var(--primary); border-radius: var(--radius-control); color: #fff; background: var(--primary); cursor: pointer; font-weight: var(--font-weight-semibold); }
.risk-action-modal-footer button.secondary { color: var(--text-strong); border-color: var(--border); background: var(--surface); }
.risk-action-modal-footer button:disabled { border-color: var(--border); color: #94a3b8; background: var(--surface-muted); cursor: not-allowed; }
.risk-readonly-note { display: flex; align-items: flex-start; gap: 9px; margin-top: 12px; padding: 10px 12px; border: 1px solid var(--primary-border); border-radius: var(--radius-control); color: var(--primary); background: var(--primary-soft); }
.risk-readonly-note p { display: grid; gap: 2px; margin: 0; font-size: 13px; }
.risk-readonly-note span { color: var(--text-strong); line-height: 1.5; }
.risk-detail-empty { display: flex; min-height: 280px; align-items: center; justify-content: center; gap: 12px; padding: 24px; color: var(--primary); text-align: left; }
.risk-detail-empty > svg { font-size: 30px; }
.risk-detail-empty strong { color: var(--text-title); font-size: 16px; }
.risk-detail-empty p { max-width: 34ch; margin: 5px 0 0; color: var(--text-muted); line-height: 1.6; }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }

@media (min-width: 1281px) {
  .risk-mobile-list { display: none; }
  .risk-table-wrap { overflow-x: hidden; }
  .risk-table { min-width: 0; font-size: 12px; }
  .risk-table th { padding: 7px 6px; }
  .risk-table td { height: var(--touch-target); padding: 0 6px; }
  .risk-table .table-cell-clamp { display: block; overflow: hidden; line-height: 1.4; overflow-wrap: normal; text-overflow: ellipsis; white-space: nowrap; -webkit-box-orient: initial; -webkit-line-clamp: initial; }
  .risk-table th:nth-child(1) { width: 8%; }
  .risk-table th:nth-child(2) { width: 13%; }
  .risk-table th:nth-child(3) { width: 18%; }
  .risk-table th:nth-child(4) { width: 22%; }
  .risk-table th:nth-child(5) { width: 11%; }
  .risk-table th:nth-child(6) { width: 9%; }
  .risk-table th:nth-child(7) { width: 10%; }
  .risk-table th:nth-child(8) { width: 9%; }
}

@media (min-width: 1281px) and (min-height: 900px) {
  .risk-workbench { height: clamp(440px, calc(100dvh - 560px), 520px); align-items: stretch; }
  .risk-list-panel,
  .risk-detail-panel { height: 100%; min-height: 0; }
  .risk-list-panel { padding-top: 8px; }
  .list-heading { padding: 0 12px; }
  .list-heading p,
  .risk-filters label > span:first-child:not(.search-input) { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
  .risk-filters { align-items: center; gap: 8px; padding: 6px 12px; }
  .risk-pagination { min-height: 46px; padding: 7px 14px; }
  .risk-detail-panel { display: grid; grid-template-rows: auto minmax(0, 1fr) auto; padding: 6px 12px 4px; }
  .risk-detail-scroll { min-height: 0; overflow: visible; }
  .detail-metrics { margin-top: 4px; }
  .risk-policy-note { margin-top: 4px; }
  .risk-detail-core { margin-top: 4px; }
  .risk-actions { margin-top: 4px; padding-top: 4px; }
  .detail-metrics dd { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .ai-explanation-text { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .ai-degraded-inline { min-height: 20px; margin-top: 2px; padding: 1px 4px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
}

@media (max-width: 1280px) {
  .risk-summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .risk-analytics { grid-template-columns: 1.5fr 1fr; }
  .rule-panel { grid-column: 1 / -1; min-height: auto; }
  .rule-list { display: grid; grid-template-columns: repeat(2, 1fr); gap: 0 16px; }
  .risk-workbench { grid-template-columns: 1fr; }
  .risk-detail-scroll { overflow: visible; }
  .risk-detail-core { grid-template-columns: minmax(0, 1fr); }
}

@media (max-width: 760px) {
  .risk-center { min-width: 0; gap: 10px; }
  .risk-summary-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
  .risk-summary-card { padding: 11px; }
  .summary-icon { width: 40px; height: 40px; font-size: 21px; }
  .summary-main { gap: 9px; }
  .summary-main h2 { font-size: 14px; }
  .summary-main strong { font-size: 23px; }
  .summary-change { align-items: flex-start; gap: 4px; }
  .summary-change-metric { display: grid; grid-template-columns: auto 13px auto; gap: 3px; }
  .summary-rate { grid-column: 1 / -1; }
  .summary-sparkline { width: 48px; flex-basis: 48px; }
  .risk-analytics,
  .risk-workbench { grid-template-columns: minmax(0, 1fr); }
  .rule-panel { grid-column: auto; }
  .rule-list { display: block; }
  .risk-table-wrap { display: none; }
  .risk-mobile-list {
    display: grid;
    gap: 10px;
    margin: 0;
    padding: 12px;
    border-top: 1px solid var(--border);
    list-style: none;
  }
  .risk-mobile-list > li {
    display: grid;
    gap: 9px;
    min-width: 0;
    padding: 12px;
    border: 1px solid var(--border);
    border-radius: var(--radius-control);
    background: var(--surface);
  }
  .risk-mobile-list > li.selected { border-color: var(--primary-border); background: var(--surface-selected); }
  .risk-mobile-heading { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
  .risk-mobile-list strong { color: var(--text-title); font-size: 15px; }
  .risk-mobile-list p { margin: 0; color: var(--text); font-size: 14px; line-height: 1.55; overflow-wrap: anywhere; }
  .risk-mobile-list dl { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; margin: 0; }
  .risk-mobile-list dl div { min-width: 0; padding: 8px; border-radius: var(--radius-control); background: var(--surface-muted); }
  .risk-mobile-list dt { color: var(--text-muted); font-size: 12px; }
  .risk-mobile-list dd { margin: 3px 0 0; color: var(--text-strong); font-size: 13px; overflow-wrap: anywhere; }
  .risk-mobile-open {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: 7px;
    min-height: var(--touch-target);
    padding: 0 12px;
    border: 1px solid var(--primary-border);
    border-radius: var(--radius-control);
    color: var(--primary);
    background: var(--primary-soft);
    font-weight: var(--font-weight-semibold);
  }
  .risk-mobile-open > :last-child { margin-left: auto; }
  .risk-mobile-empty { color: var(--text-muted); text-align: center; }
  .risk-filters { grid-template-columns: minmax(0, 1fr); min-width: 0; }
  .risk-filters > * { min-width: 0; overflow-wrap: anywhere; }
  .risk-filters select,
  .risk-filters input,
  .risk-pagination select { min-width: 0; min-height: 44px; }
  .search-input { min-width: 0; min-height: 44px; }
  .keyword-field { grid-column: 1 / -1; }
  .filter-button { min-height: 44px; grid-column: 1 / -1; }
  .icon-button { width: 44px; height: 44px; }
  .risk-pagination { min-width: 0; flex-wrap: wrap; }
  .risk-pagination > * { min-width: 0; }
  .page-controls { flex-wrap: wrap; }
  .page-controls button { min-width: 44px; height: 44px; }
  .panel-heading > div,
  .detail-heading > div { min-width: 0; overflow-wrap: anywhere; }
  .detail-heading > div { display: block; }
  .detail-heading p { margin-top: 4px; overflow: visible; white-space: normal; }
  .detail-metrics { grid-template-columns: repeat(2, 1fr); }
  .detail-metrics div:nth-child(3) { border-top: 1px solid #e7edf6; border-left: 0; }
  .detail-metrics div:nth-child(4) { border-top: 1px solid #e7edf6; }
  .risk-due input,
  .view-button { min-height: 44px; }
  .action-buttons { grid-template-columns: 1fr; }
  .action-buttons button { min-height: 44px; white-space: normal; overflow-wrap: anywhere; }
}

@media (prefers-reduced-motion: reduce) {
  .overview-spinner { animation: none; }
  .risk-table tbody tr { transition: none; }
}
</style>
