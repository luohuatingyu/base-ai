# 最近分支覆盖测试报告

## 📋 Git 基准点

- Commit: 4c6db67
- 提交说明: Add structured API trigger host rules
- 测试日期: 2026-07-25
- 分支: master

## 🎯 本次变更范围

- 移除 `API_TRIGGER_ALLOWED_HOSTS` 和 `API_TRIGGER_ALLOW_PRIVATE_NETWORK` 环境变量配置。
- 新增接口触发安全配置专用页面、后端接口、菜单和查看/更新权限。
- Host 规则、回环开关和其他私网开关保存到现有系统参数表，并通过 Redis 缓存实现保存后立即生效。
- 未保存配置时额外 Host 白名单为空，回环开关默认开启，其他私网开关默认关闭。
- `localhost`、`127.0.0.1` 和 `::1` 无需加入 Host 白名单；读取旧配置时会自动从页面值中剔除。
- Host 规则升级为精确、域名边界前缀、域名边界后缀、字符串包含和任意 Host 五种结构化类型。
- 新规则使用 `api.trigger.host-rules` JSON 存储；旧精确、`*.example.com` 和 `*` 自动转换读取，无需数据库迁移。
- 通用系统参数页面隐藏并禁止修改新旧 Host 规则和两个网络开关配置键。
- 私网阻止逻辑补充 IPv6 ULA（`fc00::/7`）识别。

## 📋 本次变更测试结果（2026-07-25）

**变更范围**：接口触发结构化 Host 规则、五类匹配语义、旧配置兼容、API Key 风格规则编辑器及 SSRF 安全边界。

**测试执行结果**：
- 总测试用例：145 个
- 通过：145 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- Domain 层：未修改，通过 Service 回归间接覆盖
- API Trigger Security Service：5/5，通过
- API Trigger URL Policy：9/9，通过
- System Configuration Service：3/3，通过
- 新增 Controller 权限测试：2/2，通过
- 后端完整测试：73/73，通过
- 前端完整回归：60/60，通过
- Python Worker：12/12，通过，运行时为 Python 3.12
- 前端生产构建：通过
- Compose 服务健康检查：Backend、Frontend、Python Worker 全部 healthy

**实现与验证确认**：
- 后端定向测试覆盖 JSON 规则读写、旧规则转换、规则类型和值校验、精确/前缀/后缀/包含/任意 Host 匹配和两个网络开关。
- 前端定向测试覆盖 API Key 风格逐条新增删除、五种类型、规则规范化、ANY 值清理、加载保存和完全开放二次确认。
- 第一次执行 `docker compose up --build -d` 时，镜像和构建内测试均成功，但宿主机 `8080` 被 `domestic-trade-backend-1` 占用；按用户指示停止 `domestic-trade` 三个容器后重新执行，当前三个 `base-ai` 服务全部健康。
- 本地忽略文件 `.env` 中的旧 Host 和私网变量已清除；仓库内 `.env.example`、Compose 和 Spring 配置均不再引用这两个变量。

**Git 基准点**：4c6db67

## 📊 测试执行结果

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 后端定向测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=ApiTriggerSecurityConfigurationServiceTest,ApiTriggerUrlPolicyTest,ApiTriggerSecurityConfigurationControllerTest,SystemConfigurationServiceTest test` | 19 通过，0 失败，0 错误，0 跳过 |
| 前端定向测试 | `node --test frontend/test/api-trigger-security.test.mjs frontend/test/host-rules.test.mjs frontend/test/api-trigger.test.mjs frontend/test/navigation.test.mjs` | 24 通过，0 失败，0 跳过 |
| 后端完整测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 73 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs` | 60 通过，0 失败，0 跳过 |
| Python Worker 回归 | `docker run --rm -v "$PWD/python-worker:/workspace" -w /workspace python:3.12-slim sh -c "pip install --no-cache-dir -q -r requirements.txt pytest && python -m pytest -q"` | Python 3.12；12 通过，0 失败，0 跳过 |
| 服务重建与启动 | `docker compose up --build -d` | 镜像构建通过；清理宿主机 8080 端口冲突后，三个服务成功启动 |
| 前端生产构建 | `docker compose up --build -d`（Frontend 镜像内执行 `npm run build`） | 通过 |
| 服务健康检查 | `docker compose ps` | Backend、Frontend、Python Worker 全部 healthy |
| 启动日志检查 | `docker compose logs --tail=25 backend`、`docker compose logs --tail=15 frontend` | 未发现本次变更相关启动错误 |
| 差异格式检查 | `git diff --check` | 通过 |

