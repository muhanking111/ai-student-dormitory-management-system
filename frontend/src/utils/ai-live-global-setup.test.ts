import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { assertNoUnsignedHistoricalToolFacts } from '../../e2e/ai-live-audit-preflight'
import { requireAiLiveIsolation } from '../../e2e/ai-live-isolation'
import { assertNoConflictingRuntimeSwitches } from '../../e2e/ai-live-runtime-switch-preflight'

const dedicatedDatabaseEnv = {
  VISUAL_OUTPUT_NAME: 'visual-stage6-20260802-ao',
  DB_URL: 'jdbc:mysql://127.0.0.1:3306/student_dormitory_e2e?useUnicode=true',
  DB_USERNAME: 'e2e_runner',
  DB_PASSWORD: 'e2e-only-password',
  REDIS_HOST: '127.0.0.1',
  REDIS_PORT: '6379',
  REDIS_DATABASE: '12',
  AI_REDIS_KEY_PREFIX: 'stage6:20260802:ao',
}

describe('AI live database isolation preflight', () => {
  it('accepts only dedicated loopback MySQL and Redis inputs and exposes non-sensitive evidence', () => {
    expect(requireAiLiveIsolation(dedicatedDatabaseEnv)).toEqual({
      connection: {
        hostname: '127.0.0.1',
        port: '3306',
        database: 'student_dormitory_e2e',
        username: 'e2e_runner',
        password: 'e2e-only-password',
      },
      redis: {
        hostname: '127.0.0.1',
        port: '6379',
        database: 12,
        keyPrefix: 'stage6:20260802:ao',
        dashboardCacheKey: 'stage6:20260802:ao:dashboard:statistics',
      },
      evidence: {
        outputName: 'visual-stage6-20260802-ao',
        mysql: {
          hostname: '127.0.0.1',
          port: '3306',
          database: 'student_dormitory_e2e',
        },
        redis: {
          hostname: '127.0.0.1',
          port: '6379',
          database: 12,
          keyPrefix: 'stage6:20260802:ao',
          dashboardCacheKey: 'stage6:20260802:ao:dashboard:statistics',
        },
      },
      backendEnv: {
        SERVER_ADDRESS: '127.0.0.1',
        DASHBOARD_CACHE_KEY: 'stage6:20260802:ao:dashboard:statistics',
      },
    })
    expect(JSON.stringify(requireAiLiveIsolation(dedicatedDatabaseEnv).evidence)).not.toMatch(
      /e2e_runner|e2e-only-password/,
    )
  })

  it('rejects an ordinary database even when it is on loopback', () => {
    expect(() => requireAiLiveIsolation({
      ...dedicatedDatabaseEnv,
      DB_URL: 'jdbc:mysql://127.0.0.1:3306/student_dormitory',
    })).toThrowError(/数据库名必须以 _e2e 结尾.*loopback 不能证明数据库隔离/)
  })

  it('does not treat a loopback tunnel as a database isolation boundary', () => {
    expect(() => requireAiLiveIsolation({
      ...dedicatedDatabaseEnv,
      DB_URL: 'jdbc:mysql://localhost:13306/production',
    })).toThrowError(/数据库名必须以 _e2e 结尾/)
  })

  it('rejects a remote database even when its name has the dedicated suffix', () => {
    expect(() => requireAiLiveIsolation({
      ...dedicatedDatabaseEnv,
      DB_URL: 'jdbc:mysql://db.internal.example:3306/student_dormitory_e2e',
    })).toThrowError(/拒绝写入非本机 MySQL/)
  })

  it('has no boolean escape hatch for an ordinary database', () => {
    expect(() => requireAiLiveIsolation({
      ...dedicatedDatabaseEnv,
      DB_URL: 'jdbc:mysql://127.0.0.1:3306/student_dormitory',
      AI_LIVE_ALLOW_NON_E2E_DATABASE: 'true',
    })).toThrowError(/数据库名必须以 _e2e 结尾/)
  })

  it('rejects a remote Redis even when MySQL and the key prefix are dedicated', () => {
    expect(() => requireAiLiveIsolation({
      ...dedicatedDatabaseEnv,
      REDIS_HOST: 'redis.internal.example',
    })).toThrowError(/拒绝写入非本机 Redis/)
  })

  it('rejects the default Redis DB because it cannot establish a dedicated test boundary', () => {
    expect(() => requireAiLiveIsolation({
      ...dedicatedDatabaseEnv,
      REDIS_DATABASE: '0',
    })).toThrowError(/Redis DB 必须使用 1-15/)
  })

  it('rejects a Redis key prefix that is not bound to the unique visual output name', () => {
    expect(() => requireAiLiveIsolation({
      ...dedicatedDatabaseEnv,
      AI_REDIS_KEY_PREFIX: 'shared:visual:cache',
    }, { requireOutputBoundRedisPrefix: true })).toThrowError(/Redis key prefix 必须绑定本次唯一输出名/)
  })
})

describe('AI live runtime switch preflight', () => {
  it('accepts an empty conflict query result', () => {
    expect(() => assertNoConflictingRuntimeSwitches('\r\n')).not.toThrow()
  })

  it('fails fast without clearing persistent kill switches', () => {
    expect(() => assertNoConflictingRuntimeSwitches(
      'CAPABILITY:DASHBOARD\r\nPROVIDER:fake\r\n',
    )).toThrowError(
      /CAPABILITY:DASHBOARD, PROVIDER:fake.*不会自动清除持久 Kill Switch/,
    )
  })
})

describe('AI live audit compatibility preflight', () => {
  it('accepts a dedicated database without unsigned historical tool facts', () => {
    expect(() => assertNoUnsignedHistoricalToolFacts('0\r\n')).not.toThrow()
  })

  it('rejects old unsigned tool facts without deleting or backfilling them', () => {
    expect(() => assertNoUnsignedHistoricalToolFacts('2\r\n')).toThrowError(
      /2 条旧版未签名工具事实.*不会自动删除或伪造回填.*新的专用 \*_e2e 数据库/,
    )
  })

  it('fails closed when the compatibility query result is not a count', () => {
    expect(() => assertNoUnsignedHistoricalToolFacts('unexpected')).toThrowError(
      /审计兼容性查询结果不合法/,
    )
  })
})

describe('AI live control-plane bootstrap', () => {
  it('activates the prompt required by the enabled fake risk explanation runtime', () => {
    const source = readFileSync(resolve(process.cwd(), 'e2e/ai-live-global-setup.ts'), 'utf8')
    const runtimePromptBlock = source.match(/const runtimePrompts = \[([\s\S]*?)\] as const/)?.[1] ?? ''
    expect(runtimePromptBlock).toContain("'risk.system'")
  })

  it('retires previous permanent-visual knowledge before creating the current evidence source', () => {
    const source = readFileSync(resolve(process.cwd(), 'e2e/ai-live-global-setup.ts'), 'utf8')

    expect(source).toContain("s.name LIKE '全量视觉知识源 %'")
    expect(source).toContain("name LIKE '全量视觉知识源 %'")
  })
})
