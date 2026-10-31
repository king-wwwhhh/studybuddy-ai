<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">
        <span class="logo-dot"></span>
        <span class="brand-name">StudyBuddy</span>
      </div>

      <nav class="nav">
        <!-- 普通用户菜单：只有管理员才看得到管理入口 -->
        <template v-if="!auth.isAdmin">
          <router-link class="nav-item" to="/dashboard">学习看板</router-link>
          <router-link class="nav-item" to="/chat">AI 对话</router-link>
          <router-link class="nav-item" to="/plans">学习计划</router-link>
          <router-link class="nav-item" to="/knowledge">知识库</router-link>
        </template>

        <!-- 管理员菜单：纯管理，不提供对话/知识库/计划入口 -->
        <template v-else>
          <router-link class="nav-item" to="/admin">用户管理</router-link>
        </template>
      </nav>

      <div class="side-foot">
        <div class="who">
          <div class="who-name">{{ auth.user?.nickname || auth.user?.email }}</div>
          <span v-if="auth.isAdmin" class="role-badge">管理员</span>
        </div>
        <button class="logout" @click="logout">退出</button>
      </div>
    </aside>

    <main class="main-area">
      <slot />
    </main>
  </div>
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
.layout { display: flex; min-height: 100vh; }

.sidebar {
  width: 224px;
  flex: none;
  display: flex;
  flex-direction: column;
  background: rgba(255, 255, 255, 0.04);
  border-right: 1px solid var(--border);
  backdrop-filter: blur(20px) saturate(140%);
  padding: 18px 14px;
}

.brand { display: flex; align-items: center; gap: 10px; padding: 4px 8px 20px; }
.logo-dot {
  width: 26px; height: 26px; border-radius: 9px;
  background: var(--grad);
  box-shadow: 0 6px 18px rgba(124, 92, 255, .5);
}
.brand-name {
  font-weight: 800; font-size: 16px; letter-spacing: .4px;
  background: var(--grad); -webkit-background-clip: text; background-clip: text; color: transparent;
}

.nav { display: flex; flex-direction: column; gap: 4px; flex: 1; }
.nav-item {
  display: block;
  padding: 11px 14px;
  border-radius: var(--radius-sm);
  color: var(--muted);
  text-decoration: none;
  font-size: 14px;
  font-weight: 600;
  transition: background .18s ease, color .18s ease, transform .18s ease;
}
.nav-item:hover { background: rgba(255, 255, 255, .07); color: var(--text); transform: translateX(2px); }
.nav-item.router-link-active {
  color: #fff;
  background: linear-gradient(135deg, rgba(124,92,255,.28), rgba(25,195,255,.18));
  box-shadow: inset 0 0 0 1px rgba(124, 92, 255, .35);
}

.side-foot { border-top: 1px solid var(--border); padding-top: 12px; }
.who { padding: 0 8px 10px; }
.who-name { font-size: 13px; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.role-badge {
  display: inline-block; margin-top: 6px;
  font-size: 11px; padding: 2px 9px; border-radius: 999px;
  background: rgba(251, 191, 36, .16); color: var(--warn);
  border: 1px solid rgba(251, 191, 36, .35);
}
.logout {
  width: 100%; padding: 9px; border-radius: var(--radius-sm);
  background: rgba(255, 255, 255, .06); color: var(--muted);
  border: 1px solid var(--border); cursor: pointer; font-size: 13px;
  transition: all .18s ease;
}
.logout:hover { color: var(--err); border-color: rgba(248, 113, 113, .45); background: rgba(248, 113, 113, .08); }

.main-area { flex: 1; min-width: 0; overflow-x: hidden; }
</style>
