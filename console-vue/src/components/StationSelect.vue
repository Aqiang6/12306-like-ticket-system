<template>
  <div class="station-select" ref="rootRef">
    <input
      class="input station-input"
      :value="display"
      :placeholder="placeholder"
      @input="onInput"
      @focus="open = true"
      @keydown.esc="open = false"
    />
    <Transition name="fade">
      <div v-if="open && filtered.length" class="station-pop glass">
        <button
          v-for="item in filtered"
          :key="item.code"
          type="button"
          class="station-option"
          @click="select(item)"
        >
          <span class="station-name">{{ item.name }}</span>
          <span class="station-code">{{ item.code }}</span>
        </button>
      </div>
    </Transition>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps({
  modelValue: { type: String, default: '' },
  stations: { type: Array, default: () => [] },
  placeholder: { type: String, default: '请选择车站' }
})

const emit = defineEmits(['update:modelValue'])

const open = ref(false)
const keyword = ref('')
const rootRef = ref(null)

const display = computed(() => {
  if (open.value) return keyword.value
  const found = props.stations.find((item) => item.code === props.modelValue)
  return found?.name ?? props.modelValue ?? ''
})

const filtered = computed(() => {
  const list = props.stations
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return list.slice(0, 30)
  return list
    .filter(
      (item) =>
        item.name.toLowerCase().includes(kw) ||
        item.code.toLowerCase().includes(kw) ||
        (item.pinyin ?? '').toLowerCase().startsWith(kw)
    )
    .slice(0, 30)
})

const onInput = (event) => {
  keyword.value = event.target.value
  open.value = true
}

const select = (item) => {
  emit('update:modelValue', item.code)
  keyword.value = ''
  open.value = false
}

const onDocClick = (event) => {
  if (rootRef.value && !rootRef.value.contains(event.target)) {
    keyword.value = ''
    open.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))
</script>

<style scoped>
.station-select {
  position: relative;
  width: 100%;
}

.station-input {
  cursor: pointer;
}

.station-pop {
  position: absolute;
  top: calc(100% + 6px);
  left: 0;
  right: 0;
  z-index: 60;
  max-height: 264px;
  overflow-y: auto;
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-float), 0 0 0 1px var(--separator);
  padding: 5px;
}

.station-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  border: none;
  background: transparent;
  font-family: inherit;
  font-size: 14px;
  color: var(--text-1);
  padding: 8px 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s var(--ease);
}

.station-option:hover {
  background: var(--accent-soft);
}

.station-code {
  font-size: 12px;
  color: var(--text-3);
  font-variant-numeric: tabular-nums;
}
</style>
