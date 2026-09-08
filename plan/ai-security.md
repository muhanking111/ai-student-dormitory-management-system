# AI 安全、隐私与审批边界

> 状态：生产前强制安全合同。
> 适用对象：AI REST/SSE、模型与 Embedding 调用、RAG、知识文件、工具、审批、风险中心、审计和供应商适配。
> 本文区分应用内安全合同与生产外部门；是否通过以 [AI 验证与验收](./ai-verification.md) 的同日证据为准，历史阶段完成不覆盖后续修改。生产 KMS、外部审计锚、供应商正式数据条款、生产数据基础设施与灾备等仍须在上线前完成。

## 2026-09-08 本地交付边界

- 工具目录 v2 仅包含 3 个上下文执行入口、2 个服务端提案入口、2 个非执行预留 ID；provider-callable 集合为空。运行入口与 readiness 校验当前标准版本、manifest hash 和规范化内容，不允许“记录旧目录、执行新代码”。旧目录仅作历史事实；通过原治理 API 的 step-up、CAS、审计及 outbox 激活新目录。
- FixedToolExecutor 对当前固定 shape、调用次数、UTF-8 响应字节数与结果准入时限做检查；不是通用 JSON Schema 引擎，也不是可中断任意阻塞 handler 的 hard timeout。固定本地 handler 的资源约束与 provider 自身超时分别负责各自边界。
- step-up 密码核验与 proof 签发持有用户行锁，与密码更新/旧 proof 撤销串行化；不降低密码要求、会话检查或一次性消费约束。
- grounded assistant 和 knowledge command 的最终提交在同一事务内按 run → user/membership/role → knowledge source/version/chunk 顺序锁定事实，重算当前权限并与起点权限比较。撤权先提交则拒绝写入正文、delta、citation 和完成事件；最终提交先获得授权锁则撤权等待其提交。SSE 发送与后续引用访问仍执行各自的会话/权限再校验。
- Demo 使用隔离库、Fake provider 和 Windows 用户绑定的持久加密 keyring。它不提供生产 KMS、跨用户密钥迁移或灾备保证；缺失/损坏已有密钥不能用重置数据库掩盖。

## 安全目标

1. AI 不能扩大当前用户在 Sa-Token/RBAC 下的权限。
2. 外部模型、向量库和对象存储只获得完成请求所需的最小化数据。
3. 模型、用户、知识文件和工具输出都按不可信输入处理。
4. 任何业务写入都绑定可复核的 proposal、人工身份、权限、payload hash、业务快照和幂等执行记录。
5. 审计失败时拒绝启动 AI；AI 故障时不影响现有非 AI 业务。
6. 未经批准的 SQL、URL、命令、文件、Bean、方法或工具永远不可由模型动态调用。

## 威胁模型

| 威胁 | 典型路径 | 必需控制 | 失败结果 |
| --- | --- | --- | --- |
| 身份伪造 | 浏览器伪造 userId、角色、页面上下文；SSE 复用他人 runId | 只信 Sa-Token；资源级 owner/ACL；每次 SSE/审批/执行重新鉴权 | 401/403，记录拒绝审计 |
| 越权/IDOR | 猜测 AI UUID 或现有业务数值 ID | AI 资源使用 UUID、所有资源执行 object authorization；不可见与不存在统一 404 | 不泄露资源是否存在 |
| Proposal 篡改 | 修改 action payload、版本、审批前业务状态 | canonical JSON hash、snapshot hash、版本和过期检查 | 409 + proposal STALE |
| CSRF | 利用 Cookie 调用审批、知识上传、风险处置 | AI 状态变更接口校验 CSRF token 和 Origin；SameSite/Lax 不是唯一防线 | 403，禁止执行 |
| Prompt injection | 文档要求忽略系统提示、泄露数据或调用隐藏工具 | 指令/数据隔离；服务端工具白名单；ACL 前后过滤；输出/工具 Schema | 拒绝工具、降级或无依据回答 |
| 数据泄露 | prompt、tool 参数、SSE、日志、trace、citation 暴露 PII/密钥 | 场景脱敏、字段级 allowlist、正文日志默认关闭、引用重鉴权 | 终止 run，触发隐私事件 |
| SQL/命令/SSRF | 模型构造 SQL、URL、shell 或动态 endpoint | 不注册相关工具；MetricCatalog；固定 provider egress allowlist | 结构化校验失败并审计 |
| 文件攻击 | 恶意 PDF/Office、宏、压缩炸弹、路径穿越 | MIME/扩展/签名/大小校验、恶意文件扫描、无宏、content-addressed key、解析限额 | QUARANTINED |
| XSS | 模型答案、引用、公告草稿带 HTML/脚本 | 首期纯文本；Vue 文本插值；禁止 `v-html`；未来富文本统一 sanitizer/CSP | 拒绝或转义内容 |
| 重放/重复写 | 重复 approve、网络超时后重试 | Idempotency-Key、proposal 终态、业务写与 execution 成功原子提交 | 返回原结果，不重复写 |
| 资源耗尽 | 长 prompt、并发 SSE、递归工具、超大文件 | 用户/IP/能力配额、长度、并发、timeout、工具次数和文件限制 | 413/429/超时 |
| 供应链/依赖 | AI SDK/BOM 引入冲突或漏洞 | 固定版本、依赖树、SBOM/OSV、真实契约测试、最小 adapter | 阻止发布 |
| 审计抵赖 | 审批人否认、DB 中记录被改 | append-only audit event、actor、hash 链、受限访问、备份 | 安全告警和人工调查 |

