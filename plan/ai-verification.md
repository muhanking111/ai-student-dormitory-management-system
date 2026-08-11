# AI 验证、评测与发布质量门

> 文档状态：现行唯一验证与验收事实源
> 验收基准：Route A RC 以 2026-08-11 Apache-2.0 公开候选树的新鲜全门、制品和哈希为准，并由不可移动 tag `rc-20260811.1` 绑定最终公开提交；UI 肉眼验收仍以 2026-08-09 Dashboard 圈注整改后的当前实现与同视口 comparison 为准；更早结果仅保留为历史基线
> 适用范围：AI 智能宿舍阶段 0–6 及其生产发布门
> 视觉合同：`design/ai-prototypes/` 中 9 张最终 PNG

## 1. 结论与状态定义

当前结论必须按以下三个状态理解，不能混写：

| 范围 | 状态 | 含义 |
| --- | --- | --- |
| 阶段 0–6 后端/数据/安全能力 | `ENGINEERING COMPLETE` | 控制面、数据合同、安全边界和既有后端证据仍有效；发生相关源码变化后必须重跑 |
| 9 图 UI 高保真落实与前端全量验收 | `COMPLETED` | 2026-08-09 用户反馈已关闭 Dashboard 三项具体差异；当前工程门、治理回归、独立 UI/安全增量、全量 Stage 6 候选链均通过，用户已确认“看现在ui差不多还可以” |
| Route A RC 封版与公开发布 | `IN_PROGRESS` | Stage 0-5 已完成；Apache-2.0 公开候选树的全量工程门、双模式演示、clean clone、新终端、桌面/移动与异常态验收均 PASS；Stage 5.5 正在净化公开历史并完成 remote/tag/Release 绑定 |
| 生产外部依赖与生产演练 | `NOT RUN` | 缺少生产 KMS、外部向量/对象服务、外部审计锚、正式供应商条款及生产灾备等真实环境证据，不能视为通过 |
| 阶段 7 预测模型与学生端 | `OUT OF SCOPE` | 本轮明确不实施，不是失败，也不是待补测项 |

因此：**阶段 0–6 后端控制能力的既有工程结论继续有效；2026-08-09 Dashboard 圈注整改的当前源码工程门、Stage 5 治理回归、两份独立 UI 增量复核、专项安全增量复核和全量 Stage 6 候选链均已通过，全部已知 P0-P3 为零；用户已完成肉眼验收，Stage 6 与 UI 高保真复验完成。** 本轮仍未放行生产环境，任何真实供应商、外部数据服务或生产写执行启用仍必须先关闭本文件列出的全部 `NOT RUN` 发布门。

本文件取代旧的分散评测、框架 Spike 和阶段验收文档。其他文档如与本文件的测试数字、状态或哈希冲突，以本文件及其指向的当前产物为准。

## 2. 当前验收快照

### 2.1 后端、协议与产物

| 验证项 | 当前结果 | 证据或说明 |
| --- | --- | --- |
| 后端 surefire 当前核心套件 | `919/919 PASS` | 2026-08-11 Apache-2.0 公开候选树执行 `mvn -q test` 与 `mvn -q -P ai-coverage verify`；161 reports，`0 failure / 0 error / 0 skipped`。 |
| 登录 CSRF 专项 | `12/12 PASS` | `CsrfProtectionTest`；覆盖旧 Cookie、Origin/Referer、context path 等回归 |
| AI 覆盖率 | line `12756/13622 = 93.64%`；branch `6727/8326 = 80.80%` | 2026-08-11 Apache-2.0 公开候选树执行 `mvn -q -P ai-coverage verify`；line/branch 门均为 80% |
| 生产配置样例契约 | `1/1 PASS` | `ProductionEnvironmentExampleContractTest`；确保 `.env.example` 覆盖当前 AI 生产安全门读取的主要部署输入 |
| 真实 MySQL/Redis | `8/8 PASS` | `AiRealInfrastructureIT 6`、`AiUploadRealInfrastructureIT 1`、`RealInfrastructureIT 1` |
| AI Schema / OpenAPI | `16/16 PASS` | `AiOpenApiContractTest + AiSchemaMigrationInitializerTest`；`55 paths / 60 operations`、`44` 张 `ai_*` 表 |
| 框架兼容 | `12/12 PASS` | `FrameworkCompatibilityTest + LangChain4jOfflineCompatibilityTest + SpringAiOpenAiStubContractTest`；runtime 仅保留 Spring AI 1.1.8，LangChain4j 只在 test scope |
| 真实模型合同 | `NOT RUN / NOT APPROVED` | 本轮 Stage 6 未运行 `RealModelContractIT`；历史 DeepSeek 只读合同不绑定当前候选，也不能替代生产放行 |
| 独立安全复核 | `P0=0 / P1=0 / P2=0 / P3=0` | 只读复核确认视觉 fixture 未进入生产路径，RBAC、CSRF、SSE、审批、step-up、引用 ACL、PII 与状态机未被本轮整改弱化；该结论不等同重新审计全部后端 |
| 供应链 | 120 个 Maven runtime 依赖、0 findings | 2026-08-11 OSV 官方 `querybatch`；Netty `4.1.135.Final` 的 `GHSA-558v-64gr-wgg4` 阻断已通过升级到 `4.1.136.Final` 关闭，并对新候选重跑全部 Stage 3 门 |
| 候选 JAR | `58,030,252` bytes | `backend/target/student-dormitory-management-system-0.1.0.jar`；Apache-2.0 公开候选树的 package 产物 |
| JAR SHA-256 | `D6983CEE32D120863E2198AF0F5302A93AE7113F55021D95AD7A99CD9E5457B8` | 2026-08-11 当前候选产物复核 |
| Route A 本地演示闭环 | `PASS` | 2026-08-11：`demo-readonly` 与 `demo-approval` 均完成启动、健康、停止、精确 reset、reset 后重启和再次健康/停止；provider 均为 `fake`，写执行分别为 `false/true`，数据库分别为 `student_dormitory_readonly_demo` / `student_dormitory_approval_demo`，Redis DB 分别为 12/13；错误 reset 令牌被拒绝，原有 `3306/6379` 未被停止 |
| Route A clean clone 演练 | `PASS` | 2026-08-11 从 Stage 5 交付树的临时本地候选克隆到全新 TEMP 目录；初始无 `node_modules`、`backend/target`、`.demo` 或 `.planning`，新 PowerShell 无 Profile、清理项目环境变量后以 `-InstallDependencies` 启动；完整 health PASS，`1366x768` 与 `390x844` 的 Dashboard/维修/公告/风险/审批/审计均 0 横向溢出，移动可见按钮无小于 `44x44`，助手为 `390x844` 全屏 |
| Route A 异常态演练 | `PASS` | Playwright Stage 5 治理 `9/9 PASS`（AI client 关闭、风险/审批/审计 loading/empty/error/degraded/stale/expired/no-permission、200% 与 reduced motion）；Assistant 撤权终态 `1/1 PASS`；真实停止 clean-demo 后端时前端保留并返回 `/login`，明确显示后端不可用，随后 stop 安全关闭其余自有资源 |

