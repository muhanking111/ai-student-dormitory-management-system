/** Runtime evidence required by the global design-system board. */
export const designSystemEvidenceContract = {
  tokens: ['--primary', '--sidebar', '--touch-target', '--radius-control', '--radius-card'],
  computedStyles: ['fontFamily', 'sidebarBackground', 'panelBorderRadius', 'touchTargetHeight'],
  stateSemantics: ['icon', 'text', 'ariaLive'],
  sharedComponents: ['AiCommandBar', 'AiEvidenceMeta', 'AiRunStatus', 'AiSafetyState'],
} as const