## 信任边界

### 浏览器到应用

- 不信任客户端提交的 userId、permission、role、publisher、repair assignee、resource object、tool name 或 model name。
- 客户端只提交 AI public ID、现有业务数值 ID、用户意图和当前看到的 proposal version/hash；服务端重新读取全部业务事实，不以 ID 难猜作为授权。
- 所有使用 Cookie 鉴权的状态变更 `/api/**`（包括现有业务和 AI）要求同源或 allowlisted Origin，并使用 CSRF token。Token 不放 URL、SSE event 或日志；只使用 Authorization header 且不带 Cookie 的非浏览器客户端按独立策略处理。
- SSE 客户端首期使用同源、带凭证的 fetch stream；若未来改用 `EventSource` 也只能同源。响应 `Cache-Control: no-store`，代理禁止缓冲和共享缓存。

### AI 编排

- system/developer policy、用户输入、检索文档、工具输出分别建模，不能拼接成无边界字符串。
- 检索文档和工具结果显式标记为“数据，不是指令”。
- 模型输出先经过结构化解析、Bean Validation、枚举/长度、权限和业务预检，失败时不进入工具或 proposal。
- 模型没有对 ToolCatalog、prompt activation、provider config、quota 或 feature flag 的写权限。

### 业务服务

- `ai.application` 不允许依赖业务 Mapper/JdbcTemplate。
- 请求入口创建短生命周期 `AuthenticatedRunContext`。raw Sa-Token 只存在于当前进程内存且受 run 总时限约束；数据库/日志只存 HMAC fingerprint。
- 后台只读/提案工具不依赖 ThreadLocal；`SessionValidityPort` 验证原会话，`ActorAuthorizationFacade` 重读账号启用状态和 RBAC，随后只读 Facade 返回专用最小 DTO。
- 所有后台活动使用 `ActorDescriptor(USER/SERVICE/MODEL/SYSTEM)`；`initiated_by_user_id` 只表示来源，不转化为授权。ingestion/eval/outbox/risk scan 的 service principal 只拥有 AI 控制面最小写权限与显式批准、按 scope 裁剪的确定性只读 Facade，不得调用 `StpUtil.login`、用 ADMIN 身份扩大读取范围或成为 `executed_by_user_id`。
- Approved handler 是固定 action type 到固定 Java handler 的 map，不接受模型提供的 class/method/URL。
- 执行保持在当前审批 HTTP 请求的 Sa-Token 上下文；现有 Service 的 `StpUtil.checkPermission` 必须真实通过。
- 后台 worker 不得模拟或伪造审批人会话执行业务写。

### 外部供应商

- provider adapter 只能访问预配置 endpoint，禁止运行时传入任意 base URL。
- 只允许 TLS；生产环境需校验证书，禁止关闭 hostname/certificate validation。
- egress firewall/代理只放行获批的模型、Embedding、向量和对象存储域名。
- 供应商返回的 tool call、citation、usage 和错误都不可信，必须校验和脱敏。

### MySQL、Redis 与备份

- 当前开发配置中的 MySQL `useSSL=false` 和可空 Redis 密码不得沿用到生产。生产 MySQL 必须 TLS、AI/应用最小权限账号、静态与备份加密、受控网络和恢复演练。
- Redis 承载 Sa-Token，会话数据的敏感级别高于普通缓存。生产必须私网、ACL/强密码、TLS、禁止公网，并把 Sa-Token keyspace/实例与可丢弃的 AI cache/限流数据隔离，防止 eviction 或运维误清理会话。
- AI 开启时生产配置门必须 fail fast 检查上述要求；备份、恢复与删除证明受同一保留/访问政策约束，不能把明文 PII/secret 复制到普通运维 artifact。

## 权限模型

### 新权限码

