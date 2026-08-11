<template>
  <main
    class="design-system-gallery"
    data-design-system-gallery
    aria-label="AI 智能宿舍设计系统状态画廊"
  >
    <header class="gallery-header">
      <h1>AI 智能宿舍设计系统</h1>
      <p>可信、克制、可解释的人机协作组件</p>
    </header>

    <div class="gallery-grid">
      <section class="gallery-panel gallery-colors" data-gallery-section="colors" aria-labelledby="gallery-colors-title">
        <h2 id="gallery-colors-title"><span>1</span>颜色</h2>
        <ul class="color-list">
          <li v-for="color in colors" :key="color.token">
            <span class="color-swatch" :style="{ background: color.value }" aria-hidden="true" />
            <strong>{{ color.label }}</strong>
            <code>{{ color.value }}</code>
            <small>{{ color.usage }}</small>
          </li>
        </ul>
      </section>

      <section class="gallery-panel gallery-typography" data-gallery-section="typography" aria-labelledby="gallery-typography-title">
        <h2 id="gallery-typography-title"><span>2</span>排版</h2>
        <div class="type-samples">
          <div>
            <small>页面标题</small>
            <strong class="type-page-title" data-font-size="32px">示例：宿舍风险总览</strong>
            <code>32px / Bold<small>Inter / PingFang SC<br />Microsoft YaHei</small></code>
          </div>
          <div>
            <small>卡片标题</small>
            <strong class="type-card-title" data-font-size="20px">示例：今日 AI 运营简报</strong>
            <code>20px / Semibold<small>Inter / PingFang SC<br />Microsoft YaHei</small></code>
          </div>
          <div>
            <small>正文</small>
            <p class="type-body">系统基于授权资料生成建议，供人工参考。</p>
            <code>14px / Regular<small>Inter / PingFang SC<br />Microsoft YaHei</small></code>
          </div>
          <div>
            <small>辅助文字</small>
            <p class="type-caption">数据截至 10:30，仅供参考</p>
            <code>12px / Regular<small>Inter / PingFang SC<br />Microsoft YaHei</small></code>
          </div>
          <div>
            <small>数字指标</small>
            <strong class="type-metric">1,234</strong>
            <code>28px / Bold<small>Inter / PingFang SC<br />Microsoft YaHei</small></code>
          </div>
        </div>
      </section>

      <section class="gallery-panel gallery-tokens" data-gallery-section="tokens" aria-labelledby="gallery-tokens-title">
        <h2 id="gallery-tokens-title"><span>3</span>基础令牌</h2>
        <div class="token-grid">
          <div><BorderOutlined /><strong>8 / 12 / 16 / 24</strong><small>间距</small></div>
          <div><AppstoreOutlined /><strong>4 / 8px</strong><small>栅格基线</small></div>
          <div><ColumnWidthOutlined /><strong>8–12px</strong><small>圆角</small></div>
          <div><MobileOutlined /><strong>44px</strong><small>触控目标</small></div>
          <div><LayoutOutlined /><strong>208 / 52px</strong><small>Sidebar</small></div>
          <div><PicCenterOutlined /><strong>64px</strong><small>Header</small></div>
          <div><BorderlessTableOutlined /><strong>0 / 1 / 2 级</strong><small>低阴影</small></div>
          <div><MenuUnfoldOutlined /><strong>480px</strong><small>Drawer</small></div>
          <div><TabletOutlined /><strong>390px</strong><small>移动基线</small></div>
        </div>
      </section>

      <section class="gallery-panel gallery-safety" data-gallery-section="safety" aria-labelledby="gallery-safety-title">
        <h2 id="gallery-safety-title"><span>4</span>风险与安全状态</h2>
        <div class="safety-tags">
          <span class="safety-tag safety-tag--danger"><StopOutlined />高风险</span>
          <span class="safety-tag safety-tag--warning"><WarningOutlined />中风险</span>
          <span class="safety-tag safety-tag--success"><SafetyCertificateOutlined />低风险</span>
          <span class="safety-tag safety-tag--warning"><ExclamationCircleOutlined />置信度较低</span>
          <span class="safety-tag safety-tag--muted"><LockOutlined />无权限</span>
          <span class="safety-tag safety-tag--info"><InfoCircleOutlined />暂无可靠来源</span>
          <span class="safety-tag safety-tag--ai"><ClockCircleOutlined />已过期</span>
          <span class="safety-tag safety-tag--muted"><CloseSquareOutlined />方案已失效</span>
        </div>
        <div class="safety-components">
          <AiSafetyState state="NO_GROUNDED" />
          <AiSafetyState state="NO_PERMISSION" />
        </div>
      </section>

      <section class="gallery-panel gallery-ai" data-gallery-section="ai-components" aria-labelledby="gallery-ai-title">
        <h2 id="gallery-ai-title"><span>5</span>AI 组件</h2>
        <div class="gallery-ai-showcase">
          <div class="gallery-ai-primary">
            <small class="gallery-specimen-label">AI 命令栏</small>
            <AiCommandBar v-model="question" aria-label="AI 命令栏状态标本" />
            <small class="gallery-specimen-label">AI 建议卡片</small>
            <article class="gallery-suggestion" aria-label="AI 建议状态标本">
              <div class="gallery-suggestion__title"><BulbOutlined /><strong>AI 建议</strong></div>
              <p>建议在 2 个工作日内完成维修，并核对影响范围。</p>
              <div class="gallery-evidence-summary">
                <span><SafetyCertificateOutlined />置信度 86%</span>
                <span><LinkOutlined />引用来源 2 / 3</span>
                <span><ClockCircleOutlined />数据截至 10:30</span>
              </div>
            </article>
          </div>
          <div class="gallery-citation-stack">
            <small class="gallery-specimen-label">引用来源卡片</small>
            <AiEvidenceMeta :evidence="evidence" open-citations />
          </div>
        </div>
        <AiProposalPreview
          current-value="未指派"
          proposed-value="值班维修组"
          impact="仅在人工审批与真实执行成功后改变指派状态"
          stage="preview"
        />
        <div class="gallery-run-row">
          <AiRunStatus state="streaming" message="正在检索授权资料" show-action />
          <AiRunStatus state="failed" message="生成失败" show-action />
        </div>
      </section>

      <section class="gallery-panel gallery-states" data-gallery-section="component-states" aria-labelledby="gallery-states-title">
        <h2 id="gallery-states-title"><span>6</span>组件状态</h2>
        <div class="state-block state-block--buttons">
          <strong>按钮状态</strong>
          <div class="state-column-labels" aria-hidden="true">
            <span />
            <span v-for="state in buttonStates" :key="state">{{ stateLabels[state] }}</span>
          </div>
          <div v-for="kind in buttonKinds" :key="kind.id" class="button-state-row" :data-button-kind="kind.id">
            <span>{{ kind.label }}</span>
            <button
              v-for="state in buttonStates"
              :key="state"
              type="button"
              class="gallery-button"
              :class="[`gallery-button--${kind.id}`, `gallery-button--${state}`]"
              :data-control-state="state"
              :aria-label="`${kind.label}${stateLabels[state]}`"
              :disabled="state === 'disabled' || state === 'loading'"
            >
              <LoadingOutlined v-if="state === 'loading'" spin aria-hidden="true" />
              <span v-else>按钮</span>
            </button>
          </div>
        </div>
        <div class="state-block state-block--inputs">
          <strong>输入框状态</strong>
          <div class="input-samples">
            <label v-for="state in inputStates" :key="state" :data-control-state="state">
              <span class="input-state-label">{{ stateLabels[state] }}</span>
              <input :class="`gallery-input--${state}`" value="输入" :disabled="state === 'disabled' || state === 'loading'" :aria-invalid="state === 'error'" readonly />
              <LoadingOutlined v-if="state === 'loading'" spin aria-hidden="true" />
            </label>
          </div>
        </div>
        <div class="state-block state-block--switches">
          <strong>开关状态</strong>
          <div class="switch-samples">
            <label v-for="item in switchStates" :key="item.label">
              <span>{{ item.label }}</span>
              <button
                type="button"
                class="gallery-switch"
                :class="[`gallery-switch--${item.state}`, { 'gallery-switch--on': item.checked }]"
                role="switch"
                :aria-checked="item.checked"
                :disabled="item.disabled"
                :data-control-state="item.state"
                :aria-label="item.label"
              >
                <LoadingOutlined v-if="item.loading" spin aria-hidden="true" />
                <span v-else class="gallery-switch__track" aria-hidden="true"><i /></span>
              </button>
            </label>
          </div>
        </div>
      </section>
    </div>
  </main>
