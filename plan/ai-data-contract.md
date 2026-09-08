# AI 数据、接口与事件契约

> 状态：已实施合同；截至 2026-07-18 文档复核，`ai-schema.sql` 已创建 44 张 `ai_*` 表，`ai-v1.yaml` 已定义 55 paths / 60 operations。
> 依赖：[AI 总计划](./ai-master-plan.md)、[安全](./ai-security.md)。
> 原则：MySQL 保存权威元数据、审批和审计；向量库与对象存储是可重建的适配层。模型不得接收表名、Mapper、SQL 或数据库连接。

`ai_step_up_failure_window` 已纳入当前控制面，现行合同与真实基础设施验收统一以 44 张 `ai_*` 表为准。

## 兼容约定

- 沿用现有 `BIGINT AUTO_INCREMENT` 主键和 `created_at/updated_at/created_operator_user_id/updated_operator_user_id` 审计字段。
- AI 自有对外资源另加不可枚举的 `public_id CHAR(36) UNIQUE`；REST 不暴露 AI 表自增 ID。现有楼栋、维修、用户等业务 API 仍使用当前数值 ID，AI 入口必须按原合同接收并做对象级授权，不能假设业务表已有 public ID。
- 时间字段使用 `TIMESTAMP(6)`；REST/SSE 输出带时区的 ISO-8601 字符串。
- 需要跨 H2/MySQL 的 JSON payload 使用 `LONGTEXT`，写入前由 Jackson 解析并按 JSON Schema 校验；不能按未索引 JSON 字段做生产筛选。
- 状态使用受服务层约束的 `VARCHAR`；状态迁移集中在领域服务，不允许 Controller/Mapper 直接改值。
- 真正 append-only 的是 approval、citation、usage ledger、audit/event 等事实记录：只插入、不原地改写，按获批保留期归档。
- run、tool、execution、ingestion、outbox、eval 等是可变状态聚合，不提供普通 CRUD 删除或任意 PATCH；所有迁移必须由领域服务使用 `version`/合法前态做条件更新，终态字段只允许写入一次。它们是“不可随意删除的受控状态”，不能误称为 append-only。
- 任何 provider API key、数据库密码、Cookie、Sa-Token、原始手机号或未脱敏密钥都不得进入这些表。
- 对现有业务表的读取/写入仍通过 Service。AI persistence adapter 只能访问 `ai_*` 控制面表。

## 现有业务表的兼容扩展

### `notice`

2026-07-11 的原始非 AI `Notice` / `NoticeRequest` / `notice` 没有正文字段。阶段 4 已完成以下向后兼容迁移，为公告草稿提供纯文本正文：

| 变更 | 定义 | 兼容行为 |
| --- | --- | --- |
| `content MEDIUMTEXT NULL` | 仅保存纯文本首期正文；最大业务长度由产品确认，服务端先设置防滥用上限 | 旧记录为 null，列表和现有页面继续可读；AI 不能据此宣称旧公告存在正文 |

已同步更新：

- `backend/src/main/java/com/example/dormitory/domain/Notice.java`。
- `backend/src/main/java/com/example/dormitory/dto/NoticeRequest.java`。
- `OperationsService.createNotice/updateNotice` 与前端公告 API/表单。
- `SchemaContractTest`、`SchemaMigrationInitializerTest`、`OperationsLifecycleTest`、前端 API 契约与公告 E2E。

首期正文不允许 HTML；如后续采用 Markdown，必须在产品和安全评审后新增格式字段与统一 sanitizer。

## AI 表

### 通用审计列

除下文另有说明，每张可更新表包含：

`id BIGINT PRIMARY KEY AUTO_INCREMENT`、`created_at TIMESTAMP(6)`、`updated_at TIMESTAMP(6)`、`created_operator_user_id BIGINT NULL`、`updated_operator_user_id BIGINT NULL`。

append-only 表只保留 `created_at` 和可信 actor 列，不允许普通更新接口。

所有异步任务和审计事实统一使用 `ActorDescriptor`，避免把“谁发起”误当成“谁执行”：

- `actor_kind`：`USER/SERVICE/MODEL/SYSTEM`；`actor_user_id` 可空，服务任务使用最小权限的 `service_principal_code`。
- `initiated_by_user_id`：可空，只表示最初发起人，不授予后台任务用户权限；`effective_subject_user_id`：可空，只记录任务按谁的数据范围运行。
- USER 要求真实、当前有效的会话；SERVICE/MODEL/SYSTEM 均不得成为首期业务写的 `executed_by_user_id`，不得调用 `StpUtil.login` 冒充用户。
- `ai_ingestion_job`、`ai_outbox_event`、`ai_eval_run`、风险扫描和审计事件都保存上述可适用字段；数据库约束或领域校验保证 actor kind 与 user/service 字段组合合法。

### 配置与治理

| 表 | 关键列 | 约束与索引 |
| --- | --- | --- |
| `ai_model_deployment` | `public_id`、`code`、`provider_code`、`model_name`、`endpoint_alias`、`capabilities_text`、`data_region`、`config_version`、`enabled` | `UNIQUE(code)`；`INDEX(enabled, provider_code)`；只存 endpoint alias，不存密钥 |
| `ai_model_alias` | `alias_code`、`active_deployment_id`、`version`、`activated_by_user_id`、`activated_at` | `UNIQUE(alias_code)`；alias 行锁/CAS 原子切换 deployment；run 固定解析后的 deployment ID |
| `ai_prompt_version` | `prompt_key`、`version`、`content MEDIUMTEXT`、`content_hash CHAR(64)`、`response_schema_version`、`status`、`active_slot_key NULL`、`approved_by_user_id`、`activated_at` | `UNIQUE(prompt_key, version)`、`UNIQUE(active_slot_key)`；ACTIVE 行写 `active_slot_key=prompt_key`，其他为 null；`INDEX(prompt_key, status, activated_at)` |
| `ai_tool_catalog_version` | `version`、`manifest_text LONGTEXT`、`manifest_hash`、`status`、`active_slot_key NULL`、`activated_at` | `UNIQUE(version)`、`UNIQUE(manifest_hash)`、`UNIQUE(active_slot_key)`；全局 ACTIVE 行写固定 slot，run 固定引用版本 |
| `ai_pricing_version` | `provider_code`、`model_name`、`currency`、`input_cost_per_million`、`output_cost_per_million`、`effective_from`、`effective_to`、`source_reference` | `UNIQUE(provider_code, model_name, effective_from)`；`INDEX(effective_from, effective_to)` |
| `ai_quota_policy` | `scope_type`、`scope_key`、`capability`、`daily_token_limit`、`monthly_cost_limit`、`concurrent_run_limit`、`status`、`effective_from` | `UNIQUE(scope_type, scope_key, capability, effective_from)`；`INDEX(status, effective_from)`；所有数值由产品审批 |
| `ai_step_up_grant` | `public_id`、`grant_token_hmac`、`grant_token_key_version`、`session_fingerprint_hash`、`session_fingerprint_key_version`、`actor_user_id`、`action_code`、`resource_public_id`、`request_hash`、`auth_method`、`authenticated_at`、`expires_at`、`state`、`used_at`、`version` | `UNIQUE(grant_token_hmac)`；`INDEX(actor_user_id, state, expires_at)`；proof 单次/短期、绑定 session/actor/action/resource/hash；不存密码或 raw token；keyring 轮换仍可验证未过期 proof |
| `ai_step_up_failure_window` | `actor_user_id`、`window_started_at`、`failure_count`、`version`、`created_at`、`updated_at` | `PRIMARY KEY(actor_user_id)`；`failure_count` 默认 0；`CHECK(failure_count >= 0)`；每个 actor 仅保留一条当前失败窗口，使用 `version` 做并发更新控制 |
| `ai_runtime_switch` | `scope_type`、`scope_key`、`resource_public_id`、`disabled`、`reason_redacted`、`version`、`updated_by_user_id` | `UNIQUE(scope_type, scope_key)`、`UNIQUE(resource_public_id)`；`INDEX(disabled, scope_type)`；MySQL 保存跨实例 Kill Switch 事实，clear 以 `expectedVersion` 做 CAS，运行时只能收窄部署配置 |

`capabilities_text` 和 `manifest_text` 都是经 Schema 校验的 JSON 文本。运行时只使用已激活、hash 匹配的版本。

prompt/tool catalog/model alias 激活必须在 recent-auth 后锁定对应 `prompt_key`/全局 slot/alias 行，原子撤销旧 active、激活新版本并写 audit/outbox；唯一 active slot 是 DB 最后防线。activate/activate 竞态只有一个请求获胜，另一个 409 后重读；回滚也创建一次受审计的原子切换，不能原地改旧内容。

