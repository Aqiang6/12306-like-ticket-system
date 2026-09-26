<template>
  <component :is="route.meta.bare ? 'div' : AppLayout">
    <router-view />
  </component>
  <ToastHost />
</template>

<script setup>
import { defineComponent, h } from 'vue'
import { RouterView, useRoute } from 'vue-router'
import ToastHost from '@/components/ToastHost.vue'
import AppNavbar from '@/components/AppNavbar.vue'
import AppSidebar from '@/components/AppSidebar.vue'

const route = useRoute()

// 常规页面布局：顶部玻璃导航 + 侧边栏 + 内容区
const AppLayout = defineComponent({
  name: 'AppLayout',
  setup() {
    return () =>
      h('div', { class: 'app-shell' }, [
        h(AppNavbar),
        h('div', { class: 'app-body' }, [
          h(AppSidebar),
          h('main', { class: 'app-main' }, [
            h('div', { class: 'app-content' }, [h(RouterView)])
          ])
        ])
      ])
  }
})
</script>
