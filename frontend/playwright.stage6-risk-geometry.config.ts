import { defineConfig } from '@playwright/test'
import visualConfig from './playwright.visual.config'

export default defineConfig({
  ...visualConfig,
  globalSetup: undefined,
  testMatch: 'stage6-risk-live-geometry.spec.ts',
  timeout: 5 * 60_000,
  reporter: 'line',
  outputDir: 'test-results/stage6-risk-live-geometry-run',
})
