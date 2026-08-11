<template>
  <div class="dashboard-view" :aria-busy="store.loading">
    <section v-if="canUseAiDashboard" class="dashboard-command" data-dashboard-section="command" aria-label="AI 智能驾驶舱">
      <AiCommandBar :loading="ai.dashboardLoading" @submit="runAiQuery" />
    </section>

    <section
      class="metric-grid"
      :class="{ 'metric-grid--four': !canReviewApprovals }"
      data-dashboard-section="metrics"
      aria-label="核心运营指标"
      :aria-busy="store.loading"
    >
      <article
        v-for="card in dashboardCards"
        :key="card.id"
        class="metric-card"
        :class="`metric-card--${card.tone}`"
        :data-dashboard-metric="card.id"
        :data-state="card.value === null ? 'unavailable' : 'available'"
        :aria-label="card.value === null ? `${card.title} 数据不可用` : undefined"
      >
        <span class="metric-card__icon" aria-hidden="true">
          <component :is="card.icon" />
        </span>
        <div class="metric-card__copy">
          <span>{{ card.title }}</span>
          <strong>{{ card.value ?? '--' }} <small>{{ card.unit }}</small></strong>
        </div>
        <component :is="card.trendIcon" class="metric-card__trend" aria-hidden="true" />
      </article>
      <article
        v-if="canReviewApprovals"
        class="metric-card metric-card--approval metric-card--desktop"
        data-dashboard-metric="approvals"
        :data-state="pendingApprovalMetricCount === null ? 'unavailable' : 'available'"
        :aria-label="pendingApprovalMetricCount === null ? '待审批数据不可用' : undefined"
      >
        <span class="metric-card__icon" aria-hidden="true"><AuditOutlined /></span>
        <div class="metric-card__copy">
          <span>待审批</span>
          <strong>{{ pendingApprovalMetricCount ?? '--' }} <small>项</small></strong>
        </div>
        <RouterLink to="/ai/approvals" aria-label="进入审批中心"><ArrowRightOutlined aria-hidden="true" /></RouterLink>
      </article>
    </section>

    <AiSafetyState
      v-if="showAiPermissionState"
      class="dashboard-ai-state"
      state="NO_PERMISSION"
      message="无权限使用 AI 驾驶舱查询，原业务指标仍可继续查看。"
    />

    <article
      v-if="canUseAiDashboard && ai.dashboardInsight"
      class="cockpit-panel ai-brief"
      data-dashboard-section="brief"
      aria-label="今日 AI 运营简报"
      :aria-busy="ai.dashboardLoading"
      :data-result-freshness="hasStaleDashboardInsight ? 'stale' : 'current'"
    >
      <header class="panel-heading ai-brief__heading">
        <div class="panel-heading__title">
          <RobotOutlined class="brief-ai-icon" aria-hidden="true" />
          <FileTextOutlined class="brief-document-icon" aria-hidden="true" />
          <h2>今日 AI 运营简报</h2>
          <small
            class="ai-brief__version"
            :title="briefMetricVersionTitle"
          >{{ briefMetricVersionLabel }}</small>
        </div>
        <div class="ai-brief__facts" aria-label="简报依据概览">
          <span
            data-brief-meta="confidence"
            :class="`ai-brief__fact--${briefConfidenceTone}`"
            :aria-label="briefConfidenceLabel"
          >
            <SafetyCertificateOutlined aria-hidden="true" />
            <span class="ai-brief__fact-copy">
              <small class="ai-brief__fact-label">置信度</small>
              <strong class="ai-brief__fact-value">{{ briefConfidenceValue }}</strong>
            </span>
          </span>
          <details data-brief-meta="citations" class="ai-brief__citations">
            <summary :aria-label="briefCitationLabel">
              <LinkOutlined aria-hidden="true" />
              <span class="ai-brief__fact-copy">
                <small class="ai-brief__fact-label">引用来源</small>
                <strong class="ai-brief__fact-value">{{ briefCitationValue }}</strong>
              </span>
            </summary>
            <ul v-if="ai.dashboardInsight.evidence.citations.length">
              <li v-for="citation in ai.dashboardInsight.evidence.citations" :key="citation.id">
                <span v-if="citation.access === 'available'">
                  {{ citation.label }} · {{ citation.locator }} · {{ citation.version }}
                </span>
                <span v-else>无权限查看此来源</span>
              </li>
            </ul>
            <p v-else>暂无可用引用</p>
          </details>
          <span data-brief-meta="as-of" :aria-label="`数据截至（北京时间） ${briefAsOf}`">
            <ClockCircleOutlined aria-hidden="true" />
            <span class="ai-brief__fact-copy">
              <small class="ai-brief__fact-label">数据截至</small>
              <strong class="ai-brief__fact-value">
                <span class="ai-brief__as-of-full" aria-hidden="true">{{ briefAsOf }}</span>
                <span class="ai-brief__as-of-mobile" aria-hidden="true">{{ briefAsOfCompact }}</span>
              </strong>
            </span>
          </span>
        </div>
        <div class="ai-brief__meta">
          <RouterLink v-if="canViewRisks" to="/ai/risks">查看分析 <ArrowRightOutlined aria-hidden="true" /></RouterLink>
        </div>
      </header>
      <p class="ai-brief__summary">{{ briefDisplaySummary }}</p>
      <AiSafetyState
        v-if="briefStatus"
        class="ai-brief__warning"
        :state="briefStatus.state"
        :message="briefStatus.message"
        :action-label="hasStaleDashboardInsight ? '重新查询' : undefined"
        :loading="ai.dashboardLoading"
        @action="retryDashboard"
      />
    </article>

    <article
      v-else-if="canUseAiDashboard && ai.dashboardLoading"
      class="cockpit-panel ai-brief ai-brief--loading"
      data-dashboard-section="brief"
      aria-label="今日 AI 运营简报"
      aria-busy="true"
    >
      <header class="panel-heading ai-brief__heading">
        <div class="panel-heading__title">
          <LoadingOutlined spin aria-hidden="true" />
          <h2>今日 AI 运营简报</h2>
        </div>
      </header>
      <p class="ai-brief__loading-copy">正在生成运营简报，请稍候…</p>
    </article>

    <AiSafetyState
      v-if="ai.dashboardError && !hasStaleDashboardInsight"
      class="dashboard-ai-state"
      state="FAILED"
      :message="ai.dashboardError"
      action-label="重新查询"
      :loading="ai.dashboardLoading"
      @action="retryDashboard"
    />
    <AiSafetyState
      v-if="dashboardLoadError"
      class="dashboard-business-state"
      state="FAILED"
      :message="dashboardLoadError"
      action-label="重新加载"
      :loading="store.loading"
      @action="retryBusinessDashboard"
    />

    <div class="dashboard-analysis" data-dashboard-section="analysis" :class="{ 'dashboard-analysis--trend-only': !canViewRisks }">
      <section
        class="cockpit-panel trend-panel"
        :class="{ 'trend-panel--sparse': trendSparseState }"
        data-dashboard-section="trend"
        aria-label="入住办理趋势"
      >
        <header class="panel-heading">
          <div class="panel-heading__title">
            <BarChartOutlined aria-hidden="true" />
            <h2>入住办理趋势</h2>
          </div>
          <label class="trend-window">
            <span class="sr-only">趋势时间范围</span>
            <select v-model.number="trendDays">
              <option :value="7">近 7 天</option>
              <option :value="30">近 30 天</option>
            </select>
          </label>
        </header>
        <div class="trend-meta">
          <span><i class="legend-bar" />入住办理（人次）</span>
          <span><i class="legend-line" />累计入住（人次）</span>
          <strong>当前空床 {{ currentVacantBeds ?? '--' }} 个</strong>
        </div>
        <div v-if="!dashboardLoadError && trendRows.length" class="dashboard-trend-stage">
          <v-chart
            class="dashboard-trend-chart"
            role="img"
            :aria-label="trendAriaDescription"
            :option="trendOption"
            autoresize
          />
          <div
            v-if="trendSparseState"
            class="trend-sparse-state"
            :data-trend-sparse-state="trendSparseState.kind"
            role="status"
          >
            <InfoCircleOutlined aria-hidden="true" />
            <span>
              <strong>{{ trendSparseState.title }}</strong>
              <small>{{ trendSparseState.detail }}</small>
            </span>
          </div>
        </div>
        <table v-if="!dashboardLoadError && trendRows.length" class="sr-only" data-trend-accessible-table>
          <caption>入住办理趋势详细数据</caption>
          <thead>
            <tr>
              <th scope="col">日期</th>
              <th scope="col">入住办理（人次）</th>
              <th scope="col">累计入住（人次）</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in trendRows" :key="row.date">
              <th scope="row">{{ row.date }}</th>
              <td>{{ row.checkIns }}</td>
              <td>{{ row.cumulative }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="panel-empty">{{ trendPanelEmptyMessage }}</p>
      </section>

      <section
        v-if="canViewRisks"
        class="cockpit-panel risk-panel"
        data-dashboard-section="risks"
        aria-label="智能风险概览"
        :data-result-freshness="hasStaleDashboardInsight ? 'stale' : 'current'"
      >
        <header class="panel-heading">
          <div class="panel-heading__title">
            <SafetyCertificateOutlined aria-hidden="true" />
            <h2>智能风险概览</h2>
          </div>
          <RouterLink to="/ai/risks">查看详情 <ArrowRightOutlined aria-hidden="true" /></RouterLink>
        </header>
        <div v-if="riskItems.length" class="risk-list">
          <RouterLink
            v-for="risk in riskItems"
            :key="risk.type"
            class="risk-row"
            :class="[
              `risk-row--${risk.tone}`,
              { 'risk-row--empty': risk.state !== 'active' },
            ]"
            to="/ai/risks"
            :aria-label="riskAccessibleLabel(risk)"
            :data-risk-category="risk.type"
            :data-risk-state="risk.state"
          >
            <span class="risk-row__icon" :class="`risk-row__icon--${risk.tone}`" aria-hidden="true">
              <component :is="riskIcon(risk.type)" />
            </span>
            <span class="risk-row__label">
              <strong>{{ risk.type }}</strong>
            </span>
            <span class="risk-row__status" :class="`risk-row__status--${risk.state}`">
              <strong>{{ riskCountLabel(risk) }}</strong>
              <small v-if="risk.state !== 'unavailable'">{{ riskSeverityLabel(risk) }}</small>
            </span>
            <span class="risk-row__description">{{ riskDescription(risk.type) }}</span>
            <ArrowRightOutlined class="risk-row__arrow" aria-hidden="true" />
          </RouterLink>
        </div>
        <p v-else class="panel-empty" role="status">{{ riskPanelEmptyMessage }}</p>
        <AiSafetyState
          v-if="hasStaleDashboardInsight"
          class="risk-panel__stale"
          state="STALE"
          message="风险分析刷新失败，当前显示上一版只读结果；请重新查询后再据此处置。"
        />
        <AiSafetyState
          v-if="unmappedRiskTotal"
          data-risk-unmapped
          state="INFO"
          :message="`另有 ${unmappedRiskTotal} 项运营风险未归入当前四类，请进入风险中心查看依据。`"
        />
      </section>
    </div>

    <section class="cockpit-panel pending-panel" data-dashboard-section="pending" aria-label="待处理事项">
      <header class="panel-heading pending-panel__heading">
        <div class="panel-heading__title">
          <CheckSquareOutlined aria-hidden="true" />
          <h2>
            待处理事项
            <span v-if="pendingTableRows.length" class="pending-panel__count pending-panel__count--desktop">（{{ pendingTableRows.length }} 条）</span>
            <span v-if="pendingItems.length" class="pending-panel__count pending-panel__count--mobile">（{{ pendingItems.length }} 类）</span>
          </h2>
        </div>
        <button
          v-if="hasHiddenMobilePendingItems"
          type="button"
          class="pending-panel__mobile-toggle"
          data-mobile-pending-toggle
          :aria-expanded="mobilePendingExpanded"
          @click="mobilePendingExpanded = !mobilePendingExpanded"
        >{{ mobilePendingExpanded ? '收起待办' : `查看全部 ${pendingItems.length} 类` }}</button>
        <ol class="approval-flow approval-flow--desktop" aria-label="AI 建议审批流程">
          <li><b>1</b> AI 建议 <ArrowRightOutlined class="approval-flow__arrow" aria-hidden="true" /></li>
          <li><b>2</b> 变更预览 <ArrowRightOutlined class="approval-flow__arrow" aria-hidden="true" /></li>
          <li><b>3</b> 人工审批</li>
        </ol>
      </header>

      <div v-if="pendingItems.length" class="pending-table-wrap">
        <table v-if="pendingTableRows.length" class="pending-table">
          <thead>
            <tr>
              <th>事项类型</th>
              <th>AI 建议（摘要）</th>
              <th>置信度</th>
              <th>变更预览（摘要）</th>
              <th>引用来源</th>
              <th>建议时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody
            v-for="group in pendingTableGroups"
            :key="group.id"
            :data-pending-group="group.id"
          >
            <tr
              v-for="item in group.rows"
              :key="item.id"
              data-pending-row
              :data-pending-kind="item.kind"
              :data-pending-approval-id="item.approvalId"
            >
              <td>
                <span class="pending-table__title-row">
                  <span class="pending-table__kind">{{ item.kindLabel }}</span>
                  <strong>{{ item.label }}</strong>
                </span>
                <small class="pending-table__count">{{ item.countLabel }}</small>
              </td>
              <td data-pending-column="recommendation" :title="item.recommendationFull">{{ item.recommendation }}</td>
              <td data-pending-column="confidence" :data-confidence-tone="item.confidenceTone">{{ item.confidence }}</td>
              <td data-pending-column="preview" :title="item.previewFull">{{ item.preview }}</td>
              <td data-pending-column="citations">{{ item.citations }}</td>
              <td data-pending-column="suggested-at">{{ item.suggestedAt }}</td>
              <td>
                <div class="pending-table__actions">
                  <RouterLink
                    data-pending-view
                    :to="item.viewTo"
                    :aria-label="item.viewAriaLabel"
                    @click="selectPendingProposal(item.approvalId)"
                  >{{ item.kind === 'ai' ? '查看建议' : item.action }}</RouterLink>
                  <RouterLink
                    v-if="item.canEnterApproval"
                    data-pending-approval
                    :to="item.approvalTo"
                    :aria-label="item.approvalAriaLabel"
                    @click="selectPendingProposal(item.approvalId)"
                  >进入审批</RouterLink>
                </div>
              </td>
            </tr>
          </tbody>
        </table>
        <div
          v-if="pendingTableRows.length"
          class="pending-reflow-list"
          aria-label="待处理事项详细列表"
        >
          <article
            v-for="item in pendingTableRows"
            :key="item.id"
            :data-pending-reflow-item="item.id"
          >
            <header class="pending-reflow-list__heading">
              <span class="pending-reflow-list__icon" aria-hidden="true"><component :is="item.icon" /></span>
              <span class="pending-reflow-list__title">
                <small class="pending-reflow-list__kind">{{ item.kindLabel }}</small>
                <strong>{{ item.label }}</strong>
                <small>{{ item.countLabel }}</small>
              </span>
            </header>
            <dl>
              <div data-pending-detail="recommendation">
                <dt>AI 建议</dt>
                <dd>{{ item.recommendationFull }}</dd>
              </div>
              <div data-pending-detail="confidence">
                <dt>置信度</dt>
                <dd>{{ item.confidence }}</dd>
              </div>
              <div data-pending-detail="preview">
                <dt>变更预览</dt>
                <dd>{{ item.previewFull }}</dd>
              </div>
              <div data-pending-detail="citations">
                <dt>引用来源</dt>
                <dd>{{ item.citations }}</dd>
              </div>
              <div data-pending-detail="suggested-at">
                <dt>建议时间</dt>
                <dd>{{ item.suggestedAt }}</dd>
              </div>
            </dl>
            <footer class="pending-reflow-list__actions">
              <RouterLink
                data-pending-view
                :to="item.viewTo"
                :aria-label="item.viewAriaLabel"
                @click="selectPendingProposal(item.approvalId)"
              >{{ item.kind === 'ai' ? '查看建议' : item.action }}</RouterLink>
              <RouterLink
                v-if="item.canEnterApproval"
                data-pending-approval
                :to="item.approvalTo"
                :aria-label="item.approvalAriaLabel"
                @click="selectPendingProposal(item.approvalId)"
              >进入审批</RouterLink>
            </footer>
          </article>
        </div>
        <div class="pending-mobile-list">
          <article
            v-for="item in mobilePendingItems"
            :key="item.id"
            :data-pending-kind="item.kind"
          >
            <span class="pending-mobile-list__icon" aria-hidden="true"><component :is="item.icon" /></span>
            <div>
              <strong>{{ item.mobileLabel }}</strong>
              <small>{{ pendingItemDescription(item) }}</small>
            </div>
            <RouterLink :to="item.to" :data-mobile-cta="item.id">{{ item.action }} <ArrowRightOutlined aria-hidden="true" /></RouterLink>
          </article>
        </div>
      </div>
      <p
        v-if="!pendingItems.length"
        class="panel-empty"
      >{{ pendingPanelEmptyMessage }}</p>
      <div class="pending-panel__mobile-safety" data-dashboard-section="guardrails" aria-label="AI 安全提示">
        <AiSafetyState
          :state="briefStatus?.state ?? 'WARNING'"
          :message="briefStatus?.message ?? 'AI 建议仅用于辅助排序，处理前需人工核验。'"
        />
        <AiSafetyState state="INFO" message="关键结论必须有可靠来源，否则不执行。" />
      </div>
      <footer class="pending-panel__guardrail" data-dashboard-section="approval-flow">
        <ol class="approval-flow approval-flow--mobile" aria-label="AI 建议审批流程">
          <li><b>1</b> AI 建议 <ArrowRightOutlined class="approval-flow__arrow" aria-hidden="true" /></li>
          <li><b>2</b> 变更预览 <ArrowRightOutlined class="approval-flow__arrow" aria-hidden="true" /></li>
          <li><b>3</b> 人工审批</li>
        </ol>
        <SafetyCertificateOutlined aria-hidden="true" />
        <span>所有写操作均需人工审批确认后，方可执行。</span>
      </footer>
    </section>
  </div>
