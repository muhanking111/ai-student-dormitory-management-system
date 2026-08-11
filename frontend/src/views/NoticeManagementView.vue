<template>
  <section v-if="!isCreatePage" class="panel management-page">
    <a-alert v-if="store.error" class="page-alert" :message="store.error" type="error" show-icon />
    <div class="management-toolbar">
      <div class="operations-filters">
        <a-input v-model:value="filters.keyword" allow-clear placeholder="搜索公告标题" @press-enter="search" />
        <a-select v-model:value="filters.type" allow-clear placeholder="公告类型">
          <a-select-option value="安全卫生">安全卫生</a-select-option>
          <a-select-option value="宿舍通知">宿舍通知</a-select-option>
          <a-select-option value="全校学生">全校学生</a-select-option>
        </a-select>
        <a-select v-model:value="filters.status" allow-clear placeholder="公告状态">
          <a-select-option value="草稿">草稿</a-select-option>
          <a-select-option value="已发布">已发布</a-select-option>
          <a-select-option value="已撤回">已撤回</a-select-option>
        </a-select>
        <a-button type="primary" @click="search"><template #icon><SearchOutlined /></template>查询</a-button>
        <a-button @click="resetFilters"><template #icon><ReloadOutlined /></template>重置</a-button>
      </div>
      <a-button v-if="auth.hasPermission('notice:write')" type="primary" @click="openCreate">
        <template #icon><PlusOutlined /></template>发布公告
      </a-button>
    </div>
    <a-table
      row-key="id"
      :columns="columns"
      :data-source="store.notices"
      :loading="store.loading"
      :pagination="pagination"
      :scroll="{ x: 900 }"
      @change="handleTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <a-tag :color="record.status === '已发布' ? 'green' : record.status === '草稿' ? 'orange' : 'default'">{{ record.status }}</a-tag>
        </template>
        <template v-else-if="column.key === 'actions'">
          <a-button type="link" size="small" @click="showDetail(record)">查看</a-button>
          <template v-if="auth.hasPermission('notice:write') && record.status !== '已撤回'">
            <a-button type="link" size="small" @click="openEdit(record)">编辑</a-button>
            <a-button type="link" size="small" danger @click="confirmDelete(record)">{{ record.status === '已发布' ? '撤回' : '删除' }}</a-button>
          </template>
        </template>
      </template>
    </a-table>

    <a-modal
      v-model:open="modalOpen"
      title="编辑公告"
      width="760px"
      :confirm-loading="store.saving"
      ok-text="确定"
      cancel-text="取消"
      destroy-on-close
      @ok="submit"
    >
      <a-form ref="formRef" :model="form" :rules="rules" layout="vertical">
        <a-form-item label="公告标题" name="title"><a-input v-model:value="form.title" /></a-form-item>
        <div class="role-form-grid">
          <a-form-item label="公告类型" name="type">
            <a-select v-model:value="form.type">
              <a-select-option value="安全卫生">安全卫生</a-select-option>
              <a-select-option value="宿舍通知">宿舍通知</a-select-option>
              <a-select-option value="全校学生">全校学生</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="发布人" name="publisher"><a-input v-model:value="form.publisher" disabled /></a-form-item>
          <a-form-item label="公告状态" name="status"><a-segmented v-model:value="form.status" :options="statusOptions" block /></a-form-item>
        </div>
        <a-form-item label="公告正文"><a-textarea v-model:value="form.content" :rows="8" :maxlength="5000" show-count /></a-form-item>
      </a-form>
    </a-modal>
  </section>

  <section v-else class="notice-create-page" role="region" aria-label="公告 AI 起草工作台">
    <p v-if="ai.error" class="notice-page-alert" role="alert">{{ ai.error }}</p>
    <header class="notice-create-heading">
      <nav class="notice-breadcrumb" aria-label="当前位置">
        <button type="button" @click="router.push('/notices')">通知管理</button>
        <span aria-hidden="true">/</span>
        <span>发布公告</span>
      </nav>
    </header>
    <p v-if="currentSnapshotError" class="notice-snapshot-alert" role="alert">
      <span>{{ currentSnapshotError }}</span>
      <button type="button" @click="loadCurrentContentSnapshot">重新加载</button>
    </p>

    <a-form
      ref="formRef"
      :model="form"
      :rules="rules"
      layout="vertical"
      class="notice-workspace-shell"
      role="dialog"
      aria-label="公告 AI 起草"
      aria-modal="false"
    >
      <div class="notice-workspace-grid">
        <section class="notice-card notice-points-panel" role="region" aria-label="公告要点">
          <h2>公告要点</h2>
          <a-form-item label="公告要点输入">
            <a-textarea
              id="ai-notice-points"
              v-model:value="draftInput.points"
              aria-label="公告要点"
              :rows="4"
              :maxlength="200"
              placeholder="输入事项、时间和需人工确认的范围，请勿输入姓名、学号或手机号"
            />
            <span class="notice-field-count">{{ draftInput.points.length }}/200</span>
          </a-form-item>
          <p v-if="inputSafetyMessage" class="notice-input-safety" data-testid="notice-input-safety" role="alert">
            <ExclamationCircleFilled />{{ inputSafetyMessage }}
          </p>
          <a-form-item label="公告类型" name="type">
            <a-select v-model:value="form.type" aria-label="公告类型">
              <a-select-option value="安全卫生">安全检查通知</a-select-option>
              <a-select-option value="宿舍通知">宿舍日常通知</a-select-option>
              <a-select-option value="全校学生">全校学生通知</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="目标范围">
            <a-select v-model:value="draftInput.audience" aria-label="目标范围">
              <a-select-option value="全体学生">全体学生</a-select-option>
              <a-select-option value="全体住宿学生">全体住宿学生</a-select-option>
              <a-select-option value="相关宿舍学生">相关宿舍学生</a-select-option>
            </a-select>
          </a-form-item>
          <a-form-item label="授权来源" name="publisher">
            <a-select v-model:value="form.publisher" aria-label="授权来源">
              <a-select-option value="学生宿舍管理中心">学生宿舍管理中心</a-select-option>
              <a-select-option value="后勤管理处">后勤管理处</a-select-option>
              <a-select-option :value="auth.user?.userName ?? '管理员'">{{ auth.user?.userName ?? '管理员' }}</a-select-option>
            </a-select>
          </a-form-item>
          <fieldset class="notice-tone-fieldset">
            <legend>语气选择</legend>
            <div class="notice-tone-options">
              <button
                v-for="tone in toneOptions"
                :key="tone"
                type="button"
                :aria-pressed="draftInput.tone === tone"
                @click="draftInput.tone = tone"
              >
                <span aria-hidden="true"></span>{{ tone }}
              </button>
            </div>
          </fieldset>
          <button
            type="button"
            class="notice-primary-button notice-generate-button"
            :disabled="!draftInput.points.trim() || ai.loading || currentSnapshotLoading || !canUseAiDraft || Boolean(inputSafetyMessage)"
            @click="generateDraft"
          >
            <RobotOutlined />{{ currentSnapshotLoading ? '读取当前公告…' : ai.loading ? '正在生成…' : '生成 AI 草稿' }}
          </button>
        </section>

        <section class="notice-card notice-draft-panel" :class="{ 'is-invalid-run': draftRunInvalid }" role="region" aria-label="AI 草稿">
          <h2>AI 草稿</h2>
          <div class="notice-editor-frame">
            <a-form-item class="notice-title-field" name="title">
              <a-input v-model:value="form.title" aria-label="公告标题" :maxlength="128" placeholder="公告标题" />
            </a-form-item>
            <a-form-item class="notice-content-field">
              <a-textarea
                v-model:value="form.content"
                aria-label="AI 草稿正文"
                :auto-size="{ minRows: 8, maxRows: 18 }"
                :maxlength="5000"
                placeholder="输入公告要点并生成草稿，或在此人工起草纯文本公告"
              />
            </a-form-item>
          </div>
          <div class="notice-draft-footer">
            <span v-if="ai.noticeDraft?.blocked" class="notice-blocked-copy">草稿已被安全检查阻止</span>
            <span v-else-if="draftRunInvalid" class="notice-invalid-copy">{{ draftRunInvalidCopy }}</span>
            <span v-else-if="draftRunDegraded" class="notice-degraded-copy">AI 草稿已降级生成，必须完成人工核验</span>
            <span v-else>{{ hasGeneratedDraft ? 'AI 草稿已生成，可继续人工编辑' : '等待生成或人工输入' }}</span>
            <span>字数：{{ draftWordCount }}</span>
          </div>
        </section>

        <aside class="notice-card notice-check-panel" role="region" aria-label="内容检查">
          <h2>内容检查</h2>
          <dl class="notice-check-list">
            <div>
              <dt><SafetyCertificateOutlined />敏感词/PII 检查</dt>
              <dd :class="piiCheckTone"><CheckCircleFilled v-if="piiCheckState === '通过'" />{{ piiCheckState }}</dd>
            </div>
            <div><dt><SafetyCertificateOutlined />置信度</dt><dd class="is-blue">{{ confidenceLabel }}</dd></div>
            <div><dt><FileTextOutlined />引用来源</dt><dd>{{ citationCount }}</dd></div>
            <div><dt><FileTextOutlined />版本</dt><dd>{{ draftVersionLabel }}</dd></div>
            <div><dt><ClockCircleOutlined />数据时间</dt><dd>{{ evidenceAsOf }}</dd></div>
          </dl>
          <div class="notice-safety-messages">
            <p :class="hasGroundedEvidence ? 'is-info' : 'is-muted'">
              <CheckCircleFilled v-if="hasGroundedEvidence" />
              <ExclamationCircleFilled v-else />
              {{ hasGroundedEvidence ? '来源已校验，可生成确定性草稿' : '生成后展示可靠来源校验结果' }}
            </p>
            <p v-if="ai.noticeDraft?.blocked" class="is-warning"><ExclamationCircleFilled />{{ firstSafetyMessage }}</p>
            <p v-else-if="proposalSafetyMessage" class="is-warning" data-testid="notice-proposal-safety"><ExclamationCircleFilled />{{ proposalSafetyMessage }}</p>
            <p v-else class="is-warning"><ExclamationCircleFilled />生成结果仍需人工复核</p>
            <p class="is-restricted"><StopOutlined />AI 无权直接发布</p>
            <p v-if="!canReviewApproval || !canWriteNotice" class="is-restricted" data-testid="notice-approval-permission"><StopOutlined />当前账号无权提交审批</p>
          </div>
        </aside>
      </div>

      <section class="notice-card notice-diff-panel" role="region" aria-label="变更预览">
        <article>
          <div class="notice-diff-heading">
            <h3>当前内容</h3>
            <small class="notice-diff-status">{{ currentSnapshotLabel }}</small>
          </div>
          <pre>{{ currentSnapshotText }}</pre>
          <span>字数：{{ currentContentSnapshot.length }}</span>
        </article>
        <ArrowRightOutlined class="notice-diff-arrow" aria-hidden="true" />
        <article :class="{ 'is-invalid-run': draftRunInvalid }">
          <h3>{{ draftRunInvalid ? '保留编辑内容' : 'AI 建议' }}</h3>
          <pre>{{ form.content || '等待生成 AI 草稿' }}</pre>
          <span>字数：{{ draftWordCount }}<b v-if="suggestionDelta > 0 && !draftRunInvalid">较当前内容 +{{ suggestionDelta }}</b><b v-if="draftRunInvalid" class="is-invalid-note">不可作为本次 AI 建议</b></span>
        </article>
      </section>

      <section class="notice-card notice-action-panel" aria-label="公告审批操作">
        <ol class="notice-approval-flow" aria-label="AI 建议 → 变更预览 → 人工审批">
          <li :class="{ complete: hasGeneratedDraft, 'is-invalid': draftRunInvalid }">
            <span><RobotOutlined /></span>
            <div><strong>AI 建议</strong><small>{{ draftRunInvalid ? draftRunFlowCopy : hasGeneratedDraft ? '已生成公告草稿' : '等待生成草稿' }}</small></div>
          </li>
          <ArrowRightOutlined class="notice-flow-arrow" aria-hidden="true" />
          <li :class="{ complete: Boolean(form.content) && !draftRunInvalid, 'is-invalid': draftRunInvalid }">
            <span><EyeOutlined /></span>
            <div><strong>变更预览</strong><small>对比查看变更内容</small></div>
          </li>
          <ArrowRightOutlined class="notice-flow-arrow" aria-hidden="true" />
          <li class="is-human">
            <span><UserOutlined /></span>
            <div><strong>人工审批</strong><small>审核通过后可发布</small></div>
          </li>
        </ol>

        <div class="notice-current-state">
          <span>当前状态</span>
          <strong>{{ currentApprovalState }}</strong>
          <small>{{ currentApprovalStateHint }}</small>
        </div>

        <div class="notice-submit-actions">
          <div>
            <button
              type="button"
              class="notice-primary-button"
              aria-label="确定保存为草稿"
              :disabled="store.saving || !form.title.trim() || !canWriteNotice"
              @click="saveDraft"
            >
              <SaveOutlined />{{ store.saving ? '正在保存…' : draftRunInvalid ? '保存人工内容' : '保存为草稿' }}
            </button>
            <button
              type="button"
              class="notice-primary-button"
              aria-label="查看审批提案，提交审批"
              :disabled="!canSubmitApproval"
              @click="submitForApproval"
            >
              <SendOutlined />提交审批
            </button>
          </div>
          <div>
            <button type="button" class="notice-disabled-button" disabled><StopOutlined />人工发布</button>
            <span>审批通过后可用</span>
          </div>
        </div>
      </section>
    </a-form>
  </section>
