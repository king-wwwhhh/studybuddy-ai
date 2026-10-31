<template>
  <div class="login-wrap">
    <div class="login-card">
      <h1 class="brand">StudyBuddy</h1>
      <p class="sub">AI 学习助手 · 登录 / 注册</p>

      <el-tabs v-model="tab" stretch>
        <!-- ============ 登录 ============ -->
        <el-tab-pane label="登录" name="login">
          <el-form :model="loginForm" label-position="top" @submit.prevent>
            <el-form-item label="邮箱">
              <el-input v-model="loginForm.email" placeholder="you@qq.com" autocomplete="username" />
            </el-form-item>
            <el-form-item label="密码">
              <el-input v-model="loginForm.password" type="password" show-password placeholder="至少 8 位，含字母和数字" />
            </el-form-item>
            <el-button type="primary" class="block" :loading="loading" @click="doLogin">登录</el-button>
            <el-button link class="tip" @click="tab = 'code'">用邮箱验证码登录</el-button>
          </el-form>
        </el-tab-pane>

        <!-- ============ 验证码登录 ============ -->
        <el-tab-pane label="验证码" name="code">
          <el-form :model="codeForm" label-position="top" @submit.prevent>
            <el-form-item label="邮箱">
              <el-input v-model="codeForm.email" placeholder="you@qq.com" />
            </el-form-item>
            <el-form-item label="验证码">
              <div class="row">
                <el-input v-model="codeForm.code" maxlength="6" placeholder="6 位数字" />
                <el-button :disabled="countdown > 0" @click="sendCode('LOGIN', codeForm.email)">
                  {{ countdown > 0 ? countdown + 's' : '获取验证码' }}
                </el-button>
              </div>
            </el-form-item>
            <el-button type="primary" class="block" :loading="loading" @click="doCodeLogin">登录</el-button>
          </el-form>
        </el-tab-pane>

        <!-- ============ 注册 ============ -->
        <el-tab-pane label="注册" name="register">
          <el-form :model="regForm" label-position="top" @submit.prevent>
            <el-form-item label="邮箱">
              <el-input v-model="regForm.email" placeholder="you@qq.com" />
            </el-form-item>
            <el-form-item label="验证码">
              <div class="row">
                <el-input v-model="regForm.code" maxlength="6" placeholder="6 位数字" />
                <el-button :disabled="countdown > 0" @click="sendCode('REGISTER', regForm.email)">
                  {{ countdown > 0 ? countdown + 's' : '获取验证码' }}
                </el-button>
              </div>
            </el-form-item>
            <el-form-item label="密码（可选，不填则只能用验证码登录）">
              <el-input v-model="regForm.password" type="password" show-password placeholder="至少 8 位，含字母和数字" />
            </el-form-item>
            <el-form-item label="昵称（可选）">
              <el-input v-model="regForm.nickname" placeholder="怎么称呼你" />
            </el-form-item>
            <el-button type="primary" class="block" :loading="loading" @click="doRegister">注册并登录</el-button>
            <p class="hint">首个注册的账号自动成为管理员</p>
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
const regForm = reactive({ email: '', code: '', password: '', nickname: '' })

onUnmounted(() => timer && clearInterval(timer))

function startCountdown(sec = 60) {
  countdown.value = sec
  timer && clearInterval(timer)
  timer = setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) clearInterval(timer)
  }, 1000)
}

async function sendCode(purpose, email) {
  if (!email) return ElMessage.warning('请先填写邮箱')
  loading.value = true
  try {
    const { data } = await http.post('/auth/send-code', { email, purpose })
    if (data.success) {
      ElMessage.success(data.message || '验证码已发送')
      startCountdown()
    } else {
      ElMessage.error(data.message || '发送失败')
    }
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '发送失败，请稍后再试')
  } finally {
    loading.value = false
  }
}

function afterLogin(data) {
  auth.save(data)
  ElMessage.success('登录成功')
  const redirect = route.query.redirect || '/dashboard'
  router.replace(redirect)
}

async function doLogin() {
  loading.value = true
  try {
    const { data } = await http.post('/auth/login', { email: loginForm.email, password: loginForm.password })
    if (data.success) afterLogin(data.data)
    else ElMessage.error(data.message)
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '登录失败')
  } finally {
    loading.value = false
  }
}

async function doCodeLogin() {
  loading.value = true
  try {
    const { data } = await http.post('/auth/login-code', { email: codeForm.email, code: codeForm.code })
    if (data.success) afterLogin(data.data)
    else ElMessage.error(data.message)
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '登录失败')
  } finally {
    loading.value = false
  }
}

async function doRegister() {
  loading.value = true
  try {
    const { data } = await http.post('/auth/register', regForm)
    if (data.success) afterLogin(data.data)
    else ElMessage.error(data.message)
  } catch (e) {
    ElMessage.error(e.response?.data?.message || '注册失败')
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-wrap {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-card {
  width: 400px;
  padding: 32px 28px 20px;
  background: #fff;
  border-radius: 16px;
  box-shadow: 0 20px 50px rgba(0, 0, 0, .18);
}
.brand { margin: 0; font-size: 26px; text-align: center; letter-spacing: .5px; }
.sub { margin: 6px 0 22px; text-align: center; color: #6b7280; font-size: 13px; }
.block { width: 100%; margin-top: 6px; }
.row { display: flex; gap: 8px; width: 100%; }
.tip { margin-top: 10px; width: 100%; }
.hint { color: #9ca3af; font-size: 12px; margin: 10px 0 0; text-align: center; }
</style>
