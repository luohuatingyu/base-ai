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
            <strong>{{ t('openPlatform.endpoints') }}</strong>
            <span>{{ endpoints.length }}</span>
          </div>
          <button v-for="endpoint in endpoints" :key="endpoint.code" type="button"
                  class="open-platform-endpoint-item" :class="{ active: selectedEndpoint?.code === endpoint.code }"
                  @click="selectEndpoint(endpoint)">
            <span>{{ t(endpoint.groupKey) }}</span>
            <strong>{{ t(endpoint.nameKey) }}</strong>
            <code><em>{{ endpoint.method }}</em> {{ endpoint.path }}</code>
          </button>
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
            <section class="open-platform-section">
              <h3>{{ t('openPlatform.curlExample') }}</h3>
              <pre><code>{{ curlExample }}</code></pre>
            </section>
            <section class="open-platform-section">
              <h3>{{ t('openPlatform.responseExample') }}</h3>
              <pre><code>{{ selectedEndpoint.responseExample }}</code></pre>
            </section>
          </div>

          <section class="open-platform-section open-platform-debugger">
            <div class="open-platform-debugger-title">
              <div><h3>{{ t('openPlatform.debugger') }}</h3><p>{{ t('openPlatform.debuggerHelp') }}</p></div>
              <el-tag v-if="selectedEndpoint.risk === 'HIGH'" type="danger">{{ t('openPlatform.highRiskWarning') }}</el-tag>
            </div>
            <el-form label-position="top">
              <el-form-item label="API Key" required>
                <el-input v-model="apiKey" type="password" show-password autocomplete="off" placeholder="sk-..." />
              </el-form-item>
              <el-form-item v-for="parameter in selectedEndpoint.pathParameters" :key="parameter.name"
                            :label="parameter.name" :required="parameter.required">
                <el-input v-model="pathValues[parameter.name]" :placeholder="parameter.example" />
              </el-form-item>
              <el-form-item v-if="selectedEndpoint.requestFields.length" :label="t('openPlatform.requestBody')" required>
                <el-input v-model="requestBody" type="textarea" :rows="14" spellcheck="false" />
              </el-form-item>
              <el-button type="primary" :loading="sending" @click="sendRequest">{{ t('openPlatform.sendRequest') }}</el-button>
            </el-form>
            <div v-if="debugResult" class="open-platform-debug-result">
              <div><strong>{{ t('openPlatform.debugResult') }}</strong><span>HTTP {{ debugResult.status }} · {{ debugResult.durationMs }} ms</span></div>
              <pre><code>{{ debugResult.body }}</code></pre>
            </div>
          </section>
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
import { buildCurlExample, buildDebugRequest, formatDebugResponse } from '../utils/openPlatform'

const { locale, t } = useI18n()
const auth = useAuthStore()
const locales = LOCALES
const loading = ref(true)
const loadError = ref('')
const endpoints = ref([])
const selectedEndpoint = ref(null)
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

/** 将请求构造校验错误转换为当前语言提示。 */
function validationMessage(error) {
  if (error.message.startsWith('PATH_PARAMETER_REQUIRED:')) {
    return t('openPlatform.pathRequired', { name: error.message.split(':')[1] })
  }
  return t(`openPlatform.errors.${error.message}`)
}

/** 发送一次真实 API Key 调试请求并展示原始响应。 */
async function sendRequest() {
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
