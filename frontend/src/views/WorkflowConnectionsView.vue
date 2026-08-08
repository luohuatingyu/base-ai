<template>
  <div class="panel">
    <div class="section-head">
      <div><h2>{{ t('workflowConnections.title') }}</h2><p>{{ t('workflowConnections.description') }}</p></div>
      <el-button v-if="auth.hasPermission('workflow:connection:create')" type="primary" @click="open()">{{ t('workflowConnections.add') }}</el-button>
    </div>
    <el-alert :title="t('workflowConnections.securityNotice')" type="warning" show-icon :closable="false" />
    <el-table :data="rows" table-layout="auto">
      <el-table-column prop="code" :label="t('common.code')" min-width="150" />
      <el-table-column prop="name" :label="t('common.name')" min-width="160" />
      <el-table-column prop="connectionType" :label="t('common.type')" width="130" />
      <el-table-column :label="t('common.status')" width="100"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? t('common.enabled') : t('common.disabled') }}</el-tag></template></el-table-column>
      <el-table-column :label="t('common.operation')" width="220" fixed="right"><template #default="scope"><div class="table-actions">
        <el-button v-if="auth.hasPermission('workflow:connection:update')" link type="success" @click="test(scope.row)">{{ t('workflowConnections.test') }}</el-button>
        <el-button v-if="auth.hasPermission('workflow:connection:update')" link type="primary" @click="open(scope.row)">{{ t('common.edit') }}</el-button>
        <el-button v-if="auth.hasPermission('workflow:connection:delete')" link type="danger" @click="remove(scope.row)">{{ t('common.delete') }}</el-button>
      </div></template></el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? t('workflowConnections.edit') : t('workflowConnections.add')" width="min(980px, 94vw)">
      <el-form label-position="top">
        <div class="connection-grid"><el-form-item :label="t('common.code')"><el-input v-model="form.code" /></el-form-item><el-form-item :label="t('common.name')"><el-input v-model="form.name" /></el-form-item></div>
        <el-form-item :label="t('common.type')"><el-select v-model="form.connectionType" class="full" @change="resetConfig"><el-option v-for="type in connectionTypes" :key="type" :label="type" :value="type" /></el-select></el-form-item>
        <section class="connection-config-section">
          <div class="connection-config-head"><div><h3>{{ t('workflowConnections.config') }}</h3><p>{{ t('workflowConnections.configHelp') }}</p></div><el-tag>{{ form.connectionType }}</el-tag></div>
          <div class="connection-config-grid">
            <article v-for="field in configFields" :key="field.key" class="connection-config-card">
              <div class="connection-card-head"><strong>{{ fieldLabel(field.key) }}</strong><small>{{ fieldDescription(field.key) }}</small></div>
              <div class="connection-card-body">
                <el-input v-if="['text', 'password'].includes(field.editor)" :model-value="form.config[field.key]"
                          :type="field.editor" :show-password="field.editor === 'password'" autocomplete="off"
                          @update:model-value="setConfigField(field.key, $event)" />
                <el-switch v-else-if="field.editor === 'boolean'" :model-value="form.config[field.key]"
                           @update:model-value="setConfigField(field.key, $event)" />
                <el-select v-else-if="field.editor === 'select'" :model-value="form.config[field.key]" class="full"
                           @update:model-value="setConfigField(field.key, $event)">
                  <el-option v-for="option in field.options" :key="option || '__empty'" :label="option || t('workflowConnections.notSet')" :value="option" />
                </el-select>
                <template v-else-if="field.editor === 'keyValue'">
                  <div class="connection-key-values">
                    <div v-for="([key, value]) in mapEntries(field.key)" :key="key" class="connection-key-value">
                      <strong>{{ key }}</strong>
                      <el-input :model-value="value" @update:model-value="setMapValue(field.key, key, $event)" />
                      <el-button link type="danger" @click="removeMapValue(field.key, key)">{{ t('common.delete') }}</el-button>
                    </div>
                    <div class="connection-key-value connection-key-value--add">
                      <el-input v-model="mapDraft.key" :placeholder="t('workflowConnections.customKey')" />
                      <el-input v-model="mapDraft.value" :placeholder="t('workflowConnections.customValue')" @keyup.enter="addMapValue(field.key)" />
                      <el-button type="primary" plain @click="addMapValue(field.key)">{{ t('common.add') }}</el-button>
                    </div>
                    <small v-if="mapError" class="connection-error">{{ mapError }}</small>
                  </div>
                </template>
              </div>
            </article>
          </div>
        </section>
        <div class="form-help">{{ t('workflowConnections.maskHelp') }}</div>
        <section class="connection-custom-section">
          <div class="connection-config-head"><div><h3>{{ t('workflowConnections.customTitle') }}</h3><p>{{ t('workflowConnections.customHelp') }}</p></div><el-tag type="info">{{ customKeys.length }}</el-tag></div>
          <div class="connection-custom-list">
            <article v-for="key in customKeys" :key="key" class="connection-custom-card">
              <div class="connection-custom-head"><strong>{{ key }}</strong><el-button link type="danger" @click="removeCustomField(key)">{{ t('common.delete') }}</el-button></div>
              <WorkflowConfigValueEditor :model-value="form.config[key]" @update:model-value="setConfigField(key, $event)" />
            </article>
            <div class="connection-custom-add">
              <el-input v-model="customKey" :placeholder="t('workflowConnections.customKey')" @keyup.enter="addCustomField" />
              <el-select v-model="customType"><el-option v-for="type in CONFIG_VALUE_TYPES" :key="type" :label="t(`workflowConfig.valueTypes.${type}`)" :value="type" /></el-select>
              <el-button type="primary" plain @click="addCustomField">{{ t('workflowConnections.addCustom') }}</el-button>
            </div>
            <small v-if="customError" class="connection-error">{{ customError }}</small>
          </div>
        </section>
        <el-form-item :label="t('common.status')"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import WorkflowConfigValueEditor from '../components/WorkflowConfigValueEditor.vue'
