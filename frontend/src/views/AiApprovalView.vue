<template>
  <section class="approval-page" aria-label="AI 审批内容">
    <AiSafetyState v-if="!enabled" message="AI 审批能力当前关闭。" tone="warning" />
    <nav class="approval-breadcrumb" aria-label="面包屑">AI 能力 <span>/</span> 审批与审计</nav>
    <nav class="governance-tabs" aria-label="审批与审计视图">
      <RouterLink class="active" to="/ai/approvals" aria-current="page">待审批</RouterLink>
      <RouterLink to="/ai/audit">运行审计</RouterLink>
    </nav>
    <AiSafetyState
      v-if="store.error"
      :message="store.error"
      tone="danger"
      action-label="重新加载"
      :loading="store.loading"
      @action="retryProposals"
    />

    <div class="approval-workbench">
      <aside class="approval-panel proposal-rail" data-testid="approval-proposal-rail" aria-label="方案列表">
        <header class="rail-heading">
          <div><h2>方案列表（{{ store.proposalTotal || store.proposals.length }}）</h2><p>选择方案查看完整变更</p></div>
          <select v-model="proposalFilter" aria-label="方案类型筛选" :disabled="proposalMutationPending">
            <option value="all">全部类型</option>
            <option value="REPAIR_ASSIGN">维修指派</option>
            <option value="NOTICE_CREATE_DRAFT">公告草稿</option>
          </select>
        </header>

        <div v-if="store.loading" class="loading-state" role="status">正在加载待审方案…</div>
        <div v-else class="proposal-list">
          <article
            v-for="proposal in store.proposals"
            :key="proposal.id"
            class="ai-proposal-row"
            :class="{ selected: store.selectedId === proposal.id }"
          >
            <button type="button" class="proposal-select" :aria-label="`查看${proposal.title}提案`" :disabled="proposalMutationPending" @click="selectProposal(proposal.id)">
              <span class="proposal-icon" :class="proposal.actionType === 'REPAIR_ASSIGN' ? 'repair' : 'notice'">
                <ToolOutlined v-if="proposal.actionType === 'REPAIR_ASSIGN'" />
                <NotificationOutlined v-else />
              </span>
              <span class="proposal-copy">
                <span class="proposal-title-row">
                  <strong>{{ proposal.title }}</strong>
                  <i class="proposal-state" :class="`proposal-${proposal.state}`">{{ proposalStateLabel(proposal.state) }}</i>
                </span>
                <small>对象 {{ proposal.target }}</small>
                <span class="proposal-meta"><b :class="`risk-${proposal.riskLevel}`">{{ severityLabel(proposal.riskLevel) }}风险</b><time>{{ expiryLabel(proposal.expiresAt) }}</time></span>
              </span>
            </button>
          </article>
          <div v-if="!store.proposals.length" class="loading-state">当前类型暂无方案</div>
        </div>

        <footer class="proposal-pagination">
          <span>共 {{ store.proposalTotal || store.proposals.length }} 条</span>
          <div>
            <button type="button" :disabled="store.loading || proposalMutationPending || !hasPrevProposalPage" aria-label="上一页" @click="changeProposalPage(store.proposalPage - 1)">‹</button>
            <button type="button" class="active" aria-current="page" :aria-label="`第 ${store.proposalPage} 页`">{{ store.proposalPage }}</button>
            <button type="button" :disabled="store.loading || proposalMutationPending || !hasNextProposalPage" aria-label="下一页" @click="changeProposalPage(store.proposalPage + 1)">›</button>
          </div>
          <span>{{ store.proposalPageSize }} 条/页</span>
        </footer>
      </aside>

      <main v-if="store.selected" class="approval-panel preview-panel" data-testid="approval-preview-panel" aria-label="提案详情">
        <header class="preview-heading">
          <div>
            <span class="title-line"><h2 ref="previewHeadingRef" tabindex="-1">{{ store.selected.title }}</h2><i class="proposal-state" :class="`proposal-${store.selected.state}`">{{ proposalStateLabel(store.selected.state) }}</i></span>
            <p>方案编号：{{ store.selected.id }}</p>
          </div>
          <span class="risk-label" :class="`risk-${store.selected.riskLevel}`">{{ severityLabel(store.selected.riskLevel) }}风险</span>
        </header>

        <section class="tool-preview" aria-labelledby="tool-preview-title">
          <header><FileSearchOutlined /><div><h3 id="tool-preview-title">工具调用预览</h3><p>仅在人工批准后调用现有业务 Service</p></div></header>
          <div class="value-diff">
            <div class="diff-label"></div>
            <strong>当前值（将被替换）</strong>
            <strong>建议值（执行后）</strong>
            <span>目标对象</span><p class="current-value">{{ store.selected.target }}</p><p class="proposed-value">{{ store.selected.target }}</p>
            <span>业务值</span><p class="current-value">{{ store.selected.currentValue }}</p><p class="proposed-value">{{ store.selected.proposedValue }}</p>
            <span>影响范围</span><p class="current-value">保持现状</p><p class="proposed-value">{{ store.selected.impact }}</p>
          </div>
        </section>

        <dl class="proposal-facts">
          <div><dt>影响范围</dt><dd>{{ store.selected.impact }}</dd></div>
          <div><dt>版本</dt><dd>v{{ store.selected.version }}</dd></div>
          <div><dt>所需权限</dt><dd>{{ store.selected.requiredPermission }}</dd></div>
          <div>
            <dt>方案快照（Hash）</dt>
            <dd class="fact-hash">
              <details class="hash-disclosure">
                <summary><code class="hash-preview">{{ summarizeHash(store.selected.businessSnapshotHash) }}</code><span>查看完整值</span></summary>
                <code class="hash-full-value" tabindex="0">{{ store.selected.businessSnapshotHash }}</code>
              </details>
            </dd>
          </div>
          <div><dt>引用来源</dt><dd>{{ store.selected.evidence.citations.length }} 条授权来源</dd></div>
          <div><dt>过期时间</dt><dd>{{ formatTime(store.selected.expiresAt) }}</dd></div>
          <div><dt>数据截至时间</dt><dd>{{ formatTime(store.selected.evidence.asOf) }}</dd></div>
          <div>
            <dt>Payload Hash</dt>
            <dd class="fact-hash">
              <details class="hash-disclosure">
                <summary><code class="hash-preview">{{ summarizeHash(store.selected.payloadHash) }}</code><span>查看完整值</span></summary>
                <code class="hash-full-value" tabindex="0">{{ store.selected.payloadHash }}</code>
              </details>
            </dd>
          </div>
        </dl>

        <AiEvidenceMeta class="evidence-meta" :evidence="store.selected.evidence" />

        <ol class="approval-flow" aria-label="AI 建议到人工审批流程">
          <li data-flow-stage="suggestion" :class="approvalJourney.suggestion.state"><span><FileSearchOutlined /></span><p><strong>AI 建议</strong><small>{{ approvalJourney.suggestion.label }}</small></p></li>
          <li class="flow-line" :class="approvalJourney.firstLine"></li>
          <li data-flow-stage="preview" :class="approvalJourney.preview.state"><span><EyeOutlined /></span><p><strong>变更预览</strong><small>{{ approvalJourney.preview.label }}</small></p></li>
          <li class="flow-line" :class="approvalJourney.secondLine"></li>
          <li data-flow-stage="approval" :class="approvalJourney.approval.state"><span><UserOutlined /></span><p><strong>人工审批</strong><small>{{ approvalJourney.approval.label }}</small></p></li>
        </ol>

        <label class="approval-attestation" data-testid="approval-attestation">
          <input ref="approvalAttestationRef" v-model="attested" type="checkbox" />
          <span>我已核对变更及影响；提交审批决定，通过门槛后才受控执行，只有 SUCCEEDED 代表成功。</span>
        </label>

        <div class="ai-approval-blockers">
          <AiSafetyState v-if="proposalExpired" message="方案已过期，请回到来源页面重新发起能力。" tone="danger" />
          <AiSafetyState v-if="store.selected.state === 'stale'" message="业务数据已变化，请回到来源页面重新发起能力。" tone="danger" />
          <AiSafetyState v-if="store.selected.state === 'needs_review'" message="执行结果不确定，已禁止自动重放；请核对原业务事实并走人工对账。" tone="danger" />
          <AiSafetyState v-if="!store.selected.auditAvailable" message="审计不可用，已禁止执行。" tone="danger" />
          <AiSafetyState v-if="!store.selected.evidence.grounded" message="暂无可靠来源，已禁止执行。" tone="warning" />
          <AiSafetyState v-if="!approvalPermissionSatisfied" message="当前会话缺少提案所需业务权限，已禁止执行。" tone="danger" />
          <AiSafetyState v-if="!approvalContractValid" message="提案版本或完整性 Hash 不合法，请刷新预览。" tone="danger" />
        </div>

        <div class="guard-grid" aria-label="审批阻断条件">
          <span :class="proposalExpired ? 'blocked' : 'clear'"><ExclamationCircleFilled />方案{{ proposalExpired ? '已过期，请刷新预览' : '有效期内' }}</span>
          <span :class="store.selected.state === 'stale' ? 'blocked' : 'clear'"><ExclamationCircleFilled />业务数据{{ store.selected.state === 'stale' ? '已变化，方案失效' : '未变化' }}</span>
          <span :class="approvalPermissionSatisfied ? 'clear' : 'blocked'"><LockOutlined />{{ approvalPermissionSatisfied ? '基础权限已满足' : '无权限批准' }}</span>
          <span :class="store.selected.auditAvailable ? 'clear' : 'blocked'"><InfoCircleFilled />审计{{ store.selected.auditAvailable ? '可用' : '不可用，已禁止执行' }}</span>
        </div>

        <p class="confidence-note"><InfoCircleFilled />置信度较低或无可靠引用来源时，请谨慎审批，必要时先刷新预览或核实数据。</p>

        <AiSafetyState v-if="store.mutationError" :message="store.mutationError" tone="danger" />

        <div class="approval-actions">
          <button type="button" class="primary" :disabled="proposalMutationPending || !canApprove || !attested" :aria-busy="store.proposalMutation === 'approve'" @click="openApproval"><LockOutlined />批准执行</button>
          <button type="button" class="danger" :disabled="proposalMutationPending || store.selected.state !== 'pending_approval'" :aria-busy="store.proposalMutation === 'reject'" @click="reject"><CloseCircleOutlined />拒绝</button>
          <button type="button" class="secondary" :disabled="proposalMutationPending" :aria-busy="store.proposalMutation === 'refresh'" @click="refresh"><ReloadOutlined />刷新预览</button>
        </div>

        <form v-if="store.selected.state === 'needs_review'" class="ai-reconfirm" @submit.prevent="reconfirm">
          <h3>人工对账</h3>
          <AiSafetyState message="先核对原业务事实。只有“可证明未执行”会使用同一 execution lease 继续原白名单动作；其他结论不会重放业务写。" tone="warning" />
          <a-select v-model:value="reconfirmResolution" aria-label="人工对账结论" :options="reconfirmOptions" />
          <a-textarea v-model:value="reconfirmComment" aria-label="人工对账说明" :rows="3" :maxlength="500" />
          <a-input-password v-if="!isDemo" v-model:value="reconfirmPassword" aria-label="人工对账当前密码" autocomplete="current-password" placeholder="重新输入当前密码以生成单次对账证明" />
          <button type="submit" :disabled="proposalMutationPending || reconfirmComment.trim().length < 6 || (!isDemo && !reconfirmPassword)" :aria-busy="store.proposalMutation === 'reconfirm'">确认对账结果</button>
        </form>
        <AiSafetyState v-if="resultMessage" :message="resultMessage" tone="success" />
      </main>

      <aside v-if="store.selected && canViewAudit" class="approval-panel runtime-rail" data-testid="approval-runtime-rail" aria-label="运行审计">
        <header class="runtime-heading"><div><h2>运行审计 <InfoCircleOutlined /></h2><p>脱敏元数据与状态链</p></div><span>总耗时：<b>{{ runtimeRun ? `${(runtimeRun.durationMs / 1000).toFixed(1)} 秒` : '待生成' }}</b></span></header>
        <AiSafetyState
          v-if="auditError"
          :message="auditError"
          tone="danger"
          action-label="重新加载"
          :loading="auditLoading"
          @action="retryAudits"
        />
        <ol class="runtime-timeline">
          <li v-for="step in runtimeSteps" :key="step.key" :class="step.state">
            <span class="runtime-node"><CheckCircleFilled v-if="step.state === 'done'" /><UserOutlined v-else-if="step.state === 'current'" /><ClockCircleOutlined v-else /></span>
            <div class="runtime-label"><strong>{{ step.label }}</strong><time>{{ step.time }}</time></div>
            <dl><div v-for="item in step.meta" :key="item[0]"><dt>{{ item[0] }}</dt><dd :class="item[1] === 'SUCCESS' ? 'success' : ''">{{ item[1] }}</dd></div></dl>
          </li>
        </ol>

        <section class="cost-card">
          <h3>Token / 成本估算</h3>
          <div><span>共 {{ runtimeTokens.toLocaleString() }} Tokens</span><b>约 ¥ {{ runtimeRun?.estimatedCost?.toFixed(4) ?? '0.0000' }}</b></div>
        </section>
        <p class="execution-note"><CheckCircleFilled /><span>仅当执行状态为 <strong>SUCCEEDED</strong> 时，才视为业务执行成功。已批准不等于已执行。</span></p>
      </aside>
    </div>

    <a-modal v-model:open="confirmOpen" title="确认批准执行" ok-text="确认批准" cancel-text="取消" :confirm-loading="store.proposalMutation === 'approve'" :mask-closable="!proposalMutationPending" @ok="approve" @cancel="cancelApproval">
      <p>确认已阅读变更内容、影响范围、权限和数据时间。</p>
      <p v-if="isDemo">演示模式只更新 AI Store，不执行真实业务。</p>
      <a-input-password v-else v-model:value="currentPassword" aria-label="当前密码" autocomplete="current-password" placeholder="重新输入当前密码以生成单次审批证明" />
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RouterLink } from 'vue-router'
import {
  CheckCircleFilled, ClockCircleOutlined, CloseCircleOutlined, ExclamationCircleFilled,
  EyeOutlined, FileSearchOutlined, InfoCircleFilled, InfoCircleOutlined, LockOutlined,
  NotificationOutlined, ReloadOutlined, ToolOutlined, UserOutlined,
} from '@ant-design/icons-vue'
import { useNow } from '@vueuse/core'
import { message } from 'ant-design-vue'
import { getAiClientMode, isAiSurfaceEnabled } from '../api/ai-client'
import AiEvidenceMeta from '../components/ai/AiEvidenceMeta.vue'
import AiSafetyState from '../components/ai/AiSafetyState.vue'
import { useAiApprovalStore } from '../stores/aiApproval'
import { useAuthStore } from '../stores/auth'
import type { AiExecutionReconfirmResolution, AiProposalPreview as AiProposalPreviewType, AiProposalState, AiRiskSeverity } from '../types/ai'
import { isProposalEvidenceConfirmable } from '../utils/ai-approval'

