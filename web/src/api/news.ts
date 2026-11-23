import { request } from './http'
import type { AnalyzeResponse, NewsItem, NewsPage, StatusBody } from './types'

/**
 * 顶栏的统计 + 当前角色。
 *
 * @param waitLogin 会话失效时是否挂起等重登。启动校验（boot）要传 false：
 *   那时页面还停在「正在连接…」，登录层没渲染，挂住就是白等两分钟。
 */
export function fetchStatus(waitLogin = true): Promise<StatusBody> {
  return request<StatusBody>('/api/status', { waitLogin })
}

/** 首屏：按发布时间倒序 */
export function fetchLatest(limit = 10): Promise<NewsPage> {
  return request<NewsPage>('/api/news', { query: { limit } })
}

/** 向下翻历史：id 更小的条目 */
export function fetchBefore(before: number, limit = 10): Promise<NewsPage> {
  return request<NewsPage>('/api/news', { query: { limit, before } })
}

/**
 * 增量：id 更大的新条目。`since` 传上一次响应里的 `nextSince`。
 *
 * 后端一次最多回 limit 条，`hasMore` 为真说明还有积压；调用方要按
 * `nextSince` 继续追，**不能**用 `maxId` 直接跳到底 —— 那会把没拿到的那段永久跳过。
 */
export function fetchNewer(since: number, limit = 50): Promise<NewsPage> {
  return request<NewsPage>('/api/news', { query: { limit, since } })
}

export function searchNews(q: string, before = 0, limit = 10): Promise<NewsPage> {
  return request<NewsPage>('/api/news/search', { query: { q, limit, before } })
}

export function fetchOne(id: number): Promise<NewsItem> {
  return request<NewsItem>('/api/news/' + id)
}

/**
 * 触发分析。超时给到 2 分钟：后端是同步等模型的，默认 15 秒会在慢响应上误判失败。
 * 返回整条 NewsView，调用方直接替换列表里那一行，不用再拼一份平行结构。
 */
export function analyzeNews(id: number): Promise<AnalyzeResponse> {
  return request<AnalyzeResponse>('/api/news/' + id + '/analyze', {
    method: 'POST',
    timeout: 120000
  })
}
