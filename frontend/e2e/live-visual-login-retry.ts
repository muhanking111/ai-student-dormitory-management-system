import { setTimeout as delay } from 'node:timers/promises'

export type VisualLoginAttemptStatus = number | 'service-not-ready'

export interface VisualLoginAttemptResult {
  status: VisualLoginAttemptStatus
  detail: unknown
}

export interface VisualLoginAttemptContext {
  attempt: number
  remainingMs: number
}

interface VisualLoginRetryOptions {
  timeoutMs: number
  intervalMs: number
  sanitize: (value: unknown) => string
  now?: () => number
  sleep?: (milliseconds: number) => Promise<void>
}

function retryable(status: VisualLoginAttemptStatus) {
  return status === 401 || status === 502 || status === 503 || status === 504 || status === 'service-not-ready'
}

export function isVisualLoginServiceNotReady(error: unknown) {
  const diagnostic = error instanceof Error ? `${error.name}: ${error.message}` : String(error)
  if (/\b(?:ECONNREFUSED|ECONNRESET|ETIMEDOUT)\b|net::ERR_CONNECTION_(?:REFUSED|RESET|TIMED_OUT)/i.test(diagnostic)) {
    return true
  }
  return /apiRequestContext\.(?:post|fetch):[\s\S]*(?:Timeout \d+ms exceeded|timed out)/i.test(diagnostic)
}

function failureLabel(result: VisualLoginAttemptResult, safeDetail: string) {
  const state = result.status === 'service-not-ready' ? '服务未就绪' : `HTTP ${result.status}`
  return safeDetail ? `${state}: ${safeDetail}` : state
}

export async function retryVisualLogin(
  attemptLogin: (context: VisualLoginAttemptContext) => Promise<VisualLoginAttemptResult>,
  options: VisualLoginRetryOptions,
): Promise<void> {
  if (!Number.isFinite(options.timeoutMs) || options.timeoutMs <= 0
    || !Number.isFinite(options.intervalMs) || options.intervalMs <= 0) {
    throw new Error('视觉登录重试参数不合法')
  }

  const now = options.now ?? Date.now
  const sleep = options.sleep ?? ((milliseconds: number) => delay(milliseconds))
  const startedAt = now()
  const deadline = startedAt + options.timeoutMs
  let attemptCount = 0
  let lastFailure = '尚未收到登录响应'

  while (now() < deadline || attemptCount === 0) {
    let result: VisualLoginAttemptResult
    try {
      result = await attemptLogin({
        attempt: attemptCount + 1,
        remainingMs: Math.max(0, deadline - now()),
      })
    } catch (error) {
      throw new Error(`真实服务登录流程失败（${options.sanitize(error)}）`, { cause: error })
    }
    attemptCount += 1

    if (result.status === 200) return

    const safeDetail = options.sanitize(result.detail)
    const safeFailure = failureLabel(result, safeDetail)
    if (!retryable(result.status)) {
      throw new Error(`真实服务登录失败（${safeFailure}）`)
    }
    lastFailure = safeFailure

    const remainingMs = deadline - now()
    if (remainingMs <= 0) break
    await sleep(Math.min(options.intervalMs, remainingMs))
  }

  throw new Error(`真实服务登录等待初始化超时（${options.timeoutMs}ms，最后状态 ${lastFailure}）`)
}
