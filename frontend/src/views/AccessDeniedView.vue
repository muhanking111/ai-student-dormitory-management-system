<template>
  <section class="panel access-denied" role="alert">
    <div class="access-denied__code">403</div>
    <h2>当前账号暂无可访问模块</h2>
    <p>请联系系统管理员为该角色分配至少一个业务读取权限，或从右上角退出后更换账号。</p>
  </section>
</template>

<style scoped>
.access-denied {
  display: grid;
  min-height: 360px;
  place-content: center;
  justify-items: center;
  padding: 40px;
  text-align: center;
}

.access-denied__code {
  color: #2563eb;
  font-size: 64px;
  font-weight: 700;
  line-height: 1;
}

.access-denied h2 {
  margin: 20px 0 8px;
  color: #1e293b;
}

.access-denied p {
  max-width: 520px;
  color: #64748b;
  line-height: 1.7;
}
</style>