step-up proof 只在创建响应中返回一次，敏感端点通过 `X-Step-Up-Proof` 提交。服务端比较 HMAC 后在同一事务将 grant 从 ACTIVE CAS 为 USED；跨 action/resource/request hash、过期、并发双用或不同 session 均拒绝。logout、kickout、停用、改密时按 session/actor 立即撤销未使用 grant。

step-up 认证失败窗口以 MySQL 中的 `ai_step_up_failure_window` 为跨实例事实源：`actor_user_id` 唯一定位 actor，`window_started_at` 固定当前窗口起点，`failure_count` 只允许非负，`version` 用于并发 CAS。该表不保存密码、factor、proof 或原始会话标识。

### 知识与摄取

| 表 | 关键列 | 约束与索引 |
| --- | --- | --- |
| `ai_knowledge_source` | `public_id`、`name`、`source_type`、`owner_user_id`、`classification`、`permission_match_mode`、`object_store_code`、`acl_version`、`status` | `UNIQUE(public_id)`；`INDEX(status, classification)`；Owner 必须是启用用户；match mode 仅 `ANY/ALL`；新 document version 默认 EXPLICIT_ACL/deny |
| `ai_knowledge_source_permission` | `source_id`、`permission_code` | `UNIQUE(source_id, permission_code)`；`INDEX(permission_code, source_id)`；FK 到 source，只接受已注册权限码且禁止保存 `*` |
| `ai_knowledge_public_approval` | `source_id`、`document_version_id`、`decision`、`approval_policy_version`、`classification_snapshot`、`acl_version`、`content_hash`、`approval_snapshot_hash`、`reviewer_user_id`、`idempotency_record_id`、`created_at` | append-only；`UNIQUE(idempotency_record_id)`；`INDEX(source_id, document_version_id, created_at)`；只批准固定 READY version/snapshot |
| `ai_upload_session` | `public_id`、`source_id`、`owner_user_id`、`quarantine_object_key`、`object_version_id`、`object_etag`、`expected_sha256`、`observed_sha256`、`expected_size_bytes`、`observed_size_bytes`、`declared_mime_type`、`detected_mime_type`、`scan_state`、`state`、`expires_at`、`write_revoked_at`、`finalized_document_version_id`、`version` | `UNIQUE(public_id)`、`UNIQUE(quarantine_object_key)`；`INDEX(owner_user_id, state, expires_at)`；key 仅由服务端生成且不通过业务 API 返回；固定 object version/ETag 后才可扫描 |
| `ai_document` | `public_id`、`source_id`、`external_key_hmac`、`external_key_key_version`、`title`、`current_version_id`、`status` | `UNIQUE(source_id, external_key_hmac)`；`INDEX(source_id, status)`；低熵文件名/外部 ID 使用用途域分离 HMAC，不使用裸 hash |
| `ai_document_version` | `public_id`、`document_id`、`version`、`content_hash`、`visibility`、`active_public_approval_id NULL`、`object_key`、`object_version_id`、`object_etag`、`mime_type`、`size_bytes`、`parser_version`、`chunk_policy_version`、`status`、`activated_at`、`retired_at` | `UNIQUE(document_id, version)`、`UNIQUE(document_id, content_hash)`；`INDEX(status, visibility, activated_at)`；visibility 仅 `EXPLICIT_ACL/PUBLIC_APPROVED`；parser/embedding 只读取固定 object version/ETag |
| `ai_document_chunk` | `public_id`、`document_version_id`、`chunk_no`、`content_redacted MEDIUMTEXT`、`content_hash`、`locator_text LONGTEXT`、`metadata_text LONGTEXT`、`vector_ref`、`status` | `UNIQUE(document_version_id, chunk_no)`；`INDEX(document_version_id, status)`；`INDEX(content_hash)` |
| `ai_ingestion_job` | `public_id`、`document_version_id`、`state`、`attempt`、`worker_id`、`service_principal_code`、`initiated_by_user_id`、`version`、`available_at`、`started_at`、`finished_at`、`error_code`、`error_summary` | `UNIQUE(document_version_id, attempt)`；worker 索引 `(state, available_at, id)`；`INDEX(worker_id, state)`；只按合法前态 CAS |

规则：

- `external_key_hmac` 是来源内稳定标识，使用环境密钥、用途域和 `external_key_key_version` 计算，不能由低熵原文件名/外部 ID 直接做裸 SHA-256。
- ACL 默认拒绝：每个新 document version 固定创建为 `EXPLICIT_ACL`；source 的空 permission 集合不可读，`ANY/ALL` 分别表示满足任一/全部已登记权限码。Owner 只天然拥有管理资格，不天然获得正文读取权，也不能自行把 version 改为公开。公开内容必须由该 version 显式 `PUBLIC_APPROVED`、source 分类为 L0，并由持有 `ai:knowledge:publish-public` 的非 Owner 治理人员在 recent-auth 后批准固定 READY version 的 content/classification/ACL snapshot，不能用空 ACL 表示公开。ACL 表禁止存 `*`，但服务端现有 RBAC 的真实超级权限可在逐项权限校验时满足所需 permission code。
- public approval 与单个 `document_version_id + content_hash + classification + acl_version + policy version` 绑定；document 内容、source 分类/ACL 或 policy 任一变化，必须在同一事务清空受影响 version 的 `active_public_approval_id`、退回 EXPLICIT_ACL/deny 并清缓存。并发 public approve/activate/ACL change 共享 source/version 行锁或 CAS，旧批准不能套用到新内容。同一 source 多文档时，只有自身 version 有有效 active approval 的 chunk 可按 public 检索，批准 A 绝不能公开 B/C。
- ACL 变更必须验证权限码存在、操作者有权授予该范围，并原子递增 `acl_version`。检索前置与后置过滤必须共享同一 `visibility + match_mode + acl_version` 语义。
- `object_key` 采用 content-addressed key；MySQL 不保存 provider 的签名 URL。客户端不能提交原始 `object_key`、bucket、URL 或“实测 checksum”；上传必须先绑定服务端 `upload session + owner + source + expected checksum`，且 upload target 只允许对随机隔离 key 做一次条件 PUT。
- finalize 立即撤销/过期写 target，由服务端自行读取并记录 observed size/checksum、object versionId/ETag；扫描、parser 和 embedding 都按固定 version/ETag 读取并再次核对 checksum。扫描后对象被覆盖、版本切换、过期 presign 或重复 finalize 一律拒绝/隔离；只有 `SCANNED_CLEAN` 的固定对象版本才能创建 document version。
- `content_redacted` 仅保存允许引用的脱敏片段；若场景要求保留受保护原文，原文放在对象存储并使用独立访问审计。
- 生产外部向量适配器启用前，metadata 至少带 source public ID、document version public ID、chunk public ID、classification、version visibility、active public approval snapshot hash、permission digest 和 content hash；public filter 必须以命中的 chunk/version 为粒度。
- 当前 source permission 变化原子递增 `acl_version`，检索每次前后使用 MySQL 当前 ACL；当前无独立 retrieval cache，内存向量仅保存版本/source/hash。外部向量适配器启用前必须补 permission digest cache 失效和 metadata refresh；该外部投影扩展暂不实施，不能据内存索引存在宣称 PR-02 完成。
- 激活新版本时，在一个 MySQL 事务中更新 `document.current_version_id`、版本状态并写 outbox；旧版本进入 RETIRED。

### 会话、运行、流与引用

