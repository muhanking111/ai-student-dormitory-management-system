# 现代 AI 智能学生宿舍管理系统计划索引

`plan/` 只维护当前有效的计划、合同、提示词和验收事实，不再保留 2026-07-11 的旧非 AI 计划快照。原业务能力已经作为现行系统基线并入 AI 总计划；后续任务不得再从旧提示词或旧验收数字恢复一套平行事实源。

## 现行文档

| 文档 | 唯一职责 |
| --- | --- |
| [AI 总计划](./ai-master-plan.md) | 项目范围、当前实现、能力/架构不变量、阶段状态、9 图映射和生产就绪计划 |
| [现行开发提示词](./development-prompts.md) | 面向后续编码 Agent 的统一执行合同和 9 张 PNG 逐页任务提示词 |
| [AI 数据、接口与事件契约](./ai-data-contract.md) | 44 张 AI 表、REST/SSE、状态、工具、审批、Outbox、迁移和生命周期 |
| [AI 安全、隐私与审批边界](./ai-security.md) | RBAC、对象授权、PII、注入、文件、CSRF、审计、step-up 和红队要求 |
| [AI 运行手册](./ai-operations-runbook.md) | 启动门、灰度、Kill Switch、事故响应、恢复、备份和交接 |
| [AI 验证与验收](./ai-verification.md) | 唯一测试/覆盖率/制品/安全验收事实源，以及生产发布门 |
| [本地封版、GitHub 公开发布与可选预发布计划](./release-candidate-delivery-plan.md) | Git 恢复、lint、RC 全量门、一键演示、公开发布检查、GitHub 仓库创建，以及条件执行的预发布/生产门 |

设计资产索引位于 [`design/ai-prototypes/README.md`](../design/ai-prototypes/README.md)。`design/` 只保留原型图片、候选视觉证据和必要索引；开发提示词只放在本目录。

## 事实优先级

发现文档冲突时按以下顺序处理：

1. 当前源码、OpenAPI、schema 和新鲜测试/运行产物。
2. `ai-data-contract.md` 与 `ai-security.md` 的数据和安全不变量。
3. `ai-master-plan.md` 的能力、架构、范围和阶段状态。
4. 9 张最终 PNG 的视觉与交互结构。
5. `development-prompts.md` 的执行提示。

PNG 中的日期、模型别名、ID、hash、数字和示例文案只表达信息层级，不是业务事实。提示词不能覆盖代码、数据或安全合同。

## 当前状态

截至 2026-08-11：

- 非 AI 业务基线、AI 阶段 0-6 后端/数据/安全实现和 9 图 UI 高保真整改与复验：`COMPLETED`。2026-08-09 用户已确认“看现在ui差不多还可以”；当前候选、工程门、独立 UI/安全复核和用户肉眼验收均已关闭。更早的 `bm/bn/bo` 与 `ew/ex/ey/ez/fa/fb/fc` 只保留为历史基线。
- 前端：18 个原业务路由 + 4 个 AI 路由，共 22 个受保护路由。
- 数据/API：19 张业务表、44 张 `ai_*` 表、OpenAPI 3.1.0 `55 paths / 60 operations`。
- 历史候选的后端、AI 覆盖率、真实 MySQL/Redis、前端、E2E、安全和供应链门保留为既有证据；旧视觉门只证明结构/旧断言通过，用户现场纠偏后不能作为高保真完成证据。当前状态与精确证据见 `ai-verification.md`。
- AI-live 使用确定性 Fake provider，只证明本地真实 HTTP/SSE/事务链路，不代表真实供应商生产验收。
- 视觉合同固定覆盖 22 个受保护路由、六个正式视口和 9 张最终 PNG。
- 当前活动 UI 纠偏计划与逐图差异矩阵位于 [`.planning/20260727-ui-prototype-texture-reassessment/`](../.planning/20260727-ui-prototype-texture-reassessment/task_plan.md)。
- 生产 KMS、外部向量/对象/扫描、外部审计锚、正式供应商数据条款、生产 TLS/备份/灾备仍为 `NOT RUN / NOT APPROVED`。
- 阶段 7 预测模型和学生端为 `OUT OF SCOPE`。
- [本地封版、GitHub 公开发布与可选预发布计划](./release-candidate-delivery-plan.md) 已于 2026-08-11 进入 `IN_PROGRESS`：Stage 0 已完成，Stage 1 正在建立新的本地版本身份；默认执行 Route A，在全部本地和公开检查通过后直接创建并推送公开仓库 `muhanking111/ai-student-dormitory-management-system`，真实预发布 Route B 仍需另行决策和授权。

精确测试计数、覆盖率、制品哈希和视觉清单哈希只在 [AI 验证与验收](./ai-verification.md) 维护。其他文档只说明稳定结构与边界并链接到该证据，避免再次产生多套验收口径。

## 固定范围

- 系统服务高校内部管理员、宿管、后勤、维修人员、只读人员和获授权审计人员。
- AI 只做授权检索、解释、固定指标、运营风险辅助、维修分诊和公告草稿。
- 首期业务提案 action type 只允许 `REPAIR_ASSIGN` 和 `NOTICE_CREATE_DRAFT`，payload schema 固定为 v1。
- 所有业务写必须经过建议、预览、人工审批和现有 Service 执行。
- AI 默认关闭；生产外部依赖和发布门未通过前不得扩大为“生产已放行”。
- 缴费仍只管理校内账单与缴费状态，不接真实支付网关。
- 阶段 7、学生端、IoT、门禁、人脸、摄像头、语音监听和任意通用 Agent 不在本轮范围。

## 文档维护

1. 每个阶段开始前先在当前活动任务计划标记 `in_progress`；阶段交付物和验证通过后，必须在同一工作会话立即更新为 `completed` 并写入 `.planning/<task>/progress.md`，不得在最终汇报时批量补写。
2. 阶段缺少环境、外部批准或验证失败时保持 `in_progress`，或明确标记 `blocked` / `NOT RUN`，同时记录原因、风险和解阻条件；没有新鲜证据不得标记完成。
3. 新功能先更新 `ai-master-plan.md` 的范围和不变量，再更新数据、安全、运行和验证文档。
4. 页面变更先用 `view_image` 查看对应最终 PNG；不调用 Figma，不重新生图。
5. 测试数、覆盖率、哈希和 `NOT RUN` 状态只更新 `ai-verification.md`。
6. 生产门必须有真实外部环境证据才能改为 `PASS`；配置存在或本地 Fake 通过不算生产验收。
7. 删除或重命名文档时先更新全仓引用，再执行坏链和旧口径扫描。
