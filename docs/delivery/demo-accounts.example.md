# 演示账号模板

演示脚本只创建一个由本地 `.env` 控制的 bootstrap 管理员，不在仓库中保存固定密码。

## 本地配置

在未提交的 `.env` 中设置：

```dotenv
BOOTSTRAP_ADMIN_USERNAME=demo-admin
BOOTSTRAP_ADMIN_PASSWORD=<仅用于本机演示的强密码>
```

同时替换 `.env.example` 中的 MySQL 应用密码和 root 密码占位值。不要复用 GitHub、学校统一身份、邮箱或生产数据库密码。

## 使用范围

| 项目 | 说明 |
| --- | --- |
| 账号来源 | 当前机器未提交的 `.env` |
| 角色 | `ADMIN` bootstrap 管理员 |
| 数据 | 脱敏演示 fixture |
| provider | 确定性 `fake` |
| 允许环境 | 本机 `127.0.0.1` 演示环境 |
| 禁止用途 | 生产、预发布、学校真实账号、公开截图或公开日志 |

健康脚本会使用同一组环境值验证登录、会话恢复、管理员角色、业务读取和 AI readiness，但不会把密码写入 `.demo/state.json` 或输出 JSON。

## 交接检查

1. 交接前确认 `.env` 未被 Git 跟踪：`git check-ignore -v .env`。
2. 演示结束后运行 `scripts/demo-stop.ps1`。
3. 需要清除演示数据时，按 `demo-guide.md` 使用精确 reset 令牌。
4. 不发送 `.env`、Cookie、日志、数据库目录或 `.demo/`；由接收者从 `.env.example` 自行创建本地配置。
