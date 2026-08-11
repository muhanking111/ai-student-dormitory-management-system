import { randomUUID } from 'node:crypto'
import { mkdirSync, rmSync, writeFileSync } from 'node:fs'
import { isAbsolute, relative, resolve, sep } from 'node:path'

export default function stage5GovernanceGlobalSetup() {
  const outputName = (process.env.VISUAL_OUTPUT_NAME?.trim() || 'stage5-governance-20260802-a')
    .replace(/[^A-Za-z0-9._-]/g, '_')
  const testResultsRoot = resolve(process.cwd(), 'test-results')
  const outputDirectory = resolve(testResultsRoot, outputName, 'fixtures')
  const relativeOutput = relative(testResultsRoot, outputDirectory)

  if (!relativeOutput || isAbsolute(relativeOutput) || relativeOutput === '..' || relativeOutput.startsWith(`..${sep}`)) {
    throw new Error(`拒绝清理 test-results 之外的 Stage 5 输出目录：${outputDirectory}`)
  }

  rmSync(outputDirectory, { recursive: true, force: true })
  mkdirSync(outputDirectory, { recursive: true })
  writeFileSync(resolve(outputDirectory, 'run-meta.json'), `${JSON.stringify({
    runId: randomUUID(),
    startedAt: new Date().toISOString(),
    outputName,
  }, null, 2)}\n`, 'utf8')
}