| 表 | 关键列 | 约束与索引 |
| --- | --- | --- |
| `ai_conversation` | `public_id`、`owner_user_id`、`surface`、`context_type`、`context_resource_id BIGINT NULL`、`status`、`title_redacted`、`last_message_at` | `UNIQUE(public_id)`；`INDEX(owner_user_id, status, last_message_at)`；业务上下文 ID 沿用现有数值 ID，并按 context type 重取/鉴权 |
| `ai_message` | `public_id`、`conversation_id`、`sequence_no`、`role`、`client_request_id NULL`、`request_hash NULL`、`content_redacted MEDIUMTEXT`、`raw_object_key NULL`、`classification`、`parent_message_id` | `UNIQUE(conversation_id, sequence_no)`、`UNIQUE(conversation_id, client_request_id)`；`INDEX(conversation_id, created_at)`；client fields 仅用户消息必填，raw key 默认 null |
| `ai_run` | `public_id`、`parent_run_id`、`conversation_id`、`request_message_id`、`capability`、`state`、`version`、`actor_user_id`、`session_fingerprint_hash`、`session_fingerprint_key_version`、`permission_digest`、`model_deployment_id`、`prompt_version_id`、`tool_catalog_version_id`、`retrieval_policy_version`、`redaction_policy_version`、`quota_policy_id`、`reserved_tokens`、`reserved_cost`、`correlation_id`、`started_at`、`first_token_at`、`finished_at`、`input_tokens`、`output_tokens`、`estimated_cost`、`cost_status`、`failure_code`、`degrade_mode` | `UNIQUE(public_id)`、`UNIQUE(correlation_id)`、`UNIQUE(request_message_id)`；`INDEX(actor_user_id, state, created_at)`；`INDEX(capability, state, created_at)`；`INDEX(model_deployment_id, created_at)`；fingerprint 保存用途域 HMAC 与 key version，raw token 不入库；状态更新带 version/合法前态；MySQL 事务预留/对账是配额权威，Redis 只可加速 burst limit |
| `ai_run_event` | `run_id`、`sequence_no`、`event_type`、`payload_redacted LONGTEXT`、`created_at` | append-only；`UNIQUE(run_id, sequence_no)`；`INDEX(run_id, created_at)`；token delta 批量写 |
| `ai_tool_call` | `public_id`、`run_id`、`sequence_no`、`tool_name`、`tool_version`、`request_redacted LONGTEXT`、`response_redacted LONGTEXT`、`required_permissions_text`、`authorization_decision`、`state`、`version`、`started_at`、`finished_at`、`error_code` | `UNIQUE(run_id, sequence_no)`；`INDEX(tool_name, state, created_at)`；拒绝调用也写记录；状态更新带 version/合法前态 |
| `ai_retrieval_trace` | `public_id`、`run_id`、`query_hash`、`index_code`、`index_version`、`filter_redacted`、`top_k`、`returned_count`、`latency_ms`、`state` | `INDEX(run_id)`；`INDEX(index_code, created_at)`；不保存原始 embedding |
| `ai_citation` | `public_id`、`run_id`、`message_id`、`citation_type`、`document_version_id`、`chunk_id`、`metric_id`、`rank_no`、`score`、`quote_redacted`、`locator_text`、`content_hash` | `UNIQUE(run_id, public_id)`；`INDEX(message_id, rank_no)`；知识 citation 用 document/chunk，Dashboard citation 用 metric_id |

`ai_run_event` 的 payload 只能是客户端需要的脱敏数据。完整内部异常栈保留在受控服务日志，不进入 SSE 或数据库正文。message delta 在送达浏览器前写入批次事件；若持久化/审计失败，不发送该批次并终止 run，避免出现不可审计输出。

`ai_run` 的 `permission_digest` 是审计快照而不是持续授权凭证。raw Sa-Token 不入库；每次后台工具调用通过内存 run context 的 token 和当前 userId 重新验证会话、账号启用与权限。进程重启后不得仅靠 digest 恢复未完成工具调用。

`clientRequestId` 的幂等范围是 conversation。相同 key + 相同 `request_hash` 返回原 message/run；相同 key + 不同正文或上下文返回 409 `AI_IDEMPOTENCY_PAYLOAD_MISMATCH`，不能创建第二条消息或再次预留预算。

### 提案、审批与业务执行

| 表 | 关键列 | 约束与索引 |
| --- | --- | --- |
| `ai_action_proposal` | `public_id`、`run_id`、`origin_tool_call_id`、`action_type`、`target_type`、`target_resource_id BIGINT NULL`、`payload_text LONGTEXT`、`preview_text LONGTEXT`、`payload_hash`、`business_snapshot_hash`、`required_business_permission`、`approval_policy_version`、`required_approval_count`、`approved_count`、`risk_level`、`state`、`proposer_user_id`、`expires_at`、`version` | `UNIQUE(public_id)`、`UNIQUE(origin_tool_call_id)`；`INDEX(state, expires_at)`；`INDEX(action_type, created_at)`；proposal dedup 由服务端 tool call 身份决定，不接受客户端全局 key；审批事务必须行锁或 version CAS；target ID 沿用现有业务表 ID，不做跨类型 FK |
| `ai_action_approval` | `proposal_id`、`proposal_version`、`decision`、`reviewer_user_id`、`idempotency_record_id`、`payload_hash`、`business_snapshot_hash`、`comment_redacted`、`created_at` | append-only；`UNIQUE(proposal_id, proposal_version, reviewer_user_id)`、`UNIQUE(idempotency_record_id)`；`INDEX(reviewer_user_id, created_at)` |
| `ai_idempotency_record` | `actor_user_id`、`route_code`、`aggregate_public_id`、`idempotency_key`、`request_hash`、`state`、`response_status`、`response_resource_public_id`、`expires_at`、`version` | `UNIQUE(actor_user_id, route_code, aggregate_public_id, idempotency_key)`；`INDEX(state, expires_at)`；相同 key 不同 request hash 必须 409 |
| `ai_action_execution` | `public_id`、`proposal_id`、`state`、`version`、`handler_name`、`execution_key`、`lease_token_hash`、`executed_by_user_id NOT NULL`、`reconfirmed_by_user_id NULL`、`result_resource_type`、`result_resource_id BIGINT NULL`、`response_redacted LONGTEXT`、`started_at`、`finished_at`、`error_code`、`error_summary` | 每个 proposal 首期只允许一次业务执行/lease：`UNIQUE(proposal_id)`、`UNIQUE(execution_key)`；execution key 由服务端派生，不信任客户端；`INDEX(state, started_at)`；状态更新带 version/合法前态；普通失败重试必须新 proposal |

`payload_text` 是固定 action schema，不允许出现 Java 类名、SQL、URL、method 名或任意 tool 名。首期 action schema 只有 `NOTICE_CREATE_DRAFT.v1` 和 `REPAIR_ASSIGN.v1`。

审批 `Idempotency-Key` 不能只靠 approval 表的全局唯一键解释语义。服务端先以 `actor + route + proposal + key` 建立/锁定 `ai_idempotency_record`，保存 canonical request hash；同 key 同 payload 返回原响应引用，同 key 异 payload 返回 409。`APPROVE` 与 `REJECT` 使用不同 `route_code`，但仍受同一 proposal 行锁串行化。

### 风险、反馈、成本与评测

