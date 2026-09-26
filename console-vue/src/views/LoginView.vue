<template>
  <div class="login-page">
    <div class="login-glow glow-a" />
    <div class="login-glow glow-b" />

    <div class="login-card">
      <div class="login-brand">
        <div class="brand-mark">12306</div>
        <div class="brand-sub">铁路购票 · 安全出行</div>
      </div>

      <div class="segmented login-segmented">
        <button
          class="segmented-item"
          :class="{ active: mode === 'login' }"
          @click="mode = 'login'"
        >
          登录
        </button>
        <button
          class="segmented-item"
          :class="{ active: mode === 'register' }"
          @click="mode = 'register'"
        >
          注册
        </button>
      </div>

      <!-- 登录 -->
      <form v-if="mode === 'login'" class="login-form" @submit.prevent="handleLogin">
        <div class="field">
          <input
            v-model.trim="loginForm.usernameOrMailOrPhone"
            class="input input-lg"
            placeholder="用户名 / 邮箱 / 手机号"
            autocomplete="username"
          />
        </div>
        <div class="field">
          <input
            v-model="loginForm.password"
            class="input input-lg"
            type="password"
            placeholder="密码"
            autocomplete="current-password"
          />
        </div>
        <button class="btn btn-primary btn-lg btn-block" type="submit" :disabled="loading">
          <span v-if="loading" class="spinner spinner-sm" />
          登 录
        </button>
        <p class="login-tip">登录即可查询车票、在线购票与管理订单</p>
      </form>

      <!-- 注册 -->
      <form v-else class="login-form" @submit.prevent="handleRegister">
        <div class="grid-form">
          <div class="field">
            <span class="field-label required">用户名</span>
            <input v-model.trim="registerForm.username" class="input" placeholder="请输入用户名" />
          </div>
          <div class="field">
            <span class="field-label required">密码</span>
            <input v-model="registerForm.password" class="input" type="password" placeholder="请输入密码" />
          </div>
          <div class="field">
            <span class="field-label required">姓名</span>
            <input v-model.trim="registerForm.realName" class="input" placeholder="请输入真实姓名" />
          </div>
          <div class="field">
            <span class="field-label required">证件类型</span>
            <select v-model.number="registerForm.idType" class="select">
              <option :value="0">中国居民身份证</option>
            </select>
          </div>
          <div class="field">
            <span class="field-label required">证件号码</span>
            <input v-model.trim="registerForm.idCard" class="input" placeholder="请输入证件号码" />
          </div>
          <div class="field">
            <span class="field-label required">手机号码</span>
            <input v-model.trim="registerForm.phone" class="input" placeholder="请输入手机号码" />
          </div>
          <div class="field" style="grid-column: 1 / -1">
            <span class="field-label required">邮箱</span>
            <input v-model.trim="registerForm.mail" class="input" placeholder="请输入邮箱账号" />
          </div>
        </div>
        <button class="btn btn-primary btn-lg btn-block" type="submit" :disabled="loading">
          <span v-if="loading" class="spinner spinner-sm" />
          注 册
        </button>
      </form>
    </div>

    <p class="login-foot">12306 Console · 基于 Vue 3 + Vite 构建</p>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { login as apiLogin, register as apiRegister } from '@/api'
import { setSession } from '@/stores/auth'
import { toast } from '@/ui/toast'

const router = useRouter()

const mode = ref('login')
const loading = ref(false)

const loginForm = reactive({
  usernameOrMailOrPhone: '',
  password: ''
})

const registerForm = reactive({
  username: '',
  password: '',
  realName: '',
  idType: 0,
  idCard: '',
  phone: '',
  mail: ''
})

