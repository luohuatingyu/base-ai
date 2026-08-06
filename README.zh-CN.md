# Base AI

[English](README.md) | [简体中文](README.zh-CN.md)

Base AI 是一个可扩展的管理与 AI 集成平台，由 Vue 管理控制台、Spring Boot 系统服务和 Python LLM Worker 组成，并通过 Docker Compose 部署。

平台提供身份与访问管理、兼容 OpenAI 的模型路由、API Key 访问、任务追踪、审计日志和定时 HTTP 自动化功能。MySQL 存储平台数据，PostgreSQL 存储自动化业务数据，Redis 存储可丢弃的会话状态。

## 功能特性

- 基于角色的访问控制，涵盖用户、角色、菜单、部门、岗位和数据权限范围。
- 支持英文和简体中文界面，以及本地化 API 消息。
- 可在管理控制台中管理模型供应商、模型和能力路由。
- 通过兼容 OpenAI 的供应商提供文本及多模态对话，支持候选模型故障切换、并发限制和 Token 统计。
- 浏览器会话使用带签名 CSRF 防护的 HttpOnly Cookie，同时兼容 Bearer Token 客户端，并提供带有效期、吊销、IP 白名单和限流能力的 `X-API-Key` 凭据。
- 支持跨服务任务追踪、任务取消、任务恢复、操作日志和登录日志。
- 支持手动和基于 Cron 的 HTTP 自动化，并提供加密请求配置与目标 Host 访问控制。
- 支持运行时平台品牌和语言配置。

## 技术栈

- **前端：** Vue 3、Vite、Pinia、Vue Router、Element Plus 和 Axios。
- **后端：** Java 17、Spring Boot 3.3、Spring MVC、Spring Data JPA 和 JDBC。
- **LLM Worker：** Python 3.12、FastAPI、HTTPX、PyYAML 和兼容 OpenAI 的 API。
- **数据服务：** MySQL、PostgreSQL 和 Redis。
- **部署：** Docker Compose 和多阶段容器构建。

## 系统架构

```text
浏览器
  |
  v
Vue 前端（端口 80）
  |
  | /api/* 反向代理
  v
Spring Boot 后端（端口 8080）
  |                  |
  |                  +--> MySQL：身份、配置、模型、任务和日志
  |                  +--> PostgreSQL：自动化配置和执行日志
  |                  +--> Redis：会话、已撤销 Token、锁和限流状态
  |
  | 经过内部认证的请求
  v
Python Worker（内部端口 8000）
  |
  v
兼容 OpenAI 的模型供应商
```

供应商 API Key 默认不会进入浏览器响应；已认证的系统管理员可在管理页面主动查看。Java 服务解析已启用的模型路由，解密对应的供应商凭据，并将候选配置发送给 Python Worker。Worker 负责执行并发限制、凭据轮询、候选模型故障切换，并向 Java 上报用量和链路追踪数据。

## 数据职责

### MySQL 系统数据库

MySQL 是平台的主数据库，存储以下数据：

- 用户、角色、菜单、权限、部门、岗位、字典和系统参数。
- 模型供应商、加密的供应商凭据、模型和能力路由。
- 外部 API Key 元数据、HMAC-SHA256 摘要及仅供管理员查看的加密副本。
- 系统任务、Java/Python 链路记录、操作日志和登录日志。

JPA 负责管理平台实体，`backend/src/main/resources/system-schema.sql` 负责初始化任务和日志表。启动应用前需要先创建目标数据库，并为配置的数据库账号授予 Schema 管理权限。

### PostgreSQL 业务数据库

PostgreSQL 专门用于承载从属业务模块。当前平台将接口触发配置和执行日志存储在 PostgreSQL 中。后端会在启动时幂等执行 `backend/src/main/resources/api-trigger-schema.sql`，因此配置的账号需要具备 DDL 权限。

未来的业务模块可以通过 Spring Bean `postgresqlJdbcTemplate` 访问 PostgreSQL，并应独立维护各自的 Schema。

### Redis 缓存

Redis 存储可重建状态，包括在线会话、已撤销 Token 标识、API Key 限流计数器和分布式调度锁。用户和权限数据仍以 MySQL 为准。

## 安全模型

