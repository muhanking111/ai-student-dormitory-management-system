# 本地封版、GitHub 公开发布与可选预发布计划

> 状态：`IN_PROGRESS`（2026-08-11 已完成 Stage 0-5；用户已确认 `Apache-2.0`，Stage 5.5 恢复执行，远程尚未创建）
> 基线日期：2026-08-11
> 推荐路线：先完成 Route A“本地可交付 Release Candidate + GitHub 公开发布”，再决定是否进入 Route B“真实预发布与生产门”
> 当前事实：阶段 0-6、9 图 UI 高保真和用户肉眼验收已完成；真实供应商、生产外部依赖、生产写执行、部署与发布仍为 `NOT RUN / NOT APPROVED`
> 计划公开仓库：`muhanking111/ai-student-dormitory-management-system`；全小写、使用连字符分词，名称直接表达项目用途

## 1. 计划目的

本计划不再扩展业务功能，也不继续进行无目标的 UI 微调。目标是把当前已经完成的系统收敛为一个可追溯、可复现、可演示、可回滚的 Release Candidate，在全部公开发布检查通过后直接创建 GitHub 公开仓库并完成首次推送，同时为未来是否进入真实预发布环境提供清晰决策门。

完成 Route A 后，应得到以下结果：

- 当前源码有明确的本地版本身份，可通过 Git commit/tag 或等价来源标识追溯。
- 前后端依赖、构建、测试、数据库和演示数据可以在干净本地环境复现。
- 核心业务、AI 助手、Dashboard、维修、公告、风险、审批和审计可以按固定脚本完成演示。
- JAR、前端构建产物、测试报告、视觉证据和关键配置样例有统一清单及 SHA-256。
- 使用说明、演示脚本、故障排查、已知限制和回滚说明完整。
- GitHub 公开仓库在创建前已完成源码、历史、许可证、隐私、敏感信息和公开制品范围审查；公开后的默认分支、tag 和 Release 与本地 RC 完全一致。
- 文档明确区分“本地工程完成”“本地演示通过”“预发布通过”和“生产放行”，不以 Fake provider 或本地测试冒充真实生产证据。

## 2. 当前基线与已知缺口

### 2.1 已完成基线

- 非 AI 业务基线和 AI 阶段 0-6：`COMPLETED`。
- 18 个原业务路由 + 4 个 AI 路由，共 22 个受保护路由。
- 19 张业务表、44 张 `ai_*` 表、OpenAPI 3.1.0 `55 paths / 60 operations`。
- AI 助手、知识治理、固定指标 Dashboard、维修分诊、公告起草、风险中心、审批、审计和运行治理已落地。
- RBAC、CSRF/Origin、SSE、引用 ACL、PII、step-up、审批、审计和状态机边界已有实现与测试证据。
- 9 张最终 PNG 对应的 Stage 1-6 高保真整改、六视口、可访问性、独立 UI/安全复核和用户肉眼验收已完成。

### 2.2 当前需要收口的工程问题

- 空 `.git/` 已完成只读恢复审计并确认无历史；2026-08-11 已建立新的本地仓库和当前完成基线根提交，尚未配置远程或创建 tag。
- 前端 ESLint flat config 已补齐；Stage 3 已将 lint、全部前后端、真实基础设施、E2E、AI-live、六视口视觉、供应链、package 与哈希门绑定到 commit `ca297b68993a864229cab274ec412bf434512a30` 并通过。
- Stage 5.5 为统一 Apache-2.0 元数据而修改评测数据注册器后，旧 Stage 3 候选已失效；2026-08-11 已对当前公开候选树从头重跑全部 Route A 工程门，最终 JAR、dist 和视觉 manifest SHA-256 分别为 `D6983CEE32D120863E2198AF0F5302A93AE7113F55021D95AD7A99CD9E5457B8`、`1108939232ADD39167E9D068F2CF4C2A54CD7806844B95CC2CDBCDFDD513C5DB`、`42851A03E777281309A1064D8EC09ABABC8063484B6FE80379C4ACD2601CD01C`。
- 现有测试、JAR 和视觉证据虽然存在，但任何源码、依赖、schema、OpenAPI 或构建配置变化都会使相关旧证据失效；正式封版必须重新生成同一候选的证据。
- 当前真实模型合同 `RealModelContractIT` 未绑定本候选运行，真实供应商和 PR-01 至 PR-06 仍为 `NOT RUN / NOT APPROVED`。
- GitHub CLI 当前登录账号为 `muhanking111`；截至 2026-08-11，`muhanking111/ai-student-dormitory-management-system` 不存在。该事实执行前必须重新查询，避免名称被占用或错误创建到其他账号。

