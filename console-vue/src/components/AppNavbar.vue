<template>
  <header class="navbar glass">
    <div class="navbar-inner">
      <router-link to="/ticketSearch" class="brand">
        <span class="brand-mark">12306</span>
        <span class="brand-sub">铁路购票</span>
      </router-link>

      <div v-if="loggedIn" class="user-area">
        <div class="user-dropdown" ref="dropdownRef">
          <button class="user-trigger" @click="menuOpen = !menuOpen">
            <span class="avatar">{{ initial }}</span>
            <span class="user-name">{{ username }}</span>
            <AppIcon name="chevron-down" :size="13" />
          </button>
          <Transition name="fade">
            <div v-if="menuOpen" class="user-menu glass">
              <button class="user-menu-item" @click="go('/userInfo')">
                <AppIcon name="user" :size="15" />
                个人信息
              </button>
              <button class="user-menu-item" @click="go('/ticketList')">
                <AppIcon name="list" :size="15" />
                车票订单
              </button>
              <div class="user-menu-sep" />
              <button class="user-menu-item danger" @click="handleLogout">
                <AppIcon name="logout" :size="15" />
                退出登录
              </button>
            </div>
          </Transition>
        </div>
      </div>
      <div v-else class="user-area">
        <router-link to="/login" class="btn btn-primary btn-sm">登录 / 注册</router-link>
      </div>
    </div>
  </header>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { getUsername, isLoggedIn, clearSession } from '@/stores/auth'
import { logout as apiLogout } from '@/api'
import { toast } from '@/ui/toast'

const router = useRouter()
const route = useRoute()

const loggedIn = computed(() => isLoggedIn())
const username = computed(() => getUsername())
const initial = computed(() => username.value.slice(0, 1).toUpperCase() || 'U')

const menuOpen = ref(false)
const dropdownRef = ref(null)

const go = (path) => {
  menuOpen.value = false
  router.push(path)
}

const handleLogout = async () => {
  menuOpen.value = false
  try {
    await apiLogout()
  } catch (error) {
    console.log(error)
  }
  clearSession()
  toast.success('退出成功')
  router.push('/login')
}

const onDocClick = (event) => {
  if (dropdownRef.value && !dropdownRef.value.contains(event.target)) {
    menuOpen.value = false
  }
}

onMounted(() => document.addEventListener('click', onDocClick))
onBeforeUnmount(() => document.removeEventListener('click', onDocClick))

watch(
  () => route.fullPath,
  () => {
    menuOpen.value = false
  }
)
</script>

<style scoped>
.navbar {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  z-index: 500;
  height: var(--nav-height);
  border-bottom: 1px solid var(--separator);
}

.navbar-inner {
  height: 100%;
  padding: 0 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.brand {
  display: flex;
  align-items: baseline;
  gap: 8px;
  color: var(--text-1);
}

.brand-mark {
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.02em;
  background: linear-gradient(135deg, #0071e3, #5e5ce6);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.brand-sub {
  font-size: 13px;
  color: var(--text-2);
  font-weight: 500;
}

.user-area {
  display: flex;
  align-items: center;
}

.user-dropdown {
  position: relative;
}

.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  border: none;
  background: transparent;
  font-family: inherit;
  font-size: 14px;
  color: var(--text-1);
  cursor: pointer;
  padding: 5px 10px;
  border-radius: var(--radius-pill);
  transition: background 0.2s var(--ease);
}

.user-trigger:hover {
  background: var(--surface-2);
}

.avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  background: linear-gradient(135deg, #0a84ff, #5e5ce6);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

.user-name {
  max-width: 120px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.user-menu {
  position: absolute;
  top: calc(100% + 10px);
  right: 0;
  min-width: 180px;
  border-radius: var(--radius-md);
  box-shadow: var(--shadow-float), 0 0 0 1px var(--separator);
  padding: 6px;
  z-index: 50;
}

.user-menu-item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  border: none;
  background: transparent;
  font-family: inherit;
  font-size: 13.5px;
  color: var(--text-1);
  padding: 9px 12px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.15s var(--ease);
}

.user-menu-item:hover {
  background: var(--surface-2);
}

.user-menu-item.danger {
  color: var(--red);
}

.user-menu-item.danger:hover {
  background: var(--red-soft);
}

.user-menu-sep {
  height: 1px;
  background: var(--separator);
  margin: 5px 8px;
}
</style>