上述覆盖率、测试数量和 JAR 哈希绑定到 2026-08-11 Apache-2.0 公开候选树，并由 tag `rc-20260811.1` 固定最终公开提交；后续若应用源码、依赖、schema、OpenAPI、构建或覆盖率配置发生变化，必须生成新候选并重跑受影响全门。

### 2.2 前端、E2E 与视觉

| 验证项 | 当前结果 | 证据或说明 |
| --- | --- | --- |
| Vitest | `46` 个测试文件，`519/519 PASS` | 2026-08-11 Apache-2.0 公开候选树执行 `npm run test`；既存 `a-modal` 测试桩 warning 保留在 stderr，不构成失败 |
| Stage 6 当前工程门 | `COMPLETED` | 当前源码已通过全量 Vitest、typecheck、build、普通 E2E、独立 accessibility/formal visual、十组 comparison、Impeccable detector、Assessment A/B 与专项安全终审；用户已确认“看现在ui差不多还可以” |
| 前端覆盖率 | statements `87.81%`、branches `80.17%`、functions `90.66%`、lines `91.71%` | 2026-08-11 Apache-2.0 公开候选树执行 `npm run test:coverage`；`46 files / 519 tests PASS`，全局四项门均为 80% |
| TypeScript | `PASS`，0 个类型错误 | 2026-08-11 Apache-2.0 公开候选树执行 `npm run typecheck`，退出 `0` |
| 构建 | `PASS` | 2026-08-11 Apache-2.0 公开候选树执行 `npm run build`，退出 `0`；Vite 8.1.4，`3863 modules transformed`；dist `74` files / `2,136,572` bytes，索引 SHA-256 `1108939232ADD39167E9D068F2CF4C2A54CD7806844B95CC2CDBCDFDD513C5DB` |
| Lint | `PASS`，`0 errors / 2706 warnings / 0 fatal` | 2026-08-11 新增 ESLint 10 flat config，检查 177 files；warning 为未隐藏的 Vue 既有模板风格建议，无全局 disable、无业务源码排除 |
| 前端依赖审计 | `0 vulnerabilities` | 2026-08-11 Apache-2.0 公开候选树执行 `npm audit --audit-level=high` 退出 `0` |
| 普通 Playwright | `72/72 PASS` | 2026-08-11 Apache-2.0 公开候选树新鲜执行；`0 skipped / 0 unexpected / 0 flaky`。Stage 6 专用合同仍只由各自独立配置执行，没有删除或弱化断言 |
| 公告 proposal 完整性专项 | `16/16 PASS` | 覆盖生成后修改、请求期间修改、离开重入、重复生成和提交期间失效 |
| AI-live | 当前候选 `1/1 PASS`，26.1 秒 | 2026-08-11 全新 `student_dormitory_rc_20260811_final_ai_live_e2e`、`5556/8456`、Redis DB 6、唯一 key prefix、确定性 Fake provider、业务写执行关闭 |
| AI-live 网络合同 | AI request/response `44/44`，原业务写 `0` | `network-evidence.json` SHA-256 `13F818BAAB0CA0981B94DDABA1C32022EB16133B5F5B280C64DCD85DB76F8C38`；9 条受控关闭均有成功响应；成功截图 SHA-256 `D496B36CAA97E0B58DAD34B3C818748BFC8067B1E714E61D310CF8E105D8D831` |
| 六视口正式门 / 9 图终检 | `COMPLETED` | Apache-2.0 公开候选树的 `final-rc-20260811-visual`：`132/132` 路由、`19/19` Assistant、`9/9` prototype capture、`1/1` 画廊、`159/159` PNG；源码/原型稳定性违规、API/request/console/page/runtime errors 与 `businessWrites` 均为 0；manifest SHA-256 `42851A03E777281309A1064D8EC09ABABC8063484B6FE80379C4ACD2601CD01C`。2026-08-09 同视口人工验收仍是质感结论依据 |
| 当前同视口终检 | `10/10` comparison 已人工逐张复核 | `stage6-comparison-20260809-dashboard-feedback-l` 含 10 metrics、30 PNG、`60/60` 文件绑定；移动两组 `cover-top`，其余同视口 `stretch`，Assistant 两组均绑定真实 streaming。差异率仅用于定位热区，不作为自动通过阈值 |
| 阶段 1R 共享壳层收敛 | `PASS`，六视口 `1/1`（8.6 分钟） | 隔离 5215/8115；132 页面、132 layout、132 shell、6/6 Canvas、3 助手、10 captures；错误集合全空、最大溢出 0px；manifest SHA-256 `D0DE75E786B202F84BE922D709E24856A4B3B919E54413F7901D1DED2EF326D7`；仅关闭共享壳层，不代表内容态高保真验收 |
| 阶段 4 风险/审批/审计内容态 | `PASS`，真实服务 `1/1`（7.9 分钟） | 隔离 `5220/8120`；MySQL `student_dormitory_stage4_current_e2e`、Redis DB 14 / key prefix `ai-live:stage4:8120:20260721-retry1`；9 signals/cases，风险表 5 行完整可见、分页/处置底边 `1002/990 <= 1008`，审批操作底边 `992`，审计 7 类事实/4 步时间线；真实浏览器确认 `actionType=NOTICE_CREATE_DRAFT` 服务端筛选及清除筛选请求；API/console/page/request 错误均为 0；前端治理聚焦 `10 files / 116 tests`，风险几何修复后子集 `2 files / 13 tests`，typecheck/build PASS（Vite 8.1.4，3859 modules transformed）；审批后端扩大回归 `65/65`（含 `AiGovernanceApiTest 9/9`）；manifest SHA-256 `031A130DE4BAAA22EA99E30B78CBA881C61D6C62B389F3C7CF770EAC99BF5925` |
| 阶段 5 移动端与可访问性 | 当前源码 `1/1 PASS` | 2026-07-27 全新 `student_dormitory_stage6_final_stage5_20260727_d_e2e`、`5344/8244`、Redis DB 15；`15` 个 layout、`15` 个 touch、最小触控 `44x44`、最大溢出 `0px`、`runtimeIssues=[]`、`violations=[]`；三图已逐张复核，manifest SHA-256 `FB576DB5EE7DCA9A8493B40DA3D1C4BC3884C47F7E72AA52D30F38ED5085D4E2` |
| 阶段 2 Dashboard/助手真实内容态 | `1/1 PASS`，23.3 秒 | `npx playwright test -c playwright.ai-live-stage2.config.ts`；`frontend/test-results/visual/stage2/` 下 4 张截图与 `manifest.json`；SHA-256 `9F66A81D422D212BCC96BD2062B49A17127B8BBF99D1471760AD51815D285637` |
| 阶段 3 维修/公告真实内容态 | `1/1 PASS`，17.0 秒 | `npx playwright test -c playwright.ai-live-stage3.config.ts`；`frontend/test-results/visual/stage3/` 下 2 张桌面截图与 `manifest.json`；SHA-256 `B6D77C79F23B705496A65F1A92DF2C542B4D2D1F5C606C039C9658561679E0F2` |
| UI 纠偏 Stage 1 Token/共享壳层 | `COMPLETED`；聚焦 Vitest `7 files / 59 tests`、Edge `8/8`、typecheck/build PASS | 最新 `stage1-shell-20260727-o`；Vite 8.1.4、3860 modules；Impeccable detector `[]`；双独立终审 P0-P3 全零。画廊/并排/差分 SHA-256 分别为 `3BCA2972664F4B16957FAC3C44F34D037DD5ED1BB247E7191BD8D7813318BD26`、`73D0354ABAFC37AD61074D79C9FBC9736D708BA8C28B2EF72BB844D2EA0E9798`、`55805CDBE8D8FAF7B2C85D425FE20C21A3F3F072A940CFEECD956892FAB2E0CD`；总体 UI 仍在 Stage 2-6 |
| UI 纠偏 Stage 2 Assistant | `COMPLETED`；聚焦 Vitest `8 files / 130 tests`、fixture Edge `5/5`、真实后端 `1/1`、typecheck/build PASS | 最新 `stage2-assistant-20260728-d`；19 fixture、4 live、六视口、200%、computed、并排/差分；fixture/live manifest SHA-256 为 `D5693C3416C0781C0ECC319C3B09D2EAA0CD106FE92F74F6E0FFC483C167BB98` / `FD6D7D11A427441770958416F9863A95FB253F3F585EDD5A3F9252811CBA4AA7`；Impeccable detector `[]`，双独立 UI 终审与专项安全终审均 P0-P3 全零；在 Stage 2 完成时总体 UI 转入 Stage 3-6 |
| UI 纠偏 Stage 3 Dashboard | `COMPLETED`；fixture `8/8`、live `1/1`、Vitest `94 suites / 438 tests`、typecheck/build/comparison PASS | 最新 `stage3-dashboard-20260801-x` / `x-live`；29 fixture、2 live、六视口、200%、computed、正确的 390x844 `cover-top` 并排/差分；fixture/live manifest SHA-256 `C377AE1AF593D9D9A80A68512E15E96D837B065EDA0E32AB3409A990FB8AA4FF` / `A48B15AAA0841070CFCDE15346ECA472A7FE6EAE8B1635762D9C27B371C1D721`；detector `[]`、完整性/敏感扫描通过，双独立 UI 与专项安全终审均 P0-P3 全零；总体 UI 进入 Stage 4-6 |
| UI 纠偏 Stage 4 维修/公告 | `COMPLETED`；fixture `8/8`、live `1/1`、移动真实链 `1/1`、Vitest `43 files / 454 tests`、typecheck/build/comparison PASS | 唯一候选 `stage4-repair-notice-20260801-k` / `k-live`；56 fixture、六视口、200%、computed、维修 `1586x992` 与公告 `1536x1024` 同视口比较；fixture/live manifest SHA-256 `E93977973C6CA4FE985AF22DD5C0C2ABC46589C77844F89A899181CA2EECA902` / `1696958B221387C171AD3A054A7E83634314A1ECAD7478A06324F784F27ED214`；detector `[]`、源码绑定/敏感扫描通过，双独立 UI 与专项安全终审均 P0-P3 全零；总体 UI 进入 Stage 5-6 |
| UI 纠偏 Stage 5 风险/审批/审计 | `COMPLETED`；fixture `9/9`、live `2/2`、Vitest `44 files / 470 tests`、typecheck/build、普通 E2E `72/72`、comparison PASS | 唯一候选 `stage5-governance-20260802-ak`；62 fixture、六视口、200%、22 条 computed、风险/审批 `1536x1024` 同视口比较与审计 8 类语义映射；fixture/live/security manifest SHA-256 `6C81B62A98BD36AF4ECA8DD46A6D672C7713343A5A023FBAB7AE96FE76E39A02` / `40E59B00A89F2DF7DE95B7E0BE33493FC1F2621DE83CCDCC90E0C48A63D4D7C2` / `E49A4BB774F3422ECD2EC2FDD3CD760F6479E36D87231B986FBC0BB77873D0D7`；detector `[]`、源码/截图绑定与扫描通过，双独立 UI 及专项安全终审均 P0-P3 全零；总体 UI 进入 Stage 6 |
| UI 纠偏 Stage 6 可访问性 | `1/1 PASS`；`44/44` 路由/profile | `stage6-accessibility-20260809-dashboard-feedback-l-accessibility-accessibility`：22 路由 x 200%/触控两 profile，12 PNG、666 源码、12 截图、3 JSON 制品，共 `681/681` 绑定；`violations=[]`、`exceptions=[]`、`businessWrites=[]`，manifest SHA-256 `B7EE785FBCF38AFC10C686D891049CABABB815D3F3773994C03768216A878918` |
| UI 纠偏 Stage 6 正式 visual | `1/1 PASS`；`159/159` PNG | `visual-stage6-20260809-dashboard-feedback-l`：132 路由、19 Assistant、9 capture、1 画廊；起止源码/原型、截图与制品共 `1567/1567` 绑定，146/146 Fake/read-only readiness，错误集合与 `businessWrites` 全空，manifest SHA-256 `5A72A1171938701498A9999920EAFB1357A655FCDE911B4927E6F5B791589414` |
| UI 纠偏 Stage 6 comparison | `10 metrics / 30 PNG / 60 bindings PASS` | `stage6-comparison-20260809-dashboard-feedback-l`；Dashboard 桌面/移动、Assistant 桌面/移动、维修、公告、风险、审批、审计和 Design System 均完成同视口 side-by-side/diff，根任务逐张 `view_image` 未发现新 P0-P3 |
| UI 纠偏 Stage 6 独立终审 | `Assessment A P0-P3=0；Assessment B P0-P3=0；专项安全 P0-P3=0` | 当前 review sheets 为 `stage6-review-sheets-20260809-dashboard-feedback-l`，5/5 联系表、19/19 Assistant；专项安全与脱敏外发包均通过，且用户已完成最终肉眼验收 |
| 视觉证据生产隔离 | `PASS` | 误设视觉 flag 的生产 build `dist-stage6-prod-misflag-20260809-dashboard-feedback-l` 共 75 文件；15 类禁用语料和 `ai-demo|visual-evidence|DesignSystemGallery` 三类禁用文件名命中均为 0 |
| 视觉错误集合 | 全部为空，最大横向溢出 0px | `apiErrors`、`apiRequestFailures`、`consoleErrors`、`pageErrors`、`runtimeErrors`；这些指标不覆盖信息密度、配色、完整状态和真实动作 |
| 独立 Sidebar/字体 UI reviewer | Stage 1 终审 `P0=0 / P1=0 / P2=0 / P3=0` | `#000c17`、父组、字重/对比度、弹层互斥和全键盘/焦点问题已关闭；Edge popup 焦点外框对比约 6.22:1 |
| 独立 UI reviewer | Stage 1-6 两份独立 UI 终审均全零 | Stage 6 已人工查看 9 原型、十组 side-by-side、19 Assistant、5 张联系表和正式 capture；工程门完成后已进入用户验收 |
| 独立 security-reviewer | Stage 6 增量终审 `P0=0 / P1=0 / P2=0 / P3=0` | 编译期视觉证据隔离、误设生产构建、Fake provider、写关闭和既有 RBAC/CSRF/SSE/引用 ACL/审批/审计/PII/状态机边界已复核；`stage6-external-evidence-20260809-dashboard-feedback-l` 扫描违规为 0，不等同重新审计全部后端 |
| 最终本地预览 URL | `http://127.0.0.1:5174/`；后端健康检查 `http://127.0.0.1:8201/api/health = UP` | 专用 `_e2e` MySQL、Redis DB 14、Fake provider、写执行关闭；健康/登录/readiness `200`，Dashboard 与公告命令 `202`，SSE `200`。390x844 品牌为 `A`、品牌内房屋图标为 0、两项 14px 图例不重叠，`pendingTop=810.59375`、`documentHeight=1233`；6 张预览截图错误集合为空。验证报告 `stage6-final-preview-verification-20260809-dashboard-feedback-l.json` |
| 助手桌面原型版本 | 最终文件等同 candidate v2 | `ai-assistant-desktop.png` 与 candidate v2 的 SHA-256 均为 `38511AB2058E2E51FC76E4F37BD39D1E097E7C62DD874A6DFCC1C4188CAE1083`；candidate v1 为 `15F9E34B78C41797BF731B51933822A37150B1038E13534542E8B4D901F249A9`，仅保留为废弃决策证据 |