## 3. 路线选择

### Route A：本地可交付 RC（推荐，默认执行）

适用于毕业设计、课程项目、答辩、作品展示、代码归档、本地验收和 GitHub 开源展示。完成 Stage 0-5 后进入 Stage 5.5 公开发布门；只有公开检查全部通过，才直接创建公开仓库并首次推送。Route A 不要求真实模型供应商、生产 KMS、灾备、生产压测或真实业务部署。

### Route B：真实预发布与生产准备（条件执行）

仅在系统要进入学校真实试用或生产使用，并且用户提供目标环境、账号、预算、合规结论和外部读写授权后执行 Stage 6-8。Route B 不能绕过 `ai-master-plan.md` 中 PR-01 至 PR-06 的任何门。

## 4. 固定边界

1. 不重新设计或替换 9 张最终原型 PNG，不调用 Figma。
2. 不增加阶段 7 预测模型、学生端、IoT、门禁、人脸、摄像头、语音监听或通用 Agent。
3. 不降低或关闭 RBAC、CSRF/Origin、SSE 鉴权、引用 ACL、PII、审批、step-up、审计和状态机约束。
4. 不删除测试、降低覆盖率门、隐藏失败或把 skip/缺环境写成 PASS。
5. `.env`、真实密码、Token、证书私钥和供应商凭据不得进入 Git、日志、截图或交付包。
6. 本地 AI 演示默认使用确定性 Fake provider；不得描述为真实模型生产验收。
7. 用户已批准在 Stage 5.5 全部检查通过后创建并首次推送公开仓库 `muhanking111/ai-student-dormitory-management-system`；在该门通过前不得提前创建或 push。部署、生产开关、真实供应商调用和其他外部资源写入仍需单独批准。
8. 生产写执行默认关闭；需要演示业务写链时，只允许在独立本地演示库中通过真实审批和现有 Service 执行，不允许静态假成功。
9. GitHub 公开仓库一经创建，按不可撤回暴露处理；不得依赖后续删除文件、改私有或重写历史补救本可在首次 push 前发现的问题。

## 5. 阶段总览

| 状态 | 阶段 | 目标 | 主要交付物 | 完成门 |
| --- | --- | --- | --- | --- |
| `completed` | Stage 0 基线与仓库身份审计 | 固定当前范围、文件边界、证据新鲜度和 Git 根因 | 基线审计、仓库恢复决策、敏感文件清单 | 已确认空 `.git/` 无可恢复历史；源码和原型未修改 |
| `completed` | Stage 1 Git 恢复与版本身份 | 建立可用、可追溯且不泄密的本地版本控制 | 可用 Git 仓库、初始 RC commit、tag 方案、范围清单 | 根提交已创建；`git status`/`git log` 可用，敏感和生成文件均被排除 |
| `completed` | Stage 2 前端 lint 质量门 | 补齐 ESLint，并在不弱化规则的前提下关闭当前问题 | ESLint 配置、`npm run lint`、聚焦测试 | lint 0 error；Vitest/coverage/typecheck/build 与依赖审计通过 |
| `completed` | Stage 3 RC 全量验证与制品锁定 | 对同一源码候选重跑全部本地质量门并生成哈希 | JAR、前端 dist、测试/覆盖率/E2E/视觉报告、RC manifest | commit `ca297b6` 授权范围内质量门全部 PASS；NOT RUN 准确 |
| `completed` | Stage 4 一键本地演示环境 | 在干净机器上可启动、重置、验证和停止 | 启停/健康/重置脚本、演示数据、演示账号说明 | 两种模式启动/健康/停止/reset/重启均通过，Fake provider 与隔离目标保持不变 |
| `completed` | Stage 5 演示验收与交付包 | 形成答辩/交付所需文档、演示流程和回滚说明 | 用户手册、运维速查、演示清单、发布说明、已知限制 | clean clone、新终端、桌面/移动关键页、异常态、停止与恢复演练全部通过 |
| `in_progress` | Stage 5.5 GitHub 公开发布门 | 在远程创建前完成全部公开审查，通过后直接创建 public 仓库并首次推送 | 公开文件白名单、许可证、安全策略、敏感扫描、GitHub 仓库、tag/Release | 用户已确认 `Apache-2.0`；远程内容与本地 RC 一致，公开扫描无违规，仓库可访问 |
| `blocked_by_decision` | Stage 6 预发布方案与外部决策 | 确定真实环境、供应商、拓扑、预算和责任人 | 架构/网络/数据流方案、决策记录、授权清单 | 所有外部输入和审批齐全 |
| `not_run` | Stage 7 PR-01 至 PR-06 执行 | 完成真实生产外部依赖、安全、恢复和容量门 | 六类生产门证据包 | 每个 PR 门在真实环境独立 PASS |
| `not_run` | Stage 8 灰度、放行与回滚演练 | 从只读白名单开始灰度并完成发布决策 | 灰度报告、SLO、告警、回滚记录、放行签字 | 生产批准完成且零容忍项为 0 |

