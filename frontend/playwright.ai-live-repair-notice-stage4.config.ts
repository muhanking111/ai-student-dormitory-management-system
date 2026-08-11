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

const loopbackHosts = new Set(['127.0.0.1', 'localhost', '::1'])

function normalizeHostname(value: string) {
  return value.startsWith('[') && value.endsWith(']')
    ? value.slice(1, -1).toLowerCase()
    : value.toLowerCase()
}

function requireLoopbackOrigin(value: string, label: string) {
  let url: URL
  try {
    url = new URL(value)
  } catch {
    throw new Error(`${label} 必须是合法 URL`)
  }
  const hostname = normalizeHostname(url.hostname)
  if (url.protocol !== 'http:' || !loopbackHosts.has(hostname)
    || url.username || url.password || url.pathname !== '/' || url.search || url.hash) {
    throw new Error(`${label} 必须是不含凭据和路径的本机 HTTP origin`)
  }
  if (!url.port) throw new Error(`${label} 必须显式声明专用端口`)
  return { hostname, port: url.port }
}

function requireLoopbackHost(value: string | undefined, label: string) {
  const hostname = normalizeHostname(value?.trim() ?? '')
  if (!loopbackHosts.has(hostname)) {
    throw new Error(`${label} 必须指向本机 loopback 地址`)
  }
  return hostname
}

function requirePort(value: string | undefined, label: string) {
  const port = value?.trim() ?? ''
  const number = Number(port)
  if (!/^\d+$/.test(port) || !Number.isInteger(number) || number < 1 || number > 65_535) {
    throw new Error(`${label} 必须是 1-65535 的合法端口`)
  }
  return port
}

const browserExecutable = process.env.VISUAL_BROWSER_EXECUTABLE?.trim() || undefined
const frontendEndpoint = requireLoopbackOrigin(visualBaseURL, 'VISUAL_BASE_URL')
const backendEndpoint = requireLoopbackOrigin(visualBackendURL, 'VISUAL_BACKEND_URL')
if (frontendEndpoint.hostname !== backendEndpoint.hostname) {
  throw new Error('Stage 4 AI live 的前后端必须使用同一本机 loopback 主机')
}
if (frontendEndpoint.port === backendEndpoint.port) {
  throw new Error('Stage 4 AI live 的前后端必须使用不同专用端口')
}

const corsAllowedOrigins = Array.from(new Set([
  ...(visualBackendEnv.CORS_ALLOWED_ORIGINS ?? '')
    .split(',')
    .map((origin) => origin.trim())
    .filter(Boolean),
  visualBaseURL,
  `http://localhost:${visualFrontendPort}`,
])).join(',')

const redisHost = requireLoopbackHost(visualBackendEnv.REDIS_HOST, 'REDIS_HOST')
const redisPort = requirePort(visualBackendEnv.REDIS_PORT, 'REDIS_PORT')
const redisDatabase = process.env.AI_LIVE_REPAIR_NOTICE_STAGE4_REDIS_DATABASE?.trim()
  || process.env.VISUAL_REDIS_DATABASE?.trim()
  || '11'
if (redisDatabase !== '11') {
  throw new Error('Stage 4 维修/公告 AI live 必须使用专用 Redis DB 11')
}
const redisKeyPrefix = process.env.AI_LIVE_REPAIR_NOTICE_STAGE4_REDIS_KEY_PREFIX?.trim()
  || `ai-live:repair-notice:stage4:${visualOutputName.replaceAll('.', '-')}`
const repairNoticeIsolation = requireAiLiveIsolation({
  ...visualBackendEnv,
  REDIS_HOST: redisHost,
  REDIS_PORT: redisPort,
  REDIS_DATABASE: redisDatabase,
  AI_REDIS_KEY_PREFIX: redisKeyPrefix,
}, { requireOutputBoundRedisPrefix: true })
const javaToolOptions = [
  process.env.AI_LIVE_REPAIR_NOTICE_STAGE4_JAVA_TOOL_OPTIONS,
  visualBackendEnv.JAVA_TOOL_OPTIONS,
  `-Ddormitory.cors.allowed-origins=${corsAllowedOrigins}`,
].filter((value): value is string => Boolean(value && value.trim())).join(' ')

