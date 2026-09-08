# AI 智能宿舍系统现行开发提示词

> 更新日期：2026-09-08
> 本文是后续编码 Agent 的唯一现行提示词入口。`design/ai-prototypes/` 中 9 张最终 PNG 是唯一视觉合同；能力、接口、安全和状态语义分别以 `ai-master-plan.md`、`ai-data-contract.md`、`ai-security.md` 为准，验收证据以 `ai-verification.md` 为准。

## 1. 使用方法

1. 选择下面一个页面任务，复制完整提示词执行；跨页面公共改动还要同时执行“设计系统与共用组件”提示词。
2. 编码前必须用 `view_image` 查看任务列出的最终 PNG，并阅读现有页面、Store、API、路由与测试；不得仅凭文字或旧截图自由发挥。
3. 不调用 Figma，不重新生成、替换或编辑原型图，不把 `candidates/` 当视觉合同。
4. 本文用于现有能力的增量维护。20260809-dashboard-feedback-l 和 RC2 均是历史候选；当前修复范围与结果见总计划顶部及 ai-verification.md 同日记录，不复用旧 hash 作为新验证。
5. 先复现和补测试，再做最小范围修改；保留当前业务流程、RBAC、错误处理与用户已有改动。

## 2. 统一执行合同

后续所有页面任务必须遵守：

