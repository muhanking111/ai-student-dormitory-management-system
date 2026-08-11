interface VisualEvidenceEnvironment {
  dev: boolean
  flag?: string
}

export function resolveVisualEvidenceMode(environment: VisualEvidenceEnvironment) {
  return environment.dev && environment.flag === 'true'
}

export const visualEvidenceMode = import.meta.env.DEV
  && import.meta.env.VITE_VISUAL_EVIDENCE_ENABLED === 'true'