| 权限码 | 能力 | 还必须满足 |
| --- | --- | --- |
| `ai:assistant:use` | 创建全局/上下文会话 | 上下文资源原 read 权限 |
| `ai:knowledge:read` | 使用知识检索 | source permission ACL |
| `ai:knowledge:manage` | 创建来源、上传、激活/退役版本 | source Owner 或管理员策略 |
| `ai:knowledge:publish-public` | 审批固定知识版本为公开 | 非 Owner 治理审批 + recent-auth + 固定 content/policy hash |
| `ai:dashboard:query` | 自然语言指标查询 | 每个 metric 的原 read 权限 |
| `ai:repair:triage` | 维修分诊与 assignment proposal | `repair:read` |
| `ai:notice:draft` | 公告草稿 proposal | `notice:read` |
| `ai:risk:read` | 查看风险案例 | 每条信号底层数据权限 |
| `ai:risk:manage` | 扫描、确认、解决案例 | 风险策略允许的范围 |
| `ai:approval:review` | 查看/审批 proposal | 目标 action 的原业务 write 权限 |
| `ai:audit:read` | 查看脱敏运行审计和成本 | 不自动授予原始正文访问 |
| `ai:audit:content:read` | 按理由读取经脱敏的必要审计正文 | 对应底层业务权限 + break-glass 策略；每次访问再审计 |
| `ai:config:manage` | 管理 prompt/model alias/quota 非密钥配置 | 系统管理员策略 |
| `ai:eval:run` | 运行离线/真实模型 eval | 数据集访问和预算 |

首期共 14 个 AI 权限码；`RbacDataInitializer` 必须逐项幂等 seed，并用测试固定数量与字符串，避免实现遗漏正文审计或公开知识治理权限。

迁移默认：

- ADMIN 可在产品确认后显式获得一般 AI 权限；`ai:audit:content:read` 和 `ai:knowledge:publish-public` 默认都不自动授予 ADMIN/运维，必须通过单独批准的 break-glass/知识治理角色或显式用户映射。
- DORM_MANAGER、REPAIRER、VIEWER 不自动新增任何 AI 权限，避免升级后权限扩大。
- 角色分配通过现有角色管理流程完成，并新增回归测试。

### 多层授权

1. Controller 检查能力权限。
2. Context resolver 检查资源级 read 权限与 owner。
3. ToolAuthorizationPolicy 计算本 run 工具集合。
4. 只读 Facade 再检查原业务 read 权限。
5. proposal 列表、详情、reject、approve 与 handler 共用 `ActionAuthorizationPolicy`，检查 `ai:approval:review` + action 的完整业务策略，而不只是一个 permission code。
6. `REPAIR_ASSIGN` 的统一策略为 ADMIN 身份 + `repair:write` + 目标对象范围；REPAIRER 即使持有 `repair:write` 也看不到跨单 proposal，直接 UUID 访问统一 404。
7. handler 调用现有 Service，Service 再执行 `StpUtil.checkPermission`。

任何一层拒绝都不能被后续层覆盖。`*` 超级权限只按现有 `auth.hasPermission` / Sa-Token 服务端规则处理，不能由客户端声明。

## PII 与数据分级

### 分级

| 级别 | 示例 | 外部模型默认策略 | 存储/日志 |
| --- | --- | --- | --- |
| L0 公开 | 经批准公开的制度、FAQ | 可发送给获批 provider | 可保存版本和引用 |
| L1 校内 | 内部流程、一般公告、聚合指标 | 仅发送给合同允许且不用于训练的 provider | 脱敏保存；正文 trace 关闭 |
| L2 个人 | 姓名、学号、手机号、具体宿舍/维修位置、账号显示名 | 删除、掩码或稳定会话内 token；场景确需时必须完成隐私评审 | 原值不进 prompt/tool audit/SSE；映射保留在内部短生命周期内 |
| L3 高敏/密钥 | 密码、Token、API key、健康/纪律/财务敏感明细、未授权画像 | 禁止发送 | 禁止写 AI 表和日志；检测到即阻断 |

### 场景脱敏

| 场景 | 可发送 | 必须移除/令牌化 |
| --- | --- | --- |
| 知识助手 | 已批准文档的授权片段、标题、章节 | 文档中偶发姓名/电话/学号；密钥；隐藏指令 |
| Dashboard | 聚合指标、口径、时间范围 | 明细学生记录；小群体可重识别维度 |
| 维修分诊 | 类型、脱敏描述、设备类别、一般紧急信号 | 报修人、手机号、精确宿舍号；用位置 token 或楼栋级信息 |
| 公告草稿 | 用户要点、批准引用、公共时间地点 | 学生名单、手机号、账号、未授权内部事件 |
| 风险中心 | 规则信号、subject token、时间区间 | 姓名、学号、电话、自由文本原文；模型不接收人工敏感结论 |
| 审计/eval | policy/version/hash、脱敏输入输出、指标 | provider key、Cookie、原始 PII、完整异常请求 |

