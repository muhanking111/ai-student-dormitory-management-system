const NON_RUNTIME_DIRECTORIES = /(?:^|\/)\b(?:e2e|test-results|coverage|playwright-report)\b(?:\/|$)/
const TEST_SOURCE_FILE = /\.(?:test|spec)\.[cm]?[jt]sx?$/

export function ignoreViteRuntimeWatchFile(filePath: string) {
  const normalizedPath = filePath.replaceAll('\\', '/')
  return NON_RUNTIME_DIRECTORIES.test(normalizedPath) || TEST_SOURCE_FILE.test(normalizedPath)
}
