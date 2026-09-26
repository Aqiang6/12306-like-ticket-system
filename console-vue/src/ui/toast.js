import { reactive } from 'vue'

const state = reactive({
  items: []
})

let seq = 0

function push(type, content, duration = 2600) {
  const id = ++seq
  state.items.push({ id, type, content })
  setTimeout(() => {
    const index = state.items.findIndex((item) => item.id === id)
    if (index !== -1) state.items.splice(index, 1)
  }, duration)
}

export const toast = {
  success: (content) => push('success', content),
  error: (content) => push('error', content),
  info: (content) => push('info', content)
}

export function useToastState() {
  return state
}
