<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { onAppHide, onAppShow, onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import NewsItem from '@/components/NewsItem.vue'
import AgentSheet from '@/components/AgentSheet.vue'
import { useNewsFeed } from '@/composables/useNewsFeed'
import { useAuth } from '@/stores/auth'
import { useTheme } from '@/stores/theme'
import { ApiError } from '@/api/http'
import { stampShort } from '@/utils/format'
import { pingVisit } from '@/utils/visit'

const auth = useAuth()
const theme = useTheme()
const {
  items,
  keyword,
  loading,
  error,
  notice,
  matched,
  state,
  reload,
  loadMore,
  search,
  reveal,
  analyze,
  isAnalyzing,
  start: startPolling,
  stop: stopPolling,
  setVisible
} = useNewsFeed()

const booting = ref(true)
const refreshing = ref(false)
const kwInput = ref('')
const form = ref({ u: '', p: '' })
const gateErr = ref('')
const gateBusy = ref(false)
/**
 * 会话中途失效时的重登浮层。
 *
 * <p>和启动登录层（form）分开两套状态：那边的密码输到一半、退出后不该带到重登框；
 * 反过来也一样。共用一套的话，退出再登入时会看到一个残留的密码。
 */
const reForm = ref({ u: '', p: '' })
const reErr = ref('')
const reBusy = ref(false)
let debounce: ReturnType<typeof setTimeout> | null = null

/** 主线浮层的开关。浮层自己管加载，这里只管弹不弹 */
const sheetOpen = ref(false)
/**
 * 从浮层点引用跳过来的那一条的 id。
 *
 * <p>用"短暂高亮 + 自动消失"而不是长期标记：列表是持续增量的，一条一直发光的行
 * 在 30 秒后会被当成"未读"，而它只是你刚才点过的引用。
 */
const highlightId = ref(0)
let highlightTimer: ReturnType<typeof setTimeout> | null = null

const stats = computed(() => auth.stats)

/** 数据源标签只取第一段：完整值是「cls.cn /v1/roll/get_roll_list」，窄屏会把标题挤没 */
const srcShort = computed(() => (stats.value?.source || 'cls.cn').split(/\s+/)[0])
const srcFull = computed(() => stats.value?.source || 'cls.cn')

/** 抓取正常 = 绿；有过错误 = 红；还没跑过 = 琥珀 */
const dotClass = computed(() => {
  const s = stats.value
  if (!s) return ''
  if (s.lastError) return 'err'
  return s.lastRunAt ? 'ok' : 'warn'
})

const lastText = computed(() =>
  stats.value?.lastRunAt ? '抓取于 ' + stampShort(stats.value.lastRunAt) : '等待首次抓取'
)

/** 配额到顶时把数字标黄并说明原因：不解释的话，点「分析」只会收到一条 429 红字 */
const callTitle = computed(() =>
  stats.value?.analysisCapped
    ? '今日分析已达上限，明天再试'
    : stats.value?.analysisAuto
      ? '后台自动分析'
      : '手动点击分析'
)

const moreText = computed(() => {
  switch (state.value) {
    case 'loading':
      return '加载中…'
    case 'more':
      return '加载更早的记录…'
    case 'err':
      return error.value + '，点一下重试'
    case 'empty':
      return keyword.value ? '没有匹配「' + keyword.value + '」的记录' : '正在抓取首批数据…'
    case 'end':
      return keyword.value
        ? '已显示全部 ' + items.value.length + ' 条匹配'
        : '已经到底了 · 共 ' + items.value.length + ' 条'
    default:
      return keyword.value ? '向下滚动查看更多匹配' : '向下滚动加载更早的记录'
  }
})

onMounted(async () => {
  auth.watchSession()
  try {
    await auth.boot()
  } catch (e) {
    gateErr.value = e instanceof ApiError ? e.message : '连不上服务器'
  } finally {
    booting.value = false
  }
  if (auth.logged) {
    pingVisit()
    // 直达链接：把 /#brief 发给同事，打开就是今日主线，不用先找右下角那个入口。
    // 单向的（关掉浮层不改 hash）：双向改 hash 会在每次开关都往历史里塞一条，
    // 手机上「返回」变成关浮层，而不是退出页面。
    // #ifdef H5
    if (location.hash === '#brief') sheetOpen.value = true
    // #endif
    await reload()
    startPolling()
  }
})

/**
 * 浮层弹出时预填账号、清空密码。
 *
 * <p>密码不预填是底线：它从来就没落过本地（这是当初换掉「每次请求带 Basic」的全部理由），
 * 也就不存在「方便地自动重登」—— 会话过期就是得再输一次。
 */
watch(
  () => auth.needLogin,
  (v) => {
    if (!v) return
    reForm.value = { u: auth.lastUser || auth.user, p: '' }
    reErr.value = ''
  }
)

// 切后台时停掉轮询：手机上一直发请求既费电，也没人看
onAppHide(() => setVisible(false))
onAppShow(() => setVisible(true))

onUnmounted(() => {
  stopPolling()
  if (debounce) clearTimeout(debounce)
  if (highlightTimer) clearTimeout(highlightTimer)
})

onReachBottom(() => {
  void loadMore()
})

onPullDownRefresh(async () => {
  await reload()
  uni.stopPullDownRefresh()
})

async function submitLogin(): Promise<void> {
  const u = form.value.u.trim()
  const p = form.value.p
  if (!u) {
    gateErr.value = '请输入账号'
    return
  }
  gateBusy.value = true
  gateErr.value = ''
  try {
    await auth.login(u, p)
    form.value.p = ''
    pingVisit()
    await reload()
    startPolling()
  } catch (e) {
    gateErr.value = e instanceof ApiError ? e.message : '登录失败'
  } finally {
    gateBusy.value = false
  }
}

function doLogout(): void {
  stopPolling()
  auth.logout()
  items.value = []
  kwInput.value = ''
}

/**
 * 原地重登。
 *
 * 成功后**不重新加载列表**：断掉的那些请求在 http 层挂着，登录一成功就会带着新令牌
 * 自己重放，拿回来的正是断点处的数据。这里再 reload 一次，反而会把 items 整个替换掉、
 * 滚动位置冲回顶部 —— 那正是挑「浮层」而不是「切回全屏登录页」想避免的事。
 */
async function submitRelogin(): Promise<void> {
  const u = reForm.value.u.trim()
  if (!u) {
    reErr.value = '请输入账号'
    return
  }
  reBusy.value = true
  reErr.value = ''
  try {
    await auth.relogin(u, reForm.value.p)
    reForm.value.p = ''
  } catch (e) {
    reErr.value = e instanceof ApiError ? e.message : '登录失败'
  } finally {
    reBusy.value = false
  }
}

/**
 * 不登了，回全屏登录层。
 *
 * 挂起的请求必须一并放掉（quitRelogin 里做），否则它们要悬满两分钟才超时，
 * 期间每次轮询都会再挂一个上去。
 */
function quitToGate(): void {
  stopPolling()
  auth.quitRelogin()
  items.value = []
  kwInput.value = ''
}

/** 搜索防抖：连打时只发最后一次，配合 composable 里的请求哨兵，响应乱序也不会错位 */
function onKwInput(): void {
  if (debounce) clearTimeout(debounce)
  debounce = setTimeout(() => {
    void search(kwInput.value)
  }, 320)
}

function onConfirm(): void {
  if (debounce) clearTimeout(debounce)
  void search(kwInput.value)
}

function clearSearch(): void {
  if (debounce) clearTimeout(debounce)
  kwInput.value = ''
  void search('')
}

async function manualRefresh(): Promise<void> {
  if (refreshing.value) return
  refreshing.value = true
  try {
    await reload()
  } finally {
    // 留一点时长让箭头转完一圈，否则快的时候看起来像没反应
    setTimeout(() => (refreshing.value = false), 500)
  }
}

function onMoreTap(): void {
  if (state.value === 'err') {
    if (items.value.length) void loadMore()
    else void reload()
    return
  }
  if (state.value === 'idle') void loadMore()
}

/** 量不到顶栏高度时的兜底：按 390px 屏的实测值给 */
const TOP_MIN = 84

/** 两条矩形，只取用得到的字段 */
type Rect = { top: number; height: number; bottom: number }

/**
 * 把某一条滚到视口中间。
 *
 * <p>原来传的是固定 `offsetTop: 96` —— 那是「离顶 96px」，不是居中；而且顶栏高度
 * 会随「提示条 / 搜索命中数」出现而变化，96 在窄屏上会把卡片标题压在栏底下。
 *
 * <p>现在先量两张矩形再算：卡片高度决定居中位置，顶栏高度决定下限。
 * 卡片比视口还高时（带分析结论的长卡片）居中会把标题推到栏下面，
 * 所以取两者较大值 —— 先保证看得见标题，再谈居中。
 */
async function scrollToCentered(id: number): Promise<void> {
  await nextTick()
  const { box, hd } = await new Promise<{ box?: Rect; hd?: Rect }>((resolve) => {
    uni
      .createSelectorQuery()
      .select('#news-' + id)
      .boundingClientRect()
      .select('.hd')
      .boundingClientRect()
      .exec((res) => {
        const arr = (res || []) as Array<Rect | null>
        resolve({ box: arr[0] || undefined, hd: arr[1] || undefined })
      })
  })
  const winH = uni.getSystemInfoSync().windowHeight || 0
  // 量不到就退回贴顶：首次渲染没完成时 boundingClientRect 会给 null，
  // 这时候硬算居中会滚到一个凭空的数上，比贴着顶栏差
  if (!box || !box.height || !winH) {
    uni.pageScrollTo({ selector: '#news-' + id, offsetTop: TOP_MIN, duration: 240 })
    return
  }
  const cover = hd && hd.height ? Math.round(hd.height) + 8 : TOP_MIN
  const centered = Math.round((winH - box.height) / 2)
  uni.pageScrollTo({
    selector: '#news-' + id,
    offsetTop: Math.max(cover, centered),
    duration: 240
  })
}

/**
 * 从主线浮层点某条引用：滚到那一条、居中、高亮。
 *
 * <p>引用是 id，不是下标 —— 列表 30 秒会自动插新条目，用下标存位置一定会错位。
 *
 * <p>原来「不在当前列表就只给一句提示」。但列表首屏只加载 10 条，引用指向
 * 几小时前的新闻时几乎必然不在 DOM 里 —— 用户点了引用什么都没发生，
 * 只能自己去搜标题。现在按 id 单独取回那一条补进列表再滚（`reveal`），
 * 只有原文真的不存在（404）才提示。
 */
async function jumpToNews(id: number): Promise<void> {
  sheetOpen.value = false
  const where = await reveal(id)
  if (where === 'missing') {
    notice.value = '这条引用打不开了，原文可能已被删除'
    return
  }
  highlightId.value = id
  if (highlightTimer) clearTimeout(highlightTimer)
  highlightTimer = setTimeout(() => (highlightId.value = 0), 2600)
  await scrollToCentered(id)
}

function dismissNotice(): void {
  notice.value = ''
}
</script>

<template>
  <view class="app" :class="theme.resolved === 'light' ? 'theme-light' : ''">
    <view v-if="booting" class="boot">正在连接…</view>

    <!-- 登录层：移动端浏览器不弹 Basic 原生框，凭证只能由页面自己收 -->
    <view v-else-if="!auth.logged" class="gate">
      <view class="gate-form">
        <view class="gate-title">实时资讯助手</view>
        <view class="gate-sub">内部工具 · 请输入账号密码</view>
        <input v-model="form.u" class="inp" placeholder="账号" autocomplete="username" />
        <input
          v-model="form.p"
          class="inp"
          password
          placeholder="密码"
          autocomplete="current-password"
          @confirm="submitLogin"
        />
        <view class="gate-btn" :class="{ busy: gateBusy }" @tap="submitLogin">
          {{ gateBusy ? '验证中…' : '进入' }}
        </view>
        <view class="gate-err">{{ gateErr }}</view>
        <view class="gate-guest">
          游客账号 <text class="mono">guest</text> / <text class="mono">guest</text>
        </view>
      </view>
    </view>

    <template v-else>
      <view class="hd">
        <!-- 两行固定结构：账号相关的放上行，数据相关的放下行。
             混在一个 flex-wrap 里，窄屏会按字数随机折行，「退出」曾经被单独挤到第三行。 -->
        <view class="row">
          <view class="brand">
            <text class="dot" :class="dotClass" />
            <text class="h1">实时资讯助手</text>
            <text class="src" :title="srcFull">{{ srcShort }}</text>
          </view>
          <view class="acts">
            <text v-if="auth.badge" class="badge" :class="auth.isGuest ? 'guest' : 'ro'">{{ auth.badge }}</text>
            <text v-if="auth.noAuth" class="badge dev">本地模式 · 无鉴权</text>
            <text v-else class="btn" @tap="doLogout">退出</text>
          </view>
        </view>

        <view class="row sub">
          <view class="meta">
            <text class="mi">已收录 <text class="b">{{ stats?.total ?? 0 }}</text> 条</text>
            <text v-if="auth.showVisits" class="mi" title="按 IP 去重，同一 IP 一天只算一个访客">
              今日访客 <text class="b">{{ stats?.visitToday ?? 0 }}</text> · 累计
              <text class="b">{{ stats?.visitTotal ?? 0 }}</text>
            </text>
            <text
              v-if="stats?.analyzedToday !== undefined"
              class="mi"
              :class="{ capped: stats?.analysisCapped }"
              :title="callTitle"
            >今日调用 <text class="b">{{ stats?.analyzedToday ?? 0 }}</text><text
              v-if="stats?.analysisCap !== undefined"
              class="cap"
            >/{{ stats.analysisCap }}</text></text>
            <text class="mi">{{ lastText }}</text>
          </view>
          <view class="acts">
            <text class="btn" :title="'主题：' + theme.label" @tap="theme.cycle()">{{ theme.icon }}</text>
            <text class="btn" :class="{ rotating: refreshing }" title="立即刷新" @tap="manualRefresh">↻</text>
          </view>
        </view>
      </view>

      <view class="searchbar">
        <input
          v-model="kwInput"
          class="q"
          placeholder="搜索标题或正文…"
          confirm-type="search"
          @input="onKwInput"
          @confirm="onConfirm"
        />
        <text v-if="kwInput" class="qclear" @tap="clearSearch">清除</text>
      </view>

      <view v-if="keyword && matched" class="hitinfo">找到 {{ matched }} 条匹配「{{ keyword }}」</view>

      <view v-if="notice" class="notice" @tap="dismissNotice">{{ notice }}</view>

      <view class="list">
        <view v-for="n in items" :id="'news-' + n.id" :key="n.id">
          <NewsItem
            :item="n"
            :keyword="keyword"
            :can-analyze="auth.canAnalyze"
            :analyzing="isAnalyzing(n.id)"
            :highlight="highlightId === n.id"
            @analyze="analyze(n.id)"
          />
        </view>
      </view>

      <view class="more" :class="state" @tap="onMoreTap">{{ moreText }}</view>

      <!-- 主线入口。浮层打开时只打免费报价，点了才花 token -->
      <view class="fab" @tap="sheetOpen = true">
        <text class="fab-dot" />
        <text>今日主线</text>
      </view>

      <AgentSheet :open="sheetOpen" :can-run="auth.canAnalyze" @close="sheetOpen = false" @pick="jumpToNews" />

      <!--
        会话中途失效的浮层。
        和启动登录层的区别：列表还在下面，滚动位置没动。登录成功后 http 层
        会把断掉的请求重放，页面不需要 reload。
      -->
      <view v-if="auth.needLogin" class="relogin">
        <view class="relogin-card">
          <view class="gate-title">登录已过期</view>
          <view class="gate-sub">重新登录后，列表会停在原处自动续上</view>
          <input v-model="reForm.u" class="inp" placeholder="账号" autocomplete="username" />
          <input
            v-model="reForm.p"
            class="inp"
            password
            placeholder="密码"
            autocomplete="current-password"
            @confirm="submitRelogin"
          />
          <view class="gate-btn" :class="{ busy: reBusy }" @tap="submitRelogin">
            {{ reBusy ? '验证中…' : '重新进入' }}
          </view>
          <view class="gate-err">{{ reErr }}</view>
          <view class="gate-quit" @tap="quitToGate">退出登录</view>
        </view>
      </view>
    </template>
  </view>
</template>

<style scoped>
.app {
  min-height: 100vh;
  background: var(--bg);
  color: var(--text);
  /* 底部留出悬浮入口的高度，否则「已经到底了」会被按钮盖掉半行 */
  padding-bottom: 78px;
}

.boot {
  padding: 80px 20px;
  text-align: center;
  color: var(--dim);
  font-size: 13px;
}

/* ---------- 顶栏 ---------- */
.hd {
  position: sticky;
  top: 0;
  z-index: 9;
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 20px 10px;
  background: var(--bg);
  border-bottom: 0.5px solid var(--line);
}
.row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-width: 0;
  /* 宽屏上限：正文 14px 一行拉满 1200px 会读丢行，旧页面也是 920 */
  max-width: 860px;
  width: 100%;
  margin: 0 auto;
}
.row.sub {
  gap: 12px;
}
.acts {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: none;
}
.brand {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--dim);
  flex: none;
}
.dot.ok {
  background: var(--down);
  animation: pulse 2.4s infinite;
}
.dot.warn {
  background: var(--amber);
}
.dot.err {
  background: var(--up);
}
@keyframes pulse {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}
.h1 {
  font-size: 15px;
  font-weight: 500;
  letter-spacing: 0.2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.src {
  font-size: 12px;
  color: var(--dim);
  border: 0.5px solid var(--line-2);
  border-radius: 4px;
  padding: 1px 6px;
}

.meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  /* 分隔符不用「·」字符而用间距：折行时行尾不会剩一个孤零零的点 */
  gap: 4px 12px;
  font-size: 12px;
  color: var(--muted);
  min-width: 0;
}
.mi {
  white-space: nowrap;
}
.b {
  color: var(--text);
  font-weight: 500;
}
.cap {
  color: var(--dim);
}
/* 配额用尽：数字整段转琥珀，和「游客」徽标同一套语义色 */
.capped,
.capped .b,
.capped .cap {
  color: var(--amber);
}
.btn {
  font-size: 12px;
  color: var(--muted);
  border: 0.5px solid var(--line-2);
  border-radius: 6px;
  padding: 2px 9px;
  cursor: pointer;
}
.btn:hover {
  color: var(--text);
  border-color: var(--line-2);
}
.rotating {
  display: inline-block;
  animation: spin 0.8s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
/* 前庭功能敏感的用户看持续旋转会难受 */
@media (prefers-reduced-motion: reduce) {
  .rotating {
    animation: none;
  }
  .dot.ok {
    animation: none;
  }
}
.badge {
  font-size: 12px;
  border-radius: 4px;
  padding: 1px 6px;
}
.badge.guest {
  color: var(--amber);
  border: 0.5px solid var(--amber-line);
}
.badge.ro {
  color: var(--dim);
  border: 0.5px solid var(--line-2);
}
.badge.dev {
  color: var(--amber);
  border: 0.5px solid var(--amber-line);
}

/* ---------- 搜索 ---------- */
.searchbar {
  display: flex;
  gap: 8px;
  padding: 12px 20px;
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}
.q {
  flex: 1;
  min-width: 0;
  background: var(--panel);
  border: 0.5px solid var(--line);
  border-radius: 8px;
  color: var(--text);
  font-size: 14px;
  padding: 8px 12px;
  height: 36px;
}
.qclear {
  font-size: 12px;
  color: var(--muted);
  border: 0.5px solid var(--line-2);
  border-radius: 8px;
  padding: 0 12px;
  line-height: 34px;
  cursor: pointer;
}

.hitinfo {
  padding: 0 20px 8px;
  font-size: 12px;
  color: var(--dim);
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}

.notice {
  margin: 0 auto 8px;
  max-width: 860px;
  box-sizing: border-box;
  padding: 6px 10px;
  font-size: 12px;
  color: var(--up);
  border: 0.5px solid var(--up-line-weak);
  border-radius: 6px;
  cursor: pointer;
}

/* ---------- 列表 ---------- */
.list {
  padding: 0 20px;
  max-width: 900px;
  width: 100%;
  margin: 0 auto;
  box-sizing: border-box;
}
.more {
  padding: 22px 20px;
  text-align: center;
  font-size: 12px;
  color: var(--dim);
  min-height: 30px;
  max-width: 900px;
  margin: 0 auto;
  box-sizing: border-box;
}
.more.more {
  color: var(--muted);
}
.more.err {
  color: var(--up);
  cursor: pointer;
}
.more.end::before {
  content: '';
  display: block;
  width: 40px;
  height: 0.5px;
  background: var(--line-2);
  margin: 0 auto 14px;
}
.more.idle {
  color: var(--muted);
  text-decoration: underline;
  text-underline-offset: 4px;
  cursor: pointer;
}

/* ---------- 主线入口 ---------- */
/* 悬浮而不是放进顶栏：顶栏那两行已经排满（账号 / 数据），再塞一个入口会把
   「退出」挤到第三行 —— 那个坑以前踩过（见 hd 上的注释）。 */
.fab {
  position: fixed;
  right: 16px;
  bottom: calc(18px + env(safe-area-inset-bottom));
  z-index: 8;
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--text);
  background: var(--panel);
  border: 0.5px solid var(--line-2);
  border-radius: 20px;
  padding: 8px 14px;
  box-shadow: 0 6px 20px rgba(0, 0, 0, 0.28);
  cursor: pointer;
}
.fab-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--amber);
}

