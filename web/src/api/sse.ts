import { ApiError } from './http'
import { readTokens } from './credentials'

/**
 * 流式 POST（SSE）。
 *
 * <p><b>为什么不用 {@code EventSource}</b>：它只能发 GET，带不了请求体，也不能自定义
 * {@code Authorization} 头。我们需要在 body 里传 templateId / keywords / force，
 * 还要带 Bearer 令牌 —— 所以只能自己拿字节流解。
 *
 * <p><b>为什么两端要分开写</b>：H5 有 {@code fetch + ReadableStream}，微信小程序只有
 * {@code wx.request({enableChunked:true}) + onChunkReceived}。这不是"可以抽象掉"的差异 ——
 * 拿到 chunk 之后的解析可以共用，拿 chunk 这一步不能。
 *
 * <p><b>最容易被忽略的坑：一个汉字会被切在两个 chunk 之间。</b>"取数"的 UTF-8 是
 * 6 个字节，浏览器完全可能在第 4 个字节处断开。这时若按 chunk 各自解码，
 * 两个 chunk 各得半个字符，拼出来是乱码。所以解码器必须<b>带着残字节走</b>
 * （见 {@link utf8Stream}），这也是这里不用 {@code TextDecoder} 的原因：
 * 小程序基础库不是每个版本都有它，而这段逻辑不到 30 行。
 */

/** 服务端事件名，与 AgentController.askStream 一一对应 */
export type SseEvent = 'open' | 'step' | 'reasoning' | 'done' | 'error'

export interface StreamHandlers {
  /** 流已建立，生成开始。这一刻起才该切"生成中" */
  onOpen?: () => void
  /** 走完一步。同一 k 的后来者应覆盖前者（模型那步从「等待中」变成「9.9 秒」） */
  onStep?: (data: unknown) => void
  /** 模型思考过程的一段增量文本，逐段追加 */
  onReasoning?: (chunk: string) => void
  onDone?: (data: unknown) => void
  /** 失败。4xx/5xx（开流前就被挡）与流内 error 事件都从这里出 */
  onError?: (e: ApiError) => void
  /** 流正常结束（无论有没有 done），收尾用 */
  onClose?: () => void
}

/** 解析出来的一帧 */
interface Frame {
  event: string
  data: string
}

/**
 * 增量 UTF-8 解码器。
 *
 * <p>返回的函数每次接收"这次新到的字节"，输出"到现在为止能确定的字符"，
 * 最后一个不完整的字符会留在内部，等下一批字节补齐 —— 这正是
 * {@code TextDecoder({stream:true})} 做的事，这里手写一份以摆脱对基础库版本的依赖。
 */
export function utf8Stream(): (bytes: Uint8Array) => string {
  let pending: number[] = []

  return function decode(bytes: Uint8Array): string {
    const all = pending.length ? pending.concat(Array.from(bytes)) : Array.from(bytes)
    pending = []

    let out = ''
    let i = 0
    while (i < all.length) {
      const b = all[i]
      let need = 0
      let cp = 0

      if (b < 0x80) {
        out += String.fromCharCode(b)
        i += 1
        continue
      } else if ((b & 0xe0) === 0xc0) {
        need = 2
        cp = b & 0x1f
      } else if ((b & 0xf0) === 0xe0) {
        need = 3
        cp = b & 0x0f
      } else if ((b & 0xf8) === 0xf0) {
        need = 4
        cp = b & 0x07
      } else {
        // 0x80~0xbf 是续字节却出现在这里，或者 0xf8+ 是非法首字节：跳过，别把整条流带崩
        i += 1
        continue
      }

      if (i + need > all.length) {
        // 不完整的尾巴：留到下一批。留着而不是丢弃，否则这个字永远缺一块
        pending = all.slice(i)
        break
      }
      for (let k = 1; k < need; k += 1) {
        cp = (cp << 6) | (all[i + k] & 0x3f)
      }
      out += String.fromCodePoint(cp)
      i += need
    }
    return out
  }
}

/**
 * 按 SSE 规范切帧。
 *
 * <p>帧之间用空行分隔。{@code :} 开头的是注释（服务端用它做心跳），
 * 丢掉 —— 它不带 data，若当成事件会让上层收到一个空对象。
 *
 * @param buf 上次没切完的残留 + 这次新解出来的文本
 * @return frames 是已经完整的帧，rest 是要带到下一次的尾巴
 */
