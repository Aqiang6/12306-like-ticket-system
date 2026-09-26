<template>
  <div>
    <div class="page-head">
      <h1 class="page-title">乘车人管理</h1>
      <p class="page-subtitle">维护常用乘车人信息，购票时可快速选择</p>
    </div>

    <div class="card">
      <div class="toolbar">
        <div class="input-search" style="width: 260px">
          <AppIcon class="icon" name="search" :size="15" />
          <input
            v-model.trim="searchName"
            class="input"
            placeholder="请输入乘客姓名"
            @keyup.enter="searchPassenger"
          />
        </div>
        <button class="btn" @click="searchPassenger">查询</button>
        <div style="flex: 1" />
        <button class="btn btn-danger" :disabled="!selectedIds.length" @click="batchDelete">
          <AppIcon name="trash" :size="15" />
          批量删除{{ selectedIds.length ? `（${selectedIds.length}）` : '' }}
        </button>
        <button class="btn btn-primary" @click="router.push('/addPassenger?type=create')">
          <AppIcon name="plus" :size="15" />
          添加乘车人
        </button>
      </div>

      <div v-if="loading" class="spin-wrap" style="min-height: 200px">
        <span class="spinner spinner-lg" />
      </div>

      <template v-else>
        <div v-if="passengerList.length" class="table-wrap">
          <table class="table">
            <thead>
              <tr>
                <th style="width: 44px">
                  <input
                    type="checkbox"
                    class="checkbox"
                    :checked="allChecked"
                    @change="toggleAll"
                  />
                </th>
                <th>姓名</th>
                <th>证件类型</th>
                <th>证件号码</th>
                <th>手机号码</th>
                <th>优惠类型</th>
                <th style="width: 140px">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="item in passengerList" :key="item.id">
                <td>
                  <input
                    type="checkbox"
                    class="checkbox"
                    :checked="selectedIds.includes(item.id)"
                    @change="toggleOne(item.id)"
                  />
                </td>
                <td style="font-weight: 600">{{ item.realName }}</td>
                <td>{{ item.idType === 0 ? '中国居民身份证' : '其他' }}</td>
                <td class="mono">{{ item.idCard }}</td>
                <td class="mono">{{ item.phone }}</td>
                <td>{{ label(DISCOUNTS_TYPE, item.discountType) }}</td>
                <td>
                  <div class="row" style="gap: 2px">
                    <button
                      class="btn btn-danger-text btn-sm"
                      @click="deletePassenger(item.id)"
                    >
                      <AppIcon name="trash" :size="14" />
                      删除
                    </button>
                    <button
                      class="btn btn-text btn-sm"
                      @click="router.push(`/addPassenger?type=edit&id=${item.id}`)"
                    >
                      <AppIcon name="edit" :size="14" />
                      编辑
                    </button>
                  </div>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-else class="empty">
          <AppIcon name="users" :size="44" />
          <span class="empty-text">暂无乘车人，点击右上角添加</span>
        </div>
      </template>
    </div>

    <div class="notice" style="margin-top: 16px">
      <div>如旅客身份信息未能在添加后的24小时内通过核验，请乘车人持有效身份证原件到车站办理身份核验。</div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import AppIcon from '@/components/AppIcon.vue'
import { getPassengerList, removePassenger } from '@/api'
import { DISCOUNTS_TYPE } from '@/constants'
import { getUsername } from '@/stores/auth'
import { toast } from '@/ui/toast'

const router = useRouter()
const username = getUsername()

const passengerList = ref([])
const loading = ref(false)
const searchName = ref('')
const selectedIds = ref([])

const label = (list, value) => list.find((item) => item.value === value)?.label ?? '--'

const allChecked = computed(
  () =>
    passengerList.value.length > 0 &&
    passengerList.value.every((item) => selectedIds.value.includes(item.id))
)

const loadPassengerList = () => {
  loading.value = true
  getPassengerList({ username })
    .then((res) => {
      if (!res.success) {
        toast.error(res.message)
        return
      }
      passengerList.value = res.data ?? []
      selectedIds.value = []
    })
    .catch((error) => {
      console.log(error)
      toast.error('乘车人加载失败')
    })
    .finally(() => {
      loading.value = false
    })
}

onMounted(loadPassengerList)

const searchPassenger = () => {
  if (!searchName.value) {
    loadPassengerList()
    return
  }
  passengerList.value = passengerList.value.filter(
    (item) => item.realName.includes(searchName.value)
  )
}

const toggleOne = (id) => {
  const index = selectedIds.value.indexOf(id)
  if (index === -1) selectedIds.value.push(id)
  else selectedIds.value.splice(index, 1)
}

const toggleAll = () => {
  selectedIds.value = allChecked.value
    ? []
    : passengerList.value.map((item) => item.id)
}

const deletePassenger = (id) => {
  removePassenger({ id, username }).then((res) => {
    if (!res.success) {
      toast.error(res.message)
      return
    }
    toast.success('删除成功')
    loadPassengerList()
  })
}

const batchDelete = () => {
  Promise.all(selectedIds.value.map((id) => removePassenger({ id, username })))
    .then(() => {
      toast.success('批量删除成功')
      loadPassengerList()
    })
    .catch((error) => {
      console.log(error)
      toast.error('批量删除失败')
    })
}
</script>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
  flex-wrap: wrap;
}

.checkbox {
  width: 16px;
  height: 16px;
  accent-color: var(--accent);
  cursor: pointer;
}
</style>