## 6. Stage 0：基线与仓库身份审计

### 目标

确认当前工作区的真实边界，避免在 Git 恢复、依赖安装或清理生成文件时误删用户资产或把敏感内容纳入版本控制。

### 涉及文件与模块

- 根目录 `.git/`、`.gitignore`、`.env`、`.env.example`、`README.md`、`compose.yml`。
- `backend/pom.xml`、后端源码、schema、OpenAPI 和 `backend/target/`。
- `frontend/package.json`、锁文件、前端源码、Playwright/Vitest 配置、`frontend/dist*`、`frontend/test-results/`。
- `plan/`、`.planning/`、`design/ai-prototypes/`。

### 执行项

1. 记录根目录、工具版本、Java/Node/npm/Maven/Docker/MySQL/Redis 可用性。
2. 确认 `.git/` 是否为空、损坏、残留 worktree 元数据或可恢复仓库；当前已知为目录存在但 Git 命令不可用。
3. 扫描 `.env`、私钥、Token、密码、数据库转储、日志、截图和测试制品，建立“禁止提交”清单。
4. 记录当前源码、配置、原型和计划文件的文件数量、修改时间及哈希索引。
5. 对照 `ai-master-plan.md`、`ai-verification.md` 和当前源码，确认封版范围仍为阶段 0-6，不引入新功能。

### 交付物

- `.planning/<rc-task>/findings.md`：仓库身份、敏感边界和恢复选择。
- `.planning/<rc-task>/progress.md`：实际命令、退出码和异常。
- RC 输入文件清单及排除清单。

### 验收

- 已明确 Git 恢复方案，不对 `.git/` 执行未经验证的删除或覆盖。
- `.env`、数据库卷、`node_modules`、`target`、`dist`、Playwright 报告和测试临时库不会被误提交。
- 没有源码、原型 PNG 或用户文件被修改。

### 阻塞与回退

- 若发现可恢复的 Git object/history，暂停新仓库初始化，优先只读恢复并由用户确认目标远程/分支。
- 若 `.git/` 确认为空且无历史可恢复，可备份其元信息后建立新的本地仓库；外部远程写入仍需批准。

## 7. Stage 1：Git 恢复与版本身份

### 目标

让每个后续构建、测试和交付物都能绑定到明确源码版本。

### 方案顺序

1. **优先恢复既有历史**：如果能确定原远程、commit 或备份，验证来源后恢复，不重写历史。
2. **建立新的本地仓库**：仅在确认 `.git/` 无可恢复内容后执行；初始提交作为当前完成基线，不冒充历史开发过程。
3. **远程同步**：只有用户明确指定远程、分支和可见性后，才配置并 push。

### 涉及文件

- `.git/`、`.gitignore`、`.env.example`、根目录与前后端依赖锁文件。
- 必要时增加 `.gitattributes`，统一文本行尾并避免二进制原型被文本处理。

### 执行项

1. 修正 `.gitignore`，确认敏感文件和生成目录均被忽略，但源码、计划、数据库迁移、OpenAPI、必要脚本和原型 PNG 被纳入。
2. 执行敏感信息、调试残留、超大文件和无关制品扫描。
3. 生成首个可追溯 RC commit，提交信息建议为 `chore: 建立本地可交付版本基线`。
4. 使用 `rc-YYYYMMDD.N` 命名候选；正式 tag 只能在 Stage 3 全量门通过后创建。
5. 保存 commit hash、tag、工作区状态和未跟踪文件清单。

### 验收

- `git rev-parse --show-toplevel`、`git status`、`git log -1` 均成功。
- `.env`、私钥、凭据、数据库数据和生成报告未进入暂存区。
- commit 内容与计划范围一致，无无关文件和外部写入。

### 回退

- 恢复动作前保存空/异常 `.git/` 的只读诊断；发生异常时恢复诊断副本，不执行 `git reset --hard`。
- 未获批准时只保留本地 commit，不创建远程仓库、不 push。

## 8. Stage 2：前端 lint 质量门

### 目标

补齐当前唯一明确缺失的前端静态质量门，同时保持 Vue 3、TypeScript、Vitest、Playwright 和现有构建行为不变。

### 涉及文件

