<template>
  <span class="status-tag" :class="`status-tag--${tone}`">{{ value }}</span>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import type { StatusTone } from '../../types/dormitory'

const statusToneMap: Record<string, StatusTone> = {
  入住中: 'success', 已入住: 'success', 已通过: 'success', 已发布: 'success', 已完成: 'success', 已缴: 'success', 优秀: 'success',
  待审核: 'warning', 待处理: 'warning', 部分缴: 'warning', 一般: 'warning',
  已满: 'danger', 已拒绝: 'danger', 未缴: 'danger', 不合格: 'danger',
  处理中: 'info', 良好: 'info', 草稿: 'default', 已撤回: 'default', 未入住: 'default',
}

const props = defineProps<{ value: string }>()
const tone = computed(() => statusToneMap[props.value] ?? 'default')
</script>
