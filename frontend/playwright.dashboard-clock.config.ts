import { defineConfig } from '@playwright/test'
import baseConfig from './playwright.visual.config'
import liveConfig from './playwright.ai-live.config'

export default defineConfig({
  ...baseConfig,
  metadata: liveConfig.metadata,
  testMatch: 'ai-live-dashboard-stage3-visual.spec.ts',
})