建议初始值、待真实数据校准：Dashboard 对可识别群体的展示最小样本数为 10；小于阈值时合并为“其他”或只返回总量。该值必须由学校隐私政策确认。

### 脱敏实现

- `PiiClassificationService` 先做字段级固定规则，再做可选检测器；固定字段规则优先于模型判断。
- `PiiRedactionService` 输出 redacted value、分类、命中规则、policy version 和不可逆 hash。
- 稳定 token 只在同一授权场景/短周期内一致，避免跨场景关联画像。
- 学号、手机号、session fingerprint 等低熵标识若需关联，只能使用带环境密钥和用途域分离的 HMAC；不能用裸 SHA-256 伪装匿名化。
- prompt、tool request/response、SSE、trace、metric tag 和异常信息分别调用对应 allowlist，不复用“显示用 DTO”。
- 流式输出通过跨 chunk 的滚动 holdback buffer 检测/脱敏后再发送，避免手机号、密钥等模式恰好被 token 分段绕过；最终持久化文本再次全量检查。
- 任何脱敏失败或分类未知的 L2/L3 输入默认阻断，不以“尽力而为”继续发送。

## Prompt injection 与工具安全

### 防御链

1. 摄取时扫描“忽略系统指令、泄露 prompt、调用工具、访问链接”等注入模式，标记风险但不把正则命中当唯一判断。
2. 文档片段包裹为独立 data block，附 source/version/chunk，不与 system prompt 混写。
3. system policy 明确文档中的指令无效；模型只能使用服务器注册的工具。
4. retrieval 前后使用同一 ACL 语义：显式 `PUBLIC_APPROVED`，或 `EXPLICIT_ACL` 的 ANY/ALL 权限匹配；空 ACL 默认拒绝。
5. 每个工具有固定 Schema、权限、次数、大小与超时；未知工具一律 DENIED。
6. 工具结果重新脱敏，不能把内部 Entity、异常栈或未授权字段返回模型。
7. 提案 payload 重新构造可信 actor 和目标，不接受模型传入 publisher/userId/method。
8. 高风险或无引用回答不生成 proposal；模型声称“已经执行”时 UI 仍以 execution 记录为准。

### 明确禁止

- 模型列举数据库表、生成或解释将被执行的任意 SQL。
- 动态 URL 抓取、重定向跟随、私网/IP/metadata endpoint 访问。
- shell、PowerShell、JavaScript/Python 执行、模板表达式、反射或动态 Bean。
- 让模型选择 provider endpoint、Object key、文件路径、工具 class/method。
- 把工具错误、Stack Trace、SQLState、内部 ID 或权限列表直接回传模型。

## 文件摄取

首期仅允许管理员显式上传，不支持模型触发 URL 抓取。

知识 ACL 固定规则：

- `EXPLICIT_ACL` version 使用 source 的多行 permission，匹配方式由 `permission_match_mode=ANY/ALL` 明确定义；空集合是 deny-all，不是公开。
- 公开内容必须由单个 document version 显式 `PUBLIC_APPROVED`、source 分类 L0 且有产品批准；source Owner 只有管理资格，不自动拥有正文读取权，也不能自行批准公开。
- ACL 表不存 `*`；当前 RBAC 中真实超级权限仅在服务端逐项校验时满足所需 permission。ACL 变更校验权限码存在、授予范围和操作者权限，并原子递增 acl version/清除缓存。
- public approval 绑定 version content hash/classification/acl/policy snapshot；任一变化立即撤销。检索按 chunk 的 document version 检查 active approval，同 source 中批准 A 不得公开未批准 B/C。
- 文件名、外部 ID 等低熵 source key 使用用途域分离、带 key version 的 HMAC，不使用裸 hash。

建议初始值、待真实数据校准：

- 单文件最大 20 MB；单 source 每次最多 20 个文件。
- 允许扩展名与 MIME/文件签名三者同时匹配的 PDF、TXT、DOCX；拒绝宏、可执行文件、脚本、HTML、压缩包和加密文件。
- PDF 页数、DOCX 解压后大小、XML 实体、图片像素与解析时长均设上限，防止压缩炸弹和解析器 DoS。

固定控制：

