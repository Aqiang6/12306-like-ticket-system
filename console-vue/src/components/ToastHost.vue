<template>
  <div class="toast-host">
    <TransitionGroup name="toast">
      <div v-for="item in state.items" :key="item.id" class="toast" :class="`toast-${item.type}`">
        <AppIcon class="icon" :name="iconMap[item.type]" :size="16" />
        <span>{{ item.content }}</span>
      </div>
    </TransitionGroup>
  </div>
</template>

<script setup>
import AppIcon from '@/components/AppIcon.vue'
import { useToastState } from '@/ui/toast'

const state = useToastState()

const iconMap = {
  success: 'check-circle',
  error: 'warning',
  info: 'info'
}
</script>
