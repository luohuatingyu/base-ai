<template>
  <div class="panel api-key-page">
    <div class="section-head">
      <div>
        <h2>{{ t('apiKeys.title') }}</h2>
        <p>{{ t('apiKeys.description') }}</p>
      </div>
      <el-button v-if="auth.isAdmin && auth.hasPermission('system:api-key:create')" type="primary" @click="openCreate">
        {{ t('apiKeys.add') }}
      </el-button>
    </div>

    <el-alert :title="t('apiKeys.secretNotice')" type="warning" :closable="false" show-icon />

    <el-collapse v-model="expandedUsageSections" class="api-key-usage">
      <el-collapse-item name="usage-guide">
        <template #title><strong>{{ t('apiKeyUsage.title') }}</strong></template>
        <div class="api-key-usage-content">
          <p>{{ t('apiKeyUsage.introduction') }}</p>
          <strong>{{ t('apiKeyUsage.exampleTitle') }}</strong>
          <pre><code>{{ t('apiKeyUsage.curlExample') }}</code></pre>
          <strong>{{ t('apiKeyUsage.rulesTitle') }}</strong>
          <ul>
            <li>{{ t('apiKeyUsage.permissionRule') }}</li>
            <li>{{ t('apiKeyUsage.credentialRule') }}</li>
            <li>{{ t('apiKeyUsage.restrictionRule') }}</li>
          </ul>
        </div>
      </el-collapse-item>
    </el-collapse>

    <el-form inline class="api-key-query">
      <el-form-item>
        <el-input v-model="query.keyword" clearable :placeholder="t('apiKeys.keyword')" @keyup.enter="load" />
      </el-form-item>
      <el-form-item>
        <el-select v-model="query.enabled" clearable :placeholder="t('common.status')" style="width: 140px">
          <el-option :label="t('common.enabled')" :value="true" />
          <el-option :label="t('common.disabled')" :value="false" />
        </el-select>
      </el-form-item>
      <el-button @click="load">{{ t('common.query') }}</el-button>
    </el-form>

    <el-table v-loading="loading" :data="rows" table-layout="auto">
      <el-table-column prop="name" :label="t('common.name')" min-width="160" />
      <el-table-column prop="keyPrefix" :label="t('apiKeys.keyPrefix')" min-width="220" />
      <el-table-column :label="t('apiKeys.owner')" min-width="190">
        <template #default="scope">{{ scope.row.ownerDisplayName }} ({{ scope.row.ownerUsername }})</template>
      </el-table-column>
      <el-table-column :label="t('apiKeys.expiration')" min-width="180">
        <template #default="scope">
          <el-tag v-if="scope.row.neverExpires" type="warning">{{ t('apiKeys.neverExpires') }}</el-tag>
          <span v-else>{{ scope.row.expiresAt || '-' }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('apiKeys.endpoints')" width="110">
        <template #default="scope">{{ scope.row.endpointCodes?.length || 0 }}</template>
      </el-table-column>
      <el-table-column :label="t('apiKeys.rateLimit')" min-width="170">
        <template #default="scope">{{ rateLimitDisplay(scope.row) }}</template>
      </el-table-column>
      <el-table-column :label="t('common.status')" width="100">
        <template #default="scope">
          <el-tag :type="scope.row.enabled ? 'success' : 'info'">
            {{ scope.row.enabled ? t('common.enabled') : t('common.disabled') }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastUsedAt" :label="t('apiKeys.lastUsedAt')" min-width="180" />
      <el-table-column :label="t('common.operation')" width="320" fixed="right">
        <template #default="scope">
          <div class="table-actions">
            <el-button v-if="auth.isAdmin" link type="primary" @click="viewSecret(scope.row)">
              {{ t('apiKeys.viewKey') }}
            </el-button>
            <el-button v-if="auth.hasPermission('system:api-key:update')" link type="primary" @click="openEdit(scope.row)">
              {{ t('common.edit') }}
            </el-button>
            <el-button v-if="auth.hasPermission('system:api-key:update')" link @click="toggle(scope.row)">
              {{ scope.row.enabled ? t('common.disabled') : t('common.enabled') }}
            </el-button>
            <el-button v-if="auth.isAdmin && auth.hasPermission('system:api-key:rotate')" link type="warning" @click="rotate(scope.row)">
              {{ t('apiKeys.rotate') }}
            </el-button>
            <el-button v-if="auth.hasPermission('system:api-key:delete')" link type="danger" @click="revoke(scope.row)">
              {{ t('apiKeys.revoke') }}
            </el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-model:current-page="query.page" :page-size="query.size" :total="total"
      layout="total, prev, pager, next" @current-change="load" />

    <el-dialog v-model="editorVisible" :title="form.id ? t('apiKeys.edit') : t('apiKeys.add')" width="760px">
      <el-form label-position="top">
        <el-form-item :label="t('common.name')">
          <el-input v-model="form.name" maxlength="100" />
        </el-form-item>
        <el-form-item :label="t('apiKeys.owner')">
          <el-select v-model="form.ownerUserId" filterable style="width: 100%">
            <el-option v-for="owner in owners" :key="owner.id" :value="owner.id"
              :label="`${owner.displayName} (${owner.username})`" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('apiKeys.expirationType')">
          <el-radio-group v-model="form.neverExpires" @change="changeExpirationType">
            <el-radio-button :value="false">{{ t('apiKeys.scheduledExpiration') }}</el-radio-button>
            <el-radio-button :value="true">{{ t('apiKeys.neverExpires') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-alert v-if="form.neverExpires" :title="t('apiKeys.neverExpiresWarning')" type="warning" :closable="false" show-icon />
        <el-form-item v-if="!form.neverExpires" :label="t('apiKeys.expiresAt')">
          <el-date-picker v-model="form.expiresAt" type="datetime" value-format="YYYY-MM-DDTHH:mm:ssZ"
            :placeholder="t('apiKeys.selectExpiresAt')" style="width: 100%" />
        </el-form-item>
        <el-form-item :label="t('apiKeys.rateLimit')">
          <div class="rate-limit-editor">
            <el-select v-model="form.rateLimitType" style="width: 180px">
              <el-option v-for="type in rateLimitTypes" :key="type" :value="type"
                :label="t(`apiKeys.rateLimitTypes.${type}`)" />
            </el-select>
            <el-input-number v-if="form.rateLimitType !== 'UNLIMITED'" v-model="form.rateLimitCount"
              :min="1" :max="100000" style="width: 220px" />
          </div>
        </el-form-item>
        <el-form-item :label="t('apiKeys.allowedIps')">
          <el-input v-model="form.allowedCidrsText" type="textarea" :rows="4" :placeholder="t('apiKeys.allowedIpsPlaceholder')" />
          <div class="form-help">{{ t('apiKeys.allowedIpsHelp') }}</div>
        </el-form-item>
        <el-form-item :label="t('apiKeys.endpoints')">
          <div class="endpoint-groups">
            <section v-for="group in endpointGroups" :key="group.key" class="endpoint-group">
              <strong>{{ group.name }}</strong>
              <el-checkbox-group v-model="form.endpointCodes">
                <el-checkbox v-for="endpoint in group.items" :key="endpoint.code" :value="endpoint.code" class="endpoint-option">
                  <span class="endpoint-method">{{ endpoint.method }}</span>
                  <span>{{ translateEndpoint(endpoint.nameKey, endpoint.code) }}</span>
                  <code>{{ endpoint.path }}</code>
                  <el-tag :type="riskType(endpoint.risk)" size="small">{{ t(`apiKeys.risk.${endpoint.risk}`) }}</el-tag>
                </el-checkbox>
              </el-checkbox-group>
            </section>
          </div>
        </el-form-item>
        <el-form-item :label="t('common.enabled')">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="secretVisible" :title="t('apiKeys.secretTitle')" width="620px" :close-on-click-modal="false"
      @closed="clearSecret">
      <el-alert :title="t('apiKeys.secretAdminNotice')" type="warning" :closable="false" show-icon />
      <el-input v-model="generatedApiKey" readonly class="secret-value">
        <template #append><el-button @click="copySecret">{{ t('apiKeys.copy') }}</el-button></template>
      </el-input>
      <template #footer><el-button type="primary" @click="secretVisible = false">{{ t('common.confirm') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'

const { t, te } = useI18n()
const auth = useAuthStore()
const rows = ref([])
const owners = ref([])
const endpoints = ref([])
const total = ref(0)
const loading = ref(false)
const saving = ref(false)
const editorVisible = ref(false)
const secretVisible = ref(false)
const generatedApiKey = ref('')
const expandedUsageSections = ref([])
const query = reactive({ keyword: '', enabled: null, page: 1, size: 5 })
const form = reactive(emptyForm())
const rateLimitTypes = ['SECOND', 'MINUTE', 'HOUR', 'DAY', 'UNLIMITED']
const endpointGroups = computed(() => {
  const groups = new Map()
  for (const endpoint of endpoints.value) {
    const groupName = translateEndpoint(endpoint.groupKey, endpoint.code)
    if (!groups.has(endpoint.groupKey)) groups.set(endpoint.groupKey, { name: groupName, items: [] })
    groups.get(endpoint.groupKey).items.push(endpoint)
  }
  return [...groups.entries()].map(([key, group]) => ({ key, ...group }))
})

/** 按当前语言翻译开放接口文案，缺失词条时回退接口编码。 */
function translateEndpoint(translationKey, endpointCode) {
  return translationKey && te(translationKey) ? t(translationKey) : endpointCode
}

/** 创建 API Key 编辑表单的默认值。 */
function emptyForm() {
  return { id: null, name: '', ownerUserId: null, enabled: true, neverExpires: false, expiresAt: defaultExpiration(), rateLimitType: 'MINUTE', rateLimitCount: 60, endpointCodes: [], allowedCidrsText: '' }
}

/** 默认将指定有效期设置为一年后。 */
function defaultExpiration() {
  const value = new Date()
  value.setFullYear(value.getFullYear() + 1)
  return value.toISOString()
}

/** 加载 API Key 分页列表。 */
async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/system/api-keys', { params: query })
    rows.value = data.items
    total.value = data.total
  } finally {
    loading.value = false
  }
}

/** 加载绑定用户和代码开放接口目录。 */
async function loadOptions() {
  const [ownerResponse, endpointResponse] = await Promise.all([
    http.get('/system/api-keys/owners'),
    http.get('/system/api-keys/endpoints')
  ])
  owners.value = ownerResponse.data
  endpoints.value = endpointResponse.data
}

/** 打开新建窗口。 */
function openCreate() {
  Object.assign(form, emptyForm())
  editorVisible.value = true
}

/** 打开编辑窗口并恢复授权和白名单配置。 */
function openEdit(row) {
  Object.assign(form, {
    id: row.id,
    name: row.name,
    ownerUserId: row.ownerUserId,
    enabled: row.enabled,
    neverExpires: row.neverExpires,
    expiresAt: row.expiresAt,
    rateLimitType: row.rateLimitType || 'MINUTE',
    rateLimitCount: row.rateLimitCount ?? 60,
    endpointCodes: [...row.endpointCodes],
    allowedCidrsText: (row.allowedCidrs || []).join('\n')
  })
  editorVisible.value = true
}

/** 切换永久有效时清理不再使用的过期时间。 */
function changeExpirationType(neverExpires) {
  form.expiresAt = neverExpires ? null : defaultExpiration()
}

/** 保存 API Key 并在创建时展示一次性 Secret。 */
async function save() {
  if (form.neverExpires) {
    await ElMessageBox.confirm(t('apiKeys.neverExpiresConfirm'), t('apiKeys.riskTitle'), { type: 'warning' })
  }
  saving.value = true
  try {
    const payload = {
      name: form.name,
      ownerUserId: form.ownerUserId,
      enabled: form.enabled,
      neverExpires: form.neverExpires,
      expiresAt: form.neverExpires ? null : form.expiresAt,
      rateLimitType: form.rateLimitType,
      rateLimitCount: form.rateLimitType === 'UNLIMITED' ? null : form.rateLimitCount,
      endpointCodes: form.endpointCodes,
      allowedCidrs: parseCidrs()
    }
    if (form.id) {
      await http.put(`/system/api-keys/${form.id}`, payload)
      ElMessage.success(t('common.successSaved'))
    } else {
      const { data } = await http.post('/system/api-keys', payload)
      showSecret(data.apiKey)
    }
    editorVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

/** 格式化列表中的 API Key 限流配置。 */
function rateLimitDisplay(row) {
  const type = row.rateLimitType || 'MINUTE'
  if (type === 'UNLIMITED') return t('apiKeys.rateLimitTypes.UNLIMITED')
  return `${row.rateLimitCount} ${t(`apiKeys.rateLimitUnits.${type}`)}`
}

/** 将逐行 IP 输入规范为去重规则列表。 */
function parseCidrs() {
  return [...new Set(form.allowedCidrsText.split(/[\n,]/).map(value => value.trim()).filter(Boolean))]
}

/** 启用或停用 API Key。 */
async function toggle(row) {
  await http.post(`/system/api-keys/${row.id}/${row.enabled ? 'disable' : 'enable'}`)
  await load()
}

/** 查询并展示管理员可解密读取的完整 API Key。 */
async function viewSecret(row) {
  try {
    const { data } = await http.get(`/system/api-keys/${row.id}/secret`)
    showSecret(data.apiKey)
  } catch (error) {
    showHttpError(error)
  }
}

/** 二次确认后轮换 Secret 并展示新 Key。 */
async function rotate(row) {
  await ElMessageBox.confirm(t('apiKeys.rotateConfirm', { name: row.name }), t('common.confirm'), { type: 'warning' })
  const { data } = await http.post(`/system/api-keys/${row.id}/rotate`)
  showSecret(data.apiKey)
  await load()
}

/** 二次确认后永久吊销 API Key。 */
async function revoke(row) {
  await ElMessageBox.confirm(t('apiKeys.revokeConfirm', { name: row.name }), t('common.deleteConfirm'), { type: 'warning' })
  await http.delete(`/system/api-keys/${row.id}`)
  await load()
}

/** 展示仅管理员可读取的完整 API Key。 */
function showSecret(apiKey) {
  generatedApiKey.value = apiKey
  secretVisible.value = true
}

/** 弹窗关闭后清除浏览器内存中的完整 API Key。 */
function clearSecret() {
  generatedApiKey.value = ''
}

/** 复制完整 API Key 到系统剪贴板。 */
async function copySecret() {
  try {
    await navigator.clipboard.writeText(generatedApiKey.value)
    ElMessage.success(t('apiKeys.copied'))
  } catch {
    ElMessage.error(t('apiKeys.copyFailed'))
  }
}

/** 将风险级别映射为 Element Plus 标签样式。 */
function riskType(risk) {
  return risk === 'HIGH' ? 'danger' : risk === 'SENSITIVE' ? 'warning' : 'success'
}

onMounted(async () => {
  await loadOptions()
  await load()
})
</script>

<style scoped>
.api-key-page { display: grid; gap: 18px; }
.api-key-usage { border: 1px solid var(--el-border-color); border-radius: 8px; padding: 0 16px; }
.api-key-usage-content { color: var(--el-text-color-regular); }
.api-key-usage-content p { margin: 0 0 14px; }
.api-key-usage-content pre { overflow-x: auto; margin: 8px 0 16px; padding: 14px; border-radius: 6px; background: var(--el-fill-color-light); white-space: pre-wrap; overflow-wrap: anywhere; }
.api-key-usage-content ul { margin: 8px 0 16px; padding-left: 22px; }
.api-key-usage-content li + li { margin-top: 6px; }
.api-key-query { margin-bottom: 0; }
.endpoint-groups { display: grid; gap: 14px; width: 100%; }
.endpoint-group { display: grid; gap: 10px; padding: 14px; border: 1px solid var(--el-border-color); border-radius: 8px; }
.endpoint-option { display: flex; width: 100%; min-height: 32px; margin-right: 0; }
.endpoint-option :deep(.el-checkbox__label) { display: grid; grid-template-columns: 54px minmax(120px, auto) minmax(180px, 1fr) auto; gap: 10px; align-items: center; width: 100%; }
.endpoint-method { color: var(--el-color-primary); font-weight: 700; }
.endpoint-option code { overflow-wrap: anywhere; color: var(--el-text-color-secondary); }
.form-help { margin-top: 6px; color: var(--el-text-color-secondary); font-size: 13px; }
.rate-limit-editor { display: flex; gap: 12px; flex-wrap: wrap; }
.secret-value { margin-top: 18px; }
@media (max-width: 720px) {
  .endpoint-option :deep(.el-checkbox__label) { grid-template-columns: 48px 1fr; }
  .endpoint-option code, .endpoint-option .el-tag { grid-column: 2; }
}
</style>
