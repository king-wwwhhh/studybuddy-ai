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

      <div class="glass panel" style="margin-bottom:16px">
        <div class="panel-head"><span>系统设置</span></div>
        <div class="setting-row">
          <div>
            <div class="setting-name">开放新用户注册</div>
            <div class="setting-desc">关闭后任何人都无法通过注册页创建账号（已有账号不受影响）</div>
          </div>
          <el-switch v-model="registrationOpen" :loading="savingSetting" @change="toggleRegistration" />
        </div>
      </div>

      <div class="glass panel">
        <div class="panel-head">
          <span>账号列表</span>
          <div>
            <button class="btn-grad" @click="openCreate">+ 新增用户</button>
            <button class="link-btn" style="margin-left:14px" @click="loadAll">刷新</button>
          </div>
        </div>
        <el-empty v-if="!users.length" description="暂无用户" />
        <el-table v-else :data="users" style="width: 100%">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="email" label="邮箱" min-width="200" />
          <el-table-column label="用户名" width="150">
            <template #default="{ row }">{{ row.username || row.nickname || '—' }}</template>
          </el-table-column>
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
          <el-table-column label="操作" width="290">
            <template #default="{ row }">
              <button class="link-btn" @click="toggleStatus(row)">
                {{ row.status === 'ACTIVE' ? '禁用' : '启用' }}
              </button>
              <button class="link-btn" @click="openEdit(row)">编辑</button>
              <button class="link-btn" @click="openReset(row)">重置密码</button>
              <button v-if="row.role !== 'ADMIN'" class="link-btn warn" @click="promote(row)">设为管理员</button>
              <button class="link-btn danger" @click="removeUser(row)">删除</button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <!-- 新增/编辑用户 -->
      <el-dialog v-model="dialog" :title="editing ? '编辑用户' : '新增用户'" width="440px">
        <el-form label-width="80px">
          <el-form-item label="邮箱">
            <el-input v-model="form.email" :disabled="editing" placeholder="user@qq.com" />
          </el-form-item>
          <el-form-item v-if="!editing" label="密码">
            <el-input v-model="form.password" type="password" show-password placeholder="至少 8 位" />
          </el-form-item>
          <el-form-item label="用户名">
            <el-input v-model="form.username" placeholder="4-20 位字母/数字/下划线/中文" />
            <div class="field-hint">唯一不可重复；不能用 admin 等保留名或违规词</div>
          </el-form-item>
          <el-form-item label="角色">
            <el-select v-model="form.role" style="width:100%">
              <el-option label="普通用户" value="USER" />
              <el-option label="管理员" value="ADMIN" />
            </el-select>
          </el-form-item>
        </el-form>
        <template #footer>
          <button class="link-btn" @click="dialog = false">取消</button>
          <button class="btn-grad" style="margin-left:12px" :disabled="saving" @click="save">保存</button>
        </template>
      </el-dialog>

      <!-- 重置密码 -->
      <el-dialog v-model="resetDialog" title="重置密码" width="400px">
        <el-form label-width="80px">
          <el-form-item label="新密码">
            <el-input v-model="resetPwd" type="password" show-password placeholder="至少 8 位" />
          </el-form-item>
        </el-form>
        <template #footer>
          <button class="link-btn" @click="resetDialog = false">取消</button>
          <button class="btn-grad" style="margin-left:12px" :disabled="saving" @click="doReset">确定</button>
        </template>
      </el-dialog>
    </div>
  </AppLayout>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
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


// ==================== 系统设置 ====================
const registrationOpen = ref(true)
const savingSetting = ref(false)

async function loadSettings() {
  try {
    const { data } = await http.get('/admin/settings')
    registrationOpen.value = data.data?.registrationOpen !== false
  } catch (e) { /* 忽略 */ }
}

async function toggleRegistration(val) {
  savingSetting.value = true
  try {
    await http.post('/admin/settings', { registrationOpen: val })
    ElMessage.success(val ? '已开放注册' : '已暂停注册')
  } catch (e) {
    ElMessage.error('设置失败')
    registrationOpen.value = !val
  } finally { savingSetting.value = false }
}

// ==================== 用户增删改 ====================
const dialog = ref(false)
const editing = ref(false)
const saving = ref(false)
const form = reactive({ id: null, email: '', password: '', username: '', role: 'USER' })

const resetDialog = ref(false)
const resetPwd = ref('')
const resetTarget = ref(null)

function openCreate() {
  editing.value = false
  Object.assign(form, { id: null, email: '', password: '', username: '', role: 'USER' })
  dialog.value = true
}

function openEdit(row) {
  editing.value = true
  Object.assign(form, { id: row.id, email: row.email, password: '', username: row.username || row.nickname || '', role: row.role })
  dialog.value = true
}

async function save() {
  if (!editing.value && (!form.email.includes('@') || form.password.length < 8)) {
    return ElMessage.warning('请填写有效邮箱，密码至少 8 位')
  }
  if (!form.username || form.username.trim().length < 4) {
    return ElMessage.warning('用户名至少 4 位')
  }
  saving.value = true
  try {
    if (editing.value) {
      const { data } = await http.post('/admin/users/' + form.id, { username: form.username, role: form.role })
      if (!data.success) { ElMessage.error(data.errorMessage || data.message || '保存失败'); return }
      ElMessage.success('已保存')
    } else {
      const { data } = await http.post('/admin/users', form)
      if (!data.success) { ElMessage.error(data.errorMessage || data.message || '创建失败'); return }
      ElMessage.success('已创建')
    }
    dialog.value = false
    await loadAll()
  } catch (e) { ElMessage.error('保存失败') } finally { saving.value = false }
}

function openReset(row) {
  resetTarget.value = row
  resetPwd.value = ''
  resetDialog.value = true
}

async function doReset() {
  if (resetPwd.value.length < 8) return ElMessage.warning('密码至少 8 位')
  saving.value = true
  try {
    const { data } = await http.post('/admin/users/' + resetTarget.value.id + '/password', { password: resetPwd.value })
    if (!data.success) { ElMessage.error(data.errorMessage || data.message || '重置失败'); return }
    ElMessage.success('密码已重置')
    resetDialog.value = false
  } catch (e) { ElMessage.error('重置失败') } finally { saving.value = false }
}

async function removeUser(row) {
  try {
    await ElMessageBox.confirm(
      `删除后该账号及其知识库、对话、计划都会被清除，且无法恢复。确定删除 ${row.email} ？`,
      '删除用户', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' })
  } catch (e) { return }
  try {
    const { data } = await http.delete('/admin/users/' + row.id)
    if (!data.success) { ElMessage.error(data.errorMessage || data.message || '删除失败'); return }
    ElMessage.success('已删除')
    await loadAll()
  } catch (e) { ElMessage.error('删除失败') }
}

onMounted(() => { loadAll(); loadSettings() })
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

.setting-row { display: flex; align-items: center; justify-content: space-between; padding: 6px 2px 16px; gap: 20px; }
.setting-name { font-weight: 600; margin-bottom: 4px; }
.setting-desc { font-size: 12.5px; color: var(--muted); }
.link-btn.danger { color: var(--err); }
.panel-head > div { display: flex; align-items: center; }
.field-hint { font-size: 12px; color: var(--muted); margin-top: 4px; }
</style>