- 原始文件名只作为脱敏显示标签，不参与磁盘/object key。
- content-addressed object key 必须归一化，拒绝 `..`、绝对路径、NTFS alternate stream 和控制字符。
- 客户端不能提交 object key/bucket/URL。服务端 upload session 绑定 owner、source、expected checksum、大小和过期时间，只签发对随机隔离 key 的一次条件 PUT；过期或重复写拒绝。
- finalize 后立即撤销写 target；服务端自行计算 observed checksum/size 并固定 object versionId/ETag。MIME、签名、恶意文件、PII/密钥扫描，以及 parser/embedding 均按同一固定版本读取并复验 checksum，防止扫描后覆盖/版本切换。
- 上传先进入隔离区，完成上述检查并达到 SCANNED_CLEAN 后才进入 PARSING；重复 finalize 只返回原 job。
- 解析器不访问网络，不执行宏，不加载外部实体/字体/链接。
- 扫描服务不可用时保持 QUARANTINED，不允许“先发布后补扫”。

生产恶意文件扫描产品与部署方式需要架构评审；在没有扫描能力前，知识功能只能使用仓库内已批准的纯文本 fixture 或管理员手工导入的受控文本。

## 结构化输出、XSS 与内容安全

- 所有模型字符串设置服务端长度上限和 Unicode 正规化。
- 公告首期只保存/渲染纯文本；换行由组件安全展示。
- AI 答案和引用标签使用 Vue 文本插值，禁止 `v-html`、动态组件名和内联事件。
- URL citation 首期不直接输出可点击外链；只输出内部 citation endpoint。未来外链须做 scheme/host allowlist 和安全跳转页。
- 若未来支持 Markdown，采用禁用 raw HTML 的 parser、严格 URL allowlist、统一 sanitizer 和 CSP 回归测试。
- CSV/Excel 导出中的模型文本需防公式注入：以 `=`、`+`、`-`、`@` 开头的单元格转义。

## CSRF、CORS 与会话

- 现有 Cookie 为 `HttpOnly`、`SameSite=Lax`；生产 HTTPS 下必须启用 `Secure`。
- CORS 只允许配置的管理端 origin，`allowCredentials=true` 时不能使用通配符。
- 所有 Cookie 鉴权的 POST/PUT/PATCH/DELETE（现有业务、AI、知识上传和审批）检查 Origin/Referer 与 CSRF token，避免 AI 执行链安全但原业务写端点仍暴露同类风险。
- 登录、会话恢复、SSE 重连和审批期间检测账号停用/权限撤销；撤销后立即停止后续 tool 和敏感 event。
- 进程重启或 run context 丢失时，未完成 run 安全失败；不能用持久化 permission digest 代替活会话恢复执行。
- SSE URL 不包含 token、prompt、userId、provider key 或 PII。
- 会话固定/并发策略沿用 Sa-Token；AI run 不能延长登录会话有效期。
- 所有已认证 AI 正文、conversation、citation、proposal preview、审计和 CSRF 响应统一 `Cache-Control: private, no-store`，按实际鉴权载体设置 `Vary: Authorization, Cookie, Origin`；反向代理/CDN 禁止共享缓存，前端禁止写 localStorage/IndexedDB/Service Worker cache。公共 `/api/health` 是无敏感正文的唯一例外。

当前 Sa-Token 长会话不能单独满足 break-glass。已实现 `RecentAuthenticationPolicy`：审计正文读取、prompt/model/config 激活、知识 `PUBLIC_APPROVED` 审批必须带与当前 session/actor/action/resource/request hash 绑定的短期 step-up 证明，并记录 `auth_time`、方式和事件。`POST /api/security/step-up` 强制 CSRF/Origin、账号状态、爆破限流和失败审计；proof 通过专用 header 单次使用，跨 action/resource 重放、并发双用、logout/kickout/改密后使用均拒绝。建议初始有效窗口 15 分钟、待学校安全政策和实际流程校准；无 MFA 时至少重新验证密码并签发一次性/短期 token，密码不得进入日志/AI 表。未来高风险 action 是否强制 MFA/step-up 由产品与安全评审；普通首期低风险审批不能反向弱化上述强制点。

CSRF 方案已使用 `GET /api/security/csrf` 返回登录态绑定 token；`frontend/src/api/client.ts` 对所有非安全方法统一携带 header，服务端已对 Cookie 鉴权的写请求开启 Origin/Referer 与 token 强制校验。当前实现已完成与 Sa-Token Cookie、Header 客户端及 CORS 行为的 MockMvc/浏览器验证；生产启用仍须配置 HTTPS、`Secure` Cookie、可信代理头和精确的 Origin/CORS allowlist。

## Secrets 与供应商治理

