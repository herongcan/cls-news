import { basicHeader, readCred } from './credentials'

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

/** 后端错误体有两种：`{"error":"..."}` 和 BasicAuthFilter 直接写的一段纯文本 */
function pickMessage(data: unknown, status: number): string {
  if (typeof data === 'string' && data.trim()) return data.trim()
  if (data && typeof data === 'object') {
    const body = data as Record<string, unknown>
    for (const key of ['error', 'message', 'msg']) {
      const v = body[key]
      if (typeof v === 'string' && v.trim()) return v.trim()
    }
  }
  if (status === 401) return '账号或密码不对'
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
}

export function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const cred = readCred()
  const header: Record<string, string> = { 'Content-Type': 'application/json' }
  if (cred) header.Authorization = basicHeader(cred)

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