import { useAuthStore } from '../stores/auth'
import { CONFIG_VALUE_TYPES, createConfigValue, isSafeConfigKey } from '../utils/workflowNodeConfig'
import { cloneConnectionConfig, CONNECTION_TYPES, connectionConfigFields, createConnectionConfig, extraConnectionConfigKeys } from '../utils/workflowConnectionConfig'

const { t, te } = useI18n()
const auth = useAuthStore()
const rows = ref([]), visible = ref(false)
const connectionTypes = CONNECTION_TYPES
const form = reactive(emptyForm())
const mapDraft = reactive({ key: '', value: '' })
const mapError = ref(''), customKey = ref(''), customType = ref('string'), customError = ref('')
const configFields = computed(() => connectionConfigFields(form.connectionType))
const customKeys = computed(() => extraConnectionConfigKeys(form.config, form.connectionType))

/** 加载当前用户可见的脱敏连接。 */
async function load() { rows.value = (await http.get('/workflow/connections')).data || [] }
/** 打开连接编辑器并保留服务端脱敏占位符。 */
function open(row) {
  const connectionType = row?.connectionType || 'MYSQL'
  Object.assign(form, emptyForm(), row || {}, { connectionType, config: createConnectionConfig(connectionType, row?.config) })
  clearDrafts(); visible.value = true
}
/** 切换类型时使用安全默认配置。 */
function resetConfig(type) { form.config = createConnectionConfig(type); clearDrafts() }
/** 创建或更新结构化连接配置。 */
async function save() {
  if (!form.code.trim() || !form.name.trim()) return ElMessage.warning(t('workflowConnections.required'))
  const command = { code: form.code, name: form.name, connectionType: form.connectionType, config: cloneConnectionConfig(form.config), enabled: form.enabled }
  try {
    if (form.id) await http.put(`/workflow/connections/${form.id}`, command)
    else await http.post('/workflow/connections', command)
    visible.value = false; await load(); ElMessage.success(t('common.successSaved'))
  } catch (error) { showHttpError(error, 'common.saveFailed') }
}
/** 执行无副作用连接测试。 */
async function test(row) { try { await http.post(`/workflow/connections/${row.id}/test`); ElMessage.success(t('workflowConnections.connected')) } catch (error) { showHttpError(error, 'workflowConnections.testFailed') } }
/** 删除未被工作流引用的连接。 */
async function remove(row) { try { await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name }), t('common.deleteConfirm')); await http.delete(`/workflow/connections/${row.id}`); await load() } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) } }
/** 返回当前语言下的连接字段名称。 */
function fieldLabel(key) { const path = `workflowConnections.fields.${key}`; return te(path) ? t(path) : key }
/** 返回当前语言下的连接字段说明。 */
function fieldDescription(key) { const path = `workflowConnections.fieldDescriptions.${key}`; return te(path) ? t(path) : key }
/** 更新一个标准或自定义配置字段。 */
function setConfigField(key, value) { form.config = { ...form.config, [key]: value } }
/** 返回对象型配置的键值列表。 */
function mapEntries(key) { return Object.entries(form.config[key] || {}) }
/** 更新对象型配置中的值。 */
function setMapValue(fieldKey, key, value) { setConfigField(fieldKey, { ...(form.config[fieldKey] || {}), [key]: value }) }
/** 删除对象型配置中的键值。 */
function removeMapValue(fieldKey, key) { const next = { ...(form.config[fieldKey] || {}) }; delete next[key]; setConfigField(fieldKey, next) }
/** 校验后向对象型配置添加键值。 */
function addMapValue(fieldKey) {
  const key = mapDraft.key.trim()
  if (!isSafeConfigKey(key)) { mapError.value = t('workflowConnections.invalidCustomKey'); return }
  if (Object.prototype.hasOwnProperty.call(form.config[fieldKey] || {}, key)) { mapError.value = t('workflowConnections.duplicateCustomKey'); return }
  setMapValue(fieldKey, key, mapDraft.value); mapDraft.key = ''; mapDraft.value = ''; mapError.value = ''
}
/** 校验后添加自定义配置卡片。 */
function addCustomField() {
  const key = customKey.value.trim()
  if (!isSafeConfigKey(key)) { customError.value = t('workflowConnections.invalidCustomKey'); return }
  if (Object.prototype.hasOwnProperty.call(form.config, key)) { customError.value = t('workflowConnections.duplicateCustomKey'); return }
  setConfigField(key, createConfigValue(customType.value)); customKey.value = ''; customError.value = ''
}
/** 删除自定义配置卡片。 */
function removeCustomField(key) { const next = { ...form.config }; delete next[key]; form.config = next }
/** 清空类型切换和弹窗复用产生的临时输入。 */
function clearDrafts() { mapDraft.key = ''; mapDraft.value = ''; mapError.value = ''; customKey.value = ''; customType.value = 'string'; customError.value = '' }
/** 创建空连接表单。 */
function emptyForm() { return { id: null, code: '', name: '', connectionType: 'MYSQL', config: createConnectionConfig('MYSQL'), enabled: true } }
onMounted(load)
</script>

