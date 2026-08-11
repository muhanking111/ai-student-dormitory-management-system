# 本地演示指南

本指南用于答辩、课程验收和本地功能展示。演示环境只绑定 `127.0.0.1`，使用隔离的 MySQL 数据库、Redis DB 和确定性 Fake provider，不访问真实模型供应商或生产资源。

## 准备

需要 Windows PowerShell 5.1、Java 21、Maven、Node.js、npm、Docker Desktop，以及可用的本地 `.env`：

```powershell
Copy-Item .env.example .env
notepad .env
```

至少替换以下占位值，且不要把 `.env` 提交到 Git：

```text
MYSQL_PASSWORD
MYSQL_ROOT_PASSWORD
BOOTSTRAP_ADMIN_USERNAME
BOOTSTRAP_ADMIN_PASSWORD
```

首次使用还需要前端依赖。可以提前运行 `npm ci`，也可以在启动时显式增加 `-InstallDependencies`。

## 模式

| 模式 | 数据边界 | AI provider | AI 写执行 |
| --- | --- | --- | --- |
| `demo-readonly` | `student_dormitory_readonly_demo`、Redis DB 12 | `fake` | 关闭 |
| `demo-approval` | `student_dormitory_approval_demo`、Redis DB 13 | `fake` | 仅在隔离库中按现有提案、人工审批、step-up 和 Service 链执行 |

默认端口为前端 `5174`、后端 `8081`、MySQL `3307`、Redis `6380`。端口被占用时脚本会拒绝启动，不会终止或接管未知进程。

## 五分钟启动

从仓库根目录运行只读模式：

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-start.ps1 `
  -Mode demo-readonly
```

脚本会启动本地 MySQL/Redis、后端和前端，导入脱敏 fixture，激活演示控制面，并在健康检查通过后打开浏览器。浏览器地址为 `http://127.0.0.1:5174`。

若当前网络不能访问 Docker Hub，可显式使用可访问且已验证的镜像源：

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-start.ps1 `
  -Mode demo-readonly `
  -MySqlImage public.ecr.aws/docker/library/mysql:8.4 `
  -RedisImage public.ecr.aws/docker/library/redis:7.4-alpine
```

只做环境预检而不启动服务：

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-start.ps1 `
  -Mode demo-readonly -PreflightOnly
```

## 健康检查

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-health.ps1
```

通过结果必须同时包含：前端首页和入口脚本 `200`、后端 health `200`、管理员登录和会话恢复 `200`、业务读取 `200`、provider 为 `fake`，以及 audit、prompt、tool catalog、budget 四项均为 `READY`。

## 建议演示顺序

1. 使用 `.env` 中的 `BOOTSTRAP_ADMIN_USERNAME` 和 `BOOTSTRAP_ADMIN_PASSWORD` 登录，刷新页面验证会话恢复和菜单 RBAC。
2. 打开 Dashboard，查看宿舍、学生、入住、维修、费用、卫生、公告和风险摘要。
3. 打开 AI 助手，展示流式响应、引用、无来源或低置信提示；说明当前是 Fake provider 的确定性本地链路。
4. 浏览维修、公告和风险页面，查看 AI 建议、差异预览、降级状态和人工处置入口。
5. 在审批中心查看提案、业务 diff、step-up、批准/拒绝/失效状态；只读模式不执行 AI 写操作。
6. 打开 AI 审计，查看运行、工具调用、引用、审批和执行时间线以及脱敏内容。
7. 浏览普通业务 CRUD 和状态流转，说明 AI 关闭时人工业务仍可使用。

需要展示真实审批写链时，先停止只读模式，再启动隔离的审批模式：

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-stop.ps1
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-start.ps1 `
  -Mode demo-approval
```

`demo-approval` 仍不访问真实供应商或生产资源。任何写入都必须经过现有权限、提案、预览、人工审批、step-up、审计和业务 Service 边界。

## 停止

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-stop.ps1
```

脚本只停止 `.demo/state.json` 中 PID 与启动时间均匹配的前后端进程树，以及本次拥有的 Compose 服务。若归属不匹配会拒绝终止。

## 重置

重置前先停止。重置令牌必须与目标完全一致：

```powershell
# 只读模式
$token = 'student_dormitory_readonly_demo|redis:12|dormitory:demo:readonly'
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-reset.ps1 `
  -ConfirmTarget $token

# 审批模式
$token = 'student_dormitory_approval_demo|redis:13|dormitory:demo:approval'
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-reset.ps1 `
  -ConfirmTarget $token
```

脚本只重建固定演示数据库，并只删除固定 Redis DB 中固定 prefix 的键。错误令牌会立即拒绝，不能使用通配数据库名、Redis DB 0 或其他 prefix。

## 日志和本地状态

- 状态：`.demo/state.json`
- 后端标准输出：`.demo/logs/backend.out.log`
- 后端错误输出：`.demo/logs/backend.err.log`
- 前端标准输出：`.demo/logs/frontend.out.log`
- 前端错误输出：`.demo/logs/frontend.err.log`

`.demo/` 是本地运行目录，已从 Git 公开范围排除。不要在 issue、截图或 Release 中上传其中的原始内容。

## 边界

- 这是本地演示通过证据，不是预发布、生产或真实模型验收。
- 不启用生产开关，不调用真实供应商，不连接学校真实数据。
- 不使用演示环境保存真实姓名、学号、手机号、宿舍分配或维修隐私信息。
- Route B 的真实供应商、KMS、备份恢复、压测、部署和灰度门均未执行。
