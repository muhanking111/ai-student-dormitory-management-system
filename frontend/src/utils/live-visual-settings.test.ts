// @vitest-environment node

import { afterEach, describe, expect, it, vi } from 'vitest'
import { basename, dirname } from 'node:path'

const originalOutputName = process.env.VISUAL_OUTPUT_NAME

afterEach(() => {
  if (originalOutputName === undefined) delete process.env.VISUAL_OUTPUT_NAME
  else process.env.VISUAL_OUTPUT_NAME = originalOutputName
  vi.resetModules()
})

describe('live visual evidence output isolation', () => {
  it('uses a dedicated test-results child directory when configured', async () => {
    process.env.VISUAL_OUTPUT_NAME = 'visual-final-20260723-j'
    const { visualDirectory, visualManifestPath } = await import('../../e2e/live-visual-settings')

    expect(basename(visualDirectory)).toBe('visual-final-20260723-j')
    expect(basename(visualManifestPath)).toBe('manifest.json')
    expect(dirname(visualManifestPath)).toBe(visualDirectory)
    expect(basename(dirname(visualDirectory))).toBe('test-results')
  })

  it('sanitizes separators instead of allowing output outside test-results', async () => {
    process.env.VISUAL_OUTPUT_NAME = '../../outside'
    const { visualDirectory } = await import('../../e2e/live-visual-settings')

    expect(basename(visualDirectory)).toBe('.._.._outside')
    expect(basename(dirname(visualDirectory))).toBe('test-results')
  })
})
