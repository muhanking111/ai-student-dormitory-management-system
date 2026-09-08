// @vitest-environment node

import { afterEach, describe, expect, it, vi } from 'vitest'

it('waits for a retained-summary refresh to finish before Dashboard visual measurements', async () => {
  const { readFileSync } = await import('node:fs')
  for (const file of ['live-visual.spec.ts', 'ai-live-dashboard-stage3-visual.spec.ts']) {
    const source = readFileSync(new URL(`../../e2e/${file}`, import.meta.url), 'utf8')
    const afterQuery = source.slice(source.indexOf(".fill('本周待维修工单有多少？')"))
    expect(afterQuery).toContain(".toHaveAttribute('aria-busy', 'false'")
    expect(afterQuery.indexOf(".toHaveAttribute('aria-busy', 'false'"))
      .toBeLessThan(afterQuery.indexOf('const geometry =') >= 0 ? afterQuery.indexOf('const geometry =') : afterQuery.indexOf('const layout ='))
  }
})
import {
  captureFreshAssistantTurnEvidence,
  validateVisualManifestEvidence,
  VISUAL_RUNTIME_ERROR_COLLECTIONS,
  type VisualEvidenceManifest,
} from './assistant-visual-evidence'

const ENV_NAMES = [
  'CI',
  'VISUAL_BASE_URL',
  'VISUAL_BACKEND_URL',
  'VISUAL_OUTPUT_NAME',
  'VISUAL_REFERENCE_INSTANT',
  'VISUAL_DB_URL',
  'VISUAL_DB_USERNAME',
  'VISUAL_DB_PASSWORD',
  'VISUAL_REDIS_HOST',
  'VISUAL_REDIS_PORT',
  'VISUAL_REDIS_PASSWORD',
  'VISUAL_REDIS_DATABASE',
  'VISUAL_AI_REDIS_KEY_PREFIX',
  'AI_LIVE_STAGE4_REDIS_KEY_PREFIX',
  'AI_LIVE_STAGE4_JAVA_TOOL_OPTIONS',
  'AI_LIVE_STAGE5_REDIS_KEY_PREFIX',
  'AI_LIVE_STAGE5_JAVA_TOOL_OPTIONS',
  'AI_LIVE_REPAIR_NOTICE_STAGE4_REDIS_DATABASE',
  'AI_LIVE_REPAIR_NOTICE_STAGE4_REDIS_KEY_PREFIX',
  'AI_LIVE_REPAIR_NOTICE_STAGE4_JAVA_TOOL_OPTIONS',
] as const

const REQUIRED_ASSISTANT_EVIDENCE_STATES = [
  'streaming',
  'succeeded',
  'citation-denied',
  'low-confidence',
  'no-grounded',
  'failed',
  'canceled',
  'timed_out',
  'history',
  'empty',
  'input',
] as const

const EXPECTED_ASSISTANT_SCREENSHOTS = [
  'live-assistant-streaming-390x844.png',
  'live-assistant-open-390x844.png',
  'fixture-assistant-safety-states-390x844.png',
  'fixture-assistant-failed-390x844.png',
  'fixture-assistant-canceled-390x844.png',
  'fixture-assistant-timed-out-390x844.png',
  'live-assistant-history-390x844.png',
  'live-assistant-empty-390x844.png',
  'live-assistant-input-390x844.png',
] as const

const originalEnvironment = Object.fromEntries(
  ENV_NAMES.map((name) => [name, process.env[name]]),
)

afterEach(() => {
  for (const name of ENV_NAMES) {
    const value = originalEnvironment[name]
    if (value === undefined) delete process.env[name]
    else process.env[name] = value
  }
  vi.resetModules()
})

