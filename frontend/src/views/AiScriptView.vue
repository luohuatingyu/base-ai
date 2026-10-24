<template>
  <div class="ai-novel-page">
    <section class="ai-novel-hero panel">
      <div>
        <span class="ai-novel-eyebrow">AI SCRIPT WORKSPACE</span>
        <h2>{{ t('aiNovel.title') }}</h2>
        <p>{{ t('aiNovel.subtitle') }}</p>
      </div>
      <div class="ai-novel-hero-actions">
        <el-tag effect="plain" type="success">{{ t('aiNovel.singleUser') }}</el-tag>
        <el-button type="primary" :disabled="busy" @click="openNovel()">{{ t('aiNovel.newNovel') }}</el-button>
      </div>
    </section>

    <div class="ai-novel-layout">
      <aside class="panel ai-novel-sidebar">
        <div class="ai-novel-panel-heading">
          <div><h3>{{ t('aiNovel.works') }}</h3><span>{{ novels.length }}</span></div>
          <el-button text type="primary" :disabled="busy" @click="openNovel()">{{ t('aiNovel.add') }}</el-button>
        </div>
        <el-empty v-if="!novels.length" :description="t('aiNovel.noWorks')" :image-size="70" />
        <div v-else class="ai-novel-work-list">
          <button v-for="novel in novels" :key="novel.id" type="button" class="ai-novel-work-item" :class="{ active: novel.id === activeNovelId }" @click="selectNovel(novel.id)">
            <strong>{{ novel.title }}</strong>
            <span>{{ t('aiNovel.chapterCount', { count: novel.chapterCount || novel.chapters?.length || 0 }) }}</span>
          </button>
        </div>
      </aside>

      <main v-if="activeNovel" class="ai-novel-main">
        <section class="panel ai-novel-meta-panel">
          <div class="ai-novel-panel-heading">
            <div><h3>{{ t('aiNovel.workInfo') }}</h3><p>{{ t('aiNovel.workInfoHint') }}</p></div>
            <div class="ai-novel-heading-actions">
              <el-button text type="danger" :disabled="busy || saving" @click="removeNovel">{{ t('common.delete') }}</el-button>
              <el-button type="primary" :loading="saving" :disabled="busy" @click="saveNovel">{{ t('common.save') }}</el-button>
            </div>
          </div>
          <el-form label-position="top" :model="activeNovel">
            <div class="ai-novel-meta-grid">
              <el-form-item :label="t('aiNovel.workTitle')" required><el-input v-model="activeNovel.title" maxlength="200" /></el-form-item>
              <el-form-item :label="t('aiNovel.status')"><el-select v-model="activeNovel.status" class="full-field"><el-option v-for="item in statuses" :key="item" :label="t(`aiNovel.statuses.${item}`)" :value="item" /></el-select></el-form-item>
              <el-form-item :label="t('aiNovel.genre')"><el-input v-model="activeNovel.genre" maxlength="80" /></el-form-item>
              <el-form-item :label="t('aiNovel.tone')"><el-input v-model="activeNovel.tone" maxlength="120" /></el-form-item>
            </div>
            <el-form-item :label="t('aiNovel.logline')"><el-input v-model="activeNovel.logline" maxlength="1000" /></el-form-item>
            <el-form-item :label="t('aiNovel.outline')"><el-input v-model="activeNovel.outline" type="textarea" :rows="4" maxlength="100000" show-word-limit /></el-form-item>
          </el-form>
        </section>

        <div class="ai-novel-editor-layout">
          <section class="panel ai-novel-chapters-panel">
            <div class="ai-novel-panel-heading">
              <div><h3>{{ t('aiNovel.chapters') }}</h3><span>{{ activeNovel.chapters?.length || 0 }}</span></div>
              <el-button text type="primary" :disabled="busy" @click="openChapter()">{{ t('aiNovel.addChapter') }}</el-button>
            </div>
            <el-empty v-if="!activeNovel.chapters?.length" :description="t('aiNovel.noChapters')" :image-size="70" />
            <div v-else class="ai-novel-chapter-list">
              <button v-for="chapter in activeNovel.chapters" :key="chapter.id" type="button" class="ai-novel-chapter-item" :class="{ active: chapter.id === activeChapterId }" @click="selectChapter(chapter.id)">
                <span>{{ String(chapter.chapterNo).padStart(2, '0') }}</span><strong>{{ chapter.title }}</strong><small>{{ chapter.wordCount || wordCount(chapter.content) }} {{ t('aiNovel.words') }}</small>
              </button>
            </div>
          </section>

          <section class="panel ai-novel-editor-panel">
            <template v-if="activeChapter">
              <div class="ai-novel-panel-heading">
                <div><h3>{{ t('aiNovel.editor') }}</h3><p>{{ t('aiNovel.editorHint') }}</p></div>
                <div class="ai-novel-heading-actions"><el-tag v-if="isChapterDirty" effect="plain" type="warning">{{ t('aiNovel.unsaved') }}</el-tag><el-button type="primary" :loading="savingChapter" :disabled="busy" @click="saveChapter">{{ t('common.save') }}</el-button></div>
              </div>
              <div class="ai-novel-chapter-fields">
                <el-input v-model="activeChapter.title" :placeholder="t('aiNovel.chapterTitle')" maxlength="200" />
                <el-input v-model="activeChapter.synopsis" type="textarea" :rows="2" :placeholder="t('aiNovel.chapterSynopsis')" maxlength="1000" />
              </div>
              <el-input v-model="activeChapter.content" class="ai-novel-content-editor" type="textarea" :rows="18" maxlength="200000" show-word-limit :placeholder="t('aiNovel.contentPlaceholder')" />
              <div class="ai-novel-editor-footer"><span>{{ wordCount(activeChapter.content) }} {{ t('aiNovel.words') }}</span><el-button text type="danger" :disabled="busy" @click="removeChapter">{{ t('common.delete') }}</el-button></div>
            </template>
            <el-empty v-else :description="t('aiNovel.selectChapter')" :image-size="86" />
          </section>
        </div>

        <section class="panel ai-novel-tools-panel">
          <div class="ai-novel-panel-heading"><div><h3>{{ t('aiNovel.aiWriting') }}</h3><p>{{ t('aiNovel.aiWritingHint') }}</p></div><el-tag effect="plain" type="success">AI</el-tag></div>
          <div class="ai-novel-tool-grid">
            <el-select v-model="writerOperation" :disabled="busy || saving" class="ai-novel-operation-select"><el-option v-for="item in operations" :key="item" :label="t(`aiNovel.operations.${item}`)" :value="item" /></el-select>
            <el-input v-model="writerInstruction" :disabled="busy || saving" :placeholder="t('aiNovel.instructionPlaceholder')" maxlength="10000" />
            <el-button type="primary" :loading="busy" :disabled="!canWrite" @click="runWriter(writerOperation)">{{ t('aiNovel.runWriter') }}</el-button>
          </div>
          <div v-if="aiResult" class="ai-novel-result">
            <div class="ai-novel-result-heading"><strong>{{ t('aiNovel.aiResult') }}</strong><el-button type="success" plain :disabled="busy" @click="applyAiResult">{{ t('aiNovel.applyResult') }}</el-button></div>
            <pre>{{ aiResult }}</pre>
          </div>
        </section>

        <section class="panel ai-novel-bottom-grid">
          <div>
            <div class="ai-novel-panel-heading"><div><h3>{{ t('aiNovel.references') }}</h3><p>{{ t('aiNovel.referencesHint') }}</p></div><el-button text type="primary" @click="openReference()">{{ t('aiNovel.addReference') }}</el-button></div>
            <el-empty v-if="!activeNovel.references?.length" :description="t('aiNovel.noReferences')" :image-size="60" />
            <div v-else class="ai-novel-reference-list"><article v-for="reference in activeNovel.references" :key="reference.id"><div><strong>{{ reference.name }}</strong><el-tag size="small" effect="plain">{{ t(`aiNovel.referenceTypes.${reference.referenceType}`) }}</el-tag></div><p>{{ reference.content }}</p><el-button link type="primary" @click="openReference(reference)">{{ t('common.edit') }}</el-button></article></div>
          </div>
          <div class="ai-novel-extract-card"><div class="ai-novel-panel-heading"><div><h3>{{ t('aiNovel.extractTitle') }}</h3><p>{{ t('aiNovel.extractHint') }}</p></div></div><el-button type="primary" :disabled="busy || !activeNovel.chapters?.length" @click="openExtract">{{ t('aiNovel.extract') }}</el-button></div>
        </section>
      </main>

      <el-empty v-else class="ai-novel-empty-main" :description="t('aiNovel.startHint')" :image-size="110"><el-button type="primary" @click="openNovel()">{{ t('aiNovel.newNovel') }}</el-button></el-empty>
    </div>

    <el-dialog v-model="novelDialogVisible" :title="t('aiNovel.newNovel')" width="min(560px, 94vw)" destroy-on-close>
      <el-form label-position="top" :model="novelForm"><el-form-item :label="t('aiNovel.workTitle')" required><el-input v-model="novelForm.title" maxlength="200" /></el-form-item><el-form-item :label="t('aiNovel.logline')"><el-input v-model="novelForm.logline" type="textarea" :rows="3" maxlength="1000" /></el-form-item></el-form>
      <template #footer><el-button @click="novelDialogVisible = false">{{ t('common.cancel') }}</el-button><el-button type="primary" :loading="saving" @click="createNovel">{{ t('common.save') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="chapterDialogVisible" :title="t('aiNovel.addChapter')" width="min(560px, 94vw)" destroy-on-close>
      <el-form label-position="top" :model="chapterForm"><el-form-item :label="t('aiNovel.chapterTitle')" required><el-input v-model="chapterForm.title" maxlength="200" /></el-form-item><el-form-item :label="t('aiNovel.chapterSynopsis')"><el-input v-model="chapterForm.synopsis" type="textarea" :rows="3" maxlength="1000" /></el-form-item></el-form>
      <template #footer><el-button @click="chapterDialogVisible = false">{{ t('common.cancel') }}</el-button><el-button type="primary" :loading="saving" @click="createChapter">{{ t('common.save') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="referenceDialogVisible" :title="referenceEditingId ? t('aiNovel.editReference') : t('aiNovel.addReference')" width="min(620px, 94vw)" destroy-on-close>
      <el-form label-position="top" :model="referenceForm"><el-form-item :label="t('aiNovel.referenceType')"><el-select v-model="referenceForm.referenceType" class="full-field"><el-option v-for="item in referenceTypes" :key="item" :label="t(`aiNovel.referenceTypes.${item}`)" :value="item" /></el-select></el-form-item><el-form-item :label="t('aiNovel.referenceName')" required><el-input v-model="referenceForm.name" maxlength="200" /></el-form-item><el-form-item :label="t('aiNovel.referenceContent')" required><el-input v-model="referenceForm.content" type="textarea" :rows="8" maxlength="200000" /></el-form-item></el-form>
      <template #footer><el-button @click="referenceDialogVisible = false">{{ t('common.cancel') }}</el-button><el-button type="primary" :loading="saving" @click="saveReference">{{ t('common.save') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="extractDialogVisible" :title="t('aiNovel.extractTitle')" width="min(620px, 94vw)" destroy-on-close>
      <el-form label-position="top" :model="extractForm"><el-form-item :label="t('aiNovel.videoProject')" required><el-select v-model="extractForm.projectId" filterable class="full-field" :placeholder="t('aiNovel.videoProjectPlaceholder')"><el-option v-for="project in projects" :key="project.id" :label="`${project.code || project.id} · ${project.name}`" :value="project.id" /></el-select></el-form-item><el-form-item :label="t('aiNovel.sourceChapters')" required><el-select v-model="extractForm.chapterIds" multiple class="full-field"><el-option v-for="chapter in activeNovel?.chapters || []" :key="chapter.id" :label="`${chapter.chapterNo}. ${chapter.title}`" :value="chapter.id" /></el-select></el-form-item><el-form-item :label="t('aiNovel.sceneCount')"><el-input-number v-model="extractForm.sceneCount" :min="1" :max="24" controls-position="right" /></el-form-item></el-form>
      <template #footer><el-button @click="extractDialogVisible = false">{{ t('common.cancel') }}</el-button><el-button type="primary" :loading="busy" @click="extractToVideo">{{ t('aiNovel.extract') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { createAiNovel, createAiNovelChapter, createAiNovelReference, deleteAiNovel, deleteAiNovelChapter, extractAiNovelToVideo, getAiNovel, listAiNovels, runAiNovelWriter, updateAiNovel, updateAiNovelChapter, updateAiNovelReference } from '../api/aiNovel'
