<template>
  <AppLayout>
    <div class="page">
      <h2 class="page-title">学习计划</h2>
      <p class="page-sub">每个账号只能看到自己的计划</p>

      <div class="glass panel">
        <div class="panel-head"><span>我的计划</span>
          <button class="btn-grad" @click="openCreate">+ 新建计划</button>
        </div>
        <el-empty v-if="!plans.length" description="暂无计划" />
        <el-table v-else :data="plans">
          <el-table-column type="expand">
            <template #default="{ row }">
              <div style="padding: 10px 26px">
                <el-empty v-if="!row.tasks?.length" description="暂无任务" :image-size="46" />
                <el-table v-else :data="row.tasks" size="small">
                  <el-table-column prop="title" label="任务" min-width="200" />
                  <el-table-column prop="day" label="第几天" width="90" />
                  <el-table-column prop="status" label="状态" width="100" />
                </el-table>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="title" label="标题" min-width="180" />
          <el-table-column prop="subject" label="科目" width="110" />
          <el-table-column prop="totalDays" label="天数" width="80" />
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <button class="link-btn" @click.stop="remove(row)">删除</button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="dialog" title="新建计划" width="420px">
      <el-form label-width="72px">
        <el-form-item label="标题"><el-input v-model="form.title" /></el-form-item>
        <el-form-item label="科目"><el-input v-model="form.subject" /></el-form-item>
        <el-form-item label="总天数"><el-input-number v-model="form.totalDays" :min="1" :max="365" /></el-form-item>
      </el-form>
      <template #footer>
        <button class="btn-ghost" @click="dialog = false">取消</button>
        <button class="btn-grad" :disabled="saving" @click="create">创建</button>
      </template>
    </el-dialog>
  </AppLayout>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppLayout from '../components/AppLayout.vue'
import http from '../api/http'

const plans = ref([])
const dialog = ref(false)
const saving = ref(false)
const form = reactive({ title: '', subject: '', totalDays: 30 })

async function load() {
  try { const { data } = await http.get('/plans'); plans.value = data.data || [] }
  catch (e) { ElMessage.error('加载计划失败') }
}
function openCreate() { form.title = ''; form.subject = ''; form.totalDays = 30; dialog.value = true }
async function create() {
  if (!form.title.trim()) return ElMessage.warning('请填写标题')
  saving.value = true
  try {
    const { data } = await http.post('/plans', { ...form })
    if (data.success) { ElMessage.success('已创建'); dialog.value = false; await load() }
    else ElMessage.error(data.message || '创建失败')
  } catch (e) { ElMessage.error('创建失败') } finally { saving.value = false }
}
async function remove(row) {
  try {
    await ElMessageBox.confirm('确定删除该计划？', '确认', { type: 'warning' })
    const { data } = await http.delete(`/plans/${row.id}`)
    if (data.success) { ElMessage.success('已删除'); await load() }
    else ElMessage.error(data.message || '删除失败')
  } catch (e) { /* 取消 */ }
}
onMounted(load)
</script>

<style scoped>
.panel { padding: 18px 20px 8px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; font-weight: 700; }
.panel-head .btn-grad { padding: 7px 16px; font-size: 13px; }
.btn-ghost { padding: 8px 16px; border-radius: var(--radius-sm); background: rgba(255,255,255,.07);
  color: var(--text); border: 1px solid var(--border); cursor: pointer; }
.link-btn { background: none; border: none; cursor: pointer; color: var(--err); font-size: 13px; }
</style>
