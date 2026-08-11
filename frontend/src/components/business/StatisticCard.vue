<template>
  <div class="stat-card" :class="`stat-card--${card.color}`">
    <div class="stat-icon">
      <component :is="iconComponent" />
    </div>
    <div>
      <p class="stat-title">{{ card.title }}</p>
      <div class="stat-value">
        {{ card.value }}
        <span>{{ card.unit }}</span>
      </div>
      <p class="stat-change">{{ card.change }}</p>
    </div>
  </div>
</template>

<script setup lang="ts">
import {
  BankOutlined,
  HomeOutlined,
  TeamOutlined,
  ToolOutlined,
  WalletOutlined,
} from '@ant-design/icons-vue'
import { computed } from 'vue'
import type { StatisticCard } from '../../types/dormitory'

const props = defineProps<{ card: StatisticCard }>()

const iconComponent = computed(() => {
  const icons = {
    home: HomeOutlined,
    building: BankOutlined,
    team: TeamOutlined,
    bed: HomeOutlined,
    tool: ToolOutlined,
    wallet: WalletOutlined,
  }

  return icons[props.card.icon as keyof typeof icons] ?? HomeOutlined
})
</script>