</template>

<script setup lang="ts">
import {
  ArrowRightOutlined,
  CheckCircleFilled,
  ClockCircleOutlined,
  ExclamationCircleFilled,
  EyeOutlined,
  FileTextOutlined,
  PlusOutlined,
  ReloadOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SaveOutlined,
  SearchOutlined,
  SendOutlined,
  StopOutlined,
  UserOutlined,
} from '@ant-design/icons-vue'
import { Modal, message } from 'ant-design-vue'
import type { FormInstance, TableColumnsType, TablePaginationConfig } from 'ant-design-vue'
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { fetchNotices } from '../api/operations'
import type { NoticeInput, OperationQuery } from '../api/operations'
import { useAuthStore } from '../stores/auth'
import { useOperationsStore } from '../stores/operations'
import type { Notice } from '../types/dormitory'
import { editableNoticeStatuses } from '../utils/notices'
import { isAiSurfaceEnabled } from '../api/ai-client'
import { useAiStore } from '../stores/ai'
import { useAiApprovalStore } from '../stores/aiApproval'

const auth = useAuthStore()
const store = useOperationsStore()
const ai = useAiStore()
const approval = useAiApprovalStore()
const route = useRoute()
const router = useRouter()
const currentPage = ref(1)
const pageSize = ref(10)
const modalOpen = ref(false)
const editingId = ref<number | null>(null)
const formRef = ref<FormInstance>()
const editingStatus = ref<Notice['status']>()
const currentContentSnapshot = ref('')
const currentSnapshotNotice = ref<Notice | null>(null)
const currentSnapshotLoading = ref(false)
const currentSnapshotError = ref('')
const skipNextListReload = ref(false)
const filters = reactive<Omit<OperationQuery, 'page' | 'pageSize'>>({})
const form = reactive<NoticeInput>({ title: '', type: '安全卫生', publisher: '学生宿舍管理中心', status: '草稿', content: '' })
type NoticeFormSnapshot = Omit<NoticeInput, 'content'> & { content: string }
type NoticeDraftInputSnapshot = { points: string; audience: string; tone: '正式' | '温和' | '紧急' }
const proposalFormSnapshot = ref<NoticeFormSnapshot | null>(null)
let draftRequestEpoch = 0
let createVisitEpoch = 0
let currentSnapshotRequestEpoch = 0
const draftInput = reactive<NoticeDraftInputSnapshot>({
  points: '', audience: '全体学生', tone: '正式',
})
const toneOptions: Array<'正式' | '温和' | '紧急'> = ['正式', '温和', '紧急']
const columns: TableColumnsType = [
  { title: '公告标题', dataIndex: 'title', key: 'title', width: 260 },
  { title: '公告类型', dataIndex: 'type', key: 'type', width: 130 },
  { title: '发布时间', dataIndex: 'date', key: 'date', width: 130 },
  { title: '发布人', dataIndex: 'publisher', key: 'publisher', width: 110 },
  { title: '状态', key: 'status', width: 100 },
  { title: '操作', key: 'actions', width: 160, fixed: 'right' },
]
const rules = {
  title: [{ required: true, message: '请输入公告标题' }],
  type: [{ required: true, message: '请选择公告类型' }],
  publisher: [{ required: true, message: '请输入发布人' }],
  status: [{ required: true, message: '请选择公告状态' }],
}
const isCreatePage = computed(() => route.name === 'noticeCreate')
const query = computed<OperationQuery>(() => ({ page: currentPage.value, pageSize: pageSize.value, ...filters }))
const statusOptions = computed(() => editableNoticeStatuses(editingStatus.value))
const canUseAiDraft = computed(() => isAiSurfaceEnabled()
  && auth.hasPermission('ai:notice:draft') && auth.hasPermission('notice:read'))
