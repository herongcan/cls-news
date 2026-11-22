/**
 * 凭证存取。
 *
 * 后端是 Basic Auth + 内存账号表，没有 Session / Cookie，所以前端只能自己保管
 * user:pass。小程序没有 localStorage，统一走 uni.getStorageSync —— H5 下它落到
 * localStorage，两端行为一致。
 *
 * 明文存密码是这套方案的已知短板：换成 /api/auth/token + 短期票据才算解决，
 * 那需要后端配合，放在二期。
 */

const KEY = 'cls.cred'

export interface Cred {
  u: string
  p: string
}

const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'

/**
 * 手写 base64 而不是用 btoa：小程序环境没有 btoa，而 uni.arrayBufferToBase64
 * 是异步回调式 API，塞进请求头构造里很别扭。账号密码是 ASCII，不需要 UTF-8 处理。
 */
function base64(input: string): string {
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

export function basicHeader(cred: Cred): string {
  return 'Basic ' + base64(cred.u + ':' + cred.p)
}

export function readCred(): Cred | null {
  try {
    const raw = uni.getStorageSync(KEY)
    if (!raw) return null
    const c = JSON.parse(raw) as Cred
    return c && c.u ? c : null
  } catch (e) {
    return null
  }
}

export function writeCred(cred: Cred): void {
  uni.setStorageSync(KEY, JSON.stringify(cred))
}

export function clearCred(): void {
  uni.removeStorageSync(KEY)
}
