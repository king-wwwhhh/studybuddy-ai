/**
 * SSE 流式对话客户端。
 *
 * 不用 EventSource：它无法带 Authorization 头（只能靠 Cookie），
 * 而本项目 token 存在 localStorage。这里用 fetch + ReadableStream 手工解析 SSE 帧，
 * 与后端 Axios 的鉴权方式保持一致。
 *
 * 后端事件格式（ChatController.SseMessage）：
 *   data: {"type":"content","data":"片段"}     增量文本
 *   data: {"type":"error","data":"错误信息"}   错误
 *   data: {"type":"done","agent":"study"}      结束，携带命中的 Agent
 */
export async function chatStream({ question, sessionId, agent, model, onChunk, onDone, onError, signal }) {
  const token = localStorage.getItem('token')
  let res
  try {
    res = await fetch('/api/chat_stream', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify({ id: sessionId, question, Agent: agent || null, model: model || null }),
      signal
    })
  } catch (e) {
    onError?.(e.message || '网络异常')
    return
  }

  if (!res.ok) {
    if (res.status === 401) {
      localStorage.removeItem('token')
      location.hash = '#/login'
      onError?.('登录已过期，请重新登录')
      return
    }
    onError?.(`服务返回 ${res.status}`)
    return
  }

  const reader = res.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let finished = false // 已收到 done 或 error，避免重复回调

  while (true) {
    const { value, done } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })

    // SSE 帧以空行分隔（兼容 CRLF）；逐帧取出 data: 行
    buffer = buffer.replace(/\r\n/g, '\n')
    let sep
    while ((sep = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, sep)
      buffer = buffer.slice(sep + 2)
      for (const line of frame.split('\n')) {
        const trimmed = line.trimEnd()
        if (!trimmed.startsWith('data:')) continue
        const payload = trimmed.slice(5).trim()
        if (!payload || payload === '[DONE]') continue
        try {
          const msg = JSON.parse(payload)
          if (msg.type === 'content') onChunk?.(msg.data || '')
          else if (msg.type === 'error') { finished = true; onError?.(msg.data || '未知错误') }
          else if (msg.type === 'done') { finished = true; onDone?.(msg.agent || '') }
        } catch (e) {
          // 非 JSON 帧（心跳等）直接忽略
        }
      }
    }
  }
  // 流自然结束但没收到 done/error 帧（例如连接被截断）：补一次空 done 让 UI 收尾
  if (!finished) onDone?.('')
}