- 环境变量只保存 secret reference 或开发密钥；生产使用获批 Secret Manager/KMS。
- 数据库只存 provider code、model alias、endpoint alias、region 和 capability，不存明文 secret。
- adapter 初始化时验证必需 secret；缺失时该 provider 状态为 DISABLED，主业务仍启动。
- 错误信息仅显示 provider alias 和稳定错误码，绝不显示 header、request body、签名 URL 或 SDK 配置 dump。
- 日志、trace、测试 fixture、截图和 CI artifact 均执行 secret scan。生产默认禁用自动 heap dump 和未授权远程诊断；break-glass dump 必须审批，只写入最小权限加密目录，按 L3 事件处理，禁止上传 CI/普通工单，并在短保留期后留下可验证销毁记录。事后 secret scan 不能把 heap dump 变成普通安全 artifact。
- 密钥按供应商独立、最小权限和环境隔离；轮换不修改 prompt/model deployment public ID。
- 供应商合同必须确认：数据是否用于训练、默认/可配置保留期、处理地域、子处理方、删除/导出、事件通知和退出机制。

## 审批完整性

- preview 必须展示 action type、目标、当前值、建议值、数据 as-of、引用、风险、所需权限、过期和 hash。
- `approve` 同时验证 proposal version、payload hash、business snapshot hash 和幂等键。
- publisher、operator、reviewer、assignee 等可信 actor 字段由服务端当前会话或确定性选项重写。
- 首期只允许 `NOTICE_CREATE_DRAFT` 和 `REPAIR_ASSIGN`；状态由代码 enum + handler map 固定。
- proposal 列表/详情/reject/approve/handler 都调用同一 `ActionAuthorizationPolicy`。`REPAIR_ASSIGN` 必须同时满足当前 `OperationsService` 的“仅系统管理员可分配”、`repair:write` 和对象范围；维修人员不能通过列表或猜 UUID 看见不可执行 proposal。
- approval append-only；proposal 和 execution 不能由普通 CRUD 接口任意 PATCH。run/tool/execution 是版本化状态聚合，只允许合法前态 CAS，不能误当作可原地改写的 append-only 事实。
- APPROVE、REJECT、EXPIRE、CANCEL、STALE 在同一 proposal 行锁/CAS 上串行；最后审批事务内原子写 approval、计数、EXECUTING 和 `UNIQUE(proposal_id)` execution lease。并发请求无论使用相同或不同 key 都只能产生一次 execution。
- 幂等记录按 `actor + route + proposal + key` 唯一并保存 canonical request hash；同 key 异 payload 返回 409，客户端 key 不直接充当全局 execution key。
- 业务 Service 与 execution success 在同一 MySQL 事务中提交；失败审计用独立事务保存。
- 进程崩溃留下 NEEDS_REVIEW 时必须由新会话中的真实有权用户调用 reconfirm；先对账业务结果并重验 CSRF/权限/hash/snapshot。可证明未执行且快照未变才复用同一 execution row 的新 lease version；结果不确定则保持人工审查，worker 不冒充用户。
- 四眼原则是产品决策；无论是否同人审批，审计都保留 proposer 与 reviewer。

## 审计与防抵赖

已实现 append-only `ai_audit_event`，字段至少包括：

- public ID、chain scope、aggregate type/public ID、event type、`ActorDescriptor`（kind、user/service、initiator/effective subject），以及 session fingerprint HMAC/key version。
- permission digest、policy/prompt/tool/model version。
- payload redacted hash、previous_event_hash、event_hash、`integrity_alg`、`integrity_key_version`、`canonicalization_version`、occurred_at、request correlation ID。

`event_hash` 对 canonical event + `previous_event_hash` 计算，按 chain scope/aggregate 形成 hash 链。写入前对 chain head 行锁或 CAS，原子分配 sequence/previous hash，禁止并发分叉。key 轮换后新事件记录新 version 并继续链接旧 hash；验证器按事件版本选择 key/canonicalization。数据库管理员仍可能整体重写，因此生产还需把每日链头/Merkle root 导出到独立、只追加介质并保存 receipt hash；具体存储由架构评审决定。

访问控制：

- `ai:audit:read` 只能读脱敏事件。
- 会话详情/live SSE 只允许 owner；审计人员不能借普通端点旁路。
- 必要正文只走 `ai:audit:content:read` 独立端点，还需当前底层业务权限和 `X-Audit-Reason`；该权限默认不随 ADMIN/运维自动获得，每次读取再写审计。
- 原始对象引用需要额外业务权限与审计理由，不因任何 audit 权限自动放行。
- audit 查询固定筛选字段和页大小，禁止全文导出全量正文。
- 审批、工具拒绝、脱敏阻断、注入命中、配额拒绝和 citation 访问都必须写事件。

## 限流、配额与滥用

建议初始值、待真实数据校准：

- 每用户每分钟 10 个新 run、同时 2 个 run。
- knowledge upload 每用户每小时 20 个文件。
- 每 run 最多 5 次工具、20 个 citation candidate、输入 16k tokens、输出 4k tokens。
- SSE 每用户最多 3 个连接；断线重连使用退避。
- 审批接口按用户和 proposal 双维度限制，连续 hash 冲突触发安全告警。

