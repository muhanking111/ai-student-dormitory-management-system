# 本地运维速查

## 运行边界

本页只适用于本地演示。服务绑定 `127.0.0.1`，默认端口为前端 `5174`、后端 `8081`、MySQL `3307`、Redis `6380`。不得把本配置直接用于预发布或生产。

## 预检

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-start.ps1 `
  -Mode demo-readonly -PreflightOnly
```

确认输出中的数据库、Redis DB/prefix、端口、provider 和写执行状态符合预期。预检不启动服务。

## 启动

```powershell
# 默认答辩/浏览模式
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-start.ps1 `
  -Mode demo-readonly -NoBrowser

# 隔离审批写链演示
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-start.ps1 `
  -Mode demo-approval -NoBrowser
```

首次缺少 `frontend/node_modules` 时，先运行 `npm ci`，或显式增加 `-InstallDependencies`。脚本不会无提示安装依赖。

## 健康检查

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-health.ps1
```

PASS 必须同时覆盖前端、后端、登录、会话恢复、业务读取、AI provider、写执行模式和四项 readiness。只检查 `/api/health` 不能替代完整演示健康门。

## 日志

```powershell
Get-Content .demo\logs\backend.out.log -Tail 200
Get-Content .demo\logs\backend.err.log -Tail 200
Get-Content .demo\logs\frontend.out.log -Tail 200
Get-Content .demo\logs\frontend.err.log -Tail 200
docker compose -p dormitory-local-demo -f compose.yml ps
```

日志、状态、Cookie、数据库内容和 `.env` 都是本地材料，不属于公开 Release。

## 停止

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-stop.ps1
```

停止脚本验证 PID 与启动时间，不匹配时拒绝处理。不要用批量 `Stop-Process java,node` 或关闭未知 MySQL/Redis 实例代替。

## 重启

```powershell
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-stop.ps1
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-start.ps1 `
  -Mode demo-readonly -NoBrowser
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-health.ps1
```

重启保留当前演示数据库。需要全新 fixture 时使用精确 reset。

## 重置

```powershell
$token = 'student_dormitory_readonly_demo|redis:12|dormitory:demo:readonly'
PowerShell -NoProfile -ExecutionPolicy Bypass -File scripts\demo-reset.ps1 `
  -ConfirmTarget $token
```

审批模式令牌为 `student_dormitory_approval_demo|redis:13|dormitory:demo:approval`。错误令牌、未知数据库、Redis DB 0 或未知 prefix 必须被拒绝。

## 本地备份边界

演示数据可由 fixture 和 reset 重建，默认不制作数据库 dump。若答辩前需要保留临时状态，只允许在本机受控目录保存，并在交付、提交或截图前删除；不要把 dump、Docker volume、`.demo/` 或日志加入 Git。

生产备份加密、恢复、跨实例/跨区和 RPO/RTO 演练属于 Route B，当前为 `NOT RUN / NOT APPROVED`。

## 回滚

本地演示的安全回滚顺序：

1. 运行 `demo-stop.ps1`，确认自有端口关闭。
2. 需要清除状态时执行精确 reset。
3. 使用已发布 tag 的源码重新启动只读模式。
4. 运行完整 `demo-health.ps1`。

不要通过关闭权限、CSRF、Origin、审计、预算、审批、step-up 或状态机来恢复服务。

## 发布前检查

- `git status --short` 只包含预期源码和公开文档。
- `.env`、`.demo/`、`target/`、`node_modules/`、`dist/`、coverage、test-results、日志和 trace 均被忽略。
- `PowerShell -File scripts/tests/demo-scripts-contract.ps1` PASS。
- `docker compose -p dormitory-local-demo -f compose.yml config --quiet` PASS。
- 本地演示停止后，`5174/8081/3307/6380` 不再监听。