六个正式视口为：1920×1080、1366×768、1586×992、1536×1024、1505×1045、390×844。9 张最终 PNG 的原生尺寸、逻辑视口、路由和关键表面已写入 `visual-stage6-20260809-dashboard-feedback-l` manifest；单纯“页面能打开”、无溢出或 Canvas 非空仍不能替代原型对齐。本轮用户已完成肉眼确认，Stage 6/UI 高保真为 `COMPLETED`。

2026-07-18 至 2026-07-27 的既有复核数字保留为历史工程/结构证据，但不再作为 UI 高保真完成依据。真实供应商合同仍保持 `NOT RUN / NOT APPROVED`，不得借视觉纠偏之名调用真实模型。

### 2.2.1 用户现场验收否决与新差异证据

- 用户现场截图确认展开二级菜单出现与蓝色 Sidebar 割裂的近黑块，Sidebar 字体偏灰、偏细；Assistant 的背景层次、字体、图标、回答卡、状态条、来源卡和输入区明显低于原型质感。
- 2026-07-27 使用当前 `5301/8201` 与 Microsoft Edge 真实登录复现：`.side-menu .ant-menu-sub.ant-menu-inline` 的 computed background 为 `rgb(0, 12, 23)`；一级/二级文字为 `rgba(255, 255, 255, 0.65)`，实际菜单字体栈由 Ant Design 覆盖为系统 UI 栈，子项字重为 `550`。
- Assistant 独立审查确认组件自建 61 种原始颜色、缺设计系统状态画廊、多个 10-11px 辅助文字和四处低于 4.5:1 的必要文字对比度；这些问题均不在旧 manifest 的断言范围内。
- 新证据和差异矩阵位于 `.planning/20260727-ui-prototype-texture-reassessment/artifacts/`。完成新实现前，旧 `f` 批次仅为结构差异起点。
- Stage 1 已以最新 `o` 批次关闭共享壳层、设计 Token 和状态画廊问题；Stage 2 已以最新 `d` 批次关闭 Assistant 桌面/移动；Stage 3 已以最新 `x/x-live` 批次关闭 Dashboard 桌面/移动；Stage 4 已以最新 `k/k-live` 批次关闭维修分诊与公告起草；Stage 5 已以最新 `ak` fixture 和 `ah-live` 安全链关闭风险、审批与审计。Stage 6 最终 `ew/ex/ey/ez/fa/fb/fc` 仅保留为历史基线；当前 `20260809-dashboard-feedback-l` 已关闭自动化、制品、可访问性、同视口比较、完整性、Assessment A/B、专项安全工程门和用户肉眼验收。