| 表 | 关键列 | 约束与索引 |
| --- | --- | --- |
| `ai_risk_scan` | `public_id`、`requested_by_user_id`、`actor_kind`、`service_principal_code`、`initiated_by_user_id`、`effective_subject_user_id`、`requested_role_codes_text`、`requested_permission_codes_text`、`requested_scope_text`、`permission_digest`、`state`、`provider_versions_text`、`unavailable_providers_text`、`signal_count`、`case_count`、`duplicate_count`、`error_code`、`version`、`started_at`、`finished_at` | `UNIQUE(public_id)`；`INDEX(requested_by_user_id, created_at)`；固定请求时的角色、权限、scope 与 digest，后台仅按该快照和最小 SERVICE actor 执行，状态资源仍按发起人范围读取 |
| `ai_risk_case` | `public_id`、`dedup_key`、`active_dedup_key NULL`、`risk_type`、`subject_type`、`subject_resource_id BIGINT NULL`、`subject_token`、`subject_token_key_version`、`severity`、`state`、`signal_policy_version`、`signal_snapshot_redacted LONGTEXT`、`explanation_run_id`、`assignee_user_id`、`opened_at`、`due_at`、`resolved_at`、`version` | `UNIQUE(active_dedup_key)`；活动态写 dedup key，RESOLVED/DISMISSED 置 null，从而允许以后 re-open；`INDEX(dedup_key, opened_at)`；`INDEX(state, severity, opened_at)`；`INDEX(subject_type, subject_resource_id)`；真实 subject ID 仅供授权应用重取，模型只收到 token |
| `ai_risk_case_event` | `case_id`、`sequence_no`、`event_type`、`actor_kind`、`actor_user_id NULL`、`service_principal_code NULL`、`initiated_by_user_id NULL`、`idempotency_record_id NULL`、`case_version`、`detail_redacted LONGTEXT`、`created_at` | append-only；`UNIQUE(case_id, sequence_no)`、`UNIQUE(idempotency_record_id)`；人工动作必须 USER 且关联 scoped idempotency，规则/模型事件保留真实 actor kind |
| `ai_feedback` | `run_id`、`message_id`、`user_id`、`rating`、`tags_text`、`comment_redacted`、`created_at` | `UNIQUE(message_id, user_id)`；`INDEX(rating, created_at)` |
| `ai_budget_bucket` | `quota_policy_id`、`scope_type`、`scope_key`、`capability`、`provider_code`、`period_type`、`period_start`、`period_end`、`token_limit`、`cost_limit`、`reserved_tokens`、`committed_tokens`、`reserved_cost`、`committed_cost`、`currency`、`version` | `UNIQUE(quota_policy_id, scope_type, scope_key, capability, provider_code, period_start)`；`INDEX(period_end)`；MySQL 权威条件更新，数值均非负 |
| `ai_budget_reservation` | `public_id`、`billing_subject_kind`、`billing_subject_public_id`、`budget_bucket_id`、`reserved_tokens`、`reserved_cost`、`committed_tokens`、`committed_cost`、`state`、`expires_at`、`version` | subject kind 仅 `RUN/EVAL/INGESTION`；`UNIQUE(billing_subject_kind, billing_subject_public_id, budget_bucket_id)`；`INDEX(state, expires_at)`；`RESERVED/COMMITTED/RELEASED/EXPIRED` 受 CAS 约束 |
| `ai_provider_attempt` | `public_id`、billing subject/sequence/attempt identity、`request_kind`、actor/initiator/effective subject、`capability`、`provider_code`、`model_name`、固定 `pricing_version_id` 与单价、`estimation_policy_version`、`state`、`owner_instance_id`、`lease_expires_at`、token/cost/usage/outcome/duration/failure、`started_at`、`finished_at`、`reconciliation_marked_at`、`version` | `UNIQUE(public_id)`、`UNIQUE(billing_subject_kind, billing_subject_public_id, request_sequence_no, attempt_no)`；`INDEX(state, started_at, id)`、`INDEX(state, lease_expires_at, id)`、subject/state 索引；每个物理请求先固定价格并持有 lease，崩溃恢复只对未知 attempt 做对账，不重复调用 provider |
| `ai_usage_ledger` | `billing_subject_kind`、`billing_subject_public_id`、`request_sequence_no`、`attempt_no`、`request_kind`、`actor_kind`、`actor_user_id NULL`、`service_principal_code NULL`、`initiated_by_user_id NULL`、`capability`、`provider_code`、`model_name`、`provider_request_id_hash NULL`、`pricing_version_id`、`input_tokens`、`output_tokens`、`cost_amount`、`currency`、`usage_source`、`occurred_at` | append-only；subject kind 仅 `RUN/EVAL/INGESTION` 且恰有一个 subject；每次物理 provider 请求/重试/fallback 一行；`UNIQUE(billing_subject_kind, billing_subject_public_id, request_sequence_no, attempt_no)`；`INDEX(actor_user_id, occurred_at)`；`INDEX(capability, occurred_at)` |
| `ai_audit_chain_head` | `chain_scope`、`aggregate_type`、`aggregate_public_id`、`last_sequence_no`、`last_event_hash`、`integrity_key_version`、`version` | `UNIQUE(chain_scope, aggregate_type, aggregate_public_id)`；按行锁或 CAS 原子分配序号/previous hash |
| `ai_audit_event` | `public_id`、`chain_scope`、`aggregate_type`、`aggregate_public_id`、`sequence_no`、`event_type`、`actor_kind`、`actor_user_id NULL`、`service_principal_code NULL`、`initiated_by_user_id NULL`、`effective_subject_user_id NULL`、`session_fingerprint_hash`、`session_fingerprint_key_version`、`permission_digest`、`payload_redacted_hash`、`previous_event_hash`、`event_hash`、`integrity_alg`、`integrity_key_version`、`canonicalization_version`、`correlation_id`、`occurred_at` | append-only；`UNIQUE(public_id)`、`UNIQUE(chain_scope, aggregate_type, aggregate_public_id, sequence_no)`；`INDEX(actor_user_id, occurred_at)`；`INDEX(service_principal_code, occurred_at)`；`INDEX(correlation_id)` |
| `ai_audit_anchor` | `anchor_date`、`chain_scope`、`root_hash`、`event_count`、`integrity_alg`、`integrity_key_version`、`canonicalization_version`、`external_sink_code`、`external_receipt_hash`、`state`、`anchored_at` | append-only；`UNIQUE(anchor_date, chain_scope)`；外部 receipt 不含正文 |
| `ai_eval_run` | `public_id`、`suite_name`、`dataset_version`、`prompt_version_id`、`model_deployment_id`、`code_revision`、`state`、`version`、`service_principal_code`、`initiated_by_user_id`、`started_at`、`finished_at`、`summary_text` | `UNIQUE(public_id)`；`INDEX(suite_name, started_at)`；只按合法前态 CAS |
| `ai_eval_result` | `eval_run_id`、`case_key`、`capability`、`state`、`metrics_text`、`failure_tags_text`、`artifact_path` | `UNIQUE(eval_run_id, case_key)`；`INDEX(capability, state)` |
| `ai_outbox_event` | `public_id`、`aggregate_type`、`aggregate_public_id`、`event_type`、`payload_redacted LONGTEXT`、`state`、`version`、`attempts`、`available_at`、`locked_by`、`service_principal_code`、`initiated_by_user_id`、`locked_at`、`published_at`、`last_error_code` | `UNIQUE(public_id)`；worker 索引 `(state, available_at, id)`；`INDEX(aggregate_type, aggregate_public_id)`；只按合法前态 CAS |

outbox 用于知识摄取、索引更新、离线评测、指标派生和成功后的通知事件。**首期业务写执行不由 outbox 代替人工会话授权**；审批请求在当前 Sa-Token 上下文中同步调用 allowlisted handler，保证现有 Service 的 `StpUtil.checkPermission` 仍生效。

预算预留必须在创建/排队 billing subject 的同一 MySQL 事务中完成：在线 run 使用 user/role/capability/provider bucket；真实 eval 与收费 ingestion 使用独立 service/eval/ingestion budget，同时保留 initiated-by。按稳定顺序锁定所有适用 bucket，或执行带 `reserved + committed + requested <= limit` 条件的版本更新；任一 bucket 不足则整体回滚并返回 429/拒绝排队，不能先调用 provider。完成、取消和超时都按每个物理 provider request 的 ledger 对账并释放剩余 reservation；tool loop、429 重试和 fallback 不能合并成一条 usage。Redis 只承担 burst/concurrency 加速，不能成为余额事实源。

审计写入先锁定/更新 `ai_audit_chain_head` 分配唯一 sequence 和 `previous_event_hash`，再以固定 canonicalization 和指定 HMAC key version 生成事件。密钥轮换从新事件开始使用新 version，并通过 previous hash 连到旧链；验证器必须按事件的 `integrity_alg + key_version + canonicalization_version` 重放。每日将链头/Merkle root 外锚到独立只追加介质并保存 receipt hash；外锚失败触发告警，业务写执行按安全策略 fail closed。

### 生命周期与可验证清除

| 表 | 关键列 | 约束与索引 |
| --- | --- | --- |
| `ai_erasure_job` | `public_id`、`request_type`、`scope_type`、`scope_public_id`、`requested_by_user_id`、`legal_basis_code`、`retention_hold`、`state`、`version`、`attempts`、`available_at`、`finished_at`、`error_code` | `UNIQUE(public_id)`；`INDEX(state, available_at)`；owner 请求仍受保留/法务策略裁剪 |
| `ai_erasure_target` | `erasure_job_id`、`target_kind`、`target_ref_hash`、`provider_code NULL`、`state`、`attempts`、`proof_ref_hash NULL`、`last_checked_at`、`last_error_code` | `UNIQUE(erasure_job_id, target_kind, target_ref_hash)`；`INDEX(state, last_checked_at)`；不保存签名 URL/原 provider payload |

清除流程固定为：conversation 先 `ARCHIVED` 并禁止新 run → 计算保留例外 → 生成 MySQL 正文、raw object、vector、cache、获批 provider retention API 等 target → 可重试清除/验证 → 写 `ERASURE_TOMBSTONE` 审计事件与 hash-chain checkpoint → 全部可清除 target 有 proof 后完成。审计、审批、业务事实和法定保留项不因 owner 删除而消失；只清空允许删除的正文并保留不可逆内容 hash、法定最小元数据和外锚。无法提供供应商删除能力或完成证据时 job 保持 `PARTIAL/NEEDS_REVIEW`，不能声称已删除。

## 状态枚举

本表自 2026-09-08 按实际数据库与服务迁移拆分。兼容枚举不代表存在创建或取消入口；未列出的任意状态转换仍拒绝。审计成本通过独立 `costStatus` 字段筛选，旧 `state=NEEDS_RECONCILIATION` 请求仅作兼容映射，不改变 run.state。

