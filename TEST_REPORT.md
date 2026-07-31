# 最近分支覆盖测试报告

## 📋 Git 基准点

Commit: 88924e974578ff9eba1b0af2babac98d015534c7
- 提交说明: Add asynchronous HTTP request trace logging
- 测试日期: 2026-07-31
- 分支: master

## 🎯 当前变更范围

- 迁移 `ba23896b` 及其后续 HTTP 请求任务日志逻辑，按请求头、请求体、响应头、响应体分别记录日志并保持业务执行顺序。
- 文本、JSON、XML、表单、JavaScript 和 GraphQL 正文完整采集，不设置请求体或响应体最大长度。
- `image/*`、multipart、附件及其他文件或二进制正文不生成正文日志，仅保留请求/响应头、状态和耗时信息。
- 为未被业务追踪切面接管的已认证 API 请求异步创建 HTTP 兜底任务，并在同一异步执行中顺序标记成功或失败。
- API 触发器改为按上游声明字符集解码响应，JSON 未声明字符集时默认使用 UTF-8。

## 📋 当前变更测试结果（2026-07-31）

**变更范围**：HTTP 请求与响应详细日志、异步 HTTP 兜底任务、日志顺序和 API 触发响应解码。

**测试执行结果**：
- 后端定向测试：13 个，通过 13 个（100%）
- 后端完整测试：185 个，通过 185 个（100%）
- Compose 后端构建阶段测试：183 个，通过 183 个（100%，新增最后两个纯测试用例前执行）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- RequestContextFilter：3/3，通过完整长正文、不记录图片正文、请求和响应日志顺序校验。
- TraceIdInterceptor：3/3，通过后端 traceId、配置排除和注解排除接口 HTTP 日志上下文校验。
- HttpRequestTraceInterceptor：3/3，通过认证用户异步提交、已有业务任务去重和未认证请求跳过校验。
- HttpRequestTraceAsyncWriter：2/2，通过任务创建后成功/失败终态顺序校验。
- ApiTriggerService 响应解码：2/2，通过缺省 UTF-8 和显式 UTF-16LE 字符集校验。
- 后端完整回归：185/185，Security、Service、Controller、任务追踪和日志队列等历史功能全部通过。
- Compose 运行态：Backend、Frontend、Python Worker 最终均为 healthy。

**实施验证记录**：
- 宿主机未安装 Maven，直接执行 `mvn` 返回 `command not found`，随后使用 Maven 3.9.9 / Java 17 Docker 容器完成测试。
- 第一次定向测试 6 个中 1 个失败：旧测试仍断言 `@TraceIgnored` 接口不生成 traceId；迁移逻辑要求其保留 HTTP 日志 traceId，修正断言后通过。
- 首次 Compose 启动因 `domestic-trade-backend-1` 占用 8080 失败，停止旧后端容器后重试。
- 第二次启动因 `domestic-trade-frontend-1` 占用 80 失败，停止旧前端容器后重试。
- 最终 Backend、Frontend、Python Worker 均完成健康检查。

## ✅ 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入/操作 | 预期结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 完整记录文本请求和响应 | Filter 单元测试；20,000 字符 JSON | 请求和返回相同长正文 | 请求体和响应体日志包含完整正文且下游可重复读取 | 正常、边界、兼容性 |
| 图片和文件正文不记录 | Filter 单元测试；`image/png` 请求与响应 | 写入二进制图片字节 | 仅产生请求头和响应头日志，不产生正文日志 | 安全、边界 |
| HTTP 日志保持业务时序 | Filter 与拦截器单元测试 | 请求日志、业务执行、响应日志 | 请求头/体先于响应头/体，日志均关联 traceId | 正常、回归 |
| 未接管 API 异步创建任务 | 拦截器和异步写入器单元测试；已认证用户 | 完成无业务任务的 API 请求 | 提交异步任务并按创建、终态顺序写入 | 正常、性能 |
| 不重复创建任务 | 拦截器单元测试；请求已有任务标记 | 完成请求 | 不调用异步写入器 | 兼容性、回归 |
| 无归属用户不创建任务 | 拦截器单元测试；未认证请求 | 完成请求 | 不提交兜底任务 | 权限、安全 |
| HTTP 失败标记任务失败 | 异步写入器单元测试；503 状态摘要 | 执行异步写入 | 创建任务后写入失败状态和原因 | 异常 |
| 上游响应按正确字符集解码 | Service 单元测试；无 charset JSON 和 UTF-16LE 文本 | 调用响应解码逻辑 | 分别按 UTF-8 和声明字符集还原正文 | 正常、兼容性 |
| 历史功能保持稳定 | 后端完整测试与 Compose 运行态 | 执行完整测试、重建并启动服务 | 185 个测试通过，三个服务 healthy | 回归、集成 |

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 初次定向测试 | `docker run --rm -v /Users/xyzc/github/base-ai/backend:/workspace -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=RequestContextFilterTest,TraceIdInterceptorTest test` | 6 个中 5 通过、1 失败；旧注解排除断言与迁移语义不一致 |
| HTTP 日志与异步任务定向测试 | `docker run --rm -v /Users/xyzc/github/base-ai/backend:/workspace -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=RequestContextFilterTest,TraceIdInterceptorTest,HttpRequestTraceAsyncWriterTest,HttpRequestTraceInterceptorTest,ApiTriggerServiceResponseDecodingTest test` | 13 通过，0 失败，0 错误，0 跳过 |
| 后端完整回归 | `docker run --rm -v /Users/xyzc/github/base-ai/backend:/workspace -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 185 通过，0 失败，0 错误，0 跳过 |
| Compose 构建启动 | `docker compose up --build -d` | 后端构建测试 183/183 通过；处理 8080、80 端口占用后三个服务 healthy |
| Compose 状态检查 | `docker compose ps` | Backend、Frontend、Python Worker 均为 healthy |

## 📊 测试覆盖范围

- Web Filter：请求体缓存、请求头/体日志、响应头/体日志、内容类型判断、字符集处理和异常隔离。
- Web Interceptor：traceId 建立、配置排除、业务任务去重和异步兜底任务提交。
- Trace Service 集成边界：任务创建后成功或失败终态更新顺序。
- Automation Service：上游响应正文字符集解码。
- 回归范围：后端全部 185 个自动化测试及 Compose 三服务运行态。

## 🔄 重测触发条件

- 修改 `RequestContextFilter`、`TraceIdInterceptor`、`HttpRequestTraceInterceptor` 或异步写入器时必须重测。
- 修改任务追踪创建、终态更新、日志队列、追踪排除配置或认证上下文时必须重测。
- 修改可记录媒体类型、正文字符集规则或 API 触发 HTTP 调用时必须重测。
- 修改 `backend/src/main/java/` 下其他业务代码时按基准点重新执行后端完整测试。

## ⚠️ 已知问题

- 按确认要求，请求体和响应体不设置最大采集长度；超大文本正文会增加请求线程内存占用和日志队列压力。
- 图片、附件和二进制正文不打印，但响应包装器仍需参与响应回写流程。
- HTTP 兜底任务采用异步最终一致性，接口返回后任务列表可能短暂不可见。
- 异步线程池满载或服务异常退出时，兜底任务可能提交失败；失败会记录告警但不会影响接口响应。

## 📝 下次测试建议

1. 增加高并发和超大文本正文压力测试，评估无长度上限时的堆内存、GC 和日志队列压力。
2. 增加真实数据库集成测试，验证异步任务和任务日志批量落库的最终一致性。
3. 增加流式响应、压缩响应和更多二进制媒体类型的运行态验证。

## ↩️ 回滚方式

- 回滚提交 `88924e9` 并重新执行 `docker compose up --build -d`，可恢复迁移前的单条 HTTP 摘要日志，并移除 HTTP 兜底任务补齐逻辑。
- 本次不涉及数据库结构、依赖、配置迁移或文件删除。

# 历史测试记录（开放平台响应示例）

## 📋 Git 基准点

Commit: 66028e36bab6c54e5f8dd7b361ef47270bcbf843
- 提交说明: Document response field examples
- 测试日期: 2026-07-29
- 分支: master

## 🎯 当前变更范围

- 开放接口字段元数据新增可选枚举值，并由公开接口目录原样透传。
- 两个现有开放接口的响应字段补充与完整响应 JSON 一致的字段级示例值。
- 响应参数表以“示例值”替代“默认值”；路径和请求参数继续显示默认值。
- 仅存在枚举元数据时，示例值以深色、可复制 Tooltip 展示完整枚举范围；说明列保持原有展示方式。
- 补充中英文文案、样式及后端目录和前端页面契约测试。

## 📋 当前变更测试结果（2026-07-29）

**变更范围**：开放平台响应字段示例值、枚举元数据及响应文档展示。

**测试执行结果**：
- 完整回归测试：323 个，通过 323 个（100%）
- 后端完整测试：175 个，通过 175 个（100%）
- 前端完整测试：136 个，通过 136 个（100%）
- Python Worker 完整测试（Python 3.12）：12 个，通过 12 个（100%）
- 后端定向测试：5 个，通过 5 个（100%）
- 前端开放平台定向测试：8 个，通过 8 个（100%）
- 失败：0 个；错误：0 个；跳过：0 个

**关键模块测试**：
- ApiKeyEndpointCatalogService：4/4，通过响应字段示例值、枚举元数据、路径参数一致性和文档完整性校验。
- OpenPlatformController：1/1，确认公开目录保留字段文档但不泄露内部权限编码。
- 开放平台前端：8/8，确认响应表显示示例值，枚举仅在示例值上提供深色提示，请求与路径参数保留默认值。
- Compose 构建：后端构建阶段 175/175 通过，前端生产构建成功，三个服务均健康。

**缺陷复现记录**：
- 实现前前端定向测试 8 个中 1 个失败：响应参数表仍只配置默认值列，未使用示例值模式或枚举 Tooltip。
- 实现前后端定向测试在测试编译阶段失败：`ApiKeyField` 和公开目录字段模型均缺少 `enumValues`。
- 实现后定向、完整三端回归和运行态健康检查均通过。

## ✅ 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入/操作 | 预期结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 响应字段提供示例值 | 后端目录单元测试；AI 对话接口元数据 | 读取 `data.content` 响应字段 | 返回 `Hello!` 示例值 | 正常、兼容性 |
| 枚举元数据可公开 | 后端目录单元测试；构造含 READY、STOPPED 枚举的开放接口 | 扫描接口目录 | 保留示例值及枚举列表 | 正常、边界 |
| 响应表使用示例值而非默认值 | 前端页面契约测试；开放平台组件源码 | 检查响应表配置与列标题 | 响应表使用 `example` 模式和“示例值”标题 | 正常、兼容性 |
| 枚举仅作用于示例值 Tooltip | 前端页面契约测试；枚举字段元数据 | 检查条件渲染及 Tooltip 属性 | 仅有枚举值时渲染深色 Tooltip，说明列不变 | 边界、可用性 |
| 公开目录不泄露权限编码 | 后端 Controller 测试；模拟目录字段 | 调用公开目录接口 | 返回字段文档且不包含 permission | 安全、回归 |
| 历史功能保持稳定 | 三端完整回归和 Compose 运行态 | 执行全部测试、重建服务并检查健康端点 | 323 个测试通过，三个服务 healthy | 回归、集成 |

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 前端缺陷复现 | `cd frontend && node --test test/open-platform.test.mjs` | 实现前 7 通过、1 失败，稳定复现响应表未使用示例值和 Tooltip |
| 后端缺陷复现 | `docker run --rm -v /Users/xyzc/github/base-ai/backend:/workspace -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=ApiKeyEndpointCatalogServiceTest,OpenPlatformControllerTest test` | 实现前测试编译 3 个错误，稳定复现枚举元数据与目录字段模型缺失 |
| 前端定向测试 | `cd frontend && node --test test/open-platform.test.mjs` | 8 通过，0 失败，0 错误，0 跳过 |
| 后端定向测试 | `docker run --rm -v /Users/xyzc/github/base-ai/backend:/workspace -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=ApiKeyEndpointCatalogServiceTest,OpenPlatformControllerTest test` | 5 通过，0 失败，0 错误，0 跳过 |
| 后端完整回归 | `docker run --rm -v /Users/xyzc/github/base-ai/backend:/workspace -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 175 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `cd frontend && node --test test/*.test.mjs tests/*.test.js` | 136 通过，0 失败，0 错误，0 跳过 |
| Python Worker 完整回归 | `docker run --rm -e PYTHONPATH=/workspace -v /Users/xyzc/github/base-ai/python-worker:/workspace -w /workspace python:3.12-slim sh -lc 'pip install -q -r requirements.txt && pytest -q'` | 12 通过，0 失败，0 错误，0 跳过 |
| Compose 配置验证 | `docker compose config --quiet` | 通过 |
| 服务重建与启动 | `docker compose up --build -d` | 成功；构建阶段后端 175/175 通过，三个服务均 healthy |
| HTTP 健康检查 | `curl -fsS http://localhost:8080/api/open/health`、`curl -fsS http://localhost/health` | 两个端点均返回 `{"status":"UP"}` |

