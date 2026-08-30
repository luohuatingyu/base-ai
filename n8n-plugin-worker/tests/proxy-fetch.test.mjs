/** n8n 插件强制代理传输测试。 */

import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import test from 'node:test'
import { proxyFetch } from '../app/proxy-fetch.mjs'

/** 启动只接收一次请求的本地测试代理。 */
async function proxy(handler) {
  const server = createServer(handler)
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  return { server, url: `http://proxy-secret@127.0.0.1:${server.address().port}` }
}

test('HTTP request is sent as an authenticated absolute proxy request', async () => {
  const seen = {}
  const running = await proxy((request, response) => {
    seen.url = request.url
    seen.authorization = request.headers['proxy-authorization']
    seen.host = request.headers.host
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end('{"proxied":true}')
  })
  try {
    const response = await proxyFetch('http://api.example.com/v1/items?limit=1', {}, running.url)
    assert.equal(response.status, 200)
    assert.equal(await response.text(), '{"proxied":true}')
    assert.deepEqual(seen, {
      url: 'http://api.example.com/v1/items?limit=1',
      authorization: `Basic ${Buffer.from('proxy-secret:').toString('base64')}`,
      host: 'api.example.com',
    })
  } finally {
    await new Promise(resolve => running.server.close(resolve))
  }
})

test('missing proxy configuration never falls back to a direct request', async () => {
  await assert.rejects(() => proxyFetch('https://api.example.com/v1/items', {}, ''),
    /N8N_OUTBOUND_PROXY_REQUIRED/)
})
