# AI 智能学生宿舍管理系统原型索引

本目录只保存原型图、候选视觉证据和必要的视觉索引。开发计划与提示词统一维护在 `plan/`：

- [AI 总计划](../../plan/ai-master-plan.md)
- [现行开发提示词](../../plan/development-prompts.md)
- [AI 数据契约](../../plan/ai-data-contract.md)
- [AI 安全契约](../../plan/ai-security.md)
- [AI 验证与验收](../../plan/ai-verification.md)

以下 9 张最终 PNG 是现行唯一视觉合同。不再等待或调用 Figma，不重新生成图片。PNG 决定各自页面主体、页面内组件、信息层级、尺寸关系、颜色、密度和响应式状态；功能、安全、权限和数据语义以 `plan/` 的现行文档为准。多张 PNG 中重复出现但彼此不一致的共享壳层，按下述统一合同解释，不能拆成逐页变体。

## 合同适用范围与冲突收敛

1. 每张最终 PNG 只约束“最终资产”表中列出的页面主体或组件；不得用一张图的底层背景覆盖另一张图的专属页面合同。
2. `AppSidebar`、`AppHeader` 和应用级布局是全站唯一共享壳层。当前源码与新鲜测试采用桌面展开 `208px`、折叠/移动 `52px`、Header `64px`，这是统一实现和验收尺寸；任何页面不得自行改成另一套宽度。
3. AI 菜单以当前实际注册路由为候选集合，并同时经过 AI 功能开关和逐路由 RBAC 过滤。原型图中的菜单名称、数量和顺序只是各阶段构图参考，不能复制成页面专属菜单，也不能绕过路由与权限事实。
4. `ai-dashboard-desktop.png` / `ai-dashboard-mobile.png` 约束 Dashboard 主体；`ai-assistant-desktop.png` / `ai-assistant-mobile.png` 只约束助手 Drawer/Sheet。助手桌面图中可见的 Dashboard 只是承载上下文，不得反向覆盖 Dashboard 专用原型。
5. `candidates/` 只保存决策证据。候选 v1 已废弃；候选 v2 与 `ai-assistant-desktop.png` 相同，只以最终文件名参与实现和验收。

原始桌面图中的 Sidebar 实测宽度并不一致，这一差异用于解释为什么共享壳层必须收敛，而不是提供多套实现尺寸：

`ai-design-system.png` 图内保留的 `240px Sidebar` 是原始设计标注，但它与各页面 PNG、当前源码及新鲜视觉证据冲突，已被上述 `208px` 共享壳层合同取代；不得据此把运行实现改回 `240px`。旧文档曾出现的移动 `72px` 也不是当前实现合同。

| 原图 | 原始像素宽度 | 合同解释 |
| --- | ---: | --- |
| `ai-dashboard-desktop.png` | 207px | 页面主体参考；共享壳层统一为 208px |
| `ai-assistant-desktop.png` | 185px | 只验收 Drawer，底层壳层不取该宽度 |
| `ai-repair-triage-desktop.png` | 202px | 只验收维修主体 |
| `ai-notice-drafting-desktop.png` | 254px | 只验收公告主体 |
| `ai-risk-center-desktop.png` | 223px | 只验收风险主体 |
| `ai-approval-audit-desktop.png` | 224px | 只验收审批/审计主体 |
| `ai-dashboard-mobile.png` | 105px / 852px 画布 | 折算到 390px 逻辑宽约 48px；实现统一使用 52px |

## 最终资产