</template>

<script setup lang="ts">
import {
  AppstoreOutlined,
  ArrowRightOutlined,
  AuditOutlined,
  BankOutlined,
  BarChartOutlined,
  CheckSquareOutlined,
  ClockCircleOutlined,
  DollarOutlined,
  FileTextOutlined,
  InfoCircleOutlined,
  LinkOutlined,
  LineChartOutlined,
  LoadingOutlined,
  PayCircleOutlined,
  RiseOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  TeamOutlined,
  ToolOutlined,
} from '@ant-design/icons-vue'
import { useNow } from '@vueuse/core'
import { BarChart, LineChart } from 'echarts/charts'
import { AriaComponent, GridComponent, LegendComponent, TooltipComponent } from 'echarts/components'
import { use } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { computed, onMounted, ref, type Component } from 'vue'
import VChart from 'vue-echarts'
import { RouterLink } from 'vue-router'
import AiCommandBar from '../components/ai/AiCommandBar.vue'
import AiSafetyState from '../components/ai/AiSafetyState.vue'
import { isAiSurfaceEnabled } from '../api/ai-client'
import { useAiStore } from '../stores/ai'
import { useAiApprovalStore } from '../stores/aiApproval'
import { useAuthStore } from '../stores/auth'
import { useDormitoryStore } from '../stores/dormitory'
import type { AiProposalPreview } from '../types/ai'
import { isProposalEvidenceConfirmable } from '../utils/ai-approval'

