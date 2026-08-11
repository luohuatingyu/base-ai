# 最近分支覆盖测试报告

## 📋 市场节点导入默认启用修复测试结果（2026-08-11）

### Git 基准点

Commit: 09b6757792727088fec142590882c6265759c523
- 提交说明: Enable imported marketplace templates
- 测试日期: 2026-08-11
- 分支: master
- 上一测试报告基准点: `92c5ce191ef18cd89358f99493f5679e46f5cc96`
- Backend 业务代码差异: n8n/Dify 市场模板在新建、恢复、确认更新或同版本再次导入成功后立即启用；未确认的版本更新保持原状态。

### 变更范围

- 市场模板新建不再显式写入停用状态，导入结果为 `CREATED` 时可立即出现在默认启用节点目录中。
- 已删除模板重新导入时恢复并启用；确认市场版本更新时重置既有配置并启用。
- 同指纹模板再次导入时执行显式启用，修复旧版本导入后仍停用的记录；未再次导入的历史停用记录不会被批量修改。
- 插件注册表原有的导入成功后启用逻辑保持不变；未通过探测、无可执行组件和未确认版本更新均不会被错误启用。
- 未修改前端、接口模型、配置、数据库结构或依赖，未新增数据迁移。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 新导入模板默认启用 | Backend Service H2 测试；管理员身份 | 导入新的 n8n 市场模板 | 返回 `CREATED`，外部身份固定且 `enabled=true`；通过 | 正常、核心验收 |
| 重复导入恢复启用 | Backend Service H2 测试；同指纹模板已由管理员停用 | 再次导入相同固定版本 | 返回 `ALREADY_IMPORTED`，模板恢复启用；通过 | 幂等、兼容、回归 |
| 已删除模板恢复启用 | Backend Service H2 测试；导入模板已软删除 | 再次导入相同模板 | 返回 `RESTORED`，`voided=false` 且 `enabled=true`；通过 | 恢复、状态边界 |
| 确认更新后启用 | Backend Service H2 测试；市场指纹发生变化 | 先拒绝替换，再以 `replaceExisting=true` 导入 | 未确认时返回 `UPDATE_AVAILABLE` 且原版本和状态不变；确认后返回 `UPDATED` 并启用；通过 | 状态冲突、兼容 |
| 插件和不兼容路径无回归 | Marketplace/Registry Service 测试 | SUPPORTED、PARTIAL 和无可执行组件包 | 支持插件启用并生成模板；不支持包拒绝导入且不创建模板；通过 | 异常、安全、回归 |
| 完整构建与部署无回归 | Backend、Frontend、双 Worker、Compose | 完整测试、镜像构建、健康与 readiness 检查 | 826 项正式测试全部通过，六服务 healthy，readiness 为 UP；通过 | 回归、构建、部署 |

### 测试执行结果

- 正式完整回归共 826 项，826 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：532/532 通过；最终 Backend 镜像构建内再次执行 532/532 并完成打包。
- Frontend 两套完整回归：109/109、168/168 通过。
- Dify Worker 使用 Python 3.12：13/13 通过；n8n Worker：4/4 通过。
- Backend 定向回归：16/16 通过，包括市场模板持久化 7、市场导入编排 7、插件注册表 2。
- 缺陷复现阶段 7 项定向测试中新增的 3 个状态断言稳定失败，分别证明新建、恢复和确认更新均返回 `enabled=false`；修复后全部通过。

### 关键模块测试

- Service 层：覆盖 `CREATED`、`ALREADY_IMPORTED`、`RESTORED`、`UPDATE_AVAILABLE` 和 `UPDATED` 全部分支及启用副作用。
- Marketplace/Registry 层：确认导入只消费已完成探测结果，支持插件注册表继续启用，不支持组件不进入运行态。
- 权限与安全层：测试继续使用管理员身份；市场模板外部来源、编码和节点类型保持后端锁定，普通更新接口不能伪造身份。
- 兼容与数据层：未修改表结构；历史停用模板不会在部署时批量启用，只有显式再次导入才恢复启用。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 缺陷复现 | Maven 3.9.9 / Java 17 容器执行 `WorkflowServiceAccessTest` | 修复前 7 项中 3 项按预期失败，均为启用状态不符 |
| Backend 定向回归 | Maven 容器执行 `WorkflowServiceAccessTest`、`WorkflowNodeMarketplaceServiceTest`、`WorkflowPluginRegistryServiceTest` | 16/16 通过，BUILD SUCCESS |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 532/532 通过，BUILD SUCCESS |
| Frontend 完整回归 | `npm test`；`node --test test/*.mjs` | 109/109、168/168 通过 |
| Dify Worker | `python3.12 -m unittest discover -s tests -v` | 13/13 通过 |
| n8n Worker | `npm test` | 4/4 通过 |
| 统一重建 | `docker compose up --build -d` | Backend 镜像内 532/532；六服务最终全部 healthy |
| 运行态健康 | HTTPS readiness 与 `docker compose ps` | readiness 返回 `UP`；Backend、Frontend、Python Worker、Dify Worker、n8n Worker、Caddy 全部 healthy |
| 差异检查 | `git diff --check`、暂存区和工作区检查 | 代码提交仅包含本次两个 Backend 文件；未纳入与本任务无关的脚本状态 |

### 测试过程问题与处理

- 首次修复后定向测试使用了无效的模板来源构造停用场景，产生 1 个测试数据错误；改为合法且被后端锁定的 n8n 来源后重新执行，16/16 通过。未弱化业务断言。
- 第一次统一重建在等待 Frontend 健康检查时被交互中断，Caddy 尚未启动；重新执行完整 `docker compose up --build -d` 后六服务全部 healthy。
- readiness 首次按非当前配置的 444 端口检查失败；依据 Compose 实际映射改用 443 后返回 `UP`，不是应用故障或端口占用。

### 已知问题与限制

- 按确认方案不增加数据迁移：历史已导入且当前停用的模板保持原状，管理员再次执行同版本导入后才会启用。
- 再次导入是显式用户操作，因此会恢复管理员此前手动停用的同版本模板；如需继续停用，应避免重新导入或在导入后再次停用。
- 确认版本更新仍会沿用既有行为重置模板配置；本次只调整更新完成后的启用状态。

### 下次测试建议

1. 增加浏览器 E2E，覆盖市场探测完成、点击导入、列表刷新后卡片立即显示启用的完整交互。
2. 如未来需要自动处理历史停用记录，应先区分系统遗留停用与管理员主动停用，再设计可审计的数据迁移。

### 重测触发条件与回滚

- 修改市场模板导入状态、版本确认语义、插件注册启用条件、模板查询过滤或节点管理启停交互时，必须重跑相关 Backend 定向测试、完整回归和 Compose 重建。
- 应用代码可撤销提交 `09b6757792727088fec142590882c6265759c523` 后执行 `docker compose up --build -d`；本次无数据库迁移，无需数据回滚。

## 📋 Dify 市场插件探测修复测试结果（2026-08-11）

### Git 基准点

Commit: 92c5ce191ef18cd89358f99493f5679e46f5cc96
- 提交说明: Fix Dify plugin probing；Support Dify package imports
- 测试日期: 2026-08-11
- 分支: master
- 上一测试报告基准点: `721d8b2c3a7443ec598cf3aac9fe8cf9ef9576d5`
- Backend 业务代码差异: 探测调度只领取可立即执行的任务，Dify 固定版本不再逐任务重复检索市场，Worker 请求使用独立 240 秒默认硬超时。

### 变更范围

- Dify pip 安装禁用缓存，并把 HOME、TMPDIR 和安装临时文件放入当前插件持久化目录；安装完成、失败或超时后均清理临时目录，不再占满 64 MiB `/data/tmp`。
- 数据库继续承担持久化排队职责；线程池并发为 2 时最多只有 2 条记录进入 PROBING，其余保持 QUEUED，租约从真正提交执行时开始。
- Dify 已持久化插件 ID 和版本后直接下载固定版本包，不再为每个后台任务调用高级搜索；n8n 目录查询、npm SRI 和固定版本校验保持原逻辑。
- Backend Worker 请求超时与 8 秒市场请求超时解耦，新增 `WORKFLOW_PLUGIN_WORKER_TIMEOUT_SECONDS`，默认 240 秒、代码硬上限 600 秒。
- Dify Python 组件通过隔离合成根包加载，支持包内相对导入；ABI 元数据版本升级为 2，旧 Worker 缓存再次探测时会自动失效重建。
- 未新增第三方依赖或数据库迁移；Python Worker 仍使用 Python 3.12，n8n Worker 行为未改。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| Dify 依赖安装不耗尽 tmpfs | Dify Worker 单元测试；模拟含 requirements.txt 的插件 | 检查 pip 命令、HOME、TMPDIR、缓存开关及清理结果 | 使用 `--no-cache-dir`，临时目录位于插件卷且调用后删除；通过 | 正常、边界、资源 |
| 探测状态与实际并发一致 | Backend Probe Service；并发 1、三条固定版本任务 | 第一条 Worker 调用阻塞时执行 dispatch | 仅 1 条 PROBING、2 条 QUEUED；通过 | 并发、边界、副作用 |
| Dify 固定版本不重复搜索 | Backend Probe Service；已从当前页写入插件 ID 和版本 | 异步探测 Dify 包 | 直接下载固定版本并调用 Worker，findDify 未调用；通过 | 正常、兼容、外部依赖 |
| 慢 Worker 使用独立超时 | Backend Worker Client 本地 HTTP 测试 | 市场超时 30 秒、Worker 超时 1 秒、服务延迟 1.5 秒 | 按 Worker 独立配置超时并返回稳定不可用原因；通过 | 超时、异常 |
| 包内相对导入可执行 | Dify Worker 单元与真实市场验证 | 组件从 `.helper` 或同包工具模块导入 | 测试组件及 GitHub、Firecrawl、DOCX Generator 完整 SUPPORTED；通过 | 兼容、回归 |
| 旧 ABI 缓存不会永久误判 | Dify Worker 缓存测试 | 删除已缓存元数据的 hostAbiVersion 后再次探测 | 旧缓存被重建并写入 hostAbiVersion=2；通过 | 升级、缓存、回归 |
| n8n 和平台功能无回归 | Backend、Frontend、双 Worker 完整测试和 Compose | 完整测试、镜像构建、六服务启动 | 825 项正式测试全部通过，六服务 healthy；通过 | 回归、构建、部署 |

### 测试执行结果

- 正式完整回归共 825 项，825 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：531/531 通过；最终 Backend 镜像构建内再次执行 531/531 并完成打包。
- Frontend 两套正式回归：109/109、168/168 通过。
- Dify Worker（Python 3.12）：13/13 通过；n8n Worker（Node 24）：4/4 通过。
- Backend 定向回归：36/36 通过，包括 Probe Service 12、Worker Client 1、Marketplace Service 7、Marketplace Client 3、Schema 13。
- 缺陷测试先稳定失败：pip 未禁用缓存；并发 1 时错误出现 3 条 PROBING；Dify 固定任务因再次搜索缺失而 REJECTED；相对导入组件被误判 PARTIAL。修复后对应测试全部通过。

### 关键模块测试

- Dify 依赖层：验证安全 requirements 过滤、无缓存安装、持久卷临时目录、失败重试、超时分类和旧 ABI 缓存重建。
- Dify ABI 层：覆盖 Tool、Model、Agent Strategy、Datasource、Trigger、Extension、未知 Dify SDK 子模块、包内相对导入、OAuth 生命周期及调用输出。
- Backend 队列层：覆盖幂等入队、立即执行槽位、租约、重试耗尽、终止拒绝、Dify 固定版本直下和 n8n 原路径。
- Backend 网络层：市场请求和 Worker 请求使用独立超时；固定来源校验、包摘要和响应体限制保持有效。
- 前端与 n8n 回归：市场轮询、状态标签、动态插件字段、导入选择及 n8n ABI 全部通过。

### 真实运行态验证

- 一次性将 34 条 Dify 瞬态 FAILED 任务重置为 QUEUED；未修改 COMPLETE、REJECTED 或插件注册表记录。
- 重置后调度始终保持最多 2 条 PROBING；`/data/tmp` 从修复前 59/64 MiB 降为 0/64 MiB，两个并发 pip 进程均明确使用 `--no-cache-dir`。
- 34 条任务全部结束；当前 Dify 记录包括 16 个 COMPLETE/SUPPORTED、21 个 COMPLETE/UNSUPPORTED、1 个历史 COMPLETE/PARTIAL、2 个市场包无效拒绝和 1 个 Worker 安全拒绝。
- JSON Process 的 4 个组件全部 SUPPORTED；GitHub 21 个、Firecrawl 7 个、DOCX Generator 4 个、Feishu Spreadsheet 8 个组件均完成真实依赖安装和探测并达到包级 SUPPORTED。
- GitHub、Firecrawl 和 DOCX Generator 源码包含包内相对导入，证明合成包加载逻辑已在真实市场包生效。
- `docker compose up --build -d` 两次成功，六个服务最终全部 healthy；未发生端口占用。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 缺陷复现 | Python 3.12 单用例；Maven 容器执行新增 Probe Service 用例 | 修复前按预期 3 项失败，确认缺陷可复现 |
| Backend 定向回归 | Maven 3.9.9 / Java 17 容器执行 5 个相关测试类 | 36/36 通过 |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 531/531 通过，BUILD SUCCESS |
| Frontend 正式回归 | `npm test`；`node --test test/*.mjs` | 109/109、168/168 通过 |
| Dify Worker | `python3.12 -m unittest discover -s tests -v` | 13/13 通过 |
| n8n Worker | `npm test` | 4/4 通过 |
| Compose 配置与重建 | `docker compose config -q`；`docker compose up --build -d` | 配置有效；Backend 镜像内 531/531；六服务 healthy |
| 真实 Dify 队列 | 事务重置 34 条瞬态缓存并查询状态、进程、tmpfs 和组件结果 | 全部收敛；16 个包 SUPPORTED；并发和临时空间符合预期 |
| 差异与清理 | `git diff --check`、Git 状态、Python `__pycache__` 检查 | 无空白错误；测试缓存已清理；无调试文件 |

### 测试过程问题与处理

- 宿主机未安装 Maven，Backend 测试统一改用项目既有 Maven 3.9.9 / Java 17 容器和 Maven 缓存卷执行。
- 首轮真实探测恢复后，Tavily 暴露此前被依赖安装失败掩盖的相对导入错误；增加失败测试后改为隔离合成根包加载，并通过多个真实相对导入插件验证。
- Compose 重建会中断当时正在执行的两个 Dify 请求；Backend 将其按瞬态故障重新排队，最终全部任务正常收敛。
- Python 3.12 单元测试产生的 `__pycache__` 已在每次重建和提交前清理，未创建其他调试文件。

### 已知问题与限制

- 21 个 COMPLETE/UNSUPPORTED 包已经完成下载和依赖安装，但当前自研 ABI 仍无法构造可执行组件；这属于具体插件 ABI 能力差异，不再是 tmpfs、超时或队列故障。
- Tavily 是相对导入修复部署前写入的唯一历史 COMPLETE/PARTIAL 记录；其中 2 个组件已支持、3 个组件保留旧相对导入结论。根据本次确认范围未自动重置 COMPLETE 记录。
- 两个市场包超过现有限制或响应无效，另一个包被 Worker 安全校验拒绝；终止拒绝不会自动重试。
- Dify 每个固定版本保存独立依赖目录，依赖较多时会占用明显磁盘空间；现有 168 小时未安装探测缓存清理策略继续适用。

### 下次测试建议

1. 为当前 21 个 UNSUPPORTED 包按原因聚类，优先补充高频 Dify Model Provider 与 Tool Provider ABI 实体。
2. 增加 Dify 依赖目录磁盘配额或共享只读 wheel 缓存设计，同时保持插件运行时依赖隔离。
3. 增加浏览器 E2E，验证 20 个 Dify 当前页任务从 QUEUED 到 COMPLETE 的状态展示和可导入动作。
4. 为多 Backend 实例补充真实 MySQL 并发租约测试，确认每实例立即执行槽位与全局租约共同生效。

### 重测触发条件与回滚

- 修改 Dify requirements 过滤、pip 目录、ABI 加载、缓存版本、Worker 超时、探测领取、租约、固定版本下载或兼容聚合时，必须重跑 Backend/双 Worker 相关测试、Backend 完整回归及 Compose 重建。
- 应用代码可依次撤销 `92c5ce191ef18cd89358f99493f5679e46f5cc96` 和 `e327afd` 后执行 `docker compose up --build -d`。
- 本次无数据库结构迁移；一次性重置只修改探测缓存的状态和尝试次数，不影响已安装插件或工作流业务数据。

## 📋 n8n 与 Dify 市场插件自动探测测试结果（2026-08-11）

### Git 基准点

Commit: 721d8b2c3a7443ec598cf3aac9fe8cf9ef9576d5
- 提交说明: Probe marketplace plugins asynchronously
- 测试日期: 2026-08-11
- 分支: master
- 上一测试报告基准点: `2c5e37653f944cf488889c6eef7d68a2d159f714`
- Backend 业务代码差异: 市场列表自动把当前页未探测 n8n/Dify 固定版本包写入持久化队列，由独立线程池异步执行安全与 ABI 探测；导入仅消费完成结果，不再下载或探测插件包。

### 变更范围

- 打开、搜索或翻页 n8n/Dify 市场时，具有导入权限的用户会幂等排队当前页全部未探测包；只读用户只能查看已有状态。前端每两秒轮询，关闭弹窗或离开页面后清理定时器。
- V16 新增独立 `workflow_marketplace_plugin_probe` 表，按来源、包和版本唯一保存 QUEUED、PROBING、COMPLETE、REJECTED、FAILED 状态、包指纹、脱敏组件结果、重试次数、租约和访问时间；自动探测不会写入已安装插件注册表。
- 探测线程池默认并发 2、队列 100、最大尝试 3 次；租约过期任务可恢复。依赖安装失败或超时按临时基础设施故障重试，不固化为永久 ABI 不兼容；包结构、安全和 Worker 响应错误直接拒绝。
- 导入只重新确认市场元数据和版本，并从 V16 读取 Worker 结果；未探测、探测中、失败或没有 SUPPORTED 组件的插件返回明确冲突或不兼容结果。已安装版本更新继续要求人工确认。
- 未安装探测包默认保留 168 小时；清理任务先领取缓存记录，再通过内部鉴权 Worker 接口删除严格 SHA-256 目录。已被插件注册表引用的指纹永远不清理。
- 双 Worker 仍使用 Base AI 自研 Node/Python 3.12 ABI，不引入 n8n/Dify 引擎或 SDK，也未新增第三方依赖。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 当前页插件自动异步探测 | Backend Marketplace/Probe Service 与 Frontend 页面测试 | 管理员打开含插件的 n8n/Dify 页、重复刷新、切换兼容筛选 | 固定版本只入队一次，接口立即返回，前端自动轮询至完成；通过 | 正常、边界、并发 |
| 导入不产生包探测 | Backend Marketplace Service 测试 | 已完成、缺失、排队中和拒绝状态分别调用导入 | 仅完成结果可进入注册；导入路径未调用包下载或 Worker inspect；通过 | 核心验收、异常、副作用 |
| 权限与资源受控 | Backend Service、线程池和迁移测试 | 只有 list 权限、具有 import 权限、队列重复任务、租约过期 | 只读访问不产生任务；导入权限可入队；唯一键、并发、队列和租约生效；通过 | 权限、安全、边界 |
| 安全与 ABI 结果准确 | Backend 参数化聚合与双 Worker 测试 | 全 SUPPORTED、混合 PARTIAL、全不支持、路径穿越、摘要不匹配 | 包级结果分别为 SUPPORTED/PARTIAL/UNSUPPORTED；恶意包在落盘或执行前拒绝；通过 | 正常、分支、安全 |
| 临时依赖故障可恢复 | Backend 与 Dify Worker 测试 | DEPENDENCY_INSTALL_FAILED/TIMEOUT、缓存失败元数据、达到重试上限 | 重新安装依赖并按上限重试；成功后正常兼容，耗尽后 FAILED；通过 | 异常、超时、恢复 |
| 版本更新不覆盖运行版本 | Marketplace 与既有 Registry 回归 | 市场出现新版本、旧插件已安装、replaceExisting=false/true | 新版本独立探测；旧版本继续运行；只有确认后替换；通过 | 状态冲突、兼容、回归 |
| 缓存清理不影响已安装包 | Backend 与双 Worker 测试 | 过期未安装包、已安装指纹、非法删除指纹 | 仅未引用严格指纹目录可删除；路径和已安装包保留；通过 | 安全、边界、副作用 |
| 完整构建与部署无回归 | Backend、Frontend、双 Worker、Compose、Flyway | 完整测试、生产构建、V16迁移、健康检查 | 818项正式测试通过，六服务 healthy，MySQL Schema V16；通过 | 回归、构建、部署 |

### 测试执行结果

- 正式完整回归共 818 项，818 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：528/528 通过；最终 Compose Backend 镜像构建内再次执行 528/528 并完成打包。
- Frontend 两套正式回归：108/108、168/168 通过；生产构建成功。
- Dify Worker（Python 3.12）：10/10 通过；n8n Worker（Node 24）：4/4 通过。
- 相关定向 Backend 最终 30/30 通过，覆盖探测队列 10 项、市场服务 7 项和 Schema 13 项。
- 真实市场运行态：访问 n8n LogSnag 页面项和 Dify Google Calendar 页面项后，均由后台自动完成固定包下载、安全/ABI 探测，并返回 `COMPLETE`、`SUPPORTED`、可导入；未点击导入。
- `docker compose up --build -d` 成功；Flyway 确认从 V15 应用 V16，Backend readiness 为 UP，Backend、Frontend、Python Worker、Dify Worker、n8n Worker、Caddy 六服务全部 healthy。

### 关键模块测试

- Domain/Schema 层：未修改 Domain；Schema 资源测试 13/13 通过，V16 仅新增探测缓存表、唯一键和索引，不删除既有表或列。
- Service/队列层：Probe Service 10/10、Marketplace Service 7/7 通过；覆盖幂等入队、权限、聚合状态、重试耗尽、旧失败缓存恢复、缓存清理和导入零探测调用。
- Registry/执行层：既有插件注册、动态连接、OAuth 和固定指纹执行完整回归通过；自动探测与安装状态相互隔离。
- Worker 层：Dify 10/10、n8n 4/4；覆盖全部组件类型、安全解压、依赖源过滤、真实加载、严格指纹清理和依赖失败缓存重试。
- Frontend 层：108/108 与 168/168；覆盖探测状态标签、禁用未完成项、兼容筛选仍持续轮询、选择清理及页面卸载停止轮询。
- 权限与安全层：只有 `workflow:node:import` 权限能产生探测任务；原因码不向浏览器暴露 Worker 路径、依赖输出或凭据。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 定向回归 | Maven 3.9.9 / Java 17 容器执行 `WorkflowPluginProbeServiceTest`、`WorkflowNodeMarketplaceServiceTest`、`WorkflowSchemaResourceTest` | 最终 30/30 通过 |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 528/528 通过，BUILD SUCCESS |
| Frontend 正式回归 | `cd frontend && npm test`；`cd frontend && node --test test/*.mjs` | 108/108、168/168 通过 |
| Dify Worker | `python3.12 -m unittest discover -s tests -v` | 10/10 通过 |
| n8n Worker | `npm test` | 4/4 通过 |
| 真实市场自动探测 | 通过 HTTPS 登录后只请求 n8n `logs` 与 Dify `time` 市场页面并轮询列表状态 | LogSnag 与 Google Calendar 均 COMPLETE/SUPPORTED；未调用导入接口 |
| 统一重建 | `docker compose up --build -d` | Backend 构建内 528/528；Frontend 构建成功；最终六服务 healthy |
| 数据库与健康检查 | Backend Flyway 日志、readiness、`docker compose ps` | MySQL V16、readiness UP、六服务 healthy |
| 差异与清理 | `git diff --check`、暂存区检查、工作区状态、Cookie trap 与 `__pycache__` 检查 | 无空白错误、无无关提交、无临时 Cookie 或 Python 缓存 |

### 测试过程问题与处理

- 首次真实探测脚本尝试直接读取 `.env` 时，密码中的 shell 特殊字符导致解析失败；未输出密码，随后改为从运行中 Backend 容器安全读取环境变量并用 Node JSON 序列化。Cookie 始终位于 `/tmp/base-ai-plugin-probe-cookie.*`，由 shell trap 自动删除。
- Dify JSON Process、Database 和 Tavily 真实包首次返回 `DEPENDENCY_INSTALL_FAILED`。该结果可能是临时依赖源故障，因此新增可恢复分类、失败元数据重新安装及最大三次重试；仍不会把未加载组件误报为兼容。另选无该故障的 Google Calendar 完成真实 Dify 自动探测。
- 最终 Compose 重建收尾阶段与一次紧接着执行的 `docker compose up -d` 发生容器删除竞态，Docker 返回“removal already in progress”；待当前状态稳定后重试成功，未发生端口占用，最终六服务 healthy。
- Python 3.12 单元测试生成的 `__pycache__` 和 `.pyc` 已在提交前全部清理；未创建或提交其他调试文件。

### 已知问题与限制

- 自动探测只覆盖用户实际打开、搜索或翻到的当前页，不会定时扫描完整市场；这是本次确认的资源控制范围。
- 完整插件包会被下载并探测全部组件，不能只下载单个组件。导入通用插件时仅为 SUPPORTED 组件生成模板；PARTIAL/UNSUPPORTED 不会伪装为可执行。
- 外部 npm/PyPI 或市场不可用时会按上限重试并显示 FAILED；再次发布相同版本但更改包内容不属于正常市场契约，当前以官方固定版本为缓存键。
- 探测 Worker 仍是受限容器和短生命周期子进程，不是逐插件微虚机；第三方插件代码的固有风险继续由非 root、只读根、能力移除、资源限制和包校验降低。
- Frontend 构建保留既有 runtime-config 非 module 与大分块警告，不影响构建成功，与本次功能无直接关系。

### 下次测试建议

1. 增加浏览器 E2E，真实覆盖连续翻页、关闭重开弹窗、兼容筛选和长时间探测的视觉状态。
2. 在具备稳定 PyPI/npm 镜像的隔离环境重测依赖较多的 Dify/n8n 插件，区分永久依赖声明错误与临时依赖源故障。
3. 为多 Backend 实例增加数据库级并发集成测试，验证两个实例同时读取相同页时只有一个租约持有者执行 Worker 调用。
4. 结合磁盘监控评估默认 168 小时保留期；如市场浏览量较大，可降低保留期或增加插件缓存容量告警。

### 重测触发条件与回滚

- 修改市场分页/版本契约、V16、探测状态机、线程池/租约、重试分类、Worker 包缓存、导入前置条件、前端轮询或缓存清理时，必须重跑相关定向测试、Backend/Frontend/双 Worker 完整回归和 Compose 重建。
- 应用代码可撤销提交 `721d8b2c3a7443ec598cf3aac9fe8cf9ef9576d5` 后执行 `docker compose up --build -d`。
- V16 是前向新增表；旧代码不会引用该表。删除探测记录、Worker 包目录或 V16 表属于数据删除，回滚时不会自动执行，必须另行确认并先备份。

## 📋 n8n 与 Dify 市场插件 ABI 兼容测试结果（2026-08-11）

### Git 基准点

Commit: 2c5e37653f944cf488889c6eef7d68a2d159f714
- 提交说明: Expand marketplace plugin compatibility
- 测试日期: 2026-08-11
- 分支: master
- 上一测试报告基准点: `1fe40abc0e00a8e660af27bca040a5da9f284caf`
- Backend 业务代码差异: 新增市场插件注册表、固定包指纹与组件 Schema、通用插件执行器、OAuth 生命周期、n8n/Dify 市场下载和双隔离 Worker 调用；不引入或运行 n8n/Dify 引擎与 SDK。

### 变更范围

- 新增 Base AI 自研 Python 3.12 Dify ABI 与 Node 24 n8n ABI Worker；插件包在非 root、只读根文件系统、能力移除、PID/CPU/内存限制和短生命周期子进程中探测与执行。
- n8n 使用认证社区节点目录和 npm 官方注册表，固定版本并校验 SRI；支持程序式节点及常见声明式 `requestDefaults`、`routing.request/send`、凭据认证和 HTTP helper 子集。
- Dify 支持 Tool、Model、Agent Strategy、Datasource、Trigger、Extension 六类声明解析，安装插件自身 PyPI 依赖，但过滤 `dify-plugin`；未知 `dify_plugin.*` 路径由 Base AI 惰性 ABI 接管。
- 市场导入持久化包摘要、版本、组件身份、参数/凭据 Schema 和兼容状态；版本变化必须显式确认，PARTIAL/UNSUPPORTED 不伪装为可执行模板。
- 工作流新增六类通用插件节点、动态参数表单、动态加密插件连接和固定身份执行校验；凭据只从工作流所有者的同组件连接解密后发送给 Worker。
- OAuth 使用一次性高熵 state、数据库 SHA-256 索引、AES-GCM 加密 PKCE verifier、十分钟有效期、单次消费和 HTTPS 回调限制；插件不得覆盖宿主 state。
- V15 新增插件、组件、OAuth 状态和触发订阅表，并为插件连接增加组件外键；运行环境已应用 V15。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 不复用两套引擎与 SDK | Worker 单元测试、镜像与依赖检查 | 加载引用 `n8n-workflow`、`dify_plugin` 及未知子模块的测试插件 | 由 Base AI 同名 ABI shim 加载；依赖安装过滤引擎/SDK；通过 | 兼容、安全、回归 |
| 全类型声明可解析和调用 | 两个 Worker 的参数化/循环 E2E | Action/Tool、Trigger、Model、Datasource、Agent Strategy、Extension | 每类生成统一 Schema，并至少执行一次对应 ABI 方法；通过 | 正常、分支、兼容 |
| 市场包安全且版本固定 | Backend 市场客户端、Worker 包测试 | npm SRI、Dify SHA-256、路径穿越、链接、超限、非法依赖源和版本更新 | 恶意包在落盘/执行前拒绝，已安装版本不被静默覆盖；通过 | 边界、异常、安全 |
| 动态节点和连接可配置 | Backend 配置/执行器与 Frontend 表单测试 | 必填、条件显示、空值、类型错误、同/异组件连接、密钥脱敏 | 动态字段正确展示并在发布与执行时校验；跨组件和跨所有者拒绝；通过 | 正常、边界、权限 |
| OAuth 状态不可伪造或重放 | Backend OAuth Service 与 Frontend 回调契约测试 | 合法回调、非 HTTPS 地址、伪造 state、重复消费 | verifier 加密、state 哈希保存且只消费一次，伪造与重放拒绝；通过 | 正常、异常、权限、安全 |
| 真实市场插件按能力分级 | 官方市场临时包与 Top 100 探测 | Top 100 固定版本；n8n LogSnag、Dify JSON Process、Dify DeepSeek | n8n 包加载/Schema 100%，Dify 98%，均高于 80%；真实样本按 SUPPORTED/PARTIAL 正确分级 | 兼容、真实回归 |
| 完整构建与运行无回归 | Backend、Frontend、双 Worker、Compose、Flyway | 完整测试、生产构建、重建启动和健康检查 | 802 项正式测试通过；六服务 healthy；MySQL Schema V15；通过 | 回归、构建、部署 |

### 测试执行结果

- 正式完整回归共 802 项，802 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：516/516 通过；包含市场客户端、注册表、连接外键、节点执行、OAuth、防重放和 Schema 测试。
- Frontend 两套正式回归：107/107、168/168 通过；覆盖动态节点/凭据表单、OAuth 回调、目录、权限和容器安全契约。
- Dify Worker（Python 3.12）：8/8 通过；n8n Worker（Node 24）：3/3 通过。
- 市场 Top 100 结构兼容探测：n8n 100/100（100%）成功加载并生成 138 个组件 Schema，其中 128 个组件判定 SUPPORTED；Dify 98/100（98%）成功加载并生成 391 个组件 Schema，两个失败包超过当前压缩包安全上限。
- `docker compose up --build -d` 成功；Backend 镜像内再次执行 516/516，Frontend Vite 生产构建成功，六服务全部 healthy。
- Flyway 日志确认 MySQL 当前 Schema 为 V15；两个插件 Worker 健康接口分别返回 Base AI Python ABI/Python 3.12 和 Base AI Node ABI/Node 24。

### 关键模块测试

- Domain/Schema 层：未修改 Domain；Schema 资源测试 12/12 通过，覆盖 V15 四张插件相关表和连接组件外键。
- Service/执行器层：插件注册表 2/2、OAuth 4/4、节点执行器 4/4、连接服务 7/7、市场服务 6/6、市场客户端 3/3 通过。
- Repository/持久化层：未新增 Repository；使用现有 `JdbcTemplate`，H2 隔离测试覆盖注册、固定版本、PARTIAL 拒绝、OAuth 单次消费和插件连接外键。
- Controller/权限层：新增接口均使用既有 `workflow:connection:update` 或 `workflow:node:list/import` 权限；完整权限契约回归通过。
- Worker 层：覆盖真实加载、全部类型调用、声明式路由、依赖缺失、非法依赖源、摘要错误、路径穿越、OAuth 生命周期和输出规范化。
- Frontend 层：动态参数/凭据 Schema、条件字段、必填提示、插件连接、OAuth 跳转回调、双语节点文档和市场探测状态通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 相关回归 | Maven 3.9.9 / Java 17 容器执行市场、注册表、连接、执行器、OAuth 和配置校验套件 | 最终相关套件全部通过 |
| Backend 完整回归 | Maven 容器执行 `mvn -B -ntp test`；Compose Backend 构建执行 `mvn -B -ntp package` | 516/516 通过，BUILD SUCCESS |
| Frontend 正式回归 | `cd frontend && npm test`；`cd frontend && node --test test/*.mjs` | 107/107、168/168 通过 |
| Dify Worker | Python 3.12 执行 `python3.12 -m unittest discover -s tests -v` | 8/8 通过 |
| n8n Worker | Node 24 执行 `npm test` | 3/3 通过 |
| Top 100 兼容探测 | n8n 按下载量、Dify 按安装量读取前 100 个固定版本到 `/tmp`；n8n 使用当前依赖安装与 PackageStore，Dify 本轮仅验证包结构和 Schema，不安装依赖或执行第三方网络逻辑 | n8n 100/100（100%）；Dify 98/100（98%）；均达到 ≥80% |
| 真实市场执行冒烟 | 官方市场下载固定版本到 `/tmp`，使用当前 PackageStore 探测并调用，命令结束自动删除临时目录 | LogSnag SUPPORTED；JSON Process 4 个 Tool SUPPORTED 且调用成功；DeepSeek MODEL PARTIAL |
| 统一重建 | `docker compose up --build -d` | 初次成功；最终复核时清理外部端口占用并重建 Caddy；Backend 516/516，Frontend 构建成功 |
| 数据库与健康检查 | Backend Flyway 日志、Worker 健康接口、`docker compose ps`、HTTPS 首页 | MySQL V15；六服务 healthy；两个 Worker ABI 正常；HTTPS 200 |
| 差异与清理 | `git diff --check`、`git diff --cached --check`、工作区状态和 `__pycache__` 检查 | 无空白错误、冲突、调试文件或 Python 缓存 |

### 测试过程问题与处理

- 插件连接外键上线后，首轮 H2 连接测试仍使用旧表结构，导致 5 条 SQL 错误；同步正式 V15 列并增加创建/改绑外键断言后 7/7 通过。
- 插件触发入口首条测试错误地把 `input` 子对象本身作为上下文，导致断言读取路径错误；修正测试请求构造后通过，未弱化生产逻辑。
- 真实 LogSnag 首次探测为声明式路由 PARTIAL；补充受控 HTTP 路由解释器和凭据认证后重新探测为 SUPPORTED。
- 真实 DeepSeek 能加载 Provider 和生成 Schema，但其模型调用依赖 Dify SDK 的模型基类语义；保持 PARTIAL，未通过占位实现误报支持。
- Dify Top 100 有 2 个包因超过 `PLUGIN_MAX_PACKAGE_BYTES` 被安全拒绝；未临时放宽上线限制以追求覆盖率。
- 最终健康复核时 Caddy 曾收到外部 SIGTERM，随后 80/443 被 `domestic-trade-caddy` 占用；按仓库规则停止占用容器并 `--force-recreate` 本项目 Caddy，最终六服务 healthy、HTTPS 200。
- 所有 `/tmp/base-ai-*` 市场探测目录均由 shell trap 删除；测试产生的 `__pycache__` 已清理，插件测试包未写入工作区。

### 已知问题与限制

- n8n 声明式节点当前覆盖常见 request/send/authenticate 路由；依赖 `preSend`、`postReceive`、复杂表达式或未实现 helper 的节点会标记 PARTIAL 或在明确 ABI 错误处停止。
- Dify Tool 类插件兼容度较高；依赖 Dify SDK 内部模型基类、守护进程服务或复杂实体行为的 Model Provider（真实 DeepSeek 样本）仍为 PARTIAL。
- Plugin Trigger 已具备类型、Schema、subscribe/refresh/dispatch ABI 和工作流入口数据传递，但需要常驻原引擎语义的长连接订阅不复用原引擎，因此不能保证兼容；此类插件应依据探测结果和真实事件回归使用。
- OAuth 仅支持插件自身提供授权/换码 ABI；n8n 仅声明通用 OAuth2 Credential、没有可调用扩展源码的条目仍为 PARTIAL。
- 插件代码具有第三方代码固有风险；当前使用独立非 root 容器、只读根、资源限制、包校验和短生命周期子进程隔离，但不是内核级逐插件微虚机沙箱。
- Frontend 构建保留既有 runtime-config 非 module 和大分块警告，不影响构建成功，与本次改造无直接关系。

### 下次测试建议

