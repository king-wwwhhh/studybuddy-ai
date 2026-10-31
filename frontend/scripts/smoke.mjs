// 冒烟：无头浏览器验证「路由守卫 → 注册页 → 提交错误验证码得到错误提示 → 登录成功进入看板」
import { chromium } from 'playwright'

const BASE = process.env.BASE || 'http://localhost:9900'
const EMAIL = process.env.EMAIL
const CODE = process.env.CODE
const PASSWORD = 'Smoke1234'

const log = (...a) => console.log(...a)
const browser = await chromium.launch()
const page = await browser.newPage()
const consoleErrors = []
page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()) })
page.on('pageerror', e => consoleErrors.push('pageerror: ' + e.message))

await page.goto(BASE, { waitUntil: 'networkidle' })
log('[1] title =', await page.title())
log('[2] 未登录被重定向到 =', await page.evaluate(() => location.hash))

// 只在当前可见的 tab pane 里找输入框
const activePane = page.locator('.el-tab-pane:visible')

// 切到注册
await page.getByRole('tab', { name: '注册' }).click()
await page.waitForTimeout(400)
log('[3] 注册 tab 输入框数量 =', await activePane.locator('input').count())

// 错误验证码：应有错误提示
await activePane.locator('input').nth(0).fill(EMAIL || 'smoke' + Date.now() + '@qq.com')
await activePane.locator('input').nth(1).fill('000000')
await page.getByRole('button', { name: '注册并登录' }).click()
await page.waitForTimeout(1500)
const bodyAfterWrong = await page.locator('body').innerText()
log('[4] 错误验证码有提示 =', /验证码|错误|失败|过期/.test(bodyAfterWrong))

// 正确验证码：注册并跳转看板
if (CODE) {
  await activePane.locator('input').nth(1).fill(CODE)
  const pwd = activePane.locator('input').nth(2)
  if (await pwd.count()) await pwd.fill(PASSWORD)
  await page.getByRole('button', { name: '注册并登录' }).click()
  await page.waitForTimeout(2500)
  log('[5] 注册后路由 =', await page.evaluate(() => location.hash))
  const dashText = await page.locator('body').innerText()
  log('[6] 看板元素可见 =', /学习看板|我的计划/.test(dashText))
  log('[7] 侧边栏 =', await page.locator('.logo').innerText())
  await page.screenshot({ path: 'smoke-dashboard.png', fullPage: true })
} else {
  await page.screenshot({ path: 'smoke-login.png', fullPage: true })
}

log('[8] 前端控制台错误数 =', consoleErrors.length)
if (consoleErrors.length) log('     前 2 条:', consoleErrors.slice(0, 2))
await browser.close()
log('DONE')