export function splitFrames(buf: string): { frames: Frame[]; rest: string } {
  // 规范允许 CRLF。统一成 LF 再切，否则 "data: x\r\n\r\n" 里找不到 "\n\n"
  let text = buf.replace(/\r\n/g, '\n')
  const frames: Frame[] = []

  let at = text.indexOf('\n\n')
  while (at >= 0) {
    const raw = text.slice(0, at)
    text = text.slice(at + 2)
    const f = parseFrame(raw)
    if (f) frames.push(f)
    at = text.indexOf('\n\n')
  }
  return { frames, rest: text }
}

function parseFrame(raw: string): Frame | null {
  let event = 'message'
  const data: string[] = []

  for (const line of raw.split('\n')) {
    if (!line || line.startsWith(':')) continue
    const colon = line.indexOf(':')
    const field = colon < 0 ? line : line.slice(0, colon)
    let value = colon < 0 ? '' : line.slice(colon + 1)
    if (value.startsWith(' ')) value = value.slice(1)
    if (field === 'event') event = value
    else if (field === 'data') data.push(value)
  }

  // 只有注释的块（心跳）在这里被丢掉
  if (!data.length) return null
  return { event, data: data.join('\n') }
}

function authHeaders(): Record<string, string> {
  const header: Record<string, string> = { 'Content-Type': 'application/json' }
  const t = readTokens()
  if (t) header.Authorization = 'Bearer ' + t.accessToken
  return header
}

/** 把一帧派发给上层。解析不了的 JSON 直接丢 —— 一条坏事件不该中断整条流。 */
function dispatch(f: Frame, h: StreamHandlers): void {
  let body: unknown = null
  try {
    body = JSON.parse(f.data)
  } catch {
    return
  }
  if (f.event === 'open') h.onOpen?.()
  else if (f.event === 'step') h.onStep?.(body)
  else if (f.event === 'reasoning') h.onReasoning?.(body as string)
  else if (f.event === 'done') h.onDone?.(body)
  else if (f.event === 'error') {
    const e = body as { code?: string; error?: string }
    h.onError?.(new ApiError(e.error || '生成失败', 502, e.code || ''))
  }
}

/** 开流前就被挡下来（401/429/503）时，body 是普通 JSON，不是 SSE */
function errorFromBody(status: number, text: string): ApiError {
  try {
    const j = JSON.parse(text) as { error?: string; message?: string; code?: string }
    return new ApiError(j.error || j.message || '请求失败（HTTP ' + status + '）', status, j.code || '')
  } catch {
    return new ApiError(text?.trim() || '请求失败（HTTP ' + status + '）', status)
  }
}

/**
 * 发一次流式请求。
 *
 * @returns 中止函数。用户关浮层、换模板时要调它 —— 不调的话连接会挂到服务端超时，
 *          而那时后端还在花钱跑模型（它不会因为前端走了就停，这是刻意的）
 */
