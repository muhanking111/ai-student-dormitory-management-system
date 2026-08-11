# 贡献指南

提交贡献即表示你有权提交相关内容，并同意该贡献按项目根目录 `LICENSE` 中的 Apache License 2.0 授权。

## 开始之前

请先阅读 [项目说明](README.md)、[计划事实优先级](plan/README.md)、[AI 数据契约](plan/ai-data-contract.md)、[AI 安全契约](plan/ai-security.md) 和 [开发提示词](plan/development-prompts.md)。当前范围是本地可交付的 Spring Boot + Vue 3 系统；Route B 生产资源、真实供应商和真实业务数据不属于普通贡献范围。

本地配置从 `.env.example` 创建，不要提交 `.env`、固定密码、Token、私钥、数据库数据、日志、trace、构建产物或原始测试报告。测试和演示数据必须明确为合成数据，不得使用真实学生、宿舍、维修、缴费或账号信息。

## 变更原则

- 保持 Vue/API/RBAC/CSRF/SSE/引用 ACL/PII/审批/step-up/审计/状态机和业务写边界。
- 不编辑、重新生成或替换 `design/ai-prototypes/` 下的 9 张最终 PNG。
- 先补复现或测试，再做最小实现；不得删除断言、降低覆盖率门或使用静态假成功。
- 不做无关重构，不提交生成目录或本机专用路径；浏览器可执行文件只能通过本地环境变量覆盖。
- API、schema、依赖或安全配置变化后，重新运行所有受影响的验证，不能复用旧候选结论。

## 本地验证

前端至少运行：

```powershell
Set-Location frontend
npm ci
npm run lint
npm run test
npm run test:coverage
npm run typecheck
npm run build
npm audit --audit-level=high
npm exec playwright install chromium
```

Playwright 浏览器安装在本机缓存中。若组织策略要求使用已安装浏览器，只在当前终端设置 `PLAYWRIGHT_BROWSER_EXECUTABLE` 或 `VISUAL_BROWSER_EXECUTABLE`；不得把本机绝对路径提交到配置或 Git 历史。

后端至少运行：

```powershell
Set-Location backend
mvn test
mvn -Pai-coverage verify
```

涉及关键页面、AI-live、真实本地 MySQL/Redis 或正式视觉合同时，还要按 [AI 验证与验收](plan/ai-verification.md) 运行对应 E2E 和隔离环境门。

## 安全问题

不要用公开 Issue 报告未披露漏洞。请按 [安全策略](SECURITY.md) 使用 GitHub 私有漏洞报告，并且只提交脱敏证据。

## 提交说明

提交信息使用 Conventional Commits，例如：

```text
feat: 增加宿舍维修筛选
fix: 修复审批过期状态仍可提交
test: 补充引用撤权回归用例
docs: 更新本地演示说明
```
