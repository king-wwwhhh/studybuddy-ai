<template>
  <AppLayout>
    <div class="chat-page">
      <!-- 会话列表 -->
      <aside class="sessions">
        <div class="sess-head">
          <span>会话</span>
          <el-button size="small" text @click="newSession">+ 新会话</el-button>
        </div>
        <div v-if="!sessions.length" class="empty-tip">还没有会话</div>
        <div v-for="s in sessions" :key="s.id" class="sess-item" :class="{ active: s.id === sessionId }" @click="switchSession(s.id)">
          <div class="sess-title">{{ s.title || '未命名会话' }}</div>
          <div class="sess-time">{{ shortTime(s.updatedAt) }}</div>
        </div>
      </aside>

      <!-- 对话区 -->
      <section class="main">
        <div ref="listRef" class="messages">
          <div v-if="!messages.length" class="welcome">
            <h3>开始新的学习对话</h3>
            <p>可以问知识库里的内容，也可以让它帮你出题、做计划。</p>
            <div class="chips">
              <el-tag v-for="q in samples" :key="q" class="chip" @click="input = q">{{ q }}</el-tag>
            </div>
          </div>

          <div v-for="(m, i) in messages" :key="i" class="msg" :class="m.role">
            <div class="bubble" :class="[m.role, { error: m.error }]">
              <div v-if="m.agent" class="agent-tag">{{ agentLabel(m.agent) }}</div>
              <div v-if="m.error" class="content">{{ m.content }}</div>
              <div v-else class="content" v-html="render(m.content)"></div>
              <span v-if="m.streaming" class="cursor">▍</span>
            </div>
          </div>
        </div>

        <div class="composer">
          <el-select v-model="agent" size="small" style="width:130px" placeholder="自动路由">
            <el-option label="自动路由" value="" />
            <el-option label="答疑导师" value="study" />
            <el-option label="出题考官" value="quiz" />
            <el-option label="规划师" value="planner" />
            <el-option label="督学教练" value="coach" />
            <el-option label="通用学伴" value="chat" />
          </el-select>
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            resize="none"
            placeholder="问点什么…（Enter 发送，Shift+Enter 换行）"
            @keydown.enter.exact.prevent="send"
          />
          <el-button type="primary" :loading="streaming" @click="send">{{ streaming ? '回答中' : '发送' }}</el-button>
        </div>
      </section>
    </div>
  </AppLayout>
</template>

<script setup>
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import AppLayout from '../components/AppLayout.vue'
import http from '../api/http'
import { chatStream } from '../api/sse'

const sessions = ref([])
const sessionId = ref(localStorage.getItem('lastSessionId') || newId())
const messages = ref([])
const input = ref('')
const agent = ref('')
const streaming = ref(false)
const listRef = ref(null)
let controller = null

const samples = [
  '帮我梳理一下向量检索的原理',
  '根据我的资料出 5 道题',
  '给我安排一周的复习计划'
]

function newId() {
  return 'web-' + Date.now() + '-' + Math.random().toString(36).slice(2, 8)
}

function newSession() {
  sessionId.value = newId()
  localStorage.setItem('lastSessionId', sessionId.value)
  messages.value = []
}

function shortTime(s) {
  if (!s) return ''
  return String(s).replace('T', ' ').slice(5, 16)
}

function agentLabel(a) {
  return { study: '答疑导师', quiz: '出题考官', planner: '规划师', coach: '督学教练', chat: '通用学伴' }[a] || a
}

/** 极简 Markdown：代码块、加粗、换行（完整解析库会增加体积，这里够用） */
function render(text) {
  if (!text) return ''
  const esc = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  return esc
    .replace(/```([\s\S]*?)```/g, '<pre><code>$1</code></pre>')
    .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
    .replace(/\n/g, '<br/>')
}

async function switchSession(id) {
  sessionId.value = id
  localStorage.setItem('lastSessionId', id)
  messages.value = []
  try {
    const { data } = await http.get(`/memory/sessions/${id}`)
    const rows = data.data || []
    messages.value = rows.map(r => ({ role: r.role === 'user' ? 'user' : 'assistant', content: r.content }))
    await scrollBottom()
  } catch (e) { /* 新会话无历史 */ }
}

async function loadSessions() {
  try {
    const { data } = await http.get('/memory/sessions')
    sessions.value = data.data || []
  } catch (e) { /* 静默 */ }
}

async function scrollBottom() {
  await nextTick()
  if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
}