use([CanvasRenderer, BarChart, LineChart, GridComponent, TooltipComponent, LegendComponent, AriaComponent])

const store = useDormitoryStore()
const auth = useAuthStore()
const ai = useAiStore()
const approval = useAiApprovalStore()
const proposalNow = useNow({ interval: 1_000 })
const trendDays = ref(7)
const dashboardLoadError = ref('')
const approvalQueryLoaded = ref(false)
const aiSurfaceEnabled = computed(() => isAiSurfaceEnabled())
const prefersReducedMotion = typeof window !== 'undefined'
  && typeof window.matchMedia === 'function'
  && window.matchMedia('(prefers-reduced-motion: reduce)').matches
const hasDashboardPermission = computed(() => auth.hasPermission('ai:dashboard:query'))
const canUseAiDashboard = computed(() => aiSurfaceEnabled.value && hasDashboardPermission.value)
const canViewRisks = computed(() => aiSurfaceEnabled.value && auth.hasPermission('ai:risk:read'))
const canReviewApprovals = computed(() => aiSurfaceEnabled.value && auth.hasPermission('ai:approval:review'))
const showAiPermissionState = computed(() => aiSurfaceEnabled.value && !hasDashboardPermission.value)
const hasStaleDashboardInsight = computed(() => Boolean(
  canUseAiDashboard.value && ai.dashboardInsight && ai.dashboardError && !ai.dashboardLoading,
))
const hasAccessibleDashboardEvidence = computed(() => Boolean(
  ai.dashboardInsight?.evidence.grounded
  && ai.dashboardInsight.evidence.citations.length > 0
  && ai.dashboardInsight.evidence.citations.every((citation) => citation.access === 'available'),
))
const hasUsableDashboardInsight = computed(() => Boolean(
  hasAccessibleDashboardEvidence.value
  && ['succeeded', 'degraded'].includes(ai.dashboardInsight?.state ?? ''),
))
const trustedAiSuggestionCount = computed(() => hasUsableDashboardInsight.value
  ? (ai.dashboardInsight?.riskCounts ?? []).reduce((sum, item) => (
      sum + Math.max(0, Number.isFinite(item.count) ? item.count : 0)
    ), 0)
  : 0)

async function loadBusinessDashboard() {
  dashboardLoadError.value = ''
  try {
    await store.loadAll(true, auth.user?.permissions ?? [])
  } catch (error) {
    dashboardLoadError.value = error instanceof Error ? error.message : '数据驾驶舱加载失败'
  }
}

async function loadPendingApprovalProposals() {
  approvalQueryLoaded.value = false
  try {
    await approval.loadProposals(
      { page: 1, pageSize: 5, state: 'pending_approval' },
      { reconcileSelection: false },
    )
  } finally {
    approvalQueryLoaded.value = true
  }
}

onMounted(async () => {
  await loadBusinessDashboard()
  if (canReviewApprovals.value) {
    void loadPendingApprovalProposals()
  }
  if (canUseAiDashboard.value && !ai.dashboardInsight) void ai.loadDashboard()
})

function runAiQuery(question: string) { void ai.loadDashboard(question) }
function retryDashboard() { void ai.loadDashboard() }
function retryBusinessDashboard() { void loadBusinessDashboard() }

function findStatistic(...keywords: string[]) {
  return store.statistics.find((item) => keywords.some((keyword) => item.title.includes(keyword)))
}

interface MetricCard {
  id: string
  title: string
  value: number | null
  unit: string
  tone: 'blue' | 'green' | 'purple' | 'orange'
  icon: Component
  trendIcon: Component
}

const dashboardCards = computed<MetricCard[]>(() => {
  const unavailable = Boolean(dashboardLoadError.value)
  const dormitory = findStatistic('宿舍总数', '宿舍')
  const students = findStatistic('学生入住人数', '入住人数', '学生')
  const vacant = findStatistic('空余床位', '空床')
  const repairs = findStatistic('待维修数量', '待维修', '维修')
  return [
    { id: 'dormitory', title: '宿舍总数', value: unavailable ? null : dormitory?.value ?? store.dormitories.length, unit: dormitory?.unit ?? '间', tone: 'blue', icon: BankOutlined, trendIcon: BarChartOutlined },
    { id: 'students', title: '入住人数', value: unavailable ? null : students?.value ?? store.students.length, unit: students?.unit ?? '人', tone: 'green', icon: TeamOutlined, trendIcon: RiseOutlined },
    { id: 'vacant', title: '空余床位', value: unavailable ? null : vacant?.value ?? Math.max(0, store.totalBeds - store.occupiedBeds), unit: vacant?.unit ?? '个', tone: 'purple', icon: AppstoreOutlined, trendIcon: LineChartOutlined },
    { id: 'repairs', title: '待维修', value: unavailable ? null : repairs?.value ?? store.pendingCounts.repairs, unit: repairs?.unit ?? '项', tone: 'orange', icon: ToolOutlined, trendIcon: BarChartOutlined },
  ]
})

const currentVacantBeds = computed(() => dashboardCards.value.find((item) => item.id === 'vacant')?.value ?? null)
const briefMetricVersionTitle = computed(() => {
  const version = ai.dashboardInsight?.metricVersion?.trim()
  return version ? `指标口径：${version}` : '指标口径暂无'
})
const briefMetricVersionLabel = computed(() => {
  const version = ai.dashboardInsight?.metricVersion?.trim()
  if (!version) return '运营指标口径 暂无'
  const publicVersion = version.match(/v\d+(?:\.\d+)?$/i)?.[0]
  return publicVersion ? `运营指标口径 ${publicVersion}` : '运营指标口径 已记录'
})
const BEIJING_TIME_ZONE = 'Asia/Shanghai'
const beijingTimeFormatter = new Intl.DateTimeFormat('zh-CN', {
  timeZone: BEIJING_TIME_ZONE,
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
})

function parseDashboardTimestamp(value: string) {
  let normalized = value.trim()
    .replace(/^(\d{4}-\d{2}-\d{2})\s+(\d{2}:\d{2})/, '$1T$2')
    .replace(/(\.\d{3})\d+(?=(?:Z|[+-]\d{2}:?\d{2})?$)/i, '$1')
  if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(normalized)
    && !/(?:Z|[+-]\d{2}:?\d{2})$/i.test(normalized)) {
    normalized += '+08:00'
  }
  const timestamp = new Date(normalized)
  return Number.isNaN(timestamp.getTime()) ? null : timestamp
}

function formatBeijingTimestamp(value: string) {
  const timestamp = parseDashboardTimestamp(value)
  if (!timestamp) return '-'
  const parts = beijingTimeFormatter.formatToParts(timestamp)
  const part = (type: Intl.DateTimeFormatPartTypes) => parts.find((item) => item.type === type)?.value ?? ''
  const formatted = `${part('year')}-${part('month')}-${part('day')} ${part('hour')}:${part('minute')}`
  return formatted.replace(/^-{2,}|\s+:$/, '-')
}

const briefAsOf = computed(() => {
  const value = ai.dashboardInsight?.evidence.asOf
  return value ? formatBeijingTimestamp(value) : '-'
})
const briefAsOfCompact = computed(() => briefAsOf.value === '-' ? '-' : briefAsOf.value.slice(5))
const briefAvailableCitationCount = computed(() => ai.dashboardInsight?.evidence.citations
  .filter((citation) => citation.access === 'available').length ?? 0)
const briefCitationLabel = computed(() => {
  const total = ai.dashboardInsight?.evidence.citations.length ?? 0
  return total === briefAvailableCitationCount.value
    ? `引用来源 ${total}`
    : `引用来源 ${briefAvailableCitationCount.value} / ${total}`
})
const briefCitationValue = computed(() => {
  const total = ai.dashboardInsight?.evidence.citations.length ?? 0
  return total === briefAvailableCitationCount.value
    ? String(total)
    : `${briefAvailableCitationCount.value}/${total}`
})
const briefConfidenceLabel = computed(() => {
  const confidence = ai.dashboardInsight?.evidence.confidence
  return confidence === undefined || !Number.isFinite(confidence)
    ? '置信度 暂无'
    : `置信度 ${Math.round(Math.min(1, Math.max(0, confidence)) * 100)}%`
})
const briefConfidenceValue = computed(() => {
  const confidence = ai.dashboardInsight?.evidence.confidence
  return confidence === undefined || !Number.isFinite(confidence)
    ? '暂无'
    : `${Math.round(Math.min(1, Math.max(0, confidence)) * 100)}%`
})
const briefConfidenceTone = computed(() => {
  const confidence = ai.dashboardInsight?.evidence.confidence
  if (confidence === undefined || !Number.isFinite(confidence)) return 'unknown'
  return confidence >= 0.8 ? 'high' : confidence >= 0.65 ? 'medium' : 'low'
})
const briefDisplaySummary = computed(() => {
  const insight = ai.dashboardInsight
  if (!insight) return ''
  if (!hasAccessibleDashboardEvidence.value) return '暂无可靠来源，不生成结论。'
  if (insight.state === 'cancelled') return '查询已取消，未展示未完成摘要。'
  if (insight.state === 'timed_out') return '查询已超时，未展示未完成摘要。'
  if (insight.state === 'failed') return '查询失败，未展示未完成摘要。'
  if (!['succeeded', 'degraded'].includes(insight.state)) return '运营简报尚未完成。'
  return formatEmbeddedUtcTimestamps(insight.summary.trim()) || '暂无可展示的运营摘要。'
})

