import { chromium } from 'playwright'
const BASE = 'http://localhost:9900'
const b = await chromium.launch()
const p = await b.newPage({ viewport: { width: 1440, height: 900 } })
const errs = []
p.on('pageerror', e => errs.push(e.message))

const login = await (await fetch(BASE + '/api/auth/login', {
  method: 'POST', headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ email: process.env.EMAIL, password: process.env.PWD })
})).json()

// 先在同一 origin 写 token，再进旧单页（不 reload，避免竞态）
await p.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
await p.evaluate(d => {
  localStorage.setItem('token', d.token)
  localStorage.setItem('user', JSON.stringify(d.user))
}, { token: login.data.token, user: { userId: login.data.userId, email: login.data.email, role: login.data.role } })

await p.goto(BASE + '/legacy/', { waitUntil: 'networkidle' })
await p.waitForTimeout(2000)
console.log('URL =', p.url(), '| 输入框 =', await p.locator('#messageInput').count())

// 手动切到「答疑」Agent
const chips = await p.locator('#agentSwitch .chip').allInnerTexts()
console.log('Agent 按钮 =', JSON.stringify(chips))
const yida = p.locator('#agentSwitch .chip').filter({ hasText: '答疑' }).first()
if (await yida.count()) { await yida.click(); await p.waitForTimeout(300) }

await p.locator('#messageInput').fill('用一句话说明向量检索是什么')
await p.locator('#sendButton').click()
console.log('已发送，等待流式…')
await p.waitForTimeout(28000)

const n = await p.locator('.msg-body').count()
console.log('气泡数 =', n)
const last = await p.locator('.msg-body').last().innerText().catch(() => '')
console.log('最后回复 =', JSON.stringify(last.slice(0, 220)))
const metas = await p.locator('.msg-meta').allInnerTexts().catch(() => [])
console.log('元信息 =', JSON.stringify(metas.slice(-2)))
console.log('错误数 =', errs.length, errs.slice(0, 2))
await p.screenshot({ path: 'legacy-answer.png' })
await b.close()
