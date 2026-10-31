<template>
  <AppLayout>
    <div class="page">
      <h2 class="page-title">知识库</h2>
      <p class="page-sub">上传资料后自动解析、切片、向量化，问答时按章节检索</p>

      <el-card>
        <template #header>
          <div class="head">
            <span>我的资料</span>
            <div>
              <el-upload :show-file-list="false" :http-request="doUpload" accept=".txt,.md,.pdf,.doc,.docx,.ppt,.pptx,.xls,.xlsx">
                <el-button type="primary" size="small" :loading="uploading">上传文件</el-button>
              </el-upload>
            </div>
          </div>
        </template>
        <el-empty v-if="!files.length" description="暂无资料" />
        <el-table v-else :data="files">
          <el-table-column prop="fileName" label="文件名" />
          <el-table-column prop="chunks" label="分片数" width="100" />
          <el-table-column prop="status" label="状态" width="110">
            <template #default="{ row }">
              <el-tag :type="row.status === 'READY' ? 'success' : row.status === 'PARTIAL' ? 'warning' : 'danger'">
                {{ row.status }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="90">
            <template #default="{ row }">
              <el-button link type="danger" @click="remove(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>
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
  try {
    const { data } = await http.get('/knowledge/list')
    files.value = data.data || []
  } catch (e) { ElMessage.error('加载知识库失败') }
}

async function doUpload(option) {
  uploading.value = true
  const fd = new FormData()
  fd.append('file', option.file)
  try {
    const { data } = await http.post('/knowledge/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    if (data.success) { ElMessage.success('上传成功'); await load() }
    else ElMessage.error(data.message || '上传失败')
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '上传失败')
  } finally { uploading.value = false }
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
.head { display: flex; justify-content: space-between; align-items: center; }
</style>