function formatEmbeddedUtcTimestamps(value: string) {
  return value.replace(
    /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?Z\b/gi,
    (timestamp) => formatBeijingTimestamp(timestamp),
  )
}

type BriefStatusState = 'NO_GROUNDED' | 'DEGRADED' | 'WARNING' | 'FAILED' | 'INFO' | 'STALE'
interface BriefStatus { state: BriefStatusState; message: string }

const briefStatus = computed<BriefStatus | null>(() => {
  const insight = ai.dashboardInsight
  if (!insight) return null
  if (ai.dashboardLoading) return { state: 'INFO', message: '正在刷新运营简报，当前显示上一版只读结果。' }
  if (hasStaleDashboardInsight.value) {
    return { state: 'STALE', message: '运营简报刷新失败，当前显示上一版只读结果；请重新查询。' }
  }
  if (!hasAccessibleDashboardEvidence.value) {
    return { state: 'NO_GROUNDED', message: '暂无可靠来源，不生成结论；请检查数据缺失。' }
  }
  const lowConfidence = insight.evidence.confidence !== undefined && insight.evidence.confidence < 0.65
  if (insight.state === 'degraded') {
    return {
      state: 'DEGRADED',
      message: lowConfidence
        ? '当前为降级结果；置信度较低，需人工核验后再处理。'
        : '当前为降级结果，请人工核验后再处理。',
    }
  }
  if (insight.state === 'cancelled') return { state: 'INFO', message: '查询已取消，未生成新的运营结论。' }
  if (insight.state === 'timed_out') return { state: 'FAILED', message: '查询已超时，请稍后重新查询。' }
  if (insight.state === 'failed') return { state: 'FAILED', message: '查询失败，请核对数据状态后重试。' }
  if (lowConfidence) return { state: 'WARNING', message: '置信度较低，需人工核验后再处理。' }
  return null
})

const trendPoints = computed(() => store.checkInTrend.slice(-trendDays.value))
const trendRows = computed(() => {
  let cumulative = 0
  return trendPoints.value.map((item) => {
    cumulative += item.value
    return { date: item.date, checkIns: item.value, cumulative }
  })
})
const trendPanelEmptyMessage = computed(() => dashboardLoadError.value
  ? '趋势数据读取失败，请重新加载'
  : '暂无趋势数据')
const trendSparseState = computed(() => {
  if (!trendRows.value.length) return null
  const activeDays = trendRows.value.filter((item) => item.checkIns > 0).length
  if (activeDays === 0) {
    return {
      kind: 'zero',
      title: '所选周期无入住办理记录',
      detail: `图表保留真实零值，覆盖 ${trendRows.value.length} 天，不填充模拟数据。`,
    } as const
  }
  if (activeDays === 1 && trendRows.value.length >= 7) {
    return {
      kind: 'limited',
      title: '可见记录较少',
      detail: `所选周期仅 1 天有入住办理记录，暂不解读为稳定趋势。`,
    } as const
  }
  return null
})
const trendAriaDescription = computed(() => {
  const vacancy = currentVacantBeds.value === null
    ? '当前空床数据不可用'
    : `当前空床 ${currentVacantBeds.value} 个`
  const sparseDescription = trendSparseState.value
    ? `${trendSparseState.value.title}；${trendSparseState.value.detail}`
    : ''
  return `入住办理趋势图，显示近 ${trendRows.value.length} 天每日入住办理人次与累计入住人次；详细数据见本区域内数据表。${vacancy}。${sparseDescription}`
})
const trendOption = computed(() => {
  return {
    animation: !prefersReducedMotion,
    color: ['#5b8ff9', '#1267ed'],
    aria: { enabled: true, description: trendAriaDescription.value, decal: { show: false } },
    tooltip: { trigger: 'axis' },
    grid: { top: 24, left: 42, right: 24, bottom: 32, containLabel: true },
    xAxis: {
      type: 'category',
      data: trendRows.value.map((item) => item.date),
      axisTick: { show: false },
      axisLine: { lineStyle: { color: '#dbe5f3' } },
      axisLabel: { color: '#62708a', fontSize: 12 },
    },
    yAxis: {
      type: 'value', minInterval: 1,
      axisLabel: { color: '#62708a', fontSize: 12 },
      splitLine: { lineStyle: { color: '#e8eef7', type: 'dashed' } },
    },
    series: [
      { name: '入住办理', type: 'bar', barMaxWidth: 18, itemStyle: { color: '#5b8ff9', borderRadius: [3, 3, 0, 0] }, data: trendRows.value.map((item) => item.checkIns) },
      {
        name: '累计入住',
        type: 'line',
        smooth: true,
        symbol: 'circle',
        symbolSize: 7,
        itemStyle: { color: '#1267ed' },
        lineStyle: { color: '#1267ed', width: 3 },
        data: trendRows.value.map((item) => item.cumulative),
      },
    ],
  }
})

interface DashboardRiskItem {
  type: '入住风险' | '维修风险' | '卫生风险' | '欠费风险'
  tone: 'checkin' | 'repair' | 'hygiene' | 'payment'
  state: 'unavailable' | 'empty' | 'active'
  count: number | null
  severity: 'low' | 'medium' | 'high' | null
  description: string
}

const riskCatalog: Array<{
  type: DashboardRiskItem['type']
  tone: DashboardRiskItem['tone']
  matches: (type: string) => boolean
}> = [
  { type: '入住风险', tone: 'checkin', matches: (type) => type.includes('入住') },
  { type: '维修风险', tone: 'repair', matches: (type) => type.includes('维修') },
  { type: '卫生风险', tone: 'hygiene', matches: (type) => type.includes('卫生') },
  { type: '欠费风险', tone: 'payment', matches: (type) => type.includes('欠费') || type.includes('费用') },
]
const severityRank = { low: 0, medium: 1, high: 2 } as const
const riskItems = computed<DashboardRiskItem[]>(() => {
  const insight = ai.dashboardInsight
  if (!insight) return []
  const source = hasUsableDashboardInsight.value ? insight.riskCounts : []
  return riskCatalog.map((category) => {
    const matches = source.filter((risk) => category.matches(risk.type))
    if (!hasUsableDashboardInsight.value || matches.length === 0) {
      return {
        type: category.type,
        tone: category.tone,
        state: 'unavailable',
        count: null,
        severity: null,
        description: '该类别尚未提供风险数据',
      }
    }
    const count = matches.reduce((sum, risk) => sum + Math.max(0, Number.isFinite(risk.count) ? risk.count : 0), 0)
    if (count === 0) {
      return {
        type: category.type,
        tone: category.tone,
        state: 'empty',
        count: 0,
        severity: null,
        description: '已完成该类别风险检查',
      }
    }
    const severity = matches.filter((risk) => risk.count > 0).reduce<'low' | 'medium' | 'high'>((highest, risk) => (
      severityRank[risk.severity] > severityRank[highest] ? risk.severity : highest
    ), 'low')
    return {
      type: category.type,
      tone: category.tone,
      state: 'active',
      count,
      severity,
      description: `${count} 项已识别风险，详情与引用以风险中心为准`,
    }
  })
})
const riskPanelEmptyMessage = computed(() => {
  if (ai.dashboardLoading && !ai.dashboardInsight) return '正在加载风险数据'
  if (ai.dashboardError && !ai.dashboardInsight) return '风险数据加载失败，请使用上方“重新查询”重试'
  return '暂无风险数据'
})
const unmappedRiskTotal = computed(() => hasUsableDashboardInsight.value
  ? ai.dashboardInsight?.riskCounts
    .filter((risk) => !riskCatalog.some((category) => category.matches(risk.type)))
    .reduce((sum, risk) => sum + Math.max(0, Number.isFinite(risk.count) ? risk.count : 0), 0) ?? 0
  : 0)
function riskIcon(type: string): Component {
  if (type.includes('维修')) return ToolOutlined
  if (type.includes('欠费') || type.includes('费用')) return PayCircleOutlined
  if (type.includes('入住')) return TeamOutlined
  return SafetyCertificateOutlined
}
function riskDescription(type: string) {
  return riskItems.value.find((risk) => risk.type === type)?.description ?? '暂无该类风险数据'
}
function riskSeverityLabel(risk: DashboardRiskItem) {
  if (risk.state === 'unavailable') return '暂无数据'
  if (risk.state === 'empty') return '未发现'
  return risk.severity === 'high' ? '高风险' : risk.severity === 'medium' ? '中风险' : '低风险'
}
function riskCountLabel(risk: DashboardRiskItem) {
  if (risk.state === 'unavailable') return '暂无数据'
  if (risk.state === 'empty') return '0 项'
  return `${risk.count} 项风险`
}
function riskAccessibleLabel(risk: DashboardRiskItem) {
  return risk.state === 'active'
    ? `${risk.type}，${riskCountLabel(risk)}，${riskSeverityLabel(risk)}`
    : `${risk.type}，${riskSeverityLabel(risk)}`
}

