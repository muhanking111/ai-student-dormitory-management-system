<template>
  <a-layout class="admin-shell" :class="{ 'admin-shell--collapsed': sidebarCollapsed }">
    <AppSidebar v-model:collapsed="sidebarCollapsed" />
    <a-layout class="admin-main">
      <AppHeader :sidebar-collapsed="sidebarCollapsed" @toggle-sidebar="toggleSidebar" />
      <a-layout-content class="admin-content">
        <router-view />
      </a-layout-content>
    </a-layout>
    <AiAssistantHost />
  </a-layout>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import AppHeader from './AppHeader.vue'
import AppSidebar from './AppSidebar.vue'
import AiAssistantHost from '../components/ai/AiAssistantHost.vue'

const sidebarCollapsed = ref(false)
const toggleSidebar = () => {
  sidebarCollapsed.value = !sidebarCollapsed.value
}
</script>
