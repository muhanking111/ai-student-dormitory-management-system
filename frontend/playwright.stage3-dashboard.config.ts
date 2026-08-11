import { defineConfig, devices } from '@playwright/test'
import baseConfig from './playwright.config'

export default defineConfig({
  ...baseConfig,
  testIgnore: [],
  testMatch: 'dashboard-stage3-contract.spec.ts',
  timeout: 3 * 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  outputDir: 'test-results/stage3-dashboard-run',
  projects: [
    { name: 'stage3-edge', use: { ...devices['Desktop Chrome'] } },
  ],
})