1. 当前有 `18 个原业务路由 + 4 个 AI 路由 = 22 个受保护路由`。继续使用现有 `router.beforeEach -> ensureSession() -> meta.permission`，不得新增绕过会话或权限的平行入口。
2. 前端已有 `AiClient`、`HttpAiClient`、SSE parser、Pinia AI Store 和确定性 `DemoAiClient`。运行模式由现有环境开关选择：真实联调走 `http`，单测/本地确定性场景可走 `demo`，关闭时安全降级；页面不得直接 `fetch`、硬编码 fixture 或复制第二套状态机。
3. 真实能力通过 `/api/ai/**`：POST 创建 conversation/run 或业务命令，GET `/api/ai/runs/{id}/events` 消费 `text/event-stream`；支持事件序列去重、`Last-Event-ID`、取消、重试、终态和断线处理。
4. Cookie 写请求继续通过公共 `apiRequest()` 发送 CSRF token 并接受 Origin 校验；`401` 统一清理会话/CSRF 状态。不得用自写请求绕过这些防护。
5. v2 工具目录保留 7 个固定 ID，实际分为 3 个 runtime context、2 个 internal proposal、2 个 reserved（capacity/notice-list）；providerCallable 为空。固定 ID 为：`knowledge.search.v1`、`dashboard.query_metric.v1`、`repair.get_context.v1`、`dormitory.get_capacity_summary.v1`、`notice.list_published.v1`、`repair.propose_assignment.v1`、`notice.propose_draft.v1`。模型不得直连数据库、生成/执行 SQL、调用任意 URL、命令、脚本或文件系统。
6. 首期提案 `actionType` 只有 `REPAIR_ASSIGN` 与 `NOTICE_CREATE_DRAFT`，对应版本化工具分别为 `repair.propose_assignment.v1` 与 `notice.propose_draft.v1`。所有业务写必须经过“AI 建议 -> 变更预览 -> 人工审批 -> 现有 Service 执行”；`APPROVED` 不等于成功，execution `SUCCEEDED` 才是业务成功。
7. 继续执行服务端会话、权限、对象范围、payload/hash、业务 snapshot、过期时间和 recent-auth/step-up 校验。前端隐藏按钮只是体验，不是授权边界。
8. 模型文本、引用和错误按纯文本渲染，禁止 `v-html`。无权限引用不显示正文；无可靠来源明确拒答；姓名、学号、手机号等 PII 默认不得进入模型。
9. AI 关闭、超时、限流、供应商或审计故障时，原业务页面和人工流程必须继续可用；不得以伪成功、静默重试写操作或前端本地改状态掩盖失败。
10. 风险、安全和状态不能只靠颜色表达；同时提供图标、文字、可访问名称、focus、loading、disabled 和错误状态。触摸目标至少 `44 x 44px`。
11. 任何视觉改动固定验证六视口：`1920x1080`、`1366x768`、`1586x992`、`1536x1024`、`1505x1045`、`390x844`。禁止全页横向滚动；复杂表格只能在自身容器滚动。
12. 图片中的日期、模型别名、数字、case ID 和 hash 只是视觉示例，不能硬编码为业务事实。
13. 全站只使用一套 `AppSidebar` / `AppHeader` / `AppLayout`：桌面 Sidebar 展开 `208px`、折叠/移动 `52px`、Header `64px`。各页面 PNG 中不同的侧栏宽度是原始构图差异，不得实现成按路由切换的壳层尺寸或样式。
14. AI 菜单以当前实际注册路由为候选集合，并由现有 AI 功能开关和逐路由 RBAC 共同过滤。不得照抄原型中的任一套 AI 菜单文字、数量或顺序，不得用隐藏菜单替代服务端授权。
15. 页面主体按各自最终 PNG 实现。Dashboard 图只约束 Dashboard 主体；Assistant 图只约束 Drawer/Sheet，其桌面底图只是承载上下文，不能覆盖 Dashboard 专用原型或改写共享侧栏。
16. `candidates/` 不参与实现。Assistant 候选 v1 已废弃，v2 与最终 `ai-assistant-desktop.png` 相同；代码、测试和验收统一引用最终文件名。
17. 不接受“整体结构接近”作为视觉完成。每张原型必须在同逻辑视口与当前页面同尺度并排审查，并记录字体、颜色、背景、图标、边框、圆角、阴影、间距和控件状态的目标/当前/偏差。
18. 运行时 computed style 优先于 CSS 源码推断。必须检查 Ant Design 的暗色菜单、半透明文字、禁用态、输入框和子菜单 Token 是否泄漏；不得依赖默认主题碰巧接近原型。
19. 视觉任务必须使用 `ui-ux-pro-max`、`impeccable` critique/audit、`frontend-patterns` 和真实浏览器验证；Sidebar/字体与 Assistant/页面质感至少各有一份独立只读 UI Agent assessment。
20. Design System 必须生成专用状态画廊，覆盖排版、颜色、图标、按钮、输入框、AI 建议卡、引用卡、审批流程及 default/hover/focus/active/disabled/loading/error；组件“存在”不等于视觉合同通过。
21. 视觉 fixture 只能构造确定性合法内容态并显式标记，不能伪造业务事实。高密度成功态、流式、空态、低置信、无来源、无权限、失败、取消、超时和禁用态必须分别留证。
22. 自动化全绿、截图存在、无溢出或 Canvas 非空不能单独关闭 UI 高保真。所有 P0-P3 视觉 finding 关闭后仍需用户肉眼验收。

## 3. 实现基线和历史证据

本节原始 PASS 来自历史候选，不代表本轮验证；当前脚本应使用显式参考时刻和唯一 VISUAL_OUTPUT_NAME，不把固定过期日期改成 2099 年。


执行任务前先确认以下基线仍成立，不要通过删除断言或降级合同来“通过”测试：

| 范围 | 当前事实 |
| --- | --- |
| 后端 | 当前候选的全量测试、AI 覆盖率、安全专项和真实 MySQL/Redis 门已通过；精确数字见 `ai-verification.md` |
| 前端 | 当前候选的 Vitest、覆盖率、typecheck 与 build 门已通过；精确数字见 `ai-verification.md` |
| 普通 E2E / AI-live | 当前候选均已通过；AI-live 使用真实应用/基础设施和确定性 Fake provider，不能当作真实供应商证据 |
| 视觉 | 固定覆盖 22 个受保护路由、六正式视口和 9 PNG；ew/ex/ey/ez/fa/fb/fc 与 l 均是历史制品，新变更重跑受影响门，结果见 ai-verification.md |
| 工程状态 | 阶段 0-6 后端/控制能力 `COMPLETED`；UI 高保真复验 `COMPLETED`；阶段 7 `OUT OF SCOPE`；生产外部门仍为 `NOT RUN` |