import { listAiVideoResource } from '../api/aiVideo'
import { showHttpError } from '../api/http'

const { t } = useI18n()
const router = useRouter()
const novels = ref([])
const projects = ref([])
const activeNovel = ref(null)
const activeNovelId = ref(null)
const activeChapterId = ref(null)
const busy = ref(false)
const saving = ref(false)
const savingChapter = ref(false)
const novelDialogVisible = ref(false)
const chapterDialogVisible = ref(false)
const referenceDialogVisible = ref(false)
const extractDialogVisible = ref(false)
const referenceEditingId = ref(null)
const writerOperation = ref('CHAPTER')
const writerInstruction = ref('')
const aiResult = ref('')
const aiResultContext = ref(null)
const chapterSnapshot = ref('')
const novelForm = reactive({ title: '', logline: '', genre: '', tone: '', language: 'zh-CN' })
const chapterForm = reactive({ title: '', synopsis: '', content: '' })
const referenceForm = reactive({ referenceType: 'CHARACTER', name: '', content: '', sortOrder: 0 })
const extractForm = reactive({ projectId: null, chapterIds: [], sceneCount: 6 })
const statuses = ['DRAFT', 'ONGOING', 'COMPLETED', 'ARCHIVED']
const operations = ['OUTLINE', 'CHAPTER', 'CONTINUE', 'REWRITE', 'POLISH', 'SUMMARY']
const referenceTypes = ['CHARACTER', 'WORLD', 'STYLE', 'RESEARCH']

