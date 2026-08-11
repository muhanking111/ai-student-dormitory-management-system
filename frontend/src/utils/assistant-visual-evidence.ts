export interface AssistantMessageCounts {
  user: number
  assistant: number
}

export interface AssistantTurnContentEvidence {
  stopControlObserved: boolean
  citationCount: number
  groundingMarkers: string[]
  expectedAnswerTextMatched: boolean
  internalPayloadExposed: boolean
  answerMetaCount: number
  answerActionCount: number
}

export interface AssistantScreenshotEvidence {
  file: string
  bytes: number
}

export interface FreshAssistantTurnContext {
  viewport: string
  question: string
  expectedGroundingMarker: string
}

export interface FreshAssistantTurnEvidence {
  viewport?: string
  question?: string
  expectedGroundingMarker?: string
  countsBefore: AssistantMessageCounts
  countsAfter: AssistantMessageCounts
  content?: AssistantTurnContentEvidence
  screenshot?: AssistantScreenshotEvidence
}

export interface FreshAssistantTurnEvidenceSteps {
  resetConversation: () => Promise<void>
  assertEmptyConversation: () => Promise<void>
  readMessageCounts: () => Promise<AssistantMessageCounts>
  sendQuestion: () => Promise<void>
  observeStopControl?: () => Promise<boolean>
  waitForMessageCounts: (expected: AssistantMessageCounts) => Promise<void>
  assertLatestTurn: () => Promise<Omit<AssistantTurnContentEvidence, 'stopControlObserved'> | void>
  scrollLatestAnswer: () => Promise<void>
  captureScreenshot: () => Promise<AssistantScreenshotEvidence | void>
}

export async function captureFreshAssistantTurnEvidence(
  steps: FreshAssistantTurnEvidenceSteps,
  context?: FreshAssistantTurnContext,
): Promise<FreshAssistantTurnEvidence> {
  await steps.resetConversation()
  await steps.assertEmptyConversation()
  const countsBefore = await steps.readMessageCounts()
  if (countsBefore.user !== 0 || countsBefore.assistant !== 0) {
    throw new Error(
      `助手重置后仍有历史消息：user=${countsBefore.user}, assistant=${countsBefore.assistant}`,
    )
  }

  const expectedCounts = {
    user: countsBefore.user + 1,
    assistant: countsBefore.assistant + 1,
  }
  await steps.sendQuestion()
  const stopControlObserved = await steps.observeStopControl?.() ?? false
  await steps.waitForMessageCounts(expectedCounts)
  const countsAfter = await steps.readMessageCounts()
  if (
    countsAfter.user !== expectedCounts.user
    || countsAfter.assistant !== expectedCounts.assistant
  ) {
    throw new Error(
      `助手消息增量不符合合同：expected=${expectedCounts.user}/${expectedCounts.assistant}, actual=${countsAfter.user}/${countsAfter.assistant}`,
    )
  }

  const content = await steps.assertLatestTurn()
  await steps.scrollLatestAnswer()
  const screenshot = await steps.captureScreenshot()

  return {
    ...(context ?? {}),
    countsBefore,
    countsAfter,
    ...(content ? { content: { stopControlObserved, ...content } } : {}),
    ...(screenshot ? { screenshot } : {}),
  }
}

export type VisualEvidenceSource = 'runtime' | 'deterministic-fixture'

export interface VisualContentStateEvidence {
  id: string
  route: string
  viewport: string
  entityId: string
  state: string
  assertions: string[]
  evidenceSource?: VisualEvidenceSource
  screenshotFile?: string
}

export interface VisualPrototypeContractEvidence {
  file: string
  evidenceMode?: string
  scope?: string
  surfaces: string[]
}

export interface VisualPrototypeCaptureEvidence {
  file: string
  prototype: string
  contractSurface: string
  route: string
  viewport: string
  bytes: number
  contentStateIds: string[]
}

export interface VisualDesignSystemCheck {
  route: string
  viewport: string
  tokens: Record<string, string>
  computedStyles: Record<string, string>
  stateSemantics: Record<string, boolean>
  sharedComponents: Record<string, boolean>
}

export interface VisualDesignSystemGalleryCaptureEvidence {
  file: string
  prototype: string
  contractSurface: string
  route: string
  viewport: string
  bytes: number
  sha256: string
  width: number
  height: number
  sectionCount: number
  states: string[]
  viewportWidth: number
  documentWidth: number
  galleryHeight: number
  minimumTargetWidth: number
  minimumTargetHeight: number
  fontFamily: string
  tokens: Record<string, string>
}

