import { describe, expect, it, vi } from 'vitest'
import {
  isVisualLoginServiceNotReady,
  retryVisualLogin,
  type VisualLoginAttemptResult,
} from '../../e2e/live-visual-login-retry'

function fakeTiming() {
  let currentTime = 0
  const sleep = vi.fn(async (milliseconds: number) => {
    currentTime += milliseconds
  })
  return { now: () => currentTime, sleep }
}

const sanitize = (value: unknown) => String(value).replaceAll('super-secret', '[REDACTED]')

async function captureRejection(promise: Promise<void>) {
  let caught: unknown
  try {
    await promise
  } catch (error) {
    caught = error
  }
  expect(caught).toBeInstanceOf(Error)
  return caught as Error
}

describe('live visual bounded login retry', () => {
  it.each([
    'apiRequestContext.post: connect ECONNREFUSED 127.0.0.1:5177',
    'apiRequestContext.post: read ECONNRESET',
    'apiRequestContext.post: Timeout 3000ms exceeded.',
    'net::ERR_CONNECTION_REFUSED',
  ])('只把明确的登录传输未就绪错误列为可重试：%s', (message) => {
    expect(isVisualLoginServiceNotReady(new Error(message))).toBe(true)
  })

  it.each([
    'HTTP 500 Internal Server Error',
    'locator.click: Timeout 3000ms exceeded.',
    '用户名输入框不存在',
  ])('其他 HTTP 或页面错误不进入服务未就绪重试：%s', (message) => {
    expect(isVisualLoginServiceNotReady(new Error(message))).toBe(false)
  })

  it('只对初始化期 401、网关未就绪和明确传输错误短暂重试，并在成功后立即停止', async () => {
    const timing = fakeTiming()
    const outcomes: VisualLoginAttemptResult[] = [
      { status: 401, detail: 'bootstrap admin 尚未初始化' },
      { status: 502, detail: 'Vite proxy backend not ready' },
      { status: 503, detail: 'service starting' },
      { status: 504, detail: 'gateway timeout while starting' },
      { status: 'service-not-ready', detail: 'ECONNREFUSED' },
      { status: 200, detail: '' },
    ]
    const attempt = vi.fn(async () => outcomes.shift()!)

    await expect(retryVisualLogin(attempt, {
      timeoutMs: 700,
      intervalMs: 100,
      sanitize,
      ...timing,
    })).resolves.toBeUndefined()

    expect(attempt).toHaveBeenCalledTimes(6)
    expect(timing.sleep.mock.calls).toEqual([[100], [100], [100], [100], [100]])
  })

  it.each([403, 409, 429, 500])(
    'HTTP %s 保持 fail-fast，不被误判为初始化竞态',
    async (status) => {
      const timing = fakeTiming()
      const attempt = vi.fn(async (): Promise<VisualLoginAttemptResult> => ({
        status,
        detail: 'password=super-secret',
      }))

      const error = await captureRejection(retryVisualLogin(attempt, {
        timeoutMs: 500,
        intervalMs: 100,
        sanitize,
        ...timing,
      }))

      expect(error).toBeInstanceOf(Error)
      expect(error.message).toContain(`HTTP ${status}`)
      expect(error.message).toContain('[REDACTED]')
      expect(error.message).not.toContain('super-secret')
      expect(attempt).toHaveBeenCalledOnce()
      expect(timing.sleep).not.toHaveBeenCalled()
    },
  )

  it('总等待严格受 timeoutMs 限制，超时错误只保留脱敏后的最后状态', async () => {
    const timing = fakeTiming()
    const attempt = vi.fn(async (): Promise<VisualLoginAttemptResult> => ({
      status: 401,
      detail: 'password=super-secret',
    }))

    const error = await captureRejection(retryVisualLogin(attempt, {
      timeoutMs: 250,
      intervalMs: 100,
      sanitize,
      ...timing,
    }))

    expect(error).toBeInstanceOf(Error)
    expect(error.message).toContain('250ms')
    expect(error.message).toContain('[REDACTED]')
    expect(error.message).not.toContain('super-secret')
    expect(timing.sleep.mock.calls.flat().reduce((sum, value) => sum + value, 0)).toBe(250)
    expect(attempt).toHaveBeenCalledTimes(3)
  })

  it('意外的页面或选择器异常保持 fail-fast，并在抛出前脱敏', async () => {
    const timing = fakeTiming()
    const attempt = vi.fn(async (): Promise<VisualLoginAttemptResult> => {
      throw new Error('selector failed password=super-secret')
    })

    const error = await captureRejection(retryVisualLogin(attempt, {
      timeoutMs: 500,
      intervalMs: 100,
      sanitize,
      ...timing,
    }))

    expect(error).toBeInstanceOf(Error)
    expect(error.message).toContain('登录流程失败')
    expect(error.message).toContain('[REDACTED]')
    expect(error.message).not.toContain('super-secret')
    expect(attempt).toHaveBeenCalledOnce()
    expect(timing.sleep).not.toHaveBeenCalled()
  })
})
