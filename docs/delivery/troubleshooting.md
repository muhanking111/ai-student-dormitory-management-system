# 本地演示故障排查

按“现象、检查、处理”顺序排查。不要通过关闭 RBAC、CSRF、Origin、审批、step-up、审计或状态机约束来绕过失败。

## Docker daemon 不可用

现象：启动前检查提示 Docker daemon 不可用或无法读取 Compose 状态。

```powershell
docker version
docker compose version
```

启动 Docker Desktop，等待 `docker version` 同时显示 Client 和 Server 后重试。不要删除未知容器或 Docker 数据目录。

## 镜像拉取失败

默认镜像为 `mysql:8.4` 和 `redis:7.4-alpine`。若 Docker Hub 在当前网络不可达，可使用已验证的 public ECR 地址：

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-start.ps1 `
  -Mode demo-readonly `
  -MySqlImage public.ecr.aws/docker/library/mysql:8.4 `
  -RedisImage public.ecr.aws/docker/library/redis:7.4-alpine
```

镜像覆盖值会写入本地状态，后续 stop/reset 使用同一镜像配置。

## `.env` 缺失或仍是占位值

现象：提示缺少 `.env`、必填项为空或仍为 `replace-with-*`。

```powershell
Copy-Item .env.example .env
notepad .env
```

只修改本机 `.env`，不要把真实值写回 `.env.example`、README、截图或日志。

## 前端依赖不存在

现象：提示 `frontend/node_modules` 不存在。

```powershell
Push-Location frontend
npm ci
Pop-Location
```

也可以在首次启动时显式增加 `-InstallDependencies`。脚本不会未经选择自动安装依赖。

## 端口已占用

默认端口：`5174`、`8081`、`3307`、`6380`。脚本发现占用会拒绝接管。

```powershell
Get-NetTCPConnection -State Listen -LocalPort 5174,8081,3307,6380 |
  Select-Object LocalAddress,LocalPort,OwningProcess
```

确认占用来源后，使用脚本参数选择四个互不相同的新端口。不要结束未知进程：

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-start.ps1 `
  -Mode demo-readonly -FrontendPort 15174 -BackendPort 18081 `
  -MySqlPort 13307 -RedisPort 16380
```

## 状态显示 `running`

先检查健康状态：

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-health.ps1
```

若服务仍属于当前演示环境，使用 `demo-stop.ps1` 正常停止后再启动。若 PID 与启动时间不匹配，停止脚本会拒绝处理；此时保留 `.demo/state.json` 和日志，人工确认进程归属，不要直接批量结束 Java/Node。

## MySQL 或 Redis 未达到 healthy

```powershell
docker compose -p dormitory-local-demo -f compose.yml ps
docker compose -p dormitory-local-demo -f compose.yml logs mysql
docker compose -p dormitory-local-demo -f compose.yml logs redis
```

检查端口、磁盘空间、镜像状态和 `.env` 密码。演示 Compose 只绑定 loopback；不要为排障改成 `0.0.0.0`。

## AI readiness 未就绪

健康结果要求 audit、prompt、tool catalog、budget 全为 `READY`。先查看后端日志：

```powershell
Get-Content .demo\logs\backend.out.log -Tail 200
Get-Content .demo\logs\backend.err.log -Tail 200
```

启动脚本会等待 AI schema 和六个内置 v1 prompt 完成导入，再初始化演示控制面。若仍未就绪，停止后重启；不要直接把控制项静态改成 READY，也不要关闭审计或预算门。

## 登录或会话恢复失败

确认使用 `.env` 中的 `BOOTSTRAP_ADMIN_USERNAME` 和 `BOOTSTRAP_ADMIN_PASSWORD`，并确认后端地址来自 `.demo/state.json`。演示端口发生变更时必须通过启动脚本传参，让前端 proxy 和 CORS 同步更新。

不要在浏览器控制台、命令历史或公开 issue 中粘贴密码、Cookie、Token 或完整请求头。

## 前端页面无法访问或 proxy 失败

```powershell
Get-Content .demo\logs\frontend.out.log -Tail 200
Get-Content .demo\logs\frontend.err.log -Tail 200
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-health.ps1
```

确认前端和后端均监听 `.demo/state.json` 记录的 loopback 端口。不要绕过 Vite proxy 直接在前端硬编码后端地址。

## SSE 中断或 AI 助手降级

先确认普通后端 health、登录会话和 AI readiness 均通过。Fake provider 仍经过真实 HTTP/SSE、权限、预算、审计和持久化链路；网络中断、会话失效或权限撤销时出现明确降级是预期安全行为，不应改成静态成功。

## reset 被拒绝

reset 只接受当前模式的精确令牌。先读取本地状态中的 mode、database、Redis DB 和 prefix，再使用 `demo-guide.md` 给出的固定格式。错误令牌被拒绝是预期行为。

不要修改脚本去接受通配数据库名、Redis DB 0、其他 prefix 或生产连接。

## 后端不可用演示后的恢复

重新启动前先运行停止脚本，确保本次持有的前后端和 Compose 服务已停止；随后运行原模式的 `demo-start.ps1` 和 `demo-health.ps1`。若需要恢复到全新 fixture，再执行精确 reset。

## 仍无法解决

保留以下本地信息用于复核，但先移除凭据、Cookie、个人路径和真实数据：

- 执行命令与退出码
- `.demo/state.json` 中非敏感字段
- 前后端日志的最小相关片段
- `docker compose ... ps` 状态
- `demo-health.ps1` 的失败控制项

不要上传 `.env`、数据库数据目录、完整日志包、trace、测试报告或 `.planning/`。
