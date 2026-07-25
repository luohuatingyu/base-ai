# 最近分支覆盖测试报告

## 📋 Git 基准点

- Commit: 6317a07
- 提交说明: Add scoped API key authentication
- 测试日期: 2026-07-25
- 分支: master

## 🎯 本次变更范围

- 新增 API Key 双认证入口，调用方可通过 `X-API-Key` 访问 Java 接口，无需先获取 Bearer Token。
- API Key 绑定现有用户，实际权限取 API Key 接口授权和用户 RBAC 权限的交集。
- 新增 `@ApiKeyEndpoint` 代码开放目录，禁止页面配置任意 URL。
- 新增 API Key 管理页面，支持创建、编辑、停用、轮换、吊销、绑定用户、接口授权、IP 白名单和限流。
- 有效期支持指定时间和永久有效；永久有效使用 `expires_at = NULL`，仍受停用、吊销、用户状态和限流控制。
- Secret 仅保存 HMAC-SHA256 摘要，完整明文只在创建或轮换成功时返回一次。
- API Key 管理接口始终只接受 Bearer Token；操作审计增加凭证类型、凭证 ID 和名称。
- `APP_API_KEY_HASH_SECRET` 可单独配置，未配置时回退到 `APP_CONFIG_ENCRYPTION_KEY`。

## 📋 本次变更测试结果（2026-07-25）

**变更范围**：API Key 双认证、接口级授权、永久有效 Key、IP 白名单、限流、审计、管理页面及既有接口触发安全功能。

**测试执行结果**：
- 总测试用例：159 个
- 通过：159 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- API Key 安全定向测试：21/21，通过
- 后端完整测试：94/94，通过
- 前端 API Key 定向及兼容测试：14/14，通过
- 前端完整回归：65/65，通过
- Python Worker：本次未执行，未修改 Worker 代码
- 前端生产构建：通过
- Compose 服务健康检查：Backend、Frontend、Python Worker 全部 healthy

**实现与验证确认**：
- 覆盖 Secret 生成、摘要校验、非法格式、超长输入和恒定时间比较。
- 覆盖 IPv4、IPv6、精确 IP、CIDR、非法地址和越界前缀。
- 覆盖接口未开放、Key 未授权、Key 过期、停用、来源 IP 不匹配和限流超限。
- 覆盖 Bearer Token 兼容、Token/API Key 同时提交拒绝、API Key 管理接口 Bearer-only。
- 覆盖永久有效与指定过期时间互斥、绑定用户停用、接口授权和一次性 Secret 展示。
- Compose 首次重建时宿主机 `8080` 被 `domestic-trade-backend-1` 占用、`80` 被 `domestic-trade-frontend-1` 占用；按项目规则停止两个冲突容器后重建成功。
- `docker compose config` 验证 `APP_API_KEY_HASH_SECRET` 未配置时正确回退到 `APP_CONFIG_ENCRYPTION_KEY`。

**Git 基准点**：6317a07

## 📊 测试执行结果

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| API Key 后端定向测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=ApiKeySecretServiceTest,ApiKeyCidrMatcherTest,ApiKeyAuthenticationServiceTest,ApiKeyManagementServiceTest,ApiKeyManagementControllerTest,AuthInterceptorTest,ApiKeyRateLimiterTest test` | 21 通过，0 失败，0 错误，0 跳过 |
| 前端 API Key 定向测试 | `node --test frontend/test/platform-api-keys.test.mjs frontend/test/navigation.test.mjs frontend/test/api-keys.test.mjs` | 14 通过，0 失败，0 错误，0 跳过 |
| 后端完整测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 94 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs` | 65 通过，0 失败，0 错误，0 跳过 |
| Python Worker 回归 | 本次未执行；本次未修改 Worker 代码 | 未执行 |
| Compose 配置检查 | `docker compose config` | API Key 摘要密钥回退配置展开通过 |
| 服务重建与启动 | `docker compose up --build -d` | 镜像构建通过；清理宿主机 8080、80 端口冲突后，三个服务成功启动 |
| 前端生产构建 | `docker compose up --build -d`（Frontend 镜像内执行 `npm run build`） | 通过 |
| 服务健康检查 | `docker compose ps`、`curl -fsS http://localhost/health`、`curl -fsS http://localhost:8080/api/open/health` | Backend、Frontend、Python Worker 全部 healthy；两个健康端点返回 UP |
| 启动日志检查 | `docker compose logs --tail=120 backend`、`docker compose logs --tail=30 frontend` | 未发现本次变更相关启动错误 |
| 差异格式检查 | `git diff --check` | 通过 |

