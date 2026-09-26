<template>
  <Teleport to="body">
    <Transition name="modal-fade">
      <div v-if="modelValue" class="modal-overlay" @click.self="handleOverlayClick">
        <div class="modal" :style="{ maxWidth: width }">
          <div class="modal-head">
            <div class="modal-title">{{ title }}</div>
            <button v-if="closable" class="btn btn-text" style="height: 30px; padding: 0 6px" @click="close">
              <AppIcon name="close" :size="16" />
            </button>
          </div>
          <div class="modal-body">
            <slot />
          </div>
          <div v-if="$slots.footer" class="modal-foot">
            <slot name="footer" />
          </div>
        </div>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { watch } from 'vue'
import AppIcon from './AppIcon.vue'

const props = defineProps({
  modelValue: Boolean,
  title: { type: String, default: '' },
  width: { type: String, default: '520px' },
  closable: { type: Boolean, default: true },
  maskClosable: { type: Boolean, default: true }
})

const emit = defineEmits(['update:modelValue'])

const close = () => emit('update:modelValue', false)

const handleOverlayClick = () => {
  if (props.maskClosable) close()
}

watch(
  () => props.modelValue,
  (open) => {
    document.body.style.overflow = open ? 'hidden' : ''
  }
)
</script>
