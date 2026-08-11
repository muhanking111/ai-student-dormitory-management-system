import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  designSystemEvidenceContract,
  designSystemGalleryTarget,
  noticePrototypePoints,
  prototypeCaptureTargets,
  prototypeContracts,
} from '../../e2e/live-visual-contracts'
import { designSystemPanelSelector } from '../../e2e/live-visual-contracts'

describe('live visual prototype contract mapping', () => {
  const visualConfigSource = readFileSync(resolve(process.cwd(), 'playwright.visual.config.ts'), 'utf8')
  const visualSpecSource = readFileSync(resolve(process.cwd(), 'e2e/live-visual.spec.ts'), 'utf8')

  it('keeps Dashboard cockpit panels in the design-system style probe', () => {
    expect(designSystemPanelSelector.split(', ')).toContain('.cockpit-panel')
  })

  it('uses exactly the nine final root PNGs and never promotes candidate assets', () => {
    const contractFiles = prototypeContracts.map((contract) => contract.file)
    const capturePrototypes = prototypeCaptureTargets.map((target) => target.prototype)

    expect(contractFiles).toHaveLength(9)
    expect(new Set(contractFiles).size).toBe(9)
    expect(contractFiles.every((file) => !file.includes('/') && !file.includes('candidate'))).toBe(true)
    expect(capturePrototypes.every((file) => contractFiles.includes(file))).toBe(true)
    expect(capturePrototypes.every((file) => !file.includes('candidate'))).toBe(true)
  })

  it('binds the design-system PNG to its dedicated state gallery instead of a business route', () => {
    const designSystem = prototypeContracts.find((contract) => contract.file === 'ai-design-system.png')
    expect(designSystem).toMatchObject({
      evidenceMode: 'computed-style-and-components',
      surfaces: ['global', 'components/ai'],
    })
    expect(designSystemGalleryTarget).toEqual({
      prototype: 'ai-design-system.png',
      route: '/__visual/design-system',
      viewport: '1505x1045',
      slug: 'design-system-gallery',
      contractSurface: 'global-design-system',
    })
    expect(prototypeCaptureTargets.some((target) => target.route === '/ai/knowledge'
      && target.prototype === 'ai-design-system.png')).toBe(false)
    expect(prototypeCaptureTargets.some((target) => target.prototype === 'ai-design-system.png')).toBe(false)
    expect(visualConfigSource).toContain("VITE_VISUAL_EVIDENCE_ENABLED: 'true'")
  })

  it('requires real token, geometry and shared-component evidence for the design system', () => {
    expect(designSystemEvidenceContract).toEqual({
      tokens: ['--primary', '--sidebar', '--touch-target', '--radius-control', '--radius-card'],
      computedStyles: ['fontFamily', 'sidebarBackground', 'panelBorderRadius', 'touchTargetHeight'],
      stateSemantics: ['icon', 'text', 'ariaLive'],
      sharedComponents: ['AiCommandBar', 'AiEvidenceMeta', 'AiRunStatus', 'AiSafetyState'],
    })
  })

  it('uses a multi-paragraph, non-PII notice fixture with prototype-level content density', () => {
    const points = noticePrototypePoints('VISUAL-CONTRACT')

    expect(points.length).toBeGreaterThanOrEqual(120)
    expect(points.length).toBeLessThanOrEqual(200)
    expect(points.split('\n')).toHaveLength(4)
    expect(points).toContain('检查时间')
    expect(points).toContain('工作要求')
    expect(points).not.toMatch(/1\d{10}|\b\d{12}\b|<[^>]+>/)
  })

  it('binds the final manifest to current source and cryptographic screenshot evidence', () => {
    expect(visualSpecSource).toContain('sourceBindings: sourceBindingsAtStart')
    expect(visualSpecSource).toContain('screenshotDetails.push({ ...fileBinding(file, path)')
    expect(visualSpecSource).toContain("assistantScreenshotDetails.push({ ...binding, route: '/', viewport, evidenceSource })")
    expect(visualSpecSource).toContain('...fileBinding(prototypeFile, prototypePath)')
    expect(visualSpecSource).toContain('...fileBinding(streamingAssistantFile, resolve(visualDirectory, streamingAssistantFile))')
    expect(visualSpecSource).toMatch(/sha256:\s*createHash\('sha256'\)/)
  })

  it('keeps all approval actions fully inside the 1536x1024 formal viewport', () => {
    expect(visualSpecSource).toContain("page.locator('.approval-actions button')")
    expect(visualSpecSource).toContain('审批操作按钮必须完整落在正式视口内并保留至少 8px 底部间距')
    expect(visualSpecSource).toContain('批准、拒绝和刷新预览操作完整落在正式视口内')
  })

  it('captures the repair attachment media surface in the formal 1586x992 evidence', () => {
    expect(visualSpecSource).toContain("page.locator('img[data-testid=\"repair-attachment-thumbnail\"]')")
    expect(visualSpecSource).toContain('维修原型内容态必须展示两张附件缩略图')
    expect(visualSpecSource).toContain('附件缩略图必须加载完成且具有可测量尺寸')
    expect(visualSpecSource).toContain('两张附件缩略图媒体表面完整可见且无裁切')
  })

  it('binds Assistant prototypes to late streaming captures and retains complete state screenshots', () => {
    for (const state of [
      'streaming', 'succeeded', 'citation-denied', 'low-confidence', 'no-grounded',
      'failed', 'canceled', 'timed_out', 'history', 'empty', 'input',
    ]) {
      expect(visualSpecSource).toContain(`'${state}'`)
    }
    expect(visualSpecSource).toContain("const requiredAssistantPrototypeStates = ['streaming'] as const")
    expect(visualSpecSource).toContain('live-assistant-streaming-${viewport.name}.png')
    expect(visualSpecSource).toContain("const filePrefix = evidenceSource === 'runtime' ? 'live' : 'fixture'")
    expect(visualSpecSource).toContain('`${filePrefix}-assistant-${kind}-${viewport}.png`')
    expect(visualSpecSource).toMatch(/captureAssistantScreenshot\(\s*page,\s*viewport\.name,\s*'failed',\s*'deterministic-fixture'/s)
    expect(visualSpecSource).toContain("captureAssistantScreenshot(page, viewport.name, 'open', 'runtime')")
    expect(visualSpecSource).toContain("data-assistant-partial")
    expect(visualSpecSource).toContain("data-assistant-evidence-phase")
    expect(visualSpecSource).toContain("streaming-guardrail")
    expect(visualSpecSource).not.toContain(
      'expect(await streamingAnswer.locator(\'[data-assistant-state="citation-available"]\').count()',
    )
    expect(visualSpecSource).toMatch(
      /await expect\.poll\(\s*async \(\) => streamingAnswer\.locator\('\[data-assistant-state="citation-available"\]'\)\.count\(\)/,
    )
    expect(visualSpecSource).toContain('contentStateIds: [streamingStateId]')
    for (const kind of [
      'streaming', 'open', 'safety-states', 'failed', 'canceled',
      'timed-out', 'history', 'empty', 'input',
    ]) {
      expect(visualSpecSource).toContain(`'${kind}'`)
    }
    expect(visualSpecSource).toContain("evidenceSource: 'deterministic-fixture'")
  })

  it('recomputes persisted bindings and records the live write boundary', () => {
    expect(visualSpecSource).toContain('persistedBindingVerification')
    expect(visualSpecSource).toContain('verifyPersistedBindings')
    expect(visualSpecSource).toContain('sourceBindingsAtStart')
    expect(visualSpecSource).toContain('sourceBindingsAtEnd')
    expect(visualSpecSource).toContain('sourceStabilityViolations')
    expect(visualSpecSource).toContain('prototypeBindingsAtStart')
    expect(visualSpecSource).toContain('prototypeBindingsAtEnd')
    expect(visualSpecSource).toContain('prototypeStabilityViolations')
    expect(visualSpecSource).toContain('collectPrototypeBindings')
    expect(visualSpecSource).toMatch(/jsonl\|md/)
    expect(visualSpecSource).toContain("resolve(repositoryDirectory, 'frontend', 'index.html')")
    expect(visualSpecSource).toContain("resolve(repositoryDirectory, 'frontend', 'vite.config.ts')")
    expect(visualSpecSource).toContain("'/api/ai/operations/readiness'")
    expect(visualSpecSource).toContain("providerAlias: 'fake'")
    expect(visualSpecSource).toContain('writeExecutionEnabled: false')
    expect(visualSpecSource).toContain('businessWrites')
    expect(visualSpecSource).toContain("context.on('request'")
    expect(visualSpecSource).not.toContain("page.on('request', (request) =>")
    expect(visualSpecSource).toContain("`after-route:${route.path}`")
    expect(visualSpecSource).toContain("'gallery-before'")
    expect(visualSpecSource).toContain("'gallery-after'")
    expect(visualSpecSource).toContain("resolve(repositoryDirectory, 'frontend', 'e2e', 'stage6-accessibility-live.spec.ts')")
    expect(visualSpecSource).toContain("resolve(repositoryDirectory, 'frontend', 'e2e', 'stage6-risk-live-geometry.spec.ts')")
    expect(visualSpecSource).toContain("resolve(repositoryDirectory, 'frontend', 'e2e', 'ai-live-audit-preflight.ts')")
    expect(visualSpecSource).toContain("resolve(repositoryDirectory, 'frontend', 'e2e', 'ai-live-runtime-switch-preflight.ts')")
    expect(visualSpecSource).toContain('isolation: aiLiveIsolation.evidence')
  })

  it('measures mobile Dashboard pending content before its nested guardrails', () => {
    expect(visualSpecSource)
      .toContain("page.locator('[data-dashboard-section=\"pending\"] > .pending-table-wrap')")
    expect(visualSpecSource)
      .not.toContain("const mobileSectionIds = ['pending', 'guardrails', 'approval-flow'] as const")
  })

  it('pins visible business dates and the 390x844 Dashboard density to the formal reference instant', () => {
    expect(visualSpecSource).toContain('const visualReferenceInstant = requireVisualReferenceInstant()')
    expect(visualSpecSource).toContain('businessDateChecks')
    expect(visualSpecSource).toContain("page.locator('.notice-diff-status')")
    expect(visualSpecSource).toContain('可见业务日期晚于正式 reference instant')
    expect(visualSpecSource).toContain('dashboardMobileGeometryChecks')
    expect(visualSpecSource).toContain('Dashboard 待办标题未在 824px 内进入连续信息流')
    expect(visualSpecSource).toContain('Dashboard 移动文档高度超过 1320px')
    expect(visualSpecSource).toContain('Dashboard 移动页面出现横向滚动')
  })
})