const store = useAiApprovalStore()
const auth = useAuthStore()
const approvalNow = useNow({ interval: 1_000 })
type ProposalWithRun = AiProposalPreviewType & { runId?: string }
type ProposalFilter = 'all' | AiProposalPreviewType['actionType']
type LinkedProposalMode = 'view' | 'approve'
const enabled = isAiSurfaceEnabled()
const isDemo = isAiDemoMode()
const confirmOpen = ref(false)
const currentPassword = ref('')
const resultMessage = ref('')
const auditLoading = ref(false)
const auditError = ref('')
const proposalFilter = ref<ProposalFilter>('all')
const attested = ref(false)
const reconfirmResolution = ref<AiExecutionReconfirmResolution>('UNKNOWN')
const reconfirmComment = ref('')
const reconfirmPassword = ref('')
const previewHeadingRef = ref<HTMLElement | null>(null)
const approvalAttestationRef = ref<HTMLInputElement | null>(null)
const approvalTarget = ref<Pick<AiProposalPreviewType, 'id' | 'version' | 'payloadHash' | 'businessSnapshotHash'> | null>(null)
const reconfirmOptions = [
  { label: '结果已存在', value: 'RESULT_CONFIRMED' }, { label: '可证明未执行', value: 'PROVEN_NOT_EXECUTED' },
  { label: '状态冲突/失败', value: 'CONFLICT' }, { label: '仍不确定', value: 'UNKNOWN' },
]
const selectedProposal = computed(() => store.selected as ProposalWithRun | null)
const proposalPageCount = computed(() => Math.max(1, Math.ceil((store.proposalTotal || store.proposals.length) / Math.max(1, store.proposalPageSize))))
const hasPrevProposalPage = computed(() => store.proposalPage > 1)
const hasNextProposalPage = computed(() => store.proposalPage * store.proposalPageSize < (store.proposalTotal || store.proposals.length))
const proposalExpired = computed(() => proposalIsExpired(store.selected))
const approvalPermissionSatisfied = computed(() => proposalPermissionIsSatisfied(store.selected))
const approvalContractValid = computed(() => proposalContractIsValid(store.selected))
const canApprove = computed(() => proposalCanBeApproved(store.selected))
const proposalMutationPending = computed(() => store.proposalMutation !== null)
const canViewAudit = computed(() => auth.hasPermission('ai:audit:read'))
type ApprovalFlowState = 'done' | 'current' | 'pending' | 'warning' | 'failure' | 'skipped'
type ApprovalFlowStep = { state: ApprovalFlowState; label: string }
const approvalJourney = computed(() => approvalJourneyFor(store.selected?.state))
const runtimeDetail = computed(() => {
  const boundRunId = selectedProposal.value?.runId?.trim()
  return boundRunId && store.auditDetail?.run.id === boundRunId ? store.auditDetail : null
})
const runtimeRun = computed(() => {
  const boundRunId = selectedProposal.value?.runId?.trim()
  if (!boundRunId) return null
  return runtimeDetail.value?.run ?? store.audits.find((item) => item.id === boundRunId) ?? null
})
const runtimeTokens = computed(() => runtimeRun.value ? runtimeRun.value.inputTokens + runtimeRun.value.outputTokens : 0)
const runtimeSteps = computed(() => {
  const run = runtimeRun.value
  const proposal = store.selected
  const detail = runtimeDetail.value
  const tool = detail?.tools[detail.tools.length - 1]
  const approval = detail?.approvals[detail.approvals.length - 1]
  const execution = detail?.executions.find((item) => item.id === proposal?.executionId)
    ?? detail?.executions[detail.executions.length - 1]
  const occurred = run ? formatTime(run.occurredAt).slice(11) : '--:--'
  const toolTime = formatTime(tool?.finishedAt ?? tool?.startedAt ?? '').slice(11)
  const approvalTime = formatTime(approval?.createdAt ?? '').slice(11)
  const executionTime = formatTime(execution?.finishedAt ?? execution?.startedAt ?? '').slice(11)
  const executionState = execution?.state.toLowerCase() ?? proposal?.executionState
  const executionSucceeded = executionState === 'succeeded'
  const executionFailed = executionState === 'failed' || executionState === 'needs_review'
  return [
    { key: 'model', label: '模型', time: occurred, state: run ? 'done' : 'pending', meta: [['模型别名', run?.modelAlias ?? '待运行'], ['提示词版本', run?.promptVersion ?? '待运行']] },
    { key: 'retrieval', label: '检索', time: occurred, state: run ? 'done' : 'pending', meta: [['检索策略', detail?.retrievals[0]?.retrievalPolicyVersion ?? '授权检索'], ['命中片段', `${detail?.citations.length ?? run?.citationCount ?? 0} 条`]] },
     { key: 'tool', label: '工具调用（预览）', time: toolTime, state: tool?.state === 'SUCCEEDED' ? 'done' : tool?.state === 'FAILED' ? 'failed' : 'pending', meta: [['工具名称', tool?.toolName ?? proposal?.actionType ?? '待选择'], ['调用状态', tool?.state ?? '审计事实缺失（未记录）']] },
    { key: 'citations', label: '引用来源', time: occurred, state: proposal?.evidence.grounded ? 'done' : 'pending', meta: [['规则命中', `${proposal?.evidence.citations.length ?? 0} 条`], ['授权来源', `${detail?.citations.length ?? proposal?.evidence.citations.filter((item) => item.access === 'available').length ?? 0} 条`]] },
    { key: 'approval', label: '人工审批', time: approvalTime, state: proposal?.state === 'pending_approval' ? 'current' : approval ? 'done' : 'pending', meta: [['审批状态', approval?.decision ?? proposalStateLabel(proposal?.state)], ['审批人', approval ? `用户 ${approval.reviewerUserId}` : '—']] },
    { key: 'execution', label: executionSucceeded ? '执行' : '执行（待执行）', time: executionTime, state: executionSucceeded ? 'done' : executionFailed ? 'failed' : 'pending', meta: [['执行状态', execution?.state ?? proposal?.executionState?.toUpperCase() ?? 'PENDING'], ['执行处理器', execution?.handlerName ?? '—']] },
    { key: 'result', label: '结果', time: executionTime, state: executionSucceeded ? 'done' : executionFailed ? 'failed' : 'pending', meta: [['执行状态', execution?.state ?? proposal?.executionState?.toUpperCase() ?? '—'], ['结果摘要', execution?.resultResourceType ?? execution?.errorCode ?? (executionSucceeded ? '业务服务已确认' : '—')]] },
  ]
})

