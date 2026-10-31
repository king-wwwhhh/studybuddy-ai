<template>
  <div class="login-wrap">
    <div class="glass login-card">
      <h1 class="brand">StudyBuddy</h1>
      <p class="sub">AI 学习助手 · 登录 / 注册</p>

      <el-tabs v-model="tab" stretch>
        <el-tab-pane label="登录" name="login">
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="邮箱或用户名"><el-input v-model="loginForm.email" placeholder="you@qq.com 或 用户名" /></el-form-item>
            <el-form-item label="密码"><el-input v-model="loginForm.password" type="password" show-password placeholder="至少 8 位，含字母和数字" /></el-form-item>
            <button class="btn-grad block" :disabled="loading" @click="doLogin">登 录</button>
            <button class="link-row" @click="tab = 'code'">用邮箱验证码登录</button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="验证码" name="code">
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="邮箱"><el-input v-model="codeForm.email" placeholder="you@qq.com" /></el-form-item>
            <el-form-item label="验证码">
              <div class="row">
                <el-input v-model="codeForm.code" maxlength="6" placeholder="6 位数字" />
                <button class="btn-ghost" :disabled="countdown > 0" @click="sendCode('LOGIN', codeForm.email)">
                  {{ countdown > 0 ? countdown + 's' : '获取验证码' }}
                </button>
              </div>
            </el-form-item>
            <button class="btn-grad block" :disabled="loading" @click="doCodeLogin">登 录</button>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="注册" name="register">
          <el-form label-position="top" @submit.prevent>
            <el-form-item label="邮箱"><el-input v-model="regForm.email" placeholder="you@qq.com" /></el-form-item>
            <el-form-item label="验证码">
              <div class="row">
                <el-input v-model="regForm.code" maxlength="6" placeholder="6 位数字" />
                <button class="btn-ghost" :disabled="countdown > 0" @click="sendCode('REGISTER', regForm.email)">
                  {{ countdown > 0 ? countdown + 's' : '获取验证码' }}
                </button>
              </div>
            </el-form-item>
            <el-form-item label="密码（可选，不填则只能用验证码登录）">
              <el-input v-model="regForm.password" type="password" show-password placeholder="至少 8 位，含字母和数字" />
            </el-form-item>
            <el-form-item label="用户名（唯一，不可重复）">
              <el-input v-model="regForm.username" placeholder="4-20 位字母/数字/下划线/中文" />
              <div class="field-hint">登录时可用邮箱或用户名</div>
            </el-form-item>
            <button class="btn-grad block" :disabled="loading" @click="doRegister">注册并登录</button>
            <p class="hint">注册后可正常使用全部学习功能；管理员权限需由管理员在后台授予</p>
          </el-form>
        </el-tab-pane>
      </el-tabs>
    </div>
  </div>
</template>

<script setup>
import { reactive, ref, onUnmounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const tab = ref('login')
const loading = ref(false)
const countdown = ref(0)
let timer = null

const loginForm = reactive({ email: '', password: '' })
const codeForm = reactive({ email: '', code: '' })
const regForm = reactive({ email: '', code: '', password: '', username: '' })

onUnmounted(() => timer && clearInterval(timer))

function startCountdown(sec = 60) {
  countdown.value = sec
  timer && clearInterval(timer)
  timer = setInterval(() => { countdown.value -= 1; if (countdown.value <= 0) clearInterval(timer) }, 1000)
}

async function sendCode(purpose, email) {
  if (!email) return ElMessage.warning('请先填写邮箱')
  loading.value = true
  try {
    const { data } = await http.post('/auth/send-code', { email, purpose })
    if (data.success) { ElMessage.success(data.message || '验证码已发送'); startCountdown() }
    else ElMessage.error(data.message || '发送失败')
  } catch (e) { ElMessage.error(e.response?.data?.message || '发送失败') }
  finally { loading.value = false }
}

function afterLogin(data) {
  auth.save(data)
  ElMessage.success('登录成功')
  // 管理员进 Vue 管理后台；普通用户进旧版单页（功能完整、含旧动效）
  if (data.role === 'ADMIN') {
    router.replace('/admin')
  } else {
    window.location.href = '/legacy/'
  }
}

async function doLogin() {
  loading.value = true
  try {
    const { data } = await http.post('/auth/login', { email: loginForm.email, password: loginForm.password })
    data.success ? afterLogin(data.data) : ElMessage.error(data.message)
  } catch (e) { ElMessage.error(e.response?.data?.message || '登录失败') } finally { loading.value = false }
}

async function doCodeLogin() {
  loading.value = true
  try {
    const { data } = await http.post('/auth/login-code', { email: codeForm.email, code: codeForm.code })
    data.success ? afterLogin(data.data) : ElMessage.error(data.message)
  } catch (e) { ElMessage.error(e.response?.data?.message || '登录失败') } finally { loading.value = false }
}

async function doRegister() {
  loading.value = true
  try {
    if (!regForm.username || regForm.username.trim().length < 4) {
      return ElMessage.warning('用户名至少 4 位')
    }
    const { data } = await http.post('/auth/register', { ...regForm, nickname: regForm.username })
    data.success ? afterLogin(data.data) : ElMessage.error(data.message)
  } catch (e) { ElMessage.error(e.response?.data?.message || '注册失败') } finally { loading.value = false }
}
</script>

<style scoped>
.login-wrap { min-height: 100vh; display: flex; align-items: center; justify-content: center; padding: 24px; }
.login-card { width: 420px; padding: 34px 30px 22px; }
.brand { margin: 0; font-size: 27px; text-align: center; font-weight: 800; letter-spacing: .5px;
  background: var(--grad); -webkit-background-clip: text; background-clip: text; color: transparent; }
.sub { margin: 8px 0 24px; text-align: center; color: var(--muted); font-size: 13px; }
.block { width: 100%; margin-top: 8px; font-size: 15px; }
.row { display: flex; gap: 10px; width: 100%; }
.btn-ghost {
  flex: none; padding: 0 16px; border-radius: var(--radius-sm);
  background: rgba(255,255,255,.07); color: var(--text);
  border: 1px solid var(--border); cursor: pointer; font-size: 13px; white-space: nowrap;
}
.btn-ghost:disabled { opacity: .5; cursor: not-allowed; }
.link-row { display: block; width: 100%; margin-top: 14px; background: none; border: none;
  color: var(--primary2); cursor: pointer; font-size: 13px; }
.hint { color: var(--muted); font-size: 12px; margin: 12px 0 0; text-align: center; }
.field-hint { font-size: 12px; color: var(--muted); margin-top: 4px; }
</style>
