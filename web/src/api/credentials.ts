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
/**
 * 上次登录用的账号。故意和令牌分开存：
 * 令牌失效时会被清掉，而「账号」这个信息不该跟着丢 —— 会话过期后重登，
 * 用户不该连自己用的是哪个账号都要重新想一遍。
 *
 * 只存账号，不存密码。
 */
const USER_KEY = 'cls.lastUser'

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

/**
 * 内存里那一份。
 *
 * <p>为什么不只依赖 uni storage：它是**同源共享**的。任何另一个标签页执行
 * {@code removeItem} 都会把这个标签的凭证一起抹掉，之后它的每条请求都变成「裸奔」
 * —— 没有 Authorization 头，服务端只能回 401，而本地又没有 refresh 可换，
 * 只能弹重登。实测复现过：另一个标签删掉键之后，这个标签下一轮请求就掉线，
 * 表现是「刚登录不到一分钟又要重新输密码」，而服务端那边会话好端端的。
 *
 * <p>所以内存里留一份、storage 只是持久化：发现键被外部清掉就补回去，
 * 正在用的标签不再受别人影响；本标签自己调 {@link clearTokens} 时两份一起清，
 * 不会出现「点了退出还登着」。
 *
 * <p>代价说清楚：A 标签登出、B 标签继续用时，B 会把自己的副本写回 storage。
 * 但登出会先在服务端把这个 session 的令牌全删掉，B 拿着副本请求只会 401，
 * 最终还是回到登录层 —— 安全边界没变，只多绕一圈。
 */
let mem: Tokens | null = null

/** 只读 storage，不做任何修补。解析失败、字段缺失一律当没有。 */
function readStorage(): Tokens | null {
  try {
    const raw = uni.getStorageSync(KEY)
    if (!raw) return null
    const t = typeof raw === 'string' ? (JSON.parse(raw) as Tokens) : (raw as Tokens)
    return isTokens(t) ? t : null
  } catch (e) {
    return null
  }
}

function writeStorage(t: Tokens): void {
  try {
    uni.setStorageSync(KEY, JSON.stringify(t))
  } catch (e) {
    // 存储满了 / 无痕模式下被禁用：内存那份还能撑完这个页面，不当错误抛
  }
}

/**
 * 判断一个响应体是不是可用的令牌对。
 *
 * <p>校验放在写入之前，是因为「写坏」比「没写」更糟：如果哪天刷新接口回了
 * 一个 200 但不是令牌形状的东西（反代错误页、网关占位、字段改名），
 * 直接写进去会把手里那份好令牌覆盖成垃圾，之后每条请求都变成裸奔 ——
 * 那正是最难查的一类掉线。宁可当这次刷新失败，也别覆盖已有的凭证。
 */
export function isTokens(v: unknown): v is Tokens {
  if (!v || typeof v !== 'object') return false
  const t = v as Record<string, unknown>
  return (
    typeof t.accessToken === 'string' &&
    t.accessToken.length > 0 &&
    typeof t.refreshToken === 'string' &&
    t.refreshToken.length > 0 &&
    typeof t.accessExpiresAt === 'number' &&
    t.accessExpiresAt > 0
  )
}

export function readTokens(): Tokens | null {
  if (mem) {
    // 键被别的标签（或浏览器清理）删掉了：补回去。没有这一步，
    // 下一个请求就没有 Authorization，401 之后连 refresh 都没得用。
    if (!readStorage()) writeStorage(mem)
    return mem
  }
  const t = readStorage()
  if (t) mem = t
  return t
}

export function writeTokens(t: Tokens): void {
  mem = t
  writeStorage(t)
}

export function clearTokens(): void {
  mem = null
  try {
    uni.removeStorageSync(KEY)
  } catch (e) {
    // 同上：清不掉也要保证内存里那份没了，否则等于没登出
  }
}

/**
 * 记住账号，供「会话过期后重登」预填。
 *
 * 写失败不影响登录，只是下次要手输 —— 所以吞掉异常。
 */
export function rememberUser(user: string): void {
  try {
    uni.setStorageSync(USER_KEY, user)
  } catch (e) {
    // 存储满了/被禁用：不值得为这个打断登录
  }
}

export function readLastUser(): string {
  try {
    const v = uni.getStorageSync(USER_KEY)
    return typeof v === 'string' ? v : ''
  } catch (e) {
    return ''
  }
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
