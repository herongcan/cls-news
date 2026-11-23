# cls-news-web

财经快讯分析助手的前端。一套代码出 **H5** 和 **微信小程序**，跑在 uni-app + Vue 3 + TypeScript 上。

## 为什么是 uni-app

后端是 Spring Boot + SQLite，接口就 9 个，页面只有列表页一种形态。选型时真正起决定作用的不是框架热度，而是「同一套 UI 要同时出现在浏览器和微信里」：

- 纯 Vue3 SPA + 原生小程序：两套 UI 代码，改一次需求写两遍
- Taro（React）：能跨端，但项目已经用 Vue 写业务，切 React 没有收益
- uni-app：`script setup` / `computed` / Pinia 全是标准 Vue3，`view`/`text` 模板层两端都能编译

代价说清楚：模板层被抽象掉了 DOM，小程序端还有一堆 CSS 限制（不支持部分选择器、没有 `position: sticky` 的完整实现）。所以复杂交互要留 H5 专用分支，不要指望 100% 一套到底。

## 目录

```
src/
├── api/              接口层，纯 TS，不碰任何 DOM / uni 页面 API
│   ├── http.ts       uni.request 封装：自动带 Bearer、401 续期重放、错误归一
│   ├── credentials.ts 凭证存取（H5 落 localStorage，小程序落 Storage）
│   ├── news.ts       9 个接口里用到的 6 个
│   └── types.ts      与后端 NewsView / Stats 一一对应的类型
├── composables/
│   └── useNewsFeed.ts  列表状态机：首屏 / 翻页 / 搜索 / 分析 / 轮询
├── components/
│   └── NewsItem.vue    单条渲染：时间列、重要度、股票、分析结论
├── stores/
│   ├── auth.ts       角色（rw / ro / guest）、登录态、顶栏统计
│   └── theme.ts      三态主题（自动 / 浅色 / 深色）
├── pages/index/      唯一页面，登录层是页内浮层
├── utils/format.ts   时间、关键词切段高亮、涨跌文案
└── App.vue           设计令牌（CSS 变量）全挂在这里
```

## 本地开发

后端先起来（默认 8080），然后：

```bash
npm install
npm run dev:h5          # http://localhost:5173
```

dev 用 vite 的 proxy 把 `/api` 转发到 `127.0.0.1:8080`，**不需要给后端加 CORS**。生产是「前端产物打进 jar 的 static/」，本来同源，proxy 完全不参与。

## 构建与部署

```bash
npm run build:h5        # 产物在 dist/build/h5
npm run sync:jar        # 复制进 ../src/main/resources/static/
```

`sync:jar` 第一次覆盖时会把手写的老页面挪成 `static/legacy.html`，不直接删。

之后 `./mvnw package` 出来的 jar 里就带着新前端，部署链路（Cloudflare Tunnel → 容器 8080）不用改。

## 微信小程序

```bash
npm run build:mp        # 产物在 dist/build/mp-weixin
```

用微信开发者工具导入该目录即可（`manifest.json` 里的 `appid` 需要填自己的）。

**上线前必须先解决域名问题**：小程序要求 request 合法域名已备案、HTTPS、不能带端口。当前线上入口 `news.herongcan.icu` 走 Cloudflare Tunnel，域名未备案，只能在开发者工具里勾「不校验合法域名」自测，真机体验版会直接请求失败。

## 当前进度

已完成并实测（本地 8080 后端 + H5 构建产物）：

| 能力 | 说明 |
|---|---|
| 登录层 | 页内浮层收账号密码（移动端浏览器不会弹 Basic 原生框），底部保留 `guest / guest` 小字提示 |
| 顶栏 | `已收录 N 条 · 今日访客 X · 累计 Y · 今日调用 N/配额 · 抓取于 HH:mm`；访客只对 `rw/ro` 显示，配额用尽时数字转琥珀并提示「明天再试」 |
| 列表 | 首屏 10 条 + 触底翻页 + 下拉刷新 + 手动刷新，三种尾部状态（加载 / 到底 / 失败重试） |
| 实时增量 | 30 秒静默 `?since=<nextSince>` 拉新，只往头部插入，不打断滚动位置；`hasMore` 为真就按同一游标继续追（最多 5 轮），积压超过一页也不会丢条；切后台停轮询，回前台立刻补一次 |
| 搜索 | 320ms 防抖 + 请求序号哨兵（防响应乱序覆盖），关键词切段高亮，命中总数单独一行 |
| 分析 | 点击后乐观置 `analyzing`，后端异步时轮询 `/api/news/{id}` 直到出结论；失败落 `failed` 可重试 |
| 角色 | `guest` 不展示访客统计（后端本来就不下发）、配额 10 次/日；`ro` 不给「分析」按钮 |
| 访客上报 | 进入主界面（含登录成功后）POST 一次 `/api/visit/ping`，`vid` 存本地复用以区分同一出口 IP；不阻塞首屏、失败不重试 |

已知未做：

- 老页面还在 `src/main/resources/static/index.html`，`npm run sync:jar` 才会被替换成这里构建的产物（旧页面挪成 `legacy.html`）。**替换前需要确认**，因为那是当前线上跑的版本。
- 微信小程序的合法域名问题（见上一节），域名备案没解决之前只有 H5 能对外用。

## 几个刻意的取舍

- **hash 路由**：产物由 Spring Boot 静态托管，没有服务端 rewrite，`history` 模式一刷新就 404。hash 模式在 jar 里是零配置的。
- **不引 UI 组件库**：整页就一个列表 + 一个登录框，自己写 200 行 CSS 比塞一个 300KB 的组件库更快也更可控。
- **关键词高亮不用 `v-html`**：小程序里 `v-html` 只能落 `rich-text`，`<mark>` 不在支持列表内。改成把正文切成「命中 / 未命中」片段，模板渲染 `<text>`，两端一致且没有注入面。
- **本地只存令牌，不存密码**：登录时用 Basic 换一对令牌（`/api/auth/token`），之后每个请求只带 Bearer；access 30 分钟、refresh 7 天且一次性旋转，401 会自动续期并重放。后端仍保留 Basic 直连，给 `curl -u` 排查用。