- 密码使用 BCrypt 哈希保存。
- 登录 Token 使用 HS256 签名，包含唯一 `jti`，并支持配置有效期。
- 用户退出后，Token 标识会记录到 Redis 中，直至 Token 自然过期。
- 后端接口通过 `@RequiredPermission` 强制校验权限；前端路由和菜单使用对应权限控制用户界面。
- 内置 `ADMIN` 角色拥有所有初始化权限。
- 敏感系统参数、供应商凭据和自动化请求数据使用 AES-GCM 加密。
- 请求日志和审计日志会屏蔽密码、Token、Cookie、Authorization 请求头和 API Key。
- API 响应统一使用 `{success, code, message, data}` 结构。消息语言遵循 `Accept-Language`，缺少该请求头时使用 `APP_DEFAULT_LOCALE`。

## 模型管理与对话

在 Web 控制台的**模型管理**模块中配置供应商、模型和能力路由：

1. 添加兼容 OpenAI 的供应商地址和一个或多个 API Key。
2. 添加模型，选择支持的模型类型，并按需配置超时时间和思考参数。
3. 添加能力路由，并设置候选模型顺序。
4. 在 AI 对话页面使用路由模式，或直接选择单个供应商模型。

初始模型类型字典包含 `text_model` 和 `vision_model`；管理员可以通过字典管理添加其他启用的类型。请求未提供 `model_type` 时，默认使用 `text_model`。

对话请求链路如下：

```text
Vue -> Java /api/ai/chat -> Python /llm/chat -> 兼容 OpenAI 的 API
```

如果运行环境不允许将提示词和模型响应写入应用日志，请设置 `LLM_LOG_CONTENT=false`。关闭内容日志后，Worker 只记录元数据和响应摘要。

## API Key 访问

外部系统可以调用代码中明确标记为支持 API Key 的接口。管理员可在**系统管理 > API Key 管理**中创建、授权、轮换、停用或吊销 Key。

```bash
curl --cacert caddy-root.crt -X POST https://127.0.0.1:444/api/ai/chat \
  -H 'X-API-Key: sk-<your-api-key>' \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"hello"}]}'
```

重要规则：

- Key 使用 `sk-` 前缀并附带自动生成的密钥。只有内置 `ADMIN` 角色用户可以创建、轮换或查看完整值。
- 数据库保留用于认证的定位前缀和 HMAC-SHA256 摘要；新建和轮换后的 Key 还会保存 AES-GCM 加密副本，仅供管理员查看。
- 加密副本功能上线前创建的历史 Key 继续有效，但管理员必须先轮换才能查看完整值。
- 实际访问范围是 Key 选中的接口与绑定用户 RBAC 权限的交集。
- 绑定用户和 Key 都必须保持启用，同时还会校验有效期、吊销状态、IP 白名单和调用频率限制。
- 请求不得同时携带 `Authorization` 和 `X-API-Key`；混合凭据会返回 HTTP 401。
- API Key 管理接口接受浏览器登录态或 Bearer Token，但不接受 API Key 自身认证。
- `APP_API_KEY_HASH_SECRET` 建议使用独立密钥；未设置时会复用 `APP_CONFIG_ENCRYPTION_KEY`。

当前支持 API Key 的接口包括 AI 对话调用和正式的接口触发执行。

## 任务追踪与审计日志

- Java 为每个 HTTP 请求创建或传播 `X-Request-Id`。
- 被追踪的操作会获得独立的 `traceId`，用于关联 Java、Python 和数据库记录。
- 调用 Worker 时会传播请求标识、父链路标识、Python 链路标识和内部认证请求头。
- `@TraceType` 描述任务类型和触发元数据；`@TraceIgnored` 排除不应创建任务的接口。
- 任务状态覆盖开始、成功、失败、取消和强制终止。
- 父任务取消会传播至对应的 Python 任务，但不会终止共享的 Worker 进程。
- 启动恢复和定时检查会在心跳超时后将遗留任务标记为失败。
- 操作日志和登录日志与任务链路分开管理。

`trace-tracking-exclusions.yml` 用于定义不自动创建任务的 HTTP 方法和路径。

## HTTP 自动化

自动化模块支持配置 HTTP 方法、请求头、查询参数、请求体、Cron 计划、超时时间、前置认证、手动执行和临时连接测试。

- 请求头、请求体和认证请求体使用 `APP_CONFIG_ENCRYPTION_KEY` 加密。
- 目标 Host 规则以及回环和私网开关在**接口触发安全配置**页面管理，修改后无需重启即可生效。
- 多个后端实例运行时，Redis 锁可防止 Cron 任务重复执行。
- 响应摘要在保存前会截断并脱敏。
- 正式执行还会创建 MySQL 任务链路记录。

