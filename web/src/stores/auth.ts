import { defineStore } from 'pinia'
import {
  clearLegacyBasic,
  clearTokens,
  readLegacyBasic,
  readTokens,
  writeTokens
} from '@/api/credentials'
import { ApiError, requestToken, revokeSession } from '@/api/http'
import { fetchStatus } from '@/api/news'
import type { Role, Stats } from '@/api/types'

interface AuthState {
  user: string
  logged: boolean
  /** 服务端 app.security.enabled=false 时不带凭证也能拿到数据，界面上要显性标出来 */
  noAuth: boolean
  role: Role
  stats: Stats | null
}

export const useAuth = defineStore('auth', {
  state: (): AuthState => ({
    user: '',
    logged: false,
    noAuth: false,
    role: 'rw',
    stats: null
  }),

  getters: {
    /** 只读账号一律不给「分析」按钮：留着再让它 403，人会以为是坏了 */
    canAnalyze: (s) => s.role !== 'ro',
    /** 游客连聚合的访客数都看不到 */
    showVisits: (s) => s.role !== 'guest',
    isGuest: (s) => s.role === 'guest',
    badge: (s) => (s.role === 'guest' ? '游客' : s.role === 'ro' ? '只读' : '')
  },

  actions: {
    /**
     * 启动时判定走哪条路：
     *   本地有旧版 {u,p} → 先换一次令牌（老用户升级后无感，不用重输密码）；
     *   有令牌 → 带上去验一次，401 且刷新也失败就把本地凭证扔掉回到登录层；
     *   都没有 → 也打一次 /api/status，能通说明服务端没开鉴权（本地开发默认）。
     */
    async boot(): Promise<void> {
      await this.migrateLegacy()
      const t = readTokens()
      this.user = t?.user ?? ''
      try {
        await this.sync()
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 401)) throw e
        clearTokens()
        this.user = ''
        this.logged = false
      }
    },

    /**
     * 把旧版明文密码换成令牌，然后删掉密码。
     *
     * 只在升级后的第一次进页面发生一次。失败说明密码已经被改过或账号被移除，
     * 清掉旧键让它正常走登录 —— 不能因为迁移失败就把人卡在「一直 401」上。
     */
    async migrateLegacy(): Promise<void> {
      const legacy = readLegacyBasic()
      if (!legacy) return
      if (readTokens()) {
        // 已经有令牌了，旧键只是上次没清干净的残留
        clearLegacyBasic()
        return
      }
      try {
        const t = await requestToken(legacy.u, legacy.p)
        writeTokens(t)
        this.user = t.user
      } catch (e) {
        // 密码错了就算了，下面 sync 会 401，界面回到登录层
      } finally {
        clearLegacyBasic()
      }
    },

    async sync(): Promise<void> {
      const st = await fetchStatus()
      this.role = st.role
      this.stats = st
      this.logged = true
      // 能拿到数据但本地没有任何令牌 = 服务端没开鉴权
      this.noAuth = !readTokens()
    },

    /**
     * 登录 = 用 Basic 换一对令牌，然后立刻验一次。
     *
     * 换令牌失败时不会写任何本地状态，所以界面上就是「密码不对」，
     * 不会出现「看起来登进去了但每个请求都 401」的中间态。
     */
    async login(user: string, pass: string): Promise<void> {
      const t = await requestToken(user, pass)
      writeTokens(t)
      this.user = t.user
      try {
        await this.sync()
      } catch (e) {
        clearTokens()
        this.user = ''
        this.logged = false
        throw e
      }
    },

    /**
     * 登出：通知服务端作废 refresh，然后清本地。
     *
     * 服务端调用是 fire-and-forget —— 网络不通也必须能登出，
     * 否则用户在断网状态下点「退出」毫无反应。
     */
    logout(): void {
      const t = readTokens()
      if (t) {
        revokeSession(t.refreshToken).catch(() => undefined)
      }
      clearTokens()
      clearLegacyBasic()
      this.user = ''
      this.logged = false
      this.stats = null
    }
  }
})