</template>

<script setup lang="ts">
import {
  AppstoreOutlined,
  BorderOutlined,
  BorderlessTableOutlined,
  BulbOutlined,
  ClockCircleOutlined,
  CloseSquareOutlined,
  ColumnWidthOutlined,
  ExclamationCircleOutlined,
  InfoCircleOutlined,
  LayoutOutlined,
  LinkOutlined,
  LoadingOutlined,
  LockOutlined,
  MenuUnfoldOutlined,
  MobileOutlined,
  PicCenterOutlined,
  SafetyCertificateOutlined,
  StopOutlined,
  TabletOutlined,
  WarningOutlined,
} from '@ant-design/icons-vue'
import { ref } from 'vue'
import AiCommandBar from '../components/ai/AiCommandBar.vue'
import AiEvidenceMeta from '../components/ai/AiEvidenceMeta.vue'
import AiProposalPreview from '../components/ai/AiProposalPreview.vue'
import AiRunStatus from '../components/ai/AiRunStatus.vue'
import AiSafetyState from '../components/ai/AiSafetyState.vue'
import type { AiEvidenceMeta as AiEvidence } from '../types/ai'

const question = ref('本周有哪些运营风险？')
const colors = [
  { token: 'primary', label: '主蓝', value: '#2563EB', usage: '品牌主色、主要操作' },
  { token: 'sidebar', label: 'Sidebar', value: '#163B83', usage: '导航侧边栏背景' },
  { token: 'ai', label: 'AI 靛青', value: '#6366F1', usage: 'AI 相关强调与组件' },
  { token: 'success', label: '成功', value: '#10B981', usage: '成功、完成、积极状态' },
  { token: 'warning', label: '警告', value: '#F59E0B', usage: '警告、注意、待处理' },
  { token: 'danger', label: '危险', value: '#EF4444', usage: '危险、错误、阻断操作' },
  { token: 'background', label: '背景', value: '#F5F7FA', usage: '页面背景色' },
  { token: 'text', label: '正文', value: '#374151', usage: '主要文本颜色' },
  { token: 'border', label: '边框', value: '#E7EDF6', usage: '分割线、边框颜色' },
] as const
const evidence: AiEvidence = {
  basis: 'deterministic',
  confidence: 0.86,
  asOf: '2026-07-27T10:30:00+08:00',
  grounded: true,
  citations: [
    { id: 'gallery-policy', label: '宿舍维修管理办法', locator: '第 3.2 条', version: '2026', access: 'available' },
    { id: 'gallery-handbook', label: '后勤值班手册', locator: '维修响应', version: '2026', access: 'available' },
    { id: 'gallery-denied', label: '限制资料', locator: '-', version: '-', access: 'denied' },
  ],
}
const buttonStates = ['default', 'hover', 'focus', 'active', 'disabled', 'loading', 'error'] as const
const inputStates = ['default', 'hover', 'focus', 'active', 'disabled', 'loading', 'error'] as const
const buttonKinds = [
  { id: 'primary', label: '主按钮' },
  { id: 'secondary', label: '次按钮' },
  { id: 'text', label: '文本' },
] as const
const stateLabels = {
  default: '默认',
  hover: '悬停',
  focus: '聚焦',
  active: '按下',
  disabled: '禁用',
  loading: '加载',
  error: '错误',
} as const
const switchStates = [
  { label: '默认', checked: false, disabled: false, loading: false, state: 'default' },
  { label: '悬停', checked: true, disabled: false, loading: false, state: 'hover' },
  { label: '聚焦', checked: true, disabled: false, loading: false, state: 'focus' },
  { label: '按下', checked: true, disabled: false, loading: false, state: 'active' },
  { label: '禁用', checked: false, disabled: true, loading: false, state: 'disabled' },
  { label: '加载', checked: true, disabled: true, loading: true, state: 'loading' },
  { label: '错误', checked: false, disabled: false, loading: false, state: 'error' },
] as const
</script>

