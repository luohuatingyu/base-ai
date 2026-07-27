# 最近分支覆盖测试报告

## 📋 Git 基准点

- Commit: e959210
- 提交说明: Align API response codes and messages
- 测试日期: 2026-07-27
- 分支: master

## 🎯 当前变更范围

- 外部业务 API 统一响应体的 `code` 改为与 HTTP 状态一致的数字。
- 成功、框架异常、认证鉴权、业务异常和限流消息通过中英文资源按 `Accept-Language` 返回。
- 未提供语言时默认英文，前端请求自动携带当前 Vue i18n 语言状态。
- 业务异常改用消息键和占位参数，异步日志继续保留可读的中文默认文本。
- `/api/open/**` 与 `/api/internal/**` 的成功响应继续保持原始精简结构。

## 📋 当前变更测试结果（2026-07-27）

**变更范围**：统一 API 数字状态码、双语响应消息、默认英文 Locale、前端语言请求头及全部业务异常消息键迁移。

**测试执行结果**：
- 完整回归测试：200 个，通过 200 个（100%）
- 后端完整测试：129 个，通过 129 个（100%）
- 前端完整测试：71 个，通过 71 个（100%）
- 响应契约与消息资源定向测试：17 个，通过 17 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- 数字状态码、双语消息、默认语言和精简接口兼容：14/14，通过
- 中英文资源键一致、英文资源纯英文及源码消息键完整性：3/3，通过
- 后端认证、权限、API Key、系统配置、模型管理、接口触发和任务链路完整回归：129/129，通过
- 前端语言请求头及既有页面逻辑完整回归：71/71，通过
- Python Worker：本次未执行测试，未修改 Worker 代码

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 响应契约与消息资源定向测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=ApiResponseContractTest,MessageBundleTest test` | 17 通过，0 失败，0 错误，0 跳过 |
| 后端完整回归 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 129 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs` | 71 通过，0 失败，0 跳过 |
| 服务重建与启动 | `docker compose up --build -d` | Backend 构建内 129 个测试通过，Frontend 生产构建通过，三个服务启动成功 |
| 服务健康检查 | `docker compose ps`、`curl -fsS http://localhost/health`、`curl -fsS http://localhost:8080/api/open/health` | Backend、Frontend、Python Worker 全部 healthy，两个健康端点返回 UP |
| 双语接口验证 | 分别以 `en-US`、`zh-CN` 和无语言头请求受保护接口 | 均返回 HTTP 401 和数字 `code: 401`；消息分别为英文、中文和默认英文 |
| 消息资源与格式检查 | 中英文键差异检查、业务异常中文硬编码扫描、`git diff --check` | 全部通过 |

## 🔄 当前覆盖范围与结果

- 正常场景：成功响应使用数字 `code: 200`，并根据语言状态返回成功消息。
- 边界场景：无语言头时默认英文；中文和英文语言状态动态切换；动态 Trace ID 和长度限制占位参数可解析。
- 异常场景：覆盖 400、401、403、404、409、429、500、502 和 503，响应体 `code` 与 HTTP 状态一致。
- 权限与安全：未认证、无权限、API Key 无效、接口未授权、IP 限制和限流消息均使用统一本地化契约；未知异常不泄露内部文本。
- 兼容性：保留 `success/message/data` 字段、前端数据解包和 401 跳转；公开与内部成功响应不增加包装。
- 回归场景：系统配置、模型管理、接口触发、任务链路、API Key 及全部前端测试通过。
- 运行环境：Compose 已使用提交 `e959210` 的源码重建，三个服务均处于 healthy 状态。

## 🔄 当前重测触发条件

- 修改 `ApiResponse`、`ApiResponseAdvice`、`GlobalExceptionHandler` 或 `BusinessException`。
- 修改 Locale 解析、前端 `Accept-Language` 请求头或中英文消息资源。
- 新增或修改业务异常、校验消息、认证鉴权状态或 HTTP 状态映射。
- 修改公开/内部接口包装排除规则，或用户明确要求重新测试。

## ⚠️ 当前已知问题与限制

