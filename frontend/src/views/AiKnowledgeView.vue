<template>
  <section class="ai-page" aria-label="AI 知识管理内容">
    <AiSafetyState message="知识文档始终按数据处理，不会成为模型指令；首期只接受经过批准的纯文本。" tone="info" />
    <div class="ai-knowledge-layout">
      <section class="panel ai-knowledge-sources" aria-label="知识来源列表">
        <div class="ai-section-title"><h2>知识来源</h2><span>{{ store.total }}</span></div>
        <button
          v-for="source in store.sources"
          :key="source.id"
          type="button"
          class="ai-knowledge-source"
          :aria-pressed="source.id === store.selectedSourceId"
          :disabled="store.loading || store.selecting || store.saving"
          @click="selectSource(source.id)"
        >
          <strong>{{ source.name }}</strong>
          <small>{{ source.classification }} · {{ source.status }} · ACL v{{ source.aclVersion }}</small>
        </button>
        <p v-if="!store.loading && !store.sources.length" class="empty-text">暂无可见知识来源</p>
      </section>

      <section class="panel ai-knowledge-detail" aria-label="知识来源详情">
        <template v-if="store.selectedSource">
          <div class="ai-section-title"><h2>{{ store.selectedSource.name }}</h2><span>{{ store.selectedSource.sourceType }}</span></div>
          <dl class="ai-detail-grid">
            <div><dt>Owner</dt><dd>用户 #{{ store.selectedSource.ownerUserId }}</dd></div>
            <div><dt>分类</dt><dd>{{ store.selectedSource.classification }}</dd></div>
            <div><dt>ACL 模式</dt><dd>{{ store.selectedSource.matchMode }}</dd></div>
            <div><dt>状态</dt><dd>{{ store.selectedSource.status }}</dd></div>
          </dl>
          <p class="ai-chain-hash">权限：{{ store.selectedSource.permissions.join('、') || '空 ACL（默认拒绝）' }}</p>

          <form v-if="canManage" class="ai-knowledge-ingest" @submit.prevent="updateSource">
            <h3>来源与 ACL 治理</h3>
            <div class="ai-knowledge-form-grid">
              <label>来源名称<a-input v-model:value="editForm.name" aria-label="编辑来源名称" :maxlength="200" /></label>
              <label>数据分类<a-segmented v-model:value="editForm.classification" :options="['L0', 'L1', 'L2']" block /></label>
              <label>ACL 匹配<a-segmented v-model:value="editForm.matchMode" :options="['ANY', 'ALL']" block /></label>
              <label>来源状态<a-segmented v-model:value="editForm.status" :options="['ACTIVE', 'PAUSED']" block /></label>
            </div>
            <label>权限（逗号分隔）<a-input v-model:value="editPermissionText" aria-label="编辑知识来源权限" placeholder="notice:read,repair:read" /></label>
            <p class="ai-chain-hash">提交时使用 ACL v{{ store.selectedSource.aclVersion }} 做 CAS；冲突后必须刷新再重试。</p>
            <button type="submit" :disabled="store.saving || !editForm.name.trim()">保存来源治理</button>
          </form>

          <form v-if="canManage" class="ai-knowledge-ingest" @submit.prevent="ingest">
            <h3>摄取纯文本版本</h3>
            <div class="ai-knowledge-form-grid">
              <label>文档标题<a-input v-model:value="versionForm.title" aria-label="文档标题" :maxlength="500" /></label>
              <label>外部键<a-input v-model:value="versionForm.externalKey" aria-label="外部键" :maxlength="256" /></label>
              <label>版本<a-input v-model:value="versionForm.version" aria-label="版本" :maxlength="32" /></label>
            </div>
            <label>纯文本正文
              <a-textarea v-model:value="store.uploadText" aria-label="纯文本正文" :rows="9" :maxlength="2000000" placeholder="粘贴已批准的纯文本；请勿包含密钥、Token、密码或高敏信息" />
            </label>
            <div class="ai-actions">
              <button type="submit" :disabled="store.saving || !canIngest">{{ store.saving ? '正在隔离摄取…' : '创建版本并摄取' }}</button>
            </div>
          </form>

          <section v-if="store.version || store.job" class="ai-knowledge-version" aria-label="知识版本与摄取任务">
            <h3>最近版本</h3>
            <dl class="ai-detail-grid">
              <div><dt>版本状态</dt><dd>{{ store.version?.status ?? '-' }}</dd></div>
              <div><dt>可见性</dt><dd>{{ store.version?.visibility ?? '-' }}</dd></div>
              <div><dt>任务状态</dt><dd>{{ store.job?.state ?? '-' }}</dd></div>
              <div><dt>尝试次数</dt><dd>{{ store.job?.attempt ?? 0 }}</dd></div>
            </dl>
            <AiSafetyState v-if="store.job?.errorCode" :message="`摄取失败：${store.job.errorCode}`" tone="danger" />
            <div class="ai-actions">
              <button type="button" class="ai-button--secondary" @click="refreshJob">刷新任务</button>
              <button v-if="canManage && store.version?.status === 'READY'" type="button" @click="activate">激活版本</button>
              <button v-if="canManage && store.version?.status === 'ACTIVE'" type="button" class="ai-button--danger" @click="retire">退休版本</button>
            </div>
            <form v-if="canPublishPublic && store.version?.status === 'READY' && store.selectedSource.classification === 'L0'"
              class="ai-knowledge-ingest" @submit.prevent="approvePublic">
              <h3>公开版本治理审批</h3>
              <AiSafetyState message="只批准当前固定 version/content/classification/ACL snapshot；Owner 不得自批。" tone="warning" />
              <a-input-password v-model:value="approvalPassword" aria-label="知识公开审批当前密码" autocomplete="current-password" placeholder="重新输入当前密码" />
              <button type="submit" :disabled="store.saving || isSelectedOwner || !approvalPassword">批准当前 L0 版本公开</button>
              <p v-if="isSelectedOwner" class="empty-text">当前用户是来源 Owner，必须由另一名知识治理审批人复核。</p>
            </form>
          </section>
        </template>
        <p v-else class="empty-text">请选择知识来源</p>
      </section>

      <section v-if="canManage" class="panel ai-knowledge-create" aria-label="创建知识来源">
        <div class="ai-section-title"><h2>新建来源</h2><span>默认非公开</span></div>
        <form @submit.prevent="createSource">
          <label>来源名称<a-input v-model:value="sourceForm.name" aria-label="来源名称" :maxlength="200" /></label>
          <label>数据分类<a-segmented v-model:value="sourceForm.classification" :options="['L0', 'L1', 'L2']" block /></label>
          <label>ACL 匹配<a-segmented v-model:value="sourceForm.matchMode" :options="['ANY', 'ALL']" block /></label>
          <label>权限（逗号分隔）<a-input v-model:value="permissionText" aria-label="知识来源权限" placeholder="notice:read,repair:read" /></label>
          <button type="submit" :disabled="store.saving || !sourceForm.name.trim()">创建来源</button>
        </form>
      </section>
    </div>
    <AiSafetyState v-if="store.error" :message="store.error" tone="danger" />
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import AiSafetyState from '../components/ai/AiSafetyState.vue'
import { useAuthStore } from '../stores/auth'
import { useAiKnowledgeStore } from '../stores/aiKnowledge'

