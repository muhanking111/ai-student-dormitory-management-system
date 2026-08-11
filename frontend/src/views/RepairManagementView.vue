<template>
  <section class="repair-page" aria-label="维修工单工作台">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <p v-if="ai.error" class="repair-page-alert" role="alert">{{ ai.error }}</p>
    <div class="panel management-toolbar repair-toolbar">
      <div class="operations-filters">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索单号、报修人或位置" @press-enter="search" />
        <a-select v-model:value="filters.type" allow-clear placeholder="报修类型">
          <a-select-option value="水电维修">水电维修</a-select-option><a-select-option value="家具维修">家具维修</a-select-option><a-select-option value="门窗维修">门窗维修</a-select-option>
        </a-select>
        <a-select v-model:value="filters.status" allow-clear placeholder="报修状态">
          <a-select-option value="待处理">待处理</a-select-option><a-select-option value="处理中">处理中</a-select-option><a-select-option value="已完成">已完成</a-select-option>
        </a-select>
        <a-button type="primary" @click="search"><template #icon><SearchOutlined /></template>查询</a-button>
        <a-button @click="resetFilters"><template #icon><ReloadOutlined /></template>重置</a-button>
      </div>
      <a-button v-if="auth.hasPermission('repair:write')" type="primary" @click="openCreate"><template #icon><PlusOutlined /></template>新增报修</a-button>
    </div>
    <div class="repair-workspace-grid">
      <section class="panel repair-list-panel" aria-label="报修列表">
        <div class="repair-section-title">
          <h2>报修列表 <small>（共 {{ store.repairTotal }} 条）</small></h2>
          <span>选择工单查看详情</span>
        </div>
        <a-table
          class="repair-desktop-table"
          row-key="id"
          size="middle"
          table-layout="fixed"
          :columns="columns"
          :data-source="store.repairOrders"
          :loading="store.loading"
          :pagination="pagination"
          :custom-row="repairRowProps"
          :row-class-name="repairRowClass"
          @change="handleTableChange"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'selection'">
              <button
                type="button"
                class="repair-row-selector"
                :class="{ 'is-selected': selectedOrder?.id === record.id }"
                :aria-label="`选择工单 ${record.code}`"
                :aria-pressed="selectedOrder?.id === record.id"
                @click.stop="selectOrder(record)"
              >
                <span aria-hidden="true"></span>
              </button>
            </template>
            <template v-else-if="column.key === 'code'">
              <span class="repair-code" :title="record.code" :aria-label="record.code">{{ compactRepairCode(record.code) }}</span>
            </template>
            <template v-else-if="column.key === 'date'">
              <span class="repair-date" :title="record.date">{{ compactRepairDate(record.date) }}</span>
            </template>
            <template v-else-if="column.key === 'status'"><a-tag :color="statusColor(record.status)">{{ record.status }}</a-tag></template>
            <template v-else-if="column.key === 'assignee'">{{ assigneeName(record.assigneeUserId) }}</template>
            <template v-else-if="column.key === 'actions'">
              <div class="repair-row-actions">
                <a-button type="link" size="small" @click.stop="showDetail(record)">查看</a-button>
              </div>
            </template>
          </template>
        </a-table>
        <div class="repair-mobile-list" aria-label="移动端报修列表">
          <button
            v-for="order in store.repairOrders"
            :key="order.id"
            type="button"
            :aria-pressed="selectedOrder?.id === order.id"
            @click="selectOrder(order)"
          >
            <strong :title="order.code" :aria-label="order.code">{{ compactRepairCode(order.code) }}</strong>
            <span>{{ order.location }} · {{ order.type }}</span>
            <a-tag :color="statusColor(order.status)">{{ order.status }}</a-tag>
          </button>
          <a-empty v-if="!store.loading && store.repairOrders.length === 0" description="暂无数据" />
        </div>
      </section>

      <div class="repair-detail-stack">
        <section class="panel repair-detail-panel" aria-label="工单详情">
          <div class="repair-section-title">
            <h2>工单详情 <a-tag v-if="selectedOrder" :color="statusColor(selectedOrder.status)">{{ selectedOrder.status }}</a-tag></h2>
            <span v-if="selectedOrder">数据截至 {{ selectedOrder.date }}</span>
          </div>
          <template v-if="selectedOrder">
            <dl class="repair-detail-grid">
              <div class="repair-detail-wide"><dt>报修单号</dt><dd :title="selectedOrder.code" :aria-label="selectedOrder.code">{{ compactRepairCode(selectedOrder.code) }}</dd></div>
              <div><dt>报修位置</dt><dd>{{ selectedOrder.location }}</dd></div>
              <div><dt>报修类型</dt><dd>{{ selectedOrder.type }}</dd></div>
              <div><dt>报修时间</dt><dd>{{ selectedOrder.date }}</dd></div>
              <div><dt>维修人员</dt><dd>{{ assigneeName(selectedOrder.assigneeUserId) }}</dd></div>
              <div class="repair-detail-wide"><dt>报修人</dt><dd>{{ selectedOrder.reporter }}</dd></div>
            </dl>
            <p class="repair-description"><strong>问题描述：</strong>{{ selectedOrder.description || '暂无补充描述' }}</p>
            <div class="repair-detail-footer">
              <div class="repair-attachments" aria-label="工单附件数据状态">
                <div
                  v-if="visualEvidenceAttachments.length"
                  class="repair-attachment-preview"
                  role="group"
                  :aria-label="visualEvidenceAttachmentsAriaLabel"
                  data-evidence-source="deterministic-fixture"
                >
                  <div class="repair-attachment-preview-grid">
                    <figure v-for="attachment in visualEvidenceAttachments" :key="attachment.src" class="repair-attachment-thumbnail">
                      <img data-testid="repair-attachment-thumbnail" :src="attachment.src" :alt="attachment.alt" />
                      <figcaption>{{ attachment.label }}</figcaption>
                    </figure>
                  </div>
                </div>
                <div v-else class="repair-attachment-state" data-testid="repair-attachment-empty" role="status" aria-label="附件数据未提供">
                  <span class="repair-attachment-visual" aria-hidden="true"><FileImageOutlined /></span>
                  <p><strong>未提供附件</strong><small>当前业务合同未提供附件数据</small></p>
                </div>
                <button
                  v-if="auth.hasPermission('repair:write')"
                  type="button"
                  class="repair-attachment-entry"
                  aria-describedby="repair-attachment-help"
                  @click="openHandle(selectedOrder, $event)"
                >
                  补充工单记录
                </button>
                <span id="repair-attachment-help" class="sr-only">当前页面没有附件上传接口，此按钮仅打开现有维修记录流程</span>
              </div>
              <div class="repair-detail-actions" aria-label="工单操作">
                <a-button v-if="canUseAiTriage" @click="openAiTriage(selectedOrder)"><ThunderboltOutlined />AI 分诊</a-button>
                <a-button v-if="isAdmin && selectedOrder.status !== '已完成'" @click="openAssign(selectedOrder, $event)"><TeamOutlined />指派</a-button>
                <a-button v-if="auth.hasPermission('repair:write')" type="primary" @click="openHandle(selectedOrder, $event)">
                  <SafetyCertificateOutlined />{{ selectedOrder.status === '已完成' ? '补充记录' : '处理' }}
                </a-button>
              </div>
            </div>
          </template>
          <a-empty v-else description="请选择一条维修工单" />
        </section>

        <section ref="triageRegionRef" class="panel repair-ai-panel" aria-label="维修智能分诊">
          <div class="repair-section-title">
            <h2>AI 分诊建议 <small>仅供人工参考</small></h2>
            <span v-if="triageResult && triageRunInvalid">{{ triageStateLabel }} · 本次未生成可依赖建议</span>
            <span v-else-if="triageResult">{{ triageStateLabel }} · 数据截至 {{ triageResult.evidence.asOf ? triageResult.evidence.asOf.replace('T', ' ').slice(0, 16) : '不可用' }} · 引用来源 {{ availableCitationCount }}</span>
          </div>
          <p v-if="selectedOrder && !canUseAiTriage" class="repair-triage-blocker" role="status">当前账号无权使用维修 AI 分诊，工单数据仍可按原流程处理。</p>
          <div v-if="selectedOrder && !triageResult" class="repair-ai-empty">
            <p>对当前工单执行确定性规则与受控模型分诊，生成分类、紧急度、建议组和可审批的指派提案。</p>
            <button v-if="canUseAiTriage" type="button" class="ai-button" :disabled="ai.loading || selectedOrder.status === '已完成'" @click="generateAiTriage">
              {{ ai.loading ? '正在分诊…' : '生成分诊建议' }}
            </button>
          </div>
          <div v-if="triageResult && triageRunInvalid" class="repair-ai-run-invalid" role="status">
            <ExclamationCircleFilled aria-hidden="true" />
            <div>
              <strong>本次分诊未生成可依赖建议</strong>
              <span>{{ aiProposalBlocker }}</span>
            </div>
          </div>
          <template v-else-if="triageResult">
            <div class="repair-ai-metrics">
              <div>
                <span class="repair-metric-icon is-category" aria-hidden="true"><ThunderboltOutlined /></span>
                <p><span>分类</span><strong>{{ triageResult.category }}</strong></p>
              </div>
              <div>
                <span class="repair-metric-icon is-urgency" aria-hidden="true"><ExclamationCircleFilled /></span>
                <p><span>紧急度</span><strong class="repair-urgency" data-testid="repair-urgency">{{ urgencyLabel(triageResult.urgency) }}</strong></p>
              </div>
              <div>
                <span class="repair-metric-icon is-team" aria-hidden="true"><TeamOutlined /></span>
                <p><span>建议维修组</span><strong>{{ triageResult.recommendedTeam }}</strong></p>
              </div>
              <div>
                <span class="repair-metric-icon is-person" aria-hidden="true"><UserOutlined /></span>
                <p><span>建议维修员</span><strong>{{ triageResult.assignmentCandidateName ?? '无合适人员' }}</strong></p>
              </div>
            </div>
            <div class="repair-ai-reason">
              <div><span><ClockCircleOutlined />SLA</span><strong>{{ triageResult.slaSuggestion }}</strong></div>
              <div><span><SafetyCertificateOutlined />置信度</span><strong data-testid="repair-confidence">{{ confidenceLabel(triageResult) }}</strong></div>
              <p><span>理由</span>{{ triageResult.reasoningSummary }}</p>
            </div>
            <div class="repair-ai-meta-row">
              <ul v-if="triageResult.missingInformation.length" class="repair-missing-information" aria-label="待补充信息">
                <li v-for="item in triageResult.missingInformation" :key="item">待补充：{{ item }}</li>
              </ul>
              <AiEvidenceMeta :evidence="triageResult.evidence" />
            </div>
            <p v-if="!canOpenAiProposal" class="repair-triage-blocker" role="status" :data-state="triageBlockerState">{{ aiProposalBlocker }}</p>
          </template>
        </section>

        <section class="panel repair-flow-panel" aria-label="维修审批流程">
          <div class="repair-section-title"><h2>执行流程</h2><span>AI 建议 → 变更预览 → 人工审批</span></div>
          <div class="repair-flow">
            <article><b>1</b><strong>AI 建议</strong><span>基于授权历史和规则生成</span></article>
            <i>→</i>
            <article><b>2</b><strong>变更预览</strong><span>当前：{{ selectedOrder ? assigneeName(selectedOrder.assigneeUserId) : '未选择' }}<br>建议：{{ triageRunInvalid ? '无有效建议' : triageResult?.assignmentCandidateName ?? '待生成' }}</span></article>
            <i>→</i>
            <article><b>3</b><strong>人工审批</strong><span>审批通过后调用现有业务服务</span><button v-if="triageResult" type="button" class="ai-button" :disabled="!canOpenAiProposal" @click="openAiProposal">查看审批提案</button></article>
          </div>
          <AiSafetyState v-if="ai.demoProposalMessage" :message="ai.demoProposalMessage" tone="success" />
        </section>
      </div>
    </div>

    <section class="panel repair-safety-panel" aria-label="安全状态提示">
      <div class="repair-section-title"><h2>安全状态提示</h2><span>所有状态均由服务端事实决定</span></div>
      <div class="repair-safety-grid">
        <article class="is-warning"><span class="repair-safety-icon" aria-hidden="true"><ExclamationCircleFilled /></span><div><strong>置信度较低，需人工核验</strong><span>低于阈值时不能提交审批</span><small>核验工单事实后重新分诊</small></div></article>
        <article class="is-danger"><span class="repair-safety-icon" aria-hidden="true"><StopOutlined /></span><div><strong>暂无合适维修人员</strong><span>请联系管理员调整排班</span><small>候选必须来自真实业务账号</small></div></article>
        <article class="is-info"><span class="repair-safety-icon" aria-hidden="true"><SafetyCertificateOutlined /></span><div><strong>无权限提交审批</strong><span>前端隐藏不替代服务端授权</span><small>需要维修写与审批复核权限</small></div></article>
        <article class="is-success"><span class="repair-safety-icon" aria-hidden="true"><CheckCircleFilled /></span><div><strong>不可再次分诊</strong><span>已完成工单保持终态</span><small>可继续补充维修记录</small></div></article>
        <article><span class="repair-safety-icon" aria-hidden="true"><InfoCircleOutlined /></span><div><strong>暂无可靠来源</strong><span>不生成确定性建议</span><small>引用重新可用后再提交</small></div></article>
      </div>
    </section>
    <a-modal v-model:open="createOpen" title="新增报修" :confirm-loading="store.saving" ok-text="提交" cancel-text="取消" destroy-on-close @ok="submitCreate" :after-close="() => restoreModalFocus('create')">
      <a-form ref="createFormRef" :model="createForm" :rules="createRules" layout="vertical">
        <div class="role-form-grid">
          <a-form-item label="报修人" name="reporter"><a-input v-model:value="createForm.reporter" aria-label="报修人" /></a-form-item>
          <a-form-item label="报修类型" name="type"><a-select v-model:value="createForm.type"><a-select-option value="水电维修">水电维修</a-select-option><a-select-option value="家具维修">家具维修</a-select-option><a-select-option value="门窗维修">门窗维修</a-select-option></a-select></a-form-item>
        </div>
        <a-form-item label="报修位置" name="location"><a-input v-model:value="createForm.location" aria-label="报修位置" /></a-form-item>
        <a-form-item label="报修描述"><a-textarea v-model:value="createForm.description" :rows="3" :maxlength="255" show-count /></a-form-item>
        <a-form-item v-if="isAdmin" label="维修人员">
          <a-select v-model:value="createForm.assigneeUserId" allow-clear show-search option-filter-prop="label" placeholder="可稍后指派">
            <a-select-option v-for="user in repairers" :key="user.id" :value="user.id" :label="user.displayName">{{ user.displayName }}（{{ user.username }}）</a-select-option>
          </a-select>
        </a-form-item>
      </a-form>
    </a-modal>
    <a-modal v-model:open="handleOpen" :title="currentOrder?.status === '已完成' ? '补充维修记录' : '处理报修'" :confirm-loading="store.saving" ok-text="保存处理" cancel-text="取消" destroy-on-close @ok="submitHandle" :after-close="() => restoreModalFocus('handle')">
      <a-form ref="handleFormRef" :model="handleForm" :rules="handleRules" layout="vertical">
        <a-alert message="处理人将自动记录为当前登录用户" type="info" show-icon class="checkout-alert" />
        <div class="role-form-grid">
          <a-form-item label="本次状态" name="status"><a-input :value="nextStatus" aria-label="本次状态" disabled /></a-form-item>
          <a-form-item label="维修费用" name="cost"><a-input-number v-model:value="handleForm.cost" :min="0" :precision="2" class="modal-number-input" /></a-form-item>
        </div>
        <a-form-item label="处理内容" name="content"><a-textarea v-model:value="handleForm.content" :rows="3" :maxlength="255" show-count /></a-form-item>
      </a-form>
    </a-modal>
    <a-modal v-model:open="assignOpen" title="指派维修人员" :confirm-loading="store.saving" ok-text="确认指派" cancel-text="取消" destroy-on-close @ok="submitAssign" :after-close="() => restoreModalFocus('assign')">
      <a-select v-model:value="assignUserId" show-search option-filter-prop="label" placeholder="请选择已启用维修人员" style="width: 100%">
        <a-select-option v-for="user in repairers" :key="user.id" :value="user.id" :label="user.displayName">{{ user.displayName }}（{{ user.username }}）</a-select-option>
      </a-select>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import {
  CheckCircleFilled,
  ClockCircleOutlined,
  ExclamationCircleFilled,
  FileImageOutlined,
  InfoCircleOutlined,
  PlusOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  StopOutlined,
  TeamOutlined,
  ThunderboltOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { message } from 'ant-design-vue'
import type { FormInstance, TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { OperationQuery, RepairOrderInput, RepairRecordInput } from '../api/operations'
import { fetchUsers } from '../api/rbac'
import type { UserAccount } from '../api/rbac'
import { useAuthStore } from '../stores/auth'
import { useOperationsStore } from '../stores/operations'
import type { RepairOrder } from '../types/dormitory'
import type { AiRepairTriageResult } from '../types/ai'
import AiEvidenceMeta from '../components/ai/AiEvidenceMeta.vue'
import AiSafetyState from '../components/ai/AiSafetyState.vue'
import { isAiSurfaceEnabled } from '../api/ai-client'
import { useAiStore } from '../stores/ai'
import { useAiApprovalStore } from '../stores/aiApproval'
import { visualEvidenceMode } from '../utils/visual-evidence-mode'

const auth = useAuthStore()
const store = useOperationsStore()
const ai = useAiStore()
const approval = useAiApprovalStore()
const router = useRouter()
interface VisualEvidenceAttachment {
  src: string
  alt: string
  label: string
}
const visualEvidenceAttachments = ref<VisualEvidenceAttachment[]>([])
const visualEvidenceAttachmentsAriaLabel = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const createOpen = ref(false)
const handleOpen = ref(false)
const assignOpen = ref(false)
const selectedOrder = ref<RepairOrder | null>(null)
const triagingOrder = ref<RepairOrder | null>(null)
const triageRegionRef = ref<HTMLElement>()
const currentOrder = ref<RepairOrder | null>(null)
const assigningOrder = ref<RepairOrder | null>(null)
const assignUserId = ref<number>()
const repairers = ref<UserAccount[]>([])
const createFormRef = ref<FormInstance>()
const handleFormRef = ref<FormInstance>()
const filters = reactive<Omit<OperationQuery, 'page' | 'pageSize'>>({})
const createForm = reactive<RepairOrderInput>({ reporter: '', location: '', type: '水电维修', description: '', assigneeUserId: undefined })
const handleForm = reactive<RepairRecordInput>({ content: '', cost: 0, status: '处理中' })
const columns: TableColumnsType = [
  { title: '', key: 'selection', width: 48, className: 'repair-selection-column' },
  { title: '报修单号', dataIndex: 'code', key: 'code', width: 132 },
  { title: '报修位置', dataIndex: 'location', key: 'location', width: 64, ellipsis: true },
  { title: '报修类型', dataIndex: 'type', key: 'type', width: 64, ellipsis: true },
  { title: '报修时间', dataIndex: 'date', key: 'date', width: 86, ellipsis: true },
  { title: '维修人员', key: 'assignee', width: 68, ellipsis: true },
  { title: '状态', key: 'status', width: 62 },
  { title: '操作', key: 'actions', width: 76, className: 'repair-action-column' },
]
const createRules = { reporter: [{ required: true, message: '请输入报修人' }], location: [{ required: true, message: '请输入报修位置' }], type: [{ required: true, message: '请选择报修类型' }] }
const handleRules = { content: [{ required: true, message: '请输入处理内容' }], cost: [{ required: true, type: 'number', min: 0, message: '维修费用不能小于 0' }] }
const query = computed<OperationQuery>(() => ({ page: currentPage.value, pageSize: pageSize.value, ...filters }))
const pagination = computed<TablePaginationConfig>(() => ({ current: currentPage.value, pageSize: pageSize.value, total: store.repairTotal, showSizeChanger: true, showTotal: (total) => `共 ${total} 条` }))
const isAdmin = computed(() => auth.user?.roleCodes?.includes('ADMIN') ?? false)
const canUseAiTriage = computed(() => isAiSurfaceEnabled() && auth.hasPermission('ai:repair:triage') && auth.hasPermission('repair:read'))
const triageResult = computed<AiRepairTriageResult | null>(() => triagingOrder.value ? ai.repairTriage[triagingOrder.value.id] ?? null : null)
const availableCitationCount = computed(() => triageResult.value?.evidence.citations
  .filter((citation) => citation.access === 'available').length ?? 0)

function confidenceLabel(result: AiRepairTriageResult) {
  if (typeof result.evidence.confidence === 'number') {
    return `${Math.round(result.evidence.confidence * 100)}%`
  }
  if (result.evidence.basis === 'deterministic' && result.evidence.grounded) return '规则确定'
  return '待核验'
}
function urgencyLabel(value: string) {
  return ({ LOW: '低', MEDIUM: '中', HIGH: '高' } as Record<string, string>)[value.toUpperCase()] ?? value
}
function compactRepairCode(value: string) {
  return value.length <= 14 ? value : `${value.slice(0, 6)}…${value.slice(-6)}`
}
function compactRepairDate(value: string) {
  const match = value.match(/^\d{4}-(\d{2})-(\d{2})[T\s](\d{2}):(\d{2})/)
  return match ? `${match[1]}-${match[2]} ${match[3]}:${match[4]}` : value
}
const triageStateLabel = computed(() => {
  const state = triageResult.value?.state
  return state === 'succeeded' ? '已完成' : state === 'degraded' ? '已降级' : state === 'failed' ? '失败' : state === 'timed_out' ? '超时' : '待核验'
})
const triageRunInvalid = computed(() => triageResult.value?.state === 'failed' || triageResult.value?.state === 'timed_out')
const triageProposalState = computed(() => triageResult.value?.proposalState)
const proposalExpiry = computed(() => triageResult.value?.proposalExpiresAt)
const proposalExpired = computed(() => {
  const value = proposalExpiry.value
  if (!value) return false
  const timestamp = Date.parse(value)
  return !Number.isFinite(timestamp) || timestamp <= Date.now()
})
const proposalStateActionable = computed(() => {
  const state = triageProposalState.value
  return state === undefined || state === 'draft' || state === 'pending_approval'
})
const evidenceActionable = computed(() => {
  const evidence = triageResult.value?.evidence
  return Boolean(evidence?.grounded && evidence.asOf && availableCitationCount.value > 0)
})
const canOpenAiProposal = computed(() => Boolean(triagingOrder.value
  && auth.hasPermission('repair:write')
  && auth.hasPermission('ai:approval:review')
  && triagingOrder.value.status !== '已完成'
  && triageResult.value?.state === 'succeeded'
  && triageResult.value?.assignmentCandidateUserId
  && evidenceActionable.value
  && proposalStateActionable.value
  && !proposalExpired.value
  && triageResult.value.proposalId
  && (triageResult.value.evidence.confidence === undefined || triageResult.value.evidence.confidence >= 0.7)))
const aiProposalBlocker = computed(() => {
  if (!canUseAiTriage.value) return '当前账号无权使用维修 AI 分诊'
  if (triagingOrder.value?.status === '已完成') return '已完成工单不可再次分诊'
  if (triageResult.value?.state === 'failed' || triageResult.value?.state === 'timed_out') return '分诊运行失败，请重试或改用人工流程'
  if (triageResult.value?.state === 'degraded') return '分诊已降级，请人工核验后重新获取可靠来源'
  if (triageProposalState.value === 'stale') return '业务数据已变化，当前分诊提案已失效，请重新分诊'
  if (triageProposalState.value === 'expired') return '分诊提案已过期，请刷新业务事实后重新分诊'
  if (proposalExpired.value) return '分诊提案已过期，请刷新业务事实后重新分诊'
  if (!evidenceActionable.value) return '暂无可靠来源或数据时间，不能提交审批'
  if (!triageResult.value?.assignmentCandidateUserId) return '暂无合适维修人员'
  if (!triageResult.value?.proposalId) return '服务端未创建审批提案，不能继续执行'
  if (!auth.hasPermission('repair:write') || !auth.hasPermission('ai:approval:review')) return '当前账号无权提交维修审批'
  if (triageResult.value.evidence.confidence !== undefined && triageResult.value.evidence.confidence < 0.7) return '置信度较低，请人工核验'
  return '置信度较低，请人工核验'
})
const triageBlockerState = computed<'NO_PERMISSION' | 'NO_GROUNDED' | 'STALE' | 'EXPIRED' | 'DEGRADED' | 'FAILED' | 'WARNING'>(() => {
  if (!canUseAiTriage.value || !auth.hasPermission('repair:write') || !auth.hasPermission('ai:approval:review')) return 'NO_PERMISSION'
  if (triageProposalState.value === 'stale') return 'STALE'
  if (triageProposalState.value === 'expired' || proposalExpired.value) return 'EXPIRED'
  if (triageResult.value?.state === 'degraded') return 'DEGRADED'
  if (triageResult.value?.state === 'failed' || triageResult.value?.state === 'timed_out') return 'FAILED'
  if (!evidenceActionable.value) return 'NO_GROUNDED'
  return 'WARNING'
})
const nextStatus = computed<'处理中' | '已完成'>(() => currentOrder.value?.status === '待处理' ? '处理中' : '已完成')
function statusColor(status: RepairOrder['status']) { return ({ 待处理: 'orange', 处理中: 'blue', 已完成: 'green' } as Record<string, string>)[status] ?? 'default' }
function assigneeName(id?: number) { return id ? repairers.value.find((user) => user.id === id)?.displayName ?? (id === auth.user?.id ? auth.user.userName : `用户 #${id}`) : '未指派' }
const sortedRepairCandidates = computed(() => repairers.value
  .filter((user) => user.enabled && user.roles.some((role) => role.code === 'REPAIRER'))
  .slice()
  .sort((left, right) => left.displayName.localeCompare(right.displayName, 'zh-Hans') || left.id - right.id)
  .map((user) => ({ userId: user.id, displayName: user.displayName })))
function redactRepairText(value: string, reporter?: string) {
  let redacted = value
    .replace(/[\w.+-]+@[\w.-]+\.[A-Za-z]{2,}/g, '[已脱敏]')
    .replace(/1[3-9]\d{9}/g, '[已脱敏]')
    .replace(/\b\d{8,18}\b/g, '[已脱敏]')
  const safeReporter = reporter?.trim()
  if (safeReporter) redacted = redacted.split(safeReporter).join('[已脱敏]')
  return redacted.replace(/\s{2,}/g, ' ').trim()
}
async function load() {
  try {
    const selectedId = selectedOrder.value?.id
    await store.loadRepairOrders(query.value)
    selectOrder(store.repairOrders.find((order) => order.id === selectedId) ?? store.repairOrders[0] ?? null)
  } catch (error) { message.error(error instanceof Error ? error.message : '报修数据加载失败') }
}
async function loadRepairers() {
  if (!isAdmin.value) return
  const users: UserAccount[] = []
  let page = 1
  do {
    const result = await fetchUsers({ page, pageSize: 100, enabled: true })
    users.push(...result.records)
    if (users.length >= result.total) break
    page += 1
  } while (page <= 100)
  repairers.value = users.filter((user) => user.enabled && user.roles.some((role) => role.code === 'REPAIRER'))
}
function search() { currentPage.value = 1; void load() }
function resetFilters() { Object.assign(filters, { keyword: undefined, type: undefined, status: undefined }); currentPage.value = 1; void load() }
function handleTableChange(page: TablePaginationConfig) { currentPage.value = page.current ?? 1; pageSize.value = page.pageSize ?? 10; void load() }
type RepairModal = 'create' | 'handle' | 'assign'
const modalReturnFocus: Record<RepairModal, HTMLElement | null> = { create: null, handle: null, assign: null }

function rememberModalTrigger(modal: RepairModal, event?: Event) {
  const eventTarget = event?.currentTarget
  modalReturnFocus[modal] = eventTarget instanceof HTMLElement
    ? eventTarget
    : document.activeElement instanceof HTMLElement ? document.activeElement : null
}

async function restoreModalFocus(modal: RepairModal) {
  const target = modalReturnFocus[modal]
  modalReturnFocus[modal] = null
  await nextTick()
  if (target?.isConnected && !target.hasAttribute('disabled')) target.focus()
}

function openCreate(event?: Event) { rememberModalTrigger('create', event); Object.assign(createForm, { reporter: '', location: '', type: '水电维修', description: '', assigneeUserId: undefined }); createOpen.value = true }
function openHandle(order: RepairOrder, event?: Event) { rememberModalTrigger('handle', event); currentOrder.value = order; Object.assign(handleForm, { content: '', cost: 0, status: order.status === '待处理' ? '处理中' : '已完成' }); handleOpen.value = true }
function openAssign(order: RepairOrder, event?: Event) { rememberModalTrigger('assign', event); assigningOrder.value = order; assignUserId.value = order.assigneeUserId; assignOpen.value = true }
function selectOrder(order: RepairOrder | null) { selectedOrder.value = order; triagingOrder.value = order }
function repairRowProps(order: RepairOrder) { return { onClick: () => selectOrder(order), 'aria-selected': selectedOrder.value?.id === order.id } }
function repairRowClass(order: RepairOrder) { return selectedOrder.value?.id === order.id ? 'repair-row--selected' : '' }
async function openAiTriage(order: RepairOrder) {
  selectOrder(order)
  ai.demoProposalMessage = ''
  await nextTick()
  const scrollBehavior = matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'
  triageRegionRef.value?.scrollIntoView({ behavior: scrollBehavior, block: 'center' })
}
async function generateAiTriage() {
  if (!triagingOrder.value || !canUseAiTriage.value || triagingOrder.value.status === '已完成' || ai.loading) return
  ai.error = null
  try {
    await ai.triage({
      repairId: triagingOrder.value.id,
      status: triagingOrder.value.status,
      descriptionRedacted: redactRepairText(triagingOrder.value.description || triagingOrder.value.type, triagingOrder.value.reporter),
      candidates: sortedRepairCandidates.value,
    })
  } catch (error) {
    const safeMessage = error instanceof Error ? error.message : '维修分诊失败'
    ai.error = safeMessage
    message.error(safeMessage)
  }
}
async function openAiProposal() {
  if (!canOpenAiProposal.value) return
  const id = triageResult.value?.proposalId
  if (!id) return
  try { await approval.selectProposal(id); await router.push('/ai/approvals') }
  catch (error) { message.error(error instanceof Error ? error.message : '审批提案加载失败') }
}
function showDetail(order: RepairOrder) { selectOrder(order) }
async function submitCreate() { await createFormRef.value?.validate(); try { await store.createRepair({ ...createForm }, query.value); createOpen.value = false; message.success('报修单已创建') } catch (error) { message.error(error instanceof Error ? error.message : '报修创建失败') } }
async function submitHandle() {
  if (!currentOrder.value) return
  const orderId = currentOrder.value.id
  const wasCompleted = currentOrder.value.status === '已完成'
  handleForm.status = nextStatus.value
  await handleFormRef.value?.validate()
  try {
    await store.handleRepair(orderId, { ...handleForm }, query.value)
    selectOrder(store.repairOrders.find((order) => order.id === orderId) ?? selectedOrder.value)
    handleOpen.value = false
    message.success(wasCompleted ? '补充记录已保存' : '报修处理已保存')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '报修处理失败')
  }
}
async function submitAssign() { if (!assigningOrder.value || !assignUserId.value) { message.warning('请选择维修人员'); return } try { await store.assignRepair(assigningOrder.value.id, assignUserId.value, query.value); assignOpen.value = false; message.success('维修人员已指派') } catch (error) { message.error(error instanceof Error ? error.message : '维修指派失败') } }
async function loadVisualEvidenceAttachments() {
  if (import.meta.env.DEV && visualEvidenceMode) {
    const outletEvidence = await import('../assets/visual-evidence/repair-outlet-evidence.png')
    const panelEvidence = await import('../assets/visual-evidence/repair-panel-evidence.png')
    visualEvidenceAttachmentsAriaLabel.value = '视觉证据附件样例，不代表生产工单记录'
    visualEvidenceAttachments.value = [
      { src: outletEvidence.default, alt: '插座与线路现场检查附件预览', label: '插座近照' },
      { src: panelEvidence.default, alt: '配电箱现场检查附件预览', label: '配电箱状态' },
    ]
  }
}
onMounted(() => { void load(); void loadRepairers(); void loadVisualEvidenceAttachments() })
</script>

<style scoped>
.repair-page { display: grid; gap: 10px; }
.repair-page-alert,
.repair-triage-blocker {
  margin: 0;
  padding: 10px 12px;
  border: 1px solid #fecaca;
  border-radius: 6px;
  color: #b91c1c;
  background: #fff7f7;
  line-height: 1.5;
}
.repair-toolbar { margin-bottom: 0; padding: 8px 14px; border-radius: 8px; }
.repair-toolbar :deep(.ant-btn),
.repair-toolbar :deep(.ant-input-affix-wrapper),
.repair-toolbar :deep(.ant-select-selector) { min-height: 44px; }
.repair-workspace-grid { display: grid; grid-template-columns: minmax(560px, 1fr) minmax(520px, 1fr); gap: 10px; align-items: start; min-width: 0; }
.repair-workspace-grid > * { min-width: 0; }
.repair-list-panel, .repair-detail-panel, .repair-ai-panel, .repair-flow-panel, .repair-safety-panel { min-width: 0; padding: 10px; border-radius: 8px; }
.repair-detail-panel { min-width: 0; }
.repair-list-panel { min-height: 0; }
.repair-detail-stack { display: grid; gap: 8px; min-width: 0; }
.repair-section-title { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 9px; }
.repair-section-title h2 { margin: 0; color: #111827; font-size: 16px; font-weight: 700; }
.repair-section-title h2 small, .repair-section-title > span { color: #64748b; font-size: 12px; font-weight: 400; }
.repair-page :deep(.ant-tag-orange) { color: var(--warning-strong) !important; }
:deep(.repair-row--selected > td) { background: #eff6ff !important; }
:deep(.repair-row--selected > td:first-child) { box-shadow: inset 3px 0 0 var(--primary); }
:deep(.repair-desktop-table tbody tr) { cursor: pointer; }
:deep(.repair-desktop-table .ant-table-thead > tr > th),
:deep(.repair-desktop-table .ant-table-tbody > tr > td) { padding-inline: 6px !important; }
:deep(.repair-desktop-table .repair-selection-column) { padding-inline: 0 !important; }
:deep(.repair-desktop-table .repair-action-column) { padding-inline: 4px !important; }
:deep(.repair-desktop-table .ant-table-tbody > tr > td) {
  height: 50px;
  padding-top: 0 !important;
  padding-bottom: 0 !important;
}
.repair-row-selector { min-width: 44px; min-height: 44px; display: grid; place-items: center; padding: 0; border: 0; color: var(--primary); background: transparent; cursor: pointer; }
.repair-row-selector span { width: 15px; height: 15px; border: 1.5px solid #b7c4d8; border-radius: 50%; background: #fff; }
.repair-row-selector.is-selected span { border: 4px solid var(--primary); box-shadow: 0 0 0 3px var(--primary-soft); }
.repair-code { display: inline-block; max-width: 100%; overflow: hidden; color: var(--text-strong); font-variant-numeric: tabular-nums; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle; }
.repair-date { display: inline-block; max-width: 100%; overflow: hidden; font-variant-numeric: tabular-nums; text-overflow: ellipsis; white-space: nowrap; vertical-align: middle; }
.repair-row-actions { display: flex; align-items: center; justify-content: center; white-space: nowrap; }
.repair-row-actions :deep(button) {
  min-width: 44px;
  min-height: 44px !important;
  padding-inline: 6px !important;
  font-size: 13px;
}
.repair-mobile-list { display: none; }
.repair-detail-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 7px 20px; margin: 0; }
.repair-detail-grid div { display: grid; grid-template-columns: 82px 1fr; gap: 8px; }
.repair-detail-grid .repair-detail-wide { grid-column: 1 / -1; }
.repair-detail-grid dt { color: #64748b; }
.repair-detail-grid dd { min-width: 0; margin: 0; color: #1f2937; font-weight: 600; white-space: normal; overflow-wrap: anywhere; }
.repair-description { margin: 8px 0 6px; line-height: 1.45; white-space: normal; overflow-wrap: anywhere; }
.repair-detail-footer { min-width: 0; display: flex; align-items: stretch; justify-content: space-between; gap: 8px; }
.repair-attachments { min-width: 0; display: flex; align-items: stretch; flex: 1 1 auto; gap: 8px; }
.repair-attachment-preview { min-width: 0; min-height: 60px; display: block; flex: 1 1 auto; padding: 3px; border: 1px solid var(--primary-border); border-radius: 7px; background: var(--surface-muted); }
.repair-attachment-preview-grid { min-width: 0; display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; }
.repair-attachment-thumbnail { position: relative; min-width: 0; height: 52px; margin: 0; overflow: hidden; border: 1px solid #cbd5e1; border-radius: 6px; background: #dbe4ee; }
.repair-attachment-thumbnail img { display: block; width: 100%; height: 100%; object-fit: cover; }
.repair-attachment-thumbnail figcaption { position: absolute; right: 4px; bottom: 4px; left: 4px; overflow: hidden; padding: 2px 5px; border-radius: 3px; color: #fff; background: rgba(15, 23, 42, .78); font-size: 12px; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.repair-attachment-state { min-width: 0; min-height: 56px; display: inline-flex; align-items: center; flex: 1 1 auto; gap: 10px; padding: 7px 10px; border: 1px dashed #cbd5e1; border-radius: 7px; color: var(--text-muted); background: var(--surface-muted); font-size: 12px; }
.repair-attachment-visual { width: 40px; height: 40px; display: grid; place-items: center; flex: 0 0 auto; border-radius: 7px; color: var(--primary); background: var(--primary-soft); font-size: 20px; }
.repair-attachment-state strong { color: #475569; }
.repair-attachment-state p { min-width: 0; display: grid; gap: 1px; margin: 0; }
.repair-attachment-state small { color: var(--text-muted); font-size: 12px; }
.repair-attachment-entry { min-width: 112px; min-height: 44px; flex: 0 0 auto; padding: 0 12px; border: 1px solid var(--primary-border); border-radius: 7px; color: var(--primary-hover); background: var(--primary-soft); cursor: pointer; font-size: 13px; font-weight: 600; overflow-wrap: normal; white-space: nowrap; }
.repair-attachment-entry:hover { border-color: #93c5fd; background: #dbeafe; }
.repair-attachment-entry:focus-visible { outline: 3px solid rgba(37, 99, 235, .25); outline-offset: 2px; }
.repair-detail-actions { display: flex; align-items: stretch; flex: 0 0 auto; gap: 8px; flex-wrap: nowrap; margin-top: 0; }
.repair-detail-actions :deep(.ant-btn) { min-height: 44px; }
.repair-ai-panel { display: grid; gap: 6px; padding: 10px; }
.repair-ai-panel > .repair-section-title { margin-bottom: 0; }
.repair-ai-panel .ai-button,
.repair-flow .ai-button { min-height: 44px; }
.repair-ai-empty { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px; border: 1px dashed #bfdbfe; border-radius: 8px; background: #f8fbff; }
.repair-ai-empty p { margin: 0; color: #64748b; line-height: 1.6; }
.repair-ai-metrics { display: grid; grid-template-columns: repeat(4, 1fr); border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.repair-ai-metrics > div { min-height: 64px; display: flex; align-items: center; gap: 9px; padding: 7px 9px; border-right: 1px solid var(--border); }
.repair-ai-metrics div:last-child { border-right: 0; }
.repair-ai-metrics p { min-width: 0; display: grid; gap: 3px; margin: 0; }
.repair-metric-icon { width: 36px; height: 36px; display: grid; place-items: center; flex: 0 0 auto; border-radius: 50%; color: var(--primary); background: var(--primary-soft); font-size: 18px; }
.repair-metric-icon.is-urgency { color: var(--danger); background: var(--surface-danger); }
.repair-metric-icon.is-team { color: #7c3aed; background: #f5f3ff; }
.repair-metric-icon.is-person { color: var(--info-strong); background: #ecfeff; }
.repair-ai-metrics span, .repair-ai-reason span { color: #64748b; font-size: 12px; }
.repair-ai-metrics strong { color: #111827; font-size: 15px; }
.repair-ai-metrics .repair-urgency { color: #ef4444; }
.repair-ai-reason { display: grid; grid-template-columns: minmax(140px, 1fr) 96px minmax(0, 2fr); gap: 0; padding: 6px; border: 1px solid var(--border); border-radius: 8px; }
.repair-ai-reason div, .repair-ai-reason p { margin: 0; display: grid; gap: 4px; }
.repair-ai-reason div { padding-inline: 8px; border-right: 1px solid var(--border); }
.repair-ai-reason p { padding-inline: 10px 4px; line-height: 1.4; }
.repair-ai-reason span { display: inline-flex; align-items: center; gap: 5px; }
.repair-missing-information {
  display: grid;
  gap: 2px;
  margin: 0;
  padding: 4px 10px 4px 26px;
  border: 1px solid #fde68a;
  border-radius: 6px;
  color: #92400e;
  background: #fffbeb;
  font-size: 12px;
  line-height: 1.45;
}
.repair-ai-meta-row { min-width: 0; display: grid; gap: 6px; }
.repair-ai-run-invalid {
  min-height: 118px;
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 18px;
  border: 1px solid #fecaca;
  border-radius: 8px;
  color: #991b1b;
  background: #fff7f7;
}
.repair-ai-run-invalid > svg { flex: 0 0 auto; color: #dc2626; font-size: 28px; }
.repair-ai-run-invalid > div { display: grid; gap: 5px; }
.repair-ai-run-invalid strong { color: #7f1d1d; font-size: 16px; }
.repair-ai-run-invalid span { color: #9f2f2f; font-size: 14px; line-height: 1.55; }
.repair-ai-panel :deep(.ai-evidence) { min-height: 44px; display: flex; align-items: center; flex-wrap: wrap; gap: 6px; line-height: 1.35; }
.repair-ai-panel :deep(.ai-citations) { margin-left: auto; }
.repair-ai-panel :deep(.ai-citations summary) { min-height: 44px; padding-inline: 4px; }
.repair-ai-panel :deep(.ai-citation-link) { min-height: 44px; padding: 6px 0; }
.repair-triage-blocker { border-color: #f5d2a8; color: #a34b0a; background: #fffaf4; font-size: 12px; }
.repair-flow { display: grid; grid-template-columns: 1fr 28px 1fr 28px 1fr; align-items: stretch; }
.repair-flow article { display: grid; grid-template-columns: 28px 1fr; gap: 3px 8px; align-content: start; padding: 8px 10px; border: 1px solid #dbe5f5; border-radius: 8px; background: #f8fbff; }
.repair-flow article b { width: 22px; height: 22px; display: grid; place-items: center; grid-row: 1 / span 2; border-radius: 50%; color: #fff; background: #2563eb; font-size: 12px; }
.repair-flow article span { color: #64748b; font-size: 12px; line-height: 1.55; }
.repair-flow article button { grid-column: 2; margin-top: 6px; }
.repair-flow > i { display: grid; place-items: center; color: #94a3b8; font-style: normal; font-size: 22px; }
.repair-safety-grid { display: grid; grid-template-columns: repeat(5, 1fr); gap: 8px; }
.repair-safety-grid article { min-height: 88px; display: grid; grid-template-columns: 36px minmax(0, 1fr); align-content: center; gap: 9px; padding: 10px 11px; border: 1px solid #dbe5f5; border-radius: 8px; background: var(--surface-muted); }
.repair-safety-grid article > div { min-width: 0; display: grid; gap: 2px; }
.repair-safety-icon { width: 36px; height: 36px; display: grid; place-items: center; align-self: start; border-radius: 50%; color: var(--text-muted); background: #eef2f7; font-size: 18px; }
.repair-safety-grid strong { color: #475569; font-size: 13px; }
.repair-safety-grid article > div > span { color: var(--text-muted); font-size: 12px; }
.repair-safety-grid small { color: var(--text-muted); font-size: 12px; }
.repair-safety-grid .is-warning { border-color: #fde68a; background: #fffbeb; }
.repair-safety-grid .is-danger { border-color: #fecaca; background: #fef2f2; }
.repair-safety-grid .is-info { border-color: #bfdbfe; background: #eff6ff; }
.repair-safety-grid .is-success { border-color: #a7f3d0; background: #ecfdf5; }
.repair-safety-grid .is-warning .repair-safety-icon { color: var(--warning-strong); background: #fef3c7; }
.repair-safety-grid .is-danger .repair-safety-icon { color: var(--danger-strong); background: #fee2e2; }
.repair-safety-grid .is-info .repair-safety-icon { color: var(--primary-hover); background: #dbeafe; }
.repair-safety-grid .is-success .repair-safety-icon { color: var(--success-strong); background: #d1fae5; }
@media (max-width: 1440px) {
  .repair-workspace-grid { grid-template-columns: 1fr; }
  .repair-safety-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (min-width: 1441px) {
  .repair-page {
    grid-template-rows: auto minmax(0, 1fr) auto;
    min-height: 0;
    height: calc(100vh - var(--header-height) - 20px);
    overflow: hidden;
  }
  .repair-workspace-grid {
    min-height: 0;
    max-height: 100%;
    overflow: hidden;
  }
  .repair-list-panel,
  .repair-detail-stack {
    min-height: 0;
    max-height: 100%;
  }
  .repair-detail-stack {
    overflow-y: auto;
    overscroll-behavior: contain;
    scrollbar-gutter: stable;
    padding-right: 2px;
    gap: 8px;
  }
  .repair-list-panel {
    overflow: hidden;
  }
  .repair-detail-panel { padding-block: 8px; }
  .repair-detail-grid { gap: 6px 20px; }
  .repair-detail-grid .repair-detail-wide { grid-column: auto; }
  .repair-description { margin-block: 7px 5px; }
  .repair-ai-panel,
  .repair-flow-panel { padding-block: 8px; }
  .repair-ai-panel { gap: 6px; }
  .repair-ai-meta-row { display: flex; align-items: stretch; gap: 8px; }
  .repair-ai-meta-row .repair-missing-information { min-height: 44px; flex: 1 1 35%; align-content: center; }
  .repair-ai-meta-row :deep(.ai-evidence) { min-width: 0; flex: 1 1 65%; }
  .repair-flow-panel > .repair-section-title { margin-bottom: 6px; }
  .repair-flow article { min-height: 88px; padding-block: 7px; }
  .repair-flow article span { line-height: 1.5; }
  .repair-flow article button { width: 100%; margin-top: 2px; padding-inline: 8px; white-space: nowrap; }
  .repair-safety-panel { padding-block: 9px; }
  .repair-safety-panel .repair-section-title { margin-bottom: 6px; }
  .repair-safety-grid article { min-height: 116px; }
  .repair-attachment-state { min-height: 80px; }
  :deep(.repair-desktop-table .ant-table-body) {
    max-height: calc(100vh - 510px) !important;
    overflow-y: auto !important;
  }
}
@media (min-width: 1441px) and (max-height: 1050px) {
  .repair-page { gap: 8px; }
  .repair-detail-stack { gap: 5px; }
  .repair-detail-panel { min-height: 230px; padding-block: 8px; }
  .repair-ai-panel { padding-block: 5px; }
  .repair-flow-panel { padding-block: 3px; }
  .repair-detail-panel > .repair-section-title { margin-bottom: 6px; }
  .repair-detail-grid { gap-block: 6px; }
  .repair-description { margin-block: 6px 5px; line-height: 1.45; }
  .repair-ai-panel { gap: 3px; }
  .repair-ai-reason { padding-block: 3px; }
  .repair-ai-meta-row .repair-missing-information { flex-basis: 35%; }
  .repair-ai-meta-row :deep(.ai-evidence) { flex-basis: 65%; }
  .repair-flow-panel > .repair-section-title { margin-bottom: 1px; }
  .repair-flow article { grid-template-columns: 24px minmax(0, 1fr); gap: 2px 6px; min-height: 96px; padding: 5px 8px; }
  .repair-flow article button { margin-top: 0; }
}
@media (max-width: 768px) {
  .repair-page { grid-template-columns: minmax(0, 1fr); min-width: 0; }
  .repair-workspace-grid,
  .repair-detail-stack { grid-template-columns: minmax(0, 1fr); min-width: 0; width: 100%; }
  .repair-list-panel,
  .repair-detail-panel,
  .repair-ai-panel,
  .repair-flow-panel,
  .repair-safety-panel { min-width: 0; }
  .repair-toolbar { width: 100%; min-width: 0; padding: 12px; }
  .repair-toolbar .operations-filters { grid-template-columns: minmax(0, 1fr); min-width: 0; }
  .repair-toolbar .operations-filters > * { width: 100%; min-width: 0; min-height: 44px; }
  .repair-toolbar .operations-filters :deep(.ant-input-affix-wrapper),
  .repair-toolbar .operations-filters :deep(.ant-select-selector) { min-height: 44px; display: flex; align-items: center; }
  .repair-toolbar .operations-filters :deep(.ant-select-selection-search-input) { height: 42px; }
  .repair-desktop-table { display: none; }
  .repair-mobile-list { display: grid; grid-template-columns: minmax(0, 1fr); min-width: 0; gap: 8px; }
  .repair-mobile-list button { width: 100%; min-height: 72px; display: grid; grid-template-columns: minmax(0, 1fr) auto; min-width: 0; gap: 4px 8px; padding: 12px; text-align: left; border: 1px solid #e7edf6; border-radius: 8px; color: #374151; background: #fff; }
  .repair-mobile-list button[aria-pressed="true"] { border-color: #2563eb; background: #eff6ff; }
  .repair-mobile-list button strong { grid-column: 1 / -1; min-width: 0; overflow-wrap: anywhere; }
  .repair-mobile-list button > span:not(.ant-tag) { grid-column: 1; min-width: 0; color: var(--text-muted); font-size: 12px; overflow-wrap: anywhere; }
  .repair-mobile-list button :deep(.ant-tag) { grid-column: 2; justify-self: end; margin-inline-end: 0; }
  .repair-section-title { min-width: 0; flex-wrap: wrap; }
  .repair-section-title h2,
  .repair-section-title > span { min-width: 0; overflow-wrap: anywhere; }
  .repair-detail-grid div { grid-template-columns: 74px minmax(0, 1fr); min-width: 0; }
  .repair-detail-grid dd,
  .repair-description { overflow: visible; white-space: normal; overflow-wrap: anywhere; }
  .repair-detail-grid .repair-detail-wide { grid-column: 1 / -1; }
  .repair-detail-footer { flex-direction: column; }
  .repair-attachments { min-width: 0; flex-wrap: wrap; }
  .repair-attachment-preview { width: 100%; }
  .repair-detail-actions { flex-wrap: wrap; }
  .repair-attachment-entry { min-height: 44px; }
  .repair-ai-empty { flex-direction: column; min-width: 0; align-items: stretch; }
  .repair-ai-empty > * { min-width: 0; }
  .repair-detail-grid, .repair-ai-metrics, .repair-ai-reason, .repair-safety-grid { grid-template-columns: 1fr; }
  .repair-ai-metrics > div { border-right: 0; border-bottom: 1px solid var(--border); }
  .repair-ai-metrics > div:last-child { border-bottom: 0; }
  .repair-ai-reason div { padding-block: 8px; border-right: 0; border-bottom: 1px solid var(--border); }
  .repair-ai-reason p { padding-block: 8px; }
  .repair-flow { grid-template-columns: 1fr; gap: 8px; }
  .repair-flow article { grid-template-columns: 28px minmax(0, 1fr); min-width: 0; }
  .repair-flow > i { transform: rotate(90deg); }
}
</style>
