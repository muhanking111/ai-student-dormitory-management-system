# 现代 AI 智能学生宿舍管理系统

<p align="center">
  <a href="https://github.com/muhanking111/ai-student-dormitory-management-system"><img alt="GitHub stars" src="https://img.shields.io/github/stars/muhanking111/ai-student-dormitory-management-system?style=for-the-badge&logo=github"></a>
  <a href="LICENSE"><img alt="License" src="https://img.shields.io/badge/license-Apache%202.0-1f2937?style=for-the-badge"></a>
  <a href="backend/pom.xml"><img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.5-6db33f?style=for-the-badge&logo=springboot&logoColor=white"></a>
  <a href="frontend/package.json"><img alt="Vue" src="https://img.shields.io/badge/Vue-3-42b883?style=for-the-badge&logo=vuedotjs&logoColor=white"></a>
</p>

面向高校宿舍管理员、后勤人员和学校管理人员的前后端分离管理系统。现行能力、架构、阶段状态与后续生产门统一见 [AI 总计划](./plan/ai-master-plan.md)；前端以 [9 张最终原型](./design/ai-prototypes/README.md) 为唯一视觉合同，后续编码任务使用 [现行开发提示词](./plan/development-prompts.md)。

AI 首期阶段 0–6 已完成，当前为 `18 个原业务路由 + 4 个 AI 路由 = 22 个受保护路由`：提供带引用助手、固定指标驾驶舱、知识治理、维修分诊、公告草稿、风险中心、审批和运行审计。AI 与写执行默认关闭；业务写只允许“建议 → 变更预览 → 人工审批 → 现有 Service”，不允许模型生成 SQL、动态工具或绕过 Sa-Token/RBAC。精确验收和外部供应商 `NOT RUN` 边界见 [AI 验证与验收](./plan/ai-verification.md)。

> **项目状态**：本仓库面向本地演示、工程审阅和毕业设计展示。Fake provider、隔离数据库和本地验收不代表真实供应商或生产环境已放行。

## 目录

