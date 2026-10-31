// 冒烟：管理员大屏真实渲染（ECharts 是否有 canvas、卡片是否有真实数字）
import { chromium } from 'playwright'
const BASE = process.env.BASE || 'http://localhost:9900'
const log = (...a) => console.log(...a)

const b = await chromium.launch()
const p = await b.newPage({ viewport: { width: 1440, height: 900 } })
const errs = []
p.on('pageerror', e => errs.push('pageerror: ' + e.message))
p.on('console', m => { if (m.type() === 'error') errs.push(m.text()) })

await p.goto(BASE + '/#/login', { waitUntil: 'networkidle' })
const pane = p.locator('.el-tab-pane:visible')
await pane.locator('input').nth(0).fill(process.env.ADMIN_EMAIL)
await pane.locator('input').nth(1).fill(process.env.ADMIN_PASSWORD)
await p.getByRole('button', { name: '登录' }).first().click()
await p.waitForTimeout(2500)

await p.goto(BASE + '/#/admin', { waitUntil: 'networkidle' })
await p.waitForTimeout(3000)

const cards = await p.locator('.card-value').allInnerTexts()
log('[1] 指标卡数值 =', JSON.stringify(cards))
const canvases = await p.locator('canvas').count()
log('[2] ECharts canvas 数量 =', canvases)
const rows = await p.locator('.el-table__row').count()
log('[3] 用户表行数 =', rows)
const bodyText = await p.locator('body').innerText()
log('[4] 页面含关键区块 =', /近 7 日趋势/.test(bodyText) && /知识库状态分布/.test(bodyText) && /用户管理/.test(bodyText))
log('[5] 错误数 =', errs.length)
if (errs.length) log('     ', errs.slice(0, 2))
await p.screenshot({ path: 'smoke-admin.png', fullPage: true })
await b.close()
log('DONE')