function clearApprovalConfirmation() { confirmOpen.value = false; currentPassword.value = ''; approvalTarget.value = null }
function isAiDemoMode() { return getAiClientMode() === 'demo' }
function clearProposalContext() {
  clearApprovalConfirmation(); attested.value = false; reconfirmResolution.value = 'UNKNOWN'; reconfirmComment.value = ''
  reconfirmPassword.value = ''; resultMessage.value = ''
}
watch(() => store.selectedId, clearProposalContext, { flush: 'sync' })
watch(canViewAudit, (allowed, previouslyAllowed) => {
  if (!allowed) {
    auditError.value = ''
    store.clearAuditCache()
    return
  }
  if (enabled && previouslyAllowed === false) void loadApprovalAudits()
}, { flush: 'sync' })
watch(proposalFilter, () => {
  if (!enabled) return
  void store.loadProposals(proposalQuery(1), { reconcileSelection: true, syncAudit: canViewAudit.value })
})
const formatTime = (value: string) => value.replace('T', ' ').slice(0, 16)
const summarizeHash = (value: string) => value.length > 24 ? `${value.slice(0, 12)}...${value.slice(-8)}` : value
const severityLabel = (value: AiRiskSeverity) => ({ low: '低', medium: '中', high: '高' })[value]
const proposalStateLabel = (value?: AiProposalState) => ({
  draft: '草稿', pending_approval: '待审批', approved: '已批准', rejected: '已拒绝', executing: '执行中',
  succeeded: '已成功', failed: '失败', needs_review: '待对账', stale: '已失效', expired: '已过期', cancelled: '已取消',
})[value ?? 'pending_approval']
const expiryLabel = (value: string) => `有效至 ${formatTime(value).slice(5)}`