确定性 Fake provider 只用于本地可复现验收，不代表真实供应商或生产发布已批准。不要把本地 AI-live 通过描述成生产就绪。

### 3.1 现行 API 快查

页面必须通过现有 `AiClient` 方法调用这些路径，不在组件内重复拼接 URL：

| 能力 | 现行路径/协议 |
| --- | --- |
| 会话 | `GET/POST /api/ai/conversations`，`GET /api/ai/conversations/{id}` |
| 消息与 run | `POST /api/ai/conversations/{id}/messages` |
| Dashboard | `POST /api/ai/dashboard/queries` |
| 知识问答 | `POST /api/ai/knowledge/queries` |
| 维修分诊 | `POST /api/ai/repairs/{repairOrderId}/triage` |
| 公告草稿 | `POST /api/ai/notices/drafts` |
| 流式事件 | `GET /api/ai/runs/{id}/events`，`text/event-stream` |
| run 控制 | `POST /api/ai/runs/{id}/cancel`、`POST /api/ai/runs/{id}/retry` |
| 引用与反馈 | `GET /api/ai/citations/{id}`、`POST /api/ai/messages/{id}/feedback` |
| 风险 | `/api/ai/risk-cases`、`/api/ai/risk-scans` 及受控处置 action |
| 提案审批 | `/api/ai/proposals`、`/{id}/approve`、`/{id}/reject` |
| 执行对账 | `POST /api/ai/executions/{id}/reconfirm` |
| 审计 | `/api/ai/audit/runs`、`/{id}/content`、`/api/ai/audit/costs` |
| 知识治理 | `/api/ai/knowledge/sources`、uploads、versions、jobs 与公开审批 |

### 3.2 统一状态真值

| 状态 | 页面必须表达的事实 |
| --- | --- |
| `ACCEPTED / QUEUED / RUNNING` | 真实排队或执行中，不伪装输出已完成 |
| `STREAMING` | 分块显示并始终提供停止，读屏不逐 token 轰炸 |
| `SUCCEEDED` | 展示引用、as-of、反馈与允许的后续动作 |
| `DEGRADED` | 保留确定性规则/检索事实，并说明降级原因 |
| `CANCELLED` | 保留已生成内容，可显式创建新 run |
| `FAILED / TIMED_OUT` | 安全错误与重试入口，不展示内部参数或堆栈 |
| `PENDING_APPROVAL` | 显示完整差异、权限、版本/hash 和影响范围 |
| `STALE / EXPIRED` | 禁止批准，刷新业务事实后重新预览 |
| `EXECUTING` | 显示执行中，不宣称业务成功 |
| `NEEDS_REVIEW` | 停止自动尝试，要求新会话内人工对账和 reconfirm |
| `NO_PERMISSION` | 说明能力受限，不泄露对象或引用正文 |
| `NO_GROUNDED_ANSWER` | 明确没有可靠来源，不给确定性结论 |

### 3.3 事实优先级

1. 当前源码、OpenAPI、schema 和新鲜测试报告优先于图片示例及旧进度文字。
2. PNG 决定各自页面主体/组件的结构、层级、尺寸、颜色、密度和响应式，不覆盖服务端权限、安全、状态机或数据约束；跨图共享壳层冲突按统一 `208/52/64` 合同和当前源码/新鲜测试收敛。
3. `ai-verification.md` 是验收数字唯一维护位置；本文数字只记录改写时的基线，发生变化时先以新鲜报告复核。
4. 发现文档与运行行为不一致时，先定位差异并更新合同/测试，不能静默选取更宽松的一方。

## 4. 九个逐页提示词

### 4.1 桌面 AI 智能驾驶舱

对应图片：`design/ai-prototypes/ai-dashboard-desktop.png`