- `frontend/package.json`、前端锁文件。
- 新增或更新前端 ESLint 配置与必要忽略文件。
- 仅修改 lint 实际发现且与当前代码有关的源码；不做无关格式化或视觉重构。

### 执行项

1. 使用与当前 Vue/TypeScript 工具链兼容的 ESLint 配置。
2. 增加 `npm run lint`，默认检查 `src`、测试和必要配置文件。
3. 先记录初始 lint 结果，再按类型分组修复；禁止通过大范围 disable、降低规则或排除业务源码获得通过。
4. 对自动修复后的文件运行聚焦 Vitest、typecheck 和 build。
5. 更新 `ai-verification.md`：只有实际运行后才能把 lint 从 `NOT CONFIGURED` 改为 PASS。

### 验收

- `npm run lint` 退出 0，error 为 0。
- `npm run test`、`npm run typecheck`、`npm run build` 继续通过。
- 无 UI、RBAC、API、SSE、审批或状态机行为变化。

### 阻塞与回退

- 如果一次性引入 lint 暴露大量历史问题，仍不得关闭核心规则；应按目录小步修复并保持 Stage 2 `in_progress`。
- 自动修复导致行为或视觉变化时，回退该局部修改并采用显式代码修复。

## 9. Stage 3：RC 全量验证与制品锁定

### 目标

对同一 commit、同一配置、同一隔离数据和同一候选编号完成封版验证，旧 PASS、旧截图和旧哈希不直接沿用。

### 前端质量门

在 `frontend/` 执行：

```powershell
npm run lint
npm run test
npm run test:coverage
npm run typecheck
npm run build
npm audit --audit-level=high
npm run e2e
npm run e2e:ai-live
npm run e2e:visual
```

要求：

- 普通 E2E 与 AI-live 使用独立本地 MySQL/Redis/端口和唯一 key prefix。
- AI-live 使用确定性 Fake provider，记录 `businessWrites`、API/console/page/request errors。
- 正式视觉重新覆盖 22 路由、六正式视口、9 prototype capture、Assistant 状态和 Design System 画廊。
- 若 Stage 2 只改变 lint 配置且源码未变，仍需至少重新运行完整前端门；任何源码变化必须重新生成视觉候选。

### 后端质量门

在 `backend/` 执行：

```powershell
mvn -q test
mvn -q -P ai-coverage verify
mvn -q -Dtest=RealInfrastructureIT,AiRealInfrastructureIT,AiUploadRealInfrastructureIT test
mvn -q -DskipTests package
```

同时执行现有 CSRF、OpenAPI/schema、框架兼容、生产样例配置、供应链和敏感信息专项门。真实供应商合同缺少凭据或授权时必须保持 `NOT RUN`。

### 制品

- `backend/target/student-dormitory-management-system-0.1.0.jar` 及 SHA-256。
- `frontend/dist/` 文件清单、总大小、每文件或索引 SHA-256。
- 前后端依赖清单和高危漏洞报告。
- 后端测试、AI line/branch coverage、真实基础设施结果。
- 前端 lint、Vitest、coverage、typecheck、build、普通 E2E、AI-live、visual 结果。
- RC manifest：commit、tag、时间、Java/Node/Maven/npm、配置摘要、数据库名、Redis DB/prefix、所有 PASS/FAIL/NOT RUN。

### 验收

- 授权范围内所有质量门退出 0，无失败、错误、未解释 skip 或测试弱化。
- AI line/branch 不低于现有 80% 门；前端覆盖率不降低既有门。
- JAR、dist、视觉 manifest 和关键报告的 SHA-256 已记录到 `ai-verification.md`。
- `git status` 只包含预期文档/制品索引变更；生成目录不进入提交。

### 失败处理

- 任一源码、schema、OpenAPI、依赖或构建配置修复都会使受影响结果失效，必须从相关质量门重新开始。
- 环境失败与产品失败分开记录；不得复用部分候选补齐另一个候选的证据。

## 10. Stage 4：一键本地演示环境

### 目标

让不熟悉项目的使用者能够按文档完成启动、健康检查、演示数据初始化、核心流程验证、停止和数据重置。

### 建议交付文件

```text
scripts/
  demo-start.ps1
  demo-health.ps1
  demo-reset.ps1
  demo-stop.ps1
docs/delivery/
  demo-guide.md
  demo-accounts.example.md
  troubleshooting.md
```

具体目录可按仓库现有结构调整，但必须保持单一入口。

### 演示模式

| 模式 | 用途 | 数据与 AI | 写执行 |
| --- | --- | --- | --- |
| `demo-readonly` | 答辩、UI 展示、功能浏览 | 隔离数据库、脱敏演示数据、Fake provider | AI 写执行关闭 |
| `demo-approval` | 展示建议、预览、审批和现有 Service 写链 | 全新独立演示库、Fake provider、明确演示账号 | 仅本地隔离库按真实审批链执行 |

