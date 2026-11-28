import {
  basicHeader,
  clearTokens,
  isTokens,
  readTokens,
  writeTokens,
  type Tokens
} from './credentials'

/**
 * 统一出口。
 *
 * 生产环境页面和接口同源（前端构建产物打进 jar 的 static/），所以 BASE 留空；
 * 开发期由 vite 的 proxy 把 /api 转发到 127.0.0.1:8080，也不需要 CORS。
 * 一旦改成独立域名托管，这里换成一个可配置的 baseUrl，并由后端放行 CORS。
 */
const BASE = ''

export class ApiError extends Error {
  /** 0 = 网络层失败（断网、超时、域名没进白名单），其余是 HTTP 状态码 */
  status: number

  /**
   * 后端的机器可读错误码（body.code），拿不到时为空串。
   *
   * 为什么不能只看 status：同一个 400 既可能是「这条不该分析」（PROMO_ITEM，
   * 不该把按钮改成"重试"），也可能是以后新增的参数错误。状态码分不出来，
   * 文案又会被改，只有 code 是稳定契约。
   */
  code: string

  constructor(message: string, status = 0, code = '') {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

type Query = Record<string, string | number | boolean | undefined>

/**
 * 鉴权方式：
 *   auto（默认）—— 有令牌带 Bearer，没有就不带
 *   none        —— 不带任何凭证，刷新令牌时必须用它，否则会递归
 *   {basic}     —— 显式用 Basic，只有「换令牌」这一步需要
 *
 * <p>曾经 auto 在没有令牌时会回落到旧版 Basic（迁移期兜底）。服务端收掉
 * 「Basic 直连业务接口」之后这条回落已经没有任何用处 —— 带上密码只会拿到 401，
 * 而且等于把密码又发了一遍。旧格式的迁移由 auth store 的 migrateLegacy()
 * 单独走 POST /api/auth/token 完成，那里是唯一还需要 Basic 的地方。
 */
export type AuthMode = 'auto' | 'none' | { basic: { u: string; p: string } }

function qs(query?: Query): string {
  if (!query) return ''
  const parts: string[] = []
  for (const k of Object.keys(query)) {
    const v = query[k]
    if (v === undefined || v === '') continue
    parts.push(encodeURIComponent(k) + '=' + encodeURIComponent(String(v)))
  }
  return parts.length ? '?' + parts.join('&') : ''
}

/** 后端错误体有三种：`{"error":"..."}`、`{"message":"..."}`，以及过滤器直接写的纯文本 */
function pickMessage(data: unknown, status: number): string {
  if (typeof data === 'string' && data.trim()) return data.trim()
  if (data && typeof data === 'object') {
    const body = data as Record<string, unknown>
    for (const key of ['error', 'message', 'msg']) {
      const v = body[key]
      if (typeof v === 'string' && v.trim()) return v.trim()
    }
  }
  if (status === 401) return '登录已过期，请重新登录'
  if (status === 403) return '当前账号没有这个权限'
  if (status === 404) return '接口不存在'
  if (status === 429) return '登录尝试过于频繁，请稍后再试'
  return '请求失败（HTTP ' + status + '）'
}

/** 取后端的机器可读错误码；没有就返回空串，调用方按"未知错误"处理。 */
function pickCode(data: unknown): string {
  if (data && typeof data === 'object') {
    const v = (data as Record<string, unknown>).code
    if (typeof v === 'string' && v.trim()) return v.trim()
  }
  return ''
}

interface RequestInit {
  method?: 'GET' | 'POST'
  query?: Query
  /** POST 请求体。uni.request 会自动 JSON 序列化，不用手动 stringify */
  body?: Record<string, unknown>
  /** 分析要等模型返回，给足时间；其余用默认值 */
  timeout?: number
  auth?: AuthMode
  /**
   * 会话失效时是否挂起等用户重登，默认 true。
   *
   * <p>启动校验必须传 false：那会儿页面还停在「正在连接…」，登录层根本没渲染出来，
   * 挂住只会让人对着转圈等满两分钟。启动阶段要的是「立刻 401 → 渲染登录层」。
   */
  waitLogin?: boolean
}

function authHeader(mode: AuthMode = 'auto'): string | null {
  if (mode === 'none') return null
  if (typeof mode === 'object') return basicHeader(mode.basic.u, mode.basic.p)
  const t = readTokens()
  return t ? 'Bearer ' + t.accessToken : null
}

/** 只负责发一次请求，不带任何重试逻辑。 */
function send<T>(path: string, init: RequestInit): Promise<T> {
  const header: Record<string, string> = { 'Content-Type': 'application/json' }
  const auth = authHeader(init.auth)
  if (auth) header.Authorization = auth

  return new Promise<T>((resolve, reject) => {
    uni.request({
      url: BASE + path + qs(init.query),
      method: init.method ?? 'GET',
      header,
      ...(init.body === undefined ? {} : { data: init.body }),
      timeout: init.timeout ?? 15000,
      success: (res) => {
        const status = res.statusCode ?? 0
        if (status >= 200 && status < 300) {
          resolve(res.data as T)
        } else {
          reject(new ApiError(pickMessage(res.data, status), status, pickCode(res.data)))
        }
      },
      fail: (err) => {
        reject(new ApiError(err.errMsg || '连不上服务器', 0))
      }
    })
  })
}

/**
 * 刷新中的单飞锁。
 *
 * 没有它的话，首屏那句「列表 + 状态 + 访客」会同时带着同一个过期令牌发出三个请求，
 * 三个都 401，然后各自去刷新 —— 而 refresh 是一次性旋转的，只有第一个能成功，
 * 另外两个会拿到 401，用户看到的现象是「刚登录又被踢出去」。
 * 加锁之后三个请求等同一个刷新结果，各自重放一次。
 */
let refreshing: Promise<RefreshResult> | null = null

/**
 * 一次刷新的三种结局。**必须分开**，因为它们的后续处理完全不同：
 *   ok        —— 换到了新令牌，重放请求
 *   dead      —— 服务端明确说这个 refresh 不认（401），只能重登
 *   transient —— 超时、断网、5xx、回了 200 但不是令牌形状；凭证还是好的，别动它
 *
 * <p>以前只有 true/false 两种，`.catch(() => false)` 把「网抖了一下」和
 * 「会话真的死了」当成一回事 —— 于是任何一次刷新超时都会 clearTokens() +
 * 弹重登框。用户什么都没做错，只是网差了一秒，却要重新输密码。
 */
type RefreshResult = 'ok' | 'dead' | 'transient'

function tryRefresh(): Promise<RefreshResult> {
  if (refreshing) return refreshing
  const t = readTokens()
  if (!t) return Promise.resolve('dead')

  const p = send<Tokens>('/api/auth/refresh', {
    method: 'POST',
    body: { refreshToken: t.refreshToken },
    auth: 'none',
    timeout: 8000
  })
    .then((next) => {
      // 形状不对就不写，见 isTokens 上的说明：写坏比不写更糟
      if (!isTokens(next)) return 'transient' as const
      writeTokens(next)
      return 'ok' as const
    })
    .catch((e) => {
      // 只有 401 才算「会话真的没了」。其余（0=网络层、5xx、超时）都当临时故障
      return e instanceof ApiError && e.status === 401 ? ('dead' as const) : ('transient' as const)
    })
    .finally(() => {
      refreshing = null
    })

  refreshing = p
  return p
}

/**
 * 刷新也换不回来之后：本地凭证彻底不可用，需要人来重新登录。
 *
 * 三种做法，各有代价：
 *   1. 直接抛 401 让页面自己弹框 —— 简单，但轮询还在跑，每 30 秒 401 一次，
 *      用户看到的是「点一下重试」和一串控制台红字，点多少次都不会成功。
 *   2. 清空列表切回登录页 —— 状态干净，但正在看的位置和翻到第几页全丢了。
 *   3. 把请求挂住，等用户原地登录完再自动重放 —— 体验最好，代价是请求可能悬着，
 *      所以必须有个上限。
 *
 * 这里选 3：所有同时失效的请求挂在同一个 Promise 上，登录成功后一起唤醒重放；
 * 用户点「退出登录」或者两分钟没动，统一按 401 失败。
 */
const LOGIN_WAIT_MS = 120_000

type SessionLostHandler = () => void

let sessionLostHandler: SessionLostHandler | null = null
/** 多个请求同时失效时只通知一次，别叠出好几层登录框 */
let lostNotified = false

interface Waiter {
  resolve: () => void
  reject: (e: unknown) => void
}

let waiter: Waiter | null = null
let waiting: Promise<void> | null = null

/** 由 auth store 注册：会话失效时把界面切到「原地重登」浮层 */
export function setSessionLostHandler(fn: SessionLostHandler | null): void {
  sessionLostHandler = fn
}

/** 登录成功：唤醒所有挂起的请求，让它们带着新令牌重放 */
export function resumeAfterLogin(): void {
  lostNotified = false
  const w = waiter
  waiter = null
  waiting = null
  w?.resolve()
}

/** 用户放弃重登：挂起的请求按 401 失败，不要一直悬着占内存 */
export function abortWaitingLogin(): void {
  lostNotified = false
  const w = waiter
  waiter = null
  waiting = null
  w?.reject(new ApiError('登录已过期，请重新登录', 401))
}

function notifySessionLost(): void {
  if (lostNotified) return
  lostNotified = true
  try {
    sessionLostHandler?.()
  } catch (e) {
    // 界面回调出错不能反过来把请求链带崩
  }
}

function waitForLogin(): Promise<void> {
  if (waiting) return waiting
  waiting = new Promise<void>((resolve, reject) => {
    const timer = setTimeout(() => {
      waiter = null
      waiting = null
      reject(new ApiError('登录已过期，请重新登录', 401))
    }, LOGIN_WAIT_MS)
    waiter = {
      resolve: () => {
        clearTimeout(timer)
        resolve()
      },
      reject: (e) => {
        clearTimeout(timer)
        reject(e)
      }
    }
  })
  return waiting
}

export function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  return requestInner<T>(path, init, false)
}

async function requestInner<T>(path: string, init: RequestInit, retried: boolean): Promise<T> {
  const mode = init.auth ?? 'auto'

  // 距过期不到 30 秒就先续一次：省掉「必然 401 → 刷新 → 重放」这个来回。
  // 用 30 秒而不是 0，是因为按 0 判断时请求在路上就已经过期了，白跑一趟。
  if (mode === 'auto' && !retried) {
    const t = readTokens()
    if (t && t.accessExpiresAt - Date.now() < 30_000) {
      await tryRefresh()
    }
  }

  // 记下这次真正带出去的凭证。401 之后如果它变了，说明另一个标签已经刷新成功并写入，
  // 那这次 401 只是「我来晚了」，不该把自己连带登出。
  const sent = mode === 'auto' ? authHeader('auto') : null

  try {
    return await send<T>(path, init)
  } catch (e) {
    const expired = e instanceof ApiError && e.status === 401
    if (!expired || retried || mode !== 'auto') throw e

    // 这次带出去的压根不是令牌（本地那份被另一个标签/浏览器清理删掉过）。
    // 现在手里有就说明只是「发得太早」，直接重放，别当成会话失效。
    if (!sent && readTokens()) return requestInner<T>(path, init, true)

    const r = await tryRefresh()
    if (r === 'ok') return requestInner<T>(path, init, true)

    // 超时/断网/5xx：凭证还是好的，这次按失败返回就行。
    // 清掉本地凭证是最糟的处理 —— 用户只是网差了一秒，回来却要重输密码。
    if (r === 'transient') throw e

    const fresh = authHeader('auto')
    if (fresh && fresh !== sent) {
      return requestInner<T>(path, init, true)
    }

    // refresh 也被服务端明确拒了：7 天到期、被登出，或者服务端换了数据库。
    // 清掉本地凭证，让界面弹出重登浮层；请求先挂住，登录成功后原地重放 ——
    // 这样列表、滚动位置都还在，用户不用重新找刚才看到哪一条。
    clearTokens()
    if (init.waitLogin === false) throw e
    notifySessionLost()
    try {
      await waitForLogin()
    } catch {
      throw e
    }
    return requestInner<T>(path, init, true)
  }
}

/** 用账号密码换令牌。只有登录这一步用 Basic，之后不再出现密码。 */
export function requestToken(user: string, pass: string): Promise<Tokens> {
  return request<Tokens>('/api/auth/token', {
    method: 'POST',
    auth: { basic: { u: user, p: pass } },
    timeout: 12000
  })
}

/**
 * 登出：服务端按 refresh 找到这次登录，把 access 和 refresh 一起作废。
 * 不带 Bearer（auth: 'none'）是故意的：登出时 access 可能已经过期了，
 * 带上它反而会被过滤器拦在 401。
 * 失败也不影响前端清本地状态。
 */
export function revokeSession(refreshToken: string): Promise<unknown> {
  return request('/api/auth/logout', {
    method: 'POST',
    body: { refreshToken },
    auth: 'none',
    timeout: 8000
  })
}