```text
在仓库根目录增量维护桌面 AI 智能驾驶舱。先用 view_image 查看 design/ai-prototypes/ai-dashboard-desktop.png，再阅读 DashboardView.vue、DashboardView.test.ts、stores/ai.ts、stores/dormitory.ts、api/ai-http.ts、api/dormitory.ts、路由和全局样式。

保持 `/` 与原 Dashboard，不新增平行首页。保留真实 KPI、趋势和原业务数据加载；AI 查询继续 POST `/api/ai/dashboard/queries` 创建 run，并通过现有事件流得到固定指标结果。只允许六个固定指标和预编译查询参数，不生成 SQL。

按 PNG 维护 Dashboard 主体中的命令栏、KPI、运营简报、趋势、四类风险和待处理建议；共享 Sidebar/Header 仍执行统一壳层合同，不复制图片中的阶段性菜单。展示 as-of、置信度、引用和指标口径；无来源明确拒答，低置信要求人工核验。待办只能进入建议/审批详情，不能在 Dashboard 直接写业务。

使用 `dashboard:read` 保住页面访问，并按 `ai:dashboard:query`、`ai:risk:read`、`ai:approval:review` 控制对应能力。AI 关闭或失败不得影响原 Dashboard。补充成功、无来源、低置信、取消、超时、权限撤销和降级测试，并运行六视口视觉回归与 Canvas 非空检查。
```

### 4.2 桌面全局/上下文智能助手

对应图片：`design/ai-prototypes/ai-assistant-desktop.png`

```text
增量维护桌面智能助手。先用 view_image 查看 design/ai-prototypes/ai-assistant-desktop.png，再阅读 AppLayout.vue、AppHeader.vue、AiAssistantHost.vue 及其测试、stores/ai.ts、api/ai-http.ts、api/ai-sse.ts、auth store 和路由。

该 PNG 只约束助手 Drawer，不约束图中作为背景的 Dashboard 或 Sidebar。保持应用级 440-480px 右 Drawer，底层当前业务页仍可见且不被替换，关闭后焦点回到触发按钮。继续使用真实 conversation/message/run 两步协议与 `/api/ai/runs/{id}/events` SSE，不用计时器伪造流式；保留事件去重、Last-Event-ID、AbortController 停止、重试新 run、取消和卸载清理。

页面必须支持新会话、历史会话、全局/页面上下文、分块输出、停止、重试、复制、反馈、引用折叠、访问拒绝、无可靠来源拒答和节制的 aria-live。关闭运行中的 Drawer 必须确认停止，工具参数、SQL、内部 ID 和堆栈不得泄露。

视觉上统一消费全局语义 Token，不在 Assistant 内维护第二套任意色板；按原型逐项复核 Header 双标签、用户/AI 身份、回答卡、元数据、来源卡、四项横向操作、安全条和 Composer。必要文字不低于 4.5:1，caption 使用 12px 基线；移动 Header/Composer 在保留 44px 触控和 safe-area 的前提下不得挤压消息区。内部 `PERSON_NAME:v1:*` 等 PII token 只可映射为安全可读占位文案，绝不能恢复真实 PII 或直接展示内部表达。

入口需 `ai:assistant:use`，上下文工具还需当前页面原 read 权限；每次重连和工具调用由服务端重验权限。输入区持续提示不得输入姓名、学号或手机号。覆盖流式跨 chunk/UTF-8、断线重连、停止、失败、权限撤销、焦点恢复和 Escape 测试。
```

### 4.3 维修智能分诊

对应图片：`design/ai-prototypes/ai-repair-triage-desktop.png`

