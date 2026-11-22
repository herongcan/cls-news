/**
 * 把 H5 构建产物同步进 Spring Boot 的 static/，让 jar 里直接带新前端。
 *
 * 为什么不做成 Maven 插件：前端构建依赖 node 环境，服务器只有 2 核 1.7G，
 * 在服务器上跑 npm 会被 OOM 杀掉；打包仍在 CI 的 runner 上做，这里只负责搬文件。
 *
 * 旧的手写页面不会丢：第一次覆盖前会挪成 legacy.html，方便对比或回滚。
 */
import { cp, mkdir, readdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises'
import { existsSync } from 'node:fs'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const src = resolve(here, '../dist/build/h5')
const target = resolve(here, '../../src/main/resources/static')

async function main() {
  if (!existsSync(src)) {
    console.error(`找不到构建产物：${src}\n先跑 npm run build:h5`)
    process.exit(1)
  }
  await mkdir(target, { recursive: true })

  const index = join(target, 'index.html')
  if (existsSync(index)) {
    const html = await readFile(index, 'utf8')
    // 带 <div id="app"> 的说明已经是 uni-app 产物，不用再备份
    if (!html.includes('id="app"')) {
      const legacy = join(target, 'legacy.html')
      if (!existsSync(legacy)) {
        await rename(index, legacy)
        console.log(`旧页面已保留为 static/legacy.html`)
      } else {
        await rm(index, { force: true })
      }
    }
  }

  for (const entry of await readdir(src)) {
    await cp(join(src, entry), join(target, entry), { recursive: true, force: true })
  }

  const files = await readdir(target)
  console.log(`已同步到 ${target}`)
  console.log(`  ${files.length} 个条目：${files.slice(0, 8).join(', ')}${files.length > 8 ? ' …' : ''}`)
}

main().catch((e) => {
  console.error(e)
  process.exit(1)
})