const canWriteNotice = computed(() => auth.hasPermission('notice:write'))
const canReviewApproval = computed(() => auth.hasPermission('ai:approval:review'))
const pagination = computed<TablePaginationConfig>(() => ({
  current: currentPage.value,
  pageSize: pageSize.value,
  total: store.noticeTotal,
  showSizeChanger: true,
  showTotal: (total) => `共 ${total} 条`,
}))
const draftWordCount = computed(() => form.content?.length ?? 0)
const suggestionDelta = computed(() => Math.max(0, draftWordCount.value - currentContentSnapshot.value.length))
const currentSnapshotLabel = computed(() => {
  if (currentSnapshotLoading.value) return '正在读取最近已发布公告'
  if (currentSnapshotError.value) return '最近已发布公告读取失败'
  if (!auth.hasPermission('notice:read')) return '当前账号无权读取最近已发布公告'
  if (currentSnapshotNotice.value) return `最近已发布：${currentSnapshotNotice.value.title} · ${currentSnapshotNotice.value.date}`
  return '暂无已发布公告'
})
const currentSnapshotText = computed(() => {
  if (currentSnapshotLoading.value) return '正在读取最近已发布公告…'
  if (currentSnapshotError.value) return '当前内容暂不可用，请重试后再进行变更预览'
  if (!auth.hasPermission('notice:read')) return '当前内容不可读取'
  return currentContentSnapshot.value || '最近已发布公告暂无正文'
})
const draftRunTimedOut = computed(() => ai.noticeDraft?.state === 'timed_out')
const draftRunFailed = computed(() => ai.noticeDraft?.state === 'failed')
const draftRunInvalid = computed(() => draftRunFailed.value || draftRunTimedOut.value)
const draftRunInvalidCopy = computed(() => draftRunTimedOut.value
  ? '本次生成超时；请重试或改用人工起草，编辑区仅保留人工内容'
  : '本次生成失败；编辑区仅保留人工内容，不作为当前 AI 结果')
const draftRunFlowCopy = computed(() => draftRunTimedOut.value
  ? '本次生成超时，请重试或改用人工起草'
  : '本次生成失败，未形成可提交建议')
