/** 在无内部令牌的短生命周期进程中探测一个 n8n 插件包。 */

import { PackageStore } from './package-store.mjs'

/** 读取后端传入的有限 JSON 请求。 */
async function readInput() {
  let value = ''
  for await (const chunk of process.stdin) value += chunk
  return JSON.parse(value)
}

try {
  const result = await new PackageStore().install(await readInput())
  process.stdout.write(JSON.stringify(result))
} catch (error) {
  process.stdout.write(JSON.stringify({ error: String(error?.message || error).slice(0, 500) }))
  process.exitCode = 1
}
