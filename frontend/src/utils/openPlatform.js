/** 将接口路径中的变量替换为 URL 编码后的调试值。 */
export function buildEndpointPath(endpoint, pathValues = {}) {
  return (endpoint.pathParameters || []).reduce((path, parameter) => {
    const value = String(pathValues[parameter.name] ?? '').trim()
    if (!value) throw new Error(`PATH_PARAMETER_REQUIRED:${parameter.name}`)
    return path.replace(`{${parameter.name}}`, encodeURIComponent(value))
  }, endpoint.path)
}

/** 判断接口是否声明了 JSON 请求体。 */
export function hasRequestBody(endpoint) {
  return Array.isArray(endpoint.requestFields) && endpoint.requestFields.length > 0
}

/** 构造在线调试使用的 Axios 请求配置并执行基础校验。 */
export function buildDebugRequest(endpoint, options) {
  const apiKey = String(options.apiKey || '').trim()
  if (!apiKey) throw new Error('API_KEY_REQUIRED')
  const config = {
    method: endpoint.method,
    url: buildEndpointPath(endpoint, options.pathValues),
    headers: {
      'X-API-Key': apiKey,
      'Accept-Language': options.locale
    },
    validateStatus: () => true
  }
  if (hasRequestBody(endpoint)) {
    const requestBody = String(options.requestBody || '').trim()
    if (!requestBody) throw new Error('REQUEST_BODY_REQUIRED')
    config.headers['Content-Type'] = 'application/json'
    try {
      config.data = JSON.parse(requestBody)
    } catch {
      throw new Error('INVALID_JSON')
    }
  }
  return config
}

/** 生成与接口元数据一致且可直接修改使用的 curl 示例。 */
export function buildCurlExample(endpoint, baseUrl = '<BASE_URL>') {
  const pathValues = Object.fromEntries((endpoint.pathParameters || [])
    .map(parameter => [parameter.name, parameter.example || `{${parameter.name}}`]))
  const lines = [
    `curl -X ${endpoint.method} '${baseUrl}${buildEndpointPath(endpoint, pathValues)}'`,
    "  -H 'X-API-Key: sk-<your-api-key>'",
    "  -H 'Accept-Language: en-US'"
  ]
  if (hasRequestBody(endpoint)) {
    lines.push("  -H 'Content-Type: application/json'")
    lines.push(`  -d '${String(endpoint.requestExample).replaceAll("'", "'\\''")}'`)
  }
  return lines.join(' \\\n')
}

/** 将调试响应格式化为便于阅读的 JSON 或文本。 */
export function formatDebugResponse(data) {
  return typeof data === 'string' ? data : JSON.stringify(data, null, 2)
}

/** 校验并格式化在线调试的 JSON 请求体。 */
export function formatJsonRequestBody(requestBody) {
  const value = String(requestBody || '').trim()
  if (!value) throw new Error('REQUEST_BODY_REQUIRED')
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    throw new Error('INVALID_JSON')
  }
}
