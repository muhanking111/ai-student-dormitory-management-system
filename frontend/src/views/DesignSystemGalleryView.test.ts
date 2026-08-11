import { mount } from '@vue/test-utils'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import AiCommandBar from '../components/ai/AiCommandBar.vue'
import AiEvidenceMeta from '../components/ai/AiEvidenceMeta.vue'
import AiProposalPreview from '../components/ai/AiProposalPreview.vue'
import AiRunStatus from '../components/ai/AiRunStatus.vue'
import AiSafetyState from '../components/ai/AiSafetyState.vue'
import DesignSystemGalleryView from './DesignSystemGalleryView.vue'

const gallerySource = readFileSync(resolve(process.cwd(), 'src/views/DesignSystemGalleryView.vue'), 'utf8')

describe('DesignSystemGalleryView', () => {
  it('覆盖设计系统原型的六类区域和完整控件状态', () => {
    const wrapper = mount(DesignSystemGalleryView)

    expect(wrapper.get('[data-design-system-gallery]').attributes('aria-label')).toBe('AI 智能宿舍设计系统状态画廊')
    expect(wrapper.findAll('[data-gallery-section]').map((section) => section.attributes('data-gallery-section'))).toEqual([
      'colors',
      'typography',
      'tokens',
      'safety',
      'ai-components',
      'component-states',
    ])
    expect(wrapper.findAll('[data-control-state]').map((state) => state.attributes('data-control-state'))).toEqual(expect.arrayContaining([
      'default',
      'hover',
      'focus',
      'active',
      'disabled',
      'loading',
      'error',
    ]))
  })

  it('直接渲染真实共享 AI 组件而非相似静态标本', () => {
    const wrapper = mount(DesignSystemGalleryView)

    expect(wrapper.findComponent(AiCommandBar).exists()).toBe(true)
    expect(wrapper.findComponent(AiEvidenceMeta).exists()).toBe(true)
    expect(wrapper.findComponent(AiProposalPreview).exists()).toBe(true)
    expect(wrapper.findComponent(AiRunStatus).exists()).toBe(true)
    expect(wrapper.findComponent(AiSafetyState).exists()).toBe(true)
    expect(wrapper.text()).not.toMatch(/PERSON_NAME:v1:|<PERSON_[A-Z_]+>/)
  })

  it('使用原型定义的引用卡和主次文本按钮状态矩阵', () => {
    const wrapper = mount(DesignSystemGalleryView)

    expect(wrapper.findAll('[data-citation-card]')).toHaveLength(3)
    expect(wrapper.find('[data-citation-card][data-access="denied"]').exists()).toBe(true)

    for (const kind of ['primary', 'secondary', 'text']) {
      const row = wrapper.get(`[data-button-kind="${kind}"]`)
      expect(row.findAll('[data-control-state]')).toHaveLength(7)
    }

    expect(wrapper.get('.type-page-title').attributes('data-font-size')).toBe('32px')
    expect(wrapper.get('.type-card-title').attributes('data-font-size')).toBe('20px')
  })

  it('所有必要状态和元数据文字均遵守 12px 辅助信息基线', () => {
    const undersizedDeclarations = [...gallerySource.matchAll(/font-size:\s*([\d.]+)px/g)]
      .map((match) => Number(match[1]))
      .filter((fontSize) => fontSize < 12)

    expect(undersizedDeclarations).toEqual([])
  })
})
