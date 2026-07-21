# AI Platform

AI平台是一个可直接扩展业务模块的平台，提供 Vue 管理端、Spring Boot 系统服务和 Python LLM Worker。

## 技术架构

- 前端：Vue 3、Vite、Pinia、Vue Router、Element Plus、Axios。
- 后端：Java 17、Spring Boot 3.3、Spring MVC、Spring Data JPA、JdbcTemplate。
- Worker：Python 3.12、FastAPI、httpx、OpenAI-compatible API。
- 部署：Docker Compose 仅管理应用容器，数据库使用外部服务。

## 数据库职责

### MySQL 系统数据库

MySQL 是平台主数据源，仅保存系统功能数据：

- 用户、角色、权限菜单及关联关系。
- AI 调用等系统任务。
- Java 和 Python Worker 的统一任务日志。

JPA 实体默认绑定 MySQL。任务与日志表通过 `backend/src/main/resources/system-schema.sql` 初始化，目标数据库需要提前创建。

### PostgreSQL 从属业务数据库

PostgreSQL 专门承载后续业务模块的数据，不保存系统用户、权限和任务日志。Java 业务模块通过以下 Bean 访问：

```java
@Qualifier("postgresqlJdbcTemplate") JdbcTemplate postgresqlJdbcTemplate
```

AI平台不创建具体业务表；业务模块应使用独立迁移脚本维护 PostgreSQL Schema。

### Redis 缓存数据库

Redis 只保存可丢失、可重建的缓存状态。当前用于登录 Token 撤销列表，权限主数据仍以 MySQL 为准。

## 权限体系

- 密码使用 BCrypt 保存。
- 登录令牌使用 HS256 签名，并带唯一 `jti` 和过期时间。
- 退出登录后，令牌编号写入 Redis 直到自然过期。
- 后端通过 `@RequiredPermission` 执行最终接口权限校验。
- 前端根据相同权限编码控制路由和菜单显示。
- 管理员角色编码为 `ADMIN`，拥有全部平台权限。
- 菜单支持目录、页面和按钮三级类型，并由登录用户的菜单树动态生成侧边栏。
- 用户可绑定部门、岗位和多个角色；角色数据范围支持全部、本部门、本部门及下级、本人和自定义部门。
- 系统管理接口使用 `list/create/update/delete` 细粒度权限，并兼容历史 `manage` 权限。

## 系统管理

- 部门、岗位、字典和系统参数统一存放在 MySQL 系统库。
- 敏感系统参数与模型 API Key 使用 AES-GCM 加密，管理接口只返回脱敏值。
- Redis 维护在线会话、最后活跃时间和 Token 撤销状态，管理员可强制下线单个会话或用户全部会话。
- 操作日志通过 AOP 记录写请求、耗时、结果和脱敏参数；登录日志独立记录登录成功与失败事件。
- 外部业务 API 使用 `{success, code, message, data}` 统一响应结构，健康检查和内部服务协议保持精简格式。

首次启动自动创建以下权限：

- `ai:chat:invoke`
- `system:user:manage`
- `system:role:manage`
- `system:menu:manage`
- `system:task:view`

## LLM 调用

浏览器不会直接持有模型 API Key。完整调用链如下：

```text
Vue -> Java /api/ai/chat -> Python Worker /llm/chat -> OpenAI-compatible API
```

Java 负责用户权限、任务状态和父任务日志上下文；Python Worker 负责模型连接池、并发限制、API Key 轮询和 Token 统计。

默认 `LLM_LOG_CONTENT=false`，任务日志只保存模型、耗时、Token 数及响应摘要，不记录完整提示词和模型响应。

模型调用始终读取容器内 `/app/config/ai-model-pools.yml` 和 `/app/config/ai-feature-routing.yml`。仓库提供 `ai-model-pools.example.yml` 作为模型池结构示例，并提供可直接使用的 `ai-feature-routing.yml` 通用模型路由配置；真实模型地址和 API Key 必须保存在仓库外。

YAML 支持：

