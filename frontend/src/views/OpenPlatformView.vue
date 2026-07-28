<template>
  <div class="open-platform-page">
    <header class="open-platform-header">
      <router-link class="open-platform-brand" to="/open-platform">
        <span>{{ appConfig.shortName }}</span>
        <strong>{{ localizedPlatformName }}</strong>
      </router-link>
      <div class="open-platform-header-actions">
        <el-select :model-value="locale" class="open-platform-locale" @change="changeLocale">
          <el-option v-for="item in locales" :key="item.code" :label="`${item.flag} ${t(item.labelKey)}`" :value="item.code" />
        </el-select>
        <router-link class="open-platform-login-link" :to="auth.isLoggedIn ? '/dashboard' : '/login'">
          {{ auth.isLoggedIn ? t('openPlatform.console') : t('openPlatform.login') }}
        </router-link>
      </div>
    </header>

    <main class="open-platform-main">
      <section class="open-platform-hero">
        <div>
          <span class="open-platform-eyebrow">{{ t('openPlatform.eyebrow') }}</span>
          <h1>{{ t('openPlatform.title') }}</h1>
          <p>{{ t('openPlatform.description') }}</p>
        </div>
        <div class="open-platform-auth-card">
          <strong>{{ t('openPlatform.authentication') }}</strong>
          <code>X-API-Key: sk-&lt;your-api-key&gt;</code>
          <span>{{ t('openPlatform.authenticationHelp') }}</span>
        </div>
      </section>

      <el-alert v-if="loadError" :title="loadError" type="error" show-icon :closable="false" />
      <div v-else v-loading="loading" class="open-platform-workspace">
        <aside class="open-platform-sidebar">
          <div class="open-platform-sidebar-title">
            <div>
              <strong>{{ t('openPlatform.endpoints') }}</strong>
              <small>{{ t('openPlatform.endpointNavigationHelp') }}</small>
            </div>
            <span>{{ endpoints.length }}</span>
          </div>
          <div class="open-platform-endpoint-list">
            <button v-for="endpoint in endpoints" :key="endpoint.code" type="button"
                    class="open-platform-endpoint-item" :class="{ active: selectedEndpoint?.code === endpoint.code }"
                    @click="selectEndpoint(endpoint)">
              <span>{{ t(endpoint.groupKey) }}</span>
              <strong>{{ t(endpoint.nameKey) }}</strong>
              <code><em>{{ endpoint.method }}</em> {{ endpoint.path }}</code>
            </button>
          </div>
        </aside>

        <article v-if="selectedEndpoint" class="open-platform-detail">
          <div class="open-platform-endpoint-heading">
            <div>
              <div class="open-platform-badges">
                <el-tag effect="dark">{{ selectedEndpoint.method }}</el-tag>
                <el-tag :type="riskTagType(selectedEndpoint.risk)">{{ t(`openPlatform.risks.${selectedEndpoint.risk}`) }}</el-tag>
              </div>
              <h2>{{ t(selectedEndpoint.nameKey) }}</h2>
              <p>{{ t(selectedEndpoint.descriptionKey) }}</p>
            </div>
            <code class="open-platform-path">{{ selectedEndpoint.path }}</code>
          </div>

          <el-tabs :key="locale" v-model="activeTab" class="open-platform-tabs">
            <el-tab-pane :label="t('openPlatform.documentationTab')" name="documentation">
              <div class="open-platform-documentation">
                <section v-if="selectedEndpoint.pathParameters.length" class="open-platform-section">
                  <h3>{{ t('openPlatform.pathParameters') }}</h3>
                  <FieldTable :fields="selectedEndpoint.pathParameters" />
                </section>

                <section v-if="selectedEndpoint.requestFields.length" class="open-platform-section">
                  <h3>{{ t('openPlatform.requestParameters') }}</h3>
                  <FieldTable :fields="selectedEndpoint.requestFields" />
                </section>

                <section class="open-platform-section">
                  <h3>{{ t('openPlatform.responseParameters') }}</h3>
                  <FieldTable :fields="selectedEndpoint.responseFields" />
                </section>

                <div class="open-platform-examples">
                  <section class="open-platform-section open-platform-code-card">
                    <div class="open-platform-section-heading">
                      <h3>{{ t('openPlatform.curlExample') }}</h3>
                      <el-button text type="primary" @click="copyText(curlExample, t('openPlatform.curlCopied'))">
                        {{ t('openPlatform.copy') }}
                      </el-button>
                    </div>
                    <pre><code>{{ curlExample }}</code></pre>
                  </section>
                  <section class="open-platform-section open-platform-code-card">
                    <div class="open-platform-section-heading">
                      <h3>{{ t('openPlatform.responseExample') }}</h3>
                      <el-button text type="primary" @click="copyText(selectedEndpoint.responseExample, t('openPlatform.responseCopied'))">
                        {{ t('openPlatform.copy') }}
                      </el-button>
                    </div>
                    <pre><code>{{ selectedEndpoint.responseExample }}</code></pre>
                  </section>
                </div>
              </div>
            </el-tab-pane>

            <el-tab-pane :label="t('openPlatform.debuggerTab')" name="debugger">
              <section class="open-platform-debugger">
                <div class="open-platform-debugger-title">
                  <div>
                    <h3>{{ t('openPlatform.debugger') }}</h3>
                    <p>{{ t('openPlatform.debuggerHelp') }}</p>
                  </div>
                  <el-tag v-if="selectedEndpoint.risk === 'HIGH'" type="danger">{{ t('openPlatform.highRiskWarning') }}</el-tag>
                </div>

                <div class="open-platform-debugger-grid">
                  <div class="open-platform-request-panel" @keydown.ctrl.enter="sendRequest" @keydown.meta.enter="sendRequest">
                    <div class="open-platform-panel-heading">
                      <div>
                        <span>{{ t('openPlatform.requestConfiguration') }}</span>
                        <code><strong>{{ selectedEndpoint.method }}</strong> {{ selectedEndpoint.path }}</code>
                      </div>
                      <small>{{ t('openPlatform.sendShortcut') }}</small>
                    </div>

                    <el-form label-position="top">
                      <el-form-item label="API Key" required>
                        <el-input v-model="apiKey" type="password" show-password autocomplete="off" placeholder="sk-..." />
                        <span class="open-platform-field-help">{{ t('openPlatform.apiKeyMemoryHelp') }}</span>
                      </el-form-item>
                      <el-form-item v-for="parameter in selectedEndpoint.pathParameters" :key="parameter.name"
                                    :label="parameter.name" :required="parameter.required">
                        <el-input v-model="pathValues[parameter.name]" :placeholder="parameter.example" />
                      </el-form-item>
                      <el-form-item v-if="selectedEndpoint.requestFields.length" :label="t('openPlatform.requestBody')" required>
                        <div class="open-platform-editor-actions">
                          <el-button text type="primary" @click="formatRequestBody">{{ t('openPlatform.formatJson') }}</el-button>
                          <el-button text @click="resetRequestBody">{{ t('openPlatform.resetExample') }}</el-button>
                        </div>
                        <el-input v-model="requestBody" type="textarea" :rows="16" spellcheck="false" />
                      </el-form-item>
                      <div class="open-platform-send-actions">
                        <el-button @click="resetDebugger">{{ t('openPlatform.resetDebugger') }}</el-button>
                        <el-button type="primary" :loading="sending" @click="sendRequest">{{ t('openPlatform.sendRequest') }}</el-button>
                      </div>
                    </el-form>
                  </div>

                  <div class="open-platform-response-panel">
                    <div class="open-platform-panel-heading">
                      <div>
                        <span>{{ t('openPlatform.debugResult') }}</span>
                        <div v-if="debugResult" class="open-platform-response-meta">
                          <el-tag :type="responseTagType(debugResult.status)" effect="plain">HTTP {{ debugResult.status }}</el-tag>
                          <small>{{ debugResult.durationMs }} ms</small>
                        </div>
                      </div>
                      <el-button v-if="debugResult" text type="primary" @click="copyText(debugResult.body, t('openPlatform.responseCopied'))">
                        {{ t('openPlatform.copy') }}
                      </el-button>
                    </div>
                    <div v-if="debugResult" class="open-platform-debug-result">
                      <pre><code>{{ debugResult.body }}</code></pre>
                    </div>
                    <el-empty v-else :description="t('openPlatform.emptyDebugResult')">
                      <template #image>
                        <div class="open-platform-empty-icon">API</div>
                      </template>
                      <span>{{ t('openPlatform.emptyDebugResultHelp') }}</span>
                    </el-empty>
                  </div>
                </div>
              </section>
            </el-tab-pane>
          </el-tabs>
        </article>
      </div>
    </main>
  </div>