## 🔄 当前覆盖范围与结果

- 正常场景：响应字段示例值、公开目录透传和中英文示例值标题均已覆盖。
- 边界场景：无枚举字段不生成 Tooltip；存在多个枚举值时完整透传并展示。
- 异常场景：缺失枚举元数据时页面安全回退为普通示例文本，不影响字段文档渲染。
- 权限与安全：公开目录继续移除内部 RBAC 权限编码；Tooltip 仅展示声明的枚举值，不引入敏感运行数据。
- 兼容性：`enumValues` 为新增可选字段，请求和路径参数仍使用原默认值展示，既有调用不受影响。
- 回归场景：后端 175 个、前端 136 个、Python Worker 12 个测试全部通过。

## 🔄 当前重测触发条件

- 修改 `ApiKeyField`、开放接口目录映射或公开接口文档结构。
- 修改开放接口响应字段示例、枚举值或完整响应示例。
- 修改开放平台字段表的列模式、Tooltip 或本地化文案。
- 用户明确要求重新验证开放接口文档展示行为。

## ⚠️ 当前已知问题与限制

- 当前两个生产开放接口不存在真实枚举型响应字段，因此页面仅在后续接口显式声明 `enumValues` 时展示 Tooltip；该分支已由目录和页面契约测试覆盖，未虚构业务枚举。
- 未执行浏览器自动化 E2E；页面行为由源码契约测试、生产构建和运行态健康检查覆盖。
- 前端生产构建保留既有 runtime-config、第三方 PURE 注释和大包体积警告，构建未失败。

## 📝 下次测试建议

1. 新增枚举型响应字段时，补充对应的端到端接口文档截图或浏览器测试，验证悬浮提示文案和小屏布局。
2. 开放接口响应结构调整时，同时更新字段级 `example` 与 `responseExample`，保持两类示例一致。
3. 如需让非枚举补充说明也使用 Tooltip，应新增独立元数据字段，避免复用枚举语义。

## ↩️ 回滚方式

- 回滚提交 `66028e3` 并重新执行 `docker compose up --build -d`，即可恢复响应参数表的默认值展示及原目录模型。
- 本次不涉及数据库迁移、依赖、配置或文件删除。

## 历史测试记录（角色权限层级）

- 角色新增、编辑由扁平权限多选改为“目录—页面—按钮”树形配置和回显。
- 权限树采用精确依赖：勾选按钮自动补齐所属页面及目录，取消页面清除按钮，勾选页面不自动授予全部按钮。
- 后端新增角色权限依赖校验，阻止直接通过接口保存按钮-only 权限。
- 规范化 5 个内置按钮的父级：强制下线、任务管理以及用户、角色、菜单的兼容管理权限均归属对应页面。
- 增加中英文提示、响应式树形样式以及前后端自动化测试。

## 📋 当前变更测试结果（2026-07-29）

**变更范围**：角色权限树、精确依赖联动、服务端权限校验、内置菜单按钮层级及双语提示。

**测试执行结果**：
- 完整回归测试：321 个，通过 321 个（100%）
- 后端完整测试：174 个，通过 174 个（100%）
- 前端现行测试：4 个，通过 4 个（100%）
- 前端历史完整回归：131 个，通过 131 个（100%）
- Python Worker 完整测试（Python 3.12）：12 个，通过 12 个（100%）
- 后端定向测试：17 个，通过 17 个（100%）
- 前端角色权限及相关定向测试：33 个，通过 33 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- PlatformAdminService：6/6，通过新增、编辑、空权限、按钮-only 拒绝和合法页面+按钮保存场景。
- DataInitializer：8/8，通过全部内置按钮直接归属页面及既有管理员初始化回归。
- 消息资源：3/3，通过中英文键完整性和占位符一致性校验。
- 前端角色权限：8/8，通过树构建、排序、回显、精确联动、取消联动和异常孤立按钮场景。
- Controller 与 Security：完整后端回归通过，角色接口原有鉴权注解和认证链路未发生回归。

**缺陷复现记录**：
- 实现前前端定向测试因角色权限树工具不存在而失败，确认原页面不具备树形配置能力。
- 实现前后端定向测试共 12 个，其中 3 个按预期失败：新增和编辑均接受按钮-only 权限，且内置按钮存在非页面父级。
- 实现后相同场景及扩展边界测试全部通过。

