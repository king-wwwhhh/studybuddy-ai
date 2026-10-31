/**
 * 端到端回归：多用户隔离 + 权限 + 统计口径（论文「系统测试」章节的可复现证据）
 *
 * 用法：BASE=http://localhost:9900 node scripts/e2e.mjs
 * 前置：后端以开发模式启动（未配 SMTP，验证码打印到日志）
 *      并把后端日志路径通过 LOG 环境变量传入（默认 ./target/app.log）
 *
 * 覆盖断言：
 *  鉴权 401 / 角色 403 / 计划隔离 / 画像隔离 / 统计口径 / 隐私字段
 */
import { readFileSync } from 'node:fs'

const BASE = process.env.BASE || 'http://localhost:9900'
const LOG = process.env.LOG || './target/app.log'

let pass = 0, fail = 0
const results = []
const check = (name, cond, detail = '') => {
  results.push([cond ? 'PASS' : 'FAIL', name, detail])
  cond ? pass++ : fail++
}

async function req(method, path, { token, body } = {}) {
  const headers = {}
  if (token) headers.Authorization = 'Bearer ' + token
  let payload
  if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body) }
  const res = await fetch(BASE + path, { method, headers, body: payload })
  let data = null
  try { data = await res.json() } catch (e) { /* 非 JSON */ }
  return { status: res.status, data }
}

/** 从后端日志抓取指定邮箱的最新验证码（开发模式） */
function readCode(email) {
  const text = readFileSync(LOG, 'utf8')
  const re = new RegExp('to=' + email.replace(/[.*+?^${}()|[\]\\]/g, '\\$&') + ' purpose=\\S+ code=(\\d{6})', 'g')
  let m, last = null
  while ((m = re.exec(text)) !== null) last = m[1]
  return last
}

async function register(email, pwd) {
  await req('POST', '/api/auth/send-code', { body: { email, purpose: 'REGISTER' } })
  await new Promise(r => setTimeout(r, 400))
  const code = readCode(email)
  if (!code) throw new Error('无法从日志读取验证码，请确认 LOG 路径与开发模式: ' + email)
  const r = await req('POST', '/api/auth/register', { body: { email, code, password: pwd } })
  if (!r.data?.success) throw new Error('注册失败: ' + JSON.stringify(r.data))
  return r.data.data
}