export function streamPost(
  path: string,
  body: Record<string, unknown>,
  handlers: StreamHandlers
): () => void {
  const header = authHeaders()
  let aborted = false
  /** 已经收到 done 或 error，后续字节一律忽略 */
  let settled = false

  const finish = (): void => {
    if (settled) return
    settled = true
    handlers.onClose?.()
  }

  const fail = (e: ApiError): void => {
    if (settled || aborted) return
    settled = true
    handlers.onError?.(e)
    handlers.onClose?.()
  }

  const consume = (text: string, rest: { value: string }): void => {
    const r = splitFrames(rest.value + text)
    rest.value = r.rest
    for (const f of r.frames) {
      if (settled) return
      dispatch(f, handlers)
      if (f.event === 'done' || f.event === 'error') {
        // 服务端在 done 之后还会 complete，前端不必等 EOF：先收尾，剩下的字节没有意义。
        // 必须在这里直接走 finish()，而不是只把 settled 置 true 等外层再收 ——
        // finish() 的第一行就是 if(settled) return，晚一步它就会把 onClose 吞掉，
        // 结果 busy 永远停在「生成中」（2026-11-28 真机卡死就是这条）
        finish()
      }
    }
  }

  // #ifdef H5
  const ctrl = new AbortController()
  const timer = setTimeout(() => ctrl.abort(), 180_000)

  fetch(path, {
    method: 'POST',
    headers: header,
    body: JSON.stringify(body),
    signal: ctrl.signal
  })
    .then((res) => {
      if (!res.ok) {
        return res.text().then((t) => {
          clearTimeout(timer)
          fail(errorFromBody(res.status, t))
        })
      }
      const reader = res.body?.getReader()
      if (!reader) {
        clearTimeout(timer)
        fail(new ApiError('这个浏览器不支持流式读取', 0))
        return undefined
      }
      const decode = utf8Stream()
      const rest = { value: '' }

      const pump = (): Promise<void> => reader.read().then(({ done, value }) => {
        if (aborted) return
        if (done) {
          if (!settled) fail(new ApiError('连接中断，没等到结果', 0))
          else finish()
          return
        }
        consume(decode(value), rest)
        if (settled) {
          // 收到 done 就主动收线：不读的话这个 reader 会一直挂着
          reader.cancel().catch(() => undefined)
          finish()
          return
        }
        return pump()
      })

      return pump()
    })
    .catch((e) => {
      if (aborted) return
      fail(e?.name === 'AbortError' ? new ApiError('等了太久，已中断', 0) : new ApiError('连不上服务器', 0))
    })
    .finally(() => clearTimeout(timer))

  return () => {
    aborted = true
    clearTimeout(timer)
    ctrl.abort()
    finish()
  }
  // #endif

  // #ifdef MP-WEIXIN
  const decode = utf8Stream()
  const rest = { value: '' }

  // enableChunked 与 onChunkReceived 是微信独有的能力，uni.request 没有等价物，
  // 所以这里必须直接拿 wx。@dcloudio/types 不给它类型（那是 uni 的命名空间），
  // 于是显式写一份最小接口 —— 比 any 好在"用错的字段会当场报错"，而不是运行时才发现
  const wxApi = (globalThis as unknown as { wx?: MpWx }).wx as MpWx | undefined
  if (!wxApi) {
    fail(new ApiError('当前环境不支持流式请求', 0))
    return () => undefined
  }
  // 判空之后再断一次。看着多余，但 H5 与小程序两段代码在 type-check 时是共存的
  // （条件编译只在构建期剥离），这边不收窄的话 tsc 会在下一行报 "possibly undefined"
  const mp = wxApi as MpWx

  const task = mp.request({
    url: path,
    method: 'POST',
    header,
    data: body,
    // 不开 chunked 的话 onChunkReceived 永远不触发，拿到的是"整个响应"，
    // 等于退回同步请求 —— 而且是静默退回，看不出来
    enableChunked: true,
    responseType: 'arraybuffer',
    success: (res) => {
      const status = res.statusCode || 0
      if (status >= 400) {
        fail(errorFromBody(status, textOf(res.data)))
        return
      }
      // 正常路径的收尾放在 done 事件里；这里兜住"流结束了但没收到 done"
      if (!settled) {
        fail(new ApiError('连接中断，没等到结果', 0))
      }
    },
    fail: (e) => {
      if (aborted) return
      fail(new ApiError(e.errMsg || '连不上服务器', 0))
    }
  })

  task.onChunkReceived((chunk) => {
    if (settled || aborted) return
    consume(decode(new Uint8Array(chunk.data)), rest)
    if (settled) {
      // 已经拿到结论，主动断开：不 abort 的话这条连接会挂到服务端超时
      task.abort()
      finish()
    }
  })

  return () => {
    aborted = true
    try {
      task.abort()
    } catch {
      /* 已经结束了 */
    }
    finish()
  }
  // #endif
}

/** 小程序里错误响应可能是 ArrayBuffer（开了 responseType）也可能是字符串，两种都收 */
function textOf(raw: unknown): string {
  if (typeof raw === 'string') return raw
  if (raw instanceof ArrayBuffer) return utf8Stream()(new Uint8Array(raw))
  return ''
}

/** 微信请求对象的最小形状。只写实际用到的那几个方法。 */
interface MpRequestTask {
  onChunkReceived: (cb: (chunk: { data: ArrayBuffer }) => void) => void
  abort: () => void
}

interface MpWx {
  request: (opts: {
    url: string
    method: string
    header: Record<string, string>
    data: Record<string, unknown>
    enableChunked: boolean
    responseType: 'arraybuffer'
    success: (res: { statusCode?: number; data?: unknown }) => void
    fail: (e: { errMsg?: string }) => void
  }) => MpRequestTask
}
