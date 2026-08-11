import { defineConfig } from '@playwright/test'
import baseConfig from './playwright.ai-live.config'

export default defineConfig({
  ...baseConfig,
  testMatch: 'ai-live-stage2-visual.spec.ts',
  outputDir: 'test-results/ai-live-stage2-run',
})
