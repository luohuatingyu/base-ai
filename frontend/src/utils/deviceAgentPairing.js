import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { showHttpError } from '../api/http'

/** 复用 Agent 配对身份建议请求，保证管理页和接入向导行为一致。 */
export function usePairingIdentity(httpClient, form, translate) {
  const loading = ref(false)

  /** 请求服务端生成身份并回填当前页面表单。 */
  const generatePairingIdentity = async () => {
    loading.value = true
    try {
      const response = await httpClient.get('/automation/device-agents/pairing/identity-suggestion')
      form.agentId = response.data.agentId
      form.deviceName = response.data.deviceName
      ElMessage.success(translate('deviceAgents.agentIdentityGenerated'))
    } catch (error) {
      showHttpError(error, 'deviceAgents.agentIdentityGenerateError')
    } finally {
      loading.value = false
    }
  }

  return { identitySuggestionLoading: loading, generatePairingIdentity }
}