async function send() {
  const q = input.value.trim()
  if (!q || streaming.value) return
  input.value = ''
  messages.value.push({ role: 'user', content: q })
  messages.value.push({ role: 'assistant', content: '', streaming: true, agent: '', error: false })
  // 关键：必须通过索引拿到响应式代理再修改。
  // 直接保存 push 前的原始对象引用去改，Vue3 不会触发更新（踩过的坑）。
  const idx = messages.value.length - 1
  const patch = (fn) => fn(messages.value[idx])
  streaming.value = true
  await scrollBottom()

  controller = new AbortController()
  await chatStream({
    question: q,
    sessionId: sessionId.value,
    agent: agent.value,
    signal: controller.signal,
    onChunk: (chunk) => {
      patch(m => { m.content += chunk })
      scrollBottom()
    },
    onDone: async (a) => {
      patch(m => { m.streaming = false; if (a) m.agent = a })
      streaming.value = false
      await loadSessions()
    },
    onError: (msg) => {
      patch(m => {
        m.streaming = false
        const partial = (m.content || '').trim()
        m.content = (partial ? partial + ' ' : '') + '⚠️ ' + (msg || '回答失败')
        m.error = true
      })
      streaming.value = false
      ElMessage.error(msg || '回答失败')
      scrollBottom()
    }
  })
}

onMounted(async () => {
  await loadSessions()
  if (localStorage.getItem('lastSessionId')) {
    await switchSession(localStorage.getItem('lastSessionId'))
  }
})
</script>

<style scoped>
.chat-page { display: flex; gap: 16px; height: calc(100vh - 96px); padding: 0 22px 20px; }
.sessions {
  width: 230px; flex: none; display: flex; flex-direction: column;
  background: var(--glass); border: 1px solid var(--border); border-radius: var(--radius);
  backdrop-filter: blur(18px) saturate(140%); padding: 12px; overflow-y: auto;
}
.sess-head { display: flex; justify-content: space-between; align-items: center; font-weight: 700; padding: 2px 4px 10px; }
.sess-head :deep(.el-button) { color: var(--primary2); }
.empty-tip { color: var(--muted); font-size: 13px; text-align: center; padding: 24px 0; }
.sess-item { padding: 9px 10px; border-radius: var(--radius-sm); cursor: pointer; transition: background .16s ease; }
.sess-item:hover { background: rgba(255,255,255,.06); }
.sess-item.active { background: linear-gradient(135deg, rgba(124,92,255,.26), rgba(25,195,255,.16)); }
.sess-title { font-size: 13px; color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sess-time { font-size: 11px; color: var(--muted); margin-top: 3px; }

.main {
  flex: 1; min-width: 0; display: flex; flex-direction: column;
  background: var(--glass); border: 1px solid var(--border); border-radius: var(--radius);
  backdrop-filter: blur(18px) saturate(140%); overflow: hidden;
}
.messages { flex: 1; overflow-y: auto; padding: 22px; }
.welcome { text-align: center; color: var(--muted); margin-top: 14vh; }
.welcome h3 { color: var(--text); font-size: 18px; }
.chips { margin-top: 16px; display: flex; gap: 8px; justify-content: center; flex-wrap: wrap; }
.chip { cursor: pointer; }
.chip :deep(.el-tag) { background: rgba(255,255,255,.07) !important; border-color: var(--border) !important; color: var(--text) !important; }

.msg { display: flex; margin-bottom: 16px; }
.msg.user { justify-content: flex-end; }
.bubble {
  max-width: 78%; padding: 11px 15px; border-radius: 14px; line-height: 1.72; font-size: 14px;
  box-shadow: 0 8px 24px rgba(0,0,0,.25);
}
.bubble.user { background: var(--grad); color: #fff; border-bottom-right-radius: 4px; }
.bubble.assistant { background: rgba(255,255,255,.07); color: var(--text); border: 1px solid var(--border); border-bottom-left-radius: 4px; }
.bubble.error { background: rgba(248,113,113,.12); color: #fecaca; border: 1px solid rgba(248,113,113,.4); }
.agent-tag { font-size: 11px; color: var(--primary2); margin-bottom: 5px; font-weight: 600; }
.cursor { animation: blink 1s infinite; color: var(--primary2); }
@keyframes blink { 50% { opacity: 0; } }

.composer { display: flex; gap: 10px; align-items: flex-end; padding: 14px; border-top: 1px solid var(--border); }
.composer :deep(.el-textarea) { flex: 1; }
:deep(pre) { background: rgba(0,0,0,.45); color: #dbe4f5; padding: 12px; border-radius: 10px; overflow-x: auto; }
</style>
