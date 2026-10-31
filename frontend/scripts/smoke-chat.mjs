// 冒烟：登录 -> 进入 AI 对话 -> 发送消息 -> 观察 SSE 流式返回
import { chromium } from 'playwright'
const BASE = process.env.BASE || 'http://localhost:9900'
const EMAIL = process.env.EMAIL
const PASSWORD = process.env.PASSWORD
const log = (...a) => console.log(...a)

const browser = await chromium.launch()
const page = await browser.newPage()
const errors = []
page.on('pageerror', e => errors.push('pageerror: ' + e.message))
page.on('console', m => { if (m.type() === 'error') errors.push(m.text()) })

await page.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
const pane = page.locator('.el-tab-pane:visible')
await pane.locator('input').nth(0).fill(EMAIL)
await pane.locator('input').nth(1).fill(PASSWORD)
await page.getByRole('button', { name: '登录' }).first().click()
await page.waitForTimeout(2500)
log('[1] 登录后路由 =', await page.evaluate(() => location.hash))

await page.goto(BASE + '/#/chat', { waitUntil: 'networkidle' })
await page.waitForTimeout(1200)
log('[2] 聊天页标题区 =', await page.locator('body').innerText().then(t => /开始新的学习对话|会话/.test(t)))

// 发送一条消息
const box = page.locator('.composer textarea')
await box.fill('你好，请用一句话介绍你自己')
await page.getByRole('button', { name: '发送' }).click()
await page.waitForTimeout(8000)

const body = await page.locator('body').innerText()
log('[3] 用户消息已上屏 =', body.includes('你好，请用一句话介绍你自己'))
const bubbleCount = await page.locator('.bubble.assistant').count()
log('[4] 助手气泡数量 =', bubbleCount)
if (bubbleCount > 0) {
  const txt = await page.locator('.bubble.assistant').last().innerText()
  log('[5] 助手回复前 120 字 =', JSON.stringify(txt.slice(0, 120)))
}
log('[6] 页面错误数 =', errors.length)
if (errors.length) log('     样例:', errors.slice(0, 3))
await page.screenshot({ path: 'smoke-chat.png', fullPage: true })
await browser.close()
log('DONE')