type PendingKind = 'ai' | 'business'

interface PendingItem {
  id: string
  kind: PendingKind
  label: string
  mobileLabel: string
  count: number
  to: string
  action: string
  icon: Component
}

interface PendingTableRow extends PendingItem {
  recommendation: string
  recommendationFull: string
  confidence: string
  confidenceTone: 'high' | 'medium' | 'low' | 'deterministic' | 'unknown'
  preview: string
  previewFull: string
  citations: string
  suggestedAt: string
  kindLabel: string
  countLabel: string
  viewTo: string
  approvalTo: string
  viewAriaLabel: string
  approvalAriaLabel: string
  approvalId?: string
  canEnterApproval: boolean
}

const loadedPendingApprovalCount = computed(() => {
  const loadedCount = approval.proposals.filter((proposal) => proposal.state === 'pending_approval').length
  return Math.max(loadedCount, approval.proposalTotal)
})
const pendingApprovalMetricCount = computed<number | null>(() => (
  approvalQueryLoaded.value && !approval.error ? loadedPendingApprovalCount.value : null
))

const pendingItems = computed<PendingItem[]>(() => {
  if (dashboardLoadError.value) return []
  const items: PendingItem[] = []
  const pendingApprovalCount = pendingApprovalMetricCount.value ?? 0
  if (canReviewApprovals.value && pendingApprovalCount > 0) {
    items.push({ id: 'approval', kind: 'ai', label: 'AI 方案审批', mobileLabel: '进入审批中心', count: pendingApprovalCount, to: '/ai/approvals', action: '进入审批', icon: AuditOutlined })
  }
  if (canViewRisks.value && trustedAiSuggestionCount.value > 0) {
    items.push({
      id: 'suggestions', kind: 'ai', label: 'AI 风险线索', mobileLabel: '查看 AI 风险线索',
      count: trustedAiSuggestionCount.value, to: '/ai/risks', action: '查看线索', icon: BarChartOutlined,
    })
  }
  if (store.pendingCounts.repairs) items.push({ id: 'repair', kind: 'business', label: '维修申请', mobileLabel: '查看待处理维修', count: store.pendingCounts.repairs, to: '/repairs', action: '查看维修', icon: ToolOutlined })
  if (store.pendingCounts.applications) items.push({ id: 'application', kind: 'business', label: '入住申请', mobileLabel: '查看入住申请', count: store.pendingCounts.applications, to: '/applications', action: '进入审核', icon: TeamOutlined })
  if (store.pendingCounts.payments) items.push({ id: 'payment', kind: 'business', label: '缴费跟进', mobileLabel: '查看待跟进缴费', count: store.pendingCounts.payments, to: '/payments', action: '查看账单', icon: DollarOutlined })
  return items
})

const mobilePendingExpanded = ref(false)
const hasHiddenMobilePendingItems = computed(() => pendingItems.value.length > 2)
const mobilePendingItems = computed(() => mobilePendingExpanded.value
  ? pendingItems.value
  : pendingItems.value.slice(0, 2))

function dashboardConfidence() {
  const confidence = ai.dashboardInsight?.evidence.confidence
  if (confidence === undefined || !Number.isFinite(confidence)) {
    return { label: '需核验', tone: 'unknown' as const }
  }
  const normalized = Math.min(1, Math.max(0, confidence))
  return {
    label: `${Math.round(normalized * 100)}%`,
    tone: normalized >= 0.8 ? 'high' as const : normalized >= 0.65 ? 'medium' as const : 'low' as const,
  }
}

function availableCitationCount(proposal: AiProposalPreview) {
  return proposal.evidence.citations.filter((citation) => citation.access === 'available').length
}

function proposalPermissionIsSatisfied(proposal: AiProposalPreview) {
  if (!canReviewApprovals.value) return false
  const requirements = proposal.requiredPermission.split('+').map((item) => item.trim()).filter(Boolean)
  const permissionCodes = requirements.filter((item) => /^[a-z][a-z0-9_-]*:[a-z0-9:_-]+$/i.test(item))
  if (!permissionCodes.every((permission) => auth.hasPermission(permission))) return false
  if (requirements.includes('ADMIN')) {
    const roleCodes = new Set([auth.user?.roleCode, ...(auth.user?.roleCodes ?? [])].filter(Boolean))
    if (!roleCodes.has('ADMIN')) return false
  }
  return true
}

function proposalCanEnterApproval(proposal: AiProposalPreview) {
  const expiresAt = Date.parse(proposal.expiresAt)
  return Boolean(
    proposal.state === 'pending_approval'
    && Number.isFinite(expiresAt)
    && expiresAt > proposalNow.value.getTime()
    && proposal.auditAvailable
    && proposalPermissionIsSatisfied(proposal)
    && Number.isSafeInteger(proposal.version)
    && proposal.version >= 0
    && /^[0-9a-f]{64}$/i.test(proposal.payloadHash)
    && /^[0-9a-f]{64}$/i.test(proposal.businessSnapshotHash)
    && isProposalEvidenceConfirmable(proposal.evidence)
  )
}

function proposalConfidence(proposal: AiProposalPreview) {
  const confidence = proposal.evidence.confidence
  if (confidence !== undefined && Number.isFinite(confidence)) {
    const normalized = Math.min(1, Math.max(0, confidence))
    return {
      label: `${Math.round(normalized * 100)}%`,
      tone: normalized >= 0.8 ? 'high' as const : normalized >= 0.65 ? 'medium' as const : 'low' as const,
    }
  }
  return proposal.evidence.basis === 'deterministic'
    ? { label: '规则确定', tone: 'deterministic' as const }
    : { label: '需核验', tone: 'unknown' as const }
}

function proposalActionTo(proposal: AiProposalPreview, mode: 'view' | 'approve') {
  return `/ai/approvals?proposal=${encodeURIComponent(proposal.id)}&mode=${mode}`
}

function proposalTypeLabel(proposal: AiProposalPreview) {
  return proposal.actionType === 'REPAIR_ASSIGN' ? '维修派单' : '公告起草'
}

function summarizePendingText(value: string, maximumLength: number) {
  const normalized = value.replace(/\s+/g, ' ').trim()
  return normalized.length <= maximumLength
    ? normalized
    : `${normalized.slice(0, maximumLength - 1)}…`
}

function selectPendingProposal(id?: string) {
  if (id) approval.selectedId = id
}

const proposalPendingRows = computed<PendingTableRow[]>(() => approval.proposals
  .filter((proposal) => proposal.state === 'pending_approval')
  .map((proposal) => {
    const confidence = proposalConfidence(proposal)
    const recommendationFull = `${proposal.title}：${proposal.target}`
    const previewFull = `${proposal.currentValue} → ${proposal.proposedValue}；${proposal.impact}`
    const suggestedAt = formatBeijingTimestamp(proposal.evidence.asOf).slice(5)
    const actionContext = `${proposal.target}，建议时间 ${suggestedAt}，方案编号 ${proposal.id}`
    return {
      id: `proposal:${proposal.id}`,
      kind: 'ai',
      label: proposalTypeLabel(proposal),
      mobileLabel: proposal.title,
      count: 1,
      to: proposalActionTo(proposal, 'view'),
      action: '查看建议',
      icon: proposal.actionType === 'REPAIR_ASSIGN' ? ToolOutlined : FileTextOutlined,
      recommendation: summarizePendingText(recommendationFull, 36),
      recommendationFull,
      confidence: confidence.label,
      confidenceTone: confidence.tone,
      preview: summarizePendingText(previewFull, 42),
      previewFull,
      citations: `${availableCitationCount(proposal)} 条`,
      suggestedAt,
      kindLabel: 'AI 建议',
      countLabel: proposal.target,
      viewTo: proposalActionTo(proposal, 'view'),
      approvalTo: proposalActionTo(proposal, 'approve'),
      viewAriaLabel: `查看建议：${actionContext}`,
      approvalAriaLabel: `进入审批：${actionContext}`,
      approvalId: proposal.id,
      canEnterApproval: proposalCanEnterApproval(proposal),
    }
  }))

const fallbackPendingRows = computed<PendingTableRow[]>(() => pendingItems.value.map((item) => {
  const copyByType: Record<string, { recommendation: string; preview: string }> = {
    repair: {
      recommendation: `人工核对 ${item.count} 项维修申请后再安排处理。`,
      preview: '打开维修列表；不自动派单。',
    },
    application: {
      recommendation: `人工核对 ${item.count} 条入住申请的床位与材料。`,
      preview: '打开入住审核；不自动通过。',
    },
    payment: {
      recommendation: `人工跟进 ${item.count} 条未缴或部分缴账单。`,
      preview: '打开账单列表；不自动催缴。',
    },
    suggestions: {
      recommendation: `人工查看 ${item.count} 项具备可靠引用的运营风险线索。`,
      preview: '进入风险中心核验证据；不自动处置。',
    },
    approval: {
      recommendation: `进入审批中心复核 ${item.count} 项 AI 方案。`,
      preview: '服务端重验后方可执行。',
    },
  }
  const copy = copyByType[item.id] ?? {
    recommendation: `人工核对 ${item.count} 项待处理事项。`,
    preview: '仅提供建议，不自动执行。',
  }
  const confidence = item.kind === 'ai'
    ? dashboardConfidence()
    : { label: '规则确定', tone: 'deterministic' as const }
  return {
    ...item,
    ...copy,
    recommendationFull: copy.recommendation,
    confidence: confidence.label,
    confidenceTone: confidence.tone,
    citations: item.kind === 'ai' ? `${briefAvailableCitationCount.value} 条` : '业务数据',
    suggestedAt: item.kind === 'ai' ? briefAsOfCompact.value : '实时',
    kindLabel: item.kind === 'ai' ? 'AI 辅助' : '业务待办',
    countLabel: `${item.count} 项待处理`,
    viewTo: item.to,
    approvalTo: '/ai/approvals',
    viewAriaLabel: item.action,
    approvalAriaLabel: '进入审批中心',
    previewFull: copy.preview,
    canEnterApproval: false,
  }
}))

