import type { AiClient } from './ai'
import { AiDisabledError } from './ai'
import { DemoAiClient } from './ai-demo'
import { HttpAiClient } from './ai-http'

export type AiClientMode = 'disabled' | 'demo' | 'http'

let injectedClient: AiClient | undefined
const demoClientSupported = import.meta.env.DEV || import.meta.env.MODE === 'test'
const demoClient = demoClientSupported ? new DemoAiClient() : undefined
const httpClient = new HttpAiClient()

export function getAiClientMode(): AiClientMode {
  if (import.meta.env.VITE_AI_ENABLED === 'true') return 'http'
  if ((import.meta.env.DEV || import.meta.env.MODE === 'test')
    && (import.meta.env.VITE_AI_DEMO_ENABLED === 'true' || import.meta.env.MODE === 'test')) return 'demo'
  return 'disabled'
}

export function isAiSurfaceEnabled() {
  return injectedClient !== undefined || getAiClientMode() !== 'disabled'
}

export function getAiClient(): AiClient {
  if (injectedClient) return injectedClient
  const mode = getAiClientMode()
  if (mode === 'demo' && demoClient) return demoClient
  if (mode === 'http') return httpClient
  return new Proxy({}, {
    get() {
      return () => Promise.reject(new AiDisabledError())
    },
  }) as AiClient
}

export function setAiClient(client: AiClient | undefined) {
  injectedClient = client
}