const draftRunDegraded = computed(() => ai.noticeDraft?.state === 'degraded')
const hasGeneratedDraft = computed(() => Boolean(ai.noticeDraft?.state === 'succeeded' && !ai.noticeDraft.blocked))
const hasGroundedEvidence = computed(() => Boolean(!draftRunInvalid.value && ai.noticeDraft?.evidence.grounded
  && ai.noticeDraft.evidence.asOf
  && ai.noticeDraft.evidence.citations.some((citation) => citation.access === 'available')))
const citationCount = computed(() => draftRunInvalid.value ? 0 : ai.noticeDraft?.evidence.citations
  .filter((citation) => citation.access === 'available').length ?? 0)
const confidenceLabel = computed(() => {
  if (draftRunInvalid.value) return '不可用'
  const confidence = ai.noticeDraft?.evidence.confidence
  if (confidence !== undefined) return `${Math.round(confidence * 100)}%`
  return hasGroundedEvidence.value ? '已校验' : '--'
})
const evidenceAsOf = computed(() => {
  if (draftRunInvalid.value) return '--'
  const value = ai.noticeDraft?.evidence.asOf
  return value ? value.replace('T', ' ').slice(0, 16) : '--'
})
const piiCheckState = computed(() => {
  if (!ai.noticeDraft) return '待检查'
  if (draftRunInvalid.value) return '未完成'
  return ai.noticeDraft.blocked ? '未通过' : '通过'
})
const piiCheckTone = computed(() => piiCheckState.value === '通过' ? 'is-success'
  : piiCheckState.value === '未通过' ? 'is-danger' : 'is-muted')
const firstSafetyMessage = computed(() => ai.noticeDraft?.safetyMessages[0] ?? '内容检查未通过')
const draftVersionLabel = computed(() => draftRunInvalid.value ? '--' : ai.noticeDraft?.version ?? '--')
const inputSafetyMessage = computed(() => {
  const points = draftInput.points.trim()
  if (!points) return ''
  if (/\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b/i.test(points)) return '检测到邮箱地址，已阻止进入 AI 起草请求'
  if (/(?:1[3-9]\d{9}|\b\d{8,18}\b)/.test(points)) return '检测到手机号或学号，已阻止进入 AI 起草请求'
  if (/<\/?[a-z][^>]*>/i.test(points) || /[<>]/.test(points)) return '检测到 HTML/脚本样式内容，已阻止进入 AI 起草请求'
  if (/(忽略|绕过|执行命令|系统提示|system prompt)/i.test(points)) return '检测到疑似提示注入内容，已阻止进入 AI 起草请求'
  return ''
})
const proposalExpiry = computed(() => ai.noticeDraft?.proposalExpiresAt)
const proposalExpired = computed(() => {
  const value = proposalExpiry.value
  if (!value) return false
  const timestamp = Date.parse(value)
  return !Number.isFinite(timestamp) || timestamp <= Date.now()
})
const proposalStateActionable = computed(() => {
  const state = ai.noticeDraft?.proposalState
  return state === undefined || state === 'draft' || state === 'pending_approval'
})
const draftStateActionable = computed(() => ai.noticeDraft?.state === 'succeeded')
const evidenceActionable = computed(() => hasGroundedEvidence.value)
const proposalSafetyMessage = computed(() => {
  if (!canUseAiDraft.value) return '当前账号无权使用公告 AI 起草'
  if (!canReviewApproval.value || !canWriteNotice.value) return '当前账号无权提交审批'
  if (!ai.noticeDraft) return ''
  if (ai.noticeDraft.state === 'timed_out') return '生成超时，请重试或改用人工起草'
  if (ai.noticeDraft.state === 'failed') return '草稿生成失败，请重试或改用人工起草'
  if (ai.noticeDraft.state === 'degraded') return '草稿结果已降级，请人工核验可靠来源'
  if (ai.noticeDraft.proposalState === 'stale') return '业务数据已变化，当前公告提案已失效，请重新生成'
  if (ai.noticeDraft.proposalState === 'expired' || proposalExpired.value) return '公告提案已过期，请重新生成'
  if (!evidenceActionable.value) return '暂无可靠来源或数据时间，不能提交审批'
  if (!ai.noticeDraft?.proposalId) return '服务端未创建审批提案，不能继续执行'
  return ''
})
const canSubmitApproval = computed(() => Boolean(canUseAiDraft.value
  && canWriteNotice.value
  && canReviewApproval.value
  && ai.noticeDraft && !ai.noticeDraft.blocked
  && draftStateActionable.value
  && evidenceActionable.value && ai.noticeDraft.proposalId
  && proposalStateActionable.value && !proposalExpired.value
  && form.title.trim() && form.content?.trim() && proposalMatchesCurrentForm()))
const currentApprovalState = computed(() => {
  if (draftRunTimedOut.value) return '生成超时'
  if (draftRunFailed.value) return '生成失败'
  if (!ai.noticeDraft?.proposalId) return '未提交'
  if (ai.noticeDraft.proposalState === 'stale') return '已失效'
  if (ai.noticeDraft.proposalState === 'expired' || proposalExpired.value) return '已过期'
  if (!canSubmitApproval.value) return '待核验'
  return '待审批'
})
const currentApprovalStateHint = computed(() => {
  if (currentApprovalState.value === '生成超时') return '请重试或改用人工起草；保留内容不可作为本次 AI 建议'
  if (currentApprovalState.value === '生成失败') return '保留内容仅可人工编辑，不可作为本次 AI 建议'
  if (currentApprovalState.value === '已失效') return '业务事实发生变化，请重新生成'
  if (currentApprovalState.value === '已过期') return '请刷新事实后重新生成'
  if (currentApprovalState.value === '待核验') return proposalSafetyMessage.value || '请人工核验后继续'
  return ai.noticeDraft?.proposalId ? '等待人工审核通过' : '生成草稿后提交审批'
})