function approvalJourneyFor(state: AiProposalState | undefined) {
  const suggestion: ApprovalFlowStep = state === 'draft'
    ? { state: 'current', label: '当前步骤' }
    : { state: 'done', label: '已完成' }
  const preview: ApprovalFlowStep = state === 'draft'
    ? { state: 'pending', label: '待预览' }
    : { state: 'done', label: '已完成' }
  const approval: ApprovalFlowStep = (() => {
    if (state === 'pending_approval') return { state: 'current', label: '当前步骤' }
    if (state === 'rejected') return { state: 'failure', label: '已拒绝' }
    if (state === 'expired') return { state: 'warning', label: '已过期' }
    if (state === 'stale') return { state: 'warning', label: '已失效' }
    if (state === 'cancelled') return { state: 'skipped', label: '已取消' }
    if (state === 'draft' || !state) return { state: 'pending', label: '待提交' }
    return { state: 'done', label: '已通过' }
  })()
  return {
    suggestion,
    preview,
    approval,
    firstLine: suggestion.state === 'done' ? 'done' : 'pending',
    secondLine: preview.state === 'done' ? 'done' : 'pending',
  }
}

function proposalIsExpired(proposal: AiProposalPreviewType | null | undefined) {
  if (!proposal || proposal.state === 'expired') return true
  const expiresAt = Date.parse(proposal.expiresAt)
  return !Number.isFinite(expiresAt) || expiresAt <= approvalNow.value.getTime()
}

function proposalPermissionIsSatisfied(proposal: AiProposalPreviewType | null | undefined) {
  if (!proposal || !auth.hasPermission('ai:approval:review')) return false
  const requirements = proposal.requiredPermission.split('+').map((item) => item.trim()).filter(Boolean)
  const permissionCodes = requirements.filter((item) => /^[a-z][a-z0-9_-]*:[a-z0-9:_-]+$/i.test(item))
  if (!permissionCodes.every((permission) => auth.hasPermission(permission))) return false
  if (requirements.includes('ADMIN')) {
    const roleCodes = new Set([auth.user?.roleCode, ...(auth.user?.roleCodes ?? [])].filter(Boolean))
    if (!roleCodes.has('ADMIN')) return false
  }
  return true
}

