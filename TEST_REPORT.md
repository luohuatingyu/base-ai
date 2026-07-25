# 最近分支覆盖测试报告

## 📋 Git 基准点

- Commit: 03641ff
- 提交说明: Explain scheduled model route synchronization
- 测试日期: 2026-07-25
- 分支: master

## 🎯 当前变更范围

- 模型路由自动同步默认改为开启，默认间隔保持 1 小时（`3600000` 毫秒）。
- Backend 和 Frontend 使用相同的环境变量，能力路由页面展示实际运行开关和间隔。
- 能力路由页面补充启动同步、定时同步和手动同步的动态说明。
- 同步间隔按天、小时、分钟、秒逐级拆分，例如 90 分钟展示为 1 小时 30 分钟。
- 手动模型路由同步接口、权限和既有同步行为保持不变。

## 📋 当前变更测试结果（2026-07-25）

**变更范围**：模型路由自动同步默认开启、前端运行时配置、动态同步说明和组合时间格式。

**测试执行结果**：
- 调度器定向测试：5 个，通过 5 个（100%）
- 能力路由前端定向测试：10 个，通过 10 个（100%）
- 后端完整测试：106 个，通过 106 个（100%）
- 前端完整测试：70 个，通过 70 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- 调度器默认开启与显式关闭：2/2，通过
- 启动同步与异常隔离：2/2，通过
- 默认间隔配置：1/1，通过
- 组合时间和动态说明：3/3，通过
- 后端完整回归：106/106，通过
- 前端完整回归：70/70，通过
- Python Worker 测试：本次未执行，未修改 Worker 代码

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 调度器定向测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=LlmRouteHealthSchedulerTest test` | 5 通过，0 失败，0 错误，0 跳过 |
| 能力路由前端定向测试 | `node --test frontend/test/model-route-health.test.mjs` | 10 通过，0 失败，0 错误，0 跳过 |
| 后端完整测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 106 通过，0 失败，0 错误，0 跳过 |
| 前端完整测试 | `node --test frontend/test/*.test.mjs` | 70 通过，0 失败，0 错误，0 跳过 |
| Compose 配置检查 | `docker compose config` | Backend 和 Frontend 默认接收 `LLM_ROUTE_HEALTH_CHECK_ENABLED=true` 和 `LLM_ROUTE_HEALTH_CHECK_INTERVAL_MS=3600000` |
| 服务重建与启动 | `docker compose up --build -d` | 三个镜像构建成功，Backend 构建内 106 个测试通过，Frontend 生产构建通过，三个服务启动成功 |
| 服务健康检查 | `docker compose ps`、`curl -fsS http://localhost/health`、`curl -fsS http://localhost:8080/api/open/health` | Backend、Frontend、Python Worker 全部 healthy，两个端点返回 UP |
| 运行配置检查 | `curl -fsS http://localhost/runtime-config.js`、Backend/Frontend 容器环境检查 | 页面和两个容器实际值均为 `true` 和 `3600000` |
| 差异格式检查 | `git diff --check` | 通过 |

## 🔄 当前覆盖范围与结果

- 正常场景：未配置开关时默认创建调度器，应用就绪后立即执行同步；页面显示每 1 小时同步一次。
- 边界场景：覆盖不足一秒、秒、分钟、小时、天和非法间隔回退；90 分钟展示为 1 小时 30 分钟。
- 异常场景：单次模型同步异常被记录并隔离，不中断调度调用。
- 权限与安全：本次不修改手动同步接口及权限控制，既有 Controller 测试继续通过。
- 兼容性：显式配置 `false` 仍可关闭调度器；手动同步逻辑和其他前后端模块完整回归通过。
- 运行环境：Compose 默认开启自动同步，同一开关和间隔已正确传入 Backend 与 Frontend。

## 🔄 当前重测触发条件

- 修改 `LlmRouteHealthScheduler` 的条件加载、启动触发或定时执行逻辑。
- 修改 `LLM_ROUTE_HEALTH_CHECK_ENABLED` 或 `LLM_ROUTE_HEALTH_CHECK_INTERVAL_MS` 的默认值和传递方式。
- 修改模型路由同步服务、Controller、模型实体或相关 Repository。
- 修改 Spring Scheduling 配置或用户明确要求重新执行完整覆盖测试。

## ⚠️ 当前已知问题与限制

- 本机没有安装 `mvn`，Maven 测试通过官方 Maven Docker 镜像执行。
- 当前间隔采用 `fixedDelay`，上一次同步完成后才开始计算下一小时等待时间。
- 调度器异常隔离测试会按预期输出一条模拟异常的 WARN 日志，不代表测试失败。
- 内置浏览器因缺少会话元数据无法建立交互连接；已完成本地预览打开、运行时配置检查、源码级页面测试和生产构建，但未执行登录后的浏览器点击回归。

## 📝 下次测试建议

1. 增加多次调度集成测试，验证实际时间间隔与并发行为。
2. 增加最近一次自动同步时间、结果和下次预计执行时间展示。
3. 浏览器连接恢复后补充登录态页面视觉和交互回归。

**当前 Git 基准点**：`03641ff`

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