### 2.3 当前证据文件

- Route A Stage 5.5 当前树全门：`.planning/20260811-release-candidate-delivery/artifacts/final-frontend-*.json` / `*.log`、`final-backend-*.log` 与最终制品哈希；`.planning/` 只保存本地证据，不进入公开仓库或 Release 附件。
- 公开范围审计：`.planning/20260811-release-candidate-delivery/artifacts/stage55-public-audit.json`、`stage55-dependency-license-audit.json`、`stage55-markdown-link-audit.json` 和净化历史后的公开树索引。
- 历史 Stage 3 manifest：`.planning/20260811-release-candidate-delivery/artifacts/stage3-ca297b6-rc-manifest.json` 仅保留为封版过程基线，不再作为 Apache-2.0 最终公开候选。

- 后端测试：`backend/target/surefire-reports/`
- 后端 AI 会话对象授权证据：`.planning/20260718-ai-prototype-full-fidelity/artifacts/backend-ai-conversation-security-20260722.md`
- AI 覆盖率：`backend/target/site/jacoco/jacoco.xml`
- 前端覆盖率：`frontend/coverage/coverage-final.json`、`frontend/coverage/clover.xml`
- AI-live：`frontend/test-results/ai-live-run/`
- AI-live 成功截图与网络摘要：`frontend/test-results/ai-live-run/ai-live-contract-真实-HttpAi-03de2-史-反馈-撤权、公告提案与知识摄取，且不绕过审批写业务-ai-live-chromium/`
- 普通 E2E：`frontend/test-results/e2e-stage6-final-20260727.json`
- 历史视觉差异基线：`frontend/test-results/visual-final-20260727-f/manifest.json`、同目录截图及 `frontend/test-results/visual-final-20260727-f-playwright.json`
- 当前 UI 纠偏计划与差异矩阵：`.planning/20260727-ui-prototype-texture-reassessment/`
- UI 纠偏 Stage 1：`frontend/test-results/stage1-shell-20260727-o/` 与 `.planning/20260727-ui-prototype-texture-reassessment/artifacts/stage1-final-review-evidence.md`
- UI 纠偏 Stage 5：`frontend/test-results/stage5-governance-20260802-ak/`、`frontend/test-results/stage5-governance-20260802-ah-live/` 与 `.planning/20260727-ui-prototype-texture-reassessment/artifacts/stage5-final-review-evidence.md`
- UI 纠偏 Stage 6：`frontend/dist-stage6-prod-misflag-20260804-ew/`、`frontend/test-results/stage6-accessibility-20260804-ex-accessibility/`、`frontend/test-results/visual-stage6-20260804-ey/`、`frontend/test-results/stage6-comparison-20260804-ez/`、`frontend/test-results/stage6-review-sheets-20260804-fa/`、`frontend/test-results/stage6-external-evidence-20260804-fb/`、`.planning/20260727-ui-prototype-texture-reassessment/artifacts/stage6-candidate-integrity-20260804-fc.json` 与 `stage6-final-review-evidence.md`
- 最终预览：`.planning/20260727-ui-prototype-texture-reassessment/artifacts/stage6-final-preview-verification.json` 与六张 `stage6-final-preview-*.png`
- 阶段 2 视觉验收：`frontend/test-results/visual/stage2/manifest.json` 及同目录 4 张截图
- 阶段 3 视觉验收：`frontend/test-results/visual/stage3/manifest.json` 及同目录 2 张截图
- 供应链：`backend/target/osv-runtime-summary.json`、`backend/target/osv-runtime-querybatch-response.json`
- 候选产物：`backend/target/student-dormitory-management-system-0.1.0.jar`

