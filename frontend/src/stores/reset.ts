import type { Pinia } from 'pinia'
import { useCheckInStore } from './checkin'
import { useDormitoryStore } from './dormitory'
import { useOperationsStore } from './operations'
import { useRbacStore } from './rbac'
import { useResourceStore } from './resource'
import { useAiStore } from './ai'
import { useAiRiskStore } from './aiRisk'
import { useAiApprovalStore } from './aiApproval'
import { useAiKnowledgeStore } from './aiKnowledge'

export function resetBusinessStores(pinia: Pinia) {
  useDormitoryStore(pinia).$reset()
  useResourceStore(pinia).$reset()
  useCheckInStore(pinia).$reset()
  useOperationsStore(pinia).$reset()
  useRbacStore(pinia).$reset()
}

export function resetAiStores(pinia?: Pinia) {
  useAiStore(pinia).resetSession()
  useAiRiskStore(pinia).resetSession()
  useAiApprovalStore(pinia).resetSession()
  useAiKnowledgeStore(pinia).resetSession()
}

export function resetSessionStores(pinia: Pinia) {
  resetBusinessStores(pinia)
  resetAiStores(pinia)
}