const pendingTableRows = computed<PendingTableRow[]>(() => {
  if (!proposalPendingRows.value.length) return fallbackPendingRows.value
  const businessRows = fallbackPendingRows.value.filter((item) => item.kind === 'business')
  return [...proposalPendingRows.value, ...businessRows].slice(0, 5)
})
const pendingTableGroups = computed(() => (['ai', 'business'] as const)
  .map((id) => ({ id, rows: pendingTableRows.value.filter((item) => item.kind === id) }))
  .filter((group) => group.rows.length > 0))

const pendingPanelEmptyMessage = computed(() => dashboardLoadError.value
  ? '待处理事项读取失败，请重新加载'
  : '暂无待处理事项')

function pendingItemDescription(item: PendingItem) {
  if (item.id === 'approval') {
    return item.count ? `共有 ${item.count} 项待审批方案` : '暂无待审批方案'
  }
  if (item.id === 'suggestions') {
    return item.count ? `共有 ${item.count} 项已引用风险线索` : '暂无已引用风险线索'
  }
  return item.count ? `共有 ${item.count} 项待人工处理` : '暂无待人工处理事项'
}

</script>

<style scoped>
.dashboard-view {
  --cockpit-blue: var(--primary);
  --cockpit-ink: var(--text-title);
  --cockpit-muted: var(--text-muted);
  --cockpit-border: var(--border);
  display: grid;
  gap: 10px;
  color: var(--cockpit-ink);
}

.dashboard-ai-state,
.dashboard-business-state {
  min-height: 48px;
  font-size: 14px;
}

.dashboard-command :deep(.ai-command-bar) {
  min-height: 58px;
  border-color: #bed2f4;
  border-radius: 8px;
  box-shadow: 0 5px 16px rgba(22, 74, 157, 0.08);
}

.dashboard-command :deep(.ai-command-bar__button) {
  width: 44px;
  min-width: 44px;
  padding: 0;
  overflow: hidden;
  color: var(--cockpit-blue);
  border: 0;
  background: transparent;
}

.dashboard-command :deep(.ai-command-bar__button > span:last-child) {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 14px;
}
.metric-grid--four { grid-template-columns: repeat(4, minmax(0, 1fr)); }

.metric-card {
  min-height: 108px;
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 16px 18px;
  border: 1px solid var(--cockpit-border);
  border-radius: 8px;
  background: var(--surface);
  box-shadow: var(--shadow-card);
}

.metric-card__icon {
  width: 52px;
  height: 52px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  border-radius: 50%;
  color: #1267ed;
  background: #edf4ff;
  font-size: 25px;
}

