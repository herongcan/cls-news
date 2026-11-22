import { defineStore } from 'pinia'

export type ThemeMode = 'auto' | 'light' | 'dark'

const KEY = 'cls.theme'

/**
 * 三态主题：自动 / 浅色 / 深色。存的是用户的选择，auto 在渲染时才解析成系统值，
 * 这样系统主题变了不用重新读写存储。
 */
export const useTheme = defineStore('theme', {
  state: () => ({
    mode: 'auto' as ThemeMode,
    systemDark: true
  }),

  getters: {
    resolved: (s): 'light' | 'dark' =>
      s.mode === 'auto' ? (s.systemDark ? 'dark' : 'light') : s.mode,
    label: (s): string => (s.mode === 'auto' ? '自动' : s.mode === 'light' ? '浅色' : '深色'),
    icon: (s): string => (s.mode === 'auto' ? '◐' : s.mode === 'light' ? '☀' : '☾')
  },

  actions: {
    init(): void {
      try {
        const saved = uni.getStorageSync(KEY)
        if (saved === 'auto' || saved === 'light' || saved === 'dark') this.mode = saved
      } catch (e) {
        /* 无痕模式读不到存储，按 auto 走 */
      }
      this.watchSystem()
    },

    watchSystem(): void {
      // #ifdef H5
      try {
        const mq = window.matchMedia('(prefers-color-scheme: dark)')
        this.systemDark = mq.matches
        mq.addEventListener('change', (e) => {
          this.systemDark = e.matches
        })
        return
      } catch (e) {
        /* 老浏览器没有 matchMedia，保持默认深色 */
      }
      // #endif

      // #ifndef H5
      try {
        this.systemDark = uni.getSystemInfoSync().theme !== 'light'
      } catch (e) {
        /* 拿不到就按深色 */
      }
      // #endif
    },

    cycle(): void {
      const order: ThemeMode[] = ['auto', 'light', 'dark']
      const next = order[(order.indexOf(this.mode) + 1) % order.length]
      this.mode = next
      try {
        uni.setStorageSync(KEY, next)
      } catch (e) {
        /* 存不了就只在本次会话生效 */
      }
    }
  }
})