## 🔄 覆盖范围与结果

- 正常场景：有效 API Key 调用已开放且已授权接口；Bearer Token 原有流程继续通过。
- 边界场景：永久有效、未来过期时间、空 IP 白名单、精确 IP、IPv4/IPv6 CIDR 和限流临界值均有测试。
- 异常场景：非法 Key、错误 Secret、过期、停用、吊销、非法 IP、未开放接口、未授权接口和 Redis 限流异常均有测试。
- 权限与安全：Key 权限不能超过绑定用户 RBAC 权限；API Key 管理接口不能使用 API Key；双凭证请求被拒绝；Secret 不落明文。
- 兼容性：Bearer Token、现有接口触发安全配置、既有前端页面和完整前端回归均通过。
- 前端：覆盖独立路由、菜单权限、国际化、Key 创建编辑、永久有效确认、接口勾选、IP 白名单、一次性 Secret、轮换和吊销。
- 运行环境：Compose 已基于提交 `6317a07` 重新构建，Backend、Frontend、Python Worker 均处于 healthy 状态。

## 🔄 重测触发条件

- 修改 `backend/src/main/java/` 下 API Key 实体、摘要、双认证拦截器、接口目录、IP 校验、限流或管理服务。
- 修改 `@ApiKeyEndpoint`、`@RequiredPermission`、绑定用户逻辑、永久有效规则或操作审计字段。
- 修改 API Key 管理 Controller、前端管理页面、路由、菜单权限或 Compose/API Key 环境配置。
- 修改 Redis 限流键、用户权限、数据范围或现有 Bearer Token 认证逻辑。
- 用户明确要求重新执行完整覆盖测试。

## ⚠️ 已知问题与限制

- 尚未增加真实浏览器端到端测试，前端交互通过源码级 Node 测试和生产构建验证。
- 尚未执行真实外部客户端创建 Key、调用 AI API、轮换和吊销的全链路集成测试；当前通过单元测试、构建和服务健康检查验证。
- API Key 来源地址默认读取 `X-Forwarded-For`，生产环境应确保只有可信反向代理可以写入该请求头，避免客户端伪造来源 IP。
- 首批仅开放 AI 对话和正式接口触发执行；其他业务接口需要增加 `@ApiKeyEndpoint` 并经过安全评审。
- `TaskTraceService` 存在既有未检查泛型操作编译提示，未影响测试结果。
- 前端构建存在既有 runtime-config、第三方 PURE 注释和包体积警告，不影响构建成功。
- `domestic-trade-backend-1` 和 `domestic-trade-frontend-1` 已按端口冲突规则停止，未删除容器或镜像。

## 📝 下次测试建议

1. 补充真实 MySQL 与 Redis 集成测试，验证 Key 持久化、限流计数、停用即时生效和多实例一致性。
2. 补充浏览器端到端测试，验证 Key 创建一次性 Secret、永久有效确认、接口授权、轮换和吊销。
3. 在可信代理配置下验证 `X-Forwarded-For` 来源解析，必要时增加代理网段白名单。
4. 后续开放更多 API 前，为每个 `@ApiKeyEndpoint` 增加风险评审和调用契约测试。
