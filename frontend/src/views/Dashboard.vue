<template>
  <AppLayout>
    <div class="page">
      <h2 class="page-title">学习看板</h2>
      <p class="page-sub">当前账号：{{ auth.user?.email }}（{{ auth.user?.role }}）</p>
      <el-row :gutter="16">
        <el-col :span="6" v-for="c in cards" :key="c.label">
          <el-card shadow="hover"><div class="card-label">{{ c.label }}</div><div class="card-value">{{ c.value }}</div></el-card>
        </el-col>
      </el-row>
      <el-card style="margin-top:16px">
        <template #header>我的学习计划</template>
        <el-empty v-if="!plans.length" description="还没有计划，去「学习计划」创建吧" />
        <el-table v-else :data="plans">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="title" label="标题" />
          <el-table-column prop="subject" label="科目" width="120" />
          <el-table-column prop="totalDays" label="天数" width="90" />
          <el-table-column prop="status" label="状态" width="110" />
        </el-table>
      </el-card>
    </div>
  </AppLayout>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import AppLayout from '../components/AppLayout.vue'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const plans = ref([])

const cards = computed(() => [
  { label: '我的计划', value: plans.value.length },
  { label: '任务总数', value: plans.value.reduce((s, p) => s + (p.tasks?.length || 0), 0) },
  { label: '已完成', value: plans.value.reduce((s, p) => s + (p.tasks || []).filter(t => t.status === 'DONE').length, 0) },
  { label: '当前角色', value: auth.user?.role || '-' }
])

onMounted(async () => {
  try {
    const { data } = await http.get('/plans')
    plans.value = data.data || []
  } catch (e) { /* 未登录由拦截器处理 */ }
})
</script>

<style scoped>
.card-label { color: #6b7280; font-size: 13px; }
.card-value { font-size: 26px; font-weight: 700; margin-top: 6px; }
</style>