</template>

<script setup>
import { computed, defineComponent, h, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage, ElMessageBox, ElTable, ElTableColumn, ElTag } from 'element-plus'
import axios from 'axios'
import { appConfig, getLocalizedPlatformName } from '../config'
import { LOCALES } from '../locales/registry'
import { useAuthStore } from '../stores/auth'
import { buildCurlExample, buildDebugRequest, formatDebugResponse, formatJsonRequestBody } from '../utils/openPlatform'

const { locale, t } = useI18n()
const auth = useAuthStore()
const locales = LOCALES
const loading = ref(true)
const loadError = ref('')
const endpoints = ref([])
const selectedEndpoint = ref(null)
const activeTab = ref('documentation')
const apiKey = ref('')
const pathValues = reactive({})
const requestBody = ref('')
const sending = ref(false)
const debugResult = ref(null)
const localizedPlatformName = computed(() => getLocalizedPlatformName(locale.value))
const curlExample = computed(() => selectedEndpoint.value ? buildCurlExample(selectedEndpoint.value) : '')

const FieldTable = defineComponent({
  props: { fields: { type: Array, required: true } },
  setup(props) {
    return () => h(ElTable, { data: props.fields, class: 'open-platform-field-table' }, () => [
      h(ElTableColumn, { prop: 'name', label: t('openPlatform.field'), minWidth: 170 }),
      h(ElTableColumn, { prop: 'type', label: t('openPlatform.fieldType'), width: 130 }),
      h(ElTableColumn, { label: t('openPlatform.required'), width: 90 }, {
        default: ({ row }) => h(ElTag, { type: row.required ? 'danger' : 'info', size: 'small' },
          () => row.required ? t('common.yes') : t('common.no'))
      }),
      h(ElTableColumn, { label: t('openPlatform.defaultValue'), minWidth: 110 }, {
        default: ({ row }) => row.defaultValue || '-'
      }),
      h(ElTableColumn, { label: t('common.description'), minWidth: 260 }, {
        default: ({ row }) => t(row.descriptionKey)
      })
    ])
  }
})