describe('Playwright visual backend capability contract', () => {
  it('records a fresh assistant turn from observable runtime steps before capture', async () => {
    const calls: string[] = []
    let observedExpectedCounts: { user: number; assistant: number } | undefined
    const countSnapshots = [
      { user: 0, assistant: 0 },
      { user: 1, assistant: 1 },
    ]

    const evidence = await captureFreshAssistantTurnEvidence({
      resetConversation: async () => { calls.push('reset') },
      assertEmptyConversation: async () => { calls.push('assert-empty') },
      readMessageCounts: async () => {
        calls.push('read-counts')
        return countSnapshots.shift() ?? { user: -1, assistant: -1 }
      },
      sendQuestion: async () => { calls.push('send') },
      observeStopControl: async () => { calls.push('observe-stop'); return true },
      waitForMessageCounts: async (expected) => {
        calls.push('wait-counts')
        observedExpectedCounts = expected
      },
      assertLatestTurn: async () => {
        calls.push('assert-latest')
        return {
          citationCount: 2,
          groundingMarkers: ['VISUAL-CONTRACT'],
          expectedAnswerTextMatched: true,
          internalPayloadExposed: false,
          answerMetaCount: 3,
          answerActionCount: 4,
        }
      },
      scrollLatestAnswer: async () => { calls.push('scroll') },
      captureScreenshot: async () => {
        calls.push('capture')
        return { file: 'live-assistant-open-390x844.png', bytes: 12_345 }
      },
    }, {
      viewport: '390x844',
      question: '请检索知识标识 VISUAL-CONTRACT',
      expectedGroundingMarker: 'VISUAL-CONTRACT',
    })

    expect(observedExpectedCounts).toEqual({ user: 1, assistant: 1 })
    expect(calls).toEqual([
      'reset',
      'assert-empty',
      'read-counts',
      'send',
      'observe-stop',
      'wait-counts',
      'read-counts',
      'assert-latest',
      'scroll',
      'capture',
    ])
    expect(evidence).toEqual({
      viewport: '390x844',
      question: '请检索知识标识 VISUAL-CONTRACT',
      expectedGroundingMarker: 'VISUAL-CONTRACT',
      countsBefore: { user: 0, assistant: 0 },
      countsAfter: { user: 1, assistant: 1 },
      content: {
        stopControlObserved: true,
        citationCount: 2,
        groundingMarkers: ['VISUAL-CONTRACT'],
        expectedAnswerTextMatched: true,
        internalPayloadExposed: false,
        answerMetaCount: 3,
        answerActionCount: 4,
      },
      screenshot: { file: 'live-assistant-open-390x844.png', bytes: 12_345 },
    })
  })

  it('rejects runtime evidence when reset does not produce exactly one new turn', async () => {
    const countSnapshots = [
      { user: 0, assistant: 0 },
      { user: 1, assistant: 0 },
    ]

    await expect(captureFreshAssistantTurnEvidence({
      resetConversation: async () => undefined,
      assertEmptyConversation: async () => undefined,
      readMessageCounts: async () => countSnapshots.shift() ?? { user: -1, assistant: -1 },
      sendQuestion: async () => undefined,
      waitForMessageCounts: async () => undefined,
      assertLatestTurn: async () => undefined,
      scrollLatestAnswer: async () => undefined,
      captureScreenshot: async () => undefined,
    })).rejects.toThrow(/消息增量/)
  })

  it('validates structured assistant, content-state, screenshot, and empty-error evidence', () => {
    const manifest = completeVisualManifest()

    expect(validateVisualManifestEvidence(manifest, {
      assistantViewports: ['390x844'],
      requiredContentStateIds: [
        'repair-populated-triage',
        'notice-generated-draft',
        'approval-pending-proposal',
        'risk-populated-detail',
        'audit-populated-run',
      ],
      requiredAssistantPrototypeStates: ['streaming'],
      forbiddenAssistantPrototypeStates: ['no-permission'],
      assistantEvidenceStateViewports: ['390x844'],
      requiredAssistantEvidenceStates: REQUIRED_ASSISTANT_EVIDENCE_STATES,
      expectedAssistantScreenshotFiles: EXPECTED_ASSISTANT_SCREENSHOTS,
    })).toEqual([])
  })

  it('rejects assistant prototype evidence without an observed stop control or linked DOM content state', () => {
    const manifest = completeVisualManifest()
    manifest.assistantTurnEvidence[0]!.content!.stopControlObserved = false
    manifest.prototypeCaptures[0]!.contentStateIds = []

    const violations = validateVisualManifestEvidence(manifest, {
      assistantViewports: ['390x844'],
      requiredContentStateIds: [],
    })

    expect(violations).toEqual(expect.arrayContaining([
      '390x844 助手真实生成中未观察到停止生成控件',
      'live-assistant-streaming-390x844.png 助手 prototype capture 未绑定内容态证据',
    ]))
  })

  it('rejects assistant prototype captures that use a safety screenshot instead of the late streaming capture', () => {
    const manifest = completeVisualManifest()
    manifest.prototypeCaptures[0]!.file = 'fixture-assistant-safety-states-390x844.png'
    manifest.prototypeCaptures[0]!.contentStateIds = [
      'assistant-citation-denied-390x844',
      'assistant-low-confidence-390x844',
      'assistant-no-grounded-390x844',
    ]

    const violations = validateVisualManifestEvidence(manifest, {
      assistantViewports: ['390x844'],
      requiredContentStateIds: [],
      requiredAssistantPrototypeStates: ['streaming'],
    })

    expect(violations).toEqual(expect.arrayContaining([
      '390x844 助手 prototype capture 必须绑定 live-assistant-streaming-390x844.png streaming 态',
      'fixture-assistant-safety-states-390x844.png 助手 prototype capture 缺少 streaming 视觉状态证据',
    ]))
  })

  it('requires every assistant review state to bind a registered screenshot from the same viewport', () => {
    const manifest = completeVisualManifest()
    const failed = manifest.contentStateChecks.find((state) => state.state === 'failed')!
    delete failed.screenshotFile
    manifest.assistantScreenshots = manifest.assistantScreenshots.filter((file) => file !== 'live-assistant-input-390x844.png')
    manifest.assistantScreenshots.push('live-assistant-unexpected-390x844.png')

    const violations = validateVisualManifestEvidence(manifest, {
      assistantViewports: ['390x844'],
      requiredContentStateIds: [],
      assistantEvidenceStateViewports: ['390x844'],
      requiredAssistantEvidenceStates: REQUIRED_ASSISTANT_EVIDENCE_STATES,
      expectedAssistantScreenshotFiles: EXPECTED_ASSISTANT_SCREENSHOTS,
    })

    expect(violations).toEqual(expect.arrayContaining([
      '390x844 助手 failed 视觉状态证据缺少 screenshotFile',
      '助手截图集合缺少 live-assistant-input-390x844.png',
      '助手截图集合包含未声明文件 live-assistant-unexpected-390x844.png',
    ]))
  })

  it('requires assistant screenshot details and filenames to independently identify runtime versus fixture evidence', () => {
    const manifest = completeVisualManifest()
    const failedState = manifest.contentStateChecks.find((state) => state.state === 'failed')!
    const failedDetail = manifest.assistantScreenshotDetails.find((detail) => detail.file === failedState.screenshotFile)!
    ;(failedDetail as unknown as { evidenceSource?: string }).evidenceSource = undefined

    expect(validateVisualManifestEvidence(manifest, {
      assistantViewports: ['390x844'],
      requiredContentStateIds: [],
      assistantEvidenceStateViewports: ['390x844'],
      requiredAssistantEvidenceStates: REQUIRED_ASSISTANT_EVIDENCE_STATES,
      expectedAssistantScreenshotFiles: EXPECTED_ASSISTANT_SCREENSHOTS,
    })).toContain('390x844 助手 failed 截图详情未声明证据来源')

    ;(failedDetail as unknown as { evidenceSource?: string }).evidenceSource = 'runtime'
    expect(validateVisualManifestEvidence(manifest, {
      assistantViewports: ['390x844'],
      requiredContentStateIds: [],
      assistantEvidenceStateViewports: ['390x844'],
      requiredAssistantEvidenceStates: REQUIRED_ASSISTANT_EVIDENCE_STATES,
      expectedAssistantScreenshotFiles: EXPECTED_ASSISTANT_SCREENSHOTS,
    })).toEqual(expect.arrayContaining([
      '390x844 助手 failed 截图详情来源 runtime 与内容态 deterministic-fixture 不一致',
      'fixture-assistant-failed-390x844.png 运行态截图必须使用 live-assistant-* 命名',
    ]))
  })

  it('rejects page-level no-permission state leaking into assistant prototype captures', () => {
    const manifest = completeVisualManifest()
    manifest.contentStateChecks.push(assistantSafetyState('assistant-no-permission-390x844', 'no-permission'))
    manifest.prototypeCaptures[0]!.contentStateIds.push('assistant-no-permission-390x844')

    const violations = validateVisualManifestEvidence(manifest, {
      assistantViewports: ['390x844'],
      requiredContentStateIds: [],
      forbiddenAssistantPrototypeStates: ['no-permission'],
    })

    expect(violations).toContain(
      'live-assistant-streaming-390x844.png 助手 prototype capture 不应包含 no-permission 视觉状态证据',
    )
  })

  it('rejects mapping the global design-system board to a business route', () => {
    const manifest = completeVisualManifest() as unknown as VisualEvidenceManifest & {
      prototypeContracts: Array<{ file: string; scope: string; surfaces: string[] }>
      prototypeCaptures: Array<{ prototype: string; contractSurface: string }>
      designSystemChecks: Array<{ route: string; viewport: string; tokens: Record<string, string> }>
    }
    manifest.prototypeContracts = [{
      file: 'ai-design-system.png',
      scope: 'global-design-system',
      surfaces: ['/ai/knowledge'],
    }]
    manifest.prototypeCaptures = [{
      prototype: 'ai-design-system.png',
      contractSurface: '/ai/knowledge',
    }] as unknown as typeof manifest.prototypeCaptures
    manifest.designSystemChecks = []

    const violations = validateVisualManifestEvidence(manifest, {
      assistantViewports: ['390x844'],
      requiredContentStateIds: [],
    })

    expect(violations).toEqual(expect.arrayContaining([
      '设计系统合同必须是全局 token/组件合同，不能映射业务路由',
      '设计系统合同不能生成冒充业务页面的 prototype capture',
      '缺少真实浏览器设计 token 证据',
    ]))
  })

  it('requires a dedicated Design System gallery capture outside prototype captures', () => {
    const manifest = completeVisualManifest() as VisualEvidenceManifest & {
      designSystemGalleryCapture?: {
        file: string
        prototype: string
        contractSurface: string
        route: string
        viewport: string
        bytes: number
        width: number
        height: number
        sectionCount: number
        states: string[]
      }
    }
    delete manifest.designSystemGalleryCapture

    expect(validateVisualManifestEvidence(manifest, {
      assistantViewports: ['390x844'],
      requiredContentStateIds: [],
    })).toContain('缺少独立 Design System 状态画廊截图证据')
  })

  it('rejects a Design System gallery capture with the wrong route, viewport, or state coverage', () => {
    const manifest = completeVisualManifest() as VisualEvidenceManifest & {
      designSystemGalleryCapture: {
        file: string
        prototype: string
        contractSurface: string
        route: string
        viewport: string
        bytes: number
        width: number
        height: number
        sectionCount: number
        states: string[]
      }
    }
    manifest.designSystemGalleryCapture = {
      ...manifest.designSystemGalleryCapture,
      prototype: 'ai-dashboard-desktop.png',
      contractSurface: '/ai/knowledge',
      route: '/ai/knowledge',
      viewport: '390x844',
      width: 390,
      height: 844,
      sectionCount: 1,
      states: ['default'],
    }

    expect(validateVisualManifestEvidence(manifest, {
      assistantViewports: ['390x844'],
      requiredContentStateIds: [],
    })).toEqual(expect.arrayContaining([
      'Design System 状态画廊必须绑定 ai-design-system.png 与专用只读路由',
      'Design System 状态画廊必须使用 1505x1045 逻辑视口',
      'Design System 状态画廊必须覆盖六类区域和七类控件状态',
    ]))
  })

  it.each(VISUAL_RUNTIME_ERROR_COLLECTIONS)(
    'rejects a non-empty %s runtime error collection',
    (collection) => {
      const manifest = completeVisualManifest()
      manifest[collection] = [{ kind: 'contract-error' }]

      expect(validateVisualManifestEvidence(manifest, {
        assistantViewports: ['390x844'],
        requiredContentStateIds: [
          'repair-populated-triage',
          'notice-generated-draft',
          'approval-pending-proposal',
          'risk-populated-detail',
          'audit-populated-run',
        ],
      })).toContain(`${collection} 必须为空`)
    },
  )

  it('rejects missing real content state and ungrounded assistant evidence', () => {
    const manifest = completeVisualManifest()
    manifest.contentStateChecks = manifest.contentStateChecks.filter(
      (check) => check.id !== 'risk-populated-detail',
    )
    manifest.assistantTurnEvidence[0]!.content = {
      ...manifest.assistantTurnEvidence[0]!.content!,
      citationCount: 0,
      groundingMarkers: ['STALE-MARKER'],
    }

    const violations = validateVisualManifestEvidence(manifest, {
      assistantViewports: ['390x844'],
      requiredContentStateIds: [
        'repair-populated-triage',
        'notice-generated-draft',
        'approval-pending-proposal',
        'risk-populated-detail',
        'audit-populated-run',
      ],
    })

    expect(violations).toEqual(expect.arrayContaining([
      '缺少内容态证据 risk-populated-detail',
      '390x844 助手引用数量必须大于 0',
      '390x844 助手 grounding marker 与本轮不一致',
    ]))
  })

  it('fails closed for malformed assistant runtime evidence', () => {
    const manifest = completeVisualManifest()
    manifest.apiErrors = null as unknown as unknown[]
    const evidence = manifest.assistantTurnEvidence[0]!
    evidence.question = '   '
    evidence.expectedGroundingMarker = ''
    evidence.countsBefore = { user: 1, assistant: 1 }
    evidence.countsAfter = { user: 4, assistant: 2 }
    evidence.content = undefined
    evidence.screenshot = undefined
    manifest.assistantTurnEvidence.push({
      viewport: 'undeclared',
      countsBefore: { user: 0, assistant: 0 },
      countsAfter: { user: 1, assistant: 1 },
    })

    const violations = validateVisualManifestEvidence(manifest, {
      assistantViewports: ['390x844', '1586x992'],
      requiredContentStateIds: [],
    })

    expect(violations).toEqual(expect.arrayContaining([
      'apiErrors 必须是数组',
      '390x844 助手问题不能为空',
      '390x844 助手本轮 grounding marker 不能为空',
      '390x844 助手截图前必须从空会话开始',
      '390x844 助手截图必须绑定恰好一个新回合',
      '390x844 缺少助手内容态证据',
      '390x844 缺少助手截图证据',
      '1586x992 助手运行证据必须且只能有 1 条',
      'undeclared 是未声明的助手证据视口',
    ]))
  })

  it('rejects mismatched assistant screenshot and incomplete content-state details', () => {
    const manifest = completeVisualManifest()
    const evidence = manifest.assistantTurnEvidence[0]!
    evidence.content = {
      stopControlObserved: true,
      citationCount: 1,
      groundingMarkers: ['VISUAL-CONTRACT'],
      expectedAnswerTextMatched: false,
      internalPayloadExposed: true,
      answerMetaCount: 2,
      answerActionCount: 3,
    }
    evidence.screenshot = { file: 'unregistered.png', bytes: 100 }
    manifest.contentStateChecks = [
      {
        id: 'notice-generated-draft',
        route: '',
        viewport: '',
        entityId: '',
        state: '',
        assertions: [],
      },
      {
        id: 'notice-generated-draft',
        route: '/notices/create',
        viewport: '1536x1024',
        entityId: 'notice-2',
        state: 'generated',
        assertions: ['第二条重复证据'],
      },
    ]

    const violations = validateVisualManifestEvidence(manifest, {
      assistantViewports: ['390x844'],
      requiredContentStateIds: ['notice-generated-draft'],
    })

    expect(violations).toEqual(expect.arrayContaining([
      '390x844 助手回答未命中预期业务内容',
      '390x844 助手回答泄露内部命令 payload',
      '390x844 助手回答元数据必须为 3 项',
      '390x844 助手回答操作必须为 4 项',
      '390x844 助手截图文件过小',
      '390x844 助手截图未登记到 manifest',
      '390x844 助手截图详情与运行证据不一致',
      '内容态证据 notice-generated-draft 重复',
      'notice-generated-draft 缺少路由或视口',
      'notice-generated-draft 缺少真实实体或状态',
      'notice-generated-draft 缺少已执行的内容断言',
    ]))
  })

  it('keeps isolated real-service specs out of the ordinary offline E2E suite', async () => {
    const { default: config } = await import('../../playwright.config')
    const webServer = Array.isArray(config.webServer) ? config.webServer[0] : config.webServer

    expect(config.testIgnore).toEqual(expect.arrayContaining([
      '**/live-visual.spec.ts',
      '**/ai-live-*.spec.ts',
      '**/repair-mobile-layout.spec.ts',
      '**/repair-notice-stage4-contract.spec.ts',
      '**/governance-stage5-contract.spec.ts',
      '**/stage6-accessibility-live.spec.ts',
      '**/stage6-risk-live-geometry.spec.ts',
    ]))
    expect(webServer?.reuseExistingServer).toBe(false)
  })

  it('fails closed when formal visual does not provide a reference instant', async () => {
    delete process.env.CI
    delete process.env.VISUAL_REFERENCE_INSTANT
    process.env.VISUAL_BASE_URL = 'http://127.0.0.1:5200'
    process.env.VISUAL_BACKEND_URL = 'http://127.0.0.1:8100'
    process.env.VISUAL_DB_URL = 'jdbc:mysql://127.0.0.1:3306/visual_clock_missing_e2e'
    process.env.VISUAL_DB_USERNAME = 'visual_test'
    process.env.VISUAL_DB_PASSWORD = 'visual_test_password'
    process.env.VISUAL_OUTPUT_NAME = 'visual-clock-missing-20260803-vm'
    process.env.VISUAL_REDIS_HOST = '127.0.0.1'
    process.env.VISUAL_REDIS_PORT = '6379'
    process.env.VISUAL_REDIS_DATABASE = '10'
    process.env.VISUAL_AI_REDIS_KEY_PREFIX = 'visual:clock-missing:20260803:vm'
    vi.resetModules()

    await expect(import('../../playwright.visual.config'))
      .rejects.toThrow(/VISUAL_REFERENCE_INSTANT/)
  })

  it('starts isolated visual services itself with one fixed JVM and MySQL reference instant', async () => {
    delete process.env.CI
    process.env.VISUAL_BASE_URL = 'http://127.0.0.1:5199'
    process.env.VISUAL_BACKEND_URL = 'http://127.0.0.1:8099'
    process.env.VISUAL_REFERENCE_INSTANT = '2026-08-03T12:00:00+08:00'
    process.env.VISUAL_DB_URL = 'jdbc:mysql://127.0.0.1:3306/visual_config_contract_e2e?serverTimezone=Asia/Shanghai&useSSL=false'
    process.env.VISUAL_DB_USERNAME = 'visual_test'
    process.env.VISUAL_DB_PASSWORD = 'visual_test_password'
    process.env.VISUAL_OUTPUT_NAME = 'visual-contract-20260802-vc'
    process.env.VISUAL_REDIS_HOST = '127.0.0.1'
    process.env.VISUAL_REDIS_PORT = '6379'
    process.env.VISUAL_REDIS_DATABASE = '10'
    process.env.VISUAL_AI_REDIS_KEY_PREFIX = 'visual:contract:20260802:vc'
    vi.resetModules()

    const { default: config } = await import('../../playwright.visual.config')
    const webServers = Array.isArray(config.webServer) ? config.webServer : [config.webServer]
    const backend = webServers[0]
    const frontend = webServers[1]
    const backendEnvironment = webServers[0]?.env as Record<string, string> | undefined

    expect(backend?.reuseExistingServer).toBe(false)
    expect(frontend?.reuseExistingServer).toBe(false)
    expect(backendEnvironment?.AI_CAPABILITY_RISK_ENABLED).toBe('true')
    expect(backendEnvironment?.AI_WRITE_EXECUTION_ENABLED).toBe('false')
    expect(backendEnvironment?.DASHBOARD_CACHE_KEY).toBe('visual:contract:20260802:vc:dashboard:statistics')
    expect(backendEnvironment?.DORMITORY_AI_BUSINESS_CLOCK_FIXED_INSTANT)
      .toBe('2026-08-03T04:00:00.000Z')
    expect(backendEnvironment?.SPRING_DATASOURCE_URL)
      .toBe(
        'jdbc:mysql://127.0.0.1:3306/visual_config_contract_e2e'
        + '?serverTimezone=Asia/Shanghai&useSSL=false&sessionVariables=timestamp=1785729600',
      )
    expect(backendEnvironment).not.toHaveProperty('SPRING_DATASOURCE_HIKARI_CONNECTION_INIT_SQL')
  })

  it('keeps stage 4 on configurable isolated infrastructure and never enables business execution', async () => {
    process.env.VISUAL_BASE_URL = 'http://127.0.0.1:5198'
    process.env.VISUAL_BACKEND_URL = 'http://127.0.0.1:8098'
    process.env.VISUAL_DB_URL = 'jdbc:mysql://127.0.0.1:3306/stage4_config_contract_e2e'
    process.env.VISUAL_DB_USERNAME = 'visual_stage4'
    process.env.VISUAL_DB_PASSWORD = 'visual_stage4_password'
    process.env.VISUAL_OUTPUT_NAME = 'stage4-contract-20260802-s4'
    process.env.VISUAL_REDIS_HOST = '127.0.0.1'
    process.env.VISUAL_REDIS_PORT = '6380'
    process.env.VISUAL_REDIS_PASSWORD = 'visual_stage4_redis_password'
    process.env.VISUAL_REDIS_DATABASE = '14'
    process.env.AI_LIVE_STAGE4_JAVA_TOOL_OPTIONS = '-Xlog:exceptions=trace:file=stage4-exceptions.log:none'
    vi.resetModules()

    const { default: config } = await import('../../playwright.ai-live-stage4.config')
    const webServers = Array.isArray(config.webServer) ? config.webServer : [config.webServer]
    const backend = webServers[0]
    const frontend = webServers[1]
    const backendEnvironment = backend?.env as Record<string, string> | undefined
    const frontendEnvironment = frontend?.env as Record<string, string> | undefined

    expect(config.testMatch).toBe('ai-live-stage4.spec.ts')
    expect(config.use?.baseURL).toBe('http://127.0.0.1:5198')
    expect(backend?.url).toBe('http://127.0.0.1:8098/api/health')
    expect(frontend?.url).toBe('http://127.0.0.1:5198')
    expect(backendEnvironment?.SPRING_DATASOURCE_URL).toBe(process.env.VISUAL_DB_URL)
    expect(backendEnvironment?.REDIS_DATABASE).toBe('14')
    expect(backendEnvironment?.AI_REDIS_KEY_PREFIX).toBe('ai-live:stage4:stage4-contract-20260802-s4')
    expect(backendEnvironment?.DASHBOARD_CACHE_KEY)
      .toBe('ai-live:stage4:stage4-contract-20260802-s4:dashboard:statistics')
    expect(backendEnvironment?.AI_CAPABILITY_RISK_ENABLED).toBe('true')
    expect(backendEnvironment?.AI_RISK_EXPLANATION_ADAPTER).toBe('fake')
    expect(backendEnvironment?.AI_PROVIDER_ACTIVE).toBe('fake')
    expect(backendEnvironment?.AI_WRITE_EXECUTION_ENABLED).toBe('false')
    expect(backendEnvironment?.JAVA_TOOL_OPTIONS).toContain(process.env.AI_LIVE_STAGE4_JAVA_TOOL_OPTIONS)
    expect(backendEnvironment?.JAVA_TOOL_OPTIONS).toContain('-Ddormitory.cors.allowed-origins=')
    expect(frontendEnvironment?.VITE_BACKEND_PROXY_TARGET).toBe('http://127.0.0.1:8098')
    expect(frontend?.command).toContain('--port 5198')
    expect(backend?.command).toContain('spring-boot:run')
  })

  it('rejects a stage 4 Redis prefix that is shared across visual output candidates', async () => {
    process.env.VISUAL_BASE_URL = 'http://127.0.0.1:5197'
    process.env.VISUAL_BACKEND_URL = 'http://127.0.0.1:8097'
    process.env.VISUAL_DB_URL = 'jdbc:mysql://127.0.0.1:3306/stage4_prefix_contract_e2e'
    process.env.VISUAL_DB_USERNAME = 'visual_stage4'
    process.env.VISUAL_DB_PASSWORD = 'visual_stage4_password'
    process.env.VISUAL_OUTPUT_NAME = 'stage4-contract-20260802-s4b'
    process.env.VISUAL_REDIS_HOST = '127.0.0.1'
    process.env.VISUAL_REDIS_PORT = '6380'
    process.env.VISUAL_REDIS_DATABASE = '14'
    process.env.AI_LIVE_STAGE4_REDIS_KEY_PREFIX = 'ai-live:stage4:shared'
    vi.resetModules()

    await expect(import('../../playwright.ai-live-stage4.config'))
      .rejects.toThrow(/Redis key prefix 必须绑定本次唯一输出名/)
  })

  it('fails closed when stage 4 is pointed at a non-dedicated database', async () => {
    process.env.VISUAL_BASE_URL = 'http://127.0.0.1:5197'
    process.env.VISUAL_BACKEND_URL = 'http://127.0.0.1:8097'
    process.env.VISUAL_DB_URL = 'jdbc:mysql://127.0.0.1:3306/not-isolated'
    process.env.VISUAL_DB_USERNAME = 'visual_stage4'
    process.env.VISUAL_DB_PASSWORD = 'visual_stage4_password'
    process.env.VISUAL_OUTPUT_NAME = 'stage4-database-contract-20260802-s4c'
    process.env.VISUAL_REDIS_DATABASE = '13'
    vi.resetModules()

    await expect(import('../../playwright.ai-live-stage4.config')).rejects.toThrow(/专用.*_e2e|数据库名必须以 _e2e/)
  })

  it('keeps stage 5 accessibility evidence on isolated infrastructure with business execution disabled', async () => {
    process.env.VISUAL_BASE_URL = 'http://127.0.0.1:5196'
    process.env.VISUAL_BACKEND_URL = 'http://127.0.0.1:8096'
    process.env.VISUAL_DB_URL = 'jdbc:mysql://127.0.0.1:3306/stage5_config_contract_e2e'
    process.env.VISUAL_DB_USERNAME = 'visual_stage5'
    process.env.VISUAL_DB_PASSWORD = 'visual_stage5_password'
    process.env.VISUAL_OUTPUT_NAME = 'stage5-contract-20260802-s5'
    process.env.VISUAL_REDIS_HOST = '127.0.0.1'
    process.env.VISUAL_REDIS_PORT = '6380'
    process.env.VISUAL_REDIS_PASSWORD = 'visual_stage5_redis_password'
    process.env.VISUAL_REDIS_DATABASE = '15'
    process.env.AI_LIVE_STAGE5_JAVA_TOOL_OPTIONS = '-Xlog:exceptions=trace:file=stage5-exceptions.log:none'
    vi.resetModules()

    const { default: config } = await import('../../playwright.ai-live-stage5.config')
    const webServers = Array.isArray(config.webServer) ? config.webServer : [config.webServer]
    const backend = webServers[0]
    const frontend = webServers[1]
    const backendEnvironment = backend?.env as Record<string, string> | undefined
    const frontendEnvironment = frontend?.env as Record<string, string> | undefined

    expect(config.testMatch).toEqual([
      'ai-live-stage5.spec.ts',
      'ai-live-stage5-security.spec.ts',
    ])
    expect(config.use?.baseURL).toBe('http://127.0.0.1:5196')
    expect(backend?.url).toBe('http://127.0.0.1:8096/api/health')
    expect(frontend?.url).toBe('http://127.0.0.1:5196')
    expect(backendEnvironment?.SPRING_DATASOURCE_URL).toBe(process.env.VISUAL_DB_URL)
    expect(backendEnvironment?.REDIS_DATABASE).toBe('15')
    expect(backendEnvironment?.AI_REDIS_KEY_PREFIX).toBe('ai-live:stage5:stage5-contract-20260802-s5')
    expect(backendEnvironment?.DASHBOARD_CACHE_KEY)
      .toBe('ai-live:stage5:stage5-contract-20260802-s5:dashboard:statistics')
    expect(backendEnvironment?.AI_CAPABILITY_ASSISTANT_ENABLED).toBe('true')
    expect(backendEnvironment?.AI_CAPABILITY_RISK_ENABLED).toBe('true')
    expect(backendEnvironment?.AI_PROVIDER_ACTIVE).toBe('fake')
    expect(backendEnvironment?.AI_WRITE_EXECUTION_ENABLED).toBe('false')
    expect(backendEnvironment?.JAVA_TOOL_OPTIONS).toContain(process.env.AI_LIVE_STAGE5_JAVA_TOOL_OPTIONS)
    expect(backendEnvironment?.JAVA_TOOL_OPTIONS).toContain('-Ddormitory.cors.allowed-origins=')
    expect(frontendEnvironment?.VITE_BACKEND_PROXY_TARGET).toBe('http://127.0.0.1:8096')
    expect(frontend?.command).toContain('--port 5196')
    expect(backend?.command).toContain('spring-boot:run')
  })

  it('rejects a stage 5 Redis prefix that is shared across visual output candidates', async () => {
    process.env.VISUAL_BASE_URL = 'http://127.0.0.1:5195'
    process.env.VISUAL_BACKEND_URL = 'http://127.0.0.1:8095'
    process.env.VISUAL_DB_URL = 'jdbc:mysql://127.0.0.1:3306/stage5_prefix_contract_e2e'
    process.env.VISUAL_DB_USERNAME = 'visual_stage5'
    process.env.VISUAL_DB_PASSWORD = 'visual_stage5_password'
    process.env.VISUAL_OUTPUT_NAME = 'stage5-contract-20260802-s5b'
    process.env.VISUAL_REDIS_HOST = '127.0.0.1'
    process.env.VISUAL_REDIS_PORT = '6380'
    process.env.VISUAL_REDIS_DATABASE = '15'
    process.env.AI_LIVE_STAGE5_REDIS_KEY_PREFIX = 'ai-live:stage5:shared'
    vi.resetModules()

    await expect(import('../../playwright.ai-live-stage5.config'))
      .rejects.toThrow(/Redis key prefix 必须绑定本次唯一输出名/)
  })

  it('fails closed when stage 5 is pointed at a non-dedicated database', async () => {
    process.env.VISUAL_BASE_URL = 'http://127.0.0.1:5195'
    process.env.VISUAL_BACKEND_URL = 'http://127.0.0.1:8095'
    process.env.VISUAL_DB_URL = 'jdbc:mysql://127.0.0.1:3306/not-isolated'
    process.env.VISUAL_DB_USERNAME = 'visual_stage5'
    process.env.VISUAL_DB_PASSWORD = 'visual_stage5_password'
    process.env.VISUAL_OUTPUT_NAME = 'stage5-database-contract-20260802-s5c'
    process.env.VISUAL_REDIS_DATABASE = '12'
    vi.resetModules()

    await expect(import('../../playwright.ai-live-stage5.config')).rejects.toThrow(/专用.*_e2e|数据库名必须以 _e2e/)
  })

  it('derives the repair-notice Stage 4 Redis prefix from the unique visual output candidate', async () => {
    process.env.VISUAL_BASE_URL = 'http://127.0.0.1:5194'
    process.env.VISUAL_BACKEND_URL = 'http://127.0.0.1:8094'
    process.env.VISUAL_DB_URL = 'jdbc:mysql://127.0.0.1:3306/repair_notice_config_contract_e2e'
    process.env.VISUAL_DB_USERNAME = 'visual_repair_notice'
    process.env.VISUAL_DB_PASSWORD = 'visual_repair_notice_password'
    process.env.VISUAL_OUTPUT_NAME = 'repair-notice-contract-20260802-rn'
    process.env.VISUAL_REDIS_HOST = '127.0.0.1'
    process.env.VISUAL_REDIS_PORT = '6380'
    process.env.VISUAL_REDIS_DATABASE = '11'
    vi.resetModules()

    const { default: config } = await import('../../playwright.ai-live-repair-notice-stage4.config')
    const webServers = Array.isArray(config.webServer) ? config.webServer : [config.webServer]
    const backendEnvironment = webServers[0]?.env as Record<string, string> | undefined
    const runtimeContract = config.metadata?.aiLiveRequestedRuntimeContract as Record<string, unknown> | undefined
    const expectedPrefix = 'ai-live:repair-notice:stage4:repair-notice-contract-20260802-rn'

    expect(backendEnvironment?.AI_REDIS_KEY_PREFIX).toBe(expectedPrefix)
    expect(backendEnvironment?.DASHBOARD_CACHE_KEY).toBe(`${expectedPrefix}:dashboard:statistics`)
    expect(runtimeContract?.redisKeyPrefix).toBe(expectedPrefix)
  })

  it('scopes deterministic Stage 4 and Stage 5 Playwright artifacts to the unique visual output candidate', async () => {
    process.env.VISUAL_OUTPUT_NAME = 'local-remediation-20260908-ui'
    vi.resetModules()

    const [{ default: stage4Config }, { default: stage5Config }] = await Promise.all([
      import('../../playwright.stage4-repair-notice.config'),
      import('../../playwright.stage5-governance.config'),
    ])
    const stage4Server = Array.isArray(stage4Config.webServer)
      ? stage4Config.webServer[0]
      : stage4Config.webServer
    const stage5Server = Array.isArray(stage5Config.webServer)
      ? stage5Config.webServer[0]
      : stage5Config.webServer

    expect(stage4Config.outputDir).toBe('test-results/local-remediation-20260908-ui/playwright')
    expect(stage5Config.outputDir).toBe('test-results/local-remediation-20260908-ui/playwright')
    expect(stage4Server?.env?.VITE_E2E_DISABLE_HMR).toBe('true')
    expect(stage5Server?.env?.VITE_E2E_DISABLE_HMR).toBe('true')
  })

  it('anchors Stage 5 proposal expiry on an explicit bounded fixture clock', async () => {
    const {
      stage5AsOf,
      stage5Proposals,
      stage5ReferenceInstant,
    } = await import('../../e2e/stage5-governance-fixtures')
    const referenceTime = Date.parse(stage5ReferenceInstant)
    const activeExpiryTimes = stage5Proposals
      .filter(({ state }) => state !== 'expired')
      .map(({ expiresAt }) => Date.parse(expiresAt))
    const expiredExpiryTimes = stage5Proposals
      .filter(({ state }) => state === 'expired')
      .map(({ expiresAt }) => Date.parse(expiresAt))

    expect(stage5AsOf).toBe(stage5ReferenceInstant)
    expect(Number.isFinite(referenceTime)).toBe(true)
    expect(activeExpiryTimes.every((expiresAt) => expiresAt > referenceTime)).toBe(true)
    expect(expiredExpiryTimes.every((expiresAt) => expiresAt <= referenceTime)).toBe(true)
    expect(Math.max(...activeExpiryTimes) - referenceTime).toBeLessThanOrEqual(14 * 24 * 60 * 60 * 1000)
    expect(stage5Proposals.every(({ expiresAt }) => expiresAt.endsWith('+08:00'))).toBe(true)
  })

  it('rejects a repair-notice Stage 4 Redis prefix shared across visual output candidates', async () => {
    process.env.VISUAL_BASE_URL = 'http://127.0.0.1:5193'
    process.env.VISUAL_BACKEND_URL = 'http://127.0.0.1:8093'
    process.env.VISUAL_DB_URL = 'jdbc:mysql://127.0.0.1:3306/repair_notice_prefix_contract_e2e'
    process.env.VISUAL_DB_USERNAME = 'visual_repair_notice'
    process.env.VISUAL_DB_PASSWORD = 'visual_repair_notice_password'
    process.env.VISUAL_OUTPUT_NAME = 'repair-notice-contract-20260802-rnb'
    process.env.VISUAL_REDIS_HOST = '127.0.0.1'
    process.env.VISUAL_REDIS_PORT = '6380'
    process.env.VISUAL_REDIS_DATABASE = '11'
    process.env.AI_LIVE_REPAIR_NOTICE_STAGE4_REDIS_KEY_PREFIX = 'ai-live:repair-notice:stage4:shared'
    vi.resetModules()

    await expect(import('../../playwright.ai-live-repair-notice-stage4.config'))
      .rejects.toThrow(/Redis key prefix 必须绑定本次唯一输出名/)
  })
})

