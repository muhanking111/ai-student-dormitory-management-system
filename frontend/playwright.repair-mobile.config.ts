import { defineConfig } from '@playwright/test'
import visualConfig from './playwright.visual.config'

export default defineConfig(visualConfig, {
  testMatch: 'repair-mobile-layout.spec.ts',
  timeout: 5 * 60_000,
  outputDir: 'test-results/repair-mobile-layout-run',
})
