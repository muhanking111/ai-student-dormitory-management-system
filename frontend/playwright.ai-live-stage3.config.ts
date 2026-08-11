import { defineConfig, devices } from '@playwright/test'
import {
  visualBackendEnv,
  visualBackendURL,
  visualBaseURL,
  visualFrontendPort,
} from './e2e/live-visual-settings'
import { requireAiLiveIsolation } from './e2e/ai-live-isolation'

const browserExecutable = process.env.VISUAL_BROWSER_EXECUTABLE?.trim() || undefined
const visualAiIsolation = requireAiLiveIsolation(visualBackendEnv)
const visualCorsAllowedOrigins = Array.from(new Set([
  ...(visualBackendEnv.CORS_ALLOWED_ORIGINS ?? '').split(',').map((origin) => origin.trim()).filter(Boolean),
  visualBaseURL,
  `http://localhost:${visualFrontendPort}`,
])).join(',')
const visualJavaToolOptions = [
  visualBackendEnv.JAVA_TOOL_OPTIONS,
  `-Ddormitory.cors.allowed-origins=${visualCorsAllowedOrigins}`,
].filter((value): value is string => Boolean(value && value.trim())).join(' ')

const visualAiBackendEnv = {
  ...visualBackendEnv,
  ...visualAiIsolation.backendEnv,
  SPRING_PROFILES_ACTIVE: 'dev',
  SPRING_DATASOURCE_URL: visualBackendEnv.DB_URL,
  SPRING_DATASOURCE_USERNAME: visualBackendEnv.DB_USERNAME,
  SPRING_DATASOURCE_PASSWORD: visualBackendEnv.DB_PASSWORD,
  SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'com.mysql.cj.jdbc.Driver',
  CORS_ALLOWED_ORIGINS: visualCorsAllowedOrigins,
  SPRING_AI_MODEL_CHAT: 'none',
  SPRING_AI_MODEL_EMBEDDING: 'none',
  DEMO_DATA_ENABLED: 'true',
  AI_ENABLED: 'true',
  AI_CAPABILITY_ASSISTANT_ENABLED: 'true',
  AI_CAPABILITY_KNOWLEDGE_ENABLED: 'true',
  AI_CAPABILITY_DASHBOARD_ENABLED: 'true',
  AI_CAPABILITY_REPAIR_ENABLED: 'true',
  AI_CAPABILITY_NOTICE_ENABLED: 'true',
  AI_PROVIDER_ACTIVE: 'fake',
  AI_STREAMING_ENABLED: 'true',
  AI_WRITE_EXECUTION_ENABLED: 'false',
  AI_ROLLOUT_ENABLED: 'true',
  AI_ROLLOUT_BASIS_POINTS: '10000',
  AI_ROLLOUT_POLICY_VERSION: 'ai-live-e2e-rollout-v1',
  AI_ROLLOUT_HMAC_KEY: 'ai-live-e2e-rollout-key-with-at-least-32-bytes',
  AI_AUDIT_HMAC_KEY: 'ai-live-e2e-audit-integrity-key-v1-20260712',
  AI_TOKENIZATION_HMAC_KEY: 'ai-live-e2e-tokenization-key-v1-20260712',
  AI_STEP_UP_HMAC_KEY: 'ai-live-e2e-step-up-proof-key-v1-20260712',
  AI_RUN_RESERVED_TOKENS: '20000',
  JAVA_TOOL_OPTIONS: visualJavaToolOptions,
}

export default defineConfig({
  testDir: './e2e',
  testMatch: 'ai-live-stage3-visual.spec.ts',
  globalSetup: './e2e/ai-live-global-setup.ts',
  timeout: 15 * 60_000,
  expect: {
    timeout: 20_000,
  },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'line',
  outputDir: 'test-results/live-visual-run',
  use: {
    baseURL: visualBaseURL,
    actionTimeout: 20_000,
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    launchOptions: browserExecutable ? { executablePath: browserExecutable } : undefined,
  },
  webServer: [
    {
      command: 'mvn -o -f ../backend/pom.xml spring-boot:run',
      url: `${visualBackendURL}/api/auth/me`,
      env: visualAiBackendEnv,
      reuseExistingServer: !process.env.CI,
      timeout: 180_000,
      stdout: 'ignore',
      stderr: 'pipe',
    },
    {
      command: `npm run dev -- --host 127.0.0.1 --port ${visualFrontendPort} --strictPort`,
      url: visualBaseURL,
      env: {
        VITE_AI_DEMO_ENABLED: 'false',
        VITE_AI_ENABLED: 'true',
        VITE_BACKEND_PROXY_TARGET: visualBackendURL,
      },
      reuseExistingServer: !process.env.CI,
      timeout: 120_000,
      stdout: 'ignore',
      stderr: 'pipe',
    },
  ],
  projects: [
    { name: 'live-visual-chromium', use: { ...devices['Desktop Chrome'] } },
  ],
})
