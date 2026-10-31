<template>
  <AppLayout>
    <div class="page">
      <h2 class="page-title">管理后台</h2>
      <p class="page-sub">仅聚合统计，不展示任何用户的对话内容</p>

      <el-row :gutter="16">
        <el-col :span="6" v-for="c in cards" :key="c.label">
          <el-card shadow="hover">
            <div class="card-label">{{ c.label }}</div>
            <div class="card-value">{{ c.value }}</div>
          </el-card>
        </el-col>
      </el-row>

      <el-row :gutter="16" style="margin-top:16px">
        <el-col :span="16">
          <el-card>
            <template #header>近 7 日趋势</template>
            <div ref="trendRef" style="height:280px"></div>
          </el-card>
        </el-col>
        <el-col :span="8">
          <el-card>
            <template #header>知识库状态分布</template>
            <div ref="pieRef" style="height:280px"></div>
          </el-card>
        </el-col>
      </el-row>

      <el-card style="margin-top:16px">
        <template #header>用户管理</template>
        <el-table :data="users">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="email" label="邮箱" />
          <el-table-column prop="nickname" label="昵称" width="140" />
          <el-table-column prop="role" label="角色" width="100" />
          <el-table-column prop="status" label="状态" width="110" />
          <el-table-column label="操作" width="200">
            <template #default="{ row }">
              <el-button link type="primary" @click="toggleStatus(row)">
                {{ row.status === 'ACTIVE' ? '禁用' : '启用' }}
              </el-button>
              <el-button v-if="row.role !== 'ADMIN'" link type="warning" @click="promote(row)">设为管理员</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
    </div>
  </AppLayout>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import AppLayout from '../components/AppLayout.vue'
import http from '../api/http'

const stats = ref({})
const users = ref([])
const trend = ref([])
const statusDist = ref([])
const trendRef = ref(null)
const pieRef = ref(null)
let trendChart = null
let pieChart = null

const cards = computed(() => [
  { label: '用户数', value: stats.value.users ?? '-' },
  { label: '会话数', value: stats.value.sessions ?? '-' },
  { label: '消息数', value: stats.value.messages ?? '-' },
  { label: '知识分片', value: stats.value.knowledgeChunks ?? '-' }
])

async function loadAll() {
  try {
    const [s, u, t, d] = await Promise.all([
      http.get('/admin/stats'), http.get('/admin/users?limit=50'),
      http.get('/admin/trend?days=7'), http.get('/admin/knowledge-status')
    ])
    stats.value = s.data.data || {}
    users.value = u.data.data || []
    trend.value = t.data.data || []
    statusDist.value = d.data.data || []
    await nextTick()
    renderCharts()
  } catch (e) {
    ElMessage.error(e.response?.status === 403 ? '需要管理员权限' : '加载统计失败')
  }
}

function renderCharts() {
  if (trendRef.value) {
    trendChart = trendChart || echarts.init(trendRef.value)
    trendChart.setOption({
      grid: { left: 40, right: 20, top: 40, bottom: 30 },
      tooltip: { trigger: 'axis' },
      legend: { data: ['消息数', '新增用户', '打卡数'] },
      xAxis: { type: 'category', data: trend.value.map(r => r.date) },
      yAxis: { type: 'value' },
      series: [
        { name: '消息数', type: 'line', smooth: true, data: trend.value.map(r => r.messages) },
        { name: '新增用户', type: 'line', smooth: true, data: trend.value.map(r => r.newUsers) },
        { name: '打卡数', type: 'line', smooth: true, data: trend.value.map(r => r.checkins) }
      ]
    })
  }
  if (pieRef.value) {
    pieChart = pieChart || echarts.init(pieRef.value)
    const data = statusDist.value.map(r => ({ name: r.status, value: r.count }))
    // 无数据时给明确文案，避免灰色空心圆被误认为"图表坏了"
    pieChart.setOption({
      tooltip: { trigger: 'item' },
      title: data.length
        ? { show: false }
        : { text: '暂无知识库文件', left: 'center', top: 'middle',
            textStyle: { color: '#9ca3af', fontSize: 13, fontWeight: 'normal' } },
      series: [{
        type: 'pie', radius: ['40%', '70%'],
        data: data.length ? data : [],
        label: { formatter: '{b}: {c} ({d}%)' }
      }]
    })
  }
}

async function toggleStatus(row) {
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  await http.post(`/admin/users/${row.id}/status?status=${next}`)
  ElMessage.success('已更新')
  await loadAll()
}

async function promote(row) {
  await http.post(`/admin/users/promote?email=${encodeURIComponent(row.email)}`)
  ElMessage.success('已设为管理员')
  await loadAll()
}

onMounted(loadAll)
onUnmounted(() => { trendChart?.dispose(); pieChart?.dispose() })
</script>

<style scoped>
.card-label { color: #6b7280; font-size: 13px; }
.card-value { font-size: 26px; font-weight: 700; margin-top: 6px; }
</style>