/* ---------- 登录层 ---------- */
.gate {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.gate-form {
  width: 100%;
  max-width: 300px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.gate-title {
  font-size: 18px;
  font-weight: 500;
  letter-spacing: 0.3px;
}
.gate-sub {
  font-size: 12px;
  color: var(--dim);
  margin-bottom: 10px;
}
/* 16px 是硬要求：iOS Safari 遇到小于 16px 的输入框会自动放大整页且不缩回 */
.inp {
  background: var(--panel);
  border: 0.5px solid var(--line-2);
  border-radius: 8px;
  color: var(--text);
  font-size: 16px;
  padding: 10px 12px;
  height: 44px;
}
.gate-btn {
  margin-top: 4px;
  text-align: center;
  background: var(--text);
  color: var(--on-accent);
  font-size: 15px;
  font-weight: 500;
  border-radius: 8px;
  padding: 11px;
  cursor: pointer;
}
.gate-btn.busy {
  opacity: 0.55;
  cursor: default;
}
.gate-err {
  min-height: 18px;
  text-align: center;
  font-size: 12px;
  color: var(--up);
}
.gate-guest {
  text-align: center;
  font-size: 11px;
  color: var(--dim);
  border-top: 0.5px solid var(--line);
  padding-top: 10px;
}

/* ---------- 会话失效浮层 ---------- */
/* 盖在原页面上，不用 v-if 换掉整棵列表：换掉就等于把滚动位置也扔了。
   背景压暗而不是全遮，是为了让人看得出来「东西还在下面」。 */
.relogin {
  position: fixed;
  inset: 0;
  z-index: 90;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(10, 11, 13, 0.72);
  -webkit-backdrop-filter: blur(8px);
  backdrop-filter: blur(8px);
}
.theme-light .relogin {
  background: rgba(250, 249, 246, 0.78);
}
.relogin-card {
  width: 100%;
  max-width: 300px;
  display: flex;
  flex-direction: column;
  gap: 10px;
  background: var(--panel);
  border: 0.5px solid var(--line-2);
  border-radius: 14px;
  padding: 20px;
  box-shadow: 0 18px 48px rgba(0, 0, 0, 0.32);
}
.gate-quit {
  margin-top: 2px;
  padding: 6px;
  text-align: center;
  font-size: 12px;
  color: var(--dim);
  border-top: 0.5px solid var(--line);
  cursor: pointer;
}
.mono {
  color: var(--muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
}
</style>