- 本机没有安装 `mvn`，Maven 测试通过官方 Maven Docker 镜像执行。
- 未使用真实登录账号验证 HTTP 200 的受保护业务接口；成功响应通过响应契约单元测试验证，401 双语响应通过运行中服务实测。
- 首次 Compose 启动分别遇到 8080 和 80 端口被 `domestic-trade-backend-1`、`domestic-trade-frontend-1` 占用；已按项目规则停止两个占用容器并重试成功。
- 未测试除 `zh-CN`、`en-US` 之外的语言；当前 Locale 解析器只声明支持中文和英文。
- `TaskTraceService` 的既有未检查泛型编译提示和前端大 Chunk 构建警告未影响测试结果。

## 📝 下次测试建议

1. 使用真实 MySQL、Redis 和登录账号补充受保护接口 200/400/403 的端到端双语验证。
2. 发布前通知外部调用方将字符串 `SUCCESS` 等判断迁移为数字 HTTP 状态码。
3. 后续新增业务异常时同时补充中英文消息键，并保留消息资源完整性测试。

**当前 Git 基准点**：`e959210`

---

## 历史测试记录（固定长度 API Key）

## 📋 Git 基准点

- Commit: 8341a18
- 提交说明: Generate fixed length API keys
- 测试日期: 2026-07-25
- 分支: master

## 🎯 当前变更范围

- 新创建及轮换的 API Key 使用 `sk-` 后接固定 32 位大小写字母和数字。
- 使用随机串前 12 位作为数据库查询标识，完整随机串仅保存 HMAC-SHA256 摘要。
- 列表脱敏值使用 `sk-<前12位>****` 格式，不暴露完整 Key。
- 历史 `bai_live_<keyId>.<secret>` 和 `sk-<keyId>.<secret>` 格式立即失效。

## 📋 当前变更测试结果（2026-07-25）

**变更范围**：API Key 调整为严格匹配 `sk-[A-Za-z0-9]{32}` 的固定长度格式，并停用两代点分格式。

**测试执行结果**：
- 总测试用例：112 个
- API Key 定向测试：20 个，通过 20 个（100%）
- 后端完整测试：112 个，通过 112 个（100%）
- 通过：112 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- API Key Secret 生成、解析、长度边界、非法字符、历史格式拒绝和摘要校验：9/9，通过
- API Key 身份认证、接口授权、IP 和异常路径：4/4，通过
- API Key 管理创建及配置校验：7/7，通过
- 后端完整回归：112/112，通过
- 前端测试：本次未执行，未修改前端代码
- Python Worker 测试：本次未执行，未修改 Worker 代码

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| API Key 定向测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=ApiKeySecretServiceTest,ApiKeyAuthenticationServiceTest,ApiKeyManagementServiceTest test` | 20 通过，0 失败，0 错误，0 跳过 |
| 后端完整测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 112 通过，0 失败，0 错误，0 跳过 |
| 服务重建与启动 | `docker compose up --build -d` | 三个镜像构建成功，Backend 构建内 112 个测试通过，Frontend 生产构建通过，三个服务启动成功 |
| 服务健康检查 | `docker compose ps`、`curl -fsS http://localhost/health`、`curl -fsS http://localhost:8080/api/open/health` | Backend、Frontend、Python Worker 全部 healthy，两个端点返回 UP |
| 差异格式检查 | `git diff --check` | 通过 |

## 🔄 当前覆盖范围与结果

- 正常场景：生成结果严格符合 `sk-[A-Za-z0-9]{32}`，可解析并通过摘要校验。
- 边界场景：31 位和 33 位随机串均被拒绝，仅接受固定 32 位。
- 异常场景：空值、非法前缀、下划线及两代历史点分格式统一返回 API Key 无效。
- 权限与安全：数据库仅保存随机串前 12 位查询标识和完整随机串的 HMAC-SHA256 摘要，列表不展示完整 Key。
- 兼容性：按确认方案主动中止旧格式兼容，两代历史 Key 均需轮换后使用。
- 回归场景：API Key 身份认证、接口授权、IP 白名单、限流及后端全部 112 个测试通过。
- 运行环境：Compose 已使用变更源码重新构建，三个服务均处于 healthy 状态。