当前仓库已建立可用 Git 身份；Route A 最终公开提交由不可移动 tag `rc-20260811.1`、GitHub Release target 和远程默认分支共同绑定。`.planning/` 中的详细运行证据不公开，公开材料只保留脱敏结论、命令范围和制品 SHA-256。

## 3. 阶段 0–6 验收矩阵

| 阶段 | 已验收能力 | 核心验收点 |
| --- | --- | --- |
| 0 技术与治理门 | 框架选择、自有端口、默认关闭、安全基线 | Spring AI 1.1.8 为运行时适配器；LangChain4j 1.12.1 仅作 test-scope 对照；当前真实模型合同 `NOT RUN / NOT APPROVED` |
| 1 AI 控制面 | 44 表、14 权限、7 个固定工具、REST/SSE、预算、审计、Outbox、幂等、开关 | OpenAPI 55/60；空权限、未知工具、预算不足和审计异常均 fail-closed |
| 2 知识与助手 | 隔离上传、对象版本、ACL、摄取、注入检测、PII、引用、拒答 | ACL 默认拒绝；L2 脱敏、L3 阻断；无授权正文和虚构 citation 不可达 |
| 3 AI Dashboard | 固定指标目录和确定性查询 | 仅允许 6 个版本化指标；模型不能生成或执行 SQL；数值必须与确定性 executor 一致 |
| 4 提案、审批与执行 | proposal、preview、hash/snapshot、step-up、唯一 lease、维修与公告 | 仅允许 `NOTICE_CREATE_DRAFT`、`REPAIR_ASSIGN`；审批前后重授权；最后边界 Kill Switch；原业务 Service 至多执行一次 |
| 5 风险中心 | 确定性信号、AI 解释、人工处置、幂等事件 | AI 不生成学生纪律、心理、健康或信用画像；人工结论不可被模型覆盖 |
| 6 强化与运行 | 超时、重试、熔断、离线 eval、红队、观测、持久 Kill Switch、对账和运行手册 | 未知写结果进入 `NEEDS_REVIEW`，不自动重放；运行时开关可跨重启/实例收窄能力 |
| UI 高保真复验 | 设计系统、Dashboard/助手、维修/公告、风险/审批/审计、移动端与可访问性 | `COMPLETED`；最终六视口、逐元素差异、完整性、Assessment A/B、专项安全和用户肉眼验收均已关闭 |

阶段完成只证明约定工程范围已闭环，不代表生产外部服务、生产性能、数据条款或灾备演练已经通过。

## 4. 框架兼容性 Spike 结论

### 4.1 锁定结果

- 基线：Spring Boot 3.5.16、Java 21、Spring Framework 6.2.19、Jackson 2.21.5、SLF4J 2.0.18。
- 首选：Spring AI 1.1.8，以 compile/runtime scope 提供 OpenAI-compatible adapter，但默认不创建真实 provider 调用。
- 回退对照：LangChain4j 1.12.1，仅进入 test scope，不进入候选运行时产物。
- 业务层只依赖项目自有 `ModelGateway`、`EmbeddingGateway`、`VectorIndexPort`、`ObjectStoragePort` 等端口；第三方框架类型不得越过 infrastructure adapter。
- 默认配置保持 `dormitory.ai.enabled=false`、`provider-active=none`、`write-execution-enabled=false`。

### 4.2 已执行合同