function proposalContractIsValid(proposal: AiProposalPreviewType | null | undefined) {
  return Boolean(proposal
    && Number.isSafeInteger(proposal.version)
    && proposal.version >= 0
    && /^[0-9a-f]{64}$/i.test(proposal.payloadHash)
    && /^[0-9a-f]{64}$/i.test(proposal.businessSnapshotHash))
}

function proposalCanBeApproved(proposal: AiProposalPreviewType | null | undefined) {
  return Boolean(proposal
    && proposal.state === 'pending_approval'
    && !proposalIsExpired(proposal)
    && proposal.auditAvailable
    && proposalPermissionIsSatisfied(proposal)
    && proposalContractIsValid(proposal)
    && isProposalEvidenceConfirmable(proposal.evidence))
}

function openApproval() {
  const proposal = store.selected
  if (!proposal || !proposalCanBeApproved(proposal) || !attested.value) return
  approvalTarget.value = { id: proposal.id, version: proposal.version, payloadHash: proposal.payloadHash, businessSnapshotHash: proposal.businessSnapshotHash }
  confirmOpen.value = true
}
async function approve() {
  try {
    const proposal = store.selected
    const target = approvalTarget.value
    if (!proposal || !target || !proposalCanBeApproved(proposal)
      || proposal.id !== target.id || proposal.version !== target.version
      || proposal.payloadHash !== target.payloadHash || proposal.businessSnapshotHash !== target.businessSnapshotHash) {
      clearApprovalConfirmation(); message.error('提案已变化，请重新确认'); return
    }
    if (!isDemo && !currentPassword.value) { message.error('批准真实执行前必须重新输入当前密码'); return }
    const applied = await store.approve(
      isDemo ? '已人工确认演示方案' : '已人工确认变更预览',
      currentPassword.value,
      { syncAudit: canViewAudit.value },
    )
    if (!applied) return
    confirmOpen.value = false; approvalTarget.value = null
    resultMessage.value = isDemo ? '演示结果，未执行真实业务' : '审批请求已提交，请以执行状态和原业务事实为准'
  } catch (error) { message.error(error instanceof Error ? error.message : '审批失败') }
  finally { currentPassword.value = '' }
}
function cancelApproval() { clearApprovalConfirmation() }
async function reject() {
  try {
    if (await store.reject(
      isAiDemoMode() ? '人工拒绝演示方案' : '人工拒绝方案',
      { syncAudit: canViewAudit.value },
    )) message.success('方案已拒绝')
  } catch (error) { message.error(error instanceof Error ? error.message : '拒绝提案失败') }
}
async function refresh() {
  try {
    if (!await store.refresh({ syncAudit: canViewAudit.value })) return
    resultMessage.value = ''; attested.value = false; message.success('方案状态已重新加载')
  } catch (error) { message.error(error instanceof Error ? error.message : '刷新提案失败') }
}
async function selectProposal(id: string) {
  try {
    await store.selectProposal(id, { syncAudit: canViewAudit.value })
    if (typeof window === 'undefined' || !window.matchMedia('(max-width: 768px)').matches) return
    await nextTick()
    if (store.selectedId !== id) return
    previewHeadingRef.value?.scrollIntoView({ behavior: 'auto', block: 'start', inline: 'nearest' })
    previewHeadingRef.value?.focus()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '提案详情加载失败')
  }
}
function proposalQuery(page: number) {
  return {
    page,
    pageSize: store.proposalPageSize,
    ...(proposalFilter.value === 'all' ? {} : { actionType: proposalFilter.value }),
  }
}
async function changeProposalPage(page: number) {
  if (store.loading || page < 1 || page > proposalPageCount.value || page === store.proposalPage) return
  await store.loadProposals(proposalQuery(page), { reconcileSelection: true, syncAudit: canViewAudit.value })
}
async function retryProposals() {
  await store.loadProposals(proposalQuery(store.proposalPage), { reconcileSelection: false, syncAudit: canViewAudit.value })
}
async function reconfirm() {
  try {
    if (!await store.reconfirm(
      reconfirmResolution.value,
      reconfirmComment.value,
      reconfirmPassword.value,
      { syncAudit: canViewAudit.value },
    )) return
    reconfirmComment.value = ''; message.success('人工对账结果已记录')
  }
  catch (error) { message.error(error instanceof Error ? error.message : '人工对账失败') }
  finally { reconfirmPassword.value = '' }
}
async function loadApprovalAudits(page = 1) {
  if (!canViewAudit.value) {
    store.clearAuditCache()
    return
  }
  if (auditLoading.value) return
  auditLoading.value = true
  auditError.value = ''
  try {
    await store.loadAudits({ page, pageSize: store.auditPageSize }, { reconcileSelection: false })
  } catch (error) {
    store.clearAuditCache()
    auditError.value = error instanceof Error ? error.message : '运行审计加载失败'
  } finally {
    auditLoading.value = false
  }
}
async function retryAudits() { await loadApprovalAudits(store.auditPage || 1) }
const proposalPublicIdPattern = /^[a-z0-9][a-z0-9._:-]{0,127}$/i
function linkedProposalContext(): { proposalId: string; mode: LinkedProposalMode | null } {
  if (typeof window === 'undefined') return { proposalId: '', mode: null }
  const params = new URLSearchParams(window.location.search)
  const proposalValue = params.get('proposal')?.trim() ?? ''
  const modeValue = params.get('mode')?.trim() ?? ''
  const mode = !modeValue
    ? 'view'
    : modeValue === 'view' || modeValue === 'approve' ? modeValue : null
  return {
    proposalId: proposalPublicIdPattern.test(proposalValue) ? proposalValue : '',
    mode,
  }
}
async function focusLinkedProposal(proposalId: string, mode: LinkedProposalMode) {
  await nextTick()
  if (store.selectedId !== proposalId) return
  const target = mode === 'approve' ? approvalAttestationRef.value : previewHeadingRef.value
  target?.scrollIntoView({ behavior: 'auto', block: mode === 'approve' ? 'center' : 'start', inline: 'nearest' })
  target?.focus()
}
async function initializeProposals() {
  await store.loadProposals({}, { reconcileSelection: false, syncAudit: canViewAudit.value })
  const { proposalId, mode } = linkedProposalContext()
  if (!proposalId) return
  try {
    await store.selectProposal(proposalId, { syncAudit: canViewAudit.value })
    if (mode) await focusLinkedProposal(proposalId, mode)
  } catch (error) {
    message.error(error instanceof Error ? error.message : '链接中的提案详情加载失败')
  }
}
onBeforeUnmount(clearProposalContext)
onMounted(() => {
  if (!enabled) return
  void initializeProposals()
  if (canViewAudit.value) void loadApprovalAudits()
  else store.clearAuditCache()
})
</script>

