import { defineConfig } from '@playwright/test'
import visualConfig from './playwright.visual.config'

export default defineConfig({
  ...visualConfig,
  testMatch: 'stage6-accessibility-live.spec.ts',
  reporter: 'line',
  outputDir: 'test-results/stage6-accessibility-run',
})
