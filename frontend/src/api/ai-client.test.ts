import { afterEach, describe, expect, it, vi } from 'vitest'
import { AiDisabledError } from './ai'
import { getAiClient, getAiClientMode, isAiSurfaceEnabled, setAiClient } from './ai-client'
import { HttpAiClient } from './ai-http'

describe('AI client mode', () => {
  afterEach(() => {
    setAiClient(undefined)
    vi.unstubAllEnvs()
  })

  it('真实开关优先，演示开关显式启用', () => {
    vi.stubEnv('VITE_AI_ENABLED', 'true')
    vi.stubEnv('VITE_AI_DEMO_ENABLED', 'true')
    expect(getAiClientMode()).toBe('http')
    expect(getAiClient()).toBeInstanceOf(HttpAiClient)
    vi.stubEnv('VITE_AI_ENABLED', 'false')
    expect(getAiClientMode()).toBe('demo')
    expect(isAiSurfaceEnabled()).toBe(true)
  })

  it('注入 client 可用于测试且不依赖环境', () => {
    const client = { queryDashboard: async () => ({}) as never }
    setAiClient(client)
    expect(getAiClient()).toBe(client)
    expect(isAiSurfaceEnabled()).toBe(true)
  })

  it('禁用 client 拒绝调用', async () => {
    vi.stubEnv('DEV', false)
    vi.stubEnv('MODE', 'production')
    vi.stubEnv('VITE_AI_ENABLED', 'false')
    vi.stubEnv('VITE_AI_DEMO_ENABLED', 'false')
    expect(getAiClientMode()).toBe('disabled')
    const client = getAiClient()
    await expect(client.queryDashboard?.({ question: 'x' })).rejects.toBeInstanceOf(AiDisabledError)
  })

  it('生产环境忽略误设的 demo 开关，避免打包或启用样例 client', () => {
    vi.stubEnv('DEV', false)
    vi.stubEnv('MODE', 'production')
    vi.stubEnv('VITE_AI_ENABLED', 'false')
    vi.stubEnv('VITE_AI_DEMO_ENABLED', 'true')

    expect(getAiClientMode()).toBe('disabled')
    expect(isAiSurfaceEnabled()).toBe(false)
  })
})