const activeChapter = computed(() => activeNovel.value?.chapters?.find(item => item.id === activeChapterId.value) || null)
const isChapterDirty = computed(() => activeChapter.value ? chapterSnapshot.value !== serializeChapter(activeChapter.value) : false)
const canWrite = computed(() => Boolean(activeNovel.value && (writerOperation.value === 'OUTLINE' || activeChapter.value)) && !busy.value && !saving.value)

/** 将章节的可编辑字段序列化，用于识别未保存草稿。 */
function serializeChapter(chapter) { return JSON.stringify({ title: chapter?.title || '', synopsis: chapter?.synopsis || '', content: chapter?.content || '', status: chapter?.status || 'DRAFT', chapterNo: chapter?.chapterNo || 1 }) }

/** 读取兼容 Axios 响应和测试替身的 data 字段。 */
function responseData(response) { return response?.data ?? response }

/** 统一处理列表响应，避免后端空响应影响编辑器初始化。 */
function responseList(response) { const value = responseData(response); return Array.isArray(value) ? value : [] }

/** 计算中文字符与英文词语的近似字数。 */
function wordCount(content) { const value = String(content || '').trim(); return value ? value.split(/\s+/).length + [...value].filter(character => /[\u3400-\u9fff]/u.test(character)).length : 0 }

