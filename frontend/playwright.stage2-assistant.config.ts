import { defineConfig, devices } from '@playwright/test'
import baseConfig from './playwright.config'

export default defineConfig({
  ...baseConfig,
  testIgnore: [],
  testMatch: 'assistant-stage2-contract.spec.ts',
  timeout: 2 * 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  outputDir: 'test-results/stage2-assistant-run',
  projects: [
    { name: 'stage2-edge', use: { ...devices['Desktop Chrome'] } },
  ],
})
