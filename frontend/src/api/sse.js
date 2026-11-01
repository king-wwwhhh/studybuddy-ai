/** Fetch-based SSE client with exactly one terminal callback and guaranteed reader cleanup. */
export async function chatStream({ question, sessionId, agent, model, onChunk, onDone, onError, signal }) {
  const token = localStorage.getItem('token')
  let reader
  let finished = false
  const fail = (message) => {
    if (finished) return
    finished = true
    onError?.(message)
  }
  try {
    const res = await fetch('/api/chat_stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify({ id: sessionId, question, Agent: agent || null, model: model || null }),
      signal
    })
    if (!res.ok) {
      if (res.status === 401) {
        localStorage.removeItem('token')
        location.hash = '#/login'
        fail('登录已过期，请重新登录')
      } else {
        fail(`服务返回 ${res.status}`)
      }
      return
    }
    if (!res.body) { fail('服务器没有返回响应流，请重试'); return }
    reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buffer = ''
    while (!finished) {
      const { value, done } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      buffer = buffer.replace(/\r\n/g, '\n')
      let sep
      while (!finished && (sep = buffer.indexOf('\n\n')) >= 0) {
        const frame = buffer.slice(0, sep)
        buffer = buffer.slice(sep + 2)
        const payload = frame.split('\n')
          .filter(line => line.startsWith('data:'))
          .map(line => line.slice(5).trimStart()).join('\n').trim()
        if (!payload) continue
        if (payload === '[DONE]') {
          finished = true
          await onDone?.('')
          break
        }
        let msg
        try { msg = JSON.parse(payload) } catch { continue }
        if (msg.type === 'content') onChunk?.(msg.data || '')
        else if (msg.type === 'error') fail(msg.data || '未知错误')
        else if (msg.type === 'done') {
          finished = true
          await onDone?.(msg.agent || '')
        }
      }
    }
    if (!finished) fail('连接已中断，回答可能不完整，请重试')
  } catch (e) {
    fail(e.name === 'AbortError' ? '已停止生成' : (e.message || '连接中断，请重试'))
  } finally {
    if (reader) {
      try { await reader.cancel() } catch { /* A broken transport may reject cancellation. */ }
      reader.releaseLock()
    }
  }
}
