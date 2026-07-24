<template>
  <div class="panel model-routes-panel">
    <div class="section-head">
      <div><h2>{{ t('routes.title') }}</h2><p>{{ t('routes.description') }}</p></div>
      <el-button v-if="auth.hasPermission('model:route:create')" type="primary" @click="open()">{{ t('routes.add') }}</el-button>
      <el-button v-if="auth.hasPermission('model:route:update')" @click="openSync()">{{ t('routes.syncRoutes') }}</el-button>
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

    <el-dialog v-model="syncVisible" :title="t('routes.syncRoutes')" width="860px">
      <el-form label-width="110px">
        <el-form-item :label="t('routes.featureCode')">
          <el-select v-model="selectedRouteIds" multiple filterable collapse-tags :placeholder="t('routes.selectFeatureCodes')">
            <el-option v-for="route in rows" :key="route.id" :label="`${route.name} (${route.featureCode})`" :value="route.id"/>
          </el-select>
        </el-form-item>
      </el-form>
      <el-alert :title="t('routes.syncScopeHint')" type="info" show-icon :closable="false"/>
      <el-tabs v-if="selectedRoutes.length" v-model="activeSyncRouteId" class="route-sync-tabs">
        <el-tab-pane v-for="route in selectedRoutes" :key="route.id" :name="String(route.id)" :label="route.featureCode">
          <el-alert v-if="syncState(route.id).error" :title="syncState(route.id).error" type="error" show-icon :closable="false"/>
          <div v-if="syncState(route.id).results.length" class="route-health-results">
            <div v-for="result in syncState(route.id).results" :key="result.modelId" class="route-health-result" :class="healthStatusClass(result.status)">
              <div class="route-health-summary">
                <strong>{{ result.modelName }}</strong>
                <span>{{ providerName(result.providerId) }}</span>
                <span>{{ healthLabel(result) }}</span>
              </div>
              <p v-if="result.error">{{ result.error }}</p>
              <el-button v-if="canRemoveModelProvider(result.status)" link type="danger" @click="removeModelProvider(route, result)">{{ t('routes.removeModelProvider') }}</el-button>
            </div>
          </div>
          <el-empty v-else-if="syncState(route.id).completed && !syncState(route.id).error" :description="t('routes.noModelsToSync')"/>
          <el-empty v-else-if="!syncState(route.id).syncing && !syncState(route.id).error" :description="t('routes.waitingSync')"/>
          <div v-if="syncState(route.id).syncing" class="route-sync-loading">{{ t('routes.syncingRoute', { code: route.featureCode }) }}</div>
        </el-tab-pane>
      </el-tabs>
      <el-empty v-else :description="t('routes.selectFeatureCodes')"/>
      <template #footer>
        <el-button @click="syncVisible=false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="syncing" :disabled="!selectedRouteIds.length" @click="syncSelectedRoutes">{{ t('routes.startSync') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
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
const selectedRouteIds = ref([])
const activeSyncRouteId = ref('')
const syncStates = reactive({})
const levels = ['LOW', 'MEDIUM', 'HIGH', 'EXTRA_HIGH', 'MAX', 'ULTRA']
const form = reactive({ id: null, featureCode: '', name: '', candidateModelIds: [], providerIds: [], capabilityLevel: 'MIDDLE', enableThinking: false, thinkingLevel: 'MEDIUM', enabled: true })

/** 按用户选择顺序返回待同步的能力路由。 */
const selectedRoutes = computed(() => selectedRouteIds.value.map(routeId => rows.value.find(route => route.id === routeId)).filter(Boolean))

/** 选择变化时初始化各路由状态并保持当前 Tab 有效。 */
watch(selectedRouteIds, routeIds => {
  routeIds.forEach(ensureSyncState)
  if (!routeIds.some(routeId => String(routeId) === activeSyncRouteId.value)) activeSyncRouteId.value = routeIds.length ? String(routeIds[0]) : ''
})

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

/** 打开路由同步窗口，行内入口默认选中当前路由。 */
function openSync(row) {
  Object.keys(syncStates).forEach(routeId => delete syncStates[routeId])
  selectedRouteIds.value = row ? [row.id] : []
  activeSyncRouteId.value = row ? String(row.id) : ''
  syncVisible.value = true
}

/** 初始化并返回指定能力路由的独立同步状态。 */
function ensureSyncState(routeId) {
  if (!syncStates[routeId]) syncStates[routeId] = { results: [], completed: false, syncing: false, error: '' }
  return syncStates[routeId]
}

/** 返回指定能力路由的同步状态。 */
function syncState(routeId) {
  return ensureSyncState(routeId)
}

/** 批量同步所选能力路由，并按路由编号分发复用后的测试结果。 */
async function syncSelectedRoutes() {
  syncing.value = true
  const routesToSync = [...selectedRoutes.value]
  routesToSync.forEach(route => Object.assign(ensureSyncState(route.id), { results: [], completed: false, syncing: true, error: '' }))
  try {
    const response = await http.post('/models/routes/sync/batch', { routeIds: selectedRouteIds.value })
    routesToSync.forEach(route => {
      const state = ensureSyncState(route.id)
      const routeResult = response.data.find(item => item.routeId === route.id)
      state.results = routeResult?.results || []
      state.completed = true
    })
    await load()
    ElMessage.success(t('routes.syncCompleted'))
  } catch (error) {
    const message = error.response?.data?.message || error.message || t('routes.syncFailed')
    routesToSync.forEach(route => Object.assign(ensureSyncState(route.id), { completed: true, error: message }))
    ElMessage.error(message)
  } finally {
    routesToSync.forEach(route => { ensureSyncState(route.id).syncing = false })
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

/** 从指定能力路由删除结果所属供应商。 */
async function removeModelProvider(route, result) {
  await ElMessageBox.confirm(t('routes.removeProviderConfirm', { provider: providerName(result.providerId), route: route.name }), t('routes.removeModelProvider'), { type: 'warning' })
  await http.delete(`/models/routes/${route.id}/providers/${result.providerId}`)
  const state = ensureSyncState(route.id)
  state.results = state.results.filter(item => item.providerId !== result.providerId)
  await load()
  ElMessage.success(t('routes.providerRemoved'))
}

onMounted(load)
</script>

<style scoped>
.route-sync-notice { margin-bottom: 16px; }
.route-sync-tabs { margin-top: 18px; }
.route-sync-loading { padding: 36px 0; color: var(--el-text-color-secondary); text-align: center; }
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
