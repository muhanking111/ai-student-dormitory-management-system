# AI 运行手册（首期阶段 0–6）

> 现行范围、默认状态和生产就绪顺序见 [AI 总计划](./ai-master-plan.md)；可复现命令、候选哈希和 `NOT RUN` 发布门见 [AI 验证与验收](./ai-verification.md)。本手册描述操作流程，不单独声明生产已放行。

## 1. 适用范围与默认状态

本手册只覆盖首期管理员 AI 能力。预测模型和学生端不在范围内。所有 AI 总开关、能力开关、供应商、流式服务和写执行在仓库默认配置中均为关闭；未完成对应发布门时不得临时绕过。

运行时 Kill Switch 只能收窄部署配置，不能把环境变量中关闭的能力打开。关闭状态写入 MySQL `ai_runtime_switch` 事实表，每次门禁从共享事实源读取，因此应用重启和多实例不会静默丢失状态。状态变更、step-up 消费和审计在同一数据库事务中提交；仍应从每个实例发起探测，排除数据库连接或旧版本实例异常。

## 2. 启动前门禁

1. 先保持 `AI_ENABLED=false` 启动并验证原业务健康、登录和 CRUD。
2. 确认 `ai_*` Schema、索引、14 个权限、活动 Prompt/ToolCatalog、预算桶和审计链可写。
3. 生产环境必须使用非 root MySQL 最小权限账号，连接串启用服务端身份校验；Redis 必须是私网、ACL 用户、密码和 TLS。
4. Cookie 会话必须设置 `SESSION_COOKIE_SECURE=true`；TLS 终止代理必须保留正确的 scheme/forwarded 信息，不能以“外层已有 HTTPS”为由让应用继续签发非 Secure Cookie。
5. 设置独立的 `AI_REDIS_KEY_PREFIX`，长度 8–128，仅使用字母、数字、冒号、下划线和连字符，且不得以 `satoken:` 开头或与 Sa-Token、其他环境/租户 namespace 重叠。
6. `AI_AUDIT_HMAC_KEY`、`AI_TOKENIZATION_HMAC_KEY`、`AI_STEP_UP_HMAC_KEY` 均使用独立的至少 32 字节密钥，并通过已批准 Secret Manager 注入，不写入源码、镜像、日志或工单。`AI_SECRET_MANAGER_PROVIDER` 与 `AI_KMS_KEY_REFERENCE` 必须填写可审计的 provider/code 和 KMS key reference；配置值只是启动门证据索引，不能替代真实授权、轮换和解密演练。
7. `AI_PROVIDER_ENDPOINT_ALLOWLIST` 必须精确包含当前 provider 的 canonical HTTPS endpoint；启用外锚时，`AI_AUDIT_ANCHOR_ENABLED=true`、`AI_AUDIT_ANCHOR_SINK=https-hmac`，并配置独立的 endpoint/HMAC key，`AI_AUDIT_ANCHOR_ENDPOINT_ALLOWLIST` 必须精确包含该 canonical HTTPS endpoint。带 userinfo、query、fragment、非 HTTPS 或未在 allowlist 中的地址一律拒绝启动。
8. 设置 `AI_BACKUP_ENCRYPTION_CONFIRMED=true` 前，必须已有加密备份和一次可复核恢复记录；该变量不是替代证据。
9. `AI_PROVIDER_ACTIVE=fake` 只允许 `dev`/`test`。没有真实凭证时保持 `none`；生产开启 Spring AI 还必须配置非空 provider credential 和合法 model alias。首期知识对象/向量实现仍是受控 Fake，生产开启 Knowledge 会由 `KNOWLEDGE_PRODUCTION_ADAPTERS_REQUIRED` 拒绝启动，直到独立评审通过。
10. 只有 P4.4 审批、step-up、唯一 execution lease、现有 Service 执行和审计链全链通过后，才允许设置 `AI_WRITE_EXECUTION_ENABLED=true`。

生产 AI 开启但上述 MySQL、Redis、备份、密钥或 provider 条件不满足时，`ProductionAiSecurityGate` 会使应用启动失败。AI 关闭时该门不阻断原业务。

## 3. 建议灰度顺序

每档至少观察一个完整业务高峰窗口，并保存低基数指标、错误码、预算、审计完整性和人工反馈证据。

1. 5%：仅内部管理员；先开 Dashboard、确定性风险和知识关键词降级，不开写执行。
2. 25%：扩大到批准的宿管管理员；开启带引用知识助手，继续保持 provider/source 独立开关。
3. 100%：只有离线 eval、红队、真实服务 E2E、容量和成本门均通过后扩大。
4. 写能力单独灰度：先 `NOTICE_CREATE_DRAFT`，再 `REPAIR_ASSIGN`；任一阶段重复写、越权、审计缺失或旧快照执行必须为 0。

## 4. Kill Switch 操作

需要 `ai:config:manage`。浏览器 Cookie 写请求还必须满足 CSRF 与 Origin 校验。禁用命令不要求 step-up，以便紧急关闭；清除覆盖会重新放行部署配置允许的流量，因此必须提供绑定目标和请求 hash 的单次 `X-Step-Up-Proof`。

- 查询：`GET /api/ai/operations/kill-switches`
- 禁用：`POST /api/ai/operations/kill-switches/{scope}/{key}/disable`
- 清除：`POST /api/ai/operations/kill-switches/{scope}/{key}/clear`

禁用请求体只包含理由：`{"reason":"已确认的 6-500 字处置理由"}`。清除请求必须同时提交列表中刚读取的当前版本：`{"reason":"上游恢复且已完成人工复核","expectedVersion":3}`。Scope：

