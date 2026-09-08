# RC `rc-20260811.2` 发布说明

> 状态：Route A 本地工程候选已完成公开封版；项目许可证为 `Apache-2.0`。本文不表示预发布或生产放行。

## 发布身份

- 公开仓库：[muhanking111/ai-student-dormitory-management-system](https://github.com/muhanking111/ai-student-dormitory-management-system)
- 不可移动 tag：`rc-20260811.2`
- 公开 Release：[RC rc-20260811.2](https://github.com/muhanking111/ai-student-dormitory-management-system/releases/tag/rc-20260811.2)
- 验证源码提交：`18030b80238a9aba030843d816952b908526b97c`
- 发布当时默认分支、tag 与 Release target 绑定同一元数据提交；后续 main 已前进，本文仅说明不可移动 RC2 历史，不覆盖 2026-09-08 本地修复；Release 不上传本地 JAR、dist、日志、测试报告、截图或 `.planning/` 原始证据。

## 相对 `.1` 的修复

- `AiRunService` 在页大小完成 `1..100` 校验后使用 `Math.toIntExact`，移除用户输入的窄化强转。
- `PiiRedactionService` 收紧重叠空白和值量词，保持 L3 fail-closed 与 L2 脱敏语义，并新增 20,000 连续空白的对抗测试。
- 前端移动可访问性合同使用精确 selector 正则，避免测试侧指数回溯。
- GitHub CodeQL run `31478731800` 的 Java/Kotlin 与 JavaScript/TypeScript job 均成功，open CodeQL alerts 为 0。

## 验证摘要

| 门 | 结果 |
| --- | --- |
| 前端 lint | 177 files，0 errors，2706 warnings，0 fatal |
| Vitest / coverage | 46 files / 519 tests PASS；87.81% / 80.17% / 90.66% / 91.71% |
| typecheck / build / npm audit | PASS / PASS / 480 dependencies，0 vulnerabilities |
| 普通 E2E | 首次因本机缺 Playwright Chromium 未进入断言；显式使用本机 Edge 151 后 `72/72 PASS` |
| AI-live | `1/1 PASS`；44/44 request/response，9 个受控完成连接关闭，业务写 0 |
| 六视口视觉 | `1/1 PASS`；132 路由 + 19 Assistant + 9 prototype + 1 gallery，共 159 PNG |
| 后端 | 161 reports / `920/920 PASS`，0 failures/errors/skipped |
| AI coverage | line `12766/13633 = 93.64%`；branch `6728/8326 = 80.81%` |
| 真实本地 MySQL/Redis | `8/8 PASS` |
| CSRF/OpenAPI/schema/框架/生产样例 | `41/41 PASS` |
| Maven runtime OSV | 120 dependencies / 0 findings |
| GitHub 安全扫描 | CodeQL 0 open；Dependabot 0 open；Secret scanning 0 open |

## 制品哈希

- JAR：`58,030,307` bytes，SHA-256 `1FC8FBBB062E236AB5065E37B85F96E70531556EC299CE52F458888C7A39C4A6`。
- 前端 dist inventory：74 files / `2,136,572` bytes，SHA-256 `1108939232ADD39167E9D068F2CF4C2A54CD7806844B95CC2CDBCDFDD513C5DB`。
- AI-live `network-evidence.json`：SHA-256 `29F915384CF700344DC0FDC9B3A8235BA6E7FBE19622CBEF6FD74CCC9F2FECCA`。
- 正式视觉 manifest：SHA-256 `9E31D3FD60F27A774D0BB7F7AF12F9954A1FC9DB5A749D21ABB663CAFD35DFEE`。
- Apache License 2.0：SHA-256 `CFC7749B96F63BD31C3C42B5C471BF756814053E847C10F3EB003417BC523D30`。

## 已知限制

- 本地演示和 AI-live 使用确定性 Fake provider，不代表真实模型质量、成本、延迟、留存或删除合同通过。
- lint 保留 2706 个未隐藏的 Vue 模板排版/prop 风格 warning；当前 error 门为 0。
- 正式视觉记录 21 个套件明确允许的预期请求失败场景；未容忍 API request failure、API error、console error、page error、runtime error 和业务写均为 0。
- 演示账号只从本地 `.env` 创建；仓库不提供固定密码。
- 本地日志、数据库、trace、测试报告、运行截图、JAR、dist 和 `.planning/` 不属于公开 Release。

## `NOT RUN / NOT APPROVED`

- 真实模型供应商合同与真实流量效果、成本和延迟校准。
- 生产 KMS/Secret Manager、外部向量/对象存储/恶意文件扫描和外部审计锚。
- 生产 MySQL/Redis TLS、ACL、加密备份恢复、跨实例/跨区和 RPO/RTO。
- 生产容量、压测、部署、灰度、Kill Switch 演练和正式批准。
- Route B Stage 6-8 与 PR-01 至 PR-06。

## 回滚

1. 停止本地演示：`scripts/demo-stop.ps1`。
2. 必要时用精确令牌 reset 演示数据库和 Redis prefix。
3. 检出上一个已验证 tag；不得移动或复用已有 tag，`rc-20260811.1` 已失效。
4. 以 `demo-readonly` 启动并运行完整健康检查。

回滚不得删除失败审计、关闭权限/CSRF/审批/step-up/状态机或把失败状态改成静态成功。