- [技术栈](#技术栈)
- [当前能力](#当前能力)
- [五分钟本地演示](#五分钟本地演示)
- [开发运行](#开发运行)
- [验证命令](#验证命令)
- [交付文档](#交付文档)
- [安全边界](#安全边界)

## 技术栈

- 前端：Vue 3、TypeScript、Vite、Ant Design Vue、Tailwind CSS、Pinia、Vue Router、ECharts
- 后端：Java 21、Spring Boot 3.5.16、MySQL、Redis、Sa-Token、MyBatis-Plus
- 测试：Vitest、Playwright、Spring Boot Test、MockMvc、H2

## 当前能力

- 登录、会话恢复、退出和受保护路由
- MySQL 用户、角色、权限多对多模型和 BCrypt 密码
- Sa-Token Redis 会话、接口权限校验和按权限显示的前端菜单/操作
- 用户管理与角色权限管理的分页筛选、创建、编辑和删除
- 楼栋、宿舍、床位的真实关联、服务端分页筛选和独立管理页面
- 创建宿舍自动生成床位，宿舍缩容仅删除空闲床位，资源写操作同步 Dashboard 缓存
- 学生信息分页 CRUD、入住申请提交/审核/拒绝、宿舍床位分配、入住记录和退宿办理
- 入住生命周期事务同步学生状态、床位状态、入住记录和宿舍入住/空床统计，退宿后释放床位和活动唯一键
- 报修、费用、卫生检查和公告的分页 CRUD 与状态流转；缴费流水、维修记录和业务状态同步写入
- 维修指派、相邻状态流转、完成后补充记录、部分缴费，以及独立的维修记录、收费记录和只读卫生记录页面
- 公告草稿、发布、编辑、撤回和独立发布入口；已发布公告不能退回草稿，发布时间按真实发布动作排序
- Dashboard 与宿舍、学生、入住申请、维修、费用、卫生、公告真实查询，近 30 天入住办理趋势来自入住记录聚合
- 宿舍新增、编辑、删除、容量校验、权限校验和删除冲突保护
- Redis Dashboard 短时缓存与不可用回退
- Ant Design 组件白名单注册和 Vite/Rolldown vendor 拆包，最大单个生产 JavaScript 块约 165KB
- 19 张表统一记录创建/更新操作人和时间，旧库迁移幂等补列、补索引并回填公告发布时间
- 可选演示数据、桌面与移动端响应式管理后台
- 独立 AI 控制面、带引用知识助手和自然语言固定指标 Dashboard
- 维修 AI 分诊与公告纯文本草稿，统一 proposal、step-up、审批、唯一执行租约和运行审计
- 确定性风险信号、人工处置、MySQL 持久 Kill Switch、预算/熔断/离线评测和红队门

当前非 AI 业务与 AI 首期阶段 0–6 的工程实现、测试和本地真实服务验收已完成；这不等于生产环境已放行。现行状态、证据和生产前剩余门统一见 [计划索引](./plan/README.md) 与 [AI 验证与验收](./plan/ai-verification.md)。

## 五分钟本地演示

演示环境需要 Windows PowerShell 5.1、Java 21、Maven、Node.js、npm 和 Docker Desktop。它只绑定本机 loopback，使用隔离数据库、脱敏 fixture 和确定性 Fake provider，不连接真实模型或生产资源。

1. 创建本地配置并替换密码占位值：

```powershell
Copy-Item .env.example .env
notepad .env
```

2. 首次安装前端依赖：

```powershell
Push-Location frontend
npm ci
Pop-Location
```

3. 从仓库根目录启动默认只读演示：

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-start.ps1 `
  -Mode demo-readonly
```

浏览器将打开 `http://127.0.0.1:5174`。使用 `.env` 中的 `BOOTSTRAP_ADMIN_USERNAME` 和 `BOOTSTRAP_ADMIN_PASSWORD` 登录。

4. 验证并停止：

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-health.ps1
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-stop.ps1
```

`demo-readonly` 的 AI 写执行关闭。需要展示现有提案、人工审批、step-up 和业务 Service 写链时，改用 `-Mode demo-approval`；该模式仍只写独立的本地演示库，不代表生产放行。

详细启动、reset 令牌、镜像源切换和演示顺序见 [本地演示指南](./docs/delivery/demo-guide.md)。

## 交付文档

- [本地演示指南](./docs/delivery/demo-guide.md)
- [用户手册](./docs/delivery/user-manual.md)
- [运维速查](./docs/delivery/operator-quickstart.md)
- [故障排查](./docs/delivery/troubleshooting.md)
- [演示验收清单](./docs/delivery/acceptance-checklist.md)
- [截图与视觉合同索引](./docs/delivery/screenshot-index.md)
- [公开资产与数据来源](./docs/delivery/asset-provenance.md)
- [当前 RC 发布说明](./docs/delivery/release-notes-rc-20260811.2.md)
- [已失效候选 `rc-20260811.1`](./docs/delivery/release-notes-rc-20260811.1.md)
- [贡献指南](./CONTRIBUTING.md)
- [安全策略](./SECURITY.md)
- [Apache License 2.0](./LICENSE)
- [第三方许可证说明](./THIRD_PARTY_NOTICES.md)

## 开发运行

1. 创建本地环境文件并修改所有密码占位符：

```powershell
Copy-Item .env.example .env
notepad .env
```

2. 启动 MySQL 和 Redis：

```powershell
docker compose up -d
```

`compose.yml` 会读取 `.env`。后端从 `backend` 目录启动时也会自动读取根目录 `.env`。

3. 启动后端：

```powershell
cd backend
mvn spring-boot:run
```

没有可用 MySQL 凭证时，可以使用只用于本地体验的 H2 Profile：

```powershell
$env:BOOTSTRAP_ADMIN_USERNAME="admin"
$env:BOOTSTRAP_ADMIN_PASSWORD="请设置一个本地测试密码"
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```

`dev` Profile 使用内存 H2 和演示数据，重启后数据会清空；默认 Profile 始终使用 MySQL。

4. 启动前端：

```powershell
cd ..\frontend
npm install
npm run dev
```

前端默认访问 `http://localhost:5173`，并通过 Vite 代理连接 `http://127.0.0.1:8080/api`。登录账号由 `.env` 中的 `BOOTSTRAP_ADMIN_USERNAME` 和 `BOOTSTRAP_ADMIN_PASSWORD` 决定。开发运行不会替代上面的可重复演示脚本。

## 演示数据

设置 `DEMO_DATA_ENABLED=true` 后，首次启动会在空表中写入脱敏的宿舍、学生、入住申请、维修、费用、卫生和公告样例。已有数据的表不会重复写入。

## 验证命令

```powershell
cd frontend
npm run test
npm run test:coverage
npm run typecheck
npm run build
npm audit --audit-level=high
npm exec playwright install chromium
npm run e2e
$env:VISUAL_DB_URL="jdbc:mysql://127.0.0.1:3306/student_dormitory_e2e?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
npm run e2e:ai-live
Remove-Item Env:VISUAL_DB_URL
npm run e2e:visual

cd ..\backend
mvn -q -P ai-coverage verify
mvn -q -Dtest=RealInfrastructureIT,AiRealInfrastructureIT,AiUploadRealInfrastructureIT test
```

Playwright 浏览器只安装到本机缓存，不进入仓库。受管 Windows 环境如需复用已安装浏览器，可仅在当前终端设置 `PLAYWRIGHT_BROWSER_EXECUTABLE`（普通 E2E）或 `VISUAL_BROWSER_EXECUTABLE`（AI-live/视觉）；不要把绝对浏览器路径写入配置或提交历史。

当前候选的后端、覆盖率、真实 MySQL/Redis、前端、普通 E2E、AI-live、六视口视觉、安全与供应链质量门均已通过。精确测试数、覆盖率、网络合同、制品哈希、视觉清单哈希和证据失效规则只在 [AI 验证与验收](./plan/ai-verification.md) 维护，避免多个入口文档再次产生不同口径。

AI-live 使用隔离的本机 `*_e2e` MySQL 和确定性 Fake provider，只证明真实 Spring Boot/MySQL/Redis/HTTP/SSE/事务链路，不代表真实模型供应商已完成生产验收。`npm run e2e:visual` 固定覆盖 22 个受保护路由、六个正式视口和 9 张最终 PNG 合同。

## 许可证

本项目采用 [Apache License 2.0](./LICENSE)。第三方依赖、字体与图标仍分别遵循其自身许可证，详见 [第三方许可证说明](./THIRD_PARTY_NOTICES.md)。

## 安全说明

- `.env` 已加入 `.gitignore`，不得提交真实密码或 Token。
- 登录 Cookie 使用 `HttpOnly` 与 `SameSite=Lax`；生产环境还应在 HTTPS 下启用 `Secure`。
- 前端菜单隐藏不是权限边界，所有写接口均执行后端权限检查和输入校验。
- 缴费模块仅记录校内账单和缴费状态，不连接真实支付网关。
- Maven 运行时基线为 Spring Boot 3.5.16、Logback 1.5.38、Jackson 2.21.5，并继续关闭 `ACCEPT_CASE_INSENSITIVE_PROPERTIES` 作为纵深防护。当前 OSV 主源结果、公告范围复核和结构化证据统一见 [AI 验证与验收](./plan/ai-verification.md)。
- 本地演示的 `.demo/`、`.env`、数据库数据、日志、trace、构建目录和测试报告均不属于公开交付范围。
- 真实供应商、生产 KMS、外部向量/对象/扫描、生产灾备、压测、部署和灰度仍为 `NOT RUN / NOT APPROVED`。
