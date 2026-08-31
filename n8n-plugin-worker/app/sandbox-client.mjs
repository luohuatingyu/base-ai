/** 通过来源专用 Unix Socket 调用插件 Docker 沙箱 Broker。 */

import { request as httpRequest } from 'node:http'

export class SandboxError extends Error {
  /** 保存有限状态码和稳定错误码。 */
  constructor(status, code) {
    const normalized = /^[A-Z0-9_]{1,80}$/.test(String(code || '')) ? String(code) : 'PLUGIN_SANDBOX_UNAVAILABLE'
    super(normalized)
    this.status = Number(status) >= 400 && Number(status) <= 599 ? Number(status) : 503
    this.code = normalized
  }
}

export class SandboxClient {
  /** 固定 Unix Socket、调用超时和响应上限。 */
  constructor(socketPath) {
    this.socketPath = socketPath
    this.timeout = Math.max(1000, Math.min(Number(process.env.PLUGIN_WORKER_TIMEOUT_SECONDS || 660) * 1000, 660000))
    this.maximum = Math.max(1024, Math.min(Number(process.env.PLUGIN_WORKER_MAX_RESPONSE_BYTES || 16 * 1024 * 1024),
      16 * 1024 * 1024))
  }

  /** 发送固定操作的有限 JSON，并拒绝网络回退和畸形响应。 */
  async request(operation, value) {
    if (!['inspect', 'invoke', 'remove'].includes(operation)) throw new SandboxError(400, 'PLUGIN_SANDBOX_OPERATION_INVALID')
    const body = Buffer.from(JSON.stringify(value))
    return new Promise((resolve, reject) => {
      const request = httpRequest({ socketPath: this.socketPath, path: `/sandbox/${operation}`, method: 'POST',
        headers: { 'content-type': 'application/json', 'content-length': body.length } }, response => {
        const chunks = []; let size = 0
        response.on('data', chunk => {
          size += chunk.length
          if (size > this.maximum) response.destroy(new SandboxError(502, 'PLUGIN_SANDBOX_OUTPUT_INVALID'))
          else chunks.push(chunk)
        })
        response.once('error', error => reject(error instanceof SandboxError ? error
          : new SandboxError(503, 'PLUGIN_SANDBOX_UNAVAILABLE')))
        response.once('end', () => {
          try {
            const result = JSON.parse(Buffer.concat(chunks).toString('utf8'))
            if (!result || typeof result !== 'object' || Array.isArray(result)) throw new Error('invalid')
            if (Math.floor((response.statusCode || 500) / 100) !== 2) {
              reject(new SandboxError(response.statusCode, result.error)); return
            }
            resolve(result)
          } catch { reject(new SandboxError(502, 'PLUGIN_SANDBOX_OUTPUT_INVALID')) }
        })
      })
      request.setTimeout(this.timeout, () => request.destroy(new SandboxError(504, 'PLUGIN_SANDBOX_TIMEOUT')))
      request.once('error', error => reject(error instanceof SandboxError ? error
        : new SandboxError(503, 'PLUGIN_SANDBOX_UNAVAILABLE')))
      request.end(body)
    })
  }

  /** 验证来源专用 Broker Socket 及其 Docker Engine 可用。 */
  async healthy() {
    return new Promise(resolve => {
      const request = httpRequest({ socketPath: this.socketPath, path: '/health', method: 'GET' }, response => {
        const chunks = []
        response.on('data', chunk => chunks.push(chunk))
        response.on('end', () => {
          try { resolve(response.statusCode === 200 && JSON.parse(Buffer.concat(chunks).toString('utf8')).status === 'UP') }
          catch { resolve(false) }
        })
      })
      request.setTimeout(3000, () => request.destroy())
      request.on('error', () => resolve(false))
      request.end()
    })
  }
}
