import { request } from './http'
import type { AnalyzeResponse, NewsItem, NewsPage, StatusBody } from './types'

export function fetchStatus(): Promise<StatusBody> {
  return request<StatusBody>('/api/status')
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
 * 增量：id 更大的新条目（since = 当前已展示的最大 id）。
 * 后端遇到 since 不分页，一次把积压的都返回，所以这里给足 limit。
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
