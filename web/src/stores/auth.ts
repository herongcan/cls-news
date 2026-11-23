import { defineStore } from 'pinia'
import {
  clearLegacyBasic,
  clearTokens,
  readLastUser,
  readLegacyBasic,
  readTokens,
  rememberUser,
  writeTokens
} from '@/api/credentials'
import {
  abortWaitingLogin,
  ApiError,
  requestToken,
  resumeAfterLogin,
  revokeSession,
  setSessionLostHandler
} from '@/api/http'
import { fetchStatus } from '@/api/news'
import type { Role, Stats } from '@/api/types'

interface AuthState {
  user: string
  logged: boolean
  /** 服务端 app.security.enabled=false 时不带凭证也能拿到数据，界面上要显性标出来 */
  noAuth: boolean
  role: Role
  stats: Stats | null
  /**
   * 会话中途失效（refresh 也换不回来）。
   *
   * <p>和 {@code logged=false} 的区别是「盖在哪里」：这个为真时页面不切走，
   * 只在上面盖一层重登浮层，列表和滚动位置都留着。启动时的 401 仍然走全屏登录层。
   */
  needLogin: boolean
  /** 上次登录用的账号，重登框预填。只存账号，不存密码。 */
  lastUser: string
}

export const useAuth = defineStore('auth', {
  state: (): AuthState => ({
    user: '',
    logged: false,
    noAuth: false,
    role: 'rw',
    stats: null,
    needLogin: false,
    lastUser: readLastUser()
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
     * 页面挂载时调一次：把「会话中途失效」接进 store。
     *
     * <p>为什么用回调而不是让 http 直接改 store：http 是纯传输层，
     * 它不该知道 Pinia 的存在，否则会绕出一个循环依赖（store 又要用 http）。
     */
    watchSession(): void {
      setSessionLostHandler(() => {
        this.needLogin = true
        this.lastUser = this.lastUser || this.user
      })
    },

    /**
     * 启动时判定走哪条路：
     *   本地有旧版 {u,p} → 先换一次令牌（老用户升级后无感，不用重输密码）；
     *   有令牌 → 带上去验一次，401 且刷新也失败就把本地凭证扔掉回到登录层；
     *   都没有 → 也打一次 /api/status，能通说明服务端没开鉴权（本地开发默认）。
     *
     * <p>{@code sync(false)}：启动阶段不挂起。此时界面还停在「正在连接…」，
     * 重登浮层挂在下面根本看不见，等两分钟等于卡死。
     */
    async boot(): Promise<void> {
      await this.migrateLegacy()
      const t = readTokens()
      this.user = t?.user ?? ''
      try {
        await this.sync(false)
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 401)) throw e
        clearTokens()
        this.user = ''
        this.logged = false
        return
      }
      if (this.user) {
        rememberUser(this.user)
        this.lastUser = this.user
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

    /**
     * @param waitLogin 会话失效时是否挂起等重登，见 {@link fetchStatus}
     */
    async sync(waitLogin = true): Promise<void> {
      const st = await fetchStatus(waitLogin)
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
      this.lastUser = t.user
      rememberUser(t.user)
      try {
        // sync(false)：这里刚换到令牌，验一次只为拿 role/stats。
        // 不能挂起 —— 万一这条也 401，默认的 waitLogin 会让人对着
        // 「验证中…」干等两分钟，看着像登录按钮坏了。
        await this.sync(false)
      } catch (e) {
        clearTokens()
        this.user = ''
        this.logged = false
        throw e
      }
    },

    /**
     * 会话中途失效后的原地重登。
     *
     * <p>和 {@link login} 的区别只有两处：成功后额外唤醒 http 层挂着的请求
     * （它们在等这一下，醒来会带着新令牌重放），以及不重新加载列表 ——
     * 重放拿回的数据就是断点处该有的数据，重新 load 反而会把滚动位置冲回顶部。
     *
     * <p>失败时保留本地状态（不 clearTokens）：登录框还开着，用户可能只是打错一个字，
     * 让他改完再试；此时本地本来也没有可用令牌。
     */
    async relogin(user: string, pass: string): Promise<void> {
      const t = await requestToken(user, pass)
      writeTokens(t)
      this.user = t.user
      this.lastUser = t.user
      rememberUser(t.user)
      // 同上：验证失败要立刻报错让人改，不能挂在那里等 120 秒
      await this.sync(false)
      this.needLogin = false
      resumeAfterLogin()
    },

    /**
     * 用户在重登浮层上点「退出登录」。
     *
     * 必须先把挂起的请求放掉（按 401 失败），否则它们要悬满两分钟；
     * 再清本地状态回到全屏登录层。
     */
    quitRelogin(): void {
      this.needLogin = false
      abortWaitingLogin()
      this.logout()
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
      this.needLogin = false
      this.user = ''
      this.logged = false
      this.stats = null
    }
  }
})