1. 在独立测试环境为 Dify Top 100 安装完整插件依赖并执行无外部副作用的组件级调用，按 ABI 缺失原因细分 SUPPORTED/PARTIAL/UNSUPPORTED；当前 98% 指标仅代表包加载和 Schema 生成。
2. 增加浏览器 E2E，覆盖真实 OAuth 供应商跳转、回调刷新、密钥脱敏编辑和授权失败恢复。
3. 为轮询型与 Webhook 型插件 Trigger 增加 Base AI 原生订阅调度和公开回调路由，再使用真实事件完成端到端验证。
4. 按真实市场高频模型 Provider 实现独立模型协议适配，避免引入 Dify SDK 的同时逐步提升 MODEL 可执行比例。

### 重测触发条件与回滚

- 修改市场契约、包下载校验、任一 ABI shim、动态 Schema、插件注册/执行/OAuth、连接加密、Worker 安全参数或 V15 后续迁移时，必须重跑相关套件、Backend/Frontend 完整回归、双 Worker 测试和 Compose 重建。
- 应用代码可依次撤销功能提交 `2c5e37653f944cf488889c6eef7d68a2d159f714` 与基础设施提交 `8df671d` 后执行 `docker compose up --build -d`。
- V15 是前向数据迁移；回滚前必须停用插件模板和连接并备份四张插件表。删除表、外键或已安装包属于数据删除，本次未自动执行，需单独确认。

## 📋 工作流模型兼容路由与向量节点测试结果（2026-08-10）

### Git 基准点

Commit: 1fe40abc0e00a8e660af27bca040a5da9f284caf
- 提交说明: Add workflow model compatibility routing
- 测试日期: 2026-08-10
- 分支: master
- 上一测试报告基准点: `c69ec030b515d36b33438fb990af1c6e7f7b9d4c`
- Backend 业务代码差异: 新增工作流模型兼容目录与 `EMBEDDING` 原生执行器；模型类型、路由和指定模型候选按节点协议及真实模型能力筛选；新增 V14 系统模板迁移。

### 变更范围

- 聊天协议节点仅允许已验证的 `text_model`、`vision_model`，向量协议节点仅允许 `embedding_model`；字典中的未来未知类型不会自动进入不兼容节点。
- 工作流模型类型、能力路由和指定模型选择器由后端兼容目录及模型实际声明能力共同筛选，切换类型后会清理失效候选，路由和指定模型两种来源均受后端校验。
- 新增文本向量化节点，支持单文本或最多 256 项文本数组，经现有 `/llm/embeddings` 协议输出顺序稳定的向量、模型、数量和维度。
- 节点文档配置区新增模型协议、推荐类型、允许类型、模型来源和未知类型处理规则表；保留桌面左侧目录固定、右侧内容独立滚动的布局。
- V14 仅幂等新增 `EMBEDDING` 系统模板，不修改既有表字段、历史迁移或第三方代码，也未新增依赖和配置。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 节点按实际协议选择模型类型 | Backend 兼容目录、配置校验和 Frontend 配置测试 | LLM/Agent/RAG/分类器/参数提取器选择文本、视觉、向量或未知类型 | 聊天节点仅接受文本/视觉，向量节点仅接受向量，未知类型拒绝；通过 | 正常、异常、兼容、安全 |
| 路由与指定模型候选均按真实能力过滤 | Backend Service、Controller 与 Frontend 选择器测试 | 混合文本、向量、音频模型及对应路由，并切换节点和模型类型 | 仅返回节点允许且候选真实支持的模型/路由，旧值在成功加载后清理；通过 | 正常、分支、异常、回归 |
| 向量节点支持单文本与批量输入 | Backend 执行器、客户端和配置校验测试 | ROUTE 单文本、DIRECT 两项数组、空值、非文本、超长及超量输入 | 两种模型来源均调用向量协议并保持顺序；非法输入在外部调用前失败；通过 | 正常、边界、异常、副作用 |
| 节点目录与数据库包含向量节点 | Backend Schema/目录测试和 Frontend 全目录参数化测试 | 遍历全部原生类型并检查 V14 资源 | `EMBEDDING` 具备 AI 分类、默认配置、双语名称、说明和系统模板；通过 | 正常、兼容、回归 |
| 节点文档展示统一兼容表 | Frontend 文档页面契约测试与 Backend 权限契约测试 | 打开任一模型节点文档并加载兼容目录 | 展示协议、推荐/允许类型、来源和仅已知兼容规则，接口使用文档权限；通过 | 正常、权限、安全 |
| 完整构建与部署无回归 | Backend、Frontend、Compose 与 Flyway | 完整测试、生产构建、应用 V14 并启动服务 | 598 项正式测试通过，生产构建成功，四服务 healthy，Schema 为 V14；通过 | 回归、构建、部署 |

### 测试执行结果

- 正式完整回归共 598 项，598 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：497/497 通过；相关定向套件最终 125/125 通过。
- Frontend 正式回归：101/101 通过；相关配置、文档和目录定向套件 58/58 通过。
- `docker compose up --build -d` 成功；Backend Dockerfile 再次执行 497/497 测试并完成打包，Frontend 生产构建成功。
- MySQL Flyway 从 V13 成功应用 V14；Backend、Python Worker、Frontend、Caddy 全部 healthy。

### 关键模块测试

- Domain/Schema 层：未修改 Domain；Schema 资源测试 11/11 通过，验证 V14 幂等模板和历史迁移边界，运行日志确认数据库为 V14。
- Service/执行器层：模型管理 44/44、AI 客户端 8/8、向量节点执行器 3/3、模型兼容目录 2/2 通过，覆盖 ROUTE/DIRECT、能力过滤、批量顺序和失败前无外部调用。
- Repository/持久化层：未新增或修改 Repository；V14 仅写入系统模板，Flyway 实际执行成功。
- Controller/权限层：工作流模型选项控制器 5/5 通过，覆盖动态字典交集、模型/路由筛选和兼容目录权限。
- Frontend 层：101/101 通过，覆盖动态模型类型、失效值清理、向量节点配置、双语目录和兼容表；生产构建成功。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 相关定向回归 | Maven 3.9.9 / Java 17 容器执行兼容目录、Schema、配置校验、模型管理、Controller、向量执行器和 AI 客户端测试 | 125/125 通过 |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 497/497 通过，BUILD SUCCESS |
| Frontend 相关定向回归 | `node --test frontend/tests/workflowNodeConfig.test.js frontend/tests/workflowNodeDocumentation.test.js frontend/tests/workflowTemplateCatalog.test.js` | 58/58 通过 |
| Frontend 正式回归 | `cd frontend && npm test` | 101/101 通过 |
| 统一重建 | `docker compose up --build -d` | 成功；Backend 构建内 497/497 通过，Frontend Vite 构建成功 |
| 数据库与健康检查 | Backend Flyway 日志、`docker compose ps` 与 Caddy 容器健康状态 | MySQL V14；四服务 healthy |
| 差异与提交检查 | `git diff --check`、`git diff --cached --check`、文件白名单和工作区状态 | 功能提交仅包含本任务 27 个文件；无调试文件或无关改动 |

### 测试过程问题与处理

- 首轮 Backend 定向测试有 2 条 Schema 断言仍假定 V6/V7 之后不存在新增节点；将 `EMBEDDING` 纳入后置迁移集合后重新执行通过，未修改历史迁移。
- 补充向量路由客户端测试时有 1 条断言错误地要求 Worker 向量请求携带仅在后端解析的路由字段；改为验证固定 `embedding_model` 路由解析及实际候选下发后重新执行通过，未弱化业务结果。
- Frontend 首轮定向测试有 2 条旧源码断言只接受无查询参数接口；更新为兼容新增 `nodeType/modelType` 参数后 58/58 通过。
- 宿主机未安装 Maven，所有 Backend 测试使用固定 Maven 3.9.9 / Java 17 容器执行；仓库未产生临时调试文件。

### 已知问题与限制

- 新增字典模型类型不会自动获得节点兼容性；必须先明确其执行协议并更新统一兼容目录，符合“仅返回已知兼容类型”的确认策略。
- 自动化使用 Mockito 和本地 HTTP Worker 替身验证路由、请求与响应，没有消耗真实外部向量模型额度；运行环境已验证 Worker 健康但未提交真实模型调用。
- Python Worker 与 Caddy 代码未变，本次未单独重跑其 pytest/Go 测试；统一镜像重建、依赖启动和健康检查均通过。
- Frontend 构建保留既有 runtime-config 非 module 和大分块警告，不影响构建成功，与本次变更无直接关系。

### 下次测试建议

1. 使用测试向量模型分别执行 ROUTE、DIRECT 的工作流端到端运行，核对实际维度、批次顺序、超时和供应商失败回退。
2. 增加浏览器组件或 E2E 测试，覆盖快速切换节点/模型类型时的竞态响应、失效选择清理及文档表格窄屏滚动。
3. 新增模型协议类型时同步扩展兼容目录和执行器契约测试，明确未知类型何时可供人工选择。

### 重测触发条件与回滚

- 修改模型类型字典、模型能力声明、节点兼容目录、路由候选解析、向量协议、节点配置/文档或 V14 后续迁移时，必须重跑相关定向测试、Backend/Frontend 完整回归和 Compose 重建。
- 应用代码可撤销提交 `1fe40abc0e00a8e660af27bca040a5da9f284caf` 后执行 `docker compose up --build -d`；回滚前应停用引用 `EMBEDDING` 的已发布工作流。
- V14 是前向模板数据迁移；旧代码可忽略该模板。删除模板属于数据变更，必须备份并再次取得人工确认，本次不执行自动数据回滚。

## 📋 工作流节点文档完善测试结果（2026-08-10）

### Git 基准点

Commit: c69ec030b515d36b33438fb990af1c6e7f7b9d4c
- 提交说明: Improve workflow node documentation
- 测试日期: 2026-08-10
- 分支: master
- 上一测试报告基准点: `3e704b6`
- Backend 业务代码差异: 节点文档新增独立只读接口，使用 `workflow:node:docs` 权限查询当前用户可见的模板元数据；不改变节点运行或数据结构。

### 变更范围

- 全部 40 种原生节点均提供中英文专属行为、输入、输出及异常/限制说明；系统、市场导入和自建模板继续复用原生契约并展示来源、版本和发布者。
- 节点文档展示字段类型、示例默认值、枚举选项、动态必填状态和可参考配置示例；RAG 的 ROUTE/DIRECT 条件字段状态与当前配置规则一致，Tavily 专用字段复用已本地化词条。
- 页面通过独立 `/workflow/node-docs` 接口加载，提供加载、失败、无搜索结果和未知模板的安全状态。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 全部原生节点具备完整双语说明 | Frontend 参数化单元测试 | 遍历当前 40 种原生节点及四类说明段落 | 每种节点均有行为、输入、输出、限制词条；通过 | 正常、兼容、回归 |
| 配置说明准确可用 | Frontend 单元测试 | RAG 路由/指定模型、HTTP、Tavily、知识库节点示例 | 字段类型、默认值、选项、动态必填及适用分支正确；通过 | 正常、边界、分支 |
| 文档权限独立 | Backend 控制器契约测试与运行态未认证请求 | 查询 `/workflow/node-docs` | 映射使用 `workflow:node:docs`；未认证请求返回 401；通过 | 权限、安全 |
| 页面安全处理异常状态 | Frontend 页面契约测试 | 模拟加载失败、空搜索和未知节点路径 | 显示明确状态，不渲染 HTML/Markdown；通过 | 异常、安全 |
| 全模块与部署无回归 | Backend、Frontend、Worker、Caddy、Compose | 构建、正式测试和健康检查 | 643 项正式测试通过，四服务 healthy；通过 | 回归、构建、部署 |

### 测试执行结果

- 正式完整回归共 643 项，643 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend Docker 构建内完整回归：485/485 通过；新增节点文档控制器定向测试 1/1 通过。
- Frontend 正式回归：94/94 通过；节点文档与配置定向回归 34/34 通过。
- Python Worker：Python 3.12 一次性容器完整回归 48/48 通过。
- Caddy：Go 1.26.5 一次性容器 16/16 通过，`go vet ./...` 通过。
- `docker compose up --build -d` 成功；Backend、Python Worker、Frontend、Caddy 全部 healthy，HTTPS 首页返回 200，节点文档接口未认证返回 401。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 节点文档与配置定向测试 | `node --test frontend/tests/workflowNodeDocumentation.test.js frontend/tests/workflowNodeConfig.test.js` | 34/34 通过 |
| Frontend 正式回归 | `cd frontend && npm test` | 94/94 通过 |
| Backend 定向测试 | Maven 3.9.9 / Java 17 容器执行 `-Dtest=WorkflowNodeDocumentationControllerTest test` | 1/1 通过 |
| Backend 完整回归 | `docker compose up --build -d` 的 Backend Dockerfile 内执行 `mvn -B -ntp package` | 485/485 通过，BUILD SUCCESS |
| Worker 完整回归 | Python 3.12 一次性容器安装哈希锁定开发依赖并执行 `pytest -q -p no:cacheprovider` | 48/48 通过 |
| Caddy 完整回归 | Go 1.26.5 一次性容器临时初始化模块并执行 `go test -v ./...`、`go vet ./...` | 16/16 通过，vet 通过 |
| 运行态检查 | `docker compose ps` 与 HTTPS 请求 | 四服务 healthy；首页 200，文档接口未认证 401 |

### 已知问题与限制

- README 合并前端入口 `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` 为 259/262 通过；3 个失败均是 `api-trigger-security.test.mjs` 对已演进 Host 规则页面的过期源码断言，与本次节点文档文件无交集，未删除、跳过或弱化。
- 文档页面的权限映射由控制器契约测试和未认证运行态请求验证；当前环境未创建“仅拥有 `workflow:node:docs`”的单独角色进行浏览器端到端验证。

### 下次测试建议

1. 增加浏览器组件测试或 E2E，验证仅有节点文档权限的角色可加载目录、搜索和失败提示。
2. 将历史 `api-trigger-security.test.mjs` 的三条源码断言迁移为当前 Host 规则交互契约，恢复 README 合并入口全绿。

### 重测触发条件与回滚

- 修改节点类型目录、节点说明词条、配置字段/默认值/条件必填、文档接口权限或页面加载逻辑时，必须重跑本节定向测试、Backend/Frontend 完整回归和 Compose 重建。
- 应用回滚可撤销提交 `c69ec030b515d36b33438fb990af1c6e7f7b9d4c` 后执行 `docker compose up --build -d`；本功能无数据库迁移或数据回滚操作。

## 📋 工作流知识库检索与入库节点测试结果（2026-08-10）

### Git 基准点

Commit: aad9bc301904e5be9da332c7f0fe1d3132d78237
- 提交说明: Fix RAG conditional field requirements
- 功能提交: `c1b1dd9f3be6741d749d03ab2e4a8960617f1cb0`（Add workflow knowledge nodes）
- 测试日期: 2026-08-10
- 分支: master
- 上一测试报告基准点: `75ea3fb93c86a00d3facf8e1144697cfb11f70ba`
- Backend 业务代码差异: 新增 `KNOWLEDGE_RETRIEVAL`、`KNOWLEDGE_UPSERT` 原生执行器，知识库后台入库显式绑定工作流所有者，并新增 V13 系统模板迁移。

### 变更范围

- `KNOWLEDGE_RETRIEVAL` 只调用知识库向量检索，不调用生成模型；输出知识库标识、名称、匹配数量以及包含切片、文档、文件名、正文和分数的匹配列表。
- `KNOWLEDGE_UPSERT` 支持 TEXT 与 BASE64 两种输入，复用现有文档提取、切片、批量 Embedding、向量写入、失败状态和重复内容保护；输出文档 ID、文件名、状态和切片数。
- 后台入库接口接收显式工作流所有者 ID，通过知识库所有者条件读取目标，避免执行线程依赖登录态或跨租户写入。
- 后端发布校验、运行默认值、节点分类、前端可视化字段、条件必填、知识库选择器、双语名称和节点文档目录同步覆盖两类新节点。
- V13 只新增两个系统节点模板，不修改既有表、字段或历史迁移；未新增依赖、配置或第三方代码。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 纯检索不触发生成模型 | Backend 执行器单元测试；知识库返回一个匹配 | 使用所有者 7、Top-K 3、阈值 0.2 检索 | 所有者参数完整传递，返回 count、正文和分数；通过 | 正常、权限、副作用 |
| 文本和 Base64 均可入库 | Backend 执行器参数测试；知识库服务使用替身 | 分别提交 UTF-8 文本和 Base64 文档 | 传入原始字节、文件元数据和工作流所有者，返回 READY 文档信息；通过 | 正常、兼容 |
| 非法输入不产生索引副作用 | Backend/Frontend 配置校验和执行器测试 | 非法 Base64、空文件名/类型、Top-K 0/51、阈值越界 | 在知识库调用前拒绝并返回稳定字段提示；通过 | 边界、异常、安全 |
| 新节点不能跨所有者访问知识库 | Backend 执行器与知识库服务契约 | 工作流运行携带 owner ID | 检索和入库均使用显式 owner ID，目标不存在或不属于所有者时由服务拒绝；通过 | 权限、安全 |
| 历史 RAG 和节点目录保持兼容 | Backend RAG/目录/Schema 测试、Frontend 配置与文档测试 | 加载全部 39 类节点并切换 RAG 模型来源 | 原 RAG 输出不变；RAG 条件字段必填正确；全部节点均有分类、配置和文档骨架；通过 | 兼容、回归 |
| 完整构建与部署有效 | 隔离提交快照、Python 3.12、Go 1.26.5、Compose | 完整测试并应用 V13 | 正式测试 639/639 通过，四服务 healthy，Schema 为 V13；通过 | 回归、构建、部署 |

### 测试执行结果

- 正式完整回归共 639 项，639 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 提交快照完整回归：484/484 通过；阶段一定向测试 67/67 通过。
- Frontend 提交快照正式回归：91/91 通过；当前共享工作区包含未提交的节点文档增强时为 94/94 通过。
- Python Worker：Python 3.12 一次性容器完整回归 48/48 通过。
- Caddy：Go 1.26.5 一次性容器 16/16 通过，`go vet ./...` 通过。
- `docker compose up --build -d` 成功；Backend、Python Worker、Frontend、Caddy 均为 healthy，HTTPS 根路径正常，readiness 为 UP，Backend 日志确认 MySQL Schema 为 V13。

### 关键模块测试

- Domain/Schema 层：未修改 Domain；V13 资源测试验证两个模板、分类及历史 V6/V7/V12 迁移边界。
- Service 层：知识库显式所有者入库、检索参数、文本/Base64 解码和无副作用失败路径通过。
- Repository/持久化层：继续使用既有 `JdbcTemplate` 与知识库三表；无新增 Repository，V13 仅插入模板。
- Controller 层：阶段一未新增 Controller；现有知识库上传和 RAG 接口回归通过。
- Frontend 层：节点字段、条件显示、必填提示、知识库资源选择、分类、双语名称及文档目录完整性通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 定向回归 | Maven 3.9.9 / Java 17 容器执行知识库执行器、配置校验、Schema、分类及 RAG 测试 | 67/67 通过 |
| Backend 提交快照完整回归 | 隔离工作树执行 `mvn -B -ntp test` | 484/484 通过，BUILD SUCCESS |
| Frontend 提交快照正式回归 | 隔离工作树安装锁定依赖并执行 `npm test` | 91/91 通过 |
| Worker 完整回归 | Python 3.12 一次性容器安装哈希锁定开发依赖并执行 `pytest -q` | 48/48 通过 |
| Caddy 完整回归 | Go 1.26.5 一次性容器临时初始化模块并执行 `go test -v ./...`、`go vet ./...` | 16/16 通过，vet 通过 |
| 统一重建 | `docker compose up --build -d` | 成功，未发生端口冲突 |
| 数据库与健康检查 | Backend Flyway 日志、`docker compose ps`、HTTPS readiness 与首页 | MySQL V13；四服务 healthy；readiness UP；HTTPS 正常 |
| 提交隔离检查 | `git diff --cached --check`、暂存文件白名单、隔离提交快照复测 | 只提交阶段一文件；并行节点文档改动保持未暂存 |

### 测试过程问题与处理

- 宿主机没有 Maven，首次定向命令未进入测试；改用固定 Maven 3.9.9 / Java 17 容器后完成定向和完整回归。
- 新节点最初触发 V6/V7 Schema 测试对未来节点的错误假设；保持历史迁移不可变，改为由 V12/V13 独立测试覆盖后置节点，重新测试通过。
- 共享工作区在实现期间并行修改节点文档 Controller、页面、详细词条和测试；阶段一使用 Git 索引隔离和临时提交工作树验证，没有覆盖或提交这些改动。
- 首次隔离前端测试发现 RAG 条件字段断言和实现未同时进入功能提交；补充单行兼容提交后，提交快照 91/91 通过。
- 临时验证工作树 `/tmp/base-ai-stage1-c1b1dd9` 与 `/tmp/base-ai-stage1-aad9bc3` 均已删除；Go/Python 临时依赖随容器退出清理，仓库无调试文件。

### 已知问题与限制

- 动态入库是同步外部副作用；向量写入成功后若后续外部系统失败，工作流无法自动删除已写入文档。
- 同内容文档继续使用知识库现有哈希唯一约束，重复提交返回冲突，不会创建重复向量；节点名称中的 UPSERT 表示受控写入，不表示按文件名覆盖。
- 未使用真实外部向量服务执行工作流端到端写入；本次复用上一基准已验证的 pgvector、Qdrant、Milvus 和 Elasticsearch 适配器契约。
- 统一重建使用共享工作区，包含未提交的节点文档增强；业务提交自身的 Backend 与 Frontend 已分别通过隔离快照完整测试。

### 下次测试建议

1. 使用隔离真实向量库执行工作流触发的文本与 Base64 入库、纯检索、重复内容和跨所有者端到端测试。
2. 为知识库显式所有者入库增加 MySQL Testcontainers 集成测试，覆盖并发重复、失败重传和外部成功/内部失败补偿。
3. 文档规模增长后将动态入库改为可取消异步任务，并增加状态轮询、幂等键和补偿删除能力。

### 重测触发条件与回滚

- 修改知识库索引、检索、节点配置/校验、执行器注册、模板分类或 V13 后续结构时，必须重新执行 Backend、Frontend、Worker、Caddy 完整回归和 Compose 重建。
- 应用回滚可撤销提交 `aad9bc301904e5be9da332c7f0fe1d3132d78237` 与 `c1b1dd9f3be6741d749d03ab2e4a8960617f1cb0` 后统一重建；回滚前必须停用新模板并确认没有已发布工作流引用新节点。
- V13 为前向模板数据迁移；旧版应用会忽略模板记录。删除模板属于数据操作，必须另行备份并取得人工确认，本次不执行。

## 📋 知识库 RAG 与向量存储测试结果（2026-08-10）

### Git 基准点

Commit: 75ea3fb93c86a00d3facf8e1144697cfb11f70ba
- 提交说明: Add knowledge base RAG support
- 测试日期: 2026-08-10
- 分支: master
- 上一测试报告基准点: `f813b0c4f7853639a777408aad860975878d120c`
- Backend 业务代码差异: 新增知识库、向量连接能力探测、四类向量存储适配器、RAG 执行器和固定向量模型调用，并扩展工作流连接、节点目录、初始化菜单及权限。

### 变更范围

- 工作流连接新增 Qdrant、Milvus、Elasticsearch 类型；PostgreSQL、Qdrant、Milvus、Elasticsearch 连接测试会执行实际向量能力探测并保存引擎、版本、状态、时间和脱敏失败原因。
- 新增知识库、文档和加密切片持久化结构；知识库固定一个向量模型，支持平台托管资源和绑定预建资源、COSINE/L2/IP、文档提取、重叠切片、批量向量化、失败状态与安全重传。
- 统一适配 pgvector JDBC 以及 Qdrant、Milvus v2、Elasticsearch REST；创建或校验维度和距离算法，支持幂等写入、Top-K 检索、按文档清理及托管资源删除，并限制出站目标、标识符、超时和响应大小。
- 新增 RAG 原生节点，按工作流所有者隔离知识库，限制检索参数和上下文长度，把检索内容作为不可信数据交给文本模型，并输出回答、引用、匹配片段及 Token 统计。
- 新增知识库管理页和独立节点文档中心；文档中心覆盖全部原生节点，并复用模板元数据展示系统、市场导入和管理员自建模板的输入、输出、配置、示例与限制。
- V12 MySQL 迁移已在运行环境应用；未新增依赖、配置文件或第三方代码修改。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 连接类型可选且显示向量能力 | Backend 服务与网络策略测试、Frontend 配置测试 | 新建四类向量连接并执行测试 | 仅实际探测通过的连接标记 SUPPORTED，修改连接后重置状态；通过 | 正常、异常、兼容、安全 |
| 四类向量存储契约受控 | Backend HTTP 协议替身和适配器测试 | 探测 Qdrant/Milvus/ES，校验预建资源维度与距离 | 识别产品响应，错误目标脱敏失败，维度或距离不匹配被拒绝；通过 | 正常、边界、异常、安全 |
| 知识库索引生命周期有效 | Backend 切片、Schema 和模型客户端测试 | 上传文本、切片、批量 embeddings、失败后同文件重传 | 切片长度/重叠受控，响应顺序一致，失败状态可清理重试；通过 | 正常、边界、异常、副作用 |
| 外部资源删除不越界 | Backend 适配器与服务逻辑回归 | 删除托管或预建知识库、删除单文档 | 托管模式删除隔离资源，预建模式只按文档清理平台向量；通过 | 权限、安全、兼容 |
| RAG 节点可配置并隔离所有者 | Backend RAG 执行器和配置参数化测试 | 选择知识库、Top-K、阈值及路由/指定文本模型 | 校验全部必填和范围，按工作流所有者检索并生成带引用回答；通过 | 正常、边界、权限、安全 |
| 节点文档覆盖全部模板 | Frontend 文档目录完整性测试 | 比较原生节点目录和显式文档目录，转换导入模板 | 39 类原生节点无遗漏，系统/导入/自建模板保留来源元数据；通过 | 正常、兼容、回归 |
| 全模块和部署无回归 | Backend、Worker、Frontend、Caddy 完整回归及 Compose | 构建提交 `75ea3fb` 并应用 V12 | 正式测试 632/632 通过，四服务 healthy，Schema 为 V12；通过 | 回归、构建、部署 |

### 测试执行结果

- 当前正式测试入口及组件完整回归共 632 项，632 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：477/477 通过；覆盖知识库切片、向量存储协议、预建资源维度/距离校验、连接状态、RAG 执行和节点配置。
- Python Worker：Python 3.12.13 完整回归 48/48 通过；本次复用既有 embeddings 路由。
- Frontend 正式完整回归：91/91 通过；生产构建成功，保留既有 runtime-config 和大分块警告。
- Caddy Go：16/16 通过，`go vet ./...` 通过；本次未修改 Caddy。
- 运行环境：`docker compose up --build -d` 成功，MySQL Schema 为 V12，Backend、Python Worker、Frontend、Caddy 均为 healthy，HTTP health 与 readiness 成功。
- README 合并前端入口共 259 项，256 项通过、3 项失败；失败仍为上一基准已记录的 `api-trigger-security.test.mjs` 过期源码断言，本次未修改对应页面或测试。

### 关键模块测试

- Domain/Schema 层：V12 资源测试覆盖连接能力字段、知识库三张表、约束和 RAG 系统模板；运行环境 Flyway 确认 V12。
- Service 层：向量协议、知识库切片、模型批量调用、连接能力状态和 RAG 执行相关测试全部通过。
- Repository/持久化层：本功能使用现有 `JdbcTemplate` 规范，无新增 Repository；Schema、所有者条件、加密切片和删除 SQL 由资源测试及完整回归覆盖。
- Controller/权限层：知识库接口显式绑定 list/create/update/delete 与工作流画布权限；RAG 单元测试验证工作流所有者 ID 传递。
- Frontend 层：连接类型、RAG 可视化配置、知识库选择器、文档目录完整性、导航和生产构建通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整回归 | `docker compose build backend`，Dockerfile 内执行 `mvn -B -ntp package` | 477/477 通过，BUILD SUCCESS |
| Worker 完整回归 | Python 3.12.13 一次性容器设置 `PYTHONPATH=/app`、安装哈希锁定开发依赖并执行 `pytest -q` | 48/48 通过 |
| Frontend 正式回归 | `cd frontend && npm test` | 91/91 通过 |
| Frontend 生产构建 | `cd frontend && npm run build` | 成功；仅既有构建警告 |
| Frontend README 合并入口 | `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` | 256/259 通过；3 个既有过期断言失败 |
| Caddy 完整回归 | 固定 Go 1.26.5 容器临时初始化模块后执行 `go test -v ./...`、`go vet ./...` | 16/16 通过，vet 通过；仓库未生成 Go 模块文件 |
| 统一重建 | `docker compose up --build -d` | 首次因 80 端口占用失败；停止 `domestic-trade-caddy` 后重试成功，最新代码再次重建成功 |
| 数据库与健康检查 | 检查 Backend Flyway 日志、`docker compose ps`、HTTP health/readiness | MySQL V12；四服务 healthy；探针成功 |
| 差异检查 | `git status`、`git diff --check`、敏感词和未跟踪文件检查 | 功能提交仅包含 42 个确认文件；无调试文件、真实密钥或第三方代码 |

### 测试过程问题与处理

- 宿主机未安装 Maven，定向 Maven 命令未进入测试；随后使用项目 Backend Dockerfile 执行完整 Maven 回归。
- Python 3.12 容器首次因挂载目录未进入模块搜索路径，在收集阶段出现 4 个 `ModuleNotFoundError`；设置 `PYTHONPATH=/app` 后 48/48 通过，未产生仓库调试文件。
- 适配器审查发现 pgvector 初版固定余弦索引、Milvus 初版错误使用标量 filter 过滤距离；已按官方协议改为三种距离映射、Milvus searchParams 和客户端统一分值过滤，并补充预建资源维度/距离测试。
- 首次 Compose 启动时 `domestic-trade-caddy` 占用 80/443；按项目规则停止该容器后重试，当前项目四服务最终健康。
- 测试和审查未删除、跳过或弱化任何有效测试；一次性 Python/Go 依赖与模块文件随容器退出清除。

### 已知问题与限制

- 未提供可安全写入的真实 pgvector、Qdrant、Milvus 和 Elasticsearch 测试实例，因此自动化使用官方响应结构的本地协议替身验证 REST 契约；真实实例必须在连接配置页执行“测试”后才会成为知识库可选项。
- 文档索引当前在上传请求内同步完成，10 MiB 上限和每批最多 256 个切片可控，但大型文档的响应时间取决于向量模型和外部存储延迟。
- MySQL 中保存的是 AES-GCM 加密切片和哈希，外部向量库只保存切片/文档 ID 与向量；数据库管理员仍应按敏感数据要求管理备份和密钥。
- V12 为新增表和字段的前向迁移，代码回滚不会自动删除知识库数据或外部托管资源。
- README 合并前端入口仍有 3 个与本次无关的过期源码断言；正式 `npm test` 入口 91/91 通过。
- 为释放 80/443 已停止 `domestic-trade-caddy`；如需恢复该入口，应先释放当前项目端口。

### 下次测试建议

1. 使用隔离的四类真实向量服务分别执行探测、托管创建、预建绑定、写入、检索、按文档删除和资源删除 E2E，并覆盖各产品支持版本。
2. 为知识库服务补充容器级 MySQL 集成测试，验证失败重传、并发上传、事务边界和外部成功/内部失败的补偿策略。
3. 文档规模增长后将索引改为受控异步任务，增加取消、进度、重试上限和幂等任务测试。
4. 独立修正 Frontend 历史补充入口的 3 个过期断言，统一 README 与正式测试入口。

### 重测触发条件与回滚

- 修改知识库表结构、文档提取/切片、向量模型批次、任一向量适配器、距离算法、连接能力状态、RAG 配置/执行、节点文档目录或相关权限时，必须重跑 Backend、Worker、Frontend、Caddy 完整回归和 Compose 统一重建。
- 应用回滚可撤销功能提交 `75ea3fb93c86a00d3facf8e1144697cfb11f70ba` 后执行 `docker compose up --build -d`；旧版应用可忽略 V12 新增结构。
- 数据回滚属于破坏性操作：必须先备份并清理平台托管的外部资源，再经人工确认后删除知识库三张表和连接能力字段；本次不自动执行数据回滚。

## 📋 向量模型平台能力同步测试结果（2026-08-10）

### Git 基准点

Commit: f813b0c4f7853639a777408aad860975878d120c
- 提交说明: Add vector model worker support
- 测试日期: 2026-08-10
- 分支: master
- 上一测试报告基准点: `da1b8ac0dccb53e10efe1a5e39a8a738da9d832e`
- Backend 业务代码差异: 仅在平台初始化与模型管理中注册 `embedding_model`、选择向量健康检查；同步 Python Worker 通用 embeddings 能力。未同步商品匹配 Controller、Repository、Service、SQL、前端业务字段、业务文案或价格校验。

### 变更范围

- 模型类型目录新增内置 `embedding_model / 向量模型`；启动时只补充缺失项，不覆盖管理员维护的已有字典数据。
- 模型连接测试根据模型声明能力向 Worker 发送 `embedding=true/false`；向量模型使用最小 embeddings 请求，文本和视觉模型保持原聊天健康检查。
- Python Worker 新增受内部令牌保护的 `POST /llm/embeddings`，支持批量输入、候选模型和 API Key 轮换、超时、并发控制及候选故障切换。
- OpenAI 兼容响应按 `index` 恢复输入顺序，并拒绝数量不匹配、重复或布尔索引、非数值或布尔向量值、空向量、维度不一致、非有限数和超大响应。
- 请求限制为 1 至 256 条输入、每条去空白后 1 至 500 字符、1 至 20 个候选；调用日志只记录模型、数量和维度，不记录向量输入或 API Key。
- 未新增依赖、配置、数据库迁移、Markdown 文件或外部业务消费者；未修改第三方代码。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 模型目录提供向量类型 | Backend 初始化与模型管理单元测试；字典为空 | 执行初始化并读取回退目录 | 保存文本、视觉、向量三项，回退目录包含可读向量标签；通过 | 正常、边界、兼容、副作用 |
| 模型测试选择正确协议 | Backend HTTP 客户端参数化测试；文本或向量模型已配置 | 调用模型连接测试 | 文本发送 `embedding=false`，向量发送 `embedding=true`；通过 | 正常、分支、回归 |
| embeddings 请求兼容 OpenAI | Worker HTTP 模拟；供应商返回乱序 index | 批量提交两条文本 | 请求 `/embeddings` 且模型和输入不变，结果恢复原输入顺序；通过 | 正常、兼容、副作用 |
| 候选故障切换有效 | Worker HTTP 模拟；首个候选返回 503 | 使用两个向量候选 | 按顺序尝试，第二候选成功后返回结果；通过 | 异常、可用性、回归 |
| 请求边界受控 | Worker Pydantic 参数化测试 | 空、空白、超长、257 条输入，0 或 21 个候选 | 全部在调用供应商前拒绝；通过 | 边界、异常、安全 |
| 供应商异常响应不污染结果 | Worker 参数化测试 | 缺项、重复或布尔 index，非数值、布尔、空、异维、NaN 向量 | 返回受控校验失败，不产生部分结果；通过 | 异常、边界、安全 |
| 响应大小受统一限制 | Worker 流式响应测试；上限 1024 字节 | 供应商返回 1025 字节 | 达到上限后立即失败，不继续缓冲解析；通过 | 边界、安全、资源 |
| 新接口保持内部认证 | 运行态 Worker；不发送内部令牌 | 请求 `POST /llm/embeddings` | 返回 HTTP 401；通过 | 权限、安全 |
| 全模块与部署无回归 | Backend、Worker、Frontend、Caddy 正式完整回归及 Compose | 当前功能提交 | 正式测试 618/618 通过，四服务 healthy，HTTPS 与 readiness 成功；通过 | 回归、构建、部署 |

### 测试执行结果

- 当前正式测试入口及组件完整回归共 618 项，618 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：466/466 通过；定向的初始化和模型管理测试 54/54 通过。
- Python Worker：Python 3.12 完整回归 48/48 通过；其中 LLM 定向测试 33/33 通过。
- Frontend 正式完整回归：88/88 通过；本次没有 Frontend 文件差异。
- Caddy Go：16/16 通过，`go vet ./...` 通过；本次没有 Caddy 文件差异。
- 权限与运行态：未认证 embeddings 请求返回 401；Backend 和 Worker readiness 均为 `UP`，四服务全部 healthy，HTTPS 根路径返回 200。
- 额外执行未纳入 `npm test` 的历史 `test/*.test.mjs`：168 项中 165 通过、3 失败。失败仍是既有 `api-trigger-security.test.mjs` 对当前工作流 CIDR 文本框和保存流程的过期源码断言，本次未修改对应页面，与上一测试基准一致，不计入本功能 618 项正式验收结果。

### 关键模块测试

- Domain 层：未修改 Domain 实体；相关兼容性由 Backend 466 项完整回归覆盖。
- Service/初始化层：`DataInitializerTest` 与 `LlmManagementServiceTest` 合计 54/54 通过，覆盖目录初始化、回退标签和连接测试协议分支。
- Repository 层：未修改 Repository 接口或持久化结构；初始化测试验证新增字典保存副作用，完整 Backend 回归通过。
- Controller/权限层：未新增公开 Java Controller；Worker 内部接口未认证烟测返回 401，既有认证中间件回归通过。
- Worker 模型层：LLM 定向 33/33、Worker 完整 48/48 通过，覆盖请求/响应模型、调用协议、故障切换和安全边界。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 预修复定向测试 | Maven 3.9.9 / Java 17 容器执行 `mvn test -B -ntp -Dtest=DataInitializerTest,LlmManagementServiceTest` | 54 项中新增断言稳定产生 3 失败、1 错误，分别定位缺失向量初始化、回退项和请求字段 |
| Worker 预修复定向测试 | Python 3.12 一次性容器执行 `python -m pytest -q -p no:cacheprovider tests/test_llm.py` | 收集阶段因缺少 Embedding 模型失败，证明当前能力缺失 |
| Backend 修复后定向测试 | 同一 Maven 容器命令 | 54/54 通过，BUILD SUCCESS |
| Worker 修复后定向测试 | Python 3.12 一次性容器执行 LLM 测试 | 33/33 通过 |
| Backend 完整回归 | `docker compose up --build -d` 的 Backend Dockerfile 执行 `mvn -B -ntp package` | 466/466 通过，BUILD SUCCESS |
| Worker 完整回归 | Python 3.12 一次性容器安装哈希锁定开发依赖后执行 `python -m pytest -q -p no:cacheprovider` | 48/48 通过 |
| Frontend 正式完整回归 | `cd frontend && npm test` | 88/88 通过 |
| Frontend 历史补充入口 | `cd frontend && node --test test/*.test.mjs` | 165/168 通过；3 个既有接口触发安全页源码契约失败 |
| Caddy Go 完整回归 | 固定 Go 1.26.5 镜像只读挂载源码，在容器临时目录执行 `go test -v ./...`、`go vet ./...` | 16/16 通过，vet 通过；仓库未生成 go.mod/go.sum |
| 统一重建 | `docker compose up --build -d` | 首次入口端口由源项目 Caddy 占用；停止 `domestic-trade-caddy` 后重试成功 |
| 权限与运行状态 | 无内部令牌请求 embeddings，检查 Compose、HTTPS、Backend/Worker readiness | embeddings 返回 401；四服务 healthy；HTTPS 200；两个探针均为 UP |
| 差异检查 | `git status`、`git diff --check`、文件白名单、商品业务关键字和未跟踪文件检查 | 仅 8 个确认文件进入功能提交；无商品业务代码、密钥、调试或临时文件 |

