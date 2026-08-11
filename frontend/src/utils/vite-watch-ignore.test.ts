import { describe, expect, it } from 'vitest'
import { ignoreViteRuntimeWatchFile } from './vite-watch-ignore'

describe('Vite runtime watcher isolation', () => {
  it.each([
    'D:/workspace/frontend/src/utils/example.test.ts',
    'D:/workspace/frontend/src/views/example.spec.tsx',
    'D:/workspace/frontend/e2e/live-visual.spec.ts',
    'D:/workspace/frontend/test-results/run/trace.zip',
    'D:/workspace/frontend/coverage/coverage-final.json',
    'D:\\workspace\\frontend\\src\\utils\\example.test.ts',
  ])('ignores non-runtime test and evidence changes: %s', (path) => {
    expect(ignoreViteRuntimeWatchFile(path)).toBe(true)
  })

  it.each([
    'D:/workspace/frontend/src/main.ts',
    'D:/workspace/frontend/src/views/AiKnowledgeView.vue',
    'D:\\workspace\\frontend\\src\\style.css',
  ])('keeps runtime source changes observable: %s', (path) => {
    expect(ignoreViteRuntimeWatchFile(path)).toBe(false)
  })
})