```text
增量维护 `/repairs` 的维修智能分诊。先用 view_image 查看 design/ai-prototypes/ai-repair-triage-desktop.png，再阅读 RepairManagementView.vue、RepairVisualContract.test.ts、operations store/API、ai store、api/ai-http.ts、AiProposalPreview.vue 和审批相关类型。

保留筛选、分页、新增、查看、人工指派和处理流程。选中工单后通过 `/api/ai/repairs/{repairOrderId}/triage` 创建真实 run，展示分类、紧急度、建议班组/人员、SLA、缺失信息、理由、as-of、引用和降级状态。

候选维修员必须来自当前用户有权读取的真实业务候选，不得由模型或前端编造 ID。只有 `repair:read` + `ai:repair:triage` 可查看分诊；通过 `repair.propose_assignment.v1` 创建 `REPAIR_ASSIGN` 提案及其审批还必须满足现行 AI 审批和 `repair:write` 服务端授权。

严格保留“AI 建议 -> 变更预览 -> 人工审批”。低置信、无候选、已完成、业务 snapshot 变化、无权限、无引用、审计不可用、STALE 或 EXPIRED 时禁用提交/批准。补充候选映射、并发变更、失效预览和原维修流程不回归测试。
```

### 4.4 公告 AI 起草

对应图片：`design/ai-prototypes/ai-notice-drafting-desktop.png`

```text
增量维护 `/notices/create` 公告 AI 起草。先用 view_image 查看 design/ai-prototypes/ai-notice-drafting-desktop.png，再阅读 NoticeManagementView.vue 及测试、ai store、api/ai-http.ts、AiProposalPreview.vue、公告业务 API 和审批合同。

保留公告列表、原创建/撤回和人工发布规则。通过 `/api/ai/notices/drafts` 创建真实 run，页面按 PNG 展示要点、类型、范围、授权来源、语气、纯文本草稿、内容检查、当前内容/AI 建议 diff 和审批区。

模型只能通过 `notice.propose_draft.v1` 提出 `NOTICE_CREATE_DRAFT` 草稿，不能发布。PII、手机号、HTML/脚本样式、无可靠来源、无权限或审计不可用必须阻断；正文始终纯文本。只有人工审批并由现有 Notice Service 成功创建草稿后才显示成功，人工发布仍是独立原业务动作。

按 `notice:read`/`notice:write` 与 `ai:notice:draft`/`ai:approval:review` 分层控制。覆盖草稿生成、PII/注入阻断、diff、stale/hash/expiry、step-up、执行失败和原公告流程回归测试。
```

### 4.5 智能风险中心

对应图片：`design/ai-prototypes/ai-risk-center-desktop.png`

```text
增量维护 `/ai/risks`。先用 view_image 查看 design/ai-prototypes/ai-risk-center-desktop.png，再阅读 AiRiskView.vue、aiRisk store、api/ai-http.ts、风险类型/测试、图表和视觉合同。

保持入住、维修、卫生和欠费的确定性运营信号，页面显示总览、趋势/分布、规则状态、案例列表、三层证据和 append-only 人工处置时间线。醒目保留“规则信号，不代表学生评价”。

列表/详情继续走 `/api/ai/risk-cases`，处置走受控 action endpoint；`ai:risk:read` 只读，`ai:risk:manage` 才能确认、解决或驳回。AI 只解释和排序，不自动处罚、分配责任或修改业务状态，不形成学生纪律、心理、健康、信用画像。

模型失败时保留规则与业务快照，明确 `deterministic_degraded`；规则版本、caseVersion、subject token、as-of 和处置说明不能被前端伪造。覆盖状态机、版本冲突、权限、降级、无置信度伪装和 ECharts 六视口非空检查。
```

### 4.6 待审批与运行审计

对应图片：`design/ai-prototypes/ai-approval-audit-desktop.png`

