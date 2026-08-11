<template>
  <section class="audit-page" aria-label="AI 运行审计内容">
    <AiSafetyState v-if="!enabled" message="AI 运行审计当前关闭。" tone="warning" />
    <nav class="audit-breadcrumb" aria-label="面包屑">AI 能力 <span>/</span> 审批与审计</nav>
    <nav class="governance-tabs" aria-label="审批与审计视图">
      <RouterLink to="/ai/approvals">待审批</RouterLink>
      <RouterLink class="active" to="/ai/audit" aria-current="page">运行审计</RouterLink>
    </nav>

    <AiSafetyState v-if="loadError" :message="loadError" tone="danger" action-label="重新加载" @action="initialize" />

    <form class="audit-filter-bar" aria-label="运行审计筛选" @submit.prevent="applyFilters">
      <label><span>能力</span>
        <select v-model="capabilityFilter" aria-label="按能力筛选"><option value="">全部能力</option><option v-for="option in capabilityOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select>
      </label>
      <label><span>状态</span>
        <select v-model="stateFilter" aria-label="按状态筛选"><option value="">全部状态</option><option v-for="option in stateOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select>
      </label>
      <label><span>Provider</span>
        <select v-model="providerFilter" aria-label="按 Provider 筛选"><option value="">全部 Provider</option><option v-for="option in providerOptions" :key="option.value" :value="option.value">{{ option.label }}</option></select>
      </label>
      <label><span>开始时间</span><input v-model="fromFilter" aria-label="开始时间" type="datetime-local" /></label>
      <label><span>结束时间</span><input v-model="toFilter" aria-label="结束时间" type="datetime-local" /></label>
      <button type="submit" :disabled="auditLoading"><SearchOutlined />{{ auditLoading ? '筛选中' : '应用筛选' }}</button>
    </form>

    <div class="audit-workbench" :aria-busy="auditLoading || detailLoading">
      <aside class="audit-panel run-rail" data-testid="audit-run-rail" aria-label="审计运行列表">
        <header class="rail-heading">
          <div><h2>运行记录（{{ store.auditTotal || store.audits.length }}）</h2><p>按时间倒序展示脱敏摘要</p></div>
          <div class="view-toggle" aria-label="审计视图模式">
            <button type="button" :class="{ active: viewMode === '业务视图' }" @click="viewMode = '业务视图'">业务</button>
            <button type="button" :class="{ active: viewMode === '技术视图' }" @click="viewMode = '技术视图'">技术</button>
          </div>
        </header>

        <div class="run-list" role="list" aria-live="polite">
          <article v-for="run in store.audits" :key="run.id" class="ai-audit-run" :class="{ selected: selectedRun?.id === run.id }">
            <button type="button" class="run-select" :aria-pressed="selectedRun?.id === run.id" :disabled="detailLoading" @click="selectAudit(run.id, { focusDetail: true })">
              <span class="run-state" :class="runStateClass(run.state)"><CheckCircleFilled v-if="isSuccessful(run.state)" /><ClockCircleFilled v-else /></span>
              <span class="run-copy">
                <span class="run-title"><strong>{{ capabilityLabel(run.capability) }}</strong><i :class="runStateClass(run.state)">{{ runStateLabel(run.state) }}</i></span>
                <small>{{ run.id }}</small>
                <span class="run-meta"><time>{{ formatTime(run.occurredAt) }}</time><b>{{ run.durationMs }} ms</b></span>
                <span v-if="viewMode === '技术视图'" class="run-technical">{{ run.modelAlias }} · {{ run.promptVersion }}</span>
              </span>
              <span class="view-detail-text">查看运行详情</span>
            </button>
          </article>
          <p v-if="!store.audits.length" class="empty-state">当前筛选条件下暂无运行记录</p>
        </div>

        <footer class="run-pagination">
          <span>共 {{ store.auditTotal || store.audits.length }} 条</span>
          <div>
            <button type="button" :disabled="auditLoading || store.auditPage <= 1" aria-label="上一页" @click="changeAuditPage(store.auditPage - 1)"><LeftOutlined /></button>
            <button type="button" class="active" disabled aria-current="page" :aria-label="`第 ${store.auditPage} 页`">{{ store.auditPage }}</button>
            <button type="button" :disabled="auditLoading || store.auditPage >= auditPageCount" aria-label="下一页" @click="changeAuditPage(store.auditPage + 1)"><RightOutlined /></button>
          </div>
          <span>{{ store.auditPageSize }} 条/页</span>
        </footer>
      </aside>

      <main class="audit-panel detail-panel" data-testid="audit-detail-panel" aria-label="审计运行详情">
        <template v-if="selectedRun">
          <header class="detail-heading">
            <div><span><h2 ref="detailHeadingRef" data-testid="audit-detail-heading" tabindex="-1">{{ capabilityLabel(selectedRun.capability) }}</h2><i :class="runStateClass(selectedRun.state)">{{ runStateLabel(selectedRun.state) }}</i></span><p>运行编号：{{ selectedRun.id }}</p></div>
            <button type="button" class="refresh-button" :disabled="detailLoading" @click="selectAudit(selectedRun.id, { focusDetail: true })"><ReloadOutlined />{{ detailLoading ? '加载中' : '查看运行详情' }}</button>
          </header>

          <dl class="run-facts">
            <div><dt>模型别名</dt><dd>{{ selectedRun.modelAlias }}</dd></div>
            <div><dt>提示词版本</dt><dd>{{ selectedRun.promptVersion }}</dd></div>
            <div><dt>引用来源</dt><dd>{{ selectedRun.citationCount }} 条</dd></div>
            <div><dt>总耗时</dt><dd>{{ selectedRun.durationMs }} ms</dd></div>
            <div><dt>输入 Token</dt><dd>{{ selectedRun.inputTokens }}</dd></div>
            <div><dt>输出 Token</dt><dd>{{ selectedRun.outputTokens }}</dd></div>
            <div><dt>估算成本</dt><dd>{{ selectedRun.estimatedCost }} {{ selectedRun.currency }}</dd></div>
            <div><dt>发生时间</dt><dd>{{ formatTime(selectedRun.occurredAt) }}</dd></div>
          </dl>

          <ol class="audit-stage-strip" data-testid="audit-stage-strip" aria-label="运行审计阶段">
            <li v-for="(stage, index) in processStages" :key="stage.key" :class="[stage.state, { 'business-success': stage.businessSuccess }]">
              <span class="stage-index" aria-hidden="true">{{ index + 1 }}</span>
              <span><strong>{{ stage.label }}</strong><small>{{ stageSummaryLabel(stage) }}</small></span>
            </li>
          </ol>

          <section class="hash-chain" aria-labelledby="hash-title">
            <header><SafetyCertificateOutlined /><div><h3 id="hash-title">脱敏 hash 链</h3><p>只校验事件完整性，不展示消息、工具参数或引用正文</p></div></header>
            <code>{{ selectedRun.chainHash }}</code>
          </section>

          <section class="timeline-panel" aria-labelledby="timeline-title">
            <header><h3 id="timeline-title">运行时间线</h3><span>{{ detailSteps.length }} 个步骤</span></header>
            <ol class="audit-timeline">
              <li v-for="step in detailSteps" :key="step.id" :class="[stepStateClass(step), `stage-${stepStageKey(step.type)}`]">
                <span class="timeline-node"><CheckCircleFilled v-if="stepStateClass(step) === 'done'" /><ClockCircleFilled v-else /></span>
                <div class="timeline-title"><strong>{{ stepDisplayLabel(step.type, step.label) }}</strong><time>{{ formatTime(step.occurredAt) }}</time></div>
                <span class="timeline-status">{{ step.status }}</span>
                <dl v-if="viewMode === '技术视图' && visibleStepMetadata(step.metadata).length">
                  <div v-for="entry in visibleStepMetadata(step.metadata)" :key="entry.key"><dt>{{ entry.label }}</dt><dd>{{ entry.value }}</dd></div>
                </dl>
              </li>
              <li v-if="!detailSteps.length" class="empty-timeline">暂无可见步骤，运行摘要仍可用于审计核验。</li>
            </ol>
          </section>

          <section v-if="store.auditDetail?.retrievals.length" class="retrieval-panel" aria-labelledby="retrieval-title">
            <h3 id="retrieval-title">授权检索摘要</h3>
            <dl v-for="trace in store.auditDetail.retrievals" :key="trace.id">
              <div><dt>索引 / 版本</dt><dd>{{ trace.indexCode }} / {{ trace.indexVersion }}</dd></div>
              <div><dt>ACL 裁剪</dt><dd>{{ trace.aclPreFilterCount }} → {{ trace.aclPostFilterCount }}</dd></div>
              <div><dt>返回数量</dt><dd>{{ trace.returnedCount }}</dd></div>
              <div><dt>耗时</dt><dd>{{ trace.latencyMs }} ms</dd></div>
            </dl>
          </section>

          <section
            v-if="viewMode === '技术视图' && linkedArtifactCount"
            class="linked-artifacts"
            data-testid="audit-linked-artifacts"
            aria-labelledby="linked-artifacts-title"
          >
            <header><h3 id="linked-artifacts-title">关联审计制品</h3><span>{{ linkedArtifactCount }} 条脱敏元数据</span></header>
            <div class="artifact-groups">
              <section v-if="store.auditDetail?.tools.length" class="artifact-group">
                <h4>工具调用（{{ store.auditDetail.tools.length }}）</h4>
                <dl v-for="tool in store.auditDetail.tools" :key="tool.id">
                  <div><dt>工具 / 版本</dt><dd>{{ tool.toolName }} / {{ tool.toolVersion }}</dd></div>
                  <div><dt>授权决策</dt><dd>{{ tool.authorizationDecision }}</dd></div>
                  <div><dt>状态</dt><dd>{{ tool.state }}{{ tool.errorCode ? ` / ${tool.errorCode}` : '' }}</dd></div>
                </dl>
              </section>
              <section v-if="store.auditDetail?.citations.length" class="artifact-group">
                <h4>授权引用（{{ store.auditDetail.citations.length }}）</h4>
                <dl v-for="citation in store.auditDetail.citations" :key="citation.id">
                  <div><dt>类型 / 顺序</dt><dd>{{ citation.citationType }} / #{{ citation.rank }}</dd></div>
                  <div><dt>来源版本</dt><dd>{{ citation.documentVersionId ?? citation.metricId ?? '授权规则来源' }}</dd></div>
                  <div><dt>内容 Hash</dt><dd>{{ citation.contentHash }}</dd></div>
                </dl>
              </section>
              <section v-if="store.auditDetail?.proposals.length" class="artifact-group">
                <h4>变更提案（{{ store.auditDetail.proposals.length }}）</h4>
                <dl v-for="proposal in store.auditDetail.proposals" :key="proposal.id">
                  <div><dt>提案 / 动作</dt><dd>{{ proposal.id }} / {{ proposal.actionType }}</dd></div>
                  <div><dt>审批进度</dt><dd>{{ proposal.approvedCount }} / {{ proposal.requiredApprovalCount }} · {{ proposal.state }}</dd></div>
                  <div><dt>快照 Hash</dt><dd>{{ proposal.businessSnapshotHash }}</dd></div>
                </dl>
              </section>
              <section v-if="store.auditDetail?.approvals.length" class="artifact-group">
                <h4>人工审批（{{ store.auditDetail.approvals.length }}）</h4>
                <dl v-for="approval in store.auditDetail.approvals" :key="`${approval.proposalId}-${approval.proposalVersion}-${approval.createdAt}`">
                  <div><dt>提案 / 版本</dt><dd>{{ approval.proposalId }} / v{{ approval.proposalVersion }}</dd></div>
                  <div><dt>决策</dt><dd>{{ approval.decision }}</dd></div>
                  <div><dt>审批快照 Hash</dt><dd>{{ approval.businessSnapshotHash }}</dd></div>
                </dl>
              </section>
              <section v-if="store.auditDetail?.executions.length" class="artifact-group">
                <h4>受控执行（{{ store.auditDetail.executions.length }}）</h4>
                <dl v-for="execution in store.auditDetail.executions" :key="execution.id">
                  <div><dt>执行 / 提案</dt><dd>{{ execution.id }} / {{ execution.proposalId }}</dd></div>
                  <div><dt>处理器 / 状态</dt><dd>{{ execution.handlerName }} / {{ execution.state }}</dd></div>
                  <div><dt>结果类型</dt><dd>{{ execution.resultResourceType ?? execution.errorCode ?? '待执行' }}</dd></div>
                </dl>
              </section>
              <section v-if="store.auditDetail?.usage.length" class="artifact-group">
                <h4>Token / 成本（{{ store.auditDetail.usage.length }}）</h4>
                <dl v-for="usage in store.auditDetail.usage" :key="`${usage.requestSequence}-${usage.attempt}-${usage.occurredAt}`">
                  <div><dt>Provider / 模型</dt><dd>{{ usage.providerCode }} / {{ usage.modelName }}</dd></div>
                  <div><dt>Token</dt><dd>{{ usage.inputTokens }} + {{ usage.outputTokens }}</dd></div>
                  <div><dt>成本</dt><dd>{{ usage.costAmount }} {{ usage.currency }}</dd></div>
                </dl>
              </section>
              <section v-if="store.auditDetail?.hashChain.length" class="artifact-group">
                <h4>Hash 链事件（{{ store.auditDetail.hashChain.length }}）</h4>
                <dl v-for="event in store.auditDetail.hashChain" :key="event.id">
                  <div><dt>聚合 / 序号</dt><dd>{{ event.aggregatePublicId }} / #{{ event.sequence }}</dd></div>
                  <div><dt>事件 / 算法</dt><dd>{{ event.eventType }} / {{ event.integrityAlgorithm }}</dd></div>
                  <div><dt>事件 Hash</dt><dd>{{ event.eventHash }}</dd></div>
                </dl>
              </section>
            </div>
          </section>

          <div class="audit-content-actions" :class="{ unavailable: contentScopeUnavailable }">
            <p v-if="contentScopeUnavailable"><LockOutlined />该能力运行不提供审计正文读取：无法安全重建底层正文授权范围。</p>
            <p v-else><LockOutlined />普通审计仅显示脱敏元数据；正文读取属于 break-glass 操作。</p>
            <button v-if="canRequestContent" type="button" @click="contentOpen = true">读取审计正文</button>
            <span v-else-if="contentScopeUnavailable" class="content-permission-note">安全范围不可用</span>
            <span v-else class="content-permission-note">无正文读取权限</span>
          </div>
          <div v-if="canRequestContent && auditContent" class="ai-audit-content">
            <header><strong>临时授权正文</strong><span>切换运行、撤权或离开页面后立即清除</span></header>
            <article v-for="item in auditContent.messages" :key="item.id"><strong>{{ roleLabel(item.role) }} · {{ item.classification }}</strong><pre>{{ item.content }}</pre></article>
          </div>
        </template>
        <p v-else class="empty-state">请选择一条运行记录查看审计详情</p>
      </main>

      <aside class="audit-panel metrics-rail" data-testid="audit-metrics-rail" aria-label="运行指标与成本">
        <header class="metrics-heading"><div><h2>运行指标</h2><p>所选运行的可观测摘要</p></div><button type="button" :disabled="store.costsLoading" :aria-busy="store.costsLoading" @click="loadCosts"><DollarCircleOutlined />成本明细</button></header>
        <div class="metric-grid">
          <article><span class="metric-icon duration"><ClockCircleOutlined /></span><p><small>总耗时</small><strong>{{ selectedRun ? `${selectedRun.durationMs} ms` : '—' }}</strong></p></article>
          <article><span class="metric-icon token"><ThunderboltOutlined /></span><p><small>Token / 成本</small><strong>{{ totalTokens.toLocaleString() }} / {{ selectedRun?.estimatedCost ?? 0 }}</strong></p></article>
          <article><span class="metric-icon citation"><FileSearchOutlined /></span><p><small>授权来源</small><strong>{{ selectedRun?.citationCount ?? 0 }} 条</strong></p></article>
          <article><span class="metric-icon chain"><SafetyCertificateOutlined /></span><p><small>链完整性</small><strong>{{ selectedRun?.chainHash ? '可校验' : '待校验' }}</strong></p></article>
        </div>

        <section class="provider-card">
          <h3>模型与策略</h3>
          <dl><div><dt>模型别名</dt><dd>{{ selectedRun?.modelAlias ?? '—' }}</dd></div><div><dt>提示词版本</dt><dd>{{ selectedRun?.promptVersion ?? '—' }}</dd></div><div><dt>运行状态</dt><dd :class="selectedRun ? runStateClass(selectedRun.state) : ''">{{ selectedRun ? runStateLabel(selectedRun.state) : '—' }}</dd></div></dl>
        </section>

        <section
          class="evidence-workbench"
          data-testid="audit-evidence-workbench"
          aria-labelledby="evidence-workbench-title"
        >
          <header>
            <div><h3 id="evidence-workbench-title">审计证据工作台</h3><p>仅展示可核验脱敏元数据</p></div>
            <span>7 类事实</span>
          </header>
          <div class="evidence-grid">
            <article
              v-for="group in evidenceGroups"
              :key="group.key"
              class="audit-evidence-group"
              :class="{ missing: group.missing }"
              :data-evidence-kind="group.key"
            >
              <header><h4>{{ group.label }}</h4><span>{{ group.badge }}</span></header>
              <p v-if="group.missing">暂无可核验记录<small v-if="group.secondary">{{ group.secondary }}</small></p>
              <template v-else>
                <strong :title="group.primary">{{ group.primary }}</strong>
                <small :title="group.secondary">{{ group.secondary }}</small>
              </template>
            </article>
          </div>
        </section>

        <AiSafetyState v-if="store.costsError" :message="store.costsError" tone="danger" />
        <section v-if="store.costs.length" class="cost-list" aria-label="AI 成本明细">
          <h3>成本明细</h3>
          <article v-for="cost in store.costs" :key="cost.id"><div><strong>{{ capabilityLabel(cost.capability) }}</strong><small>{{ cost.providerAlias }}</small></div><p><b>{{ cost.inputTokens + cost.outputTokens }}</b> Tokens<em>{{ cost.estimatedCost }} {{ cost.currency }}</em></p></article>
        </section>

        <p class="audit-safety-note"><InfoCircleFilled /><span v-if="contentScopeUnavailable">当前能力运行不提供正文读取；仅保留可核验的脱敏元数据。</span><span v-else>审计页不回放消息、工具参数或引用正文。正文只在额外授权和二次认证后短暂显示。</span></p>
      </aside>
    </div>

    <a-modal v-if="contentFormMounted" v-model:open="contentOpen" title="读取审计正文" ok-text="确认读取" cancel-text="取消"
      :ok-button-props="{ disabled: !canSubmitContentRead }" :confirm-loading="contentReading"
      @ok="readContent" @cancel="cancelContentRead">
      <AiSafetyState message="该操作属于 break-glass 读取，将记录理由并写入安全审计。" tone="warning" />
      <a-textarea v-model:value="contentReason" aria-label="审计正文读取理由" :rows="3" :maxlength="500" placeholder="至少 10 个字符，说明业务或安全复核目的" />
      <a-input-password v-model:value="currentPassword" aria-label="当前密码" autocomplete="current-password" placeholder="重新输入当前密码" />
      <p class="content-form-hint" :class="{ valid: canSubmitContentRead }">理由至少 10 个字符，并重新输入当前密码</p>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import {
  CheckCircleFilled, ClockCircleFilled, ClockCircleOutlined, DollarCircleOutlined, FileSearchOutlined,
  InfoCircleFilled, LeftOutlined, LockOutlined, ReloadOutlined, RightOutlined, SafetyCertificateOutlined,
  SearchOutlined, ThunderboltOutlined,
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import { isAiSurfaceEnabled } from '../api/ai-client'
import AiSafetyState from '../components/ai/AiSafetyState.vue'
import { useAiApprovalStore } from '../stores/aiApproval'
import { useAuthStore } from '../stores/auth'
import type { AiAuditRunContent, AiAuditRunQuery, AiAuditStep, AiRunState } from '../types/ai'

const store = useAiApprovalStore()
const auth = useAuthStore()
const enabled = isAiSurfaceEnabled()
const viewMode = ref<'业务视图' | '技术视图'>('业务视图')
const detailHeadingRef = ref<HTMLElement | null>(null)
const contentOpen = ref(false)
const contentFormMounted = ref(false)
const contentReason = ref('')
const currentPassword = ref('')
const auditContent = ref<AiAuditRunContent | null>(null)
const auditLoading = ref(false)
const detailLoading = ref(false)
const contentReading = ref(false)
const loadError = ref('')
const capabilityFilter = ref('')
const stateFilter = ref('')
const providerFilter = ref('')
const fromFilter = ref('')
const toFilter = ref('')
const capabilityOptions = ['ASSISTANT', 'KNOWLEDGE', 'DASHBOARD', 'REPAIR', 'NOTICE', 'RISK', 'EVALUATION'].map((value) => ({ label: capabilityLabel(value), value }))
const stateOptions = ['ACCEPTED', 'QUEUED', 'RUNNING', 'STREAMING', 'SUCCEEDED', 'DEGRADED', 'FAILED', 'TIMED_OUT', 'CANCELLED', 'NEEDS_RECONCILIATION'].map((value) => ({ label: value, value }))
const providerOptions = [{ label: 'Fake', value: 'fake' }, { label: 'Spring AI', value: 'spring-ai' }, { label: 'DeepSeek', value: 'deepseek' }]
const selectedRun = computed(() => store.auditDetail?.run ?? store.audits.find((item) => item.id === store.selectedAuditId) ?? store.audits[0] ?? null)
const contentScopeUnavailable = computed(() => ['KNOWLEDGE', 'RISK', 'EVALUATION'].includes(selectedRun.value?.capability.trim().toUpperCase() ?? ''))
const canReadContent = computed(() => auth.hasPermission('ai:audit:content:read'))
const canRequestContent = computed(() => canReadContent.value && !contentScopeUnavailable.value)
const canSubmitContentRead = computed(() => canRequestContent.value && contentReason.value.trim().length >= 10 && Boolean(currentPassword.value))
const detailSteps = computed(() => store.auditDetail?.steps ?? selectedRun.value?.steps ?? [])
const linkedArtifactCount = computed(() => {
  const detail = store.auditDetail
  return detail
    ? detail.tools.length + detail.citations.length + detail.proposals.length + detail.approvals.length
      + detail.executions.length + detail.usage.length + detail.hashChain.length
    : 0
})
const totalTokens = computed(() => selectedRun.value ? selectedRun.value.inputTokens + selectedRun.value.outputTokens : 0)
const auditPageCount = computed(() => Math.max(1, Math.ceil((store.auditTotal || store.audits.length) / Math.max(1, store.auditPageSize))))
const stageDefinitions = [
  { key: 'model', label: '模型处理' },
  { key: 'retrieval', label: '授权检索' },
  { key: 'tool', label: '工具调用' },
  { key: 'proposal', label: '提案生成' },
  { key: 'approval', label: '人工审批' },
  { key: 'execution', label: '受控执行' },
  { key: 'result', label: '运行结果' },
] as const
type AuditStageKey = typeof stageDefinitions[number]['key']
type AuditStageState = 'idle' | 'pending' | 'current' | 'warning' | 'failure' | 'skipped' | 'done'
const processStages = computed(() => stageDefinitions.map((definition) => {
  const steps = detailSteps.value.filter((step) => stepStageKey(step.type) === definition.key)
  const states = steps.map(auditStepState)
  const state = aggregateStageState(states)
  return {
    ...definition,
    count: steps.length,
    state,
    businessSuccess: (definition.key === 'execution' || definition.key === 'result') && state === 'done',
  }
}))
const evidenceGroups = computed(() => {
  const detail = store.auditDetail
  const run = selectedRun.value
  const usage = detail?.usage[0]
  const retrieval = detail?.retrievals[0]
  const tool = detail?.tools[0]
  const citation = detail?.citations[0]
  const approval = detail?.approvals[0]
  const proposal = detail?.proposals[0]
  const execution = detail?.executions[0]
  const hashEvents = detail?.hashChain ?? []
  const hashEvent = hashEvents[hashEvents.length - 1]

  return [
    {
      key: 'model', label: '模型', missing: !run,
      badge: usage ? `${detail?.usage.length ?? 0} 次调用` : run ? '运行摘要' : '缺失',
      primary: run?.modelAlias ?? '',
      secondary: usage
        ? `${usage.providerCode} · ${usage.inputTokens + usage.outputTokens} Tokens`
        : run ? `${run.inputTokens + run.outputTokens} Tokens · 调用明细未返回` : '',
    },
    {
      key: 'retrieval', label: '检索', missing: !retrieval,
      badge: retrieval ? `${detail?.retrievals.length ?? 0} 条` : '缺失',
      primary: retrieval ? `${retrieval.retrievalMode} · ${retrieval.state}` : '',
      secondary: retrieval ? `ACL ${retrieval.aclPreFilterCount}→${retrieval.aclPostFilterCount} · 返回 ${retrieval.returnedCount}` : '',
    },
    {
      key: 'tool', label: '工具', missing: !tool,
      badge: tool ? `${detail?.tools.length ?? 0} 次` : '缺失',
      primary: tool?.toolName ?? '',
      secondary: tool ? `${tool.authorizationDecision} · ${tool.state}` : '',
    },
    {
      key: 'citation', label: '引用', missing: !citation,
      badge: citation ? `${detail?.citations.length ?? 0} 条` : '缺失',
      primary: citation ? citation.documentVersionId ?? citation.metricId ?? '授权规则来源' : '',
      secondary: citation
        ? `${citation.citationType} · #${citation.rank}`
        : run?.citationCount ? `运行摘要计数 ${run.citationCount} 条` : '',
    },
    {
      key: 'approval', label: '人工审批', missing: !approval && !proposal,
      badge: approval ? `${detail?.approvals.length ?? 0} 个决定` : proposal ? '待决定' : '缺失',
      primary: approval?.decision ?? (proposal ? '暂无审批决定' : ''),
      secondary: approval
        ? `${approval.proposalId} · v${approval.proposalVersion}`
        : proposal ? `${proposal.id} · ${proposal.state}` : '',
    },
    {
      key: 'execution', label: '受控执行', missing: !execution,
      badge: execution ? `${detail?.executions.length ?? 0} 条` : '缺失',
      primary: execution ? `${execution.id} · ${execution.state}` : '',
      secondary: execution ? execution.resultResourceType ?? execution.errorCode ?? '结果资源未记录' : '',
    },
    {
      key: 'result', label: '运行结果', missing: !run,
      badge: hashEvent ? `${hashEvents.length} 个链事件` : run ? '运行摘要' : '缺失',
      primary: run ? runStateLabel(run.state) : '',
      secondary: hashEvent?.eventHash ?? run?.chainHash ?? '',
    },
  ]
})
let active = true
let auditSelectionEpoch = 0
let contentRequestEpoch = 0
const auditContentMessageKey = 'ai-audit-content-read'

const metadataLabels: Record<string, string> = {
  sequence: '序号', requestHash: '请求 Hash', resultHash: '结果 Hash', proposalId: '提案 ID',
  executionId: '执行 ID', toolName: '工具名称', toolVersion: '工具版本', latencyMs: '耗时',
  returnedCount: '返回数量', aclPreFilterCount: 'ACL 前', aclPostFilterCount: 'ACL 后',
  indexVersion: '索引版本', retrievalMode: '检索模式', retrievalPolicyVersion: '检索策略',
  embeddingModelVersion: '向量模型', approvalState: '审批状态', state: '状态',
}

function capabilityLabel(value: string) {
  const normalized = value.toUpperCase()
  return ({ ASSISTANT: '智能问答', KNOWLEDGE: '知识检索', DASHBOARD: '指标分析', REPAIR: '维修分诊', NOTICE: '公告起草', RISK: '风险解释', EVALUATION: '离线评测', 'REPAIR.TRIAGE': '维修智能分诊' } as Record<string, string>)[normalized] ?? value
}
function runStateLabel(value: AiRunState) {
  return ({ idle: '未开始', accepted: '已受理', queued: '排队中', running: '运行中', streaming: '生成中', succeeded: '已成功', degraded: '已降级', failed: '失败', timed_out: '已超时', cancelled: '已取消', needs_reconciliation: '需要人工对账' })[value]
}
const isSuccessful = (value: AiRunState) => value === 'succeeded'
const runStateClass = (value: AiRunState) => value === 'succeeded' ? 'success' : ['failed', 'timed_out', 'cancelled'].includes(value) ? 'failure' : ['degraded', 'needs_reconciliation'].includes(value) ? 'warning' : 'pending'
function auditStepState(step: Pick<AiAuditStep, 'type' | 'status'>): AuditStageState {
  const status = step.status.trim().toUpperCase()
  const type = step.type.trim().toUpperCase()
  if (/\b(FAILED|FAILURE|ERROR|REJECTED|DENIED)\b/.test(status) || /FAILED|REJECTED/.test(type)) return 'failure'
  if (/\b(EXPIRED|STALE|DEGRADED|PARTIAL|NEEDS_REVIEW|NEEDS_RECONCILIATION|UNKNOWN)\b/.test(status)) return 'warning'
  if (/\b(SKIPPED|CANCELLED)\b/.test(status)) return 'skipped'
  if (/\b(PENDING_APPROVAL|RUNNING|EXECUTING|STREAMING|IN_PROGRESS)\b/.test(status)) return 'current'
  if (/\b(PENDING|QUEUED|ACCEPTED|DRAFT)\b/.test(status)) return 'pending'
  if (/\b(SUCCEEDED|SUCCESS|COMPLETED|APPROVED|RECORDED)\b/.test(status)) return 'done'
  if (/COMPLETED|SUCCEEDED|APPROVED/.test(type)) return 'done'
  return 'pending'
}

function aggregateStageState(states: AuditStageState[]): AuditStageState {
  if (!states.length) return 'idle'
  return (['failure', 'warning', 'current', 'pending', 'skipped', 'done'] as const)
    .find((state) => states.includes(state)) ?? 'pending'
}

function stepStateClass(step: Pick<AiAuditStep, 'type' | 'status'>) {
  const state = auditStepState(step)
  return state === 'failure' ? 'failed' : state
}
const formatTime = (value: string) => value ? value.replace('T', ' ').slice(0, 16) : '—'

function stepStageKey(type: string): AuditStageKey {
  const normalized = type.toLowerCase()
  if (normalized.includes('retrieval') || normalized.includes('citation')) return 'retrieval'
  if (normalized.includes('tool')) return 'tool'
  if (normalized.includes('proposal')) return 'proposal'
  if (normalized.includes('approval')) return 'approval'
  if (normalized.includes('execution') || normalized.includes('reconfirm')) return 'execution'
  if (normalized.includes('completed') && normalized.includes('run') || normalized.includes('result')) return 'result'
  return 'model'
}

function stepDisplayLabel(type: string, fallback: string) {
  const key = stepStageKey(type)
  const stage = stageDefinitions.find((item) => item.key === key)
  const normalized = type.toLowerCase()
  if (normalized.includes('accepted')) return '运行受理'
  if (normalized.includes('queued')) return '进入队列'
  if (normalized.includes('started')) return '开始运行'
  if (normalized.includes('completed')) return key === 'result' ? '运行完成' : `${stage?.label ?? fallback}完成`
  if (normalized.includes('failed')) return `${stage?.label ?? fallback}失败`
  if (normalized.includes('created')) return stage?.label ?? fallback
  if (normalized.includes('approved')) return '人工审批通过'
  if (normalized.includes('rejected')) return '人工审批拒绝'
  if (normalized.includes('succeeded')) return `${stage?.label ?? fallback}成功`
  return stage?.label ?? fallback
}

function stageSummaryLabel(stage: { state: string; count: number }) {
  const statusLabel = ({
    done: '已完成', failure: '失败', warning: '需关注', current: '进行中', skipped: '已跳过', pending: '待处理', idle: '待处理',
  } as Record<string, string>)[stage.state] ?? '待处理'
  return `${statusLabel} · ${stage.count ? `${stage.count} 条记录` : '暂无记录'}`
}

function isCompactViewport() {
  return typeof window !== 'undefined'
    && typeof window.matchMedia === 'function'
    && window.matchMedia('(max-width: 768px)').matches
}

function visibleStepMetadata(metadata: AiAuditStep['metadata']) {
  return Object.entries(metadata)
    .filter(([key, value]) => key in metadataLabels && ['string', 'number', 'boolean'].includes(typeof value))
    .map(([key, value]) => ({ key, label: metadataLabels[key]!, value: String(value) }))
}

function roleLabel(role: string) {
  return ({ USER: '用户', ASSISTANT: 'AI 助手', SYSTEM: '系统', TOOL: '工具' } as Record<string, string>)[role.toUpperCase()] ?? role
}

function invalidateContentReadContext() {
  contentRequestEpoch += 1
  auditContent.value = null
  contentOpen.value = false
  contentReason.value = ''
  currentPassword.value = ''
  contentReading.value = false
  if (typeof message.destroy === 'function') message.destroy(auditContentMessageKey)
}
watch(canRequestContent, async (allowed) => {
  if (allowed) { contentFormMounted.value = true; return }
  invalidateContentReadContext()
  await nextTick()
  await nextTick()
  if (!canRequestContent.value) contentFormMounted.value = false
}, { immediate: true, flush: 'sync' })
watch(() => store.selectedAuditId, invalidateContentReadContext, { flush: 'sync' })

async function selectAudit(id: string, options: { focusDetail?: boolean } = {}) {
  const selectionEpoch = ++auditSelectionEpoch
  invalidateContentReadContext()
  detailLoading.value = true
  try {
    await store.selectAudit(id)
    if (!options.focusDetail || !isCompactViewport()) return
    await nextTick()
    if (
      selectionEpoch !== auditSelectionEpoch
      || !active
      || store.selectedAuditId !== id
      || store.auditDetail?.run.id !== id
    ) return
    detailHeadingRef.value?.scrollIntoView({ block: 'start', inline: 'nearest' })
    detailHeadingRef.value?.focus()
  }
  catch (error) {
    const detailError = error instanceof Error ? error.message : '审计详情加载失败'
    loadError.value = detailError
    message.error(detailError)
  } finally { detailLoading.value = false }
}
async function loadCosts() {
  try {
    if (await store.loadCosts() && !store.costs.length) message.info('当前没有成本明细')
  } catch (error) { message.error(error instanceof Error ? error.message : '成本明细加载失败') }
}
function auditQuery(page: number): AiAuditRunQuery {
  return {
    page,
    pageSize: store.auditPageSize,
    capability: capabilityFilter.value as AiAuditRunQuery['capability'] || undefined,
    state: stateFilter.value as AiAuditRunQuery['state'] || undefined,
    provider: providerFilter.value || undefined,
    from: fromFilter.value ? new Date(fromFilter.value).toISOString() : undefined,
    to: toFilter.value ? new Date(toFilter.value).toISOString() : undefined,
  }
}
async function loadAuditPage(page: number) {
  auditLoading.value = true
  loadError.value = ''
  store.error = null
  const recordsBeforeLoad = store.audits
  try {
    await store.loadAudits(auditQuery(page))
    if (store.error) throw new Error(store.error)
    const first = store.audits[0]
    if (first) await selectAudit(first.id)
    else if (store.audits !== recordsBeforeLoad) store.$patch({ selectedAuditId: '', auditDetail: null })
  } finally { auditLoading.value = false }
}
async function applyFilters() {
  if (fromFilter.value && toFilter.value && fromFilter.value > toFilter.value) { message.error('开始时间不能晚于结束时间'); return }
  try {
    await loadAuditPage(1)
  } catch (error) { message.error(error instanceof Error ? error.message : '审计筛选失败') }
}
async function changeAuditPage(page: number) {
  if (page < 1 || page > auditPageCount.value || page === store.auditPage) return
  try { await loadAuditPage(page) }
  catch (error) { message.error(error instanceof Error ? error.message : '审计分页加载失败') }
}
async function readContent() {
  if (!canRequestContent.value) { invalidateContentReadContext(); return }
  const requestedAuditId = store.selectedAuditId
  const requestEpoch = ++contentRequestEpoch
  contentReading.value = true
  try {
    const content = await store.loadAuditContent(contentReason.value.trim(), currentPassword.value)
    if (!content || content.runId !== requestedAuditId || store.auditDetail?.run.id !== requestedAuditId || requestEpoch !== contentRequestEpoch || !active || !canRequestContent.value || store.selectedAuditId !== requestedAuditId) return
    auditContent.value = content
    contentOpen.value = false
    contentReason.value = ''
    message.success({ key: auditContentMessageKey, content: `运行 ${requestedAuditId}：审计正文已按授权读取` })
  } catch (error) {
    if (requestEpoch !== contentRequestEpoch || !active || store.selectedAuditId !== requestedAuditId) return
    const detail = error instanceof Error ? error.message : '审计正文读取失败'
    message.error({ key: auditContentMessageKey, content: `运行 ${requestedAuditId}：${detail}` })
  } finally {
    if (requestEpoch === contentRequestEpoch) {
      currentPassword.value = ''
      contentReading.value = false
    }
  }
}
function cancelContentRead() { invalidateContentReadContext() }
async function initialize() {
  auditLoading.value = true
  loadError.value = ''
  try {
    await store.loadAudits(auditQuery(1), { reconcileSelection: false })
    const selected = store.audits.find((item) => item.id === store.selectedAuditId)
    const next = selected ?? store.audits[0]
    if (next && store.auditDetail?.run.id !== next.id) await selectAudit(next.id)
  } catch (error) {
    loadError.value = error instanceof Error ? error.message : '审计列表加载失败'
  } finally {
    auditLoading.value = false
  }
}
onBeforeUnmount(() => { active = false; invalidateContentReadContext() })
onMounted(() => { if (enabled) void initialize() })
</script>

<style scoped>
.audit-page { display: grid; gap: 12px; color: #374151; font-family: var(--font-family-ui); }
.audit-breadcrumb { color: var(--text-muted); font-size: 13px; }
.audit-breadcrumb span { margin: 0 8px; color: #c2cbd8; }
.governance-tabs { display: flex; gap: 28px; min-height: 44px; border-bottom: 1px solid #e7edf6; }
.governance-tabs a { position: relative; display: flex; align-items: center; min-height: 44px; padding: 0 14px; color: var(--text-muted); font-size: 14px; text-decoration: none; }
.governance-tabs a.active { color: #2563eb; font-weight: 600; }
.governance-tabs a.active::after { position: absolute; right: 0; bottom: -1px; left: 0; height: 2px; background: #2563eb; content: ""; }
.audit-filter-bar { display: grid; grid-template-columns: repeat(3,minmax(120px,1fr)) repeat(2,minmax(165px,1.15fr)) auto; gap: 9px; align-items: end; padding: 12px; border: 1px solid #e7edf6; border-radius: 8px; background: #fff; box-shadow: 0 3px 12px rgba(15,47,111,.035); }
.audit-filter-bar label { display: grid; gap: 4px; color: #64748b; font-size: 12px; }
.audit-filter-bar select,
.audit-filter-bar input { width: 100%; min-height: 44px; padding: 7px 9px; border: 1px solid #dce4ef; border-radius: 5px; color: #374151; background: #fff; font: inherit; font-size: 12px; }
.audit-filter-bar button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: 44px; padding: 0 15px; border: 1px solid #2563eb; border-radius: 5px; color: #fff; background: #2563eb; cursor: pointer; }
.audit-filter-bar button:disabled { border-color: #94a3b8; background: #94a3b8; cursor: wait; }
.audit-workbench { display: grid; grid-template-columns: minmax(250px,.75fr) minmax(440px,1.4fr) minmax(280px,.85fr); gap: 12px; align-items: start; }
.audit-panel { min-width: 0; border: 1px solid #e7edf6; border-radius: 8px; background: #fff; box-shadow: 0 3px 12px rgba(15,47,111,.045); }
.run-rail { display: flex; flex-direction: column; overflow: hidden; }
.rail-heading,
.detail-heading,
.metrics-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.rail-heading { padding: 14px 12px 10px; }
.rail-heading h2,
.detail-heading h2,
.metrics-heading h2 { margin: 0; color: #111827; font-size: 18px; line-height: 1.3; }
.rail-heading p,
.detail-heading p,
.metrics-heading p { margin: 3px 0 0; color: #64748b; font-size: 12px; }
.view-toggle { display: flex; padding: 2px; border: 1px solid #dce4ef; border-radius: 5px; background: #f8fafc; }
.view-toggle button { min-height: 44px; padding: 0 12px; border: 0; border-radius: 3px; color: #64748b; background: transparent; font-size: 12px; font-weight: 600; }
.view-toggle button.active { color: #2563eb; background: #fff; box-shadow: 0 1px 3px rgba(15,47,111,.1); }
.run-list { min-height: 0; border-top: 1px solid #edf2f8; overflow-y: auto; }
.ai-audit-run { border-top: 1px solid #edf2f8; }
.ai-audit-run:first-child { border-top: 0; }
.ai-audit-run.selected { margin: 6px; border: 1px solid #3b82f6; border-radius: 7px; background: #f7faff; }
.run-select { position: relative; display: grid; grid-template-columns: 23px minmax(0,1fr); gap: 7px; width: 100%; min-height: 86px; padding: 11px; border: 0; color: inherit; background: transparent; text-align: left; cursor: pointer; }
.run-state { display: grid; place-items: center; margin-top: 2px; font-size: 16px; }
.success { color: var(--success-strong) !important; }
.failure { color: #dc2626 !important; }
.warning { color: #d97706 !important; }
.pending { color: #2563eb !important; }
.run-title { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 5px; }
.run-title strong { color: #172554; font-size: 14px; line-height: 1.35; }
.run-title i { padding: 3px 6px; border-radius: 4px; background: #f1f5f9; font-size: 12px; font-style: normal; }
.run-copy > small { display: block; margin-top: 4px; color: #64748b; font-size: 12px; overflow-wrap: anywhere; }
.run-meta { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 4px 8px; margin-top: 7px; color: #64748b; font-size: 12px; }
.run-technical { display: block; margin-top: 5px; color: #31517d; font-size: 12px; overflow-wrap: anywhere; }
.view-detail-text { grid-column: 2; justify-self: end; margin-top: 2px; color: #2563eb; font-size: 12px; }
.empty-state { padding: 36px 14px; color: #64748b; font-size: 12px; text-align: center; }
.run-pagination { display: flex; align-items: center; justify-content: space-between; gap: 6px; min-height: 58px; padding: 7px 11px; border-top: 1px solid #edf2f8; color: #64748b; font-size: 12px; }
.run-pagination div { display: flex; gap: 4px; }
.run-pagination button { display: grid; place-items: center; width: 44px; height: 44px; border: 1px solid #dce4ef; border-radius: 4px; color: #64748b; background: #fff; }
.run-pagination button.active { border-color: #2563eb; color: #fff; background: #2563eb; }
.run-pagination button:disabled:not(.active) { color: #cbd5e1; background: #f8fafc; }
.detail-panel { padding: 14px; overflow-y: auto; }
.detail-heading > div > span { display: flex; align-items: center; gap: 8px; }
.detail-heading i { padding: 3px 6px; border-radius: 4px; background: #ecfdf5; font-size: 12px; font-style: normal; }
.detail-heading h2:focus { outline: 2px solid #2563eb; outline-offset: 3px; border-radius: 3px; }
.refresh-button,
.metrics-heading button { display: inline-flex; align-items: center; justify-content: center; gap: 5px; min-height: 44px; padding: 0 12px; border: 1px solid #bfdbfe; border-radius: 5px; color: #2563eb; background: #fff; font-size: 12px; cursor: pointer; }
.run-facts { display: grid; grid-template-columns: repeat(4,1fr); margin: 12px 0 0; border: 1px solid #e7edf6; border-radius: 6px; overflow: hidden; }
.run-facts div { min-width: 0; padding: 9px; border-top: 1px solid #e7edf6; border-left: 1px solid #e7edf6; }
.run-facts div:nth-child(-n+4) { border-top: 0; }
.run-facts div:nth-child(4n+1) { border-left: 0; }
.run-facts dt { color: #64748b; font-size: 12px; }
.run-facts dd { margin: 4px 0 0; color: #172554; font-size: 12px; overflow-wrap: anywhere; }
.audit-stage-strip { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; margin: 10px 0 0; padding: 12px; border: 1px solid #e7edf6; border-radius: 6px; list-style: none; background: #fbfdff; }
.audit-stage-strip li { display: grid; grid-template-columns: 30px minmax(0, 1fr); align-items: start; gap: 8px; min-width: 0; min-height: 78px; padding: 10px; border: 1px solid #dbe5f1; border-radius: 8px; background: #fff; }
.audit-stage-strip li.done { border-color: #bfdbfe; background: #eff6ff; }
.audit-stage-strip li.business-success { border-color: #9fdabf; background: #f0fdf4; }
.audit-stage-strip li.current { border-color: #93c5fd; background: #f7faff; box-shadow: inset 3px 0 0 #2563eb; }
.audit-stage-strip li.warning { border-color: #f4d28c; background: #fffaf0; }
.audit-stage-strip li.failure { border-color: #fecaca; background: #fff7f7; }
.audit-stage-strip li.skipped,
.audit-stage-strip li.pending,
.audit-stage-strip li.idle { border-style: dashed; background: #f8fafc; }
.stage-index { display: grid; place-items: center; width: 30px; height: 30px; border: 1px solid #cbd5e1; border-radius: 50%; color: #64748b; background: #fff; font-size: 12px; font-weight: 700; flex: 0 0 auto; }
.audit-stage-strip li.done .stage-index { border-color: #2563eb; color: #fff; background: #2563eb; }
.audit-stage-strip li.business-success .stage-index { border-color: #10b981; background: #10b981; }
.audit-stage-strip li.current .stage-index { border-color: #2563eb; color: #2563eb; background: #eff6ff; }
.audit-stage-strip li.warning .stage-index { border-color: #d97706; color: #b45309; background: #fffbeb; }
.audit-stage-strip li.failure .stage-index { border-color: #ef4444; color: #fff; background: #ef4444; }
.audit-stage-strip li.skipped .stage-index { border-style: dashed; color: #64748b; background: #f1f5f9; }
.audit-stage-strip strong,
.audit-stage-strip small { display: block; white-space: normal; }
.audit-stage-strip strong { color: #172554; font-size: 14px; line-height: 1.35; }
.audit-stage-strip small { margin-top: 4px; color: #4b5563; font-size: 12px; line-height: 1.45; }
.hash-chain { margin-top: 10px; padding: 10px; border: 1px solid #bfd4f8; border-radius: 6px; background: #f5f9ff; }
.hash-chain header { display: flex; align-items: center; gap: 8px; color: #2563eb; }
.hash-chain h3 { margin: 0; color: #172554; font-size: 14px; }
.hash-chain p { margin: 2px 0 0; color: #64748b; font-size: 12px; }
.hash-chain code { display: block; margin-top: 8px; padding: 7px; border-radius: 4px; color: #31517d; background: #fff; font: 12px/1.5 ui-monospace, SFMono-Regular, Consolas, monospace; overflow-wrap: anywhere; }
.timeline-panel,
.retrieval-panel,
.linked-artifacts { margin-top: 12px; }
.timeline-panel > header { display: flex; align-items: center; justify-content: space-between; }
.timeline-panel h3,
.retrieval-panel h3,
.provider-card h3,
.cost-list h3,
.evidence-workbench h3 { margin: 0; color: #172554; font-size: 14px; }
.timeline-panel header span { color: #64748b; font-size: 12px; }
.audit-timeline { margin: 9px 0 0; padding: 0; border: 1px solid #e7edf6; border-radius: 6px; list-style: none; overflow: hidden; }
.audit-timeline li { position: relative; display: grid; grid-template-columns: 28px minmax(100px,.75fr) auto; align-items: start; min-height: 54px; padding: 9px 10px; border-top: 1px solid #edf2f8; }
.audit-timeline li:first-child { border-top: 0; }
.timeline-node { display: grid; place-items: center; width: 18px; height: 18px; color: #94a3b8; }
.audit-timeline li.done .timeline-node { color: #2563eb; }
.audit-timeline li.stage-execution.done .timeline-node,
.audit-timeline li.stage-result.done .timeline-node { color: #10b981; }
.audit-timeline li.current .timeline-node { color: #2563eb; }
.audit-timeline li.warning .timeline-node { color: #d97706; }
.audit-timeline li.skipped .timeline-node { color: #64748b; }
.audit-timeline li.failed .timeline-node { color: #ef4444; }
.timeline-title strong,
.timeline-title time { display: block; }
.timeline-title strong { color: #172554; font-size: 14px; }
.timeline-title time { margin-top: 3px; color: #64748b; font-size: 12px; }
.timeline-status { padding: 4px 8px; border-radius: 999px; color: #475569; background: #f1f5f9; font-size: 12px; font-weight: 600; }
.audit-timeline dl { grid-column: 2 / -1; display: flex; flex-wrap: wrap; gap: 6px 12px; margin: 8px 0 0; padding: 7px; border-radius: 4px; background: #f8fafc; }
.audit-timeline dl div { display: flex; gap: 4px; font-size: 12px; }
.audit-timeline dt { color: #94a3b8; }
.audit-timeline dd { margin: 0; color: #475569; overflow-wrap: anywhere; }
.empty-timeline { display: block !important; color: #64748b; font-size: 12px; text-align: center; }
.retrieval-panel > dl { display: grid; grid-template-columns: repeat(4,1fr); margin: 8px 0 0; border: 1px solid #e7edf6; border-radius: 6px; }
.retrieval-panel dl div { min-width: 0; padding: 8px; border-left: 1px solid #e7edf6; }
.retrieval-panel dl div:first-child { border-left: 0; }
.retrieval-panel dt { color: #64748b; font-size: 12px; }
.retrieval-panel dd { margin: 3px 0 0; color: #172554; font-size: 12px; overflow-wrap: anywhere; }
.linked-artifacts > header { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.linked-artifacts h3 { margin: 0; color: #172554; font-size: 14px; }
.linked-artifacts > header span { color: #64748b; font-size: 12px; }
.artifact-groups { margin-top: 8px; border-top: 1px solid #e7edf6; }
.artifact-group { padding: 9px 0; border-bottom: 1px solid #e7edf6; }
.artifact-group h4 { margin: 0 0 6px; color: #31517d; font-size: 14px; line-height: 1.35; }
.artifact-group dl { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); margin: 0; padding: 7px 0; border-top: 1px dashed #e7edf6; }
.artifact-group dl:first-of-type { border-top: 0; }
.artifact-group dl div { min-width: 0; padding-right: 8px; }
.artifact-group dt { color: #64748b; font-size: 12px; }
.artifact-group dd { margin: 3px 0 0; color: #475569; font-size: 12px; overflow-wrap: anywhere; }
.audit-content-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 12px; padding: 9px; border: 1px solid #f4d28c; border-radius: 5px; background: #fffaf0; }
.audit-content-actions p { display: flex; align-items: center; gap: 6px; margin: 0; color: #92400e; font-size: 12px; line-height: 1.5; }
.audit-content-actions button { min-height: 44px; padding: 0 12px; border: 1px solid #d97706; border-radius: 5px; color: #b45309; background: #fff; font-size: 12px; white-space: nowrap; }
.audit-content-actions.unavailable { border-color: #bfd4f8; background: #f5f9ff; }
.audit-content-actions.unavailable p { color: #1d4ed8; }
.content-permission-note { color: #92400e; font-size: 12px; white-space: nowrap; }
.audit-content-actions.unavailable .content-permission-note { color: #1d4ed8; }
.ai-audit-content { display: grid; gap: 8px; margin-top: 10px; }
.ai-audit-content > header { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding-bottom: 7px; border-bottom: 1px solid #dce4ef; }
.ai-audit-content > header strong { color: #172554; font-size: 14px; }
.ai-audit-content > header span { color: #92400e; font-size: 12px; text-align: right; }
.ai-audit-content article { padding: 9px; border-radius: 5px; background: #f8fafc; }
.ai-audit-content strong { color: #172554; font-size: 12px; }
.ai-audit-content pre { margin: 5px 0 0; white-space: pre-wrap; overflow-wrap: anywhere; font: 12px/1.55 inherit; }
.metrics-rail { padding: 14px 11px; overflow-y: auto; }
.metrics-heading { padding: 0 3px 10px; }
.metric-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.metric-grid article { display: flex; align-items: center; gap: 8px; min-width: 0; padding: 10px; border: 1px solid #e7edf6; border-radius: 6px; }
.metric-icon { display: grid; place-items: center; width: 31px; height: 31px; border-radius: 6px; flex: 0 0 auto; }
.metric-icon.duration { color: #2563eb; background: #eff6ff; }
.metric-icon.token { color: #7c3aed; background: #f4f0ff; }
.metric-icon.citation { color: #059669; background: #ecfdf5; }
.metric-icon.chain { color: #d97706; background: #fffbeb; }
.metric-grid p { display: grid; gap: 3px; min-width: 0; margin: 0; }
.metric-grid small { color: #64748b; font-size: 12px; }
.metric-grid strong { color: #172554; font-size: 14px; overflow-wrap: anywhere; }
.provider-card,
.cost-list,
.evidence-workbench { margin-top: 10px; padding: 11px; border: 1px solid #e7edf6; border-radius: 6px; }
.provider-card dl { margin: 9px 0 0; }
.provider-card dl div { display: grid; grid-template-columns: 92px minmax(0,1fr); gap: 6px; padding: 7px 0; border-top: 1px solid #edf2f8; font-size: 12px; }
.provider-card dl div:first-child { border-top: 0; }
.provider-card dt { color: #64748b; }
.provider-card dd { margin: 0; color: #172554; overflow-wrap: anywhere; }
.evidence-workbench > header { display: flex; align-items: flex-start; justify-content: space-between; gap: 8px; }
.evidence-workbench > header p { margin: 2px 0 0; color: #64748b; font-size: 12px; }
.evidence-workbench > header > span { color: #64748b; font-size: 12px; white-space: nowrap; }
.evidence-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 6px; margin-top: 8px; }
.audit-evidence-group { min-width: 0; min-height: 56px; padding: 7px; border: 1px solid #e5ebf4; border-radius: 5px; background: #fbfdff; }
.audit-evidence-group > header { display: flex; align-items: center; justify-content: space-between; gap: 4px; }
.audit-evidence-group h4 { margin: 0; color: #31517d; font-size: 14px; }
.audit-evidence-group > header span { padding: 3px 5px; border-radius: 3px; color: #047857; background: #ecfdf5; font-size: 12px; white-space: nowrap; }
.audit-evidence-group > strong,
.audit-evidence-group > small { display: block; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.audit-evidence-group > strong { margin-top: 6px; color: #172554; font-size: 12px; }
.audit-evidence-group > small { margin-top: 3px; color: #64748b; font-size: 12px; }
.audit-evidence-group.missing { border-style: dashed; background: #f8fafc; }
.audit-evidence-group.missing > header span { color: var(--text-strong); background: #eef2f7; }
.audit-evidence-group > p { margin: 7px 0 0; color: #64748b; font-size: 12px; }
.audit-evidence-group > p small { display: block; margin-top: 3px; color: #64748b; font-size: 12px; }
.cost-list article { display: flex; justify-content: space-between; gap: 8px; padding: 9px 0; border-top: 1px solid #edf2f8; }
.cost-list article:first-of-type { margin-top: 7px; }
.cost-list strong,
.cost-list small { display: block; }
.cost-list strong { color: #172554; font-size: 14px; }
.cost-list small { margin-top: 2px; color: #64748b; font-size: 12px; }
.cost-list p { display: grid; gap: 2px; margin: 0; color: #64748b; font-size: 12px; text-align: right; }
.cost-list b { color: #475569; }
.cost-list em { color: #059669; font-style: normal; }
.audit-safety-note { display: flex; gap: 7px; margin: 10px 0 0; padding: 10px; border: 1px solid #bfd4f8; border-radius: 6px; color: #1d4ed8; background: #f5f9ff; font-size: 12px; line-height: 1.5; }
.content-form-hint { margin: 7px 0 0; color: #92400e; font-size: 12px; }
.content-form-hint.valid { color: #047857; }
.audit-page :is(button, select, input, textarea, a):focus-visible { outline: 2px solid #2563eb; outline-offset: 2px; }

@media (min-width: 1321px) {
  .audit-workbench { height: calc(100dvh - 258px); min-height: 430px; align-items: stretch; }
  .audit-panel { height: 100%; }
  .run-list { flex: 1; }
}

@media (max-width: 1320px) {
  .audit-workbench { grid-template-columns: minmax(250px,.75fr) minmax(440px,1.4fr); }
  .metrics-rail { grid-column: 1 / -1; }
  .metric-grid { grid-template-columns: repeat(4,1fr); }
  .evidence-grid { grid-template-columns: repeat(4,minmax(0,1fr)); }
  .audit-stage-strip { grid-template-columns: repeat(4, minmax(0, 1fr)); }
}
@media (max-width: 920px) {
  .audit-filter-bar { grid-template-columns: repeat(2,1fr); }
  .audit-filter-bar button { min-height: 44px; }
  .audit-workbench { grid-template-columns: 1fr; }
  .metrics-rail { grid-column: auto; }
  .audit-stage-strip { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}
@media (max-width: 768px) {
  .audit-page { min-width: 0; overflow-wrap: anywhere; }
  .governance-tabs { flex-wrap: wrap; min-width: 0; min-height: 44px; gap: 4px 12px; }
  .governance-tabs a { min-width: 0; min-height: 44px; flex: 1 1 120px; justify-content: center; overflow-wrap: anywhere; text-align: center; }
  .audit-filter-bar { grid-template-columns: minmax(0, 1fr); min-width: 0; }
  .audit-filter-bar > * { min-width: 0; }
  .audit-filter-bar select,
  .audit-filter-bar input,
  .audit-filter-bar button { min-width: 0; min-height: 44px; }
  .audit-workbench { grid-template-columns: minmax(0, 1fr); }
  .rail-heading,
  .detail-heading,
  .metrics-heading { flex-wrap: wrap; }
  .rail-heading > *,
  .detail-heading > *,
  .metrics-heading > * { min-width: 0; }
  .detail-heading > div > span,
  .run-title,
  .run-meta { flex-wrap: wrap; }
  .view-toggle { min-width: 0; flex-wrap: wrap; }
  .view-toggle button { min-width: 44px; min-height: 44px; }
  .run-select { min-height: 90px; }
  .run-pagination { flex-wrap: wrap; }
  .run-pagination div { flex-wrap: wrap; }
  .run-pagination button { width: 44px; height: 44px; }
  .refresh-button,
  .metrics-heading button,
  .audit-content-actions button { min-height: 44px; white-space: normal; overflow-wrap: anywhere; }
  .run-facts { grid-template-columns: repeat(2,minmax(0,1fr)); }
  .run-facts div:nth-child(n) { border-top: 1px solid #e7edf6; }
  .run-facts div:nth-child(-n+2) { border-top: 0; }
  .run-facts div:nth-child(2n+1) { border-left: 0; }
  .retrieval-panel > dl { grid-template-columns: repeat(2,minmax(0,1fr)); }
  .retrieval-panel dl div:nth-child(3) { border-top: 1px solid #e7edf6; border-left: 0; }
  .retrieval-panel dl div:nth-child(4) { border-top: 1px solid #e7edf6; }
  .metric-grid { grid-template-columns: repeat(2,minmax(0,1fr)); }
  .evidence-grid { grid-template-columns: repeat(2,minmax(0,1fr)); }
  .artifact-group dl { grid-template-columns: minmax(0,1fr); gap: 6px; }
  .audit-stage-strip { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .audit-content-actions { align-items: stretch; flex-direction: column; }
  .audit-content-actions :is(p, span, button) { min-width: 0; overflow-wrap: anywhere; white-space: normal; }
  .audit-timeline li { grid-template-columns: 28px minmax(0,1fr); }
  .timeline-status { grid-column: 2; justify-self: start; }
  .ai-audit-content > header { flex-wrap: wrap; }
}
</style>
