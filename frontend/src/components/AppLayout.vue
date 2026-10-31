<template>
  <el-container class="layout">
    <el-aside width="210px" class="aside">
      <div class="logo">StudyBuddy</div>
      <el-menu :default-active="$route.path" router class="menu">
        <el-menu-item index="/dashboard">学习看板</el-menu-item>
        <el-menu-item index="/chat">AI 对话</el-menu-item>
        <el-menu-item index="/plans">学习计划</el-menu-item>
        <el-menu-item index="/knowledge">知识库</el-menu-item>
        <el-menu-item v-if="auth.isAdmin" index="/admin">管理后台</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <span class="who">
          {{ auth.user?.nickname || auth.user?.email }}
          <el-tag v-if="auth.isAdmin" size="small" type="warning" style="margin-left:6px">管理员</el-tag>
        </span>
        <el-button link @click="logout">退出</el-button>
      </el-header>
      <el-main><slot /></el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
async function logout() {
  await auth.logout()
  router.replace('/login')
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside { background: #1f2430; color: #fff; }
.logo { height: 56px; line-height: 56px; text-align: center; font-weight: 700; letter-spacing: 1px; color: #fff; }
.menu { border-right: none; background: transparent; }
.menu :deep(.el-menu-item) { color: #c7cbd6; }
.menu :deep(.el-menu-item.is-active) { color: #fff; background: #2b3242; }
.header { display: flex; align-items: center; justify-content: space-between; background: #fff; border-bottom: 1px solid #eef0f5; }
.who { font-size: 14px; color: #374151; }
</style>