| 合同面 | Spring AI 1.1.8 | LangChain4j 1.12.1 | 项目边界 |
| --- | --- | --- | --- |
| complete | 本地 OpenAI-compatible HTTP Stub 实际执行 | in-process `ChatModel` 同题执行 | 映射为项目 `ModelGateway` |
| stream/cancel | SSE 中文 delta、usage、取消和唯一终态 | handler 顺序、usage 与终态 | 项目 `AiEventStream` 统一语义 |
| tools | 显式固定工具；provider 返回不直接执行 | 固定 `ToolSpecification` | 只接受版本化 `ToolCatalog` allowlist |
| structured output | JSON Schema 生成、解析与非法输出失败 | `ResponseFormat` + `JsonSchema` | 框架 Schema 类型不外泄 |
| usage/observation | provider usage、MySQL ledger、Micrometer | token usage、listener metadata | 日志默认不记录 prompt/tool 明文 |

基础聚焦合同为 `21/21 PASS`，框架同题合同为 `10/10 PASS`。DeepSeek `deepseek-v4-flash` 真实合同为 `5/5 PASS`。真实模型测试使用临时进程环境变量，凭证没有写入仓库、日志或浏览器 artifact。

若未来真实模型合同通过，也只证明当次适配器的网络与协议行为；当前候选未运行该合同，供应商正式留存、删除、区域、数据处理条款和生产价格校准均为 `NOT RUN / NOT APPROVED`。

### 4.3 框架回归命令

在 `backend/` 下执行：

```powershell
mvn -o "-Dtest=ModelGatewayContractTest,ToolCatalogTest,ActorDescriptorTest,PortBoundaryContractTest,PortInputValidationTest,FrameworkCompatibilityTest,AiPropertiesTest,DeterministicFakeEmbeddingGatewayTest" test
mvn -o "-Dtest=FrameworkCompatibilityTest,LangChain4jOfflineCompatibilityTest,SpringAiOpenAiStubContractTest" test
mvn -o dependency:tree "-Dscope=runtime" "-Dincludes=org.springframework.ai:*,dev.langchain4j:*"
```

预期运行时依赖包含 Spring AI，不包含 LangChain4j。若框架合同失败，只能替换 infrastructure adapter，不能连带改写领域状态机、API、数据库或前端协议来规避失败。

## 5. 测试策略

### 5.1 TDD 顺序

每个新能力或修复必须按以下顺序推进：

1. 写用户旅程、失败旅程和安全不变量。
2. 先写领域/策略单测并确认因缺少实现而失败。
3. 增加 Service、MockMvc、adapter contract 和前端 store/API 测试。
4. 实现满足最小确定性测试集。
5. 增加真实 MySQL/Redis 与 Mock Playwright 回归。
6. 对需要外部行为的部分增加真实模型合同、离线 eval 和红队。
7. 重构后复跑全量质量门，生成新候选证据。

禁止先写 prompt、手工点通页面，再以成功截图反推实现完成。prompt、model、retrieval、tool policy 或 dataset 变化都必须产生新的 eval run。

### 5.2 分层职责

| 层级 | 必须证明的内容 |
| --- | --- |
| 领域与纯函数 | 状态机合法/非法迁移、CAS/version、canonical hash、幂等、配额、权限、ACL、PII、工具 allowlist、outbox、actor 约束；不连接网络或真实基础设施 |
| Service/MockMvc | 401/403/404/409/422/429/503 语义、201 Location、202 run/job、SSE 回放/取消、owner/ACL、审批/拒绝/过期、请求丢失重试、CSRF/Origin、审计拒绝事件和下游调用次数 |
| 真实 MySQL/Redis | 表/列/FK/索引、事务、微秒精度、并发审批唯一执行、预算原子预留、Outbox 锁、审计链、上传固定对象版本、Redis 降级不破坏 MySQL 事实 |
| Adapter 合同 | model complete/stream/cancel/tool/schema/usage；向量 ACL/upsert/delete/rebuild；对象 checksum/version/stream/delete；错误码与敏感信息隔离 |
| 前端 Vitest | API 契约、SSE 跨 chunk/UTF-8/重连/取消、Store 状态、401 清理、proposal 失效、纯文本渲染、页面卸载清理敏感内存 |
| Mock Playwright | 权限旅程、引用、Dashboard、维修/公告 proposal、审批冲突、异常与降级、注入/XSS、键盘、移动端；禁止固定 sleep 和 CSS class 定位 |
| AI-live | 真实 Spring Boot/MySQL/Redis/HTTP/SSE/事务和页面联动；provider 可使用确定性 Fake，不能冒充真实模型证据 |
| 六视口视觉 | 22 路由、9 张 PNG 合同、无溢出/遮挡/空白、Canvas 非空、运行时错误为空；同时提供同尺度原型对照、逐元素计算样式、状态画廊、独立 UI assessment 和用户验收 |
| 真实模型 | 候选 provider/model alias 的 complete、stream、cancel、tool/schema、usage、超时与错误映射；使用合成数据和独立预算 |
| 离线 eval/红队 | 版本化数据集、确定性指标、PII canary、权限和工具零容忍、失败分类和人工复核 |

### 5.3 不可弱化的安全断言

- 空 ACL、未知权限、未知工具、未知 actor、未知对象版本和审计不可用均默认拒绝。
- `SERVICE`、`MODEL`、`SYSTEM` 不能成为业务写执行人。
- 模型不能访问任意 SQL、URL、HTTP、Shell、脚本、文件系统、反射、Bean 或隐藏工具。
- proposal 必须绑定 id、version、payload hash、business snapshot hash、审批者权限、step-up 和唯一 execution lease。
- 任何异步 worker 在 provider egress 或业务写之前都要重新检查权限和 MASTER/CAPABILITY/PROVIDER Kill Switch。
- 真实基础设施测试不得静默 skip；缺少环境时报告为 `NOT RUN`。
- 浏览器 artifact、日志和供应商合同报告只能保存脱敏 hash、usage、latency、finish reason 和结构化断言。

## 6. 离线评测与红队合同

### 6.1 数据集

版本化数据集位于 `backend/src/main/resources/ai/eval/`，至少覆盖 knowledge、dashboard、repair、notice、risk 和 security-redteam。每条 case 必须包含稳定 `caseKey`、能力、输入/fixture、预期工具、引用、禁用模式、安全结果、标签和 `datasetVersion`。

要求：

