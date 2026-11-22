import {
  basicHeader,
  clearTokens,
  readLegacyBasic,
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

  constructor(message: string, status = 0) {
    super(message)
    this.name = 'ApiError'
    this.status = status
  }
}

type Query = Record<string, string | number | boolean | undefined>

/**
 * 鉴权方式：
 *   auto（默认）—— 有令牌带 Bearer，没有则沿用旧版 Basic（迁移期兜底）
 *   none        —— 不带任何凭证，刷新令牌时必须用它，否则会递归
 *   {basic}     —— 显式用 Basic，只有「换令牌」这一步需要
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
  return '请求失败（HTTP ' + status + '）'
}

interface RequestInit {
  method?: 'GET' | 'POST'
  query?: Query
  /** POST 请求体。uni.request 会自动 JSON 序列化，不用手动 stringify */
  body?: Record<string, unknown>
  /** 分析要等模型返回，给足时间；其余用默认值 */
  timeout?: number
  auth?: AuthMode
}

function authHeader(mode: AuthMode = 'auto'): string | null {
  if (mode === 'none') return null
  if (typeof mode === 'object') return basicHeader(mode.basic.u, mode.basic.p)
  const t = readTokens()
  if (t) return 'Bearer ' + t.accessToken
  // 迁移期兜底：本地还是旧版 {u,p} 时，先用 Basic 把请求打通，
  // 等 boot() 换到令牌之后就一直走 Bearer。
  const legacy = readLegacyBasic()
  return legacy ? basicHeader(legacy.u, legacy.p) : null
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
          reject(new ApiError(pickMessage(res.data, status), status))
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
let refreshing: Promise<boolean> | null = null

function tryRefresh(): Promise<boolean> {
  if (refreshing) return refreshing
  const t = readTokens()
  if (!t) return Promise.resolve(false)

  const p = send<Tokens>('/api/auth/refresh', {
    method: 'POST',
    body: { refreshToken: t.refreshToken },
    auth: 'none',
    timeout: 8000
  })
    .then((next) => {
      writeTokens(next)
      return true
    })
    .catch(() => false)
    .finally(() => {
      refreshing = null
    })

  refreshing = p
  return p
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

  try {
    return await send<T>(path, init)
  } catch (e) {
    const expired = e instanceof ApiError && e.status === 401
    if (!expired || retried || mode !== 'auto') throw e

    const ok = await tryRefresh()
    if (!ok) {
      // refresh 也失效了（7 天到期，或服务重启过）。清掉本地凭证，
      // 让上层把界面切回登录层，而不是一直重试。
      clearTokens()
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
