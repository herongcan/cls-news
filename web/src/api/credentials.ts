/**
 * 凭证存取。
 *
 * 存的是后端签发的令牌对，不再是账号密码。
 *
 * 为什么必须换掉「存 user:pass」：密码是长期凭证，一旦进到 localStorage
 * （或小程序的 uni storage），任何一次 XSS、任何一次共用设备都会把它交出去；
 * 而它泄露之后只能改环境变量重启整个服务。令牌是短期的、能单独作废、
 * 且不包含密码本身 —— 泄露的影响窗口从「永久」缩到「最长 30 分钟」。
 *
 * 小程序没有 localStorage，统一走 uni.getStorageSync：H5 下落到 localStorage，
 * 两端行为一致。这也是选 token 的另一个好处 —— 换存储后端时不用再搬密码。
 */

import type { Role } from './types'

/** 新键。旧键 cls.cred 里存的是 {u,p}，只在迁移时读一次。 */
const KEY = 'cls.auth'
const LEGACY_KEY = 'cls.cred'

/** 后端 /api/auth/token 的响应结构，字段名与 Java 侧一致。 */
export interface Tokens {
  user: string
  role: Role
  accessToken: string
  accessExpiresAt: number
  /** 秒。后端算好的，前端不用自己减，避免时钟偏差 */
  expiresIn: number
  refreshToken: string
  refreshExpiresAt: number
}

/** 旧版存下来的账号密码，只用于一次性迁移。 */
export interface LegacyBasic {
  u: string
  p: string
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

/**
 * 手写 base64 而不是用 btoa：小程序环境没有 btoa，而 uni.arrayBufferToBase64
 * 是异步回调式 API，塞进请求头构造里很别扭。账号密码是 ASCII，不需要 UTF-8 处理。
 */
export function base64(input: string): string {
  let out = ''
  let i = 0
  while (i < input.length) {
    const c1 = input.charCodeAt(i++)
    const c2 = i < input.length ? input.charCodeAt(i++) : NaN
    const c3 = i < input.length ? input.charCodeAt(i++) : NaN

    out += B64[c1 >> 2]
    out += B64[((c1 & 3) << 4) | (isNaN(c2) ? 0 : c2 >> 4)]
    out += isNaN(c2) ? '=' : B64[((c2 & 15) << 2) | (isNaN(c3) ? 0 : c3 >> 6)]
    out += isNaN(c3) ? '=' : B64[c3 & 63]
  }
  return out
}

/** 只在换令牌那一步用得上；日常请求都走 Bearer。 */
export function basicHeader(user: string, pass: string): string {
  return 'Basic ' + base64(user + ':' + pass)
}

export function readTokens(): Tokens | null {
  try {
    const raw = uni.getStorageSync(KEY)
    if (!raw) return null
    const t = JSON.parse(raw) as Tokens
    return t && t.accessToken && t.refreshToken ? t : null
  } catch (e) {
    return null
  }
}

export function writeTokens(t: Tokens): void {
  uni.setStorageSync(KEY, JSON.stringify(t))
}

export function clearTokens(): void {
  uni.removeStorageSync(KEY)
}

/**
 * 读旧版遗留的 {u,p}。
 *
 * 存在的意义只有迁移：老用户升级后本地还是旧格式，如果不主动换一次令牌，
 * 他会在接下来所有请求上拿到 401，然后被要求重新输密码 —— 明明密码就在本地。
 */
export function readLegacyBasic(): LegacyBasic | null {
  try {
    const raw = uni.getStorageSync(LEGACY_KEY)
    if (!raw) return null
    const c = JSON.parse(raw) as LegacyBasic
    return c && c.u ? c : null
  } catch (e) {
    return null
  }
}

export function clearLegacyBasic(): void {
  uni.removeStorageSync(LEGACY_KEY)
}