.metric-card--green .metric-card__icon { color: #04a875; background: #e9f8f2; }
.metric-card--purple .metric-card__icon { color: #6547ed; background: #f1edff; }
.metric-card--orange .metric-card__icon { color: #f27a00; background: #fff2e3; }
.metric-card--approval .metric-card__icon { color: #1267ed; background: #edf4ff; }

.metric-card__copy { min-width: 0; display: grid; gap: 6px; }
.metric-card__copy > span { color: #3f4d67; font-size: 14px; overflow-wrap: anywhere; }
.metric-card__copy strong { color: #0f1f3d; font-size: 29px; line-height: 1; }
.metric-card__copy small { color: #4f5d75; font-size: 12px; font-weight: 500; }
.metric-card__trend { display: none; margin-left: auto; color: #10a972; font-size: 18px; }
.metric-card--orange .metric-card__trend { color: #f27a00; }
.metric-card > a {
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  display: grid;
  place-items: center;
  margin-left: auto;
  border-radius: 6px;
  color: var(--cockpit-blue);
  font-size: 18px;
}

.cockpit-panel {
  min-width: 0;
  overflow: hidden;
  border: 1px solid var(--cockpit-border);
  border-radius: 8px;
  background: var(--surface);
  box-shadow: var(--shadow-card);
}

.panel-heading {
  min-height: 52px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 0 18px;
}

.panel-heading__title { min-width: 0; display: flex; align-items: center; gap: 10px; color: #365edb; }
.panel-heading h2 { margin: 0; color: var(--cockpit-ink); font-size: 18px; font-weight: 600; line-height: 1.35; }
.panel-heading a { min-height: 44px; display: inline-flex; align-items: center; gap: 6px; white-space: nowrap; }
.brief-ai-icon { flex: 0 0 auto; color: #5d4de5; font-size: 20px; }

.ai-brief { overflow: visible; padding-bottom: 14px; }
.ai-brief__heading { border-bottom: 1px solid #e5ebf4; }
.ai-brief__meta { display: flex; align-items: center; gap: 24px; color: var(--cockpit-muted); font-size: 12px; }
.ai-brief__summary { margin: 14px 18px 10px; color: #2f3d57; font-size: 14px; line-height: 1.7; }
.ai-brief__version {
  padding: 3px 7px;
  border-radius: 4px;
  color: #53617a;
  background: #f1f4f8;
  font-size: 12px;
  font-weight: 500;
  white-space: nowrap;
}
.ai-brief__facts {
  display: flex;
  align-items: stretch;
  gap: 8px;
  margin: 0 18px;
}
.ai-brief__facts > [data-brief-meta] {
  min-width: 0;
  min-height: 40px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 10px;
  border: 1px solid #e3eaf4;
  border-radius: 6px;
  color: var(--cockpit-muted);
  background: #f9fbfe;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.ai-brief__fact-copy { min-width: 0; display: inline-flex; align-items: center; gap: 4px; }
.ai-brief__fact-label,
.ai-brief__fact-value { color: inherit; font-size: 12px; font-weight: 500; line-height: 18px; }
.ai-brief__fact-value { font-weight: 600; }
.ai-brief__as-of-mobile { display: none; }
.ai-brief__citations { position: relative; }
.ai-brief__citations summary {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  cursor: pointer;
  list-style: none;
}
.ai-brief__citations summary::-webkit-details-marker { display: none; }
.ai-brief__citations summary:focus-visible { outline: 3px solid var(--primary); outline-offset: 2px; }
.ai-brief__citations ul,
.ai-brief__citations p {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  z-index: 5;
  width: min(360px, 80vw);
  margin: 0;
  padding: 8px 10px;
  border: 1px solid #dbe5f2;
  border-radius: 6px;
  color: #43516a;
  background: #fff;
  box-shadow: 0 8px 24px rgb(35 59 98 / 14%);
  list-style: none;
}
.ai-brief__citations li { padding: 5px 0; overflow-wrap: anywhere; }
.ai-brief__fact--high { color: #087a5b !important; background: #eef9f5 !important; }
.ai-brief__fact--medium { color: #b45a00 !important; background: #fff8eb !important; }
.ai-brief__fact--low { color: #c2410c !important; background: #fff6ed !important; }
.ai-brief__fact--unknown { color: #667085 !important; }
.ai-brief__warning { margin: 12px 18px 0; }
.ai-brief__loading-copy {
  min-height: 68px;
  display: flex;
  align-items: center;
  margin: 0;
  padding: 0 18px;
  color: var(--cockpit-muted);
}

.dashboard-analysis { display: grid; grid-template-columns: minmax(0, 1.08fr) minmax(360px, .92fr); gap: 14px; }
.dashboard-analysis--trend-only { grid-template-columns: 1fr; }
.trend-panel, .risk-panel { min-height: 318px; }
.trend-panel .panel-heading, .risk-panel .panel-heading { border-bottom: 1px solid #edf1f7; }
.trend-window select { min-height: 44px; padding: 0 32px 0 12px; border: 1px solid #d7e1ef; border-radius: 6px; color: #43516a; background: #fff; }
.trend-meta { display: flex; align-items: center; gap: 22px; padding: 10px 18px 0; color: var(--cockpit-muted); font-size: 12px; }
.trend-meta span { display: inline-flex; align-items: center; gap: 7px; }
.trend-meta strong { margin-left: auto; color: #45536d; font-weight: 600; }
.legend-bar { width: 13px; height: 9px; border-radius: 2px; background: #5b8ff9; }
.legend-line { width: 16px; height: 3px; border-radius: 2px; background: #1267ed; }
.dashboard-trend-stage { position: relative; width: 100%; height: 240px; }
.dashboard-trend-chart { width: 100%; height: 100%; }
.trend-sparse-state {
  position: absolute;
  top: 54px;
  left: 50%;
  max-width: min(360px, calc(100% - 40px));
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 10px 12px;
  transform: translateX(-50%);
  border: 1px solid #cfe0f8;
  border-radius: 6px;
  color: #27466f;
  background: rgba(247, 250, 255, .96);
  box-shadow: 0 4px 12px rgba(37, 99, 235, .08);
  pointer-events: none;
}
.trend-sparse-state > svg { flex: 0 0 auto; margin-top: 2px; color: #2563eb; font-size: 16px; }
.trend-sparse-state span { min-width: 0; display: grid; gap: 2px; }
.trend-sparse-state strong { color: #17345c; font-size: 14px; font-weight: 600; line-height: 20px; }
.trend-sparse-state small { color: #526987; font-size: 12px; line-height: 18px; }

.risk-list { display: grid; gap: 7px; padding: 9px 14px 8px; }
.risk-row { min-height: 50px; display: grid; grid-template-columns: 34px 82px 104px minmax(0, 1fr) 14px; align-items: center; gap: 8px; padding: 7px 10px; border: 1px solid #e4eaf3; border-radius: 6px; color: inherit; }
.risk-row:hover, .risk-row:focus-visible { border-color: #9fc2fb; background: #f7faff; }
.risk-row__icon { width: 28px; height: 28px; display: grid; place-items: center; border-radius: 7px; color: #1769ea; background: #eef5ff; }
.risk-row__label { min-width: 0; }
.risk-row__label strong { color: var(--cockpit-ink); font-size: 14px; }
.risk-row__status { min-width: 0; display: inline-flex; align-items: baseline; gap: 5px; color: var(--danger-strong); font-size: 14px; line-height: 20px; white-space: nowrap; }
.risk-row__status strong { font-size: inherit; font-weight: 600; }
.risk-row__status small { color: inherit; font-size: inherit; }
.risk-row__status--empty { color: var(--success-strong); }
.risk-row__status--unavailable { color: #52617a; }
.risk-row__description { min-width: 0; overflow: hidden; color: var(--cockpit-muted); font-size: 13px; line-height: 20px; text-overflow: ellipsis; white-space: nowrap; }
.risk-row__icon--checkin { color: #1769ea; background: #eef5ff; }
.risk-row__icon--repair { color: #e96b00; background: #fff3e8; }
.risk-row__icon--hygiene { color: #079568; background: #eaf9f4; }
.risk-row__icon--payment { color: #e5484d; background: #fff0f1; }
.risk-row--empty { background: #fbfcfe; }
.risk-row__arrow { color: #6d7b93; font-size: 11px; }
.risk-panel :deep(.ai-safety-state) { margin: 0 14px 10px; padding: 6px 9px; font-size: 14px; }

.pending-panel__heading { min-height: 44px; border-bottom: 1px solid #e5ebf4; }
.pending-panel__heading h2 span { color: #53617a; font-size: 13px; font-weight: 500; }
.pending-panel__count--mobile { display: none; }
.pending-panel__mobile-toggle { display: none; }
.approval-flow { display: flex; align-items: center; gap: 12px; margin: 0; padding: 0; color: #5b6880; list-style: none; font-size: 14px; }
.approval-flow li { display: inline-flex; align-items: center; gap: 6px; }
.approval-flow b { width: 18px; height: 18px; display: grid; place-items: center; border-radius: 50%; color: #fff; background: var(--cockpit-blue); font-size: 10px; }
.approval-flow__arrow { margin-left: 6px; color: #8090a8; }
.pending-table-wrap { padding: 0 14px; }
.pending-table { width: 100%; border-collapse: collapse; table-layout: fixed; font-size: 14px; }
.pending-table th, .pending-table td { padding: 0 10px; border: 1px solid #dce4ef; text-align: left; }
.pending-table th { height: 34px; color: #53617a; background: #f8fafe; font-weight: 600; }
.pending-table td { min-height: 48px; height: 48px; color: #35435c; vertical-align: middle; }
.pending-table th:nth-child(1) { width: 12%; }
.pending-table th:nth-child(2) { width: 23%; }
.pending-table th:nth-child(3) { width: 8%; }
.pending-table th:nth-child(4) { width: 25%; }
.pending-table th:nth-child(5) { width: 8%; }
.pending-table th:nth-child(6) { width: 9%; }
.pending-table th:nth-child(7) { width: 15%; }
.pending-table tbody[data-pending-group="ai"] td { background: #fbfaff; }
.pending-table tbody[data-pending-group="business"] tr:first-child td { border-top-width: 2px; border-top-color: #b8c6da; }
.pending-table td:not(:first-child) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pending-table td[data-pending-column="recommendation"],
.pending-table td[data-pending-column="preview"] {
  overflow: hidden;
  padding-block: 3px;
  line-height: 20px;
  overflow-wrap: anywhere;
  white-space: normal;
}
.pending-table td[data-pending-column="confidence"] { color: #0a7d62; font-weight: 600; text-align: center; }
.pending-table td[data-pending-column="confidence"][data-confidence-tone="medium"] { color: #b65f00; }
.pending-table td[data-pending-column="confidence"][data-confidence-tone="low"] { color: #c43f45; }
.pending-table td[data-pending-column="confidence"][data-confidence-tone="unknown"] { color: #68758c; }
.pending-table td[data-pending-column="citations"],
.pending-table td[data-pending-column="suggested-at"] { color: #52617a; text-align: center; }
.pending-table td:first-child { overflow: hidden; }
.pending-table td:last-child { padding-inline: 4px; }
.pending-table__title-row { min-width: 0; display: flex; align-items: center; gap: 5px; }
.pending-table td:first-child strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pending-table__kind {
  flex: 0 0 auto;
  display: inline-flex;
  color: #475569;
  font-size: 12px;
  font-weight: 600;
  line-height: 18px;
}
.pending-table tr[data-pending-kind="ai"] .pending-table__kind { color: #5d4de5; }
.pending-table__count { display: block; overflow: hidden; color: var(--cockpit-muted); font-size: 12px; line-height: 18px; text-overflow: ellipsis; white-space: nowrap; }
.pending-table__actions { display: flex; align-items: center; gap: 6px; flex-wrap: nowrap; }
.pending-table__actions a {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 0 7px;
  border: 1px solid #2d78f0;
  border-radius: 4px;
  color: var(--cockpit-blue);
  white-space: nowrap;
}
.pending-table__actions a[data-pending-approval] { color: #fff; background: var(--cockpit-blue); }
.pending-reflow-list,
.pending-mobile-list { display: none; }
.pending-panel__mobile-safety,
.approval-flow--mobile { display: none; }
.pending-panel__guardrail { min-height: 44px; display: flex; align-items: center; justify-content: center; gap: 9px; color: #65738b; font-size: 14px; }
.pending-panel__guardrail svg { color: #5364e9; font-size: 18px; }
.panel-empty { margin: 0; padding: 42px 18px; color: var(--cockpit-muted); text-align: center; }

.metric-card > a:focus-visible,
.panel-heading a:focus-visible,
.risk-row:focus-visible,
.pending-table a:focus-visible,
.pending-reflow-list a:focus-visible,
.pending-mobile-list a:focus-visible,
.trend-window select:focus-visible {
  outline: 3px solid var(--primary);
  outline-offset: 2px;
}

@media (min-width: 769px) {
  .brief-document-icon { display: none; }
  .ai-brief { padding-bottom: 8px; }
  .ai-brief__heading { min-height: 52px; }
  .ai-brief__summary {
    margin: 7px 18px 5px;
    line-height: 1.4;
    white-space: normal;
    overflow-wrap: anywhere;
  }
  .ai-brief__facts { min-height: 40px; flex: 0 1 auto; margin: 0 0 0 auto; }
  .ai-brief__facts > [data-brief-meta] { min-height: 40px; padding: 4px 8px; }
  .ai-brief__warning {
    min-height: 36px;
    align-items: center;
    margin: 6px 18px 0;
    padding: 5px 9px;
    font-size: 14px;
  }
}

@media (min-width: 1360px) {
  .dashboard-command {
    position: fixed;
    top: 9px;
    left: 48%;
    z-index: 12;
    width: min(460px, calc(52vw - 370px));
  }
  .dashboard-command :deep(.ai-command-bar) {
    min-height: 46px;
    height: 46px;
    box-shadow: none;
  }
}

@media (min-width: 1101px) and (max-height: 1000px) {
  .dashboard-view { gap: 7px; }
  .trend-panel,
  .risk-panel { min-height: 282px; }
  .dashboard-trend-stage { height: 204px; }
  .pending-panel__guardrail { min-height: 32px; }
}

@media (max-width: 1100px) {
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .metric-card--desktop { display: none; }
  .dashboard-analysis { grid-template-columns: 1fr; }
}

@media (max-width: 1100px) {
  .pending-table { display: none; }
  .pending-reflow-list {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    border-top: 1px solid #dce4ef;
    border-left: 1px solid #dce4ef;
  }
  .pending-reflow-list article {
    min-width: 0;
    display: grid;
    align-content: start;
    gap: 12px;
    padding: 14px;
    border-right: 1px solid #dce4ef;
    border-bottom: 1px solid #dce4ef;
  }
  .pending-reflow-list__heading {
    min-width: 0;
    display: grid;
    grid-template-columns: 36px minmax(0, 1fr);
    align-items: center;
    gap: 10px;
  }
  .pending-reflow-list__icon {
    width: 36px;
    height: 36px;
    display: grid;
    place-items: center;
    border-radius: 7px;
    color: var(--cockpit-blue);
    background: #eef5ff;
    font-size: 18px;
  }
  .pending-reflow-list__title { min-width: 0; display: grid; gap: 2px; }
  .pending-reflow-list__kind { color: #5d4de5 !important; font-weight: 600; }
  .pending-reflow-list__title strong { color: var(--cockpit-ink); font-size: 14px; }
  .pending-reflow-list__title small { color: var(--cockpit-muted); font-size: 12px; }
  .pending-reflow-list dl { display: grid; gap: 9px; margin: 0; }
  .pending-reflow-list dl > div { display: grid; grid-template-columns: 76px minmax(0, 1fr); gap: 8px; }
  .pending-reflow-list dt { color: #53617a; font-size: 12px; font-weight: 600; }
  .pending-reflow-list dd { min-width: 0; margin: 0; color: #35435c; font-size: 14px; line-height: 1.5; overflow-wrap: anywhere; }
  .pending-reflow-list__actions { display: flex; flex-wrap: wrap; gap: 8px; margin-top: auto; }
  .pending-reflow-list__actions a {
    min-height: 44px;
    display: inline-flex;
    align-items: center;
    padding: 0 12px;
    border: 1px solid #2d78f0;
    border-radius: 6px;
    color: var(--cockpit-blue);
    background: var(--surface);
  }
  .pending-reflow-list__actions a[data-pending-approval] { color: #fff; background: var(--cockpit-blue); }
}

@media (max-width: 768px) {
  .dashboard-view { align-content: start; gap: 4px; padding-bottom: max(16px, var(--safe-area-bottom)); }
  .dashboard-command :deep(.ai-command-bar) { min-height: 52px; height: 52px; flex-wrap: nowrap; padding: 0 4px 0 10px; border-radius: 8px; }
  .dashboard-command :deep(.ai-command-bar__input) { flex-basis: auto; height: 52px; font-size: 16px; }
  .dashboard-command :deep(.ai-command-bar__button) { width: 44px; min-width: 44px; min-height: 44px; }

  .metric-grid { gap: 8px; }
  .metric-card { position: relative; min-height: 70px; align-items: center; gap: 8px; padding: 8px; }
  .metric-card__icon { width: 40px; height: 40px; font-size: 20px; }
  .metric-card__copy { gap: 3px; }
  .metric-card__copy > span { font-size: 14px; line-height: 20px; }
  .metric-card__copy strong { font-size: 26px; line-height: 28px; }
  .metric-card__copy small { font-size: 12px; }
  .metric-card__trend { position: absolute; right: 10px; bottom: 10px; display: inline-flex; margin: 0; font-size: 16px; }

  .panel-heading { min-height: 48px; padding: 0 12px; }
  .panel-heading__title { gap: 8px; }
  .panel-heading h2 { font-size: 16px; }
  .brief-ai-icon { display: none; }
  .brief-document-icon { display: inline-flex; color: #5d4de5; }
  .ai-brief { order: 3; padding-bottom: 0; }
  .ai-brief__heading { min-height: 44px; align-items: center; flex-wrap: wrap; }
  .ai-brief__meta > span { display: none; }
  .ai-brief__meta a { display: none; }
  .ai-brief__summary,
  .ai-brief__version { display: none; }
  .ai-brief__facts { order: 3; flex-basis: 100%; display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 0; margin: 0; border-top: 1px solid #e5ebf4; }
  .ai-brief__facts > [data-brief-meta] { min-height: 52px; display: grid; grid-template-columns: 18px minmax(0, 1fr); place-content: center; align-items: center; gap: 6px; padding: 6px; border: 0; border-right: 1px solid #e5ebf4; border-radius: 0; background: transparent !important; text-align: left; }
  .ai-brief__facts > .ai-brief__citations { display: block; padding: 6px; }
  .ai-brief__facts > [data-brief-meta]:last-child { border-right: 0; }
  .ai-brief__fact-copy { display: grid; gap: 1px; }
  .ai-brief__fact-label { font-size: 14px; font-weight: 500; line-height: 20px; }
  .ai-brief__fact-value { font-size: 22px; font-weight: 700; line-height: 26px; }
  [data-brief-meta="as-of"] .ai-brief__fact-value { font-size: 14px; line-height: 20px; }
  .ai-brief__citations summary { min-height: 52px; display: grid; grid-template-columns: 18px minmax(0, 1fr); align-items: center; gap: 6px; padding: 0; text-align: left; }
  .ai-brief__citations ul,
  .ai-brief__citations p { left: 50%; width: min(270px, calc(100vw - 120px)); transform: translateX(-50%); text-align: left; }
  .ai-brief__warning { min-height: 44px; display: flex; align-items: center; margin: 4px 10px 4px; padding: 6px 10px; font-size: 14px; line-height: 20px; }
  .ai-brief__as-of-full { display: none; }
  .ai-brief__as-of-mobile { display: inline; }

  .dashboard-analysis { order: 4; gap: 6px; }
  .risk-panel { order: -1; min-height: auto; }
  .risk-list { grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 4px; padding: 4px 8px 6px; }
  .risk-row { min-height: 100px; grid-template-columns: minmax(0, 1fr); grid-template-areas: "icon" "label" "status"; align-content: center; justify-items: center; gap: 3px; padding: 5px 2px; text-align: center; }
  .risk-row--checkin { background: #f3f7ff; }
  .risk-row--repair { background: #fff7ed; }
  .risk-row--hygiene { background: #effaf6; }
  .risk-row--payment { background: #fff2f3; }
  .risk-row--empty { background: var(--surface-muted); }
  .risk-row__icon { grid-area: icon; width: 22px; height: 22px; align-self: center; justify-self: center; font-size: 14px; }
  .risk-row__label { grid-area: label; justify-self: center; }
  .risk-row__description, .risk-row__arrow { display: none; }
  .risk-row__label strong { font-size: 14px; line-height: 20px; }
  .risk-row__status { grid-area: status; justify-self: center; display: grid; justify-items: center; gap: 0; font-size: 14px; line-height: 18px; }
  .risk-row__status small { font-size: inherit; line-height: 18px; }

  .trend-panel { min-height: 200px; }
  .trend-window select { min-height: 44px; padding: 0 28px 0 10px; font-size: 14px; }
  .trend-meta { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px 8px; padding: 8px 12px 0; font-size: 14px; }
  .trend-meta span { min-width: 0; gap: 4px; white-space: nowrap; }
  .trend-meta span:last-of-type { justify-self: end; }
  .trend-meta strong { display: none; }
  .dashboard-trend-stage { height: 125px; }
  .dashboard-trend-chart { height: 125px; }
  .trend-sparse-state { top: 19px; max-width: calc(100% - 28px); padding: 7px 9px; }
  .trend-sparse-state strong { font-size: 14px; line-height: 18px; }
  .trend-sparse-state small { font-size: 12px; line-height: 16px; }
  .trend-panel--sparse { min-height: 160px; }
  .trend-panel--sparse .dashboard-trend-stage,
  .trend-panel--sparse .dashboard-trend-chart { height: 84px; }
  .trend-panel--sparse .trend-sparse-state { top: 5px; padding: 5px 8px; }

  .pending-panel { order: 5; }
  .pending-panel__heading { min-height: 44px; justify-content: space-between; padding: 0 8px 0 12px; }
  .pending-panel__count--desktop { display: none; }
  .pending-panel__count--mobile { display: inline; }
  .pending-panel__mobile-toggle {
    min-height: 44px;
    display: inline-flex;
    align-items: center;
    justify-content: center;
    padding: 0 6px;
    border: 0;
    color: var(--cockpit-blue);
    background: transparent;
    font-size: 14px;
    font-weight: 600;
  }
  .approval-flow--desktop { display: none; }
  .approval-flow--mobile { width: 100%; display: flex; justify-content: space-between; gap: 4px; }
  .approval-flow { font-size: 14px; }
  .approval-flow li { flex: 0 0 auto; gap: 4px; white-space: nowrap; }
  .approval-flow--mobile .approval-flow__arrow { margin-left: 2px; font-size: 12px; }
  .approval-flow b { width: 20px; height: 20px; font-size: 11px; }
  .pending-table { display: none; }
  .pending-reflow-list { display: none; }
  .pending-table-wrap { padding: 0 10px; }
  .pending-mobile-list { display: grid; }
  .pending-mobile-list article { min-height: 62px; display: grid; grid-template-columns: 36px minmax(0, 1fr) auto; align-items: center; gap: 8px; border-bottom: 1px solid #e5ebf4; }
  .pending-mobile-list__icon { width: 36px; height: 36px; display: grid; place-items: center; border-radius: 8px; color: #5d4de5; background: #f0edff; font-size: 18px; }
  .pending-mobile-list article > div { min-width: 0; display: grid; gap: 2px; }
  .pending-mobile-list strong { color: var(--cockpit-ink); font-size: 14px; }
  .pending-mobile-list small { color: var(--cockpit-muted); font-size: 14px; line-height: 20px; overflow-wrap: anywhere; }
  .pending-mobile-list a { min-height: 44px; display: inline-flex; align-items: center; gap: 4px; padding: 0 9px; border: 1px solid #2d78f0; border-radius: 7px; white-space: nowrap; font-size: 14px; }
  .pending-panel__mobile-safety { display: grid; gap: 6px; padding: 6px 10px 0; }
  .pending-panel__mobile-safety :deep(.ai-safety-state) { min-height: 44px; padding: 8px 10px; font-size: 14px; }
  .pending-panel__guardrail {
    min-height: 80px;
    flex-direction: column;
    gap: 6px;
    padding: 8px 10px;
    text-align: center;
    font-size: 14px;
  }
  .pending-panel__guardrail > svg { display: none; }
}
</style>
