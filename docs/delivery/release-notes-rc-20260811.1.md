# RC `rc-20260811.1` 发布说明

> 状态：Route A 本地候选；项目许可证已确定为 `Apache-2.0`，GitHub tag 和 Release 在 Stage 5.5 公开门完成后绑定。本文不表示预发布或生产放行。

## 主要能力

- 18 个原业务路由与 4 个 AI 治理路由，共 22 个受保护路由。
- 宿舍、楼栋、床位、学生、入住/退宿、维修、费用、卫生、公告、用户和角色权限管理。
- 带授权引用的 AI 助手、固定指标 Dashboard、知识治理、维修分诊、公告纯文本草稿、确定性风险中心、审批与运行审计。
- AI 写链固定为建议、变更预览、人工审批、step-up、现有业务 Service 和审计；首期 action type 仅 `REPAIR_ASSIGN` 与 `NOTICE_CREATE_DRAFT`。
- 两种一键本地演示模式：默认只读和独立审批写链，均使用 Fake provider 与隔离 MySQL/Redis。

## 本轮封版改动

- 新建安全的启动、健康、停止、精确 reset 和控制面初始化脚本。
- Compose 数据服务改为 loopback 绑定，并支持端口与镜像覆盖。
- 增加 ESLint 10 flat config，关闭全部 lint error；保留未隐藏的既有 Vue 风格 warning。
- 将 Netty 从 `4.1.135.Final` 升级到 `4.1.136.Final`，关闭 `GHSA-558v-64gr-wgg4` / `CVE-2026-59901` HIGH 阻断。
- 补齐 README、演示指南、账号模板、用户手册、运维速查、故障排查、验收清单和截图索引。
- 增加 Apache License 2.0、贡献指南、安全策略、第三方许可证与公开资产来源说明。
- 将 6 个合成评测数据集及其注册器合同统一为精确 SPDX 标识 `Apache-2.0`；`MIT`、`Apache 2.0` 等漂移值均 fail-closed 拒绝。

## 验证摘要

| 门 | 结果 |
| --- | --- |
| 前端 lint | 177 files，0 errors，2706 warnings，0 fatal |
| Vitest | 46 files / 519 tests PASS |
| 前端 coverage | statements 87.81%、branches 80.17%、functions 90.66%、lines 91.71% |
| typecheck / build / npm audit | PASS / PASS / 0 vulnerabilities |
| 普通 E2E | 72/72 PASS |
| AI-live | 1/1 PASS，Fake provider，业务写关闭 |
| 六视口视觉 | 1/1 PASS，159 PNG，错误与业务写均为 0 |
| 后端 | 161 reports / 919 tests PASS |
| AI coverage | line 93.64%、branch 80.79% |
| 真实本地 MySQL/Redis | 8/8 PASS |
| CSRF/OpenAPI/schema/框架/生产样例 | 41/41 PASS |
| Maven runtime OSV | 120 dependencies / 0 findings |
| 本地演示 | 两种模式启动、健康、停止、reset、重启均 PASS |
| clean clone / 新终端 | 无依赖、target、demo state 或残留项目环境变量；从零启动与完整健康 PASS |
| Stage 5 治理异常态 | 9/9 PASS |
| Assistant 引用撤权 | 1/1 PASS |

JAR SHA-256：`D6983CEE32D120863E2198AF0F5302A93AE7113F55021D95AD7A99CD9E5457B8`。

前端 dist inventory SHA-256：`1108939232ADD39167E9D068F2CF4C2A54CD7806844B95CC2CDBCDFDD513C5DB`。

正式视觉 manifest SHA-256：`42851A03E777281309A1064D8EC09ABABC8063484B6FE80379C4ACD2601CD01C`。

## 已知限制

- 本地演示使用确定性 Fake provider，不代表真实模型质量、成本、延迟、留存或删除合同通过。
- lint 仍报告 2706 个 Vue 模板排版/prop 风格 warning；它们未被全局关闭，不构成当前 error 门失败。
- Docker Hub 在本次网络环境不可达，演练通过脚本镜像覆盖使用 public ECR；默认镜像名仍为官方短名。
- 开发模式 ECharts 会输出 `grid.containLabel` 的 legacy 提示日志；正式视觉门无 console error，该提示不影响当前布局与功能，但后续升级 ECharts 时应迁移到 `grid.outerBounds`。
- 演示账号只从本地 `.env` 创建，仓库不提供固定密码。
- 本地日志、数据库、trace、测试报告、运行截图和 `.planning/` 不属于公开 Release。

## `NOT RUN / NOT APPROVED`

- 真实模型供应商合同与真实流量效果/成本/延迟校准。
- 生产 KMS/Secret Manager、外部向量/对象存储/恶意文件扫描和外部审计锚。
- 生产 MySQL/Redis TLS、ACL、加密备份恢复、跨实例/跨区和 RPO/RTO。
- 生产容量、压测、部署、灰度、Kill Switch 演练和正式批准。
- Route B Stage 6-8 与 PR-01 至 PR-06。

## 回滚

1. 停止本地演示：`scripts/demo-stop.ps1`。
2. 必要时用精确令牌 reset 演示数据库和 Redis prefix。
3. 检出上一个已验证 tag，不移动或复用已有 tag。
4. 以 `demo-readonly` 启动并运行完整健康检查。

回滚不得删除失败审计、关闭权限/CSRF/审批/step-up/状态机或把失败状态改成静态成功。
