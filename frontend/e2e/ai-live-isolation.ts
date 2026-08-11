const LOOPBACK_HOSTS = new Set(['127.0.0.1', 'localhost', '::1'])
const DEDICATED_DATABASE_NAME = /^[a-z0-9][a-z0-9_$-]*_e2e$/i
const REDIS_KEY_PREFIX = /^[a-z0-9][a-z0-9:_-]{11,127}$/i

type AiLiveDatabaseEnvironment = Record<string, string | undefined>

interface AiLiveIsolationOptions {
  requireOutputBoundRedisPrefix?: boolean
}

export interface AiLiveMysqlConnection {
  hostname: string
  port: string
  database: string
  username: string
  password: string
}

export interface AiLiveRedisIsolation {
  hostname: string
  port: string
  database: number
  keyPrefix: string
  dashboardCacheKey: string
}

export interface AiLiveIsolation {
  connection: AiLiveMysqlConnection
  redis: AiLiveRedisIsolation
  evidence: {
    outputName: string
    mysql: Pick<AiLiveMysqlConnection, 'hostname' | 'port' | 'database'>
    redis: AiLiveRedisIsolation
  }
  backendEnv: {
    SERVER_ADDRESS: '127.0.0.1'
    DASHBOARD_CACHE_KEY: string
  }
}

function normalizedRunId(value: string) {
  return value.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
}

function candidateOutputSuffix(outputName: string) {
  const parts = normalizedRunId(outputName).split('-').filter(Boolean)
  let dateIndex = -1
  for (let index = parts.length - 1; index >= 0; index -= 1) {
    if (/^\d{8}$/.test(parts[index] ?? '')) {
      dateIndex = index
      break
    }
  }
  if (dateIndex < 0 || dateIndex === parts.length - 1) return ''
  return parts.slice(dateIndex).join('-')
}

/**
 * AI live 会写入持久控制面与审计数据，因此数据库名本身必须声明测试边界。
 * 这里刻意不提供布尔豁免：loopback 地址可能只是通往正式库的隧道。
 */
export function requireAiLiveIsolation(
  environment: AiLiveDatabaseEnvironment,
  options: AiLiveIsolationOptions = {},
): AiLiveIsolation {
  const rawUrl = environment.DB_URL?.trim() ?? ''
  const match = rawUrl.match(
    /^jdbc:mysql:\/\/(\[[^\]]+\]|[^/:?]+)(?::(\d+))?\/([^?;]+)(?:[?;].*)?$/i,
  )
  if (!match) {
    throw new Error('AI live E2E 需要标准 jdbc:mysql://host:port/database DB_URL')
  }

  const [, rawHostname, port = '3306', database] = match
  const hostname = rawHostname.startsWith('[') && rawHostname.endsWith(']')
    ? rawHostname.slice(1, -1)
    : rawHostname

  if (!DEDICATED_DATABASE_NAME.test(database)) {
    throw new Error(
      'AI live E2E 数据库名必须以 _e2e 结尾且只使用安全名称字符；'
      + 'DB host 为 loopback 不能证明数据库隔离',
    )
  }
  if (!LOOPBACK_HOSTS.has(hostname.toLowerCase())) {
    throw new Error('AI live E2E 拒绝写入非本机 MySQL；请使用 loopback 上的专用 *_e2e 测试数据库')
  }

  const username = environment.DB_USERNAME ?? ''
  const password = environment.DB_PASSWORD ?? ''
  if (!username.trim() || !password.trim()) {
    throw new Error('AI live E2E 缺少 MySQL 测试凭据')
  }

  const redisHostname = environment.REDIS_HOST?.trim().toLowerCase() ?? ''
  if (!LOOPBACK_HOSTS.has(redisHostname)) {
    throw new Error('AI live E2E 拒绝写入非本机 Redis；请使用 loopback 上的候选专用逻辑 DB 与 key prefix')
  }
  const redisPortNumber = Number(environment.REDIS_PORT ?? '')
  if (!Number.isInteger(redisPortNumber) || redisPortNumber < 1 || redisPortNumber > 65_535) {
    throw new Error('AI live E2E 需要 1-65535 的合法 Redis 端口')
  }
  const redisDatabase = Number(environment.REDIS_DATABASE ?? '')
  if (!Number.isInteger(redisDatabase) || redisDatabase < 1 || redisDatabase > 15) {
    throw new Error('AI live E2E 的 Redis DB 必须使用 1-15，不能使用默认 DB 0')
  }
  const outputName = environment.VISUAL_OUTPUT_NAME?.trim() ?? ''
  const outputSuffix = candidateOutputSuffix(outputName)
  const keyPrefix = environment.AI_REDIS_KEY_PREFIX?.trim() ?? ''
  if (!REDIS_KEY_PREFIX.test(keyPrefix)) {
    throw new Error('AI live E2E 需要 12-128 位且只含字母、数字、冒号、下划线或连字符的 Redis key prefix')
  }
  if (options.requireOutputBoundRedisPrefix
      && (!outputSuffix || !normalizedRunId(keyPrefix).includes(outputSuffix))) {
    throw new Error('AI live E2E 的 Redis key prefix 必须绑定本次唯一输出名中的日期与候选后缀')
  }
  const redis: AiLiveRedisIsolation = {
    hostname: redisHostname,
    port: String(redisPortNumber),
    database: redisDatabase,
    keyPrefix,
    dashboardCacheKey: `${keyPrefix}:dashboard:statistics`,
  }

  return {
    connection: { hostname, port, database, username, password },
    redis,
    evidence: {
      outputName,
      mysql: { hostname, port, database },
      redis,
    },
    backendEnv: {
      SERVER_ADDRESS: '127.0.0.1',
      DASHBOARD_CACHE_KEY: redis.dashboardCacheKey,
    },
  }
}
