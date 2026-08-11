import { defineConfig, devices } from '@playwright/test'

const browserExecutable = process.env.PLAYWRIGHT_BROWSER_EXECUTABLE?.trim() || undefined

export default defineConfig({
  testDir: './e2e',
  testMatch: 'stage1-shell-design-system.spec.ts',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'line',
  outputDir: 'test-results/stage1-shell-20260727-m/run',
  use: {
    baseURL: 'http://127.0.0.1:5303',
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    reducedMotion: 'reduce',
    launchOptions: browserExecutable ? { executablePath: browserExecutable } : undefined,
  },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5303 --strictPort',
    url: 'http://127.0.0.1:5303/__visual/design-system',
    env: {
      VITE_VISUAL_EVIDENCE_ENABLED: 'true',
      VITE_AI_DEMO_ENABLED: 'true',
      VITE_AI_ENABLED: 'false',
    },
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
  projects: [{ name: 'edge', use: { ...devices['Desktop Chrome'] } }],
})
