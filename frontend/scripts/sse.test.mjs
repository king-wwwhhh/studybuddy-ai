import { test, afterEach } from 'node:test'
import assert from 'node:assert/strict'
import { chatStream } from '../src/api/sse.js'

const original = { fetch: globalThis.fetch, localStorage: globalThis.localStorage, location: globalThis.location }
afterEach(() => Object.assign(globalThis, original))

function setup(reads, status = 200) {
  const seen = { chunks: [], done: [], errors: [], cancelled: 0, released: 0, removed: [] }
  const reader = {
    async read() {
      const next = reads.shift()
      if (next instanceof Error) throw next
      return next === undefined ? { done: true } : { value: new TextEncoder().encode(next), done: false }
    },
    async cancel() { seen.cancelled++ },
    releaseLock() { seen.released++ }
  }
  globalThis.localStorage = { getItem: () => 'test-token', removeItem: key => seen.removed.push(key) }
  globalThis.location = { hash: '' }
  globalThis.fetch = async () => ({ ok: status === 200, status, body: { getReader: () => reader } })
  return { seen, run: () => chatStream({ question: 'q', sessionId: 's',
    onChunk: s => seen.chunks.push(s), onDone: s => seen.done.push(s), onError: s => seen.errors.push(s) }) }
}

test('split CRLF frames and duplicate terminal events complete once', async () => {
  const { seen, run } = setup(['data: {"type":"content","data":"中文"}\r',
    '\n\r\ndata: {"type":"done","agent":"study"}\r\n\r\ndata: {"type":"done"}\n\n'])
  await run()
  assert.deepEqual(seen.chunks, ['中文'])
  assert.deepEqual(seen.done, ['study'])
  assert.deepEqual(seen.errors, [])
  assert.equal(seen.cancelled, 1)
  assert.equal(seen.released, 1)
})

test('read failure reports error and releases reader', async () => {
  const { seen, run } = setup(['data: {"type":"content","data":"partial"}\n\n', new Error('socket reset')])
  await run()
  assert.deepEqual(seen.chunks, ['partial'])
  assert.deepEqual(seen.errors, ['socket reset'])
  assert.deepEqual(seen.done, [])
  assert.equal(seen.released, 1)
})

test('unexpected EOF is an error instead of successful completion', async () => {
  const { seen, run } = setup([])
  await run()
  assert.equal(seen.errors.length, 1)
  assert.deepEqual(seen.done, [])
})

test('server error is terminal even if followed by done', async () => {
  const { seen, run } = setup(['data: {"type":"error","data":"denied"}\n\ndata: {"type":"done"}\n\n'])
  await run()
  assert.deepEqual(seen.errors, ['denied'])
  assert.deepEqual(seen.done, [])
})

test('abort during reading terminates cleanly', async () => {
  const { seen, run } = setup([new DOMException('aborted', 'AbortError')])
  await run()
  assert.deepEqual(seen.errors, ['已停止生成'])
  assert.equal(seen.released, 1)
})

test('401 clears token and navigates to login', async () => {
  const { seen, run } = setup([], 401)
  await run()
  assert.deepEqual(seen.removed, ['token'])
  assert.equal(globalThis.location.hash, '#/login')
  assert.equal(seen.errors.length, 1)
})

test('missing body and fetch failure report errors', async () => {
  const { seen, run } = setup([])
  globalThis.fetch = async () => ({ ok: true, body: null })
  await run()
  assert.equal(seen.errors.length, 1)
  globalThis.fetch = async () => { throw new Error('offline') }
  await run()
  assert.equal(seen.errors.length, 2)
})
