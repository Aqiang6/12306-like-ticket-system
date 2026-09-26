<template>
  <div>
    <div class="page-head">
      <h1 class="page-title">{{ isEdit ? '编辑乘车人' : '添加乘车人' }}</h1>
      <p class="page-subtitle">
        {{ isEdit ? '编辑模式下仅支持修改手机号码' : '请如实填写乘车人证件信息' }}
      </p>
    </div>

    <div class="card" style="max-width: 640px">
      <div class="form-section">基本信息</div>
      <div class="grid-form">
        <div class="field">
          <span class="field-label required">证件类型</span>
          <select v-model.number="form.idType" class="select" :disabled="isEdit">
            <option :value="0">中国居民身份证</option>
          </select>
        </div>
        <div class="field">
          <span class="field-label required">姓名</span>
          <input v-model.trim="form.realName" class="input" :disabled="isEdit" placeholder="请输入姓名" />
        </div>
        <div class="field" style="grid-column: 1 / -1">
          <span class="field-label required">证件号码</span>
          <input v-model.trim="form.idCard" class="input" :disabled="isEdit" placeholder="请输入证件号码" />
        </div>
      </div>

      <div class="form-section">联系方式</div>
      <div class="grid-form">
        <div class="field">
          <span class="field-label required">手机号码</span>
          <input v-model.trim="form.phone" class="input" placeholder="请输入手机号码" />
          <span v-if="phoneError" class="field-error">格式不正确，请输入 11 位手机号</span>
        </div>
      </div>

      <div class="form-section">附加信息</div>
      <div class="grid-form">
        <div class="field">
          <span class="field-label required">优惠（待）类型</span>
          <select v-model.number="form.discountType" class="select" :disabled="isEdit">
            <option v-for="item in DISCOUNTS_TYPE" :key="item.value" :value="item.value">
              {{ item.label }}
            </option>
          </select>
        </div>
      </div>

      <hr class="divider" />

      <div class="row" style="justify-content: flex-end; gap: 10px">
        <button class="btn" @click="router.push('/passenger')">取消</button>
        <button class="btn btn-primary" :disabled="submitting" @click="onSubmit">
          <span v-if="submitting" class="spinner spinner-sm spinner-light" />
          保存
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { addPassenger, updatePassenger, getPassengerList } from '@/api'
import { DISCOUNTS_TYPE } from '@/constants'
import { getUsername } from '@/stores/auth'
import { toast } from '@/ui/toast'

const route = useRoute()
const router = useRouter()
const username = getUsername()

const isEdit = computed(() => route.query.type === 'edit')
const submitting = ref(false)
const phoneError = ref(false)

const form = reactive({
  realName: '',
  idType: 0,
  discountType: 0,
  phone: '',
  idCard: ''
})

onMounted(() => {
  if (route.query.type === 'edit') {
    getPassengerList({ username }).then((res) => {
      const found = (res.data ?? []).find((item) => item.id == route.query.id)
      if (found) {
        Object.assign(form, found)
      }
    })
  }
})

const onSubmit = () => {
  phoneError.value = !/^1(3|5|6|7|8)[0-9]{9}$/.test(form.phone)
  if (!form.realName || !form.idCard || !form.phone) {
    toast.error('请完善信息')
    return
  }
  if (phoneError.value) {
    toast.error('手机号格式不正确')
    return
  }

  submitting.value = true
  if (isEdit.value) {
    const { id, phone } = form
    updatePassenger({ id, phone, username })
      .then((res) => {
        if (res.success) {
          toast.success('乘车人修改成功')
          router.push('/passenger')
        } else {
          toast.error(res.message)
        }
      })
      .catch((error) => console.log(error))
      .finally(() => {
        submitting.value = false
      })
    return
  }

  addPassenger({ username, ...form })
    .then((res) => {
      if (res.success) {
        toast.success('乘车人创建成功')
        router.push('/passenger')
      } else {
        toast.error(res.message)
      }
    })
    .catch((error) => console.log(error))
    .finally(() => {
      submitting.value = false
    })
}
</script>

<style scoped>
.form-section {
  font-size: 15px;
  font-weight: 700;
  margin: 6px 0 14px;
  padding-left: 10px;
  border-left: 3px solid var(--accent);
}

.form-section:first-of-type {
  margin-top: 0;
}

.grid-form + .form-section {
  margin-top: 22px;
}

.spinner-light {
  border-color: rgba(255, 255, 255, 0.4);
  border-top-color: #fff;
}
</style>
