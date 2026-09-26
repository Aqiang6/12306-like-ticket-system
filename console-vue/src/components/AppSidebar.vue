<template>
  <aside class="sidebar">
    <nav class="side-nav">
      <div class="side-group">车票</div>
      <router-link
        v-for="item in ticketItems"
        :key="item.path"
        :to="item.path"
        class="side-item"
        :class="{ active: route.path === item.path }"
      >
        <AppIcon :name="item.icon" :size="17" />
        {{ item.label }}
      </router-link>

      <div class="side-group">常用信息</div>
      <router-link
        v-for="item in profileItems"
        :key="item.path"
        :to="item.path"
        class="side-item"
        :class="{ active: route.path === item.path }"
      >
        <AppIcon :name="item.icon" :size="17" />
        {{ item.label }}
      </router-link>

      <div class="side-group">订单</div>
      <router-link
        v-for="item in orderItems"
        :key="item.path"
        :to="item.path"
        class="side-item"
        :class="{ active: route.path === item.path }"
      >
        <AppIcon :name="item.icon" :size="17" />
        {{ item.label }}
      </router-link>
    </nav>

    <div class="side-foot">
      <button class="btn btn-ghost" style="width: 100%; justify-content: flex-start" @click="handleLogout">
        <AppIcon name="logout" :size="15" />
        退出登录
      </button>
    </div>
  </aside>
</template>

<script setup>
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { clearSession } from '@/stores/auth'
import { logout as apiLogout } from '@/api'
import { toast } from '@/ui/toast'

const route = useRoute()
const router = useRouter()

const ticketItems = [
  { path: '/ticketSearch', label: '车票查询', icon: 'search' }
]

const profileItems = [
  { path: '/userInfo', label: '个人信息', icon: 'user' },
  { path: '/passenger', label: '乘车人管理', icon: 'users' }
]

const orderItems = [
  { path: '/ticketList', label: '车票订单', icon: 'list' },
  { path: '/personalTicket', label: '本人车票', icon: 'ticket' }
]

const handleLogout = async () => {
  try {
    await apiLogout()
  } catch (error) {
    console.log(error)
  }
  clearSession()
  toast.success('退出成功')
  router.push('/login')
}
</script>

<style scoped>
.sidebar {
  position: fixed;
  top: var(--nav-height);
  left: 0;
  bottom: 0;
  width: var(--sider-width);
  display: flex;
  flex-direction: column;
  padding: 20px 14px 16px;
  border-right: 1px solid var(--separator);
  background: rgba(245, 245, 247, 0.6);
  -webkit-backdrop-filter: saturate(180%) blur(20px);
  backdrop-filter: saturate(180%) blur(20px);
  z-index: 400;
}

.side-nav {
  flex: 1;
  overflow-y: auto;
}

.side-group {
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
  color: var(--text-3);
  padding: 0 12px;
  margin: 18px 0 6px;
  text-transform: uppercase;
}

.side-group:first-child {
  margin-top: 0;
}

.side-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 12px;
  border-radius: 10px;
  font-size: 14px;
  font-weight: 500;
  color: var(--text-1);
  margin-bottom: 2px;
  transition: all 0.18s var(--ease);
}

.side-item:hover {
  background: var(--surface-2);
}

.side-item.active {
  background: var(--accent-soft);
  color: var(--accent);
}

.side-foot {
  border-top: 1px solid var(--separator);
  padding-top: 12px;
}
</style>