/** 切换公开页面语言并沿用平台语言持久化规则。 */
function changeLocale(value) {
  locale.value = value
  localStorage.setItem('locale', value)
}

/** 选择接口并用元数据示例初始化调试输入。 */
function selectEndpoint(endpoint) {
  selectedEndpoint.value = endpoint
  Object.keys(pathValues).forEach(key => delete pathValues[key])
  endpoint.pathParameters.forEach(parameter => { pathValues[parameter.name] = parameter.example || '' })
  requestBody.value = endpoint.requestExample || ''
  debugResult.value = null
}

/** 根据风险等级返回 Element Plus 标签样式。 */
function riskTagType(risk) {
  return risk === 'HIGH' ? 'danger' : risk === 'SENSITIVE' ? 'warning' : 'success'
}

/** 根据响应状态码返回 Element Plus 标签样式。 */
function responseTagType(status) {
  if (status >= 200 && status < 300) return 'success'
  if (status >= 400 && status < 500) return 'warning'
  return 'danger'
}

/** 将请求构造校验错误转换为当前语言提示。 */
function validationMessage(error) {
  if (error.message.startsWith('PATH_PARAMETER_REQUIRED:')) {
    return t('openPlatform.pathRequired', { name: error.message.split(':')[1] })
  }
  return t(`openPlatform.errors.${error.message}`)
}

/** 将请求体格式化为便于编辑的缩进 JSON。 */
function formatRequestBody() {
  try {
    requestBody.value = formatJsonRequestBody(requestBody.value)
    ElMessage.success(t('openPlatform.jsonFormatted'))
  } catch (error) {
    ElMessage.warning(validationMessage(error))
  }
}

/** 将当前请求体恢复为接口元数据提供的示例。 */
function resetRequestBody() {
  requestBody.value = selectedEndpoint.value.requestExample || ''
  ElMessage.success(t('openPlatform.exampleRestored'))
}

/** 清空当前接口的调试结果并恢复路径和请求示例。 */
function resetDebugger() {
  selectEndpoint(selectedEndpoint.value)
}

/** 复制文本并反馈操作结果。 */
async function copyText(value, successMessage) {
  try {
    await navigator.clipboard.writeText(String(value || ''))
    ElMessage.success(successMessage)
  } catch {
    ElMessage.error(t('openPlatform.copyFailed'))
  }
}

/** 发送一次真实 API Key 调试请求并展示原始响应。 */
async function sendRequest() {
  if (sending.value) return
  let config
  try {
    config = buildDebugRequest(selectedEndpoint.value, {
      apiKey: apiKey.value,
      pathValues,
      requestBody: requestBody.value,
      locale: locale.value
    })
    if (selectedEndpoint.value.risk === 'HIGH') {
      await ElMessageBox.confirm(t('openPlatform.highRiskConfirm'), t('openPlatform.highRiskTitle'), { type: 'warning' })
    }
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.warning(validationMessage(error))
    return
  }
  sending.value = true
  const startedAt = performance.now()
  try {
    const response = await axios(config)
    debugResult.value = {
      status: response.status,
      durationMs: Math.round(performance.now() - startedAt),
      body: formatDebugResponse(response.data)
    }
  } catch (error) {
    debugResult.value = {
      status: error.response?.status || 0,
      durationMs: Math.round(performance.now() - startedAt),
      body: formatDebugResponse(error.response?.data || { message: error.message })
    }
  } finally { sending.value = false }
}

/** 加载公开接口目录并默认选中第一个接口。 */
async function loadEndpoints() {
  loading.value = true
  try {
    const { data } = await axios.get('/api/open/platform/endpoints', { headers: { 'Accept-Language': locale.value } })
    endpoints.value = data
    if (data.length) selectEndpoint(data[0])
  } catch (error) {
    loadError.value = error.response?.data?.message || t('openPlatform.loadFailed')
  } finally { loading.value = false }
}

onMounted(loadEndpoints)
onBeforeUnmount(() => {
  apiKey.value = ''
  requestBody.value = ''
  debugResult.value = null
})
</script>