export const VISUAL_RUNTIME_ERROR_COLLECTIONS = [
  'apiErrors',
  'apiRequestFailures',
  'consoleErrors',
  'pageErrors',
  'runtimeErrors',
] as const

export type VisualRuntimeErrorCollection = typeof VISUAL_RUNTIME_ERROR_COLLECTIONS[number]

export interface VisualEvidenceManifest {
  assistantTurnEvidence: FreshAssistantTurnEvidence[]
  assistantScreenshots: string[]
  assistantScreenshotDetails: Array<{
    file: string
    route: string
    viewport: string
    bytes: number
    evidenceSource: VisualEvidenceSource
  }>
  contentStateChecks: VisualContentStateEvidence[]
  prototypeContracts: ReadonlyArray<VisualPrototypeContractEvidence>
  prototypeCaptures: VisualPrototypeCaptureEvidence[]
  designSystemChecks: VisualDesignSystemCheck[]
  designSystemGalleryCapture?: VisualDesignSystemGalleryCaptureEvidence | null
  apiErrors: unknown[]
  apiRequestFailures: unknown[]
  consoleErrors: unknown[]
  pageErrors: unknown[]
  runtimeErrors: unknown[]
}

export interface VisualManifestEvidenceExpectation {
  assistantViewports: readonly string[]
  requiredContentStateIds: readonly string[]
  requiredAssistantPrototypeStates?: readonly string[]
  forbiddenAssistantPrototypeStates?: readonly string[]
  assistantEvidenceStateViewports?: readonly string[]
  requiredAssistantEvidenceStates?: readonly string[]
  expectedAssistantScreenshotFiles?: readonly string[]
}