## 🔄 当前重测触发条件

- 修改 `ApiKeySecretService` 的生成格式、解析规则、摘要或脱敏逻辑。
- 修改 API Key 管理服务、认证服务、实体、Repository 或 Controller。
- 修改接口授权、IP 白名单、限流、Hash Secret 配置或 Bearer Token 共存规则。
- 用户明确要求重新执行完整覆盖测试。

## ⚠️ 当前已知问题与限制

- 本机没有安装 `mvn`，Maven 测试通过官方 Maven Docker 镜像执行。
- 历史 `bai_live_<keyId>.<secret>` 和 `sk-<keyId>.<secret>` Key 不会自动转换，必须在管理页面执行轮换。
- 未执行真实登录态创建、调用、轮换的外部客户端端到端测试；当前通过单元测试、完整回归、构建和健康检查验证。
- `TaskTraceService` 的既有未检查泛型编译提示未影响测试结果。

## 📝 下次测试建议

1. 补充真实登录态端到端测试，覆盖创建 `sk-` Key、调用开放接口、轮换和吊销。
2. 在发布前通知 API Key 使用方完成旧 Key 轮换，避免接口调用中断。
3. 补充真实 MySQL 与 Redis 环境下的 API Key 全链路集成测试。

**当前 Git 基准点**：`8341a18`

---

## 历史测试记录（API Key）

- API Key 调用频次支持每秒、每分钟、每小时、每天和无限制五种模式。
- 受限模式继续使用 Redis 自然固定窗口计数，无限制模式不访问 Redis。
- 管理接口新增 `rateLimitType` 和 `rateLimitCount`，继续兼容历史 `rateLimitPerMinute` 请求字段。
- 数据库保留历史每分钟字段，并在应用启动时将旧数据回填为 `MINUTE + 原调用次数`。
- API Key 管理页面新增周期选择、次数输入、无限制交互和列表展示。
- 开放 API 名称和分组使用国际化 Key，并在词条缺失时回退接口编码。

## 📋 本次变更测试结果（2026-07-25）

**变更范围**：API Key 多周期限流、历史数据兼容迁移、管理页面交互及开放接口目录国际化。

**测试执行结果**：
- 总测试用例：169 个
- 通过：169 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- API Key 限流后端定向测试：19/19，通过
- 后端完整测试：101/101，通过
- 前端限流定向测试：2/2，通过
- 前端完整回归：68/68，通过
- Python Worker：本次未执行，未修改 Worker 代码
- 前端生产构建：通过
- Compose 服务健康检查：Backend、Frontend、Python Worker 全部 healthy

**实现与验证确认**：
- 覆盖 SECOND、MINUTE、HOUR、DAY 的固定窗口键和缓存过期时间。
- 覆盖调用次数临界值、超限 429、Redis 异常 503 和无限制跳过 Redis。
- 覆盖历史每分钟请求字段兼容以及旧数据库记录启动回填。
- 覆盖管理页面五种周期选项、无限制空次数提交和旧响应字段回退。
- 覆盖开放接口名称、分组国际化及词条缺失回退。
- 迭代中首次定向测试因无限制用例存在多余 Mockito 桩而报错，移除严格桩冲突后复测通过。
- 并发修改期间一次完整测试遇到 `ApiKeyEndpoint` 新旧签名短暂不一致导致测试编译失败，同步当前工作树后复测 101/101 通过。

**Git 基准点**：dd4c6e5