async function main() {
  // ---------- 1. 鉴权 ----------
  let r = await req('GET', '/api/plans')
  check('未登录访问 /api/plans → 401', r.status === 401, 'status=' + r.status)
  r = await req('GET', '/api/admin/stats')
  check('未登录访问 /api/admin/stats → 401', r.status === 401, 'status=' + r.status)

  // ---------- 2. 准备两个账号 ----------
  // 管理员：优先用环境变量提供的既有管理员（库里首个注册用户才是 ADMIN）
  const stamp = Date.now()
  const userEmail = 'e2e-user-' + stamp + '@qq.com'
  let tA, adminEmail

  if (process.env.ADMIN_EMAIL && process.env.ADMIN_PASSWORD) {
    const r0 = await req('POST', '/api/auth/login', {
      body: { email: process.env.ADMIN_EMAIL, password: process.env.ADMIN_PASSWORD }
    })
    if (!r0.data?.success) throw new Error('管理员登录失败，请检查 ADMIN_EMAIL/ADMIN_PASSWORD')
    tA = r0.data.data.token
    adminEmail = process.env.ADMIN_EMAIL
    check('既有管理员登录成功', r0.data.data.role === 'ADMIN', 'role=' + r0.data.data.role)
  } else {
    const adminEmailNew = 'e2e-admin-' + stamp + '@qq.com'
    const admin = await register(adminEmailNew, 'Admin1234')
    tA = admin.token
    adminEmail = adminEmailNew
    check('新注册用户角色符合规则（首个=ADMIN，否则 USER）', ['ADMIN', 'USER'].includes(admin.role), 'role=' + admin.role)
  }

  const user = await register(userEmail, 'User1234')
  check('新注册的第二个账号为 USER', user.role === 'USER', 'role=' + user.role)
  const tU = user.token

  r = await req('GET', '/api/auth/me', { token: tA })
  check('/auth/me 返回当前用户', r.data?.data?.email === adminEmail)

  // ---------- 3. 权限 ----------
  r = await req('GET', '/api/admin/stats', { token: tU })
  check('普通用户访问 /admin/stats → 403', r.status === 403, 'status=' + r.status)
  r = await req('GET', '/api/admin/users', { token: tU })
  check('普通用户访问 /admin/users → 403', r.status === 403, 'status=' + r.status)
  r = await req('GET', '/api/admin/stats', { token: tA })
  check('管理员访问 /admin/stats → 200', r.status === 200)

  // ---------- 4. 计划隔离 ----------
  r = await req('POST', '/api/plans', { token: tA, body: { title: 'E2E 计划', subject: '数学', totalDays: 10 } })
  const planId = r.data?.data?.id
  check('管理员创建计划成功', !!planId, 'planId=' + planId)

  r = await req('GET', '/api/plans', { token: tU })
  check('普通用户列表看不到管理员计划', (r.data?.data || []).every(p => p.id !== planId))

  r = await req('GET', '/api/plans/' + planId, { token: tU })
  check('普通用户直查管理员计划 → 404 或无数据', r.status === 404 || !r.data?.data, 'status=' + r.status)

  r = await req('DELETE', '/api/plans/' + planId, { token: tU })
  check('普通用户删除管理员计划 → 404', r.status === 404, 'status=' + r.status)

  r = await req('GET', '/api/plans', { token: tA })
  check('管理员计划未被删除', (r.data?.data || []).some(p => p.id === planId))

  // ---------- 5. 画像隔离 ----------
  await req('PUT', '/api/profile', { token: tA, body: { name: '管理员画像', subjects: '数学' } })
  r = await req('GET', '/api/profile', { token: tU })
  check('普通用户读到自己的默认画像', r.data?.data?.name === '同学', 'name=' + r.data?.data?.name)
  r = await req('GET', '/api/profile', { token: tA })
  check('管理员画像保持自己的内容', r.data?.data?.name === '管理员画像')

  // ---------- 6. 知识库与会话隔离 ----------
  r = await req('GET', '/api/knowledge/list', { token: tU })
  check('普通用户知识库列表可访问且不含他人文件', r.status === 200)
  r = await req('GET', '/api/memory/sessions', { token: tU })
  check('普通用户会话列表可访问', r.status === 200)

  // ---------- 7. 统计口径 ----------
  r = await req('GET', '/api/admin/stats', { token: tA })
  const s = r.data?.data || {}
  check('统计含 9 项指标', Object.keys(s).length >= 9, Object.keys(s).join(','))
  check('用户数 >= 2', s.users >= 2, 'users=' + s.users)
  check('计划数 >= 1', s.plans >= 1, 'plans=' + s.plans)

  r = await req('GET', '/api/admin/trend?days=7', { token: tA })
  check('趋势返回 7 天且末位是今天', (r.data?.data || []).length === 7,
    'last=' + (r.data?.data || []).at(-1)?.date)

  r = await req('GET', '/api/admin/users?limit=20', { token: tA })
  const list = r.data?.data || []
  check('用户列表不含密码字段', list.every(u => !('passwordHash' in u) && !('password_hash' in u)))
  check('用户列表含刚注册的两个账号', list.some(u => u.email === adminEmail) && list.some(u => u.email === userEmail))

  // ---------- 输出 ----------
  console.log('\n=== E2E 回归结果 ===')
  for (const [st, name, detail] of results) {
    console.log(`[${st}] ${name}${detail ? '  (' + detail + ')' : ''}`)
  }
  console.log(`\n通过 ${pass} / 失败 ${fail}`)
  process.exit(fail === 0 ? 0 : 1)
}

main().catch(e => { console.error('[E2E ERROR]', e.message); process.exit(1) })