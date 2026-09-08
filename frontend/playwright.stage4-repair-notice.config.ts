import { defineConfig, devices } from '@playwright/test'
import baseConfig from './playwright.config'

const baseURL = 'http://127.0.0.1:5177'
const outputName = (process.env.VISUAL_OUTPUT_NAME?.trim() || 'stage4-repair-notice-20260801-j')
  .replace(/[^A-Za-z0-9._-]/g, '_')

export default defineConfig({
  ...baseConfig,
  testIgnore: [],
  testMatch: 'repair-notice-stage4-contract.spec.ts',
  timeout: 3 * 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  outputDir: `test-results/${outputName}/playwright`,
  use: {
    ...baseConfig.use,
    baseURL,
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5177 --strictPort',
    url: baseURL,
    env: {
      VITE_AI_DEMO_ENABLED: 'true',
      VITE_AI_ENABLED: 'false',
      VITE_E2E_DISABLE_HMR: 'true',
      VITE_VISUAL_EVIDENCE_ENABLED: 'true',
    },
    reuseExistingServer: false,
    timeout: 120_000,
  },
  projects: [
    { name: 'stage4-edge', use: { ...devices['Desktop Chrome'] } },
  ],
})