## 📊 测试执行结果

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| API Key 后端定向测试 | `docker run --rm -v "$PWD":/workspace -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -ntp -Dtest=ApiKeyRateLimiterTest,ApiKeyAuthenticationServiceTest,ApiKeyManagementServiceTest,ApiKeyRateLimitDataMigrationTest,ApiKeyManagementControllerTest test`（在 `backend/` 执行） | 最终 19 通过，0 失败，0 错误，0 跳过；首次运行 1 个 Mockito 严格桩错误，修复后通过 |
| 前端 API Key 限流定向测试 | `node --test frontend/test/api-key-rate-limit.test.mjs` | 2 通过，0 失败，0 错误，0 跳过 |
| 后端完整测试 | `docker run --rm -v "$PWD":/workspace -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B -ntp`（在 `backend/` 执行） | 最终 101 通过，0 失败，0 错误，0 跳过；并发签名同步前曾出现 1 次测试编译失败 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs` | 68 通过，0 失败，0 错误，0 跳过 |
| Python Worker 回归 | 本次未执行；本次未修改 Worker 代码 | 未执行 |
| 服务重建与启动 | `docker compose up --build -d` | 镜像构建通过，三个服务成功启动；Backend 镜像构建阶段 101 个测试通过 |
| 前端生产构建 | `docker compose up --build -d`（Frontend 镜像内执行 `npm run build`） | 通过 |
| 服务健康检查 | `docker compose ps --format json` | Backend、Frontend、Python Worker 全部 healthy |
| 本地页面预览 | IDE 应用预览打开 `http://localhost:80` | 页面可访问；未执行登录后的浏览器端到端操作 |

## 🔄 覆盖范围与结果

- 正常场景：每秒、每分钟、每小时、每天和无限制配置均有测试。
- 边界场景：1、100000、0、100001、空次数和无限制附带次数均有覆盖。
- 异常场景：超限返回 429，Redis 不可用返回 503，无限制不依赖 Redis。
- 权限与安全：限流变更不改变接口授权、用户 RBAC、IP 白名单和 Secret 校验顺序。
- 兼容性：历史 `rateLimitPerMinute` 请求、历史数据库记录和管理页面旧响应字段均保持兼容。
- 前端：覆盖周期切换、无限制隐藏次数、提交字段、列表格式化和中英文文案。
- 运行环境：Compose 已基于提交 `dd4c6e5` 对应源码重新构建，Backend、Frontend、Python Worker 均处于 healthy 状态。

## 🔄 重测触发条件

- 修改 `backend/src/main/java/` 下 API Key 实体、摘要、双认证拦截器、接口目录、IP 校验、限流或管理服务。
- 修改 `@ApiKeyEndpoint`、`@RequiredPermission`、绑定用户逻辑、永久有效规则或操作审计字段。
- 修改 API Key 管理 Controller、前端管理页面、路由、菜单权限或 Compose/API Key 环境配置。
- 修改 Redis 限流键、用户权限、数据范围或现有 Bearer Token 认证逻辑。
- 用户明确要求重新执行完整覆盖测试。

## ⚠️ 已知问题与限制

- 尚未增加登录后的真实浏览器端到端测试，前端交互通过源码级 Node 测试、生产构建和页面可访问性验证。
- 尚未执行真实外部客户端创建 Key、调用 AI API、轮换和吊销的全链路集成测试；当前通过单元测试、构建和服务健康检查验证。
- API Key 来源地址默认读取 `X-Forwarded-For`，生产环境应确保只有可信反向代理可以写入该请求头，避免客户端伪造来源 IP。
- 首批仅开放 AI 对话和正式接口触发执行；其他业务接口需要增加 `@ApiKeyEndpoint` 并经过安全评审。
- `TaskTraceService` 存在既有未检查泛型操作编译提示，未影响测试结果。
- 前端构建存在既有 runtime-config、第三方 PURE 注释和包体积警告，不影响构建成功。
- 固定自然窗口在时间边界附近允许短时突发，这是现有 Redis 计数模型的延续，并非滚动窗口。

## 📝 下次测试建议

1. 补充真实 MySQL 与 Redis 集成测试，验证旧字段回填、不同周期切换和多实例计数一致性。
2. 补充登录后的浏览器端到端测试，验证五种周期切换、无限制保存和编辑回显。
3. 如业务需要严格平滑流量，评估滑动窗口或令牌桶算法，并增加突发流量性能测试。
4. 在可信代理配置下继续验证 `X-Forwarded-For` 来源解析和 API Key 全链路调用。