## 前置条件

- Docker Engine 和 Docker Compose v2。
- 可访问的 MySQL、PostgreSQL 和 Redis 服务。Docker Compose 不会创建这些外部依赖。
- 已创建的 MySQL 和 PostgreSQL 数据库，以及具有初始化所需 Schema 权限的账号。

如需在容器外进行本地开发，请使用 Java 17、Maven 3.9、Node.js 24 和 Python 3.12。

## 配置

仓库只跟踪 `.env.example`，请勿提交包含真实配置的 `.env` 文件。

1. 复制配置模板：

   ```bash
   cp .env.example .env
   ```

2. 配置外部 MySQL、PostgreSQL 和 Redis 连接。

3. 替换所有占位凭据。可使用以下命令生成配置加密密钥：

   ```bash
   openssl rand -base64 32
   ```

4. 至少检查以下安全敏感配置：

   ```dotenv
   APP_TOKEN_SECRET=<至少32位随机字符>
   APP_SEED_ADMIN_PASSWORD=<至少10位安全字符>
   APP_SEED_ADMIN_PASSWORD_SYNC_ENABLED=false
   APP_CONFIG_ENCRYPTION_KEY=<Base64编码的32字节密钥>
   APP_API_KEY_HASH_SECRET=<至少32位随机字符>
   PYTHON_WORKER_INTERNAL_TOKEN=<至少24位随机字符>
   ```

API Key 哈希密钥仅因存在加密密钥回退机制而可以省略；生产环境建议配置独立值。

### 环境变量分组

- **MySQL：** `MYSQL_URL`、`MYSQL_USERNAME`、`MYSQL_PASSWORD`。
- **PostgreSQL：** `POSTGRES_URL`、`POSTGRES_USERNAME`、`POSTGRES_PASSWORD`、`POSTGRES_POOL_SIZE`。
- **Redis：** `REDIS_HOST`、`REDIS_PORT`、`REDIS_PASSWORD`、`REDIS_DATABASE`、`REDIS_TIMEOUT`。
- **品牌与语言：** `APP_PLATFORM_CODE`、`APP_PLATFORM_NAME_EN`、`APP_PLATFORM_NAME_ZH`、`APP_PLATFORM_SHORT_NAME`、`APP_DEFAULT_LOCALE`。
- **认证与加密：** `APP_TOKEN_SECRET`、`APP_TOKEN_EXPIRE_MINUTES`、`APP_SESSION_COOKIE_SECURE`、`APP_SEED_ADMIN_USERNAME`、`APP_SEED_ADMIN_PASSWORD`、`APP_SEED_ADMIN_PASSWORD_SYNC_ENABLED`、`APP_CONFIG_ENCRYPTION_KEY`、`APP_API_KEY_HASH_SECRET`。
- **Worker 与模型调用：** `PYTHON_WORKER_INTERNAL_TOKEN`、`JAVA_INSTANCE_ID`、`PYTHON_WORKER_INSTANCE_ID`、`LLM_TIMEOUT_SECONDS`、`LLM_LOG_CONTENT`。
- **路由健康检查：** `LLM_ROUTE_HEALTH_CHECK_ENABLED`、`LLM_ROUTE_HEALTH_CHECK_INTERVAL_MS`。
- **任务追踪与日志：** `TRACE_TRACKING_EXCLUSIONS_FILE`、`TRACE_LOG_PERSIST_LEVEL`、`TRACE_LOG_QUEUE_CAPACITY`、`TRACE_LOG_BATCH_SIZE`、`TRACE_LOG_FLUSH_INTERVAL_MS`、`TRACE_LOG_RETENTION_DAYS`、`TRACE_HEARTBEAT_TIMEOUT_SECONDS`。
- **自动化：** `API_TRIGGER_SCHEDULER_POOL_SIZE`、`API_TRIGGER_LOCK_SECONDS`、`API_TRIGGER_RESULT_MAX_LENGTH`。
- **HTTPS 入口、端口和镜像：** `APP_DOMAIN`、`TLS_CERT_FILE`、`TLS_KEY_FILE`、`HOST_IP_CHECK_INTERVAL_SECONDS`、`HTTP_PORT`、`HTTPS_PORT`、`FRONTEND_BACKEND_URL`，以及 `.env.example` 中可选的镜像与软件包镜像源变量。后端 8080 和 Worker 8000 端口仅在内部网络开放。