const backendEnv = {
  ...visualBackendEnv,
  ...repairNoticeIsolation.backendEnv,
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
  // 本套件只能消费专用库里的既有合法输入，不能靠启动时 demo seed 造业务数据。
  DEMO_DATA_ENABLED: 'false',
  AI_ENABLED: 'true',
  AI_CAPABILITY_ASSISTANT_ENABLED: 'true',
  AI_CAPABILITY_KNOWLEDGE_ENABLED: 'true',
  AI_CAPABILITY_DASHBOARD_ENABLED: 'true',
  AI_CAPABILITY_REPAIR_ENABLED: 'true',
  AI_CAPABILITY_NOTICE_ENABLED: 'true',
  AI_CAPABILITY_RISK_ENABLED: 'false',
  AI_CAPABILITY_EVALUATION_ENABLED: 'false',
  AI_PROVIDER_ACTIVE: 'fake',
  AI_STREAMING_ENABLED: 'true',
  AI_WRITE_EXECUTION_ENABLED: 'false',
  AI_ROLLOUT_ENABLED: 'true',
  AI_ROLLOUT_BASIS_POINTS: '10000',
  // 该既有 Stage 3 专用库的持久审计链由以下稳定测试 key 创建；更换 key 会被启动门拒绝。
  AI_ROLLOUT_POLICY_VERSION: 'ai-live-e2e-rollout-v1',
  AI_ROLLOUT_HMAC_KEY: 'ai-live-e2e-rollout-key-with-at-least-32-bytes',
  AI_AUDIT_HMAC_KEY: 'ai-live-e2e-audit-integrity-key-v1-20260712',
  AI_TOKENIZATION_HMAC_KEY: 'ai-live-e2e-tokenization-key-v1-20260712',
  AI_STEP_UP_HMAC_KEY: 'ai-live-e2e-step-up-proof-key-v1-20260712',
  AI_RUN_RESERVED_TOKENS: '20000',
  REDIS_HOST: redisHost,
  REDIS_PORT: redisPort,
  REDIS_DATABASE: redisDatabase,
  AI_REDIS_KEY_PREFIX: redisKeyPrefix,
  AI_OUTBOX_INITIAL_DELAY: 'PT1S',
  AI_OUTBOX_INTERVAL: 'PT1S',
}

export default defineConfig({
  testDir: './e2e',
  testMatch: 'ai-live-repair-notice-stage4.spec.ts',
  globalSetup: './e2e/ai-live-global-setup.ts',
  timeout: 15 * 60_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: 'line',
  metadata: {
    aiLiveRequestedRuntimeContract: {
      suite: 'repair-notice-stage4',
      clientMode: 'HttpAiClient',
      provider: backendEnv.AI_PROVIDER_ACTIVE,
      streamingEnabled: backendEnv.AI_STREAMING_ENABLED === 'true',
      aiWriteExecutionEnabled: backendEnv.AI_WRITE_EXECUTION_ENABLED === 'true',
      demoDataEnabled: backendEnv.DEMO_DATA_ENABLED === 'true',
      database: repairNoticeIsolation.connection.database,
      databaseHost: repairNoticeIsolation.connection.hostname,
      databasePort: repairNoticeIsolation.connection.port,
      redisHost,
      redisPort,
      redisDatabase,
      redisKeyPrefix: backendEnv.AI_REDIS_KEY_PREFIX,
      frontendOrigin: visualBaseURL,
      frontendPort: visualFrontendPort,
      backendOrigin: visualBackendURL,
      backendPort: visualBackendPort,
    },
  },
  outputDir: 'test-results/stage4-repair-notice-j-live-run',
  use: {
    baseURL: visualBaseURL,
    actionTimeout: 30_000,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
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
        VITE_AI_ENABLED: 'true',
        VITE_AI_DEMO_ENABLED: 'false',
        VITE_BACKEND_PROXY_TARGET: visualBackendURL,
      },
      reuseExistingServer: false,
      timeout: 120_000,
      stdout: 'ignore',
      stderr: 'pipe',
    },
  ],
  projects: [
    { name: 'ai-live-repair-notice-stage4-chromium', use: { ...devices['Desktop Chrome'] } },
  ],
})