const auth = useAuthStore()
const store = useAiKnowledgeStore()
const permissionText = ref('ai:knowledge:read')
const editPermissionText = ref('')
const approvalPassword = ref('')
const sourceForm = reactive<{ name: string; classification: 'L0' | 'L1' | 'L2'; matchMode: 'ANY' | 'ALL' }>({ name: '', classification: 'L1', matchMode: 'ANY' })
const editForm = reactive<{ name: string; classification: 'L0' | 'L1' | 'L2'; matchMode: 'ANY' | 'ALL'; status: 'ACTIVE' | 'PAUSED' }>({
  name: '', classification: 'L1', matchMode: 'ANY', status: 'ACTIVE',
})
const versionForm = reactive({ title: '', externalKey: '', version: 'v1' })
const canManage = computed(() => auth.hasPermission('ai:knowledge:manage'))
const canPublishPublic = computed(() => auth.hasPermission('ai:knowledge:publish-public'))
const isSelectedOwner = computed(() => Boolean(store.selectedSource && auth.user?.id === store.selectedSource.ownerUserId))
const canIngest = computed(() => Boolean(store.uploadText.trim() && versionForm.title.trim() && versionForm.externalKey.trim() && versionForm.version.trim()))

async function selectSource(id: string) {
  try { await store.selectSource(id); syncEditForm() }
  catch (error) { message.error(error instanceof Error ? error.message : '知识详情加载失败') }
}
async function createSource() {
  const permissions = permissionText.value.split(',').map((item) => item.trim()).filter(Boolean)
  if (!permissions.length) { message.warning('空 ACL 默认拒绝，请至少填写一个读取权限'); return }
  try {
    await store.createSource({ ...sourceForm, permissions })
    sourceForm.name = ''; syncEditForm(); message.success('知识来源已创建')
  } catch (error) { message.error(error instanceof Error ? error.message : '知识来源创建失败') }
}
function syncEditForm() {
  if (!store.selectedSource) return
  editForm.name = store.selectedSource.name
  editForm.classification = store.selectedSource.classification
  editForm.matchMode = store.selectedSource.matchMode
  editForm.status = store.selectedSource.status === 'PAUSED' ? 'PAUSED' : 'ACTIVE'
  editPermissionText.value = store.selectedSource.permissions.join(',')
}
async function updateSource() {
  const permissions = [...new Set(editPermissionText.value.split(',').map((item) => item.trim()).filter(Boolean))]
  try {
    await store.updateSelectedSource({ ...editForm, permissions })
    syncEditForm(); message.success('知识来源治理已更新，旧公开批准已按合同撤销')
  } catch (error) { message.error(error instanceof Error ? error.message : '知识来源更新失败') }
}
async function ingest() {
  try {
    await store.ingestText({ ...versionForm })
    message.success('知识版本已进入摄取任务')
  } catch (error) { message.error(error instanceof Error ? error.message : '知识摄取失败') }
}
async function refreshJob() { try { await store.refreshJob() } catch (error) { message.error(error instanceof Error ? error.message : '任务刷新失败') } }
async function activate() { try { await store.activate(); message.success('知识版本已激活') } catch (error) { message.error(error instanceof Error ? error.message : '版本激活失败') } }
async function retire() { try { await store.retire(); message.success('知识版本已退休') } catch (error) { message.error(error instanceof Error ? error.message : '版本退休失败') } }
async function approvePublic() {
  try {
    await store.approvePublic(approvalPassword.value)
    approvalPassword.value = ''; message.success('固定知识版本已完成公开治理审批')
  } catch (error) { message.error(error instanceof Error ? error.message : '知识公开审批失败') }
  finally { approvalPassword.value = '' }
}

onMounted(async () => {
  try { await store.loadSources(); syncEditForm() }
  catch (error) { message.error(error instanceof Error ? error.message : '知识来源加载失败') }
})
</script>