/** 加载作品列表并自动打开最近编辑的作品。 */
async function loadNovels() {
  try {
    novels.value = responseList(await listAiNovels())
    if (!novels.value.length) { activeNovel.value = null; activeNovelId.value = null; activeChapterId.value = null; return }
    const selected = novels.value.find(item => item.id === activeNovelId.value) || novels.value[0]
    await selectNovel(selected.id, true)
  } catch (error) { showHttpError(error, 'common.loadingFailed') }
}

/** 加载可用于提取的 AI 视频项目。 */
async function loadProjects() {
  try { projects.value = responseList(await listAiVideoResource('projects')) } catch (error) { showHttpError(error, 'common.loadingFailed') }
}

/** 加载完整作品详情并建立章节快照。 */
async function selectNovel(id, force = false) {
  if (busy.value || (!force && activeNovelId.value === id)) return
  if (!force && isChapterDirty.value && !(await confirmDiscard())) return
  try {
    const detail = responseData(await getAiNovel(id))
    activeNovel.value = { ...detail, chapters: detail.chapters || [], references: detail.references || [] }
    activeNovelId.value = id
    activeChapterId.value = activeNovel.value.chapters[0]?.id || null
    updateChapterSnapshot()
    aiResult.value = ''
    aiResultContext.value = null
  } catch (error) { showHttpError(error, 'common.loadingFailed') }
}