<style scoped>
.panel { display: grid; gap: 18px; }
.connection-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-help { margin: -8px 0 18px; color: var(--app-muted); font-size: 13px; }
.connection-config-section, .connection-custom-section { display: flex; flex-direction: column; gap: 14px; margin-bottom: 18px; }
.connection-config-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.connection-config-head h3, .connection-config-head p { margin: 0; }
.connection-config-head p { margin-top: 4px; color: var(--app-muted); font-size: 13px; }
.connection-config-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.connection-config-card, .connection-custom-card { min-width: 0; padding: 14px; border: 1px solid #dfe7f2; border-radius: 12px; background: #f8fafc; }
.connection-card-head { display: flex; flex-direction: column; gap: 4px; margin-bottom: 12px; }
.connection-card-head small { color: var(--app-muted); line-height: 1.45; }
.connection-card-body { min-width: 0; }
.connection-key-values, .connection-custom-list { display: flex; flex-direction: column; gap: 10px; }
.connection-key-value { display: grid; grid-template-columns: minmax(120px, .8fr) minmax(160px, 1.2fr) auto; align-items: center; gap: 8px; }
.connection-key-value strong { overflow-wrap: anywhere; }
.connection-custom-card { background: #fff; }
.connection-custom-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.connection-custom-head strong { overflow-wrap: anywhere; }
.connection-custom-add { display: grid; grid-template-columns: minmax(160px, 1fr) 150px auto; gap: 8px; }
.connection-error { color: var(--el-color-danger); }
@media (max-width: 720px) {
  .connection-grid, .connection-config-grid, .connection-key-value, .connection-custom-add { grid-template-columns: 1fr; }
  .connection-grid { gap: 0; }
  .connection-key-value { align-items: stretch; }
}
</style>