- 仅使用合成数据或经书面批准的去标识数据，并放置明显虚构的 PII canary。
- manifest 保存文件 hash、创建/审核人、来源、许可、分类和变更说明。
- golden、challenge、security-redteam 分层；不能直接修改失败样本的期望来制造通过。
- 报告绑定 prompt/model/index/tool/redaction/pricing/dataset version 和构建来源。
- 失败样本进入人工 triage，归因到模型、检索、数据、Schema、权限、评测器或产品歧义。

### 6.2 程序化优先

- Schema、enum、hash、权限、citation target、数值、tool name/arguments、状态机和 PII canary 使用确定性断言。
- 检索用标注 relevant chunk 计算 Recall/nDCG；Dashboard 直接比较结构化 intent 与 executor result。
- LLM-as-judge 只可补充评估表达清晰度、引用支持度和公告可读性，必须记录 judge model/prompt/temperature/input hash/reason。
- judge 不得评判或豁免权限、PII、SQL、数值、状态机和业务执行正确性。

### 6.3 固定零容忍门

| 指标 | 发布门 |
| --- | --- |
| 未授权业务执行 | 0 |
| 跨 ACL citation/retrieval | 0 |
| L3 密钥、凭证或敏感数据外发 | 0 |
| 任意 SQL/Shell/URL/隐藏工具成功调用 | 0 |
| proposal hash/snapshot 不匹配仍执行 | 0 |
| Dashboard 支持指标的数值与确定性 executor 不一致 | 0 |

任何零容忍项出现 1 次，立即关闭对应 capability，并保留失败 run、eval、audit 和 artifact。

### 6.4 建议初始效果门

以下数值是发布数据校准的初始门，不代表本轮已经取得生产质量结论：

| 能力 | 指标 | 初始门 |
| --- | --- | --- |
| RAG 检索 | Recall@5；nDCG@10；无关上下文比例 | ≥0.85；≥0.80；≤0.20 |
| 知识回答 | citation validity；grounded claim precision；有答案覆盖 | 100%；≥0.95；≥0.90 |
| 无依据处理 | 无答案/无权限时安全拒答率 | ≥0.98 |
| Dashboard | intent exact match；口径引用；unsupported 安全拒答 | ≥0.95；100%；≥0.98 |
| 维修分诊 | category macro-F1；高紧急度 recall；必需补充信息命中 | ≥0.80；≥0.90；≥0.80 |
| 公告草稿 | Schema 合法率；人工少量修改可用率 | 100%；≥0.80 |
| 风险中心 | 规则证据完整率；人工确认 precision；重复案例率 | 100%；≥0.80；≤0.05 |
| 工具 | allowed tool/arguments exact match；不必要工具率 | ≥0.98；≤0.02 |
| 性能 | 首 token p95；短请求完成 p95；read tool p95 | ≤5s；≤30s；≤2s |
| 稳定性 | 成功率；SSE 可恢复断线率 | ≥99%；≥99% |
| 成本 | 单能力 p50/p95 | 不超过产品批准预算，数值待真实账单校准 |

阈值未达到时不能通过更换 judge 豁免；必须记录根因、风险、批准人和例外到期日。

## 7. 可复现质量门

### 7.1 后端

在仓库根目录执行：

```powershell
cd backend
mvn -q test
mvn -q "-Dtest=CsrfProtectionTest" test
mvn -q -P ai-coverage verify
mvn -q "-Dtest=RealInfrastructureIT,AiRealInfrastructureIT,AiUploadRealInfrastructureIT" test
mvn -q "-Dtest=AiOpenApiContractTest,AiSchemaMigrationInitializerTest" test
mvn -q "-Dtest=FrameworkCompatibilityTest,LangChain4jOfflineCompatibilityTest,SpringAiOpenAiStubContractTest" test
mvn -q -DskipTests package
Get-FileHash -Algorithm SHA256 .\target\student-dormitory-management-system-0.1.0.jar
```

真实供应商合同需要临时凭证、独立预算和显式授权；配置齐全时执行：

```powershell
cd backend
mvn "-Dtest=RealModelContractIT" test
```

缺少真实供应商配置时必须写 `NOT RUN`，不能把 JUnit 条件跳过计为 `PASS`。

### 7.2 前端与真实服务

```powershell
cd frontend
npm run test:coverage
npm run typecheck
npm run build
npm run e2e
npm run e2e:ai-live
npm run e2e:visual
Get-FileHash -Algorithm SHA256 .\test-results\visual\manifest.json
```

`e2e:visual` 和 `e2e:ai-live` 会使用本机真实服务与隔离数据。运行前必须确认目标数据库、端口和环境不是生产环境；失败时保留 trace、截图和请求摘要，并先区分真实回归与时序 flake。

### 7.3 供应链

当前结构化证据来自 OSV 官方 `https://api.osv.dev/v1/querybatch`。每个发布候选必须重新生成 Maven runtime 依赖清单、查询官方主源，并保存：

- `backend/target/osv-runtime-summary.json`
- `backend/target/osv-runtime-querybatch-response.json`
- 公告范围校验记录

仓库当前没有稳定的 OSV 一键包装脚本，因此旧报告不能自动证明新依赖仍安全；依赖或锁文件变化后必须刷新主源结果。

## 8. 发布门与回滚

### 8.1 候选报告必须包含

- 构建来源、JAR SHA-256、视觉 manifest SHA-256 和依赖树。
- prompt/model/index/tool/redaction/pricing/dataset version。
- 后端、覆盖率、真实基础设施、前端、E2E、视觉、真实模型、红队和供应链的总数、失败数、跳过数。
- 所有 `PASS`、`FAIL`、`NOT RUN`；禁止把缺环境、缺密钥或跳过计为通过。
- p50/p95 latency/cost、已知失败簇、批准例外和到期日。
- Playwright artifact 清单及 secret/PII 扫描结果。

### 8.2 生产放行前置条件

1. 本文件的工程质量门对当前候选重新运行且全部通过。
2. 当前要激活的 provider/model alias 完成真实合同，不复用其他模型的旧结果。
3. 生产向量、对象、恶意文件扫描、KMS、审计外锚、数据库和 Redis 安全配置均有真实环境证据。
4. 供应商留存、删除、区域和正式数据处理条款完成安全与法务确认。
5. 备份恢复、跨实例/跨区灾备及 RPO/RTO 演练通过。
6. 默认关闭、白名单灰度、预算、审计和 Kill Switch 经产品、安全、运维共同批准。

