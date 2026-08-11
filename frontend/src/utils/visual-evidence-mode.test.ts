// @vitest-environment node

import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const frontendRoot = resolve(__dirname, '../..')

describe('visual evidence mode production boundary', () => {
  it('requires both the dev server and the explicit visual evidence flag', async () => {
    const { resolveVisualEvidenceMode } = await import('./visual-evidence-mode')

    expect(resolveVisualEvidenceMode({ dev: true, flag: 'true' })).toBe(true)
    expect(resolveVisualEvidenceMode({ dev: false, flag: 'true' })).toBe(false)
    expect(resolveVisualEvidenceMode({ dev: true, flag: 'false' })).toBe(false)
    expect(resolveVisualEvidenceMode({ dev: true, flag: undefined })).toBe(false)
  })

  it('routes every frontend visual fixture through the shared fail-closed gate', () => {
    const routerSource = readFileSync(resolve(frontendRoot, 'src/router/index.ts'), 'utf8')
    const repairSource = readFileSync(resolve(frontendRoot, 'src/views/RepairManagementView.vue'), 'utf8')
    const demoSource = readFileSync(resolve(frontendRoot, 'src/api/ai-demo.ts'), 'utf8')
    const httpSource = readFileSync(resolve(frontendRoot, 'src/api/ai-http.ts'), 'utf8')

    for (const source of [routerSource, repairSource, demoSource, httpSource]) {
      expect(source).toContain('visualEvidenceMode')
      expect(source).not.toContain("import.meta.env.VITE_VISUAL_EVIDENCE_ENABLED === 'true'")
      expect(source).not.toContain("import.meta.env.VITE_VISUAL_EVIDENCE_ENABLED !== 'true'")
    }

    expect(routerSource).toContain('import.meta.env.DEV && visualEvidenceMode')
    expect(repairSource).toContain('if (import.meta.env.DEV && visualEvidenceMode)')
    expect(repairSource).toContain("await import('../assets/visual-evidence/repair-outlet-evidence.png')")
    expect(repairSource).toContain("await import('../assets/visual-evidence/repair-panel-evidence.png')")
    expect(repairSource).toContain(':aria-label="visualEvidenceAttachmentsAriaLabel"')
    expect(repairSource).toContain("visualEvidenceAttachmentsAriaLabel.value = '视觉证据附件样例，不代表生产工单记录'")
    expect(repairSource).not.toContain('aria-label="视觉证据附件样例')
    expect(repairSource).not.toContain("import repairOutletEvidence from '../assets/visual-evidence/repair-outlet-evidence.png'")
    expect(repairSource).not.toContain("import repairPanelEvidence from '../assets/visual-evidence/repair-panel-evidence.png'")
    expect(httpSource).toMatch(/const visualEvidenceTerminalHoldMs = [\d_]+/)
    expect(httpSource).toContain('}, visualEvidenceTerminalHoldMs)')
  })

  it('keeps provisional assistant evidence open for the full browser observation budget', () => {
    const httpSource = readFileSync(resolve(frontendRoot, 'src/api/ai-http.ts'), 'utf8')
    const visualSource = readFileSync(resolve(frontendRoot, 'e2e/live-visual.spec.ts'), 'utf8')
    const holdMatch = httpSource.match(/const visualEvidenceTerminalHoldMs = ([\d_]+)/)
    const citationBudgetMatch = visualSource.match(
      /助手后段流式证据必须包含至少一个授权引用'[\s\S]{0,240}?timeout:\s*([\d_]+)/,
    )
    const terminalBudgetMatch = visualSource.match(
      /const assistantTerminalEvidenceTimeoutMs = ([\d_]+)/,
    )

    expect(holdMatch, 'visual evidence 必须声明终态保留窗').not.toBeNull()
    expect(citationBudgetMatch, '正式 visual 必须声明授权引用观察预算').not.toBeNull()
    expect(terminalBudgetMatch, '正式 visual 必须声明成功终态等待预算').not.toBeNull()

    const holdMs = Number(holdMatch![1]!.replaceAll('_', ''))
    const citationBudgetMs = Number(citationBudgetMatch![1]!.replaceAll('_', ''))
    const terminalBudgetMs = Number(terminalBudgetMatch![1]!.replaceAll('_', ''))
    expect(holdMs, 'provisional 保留窗必须覆盖完整引用观察预算并留出截图余量')
      .toBeGreaterThanOrEqual(citationBudgetMs + 5_000)
    expect(terminalBudgetMs, '成功终态等待预算必须覆盖完整 provisional 保留窗并留出调度余量')
      .toBeGreaterThanOrEqual(holdMs + 5_000)
    expect(visualSource).toContain('timeout: assistantTerminalEvidenceTimeoutMs')
  })

  it('replaces the demo AI implementation in every production build', () => {
    const aiClientSource = readFileSync(resolve(frontendRoot, 'src/api/ai-client.ts'), 'utf8')
    const viteConfigSource = readFileSync(resolve(frontendRoot, 'vite.config.ts'), 'utf8')
    const productionStubPath = resolve(frontendRoot, 'src/api/ai-demo-production-disabled.ts')

    expect(aiClientSource).toContain("import.meta.env.DEV || import.meta.env.MODE === 'test'")
    expect(viteConfigSource).toContain("command === 'build'")
    expect(viteConfigSource).toContain('find: /^\\.\\/ai-demo$/')
    expect(viteConfigSource).toContain('ai-demo-production-disabled.ts')
    expect(existsSync(productionStubPath)).toBe(true)

    const productionStubSource = readFileSync(productionStubPath, 'utf8')
    expect(productionStubSource).not.toContain('demo-local')
    expect(productionStubSource).not.toContain('proposal-repair-001')
    expect(productionStubSource).not.toContain('knowledge-source-demo')
  })
})