## ✅ 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入/操作 | 预期结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 新增、编辑以完整权限树展示 | 前端单元与源码集成测试；提供无序目录、页面、按钮数据 | 构建角色权限树并检查页面组件 | 节点按目录、页面、按钮层级和排序展示，弹窗使用复选树 | 正常、兼容性 |
| 编辑时正确回显历史权限 | 前端单元测试；历史角色仅包含按钮 ID | 规范化已选权限 | 自动补齐所属页面及上级目录，按钮保持选中 | 正常、兼容性 |
| 按钮必须依赖所属页面 | 前端参数化逻辑测试；标准权限树 | 单独勾选按钮 | 仅补齐所属页面和目录，不授予同级按钮 | 正常、安全 |
| 页面不反向授予全部按钮 | 前端单元测试；页面下有多个按钮 | 单独勾选页面 | 只选中页面和目录，所有按钮保持未选 | 边界、最小权限 |
| 取消页面清除按钮权限 | 前端单元测试；页面及按钮均已选 | 取消页面 | 页面和下属按钮取消，目录可独立保留 | 正常、回归 |
| 异常孤立按钮无法配置 | 前端单元测试；按钮直接挂在目录下 | 勾选孤立按钮或回显其历史 ID | 按钮不会进入最终选择集合 | 异常、安全 |
| API 无法绕过页面依赖 | 后端 Service 测试；页面和按钮层级完整 | 新增或编辑仅提交按钮 ID | 抛出本地化业务异常且角色不保存 | 异常、安全 |
| 合法权限及空权限保持兼容 | 后端 Service 测试；仓储隔离 | 提交页面+按钮、空集合或 null | 合法权限正常保存，空权限角色仍可创建 | 正常、边界、兼容性 |
| 所有内置按钮归属页面 | DataInitializer 单元测试；为种子菜单分配稳定测试 ID | 执行完整初始化并遍历按钮 | 每个 BUTTON 的直接父级均为 MENU | 数据、回归 |
| 历史功能保持稳定 | 三端完整回归和 Compose 运行态 | 执行全部测试、重建服务并健康检查 | 321 个测试全部通过，三个服务 healthy | 回归、集成 |

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 前端缺陷复现 | `cd frontend && node --test test/role-permissions.test.mjs` | 实现前因权限树工具不存在而失败，稳定复现扁平配置缺陷 |
| 后端缺陷复现 | `docker run --rm -v "$PWD/backend:/workspace" -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=PlatformAdminServiceTest,DataInitializerTest test` | 实现前 12 个测试中 3 个失败，稳定复现按钮依赖和内置层级缺陷 |
| 前端定向测试 | `cd frontend && node --test test/role-permissions.test.mjs test/localization.test.mjs test/layout-alignment.test.mjs` | 33 通过，0 失败 |
| 后端定向测试 | `docker run --rm -v "$PWD/backend:/workspace" -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=PlatformAdminServiceTest,DataInitializerTest,MessageBundleTest test` | 17 通过，0 失败，0 错误，0 跳过 |
| 后端完整回归 | `docker run --rm -v "$PWD/backend:/workspace" -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 174 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `cd frontend && npm test && node --test test/*.test.mjs` | 135 通过，0 失败 |
| Python Worker 完整回归 | `docker run --rm -e PYTHONPATH=/workspace -v "$PWD/python-worker:/workspace" -w /workspace python:3.12-slim sh -lc 'pip install -q -r requirements.txt && pytest -q'` | 12 通过，0 失败 |
| Compose 配置验证 | `docker compose config --quiet` | 通过 |
| 服务重建与启动 | `docker compose up --build -d` | 清理 8080、80 端口冲突后成功；构建阶段后端 174/174 通过，三个服务均 healthy |
| HTTP 健康检查 | `curl -fsS http://localhost:8080/api/open/health`、`curl -fsS http://localhost/health` | 两个端点均返回 `{"status":"UP"}` |
| 格式与工作区检查 | `git diff --check`、`git status --short` | 格式检查通过，无调试文件或无关变更 |

## 🔄 当前覆盖范围与结果

- 正常场景：权限树构建、角色新增编辑回显、页面+按钮合法保存和精确依赖联动均通过。
- 边界场景：null、空权限、页面单选、页面取消、目录取消和异常孤立按钮均已覆盖。
- 异常场景：按钮-only 新增和编辑请求均被后端拒绝，错误消息支持中英文。
- 权限与安全：前端按最小权限原则不因选择页面而授予全部按钮；后端阻止绕过前端建立无效授权。
- 兼容性：既有角色接口结构、菜单权限编码和空权限角色行为保持不变；历史按钮-only 角色在编辑回显时自动补齐页面。
- 回归场景：后端 174 个、前端 135 个、Python Worker 12 个测试全部通过。

## 🔄 当前重测触发条件

- 修改角色权限树构建、勾选联动或回显逻辑。
- 修改角色创建、编辑的菜单权限校验或角色—菜单关系。
- 修改菜单 CATALOG、MENU、BUTTON 层级定义或内置菜单初始化。
- 修改角色、菜单相关中英文错误消息或接口契约。
- 用户明确要求重新验证角色权限配置行为。

## ⚠️ 当前已知问题与限制

- 未执行浏览器自动化 E2E；界面行为由权限树单元测试、页面源码集成断言、生产构建和运行态健康检查覆盖。
- 历史自定义角色不会在启动时批量扩展页面权限；打开编辑弹窗会补齐层级，保存后持久化规范结果，避免部署时静默扩大现有角色访问范围。
- 自定义菜单若仍将按钮挂在目录而非页面下，该按钮会显示在树中但无法勾选，需要先在菜单管理中修正父级。
- Compose 启动按仓库规则停止了占用端口的 `domestic-trade-backend-1` 和 `domestic-trade-frontend-1`，未自动恢复。

## 📝 下次测试建议

1. 后续引入浏览器测试框架时，补充角色新增、编辑、勾选按钮、取消页面和双语切换的真实交互 E2E。
2. 若需要批量治理历史按钮-only 角色，先增加只读审计和管理员确认流程，再设计显式数据迁移。
3. 菜单管理后续可增加类型父子约束，从数据入口阻止 BUTTON 挂到非 MENU 节点。

## ↩️ 回滚方式

- 回滚提交 `8fbc1bf` 并重新执行 `docker compose up --build -d`，可恢复原扁平角色权限配置及原服务端行为。
- 内置菜单父级由初始化器按权限编码幂等维护，回滚并重启后会恢复旧父级；不涉及表结构、依赖或文件删除。

## 历史测试记录（管理员密码同步）

## 📋 Git 基准点

Commit: db0adccc1f5137d8b4c051db3845efebdfd3e2ef
- 提交说明: Add configurable admin password synchronization
- 测试日期: 2026-07-29
- 分支: master

## 🎯 当前变更范围

- 新增 `APP_SEED_ADMIN_PASSWORD_SYNC_ENABLED` 管理员种子密码同步开关。
- 开关未配置或设置为 `false` 时保留已有管理员密码，保持历史行为。
- 开关显式设置为 `true` 时，在启动阶段比较已有密码与种子密码，仅在不匹配时使用 BCrypt 更新。
- 首次创建管理员时不受同步开关影响，始终设置安全校验通过的种子密码。
- 补充 Compose、Spring 类型安全配置、环境变量示例和中英文使用说明。

## 📋 当前变更测试结果（2026-07-29）

**变更范围**：管理员种子密码同步开关、启动初始化逻辑、配置绑定、部署配置及使用文档。

**测试执行结果**：
- 完整回归测试：306 个，通过 306 个（100%）
- 后端完整测试：167 个，通过 167 个（100%）
- 前端现行测试：4 个，通过 4 个（100%）
- 前端历史完整回归：123 个，通过 123 个（100%）
- Python Worker 完整测试（Python 3.12）：12 个，通过 12 个（100%）
- 新增管理员初始化定向测试：7 个，通过 7 个（100%，已包含在后端完整测试中）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- DataInitializer：7/7，通过默认关闭、显式关闭、开启同步、已一致不重哈希和首次创建场景。
- Service 层：71/71，通过管理员初始化、认证、模型、API Key、任务追踪和系统配置回归。
- Controller 层：17/17，通过接口层回归。
- Security 层：27/27，通过密码认证、Token 拦截和 API Key 安全回归。
- 配置绑定：默认关闭和显式开启均通过；`docker compose config --quiet` 通过。
- Repository 层：本次无 Repository 接口变更；定向测试使用 Mock 隔离仓储，运行态启动使用现有 MySQL 验证。

**实施验证记录**：
- 宿主机未安装 Maven，直接执行定向测试命令返回 `mvn: command not found`；随后使用项目既有 Maven 3.9.9 / Java 17 容器完成全部后端测试。
- 首次 Compose 启动因主机 8080 端口被 `domestic-trade-backend-1` 占用而失败，按仓库规则停止该容器后重试。
- 第二次启动因主机 80 端口被 `domestic-trade-frontend-1` 占用而失败，停止该容器后再次重试成功。
- 最终 `docker compose up --build -d` 成功，Backend、Frontend、Python Worker 均为 healthy。
- 后端和前端 HTTP 健康检查均返回 `UP`；当前本地环境未显式开启同步开关，已有管理员密码未被启动过程覆盖。

## ✅ 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入/操作 | 预期结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 未配置时默认关闭 | 配置绑定单元测试；使用默认 `PlatformProperties` | 不设置同步属性 | 开关值为 `false` | 边界、兼容性、安全 |
| 显式关闭时保留密码 | DataInitializer 单元测试；已有管理员 | 设置开关为 `false` 后执行初始化 | 原密码哈希不变，不调用密码匹配和编码 | 正常、兼容性、回归 |
| 显式开启时同步不同密码 | DataInitializer 单元测试；已有管理员密码不匹配 | 设置开关为 `true` 后执行初始化 | 使用 BCrypt 编码种子密码并保存新哈希 | 正常、安全 |
| 密码已一致时保持幂等 | DataInitializer 单元测试；已有密码匹配 | 开启同步后重复初始化 | 保留原哈希，不重复编码 | 边界、回归 |
| 首次创建不受开关影响 | 参数化单元测试；管理员不存在 | 分别使用 `false`、`true` 创建管理员 | 两种状态均保存种子密码哈希 | 正常、边界 |
| 显式配置可开启 | Spring Binder 单元测试 | 绑定 `app.seed.admin-password-sync-enabled=true` | 类型安全配置值为 `true` | 配置、兼容性 |
| Compose 正确传递默认值 | Compose 配置验证和运行态重建 | 未设置环境变量并启动服务 | 后端收到默认 `false`，三个服务健康 | 集成、兼容性 |
| 历史功能保持稳定 | 三端完整回归 | 执行全部测试 | 306 个测试全部通过 | 回归 |

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 宿主机定向测试尝试 | `cd backend && mvn -B -Dtest=DataInitializerTest test` | 环境无 Maven，命令未执行测试；改用容器命令验证 |
| 后端定向测试 | `docker run --rm -v "$PWD/backend:/workspace" -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=DataInitializerTest test` | 7 通过，0 失败，0 错误，0 跳过 |
| 后端完整回归 | `docker run --rm -v "$PWD/backend:/workspace" -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 167 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `cd frontend && npm test && node --test test/*.test.mjs` | 127 通过，0 失败 |
| Python Worker 完整回归 | `docker run --rm -e PYTHONPATH=/workspace -v "$PWD/python-worker:/workspace" -w /workspace python:3.12-slim sh -lc 'pip install -q -r requirements.txt && pytest -q'` | 12 通过，0 失败 |
| Compose 配置验证 | `docker compose config --quiet` | 通过 |
| 服务重建与启动 | `docker compose up --build -d` | 清理 8080、80 端口占用后最终成功，三个服务均 healthy；构建阶段后端 167/167 通过 |
| HTTP 健康检查 | `curl -fsS http://localhost:8080/api/open/health`、`curl -fsS http://localhost/health` | 两个端点均返回 `{"status":"UP"}` |
| 格式与工作区检查 | `git diff --check`、`git status --short` | 格式检查通过；业务变更已提交，无调试文件 |

## 🔄 当前覆盖范围与结果

- 正常场景：开启同步后，已有管理员密码与种子密码不一致时正确更新。
- 边界场景：开关缺省、显式关闭、密码已一致和管理员不存在均已覆盖。
- 异常场景：宿主机缺少 Maven 时使用固定版本容器完成测试；端口冲突按仓库规则清理后重试成功。
- 权限与安全：默认不覆盖生产管理员密码；开启后仅保存 BCrypt 哈希，明文密码不进入数据库和测试输出。
- 兼容性：默认行为与变更前一致；既有认证、API Key、Controller、前端和 Worker 回归全部通过。
- 回归场景：后端 167 个、前端 127 个、Python Worker 12 个测试全部通过。

## 🔄 当前重测触发条件

- 修改 `DataInitializer` 管理员初始化或密码同步逻辑。
- 修改 `PlatformProperties.Seed`、`app.seed` 配置绑定或对应环境变量名称与默认值。
- 修改用户密码哈希格式、`BCryptPasswordEncoder` 配置、认证逻辑或用户仓储行为。
- 用户明确要求重新验证管理员密码同步行为。

## ⚠️ 当前已知问题与限制

- 为避免改变现有管理员凭据，运行态 Compose 验证使用默认关闭状态；显式开启后的密码更新由 7 个 DataInitializer 单元测试验证，未在当前数据库执行真实密码重置。
- 定向测试通过 Mock 隔离仓储；当前数据库上的默认关闭路径由真实 Compose 启动和健康检查覆盖，但未执行浏览器端登录 E2E。
- 开启同步后，管理页面手动修改的管理员密码会在下一次应用启动时被环境变量值覆盖，这是该开关的预期行为。
- 本次按规则停止了占用 8080 和 80 端口的 `domestic-trade-backend-1`、`domestic-trade-frontend-1` 容器，未自动恢复它们。

## 📝 下次测试建议

1. 在隔离数据库中补充真实 Repository 集成测试，验证开启同步后数据库哈希更新且可通过认证服务登录。
2. 若生产环境计划开启该开关，先在预发布环境轮换为新的随机强密码，并验证启动后旧密码失效、新密码生效。
3. 后续如加入会话强制失效能力，应补充密码同步后现有 Token 是否撤销的安全验收。

## ↩️ 回滚方式

- 将 `APP_SEED_ADMIN_PASSWORD_SYNC_ENABLED` 设置为 `false` 或移除即可停止后续同步，无需回滚代码。
- 回滚提交 `db0adcc` 可移除本功能；已被同步过的密码不会自动恢复，需通过用户管理或受控数据库操作重新设置。

## 历史测试记录（AI 对话 Trace ID 回显）

## 📋 Git 基准点

Commit: d96017ea0eadd8ebe02224a8d65cfcedc3d2baa2
- 提交说明: Preserve chat trace ID after response unwrap
- 测试日期: 2026-07-28
- 分支: master

## 🎯 当前变更范围

- Axios 统一响应解包前读取根级 `traceId`，并保存为标准化的 `response.traceId`。
- AI 对话页面使用 `response.traceId` 创建助手回显消息，避免解包 `data` 后丢失当前请求追踪标识。
- 当统一响应体缺少 traceId 时，前端使用 `X-Trace-Id` 响应头兜底；业务 `data.traceId` 不参与当前请求 traceId 判定。
- 非统一响应保持原业务数据结构，避免影响健康检查等既有调用。
- 本次仅修改前端响应解包、AI 对话回显及对应测试；后端统一响应协议保持不变。

## 📋 当前变更测试结果（2026-07-28）

**变更范围**：前端统一响应解包、AI 对话助手消息 Trace ID 回显和兼容性测试。

**测试执行结果**：
- 完整回归测试：299 个，通过 299 个（100%）
- 后端完整测试：160 个，通过 160 个（100%）
- 前端现行测试：4 个，通过 4 个（100%）
- 前端历史完整回归：123 个，通过 123 个（100%）
- Python Worker 完整测试（Python 3.12）：12 个，通过 12 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**实施验证记录**：
- 缺陷复现测试初次因 `apiResponse.js` 尚不存在而失败，确认 AI 对话页面缺少稳定保留根级 traceId 的解包能力。
- 新增统一响应解包测试，覆盖根级 traceId、响应头兜底、嵌套伪造 traceId 忽略和非统一响应兼容性。
- AI 对话定向测试 9/9 通过；前端完整回归合计 127/127 通过。
- 后端完整回归 160/160、Python 3.12 回归 12/12 均通过。
- `docker compose up --build -d` 最终成功返回，Backend、Frontend、Python Worker 复核均为 healthy。
- 未调用会产生费用的真实模型成功路径；通过 Axios 解包到助手消息创建的单元链路验证回显 traceId。

## ✅ 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入/操作 | 预期结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| traceId 与 code 平级 | 后端响应契约及运行态请求 | 序列化统一成功或失败响应 | 根对象包含 traceId，data 中不包含 traceId | 正常、兼容性 |
| traceId 位于 data 前 | 后端 ObjectMapper 测试及运行态原始 JSON | 检查字段索引顺序 | traceId 在 data 之前序列化 | 正常、契约 |
| AI 对话 data 不包含 traceId | 后端 DTO 反射测试 | 检查 `ChatResponse` 记录组件 | 仅包含模型业务字段 | 正常、回归 |
| 开放文档层级正确 | 后端目录测试及运行态公开目录 | 检查字段和解析响应示例 | 仅声明根级 traceId，不存在 data.traceId | 正常、文档 |
| 前端从统一响应读取 traceId | 前端工具及源码契约测试 | 提供业务 data 和独立信封 traceId | 解包后 `response.traceId` 保留根级值，助手消息正确展示 | 正常、兼容性 |
| 嵌套伪造 traceId 不生效 | 前端单元测试 | data 和信封提供不同 traceId | 页面忽略 data 值，仅采用信封值 | 安全、边界 |
| 响应头可作为 traceId 兜底 | 前端单元测试 | 响应体无 traceId、响应头包含 `X-Trace-Id` | `response.traceId` 使用响应头值 | 边界、兼容性 |
| 非统一响应不被误解包 | 前端单元测试 | 返回普通健康状态对象 | 业务数据结构保持不变 | 兼容性、回归 |
| 缺失 traceId 不影响模型内容 | 前端单元测试 | 仅提供模型结果或 Token | 内容正常展示，traceId 为 null | 边界、异常 |
| 既有功能保持稳定 | 三端完整回归 | 执行全部测试 | 299 个测试全部通过 | 兼容性、回归 |

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 前端缺陷复现 | `node --test frontend/test/api-response.test.mjs` | 实现前因响应解包工具缺失而失败，稳定复现问题 |
| 前端定向测试 | `node --test frontend/test/api-response.test.mjs frontend/test/chat-response.test.mjs` | 9 通过，0 失败 |
| 后端完整回归 | `docker run --rm -v "$PWD/backend:/workspace" -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 最终 160 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `cd frontend && npm test && node --test test/*.test.mjs` | 127 通过，0 失败 |
| Python Worker 完整回归 | `docker run --rm -e PYTHONPATH=/workspace -v "$PWD/python-worker:/workspace" -w /workspace python:3.12-slim sh -lc 'pip install -q -r requirements.txt && pytest -q'` | 12 通过，0 失败 |
| 服务重建与启动 | `docker compose up --build -d` | 最终命令成功返回；三个服务复核均为 healthy |
| 格式与工作区检查 | `git diff --check`、`git status --short` | 格式检查通过，无临时调试文件 |

## 🔄 当前覆盖范围与结果

- 正常场景：统一响应根级 traceId 在解包后保留，并传入 AI 助手消息展示。
- 边界场景：响应体缺失 traceId 时使用响应头兜底；空值和超长值被规范化过滤。
- 异常场景：缺失 traceId 不影响 AI 内容、模型和 Token 元数据回显。
- 权限与安全：前端不信任业务 data 中的 traceId，仅采用统一响应根级字段或后端响应头。
- 兼容性：非统一响应不解包；`response.api` 和 `response.data` 的既有调用方式继续保留。
- 回归场景：后端 160 个、前端 127 个、Python Worker 12 个测试全部通过。

## ⚠️ 当前已知问题与限制

- 本次未执行会产生真实模型费用的 AI 对话成功调用；已通过前端解包到消息创建的单元链路及完整回归验证。
- Axios 拦截器将当前请求 traceId 保存在 `response.traceId`，同时继续用 `response.api` 保留完整信封、用 `response.data` 提供业务数据。
- 任务查询结果中的 traceId 是任务资源属性，不代表查询接口自身的当前请求 traceId，因此仍可存在于业务 data 中。

## 📝 下次测试建议

1. 在具备无费用测试模型时，补充 AI 对话成功响应端到端断言，验证页面展示的 traceId 与响应根级字段一致。
2. 新增统一响应调用时统一使用拦截器提供的 `response.traceId`，不得从业务 `data` 中读取当前请求 traceId。
3. 可补充 Axios 实例级集成测试，覆盖真实拦截器注册和响应头大小写归一化行为。

## ↩️ 回滚方式

- 回滚提交 `1d145ab` 可恢复原统一响应字段声明顺序；回滚提交 `b471324` 可恢复 AI 对话 `data.traceId` 和旧前端读取方式。
- 本次不涉及数据库迁移、依赖或配置变更，回滚仅影响响应 JSON 层级、顺序和接口文档。

## 历史测试记录（后端生成 traceId）

## 📋 Git 基准点

Commit: 4a646531b30676431d01211f6ed31335f1d003e6
- 提交说明: Add backend-generated trace IDs to API responses
- 测试日期: 2026-07-28
- 分支: master

## 🎯 当前变更范围

- 非忽略接口在进入 Spring MVC 控制器链路时由后端生成 32 位 traceId，不采用调用方提交的 `X-Trace-Id`。
- traceId 统一写入请求属性、MDC 和 `X-Trace-Id` 响应头，并由任务追踪直接复用，避免同一请求重复生成。
- 统一成功和失败响应增加 `traceId` 字段，业务异常、认证异常、参数异常和未知异常均可返回当前请求 traceId。
- `@TraceIgnored`、`excluded-methods` 和 `excluded-paths` 共用同一忽略策略；被忽略接口不生成、不返回 traceId，既有原始响应协议保持不变。
- 保留 `X-Request-Id`、任务预留接口和内部任务追踪能力，不涉及数据库迁移、外部依赖或配置格式变更。

## 📋 当前变更测试结果（2026-07-28）

**变更范围**：后端生成 traceId、请求上下文传播、任务记录复用、统一成功及异常响应返回、忽略接口兼容。

**测试执行结果**：
- 完整回归测试：294 个，通过 294 个（100%）
- 后端完整测试：158 个，通过 158 个（100%）
- 前端现行测试：4 个，通过 4 个（100%）
- 前端历史完整回归：120 个，通过 120 个（100%）
- Python Worker 完整测试（Python 3.12）：12 个，通过 12 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**实施验证记录**：
- 后端 traceId 定向测试执行 18 个，覆盖响应契约、忽略策略、调用方伪造头忽略和任务记录复用，18/18 通过。
- 后端完整测试在最终代码上执行两次，均为 158/158 通过；最终 `docker compose up --build -d` 构建内再次执行 158 个测试并通过。
- Python Worker 首次独立测试因容器未设置 `PYTHONPATH` 导致 2 个测试模块收集失败；补充 `PYTHONPATH=/workspace` 后使用 Python 3.12 重跑，12/12 通过，代码未因此修改。
- 首次 Compose 重建完成镜像后 Backend 健康等待阶段短暂以 143 退出，容器自动恢复后重试成功；最终代码完成后再次重建，Backend、Frontend、Python Worker 均为 healthy。
- 运行态使用未认证 `POST /api/system/users` 验证 HTTP 401 响应头和响应体 traceId 完全一致、长度为 32，且调用方提交的 `X-Trace-Id` 未被采用。
- 运行态使用 `GET /api/open/platform` 验证忽略接口 HTTP 200，响应头和响应体均不存在 traceId。

**关键模块测试**：
- Web traceId 生成与忽略策略：3/3，通过
- 统一成功和异常响应契约：14/14，通过
- Service 任务记录复用后端 traceId：1/1，通过
- 后端完整回归：158/158，通过
- 前端完整回归：124/124，通过
- Python Worker 完整回归：12/12，通过

## ✅ 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入/操作 | 预期结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 非忽略接口由后端生成 traceId | Web 单元测试；普通 POST 接口 | 请求携带伪造 `X-Trace-Id` | 生成新的 32 位 traceId，写入请求、MDC 和响应头，不采用调用方值 | 正常、安全 |
| 任务记录复用请求 traceId | Service 单元测试；已生成请求 traceId | 创建运行任务 | 数据库写入值与请求 traceId 一致，不再次生成 | 正常、兼容性 |
| 成功响应返回 traceId | 响应契约测试；MDC 已绑定 traceId | 包装普通业务结果 | 统一响应 `traceId` 与当前请求一致 | 正常 |
| 异常响应返回 traceId | 参数化响应契约测试；业务状态 400/401/403/404/429/502/503 | 调用全局异常处理器 | HTTP 状态、业务 code、消息及 traceId 均正确 | 异常、权限、回归 |
| 调用方不能控制 traceId | Web 单元及运行态验证 | 提交 `X-Trace-Id: caller-supplied` | 后端返回不同的新 traceId | 安全、边界 |
| 配置忽略方法不返回 traceId | Web 单元测试；默认排除 GET | 请求普通 GET 接口 | 不创建请求属性、MDC 或响应头 | 兼容性、边界 |
| 注解忽略接口不返回 traceId | Web 单元测试；控制器声明 `@TraceIgnored` | 请求被忽略控制器 | 不生成或返回 traceId | 兼容性、回归 |
| 公开忽略接口保持原协议 | 运行态验证；服务 healthy | 请求 `GET /api/open/platform` | HTTP 200，响应头和原始 JSON 均无 traceId | 正常、兼容性 |
| 认证失败仍返回同一 traceId | 运行态验证；未提交认证凭证 | POST 非忽略管理接口 | HTTP 401，响应头与统一错误体 traceId 一致 | 异常、权限、安全 |

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 后端定向测试 | `docker run --rm -v "$PWD/backend:/workspace" -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=ApiResponseContractTest,TraceIdInterceptorTest,TaskTraceServiceTraceIdTest test` | 18 通过，0 失败，0 错误，0 跳过 |
| 后端完整回归 | `docker run --rm -v "$PWD/backend:/workspace" -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 最终代码 158 通过，0 失败，0 错误，0 跳过 |
| 前端现行测试 | `cd frontend && npm test` | 4 通过，0 失败 |
| 前端历史完整回归 | `cd frontend && node --test test/*.test.mjs` | 120 通过，0 失败 |
| Python Worker 首次执行 | `docker run --rm -v "$PWD/python-worker:/workspace" -w /workspace python:3.12-slim sh -lc 'pip install -q -r requirements.txt && pytest -q'` | 环境缺少 `PYTHONPATH`，2 个模块收集失败；未进入测试执行 |
| Python Worker 完整回归 | `docker run --rm -e PYTHONPATH=/workspace -v "$PWD/python-worker:/workspace" -w /workspace python:3.12-slim sh -lc 'pip install -q -r requirements.txt && pytest -q'` | 12 通过，0 失败 |
| 服务重建与启动 | `docker compose up --build -d` | 最终构建内后端 158 个测试通过；三个服务均 healthy |
| 运行态契约 | Node Fetch 请求非忽略认证失败接口和公开忽略接口 | 非忽略接口头体 traceId 一致且拒绝调用方值；忽略接口无 traceId |
| 格式与工作区检查 | `git diff --check`、`git status --short`、`git diff --stat` | 格式检查通过；代码和测试已独立提交，无临时调试文件 |

## 🔄 当前覆盖范围与结果

- 正常场景：后端生成、请求上下文传播、统一成功响应和任务记录复用均有测试。
- 边界场景：调用方提交 traceId、默认 GET 排除、注解排除和空 trace 上下文均保持明确行为。
- 异常场景：业务异常、认证失败、参数解析失败、任务取消和未知异常均通过统一响应回归覆盖。
- 权限与安全：调用方不能指定或复用后端 traceId；未认证请求仍获得用于排障的后端 traceId，不泄露内部堆栈。
- 兼容性：公开、内部、认证、任务查询和默认 GET 等忽略接口保持原协议；`X-Request-Id` 与任务预留能力保留。
- 回归场景：后端 158 个、前端 124 个、Python Worker 12 个测试全部通过。
- 运行环境：Backend、Frontend、Python Worker 已由提交 `4a64653` 的源码重新构建并全部 healthy。

## 🔄 当前重测触发条件

- 修改 `ApiResponse`、`ApiResponseAdvice`、`GlobalExceptionHandler` 或统一响应序列化配置。
- 修改 `TraceIdInterceptor`、`TraceTrackingPolicy`、`TraceTrackingAspect` 或任务追踪创建逻辑。
- 修改 `trace-tracking-exclusions.yml`、默认排除方法、排除路径或 `@TraceIgnored` 使用范围。
- 修改认证拦截器顺序、MVC 拦截器注册或请求 MDC 清理逻辑。
- 用户明确要求重新执行完整覆盖测试。

## ⚠️ 当前已知问题与限制

- 当前默认配置排除所有 `GET` 和 `OPTIONS` 请求，因此这些接口按需求不会生成或返回 traceId；若需覆盖查询接口，应先调整并确认忽略配置范围。
- 被忽略接口发生异常时统一响应不会包含 traceId，这是“除非被忽略接口”的预期行为。
- traceId 当前用于应用任务和日志追踪，不替代 `X-Request-Id`；两类标识继续承担不同职责。
- 前端仓库同时存在 `tests/*.test.js` 和 `test/*.test.mjs` 两套测试入口，`npm test` 只覆盖前者，本次已额外执行后者完整回归。
- Compose 首次重建曾出现既有的 Backend 健康等待 143 短暂退出，重试及最终重建均成功，三个服务当前 healthy。

## 📝 下次测试建议

1. 若未来取消 GET 排除，补充查询接口成功、404、无权限和大分页响应的 traceId 集成测试。
2. 在具备专用测试账号时，补充一个无副作用的认证成功 POST 接口，验证响应头、响应体和数据库任务记录三者端到端一致。
3. 可在后续性能测试中对比启用前后的 P50/P95 延迟，重点观察任务追踪数据库写入，而非 traceId 字符串生成。
4. 建议后续统一前端两套测试目录和 `npm test` 入口，避免完整回归依赖额外命令。

## ↩️ 回滚方式

- 回滚功能提交 `4a64653` 可移除后端生成 traceId、统一响应字段和共享忽略策略。
- 本次无数据库迁移、配置格式变化或新增依赖，回滚后恢复原有仅任务接口返回 `X-Trace-Id` 的行为。

## 历史测试记录（API Key 开放平台）

## 📋 Git 基准点

Commit: 29662482c198e28688c7220188ce6531affabae1
- 提交说明: Add API key open platform documentation
- 测试日期: 2026-07-28
- 分支: master

## 🎯 当前变更范围

- 新增公开访问的 `/open-platform` 页面，展示 API Key 可配置开放接口的认证、入参、出参、示例和风险等级。
- 扩展 `@ApiKeyEndpoint` 后端元数据，由同一目录同时驱动 API Key 管理和开放平台文档；缺少响应文档或路径参数不一致时启动校验失败。
- 新增公开目录接口 `GET /api/open/platform/endpoints`，返回接口文档但不暴露内部 RBAC 权限编码。
- 新增 API Key 在线调试，支持路径变量编码、JSON 请求体校验、响应状态和耗时展示；高风险接口调用前强制确认。
- API Key 仅保存在页面组件内存，不写入 URL、日志、`localStorage` 或 `sessionStorage`，请求不携带 Bearer Token。
- 登录页增加开放平台入口，并调整公开路由守卫，使登录前后均可访问开放平台。

## 📋 当前变更测试结果（2026-07-28）

**变更范围**：API Key 开放接口文档元数据、公开目录、双语开放平台页面和安全在线调试。

**测试执行结果**：
- 完整回归测试：285 个，通过 285 个（100%）
- 后端完整测试：154 个，通过 154 个（100%）
- 前端完整测试：119 个，通过 119 个（100%）
- Python Worker 完整测试（Python 3.12.13）：12 个，通过 12 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**实施验证记录**：
- 前端定向测试首次执行即 6/6 通过，生产构建成功。
- 后端定向测试首次在测试编译阶段发现 Java 17 不支持 `List.getFirst()`，且既有测试样例注解缺少新增必填元素；改为 `get(0)`，并通过默认空元数据保持源码兼容、由启动期完整性校验继续强制生产文档后，22/22 通过。
- 首次 `docker compose up --build -d` 完成镜像构建及 154 项后端测试后，Backend 健康等待阶段短暂以 143 退出；容器自动恢复健康后重新执行同一命令，三个服务均成功重建并 healthy。
- 公开目录真实请求返回 2 个接口且不包含权限编码；使用无效 API Key 调用 AI 对话接口返回 HTTP 401 和统一错误体，未触发模型调用。
- 前端应用预览成功打开，控制台无错误；浏览器自动化连接因运行会话缺少元数据未能执行点击式在线调用，未将此项误报为已验证。

**关键模块测试**：
- API Key 接口目录、文档完整性和路径参数一致性：3/3，通过
- 公开目录权限字段脱敏：1/1，通过
- API Key 认证、目录管理和服务兼容定向回归：18/18，通过
- 前端路径编码、请求构造、非法输入、curl 示例、公开路由及凭证内存安全：6/6，通过
- 后端完整回归：154/154，通过
- 前端完整回归：119/119，通过
- Python Worker 完整回归：12/12，通过

## ✅ 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入/操作 | 预期结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 未登录和已登录用户均可访问开放平台 | 前端路由契约；任意登录状态 | 访问 `/open-platform` | 不跳转登录页或工作台；登录页仍只允许游客 | 正常、权限、兼容性 |
| 页面接口集合与 API Key 可配置目录一致 | 后端目录测试；扫描实际 Controller | 生成开放接口目录 | 仅包含 `ai.chat.invoke` 和 `automation.api-trigger.execute`，顺序稳定 | 正常、回归 |
| 新增开放接口必须提供完整文档 | 后端目录测试；构造缺失响应文档的接口 | 执行启动目录校验 | 抛出文档不完整错误，禁止带缺失文档的接口启动 | 异常、兼容性 |
| 路径变量必须与文档声明一致 | 后端目录测试；路径含 `{id}` 但未声明字段 | 生成接口目录 | 抛出路径参数文档不匹配错误 | 边界、异常 |
| 公开目录不泄露内部权限编码 | Controller 单元测试 | 请求公开接口模型 | 保留参数和示例，不包含 `permission` 字段 | 权限、安全 |
| AI 对话调试正确发送 JSON | 前端工具测试；有效 Key 和 JSON | 构造调试请求 | 发送 `X-API-Key`、语言和 JSON，不发送 Authorization | 正常、安全 |
| 触发器调试正确处理路径且无空请求体 | 前端参数化测试；ID 含特殊字符或正常数字 | 构造请求 | 路径值 URL 编码；无请求体和 Content-Type | 正常、边界 |
| 无效输入不得发送请求 | 前端工具测试；空 Key、空请求体、非法 JSON、空路径参数 | 构造请求 | 在发起 Axios 请求前返回明确校验错误 | 边界、异常、安全 |
| 高风险接口调用前必须确认且凭证不持久化 | 前端源码契约测试 | 检查页面生命周期和高风险分支 | 存在确认框；卸载时清空 Key；不写入持久化存储 | 权限、安全、副作用 |
| 实际业务接口继续执行 API Key 认证 | 真实 HTTP 请求；使用无效 Key | 调用 `POST /api/ai/chat` | 返回 HTTP 401 和统一错误体，不调用模型 | 权限、安全、回归 |

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 前端开放平台定向测试 | `node --test test/open-platform.test.mjs`（在 `frontend/` 执行） | 6 通过，0 失败，0 错误，0 跳过 |
| 前端生产构建 | `npm run build`（在 `frontend/` 执行） | 构建成功；仅有既有 runtime-config、第三方 PURE 注释和大 Chunk 警告 |
| 后端开放平台与 API Key 定向测试 | `docker run --rm -v "$PWD:/workspace" -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=ApiKeyEndpointCatalogServiceTest,OpenPlatformControllerTest,ApiKeyManagementControllerTest,ApiKeyManagementServiceTest,ApiKeyAuthenticationServiceTest test`（在 `backend/` 执行） | 首次测试编译发现 Java 17 和注解兼容问题；修正后 22 通过，0 失败，0 错误，0 跳过 |
| 后端完整回归 | `docker run --rm -v "$PWD:/workspace" -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B`（在 `backend/` 执行） | 154 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `node --test test/*.test.mjs tests/*.test.js`（在 `frontend/` 执行） | 119 通过，0 失败，0 错误，0 跳过 |
| Python Worker 完整回归 | `docker run --rm -v "$PWD/python-worker:/workspace" -w /workspace base-ai-python-worker:latest python -m pytest -q` | Python 3.12.13；12 通过，0 失败 |
| 服务重建与启动 | `docker compose up --build -d` | 首次健康等待短暂失败后自动恢复；重新执行同一命令成功，Backend 镜像构建内 154 项测试通过，三个服务重建完成 |
| 服务健康与公开目录 | `docker compose ps`、`curl -fsS http://localhost:8080/api/open/health`、`curl -fsS http://localhost:8080/api/open/platform/endpoints`、`curl -fsSI http://localhost/open-platform` | 三个服务 healthy；健康接口 UP；公开目录返回 2 项；页面返回 HTTP 200 |
| API Key 拒绝验证 | `curl -X POST http://localhost:8080/api/ai/chat -H 'X-API-Key: sk-invalid' ...` | HTTP 401，响应 `code=401`、消息“API Key 无效”，未进入模型调用 |
| 页面预览检查 | IDE 应用预览打开 `http://localhost/open-platform` 并读取 ConsoleOutput | 页面成功打开，控制台无错误；浏览器点击自动化因会话元数据缺失未执行 |
| 格式与提交范围检查 | `git diff --check`、`git diff --cached --check`、`git status --short` | 格式检查通过；功能变更已独立提交，未产生临时调试文件 |

## 🔄 当前覆盖范围与结果

- 正常场景：公开目录、两类接口文档、JSON 请求构造、路径替换、curl 示例和统一响应展示均有测试。
- 边界场景：空 API Key、空请求体、非法 JSON、空路径参数、路径特殊字符和无请求体接口均已覆盖。
- 异常场景：文档缺失、路径文档不匹配、无效 API Key、Compose 首次健康等待异常均已记录并验证最终结果。
- 权限与安全：公开目录不暴露权限编码；调试只发送 `X-API-Key`，不发送 Bearer Token，不持久化 Key；高风险操作要求二次确认。
- 兼容性：API Key 管理继续使用同一目录服务，认证、限流、RBAC、IP 白名单和既有接口响应结构未改变。
- 回归场景：后端 154 个、前端 119 个、Python Worker 12 个测试全部通过。
- 运行环境：Backend、Frontend、Python Worker 已由提交 `2966248` 的源码重新构建并全部 healthy。

## 🔄 当前重测触发条件

- 新增、删除或修改任何 `@ApiKeyEndpoint` 开放接口。
- 修改开放接口路径、HTTP 方法、请求或响应结构。
- 修改 `ApiKeyEndpointCatalogService`、公开目录 Controller 或 API Key 认证拦截逻辑。
- 修改开放平台路由、在线调试请求构造、风险确认或凭证生命周期。
- 修改开放平台及 API Key 接口名称、分组和字段双语消息。
- 用户明确要求重新执行完整覆盖测试。

## ⚠️ 当前已知问题与限制

- 在线调试会调用真实业务接口，AI 对话可能产生模型费用，接口触发器可能产生外部副作用；页面通过风险提示和高风险二次确认降低误操作风险，但不能替代调用方权限治理。
- 本次未使用有效 API Key 执行真实模型或真实接口触发器，避免产生费用和外部副作用；有效凭证端到端成功路径尚未验证。
- 浏览器自动化连接因当前运行会话缺少必需元数据而不可用；已完成应用预览、控制台检查、公开页面 HTTP 200、源码契约测试和生产构建，但未完成点击切换及移动视口自动化截图验证。
- Frontend 生产构建仍有既有 runtime-config、第三方 PURE 注释和大 Chunk 警告，不影响构建成功。
- 后端完整测试中的预期异常日志和路由同步失败日志属于既有异常分支验证，测试结果仍为 154/154 通过。

## 📝 下次测试建议

1. 在可提供专用测试 API Key 和无副作用触发器时，补充 AI 对话成功响应和触发器成功响应的浏览器端到端测试。
2. 浏览器自动化环境恢复后，补充中文/英文切换、接口切换、高风险确认取消及桌面/移动视口截图回归。
3. 新增开放接口时沿用 `@ApiKeyEndpoint` 元数据并补充目录完整性测试，不得在前端单独维护接口清单。
4. 若开放接口数量增长明显，可评估分页、搜索和 JSON Schema 展示，但需保持当前 API Key 目录为唯一接口来源。

## ↩️ 回滚方式

- 回滚功能提交 `2966248` 可移除开放平台页面、公开目录及文档元数据，不涉及数据库迁移或配置回滚。
- 回滚后 API Key 现有生成、认证、接口授权、IP 白名单和频次限制行为保持不变，仅不再提供本次新增的公开文档与在线调试入口。

## 历史测试记录（内置数据本地化）

## 📋 Git 基准点

- Commit: b3e5ca6
- 提交说明: Fix built-in data localization switching
- 测试日期: 2026-07-27
- 分支: master

## 🎯 当前变更范围

- 原始 8 类内置数据统一按稳定业务标识在前端即时翻译，切换语言不依赖重新请求。
- 登录日志兼容新消息键和历史中文值；非预期登录异常只记录安全的通用失败键。
- 任务类型改为持久化语言无关代码，并兼容旧翻译键、历史中文值和未知类型回退。
- 模型类型字典保留可读 label；内置 value 翻译，自定义类型回退管理员维护的 label。
- 补齐用户角色选择器、任务筛选器、模型表单、默认路由同步选择器和确认文案等遗漏位置。
- 使用当前源码重新构建 Backend、Frontend、Python Worker，并验证运行产物包含新任务代码。

## 📋 当前变更测试结果（2026-07-27）

**变更范围**：用户、角色、菜单、部门、登录日志、任务类型、模型类型和默认能力路由的中英文即时切换与历史数据兼容。

**测试执行结果**：
- 完整回归测试：231 个，通过 231 个（100%）
- 后端完整测试：144 个，通过 144 个（100%）
- 前端完整测试：87 个，通过 87 个（100%）
- 后端本地化定向测试：37 个，通过 37 个（100%）
- 前端本地化、导航、布局和路由定向测试：35 个，通过 35 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**缺陷复现记录**：
- 修复前前端定向测试：9 个中 7 个通过、2 个失败；稳定复现缺失 `auth.*` 真实词条和任务页面未接入兼容解析器。
- 修复前后端定向测试：37 个中 34 个通过、3 个失败；稳定复现任务持久化 UI key、模型类型回退暴露 key、登录内部异常正文写入审计日志。
- 修复后上述失败测试与完整测试全部通过，未删除、跳过或弱化测试。

**关键模块测试**：
- AuthService 登录审计消息键与敏感异常保护：3/3，通过
- LlmManagementService 模型类型目录、回退及模型路由：33/33，通过
- TraceType 稳定任务代码：1/1，通过
- 八类共享本地化解析器与页面接入：9/9，通过
- 全部内置 BUTTON 权限映射与导航回归：8/8，通过
- 布局与能力路由交互回归：18/18，通过
- 其余后端、前端完整回归：全部通过

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 前端缺陷复现 | `node --test test/localization.test.mjs` | 修复前 7 通过、2 失败，稳定复现；修复后通过 |
| 后端缺陷复现 | `docker run --rm -v "$PWD:/workspace" -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=AuthServiceTest,LlmManagementServiceTest,TraceTypeCodeTest test` | 修复前 34 通过、3 失败；修复后 37/37 通过 |
| 前端定向回归 | `node --test test/localization.test.mjs test/model-route-health.test.mjs test/navigation.test.mjs test/layout-alignment.test.mjs` | 35 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `node --test test/*.test.mjs` | 87 通过，0 失败，0 错误，0 跳过 |
| 后端完整回归 | `docker run --rm -v "$PWD:/workspace" -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 144 通过，0 失败，0 错误，0 跳过 |
| 服务重建与启动 | `docker compose up --build -d` | Backend 构建内 144 个测试通过；Frontend 生产构建通过；三个服务重新创建并 healthy |
| 运行产物检查 | `docker compose ps`、容器创建时间检查、生产 JS 搜索稳定任务代码 | 三个容器均使用本次新构建产物，Frontend bundle 包含 `API_TRIGGER_SECURITY_UPDATE` |
| 登录态联合验收 | 使用真实管理员登录 API 获取 8 类运行数据，并分别通过 zh-CN/en-US 真实语言资源和生产解析器断言 | 8/8 通过；验证后调用 logout 清理测试会话 |
| 格式与工作区检查 | `git diff --check`、`git status --short`、`git diff --stat` | 格式检查通过，仅包含本任务相关文件 |

## 🔄 当前覆盖范围与结果

- 正常场景：内置管理员、ADMIN 角色、ROOT 部门、系统菜单、登录消息、任务类型、内置模型类型和 DEFAULT 路由均完成中英文双向验证。
- 边界场景：空值、未知角色/部门/路由/任务类型、未知模型类型和空模型字典均有回退测试。
- 异常场景：历史中文登录消息、旧任务翻译键、历史中文任务值和非预期认证异常均有测试。
- 权限与安全：用户名称需同时满足 ADMIN 角色关联和默认名称；未知登录内部异常不再写入敏感正文；原有 RBAC 测试全部通过。
- 兼容性：自定义名称和自定义模型类型保持原值/字典 label；任务筛选 label 翻译但查询 value 保持数据库原值。
- 回归场景：导航裁剪、全部内置 BUTTON 权限、菜单 Actions 布局、能力路由同步、API Key、接口触发和模型路由测试全部通过。
- 运行环境：Backend、Frontend、Python Worker 已由本次源码重新构建，均处于 healthy 状态。

## 🔄 当前重测触发条件

- 修改内置角色、部门、菜单权限、任务代码、模型类型 value 或能力路由 featureCode。
- 修改登录审计消息、任务持久化值、模型类型字典契约或历史兼容映射。
- 修改 8 个相关页面、语言资源、语言切换行为或菜单 Actions 布局。
- 用户明确要求重新执行完整覆盖测试。

## ⚠️ 当前已知问题与限制

- 自定义业务名称不做自动翻译；需要双语自定义名称时必须另行设计字段和数据库迁移。
- 历史任务和登录日志不做数据迁移，由前端兼容解析器长期读取；未知历史值保持原文。
- 登录态联合验收覆盖真实 API、真实数据库数据、真实语言资源和生产解析器；当前环境未提供可编程浏览器点击工具，因此未自动执行逐页 DOM 点击录像。
- Frontend 生产构建仍有既有 runtime-config、第三方 PURE 注释和大 Chunk 警告，不影响构建成功。
- 后端测试中的预期异常日志和路由同步失败日志属于既有异常分支验证，测试结果仍为 144/144 通过。

## 📝 下次测试建议

1. 在 CI 增加 Playwright/Cypress 登录态浏览器测试，逐页执行中→英→中并截图比较。
2. 新增内置任务类型时同时添加稳定代码、双语词条、历史兼容映射和筛选测试。
3. 新增模型类型时明确其为内置翻译类型还是管理员自定义类型，并验证未知 label 回退。

**当前 Git 基准点**：`b3e5ca6`

---

## 历史测试记录（菜单名称本地化与英文布局）

## 📋 Git 基准点

- Commit: 8bf3561
- 提交说明: Localize menu names and fix English layouts
- 测试日期: 2026-07-27
- 分支: master

## 🎯 当前变更范围

- 集中维护内置菜单权限/路径与 i18n Key 的映射，侧栏、顶部标题和菜单管理页统一按当前语言显示。
- 同路径的模型目录与模型配置通过权限区分，顶部标题优先使用实际页面菜单。
- 自定义或未知菜单继续回退后台原始 `name`，不修改数据库和后端接口。
- 英文展开侧栏、移动抽屉、长菜单文字、侧栏说明和菜单编辑弹窗完成响应式适配。

## 📋 当前变更测试结果（2026-07-27）

**变更范围**：内置菜单名称国际化、同路径菜单解析、英文导航框架及菜单管理弹窗布局。

**测试执行结果**：
- 完整回归测试：216 个，通过 216 个（100%）
- 后端完整测试：139 个，通过 139 个（100%）
- 前端完整测试：77 个，通过 77 个（100%）
- 菜单本地化与布局定向测试：16 个，通过 16 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- 内置菜单中英文、未知菜单回退、同路径页面优先和组件统一接入：7/7，通过
- 导航框架、长英文文本、菜单弹窗及响应式布局：9/9，通过
- 后端完整回归：139/139，通过
- 前端完整回归：77/77，通过
- Python Worker：本次未执行测试，未修改 Worker 代码

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 菜单本地化与布局定向测试 | `node --test frontend/test/navigation.test.mjs frontend/test/layout-alignment.test.mjs` | 最终 16 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs` | 最终 77 通过，0 失败，0 错误，0 跳过；集中映射后首次运行有 2 个旧源码归属断言失败，更新断言归属后复测通过 |
| 后端完整回归 | `docker run --rm -v "$PWD:/workspace" -v base-ai-maven-cache:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B`（在 `backend/` 执行） | 139 通过，0 失败，0 错误，0 跳过；主机无 `mvn`，改用项目同版本 Maven 容器执行 |
| 服务重建与启动 | `docker compose up --build -d` | Frontend 生产构建通过，Backend、Frontend、Python Worker 均成功重建并启动 |
| 服务健康与格式检查 | `docker compose ps`、`git diff --check` | 三个服务均 healthy，差异格式检查通过 |

## 🔄 当前覆盖范围与结果

- 正常场景：内置菜单在中文和英文下显示对应名称，侧栏、顶部标题、菜单表格及上级菜单选择保持一致。
- 边界场景：空菜单、未知路径、自定义权限和目录/页面同路径均有回退或优先级测试。
- 异常场景：未知菜单不会抛出异常或显示翻译 Key，安全回退后台原始名称。
- 权限与安全：现有菜单权限裁剪、父目录提升和前端路由白名单测试继续通过，未改变授权逻辑。
- 兼容性：未修改菜单数据结构、后端 API 和自定义菜单保存行为；中文侧栏保持 272px，折叠态保持 64px。
- 回归场景：API Key、接口触发、AI 对话、字典、模型路由及全部前后端测试通过。
- 运行环境：Compose 已使用提交 `8bf3561` 的源码重建，Backend、Frontend、Python Worker 均 healthy。

## 🔄 当前重测触发条件

- 修改内置菜单权限、路径、i18n Key 或菜单树查找优先级。
- 修改侧栏、移动抽屉、顶部标题或菜单管理弹窗布局。
- 修改菜单权限裁剪、前端路由白名单或语言切换行为。
- 用户明确要求重新执行完整覆盖测试。

## ⚠️ 当前已知问题与限制

- 按确认范围未增加数据库双语字段；自定义菜单、无映射的按钮权限仍显示后台原始单语言 `name`。
- 菜单编辑框仍保存后台原始单一名称，不会把当前界面翻译写回数据库。
- 本机没有安装 `mvn`，Maven 测试通过官方 Maven Docker 镜像执行。
- 未执行登录后的真实浏览器端到端操作；当前通过 Node 测试、Frontend 生产构建和服务健康检查验证。
- 前端构建存在既有 runtime-config、第三方 PURE 注释和大 Chunk 警告，不影响构建成功。

## 📝 下次测试建议

1. 补充登录态浏览器端到端测试，实际切换中英文并覆盖桌面、平板和手机宽度。
2. 如果自定义菜单也需要双语编辑，新增 `nameZh`/`nameEn` 数据字段、迁移及接口兼容测试。
3. 新增内置菜单时将权限、路径、翻译资源和菜单本地化测试作为同一变更提交。

**当前 Git 基准点**：`8bf3561`

---

## 历史测试记录（后端默认语言可配置）

## 📋 Git 基准点

- Commit: da79cc2
- 提交说明: Make backend default locale configurable
- 测试日期: 2026-07-27
- 分支: master

## 🎯 当前变更范围

- 新增 `APP_DEFAULT_LOCALE` 后端默认语言配置，支持 `en-US`、`zh-CN`。
- 未配置时保持 `en-US`，请求显式携带 `Accept-Language` 时仍优先使用请求语言。
- 非法、空白或不支持的配置会阻止 Backend 启动并输出允许值。
- 同步类型安全配置、应用配置、Compose、环境变量模板和 README。

## 📋 当前变更测试结果（2026-07-27）

**变更范围**：后端默认 Locale 可配置、配置值严格校验、部署环境映射及请求语言优先级。

**测试执行结果**：
- 完整回归测试：210 个，通过 210 个（100%）
- 后端完整测试：139 个，通过 139 个（100%）
- 前端完整测试：71 个，通过 71 个（100%）
- 默认语言与响应契约定向测试：27 个，通过 27 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- 默认英文、中英文配置、属性绑定、请求头覆盖和非法值拒绝：10/10，通过
- 数字响应码、双语响应与消息资源完整性：17/17，通过
- 后端完整回归：139/139，通过
- 前端完整回归：71/71，通过
- Python Worker：本次未执行测试，未修改 Worker 代码

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 默认语言与响应契约定向测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=I18nConfigTest,ApiResponseContractTest,MessageBundleTest test` | 最终 27 通过，0 失败，0 错误，0 跳过；首次测试编译失败后修正 `BindResult.orElseThrow` Supplier 用法并复测通过 |
| 后端完整回归 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 139 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs` | 71 通过，0 失败，0 跳过 |
| Compose 配置映射 | `docker compose config` | Backend 环境已包含默认 `APP_DEFAULT_LOCALE=en-US` |
| 服务重建与启动 | `docker compose up --build -d` | Backend 构建内 139 个测试通过，Frontend 生产构建通过，三个服务启动成功 |
| 默认英文验证 | 无语言头请求受保护接口 | 返回 HTTP 401、数字 `code: 401` 和英文消息 |
| 配置中文及请求覆盖验证 | 临时以 `APP_DEFAULT_LOCALE=zh-CN` 重建 Backend；分别使用无语言头和 `en-US` 请求 | 无语言头返回中文，显式 `en-US` 返回英文；验证后已恢复 `en-US` |
| 服务健康与格式检查 | `docker compose ps`、健康端点、`git diff --check` | 三个服务均 healthy，健康端点正常，格式检查通过 |

## 🔄 当前覆盖范围与结果

- 正常场景：`en-US`、`zh-CN` 均能配置为默认语言，Compose 环境变量映射正确。
- 边界场景：未配置保持英文；空值、空字符串、`fr-FR`、`zh`、`english` 均被拒绝。
- 异常场景：非法配置抛出明确异常并列出允许值，避免静默回退。
- 权限与安全：无语言头与显式请求头均通过未认证接口验证，响应继续使用 HTTP 401 和数字状态码。
- 兼容性：默认值仍为 `en-US`，前端继续显式发送当前语言，现有调用行为不变。
- 回归场景：API 响应契约、消息资源、认证、系统管理和前端全部测试通过。
- 运行环境：最终以 `APP_DEFAULT_LOCALE=en-US` 运行，Backend、Frontend、Python Worker 均 healthy。

## 🔄 当前重测触发条件

- 修改 `APP_DEFAULT_LOCALE` 名称、默认值、支持语言或非法值处理方式。
- 修改 `PlatformProperties.I18n`、`I18nConfig`、LocaleResolver 或请求语言优先级。
- 修改 Compose、应用配置或环境变量模板中的默认语言映射。
- 用户明确要求重新执行完整覆盖测试。

## ⚠️ 当前已知问题与限制

- 本机没有安装 `mvn`，Maven 测试通过官方 Maven Docker 镜像执行。
- 非法配置的启动失败行为通过 LocaleResolver 单元测试验证，未使用错误配置实际启动完整 Compose 环境。
- `docker compose config` 会展开当前环境中的敏感变量；诊断输出不得对非受信人员公开，若已经泄露应轮换相关凭据。
- 仅支持 `en-US`、`zh-CN`；增加其他语言需要同步消息资源、支持列表和测试。
- `TaskTraceService` 的既有未检查泛型编译提示和前端大 Chunk 构建警告未影响测试结果。

## 📝 下次测试建议

1. 在发布流水线增加非法 `APP_DEFAULT_LOCALE` 的容器启动失败测试，并校验错误日志。
2. 部署时在外部环境文件显式设置 `APP_DEFAULT_LOCALE`，避免不同环境依赖隐式默认值。
3. 新增语言时同时补充完整消息资源、前端 Locale 和端到端响应验证。

**当前 Git 基准点**：`da79cc2`

---

## 历史测试记录（统一 API 响应码与双语消息）

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