### 执行项

1. 启动前检查 Java、Maven、Node、npm、Docker、端口和 `.env` 必填项。
2. 默认通过 `compose.yml` 启动本地 MySQL/Redis；明确它不是生产配置。
3. 初始化脱敏演示数据，重复运行必须幂等或先显式重置。
4. 启动后端和前端，等待健康检查通过后再打开浏览器。
5. 健康脚本检查后端 health、登录、会话恢复、AI readiness、前端首页和关键静态资源。
6. 停止脚本只停止本次启动的进程，不终止未知 Java/Node/MySQL/Redis 进程。
7. 重置脚本只允许操作命名明确的本地演示数据库和 Redis DB/prefix，执行前打印目标并二次校验。

### 核心演示流程

1. 管理员登录、菜单 RBAC 和会话恢复。
2. Dashboard 指标、趋势、风险概览和待办操作。
3. AI 助手提问、流式输出、引用、无来源/低置信和撤权状态。
4. 维修列表、详情、AI 分诊、提案和审批入口。
5. 公告输入、AI 草稿、检查、差异预览和审批。
6. 风险规则、案例列表、详情、人工处置和降级状态。
7. 审批 diff、step-up、批准/拒绝/失效状态。
8. 审计时间线、脱敏内容和对象授权失败。
9. 普通业务 CRUD、状态流转和 AI 关闭时人工流程可用。

### 验收

- 在干净本地环境按单一文档完成启动，健康检查全部通过。
- 两种演示模式都不访问真实供应商或生产资源。
- 所有账号、姓名、学号、手机号和宿舍数据均为明确脱敏 fixture。
- 失败、停止、重启和重置均有可复现结果，不依赖手工修改数据库。

## 11. Stage 5：演示验收与交付包

### 目标

形成可以交给老师、评审、开发者或运维人员使用的完整本地交付材料。

### 交付内容

- 根 `README.md`：五分钟快速启动、系统能力、默认安全状态和文档入口。
- `docs/delivery/demo-guide.md`：10-15 分钟演示顺序、操作账号、预期结果和备用路线。
- `docs/delivery/user-manual.md`：角色、页面、核心业务操作和错误状态。
- `docs/delivery/operator-quickstart.md`：启动、停止、健康检查、日志、备份和恢复边界。
- `docs/delivery/troubleshooting.md`：端口、数据库、Redis、登录、CSRF、Vite proxy、SSE、Docker 和浏览器问题。
- `docs/delivery/release-notes-<rc>.md`：能力、修复、验证、已知限制、NOT RUN 和回滚方式。
- RC manifest、JAR/dist 哈希、测试摘要和必要的脱敏截图索引。

### 最终人工演练

1. 使用新终端按文档从零启动，不依赖残留环境变量。
2. 使用演示账号完整走一次核心演示流程。
3. 验证 1366x768 桌面和 390x844 移动关键页面。
4. 模拟 AI 关闭、权限不足、引用撤销、审批过期和后端不可用。
5. 停止并重新启动，确认状态和数据符合文档。
6. 由用户确认“可作为本地交付/答辩版本封版”。

### Route A 本地封版条件

- Stage 0-5 均为 `completed`，允许进入 Stage 5.5 公开发布门。
- 当前 RC commit/tag、JAR、dist、证据和文档相互绑定。
- 核心演示无 P0-P3 未关闭问题。
- 所有真实供应商和生产门仍准确标为 `NOT RUN / NOT APPROVED`。
- 用户完成本地交付验收。

## 12. Stage 5.5：GitHub 公开发布门

### 目标

在任何源码上传到远程之前完成全部公开审查。检查全部通过后，直接在当前 GitHub 账号下创建公开仓库并完成首次推送，不先创建私有仓库，不把远程仓库当作检查环境。

### 固定仓库身份

| 项目 | 计划值 |
| --- | --- |
| GitHub owner | `muhanking111` |
| repository | `ai-student-dormitory-management-system` |
| 完整名称 | `muhanking111/ai-student-dormitory-management-system` |
| 可见性 | `public` |
| remote | `origin` |
| 默认分支 | 优先 `main`；执行前确认本地分支和 GitHub 账号默认分支设置 |
| 描述 | `AI-powered student dormitory management system built with Spring Boot and Vue 3.` |

命名理由：名称全小写，使用单个连字符分隔单词，不含空格、下划线、中文或无意义缩写；`ai`、`student-dormitory` 和 `management-system` 能直接说明技术方向、使用对象和系统类型。