| 聚合 | 合法状态 |
| --- | --- |
| model deployment / alias | deployment 使用 enabled；alias 绑定 deployment，不共用状态枚举 |
| prompt / tool catalog | DRAFT、ACTIVE、INACTIVE、RETIRED（仅允许已实现的治理迁移） |
| knowledge source | ACTIVE、PAUSED |
| document | 文档身份与 current_version_id；生命周期主要由 version 管理 |
| document version | PENDING、READY、ACTIVE、RETIRED、QUARANTINED |
| upload session | CREATED、UPLOADING、UPLOADED、SCANNING、SCANNED_CLEAN、QUARANTINED、FINALIZED、EXPIRED |
| ingestion job | QUEUED、RUNNING、SUCCEEDED、FAILED、DEAD |
| outbox | PENDING、PROCESSING、SUCCEEDED、RETRYABLE_FAILED、DEAD |
| conversation | ACTIVE、ARCHIVED |
| run | ACCEPTED、QUEUED、RUNNING、STREAMING、SUCCEEDED、FAILED、TIMED_OUT、CANCELLED；DEGRADED 为兼容预留值，当前用结果中的降级标识表示 |
| run cost_status | RESERVED、ESTIMATED、FINAL、RELEASED、UNKNOWN、NEEDS_RECONCILIATION；与 run 生命周期分离 |
| tool call | REQUESTED、DENIED、RUNNING、SUCCEEDED、FAILED、TIMED_OUT |
| proposal | PENDING_APPROVAL、APPROVED、EXECUTING、NEEDS_REVIEW、SUCCEEDED、REJECTED、EXPIRED、STALE、FAILED；DRAFT/CANCELLED 保留枚举兼容，不提供任意创建/取消迁移 |
| approval | APPROVE、REJECT |
| execution | EXECUTING、SUCCEEDED、FAILED、NEEDS_REVIEW（只有合法前态迁移；不使用 PENDING/RUNNING 代替真实值） |
| idempotency | PENDING、COMPLETED、FAILED、EXPIRED |
| step-up grant | ACTIVE、USED、EXPIRED、REVOKED |
| budget reservation | RESERVED、COMMITTED、RELEASED、EXPIRED |
| provider attempt | state：STARTED、FINISHED、NEEDS_RECONCILIATION；outcome：SUCCEEDED、FAILED_RETRYABLE、FAILED_FATAL、TIMED_OUT、CANCELLED、UNKNOWN |
| risk scan | QUEUED、RUNNING、SUCCEEDED、PARTIAL、FAILED、NEEDS_REVIEW |
| risk case | OPEN、ACKNOWLEDGED、RESOLVED、DISMISSED |
| eval | QUEUED、RUNNING、PASSED、FAILED、CANCELLED |
| erasure job | PENDING、PROCESSING、SUCCEEDED、PARTIAL、RETRYABLE_FAILED、NEEDS_REVIEW |
| erasure target | PENDING、VERIFIED、RETRYABLE_FAILED、NEEDS_REVIEW、RETAINED |

