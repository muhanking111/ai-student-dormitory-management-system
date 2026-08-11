import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiRequest, clearCsrfToken, setUnauthorizedHandler } from './client'

describe('apiRequest', () => {
  afterEach(() => {
    setUnauthorizedHandler(undefined)
    clearCsrfToken()
    vi.unstubAllGlobals()
  })

  it('携带 Cookie 并返回业务数据', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ code: 0, message: 'success', data: [{ id: 1 }] }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiRequest<Array<{ id: number }>>('/api/dormitories')).resolves.toEqual([{ id: 1 }])
    expect(fetchMock).toHaveBeenCalledWith('/api/dormitories', expect.objectContaining({ credentials: 'include' }))
  })

  it('将未登录响应转换为统一异常', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: async () => ({ code: 401, message: '未登录或登录已过期', data: null }),
    }))

    const request = apiRequest('/api/students')
    await expect(request).rejects.toBeInstanceOf(ApiError)
    await expect(request).rejects.toMatchObject({ status: 401, message: '未登录或登录已过期' })
    expect(onUnauthorized).toHaveBeenCalledOnce()
    expect(onUnauthorized.mock.calls[0]?.[0]).toMatchObject({ status: 401, code: 401 })
  })

  it('正确处理 204 空响应', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ code: 0, message: 'success', data: { token: 'csrf-test' } }) })
      .mockResolvedValueOnce({ ok: true, status: 204 })
    vi.stubGlobal('fetch', fetchMock)

    await expect(apiRequest<void>('/api/students/1', { method: 'DELETE' })).resolves.toBeUndefined()
    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/security/csrf', expect.objectContaining({ credentials: 'include' }))
    expect(fetchMock.mock.calls[1]?.[1]).toEqual(expect.objectContaining({
      method: 'DELETE',
      headers: expect.any(Headers),
    }))
    expect((fetchMock.mock.calls[1]?.[1] as RequestInit).headers).toSatisfy((headers: Headers) => headers.get('X-CSRF-Token') === 'csrf-test')
  })

  it('同一会话复用内存 CSRF token 且不写入持久存储', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce({ ok: true, status: 200, json: async () => ({ code: 0, message: 'success', data: { token: 'csrf-memory-only' } }) })
      .mockResolvedValue({ ok: true, status: 200, json: async () => ({ code: 0, message: 'success', data: null }) })
    vi.stubGlobal('fetch', fetchMock)

    await apiRequest('/api/notices', { method: 'POST', body: '{}' })
    await apiRequest('/api/notices/1', { method: 'PATCH', body: '{}' })

    expect(fetchMock.mock.calls.filter(([url]) => url === '/api/security/csrf')).toHaveLength(1)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })

  it('将非 JSON 响应转换为格式错误', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 502,
      json: async () => { throw new Error('invalid json') },
    }))

    await expect(apiRequest('/api/broken')).rejects.toMatchObject({
      status: 502,
      message: '服务器响应格式错误',
    })
  })

  it('将业务错误码转换为统一异常', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ code: 409, message: '业务冲突', data: null }),
    }))

    await expect(apiRequest('/api/conflict')).rejects.toMatchObject({
      status: 200,
      code: 409,
      message: '业务冲突',
    })
  })
})