### 公开文件范围

默认允许公开：

- 前后端源码、测试、数据库迁移、OpenAPI、构建配置和依赖锁文件。
- 根 `README.md`、`plan/` 中现行计划、公开使用说明和 `.env.example`。
- 已确认拥有发布权的 9 张原型 PNG 和设计索引。
- 不含凭据、真实数据或内部拓扑的演示脚本与 fixture。
- `LICENSE`、`SECURITY.md`、必要的 `CONTRIBUTING.md` 和 Release notes。

默认禁止公开：

- `.env`、真实密钥、Token、Cookie、证书、私钥和供应商凭据。
- MySQL/Redis 数据文件、数据库 dump、真实账号、真实学生/宿舍/维修/缴费数据。
- `node_modules/`、`target/`、`dist*`、Playwright 原始报告、trace、video、日志和临时缓存。
- 含本机用户名、绝对路径、内部端口拓扑、运行 UUID、私有 API 细节或未脱敏截图的原始 `.planning/` 记录和测试制品。
- 未确认版权或公开授权的第三方图片、字体、数据集和文档。

`.planning/` 不整体公开。执行时建立公开白名单：需要保留的决策和验收结论收敛到 `plan/`、README、Release notes 或专门的脱敏公开摘要；原始活动日志继续留在本地并加入公开仓库忽略规则。

### 许可证决策

创建远程仓库前必须确定许可证，不能在没有结论时默认声称“开源”。候选选项包括：

- `MIT`：允许范围宽，适合展示、学习和二次开发。
- `Apache-2.0`：在宽松授权基础上增加明确专利条款。
- 暂不授予开源许可证：代码仍可公开查看，但不应描述为允许自由使用、修改和分发。

许可证属于用户决策。用户已于 2026-08-11 明确选择 `Apache-2.0`；根目录 `LICENSE`、构建元数据、README、Release notes 和合成评测 manifest 必须保持同一标识。

### 当前预公开证据

- Apache 官方完整许可证文本逐字一致，SHA-256 `CFC7749B96F63BD31C3C42B5C471BF756814053E847C10F3EB003417BC523D30`；公开范围旧 `INTERNAL_PROJECT_USE` 标识为 0。
- 前端 lint `177 files / 0 errors / 2706 warnings`、Vitest `519/519`、coverage `87.81/80.17/90.66/91.71`、typecheck/build、npm audit、普通 E2E `72/72`、AI-live `1/1` 和六视口视觉 `1/1` 均 PASS。
- 后端 `161 reports / 919/919 PASS`；AI line `93.64%`、branch `80.80%`；真实 MySQL/Redis `8/8`、CSRF/OpenAPI/schema/框架兼容/生产样例 `41/41`、Maven runtime OSV `120 dependencies / 0 findings`。
- 当前公开树 `784 files / 18,701,555 bytes`，当前树 blockers 0，11 张 PNG 元数据 findings 0，Markdown `32 files / 74 links / 0 findings`，npm 480 与 Maven 120 依赖许可证 missing/manual 0。
- 独立 `security-reviewer` 复核未发现 Apache-2.0 注册器改动或公开文档引入新的安全弱化；当前唯一阻断是旧 4 个本地中间提交中的 8 个 Edge 绝对路径，必须在首次 push 前收敛为净化公开历史并复扫。

### 上传前公开审查

1. 固定待推送 commit、分支、tag 和工作区状态；未提交修改必须为 0。
2. 执行 `codex-precommit-check` 范围审查，确认没有无关文件、调试日志、测试弱化和配置降级。
3. 扫描当前文件和完整本地 Git 历史中的凭据、私钥、Token、数据库连接、Cookie、真实 PII 和内部路径。
4. 检查 `.gitignore`、`.gitattributes`、`.env.example`、README、LICENSE 和 SECURITY.md。
5. 检查所有 PNG、附件、fixture、SQL、JSON、Markdown、trace 和报告是否含真实身份或本地私有信息。
6. 检查依赖许可证和高危供应链结果；未解释高危项不得公开标记为稳定版本。
7. 在离线临时目录执行一次“准备公开文件集合”复算，确认没有被忽略规则遗漏的敏感文件。
8. 使用 `git ls-files` 生成最终公开清单，并由根任务逐项检查高风险扩展名。
9. 重新确认 `gh auth status` 的活动账号为 `muhanking111`。
10. 重新查询仓库名不存在；若名称已经存在或账号不一致，停止并由用户决定新名称，禁止自动添加随机后缀。

### 创建与首次推送

仅在上述检查全部 PASS 后执行：

