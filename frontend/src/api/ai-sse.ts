import type { AiRunEvent } from '../types/ai'
import { reportUnauthorizedResponse } from './client'

export interface RawSseEvent {
  id?: string
  event?: string
  data: string
}

export interface AiSseOptions {
  signal?: AbortSignal
  lastSequence?: number
  onEventId?: (eventId: string) => void
}

export interface OpenAiSseOptions extends AiSseOptions {
  lastEventId?: string
}

function abortError() {
  return new DOMException('The operation was aborted', 'AbortError')
}

function parseRawEvent(block: string): RawSseEvent | null {
  let id: string | undefined
  let event: string | undefined
  const data: string[] = []
  for (const rawLine of block.split(/\r?\n/)) {
    if (!rawLine || rawLine.startsWith(':')) continue
    const separator = rawLine.indexOf(':')
    const field = separator < 0 ? rawLine : rawLine.slice(0, separator)
    let value = separator < 0 ? '' : rawLine.slice(separator + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'id') id = value
    else if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }
  return data.length > 0 ? { id, event, data: data.join('\n') } : null
}

export async function* parseSseStream(
  stream: ReadableStream<Uint8Array>,
  signal?: AbortSignal,
): AsyncGenerator<RawSseEvent> {
  if (signal?.aborted) throw abortError()
  const reader = stream.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  const cancel = () => { void reader.cancel(abortError()) }
  signal?.addEventListener('abort', cancel, { once: true })
  try {
    while (true) {
      if (signal?.aborted) throw abortError()
      const { done, value } = await reader.read()
      if (done) break
      buffer += decoder.decode(value, { stream: true })
      let match = /\r?\n\r?\n/.exec(buffer)
      while (match?.index !== undefined) {
        const block = buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match[0].length)
        const event = parseRawEvent(block)
        if (event) yield event
        match = /\r?\n\r?\n/.exec(buffer)
      }
    }
    buffer += decoder.decode()
    const final = parseRawEvent(buffer)
    if (final) yield final
  } finally {
    signal?.removeEventListener('abort', cancel)
    reader.releaseLock()
  }
}

export async function collectAiSseEvents(
  stream: ReadableStream<Uint8Array>,
  options: AiSseOptions = {},
) {
  if (options.signal?.aborted) throw abortError()
  const events: AiRunEvent[] = []
  let lastSequence = options.lastSequence ?? 0
  for await (const raw of parseSseStream(stream, options.signal)) {
    const event = JSON.parse(raw.data) as AiRunEvent
    if (!Number.isSafeInteger(event.sequence) || event.sequence <= lastSequence) continue
    lastSequence = event.sequence
    if (raw.id) options.onEventId?.(raw.id)
    events.push({ ...event, eventId: raw.id ?? event.eventId })
  }
  return events
}

export async function* parseAiEventStream(
  stream: ReadableStream<Uint8Array>,
  options: AiSseOptions = {},
): AsyncGenerator<AiRunEvent> {
  if (options.signal?.aborted) throw abortError()
  let lastSequence = options.lastSequence ?? 0
  for await (const raw of parseSseStream(stream, options.signal)) {
    const event = JSON.parse(raw.data) as AiRunEvent
    if (!Number.isSafeInteger(event.sequence) || event.sequence <= lastSequence) continue
    lastSequence = event.sequence
    if (raw.id) options.onEventId?.(raw.id)
    yield { ...event, eventId: raw.id ?? event.eventId }
  }
}

export async function* openAiEventStream(
  url: string,
  options: OpenAiSseOptions = {},
): AsyncGenerator<AiRunEvent> {
  const headers = new Headers({ Accept: 'text/event-stream' })
  if (options.lastEventId) headers.set('Last-Event-ID', options.lastEventId)
  const response = await fetch(url, {
    method: 'GET',
    headers,
    credentials: 'include',
    cache: 'no-store',
    signal: options.signal,
  })
  if (response.status === 401) reportUnauthorizedResponse()
  if (!response.ok || !response.body) throw new Error(`AI 流式响应不可用（HTTP ${response.status}）`)
  yield* parseAiEventStream(response.body, options)
}
