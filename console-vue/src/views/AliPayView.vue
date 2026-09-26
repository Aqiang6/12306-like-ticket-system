<template>
  <!-- 支付宝表单自动提交跳转 -->
  <div v-html="html"></div>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const html = ref(decodeURIComponent(route.query.body ?? ''))

setTimeout(() => {
  document.forms[0]?.submit()
}, 100)
</script>