```text
增量维护 `/ai/approvals` 与 `/ai/audit`。先用 view_image 查看 design/ai-prototypes/ai-approval-audit-desktop.png，再阅读 AiApprovalView.vue、AiAuditView.vue、aiApproval store、api/ai-http.ts、proposal/execution 类型、step-up 与安全生命周期测试。

审批页保持列表、提案目标、当前值/建议值、影响范围、所需权限、引用、as-of、版本、payload hash、business snapshot hash、过期时间和完整执行时间线。批准继续调用 `/api/security/step-up` 与 `/api/ai/proposals/{id}/approve`，拒绝、刷新预览、NEEDS_REVIEW reconfirm 走现有受控 API；不得本地改状态冒充执行。

STALE、EXPIRED、低置信、无引用、业务权限缺失、审计不可用或 hash/版本变化时禁批。批准按钮需要确认影响范围；业务写不做透明网络重试。只有 execution `SUCCEEDED` 显示成功，APPROVED、EXECUTING、NEEDS_REVIEW 均不是成功。

审计页默认只读 `/api/ai/audit/runs` 脱敏元数据、检索轨迹、成本与 hash 链。正文读取需 `ai:audit:content:read`、底层业务授权、至少 10 字理由、recent-auth/step-up，并追加审计；无权资源按 404。覆盖冲突、单次 proof、响应丢失对账、reconfirm、正文 break-glass 和纯文本渲染测试。
```

### 4.7 移动端 AI 智能驾驶舱

对应图片：`design/ai-prototypes/ai-dashboard-mobile.png`

```text
完善 `/` 在 390x844 下的现有响应式驾驶舱。先用 view_image 同时查看 ai-dashboard-mobile.png 与 ai-dashboard-desktop.png，再阅读 DashboardView.vue、AppSidebar.vue、AppHeader.vue、全局样式、ECharts 配置和 live visual 测试。

复用桌面真实 API、Store、权限和状态，不复制移动版业务逻辑。保持统一的 52px 折叠 Sidebar、64px Header、两列统计卡、单列简报/风险/趋势/待办；首屏可发现关键风险，完整审批链可滚动到达。

命令栏、图表、低置信、无来源和按钮文字不得溢出；触摸目标至少 44px，safe-area 有效，body/documentElement 不出现横向滚动。表格在移动端改为摘要列表/详情层，不通过整体缩放塞入桌面表格。

补 390x844 的 Sidebar 偏移、scrollWidth、长中文、200% 缩放、Canvas 尺寸、命令查询和审批跳转断言，同时回归其余五个视口。
```

### 4.8 移动端智能助手

对应图片：`design/ai-prototypes/ai-assistant-mobile.png`

```text
完善智能助手在 390x844 下的全屏 Sheet。先用 view_image 同时查看 ai-assistant-mobile.png 与 ai-assistant-desktop.png，再阅读 AiAssistantHost.vue、AppLayout.vue、AppHeader.vue、ai store、SSE client、样式和组件测试。两张图片只约束助手自身，不能据此复制或改写底层页面与共享壳层。

复用桌面 conversation/run/SSE 实现，不复制移动客户端或 demo 状态。移动端临时覆盖 Sidebar，固定可访问的标题栏和输入区；停止生成始终可达，关闭后恢复原页面滚动与触发按钮焦点。

按 PNG 保留历史会话、全局/上下文标记、流式状态、回答、引用折叠、无权限引用、重试/复制/反馈、低置信和无来源状态。处理软键盘、safe-area、长引用、离线/重连与 200% 缩放，不得让输入区遮住内容。

Playwright 覆盖发送、停止、重试、展开引用、关闭确认、焦点返回、权限撤销和 body.scrollWidth；同一组件在五个桌面视口仍保持 440-480px Drawer。
```

### 4.9 AI 设计系统与共用组件

对应图片：`design/ai-prototypes/ai-design-system.png`

