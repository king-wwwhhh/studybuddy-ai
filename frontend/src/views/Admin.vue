<template>
  <AppLayout>
    <div class="page">
      <h2 class="page-title">用户管理</h2>
      <p class="page-sub">管理员只负责账号与权限；对话、知识库、学习计划均为用户自有数据，管理员不介入。</p>

      <!-- 只保留与「用户」直接相关的三个指标 -->
      <div class="stat-row">
        <div class="glass stat">
          <div class="stat-label">用户总数</div>
          <div class="stat-value">{{ stats.users ?? '-' }}</div>
        </div>
        <div class="glass stat">
          <div class="stat-label">近 7 日活跃</div>
          <div class="stat-value">{{ stats.activeUsers7d ?? '-' }}</div>
        </div>
        <div class="glass stat">
          <div class="stat-label">今日新增</div>
          <div class="stat-value">{{ todayNewUsers }}</div>
        </div>
      </div>

      <div class="glass panel">
        <div class="panel-head">
          <span>账号列表</span>
          <button class="btn-grad" @click="loadAll">刷新</button>
        </div>
        <el-empty v-if="!users.length" description="暂无用户" />
        <el-table v-else :data="users" style="width: 100%">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="email" label="邮箱" min-width="200" />
          <el-table-column prop="nickname" label="昵称" width="150" />
          <el-table-column label="角色" width="110">
            <template #default="{ row }">
              <span :class="['pill', row.role === 'ADMIN' ? 'pill-admin' : 'pill-user']">{{ row.role }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="110">
            <template #default="{ row }">
              <span :class="['pill', row.status === 'ACTIVE' ? 'pill-ok' : 'pill-off']">
                {{ row.status === 'ACTIVE' ? '正常' : '已禁用' }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="lastLoginAt" label="最近登录" width="180">
            <template #default="{ row }">{{ short(row.lastLoginAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="180">
            <template #default="{ row }">
              <button class="link-btn" @click="toggleStatus(row)">
                {{ row.status === 'ACTIVE' ? '禁用' : '启用' }}
              </button>
              <button v-if="row.role !== 'ADMIN'" class="link-btn warn" @click="promote(row)">设为管理员</button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>
  </AppLayout>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppLayout from '../components/AppLayout.vue'
import http from '../api/http'

const stats = ref({})
const users = ref([])
const trend = ref([])

const todayNewUsers = computed(() => {
  const last = trend.value[trend.value.length - 1]
  return last ? last.newUsers : '-'
})

function short(v) {
  if (!v) return '—'
  return String(v).replace('T', ' ').slice(0, 16)
}

async function loadAll() {
  try {
    const [s, u, t] = await Promise.all([
      http.get('/admin/stats'),
      http.get('/admin/users?limit=100'),
      http.get('/admin/trend?days=7')
    ])
    stats.value = s.data.data || {}
    users.value = u.data.data || []
    trend.value = t.data.data || []
  } catch (e) {
    ElMessage.error(e.response?.status === 403 ? '需要管理员权限' : '加载失败')
  }
}

async function toggleStatus(row) {
  const next = row.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  if (next === 'DISABLED') {
    try {
      await ElMessageBox.confirm('禁用后该用户将无法登录，确定？', '确认', { type: 'warning' })
    } catch (e) { return }
  }
  try {
    await http.post(`/admin/users/${row.id}/status?status=${next}`)
    ElMessage.success(next === 'ACTIVE' ? '已启用' : '已禁用')
    await loadAll()
  } catch (e) { ElMessage.error('操作失败') }
}

async function promote(row) {
  try {
    await ElMessageBox.confirm(`将 ${row.email} 提升为管理员？`, '确认', { type: 'warning' })
  } catch (e) { return }
  try {
    await http.post(`/admin/users/promote?email=${encodeURIComponent(row.email)}`)
    ElMessage.success('已设为管理员')
    await loadAll()
  } catch (e) { ElMessage.error('操作失败') }
}

onMounted(loadAll)
</script>

<style scoped>
.stat-row { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; margin-bottom: 18px; }
.stat { padding: 18px 20px; }
.stat-label { color: var(--muted); font-size: 13px; }
.stat-value {
  font-size: 30px; font-weight: 800; margin-top: 8px;
  background: var(--grad); -webkit-background-clip: text; background-clip: text; color: transparent;
}
.panel { padding: 18px 20px 8px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; font-weight: 700; }
.panel-head .btn-grad { padding: 7px 16px; font-size: 13px; }
.pill { display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; }
.pill-admin { background: rgba(251,191,36,.16); color: var(--warn); border: 1px solid rgba(251,191,36,.35); }
.pill-user { background: rgba(154,166,192,.14); color: var(--muted); border: 1px solid rgba(154,166,192,.3); }
.pill-ok { background: rgba(52,211,153,.14); color: var(--ok); border: 1px solid rgba(52,211,153,.32); }
.pill-off { background: rgba(248,113,113,.14); color: var(--err); border: 1px solid rgba(248,113,113,.32); }
.link-btn {
  background: none; border: none; cursor: pointer; color: var(--primary2);
  font-size: 13px; margin-right: 12px; padding: 0;
}
.link-btn:hover { text-decoration: underline; }
.link-btn.warn { color: var(--warn); }
</style>
