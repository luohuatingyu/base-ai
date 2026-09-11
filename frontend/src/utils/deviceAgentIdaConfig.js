/**
 * 读取 Agent 已保存的 IDA 配置。
 *
 * 首次接入时后端返回 404 代表尚无配置，按空配置处理；其他错误必须
 * 向上抛出，防止向导在无法确认旧值时用默认值覆盖用户配置。
 *
 * @param {Object} httpClient 统一 HTTP 客户端
 * @param {string} agentId Agent 标识
 * @returns {Promise<Object|null>} 已有配置，尚未配置时返回 null
 */
export async function loadExistingIdaConfig(httpClient, agentId) {
  try {
    const response = await httpClient.get(`/automation/device-agents/${agentId}/ida-config`,
      { silentError: true })
    return response.data || null
  } catch (error) {
    if (error?.response?.status === 404) return null
    throw error
  }
}

/**
 * 构造向导自动探测签名后的主机级 IDA 配置请求。
 *
 * 设备由 Agent 设备池持续自动发现，不写入主机配置；签名身份使用本次探测结果，
 * “允许注册新设备”是用户明确做出的安全选择，必须从已有配置保留。
 *
 * @param {Object} signing 本次探测选定的签名身份
 * @param {Object|null} existingConfig 已保存的 IDA 配置
 * @returns {Object} 可直接提交的 IDA 配置请求
 */
export function buildDetectedIdaConfigPayload(signing, existingConfig) {
  return {
    signingConfig: {
      xcodeOrgId: signing.teamId,
      xcodeSigningId: signing.signingIdentity || null,
      allowProvisioningDeviceRegistration:
        existingConfig?.signingConfig?.allowProvisioningDeviceRegistration === true,
      updatedIdaBundleId: existingConfig?.signingConfig?.updatedIdaBundleId || null
    },
    launchMode: existingConfig?.launchMode || 'XCODEBUILD',
    idaUrl: existingConfig?.launchMode === 'URL' ? existingConfig.idaUrl : null,
    appiumServerUrl: 'http://127.0.0.1:4723',
    baseIdaLocalPort: existingConfig?.baseIdaLocalPort || 8100
  }
}
