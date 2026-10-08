import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { buildScriptRequest, parseScriptResponse, scriptContent, scriptVideoPrompt, storyboardPayloads } from '../src/utils/ai-script.js'

const view = readFileSync(new URL('../src/views/AiScriptView.vue', import.meta.url), 'utf8')
const router = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const navigation = readFileSync(new URL('../src/utils/navigation.js', import.meta.url), 'utf8')
const initializer = readFileSync(new URL('../../backend/src/main/java/com/baseai/platform/service/DataInitializer.java', import.meta.url), 'utf8')
const controller = readFileSync(new URL('../../backend/src/main/java/com/baseai/platform/controller/AiScriptController.java', import.meta.url), 'utf8')
const videoView = readFileSync(new URL('../src/views/AiVideoView.vue', import.meta.url), 'utf8')

test('AI script response parser handles JSON fences and keeps fallback content', () => {
  const parsed = parseScriptResponse('```json\n{"title":"雨夜来信","logline":"侦探追查一封信","synopsis":"故事梗概","scenes":[{"title":"书店","sceneDescription":"雨夜推门","durationMs":4300}]}\n```')
  assert.equal(parsed.title, '雨夜来信')
  assert.equal(parsed.scenes[0].durationMs, 4300)
  assert.equal(parseScriptResponse('模型无法返回 JSON').scenes.length, 1)
})

test('AI script helpers build bounded requests and storyboard payloads', () => {
  const request = buildScriptRequest({ brief: '  夜航船  ', durationSeconds: 99999, sceneCount: 0 })
  assert.deepEqual(request, { brief: '夜航船', genre: '', tone: '', language: '简体中文', durationSeconds: 3600, sceneCount: 6 })
  const script = parseScriptResponse('{"title":"夜航","synopsis":"寻找灯塔","scenes":[{"title":"甲板","sceneDescription":"海雾","camera":"远景","durationMs":5000}]}')
  assert.equal(storyboardPayloads(script, 12, 8)[0].scriptId, 8)
  assert.match(scriptContent(script), /夜航/)
  assert.match(scriptVideoPrompt(script), /STORYBOARD|夜航|海雾/)
})

test('AI script menu opens the original-novel workspace and keeps video scripts in AI Video', () => {
  assert.match(initializer, /menu\(null, "AI剧本", "MENU", "\/ai-novel"/)
  assert.match(initializer, /ai:novel:invoke.*35/)
  assert.match(initializer, /ai:video:catalog.*36/)
  assert.match(router, /path: 'ai-novel'.*ai:novel:invoke/)
  assert.match(navigation, /ai:novel:invoke.*aiNovel/)
  assert.match(view, /listAiNovels/)
  assert.match(view, /runAiNovelWriter/)
  assert.match(view, /extractAiNovelToVideo/)
  assert.match(videoView, /route: 'scripts'/)
})

test('AI script backend endpoint keeps independent permission and structured output contract', () => {
  assert.match(controller, /@RequestMapping\("\/api\/ai\/script"\)/)
  assert.match(controller, /@RequiredPermission\("ai:script:invoke"\)/)
  assert.match(controller, /"scenes"/)
  assert.match(controller, /sceneCount < 1 \|\| sceneCount > 24/)
})