/** 切换章节前确认是否放弃当前草稿。 */
async function selectChapter(id) {
  if (busy.value || id === activeChapterId.value) return
  if (isChapterDirty.value && !(await confirmDiscard())) return
  activeChapterId.value = id
  updateChapterSnapshot()
  aiResult.value = ''
  aiResultContext.value = null
}

/** 弹出放弃草稿确认框，关闭或取消时保留原内容。 */
async function confirmDiscard() {
  try { await ElMessageBox.confirm(t('aiNovel.discardConfirm'), t('common.confirm')); return true } catch { return false }
}

/** 记录当前章节已保存状态。 */
function updateChapterSnapshot() { chapterSnapshot.value = serializeChapter(activeChapter.value) }

/** 打开新作品表单。 */
function openNovel() { Object.assign(novelForm, { title: '', logline: '', genre: '', tone: '', language: 'zh-CN' }); novelDialogVisible.value = true }

/** 创建作品并打开详情。 */
async function createNovel() {
  if (!novelForm.title.trim()) return ElMessage.warning(t('aiNovel.titleRequired'))
  saving.value = true
  try { const created = responseData(await createAiNovel({ ...novelForm })); novelDialogVisible.value = false; novels.value.unshift(created); await selectNovel(created.id, true); ElMessage.success(t('common.successSaved')) } catch (error) { showHttpError(error, 'common.saveFailed') } finally { saving.value = false }
}

/** 保存作品元数据，保留章节编辑器中的本地草稿。 */
async function saveNovel() {
  if (!activeNovel.value) return false
  saving.value = true
  const chapters = activeNovel.value.chapters
  const references = activeNovel.value.references
  try {
    const payload = { title: activeNovel.value.title, logline: activeNovel.value.logline, genre: activeNovel.value.genre, tone: activeNovel.value.tone, language: activeNovel.value.language || 'zh-CN', outline: activeNovel.value.outline, status: activeNovel.value.status || 'DRAFT' }
    const saved = responseData(await updateAiNovel(activeNovel.value.id, payload))
    Object.assign(activeNovel.value, { ...saved, ...payload, id: activeNovel.value.id })
    activeNovel.value.chapters = chapters
    activeNovel.value.references = references
    novels.value = novels.value.map(item => item.id === activeNovel.value.id ? { ...item, ...payload } : item)
    ElMessage.success(t('common.successSaved'))
    return true
  } catch (error) { showHttpError(error, 'common.saveFailed'); return false } finally { saving.value = false }
}

/** 删除当前作品并回到作品列表。 */
async function removeNovel() {
  if (!activeNovel.value) return
  try { await ElMessageBox.confirm(t('common.confirmDelete', { name: activeNovel.value.title }), t('common.deleteConfirm')); await deleteAiNovel(activeNovel.value.id); await loadNovels(); ElMessage.success(t('common.successDeleted')) } catch (error) { if (!['cancel', 'close'].includes(error)) showHttpError(error) }
}

/** 打开新增章节表单。 */
function openChapter() { Object.assign(chapterForm, { title: '', synopsis: '', content: '' }); chapterDialogVisible.value = true }