### 测试过程问题与处理

- 主机 Python 为 3.12.13，但未安装 pytest，且主机没有 Maven；直接命令未进入测试，随后全部改用项目既有的隔离容器方式，未向主机或仓库新增依赖。
- 预修复测试先稳定暴露缺失能力，再补充实现并复跑到通过；未删除、跳过或弱化有效测试。
- 代码审查发现 Python `bool` 同时是 `int` 子类，显式拒绝布尔 index 和向量值，并增加对应恶意响应测试。
- 首次统一启动时 `domestic-trade-caddy` 占用 80/443；按项目规则仅停止该入口容器并重新启动当前项目，最终四服务全部健康。
- Caddy 一次性测试首次使用登录 shell 导致镜像预设 Go PATH 被重置，未启动测试；改用非登录 shell后 16/16 和 vet 通过。
- 测试产生的 Python `__pycache__` 已清理；Caddy 模块文件只存在于一次性容器临时目录，容器退出后自动清除。

### 已知问题与限制

- 当前只提供平台模型类型、连接健康检查和 Worker 内部 embeddings 能力；没有同步商品匹配、pgvector 索引或任何业务消费者。
- 仅验证 OpenAI 兼容协议的可执行模拟，没有使用真实外部向量模型 API Key 进行公网端到端调用。
- embeddings 调用方必须提供模型中心已解析的候选列表；Worker 不自行解析 Java 能力路由。
- 启动后会持久化 `embedding_model` 字典项。回滚代码不会自动删除该行，但旧版动态模型类型逻辑可兼容保留项。
- 历史 Frontend 补充目录仍有 3 个与本次无关的过期断言失败；本次未修改、删除或弱化这些测试。
- 为释放 80/443 已停止 `domestic-trade-caddy`；如需恢复源项目入口，应先释放当前项目端口后再启动。

### 下次测试建议

1. 使用专用沙箱向量模型执行真实 embeddings E2E，覆盖供应商限流、超时、Key 轮换、不同维度和实际响应大小。
2. 后续业务接入时为 Java 调用方补充默认路由筛选、维度约束、持久化一致性和业务权限测试，不在 Worker 中耦合业务索引。
3. 增加 Worker 路由级集成测试，使用有效内部令牌验证完整请求与响应序列化；当前路由行为由函数测试、Pydantic 测试和 401 烟测共同覆盖。
4. 在独立任务中修正或迁移 Frontend 历史补充入口的 3 个过期源码断言，避免与正式 `npm test` 入口长期分叉。

### 重测触发条件与回滚

- 修改模型类型目录、模型连接测试协议、Worker embeddings 请求/响应模型、候选切换、输入输出限制或内部认证时，必须重跑 Backend 与 Worker 定向测试、四组件完整回归及 Compose 统一重建。
- 应用回滚可撤销功能提交 `f813b0c4f7853639a777408aad860975878d120c` 后执行 `docker compose up --build -d`；本次没有 Schema 或配置迁移。
- 如需彻底恢复数据状态，可在确认没有模型引用后另行删除 `llm_model_type` 中的 `embedding_model` 字典项；该数据删除不属于本次自动回滚范围。

## 📋 n8n 与 Dify 节点兼容修复测试结果（2026-08-10）

### Git 基准点

Commit: da1b8ac0dccb53e10efe1a5e39a8a738da9d832e
- 提交说明: Fix marketplace node compatibility
- 测试日期: 2026-08-10
- 分支: master
- 上一测试报告基准点: `499cea569b9acfb4e284ed1cbae007b986934e73`
- Backend 业务代码差异: 新增 Tavily 受管连接和原生执行器，修正 Dify 插件分页与动作模型、市场模板更新语义、批量预校验和外部身份保护，因此执行 Backend、Frontend、Python Worker、Caddy 完整回归、官方契约烟测和 Compose 统一重建。

### 变更范围

- n8n 与 Dify 可导入项明确标记为 Base AI 原生能力子集，不再暗示完整兼容第三方节点的全部操作、参数和输出。
- Dify Tavily 以一个插件卡片参与分页，Search 与 Extract 作为可选择子动作；Crawl、Map、Research 仍显示为未纳入支持范围。
- Tavily API Key 改由独立 TAVILY 连接使用 AES-GCM 加密保存和脱敏返回；模板、工作流图和版本快照只保存连接 ID，不再保存凭据。
- 新增 TAVILY_TOOL 原生执行器，固定调用 Tavily Search/Extract 官方 HTTPS 端点，通过 Authorization Bearer 注入凭据，并拒绝非 2xx 响应。
- 导入前校验 Dify provider 的 secret-input 凭据声明和 Search/Extract 必填参数；市场目录解析增加录制契约测试，外部响应结构漂移时显式失败。
- 市场批量导入先完成全部外部条目与包声明预校验，再进入单事务持久化，避免后续条目非法时留下前序部分导入。
- 已导入模板的来源、编码和节点类型由 Backend 锁定；市场指纹变化返回 UPDATE_AVAILABLE，管理员确认后才重置为最新原生配置、更新版本并保持停用。
- Frontend 增加 TAVILY 连接配置、Search/Extract 条件字段、Dify 子动作选择、原生子集提示和版本更新二次确认；未新增依赖、配置、数据库迁移或第三方运行代码。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| Tavily 凭据不进入模板或请求体 | Backend 连接 Service 与执行器测试；创建 TAVILY 连接 | 保存 `tvly-secret`，执行 Search | 数据库仅保存密文，管理视图返回掩码，Bearer Header 注入密钥且 Body 不含密钥；通过 | 正常、安全、副作用 |
| Tavily 非成功响应中止执行 | Backend 执行器测试；外部服务返回 401 | 执行 Extract | 抛出连接执行失败，不把鉴权错误作为正常节点输出；通过 | 异常、安全 |
| Dify 插件分页与动作选择正确 | Backend 市场 Service + Frontend 契约测试 | 查询 Tavily 插件并选择 Search/Extract | total 按一个插件计算，返回两个子动作且可独立选择；通过 | 正常、边界、交互 |
| 兼容范围表达真实 | Backend Service + Frontend 双语契约测试 | 浏览 n8n/Dify 可导入项 | 统一返回 NATIVE_SUBSET 并提示不保证第三方完整等价；通过 | 兼容、交互 |
| 外部包契约漂移可检测 | Backend 包解析和录制契约测试；构造错误凭据类型、参数类型和响应结构 | 解析 Dify ZIP、n8n/Dify 目录 | 当前声明通过；凭据、必填参数或目录数组漂移时拒绝；通过 | 正常、异常、安全 |
| 批量非法输入不部分持久化 | Backend 市场 Service 测试；第一项受支持、第二项不受支持 | 一次提交两个外部 ID | 全部草稿预校验失败后持久化服务未调用；通过 | 异常、事务、副作用 |
| 已导入模板身份不可伪造 | Backend H2 Service 测试；已导入 n8n 模板 | 更新接口提交伪造 code/type/source | 保存后仍保持原编码、节点类型和来源，仅允许维护字段变化；通过 | 权限、安全、兼容 |
| 市场更新需明确确认 | Backend H2 Service + Frontend 契约测试；指纹和版本变化 | 首次不替换、随后确认替换 | 依次返回 UPDATE_AVAILABLE、UPDATED；旧版本未提前改变，确认后配置重置且 disabled；通过 | 状态冲突、交互、副作用 |
| 全模块与部署无回归 | 四套完整测试、官方只读接口和 Compose | 当前功能提交 | 正式测试 595/595 通过，四服务 healthy，HTTPS 与 readiness 成功；通过 | 回归、构建、部署 |

### 测试执行结果

- 当前正式测试入口及组件完整回归共 595 项，595 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：463/463 通过；覆盖连接加密脱敏、Tavily Bearer 执行、非 2xx、市场契约解析、Dify 插件分页、批量预校验、身份锁定、版本更新确认和 Schema 不变量。
- Frontend 当前正式入口：88/88 通过；覆盖 TAVILY 连接字段、节点条件参数、连接类型过滤、Dify 子动作和更新确认契约。
- Python Worker：Python 3.12 一次性容器执行 28/28 通过。
- Caddy Go：16/16 通过，`go vet ./...` 通过。
- 官方只读契约：n8n 目录和 Dify 高级搜索均返回 200；Dify 当前 Tavily 0.1.11 包仍声明 secret-input 凭据及 Search/Extract 字符串参数；Tavily 官方文档确认 Usage/Search/Extract 的 Bearer 鉴权与当前参数边界。
- 额外执行未纳入 `npm test` 的历史 `test/*.test.mjs`：168 项中 165 通过、3 失败。失败仍是既有 `api-trigger-security.test.mjs` 对当前工作流 CIDR 文本框和保存流程的过期源码断言，本次未修改对应页面，不计入本功能 595 项正式验收结果。
- Compose 生产构建成功；Backend、Frontend、Python Worker、Caddy 全部 healthy，HTTPS 根路径返回 200，Backend 与 Worker readiness 均返回 UP。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整回归 | `docker compose build backend` 及统一重建中的 Dockerfile Maven 3.9.9 / Java 17 `mvn -B -ntp package` | 463/463 通过，BUILD SUCCESS |
| Frontend 正式完整回归 | `cd frontend && npm test` | 88/88 通过 |
| Frontend 历史补充入口 | `cd frontend && node --test test/*.test.mjs` | 165/168 通过；3 个既有接口触发安全页源码契约失败 |
| Python Worker 完整回归 | Python 3.12 一次性容器安装哈希锁定开发依赖后执行 `python -m pytest -q -p no:cacheprovider` | 28/28 通过 |
| Caddy Go 完整回归 | Go 1.26.5 一次性容器临时初始化模块并执行 `go test -v ./...`、`go vet ./...` | 16/16 通过，vet 通过 |
| 官方市场与 API 契约烟测 | 限时请求 n8n/Dify 官方目录，流式读取 Dify Tavily 0.1.11 声明，对照 Tavily 官方 Markdown API 文档 | 目录均为 200；凭据、关键参数、Bearer 鉴权和支持范围符合适配器约束 |
| 统一重建 | `docker compose up --build -d` | 首次 Caddy 因 80 端口占用失败；停止占用容器后重试成功 |
| 运行状态 | `docker compose ps`、HTTPS 根路径、Backend/Worker readiness | 四服务 healthy；HTTPS 200；两个探针均为 UP |
| 差异检查 | `git diff --check`、文件范围、敏感字段和未跟踪文件检查 | 通过；API Key 仅出现在加密连接和测试数据中，无调试或临时文件 |

### 测试过程问题与处理

- 首版 Dify 参数校验使用 Java 模式变量时超出作用域，编译失败后改为显式安全收窄并重新执行完整测试。
- 首版将 TAVILY_TOOL 当作历史内置模板，Schema 测试稳定暴露缺少迁移；确认该节点仅由市场动态导入后增加 MARKETPLACE_ONLY 不变量，未新增无意义的数据库迁移。
- Caddy 一次性测试容器首次缺少 `gopkg.in/yaml.v3`，未启动用例；在容器临时模块中获取项目真实依赖后重新执行，16/16 和 vet 均通过，未写入仓库。
- 首次统一启动由 `domestic-trade-caddy` 占用 80/443；按项目规则停止该容器后重新执行，最终四服务全部健康。
- Backend 健康烟测首次请求了不存在的 `/actuator/health` 并返回 404；随后使用 Compose 定义的 `/api/open/health/ready` 验证为 UP，不影响服务状态。

### 已知问题与限制

- n8n 仍只支持 PostgreSQL、MySQL、Redis、AWS S3/S3、Kafka Trigger 和 RabbitMQ Trigger 的 Base AI 原生语义子集；不是完整 n8n 运行时。
- Dify 当前只支持 Tavily Search 与 Extract；Crawl、Map、Research 和插件 Python 运行时不在本次范围内。
- 未使用真实 Tavily API Key 发起成功的公网端到端请求；Bearer Header、请求体、2xx 输出和 401 失败由可执行测试覆盖。运行前还需在接口触发安全策略中允许 `api.tavily.com`。
- 旧版已导入 Tavily HTTP 模板不会静默改写；重新导入检测到 UPDATE_AVAILABLE 后，管理员需确认更新、选择 TAVILY 连接并重新启用。
- 未执行浏览器自动化 E2E；页面交互由 Frontend 工具测试、Vue 源码契约测试和生产构建覆盖。
- 历史 Frontend 补充目录仍有 3 个与本次无关的过期断言失败；本次未删除、跳过或弱化这些测试。
- 为释放 80/443 已停止 `domestic-trade-caddy`；如需恢复该项目，应先释放本项目端口后再启动。

### 下次测试建议

1. 使用专用测试 Tavily Key 增加 Search/Extract 公网沙箱 E2E，并校验额度、超时、限流和响应体上限。
2. 增加浏览器 E2E，覆盖 Dify 插件子动作多选、UPDATE_AVAILABLE 取消/确认、连接选择、停用模板重新启用和错误提示。
3. 为 n8n 每个白名单节点建立更细的操作/参数能力矩阵；新增映射前以官方节点版本录制契约和真实依赖容器验证语义差异。
4. 在后续独立任务中修正或迁移 `frontend/test/api-trigger-security.test.mjs` 的 3 个过期源码契约，并统一两套 Frontend 测试入口。

### 重测触发条件与回滚

- 修改 n8n 白名单、Dify 插件/动作映射、Tavily 连接或执行器、市场包解析、批量导入事务、模板外部身份、更新确认或节点配置界面时，必须重跑 Backend、Frontend、Python Worker、Caddy 完整回归和 Compose 统一重建。
- 应用回滚可撤销功能提交 `da1b8ac0dccb53e10efe1a5e39a8a738da9d832e` 后执行 `docker compose up --build -d`；本次未新增数据库迁移或配置，可直接恢复旧应用行为。
- 回滚前若已确认更新旧 Tavily 模板，应先导出模板配置；撤销代码不会自动恢复被管理员确认替换的旧模板内容。

## 📋 n8n 与 Dify 节点市场导入测试结果（2026-08-09）

### Git 基准点

Commit: 499cea569b9acfb4e284ed1cbae007b986934e73
- 提交说明: Add marketplace node imports
- 测试日期: 2026-08-09
- 分支: master
- 上一测试报告基准点: `981e47af29914a9f6fb6d23853d04938f2e8cfa9`
- Backend 业务代码差异: 新增官方市场代理、白名单原生适配、Dify 包声明安全解析、幂等模板持久化、独立导入权限及节点模板外部身份字段，因此执行 Backend、Frontend、Python Worker、Caddy 完整回归和 Compose 统一重建。

### 变更范围

- 节点管理切换至 SYSTEM 时保留新增模板入口；切换至 N8N 或 DIFY 时展示对应官方市场导入入口。
- 市场弹窗支持搜索、分页、全量条目浏览、兼容性标识和“仅显示可导入”筛选；未适配条目可查看但不能选中导入。
- n8n 精确白名单将 PostgreSQL、MySQL、Redis、S3、Kafka Trigger 和 RabbitMQ Trigger 转换为 Base AI 现有原生节点类型。
- Dify 当前精确支持官方市场 `langgenius/tavily` 的 Search 与 Extract 工具，导入时重新下载官方插件包并只解析 manifest/provider/tool YAML 声明，不加载或执行包内 Python 源码。
- 市场目录和导入均由 Backend 代理；只允许 HTTPS 官方根域名，Dify 下载重定向限制为官方市场或官方 R2 存储，并设置超时、响应大小、压缩文件数和解压总量上限。
- 导入时服务端重新查询市场条目并重新执行白名单适配，不信任前端提交的名称、类型或配置；同一来源与外部 ID 幂等，已删除模板可恢复，新导入模板默认停用。
- 导入后仍使用现有系统节点卡片布局和 `WorkflowNodeConfigEditor` 原生配置编辑器；外部来源、模板编码和节点类型锁定，名称、说明、分类、配置和启用状态可继续维护。
- 新增 MySQL V11 迁移、`workflow:node:import` 权限、中英文提示和可配置市场请求安全上限；新增 SnakeYAML 直接依赖以使用受限 `SafeConstructor` 解析声明。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 来源切换展示对应操作 | Frontend 组件契约测试；用户具备节点新增或导入权限 | 切换 SYSTEM、N8N、DIFY | SYSTEM 展示新增模板，外部来源展示对应官方市场导入；通过 | 正常、权限、交互 |
| 全市场可浏览且仅白名单可导入 | Backend Service + Frontend 契约测试；市场同时返回 Redis 和 Slack | 浏览全部、启用仅兼容筛选、直接提交 Slack ID | 全部条目标记兼容状态；筛选按白名单先过滤再分页；Slack 不可选且服务端拒绝；通过 | 正常、边界、安全 |
| n8n 转换为原生节点模板 | Backend Service 测试；官方目录可查到白名单 ID | 导入受支持 n8n 节点 | 生成受控原生类型、稳定编码和默认配置，不保存第三方运行代码；通过 | 正常、兼容、安全 |
| Dify 包不执行且声明身份可信 | Backend 包解析与 Service 测试；内存 ZIP 含声明和恶意 Python 文件 | 导入 Tavily Search、构造路径穿越和超限解压包 | 只读取三类 YAML 并校验引用/工具名；Python 不执行；恶意包被拒绝；通过 | 正常、异常、恶意输入、安全 |
| 导入幂等且默认停用 | Backend H2 Service 测试；已登录管理员 | 连续两次导入同一 `source + external_key` | 首次 CREATED 且 disabled，第二次 ALREADY_IMPORTED 并返回原模板；通过 | 正常、状态冲突、副作用 |
| 通用创建接口不可伪造市场来源 | Backend H2 Service 测试 | 通过普通新增接口提交 DIFY 来源 | 服务端要求使用市场导入接口并拒绝请求；通过 | 权限、安全、异常 |
| 导入权限独立且返回外部身份 | Backend Controller 反射契约、Schema 与 Service 测试 | 检查浏览/导入注解和模板查询结果 | 浏览要求 list，导入要求 import；返回版本、发布者、指纹和导入时间；通过 | 权限、安全、兼容 |
| 导入卡片沿用原生配置方式 | Frontend 契约测试 | 打开已导入模板 | 使用统一节点卡片和原生配置编辑器，锁定外部身份字段；通过 | 交互、兼容、回归 |
| 数据迁移和部署可用 | Backend Schema 测试、真实 MySQL 8.0、Compose | 应用 V11 并统一构建启动 | Schema 当前为 v11，四服务 healthy，入口健康检查成功；通过 | 数据、构建、部署、回归 |

### 测试执行结果

- 当前正式测试入口及组件完整回归共 584 项，584 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：452/452 通过；市场 Service 4/4、包安全解析 3/3、Schema 8/8、模板持久化/访问 4/4、Controller 权限契约 4/4 均通过。
- Frontend 当前正式入口：88/88 通过；其中工作流模板目录定向测试 17/17 通过并包含新增 3 项导入契约。
- Python Worker：Python 3.12 一次性容器执行 28/28 通过。
- Caddy Go：16/16 通过，`go vet ./...` 通过。
- Backend 容器运行态接口：管理员登录与 `workflow:node:import` 权限验证通过；n8n 兼容目录返回 7 项、Dify 兼容目录返回 2 项，响应码均为 200；只读验证后已注销，未导入模板。
- 经用户确认后执行真实导入链路：Dify Tavily Search 首次导入为 CREATED 并生成模板 ID 37，重复导入为 ALREADY_IMPORTED；更新原生 HTTP 搜索参数后启用成功，来源、外部 ID 和节点类型保持不变；验证完成后恢复停用，避免缺少真实 Tavily Key 时被误用。
- 额外执行未纳入 `npm test` 的历史 `test/*.test.mjs`：168 项中 165 通过、3 失败。失败均来自既有 `api-trigger-security.test.mjs` 对当前工作流 CIDR 文本框及保存流程的旧源码断言；本次未修改对应页面，未计入本功能 584 项正式验收结果。
- Compose 生产构建：Backend 测试阶段再次执行 452 项并通过，Frontend Vite 构建成功；Backend、Frontend、Python Worker、Caddy 全部 healthy。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整回归 | `docker compose build backend`（Dockerfile 内 Maven 3.9.9 / Java 17 执行 `mvn -B -ntp package`） | 452/452 通过，BUILD SUCCESS |
| Frontend 定向回归 | `cd frontend && node --test tests/workflowTemplateCatalog.test.js` | 17/17 通过 |
| Frontend 正式完整回归 | `cd frontend && npm test` | 88/88 通过 |
| Frontend 历史补充入口 | `cd frontend && node --test test/*.test.mjs` | 165/168 通过；3 个既有接口触发安全页源码契约失败，与本次文件无差异 |
| Python Worker 完整回归 | Python 3.12 一次性容器安装哈希锁定开发依赖后执行 `python -m pytest -q -p no:cacheprovider` | 28/28 通过 |
| Caddy Go 完整回归 | Go 1.26.5 一次性容器执行 `go test -v ./...`、`go vet ./...` | 16/16 通过，vet 通过 |
| 官方市场契约烟测 | 限时请求 n8n `/api/nodes/search-filters` 与 Dify `/api/v1/plugins/search/advanced` | 两个官方接口均返回当前预期 JSON 结构，Dify 可查到 Tavily |
| 登录态市场接口烟测 | 在 Backend 容器内登录管理员并只读请求 N8N/DIFY 的 `compatibleOnly=true` 目录 | 登录和独立导入权限有效；N8N 返回 7 项、DIFY 返回 2 项，均为 200；随后注销且未写入模板 |
| 真实导入与配置链路 | 经用户确认后通过 Backend 接口导入 `langgenius/tavily/tavily_search`，重复导入，调整非敏感 HTTP 搜索参数并启用、查询、停用 | 模板 ID 37；CREATED → ALREADY_IMPORTED；启用回显通过且外部身份不可变；最终安全恢复为 disabled |
| 统一重建 | `docker compose up --build -d` | 首次 Caddy 因主机 80 端口占用失败；停止占用容器后重试成功，四服务 healthy |
| 数据库迁移 | Backend 启动 Flyway 日志 | MySQL Schema 当前为 v11，无待应用迁移 |
| 运行状态 | `docker compose ps`、HTTP health 与 API readiness | 四服务 healthy；两个探针请求均成功 |
| 差异检查 | `git diff --check`、`git diff --cached --check`、文件范围及未跟踪文件检查 | 通过；未修改接口触发安全页，无调试或临时文件 |

### 测试过程问题与处理

- 首次直接使用 Maven 官方容器下载既有依赖时出现 TLS `Tag mismatch`，未进入编译；改用项目 Dockerfile 配置的 Maven 镜像后完成完整测试，不影响代码。
- 初版 Dify ZIP 解析器存在 Java 模式变量作用域编译错误，修正收窄逻辑后继续测试。
- 初版 H2 模板夹具缺少新增列默认值，随后又因时间戳默认值导致 `GeneratedKeyHolder` 返回多列；将夹具调整为与生产插入行为一致后，持久化测试和完整 452 项均通过，未弱化业务断言。
- 提交前审查发现“仅显示可导入”在市场分页后过滤会产生空页和错误总数；改为按服务端白名单先生成兼容目录、再检索分页，并增加回归用例。
- 首次统一启动由 `domestic-trade-caddy` 占用 80/443；按项目规则停止该容器后重新执行，最终四服务全部健康。
- Caddy 一次性测试容器前两次因登录 shell PATH 和 `/tmp` 模块根限制未进入测试；改在容器临时工作目录执行后 16/16 通过，容器销毁时已清理临时模块。
- 最终状态复核时 Caddy 收到外部 SIGTERM 并以 0 正常退出，日志无崩溃或 OOM。Docker 事件确认另一工作区 `/Users/xyzc/gitee/domestic-trade` 正在后台执行 `docker compose up --build -d` 并停止本项目 Caddy 以争用 80/443；为避免干扰并行任务，后续登录态市场验证改在 Backend 容器内部完成。

### 已知问题与限制

- 当前 n8n 原生白名单仅覆盖 PostgreSQL、MySQL、Redis、AWS S3/S3、Kafka Trigger 和 RabbitMQ Trigger；Dify 仅覆盖 Tavily Search/Extract。其他条目可以浏览但会显示暂不支持。
- 市场依赖 n8n 与 Dify 当前官方 Web API，接口变化或外部服务不可用时会返回市场暂不可用；目录默认缓存 300 秒，不影响已导入模板运行。
- 导入模板默认停用，需要管理员补齐 Base AI 原生连接或 Tavily API Key 等配置后手动启用；第三方插件自身的凭据、运行时和自定义代码不会被导入。
- 未执行浏览器页面自动化 E2E；已通过真实登录态 Backend 接口完成导入、幂等、配置、启用、回显和安全停用链路，页面交互仍由 Frontend 契约及生产构建覆盖。
- 历史 Frontend 补充测试目录有 3 个既存旧断言失败：其假设接口触发安全页不存在任何 textarea 且只有旧自动保存流程，与当前已提交的工作流 CIDR 配置不一致；本次未修改无关测试或业务页。
- Frontend 构建仍有既有 runtime-config、第三方 PURE 注释和大 chunk 警告，不影响构建成功。
- 本机另一个工作区正在并行重建并争用 80/443，因此本项目 Caddy 可能被其外部 Compose 任务停止；Backend、Frontend 和 Python Worker 不受影响。需在两个项目之间明确端口归属后再恢复对应 Caddy。

### 下次测试建议

1. 增加登录态浏览器 E2E，覆盖来源切换、市场搜索/分页、不可导入状态、批量导入、默认停用和配置启用。
2. 使用可控的官方响应录制或契约测试服务，持续监测 n8n 与 Dify 市场字段、分页和下载重定向变化。
3. 扩展白名单前为每个第三方节点建立原生能力映射、凭据迁移规则、恶意包测试和运行集成测试，禁止直接执行插件代码。
4. 在后续独立任务中修正或迁移 `frontend/test/api-trigger-security.test.mjs` 的 3 个过期源码契约，并考虑统一两套 Frontend 测试入口。

### 重测触发条件与回滚

- 修改市场白名单、官方接口适配、包下载/解析安全限制、模板外部身份、导入权限、节点原生映射、V11 迁移或市场导入界面时，必须重跑 Backend、Frontend 完整回归和 Compose 统一重建。
- 应用回滚可撤销功能提交 `499cea569b9acfb4e284ed1cbae007b986934e73` 后执行 `docker compose up --build -d`；V11 新增列和唯一约束均为前向兼容结构，建议保留以免破坏审计信息。
- 如必须回退 V11 数据结构，应先停机、备份并确认没有 N8N/DIFY 导入模板依赖，再设计独立下行迁移；不得直接删除生产字段或导入数据。

## 📋 工作流安全与执行可靠性加固测试结果（2026-08-08）

### Git 基准点

Commit: 981e47af29914a9f6fb6d23853d04938f2e8cfa9
- 提交说明: Harden workflow security and execution
- 测试日期: 2026-08-08
- 分支: master
- 上一测试报告基准点: `4f7cad12590ae883af7be2601b8c1ce56fb6cc62`
- Backend 业务代码差异: 工作流资源级授权、连接网络策略、版本连接快照、执行状态机、负载/日志/步骤限制、Webhook 鉴权与限流均发生变化，因此执行 Backend、Frontend、Python Worker、Caddy 全量回归及 Compose 统一重建。

### 变更范围

- 工作流、模板、运行记录和连接按所有者隔离；API Key 还必须属于工作流所有者并显式绑定工作流，历史 Key 空白名单默认拒绝工作流访问，运行详情仅允许创建该运行的同一 Key 读取。
- 新增独立的工作流连接器 Host/CIDR 出站白名单，首次启动自动导入既有连接的精确目标；保存、测试、解析和执行连接前均校验协议、Host、端口及解析后的私网地址。
- 发布版本保存连接安全修订快照；连接敏感配置、类型或启用状态变化后，旧版本在重新发布前不可执行，连接删除改为精确引用校验。
- 执行线程池使用有界队列；队列拒绝写入失败终态；运行使用实例租约和心跳，多实例只回收过期任务；取消、成功、失败和等待恢复均使用条件状态更新，包含子工作流和 Agent 子运行。
- 图、嵌套节点、累计步骤、节点输入输出、运行日志、文档/S3 内容和 Webhook 请求体均增加资源上限；运行中连接资源登记到 Trace，可在取消时主动关闭。
- SQL 连接器限制可执行语句类别并拒绝只读 `WITH` 绕过及 DDL；Webhook 使用原始请求体 HMAC、强随机 Secret、事件 ID 校验、Redis 限流和投递记录清理；消息消费者使用有界线程池并校验连接修订。
- 新增 MySQL V10 增量迁移、环境变量、中英文提示、API Key 工作流多选和工作流连接器独立网络策略管理界面；未新增第三方依赖。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 工作流资源与 API Key 权限隔离 | Backend Service/H2/Controller 与 Frontend 契约测试 | 所有者、管理员、其他用户、同所有者不同 Key、空白名单及越权工作流 | 仅所有者/管理员或同所有者且显式授权的 Key 可访问；历史空白名单默认拒绝；通过 | 正常、权限、安全、兼容 |
| 连接器阻止 SSRF 与内网横移 | Backend 网络策略、初始化器和连接 Service 测试 | 精确 Host、公网地址、回环/私网地址、CIDR、非法协议、多个 Broker | Host 必须放行，受限地址还必须命中 CIDR；现有目标精确导入；通过 | 正常、边界、异常、安全 |
| 已发布版本绑定连接修订 | Backend 发布、连接与 Schema 测试 | 发布后修改连接配置、停用或删除被引用连接 | 修订不一致时拒绝触发/执行，精确引用阻止删除，重新发布生成新快照；通过 | 状态冲突、兼容、回归 |
| 取消终态不可被覆盖 | Backend 真实 H2 状态机测试 | CANCELLED 后迟到成功或失败，包含子运行终态写 | 条件更新返回失败，状态保持 CANCELLED；通过 | 并发、异常、副作用 |
| 队列、租约与等待恢复可靠 | Backend 真实 H2 状态机测试 | 初始队列拒绝、恢复队列拒绝、活跃与过期租约 | 拒绝后分别进入 FAILED/WAITING；仅过期租约被回收；通过 | 异常、超时、多实例、回归 |
| 资源限制不可通过嵌套或累计绕过 | Backend 图校验、执行日志、数据和连接执行器测试 | 嵌套图总节点、累计小日志、超限文档/S3、超长输入 | 按整个运行累计限制并在持久化或外部调用前拒绝；通过 | 边界、恶意输入、安全 |
| SQL 与 Webhook 入口加固 | Backend 执行器、Webhook Controller/限流/触发器测试 | `WITH`、DDL、超限 Body、弱 Secret、非法事件 ID、超频请求、错误签名 | SQL 类别、Body、Secret、事件 ID、限流和原始体 HMAC 均按规则拒绝；通过 | 异常、安全、兼容 |
| 管理界面支持新安全策略 | Frontend 完整契约测试 | 切换 Key 所有者、空工作流白名单、保存独立 Host/CIDR 策略 | 清理跨所有者授权、提示默认拒绝、使用独立策略接口并确认 ANY；通过 | 交互、权限、安全 |
| 全模块与部署无回归 | 四套完整测试和 Compose 环境 | 当前功能提交 | 570/570 通过；V10 在 MySQL 8.0 成功应用；四服务 healthy；通过 | 回归、构建、部署 |

### 测试执行结果

- 自动化测试总数：570 项，570 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：441/441 通过；Compose 构建阶段再次执行 441 项并通过，不重复计数。
- Frontend 完整回归：85/85 通过；新增工作流安全契约 3/3 通过。
- Python Worker：Python 3.12 容器执行 28/28 通过。
- Caddy Go：16/16 通过，gofmt 无差异，go vet 通过。
- 关键 Backend 覆盖：访问控制 5/5、执行状态机 6/6、网络策略及初始化 4/4、Webhook Body/限流 4/4、连接 Service 4/4、连接执行器 10/10、图校验 9/9、触发器 4/4、API Key 管理 Service 11/11。
- 运行态验证：真实 MySQL 8.0 从 V9 成功迁移到 V10；Backend、Frontend、Python Worker、Caddy 全部 healthy；HTTP health 与 HTTPS readiness 均成功。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 状态机定向回归 | Maven 3.9.9 / Java 17 容器执行 `WorkflowExecutionServiceStateTest` | 6/6 通过，BUILD SUCCESS |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn clean test -B -ntp` | 441/441 通过，BUILD SUCCESS |
| Frontend 完整回归 | `cd frontend && npm test -- --run` | 85/85 通过 |
| Python Worker 完整回归 | Python 3.12 一次性容器安装锁定开发依赖后执行 `python -m pytest -q` | 28/28 通过 |
| Caddy Go 完整回归 | Go 1.26.5 一次性容器执行 gofmt、`go test -v ./...`、`go vet ./...` | 16/16 通过，格式和 vet 通过 |
| 统一重建 | `docker compose up --build -d` | 镜像构建成功；构建内 Backend 441/441 通过；四服务 healthy |
| 数据库迁移 | Backend 启动 Flyway 日志 | 验证 11 个 MySQL 迁移，V10 成功应用，Schema 当前为 v10 |
| 运行状态 | `docker compose ps`、HTTP health、HTTPS readiness | 四服务 healthy；探针分别返回 OK 和 UP |
| 差异检查 | `git diff --check`、`git diff --cached --check`、提交前文件范围检查 | 通过，无调试或临时文件 |

### 测试过程问题与处理

- 首轮定向测试暴露新增构造参数及 H2 Fixture 缺少连接表，补齐正式测试前置条件后继续验证，未删除或弱化断言。
- 等待恢复最初使用 MySQL 多表更新，H2 状态机测试稳定失败；实现改为可移植的两步条件领取，并验证队列拒绝后回到 WAITING。
- 提交前安全回看发现子工作流和 Agent 子运行仍有无条件终态写；增加“取消后迟到失败”测试并改为条件更新，定向 6/6 和完整 441/441 均通过。
- 宿主 Python 3.12 未安装 pytest，改用 Python 3.12 一次性容器安装哈希锁定依赖并测试，未修改宿主环境或仓库文件。
- 首次统一启动因 `domestic-trade-caddy` 占用 80/443 端口失败；按项目规则停止该容器后重新执行，最终四服务全部 healthy。

### 已知问题与限制

- V10 上线后，历史 API Key 的工作流白名单为空，因此工作流接口默认拒绝；管理员必须按绑定用户显式授权工作流。
- 首次启动只自动信任当时存在连接的精确目标；本次运行数据库中没有既有工作流连接，初始化结果为 0 个 Host/CIDR。创建新连接前需先在“API 触发安全”页面配置工作流连接器策略。
- 修改连接类型、敏感配置或启用状态会增加安全修订号，引用旧修订的已发布工作流需重新发布后才能运行。
- 取消会关闭当前客户端资源并阻止迟到终态覆盖，但外部系统已经确认的数据库、消息或对象存储副作用无法跨系统事务自动回滚。
- 网络策略在每次连接前校验 DNS 返回的全部地址；管理员仍应只放行受信任域名，并避免高风险 ANY、CONTAINS 或过宽 CIDR 规则。
- 未执行浏览器 E2E；管理交互由 Frontend 契约测试、生产构建和运行态健康检查覆盖。
- `domestic-trade-caddy` 为释放本项目端口已停止，如需使用对应项目，应先释放本项目 80/443 后再启动。

### 下次测试建议

1. 增加真实 MySQL、Redis、Kafka、RabbitMQ 和 S3 测试容器，覆盖 DNS 变化、连接取消和网络策略的端到端行为。
2. 增加浏览器 E2E，验证 API Key 白名单、工作流网络策略 ANY 二次确认、跨所有者选项清理和错误提示。
3. 增加双 Backend 实例故障注入测试，验证进程强杀、租约到期、等待检查点恢复及同一任务不重复执行。
4. 增加 Webhook Redis 故障、并发限流和大请求慢速上传测试，并监控投递清理任务耗时。

### 重测触发条件与回滚

- 修改工作流所有权/API Key 授权、连接目标解析或网络策略、版本连接快照、运行状态机与租约、资源上限、Webhook/消息触发或 V10 相关实体时，必须重跑 Backend、Frontend、Python Worker、Caddy 完整回归及 Compose 统一重建。
- 应用回滚可撤销功能提交 `981e47af29914a9f6fb6d23853d04938f2e8cfa9` 后执行 `docker compose up --build -d`；V10 为加表加列的前向兼容迁移，建议保留数据库结构，避免破坏运行和审计数据。
- 如必须回退数据结构，应先停机并备份数据库，再确认没有新工作流授权、连接快照或运行记录依赖后制定单独迁移；不得直接删除 V10 表列。

## 📋 工作流模型路由功能编码下拉测试结果（2026-08-08）

### Git 基准点

Commit: 4f7cad12590ae883af7be2601b8c1ce56fb6cc62
- 提交说明: Use model route selector for workflow nodes
- 测试日期: 2026-08-08
- 分支: master
- 上一测试报告基准点: `182fce8c3b522dbcc6821251488e8436277017cd`
- Backend 业务代码差异: 新增工作流专用启用模型路由选项查询及只读接口，因此已执行 Backend、Frontend 完整回归和 Compose 统一重建。

### 变更范围

- AI 节点选择“模型路由”后，功能编码由自由文本改为可搜索、可清空的模型路由下拉，选项显示“路由名称（功能编码）”并继续保存原有 `featureCode` 字符串。
- 工作流专用接口只返回已启用模型路由的 ID、功能编码和名称，按功能编码排序，复用 `workflow:node:list` 权限且不返回供应商池等管理配置。
- 已有有效编码按服务端编码规范回显；失效或停用编码在选项成功加载后移除显式值并回退 `DEFAULT`，加载失败时保留原值。
- 未修改数据库结构、节点运行配置格式、发布校验、执行器、依赖或环境配置。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 功能编码使用模型路由下拉 | Frontend 配置定义与组件契约测试；AI 节点选择 `ROUTE` | 检查 LLM、Agent、问题分类和参数提取节点字段及编辑器 | 四类节点均使用可搜索、可清空的 `modelRoute` 选择器，不再自由输入；通过 | 正常、交互、回归 |
| 下拉只展示启用路由 | Backend Service 单元测试；仓库同时返回启用和停用路由 | 返回 `CHAT`、`DEFAULT` 和停用路由 | 仅返回启用项并按功能编码排序；通过 | 正常、边界、兼容 |
| 选项接口权限和数据最小化 | Backend Controller 契约与 Service 单元测试 | 使用节点查看权限访问 `/workflow/route-options` | 接口为只读 GET，要求 `workflow:node:list`，结果不含供应商池和敏感字段；通过 | 权限、安全 |
| 有效旧编码回显、失效编码回退 | Frontend 工具与组件契约测试 | 输入大小写不同的有效编码、未知编码和空值 | 有效值规范为服务端编码；未知或空值返回 `null` 并移除显式配置；通过 | 边界、异常、兼容 |
| 加载失败不误改配置 | Frontend 组件契约测试 | 路由选项接口失败 | 只展示失败提示，不标记加载完成且保留原功能编码；通过 | 异常、副作用 |
| 全模块无回归 | Backend 与 Frontend 完整套件 | 当前功能提交 | Backend 411/411、Frontend 80/80 通过 | 回归、兼容 |

### 测试执行结果

- 自动化测试总数：491 项，491 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：411/411 通过；其中 `LlmManagementServiceTest` 40/40、`WorkflowModelOptionsControllerTest` 3/3 通过。
- Frontend 完整回归：80/80 通过；其中工作流节点配置测试 26/26 通过。
- Backend 定向回归：路由 Service 与工作流选项 Controller 共 43/43 通过，包含在完整结果中，不重复计数。
- Compose 构建阶段再次执行 Backend 411 项测试并通过，Frontend 生产构建成功；Backend、Frontend、Python Worker、Caddy 均 healthy。
- 本轮未独立执行 Python Worker Pytest、Caddy Go 测试和浏览器 E2E，未计入自动化通过数。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 缺陷复现 | 新增路由下拉测试后执行 Frontend 定向测试 | 因缺少路由编码工具导出而失败，稳定复现实现缺口 |
| Frontend 完整回归 | `cd frontend && npm test` | 80/80 通过 |
| Frontend 定向回归 | `cd frontend && node --test tests/workflowNodeConfig.test.js` | 26/26 通过 |
| Backend 定向回归 | Maven 3.9.9 / Java 17 容器执行 `LlmManagementServiceTest`、`WorkflowModelOptionsControllerTest` | 43/43 通过，BUILD SUCCESS |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn test -B` | 411/411 通过，BUILD SUCCESS |
| 统一重建 | `docker compose up --build -d` | 首次因 80 端口被占用失败；停止占用容器后重试成功，四服务 healthy |
| 运行状态 | `docker compose ps`、`curl -fsS http://localhost/api/open/health/ready` | 四服务 healthy，就绪探针成功 |
| 差异检查 | `git diff --check`、提交前文件范围检查 | 通过 |

