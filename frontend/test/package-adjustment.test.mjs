import assert from 'node:assert/strict'
import { webcrypto } from 'node:crypto'
import { readFileSync } from 'node:fs'
import test, { before } from 'node:test'
import { buildTokenWithPlan, formatJwtTimestamp, parseJwtToken } from '../src/utils/jwtToken.js'
import { buildAccessibleNavigation, localizeMenuName } from '../src/utils/navigation.js'
import zhCN from '../src/locales/zh-CN.js'
import enUS from '../src/locales/en-US.js'

const routeSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const viewSource = readFileSync(new URL('../src/views/PackageAdjustmentView.vue', import.meta.url), 'utf8')
let signingKeyPair
let privateKeyPem

function bytesToBase64(bytes) {
  return Buffer.from(bytes).toString('base64')
}

function bytesToPem(bytes) {
  const base64 = bytesToBase64(bytes)
  return `-----BEGIN PRIVATE KEY-----\n${base64.match(/.{1,64}/g).join('\n')}\n-----END PRIVATE KEY-----`
}

function decodeBase64Url(value) {
  return Uint8Array.from(Buffer.from(value.replace(/-/g, '+').replace(/_/g, '/'), 'base64'))
}

before(async () => {
  signingKeyPair = await webcrypto.subtle.generateKey(
    { name: 'RSASSA-PKCS1-v1_5', modulusLength: 2048, publicExponent: new Uint8Array([1, 0, 1]), hash: 'SHA-256' },
    true,
    ['sign', 'verify']
  )
  privateKeyPem = bytesToPem(await webcrypto.subtle.exportKey('pkcs8', signingKeyPair.privateKey))
})

function encode(value) {
  const bytes = new TextEncoder().encode(JSON.stringify(value))
  let binary = ''
  bytes.forEach(byte => { binary += String.fromCharCode(byte) })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

function sampleToken(auth = { chatgpt_account_id: 'account-1', chatgpt_user_id: 'user-1', chatgpt_plan_type: 'plus' }) {
  return `${encode({ alg: 'RS256', typ: 'JWT' })}.${encode({ email: 'person@example.com', iat: 1700000000, exp: 1700003600, 'https://api.openai.com/auth': auth })}.signature-part`
}

test('解析 JWT 并提取套餐和关键账户信息', () => {
  const parsed = parseJwtToken(sampleToken())
  assert.equal(parsed.header.alg, 'RS256')
  assert.equal(parsed.payload.email, 'person@example.com')
  assert.equal(parsed.auth.chatgpt_account_id, 'account-1')
  assert.equal(parsed.planType, 'plus')
  assert.equal(parsed.signature, 'signature-part')
})

test('非法 JWT 结构、编码和 JSON 会被拒绝', () => {
  assert.throws(() => parseJwtToken('only.two'), /Header\.Payload\.Signature/)
  assert.throws(() => parseJwtToken('%%%.' + encode({}) + '.sig'), /Header 不是有效的 Base64URL/)
  assert.throws(() => parseJwtToken(`${encode({})}.not-json.sig`), /Payload 不是有效的 JSON/)
})

test('修改套餐后使用 issuer 私钥生成可验证的 RS256 Token', async () => {
  const parsed = parseJwtToken(sampleToken())
  const nextToken = await buildTokenWithPlan(parsed, 'team', privateKeyPem)
  const next = parseJwtToken(nextToken)
  assert.equal(next.planType, 'team')
  assert.notEqual(next.signature, parsed.signature)
  assert.equal(next.payload.email, parsed.payload.email)
  const [header, payload, signature] = nextToken.split('.')
  assert.equal(await webcrypto.subtle.verify(
    { name: 'RSASSA-PKCS1-v1_5' },
    signingKeyPair.publicKey,
    decodeBase64Url(signature),
    new TextEncoder().encode(`${header}.${payload}`)
  ), true)
  await assert.rejects(() => buildTokenWithPlan(parsed, '  ', privateKeyPem), /套餐类型不能为空/)
})

test('缺少 auth 对象时仍可创建套餐字段', async () => {
  const parsed = parseJwtToken(`${encode({ alg: 'RS256' })}.${encode({ sub: 'subject' })}.sig`)
  const next = parseJwtToken(await buildTokenWithPlan(parsed, 'free', privateKeyPem))
  assert.equal(next.planType, 'free')
  assert.equal(next.payload.sub, 'subject')
})

test('非法算法或私钥会阻止签发', async () => {
  const hs256 = parseJwtToken(`${encode({ alg: 'HS256' })}.${encode({})}.sig`)
  await assert.rejects(() => buildTokenWithPlan(hs256, 'team', privateKeyPem), /仅支持 Header.alg 为 RS256/)
  await assert.rejects(() => buildTokenWithPlan(parseJwtToken(sampleToken()), 'team', 'not-a-key'), /PKCS#8 PEM/)
})

test('时间戳格式化支持有效值和无效值', () => {
  assert.match(formatJwtTimestamp(1700000000), /2023/)
  assert.equal(formatJwtTimestamp('unknown'), '')
  assert.equal(formatJwtTimestamp(Number.MAX_VALUE), '')
})

test('套餐调整页面已接入受权限控制的动态导航', () => {
  const menus = [
    { id: 1, parentId: null, type: 'CATALOG', path: '/ai', permission: 'ai:catalog', name: 'AI 能力', sortOrder: 30 },
    { id: 2, parentId: null, type: 'CATALOG', path: '/packages', permission: 'package:catalog', name: '套餐管理', sortOrder: 15 },
    { id: 3, parentId: 2, type: 'MENU', path: '/package-adjustment', permission: 'ai:package-adjustment:list', name: '套餐调整', sortOrder: 10 }
  ]
  const visible = buildAccessibleNavigation(menus, ['/package-adjustment'], permission => ['package:catalog', 'ai:package-adjustment:list'].includes(permission))
  assert.deepEqual(visible.map(item => item.path), ['/packages'])
  assert.deepEqual(visible[0].children.map(item => item.path), ['/package-adjustment'])
  assert.match(routeSource, /path: 'package-adjustment'/)
  assert.match(viewSource, /parseJwtToken/)
  assert.match(viewSource, /privateKey/)
  assert.equal(localizeMenuName(menus[1], key => key === 'nav.items.packages' ? zhCN.nav.items.packages : key), '套餐管理')
  assert.equal(localizeMenuName(menus[2], key => key === 'nav.items.packageAdjustment' ? zhCN.nav.items.packageAdjustment : key), '套餐调整')
  assert.equal(localizeMenuName(menus[1], key => key === 'nav.items.packages' ? enUS.nav.items.packages : key), 'Package Management')
  assert.equal(localizeMenuName(menus[2], key => key === 'nav.items.packageAdjustment' ? enUS.nav.items.packageAdjustment : key), 'Plan Adjustment')
})