## 🔄 覆盖范围与结果

- 正常场景：五种 Host 规则均覆盖正例；`suffix=factory.ai` 允许 `factory.ai` 和 `app.factory.ai`，前缀规则同样按域名标签边界生效。
- 边界场景：前缀 `app` 不匹配 `application.ai`；后缀 `factory.ai` 不匹配 `evilfactory.ai`；规则大小写统一并按类型和值去重。
- 兼容场景：旧精确值转换为 `EXACT`、旧 `*.domain` 转换为 `SUFFIX`、旧 `*` 转换为 `ANY`，内置回环值继续自动剔除。
- 异常场景：非法类型、空规则值、协议、端口、非法 DNS 名称、非法包含字符和非 HTTP/HTTPS URL 均被拒绝。
- 权限与安全：任意 Host 不绕过回环和其他私网开关；通用参数入口无法修改新旧 Host 规则键；私网地址分类保持兼容。
- 动态生效：URL 策略每次校验读取当前有效配置；保存事务清除共享 Redis 缓存，后续请求无需重启即可读取新值。
- 兼容性：接口触发任务结构、调度参数和网络开关不变；旧 Host 配置只读迁移，新规则写入独立键以保留代码回滚能力。
- 前端：覆盖专用路由、导航、国际化、权限按钮、规则行新增删除、类型切换、ANY 无值展示和现有接口触发页面回归。
- Worker：完整测试通过，本次未修改 Worker 业务代码。
- 运行环境：Compose 已基于提交 `4c6db67` 重新构建，Backend、Frontend、Python Worker 均处于 healthy 状态。

## 🔄 重测触发条件

- 修改 `backend/src/main/java/` 下接口触发 URL 校验、运行时配置读取、缓存失效或系统参数隔离逻辑。
- 修改接口触发安全配置 Controller、权限编码、菜单初始化或前端专用配置页面。
- 修改 Host 规则 JSON、匹配类型、前后缀边界、回环默认值或私网地址识别范围。
- 修改 Redis 缓存键、系统参数固定键、配置持久化方式或 Compose 运行配置。
- 用户明确要求重新执行完整覆盖测试。

## ⚠️ 已知问题与限制

- `ANY` 配合开启回环和其他私网会允许任意 HTTP/HTTPS Host，页面已显示警告并要求二次确认，但管理员仍需自行评估 SSRF 风险。
- `CONTAINS` 使用普通字符串包含语义，范围可能宽于精确或域名边界规则，管理员需谨慎配置短字符串。
- 回滚到不支持结构化规则的旧版本时，新前缀和包含规则不会生效；旧 `api.trigger.allowed-hosts` 值被保留用于回滚。
- 非私网模式在 URL 校验时解析 DNS，校验与真实连接之间仍存在 DNS 变化窗口；本次未实现连接层 DNS 固定。
- 尚未增加真实浏览器端到端测试，前端交互通过源码级 Node 测试和生产构建验证。
- 后端编译仍提示 `TaskTraceService` 存在既有未检查泛型操作，未影响测试结果。
- 前端构建仍有既有 runtime-config、第三方 PURE 注释和包体积警告，不影响构建成功。
- `domestic-trade` 三个容器已按用户指示停止，未删除容器或镜像。

## 📝 下次测试建议

1. 补充真实 MySQL 与 Redis 集成测试，验证专用配置事务提交、跨实例缓存失效和显式空白名单持久化。
2. 补充浏览器端到端测试，验证不同权限用户的页面可见性、保存动作、风险确认和错误提示。
3. 增加 DNS rebinding 防护验证，评估在 HTTP 连接层固定解析地址或使用受控 DNS 解析器。
4. 后续修改默认回环范围、私网地址识别或星号语义时，基于本报告基准点重新执行完整测试。
