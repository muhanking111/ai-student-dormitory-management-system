<template>
  <a-config-provider :locale="zhCN" :theme="appTheme">
    <router-view />
  </a-config-provider>
</template>

<script setup lang="ts">
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import { appTheme } from './design/theme'
</script>
