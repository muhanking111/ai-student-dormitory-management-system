import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import approvalViewSource from './AiApprovalView.vue?raw'
import auditViewSource from './AiAuditView.vue?raw'
import riskViewSource from './AiRiskView.vue?raw'
import noticeViewSource from './NoticeManagementView.vue?raw'

const globalStyles = readFileSync(resolve(process.cwd(), 'src/style.css'), 'utf8')

function literalPixelFontSizes(source: string) {
  const styles = source.slice(source.lastIndexOf('<style scoped>'))
  return [...styles.matchAll(/font-size:\s*(\d+(?:\.\d+)?)px/g)].map((match) => ({
    declaration: match[0],
    size: Number(match[1]),
  }))
}

describe('Stage 5 治理与公告移动可访问性合同', () => {
  it('风险中心在窄宽下提供 44px 筛选、分页和处置目标，并允许内容收缩换行', () => {
    const mobileStyles = riskViewSource.slice(riskViewSource.lastIndexOf('@media (max-width: 760px)'))

    expect(mobileStyles).toMatch(/\.risk-center\s*\{[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.risk-workbench\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);/s)
    expect(mobileStyles).toMatch(/\.risk-filters\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.risk-filters > \*\s*\{[^}]*min-width:\s*0;[^}]*overflow-wrap:\s*anywhere;/s)
    expect(mobileStyles).toMatch(/\.icon-button\s*\{[^}]*width:\s*44px;[^}]*height:\s*44px;/s)
    expect(mobileStyles).toMatch(/\.risk-filters select,\s*\.risk-filters input,\s*\.risk-pagination select\s*\{[^}]*min-width:\s*0;[^}]*min-height:\s*44px;/s)
    expect(mobileStyles).toMatch(/\.page-controls\s*\{[^}]*flex-wrap:\s*wrap;/s)
    expect(mobileStyles).toMatch(/\.page-controls button\s*\{[^}]*min-width:\s*44px;[^}]*height:\s*44px;/s)
    expect(mobileStyles).toMatch(/\.action-buttons button\s*\{[^}]*min-height:\s*44px;[^}]*white-space:\s*normal;/s)
  })

  it('审批页在窄宽下让治理标签、筛选、分页、审批和对账操作可触达', () => {
    const mobileStyles = approvalViewSource.slice(approvalViewSource.lastIndexOf('@media (max-width: 768px)'))

    expect(mobileStyles).toMatch(/\.approval-page\s*\{[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.governance-tabs\s*\{[^}]*flex-wrap:\s*wrap;[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.governance-tabs a\s*\{[^}]*min-width:\s*0;[^}]*min-height:\s*44px;[^}]*overflow-wrap:\s*anywhere;/s)
    expect(mobileStyles).toMatch(/\.approval-workbench\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);/s)
    expect(mobileStyles).toMatch(/\.rail-heading select\s*\{[^}]*min-width:\s*0;[^}]*min-height:\s*44px;/s)
    expect(mobileStyles).toMatch(/\.proposal-pagination\s*\{[^}]*flex-wrap:\s*wrap;/s)
    expect(mobileStyles).toMatch(/\.proposal-pagination button\s*\{[^}]*width:\s*44px;[^}]*height:\s*44px;/s)
    expect(mobileStyles).toMatch(/\.approval-actions button,\s*\.ai-reconfirm button\s*\{[^}]*min-height:\s*44px;[^}]*white-space:\s*normal;/s)
  })

  it('审计页在窄宽下让筛选、分页、刷新、指标和正文操作保持 44px', () => {
    const mobileStyles = auditViewSource.slice(auditViewSource.lastIndexOf('@media (max-width: 768px)'))

    expect(mobileStyles).toMatch(/\.audit-page\s*\{[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.governance-tabs\s*\{[^}]*flex-wrap:\s*wrap;[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.governance-tabs a\s*\{[^}]*min-width:\s*0;[^}]*min-height:\s*44px;/s)
    expect(mobileStyles).toMatch(/\.audit-filter-bar\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.audit-filter-bar select,\s*\.audit-filter-bar input,\s*\.audit-filter-bar button\s*\{[^}]*min-width:\s*0;[^}]*min-height:\s*44px;/s)
    expect(mobileStyles).toMatch(/\.audit-workbench\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);/s)
    expect(mobileStyles).toMatch(/\.run-pagination\s*\{[^}]*flex-wrap:\s*wrap;/s)
    expect(mobileStyles).toMatch(/\.run-pagination button\s*\{[^}]*width:\s*44px;[^}]*height:\s*44px;/s)
    expect(mobileStyles).toMatch(/\.refresh-button,\s*\.metrics-heading button,\s*\.audit-content-actions button\s*\{[^}]*min-height:\s*44px;[^}]*white-space:\s*normal;/s)
    expect(mobileStyles).toMatch(/\.audit-content-actions :is\(p,\s*span,\s*button\)\s*\{[^}]*min-width:\s*0;[^}]*overflow-wrap:\s*anywhere;/s)
  })

  it('风险、审批与审计页不再声明低于 12px 的必要界面文字', () => {
    for (const [surface, source] of [
      ['风险中心', riskViewSource],
      ['待审批', approvalViewSource],
      ['运行审计', auditViewSource],
    ] as const) {
      const undersized = literalPixelFontSizes(source).filter(({ size }) => size < 12)
      expect(undersized, `${surface} 仍存在低于 12px 的必要文字`).toEqual([])
    }
  })

  it('治理页的筛选、视图切换、分页与主要命令在桌面和移动均使用 44px 合同', () => {
    expect(approvalViewSource).toMatch(/\.governance-tabs a\s*\{[^}]*min-height:\s*(?:44px|var\(--touch-target\));/s)
    expect(approvalViewSource).toMatch(/\.rail-heading select\s*\{[^}]*min-height:\s*(?:44px|var\(--touch-target\));/s)
    expect(approvalViewSource).toMatch(/\.proposal-pagination button\s*\{[^}]*width:\s*(?:44px|var\(--touch-target\));[^}]*height:\s*(?:44px|var\(--touch-target\));/s)

    expect(auditViewSource).toMatch(/\.governance-tabs a\s*\{[^}]*min-height:\s*(?:44px|var\(--touch-target\));/s)
    expect(auditViewSource).toMatch(/\.audit-filter-bar select,\s*\.audit-filter-bar input\s*\{[^}]*min-height:\s*(?:44px|var\(--touch-target\));/s)
    expect(auditViewSource).toMatch(/\.view-toggle button\s*\{[^}]*min-height:\s*(?:44px|var\(--touch-target\));/s)
    expect(auditViewSource).toMatch(/\.run-pagination button\s*\{[^}]*width:\s*(?:44px|var\(--touch-target\));[^}]*height:\s*(?:44px|var\(--touch-target\));/s)

    expect(riskViewSource).toMatch(/\.risk-filters select,\s*\.risk-filters input(?:,\s*[^{}]+)*\s*\{[^}]*min-height:\s*(?:44px|var\(--touch-target\));/s)
    expect(riskViewSource).toMatch(/\.page-controls button\s*\{[^}]*min-width:\s*(?:44px|var\(--touch-target\));[^}]*height:\s*(?:44px|var\(--touch-target\));/s)
  })

  it('公告起草页在窄宽下让主要动作和提交区收缩、换行且保持 44px', () => {
    const mobileStyles = noticeViewSource.slice(noticeViewSource.lastIndexOf('@media (max-width: 768px)'))

    expect(mobileStyles).toMatch(/\.notice-create-page\s*\{[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.notice-workspace-grid,\s*\.notice-action-panel\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.notice-submit-actions\s*\{[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.notice-submit-actions > div\s*\{[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\);[^}]*min-width:\s*0;/s)
    expect(mobileStyles).toMatch(/\.notice-primary-button,\s*\.notice-disabled-button\s*\{[^}]*min-width:\s*0;[^}]*min-height:\s*44px;[^}]*white-space:\s*normal;[^}]*overflow-wrap:\s*anywhere;/s)
    expect(mobileStyles).toMatch(/\.notice-submit-actions \.notice-primary-button,\s*\.notice-submit-actions \.notice-disabled-button\s*\{[^}]*min-height:\s*44px;/s)
  })

  it('知识治理在移动与 200% 缩放宽度下约束真实输入、分段项和原生按钮', () => {
    const mobileStyles = globalStyles.slice(globalStyles.lastIndexOf('@media (max-width: 768px)'))

    expect(mobileStyles).toMatch(/\.ai-knowledge-ingest \.ant-input,[\s\S]*?\.ai-knowledge-create button,[\s\S]*?\.ai-knowledge-create \.ant-segmented\s*\{[^}]*min-height:\s*var\(--touch-target\);/s)
    expect(mobileStyles).toMatch(/\.ai-knowledge-ingest \.ant-segmented-item-label,[\s\S]*?\.ai-knowledge-create \.ant-segmented-item-label\s*\{[^}]*min-height:\s*40px;[^}]*align-items:\s*center;/s)
  })
})