const handleLogin = () => {
  if (!loginForm.usernameOrMailOrPhone || !loginForm.password) {
    toast.error('请输入账号与密码')
    return
  }
  loading.value = true
  apiLogin(loginForm)
    .then((res) => {
      if (res.success) {
        setSession({
          token: res.data?.accessToken,
          username: res.data?.username,
          userId: res.data?.userId
        })
        toast.success('登录成功')
        router.push('/ticketSearch')
      } else {
        toast.error(res.message ?? '登录失败')
      }
    })
    .catch((error) => {
      console.log(error)
      toast.error('登录失败，请稍后重试')
    })
    .finally(() => {
      loading.value = false
    })
}

const handleRegister = () => {
  const required = ['username', 'password', 'realName', 'idCard', 'phone', 'mail']
  if (required.some((key) => !registerForm[key])) {
    toast.error('请完善注册信息')
    return
  }
  loading.value = true
  apiRegister(registerForm)
    .then((res) => {
      if (res.success) {
        toast.success('注册成功，请登录')
        mode.value = 'login'
        loginForm.usernameOrMailOrPhone = res.data?.username ?? registerForm.username
        loginForm.password = ''
      } else {
        toast.error(res.message ?? '注册失败')
      }
    })
    .catch((error) => {
      console.log(error)
      toast.error('注册失败，请稍后重试')
    })
    .finally(() => {
      loading.value = false
    })
}
</script>

<style scoped>
.login-page {
  position: relative;
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 32px 16px;
  background:
    radial-gradient(1000px 600px at 15% 10%, rgba(94, 92, 230, 0.35), transparent 60%),
    radial-gradient(900px 600px at 85% 20%, rgba(10, 132, 255, 0.4), transparent 60%),
    radial-gradient(800px 700px at 50% 100%, rgba(191, 90, 242, 0.28), transparent 60%),
    #0b0d17;
  overflow: hidden;
}

.login-glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.55;
  pointer-events: none;
  animation: drift 14s ease-in-out infinite alternate;
}

.glow-a {
  width: 420px;
  height: 420px;
  background: rgba(10, 132, 255, 0.5);
  top: -120px;
  left: -80px;
}

.glow-b {
  width: 480px;
  height: 480px;
  background: rgba(175, 82, 222, 0.4);
  bottom: -160px;
  right: -100px;
  animation-delay: -7s;
}

@keyframes drift {
  from { transform: translate3d(0, 0, 0) scale(1); }
  to { transform: translate3d(50px, 30px, 0) scale(1.12); }
}

.login-card {
  position: relative;
  z-index: 1;
  width: 100%;
  max-width: 420px;
  border-radius: 24px;
  padding: 32px 32px 28px;
  background: rgba(255, 255, 255, 0.82);
  -webkit-backdrop-filter: saturate(180%) blur(28px);
  backdrop-filter: saturate(180%) blur(28px);
  box-shadow: 0 24px 80px rgba(0, 0, 0, 0.35), inset 0 0 0 1px rgba(255, 255, 255, 0.5);
}

.login-brand {
  text-align: center;
  margin-bottom: 22px;
}

.login-brand .brand-mark {
  font-size: 34px;
  font-weight: 800;
  letter-spacing: -0.03em;
  background: linear-gradient(135deg, #0071e3, #5e5ce6);
  -webkit-background-clip: text;
  background-clip: text;
  -webkit-text-fill-color: transparent;
}

.login-brand .brand-sub {
  margin-top: 2px;
  color: var(--text-2);
  font-size: 13px;
}

.login-segmented {
  display: flex;
  width: 100%;
  margin-bottom: 22px;
}

.login-segmented .segmented-item {
  flex: 1;
  padding: 8px 0;
  font-size: 14px;
}

.login-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.input-lg {
  height: 44px;
  font-size: 15px;
  border-radius: 12px;
}

.login-tip {
  text-align: center;
  color: var(--text-2);
  font-size: 12.5px;
  margin-top: 4px;
}

.login-foot {
  position: relative;
  z-index: 1;
  margin-top: 26px;
  color: rgba(255, 255, 255, 0.45);
  font-size: 12px;
}

@media (max-width: 520px) {
  .login-card {
    padding: 24px 20px;
  }
}
</style>
