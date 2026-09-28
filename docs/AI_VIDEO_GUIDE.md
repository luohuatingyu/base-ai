# AI 视频工作台使用说明

## 1. 功能定位

AI 视频工作台用于管理从创意到成片发布的结构化流程。当前版本提供项目、剧本、分镜、角色与场景等资产、生成任务、视频合成、成片审核和发布记录的统一维护能力。每一条记录都属于一个项目，普通用户只能看到自己拥有的项目及其子资源，系统管理员可以查看全部项目。

完整流程如下：

```text
项目 → 剧本 → 分镜 → 角色/场景/物品/服装/动作/画风/声音资产
     → 生成任务 → 视频合成 → 成片 → 审核 → 发布
```

## 2. 菜单和使用方式

登录后在左侧进入 **AI视频**，菜单分为四组：

| 菜单组 | 页面 | 用途 |
| --- | --- | --- |
| 工作台 | 工作台首页 | 查看项目、资产、任务和成片统计，了解制作流程 |
| 内容创作 | 项目管理、剧本管理、分镜管理 | 建立项目并准备可执行的镜头内容 |
| 资产中心 | 角色、场景、物品、服装、动作、画风、声音、音效 | 管理可以被多个分镜复用的资产 |
| 生成中心 | 生成任务、视频合成 | 保存生成参数、任务状态和合成结果 |
| 发布中心 | 成片、审核、发布、数据分析 | 维护成片版本、审核意见和渠道发布记录 |

建议首次使用时按以下顺序操作：

1. 在“项目管理”创建项目，设置编码、画幅、分辨率和风格。
2. 在“剧本管理”创建剧本，并填写故事内容、对白和旁白。
3. 在“分镜管理”按镜头顺序建立分镜，填写画面描述、机位和时长。
4. 在资产中心创建角色、场景、物品和声音记录，把资源地址或预览地址保存到对应资产。
5. 在“生成任务”提交图片、视频、配音或音乐任务，填写提示词、输入 JSON 和能力路由 ID。
6. 在“视频合成”创建渲染任务，填写镜头、字幕、配音和输出配置 JSON。
7. 在“成片管理”保存渲染输出地址和版本信息。
8. 在“审核管理”提交审核意见；通过后在“发布管理”记录渠道和外部发布编号。

### 五种成片路径

工作台首页提供五个快捷入口，适合不同的素材准备方式：

| 路径 | 适用场景 | 首个动作 | 后续建议 |
| --- | --- | --- | --- |
| 文本生成视频 | 只有创意或提示词 | 创建 `VIDEO` 任务 | 补充分镜和资产，再进入渲染 |
| 分镜合成视频 | 已经完成镜头拆解 | 创建渲染任务 | 写入字幕、配音和镜头配置 JSON |
| 资产驱动生成 | 已有角色、场景或物品 | 创建 `IMAGE` 或 `VIDEO` 任务 | 在 `inputJson` 关联资产编号 |
| 配音与音乐 | 画面已确定，需要声音 | 创建 `TTS` 或 `MUSIC` 任务 | 将输出地址写入渲染配置 |
| 模板批量合成 | 需要批量生成系列视频 | 创建模板渲染任务 | 在配置 JSON 中保存模板和变量 |

点击路径后会进入对应创建页面，并自动带入任务类型或渲染模式；提交前仍可调整项目、提示词、能力路由和 JSON 参数。路径入口只负责建立任务，实际生成由配置的 Provider Worker 推进。

## 3. 数据和状态

### 项目

项目字段包括项目编码、名称、简介、封面地址、画幅、分辨率、帧率和风格。项目状态为：

- `DRAFT`：草稿，可继续维护。
- `ACTIVE`：制作中。
- `ARCHIVED`：归档项目，建议停止新增内容。

### 剧本和分镜

剧本通过 `content` 保存正文，版本号用于人工维护版本。分镜通过 `shotNo` 排序，`durationMs` 使用毫秒，便于后续渲染服务直接计算时间轴。

### 资产

资产类型使用稳定编码：

```text
CHARACTER 角色
SCENE     场景
ITEM      物品
COSTUME   服装造型
MOTION    动作表情
STYLE     画风模板
VOICE     角色声音
SOUND     音乐音效
```

`metadataJson` 用于保存供应商或业务扩展参数，`previewUrl` 用于页面预览，`resourceUrl` 用于原始资源或对象存储地址。

### 生成任务和渲染任务

生成任务状态为 `QUEUED`、`RUNNING`、`SUCCEEDED`、`FAILED`、`CANCELLED`。失败或取消的任务可以点击“重试”，排队或运行中的任务可以点击“取消”。渲染任务使用相同的排队和进度字段，输出地址写入 `outputUrl`。

工作台任务仍用于项目编排；实际图片和视频调用可从“AI 对话”页面进入“媒体生成”模式。图片请求同步返回结果，视频请求保存供应商任务编号并由前端和后台定时轮询，任务状态、输出地址和失败码均持久化。

模型配置中分别维护 `image_model`（图片生成模型）与 `video_model`（视频生成模型），每条生成模型配置只选择一种类型；`vision_model` 仍用于视觉理解。供应商地址与加密 API Key 复用现有管理功能，新增类型不改变已有文本与视觉理解组合。

两种生成模型不出现在文本聊天候选中，而是在 AI 对话的“媒体生成”模式中单独选择。平台按 OpenAI 兼容约定调用 `/images/generations` 或 `/videos/generations`，当前供应商若使用不同路径或请求字段，需要在供应商适配层对齐后再启用。视觉模型仍只用于视觉理解。