function captureFormSnapshot(): NoticeFormSnapshot {
  return {
    title: form.title,
    type: form.type,
    publisher: form.publisher,
    status: form.status,
    content: form.content ?? '',
  }
}
function formMatchesSnapshot(snapshot: NoticeFormSnapshot) {
  return snapshot.title === form.title
    && snapshot.type === form.type
    && snapshot.publisher === form.publisher
    && snapshot.status === form.status
    && snapshot.content === (form.content ?? '')
}
function captureDraftInputSnapshot(): NoticeDraftInputSnapshot {
  return { ...draftInput }
}
function draftInputMatchesSnapshot(snapshot: NoticeDraftInputSnapshot) {
  return snapshot.points === draftInput.points
    && snapshot.audience === draftInput.audience
    && snapshot.tone === draftInput.tone
}
function draftRequestIsCurrent(
  requestEpoch: number,
  visitEpoch: number,
  formSnapshot: NoticeFormSnapshot,
  inputSnapshot: NoticeDraftInputSnapshot,
) {
  return requestEpoch === draftRequestEpoch
    && visitEpoch === createVisitEpoch
    && isCreatePage.value
    && formMatchesSnapshot(formSnapshot)
    && draftInputMatchesSnapshot(inputSnapshot)
}
function proposalMatchesCurrentForm() {
  const snapshot = proposalFormSnapshot.value
  return Boolean(snapshot && formMatchesSnapshot(snapshot))
}
function invalidateDraftProposal() {
  const draft = ai.noticeDraft
  const id = draft?.proposalId
  proposalFormSnapshot.value = null
  if (!id) return
  draft.proposalId = undefined
  draft.proposalState = undefined
  if (approval.selectedId === id) approval.selectedId = ''
}
function discardStaleDraft() {
  invalidateDraftProposal()
  ai.noticeDraft = null
}
function proposalIsCurrent(id: string, visitEpoch: number) {
  return createVisitEpoch === visitEpoch
    && isCreatePage.value
    && ai.noticeDraft?.proposalId === id
    && canSubmitApproval.value
}

async function load() {
  try { await store.loadNotices(query.value) }
  catch (error) { message.error(error instanceof Error ? error.message : '公告数据加载失败') }
}
function currentSnapshotRequestIsCurrent(requestEpoch: number, visitEpoch: number) {
  return requestEpoch === currentSnapshotRequestEpoch && visitEpoch === createVisitEpoch && isCreatePage.value
}
async function loadCurrentContentSnapshot() {
  const requestEpoch = ++currentSnapshotRequestEpoch
  const visitEpoch = createVisitEpoch
  currentSnapshotLoading.value = true
  currentSnapshotError.value = ''
  currentSnapshotNotice.value = null
  currentContentSnapshot.value = ''
  if (!auth.hasPermission('notice:read')) {
    currentSnapshotLoading.value = false
    return
  }
  try {
    const page = await fetchNotices({ page: 1, pageSize: 1, status: '已发布' })
    if (!currentSnapshotRequestIsCurrent(requestEpoch, visitEpoch)) return
    const latest = page.records.find((notice) => notice.status === '已发布') ?? null
    currentSnapshotNotice.value = latest
    currentContentSnapshot.value = latest?.content?.trim() ?? ''
  } catch {
    if (!currentSnapshotRequestIsCurrent(requestEpoch, visitEpoch)) return
    currentSnapshotError.value = '最近已发布公告加载失败，当前内容预览暂不可用'
  } finally {
    if (currentSnapshotRequestIsCurrent(requestEpoch, visitEpoch)) currentSnapshotLoading.value = false
  }
}
function search() { currentPage.value = 1; void load() }
function resetFilters() {
  Object.assign(filters, { keyword: undefined, type: undefined, status: undefined })
  currentPage.value = 1
  void load()
}
function handleTableChange(page: TablePaginationConfig) {
  currentPage.value = page.current ?? 1
  pageSize.value = page.pageSize ?? 10
  void load()
}
async function openCreate() { await router.push('/notices/create') }
function resetCreateWorkspace() {
  ai.invalidateNoticeRequests()
  currentSnapshotRequestEpoch += 1
  editingId.value = null
  editingStatus.value = undefined
  modalOpen.value = false
  currentContentSnapshot.value = ''
  currentSnapshotNotice.value = null
  currentSnapshotLoading.value = false
  currentSnapshotError.value = ''
  Object.assign(form, {
    title: '',
    type: '安全卫生',
    publisher: '学生宿舍管理中心',
    status: '草稿',
    content: '',
  })
  Object.assign(draftInput, { points: '', audience: '全体学生', tone: '正式' })
  proposalFormSnapshot.value = null
  ai.noticeDraft = null
  ai.demoProposalMessage = ''
  ai.error = null
}
function openEdit(notice: Notice) {
  if (notice.status === '已撤回') return
  editingId.value = notice.id
  editingStatus.value = notice.status
  Object.assign(form, {
    title: notice.title,
    type: notice.type,
    publisher: auth.user?.userName ?? notice.publisher,
    status: notice.status,
    content: notice.content ?? '',
  })
  modalOpen.value = true
}
function showDetail(notice: Notice) {
  Modal.info({
    title: notice.title,
    content: `${notice.type} / ${notice.publisher} / ${notice.date} / ${notice.status}\n${notice.content ?? '暂无正文'}`,
  })
}
async function generateDraft() {
  if (ai.loading) return
  if (!canUseAiDraft.value) {
    message.error('当前账号无权使用公告 AI 起草')
    return
  }
  if (inputSafetyMessage.value) {
    invalidateDraftProposal()
    ai.noticeDraft = null
    ai.error = inputSafetyMessage.value
    message.warning(inputSafetyMessage.value)
    return
  }
  const requestEpoch = ++draftRequestEpoch
  const visitEpoch = createVisitEpoch
  const requestFormSnapshot = captureFormSnapshot()
  const requestDraftInputSnapshot = captureDraftInputSnapshot()
  invalidateDraftProposal()
  try {
    const result = await ai.draftNotice({ ...requestDraftInputSnapshot, type: requestFormSnapshot.type })
    const responseIsCurrent = draftRequestIsCurrent(
      requestEpoch, visitEpoch, requestFormSnapshot, requestDraftInputSnapshot,
    )
    if (!responseIsCurrent) {
      discardStaleDraft()
      return
    }
    if (!result) return
    const resultCanPopulateDraft = !result.blocked && (result.state === 'succeeded' || result.state === 'degraded')
    if (resultCanPopulateDraft) {
      form.title = result.title
      form.content = result.content
      form.status = '草稿'
      proposalFormSnapshot.value = captureFormSnapshot()
    } else if (result.state === 'failed' || result.state === 'timed_out') {
      invalidateDraftProposal()
    }
  } catch (error) {
    if (!draftRequestIsCurrent(requestEpoch, visitEpoch, requestFormSnapshot, requestDraftInputSnapshot)) return
    const safeMessage = error instanceof Error ? error.message : '公告 AI 起草失败'
    ai.error = safeMessage
    message.error(safeMessage)
  }
}
async function openDraftProposal() {
  const id = ai.noticeDraft?.proposalId
  const visitEpoch = createVisitEpoch
  if (!id || !proposalIsCurrent(id, visitEpoch)) return
  try {
    await approval.selectProposal(id)
    if (!proposalIsCurrent(id, visitEpoch) || approval.selectedId !== id) {
      if (approval.selectedId === id) approval.selectedId = ''
      return
    }
    await router.push('/ai/approvals')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '审批提案加载失败')
  }
}
async function saveDraft() {
  if (!canWriteNotice.value) {
    message.error('当前账号无权保存公告')
    return
  }
  form.status = '草稿'
  try {
    await formRef.value?.validate()
    await store.saveNotice(null, { ...form, status: '草稿' }, query.value)
    message.success('公告已保存')
    skipNextListReload.value = true
    await router.push('/notices')
  } catch (error) {
    if (error instanceof Error) message.error(error.message)
  }
}
async function submitForApproval() {
  if (!canSubmitApproval.value) return
  try {
    await formRef.value?.validate()
    await openDraftProposal()
  } catch (error) {
    if (error instanceof Error) message.error(error.message)
  }
}
async function submit() {
  await formRef.value?.validate()
  try {
    await store.saveNotice(editingId.value, { ...form }, query.value)
    modalOpen.value = false
    message.success('公告已更新')
  } catch (error) {
    message.error(error instanceof Error ? error.message : '公告保存失败')
  }
}
function confirmDelete(notice: Notice) {
  const withdrawing = notice.status === '已发布'
  Modal.confirm({
    title: `${withdrawing ? '确认撤回' : '确认删除'}公告：${notice.title}？`,
    okText: withdrawing ? '撤回' : '删除',
    okType: 'danger',
    cancelText: '取消',
    async onOk() {
      await store.removeNotice(notice.id, query.value)
      message.success(withdrawing ? '公告已撤回并保留记录' : '公告已删除')
    },
  })
}

