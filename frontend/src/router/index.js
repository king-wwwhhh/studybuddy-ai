import { createRouter, createWebHashHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { public: true } },
  { path: '/', redirect: '/dashboard' },
  { path: '/dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue') },
  { path: '/chat', name: 'chat', component: () => import('../views/Chat.vue') },
  { path: '/plans', name: 'plans', component: () => import('../views/Plans.vue') },
  { path: '/knowledge', name: 'knowledge', component: () => import('../views/Knowledge.vue') },
  { path: '/admin', name: 'admin', component: () => import('../views/Admin.vue'), meta: { admin: true } }
]

const router = createRouter({ history: createWebHashHistory(), routes })

router.beforeEach((to) => {
  const auth = useAuthStore()
  if (to.meta.public) return true
  if (!auth.isLoggedIn) return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.admin && !auth.isAdmin) return { name: 'dashboard' }
  return true
})

export default router