图片和视频调用都会写入 `llm_usage_record`：`model_type` 分别为 `image_model` 与 `video_model`，来源分别为 `AI_IMAGE_GENERATION` 与 `AI_VIDEO_GENERATION`。供应商返回的 `input_tokens`、`output_tokens`、`total_tokens` 会原样记录；未返回的字段保持空值，不用 0 估算。视频轮询更新同一个 `usage_id`，失败重试会创建新记录。

### 审核和发布

审核状态为 `PENDING`、`APPROVED`、`REJECTED`。发布状态为 `DRAFT`、`PUBLISHING`、`PUBLISHED`、`FAILED`。点击“完成发布”会写入 `publishedAt`，用于后续统计和渠道同步。

## 4. 权限模型

页面权限和按钮权限按资源拆分。以项目为例：

```text
ai:video:project:list
ai:video:project:create
ai:video:project:update
ai:video:project:delete
```

资产使用资产自身的权限前缀，例如：

```text
ai:video:item:list
ai:video:item:create
ai:video:item:update
ai:video:item:delete
```

生成任务、渲染、成片、审核和发布分别使用 `task`、`render`、`video`、`review` 和 `publish` 前缀。后端会再次校验权限和项目所有权，不能只依赖前端按钮隐藏。

## 5. 接口说明

接口前缀为 `/api/ai-video`，统一使用当前登录 Cookie 会话和 CSRF 保护。

AI 对话媒体入口使用 `/api/ai/chat`：`GET /generation-models` 获取图片/视频模型，`POST /generation` 发起调用，`GET /generation/{id}` 查询视频进度和产物。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/overview` | 工作台统计 |
| GET | `/{resource}` | 查询资源列表 |
| POST | `/{resource}` | 创建资源 |
| PUT | `/{resource}/{id}` | 更新资源 |
| DELETE | `/{resource}/{id}` | 删除资源 |
| POST | `/tasks/{id}/cancel` | 取消生成任务 |
| POST | `/tasks/{id}/retry` | 重试生成任务 |
| POST | `/videos/{videoId}/review` | 提交成片审核 |
| POST | `/publishes/{id}/complete` | 完成发布记录 |

`resource` 可取：`projects`、`scripts`、`storyboards`、`assets`、`tasks`、`renders`、`videos`、`reviews`、`publishes`。查询资产时使用 `assetType` 参数，例如：

```text
GET /api/ai-video/assets?assetType=ITEM
```

创建项目示例：

```json
{
  "code": "DEMO_001",
  "name": "示例短剧",
  "description": "第一季试播集",
  "aspectRatio": "9:16",
  "resolution": "1080x1920",
  "fps": 25,
  "style": "国风二维",
  "status": "DRAFT"
}
```

创建生成任务示例：

```json
{
  "projectId": 1,
  "taskType": "VIDEO",
  "name": "第一镜头视频生成",
  "prompt": "夜雨中的古城街道，电影感推镜头",
  "providerRouteId": 12,
  "inputJson": "{\"storyboardId\": 7, \"durationMs\": 5000}"
}
```

## 6. 数据库表

数据库迁移文件为 `V44__add_ai_video_workspace.sql`，新增以下表：

```text
ai_video_project
ai_video_script
ai_video_storyboard
ai_video_asset
ai_video_task
ai_video_render
ai_video_video
ai_video_review
ai_video_publish
```

子资源通过外键关联项目或成片，删除项目时由数据库级联清理子资源。任务和发布记录保留外部编号、地址和错误信息，便于重试和排障。

## 7. 本地启动和验证

项目的 Compose 文件不内置 MySQL 和 Redis。开发环境需要先在 Compose 网络中提供两个服务：

```bash
docker network create baseai-local_app-network 2>/dev/null || true
docker run -d --name mysql --network baseai-local_app-network --network-alias mysql \
  -e MYSQL_DATABASE=base_ai_system \
  -e MYSQL_USER=base_ai_system \
  -e MYSQL_PASSWORD='<MYSQL_PASSWORD>' \
  -e MYSQL_ROOT_PASSWORD='<MYSQL_MIGRATION_PASSWORD>' \
  -v baseai-local_mysql_data:/var/lib/mysql mysql:8.4
docker run -d --name redis --network baseai-local_app-network --network-alias redis \
  redis:7.4 redis-server --requirepass '<REDIS_PASSWORD>'
docker compose up --build -d backend frontend
```

访问 `http://localhost:8080/login`，登录后进入“AI视频”。后端启动时会自动执行 Flyway 迁移并初始化权限菜单。

## 8. 供应商适配扩展

工作台不把具体供应商写死在页面。后续接入图像、视频或语音供应商时，建议实现以下步骤：

1. 在模型供应商和能力路由中登记供应商、模型和能力编码。
2. 在任务 Worker 中读取 `taskType`、`providerRouteId`、`inputJson`。
3. 将外部任务编号、进度、输出地址和错误原因回写 `ai_video_task` 或 `ai_video_render`。
4. 生成完成后创建或更新资产、分镜和成片关联记录。
5. 对外部接口增加超时、重试、幂等键和敏感字段脱敏。

## 9. 排障

- 页面无菜单：确认当前角色拥有 `ai:video:*:list` 权限，退出后重新登录刷新权限快照。
- 列表请求 403：检查角色按钮权限和项目所有者范围。
- 后端启动失败并提示 `UnknownHostException: mysql` 或 `redis`：确认两个容器都加入 `baseai-local_app-network`，且网络别名分别为 `mysql`、`redis`。
- 迁移失败：查看 `docker logs baseai-local-backend`，确认 MySQL 用户拥有 `base_ai_system` 数据库的建表权限。
- 任务一直排队：当前版本只负责任务编排和状态维护，需要部署对应的外部生成 Worker 才会推进任务。
