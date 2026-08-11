import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendDirectory = fileURLToPath(new URL('../', import.meta.url))
const repositoryDirectory = resolve(frontendDirectory, '..')
const rootEnvPath = resolve(repositoryDirectory, '.env')

function parseEnvFile(path: string) {
  if (!existsSync(path)) return {} as Record<string, string>

  return readFileSync(path, 'utf8').split(/\r?\n/).reduce<Record<string, string>>((values, rawLine) => {
    const line = rawLine.trim().replace(/^export\s+/, '')
    if (!line || line.startsWith('#')) return values
    const separator = line.indexOf('=')
    if (separator <= 0) return values

    const key = line.slice(0, separator).trim()
    let value = line.slice(separator + 1).trim()
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
      value = value.slice(1, -1)
    }
    values[key] = value
    return values
  }, {})
}

function firstNonEmpty(...values: Array<string | undefined>) {
  return values.find((value) => value !== undefined && value.trim() !== '')?.trim() ?? ''
}

const rootEnv = parseEnvFile(rootEnvPath)

function visualValue(visualName: string, standardName: string, fallback = '') {
  return firstNonEmpty(
    process.env[visualName],
    process.env[standardName],
    rootEnv[standardName],
    fallback,
  )
}

export const visualBaseURL = firstNonEmpty(
  process.env.VISUAL_BASE_URL,
  'http://127.0.0.1:5174',
)
export const visualBackendURL = firstNonEmpty(
  process.env.VISUAL_BACKEND_URL,
  'http://127.0.0.1:8080',
)
export const visualFrontendPort = new URL(visualBaseURL).port || '80'
export const visualBackendPort = new URL(visualBackendURL).port || '80'
const requestedVisualOutputName = firstNonEmpty(process.env.VISUAL_OUTPUT_NAME, 'visual')
const sanitizedVisualOutputName = requestedVisualOutputName.replace(/[^A-Za-z0-9._-]/g, '_')
export const visualOutputName = sanitizedVisualOutputName === '.' || sanitizedVisualOutputName === '..'
  ? 'visual'
  : sanitizedVisualOutputName || 'visual'
export const visualDirectory = resolve(frontendDirectory, 'test-results', visualOutputName)
export const visualManifestPath = resolve(visualDirectory, 'manifest.json')

export function requireVisualReferenceInstant() {
  const value = process.env.VISUAL_REFERENCE_INSTANT?.trim()
  if (!value) throw new Error('正式视觉套件必须显式设置 VISUAL_REFERENCE_INSTANT')

  const instant = new Date(value)
  if (Number.isNaN(instant.getTime())) {
    throw new Error('VISUAL_REFERENCE_INSTANT 必须是合法 ISO-8601 时间')
  }
  return instant
}

export const visualCredentials = {
  username: firstNonEmpty(
    process.env.VISUAL_USERNAME,
    process.env.BOOTSTRAP_ADMIN_USERNAME,
    rootEnv.BOOTSTRAP_ADMIN_USERNAME,
  ),
  password: firstNonEmpty(
    process.env.VISUAL_PASSWORD,
    process.env.BOOTSTRAP_ADMIN_PASSWORD,
    rootEnv.BOOTSTRAP_ADMIN_PASSWORD,
  ),
}

export function requireVisualCredentials() {
  if (!visualCredentials.username || !visualCredentials.password) {
    throw new Error(
      '真实视觉套件需要根 .env 中的 BOOTSTRAP_ADMIN_USERNAME/BOOTSTRAP_ADMIN_PASSWORD，'
      + '或显式设置 VISUAL_USERNAME/VISUAL_PASSWORD',
    )
  }
  return visualCredentials
}

const backendVariableMappings: Array<[string, string]> = [
  ['DB_URL', 'VISUAL_DB_URL'],
  ['DB_USERNAME', 'VISUAL_DB_USERNAME'],
  ['DB_PASSWORD', 'VISUAL_DB_PASSWORD'],
  ['REDIS_HOST', 'VISUAL_REDIS_HOST'],
  ['REDIS_PORT', 'VISUAL_REDIS_PORT'],
  ['REDIS_PASSWORD', 'VISUAL_REDIS_PASSWORD'],
  ['REDIS_DATABASE', 'VISUAL_REDIS_DATABASE'],
  ['DEMO_DATA_ENABLED', 'VISUAL_DEMO_DATA_ENABLED'],
]

export const visualBackendEnv = { ...rootEnv }
for (const [standardName, visualName] of backendVariableMappings) {
  const value = visualValue(visualName, standardName)
  if (value) visualBackendEnv[standardName] = value
}

const redisDatabase = Number(visualBackendEnv.REDIS_DATABASE ?? '0')
if (!Number.isInteger(redisDatabase) || redisDatabase < 0 || redisDatabase > 15) {
  throw new Error('真实视觉/AI live E2E 的 Redis DB 必须是 0-15 的合法编号')
}
visualBackendEnv.AI_REDIS_KEY_PREFIX = firstNonEmpty(
  process.env.VISUAL_AI_REDIS_KEY_PREFIX,
  process.env.AI_REDIS_KEY_PREFIX,
  rootEnv.AI_REDIS_KEY_PREFIX,
  `visual:e2e:${visualBackendPort}`,
)
visualBackendEnv.VISUAL_OUTPUT_NAME = visualOutputName
if (visualCredentials.username) visualBackendEnv.BOOTSTRAP_ADMIN_USERNAME = visualCredentials.username
if (visualCredentials.password) visualBackendEnv.BOOTSTRAP_ADMIN_PASSWORD = visualCredentials.password
visualBackendEnv.SERVER_PORT = visualBackendPort
visualBackendEnv.CORS_ALLOWED_ORIGINS = firstNonEmpty(
  process.env.VISUAL_CORS_ALLOWED_ORIGINS,
  process.env.CORS_ALLOWED_ORIGINS,
  rootEnv.CORS_ALLOWED_ORIGINS,
  `${visualBaseURL},http://localhost:${visualFrontendPort}`,
)

const sensitiveValues = Array.from(new Set([
  visualCredentials.username,
  visualCredentials.password,
  ...Object.entries(visualBackendEnv)
    .filter(([name]) => /(PASSWORD|SECRET|TOKEN|KEY)$/i.test(name))
    .map(([, value]) => value),
].filter((value) => value.length >= 3))).sort((left, right) => right.length - left.length)

export function sanitizeDiagnostic(input: unknown) {
  let value = input instanceof Error ? `${input.name}: ${input.message}` : String(input)
  for (const sensitiveValue of sensitiveValues) value = value.replaceAll(sensitiveValue, '[REDACTED]')
  return value
    .replace(/((?:password|token|authorization|cookie)\s*[:=]\s*)[^\s,;]+/gi, '$1[REDACTED]')
    .slice(0, 1_000)
}
