import { defineConfig, devices } from '@playwright/test'
import {
  visualBackendEnv,
  visualBackendPort,
  visualBackendURL,
  visualBaseURL,
  visualFrontendPort,
  visualOutputName,
} from './e2e/live-visual-settings'
import { requireAiLiveIsolation } from './e2e/ai-live-isolation'

const browserExecutable = process.env.VISUAL_BROWSER_EXECUTABLE?.trim() || undefined
const corsAllowedOrigins = Array.from(new Set([
  ...(visualBackendEnv.CORS_ALLOWED_ORIGINS ?? '')
    .split(',')
    .map((origin) => origin.trim())
    .filter(Boolean),
  visualBaseURL,
  `http://localhost:${visualFrontendPort}`,
])).join(',')

const stage5RedisKeyPrefix = process.env.AI_LIVE_STAGE5_REDIS_KEY_PREFIX?.trim()
  || `ai-live:stage5:${visualOutputName.replaceAll('.', '-')}`
const stage5Isolation = requireAiLiveIsolation({
  ...visualBackendEnv,
  AI_REDIS_KEY_PREFIX: stage5RedisKeyPrefix,
}, { requireOutputBoundRedisPrefix: true })
const javaToolOptions = [
  process.env.AI_LIVE_STAGE5_JAVA_TOOL_OPTIONS,
  visualBackendEnv.JAVA_TOOL_OPTIONS,
  `-Ddormitory.cors.allowed-origins=${corsAllowedOrigins}`,
].filter((value): value is string => Boolean(value && value.trim())).join(' ')

const backendEnv = {
  ...visualBackendEnv,
  ...stage5Isolation.backendEnv,
  SERVER_PORT: visualBackendPort,
  CORS_ALLOWED_ORIGINS: corsAllowedOrigins,
  JAVA_TOOL_OPTIONS: javaToolOptions,
  SPRING_PROFILES_ACTIVE: 'dev',
  SPRING_DATASOURCE_URL: visualBackendEnv.DB_URL,
  SPRING_DATASOURCE_USERNAME: visualBackendEnv.DB_USERNAME,
  SPRING_DATASOURCE_PASSWORD: visualBackendEnv.DB_PASSWORD,
  SPRING_DATASOURCE_DRIVER_CLASS_NAME: 'com.mysql.cj.jdbc.Driver',
  SPRING_AI_MODEL_CHAT: 'none',
  SPRING_AI_MODEL_EMBEDDING: 'none',
  DEMO_DATA_ENABLED: 'true',
  AI_ENABLED: 'true',
  AI_CAPABILITY_ASSISTANT_ENABLED: 'true',
  AI_CAPABILITY_KNOWLEDGE_ENABLED: 'true',
  AI_CAPABILITY_DASHBOARD_ENABLED: 'true',
  AI_CAPABILITY_REPAIR_ENABLED: 'true',
  AI_CAPABILITY_NOTICE_ENABLED: 'true',
  AI_CAPABILITY_RISK_ENABLED: 'true',
  AI_CAPABILITY_EVALUATION_ENABLED: 'false',
  AI_PROVIDER_ACTIVE: 'fake',
  AI_RISK_EXPLANATION_ENABLED: 'true',
  AI_RISK_EXPLANATION_ADAPTER: 'fake',
  AI_STREAMING_ENABLED: 'true',
  AI_WRITE_EXECUTION_ENABLED: 'false',
  AI_ROLLOUT_ENABLED: 'true',
  AI_ROLLOUT_BASIS_POINTS: '10000',
  AI_ROLLOUT_POLICY_VERSION: 'ai-live-stage5-rollout-v1',
  AI_ROLLOUT_HMAC_KEY: 'ai-live-stage5-rollout-key-with-at-least-32-bytes',
  AI_AUDIT_HMAC_KEY: 'ai-live-stage5-audit-integrity-key-v1-20260721',
  AI_TOKENIZATION_HMAC_KEY: 'ai-live-stage5-tokenization-key-v1-20260721',
  AI_STEP_UP_HMAC_KEY: 'ai-live-stage5-step-up-proof-key-v1-20260721',
  AI_RUN_RESERVED_TOKENS: '20000',
  AI_REDIS_KEY_PREFIX: stage5RedisKeyPrefix,
}

export default defineConfig({
  testDir: './e2e',
  testMatch: ['ai-live-stage5.spec.ts', 'ai-live-stage5-security.spec.ts'],
  globalSetup: './e2e/ai-live-global-setup.ts',
  timeout: 15 * 60_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'line',
  outputDir: 'test-results/ai-live-stage5-run',
  use: {
    baseURL: visualBaseURL,
    actionTimeout: 30_000,
    trace: 'retain-on-failure',
    screenshot: 'off',
    video: 'off',
    launchOptions: browserExecutable ? { executablePath: browserExecutable } : undefined,
  },
  webServer: [
    {
      command: 'mvn -o -f ../backend/pom.xml spring-boot:run',
      url: `${visualBackendURL}/api/health`,
      env: backendEnv,
      reuseExistingServer: false,
      timeout: 240_000,
      stdout: 'pipe',
      stderr: 'pipe',
    },
    {
      command: `npm run dev -- --host 127.0.0.1 --port ${visualFrontendPort} --strictPort`,
      url: visualBaseURL,
      env: {
        VITE_AI_DEMO_ENABLED: 'false',
        VITE_AI_ENABLED: 'true',
        VITE_BACKEND_PROXY_TARGET: visualBackendURL,
        VITE_E2E_DISABLE_HMR: 'true',
      },
      reuseExistingServer: false,
      timeout: 120_000,
      stdout: 'ignore',
      stderr: 'pipe',
    },
  ],
  projects: [
    { name: 'ai-live-stage5-chromium', use: { ...devices['Desktop Chrome'] } },
  ],
})
