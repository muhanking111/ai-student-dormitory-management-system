import { defineConfig, devices } from '@playwright/test'
import baseConfig from './playwright.config'

const baseURL = 'http://127.0.0.1:5178'

export default defineConfig({
  ...baseConfig,
  globalSetup: './e2e/stage5-governance-global-setup.ts',
  testIgnore: [],
  testMatch: 'governance-stage5-contract.spec.ts',
  timeout: 3 * 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  outputDir: 'test-results/stage5-governance-run',
  use: {
    ...baseConfig.use,
    baseURL,
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5178 --strictPort',
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
    { name: 'stage5-edge', use: { ...devices['Desktop Chrome'] } },
  ],
})
