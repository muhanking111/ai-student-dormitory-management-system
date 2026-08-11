<template>
  <section class="panel chart-card">
    <div class="panel-title">
      <h2>{{ title }}</h2>
      <RouterLink v-if="more && moreTo" :to="moreTo">更多 &gt;</RouterLink>
      <span v-else-if="more" class="more-label">更多 &gt;</span>
    </div>
    <slot />
  </section>
</template>

<script setup lang="ts">
import { RouterLink } from 'vue-router'
import type { RouteLocationRaw } from 'vue-router'

defineProps<{
  title: string
  more?: boolean
  moreTo?: RouteLocationRaw
}>()
</script>

<style scoped>
.more-label {
  color: #64748b;
  font-size: 12px;
}
</style>