| 文件 | 实际尺寸 | 页面目的 | 当前承载 |
| --- | --- | --- | --- |
| `ai-dashboard-desktop.png` | 1586 x 992 | 桌面 AI 驾驶舱、固定指标入口、简报、趋势、风险和待办 | `/`，`DashboardView.vue` |
| `ai-assistant-desktop.png` | 1586 x 992 | 桌面全局/上下文助手 Drawer；不约束底层 Dashboard | 应用级，`AiAssistantHost.vue` / `AppLayout.vue` / `AppHeader.vue` |
| `ai-repair-triage-desktop.png` | 1586 x 992 | 维修列表、详情、分诊建议和人工审批链 | `/repairs`，`RepairManagementView.vue` |
| `ai-notice-drafting-desktop.png` | 1536 x 1024 | 公告要点、纯文本草稿、内容检查、diff 和审批 | `/notices/create`，`NoticeManagementView.vue` |
| `ai-risk-center-desktop.png` | 1536 x 1024 | 四类运营风险、规则证据、解释和人工处置 | `/ai/risks`，`AiRiskView.vue` |
| `ai-approval-audit-desktop.png` | 1536 x 1024 | 提案差异、审批阻断态、执行和运行审计 | `/ai/approvals`、`/ai/audit` |
| `ai-dashboard-mobile.png` | 852 x 1846 | 390 x 844 驾驶舱等比例视觉基线 | `/` 响应式布局 |
| `ai-assistant-mobile.png` | 853 x 1844 | 390 x 844 全屏助手等比例视觉基线；只约束 Sheet | 应用级移动 Sheet |
| `ai-design-system.png` | 1505 x 1045 | 颜色、排版、尺寸、组件和安全状态 | 全局样式与 `components/ai/` |

## 页面合同

### 驾驶舱

- 保留原业务 Dashboard 与固定指标事实，不创建平行首页。
- 桌面保留命令栏、KPI、运营简报、趋势、风险概览和待处理事项。
- 移动端使用统一的 52px 折叠 Sidebar、两列统计卡和单列内容；首屏可见关键风险，无全页横向滚动。
- 所有 AI 待办只进入查看建议或审批，不在 Dashboard 直接执行业务写。

### 全局/上下文助手

- 桌面为约 440-480px 右 Drawer，底层页面保持可见；移动端为 390px 全屏 Sheet。
- 必须支持流式、停止、重试、复制、反馈、引用折叠、访问拒绝和无可靠来源拒答。
- 关闭运行中的助手必须确认停止；关闭后焦点返回触发按钮。
- 引用无权限时不展示正文，输入区持续提示不得输入姓名、学号或手机号。

### 维修分诊

- 左侧列表与筛选、分页和人工处理继续有效；右侧展示选中工单事实和 AI 建议。
- 候选维修员只能来自当前业务可用选项，不得由模型编造 ID。
- 低置信、无候选、已完成、数据变更、无权限或无来源时禁用提案/审批。
- 流程固定为“AI 建议 -> 变更预览 -> 人工审批”。

### 公告起草

- 结构为要点输入、纯文本草稿、内容检查、当前内容/AI 建议 diff 和审批区。
- AI 只能创建草稿，不能发布；人工发布动作必须位于审批和原业务确认之后。
- PII、手机号、脚本样式内容、无可靠来源和无权限均有明确阻断态。

### 风险中心

- 只展示入住、维修、卫生和欠费的确定性运营信号。
- 页面必须显示“规则信号，不代表学生评价”。
- AI 只解释和排序，不自动处罚或修改业务状态；人工时间线 append-only。
- 模型失败时继续显示规则证据并标记降级。

### 审批与审计

- 审批页突出目标、当前值/建议值、影响、权限、引用、as-of、版本/hash 和过期时间。
- `STALE`、`EXPIRED`、低置信、无引用、缺业务权限或审计不可用时禁用批准。
- `APPROVED` 不等于业务成功，只有 execution `SUCCEEDED` 才显示成功。
- 审计页默认只显示脱敏元数据和 hash 链，不回放 message、tool 或 citation 正文。

## 能力与安全边界

1. MySQL、现有业务 Service 和 Sa-Token/RBAC 是事实与授权来源；模型输出只是建议。
2. 模型不直连数据库、不生成或执行 SQL，不调用任意 URL、命令、脚本或文件系统。
3. 首期业务提案仅包括维修指派和公告草稿。
4. 所有业务写固定经过建议、预览、人工审批和现有 Service 执行。
5. 低置信、无来源、来源无权限、数据过期、权限不足、审计不可用时显示安全状态并阻断确认。
6. 不做学生纪律、心理、健康、信用或个体预测画像。
7. 图片中的模型别名、版本、日期、case ID、hash 和数字只表达信息层级，不是实现合同。
8. 模型文本与引用正文只按纯文本渲染，禁止 `v-html`。