function completeVisualManifest(): VisualEvidenceManifest {
  return {
    assistantTurnEvidence: [{
      viewport: '390x844',
      question: '请检索知识标识 VISUAL-CONTRACT',
      expectedGroundingMarker: 'VISUAL-CONTRACT',
      countsBefore: { user: 0, assistant: 0 },
      countsAfter: { user: 1, assistant: 1 },
      content: {
        stopControlObserved: true,
        citationCount: 1,
        groundingMarkers: ['VISUAL-CONTRACT'],
        expectedAnswerTextMatched: true,
        internalPayloadExposed: false,
        answerMetaCount: 3,
        answerActionCount: 4,
      },
      screenshot: { file: 'live-assistant-open-390x844.png', bytes: 12_345 },
    }],
    assistantScreenshots: [...EXPECTED_ASSISTANT_SCREENSHOTS],
    assistantScreenshotDetails: EXPECTED_ASSISTANT_SCREENSHOTS.map((file) => ({
      file,
      route: '/',
      viewport: '390x844',
      bytes: 12_345,
      evidenceSource: file.startsWith('fixture-assistant-') ? 'deterministic-fixture' : 'runtime',
    })),
    contentStateChecks: [
      assistantState('assistant-streaming-390x844', 'streaming', 'live-assistant-streaming-390x844.png', 'runtime'),
      assistantState('assistant-succeeded-390x844', 'succeeded', 'live-assistant-open-390x844.png', 'runtime'),
      assistantState('assistant-citation-denied-390x844', 'citation-denied', 'fixture-assistant-safety-states-390x844.png'),
      assistantState('assistant-low-confidence-390x844', 'low-confidence', 'fixture-assistant-safety-states-390x844.png'),
      assistantState('assistant-no-grounded-390x844', 'no-grounded', 'fixture-assistant-safety-states-390x844.png'),
      assistantState('assistant-failed-390x844', 'failed', 'fixture-assistant-failed-390x844.png'),
      assistantState('assistant-canceled-390x844', 'canceled', 'fixture-assistant-canceled-390x844.png'),
      assistantState('assistant-timed-out-390x844', 'timed_out', 'fixture-assistant-timed-out-390x844.png'),
      assistantState('assistant-history-390x844', 'history', 'live-assistant-history-390x844.png', 'runtime'),
      assistantState('assistant-empty-390x844', 'empty', 'live-assistant-empty-390x844.png', 'runtime'),
      assistantState('assistant-input-390x844', 'input', 'live-assistant-input-390x844.png', 'runtime'),
      contentState('repair-populated-triage', '/repairs'),
      contentState('notice-generated-draft', '/notices/create'),
      contentState('approval-pending-proposal', '/ai/approvals'),
      contentState('risk-populated-detail', '/ai/risks'),
      contentState('audit-populated-run', '/ai/audit'),
    ],
    prototypeContracts: [
      {
        file: 'ai-assistant-mobile.png',
        evidenceMode: 'screenshot-and-content-state',
        surfaces: ['assistant'],
      },
      {
        file: 'ai-design-system.png',
        evidenceMode: 'computed-style-and-components',
        surfaces: ['global', 'components/ai'],
      },
    ],
    prototypeCaptures: [{
      file: 'live-assistant-streaming-390x844.png',
      prototype: 'ai-assistant-mobile.png',
      contractSurface: 'assistant',
      route: '/',
      viewport: '390x844',
      bytes: 12_345,
      contentStateIds: ['assistant-streaming-390x844'],
    }],
    designSystemChecks: [{
      route: '/',
      viewport: '390x844',
      tokens: {
        '--primary': '#2563eb',
        '--sidebar': '#163b83',
        '--touch-target': '44px',
        '--radius-control': '8px',
        '--radius-card': '12px',
      },
      computedStyles: {
        fontFamily: 'Inter',
        sidebarBackground: 'rgb(22, 59, 131)',
        panelBorderRadius: '12px',
        touchTargetHeight: '44px',
      },
      stateSemantics: { icon: true, text: true, ariaLive: true },
      sharedComponents: {
        AiCommandBar: true,
        AiEvidenceMeta: true,
        AiRunStatus: true,
        AiSafetyState: true,
      },
    }],
    designSystemGalleryCapture: {
      file: 'design-system-gallery-1505x1045.png',
      prototype: 'ai-design-system.png',
      contractSurface: 'global-design-system',
      route: '/__visual/design-system',
      viewport: '1505x1045',
      bytes: 123_456,
      sha256: 'A'.repeat(64),
      width: 1505,
      height: 1045,
      sectionCount: 6,
      states: ['default', 'hover', 'focus', 'active', 'disabled', 'loading', 'error'],
    },
    apiErrors: [],
    apiRequestFailures: [],
    consoleErrors: [],
    pageErrors: [],
    runtimeErrors: [],
  } as unknown as VisualEvidenceManifest
}

function contentState(id: string, route: string, viewport = '1536x1024') {
  return {
    id,
    route,
    viewport,
    entityId: `${id}-entity`,
    state: 'verified',
    assertions: ['真实 API 已完成', '内容面板可见'],
  }
}

function assistantSafetyState(id: string, state: string) {
  return assistantState(id, state, 'fixture-assistant-safety-states-390x844.png')
}

function assistantState(
  id: string,
  state: string,
  screenshotFile: string,
  evidenceSource: 'runtime' | 'deterministic-fixture' = 'deterministic-fixture',
) {
  return {
    id,
    route: '/',
    viewport: '390x844',
    entityId: `${id}-entity`,
    state,
    assertions: ['助手 UI 状态已渲染并截图'],
    evidenceSource,
    screenshotFile,
  }
}
