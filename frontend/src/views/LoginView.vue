<template>
  <main class="login-shell">
    <section class="login-brand" aria-label="系统标识">
      <div class="login-brand-mark"><HomeOutlined /></div>
      <div>
        <p>高校后勤管理平台</p>
        <h1>学生宿舍管理系统</h1>
        <span>统一管理住宿资源、学生入住与后勤业务</span>
      </div>
    </section>

    <section class="login-form-section">
      <div class="login-form-wrap">
        <p class="login-eyebrow">管理后台</p>
        <h2>账号登录</h2>
        <p class="login-subtitle">请输入管理员账号与密码</p>
        <a-alert v-if="errorMessage" :message="errorMessage" type="error" show-icon closable @close="errorMessage = ''" />
        <a-form :model="form" layout="vertical" @finish="handleSubmit">
          <a-form-item label="用户名" name="username" :rules="[{ required: true, message: '请输入用户名' }]">
            <a-input id="login-username" v-model:value="form.username" aria-label="用户名" size="large" autocomplete="username" placeholder="请输入用户名">
              <template #prefix><UserOutlined /></template>
            </a-input>
          </a-form-item>
          <a-form-item label="密码" name="password" :rules="[{ required: true, message: '请输入密码' }]">
            <a-input-password id="login-password" v-model:value="form.password" aria-label="密码" size="large" autocomplete="current-password" placeholder="请输入密码">
              <template #prefix><LockOutlined /></template>
            </a-input-password>
          </a-form-item>
          <a-button type="primary" html-type="submit" aria-label="登录" size="large" block :loading="auth.loading">登录</a-button>
        </a-form>
      </div>
    </section>
  </main>
</template>

<script setup lang="ts">
import { HomeOutlined, LockOutlined, UserOutlined } from '@ant-design/icons-vue'
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const errorMessage = ref(route.query.expired
  ? '登录已过期，请重新登录'
  : route.query.unavailable
    ? '暂时无法连接后端服务，请检查服务状态'
    : '')
const form = reactive({ username: '', password: '' })

const handleSubmit = async () => {
  errorMessage.value = ''
  try {
    await auth.login(form)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/'
    await router.replace(redirect)
  } catch (error) {
    errorMessage.value = error instanceof Error ? error.message : '登录失败'
  }
}
</script>