<style scoped>
.design-system-gallery {
  width: 100%;
  min-height: 100vh;
  padding: 23px 33px 24px;
  overflow: hidden;
  color: var(--text);
  background: var(--bg);
  border: 1px solid var(--border);
  border-radius: 10px;
}

.gallery-header { margin-bottom: 10px; padding-left: 4px; }
.gallery-header h1 { margin: 0; color: #0f1f3d; font-size: 45px; font-weight: 700; line-height: 56px; transform: translateY(-4px); }
.gallery-header p { margin: 0 0 0 4px; color: var(--text-muted); font-size: 21.5px; line-height: 28px; }

.gallery-grid {
  display: grid;
  grid-template-columns: 420px 553px minmax(0, 1fr);
  grid-template-areas:
    "colors typography tokens"
    "safety ai states";
  grid-template-rows: 475px 411px;
  gap: 16px;
  align-items: stretch;
}

.gallery-panel {
  min-width: 0;
  padding: 13px 14px;
  overflow: hidden;
  border: 1px solid var(--border);
  border-radius: var(--radius-card);
  background: var(--surface);
  box-shadow: var(--shadow-card);
}

.gallery-panel h2 { display: flex; align-items: center; gap: 9px; margin: 0 0 8px; color: #17233d; font-size: 18px; font-weight: 700; line-height: 24px; }
.gallery-panel h2 > span { width: 24px; height: 24px; display: inline-grid; place-items: center; border-radius: 5px; color: #fff; background: var(--primary); font-size: 13px; }
.gallery-colors h2,
.gallery-typography h2,
.gallery-tokens h2 { margin-bottom: 24px; }
.gallery-colors { grid-area: colors; }
.gallery-typography { grid-area: typography; }
.gallery-tokens { grid-area: tokens; }
.gallery-safety { grid-area: safety; }
.gallery-ai { grid-area: ai; }
.gallery-states { grid-area: states; }

.color-list { margin: 0; padding: 0 6px; list-style: none; }
.color-list li { height: 44px; display: grid; grid-template-columns: 34px 70px 74px minmax(0, 1fr); align-items: center; gap: 20px; border-top: 1px solid var(--border); }
.color-list li:first-child { border-top: 0; }
.color-swatch { width: 32px; height: 32px; border: 1px solid rgb(15 23 42 / 6%); border-radius: 5px; }
.color-list strong { color: var(--text-title); font-size: 13px; }
.color-list code { color: var(--text-strong); font: 12px/1.3 var(--font-family-ui); }
.color-list small { color: var(--text-muted); font-size: 12px; line-height: 1.35; }

.type-samples { border: 1px solid var(--border); border-radius: var(--radius-control); overflow: hidden; }
.type-samples > div { height: 74px; display: grid; grid-template-columns: 66px minmax(0, 1fr) 118px; align-items: center; gap: 8px; padding: 6px 10px; border-top: 1px solid var(--border); }
.type-samples > div:first-child { border-top: 0; }
.type-samples > div:last-child { height: 76px; }
.type-samples > div > small { color: var(--text-muted); font-size: 12px; }
.type-samples p { margin: 0; }
.type-samples code { display: grid; gap: 2px; color: var(--text-strong); font: 12px/1.3 var(--font-family-ui); }
.type-samples code small { color: var(--text-muted); font-size: 12px; font-weight: 400; }
.type-page-title { color: var(--text-title); font-size: 32px; font-weight: 700; line-height: 1.15; white-space: nowrap; }
.type-card-title { color: var(--text-title); font-size: 20px; font-weight: 600; line-height: 1.25; white-space: nowrap; }
.type-body { color: var(--text); font-size: 14px; line-height: 1.5; }
.type-caption { color: var(--text-muted); font-size: 12px; line-height: 1.5; }
.type-metric { color: var(--primary); font-size: 28px; font-weight: 700; }

.token-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); grid-template-rows: repeat(3, 126px); gap: 10px; }
.token-grid > div { display: grid; place-items: center; align-content: center; gap: 7px; padding: 8px; border: 1px solid var(--border); border-radius: var(--radius-control); text-align: center; }
.token-grid .anticon { color: var(--primary); font-size: 30px; }
.token-grid strong { color: var(--text-title); font-size: 13px; }
.token-grid small { color: var(--text-muted); font-size: 12px; }

.safety-tags { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px; }
.safety-tag { min-height: 44px; display: inline-flex; align-items: center; justify-content: center; gap: 5px; padding: 5px 7px; border: 1px solid; border-radius: 6px; font-size: 12px; line-height: 16px; text-align: center; }
.safety-tag--danger { color: #b91c1c; border-color: #fecaca; background: var(--surface-danger); }
.safety-tag--warning { color: #b45309; border-color: #fde68a; background: var(--surface-warning); }
.safety-tag--success { color: #047857; border-color: #a7f3d0; background: var(--surface-success); }
.safety-tag--muted { color: var(--text-muted); border-color: #cbd5e1; background: var(--surface-muted); }
.safety-tag--info { color: #1d4ed8; border-color: var(--primary-border); background: var(--primary-soft); }
.safety-tag--ai { color: #4338ca; border-color: #c7d2fe; background: var(--ai-soft); }
.safety-components { display: grid; gap: 8px; margin-top: 12px; }
.safety-components :deep(.ai-safety-state) { min-height: 44px; padding: 9px 11px; }

.gallery-ai { display: grid; align-content: start; gap: 7px; }
.gallery-ai h2 { margin-bottom: 0; }
.gallery-ai-showcase { display: grid; grid-template-columns: 1.42fr 1fr; gap: 8px; }
.gallery-ai-primary,
.gallery-citation-stack { min-width: 0; }
.gallery-specimen-label { display: block; margin-bottom: 2px; color: var(--text-strong); font-size: 12px; font-weight: 600; line-height: 15px; }
.gallery-ai-primary > .gallery-specimen-label:not(:first-child) { margin-top: 4px; }
.gallery-ai :deep(.ai-command-bar) { height: 44px; min-height: 44px; padding: 0 5px 0 9px; border-radius: var(--radius-control); box-shadow: none; }
.gallery-ai :deep(.ai-command-bar__input),
.gallery-ai :deep(.ai-command-bar__button) { height: 44px; min-height: 44px; }
.gallery-ai :deep(.ai-command-bar__button) { min-width: 78px; padding-inline: 8px; }
.gallery-ai :deep(.ai-command-bar__mark) { font-size: 17px; }
.gallery-suggestion { min-height: 78px; padding: 7px 9px; border: 1px solid #c7d7f6; border-radius: var(--radius-control); background: #fbfdff; }
.gallery-suggestion__title { display: flex; align-items: center; gap: 6px; color: #4338ca; font-size: 12px; font-weight: 600; }
.gallery-suggestion p { margin: 3px 0 5px; font-size: 12px; line-height: 1.35; }
.gallery-evidence-summary { display: flex; align-items: center; gap: 5px; color: var(--text-muted); font-size: 12px; white-space: nowrap; }
.gallery-evidence-summary span { display: inline-flex; align-items: center; gap: 3px; }
.gallery-evidence-summary span:first-child { color: #047857; }
.gallery-citation-stack :deep(.ai-confidence),
.gallery-citation-stack :deep(.ai-evidence__meta),
.gallery-citation-stack :deep(.ai-citations > summary) { display: none; }
.gallery-citation-stack :deep(.ai-citations) { margin: 0; }
.gallery-citation-stack :deep(.ai-citations ul) { margin: 0; padding: 0; }
.gallery-citation-stack :deep(.ai-citations li) { min-height: 44px; padding: 0; border: 1px solid var(--border); border-bottom: 0; list-style: none; }
.gallery-citation-stack :deep(.ai-citations li:first-child) { border-radius: 7px 7px 0 0; }
.gallery-citation-stack :deep(.ai-citations li:last-child) { border-bottom: 1px solid var(--border); border-radius: 0 0 7px 7px; }
.gallery-citation-stack :deep(.ai-citation-link),
.gallery-citation-stack :deep(.ai-citation-denied) { min-height: 44px; padding: 4px 7px; font-size: 12px; }
.gallery-ai :deep(.ai-proposal-preview) { display: grid; grid-template-columns: 1.45fr 1fr; gap: 6px; }
.gallery-ai :deep(.ai-flow) { grid-column: 1 / -1; min-height: 44px; padding: 5px; gap: 6px; font-size: 12px; }
.gallery-ai :deep(.ai-flow__step) { gap: 4px; }
.gallery-ai :deep(.ai-flow__icon) { width: 24px; height: 24px; }
.gallery-ai :deep(.ai-value-diff) { margin: 0; gap: 6px; }
.gallery-ai :deep(.ai-value-diff > div) { min-height: 48px; padding: 5px 7px; font-size: 12px; }
.gallery-ai :deep(.ai-value-diff dt) { font-size: 12px; }
.gallery-ai :deep(.ai-value-diff dd) { margin-top: 2px; }
.gallery-ai :deep(.ai-proposal-preview__impact) { min-height: 48px; margin: 0; padding: 5px; align-items: center; border: 1px solid var(--border); border-radius: 7px; font-size: 12px; line-height: 1.35; }
.gallery-run-row { display: grid; grid-template-columns: 1fr 1fr; gap: 7px; }
.gallery-run-row :deep(.ai-run-status) { height: 44px; min-height: 44px; padding: 0 6px; overflow: hidden; border: 1px solid var(--border); border-radius: 7px; font-size: 12px; }
.gallery-run-row :deep(.ai-run-status__message) { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.gallery-run-row :deep(.ai-run-status__action) { height: 44px; min-width: 44px; min-height: 44px; padding-inline: 6px; font-size: 12px; }

.state-block > strong { display: block; margin-bottom: 3px; color: var(--text-title); font-size: 12px; line-height: 16px; }
.state-block + .state-block { margin-top: 6px; }
.state-column-labels,
.button-state-row { display: grid; grid-template-columns: 48px repeat(7, minmax(44px, 1fr)); gap: 2px; }
.state-column-labels { margin-bottom: 2px; color: var(--text-muted); font-size: 12px; line-height: 14px; text-align: center; }
.button-state-row + .button-state-row { margin-top: 2px; }
.button-state-row > span { display: flex; align-items: center; color: var(--text-strong); font-size: 12px; }
.gallery-button { width: 100%; min-width: 44px; height: 44px; display: inline-flex; align-items: center; justify-content: center; padding: 0 2px; border: 1px solid transparent; border-radius: 5px; font-size: 12px; }
.gallery-button--primary { color: #fff; border-color: var(--primary); background: var(--primary); }
.gallery-button--secondary { color: var(--primary); border-color: var(--primary); background: var(--surface); }
.gallery-button--text { color: var(--primary); background: transparent; }
.gallery-button--hover.gallery-button--primary { border-color: var(--primary-hover); background: var(--primary-hover); }
.gallery-button--hover.gallery-button--secondary,
.gallery-button--hover.gallery-button--text { background: var(--primary-soft); }
.gallery-button--focus { outline: 2px solid rgb(37 99 235 / 34%); outline-offset: -3px; }
.gallery-button--active.gallery-button--primary { background: var(--primary-dark); }
.gallery-button--active.gallery-button--secondary,
.gallery-button--active.gallery-button--text { background: var(--primary-muted); }
.gallery-button--disabled { color: #94a3b8; border-color: #e2e8f0; background: var(--surface-hover); }
.gallery-button--loading { color: var(--primary); border-color: var(--primary-border); background: var(--surface); }
.gallery-button--error { color: #fff; border-color: #b91c1c; background: #b91c1c; }

.input-samples { display: grid; grid-template-columns: repeat(7, minmax(44px, 1fr)); gap: 2px; }
.input-samples label { position: relative; min-width: 44px; padding-top: 16px; }
.input-state-label { position: absolute; top: 0; inset-inline: 0; color: var(--text-muted); font-size: 12px; line-height: 14px; text-align: center; }
.input-samples input { width: 100%; min-width: 44px; height: 44px; padding: 0 4px; border: 1px solid #cbd5e1; border-radius: 5px; color: var(--text); background: var(--surface); font-size: 12px; }
.input-samples label > .anticon { position: absolute; top: 29px; right: 4px; color: var(--primary); }
.input-samples .gallery-input--hover { border-color: #93c5fd; }
.input-samples .gallery-input--focus { border-color: var(--primary); box-shadow: inset 0 0 0 2px rgb(37 99 235 / 22%); }
.input-samples .gallery-input--active { border-color: var(--primary-dark); box-shadow: inset 0 0 0 1px var(--primary-dark); }
.input-samples .gallery-input--disabled { color: #94a3b8; background: var(--surface-hover); }
.input-samples .gallery-input--error { border-color: var(--danger); box-shadow: inset 0 0 0 1px rgb(239 68 68 / 24%); }

.switch-samples { display: grid; grid-template-columns: repeat(7, minmax(44px, 1fr)); gap: 2px; }
.switch-samples label { min-width: 44px; display: grid; justify-items: center; color: var(--text-muted); font-size: 12px; line-height: 14px; }
.gallery-switch { width: 44px; height: 44px; display: grid; place-items: center; padding: 0; border: 0; border-radius: 7px; color: var(--primary); background: transparent; }
.gallery-switch__track { position: relative; width: 38px; height: 22px; border-radius: 999px; background: #cbd5e1; }
.gallery-switch__track i { position: absolute; top: 3px; left: 3px; width: 16px; height: 16px; border-radius: 50%; background: var(--surface); box-shadow: 0 1px 2px rgb(15 23 42 / 22%); }
.gallery-switch--on .gallery-switch__track { background: var(--success); }
.gallery-switch--on .gallery-switch__track i { left: 19px; }
.gallery-switch--hover { background: var(--primary-soft); }
.gallery-switch--focus { outline: 2px solid rgb(37 99 235 / 34%); outline-offset: -3px; }
.gallery-switch--active .gallery-switch__track { background: #059669; }
.gallery-switch--disabled { opacity: .52; }
.gallery-switch--loading { color: #fff; background: var(--primary); }
.gallery-switch--error .gallery-switch__track { background: var(--danger); }

@media (max-width: 1200px) {
  .design-system-gallery { min-height: 100vh; height: auto; padding: 20px; overflow: visible; border-radius: 0; }
  .gallery-header { margin-bottom: 18px; }
  .gallery-header h1 { font-size: 32px; line-height: 40px; }
  .gallery-header p { font-size: 14px; line-height: 22px; }
  .gallery-grid { grid-template-columns: 1fr 1fr; grid-template-areas: "colors typography" "tokens safety" "ai states"; grid-template-rows: auto; }
  .gallery-panel { min-height: 420px; }
}
</style>
