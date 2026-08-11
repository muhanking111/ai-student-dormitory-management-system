import { defineConfig, devices } from '@playwright/test'
import {
  visualBackendEnv,
  visualBackendURL,
  visualBaseURL,
  visualFrontendPort,
} from './e2e/live-visual-settings'
import { requireAiLiveIsolation } from './e2e/ai-live-isolation'

const browserExecutable = process.env.VISUAL_BROWSER_EXECUTABLE?.trim() || undefined
const aiLiveIsolation = requireAiLiveIsolation(visualBackendEnv)

// application-dev.yml contains a fixed local-origin fallback.  Keep the
// isolated test origin explicit at the JVM property layer so it cannot be
// shadowed by profile configuration or the repository .env file.
const aiLiveCorsAllowedOrigins = Array.from(new Set([
  ...(visualBackendEnv.CORS_ALLOWED_ORIGINS ?? '').split(',').map((origin) => origin.trim()).filter(Boolean),
  visualBaseURL,
  `http://localhost:${visualFrontendPort}`,
])).join(',')
const aiLiveJavaToolOptions = [
  visualBackendEnv.JAVA_TOOL_OPTIONS,
  `-Ddormitory.cors.allowed-origins=${aiLiveCorsAllowedOrigins}`,
].filter((value): value is string => Boolean(value && value.trim())).join(' ')

const aiLiveBackendEnv = {
  ...visualBackendEnv,
  ...aiLiveIsolation.backendEnv,
  CORS_ALLOWED_ORIGINS: aiLiveCorsAllowedOrigins,
  JAVA_TOOL_OPTIONS: aiLiveJavaToolOptions,
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
  AI_PROVIDER_ACTIVE: 'fake',
  AI_STREAMING_ENABLED: 'true',
  AI_WRITE_EXECUTION_ENABLED: 'false',
  AI_ROLLOUT_ENABLED: 'true',
  AI_ROLLOUT_BASIS_POINTS: '10000',
  AI_ROLLOUT_POLICY_VERSION: 'ai-live-e2e-rollout-v1',
  // 专用 *_e2e 库保留跨运行审计链；固定测试 key 用于验证旧链，不能随机化或靠清表规避。
  AI_ROLLOUT_HMAC_KEY: 'ai-live-e2e-rollout-key-with-at-least-32-bytes',
  AI_AUDIT_HMAC_KEY: 'ai-live-e2e-audit-integrity-key-v1-20260712',
  AI_TOKENIZATION_HMAC_KEY: 'ai-live-e2e-tokenization-key-v1-20260712',
  AI_STEP_UP_HMAC_KEY: 'ai-live-e2e-step-up-proof-key-v1-20260712',
  AI_RUN_RESERVED_TOKENS: '20000',
}

export default defineConfig({
  testDir: './e2e',
  testMatch: 'ai-live-contract.spec.ts',
  globalSetup: './e2e/ai-live-global-setup.ts',
  timeout: 15 * 60_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'line',
  metadata: {
    aiLiveRequestedRuntimeContract: {
      clientMode: 'HttpAiClient',
      provider: aiLiveBackendEnv.AI_PROVIDER_ACTIVE,
      aiWriteExecutionEnabled: aiLiveBackendEnv.AI_WRITE_EXECUTION_ENABLED === 'true',
      database: aiLiveIsolation.connection.database,
      databaseHost: aiLiveIsolation.connection.hostname,
      redisDatabase: aiLiveBackendEnv.REDIS_DATABASE,
      redisKeyPrefix: aiLiveBackendEnv.AI_REDIS_KEY_PREFIX,
      frontendOrigin: visualBaseURL,
      backendOrigin: visualBackendURL,
    },
  },
  outputDir: 'test-results/ai-live-run',
  use: {
    baseURL: visualBaseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'off',
    launchOptions: browserExecutable ? { executablePath: browserExecutable } : undefined,
  },
  webServer: [
    {
      command: 'mvn -f ../backend/pom.xml spring-boot:run',
      url: `${visualBackendURL}/api/health`,
      env: aiLiveBackendEnv,
      reuseExistingServer: false,
      timeout: 240_000,
      stdout: 'ignore',
      stderr: 'ignore',
    },
    {
      command: `npm run dev -- --host 127.0.0.1 --port ${visualFrontendPort} --strictPort`,
      url: visualBaseURL,
      env: {
        VITE_AI_ENABLED: 'true',
        VITE_AI_DEMO_ENABLED: 'false',
        VITE_BACKEND_PROXY_TARGET: visualBackendURL,
      },
      reuseExistingServer: false,
      timeout: 120_000,
      stdout: 'ignore',
      stderr: 'ignore',
    },
  ],
  projects: [
    { name: 'ai-live-chromium', use: { ...devices['Desktop Chrome'] } },
  ],
})