onMounted(() => { if (!isCreatePage.value) void load() })
watch(() => route.name, (name, previousName) => {
  if (name !== previousName) {
    createVisitEpoch += 1
    if (previousName === 'noticeCreate' && name !== 'noticeCreate') ai.invalidateNoticeRequests()
  }
  if (name === 'noticeCreate' && auth.hasPermission('notice:write')) {
    resetCreateWorkspace()
    void loadCurrentContentSnapshot()
    return
  }
  if (name === 'notices' && previousName === 'noticeCreate') {
    if (skipNextListReload.value) skipNextListReload.value = false
    else void load()
  }
}, { immediate: true, flush: 'sync' })
watch(
  () => [form.title, form.content, form.type, form.publisher, form.status],
  () => {
    if (proposalFormSnapshot.value && !proposalMatchesCurrentForm()) invalidateDraftProposal()
  },
  { flush: 'sync' },
)
</script>

<style scoped>
.notice-create-page {
  min-height: calc(100vh - 108px);
  color: #17213a;
}

.notice-create-heading {
  min-height: 30px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 14px;
  margin-bottom: 4px;
}

.notice-snapshot-alert {
  min-height: 34px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 0 0 8px;
  padding: 6px 10px;
  border: 1px solid #f5d2a8;
  border-radius: 5px;
  color: #a34b0a;
  background: #fffaf4;
  font-size: 12px;
  line-height: 1.4;
}

.notice-snapshot-alert button {
  min-height: 44px;
  padding: 0 12px;
  border: 1px solid #f0bd82;
  border-radius: 4px;
  color: #a34b0a;
  background: #fff;
  cursor: pointer;
  font-size: 12px;
}

.notice-snapshot-alert button:focus-visible {
  outline: 3px solid rgba(37, 99, 235, .25);
  outline-offset: 2px;
}

.notice-page-alert,
.notice-input-safety {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0 0 10px;
  padding: 9px 10px;
  border: 1px solid #fecaca;
  border-radius: 5px;
  color: #b91c1c;
  background: #fff7f7;
  font-size: 12px;
  line-height: 1.45;
}

