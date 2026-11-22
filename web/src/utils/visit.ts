/**
 * 访客上报。
 *
 * 后端按「出口 IP + vid」去重：公司 NAT 后面十个人，只按 IP 算是 1 个访客，
 * 带上 vid 才是 10 个。vid 存本地，同一个人反复回访不会重复计数。
 *
 * 只在前端拿到有效凭证后调用。/api/visit/ping 需要过鉴权（只读账号也放行），
 * 扫描器进不来，所以这份统计不会被爬虫污染 —— 这也是不能改成统计 GET / 的原因：
 * 首页为了让移动端能弹登录框是免鉴权的。
 */

import { request } from '@/api/http'

const KEY = 'cls.vid'

/** 整个会话只报一次：登录页进入和登录成功后都会走到这里 */
let reported = false

/**
 * 32 位十六进制随机串。
 *
 * 小程序没有 crypto.randomUUID，浏览器在 http 明文下也没有（非 secure context），
 * 所以优先用 getRandomValues，拿不到再用 Math.random 兜底 —— 访客去重不需要密码学强度。
 */
function newId(): string {
  const c = typeof globalThis === 'undefined' ? undefined : (globalThis as { crypto?: Crypto }).crypto
  if (c && typeof c.getRandomValues === 'function') {
    const buf = new Uint8Array(16)
    c.getRandomValues(buf)
    return Array.from(buf, (b) => b.toString(16).padStart(2, '0')).join('')
  }
  let s = ''
  for (let i = 0; i < 32; i++) s += Math.floor(Math.random() * 16).toString(16)
  return s
}

function visitId(): string {
  try {
    const cached = uni.getStorageSync(KEY)
    if (typeof cached === 'string' && /^[0-9a-f]{8,64}$/.test(cached)) return cached
    const id = newId()
    uni.setStorageSync(KEY, id)
    return id
  } catch (e) {
    // 无痕模式 / 存储被禁用：不报错，服务端会按 IP 兜底去重
    return ''
  }
}

/** 上报一次访问。不阻塞首屏、失败不重试 —— 这是统计，不是业务 */
export function pingVisit(): void {
  if (reported) return
  reported = true
  const vid = visitId()
  if (!vid) return
  request<unknown>('/api/visit/ping', { method: 'POST', body: { vid } }).catch(() => undefined)
}
