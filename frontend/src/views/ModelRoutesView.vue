<template>
  <div class="panel model-routes-panel">
    <div class="section-head">
      <div><h2>{{ t('routes.title') }}</h2><p>{{ t('routes.description') }}</p></div>
      <el-button v-if="auth.hasPermission('model:route:create')" type="primary" @click="open()">{{ t('routes.add') }}</el-button>
    </div>
    <el-alert class="route-sync-notice" :title="t('routes.editSyncNotice')" type="warning" show-icon :closable="false"/>
    <el-table :data="rows">
      <el-table-column prop="featureCode" :label="t('routes.featureCode')"/>
      <el-table-column prop="name" :label="t('common.name')"/>
      <el-table-column prop="capabilityLevel" :label="t('models.capability')"/>
      <el-table-column prop="thinkingLevel" :label="t('routes.thinkingLevel')"/>
      <el-table-column :label="t('common.operation')" width="180">
        <template #default="scope">
          <el-button v-if="auth.hasPermission('model:route:update')" link type="primary" @click="openSync(scope.row)">{{ t('routes.sync') }}</el-button>
          <el-button v-if="auth.hasPermission('model:route:update')" link type="primary" @click="open(scope.row)">{{ t('common.edit') }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id?t('routes.edit'):t('routes.add')">
      <el-form label-width="110px">
        <el-form-item :label="t('routes.featureCode')"><el-input v-model="form.featureCode"/></el-form-item>
        <el-form-item :label="t('common.name')"><el-input v-model="form.name"/></el-form-item>
        <el-form-item :label="t('routes.providerPool')"><el-select v-model="form.providerIds" multiple><el-option v-for="item in providers" :key="item.id" :label="item.name" :value="item.id"/></el-select></el-form-item>
        <el-form-item :label="t('models.capability')"><el-select v-model="form.capabilityLevel"><el-option label="Low" value="LOW"/><el-option label="Medium" value="MIDDLE"/><el-option label="High" value="HIGH"/></el-select></el-form-item>
        <el-form-item :label="t('routes.thinking')"><el-switch v-model="form.enableThinking"/></el-form-item>
        <el-form-item v-if="form.enableThinking" :label="t('routes.thinkingLevel')"><el-select v-model="form.thinkingLevel"><el-option v-for="level in levels" :key="level" :label="level" :value="level"/></el-select></el-form-item>
        <el-form-item :label="t('common.enabled')"><el-switch v-model="form.enabled"/></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="syncVisible" :title="t('routes.syncRoute',{name:syncRoute?.name||''})" width="720px">
      <el-alert :title="t('routes.syncScopeHint')" type="info" show-icon :closable="false"/>
      <div v-if="syncResults.length" class="route-health-results">
        <div v-for="result in syncResults" :key="result.modelId" class="route-health-result" :class="healthStatusClass(result.status)">
          <div class="route-health-summary">
            <strong>{{ result.modelName }}</strong>
            <span>{{ providerName(result.providerId) }}</span>
            <span>{{ healthLabel(result) }}</span>
          </div>
          <p v-if="result.error">{{ result.error }}</p>
          <el-button v-if="canRemoveModelProvider(result.status)" link type="danger" @click="removeModelProvider(result)">{{ t('routes.removeModelProvider') }}</el-button>
        </div>
      </div>
      <el-empty v-else-if="syncCompleted" :description="t('routes.noModelsToSync')"/>
      <template #footer>
        <el-button @click="syncVisible=false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="syncing" @click="syncCurrentRoute">{{ t('routes.startSync') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { canRemoveModelProvider, healthStatusClass } from '../utils/modelRouteHealth'

const { t } = useI18n()
const auth = useAuthStore()
const rows = ref([])
const providers = ref([])
const visible = ref(false)
const syncVisible = ref(false)
const syncing = ref(false)
const syncCompleted = ref(false)
const syncRoute = ref(null)
const syncResults = ref([])
const levels = ['LOW', 'MEDIUM', 'HIGH', 'EXTRA_HIGH', 'MAX', 'ULTRA']
const form = reactive({ id: null, featureCode: '', name: '', candidateModelIds: [], providerIds: [], capabilityLevel: 'MIDDLE', enableThinking: false, thinkingLevel: 'MEDIUM', enabled: true })

/** 加载路由和供应商目录。 */
async function load() {
  [rows.value, providers.value] = await Promise.all([
    http.get('/models/routes').then(response => response.data),
    http.get('/models/providers').then(response => response.data)
  ])
}

/** 打开路由编辑窗口并复制数组字段。 */
function open(row) {
  Object.assign(form, row ? { ...row, providerIds: [...(row.providerIds || [])] } : { id: null, featureCode: '', name: '', candidateModelIds: [], providerIds: [], capabilityLevel: 'MIDDLE', enableThinking: false, thinkingLevel: 'MEDIUM', enabled: true })
  visible.value = true
}

/** 保存当前能力路由。 */
async function save() {
  form.id ? await http.put(`/models/routes/${form.id}`, form) : await http.post('/models/routes', form)
  visible.value = false
  await load()
  ElMessage.success(t('common.successSaved'))
}

/** 打开单条路由同步窗口。 */
function openSync(row) {
  syncRoute.value = row
  syncResults.value = []
  syncCompleted.value = false
  syncVisible.value = true
}

/** 测试当前路由已配置的全部模型供应并同步到内存。 */
async function syncCurrentRoute() {
  syncing.value = true
  try {
    const response = await http.post('/models/routes/sync', { routeId: syncRoute.value.id })
    syncResults.value = response.data
    syncCompleted.value = true
    await load()
    syncRoute.value = rows.value.find(item => item.id === syncRoute.value.id) || syncRoute.value
    ElMessage.success(t('routes.syncCompleted'))
  } finally {
    syncing.value = false
  }
}

/** 返回供应商展示名称。 */
function providerName(providerId) {
  return providers.value.find(item => item.id === providerId)?.name || `#${providerId}`
}

/** 返回模型同步结果的耗时或失败文案。 */
function healthLabel(result) {
  return result.status === 'FAILED' ? t('routes.syncFailed') : t('routes.syncDuration', { duration: result.durationMs })
}

/** 从当前能力路由删除结果所属供应商。 */
async function removeModelProvider(result) {
  await ElMessageBox.confirm(t('routes.removeProviderConfirm', { provider: providerName(result.providerId), route: syncRoute.value.name }), t('routes.removeModelProvider'), { type: 'warning' })
  await http.delete(`/models/routes/${syncRoute.value.id}/providers/${result.providerId}`)
  syncResults.value = syncResults.value.filter(item => item.providerId !== result.providerId)
  await load()
  syncRoute.value = rows.value.find(item => item.id === syncRoute.value.id) || syncRoute.value
  ElMessage.success(t('routes.providerRemoved'))
}

onMounted(load)
</script>

<style scoped>
.route-sync-notice { margin-bottom: 16px; }
.route-health-results { display: grid; gap: 12px; margin-top: 18px; }
.route-health-result { padding: 14px 16px; border: 1px solid transparent; border-radius: 10px; }
.route-health-result.is-healthy { border-color: #95d475; background: #f0f9eb; }
.route-health-result.is-warning { border-color: #eebe77; background: #fdf6ec; }
.route-health-result.is-slow { border-color: #fab6b6; background: #fef0f0; }
.route-health-result.is-failed { border-color: #c45656; color: #7d1f1f; background: #f2b8b8; }
.route-health-summary { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 16px; }
.route-health-summary strong { margin-right: auto; }
.route-health-result p { margin: 8px 0 0; overflow-wrap: anywhere; }
.route-health-result .el-button { margin-top: 8px; }
</style>