```text
增量维护 AI 设计系统和 `frontend/src/components/ai/`。先用 view_image 查看 design/ai-prototypes/ai-design-system.png，再阅读全局 CSS、AiCommandBar.vue、AiEvidenceMeta.vue、AiProposalPreview.vue、AiRunStatus.vue、AiSafetyState.vue 及全部组件测试。

统一令牌：primary #2563EB、Sidebar #163B83、AI accent #6366F1、success #10B981、warning #F59E0B、danger #EF4444、背景 #F5F7FA、边框 #E7EDF6；桌面 Sidebar 展开 208px、折叠/移动 52px、Header 64px、Drawer 440-480px。卡片圆角 8-12px、低阴影，禁止霓虹、重玻璃拟态和装饰性大渐变。

共用组件只接 typed props/emits 或现有 Store action，不自行 fetch。统一表达 run、citation、confidence、proposal、approval、execution、permission、stale/expired、degraded/no-grounded-answer 状态；风险状态同时用图标、文字和可访问名称。

保证按钮/输入的 default、hover、focus-visible、active、disabled、loading 和错误态稳定，动态内容不引发布局跳动。测试纯文本渲染、键盘操作、aria、长中文、无权限和所有终态；逐页跑六视口，不能用改全局样式掩盖单页溢出。

共享 Sidebar 必须补路由到父组的受控 `openKeys` 映射，当前子路由打开时父组和选中子项必须可见；展开子菜单背景不得为 Ant 默认 `#000c17`。一级/二级文字使用明确高对比颜色和 500/600/700 标准字重，图标按语义映射并与文字同步状态。新增展开、hover、selected、focus、滚动和当前路由自动展开的真实浏览器截图与 computed-style 断言。
```

## 5. 验证命令

按改动范围先跑聚焦测试，再跑完整质量门。涉及真实服务的命令执行前，必须确认数据库、Redis、端口和环境不是生产环境。

```powershell
# 后端（仓库根目录）
cd backend
mvn -q test
mvn -q "-Dtest=CsrfProtectionTest" test
mvn -q -P ai-coverage verify
mvn -q "-Dtest=RealInfrastructureIT,AiRealInfrastructureIT,AiUploadRealInfrastructureIT" test
mvn -q "-Dtest=AiOpenApiContractTest,AiSchemaMigrationInitializerTest" test
mvn -q -DskipTests package
Get-FileHash -Algorithm SHA256 .\target\student-dormitory-management-system-0.1.0.jar

# 前端（仓库根目录）
cd frontend
npm run test:coverage
npm run typecheck
npm run build
npm run e2e
npm run e2e:ai-live
npm run e2e:visual
Get-FileHash -Algorithm SHA256 .\test-results\visual\manifest.json
```

真实供应商合同 `RealModelContractIT` 只有在凭证、独立预算和用户明确授权齐全时运行；否则记录 `NOT RUN`，不得把 JUnit 条件跳过计为通过。

## 6. 完成交付规则

1. 报告修改文件、行为变化、权限/安全影响和所有实际执行的命令；未运行项明确写 `NOT RUN` 与原因。
2. 六视口逐一检查 network、console、page error、runtime、横向溢出、遮挡和 Canvas 非空；原型对应页面还要核对 9 个 prototype contracts。
3. 当前 AI-live 必须证明真实 `HttpAiClient` 请求 `/api/ai/**`、助手 SSE、公告草稿提案和知识摄取可用，且没有绕过审批写业务；确定性 Fake provider 仅替代模型输出。通用 AI-live 与维修/公告专用 `playwright.ai-live-repair-notice-stage4.config.ts` 分开执行，修改维修链时必须运行对应专用验证；精确结果见 [AI 验证与验收](./ai-verification.md)。
4. 任何新增业务写动作、工具、权限、状态或数据表都不属于普通页面改造，必须先更新总计划、数据契约和安全评审，再实施。
5. 不删除旧断言、不降低覆盖率门、不关闭 CSRF/Origin/RBAC/审计、不用截图替代功能验证，也不把本地结果扩张为生产结论。
6. 每个阶段开始前立即在活动任务计划标记 `in_progress`；阶段交付物和验证通过后，在同一工作会话立即改为 `completed` 并把命令与结果写入进度文件。验证失败或缺少环境/批准时标记 `blocked` / `NOT RUN` 并记录解阻条件，禁止到最终汇报再批量更新状态。
7. UI 完成报告必须链接逐图差异矩阵、状态画廊、全新六视口制品、两份独立 UI assessment 和用户验收结论；旧 `visual-final-20260727-f` 只能列为结构基线。