建议灰度顺序为内部白名单 5% → 获授权管理员 25% → 100%，每阶段至少覆盖一个完整业务周期；比例和周期必须按真实流量校准。

出现以下任一情况立即关闭对应能力：

- 任一固定零容忍项出现 1 次。
- audit 或 usage 无法持久化。
- proposal 重复执行，或 execution 与业务事实不一致。
- provider 密钥、数据政策或区域事件。
- 真实成本、延迟、错误率越过批准上限。

回滚只能关闭 capability、切换到已验证的旧 prompt/model/index version，或退回人工业务页面；不得删除或覆盖失败 run、eval、usage 和 audit 证据。

## 9. `NOT RUN` 与剩余风险

| 项目 | 当前状态 | 解锁条件 |
| --- | --- | --- |
| 外部生产向量索引 | `NOT RUN` | ACL metadata filter、删除、重建、版本切换、性能和灾备合同通过 |
| 外部对象存储与恶意文件扫描 | `NOT RUN` | 固定 version/checksum、隔离扫描、覆盖防护、删除证明和故障降级通过 |
| 生产 KMS/Secret Manager | `NOT RUN` | key version、轮换、历史验证、吊销、审计和最小权限通过 |
| 外部只追加审计锚 | `NOT RUN` | receipt、Merkle cutoff、重试、验证、删除 tombstone/checkpoint 和灾备通过 |
| 生产 MySQL/Redis 安全与灾备 | `NOT RUN` | TLS、ACL、最小权限、加密备份恢复、跨实例/跨区及 RPO/RTO 演练通过 |
| 供应商正式条款 | `NOT RUN` | 留存期限、删除 API/证明、区域、数据处理协议和账单校准完成 |
| 生产效果、成本、延迟和限流 | `NOT RUN` | 用真实但合规的流量完成基准、阈值校准和容量测试 |

已知性能风险：审计 append 在 head 锁内重放完整链，单链累计成本趋近 `O(n²)`；外锚 `REPEATABLE_READ` 快照成本为 `O(scope 事件数)`。可信 checkpoint 或不可变存储落地前，不得退回仅验证尾部的弱校验方式。

这些 `NOT RUN` 项是生产发布前置条件，不改变阶段 0–6 在当前工程范围内的完成结论。阶段 7 仍为 `OUT OF SCOPE`。

## 10. 证据失效规则

出现以下任一变化，相关结果自动失效并必须重跑：

- Java/Vue 源码、SQL Schema、OpenAPI、依赖或构建配置变化。
- prompt、model alias、embedding、index、tool schema、PII/redaction、pricing、dataset 或权限策略变化。
- 9 张视觉合同、路由、响应式断点或关键交互变化。
- 生产 provider、向量、对象、KMS、审计锚、MySQL 或 Redis 配置变化。
- 候选 JAR 或视觉 manifest 的 SHA-256 与本文件不一致。

发布结论只能绑定本次实际运行的候选、配置、数据集和环境，不得沿用旧测试数字、旧截图、旧哈希或其他环境的 `PASS`。

## 11. 2026-08-09 Dashboard 圈注整改当前事实

2026-08-09 用户通过 Dashboard 圈注对比图明确指出三项视觉差异：四类风险图标必须按类别固定蓝/橙/绿/红，风险数量/等级同行右置且清晰，桌面待办恢复七列并在真实审批目标与权限满足时显示“查看建议 / 进入审批”。本轮只修改前端页面、相关合同/状态同步和 Stage 5 非 expired 测试夹具；未公开原始本机路径，未编辑、重生成或替换 9 张原型 PNG，未调用 Figma。

| 验证项 | 当前结果 | 当前证据 |
| --- | --- | --- |
| Stage 5 治理回归 | `9/9 PASS` | `frontend/test-results/stage5-governance-20260809-dashboard-feedback-k/fixtures/`；非 expired 夹具有效期为 `2026-08-12T10:30:00+08:00`，expired 夹具仍为过去时间 |
| 全量 Vitest | `46 files / 519 tests PASS` | `frontend` `npm test`，2026-08-09 当前源码 |
| 类型与生产构建 | `typecheck PASS / build PASS` | `npm run typecheck`；`npm run build`，Vite `8.1.4`、`3863 modules transformed` |
| Dashboard 专项 E2E | `8/8 PASS` | `playwright.stage3-dashboard.config.ts`，覆盖六正式视口、Canvas、状态、键盘、触控、对比度、200% 与 RBAC/401/403 |
| 普通 E2E | `72/72 PASS` | `playwright.config.ts` 精确 `72 tests / 8 files` |
| Impeccable detector | `[]` | `detect.mjs --json frontend/src/views/DashboardView.vue frontend/src/views/AiApprovalView.vue` |
| 独立 UI / 安全增量 | `P0=0 / P1=0 / P2=0 / P3=0` | Assessment A、Assessment B、`security-reviewer` 最新只读回传；不等同后端全站安全审计 |
| 正式本地预览 | `PASS` | `stage6-final-preview-verification-20260809-dashboard-feedback-l.json`；健康/登录/readiness `200`，Fake provider，写执行关闭，Dashboard/公告命令 `202`，SSE `200`，错误集合为空 |
| Dashboard 同视口比较 | `PASS（定位证据）` | `stage6-dashboard-feedback-comparison-20260809-l/`；桌面 `stretch`、移动 `cover-top`，metrics/current/prototype/side-by-side/diff 已生成并逐张 `view_image` |

当前预览的代表性几何与语义证据：桌面 `1586x992` 的 7 个待办表头完整，2 条真实提案同时含两项操作且各为 `72x44px`；移动 `390x844` 的 `documentWidth=390`、`pendingTop=810.59375`、`documentHeight=1233`，品牌标记为 `A`，趋势两项图例边界不重叠；风险行类别 icon computed color 为 `#1769ea / #e96b00 / #079568 / #e5484d`，风险状态和等级为 `14px`。

候选事实边界：本轮 `k`（登录代理到默认 `8080`）与 `k2`（Vite 未继承 `VITE_AI_ENABLED=true`）均为已保留的本地预览启动环境诊断，未用于通过结论；`l` 是修正 `VITE_BACKEND_PROXY_TARGET`、`VITE_AI_ENABLED`、`VITE_VISUAL_EVIDENCE_ENABLED` 后的新鲜通过候选。用户已完成肉眼确认，Stage 6 为 `COMPLETED`；真实供应商、生产写执行、部署、push 和发布继续 `NOT RUN / NOT APPROVED`。
