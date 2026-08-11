import { defineConfig, devices } from '@playwright/test'

const browserExecutable = process.env.PLAYWRIGHT_BROWSER_EXECUTABLE?.trim() || undefined

export default defineConfig({
  testDir: './e2e',
  testIgnore: [
    '**/live-visual.spec.ts',
    '**/ai-live-*.spec.ts',
    '**/repair-mobile-layout.spec.ts',
    '**/stage1-shell-design-system.spec.ts',
    '**/assistant-stage2-contract.spec.ts',
    '**/dashboard-stage3-contract.spec.ts',
    '**/repair-notice-stage4-contract.spec.ts',
    '**/governance-stage5-contract.spec.ts',
    '**/stage6-accessibility-live.spec.ts',
    '**/stage6-risk-live-geometry.spec.ts',
  ],
  timeout: 30_000,
  expect: {
    timeout: 10_000,
  },
  workers: 8,
  outputDir: 'test-results/run',
  use: {
    baseURL: 'http://127.0.0.1:5174',
    launchOptions: browserExecutable ? { executablePath: browserExecutable } : undefined,
    trace: 'on-first-retry',
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5174 --strictPort',
    url: 'http://127.0.0.1:5174',
    env: {
      VITE_AI_DEMO_ENABLED: 'true',
      VITE_AI_ENABLED: 'false',
      VITE_VISUAL_EVIDENCE_ENABLED: 'true',
    },
    reuseExistingServer: false,
    timeout: 120_000,
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
})
