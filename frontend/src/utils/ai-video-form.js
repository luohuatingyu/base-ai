/** 各类资产的业务属性，统一存入后端已有 metadataJson 字段。 */
export const assetAttributes = {
  characters: ['appearance', 'personality'], scenes: ['location', 'timeOfDay', 'weather'],
  items: ['material', 'color', 'dimensions'], costumes: ['style', 'color', 'character'],
  motions: ['duration', 'intensity'], styles: ['keywords', 'palette'],
  voices: ['voice', 'emotion', 'sampleText'], sounds: ['soundType', 'duration', 'emotion']
}

/** 获取当前模块用于保存扩展配置的原有接口字段。 */
export function configurationKey(section) {
  if (assetAttributes[section]) return 'metadataJson'
  return ({ tasks: 'inputJson', renders: 'configJson' })[section] || ''
}

/** 仅接受 JSON 对象，避免数组或基本值在表单编辑时被静默覆盖。 */
export function parseConfiguration(text) {
  if (!text || !String(text).trim()) return {}
  const value = JSON.parse(text)
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('invalidJson')
  return value
}

/** 修改一个可视字段时保留供应商和历史数据的所有未知键。 */
export function updateConfiguration(text, key, value) {
  const data = parseConfiguration(text)
  if (value === '' || value === null || value === undefined) delete data[key]
  else Object.defineProperty(data, key, { value, enumerable: true, configurable: true, writable: true })
  return JSON.stringify(data, null, 2)
}

/** 预览只允许 HTTP(S) 或站内绝对路径，禁止脚本和协议相对地址。 */
export function safeMediaUrl(value) {
  if (typeof value !== 'string' || /[\\\s]/.test(value)) return ''
  if (/^\/(?!\/)/.test(value)) return value
  try { const url = new URL(value); return ['http:', 'https:'].includes(url.protocol) ? value : '' } catch { return '' }
}

/** 保存前验证扩展配置长度与类型，不发送虚拟表单属性。 */
export function prepareVideoPayload(form, section) {
  const payload = { ...form }
  const key = configurationKey(section)
  if (key && payload[key]) {
    parseConfiguration(payload[key])
    if (payload[key].length > 20000) throw new Error('configTooLong')
  }
  return payload
}