`APP_DEFAULT_LOCALE` 支持 `en-US` 或 `zh-CN`，默认值为 `en-US`。
Docker Compose 使用 `APP_PLATFORM_SHORT_NAME` 生成项目名并自动规范为小写，四个运行时容器依次命名为 `<简称>-backend`、`<简称>-python-worker`、`<简称>-frontend` 和 `<简称>-caddy`。例如 `APP_PLATFORM_SHORT_NAME=AI` 时，容器名为 `ai-backend`、`ai-python-worker`、`ai-frontend` 和 `ai-caddy`。

### HTTPS 入口模式

Caddy 根据配置自动选择一种入口模式：

- **域名证书模式：** `APP_DOMAIN`、`TLS_CERT_FILE` 和 `TLS_KEY_FILE` 必须同时配置。证书应为包含中间证书的 PEM 完整链，私钥应为无交互密码的 PEM 文件。三项只配置一部分时 Caddy 会拒绝启动，不会降级到 HTTP。
- **IP 内部 CA 模式：** 上述三项全部留空后生效。项目始终将 `localhost` 和 `127.0.0.1` 加入证书，并由宿主机跟踪器自动发现默认网卡 IPv4，无需手工填写 IP。Caddy 持久化内部 CA 签发包含全部当前地址的多 SAN 证书，访问设备必须信任 Caddy 根证书。

域名使用标准端口的示例：

```dotenv
APP_DOMAIN=ai.example.com
TLS_CERT_FILE=/absolute/path/fullchain.pem
TLS_KEY_FILE=/absolute/path/privkey.pem
HTTP_PORT=80
HTTPS_PORT=443
APP_SESSION_COOKIE_SECURE=true
```

IP 自动发现模式的示例：

```dotenv
APP_DOMAIN=
TLS_CERT_FILE=
TLS_KEY_FILE=
HOST_IP_CHECK_INTERVAL_SECONDS=60
HTTP_PORT=81
HTTPS_PORT=444
APP_SESSION_COOKIE_SECURE=true
```

宿主机跟踪器仅选择当前默认路由网卡的可用 IPv4，不会把 Docker、回环、链路本地或基准测试网段写入证书。它将地址原子写入本地 `.runtime` 目录，Caddy 按 `HOST_IP_CHECK_INTERVAL_SECONDS` 重读；DHCP 续租或默认网卡切换后会自动重签证书并热加载。当主机位于 NAT 后时，外部公网地址不属于本机网卡，应使用域名证书模式。

IP 证书目标有效期为 30 天，且不会超过 Caddy 中间 CA 的剩余有效期。地址 SAN、中间 CA 或有效期需要更新时会自动重新签发，并通过只监听容器回环地址的管理接口热加载。该管理接口不会发布到宿主机或 Compose 网络。首次签发失败会阻止 Caddy 启动，后台续期失败则保留当前证书并在下一周期重试。

域名证书和私钥以只读文件挂载，Caddy 使用容器 UID `10001`。Linux 主机应通过专用组或 ACL 授予该 UID 读取文件及遍历父目录的权限，不要将私钥设置为所有用户可读。可在启动前验证容器用户权限：

```bash
docker compose run --rm --entrypoint sh caddy -c \
  'test -r /etc/caddy/tls/fullchain.pem && test -r /etc/caddy/tls/privkey.pem'
```

替换续期后的域名证书文件后，执行 `docker compose restart caddy` 重新加载。项目不会修改或自动续期宿主机上的证书。

## 使用 Docker Compose 启动

启动前先验证最终配置。请注意，`docker compose config` 会展开敏感配置，请勿分享其输出。

```bash
docker compose config --quiet
./scripts/base-ai.sh up --build -d
./scripts/base-ai.sh ps
```

IP 自动发现依赖上述启动脚本在宿主机运行跟踪器。直接执行 `docker compose up` 仍可以使用 `localhost` 和 `127.0.0.1`，但不会持续发现局域网地址变化。

所有服务进入健康状态后，可访问：

- Web 控制台：<https://127.0.0.1:444>
- 后端 API 和开放平台：<https://127.0.0.1:444/api>
- 统一健康检查：<https://127.0.0.1:444/api/open/health>
- 后端存活检查：<https://127.0.0.1:444/api/open/health/live>
- 后端就绪检查：<https://127.0.0.1:444/api/open/health/ready>
- Caddy 健康检查：<https://127.0.0.1:444/health>

