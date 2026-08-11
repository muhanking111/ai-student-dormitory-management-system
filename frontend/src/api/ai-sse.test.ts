import { describe, expect, it } from 'vitest'
import { collectAiSseEvents, openAiEventStream, parseSseStream } from './ai-sse'
import { afterEach, vi } from 'vitest'
import { setUnauthorizedHandler } from './client'

function streamBytes(chunks: Uint8Array[]) {
  return new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(chunk))
      controller.close()
    },
  })
}

describe('AI SSE parser', () => {
  afterEach(() => {
    setUnauthorizedHandler(undefined)
    vi.unstubAllGlobals()
  })
  it('支持 UTF-8 跨 chunk、CRLF 和多行 data', async () => {
    const source = [
      'id: 7\r\n',
      'event: message.delta\r\n',
      'data: {\r\n',
      'data: "runId":"run-1","sequence":7,"type":"message.delta",\r\n',
      'data: "timestamp":"2026-07-11T10:30:00+08:00","payload":{"textDelta":"你好"}}\r\n\r\n',
    ].join('')
    const bytes = new TextEncoder().encode(source)
    const chineseStart = bytes.findIndex((value, index) => value >= 0x80 && index > 0)
    const chunks = [bytes.slice(0, chineseStart + 1), bytes.slice(chineseStart + 1, chineseStart + 3), bytes.slice(chineseStart + 3)]

    const raw = []
    for await (const event of parseSseStream(streamBytes(chunks))) raw.push(event)

    expect(raw).toEqual([{ id: '7', event: 'message.delta', data: expect.stringContaining('你好') }])
    await expect(collectAiSseEvents(streamBytes(chunks))).resolves.toMatchObject([
      { sequence: 7, type: 'message.delta', payload: { textDelta: '你好' } },
    ])
  })

  it('按 sequence 去重并回传最后事件 ID', async () => {
    const encoder = new TextEncoder()
    const stream = streamBytes([encoder.encode([
      'id: 2\ndata: {"runId":"r","sequence":2,"type":"run.started","timestamp":"t","payload":{}}\n\n',
      'id: 2\ndata: {"runId":"r","sequence":2,"type":"run.started","timestamp":"t","payload":{}}\n\n',
      'id: 3\ndata: {"runId":"r","sequence":3,"type":"run.completed","timestamp":"t","payload":{}}\n\n',
    ].join(''))])
    const seenIds: string[] = []

    const events = await collectAiSseEvents(stream, { lastSequence: 1, onEventId: (id) => seenIds.push(id) })

    expect(events.map((event) => event.sequence)).toEqual([2, 3])
    expect(seenIds).toEqual(['2', '3'])
  })

  it('响应 AbortSignal 并停止解析', async () => {
    const controller = new AbortController()
    controller.abort()

    await expect(collectAiSseEvents(streamBytes([]), { signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' })
  })

  it('重连请求通过 Header 携带 Last-Event-ID，绝不放入 URL', async () => {
    const body = streamBytes([new TextEncoder().encode('id: 10\ndata: {"runId":"r","sequence":10,"type":"run.completed","timestamp":"t","payload":{}}\n\n')])
    const fetchMock = vi.fn().mockResolvedValue({ ok: true, status: 200, body })
    vi.stubGlobal('fetch', fetchMock)

    const events = []
    for await (const event of openAiEventStream('/api/ai/runs/run-1/events', { lastEventId: '9', lastSequence: 9 })) events.push(event)

    expect(events).toHaveLength(1)
    expect(fetchMock).toHaveBeenCalledWith('/api/ai/runs/run-1/events', expect.objectContaining({
      credentials: 'include',
      headers: expect.any(Headers),
    }))
    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Headers
    expect(headers.get('Last-Event-ID')).toBe('9')
    expect(String(fetchMock.mock.calls[0]?.[0])).not.toContain('lastEventId')
  })

  it('SSE 会话过期时触发统一未授权处理并显式失败', async () => {
    const onUnauthorized = vi.fn()
    setUnauthorizedHandler(onUnauthorized)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 401, body: null }))

    const consume = async () => {
      for await (const event of openAiEventStream('/api/ai/runs/run-expired/events')) void event
    }

    await expect(consume()).rejects.toThrow('AI 流式响应不可用（HTTP 401）')
    expect(onUnauthorized).toHaveBeenCalledOnce()
    expect(onUnauthorized.mock.calls[0]?.[0]).toMatchObject({
      status: 401,
      code: 401,
      message: '未登录或登录已过期',
    })
  })
})