.notice-input-safety { margin-top: -3px; border-color: #f5d2a8; color: #a34b0a; background: #fffaf4; }

.notice-breadcrumb {
  min-height: 28px;
  display: flex;
  align-items: center;
  gap: 10px;
  color: #111827;
  font-size: 13px;
}

.notice-breadcrumb button {
  padding: 0;
  border: 0;
  color: #0b63f6;
  background: transparent;
  cursor: pointer;
}

.notice-workspace-shell {
  display: grid;
  gap: 12px;
}

.notice-workspace-grid {
  display: grid;
  grid-template-columns: minmax(250px, .78fr) minmax(420px, 1.42fr) minmax(275px, .9fr);
  gap: 12px;
  align-items: stretch;
}

.notice-card {
  min-width: 0;
  border: 1px solid #dfe6f0;
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 3px 12px rgba(15, 47, 111, .045);
}

.notice-card h2,
.notice-card h3 {
  margin: 0;
  color: #121a2f;
  font-weight: 700;
}

.notice-card h2 { font-size: 18px; }
.notice-card h3 { font-size: 15px; }
.notice-diff-heading { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.notice-diff-status { min-width: 0; color: #64748b; font-size: 12px; font-weight: 400; overflow-wrap: anywhere; text-align: right; }

.notice-points-panel,
.notice-check-panel {
  min-height: 526px;
  padding: 16px 18px;
}
.notice-draft-panel { align-self: start; min-height: 0; padding: 16px 18px; }

.notice-points-panel {
  display: flex;
  flex-direction: column;
}

.notice-points-panel h2,
.notice-draft-panel h2,
.notice-check-panel h2 { margin-bottom: 12px; }

.notice-points-panel :deep(.ant-form-item) { margin-bottom: 11px; }
.notice-points-panel :deep(.ant-form-item-label) { padding-bottom: 5px; }
.notice-points-panel :deep(.ant-form-item-label > label) { color: #182033; font-size: 14px; font-weight: 600; }
.notice-points-panel :deep(.ant-input),
.notice-points-panel :deep(.ant-select-selector) { border-color: #cfd8e7 !important; border-radius: 5px !important; }
.notice-points-panel :deep(textarea.ant-input) { min-height: 92px; resize: none; line-height: 1.55; }

.notice-field-count {
  display: block;
  margin-top: -22px;
  padding: 0 8px 4px 0;
  color: #64748b;
  font-size: 12px;
  text-align: right;
  pointer-events: none;
}

.notice-tone-fieldset {
  margin: 0;
  padding: 0;
  border: 0;
}

.notice-tone-fieldset legend {
  margin-bottom: 7px;
  color: #182033;
  font-size: 14px;
  font-weight: 600;
}

.notice-tone-options {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 8px;
}

.notice-tone-options button {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  padding: 0;
  border: 0;
  color: #25314c;
  background: transparent;
  cursor: pointer;
  font-size: 14px;
}

.notice-tone-options button span {
  width: 16px;
  height: 16px;
  border: 1px solid #bac6d8;
  border-radius: 50%;
  background: #fff;
}

.notice-tone-options button[aria-pressed="true"] span {
  border: 5px solid #1769f6;
}

.notice-primary-button,
.notice-disabled-button {
  min-height: 44px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 0 18px;
  border-radius: 5px;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
}

.notice-primary-button {
  border: 1px solid #0b63f6;
  color: #fff;
  background: #0b63f6;
  box-shadow: 0 5px 12px rgba(11, 99, 246, .16);
}

.notice-primary-button:hover { background: #0755d8; }
.notice-primary-button:focus-visible,
.notice-tone-options button:focus-visible,
.notice-breadcrumb button:focus-visible {
  outline: 3px solid rgba(37, 99, 235, .25);
  outline-offset: 2px;
}

.notice-primary-button:disabled {
  border-color: #c9d3e1;
  color: #5b677a;
  background: #e9eef5;
  box-shadow: none;
  cursor: not-allowed;
}

.notice-generate-button {
  width: 100%;
  margin-top: auto;
}

.notice-draft-panel {
  display: flex;
  flex-direction: column;
}

.notice-editor-frame {
  min-width: 0;
  min-height: 0;
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  overflow: visible;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  background: #fff;
}
.notice-draft-panel.is-invalid-run .notice-editor-frame { border-color: #f0b7b7; background: #fffafa; }
.notice-title-field { flex: 0 0 auto; margin-bottom: 0; }
.notice-title-field :deep(.ant-input) {
  min-height: 46px;
  padding: 10px 13px;
  border: 0;
  border-bottom: 1px solid #dbe3ee;
  border-radius: 0;
  color: #17213a;
  background: transparent;
  font-size: 15px;
  font-weight: 600;
}
.notice-title-field :deep(.ant-input:focus) { box-shadow: inset 0 -2px 0 rgba(37, 99, 235, .35); }
.notice-content-field { width: 100%; min-width: 0; flex: 0 0 auto; margin-bottom: 0; }
.notice-content-field :deep(.ant-form-item-row) {
  width: 100%;
  min-width: 0;
  height: auto;
  flex-direction: column !important;
  flex-wrap: nowrap !important;
}
.notice-content-field :deep(.ant-form-item-label) {
  width: 100%;
  flex: 0 0 auto !important;
  max-width: 100%;
}
.notice-content-field :deep(.ant-form-item-control-input),
.notice-content-field :deep(.ant-form-item-control-input-content) { width: 100%; min-width: 0; height: auto; }
.notice-content-field :deep(.ant-form-item-control) {
  width: 100%;
  min-width: 0;
  max-width: 100%;
  height: auto;
  flex: 0 0 auto !important;
  align-self: stretch;
  margin-inline: 0;
}
.notice-content-field :deep(textarea.ant-input) {
  width: 100%;
  max-width: 100%;
  padding: 12px 13px;
  resize: none;
  border: 0;
  border-radius: 0;
  color: #111827;
  background: transparent;
  font-size: 15px;
  line-height: 1.6;
}
.notice-content-field :deep(textarea.ant-input:focus) { box-shadow: inset 0 0 0 2px rgba(37, 99, 235, .18); }

.notice-draft-footer {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 7px;
  color: #64748b;
  font-size: 12px;
}

.notice-blocked-copy { color: #dc2626; }
.notice-invalid-copy { color: #b91c1c; font-weight: 600; }
.notice-degraded-copy { color: #a34b0a; font-weight: 600; }

.notice-check-list { margin: 0; }
.notice-check-list > div {
  min-height: 48px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  border-bottom: 1px solid #e7edf5;
}

.notice-check-list dt,
.notice-check-list dd {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 14px;
}

.notice-check-list dt svg { color: #1769f6; font-size: 20px; }
.notice-check-list dd { color: #17213a; font-weight: 600; }
.notice-check-list dd.is-success { color: #169653; }
.notice-check-list dd.is-danger { color: #dc2626; }
.notice-check-list dd.is-blue { color: #0b63f6; font-size: 17px; }
.notice-check-list dd.is-muted { color: #64748b; }

.notice-safety-messages {
  display: grid;
  gap: 10px;
  margin-top: 18px;
}

.notice-safety-messages p {
  min-height: 45px;
  display: flex;
  align-items: center;
  gap: 9px;
  margin: 0;
  padding: 9px 10px;
  border: 1px solid;
  border-radius: 5px;
  font-size: 12px;
  line-height: 1.45;
}

.notice-safety-messages svg { flex: 0 0 auto; font-size: 16px; }
.notice-safety-messages .is-info { border-color: #b9d3ff; color: #075be8; background: #f4f8ff; }
.notice-safety-messages .is-muted { border-color: #dbe3ee; color: #64748b; background: #f8fafc; }
.notice-safety-messages .is-warning { border-color: #f5d2a8; color: #a34b0a; background: #fffaf4; }
.notice-safety-messages .is-restricted { border-color: #d9c9fb; color: #6536e8; background: #fbf9ff; }

.notice-diff-panel {
  min-height: 156px;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 42px minmax(0, 1fr);
  align-items: center;
  padding: 12px 16px;
}

.notice-diff-panel article { min-width: 0; }
.notice-diff-panel pre {
  min-height: 86px;
  max-height: 112px;
  margin: 7px 0 4px;
  padding: 10px;
  overflow: auto;
  border: 1px solid #d6deea;
  border-radius: 5px;
  color: #111827;
  background: #fff;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.notice-diff-panel article > span {
  display: block;
  color: #64748b;
  font-size: 12px;
  text-align: right;
}

.notice-diff-panel article > span b { margin-left: 10px; color: #198754; font-weight: 600; }
.notice-diff-panel article > span b.is-invalid-note { color: #b91c1c; }
.notice-diff-panel article.is-invalid-run pre { border-color: #f0b7b7; color: #7f1d1d; background: #fffafa; }
.notice-diff-arrow {
  width: 44px;
  height: 44px;
  justify-self: center;
  padding: 11px;
  border: 1px solid #d6deea;
  border-radius: 50%;
  color: #17213a;
  background: #fff;
  font-size: 20px;
}

.notice-action-panel {
  min-height: 120px;
  display: grid;
  grid-template-columns: minmax(480px, 1.45fr) 170px minmax(310px, 1fr);
  align-items: stretch;
  overflow: hidden;
}

.notice-approval-flow {
  min-width: 0;
  display: grid;
  grid-template-columns: 1fr 26px 1fr 26px 1fr;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 12px 16px;
  list-style: none;
}

.notice-approval-flow li {
  min-height: 78px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px;
  border: 1px solid #c8dcfb;
  border-radius: 6px;
  color: #17213a;
  background: #f8fbff;
}

.notice-approval-flow li > span {
  width: 48px;
  height: 48px;
  flex: 0 0 48px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border: 1px solid #c8dcfb;
  border-radius: 50%;
  color: #0b63f6;
  background: #eef6ff;
  font-size: 25px;
}
.notice-approval-flow li.is-human { border-color: #f0d2ac; background: #fffaf4; }
.notice-approval-flow li.is-human > span { border-color: #f0d2ac; color: #e77916; background: #fff8ef; }
.notice-approval-flow li.is-invalid { border-color: #f0b7b7; color: #7f1d1d; background: #fff7f7; }
.notice-approval-flow li.is-invalid > span { border-color: #f0b7b7; color: #dc2626; background: #fff; }
.notice-approval-flow strong,
.notice-approval-flow small { display: block; }
.notice-approval-flow strong { font-size: 14px; }
.notice-approval-flow small { margin-top: 5px; color: #64748b; font-size: 12px; }
.notice-flow-arrow { justify-self: center; color: #17213a; font-size: 18px; }

.notice-current-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 12px;
  border-right: 1px solid #e2e8f0;
  border-left: 1px solid #e2e8f0;
  text-align: center;
}

.notice-current-state span { color: #64748b; font-size: 12px; }
.notice-current-state strong { margin: 7px 0 4px; color: #e86713; font-size: 20px; }
.notice-current-state small { color: #64748b; font-size: 12px; }

.notice-submit-actions {
  display: grid;
  align-content: center;
  gap: 10px;
  padding: 12px 16px;
}

.notice-submit-actions > div:first-child { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.notice-submit-actions > div:last-child { display: grid; grid-template-columns: 1fr auto; align-items: center; gap: 10px; }
.notice-submit-actions .notice-primary-button,
.notice-disabled-button { width: 100%; min-height: 44px; padding: 0 12px; font-size: 14px; }
.notice-disabled-button { border: 1px solid #d7dee9; color: #5b677a; background: #f1f4f8; cursor: not-allowed; }
.notice-submit-actions > div:last-child span { color: #64748b; font-size: 12px; white-space: nowrap; }

@media (max-width: 1240px) {
  .notice-workspace-grid { grid-template-columns: minmax(250px, .75fr) minmax(390px, 1.25fr); }
  .notice-check-panel { grid-column: 1 / -1; min-height: auto; }
  .notice-check-list { display: grid; grid-template-columns: repeat(5, 1fr); }
  .notice-check-list > div { min-height: 70px; align-items: flex-start; flex-direction: column; justify-content: center; padding: 8px; border-right: 1px solid #e7edf5; }
  .notice-check-list > div:last-child { border-right: 0; }
  .notice-safety-messages { grid-template-columns: repeat(3, 1fr); }
  .notice-action-panel { grid-template-columns: 1fr 160px; }
  .notice-submit-actions { grid-column: 1 / -1; border-top: 1px solid #e2e8f0; }
  .notice-submit-actions > div:last-child { grid-template-columns: minmax(180px, 1fr) auto; }
}

@media (max-width: 900px) {
  .notice-workspace-grid { grid-template-columns: 1fr; }
  .notice-points-panel,
  .notice-draft-panel,
  .notice-check-panel { min-height: auto; }
  .notice-generate-button { margin-top: 12px; }
  .notice-check-panel { grid-column: auto; }
  .notice-check-list { grid-template-columns: repeat(2, 1fr); }
  .notice-check-list > div { border-right: 0; }
  .notice-safety-messages { grid-template-columns: 1fr; }
  .notice-diff-panel { grid-template-columns: 1fr; gap: 8px; }
  .notice-diff-arrow { transform: rotate(90deg); }
  .notice-action-panel { grid-template-columns: 1fr; }
  .notice-current-state { min-height: 100px; border: 0; border-top: 1px solid #e2e8f0; border-bottom: 1px solid #e2e8f0; }
}

@media (max-width: 768px) {
  .notice-create-page { min-width: 0; overflow-wrap: anywhere; }
  .notice-workspace-shell { min-width: 0; }
  .notice-create-heading { align-items: flex-start; flex-direction: column; gap: 2px; }
  .notice-snapshot-alert { align-items: flex-start; flex-wrap: wrap; }
  .notice-workspace-grid,
  .notice-action-panel { grid-template-columns: minmax(0, 1fr); min-width: 0; }
  .notice-breadcrumb { margin-bottom: 4px; }
  .notice-breadcrumb button { min-height: 44px; }
  .notice-points-panel,
  .notice-draft-panel,
  .notice-check-panel { padding: 14px; }
  .notice-check-list { grid-template-columns: 1fr; }
  .notice-check-list > div { min-height: 52px; align-items: center; flex-direction: row; justify-content: space-between; }
  .notice-tone-options { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .notice-tone-options button { min-width: 0; min-height: 44px; white-space: normal; overflow-wrap: anywhere; }
  .notice-draft-footer { flex-wrap: wrap; }
  .notice-diff-panel pre { max-height: none; overflow: visible; }
  .notice-diff-heading { align-items: flex-start; flex-direction: column; gap: 2px; }
  .notice-diff-status { text-align: left; }
  .notice-check-list :is(dt, dd) { min-width: 0; overflow-wrap: anywhere; }
  .notice-approval-flow { grid-template-columns: minmax(0, 1fr); }
  .notice-flow-arrow { transform: rotate(90deg); }
  .notice-submit-actions { min-width: 0; }
  .notice-submit-actions > div { grid-template-columns: minmax(0, 1fr); min-width: 0; }
  .notice-primary-button,
  .notice-disabled-button { min-width: 0; min-height: 44px; white-space: normal; overflow-wrap: anywhere; }
  .notice-submit-actions .notice-primary-button,
  .notice-submit-actions .notice-disabled-button { min-height: 44px; }
  .notice-submit-actions > div:last-child span { white-space: normal; overflow-wrap: anywhere; text-align: center; }
}
</style>