限流同时使用登录 userId 和 IP/设备信号；不能仅依赖可伪造的 `X-Forwarded-For`。代理信任链需部署配置确认。

硬预算以 MySQL bucket 为权威：run、真实 eval 和会产生 provider 费用的 ingestion 在排队/调用前，以稳定顺序锁住所有适用 user/role/capability/provider/eval bucket，或用余额条件 CAS，整组预留成功后才调用。每个物理 provider attempt（含重试、tool loop、fallback）单独记 ledger 并对账；Redis 只做 burst/concurrency 加速。并发临界余额、取消、中断和缺失 usage 都不得造成超预算或漏记费用。

## 保留、删除与数据主体请求

当前没有学校法律/档案政策证据，以下均为建议初始值、待法务和产品校准：

| 数据 | 建议保留 | 删除/归档 |
| --- | --- | --- |
| 脱敏 message 与 run event | 30 天 | 到期清空正文，保留不可逆 hash 和聚合指标 |
| tool/retrieval/citation 明细 | 90 天 | 保留必要审计 hash，正文按 ACL 删除 |
| usage/cost | 365 天 | 聚合后归档 |
| proposal/approval/execution/audit | 365 天或学校审计政策要求 | 不允许用户直接删除；到期归档并保留链头 |
| knowledge retired version | 至少覆盖所有 citation 保留期 | 先退役索引，再按引用与档案政策删除对象 |
| eval artifact | 180 天 | 仅保存脱敏样本；失败样本同样受保留期 |

删除会话时先 ARCHIVED，创建可重试 erasure job，并将 MySQL 正文、raw object、vector、cache、获批 provider retention API 拆成独立 target。每个 target 保存状态和不可逆 proof reference；完成后写 `ERASURE_TOMBSTONE` 与 hash-chain checkpoint。业务事实、审批和法定审计不能被会话删除连带移除；legal hold 显式标为 RETAINED。供应商不支持删除或无法返回完成证据时保持 PARTIAL/NEEDS_REVIEW，不能向用户声称全部删除。

## 安全测试矩阵

| 测试 | 层级 | 必需断言 |
| --- | --- | --- |
| 未登录/停用/权限撤销 | MockMvc + E2E | REST、SSE、tool、proposal、citation 全部拒绝 |
| recent-auth | MockMvc + E2E | 仅有长寿命 Cookie 不能读 audit content、激活 prompt/config 或批准 PUBLIC；step-up 绑定 session/actor/action、过期/重放拒绝并审计；keyring 轮换窗口内旧未过期 proof 可按版本验证，过窗即失效 |
| IDOR/对象策略 | MockMvc | 用户 A 不能读用户 B 的 conversation/run/proposal/citation；REPAIRER 的 list/direct UUID 都看不到仅 ADMIN 可执行 proposal |
| 知识 ACL/公开审批 | Service + MySQL | empty deny、ANY/ALL、Owner 非天然可读、`*` 不入表；Owner 不能自升 PUBLIC，旧 content hash 批准不能套新内容，并发 activate/ACL change 安全撤销 |
| 双重权限 | Service + MockMvc | 有 AI 权限但无业务 write，及反向组合都不能审批 |
| CSRF/CORS | MockMvc + 浏览器 | 缺 token、恶意 Origin、跨站表单均不能改变状态 |
| 任意 SQL/工具 | Unit + real model contract | prompt 含 SQL/class/method/URL 也只产生 DENIED tool audit |
| Prompt injection | offline eval + red team | 文档/用户/tool output 注入不能泄露 prompt、扩大 ACL 或执行 proposal |
| PII 泄露 | Unit + contract + log scan | 姓名/学号/手机号/密钥不出现在 provider request、SSE、audit、trace |
| 文件攻击/TOCTOU | Unit + integration | 双扩展名、MIME 欺骗、路径穿越、宏、压缩炸弹、恶意 PDF、扫描后覆盖、过期 presign、版本切换进入 QUARANTINED/拒绝 |
| XSS/公式注入 | Vitest + E2E | AI 文本只作文本渲染；公告/导出不能执行脚本或公式 |
| 缓存隔离 | MockMvc + 代理/浏览器 | 认证 AI JSON/SSE/CSRF 均 private/no-store 且正确 Vary；localStorage/Service Worker/CDN 无跨用户正文缓存 |
| 重放/竞态 | MySQL integration | approve/approve、approve/reject、approve/expire、reconfirm/approve 竞态只产生一个 execution lease；所有 201/202 command 响应丢失重试只建一次 run/job/provider call；同 key 异 payload/snapshot 变化返回 409 |
| 风险状态竞态 | MySQL integration | acknowledge/resolve/dismiss/reopen 共用 case version/CAS；重复 key 只写一条 event；模型/服务主体不能覆盖人工结论 |
| 业务服务边界 | Service test | AI package 无 Mapper/JdbcTemplate 依赖；现有 Service 权限仍生效 |
| 服务主体 | Service + integration | 后台任务有 service actor/initiator；无 `StpUtil.login`；service/model/system actor 调业务写恒拒绝 |
| 限流/成本 | Integration | 并发临界预算仅允许额度内请求；超限 429 且 provider 0 调用；run/eval/ingestion 的重试/fallback 逐 attempt 对账，reservation 正确释放 |
| 审计完整性 | MySQL integration | 并发事件不分叉；每个 run/tool/approval/execution 有含 alg/key/canonicalization version 的 hash 链；轮换可验证、篡改可检测、每日外锚有 receipt |
| 清除证明 | Integration | archive 后不能新 run；对象/向量/cache/provider target 可重试；legal hold 保留；缺 proof 不得完成 |
| 数据基础设施 | 启动门 + 恢复演练 | 生产 MySQL 无 TLS、Redis 公网/空 ACL/无 TLS 或会话与 AI cache 未隔离时 AI fail fast；加密备份可按 RPO/RTO 恢复 |
| secrets | 质量门 | 源码、fixture、日志、截图、artifact 无常见 token/key |
| 依赖 | Maven/npm/OSV | 选定 AI 依赖无未接受的高危漏洞，依赖树可重复 |

