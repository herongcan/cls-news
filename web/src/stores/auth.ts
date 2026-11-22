import { defineStore } from 'pinia'
import { clearCred, readCred, writeCred } from '@/api/credentials'
import { ApiError } from '@/api/http'
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
     *   有凭证 → 带上去验一次，401 就把凭证扔掉回到登录层；
     *   没凭证 → 也打一次 /api/status，能通说明服务端没开鉴权（本地开发默认）。
     */
    async boot(): Promise<void> {
      const cred = readCred()
      this.user = cred ? cred.u : ''
      try {
        await this.sync()
      } catch (e) {
        if (!(e instanceof ApiError && e.status === 401)) throw e
        this.logged = false
      }
    },

    async sync(): Promise<void> {
      const st = await fetchStatus()
      this.role = st.role
      this.stats = st
      this.logged = true
      this.noAuth = !readCred()
    },

    async login(user: string, pass: string): Promise<void> {
      writeCred({ u: user, p: pass })
      this.user = user
      try {
        await this.sync()
      } catch (e) {
        clearCred()
        this.user = ''
        this.logged = false
        throw e
      }
    },

    logout(): void {
      clearCred()
      this.user = ''
      this.logged = false
      this.stats = null
    }
  }
})