统一健康检查和就绪检查仅在 MySQL、PostgreSQL、Redis 与 Python Worker 全部可用时返回 HTTP 200，否则返回 HTTP 503。公网入口对 `/api/internal` 路径统一返回 HTTP 404，Java 与 Worker 之间仍通过 Docker 内部网络直连。

HTTP 入口仅用于 308 跳转，业务页面和 API 统一通过 HTTPS 提供。默认 81/444 端口避免与其他本地项目冲突；无需显式端口的公网域名应使用 80/443。HSTS 仅在标准 80/443 模式启用，避免浏览器在非标准端口下升级到错误端口。

IP 模式首次启动后，导出内部 CA 的公开根证书：

```bash
docker compose cp caddy:/data/caddy/pki/authorities/local/root.crt ./caddy-root.crt
```

只安装 `root.crt`，绝不要从 Caddy 数据卷复制或分发根私钥。常见系统信任方式如下，安装完成后应重启浏览器：

```bash
# macOS
sudo security add-trusted-cert -d -r trustRoot \
  -k /Library/Keychains/System.keychain caddy-root.crt

# Windows（管理员命令提示符）
certutil -addstore -f Root caddy-root.crt

# Debian/Ubuntu
sudo cp caddy-root.crt /usr/local/share/ca-certificates/base-ai-caddy.crt
sudo update-ca-certificates
```

Firefox 等使用独立证书库的客户端可能仍需在浏览器设置中导入。Caddy CA 和自动签发的多 SAN IP 证书保存在命名卷中；普通容器重建不会更换根 CA。修改 `APP_PLATFORM_SHORT_NAME`、执行 `docker compose down -v` 或手动删除 Caddy data 卷会生成新的根 CA，届时所有访问设备必须重新信任。

首次初始化后，使用 `APP_SEED_ADMIN_USERNAME` 配置的用户名和 `APP_SEED_ADMIN_PASSWORD` 配置的密码登录。初始管理员、角色、权限树、根部门和模型类型字典会自动创建。

`APP_SEED_ADMIN_PASSWORD_SYNC_ENABLED` 未配置或设置为 `false` 时，种子密码仅用于首次创建管理员，不覆盖已有密码。显式设置为 `true` 后，应用会在每次启动时检查已有管理员密码，并在不一致时同步为 `APP_SEED_ADMIN_PASSWORD`；此时在管理页面手动修改的密码会在下次启动时被环境变量中的值覆盖。

停止应用：

```bash
./scripts/base-ai.sh down
```

## 开发与测试

运行后端测试套件：

```bash
cd backend
mvn test -B
```

使用 Python 3.12 运行 Worker 测试：

```bash
cd python-worker
python3.12 -m pytest
```

在仓库根目录运行完整的前端 Node 测试套件：

```bash
node --test frontend/test/*.test.mjs frontend/tests/*.test.js
```

代码变更后重新构建 Docker 环境：

```bash
./scripts/base-ai.sh up --build -d
```

## 仓库结构

```text
backend/                        Spring Boot API 和平台服务
database/postgresql/            PostgreSQL Schema 参考脚本
frontend/                       Vue 管理控制台和 API 代理
python-worker/                  FastAPI LLM Worker
.env.example                    环境变量模板
docker-compose.yml              应用容器定义
trace-tracking-exclusions.yml   默认任务追踪排除项
TEST_REPORT.md                  测试基准和执行历史
```

## 生产环境注意事项

- 将生产环境变量文件保存在仓库外，并限制其文件系统权限。
- MySQL 系统数据库和 PostgreSQL 业务数据库应使用独立的最小权限账号。
- 首次启动前轮换所有示例凭据。
- 不要将供应商凭据写入源代码、Shell 历史、日志或 Git 历史。
- 为 `APP_API_KEY_HASH_SECRET` 配置独立密钥，不要依赖加密密钥回退机制。
- 检查 `LLM_LOG_CONTENT`；配置模板默认开启内容日志，可能不适合处理敏感数据的环境。
- 执行 Schema 或应用升级前，备份 MySQL 任务与日志数据以及 PostgreSQL 自动化日志。
- 无法访问公共镜像仓库时，可覆盖 `MAVEN_IMAGE`、`JRE_IMAGE`、`NODE_IMAGE`、`PYTHON_IMAGE` 或 Python 软件包镜像源配置。

## 许可证

本项目使用 Apache License 2.0，详情请参阅 `LICENSE`。
