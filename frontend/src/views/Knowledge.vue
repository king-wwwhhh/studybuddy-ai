<template>
  <AppLayout>
    <div class="page">
      <h2 class="page-title">知识库</h2>
      <p class="page-sub">上传资料后自动解析、切片、向量化，问答时按章节检索</p>

      <div class="glass panel">
        <div class="panel-head">
          <span>我的资料</span>
          <label class="btn-grad upload">
            上传文件
            <input type="file" accept=".txt,.md,.pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx" @change="onPick" hidden />
          </label>
        </div>
        <el-empty v-if="!files.length" description="暂无资料" />
        <el-table v-else :data="files">
          <el-table-column prop="fileName" label="文件名" min-width="220" />
          <el-table-column prop="chunks" label="分片数" width="100" />
          <el-table-column label="状态" width="120">
            <template #default="{ row }">
              <span :class="['pill', row.status === 'READY' ? 'ok' : row.status === 'PARTIAL' ? 'warn' : 'off']">
                {{ row.status }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <button class="link-btn" @click="remove(row)">删除</button>
            </template>
          </el-table-column>
        </el-table>
        <p v-if="uploading" class="uploading">上传中，正在解析与向量化…</p>
      </div>
    </div>
  </AppLayout>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import AppLayout from '../components/AppLayout.vue'
import http from '../api/http'

const files = ref([])
const uploading = ref(false)

async function load() {
  try { const { data } = await http.get('/knowledge/list'); files.value = data.data || [] }
  catch (e) { ElMessage.error('加载知识库失败') }
}

async function onPick(ev) {
  const file = ev.target.files?.[0]
  ev.target.value = ''
  if (!file) return
  uploading.value = true
  const fd = new FormData()
  fd.append('file', file)
  try {
    const { data } = await http.post('/knowledge/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    if (data.success) {
      const st = data.data?.status
      ElMessage.success(st === 'PARTIAL' ? '已上传（文本为尽力抽取）' : '上传成功')
      await load()
    } else ElMessage.error(data.message || '上传失败')
  } catch (e) { ElMessage.error(e.response?.data?.message || '上传失败') }
  finally { uploading.value = false }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm('确定删除该资料及其向量？', '确认', { type: 'warning' })
    const { data } = await http.delete(`/knowledge/${row.id}`)
    if (data.success) { ElMessage.success('已删除'); await load() }
    else ElMessage.error(data.message || '删除失败')
  } catch (e) { /* 取消 */ }
}

onMounted(load)
</script>

<style scoped>
.panel { padding: 18px 20px 16px; }
.panel-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; font-weight: 700; }
.upload { padding: 7px 16px; font-size: 13px; display: inline-block; }
.pill { display: inline-block; padding: 2px 10px; border-radius: 999px; font-size: 12px; font-weight: 600; }
.pill.ok { background: rgba(52,211,153,.14); color: var(--ok); border: 1px solid rgba(52,211,153,.32); }
.pill.warn { background: rgba(251,191,36,.16); color: var(--warn); border: 1px solid rgba(251,191,36,.35); }
.pill.off { background: rgba(248,113,113,.14); color: var(--err); border: 1px solid rgba(248,113,113,.32); }
.link-btn { background: none; border: none; cursor: pointer; color: var(--err); font-size: 13px; }
.uploading { color: var(--muted); font-size: 13px; margin: 8px 0 0; }
</style>