## 视觉令牌

| 令牌 | 值 | 用途 |
| --- | --- | --- |
| `--primary` | `#2563EB` | 主按钮、链接、选中态 |
| `--primary-dark` | `#173F97` | 深色主状态 |
| `--sidebar` | `#163B83` | Sidebar 主色 |
| `--sidebar-strong` | `#0F2F6F` | Sidebar 深色层 |
| `--ai-accent` | `#6366F1` | AI 标识和建议；不能代替风险色 |
| `--ai-soft` | `#EEF2FF` | AI 轻背景 |
| `--success` | `#10B981` | 完成、可靠来源、高置信 |
| `--warning` | `#F59E0B` | 待核验、中置信、即将过期 |
| `--danger` | `#EF4444` | 高风险、失败、拒绝 |
| `--info` | `#06B6D4` | 只读提示、数据口径 |
| `--bg` | `#F5F7FA` | 页面背景 |
| `--surface` | `#FFFFFF` | 卡片、Drawer、表格 |
| `--text-title` | `#111827` | 标题 |
| `--text` | `#374151` | 正文 |
| `--text-muted` | `#64748B` | 辅助说明 |
| `--border` | `#E7EDF6` | 分隔线和卡片边框 |

- 字体：`Inter, "PingFang SC", "Microsoft YaHei", system-ui, sans-serif`。
- 页面标题 20-24px/700；卡片标题 15-16px/700；正文 13-14px；辅助信息 12px。
- 桌面 Sidebar 展开 208px、Header 64px、内容间距 18-24px、Drawer 440-480px。
- 移动基线 390 x 844、折叠 Sidebar 52px；触摸目标至少 44 x 44px。
- 卡片圆角 8-12px；保持低阴影，禁止重玻璃拟态、霓虹科幻和无意义动画。

风险和安全状态不能只靠颜色表达，必须同时显示图标、文本标签和可访问名称。

## 共用组件与状态

共用组件至少覆盖命令栏、引用卡、建议卡、提案预览、审批条、运行状态、置信度标签、AI 表格单元格和安全状态。审批条固定表达“AI 建议 -> 变更预览 -> 人工审批”；引用卡只渲染纯文本；表格单元格保持紧凑，长解释进入 Drawer/详情。

| 状态 | 用户可见行为 |
| --- | --- |
| `IDLE` | 可输入或触发分析 |
| `QUEUED / RUNNING` | 显示真实进度，不伪装完成 |
| `STREAMING` | 分块输出并始终提供停止；`aria-live` 不逐 token 轰炸 |
| `SUCCEEDED` | 展示引用、as-of、反馈和后续动作 |
| `DEGRADED` | 展示确定性规则/检索结果和降级原因 |
| `CANCELLED` | 保留已生成内容并允许新建 run |
| `FAILED / TIMED_OUT` | 显示安全错误和重试，不展示堆栈/内部参数 |
| `PENDING_APPROVAL` | 展示完整差异并允许有权用户批准或拒绝 |
| `STALE / EXPIRED` | 禁止批准，要求刷新并重新预览 |
| `NO_PERMISSION` | 说明能力受限，不泄露未授权正文 |
| `NO_GROUNDED_ANSWER` | 明确无可靠来源，不给确定性结论 |

## 候选视觉记录

- `ai-assistant-desktop.png` 与 `candidates/ai-assistant-desktop-candidate-v2.png` 相同；v2 只作为最终稿形成过程的留档，开发与验收只引用最终文件名。
- `candidates/ai-assistant-desktop-candidate-v1.png` 已废弃：不可访问来源的锁定表达不够准确，抽屉底部信息密度失衡，任何实现不得据此回退。
- `candidates/` 仅保存设计决策证据，不属于 9 张最终交付资产，也不是当前视觉合同。

## 验收视口

视觉套件必须覆盖：

- 1920 x 1080
- 1366 x 768
- 1586 x 992
- 1536 x 1024
- 1505 x 1045
- 390 x 844

每个视口都要检查 API、网络、console、page、runtime、布局溢出和 Canvas 非空；精确命令和当前证据见 `plan/ai-verification.md`。
