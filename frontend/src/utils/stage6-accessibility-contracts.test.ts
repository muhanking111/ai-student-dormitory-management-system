// @vitest-environment node

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  STAGE6_ACCESSIBILITY_PROFILES,
  STAGE6_ACCESSIBILITY_ROUTES,
  analyzeContrastSample,
  bindingMatches,
  findActiveAnimationViolations,
  hasVisibleFocusIndicator,
  sha256Hex,
  textClipViolation,
  touchTargetAssessment,
} from '../../e2e/stage6-accessibility-live.spec'

describe('Stage 6 accessibility live contracts', () => {
  it('covers every protected route at 200% equivalent and mobile touch profiles', () => {
    expect(STAGE6_ACCESSIBILITY_ROUTES).toHaveLength(22)
    expect(new Set(STAGE6_ACCESSIBILITY_ROUTES.map(({ path }) => path)).size).toBe(22)
    expect(STAGE6_ACCESSIBILITY_PROFILES).toEqual([
      { name: '200pct-1920x1080', width: 960, height: 540, touch: false },
      { name: 'touch-390x844', width: 390, height: 844, touch: true },
    ])
  })

  it('composites translucent foreground and background colors before applying WCAG thresholds', () => {
    const blackOnWhite = analyzeContrastSample({
      selector: 'p',
      text: '普通正文',
      color: 'rgb(0, 0, 0)',
      backgroundLayers: ['rgba(0, 0, 0, 0)', 'rgb(255, 255, 255)'],
      fontSizePx: 14,
      fontWeight: 400,
    })
    expect(blackOnWhite.ratio).toBeCloseTo(21, 2)
    expect(blackOnWhite.threshold).toBe(4.5)
    expect(blackOnWhite.violation).toBeNull()

    const weakText = analyzeContrastSample({
      selector: '.muted',
      text: '必要辅助文字',
      color: 'rgba(255, 255, 255, 0.65)',
      backgroundLayers: ['rgb(37, 99, 235)'],
      fontSizePx: 14,
      fontWeight: 500,
    })
    expect(weakText.ratio).toBeLessThan(4.5)
    expect(weakText.violation).toContain('contrast')

    const largeText = analyzeContrastSample({
      selector: 'h1',
      text: '页面标题',
      color: 'rgb(117, 117, 117)',
      backgroundLayers: ['rgb(255, 255, 255)'],
      fontSizePx: 24,
      fontWeight: 400,
    })
    expect(largeText.threshold).toBe(3)
    expect(largeText.violation).toBeNull()
  })

  it('requires a real focus-visible outline or shadow', () => {
    expect(hasVisibleFocusIndicator({
      focusVisible: true,
      outlineStyle: 'solid',
      outlineWidth: '2px',
      outlineColor: 'rgb(37, 99, 235)',
      boxShadow: 'none',
    })).toBe(true)
    expect(hasVisibleFocusIndicator({
      focusVisible: true,
      outlineStyle: 'none',
      outlineWidth: '0px',
      outlineColor: 'rgba(0, 0, 0, 0)',
      boxShadow: 'none',
    })).toBe(false)
    expect(hasVisibleFocusIndicator({
      focusVisible: false,
      outlineStyle: 'solid',
      outlineWidth: '2px',
      outlineColor: 'rgb(37, 99, 235)',
      boxShadow: 'none',
    })).toBe(false)
  })

  it('rejects running non-zero animations but accepts zero-duration or finished motion', () => {
    expect(findActiveAnimationViolations([
      { target: '.spinner', playState: 'running', durationMs: 800, name: 'spin' },
      { target: '.finished', playState: 'finished', durationMs: 300, name: 'fade' },
      { target: '.disabled', playState: 'running', durationMs: 0, name: 'none' },
    ])).toEqual(['.spinner animation spin remains running for 800ms'])
  })

  it('flags clipped necessary text unless the full text has an auditable accessible alternative', () => {
    expect(textClipViolation({
      selector: '.label',
      text: '完整必要文字',
      scrollWidth: 130,
      clientWidth: 90,
      scrollHeight: 24,
      clientHeight: 24,
      title: '',
      ariaLabel: '',
    })).toContain('horizontal text clipping')
    expect(textClipViolation({
      selector: '.label',
      text: '完整必要文字',
      scrollWidth: 130,
      clientWidth: 90,
      scrollHeight: 24,
      clientHeight: 24,
      title: '完整必要文字',
      ariaLabel: '',
    })).toBeNull()
  })

  it('enforces 44x44 touch targets with only an explicit inline prose-link exception', () => {
    expect(touchTargetAssessment({
      selector: 'button.icon',
      tag: 'BUTTON',
      width: 40,
      height: 44,
      display: 'inline-flex',
      accessibleName: '刷新',
      parentTag: 'DIV',
      hasGraphic: true,
      controlType: '',
      labelWidth: 0,
      labelHeight: 0,
    })).toEqual({ violation: 'button.icon effective target is 40x44px', exception: null })

    expect(touchTargetAssessment({
      selector: 'a.policy-link',
      tag: 'A',
      width: 66,
      height: 20,
      display: 'inline',
      accessibleName: '查看政策',
      parentTag: 'P',
      hasGraphic: false,
      controlType: '',
      labelWidth: 0,
      labelHeight: 0,
    })).toEqual({
      violation: null,
      exception: 'INLINE_PROSE_LINK: a.policy-link (66x20px)',
    })

    expect(touchTargetAssessment({
      selector: 'input[type="checkbox"]',
      tag: 'INPUT',
      width: 16,
      height: 16,
      display: 'inline-block',
      accessibleName: '确认变更',
      parentTag: 'LABEL',
      hasGraphic: false,
      controlType: 'checkbox',
      labelWidth: 120,
      labelHeight: 44,
    })).toEqual({ violation: null, exception: null })

    expect(touchTargetAssessment({
      selector: 'span.ant-input-affix-wrapper > input.ant-input',
      tag: 'INPUT',
      width: 244,
      height: 22,
      compositeWidth: 244,
      compositeHeight: 44,
      display: 'block',
      accessibleName: '搜索报修单',
      parentTag: 'SPAN',
      hasGraphic: false,
      controlType: 'text',
      labelWidth: 0,
      labelHeight: 0,
    })).toEqual({ violation: null, exception: null })

    expect(touchTargetAssessment({
      selector: 'span.ant-input-affix-wrapper > input.ant-input',
      tag: 'INPUT',
      width: 91,
      height: 22,
      compositeWidth: 91,
      compositeHeight: 34,
      display: 'block',
      accessibleName: '搜索用户',
      parentTag: 'SPAN',
      hasGraphic: false,
      controlType: 'text',
      labelWidth: 0,
      labelHeight: 0,
    })).toEqual({
      violation: 'span.ant-input-affix-wrapper > input.ant-input effective target is 91x34px',
      exception: null,
    })
  })

  it('pins accessible semantic colors and mobile compound-control geometry', () => {
    const themeSource = readFileSync(resolve(process.cwd(), 'src/design/theme.ts'), 'utf8')
    const globalStyles = readFileSync(resolve(process.cwd(), 'src/style.css'), 'utf8')
    const auditSource = readFileSync(resolve(process.cwd(), 'src/views/AiAuditView.vue'), 'utf8')
    const approvalSource = readFileSync(resolve(process.cwd(), 'src/views/AiApprovalView.vue'), 'utf8')

    expect(globalStyles).toContain('--text-muted: #5b6b82;')
    expect(themeSource).toContain("colorTextPlaceholder: '#5b6b82'")
    expect(themeSource).toContain("colorTextDescription: '#5b6b82'")
    expect(globalStyles).toMatch(/\.admin-header \.ant-breadcrumb-link\s*\{[^}]*color:\s*var\(--text-muted\);/s)
    expect(globalStyles).toMatch(/\.ant-select-selection-placeholder,[\s\S]*?\.ant-empty-description\s*\{[^}]*color:\s*var\(--text-muted\)\s*!important;/s)
    expect(globalStyles).toMatch(/\.management-page \.ant-tag-green\s*\{[^}]*color:\s*var\(--success-strong\)\s*!important;/s)
    expect(globalStyles).toMatch(/\.management-page \.ant-tag-orange\s*\{[^}]*color:\s*var\(--warning-strong\)\s*!important;/s)
    expect(globalStyles).toMatch(/\.management-page \.ant-btn-link\.ant-btn-dangerous\s*\{[^}]*color:\s*var\(--danger-strong\)\s*!important;/s)
    expect(globalStyles).toMatch(/@media\s*\(max-width:\s*1100px\)[\s\S]*?\.management-page \.ant-input-affix-wrapper,[\s\S]*?\.management-page \.ant-select-selector\s*\{[^}]*min-height:\s*var\(--touch-target\)(?:\s*!important)?\s*;/s)
    expect(globalStyles).toMatch(/@media\s*\(max-width:\s*1100px\)[\s\S]*?\.management-page \.ant-pagination-item,[\s\S]*?\.management-page \.ant-pagination-next\s*\{[^}]*min-width:\s*var\(--touch-target\)(?:\s*!important)?\s*;[^}]*height:\s*var\(--touch-target\)(?:\s*!important)?\s*;/s)
    expect(auditSource).toMatch(/\.success\s*\{[^}]*color:\s*var\(--success-strong\)\s*!important;/s)
    expect(auditSource).toMatch(/\.audit-evidence-group\.missing > header span\s*\{[^}]*color:\s*var\(--text-strong\);/s)
    expect(auditSource).toMatch(/\.governance-tabs a\s*\{[^}]*color:\s*var\(--text-muted\);/s)
    expect(approvalSource).toMatch(/\.governance-tabs a\s*\{[^}]*color:\s*var\(--text-muted\);/s)
  })

  it('recomputes SHA-256 evidence instead of trusting persisted metadata', () => {
    const bytes = new TextEncoder().encode('stage6-accessibility')
    const hash = sha256Hex(bytes)
    expect(hash).toMatch(/^[A-F0-9]{64}$/)
    expect(bindingMatches({ file: 'evidence.txt', bytes: bytes.length, sha256: hash }, bytes)).toBe(true)
    expect(bindingMatches({ file: 'evidence.txt', bytes: bytes.length, sha256: '0'.repeat(64) }, bytes)).toBe(false)
  })

  it('keeps the browser contract auditable and read-only', () => {
    const source = readFileSync(resolve(process.cwd(), 'e2e/stage6-accessibility-live.spec.ts'), 'utf8')
    expect(source).toContain("page.keyboard.press('Tab')")
    expect(source).toContain("matchMedia('(prefers-reduced-motion: reduce)').matches")
    expect(source).toContain('document.getAnimations({ subtree: true })')
    expect(source).toContain("'/api/ai/operations/readiness'")
    expect(source).toContain('businessWrites')
    expect(source).toContain('screenshotDetails')
    expect(source).toContain('sourceBindings')
    expect(source).toContain('persistedBindingViolations')
    expect(source).toContain('inactiveAncestor')
    expect(source).toContain('symbolOnly')
    expect(source).toContain('selectorSegment')
    expect(source).toContain('compositeWidth')
    expect(source).toContain('descendantAccessibleName')
  })
})