/** 创建章节并把它设为当前编辑目标。 */
async function createChapter() {
  if (!activeNovel.value || !chapterForm.title.trim()) return ElMessage.warning(t('aiNovel.chapterTitleRequired'))
  saving.value = true
  try { const created = responseData(await createAiNovelChapter(activeNovel.value.id, { ...chapterForm, chapterNo: (activeNovel.value.chapters?.length || 0) + 1 })); activeNovel.value.chapters.push(created); chapterDialogVisible.value = false; activeChapterId.value = created.id; updateChapterSnapshot(); ElMessage.success(t('common.successSaved')) } catch (error) { showHttpError(error, 'common.saveFailed') } finally { saving.value = false }
}

/** 保存当前章节正文、摘要和标题。 */
async function saveChapter() {
  if (!activeNovel.value || !activeChapter.value) return true
  savingChapter.value = true
  const chapter = activeChapter.value
  const payload = { parentId: chapter.parentId || null, chapterNo: chapter.chapterNo, title: chapter.title, synopsis: chapter.synopsis, content: chapter.content, status: chapter.status || 'DRAFT' }
  try { const saved = responseData(await updateAiNovelChapter(activeNovel.value.id, chapter.id, payload)); Object.assign(chapter, { ...saved, ...payload, id: chapter.id, wordCount: wordCount(payload.content) }); updateChapterSnapshot(); return true } catch (error) { showHttpError(error, 'common.saveFailed'); return false } finally { savingChapter.value = false }
}

/** 删除当前章节并选择相邻章节。 */
async function removeChapter() {
  if (!activeNovel.value || !activeChapter.value) return
  try { await ElMessageBox.confirm(t('common.confirmDelete', { name: activeChapter.value.title }), t('common.deleteConfirm')); const index = activeNovel.value.chapters.findIndex(item => item.id === activeChapter.value.id); await deleteAiNovelChapter(activeNovel.value.id, activeChapter.value.id); activeNovel.value.chapters.splice(index, 1); activeChapterId.value = activeNovel.value.chapters[Math.max(0, index - 1)]?.id || null; updateChapterSnapshot() } catch (error) { if (!['cancel', 'close'].includes(error)) showHttpError(error) }
}

/** 保存草稿后调用 AI 写作，并把结果绑定到发起请求时的目标。 */
async function runWriter(operation) {
  if (busy.value || !activeNovel.value) return
  if (operation !== 'OUTLINE' && !activeChapter.value) return ElMessage.warning(t('aiNovel.selectChapter'))
  const novelId = activeNovel.value.id
  const chapterId = activeChapter.value?.id || null
  const saved = operation === 'OUTLINE' ? await saveNovel() : await saveChapter()
  if (!saved) return
  busy.value = true
  aiResultContext.value = { novelId, chapterId, operation }
  try { const result = responseData(await runAiNovelWriter(novelId, { operation, chapterId, instruction: writerInstruction.value })); aiResult.value = result?.content || ''; ElMessage.success(t('aiNovel.writerReady')) } catch (error) { aiResult.value = ''; showHttpError(error, 'aiNovel.writerFailed') } finally { busy.value = false }
}

/** 将 AI 结果应用到原始章节或作品大纲，防止切换目标后误写入。 */
function applyAiResult() {
  const context = aiResultContext.value
  if (!context || !aiResult.value || context.novelId !== activeNovel.value?.id || context.chapterId !== (activeChapter.value?.id || null)) return
  if (context.operation === 'OUTLINE') activeNovel.value.outline = aiResult.value
  else if (context.operation === 'SUMMARY') activeChapter.value.synopsis = aiResult.value
  else if (context.operation === 'CONTINUE') activeChapter.value.content = [activeChapter.value.content, aiResult.value].filter(Boolean).join('\n\n')
  else activeChapter.value.content = aiResult.value
}

/** 打开参考资料编辑器并载入已有资料。 */
function openReference(reference = null) { referenceEditingId.value = reference?.id || null; Object.assign(referenceForm, { referenceType: reference?.referenceType || 'CHARACTER', name: reference?.name || '', content: reference?.content || '', sortOrder: reference?.sortOrder || 0 }); referenceDialogVisible.value = true }