### 测试过程问题与处理

- 宿主机未安装 Maven，仓库也未提供 Maven Wrapper；使用 Maven 3.9.9 / Java 17 一次性容器执行 Backend 定向和完整测试，未跳过测试。
- 首次 Compose 启动 Caddy 时，`domestic-trade-caddy` 容器占用 80/443 端口；按项目规则停止该容器后重新执行 `docker compose up --build -d`，本项目四个服务全部健康。
- Backend 完整测试中的路由同步失败堆栈和依赖健康告警来自验证异常隔离的既有测试，最终断言、测试统计及 Maven 构建均成功。

### 已知问题与限制

- 下拉按数据库中的“启用”状态展示；已启用但尚未同步、无可用候选模型或健康检查失败的路由仍可能在运行时不可用。
- 选项接口失败时为避免网络故障误改历史配置会保留当前编码，用户需在接口恢复后重新确认选项。
- 本轮未执行浏览器 E2E，交互行为由配置工具单元测试、Vue 组件契约测试和生产构建覆盖。
- `domestic-trade-caddy` 为释放本项目端口已停止，如需使用对应项目需在本项目释放 80/443 后另行启动。

### 下次测试建议

1. 增加浏览器 E2E，验证节点模板和画布实例中切换 `ROUTE` 后下拉加载、搜索、清空、失效值回退及保存行为。
2. 后续若要求只展示实际可调用路由，可在选项接口增加已同步候选模型状态，并补充未同步、空供应商池和健康失败测试。
3. 增加接口集成测试，使用仅具备 `workflow:node:list` 的账号验证可访问选项，同时确认无 `model:route:list` 权限也不受影响。

### 重测触发条件与回滚

- 修改模型路由启用规则、工作流资源选项权限、功能编码配置格式或 AI 节点编辑器时，必须重跑 Backend、Frontend 完整回归和 Compose 统一重建。
- 回滚应用代码可撤销功能提交 `4f7cad12590ae883af7be2601b8c1ce56fb6cc62` 后执行 `docker compose up --build -d`；本次无数据库迁移或数据回填。

## 📋 工作流有效默认值必填校验测试结果（2026-08-08）

### Git 基准点

Commit: 182fce8c3b522dbcc6821251488e8436277017cd
- 提交说明: Use valid defaults for workflow requirements
- 测试日期: 2026-08-08
- 分支: master
- 上一测试报告基准点: `1446320e0b13d9c4483eed35082bca5f257cac41`
- Backend 业务代码差异: 新增工作流节点有效默认值归一化，并在发布校验和执行器中复用，因此已执行 Backend、Frontend 完整回归和 Compose 统一重建。

### 变更范围

- 仅将能独立满足业务规则的默认值视为已配置：模型路由的 `DEFAULT` 功能编码和 `text_model` 类型、HTTP GET、迭代集合、合并模式和空值集合、等待时长、默认分支、排序方向、聚合操作及消息 `null` 值等。
- 空占位默认值仍须用户填写，例如用户提示词、URL、条件左值、工作流编码、主题、连接、对象键和消息目的地。
- 前端缺失提示、后端发布校验和执行器实际配置均使用同一份默认化语义；默认值不会强制写回模板或画布 JSON。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 路由模型默认值不报缺失 | Frontend/Backend 单元测试 | 选择 `ROUTE`，仅提供用户提示词 | 自动使用 `DEFAULT` 和 `text_model`，允许发布；通过 | 正常、兼容 |
| HTTP 默认方法不报缺失 | Frontend/Backend 单元测试 | 仅提供 URL | 自动使用 GET，允许发布；通过 | 正常、兼容 |
| 有效结构化默认值可用 | Backend 校验器测试 | 空 MERGE、SET_VARIABLE 配置 | 自动使用 ARRAY/空集合或空对象，允许发布；通过 | 正常、边界 |
| 空占位仍被拒绝 | Backend 校验器测试 | 空 HTTP、无提示词 LLM | URL、提示词仍报告为缺失；通过 | 异常、边界 |
| 运行时与发布语义一致 | Backend 执行器回归 | 数据、连接和 AI 节点执行 | 执行前应用同一默认化规则；通过 | 回归、副作用 |
| 全模块无回归 | Backend 与 Frontend 完整套件 | 当前功能提交 | Backend 409/409、Frontend 72/72 通过 | 回归、兼容 |

### 测试执行结果

- Backend 完整回归：409/409 通过，失败 0，错误 0，跳过 0。
- Frontend 完整回归：72/72 通过，失败 0，错误 0，跳过 0。
- Backend 定向回归：节点校验器、数据节点、连接节点和 AI 客户端共 63/63 通过，包含在完整结果中，不重复计数。
- Compose 构建阶段再次执行 Backend 409 项测试并通过；Frontend 生产构建成功。
- Backend、Frontend、Python Worker、Caddy 均 healthy。
- 本轮未独立执行 Python Worker Pytest 与 Caddy Go 测试，未计入自动化通过数。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Frontend 定向与完整回归 | `cd frontend && npm test` | 72/72 通过 |
| Backend 定向回归 | Maven 3.9.9 / Java 17 容器执行节点校验器、执行器与 AI 客户端测试 | 63/63 通过，BUILD SUCCESS |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn test -B` | 409/409 通过，BUILD SUCCESS |
| 统一重建 | `docker compose up --build -d` 后执行 `docker compose up -d` 完成异步容器替换 | 镜像构建成功；四服务 healthy |
| 差异检查 | `git diff --check`、提交前文件范围检查 | 通过 |

### 测试过程问题与处理

- 宿主机未安装 Maven，使用 Maven 3.9.9 / Java 17 一次性容器执行 Backend 测试，未跳过测试。
- Compose 首次重建时旧前端容器仍在异步移除，第二次启动返回容器移除进行中；确认并重试 `docker compose up -d` 后全部服务健康，未发生端口或应用启动错误。
- 有一次从仓库根目录执行定向前端测试，因测试路径属于 frontend 子目录而未找到文件；随后使用正确工作目录执行定向及完整套件，均通过。

### 已知问题与限制

- 默认值仅在内存配置、校验和运行阶段生效，不会自动写入历史模板或工作流版本。
- 空字符串、空对象或 `null` 是否属于有效默认值按节点实际业务语义区分；不具备独立执行语义的占位值不会绕过必填校验。
- 本轮未独立执行 Python Worker 和 Caddy 测试套件，建议在涉及其代码或发布前补跑。

### 下次测试建议

1. 增加浏览器 E2E，验证默认值状态卡片不会显示缺失提示且不写回未编辑字段。
2. 为每个具有默认值的节点增加运行态集成测试，确认默认化配置与序列化版本兼容。
3. 后续扩展节点默认值时，必须同步更新前端规则、后端默认目录及参数化覆盖测试。

### 重测触发条件与回滚

- 修改节点默认值、必填规则、默认化顺序或执行器读取配置的方式时，必须重跑 Backend、Frontend 完整回归和 Compose 统一重建。
- 回滚应用代码可撤销功能提交 `182fce8c3b522dbcc6821251488e8436277017cd` 后执行 `docker compose up --build -d`；本次无数据库迁移或数据回填。

## 📋 工作流全节点方案与必填校验测试结果（2026-08-08）

### Git 基准点

Commit: 1446320e0b13d9c4483eed35082bca5f257cac41
- 提交说明: Enforce workflow node configuration modes
- 测试日期: 2026-08-08
- 分支: master
- 上一测试报告基准点: `8c216877ffd3d0695b0f4f3194748ed5dfaa4326`
- Backend 业务代码差异: 涵盖全部 36 种原生工作流节点的必填规则、运行前校验、AI 单模型类型推导及 Redis 外部连接前参数校验，因此已执行 Backend、Frontend 完整回归和 Compose 统一重建。

### 变更范围

- 为全部 36 种原生工作流节点建立显式的前后端必填规则，并以测试保证节点类型清单与校验策略一致。
- AI 节点必须选择“模型路由”或“指定模型”方案；路由模式要求功能编码和模型类型，指定模型模式要求模型 ID 且由后端推导模型类型；LLM 和 Agent 用户提示词必填。
- 等待、文档提取、S3 上传和 RabbitMQ 发布等多方案节点必须选择方案，并仅展示、校验当前方案关联字段。
- 邮件节点要求有效邮件路由和非空单行主题；邮件正文保持可选，省略时按空正文发送。
- 发布、手动运行、API、触发器、子工作流和 Agent 子工作流均会校验有效配置；LLM、AI、HTTP、连接与数据执行器在对应运行边界再次校验。
- 方案选定后的条件字段升级为不可关闭的必填字段，不再显示“启用该字段”开关。
- 未新增依赖、数据库迁移、配置文件或临时调试文件。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 所有原生节点均被校验 | Backend/Frontend 单元测试 | 遍历 36 种节点类型 | 前后端策略清单与节点类型清单完全一致；通过 | 回归、兼容 |
| AI 模型方案二选一 | Frontend/Backend 参数化测试 | `ROUTE`、`DIRECT`、缺失或非法方案 | 路由要求功能编码和模型类型；指定模型只要求模型 ID；通过 | 正常、边界、异常 |
| 选定方案后字段不可关闭 | Frontend 规则与组件契约测试 | 选择模型路由、指定模型、等待、文档和 RabbitMQ 方案 | 适用字段标为必填，编辑器不显示启用开关；通过 | 正常、交互、回归 |
| 全节点必传字段和嵌套结构 | Backend 参数化与单元测试 | 最小有效配置、条件、分支、工具、分类、连接和消息方案 | 36 种节点均可通过最小有效配置；缺失项被拒绝；通过 | 正常、边界、异常 |
| 邮件主题必填、正文可选 | Backend 校验器和连接执行器测试 | 空主题、正文缺失、空正文 | 空主题被拒绝；正文缺失仍调用受管邮件客户端；通过 | 正常、边界、副作用 |
| 运行阶段防御性校验 | Backend 客户端和执行器测试 | 解析后的配置、指定模型无模型类型、Redis 空参数 | 无效配置在调用前拒绝；指定模型推导模型类型；通过 | 异常、安全、副作用 |
| 全模块无回归 | Backend 与 Frontend 完整套件 | 功能提交当前代码 | Backend 408/408、Frontend 72/72 通过 | 回归、兼容 |
| 重建环境可运行 | Compose 环境 | `docker compose up --build -d` | Backend、Frontend、Python Worker、Caddy 均 healthy | 构建、部署 |

### 测试执行结果

- Backend 完整回归：408/408 通过，失败 0，错误 0，跳过 0。
- Frontend 完整回归：72/72 通过，失败 0，错误 0，跳过 0。
- Backend 定向回归：节点校验器、数据节点、连接节点和 AI 客户端共 62/62 通过，包含在 Backend 完整结果中，不重复计数。
- Compose 构建阶段再次执行 Backend 完整 408 项测试并通过；Frontend 生产构建成功。
- 四个 Compose 服务均为 healthy。
- 本轮未独立执行 Python Worker Pytest 与 Caddy Go 测试；两者均已随 Compose 成功构建和健康启动，但不计入自动化测试通过数。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Frontend 定向与完整回归 | `cd frontend && npm test` | 72/72 通过 |
| Backend 定向回归 | Maven 3.9.9 / Java 17 容器执行指定工作流与 AI 测试 | 62/62 通过，BUILD SUCCESS |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn test -B` | 408/408 通过，BUILD SUCCESS |
| 统一重建 | `docker compose up --build -d` | 镜像构建成功；构建内 Backend 408/408 通过，Frontend 生产构建成功 |
| 服务健康检查 | `docker compose ps` | Backend、Frontend、Python Worker、Caddy 均 healthy |
| 差异检查 | `git diff --check`、提交前文件范围检查 | 通过 |

### 测试过程问题与处理

- 宿主机未安装 Maven，使用 Maven 3.9.9 / Java 17 一次性容器执行 Backend 测试，未跳过测试。
- 首轮定向测试暴露 HTTP 方法新增为必填、Redis 参数应在连接前验证两项旧测试前置条件；已补齐 HTTP 方法并将 Redis 参数校验提前，业务断言保持不变。
- 曾在仓库根目录误执行 `npm test`，因根目录没有 package.json 而立即失败；随后在 frontend 目录执行完整套件并通过，该命令未运行任何测试或修改文件。
- Compose 构建存在既有 Vite runtime-config、第三方 PURE 注释和大分块非阻断警告；生产构建及健康检查均成功。

### 已知问题与限制

- 历史工作流缺少新增方案字段或必传字段时，将无法再次发布或运行，需在画布中补充配置。
- 模板可继续保存不完整的默认配置，以支持由工作流实例补齐；拦截发生在发布及所有运行入口。
- 本轮未执行 Python Worker 和 Caddy 的独立测试套件，建议在涉及其代码或发布前补跑。

### 下次测试建议

1. 增加浏览器 E2E，覆盖方案切换后字段自动变为必填、隐藏非适用字段和发布提示。
2. 增加真实已发布工作流的运行态测试，分别覆盖 API、Webhook、消息触发和子工作流入口的拦截结果。
3. 后续修改模型类型推导或 Worker 请求格式时，补充 Python Worker 协议回归测试。

### 重测触发条件与回滚

- 修改节点方案、必填规则、表达式解析、执行器调用前校验、模型直连类型推导或邮件主题规则时，必须重跑 Backend、Frontend 完整回归和 Compose 统一重建。
- 修改 Python Worker 或 Caddy 代码时，还应分别运行其独立测试套件。
- 回滚应用代码可撤销功能提交 `1446320e0b13d9c4483eed35082bca5f257cac41` 后执行 `docker compose up --build -d`；本次无数据库迁移或数据回填。

## 📋 工作流受管资源下拉选择测试结果（2026-08-08）

### Git 基准点

Commit: 8c216877ffd3d0695b0f4f3194748ed5dfaa4326
- 提交说明: Use selectors for workflow resources
- 测试日期: 2026-08-08
- 分支: master
- 上一测试报告基准点: `cf308192b35d6e3a5c638792f4bb17606a060c03`
- Backend 业务代码差异: `git diff cf308192b35d6e3a5c638792f4bb17606a060c03 8c216877ffd3d0695b0f4f3194748ed5dfaa4326 -- backend/src/main/java/` 包含工作流邮件路由和当前用户连接选项接口，因此已执行 Backend、Frontend、Python Worker、Caddy 完整回归及 Compose 统一重建。
- 基准点跨度说明: 上一基准点之后包含若干仅调整画布连线交互的前端提交；本次完整回归统一覆盖这些已提交变更和当前受管资源选择功能。

### 变更范围

- LLM、Agent、问题分类和参数提取节点的“指定模型”统一改为可搜索、可清空的模型选择器，不再手工输入 `modelId`。
- 邮件发送节点的 `routeId` 改为邮件路由选择器；仅展示启用、已配置收件人且绑定启用邮箱账户的可发送路由。
- Webhook 触发、即时通知、SQL、Redis、S3、Kafka 和 RabbitMQ 共九类节点的 `connectionId` 改为连接选择器，并按节点执行器允许的连接类型动态过滤。
- 新增受 `workflow:node:list` 权限保护的邮件路由和连接精简选项接口；邮件选项不返回账户、收件人或抄送人，连接选项不读取或返回加密配置。
- 连接选项仅包含当前用户拥有、启用且未作废的连接，管理员也不会获得其他所有者的节点选项，保持工作流所有权校验一致。
- 下拉仍保存数字 ID，工作流 JSON、数据库结构和节点执行格式保持不变；有效历史 ID 正常回显，失效或类型不兼容 ID 仅在选项成功加载后清除。
- 模型、邮件和连接列表加载失败时保留已有配置并显示中英文错误，不因外部依赖失败误清理节点配置。
- 未新增依赖、配置、数据库迁移、删除文件或临时调试代码。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 所有受管资源 ID 均使用选择器 | Frontend 节点定义与组件契约测试，加载全部原生节点 | 遍历 `modelId`、`routeId`、`connectionId` 字段 | 四类 AI、邮件及九类连接节点分别使用对应选择器，不存在资源 ID 数字编辑器；符合预期 | 正常、兼容、回归 |
| 模型按模型类型过滤 | Frontend 工具参数化测试 | 文本、视觉、扩展及未知模型类型 | 仅返回声明支持当前类型的启用模型，未知类型为空；符合预期 | 正常、边界、兼容 |
| 连接按节点类型过滤 | Frontend 工具参数化测试 | SQL、Redis、Webhook 及未知节点和混合连接 | SQL 支持 MySQL/PostgreSQL，其余节点仅展示运行时允许类型，未知节点为空；符合预期 | 正常、边界、安全 |
| 资源标签可识别且值保持数字 ID | Frontend 工具测试 | 输入资源名称、编码、类型和数字字符串 ID | 标签包含名称、编码或真实标识，合法 ID 规范为数字，非法和不存在 ID 返回 null；符合预期 | 正常、边界、数据副作用 |
| 邮件选项仅包含可发送路由 | Backend Service 单元测试 | 混合启用、停用、待配置路由及停用邮箱账户 | 仅返回启用且可发送路由，响应记录不包含收件人数据；符合预期 | 正常、异常、安全 |
| 连接选项遵守所有权和启用状态 | Backend H2 Service 测试 | 混合当前用户、其他用户、停用和作废连接，密文使用不可解密占位值 | 仅返回当前用户启用连接且查询无需解密配置；符合预期 | 权限、安全、越权、异常 |
| 精简接口权限受控 | Backend Controller 契约、AuthInterceptor 回归和运行态请求 | 检查三个选项接口注解并执行未登录访问 | 均要求 `workflow:node:list`，运行态全部返回统一 HTTP 401；符合预期 | 权限、安全、异常 |
| 选项加载失败不破坏旧配置 | Frontend 组件契约测试 | 模拟模型、邮件或连接接口失败 | 只设置错误状态，未标记加载完成且不触发旧 ID 清理；符合预期 | 依赖失败、异常、副作用 |
| 全部模块无回归 | Backend、Frontend、Python Worker、Caddy 完整套件 | 当前功能提交全部自动化测试 | 648/648 通过，失败 0、错误 0、跳过 0；符合预期 | 回归、兼容、安全 |
| 统一重建后服务可运行 | 正式 Compose 环境 | `docker compose up --build -d` | 四个镜像构建完成，Backend、Frontend、Worker、Caddy 全部 healthy；符合预期 | 构建、部署 |

### 测试执行结果

- 自动化测试合计：648/648 通过（Backend 367、Frontend 237、Python Worker 28、Caddy Go 16），失败 0，错误 0，跳过 0，通过率 100%。
- Backend 定向覆盖：邮件管理 Service 27、连接 Service 3、资源选项接口契约 2，共 32/32 通过；包含在 Backend 完整 367 项中，不重复计数。
- Frontend 定向覆盖：全部资源字段编辑器、模型/连接类型过滤、标签、合法/非法/失效 ID、按需加载及失败保护，共 19/19 通过；包含在 Frontend 完整 237 项中，不重复计数。
- Python Worker 使用 Python 3.12 容器执行 28 项测试，全部通过；只读挂载工作区并禁用字节码和 Pytest 缓存，未产生临时文件。
- Caddy 使用 Go 1.26.5 容器执行格式检查、16 项测试和 `go vet`，全部通过；Go module 临时文件仅存在于一次性容器中。
- Compose 构建期间 Backend 再次执行完整 367 项测试并通过；Frontend 生产构建转换 1749 个模块成功。
- 运行态四个 Compose 服务全部 healthy；Backend 就绪接口返回 `UP`，三个选项接口未登录访问均返回 HTTP 401。
- 静态检查：功能提交前 `git diff --check`、`git diff --cached --check` 通过，功能提交仅包含确认范围内的 11 个文件。

### 关键模块测试

