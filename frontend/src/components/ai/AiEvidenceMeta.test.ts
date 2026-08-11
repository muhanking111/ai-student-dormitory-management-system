import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setAiClient } from '../../api/ai-client'
import AiEvidenceMeta from './AiEvidenceMeta.vue'

const evidence = {
  asOf: '2026-07-12T10:00:00Z',
  grounded: true,
  citations: [{ id: 'citation-1', label: '维修制度', locator: '第 1 段', version: 'v1', access: 'available' as const }],
}

describe('AiEvidenceMeta citation detail', () => {
  beforeEach(() => setAiClient(undefined))

  it('确定性证据没有模型置信度时不显示置信度标签', () => {
    const wrapper = mount(AiEvidenceMeta, { props: { evidence: { ...evidence, basis: 'deterministic' as const } } })

    expect(wrapper.text()).not.toContain('置信度')
  })

  it('点击授权 citation 后只发出 typed 事件，不自行请求数据', async () => {
    const getCitation = vi.fn()
    setAiClient({ getCitation })
    const wrapper = mount(AiEvidenceMeta, { props: { evidence } })

    await wrapper.get('button.ai-citation-link').trigger('click')

    expect(getCitation).not.toHaveBeenCalled()
    expect(wrapper.emitted('request-citation')).toEqual([['citation-1']])
  })

  it('由父层注入 citation 详情并始终按纯文本展示', () => {
    const citationDetail = {
      id: 'citation-1', type: 'KNOWLEDGE', sourceId: 'source-1', documentId: 'document-1',
      documentVersionId: 'version-1', chunkId: 'chunk-1', rank: 1, score: 0.9,
      quote: '<script>不会执行</script> 允许展示的脱敏片段', locator: '第 1 段',
      contentHash: 'a'.repeat(64), createdAt: '2026-07-12T10:00:00Z',
    } as const
    const wrapper = mount(AiEvidenceMeta, {
      props: {
        evidence,
        citationDetails: { 'citation-1': citationDetail },
        expandedCitationIds: ['citation-1'],
      },
    })

    expect(wrapper.get('pre').text()).toContain('<script>不会执行</script>')
    expect(wrapper.find('script').exists()).toBe(false)
  })

  it('citation 撤权或 Kill Switch 拒绝时只显示锁定态，不回放正文', () => {
    const wrapper = mount(AiEvidenceMeta, { props: { evidence, deniedCitationIds: ['citation-1'] } })

    expect(wrapper.text()).toContain('无权限查看此来源')
    expect(wrapper.find('pre').exists()).toBe(false)
  })

  it('展示置信度、可查看引用数和 as-of，长中文不会丢失', () => {
    const longLabel = '宿舍维修管理办法与授权范围说明'.repeat(8)
    const wrapper = mount(AiEvidenceMeta, {
      props: {
        evidence: {
          ...evidence,
          confidence: 0.864,
          citations: [
            { ...evidence.citations[0], label: longLabel },
            { id: 'citation-2', label: '受限来源', locator: '内部', version: 'v2', access: 'denied' as const },
          ],
        },
      },
    })

    expect(wrapper.text()).toContain('置信度 86%')
    expect(wrapper.text()).toContain('引用来源 1 / 2')
    expect(wrapper.text()).toContain('数据截至 2026-07-12 10:00')
    expect(wrapper.text()).toContain(longLabel)
  })
})
