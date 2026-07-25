# 最近分支覆盖测试报告

## 📋 Git 基准点

- Commit: c6fa5dd
- 提交说明: Add dynamic API trigger security settings
- 测试日期: 2026-07-25
- 分支: master

## 🎯 本次变更范围

- 移除 `API_TRIGGER_ALLOWED_HOSTS` 和 `API_TRIGGER_ALLOW_PRIVATE_NETWORK` 环境变量配置。
- 新增接口触发安全配置专用页面、后端接口、菜单和查看/更新权限。
- Host 白名单和私网开关保存到现有系统参数表，并通过 Redis 缓存实现保存后立即生效。
- 未保存配置时默认仅允许 `localhost`、`127.0.0.1` 和 `::1` 回环地址。
- 支持精确 Host、`*.example.com` 和 `*`；`*` 仅放开 Host，私网访问仍由独立开关控制。
- 通用系统参数页面隐藏并禁止修改两个专用安全配置键。
- 私网阻止逻辑补充 IPv6 ULA（`fc00::/7`）识别。

## 📋 本次变更测试结果（2026-07-25）

**变更范围**：接口触发动态 Host 白名单、私网访问策略、专用配置页面、权限隔离、环境变量移除及 SSRF 安全边界。

**测试执行结果**：
- 总测试用例：136 个
- 通过：136 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- Domain 层：未修改，通过 Service 回归间接覆盖
- API Trigger Security Service：4/4，通过
- API Trigger URL Policy：6/6，通过
- System Configuration Service：3/3，通过
- 新增 Controller 权限测试：2/2，通过
- 后端完整测试：69/69，通过
- 前端完整回归：55/55，通过
- Python Worker：12/12，通过，运行时为 Python 3.12
- 前端生产构建：通过
- Compose 服务健康检查：Backend、Frontend、Python Worker 全部 healthy

**实现与验证确认**：
- 后端定向测试覆盖安全默认值、显式空白名单、规则规范化、非法规则、缓存失效、动态读取、通配域名、私网开关、IPv6 ULA 和接口权限。
- 前端定向测试覆盖独立路由、导航文案、加载/保存接口、更新权限、星号风险提示和完全开放二次确认。
- 第一次执行 `docker compose up --build -d` 时，镜像和构建内测试均成功，但宿主机 `8080` 被 `domestic-trade-backend-1` 占用；按用户指示停止 `domestic-trade` 三个容器后重新执行，当前三个 `base-ai` 服务全部健康。
- 本地忽略文件 `.env` 中的旧 Host 和私网变量已清除；仓库内 `.env.example`、Compose 和 Spring 配置均不再引用这两个变量。

**Git 基准点**：c6fa5dd

## 📊 测试执行结果

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 后端定向测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=ApiTriggerSecurityConfigurationServiceTest,ApiTriggerUrlPolicyTest,ApiTriggerSecurityConfigurationControllerTest,SystemConfigurationServiceTest test` | 15 通过，0 失败，0 错误，0 跳过 |
| 前端定向测试 | `node --test frontend/test/api-trigger-security.test.mjs frontend/test/api-trigger.test.mjs frontend/test/navigation.test.mjs` | 19 通过，0 失败，0 跳过 |
| 后端完整测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 69 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs` | 55 通过，0 失败，0 跳过 |
| Python Worker 回归 | `docker run --rm -v "$PWD/python-worker:/workspace" -w /workspace python:3.12-slim sh -c "pip install --no-cache-dir -q -r requirements.txt pytest && python -m pytest -q"` | Python 3.12；12 通过，0 失败，0 跳过 |
| 服务重建与启动 | `docker compose up --build -d` | 镜像构建通过；清理宿主机 8080 端口冲突后，三个服务成功启动 |
| 前端生产构建 | `docker compose up --build -d`（Frontend 镜像内执行 `npm run build`） | 通过 |
| 服务健康检查 | `docker compose ps` | Backend、Frontend、Python Worker 全部 healthy |
| 启动日志检查 | `docker compose logs --tail=25 backend`、`docker compose logs --tail=15 frontend` | 未发现本次变更相关启动错误 |
| 差异格式检查 | `git diff --check` | 通过 |

## 🔄 覆盖范围与结果

- 正常场景：默认回环 Host、精确 Host、子域通配、任意 Host 星号和私网开启组合均按配置通过。
- 边界场景：空白名单拒绝全部；重复规则去重；大小写、空白和 IPv6 方括号被规范化；IPv4、IPv6 和单标签容器 DNS 名称可配置。
- 异常场景：非法 URL、带协议或端口的 Host 规则、非法通配、越界 IPv4、非 HTTP/HTTPS URL 均被拒绝。
- 权限与安全：查看和更新使用独立权限；通用参数入口无法读取、创建、更新或删除专用键；关闭私网时拒绝回环、RFC1918、链路本地、组播和 IPv6 ULA 地址。
- 动态生效：URL 策略每次校验读取当前有效配置；保存事务清除共享 Redis 缓存，后续请求无需重启即可读取新值。
- 兼容性：接口触发任务结构、调度参数、精确 Host 和 `*.example.com` 匹配语义保持兼容；仅安全配置来源由环境变量迁移到页面。
- 前端：覆盖专用路由、导航、国际化、权限按钮、Host 输入解析、风险提示和现有接口触发页面回归。
- Worker：完整测试通过，本次未修改 Worker 业务代码。
- 运行环境：Compose 已基于提交 `c6fa5dd` 重新构建，Backend、Frontend、Python Worker 均处于 healthy 状态。

## 🔄 重测触发条件

- 修改 `backend/src/main/java/` 下接口触发 URL 校验、运行时配置读取、缓存失效或系统参数隔离逻辑。
- 修改接口触发安全配置 Controller、权限编码、菜单初始化或前端专用配置页面。
- 修改 Host 规则格式、默认白名单、私网地址识别范围或 `*` 的语义。
- 修改 Redis 缓存键、系统参数固定键、配置持久化方式或 Compose 运行配置。
- 用户明确要求重新执行完整覆盖测试。

## ⚠️ 已知问题与限制

- `*` 配合开启私网会允许任意 HTTP/HTTPS Host，页面已显示警告并要求二次确认，但管理员仍需自行评估 SSRF 风险。
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