/** 保存人物、世界观、文风或研究资料。 */
async function saveReference() {
  if (!activeNovel.value || !referenceForm.name.trim() || !referenceForm.content.trim()) return ElMessage.warning(t('aiNovel.referenceRequired'))
  saving.value = true
  try {
    const payload = { ...referenceForm }
    const saved = responseData(referenceEditingId.value ? await updateAiNovelReference(activeNovel.value.id, referenceEditingId.value, payload) : await createAiNovelReference(activeNovel.value.id, payload))
    const index = activeNovel.value.references.findIndex(item => item.id === referenceEditingId.value)
    if (index >= 0) activeNovel.value.references.splice(index, 1, { ...activeNovel.value.references[index], ...saved, ...payload })
    else activeNovel.value.references.push({ ...saved, ...payload })
    referenceDialogVisible.value = false
    ElMessage.success(t('common.successSaved'))
  } catch (error) { showHttpError(error, 'common.saveFailed') } finally { saving.value = false }
}

/** 打开提取到视频表单，默认选中当前章节。 */
function openExtract() { extractForm.chapterIds = activeChapter.value ? [activeChapter.value.id] : []; extractDialogVisible.value = true; if (!projects.value.length) loadProjects() }

/** 保存选中章节后提取视频改编剧本，并跳转到 AI 视频剧本管理。 */
async function extractToVideo() {
  if (!activeNovel.value || !extractForm.projectId || !extractForm.chapterIds.length) return ElMessage.warning(t('aiNovel.extractRequired'))
  const saved = activeChapter.value && extractForm.chapterIds.includes(activeChapter.value.id) ? await saveChapter() : true
  if (!saved) return
  busy.value = true
  try { await extractAiNovelToVideo(activeNovel.value.id, { projectId: extractForm.projectId, chapterIds: [...extractForm.chapterIds], sceneCount: extractForm.sceneCount }); extractDialogVisible.value = false; ElMessage.success(t('aiNovel.extractSuccess')); router.push('/ai-video/scripts') } catch (error) { showHttpError(error, 'aiNovel.extractFailed') } finally { busy.value = false }
}

/** 页面首次进入时加载作品和视频项目。 */
onMounted(() => { loadNovels(); loadProjects() })
</script>