状态机以 [AI 总计划](./ai-master-plan.md#7-状态机) 为准；表中的状态集合不能被自由组合。

execution 恢复仅允许 `NEEDS_REVIEW → EXECUTING/SUCCEEDED/FAILED`：EXECUTING 要求 reconfirm 已证明未执行且快照未变，SUCCEEDED 只用于对账已存在的原子业务结果，FAILED 用于冲突/明确不可恢复；任何不确定状态继续 NEEDS_REVIEW。所有分支复用原 row 和递增 version。

## REST API

以下清单与当前 `openapi/ai-v1.yaml` 对齐，共 55 个 path、60 个 operation；文档中的权限和状态说明不能替代 OpenAPI DTO 校验或 Controller 对象级授权。

### 公共约定

- 基路径 `/api/ai`；全部 AI 端点都需 Sa-Token 登录。公共 liveness 继续复用现有 `/api/health`，只返回 200/应用状态，不新增匿名 `/api/ai/**` 排除项，也不暴露 provider/model/endpoint/region/依赖错误。
- 为兼容当前内部 API，首期不在 URL 中插入 `v1`；action、prompt、tool、metric、SSE event 和 DTO Schema 都显式带版本。若未来出现破坏性 HTTP 合同变化，再引入 `/api/ai/v2` 并保留迁移窗口。
- JSON 接口继续返回 `ApiResponse<T>`，分页继续使用 `PageResponse<T>`。
- 资源创建 201 并返回 `Location`；异步 run/ingestion/eval 创建 202 并返回状态资源 `Location`；成功删除或取消无正文时 204。
- 400 表示请求格式/校验失败；401 未登录；403 无权限/工具拒绝；404 不存在或不可见；409 状态/快照/幂等冲突；422 表示结构合法但无法映射为受支持指标或动作；429 配额；503 供应商或审计控制面不可用。
- AI 专用错误以 `ApiResponse<AiErrorDetail>` 返回，`data.errorCode` 使用稳定字符串，例如 `AI_PROPOSAL_STALE`、`AI_QUOTA_EXCEEDED`、`AI_NO_GROUNDED_ANSWER`。不修改现有公共 envelope。
- `AiErrorDetail` 可包含经过 allowlist 的 field errors、retryable、runId 和 safe metadata，不包含异常类、SQL、Stack Trace 或供应商原文。
- 所有列表沿用管理端 offset pagination：`page`、`pageSize`；服务端设最大页大小和固定排序白名单，不接受客户端任意字段/表达式。
- 正常和拒绝响应返回用户/能力级限流信息；429 和可安全重试的 503 返回 `Retry-After`。header 数值不得暴露全局供应商配额。
- 除无敏感正文的公共 `/api/health` 外，所有认证 AI JSON/SSE/CSRF 响应返回 `Cache-Control: private, no-store`，并按鉴权方式设置 `Vary: Authorization, Cookie, Origin`；代理/CDN/Service Worker 不得共享缓存 conversation、citation、proposal 或 audit content。
- 所有返回 202 的 command（消息/run、摄取/finalize、Dashboard、维修分诊、公告草稿、风险扫描、eval、erasure）都要求 `Idempotency-Key` 或合同明确的 `clientRequestId`，并在调用 provider/排队前写 `ai_idempotency_record(actor + route + aggregate/resource + key + canonical request hash)`。同 payload 重试返回原 status/Location 且 provider/job 只创建一次；同 key 异 payload 返回 409。昂贵 201 upload-session 创建也遵循同一规则。
- 所有带 `{id}` 的详情、修改、action 端点都执行与列表相同的对象范围校验；不可见资源统一 404，不以 UUID 难猜或前端隐藏作为授权。

### 会话与运行

| 方法与路径 | 输入/输出 | 权限与规则 |
| --- | --- | --- |
| `POST /api/ai/conversations` | 输入 surface、可选 contextType/contextId；返回 201 conversation | `ai:assistant:use`；业务 ID 沿用现有数值合同，服务端重取并做对象授权 |
| `GET /api/ai/conversations` | 当前用户会话分页 | 只返回 owner 的会话；审计权限另走 audit API |
| `GET /api/ai/conversations/{id}` | 会话和脱敏消息 | 仅 owner；审计人员不能通过普通会话端点读取正文 |
| `POST /api/ai/conversations/{id}/messages` | 输入 text、clientRequestId；返回 202 runId/eventsUrl | conversation 内幂等；同 key 同 request hash 返回原 run，同 key 异 payload 409；检查能力开关、配额、PII |
| `POST /api/ai/conversations/{id}/archive` | 无正文，返回 204 | 仅 owner；阻止新 run，不删除业务/审计事实 |
| `DELETE /api/ai/conversations/{id}` | 返回 202 erasureJobId/Location | 仅 owner；要求 `Idempotency-Key`，先归档，再按保留策略异步清除允许删除的正文 |
| `GET /api/ai/erasure-jobs/{id}` | target 状态与安全失败码 | owner 仅看自己的请求；治理视图另需 `ai:audit:read` 且不回放正文 |
| `GET /api/ai/runs/{id}` | run 状态、版本、父 run lineage 和安全结果元数据 | 仅 owner；与会话对象范围复用同一授权，不返回 provider 原始错误 |
| `POST /api/ai/runs/{id}/cancel` | 无正文，返回 204 | owner；终态 run 幂等返回 204 |
| `POST /api/ai/runs/{id}/retry` | 返回 202 新 run/Location | owner + `Idempotency-Key`；仅终态 ASSISTANT run，可复用原脱敏输入与 request hash，但重新获取当前权限、上下文和激活配置，并以 `parentRunId` 保留 lineage |
| `GET /api/ai/runs/{id}/events` | SSE | 仅 owner 的活会话；审计人员不能旁路订阅 live stream |
| `POST /api/ai/messages/{id}/feedback` | rating、tags、comment | 消息可见者；一人一条可更新反馈，保留审计事件 |

### 知识

| 方法与路径 | 输入/输出 | 权限与规则 |
| --- | --- | --- |
| `GET /api/ai/knowledge/sources` | ACL 可见 source 列表 | `ai:knowledge:read`；manage 视图仍按 Owner/ADMIN 裁剪 |
| `POST /api/ai/knowledge/sources` | 创建 source | `ai:knowledge:manage`；非 ADMIN 只能把自己设为 Owner；空 ACL 默认 deny；后续 version 固定从 EXPLICIT_ACL 开始，创建请求不能自报 PUBLIC_APPROVED |
| `GET /api/ai/knowledge/sources/{id}` | source、当前版本和 ACL 摘要 | `ai:knowledge:read`；每次读取按当前 ACL/Owner/ADMIN 范围裁剪，不可见统一 404 |
| `PATCH /api/ai/knowledge/sources/{id}` | 修改非不可变元数据或暂停 | `ai:knowledge:manage` + source Owner 或 ADMIN；不能直接设置 version PUBLIC_APPROVED；分类/ACL 变化原子撤销该 source 各 version 的旧 public approval |
| `POST /api/ai/knowledge/sources/{id}/uploads` | 声明 MIME、size、sha256；返回 201 uploadSession 与短期 opaque upload target | `ai:knowledge:manage` + source Owner 或 ADMIN + `Idempotency-Key`；session 绑定 owner/source/expiry，只写隔离区 |
| `PUT /api/ai/knowledge/uploads/{id}/content` | `text/plain` 流式内容，成功 204 | upload session owner；最大 20 MiB，按 session-random key 条件写隔离区，不接受客户端 object key/URL，超限 413、类型不符 415 |
| `POST /api/ai/knowledge/uploads/{id}/finalize` | 无客户端 observed metadata；返回 202 scan/job 状态 | session owner + source Owner 或 ADMIN + `Idempotency-Key`；立即撤销写 target，由服务端计算 size/checksum 并固定 versionId/ETag；重复 finalize 返回原 job/Location |
| `POST /api/ai/knowledge/sources/{id}/versions` | `uploadSessionId`，或由服务端代建 session 的 multipart；202 ingestion job | `ai:knowledge:manage` + source Owner 或 ADMIN + `Idempotency-Key`；拒绝客户端 `objectKey`/bucket/URL；session 必须同 owner/source、未过期、checksum 一致且 `SCANNED_CLEAN` |
| `GET /api/ai/knowledge/versions/{id}` | version 状态、hash、ACL/public approval 摘要 | `ai:knowledge:read` 且重新检查所属 source；不返回隔离 object key 或未授权正文 |
| `GET /api/ai/knowledge/jobs/{id}` | 摄取状态、失败码和统计 | `ai:knowledge:manage` + 对应 source Owner 或 ADMIN |
| `POST /api/ai/knowledge/versions/{id}/approve-public` | version/content/snapshot hash；204 | `ai:knowledge:publish-public` + 非 Owner 治理策略 + `X-Step-Up-Proof` + `Idempotency-Key`；固定 READY version，不能直接激活其他内容 |
| `POST /api/ai/knowledge/versions/{id}/activate` | 204 | `ai:knowledge:manage` + 对应 source Owner 或 ADMIN；仅 READY，原子切换 current version；PUBLIC 还要求同 version/snapshot 的有效治理批准 |
| `POST /api/ai/knowledge/versions/{id}/retire` | 204 | `ai:knowledge:manage` + 对应 source Owner 或 ADMIN；不能删除历史 citation |
| `POST /api/ai/knowledge/versions/{id}/rollback` | 204 | `ai:knowledge:manage` + 对应 source Owner 或 ADMIN；只允许回滚到同 source 的 READY 版本，原子更新 current pointer 并保留历史引用 |
| `GET /api/ai/citations/{id}` | 定位信息与当前可访问的脱敏引用 | 重新检查 source ACL 和当前权限 |

### 能力端点

| 方法与路径 | 输出 | 权限 |
| --- | --- | --- |
| `POST /api/ai/dashboard/queries` | 202 run | `Idempotency-Key`；`ai:dashboard:query` + 每个 metric 的原业务 read 权限 |
| `POST /api/ai/knowledge/queries` | 202 run，返回引用或安全拒答 | `Idempotency-Key`；`ai:knowledge:read`；`topK` 仅 1–20，检索前后均重查 source ACL，不能用模型回答替代 citation |
| `POST /api/ai/repairs/{repairOrderId}/triage` | 202 run，可产生 assignment proposal | `Idempotency-Key`；`ai:repair:triage` + `repair:read`；REPAIRER 仅限当前 assignee |
| `POST /api/ai/notices/drafts` | 202 run，可产生 notice draft proposal | `Idempotency-Key`；`ai:notice:draft` + `notice:read` |
| `GET /api/ai/risk-cases` | 风险分页 | `ai:risk:read`；按底层数据权限裁剪 |
| `GET /api/ai/risk-cases/{id}` | 规则证据、AI 解释、人工事件 | 同上，字段级再裁剪 |
| `POST /api/ai/risk-scans` | 202 scan run | `Idempotency-Key`；`ai:risk:manage`；只扫描 actor 可读底层范围且只运行已激活规则 |
| `GET /api/ai/risk-scans/{id}` | actor-scoped 扫描状态、provider 版本/不可用项和计数 | 发起人范围 + `ai:risk:manage`；不可见统一 404，不返回底层未授权案例 |
| `POST /api/ai/risk-cases/{id}/{action}` | `action=acknowledge/resolve/dismiss`；caseVersion 与对应 conclusion/reason/comment，成功 204 | `Idempotency-Key`；`ai:risk:manage` + case 底层数据范围；同一 case/version 行锁或 CAS，人工动作 append-only，非法前态 409 |

风险人工 command 统一以 `actor + route + case + key + canonical request hash` 幂等：同 key/同 version+payload 返回原 204 且只写一个 event；同 key 异 payload 返回 409；不同 key 命中已完成/非法前态也返回状态冲突，不重复事件或覆盖结论。ACK/RESOLVE/DISMISS 与确定性 REOPEN 都锁定同一 case/version；REOPEN 仅由已激活规则在终态后追加新事件并 CAS 回 OPEN（或按 policy 创建新 case），不得改写原人工结论。`active_dedup_key` 唯一冲突时关联已有活动 case，不能生成两个活动案例。

### 审批、审计与治理

| 方法与路径 | 输入/输出 | 权限与规则 |
| --- | --- | --- |
| `GET /api/ai/proposals` | 待审/历史分页 | `ai:approval:review` + `ActionAuthorizationPolicy` 完整 action 策略；`REPAIR_ASSIGN` 还要求 ADMIN、`repair:write` 和对象范围 |
| `GET /api/ai/proposals/{id}` | preview、hash、snapshot、过期和成本 | 与列表复用同一 `ActionAuthorizationPolicy`；不可见统一 404 |
| `POST /api/ai/proposals/{id}/approve` | proposalVersion、payloadHash、businessSnapshotHash、comment；返回 execution result | `ai:approval:review` + 完整 action 策略；要求 `Idempotency-Key` |
| `POST /api/ai/proposals/{id}/reject` | version、comment；204 | `Idempotency-Key`；与列表/approve 相同的完整 action 策略和 proposal 可见性；同 key 同 version/comment hash 返回原 204，同 key 异 payload 409；不可执行 |
| `POST /api/ai/executions/{id}/reconfirm` | executionVersion、payloadHash、businessSnapshotHash、comment；返回对账/执行结果 | 仅 NEEDS_REVIEW；新真实 USER 会话 + 与原 action 相同完整策略 + CSRF/`Idempotency-Key`；复用唯一 execution row，不新建 attempt |
| `GET /api/ai/audit/runs` | 按时间/能力/状态/provider 分页 | `ai:audit:read`；不允许任意字段排序 |
| `GET /api/ai/audit/runs/{id}` | run/tool/retrieval/citation/usage/approval 元数据与 hash 链，不返回 message/tool/citation 正文 | `ai:audit:read` |
| `GET /api/ai/audit/runs/{id}/content` | 经脱敏的必要正文 | `ai:audit:content:read` + 对应底层业务权限 + `X-Step-Up-Proof` + `X-Audit-Reason`；每次访问再写 audit event |
| `GET /api/ai/audit/costs` | 固定维度聚合 | `ai:audit:read`；不接受 SQL/表达式 |
| `GET /api/ai/operations/readiness` | AI capability/adapter alias 的粗粒度状态与稳定错误码 | `ai:config:manage` + 内部管理网；不返回 endpoint、凭证、地域或 provider 原始错误；不得把 `/api/ai/**` 加入 Sa-Token 全局排除 |
| `GET /api/ai/operations/kill-switches` | 当前禁用项的 scope/key、稳定 `resourcePublicId`、reason、version | `ai:config:manage`；MySQL 事实源，需逐实例读取验证 |
| `POST /api/ai/operations/kill-switches/{scope}/{key}/disable` | reason；返回当前禁用记录 | `ai:config:manage`；紧急关闭不要求 step-up，审计必须可写，运行时只能收窄部署配置 |
| `POST /api/ai/operations/kill-switches/{scope}/{key}/clear` | reason + `expectedVersion`；返回清除后的记录 | `ai:config:manage` + `X-Step-Up-Proof`；proof 的 `KILL_SWITCH_CLEAR` request hash 必须绑定 scope、canonical key、reason 和同一 expectedVersion，版本变化返回 409 |
| `GET /api/security/csrf` | 返回与当前登录态绑定的短期 CSRF token，`Cache-Control: no-store` | 已登录用户；这是 Cookie 鉴权全局安全端点，不限 AI；token 不进入 URL、日志或 SSE |
| `POST /api/security/step-up` | actionCode、resourcePublicId、requestHash + 当前密码/获批 factor；返回一次性 opaque proof 和 expiresAt | 当前会话 + CSRF/Origin + 账号启用 + 爆破限流；密码只在请求内存验证，不记录；proof 绑定 session/actor/action/resource/hash，以 `X-Step-Up-Proof` 使用；private/no-store |
| `GET/POST /api/ai/prompts` | 版本列表/创建 draft | `ai:config:manage` |
| `GET /api/ai/prompts/{id}` | 单个 prompt version 的状态、hash、schema 和激活元数据 | `ai:config:manage`；不存在返回 404，不通过该端点修改不可变内容 |
| `POST /api/ai/prompts/{id}/activate` | 204 | `ai:config:manage` + `X-Step-Up-Proof`；要求审批人与创建人策略由产品确认 |
| `POST /api/ai/model-aliases/{alias}/activate` | deploymentId/version；204 | `ai:config:manage` + `X-Step-Up-Proof`；alias 行锁/CAS，provider contract/数据政策已通过 |
| `GET /api/ai/tool-catalogs` | 服务端标准 7 工具 ID 与 manifest hash | `ai:config:manage`；不接受客户端上传任意工具或实现类 |
| `POST /api/ai/tool-catalogs/{id}/activate` | version/hash；204 | `ai:config:manage` + `X-Step-Up-Proof`；全局 active slot 行锁/CAS，未知/高危工具拒绝 |
| `POST /api/ai/eval-runs` | suite/dataset/model/prompt 版本；202 eval run/Location | `Idempotency-Key`；`ai:eval:run` + 数据集权限 + 独立 eval 预算；记录 USER initiator，后台以最小 SERVICE actor 执行 |
| `GET /api/ai/eval-runs/{id}` | 状态、版本、脱敏指标与 artifact 引用 | `ai:eval:run` + 对应数据集权限；artifact 重新鉴权，不返回 provider 原始正文 |

## SSE 契约

响应头至少包含 `Content-Type: text/event-stream`、`Cache-Control: no-store`、`X-Accel-Buffering: no`（若代理支持）。SSE `id` 使用单调递增 sequence。

统一 data：

~~~json
{
  "eventId": "47",
  "runId": "uuid",
  "sequence": 47,
  "type": "citation.added",
  "timestamp": "2026-07-11T14:30:00+08:00",
  "payload": {}
}
~~~

| event type | payload 最小字段 | 是否持久化/可回放 |
| --- | --- | --- |
| `run.accepted` | capability | 是 |
| `run.queued` | 运行排队状态 | 是 |
| `run.started` | modelAlias | 是 |
| `message.delta` | textDelta | 合并批次后是 |
| `citation.added` | citationId、label、locator、rank | 是 |
| `tool.started` | 预留扩展 | 当前不发出；工具事实由审计查询返回 |
| `tool.completed` | 预留扩展 | 当前不发出；工具事实由审计查询返回 |
| `proposal.created` | 预留扩展 | 当前不发出；提案通过命令结果/列表查询获得 |
| `usage.updated` | inputTokens、outputTokens、costEstimate、currency、estimateFlag | 是 |
| `run.degraded` | 预留扩展 | 当前不发出；降级由终态结果的安全字段表示 |
| `run.completed` | 助手结果的 messageId/finishReason，或对应命令的结构化结果 | 是 |
| `run.failed` | errorCode、retryable、safeMessage | 是 |
| `heartbeat` | serverTime | 否 |

SSE 不发送内部堆栈、原始工具参数、provider request ID 中的敏感信息或未经 ACL 校验的引用正文。

## 结构化业务合同

### DashboardQueryIntent v1

~~~json
{
  "metricIds": ["repair.pending.count"],
  "dateRange": {"preset": "LAST_30_DAYS"},
  "dimensions": ["repairType"],
  "filters": {"repairTypes": ["水电维修"]},
  "presentationHint": "TABLE"
}
~~~

- metric、dimension、filter key 都必须存在于 `MetricCatalog`。
- 服务端验证现有数值业务 ID 的存在性、对象权限和允许范围；模型永远看不到表名，也不能把 ID 直接拼进查询表达式。
- 最大时间范围、组合数量和行数由 metric 定义控制。
- 当前默认目录不支持 buildingId/buildingIds；楼栋维度扩展暂不实施。维修指标支持 repairType/repairTypes，其他默认指标不开放额外维度。不能将公共类型中的预留字段当作默认目录能力。

### RepairTriage v1

~~~json
{
  "category": "水电",
  "urgency": "HIGH",
  "recommendedTeam": "水电维修组",
  "missingInformation": ["是否已经断电"],
  "reasoningSummary": "脱敏后的简短理由",
  "assignmentCandidateUserId": null
}
~~~

`urgency` 只是建议，不修改 `repair_order.status`。人员候选必须来自授权的确定性选项，不允许模型编造 ID。

### NoticeCreateDraftProposal v1

~~~json
{
  "title": "公告标题",
  "type": "通知",
  "status": "草稿",
  "content": "纯文本正文"
}
~~~

执行 payload 只接受 title/type/status/content。publisher/operator 从真实会话取得；引用及解释属于 preview/evidence，不混入执行参数。`status` 固定为“草稿”，任何“已发布”值都拒绝。

### RepairAssignProposal v1

~~~json
{
  "repairOrderId": 123,
  "assigneeUserId": 45
}
~~~

执行时重新读取数值 ID 对应的维修单和用户并做对象/角色校验，再调用 `OperationsService.assignRepairOrder`。该服务当前还要求只有系统管理员可以分配维修单，因此 `repair:write` 不是充分条件；若审批人不满足现有服务规则，返回 403。当前 assignee 或状态与 snapshot 不一致时返回 409 并将 proposal 标记 STALE。

## 工具白名单

目录保留 7 个固定 ID 以兼容旧记录，区分运行时上下文、内部提案、预留三种用途。当前不向模型注册 provider-callable 工具；容量摘要和公告列表预留工具不执行，也不记录伪造成功。公告页面上下文经独立业务 Facade 重取，不冒充 notice.list_published.v1。实际已执行上下文使用关闭字段集合校验、字节上限、次数计数和结果截止时间；同步 handler 的截止时间表示拒绝超时结果，不代表能够强制终止数据库阻塞。

| Tool ID | 输入 | 输出 | 调用时权限 | 审批/执行权限 |
| --- | --- | --- | --- | --- |
| `knowledge.search.v1` | query、source scope、topK | 脱敏片段与 citation candidate | `ai:knowledge:read` + source permission | 不适用 |
| `dashboard.query_metric.v1`（当前上下文） | 固定 dashboard.context.v1 请求 | 授权 cards；独立 DashboardQueryIntent 命令由指标服务处理 | `ai:dashboard:query` + metric permission | 不适用 |
| `repair.get_context.v1` | 现有 repair 数值 ID | 脱敏报修快照 | `ai:repair:triage` + `repair:read` + actor-aware `RepairAccessPolicy`；REPAIRER 必须仍是当前 assignee | 不适用 |
| `dormitory.get_capacity_summary.v1`（预留，不执行） | 预留 | 无运行结果 | `ai:assistant:use` + `dormitory:read` | 不适用 |
| `notice.list_published.v1`（预留，不执行） | 预留 | 无运行结果 | `ai:assistant:use` + `notice:read` | 不适用 |
| `repair.propose_assignment.v1` | validated triage + candidate | proposal public ID | `ai:repair:triage` + `repair:read` | `ai:approval:review` + `repair:write` + 现有 Service 的管理员分配规则 |
| `notice.propose_draft.v1` | validated draft | proposal public ID | `ai:notice:draft` + `notice:read` | `ai:approval:review` + `notice:write` |

ToolCatalog 对每个工具固定：版本、描述、输入/输出 schema、required permissions、最大响应大小、超时、每 run 次数、数据分类和是否可产生 proposal。

维修 read tool 必须显式接收已复核的 `BusinessActorScope`，调用与 Controller 共用的 `RepairAccessPolicy`/actor-aware Service；不能调用依赖后台 ThreadLocal 的旧查询后再自行过滤。查询与改派竞态中，读取当下已失去 assignee 范围时统一 403/404，返回模型的上下文为空。

禁止项：

- 任意 SQL、表/列枚举、JDBC、Mapper、数据库 schema 浏览。
- 任意 URL fetch、HTTP client、shell、脚本、文件系统、动态 Bean/反射调用。
- 任意 tool name、class name、method name 或 endpoint path 由模型传入。
- 首期所有入住、退宿、床位、缴费、公告发布、维修完成、用户与角色写动作。

## 审批协议

### 创建

1. 提案工具根据固定 action schema 生成 payload。
2. 服务端重写可信 actor 字段，计算 canonical JSON `payload_hash`。
3. 从现有 Service 读取目标状态并计算 `business_snapshot_hash`。
4. 生成 preview：目标、当前值、建议值、影响、所需权限、引用、数据时间、风险和过期时间。
5. 保存 PENDING_APPROVAL；默认建议 30 分钟后过期，待真实操作时长校准。

### 审批

1. 客户端提交 proposal version、两个 hash 和 `Idempotency-Key`。
2. 服务端检查 Sa-Token、账号启用，并用与 list/detail/reject/handler 共用的 `ActionAuthorizationPolicy` 计算 proposal 可见性和完整 action 策略；例如 `REPAIR_ASSIGN` 必须同时满足 ADMIN、`repair:write` 和对象范围。
3. 开启审批事务，按 `SELECT ... FOR UPDATE` 锁住 proposal；等价实现可用 version CAS + 有界重读，但必须让 APPROVE、REJECT、EXPIRE、CANCEL 和 STALE 判定共享同一串行化点。
4. 在锁内校验合法前态、version、expiry、policy、两个 hash、当前业务 snapshot、当前可见性和权限；任何变化返回 `AI_PROPOSAL_STALE` 或稳定 409，Service 调用次数为 0。
5. 在同一事务创建/锁定 `ai_idempotency_record` 并比较 canonical request hash，再 append approval。REJECT 是否立即终止由已冻结的 policy 决定；终止后的并发 APPROVE 只能读到终态，不能写 execution。
6. 对当前 proposal version 的独立 APPROVE reviewer 计数并原子更新 `approved_count`。未达门槛则保持 PENDING_APPROVAL；达到门槛的唯一事务记录 APPROVED 迁移、立刻进入 EXECUTING，并插入 `UNIQUE(proposal_id)` 的 execution lease。只有成功插入 lease 的请求拥有执行资格；其他并发请求返回现有状态/结果引用。
7. 获得 lease 的最后审批请求在当前 HTTP 和真实 Sa-Token 上下文中调用 allowlisted handler；handler 内现有 Service 再次执行 `StpUtil.checkPermission`。SERVICE/MODEL/SYSTEM actor 永远不具备 execution 资格。
8. 业务 Service 变更和 execution SUCCEEDED 在同一 MySQL 事务中提交；失败时另一个独立事务记录 FAILED/NEEDS_REVIEW。execution 状态以 version/合法前态更新，不能创建第二个 attempt 绕过唯一 lease。
9. 成功事务可同时写 outbox，供通知和分析使用；outbox 不负责模拟审批人执行。

如果进程在审批已记录、业务事务未开始前退出，execution 标记 NEEDS_REVIEW；用户必须在新会话通过 reconfirm 端点重新确认。服务端先对账业务状态：结果已经原子提交则只把同一 execution 对账为 SUCCEEDED；能证明原事务未执行且当前 snapshot/hash 未变时，更新同一 execution 的 version/lease 后进入 EXECUTING；结果不确定则保持 NEEDS_REVIEW，冲突则 FAILED/STALE 并要求新 proposal。系统不后台冒充审批人，也不创建第二个 execution attempt。

`approval_policy_version` 决定是否允许 proposer 自审、所需独立审批人数和拒绝是否立即终止。达到 `required_approval_count` 前 proposal 保持 PENDING_APPROVAL，不创建 execution；政策改变时现有 proposal 标记 STALE 并重新预览。

### 拒绝、过期与重试

- REJECT 写 append-only approval event；其与 APPROVE/EXPIRE/CANCEL 使用同一 proposal 行锁/CAS，按冻结 policy 决定是否立即终止。
- EXPIRED/STALE 不能批准；必须基于新业务快照创建新 proposal。
- FAILED 不由模型改参重试。用户看到原业务错误后，可重新预览并生成新 proposal。
- 同一 actor/route/proposal/key + 同 request hash 返回原执行结果；同 key 异 payload 返回 409；不同 key 也不能绕过 proposal 终态或 `UNIQUE(proposal_id)` execution lease。

## Outbox 事件

| 事件 | 生产事务 | 消费者 | 失败处理 |
| --- | --- | --- | --- |
| `KnowledgeVersionRegistered.v1` | 创建 version | ingestion worker | 指数退避；超过上限进入 DEAD/QUARANTINED |
| `KnowledgeVersionActivated.v1` | 激活 current version | 内部 receipt 确认 | 当前 MySQL 版本/ACL 为真值；外部 cleanup 适配器暂不实施 |
| `AiRunCompleted.v1` | run 终态 | 内部 receipt 确认 | usage 已在主事务处理；自动 eval sampling 暂不实施 |
| `ActionExecutionSucceeded.v1` | 业务写 + execution 成功事务 | 内部 receipt 确认 | 不重复业务写；通知/分析订阅暂不实施 |
| `RiskCaseOpened.v1` | 新风险案例 | 内部 receipt 确认 | 页面查询案例事实；外部通知暂不实施 |
| `FeedbackRecorded.v1` | feedback 写入 | 内部 receipt 确认 | 自动进入评测/训练数据集暂不实施 |
| `EvalRunRequested.v1` | eval 创建 | eval worker | 不影响线上请求 |

消费者以 outbox public ID 做幂等；多实例锁定和归档策略在架构评审中压测。

`ControlPlaneOutboxHandler` 的 SUCCEEDED 仅证明内部事件已校验和确认，不证明通知、分析、缓存或训练数据消费者已经运行。ActionProposalCreated/StateChanged、KnowledgeSourceChanged 与配置治理事件也使用该内部 receipt。若未来接入外部消费者，需要独立幂等回执和重放策略，不沿用内部确认冒充交付。

## 已实施迁移顺序与回退

1. 已增加 44 张 AI 控制面表、索引和 14 个权限种子，仓库 feature flag 默认仍关闭；权限初始化使用已批准映射幂等补缺，不能假设 ADMIN 因旧非空角色记录自动获得 AI 权限。
2. `AiSchemaMigrationInitializer` 已执行表/列/索引存在性检查，H2 与 MySQL 都有回归覆盖。
3. `SchemaContractTest` 和真实基础设施 IT 已分组断言 19 张现有业务表与 44 张 `ai_*` 表；第 44 张 `ai_step_up_failure_window` 的列、主键和非负约束均纳入合同。
4. Spring AI adapter 默认关闭；本地 Stub 合同和 2026-07-15 DeepSeek `deepseek-v4-flash` 真实 5 项合同分别验证，真实凭证不进入源码或测试资源。
5. 本地确定性向量/对象适配器及合同测试已完成；生产外部适配器仍为 `NOT RUN`，选型后必须先完成 ACL、重建、删除、隔离扫描和灾备测试，才能摄取生产知识。
6. 公告正文已按“增列 → 双读兼容 → API/前端启用 → AI proposal handler”顺序完成迁移。
7. 回退时先关 capability/write-execution 开关并停止 worker；不删除审计表、不回滚已增加的 nullable 公告字段。

任何迁移失败都必须保持当前非 AI API 可启动；AI 初始化失败在开关关闭时不得阻断主应用启动，开关开启且控制面契约不完整时则应 fail fast。