- `MASTER/*`：停止所有新 AI 操作；
- `CAPABILITY/{ASSISTANT|KNOWLEDGE|DASHBOARD|REPAIR|NOTICE|RISK|EVALUATION}`；
- `PROVIDER/{providerCode}`；
- `SOURCE/{sourcePublicId}`；
- `WRITE/*`：立即阻止新的审批执行，保留提案读取和人工业务流程。

禁用响应和列表会返回稳定的 `resourcePublicId` 与当前 `version`。清除必须按以下顺序执行：

1. 重新读取 `GET /api/ai/operations/kill-switches`，固定目标的 `resourcePublicId` 和当前 `version`。
2. 固定最终清除理由，并按 UTF-8 对 canonical 文本 `kill-switch.v2|{SCOPE}|{CANONICAL_KEY}|false|{trimmedReason}|{expectedVersion}` 计算 SHA-256；`SCOPE` 和 key 使用服务端 canonical 大写/规范值。
3. 向 `POST /api/security/step-up` 提交 `actionCode=KILL_SWITCH_CLEAR`、上述 `resourcePublicId` 和 `requestHash`，获取单次 proof。
4. 使用同一理由、同一 `expectedVersion` 和 `X-Step-Up-Proof` 调用 clear。版本已被其他处置更新时返回 409，必须从第 1 步重新开始；不得把旧 proof 或旧 hash 改绑到新版本。

禁用后必须从每个实例读取开关列表，并发起一个预期返回 `AI_DISABLED`、`AI_CAPABILITY_DISABLED` 或 `AI_PROVIDER_DISABLED` 的探测请求。不要用前端菜单隐藏作为关闭证据。写执行还会在 `OperationsService` 调用前最后一次复核 `WRITE/*`，避免 lease 获取后的开关竞态。

## 5. 事故处置

### 越权、PII、Prompt Injection 或绕审批写

1. 立即禁用 `MASTER/*`；若只涉及写链，同时禁用 `WRITE/*`。
2. 保留数据库、对象版本、索引版本、Outbox 和审计链；不要删除或覆盖现场。
3. 撤销受影响用户会话与 step-up grant，轮换泄露的供应商或 HMAC 密钥。
4. 按 run/proposal/execution/correlation ID 核对调用、引用、批准人、业务结果和审计 hash chain。
5. 修复后运行对应红队、越权、PII、幂等和真实服务 E2E；未达零容忍门不得清除开关。

### 审计不可写

新的 AI run、提案执行和敏感审计正文读取必须 fail-closed。立即禁用 `MASTER/*`，修复数据库或审计密钥，验证链头连续性和篡改检测后再恢复。原业务必须继续可用。

### 供应商超时、429、5xx 或成本异常

禁用对应 `PROVIDER` 或高成本 `CAPABILITY`，保留确定性 Dashboard、风险规则或授权关键词降级。禁止同时启用框架重试和自有重试；逐物理 attempt 对账 token 与成本。

### 知识摄取或引用异常

禁用具体 `SOURCE`，暂停激活，固定可疑 object version/ETag/checksum；回退到上一 READY 版本并重建向量。未 READY、ACL 为空、扫描失败或已退休版本不得进入检索。

## 6. 恢复与回滚

1. 选择上一已验证的 prompt/model/index/policy 版本，不原地修改不可变版本。
2. 对对象 checksum、chunk hash、向量引用、ACL snapshot 和当前版本指针做一致性检查。
3. 先在 Fake/离线 eval 和真实服务 E2E 复现，再在 5% 白名单恢复。
4. 清除 Kill Switch 前重新认证并获取目标绑定的单次 proof；清除后逐实例验证。
5. 如果业务执行结果不确定，进入 `NEEDS_REVIEW` 对账，不重试业务写；确认未执行后才可重新获得 execution lease。

## 7. 备份恢复演练

仓库 `compose.yml` 只用于本地开发，暴露端口且 Redis 无 ACL/TLS，不能作为生产安全配置。生产演练应在隔离恢复环境完成：

1. 创建 MySQL 加密备份并记录备份 ID、KMS key version、开始/结束时间和 checksum。
2. 恢复到隔离 MySQL，使用只读校验账号核对 19 张业务表、全部 `ai_*` 表、行数、外键和关键唯一约束。
3. 验证 `ai_audit_chain_head` 与事件重算一致，随机篡改副本应被完整性检查拒绝。
4. 重建 Redis，不从 Redis 恢复 AI 事实；验证 Sa-Token 与缓存 namespace 隔离、旧会话失效。
5. 从固定对象版本重建知识索引，验证 checksum、ACL 和 citation；不得用缓存或向量库反向覆盖 MySQL 事实。
6. 运行 AI 总开关关闭下的原业务全量 E2E，再运行 Fake Adapter 合同与只读 AI 冒烟。
7. 记录 RTO、RPO、负责人、失败项和证据路径；没有真实演练记录时必须标记 `NOT RUN`。

## 8. 发布与交接证据

每次发布至少保存：后端单测/MockMvc/真实 MySQL+Redis、前端 Vitest/typecheck/build、Mock 与真实服务 E2E、六视口视觉（1920x1080、1366x768、1586x992、1536x1024、1505x1045、390x844）及 manifest、离线 eval、红队、依赖与密钥扫描、Kill Switch 演练、备份恢复演练。真实 provider/vector/object/KMS/外锚缺少环境时明确写 `NOT RUN`，不得写成 PASS。