<style scoped>
.ai-novel-page { display: flex; flex-direction: column; gap: 20px; min-width: 0; }
.ai-novel-hero { display: flex; align-items: center; justify-content: space-between; gap: 24px; padding: 28px 30px; background: linear-gradient(135deg, #ffffff 0%, #edf4ff 100%); }
.ai-novel-eyebrow { color: #2563eb; font-size: 12px; font-weight: 800; letter-spacing: .18em; }
.ai-novel-hero h2 { margin: 10px 0 8px; font-size: 30px; letter-spacing: -.03em; }
.ai-novel-hero p { margin: 0; color: #667085; line-height: 1.7; }
.ai-novel-hero-actions, .ai-novel-heading-actions { display: flex; align-items: center; gap: 10px; }
.ai-novel-layout { display: grid; grid-template-columns: 260px minmax(0, 1fr); gap: 20px; align-items: start; }
.ai-novel-sidebar { position: sticky; top: 20px; min-height: 420px; padding: 18px; }
.ai-novel-main { display: flex; min-width: 0; flex-direction: column; gap: 20px; }
.ai-novel-panel-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; margin-bottom: 18px; }
.ai-novel-panel-heading > div:first-child { min-width: 0; }
.ai-novel-panel-heading h3 { margin: 0; font-size: 18px; }
.ai-novel-panel-heading p { margin: 5px 0 0; color: #7b8798; font-size: 13px; line-height: 1.5; }
.ai-novel-panel-heading > div > span { display: inline-block; margin-top: 6px; color: #7b8798; font-size: 12px; }
.ai-novel-work-list, .ai-novel-chapter-list { display: flex; flex-direction: column; gap: 6px; }
.ai-novel-work-item, .ai-novel-chapter-item { display: flex; width: 100%; border: 1px solid transparent; border-radius: 10px; color: #344054; background: transparent; text-align: left; cursor: pointer; transition: .2s ease; }
.ai-novel-work-item { flex-direction: column; gap: 6px; padding: 12px; }
.ai-novel-work-item:hover, .ai-novel-work-item.active, .ai-novel-chapter-item:hover, .ai-novel-chapter-item.active { border-color: #b9d0ff; background: #f0f5ff; }
.ai-novel-work-item strong, .ai-novel-chapter-item strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ai-novel-work-item span, .ai-novel-chapter-item small { color: #7b8798; font-size: 12px; }
.ai-novel-meta-panel, .ai-novel-chapters-panel, .ai-novel-editor-panel, .ai-novel-tools-panel, .ai-novel-bottom-grid { padding: 22px; }
.ai-novel-meta-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.ai-novel-editor-layout { display: grid; grid-template-columns: 260px minmax(0, 1fr); gap: 20px; align-items: stretch; }
.ai-novel-chapters-panel, .ai-novel-editor-panel { min-width: 0; }
.ai-novel-chapter-item { align-items: center; gap: 10px; padding: 10px; }
.ai-novel-chapter-item > span { display: grid; flex: 0 0 30px; place-items: center; height: 30px; border-radius: 8px; color: #2563eb; background: #e8f0ff; font-size: 12px; font-weight: 800; }
.ai-novel-chapter-item strong { flex: 1; }
.ai-novel-chapter-fields { display: flex; flex-direction: column; gap: 10px; margin-bottom: 12px; }
.ai-novel-content-editor :deep(textarea) { min-height: 380px !important; line-height: 1.8; }
.ai-novel-editor-footer { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; color: #7b8798; font-size: 12px; }
.ai-novel-tool-grid { display: grid; grid-template-columns: 180px minmax(0, 1fr) auto; gap: 10px; align-items: center; }
.ai-novel-result { margin-top: 18px; padding: 16px; border: 1px solid #dce6f6; border-radius: 12px; background: #f8faff; }
.ai-novel-result-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.ai-novel-result pre { max-height: 300px; margin: 12px 0 0; overflow: auto; color: #344054; font: 13px/1.7 ui-monospace, SFMono-Regular, Menlo, monospace; white-space: pre-wrap; word-break: break-word; }
.ai-novel-bottom-grid { display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(260px, .7fr); gap: 24px; }
.ai-novel-reference-list { display: flex; flex-direction: column; gap: 10px; }
.ai-novel-reference-list article { position: relative; padding: 12px; border: 1px solid #e4e7ec; border-radius: 10px; }
.ai-novel-reference-list article > div { display: flex; align-items: center; gap: 8px; }
.ai-novel-reference-list p { margin: 8px 0 0; color: #667085; font-size: 13px; line-height: 1.6; white-space: pre-wrap; }
.ai-novel-reference-list .el-button { position: absolute; top: 8px; right: 8px; }
.ai-novel-extract-card { display: flex; flex-direction: column; justify-content: space-between; min-height: 160px; padding: 16px; border-radius: 12px; background: linear-gradient(145deg, #eef5ff, #f8fbff); }
.ai-novel-empty-main { min-height: 500px; padding: 70px 20px; }
@media (max-width: 1100px) { .ai-novel-layout, .ai-novel-editor-layout { grid-template-columns: 1fr; } .ai-novel-sidebar { position: static; min-height: 0; } .ai-novel-work-list { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); } .ai-novel-meta-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 700px) { .ai-novel-hero, .ai-novel-panel-heading { align-items: flex-start; flex-direction: column; } .ai-novel-hero-actions, .ai-novel-heading-actions { width: 100%; justify-content: space-between; } .ai-novel-work-list, .ai-novel-meta-grid, .ai-novel-tool-grid, .ai-novel-bottom-grid { grid-template-columns: 1fr; } .ai-novel-tool-grid .el-button { width: 100%; } }
</style>