- 多供应商和多个 API Key。
- 供应商级或 API Key 级并发限制。
- `ai-feature-routing.yml` 只配置通用模型的能力等级和思考模式。
- 每次请求通过 `model_type` 选择具体模型类型，例如 `text_model`、`audio_model` 或 `reasoning_model`。
- 组池顺序和 API Key 故障切换。
- `model_type` 为空时默认使用 `text_model`；通用配置缺失时默认使用 `middle` 能力等级并关闭思考模式。

## 统一日志

- Java 请求自动生成或传播 `X-Request-Id`。
- AI 调用创建 MySQL 系统任务并写入 MDC `jobId`。
- Java Logback 将带 `jobId` 的日志异步批量写入 MySQL。
- Java 调用 Worker 时传播 `X-Request-Id`、`X-Parent-Job-Id` 和内部令牌。
- Python 使用 ContextVar 注入 `requestId`、`jobId`、`pythonJobId`，并异步批量回传 Java。
- 日志保留天数、队列容量和持久化级别均由统一环境变量维护。

## 任务调度与 AOP

- 默认对未排除的 `RestController` 写操作建立系统任务。
- `@JobType` 用于声明任务名称、触发入口和定时任务所有者参数。
- `@JobIgnored` 用于排除登录、健康检查、任务查询和内部日志接口。
- AOP 自动维护任务开始、成功、失败、取消和强制终止状态。
- 请求参数与请求头保存前会递归屏蔽密码、Token、Cookie、Authorization 和 API Key。
- 前端可预留 `X-Job-Id`，后端绑定后通过响应头返回同一任务编号。
- `job-tracking-exclusions.yml` 统一维护不需要建立任务的 HTTP 方法和路径。
- Python 子任务独立记录 Worker 实例、状态、心跳和完成原因。
- 父任务取消会传播到对应 Python 异步任务，强制终止不会杀死共享 Worker 进程。
- 定时巡检和应用启动恢复会将心跳超时的遗留任务标记为失败。

## 自动化接口触发

接口触发配置和执行记录存放在 PostgreSQL 业务库。首次部署需使用具有 DDL 权限的账号执行：

```bash
psql "$POSTGRES_DSN" -f database/postgresql/api-trigger.sql
```

支持 HTTP 方法、请求头、查询参数、请求体、Cron、超时、前置认证、手动执行和临时测试。正式执行会同时创建 MySQL 系统任务。

- `APP_CONFIG_ENCRYPTION_KEY` 使用 AES-GCM 加密请求头、请求体和认证请求体。
- `API_TRIGGER_ALLOWED_HOSTS` 是必填目标域名白名单，支持 `*.example.com`。
- `API_TRIGGER_ALLOW_PRIVATE_NETWORK=false` 默认阻止本机、链路本地及私有网络地址。
- Redis 分布式锁避免多后端实例重复执行同一 Cron。
- 执行摘要会截断并屏蔽常见 Token、密码和 Authorization 内容。

## 环境变量

仓库只提供 `.env.example`，**不会提供或提交 `.env`**。生产环境建议把真实变量文件保存在仓库外，例如：

```bash
sudo install -d -m 700 /etc/base-ai
sudo cp .env.example /etc/base-ai/base-ai.env
sudo chmod 600 /etc/base-ai/base-ai.env
sudo editor /etc/base-ai/base-ai.env
```

必须替换所有密码和内部令牌。不要把 `/etc/base-ai/base-ai.env` 复制回仓库。

`COMPOSE_PROJECT_NAME` 是 Docker Compose 资源的统一命名前缀，默认示例值为 `base-ai`。修改该值会同步调整 Compose 项目名、应用镜像名称和网络名称。

另行创建外部运行时 YAML：

```bash
sudo cp ai-model-pools.example.yml /etc/base-ai/ai-model-pools.yml
sudo cp ai-feature-routing.yml /etc/base-ai/ai-feature-routing.yml
sudo cp job-tracking-exclusions.yml /etc/base-ai/job-tracking-exclusions.yml
sudo chmod 600 /etc/base-ai/ai-model-pools.yml
sudo chmod 644 /etc/base-ai/ai-feature-routing.yml /etc/base-ai/job-tracking-exclusions.yml
sudo editor /etc/base-ai/ai-model-pools.yml
```

