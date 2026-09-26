<template>
  <div class="pagination">
    <span class="page-info">共 {{ total }} 条</span>
    <button class="page-btn" :disabled="current <= 1" @click="go(current - 1)">
      <AppIcon name="chevron-left" :size="14" />
    </button>
    <button
      v-for="page in pages"
      :key="page"
      class="page-btn"
      :class="{ active: page === current }"
      @click="go(page)"
    >
      {{ page }}
    </button>
    <button class="page-btn" :disabled="current >= pageCount" @click="go(current + 1)">
      <AppIcon name="chevron-right" :size="14" />
    </button>
    <select
      class="select"
      style="width: 96px; height: 30px; font-size: 13px"
      :value="pageSize"
      @change="changeSize($event.target.value)"
    >
      <option v-for="size in sizeOptions" :key="size" :value="size">{{ size }} 条/页</option>
    </select>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import AppIcon from './AppIcon.vue'

const props = defineProps({
  current: { type: Number, default: 1 },
  pageSize: { type: Number, default: 10 },
  total: { type: Number, default: 0 },
  sizeOptions: { type: Array, default: () => [10, 20, 50] }
})

const emit = defineEmits(['change'])

const pageCount = computed(() => Math.max(1, Math.ceil(props.total / props.pageSize)))

const pages = computed(() => {
  const count = pageCount.value
  const current = props.current
  let start = Math.max(1, current - 2)
  let end = Math.min(count, start + 4)
  start = Math.max(1, end - 4)
  const list = []
  for (let i = start; i <= end; i++) list.push(i)
  return list
})

const go = (page) => {
  if (page < 1 || page > pageCount.value || page === props.current) return
  emit('change', page, props.pageSize)
}

const changeSize = (size) => {
  emit('change', 1, Number(size))
}
</script>
