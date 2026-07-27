<template>
  <div class="panel">
    <div class="section-head"><div><h2>{{ t('models.title') }}</h2><p>{{ t('models.description') }}</p></div><el-button type="primary" @click="open()">{{ t('models.add') }}</el-button></div>
    <el-table :data="rows">
      <el-table-column prop="code" :label="t('common.code')" />
      <el-table-column prop="name" :label="t('common.name')" />
      <el-table-column prop="modelName" :label="t('models.identifier')" />
      <el-table-column :label="t('models.modelType')"><template #default="scope"><el-tag v-for="type in scope.row.supportedModelTypes" :key="type" size="small">{{ localizeModelType(type, modelTypes, t) }}</el-tag></template></el-table-column>
      <el-table-column prop="capabilityLevel" :label="t('models.capability')" />
      <el-table-column :label="t('models.thinkingLevels')" min-width="260">
        <template #default="scope">
          <div v-if="mappingEntries(scope.row.thinkingLevels).length" class="thinking-mapping-tags">
            <el-tag v-for="entry in mappingEntries(scope.row.thinkingLevels)" :key="entry.level" size="small" effect="plain">
              {{ thinkingLevelLabel(entry.level) }} → {{ entry.value }}
            </el-tag>
          </div>
          <span v-else>-</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.operation')"><template #default="s"><el-button link type="success" @click="startTest(s.row)">{{ t('models.test') }}</el-button><el-button link type="primary" @click="open(s.row)">{{ t('common.edit') }}</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? t('models.edit') : t('models.add')">
      <el-form label-width="110px">
        <el-form-item :label="t('common.code')"><el-input v-model="form.code" /></el-form-item>
        <el-form-item :label="t('common.name')"><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('models.provider')"><el-select v-model="form.providerId"><el-option v-for="item in providers" :key="item.id" :label="item.name" :value="item.id" /></el-select></el-form-item>
        <el-form-item :label="t('models.identifier')"><el-input v-model="form.modelName" /></el-form-item>
        <el-form-item :label="t('models.modelType')"><el-checkbox-group v-model="form.supportedModelTypes"><el-checkbox v-for="type in modelTypes" :key="type.value" :label="type.value">{{ localizeModelType(type.value, modelTypes, t) }}</el-checkbox></el-checkbox-group></el-form-item>
        <el-form-item :label="t('models.capability')"><el-select v-model="form.capabilityLevel"><el-option :label="t('models.low')" value="LOW" /><el-option :label="t('models.middle')" value="MIDDLE" /><el-option :label="t('models.high')" value="HIGH" /></el-select></el-form-item>
        <el-form-item :label="t('models.thinkingLevels')">
          <div class="thinking-levels">
            <div v-for="level in thinkingLevels" :key="level" class="thinking-level-row">
              <span class="thinking-level-label">{{ thinkingLevelLabel(level) }}</span>
              <el-input v-model="thinkingMapping[level]" :placeholder="t('models.thinkingValuePlaceholder')" />
            </div>
          </div>
        </el-form-item>
        <el-form-item :label="t('common.enabled')"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible = false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="testVisible" :title="t('models.test')"><el-form><el-form-item :label="t('models.thinkingLevels')"><el-select v-model="testThinkingLevel" clearable :placeholder="t('models.noThinking')"><el-option v-for="level in testLevels" :key="level" :label="thinkingLevelLabel(level)" :value="level" /></el-select></el-form-item></el-form><template #footer><el-button @click="testVisible = false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="runTest">{{ t('models.test') }}</el-button></template></el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { localizeModelType } from '../utils/localization'
import { THINKING_LEVELS, parseThinkingMappings, serializeThinkingMappings, thinkingMappingEntries } from '../utils/thinkingLevels'

const { t } = useI18n()
const rows = ref([])
const providers = ref([])
const modelTypes = ref([])
const visible = ref(false)
const testVisible = ref(false)
const testModel = ref(null)
const testLevels = ref([])
const testThinkingLevel = ref('')
const thinkingLevels = THINKING_LEVELS
const form = reactive({ id: null, code: '', name: '', providerId: null, modelName: '', supportedModelTypes: ['text_model'], capabilityLevel: 'MIDDLE', thinkingLevels: '', enabled: true })
const thinkingMapping = reactive({})

/** 将后端旧版单值或新版集合统一转换为类型编码数组。 */
function normalizeTypes(row) {
  if (Array.isArray(row?.supportedModelTypes) && row.supportedModelTypes.length) return [...row.supportedModelTypes]
  const legacy = String(row?.modelType || '').split(',').map(value => value.trim().toLowerCase()).flatMap(value => value === 'both' ? ['text_model', 'vision_model'] : [value === 'text' ? 'text_model' : value === 'vision' ? 'vision_model' : value]).filter(Boolean)
  return legacy.length ? legacy : ['text_model']
}
/** 获取思考等级的本地化名称，并保留标准编码便于识别配置。 */
function thinkingLevelLabel(level) { return `${t(`models.thinkingLevelLabels.${level}`)} (${level})` }
/** 将后端映射转换为列表使用的有序展示条目。 */
function mappingEntries(value) { return thinkingMappingEntries(value) }
async function load() {
  ;[rows.value, providers.value, modelTypes.value] = await Promise.all([
    http.get('/models').then(r => r.data),
    http.get('/models/providers').then(r => r.data),
    http.get('/models/model-types').then(r => r.data)
  ])
  rows.value.forEach(row => { row.supportedModelTypes = normalizeTypes(row) })
}
/** 打开新增或编辑窗口，并回显模型支持类型。 */
function open(row) {
  Object.assign(form, row ? { ...row, supportedModelTypes: normalizeTypes(row) } : { id: null, code: '', name: '', providerId: null, modelName: '', supportedModelTypes: modelTypes.value.length ? [modelTypes.value[0].value] : ['text_model'], capabilityLevel: 'MIDDLE', thinkingLevels: '', enabled: true })
  Object.assign(thinkingMapping, Object.fromEntries(thinkingLevels.map(level => [level, ''])), parseThinkingMappings(form.thinkingLevels))
  visible.value = true
}
/** 按后端兼容格式保存模型及其思考等级映射。 */
async function save() { form.thinkingLevels = serializeThinkingMappings(thinkingMapping); form.id ? await http.put(`/models/${form.id}`, form) : await http.post('/models', form); visible.value = false; await load(); ElMessage.success(t('common.successSaved')) }
/** 打开模型测试窗口，并仅提供当前模型已配置的思考等级。 */
function startTest(row) { testModel.value = row; testLevels.value = thinkingMappingEntries(row.thinkingLevels).map(entry => entry.level); testThinkingLevel.value = ''; if (testLevels.value.length) testVisible.value = true; else runTest() }
async function runTest() { const result = (await http.post(`/models/${testModel.value.id}/test`, null, { params: testThinkingLevel.value ? { thinkingLevel: testThinkingLevel.value } : {} })).data; testVisible.value = false; ElMessage.success(t('models.connected', { duration: result.durationMs })) }
onMounted(load)
</script>

<style scoped>
.thinking-mapping-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  padding: 4px 0;
}

.thinking-levels {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 18px;
  width: 100%;
}

.thinking-level-row {
  display: grid;
  grid-template-columns: minmax(150px, auto) minmax(0, 1fr);
  gap: 8px;
  align-items: center;
}

.thinking-level-label {
  color: var(--el-text-color-regular);
  font-size: 13px;
  white-space: nowrap;
}

@media (max-width: 768px) {
  .thinking-levels {
    grid-template-columns: 1fr;
  }
}
</style>
