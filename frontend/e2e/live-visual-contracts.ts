export type PrototypeEvidenceMode =
  | 'screenshot-and-content-state'
  | 'computed-style-and-components'

export interface PrototypeContract {
  file: string
  nativeWidth: number
  nativeHeight: number
  logicalViewport: string
  surfaces: readonly string[]
  evidenceMode: PrototypeEvidenceMode
}

export interface PrototypeCaptureTarget {
  prototype: string
  route: string
  viewport: string
  slug: string
  contractSurface: string
}

export interface DesignSystemGalleryTarget {
  prototype: 'ai-design-system.png'
  route: '/__visual/design-system'
  viewport: '1505x1045'
  slug: 'design-system-gallery'
  contractSurface: 'global-design-system'
}

// Keep the style probe aligned with every panel family used by the live routes.
export const designSystemPanelSelector = [
  '.ai-card',
  '.panel',
  '.approval-panel',
  '.audit-panel',
  '.management-panel',
  '.cockpit-panel',
].join(', ')

/**
 * Stable, non-PII fixture used by visual and unit contracts. The text is
 * deliberately long enough to exercise the four-paragraph notice layout.
 */
export function noticePrototypePoints(marker = 'VISUAL-CONTRACT') {
  return [
    `检查时间：本周五 22:00 至 23:30（${marker}）`,
    '检查范围：公共区域用电、消防通道、插线板和易燃物品；请各楼栋提前完成自查。',
    '工作要求：保持通道畅通，发现隐患立即停止使用并联系值班宿管，禁止遮挡消防设施。',
    '整改安排：现场问题按登记结果限期整改，复核结果由宿舍管理中心统一留档并通知相关楼栋。',
  ].join('\n')
}

/**
 * The design-system board describes global tokens and shared components. It is
 * uses a dedicated state gallery rather than pretending a business route is the board.
 */
export const prototypeContracts: readonly PrototypeContract[] = [
  { file: 'ai-dashboard-desktop.png', nativeWidth: 1586, nativeHeight: 992, logicalViewport: '1586x992', surfaces: ['/'], evidenceMode: 'screenshot-and-content-state' },
  { file: 'ai-assistant-desktop.png', nativeWidth: 1586, nativeHeight: 992, logicalViewport: '1586x992', surfaces: ['assistant'], evidenceMode: 'screenshot-and-content-state' },
  { file: 'ai-repair-triage-desktop.png', nativeWidth: 1586, nativeHeight: 992, logicalViewport: '1586x992', surfaces: ['/repairs'], evidenceMode: 'screenshot-and-content-state' },
  { file: 'ai-notice-drafting-desktop.png', nativeWidth: 1536, nativeHeight: 1024, logicalViewport: '1536x1024', surfaces: ['/notices/create'], evidenceMode: 'screenshot-and-content-state' },
  { file: 'ai-risk-center-desktop.png', nativeWidth: 1536, nativeHeight: 1024, logicalViewport: '1536x1024', surfaces: ['/ai/risks'], evidenceMode: 'screenshot-and-content-state' },
  { file: 'ai-approval-audit-desktop.png', nativeWidth: 1536, nativeHeight: 1024, logicalViewport: '1536x1024', surfaces: ['/ai/approvals', '/ai/audit'], evidenceMode: 'screenshot-and-content-state' },
  { file: 'ai-dashboard-mobile.png', nativeWidth: 852, nativeHeight: 1846, logicalViewport: '390x844', surfaces: ['/'], evidenceMode: 'screenshot-and-content-state' },
  { file: 'ai-assistant-mobile.png', nativeWidth: 853, nativeHeight: 1844, logicalViewport: '390x844', surfaces: ['assistant'], evidenceMode: 'screenshot-and-content-state' },
  { file: 'ai-design-system.png', nativeWidth: 1505, nativeHeight: 1045, logicalViewport: 'global', surfaces: ['global', 'components/ai'], evidenceMode: 'computed-style-and-components' },
]

export const prototypeCaptureTargets: readonly PrototypeCaptureTarget[] = [
  { prototype: 'ai-dashboard-desktop.png', route: '/', viewport: '1586x992', slug: 'dashboard-desktop', contractSurface: '/' },
  { prototype: 'ai-repair-triage-desktop.png', route: '/repairs', viewport: '1586x992', slug: 'repair-triage-desktop', contractSurface: '/repairs' },
  { prototype: 'ai-notice-drafting-desktop.png', route: '/notices/create', viewport: '1536x1024', slug: 'notice-drafting-desktop', contractSurface: '/notices/create' },
  { prototype: 'ai-risk-center-desktop.png', route: '/ai/risks', viewport: '1536x1024', slug: 'risk-center-desktop', contractSurface: '/ai/risks' },
  { prototype: 'ai-approval-audit-desktop.png', route: '/ai/approvals', viewport: '1536x1024', slug: 'approval-desktop', contractSurface: '/ai/approvals' },
  { prototype: 'ai-approval-audit-desktop.png', route: '/ai/audit', viewport: '1536x1024', slug: 'audit-desktop', contractSurface: '/ai/audit' },
  { prototype: 'ai-dashboard-mobile.png', route: '/', viewport: '390x844', slug: 'dashboard-mobile', contractSurface: '/' },
]

export const designSystemGalleryTarget: DesignSystemGalleryTarget = {
  prototype: 'ai-design-system.png',
  route: '/__visual/design-system',
  viewport: '1505x1045',
  slug: 'design-system-gallery',
  contractSurface: 'global-design-system',
}

export { designSystemEvidenceContract } from '../src/utils/visual-contracts'
