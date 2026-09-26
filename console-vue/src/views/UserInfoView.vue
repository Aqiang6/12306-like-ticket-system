<template>
  <div>
    <div class="page-head">
      <h1 class="page-title">个人信息</h1>
      <p class="page-subtitle">查看与维护您的账户资料</p>
    </div>

    <div class="profile-grid">
      <!-- 基本信息 -->
      <div class="card profile-card">
        <div class="profile-hero">
          <div class="profile-avatar">{{ initial }}</div>
          <div>
            <div class="profile-name">{{ value('realName') || value('username') || '--' }}</div>
            <div class="profile-account text-secondary">{{ value('username') }}</div>
          </div>
        </div>

        <hr class="divider" />

        <div class="info-list">
          <div v-for="row in baseRows" :key="row.label" class="info-row">
            <span class="info-label">{{ row.label }}</span>
            <span class="info-value" :class="row.valueClass">{{ row.render() }}</span>
          </div>
        </div>
      </div>

      <!-- 联系方式 -->
      <div class="card">
        <div class="card-head">
          <div class="card-title">联系方式</div>
          <button v-if="contactEditing" class="btn btn-text" @click="contactEditing = false">
            取消
          </button>
          <button
            v-else
            class="btn btn-text"
            @click="openContactEdit"
          >
            <AppIcon name="edit" :size="14" />
            编辑
          </button>
        </div>

        <div v-if="!contactEditing" class="info-list">
          <div class="info-row">
            <span class="info-label">手机号</span>
            <span class="info-value">{{ contact.phone || '--' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">邮箱</span>
            <span class="info-value">{{ contact.mail || '--' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">地址</span>
            <span class="info-value">{{ contact.address || '--' }}</span>
          </div>
          <div class="info-row">
            <span class="info-label">邮编</span>
            <span class="info-value">{{ contact.postCode || '--' }}</span>
          </div>
        </div>

        <div v-else class="grid-form">
          <div class="field">
            <span class="field-label">手机号</span>
            <input class="input" :value="contact.phone" disabled />
          </div>
          <div class="field">
            <span class="field-label">邮箱</span>
            <input v-model.trim="contact.mail" class="input" placeholder="请输入邮箱" />
          </div>
          <div class="field">
            <span class="field-label">地址</span>
            <input v-model.trim="contact.address" class="input" placeholder="请输入地址" />
          </div>
          <div class="field">
            <span class="field-label">邮编</span>
            <input v-model.trim="contact.postCode" class="input" placeholder="请输入邮编" />
          </div>
          <div style="grid-column: 1 / -1" class="row">
            <button class="btn btn-primary" :disabled="saving" @click="saveContact">
              <span v-if="saving" class="spinner spinner-sm spinner-light" />
              保存修改
            </button>
          </div>
        </div>
      </div>

      <!-- 附加信息 -->
      <div class="card">
        <div class="card-head">
          <div class="card-title">附加信息</div>
          <button
            v-if="!typeEditing"
            class="btn btn-text"
            @click="typeEditing = true"
          >
            <AppIcon name="edit" :size="14" />
            编辑
          </button>
        </div>

        <div v-if="!typeEditing" class="info-list">
          <div class="info-row">
            <span class="info-label">优惠（待）类型</span>
            <span class="info-value">{{ label(DISCOUNTS_TYPE, userType) }}</span>
          </div>
        </div>

        <div v-else class="grid-form">
          <div class="field">
            <span class="field-label required">优惠（待）类型</span>
            <select v-model.number="userType" class="select">
              <option v-for="item in DISCOUNTS_TYPE" :key="item.value" :value="item.value">
                {{ item.label }}
              </option>
            </select>
          </div>
          <div style="grid-column: 1 / -1" class="row">
            <button class="btn btn-primary" :disabled="saving" @click="saveUserType">
              <span v-if="saving" class="spinner spinner-sm spinner-light" />
              保存修改
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import AppIcon from '@/components/AppIcon.vue'
import { getUserInfo, updateUserInfo } from '@/api'
import { REGIN_MAP, CHECK_STATUS, DISCOUNTS_TYPE } from '@/constants'
import { getUsername } from '@/stores/auth'
import { toast } from '@/ui/toast'

const username = getUsername()

const state = reactive({
  info: {}
})

const contact = reactive({
  phone: '',
  mail: '',
  address: '',
  postCode: ''
})

const contactEditing = ref(false)
const typeEditing = ref(false)
const saving = ref(false)
const userType = ref(undefined)

const initial = computed(() => (state.info.realName || username || 'U').slice(0, 1).toUpperCase())

const value = (key) => state.info[key]

const label = (list, value, key = 'value') =>
  list.find((item) => item[key] === value)?.label ?? '--'

const baseRows = computed(() => [
  { label: '用户名', render: () => value('username') ?? '--' },
  { label: '姓名', render: () => value('realName') ?? '--' },
  { label: '国家/地区', render: () => label(REGIN_MAP, value('region')) },
  {
    label: '证件类型',
    render: () => (value('idType') === 0 ? '中国居民身份证' : '未知')
  },
  { label: '证件号码', render: () => value('idCard') ?? '--' },
  {
    label: '核验状态',
    render: () => label(CHECK_STATUS, value('verifyStatus')),
    valueClass: value('verifyStatus') === 0 ? 'text-success' : 'text-orange'
  }
])

onMounted(() => {
  getUserInfo({ username }).then((res) => {
    if (!res.success) return
    state.info = res.data ?? {}
    contact.phone = res.data?.phone ?? ''
    contact.mail = res.data?.mail ?? ''
    contact.address = res.data?.address ?? ''
    contact.postCode = res.data?.postCode ?? ''
    userType.value = res.data?.userType
  })
})

const openContactEdit = () => {
  contactEditing.value = true
}

const saveContact = () => {
  saving.value = true
  updateUserInfo({
    username,
    mail: contact.mail,
    postCode: contact.postCode,
    address: contact.address
  })
    .then((res) => {
      if (res.success) {
        contactEditing.value = false
        toast.success('修改信息成功')
      } else {
        toast.error(res.message)
      }
    })
    .catch((error) => {
      console.log(error)
      toast.error('修改失败，请稍后重试')
    })
    .finally(() => {
      saving.value = false
    })
}

const saveUserType = () => {
  saving.value = true
  updateUserInfo({ username, userType: userType.value })
    .then((res) => {
      if (res.success) {
        typeEditing.value = false
        toast.success('修改信息成功')
      } else {
        toast.error(res.message)
      }
    })
    .catch((error) => {
      console.log(error)
      toast.error('修改失败，请稍后重试')
    })
    .finally(() => {
      saving.value = false
    })
}
</script>

<style scoped>
.profile-grid {
  display: grid;
  grid-template-columns: 1fr 1.4fr;
  gap: 16px;
  align-items: start;
}

@media (max-width: 980px) {
  .profile-grid {
    grid-template-columns: 1fr;
  }
}

.profile-hero {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 6px 0 14px;
}

.profile-avatar {
  width: 62px;
  height: 62px;
  border-radius: 50%;
  background: linear-gradient(135deg, #0a84ff, #5e5ce6);
  color: #fff;
  font-size: 26px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.profile-name {
  font-size: 19px;
  font-weight: 700;
}

.profile-account {
  margin-top: 2px;
  font-size: 13px;
}

.info-list {
  display: flex;
  flex-direction: column;
}

.info-row {
  display: grid;
  grid-template-columns: 120px 1fr;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px solid var(--separator);
  font-size: 14px;
}

.info-row:last-child {
  border-bottom: none;
}

.info-label {
  color: var(--text-2);
}

.info-value {
  font-weight: 500;
  word-break: break-all;
}

.spinner-light {
  border-color: rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
}
</style>
