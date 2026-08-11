import { defineConfig } from '@playwright/test'
import baseConfig from './playwright.ai-live.config'

export default defineConfig({
  ...baseConfig,
  testMatch: 'ai-live-dashboard-stage3-visual.spec.ts',
  outputDir: 'test-results/ai-live-dashboard-stage3-run',
})
