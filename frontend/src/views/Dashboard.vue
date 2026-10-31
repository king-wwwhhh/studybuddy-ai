<template>
  <AppLayout>
    <div class="page">
      <h2 class="page-title">学习看板</h2>
      <p class="page-sub">{{ auth.user?.nickname || auth.user?.email }} · 今天也要加油</p>

      <div class="stat-row">
        <div class="glass stat" v-for="c in cards" :key="c.label">
          <div class="stat-label">{{ c.label }}</div>
          <div class="stat-value">{{ c.value }}</div>
        </div>
      </div>

      <div class="glass panel">
        <div class="panel-head"><span>我的学习计划</span>
          <router-link to="/plans" class="link">去管理 →</router-link>
        </div>
        <el-empty v-if="!plans.length" description="还没有计划，去「学习计划」创建吧" />
        <el-table v-else :data="plans">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="title" label="标题" min-width="180" />
          <el-table-column prop="subject" label="科目" width="120" />
          <el-table-column prop="totalDays" label="天数" width="90" />
          <el-table-column prop="status" label="状态" width="110" />
        </el-table>
      </div>
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
  { label: '已完成', value: plans.value.reduce((s, p) => s + (p.tasks || []).filter(t => t.status === 'DONE').length, 0) }
])

onMounted(async () => {
  try {
    const { data } = await http.get('/plans')
    plans.value = data.data || []
  } catch (e) { /* 拦截器处理 */ }
})
</script>

<style scoped>
.stat-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 18px; }
.stat { padding: 18px 20px; }
.stat-label { color: var(--muted); font-size: 13px; }
.stat-value { font-size: 30px; font-weight: 800; margin-top: 8px;
  background: var(--grad); -webkit-background-clip: text; background-clip: text; color: transparent; }
.panel { padding: 18px 20px 8px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; font-weight: 700; }
.link { color: var(--primary2); font-size: 13px; text-decoration: none; }
</style>