<style scoped>
.approval-page { display: grid; gap: var(--space-3); color: var(--text); font-family: var(--font-family-ui); }
.approval-breadcrumb { color: var(--text-muted); font-size: 13px; }
.approval-breadcrumb span { margin: 0 8px; color: #c2cbd8; }
.governance-tabs { display: flex; gap: 28px; min-height: var(--touch-target); border-bottom: 1px solid #e7edf6; }
.governance-tabs a { position: relative; display: flex; align-items: center; min-height: var(--touch-target); padding: 0 14px; color: var(--text-muted); font-size: 14px; text-decoration: none; }
.governance-tabs a.active { color: #2563eb; font-weight: 600; }
.governance-tabs a.active::after { position: absolute; right: 0; bottom: -1px; left: 0; height: 2px; background: #2563eb; content: ""; }
.approval-workbench { display: grid; grid-template-columns: minmax(260px, .72fr) minmax(460px, 1.42fr) minmax(300px, .9fr); gap: var(--space-3); align-items: start; }
.approval-panel { min-width: 0; border: 1px solid var(--border); border-radius: var(--radius-control); background: var(--surface); box-shadow: var(--shadow-card); }
.proposal-rail { overflow: hidden; }
.rail-heading,
.preview-heading,
.runtime-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.rail-heading { padding: 14px 14px 10px; }
.rail-heading h2,
.preview-heading h2,
.runtime-heading h2 { margin: 0; color: var(--text-title); font-size: 16px; }
.preview-heading h2 { scroll-margin-top: calc(var(--header-height) + 12px); }
.preview-heading h2:focus { outline: 2px solid var(--primary); outline-offset: 3px; }
.rail-heading p,
.preview-heading p,
.runtime-heading p { margin: 3px 0 0; color: var(--text-muted); font-size: var(--font-size-caption); }
.rail-heading select { width: 112px; min-height: var(--touch-target); padding: 4px 8px; border: 1px solid var(--border); border-radius: var(--radius-control); color: var(--text); background: var(--surface); font: inherit; font-size: var(--font-size-caption); }
.proposal-list { border-top: 1px solid #edf2f8; }
.ai-proposal-row { border-top: 1px solid #edf2f8; }
.ai-proposal-row:first-child { border-top: 0; }
.ai-proposal-row.selected { margin: 6px; border: 1px solid #3b82f6; border-radius: 7px; background: #f7faff; }
.proposal-select { display: grid; grid-template-columns: 38px minmax(0,1fr); gap: 9px; width: 100%; min-height: 82px; padding: 11px 12px; border: 0; color: inherit; background: transparent; text-align: left; cursor: pointer; }
.proposal-icon { display: grid; place-items: center; width: 34px; height: 34px; border: 1px solid #cfe0fb; border-radius: 50%; font-size: 17px; }
.proposal-icon.repair { color: #2563eb; background: #eff6ff; }
.proposal-icon.notice { color: #4f46e5; background: #eef2ff; }
.proposal-copy { min-width: 0; }
.proposal-title-row { display: flex; align-items: center; justify-content: space-between; gap: 5px; }
.proposal-title-row strong { color: var(--text-title); font-size: var(--font-size-body); }
.proposal-state { padding: 3px 7px; border-radius: 4px; color: var(--warning-strong); background: var(--surface-warning); font-size: var(--font-size-caption); font-style: normal; font-weight: var(--font-weight-medium); white-space: nowrap; }
.proposal-approved,
.proposal-succeeded { color: #059669; background: #ecfdf5; }
.proposal-rejected,
.proposal-failed { color: #dc2626; background: #fff1f2; }
.proposal-expired,
.proposal-stale { color: #64748b; background: #f1f5f9; }
.proposal-copy > small { display: block; margin-top: 5px; color: var(--text-muted); font-size: var(--font-size-caption); line-height: 1.45; overflow-wrap: anywhere; }
.proposal-meta { display: flex; justify-content: space-between; gap: 8px; margin-top: 8px; font-size: var(--font-size-caption); line-height: 1.4; }
.proposal-meta b { font-weight: 500; }
.proposal-meta time { color: #64748b; }
.risk-high { color: #ef4444; }
.risk-medium { color: #f59e0b; }
.risk-low { color: #2563eb; }
.loading-state { padding: 32px 14px; color: #64748b; font-size: 12px; text-align: center; }
.proposal-pagination { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 48px; padding: 7px 12px; border-top: 1px solid var(--border); color: var(--text-muted); font-size: var(--font-size-caption); }
.proposal-pagination div { display: flex; gap: 4px; }
.proposal-pagination button { width: var(--touch-target); height: var(--touch-target); border: 1px solid #dce4ef; border-radius: 4px; color: #64748b; background: #fff; }
.proposal-pagination button.active { border-color: #2563eb; color: #fff; background: #2563eb; }
.preview-panel { padding: 14px; }
.title-line { display: flex; align-items: center; gap: 8px; }
.risk-label { padding: 4px 8px; border: 1px solid currentColor; border-radius: 4px; font-size: var(--font-size-caption); font-weight: var(--font-weight-semibold); white-space: nowrap; }
.tool-preview { margin-top: 12px; padding: 12px; border: 1px solid var(--primary-border); border-radius: var(--radius-control); background: var(--surface-muted); }
.tool-preview > header { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; color: #2563eb; }
.tool-preview h3 { margin: 0; color: var(--text-title); font-size: var(--font-size-body); }
.tool-preview header p { margin: 2px 0 0; color: var(--text-muted); font-size: var(--font-size-caption); }
.value-diff { display: grid; grid-template-columns: 104px 1fr 1fr; border: 1px solid var(--border); border-radius: 5px; background: var(--surface); overflow: hidden; font-size: var(--font-size-caption); line-height: 1.45; }
.value-diff > * { margin: 0; min-width: 0; padding: 8px; border-top: 1px solid #e7edf6; border-left: 1px solid #e7edf6; overflow-wrap: anywhere; }
.value-diff > :nth-child(-n+3) { border-top: 0; }
.value-diff > :nth-child(3n+1) { border-left: 0; color: #475569; font-weight: 600; }
.value-diff > strong { color: #475569; background: #f8fafc; font-weight: 600; }
.value-diff .current-value { color: var(--danger-strong); background: var(--surface-danger); box-shadow: inset 3px 0 0 var(--danger); }
.value-diff .proposed-value { color: var(--success-strong); background: var(--surface-success); box-shadow: inset 3px 0 0 var(--success); }
.proposal-facts { display: grid; grid-template-columns: 1fr 1fr; margin: 12px 0 0; border: 1px solid #e7edf6; border-radius: 6px; overflow: hidden; }
.proposal-facts div { display: grid; grid-template-columns: 105px minmax(0,1fr); min-height: 37px; border-top: 1px solid #e7edf6; }
.proposal-facts div:nth-child(-n+2) { border-top: 0; }
.proposal-facts div:nth-child(2n) { border-left: 1px solid #e7edf6; }
.proposal-facts dt,
.proposal-facts dd { padding: 8px; font-size: var(--font-size-caption); line-height: 1.45; overflow-wrap: anywhere; }
.proposal-facts dt { color: #64748b; background: #f8fafc; }
.proposal-facts dd { margin: 0; color: #172554; }
.proposal-facts .fact-hash { padding: 5px 8px; }
.hash-disclosure { min-width: 0; }
.hash-disclosure summary { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; min-height: var(--touch-target); color: var(--primary); cursor: pointer; }
.hash-disclosure summary:focus,
.hash-disclosure summary:focus-visible { outline: 2px solid var(--primary); outline-offset: 2px; border-radius: 4px; }
.hash-disclosure summary::marker { color: var(--primary); }
.hash-preview { min-width: 0; color: var(--text-strong); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: var(--font-size-caption); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.hash-disclosure summary > span { font-size: var(--font-size-caption); font-weight: var(--font-weight-semibold); white-space: nowrap; }
.hash-full-value { display: block; margin-top: 6px; padding: 8px; border: 1px solid var(--primary-border); border-radius: 4px; color: var(--text-strong); background: var(--surface); font-family: ui-monospace, SFMono-Regular, Consolas, monospace; font-size: var(--font-size-caption); line-height: 1.5; overflow-wrap: anywhere; }
.evidence-meta { margin-top: 10px; }
.approval-flow { display: grid; grid-template-columns: auto 1fr auto 1fr auto; align-items: center; margin: 14px 0 10px; padding: 0 20px; list-style: none; }
.approval-flow li:not(.flow-line) { display: flex; align-items: center; gap: 7px; }
.approval-flow li > span { display: grid; place-items: center; width: 34px; height: 34px; border: 1px solid #cbd5e1; border-radius: 50%; color: #64748b; background: #f8fafc; font-size: 17px; }
.approval-flow p { display: grid; gap: 2px; margin: 0; }
.approval-flow strong { color: var(--text-title); font-size: var(--font-size-body); }
.approval-flow small { color: var(--text-muted); font-size: var(--font-size-caption); }
.approval-flow .done > span { border-color: #2563eb; color: #2563eb; background: #eff6ff; }
.approval-flow .done small { color: #1d4ed8; }
.approval-flow .current > span { border-color: #2563eb; border-style: dashed; color: #2563eb; background: #eff6ff; }
.approval-flow .current small { color: #1d4ed8; }
.approval-flow .warning > span { border-color: #d97706; color: #b45309; background: #fffbeb; }
.approval-flow .warning small { color: #b45309; }
.approval-flow .failure > span { border-color: #ef4444; color: #dc2626; background: #fff1f2; }
.approval-flow .failure small { color: #dc2626; }
.approval-flow .skipped > span { border-style: dashed; background: #f1f5f9; }
.flow-line { height: 2px; margin: 0 8px; background: #dce4ef; }
.flow-line.done { background: #93c5fd; }
.approval-attestation { display: flex; align-items: flex-start; gap: 8px; padding: 10px; border: 1px solid var(--border); border-radius: 5px; color: var(--text); font-size: 13px; line-height: 1.5; }
.approval-attestation:focus-within { border-color: var(--primary); box-shadow: 0 0 0 2px rgba(37, 99, 235, .16); }
.approval-attestation input { width: 15px; height: 15px; margin: 0; flex: 0 0 auto; }
.ai-approval-blockers { display: grid; gap: 6px; margin-top: 8px; }
.guard-grid { display: grid; grid-template-columns: repeat(4,1fr); gap: 8px; margin-top: 10px; }
.guard-grid span { display: flex; align-items: center; gap: 8px; min-height: var(--touch-target); padding: 9px; border: 1px solid var(--primary-border); border-radius: var(--radius-control); color: var(--primary); background: var(--primary-soft); font-size: var(--font-size-caption); line-height: 1.4; }
.guard-grid span.blocked { border-color: #fecaca; color: #dc2626; background: #fff7f7; }
.confidence-note { display: flex; gap: 7px; margin: 10px 0 0; padding: 9px 10px; border: 1px solid var(--warning); border-radius: 5px; color: var(--warning-strong); background: var(--surface-warning); font-size: var(--font-size-caption); line-height: 1.5; }
.approval-actions { display: grid; grid-template-columns: 1.2fr 1fr 1.2fr; gap: 10px; margin-top: 12px; }
.approval-actions button { display: inline-flex; align-items: center; justify-content: center; gap: 6px; min-height: var(--touch-target); border: 1px solid var(--primary); border-radius: var(--radius-control); color: var(--primary); background: var(--surface); font-size: var(--font-size-body); font-weight: var(--font-weight-semibold); cursor: pointer; }
.approval-actions button:focus { outline: 2px solid var(--primary); outline-offset: 2px; }
.approval-actions button.primary { color: #fff; background: #2563eb; }
.approval-actions button.danger { border-color: #ef4444; color: #dc2626; }
.approval-actions button:disabled { border-color: #dbe3ed; color: #94a3b8; background: #eef2f7; cursor: not-allowed; }
.ai-reconfirm { display: grid; gap: 8px; margin-top: 12px; padding-top: 12px; border-top: 1px solid #e7edf6; }
.ai-reconfirm h3 { margin: 0; color: var(--text-title); font-size: var(--font-size-body); }
.ai-reconfirm button { min-height: var(--touch-target); border: 0; border-radius: var(--radius-control); color: #fff; background: var(--primary); font-size: var(--font-size-body); }
.runtime-rail { padding: 14px 10px; background: var(--surface); }
.runtime-heading { margin: -4px -2px 0; padding: 10px 8px 12px; border-bottom: 1px solid var(--border); border-radius: 6px 6px 0 0; background: var(--surface-muted); }
.runtime-heading span { color: var(--text-muted); font-size: var(--font-size-caption); white-space: nowrap; }
.runtime-heading b { color: #059669; }
.runtime-timeline { margin: 0; padding: 0; list-style: none; }
.runtime-timeline li { position: relative; display: grid; grid-template-columns: 28px minmax(88px,.8fr) minmax(0,1.25fr); min-height: 84px; border-top: 1px solid var(--border); }
.runtime-timeline li.current { background: var(--surface-selected); box-shadow: inset 3px 0 0 var(--primary); }
.runtime-timeline li::after { position: absolute; top: 32px; bottom: -8px; left: 11px; width: 1px; background: #dce5f1; content: ""; }
.runtime-timeline li:last-child::after { display: none; }
.runtime-node { z-index: 1; display: grid; place-items: center; width: 18px; height: 18px; margin: 12px 0 0 2px; border-radius: 50%; color: #94a3b8; background: #fff; }
.runtime-timeline li.done .runtime-node { color: #10b981; }
.runtime-timeline li.current .runtime-node { color: #2563eb; }
.runtime-timeline li.failed .runtime-node { color: #ef4444; }
.runtime-label { padding: 11px 6px 8px 0; }
.runtime-label strong,
.runtime-label time { display: block; }
.runtime-label strong { color: var(--text-title); font-size: var(--font-size-body); }
.runtime-label time { margin-top: 4px; color: var(--text-muted); font-size: var(--font-size-caption); }
.runtime-timeline dl { margin: 0; padding: 9px 5px 7px 8px; border-left: 1px solid #edf2f8; }
.runtime-timeline dl div { display: grid; grid-template-columns: minmax(72px,.9fr) minmax(0,1.1fr); gap: 6px; margin: 0 0 7px; font-size: var(--font-size-caption); line-height: 1.4; }
.runtime-timeline dt { color: #64748b; }
.runtime-timeline dd { margin: 0; color: #475569; overflow-wrap: anywhere; }
.runtime-timeline dd.success { color: #059669; }
.cost-card { margin-top: 12px; padding: 12px; border: 1px solid #e7edf6; border-radius: 7px; }
.cost-card h3 { margin: 0 0 10px; color: var(--text-title); font-size: var(--font-size-body); }
.cost-card div { display: flex; justify-content: space-between; gap: 8px; color: var(--text-muted); font-size: var(--font-size-caption); }
.cost-card b { color: #475569; }
.execution-note { display: flex; gap: 8px; margin: 10px 0 0; padding: 10px; border: 1px solid var(--success); border-radius: 6px; color: var(--success-strong); background: var(--surface-success); font-size: var(--font-size-caption); line-height: 1.5; }
.execution-note strong { color: #047857; }

@media (min-width: 1321px) and (min-height: 900px) {
  .preview-panel { padding: 10px 12px; }
  .tool-preview { margin-top: 8px; padding: 10px; }
  .tool-preview > header { margin-bottom: 6px; }
  .value-diff > * { padding: 5px 8px; }
  .proposal-facts { margin-top: 8px; }
  .proposal-facts div { min-height: 31px; }
  .proposal-facts dt,
  .proposal-facts dd { padding: 5px 8px; }
  .evidence-meta { margin-top: 6px; }
  .approval-flow { margin: 6px 0 4px; }
  .approval-attestation { padding: 6px 8px; }
  .ai-approval-blockers { gap: 4px; margin-top: 6px; }
  .guard-grid { gap: 6px; margin-top: 6px; }
  .guard-grid span { padding: 5px 8px; }
  .confidence-note { margin-top: 4px; padding: 5px 8px; }
  .approval-actions { margin-top: 4px; }
}

@media (max-width: 1320px) {
  .approval-workbench { grid-template-columns: minmax(250px,.75fr) minmax(460px,1.45fr); }
  .runtime-rail { grid-column: 1 / -1; }
  .runtime-timeline { display: grid; grid-template-columns: repeat(2,1fr); gap: 0 16px; }
}
@media (max-width: 900px) {
  .approval-workbench { grid-template-columns: 1fr; }
  .runtime-rail { grid-column: auto; }
  .guard-grid { grid-template-columns: repeat(2,1fr); }
}
@media (max-width: 768px) {
  .approval-page { min-width: 0; overflow-wrap: anywhere; }
  .governance-tabs { flex-wrap: wrap; min-width: 0; min-height: 44px; gap: 4px 12px; }
  .governance-tabs a { min-width: 0; min-height: 44px; flex: 1 1 120px; justify-content: center; overflow-wrap: anywhere; text-align: center; }
  .approval-workbench { grid-template-columns: minmax(0, 1fr); }
  .rail-heading,
  .preview-heading,
  .runtime-heading { flex-wrap: wrap; }
  .rail-heading > *,
  .preview-heading > *,
  .runtime-heading > * { min-width: 0; }
  .rail-heading select { width: 100%; min-width: 0; min-height: 44px; max-width: 100%; }
  .proposal-select { min-height: 88px; }
  .proposal-title-row,
  .proposal-meta,
  .title-line { flex-wrap: wrap; }
  .value-diff { grid-template-columns: 64px minmax(0, 1fr) minmax(0, 1fr); }
  .proposal-facts { grid-template-columns: 1fr; }
  .proposal-facts div { border-top: 1px solid #e7edf6 !important; border-left: 0 !important; }
  .proposal-facts div:first-child { border-top: 0 !important; }
  .proposal-pagination { flex-wrap: wrap; }
  .proposal-pagination button { width: 44px; height: 44px; }
  .approval-flow { padding: 0; }
  .approval-flow li > span { width: 30px; height: 30px; }
  .approval-flow li:not(.flow-line) { flex-direction: column; justify-content: center; gap: 4px; text-align: center; }
  .approval-flow p { display: grid; justify-items: center; }
  .approval-flow small { line-height: 1.35; }
  .hash-disclosure summary { min-height: var(--touch-target); }
  .guard-grid,
  .approval-actions,
  .runtime-timeline { grid-template-columns: 1fr; }
  .approval-actions button,
  .ai-reconfirm button { min-height: 44px; white-space: normal; overflow-wrap: anywhere; }
}
</style>
