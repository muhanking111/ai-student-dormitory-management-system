# 真实基础设施集成验证

真实 IT 仅允许 loopback 上名称以 `_e2e` 结尾的专用 MySQL 和 Redis DB 1–15。配置先读取根 `.env`，再由 `REAL_IT_` 前缀的进程环境变量覆盖；普通业务库和 Redis DB 0 会被拒绝。测试类以 `IT` 结尾，默认 `mvn test` 不执行。

在 `backend` 目录运行：

```powershell
$env:REAL_IT_DB_URL = 'jdbc:mysql://127.0.0.1:3307/student_dormitory_real_it_e2e?serverTimezone=Asia/Shanghai'
$env:REAL_IT_REDIS_HOST = '127.0.0.1'
$env:REAL_IT_REDIS_PORT = '6380'
$env:REAL_IT_REDIS_DATABASE = '11'
# 使用本机安全渠道设置 REAL_IT_DB_USERNAME / REAL_IT_DB_PASSWORD，不把凭据写入报告。
mvn -o -Dtest=RealInfrastructureIT,AiRealInfrastructureIT,AiUploadRealInfrastructureIT -Dsurefire.reportsDirectory=target/current-real-it test
```

运行前要求：

- 根目录 `.env` 必须包含可用的 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD`、Redis 配置和 `BOOTSTRAP_ADMIN_USERNAME` / `BOOTSTRAP_ADMIN_PASSWORD`。
- 覆盖后的 MySQL、Redis 必须已启动，专用库及其最小测试权限已配置，管理员账号和 RBAC 数据可由测试上下文初始化。
- 缺少配置、服务不可达、数据库不是 MySQL、表或列契约不完整时，测试直接失败，不会静默跳过。

`RealInfrastructureIT` 禁用演示数据及业务初始化器，只保留正式 schema migration。其临时业务链位于测试事务中并回滚，`@AfterTransaction` 再确认唯一数据不存在；Sa-Token 登录态与 Dashboard 缓存显式清理。AI IT 会留下专用测试审计等事实，不能将此描述为全套测试零写入；不要与 Demo 或浏览器套件共享同一数据库。