- 工作流节点资源编辑器和类型映射：5/5 新增或调整用例通过，所在 Frontend 定向套件 19/19 通过。
- 邮件路由精简选项与敏感数据隔离：1/1 新增用例通过，所在 Service 套件 27/27 通过。
- 当前用户连接选项与无解密查询：1/1 新增用例通过，所在 Service 套件 3/3 通过。
- 工作流资源选项接口权限契约：2/2 通过。
- Backend 完整回归：367/367 通过。
- Frontend 完整回归：237/237 通过。
- Python Worker：28/28 通过。
- Caddy：16/16 通过，格式检查及 `go vet` 通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Frontend 缺陷复现 | `node --test tests/workflowNodeConfig.test.js` | 新资源函数尚不存在，测试按预期失败 |
| Frontend 定向测试 | `node --test tests/workflowNodeConfig.test.js` | 19/19 通过 |
| Backend 定向测试 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp -Dtest=MailManagementServiceTest,WorkflowConnectionServiceTest,WorkflowModelOptionsControllerTest test` | 32/32 通过，BUILD SUCCESS |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 367/367 通过，BUILD SUCCESS |
| Frontend 完整回归 | `node --test test/*.test.mjs tests/*.test.js` | 237/237 通过 |
| Python Worker 完整回归 | Python 3.12 容器按哈希安装开发依赖后执行 `python -m pytest -q -p no:cacheprovider` | 28/28 通过 |
| Caddy Go 回归 | Go 1.26.5 一次性容器执行 `gofmt` 检查、`go test ./...`、`go vet ./...` | 16/16 通过 |
| 统一重建 | `docker compose up --build -d` | 命令退出码 0，四服务全部 healthy |
| 运行态就绪检查 | Backend 容器请求 `/api/open/health/ready` | 返回 `{"status":"UP"}` |
| 未登录权限检查 | GET `/api/workflow/model-options`、`/mail-route-options`、`/connection-options` | 均返回 HTTP 401 和统一未登录响应 |
| 差异检查 | `git diff --check`、`git diff --cached --check`、提交文件范围和工作区状态检查 | 均通过 |

### 测试过程问题与处理

- 首次 Frontend 缺陷复现因计划新增的资源兼容函数尚不存在而失败，确认测试能够稳定阻止资源 ID 回退为数字输入；实现后同一套测试 19/19 通过。
- 宿主机未安装 Maven，按项目既有方式使用 Maven 3.9.9 / Java 17 容器执行定向和完整测试，未跳过任何 Backend 测试。
- 首次 Backend 定向测试的新增断言误用 Java 21 `List.getFirst()`，在 Java 17 测试编译阶段失败；改为等价的 `get(0)` 后定向 32/32 和完整 367/367 通过，业务断言未删除或弱化。
- 运行态权限检查首次使用 Zsh 保留变量 `path` 作为循环变量，覆盖 `PATH` 后导致循环内命令无法找到；改用 `endpoint` 变量后三个接口均验证为 401，并清除了 Backend 容器内的临时响应文件。
- Compose 构建保留既有 Vite runtime-config、第三方 PURE 注释和大分块非阻断警告；生产构建和健康检查成功。
- Python 测试容器出现 root 用户安装依赖提示；容器为一次性环境且工作区只读，测试正常完成，无缓存或调试文件残留。

### 已知问题与限制

- 资源选择器仅展示当前可用资源；历史配置引用停用、删除、其他所有者或类型不兼容资源时，会在列表成功加载后从当前编辑副本清除，只有用户保存模板或画布后才持久化。
- 连接选项始终限定当前用户所有权；其他用户复用包含连接默认值的模板时，需要在画布中改选自己的同类型连接，这与现有保存校验一致。
- 邮件选项过滤停用或待配置路由，但节点执行器的历史数字 ID 兼容逻辑未改变；本次不修改既有工作流版本或执行语义。
- 自动化测试覆盖资源筛选、数据最小化、权限声明和未登录运行态响应，尚未增加带真实登录用户的浏览器端到端选择测试。

### 下次测试建议

1. 增加浏览器 E2E，覆盖三类资源卡片展开、搜索、清空、节点类型切换和接口失败提示。
2. 增加分别拥有和不拥有连接的运行态测试账号，直接验证连接选项接口的 200、空列表和跨用户隔离。
3. 后续若将子工作流编码等其他受管引用也改为选择器，应复用精简选项接口模式并验证循环引用和发布状态过滤。

### 重测触发条件与回滚

- 修改资源选项返回字段、启用/所有权过滤、节点与连接类型映射、资源 ID 清理语义或接口权限时，必须重跑 Frontend、Backend 完整回归和 Compose 统一重建。
- 修改 Worker 请求格式或 Caddy 入口行为时，还需分别重跑 Python Worker 和 Caddy Go 套件。
- 回滚应用代码可撤销功能提交 `8c216877ffd3d0695b0f4f3194748ed5dfaa4326` 后执行 `docker compose up --build -d`；本次无数据库迁移或数据回填。

## 📋 Agent 指定模型下拉选择测试结果（2026-08-08）

### Git 基准点

Commit: cf308192b35d6e3a5c638792f4bb17606a060c03
- 提交说明: Add Agent model selector
- 测试日期: 2026-08-08
- 分支: master
- 上一测试报告基准点: `405db69163389d643f67747641a3563e18caa22f`
- Backend 业务代码差异: `git diff 405db69163389d643f67747641a3563e18caa22f cf308192b35d6e3a5c638792f4bb17606a060c03 -- backend/src/main/java/` 包含工作流可选模型查询接口，因此已执行 Backend、Frontend、Python Worker、Caddy 完整回归及 Compose 统一重建。

### 变更范围

- Agent 节点的“指定模型”由数字输入改为可搜索、可清空的下拉选择；LLM、问题分类器、参数提取器等其他节点保持原有编辑器行为。
- 新增受 `workflow:node:list` 权限保护的只读模型选项接口，仅返回模型 ID、名称、真实模型标识、供应商名称和支持类型，不返回 API Key、健康错误等敏感字段。
- 下拉仅包含启用供应商下的启用模型，并按 Agent 当前模型类型动态过滤；切换类型或加载后发现旧模型不兼容时移除 `modelId`，回退到能力路由。
- 模型列表加载失败时保留已有配置并展示中英文错误提示；清空选择会删除可选字段，不写入无效 ID。
- 未新增依赖、配置、数据库迁移或持久化格式变更。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| Agent 不再手工输入模型 ID | Frontend 组件契约测试，Agent 配置可展开 | 打开 `modelId` 字段 | 使用可搜索、可清空下拉，无数字输入框；符合预期 | 正常、兼容 |
| 下拉显示可识别模型信息 | Frontend 工具参数化测试 | 输入模型名称、真实标识和供应商 | 标签为“名称（真实标识）· 供应商”，选择值保持数字 ID；符合预期 | 正常、数据副作用 |
| 模型类型动态过滤 | Frontend 工具参数化测试 | 文本、视觉、扩展类型及未知类型 | 仅保留声明支持当前类型的模型，未知类型返回空集合；符合预期 | 正常、边界、兼容 |
| 不兼容或非法旧值自动清理 | Frontend 工具与组件契约测试 | 切换类型，或传入空值、非法 ID | 不兼容值返回 null 并删除 `modelId`，继续使用能力路由；符合预期 | 边界、异常、副作用 |
| 只提供真实可用模型 | Backend Service 单元测试 | 混合启用/停用供应商和模型 | 仅返回启用供应商下的启用模型；符合预期 | 正常、异常、回归 |
| 接口不泄漏敏感配置 | Backend Service 单元测试 | 查询工作流模型选项 | 仅返回五个下拉字段且加密服务无交互；符合预期 | 权限、安全 |
| 接口权限保持受控 | Backend Controller 契约、AuthInterceptor 完整回归、运行态请求 | 检查注解；未登录请求新接口 | 要求 `workflow:node:list`，全局 401/403 语义通过，运行态未登录返回 401；符合预期 | 权限、安全、异常 |
| 其他模块无回归 | Backend、Frontend、Worker、Caddy 完整套件 | 当前功能提交全部自动化测试 | 641/641 通过，失败 0、错误 0、跳过 0；符合预期 | 回归、兼容 |
| 统一重建后服务可运行 | 正式 Compose 环境 | `docker compose up --build -d` | 四个镜像构建完成，Backend、Frontend、Worker、Caddy 全部 healthy；符合预期 | 构建、部署 |

### 测试执行结果

- 自动化测试合计：641/641 通过（Backend 364、Frontend 233、Python Worker 28、Caddy Go 16），失败 0，错误 0，跳过 0，通过率 100%。
- Backend 定向覆盖：包含模型选项筛选与敏感字段隔离新增用例的 LLM 管理 Service 套件 39 项、接口权限契约 1 项，共 40/40 通过；包含在 Backend 完整 364 项中，不重复计数。
- Frontend 定向覆盖：Agent 专用编辑器、类型过滤、动态扩展类型、标签、空值、非法值和类型切换，共 15/15 通过；包含在 Frontend 完整 233 项中，不重复计数。
- Python Worker 使用 Python 3.12 执行 28 项测试，全部通过。
- Caddy Go 执行格式检查、16 项测试和 `go vet`，全部通过。
- 运行态：四个 Compose 服务全部 healthy；Backend 就绪接口返回 `UP`；未登录访问模型选项接口返回 HTTP 401。
- 静态检查：`git diff --check` 通过，功能提交仅包含确认范围内的 9 个文件。

### 关键模块测试

- LLM 模型选项筛选和敏感字段隔离：1/1 新增用例通过，所在 Service 套件 39/39 通过。
- Workflow 模型选项接口权限契约：1/1 通过。
- Agent 配置编辑与模型类型兼容：2/2 新增用例通过，所在工作流配置套件 15/15 通过。
- Backend 完整回归：364/364 通过。
- Frontend 完整回归：233/233 通过。
- Python Worker：28/28 通过。
- Caddy：16/16 通过，格式检查及 `go vet` 通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Frontend 定向测试 | `node --test tests/workflowNodeConfig.test.js` | 15/15 通过 |
| Backend 定向测试 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp -Dtest=LlmManagementServiceTest,WorkflowModelOptionsControllerTest test` | 40/40 通过，BUILD SUCCESS |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 364/364 通过，BUILD SUCCESS |
| Frontend 完整回归 | `node --test test/*.test.mjs tests/*.test.js` | 233/233 通过 |
| Python Worker 完整回归 | Python 3.12 容器按哈希安装开发依赖后执行 `python -m pytest -q -p no:cacheprovider` | 28/28 通过 |
| Caddy Go 回归 | Go 1.26.5 容器执行 `gofmt`、`go test ./...`、`go vet ./...` | 16/16 通过 |
| 统一重建 | `docker compose up --build -d` | 命令退出码 0，四服务全部 healthy |
| 运行态就绪检查 | `/api/open/health/ready` | 返回 `{"status":"UP"}` |
| 未登录权限检查 | GET `/api/workflow/model-options` | 返回 HTTP 401 和统一未登录响应 |
| 差异检查 | `git diff --check`、提交文件范围和工作区状态检查 | 均通过 |

### 测试过程问题与处理

- 第一次 Backend 定向测试使用独立 Maven 缓存时，Maven Central 下载 Spring AMQP 依赖出现一次 TLS `bad_record_mac`，测试尚未进入编译阶段；切换到项目此前完整回归使用的宿主 Maven 缓存后，定向 40/40 和完整 364/364 均通过。
- 工作期间另一项已确认的前端默认值状态改动先独立提交；本功能基于该提交继续开发，最终功能提交差异未混入其他未提交内容。
- Python 测试容器出现 root 用户安装依赖的非阻断提示；容器为一次性环境，测试正常完成且未写入工作区缓存。

### 已知问题与限制

- 本次仅调整 Agent 节点；其他包含 `modelId` 的 AI 节点仍使用现有数字编辑器，符合确认范围。
- 模型选项只包含当前启用供应商下的启用模型；已停用或删除的历史模型 ID 在列表加载成功后会被清理并回退能力路由。
- 当前自动化测试覆盖数据逻辑、组件契约、权限声明与运行态未登录响应，尚未增加真实浏览器端到端点击测试。

### 下次测试建议

1. 增加浏览器 E2E，验证 Agent 卡片展开、模型搜索、清空、类型切换和错误提示的真实交互。
2. 后续若将模型下拉推广到 LLM、问题分类器或参数提取器，应复用同一选项接口并分别验证默认路由语义。
3. 增加带 `workflow:node:list` 和不带该权限的运行态集成账号，直接覆盖新接口的 200/403 响应。

## 📋 节点模板必填提示与发布校验测试结果（2026-08-08）

### Git 基准点

Commit: 405db69163389d643f67747641a3563e18caa22f
- 提交说明: Validate required workflow node configuration
- 测试日期: 2026-08-08
- 分支: master
- 上一测试报告基准点: `64e1e7279ae0a0d2fe92eb19520b573baf978643`
- Backend 业务代码差异: `git diff 64e1e7279ae0a0d2fe92eb19520b573baf978643 405db69163389d643f67747641a3563e18caa22f -- backend/src/main/java/` 包含工作流节点发布前必填配置校验，因此已执行 Backend、Frontend、Python Worker、Caddy 完整回归及 Compose 统一重建。
- 基准点跨度说明: 上一报告后还包含画布显示、节点筛选及连接配置卡片等已提交变更；本次完整回归以当前功能提交为统一验收基准。

### 变更范围

- 节点模板和画布实例的标准字段卡片取消“启用该字段”开关；展开卡片不会修改数据，第一次编辑才写入配置对象，已配置字段可主动清除。
- 模板基础信息明确标记编码、名称、节点类型、节点来源和功能类型为必填项；说明、状态和默认配置保持可选。
- 原生节点字段区分必填、条件必填和可选，模板允许暂时缺少运行参数，并在中英文界面汇总提示发布前需补充的字段。
- 工作流发布前校验当前保存版本，合并不可变模板快照和实例覆盖，并递归检查迭代、循环子画布；任一节点缺少必填配置时汇总错误且不更新发布状态、版本或 revision。
- 条件必填覆盖文档内容或 Base64、S3 非 LIST 操作的对象键、RabbitMQ 交换机或路由键、Redis 命令最少参数数等场景。
- LLM 模型路由、等待时长、超时和重试策略等具备运行默认值的参数不误标为必填。
- 未新增依赖、配置、数据库迁移或临时调试文件。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 未配置字段无需显式启用 | Frontend 工具与组件契约测试 | 展开未配置标准字段并检查编辑、清除入口 | 展开不写入；第一次修改自动写入；无启用开关；符合预期 | 正常、边界、副作用 |
| 模板基础必填项明确 | Frontend 组件与中英文资源测试 | 打开新增或编辑模板弹窗 | 编码、名称、类型、来源、功能类型显示必填提示，默认配置允许留空；符合预期 | 正常、兼容 |
| 节点运行必填字段提前提示 | Frontend 参数化规则测试 | HTTP、SQL、迭代、文档、S3、Redis、RabbitMQ 等配置 | 固定必填、组合必填和操作条件均返回对应提示；符合预期 | 正常、边界、异常 |
| 运行默认值不误报 | Frontend 与 Backend 单元测试 | START、END、LLM、WAIT 空配置 | 不产生必填错误；符合预期 | 兼容、回归 |
| 模板与实例共同补齐配置 | Backend 单元测试 | SQL 的连接来自模板快照、查询来自实例覆盖 | 合并后的有效配置通过发布校验；符合预期 | 正常、兼容 |
| 缺少配置阻止发布 | Backend H2 Service 集成测试 | 当前版本 HTTP 节点缺少 URL | 返回稳定业务错误，状态保持 DRAFT，publishedVersionId 为空且 revision 不变；符合预期 | 异常、数据副作用 |
| 配置完整允许发布 | Backend H2 Service 集成测试 | HTTP URL 由模板快照提供 | 状态更新为 PUBLISHED，发布版本指向当前版本，revision 原子递增；符合预期 | 正常、数据副作用 |
| 嵌套节点统一校验 | Backend 单元测试 | 迭代子画布 SQL 节点缺少查询 | 主图与子图错误一次汇总返回；符合预期 | 边界、异常、回归 |
| 全部模块无回归 | Backend、Frontend、Worker、Caddy 完整套件 | 当前功能提交全部自动化测试 | 632/632 通过，失败 0、错误 0、跳过 0；符合预期 | 回归、安全、兼容 |
| 统一重建后服务可运行 | 正式 Compose 环境 | `docker compose up --build -d` | 四个镜像构建完成，Backend、Frontend、Worker、Caddy 全部 healthy；符合预期 | 构建、部署 |

### 测试执行结果

- 自动化测试合计：632/632 通过（Backend 362、Frontend 226、Python Worker 28、Caddy Go 16），失败 0，错误 0，跳过 0，通过率 100%。
- Backend 定向覆盖：节点配置校验 4、发布原子性 2、模板快照触发器兼容 1，共 7/7 通过；包含在 Backend 完整 362 项中，不重复计数。
- Frontend 定向覆盖：节点字段启用语义、必填级别、组合条件、模板基础必填和中英文提示，共同纳入 22/22 工作流相关定向测试；包含在 Frontend 完整 226 项中，不重复计数。
- 生产构建：Vite 1749 个模块转换成功；仅保留既有 runtime-config、第三方 PURE 注释和大分块警告，没有构建失败。
- 运行态：四个 Compose 服务全部 healthy；Backend 开放就绪接口返回 `UP`。
- 静态检查：`git diff --check` 和 `git diff --cached --check` 均通过。

### 关键模块测试

- Workflow Service 与发布事务：2/2 通过。
- Workflow 节点配置校验：4/4 通过。
- Workflow 模板快照兼容：1/1 通过。
- Frontend 节点配置与模板管理：22/22 定向测试通过。
- Backend 完整回归：362/362 通过。
- Frontend 完整回归：226/226 通过。
- Python Worker：28/28 通过（Python 3.12）。
- Caddy：16/16 通过，`go vet` 通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 定向测试 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp -Dtest=WorkflowNodeConfigValidatorTest,WorkflowServicePublishTest,WorkflowServiceTriggerDefinitionTest test` | 7/7 通过，BUILD SUCCESS |
| Frontend 定向测试 | `node --test tests/workflowNodeConfig.test.js tests/workflowTemplateCatalog.test.js` | 22/22 通过 |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 362/362 通过，BUILD SUCCESS |
| Frontend 完整回归 | `node --test test/*.test.mjs tests/*.test.js` | 226/226 通过 |
| Python Worker 完整回归 | Python 3.12 容器按哈希安装开发依赖后执行 `python -m pytest -q -p no:cacheprovider` | 28/28 通过 |
| Caddy Go 回归 | Go 1.26.5 容器执行 `gofmt`、`go test ./...`、`go vet ./...` | 16/16 通过 |
| 统一重建 | `docker compose up --build -d` | 命令退出码 0，四服务全部 healthy |
| 运行态就绪检查 | Backend 容器请求 `/api/open/health/ready` | 返回 `{"status":"UP"}` |
| 差异检查 | `git diff --check`、`git diff --cached --check`、工作区状态检查 | 均通过 |

### 测试过程问题与处理

- 宿主机未安装 Maven，按项目允许方式改用固定 Maven 3.9.9 / Java 17 容器执行定向和完整测试；未跳过任何 Backend 测试。
- 第一次 Frontend 定向运行有 2 个新增源码契约正则过窄，实际组件结构正确；修正测试表达式后定向 22/22、完整 226/226 通过，没有弱化业务断言。
- Compose 构建期间的 Vite runtime-config、第三方 PURE 注释和 chunk 大小均为既有非阻断警告；构建和健康检查成功。

### 已知问题与限制

- 模板默认配置允许不完整，这是本次确认的复用策略；只有保存后的当前工作流版本在发布时执行强校验。
- 必填规则覆盖平台原生节点标准字段；附加自定义参数没有通用 Schema，不能自动判断业务必填性。
- 条件必填提示根据当前操作或命令计算；修改 S3 操作、Redis 命令等字段后，提示会即时重新计算。
- 发布接口校验当前已保存版本，不会隐式保存浏览器中尚未提交的画布修改，保持现有版本语义。

### 下次测试建议

1. 增加浏览器 E2E，验证真实点击字段卡片、首次输入、清除配置及必填警告的视觉状态。
2. 为发布错误增加前端节点定位能力，使用户可从汇总错误直接选中对应画布节点。
3. 若未来允许自定义节点 Schema，应把必填规则纳入模板元数据，并由前后端共同消费同一版本化定义。

### 重测触发条件与回滚

- 修改节点字段定义、必填/条件必填规则、模板快照合并、子画布结构或发布事务时，必须重跑 Backend、Frontend 完整回归和 Compose 统一重建。
- 回滚应用代码可回退功能提交 `405db69163389d643f67747641a3563e18caa22f` 后重新执行 `docker compose up --build -d`；本次无数据库迁移或数据回填。
- 如仅调整展示文案，仍需执行 Frontend 定向测试；若改变发布规则，必须同步更新前后端规则测试，避免提示和服务端校验漂移。

---

## 📋 画布右键节点目录与三来源配置测试结果（2026-08-08）

### Git 基准点

Commit: 64e1e7279ae0a0d2fe92eb19520b573baf978643
- 提交说明: Support configurable workflow node sources
- 测试日期: 2026-08-08
- 分支: master
- 上一测试报告基准点: `5f0aaa6009275c417cafef1ad6066f2d75c5011e`
- Backend 业务代码差异: `git diff 5f0aaa6009275c417cafef1ad6066f2d75c5011e 64e1e7279ae0a0d2fe92eb19520b573baf978643 -- backend/src/main/java/` 包含模板来源枚举、创建和更新校验及兼容逻辑，因此已执行 Backend 完整回归、Frontend 完整回归、统一重建和真实 MySQL 运行态验收。

### 变更范围

- 画布移除固定节点模板侧栏，改为空白区域右键弹出节点目录；先选择十类功能分类，再单击节点并在原始右键坐标创建实例。
- 节点目录仅展示启用模板，每个节点明确显示单一来源标签：系统、n8n 或 Dify；菜单支持点击外部、Escape 和窗口尺寸变化时关闭，并限制在可视区域内。
- 撤销和重做改为画布内悬浮操作；节点属性、连线、子画布、模板配置深复制和历史快照行为保持不变。
- 节点管理按十类功能分类展示，管理员可以通过受控下拉框调整模板来源及功能分类；系统模板仍保持编码、类型和删除保护。
- Backend 对 `SYSTEM`、`N8N`、`DIFY` 执行枚举校验；创建时旧客户端缺省来源兼容为系统，更新时缺省字段保留管理员已有来源和分类，避免旧客户端覆盖元数据。
- 新增 MySQL V9 向前迁移。由于已执行的 V8 曾将来源规范为 `SYSTEM/CUSTOM`，V9 将未知或 `CUSTOM` 来源安全回填为 `SYSTEM`，恢复数据库默认来源为 `SYSTEM`，不删除模板或历史版本。
- 本次来源仅为目录元数据，不导入或执行 n8n/Dify 外部节点，也不改变节点执行语义和画布保存格式。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 固定模板栏被右键目录替代 | Frontend 源码契约、完整回归和生产构建 | 检查 `pane-context-menu`、模板栏和拖放入口 | 固定模板栏及旧拖放数据入口不存在；右键分类菜单存在；符合预期 | 正常、兼容、回归 |
| 节点按十类功能展示 | Frontend 目录工具参数化测试 | 全部原生节点、停用模板、乱序模板 | 36 类原生节点均落入受控分类；分组顺序稳定且停用模板不进入画布菜单；符合预期 | 正常、边界 |
| 单击在右键坐标创建节点 | Frontend 画布契约和生产构建 | 触发空白画布右键并选择模板 | 使用 `screenToFlowCoordinate` 保存原始坐标，单击后创建并关闭菜单；符合预期 | 正常、副作用 |
| 来源可配置为系统、n8n、Dify | Backend 单元、Frontend 单元和真实 API | 对 HTTP 模板依次 PUT `N8N`、`DIFY`，最后恢复 `SYSTEM` | 三种来源均持久化并原样返回；恢复后无临时数据残留；符合预期 | 正常、数据副作用 |
| 非法来源和分类被拒绝 | Backend 单元测试 | `external` 来源、`other` 分类 | 分别返回稳定业务错误键，不写入任意枚举；符合预期 | 异常、安全 |
| 旧客户端更新不覆盖目录元数据 | Backend 单元测试 | 已有 n8n 来源或手工分类，更新命令省略新字段 | 保留已有来源和分类；创建缺省仍回退系统及节点默认分类；符合预期 | 兼容、回归 |
| 已执行 V8 的数据库可安全升级 | Migration 资源测试、Compose 启动和真实 API | 应用 V9，读取 36 个历史模板 | V9 成功，36 个模板保留，覆盖十类且历史来源为系统；符合预期 | 迁移、兼容、数据 |
| 现有功能不回归 | Backend、Frontend、Worker、Caddy 完整套件 | 当前功能提交全部自动化测试 | 609/609 通过，失败 0、错误 0、跳过 0；符合预期 | 回归、安全 |
| 统一重建后服务可运行 | 正式 Compose 环境 | `docker compose up --build -d` | 四个镜像构建完成，Backend、Frontend、Worker、Caddy 全部 healthy | 构建、部署 |

### 测试执行结果

- 自动化测试合计：609/609 通过（Backend 356、Frontend 209、Python Worker 28、Caddy Go 16），失败 0，错误 0，跳过 0。
- Backend 目录定向覆盖：模板目录 3、Schema 6；均包含在 Backend 完整 356 项中，不重复计数。
- Frontend 目录定向覆盖：功能分类、三来源、停用过滤、右键菜单、坐标添加、节点管理配置和悬浮历史操作；均包含在 Frontend 完整 209 项中。
- 生产构建：Vite 1748 个模块转换成功；仅保留既有运行时配置脚本、第三方 PURE 注释和大分块提示，没有构建失败。
- 静态检查：`docker compose config --quiet`、`git diff --check`、`git diff --cached --check` 均通过。
- 运行态：四个 Compose 服务全部 healthy；开放健康接口返回 `UP`；HTTP 模板的 SYSTEM、N8N、DIFY 更新、读取和最终恢复均成功。
- 清理：运行态验证只临时修改一个系统模板来源并恢复为 SYSTEM；临时 Cookie 使用 `/tmp/base-ai-workflow-catalog-cookie.*`，退出时已删除，无调试文件和临时模板残留。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整回归 | Maven 3.9.9 / Java 17 Docker 构建执行 `mvn -B -ntp package` | 356/356 通过，BUILD SUCCESS |
| Frontend 完整回归 | `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` | 209/209 通过 |
| Frontend 生产构建 | `npm run build` 及 Compose Frontend 构建 | 构建成功 |
| Python Worker 完整回归 | Python 3.12 固定容器按哈希安装开发依赖后执行 `python -m pytest -q -p no:cacheprovider` | 28/28 通过 |
| Caddy Go 回归 | Go 1.26.5 固定容器执行 `gofmt`、`go test`、`go vet` | 16/16 通过，格式和 vet 通过 |
| 统一重建 | `docker compose up --build -d` | 命令退出码 0，四服务全部 healthy |
| 模板来源运行态 | Cookie 会话和双提交 CSRF 调用节点模板 GET/PUT | SYSTEM、N8N、DIFY 均保存和返回正确，最后恢复 SYSTEM |
| Compose 与差异检查 | `docker compose config --quiet`、`git diff --check`、工作区检查 | 全部通过 |

### 测试过程问题与处理

- 共享工作区中的上一任务曾把模板来源规范为 `SYSTEM/CUSTOM` 并执行 V8。为避免改写已执行迁移，本次新增 V9 向前兼容迁移，不删除或重命名 V8。
- 第一次运行态登录尝试直接 `source` 本地环境文件，因合法特殊字符无法作为 Shell 脚本解析而未认证；改为从运行容器安全读取已注入环境值，凭证未输出。
- 第一次模板 PUT 未携带双提交 CSRF Header，被安全层以 403 正确拒绝且没有数据变更；补充 CSRF Cookie 对应 Header 后三种来源验证成功，未绕过安全机制。
- 一次暂存快照隔离测试只打包 Frontend，根级 Compose/Caddy 契约因依赖文件缺失而未进入有效验证；随后在完整工作区重新执行 209/209，通过结果作为正式记录。

### 已知问题与限制

- n8n 和 Dify 当前是单一来源元数据，不代表自动兼容或导入对应产品的节点实现。
- 历史 36 个模板按确认规则全部标记为系统来源；n8n、Dify 标签需管理员按实际来源调整。
- 未新增浏览器自动化依赖；右键交互由目录工具单元、组件契约、生产构建和真实后端 API 覆盖，复杂画布的鼠标手感仍建议在预生产人工体验。
- V9 为已执行 V8 的向前修正，不物理删除 V8；直接回滚应用时数据库中的额外来源值和列仍保留。

### 下次测试建议

1. 增加浏览器 E2E，真实触发右键、切换十类目录、验证边缘位置菜单钳制、Escape/外部点击和缩放后的节点坐标。
2. 在模板数量显著增加时验证菜单滚动、分类切换和渲染性能，并评估是否增加搜索能力。
3. 若未来真正导入 n8n/Dify 定义，应另行设计版本、执行器兼容、安全审查和来源同步策略，不能仅依赖当前标签。

### 重测触发条件与回滚

- 修改模板来源枚举、分类映射、模板创建更新逻辑、画布坐标转换、菜单关闭行为或 MySQL 迁移时，必须重跑 Backend、Frontend、统一重建和来源 API 运行态验收。
- 应用回滚可回退功能提交 `64e1e7279ae0a0d2fe92eb19520b573baf978643` 后重新执行 `docker compose up --build -d`；V9 应保留，旧应用会把未知展示值按系统模板保护规则回退，不影响执行。
- 如必须物理回滚 V9，应先确认没有模板使用 N8N/DIFY，并备份 `workflow_node_template`；因 Flyway 已记录迁移，不建议直接删除迁移记录或手工回改列默认值。

---

## 📋 原生工作流节点扩展与可视化配置测试结果（2026-08-07）

### Git 基准点

Commit: 5f0aaa6009275c417cafef1ad6066f2d75c5011e
- 提交说明: Harden workflow connector nodes
- 测试日期: 2026-08-07
- 分支: master
- 上一测试报告基准点: `265f6664a407405113d4eb118e8dc2bec7fa3b7b`
- Backend 业务代码差异: `git diff 265f6664a407405113d4eb118e8dc2bec7fa3b7b 5f0aaa6009275c417cafef1ad6066f2d75c5011e -- backend/src/main/java/` 增加 Redis 参数前置校验；结合上一功能提交覆盖工作流执行器注册、原生节点、连接管理、触发器、模板目录、运行恢复和配置限制，因此已重新执行 Backend、Frontend 及统一部署回归。

### 变更范围

- 节点管理改为按“Base AI 原生 / 自定义”来源分组的响应式卡片目录；卡片展示节点类型、功能分类、状态及说明，权限控制和系统模板保护规则保持不变。
- 节点模板默认配置和画布节点实例配置统一使用卡片式可视化编辑器；标准字段按节点类型呈现，额外字段通过可递归的字符串、数字、布尔、空值、对象和数组参数卡维护，不再要求用户直接编辑 JSON。
- 原生节点扩展至流程控制、数据转换、文本与文档、AI、通知、数据库/缓存/对象存储、Kafka/RabbitMQ 和 Webhook/定时触发等能力；执行器通过受控注册表分派。
- 新增工作流连接管理，支持 MySQL、PostgreSQL、Redis、S3、Kafka、RabbitMQ 和 Webhook；敏感配置继续使用 AES-GCM 加密，列表只返回脱敏值，画布仅保存连接 ID。
- 连接执行器新增参数化 H2 SQL、SQL 写权限、受管邮件/通知、S3 前缀、Kafka Topic、RabbitMQ Exchange 及 Redis 非数组参数测试；Redis 在建立外部连接前完成参数结构校验。
- 新增 Webhook、定时和消息触发基础设施，以及 WAIT、子工作流、失败策略、重试参数和运行恢复能力；触发器仍受所有者、连接类型、幂等事件和资源上限约束。
- 新增 MySQL V6-V8 迁移，创建连接、等待和触发投递结构，补充节点模板的来源及功能分类并规范原生来源；未删除历史表或历史模板。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 节点管理以卡片分类展示 | Frontend 页面契约及生产构建 | 加载系统模板与自定义模板 | 分别进入 Base AI 原生和自定义分组，卡片展示类型、功能分类和状态；符合预期 | 正常、兼容、响应式 |
| 模板和画布配置不使用 JSON 文本框 | Frontend 页面契约与配置工具单元 | 打开模板默认配置和画布实例配置 | 两处均使用同一可视化配置组件，未出现 `configText` 或配置 JSON 文本框；符合预期 | 正常、回归 |
| 标准字段与任意附加字段可往返 | Frontend 参数化单元 | 标量、空值、深层对象、数组、空集合、未知字段和危险键名 | 类型保持、深复制不污染原值、未知字段保留，原型污染键被拒绝；符合预期 | 正常、边界、安全 |
| 全部原生节点有受控分类和配置入口 | Backend/Frontend 目录单元 | 36 类原生节点及迁移前模板 | 类型均进入稳定功能分类并具备配置定义，旧模板可推导兼容元数据；符合预期 | 兼容、数据、回归 |
| 连接配置加密且越权受限 | Backend Service 单元 | 创建含密码连接、脱敏更新、跨所有者更新 | 密文不含明文密码，`******` 保留旧密钥，跨所有者操作被拒绝；符合预期 | 权限、安全、数据副作用 |
| 连接节点遵守执行与目的地边界 | Backend 执行器单元 | 参数化 SQL、未授权写入、邮件、通知、越界 S3/Kafka/RabbitMQ 和非法 Redis 参数 | 合法只读查询及受管调用成功；写入、目的地越界和非法参数在外连前被拒绝；符合预期 | 正常、异常、权限、安全 |
| 数据与 AI 节点执行语义正确 | Backend 执行器单元 | 排序、聚合、CSV 转义、Schema 校验、分类和提取配置 | 正常结果正确，非法结构和 Schema 明确失败；符合预期 | 正常、边界、异常 |
| 触发器、子工作流和嵌套图受限 | Backend Service/Validator 单元 | 触发节点边界、重复事件、连接类型、嵌套深度和等待状态 | 合法触发可调度，重复及越权连接被拒，图和资源上限生效；符合预期 | 权限、安全、资源保护 |
| 现有功能不回归 | Backend、Frontend、Worker、Caddy 完整套件 | 当前功能提交全部自动化测试 | 608/608 通过，失败 0、错误 0、跳过 0；符合预期 | 回归、权限、安全 |
| 统一重建后服务可运行 | Docker Compose 完整构建与健康检查 | `docker compose up --build -d` | Backend 构建阶段 355/355 通过，四服务最终全部 healthy；符合预期 | 构建、部署、回归 |

### 测试执行结果

- 自动化测试合计：608/608 通过（Backend 355、Frontend 209、Python Worker 28、Caddy Go 16），失败 0，错误 0，跳过 0，通过率 100%。
- Backend 关键模块：Workflow Service、连接、连接执行器、触发器、图校验、数据执行器、模板目录及 Schema 相关新增/回归用例全部通过，并包含于完整 355 项中。
- Frontend 关键模块：节点来源分组、配置结构往返、全部节点配置定义、画布菜单、模板分类和连接管理契约全部通过，并包含于完整 209 项中。
- Python Worker：Python 3.12 隔离容器执行 28 项完整测试，全部通过。
- Caddy：Go 1.26.5 隔离容器执行 16 项完整测试，全部通过。
- 静态及部署：`git diff --check` 通过；统一 Compose 重建成功；Backend、Frontend、Python Worker、Caddy 全部 healthy；登录态 API 返回 36 个 Base AI 原生模板且来源集合仅为 `SYSTEM`，连接权限可用。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整回归与构建 | `docker compose up --build -d` 内 Maven 3.9.9 / Java 17 执行 `mvn -B -ntp package` | 355/355 通过，BUILD SUCCESS |
| Frontend 快捷套件 | `cd frontend && npm test` | 41/41 通过 |
| Frontend 完整回归 | `cd frontend && node --test test/*.test.mjs tests/*.test.js` | 209/209 通过 |
| Worker 完整回归 | Python 3.12 只读源码容器安装锁定开发依赖后执行 `python -m pytest -q -p no:cacheprovider` | 28/28 通过 |
| Caddy Go 回归 | Go 1.26.5 只读源码容器在临时目录执行 `go test -v` | 16/16 通过 |
| 规定统一重建 | `docker compose down --remove-orphans` 后执行 `docker compose up --build -d` | 未删除卷；四镜像构建成功，四服务全部 healthy |
| HTTPS 健康检查 | `curl -kfsS https://localhost/api/open/health/ready` | HTTP 200，状态 `UP` |
| 登录态 API 冒烟 | 临时 Cookie 登录后读取 `/api/auth/me`、`/api/workflow/nodes`、`/api/workflow/connections` | 管理员连接权限存在；36 个模板均为 Base AI 原生来源；临时 Cookie 已删除 |
| 变更与清理检查 | `git status`、`git diff --check`、提交范围检查 | 无调试文件、缓存或临时容器遗留 |

### 测试过程问题与处理

- 首次统一构建发现并发扩展调用当前 AWS SDK 不存在的 `ResponseBytes.asBase64String()`；改用 JDK Base64 编码后编译通过。
- 首轮 Backend 完整测试发现连接 Service 测试缺少 H2 表结构、CSV 转义测试数据不是有效 JSON；补齐独立测试表并修正转义后，348/348 通过，未删除、跳过或弱化测试。
- 多次 Compose 启动因并发重建遗留的容器名称发生冲突；最终执行不删除卷的 `docker compose down --remove-orphans` 清理运行态容器，再统一重建成功，持久数据未删除。
- 最终连接执行器定向覆盖新增 7 项，参数化 H2 SQL、邮件/通知委派及外部目的地边界均通过；完整 Backend 从 348 项增至 355 项。
- Air 预览受本地自签名证书拦截，宿主浏览器自动化又受临时目录权限限制，因此未取得浏览器截图；页面行为由 209 项 Frontend 契约/单元测试、Vite 生产构建和真实 HTTPS 健康检查覆盖。

### 已知问题与限制

- 未连接真实 MySQL/PostgreSQL 业务库、Redis、S3、Kafka、RabbitMQ、通知 Webhook 或模型供应商执行端到端节点调用；连接加密、类型约束、命令边界和执行分派由自动化测试覆盖，外部系统兼容性仍需预生产验收。
- 未完成真实浏览器登录后的鼠标交互截图；卡片布局、配置组件接入、权限显隐和响应式规则已通过源码契约与生产构建验证，复杂嵌套参数的实际操作体验建议人工复核。
- 可视化附加参数编辑器最大展示八层嵌套；更深的既有数据保持原值但不在当前界面展开编辑，以避免无限递归和页面资源耗尽。
- 前端构建仍有 runtime-config、第三方 PURE 注释及大 Chunk 的既有警告，不影响本次构建或运行结果。

### 下次测试建议

1. 在预生产配置七类真实受管连接，覆盖成功、认证失败、超时、权限不足、目的地越界、消息确认和网络中断。
2. 增加浏览器 E2E，覆盖来源分组、卡片点击、字段启停、对象/数组嵌套、模板保存、画布实例覆盖、撤销重做和窄屏布局。
3. 使用真实 Webhook、Cron、Kafka 和 RabbitMQ 事件验证幂等投递、重启恢复、重复消息、取消及失败重试。
4. 对大对象、八层嵌套、最大节点数、最大迭代数及批量模板目录执行容量和交互性能测试。

### 重测触发条件与回滚

- 修改任一工作流执行器、节点类型、连接加密/授权、触发器、图校验、失败策略、配置字段、模板来源/功能分类或 V6-V8 Schema 时，必须重跑 Backend、Frontend 完整套件及统一 Compose 重建。
- 修改可视化参数类型、递归深度、卡片分组、画布实例配置或权限显隐时，必须重跑 Frontend 完整套件、生产构建和浏览器交互验收。
- 如只回退连接节点加固，可撤销提交 `5f0aaa6009275c417cafef1ad6066f2d75c5011e`；如回退完整节点扩展，可回退功能提交 `265f6664a407405113d4eb118e8dc2bec7fa3b7b` 的父提交后重新执行 `docker compose up --build -d`。V6-V8 已应用的数据结构和元数据列应保留，旧应用会忽略新增表/列。
- 如必须物理回滚数据库，应先停止触发消费与工作流写入并备份连接、等待状态、触发投递和模板元数据；删除表、列或迁移记录属于破坏性操作，必须另行制定迁移方案并确认。

---

## 📋 工作流管理与 MySQL 执行引擎测试结果（2026-08-07）

### Git 基准点

Commit: 14c27e253ead1fb785de7a711ffe4c93da441664
- 提交说明: Add executable workflow management
- 测试日期: 2026-08-07
- 分支: master
- 上一测试报告基准点: `75d130c480f00c9bc5d09393a1c3e641e325f480`
- Backend 业务代码差异: `git diff 75d130c480f00c9bc5d09393a1c3e641e325f480 14c27e253ead1fb785de7a711ffe4c93da441664 -- backend/src/main/java/` 包含工作流管理、校验、执行、开放接口、任务追踪和资源限制，因此已执行定向、完整、部署及真实 MySQL 运行态测试。

### 变更范围

- 新增与“自动化”平级的“工作流”目录，以及“节点管理”“画布管理”页面和对应按钮权限；管理员可维护复用节点模板、拖拽画布、撤销重做、编辑嵌套子画布、发布版本、手动运行及查看逐节点日志。
- 支持 START、END、LLM、HTTP、工具调用 AGENT、CONDITION、数组 ITERATION 和条件 LOOP；普通图必须为可达 DAG，循环只能通过受限控制节点表达。
- 工作流定义、不可变版本、节点模板、工作流运行和节点运行日志全部作为框架层数据存入 MySQL；配置、输入和输出使用既有 AES-GCM 能力加密。
- 新增已发布工作流的 API Key 开放执行与运行查询接口；有效权限是 API Key 端点范围与绑定用户 RBAC 权限的交集。
- Python 3.12 Worker 新增一次 Agent 工具选择协议；具体 HTTP 或子工作流工具仍由 Java 执行器在既有 SSRF、TLS、权限和资源边界内执行。
- 新增 MySQL V4 工作流 Schema，以及 V5 历史 API Key 列兼容迁移。V5 只在旧列存在时将其调整为可空，不删除旧列或历史数据；PostgreSQL 未增加工作流表。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 工作流与自动化平级且包含两个页面 | Backend 初始化单元、Frontend 路由/导航完整套件 | 初始化菜单并以不同权限生成导航 | 顶层工作流目录、节点管理和画布管理均存在，按钮权限独立；符合预期 | 正常、权限、兼容 |
| 节点模板可复用且类型完整 | MySQL 迁移资源测试、运行库检查 | 应用 V4，查询内置模板和五张工作流表 | 八类内置节点及五张表全部存在；模板实例保留快照；符合预期 | 正常、数据、回归 |
| 画布拒绝非法结构 | Backend 图校验单元 | 最小 DAG、悬空边、普通循环、无边界、不可达节点、Vue Flow `data.config` 嵌套循环、超深子画布 | 合法图通过；非法图及超限嵌套被明确拒绝；符合预期 | 边界、异常、安全 |
| 条件、变量和 UI 配置可执行 | 表达式单元及正式 Compose 运行态 | `data.config` 条件 `input.value >= 10`，输入 12，真假两条分支 | 状态 SUCCESS，输出 `approved=true,value=12`，仅执行命中路径的 3 个节点；符合预期 | 正常、分支、集成 |
| 迭代、循环、Agent 和递归受限 | Backend 校验/配置测试、Python Agent 协议测试 | 节点数、迭代数、Agent 步数、子画布/子工作流深度和负载配置；重复或非法工具参数 | 配置硬上限生效；非法 Agent 消息、工具及参数被拒绝；符合预期 | 边界、异常、资源保护 |
| 手动运行保留版本和逐节点日志 | 正式 Compose 运行态及 MySQL 查询 | 创建草稿、提交对象输入、轮询运行详情 | 使用不可变版本异步执行，运行及 3 条节点日志持久化，输入输出可解密返回；符合预期 | 正常、数据副作用、集成 |
| API Key 可执行已发布工作流 | 正式 Compose 运行态；临时管理员 Key 只授权两个工作流端点 | 发布 START→END 工作流，API Key 输入 `value=42`，轮询结果后吊销 | 创建 Key、异步执行、结果查询和吊销全部成功；状态 SUCCESS，2 个节点，输出值 42；符合预期 | 正常、权限、安全 |
| 历史 API Key Schema 可继续创建 Key | MySQL Flyway 运行态与 Schema 查询 | 历史库保留非空 `rate_limit_per_minute`，应用 V5 后创建新 Key | 旧列变为可空，V4/V5 均成功，API Key 创建恢复；符合预期 | 兼容、迁移、回归 |
| 工作流数据不进入 PostgreSQL | Git 变更、Flyway 路径及运行库检查 | 检查迁移目录和 MySQL 表 | 工作流迁移仅存在于 MySQL，PostgreSQL 迁移链保持原范围；符合预期 | 架构、兼容 |
| 现有功能不回归 | Backend、Frontend、Worker、Caddy Go 完整套件 | 当前基准点全部自动化测试 | 565/565 通过，失败 0、错误 0、跳过 0；符合预期 | 回归、权限、安全 |
| 统一重建后服务可运行 | Docker Compose 完整构建与健康检查 | `docker compose up --build -d` | 四个镜像构建成功，Backend 构建阶段 329/329，通过后四服务全部 healthy | 构建、部署、回归 |

### 测试执行结果

- 自动化测试合计：565/565 通过（Backend 329、Frontend 192、Python Worker 28、Caddy Go 16），失败 0，错误 0，跳过 0。
- 工作流 Backend 定向覆盖：表达式 6、图结构 5、Schema 2、菜单初始化 11、API Key 端点目录 4；均包含在 Backend 完整 329 项中，不重复计数。
- Frontend 工作流定向覆盖：最小画布、唯一 ID、悬空边和普通循环；包含在 Frontend 完整 192 项中。
- Python Agent 定向覆盖：有效工具调用解析和非法非对象参数拒绝；包含在 Worker 完整 28 项中。
- 静态检查：`docker compose config --quiet`、`git diff --check` 和 `git diff --cached --check` 全部通过。
- 数据库迁移：真实 MySQL 的 Flyway V4、V5 均为成功状态；五张工作流表存在，历史 `rate_limit_per_minute` 已变为可空。
- 运行态验收：会话手动运行和 API Key 开放运行均成功，条件分支、UI `data.config`、节点日志、结果查询及 Key 吊销均符合预期。
- 清理恢复：临时工作流、版本、运行、节点日志、任务链路、API Key 及 Cookie 文件均已删除；两类临时工作流和临时 Key 的 MySQL 残留计数均为 0。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整回归与构建 | `docker compose up --build -d` 内 Maven 3.9.9 / Java 17 执行 `mvn -B -ntp package` | 329/329 通过，BUILD SUCCESS |
| Frontend 完整回归 | `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` | 192/192 通过 |
| Frontend 工作流快捷套件 | `npm test -- --runInBand` | 26/26 通过，其中工作流 2/2 |
| Worker 完整回归 | Python 3.12.13 隔离容器按哈希安装开发依赖后执行 `python -m pytest` | 28/28 通过 |
| Caddy Go 回归 | Go 1.26.5 隔离容器复制源码到临时目录后执行 `go test` | 16/16 通过 |
| Compose 静态校验 | `docker compose config --quiet` | 通过 |
| 规定统一重建 | `docker compose up --build -d` | 最终构建成功，四个项目服务均 healthy |
| MySQL 迁移验证 | 查询 `flyway_schema_history`、`information_schema.COLUMNS` 和工作流表 | V4/V5 成功；旧限流列可空；五张工作流表存在 |
| API Key 端到端 | 创建、发布、创建受限 Key、开放执行、轮询查询、吊销并清理 | SUCCESS；输出值 42；2 条节点日志；Key 生命周期验证通过 |
| UI 配置端到端 | 以 Vue Flow `data.config` 创建条件工作流并手动执行 | SUCCESS；`approved=true,value=12`；仅 3 个命中节点执行 |
| 变更和清理检查 | `git status`、暂存差异、格式检查、MySQL 临时前缀计数 | 功能提交只含确认范围；无调试文件或临时业务数据 |

### 测试过程问题与处理

- API Key 首次运行验收发现历史 MySQL 仍保留 `rate_limit_per_minute NOT NULL`，而当前实体已使用 `rate_limit_type/rate_limit_count`，导致新 Key 插入失败。经用户再次批准后新增条件式 V5 兼容迁移，仅放宽旧列空值约束；重建后 Key 创建、执行、查询和吊销全部通过。
- 首次统一重建时宿主机 80/443 被 `domestic-trade-caddy` 占用；按项目规则只停止该占用容器后重试。最终当前项目按本机配置发布 81/444，四服务均 healthy；外部容器随后按自身重启策略恢复在 80/443 运行，两项目不再冲突。
- 宿主机 Python 3.12.13 未安装 pytest；改用只读挂载源码的 Python 3.12 隔离容器安装锁定开发依赖，28/28 通过。只读挂载使 pytest 缓存写入产生一条非测试失败警告，仓库未产生缓存文件。
- 首次 Caddy Go 隔离命令使用登录 Shell，镜像 PATH 被重置而未找到 Go；改用普通 Shell 并在容器临时目录初始化 module 后测试通过，未修改仓库。
- UI 运行态脚本最初使用默认 443，而当前 Compose 实际映射为 444，请求未进入项目；改为从容器端口映射动态读取后完整验收通过。失败尝试未创建工作流或 Key。
- 审查发现 Vue Flow 把实例配置保存在 `data.config`，初版嵌套校验只读取顶层 `config`；补充覆盖用例后统一兼容两种格式，并增加子画布深度校验与执行深度递增，最终 Backend 329/329 通过。

### 已知问题与限制

- 本次未调用真实外部 LLM 供应商或真实外部 HTTP 服务；Agent 协议、候选解析和 HTTP 安全复用由自动化测试覆盖，但生产模型能力路由、供应商 tool-calling 兼容性和目标网络仍需环境验收。
- 未执行浏览器自动化；画布交互通过前端单元、生产构建和真实 HTTP API 等价验证，拖拽手感及复杂大图性能仍需人工体验测试。
- 工作流异步任务由单进程线程池执行；服务重启会将遗留 QUEUED/RUNNING 记录标记失败，当前版本不支持断点续跑或分布式抢占。
- ITERATION 和 LOOP 按顺序执行，尚不提供并行迭代；定义删除为软删除，历史版本和运行记录继续保留。
- V5 为兼容历史库保留旧限流列并改为可空，没有物理删除该列；待所有部署确认不再运行旧版本后，可另行规划清理迁移。

### 下次测试建议

1. 在预生产配置真实模型能力路由，分别验证 LLM 直答、Agent 单/多工具调用、工具失败、候选切换、超时和 Token 统计。
2. 使用受控 HTTP 测试服务验证工作流 HTTP 节点的各方法、模板变量、JSON/文本响应、重定向、SSRF 拒绝和超时。
3. 增加浏览器 E2E，覆盖模板拖入、节点配置、条件连线、嵌套画布、撤销重做、并发编辑冲突、发布和日志抽屉。
4. 在大图和上限输入下执行容量测试，验证线程池排队、取消、100 次迭代/循环、深度上限和 1 MiB 负载限制。

### 重测触发条件与回滚

- 修改任一工作流 Domain/Service/Controller、节点执行语义、表达式、图校验、加密字段、MySQL Schema、API Key 端点、Worker Agent 协议或资源上限时，必须重跑 Backend、Worker、Frontend 完整套件及相应运行态验收。
- 修改菜单权限、Vue Flow 依赖、路由、画布序列化或页面交互时，必须重跑 Frontend 完整套件、生产构建和浏览器/HTTP 等价验收。
- 应用回滚可回退功能提交 `14c27e253ead1fb785de7a711ffe4c93da441664` 后重新执行 `docker compose up --build -d`。为避免破坏已产生的工作流历史数据，V4/V5 表和兼容列应保留；旧应用会忽略这些额外表和可空旧列。
- 如必须物理回滚数据库，应先停止工作流写入并备份五张工作流表，再按外键顺序删除节点运行、运行、版本、定义和模板表；V5 只有在确认旧列不存在空值时才能恢复非空约束。

---

## 📋 API Trigger 安全重定向与 Caddy 公共 CA 测试结果（2026-08-07）

### Git 基准点

Commit: 75d130c480f00c9bc5d09393a1c3e641e325f480
- 提交说明: Support secure API trigger redirects
- 测试日期: 2026-08-07
- 分支: master
- 上一测试报告基准点: `e17167958253ef876b1e9c273f92044b570a616d`
- 对应上游功能提交: `domestic-trade/master` 的 `820b2b5d5b33839e22aa4b83e7b68d9b0256b9db`
- Backend 业务代码差异: `git diff e17167958253ef876b1e9c273f92044b570a616d 75d130c480f00c9bc5d09393a1c3e641e325f480 -- backend/src/main/java/` 包含接口触发重定向与 TLS 信任逻辑，因此已执行定向、完整、部署和运行态测试。

### 变更范围

- API Trigger 改为显式处理远端重定向：每一跳重新校验 URL、Host 和网络地址，最多跟随五次，并检测循环。
- GET 支持 301、302、303、307、308；POST、PUT、PATCH、DELETE 仅支持保持方法及正文的 307、308。跨 Host、HTTPS 降级 HTTP、缺失或非法 `Location`、不受支持状态和超限跳转均被拒绝。
- 保留 JVM 默认公共 CA，同时加载 Caddy 发布的公共根证书；公共 CA 域名与项目内部 CA HTTPS 地址可同时访问，Caddy 根证书出现后无需重启 Backend。
- 采用已确认的 A 方案：Caddy 通过独立 `caddy-public-ca` 卷原子发布权限为 0444 的 `root.crt`，Backend 只读挂载该公开证书卷，不挂载含 CA 私钥和站点私钥的 `caddy-data`。
- Caddy 和 Backend 镜像均为 UID 10001 准备公共证书目录，避免命名卷首次初始化的权限问题。
- 同步中英文错误消息、部署文档和部署契约；未新增依赖、数据库迁移、数据回填或前端/Python Worker 业务逻辑。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| GET 可安全跟随标准重定向 | Backend 参数化单元测试 | 301、302、303、307、308，同 Host 相对或绝对 `Location` | 五种状态均到达目标，最终正文和状态正确；符合预期 | 正常、兼容 |
| 非 GET 仅跟随保留语义的跳转 | Backend 参数化单元与运行态认证 | POST 307/308；POST 301/302/303；HTTP 认证登录后请求目标 | 307/308 保留方法和 JSON 正文；301/302/303 被拒；认证与目标两次 308 后最终 200；符合预期 | 正常、边界、回归 |
| 每一跳保持 SSRF 安全边界 | Backend 单元测试 | 跨 Host、HTTPS→HTTP、无效或缺失 `Location`、不支持的 305 | 分别以明确业务错误拒绝，未访问不安全下一跳；符合预期 | 权限、安全、异常 |
| 重定向次数和循环受限 | Backend 单元测试 | 恰好五跳、超过五跳、循环地址 | 五跳成功；超限和循环均拒绝；符合预期 | 边界、资源保护 |
| Caddy 内部 CA HTTPS 可访问 | TLS 单元、部署契约与正式 Compose 运行态 | `https://172.30.0.10/api/open/health/live` | Backend 只读加载公共根证书，API Trigger 返回 200 和 `UP`；符合预期 | 正常、TLS、集成 |
| HTTP 地址可经 Caddy 访问 HTTPS | 正式 Compose 运行态 | `http://172.30.0.10/api/open/health/live` | Caddy 返回 308，API Trigger 保持 Host 并跟随后以 HTTPS 返回 200；符合预期 | 正常、重定向、兼容 |
| 公共 CA 信任不回归 | TLS 组合信任单元与正式运行态 | API Trigger 请求 `https://example.com/` | JVM 公共 CA 校验成功并返回 200；符合预期 | 兼容、回归 |
| Backend 不接触 Caddy 私钥 | 部署契约、容器挂载与文件校验 | 检查 Compose、Backend Mounts、卷内证书权限和 SHA-256 | Backend 仅有只读公共 CA 卷且无 `/app/caddy-data`；公开证书与 Caddy 源根证书一致、权限 0444；符合预期 | 权限、安全、部署 |
| CA 文件异常时安全失败 | Backend TLS 单元测试 | 缺失、延迟出现、目录、符号链接、非法 PEM、DER、超大文件、非 CA 叶证书、超量证书 | 缺失的可选 Caddy 根证书兼容启动；出现后即时加载；其他非法配置拒绝；符合预期 | 异常、边界、安全 |
| 现有功能不回归 | Backend、Frontend、Worker、Caddy Go 完整套件 | 当前分支全部自动化测试 | 547/547 通过，失败 0、错误 0、跳过 0；符合预期 | 回归、权限、安全 |
| 统一重建后服务可运行 | Docker Compose 完整构建与健康检查 | `docker compose up --build -d` | 四个镜像构建成功，四个服务最终全部 healthy，HTTPS 健康检查为 200 | 构建、部署、回归 |

### 测试执行结果

- 自动化测试合计：547/547 通过（Backend 315、Frontend 190、Python Worker 26、Caddy Go 16），失败 0，错误 0，跳过 0。
- Backend 定向测试：39/39 通过，其中重定向与响应 25、TLS 信任 11、消息资源 3；这些用例已包含在 Backend 完整 315 项中，不重复计入总数。
- Caddy 部署契约：12/12 通过；该套件已包含在 Frontend 190 项中，不重复计数。
- Caddy Go：16/16 通过；`gofmt` 无差异，`go vet` 通过。
- 静态检查：`docker compose config --quiet`、Shell 语法、`git diff --check` 全部通过。
- 规定统一重建：`docker compose up --build -d` 在释放被另一项目占用的 80/443 端口后成功；最终代码再次重建时 Backend 镜像构建阶段执行 315/315 测试，最终四服务均 healthy。
- 运行态验收：HTTPS 直连、HTTP GET 308、认证 POST 308 加目标 GET 308、公有 CA HTTPS 四项均返回 200；临时 API Trigger 安全策略已恢复原值。
- 隔离验收：Backend 的挂载中不存在 `caddy-data`，公共根证书可读且哈希一致；Caddy 私钥不可由 Backend 访问。
- 清理恢复：临时目录 `/tmp/base-ai-api-trigger-redirect-test` 已删除；Caddy 已恢复空 `APP_HTTPS_IPS` 的常规配置，`domestic-trade-caddy` 原 `unless-stopped` 重启策略已恢复并保持停止，当前项目四服务均 healthy。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 缺陷复现 | 定向执行 `ApiTriggerServiceResponseDecodingTest`、`ApiTriggerTlsTrustTest`、`MessageBundleTest` | 实现前因缺少 TLS 信任类稳定失败；实现后 39/39 通过 |
| Backend 完整回归 | Maven 3.9.9 / Eclipse Temurin 17 固定容器执行 `mvn -B -ntp test` | 315/315 通过，BUILD SUCCESS |
| Caddy 部署契约 | `node --test frontend/test/security-deployment.test.mjs` | 12/12 通过 |
| Frontend 完整回归 | `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` | 190/190 通过 |
| Worker 完整回归 | Python 3.12 环境执行 `python -m pytest -q -p no:cacheprovider` | 26/26 通过 |
| Caddy Go 单元与静态检查 | 固定 Go 环境执行 `gofmt`、`go test`、`go vet` | 16/16 通过，格式与 vet 通过 |
| Compose 与 Shell 静态检查 | `docker compose config --quiet`、`sh -n caddy/caddy-entrypoint.sh`、`git diff --check` | 全部通过 |
| 规定统一重建 | `docker compose up --build -d` | 四镜像成功构建，四服务最终 healthy |
| 公共 CA 隔离 | `docker inspect` 挂载检查、容器内可读性/不存在性断言、根证书 SHA-256 和权限比对 | 仅公开根证书共享；Backend 无 Caddy 私钥卷访问能力 |
| API Trigger HTTP/HTTPS | 调用 `/api/automation/api-triggers/test` 请求内部 HTTP、内部 HTTPS 和 `example.com` | 三类目标均返回 HTTP 200，内部响应为 `{"status":"UP"}` |
| 认证重定向 | 认证 URL 和目标 URL 均使用内部 HTTP；Caddy 分别返回 308 | 登录 POST 方法和 JSON 正文保持，Token 提取成功，目标 GET 最终返回 200 |
| 清理与恢复 | 恢复安全策略、删除临时目录、空配置重建 Caddy、恢复外部容器重启策略 | 无调试文件或临时策略遗留，当前四服务 healthy |

### 测试过程问题与处理

- 首次定向测试按“先失败再修复”执行，因 `ApiTriggerTlsTrust` 尚不存在而编译失败；初版曾包含额外私有 CA 目录。用户确认项目只使用一套 Caddy 私有 CA 后，移除目录能力及仅适用于该能力的目录扫描测试，最终 39/39 通过，没有跳过或弱化仍有效的测试。
- 首次部署契约 12 项中 3 项因公共 CA 环境变量、卷和镜像目录尚未实现而失败；A 方案实现后 12/12 通过。
- 首次 `docker compose up --build -d` 完成镜像构建和 Backend 317 项测试后，80/443 被 `domestic-trade-caddy` 占用；按项目规则临时关闭该容器并禁用自动重启后重试成功。
- 运行态验收期间该外部容器曾再次自动启动并抢占端口；再次停止后完成验收，最后恢复其 `unless-stopped` 策略并保持停止，未修改其镜像、卷或项目文件。
- 临时 API Trigger 安全策略只增加内部 Caddy IP 和 `example.com` 白名单，验收后已恢复为原配置；管理员凭证和 Token 未写入仓库或测试报告。

### 已知问题与限制

- Caddy 内部 IP 证书仍由项目内部 CA 签发；浏览器或项目外客户端仍需单独安装根证书。A 方案只解决 Backend API Trigger 的信任，不改变外部客户端信任库。
- A 方案只向 Backend 暴露项目 Caddy 的公开根证书，不提供任意自定义私有 CA 目录；如未来需要访问其他私有 PKI，需另行设计最小权限证书挂载。
- 非 GET 请求不会跟随可能改变方法或丢失正文的 301、302、303；跨 Host 跳转和 HTTPS→HTTP 降级始终拒绝，这是预期安全限制。
- HTTP 认证 URL 的首跳仍以明文发送凭证和正文；生产配置应直接使用最终 HTTPS URL。
- Caddy 根证书首次生成前，Backend 仍可使用 JVM 公共 CA，但内部 CA HTTPS 需等待 Caddy 发布公开根证书。

### 下次测试建议

1. 在预生产网络使用真实内网 IP、反向代理链和生产 Host 白名单验证多跳 307/308、超时和连接中断。
2. 在 Caddy 根 CA 轮换演练中验证公开证书卷原子替换、Backend 下一次触发即时加载和并发请求行为。
3. 若引入其他企业私有 CA，先设计每个 CA 的只读最小挂载、文件上限和轮换流程，再扩展组合信任测试。

### 重测触发条件与回滚

- 修改重定向状态、跳数、方法/正文保留、Host 比较、协议降级或逐跳 URL 安全策略时，必须重跑 Backend 定向及完整套件。
- 修改 Caddy 根 CA 发布、公共卷权限、Backend TLS 信任、Compose 挂载、Caddy/Backend 镜像用户时，必须重跑 TLS、部署契约、Compose 重建、挂载隔离及 HTTP/HTTPS 运行态测试。
- 可回滚功能提交 `75d130c480f00c9bc5d09393a1c3e641e325f480` 后重新执行 `docker compose up --build -d`；本次没有数据库迁移或业务数据变更，新增的 `caddy-public-ca` 命名卷可保留，也可在确认无其他容器使用后手工删除。

---

## 📋 APP_HTTPS_IPS 与混合 HTTPS 入口测试结果（2026-08-07）

### Git 基准点

Commit: e17167958253ef876b1e9c273f92044b570a616d
- 提交说明: Support preconfigured HTTPS IP addresses
- 测试日期: 2026-08-07
- 分支: master
- 上一测试报告基准点: `1a2f15dae635a024c892a05d59f97b764f3cac3b`
- 对应上游功能提交: `domestic-trade/master` 的 `0f2facbd42e5b1dc0a1a2e4bfa6f77540746671f`
- Backend 业务代码差异: `git diff 1a2f15dae635a024c892a05d59f97b764f3cac3b HEAD -- backend/src/main/java/` 无输出；本次未修改 Backend 业务代码，仍按确认范围完成三端完整回归。

### 变更范围

- 新增 `APP_HTTPS_IPS`，支持逗号或空白分隔的规范 IPv4；启动时将预配置地址加入 Caddy 内部 CA 证书，使对应地址首次可直接使用 HTTPS。
- 外部域名证书、预配置 IP 和动态学习 IP 可同时启用；域名继续使用部署者提供的证书，IP 共用内部 CA 多 SAN 证书。
- 域名证书启用后仍支持 HTTP 请求驱动的 IPv4 动态学习；动态签发、续期和 Caddy reload 会保留全部域名、预配置 IP 与已学习 IP。
- Caddy 文件夹证书加载器同时加载外部域名 PEM 包和内部 IP PEM 包；内部证书、私钥、合并包及学习状态在签发、持久化或 reload 失败时一并回滚。
- 预配置和动态地址统一拒绝 IPv6、非规范、未指定、链路本地、组播与不可用地址；内部证书最多包含 256 个非固定回环 IPv4，动态地址仍受 `IP_CERT_MAX_LEARNED_HOSTS` 独立限制。
- 同步 Compose 环境传递、环境模板、中英文部署文档、Go 单元测试和 Caddy 部署契约；未新增依赖，未修改数据库、Backend 业务代码、前端业务逻辑或 Python Worker 业务逻辑。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 预配置 IP 首次可直接 HTTPS | 正式 Compose 运行态；Caddy 根 CA 已存在 | `APP_HTTPS_IPS=127.0.0.2`，不发送首次 HTTP，直接请求 HTTPS | HTTPS `/health` 返回 200；证书 SAN 包含 `127.0.0.2`，学习状态未写入该地址；符合预期 | 正常、集成、兼容 |
| 域名证书、预配置 IP 与动态学习同时可用 | 临时 `ai.test` 自签证书、预配置 `127.0.0.2`、未知 `127.0.0.4` | 先验证域名和预置 IP，再通过 HTTP 业务路径学习新 IP | 域名和预置 IP 均为 200；新 IP 返回保留路径与查询参数的 308，随后可信 HTTPS 为 200；符合预期 | 正常、集成、混合入口 |
| 动态 reload 不丢失既有入口 | Go reloader 单元与混合运行态 | 新 IP 签发并触发 Caddy reload 后重新请求 `ai.test` 和 `127.0.0.2` | 外部域名证书、预置 IP 和新增 IP 全部继续返回 200；符合预期 | 回归、状态变化 |
| 配置与学习状态稳定合并 | Go 参数化单元与 Shell 入口诊断 | 逗号、空白、重复配置、配置与学习重复、空配置 | 地址规范化并稳定去重；空变量保持 `localhost` 和 `127.0.0.1` 兼容行为；符合预期 | 边界、兼容 |
| 非法地址和资源超限被拒绝 | Go 单元与部署契约 | IPv6、前导零、`0.0.0.0`、链路本地、组播、DNS 名和超过 256 个地址 | 非法配置拒绝启动或签发；达到运行上限返回 HTTP 429；符合预期 | 异常、安全、资源保护 |
| 证书更新失败可恢复 | Go 证书生命周期、reload 失败和状态回滚测试 | 签发、续期、bundle 生成或 reload 失败 | 证书、私钥、内部 PEM 包和学习状态恢复旧版本；符合预期 | 异常、故障恢复 |
| 现有域名/IP 入口和应用功能不回归 | Caddy 契约、Go 单元及三端完整套件 | 当前分支全部既有自动化测试 | Go 16/16、部署契约 12/12、Backend 287/287、Frontend 190/190、Worker 26/26 全部通过 | 兼容、回归、权限、安全 |
| 统一重建后服务可运行 | Docker Compose 构建、健康检查和 HTTPS curl | 执行规定的统一重建并恢复默认空入口配置 | 四个镜像构建成功，四个服务均 healthy，默认 HTTPS 健康检查为 200 | 构建、部署、回归 |

### 测试执行结果

- 自动化测试合计：519/519 通过（Backend 287、Frontend 190、Python Worker 26、Caddy Go 16），失败 0，错误 0，跳过 0。
- Caddy 部署契约：12/12 通过；该 12 项包含在 Frontend 190 项完整测试中，不重复计数。
- Caddy Go：16/16 通过；`gofmt` 无差异，`go vet` 通过。
- 静态检查：Shell 语法、`docker compose config --quiet`、`git diff --check` 全部通过。
- 规定统一重建：`docker compose up --build -d` 成功，Backend、Python Worker、Frontend、Caddy 全部 healthy。
- 纯 IP 运行态：`127.0.0.2` 未经 HTTP 学习即可完成受根 CA 信任的 HTTPS 请求，证书 SAN 正确且未写入动态学习状态。
- 混合入口运行态：`ai.test` 外部证书、`127.0.0.2` 预配置内部证书与 `127.0.0.4` 动态内部证书同时工作；动态 reload 前后域名和预置 IP 均保持可用。
- 清理恢复：临时证书、YAML、OpenSSL 配置、测试镜像和动态学习地址已删除；Caddy 已恢复为空域名、空预配置 IP 的默认运行态，最终四服务均 healthy。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Caddy Go 单元与静态检查 | 固定 Go 1.26.5 Alpine 容器执行 `gofmt -d`、`go test -v ip-cert-helper.go ip-cert-helper_test.go`、`go vet` | 16/16 通过，格式与 vet 通过 |
| Caddy 部署契约 | `node --test frontend/test/security-deployment.test.mjs` | 12/12 通过 |
| Frontend 完整回归 | `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` | 190/190 通过 |
| Backend 完整回归 | Maven 3.9.9 / Eclipse Temurin 17 固定容器执行 `mvn -B -ntp test` | 287/287 通过，BUILD SUCCESS |
| Worker 完整回归 | Python 3.12 固定容器按哈希安装开发依赖后执行 `python -m pytest -q -p no:cacheprovider` | 26/26 通过 |
| Compose 与 Shell 静态检查 | `docker compose config --quiet`、`sh -n caddy/caddy-entrypoint.sh`、`git diff --check` | 全部通过 |
| 规定统一重建 | `docker compose up --build -d` | 四个镜像构建成功，四个服务启动并进入 healthy |
| 预配置 IP 运行态 | 以 `APP_HTTPS_IPS=127.0.0.2` 重建 Caddy，在容器内直接请求 HTTPS 并读取 SAN | 首次 HTTPS 200；SAN 包含预置地址；学习状态未写入该地址 |
| 混合 TLS 运行态 | 临时 `ai.test` 域名证书、预置 IP、未知 IP HTTP 学习、reload 后重复请求 | 域名/预置/动态入口全部通过；HTTP 308 保留路径和查询参数 |
| 清理与恢复 | 删除临时目录、测试镜像和动态学习状态，以空入口变量重建 Caddy | 无调试文件或学习地址遗留；默认四服务 healthy |

### 测试过程问题与处理

- 首次 Go 容器命令使用登录 Shell 后重置了镜像 `PATH`，导致 `go` 和 `gofmt` 未找到，测试未进入执行；改为显式传递 Go 工具链路径后 16/16 通过，`go vet` 通过。
- 首次读取证书 SAN 时宿主机 LibreSSL 不支持 `openssl x509 -ext`；改用 `openssl x509 -text` 后确认 SAN 正确，不影响先前已通过的 HTTPS 和状态断言。
- 首次混合入口健康等待脚本使用了 zsh 只读变量 `status`，请求尚未执行即退出；改名为 `health_state` 后完整混合入口流程通过。
- 纯 IP 验证后 Caddy 曾收到一次外部 `SIGTERM` 并以状态 0 退出，日志无应用错误；后续混合入口重建、默认配置恢复及最终健康检查均持续通过。
- 测试临时目录 `/tmp/base-ai-https-ip-sync-test`、临时构建镜像和动态学习地址均已清理，未创建或遗留仓库调试文件。

### 已知问题与限制

- IP 证书仍由 Caddy 内部 CA 签发，客户端必须安装项目根证书；`APP_HTTPS_IPS` 不会让证书自动获得公共 CA 信任。
- `APP_HTTPS_IPS` 仅支持规范 IPv4，不支持 IPv6；配置变更需要重启或重建 Caddy 后才会更新 SAN。
- 内部 IP 证书最多包含 256 个非固定回环地址；动态地址还受 `IP_CERT_MAX_LEARNED_HOSTS` 和最小签发间隔限制。
- 未预配置且未学习的 IP 首次仍需通过非 `/health` 的 HTTP 路径触发学习；首次直接 HTTPS 无法获得匹配证书。
- 本次使用回环 IPv4 和临时自签域名证书完成等价混合验证，未使用真实公网 IP、生产域名证书、外部负载均衡器或真实浏览器信任库。
- 前端生产构建仍有 runtime-config、第三方 PURE 注释和大 Chunk 的既有警告，不影响构建与运行结果。

### 下次测试建议

1. 在预生产主机配置真实内网、公网或 NAT 映射 IPv4，分别验证启动时直连、地址变更、移除配置和重启后的 SAN 收敛。
2. 使用生产域名证书与多客户端根证书信任库验证域名/IP 并发访问、HTTP/2、HTTP/3、Cookie、CSRF 和 HSTS。
3. 在接近 256 个 IP SAN 和动态学习上限的隔离环境执行证书大小、握手性能、429 恢复和续期压力测试。

### 重测触发条件与回滚

- 修改 `APP_HTTPS_IPS` 语法、地址安全边界、SAN 总量、域名 PEM 加载、IP 签发/续期、动态学习、Caddy reload、入口 Host 白名单、Compose 传递或相关文档契约时，必须重跑 Caddy Go、部署契约、混合运行态和统一重建。
- 修改 Backend 业务代码、前端业务逻辑或 Python Worker 时，按项目规则执行对应完整测试。
- 可回滚功能提交 `e17167958253ef876b1e9c273f92044b570a616d` 后重新执行 `docker compose up --build -d`；没有数据库迁移、数据回填、业务数据变更或需要删除的命名卷。

---

## 📋 HTTPS 站点分组与多证书 SNI 测试结果（2026-08-06）

### Git 基准点

Commit: 1a2f15dae635a024c892a05d59f97b764f3cac3b
- 提交说明: Support per-site TLS certificate groups
- 测试日期: 2026-08-06
- 分支: master
- 上一测试基准点: `be5c89e41204529d28a16b57ff8278c8e38c8344`

### 变更范围

- 修正上一版“全部域名共用一张证书”的模型，改为 `sites` 分组；每组 `domains` 独立绑定 `tls_cert_file` 和 `tls_key_file`。
- 配置入口重命名为 `APP_HTTPS_SITES_FILE`，占位文件重命名为 `caddy/https-sites-placeholder.yml`；证书通过 `TLS_CERTS_DIR` 根目录统一只读挂载。
- YAML 证书路径仅允许 `TLS_CERTS_DIR` 下的相对路径，拒绝绝对路径、`..` 和符号链接越界；同一域名不能跨分组重复。
- 启动时验证证书与私钥匹配，并用叶证书 SAN 验证所属组全部域名；验证成功后生成权限为 0600 的临时合并 PEM 包，Caddy 按 SNI 选择匹配证书。
- 配置限制为 64 KiB、最多 64 个站点分组和合计 256 个域名；IP 内部 CA 模式、Host 白名单、HTTP 跳转和内部接口隔离保持不变。
- 未修改 Backend 业务代码、数据库结构、业务数据或前端业务逻辑。

### 测试执行结果

- 自动化测试合计：514/514 通过（100%），失败 0，错误 0，跳过 0。
- 后端完整测试：287/287 通过，Maven BUILD SUCCESS。
- 前端完整测试：189/189 通过，其中 Caddy 部署契约 11/11 通过。
- Python Worker 完整测试：26/26 通过，运行于 Python 3.12.13。
- Caddy Go 单元测试：12/12 通过，Go vet 通过。
- Docker Compose：`docker compose up --build -d` 成功，四个服务全部 healthy。
- 双证书运行态：`ai.test`、`api.test` 使用第一张双 SAN 证书，`console.test` 使用第二张证书，三者受各自证书校验的 HTTPS 均返回 200。
- HTTP 运行态：允许域名返回保留 Host、路径和查询参数的 308；未知 Host 和 `/api/internal/**` 均返回 404。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 每组 domains 绑定独立证书和私钥 | Go 单元与运行态；两组域名、两对不同密钥 | 生成两个 PEM 包，Caddy 按 SNI 返回对应证书；符合预期 | 正常、集成 |
| 同组支持多个域名 | Go 单元与运行态；`ai.test`、`api.test` 共用双 SAN 证书 | 两域名均通过同一证书校验并返回 200；符合预期 | 正常、兼容 |
| 不同组使用不同证书 | 运行态；`console.test` 使用第二张独立证书 | 使用第二张 CA 文件验证成功并返回 200；符合预期 | 正常、安全 |
| 证书和域名必须匹配 | 参数化 Go 单元；SAN 不覆盖配置域名 | 启动准备失败，不生成可用入口；符合预期 | 异常、安全 |
| 证书和私钥必须匹配 | 参数化 Go 单元；第一张证书搭配第二张私钥 | 解析失败并拒绝配置；符合预期 | 异常、安全 |
| 证书路径不能逃逸根目录 | 参数化 Go 单元；`..` 和指向根目录外的符号链接 | 两类路径均拒绝；符合预期 | 权限、安全 |
| 域名不能跨组重复 | 参数化 Go 单元；不同大小写的相同域名位于两组 | 规范化后检测冲突并拒绝；符合预期 | 冲突、边界 |
| 非法 YAML 和资源超限被拒绝 | Go 单元；未知字段、多文档、非字符串、空列表、超大文件/列表 | 全部拒绝；符合预期 | 异常、边界、安全 |
| IP 模式与入口隔离不回归 | 部署契约、Compose 重建及运行态请求 | IP 模式 healthy，未知 Host 和内部接口 404；符合预期 | 兼容、权限、回归 |
| 历史业务功能不回归 | Backend、Frontend、Worker 完整套件 | 502 项业务与前端自动化测试全部通过；符合预期 | 回归、异常、权限 |

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Caddy Go 单元与静态检查 | 固定 Go 1.26.5 容器执行 `gofmt`、`go test`、`go vet` | 12/12 通过，vet 通过 |
| 部署定向测试 | `node --test frontend/test/security-deployment.test.mjs` | 11/11 通过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` | 189/189 通过 |
| 后端完整回归 | 固定 Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 287/287 通过，BUILD SUCCESS |
| Worker 完整回归 | Python 3.12.13 容器安装锁定依赖后执行 pytest | 26/26 通过 |
| Compose 与 Shell 校验 | `docker compose config --quiet`、`sh -n caddy/caddy-entrypoint.sh`、`git diff --check` | 全部通过 |
| Compose 统一重建 | `docker compose up --build -d` | 四镜像成功构建，四服务 healthy |
| 双证书 SNI | 两张临时自签证书、三个域名及 curl `--cacert --resolve` | 三个 HTTPS 请求均 200，且各自证书校验正确 |
| HTTP 和安全边界 | 两个允许 Host 跳转、未知 Host、内部接口 | 结果依次为 308、308、404、404 |
| 临时包权限 | 容器内检查 `/tmp/base-ai-https-tls/*.pem` | 两个文件均为 0600 |
| 测试资源清理 | 停止测试容器并删除 `/tmp/base-ai-multi-cert-test` | 临时 YAML、证书、私钥和容器全部清理 |

### 测试过程问题与处理

- 本次需求澄清了“每个 domains 分组对应一对证书/私钥”，因此用向前提交替代上一版全局证书模型，没有改写已存在的提交历史。
- 实现过程中另一个已授权任务并发提交了 trace 配置迁移及其测试报告；本任务使用路径限定提交，未夹带或覆盖其改动，最终 Compose 重建同时覆盖两边工作树并通过。
- 宿主机未安装 Maven，使用项目固定的 Maven 3.9.9 / Java 17 容器完成 287 项后端测试。
- 临时运行态证书仅用于本地 SNI 验收，没有修改 `.env`、系统信任库、业务数据或 Caddy 命名卷，测试结束后已清理。

### 重测触发条件

- 修改 `sites` schema、域名分组、证书字段、路径规范化、SAN/密钥校验或临时 PEM 生成。
- 修改 `APP_HTTPS_SITES_FILE`、`TLS_CERTS_DIR`、Compose 挂载、Caddy TLS loader 或 SNI 行为。
- 修改 Host 白名单、HTTP 跳转、IP 学习模式、内部接口边界或 Caddy 证书生命周期。
- 修改 Backend 业务代码、认证授权、Controller、Service、Repository、Domain 或核心配置。

### 已知问题与限制

- 未使用真实公网 DNS、生产 CA 证书或外部负载均衡器；运行态使用两张本地自签证书完成等价 SNI 和 TLS 校验。
- YAML 或宿主机证书变化不会自动热加载；修改后必须执行 `docker compose restart caddy`，启动流程会重新验证全部分组。
- YAML 的 `domains` 不接受通配符，但某组证书可以使用能够覆盖该组具体域名的通配符 SAN。
- Caddy 使用的合并 PEM 位于容器临时目录，权限为 0600，容器重建后会由只读挂载源重新生成。
- 前端生产构建仍有主包超过 500 kB 的既有性能告警，与本次入口改造无关。

### 下次测试建议

1. 在目标 Linux 主机使用真实多 SAN/通配符证书，验证 UID 10001 的目录遍历权限、证书续期替换和重启加载。
2. 使用真实 DNS 和独立客户端验证多域名 SNI、HTTP/2、HTTP/3、防火墙及外部 80/443。
3. 如需自动热加载，需另行设计文件监听、全组原子验证、失败回滚和不中断 reload。

### 回滚方式

- 回滚实现提交 `1a2f15d`，恢复上一版单证书 YAML 模型，并重新执行 `docker compose up --build -d`。
- 回滚前将 `APP_HTTPS_SITES_FILE`、`TLS_CERTS_DIR` 转换回上一版 `APP_DOMAIN_FILE`、`TLS_CERT_FILE`、`TLS_KEY_FILE`。
- 本次无数据库迁移和业务数据修改；Caddy data/config 命名卷可以保留。

---

## 📋 任务追踪排除配置迁移测试结果（2026-08-06）

### Git 基准点

Commit: be5c89e41204529d28a16b57ff8278c8e38c8344
- 提交说明: Relocate trace tracking configuration
- 测试日期: 2026-08-06
- 分支: master
- 上一测试基准点: `92d65da884384c51dfeb64715142d95c5c7fa537`

### 变更范围

- 默认任务追踪排除配置从仓库根目录迁移到 `backend/config/trace-tracking-exclusions.yml`。
- Compose 默认宿主机挂载源同步到新位置，容器内 `/app/config/trace-tracking-exclusions.yml` 和 `TRACE_TRACKING_EXCLUSIONS_FILE` 外部覆盖能力保持不变。
- 中英文文档同步更新配置说明和仓库结构。
- 本地未跟踪 `.env` 的旧相对路径覆盖同步到新位置，仅用于本机重建，不纳入 Git 提交。
- 未修改配置内容、`backend/src/main/java/`、数据库结构、业务逻辑、前端业务逻辑或 Python Worker。

### 测试执行结果

- 自动化测试：189 个唯一前端用例全部通过（100%），失败 0，错误 0，跳过 0；其中部署定向套件 11 个用例先独立执行，再随完整套件复测通过。
- Compose 静态解析：通过；Backend 挂载源解析为 `backend/config/trace-tracking-exclusions.yml`。
- 容器挂载验证：容器内配置可读，且与宿主机新位置文件的 SHA-256 一致。
- Docker Compose：`docker compose up --build -d` 完整构建和启动成功，Backend、Python Worker、Frontend、Caddy 全部 healthy。
- 后端 Maven、Python Worker 和 Caddy Go 独立测试本次未执行；本次没有修改对应业务代码，且 Docker 构建和运行态健康检查已覆盖配置装载。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 配置不再位于仓库顶层 | 文件结构与 Git rename 检查；迁移提交 | 根目录文件删除，新文件位于 Backend 配置目录；符合预期 | 正常、结构 |
| Compose 默认使用新位置 | Compose 静态解析；本地环境变量同步为新相对路径 | Backend 挂载源解析到新文件；符合预期 | 正常、集成 |
| 容器继续使用原内部路径 | 运行态挂载检查；读取容器内配置并比对 SHA-256 | 内部路径可读且内容一致；符合预期 | 兼容、回归 |
| 外部路径覆盖能力不变 | 配置审查；保留 `TRACE_TRACKING_EXCLUSIONS_FILE` 插值 | 自定义宿主机文件仍可覆盖默认源；符合预期 | 兼容 |
| 配置迁移不影响服务启动 | Compose 完整重建和健康检查 | 四个服务均构建成功并进入 healthy；符合预期 | 集成、回归 |
| 部署及前端历史行为不回归 | 部署定向测试和前端完整套件 | 11/11、189/189 通过；符合预期 | 回归、安全 |

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Compose 静态解析 | `docker compose config --quiet` 及 JSON 挂载源断言 | 通过，挂载源为新位置 |
| 部署定向测试 | `node --test frontend/test/security-deployment.test.mjs` | 11/11 通过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` | 189/189 通过 |
| Compose 统一重建 | `docker compose up --build -d` | 四镜像构建成功，四服务 healthy |
| 运行态挂载 | 容器内可读性检查及宿主机/容器 SHA-256 比对 | 通过，内容一致 |
| 差异格式 | `git diff --check`、`git diff --cached --check` | 通过 |

### 测试过程问题与处理

- 首次 Compose 挂载源断言失败：本地未跟踪 `.env` 仍显式覆盖旧根目录相对路径。将该本地覆盖同步为新位置后，Compose 静态解析、定向测试、完整测试、重建和挂载校验全部通过；仓库默认配置本身无解析错误。
- Docker 前端构建继续报告既有的 runtime-config、第三方 PURE 注释和主包超过 500 kB 警告，不影响构建成功或服务健康。
- 未创建临时测试或调试文件，无需额外清理。

### 重测触发条件

- 修改 `backend/config/trace-tracking-exclusions.yml` 的默认排除方法、排除路径或配置结构。
- 修改 Compose 的 `TRACE_TRACKING_EXCLUSIONS_FILE` 插值、宿主机挂载源或容器内配置路径。
- 修改 Spring 配置导入路径、`TraceTrackingPolicy` 或 `@TraceIgnored` 使用范围。
- 用户明确要求重新执行完整覆盖测试。

### 已知问题与限制

- 生产环境若显式配置了旧仓库根目录相对路径，需要在部署环境中同步更新；绝对外部配置路径不受影响。
- 本次没有改变排除规则内容，因此没有重新执行后端任务追踪业务测试；最近业务基准中的后端覆盖结论保持不变。

### 下次测试建议

1. 后续修改排除规则内容时，执行后端任务追踪定向测试和 Maven 完整测试，并覆盖正常、忽略、异常和权限场景。
2. 发布前检查目标环境的 `TRACE_TRACKING_EXCLUSIONS_FILE` 是否仍引用旧仓库相对位置。

### 回滚方式

- 回滚提交 `be5c89e`，恢复根目录配置和 Compose 默认挂载路径，然后执行 `docker compose up --build -d`。
- 本次没有数据库迁移或业务数据修改，无需数据回滚。

---

## 📋 YAML 多域名 HTTPS 入口测试结果（2026-08-06）

### Git 基准点

Commit: 92d65da884384c51dfeb64715142d95c5c7fa537
- 提交说明: Support YAML domain lists for HTTPS ingress
- 测试日期: 2026-08-06
- 分支: master
- 上一测试基准点: `9fa8f2e8031572e94342dc5a164dda7498a77198`

### 变更范围

- 域名证书模式由单值 `APP_DOMAIN` 改为 `APP_DOMAIN_FILE`，通过只读挂载的 YAML 文件维护一个或多个域名；原 `APP_DOMAIN` 配置入口已移除。
- YAML 使用固定的 `gopkg.in/yaml.v3 v3.0.1` 完整解析器，schema 只接受顶层 `domains` 字符串列表；支持块/行内列表、引号、注释和锚点。
- 域名统一转为小写并去重，逐项拒绝协议、端口、路径、通配符、非 ASCII、非法标签和隐式数字/布尔值；同时限制单文件 64 KiB、最多 256 项、仅一个 YAML 文档。
- Caddy 为全部规范化域名生成 HTTPS site 地址及 HTTP Host 白名单；每个允许域名均重定向到自身 HTTPS 地址，未知 Host 和内部接口继续返回 404。
- Compose 新增域名清单只读挂载和 IP 模式占位文件；域名清单、完整证书链和私钥仍必须同时配置，一张证书必须覆盖清单内全部域名。
- 未修改 `backend/src/main/java/`、数据库结构、业务数据或前端业务行为。

### 测试执行结果

- 自动化测试合计：514/514 通过（100%），失败 0，错误 0，跳过 0。
- 后端完整测试：287/287 通过，Maven BUILD SUCCESS。
- 前端完整测试：189/189 通过，其中 Caddy 部署契约 11/11 通过。
- Python Worker 完整测试：26/26 通过，使用 Python 3.12.13 和锁定开发依赖。
- Caddy Go 单元测试：12/12 通过，Go vet 通过。
- Docker Compose：`docker compose up --build -d` 完整构建成功，Backend、Python Worker、Frontend、Caddy 全部 healthy。
- 双域名运行态：`ai.test` 与 `api.test` 的 HTTPS 均返回 200，HTTP 均返回保留路径和查询参数的 308；未知 Host 与内部接口均返回 404。

### 关键模块测试

- YAML 域名解析：3 个新增 Go 用例覆盖完整 YAML 语法、锚点、大小写规范化、重复项、未知字段、多文档、非字符串标量、非法域名、文件大小和条目数量限制；Caddy Go 合计 12/12 通过。
- Caddy 入口契约：11/11 通过，覆盖 `APP_DOMAIN` 移除、YAML 挂载、多域名转换、缺失/空白/解析失败配置、IP 模式兼容、端口和安全边界。
- 运行态入口：临时双 SAN 证书和含大小写重复项的 YAML 清单验证两个域名的 TLS、HTTP 跳转、Host 白名单及内部接口隔离。
- 完整回归：Backend 287/287、Frontend 189/189、Python Worker 26/26，无历史功能回归。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| YAML 维护一个或多个域名 | Go 单元、入口契约；块列表、引号、锚点、单域名和双域名 | 完整解析并输出稳定域名顺序；符合预期 | 正常、兼容 |
| 全部域名同时响应 | 运行态；双 SAN 临时证书，`ai.test`、`api.test` | 两个 HTTPS 健康检查均 200；符合预期 | 正常、集成 |
| HTTP 按请求域名安全跳转 | 运行态；两个域名请求 `/probe?x=1` | 均返回 308，目标保留对应 Host、路径和查询；符合预期 | 正常、安全 |
| 域名规范化和去重 | Go 单元及运行态；`AI.TEST`、`ai.test`、`api.test` | 解析为 `ai.test api.test`，无重复 site；符合预期 | 边界、兼容 |
| 非法 YAML 和域名拒绝启动 | 参数化 Go/入口测试；空列表、未知字段、多文档、数字、通配符、URL、端口、非法标签 | 全部失败并返回明确错误；符合预期 | 异常、安全 |
| 资源上限有效 | Go 单元；超过 64 KiB 和超过 256 项 | 均拒绝解析；符合预期 | 边界、安全 |
| 域名配置必须完整 | 入口契约；仅配置清单路径或缺失挂载文件 | 拒绝启动，不降级到 IP 模式；符合预期 | 异常、安全 |
| 未知 Host 与内部接口保持隔离 | 运行态；未知域名及 `/api/internal/health` | 均返回 404；符合预期 | 权限、安全、回归 |
| IP 模式保持兼容 | 入口契约及 Compose 重建；域名三项留空 | localhost、127.0.0.1 和已学习 IP 行为不变；符合预期 | 兼容、回归 |
| 历史业务功能不回归 | Backend、Frontend、Worker 完整套件 | 502 项业务与前端自动化测试全部通过；符合预期 | 回归、异常、权限 |

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Caddy Go 单元与静态检查 | 固定 Go 1.26.5 容器执行 `gofmt`、`go test`、`go vet` | 12/12 通过，vet 通过 |
| 部署定向测试 | `node --test frontend/test/security-deployment.test.mjs` | 11/11 通过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` | 189/189 通过 |
| 后端完整回归 | 固定 Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 287/287 通过，BUILD SUCCESS |
| Worker 完整回归 | Python 3.12.13 容器安装 `requirements-dev.txt` 后执行 pytest | 26/26 通过 |
| Compose 静态与 Shell 校验 | `docker compose config --quiet`、`sh -n caddy/caddy-entrypoint.sh`、`git diff --check` | 全部通过 |
| Compose 统一重建 | `docker compose up --build -d` | 首次因 80/443 被占用失败；停止占用容器后重试成功，四服务 healthy |
| YAML 解析运行态 | 含 `AI.TEST`、`api.test`、`ai.test` 的完整 YAML | 输出 `ai.test api.test`；符合预期 |
| 双域名 TLS | 临时双 SAN 自签证书及 curl `--cacert --resolve` | 两个域名 HTTPS 均 200 |
| HTTP 跳转和入口隔离 | 两个允许域名、未知 Host、内部接口 | 结果依次为 308、308、404、404 |
| 测试资源清理 | 停止临时容器并删除 `/tmp/base-ai-domain-runtime-test` | 临时容器、YAML、证书和私钥均已清理 |

### 测试过程问题与处理

- 宿主机未安装 Maven，按既有固定版本改用 Maven 3.9.9 / Java 17 容器运行完整后端测试，287/287 通过。
- YAML 库会把未加引号的数字标量转换成字符串；首次 Go 测试通过 `domains: [42]` 稳定发现该边界。实现改为检查 YAML 节点必须显式为字符串，重跑后 12/12 通过。
- 首次 Compose 启动因 `domestic-trade-caddy` 占用宿主机 80/443 失败；按仓库规则停止该容器后重新执行完整命令，四个本项目服务全部 healthy。该外部容器当前保持停止，避免再次争用端口。
- 首次两条 HTTP 跳转 curl 命令因 zsh 将未加引号 URL 中的 `?` 解析为通配符而未发出请求；修正 URL 引号后两项均返回预期 308，不属于服务失败。
- 临时运行态测试使用自签双 SAN 证书，没有改动 `.env`、系统信任库、业务数据或 Caddy 命名卷；测试文件及容器已清理。

### 重测触发条件

- 修改 `APP_DOMAIN_FILE` schema、YAML 解析依赖、域名规范化/校验、资源上限或错误处理。
- 修改 Caddyfile、入口脚本、Compose 域名/证书挂载、Host 白名单、HTTPS site 或 HTTP 跳转行为。
- 修改证书格式、域名清单加载/重启流程、IP 学习模式或入口安全边界。
- 修改 `backend/src/main/java/`、Controller、Service、Repository、Domain、认证授权或核心业务配置。

### 已知问题

- 未使用真实公网 DNS、生产 CA 证书、外部负载均衡器或浏览器执行验收；运行态使用两个本地域名和临时双 SAN 自签证书完成等价 TLS/路由验证。
- YAML 或证书文件变化不会自动热加载，维护后必须执行 `docker compose restart caddy`。
- YAML 域名项不接受通配符；证书本身可以使用覆盖全部具体清单域名的 SAN 或通配符证书。
- 前端生产构建仍提示主包超过 500 kB，该既有性能告警与本次入口变更无关。

### 下次测试建议

1. 在目标 Linux 主机使用真实域名和生产多 SAN/通配符证书，验证文件 ACL、DNS、外部 80/443、防火墙及续期重启流程。
2. 增加受控浏览器 E2E，覆盖两个域名的登录 Cookie、CSRF、HSTS 和跨域名会话隔离预期。
3. 如未来需要自动加载 YAML 或证书变化，设计文件监听、失败回滚和不中断 reload 测试后再扩展。

### 回滚方式

- 回滚实现提交 `92d65da`，恢复 `APP_DOMAIN` 单域名配置，并执行 `docker compose up --build -d`。
- 回滚前将部署环境从 `APP_DOMAIN_FILE` 改回单个 `APP_DOMAIN`；证书和私钥路径保持不变。
- 本次没有数据库迁移、数据回填或业务数据修改，无需数据库逆向处理；Caddy data/config 命名卷可以保留。

---

## 📋 请求驱动 IP 证书签发测试结果（2026-08-06）

### Git 基准点

Commit: 9fa8f2e8031572e94342dc5a164dda7498a77198
- 提交说明: Implement request-driven IP certificates
- 测试日期: 2026-08-06
- 分支: master
- 上一测试基准点: `3d44128efeed8c1ebc009125baee9de991e66abe`

### 变更范围

- IP 模式不再配置或探测宿主机 IP；删除宿主机跟踪脚本、`.runtime` 挂载及 macOS/Linux 网络命令分支。
- 新 IPv4 首次通过 HTTP 访问时，由 Caddy 容器回环签发服务校验 Host、更新内部 CA 多 SAN 证书、持久化已学习地址、强制热加载并返回 HTTPS 308。
- 学习服务支持规范回环、内网和公网 IPv4，拒绝域名、IPv6、前导零、越界、未指定、链路本地和组播地址；只接受 GET/HEAD。
- 新地址签发使用互斥锁合并并发请求，默认最短间隔 5 秒、最多学习 32 个地址；超限返回 429。
- 证书、私钥、地址状态和 Caddy reload 采用失败回滚；后台每小时检查证书续期，运行状态全部保存在既有 Caddy data 卷。
- 域名证书模式继续要求域名、完整链和私钥同时配置，未知 IP 不会触发签发。

### 测试执行结果

- 自动化测试合计：510/510 通过（100%），失败 0，错误 0，跳过 0。
- 后端完整测试：287/287 通过，Maven 无缓存构建成功。
- 前端完整测试：188/188 通过，其中 Caddy 部署契约 10/10 通过。
- Python Worker 完整测试：26/26 通过，使用 Python 3.12.13 和锁定开发依赖。
- Caddy Go 单元测试：9/9 通过，Go vet 通过。
- Docker Compose：`docker compose up --build -d` 成功，四个服务全部 healthy。
- IP 运行态：首次 HTTP 308、证书 SAN 热更新、受根 CA 验证的 HTTPS 200、重启持久化、非法 Host 404、POST 405、签发限流 429、内部接口 404 均符合预期。
- 域名运行态：临时域名证书容器启动成功，Caddy 和回环签发服务健康，未知 IP 返回 404。

### 关键模块测试

- Caddy 请求驱动签发：5 个新增 Go 用例覆盖地址分类、首次签发、并发合并、reload 回滚、域名模式、非法方法和地址上限；连同原证书生命周期用例共 9/9 通过。
- Caddy 入口契约：10/10 通过，覆盖无宿主机探测、数据卷状态、模式切换、资源限制、端口、内部接口和非 root 权限。
- Backend：287/287 通过，Controller、Service、Repository、认证授权、健康检查和历史回归无异常。
- Frontend：188/188 通过，页面、路由、API 客户端、会话和部署契约无回归。
- Python Worker：26/26 通过，LLM、邮件投递和链路上下文无回归。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 不配置 IP 且不依赖宿主操作系统 | 部署契约；检查 Compose、入口脚本和已删除跟踪脚本 | 无 IP 配置、`.runtime`、`uname`、`route`、`ifconfig` 或 `ip` 调用；符合预期 | 配置、兼容、安全 |
| 新 IP 首次 HTTP 自动签发 | Go 单元及运行态；未学习 `127.0.0.2` 请求带路径和查询参数 | 签发后返回 HTTPS 308，Location 保留路径和查询；符合预期 | 正常、集成 |
| 签发后 HTTPS 可用 | 运行态；根 CA、`127.0.0.2` URL、TCP 定向至发布端口 | TLS 校验通过，liveness 返回 200；符合预期 | 正常、安全 |
| 回环、内网和公网 IPv4 可学习 | 参数化 Go 单元；`127.0.0.2`、`10.20.30.40`、`192.168.1.20`、`8.8.8.8` | 均解析为规范 IPv4；符合预期 | 正常、边界 |
| 非法 Host 不触发签发 | Go 单元及运行态；域名、IPv6、前导零、越界、未指定、链路本地和组播 | 单元全部拒绝，运行态域名返回 404且状态不变；符合预期 | 异常、安全 |
| 仅安全方法触发签发 | Go 单元及运行态；未知 IP 的 POST | 返回 405，不签发；符合预期 | 异常、安全 |
| 并发访问只签发一次 | Go 单元；12 个并发 GET 请求同一新 IP | 全部 308，仅调用一次 reload；符合预期 | 并发、回归 |
| 资源限制生效 | Go 单元地址上限；运行态连续访问两个新 IP | 超过上限或冷却期返回 429，已有地址继续有效；符合预期 | 边界、安全 |
| reload 失败自动回滚 | Go 单元；注入管理接口失败 | 返回 503，学习文件不存在且证书指纹保持原值；符合预期 | 异常、故障恢复 |
| 地址跨重启持久化 | 运行态；签发 `127.0.0.2` 后重启 Caddy | 状态文件仍包含地址，HTTPS 继续返回 200；符合预期 | 兼容、回归 |
| 测试状态可恢复基线 | 运行态；移除测试学习状态并重启 | SAN 恢复为 `localhost`、`127.0.0.1`，四服务 healthy；符合预期 | 清理、回归 |
| 域名模式保持隔离 | 临时容器；完整域名证书和未知 IP Host | 域名模式正常启动，内部服务健康，未知 IP 404；符合预期 | 兼容、安全 |
| 内部接口继续隔离 | 运行态；学习地址请求 `/api/internal/health` | 返回 404；符合预期 | 权限、安全、回归 |
| 历史业务功能不回归 | 后端、前端和 Worker 完整套件 | 501 项业务与前端自动化测试全部通过；符合预期 | 回归、异常、权限 |

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Go 单元与静态检查 | 固定 Go 1.26.5 容器执行 `go test`、`go vet` | 9/9 通过，vet 通过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` | 188/188 通过 |
| 后端完整回归 | `docker compose build --no-cache backend`，构建阶段执行 Maven package | 287/287 通过，BUILD SUCCESS |
| Worker 完整回归 | Python 3.12.13 一次性容器安装 `requirements-dev.txt` 后执行 pytest | 26/26 通过 |
| Compose 静态与 Shell 校验 | `docker compose config --quiet`、`sh -n caddy/caddy-entrypoint.sh`、`git diff --check` | 全部通过 |
| Compose 统一重建 | `docker compose up --build -d` | 四镜像构建成功，四服务 healthy |
| 首次 HTTP 签发 | 未学习 Host 请求 `/probe?x=1` | 308，Location 为对应 HTTPS 且保留 URI |
| TLS 与 SAN | 临时导出根 CA，curl 验证 HTTPS；LibreSSL 文本查看 SAN | HTTPS 200；SAN 包含 localhost、127.0.0.1 和学习地址 |
| 重启持久化 | 重启 Caddy，读取状态并再次验证 HTTPS | 地址保留，HTTPS 200 |
| 安全边界 | 域名 Host、POST、连续新地址、内部接口 | 状态依次为 404、405、308/429、404 |
| 域名模式 | 临时证书和临时容器启动，检查两项健康和未知 IP | 两项健康均 OK，未知 IP 404；临时资源已清理 |
| 测试状态清理 | 删除测试学习文件并重启 Caddy | SAN 恢复基线，未遗留临时证书、容器或调试文件 |
| 提交前检查 | `git status --short --untracked-files=all`、暂存 diff、空白检查 | 仅包含确认范围内文件 |

### 测试过程问题与处理

- 宿主机未安装 `gofmt`，首次宿主命令未执行测试；随后使用 Dockerfile 固定的 Go 1.26.5 镜像完成格式化、9/9 单元测试和 vet。
- Alpine 固定 Go 镜像未启用 CGO，`go test -race` 无法执行；项目原测试范围不包含 race，普通并发用例和互斥行为已通过，后续可在带 C 编译器的专用 CI 增加 race 检查。
- 首次 `docker compose run caddy validate` 因已有固定 IP Caddy 容器占用地址而未启动；随后通过独立镜像适配、完整 Compose 重建和运行态请求完成等价且更完整的验证。
- 宿主 LibreSSL 不支持 `openssl x509 -ext`，改用 `openssl x509 -text` 验证 SAN；TLS 信任同时由 curl `--cacert` 成功确认。

### 重测触发条件

- 修改 Caddyfile、请求学习服务、证书辅助程序、入口脚本、PKI 生命周期、限流、地址状态或管理接口。
- 修改 HTTP/HTTPS 端口、Compose 数据卷、健康检查、域名/IP模式选择或证书挂载。
- 修改 `backend/src/main/java/`、Controller、Service、Repository、Domain 或核心业务配置。
- 修改前端会话/API访问方式或 Python Worker 通信链路。

### 已知问题与限制

- IPv4 的首次访问必须使用 HTTP；TLS 标准不通过 SNI提供 IP 字面量，因此未学习地址无法首次直接使用 HTTPS。
- HTTP Host 可由非浏览器客户端伪造；地址上限、冷却、并发合并和回滚只能限制资源消耗，不构成 IP 所有权证明。
- 未使用真实内网、公网或 NAT 映射地址进行外部客户端验证；地址分类由参数化单元测试覆盖，运行态使用回环地址完成等价签发和 TLS 流程。
- 未把根 CA 安装到浏览器或宿主系统信任库；TLS 使用临时导出的公开根证书和 curl 验证，文件已清理。
- IPv6 不在本次范围内；IPv6 Host 明确拒绝，不会触发签发。
- 未执行 Go race 检查，原因是固定 Alpine 构建镜像未启用 CGO；并发功能测试已执行。
- 前端生产构建继续提示主包超过 500 kB，该既有性能问题与本次入口修改无关。

### 下次测试建议

1. 在目标网络使用真实内网、公网和 NAT 映射 IPv4，从独立客户端完成首次 HTTP、根 CA 信任和浏览器 HTTPS 验证。
2. 在带 CGO 和 C 编译器的固定 Go CI 镜像增加 `go test -race`。
3. 增加容器故障注入集成测试，实际阻断管理端点和数据卷写入，验证 503 及运行中旧证书保持可用。
4. 评估是否需要受控的地址清理管理接口；当前达到上限后按安全设计拒绝新地址，不自动淘汰旧地址。

### 回滚方式

- 回滚实现提交 `9fa8f2e` 的前一个版本并执行 `docker compose up --build -d`，可恢复宿主机地址跟踪方案。
- Caddy data/config 命名卷可以保留；旧实现会按其入口逻辑重新签发叶证书，根 CA 不变。
- 本次没有数据库迁移、业务数据回填或第三方依赖变更，无需数据库逆向操作。

---

## 📋 自适应 HTTPS 与双 IP 多 SAN 入口测试结果（2026-08-06）

### Git 基准点

Commit: 3d44128efeed8c1ebc009125baee9de991e66abe
- 提交说明: Add adaptive HTTPS ingress modes
- 测试日期: 2026-08-06
- 分支: master
- 上一测试基准点: `370b4b8cbc001087bb865e2a1404c2a5cf692176`

### 变更范围

- Caddy 支持域名证书与 IP 内部 CA 两种互斥模式；域名、完整证书链和私钥必须同时配置，部分配置会拒绝启动。
- IP 模式支持同时配置公网与内网 IPv4，由 Caddy 持久化内部 CA 签发一张覆盖全部 IP SAN 的证书，并通过默认 SNI 兼容不发送 SNI 的 IP 客户端。
- 新增 Go 1.26 标准库证书辅助程序：校验 Caddy 根/中间 CA、规范化和去重 IPv4、签发 ECDSA 多 SAN 证书、复用有效证书，并在 SAN、签发者或有效期变化时续期。
- IP 证书每小时检查一次，目标有效期 30 天、提前 48 小时续期；续期后通过仅监听容器回环地址的 Caddy 管理接口强制热加载。域名模式继续关闭管理接口。
- Compose 发布默认 81/444 TCP 与 444 UDP，持久化 Caddy data/config，默认启用 Secure Cookie；HTTP 仅对允许的 Host 执行 308 跳转，未知 Host 返回 404。
- 标准 80/443 模式启用一年 HSTS；非标准端口不发送 HSTS，避免浏览器把 HTTP 非标准端口升级到错误的 HTTPS 端口。
- 中英文文档补充域名/IP 配置、双 IP、证书权限、根 CA 导出安装、NAT、证书续期和命名卷生命周期说明。

### 测试执行结果

- 自动化测试合计：503/503 通过（100%），失败 0，错误 0，跳过 0。
- 后端完整测试：287/287 通过；`SessionCookieServiceTest` 4/4 通过，覆盖默认与显式 Secure Cookie 分支。
- 前端完整测试：186/186 通过；部署契约测试 8/8 通过，较上一基准新增域名/IP模式解析与非法配置测试。
- Python Worker 完整测试：26/26 通过，使用 Python 3.12.13 与锁定开发依赖。
- Caddy IP 证书辅助程序：4/4 Go 单元测试通过，Go vet 通过。
- Docker Compose：`docker compose up --build -d` 成功；Backend、Python Worker、Frontend、Caddy 全部 healthy。
- IP 运行态：两个 IP 的无 SNI TLS 校验均通过，HTTP 308 保留 Host、路径和查询参数，内部接口 404，未知 Host 404。
- 证书生命周期：强制续期后磁盘证书指纹变化；强制 reload 前服务保持旧证书，reload 后切换新证书；普通 Caddy 重建前后根 CA 文件及 SHA-256 指纹一致。
- 域名运行态：临时 `base-ai.test` 完整证书和私钥加载成功，HTTPS 200、HTTP 308、内部接口 404，回环管理接口不可访问。
- 标准端口配置：80/443 域名 Caddyfile 校验通过；非标准 81/444 响应确认不包含 HSTS。

### 关键模块测试

- Caddy 多 SAN PKI：4/4，通过双 IP、重复 IP去重、非法地址、稳定复用、强制续期、临期续期、SAN 变化和中间 CA轮换测试。
- Caddy 入口契约：8/8，通过端口发布、CA 持久化、Host 白名单、TLS 模式切换、配置拒绝、内部接口隔离和非 root 权限检查。
- Security/会话 Cookie：4/4 定向通过，后端完整 Security 回归全部通过。
- Backend：287/287，通过 Controller、Service、Repository、认证授权、健康检查和历史回归。
- Frontend：186/186，通过页面、路由、API 客户端、Cookie 会话和部署契约回归。
- Python Worker：26/26，通过 LLM、邮件投递和链路上下文回归。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 域名配置完整时使用已有证书 | 运行态集成；`base-ai.test`、临时完整链和私钥 | HTTPS 200，HTTP 308 到同 Host 的 444，内部接口 404；符合预期 | 正常、安全、集成 |
| 域名配置不完整时禁止降级 | 入口契约；分别缺少域名、证书或私钥 | 启动配置解析失败并输出明确错误；符合预期 | 异常、安全 |
| 未配置域名证书时进入 IP 模式 | 入口契约和 Compose；清空域名三项，提供两个 IPv4 | 解析为 IP 模式，使用内部多 SAN 证书；符合预期 | 正常、兼容 |
| 公网与内网 IP 同时支持无 SNI HTTPS | 运行态集成；`127.0.0.1`、`127.0.0.2`，宿主机 LibreSSL/curl 不发送 IP SNI | 两个 URL 均使用受根 CA验证的同一张证书并返回 200；符合预期 | 正常、边界、兼容 |
| 多 SAN 证书只包含配置地址 | Go 单元及运行态 OpenSSL；双 IP和重复 IP | SAN 精确包含两个去重 IPv4，无额外 DNS/IP；符合预期 | 边界、安全 |
| 非法 IPv4 和端口被拒绝 | 参数化入口测试；越界、前导零、IPv6、主机名、0/65536 端口 | 均失败且不生成有效入口配置；符合预期 | 异常、安全 |
| 证书自动复用与续期 | Go 单元；未变化、强制、剩余不足 48 小时、SAN/中间 CA变化 | 稳定配置复用；四类变化重新签发；符合预期 | 正常、边界、回归 |
| 续期后热加载且不中断旧证书 | 运行态指纹；强制签发后执行回环管理 reload | reload 前呈现旧指纹，reload 后呈现新指纹，服务持续返回 200；符合预期 | 集成、故障恢复 |
| 普通重建不更换根 CA | 运行态；导出根 CA、强制重建 Caddy、再次导出并比较 | 文件完全一致、SHA-256 指纹一致，双 IP仍返回 200；符合预期 | 兼容、回归 |
| HTTP 仅跳转允许的 Host | 运行态 curl；两个配置 IP、临时域名和未知 Host | 已配置 Host 308 并保留路径/查询，未知 Host 404；符合预期 | 正常、安全 |
| Secure Cookie 默认开启 | Compose 契约和后端 Cookie 测试 | Compose 默认 true，会话与 CSRF Cookie Secure 分支通过；符合预期 | 安全、兼容 |
| 内部接口继续隔离 | 域名与双 IP运行态请求 `/api/internal/health` | 均返回 404，公开健康接口返回 200；符合预期 | 权限、安全、回归 |
| 历史功能不回归 | 后端、前端、Worker 完整测试 | 499 项历史与部署测试全部通过；符合预期 | 回归、异常、边界、权限 |

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Compose 静态校验 | 双 IP变量执行 `docker compose config --quiet` | 通过 |
| Caddy Go 单元测试 | Go 1.26.5 容器执行 `go test ip-cert-helper.go ip-cert-helper_test.go` | 4/4 通过 |
| Caddy Go 静态检查 | Go 1.26.5 容器执行 `go vet ip-cert-helper.go ip-cert-helper_test.go` | 通过 |
| Shell 与空白检查 | `sh -n caddy/caddy-entrypoint.sh`、`git diff --check` | 通过 |
| 后端完整回归 | `docker compose build --no-cache backend` 中 Maven 3.9.9 / Java 17 执行 `mvn -B -ntp package` | 287/287 通过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` | 186/186 通过 |
| Worker 完整回归 | Python 3.12.13 临时容器安装锁定开发依赖后执行 `python -m pytest -p no:cacheprovider` | 26/26 通过 |
| Compose 统一重建 | 双 IP、81/444 环境执行 `docker compose up --build -d` | 四镜像构建成功，四服务 healthy |
| IP Caddy 配置校验 | 经入口脚本执行 `caddy validate` | 多 SAN证书复用，配置有效 |
| 双 IP TLS | 根 CA `--cacert`，第二 IP使用 `--resolve` 绕过 Docker Desktop 回环转发限制 | 两个 IP均 TLS 验证通过并返回 200 |
| HTTP 和入口隔离 | curl 请求两个 IP的 HTTP、未知 Host和 `/api/internal/health` | 308、308、404、404，路径与查询参数保留 |
| 热加载 | 强制签发、比较磁盘/服务指纹、`caddy reload --force`、再次比较 | 服务证书切换到新指纹，第二 IP继续 200 |
| CA 持久化 | 重建前后导出根 CA并执行文件比较和 SHA-256 指纹检查 | 完全一致 |
| 域名模式 | 临时 RSA 证书、`base-ai.test` 和 `--resolve` 运行态请求 | HTTPS 200、HTTP 308、内部接口 404、管理接口关闭 |
| 标准端口 HSTS配置 | 临时域名证书以 80/443 执行 Caddy validate | 配置有效；非标准运行态无 HSTS |
| 提交前检查 | `git status --short`、暂存 diff、文件范围检查 | 仅包含当前任务文件，无调试文件或真实证书 |

### 重测触发条件

- 修改 Caddyfile、证书辅助程序、入口脚本、PKI生命周期、HTTP/HTTPS端口、Host 跳转或根 CA数据卷。
- 修改域名/IP环境变量规则、Compose 证书挂载、Secure Cookie 默认值或代理边界。
- 修改 `backend/src/main/java/`、认证会话、Controller、Service、Repository、Domain 或核心业务配置。
- 修改前端 Cookie 会话客户端、开放 API访问地址或 Python Worker通信路径。

### 已知问题与限制

- 未使用真实公网/内网地址、真实公网 DNS、生产证书、防火墙或 NAT执行外部网络验证；双 IP通过两个回环测试地址和证书 SAN完成等价验证。
- Docker Desktop 不直接转发 `127.0.0.2` 的发布端口，因此第二 IP运行态通过 curl `--resolve` 保留 URL/Host/证书校验目标，同时把 TCP连接定向到 `127.0.0.1`；Linux 实机仍建议使用真实双网卡地址复测。
- 未把测试根 CA安装到宿主机系统或浏览器信任库；TLS 信任使用 curl `--cacert` 验证，避免测试修改宿主机安全状态。
- 域名模式使用短期自签测试证书，没有使用用户的真实域名证书；完整链加载、Host 路由和管理接口边界已验证。
- 当前命名卷中若存在旧 7 天中间 CA，会继续使用到 Caddy 自动轮换；叶证书有效期自动限制在中间 CA剩余有效期内，轮换后辅助程序会检测签发者变化并重新签发。
- 本地 `.env` 未被修改；当前运行服务通过命令行覆盖使用双回环 IP和 81/444。正式部署需把真实域名/证书或公网/内网 IP持久化到部署环境。
- 直接启动的 Maven 临时容器因未配置项目 Maven 镜像停在项目扫描阶段，已终止；随后使用 Docker 构建中配置的镜像完成 287/287，不存在未验证的后端用例。
- 前端生产构建仍提示主包超过 500 kB，该既有性能问题不影响本次 HTTPS 验收。

### 下次测试建议

1. 在目标 Linux 主机配置真实公网与内网 IPv4，验证双网卡、路由器端口转发、安全组及外部客户端访问。
2. 在受控客户端实际安装 Caddy 根 CA，增加 Chrome、Edge、Safari 和 Firefox 浏览器登录 E2E。
3. 使用真实生产域名证书验证证书权限、续期文件替换和 Caddy 重启加载。
4. 通过时间加速或专用测试环境验证一小时后台检查、中间 CA自动轮换和失败重试日志告警。

### 回滚方式

- 回滚实现提交 `3d44128`，恢复纯 HTTP Caddy入口后执行 `docker compose up --build -d`。
- Caddy data/config 命名卷可保留，回滚后的纯 HTTP模式不会读取内部 CA；如需删除命名卷会导致根 CA永久变化，必须另行确认并通知所有客户端。
- 本次没有数据库迁移、数据回填、第三方运行时依赖或业务数据变更，无需数据库逆向处理。

---

## 📋 全站 HTTP 入口测试结果（2026-08-06）

### Git 基准点

Commit: 370b4b8cbc001087bb865e2a1404c2a5cf692176
- 提交说明: Use HTTP ingress for service access
- 测试日期: 2026-08-06
- 分支: master
- 上一测试基准点: `ae3cea9a8692c2ab6917cbe52191eeb3d250107f`

### 变更范围

- Caddy 改为仅监听容器 80 端口，移除 TLS、HTTP 到 HTTPS 跳转、HSTS 以及证书数据卷；保留其他安全响应头、请求体限制、前后端反向代理和 `/api/internal/**` 入口隔离。
- Compose 默认仅发布宿主机 `81 -> 80`，Caddy 健康检查改用 HTTP；后端 8080、前端 8080 和 Worker 8000 继续仅在 Compose 内部网络开放。
- 新增 `APP_SESSION_COOKIE_SECURE` 配置，纯 HTTP 默认值为 `false`；会话 Cookie 继续保留 HttpOnly、SameSite=Strict、Host-only 和签名双提交 CSRF 防护，上游 TLS 终止场景可显式设为 `true`。
- 中英文部署文档和 API Key 调用示例统一使用 `http://localhost:81`，并明确明文 HTTP 仅适用于可信网络。

### 测试执行结果

- 后端完整测试：287/287 通过，失败 0，错误 0，跳过 0。
- 前端完整测试：184/184 通过，失败 0，错误 0，跳过 0。
- Python Worker 完整测试（Python 3.12.13）：26/26 通过，失败 0，错误 0，跳过 0。
- 自动化用例合计：497/497 通过（100%）。
- 后端定向测试：`SessionCookieServiceTest` 4/4 通过；HTTP 默认 Cookie 和显式 Secure Cookie 分支均已覆盖。
- 部署定向测试：`security-deployment.test.mjs` 6/6 通过；覆盖 HTTP 单端口、反向代理、安全响应头和内部接口隔离。
- Docker Compose：完整构建并启动成功；Backend、Python Worker、Frontend、Caddy 全部 healthy，构建阶段再次执行后端 287 项测试并全部通过。
- HTTP 运行态：liveness 和 readiness 均返回 200，无 HTTPS 重定向或 HSTS；公网 `/api/internal/health` 返回 404。
- 跨项目访问：独立默认 bridge 网络中的 Python 3.12 容器通过 `http://host.docker.internal:81/api/open/health/live` 访问成功并返回 200。

### 关键模块测试

- Security/会话 Cookie：4/4 定向通过，完整 Security 回归全部通过。
- Backend：287/287 通过，覆盖 Controller、Service、Repository、认证授权、健康检查和历史回归。
- Frontend：184/184 通过，覆盖部署契约、Cookie 会话客户端、路由及页面回归。
- Python Worker：26/26 通过，覆盖 LLM、邮件投递和链路上下文。
- Deployment：Compose 配置校验、Caddy 配置校验、容器健康、端口发布及跨容器 HTTP 访问全部通过。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 控制台和 API 使用纯 HTTP 且不重定向 | 部署契约、Caddy 校验及运行态 curl；请求 81 端口健康接口 | HTTP 直接返回 200，无 Location、TLS 和 HSTS；符合预期 | 正常、兼容、集成 |
| 仅发布宿主机 81 端口 | Compose 契约及 `docker compose ps` | 仅 Caddy 发布 `81 -> 80`，443 不再发布，内部服务端口不对宿主机开放；符合预期 | 安全、兼容、回归 |
| HTTP 下浏览器会话 Cookie 可用 | 后端单元测试；默认配置签发会话和 CSRF Cookie | Cookie 不含 Secure，仍保留 HttpOnly、SameSite=Strict、路径隔离及 CSRF；符合预期 | 正常、安全、兼容 |
| 上游 TLS 终止仍可启用 Secure Cookie | 后端单元测试；设置 `APP_SESSION_COOKIE_SECURE=true` | 会话与 CSRF Cookie 均携带 Secure；符合预期 | 分支、兼容、安全 |
| 内部接口不因入口改造而暴露 | 部署契约及运行态请求 `/api/internal/health` | Caddy 返回 404，普通公开健康接口返回 200；符合预期 | 权限、安全、回归 |
| 其他 Docker 项目可通过宿主机 HTTP 访问 | 独立 bridge 网络 Python 3.12 容器请求 `host.docker.internal:81` | liveness 返回 200；符合预期 | 集成、兼容 |
| 历史业务功能不回归 | 后端、前端和 Worker 完整测试 | 497 项全部通过；符合预期 | 回归、异常、边界、权限 |

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Compose 静态校验 | `docker compose config --quiet` | 通过 |
| 后端 Cookie 定向测试 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp -Dtest=SessionCookieServiceTest test` | 4/4 通过 |
| 后端完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 287/287 通过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` | 184/184 通过 |
| Worker 完整回归 | Python 3.12.13 容器安装锁定开发依赖后执行 `python -m pytest -p no:cacheprovider` | 26/26 通过 |
| Compose 统一重建 | `docker compose up --build -d` | 四镜像构建成功，后端构建测试 287/287 通过，四服务 healthy |
| Caddy 配置校验 | 容器内执行 `caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile` | 配置有效，仅 HTTP 监听 |
| HTTP 与入口隔离 | curl 请求 81 端口 live、ready 和 internal 路径 | 状态依次为 200、200、404，无重定向及 HSTS |
| 跨容器访问 | 独立 Python 3.12 容器通过 `host.docker.internal:81` 请求 live | 返回 200 |
| 静态变更检查 | `git diff --check`、`git status`、`git diff` | 无空白错误，无临时调试文件，实现提交仅含确认范围的 10 个文件 |

### 重测触发条件

- 修改 `backend/src/main/java/`、认证会话、Cookie 属性、CSRF 或其他安全策略。
- 修改 Caddyfile、Compose 端口、反向代理路由、HTTP/HTTPS 协议或上游 TLS 终止方式。
- 修改 Controller、Service、Repository、Domain、核心业务配置或开放 API 认证方式。
- 修改前端 Cookie 会话客户端、API 请求凭据处理或运行时访问地址。

### 已知问题与限制

- 纯 HTTP 会明文传输密码、会话 Cookie、Bearer Token、API Key 和业务请求数据，仅适用于用户确认的可信网络；公网或不可信网络必须增加上游 HTTPS。
- 曾访问旧 HTTPS 入口的浏览器可能缓存 localhost HSTS，需要清理浏览器 HSTS/站点数据，或改用 `http://127.0.0.1:81`。
- 宿主机 Python 3.12.13 未安装 pytest，首次宿主机测试命令因 `No module named pytest` 未进入用例执行；随后在固定 Python 3.12.13 容器中完成 26/26 测试，未修改宿主机环境。
- 未使用真实管理员密码执行浏览器登录 E2E；Cookie 属性及 CSRF 分支由后端单元测试覆盖，HTTP 入口、前端会话契约和服务健康已分别验证。
- 前端生产构建仍提示主包超过 500 kB，该既有性能问题不影响本次验收。

### 下次测试建议

1. 使用专用测试账号增加浏览器 E2E，覆盖 HTTP 登录、刷新恢复、CSRF 写请求、超时及登出清理。
2. 若部署到其他物理主机，验证主机防火墙放行 81、调用方到宿主机的路由以及 API Key IP 白名单。
3. 若未来恢复 HTTPS 或增加上游 TLS，设置 `APP_SESSION_COOKIE_SECURE=true` 并完整复测 Cookie、代理头、证书和 HSTS 行为。

### 回滚方式

- 回滚实现提交 `370b4b8`，恢复 TLS、443 发布、HTTP 跳转和默认 Secure Cookie 后重新执行 `docker compose up --build -d`。
- 恢复 80/443 前需先处理当前由 `domestic-trade-caddy-1` 占用的端口，或通过环境变量指定不冲突的宿主机端口。
- 本次没有数据库迁移、数据回填或删除操作，无需数据库逆向处理。

---

## 📋 浏览器会话与服务就绪加固测试结果（2026-08-06）

### Git 基准点

Commit: ae3cea9a8692c2ab6917cbe52191eeb3d250107f
- 提交说明: Harden browser sessions and service readiness
- 测试日期: 2026-08-06
- 分支: master
- 上一测试基准点: `a7d7edb3a1a7fe2400f26f3680be88f98ecac829`

### 变更范围

- 浏览器登录态改用按平台编码隔离的 Secure、SameSite=Strict、Host-only HttpOnly Cookie，并使用与 JWT 绑定的签名双提交 CSRF Token；现有 Bearer Token 客户端保持兼容。
- 前端不再读取或保存 Bearer Token，启动时通过 Cookie 恢复会话，并自动为 Cookie 写请求附加 CSRF 请求头；启动时清除旧版本遗留的本地 Token。
- 新增独立存活与就绪检查；就绪检查覆盖 MySQL、PostgreSQL、Redis 和 Python Worker，失败时仅向客户端返回 DOWN 与 HTTP 503。
- 公网 Caddy 入口对 `/api/internal` 及其子路径返回 404；新增可配置的 HTTPS 跳转目标，支持当前服务使用 81/444 等非标准端口。
- 数据库健康探测使用 5 秒默认连接等待上限，Worker 健康探测使用 3 秒连接及读取超时。
- 按用户确认不新增“每用户 20 次/分钟、200 次/天”或其他用户级 AI 调用额度限制；既有 API Key 限流和登录失败保护保持原行为。

### 测试执行结果

- 后端完整测试：286/286 通过，失败 0，错误 0，跳过 0。
- 前端完整测试：184/184 通过，失败 0，错误 0，跳过 0。
- Python Worker 完整测试（Python 3.12.13）：26/26 通过，失败 0，错误 0，跳过 0。
- 自动化用例合计：496/496 通过（100%）。
- Docker Compose：使用 81/444 端口完整构建并启动成功；Backend、Python Worker、Frontend、Caddy 全部 healthy。
- 端口运行态：原服务已恢复并在 80/443 healthy；当前服务在 81/444 healthy，HTTP 81 正确 308 跳转到 HTTPS 444。
- 健康与入口：当前服务 liveness、readiness 均返回 200；公网 `/api/internal/health` 返回 404；Caddy 配置校验通过。
- 认证运行态：Bearer 认证 200、Cookie 认证 200、Cookie 写请求缺少 CSRF 返回 403、有效 CSRF 登出返回 200、已撤销 Bearer Token 返回 401。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 浏览器 Token 不再暴露给脚本存储 | 前端契约测试；检查 HTTP 客户端和认证 Store | 不读写 localStorage Token，仅清理旧值；通过 Cookie 恢复用户 | 正常、安全、兼容、回归 |
| Cookie 会话具备安全属性且平台间隔离 | 后端单元测试；平台编码包含合法字符，登录签发有效 JWT | 会话 Cookie 为 HttpOnly/Secure/SameSite=Strict/Path=/api，CSRF Cookie 可供页面读取且名称包含平台编码 | 正常、边界、安全 |
| Cookie 写请求必须通过签名 CSRF 校验 | Interceptor、Token、Cookie Service 测试及运行态请求；缺失、错误和正确 Token | 缺失或不匹配返回 403，正确双提交 Token 通过，Token 与 JWT 不可替换使用 | 正常、异常、安全 |
| Bearer Token 与 API Key 保持兼容 | Interceptor 完整回归与运行态 Bearer 请求；显式 API Key 与会话 Cookie 并存 | Bearer 无需 CSRF；显式 API Key 使用其自身身份；混合 Authorization 与 API Key 仍返回 401 | 正常、权限、兼容、回归 |
| 登出同时撤销令牌并清除 Cookie | Controller/Cookie Service 测试与运行态请求 | 有效 CSRF 登出成功，Cookie 清除，原令牌后续返回 401 | 正常、安全、关键副作用 |
| 就绪检查真实覆盖四项依赖 | Health Service/Controller 测试；依赖正常、数据库异常、Redis 异常、Worker 异常 | 全部正常返回 200 UP；任一失败返回 503 DOWN 且不向客户端泄露依赖细节 | 正常、异常、安全、集成 |
| 存活检查不被外部依赖故障拖累 | Health Controller 测试及运行态请求 | Java 进程可响应时固定返回 200 UP | 正常、故障恢复 |
| 内部接口不可从公网入口访问 | Caddy 部署契约、配置校验和运行态 curl | `/api/internal` 及子路径由入口直接返回 404，普通 `/api` 路由不受影响 | 权限、安全、兼容 |
| 非标准端口跳转准确且原服务恢复 | Compose 重建、容器状态及 curl；原服务 80/443、当前服务 81/444 | 两套服务同时 healthy；81 跳转到 `https://localhost:444`，原服务 HTTPS 返回 200 | 正常、兼容、集成、回归 |

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 后端完整回归 | Backend Docker 构建中的 Maven `test` 阶段 | 286/286 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` | 184/184 通过，0 失败，0 跳过 |
| Worker 完整回归 | Python 3.12.13 执行 `python -m pytest -p no:cacheprovider` | 26/26 通过，0 失败，0 错误，0 跳过 |
| Compose 统一重建 | `HTTP_PORT=81 HTTPS_PORT=444 CADDY_HTTPS_ORIGIN=https://localhost:444 docker compose up --build -d` | 四镜像构建成功；构建阶段后端 286/286 通过，四服务 healthy |
| Caddy 配置校验 | 容器内执行 `caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile` | 配置有效 |
| 健康、跳转和入口验证 | curl 请求 81、444 的 live/ready/internal 路径，并检查原服务 443 | 308、200、200、404 符合设计；原服务 HTTPS 200 |
| 认证运行态验证 | 使用短时签名测试 JWT 分别执行 Bearer、Cookie、缺失/有效 CSRF 及撤销后请求 | 状态依次为 200、200、403、200、401；测试令牌未落盘或输出 |
| 静态变更检查 | `git diff --check`、变更范围搜索及提交前状态检查 | 无空白错误；未新增用户级调用限流；无临时调试文件 |

### 已知问题与限制

- 运行环境中的既有管理员密码与当前种子密码不一致，因此未使用真实账号密码重复登录；Cookie 属性已由单元测试验证，Cookie/CSRF/撤销链路使用同一密钥生成的短时测试 JWT 完成运行态验证，未修改管理员凭据。
- 当前服务的 81/444 端口通过本次启动命令的环境变量覆盖，未修改被忽略的本地 `.env`；后续重建必须继续携带相同三个环境变量，或由运维自行持久化对应配置。
- 原 80/443 服务恢复时使用了一次性合规种子密码覆盖其启动环境，未覆盖既有管理员密码；若其自身配置仍保留不合规种子密码，未来单独重建仍可能启动失败。
- 前端生产构建仍提示主包超过 500 kB，该既有性能问题不影响本次安全验收。
- 敏感日志内容治理仍在本次确认范围之外，是尚未处理的高风险项。
- 未执行浏览器 E2E、真实依赖故障注入、多实例切换、生产域名证书或外部渗透测试。

### 下次测试建议

1. 使用真实测试账号增加浏览器 E2E，验证登录响应 Cookie、刷新恢复、跨标签页、超时及登出清除行为。
2. 在预生产环境逐项中断 MySQL、PostgreSQL、Redis 和 Worker，验证 readiness 503、容器恢复与告警联动。
3. 将 81/444 外部端口配置持久化到部署系统，并为并行运行的两套服务配置不同正式域名，进一步隔离 Cookie 与证书。
4. 单独制定并确认敏感日志脱敏与访问治理方案。

### 回滚方式

- 回滚功能提交 `ae3cea9` 后，使用原端口及 HTTPS 跳转配置重新执行 `docker compose up --build -d`。
- 回滚会恢复前端 localStorage Bearer Token 行为，可能重新引入脚本读取 Token 的风险；执行前应清理浏览器旧登录态并评估兼容影响。
- 本次没有数据库迁移、数据回填或删除操作，无需数据库逆向处理。

---

## 📋 本次安全加固测试结果（2026-08-06）

### Git 基准点

Commit: a7d7edb3a1a7fe2400f26f3680be88f98ecac829
- 提交说明: Harden runtime supply chain
- 测试日期: 2026-08-06
- 分支: master
- 上一测试基准点: `666fe85a9ee78f35af2a00891df2b41e80d491fe`

### 变更范围

- 修复 Python Worker 内部鉴权绕过，限制可信 Host，并固定 Python 3.12 依赖及哈希。
- 加固登录失败计数、可信代理解析、RBAC 委派边界、最后管理员保护、12 位密码和 BCrypt 72 字节限制。
- 为入口请求、接口触发请求/响应和 Worker LLM 响应增加资源上限；对接口触发及模型供应商 URL 执行 DNS 重绑定防护、私网阻断和禁用重定向。
- 使用 Caddy 提供 TLS、HTTP 到 HTTPS 跳转和前端安全响应头；仅暴露 80/443，保留开放平台入口。
- 使用 Flyway 分别管理 MySQL 和 PostgreSQL 自动 DDL，已有非空库从版本 0 建立基线，Hibernate 改为 `validate`。
- 升级 Spring Boot 3.5.14、Spring Framework 6.2.19、Spring Data 3.5.12、Tomcat 10.1.55、Jackson 2.21.4、Netty 4.1.136.Final 和 PostgreSQL JDBC 42.7.12。
- Caddy 升级至 2.11.4，并使用 Go 1.26.5、修复版 x/text 与 gRPC 重建；前端生产镜像移除 npm、Corepack 和 Yarn。
- 四个运行时镜像全部使用非 root 用户、移除多余 capabilities 并启用 `no-new-privileges`；基础镜像固定摘要。
- 新增 Dependabot 周期更新以及 Trivy 仓库、配置、密钥和四镜像扫描；GitHub Actions 使用完整提交 SHA。
- 按确认范围暂不处理敏感日志内容治理。

### 测试执行结果

- 后端完整测试：270/270 通过，失败 0，错误 0，跳过 0。
- 前端完整测试：180/180 通过，失败 0，跳过 0。
- Python Worker 完整测试：26/26 通过，失败 0，错误 0，跳过 0。
- 自动化用例合计：476/476 通过（100%）。
- npm 依赖审计：0 个漏洞。
- Trivy 仓库扫描：Maven、npm、pip、Dockerfile、配置及密钥中高危/严重可修复发现 0 个。
- Trivy 镜像扫描：Backend、Python Worker、Frontend、Caddy 高危/严重可修复发现均为 0 个。
- Docker Compose：完整构建并启动成功；Backend、Python Worker、Frontend、Caddy 全部 healthy。
- TLS 运行态：HTTPS 健康接口返回 200，HTTP 返回 308；HSTS、CSP、X-Content-Type-Options、X-Frame-Options、Referrer-Policy 和 Permissions-Policy 生效。
- 端口与权限：仅 Caddy 发布 80/443；其他服务无宿主端口；四个服务均以非 root 用户运行，仅 Caddy 保留 `NET_BIND_SERVICE`。
- 数据库迁移：现有 MySQL/PostgreSQL Flyway 历史校验通过；空 MySQL 执行 V1/V2 并创建抽查的 4 张表，空 PostgreSQL 执行 V1 并创建 2 张触发表，空库后端健康返回 200。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| Worker 仅接受合法内部凭证和 Host | Pytest：缺失、错误、合法令牌及非法 Host | 未授权请求被拒绝，合法内部调用通过 | 正常、异常、权限、安全 |
| 登录、代理和 RBAC 不可绕过 | 后端 Security/Service 全量测试 | Redis 失败关闭、可信代理从右向左解析、越权委派及最后管理员变更被拒绝 | 正常、异常、权限、安全、回归 |
| SSRF 与资源上限生效 | API Trigger、LLM、请求过滤器及 Worker 测试 | 私网/重绑定/重定向被阻断，超限请求或响应稳定失败 | 边界、异常、安全、回归 |
| TLS、响应头与端口收敛 | 前端部署契约、Caddy 校验、curl、容器检查 | HTTPS 200、HTTP 308、安全头存在，仅发布 80/443 | 正常、安全、兼容、集成 |
| 自动 DDL 同时兼容历史库和空库 | Flyway 资源测试、现有库启动、临时空 MySQL/PostgreSQL | 历史迁移校验通过，空库自动建表，Hibernate validate 成功 | 正常、兼容、边界、集成 |
| 依赖升级不破坏历史功能 | 后端 270、前端 180、Worker 26 项完整回归 | 476 项全部通过，四服务健康 | 兼容、回归、集成 |
| 运行时最小权限 | 部署契约与 Docker inspect | 四服务非 root，capabilities 最小化，内部服务无宿主端口 | 权限、安全 |
| 供应链风险可持续发现 | npm audit、Trivy、Dependabot/Workflow 契约测试 | 当前可修复高危/严重发现为 0，CI 定期阻断新增问题 | 安全、回归 |

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Compose 重建及后端完整测试 | `MAVEN_MIRROR_URL= docker compose up --build -d` | 构建阶段后端 270/270 通过，四服务启动成功 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs frontend/tests/*.test.js` | 180/180 通过 |
| Worker 完整回归 | Python 3.12 运行镜像安装哈希锁定的测试依赖后执行 `python -m pytest -p no:cacheprovider` | 26/26 通过 |
| 前端依赖审计 | `npm audit --audit-level=moderate` | 0 个漏洞 |
| 配置校验 | `docker compose config -q`、`caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile` | 均通过 |
| Trivy 仓库扫描 | Trivy 0.70.0，扫描 vuln、misconfig、secret，级别 HIGH/CRITICAL | 0 个可修复发现 |
| Trivy 镜像扫描 | Trivy 0.70.0 扫描四个最终运行镜像，级别 HIGH/CRITICAL | 四镜像均为 0 个可修复发现 |
| 历史数据库启动 | Compose 后端启动并校验 Flyway 与 Hibernate 日志 | MySQL V2、PostgreSQL V1 均为最新，服务 healthy |
| 空库数据库验收 | 临时 MySQL 8.4、PostgreSQL 17、Redis 7 与最终后端镜像 | MySQL V1/V2、PostgreSQL V1 成功，健康接口 200；临时资源已删除 |
| TLS 与最小权限 | curl、`docker compose ps`、`docker inspect` | HTTPS 200、HTTP 308；非 root、端口和 capabilities 符合设计 |

### 重测触发条件

- 修改 `backend/src/main/java/`、Domain、Repository、Service、Controller 或影响业务的核心配置。
- 修改认证授权、可信代理、Worker 内部协议、SSRF 策略或任何请求/响应资源上限。
- 新增或修改 Flyway 迁移、JPA 实体及数据库连接配置。
- 修改基础镜像、依赖版本、Dockerfile、Compose、Caddyfile 或 GitHub Actions 供应链策略。
- 修改开放平台路由、TLS 模式、端口发布或前端安全响应头。

### 已知问题与限制

- Trivy 门禁设置为 HIGH/CRITICAL 且 `ignore-unfixed=true`，用于阻断已有修复版本但尚未升级的问题；无上游修复的问题仍需结合后续数据库更新持续观察。
- 内部 CA 模式需要管理员在客户端显式信任 Caddy 根证书；公网开放平台应配置真实域名和 ACME 邮箱。
- 前端生产构建仍提示主包超过 500 kB，该既有性能问题不影响本次安全验收。
- 未执行真实多实例故障注入、生产数据规模压测、浏览器 E2E 或外部渗透测试。
- 敏感日志内容治理按用户确认暂不处理。

### 下次测试建议

1. 在预生产公网域名下验证 ACME 证书签发、续期、HSTS 和开放平台 API Key 调用。
2. 增加 Testcontainers 数据库迁移测试，使 MySQL/PostgreSQL 空库建表在 CI 中自动执行。
3. 增加浏览器 E2E、并发限流、DNS 重绑定和大响应流式攻击场景。
4. 定期复核 Trivy 未修复项，并在上游发布修复后立即升级固定摘要。

### 回滚方式

- 运行时和供应链变更可回滚提交 `a7d7edb` 后重新执行 `docker compose up --build -d`。
- 其他安全能力分别位于 `c0c8ab1`、`e4a9661`、`8a4a7ec`、`f5343b5` 和 `5dedcd2`，应按依赖关系逆序回滚。
- Flyway 已记录数据库版本；代码回滚不会自动删除表或迁移历史。如需数据库逆向 DDL 或数据恢复，必须另行制定并确认迁移方案。

---

## 📋 本次变更测试结果（2026-08-05）

**Git 基准点**：`666fe85a9ee78f35af2a00891df2b41e80d491fe`（Remove legacy compatibility logic）

**变更范围**：按当前数据模型删除接口触发旧 Host 配置、明文密钥、API Key 旧限流字段、模型单值类型、路由候选模型、旧管理权限和链路日志旧列名兼容；能力路由统一使用供应商池；公开品牌接口仅保留 `/api/open/platform`，旧 `/api/open/branding` 返回标准 404；不执行历史数据迁移、回填或删除。

**测试执行结果**：
- 后端定向测试：首次 64 个通过；404 修复后 11 个通过，均为 100%，失败 0 个，错误 0 个，跳过 0 个。
- 后端完整测试：最终 245 个，通过 245 个（100%），失败 0 个，错误 0 个，跳过 0 个。
- 前端完整测试：176 个，通过 176 个（100%），失败 0 个，错误 0 个，跳过 0 个。
- 总计：421 个当前测试用例通过（后端 245、前端 176）。
- Docker Compose 构建启动：通过；镜像构建阶段再次执行后端 245 个测试并全部通过。
- 服务健康检查：Backend、Frontend、Python Worker 全部 healthy。
- 运行时接口：`/api/open/platform` 返回 200，`/api/open/branding` 返回 404，`/api/open/platform/endpoints` 返回 200。

**关键模块测试**：
- `PlatformControllerTest`：1/1 通过，确认控制器仅映射 `/api/open/platform`。
- `ApiResponseContractTest`：10/10 通过，确认已移除路径和不存在资源返回统一 404。
- `ApiTriggerSecurityConfigurationServiceTest`：4/4 通过，确认仅读取当前结构化 Host 规则。
- `ConfigCryptoServiceTest`：3/3 通过，确认当前 AES-GCM 密文可解密，明文和非法密文被拒绝。
- `ApiKeyManagementServiceTest`：10/10 通过，确认仅使用当前限流类型与次数字段。
- `LlmManagementServiceTest`：35/35 通过，确认模型类型集合和供应商池路由逻辑。
- `DataInitializerTest`：10/10 通过，确认不再生成旧 `system:*:manage` 权限。
- `TraceLogFlusherTest`：1/1 通过，确认 SQL 固定写入当前 `level` 列且不再探测旧列。

**验收标准—测试映射**：

| 验收标准 | 测试用例 | 场景类型 |
| --- | --- | --- |
| 公开平台配置仅保留 `/api/open/platform` | `PlatformControllerTest#returnsPlatformConfigurationFromCurrentPath`、运行时 HTTP 验证 | 正常、兼容 |
| 已移除 `/api/open/branding` 并返回标准 404 | `ApiResponseContractTest#missingRouteReturnsNotFoundResponse`、运行时 HTTP 验证 | 异常、兼容、安全 |
| Host 安全配置不再读取旧逗号字段 | `ApiTriggerSecurityConfigurationServiceTest` | 正常、边界、回归 |
| 敏感配置只接受当前 AES-GCM 密文 | `ConfigCryptoServiceTest` | 正常、异常、安全 |
| API Key 只使用当前限流模型 | `ApiKeyManagementServiceTest`、`ApiKeyAuthenticationServiceTest`、前端 API Key 测试 | 正常、边界、兼容、回归 |
| 模型仅使用类型集合，路由仅使用供应商池 | `LlmManagementServiceTest`、前端模型路由测试 | 正常、边界、异常、回归 |
| 内置权限不再包含旧 manage 权限 | `DataInitializerTest#excludesLegacyManagementPermissions`、`navigation.test.mjs` | 权限、安全、回归 |
| 链路日志 SQL 只写当前 level 列 | `TraceLogFlusherTest#flushUsesCurrentLevelColumn` | 正常、SQL、回归 |

**已知问题与限制**：
- 宿主机未安装 Maven，后端测试通过 Maven Docker 容器执行。
- 当前本地数据库仍存在历史 `sys_api_key.secret_encrypted IS NULL` 记录，Hibernate 尝试收紧 `NOT NULL` 时记录 DDL 警告；本次根据“不考虑之前数据影响”的范围未执行数据回填、迁移或删除，服务仍正常启动并通过健康检查。
- 前端生产构建继续提示主包超过 500 kB；该既有性能提示不影响本次功能验收。
- 未执行真实多实例、故障注入或生产数据规模压测。

**下次测试建议**：
1. 在全新数据库上执行完整启动验收，确认所有当前实体约束一次性创建成功。
2. 如需复用历史数据库，单独制定 API Key 空密文数据清理方案并人工确认后执行。
3. 增加公开接口 404 的 MockMvc 集成测试及数据库 Schema 约束集成测试。

## 📋 Git 基准点

Commit: 666fe85a9ee78f35af2a00891df2b41e80d491fe
- 提交说明: Remove legacy compatibility logic
- 测试日期: 2026-08-05
- 分支: master
- 上一测试基准点: `9d5e904`
- 上一测试报告提交: `0c39891`

## 🎯 当前变更范围

- 邮件路由页面增加人工测试功能，使用所选路由自身的 SMTP 账户、主送和抄送配置发送固定测试邮件，不回退 `DEFAULT`。
- 测试邮件语言跟随当前界面的 `Accept-Language`；无前端上下文的后端业务邮件统一使用 `APP_DEFAULT_LOCALE`。
- 物流失败通知主题、字段标签和正文由中英文资源生成，不再硬编码中文。
- 测试接口复用 `mail:route:update` 权限并记录稳定追踪类型 `MAIL_ROUTE_TEST`；待配置路由和停用账户在调用 Worker 前被拒绝。
- 物流失败状态增加 `ESTIMATE` 与 `PLACE` 阶段，支持确认未下单后恢复至 `WAITING`，以及下单失败后切换当前报价批次中的其他可用渠道继续下单。
- `UNKNOWN` 明确用于承运商下单请求发生异常且无法确认是否成功的场景，必须人工确认未下单后才能恢复或切换渠道。
- 订单汇总件数、重量和体积统一依据商品明细在服务端重算并校验；订单列表改为每页固定 10 条。
- 增加失败订单通知接口，一次汇总当前全部 `FAILED` 和 `UNKNOWN` 订单并通过 Python Worker 发送邮件，不内置定时调度。
- 保留邮件账户、邮件路由、DEFAULT 身份约束、密码加密与脱敏、邮件管理菜单及 SMTP Worker 能力。
- 本次未新增依赖、数据库脚本或配置迁移。

## 📊 当前测试执行结果

## 📋 本次变更测试结果（2026-08-03）

**变更范围**：同步源项目系统参数管理逻辑，适配当前项目的系统参数分页、Key 模糊检索、排序、系统托管参数保护、管理员敏感值回显和前端分页交互；未引入当前项目不存在的物流、营销、线索采集业务配置模块。

**测试执行结果**：
- 总测试用例：411 个（后端 235 个、前端 176 个）
- 通过：411 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- 系统参数后端专项：6/6，通过
- 前端系统参数契约专项：3/3，通过
- 后端完整测试：235/235，通过
- 前端完整回归：176/176，通过
- 前端生产构建：通过
- Compose 服务健康检查：Backend、Frontend、Python Worker 全部 healthy

**Git 基准点**：a992dcaeb8f89dfcdce69d0cf5d6b967410a6710

**新发现的问题**：
- 首次 Compose 启动时发现旧 `domestic-trade-backend-1` 占用 `8080`，停止旧容器后重试成功。
- 第二次 Compose 启动时发现旧 `domestic-trade-frontend-1` 占用 `80`，停止旧容器后重试成功。
- 宿主机未安装 `mvn`，后端测试统一通过 Maven Docker 容器执行。

- 后端邮件专项：36 个，通过 36 个（100%），0 失败，0 错误，0 跳过。
- 前端邮件及 HTTP 错误专项：10 个，通过 10 个（100%），0 失败，0 错误，0 跳过。
- 后端完整回归：232 个，通过 232 个（100%），0 失败，0 错误，0 跳过。
- 前端完整回归：175 个，通过 175 个（100%），0 失败，0 错误，0 跳过。
- Python Worker 本次未修改，沿用上一基准完整回归 36/36 通过。
- 前端生产构建：Vite 构建成功，保留既有非阻断警告。
- Compose 重建：Backend、Frontend、Python Worker 镜像构建成功，服务均已启动并健康。
- 运行态接口：新增测试接口未登录访问返回中文 401 且未触发邮件；三个服务均健康。

## ✅ 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入/操作 | 预期结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 测试邮件跟随当前界面语言 | Delivery Service 参数化测试；中英文资源完整 | 分别传入 zh-CN、en-US 请求语言 | 固定主题和正文按请求语言发送，业务含义一致 | 正常、兼容、国际化 |
| 人工测试精确使用所选路由 | Management/Delivery Service 测试；具体路由与 DEFAULT 并存 | 测试启用或停用的具体路由 | 使用具体路由的 SMTP、主送和抄送，不查询或回退 DEFAULT | 正常、边界、安全 |
| 不可用配置阻止测试发送 | Service 测试；路由不存在、待配置或账户停用 | 调用路由测试 | 返回稳定业务错误，不调用 Worker | 异常、边界、安全 |
| 后端邮件使用系统语言 | Delivery/Logistics Service 测试；线程语言与系统语言不同 | 生成物流失败通知 | 主题、字段和正文均按 APP_DEFAULT_LOCALE 生成 | 正常、兼容、回归 |
| 测试入口受权限和追踪保护 | Controller、Trace 和前端契约测试 | 无更新权限或未登录访问；有权限点击测试 | 按 mail:route:update 控制入口和接口，记录 MAIL_ROUTE_TEST；未登录返回 401 | 权限、安全、回归 |
| 邮件菜单位置和权限层级正确 | DataInitializer、前端导航测试；初始化空菜单 | 启动初始化并读取权限树 | 邮件管理位于系统和模型之间，账户/路由页面及按钮归属正确 | 正常、权限、回归 |
| 邮箱密码安全保存和更新 | Service 单元测试；真实 AES-GCM、模拟仓储 | 新建、空密码编辑、新密码编辑 | 保存密文；列表不泄漏；空值保留旧密文；非空值生成新密文 | 正常、边界、安全、兼容 |
| 明文密码仅系统管理员可读 | Service、Controller、前端契约测试 | 管理员、未登录用户、普通更新者读取单账户密码 | 管理员成功；未登录 401；非管理员 403；不存在账户 404；弹窗关闭清空 | 正常、异常、权限、安全 |
| 邮箱配置拒绝非法输入 | Service 参数化测试 | 非法编码、端口、TLS、发件地址和换行注入 | 返回对应业务错误且不持久化 | 边界、异常、恶意输入 |
| 邮件路由正确解析和回退 | Service 单元测试；具体及 DEFAULT 路由 | 具体路由存在、缺失、停用、账户停用或待配置 | 优先具体路由；必要时回退 DEFAULT；不可用时稳定失败 | 正常、异常、兼容 |
| DEFAULT 邮件路由不可破坏 | Service、初始化器、前端契约测试 | 空环境初始化，提交改名、改编码、禁用、删除请求 | 固定系统身份并保持启用；删除被拒绝；待配置状态可展示 | 正常、边界、安全、回归 |
| DEFAULT 模型路由身份固定 | Service、启动初始化器和前端测试 | 启动历史改名数据，提交改编码和名称请求 | 恢复固定编码和名称；普通路由继续可编辑 | 正常、兼容、回归 |
| 敏感邮件请求不进入明细日志 | Controller、RequestContextFilter 测试 | 提交包含密码的邮件账户请求 | 不生成 Request ID 或 HTTP 请求/响应明细日志 | 安全、回归 |
| 邮件页面适配现有布局 | 前端布局、页面及工具测试 | 展示多列账户/路由，增删多个收件人 | 主表列宽和操作列符合规范；地址过滤空值并去重 | 正常、边界、可用性 |
| 历史功能保持稳定 | 完整前后端测试与 Compose 运行态 | 执行完整测试、重建并启动服务 | 后端 232 个、前端 175 个测试通过，三个服务 healthy | 回归、集成 |

## 🧪 当前测试执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 后端邮件专项 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -ntp -Dtest=MailManagementServiceTest,MailDeliveryServiceTest,MailManagementControllerContractTest,LogisticsFailureNotificationServiceTest,MessageBundleTest,TraceTypeCodeTest test` | 36/36 通过，0 失败，0 错误，0 跳过 |
| 前端邮件及 HTTP 错误专项 | `cd frontend && node --test test/logistics-orders.test.mjs test/http-error.test.mjs` | 10/10 通过，0 失败，0 跳过 |
| 后端完整回归 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B -ntp` | 232/232 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `cd frontend && node --test test/*.test.mjs tests/*.test.js` | 175/175 通过，0 失败，0 跳过 |
| Python Worker | 本次未修改，未重复执行 | 沿用上一基准 36/36 通过结果 |
| Compose 统一重建 | `docker compose up --build -d` | Backend、Frontend、Python Worker 镜像构建成功并启动 |
| Compose 状态检查 | `docker compose ps` | Backend、Frontend、Python Worker 均为 healthy |
| 运行态健康检查 | `curl -fsS http://localhost:8080/api/open/health`、`curl -fsS http://localhost/health` | Backend、Frontend 均返回 `{"status":"UP"}` |
| 邮件测试接口权限 | 未登录以 `Accept-Language: zh-CN` 调用 `POST /api/mail/routes/1/test` | 返回 HTTP 401 和“请先登录”，未触发 SMTP |
| 静态变更检查 | `git diff --check`、暂存差异和工作区状态检查 | 无空白错误；功能提交仅包含本次确认范围 |

## 📊 当前测试覆盖范围

- Domain/Repository：邮件账户、可待配置的邮件路由、唯一编码及账户引用查询。
- Service：账户 CRUD、AES-GCM 加解密、精确测试路由解析、请求/系统邮件语言选择、路由解析和 DEFAULT 强约束。
- Controller/权限：账户和路由独立 CRUD、邮件路由测试、明文密码的更新权限与 ADMIN 角色双重隔离。
- 初始化：邮件表字段兼容迁移、邮件 DEFAULT 初始化、模型 DEFAULT 身份纠正及菜单层级。
- Frontend：统一 HTTP 错误处理、请求语言、邮件路由测试、逐行收件人编辑、管理员密码回显、DEFAULT 字段锁定和统一表格布局。
- 回归：后端 232 个、前端 175 个测试；Python Worker 未修改。

## 🔄 当前重测触发条件

- 修改邮件账户、邮件路由、密码加密/回显、权限或敏感追踪排除规则。
- 修改邮件或模型 DEFAULT 的固定编码、名称、启用规则及启动初始化逻辑。
- 修改邮件管理菜单、前端路由、表单、逐行邮箱编辑或表格布局。
- 修改测试邮件模板、请求语言传递、`APP_DEFAULT_LOCALE` 或后端邮件本地化规则。
- 当前 Git 基准点之后存在后端业务代码变更，或用户明确要求重新验证。

## ⚠️ 当前已知问题与限制

- 未配置或发送真实 SMTP 邮件；SMTP 行为由隔离外部连接的测试覆盖。
- Compose 构建命中既有镜像缓存；功能代码已通过本次独立完整回归，运行容器已按当前工作区重新创建并健康。
- 邮箱密码明文接口属于高敏感能力，除 Controller 权限外还强制要求 `ADMIN` 角色，并通过全路径追踪排除防止进入 HTTP 明细日志。
- JPA 会创建 `sys_mail_account` 和 `sys_mail_route`；代码回滚不会自动删除表或数据。

## 📝 下次测试建议

1. 在测试邮箱环境中分别使用中英文界面执行真实 STARTTLS/SSL 测试邮件验收。
2. 使用管理员和普通操作员验证密码读取隔离、测试按钮权限以及列表持续脱敏。
3. 增加浏览器端 E2E 测试，覆盖邮件路由测试按钮、加载状态和错误反馈。

## ↩️ 回滚方式

- 回滚功能提交 `5ce3be4` 和 `0fbeeae` 并重新执行 `docker compose up --build -d`，可移除本次同步的邮件本地化测试和统一前端错误处理。
- 代码回滚不会删除新增邮件表或历史配置；如需清理数据库对象和数据，必须另行确认后执行。

---

## 📋 Git 基准点

Commit: 9e695dc0949848c050cc6b859c473b72ec4de539
- 提交说明: Add Trace ID filters for task logs
- 测试日期: 2026-07-31
- 分支: master

## 🎯 当前变更范围

- 系统任务列表新增 Trace ID 精确筛选，并与状态、任务类型、触发入口、日志和时间等既有条件组合使用。
- 系统任务列表与总数查询使用一致筛选条件，非管理员继续受 `owner_user_id` 数据权限约束。
- 接口触发器执行日志在当前配置 ID 范围内新增 Trace ID 精确筛选，并展示 Trace ID 列。
- 两个前端筛选入口均支持查询、回车触发和重置，未传 Trace ID 时保持原有接口行为。
- 本次不涉及数据库结构、依赖、配置迁移或跨配置数据查询。

## 📋 当前变更测试结果（2026-07-31）

**变更范围**：系统任务调度与接口触发器执行日志的 Trace ID 精确检索。

**测试执行结果**：
- 后端定向测试：9 个，通过 9 个（100%）
- 前端定向测试：17 个，通过 17 个（100%）
- 后端完整测试：191 个，通过 191 个（100%）
- 前端完整测试：148 个，通过 148 个（100%）
- Compose 后端构建阶段测试：191 个，通过 191 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- TaskTraceService：4/4，通过 Trace ID 精确命中、无结果、未传参数兼容、组合筛选、用户数据隔离及分页总数校验。
- ApiTriggerService：5/5，通过当前配置范围内 Trace ID 命中、无结果、未传参数兼容及响应字符集历史用例。
- 前端定向验证：17/17，通过两个页面参数传递、重置行为和执行日志 Trace ID 列展示。
- 后端完整回归：191/191，Security、Service、Controller、任务追踪和自动化模块等历史功能全部通过。
- 前端完整回归：148/148，页面结构、交互契约、本地化和工具函数等历史功能全部通过。
- Compose 运行态：Backend、Frontend、Python Worker 最终均为 healthy。

**实施验证记录**：
- 宿主机未安装 Maven，直接执行 `mvn` 返回 `command not found`；随后使用项目锁定的 Maven 3.9.9 / Java 17 Docker 镜像完成定向和完整测试。
- 前端测试使用 Node.js 内置测试运行器执行，定向与完整测试均一次通过。
- `docker compose up --build -d` 一次成功，构建阶段再次运行 191 个后端测试并全部通过。
- Backend `/api/open/health`、Frontend `/health` 返回 `UP`，三个 Compose 服务均为 healthy。

## ✅ 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入/操作 | 预期结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 系统任务按 Trace ID 精确命中 | Service 单元测试；存在目标任务 | Trace ID 与 `SUCCESS` 状态组合查询 | 列表和总数查询均使用精确条件并返回目标任务 | 正常、兼容性 |
| 系统任务无匹配时返回空分页 | Service 单元测试；目标 Trace ID 不存在 | 查询第二页、每页 10 条 | 记录为空、总数为 0，分页参数保持一致 | 异常、边界 |
| 非管理员不能越权定位任务 | Service SQL 单元测试；普通用户 ID 7 | 按 Trace ID 查询 | 列表和总数查询同时包含 `owner_user_id` 与 Trace ID 条件 | 权限、安全 |
| 未传 Trace ID 保持原行为 | Service 单元测试；空白 Trace ID | 执行任务与日志查询 | SQL 不增加 Trace ID 条件，原列表行为不变 | 兼容性、回归 |
| 接口日志仅查询当前配置 | Service 单元测试；配置 ID 9 | 按 Trace ID 查询执行日志 | SQL 同时包含配置 ID 与 Trace ID 精确条件 | 权限、安全 |
| 接口日志 Trace ID 无匹配 | Service 单元测试；目标日志不存在 | 查询缺失 Trace ID | 返回空列表，不扩大到其他配置 | 异常、边界 |
| 前端正确传参与重置 | Node 静态交互测试；两个 Vue 页面 | 输入、查询并重置 Trace ID | 请求携带参数，重置清空参数并重新查询 | 正常、回归 |
| 接口日志展示 Trace ID | Node 组件结构测试；执行日志抽屉 | 打开日志抽屉 | 表格包含 Trace ID 列 | 正常、可用性 |
| 历史功能保持稳定 | 完整前后端测试与 Compose 运行态 | 执行完整测试、重建并启动服务 | 后端 191 个、前端 148 个测试通过，三个服务 healthy | 回归、集成 |

## 📊 当前测试执行记录

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 后端定向测试 | `docker run --rm -v "$PWD":/workspace -v "$HOME/.m2":/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -ntp -Dtest=TaskTraceServiceTraceIdTest,ApiTriggerServiceResponseDecodingTest test` | 9 通过，0 失败，0 错误，0 跳过 |
| 前端定向测试 | `node --test test/api-trigger.test.mjs test/system-pagination.test.mjs` | 17 通过，0 失败，0 跳过 |
| 后端完整回归 | `docker run --rm -v "$PWD":/workspace -v "$HOME/.m2":/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B -ntp` | 191 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `node --test test/*.test.mjs tests/*.test.js` | 148 通过，0 失败，0 跳过 |
| Compose 构建启动 | `docker compose up --build -d` | 后端构建测试 191/191 通过；三个服务一次启动成功并均为 healthy |
| Compose 状态检查 | `docker compose ps` | Backend、Frontend、Python Worker 均为 healthy |

## 📊 测试覆盖范围

- Task Trace Controller/Service：可选参数传递、精确 SQL 条件、组合筛选、分页总数和用户数据隔离。
- API Trigger Controller/Service：当前配置日志边界、精确 Trace ID 条件、空筛选兼容和空结果。
- Frontend Views：系统任务筛选传参/重置、接口执行日志筛选传参/重置和 Trace ID 列。
- 回归范围：后端全部 191 个测试、前端全部 148 个测试及 Compose 三服务运行态。

## 🔄 重测触发条件

- 修改任务列表筛选、权限条件、分页或总数查询时必须重测。
- 修改接口触发器配置归属、执行日志查询或日志视图模型时必须重测。
- 修改两个前端页面的查询参数、重置逻辑或执行日志表格时必须重测。
- 修改 `backend/src/main/java/` 下其他业务代码时按基准点重新执行后端完整测试。

## ⚠️ 已知问题

- Trace ID 使用精确匹配，输入不完整或大小写不同不会命中，这是本次确认的预期行为。
- 前端自动化测试为源码交互契约测试，未执行浏览器端登录后的真实页面 E2E 操作。
- 接口触发器执行日志仍受原有最近 200 条上限约束；指定 Trace ID 的记录若超出当前查询排序范围，SQL 会先筛选再应用该上限。

## 📝 下次测试建议

1. 增加基于真实 MySQL 数据的集成测试，验证列表和总数 SQL 在多日志关联下保持一致。
2. 增加浏览器 E2E 测试，覆盖登录后输入、回车查询、重置和切换接口配置的完整流程。
3. 监控高频 Trace ID 定位场景的查询耗时；数据量增长后评估现有 Trace ID 索引使用情况。

## ↩️ 回滚方式

- 回滚提交 `9e695dc` 并重新执行 `docker compose up --build -d`，可移除两个 Trace ID 筛选入口并恢复原接口签名。
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
- 修改 `backend/config/trace-tracking-exclusions.yml`、默认排除方法、排除路径或 `@TraceIgnored` 使用范围。
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
