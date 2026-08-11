export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
}

export class ApiError extends Error {
  readonly status: number
  readonly code: number

  constructor(status: number, code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export type UnauthorizedHandler = (error: ApiError) => void

let unauthorizedHandler: UnauthorizedHandler | undefined
let csrfToken: string | undefined
let csrfRequest: Promise<string> | undefined

export function setUnauthorizedHandler(handler: UnauthorizedHandler | undefined) {
  unauthorizedHandler = handler
}

export function clearCsrfToken() {
  csrfToken = undefined
  csrfRequest = undefined
}

export function reportUnauthorizedResponse(message = '未登录或登录已过期') {
  clearCsrfToken()
  unauthorizedHandler?.(new ApiError(401, 401, message))
}

function throwApiError(error: ApiError): never {
  if (error.status === 401) {
    clearCsrfToken()
    unauthorizedHandler?.(error)
  }
  throw error
}

function isUnsafeMethod(method?: string) {
  return ['POST', 'PUT', 'PATCH', 'DELETE'].includes((method ?? 'GET').toUpperCase())
}

function needsCsrf(path: string, init: RequestInit, headers: Headers) {
  return isUnsafeMethod(init.method)
    && path !== '/api/auth/login'
    && path !== '/api/security/csrf'
    && !headers.has('X-CSRF-Token')
}

async function requestCsrfToken() {
  if (csrfToken) return csrfToken
  if (csrfRequest) return csrfRequest
  csrfRequest = (async () => {
    const response = await fetch('/api/security/csrf', {
      method: 'GET',
      credentials: 'include',
      cache: 'no-store',
      headers: { Accept: 'application/json' },
    })
    if (response.status === 401) reportUnauthorizedResponse()
    if (!response.ok) throw new ApiError(response.status, response.status, '无法获取安全校验令牌')
    const payload = await response.json() as ApiEnvelope<{ token?: string } | string> | { token?: string }
    const data = 'data' in payload ? payload.data : payload
    const token = typeof data === 'string' ? data : data?.token
    if (!token) throw new ApiError(503, 503, '安全校验令牌不可用')
    csrfToken = token
    return token
  })()
  try { return await csrfRequest }
  finally { csrfRequest = undefined }
}

export async function apiRequest<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  if (init.body && !headers.has('Content-Type')) {
    headers.set('Content-Type', 'application/json')
  }
  if (needsCsrf(path, init, headers)) headers.set('X-CSRF-Token', await requestCsrfToken())

  const response = await fetch(path, {
    ...init,
    headers,
    credentials: 'include',
  })

  if (response.status === 204) {
    return undefined as T
  }

  let payload: ApiEnvelope<T>
  try {
    payload = (await response.json()) as ApiEnvelope<T>
  } catch {
    return throwApiError(new ApiError(response.status, response.status, '服务器响应格式错误'))
  }

  if (!response.ok || payload.code !== 0) {
    return throwApiError(new ApiError(response.status, payload.code, payload.message || '请求失败'))
  }
  return payload.data
}
