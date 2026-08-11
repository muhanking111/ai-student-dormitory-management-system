# 真实基础设施集成验证

`RealInfrastructureIT` 连接仓库根目录 `.env` 指向的真实 MySQL 与 Redis。测试类以 `IT` 结尾，默认 `mvn test` 不会自动执行。

在 `backend` 目录运行：

```powershell
mvn -Dtest=RealInfrastructureIT test
```

运行前要求：

- 根目录 `.env` 必须包含可用的 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、Redis 配置和 `BOOTSTRAP_ADMIN_USERNAME` / `BOOTSTRAP_ADMIN_PASSWORD`。
- `.env` 指向的 MySQL、Redis 必须已经启动，管理员账号和 RBAC 基础数据必须存在。
- 缺少配置、服务不可达、数据库不是 MySQL、表或列契约不完整时，测试直接失败，不会静默跳过。

测试禁用演示数据及业务初始化器，只保留正式 schema migration。唯一临时业务链位于测试事务中并在结束后回滚；`@AfterTransaction` 会再次确认唯一数据不存在。Sa-Token 登录态和 Dashboard Redis 缓存会显式清理。