将真实模型地址和 API Key 写入组池 YAML，并在环境文件中设置：

```dotenv
AI_MODEL_POOLS_FILE=/etc/base-ai/ai-model-pools.yml
AI_FEATURE_ROUTING_FILE=/etc/base-ai/ai-feature-routing.yml
JOB_TRACKING_EXCLUSIONS_FILE=/etc/base-ai/job-tracking-exclusions.yml
```

三份运行时 YAML 均可通过外部环境文件指定宿主机路径；未设置非敏感配置路径时，Docker Compose 回退到仓库内默认文件。具体模型类型和能力等级分别通过请求的 `model_type` 与通用配置选择。

环境变量分为：

- 平台品牌：`APP_BRAND_CODE`、`APP_BRAND_NAME_EN`、`APP_BRAND_NAME_ZH`、`APP_BRAND_SHORT_NAME`，默认展示为 `AI Platform` 和 `AI平台`。
- MySQL 系统库：`MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`。
- PostgreSQL 业务库：`POSTGRES_URL`、`POSTGRES_USERNAME`、`POSTGRES_PASSWORD`。
- Redis 缓存：`REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_DATABASE`。
- 平台安全：`APP_TOKEN_SECRET`、`APP_SEED_ADMIN_PASSWORD`、`PYTHON_WORKER_INTERNAL_TOKEN`。
- 配置加密：`APP_CONFIG_ENCRYPTION_KEY`，可通过 `openssl rand -base64 32` 生成。
- YAML 文件挂载：`AI_MODEL_POOLS_FILE`、`AI_FEATURE_ROUTING_FILE`、`JOB_TRACKING_EXCLUSIONS_FILE`；调用超时和内容日志仍使用 `LLM_TIMEOUT_SECONDS`、`LLM_LOG_CONTENT`。
- 接口触发：`API_TRIGGER_ALLOWED_HOSTS`、`API_TRIGGER_ALLOW_PRIVATE_NETWORK`、`API_TRIGGER_LOCK_SECONDS`。
- 日志：`JOB_LOG_PERSIST_LEVEL`、`JOB_LOG_RETENTION_DAYS`、`JOB_LOG_QUEUE_CAPACITY`。
- 任务治理：`JOB_HEARTBEAT_TIMEOUT_SECONDS`。

## Docker 启动

Docker Compose 不创建 MySQL、PostgreSQL 或 Redis。启动前需要保证三个外部服务可以从容器网络访问。

启动前必须在环境变量文件中设置 `COMPOSE_PROJECT_NAME`，已有部署升级时也需要补充该配置项。

```bash
docker compose --env-file /etc/base-ai/base-ai.env config
docker compose --env-file /etc/base-ai/base-ai.env up -d --build
docker compose --env-file /etc/base-ai/base-ai.env ps
```

默认访问地址：

- 前端：`http://localhost`
- Java 后端：`http://localhost:8080`
- 后端健康检查：`http://localhost:8080/api/open/health`

如果网络无法访问 Docker Hub，可在外部环境变量文件中覆盖 `MAVEN_IMAGE`、`JRE_IMAGE`、`NODE_IMAGE` 和 `PYTHON_IMAGE`。

## 目录结构

```text
backend/         Spring Boot 系统服务
frontend/        Vue 管理端与同源 API 代理
python-worker/   FastAPI LLM Worker
.env.example     唯一环境变量模板
ai-model-pools.example.yml  LLM 模型池配置模板
ai-feature-routing.yml  默认业务模型路由配置
job-tracking-exclusions.yml  默认任务跟踪排除配置
docker-compose.yml
```

## 安全要求

- `APP_TOKEN_SECRET` 至少使用 32 位随机字符串。
- `PYTHON_WORKER_INTERNAL_TOKEN` 至少使用 24 位随机字符串。
- 首次启动管理员密码不得使用示例值。
- LLM Key 只能写入仓库外部的 `ai-model-pools.yml`，不得写入源码或 Git 历史。
- PostgreSQL 业务账号和 MySQL 系统账号应分别授权，不得共用数据库超级用户。