export function validateVisualManifestEvidence(
  manifest: VisualEvidenceManifest,
  expectation: VisualManifestEvidenceExpectation,
): string[] {
  const violations: string[] = []

  const designSystemContracts = Array.isArray(manifest.prototypeContracts)
    ? manifest.prototypeContracts.filter((contract: VisualPrototypeContractEvidence) => contract.file === 'ai-design-system.png')
    : []
  const designSystemContract = designSystemContracts[0]
  if (!designSystemContract
    || (designSystemContract.evidenceMode !== 'computed-style-and-components'
      && designSystemContract.scope !== 'global-design-system')
    || designSystemContract.surfaces.some((surface: string) => surface.startsWith('/'))) {
    violations.push('设计系统合同必须是全局 token/组件合同，不能映射业务路由')
  }
  const designSystemCaptures = Array.isArray(manifest.prototypeCaptures)
    ? manifest.prototypeCaptures.filter((capture: VisualPrototypeCaptureEvidence) => capture.prototype === 'ai-design-system.png')
    : []
  if (designSystemCaptures.length > 0) {
    violations.push('设计系统合同不能生成冒充业务页面的 prototype capture')
  }
  const designSystemGallery = manifest.designSystemGalleryCapture
  if (!designSystemGallery) {
    violations.push('缺少独立 Design System 状态画廊截图证据')
  } else {
    if (designSystemGallery.prototype !== 'ai-design-system.png'
      || designSystemGallery.contractSurface !== 'global-design-system'
      || designSystemGallery.route !== '/__visual/design-system') {
      violations.push('Design System 状态画廊必须绑定 ai-design-system.png 与专用只读路由')
    }
    if (designSystemGallery.viewport !== '1505x1045'
      || designSystemGallery.width !== 1505
      || designSystemGallery.height !== 1045) {
      violations.push('Design System 状态画廊必须使用 1505x1045 逻辑视口')
    }
    const requiredGalleryStates = ['default', 'hover', 'focus', 'active', 'disabled', 'loading', 'error']
    if (designSystemGallery.sectionCount !== 6
      || requiredGalleryStates.some((state) => !designSystemGallery.states.includes(state))) {
      violations.push('Design System 状态画廊必须覆盖六类区域和七类控件状态')
    }
    if (designSystemGallery.bytes <= 1_024 || !/^[a-f0-9]{64}$/i.test(designSystemGallery.sha256)) {
      violations.push('Design System 状态画廊截图文件或哈希证据无效')
    }
    if (designSystemGallery.documentWidth > designSystemGallery.viewportWidth + 1
      || designSystemGallery.galleryHeight > designSystemGallery.height + 1) {
      violations.push('Design System 状态画廊存在横向溢出或超出原型首屏')
    }
    if (designSystemGallery.minimumTargetWidth < 44 || designSystemGallery.minimumTargetHeight < 44) {
      violations.push('Design System 状态画廊存在小于 44px 的交互目标')
    }
  }
  const designSystemChecks = Array.isArray(manifest.designSystemChecks)
    ? manifest.designSystemChecks
    : []
  if (designSystemChecks.length === 0) {
    violations.push('缺少真实浏览器设计 token 证据')
  } else {
    const tokenNames = ['--primary', '--sidebar', '--touch-target', '--radius-control', '--radius-card']
    const computedStyleNames = ['fontFamily', 'sidebarBackground', 'panelBorderRadius', 'touchTargetHeight']
    const semanticNames = ['icon', 'text', 'ariaLive']
    const componentNames = ['AiCommandBar', 'AiEvidenceMeta', 'AiRunStatus', 'AiSafetyState']
    for (const check of designSystemChecks) {
      for (const name of tokenNames) {
        if (!check.tokens?.[name]?.trim()) violations.push(`${check.viewport} 缺少设计 token ${name}`)
      }
      for (const name of computedStyleNames) {
        if (!check.computedStyles?.[name]?.trim()) violations.push(`${check.viewport} 缺少设计计算样式 ${name}`)
      }
    }
    for (const name of semanticNames) {
      if (!designSystemChecks.some((check) => check.stateSemantics?.[name] === true)) {
        violations.push(`缺少状态语义证据 ${name}`)
      }
    }
    for (const name of componentNames) {
      if (!designSystemChecks.some((check) => check.sharedComponents?.[name] === true)) {
        violations.push(`缺少共用 AI 组件证据 ${name}`)
      }
    }
  }

  if (Array.isArray(manifest.prototypeContracts) && Array.isArray(manifest.prototypeCaptures)) {
    for (const capture of manifest.prototypeCaptures) {
      const contract = manifest.prototypeContracts.find((candidate) => candidate.file === capture.prototype)
      if (!contract) {
        violations.push(`${capture.prototype} capture 没有对应视觉合同`)
      } else if (!contract.surfaces.includes(capture.contractSurface)) {
        violations.push(`${capture.file} 未绑定视觉合同 surface ${capture.contractSurface}`)
      }
    }
    for (const contract of manifest.prototypeContracts.filter((candidate) => candidate.file !== 'ai-design-system.png')) {
      if (!manifest.prototypeCaptures.some((capture) => capture.prototype === contract.file)) {
        violations.push(`${contract.file} 缺少真实 prototype capture`)
      }
    }
    for (const capture of manifest.prototypeCaptures.filter((candidate) => candidate.contractSurface === 'assistant')) {
      const expectedPrototypeState = expectation.requiredAssistantPrototypeStates?.includes('succeeded')
        ? 'succeeded' : 'streaming'
      const expectedPrototypeFile = expectedPrototypeState === 'streaming'
        ? `live-assistant-streaming-${capture.viewport}.png`
        : `live-assistant-open-${capture.viewport}.png`
      if (capture.file !== expectedPrototypeFile) {
        violations.push(`${capture.viewport} 助手 prototype capture 必须绑定 ${expectedPrototypeFile} ${expectedPrototypeState} 态`)
      }
      if (!Array.isArray(capture.contentStateIds) || capture.contentStateIds.length === 0) {
        violations.push(`${capture.file} 助手 prototype capture 未绑定内容态证据`)
        continue
      }
      for (const id of capture.contentStateIds) {
        const state = manifest.contentStateChecks.find((candidate) => candidate.id === id)
        if (!state || state.route !== capture.route || state.viewport !== capture.viewport) {
          violations.push(`${capture.file} 助手内容态证据 ${id} 与截图路由或视口不一致`)
        } else if (state.screenshotFile !== capture.file) {
          violations.push(`${capture.file} 助手内容态证据 ${id} 未绑定当前 prototype screenshotFile`)
        }
      }
      const linkedStates = capture.contentStateIds
        .map((id) => manifest.contentStateChecks.find((candidate) => candidate.id === id))
        .filter((state): state is VisualContentStateEvidence => Boolean(state))
      for (const requiredState of expectation.requiredAssistantPrototypeStates ?? []) {
        const evidence = linkedStates.find((state) => state.state === requiredState)
        if (!evidence) {
          violations.push(`${capture.file} 助手 prototype capture 缺少 ${requiredState} 视觉状态证据`)
        } else if (evidence.evidenceSource !== 'runtime' && evidence.evidenceSource !== 'deterministic-fixture') {
          violations.push(`${capture.file} 助手 ${requiredState} 视觉状态证据未声明来源`)
        }
      }
      for (const forbiddenState of expectation.forbiddenAssistantPrototypeStates ?? []) {
        if (linkedStates.some((state) => state.state === forbiddenState)) {
          violations.push(`${capture.file} 助手 prototype capture 不应包含 ${forbiddenState} 视觉状态证据`)
        }
      }
    }
  }

  for (const collection of VISUAL_RUNTIME_ERROR_COLLECTIONS) {
    const errors = manifest[collection]
    if (!Array.isArray(errors)) {
      violations.push(`${collection} 必须是数组`)
    } else if (errors.length > 0) {
      violations.push(`${collection} 必须为空`)
    }
  }

  const assistantScreenshots = Array.isArray(manifest.assistantScreenshots)
    ? manifest.assistantScreenshots
    : []
  const assistantScreenshotDetails = Array.isArray(manifest.assistantScreenshotDetails)
    ? manifest.assistantScreenshotDetails
    : []
  const expectedAssistantScreenshotFiles = new Set(expectation.expectedAssistantScreenshotFiles ?? [])
  if (expectedAssistantScreenshotFiles.size > 0) {
    for (const file of expectedAssistantScreenshotFiles) {
      if (!assistantScreenshots.includes(file)) violations.push(`助手截图集合缺少 ${file}`)
    }
    for (const file of assistantScreenshots) {
      if (!expectedAssistantScreenshotFiles.has(file)) violations.push(`助手截图集合包含未声明文件 ${file}`)
    }
    if (new Set(assistantScreenshots).size !== assistantScreenshots.length) {
      violations.push('助手截图集合包含重复文件')
    }
    if (assistantScreenshotDetails.length !== expectedAssistantScreenshotFiles.size) {
      violations.push('助手截图详情数量与精确截图集合不一致')
    }
    const detailFiles = assistantScreenshotDetails.map(({ file }) => file)
    if (new Set(detailFiles).size !== detailFiles.length) {
      violations.push('助手截图详情包含重复文件')
    }
    for (const file of expectedAssistantScreenshotFiles) {
      if (!detailFiles.includes(file)) violations.push(`助手截图详情缺少 ${file}`)
    }
    for (const file of detailFiles) {
      if (!expectedAssistantScreenshotFiles.has(file)) violations.push(`助手截图详情包含未声明文件 ${file}`)
    }
  }

  for (const viewport of new Set(expectation.assistantEvidenceStateViewports ?? [])) {
    for (const requiredState of new Set(expectation.requiredAssistantEvidenceStates ?? [])) {
      const matches = manifest.contentStateChecks.filter((state) => (
        state.route === '/' && state.viewport === viewport && state.state === requiredState
      ))
      if (matches.length === 0) {
        violations.push(`${viewport} 助手缺少 ${requiredState} 视觉状态证据`)
        continue
      }
      if (matches.length > 1) violations.push(`${viewport} 助手 ${requiredState} 视觉状态证据重复`)
      const evidence = matches[0]!
      if (evidence.evidenceSource !== 'runtime' && evidence.evidenceSource !== 'deterministic-fixture') {
        violations.push(`${viewport} 助手 ${requiredState} 视觉状态证据未声明来源`)
      }
      if ((requiredState === 'streaming' || requiredState === 'succeeded') && evidence.evidenceSource !== 'runtime') {
        violations.push(`${viewport} 助手 ${requiredState} 必须来自真实运行态`)
      }
      if (!evidence.screenshotFile) {
        violations.push(`${viewport} 助手 ${requiredState} 视觉状态证据缺少 screenshotFile`)
        continue
      }
      if (!assistantScreenshots.includes(evidence.screenshotFile)) {
        violations.push(`${viewport} 助手 ${requiredState} screenshotFile 未登记到 manifest`)
      }
      const detail = assistantScreenshotDetails.find((item) => (
        item.file === evidence.screenshotFile && item.viewport === viewport && item.route === '/'
      ))
      if (!detail || detail.bytes <= 1_024) {
        violations.push(`${viewport} 助手 ${requiredState} screenshotFile 缺少有效详情绑定`)
      } else if (!detail.evidenceSource) {
        violations.push(`${viewport} 助手 ${requiredState} 截图详情未声明证据来源`)
      } else {
        if (detail.evidenceSource !== evidence.evidenceSource) {
          violations.push(`${viewport} 助手 ${requiredState} 截图详情来源 ${detail.evidenceSource} 与内容态 ${evidence.evidenceSource} 不一致`)
        }
        const expectedPrefix = detail.evidenceSource === 'runtime'
          ? 'live-assistant-'
          : 'fixture-assistant-'
        if (!detail.file.startsWith(expectedPrefix)) {
          const sourceLabel = detail.evidenceSource === 'runtime' ? '运行态' : '确定性 fixture'
          violations.push(`${detail.file} ${sourceLabel}截图必须使用 ${expectedPrefix}* 命名`)
        }
      }
    }
  }

  const expectedViewports = new Set(expectation.assistantViewports)
  for (const viewport of expectedViewports) {
    const matches = manifest.assistantTurnEvidence.filter((item) => item.viewport === viewport)
    if (matches.length !== 1) {
      violations.push(`${viewport} 助手运行证据必须且只能有 1 条`)
      continue
    }

    const evidence = matches[0]!
    const marker = evidence.expectedGroundingMarker ?? ''
    if (!evidence.question?.trim()) violations.push(`${viewport} 助手问题不能为空`)
    if (!marker) violations.push(`${viewport} 助手本轮 grounding marker 不能为空`)
    if (evidence.countsBefore.user !== 0 || evidence.countsBefore.assistant !== 0) {
      violations.push(`${viewport} 助手截图前必须从空会话开始`)
    }
    if (
      evidence.countsAfter.user !== evidence.countsBefore.user + 1
      || evidence.countsAfter.assistant !== evidence.countsBefore.assistant + 1
    ) {
      violations.push(`${viewport} 助手截图必须绑定恰好一个新回合`)
    }

    const content = evidence.content
    if (!content) {
      violations.push(`${viewport} 缺少助手内容态证据`)
    } else {
      if (!content.stopControlObserved) violations.push(`${viewport} 助手真实生成中未观察到停止生成控件`)
      if (content.citationCount <= 0) violations.push(`${viewport} 助手引用数量必须大于 0`)
      if (
        content.groundingMarkers.length !== 1
        || content.groundingMarkers[0] !== marker
      ) {
        violations.push(`${viewport} 助手 grounding marker 与本轮不一致`)
      }
      if (!content.expectedAnswerTextMatched) violations.push(`${viewport} 助手回答未命中预期业务内容`)
      if (content.internalPayloadExposed) violations.push(`${viewport} 助手回答泄露内部命令 payload`)
      if (content.answerMetaCount !== 3) violations.push(`${viewport} 助手回答元数据必须为 3 项`)
      if (content.answerActionCount !== 4) violations.push(`${viewport} 助手回答操作必须为 4 项`)
    }

    const screenshot = evidence.screenshot
    if (!screenshot) {
      violations.push(`${viewport} 缺少助手截图证据`)
    } else {
      if (screenshot.bytes <= 1_024) violations.push(`${viewport} 助手截图文件过小`)
      if (!manifest.assistantScreenshots.includes(screenshot.file)) {
        violations.push(`${viewport} 助手截图未登记到 manifest`)
      }
      const detail = manifest.assistantScreenshotDetails.find((item) => (
        item.file === screenshot.file && item.viewport === viewport
      ))
      if (!detail || detail.bytes !== screenshot.bytes || detail.route !== '/' || detail.evidenceSource !== 'runtime') {
        violations.push(`${viewport} 助手截图详情与运行证据不一致`)
      }
    }
  }

  for (const evidence of manifest.assistantTurnEvidence) {
    if (!evidence.viewport || !expectedViewports.has(evidence.viewport)) {
      violations.push(`${evidence.viewport ?? 'unknown'} 是未声明的助手证据视口`)
    }
  }

  for (const id of new Set(expectation.requiredContentStateIds)) {
    const matches = manifest.contentStateChecks.filter((check) => check.id === id)
    if (matches.length === 0) {
      violations.push(`缺少内容态证据 ${id}`)
      continue
    }
    if (matches.length > 1) violations.push(`内容态证据 ${id} 重复`)
    const evidence = matches[0]!
    if (!evidence.route || !evidence.viewport) violations.push(`${id} 缺少路由或视口`)
    if (!evidence.entityId || !evidence.state) violations.push(`${id} 缺少真实实体或状态`)
    if (!Array.isArray(evidence.assertions) || evidence.assertions.length === 0) {
      violations.push(`${id} 缺少已执行的内容断言`)
    }
  }

  return violations
}