```powershell
gh repo create muhanking111/ai-student-dormitory-management-system `
  --public `
  --source . `
  --remote origin `
  --description "AI-powered student dormitory management system built with Spring Boot and Vue 3." `
  --push
```

如果许可证已作为本地 `LICENSE` 提交，不再让 GitHub CLI 远程生成第二份 README、`.gitignore` 或许可证，避免首次推送出现无关历史或合并冲突。

### 推送后核验

1. 执行 `git remote -v`，确认 `origin` 指向计划仓库。
2. 执行 `git ls-remote origin`，确认远程默认分支 hash 与本地一致。
3. 使用 GitHub API/CLI 核对仓库 `visibility=PUBLIC`、owner、name、description 和默认分支。
4. 检查远程根目录、README、LICENSE、SECURITY.md、源码、计划和原型是否完整。
5. 再次搜索公开仓库中的 `.env`、密钥、私钥、PII、数据库 dump、日志、内部路径和禁止制品，结果必须为 0。
6. 核对 GitHub Security 页面可用设置；按账号/仓库能力启用依赖告警、Secret scanning、Push protection 和 Code scanning。
7. 推送 RC tag，并创建与该 tag 绑定的公开 Release；Release 明确写明“本地演示/工程候选，不代表生产放行”。
8. 保存远程仓库 URL、默认分支、远程 hash、tag、Release URL 和公开扫描摘要。

### 验收

- 创建前所有公开检查均有新鲜 PASS 证据。
- 远程仓库名称严格为 `ai-student-dormitory-management-system`，owner 为 `muhanking111`，可见性为 `PUBLIC`。
- 远程默认分支和 tag 与本地 RC commit/hash 一致。
- 远程公开内容中禁止项命中为 0。
- README、许可证、安全策略、启动说明、功能边界和 NOT RUN 声明完整。
- GitHub Release 不把 Fake provider、本地演示或阶段 0-6 工程完成描述为生产上线。

### 失败与回退

- 创建命令前失败：不创建远程仓库，修复后重新运行全部受影响检查。
- 仓库创建成功但 push 失败：保持仓库不写入其他内容，定位认证、分支或网络问题；不得用强推覆盖未知远程历史。
- 推送后发现疑似敏感信息：立即停止后续 Release/宣传，先撤销对应真实凭据并评估历史清理；不能只删除最新文件后继续。
- owner、名称或可见性不符合计划：停止后续操作，保留证据并由用户决定删除/更名；不得自行创建第二个仓库规避问题。

### Route A 公开完成标准

- Stage 0-5.5 全部 `completed`。
- 本地 RC、远程默认分支、tag 和 Release 绑定同一 commit。
- 公开仓库检查、敏感扫描、许可证和安全策略全部完成。
- 仓库可由未登录浏览器访问，README 能独立指导本地运行。
- 生产外部门仍准确保持 `NOT RUN / NOT APPROVED`。

## 13. Stage 6：预发布方案与外部决策

本阶段默认 `blocked_by_decision`，不因 Route A 完成自动启动。

### 必须由用户或组织明确的输入

- 使用目的：内部试点、真实生产或仅公网演示。
- 目标云/服务器、区域、域名、证书和网络拓扑。
- MySQL、Redis、对象存储、向量库、文件扫描、KMS 和审计外锚产品。
- 模型供应商、model alias、地域、预算、限流、留存、训练、删除和退出条款。
- 数据分类、真实 PII 是否允许进入供应商、DPA/法务/安全结论。
- SLO、并发、成本上限、RPO、RTO、备份周期和恢复责任人。
- 预发布账号、凭据注入方式、灰度名单、告警接收人和回滚批准人。
- 是否允许执行非生产外部读写测试的明确授权。

### 交付物

- 预发布架构图、网络边界、数据流和信任边界。
- 环境配置矩阵和 Secret/KMS 注入方案。
- 数据保留/删除/备份/恢复策略。
- PR-01 至 PR-06 的负责人、环境、时间和证据路径。

### 验收

- 未决问题全部有责任人和结论。
- 不使用本地 compose、Fake provider 或历史合同替代生产方案。
- 任何外部写入前已获得用户授权。

## 14. Stage 7：PR-01 至 PR-06 生产门

| 门 | 必须完成的工作 | 核心验证 | 回退 |
| --- | --- | --- | --- |
| PR-01 Secret/KMS | 真实 Secret Manager/KMS、版本、轮换、吊销、最小权限 | 旧版本验证、轮换演练、缺 key fail closed、审计完整 | provider/write 关闭 |
| PR-02 向量/对象/扫描 | 外部向量库、对象存储、恶意文件扫描、地域与加密 | ACL 前后置过滤、版本/ETag、删除、重建、覆盖攻击、灾备 | Knowledge 关闭 |
| PR-03 审计外锚 | 独立不可变介质、receipt、checkpoint、补偿 | receipt 新鲜度、断网重试、验证、tombstone、恢复 | 新 run/写执行关闭 |
| PR-04 供应商合规 | DPA、地域、留存、训练、删除、子处理方、退出 | 真实合同、删除证明、成本/延迟/限流基准 | provider `none` |
| PR-05 数据基础设施 | MySQL/Redis TLS、ACL、最小权限、私网、加密备份 | 恢复演练、跨实例/跨区、RPO/RTO、Secure Cookie | AI 关闭或退回人工 |
| PR-06 压测与灰度 | 负载模型、预算、SSE、outbox、审计链、多实例 | p50/p95、成本、错误率、Kill Switch、故障演练 | 关闭对应 capability |

每个门必须在 `ai-verification.md` 记录实际环境、命令、总数、失败、跳过、哈希和证据路径。六个门互不代替，局部 PASS 不等于生产放行。

## 15. Stage 8：灰度、放行与回滚演练

### 灰度顺序

1. 内部白名单、只读能力、建议比例 5%。
2. 获授权管理员、只读能力扩大到 25%。
3. 提案生成灰度，仍不自动执行。
4. 写执行在单独审批后小范围启用。
5. 满足完整业务周期和 SLO 后再评估扩大比例。

比例和周期必须根据真实流量重新确定，不能机械照搬建议值。

### 零容忍停止条件

- RBAC、对象 ACL、CSRF、PII 或审计出现任何确认性违规。
- proposal 重复执行、业务事实与 execution 不一致。
- audit、usage、预算或 idempotency 无法持久化。
- provider 地域、凭据、数据政策或供应链发生异常。
- 成本、延迟、错误率或恢复时间超过批准阈值。

### 验收

- Kill Switch、配置回退、旧版本切换和人工业务页面回退均完成演练。
- 失败 run、usage、eval、proposal、execution 和 audit 记录被保留，未通过删除数据掩盖失败。
- 产品、安全、运维和业务负责人完成明确放行记录。

## 16. 证据与状态维护规则

1. 正式执行本计划前，新建 `.planning/<date>-release-candidate-delivery/`，包含 `task_plan.md`、`findings.md` 和 `progress.md`。
2. 每个 Stage 开始前立即标记 `in_progress`，写明目标、文件、验收和阻塞；完成交付物和新鲜验证后立即标记 `completed`。
3. 缺环境、缺授权、测试失败或需要用户决策时保持 `in_progress`，或标记 `blocked` / `NOT RUN`，禁止批量补状态。
4. 稳定范围和能力变化更新 `ai-master-plan.md`；精确测试数、覆盖率、哈希和 NOT RUN 只更新 `ai-verification.md`。
5. 每个证据必须绑定同一 commit/tag、候选编号、配置摘要、数据库和 Redis 隔离标识。
6. 历史制品只作为诊断基线，不用于当前 RC 通过结论。

## 17. 完成定义

### 本地交付完成

满足以下全部条件，才能声明“本地可交付并已公开的 RC 完成”：

- Git/等价源码身份可追溯。
- lint、测试、覆盖率、typecheck、build、E2E、AI-live、视觉和后端质量门通过。
- 一键启动、健康检查、停止和重置可复现。
- 演示数据脱敏，演示流程完整，异常状态可验证。
- 文档、制品、哈希和源码版本一致。
- 用户完成本地交付验收。
- Stage 5.5 公开发布门通过，远程仓库、默认分支、tag 和 Release 与本地 RC 一致。

### 生产完成

只有 Route B Stage 6-8、PR-01 至 PR-06、真实供应商合同、真实基础设施、备份恢复、压测、灰度和正式批准全部完成后，才允许声明“生产放行”。Route A 完成不能自动升级为生产完成。

## 18. 推荐的立即下一步

按以下顺序启动执行：

1. 建立新的活动 `.planning` 任务并把 Stage 0 标记为 `in_progress`。
2. 只读诊断当前空 `.git/`，确认是否存在历史恢复来源。
3. 完成敏感文件和生成目录边界扫描。
4. 用户确认“恢复旧仓库”或“建立新本地仓库”后进入 Stage 1。
5. Git 身份稳定后补 ESLint，再开始正式 RC 全量质量门。
6. Stage 0-5 全部完成后执行 Stage 5.5；公开检查通过前不得创建远程仓库。
7. 公开检查通过后，直接创建 `muhanking111/ai-student-dormitory-management-system` 为 public 并完成首次推送。