发布零容忍项：

- 未授权业务写执行次数必须为 0。
- 跨权限 citation/knowledge 命中必须为 0。
- L3 secret/credential 外发必须为 0。
- 红队中成功的任意 SQL、shell、URL fetch 或隐藏工具调用必须为 0。

这些是安全不变量，不属于待校准业务阈值。

## 事件响应与恢复

### Kill switch

- `dormitory.ai.enabled=false`：关闭全部 AI API/worker，原业务继续。
- 能力级开关：单独关闭 knowledge/dashboard/repair/notice/risk。
- `write-execution.enabled=false`：保留只读和提案，禁止审批执行。
- provider/vector/source 级开关：隔离单一供应商或知识源。

### 处置步骤

1. 停止相关能力和 provider egress。
2. 保全 audit hash 链、run/tool/provider request metadata 和部署版本；不复制原始 PII 到工单。
3. 轮换供应商密钥，撤销受影响 token/会话。
4. 确定受影响 source/user/run/citation/proposal 范围。
5. 对知识泄露执行 source suspension、索引删除与对象访问撤销。
6. 用修复后的 policy/prompt/adapter 重跑红队与离线 eval。
7. 获安全负责人批准后分阶段恢复；保留事件和用户通知决策。

## 生产前安全评审事项

### 已完成的应用内控制

- 已落地 14 个权限码、`RecentAuthenticationPolicy`、`ActionAuthorizationPolicy`/object authorization、actor-aware 维修查询、工具 allowlist、双 hash 审批、行锁/CAS、scoped 幂等和同步 Service 执行。
- 已落地字段级 PII 分类/脱敏、纯文本渲染、CSRF/Origin 校验和 SSE no-store。
- 已落地 ActorDescriptor、版本化 audit hash 链、MySQL budget bucket、upload session/固定对象版本、erasure 状态机、feature flag 和安全测试夹具。

### 需要架构/安全评审

- 生产 Secret Manager/KMS、审计 keyring/每日链头外存、恶意文件扫描、parser sandbox 和 erasure proof sink。
- 模型/向量/对象存储的数据地域、网络出口、加密、备份和删除证明。
- 生产 MySQL TLS/最小权限/备份加密、Redis 私网+ACL+TLS+Sa-Token/cache 隔离与恢复演练。
- 生产 HTTPS 下 `Secure` Cookie、可信代理头、Origin/CORS allowlist 与证书终止方式的部署配置和运行验证。

### 已由产品决策关闭

- 每个知识源必须有显式 Owner、分类和 ACL；空 ACL 默认拒绝。Owner 不能自行批准公开，公开范围仍需独立权限。
- 首期 `NOTICE_CREATE_DRAFT` 与 `REPAIR_ASSIGN` 允许同一名具备 AI 权限、业务权限和真实用户身份的人员审批；未来高风险动作默认双人审批。
- 风险类型只允许维修积压、重复报修、入住一致性和长期待办等运营信号，高/中/低 SLA 分别为 2/3/5 天；禁止学生纪律、心理、健康和信用画像。
- 初始限额、预算与分层保留期按 [AI 总计划](./ai-master-plan.md#10-已确认产品决策) 的 PD-04 执行。DeepSeek 正式数据处理地域、留存、训练、删除、子处理方和 DPA 仍是生产发布前法务/安全证据，不是未决产品功能选择。
