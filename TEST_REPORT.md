# 最近分支覆盖测试报告

## 数据同步工作台布局（2026-09-12）

### Git 基准与范围

Commit: 724fec3eee679eab80d0c32db7056baebe01a9e1
- 提交信息：Redesign data sync workspace and plan editor；分支 master；测试日期 2026-09-12（Asia/Shanghai）。
- Vue 3 / Element Plus / Vue I18n / CSS Grid；修改 DataSyncView.vue、中英文资源及 data-sync-remote.test.mjs，无新增项目依赖、后端接口、配置或数据库变更。
- 四张指标卡展示计划总数、启用、运行中（含取消中）、最近失败；任务卡片突出源库到目标库的流向，右侧提供选中摘要与权限控制的操作。
- 搜索计划、服务器及连接，状态筛选兼容历史 SUCCESS；新建/编辑使用分组抽屉，底部固定预检与保存。表搜索不丢失隐藏选择；新建清除历史 ID，保存失败保留输入，保存期间禁止关闭。
- 共享工作区存在服务器终端、账号匹配、邮件功能等其他任务；本任务代码通过独立 Git index 只提交四个文件的相关改动，未提交其他任务文案、业务代码或配置。

### 实际测试及验收

- 根目录执行 `node --test frontend/test/data-sync-remote.test.mjs`：26/26 通过，失败 0、错误 0、跳过 0。
- frontend 目录执行 `npm run lint && npm run typecheck && npm run test:coverage`：首次完整 404/404 通过，失败 0、跳过 0，工具函数覆盖率行 98.40%、分支 80.95%、函数 95.27%。后续因共享工作区发生其他修改再次执行同一检查及 E2E，退出 0，最新工具函数覆盖率行 98.42%、分支 81.23%、函数 95.33%。覆盖统计仅针对 src/utils，不代表 Vue 组件行覆盖率。
- frontend 目录执行 `node --test e2e/*.test.mjs`：2/2 通过，失败 0、跳过 0，验证生产 SPA、API 代理、畸形路径及 WebSocket 代理。使用 `docker cp ai-frontend:/app/dist/. frontend/dist/` 获取统一构建产物，未单独运行前端 build。
- 使用 `npm run dev` 启动 Vite，环境已有 Playwright 包通过内存中的 `node <<'NODE'` 脚本运行 Chromium；挂载真实 Vue 页面及 Element Plus，仅隔离 Axios 外部依赖，未向真实服务发送写请求。
- 中英文分别验收 1440、768、390 像素宽度：六组均无页面横向溢出；390 像素中英文抽屉无横向溢出且保存按钮可见。浏览器实际点击验证无匹配搜索/清除、运行中按钮限制、编辑回填、表搜索保留选择、新建重置、只读用户隐藏写操作，未捕获页面异常。另完成桌面截图视觉检查。

| 验收标准 | 层级/前置条件 | 输入与预期 | 场景 |
| --- | --- | --- | --- |
| 任务可检索、统计准确 | Node 执行真实页面脚本与 Vue computed | 空列表、大小写/空白关键词、服务器/连接名、历史成功状态、恶意文本；匹配 ID 正确、统计不受筛选影响 | 正常、边界、安全、兼容 |
| 摘要跟随选择 | Node 响应式测试 | 筛选、删除、刷新、不可用连接；选中项正确回退、更新或为空 | 边界、异常、回归 |
| 编辑和新建数据正确 | Node 与 Chromium 真实组件 | 编辑回填服务器/表，源连接清空，新建重置 ID；查询携带正确服务器 | 正常、边界、兼容 |
| 表搜索不丢失选择 | Node 与 Chromium | Schema、大小写、无匹配；过滤展示但保留选中表及数量 | 边界、回归 |
| 保存行为可靠 | Node 真实 save/closeEditor，隔离网络 | 无效表单不请求，失败保留输入，成功关闭，保存期间拒绝关闭；验证请求表映射 | 异常、状态冲突 |
| 权限和安全限制保留 | 既有契约及 Chromium 渲染 | 只读权限隐藏操作、运行中禁止重复执行、取消可用、全量替换确认保留 | 权限、安全、回归 |
| 双语响应式布局可用 | Chromium DOM 尺寸及视觉检查 | 六组视口/语言、超长名称和表名；布局自适应，操作可见，无横向溢出 | 边界、兼容 |

### 构建与运行环境

- 执行 `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`。早期构建 Maven `mvn -B -ntp package` 的903项测试全部通过；首次构建后启动遇到另一任务同时重建的容器名称冲突，等待后重试。
- 用户再次继续时，原工具会话已经失效，运行环境又被其他任务替换；重新执行上述完整 Compose 命令，镜像修订 eac558aeeab96cb9e1faa41ce81fda98b059624f，包含本任务提交与构建时共享工作区的其他改动。
- 最终重建退出0；本轮 Maven 测试922/922通过，失败0、错误0、跳过0。backend、frontend、deployment-agent、document-parser、python-worker 均 healthy，Caddy 已启动。运行中前端资源 index-CcKLqbOM.js 包含 sync-workbench，确认新版布局已进入运行镜像；未出现端口占用。

### 过程问题、限制与后续建议

- 初次定向测试两项仍引用旧表格 scope.row，改为卡片/摘要绑定，保留业务断言；新增测试脚本首次缺少语句分隔符，修正后26项通过。未删除、跳过或弱化有效测试。
- 首次 E2E 在根目录运行导致终端测试找不到 server.mjs，1/2失败；切换 frontend 目录后2/2通过。
- Air 预览受 localhost HTTPS 升级影响出现 ERR_SSL_PROTOCOL_ERROR，且工具拒绝切换来源；改用独立 Chromium 的127.0.0.1进行验收。浏览器内存夹具首次未声明UTF-8，补充后中文回填及其余交互全部通过，生产代码无需修改。
- Compose 查询未传 APP_IMAGE_REVISION 时会失败；按项目既有方式提供 Git 修订后正常解析。
- 未连接真实源/目标库执行写入、删除或重试，数据库同步端到端集成不在本次布局验收中；建议使用专用测试库验证真实预检和执行链路。
- 后端构建测试只代表构建时的工作区快照，不覆盖后续其他任务变更。本节基准为已提交的数据同步前端布局版本。
- 临时截图 /tmp/base-ai-sync-workspace.jpg 已清理，本次 Vite 进程、浏览器上下文及临时 Git index 目录已清理；未提交临时测试代码或新增 Markdown。
- 后续修改筛选、摘要、抽屉、状态或权限时重跑定向与完整前端测试；后端业务改动按项目规则另行完整测试。回滚可撤销724fec3及本节报告提交，无数据迁移和依赖回退。

## 服务器 SSH 交互终端（2026-09-12）

### Git 基准与范围

Commit: 0f740b5be9577f1d9998bc1e098720c694cc8f72
- 提交信息：Add interactive SSH terminals to server management；分支 master；测试日期 2026-09-12（Asia/Shanghai）。
- Vue 3、xterm.js、Spring WebSocket、Go WebSocket/PTY、OpenSSH。新增 SSH 终端入口、持续会话、尺寸同步、中断、断开重连及独立 operations:server:shell 权限；新增 V38 权限迁移，默认不授予 OPS。
- 一次性票据有效期 30 秒，握手必须登录且同源，票据通过首帧传输；会话归属、权限、启用状态持续检查，空闲 5 分钟、最长 30 分钟；每用户最多 4 个连接、全局最多 32 个。终端正文不写审计日志，凭据不返回前端。
- 基于上述提交的独立源码快照完成最终验证和构建，隔离共享工作区中邮件等其他未提交改动。最新报告前置提交 eac558a 与本代码基准之间无 Java 业务差异。

### 实际执行命令与结果

- 后端：Docker maven:3.9.9-eclipse-temurin-17 容器内复制提交快照后执行 `mvn -B -ntp test`，903/903 通过，失败 0、错误 0、跳过 0，通过率 100%。完整套件包含 Domain、Repository、Service、Controller 及历史功能；终端服务 18、来源校验 15、真实 WebSocket 桥接 1、服务器校验 23、监控 28、凭据 41 全部通过。不将用例通过率表述为 Java 行覆盖率。
- 前端快照目录：`npm ci --ignore-scripts`、`npm run lint`、`npm run typecheck`、`npm run test:coverage` 均成功；404/404 通过，失败和跳过均为 0。现有工具函数行覆盖率 98.40%、分支 80.95%、函数 95.27%；该指标不代表 Vue 组件行覆盖率。
- 从 Compose 构建镜像取得 dist 后，在前端快照目录执行 `node --test e2e/*.test.mjs`：2/2 通过，失败 0、跳过 0，包含生产 Node WebSocket 代理双向数据及原有 HTTP/SPA 行为。
- Go：golang:1.26.6-alpine 隔离容器安装 openssh-server/openssh-client，生成临时主机密钥并执行 `go test -cover -tags=integration ./...`，退出 0，语句覆盖率 59.9%；全部用例通过，无跳过。覆盖真实密码、私钥、组合认证及加密私钥组合认证。
- 部署：使用临时 COMPOSE_FILE 构建上下文覆盖和 `APP_IMAGE_REVISION=0f740b5be9577f1d9998bc1e098720c694cc8f72 docker compose up --build -d`，退出 0；backend、frontend、deployment-agent、document-parser、python-worker、caddy 六服务均 healthy，容器 revision 标签与代码基准一致。后端沿用 Docker 重建遗留的名称前缀，但 Compose 服务为 backend。
- HTTPS 运行验证：匿名终端握手 403、登录后的同源握手 101、跨站握手 403，测试登录会话登出 200；凭据仅在内存使用。自签名证书验证仅在本机核验脚本中关闭。
- `git diff --check`、提交前暂存差异检查通过；只提交终端范围。临时快照及构建覆盖文件在交付前清理，容器测试临时文件随 --rm 容器清除，无额外测试报告文件。

### 验收标准—测试用例映射

| 验收标准 | 层级／前置条件与输入 | 预期业务结果／场景 |
| --- | --- | --- |
| 连续交互 | Go 真实 SSH 集成：登录后 cd /tmp、pwd、中文输出、stty size、sleep 后 Ctrl+C | 目录保持、UTF-8 输出正确、尺寸 37×112 生效、中断后继续执行；正常／兼容 |
| 认证兼容及主机校验 | 隔离 sshd 四种认证，配置正确和错误指纹 | 合法认证成功，错误指纹拒绝；正常／异常／安全 |
| 票据与权限 | Java 服务与 H2 业务测试：过期、重放、其他用户、无权限、manage 旧权限、禁用、LOCAL、不存在目标 | 拒绝越权与不支持目标、不启动 Agent；安全／异常 |
| 来源及长连接安全 | 15 个来源参数化用例、运行 HTTPS 握手、权限撤销 supplier | TLS 代理同源可连、跨站拒绝、身份失效断开；安全／兼容 |
| 资源边界 | Java/Go：零与最大尺寸、超大整数、超长输入、四会话上限、首帧超时、重复关闭 | 非法协议拒绝、超时回收、容量可复用；边界／异常 |
| 双向传输与回收 | 真 Tomcat/WebSocket 客户端，外部 Agent 协议端点 | 首帧消费票据、Agent 收到配置、输入输出双向转发、关闭传播；集成 |
| 前端生命周期 | 执行组件真实逻辑并隔离网络/渲染：票据失败、关闭竞态、旧连接消息、重连、积压、emoji 分片 | 错误可重试、旧响应不复活窗口、连接释放、字符不拆坏；正常／边界／异常 |
| 历史功能 | 完整 Java、前端、Go 套件 | 凭据、监控、数据同步、HTTP 代理等回归通过；回归 |

### 测试中发现及已知限制

- 首轮 Go 集成失败原因是 sshd 的 PerSourcePenalties 对连续错误认证测试限流，导致超时用例提前断开；仅在隔离 sshd 测试配置关闭来源惩罚，原超时断言保留，复验全部通过。
- 首轮完整 Java 测试 895 项出现 1 个插件探测异步状态失败及 7 个并行凭据改动相关错误。凭据工作提交完成后，提交快照完整复验 903 项全部通过；未删除、跳过或弱化有效测试。
- 前端完整测试曾遇到并行数据同步页面与测试不同步，待该工作更新后完整快照 404 项通过。一次 E2E 从错误工作目录启动失败，按要求进入 frontend 后 2/2 通过。
- 新增真实桥接测试初次因测试 Tomcat 未注册 DispatcherServlet 返回 404；补齐测试容器路由及 WebSocket 初始化后通过。
- Java 17 HttpClient 在测试 Tomcat 关闭时出现默认工作线程尚未退出的提示；桥接断连和 Agent 回收断言通过，未发现活动 SSH 会话残留。后续可单独验证应用关闭时 HTTP 客户端执行器生命周期。
- 浏览器工具将 HTTP 到 HTTPS 的跳转视为离开预览来源，无法完成页面视觉验收。全屏布局、Tab 补全和 vim/top 等完整交互程序尚未在真实浏览器端验证，自动化传输测试不能替代该验收。
- 仅支持 SSH；LOCAL、文件传输、断线会话恢复不在范围。未配置指纹时保留已有自动信任行为，已配置指纹严格匹配。

### 重测触发与下次建议

- 修改终端/凭据业务、权限初始化、WebSocket 协议、代理或核心配置必须重跑上述相关及完整测试；业务代码差异须更新 Git 基准。
- 在真实浏览器登录后打开服务器“终端”，核对全屏/窄屏、Tab、Ctrl+C、vim/top、关闭与重新连接；验证部署网络长连接及实际账号权限撤销。
- 回滚可撤销终端代码提交并重新构建；保留已应用的 V38 迁移历史，不删除已有数据。

## 服务器用户名必填与账密匹配（2026-09-12）

### Git 基准与范围

Commit: 86bfcb63fe9bce45d7f56d281b8e0bec8ef1690d
- 提交信息：Match server password credentials to entered username；分支 master；测试日期 2026-09-12（Asia/Shanghai）。
- Vue 3 / Element Plus / Java 17 / Spring JDBC。修改服务器表单、服务器认证服务、中英文文案及正式测试，无新增依赖、数据库迁移或持久配置变更。
- 三种 SSH 认证方式均必填用户名。选择账密、秘钥或切换认证方式不覆盖用户名。账密按用户名去除首尾空白后精确匹配（区分大小写），同时检查类型、所有者及启用状态；未填写用户名时没有账密选项，秘钥不按用户名过滤。
- 修改用户名后解除不匹配的账密引用。后端新旧引用在保存、更新及执行时均校验账号匹配；凭据账号轮换不再静默改变服务器登录身份，不匹配返回 server.credentialUsernameMismatch。
- 本次从前一基准 a3b317a 继续，业务变更触发完整重测。共享工作区有正在开发的终端与数据同步改动，通过独立 Git index 与临时源码副本隔离；提交仅含上述八个文件中的本任务改动。

### 实际命令与结果

- 前端定向：根目录执行 `node --test frontend/test/servers.test.mjs frontend/test/server-credentials.test.mjs`，27/27 通过，失败 0、跳过 0。
- 在隔离副本的 frontend 执行 `npm run lint && npm run typecheck && npm run test:coverage`：退出 0，387/387 通过，失败 0、跳过 0；工具函数行覆盖率 98.40%、分支 80.95%、函数 95.27%，达到现有门槛，不代表 Vue 组件覆盖率。
- 隔离副本 frontend 执行 `node --test e2e/*.test.mjs`：1/1 通过，失败 0、跳过 0。复用已有 dist 验证生产 Node 服务、SPA、代理和畸形路径；新前端资源另由 Compose 构建并核验。
- 工作区后端定向容器命令：`docker run --rm -v "$PWD/backend:/source:ro" -v "$HOME/.m2:/root/.m2" -w /tmp/backend maven:3.9.9-eclipse-temurin-17 sh -c 'cp /source/pom.xml . && cp -R /source/src . && mvn -B -ntp -Dtest=ServerCredentialServiceTest,ServerManagementValidationTest,ServerManagementControllerTest test'`，66/66 通过（包括并行任务新增的一个校验用例）。
- 权威完整验证使用隔离源码：`docker run --rm -v /tmp/base-ai-username.4i0Qas/source/backend:/source:ro -v "$HOME/.m2:/root/.m2" -w /tmp/backend maven:3.9.9-eclipse-temurin-17 sh -c 'cp /source/pom.xml . && cp -R /source/src . && mvn -B -ntp test'`，868/868 通过，通过率 100%，失败 0、错误 0、跳过 0，BUILD SUCCESS。
- 关键模块：凭据服务 41/41、服务器校验 22/22、服务器 Controller 2/2、服务器监控 28/28；完整套件覆盖原有 Domain、Service、Repository、Controller、安全、工作流和数据同步回归。项目没有配置 Java 行覆盖率工具，本次不声称 Java 行覆盖率。
- 先更新前端回归断言并执行，旧实现的账号覆盖、允许空用户名和未按账号筛选按预期失败；实现后原断言目的均保留且全部通过。第一次隔离副本生成时零上下文补丁偏移导致 Java 编译失败，修正副本生成位置后重新运行完整测试通过，不涉及削弱测试。
- 工作区前端完整检查也通过，但包含并行任务测试；本报告以前述隔离副本 387 项作为本次提交的验收基准。

### 验收标准—测试用例映射

| 验收标准 | 层级与前置条件 | 输入与预期结果 | 场景 |
| --- | --- | --- | --- |
| 所有模式必填用户名 | 前端真实表单函数；三种模式与全部来源组合 | 有账号通过，账号清空拒绝；模板仅一个始终显示的账号输入 | 正常、边界、回归 |
| 账密仅展示可用匹配项 | 前端真实 availableCredentials；混合凭据列表 | deploy、Deploy、首尾空白、空值、不存在账号；仅同名同所有者启用 PASSWORD 返回，KEY 不按账号过滤 | 正常、边界、权限 |
| 用户名不被选择操作覆盖 | 前端真实来源切换与旧引用选择函数 | 切换账密或秘钥只清理相应秘密，手填账号保持 | 回归、兼容 |
| 修改用户名清除不匹配引用 | 前端真实 changeUsername；新旧两类引用 | 不匹配解除引用并清除秘密；匹配引用保留，独立秘钥不变 | 分支、边界、安全 |
| 后端阻止不匹配账号 | 参数化真实 H2 服务测试；新旧引用与密码/组合模式 | 空值、空白、root、Deploy、恶意账号均拒绝且数据库不新增服务器 | 异常、权限、安全 |
| 更新与执行维持账号一致 | 真实数据库、加密和服务 | 服务器修改为不匹配用户名失败且原账号不变；密码轮换继续可用，凭据改账号后执行报错 | 回归、兼容、状态冲突 |
| 旧安全边界保留 | 完整后端测试 | 失效/越权引用、类型错误、未登录、缺失材料、长度限制、秘密不回传与引用删除保护继续通过 | 安全、兼容、异常 |

### 部署与限制

- 使用本节代码基准作为 APP_IMAGE_REVISION，通过 Node 在内存读取隔离副本的 Compose 配置，将 build.context 与 additional_contexts 指向隔离源码，保留原项目 ai、环境、端口和卷，执行 `docker compose --project-directory /Users/xyzc/github/base-ai -p ai -f - up --build -d`；未写入额外配置文件，未把其他任务的未提交源码纳入构建。
- Compose 后端 package 再次运行 868/868 测试通过，未跳过测试；前端仅在 Compose 中编译，没有单独执行 npm run build 或 mvnw compile。
- Compose 命令退出 0；六个服务 caddy、frontend、backend、deployment-agent、document-parser、python-worker 均 healthy，镜像版本均为 86bfcb63fe9bce45d7f56d281b8e0bec8ef1690d，无端口冲突。内存比较 HTTPS 返回的 index-CncOwIDI.js、index-CBaMr6ME.css 与容器内资源 SHA-256 一致，JS 包含用户名匹配文案；curl -k 仅用于本机自签名证书。
- 环境已被并行任务应用 V38；本次不新增或回退迁移，Flyway 提示数据库版本高于本次源码 V37，但后端启动成功。并行任务的功能需由其后续构建重新部署。
- 浏览器真实交互、窄屏视觉和真实服务器账号连接未在本轮验证；已有预览有 HTTP→HTTPS 来源限制。建议浏览器验证三种模式、同名筛选、改用户名清除选择，再使用有效目标测试连接。前一节 SSH Agent 集成基准保留，本次未修改 Agent。
- 临时源码与独立索引 /tmp/base-ai-username.4i0Qas 已清理；Maven --rm 容器自动清除测试副本。正式测试保留，未新增 Markdown 文件。本任务改动均提交，工作区保留其他任务的未提交文件。
- 回滚仅需回退本次用户名匹配提交并重建，无数据迁移。下次修改认证、账号匹配、凭据解析或权限时重跑本节定向与完整套件并更新基准。

## 服务器三种认证与独立凭据来源（2026-09-12）

### Git 基准与实现范围

Commit: a3b317a67c7a7fd33c9a98a4497669114a6ec854
- 提交信息：Support independent server password and key credentials；分支 master；测试日期 2026-09-12（Asia/Shanghai）。
- Vue 3 / Element Plus / Java 17 / Spring JDBC / AES-GCM / MySQL Flyway；新增 V37 的两个可空引用列及外键，无新增依赖。
- 新增和编辑支持账号＋秘钥、账密、账密＋秘钥。密码和秘钥分别选择已有凭据或手工配置，可混搭。只有纯秘钥模式独立显示账号；账密模式从已选账密继承账号，手工账密在账密区域填写账号。
- 后端按类型和所有者解析引用、检查启用状态，执行时读取最新秘密；手工秘密加密存储，引用秘密不复制至服务器配置。新引用纳入删除保护，切换为手工或本地配置后解除引用；旧 credentialId 接口与记录继续兼容。
- 原报告基准 fd31496e 到实施前 HEAD 的业务代码无差异；本次业务变更已触发完整重测。本节基准不包含随后出现的其他任务终端功能改动。

### 实际执行命令与结果

- 根目录：`node --test frontend/test/servers.test.mjs frontend/test/server-credentials.test.mjs`，最终 25/25 通过，失败 0、跳过 0。
- frontend 工作目录：`npm run lint && npm run typecheck && npm run test:coverage`，退出 0，385/385 通过，失败 0、跳过 0。工具函数覆盖率：行 98.40%、分支 80.95%、函数 95.27%；不代表 Vue 组件覆盖率。
- frontend 工作目录：`node --test e2e/*.test.mjs`，1/1 通过，失败 0、跳过 0；覆盖生产 Node 服务、SPA、API 代理和畸形路径，不是浏览器 UI 测试。
- 本机 `mvn -B -ntp -Dtest=ServerCredentialServiceTest,ServerManagementValidationTest,ServerManagementControllerTest test` 与 `go test ./...` 因未安装 Maven/Go 返回 127，改用容器执行。
- 后端定向：`docker run --rm -v "$PWD/backend:/source:ro" -v "$HOME/.m2:/root/.m2" -w /tmp/backend maven:3.9.9-eclipse-temurin-17 sh -c 'cp /source/pom.xml . && cp -R /source/src . && mvn -B -ntp -Dtest=ServerCredentialServiceTest,ServerManagementValidationTest,ServerManagementControllerTest test'`，59/59 通过。随后增加执行时解析、凭据轮换和解除引用断言，由完整套件验证。
- 后端完整：`docker run --rm -v "$PWD/backend:/source:ro" -v "$HOME/.m2:/root/.m2" -w /tmp/backend maven:3.9.9-eclipse-temurin-17 sh -c 'cp /source/pom.xml . && cp -R /source/src . && mvn -B -ntp test'`，862/862 通过，通过率 100%，失败 0、错误 0、跳过 0，BUILD SUCCESS。
- 关键模块：凭据服务 35/35、服务器校验 22/22、服务器 Controller 2/2、服务器监控 28/28；完整套件包含既有 Domain、Service、Repository、Controller、安全、数据同步等回归。后端未配置 JaCoCo，本次不声称 Java 行覆盖率。
- Go 普通回归：`docker run --rm -v "$PWD/deployment-agent:/source:ro" -w /tmp/agent golang:1.26.6-alpine sh -c 'cp /source/go.mod /source/main.go /source/main_test.go /source/data_sync_test.go . && go test -count=1 ./...'`，退出 0。
- Go 真实 SSH 集成：`docker run --rm --tmpfs /tmp:rw,noexec,nosuid,nodev -e GOTMPDIR=/build -v "$PWD/deployment-agent:/source:ro" -w /workspace golang:1.26.6-alpine sh -c 'apk add --no-cache openssh >/dev/null && mkdir -p /run/sshd /build && cp /source/*.go /source/go.mod . && go test -tags integration -cover -v ./...'`，31 个顶层测试及参数场景全部通过，失败 0、跳过 0，语句覆盖率 56.4%。包含实际 sshd 的秘钥、密码、组合认证及加密秘钥场景。
- 首次前端定向出现 2 项失败：旧模板断言匹配直接赋值事件、缺少认证类型的表单未拒绝；已更新事件契约并补充类型校验，保留原测试目的后全部通过。首次后端测试编译因新增测试调用私有方法失败，改为通过公开列表接口验证后通过。

### 验收标准—测试用例映射

| 验收标准 | 层级与前置条件 | 输入与预期结果 | 场景 |
| --- | --- | --- | --- |
| 三种方式、选择与手填可混搭 | 前端真实函数；后端真实 H2、实际迁移及加密 | 三种方式全部来源组合通过校验；组合模式四种来源保存并解析正确材料 | 正常、兼容 |
| 账密无需重复填写账号 | 前端真实函数及模板契约；后端服务 | 选账密且 username 为空仍成功，服务端继承 deploy；仅纯秘钥模式显示独立账号 | 正常、边界 |
| 引用不复制秘密且动态解析 | 后端 independentCredentialCombinations 参数测试 | 加密配置中引用秘密为空，执行目标返回正确材料；轮换账密后使用新账号和密码 | 安全、回归 |
| 切换来源清理与解除保护 | 前端来源切换函数；后端真实数据库 | 切换账密不清除秘钥；切换秘钥清理口令；服务器改为手工密码后清除双引用、移除旧秘钥并允许删除凭据 | 边界、回归 |
| 非法引用拒绝且不创建记录 | 后端 rejectsInvalidIndependentReference 参数测试 | 跨用户、停用、类型不符、不存在四种引用均拒绝且数据库无新增服务器；前端失效引用不可提交 | 异常、权限 |
| 既有安全与旧接口保持 | 完整后端与前端既有测试 | 空材料、长度边界、恶意身份、未登录、删除冲突、旧单凭据和监控用例通过 | 安全、兼容、回归 |
| SSH 能实际完成三种认证 | Go 真实 sshd 集成测试 | 公钥、密码、组合及带口令私钥分别完成认证，错误与超时按预期处理 | 正常、异常 |

### 构建、运行与限制

- 首次裸 `docker compose up --build -d` 因缺少必填 APP_IMAGE_REVISION 失败；按项目已有用法执行 `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d` 后退出 0。构建中后端 package 再次执行 862 项测试全部通过，未跳过测试；未单独运行前端 build 或后端 compile。
- 构建启动于代码提交前，镜像标签为当时 HEAD 1cbe5c41d42b82daa9d5af3d68fae8b039b1d7e0，但构建上下文包含本节 a3b317a 的变更。后续出现其他任务未提交改动，未把它们纳入本次重建或提交。
- MySQL 日志确认 V36→V37 迁移成功；六个容器 caddy、frontend、backend、deployment-agent、document-parser、python-worker 均 healthy。无端口冲突。
- 内存中比较 HTTPS 返回的 JS/CSS 与运行容器资源 SHA-256，一致；JS 包含 passwordCredentialId 和“账密＋秘钥”。curl -k 仅用于本机自签名证书，未保存调试文件。
- 浏览器最初 5173 预览服务未运行；改开运行中的 80 端口后跳转 HTTPS，预览工具因来源变化拒绝继续操作。浏览器实际点击、下拉选择及窄屏视觉验收未完成，自动化函数与模板断言不替代这些验收。建议在浏览器打开服务器新增页，逐一核验三种模式和选择/手填混搭。
- Go 集成及 Maven 使用 --rm 容器，所有临时源副本、构建产物和 SSH 测试材料随容器清除；未创建工作区临时文件。正式测试保留；git diff --check 通过。
- 回滚时先将双引用服务器改为旧版支持的配置，再回退本次代码；可保留新增可空数据库列。未经数据确认不要删除列。
- 下次修改认证来源、账号继承、引用解析/删除保护或相关业务配置时，重跑本节定向与完整套件并更新基准；其他任务的后续业务改动需独立验证。

## 凭据文件导入与长文本编辑（2026-09-11）

### Git 基准与范围

Commit: fd31496e74f34d0c07f000dfb33808ffe1810aee
- 提交信息：Improve credential file import and full text editing；分支 master，测试日期 2026-09-11。
- 仅修改 ServerCredentialManager.vue、中英文语言资源、server-credentials.test.mjs；Vue 3 / Element Plus / 浏览器 File API / TextEncoder，无新增依赖、后端接口、配置或迁移变更。
- 弹窗加宽、减少多层卡片，三个长文本字段全宽独占行。私钥默认 8 行、其他材料 4 行，自动增高至 16 行后滚动；等宽字体、视觉自动折行、无 maxlength 截断。每个字段支持全屏展开编辑，通过同一 form 字段即时同步，收起保留输入。
- 上传区域支持文件选择和拖拽；显示文件名、UTF-8 字节大小、读取中/成功/失败状态。空文件、超出 32 KiB 和读取失败分别提示；失败保留已有文本，敏感内容不写浏览器存储。关闭、切换类型及手动编辑使异步读取结果失效，读取期间阻止保存。
- 展示字节用量，手动粘贴超限保留全文并禁止提交，不进行自动格式化、截断或修改内容。历史秘密留空保留的原行为不变。

### 实际测试命令及结果

- 定向：frontend 工作目录执行 `node --test test/server-credentials.test.mjs test/servers.test.mjs`，23/23 通过，失败 0、跳过 0。
- 完整：frontend 工作目录执行 `npm run lint && npm run typecheck && npm run test:coverage && node --test e2e/*.test.mjs`，退出 0；383/383 单元测试通过，失败 0、跳过 0；生产服务 E2E 1/1 通过。工具函数覆盖率：行 98.40%、分支 80.95%、函数 95.27%，原门槛全部通过；不代表 Vue 组件的行覆盖率。
- 首次误在仓库根执行 npm run typecheck，因根目录无 package.json 报 ENOENT；随后在 frontend 正确目录执行通过，不是应用缺陷。
- `git diff abbb8cbb33283b6e27d65ff6a3721b6f04f71643 HEAD -- backend/src/main/java/` 无输出，本次未单独重跑后端测试，沿用最近后端 854/854 基准。
- `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d` 配合 set -o pipefail 运行，退出 0；没有跳过构建测试或单独运行前端 build/后端 compile。

### 验收标准—用例映射

| 验收标准 | 层级/前置条件 | 输入和预期结果 | 测试与场景 |
| --- | --- | --- | --- |
| 文件选择和拖拽可导入完整原文 | 组件真实函数 + 真实文件读取工具 | 多行带空白原文及 32768 字节内容，两种事件均完整导入并显示名称与字节数 | 本地文件导入保留原文并区分空文件超限及读取失败；正常/边界 |
| 失败时保留已有内容 | 同上，原文为 existing | 空/空白文件、32769 字节、多字节中文超限、读取异常返回对应错误，原文不变 | 同上；边界/异常 |
| 异步读取不复活秘密或覆盖新编辑 | 真实组件函数，可控延迟文件读取 | 读取中关闭/切换/手工编辑后完成旧请求，不覆盖新内容并清除文件状态 | 过期文件读取不会覆盖关闭或修改后的表单；异步/安全/回归 |
| 长文本不截断且按字节限制保存 | 真实保存函数，隔离 HTTP | 私钥/公钥/证书分别输入多行、超长单行、32768/32769 字节及中文；超限无请求且原文保留，合法文本提交保持原文 | 长文本不截断且超限阻止保存；正常/边界 |
| 内联与全屏使用同一原文、视觉换行 | 模板契约检查 | 两个输入 v-model 均绑定相同 form 字段，wrap=soft，三个长字段无 maxlength | 同上；结构/回归；实际浏览器交互仍待验收 |
| 原有凭据流程兼容 | 现有全套前端测试 | 保存、权限、删除冲突、类型切换、秘密清理、服务器引用等行为保持 | 完整 383 项；回归 |

### 部署验证与待验收项

- 六个容器 caddy、frontend、backend、deployment-agent、document-parser、python-worker 均运行 fd31496e74f34d0c07f000dfb33808ffe1810aee，健康检查全部 healthy，未发生端口冲突。
- HTTPS 首页实际加载 index-B2Bz4_nB.js、index-Dqogo9SA.css；curl 获取的文件与 docker exec 读取的镜像文件 SHA-256 一致，包含“展开编辑”“拖拽私钥文件”及 expanded-material/material-editor 样式。核验在内存完成，curl -k 仅用于本机自签名证书。
- 浏览器预览工具因 HTTP 来源跳转到 HTTPS 来源而拒绝操作，因此桌面/窄屏视觉检查、文件选择器交互以及全屏实际展开收起未完成；自动化函数和模板检查、资源核验不能替代这些验收。建议浏览器打开新增凭据→秘钥，导入文件后逐个展开三个长文本区，在桌面和窄屏核对全文可滚动访问及编辑同步。
- 本轮无新增调试文件、临时测试文件或额外测试服务。git diff --check 通过，修改按范围提交。并行数据同步测试报告提交保留，不纳入本次代码提交。
- 下次涉及文件读取、编辑同步或长度限制时重跑本节相关用例及完整前端检查；涉及后端业务变更时重跑完整后端测试并更新基准。无数据库变更，可通过回退本次前端代码并重建回滚。

## 数据同步界面样式优化（2026-09-11）

### 自动化测试基准与范围

Commit: 5cc7605f3bfe02bfcb697048aa5da395d98d5176
- 提交信息：Improve data synchronization workspace layout；分支：master；测试日期：2026-09-11。
- 本次仅修改 DataSyncView.vue、中英文语言资源与现有 data-sync-remote.test.mjs，使用 Vue 3、Element Plus、现有图标库及 scoped CSS；没有新增依赖、修改后端、运行配置或数据库。
- 页头和计划概览、编号配置分区、来源到目标的数据流布局、表选择卡片及空状态、已选数量、计划列表、语义状态标签、手动调度提示和弹窗排版统一优化；窄屏使用单列配置及可横向滚动表格，避免固定操作列遮挡内容。
- 保留原有服务器选择、接口参数、权限判断、预检、保存、执行、取消、重试、删除和全量替换确认逻辑。状态格式化兼容空值及未知状态，中英文均提供文案。
- 任务开始时已有的凭据修改由其他工作单独提交，本次没有纳入。最初检查 d559cdc 到 HEAD 发现凭据业务代码差异，因此额外执行后端完整测试；最终检查 `git diff abbb8cbb33283b6e27d65ff6a3721b6f04f71643 HEAD -- backend/src/main/java/` 无输出。
- 自动化与部署检查通过；浏览器视觉和交互验收受预览环境限制，尚未完成，不能据此认定全部验收完成。

### 实际执行命令与结果

- 根目录执行 `node --test frontend/test/data-sync-remote.test.mjs`：13/13 通过，失败 0、错误 0、跳过 0；最后补充未知状态原型键安全回退后再次通过。
- frontend 工作目录执行 `npm run lint && npm run typecheck && npm run test:coverage`：退出 0，380/380 通过，失败 0、跳过 0；最终代码重新执行并通过。工具函数行覆盖率 98.40%、分支 80.95%、函数 95.27%，达到现有门槛；此指标不代表 Vue 页面或 Java 代码行覆盖率。
- frontend 工作目录执行 `node --test e2e/*.test.mjs`：1/1 通过，失败 0、跳过 0。该套件验证生产 Node 前端服务的运行配置、SPA、API 代理及畸形路径，不是浏览器 UI 测试。没有单独执行 npm run build；Vite 编译在 Compose 内完成。
- 根目录执行 `docker run --rm -v "$PWD/backend:/source:ro" -v "$HOME/.m2:/root/.m2" -w /tmp/backend maven:3.9.9-eclipse-temurin-17 sh -c 'cp /source/pom.xml . && cp -R /source/src . && mvn -B -ntp test'`：854/854 通过，通过率 100%，失败 0、错误 0、跳过 0，BUILD SUCCESS。包含数据同步、Domain、Service、Repository、Controller 等现有套件；凭据 27/27、服务器校验 22/22、服务器监控 28/28 通过。本次没有新增 Java 业务逻辑。
- 根目录执行 `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`：最终退出 0；镜像版本为本节基准提交。首次工作树构建成功后，按最终提交版本再次构建成功，确保状态回退修正也已部署。运行时依赖下载约两分钟，无端口冲突。
- `docker ps --filter name=ai- --format '{{.Names}} {{.Status}} {{.Image}}'`：frontend、backend、caddy、deployment-agent、document-parser、python-worker 六个服务均 healthy，镜像均为本节基准版本。
- 使用 `node --input-type=module` 内存脚本调用 `docker exec ai-frontend cat /app/dist/index.html` 与 `curl -kfsS --retry 3 https://localhost/data-sync`，以及入口中的 JS/CSS 资源，逐项比较 SHA-256：页面和 2 个资源均与运行镜像一致，CSS 包含 sync-workspace 与 sync-flow。脚本未写入文件，curl -k 仅用于本机证书验证。资源匹配不能代替页面视觉和交互验收。
- `git diff --check` 与 `git diff --cached --check` 通过；代码提交只包含本次四个文件，测试报告单独提交。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入、预期结果及覆盖场景 | 实际结果 |
| --- | --- | --- | --- |
| 页头、配置、计划列表层次清晰，桌面和窄屏无页面溢出 | 浏览器；有权限用户访问数据同步页面 | 1440px/390px，空计划、长计划名及大量长表名；分区清晰、控件可操作、表格局部滚动；正常/边界 | 未执行；预览来源限制 |
| 服务器、表查询、预检和历史计划行为兼容 | 现有前端定向契约测试；读取实际页面源码 | 启用服务器约束、查询和预检携带 serverId、历史本机回退及编辑回填；正常/兼容/回归 | 原有 3 项通过；浏览器编辑、预检流程待验收 |
| 状态颜色和中英文文案完整 | 执行实际页面 statusType/statusLabel 方法；注入真实语言资源 | 参数化 PENDING、RUNNING、SUCCEEDED、SUCCESS、FAILED、CANCEL_REQUESTED、CANCELLED、SKIPPED；应返回对应语义颜色与文案；正常/分支 | 8 项通过 |
| 缺失或未知状态兼容且安全回退 | 执行实际状态格式化方法 | null、undefined、空字符串、未来状态、__proto__、toString、脚本文本；缺失值显示尚未运行，其余保留文本并使用 info；边界/兼容/恶意输入 | 1 项通过；文本转义依赖 Vue 插值，未声称验证浏览器 XSS |
| 权限及危险操作确认保留 | 页面契约测试；读取实际模板与绑定 | 创建/更新切换、各操作权限、FULL_REPLACE 确认、selectedTables 绑定、执行/取消禁用条件；权限/安全/回归 | 1 项通过；真实不同权限用户和点击流程待验收 |
| 服务与已有功能无自动化回归 | 前端完整测试、Node 生产服务 E2E、后端完整测试 | 全部现有测试、覆盖率门槛及统一构建；兼容/异常/回归 | 380/380、1/1、854/854，构建部署成功 |

### 已知限制、清理与下次验证

- 内置浏览器将 http://localhost:5173 自动转到 HTTPS，HTTP Vite 服务出现 ERR_SSL_PROTOCOL_ERROR；临时在内存中复用本机 Caddy 证书启动 HTTPS Vite 后，工具仍以“离开预览应用来源”为由拒绝访问；127.0.0.1 同样被来源限制拒绝。Vite 原有 API 代理指向未暴露的 localhost:8080，还出现 ECONNREFUSED。没有改动项目代理或 TLS 配置。
- 查找已有隔离浏览器时仅找到 Playwright 包，没有可用浏览器二进制；未新增浏览器依赖。因此没有完成有数据/空数据、长名称、多表、窄屏、中英文切换、不同权限、编辑预检和详情弹窗的真实浏览器验收，也没有发起真实数据同步或覆盖写入。
- 建议登录 https://localhost/data-sync，在桌面与窄屏逐项执行上述浏览器用例，再补充验收结果；本次视觉效果仍需人工确认。
- 临时 HTTP/HTTPS Vite 进程均已停止；没有保存调试文件、截图或临时测试代码。Maven 副本位于自动删除测试容器的 /tmp/backend，容器退出后清除；证书仅在内存中读取，没有输出或保存。
- 下次修改后端业务代码、Domain、Repository、Service、Controller 或核心业务配置时，比较本节基准并重跑完整测试；修改本页面需重跑前端定向、完整检查及浏览器验收。
- 回滚方式：撤销本次独立界面提交并统一重建部署；无数据迁移或配置回滚需求。

## 私钥凭据不维护 SSH 用户（2026-09-11）

### Git 基准点

Commit: abbb8cbb33283b6e27d65ff6a3721b6f04f71643
- 提交信息：Keep SSH usernames on servers for key credentials；分支：master；测试日期：2026-09-11。
- 相对上一基准 d559cdcf4af42b02e181a9f0d850588299e89bcf，业务修改仅 ServerCredentialService；技术栈仍为 Spring Boot/JDBC/AES-GCM 与 Vue 3/Element Plus，无新增依赖、配置或数据库迁移。
- 私钥表单移除账号，列表显示“—”，切换私钥清空账号，提交不携带账号值。服务端忽略私钥请求中的账号，保存空账号；读取 KEY 或被识别为 KEY 的历史 RSA 记录时返回空账号，因此绑定与运行解析均使用服务器自身 SSH 用户。旧数据库账号不批量清理，编辑时清空。账号密码和历史组合凭据维持原规则。

### 实际测试结果

- 先补充失败测试：Docker 内执行 `mvn -B -ntp -Dtest=ServerCredentialServiceTest test`，27 项中通过 24、失败 2、错误 1、跳过 0；复现历史私钥账号仍返回以及私钥账号仍参与校验。
- 修复后在根目录执行 `docker run --rm -v "$PWD/backend:/source:ro" -v "$HOME/.m2:/root/.m2" -w /tmp/backend maven:3.9.9-eclipse-temurin-17 sh -c 'cp /source/pom.xml . && cp -R /source/src . && mvn -B -ntp -Dtest=ServerCredentialServiceTest,ServerManagementValidationTest,ServerManagementControllerTest,ServerManagementMonitorTest test && mvn -B -ntp test'`：定向 79/79（凭据 27、校验 22、Controller 2、监控 28）；完整 854/854，通过率 100%，失败 0、错误 0、跳过 0。
- frontend 工作目录：`node --test test/server-credentials.test.mjs test/servers.test.mjs` 20/20；`npm run lint && npm run typecheck && npm run test:coverage && node --test e2e/*.test.mjs` 全部退出 0，完整 370/370、E2E 1/1，失败和跳过均为 0。工具函数行覆盖 98.40%、分支 80.95%、函数 95.27%，通过原门槛；不表示 Vue 组件或 Java 行覆盖率。

### 验收标准—测试用例映射

| 验收标准 | 层级/前置条件 | 输入及预期结果 | 用例/场景 |
| --- | --- | --- | --- |
| 私钥不保存账号且独立于账号校验 | Service，真实 H2/加密及已登录用户 | 私钥附带非法账号仍可保存，但数据库、元数据账号为空 | keyCredentialsUseEachServersUsername；正常/非法输入兼容 |
| 同一私钥由不同服务器用户复用 | Service，已保存私钥 | deploy、root 分别绑定，执行目标保留各自账号及私钥；服务器未填用户拒绝 | keyCredentialsUseEachServersUsername；正常/边界 |
| 历史私钥账号不覆盖服务器 | Service，参数化 KEY/RSA 历史数据 | old-user 不返回；服务器使用 server-user；编辑清空旧账号且保留密文 | ignoresLegacyKeyUsername；历史兼容/编辑/回归 |
| 表单只在密码类型维护账号 | 前端真实函数和模板检查 | KEY 新建/编辑含旧账号提交空账号；切换清空；PASSWORD 保留账号 | 两类凭据的必填校验与秘密保留、切换私钥类型清理账号与不适用的临时输入、私钥表单和列表不展示凭据账号；正常/边界/界面 |
| 账号密码、轮换与权限回归 | 既有 Service 及前端测试 | 密码账号自动带入、私钥轮换 Agent 使用服务器 deploy 用户、未授权访问拒绝 | sharesCredentialsAndResolvesLatestValues、passesRotatedCredentialsToAgentWithoutExposingThem、enforcesOwnershipAndAdminSecretAccess；回归/安全 |

### 部署与已知限制

- `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d` 配合 set -o pipefail 执行并成功退出 0，未跳过构建测试，未发生端口冲突。
- `docker ps --filter name=ai- --format '{{.Names}} {{.Image}} {{.Status}}'` 确认六个服务均运行 abbb8cbb33283b6e27d65ff6a3721b6f04f71643 且 healthy。
- HTTPS 首页资源更新为 index--9GQB9AL.js 和 index-DeqBoXIV.css。curl 获取内容与 docker exec 读取的容器文件逐一比较 SHA-256，均一致；实际脚本包含“SSH 用户在服务器中填写”。本机自签名 HTTPS 核验使用 curl -k。
- 未执行登录后的浏览器视觉验收或真实远程 SSH 验证；自动化已覆盖真实服务逻辑、数据库和回环 Agent。建议通过浏览器核对私钥弹窗及两台服务器不同 SSH 用户的真实连接。
- 无临时调试文件；测试源码仅复制到 --rm 容器中并随退出清除。git diff --check 通过，只提交本次相关文件。
- 后续业务代码、实体、Repository、Service、Controller 或核心业务配置变化，按本节基准重新执行完整测试并更新报告。回滚代码前需评估已编辑私钥账号被清空的影响，不会自动恢复旧账号；服务器维护的 SSH 用户保持可用。

## 凭据类型修复与管理界面完善（2026-09-11）

### Git 基准点

Commit: d559cdcf4af42b02e181a9f0d850588299e89bcf
- 提交信息：Fix credential type lifecycle and improve management interface
- 分支：master；测试日期：2026-09-11。
- 相对已验证基准 f1d1cde80ba6c1a9768fb532c8821dad71a93579，业务变更集中于 ServerCredentialService；执行定向和完整测试。以下历史章节的失败与旧基准保留作为过程记录，以本节为最新验收记录。

### 实现与影响范围

- KEY 与 PASSWORD 严格互斥，编辑持久化 credential_type；未引用记录切换类型清除另一类型的旧密文，已引用记录禁止切换。私钥、密码和口令继续 AES-GCM 加密，列表不返回秘密。
- RSA 历史单一私钥或纯账号密码记录在读取时识别为对应类型；历史组合及其他材料记录仍标记为历史凭据，继续服务原有连接，转换时要求解除引用。不执行批量迁移、不删除历史数据。
- Vue 3 / Element Plus 管理弹窗新增顶部说明区、搜索与类型筛选、认证卡片、滚动分组表单、状态标签、历史提示、中英文文案及移动端布局；类型切换清理不适用的临时输入，保存前检查必填材料，明文弹窗仅展示已设置字段。
- 服务器新增界面只提供两种认证选项，历史组合服务器保留兼容展示；原服务器引用权限、连接和监控流程保留。公钥和证书仅在私钥类型作为可选存档，未新增 SSH 证书认证支持。
- 未新增依赖、修改运行配置或数据库迁移。回滚需评估新类型记录兼容性后回退应用镜像；显式切换类型清除的旧秘密不能靠代码回滚恢复，应通过凭据重新配置恢复。

### 实际测试命令与结果

1. 先补充切换类型、混合输入失败用例，再执行 Docker Maven 定向测试：ServerCredentialServiceTest 20 项，失败断言 2、错误 8；稳定复现缺少消息资源和切换残留秘密问题。没有跳过或删除失败用例。
2. 项目根目录执行：`docker run --rm -v "$PWD/backend:/source:ro" -v "$HOME/.m2:/root/.m2" -w /tmp/backend maven:3.9.9-eclipse-temurin-17 sh -c 'cp /source/pom.xml . && cp -R /source/src . && mvn -B -ntp -Dtest=ServerCredentialServiceTest,ServerManagementValidationTest,ServerManagementControllerTest,ServerManagementMonitorTest test && mvn -B -ntp test'`。定向 76/76 通过；完整 851/851 通过，通过率 100%，失败 0、错误 0、跳过 0。关键模块：凭据 24、服务器校验 22、监控 28、Controller 2。
3. `node --test frontend/test/server-credentials.test.mjs frontend/test/servers.test.mjs`：18/18 通过；随后新增搜索组合测试，由最终完整前端套件覆盖。
4. frontend 工作目录执行 `npm run lint && npm run typecheck && npm run test:coverage && node --test e2e/*.test.mjs`：退出 0，完整前端 369/369 通过、失败 0、跳过 0；生产服务 E2E 1/1 通过。工具函数覆盖率：行 98.40%、分支 80.95%、函数 95.27%，均通过既有门槛。该覆盖率不代表 Vue 组件或后端行覆盖率。
5. `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`：成功退出 0，构建内 `mvn -B -ntp package` 再次验证 851/851 通过。通过 set -o pipefail 保留 Compose 真实失败状态；没有跳过测试。

### 验收标准—测试用例映射

| 验收标准 | 层级与前置条件 | 输入与预期结果 | 用例与场景 |
| --- | --- | --- | --- |
| 两种类型互斥且必填有效 | Service / 独立 H2、真实加密、已登录用户 | 两类混合材料、空值、掩码新建均拒绝且不落库；正常分别创建成功 | rejectsMixedMaterials、rejectsMissingSecrets、rejectsInvalidFields；正常/边界/异常/恶意账号 |
| 编辑持久化类型并清理旧秘密 | Service / 未引用记录 | PASSWORD→KEY→PASSWORD 后类型正确且另一类密文为空；空更新保留现有秘密 | switchesTypeAndClearsOldSecrets、storesEncryptedMaterialsAndPreservesSecretsOnEdit；分支/回归 |
| 历史数据与服务器引用兼容 | Service / SQL 构造真实历史 RSA 数据及服务器引用 | 历史组合仍可解析两类材料；引用中转换拒绝；纯密码记录可保留密文更新类型 | preservesLegacyCombinedCredentials、protectsReferencedTypeAndNormalizesLegacyAccount、switchesReferencesAndUsesServerUsernameForKeyOnly；兼容/状态冲突 |
| 轮换与访问安全保持有效 | Service / 多服务器共享引用、不同用户、回环 HTTP Agent | 密码与私钥轮换分别生效；外部响应不含秘密；越权访问、删除和管理员明文权限按原规则处理 | sharesCredentialsAndResolvesLatestValues、passesRotatedCredentialsToAgentWithoutExposingThem、enforcesOwnershipAndAdminSecretAccess；权限/回归 |
| 前端校验及字段清理 | 组件真实函数 / 隔离 HTTP 与消息框 | 两种类型的新建、空编辑、缺账号、掩码和切换；验证实际提交字段与清理副作用 | 两类凭据的必填校验与秘密保留、切换类型清理临时输入且保留账号、凭据创建编辑保留材料并在成功后清除敏感输入；正常/边界/异常 |
| 搜索和类型筛选 | 组件真实 computed / 不同类型元数据 | 大小写、首尾空白、空账号、历史记录及无匹配输入返回正确记录集合 | 凭据搜索和类型筛选返回匹配记录；正常/边界 |
| 新界面实际部署 | Compose / 六个服务与 HTTPS 入口 | 新镜像健康，网页 JS/CSS 与容器文件哈希一致且包含新组件标记 | 实际资源核验；部署回归 |

### 部署核验与限制

- `docker ps --filter name=ai- --format '{{.Names}} {{.Image}} {{.Status}}'`：caddy、frontend、backend、deployment-agent、document-parser、python-worker 六个运行容器均使用 d559cdcf4af42b02e181a9f0d850588299e89bcf 且 healthy。未发生端口冲突。
- HTTPS 实际页面从旧 index-Bf4E_5sC.js / index-dvKesZJx.css 更新为 index-DM0gXP7q.js / index-CAAdJsNO.css。使用 curl 与 docker exec cat 读取两端文件，在内存中比较 SHA-256：JS 为 37e45297aa314cf21a8585451261e6388d85c8ba7706c019f78d4b443bf37670，CSS 为 11665562d51f9ed37db0d635aae678bf884bca298d500342dd6b66a4fe4e8fe2，均相同。实际脚本包含 credential-hero，样式包含 credential-filters。
- 资源核验首次受 Node execFileSync 默认缓冲上限影响报 ENOBUFS；将仅内存缓冲提高至 20 MiB 后校验退出 0，不涉及应用修改。curl -k 仅用于本机自签名证书。
- `curl -ksS -o /dev/null -w '%{http_code}' https://localhost/api/server-credentials`：未登录返回 401。
- 浏览器预览停在 HTTP→HTTPS 跳转限制，工具拒绝跨预览来源导航。因此没有完成登录后的真实浏览器操作、窄屏视觉验收与真实 SSH 远程连接验证；不能将资源哈希验证等同于这些验收。建议在本机 HTTPS 页面登录验证两类凭据增改、搜索筛选及服务器连接；自动化服务测试使用回环 Agent 隔离外部依赖。
- 未创建调试文件或临时测试文件；Maven 源码副本随 --rm 测试容器清除；未留下额外服务进程。git diff --check 通过，仅提交当前任务文件。
- 下次修改业务代码、实体、Repository、Service、Controller 或核心业务配置时，必须相对本节 Git 基准点检查差异并重跑完整测试、更新报告；真实 SSH 与浏览器布局验证仍建议补充。

## 容器未更新排查（2026-09-11）

- 待验证提交：3ad5ee0c95b52b073fedb46752e2018318f29a14。该提交未通过验收，不更新已通过测试的基准点。
- 实际运行：ai-frontend、ai-backend 等容器仍使用 f1d1cde80ba6c1a9768fb532c8821dad71a93579 镜像。
- 执行 `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`；构建内 `mvn -B -ntp package` 失败，844 项测试中通过 837、错误 7、失败断言 0、跳过 0；因此未部署新版本。外层输出经 tail 截取，管道退出码不代表 Compose 成功。
- 错误用例均属于 ServerCredentialServiceTest：enforcesOwnershipAndAdminSecretAccess、passesRotatedCredentialsToAgentWithoutExposingThem、sharesCredentialsAndResolvesLatestValues、storesEncryptedMaterialsAndPreservesSecretsOnEdit、supportsBoundariesAndReportsUnreadableSecret、switchesReferencesAndUsesServerUsernameForKeyOnly、updatesLegacyServerAndUnlinksLocalMode。组合材料触发新互斥校验后，缺少 server.credentialTypeConflict 消息资源导致 MissingResourceException；这些问题由此前类型拆分引入，尚未修复。
- 前端定向 `node --test frontend/test/server-credentials.test.mjs frontend/test/servers.test.mjs`：16/16 通过。frontend 工作目录执行 `npm run lint && npm run typecheck && npm run test:coverage`：退出 0，覆盖率门槛通过，工具函数行 98.40%、分支 80.95%、函数 95.27%。现有前端测试通过不能证明新旧凭据兼容或页面部署成功。
- 未执行浏览器登录后验收和本轮前端 E2E。下一步需修复凭据类型保存、旧数据兼容、消息资源和相关测试，再运行完整后端测试及统一构建，最后核验容器镜像和浏览器资源版本。
- 本次没有创建调试文件，没有跳过测试部署失败版本。此前聊天中“构建启动成功、完整测试通过”的结论不成立。

## 可复用服务器秘钥与账号密码管理（2026-09-11）

### Git 基准点与实现范围

Commit: f1d1cde80ba6c1a9768fb532c8821dad71a93579
- 提交信息：Complete reusable server credential management；分支：master；测试日期：2026-09-11。
- 本次基于此前未完成的凭据后端骨架继续开发。检查旧基准 cb5ee140660424ff340234a8daefe313021f8464 与 HEAD 的业务代码差异后，执行完整后端与前端验证。
- 技术栈：Java 17 / Spring Boot / JDBC / MySQL / Flyway、Vue 3 / Element Plus、AES-GCM；未新增依赖或修改运行配置，Python 运行镜像保持 3.12。
- “服务器管理 → 秘钥管理”支持标签、RSA 类型、公钥、私钥、证书、账号、密码和私钥口令；支持仅保存账号密码。新建 SSH 服务器选择同一所有者的启用凭据，账号自动带入；无账号的密钥可使用服务器填写的账号。
- 服务器持久化凭据引用，不复制秘密；连接测试、监控、部署的共享 Agent 载荷及数据同步执行目标按需读取最新凭据。旧服务器原有直接凭据保留，可编辑切换引用；未执行旧凭据的批量抽取或自动迁移。
- 私钥、密码、口令加密保存；列表仅返回设置状态，管理员显式明文查看使用 no-store 响应并在弹窗关闭后清理。创建、更新、明文查看禁止捕获请求快照。引用中的凭据不得删除或停用；绑定和删除使用相同凭据行锁。
- V35 创建凭据表与服务器引用列，V36 补充私钥口令密文列。生产 MySQL 已从 V34 成功迁移至 V36，没有删除历史字段或数据。

### 实际执行命令与结果

- 后端定向：在项目根目录执行 `docker run --rm -v "$PWD/backend:/source:ro" -v "$HOME/.m2:/root/.m2" -w /tmp/backend maven:3.9.9-eclipse-temurin-17 sh -c 'cp /source/pom.xml . && cp -R /source/src . && mvn -B -ntp -Dtest=ServerCredentialServiceTest,ServerManagementValidationTest,ServerManagementControllerTest,ServerManagementMonitorTest test && mvn -B -ntp test'`。定向 69/69 通过，失败 0、错误 0、跳过 0；随后完整 844/844 通过。
- 后端最终代码完整复核：`docker run --rm -v "$PWD/backend:/source:ro" -v "$HOME/.m2:/root/.m2" -w /tmp/backend maven:3.9.9-eclipse-temurin-17 sh -c 'cp /source/pom.xml . && cp -R /source/src . && mvn -B -ntp test'`，844/844 通过，通过率 100%，失败 0、错误 0、跳过 0。
- 关键定向模块：ServerCredentialServiceTest 17/17、ServerManagementValidationTest 22/22、ServerManagementMonitorTest 28/28、ServerManagementControllerTest 2/2。使用真实 AES-GCM、H2 和实际 Flyway SQL；HTTP Agent 用回环测试服务器隔离外部依赖，未 Mock 凭据核心读写或加解密逻辑。
- 前端定向：`node --test frontend/test/server-credentials.test.mjs frontend/test/servers.test.mjs`，16/16 通过，失败 0、跳过 0。
- 前端完整检查：在 frontend 工作目录执行 `npm run lint && npm run typecheck && npm run test:coverage`，全部通过；366 项单元测试通过，失败 0、跳过 0。工具函数行覆盖率 98.40%、分支 80.95%、函数 95.27%；此统计不表示 Vue 组件或后端的代码覆盖率。
- 前端生产服务 E2E：在 frontend 工作目录执行 `node --test e2e/*.test.mjs`，1/1 通过，失败 0、跳过 0；覆盖生产 Node 服务的 SPA、API 代理与错误路径，不等同于登录后的浏览器全流程验收。
- 统一重建与启动：`APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`，退出 0。镜像版本为上述代码基准点；前端 Vite 编译与后端 Maven package 在 Compose 构建内执行。未单独运行后端 compile 或前端 npm run build。
- 运行验证：`docker ps --filter name=ai- --format '{{.Names}} {{.Status}}'` 确认 backend、frontend、deployment-agent、document-parser、python-worker、caddy 六个容器全部 healthy；后端日志确认两项迁移成功，当前版本 V36，应用正常启动。未发生端口冲突。
- 未登录接口验证：`curl -k -s -o /dev/null -w 'credential endpoint unauthenticated HTTP %{http_code}\n' https://localhost/api/server-credentials`，返回 401；本机自签名证书仅在此验证命令中使用 -k。
- 变更检查：`git diff --check`、`git diff --cached --check` 通过。Maven 在自动删除的测试容器中复制源码，临时文件随容器销毁；工作区未创建调试文件，没有提交临时测试脚本。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期及实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 保存完整材料且秘密加密 | 服务/数据库：storesEncryptedMaterialsAndPreservesSecretsOnEdit；已认证所有者，提交全部材料与带首尾空格的密码 | 元数据完整，数据库为 enc:v1 密文，解密保留原密码，空值/掩码编辑保留已有秘密 | 正常、边界、回归 |
| 类型与字段约束有效 | 参数化服务测试：rejectsUnsupportedType、rejectsInvalidFields；空/EC/ED25519/rsa 类型、超长标签、多字节超限材料、超长密码、恶意账号、空材料 | 拒绝非法输入，不创建记录；supportsBoundariesAndReportsUnreadableSecret 验证合法最大长度可保存 | 边界、异常、安全 |
| 账号密码可独立复用 | 服务/数据库：rejectsUnavailableCredentialsAndSupportsAccountOnly；仅提交账号与密码 | 可保存账号凭据，无私钥时不能用于 KEY 认证，停用或删除后不能绑定 | 正常、异常 |
| 多台服务器共享最新凭据 | 服务/数据库：sharesCredentialsAndResolvesLatestValues；两台服务器引用同一凭据后轮换密码 | 两台数据同步执行目标读取新密码，服务器配置不包含秘密副本，引用中的凭据不可删除/停用 | 正常、状态冲突、回归 |
| 实际 Agent 请求使用轮换材料 | HTTP 集成：passesRotatedCredentialsToAgentWithoutExposingThem；回环 Agent 接收连接测试与监控请求 | 请求包含最新账号、私钥、密码、口令；用户响应隐藏 Agent 原始输出与秘密 | 正常、安全、集成 |
| 权限与所有权隔离 | 服务与控制器契约：enforcesOwnershipAndAdminSecretAccess、protectsCredentialEndpointsAndDisablesSecretSnapshots；未登录、其他用户、管理员 | 未登录 401；普通用户越权修改/删除/绑定/明文查看 403；管理员可管理及查看，仍不能跨所有者绑定；接口权限和禁止快照元数据正确 | 权限、安全 |
| 历史服务器与切换认证兼容 | 服务/数据库：updatesLegacyServerAndUnlinksLocalMode、switchesReferencesAndUsesServerUsernameForKeyOnly；直接密码记录切换引用、KEY 切 PASSWORD、切 LOCAL | 旧记录保持可用，引用切换生效，无账号私钥使用服务器账号，LOCAL 解除引用后可删除凭据 | 兼容、回归、分支 |
| 依赖异常不泄露秘密 | 服务/数据库：supportsBoundariesAndReportsUnreadableSecret；损坏密文、不存在 ID；原监控异常套件 | 返回稳定错误和正确 404，不返回密文/解密内部错误；既有超时、认证失败和网络诊断回归通过 | 异常、安全、回归 |
| 页面凭据选择与账号带入 | 前端真实函数测试：服务器选择凭据并自动复用账号；新服务器无引用、有效/无效引用、选择账号凭据 | 新服务器必须选择有效凭据，选择后带入账号并清除旧临时秘密，双语入口可用 | 正常、边界、兼容 |
| 管理页增删改及秘密生命周期 | 前端真实函数测试：server-credentials.test.mjs；新增/编辑成功与失败、无效表单、重复提交、取消删除、引用冲突、管理员显式查看 | 材料完整提交，成功清空并刷新；失败保留输入；取消不调用接口，冲突展示错误，关闭明文弹窗清理，未使用浏览器持久存储或 v-html | 正常、异常、安全 |

### 发现的问题与修复

- 上一阶段骨架错误引用不存在的 BusinessException 包，编辑直接覆盖秘密为空，创建通过列表首项取 ID。现已修正错误导入，并实现带权限校验的事务写入、秘密保留和当前 INSERT 的生成键获取。
- 首次定向执行 65 项测试出现 5 项错误：H2 的 RETURN_GENERATED_KEYS 同时返回 id 和默认时间字段，getKey 不能读取多列。改为显式请求 id 列后，新增测试及完整测试全部通过，没有删除、跳过或弱化失败用例。
- 后端没有配置 JaCoCo 等行/分支覆盖率插件；本报告给出可执行场景与通过数量，不声称后端行覆盖率达到 100%。

### 已知限制、未执行项与下次测试建议

- 本次支持 RSA 类型标记的材料保存与选择，不提供密钥生成、材料算法解析或公私钥配对校验；密钥实际可加载性和认证结果由既有 SSH 链路验证。certificate 仅保存，不参与 SSH 证书认证。
- 敏感字段留空表示保留；移除已存秘密应新建凭据并切换服务器引用。更新共享凭据影响后续连接，已经开始的 SSH 会话不会中途更换认证材料。
- 未操作用户现有远端服务器的认证配置，也未使用真实远端凭据执行新的 SSH 登录验收；隔离 HTTP 集成已验证 Agent 请求材料，真实环境仍应在用户选择有效凭据后执行“连接测试”和“资源监控”。
- 未执行登录后的浏览器人工全流程：本地 Air 预览从 HTTP 跳转 HTTPS 后受到预览来源限制且没有登录会话。前端正式函数测试、类型检查、生产编译和 Node 服务 E2E 已通过；下次可在已登录浏览器验证新建账号凭据、选择、轮换和删除保护。
- 本次未变更 Go/Python/其他独立服务业务逻辑，未额外执行这些模块的完整测试套件；后端和前端完整套件已执行。
- 回滚时不能仅回退应用镜像而忽略已绑定引用：需先安全恢复服务器直接认证配置，或恢复包含服务器与凭据的同一数据库备份；本次加法迁移应保留，避免删除共享凭据或破坏 Flyway 历史。未执行破坏性回滚演练。

### 重测触发条件

- 修改凭据、服务器或数据同步业务代码、DTO、数据库迁移、权限或加密相关配置时，重新执行定向与完整后端测试并更新基准；修改管理页面时执行定向、前端完整检查与统一 Compose 重建。
- 基准差异检查使用 `git diff f1d1cde80ba6c1a9768fb532c8821dad71a93579 HEAD -- backend/src/main/java/`，未提交的业务变更也需纳入检查；测试报告本身以独立提交保存，不改变已验收业务代码版本。

## SSH 运行用户修复与操作系统探测（2026-09-11）

### Git 基准点与交付状态

Commit: cb5ee140660424ff340234a8daefe313021f8464
- 提交信息：Fix SSH runtime user and display detected server OS；分支：master；测试日期：2026-09-11。
- 技术栈：Go/OpenSSH、Java 17/Spring/JDBC、Vue 3/Element Plus、Docker Compose；Python 服务仍使用 3.12。
- 自动化测试、镜像重建和服务健康检查通过。阿里云实际连接和监控尚未验收通过，用户选择“先不处理，我调整方案”，已暂停进一步远端认证调整。

### 已确认原因与变更范围

- 原运行容器使用 UID 10001，但镜像未创建对应系统用户；实际执行 `docker exec ai-deployment-agent ssh -V` 返回 `No user exists for uid 10001`，退出 255。修复镜像用户，并在镜像构建阶段以该用户执行 `ssh -V` 验证。
- 原 Agent 只返回子进程退出状态，后端进一步隐藏监控故障原因。新增白名单错误码，区分本地用户缺失、认证失败、网络超时、连接拒绝、DNS、路由、私钥加载、Agent 通信和监控兼容问题，避免原始 SSH 输出或凭据泄漏。
- 连接测试和监控通过固定只读命令读取系统家族、发行版、版本、内核和架构；只解析系统标识文件，不执行其内容。系统信息有长度和控制字符校验，识别失败不伪造系统身份。
- 系统信息复用现有加密 JSON 字段保存，列表返回独立字段；使用密文乐观锁防止慢探测覆盖并发修改，连接目标变化后清除旧身份。监控资源快照仍不持久化。
- 前端列表新增本地操作系统图标、发行版、版本、可展开的内核/架构/探测时间，以及未探测状态；错误说明支持中英文。没有新增依赖或数据库迁移。
- 涉及 Agent Dockerfile、Agent 连接/监控逻辑、服务器 Service/Models、服务器列表、现有语言文件和正式测试。共享 SSH 入口仍用于部署，数据同步的标准输入认证通道已回归。

### 实际执行命令与结果

- 缺陷复现：`docker run --rm -v "$PWD/deployment-agent:/workspace:ro" -w /workspace golang:1.26.6-alpine go test -run TestRemoteDiagnostics -v ./...`。修复前 8 个参数场景全部失败，实际返回均为 `exit status 255`；修复后完整测试全部通过。
- Go 完整单元测试：`docker run --rm -v "$PWD/deployment-agent:/workspace" -w /workspace golang:1.26.6-alpine sh -c 'gofmt -w main.go main_test.go && go test -cover -v ./...'`。30 个顶层测试及参数场景通过，失败 0；当次语句覆盖率 55.6%。
- Go 最终完整与真实 SSH 集成：`docker run --rm --name base-ai-server-ssh-tests --tmpfs /tmp:rw,noexec,nosuid,nodev -e GOTMPDIR=/build -v "$PWD/deployment-agent:/source:ro" -w /workspace golang:1.26.6-alpine sh -c 'apk add --no-cache openssh >/dev/null && mkdir -p /run/sshd /build && cp /source/*.go /source/go.mod . && gofmt -d *.go && go test -tags integration -cover -v ./...'`。31 个顶层测试及参数场景全部通过，失败 0、跳过 0，语句覆盖率 56.4%；格式检查无差异。真实 sshd 覆盖私钥、密码、组合认证、加密私钥、错误凭据、超时、标准输入、系统探测和实时资源采集。
- 后端最终定向及完整测试：`docker run --rm -v "$PWD/backend:/source:ro" -v "$HOME/.m2:/root/.m2" -w /tmp/backend maven:3.9.9-eclipse-temurin-17 sh -c 'cp /source/pom.xml . && cp -R /source/src . && mvn -B -ntp -Dtest=ServerManagementValidationTest,ServerManagementControllerTest,ServerManagementMonitorTest test && mvn -B -ntp test'`。定向 51/51（监控与身份 28、服务器校验 22、Controller 1），完整 826/826；通过率 100%，失败 0、错误 0、跳过 0。
- 前端定向：`node --test frontend/test/servers.test.mjs`，11/11 通过。
- 前端完整检查：在 frontend 工作目录执行 `npm run lint && npm run typecheck && npm run test:coverage && node --test e2e/*.test.mjs`，lint、类型检查、361 项单元测试与 1 项生产服务 E2E 通过，失败 0。工具函数行覆盖率 98.40%、分支 80.95%、函数 95.27%。前端生产编译通过统一 Compose 构建执行，未单独运行 `npm run build`。
- 最终重建：`APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`，退出 0；镜像标签对应上述代码基准点，构建中的 Maven package 826 项测试也全部通过。运行的 backend、frontend、deployment-agent、document-parser、python-worker、caddy 六个容器均 healthy。
- 生产运行用户验证：`docker exec ai-deployment-agent sh -c 'id; ssh -V'`，退出 0，运行身份 `10001(agent)`，SSH 能正常启动。
- 变更检查：`git diff --check`、`git diff --cached --check` 均通过；测试容器使用 `--rm`，测试临时目录由正式用例清理，没有创建工作区调试文件。

### 验收标准与测试覆盖映射

| 验收标准 | 测试层级与前置条件/输入 | 预期与实际结果 | 场景 |
| --- | --- | --- | --- |
| 生产用户能够运行 SSH | 镜像构建和运行容器，UID 10001 执行 ssh；隔离 sshd 三种认证 | 用户可解析，SSH 启动成功，合法凭据认证通过 | 正常、回归 |
| 失败原因可定位且不泄密 | Go TestRemoteDiagnostics；Java preservesSafeDiagnostics、hidesAgentFailureDetails；输入错误密码、网络故障、任意敏感输出 | 返回对应安全码，未知信息隐藏，列表保存安全错误 | 异常、安全 |
| 系统信息准确且有界 | Go TestSystemInfoParsing；Linux 实际采集；Alibaba Cloud Linux/Ubuntu/CentOS、缺失文本、超长/控制字符/恶意文本 | 系统字段正确，危险内容不执行，旧响应不捏造 OS | 正常、边界、安全、兼容 |
| 连接成功不依赖系统信息有效 | Java ignoresMalformedIdentity、rejectsUnsafeIdentityFields；缺失字段、无效日期、超长或非法标识 | 连接成功保留，无效系统信息不保存 | 边界、异常、兼容 |
| 信息持久化且并发安全 | Java persistsSystemIdentityAndInvalidatesChangedTarget、doesNotOverwriteConcurrentCredentialEdit；同目标编辑、新目标、探测期间修改凭据 | 列表可读、原凭据保留、目标变化清理旧 OS、并发新配置不被覆盖 | 正常、状态冲突、回归 |
| 非 Linux 识别不伪造监控数据 | Go 非 Linux 参数与 Java persistsIdentityWhenMonitoringUnsupported | 保留系统身份，返回 MONITOR_OS_UNSUPPORTED，主机指标为空 | 边界、兼容 |
| 所有权和启用约束生效 | 现有服务器权限/停用/不存在测试与完整后端权限套件 | 越权和非法状态不触发远端采集 | 权限、异常、回归 |
| 列表图标及信息安全展示 | 前端参数化图标、字段插值、未探测占位、双语错误测试 | 图标正确回退，字段齐全，不使用 v-html | 正常、边界、安全、兼容 |

### 阿里云实机验收与阻塞

- 通过应用现有登录和服务器接口读取名为“阿里云”的记录，原状态 FAILED，错误为 `exit status 255`。
- 修复运行用户后，实机监控和连接测试都到达 SSH 认证阶段并返回 `SSH_AUTHENTICATION_FAILED`；当前服务器记录为 `root` 用户、`PASSWORD` 认证。
- 使用 `PreferredAuthentications=none` 做只读 SSH 握手诊断，远端实际通告 `publickey,gssapi-keyex,gssapi-with-mic`，未通告 password 或 keyboard-interactive；服务器软件为 OpenSSH_9.6。不能从 SSH 软件版本推断具体操作系统。
- 结论：平台镜像用户缺失已修复；当前阿里云记录的密码认证方式与远端策略不匹配，仍需要有效私钥或另行确认的远端策略变更。未修改阿里云安全组、远端 SSH 配置或平台记录的认证方式。
- 首次临时 HTTP 验证脚本遗漏 CSRF 请求头，POST 测试返回 403；补充应用的 X-CSRF-Token 后 POST 返回 HTTP 200 和上述真实 SSH 认证错误。这是验证脚本问题，不是应用功能测试失败；没有通过关闭 CSRF 绕过验证。
- 用户明确暂停认证处理，等待其调整方案。因此本轮未获得阿里云真实系统信息，不将“操作系统实机识别、列表展示及远端资源监控恢复”判为验收通过。

### 已知限制与下次验证

- 资源采集仍依赖 Linux procfs 和基础命令；非 Linux 只保留可识别的系统身份并提示不支持监控。LOCAL 模式反映 Agent 容器环境，不代表 Docker 宿主操作系统。
- 新增操作系统列通过前端单元测试、类型检查和生产编译；未执行登录态浏览器视觉验收。前端 E2E 为现有生产 HTTP 服务测试，不等同于操作系统列的真实浏览器交互测试。
- Java 没有配置本轮 JaCoCo 覆盖率测量，不提供未经测量的 Java 百分比；Go 百分比为整个 Agent 语句覆盖率，不代表全部业务分支已覆盖。原有构建警告不影响本轮测试结果。
- 用户确认认证新方案并配置对应私钥后，重新执行服务器连接测试、资源监控和列表刷新，验证真实发行版/版本/内核/架构/时间，并完成浏览器展示验收。
- SSH 执行、镜像用户、系统解析、服务器存储/接口、权限或前端展示变化时重跑相关和完整测试并更新本报告；回滚可撤销本次代码提交后执行统一 Compose 重建。仅报告更新不触发重复业务测试。

## SSH 自动信任与组合认证（2026-09-11）

### Git 基准点

Commit: a6be07dc36dc7004f9950a2390cd7472fd5a1899
- 提交信息：Support SSH prompts with noexec temporary storage；分支：master；测试日期：2026-09-11。
- 功能提交：c62948c78d9f2f512fec8f674587d06d2d2c5d42（Support combined SSH authentication and automatic host trust）。
- 技术栈：Vue、Java/Spring、Go/OpenSSH。前端取消指纹输入，后端和 Agent 忽略历史指纹；新增 KEY_PASSWORD，保留独立私钥口令。SSH 执行统一入口，使用 Agent 自身响应口令，兼容 /tmp 的 noexec 挂载，凭据不进入命令参数。
- 影响连接、监控、部署及数据同步的 SSH 通道；不修改数据库、部署配置或新增运行依赖。并行任务的监控移除已独立提交，本节完整回归包含该基线。

### 执行命令与结果

- 后端相关测试：`mvn -B -ntp -Dtest=ServerManagementValidationTest,ServerManagementControllerTest,ServerManagementMonitorTest test`，28/28 通过。
- 后端完整测试：`mvn -B -ntp test`，803/803 通过，失败 0、错误 0、跳过 0，通过率 100%。最终 Compose 构建中 `mvn -B -ntp package` 再次运行 803 项全部通过。
- Maven 执行环境：`docker run --rm -v "$PWD/backend:/source:ro" -v "$HOME/.m2:/root/.m2" -w /tmp/backend maven:3.9.9-eclipse-temurin-17 sh -c 'cp /source/pom.xml . && cp -R /source/src . && mvn -B -ntp test'`。相关套件使用相同环境替换 Maven 参数。
- 前端相关测试：`node --test frontend/test/servers.test.mjs`，9/9 通过。完整 `cd frontend && npm test`：lint、typecheck、359 项单元测试、1 项生产服务测试通过，失败 0。工具函数行覆盖率 98.40%、分支 80.95%、函数 95.27%。
- Agent 完整真实 SSH 验证：`docker run --rm --name base-ai-ssh-noexec-tests --tmpfs /tmp:rw,noexec,nosuid,nodev -e GOTMPDIR=/build -v "$PWD/deployment-agent:/source:ro" -w /workspace golang:1.26.6-alpine sh -c 'apk add --no-cache openssh >/dev/null && mkdir -p /run/sshd /build && cp /source/*.go /source/go.mod . && go test -tags integration -cover -v ./...'`。28 个顶层测试及其参数子用例通过，失败 0，语句覆盖率 51.5%。随后新增提示参数和输出异常用例，最终 Compose 构建的 `go test ./...` 完整通过（28 个顶层非集成用例）。
- 集成测试为正式保留用例，使用 integration 构建标签，要求一次性 root Docker 环境；使用真实 sshd，不 Mock SSH 核心认证。普通构建不执行需要安装 sshd 的集成用例，本轮已单独执行。
- `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`：最终退出 0；镜像标签为执行时 HEAD `542cbbb6a348cb18e2586e520f6e58ab28b84b63`，构建输入含本节已提交代码。Backend、Frontend、Caddy、Deployment Agent、Document Parser、Python Worker 六个服务均 healthy。
- 运行检查：`docker exec ai-backend curl -fsS http://localhost:8080/api/open/health/ready` 返回 UP；通过 `docker exec` 向运行中 Agent 的口令模式注入非敏感测试值，响应正确。确认实际 /tmp 为 noexec；未修改挂载权限。
- `git diff --check` 通过。临时源码、sshd 配置、测试密钥均在自动删除的测试容器内；所有本次测试容器已退出清理，无宿主机调试文件。

### 验收标准—可执行测试映射

| 验收标准 | 层级、前置条件和输入 | 预期结果与场景 |
| --- | --- | --- |
| 指纹无需维护 | 表单、Java、Go 测试；空或旧指纹；临时 sshd 在同地址更换密钥 | 正常连接，无手工指纹要求；边界、兼容、回归 |
| 三种认证可用 | TestSSHIntegration；真实 sshd 分别要求 publickey、password、publickey,password | 认证成功，远端输出 AUTHENTICATED；正常 |
| 加密私钥组合登录 | 同一集成测试；加密私钥、独立口令及账户密码，包含引号与美元字符 | 成功；错误私钥、密码或口令均拒绝；正常、异常、安全 |
| 凭据完整与编辑兼容 | validatesAuthenticationCombinations、preservesCombinedCredentialsOnlyForSameAuthentication、表单函数测试 | 缺失或掩码凭据拒绝，同认证编辑保留，切换要求重新输入；边界、兼容 |
| 凭据隔离与清理 | TestSSHCredentialIsolation、TestSSHPromptErrors；恶意字符、未知提示、关闭输出 | 原样返回正确口令，未知提示拒绝，权限 0700/0600，参数无秘密，清理目录；安全、异常 |
| noexec 与流传输 | noexec 容器内真实 SSH；cat 标准输入、sleep 超时 | 数据完整返回、超时终止，口令响应无需执行临时脚本；兼容、回归、异常 |
| 权限与调用链回归 | 完整后端权限、服务器、数据同步套件及 Agent 内部令牌测试 | 未授权操作拒绝，既有调用链断言通过；安全、回归 |

### 问题、限制及后续建议

- 首次失败测试稳定复现指纹必填与 KEY_PASSWORD 不支持；实现后通过。并行监控编辑的中间状态曾导致 Go 编译与前端断言失败，待其独立修改稳定后完整复验通过，未删除或跳过有效测试。
- 首次 Compose 启动与另一任务同时重建导致容器名冲突；待其结束后重新完整执行成功，无端口冲突，无额外停止其他项目容器。
- 实际挂载发现 noexec 后，将临时口令脚本改为 Agent 可执行文件自身响应；真实 noexec 集成测试通过，并单独提交兼容修复。
- 每次自动信任不会固定校验服务器身份。是否强制私钥和密码两项均成功由远端 SSH 策略决定。现有 hostKey API 字段及旧指纹工具函数保留兼容，连接路径不再使用。
- 未执行用户实际远程服务器的登录态浏览器验收、真实跨数据库数据同步或生产远程部署；本轮使用受控真实 sshd 验证认证和标准输入通道。建议在目标服务器执行页面连接测试与业务同步验收。
- Java 未配置或执行 JaCoCo，本报告不提供未经测量的 Java 覆盖率；Go 覆盖率包含整个 Agent，不能理解为全部业务分支已覆盖。前端构建仍有既有大包及依赖注释警告。
- SSH 认证、凭据合并、权限、核心配置、业务代码或 Agent 执行逻辑变更时需重跑相关及完整套件并更新基准点；回滚可撤销本节两个代码提交后重建服务。

## 移除 Docker 容器监控（2026-09-11）

### Git 基准点与范围

Commit: b0b7e034f304e564a5e353004e0eeb2f8aa3de02
- 提交信息：Remove Docker container monitoring from server resources；分支：master；测试日期：2026-09-11。
- 本节验收本次监控移除提交；共享工作区同时存在独立 SSH 认证变更，本次仅分块提交监控相关内容。完整套件和镜像构建基于执行时共享工作区，因此不将本报告视为其他后续提交的独立验收。
- 前端移除容器列表、健康状态、Docker 错误提示及相关样式；Go Agent 不再调用 Docker 查询；Java 后端只验证主机指标，兼容旧 Agent 的 PARTIAL 响应并忽略容器错误。
- 为兼容旧接口保留空 `containers` 字段和容器数据类型，不再采集或展示容器数据。保留 Docker 部署和数据同步能力，不修改 Docker Socket 权限或挂载配置。

### 执行命令与结果

- `node --test frontend/test/servers.test.mjs`：新增移除断言先复现失败，修改后 9/9 通过。
- `cd frontend && npm test`：lint、typecheck、覆盖测试及生产服务测试通过；单元测试 359/359，生产服务测试 1/1，失败 0。工具函数行覆盖率 98.40%、分支 80.95%、函数 95.27%。
- `docker run --rm -v "$PWD/deployment-agent:/workspace:ro" -w /workspace golang:1.26.6-alpine go test ./...`：通过；另将 `git archive $(git write-tree) deployment-agent` 输送到一次性 Go 容器，独立验证仅含本次暂存变更的 Agent 完整套件，通过。
- 后端 Docker Maven 容器命令：`mvn -B -ntp -Dtest=ServerManagementMonitorTest test && mvn -B -ntp test`。监控 5/5 通过；完整套件 803/803，通过率 100%，失败 0、错误 0、跳过 0。完整测试包含服务器校验、权限和数据同步回归。
- Maven 通过 `docker run --rm -v "$PWD/backend:/source:ro" -v "$HOME/.m2:/root/.m2" -w /tmp/backend maven:3.9.9-eclipse-temurin-17` 运行，容器内复制 pom.xml 和 src 后执行上述命令。
- 初期 Go 编译发现未清理的容器解析引用及缺少 strings 导入，Java 测试缺少 assertNull 导入；均已修复并重新执行通过。一次 Compose 构建因此失败，最终重试成功，未跳过或弱化主机错误和权限测试。
- `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`：最终成功。镜像标签为构建开始时 HEAD `dc716cadb263c312d14bfcc3c836e4decf2f9d4e`，实际构建输入包含本次及共享工作区代码；六个应用服务均健康。
- 运行验证：Backend 容器使用内部认证调用 Agent `/monitor`，LOCAL 返回 `SUCCEEDED`，CPU、内存、磁盘及运行时长均有有效值，`containers: []`，不再出现 Docker 错误。运行环境未开放 Docker Socket 权限。
- `git diff --cached --check`：通过。

### 验收标准—测试映射

| 验收标准 | 层级、前置条件和输入 | 预期结果与场景 |
| --- | --- | --- |
| 页面不再监控容器 | 前端回归，读取实际服务器组件 | 无容器列表和 Docker 错误，保留主机指标与采集失败提示；正常、回归 |
| 采集不依赖 Docker | Go 实时 Linux 测试，检查固定脚本并调用 collectMonitor | 脚本无 docker 命令和容器标记，返回有效指标及 SUCCEEDED；正常、无 Docker 环境 |
| 兼容旧响应 | Go 解析及后端 HTTP 集成测试，输入旧容器数据或错误 | 忽略容器数据和错误，有效主机指标返回成功；兼容、异常 |
| 主机异常仍被拒绝 | 保留现有异常指标和失败响应测试 | 缺失或无效指标、计数倒退、Agent 失败仍失败；边界、异常 |
| 权限继续生效 | 后端监控测试，越权、停用和不存在的服务器 | 拒绝采集；安全、回归 |

### 限制与重测建议

- 未运行真实 SSH 服务器端到端测试和登录态浏览器操作；本次已验证本地运行端点与正式测试套件。独立 SSH 认证集成任务不属于本报告范围。
- 无新增依赖、数据库变更或宿主机临时调试文件。一次性验证容器自动删除；其他任务产生的文件未删除或纳入本次提交。
- 修改监控业务逻辑、Agent 协议或核心配置后，重跑相关及完整测试并更新本报告；前端或 Go 后续独立变更应使用对应套件复验。

## IDA 数据库存储兼容修复（2026-09-11）

### Git 基准点

Commit: eb4bcb57edcd95648b0d9cb7b2b307f2a65aaff3
- 提交信息：Restore immutable migrations and preserve IDA storage compatibility
- 分支：master；测试日期：2026-09-11。
- 本节取代上一节中的数据库启动阻塞状态；未修改数据库存量数据或 Flyway 历史记录。

### 变更范围与技术实现

- V31/V32 恢复至已部署版本的原始内容，校验和分别为 1040951367、187493084。
- Spring JDBC 设备配置、设备池和命令状态读写保留历史 WDA 表名与列名，Java 模型及对外接口继续使用 IDA。
- Jackson `JsonAlias` 兼容旧密文里的 `updatedWdaBundleId`；读取不改写密文，序列化仍输出 `updatedIdaBundleId`。
- 正式测试使用真实 H2 数据库执行旧表结构的读写，并增加历史迁移校验和及新旧密文参数化测试。

### 测试执行结果

- 定向 Maven 测试先复现：24 项，3 失败、9 错误；兼容修复后测试发现新测试的独立 ObjectMapper 未注册日期模块，补齐测试配置后最终 24/24 通过。
- 定向命令：Docker Maven 容器内执行 `mvn -B -ntp -Dtest=DeviceAgentScopeContractTest,DeviceAgentAutomationConfigServiceTest,DeviceAgentDeviceServiceTest,DeviceAgentCommandServiceTest test`。
- 完整命令：同一容器内执行 `mvn -B -ntp test`。总计 794 项，通过 794，失败 0、错误 0、跳过 0，通过率 100%。
- 容器启动方式：`docker run --rm -v "$PWD/backend:/source:ro" -v "$HOME/.m2:/root/.m2" -w /tmp/backend maven:3.9.9-eclipse-temurin-17 sh -c 'cp /source/pom.xml . && cp -R /source/src . && mvn -B -ntp -Dtest=DeviceAgentScopeContractTest,DeviceAgentAutomationConfigServiceTest,DeviceAgentDeviceServiceTest,DeviceAgentCommandServiceTest test && mvn -B -ntp test'`。
- 关键模块：设备配置 7/7、设备池 4/4、设备命令 8/8、命名与历史迁移契约 5/5；服务器 Controller 1/1、Monitor 5/5、Validation 13/13，全部通过。
- 独立 MySQL 8.4 集成验证：创建空库 `migration_validation`，按版本顺序通过 `docker exec -i base-ai-migration-validation mysql -uroot migration_validation` 执行全部 34 个迁移脚本；全部通过。information_schema 验证设备 5 个历史列和配置 3 个关键列齐全。
- 新库验证执行的是完整 SQL 脚本链；已有运行库则通过应用真实 Flyway 启动验证。日志确认 `Successfully validated 34 migrations` 和 `No migration necessary`，后端正常启动。
- `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`：成功，Docker 构建内完整 Maven package 测试通过；所有服务重新启动。镜像版本标签为构建时 HEAD `f89eb95b27f54751bb422527cfa4796eefdbff18`，构建输入包含随后提交为本节基准点的已测试工作区代码。
- `git diff --check`：通过；V31/V32 与已部署提交 `ee42649574b042d6853ffa79c39fef4f97bfc498` 的差异为空。

### 验收标准—测试用例映射

| 验收标准 | 层级与前置条件 | 输入与预期结果 | 场景 |
| --- | --- | --- | --- |
| 历史迁移不可变 | 参数化契约测试，读取 V31/V32 | CRC32 与已部署校验和一致 | 兼容、回归 |
| 旧表结构可正常读写 | H2 服务测试，建立历史 WDA 表列 | 更新配置、同步设备及执行 IDA 命令后，字段和副作用正确 | 正常、异常、安全、回归 |
| 存量签名不丢失 | 参数化服务测试，真实加密配置写入历史表 | 新旧 bundle 字段均正确读取；禁止设备注册选择保留；密文和版本不变；API 仅输出 IDA | 兼容、安全、回归 |
| 新旧数据库均可用 | 独立空 MySQL 和已有运行数据库 | 34 个脚本完整成功；运行库 Flyway 校验成功且无需迁移 | 新建、升级兼容 |
| 服务与监控恢复 | Compose 重建和内部 HTTP 验证 | Backend 正常启动；Agent `/test` 返回 SUCCEEDED，`/monitor` 返回实时 CPU、内存及磁盘 | 正常、降级 |

### 限制、清理与下次建议

- LOCAL 监控仍为 PARTIAL：Docker Socket 无访问权限，主机指标正常，容器列表未验收。本次不扩大 Docker 权限；真实 SSH 服务器仍需登录后端到端验证。
- 页面入口已返回登录认证响应；Air 预览因 HTTP 80 跳转 HTTPS 443 后的同源限制无法操作，未完成登录态页面验收。实际访问入口为 `https://localhost/`。
- 本轮未重复运行未修改的前端及 Go 套件，前轮结果为前端 358/358、生产服务 1/1、Go 25/25，通过；后端完整测试已覆盖本轮变更。
- 未新增测试依赖或配置，不提供未经执行的 JaCoCo 覆盖率数字。覆盖验证由上述正式测试与真实数据库验证组成。
- Maven 临时源码和测试结果均位于自动删除的容器；独立 MySQL 验证容器已停止并自动移除，未生成宿主机调试文件。
- 后续修改业务逻辑、存储映射、历史迁移或核心配置时必须重跑相关及完整测试，更新本报告基准点。历史迁移禁止再次重写；需要数据库演进时追加新版本迁移。

## 服务器管理简化与连接恢复（2026-09-11）

### Git 基准点

Commit: 62529051e0bc78db972c0e32fef2a07b44d1f78f
- 提交信息：Simplify server actions and align IDA regression tests
- 分支：master；测试日期：2026-09-11。
- 状态：代码测试通过，完整运行环境验收未完成；数据库兼容修复方案待确认。

### 变更与测试执行结果

- 移除服务器部署及部署历史页面入口、弹窗和专属逻辑；保留后端接口与历史数据。
- 测试连接增加请求中状态、重复点击保护和内部配置错误的本地化提示。
- 经用户确认，修复设备 Agent 测试中的 WDA→IDA 过期引用，保留禁止展示旧产品名称的断言。
- `node --test frontend/test/servers.test.mjs`：移除入口的失败测试先复现为 6 通过、1 失败，页面修改后 7/7 通过；随后新增连接请求行为测试，最终随完整前端套件通过。
- `cd frontend && npm test`：lint、类型检查通过；单元测试 358/358 通过，失败 0、跳过 0；生产服务测试 1/1 通过。工具函数行覆盖率 98.40%、分支 80.95%、函数 95.27%。
- `docker run --rm -v "$PWD/deployment-agent:/workspace:ro" -w /workspace golang:1.26.6-alpine go test -v ./...`：25/25 通过，失败 0、跳过 0，包含实时 Linux 指标采集。
- `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`：镜像构建通过，包含 Dockerfile 内完整 `mvn -B -ntp package` 测试；此前一次后端执行 790 项、1 项失败，修复误改的旧名称否定断言后构建通过。最终启动因 V31/V32 Flyway 校验失败返回非零，不能作为整体运行验收通过。
- `git diff --check`：通过。

### 验收标准—测试用例映射

| 验收标准 | 层级与前置条件 | 输入与预期结果 | 场景 |
| --- | --- | --- | --- |
| 移除部署页面功能 | 前端源码回归，读取服务器组件 | 不再存在部署/历史入口、权限引用及状态；保留测试、监控和编辑 | 正常、回归 |
| 连接测试状态正确 | 前端行为测试，执行组件实际测试方法并隔离 HTTP | 同一服务器重复点击只请求一次；成功刷新列表；业务失败本地化；超时释放状态 | 正常、异常、状态冲突 |
| 主机监控可降级 | Go 单元与实时 Linux 集成测试 | 正常指标解析；无效指标拒绝；Docker 失败仍保留主机数据 | 正常、边界、异常 |
| 保留安全与兼容性 | Go 完整套件与后端构建测试 | SSH 身份、指纹和内部令牌校验；拒绝注入；保留数据同步与历史部署逻辑 | 安全、兼容、回归 |

### 运行验证与限制

- 本地忽略文件 `.env` 已配置 deployment profile 和随机内部令牌；令牌未输出或提交。
- 新 Agent 健康；从 Backend 容器调用 Agent `/test` 返回 `SUCCEEDED`，`/monitor` 返回实时 CPU、内存和磁盘数据。
- LOCAL 监控返回 `PARTIAL`：Docker Socket 无访问权限，容器列表未验证；LOCAL 指标来自 Agent 所在 Linux 运行环境。
- 最新后端启动失败：数据库 V31/V32 校验和分别为 1040951367、187493084，源码为 -2092562424、-1740873095；源于此前历史迁移中的 WDA 表/字段被改为 IDA，本次未修改数据库或校验记录。
- 已恢复后端镜像 `ee42649574b042d6853ffa79c39fef4f97bfc498`；新前端、Agent 和其他服务均健康。页面预览首次尝试因服务重建时不可访问失败，尚未完成登录态页面和真实 SSH 服务器端到端验收。
- 无新增临时测试或调试文件；本地密钥配置保留供运行环境使用，不进入 Git。

### 重测触发与下次建议

- 数据库存储兼容、后端业务逻辑或核心配置进一步修改后，执行相关测试、完整 Maven 测试及统一服务重建。
- 数据库修复方案确认后，验证历史迁移不可变性、存量加密配置读取、字段映射及新旧数据库启动；再次验证服务器页面、SSH 连接、资源监控与权限。
- Docker 容器列表需有效且有权限的 Socket；真实 SSH 验收需使用已配置的目标服务器。

## MacAir 无线 iOS 设备在线判定（2026-09-11）

### 本次变更测试结果

**变更范围**：无线设备只要被 `devicectl` 枚举到，即判定为在线；开发隧道为 `connected`、`connecting`、`disconnected`、`unavailable`、空值或缺失时均保持 `AVAILABLE`。USB 与其他传输类型的既有判定不变。

**测试执行结果**：设备检测定向测试 61/61 通过，失败 0、错误 0、跳过 0。`git diff --check` 通过。

**构建验证**：已执行 `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`，构建因仓库既有 Backend 测试编译错误失败；错误涉及缺失的 `AgentWdaConfigView`、`UpdateAgentWdaConfigRequest`、`WdaSigningConfig` 等类型，与本次 Agent 变更无关，服务未完成启动。

**Git 基准点**：98369b045a98b9eca924c5a3c8448ec4325c8be9。

**验收标准—测试用例映射**：无线设备被发现即在线；Python 参数化单元测试覆盖正常、隧道异常、空值和边界状态，全部通过。


## WDA 业务命名统一为 IDA（2026-09-11）

### 本次变更测试结果

**变更范围**：设备 Agent 业务标识、接口字段、Appium 能力字段及前端展示统一改为 IDA，并重命名相关模块文件。

**测试执行结果**：未完成。`docker compose up --build -d` 因缺少 `APP_IMAGE_REVISION` 环境变量失败；`cd backend && mvn test -B` 因环境未安装 Maven（`mvn: command not found`）未执行。

**静态检查**：业务代码与前端中未发现独立的 WDA 标识残留；工作流中的 `workflow` 子串误匹配已排除。

**已知问题**：尚未完成运行时构建及完整测试，需在具备 Docker Compose 环境变量和 Maven 的环境中复验。

**Git 基准点**：96073b1

## USB 实连设备与开发隧道状态解耦验收（2026-09-11）

### Git 基准点

Commit: ee42649574b042d6853ffa79c39fef4f97bfc498
- 提交信息: Fix USB device detection without developer tunnels
- 测试日期: 2026-09-11
- 分支: master
- 本次仅修改 Agent 设备检测及正式测试，不修改后端、前端、数据库、运行配置或生产依赖。
- 用户已批准按当前完整工作区重建；并行完成的 OSS 提交保留在当前历史中，未由本次任务修改或重复提交。
- 未执行 git push；测试报告单独提交。

### 变更范围与实现

- 使用 Python 3.12 标准库经 `/var/run/usbmuxd` 发送一次只读 `ListDevices` 请求，在本机内存中取得真实 USB 实连标识。
- 对 devicectl 已发现的同一设备，USB 实连证据优先于开发隧道状态及历史接入方式，报告 `connected=true`、`connectionType=USB`、`status=AVAILABLE`。
- 没有 USB 实连证据时保持既有 devicectl 判定；不把历史 `wired` 字符串本身作为在线证据，不改变无线兼容行为。
- Socket 操作具有超时，响应分片共享一个 5 秒接收截止时间，响应上限为 1 MiB；校验协议版本、消息类型、请求标记及 plist 结构，异常时安全降级。
- 保持设备摘要、设备池上限、WDA 端口分配和后端报告格式不变；USB 在线不设置 IDA 就绪或运行状态，原始 UDID 不进入后端报告。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| USB 在线不依赖开发隧道 | Python 单元测试，USB 枚举存在目标，隧道为 disconnected、unavailable、空字符串或空值，接入方式为 wired、localNetwork 或空值 | 全部报告 USB、AVAILABLE，IDA 仍 UNKNOWN 且未运行；通过 | 正常、边界、缺陷回归 |
| 不伪造离线设备的 USB 证据 | Python 参数化测试，USB 清单为空或仅有其他设备，目标为历史 wired 且隧道断开 | 目标仍为 OFFLINE；通过 | 边界、异常、回归 |
| 多连接、多设备准确匹配 | Python 单元测试，同设备 USB 和网络记录重复、另一台设备离线 | 按本机标识去重，USB 优先，不串设备，报告不含原始标识；通过 | 正常、兼容、隐私 |
| USB 查询只读且响应有界 | Socket 边界单元测试，一字节分片及整块响应，空清单、非法条目、128/129 字符标识、恰好 1 MiB 响应 | 只发送 ListDevices，分片正确重组，过滤网络和非法标识，边界合法响应可解析；通过 | 正常、边界、安全 |
| 本机依赖故障安全降级 | Socket 参数化测试，创建、连接、发送、读取阶段的文件不存在、权限错误、超时；错误协议头、超限、截断、非法 plist 和慢速分片 | 不崩溃、不增加虚假 USB 证据，保留无线与离线判定；通过 | 异常、权限、安全 |
| 不掩盖 devicectl 故障 | Python 参数化测试，退出失败、无输出、非法 JSON、系统错误及超时 | 保留 DEVICE_DETECTION_FAILED，不执行 USB 查询或同步不完整清单；通过 | 异常、兼容 |
| 原有候选和数量约束不变 | Python 单元测试，缺少标识、非 iOS、非法条目及 101 台有效设备 | 非法候选过滤，最多返回 100 台；通过 | 边界、回归 |
| 两种同步入口均生效 | AgentRuntime 集成测试，周期同步及 DETECT_DEVICE 使用真实检测、报告和 WdaRuntime，只隔离外部依赖 | 后端收到 USB 在线摘要，端口应用成功，不创建 Appium 会话，即时检测返回 synchronized=true；通过 | 集成、兼容、副作用 |
| 已部署 Agent 持续同步 USB 在线 | 本机实测及只读数据库核对，三次采样覆盖开发隧道断开、连接、再次断开 | 每次本机检测及后端记录均为 USB、AVAILABLE，Agent 新版本心跳在线且设备时间戳继续推进；通过 | 运行态、回归 |

### 测试执行结果

- 修复前：新增业务回归集合 21 个用例中 13 个失败、8 个通过，稳定复现 USB 被开发隧道误判离线以及 USB 优先级错误。
- 修复后定向测试：70/70 通过，通过率 100%，失败 0、错误 0、跳过 0。
- Agent 完整测试：97/97 通过，通过率 100%，失败 0、错误 0、跳过 0。
- `device_agent/device_detect.py`：101/101 可执行语句、30/30 分支覆盖，行及分支覆盖率均为 100%。
- Agent 全模块综合覆盖率为 63%；100% 仅指本次修改的设备检测模块，不代表整个 Agent。
- `git diff --check` 通过。
- Compose 重建成功，5/5 默认服务 healthy，镜像 revision 均为 `ee42649574b042d6853ffa79c39fef4f97bfc498`。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 测试环境 | `/opt/homebrew/bin/python3.12 -m venv /tmp/baseai-usb-fix.aqgl25/venv`，临时环境安装 `pytest>=8.4,<9`、`coverage>=7,<8` | 使用 pytest 8.4.2、coverage 7.16.0，不修改项目依赖 |
| 失败复现 | 在 `device-agent/` 执行临时 Python 的 `-m pytest -p no:cacheprovider tests/test_device_detect.py` | 修复前 13 失败、8 通过，失败业务断言随后全部修复 |
| 定向回归 | `PYTHONDONTWRITEBYTECODE=1 /tmp/baseai-usb-fix.aqgl25/venv/bin/python -m pytest -p no:cacheprovider --color=no --tb=short tests/test_device_detect.py tests/test_main.py` | 70/70 通过 |
| 完整 Agent 覆盖测试 | `PYTHONDONTWRITEBYTECODE=1 COVERAGE_FILE=/tmp/baseai-usb-fix.aqgl25/.coverage /tmp/baseai-usb-fix.aqgl25/venv/bin/python -m coverage run --branch --source=device_agent -m pytest -p no:cacheprovider --color=no --tb=short`，随后 `coverage report -m` | 97/97 通过，修改模块行与分支均 100% |
| 平台重建 | 根目录执行 `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d` | 全部工作区代码构建并启动，后端 Maven package 层复用缓存，前端构建实际执行 |
| 健康与版本检查 | `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose ps`，定向读取容器健康和镜像 revision | 5/5 healthy，版本与修复提交一致 |
| 安装包校验 | 通过已配置 CA 的 HTTPS 下载当前平台 Agent 包，在内存中校验 SHA-256，并逐字节比较设备检测源码 | 发布包与已测代码完全一致 |
| Agent 升级 | 先只读确认无待执行/租约中命令及运行中的受管 IDA，再调用现有 `upgrade` 流程并向用户级 Agent 发送 SIGTERM | 切换到 `20260911.0400+52651ceb69c3`，Agent 自动重启，未重启 Appium 或 Registry |
| 运行态验收 | 12:03:55、12:04:16、12:04:36（UTC+08:00）连续本机检测并执行只读数据库 SELECT | 隧道分别 disconnected、connected、disconnected；设备始终 USB、AVAILABLE；IDA 保持 UNKNOWN/未运行 |
| 环境清理 | 删除本任务创建的 `/tmp/baseai-usb-fix.aqgl25` 虚拟环境和覆盖数据 | 已清理，未提交临时脚本或调试文件 |

### 已知问题与未执行项

- 本次未新增执行 Backend Maven 全量测试：`git diff fa50f51 HEAD -- backend/src/main/java/` 无变化，Compose 的 Maven 测试构建层为缓存，不冒充本次重跑结果。
- 本次不修改前端，未重跑前端 lint、类型检查、完整单测或浏览器 E2E；Compose 中的生产构建成功不替代这些测试。
- 管理页未登录，本次通过只读数据库验证设备池数据和 Agent 心跳，未进行登录后的页面操作。
- Appium 旧 `/sessions` 读取返回 404，`/appium/sessions` 因未启用 `session_discovery` 返回 500；未放宽安全配置。升级前改用后台命令/设备状态及本机进程、日志确认无受管自动化运行。
- 首次单独执行 `docker compose ps` 未传 `APP_IMAGE_REVISION`，发生插值失败；补齐该变量后检查通过。实际重建命令已正确传入变量。
- 实机未执行人工拔线、插线或 IDA 安装/启动；拔线后的无 USB 证据及异常情况由正式自动化测试覆盖。
- 开发隧道本身仍可能断开，本次只修复物理连接状态判定，不宣称修复隧道或 IDA 的就绪问题。
- USB 枚举失败时沿用既有隧道判定，可能再次显示离线；此降级避免查询失败时假报 USB 在线。

### 重测触发条件与下次测试建议

- 修改设备发现、usbmuxd 协议、设备摘要、报告格式或周期/即时同步时，重跑本节定向及完整 Agent 覆盖测试。
- 修改 WDA、Registry 或后端设备池处理时，补跑对应模块测试并验证真实设备的独立连接和就绪状态。
- 建议在另一台 Mac、仅无线设备及 USB 拔插场景补做实机验证，并在登录后的设备池页面核对显示。

### 回滚方式

- 本机保留升级前 `20260911.0322+26473798d735` 版本，可通过现有指定版本升级流程切回，并在无自动化运行时重启 Agent。
- 如需回滚源码，撤销 `ee42649574b042d6853ffa79c39fef4f97bfc498` 后重新执行 Compose 重建；不涉及数据迁移。

## 阿里云 OSS 对象存储数据源类型验收（2026-09-11）

### Git 基准点

Commit: fa50f51
- 提交信息: Add Alibaba Cloud OSS as object storage data source type
- 测试日期: 2026-09-11
- 分支: master
- 未执行 git push。

### 变更范围

- 新增阿里云 OSS 作为对象存储数据源类型，与 S3 并列支持工作流连接。
- 新增 `ObjectStorageService` 统一处理 S3 和 OSS 的上传、下载、删除和预签名操作，含 Key 前缀校验和删除权限校验。
- `DataSourceController` 新增 4 个对象存储 REST 端点（upload、download、delete、presign）。
- `WorkflowConnectionService` 在支持类型集合中新增 OSS；`WorkflowConnectionTester` 新增 OSS Bucket 存在性验证；`WorkflowConnectionTargetParser` 新增 OSS Endpoint 协议解析。
- 后端国际化新增 `objectStorage.operationInvalid` 消息；前端新增 OSS 类型图标、配置字段、分类映射和中英文文案。
- `pom.xml` 新增 `aliyun-sdk-oss 3.17.4` 依赖。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| OSS 连接可创建并脱敏保存凭据 | Backend `WorkflowConnectionServiceTest.createsAndMasksOssConnection` | 类型为 OSS，accessKey/secretKey 脱敏，resolved 返回明文；通过 | 正常、安全 |
| OSS 连接测试失败时留存检测结果 | Backend `WorkflowConnectionTesterTest.recordsFailedTestResultForUnreachableOssConnection` | 不可达 Endpoint 抛出异常并记录 recordTestResult(false)；通过 | 异常、回归 |
| OSS Endpoint 按协议解析且空值不返回目标 | Backend `WorkflowNetworkPolicyTest.parserExtractsOssEndpoint` | HTTPS 正常解析，FTP 拒绝，空 Endpoint 返回空列表；通过 | 正常、边界、安全 |
| 对象存储服务安全校验 | Backend `ObjectStorageServiceTest`（10 个用例） | 删除权限关闭时拒绝、Key 超出前缀拒绝、非对象存储类型拒绝、预签名操作校验、空键拒绝；通过 | 权限、安全、边界、异常 |
| 前端连接配置覆盖 OSS | Frontend `workflowConnectionConfig.test.js`（17 个用例） | 13 类连接含 OSS，字段/默认值/分类/颜色/图标断言完整；通过 | 正常、兼容、回归 |
| 数据源管理页面覆盖全部受管连接 | Frontend `data-sources.test.mjs` | 页面覆盖安全占位符和连接入口；通过 | 正常、兼容 |
| 服务构建和运行环境无回归 | Maven 全量测试、前端全量测试、Compose 健康检查 | Backend 790/790、Frontend 357/357 通过；5/5 服务 healthy；通过 | 回归、构建、运行态 |

### 测试执行结果

- Backend Maven `clean test`：790/790 通过，通过率 100%，失败 0、错误 0、跳过 0。
- Frontend `node --test test/*.mjs tests/*.js`：357/357 通过，通过率 100%，失败 0、错误 0、跳过 0。
- `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d` 执行成功；Backend、Frontend、Python Worker、Document Parser、Caddy 5/5 healthy。

### 关键模块测试

- `ObjectStorageService`：10/10 通过，覆盖 S3/OSS 上传下载删除预签名、Key 前缀校验、删除权限、非对象存储类型拒绝和预签名操作校验。
- `WorkflowConnectionService`：OSS 连接创建、脱敏和 resolved 解密通过。
- `WorkflowConnectionTester`：OSS 不可达连接测试失败并留存结果通过。
- `WorkflowNetworkPolicy`：OSS Endpoint HTTP/HTTPS 解析和空值处理通过。
- Frontend `workflowConnectionConfig`：13 类连接字段、默认值、分类归属、品牌色和图标断言全部通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp clean test` | 790/790 通过 |
| Frontend 完整回归 | `node --test test/*.mjs tests/*.js` | 357/357 通过 |
| 服务重建 | `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d` | 5/5 healthy |

### 已知问题

- `device-agent/tests/test_device_detect.py` 存在未提交的独立变更（设备检测 USB 在线判定测试），与本次 OSS 功能无关，未纳入本次提交。

### Git 基准点（历史）

---

## 设备 Agent 自动化域与 IDA 命名迁移验收（2026-09-11）

### Git 基准点

Commit: 9b35f1c9b018cf966546f9c83c63b2e979c1ba30
- 提交信息: Move device agents into automation domain
- 测试日期: 2026-09-11
- 分支: master
- 未执行 git push。

### 变更范围

- 设备 Agent 菜单从运维目录迁移到自动化目录，权限域统一为 `automation:device-agent:*`，路由、导航、控制器和内置运维角色保持一致。
- 新增 V34 Flyway 迁移，原位转换既有菜单权限、父级目录和按钮排序，并为已有角色补齐自动化目录授权，保留菜单 ID 和原有页面/按钮授权。
- 将用户可见的 WDA 产品名称统一为 IDA（iOS Device Automation），保留底层 WDA 文件、协议命令、字段和错误码以维持 Agent/Appium 兼容性。
- 中英文界面、后端消息、Agent 构建/启动结果及 README 均使用 IDA 产品简称。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 设备 Agent 位于自动化目录 | Backend `DataInitializerTest` 与 Frontend 路由/导航契约读取菜单父级、路径和权限 | 父级为自动化目录，路径保持 `/automation/device-agents`，使用 `automation:device-agent:list`；通过 | 正常、兼容、回归 |
| 全部管理接口使用新权限域 | Backend 控制器契约测试和 Frontend 页面权限测试覆盖列表、创建、更新、删除、执行 | 所有入口仅检查 `automation:device-agent:*`，不再检查旧运维域；通过 | 权限、安全、回归 |
| 历史权限和角色授权完整迁移 | Backend H2 `DeviceAgentPermissionMigrationTest` 输入旧权限菜单、既有角色关系和已存在目录授权 | 菜单 ID、角色页面/按钮授权保留，权限前缀、父级、排序更新，缺失目录授权补齐；通过 | 迁移、兼容、权限 |
| WDA 产品名替换为 IDA | Frontend 中英文 locale 契约、Backend 消息资源契约、Python Agent 构建/启动结果测试 | 用户可见文案统一为 IDA，底层协议键仍兼容；通过 | 正常、兼容、国际化 |
| 服务构建和运行环境无回归 | Maven 全量测试、Python 3.12 Agent 全量测试、目标前端测试及 Compose 健康检查 | Backend 790/790、Agent 36/36、目标前端 13/13 通过；五个默认服务 healthy；通过 | 回归、构建、运行态 |

### 测试执行结果

- Backend Maven `clean test`：790/790 通过，通过率 100%，失败 0、错误 0、跳过 0；设备 Agent 迁移、控制器、初始化和命名契约均通过。
- Frontend 设备 Agent 定向测试：13/13 通过，覆盖路由权限、导航映射、IDA 中英文文案和页面动作权限。
- Frontend `npm test`：ESLint 和类型检查通过；覆盖测试阶段有 2 条既有 `ServersView` 旧结构断言失败，未进入 E2E，失败与本次设备 Agent 变更无关。
- Device Agent Python 3.12：36/36 测试通过，临时测试虚拟环境已清理。
- `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d` 最终执行成功；Backend、Frontend、Python Worker、Document Parser、Caddy 5/5 healthy。

### 关键模块测试

- `DataInitializer`：自动化目录菜单、运维角色目录继承和新权限集合通过。
- `DeviceAgentPermissionMigrationTest`：旧权限前缀转换、菜单父级/排序、角色授权补齐和幂等保护通过。
- Device Agent 控制器与前端契约：列表、配对、配置、Registry、设备端口和命令动作均使用新权限域。
- Locale/消息资源：中文和英文用户可见值不再出现独立 WDA 产品名；WDA 协议键保留。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp clean test` | 790/790 通过 |
| Frontend 设备 Agent 定向回归 | `cd frontend && node --test test/device-agent.test.mjs tests/deviceAgentManagement.test.js` | 13/13 通过 |
| Frontend 完整质量门 | `cd frontend && npm test` | ESLint、类型检查通过；2 条既有 ServersView 断言失败，覆盖与 E2E 未完成 |
| Device Agent Python 回归 | Python 3.12 临时虚拟环境执行 `pytest` | 36/36 通过，临时环境已清理 |
| 服务重建与运行态 | `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`、`docker compose ps` | 5/5 默认服务构建并 healthy |

### 重测触发条件

- 修改设备 Agent 菜单、权限注解、角色种子、V34 迁移或相关 API 时，必须重新执行 Backend 全量测试和权限迁移定向测试。
- 修改设备 Agent 页面、路由、导航或 IDA 文案时，必须重新执行前端定向测试和完整质量门。
- 修改 Agent Python WDA 运行时、协议命令或结果文案时，必须重新执行 Python 3.12 全量测试。
- 修改核心配置、数据库迁移或镜像构建方式时，必须重新执行 Compose 重建和五服务健康检查。

### 已知问题

- Frontend 完整 `npm test` 仍受既有 `ServersView` 重构后的两条旧结构断言阻断；本次未修改该页面或其测试，设备 Agent 定向测试全部通过。
- WDA 文件名、Java/Python 内部类型、Appium 能力键、协议命令和错误码保留原名，仅用户可见产品文案改为 IDA；后续若要清理内部命名需单独设计兼容迁移。

### 下次测试建议

- 更新 `ServersView` 旧断言后重新执行前端完整质量门和 E2E。
- 在真实 MySQL 上验证 V34 对已有角色、菜单 ID 和重复执行的迁移结果，并核对回滚备份策略。
- 在真实 iOS 设备上验证 IDA 构建、签名、安装和 Appium 会话提示与底层 WDA 协议兼容。

## 数据同步按服务器远程执行验收（2026-09-11）

### Git 基准点

Commit: dba837f8fff192e28645156eb393615919f839f3
- 提交信息: Complete data sync server safeguards
- 测试日期: 2026-09-11
- 分支: master
- 未执行 git push。

### 变更范围

- 数据同步计划新增执行服务器选择；服务器列表仅返回当前用户拥有且启用的受管服务器，历史未指定服务器的计划继续按平台本地目标展示。
- 表查询、预检、执行、取消和进度恢复均携带同一服务器目标；远程服务器通过 Deployment Agent 在目标机执行，平台只负责提交任务和恢复状态。
- 新增 V33 迁移，为计划和运行记录保存服务器、Agent 任务编号及并发槽位；删除仍被计划引用的服务器会被拒绝。
- Deployment Agent 新增数据同步查询、预检、执行、任务状态和取消协议；Worker 通过标准输入接收任务并以 NDJSON 输出进度，凭据不写入响应或日志。
- 前端计划表单、列表和运行明细支持中英文服务器名称；新增 `DATA_SYNC_WORKER_IMAGE` 配置用于目标机 Worker 镜像。
- 可按逆序回滚业务提交 `dba837f`、`84ad32a`、`df6cda4`、`63ef01c` 并重新执行迁移和 Compose 构建。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 计划必须选择可用执行服务器 | Frontend 数据同步契约测试加载服务器列表，输入启用、停用和空选择 | 停用服务器不可选，空选择阻止保存；通过 | 正常、边界、异常 |
| 查询和预检使用所选服务器 | Frontend 契约测试拦截表查询与预检请求，检查 `serverId` | 两类请求均携带当前服务器；通过 | 正常、回归 |
| 服务器权限和状态得到隔离 | Backend Service 校验测试覆盖不存在、非拥有、停用服务器；Agent 协议测试覆盖非法响应 | 返回业务错误，不泄露目标配置；通过 | 权限、安全、异常 |
| 任务真正远程执行并可恢复进度 | Backend Agent Client、H2 Worker 测试和 Go NDJSON 进程测试 | Agent 收到目标配置和 Worker 镜像，Worker 产生表清单、预检结果和进度，平台可恢复终态；通过 | 集成、正常、兼容 |
| 并发、取消和失败状态正确落库 | Backend 远程执行测试及服务状态分支测试 | 活跃槽位防重复执行，取消转发到 Agent，失败/取消释放槽位；通过 | 边界、异常、回归 |
| 数据库迁移及历史计划兼容 | Backend 资源测试检查 V33 字段、索引和约束；历史 `server_id` 为空的计划映射平台本地 | 迁移结构完整，历史计划可查看和运行；通过 | 兼容、回归 |
| 前端和运行环境不回归 | Frontend 完整质量门、Backend 完整 Maven、Agent 完整 Go、干净 worktree Compose 构建 | 质量门、镜像构建、默认服务健康检查全部通过；通过 | 回归、构建、运行态 |

### 测试执行结果

- Backend 完整 Maven 测试：775/775 通过，通过率 100%，失败 0、错误 0、跳过 0；数据同步定向测试 23/23 通过。
- Frontend `npm test`：Lint、类型检查、354/354 单元与契约测试、覆盖率、生产构建及 1/1 E2E 全部通过；覆盖率行 98.39%、分支 80.95%、函数 95.27%。
- Deployment Agent Go 1.26.6：25/25 测试通过，`go vet ./...` 通过，Dockerfile 镜像构建测试通过。
- 干净功能提交 worktree 执行 Compose 重建成功；Backend、Frontend、Python Worker、Document Parser、Caddy 五个默认服务均 healthy。Deployment Agent `deployment` profile 镜像构建成功。
- 已验证自动化用例合计 1,155 个（Backend 775、Frontend 354、E2E 1、Agent 25），全部通过。

### 关键模块测试

- `DataSyncService`：服务器校验、迁移映射、远程查询/预检/执行、取消、轮询恢复和并发槽位共 23/23 定向测试通过。
- `DataSyncAgentClient` 与 `DataSyncRemoteWorker`：HTTP 协议、敏感字段不回显、H2 数据复制、NDJSON 进度和终态共 2 个 Backend 测试通过。
- `deployment-agent/main.go`：数据同步请求校验、Docker/SSH 参数、NDJSON 读取、取消进程和输出缓冲共 25/25 Go 测试通过。
- `DataSyncView.vue`：服务器选择、停用选项、请求参数、计划/运行服务器展示和历史计划兼容契约通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn test -B -ntp` | 775/775 通过 |
| Frontend 完整质量门 | `cd frontend && npm test` | Lint、类型检查、354/354、覆盖率、构建、1/1 E2E 通过 |
| Deployment Agent 回归 | Go 1.26.6 容器执行 `gofmt -l`、`go test ./...`、`go vet ./...` | 25/25 通过，格式和静态检查通过 |
| 共享工作区 Compose 尝试 | `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d` | 受其他任务未提交的 `DeviceAgentPermissionMigrationTest` 断言失败阻断，未纳入验收结果 |
| 干净 worktree Compose 重建 | `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose --env-file /Users/xyzc/github/base-ai/.env up --build -d` | 五个默认服务构建、启动并健康 |
| Deployment Agent 镜像 | `docker compose --profile deployment build deployment-agent` | 构建成功，包含 Agent 数据同步测试文件 |
| 运行态检查 | `docker compose ps` | Backend、Frontend、Python Worker、Document Parser、Caddy 5/5 healthy |

### 重测触发条件

- 修改数据同步计划字段、服务器权限、Agent 协议、远程 Worker、运行状态机或 V33 迁移时，必须重新执行 Backend 数据同步定向测试和完整 Maven 测试。
- 修改数据同步页面、请求参数、国际化或服务器选择交互时，必须重新执行 Frontend 数据同步测试和完整质量门。
- 修改 Deployment Agent 数据同步命令、Worker 镜像或 Compose profile 时，必须重新执行 Go 全量测试、Agent 镜像构建及干净 worktree Compose 重建。
- 修改核心配置或镜像构建方式时，必须重新执行完整测试报告流程并更新 Git 基准点。

### 已知问题

- 未在真实远程 SSH 主机和真实 MySQL/PostgreSQL 实例上执行端到端复制；本次覆盖 Agent 协议、SSH/Docker 命令构造、H2 Worker 和进度恢复，真实目标机验证仍需受控环境。
- Deployment Agent 是可选 `deployment` profile；本次构建了镜像但未挂载真实 Docker 套接字常驻运行，生产环境需配置内部令牌、目标机 Docker 权限和 `DATA_SYNC_WORKER_IMAGE`。
- 共享工作区仍保留其他任务的未提交修改；本次功能和报告提交未纳入这些文件，Compose 验收使用干净隔离快照完成。
- Frontend 生产构建保留既有 runtime-config 非 module、PURE 注解和大 chunk 警告，未影响构建或运行。

### 下次测试建议

- 准备一台受控 SSH Docker 主机，使用真实数据库执行表查询、预检、增量复制、取消和失败重试，核对 Agent 任务终态与平台运行记录一致。
- 增加远程 Worker 镜像拉取失败、网络超时、数据库凭据错误和大表进度长轮询的容器级集成测试。
- 在浏览器中覆盖启用/停用服务器切换、历史计划编辑、取消运行和中英文切换，确认敏感配置始终不进入前端响应和日志。

## 服务器 Compose 自动检测与私钥文件验收（2026-09-11）

### Git 基准点

Commit: aa13c4a5a09cb22e1900dbcace5f92474721a0b0
- 提交信息: Auto-detect server Compose projects
- 测试日期: 2026-09-11
- 分支: master
- 未执行 git push。

### 变更范围

- 新增或编辑服务器时不再展示、要求或提交 Compose 目录和 Compose 文件；连接测试只验证本机可用或 SSH 可连通。
- 部署任务在未保存旧版 Compose 配置时自动发现项目：本机固定检查 `/workspace`，远程优先读取 Docker Compose 容器标签，再以最大深度 4 扫描 `$HOME`、`/opt`、`/srv`，最多处理 40 个候选。
- 仅接受 `docker-compose.yml` 或 `compose.yml`，并通过 `backend`、`frontend`、`caddy` 服务组合识别 Base AI；没有匹配或存在多个匹配时明确失败，不猜测部署目标。
- SSH 私钥既可直接粘贴，也可由浏览器选择本地文件后读取文本；只显示本地文件名，提交的仍是私钥内容，并沿用后端加密保存流程。
- 保留历史服务器已保存 Compose 目录和文件的兼容行为；未新增依赖、配置、数据迁移或删除文件。可执行 `git revert aa13c4a5a09cb22e1900dbcace5f92474721a0b0` 回滚并重新构建服务。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 新增服务器无需 Compose 配置 | Backend H2 Service 测试提交空目录和空文件；Frontend 契约测试检查表单与请求模型 | 服务器创建成功，界面无 Compose 输入项，请求保持空值；通过 | 正常、边界、兼容 |
| 连接测试不依赖 Compose | Go 单测输入空 Compose 配置并调用本机 `/test` 处理器 | 校验与连接测试成功，不执行 Compose；通过 | 正常、回归 |
| 部署时自动发现唯一 Base AI 项目 | Go 单元与 Shell 集成测试提供标签候选、扫描候选及唯一有效服务组合 | 标签优先、候选去重，并选择唯一 `backend/frontend/caddy` 项目；通过 | 正常、兼容、集成 |
| 不安全或不确定目标不得部署 | Go 参数化测试覆盖零候选、多个 Base AI、多个普通候选、非法路径及超过 40 个候选 | 返回未找到、歧义或非法配置错误，不执行部署；通过 | 边界、异常、安全 |
| 本机发现遵循固定挂载目录 | Go 单测在临时 `/workspace` 等价根目录创建零个、一个或两个 Compose 文件 | 唯一文件成功；零个和多个均失败；通过 | 正常、边界、异常 |
| 私钥文件与粘贴输入均可用 | Frontend 单测输入普通文件、空文件、64 KiB 临界文件、超限元数据、UTF-8 超限内容和读取异常 | 合法内容回填同一私钥字段；空值、超限和读取失败均提示；通过 | 正常、边界、异常 |
| 私钥文件路径不会上传 | Frontend 契约测试检查文件选择器、读取工具和提交模型 | 仅本地显示文件名，请求仅携带私钥文本；后端继续加密保存；通过 | 权限、安全、隐私 |
| 历史 Compose 配置保持兼容 | Backend 与 Go 测试输入成对的旧目录和文件，并覆盖缺一项、非法文件名和目录穿越 | 合法旧配置直接使用；部分或不安全配置被拒绝；通过 | 兼容、安全、回归 |
| 完整质量门及运行环境无回归 | Backend、Frontend、Deployment Agent 完整测试和精确提交 Compose 镜像构建、健康检查 | 772/772 Backend、347/347 Frontend、1/1 E2E、21/21 Go 通过；五个默认服务及代理探活通过 | 回归、构建、运行态 |

### 测试执行结果

- 缺陷复现：修复前 Backend 新增服务器测试因 Compose 必填校验失败；Go 空 Compose 校验和本机连接处理器 2 个测试失败；Frontend 因缺少私钥文件读取模块失败，均稳定复现需求缺口。
- Backend 定向测试：18/18 通过；完整 Maven 测试：772/772 通过，通过率 100%，失败 0、错误 0、跳过 0。
- Frontend 私钥文件与服务器表单定向测试：5/5 通过；完整单元与契约测试：347/347 通过；E2E：1/1 通过，失败 0、错误 0、跳过 0。
- Frontend 覆盖率：行 98.30%、分支 81.00%、函数 95.80%；新增私钥文件读取工具行、分支、函数均为 100%。
- Deployment Agent：21/21 Go 测试通过，失败 0；Shell 发现测试通过伪 Docker 输出验证标签优先、目录扫描、服务识别和安全过滤。
- 总计 1,141 个不重复自动化用例通过；ESLint、Vue 类型检查、Vite 生产构建以及全部精确提交镜像构建均通过。
- 最终 Backend、Frontend、Python Worker、Document Parser、Caddy 均运行 revision `aa13c4a5a09cb22e1900dbcace5f92474721a0b0` 且为 healthy；网关就绪接口返回 `UP`。

### 关键模块测试

- `ServerManagementService`：无 Compose 创建、旧配置兼容、部分配置拒绝、非法目录和文件名校验，以及凭据加密路径共 18/18 定向测试通过。
- `ServersView.vue` 与 `serverCredentials.js`：文件读取、64 KiB 上限、UTF-8 字节长度、异常提示、仅文件名本地展示及粘贴输入兼容通过。
- `deployment-agent/main.go`：连接测试解耦、候选发现、Base AI 服务识别、唯一性选择、安全校验、旧配置优先和部署失败记录共 21/21 通过。
- 完整回归：Backend 772/772、Frontend 347/347 加 1/1 E2E、Deployment Agent 21/21 通过；Compose 运行态 5/5 默认服务 healthy。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 定向回归 | Maven 执行 `ServerManagementValidationTest,ServerManagementMonitorTest` | 18/18 通过 |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 772/772 通过 |
| Frontend 定向回归 | `node --test test/servers.test.mjs` | 5/5 通过 |
| Frontend 完整质量门 | `npm test` | Lint、类型检查、347/347 测试、覆盖率、生产构建及 1/1 E2E 全部通过 |
| Deployment Agent 完整回归 | Go 1.26.6 容器执行 `go test ./...` | 21/21 通过 |
| 统一重建 | `docker compose up --build -d` | 功能工作区内容构建并启动成功；发现初次传入的长 revision 值有误后未将其作为最终版本验收 |
| 精确提交重建与切换 | 提交 `aa13c4a5a09cb22e1900dbcace5f92474721a0b0` 的隔离干净 worktree 构建全部默认镜像和 Deployment Agent 镜像，再以 `--no-build` 切换默认服务 | 镜像构建成功，避免纳入共享工作区并发改动；五个默认服务 revision 精确一致且 healthy |
| 运行态探活 | `docker compose ps`、HTTPS 就绪接口、无 Docker 套接字的临时 Deployment Agent 容器 `/health` | 5/5 默认服务 healthy；网关与代理均返回 `UP`；临时容器已清理 |

### 重测触发条件

- 后续修改服务器创建/编辑字段、连接测试语义、凭据加密或私钥输入处理时，必须执行 Backend 服务器管理定向测试、Frontend 服务器测试及完整回归。
- 后续修改 Compose 候选来源、扫描边界、服务识别、路径安全校验或唯一性策略时，必须执行 Deployment Agent 完整测试，并在受控目标机复验真实部署。
- 后续修改 Compose 挂载路径、Deployment Agent 运行配置或镜像构建方式时，必须重新执行精确提交 Compose 重建、默认服务健康检查和代理健康检查。

### 已知问题

- 未对真实远程 SSH 主机执行部署、升级或重启；该验证需要独立目标机及受控 Docker 权限。本次以 SSH 命令生成测试、伪 Docker Shell 集成测试和完整 Go 单测覆盖发现逻辑。
- Deployment Agent 属于可选 profile，当前环境未配置真实内部令牌及 rootless Docker 套接字，因此未作为常驻服务启动；已构建精确 revision 镜像，并在不挂载 Docker 套接字的临时容器中确认 `/health` 返回 `UP`，临时容器已清理。
- Frontend 生产构建仍输出既有 runtime-config 非 module、PURE 注解和大 chunk 警告；未影响构建或运行，本次未扩大范围处理。
- 共享工作区存在其他任务并发修改；功能与报告提交均只纳入本任务文件，精确镜像从功能提交的隔离干净 worktree 构建。

### 下次测试建议

- 准备两台受控 SSH 测试机，分别放置唯一 Base AI Compose 项目和两个 Base AI Compose 项目，验证真实 Docker 标签、目录扫描、唯一部署和歧义阻断。
- 在浏览器中选择 OpenSSH、RSA、ED25519 私钥文件并完成真实 SSH 连接，确认本地文件名不进入网络请求、数据库和日志。
- 后续可增加浏览器端请求拦截 E2E，以及 Deployment Agent 与 rootless Docker 套接字的容器级集成测试。

## 新增数据源配置表单优化验收（2026-09-11）

### Git 基准点

Commit: 37055f09e801bf335cf89c7d5b044831d6b2c862
- 提交信息: Improve data source configuration form
- 测试日期: 2026-09-11
- 分支: master
- 未执行 git push。

### 变更范围

- 新增数据源表单改为紧凑双列布局，并按连接、认证、范围、行为分组；窄屏自动回退单列。
- 为各数据源补充配置说明、字段示例、必填/选填/条件必填状态、风险提示和明确的布尔开关状态。
- 对齐实际后端参数：Redis 增加 `allowWrite`；Webhook 不再展示后端未消费的 `secret`，但编辑历史数据时仍保留该值。
- 增加标准字段必填校验和 Kafka SASL 条件校验；示例连接地址改为占位提示，避免误把示例值当作真实默认配置提交。
- 补齐中英文文案。未修改后端接口、数据结构、数据库、配置或依赖；可执行 `git revert 37055f09e801bf335cf89c7d5b044831d6b2c862` 回滚并重新构建服务。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 配置项按用途紧凑展示并适配窄屏 | Frontend 契约测试读取表单分组、双列网格及响应式样式 | 四类分组存在，宽字段可跨列，窄屏切换单列；通过 | 正常、边界、兼容 |
| 数据源参数与实际能力一致 | Frontend 单测遍历内置数据源字段，重点输入 Redis、Webhook | Redis 包含 `allowWrite`；Webhook 标准字段不含 `secret`；通过 | 正常、回归 |
| 历史 Webhook 配置不丢失 | Frontend 单测输入含旧 `secret` 的 Webhook 配置并执行创建、合并 | 标准表单不展示该字段，保存时仍保留历史值；通过 | 兼容、数据安全 |
| 必填与条件必填配置在提交前被识别 | Frontend 单测输入空标准字段及启用 SASL 但缺少认证信息的 Kafka 配置 | 返回准确缺失字段；未启用 SASL 时不误报；通过 | 边界、异常、安全 |
| 示例值不会成为默认提交值 | Frontend 单测创建各类型初始配置 | 地址和 URI 默认为空，示例仅作为占位提示；通过 | 正常、兼容 |
| 中英文界面均提供配置指导 | Frontend 契约测试检查中英文分组、状态、风险与错误文案 | 两种语言所需键值完整；通过 | 兼容、回归 |
| 前端历史功能与生产产物不回归 | Frontend 完整测试、E2E、Lint、类型检查和 Vite 生产构建 | 347/347 单元与契约测试、1/1 E2E 通过，质量门和构建通过 | 回归、构建 |
| 当前代码应用到运行环境 | 功能提交的隔离干净 worktree 执行完整 Compose 重建、健康检查和 HTTPS 探活 | 五个默认服务全部 healthy，HTTPS 首页返回 200；通过 | 集成、运行态 |

### 测试执行结果

- Frontend 定向测试：10/10 通过；完整单元与契约测试：347/347 通过；E2E：1/1 通过；失败 0、错误 0、跳过 0。
- Frontend 覆盖率：行 98.22%、分支 80.81%、函数 95.80%；连接配置工具行覆盖率 98.89%、分支 81.40%、函数 100%。
- ESLint、Vue 类型检查和 Vite 生产构建均通过。
- 功能提交隔离环境执行完整 Compose 构建与启动成功；Backend、Frontend、Python Worker、Document Parser、Caddy 均使用 revision `37055f09e801bf335cf89c7d5b044831d6b2c862` 且健康；HTTP 跳转 HTTPS，HTTPS 首页返回 200。
- 共享工作区首次重建读取到其他任务尚未提交的 Server Management 变更，Backend 测试出现 1 个与本功能无关的错误；按用户确认改用功能提交的隔离干净 worktree 重建后全部通过，未将共享工作区改动带入镜像。

### 关键模块测试

- `workflowConnectionConfig.js`：字段清单、安全默认值、元数据、条件必填和历史配置兼容共 10/10 定向测试通过。
- `DataSourcesView.vue`：分组双列布局、动态扩展字段、风险开关、缺失字段提示和移动端样式契约通过。
- Frontend 完整回归：347/347 加 1/1 E2E 通过；连接、权限、运行时配置和生产构建无回归。
- Compose 运行态：5/5 默认服务 healthy，HTTPS 首页可访问。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Frontend 定向回归 | `cd frontend && node --test tests/workflowConnectionConfig.test.js` | 10/10 通过 |
| 代码规范 | `cd frontend && npm run lint` | 通过 |
| 类型检查 | `cd frontend && npm run typecheck` | 通过 |
| Frontend 完整质量门 | `cd frontend && npm test` | 347/347 测试、1/1 E2E、生产构建全部通过 |
| 共享工作区重建尝试 | `APP_IMAGE_REVISION=58ca841... docker compose up --build -d` | 其他任务未提交的 Server Management 代码导致 Maven 771 个测试中 1 个错误，未作为本功能验收结果 |
| 隔离环境完整重建 | 功能提交干净 worktree 执行 `APP_IMAGE_REVISION=37055f09e801bf335cf89c7d5b044831d6b2c862 docker compose --env-file /Users/xyzc/github/base-ai/.env up --build -d` | 构建、测试和启动成功，五个默认服务全部 healthy |
| 运行态探活 | `docker compose ps`、HTTP 和 HTTPS 首页请求 | 5/5 healthy；HTTP 返回 308；HTTPS 返回 200 |

### 重测触发条件

- 后续修改数据源类型、标准参数、条件必填规则、插件扩展字段或配置序列化逻辑时，必须重新执行连接配置定向测试和 Frontend 完整质量门。
- 后续修改新增/编辑数据源表单布局、响应式断点、国际化键或风险开关交互时，必须补充相应契约测试并执行 Frontend 完整回归。
- 后续修改后端连接参数消费逻辑时，必须同步核对前端字段定义，并执行相关 Backend 测试及完整 Compose 重建。

### 已知问题

- 本次未连接真实 MySQL、Kafka、Redis、Webhook 等外部服务逐项验证凭据有效性；参数清单依据现有后端消费逻辑核对，并由 Frontend 自动化测试覆盖表单行为。
- Frontend 生产构建仍输出既有的 runtime-config 非 module、PURE 注解和大 chunk 警告；未影响构建或运行，本次未扩大范围处理。
- 共享工作区仍有其他任务的 README、Server Management、Deployment Agent 和服务器凭据相关未提交变更；本次两个提交不包含这些文件。

### 下次测试建议

- 在具备测试实例时，为 MySQL、Kafka SASL、Redis 只读/写入和 Webhook 请求分别执行真实连接测试，验证提示内容与外部服务配置一致。
- 增加登录态浏览器视觉回归，覆盖桌面双列、移动端单列、长错误文案以及中英文切换后的高度变化。

## 新增数据源配置向导 V2 验收（2026-09-11）

### Git 基准点

Commit: 8688e17dbd7dc9e884ca8809a8e19d53304c6bfe
- 提交信息: Redesign data source setup wizard
- 测试日期: 2026-09-11
- 分支: master
- 未执行 git push。

### 变更范围

- 新增数据源改为两步向导：先选择数据源类型，再进入配置；类型卡片按分类展示品牌色、参数摘要和安全提示。
- 配置页改为紧凑双列表单，右侧增加配置助手，展示必填进度、当前字段示例、风险提示和参数清单；窄屏自动切换单列。
- 高级选项默认收起，检测到非默认配置时自动展开；切换数据源类型前对已有配置进行风险确认。
- “保存并检测”复用现有保存接口与连接检测接口；保存成功但检测失败时保留弹窗并明确提示已保存结果。
- 增加必填字段实时校验、插件组件提示和中英文文案；未修改后端接口、数据结构、数据库、配置或依赖。可执行 `git revert 8688e17dbd7dc9e884ca8809a8e19d53304c6bfe` 回滚并重新构建服务。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 新增流程按“选类型—配参数”分步完成 | Frontend 契约测试检查向导步骤、类型卡片、配置阶段和继续按钮 | 两步结构、分类卡片和配置入口均存在；通过 | 正常、回归 |
| 配置页面具备明显的填写辅助 | Frontend 契约测试检查双列布局、配置助手、进度条、示例、风险和参数清单 | 桌面端显示助手，窄屏切换单列；通过 | 正常、边界、兼容 |
| 高级选项默认收起并保留已配值 | Frontend 单测输入默认值、非默认值和自定义参数 | 默认配置保持收起，已配置高级项自动展开且值不丢失；通过 | 正常、边界、兼容 |
| 切换类型不会静默丢失配置 | Frontend 单测输入空配置、安全默认值、普通配置和历史敏感配置 | 无实际配置可直接切换；有配置先要求确认；取消时保留原表单；通过 | 异常、安全、回归 |
| 保存并检测复用既有接口并正确处理失败 | Frontend 单测模拟有权限、无检测权限、检测失败和保存失败 | 先保存再按权限检测；检测失败明确返回已保存状态；保存失败不触发检测；通过 | 正常、异常、权限、安全 |
| 必填字段在提交前实时提示 | Frontend 单测输入空值、空白值、完整值及 Kafka SASL 条件分支 | 缺失字段准确提示，条件未启用时不误报；通过 | 边界、异常、兼容 |
| 中英文界面保持完整 | Frontend 契约测试检查向导、助手、按钮、校验和检测结果资源键 | 中英文资源均覆盖新增界面文案；通过 | 兼容、回归 |
| 现有数据源参数和历史配置不回归 | Frontend 单测遍历十二类连接、旧 Webhook 签名密钥、嵌套值与未知字段 | 字段清单、敏感值透传和自定义配置保持兼容；通过 | 兼容、回归、安全 |

### 测试执行结果

- 数据源配置定向测试：14/14 通过，通过率 100%，失败 0、错误 0、跳过 0。
- Frontend 完整 `npm test`：355 个用例中 353 个通过、2 个失败、错误 0、跳过 0；失败为并发服务器页面改动引起的“服务器页面支持手工新增 SSH 配置并保留本地模式兼容”和“私钥支持本地文件读取并保留直接粘贴输入”，与本次数据源向导无关，未修改相关文件。
- ESLint、Vue 类型检查和 Vite 生产构建均通过；构建仅输出既有 runtime-config 非 module、PURE 注解和大 chunk 警告。
- 本次只修改 Frontend 业务界面、连接配置工具和测试；按测试报告规则未触发 Backend 基准点重测，但 Compose 重建会执行后端质量门。

### 关键模块测试

- `workflowConnectionConfig.js`：类型切换保护、高级配置识别、保存/检测顺序、权限分支、部分失败和持久化失败共 14/14 通过。
- `DataSourcesView.vue`：两步向导、类型卡片、配置助手、实时校验、高级选项、检测失败留窗和响应式样式均由契约测试覆盖。
- Frontend 质量门：Lint、类型检查和生产构建通过；完整测试仅受并发 ServersView 测试失败影响。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 数据源定向回归 | `cd frontend && node --test tests/workflowConnectionConfig.test.js` | 14/14 通过 |
| 代码规范 | `cd frontend && npm run lint` | 通过 |
| 类型检查 | `cd frontend && npm run typecheck` | 通过 |
| 生产构建 | `cd frontend && npm run build` | 通过；保留既有构建警告 |
| Frontend 完整回归 | `cd frontend && npm test` | 355 总计、353 通过、2 失败；失败均来自并发 ServersView/私钥测试 |
| Compose 重建尝试 | 在提交 `8688e17dbd7dc9e884ca8809a8e19d53304c6bfe` 的隔离 worktree 执行 `APP_IMAGE_REVISION=8688e17dbd7dc9e884ca8809a8e19d53304c6bfe docker compose --env-file /Users/xyzc/github/base-ai/.env up --build -d` | Backend Maven 777 个测试中 1 个失败：`DeviceAgentPermissionMigrationTest.movesDeviceAgentPermissionsIntoAutomationDomain` 期望 6、实际 5；该失败来自并发 Device Agent 迁移改动，导致 Python Worker 构建被取消，未完成 V2 全服务启动验收 |

### 重测触发条件

- 后续修改数据源类型卡片、配置字段、条件必填、配置序列化或保存/检测顺序时，必须重新执行数据源定向测试和 Frontend 完整质量门。
- 后续修改向导步骤、助手内容、高级选项交互、响应式断点或国际化键时，必须补充对应契约测试并执行 Frontend 完整回归。
- 后续修改后端连接参数消费逻辑、检测接口或 Compose 构建链路时，必须执行相关 Backend 测试和完整 Compose 重建。

### 已知问题

- 尚未连接真实 MySQL、Kafka、Redis、Webhook 等外部服务验证凭据有效性；本次验证覆盖字段、权限、顺序和错误处理，不替代真实连通性测试。
- 完整 Frontend 测试中的两个 ServersView 失败属于共享工作区其他任务，未纳入本次提交；Compose 后端失败同样属于 Device Agent 迁移测试，不应通过修改无关代码规避。
- 由于 Compose 构建被上述后端失败中断，当前运行环境未能证明已切换到 V2 镜像；修复并发测试后应重新执行精确提交的 Compose 重建与健康检查。

### 下次测试建议

- 修复或合并并发 ServersView 与 Device Agent 迁移改动后，重新执行 Frontend 355+ 全量测试和 Backend 全量测试。
- 在具备测试实例时，为 MySQL、Kafka SASL、Redis 只读/写入和 Webhook 分别执行真实检测，确认助手示例与服务端错误一致。
- 增加浏览器端视觉回归，覆盖桌面双列、移动端单列、类型切换确认、检测失败留窗及中英文切换。

## Device Agent 升级版本上报修复验收（2026-09-11）

### Git 基准点

Commit: a203957a2b778e2036a7bdd1e1c8c0e0be4ef826
- 提交信息: Report active device agent version
- 测试日期: 2026-09-11
- 分支: master
- 未执行 git push。

### 变更范围

- 修复 Agent 已成功切换代码并由 LaunchAgent 自动重启后，健康心跳仍固定上报 Python 包版本 `1.0.0`，导致管理页持续显示“待重启生效”的问题。
- 健康心跳现在从 `current` 符号链接解析本机实际激活的发布目录版本，并继续使用发布清单的时间戳与代码哈希格式。
- `current` 缺失、断链、指向版本目录外或版本名称非法时安全回退静态包版本，避免启动失败或把非发布路径作为版本上报。
- 未修改后端、前端、配置、数据库结构或依赖。可执行 `git revert a203957a2b778e2036a7bdd1e1c8c0e0be4ef826` 回滚，并重新构建服务及升级 Agent。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| Agent 重启后上报实际激活的发布版本 | Agent 单测创建合法版本目录并让 `current` 指向 `20260911.0123+abcdef123456` | 返回发布目录名称，不再返回固定 `1.0.0`；通过 | 正常、回归 |
| 健康心跳使用实际发布版本 | Agent Runtime 单测替换版本探测、历史版本及 XCUITest 探测结果后调用 `report_health` | `agentVersion` 为实际发布版本，其余健康字段保持兼容；通过 | 正常、兼容 |
| 不可用版本链接不影响 Agent 启动 | 参数化单测覆盖 `current` 缺失和断链 | 两种输入均安全回退 `1.0.0`；通过 | 边界、异常 |
| 非可信版本链接不得作为版本上报 | 参数化单测覆盖链接指向版本目录外和包含 `..` 的非法名称 | 两种输入均拒绝目标并回退，不泄露外部路径；通过 | 权限、安全、恶意输入 |
| 升级、回退和 Agent 历史功能保持兼容 | Python 3.12 完整 Device Agent pytest 输入全部 11 个测试模块 | 36/36 通过，升级、回退、Registry、诊断、设备发现和 WDA 无回归 | 兼容、回归 |
| 当前代码应用到运行环境 | 完整 Compose 重建、容器健康检查、网关探活及 Caddy 分发包检查 | 五个默认服务全部 healthy；清单与归档包含新版本及修复代码；通过 | 集成、运行态 |

### 测试执行结果

- 缺陷复现：修复前定向测试共 15 个，9 个通过、6 个按预期失败；失败均为缺少实际发布版本解析或健康上报仍无法使用该版本。
- 修复后定向测试：15/15 通过；完整 Device Agent 测试：36/36 通过，通过率 100%，失败 0、错误 0、跳过 0。
- Python 版本：3.12.13；pytest 9.1.1；coverage 7.16.0。
- 覆盖率：Device Agent 代码、测试合计 1,326 条语句及 258 个分支，综合覆盖率 69%；`test_main.py` 与 `test_upgrade.py` 均为 100%。
- 运行态：Backend、Frontend、Python Worker、Document Parser、Caddy 均使用 revision `a203957a2b778e2036a7bdd1e1c8c0e0be4ef826` 且健康；健康接口返回 `UP`。
- Agent 分发清单版本为 `20260911.0132+159736a1543a`，归档中的健康上报调用和实际版本解析函数均已核验存在。

### 关键模块测试

- `upgrade.py`：合法激活版本、链接缺失、断链、越界和非法名称共 5/5 通过，文件综合覆盖率 69%。
- `main.py`：健康心跳发布版本契约及既有命令生命周期、WDA、Registry 测试通过，文件综合覆盖率 32%。
- Device Agent 完整回归：36/36 通过，覆盖配置、设备发现、诊断、安装契约、主循环、Registry、签名、升级与 WDA。
- Compose 运行态：5/5 默认服务 healthy，网关就绪接口和公开 Agent 清单均可访问。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 缺陷复现 | Python 3.12 执行 `pytest tests/test_upgrade.py tests/test_main.py -q` | 15 个测试中 6 个按预期失败、9 个通过，稳定复现固定版本上报缺陷 |
| 定向回归 | Python 3.12 执行 `pytest tests/test_upgrade.py tests/test_main.py -q` | 15/15 通过 |
| 完整覆盖测试 | Python 3.12 执行 `coverage run --branch -m pytest` 和 `coverage report -m` | 36/36 通过，综合覆盖率 69% |
| 默认服务完整重建 | `APP_IMAGE_REVISION=a203957a2b778e2036a7bdd1e1c8c0e0be4ef826 docker compose up --build -d` | 构建和启动成功，五个默认服务全部 healthy |
| 网关与发布物验证 | 请求 `/api/open/health/ready`、公开清单，并检查 Caddy 归档内容 | 健康接口 `UP`；版本为 `20260911.0132+159736a1543a`；修复代码存在 |

### 重测触发条件

- 后续修改 Agent 发布版本格式、`current` 激活链接、升级/回退流程或健康上报字段时，必须重新执行定向测试和完整 Device Agent 覆盖测试。
- 后续修改 Caddy Agent 归档构建逻辑时，必须重新执行完整 Compose 构建，并核对公开清单版本与归档内容一致。
- 后续修改管理页版本哈希比较或等待重启状态时，必须增加 Frontend 契约测试并复验升级状态自动解除。

### 已知问题

- 当前已安装的本机 Agent 仍是修复前版本；需要从管理页再执行一次升级，下载 `20260911.0132+159736a1543a` 后，新进程才会按实际发布版本上报并自动解除“待重启生效”。
- 本次未执行未变更的 Backend Maven 和 Frontend Node 完整测试；已按确认范围执行完整 Device Agent 测试，Compose 构建成功且所有运行服务健康。

### 下次测试建议

- 从管理页升级真实 Mac Agent 后，确认 LaunchAgent 进程号变化、管理页显示 `20260911.0132+159736a1543a`，且“待重启生效”在首次新心跳后自动消失。
- 后续可增加管理页与真实 Agent 的端到端升级测试，以命令目标版本和首次重启心跳版本一致作为最终断言。

## 数据库连接图标与 Agent 升级互斥验收（2026-09-10）

### Git 基准点

Commit: 194ead1568267edc94c145663543cc5466e2a35b
- 提交信息: Correct device registry test report
- 本次功能提交: `511c63bfb72fbb00782547b07d2c0201607a6de3`（Agent 升级互斥）、`16c78fe1defc88e650e193f6d091ff2e1f7c7e8e`（连接图标）
- 测试日期: 2026-09-10
- 分支: master
- 未执行 git push。

### 变更范围

- 数据库连接选择器将 Kafka、Qdrant、Milvus、Tavily 替换为官方矢量标志；MySQL、PostgreSQL、Elasticsearch 保留既有品牌图形。
- Redis、S3、RabbitMQ、Webhook、Plugin 使用明确的中性语义图标，避免在授权不明确时直接复制品牌商标；S3 继续使用存储桶语义图标。
- Agent 命令创建和领取使用注册行锁串行化状态判断：有活动租约时拒绝创建升级命令，升级排队或执行时拒绝创建其他命令，升级命令优先领取且执行期间停止派发其他命令。
- 保留普通非升级命令的既有领取行为；升级失败、完成或租约过期后恢复后续命令处理。
- 未新增依赖、配置或数据库迁移。可分别执行 `git revert 16c78fe1defc88e650e193f6d091ff2e1f7c7e8e` 和 `git revert 511c63bfb72fbb00782547b07d2c0201607a6de3` 回滚，然后重新构建服务。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 有官方来源的连接类型显示对应标志 | Frontend Node 契约测试读取图标组件，检查 Kafka、Qdrant、Milvus、Tavily 官方路径签名 | 四类官方路径、填色和缩放映射存在，旧占位图形不存在；通过 | 正常、兼容、回归 |
| 无明确授权的类型使用可辨识语义图标 | Frontend Node 契约测试读取 Redis、S3、RabbitMQ、Webhook、Plugin 映射 | 各类型使用独立语义图形，未知类型回退 Plugin；通过 | 正常、边界、兼容 |
| 图标样式和分类导航行为不回归 | Frontend 完整测试输入全部十二种连接类型和七类分类 | 类型颜色、分类图标、编辑配置与页面导航保持既有行为；340/340 通过 | 正常、边界、回归 |
| 活动任务期间不能发起升级 | Backend H2（MySQL 模式）Service 测试先领取普通命令，再创建 UPGRADE | 返回 409 `upgradeAgentBusy`，不新增升级命令；通过 | 状态冲突、并发、安全 |
| 升级排队和执行期间独占 Agent | Backend Service 测试同时准备普通命令和升级命令，并模拟能力缺失、领取、完成 | 升级优先；无升级能力不能绕过；升级执行期间无其他租约；通过 | 正常、异常、兼容 |
| 升级终态后恢复普通任务 | Backend Service 测试分别回报升级 COMPLETED、FAILED，并保留待处理普通命令 | 终态清除互斥，普通命令随后可领取；通过 | 异常、回归 |
| 没有升级任务时保留普通领取行为 | Backend Service 测试领取普通命令后再次领取另一条普通命令 | 仍可按原行为领取，未扩大为全局单租约限制；通过 | 兼容、回归 |
| 当前代码应用到运行环境 | 五个默认服务统一使用基准点标签启动，检查容器健康、网关和前端产物 | 五个服务均健康，revision 一致，健康接口 UP，首页 200，官方图标路径存在 | 集成、运行态、回归 |

### 测试执行结果

- 可计数的完整自动化测试共 1,111 个，通过 1,111 个，通过率 100%；失败 0、错误 0、跳过 0。
- Backend 定向测试：`DeviceAgentCommandServiceTest` 8/8 通过；Backend 完整 Maven 测试 770/770 通过。
- Frontend 图标与连接配置定向测试 7/7 通过；完整覆盖率测试 340/340 通过；E2E 1/1 通过；ESLint、Vue 类型检查和生产构建通过。
- Frontend 覆盖率：分支 80.44%、函数 95.65%、行 98.24%。
- 运行态：Backend、Frontend、Python Worker、Document Parser、Caddy 均为 healthy，镜像 revision 均为 `194ead1568267edc94c145663543cc5466e2a35b`；`GET /api/open/health/ready` 返回 `UP`，HTTPS 首页返回 200。
- 默认 Compose 完整重建两次在未修改的 Python Worker `apt-get update` 阶段失败：当前网络代理对 Debian `trixie/main` ARM64 索引持续返回连接失败/404。已使用成功构建并测试的当前 Backend JAR、Frontend dist 和 Caddy 镜像，结合未变更的 Python Worker 基础镜像进行无网络组装，再执行 Compose 统一替换；运行环境已应用本次代码，但完整联网重建仍需网络恢复后复验。

### 关键模块测试

- `DataSourceTypeIcon.vue` 与连接配置契约：7/7 通过，覆盖官方路径、语义回退、分类导航、样式和配置兼容。
- `DeviceAgentCommandServiceTest`：8/8 通过，覆盖创建冲突、领取优先级、能力缺失、成功/失败终态和普通命令兼容。
- Backend 完整回归：770/770 通过，覆盖 Domain、Repository、Service、Controller 及数据库迁移契约。
- Frontend 完整回归：340/340 加 1/1 E2E 通过，覆盖页面、权限、运行时配置和生产产物。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Frontend 缺陷复现 | 修改前新增官方路径契约断言并执行 `node --test tests/workflowConnectionConfig.test.js` | 新断言稳定失败，确认旧 Kafka、Qdrant、Milvus、Tavily 占位图形不符合预期 |
| Frontend 定向回归 | `cd frontend && node --test tests/workflowConnectionConfig.test.js` | 7/7 通过 |
| Frontend 完整质量门 | `cd frontend && npm test` | ESLint、类型检查、340/340 覆盖率测试、生产构建及 1/1 E2E 全部通过 |
| Backend 定向回归 | Maven 3.9.9 / Temurin 17 容器执行 `mvn -B -ntp -s /tmp/settings.xml -Dtest=DeviceAgentCommandServiceTest test` | 8/8 通过 |
| Backend 完整回归 | Maven 3.9.9 / Temurin 17 容器执行 `mvn -B -ntp -s /tmp/settings.xml test` | 770/770 通过，失败 0、错误 0、跳过 0 |
| 默认服务完整重建 | 两次执行 `APP_IMAGE_REVISION=<feature-commit> docker compose up --build -d` | Backend 测试和多个镜像阶段通过，均由外部 Debian ARM64 包索引连接失败/404 阻断 |
| 无网络运行态替换 | 复用已验证构建产物组装统一 revision 镜像，再执行 `APP_IMAGE_REVISION=<baseline> docker compose up -d --no-build` | 五个默认服务全部 healthy，统一 revision 生效 |
| 网关与产物验证 | 请求 `/api/open/health/ready`、HTTPS 首页，并在 Frontend 容器检查四类官方 SVG 路径签名 | 健康接口 UP、首页 200、官方图标签名全部存在 |

### 重测触发条件

- 后续修改连接类型、图标路径、分类导航或品牌使用策略时，必须重新执行 Frontend 定向测试和完整质量门。
- 后续修改 Agent 命令状态、租约期限、能力协商、升级流程或注册锁粒度时，必须重新执行 `DeviceAgentCommandServiceTest` 和 Backend 完整测试。
- 网络恢复后必须重新执行 `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`，确认联网完整重建和五个默认服务健康。

### 已知问题

- 完整联网 Compose 重建仍受当前外部 Debian 镜像索引连接失败/404 阻断；该问题不来自本次代码，当前运行态通过复用已验证且对应源码未变更的基础镜像完成。
- Frontend 生产构建仍输出既有的 runtime-config 非 module、PURE 注解和大 chunk 警告；未影响构建和测试，本次未扩大范围处理。
- Redis、S3、RabbitMQ 当前使用语义图标而非品牌商标；后续确认官方素材及授权条件后可单独替换，其中 S3 按本次确认暂不处理为 AWS 品牌图标。
- 未使用登录态浏览器逐项截图比较连接选择器；当前由源文件契约、完整 Frontend E2E 和容器生产产物签名共同验证。

### 下次测试建议

- 网络恢复后完成一次不复用运行镜像的完整 Compose 重建，并再次核对五个服务的 revision 和健康状态。
- 使用不同尺寸和深浅主题实际打开连接选择器，确认官方多色图标的 24px 可读性和视觉对齐。
- 增加两个并发事务同时创建 UPGRADE 与普通命令的数据库集成测试，进一步验证 MySQL `FOR UPDATE` 的竞争顺序。

## MacAir iOS 设备池离线修复验收（2026-09-10）

### Git 基准点

Commit: 3902fd54bcba7f0ff3882759856fa2eff3432880
- 提交信息: Fix device registry tunnel bootstrap
- 测试日期: 2026-09-10
- 分支: master
- 未执行 git push。

### 变更范围

- 修复 Device Agent 的 Registry 隧道引导循环依赖：本机发现到的候选设备即使尚未建立隧道，也会通过受限 Unix Socket 交给 root Helper 创建隧道。
- 设备池在线状态仍严格依据 `devicectl` 的真实隧道状态，不会把已配对但当前不可用的设备伪造成在线。
- 原始 UDID 仍仅存在于 Agent 内存和 root-owned 本机 IPC 中，后端同步、数据库和前端继续只使用匿名 SHA-256 设备标识。
- 未修改后端、前端、配置、数据库迁移或依赖。回滚可执行 `git revert 3902fd54bcba7f0ff3882759856fa2eff3432880`，重新发布 Caddy Agent 包，并将 MacAir Agent 切回本机保留的旧版本。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 未建隧道的候选设备可引导 Registry | Agent 参数化单测输入 `connected=false` 候选设备 | 原始 UDID 进入本机 `RegistryConfig`，修复前稳定失败、修复后通过 | 正常、回归 |
| 不伪造设备在线状态 | 同一单测检查离线候选的后端安全报告 | Registry 可接收候选，但报告中的 `connected` 仍为 `false` | 兼容、安全 |
| 已连接和空设备池行为兼容 | 参数化单测输入已连接设备、空列表及在线/离线混合集合 | 已连接设备继续传递，空列表保持为空，发现顺序不变 | 边界、兼容 |
| 原始设备标识不进入后端 | 设备发现既有隐私测试及 Registry 本机 IPC 测试 | 后端报告仅含匿名摘要，原始 UDID 仅交给本机 Helper | 安全、回归 |
| MacAir 设备池不再循环离线 | 升级真实 MacAir Agent 后跨 3 次约 30 秒同步周期读取数据库 | iPhone 持续为 `AVAILABLE`；Registry 持续 `ONLINE` 且错误码为空 | 集成、真实设备、回归 |
| 当前不可用设备保持离线 | macOS `devicectl list devices` 返回 iPad `unavailable` | iPad 保持 `OFFLINE`，未被修复逻辑错误标记在线 | 异常、兼容 |
| 统一重建不夹带并发改动 | 功能提交干净 worktree 执行默认 Compose 重建 | Backend 测试、Frontend/Caddy 构建通过；Python Worker 因外部 Debian 索引 404 阻断，完整重建未通过 | 集成、环境限制 |

### 测试执行结果

- Mac Agent 完整 pytest：30/30 通过，通过率 100%；失败 0、错误 0、跳过 0。
- 缺陷复现：新增参数化回归用例修复前 2/4 通过，两个包含未建隧道候选设备的分支按预期失败；修复后相关定向测试 9/9 通过。
- Backend：功能提交干净 worktree 的 Docker 构建阶段运行 Maven 完整测试，765/765 通过，失败 0、错误 0、跳过 0。
- Frontend：功能提交干净 worktree 的生产构建通过；Caddy 成功生成带校验清单的新 Agent 包。
- 真实 MacAir：Agent 从 `20260910.0956+d37912b9621c` 升级到最终干净包 `20260910.1340+0d19436782c2` 并成功重启；BaseAI Registry Helper 的受管进程包含 2 个脱敏设备参数。
- 运行态连续 3 次同步中，iPhone 均为 `AVAILABLE`；Registry 均为 `ONLINE`、无 `REGISTRY_DEVICE_NOT_CONFIGURED`；iPad 由 macOS 报告为 `unavailable`，因此保持离线。
- 默认服务完整 Compose 重建连续两次失败，失败数 2：均为未修改的 Python Worker 镜像执行 `apt-get update` 时 Debian `trixie/main` 索引经当前网络代理返回连接失败/404。该项未通过，不以 Caddy 单服务发布替代完整重建结果。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Agent 失败复现 | 临时 Python 3.12 venv 执行 `python -m pytest -q tests/test_main.py::test_registry_receives_all_discovered_devices` | 修复前 2/4 通过、2/4 按预期失败 |
| Agent 定向回归 | 临时 Python 3.12 venv 执行 Registry、设备发现和主循环相关测试 | 9/9 通过 |
| Agent 完整回归 | 临时 Python 3.12 venv 执行 `python -m pytest` | 30/30 通过 |
| 默认服务统一重建 | 功能提交干净 worktree 两次执行 `APP_IMAGE_REVISION=<feature-commit> docker compose up --build -d` | 两次均在 Python Worker Debian 包索引下载处失败；Backend 765/765 与 Frontend/Caddy 构建已通过，容器替换前退出 |
| Caddy Agent 包发布 | 使用已成功生成的功能提交 Caddy 镜像，以 `--no-build --no-deps` 恢复正式工作区挂载 | Caddy 使用完整功能提交镜像并健康，新 Agent 清单和包校验可下载 |
| MacAir Agent 升级 | 通过 Agent 既有校验下载与原子切换机制升级，随后 kickstart 用户 LaunchAgent | 新版本生效，进程运行，Agent 标准输出和错误日志均为空 |
| 真实运行态验证 | 跨 3 个同步周期读取 Registry 和匿名设备池状态，并用 `devicectl` 核对物理状态 | iPhone 持续在线；Registry 持续在线且无错误；iPad 的离线状态与 macOS `unavailable` 一致 |

### 重测触发条件

- 后续修改设备发现在线语义、Registry 候选过滤、root Helper 参数、匿名标识边界或 Agent 同步周期时，必须重新执行本节定向测试、完整 Agent pytest 和真实 Mac 运行态验证。
- 网络恢复后必须重新执行 `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d`，确认五个默认服务使用同一提交镜像并全部健康。

### 已知问题

- 完整 Compose 重建尚未通过，原因是外部 Debian 包索引连接失败/404；当前仅 Caddy 使用本次功能提交镜像，其他默认服务保持各自此前健康版本。
- 本机还有第三方 AppleAuto/WeComRegistry 进程同样配置了 Registry 端口 `42314`；检查时该端口没有活动 TCP 监听，但未来两套 Registry 同时建立隧道时可能冲突。本次未停止或修改第三方服务，也未更改 BaseAI 配置。
- 当前 iPad 被 macOS 明确报告为 `unavailable`，因此仍显示离线；需要唤醒设备、开启无线连接或接入 USB 后再验证其实际在线状态。
- 共享工作区存在其他任务的未提交后端和前端变更；本次提交、干净构建和 Caddy 发布未包含这些文件。一次从共享工作区触发的依赖构建在发现范围风险后立即中止，未替换相关容器。

### 下次测试建议

- 网络恢复后完成统一 Compose 重建，并核对 Backend、Frontend、Python Worker、Document Parser、Caddy 的镜像 revision 全部一致。
- 唤醒或连接 iPad 后连续观察两个同步周期，确认其从 `unavailable/OFFLINE` 转为 `connected/AVAILABLE`。
- 在启用 WDA 前为 BaseAI Registry 分配未被 AppleAuto 使用的端口，或停止不再使用的第三方 Registry，再验证 `tunnelCount` 和 WDA 会话建立。

## 设备 Agent 全栈管理同步验收（2026-09-10）

### Git 基准点

Commit: 5dd20f394a61326c2c7e01146baaeb8958007c47
- 提交信息: Complete device agent full-stack management sync
- 测试日期: 2026-09-10
- 分支: master
- 未执行 git push。

### 变更范围

- 将源项目设备 Agent 管理能力完整同步到本项目的通用设备 Agent 体系，排除企业微信账号、好友任务、`TASK_EXECUTION` 等业务逻辑；设备 Agent、配对码、配置、设备池、命令、Registry 与操作速度全部持久化到 MySQL。
- 新增 MySQL V32 迁移：`automation_device_agent_wda_config` 增加 `SLOW/STANDARD/FAST` 固定操作速度档位及派生无线/USB 页面采样参数和一致性约束。
- 后端新增操作速度查询/保存接口（保存后审计并下发 `UPDATE_CONFIG`，档位无变化时跳过写库与下发）；配对码列表支持分页、`includeInactive` 和按 Agent 筛选；Agent 列表支持配对状态筛选；设备名允许清空。
- Mac Agent 新增 Apple 开发签名身份探测（含团队 ID 与到期日）、结构化设备检测结果、指定版本升级回退与本地版本上报、操作速度档位校验、额外 CA 信任（保留系统根证书）、`set-server` 本机改址命令；安装器支持 `--ca-file`、`--insecure`、`--npm-registry`，下载强制 HTTPS。
- 前端同步完整管理页交互（配对码生命周期、设备名/回连地址维护、升级回退、配置重发、设备池、WDA 配置、Registry、操作速度、撤销删除），新增通用配置指南与七步接入向导，注册路由与导航，接入 `operations:device-agent:*` 细粒度权限；`config.js` 移植部署前缀工具 `resolvePlatformBaseUrl`/`withBasePath`。
- 代码审查后修复：WDA 配置保存保留既有 `launchMode/wdaUrl`；回连地址清空时不再下发 Agent 无法执行的改址命令，且下发值改为实际落库地址；改址弹窗提供自签名开关；本机改址命令追加 launchd 重启；命令轮询统一把 `CANCELLED` 视为终态并在页面卸载时终止；等待重启轮询增加在途保护；签名探测限制候选数量；SETUP_WDA 增加签名预检并回稳定错误码 `SIGNING_IDENTITY_MISSING`。
- 回滚方式：`git revert 5dd20f394a61326c2c7e01146baaeb8958007c47` 后重新执行 `docker compose up --build -d`。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 操作速度档位持久化到 MySQL 并下发 | Backend H2(MySQL 模式) Service 测试保存 FAST 档位 | 档位与派生采样参数写入 `automation_device_agent_wda_config`，并创建 `UPDATE_CONFIG` 命令 | 正常 |
| 非法速度档位被拒绝 | Backend Service 测试输入 TURBO | 抛出业务异常，不落库 | 异常 |
| 相同档位重复保存不重复下发 | Backend Service 测试连续保存两次 FAST | 仅创建一次命令，无重复审计 | 边界、回归 |
| 配对码分页与状态筛选 | Backend Service 测试创建两条配对并撤销一条 | 活跃查询排除撤销记录，分页与 Agent 筛选计数正确，非法状态值拒绝 | 正常、边界、异常 |
| 协议与迁移不引入企业微信业务 | Backend 契约测试读取命令白名单与 V27/V31/V32 迁移 | 白名单仅含通用命令；迁移不含 wecom/账号/好友内容 | 安全、兼容、回归 |
| Agent 速度档位映射与防篡改 | Agent pytest 输入 FAST 与被篡改的派生参数 | 输入频率映射 240；派生值与档位不一致时拒绝 | 正常、安全 |
| 签名探测过滤与失败语义 | Agent pytest 模拟 security/openssl 输出 | 仅返回开发证书并提取团队 ID；解析失败显式报 `SIGNING_DETECT_FAILED` | 正常、异常 |
| 指定版本回退不访问网络 | Agent pytest 固定已保留版本并禁止下载 | 原子切换符号链接并重装，不请求远端清单 | 正常、回归 |
| 配置 CA 文件校验 | Agent pytest 写入不存在的 CA 路径 | 加载失败返回 `AGENT_CONFIG_INVALID` | 边界、安全 |
| 安装命令构建与自签名探测 | Frontend Node 测试构造含引号配对码、自签名、npm 镜像的命令并模拟 fetch | 参数安全转义，`--ca-file`/`--insecure`/`--npm-registry`/`--force-pair` 齐备，探测失败安全回退 | 正常、边界、异常 |
| 管理页入口与权限 | Frontend Node 契约测试读取管理页与路由 | 指南/向导路由已注册，权限 helper 存在，分页与速度交互齐备，无企业微信残留 | 权限、兼容、回归 |
| 运行环境应用功能提交 | 功能提交哈希重建默认服务 | backend/caddy/document-parser/frontend/python-worker 全部健康 | 集成、回归 |

### 测试执行结果

- 唯一可计数自动化测试共 1,131 个，通过 1,131 个，通过率 100%；失败 0；错误 0；跳过 0。
- Backend：765/765，Maven 3.9.9 / Temurin 17 完整测试通过；设备 Agent 定向模块 24/24 通过（含操作速度、分页筛选、V32 迁移契约）。
- Frontend：339/339 覆盖率测试与 1/1 E2E 通过；ESLint、Vue 类型检查和生产构建通过。
- Mac Agent：26/26 pytest 通过（含签名探测、速度档位、版本回退、CA 校验、SETUP_WDA 签名预检）。

### 关键模块测试

- DeviceAgentAutomationConfigService：5/5 通过（速度持久化、派生值、非法档位、无变化跳过、回环地址限制）。
- DeviceAgentRegistrationService：4/4 通过（配对一次领取、能力白名单、分页筛选、状态校验）。
- DeviceAgentScopeContractTest：2/2 通过（命令白名单与 V27/V31/V32 迁移范围）。
- device-agent pytest：10 个测试模块全部通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 定向测试 | Maven 容器执行 `mvn test -B -Dtest='DeviceAgent*Test'` | 24/24 通过 |
| Backend 完整测试 | Maven 3.9.9 / Temurin 17 容器执行 `mvn test -B` | 765/765，BUILD SUCCESS |
| Frontend 质量门 | `cd frontend && npm run lint && npm run typecheck` | 通过 |
| Frontend 覆盖率测试 | `cd frontend && npm run test:coverage` | 339/339 通过 |
| Frontend E2E | `cd frontend && npm run test:e2e` | 1/1 通过（含生产构建） |
| Mac Agent 测试 | 临时 venv 执行 `python3.12 -m pytest` | 26/26 通过 |
| 默认服务统一重建 | `APP_IMAGE_REVISION=<commit> docker compose up --build -d` | backend/caddy/document-parser/frontend/python-worker 均运行且健康 |

### 重测触发条件

- 后续修改操作速度模型/派生参数、配对码分页与筛选、WDA 配置契约、Agent 签名探测、升级回退、安装器参数或管理页交互时，必须重新执行本节定向用例和完整测试。

### 已知问题

- 操作速度档位中的动作间隔、滑动间隔在通用 Agent 中目前仅作为固定档位契约保存与校验；通用 Agent 无业务任务执行能力，实际生效的运行时参数为输入频率与无线/USB 页面采样，相关 UI 文案描述的是档位契约语义。
- 前端 `test/device-agent.test.mjs` 中三条断言按新实现等价更新：设备检测由 `/devices/detect` 端点承担、端口范围由 `el-input-number` min/max 承担、隐私承诺文案迁移到指南文案，测试意图未弱化。
- 本机无 Maven/pytest 全局安装，测试分别通过 Maven 官方容器和 `/tmp/base-ai-device-agent-test` 临时虚拟环境执行；两个临时环境已在收尾清理。
- 未连接真实 iOS 设备与真实 Mac Agent，签名探测、WDA 构建、设备检测和升级回退仅由模拟命令测试覆盖。

### 下次测试建议

- 在真实 Mac 上执行一键安装与接入向导，验证自签名证书链、npm 镜像、SETUP_WDA 签名预检和版本回退的端到端行为。
- 使用无 `operations:device-agent:execute` 权限的账号验证管理页、指南与向导的按钮隐藏及后端拒绝行为。

## 服务器手工维护与实时资源监控验收（2026-09-10）

### Git 基准点

Commit: 10fa8374d387760a142382093b6df3c8ee03ed80
- 提交信息: Add real-time server resource monitoring
- 测试日期: 2026-09-10
- 分支: master
- 未执行 git push。

### 变更范围

- 服务器管理页提供明确的“新增服务器”入口，新记录默认使用 SSH 模式并由用户手工维护主机、端口、账号、认证方式、Host Key 指纹和凭据；既有 LOCAL 模式继续兼容。
- 新增只读 `GET /api/servers/{id}/monitor` 接口，复用 `operations:server:test` 权限、资源所有权和启用状态校验，通过隔离 Deployment Agent 实时采集，不保存监控历史。
- 实时快照包含 CPU 核数、使用率、1/5/15 分钟负载、内存、磁盘、运行时长，以及最多 200 个 Docker 容器的名称、镜像、运行状态、健康状态和状态描述。
- Docker 不可用时返回 `PARTIAL` 并保留主机指标；Agent 故障、越界指标或异常响应统一降级为安全错误键，不向页面透传 SSH 内部失败详情。
- 未新增依赖、配置、数据库字段或迁移。回滚可执行 `git revert 10fa8374d387760a142382093b6df3c8ee03ed80`，随后使用相同 Compose 命令重建服务。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 页面可手工新增 SSH 服务器 | Frontend 契约测试读取服务器页面，输入新增表单默认值与 SSH/LOCAL 选项 | 新增按钮受 create 权限控制；默认 SSH，手工字段完整，LOCAL 保持可选 | 正常、权限、兼容 |
| SSH 表单拒绝缺失必填项 | Frontend 页面逻辑测试名称、主机、端口、账号、Host Key 及新凭据分支；Backend 既有校验套件覆盖非法配置 | 缺失字段不提交；新增或切换认证方式时必须填写对应凭据 | 边界、异常、安全 |
| 弹窗实时展示基础资源 | Backend H2 + HTTP Agent 集成测试返回 CPU、负载、内存、磁盘和运行时长；Frontend 契约测试检查弹窗与刷新请求 | 打开或刷新时请求当前快照，指标结构化展示且不建立历史轮询或持久化 | 正常、兼容 |
| 展示容器状态并兼容 Docker 不可用 | Go 解析测试输入运行/退出/健康容器及 Docker 错误；Backend `PARTIAL` 集成测试；Frontend 空集合和健康标签测试 | 最多返回 200 个容器；Docker 失败时主机指标仍可见并展示受限提示 | 正常、边界、异常 |
| 权限、所有权和停用状态受控 | Controller 权限契约测试；Service 使用其他所有者、停用服务器和不存在编号 | 仅 server:test 权限可访问；越权、停用和不存在均在调用 Agent 前拒绝 | 权限、安全、异常 |
| Agent 输入输出和 SSH 执行安全 | Go 测试覆盖 Bearer 鉴权、未知/多余 JSON、SSH 注入输入、Host Key 与凭据；Backend 覆盖越界指标及失败详情 | 仅执行固定只读脚本，SSH 继续固定指纹校验；非法响应不透传敏感细节 | 安全、边界、异常 |
| 运行环境应用功能提交 | 使用功能提交完整哈希构建并启动默认服务，随后检查镜像、健康状态与网关健康接口 | 五个默认服务使用同一功能版本并健康；网关健康请求成功 | 集成、回归 |

### 测试执行结果

- 唯一可计数自动化测试共 1,098 个，通过 1,098 个，通过率 100%；失败 0；错误 0；跳过 0。
- Backend：751/751，Maven 3.9.9 / Temurin 17 完整测试通过；服务器管理定向模块 17/17 通过，其中监控服务 5/5、接口契约 1/1、既有校验 11/11。
- Frontend：331/331 覆盖率测试与 1/1 E2E 通过；ESLint、Vue 类型检查和生产构建通过。工具覆盖率为行 98.09%、分支 80.58%、函数 96.21%。
- Deployment Agent：15/15 Go 测试通过；包含参数边界、鉴权、结构化解析、Docker 部分失败和真实 Linux 基础指标采集。
- Agent 镜像构建成功；临时运行态探针返回 `PARTIAL`、8 核 CPU、有效内存/磁盘指标和空容器列表，符合未挂载 Docker Socket 的预期降级行为。

### 关键模块测试

- 服务器监控 Controller 与 Service：6/6 通过。
- 服务器配置兼容与安全校验：11/11 通过。
- Deployment Agent 全部测试：15/15 通过。
- 服务器页面新增契约：3/3 通过；完整前端质量门全部通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Deployment Agent 完整测试 | 锁定 Go 1.26.6 Alpine 容器执行 `gofmt -w main.go main_test.go && go test ./...` | 15/15 通过；真实 Linux 采集用例通过 |
| Backend 定向测试 | Maven 容器执行 `mvn test -B -Dtest='ServerManagementValidationTest,ServerManagementControllerTest,ServerManagementMonitorTest'` | 初版 16/16 通过；安全失败详情用例加入后由完整套件验证，模块最终 17/17 通过 |
| Backend 完整测试 | 临时干净工作树使用 Maven 3.9.9 / Temurin 17 容器执行 `mvn test -B` | 751/751，BUILD SUCCESS；临时工作树和补丁均已清理 |
| Frontend 完整质量门 | `cd frontend && npm test` | ESLint、类型检查、331/331 覆盖率测试、生产构建和 1/1 E2E 全部通过 |
| Agent 镜像验证 | `docker compose --profile deployment build deployment-agent` | 镜像构建成功，镜像内 Go 测试通过 |
| 默认服务统一重建 | 功能提交干净工作树执行 `APP_IMAGE_REVISION=<feature-commit> docker compose up --build -d`，正式工作区用同版本镜像重建绑定 | backend/caddy/document-parser/frontend/python-worker 均运行且健康，网关健康接口成功 |
| Agent 运行态探针 | 临时容器设置测试令牌，调用 `/monitor` 的 LOCAL 模式且不挂载 Docker Socket | 返回有效主机指标和预期 `PARTIAL`；临时容器已停止并删除 |

### 重测触发条件

- 后续修改服务器配置模型、Controller 权限、Service 所有权或 Agent 响应校验、Deployment Agent SSH/采集脚本、服务器管理弹窗或容器状态映射时，必须重新执行本节定向用例和完整测试。

### 已知问题

- 当前本机 `.env` 未配置至少 24 位 `DEPLOYMENT_AGENT_INTERNAL_TOKEN`，因此可选 deployment profile 未常驻启动；使用监控前需按 README 生成内部令牌、配置 Docker Socket 并执行 `docker compose --profile deployment up --build -d`。
- 首次在共享工作区执行统一重建时，被另一组尚未完成的 Device Agent 业务代码阻断编译；本功能随后在不包含该未提交改动的干净工作树中完成 751/751 测试与镜像构建，未修改或提交那些并发文件。
- 未连接真实 SSH 主机，避免使用或新增外部凭据；SSH 参数、Host Key 固定校验、凭据分支与命令注入防护已由自动化测试覆盖。
- Frontend 构建继续输出既有运行配置脚本、第三方 PURE 注释和大分块警告，构建成功且与本次功能无关。

### 下次测试建议

- 配置 rootless Docker Socket 与随机内部令牌后启用 deployment profile，在页面新增一台专用 SSH 测试机并实际刷新资源监控弹窗。
- 分别使用无 server:test 权限、非资源所有者和停用服务器验证页面入口及接口拒绝行为，再使用 Docker 健康/不健康/重启中容器核对标签展示。

## 数据源维护操作栏显示修复验收（2026-09-10）

### Git 基准点

Commit: f4535252aa4b73a7dbd3ccaf35fb276b80432f7f
- 提交信息: Show data source maintenance actions
- 测试日期: 2026-09-10
- 分支: master
- 未执行 git push。

### 变更范围

- 修正公共 `.section-head` 布局规则：只隐藏第一个重复标题容器，不再隐藏其余操作容器。
- 页头可识别任意后代层级的 `.el-button`，数据源页面嵌套在 `.head-actions` 中的“新增数据源”“全部检测”等入口恢复显示。
- 数据源卡片内的编辑、删除入口和既有权限逻辑不变；拥有查看权限自动具备新增、编辑、删除能力，测试权限继续独立授权。
- 同类嵌套操作栏页面（服务器管理、设备代理）同步恢复；未新增依赖、配置或数据迁移。回滚可执行 `git revert f4535252aa4b73a7dbd3ccaf35fb276b80432f7f` 后重新构建服务。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 数据源维护操作栏可见 | Frontend 契约测试读取数据源页嵌套 `.head-actions` 和公共 CSS | 页头存在新增权限按钮，公共样式不再隐藏整个操作容器 | 正常、回归 |
| 重复页面标题保持隐藏 | Frontend 契约测试检查 `.section-head > div:first-child` | 只隐藏第一个标题容器，避免内容区重复展示页面名称 | 兼容、回归 |
| 无操作按钮的页头保持隐藏 | Frontend 契约测试检查 `.section-head:not(:has(.el-button))` | 无按钮时继续隐藏空页头；嵌套按钮可被识别 | 边界、兼容 |
| 权限边界保持不变 | Frontend 完整权限与角色权限树测试 | list 自动包含新增、编辑、删除，test 和其他资源权限仍不自动授予 | 权限、安全、回归 |
| 修复应用至运行环境 | Compose 重建后检查五个容器、就绪接口与线上压缩 CSS | 五个容器健康，接口 HTTP 200，线上资源包含修复后的两个选择器 | 集成、回归 |

### 测试执行结果

- Frontend 唯一可计数测试共 328 个：覆盖率测试 327/327、E2E 1/1，通过率 100%；失败 0；错误 0；跳过 0。
- Frontend 工具覆盖率：行 98.09%、分支 80.58%、函数 96.21%；ESLint、Vue 类型检查和生产构建全部通过。
- 失败复现：修复前数据源定向测试 2/3 通过，新增的嵌套操作栏用例按预期失败；修复后数据源与布局定向测试 17/17 通过。
- Docker 构建过程中 Maven 基于当时共享工作区执行 750/750 测试并通过；其中包含另一项尚未提交的服务器监控改动，不计入本次前端验收总数。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Frontend 失败复现 | `cd frontend && node --test test/data-sources.test.mjs` | 修复前 2/3 通过，新增回归用例稳定失败 |
| Frontend 定向回归 | `cd frontend && node --test test/data-sources.test.mjs test/layout-alignment.test.mjs` | 17/17 通过 |
| Frontend 完整质量门 | `cd frontend && npm test` | ESLint、类型检查、327/327 覆盖率测试、生产构建和 1/1 E2E 全部通过 |
| 默认服务统一重建 | `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d` | backend/caddy/document-parser/frontend/python-worker 全部重建并健康；构建内 Maven 750/750 通过 |
| 运行态验证 | 检查五个容器镜像标签、就绪接口及线上 CSS 资源 | 镜像版本均为功能提交完整哈希；接口 HTTP 200、状态 `UP`；线上 CSS 包含修复选择器 |

### 重测触发条件

- 后续修改公共 `.section-head` 结构、数据源页头操作容器、数据源权限推导或前端构建流程时，必须重新执行本节定向用例和完整前端套件。

### 已知问题

- 未在真实数据源上执行新增、编辑或删除，避免验收过程产生业务数据；入口结构、权限推导和运行态资源均已由自动化测试与线上资源检查覆盖。
- Frontend 构建继续输出既有运行配置脚本、第三方 PURE 注释和大分块警告，构建成功且与本次修复无关。
- Docker 重建使用共享工作区，运行中的 Backend 同时包含另一项尚未提交的服务器监控开发内容；本次提交未修改或提交这些文件。

### 下次测试建议

- 使用仅授权 `operations:data-source:list` 的普通账号刷新数据源管理页，确认右上角出现“新增数据源”，并验证卡片上的编辑、删除入口。
- 如浏览器仍保留修复前页面，执行强制刷新以加载新的哈希静态资源，再验证维护操作栏。

## 数据源查看权限包含维护能力验收（2026-09-10）

### Git 基准点

Commit: 3a048c940e32cd2b1642afe82df66fad2cf10450
- 提交信息: Grant data source maintenance with view access
- 测试日期: 2026-09-10
- 分支: master
- 未执行 git push。

### 变更范围

- 拥有 `operations:data-source:list` 的用户自动拥有数据源新增、编辑和删除能力；前端按钮展示与后端 `RequiredPermission` 校验使用相同推导规则，现有角色无需数据迁移即可生效。
- 角色权限树在勾选或回显数据源查看权限时自动补齐新增、编辑和删除按钮权限；后端保存角色时再次归一化，防止绕过前端直接提交不完整权限集合。
- `operations:data-source:test` 继续独立授权；数据同步及其他资源权限不受影响，数据源更新和删除仍受所有者隔离与引用保护约束。
- 未新增依赖、配置或数据库迁移；回滚可执行 `git revert 3a048c940e32cd2b1642afe82df66fad2cf10450` 后按相同 Compose 命令重建。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 查看权限自动拥有新增、编辑、删除 | Backend `AuthUserPermissionTest` 参数化输入三个维护权限；Frontend `permissions.test.mjs` 输入仅含 list 的普通用户 | 三个维护权限均判定为 true，页面已有按钮条件随之生效 | 正常、权限、兼容 |
| 测试及其他资源权限保持独立 | 前后端权限单测输入 `operations:data-source:test` 与 `operations:data-sync:create` | 均判定为 false，不扩大确认范围外权限 | 边界、权限、安全 |
| 角色配置自动归一化 | Backend `PlatformAdminServiceTest` 仅提交数据源 list；Frontend `role-permissions.test.mjs` 执行勾选、历史回显和单独取消维护按钮 | 保存与界面均固定包含 list/create/update/delete，不包含 test | 正常、边界、回归 |
| 既有管理员、精确权限和 manage 兼容行为不变 | Frontend 权限单测覆盖 ADMIN、精确权限、历史 manage 和未登录输入 | 既有授权结果保持不变，未登录请求权限返回 false | 兼容、异常、回归 |
| 数据源对象级安全边界不变 | Backend `WorkflowConnectionServiceTest` 使用其他用户更新非本人数据源 | 继续拒绝越权维护；被工作流或同步计划引用的数据源删除保护保持通过 | 权限、安全、回归 |
| 运行环境应用新代码 | 使用功能提交完整哈希统一重建五个默认服务并访问就绪端点 | 五个镜像标签一致，服务健康，就绪接口返回 HTTP 200 和 `UP` | 集成、兼容、回归 |

### 测试执行结果

- 唯一可计数测试共 1,067 个，通过 1,067 个，通过率 100%；失败 0；错误 0；跳过 0。
- Backend：745/745，Maven 3.9.9 / Temurin 17 完整测试通过；定向权限与数据源模块 33/33 通过。
- Frontend：321/321 覆盖率测试与 1/1 E2E 通过；ESLint、Vue 类型检查和生产构建通过。
- Frontend 工具覆盖率：行 98.06%、分支 80.42%、函数 96.15%；新增 `permissions.js` 为 100%/100%/100%。
- 失败复现：实现前 Frontend 新增用例 2 个稳定失败；Backend 权限用例 3 个失败，修正测试夹具后角色归一化用例 1 个稳定失败。实现后全部转为通过。

### 关键模块测试

- 身份权限推导：4/4 通过。
- 角色保存与权限委派：17/17 通过。
- 数据源 Controller 权限契约：4/4 通过。
- 数据源所有权、加密配置和删除保护：8/8 通过。
- 前端权限推导、角色权限树及数据源页面定向用例：16/16 通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Frontend 失败复现 | `node --test test/role-permissions.test.mjs` | 实现前 8/10 通过，2 个新增联动用例按预期失败 |
| Backend 失败复现 | Maven 容器执行 `mvn -B -ntp -Dtest='AuthUserPermissionTest,PlatformAdminServiceTest' test` | 测试夹具修正后 17/21 通过，4 个新增权限用例按预期失败 |
| 前后端定向回归 | Frontend 三个定向文件；Backend `AuthUserPermissionTest,PlatformAdminServiceTest,DataSourceControllerTest,WorkflowConnectionServiceTest` | Frontend 16/16、Backend 33/33 通过 |
| Backend 完整测试 | Maven 3.9.9 / Temurin 17 容器执行 `mvn -B -ntp test` | 745/745，BUILD SUCCESS |
| Frontend 完整质量门 | `cd frontend && npm test` | ESLint、类型检查、321/321 覆盖率测试、生产构建和 1/1 E2E 全部通过 |
| 默认服务统一重建 | `APP_IMAGE_REVISION=3a048c940e32cd2b1642afe82df66fad2cf10450 docker compose up --build -d` | backend/caddy/document-parser/frontend/python-worker 全部重建并启动 |
| 运行态验证 | 检查五个容器镜像标签与健康状态；请求 `https://localhost/api/open/health/ready` | 镜像标签均为功能提交完整哈希，就绪接口 HTTP 200、状态 `UP` |

### 重测触发条件

- 后续修改 `AuthUser`、角色保存逻辑、前端权限判定、数据源 Controller 权限或所有权校验时，必须重新执行本节前后端定向用例和完整套件。

### 已知问题

- 未执行真实非管理员浏览器账号的写操作，避免为测试创建或删除生产数据；权限推导、角色持久化和对象级越权均由可执行自动化测试覆盖。
- Frontend 构建仍输出既有运行配置脚本、第三方 PURE 注释和大分块警告，构建成功且与本次权限变更无关。
- 主机未安装 Maven，Backend 测试使用项目锁定的 Maven 3.9.9 / Temurin 17 容器执行。

### 下次测试建议

- 使用仅授权 `operations:data-source:list` 的非管理员账号刷新页面，实际新增、编辑并删除一条无引用的测试数据源，确认按钮和接口行为一致。
- 使用同一账号确认“测试连接”“全部检测”和状态详情仍不可见，除非额外授予 `operations:data-source:test`。

## 导航目录调整验收：邮件管理移入运维管理（2026-09-10）

### Git 基准点

Commit: abb3202cb63024ab921b23be7a2f480e12a22d5f
- 提交信息: Move mail management under operations and reorder top catalogs
- 测试日期: 2026-09-10
- 分支: master
- 未执行 git push。

### 变更范围

- 「邮件管理」目录（`system:mail:catalog`，含邮箱配置、邮件路由）从「系统管理」移入「运维管理」，排序位于服务器管理之后、监控审计之前（sort_order 15）。
- 一级目录顺序调整为：工作台之后依次为系统管理(10)、运维管理(20)、AI 能力(30)、自动化(40)。
- 权限标识保持 `system:mail:*` 不变，不涉及权限编码迁移、前端路由或接口改动。
- 无数据库迁移脚本：`DataInitializer.menu()` 按权限标识幂等更新现有菜单行的父级与排序，应用启动即自动纠正存量数据。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 邮件管理归入运维管理 | Backend 单测 `DataInitializerTest`（Mockito 隔离仓储）：执行 `run()` 捕获菜单保存值 | `system:mail:catalog` 的 parentId 等于 `operations:catalog` 的 id，邮箱配置仍为其子菜单 | 正常、回归 |
| 一级目录顺序为系统管理、运维管理、AI 能力、自动化 | Backend 单测 `DataInitializerTest`：按 sortOrder 排序四个一级目录 | 顺序为 `system → operations → ai → automation` | 正常 |
| 存量菜单数据自动纠正 | 启动后查询远程库 `sys_menu` | `system:mail:catalog` 父级为 `operations:catalog`、sort_order 15；一级排序 10/20/30/40 | 兼容、回归 |

### 测试执行结果

- Backend 完整测试：740/740 通过，通过率 100%；失败 0；错误 0；跳过 0。
- Frontend：本次未改动前端代码，未重跑前端套件（导航为数据库驱动，前端无需变更）。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 定向测试 | Maven 容器执行 `mvn test -B -Dtest=DataInitializerTest` | 17/17 通过 |
| Backend 完整测试 | Maven 3.9.9 / Temurin 17 容器执行 `mvn test -B` | 740/740，BUILD SUCCESS |
| 服务统一重建 | `APP_IMAGE_REVISION=$(git rev-parse HEAD) docker compose up --build -d` | 五个服务（backend/caddy/document-parser/frontend/python-worker）全部构建并健康 |
| 存量数据验证 | 容器内 mysql 客户端只读查询远程库 `sys_menu` | 邮件管理父级与一级排序均符合预期 |

### 已知问题

- 无新增问题。主机未安装 Maven，测试通过官方 Maven 容器执行；`docker compose` 需要 `APP_IMAGE_REVISION` 环境变量，已按 README 设置为当前提交号。

### 下次测试建议

- 使用管理员账号在浏览器中刷新导航，目视确认「邮件管理」出现在「运维管理」且一级顺序正确。
- 使用仅含 `system:mail:*` 授权的自定义角色登录，确认菜单裁剪行为不变。

## 数据源页面改版与健康状态监测验收（2026-09-10）

### Git 基准点

Commit: b9d620f5038bdb3a4d52c7671eeedf7b03564db9
- 提交信息: Redesign data source page with health status monitoring
- 测试日期: 2026-09-10
- 分支: master
- 未执行 git push。

### 变更范围

- 数据源管理页由表格改为按分类分组的卡片布局，新增十二类连接类型的内联 SVG 图标（`DataSourceTypeIcon.vue`，不新增依赖）。
- 卡片展示健康状态圆点（正常 / 异常 / 未检测 / 已停用）、最近检测时间、测试延迟与向量能力标签，并提供“状态详情”抽屉实时探测。
- Flyway V30 为 `workflow_connection` 新增 `last_test_at`、`last_test_ok`、`last_test_latency_ms`、`last_test_info` 四个可空列，留存最近一次连通性检测结果。
- `WorkflowConnectionTester` 在测试时采集轻量只读指标并留存：MySQL/PostgreSQL 版本与活动连接数、Redis 版本 / 内存 / 客户端数、全部类型测试延迟；失败结果同样留存。
- 新增 `GET /api/data-sources/{id}/status` 实时状态接口，复用 `operations:data-source:test` 权限。
- 页面状态检测方式可配置：默认手动点击检测，用户可开启自动检测并选择 30/60/120 秒间隔（保存在浏览器本地）。
- 中英文文案同步补充；自动检测与全部检测仅对具备测试权限的账号可见。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 检测结果与指标留存 | Backend 单测（Mockito 隔离外部连接）：插件连接测试成功 | `recordTestResult(true, latency, info)` 被调用，返回含 `connected` 和 `latencyMs` | 正常 |
| 检测失败同样留存 | Backend 单测：非法插件配置、不可达 JDBC URL | `recordTestResult(false, ...)` 被调用并抛出业务异常 | 异常 |
| 向量能力与检测结果联动 | Backend 单测：H2 JDBC + Mock 向量探测（支持 / 不支持） | `recordVectorCapability` 与 `recordTestResult` 均被调用，不支持时 `connected=false` | 正常、异常、分支 |
| 非向量类型不写向量记录 | Backend 单测：插件连接测试 | `recordVectorCapability` 从未被调用 | 分支、回归 |
| 留存结果在列表视图可见 | Backend H2 集成测试：`recordTestResult` 后查询视图 | `lastTestOk`、`lastTestLatencyMs`、`lastTestInfo` 正确返回 | 正常、兼容 |
| 状态接口权限 | Backend 反射契约测试：`GET /{id}/status` | 使用 `operations:data-source:test`，路径正确 | 权限、安全 |
| 页面展示与交互 | Frontend 覆盖率测试套件与生产构建 | 315/315 通过，构建成功，页面卡片 / 图标 / 状态文案正常 | 正常、兼容、回归 |

### 测试执行结果

- 唯一可计数测试共 1,056 个，通过 1,056 个，通过率 100%；失败 0；错误 0；跳过 0。
- Backend：740/740，Maven 3.9 / Temurin 17 完整测试通过（新增 7 个测试器与留存用例、1 个状态接口契约用例、1 个留存视图用例）。
- Frontend：315/315 覆盖率测试与 1/1 E2E 通过；ESLint、Vue 类型检查和生产构建通过。
- Backend 定向首轮：`WorkflowConnectionTesterTest,WorkflowConnectionServiceTest,DataSourceControllerTest` 18/18 通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 定向测试 | Maven 容器执行 `mvn test -B -Dtest='WorkflowConnectionTesterTest,WorkflowConnectionServiceTest,DataSourceControllerTest'` | 首轮编译错误（Lettuce `info()` 返回 String）修复后 18/18 通过 |
| Backend 完整测试 | Maven 3.9 / Temurin 17 容器执行 `mvn test -B` | 740/740，BUILD SUCCESS |
| Frontend 完整质量门 | `cd frontend && npm test` | ESLint、类型检查、315/315 覆盖率测试、生产构建与 1/1 E2E 全部通过 |
| 默认服务统一重建 | `APP_IMAGE_REVISION=b9d620f... docker compose up --build -d` | 五个服务全部构建并启动健康 |
| 数据库迁移 | 检查 Backend Flyway 启动日志 | V30 成功应用，Schema 升级到 v30 |

### 已知问题

- 重建时暴露历史遗留问题：远程库中 V28 校验和（856266360）是 12:21 用未提交的临时文件版本记录的，早于恢复提交（12:33）；经用户确认后已按 `flyway repair` 等价方式将 `flyway_schema_history` 中 V28 校验和更新为当前仓库文件的 -293149862（仅元数据，未执行迁移 SQL）。今后不得修改任何已应用迁移文件。
- 状态检测为按需探测：MySQL/PostgreSQL/Redis 返回版本与连接 / 内存指标，其余类型返回连通与延迟；Kafka、RabbitMQ、S3 等暂不采集深度指标。
- 自动检测基于浏览器定时器，页面隐藏（`document.hidden`）时暂停轮询，关闭标签页即停止。
- Frontend 构建继续输出既有运行配置脚本、第三方 PURE 注释和大分块警告，构建与测试均通过，本次未修改这些非相关问题。

### 下次测试建议

- 在运行环境中对 MySQL、Redis 数据源实际执行“状态详情”，核对版本、活动连接数和延迟指标与真实实例一致。
- 开启自动检测后观察一段时间，确认远程数据库探测频率符合预期且无权限报错。

## 数据源管理独立页面验收（2026-09-10）

### Git 基准点

Commit: 2888353fac1badbd4f7ee64a129faea9fe4a937d
- 提交信息: Restore applied V28 migration checksum
- 核心功能提交: 2f78119（Add dedicated data source management）
- 测试日期: 2026-09-10
- 分支: master
- 未执行 git push。

### 变更范围

- 新增运维管理下与数据同步平级的“数据源管理”页面（`/data-sources`），支持全部十二类受管连接类型的新增、编辑、删除、启停、连接测试和插件 OAuth 授权。
- 新增 `/api/data-sources` 专用接口和 `operations:data-source:{list,create,update,delete,test}` 独立权限，复用 `workflow_connection` 表、AES-GCM 加密和出站安全校验，不新增业务表。
- 使用 Flyway V29 将存量 `automation:workflow:connection:*` 权限原位迁移到 `operations:data-source:*`，保留菜单 ID 与角色授权，并补齐运维目录祖先。
- 移除“工作流 → 连接配置”维护入口；工作流画布、数据同步和知识库继续按所有者复用同一批数据源。
- 内置 OPS 角色新增数据源维护权限；恢复已应用 V28 迁移文件的原始内容以修复 Flyway 校验和。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 数据源接口使用独立路径和权限 | Backend 反射契约测试检查 `/api/data-sources` 全部方法的权限注解和 HTTP 动词 | list/create/update/delete/test 使用独立权限和正确动词 | 正常、权限、安全 |
| 插件 OAuth 归属数据源权限 | Backend 契约测试检查组件选项、授权和回调端点 | 全部使用 `operations:data-source:*` 权限 | 权限、安全、兼容 |
| 存量权限和角色授权无损迁移 | Backend H2 执行 V29，输入旧连接权限菜单和自定义角色授权 | 权限 KEY 原位更新，页面移动到运维目录，角色授权保留并补齐目录 | 正常、迁移、兼容、回归 |
| 页面与导航正确迁移 | Frontend 契约测试检查路由、导航、按钮权限、双语资源和旧入口移除 | `/data-sources` 平级注册，旧连接维护入口不再存在 | 正常、兼容、回归 |
| 数据同步与知识库继续可用 | 既有 DataSync 和 KnowledgeBase 测试复用同一连接服务 | 同步计划引用和知识库向量连接选择不受影响 | 回归 |
| 运行环境可升级 | Compose 使用提交号统一构建并连接现有 MySQL | V29 已成功应用，默认五个服务最终健康 | 集成、迁移、回归 |

### 测试执行结果

- 唯一可计数测试共 1,048 个，通过 1,048 个，通过率 100%；失败 0；错误 0；跳过 0。
- Backend：732/732，Maven 3.9.9 / Temurin 17 完整测试通过（新增 5 个数据源契约与迁移用例）。
- Frontend：315/315，ESLint、Vue 类型检查、覆盖率测试和生产构建通过；E2E 1/1 通过。
- Frontend 工具覆盖率：行 98.03%、分支 80.19%、函数 96.09%。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 定向测试 | Maven 容器执行 `mvn -Dtest='PermissionKeyMigrationTest,DataInitializerTest,DataSourceControllerTest,DataSourcePermissionMigrationTest,WorkflowModelOptionsControllerTest,ApiKeyEndpointCatalogServiceTest' test` | 首轮发现测试建表缺少自增，修正后通过 |
| Backend 完整测试 | Maven 3.9.9 / Temurin 17 容器执行 `mvn test -B -ntp` | 732/732，BUILD SUCCESS |
| Frontend 完整质量门 | `cd frontend && npm test` | ESLint、类型检查、315/315 覆盖率测试、生产构建和 1/1 E2E 全部通过 |
| 默认服务统一重建 | `APP_IMAGE_REVISION=2888353fac1badbd4f7ee64a129faea9fe4a937d docker compose up --build -d` | 五个服务全部构建并启动健康 |
| 数据库迁移 | 检查 Backend Flyway 启动日志 | V29 成功应用，Schema 升级到 v29 |
| 运行态探测 | 前端首页和 `/api/open/health/ready` | 前端可访问，后端就绪 200 |

### 已知问题

- 已应用的 V28 迁移文件曾被并发修改格式导致 Flyway 校验和不匹配、后端无法启动；已恢复为已应用的原始内容（提交 2888353），对应测试加固保留在提交 11c7c35。今后不得修改任何已应用迁移文件。
- 首次后端定向测试失败原因为测试自身 H2 建表缺少 `AUTO_INCREMENT`，与生产 Schema 不一致；已按 V1 真实结构修正，非业务缺陷。
- 插件组件选项接口从 `automation:workflow:node:list` 改为 `operations:data-source:list`，需要插件配置权限的账号需同步授予数据源查看权限。
- Frontend 构建继续输出既有运行配置脚本、第三方 PURE 注释和大分块警告，构建与测试均通过，本次未修改这些非相关问题。

### 下次测试建议

- 使用具备数据源权限的非管理员账号在运行环境中实际新增、测试、编辑并删除一个 MySQL 数据源，再验证数据同步计划选择和删除保护（`workflow.connectionInUse`）。
- 验证拥有旧 `automation:workflow:connection:*` 授权的自定义角色在升级后可以继续访问数据源页面。

## 管理权限域重构验收（2026-09-10）

### Git 基准点

Commit: 05ff1d5659279bc4e918d7f10817f8e68af4df19
- 提交信息: Reorganize management permission domains
- 测试日期: 2026-09-10
- 分支: master
- 未执行 git push。

### 变更范围

- 将模型、工作流、运维和邮件权限迁入明确业务域，并同步后端接口、前端路由、按钮和导航权限。
- 使用 Flyway V28 原位迁移菜单权限 KEY，保持菜单 ID 和既有角色资源授权不变，仅补齐新目录祖先。
- 重组 AI、自动化、运维和系统管理导航，新增内置 OPS 角色的运维权限种子。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 存量权限原位迁移 | Backend H2 执行 V28，输入旧权限菜单和自定义角色授权，并重复执行迁移 | 权限 KEY 全部更新，菜单 ID 和资源授权保持，目录祖先补齐，重复执行无重复数据 | 正常、迁移、兼容、回归 |
| 前后端权限保持一致 | Backend Controller 契约测试与 Frontend 路由、导航、按钮契约测试 | 新权限 KEY 在菜单、接口、路由和按钮中一致，旧 KEY 不再作为管理入口 | 正常、权限、兼容 |
| 默认角色授权正确 | DataInitializer 测试 ADMIN、OPS 和自定义角色 | ADMIN 获得完整权限，OPS 仅获得数据同步和服务器权限，自定义授权不扩权 | 权限、安全、回归 |
| 运行环境可升级 | Compose 使用提交号统一构建并连接现有 MySQL | V28 已成功应用，默认五个服务最终健康 | 集成、迁移、回归 |

### 测试执行结果

- 唯一可计数测试共 1,043 个，通过 1,043 个，通过率 100%；失败 0；错误 0；跳过 0。
- Backend：727/727，Maven 3.9.9 / Temurin 17 完整测试通过。
- Frontend：315/315，ESLint、Vue 类型检查、覆盖率测试和生产构建通过；E2E 1/1 通过。
- Frontend 工具覆盖率：行 98.03%、分支 80.19%、函数 96.09%。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整测试 | Maven 3.9.9 / Temurin 17 容器执行 `mvn test -B -ntp` | 727/727，BUILD SUCCESS |
| Frontend 完整质量门 | `cd frontend && npm test` | ESLint、类型检查、315/315 覆盖率测试、生产构建和 1/1 E2E 全部通过 |
| 默认服务统一重建 | `APP_IMAGE_REVISION=05ff1d5659279bc4e918d7f10817f8e68af4df19 docker compose up --build -d` | Backend、Frontend、Python Worker、Document Parser、Caddy 全部构建并启动健康 |
| 数据库迁移 | 检查 Backend Flyway 启动日志 | V28 成功应用，Schema 升级到 v28 |
| 静态检查 | `git diff --check`、`docker compose config --quiet`（补充必填修订变量后） | 空白检查通过，Compose 配置有效 |

### 已知问题

- 宿主机未安装 Maven，后端测试使用项目固定版本的 Maven 容器执行。
- 首次未设置 `APP_IMAGE_REVISION` 的 Compose 配置检查按预期被必填变量校验拒绝；补充当前提交号后重建成功。
- Backend 历史日志中保留一次 V28 半完成状态校验失败记录；随后数据库迁移记录已修复，V28 成功应用且当前服务健康。
- Frontend 构建继续输出既有运行配置脚本、第三方 PURE 注释和大分块警告，本次构建与测试不受影响。

### 下次测试建议

- 新增或调整任一业务域权限时，继续同时覆盖菜单种子、迁移、Controller、前端路由和按钮。
- 在生产升级前备份 `sys_menu`、`sys_role_menu` 和 Flyway 历史表，并在副本环境复核 V28。

## 通用只读 iOS 设备 Agent 验收（2026-09-10）

### Git 基准点

Commit: cf157791736538697b1000589673ee2621d2edd9
- 提交信息: Keep device agent protocol responses unwrapped
- 核心功能提交: e8f52114e819aa46f1f7fdf848f3233d97cc1530（Add read-only iOS device agent management）
- 自升级修复提交: 9229d238c01cfe975604d7d80db5ffef69334283（Fix device agent self-upgrade）
- 上一完整测试基准点: 3370c3fa283094bc629091414eae9d29eb63f101
- 基准差异检查: `git diff 3370c3fa283094bc629091414eae9d29eb63f101 cf157791736538697b1000589673ee2621d2edd9 -- backend/src/main/java/` 包含设备 Agent 配对、认证、命令、设备池、管理接口、配置、权限菜单和机器协议响应业务代码，已触发 Backend 完整测试。
- 测试日期: 2026-09-10
- 分支: master
- 未执行 git push。

### 变更范围

- 新增通用只读 iOS 设备 Agent，不再以企业微信或 WDA 定义 Agent；功能明确不包含好友任务、账号绑定、业务任务执行、设备自动化会话和设备接管。
- 新增 Flyway V27 MySQL 系统表，保存 Agent 注册、一次性配对、健康状态、白名单命令、匿名设备清单和审计记录；每 Agent HMAC Secret 使用平台 AES-GCM 密钥加密。
- Agent 请求使用正文 SHA-256、时间戳、Nonce 和独立 HMAC 签名；Redis `SET NX` 防重放，撤销 Agent 后 Secret、未完成命令和未使用配对码立即失效。
- `/api/agent/**` 机器协议保持原始 JSON 响应，不进入面向管理端业务 API 的统一响应包装，确保 Python Agent 可直接解析配对、配置和命令结果。
- Mac Agent 固定使用 Python 3.12 标准库，只调用 `devicectl list devices` 和环境版本查询；原始 UDID 仅在单次进程内存中参与 Agent 范围 SHA-256 计算，不落盘也不上传。
- Caddy 构建并分发 Apple Silicon/Intel 双架构自包含 Python 3.12 运行时、安装脚本和校验和保护的 Agent 包；自升级先重装已校验版本，再切换链接并由 LaunchAgent 重启。
- 前端新增“设备 Agent 管理”权限页面，支持配对、只读设备清单、健康/诊断、升级、默认实例、撤销和删除，并持续提示只读边界。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| Agent 数据属于 MySQL 系统功能 | Backend Schema 契约检查 V27；Compose 连接现有 MySQL 启动 Backend | 仅创建六类通用 Agent 表；Flyway 实际应用 V27 后再次校验 27 个迁移成功 | 正常、迁移、兼容、回归 |
| 配对和通信凭据安全 | H2 Service 测试输入一次性配对码、重复领取、非法能力；Filter 测试输入有效 HMAC 和重复 Nonce；响应契约测试输入 Agent 协议路径 | Secret 独立生成且仅加密落库；配对码不可复用；正文可重复读取；重放返回 401；机器协议响应不被二次包装 | 正常、异常、权限、安全、兼容 |
| Agent 不接管正在被其他程序控制的设备 | Java/Python 精确能力白名单测试和前端范围契约检查全部命令与端点 | 仅允许诊断、设备列表发现、健康、配置、改址和升级；不存在设备会话、账号、好友或业务任务执行能力 | 边界、权限、安全、回归 |
| 设备清单匿名且支持完整快照 | H2 设备服务输入多设备、空快照、重复摘要；Python 模拟 devicectl 返回 USB/无线重复项和原始 UDID | 在线设备去重保存，缺失设备转离线；报告不含原始 UDID，非 iOS 设备被忽略 | 正常、边界、异常、隐私 |
| 管理页面受权限保护 | Frontend 契约检查路由、导航、只读动作和旧自动化端点；运行态未登录访问管理接口 | 路由绑定 `automation:device-agent:list`；页面动作保持通用只读；未登录返回 401 | 正常、权限、安全、兼容 |
| 安装与升级可验证和回滚 | Python 3.12 测试配置权限、签名、诊断、升级成功/失败；Caddy 包检查双架构运行时、包 SHA-256 和升级源码 | 配置权限为 0600；升级失败保留旧进程，成功先上报再退出；分发包校验和及双架构运行时完整 | 正常、异常、供应链、恢复 |

### 测试执行结果

- 可计数测试用例共 1,051 个，通过 1,051 个，通过率 100%；失败 0；错误 0；跳过 0。
- Backend：725/725，Maven 3.9.9 / Temurin 17 完整测试套件通过；设备 Agent 定向测试 10/10 和 Agent 原始响应契约测试均包含在完整套件中。
- Frontend：316/316；ESLint、Vue 类型检查、315 个覆盖率测试、生产构建和 1 个 E2E 全部通过。工具函数覆盖率为行 98.02%、分支 80.19%、函数 96.09%。
- Device Agent：Python 3.12 执行 10/10 pytest 通过；安装包构建、Shell 语法和 LaunchAgent Plist 校验通过。
- Compose 配置、默认五服务统一重建、运行态健康、权限入口、镜像修订、Agent 分发包和 MySQL Flyway V27 均通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 设备 Agent 定向测试 | Maven 3.9.9 / Temurin 17 容器执行 `mvn -B -ntp -Dtest='DeviceAgent*Test' test` | 10/10，BUILD SUCCESS |
| Backend 完整套件 | Maven 3.9.9 / Temurin 17 容器执行 `mvn test -B -ntp` | 725/725，BUILD SUCCESS |
| Device Agent | Python 3.12 临时虚拟环境安装 `requirements-dev.in` 后执行 pytest，并关闭字节码与 pytest 缓存 | 10/10，通过；临时环境和仓库测试元数据均已清理 |
| Frontend 完整质量门 | `cd frontend && npm test`，并单独复核覆盖率汇总 | ESLint、类型检查、315/315 覆盖率测试、生产构建和 1/1 E2E 全部通过 |
| 静态配置 | `docker compose config --quiet`、`sh -n`、`plutil -lint`、`git diff --check` | Compose、两个安装脚本、LaunchAgent Plist 和空白检查均通过 |
| 默认服务统一重建 | 注入 `APP_IMAGE_REVISION=cf157791736538697b1000589673ee2621d2edd9` 后执行 `docker compose up --build -d` | Backend、Frontend、Python Worker、Document Parser、Caddy 均使用最终提交镜像构建并启动，最终全部 healthy |
| 数据库迁移与入口 | 检查 Backend Flyway 日志并探测 HTTPS `/api/open/health`、`/api/open/health/ready`、管理与 Agent 协议入口 | 27 个迁移校验成功且 Schema 为最新；health/readiness 均为 200，未登录管理和未知 Agent 均为 401 |
| Agent 分发包 | Caddy 容器检查双架构运行时、包内文件、SHA-256、自升级重装和退出逻辑 | Python 3.12.8 双架构运行时完整；包校验和、自升级源码和五个镜像修订均通过 |

### 测试过程问题与处理

- 宿主 Python 3.12 未安装 pytest；使用 `/tmp` 下临时 Python 3.12 虚拟环境安装开发依赖，测试结束由退出钩子删除，没有遗留调试目录。
- 首次 Agent 测试安装发现 setuptools 把 `launchd` 误判为顶层包；显式限定只打包 `device_agent` 后，安装和 10 个 Agent 测试全部通过。
- 首次前端契约断言按完整 `/api` URL 和顶层路由编写，与项目 Axios Base URL、子路由约定不符；调整为既有项目约定后，定向及完整质量门通过，未弱化业务边界断言。
- 首次运行态探测使用 README 示例端口 444，但当前 `.env` 实际配置为标准 443；改用 Compose 显示的实际端口后，全部入口探测通过，没有停止或修改其他项目容器。
- 代码复核发现自升级仅切换版本链接会继续加载虚拟环境中的旧包；改为校验后重装、切换、上报并退出，补充成功与失败分支测试后重新构建，分发包检查通过。
- 没有连接或控制实体 iOS 设备，没有创建自动化会话；未创建或遗留调试文件，未删除、跳过或弱化已有有效测试。

### 已知问题与限制

- 当前实体 iOS 设备正被其他程序控制，本次按用户要求未执行连接、配对、断连恢复或任何实体设备验证；服务端、协议和模拟 devicectl 数据均已验证，但不能据此宣称实体链路已验收。
- 未在另一台目标 Mac 上真实执行一次性配对、Keychain 写入和 LaunchAgent 冷启动；目标 Mac 使用 Caddy 内部 CA 时，必须先安全安装平台公开根证书。
- 当前 Agent 有意不提供任何 iOS 自动化或企业微信业务能力；好友任务、账号绑定、业务任务接口与页面不在本次范围内。
- Frontend 构建继续输出既有运行配置脚本、第三方 PURE 注释和大分块警告；构建、测试与 E2E 均通过，本次未修改这些非相关问题。

### 下次测试建议

- 待设备空闲后，在隔离目标 Mac 上安装平台根证书并执行一次真实配对，验证 Keychain、LaunchAgent、心跳和匿名设备清单，不运行任何设备自动化工具。
- 依次验证 USB/无线发现、拔线离线、重连恢复、多设备去重和原始 UDID 不出现在本地配置、后端响应、数据库及日志中。
- 在已配对测试 Agent 上发布一个新包，验证升级成功重启、新版本心跳，以及校验和错误或安装失败时旧进程继续服务。

### 回滚方式

- 代码回滚按从新到旧顺序执行 `git revert cf15779`、`git revert 9229d23`、`git revert e8f5211`，不得强制重置；回滚后重新执行 Backend、Frontend、Device Agent 测试和 `docker compose up --build -d`。
- V27 已在实际 MySQL 应用，不得删除或修改已执行迁移。仅回滚应用代码不会删除新增表；若确需物理删除，必须先备份并由数据库管理员确认没有 Agent 注册、审计和设备数据。
- 运行环境回滚后应核对五个默认服务健康、镜像修订、Flyway 兼容性和入口权限边界；无需恢复或启动任何 iOS 设备控制程序。

## 数据同步与服务器管理验收（2026-09-09）

### Git 基准点

Commit: 3370c3fa283094bc629091414eae9d29eb63f101
- 提交信息: Recover deployment results after backend restart
- 上一完整测试基准点: 806666e9ee3fa147415105f04f95d51d0b77e491
- 基准差异检查: `git diff 806666e9ee3fa147415105f04f95d51d0b77e491 3370c3fa283094bc629091414eae9d29eb63f101 -- backend/src/main/java/` 包含数据同步、服务器管理、连接引用保护、菜单与权限初始化等业务代码变更，已触发 Backend 完整测试和项目相关回归测试。
- 测试日期: 2026-09-09
- 分支: master
- 未执行 git push。

### 变更范围

- 新增 MySQL/PostgreSQL 表级数据同步：从源连接选择表和目标连接，支持 `UPSERT`、`FULL_REPLACE`、`APPEND`，按批次复制数据；目标表不存在时创建，已存在时执行结构兼容性校验，不自动删除目标表。
- 新增同步计划、手动执行、Spring 六字段 Cron 调度、执行记录、取消、失败重试和单计划并发互斥；禁止删除仍被同步计划引用的工作流数据库连接。
- 新增服务器管理：支持本地服务器和 SSH 服务器，凭据加密保存，提供连接测试以及受限的 Docker Compose 部署动作；管理员和 `OPS` 角色可管理，普通用户仅可访问其所属数据。
- 新增独立非 root Deployment Agent。Agent 仅接受固定动作、Bearer Token 和安全 Job ID，校验 SSH 主机指纹、参数与工作目录；本地部署可通过可选 `deployment` profile 运行。
- 为本机自部署增加异步任务登记和 Backend 对账恢复：Backend 重启后轮询 Agent 任务结果，重复提交同一 Job ID 保持幂等，超过 16 分钟的不可恢复任务标记失败并释放运行槽位。
- 新增 Flyway V26 数据表、后端接口与测试、前端数据同步/服务器管理页面及中英文文案、Compose 配置、环境变量示例和使用说明。实际 MySQL 已成功应用 V26，既有迁移未被修改。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 可选择源表同步到另一个数据库 | Backend 集成测试使用 H2 的 MySQL/PostgreSQL 兼容模式，输入单表、多表、空表和批量数据 | 仅选中表被复制；数据与关键副作用正确；9 个复制测试通过 | 正常、边界、兼容 |
| 支持三种同步策略 | 对相同主键、已有目标数据和空目标分别执行 `UPSERT`、`FULL_REPLACE`、`APPEND` | 更新插入、清空重载和仅追加行为符合策略；事务失败按表回滚 | 正常、状态、回归 |
| 缺表可创建，不安全结构必须拒绝 | 输入缺失目标表、窄化字段、不兼容类型、非法 Decimal、缺少主键、非法标识符和注入式表名 | 安全结构自动创建；不兼容结构、无主键 UPSERT 和恶意输入均在写入前失败 | 边界、异常、安全 |
| 同步计划支持人工和 Cron 执行 | Service/Controller 测试输入禁用/启用计划、合法及非法六字段 Cron、重复运行、取消、失败后重试 | 人工执行与调度执行可追踪；非法 Cron、并发冲突和非法状态转换被拒绝 | 正常、边界、异常、状态 |
| 连接引用和租户权限保持安全 | 输入被计划引用的连接、管理员、`OPS`、普通用户、跨用户资源和未认证请求 | 被引用连接不可删除；管理员/OPS 可管理，普通用户不可越权；凭据不出现在响应中 | 权限、安全、兼容、回归 |
| 本地和 SSH 只执行受控 Compose 动作 | Backend 验证测试和 Agent Go 测试输入固定动作、Bearer Token、主机指纹、密钥/密码/口令、非法路径与命令注入 | 本地/SSH 请求仅映射到固定参数；精确主机密钥匹配；未授权、注入和非法输入被拒绝 | 正常、异常、权限、安全 |
| 本机部署导致 Backend 重启后结果可恢复 | Backend 使用 H2 与本地 HTTP Server 模拟执行响应中断、Agent 成功/失败查询和超时；Agent 测试调用重复 Job ID | 运行记录保留 Job 标记并由定时对账完成；重复执行幂等；16 分钟超时失败并释放唯一运行槽 | 异常、恢复、并发、回归 |
| 前端提供可操作入口且保持既有契约 | Frontend 完整质量门覆盖路由、表单、API 契约、导航、中英文文案和部署安全契约 | 数据同步与服务器页面可访问；表单校验、权限入口和安全契约通过，既有功能无回归 | 正常、权限、兼容、回归 |
| 部署和数据库升级可重复执行 | Compose 静态解析、Agent profile 构建、默认服务统一重建、运行态健康检查和 Backend Flyway 日志 | 最终提交标记镜像构建成功；五个默认服务启动；Flyway 校验 26 个迁移且 Schema 为 V26 | 配置、部署、迁移、回归 |

### 测试执行结果

- 可计数测试用例共 1,035 个，通过 1,035 个，通过率 100%；失败 0；错误 0；跳过 0。
- Backend：714/714，Maven 3.9.9 / Temurin 17 完整测试套件通过；恢复机制与消息定向测试 14/14、功能相关初始定向测试 46/46，均已包含在完整套件中。
- Frontend：312/312；ESLint、Vue 类型检查、311 个覆盖率测试、生产构建和 1 个 E2E 全部通过。工具函数覆盖率为行 98.02%、分支 80.19%、函数 96.09%。
- Deployment Agent：Go 1.26.6 执行 `go test ./...` 通过，源码中 9 个 `Test*` 用例全部通过；Agent 镜像构建成功并包含 Docker CLI、Compose 5.5.0、OpenSSH、sshpass 和主机密钥工具。
- Compose 配置、Agent 可选 profile 构建、默认五服务统一重建、运行态健康及 MySQL Flyway V26 迁移均通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整套件 | Maven 3.9.9 / Temurin 17 容器挂载 `backend` 与本地 Maven 缓存，执行 `mvn test -B` | 714/714，BUILD SUCCESS |
| Backend 定向回归 | 执行数据同步、服务器管理和消息资源相关测试类 | 初始相关测试 46/46；恢复机制与消息测试 14/14，均通过 |
| Frontend 完整质量门 | Node 24 Alpine 容器挂载完整仓库和独立 `node_modules` 卷，执行 `npm test` | ESLint、类型检查、311 个覆盖率测试、构建和 1 个 E2E 全部通过 |
| Deployment Agent | Go 1.26.6 容器执行 `gofmt -w main.go main_test.go && go test ./...` | 格式化通过；9/9 测试通过 |
| Compose 配置与 Agent 镜像 | 执行 Compose 配置检查和 `docker compose --profile deployment build deployment-agent` | 配置有效；最终提交标记 Agent 镜像构建成功，运行工具检查通过 |
| 默认服务统一重建 | 注入 `APP_IMAGE_REVISION=3370c3fa283094bc629091414eae9d29eb63f101` 后执行 `docker compose up --build -d` | Backend、Frontend、Python Worker、Document Parser、Caddy 重建并启动；最终检查保持 healthy |
| 数据库迁移 | 检查 Backend 启动日志和实际外部 MySQL 的 Flyway 历史 | 26 个迁移校验成功，Schema 从 V25 升级至 V26 |
| 静态与工作区检查 | 执行 `git diff --check`、`git status --short` 和测试基准差异检查 | 无空白错误；报告提交前仅包含本报告变更 |

### 测试过程问题与处理

- 首次 Compose 重建因未设置必填的 `APP_IMAGE_REVISION` 失败；随后使用当前已测试 Git Commit 注入该变量，构建和启动成功，未将运行值写入仓库。
- 端口 80/443 被非本项目容器 `docker-nginx-nginx-1` 占用；按仓库规则停止该容器后重新执行 Compose，项目服务启动成功。该非项目容器目前保持停止。
- 首次 Frontend 容器仅挂载 `frontend` 目录，依赖仓库根文件的测试出现 `ENOENT`；改为挂载完整仓库后重新执行完整质量门，312/312 全部通过。
- 代码复核发现本机部署重建 Backend 时，同步等待 Agent 响应会丢失部署结果；补充 Agent Job 注册表、Backend 定时对账、幂等与超时回收后，重新执行定向及完整 Backend/Agent 测试并通过。
- 没有创建或遗留调试文件；测试密钥未输出、未写入仓库或临时文件；未删除、跳过或弱化既有测试。

### 已知问题与限制

- 实际 MySQL 已完成 V26 迁移，但本次没有可用的隔离外部 MySQL/PostgreSQL 组合执行真实跨库业务同步；复制路径由 H2 的 MySQL/PostgreSQL 兼容模式覆盖，仍建议在独立真实数据库上补充双向 E2E。
- 当前环境没有可用的真实 SSH 主机和 rootless Docker Socket；已完成 Agent 镜像、命令约束、认证、主机指纹和 Backend 集成模拟测试，但未执行真实远程 SSH 部署。
- `FULL_REPLACE` 对单张表使用事务，不保证多表计划整体原子；MySQL 创建目标表的 DDL 也不是事务性的。失败后可从执行记录定位并重试未完成计划。
- Agent Job 注册表位于内存。执行本机部署期间 Deployment Agent 必须持续运行；若 Agent 自身重启，Backend 会在 16 分钟后将任务标记失败并释放运行槽。
- Frontend 构建仍输出既有运行配置脚本、第三方 PURE 注释和大分块警告；构建、测试与 E2E 均通过，本次未修改这些非相关问题。

### 下次测试建议

- 在隔离的真实 MySQL 和 PostgreSQL 中执行双向同步矩阵，覆盖大表分页、字符集、时区、Decimal 精度、二进制字段、断线重试和三种策略的数据一致性。
- 在受控 SSH 主机上部署 rootless Docker 与 Compose，分别验证密钥、密码、密钥口令、主机指纹变更、网络超时和远端服务重启后的结果对账。
- 演练 Deployment Agent 在任务运行中重启，确认 16 分钟超时告警与人工重试流程；如需 Agent 重启后继续恢复，应将 Job 状态迁移到受保护的持久存储。
- 对多表强一致场景评估快照、变更数据捕获或目标暂存表切换，不应直接把当前逐表事务扩展为跨数据库事务。

### 回滚方式

- 代码回滚按从新到旧顺序执行 `git revert 3370c3f`、`git revert 2c6939a`，不得使用强制重置；回滚后重新执行 Backend、Frontend、Agent 测试和 `docker compose up --build -d`。
- V26 已在实际 MySQL 应用，不得删除或修改已执行迁移。仅回滚应用代码不会删除新增表；如确需物理回滚，先备份并确认无同步计划、执行历史和服务器配置数据，再由数据库管理员手工删除新增表。
- 运行环境回滚后应检查 Flyway 兼容性、五个默认服务健康、入口 readiness 和部署记录状态；被停止的非项目 `docker-nginx-nginx-1` 是否恢复由其所属项目负责人决定。

## Rootless 插件适配器运行验收（2026-09-06）

### Git 基准点

Commit: 806666e9ee3fa147415105f04f95d51d0b77e491
- 提交信息: Enable rootless adapter lifecycle
- 上一完整测试基准点: 16fa0198aa9da9b9e8f8ae61e165f22a042138f6
- 基准差异检查: `git diff 16fa0198aa9da9b9e8f8ae61e165f22a042138f6 806666e9ee3fa147415105f04f95d51d0b77e491 -- backend/src/main/java/` 无输出；本次只修改 Adapter Broker 镜像、Compose 运行配置和部署契约测试，未触发后端业务代码完整重测。
- 测试日期: 2026-09-06
- 分支: master
- 未执行 git push。

### 变更范围

- 安装 Lima 2.2 并创建 `base-ai-rootless` 实例，配置 6 CPU、8 GiB 内存和 80 GiB 磁盘；实例使用 rootless Docker 29.8.0，并注册为 macOS 用户登录时自动启动。
- 将 `bootstrap-secrets`、Caddy 状态和 DIFY/n8n 插件数据卷从 Docker Desktop 只读复制到 rootless Daemon；Docker Desktop 中原有五个 `ai-*` 服务保留为停止状态，其他项目未停止或重建。
- Adapter Broker 镜像增加 Docker Compose 5.4.0 插件。ARM64 与 AMD64 发布文件分别使用固定 SHA-256 校验，Broker 仍为 `scratch` 镜像且只包含 Docker CLI、Compose 插件和 Adapter Manager 二进制。
- Broker 仅新增 DIFY 与 n8n Worker 的专用内部 token，用于从窄化 Compose 创建 Worker；数据库、Redis、会话、配置加密等平台密钥仍不进入 Broker。
- 完整 `plugin-adapters` profile 已在 rootless Daemon 运行。DIFY 与 n8n 的期望状态通过平台正式管理接口持久化为启用。
- 按既定范围不修改数据库/Redis 传输协议，不轮换或增强现有生产凭据，也不修改 `domestic-trade` 仓库或容器。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| Broker 能在最小镜像内执行受控 Compose 生命周期命令 | Frontend 部署契约先稳定复现缺少 Compose 插件；镜像构建校验 ARM64 发布文件；容器内执行 `docker compose version --short` | 失败测试修复后通过；容器内 Compose 版本为 5.4.0 | 缺陷复现、供应链、兼容 |
| Broker 只获得创建 Worker 必需的凭据 | 部署契约检查 Broker 服务块；输入 DIFY/n8n Worker token 并检查 MySQL、PostgreSQL、Redis、会话和配置加密密钥 | 两枚 Worker token 存在；平台核心密钥均不存在 | 权限、安全、回归 |
| Docker 控制面真实运行于 rootless Daemon | Broker 启动预检与最终运行检查；读取宿主和 Broker 容器内 `SecurityOptions`、Socket、网络和挂载 | 两侧均包含 `name=rootless`；Broker 无网络、只读根文件系统、非特权且不挂载 `.env` 或根 Compose | 部署、权限、安全 |
| DIFY 与 n8n 可由平台启停并保留期望状态 | 使用管理员正式接口依次对 DIFY、n8n 输入 `enabled=false` 和 `enabled=true`，轮询期望值与实际容器状态，并等待一次以上 15 秒 Backend 对账周期 | 两者均完成 `DISABLING → STOPPED → ENABLING → RUNNING`；最终期望值为 true，对账后保持 healthy | 正常、状态、持久化、回归 |
| 全 profile 可追溯重建且资源受限 | rootless context 执行完整 Compose 重建；检查 11 个容器的镜像标签、OCI revision、Memory、NanoCPUs、PidsLimit 和 RestartCount | 11 个镜像均对应本基准提交；资源限额非零，重启数均为 0 | 构建、部署、稳定性 |
| 外部入口边界在迁移后保持有效 | 宿主 18443 探测 readiness、未认证接口、内部接口和恶意 Origin | 返回 200、401、404；恶意 Origin 无允许来源响应头 | 权限、安全、兼容 |

### 测试执行结果

- 本次重新执行的可计数测试：Frontend 312/312，通过率 100%；失败 0；错误 0；跳过 0。
- Frontend：ESLint、Vue 类型检查、311 个覆盖率测试、生产构建和 1 个 E2E 全部通过。工具函数覆盖率为行 98.01%、分支 80.19%、函数 96.09%。
- Adapter Manager/Broker：Go 1.26.6 固定摘要容器执行 `go test ./...` 通过；Go 默认输出不提供可聚合用例数。
- 部署契约定向测试先按缺陷修复规则失败，完成实现后 1/1 通过；完整部署契约 19/19 通过，包含在 Frontend 的 311 个覆盖率测试中。
- 上一基准记录的 Backend 688/688、Python Worker 77/77、Dify Worker 25/25、n8n Worker 16/16 仍适用于未变更的业务源码；本次未重复执行。
- Compose 默认及 `plugin-adapters` profile 静态解析、11 镜像完整构建、统一启动、平台生命周期和运行态安全检查全部通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 缺陷复现与部署契约 | Node Test Runner 定向执行 Adapter Broker 部署契约，再执行完整 `security-deployment.test.mjs` | 修复前 0/1 失败；修复后定向 1/1、完整 19/19 通过 |
| Adapter Manager/Broker | Go 1.26.6 固定摘要容器只读挂载源码并执行 `go test ./...` | 通过 |
| Frontend 完整质量门 | `cd frontend && npm test` | ESLint、Vue 类型检查、311 个覆盖率测试、生产构建和 1 个 E2E 全部通过 |
| Compose 配置 | 复用运行容器中的既有配置且不输出密钥，执行默认及 `plugin-adapters` profile 的 `docker compose config --quiet` | 两种配置均通过 |
| Rootless 完整重建 | `docker --context lima-base-ai-rootless compose --profile plugin-adapters up --build -d` | 11 个提交标记镜像构建成功；Compose ARM64 文件校验返回 OK；11 个服务启动成功 |
| 平台生命周期 | 管理员正式 API 对 DIFY、n8n 分别执行关闭、等待停止、启用、等待运行并退出测试会话 | 两个来源全链路通过；最终均为 `enabled=true`、`RUNNING` |
| 运行态安全 | 检查 rootless SecurityOptions、Broker Compose、挂载、权限、资源、revision、重启数、入口和 CORS | 11/11 healthy；重启 0；Compose 5.4.0；入口 200/401/404；恶意 Origin 被拒绝 |

### 测试过程问题与处理

- 第一次迁移脚本在停机前因使用 zsh 只读变量名退出，没有改变运行环境。更名后重试。
- 第一次实际切换把容器内跟踪配置路径继承为 Lima 主机路径，Backend 创建失败；自动回滚恢复 Docker Desktop 五个服务全部 healthy。排除该容器内部变量后再次切换成功。
- Caddy 首次使用了错误的端口变量名，在 Lima 内部可用但宿主 18443 不可达；改用 Compose 实际接受的 `HTTP_PORT=18080` 与 `HTTPS_PORT=18443` 后，宿主 readiness 恢复 200。
- DIFY 插件卷约 7.4 GiB，复制时间来自真实数据迁移；源卷全程只读，复制完成后无临时归档文件。
- 宿主未安装 Go。第一次固定镜像测试因只读 tmpfs 的执行限制失败；改为一次性可写容器根文件系统、源码只读挂载后，同一 `go test ./...` 通过，容器退出即删除。
- 直接 Manager 生命周期测试后 Worker 被 Backend 按数据库中的默认关闭期望值回收，证明对账正常。随后改用平台正式接口持久化启用，再次执行完整生命周期后保持运行。
- 登录响应使用统一数据包装，第一次验收脚本按根级 token 解析失败；修正为解析 `data.token` 后，后续测试会话均显式退出，并通过在线会话管理接口清理了首次测试会话。该失败未修改适配器期望状态。
- 没有创建或遗留调试文件，运行密钥未输出、未写入仓库或临时文件。

### 已知问题与限制

- 按用户明确决定保留的风险：MySQL、PostgreSQL 与 Redis 的现有传输保护不在本次范围内；现有生产凭据强度也未调整或轮换。
- Lima 配置为用户登录时自动启动。主机启动后、用户登录前，本项目不会提供服务；如需无人值守开机启动，应单独评估并改用 boot 条件。
- 新增的插件签名密钥、出站网关 token 和 rootless Socket 参数未写入仓库 `.env`。现有容器和 VM 重启可复用已保存的容器配置；未来主动执行 Compose 重建时，仍须从密钥管理或受控运行环境重新注入。
- 本次验证覆盖 Worker 生命周期与 rootless Docker 控制面，没有从外部市场下载并执行一个真实第三方插件包；插件包解析、一次性沙箱、出站域名令牌和清理行为由现有 Worker 与 Go 测试覆盖。

### 下次测试建议

- 选择一个经过审批的最小 DIFY 或 n8n 插件，在隔离测试数据上执行下载、探测和一次调用，补充一次性沙箱容器及卷清理的运行态证据。
- 演练 macOS 注销、重新登录后的 Lima 自动启动、11 个容器恢复和 18443 端口转发，确认桌面环境的完整冷启动恢复时间。
- 将 rootless Socket 参数与插件专用密钥接入部署密钥管理，提供不依赖运行容器反向读取环境变量的可重复重建入口。

### 回滚方式

- 代码回滚使用 `git revert 806666e`，不得强制重置工作区；回滚后重新执行部署契约、Go 测试和 Compose 配置检查。
- 运行环境回滚时，先停止 Lima 中项目名为 `ai` 的容器，再启动 Docker Desktop 中保留的 `ai-document-parser`、`ai-python-worker`、`ai-backend`、`ai-frontend` 和 `ai-caddy`，随后检查 readiness 与重启数。不要删除已复制卷，确认稳定后再决定是否停用 Lima 登录自启动。
- 回滚到默认 profile 会再次停用插件适配器；数据库中已持久化的两项启用期望应同步通过平台接口改回 false，避免 Backend 持续重试不可用的 Manager。

## 剩余安全风险修复测试结果（2026-09-06）

### Git 基准点

Commit: 16fa0198aa9da9b9e8f8ae61e165f22a042138f6
- 提交信息: Pass image revisions through CI validation
- 核心安全提交: 8d888ec9640eafdefb756cd6335dc4a487fa14d0（Harden application and container security）
- 构造注入修复提交: 158b2b7a6f64f164a2665fdb85b22d84faf9126d（Fix workflow network policy constructor injection）
- 上一测试报告业务基准点: a9fe614
- 基准差异检查: 17 个后端业务代码文件、8 个后端测试文件以及 Compose、镜像、CI、Go Broker 和部署契约发生变化，已触发完整项目重测。
- 测试日期: 2026-09-06
- 分支: master
- 未执行 git push。

### 变更范围

- 插件适配器改为可选 `plugin-adapters` profile；默认核心平台不启动适配器。Broker 只读取窄化 Adapter Compose，不读取平台 `.env` 或根 Compose，并在开放控制 Socket 前验证 Docker Daemon 的 rootless 安全选项。
- 所有自建镜像使用必填 Git Commit 作为标签并写入 OCI revision；CI 构建与扫描显式传入当前提交，不再使用 `latest`。
- Document Parser、Backend、Python Worker、Adapter Broker、Supervisor、Manager、Outbound Gateway、Dify/n8n Worker、Frontend 和 Caddy 均配置 CPU、内存与 PID 上限。
- 工作流 Host/CIDR 策略在实际解析阶段复核地址；Lettuce 使用受控解析器，其他 JVM 网络客户端使用进程级固定正向 DNS 缓存，阻断校验与建连之间的 DNS 重绑定窗口。
- 管理员敏感凭据回查增加 Redis 跨实例限流：默认连续失败 5 次后封禁 15 分钟，正确密码清理状态，Redis 异常时拒绝回查。
- CORS 默认关闭；仅接受配置中的精确 HTTP/HTTPS Origin，拒绝通配符、路径、查询、用户信息和非 HTTP 协议。
- 修复 `WorkflowNetworkPolicy` 多构造器下的 Spring 注入选择，防止 Backend 因 Bean 创建失败进入重启循环。
- 按用户明确决定，本次不修改数据库/Redis 传输保护，也不轮换或增强现有生产凭据。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 默认停用插件适配器，启用时 Broker 只持有收敛后的 rootless Docker 权限 | Adapter Manager Go 完整测试；Frontend 部署契约测试；默认 Compose 重建后检查运行容器 | 未配置隔离 Docker 时无插件容器运行；Broker 固定命令、窄化挂载和 rootless 校验测试通过 | 正常、异常、权限、安全、兼容 |
| 镜像可追溯到唯一 Git 提交 | Frontend 部署契约检查 11 个镜像声明、8 个 Dockerfile 和 CI；运行态检查五个默认镜像标签与 OCI revision | 无 `latest`；运行镜像标签和 revision 均为当前基准提交；通过 | 部署、供应链、回归 |
| 核心及可选容器均有资源上限 | Frontend 部署契约覆盖全部 11 个服务；运行态读取五个默认容器的 Memory、NanoCPUs 和 PidsLimit | 静态配置完整，运行容器限额均为非零值；通过 | 边界、稳定性、回归 |
| 工作流连接抵抗 DNS 重绑定 | Backend BaseAiApplication、WorkflowNetworkPolicy、WorkflowRedisClientFactory 和 Connector Executor 测试；模拟相邻解析从公网切换到回环地址 | 第二次受控解析拒绝私网地址，Redis 建连使用策略解析器，Spring 正确创建策略 Bean；通过 | 异常、安全、兼容、回归 |
| 敏感凭据回查具有跨实例失败限流 | Backend SecretRevealAttemptService 与 SecretRevealAuthorizationService 测试输入首次失败、阈值失败、封禁、正确密码、非管理员和 Redis 异常 | 失败窗口、15 分钟封禁、成功清理、权限拒绝和 Redis 故障关闭均符合预期；通过 | 正常、边界、异常、权限、安全 |
| 生产 CORS 只允许显式精确来源 | Backend WebConfig 参数化测试输入空配置、重复/大小写来源、通配符、路径、查询和非 HTTP 来源；运行态发送恶意 Origin | 空配置不注册 CORS；非法配置拒绝启动；`https://evil.example` 无允许来源响应头；通过 | 正常、边界、异常、安全、兼容 |
| Backend 可稳定启动且外部入口边界有效 | Spring 构造注入测试；统一重建后检查 readiness、重启次数、启动错误、受保护接口和内部接口 | readiness 200，重启 0，构造失败 0；受保护接口 401，内部接口 404；通过 | 稳定性、权限、安全、回归 |

### 测试执行结果

- 可计数测试用例：1,118 个；通过 1,118 个（100%）；失败 0；错误 0；跳过 0。
- Backend：688/688，通过完整 Maven 测试套件；其中本次风险相关定向测试 31/31，已包含在 688 个总数中。
- Frontend：312/312；ESLint、Vue 类型检查、311 个覆盖率测试、生产构建和 1 个 E2E 全部通过。工具函数覆盖率：行 98.01%、分支 80.19%、函数 96.09%。
- Python Worker（Python 3.12.14）：77/77；Dify Plugin Worker（Python 3.12.13）：25/25；n8n Plugin Worker：16/16。
- Adapter Manager/Broker 与 Outbound Gateway 的 Go 完整包测试均通过；Go 默认输出不提供可聚合用例数，未计入上述 1,118 个可计数总数。
- Compose 配置解析、统一重建、运行态健康与安全探测全部通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 风险相关套件 | 固定摘要 Maven 3.9.9 / Temurin 17 容器执行 7 个相关测试类 | 31/31，BUILD SUCCESS |
| Backend 完整套件 | 固定摘要 Maven 3.9.9 / Temurin 17 容器执行 `mvn -B -ntp test` | 688/688，BUILD SUCCESS |
| Frontend 完整质量门 | `cd frontend && npm test` | ESLint、Vue 类型检查、311 个覆盖率测试、生产构建和 1 个 E2E 均通过 |
| Python Worker | Python 3.12 固定摘要容器只读挂载源码，安装锁定开发依赖后执行 pytest | 77/77，通过 |
| Dify 与 n8n Plugin Worker | Python 3.12 执行 unittest；Node 执行内置 test runner | 25/25 与 16/16，通过 |
| Go 服务 | Go 1.26.6 固定摘要容器分别执行 Adapter Manager/Broker 与 Outbound Gateway 的 `go test ./...` | 两个模块均通过 |
| Compose 配置与启动 | 复用当前 `ai-*` 运行配置并为未启动 profile 提供未落盘临时令牌，执行 `docker compose config --quiet` 与 `docker compose up --build -d` | 五个默认服务重建成功并保持 healthy |
| 运行态安全检查 | 检查容器来源、资源限额、OCI revision、可选 profile、readiness、重启数、CORS、认证和内部路由 | 全部通过；恶意 Origin 无 CORS 头，401/404 边界正确 |
| 静态与工作区检查 | `git diff --check`、`git status --short` | 通过；提交前只有测试报告变更 |

### 测试过程问题与处理

- 宿主机未安装 Maven，按项目固定镜像使用 Maven 3.9.9 / Temurin 17 容器运行定向和完整测试，未修改宿主依赖。
- 首次前端定向命令把测试路径交给了错误的 npm 执行工作目录，测试文件未执行；随后项目 `npm test` 完整质量门已覆盖该文件并全部通过。npm 临时解析未修改仓库。
- 宿主 Python 3.12 未安装 pytest；改用 Python 3.12 固定摘要容器只读挂载源码、安装带哈希的锁定开发依赖后运行，77 个测试全部通过，仓库未生成 pytest 缓存。
- 本地 `.env` 不保存部署值。Compose 验证从当前 `ai-backend` 容器复用既有必需配置，插件 profile 所需的临时令牌只存在于验证进程，未输出或落盘。
- 历史上停止的 `ai-adapter-*`、`ai-outbound-gateway` 和两个插件 Worker 容器仍存在，但没有运行；本次未删除这些非当前任务产生的容器。

### 已知问题与限制

- 按用户明确决定保留的风险：MySQL、PostgreSQL 与 Redis 的现有传输保护不在本次范围内；现有生产凭据强度也未调整或轮换。
- JVM 正向 DNS 结果会固定到 Backend 进程生命周期。工作流允许目标合法变更 IP 后，需要重启 Backend 才能使用新地址；部署侧仍应保留出口网络策略作为独立边界。
- 当前主机没有可用于本项目的独立 rootless Docker Socket，因此插件适配器 profile 保持停用；Broker 的 rootless 拒绝逻辑由 Go 测试覆盖，未执行真实 rootless Daemon 上的插件运行态验收。
- 本地 `.env` 为空；后续部署必须从密钥管理提供必需配置，并显式把 `APP_IMAGE_REVISION` 设为待部署的已测试 Git Commit。
- Frontend 构建继续出现既有的运行配置脚本、第三方 PURE 注释和大分块警告；构建、覆盖率测试和 E2E 均通过。

### 下次测试建议

- 在独立 rootless Docker Engine 上启用 `plugin-adapters` profile，验证 Broker 启动校验、Socket 权限、一次性沙箱资源和停止后的清理行为。
- 增加多 Backend 实例共享 Redis 的并发回查限流集成测试，覆盖阈值附近的并发失败和成功清理竞争。
- 在隔离 DNS 测试环境中验证 JDBC、S3、Kafka 和 RabbitMQ 客户端在真实建连阶段复用 JVM 固定解析结果，并演练目标 IP 变更后的受控重启。
- 将部署入口固化为只接收干净 Git Commit 或镜像 digest 的脚本/流水线，并从密钥管理注入变量，避免依赖人工 Shell 环境。

### 回滚方式

- 如需回滚，按从新到旧顺序分别执行 `git revert 16fa019`、`git revert 158b2b7`、`git revert 8d888ec`，不得使用破坏工作区的强制重置。
- 回滚后必须重新执行 Backend、Frontend、Python、Go 和插件 Worker 完整测试，并运行 `docker compose up --build -d` 与运行态边界检查。
- 回滚核心安全提交会重新开放宽 CORS、无限资源和未限流回查等风险，只应在隔离环境中进行。

## 风险修复方案 A 测试结果（2026-09-02）

### Git 基准点

Commit: a9fe614
- 提交信息: Harden credential and execution security
- 上一测试报告业务基准点: af666ba38d1b7250554061f66c0f02d4a3a08346
- 基准差异检查: 23 个后端业务代码文件发生变化，共 832 行新增、180 行删除，已触发完整后端重测。
- 测试日期: 2026-09-02
- 分支: master
- 未执行 git push。

### 变更范围

- API Key、模型供应商密钥和 SMTP 密码均改为管理员输入当前密码后二次验证才可回查；响应禁止缓存，前端不自动回填明文，并在弹窗关闭时清空内存中的敏感值。
- API Trigger 以当前调用者为边界执行所有读取、修改、执行和日志查询；会话管理员可跨所有者管理，API Key 即使绑定管理员也不能越过所属资源边界。
- 启用数据范围解析与校验，用户、部门和角色授权不能跨越当前操作者的数据范围。
- 请求快照过滤凭据字段；敏感回查不再采集请求体；运维日志接口不再返回请求数据；新增每日保留期清理任务，MySQL V25 前向迁移按确认范围清空既有 operation_log.request_data。
- 请求体限额改为惰性有界读取，内部签名请求先校验请求头再读取和缓存正文；上传限制与产品 10 MB 文案对齐，超限返回 413。
- API Trigger 对目标主机进行 DNS 解析校验，并在 HTTP 客户端中复用经验证的解析结果；限制重定向、重试和私网目标。
- Broker 使用最小化的独立 Adapter Compose 文件，不再挂载完整 Compose 配置或环境文件；部署配置要求提供 rootless Docker Socket。Dify Worker 升级 Werkzeug 至 3.1.6 并移除不确定的 apt upgrade。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 管理员可回查 API Key，且回查前需二次验证 | Backend SecretRevealAuthorizationService、API Key/LLM/邮件 Controller 契约测试；Frontend API Key、模型供应商、邮件页面契约测试 | 正确密码允许回查；错误密码返回拒绝；前端不自动读取或保留明文；通过 | 正常、异常、权限、安全 |
| API Key 不得越权管理其他人的 API Trigger | ApiTriggerServicePersistenceTest 分别以会话管理员与绑定管理员的 API Key 访问他人资源 | 会话管理员可按管理权限操作；API Key 对非所有者资源得到 404；通过 | 权限、安全、回归 |
| 数据范围实际限制用户、部门与角色操作 | DataScopeResolverTest 与 PlatformAdminServiceTest 输入本范围、跨范围及超范围角色授权 | 跨范围查询/修改和权限委派均拒绝；合法范围操作保持兼容；通过 | 正常、边界、权限、安全 |
| 运维日志不存储或暴露敏感请求数据，历史数据可按保留期清理 | TraceRequestSnapshotSanitizerTest、OperationAuditAspectTest、OperationLogRetentionJobTest、SystemMonitorControllerTest 与 V25 迁移资源 | 凭据字段脱敏，禁用敏感接口请求采集，查询结果不含 requestData，过期日志被删除；通过 | 安全、回归、数据治理 |
| 大请求和上传超限不在认证前占用过量内存 | RequestSizeLimitFilterTest、InternalRequestAuthFilterTest、InternalRequestSignerTest、GlobalExceptionHandlerTest | 声明/分块超限均为 413；非法签名不读取正文；10 MB 上传限制一致；通过 | 边界、异常、安全 |
| API Trigger 外连抵抗私网和 DNS 重绑定 | ApiTriggerUrlPolicyTest、ApiTriggerServicePersistenceTest、ApiTriggerServiceResponseDecodingTest | 回环/私网解析被拒绝，已验证解析器参与 HTTP 调用，超时和重定向受控；通过 | 安全、异常、兼容 |
| Broker 与插件运行配置收敛，Dify 依赖升级可构建 | Go Adapter 单元测试、Frontend 部署与生命周期契约测试、Dify 镜像内单元测试、Compose 配置校验 | Broker 无完整环境文件挂载，窄化 Compose 可解析，Werkzeug 3.1.6 镜像与 25 个测试通过；通过 | 供应链、部署、安全 |
| 全环境可重建并启动 | Compose 统一重建、配置校验和运行态健康检查 | 当前启用的 8 个服务容器均 healthy；通过 | 构建、部署、回归 |

### 测试执行结果

- 可计数测试用例：1,008 个；通过 1,008 个（100%）；失败 0；错误 0；跳过 0。
- Backend：675/675，通过完整 Maven 测试套件；涵盖 Controller、Service、安全、审计、自动化、工作流与持久化相关回归。
- Frontend：308/308；ESLint、Vue 类型检查、覆盖率测试、生产构建和 1 个 E2E 全部通过。工具函数覆盖率：行 98.01%、分支 80.19%、函数 96.09%。
- Dify Plugin Worker（Python 3.12）：25/25，通过镜像内 unittest。
- Adapter Manager/Broker：Go 完整包测试通过；Go 的默认输出不提供可聚合的用例数，未计入上述 1,008 个可计数总数。
- Docker Compose 配置校验、统一重建和当前运行态健康检查均通过。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整套件 | 固定摘要 Maven 3.9.9 / Temurin 17 容器执行 mvn test -B | 675/675，BUILD SUCCESS |
| Frontend 统一质量门 | npm --prefix frontend test | ESLint、vue-tsc、308 个覆盖率测试、生产构建和 1 个 E2E 均通过 |
| Dify Worker 镜像与测试 | docker compose --profile plugin-adapters build dify-plugin-worker；镜像内 python -m unittest discover -s tests | 镜像构建成功，25/25 通过，确认安装 Werkzeug 3.1.6 |
| Adapter Manager/Broker | 固定 Go 1.26.6 容器执行 go test ./... | 通过 |
| Compose 配置与启动 | 提供未输出、未落盘的临时签名密钥后执行 docker compose config --quiet 与 docker compose up --build -d | 配置通过，重建启动成功 |
| 运行态检查 | docker compose ps | 当前启用的 8 个服务容器均 healthy |
| 静态检查 | git diff --check | 通过 |

### 测试过程问题与处理

- 宿主机未安装 Maven，改用项目固定摘要的 Maven 容器运行完整测试；未修改宿主机依赖。
- Compose 的签名密钥和 Docker Socket 变量按预期为必填项。验证时使用未输出、未落盘的随机签名密钥；本机 Docker Desktop 不提供 rootless Socket，因此仅在本次验证命令中显式传入现有 Socket，仓库配置本身未回退到该 Socket。
- 前端生产构建出现既有运行时配置脚本、第三方 PURE 注释和大分块警告；均非阻断，构建和 E2E 通过。
- 未创建或遗留临时测试、调试文件。

### 未执行的测试

- 未重新执行 Trivy 源码/镜像扫描、npm audit、Actionlint、Python Worker、n8n Plugin Worker、Outbound Gateway 的独立套件；本次未修改这些模块或其依赖。Dify Worker、Frontend、Backend 和 Adapter Manager 已按变更范围完成测试。
- 未使用真实生产账号进行浏览器端密钥回查；该流程由后端授权、Controller 响应策略和前端交互契约测试覆盖。
- 未在 rootless Docker Engine 主机上进行 Broker 的运行态 Socket 挂载验证；见下方限制。

### 已知问题与限制

- 生产部署必须把 ADAPTER_DOCKER_SOCKET 设置为 rootless Docker Socket，并确保对应服务账号可访问；本机 Docker Desktop 的临时验证 Socket 不能作为生产配置示例。
- API Trigger 已固定经验证的解析结果。工作流连接器的运行时 DNS 与网络边界仍须由部署侧的出站网关和网络策略持续约束，不能仅依赖应用层校验。
- V25 为前向数据清理迁移，已清空的历史 operation_log.request_data 不可由代码回滚恢复；保留了操作主体、时间、对象类型和结果等审计元数据。

### 回滚方式

- 使用 git revert a9fe614 回滚功能代码、测试和部署配置；不得使用破坏工作区的强制重置。
- V25 不应被删除、修改或回滚为重新写入历史请求数据。代码回滚后仍保留已清理的敏感字段状态。
- 回滚后需重新提供适当的 Socket 和签名密钥，并执行 docker compose up --build -d、Backend 完整测试和 Frontend 完整质量门。

### 下次测试建议

- 在实际 rootless Docker Engine 环境中执行 Adapter Broker 创建、启停和插件隔离的运行态验证。
- 对真实生产规模的操作日志执行保留期清理性能评估，并确认备份策略不再包含已清理的请求正文。
- 增加已登录浏览器 E2E，覆盖正确/错误管理员密码回查、响应缓存头和关闭弹窗后的敏感值清理。
- 在工作流连接器使用动态 DNS 的部署环境中，持续验证网关的 DNS、CIDR 和出口网络策略。

## 知识库展示与维护优化测试结果（2026-09-01）

### Git 基准点

Commit: af666ba38d1b7250554061f66c0f02d4a3a08346
- 提交信息: `Improve knowledge base management`
- 上一测试报告业务基准点: `e9377369e9c8fee18d4133306f0737468852ebc3`
- 基准差异检查: `git diff e9377369e9c8fee18d4133306f0737468852ebc3 af666ba38d1b7250554061f66c0f02d4a3a08346 -- backend/src/main/java/` 有输出，共 2 个业务代码文件、103 行新增、4 行删除，已触发完整重测
- 测试日期: 2026-09-01
- 分支: master
- 未执行 `git push`

### 变更范围

- 新增知识库管理分页查询，支持关键字、启用状态和存储类型筛选，返回当前可见范围的知识库、文档数量及全局统计；普通用户仅可查看自己的知识库，管理员可查看全部。
- 新增知识库启停接口、文档服务端分页筛选和最多 100 条的批量删除接口；更新操作保持所有者权限约束，批量删除对单条失败返回明确结果，不隐藏已成功的副作用。
- 前端改为主从式知识库管理界面，补充统计卡片、筛选、分页、详情与文档状态；非所有者知识库对管理员只读显示。
- 上传改为最多 20 个文件的顺序队列，单文件上限 10 MB，逐文件显示状态并允许失败项重试；文档支持当前页多选和批量删除确认。
- 创建与编辑表单增加字段级校验；已有文档时锁定影响资源身份的配置，保留已有单条上传、单条删除和知识库 CRUD 接口兼容性。
- 按确认范围不包含数据库迁移、新依赖、文档内容预览、向量重建或批量重建，也未改变工作流中的知识库选择方式。

### 验收标准—测试用例映射

| 验收标准 | 测试层级、前置条件与输入 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 管理列表可分页筛选且统计准确 | Service 集成测试使用 H2 写入多所有者、启用/停用、不同存储类型、有效及已作废文档；输入关键字、状态、存储、页码与每页数量 | 返回匹配页；统计基于用户可见范围且排除作废数据；通过 | 正常、边界、兼容、回归 |
| 普通用户与管理员的数据权限明确 | Service 测试分别以所有者、其他用户和管理员身份查询、启停及删除 | 普通用户仅见自有数据且只有所有者可更新；管理员可跨用户查看但不能替代所有者更新；通过 | 权限、安全、越权 |
| 非法筛选和分页输入得到稳定处理 | Service 参数化测试输入无效状态、存储类型、超大页码及页大小 | 无效枚举被拒绝；页码受控；列表不泄露其他用户数据；通过 | 边界、异常、安全 |
| 文档可服务端分页、筛选与查看状态 | Service 集成测试写入不同名称、状态及作废文档；输入关键字、状态和分页参数 | 仅返回当前知识库内匹配且未作废文档，数量与页信息正确；通过 | 正常、边界、兼容 |
| 启停与批量删除遵守所有者权限 | Service 测试输入合法 ID、重复 ID、空集合、非法 ID、超过 100 条及不存在资源 | 合法项产生更新/删除副作用；重复项去重；非法请求拒绝；单条失败被逐项报告；通过 | 正常、边界、异常、权限 |
| Controller 暴露的新接口具有正确权限注解 | Controller 反射契约测试检查管理查询、启停、文档分页与批量删除方法 | 查询要求知识库列表权限，更新要求知识库更新权限；通过 | 权限、安全、回归 |
| 多文件上传按顺序执行并可重试 | Frontend 工具测试输入多文件、第二个文件失败后重试 | 同一时刻只执行一个上传；各文件状态独立；重试只处理失败项；通过 | 正常、异常、回归 |
| 上传边界与错误展示受控 | Frontend 参数化测试输入 0/1/20/21 个文件、10 MB 临界值和未知错误 | 数量及大小边界正确；未知错误不回显原始敏感内容；通过 | 边界、异常、安全 |
| 主从界面包含筛选、状态、批量操作和只读控制 | Frontend SFC 契约测试检查分页、筛选、选择、批量删除、队列和所有者判断 | 关键交互与权限控制存在，旧管理入口保持可用；通过 | 正常、权限、兼容、回归 |
| 变更可在完整环境构建运行 | Backend/Frontend 完整套件、`docker compose up --build -d`、容器健康检查和未认证路由探测 | 964 个测试全部通过，9 个核心服务 healthy，新路由未认证返回 401；通过 | 构建、部署、安全、回归 |

### 测试执行结果

- 本次实际执行且不重复计数的测试用例：964 个。
- 通过：964 个（100%）；失败 0；错误 0；跳过 0。
- Backend：655/655；其中知识库相关包 13/13，新管理 Service 与 Controller 契约测试 8/8。
- Frontend：308/308；知识库管理工具与界面契约测试 5/5；生产服务 E2E 1/1。
- Frontend 工具函数覆盖率：行 98.01%、分支 80.19%、函数 96.09%，高于 95%/75%/90% 阈值；新增知识库管理工具分别为 100%、86.36%、100%。
- ESLint、Vue 类型检查、生产构建、`git diff --check` 全部通过。
- 运行态：Adapter Docker Broker、Adapter Supervisor、Adapter Manager、Outbound Gateway、Document Parser、Python Worker、Backend、Frontend、Caddy 共 9 个核心服务全部 healthy；知识库管理接口受认证保护。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 知识库相关套件 | Maven 3.9.9 / Temurin 17 固定摘要容器执行 KnowledgeBase Management、Controller、Chunk 与 VectorStore 测试 | 13/13 通过；最终修正后管理与 Controller 8/8 再次通过 |
| Backend 完整套件 | `maven@sha256:f58d59b6273e785ac0a4477f6e9b5ba1d7731c75b906c0f7b34076f1851318cc` 容器执行 `mvn -B -ntp test` | 655/655，BUILD SUCCESS |
| Frontend 相关套件 | Node Test Runner 执行知识库管理工具和 SFC 契约测试 | 5/5 通过 |
| Frontend 统一质量门 | `cd frontend && npm test` | ESLint、`vue-tsc`、308 个覆盖率测试、生产构建、1 个 E2E 全部通过 |
| 统一重建 | 提供未输出、未落盘的临时签名密钥后执行 `docker compose up --build -d` | 构建成功；首次启动因 80 端口冲突失败，处理后原命令重试成功 |
| 运行态检查 | 容器健康状态与未认证请求 `/api/knowledge-bases/management` | 9 个核心服务 healthy；接口返回 401 |
| 静态检查 | `git diff --check`、TODO/FIXME/console/debug 搜索和 `git status --short` | 通过；功能提交后工作区干净 |

### 测试过程问题与处理

- 宿主未安装 Maven；改用固定摘要的 Maven 3.9.9 / Temurin 17 容器和项目 Maven 缓存执行相关及完整套件，未修改宿主依赖。
- 未提供 `PLUGIN_SANDBOX_EGRESS_SIGNING_KEY` 时，Compose 在变量插值阶段按预期拒绝执行。验证时使用未输出、未落盘的随机 256-bit 临时值；该值未写入配置或仓库。
- 统一重建首次启动 Caddy 时发现 `domestic-trade-caddy` 占用 80 端口；按规范精确停止该容器后重试成功。最终检查时该外部容器已被其他操作恢复并处于 healthy，当前项目 9 个核心服务仍全部 healthy。
- 一次健康轮询脚本误用 zsh 只读变量 `status`，诊断脚本立即失败；改用任务专用变量后检查通过，不影响产品容器或测试结果。
- Frontend 生产构建继续出现既有的运行配置脚本、第三方 PURE 注释和大分块提示；均为非阻断警告，构建和 E2E 通过。
- 未创建或遗留临时测试、调试文件；正式新增测试已随功能提交。

### 未执行的测试

- 本次未修改 Python Worker、Dify/n8n Plugin Worker、Go 服务、数据库迁移、供应链配置或镜像基础层，因此未重跑这些模块的独立单元测试、`npm audit`、Actionlint、Trivy 源码/镜像扫描及可选插件 profile；其最近结果保留在上一基准报告，不计入本次 964 个用例。
- 未执行大数据量 MySQL 性能压测、并发批量上传压测和真实向量服务内容校验；当前验收以 H2 业务集成测试、完整回归、生产构建 E2E 与容器健康为依据。

### 已知问题与限制

- `PLUGIN_SANDBOX_EGRESS_SIGNING_KEY` 仍是 Compose 必填变量，本机未持久化；后续 Compose 操作前必须从密钥管理或受保护环境提供至少 32 个字符的值。
- 多文件上传复用现有单文件接口并顺序执行，单次最多 20 个、单文件最多 10 MB；本次未引入并行上传、断点续传或后台任务。
- 批量删除单次最多 100 条并逐项执行，部分失败时保留已成功删除结果；该操作不是跨外部向量存储的原子事务。
- 管理统计使用当前权限范围内的聚合查询；未新增数据库索引。大数据量下的查询耗时需要结合生产数据另行压测。
- 本次未提供文档内容预览、向量重建、批量重建或工作流选择器改版。

### 回滚方式

- 使用 `git revert af666ba38d1b7250554061f66c0f02d4a3a08346` 回滚功能代码与测试，不得使用破坏工作区的强制重置；测试报告提交可独立回滚。
- 本次无数据库迁移、新依赖或持久化结构变更，回滚不需要数据处理；回滚后重新执行 `docker compose up --build -d` 和 Backend/Frontend 完整测试。
- 回滚只恢复旧知识库展示和维护能力，不删除现有知识库、文档或向量数据。

### 下次测试建议

- 使用接近生产规模的 MySQL 数据验证多条件筛选、文档计数聚合和深分页性能，必要时基于执行计划增加索引或改为游标分页。
- 增加已登录用户的浏览器端知识库 E2E，覆盖创建、编辑、20 文件队列、失败重试、跨页批量删除和管理员只读视图。
- 若后续改为并行上传或后台任务，应补充并发上限、取消、重试幂等、部分成功恢复和外部向量服务超时测试。
- 若改动 Worker、插件、Go 服务、依赖锁或基础镜像，应重新执行对应独立测试、供应链审计与九镜像扫描。

## 插件逐包独立沙箱测试结果（2026-08-31）

### Git 基准点

Commit: e9377369e9c8fee18d4133306f0737468852ebc3
- 提交信息: `Isolate plugin execution in dedicated sandboxes`
- 上一测试报告基准点: `cb57855dd345a18b75cc5e50d97f3a7dd506337a`
- 基准差异检查: `git diff cb57855dd345a18b75cc5e50d97f3a7dd506337a e9377369e9c8fee18d4133306f0737468852ebc3 -- backend/src/main/java/` 有输出，共 4 个业务代码文件、38 行新增、9 行删除，已触发完整重测
- 测试日期: 2026-08-31
- 分支: master
- 未执行 `git push`

### 变更范围

- Dify 与 n8n 常驻 Worker 退化为受鉴权控制面，只把固定类型的 `inspect`、`invoke`、`remove` 请求转发到来源专用 Unix Socket；不再执行第三方代码，不再挂载插件包目录，也不持有长期代理凭据。
- Adapter Docker Broker 为每个来源和包指纹创建独立持久卷；每次探测或调用创建新的非 root、只读、去能力、限 CPU/内存/PID 的一次性容器及独占内部网络。调用阶段只读挂载包卷，容器不接收 Docker Socket 或 Broker Socket。
- Broker 为每次操作签发短时 HMAC 代理令牌，绑定来源、包指纹、操作、精确域名、有效期和随机数；出站网关将令牌域名与运维全局白名单取交集，拒绝 IP、通配符、子域扩张、过期和篡改令牌。
- Backend 从已批准的 `external_services_json` 提取运行域名并随调用下发；Dify/n8n Worker ABI 分别升级到 7/6。V24 迁移只重排旧的已完成探测，保留插件、组件和人工准入数据，避免复用历史共享目录中的缓存。
- Adapter Broker 最终镜像改为 scratch，只包含项目 Broker 和从固定版本、校验 SHA-256 的 Docker CLI 源码以 Go 1.26.6 构建的二进制，不再携带 buildx、compose 或 Alpine 运行层。
- n8n 沙箱保留依赖安装所需 npm 12.0.2，并将其自带的 `brace-expansion`、`ip-address`、`tar` 精确更新到已修复版本；corepack 与 yarn 继续移除。
- 按用户确认明确不包含：MySQL 未验证服务端身份、PostgreSQL 未验证 TLS、Redis 未启用 TLS；本次未改变这三项连接策略。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与用例 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 第三方插件不在共享常驻进程执行 | Dify/n8n Sandbox Client、Server 与 Compose 契约测试；可选 Worker 运行态健康检查 | Worker 只转发有限 JSON；第三方代码由一次性容器执行；通过 | 正常、安全、架构 |
| 不同插件不共享包目录和依赖缓存 | Adapter `TestSandboxArgumentsProvidePerFingerprintIsolation`、双插件运行态验证 | 不同指纹映射不同卷，各自只能看到自己的指纹与缓存；通过 | 正常、并发、隔离 |
| 插件不能获得 Docker 控制面或长期凭据 | Adapter 参数测试、Frontend Compose 契约、`docker inspect` 运行态检查 | 沙箱无 Docker/Broker Socket；控制 Worker 无包卷、代理令牌和签名密钥；通过 | 权限、安全、恶意输入 |
| 插件出站权限按操作和精确域名收敛 | Gateway `TestGatewayScopesSandboxCredential`、令牌过期/域名测试、真实代理验证 | 获批精确域名可访问，其他全局允许域名及子域扩张被拒绝；通过 | 权限、安全、边界 |
| 调用方不能注入镜像、命令、挂载或网络 | Adapter `TestSandboxBrokerRejectsCallerControlledDockerFields`、请求严格解析测试 | 未知 Docker 字段在执行前返回 400，Runner 无调用；通过 | 恶意输入、异常 |
| 依赖安装能力在隔离后保持可用 | Dify 25 个、n8n 16 个完整 Worker 测试及两个可选镜像健康检查 | 固定依赖安装、探测、调用和缓存兼容均通过；通过 | 正常、兼容、回归 |
| 历史共享缓存不会迁移到独立卷 | Backend `WorkflowSchemaResourceTest` 与真实 Flyway 启动 | V24 将 COMPLETE 重排为 QUEUED 并清空探测缓存字段；真实库从 23 升至 24；通过 | 数据迁移、安全、兼容 |
| Adapter Manager 镜像不存在 CI 阻断漏洞 | Dockerfile 安全契约、Docker CLI 运行态版本、Trivy 镜像扫描 | Broker 仅含两个 0 漏洞 Go 二进制；原 OS/CLI/插件 HIGH 已清零；通过 | 供应链、安全 |
| n8n 镜像不存在已修复高危依赖 | Dockerfile版本契约、运行态 npm 版本读取、Trivy 镜像扫描 | npm 12.0.2；依赖为 5.0.9/10.3.1/7.5.21；HIGH/CRITICAL 为 0；通过 | 供应链、安全 |
| 全环境可统一重建并健康运行 | `docker compose up --build -d`、核心服务健康和可选 profile 启停 | 9 个核心服务 healthy；两个插件 Worker 可 healthy 且按默认 profile 停止；通过 | 构建、部署、回归 |

### 测试执行结果

- 总测试用例：1083 个。
- 通过：1083 个（100%）；失败 0；错误 0；跳过 0。
- Backend：647/647；其中 Workflow 包 262/262、Service 包 163/163、Controller 包 25/25，Domain 与 Repository 继续由完整服务、持久化和 Schema 测试间接覆盖。
- Frontend：303/303；生产服务 E2E 1/1。工具函数覆盖率：行 97.94%、分支 79.95%、函数 95.95%，均高于 95%/75%/90% 阈值。
- Python Worker（Python 3.12）：77/77。
- Dify Plugin Worker：25/25；n8n Plugin Worker：16/16。
- Adapter Manager/Broker/Supervisor：11/11；Outbound Gateway：3/3。
- `npm audit`：0 漏洞；Actionlint、Compose 配置校验和 Go 格式检查全部通过。
- Trivy 源码扫描：依赖、Dockerfile 高危误配置和密钥均为 0 个可修复 HIGH/CRITICAL 发现。
- Trivy 镜像扫描：Backend、Document Parser、Frontend、Python Worker、Dify Worker、n8n Worker、Adapter Manager、Outbound Gateway、Caddy 共 9/9 均为 0 个可修复 HIGH/CRITICAL 发现。
- 运行态：9 个核心服务全部 healthy；Dify/n8n Worker 短暂启动后均 healthy，确认无 Docker Socket、包卷和长期代理凭据，再以 SIGTERM 正常停止。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整套件 | Maven 3.9.9 / Temurin 17 固定摘要容器执行 `mvn -B -ntp test` | 647/647，BUILD SUCCESS |
| Frontend 统一质量门 | `cd frontend && npm test` | ESLint、`vue-tsc`、303 个覆盖率测试、生产构建、1 个 E2E 全部通过 |
| Frontend 供应链 | `cd frontend && npm audit` | found 0 vulnerabilities |
| Python Worker | Python 3.12 固定摘要容器按哈希安装 `requirements-dev.txt` 后执行 `pytest tests -q` | 77/77 通过 |
| Dify Worker | Python 3.12 固定摘要容器执行 `python -m unittest discover -s tests -v` | 25/25 通过 |
| n8n Worker | `npm test` | 16/16 通过 |
| Go 服务 | Go 1.26.6 固定摘要容器分别执行 `go test ./...` | Adapter 11/11、Gateway 3/3 通过 |
| Go 格式 | Go 1.26.6 固定摘要容器执行 `gofmt -d` 并断言无输出 | 通过 |
| Workflow 语法 | Actionlint 容器检查全部 Workflow | 通过 |
| Compose 配置 | 设置非生产测试签名密钥后执行 `docker compose config --quiet` | 通过 |
| 统一重建 | 设置临时签名密钥执行 `docker compose up --build -d` | 首次因 80 端口占用失败；精确停止占用容器后按原命令重试成功 |
| 可选插件运行态 | `docker compose --profile plugin-adapters up -d --wait`，检查后 `stop` | Dify/n8n 均 healthy，隔离配置符合预期，随后正常停止 |
| 数据迁移 | Backend 启动日志中的 Flyway 校验和迁移记录 | 成功校验 24 个迁移，V24 从 23 应用到 24 |
| 沙箱集成 | 两个不同指纹插件执行探测/调用，并检查卷、环境和代理行为 | 各自只见独立缓存；无长期凭据/控制 Socket；精确域名允许，非令牌域名拒绝 |
| 源码安全扫描 | Trivy FS：`vuln,misconfig,secret`，`HIGH,CRITICAL`，`ignore-unfixed` | 0 个阻断发现 |
| 交付镜像扫描 | 按 CI 矩阵逐个构建 9 个镜像，Trivy 扫描 OS + Library | 9/9 均为 0 个阻断发现 |
| Adapter CLI 运行态 | Broker 内执行 `docker version` | Client 29.7.2 / Go 1.26.6，Server 29.7.2 |
| n8n npm 运行态 | 镜像内读取 npm 及三项修复依赖版本 | 12.0.2；5.0.9、10.3.1、7.5.21 |
| 静态检查 | `git diff --check`、`git status --porcelain=v1` | 通过；功能提交后仅测试报告待提交 |

### 测试过程问题与处理

- CI 报告 `Container image (adapter-manager)` 失败。本地按工作流参数复现：上游 Docker CLI 镜像含 12 个 Alpine HIGH，Docker CLI 9 个 HIGH，并额外携带存在漏洞的 buildx/compose。改为校验源码后以 Go 1.26.6 编译最小 CLI、scratch 运行层，复扫两个二进制均为 0。
- n8n 镜像首次复扫发现 npm 自带 `brace-expansion`、`ip-address`、`tar` 共 4 个 HIGH。仅升级 npm 仍未消除，随后用 npm 自身依赖解析精确覆盖到修复版本，保持插件依赖安装能力，复扫为 0。
- 一次 n8n 版本诊断把 npm 版本文本与 JSON 同管道交给 `jq`，导致诊断命令解析失败；拆分版本读取与 JSON 读取后通过，产品测试和镜像均未失败。
- `docker compose up --build -d` 首次启动 Caddy 时发现 `domestic-trade-caddy` 占用 80/443。按项目规范精确停止该容器后原命令重试成功；该外部容器未自动恢复，避免再次抢占当前项目端口。
- Docker Desktop 在较早的并发集成验证中曾异常退出；重启后降低并发并完整重跑受影响测试、Compose 构建和九镜像扫描，最终结果全部通过。
- Python 测试只读挂载源码，pytest 报告无法写 `.pytest_cache` 的单条非功能性告警；77 个测试全部通过，仓库未生成缓存文件。
- 所有临时扫描镜像、试验性 Docker CLI 镜像和 Trivy 缓存卷均已删除；未遗留临时测试或调试文件。

### 已知问题与限制

- 按用户明确决定保留的风险：MySQL 未验证服务端身份、PostgreSQL 未验证 TLS、Redis 未启用 TLS。若部署跨越不可信网络，应单独安排证书、服务端名称校验和 Redis TLS 兼容验证。
- 新增 `PLUGIN_SANDBOX_EGRESS_SIGNING_KEY` 为强制独立密钥，至少 32 个字符，只能由 Broker 与出站网关持有。本机 `.env` 尚未持久化该值；当前运行容器使用未输出、未落盘的随机 256-bit 值，下次 Compose 操作前必须通过密钥管理或 `.env` 提供新值。
- `domestic-trade-caddy` 因 80/443 端口冲突已停止且未自动恢复；恢复它之前必须先停止当前 `ai-caddy` 或调整双方端口。
- Dify 与 n8n Plugin Worker 是可选 profile 服务，默认停止；单元测试、镜像构建/扫描以及短暂 healthy 运行验证已完成。
- 每个唯一来源 + 包指纹使用一个持久卷；字节完全相同的同源包复用同一只读运行内容。不同指纹、不同来源及每次调用的容器与网络均隔离。
- Trivy 使用 `ignore-unfixed=true`；本报告的“0”表示当前漏洞库中存在修复版本的 HIGH/CRITICAL 发现为 0，不代表未来数据库不会新增发现，也不代表无修复版本的风险不存在。

### 回滚方式

- 使用 `git revert e9377369e9c8fee18d4133306f0737468852ebc3` 回滚代码和配置，不得使用破坏工作区的强制重置；回滚会重新暴露共享插件进程、共享缓存和长期代理凭据风险。
- V24 已由 Flyway 应用，不得删除或改写迁移历史。代码回滚后可保留已排队探测记录；若需恢复新的隔离实现，应通过后续前向迁移调整状态。
- 回滚前先停止可选插件 Worker，并确认没有标签为 `base-ai.plugin-sandbox=true` 的短生命周期容器或网络正在执行；持久插件卷不会由代码回滚自动删除。

### 下次测试建议

- 每次 Docker CLI、Go、Node/npm、基础镜像摘要或 Trivy 漏洞库更新后，重复执行源码扫描和九镜像矩阵。
- 增加 CI 中的真实 Docker Engine 沙箱集成作业，覆盖两个并发插件、超时强杀、Broker 重启、网关重连和持久卷恢复。
- 如后续支持插件声明通配符域名，必须保持默认拒绝并新增公共后缀、IDN、DNS 重绑定和子域接管测试；当前实现只接受精确 ASCII DNS 名称。
- 若后续决定处理三项排除风险，应分别准备可信 CA、服务端名称、Redis TLS 端点和旧环境回滚连接串。

## 项目风险全量修复测试结果（2026-08-30）

### Git 基准点

Commit: cb57855dd345a18b75cc5e50d97f3a7dd506337a
- 提交信息: `Apply runtime image security updates`
- 变更提交: `0cf7e29`、`3d3c8f0`、`8e018bb`、`0077c60`、`773c32b`、`83b7455`、`12328a7`、`cb57855`
- 上一测试报告基准点: `0ef3d6c`
- 基准差异检查: `git diff 0ef3d6c HEAD -- backend/src/main/java/` 有输出，共 19 个业务代码文件、904 行新增、128 行删除，已触发完整重测
- 测试日期: 2026-08-30
- 分支: master
- 未执行 `git push`

### 变更范围

- 恢复 MySQL V1~V23 版本化迁移链，避免已部署数据库因重写基线产生 Flyway 校验失败；强化数据库初始化、密码策略和敏感配置校验。
- Backend、Python Worker、插件 Worker、Adapter Manager 之间的内部请求统一使用带时间窗和防重放能力的签名认证；内部接口不再依赖静态共享 Header。
- 工作流和知识库外部请求统一经过出站网关，网关严格执行 Host/CIDR 策略与代理凭据校验，缺少代理配置时禁止回退直连。
- Docker Socket 仅由隔离的 Adapter Docker Broker 持有；Manager 与 Supervisor 通过受控 Unix Socket 和固定类型命令转发，不接受任意 Docker 参数。
- 不可信文档解析迁移到独立无网络、只读、非 root、资源受限的 Document Parser 容器，通过共享 Unix Socket 通信，并限制嵌入文档与解析超时。
- 前端统一质量门包含 ESLint、Vue 类型检查、覆盖率阈值、生产构建和真实服务 E2E；修复畸形 URL 崩溃及路径穿越处理，补齐供应链审计和固定摘要镜像。
- RabbitMQ Client 升级至 5.33.1，Apache HttpCore 5 升级至 5.4.3，Go 升级至 1.26.6；更新 Python/Node/JRE 固定摘要并安装系统安全更新，移除最终镜像中不需要的 Pebble、npm、corepack 和 yarn。
- 安全工作流覆盖依赖、Dockerfile 配置、密钥和 9 个交付镜像，所有 Action 均固定到提交摘要。
- 按用户确认明确不包含：MySQL 未验证服务端身份、PostgreSQL 未验证 TLS、Redis 未启用 TLS；本次未针对这三项改变连接策略。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与用例 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 历史数据库可继续使用版本化迁移 | Backend 迁移资源测试、完整套件、容器启动 | V1~V23 保持可解析，Backend 完成 Flyway 校验并 healthy；通过 | 兼容、部署、回归 |
| 数据库启动与管理员初始化使用安全默认值 | Backend 配置、密码策略、初始化和健康检查测试 | 缺失/弱配置拒绝启动，敏感值不泄漏，合法配置正常初始化；通过 | 正常、边界、异常、安全 |
| 内部请求不可伪造、篡改、过期或重放 | Backend、Python、Dify、n8n 内部认证测试 | 合法签名仅接受一次，过期及正文篡改均拒绝；通过 | 权限、安全、恶意输入 |
| 业务外连必须经过受控网关 | Backend 出站客户端、Go Gateway、n8n Proxy Fetch 测试 | 私网/未授权目标拒绝，代理认证正确，缺配置不直连；通过 | 权限、安全、异常、兼容 |
| 插件容器不能直接控制 Docker | Go Adapter 8 个测试、前端部署契约、Compose 运行态检查 | 仅 Broker 挂载 Socket，命令类型和服务名均受白名单约束；通过 | 权限、安全、回归 |
| 不可信文档解析与主服务隔离 | Backend 解析协议/Worker/Unix Client 测试、Compose 健康与隔离检查 | 解析器无网络、只读、非 root、限时限资源，Backend 仅访问 Socket；通过 | 安全、异常、边界、部署 |
| 前端生产服务安全处理请求 | Frontend 301 个单元/契约测试、1 个真实服务 E2E | 畸形 URL、路径穿越、运行配置、SPA 和 API 代理行为正确；通过 | 正常、边界、安全、回归 |
| 依赖和镜像不存在可修复高危漏洞 | `npm audit`、Trivy FS 与 9 镜像扫描 | npm 0 漏洞；HIGH/CRITICAL 已修复漏洞、误配置和密钥均为 0；通过 | 供应链、安全 |
| 全环境可由统一命令重建并运行 | `docker compose up --build -d`、`docker compose ps` | 9 个核心服务全部 healthy；两个可选插件 Worker 按 profile 保持停止；通过 | 构建、部署、回归 |
| 用户排除项不被本次变更扩大 | 配置 diff 与变更检查 | 三项排除风险未作为本次验收条件，也未引入额外绕过；通过 | 范围、兼容 |

### 测试执行结果

- 总测试用例：1071 个。
- 通过：1071 个（100%）；失败 0；错误 0；跳过 0。
- Backend：645/645；其中 Service 包 163/163、Controller 包 25/25，Domain 与 Repository 通过业务服务、持久化及 Schema 测试间接覆盖，项目没有独立同包测试套件。
- Frontend：301/301；生产服务 E2E 1/1。工具函数覆盖率：行 97.94%、分支 79.95%、函数 95.95%，均高于 95%/75%/90% 阈值。
- Python Worker（Python 3.12）：77/77。
- Dify Plugin Worker：23/23；n8n Plugin Worker：14/14。
- Adapter Manager/Broker/Supervisor：8/8；Outbound Gateway：2/2。
- `npm audit`：0 漏洞。
- Trivy 源码扫描：6 份语言依赖清单 0 个 HIGH/CRITICAL 已修复漏洞，8 份 Dockerfile 0 个对应高危误配置，密钥 0。
- Trivy 镜像扫描：Backend、Document Parser、Frontend、Python Worker、Dify Worker、n8n Worker、Adapter Manager、Outbound Gateway、Caddy 共 9/9 镜像均为 0 个 HIGH/CRITICAL 已修复漏洞。
- 完整重建后 Adapter Docker Broker、Adapter Supervisor、Adapter Manager、Outbound Gateway、Document Parser、Python Worker、Backend、Frontend、Caddy 共 9 个核心服务全部 healthy。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整套件 | Maven 3.9.9 / Temurin 17 固定摘要容器执行 `mvn -B -ntp test` | 645/645，BUILD SUCCESS |
| Frontend 统一质量门 | `cd frontend && npm test` | ESLint、`vue-tsc`、301 个覆盖率测试、生产构建、1 个 E2E 全部通过 |
| Frontend 供应链 | `cd frontend && npm audit` | found 0 vulnerabilities |
| Python Worker | Python 3.12 固定摘要容器按哈希安装 `requirements-dev.txt` 后执行 `pytest -q` | 77/77 通过 |
| Dify Worker | `python3.12 -m unittest discover -s tests -v` | 23/23 通过 |
| n8n Worker | `node --test tests/*.test.mjs` | 14/14 通过 |
| Go 服务 | Go 1.26.6 固定摘要容器分别执行 `go test ./...` | Adapter 8/8、Gateway 2/2 通过 |
| Workflow 语法 | Actionlint 容器检查全部 Workflow | 通过 |
| 统一重建 | `docker compose up --build -d` | 构建并启动成功，9 个核心服务 healthy |
| 可选插件镜像 | `docker compose --profile plugin-adapters build dify-plugin-worker n8n-plugin-worker` | 两个镜像构建成功 |
| 源码安全扫描 | Trivy FS：`vuln,misconfig,secret`，`HIGH,CRITICAL`，`ignore-unfixed` | 依赖、配置、密钥均为 0 |
| 交付镜像扫描 | Trivy Image：9 镜像，OS + Library，`HIGH,CRITICAL`，`ignore-unfixed` | 9/9 均为 0 |
| 运行态检查 | `docker compose ps` 及容器健康检查 | 9 个核心服务 healthy；插件 Worker 按 profile 停止 |
| 静态检查 | `docker compose config --quiet`、`git diff --check`、`git status --short` | 通过；报告更新前工作区干净 |

### 测试过程问题与处理

- 宿主 Python 3.12 未安装 pytest、宿主未安装 Go；改用项目固定摘要的 Python 3.12 与 Go 1.26.6 容器执行，未安装宿主依赖。
- 首次 Trivy 镜像扫描下载 Java 漏洞库时出现远端 `unexpected EOF`；保留 Java 扫描并使用持久缓存重试，随后成功定位并修复 RabbitMQ、HttpCore 与 Pebble 问题。
- 漏洞库更新后继续发现 Python/Node/Caddy 基础系统包问题；更新固定摘要、安装安全更新、移除不需要的运行时工具后重新构建并全量复扫，最终清零。
- Python 测试以只读方式挂载源码，pytest 报告无法写 `.pytest_cache` 的单条非功能性告警；77 个业务测试全部通过，仓库未生成缓存文件。
- Frontend 构建存在既有的运行配置脚本、第三方 PURE 注释和大分块提示；不影响构建与 E2E，通过率和覆盖率均达标。
- 一次 `npm audit` 从仓库根目录误执行，因缺少根锁文件在用户 npm 日志目录生成调试日志；已删除该日志并从 Frontend 正确重跑，结果为 0 漏洞。
- 未遗留临时测试、调试或未跟踪文件。

### 已知问题与限制

- 按用户明确决定保留的风险：MySQL 未验证服务端身份、PostgreSQL 未验证 TLS、Redis 未启用 TLS。若部署跨越不可信网络，应单独安排证书、服务端身份校验和兼容性验证。
- Trivy 与仓库安全工作流使用 `ignore-unfixed=true`；本报告的“0”表示当前漏洞库中存在修复版本的 HIGH/CRITICAL 发现为 0，不代表未来漏洞库不会新增发现，也不代表无修复版本的风险不存在。
- Dify 与 n8n Plugin Worker 是可选 profile 服务，默认不启动；其源码测试、镜像构建和镜像扫描已完成，但未作为常驻容器运行。
- Backend 未配置统一行覆盖率聚合，本次以 645 个完整测试、验收映射、运行态健康和镜像扫描作为覆盖证据。

### 回滚方式

- 代码与配置可按 `cb57855` → `12328a7` → `83b7455` → `773c32b` → `0077c60` → `8e018bb` → `3d3c8f0` → `0cf7e29` 的逆序使用 `git revert` 回滚；不得使用破坏工作区的强制重置。
- 回滚内部签名、Broker、出站网关或解析器隔离会重新暴露对应安全风险，必须同步回滚 Compose 拓扑并重新执行完整测试与镜像扫描。
- MySQL 迁移链恢复不包含数据删除；如回滚迁移文件，必须先核对目标库 `flyway_schema_history`，禁止直接覆盖已执行迁移或清库。

### 下次测试建议

- 每次依赖锁、基础镜像摘要或安全数据库更新后重复运行 Trivy FS 与 9 镜像矩阵，避免新披露漏洞滞留。
- 若后续决定处理三项排除风险，应分别准备可信 CA、服务端名称、Redis TLS 端点和回滚连接串，并覆盖证书过期、名称不匹配、降级与旧环境兼容场景。
- 建议在 CI 中保留 Frontend 覆盖率阈值、Actionlint、完整 Backend 套件和镜像矩阵为合并阻断条件。

## 数据库迁移合并为单一基线测试结果（2026-08-24）

### Git 基准点

Commit: 0ef3d6c
- 业务代码提交: `0ef3d6c`（Consolidate MySQL migrations into a single baseline）
- 测试日期: 2026-08-24
- 分支: master
- 未执行 `git push`

### 变更范围

- MySQL 迁移 V1~V23 合并为单一 `V1__create_platform_schema.sql`，直接建出 48 张表的最终结构，并按原顺序写入 40 个内置节点模板；`workflow_definition` 与 `workflow_version` 的循环外键仍以建表后 `ALTER TABLE` 补充。
- 删除 V2~V23 共 22 个增量迁移文件。历史演进动作（V3 模型类型规范化、V5 旧限流列兼容、V7~V9 来源分类回填、V17 市场插件重分类、V18 存量插件停用、V19/V20 展示元数据回填、V21 期限回填）在最终结构中不再存在，随文件一并移除。
- 停止对 PostgreSQL 执行 Flyway 迁移：`DatabaseConfig.postgresqlDataSource` 不再调用 `migrate`，删除 `db/migration/postgresql/`。PostgreSQL 数据源与 `HealthService` 就绪探测保持不变。
- 删除三个未被任何代码引用的游离脚本：`api-trigger-schema.sql`、`system-schema.sql`、`database/postgresql/api-trigger.sql`，并同步修正 README 中英文的数据库章节与仓库结构。
- 测试适配：`ApiTriggerServicePersistenceTest` 改为从基线脚本截取接口触发章节；`TraceSchemaResourceTest`、`WorkflowSchemaResourceTest` 改为针对基线断言最终结构。

### 生产库处置

- 远程 MySQL `111.228.33.161/base-ai` 按确认结论清库重建：`DROP DATABASE` 后以 `utf8mb4 / utf8mb4_unicode_ci` 重建，由新基线全新建表。
- 清库前完整备份（含数据，4.2 MB）保存在 `/tmp/base-ai-full-backup.sql`，未纳入版本库。
- PostgreSQL `base-ai.master` 未做任何改动。经核查该 Schema 与 `auto` 项目共用，其 `flyway_schema_history` 含 auto 追加的 V2~V6，且 auto 的 `ApiTriggerService` 带 `@Qualifier("postgresqlJdbcTemplate")` 仍在读写 `automation_api_trigger_config` / `_log`，故两张表保留不删。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与用例 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 合并脚本与原迁移链产生相同表结构 | 运行态：临时 MySQL 8.4 容器 A/B 对照，`mysqldump --no-data` 逐字节 diff | 48 张表结构完全一致，diff 无输出；通过 | 兼容、等价性 |
| 合并脚本与原迁移链产生相同种子数据 | 运行态：`workflow_node_template` 全列 + 插入顺序 diff | 40 行、全部 17 列、ID 分配顺序完全一致；通过 | 兼容、等价性 |
| 基线在真实空库上可完整执行 | 运行态：清库后容器启动，Flyway 迁移 | `flyway_schema_history` 仅 1 条 `version=1 success=1`，49 张表（48 业务表 + 历史表）；通过 | 正常、部署 |
| 基线结构与 JPA 实体映射一致 | 运行态：`spring.jpa.hibernate.ddl-auto=validate` 启动校验 | 后端启动成功并转为 healthy，无 validate 异常；通过 | 兼容、回归 |
| 数据初始化在全新库上正常播种 | 运行态：`sys_menu` / `sys_user` / `workflow_node_template` 计数 | 97 个菜单、1 个管理员、40 个模板；通过 | 正常 |
| 线上重建结构等于原迁移链结果 | 运行态：线上 `mysqldump` 与临时库原链结果 diff | 917 行逐字节一致；通过 | 兼容、端到端 |
| 基线包含全部工作流表与原生节点 | Backend：`containsVersionedWorkflowSchemaAndBuiltInNodes`、`containsNativeWorkflowExtensionSchemaAndNodes` | 5 张核心表齐备，40 个原生节点类型全部出现；通过 | 正常 |
| 循环外键在建表后补充 | Backend：`addsCircularVersionForeignKeysAfterTableCreation` | `ALTER TABLE` 位置晚于 `workflow_version` 建表，两个外键均声明；通过 | 边界、正确性 |
| 内置模板逐行提供非空展示元数据 | Backend：`seedsEveryBuiltInTemplateWithLocalizationJson` | `'{}'` 出现 40 次，等于原生节点数；通过 | 边界、异常预防 |
| 内置模板分类不超出目录常量 | Backend：`seedCategoriesStayWithinCatalog` | 40 行分类全部命中 `WorkflowTemplateCatalog.CATEGORIES`；通过 | 兼容、回归 |
| 市场专有节点类型不得预置 | Backend：`declaresTemplateSourceAndFunctionalCategory` | 7 个 `MARKETPLACE_ONLY` 类型均未出现在基线中；通过 | 权限安全、边界 |
| 基线保留插件准入与探测缓存约束 | Backend：`addsPluginAdmissionControl`、`addsMarketplacePluginProbeCache`、`addsMarketplacePluginRuntimeSchema` | 准入表、探测队列、唯一键与租约列齐备，插件默认 `PENDING`；通过 | 权限安全、回归 |
| 基线保留投递恢复与调度分发状态 | Backend：`containsDeliveryRecoveryAndDispatchState` | `delivery_status`、`deadline_at NOT NULL`、`idx_workflow_run_dispatch`、调度状态表齐备；通过 | 回归 |
| 接口触发持久化逻辑不受合并影响 | Backend：`ApiTriggerServicePersistenceTest` 27 个用例（含 2 组参数化） | 从基线截取章节建表后全部通过；通过 | 完整回归 |
| 追踪与密钥密文列在基线中保持最终形态 | Backend：`TraceSchemaResourceTest` 2 个用例 | `task_trace`、`trace_log`、`secret_encrypted TEXT NOT NULL` 齐备；通过 | 兼容 |
| PostgreSQL 停用迁移后不影响就绪探测 | Backend：`HealthServiceTest` 4 个用例；运行态：`/api/open/health/ready` | 单元测试通过，容器内探测返回 `{"status":"UP"}`；通过 | 回归、部署 |
| 历史后端功能无回归 | Backend：完整测试套件 | 620/620 通过；通过 | 完整回归 |
| 前端无回归 | Frontend：默认套件与扩展套件 | 130/130、168/168 通过；通过 | 完整回归 |

### 测试执行结果

- 总测试用例：918 个（Backend 620 + Frontend 298）。
- 通过：918 个（100%）；失败 0；错误 0；跳过 0。
- 关键模块：Schema 资源层 `WorkflowSchemaResourceTest` 16/16、`TraceSchemaResourceTest` 2/2；Service 层 `ApiTriggerServicePersistenceTest` 27/27；配置层 `HealthServiceTest` 4/4。
- 用例数由 626 降为 620，减少的 6 个见下方"删除的失效用例"。
- 未执行：Python Worker 测试套件。本次未改动 `python-worker/`，与变更无关。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 合并等价性 A/B 对照 | 临时容器 `mysql:8.4`：`db_legacy` 顺序执行原 V1~V23，`db_merged` 执行合并 V1，两库 `mysqldump --no-data` 与模板全列导出 diff | 结构与种子数据均无差异 |
| 后端完整套件 | `docker run --rm -v <backend>:/workspace -v ~/.m2:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -ntp clean test` | 620/620 通过，BUILD SUCCESS |
| 服务重建与启动 | `docker compose up --build -d` | 6 个容器全部 healthy |
| 线上库校验 | `mysqldump --no-data` 与 `flyway_schema_history`、业务表计数查询 | 单条迁移记录，49 张表，40 个模板，结构与原链一致 |
| 就绪探测 | 容器内 `curl /api/open/health/ready`、宿主 `curl /health` | 均返回 `{"status":"UP"}` |
| 前端默认套件 | `npm test`（`tests/*.test.js`） | 130/130 通过 |
| 前端扩展套件 | `node --test test/*.test.mjs` | 168/168 通过 |

### 删除的失效用例

以下用例的被测对象是增量迁移脚本对存量数据的改写行为，合并后被测对象不复存在，经确认后删除。最终结构、种子数据和业务持久化逻辑的覆盖不受影响。

| 删除项 | 原验证内容 |
| --- | --- |
| `WorkflowPluginAdmissionMigrationTest`（整类 1 个用例） | V18 将存量插件转为待审批停用并记录原启用值 |
| `WorkflowPluginLocalizationMigrationTest`（整类 2 个用例） | V19/V20 为存量组件、模板和运行节点回填 `{}` |
| `WorkflowMarketplaceCategoryMigrationTest`（整类 1 个用例） | V17 重分类误放在网络分类的市场插件 |
| `TraceSchemaResourceTest.normalizesLegacyModelTypesWithoutChangingCanonicalValues` | V3 将历史 `model_type` 规范化 |
| `WorkflowSchemaResourceTest.relaxesLegacyApiKeyRateLimitColumn` | V5 兼容仍保留 `rate_limit_per_minute` 的历史库 |
| `WorkflowSchemaResourceTest.normalizesNativeWorkflowTemplateSources` | V8 将模板来源默认值改为 `CUSTOM`（已被 V9 覆盖） |
| `WorkflowSchemaResourceTest.restoresConfigurableWorkflowTemplateSources` | V9 恢复三种可配置来源，断言已并入 `declaresTemplateSourceAndFunctionalCategory` |
| `WorkflowSchemaResourceTest.recategorizesExistingMarketplacePluginTemplates` | V17 重分类规则的文本断言 |

同批新增 4 个用例：`addsCircularVersionForeignKeysAfterTableCreation`、`seedsEveryBuiltInTemplateWithLocalizationJson`、`containsDeliveryRecoveryAndDispatchState`、`seedCategoriesStayWithinCatalog`。

### 已知问题与限制

- 合并后的基线面向全新数据库。任何已执行过 V1~V23 的库直接升级会触发 Flyway `checksum mismatch` 与 `Detected applied migration not resolved locally`，必须清库重建或人工清理 `flyway_schema_history`。本次远程库已按确认结论清库重建。
- PostgreSQL `master` Schema 中 `automation_api_trigger_config` 与 `automation_api_trigger_log` 仍为 `auto` 项目在用，属于跨项目共用资源，本项目不再管理也不得删除。
- 清库前备份仅存放在本机 `/tmp/base-ai-full-backup.sql`，重启后可能被系统清理，如需长期留存请自行转移。

### 下次测试建议

- 若后续新增 PostgreSQL 业务表，需先确认目标 Schema 是否独占，再决定是否恢复 PG 侧 Flyway 迁移链；恢复时应使用独立 Schema 避免与 `auto` 项目冲突。
- 基线脚本已成为唯一 Schema 事实来源，后续任何表结构变更都应新增 `V2` 起的增量迁移，不得直接改写 V1。可考虑增加一个校验 `db/migration/mysql` 目录文件数量与命名的测试，防止误改基线。

## 接口触发器迁移至 MySQL 测试结果（2026-08-24）

### Git 基准点

Commit: 9c010db5287bdf432bb2cdf0794f29b0c02f2270
- 业务代码提交: `6b79411`（Move API trigger storage to MySQL）
- 文档提交: `9c010db`（Update docs for API trigger MySQL storage）
- 测试日期: 2026-08-24
- 分支: master
- 未执行 `git push`

### 变更范围

- 接口触发器的 `automation_api_trigger_config` 与 `automation_api_trigger_log` 由 PostgreSQL 迁移到 MySQL 主库，新增 MySQL 迁移 `V23__move_api_trigger_to_mysql.sql`，采用项目既有的 `BIT(1)`、`DATETIME(6)`、InnoDB / utf8mb4 约定及内联索引与外键。
- `ApiTriggerService` 的数据源限定名由 `postgresqlJdbcTemplate` 改为 `mysqlJdbcTemplate`；`INSERT ... RETURNING id` 改为 `GeneratedKeyHolder` 显式回读 `id` 列；四处 `NOW()` 改为 `CURRENT_TIMESTAMP(6)` 以保留微秒精度。
- 新增消息键 `apiTrigger.createFailed`（中英文）。
- PostgreSQL 连接能力全部保留：数据源 Bean、`POSTGRES_*` 配置、Flyway 迁移链、`HealthService` 就绪探测和 `postgresqlJdbcTemplate` 均未改动；PostgreSQL 侧原有两张表按确认结论保留不动，存量数据不迁移。
- 知识库 pgvector 与工作流 Connector 的 PostgreSQL 连接类型不在本次范围内，未做任何改动。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与用例 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 自增主键回填替代 RETURNING 语法 | Backend：`createReturnsGeneratedIdAndPersistsAllFields`、`createReturnsDistinctIncreasingIds` | 返回正整数主键且连续创建严格递增，全字段可回读；通过 | 正常 |
| 加密字段经 MEDIUMTEXT 列往返无损 | Backend：`encryptedColumnsRoundTripThroughMediumText` | 库内密文不含明文，解密后与原文一致；通过 | 正常、安全 |
| 更新语句在 MySQL 上兼容并推进 updated_at | Backend：`updateModifiesFieldsAndAdvancesUpdatedAt` | 字段全部改写，`updated_at` 晚于创建时间；通过 | 正常 |
| BIT(1) 与 Java 布尔值双向一致 | Backend：`booleanColumnsRoundTripAndVoidedConfigurationIsHidden`、`disableOnlyClearsEnabledFlag`、`listFiltersByEnabledFlag` | 启用、停用、作废三态读写一致，停用不影响作废标记；通过 | 正常、兼容 |
| 作废配置从列表隐藏且不可越权出现 | Backend：`booleanColumnsRoundTripAndVoidedConfigurationIsHidden` | `list` 返回空，直接按 ID 仍可读取历史；通过 | 权限安全 |
| 关键字查询大小写不敏感并支持中文 | Backend：`listMatchesKeywordCaseInsensitively`（参数化 5 组） | `daily`/`DAILY`/`Sync`/`同步` 命中，`absent` 不命中；通过 | 兼容、边界 |
| 调度扫描只返回启用且配置 Cron 的任务 | Backend：`findEnabledReturnsOnlyScheduledConfigurations` | 无 Cron、空 Cron、已停用、已作废四类均被排除；通过 | 边界、回归 |
| 执行成功写日志并回填最近状态 | Backend：`executeWritesSuccessLogAndBackfillsLastStatus` | 日志状态 SUCCESS、HTTP 200、摘要正确，配置 `last_status`/`last_trigger_at` 同步；通过 | 正常 |
| 执行失败写失败日志且不写 HTTP 状态 | Backend：`executeWritesFailureLogWhenRemoteReturnsError` | 状态 FAILED，`http_status` 与摘要为空，错误信息非空；通过 | 异常 |
| 执行摘要按配置上限截断 | Backend：`executionSummaryIsTruncatedToConfiguredLimit` | 上限 8 时写入 `01234567`；通过 | 边界 |
| DATETIME(6) 保证同毫秒内日志稳定倒序 | Backend：`logsKeepStableDescendingOrderWithinSameMillisecond` | 相隔微秒的三条日志按 c→b→a 稳定倒序；通过 | 边界 |
| 日志查询不跨配置泄漏 | Backend：`logsDoNotLeakAcrossConfigurations` | 仅返回本配置日志，`config_id` 一致；通过 | 权限安全 |
| Trace ID 精确过滤不模糊命中 | Backend：`logsFilterByExactTraceId` | `trace-1` 不命中 `trace-12`，两端空白被裁剪，未知 Trace 返回空；通过 | 安全、边界 |
| 不存在的配置统一返回未找到 | Backend：`missingConfigurationIsRejected`（参数化 6 组） | get/disable/void/logs/update/execute 均抛 `apiTrigger.notFound` 且状态 404；通过 | 异常 |
| 已停用配置不可执行 | Backend：`disabledConfigurationCannotBeExecuted` | 抛 `apiTrigger.disabled`；通过 | 异常、权限 |
| 空值边界可写入非空 MEDIUMTEXT 列 | Backend：`blankOptionalFieldsArePersistedAsEmptyText` | 空描述、空请求头、空正文落库为空串，默认值按 DDL 生效，Cron 为 NULL；通过 | 边界 |
| 迁移脚本在真实 MySQL 上可执行 | 运行态：Flyway 迁移记录与 `information_schema` 校验 | V23 成功应用，两表创建，列类型为 `bigint auto_increment`、`bit(1) DEFAULT b'1'`、`datetime(6) DEFAULT CURRENT_TIMESTAMP(6)`、`mediumtext`、`text`；通过 | 兼容、部署 |
| 前端接口触发器页面与接口契约无回归 | Frontend：`api-trigger`、`api-trigger-security`、`navigation` 及全量前端测试 | 全部通过，无接口契约变更；通过 | 回归 |
| 历史后端功能无回归 | Backend：完整测试套件 | 626/626 通过；通过 | 完整回归 |

### 测试执行结果

- 总测试用例：924 个（Backend 626 + Frontend 298）。
- 通过：924 个（100%）；失败 0；错误 0；跳过 0。
- 本次新增：`ApiTriggerServicePersistenceTest` 27 个用例，全部通过，已包含在 Backend 626 中。
- 关键模块：Service 层（`ApiTriggerService` 全部 SQL 路径）27/27；Frontend 接口触发器相关 31/31。
- 未执行：Python Worker 测试套件。本次未改动 `python-worker/`，与变更无关。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 定向新增测试 | `docker run --rm -v <backend>:/workspace -v ~/.m2:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -ntp -Dtest=ApiTriggerServicePersistenceTest test` | 27/27 通过 |
| 后端完整套件 | `docker run --rm -v <backend>:/workspace -v ~/.m2:/root/.m2 -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -ntp test` | 626/626 通过，BUILD SUCCESS |
| 前端默认套件 | `npm test`（`tests/*.test.js`） | 130/130 通过 |
| 前端扩展套件 | `node --test test/*.test.mjs` | 168/168 通过 |
| 前端接口触发器定向回归 | `node --test test/api-trigger.test.mjs test/api-trigger-security.test.mjs test/navigation.test.mjs` | 31/31 通过 |
| 环境重建 | `docker compose up --build -d` | 构建成功；Adapter Manager、Adapter Supervisor、Python Worker、Outbound Gateway 四个服务 healthy；Backend 启动失败，Frontend 与 Caddy 因依赖未启动 |
| 真实 MySQL 迁移校验 | 通过 `mysql:8.4` 客户端查询 `flyway_schema_history` 与 `information_schema.columns` | V23 于 2026-08-24 11:33:54 成功应用，两表及列类型符合预期 |

说明：本机未安装 Maven，按项目 `backend/Dockerfile` 中同款 `maven:3.9.9-eclipse-temurin-17` 镜像执行测试，Java 版本与 CI 一致（Temurin 17）。

### 已知问题

- **Backend 容器启动失败，原因与本次变更无关。** `docker compose up --build -d` 中 `ai-backend` 不健康，根因是所连 PostgreSQL 实例的 `master.flyway_schema_history` 存在仓库中不存在的历史迁移记录，Flyway 校验失败：`Detected applied migration not resolved locally: use timestamptz for wecom automation`。
- 该实例的 PostgreSQL 迁移历史包含 `add wecom friend automation`(V2)、`add wecom agent diagnostics`(V3)、`use timestamptz for wecom automation`(V3.1 及一条 version 为空的可重复迁移)、`add wecom official messaging`(V4)、`add wecom agent bootstrap`(V5)、`drop wecom agent command registration fk`(V6)，最近一条安装于 2026-08-24 02:50:11，均早于本次改动；当前 `master` 分支下 `db/migration/postgresql/` 仅有 V1。
- 本次改动未触碰任何 PostgreSQL 迁移文件（`git status` 对该目录无输出），因此该故障在本次改动前即存在，属于环境与分支的历史漂移。
- 受此阻塞，未能完成后端应用级端到端冒烟（接口触发器页面实际读写 MySQL）。数据库层已通过真实 MySQL 迁移校验与 27 个持久化用例覆盖，应用装配层未在运行态验证。
- 建议的解决方式（需人工确认后执行，均会改动数据库或代码）：补齐缺失的 PostgreSQL 迁移文件、对 PostgreSQL 执行 `flyway repair`，或明确该数据库不应被 `master` 分支使用并切换连接目标。

### 测试环境限制

- H2 不支持 MySQL 的位串字面量，持久化测试在加载真实迁移脚本时仅将 `b'1'`/`b'0'` 替换为 `TRUE`/`FALSE`，其余 DDL（`BIT(1)`、`MEDIUMTEXT`、`DATETIME(6) DEFAULT CURRENT_TIMESTAMP(6)`、内联 `INDEX ... DESC`、外键、`ENGINE`/`CHARSET` 子句）均按原文执行；位串默认值已通过真实 MySQL 的 `information_schema` 校验补齐。

### 下次测试建议

- Backend 容器可正常启动后，补做接口触发器的应用级端到端冒烟：新建配置、手动执行、查看执行日志、按 Trace ID 过滤。
- 若后续决定清理 PostgreSQL 中遗留的接口触发器两张表，需要单独验证 `postgresqlDataSource` 与就绪检查在空 Schema 下仍正常。

## 日志安全默认值与链路入口优化测试结果（2026-08-20）

### Git 基准点

Commit: 9f8e39f1cc8f3aca6ebd1f3d52702603d67f4579
- 日志优化提交: `05a9120`（Harden trace logging defaults）
- 基准说明: 测试时共享工作区同时包含 `9f8e39f` 的 Adapter Supervisor 代码；后续 `d604662` 仅更新测试文档，不影响运行结果。
- 测试日期: 2026-08-20
- 分支: master
- 未执行 `git push`

### 变更范围

- Java HTTP 请求日志改为仅记录事件、方法、路径、状态码和耗时，不再读取或输出 Header、Query、请求正文及响应正文；`requestId`、MDC 和 Trace 关联保持不变。
- Python Worker 的 `LLM_LOG_CONTENT` 默认值改为 `false`，关闭时保留模型、Token、耗时和响应摘要；扩展 Authorization、API Key、Token、Cookie、Session、Secret、Password、Credential 等常见凭据格式的脱敏字段。
- AI Chat 的链路入口按认证方式动态标记：网页登录为 `WEB_UI`，API Key 调用为 `API_KEY`；固定入口类型不受影响。
- AI Chat 中的 Trace ID 在具备 `system:task:view` 权限时可直达任务日志，任务页会校验 Trace ID、加载对应记录并按需打开日志抽屉；无权限用户仅看到普通文本。
- 同步更新 Compose、环境变量模板、中英文说明及本机忽略的 `.env` 默认配置；未新增依赖、数据库迁移或外部接口。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与用例 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| Java HTTP 层不泄露请求或响应内容 | Backend：`RequestContextFilterTest`；运行态伪凭据探针 | 长正文、Header、Query、Cookie、响应内容和探针标记均不进入日志，业务流与响应保持不变；通过 | 正常、边界、安全、回归 |
| 模型正文日志默认关闭且保留必要元数据 | Python：默认配置及关闭内容日志测试；Compose 与运行容器配置检查 | 默认值与运行值均为 `false`，仍保留模型、Token、耗时和响应摘要；通过 | 默认值、配置、兼容 |
| 常见凭据格式统一脱敏 | Python：参数化敏感字段测试 | Authorization、Proxy Authorization、API Key、Token、Cookie、Session、Secret、Password、Credential 等值均替换为 `***`；通过 | 边界、异常、安全、恶意输入 |
| Trace 入口与认证方式一致 | Backend：`TraceTypeCodeTest` | 登录用户为 `WEB_UI`，API Key 用户为 `API_KEY`，固定 `MANUAL` 入口不变；通过 | 正常、分支、兼容 |
| Trace ID 可安全直达对应日志 | Frontend：Chat 响应与 Tasks 日志入口契约测试 | 有权限时跳转并自动加载，Trace ID 编码且仅接受限定字符和长度；无权限不生成链接；通过 | 权限、安全、边界、回归 |
| 所有相关历史功能无回归 | Backend、Python Worker、Frontend 完整测试；统一重建及健康检查 | 964/964 通过；七个基础服务 healthy；通过 | 完整回归、构建、部署 |

### 测试执行结果

- 正式完整自动化：964/964，通过率 100%；失败 0，错误 0，跳过 0。
- Backend：599/599；Python Worker（Python 3.12）：67/67；Frontend：298/298。
- 定向测试：Backend 9/9、Python Worker 58/58、Frontend 24/24，均包含在上述完整测试总数中。
- `docker compose up --build -d` 完整重建成功；Adapter Manager、Adapter Supervisor、Backend、Frontend、Python Worker、Outbound Gateway、Caddy 共七个服务全部 healthy，Backend readiness 为 `UP`。
- 运行容器确认 `llm_log_content=false`；向 Backend 发送带唯一伪凭据标记的未授权请求后检查近期日志，标记未出现。
- 未生成行覆盖率百分比；项目现有测试入口未配置统一覆盖率聚合，本次以验收标准映射、定向测试和三个模块完整回归为准。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 定向 | Maven 3.9.9 / Java 17 锁定容器执行 `mvn -B -ntp -Dtest=RequestContextFilterTest,TraceIdInterceptorTest,TraceTypeCodeTest test` | 9/9 通过 |
| Backend 完整 | Maven 3.9.9 / Java 17 锁定容器执行 `mvn test -B -ntp` | 599/599 通过；失败、错误、跳过均为 0 |
| Python 定向 | Python 3.12 锁定容器设置 `PYTHONPATH=.` 后执行 `pytest` 日志与 Trace 测试 | 58/58 通过 |
| Python 完整 | Python 3.12 锁定容器按哈希安装开发依赖后执行完整 `pytest` | 67/67 通过 |
| Frontend 定向 | `cd frontend && node --test test/chat-response.test.mjs tests/tasksViewLogDisplay.test.js` | 24/24 通过 |
| Frontend 完整 | `cd frontend && node --test test/*.test.mjs tests/*.test.js` | 298/298 通过 |
| 统一重建 | `docker compose up --build -d` | 首次因 `domestic-trade-caddy` 占用 80/443 失败；停止精确冲突容器后重试成功 |
| 运行态检查 | `docker compose ps`、Backend readiness、Worker `load_settings()`、唯一伪凭据日志探针 | 七个服务 healthy；Backend=`UP`；正文日志关闭；探针未泄漏 |
| 静态检查 | `git diff --check`、`git diff --cached --check` | 通过 |

### 测试过程问题与处理

- 宿主机没有 Maven，Backend 测试改用项目锁定的 Maven/Java 容器；没有安装宿主依赖。
- 首次 Frontend 命令从仓库根目录执行导致路径错误，切换到 `frontend/` 后定向与完整测试均通过。
- Python 首次容器测试缺少 `PYTHONPATH=.`，补齐运行环境后通过；一次临时断言误把字段名中的 `secret` 当作敏感值泄漏，修正为验证实际伪凭据值后通过。
- Backend 定向测试首次因测试替身声明为 `ServletResponse` 而无法调用 `setHeader`，改为符合实际响应类型的断言后通过；业务实现未因此弱化。
- 统一重建首次被外部 `domestic-trade-caddy` 占用 80/443 阻断，按规则仅停止该精确容器后重试；未删除容器、数据卷或业务数据。
- 测试及暂存期间存在 Adapter Supervisor 的并发修改与提交；最终日志提交 `05a9120` 已核验只包含本任务 18 个文件，Adapter 代码单独位于 `9f8e39f`。

### 未执行测试

- 未调用真实外部模型供应商，避免产生费用、发送业务或测试数据及依赖外部网络；模型日志行为通过隔离测试、运行配置和运行态探针验证。
- 未执行生产环境、多节点部署或外部日志平台的端到端验证；验证范围为仓库 Docker Compose 环境。

### 已知问题与限制

- 主动设置 `LLM_LOG_CONTENT=true` 后，凭据虽会按已知格式脱敏，但提示词和响应仍可能包含不可识别的业务敏感信息；启用前必须完成数据分类、保留期限和日志访问权限评审。
- 不含字段名或可识别前缀的任意裸密钥无法依靠通用文本规则可靠识别，禁止把日志脱敏当作密钥管理替代方案。
- `domestic-trade-caddy` 当前保持停止，可使用 `docker start domestic-trade-caddy` 恢复；恢复前需先处理与本项目 Caddy 的 80/443 端口冲突。

### 下次测试建议、重测触发和回滚

- 修改 `RequestContextFilter`、Trace 注解/切面、认证类型映射、模型日志配置/脱敏规则、Chat Trace 链接或 Tasks 路由加载逻辑时，必须重跑对应定向测试、Backend/Python/Frontend 完整回归及统一重建。
- 后续建议接入统一日志字段白名单与集中式敏感数据扫描，并在具备隔离测试账号和供应商沙箱时补充真实模型调用的日志端到端验证。
- 回滚时撤销 `05a9120` 并执行 `docker compose up --build -d`；本机忽略的 `.env` 不受 Git 回滚影响，如需恢复旧行为必须显式设置 `LLM_LOG_CONTENT=true`。

## Adapter Docker Supervisor 隔离与未推送代码回归测试结果（2026-08-20）

### Git 基准点

Commit: 9f8e39f1cc8f3aca6ebd1f3d52702603d67f4579
- 提交说明: Isolate adapter Docker supervisor
- 前置日志安全提交: `05a9120`（Harden trace logging defaults）
- 测试日期: 2026-08-19 至 2026-08-20
- 分支: master
- 未推送范围: `origin/master..47ed4d5` 共 5 个本地提交，未执行 `git push`

### 变更范围

- 将原先同时承载内部 HTTP API 和 Docker Socket 权限的 Adapter Manager 拆为双层控制面：网络可达的 Manager 仅负责令牌鉴权、严格 JSON 校验和类型化转发；无网络 Supervisor 负责固定的 N8N/DIFY Compose 操作。
- Manager 使用最小 `scratch` 镜像、非 root 用户和只读私有控制卷，不再挂载 Docker Socket、Compose 文件、`.env` 或其他部署文件。
- Supervisor 通过私有 Unix Socket 提供服务，配置为 `network_mode: none`，继续限制为两个硬编码 Worker、禁止运行时构建、限制命令超时和响应错误内容。
- Docker Socket 仅迁移至无网络 Supervisor；为保持现有按需启停能力，Supervisor 仍只读挂载 Compose/环境文件并持有宿主 Docker 控制权限。
- 没有修改本地 `.env`、密钥、生产连接信息、数据库结构、Backend API 协议或前端开关交互。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与用例 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 网络 Manager 不持有 Docker 或部署文件权限 | Frontend Compose 隔离回归；运行容器 `docker inspect` | Manager 仅只读挂载 `/run/adapter-control`，无 Docker Socket、`.env` 和 Compose 文件；通过 | 权限、安全、部署 |
| Supervisor 无网络且只接受固定类型命令 | Adapter Go：来源白名单、畸形 JSON、尾随 JSON、恶意来源、Unix Socket 客户端测试 | 仅 N8N/DIFY 可到达固定 Compose 命令；Supervisor 网络为 `none`；7/7 通过 | 边界、异常、恶意输入、安全 |
| 控制层失败不泄露部署细节 | Adapter Go：`TestManagerBoundsSupervisorFailures`、`TestFailedOperationPreservesBoundedError` | `.env` 路径、命令输出和底层错误不返回调用方；通过 | 异常、安全 |
| 现有 Backend 生命周期协议保持兼容 | `WorkflowAdapterManagerClientTest`、`WorkflowAdapterLifecycleServiceTest` | 鉴权请求、状态映射、并发关闭保护和期望状态持久化不变；定向 7/7 通过 | 正常、状态冲突、兼容 |
| 两个按需 Worker 可真实启停 | Backend 容器调用 Manager，轮询实际容器健康并停止 | N8N、DIFY 均从 STOPPED 到 RUNNING，再到 STOPPED；通过 | 集成、运行态、回归 |
| 所有未推送代码无功能回归 | Backend、Frontend、Python、Dify、n8n、两个 Go 服务完整测试及统一重建 | 827/827 通过；七个基础服务 healthy；通过 | 完整回归、构建、部署 |

### 测试执行结果

- 正式完整自动化：827/827，通过率 100%；失败 0，错误 0，跳过 0。
- Backend：598/598；Frontend：129/129；Python Worker（Python 3.12）：60/60；Dify Worker（Python 3.12）：21/21；n8n Worker：10/10。
- Adapter Manager/Supervisor：7/7；Outbound Gateway：2/2；Backend Adapter 定向测试另计 7/7，因包含在 Backend 完整测试中未重复计入总数。
- Workflow/Dependabot YAML 解析、Actionlint、CI 环境 Compose 插值与 `docker compose config --quiet` 均通过。
- 插件 Worker 镜像预构建通过；`docker compose up --build -d` 最终成功，Backend、Frontend、Python Worker、Adapter Manager、Adapter Supervisor、Outbound Gateway、Caddy 全部 healthy。
- N8N 与 DIFY 均通过 Manager → Unix Socket → Supervisor → Docker Compose 链路达到 `RUNNING`，验证后均正常停止。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Adapter 完整 | 锁定 Go 1.26.5 镜像执行 `gofmt` 和 `go test ./...` | 7/7 通过；宿主未安装 Go，使用项目锁定容器工具链 |
| Backend Adapter 定向 | Maven 3.9.9 / Java 17 容器执行 `-Dtest=WorkflowAdapterManagerClientTest,WorkflowAdapterLifecycleServiceTest test` | 7/7 通过 |
| Backend 完整 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 598/598，通过；失败、错误、跳过均为 0 |
| Frontend 完整 | `cd frontend && npm test` | 129/129 通过；其中生命周期/Compose 隔离 4/4 |
| Python Worker 完整 | Python 3.12 锁定镜像按哈希安装开发依赖后执行 `pytest tests -q` | 60/60 通过 |
| Dify Worker 完整 | Python 3.12 锁定镜像安装运行依赖后执行 `unittest discover -s tests -v` | 21/21 通过 |
| n8n Worker 完整 | `cd n8n-plugin-worker && npm test` | 10/10 通过 |
| Outbound Gateway 完整 | 锁定 Go 1.26.5 镜像执行 `go test ./...` | 2/2 通过 |
| CI 与 Compose 静态校验 | Ruby YAML 解析、Actionlint、CI 环境 `docker compose --env-file /dev/null config --quiet` | 全部通过 |
| 插件镜像构建 | `docker compose --profile plugin-adapters build dify-plugin-worker n8n-plugin-worker` | 两个镜像构建成功 |
| 统一重建 | `docker compose up --build -d` | 首次因 `apple-auto-caddy` 占用 80/443 失败；按规则停止冲突容器后重试成功 |
| 真实启停 | Backend 容器携带内部令牌对 N8N/DIFY 分别执行启用、状态查询和停用 | 两者均达到 RUNNING 并最终 STOPPED |
| 运行隔离 | `docker inspect` Manager/Supervisor 挂载、用户和网络 | Manager=`10001:0`、仅只读控制卷；Supervisor 网络=`none`，Docker Socket 仅位于 Supervisor |

### 测试过程问题与处理

- 宿主机没有 `gofmt`/Go 工具链，改用 Dockerfile 已锁定的 Go 1.26.5 镜像；没有安装宿主依赖，也没有遗留调试文件。
- Docker Desktop 初始未运行，启动后继续容器化测试；一次 Buildx 写入在受限环境被拒绝，经授权后使用正常 Compose 构建路径完成。
- 首次统一重建因外部 `apple-auto-caddy` 占用 80/443 失败；确认精确容器后停止该容器并重试，未删除容器、数据卷或业务数据。
- 真实 N8N 启动后数据库期望状态仍为关闭，Backend 的 15 秒协调任务会按设计自动停止它；回归改为在协调窗口内确认 healthy/RUNNING，然后显式停止。DIFY 使用同样流程通过。
- 测试期间存在并发的日志安全改动及提交；完整测试均基于包含这些未推送改动的共享工作区执行，最终功能提交只补齐 Adapter 实现、测试和说明。

### 已知问题与限制

- Docker Socket 的宿主级权限没有被彻底消除，而是隔离到无网络、固定命令的 Supervisor。若 Supervisor 进程或 Docker CLI 本身被攻破，仍可能获得宿主 Docker 权限；彻底消除需迁移到外部编排平台或具备最小权限授权模型的控制面。
- Supervisor 为 Compose 变量展开仍需只读访问 `.env` 和少量部署文件；这些文件不再暴露给网络 Manager。本次没有读取输出、修改、轮换或提交本地 `.env`。
- 没有执行生产集群编排、远程 Docker 主机或多节点故障切换测试；当前验证范围为仓库 Compose 部署模型。
- 本次没有新增依赖、数据库迁移或需清理的调试文件。

### 下次测试建议、重测触发和回滚

- 修改 Manager/Supervisor 协议、Unix Socket 权限、服务白名单、Compose Worker 定义或生命周期协调逻辑时，必须重跑 Adapter/Backend/Frontend、完整跨模块回归、统一重建和两个 Worker 真实启停。
- 后续建议将 Supervisor 迁移至外部编排控制面，并增加最小权限身份、请求重放保护、操作审计、故障恢复和多实例领导者测试。
- 回滚时撤销 `9f8e39f` 后执行 `docker compose up --build -d`；该提交不包含数据库迁移或日志安全改动，无需回滚 `05a9120`。

## 模型调用内容日志与凭据脱敏测试结果（2026-08-19）

### Git 基准点

Commit: e2133a9
- 提交说明: Restore sanitized LLM content logs
- 测试日期: 2026-08-19
- 分支: master
- 重测触发: 修改 `python-worker/app/logging_config.py`、模型调用内容日志、日志回传、凭据脱敏规则或 `LLM_LOG_CONTENT` 配置时。

### 变更范围

- 模型系统提示词、用户提示词、助手历史和模型响应默认恢复为日志输出，同时继续记录模型、Token 数和耗时。
- 普通键值、HTTP Authorization/Bearer、单/双引号 JSON 中的 `api_key`、`x-api-key`、`token`、`secret`、`password` 和 `authorization` 在输出前统一替换为 `***`。
- 控制台日志、回传 Java 的链路日志消息及异常堆栈使用同一脱敏入口，避免异常路径绕过脱敏。
- Compose、环境变量模板及本机运行配置默认启用 `LLM_LOG_CONTENT=true`；显式设为 `false` 时继续只记录元数据和响应摘要。
- 未修改模型供应商请求协议、API Key 轮换、路由、数据库结构、后端业务代码或前端业务代码。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与用例 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 模型调用正文日志默认可用 | Worker 单元：`test_success_log_prints_model_content_and_redacts_embedded_credentials`；Compose 配置和容器运行态检查 | 系统提示词、用户提示词、响应与调用元数据均打印；Compose 与运行容器均为 `LLM_LOG_CONTENT=true`；通过 | 正常、配置、运行态 |
| API Key 等凭据不写入日志 | Worker 参数化单元：`test_log_sanitizer_redacts_credential_formats` | JSON、单引号对象、Bearer Header 和普通键值中的凭据均替换为 `***`；通过 | 正常、边界、安全、恶意输入 |
| 链路日志和异常堆栈同样脱敏 | Worker 单元：`test_shipped_logs_redact_credentials_from_message_and_exception` | 回传消息和 throwable 不包含原始凭据且保留事件主体；通过 | 异常、安全、回归 |
| 可按环境要求关闭内容正文 | Worker 单元：`test_disabled_content_log_keeps_metadata_and_response_digest` | 显式关闭后不记录提示词/响应正文，仍记录调用摘要与哈希；通过 | 兼容、回归 |
| Worker 历史功能不回归 | Python Worker 完整测试、Compose 统一重建和健康检查 | 60/60 通过；七个基础服务全部 healthy；通过 | 完整回归、部署 |

### 测试执行结果

- Python Worker（Python 3.12）：60/60，通过率 100%；失败 0，错误 0，跳过 0。
- 针对性日志测试最终结果：51/51，通过率 100%。
- 缺陷复现阶段：首次为 47 通过、4 失败，稳定复现 JSON 凭据、回传消息/异常以及模型提示词泄露；首轮实现后为 50 通过、1 失败，发现 Authorization Bearer 的规则顺序问题；修正后 51/51 通过。
- `docker compose config` 确认 Python Worker 环境为 `LLM_LOG_CONTENT=true`；运行容器读取结果同为 `true`。
- `docker compose up --build -d` 最终成功，Backend、Frontend、Python Worker、Adapter Manager、Adapter Supervisor、Outbound Gateway 和 Caddy 共七个服务全部 healthy。
- 未新增依赖；现有运行镜像和开发依赖未配置 coverage 插件，因此未生成覆盖率百分比。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 宿主机测试入口 | `cd python-worker && PYTHONPATH=. python3.12 -m pytest ...` | Python 3.12.13 可用，但宿主机未安装 pytest，未进入用例收集；后续改用只读挂载的 Python 3.12 容器 |
| 缺陷复现与针对性回归 | Python 3.12 容器按哈希安装 `requirements-dev.txt` 后执行 `pytest -q -p no:cacheprovider tests/test_trace_context.py tests/test_llm.py` | 依次得到 47 通过/4 失败、50 通过/1 失败和最终 51/51 通过 |
| Worker 完整回归 | Python 3.12 容器按哈希安装 `requirements-dev.txt` 后执行 `pytest -q -p no:cacheprovider` | 60/60 通过 |
| Compose 配置 | `docker compose config --format json` 后仅断言 Python Worker 的 `LLM_LOG_CONTENT` | 值为 `true`；通过，未输出其他环境变量内容 |
| 统一重建 | `docker compose up --build -d` | 首次因外部 `apple-auto-caddy` 占用 80/443 失败；停止该冲突容器后重试成功，未删除容器或数据卷 |
| 运行态复核 | `docker compose ps`、容器内 `load_settings()` 和 Python Worker 健康检查 | 七个服务全部 healthy；`llm_log_content=true`；通过 |
| 静态检查 | `git diff --check`、`git diff --cached --check` | 通过 |

### 未执行测试

- 未单独执行 Backend Maven 完整测试、Frontend 单元测试及插件 Worker 测试：本次确认范围仅修改 Python Worker 日志、配置和说明文档，没有修改这些模块的业务代码；Compose 重建完成了各镜像构建和运行态集成检查。
- 未调用真实外部模型供应商，避免产生费用、发送测试数据或依赖外部网络状态；模型日志行为由 HTTPX 隔离测试、日志捕获和运行配置共同验证。

### 已知问题与限制

- 脱敏规则覆盖已知凭据字段和 Bearer 格式；未带字段名、没有可识别前缀的任意裸密钥无法仅靠通用日志文本可靠识别。
- 开启内容日志后，提示词和响应中的业务信息仍会被记录；凭据脱敏不能替代日志访问控制、保留期限和数据分类管理。
- 统一重建读取了共享工作区中同期存在的 Adapter Manager 和 Frontend 未提交改动，但本功能提交未包含这些无关变更。

### 下次测试建议与回滚方式

- 新增供应商鉴权格式时，为对应字段名、Header 或错误体格式补充参数化脱敏用例。
- 如需阻止所有提示词和响应持久化，将 `LLM_LOG_CONTENT=false` 写入环境并重新执行 `docker compose up --build -d`；无需数据库回滚。
- 代码级回滚可撤销功能提交 `e2133a9` 后统一重建；本次没有数据迁移、依赖变更或需清理的数据文件。

## NVIDIA LLM 请求策略兼容性测试结果（2026-08-19）

### Git 基准点

Commit: bbc13eb
- 提交说明: Add NVIDIA LLM request strategy
- 测试日期: 2026-08-19
- 分支: master
- 重测触发: 修改 `python-worker/app/llm.py`、`python-worker/app/llm_provider.py`、供应商请求策略或模型思考参数映射时。

### 变更范围

- 将模型请求构造抽离为可扩展策略：未知供应商继续使用原有 OpenAI-compatible 策略，NVIDIA `integrate.api.nvidia.com` 使用 Nemotron 专用策略。
- NVIDIA 非思考请求不再发送顶层 `enable_thinking`；思考请求将 SDK 的 `extra_body` 语义展开为顶层 `chat_template_kwargs.enable_thinking` 和整数 `reasoning_budget`。
- 供应商非 2xx 响应现在包含状态码和脱敏后的错误体，避免只返回 `HTTPStatusError`，且不记录 JSON 错误体中的认证字段。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与用例 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 既有 OpenAI-compatible 供应商请求不变 | Worker 单元：`test_generic_strategy_preserves_existing_thinking_payload` | 保留顶层 `enable_thinking` 与原有 `reasoning_effort`；通过 | 兼容、回归 |
| NVIDIA 普通调用可用 | Worker 单元：`test_nvidia_strategy_omits_unsupported_top_level_thinking_flag` | 不发送 NVIDIA 不接受的通用思考字段；通过 | 正常、兼容 |
| NVIDIA 思考请求格式正确 | Worker 单元：`test_nvidia_strategy_expands_thinking_parameters_to_provider_payload`、`test_nvidia_strategy_rejects_non_numeric_thinking_budget` | 正确构造 `chat_template_kwargs` 和正整数预算；非法映射受控拒绝；通过 | 正常、边界、异常 |
| 供应商失败可诊断且不泄露密钥 | Worker 单元：`test_invoke_reports_provider_status_and_sanitized_error_body` | 返回 HTTP 状态码，JSON `api_key` 被脱敏；通过 | 异常、安全 |
| 平台调用契约无回归 | Python Worker 完整、Backend 完整测试与 Compose 重建 | 53/53、598/598 通过；全部基础服务 healthy | 回归、部署 |

### 测试执行结果

- Python Worker（Python 3.12）：53/53，通过率 100%；失败 0，错误 0，跳过 0。
- Backend 完整回归：598/598；失败 0，错误 0，跳过 0；`BUILD SUCCESS`。
- `docker compose up --build -d` 最终成功；Backend、Frontend、Python Worker、Adapter Manager、Outbound Gateway、Caddy 全部 healthy。
- 首次统一启动因外部容器 `domestic-trade-caddy` 占用宿主机 80/443 端口失败；已按项目规则停止该容器后重建成功。
- 运行镜像和开发依赖均未提供 `coverage` 或 pytest 覆盖率插件，未能生成覆盖率百分比；未为本次任务新增依赖。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Worker 完整 | Python 3.12 临时容器安装 `requirements-dev.txt`，以 `PYTHONPATH=/workspace` 执行 `pytest -q` | 53/53 通过；临时容器自动清理 |
| Backend 完整 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 598/598 通过，BUILD SUCCESS |
| 统一重建 | `docker compose up --build -d` | 首次端口冲突后停止占用容器并重试成功；六个基础服务 healthy |
| 覆盖率检查 | `python -m coverage --version` | 工具未配置，未生成覆盖率数据 |

### 已知问题与限制

- 当前 NVIDIA 策略只匹配 `integrate.api.nvidia.com`；其他具有非标准 OpenAI 请求语义的供应商需新增独立策略并配套契约测试。
- NVIDIA 流式 SSE 和 `reasoning_content` 尚未作为平台响应字段透传；本次维持平台现有非流式响应协议。

### 下次测试建议与重测触发条件

- 新增供应商策略时，至少覆盖普通对话、思考模式、Agent 工具调用、非 2xx 错误和通用策略回归。
- 若开放 NVIDIA 流式输出或思考内容展示，需新增 SSE 分块、终止事件、usage 和前端渲染的端到端测试。
- 如需量化覆盖率，应在单独确认后将覆盖率工具加入 Python 开发依赖和 CI。

## 项目安全风险修复测试结果（2026-08-18）

### Git 基准点

Commit: c459d41b004167affa5c0c0cc4f60b117427a793
- 提交说明: Update adapter isolation regression test
- 核心功能提交: c930c47（Harden plugin isolation and configuration security）、bec30e3（Complete security hardening and test gates）、efde786（Propagate required adapter runtime secrets）
- 测试日期: 2026-08-18
- 分支: master
- 重测触发: 加密密钥配置、异步审计、数据库连接池、插件执行/依赖安装、出站网关、容器网络或内部令牌发生变更。

### 变更范围

- n8n 第三方包元数据加载移入短生命周期子进程，子进程不继承平台内部令牌；Dify 与 n8n 只接受直接依赖的精确版本，安装继续使用锁文件或哈希约束。
- 新增独立出站网关，以静态域名白名单、端口限制、私网地址拒绝、连接时重新解析和禁止重定向降低 SSRF、横向访问与 DNS rebinding 风险；Backend 通过网关反向代理访问按需 Worker。
- Backend 不再连接插件出站网络；插件 Worker 默认关闭并仅连接内部出站网络。Adapter Manager 移除整仓库挂载，固定可控服务且禁止运行时构建镜像；Docker Socket 挂载仍保留以支持现有启停能力。
- 配置密文升级为 `enc:v1:<key-id>:<payload>`，支持活动密钥写入、多历史密钥读取，并兼容旧 `enc:` 数据渐进迁移；API Key 哈希密钥必须独立显式配置。
- LLM 内容日志默认关闭，Redis/数据库示例改为 TLS 安全默认值；异步审计队列采用调用线程回退，降低队列满时静默丢审计事件的风险；调整数据库池最小空闲连接。
- 新增跨模块测试 CI 与镜像安全扫描矩阵，运行时依赖和基础镜像使用精确版本或摘要。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与用例 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- |
| 第三方插件不在服务主进程执行且不获得内部令牌 | n8n Worker 10 项、Dify Worker 21 项 | 子进程探测、环境清理、非法包、精确版本及兼容回归全部通过 | 正常、异常、安全、兼容 |
| 插件出站只能经过受控网关 | Outbound Gateway 2 项、Frontend Compose 隔离回归、Compose 配置 | 非准入/私网目标拒绝，代理凭据校验、网络隔离及内部路由通过 | 正常、边界、恶意输入、回归 |
| Adapter Manager 控制边界收敛 | Adapter Manager 4 项、Frontend Compose 隔离回归 | 固定服务白名单、鉴权、请求限长、错误限长、禁止隐式构建和整仓库挂载通过 | 异常、权限、安全、回归 |
| 密文可轮换并兼容历史数据 | ConfigCryptoServiceTest 4 项及 Backend 全量 | 活动密钥写入、历史密钥解密、旧格式读取、未知密钥拒绝通过 | 正常、边界、异常、兼容 |
| 安全配置默认值和审计回退有效 | Backend 598 项、Compose 配置 | 独立哈希密钥、日志默认关闭、TLS 示例、审计拒绝策略及池配置无回归 | 安全、配置、回归 |
| 构建、部署与跨模块功能无回归 | 七套自动化、插件镜像构建、统一 Compose 重建 | 812/812 通过；六个基础服务 healthy，插件 Worker 默认停止 | 构建、部署、兼容、回归 |

### 测试执行结果

- 正式自动化用例：812/812，通过率 100%；失败 0，错误 0，跳过 0。
- Backend 完整回归：598/598；Compose 构建阶段再次执行 598/598 并 `BUILD SUCCESS`。
- Frontend：129/129；Python Worker（Python 3.12）：48/48；Dify Worker（Python 3.12）：21/21；n8n Worker：10/10。
- Adapter Manager：4/4；Outbound Gateway：2/2。
- `docker compose config --quiet` 通过；Dify/n8n 按需镜像构建成功；`docker compose up --build -d` 构建成功，Backend、Frontend、Python Worker、Adapter Manager、Outbound Gateway、Caddy 全部 healthy。
- 首次直接运行 Python 测试因宿主缺少 pytest/FastAPI 依赖而收集失败，随后使用 Python 3.12 临时虚拟环境和哈希锁定的开发依赖重跑通过；临时环境已清理。
- 首次使用错误 Go 镜像摘要重跑时遇到镜像代理 429，改用 Dockerfile 锁定且本机已有的正确镜像后，两个 Go 测试套件均通过。

### 关键模块测试

| 模块 | 结果 | 覆盖说明 |
| --- | --- | --- |
| Backend Service / Security / Workflow | 598/598 | 密钥轮换、审计回退、连接池配置、插件客户端和完整回归 |
| Frontend / Compose 约束 | 129/129 | Manager 挂载边界、按需 Worker、禁止运行时构建及页面回归 |
| Python / Dify / n8n Worker | 79/79 | Python 3.12 任务、包准入、子进程隔离、环境清理和插件兼容 |
| Adapter Manager / Outbound Gateway | 6/6 | 鉴权、固定服务、请求约束、白名单、私网阻断和代理凭据 |
| Domain / Repository | 未修改 | 由 Backend 完整套件验证接口和持久化兼容性 |

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 完整 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp package` | 598/598，通过；BUILD SUCCESS |
| Frontend 完整 | `cd frontend && npm test` | 129/129，通过 |
| Python Worker | Python 3.12 临时环境安装 `requirements-dev.txt` 后执行 pytest | 48/48，通过；临时环境已清理 |
| Dify Worker | Python 3.12 执行 unittest discover | 21/21，通过 |
| n8n Worker | `cd n8n-plugin-worker && npm test` | 10/10，通过 |
| Go 服务 | Go 1.26.5 容器分别执行 `go test ./...` | Adapter 4/4、Gateway 2/2，通过 |
| Compose 校验与重建 | `docker compose config --quiet`、profile 镜像构建、`docker compose up --build -d` | 全部成功；六个基础服务 healthy，按需 Worker 停止 |

### 已知问题与限制

- Adapter Manager 为兼容当前容器启停功能仍挂载 Docker Socket；已通过固定服务、禁止构建、只读必要文件挂载、无宿主端口及能力收敛降低风险，但该 Socket 仍具有宿主级控制风险，彻底移除需要改造为外部编排控制面。
- 出站域名白名单来自启动配置，修改后需要重启网关；第三方运行时必须使用支持标准 HTTP/HTTPS 代理变量的客户端。网络隔离会阻止绕过代理的直接出网。
- 旧 `enc:` 密文不会后台批量重写；读取保持兼容，新建或再次保存时使用活动密钥版本。完成迁移前必须保留旧密钥。
- 本地 Redis 未配置 TLS，本次运行态验证仅用命令级 `REDIS_SSL=false` 覆盖；仓库示例和默认值仍要求 TLS。生产数据库与 Redis 的证书链需在部署环境单独验收。
- 镜像安全扫描矩阵已纳入 CI，本地未重复执行外部漏洞库扫描；扫描结果以 CI 运行为准。

### 下次测试建议与重测触发条件

- 修改网关域名解析、代理鉴权、Worker 网络或插件包安装逻辑时，必须重跑网关、两个 Worker、Frontend Compose 回归及统一重建，并增加对应绕过样例。
- 修改密钥格式、活动密钥选择或历史密钥清理时，必须保留旧格式/历史版本夹具并重跑 Backend 全量；生产轮换前应先做密文 key-id 盘点。
- 后续移除 Docker Socket 时，应为新的编排控制面增加最小权限、重放保护、超时、审计和故障恢复测试。

### 回滚方式

- 依次回滚 c459d41、efde786、bec30e3、c930c47 后重新执行统一重建。回滚前需确认没有仅由新活动密钥写入且旧版本无法读取的密文，并恢复旧插件网络与令牌配置；不涉及数据库结构迁移。

## 工作流可靠性、Cron 恢复与插件隔离测试结果（2026-08-17）

### Git 基准点

Commit: a09a46a
- 提交说明: Compensate latest missed cron event
- 前一功能提交: 0a234aa（Harden workflow execution recovery）
- 测试日期: 2026-08-17
- 分支: master
- 重测触发: 工作流执行、触发调度、图终态、插件 Worker 隔离及核心配置发生业务变更。

### 变更范围

- 运行记录先以 QUEUED 持久化，事务提交后派发；后台扫描、租约和原子领取保证进程崩溃、队列拒绝及多实例恢复。
- 总执行期限在排队、运行、WAIT 恢复和成功写回处统一校验；过期任务主动取消本地运行时，节点边界阻止迟到副作用继续执行。
- Cron 计划持久化到 MySQL，多实例通过条件更新抢占；停机期间每个触发器只补跑最近一次错过时间点，并跳到当前时间之后。
- 图校验要求所有可达路径以 END 结束且 END 无下游；WAIT 检查点保存终态信息。
- Dify 与 n8n 使用独立内部令牌；n8n 包探测/安装移入不继承内部令牌的短生命周期子进程，插件仍可使用自身 HTTP 客户端完成非平台 ABI 调用。
- 新增 V22 调度状态表、派发索引及对应部署配置。

### 验收标准—测试用例映射

| 验收标准 | 测试用例 | 结果与场景 |
| --- | --- | --- |
| 事务提交后可靠派发并可恢复 | WorkflowExecutionServiceStateTest、Compose 后端构建 | 通过；队列拒绝、租约过期、状态竞争、回归 |
| 总期限阻止迟到成功和 WAIT 恢复 | WorkflowExecutionServiceStateTest | 通过；边界、超时、取消、副作用安全 |
| 停机 Cron 每触发器只补最近一次 | WorkflowTriggerServiceTest | 5/5；正常、停机积压、幂等 |
| 图执行只能以 END 成功 | WorkflowGraphValidatorTest | 12/12；悬空终点、END 下游、条件分支 |
| 插件双令牌和非 ABI 兼容 | WorkflowPluginWorkerClientTest、n8n Worker tests | 通过；同地址令牌隔离、真实探测、兼容回归 |

### 测试执行结果

- Backend 完整：597/597，通过率 100%，失败 0，错误 0，跳过 0；由 `docker compose --profile plugin-adapters up --build -d` 构建阶段执行。
- n8n Worker（Node 24 容器）：9/9，通过；宿主 Node 26 的原生断言及受限监听失败未计入支持版本结果。
- Compose 配置：`docker compose --profile plugin-adapters config --quiet` 通过。
- 运行态：Backend、Frontend、Python Worker、Dify Worker、n8n Worker、Adapter Manager、Caddy 全部 healthy。
- Flyway：MySQL V22 成功应用，新增 `workflow_schedule_state` 和派发索引。
- `git diff --check`：通过。

### 已知问题与限制

- 为保持未经平台 HTTP ABI 的第三方插件兼容，插件运行时仍允许其自身 HTTP 客户端出网；本次重点隔离平台内部令牌、安装/探测父进程和数据库/运行时状态，未引入会破坏现有插件的强制 HTTP 代理。
- 超高频 Cron 在极长停机期间会逐次计算最近表达式时间，结果精确但恢复扫描耗时随错过次数增长；可后续增加按表达式类型的数学优化。

### 下次测试建议

- 修改工作流状态机、触发调度、V22 迁移、插件 Worker 环境变量或子进程边界时，重新执行 Backend 全量、Node Worker 全量和 Compose 重建。
- 如未来增加出网网关，补充代理兼容、DNS rebinding、私网地址阻断和插件 HTTP 客户端回归测试。

## 工作流可靠性与恢复修复测试结果（2026-08-13）

### Git 基准点

Commit: 099eeae629d13ed8a2c909c40a81473ff31c0dd6
- 提交说明: Harden workflow execution reliability
- 测试日期: 2026-08-13
- 分支: master
- 重测触发: 工作流触发幂等、执行恢复、图语义、发布并发、输入 Schema、运行期限和保留策略业务代码发生变更。

### 变更范围

- Webhook 和消息投递增加事务边界、运行关联校验、孤儿投递回收和投递状态记录；缺失事件 ID 按至少一次语义生成独立标识。旧版 Cron 内部调度线程不经过 Spring 事务代理，主要依赖幂等记录和孤儿投递回收提供最终恢复。
- Kafka 分区失败停止后续记录处理；发布使用已验证版本的乐观并发条件。
- CONDITION 节点强制 true/false 分支，顶层图必须到达 END；等待恢复覆盖 WAITING/RESUMING。
- 外部副作用节点不再套用通用自动重试；运行增加总期限、过期失败和节点/运行保留清理。
- 开放输入使用受限递归 JSON Schema 校验，拒绝 $ref 和超深结构；新增 V21 MySQL 迁移及运行时配置。

### 验收标准—测试用例映射

| 验收标准 | 测试用例 | 结果与场景 |
| --- | --- | --- |
| Webhook/message 基本投递与 Kafka 分区失败受控 | WorkflowTriggerServiceTest、WorkflowMessageTriggerManagerTest | 通过；签名异常、事件 ID 校验、正常投递、分区失败 |
| 重复投递、孤儿回收和 Cron 事务恢复 | 本轮未覆盖 | 不纳入本轮“全部通过”结论；需补充可执行测试 |
| 执行租约和总期限可恢复 | WorkflowExecutionServiceStateTest | 10/10；边界、状态冲突、回归 |
| 图分支和 END 语义受控 | WorkflowGraphValidatorTest、执行状态测试 | 10/10；正常、边界、非法图 |
| 发布并发不覆盖新草稿 | WorkflowServicePublishTest | 4/4；并发冲突、回归 |
| 输入 Schema 递归约束有效 | WorkflowInputSchemaValidatorTest | 2/2；嵌套、范围、额外字段、恶意引用 |
| 跨模块无回归 | Backend、Frontend、Python、Dify、n8n 完整套件 | 全部通过 |

### 测试执行结果

- Backend 完整：594/594，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 工作流定向：27/27，通过。
- Frontend：129/129，通过。
- Python Worker（Python 3.12，开发依赖容器）：48/48，通过。
- Dify Worker：21/21，通过。
- n8n Worker：9/9，通过；首次受限沙箱运行因无法监听 127.0.0.1 失败，放宽运行权限后重试通过。
- docker compose config --quiet：通过。
- docker compose up --build -d：首次因外部 domestic-trade-caddy 占用 80/443 失败；按规则停止该容器后重试成功。
- 运行态：adapter-manager、python-worker、backend、frontend、caddy 全部 healthy；Flyway MySQL schema 版本 21。
- 本轮合计自动化用例：801/801 通过。

### 已知问题与限制

- 插件 Worker 仍存在直接出网能力，尚未完成独立 outbound gateway、域名准入和 DNS 重绑定防护。
- ConfigCryptoService 尚未完成带版本密钥轮换与迁移。
- Cron 内部线程调用未经过 Spring 事务代理；孤儿投递回收提供最终恢复，但不是严格跨进程原子 outbox。
- 运行清理按终态和保留期删除数据，生产环境应先确认合规保留期限。

### 下次测试建议与重测触发条件

- 修改工作流执行、触发、迁移、输入校验、配置或插件网络边界时，重新执行 Backend 全量、五服务 Compose 重建及 Python/Node Worker 套件。
- 完成插件网关或密钥轮换后，新增网络攻击、DNS rebinding、密钥版本迁移和回滚测试。

### 回滚方式

- 回滚功能提交 099eeae 后重新执行 docker compose up --build -d；V21 为新增列和索引，回滚代码前需保留迁移结构，避免已应用 Flyway 历史不一致。

## 📋 工作流节点元数据语言适配测试结果（2026-08-12）

### Git 基准点

Commit: 3154d5021e744aeb8f2c96d8486c52b2c0f0052e
- 提交说明: Localize workflow node metadata；Harden workflow localized error output
- 测试日期: 2026-08-12
- 分支: master
- 前一业务基准: `af586ab`（工作流国际化缺陷修复）
- 重测触发: `backend/src/main/java/` 下工作流模板、插件注册/探测、运行记录和接口模型发生业务代码变更。

### 变更范围

- 画布开始/结束节点、Dify 市场组件、动作、字段、凭据及枚举选项按 `zh-CN` / `en-US` 展示；自定义节点名保持原样。
- Dify Worker ABI 升级到 6，输出受控双语元数据；元数据变化不改变执行 Schema 指纹、插件审批、启用状态或用户配置值。
- 已导入 Dify 包在同包指纹重新探测后刷新组件与模板展示元数据；历史画布同步新 Schema 时保留已有参数和自定义名称。
- 节点文档和运行历史节点名按当前语言展示；V19 保存组件/模板展示元数据，V20 为运行节点保存默认名称与受控本地化元数据并兼容历史记录。
- 适配器状态接口保留兼容字段但不下发容器技术错误；节点配置错误参数统一使用语言中立标点，避免英文响应混入中文标点。
- 未新增依赖；未调用第三方业务 API；插件 Worker 仍由按需管理器控制，默认保持停止。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 基础节点随语言切换 | Frontend 目录与画布测试 | START/END 历史默认名、中文/英文切换、自定义名称 | 默认名正确切换；自定义名不改写；通过 | 正常、边界、兼容 |
| Dify 名称、字段和选项完整双语 | Worker、Backend、Frontend 测试 | 双语 YAML、缺失单一语言、字段/凭据/枚举选择 | 当前语言展示，缺失语言安全回退，提交值稳定；通过 | 正常、边界、兼容 |
| 元数据刷新不影响执行身份 | Backend Registry/Probe/Service 测试 | 同包指纹 ABI 6 重探测并刷新已导入模板 | Schema 与本地化更新，指纹、审批、启用状态和配置值保持；通过 | 状态、副作用、安全 |
| 历史画布与运行记录兼容 | Frontend Graph、Backend 状态及迁移测试 | 历史节点、空元数据、自定义名、V19/V20 回填 | 新元数据可回填，原名称与配置保持；通过 | 迁移、边界、回归 |
| 节点文档完整适配 | Frontend 文档测试 | 英文文档示例、动态插件字段与选项 | 示例无中文展示文本，动态元数据按当前语言展示；通过 | 正常、兼容 |
| 全量功能无回归 | 四套完整自动化与 Compose | 完整测试、生产构建、统一重建和 Flyway 启动 | 746/746 通过；基础服务 healthy；数据库到 v20；通过 | 回归、构建、部署 |
| 技术错误与语言中立参数 | Backend 完整回归和适配器/节点配置测试 | manager 原始错误、嵌套节点缺失字段、非法 Tavily 操作 | 接口不泄漏技术错误；参数不混入中文标点；通过 | 异常、安全、兼容 |

### 测试执行结果

- 正式完整自动化用例总数：746；通过 746，通过率 100%；失败 0，错误 0，跳过 0。
- Backend 完整回归：587/587；Frontend 完整回归：129/129。
- Dify Worker（Python 3.12）：21/21；n8n Worker：9/9。
- `docker compose up --build -d` 最终成功；构建阶段 Backend 再次执行 587/587，Frontend Vite 生产构建成功。
- Adapter Manager、Python Worker、Backend、Frontend、Caddy 五个基础服务全部 healthy；Flyway 确认 MySQL 当前版本为 20。

### 关键模块测试

| 模块 | 结果 | 覆盖说明 |
| --- | --- | --- |
| Workflow Backend | 587/587 | 模板、市场、注册表、探测、运行记录、V19/V20 迁移及全量回归 |
| Frontend | 129/129 | 画布、节点管理、配置表单、连接凭据、文档、运行详情和自定义名保护 |
| Dify Worker | 21/21 | ABI 6、组件/模型/字段/凭据双语元数据、缺失语言回退和安全探测 |
| n8n Worker | 9/9 | 既有表达式、路由、安全和包缓存回归 |
| Domain / Repository | 未变更 | 本次未修改实体或 Repository 接口，由 Backend 完整回归覆盖兼容性 |

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 定向 | Maven 3.9.9 / Java 17 容器执行状态、校验、迁移和资源测试 | 85/85 通过 |
| Backend 完整 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp clean test` | 587/587 通过，BUILD SUCCESS |
| Frontend 完整 | `cd frontend && npm test` | 129/129 通过 |
| Dify Worker | Python 3.12 执行 unittest discover | 21/21 通过 |
| n8n Worker | `cd n8n-plugin-worker && npm test` | 9/9 通过 |
| 统一重建 | `docker compose up --build -d` | 最终成功；Backend 587/587；Frontend 生产构建成功 |
| Dify 镜像 | 使用 `plugin-adapters` profile 单独构建 Dify Worker | Python 3.12 镜像构建成功，按需服务保持停止 |
| 运行态与迁移 | `docker compose ps -a`、Backend Flyway 日志 | 五个基础服务 healthy；MySQL schema v20 |
| 差异检查 | `git diff --check`、暂存文件清单与提交后状态复核 | 无空白错误；功能提交只包含确认的语言适配范围 |

### 已知问题与限制

- Dify 已安装包需要在适配器启用并完成 ABI 6 同包指纹重探测后，才会刷新历史组件与模板双语元数据。
- 历史运行节点未保存默认名称身份和本地化元数据时无法可靠判断是否为用户自定义名，因此继续按原始名称显示。
- n8n 本次只执行既有回归，没有新增 n8n 包元数据翻译；本次缺陷与扩展范围均针对基础节点和 Dify。
- 首次统一重建遇到 Docker 瞬时容器 ID 丢失，重试后成功；此前 80 端口由 `domestic-trade-caddy` 占用，已按项目规则停止该容器。

### 下次测试建议与重测触发条件

- 修改 Dify ABI、本地化 JSON 结构、组件/模板指纹、探测刷新或 V19/V20 后续迁移时，必须重跑 Backend、Frontend、Dify Worker 完整测试和 Compose 重建。
- 后续可增加登录态浏览器端到端测试，实际导入 Dify 日历插件并在节点管理、画布、配置、文档和运行详情间切换中英文。
- 若扩展 n8n 多语言元数据，应沿用受控语言、稳定值与展示元数据不参与执行指纹的约束，并建立独立验收用例。

### 回滚方式

- 回滚功能提交 `3154d50` 与 `bf07c70` 后重新执行 `docker compose up --build -d`；V19/V20 为向后兼容的新增列，不应修改已应用的 Flyway 历史或直接删除列。
- 如需回退展示行为，旧版本会忽略新增 JSON 与默认名称列；历史节点名和既有执行配置仍保持可读。

## 📋 工作流国际化缺陷修复测试结果（2026-08-12）

### Git 基准点

Commit: af586ab51bc6736728b4d4f0883cfca3ad280b34
- 提交说明: Fix workflow internationalization gaps
- 测试日期: 2026-08-12
- 分支: master
- 前一业务基准: `c5ca222`（工作流连接分类）
- 重测触发: `backend/src/main/java/` 下国际化配置、工作流执行服务和新增错误消息服务发生业务代码变更。

### 变更范围

- 补齐插件连接凭据配置的中英文后端消息，并让资源测试扫描所有 `BusinessException` 和工作流错误编码引用，防止工作流消息键再次遗漏。
- 工作流运行与节点业务错误以消息键和受限参数结构化保存，查询时按当前请求语言解析；历史纯文本错误保持原样，损坏标记和未知异常返回安全通用文案。
- `lang` 参数改由支持语言白名单解析，优先级为 `lang` 参数、`Accept-Language`、系统默认语言；非法参数回退请求头或默认语言，不再调用只读解析器的 `setLocale`。
- 画布校验返回稳定错误码并使用中英文词典展示；工作流、运行与节点运行状态以及 Run ID、Version 表头统一本地化，未知状态保留原值。
- 未新增依赖、配置、数据库迁移或数据回填；已有 `error_message` 字段继续兼容新旧记录。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 工作流消息键完整且插件非法凭据不退化为 500 | Backend 资源与插件执行器测试 | 扫描工作流业务异常键；传入非对象 credentials | 中英文键齐全；返回 400 与 `workflow.connectionConfigInvalid`；通过 | 异常、兼容、回归 |
| 异步业务错误可按查询语言展示 | Backend H2 状态测试 | 保存带参数的迭代上限错误，分别以 en-US、zh-CN 查询运行和节点日志 | 英文和中文均正确格式化；通过 | 正常、分支、持久化 |
| 历史或损坏错误记录安全兼容 | Backend H2 状态测试 | 查询历史纯文本和损坏结构化标记 | 历史文本原样返回；损坏标记回退通用文案；通过 | 边界、异常、兼容 |
| 任务追踪不展示内部错误标记 | Backend 队列拒绝测试 | 模拟执行器队列已满 | 运行记录保存结构化错误，追踪记录保存可读中文；通过 | 异常、副作用、安全 |
| lang 参数具有正确优先级 | Backend LocaleResolver 测试与真实 HTTPS 请求 | 合法参数覆盖相反请求头；非法参数配合请求头 | 合法参数优先，非法参数安全回退；通过 | 正常、边界、兼容 |
| 画布校验和状态完整双语 | Frontend 工作流图测试 | 无效画布、悬空连线、循环、九种状态与未知状态 | 稳定错误码映射双语；已知状态本地化，未知状态保留；通过 | 正常、分支、边界、兼容 |
| 全量功能无回归 | Backend、Frontend 完整套件与 Compose 环境 | 执行全部自动化测试、生产构建和统一重建 | 872/872 通过；五个基础服务 healthy；通过 | 回归、构建、部署 |

### 测试执行结果

- 正式完整自动化用例总数：872；通过 872，通过率 100%；失败 0，错误 0，跳过 0。
- Backend 完整回归：580/580；最终国际化与工作流定向测试：27/27。
- Frontend 现行完整回归：124/124；历史补充入口：168/168；画布定向测试：13/13。
- `docker compose up --build -d` 一次成功，构建阶段 Backend 再次执行 580/580，Frontend Vite 生产构建成功。
- Adapter Manager、Python Worker、Backend、Frontend、Caddy 五个基础服务全部 healthy；HTTPS readiness 返回 `UP`。

### 关键模块测试

| 模块 | 结果 | 覆盖说明 |
| --- | --- | --- |
| 国际化配置 | 12/12 | 默认语言、请求头、lang 参数优先级、非法参数回退、非法系统配置 |
| 消息资源 | 3/3 | 中英文键对齐、英文无中文、源码引用键完整性 |
| 工作流执行状态 | 7/7 | 运行终态、队列拒绝、错误持久化与双语查询、日志预算、租约恢复、等待恢复 |
| 插件节点执行 | 5/5 | 固定身份、凭据、非法配置、参数校验、触发器事件 |
| Frontend 工作流画布定向 | 13/13 | 图校验、状态双语、历史、连线、节点配置、最大化与布局 |
| Domain / Repository | 未变更 | 本次未修改实体或 Repository 接口，由 Backend 580 项完整回归覆盖兼容性 |

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 缺陷复现 | Maven 3.9.9 / Java 17 容器执行国际化、状态和插件定向测试 | 首次测试编译因新增解析器尚不存在而失败；实现后扫描器发现权限码误判并收紧规则 |
| Backend 最终定向 | Maven 容器执行 `-Dtest=MessageBundleTest,I18nConfigTest,WorkflowExecutionServiceStateTest,WorkflowPluginNodeExecutorTest test` | 27/27 通过 |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn test -B -ntp` | 580/580 通过，BUILD SUCCESS |
| Frontend 缺陷复现 | `cd frontend && node --test tests/workflowGraph.test.js` | 修复前因缺少状态本地化导出而失败，稳定复现 |
| Frontend 定向 | `cd frontend && node --test tests/workflowGraph.test.js` | 13/13 通过 |
| Frontend 现行完整 | `cd frontend && npm test` | 124/124 通过 |
| Frontend 历史补充 | `cd frontend && node --test test/*.mjs` | 168/168 通过 |
| Compose 配置 | `docker compose config --quiet` | 通过 |
| 统一重建 | `docker compose up --build -d` | 一次成功；Backend 580/580；Frontend 生产构建成功 |
| 运行态与语言参数 | `docker compose ps`、HTTPS readiness、合法及非法 lang 参数请求 | 五个基础服务 healthy；readiness UP；参数覆盖和回退符合预期 |
| 差异检查 | `git diff --check`、`git status --short`、暂存差异复核 | 无空白错误；功能提交仅包含确认范围内 14 个文件 |

### 已知问题与限制

- 历史纯文本错误没有消息键，无法在切换语言后重新翻译，继续按原文显示以保证兼容。
- 未知运行时异常统一保存为安全通用错误，不向客户端暴露内部异常文本或堆栈；详细诊断仍应查看受控服务日志和 Trace。
- “失败后继续”节点业务输出为执行时的稳定中文可读文案，运行与节点日志仍可按查询语言本地化；该输出可能被后续节点作为业务数据消费，因此不在查询时改写。
- 本次未修改 Python Worker；Python 运行镜像仍固定为 3.12，未重复执行无关 Worker 单元测试。

### 下次测试建议与重测触发条件

- 新增工作流业务消息键、状态或画布校验分支时，必须同步补充中英文资源及参数化测试。
- 修改 `BusinessException`、工作流错误持久化格式、运行日志模型或国际化解析优先级时，必须重新执行 Backend 完整回归和双语查询测试。
- 后续可增加登录态端到端测试，实际创建失败运行并在界面切换语言后复查同一运行记录。

### 回滚方式

- 先回退功能提交 `af586ab`，再执行 `docker compose up --build -d`；本次没有数据库迁移，已有纯文本或结构化错误记录均不会阻止旧版本读取，但旧版本会原样展示结构化新记录。


## 📋 n8n 与 Dify 安全合格组件全兼容测试结果（2026-08-12）

### Git 基准点

Commit: 1125abc683970b9bc0bc74ff2df2b73e51554052
- 提交说明: Complete n8n node compatibility；Align n8n worker ABI version
- 测试日期: 2026-08-12
- 分支: master
- 关联 Worker 提交: `e4f238d024b891c1b1bb38301850edee0770cd62`
- 验收边界: 仅要求通过现有包大小、路径、依赖来源、结构、表达式和准入安全校验的组件全部兼容；不合格包继续拒绝。

### 变更范围

- Dify ABI 5 补齐 Agent、模型、Pydantic 实体、日志消息、构造期凭据和官方 SDK 常用导入路径；插件依赖继续在受限持久目录安装，官方 Dify SDK 和任意依赖来源继续拒绝。
- n8n ABI 5 展开 `VersionedNodeType` 默认版本，支持真实包使用的数组 join/split、数字转换、有限日期格式化、JSON.parse 与混合模板表达式；不使用 `eval` 或动态函数构造。
- n8n 为运行环境隐式提供的 `lodash/set` 增加最小本地兼容层，并显式拒绝 `__proto__`、`prototype` 和 `constructor` 路径，避免原型污染。
- Backend 同步要求 n8n ABI 5；ABI 4 及更旧缓存会重新探测，不能继续复用旧的部分兼容结论。
- 未放宽路径穿越、链接、包大小、解压体积、文件数、依赖来源、危险表达式、非法 hook 和插件准入审批规则。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| Dify 安全合格组件全部可加载 | Worker 全量缓存复检；86 个已缓存安全合格包 | 重新解析并用隔离子进程加载全部组件 | 310/310 通过，失败 0；通过 | 正常、兼容、真实数据 |
| n8n 剩余部分兼容节点全部转为支持 | Worker 隔离重打包；10 个当前非完全兼容包 | 版本节点、routing、Mailtrap 依赖重新探测 | 10 包、13 组件全部 ABI 5 / SUPPORTED；通过 | 正常、兼容、真实数据 |
| 版本节点选择稳定 | n8n Worker 正式集成测试 | 两个版本且默认版本为 2 的 VersionedNodeType | 探测身份正确，真实调用返回版本 2；通过 | 分支、兼容 |
| 声明式转换安全执行 | n8n Worker HTTP 集成测试 | join、split、Number/isNaN、日期、JSON 和混合模板 | 请求路径、查询参数和业务输出正确；通过 | 正常、边界、业务结果 |
| 最小依赖不引入原型污染 | n8n Worker 安全测试 | `lodash/set` 正常路径与 `__proto__` 恶意路径 | 正常字段写入，污染路径无副作用；通过 | 安全、恶意输入 |
| 危险包和表达式继续拒绝 | 两个 Worker 既有安全回归 | 路径穿越、摘要不符、任意依赖源、任意表达式和非法 hook | 保持拒绝或 PARTIAL/UNSUPPORTED；通过 | 异常、安全、回归 |
| 历史缓存自动失效 | Backend Worker Client/Probe 测试 | n8n ABI 5、Dify ABI 5 与旧 ABI 响应 | 当前版本接受，旧版本拒绝并进入重探测；通过 | 状态、兼容、副作用 |

### 测试执行结果

- 正式自动化用例总数：720；通过 720，通过率 100%；失败 0，错误 0，跳过 0。
- Backend 完整回归：576/576；最终 Worker Client/Probe 定向回归：21/21。
- Frontend 完整回归：115/115；Dify Worker（Python 3.12）：20/20；n8n Worker：9/9。
- 真实缓存复检：Dify 86 包、310 组件全部通过；n8n 原 10 个非完全兼容包共 13 个组件全部 ABI 5 / SUPPORTED。
- `docker compose up --build -d` 成功；Backend、Frontend、Python Worker、Adapter Manager、Caddy 全部 healthy。两个插件 Worker 最新镜像单独启动均达到 healthy，随后由按需管理器恢复 STOPPED。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Dify Worker | Python 3.12 执行 `python3.12 -m unittest discover -s tests -v` | 20/20 通过 |
| n8n Worker | 执行 `npm test` | 9/9 通过 |
| Backend 定向 | Maven 3.9.9 / Java 17 容器执行 Worker Client 与 Probe Service | 21/21 通过 |
| Backend 完整 | Maven 3.9.9 / Java 17 容器执行 `mvn -B -ntp test` | 576/576 通过 |
| Frontend 完整 | 执行 `npm test` | 115/115 通过 |
| n8n 真实缓存 | 在 Worker 数据卷的隔离目录重打包并使用最新镜像重新探测 | 10 包、13/13 组件 SUPPORTED，ABI 5 |
| Dify 真实缓存 | 最新镜像逐包重解析元数据并以隔离子进程加载组件 | 86 包、310/310 组件通过 |
| 统一重建与运行态 | `docker compose up --build -d`、Worker profile 单独健康检查 | 五个基础服务 healthy；两个 Worker 镜像 healthy 后恢复 STOPPED |

### 已知限制、清理与回滚

- 兼容验证不使用生产凭据，也不调用收费或第三方业务 API；供应商鉴权、配额和实时响应仍由实际连接配置与外部服务决定。
- 只对当前缓存版本和正式测试夹具作出结论；新市场版本会按 ABI 与安全校验重新探测，结构或安全不合格的包仍被拒绝。
- n8n 日期与表达式解释器只实现已审计白名单；新增语法必须先增加正常、边界和危险表达式测试，禁止改为任意 JavaScript 执行。
- 真实缓存复检使用的数据卷隔离目录和临时归档已清理；一次误在根目录执行 npm 产生的宿主日志也已删除，仓库无调试文件。
- 回滚可依次回退 `1125abc` 与 `e4f238d`；回滚后必须重建服务，旧 ABI 5 缓存会因 Backend/Worker 版本变化重新探测。

## 📋 插件准入清单与强制审批测试结果（2026-08-12）

### Git 基准点

Commit: 1125abc683970b9bc0bc74ff2df2b73e51554052
- 提交说明: Add plugin admission controls；Align n8n worker ABI version
- 测试日期: 2026-08-12
- 分支: master
- Backend 业务代码差异: 新增插件许可证、外部服务、数据类型准入记录、独立权限与审批接口；未批准插件禁止启用和执行，包指纹变化自动重置审批。

### 变更范围

- V18 为全部现有市场插件建立 `PENDING` 准入记录并停用插件；记录插件身份、许可证、外部服务、固定数据类型、备注和审批审计信息。
- n8n 从 `package.json` 与节点声明、Dify 从 manifest 和 YAML 声明预填许可证及固定 HTTPS 服务域名；自动识别结果必须人工核对。
- 保存资料会重置审批并停用插件；只有资料完整、兼容状态为 `SUPPORTED` 且管理员批准后才能启用和执行。
- 新增 `workflow:plugin:admission` 权限和节点管理页准入清单，不改变 n8n/Dify 适配服务按需启停规则。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 完整记录准入字段 | Backend Migration、Registry、Worker 测试 | 安装插件并读取许可证、来源、版本、外部服务和数据类型 | V18 建表成功，Worker 候选被规范化写入；通过 | 正常、数据迁移 |
| 未批准插件不能使用 | Backend Registry 测试 | PENDING 插件启用或执行组件 | 返回 `workflow.pluginAdmissionRequired`，组件选项不可见；通过 | 权限、安全、异常 |
| 完整资料可审批 | Backend Registry 与 Controller 测试 | 提交 MIT、合法域名、固定数据类型后批准 | 状态变为 APPROVED，插件同步启用且可执行；通过 | 正常、接口、副作用 |
| 非法资料被拒绝 | Backend Registry 参数边界 | HTTP 许可证地址、非法域名、空许可证、NO_DATA 与其他分类并选 | 返回准入资料非法；空服务名按域名补全；通过 | 边界、恶意输入 |
| 资料或包变化重新审批 | Backend Registry 测试 | 修改准入资料、拒绝、同指纹重装、不同指纹升级 | 修改/升级重置 PENDING 并停用；同指纹保留批准；通过 | 状态、兼容、回归 |
| 管理入口受独立权限控制 | Controller、Initializer、Frontend 测试 | 查询、编辑和审批准入记录 | 三个接口和页面入口均要求独立权限；通过 | 权限、安全 |
| 运行环境无回归 | 完整测试与 Compose 重建 | 两个 Worker、Backend、Frontend、统一重建 | Backend 576/576、Frontend 115/115、Dify 20/20、n8n 9/9；基础服务 healthy；通过 | 回归、部署 |

### 测试执行结果

- Backend 完整回归：576/576 通过，失败 0，错误 0，跳过 0；Compose 构建阶段再次通过 576/576。
- Backend 最终 ABI/准入定向回归：Registry 与 Worker Client 5/5 通过；并行 n8n ABI 5 合入后 Worker Client 2/2 再次通过。
- Frontend 完整回归：115/115 通过；Dify Worker（Python 3.12）：20/20 通过；n8n Worker 最终完整回归：9/9 通过。
- `docker compose up --build -d` 成功；Backend、Frontend、Python Worker、Adapter Manager、Caddy 全部 healthy，Flyway V18 已在真实 MySQL 应用。

### 测试过程问题与处理

- V18 的 MySQL `UPDATE ... JOIN` 和 `BIT` 语法不直接被 H2 接受；生产迁移保持不变，迁移测试仅在执行夹具内转换为 H2 等价语法，并通过真实 MySQL 启动验证 Flyway 校验和。
- 工作区同时合入 n8n 声明式表达式兼容功能；其初始完整测试 8/9，完成后重跑为 9/9。准入提交通过交互暂存隔离，未把未完成的兼容代码夹带进准入提交。
- 主机没有 Maven，使用项目固定的 Maven 3.9.9 / Java 17 容器执行；未创建或遗留调试文件。

### 已知限制与回滚

- 自动识别只读取显式清单和静态 YAML/节点声明，不扫描或执行插件代码，因此动态拼接的服务地址必须人工补充。
- 本清单覆盖通用市场插件包；系统原生节点和不经过插件注册表的内建适配路径不纳入本次准入表。
- V18 会停用全部历史插件，回滚 Java/前端提交不会自动恢复启用状态；如需回滚数据，必须依据迁移前备份和 `enabled_before_admission` 经人工确认恢复，禁止修改 Flyway 历史。
- 修改准入字段、审批状态机、Worker ABI/元数据、插件启用校验或 V18 后续迁移时，必须重跑两个 Worker、Backend/Frontend 完整回归和 Compose 重建。

## 📋 n8n 与 Dify 适配服务按需启停测试结果（2026-08-11）

### Git 基准点

Commit: 848d8157b7d1423d97723e0c399db90a40f6f768
- 提交说明: Add on-demand workflow adapter services
- 测试日期: 2026-08-11
- 分支: master
- 上一代码基准点: `bb68113e`（Require Dify worker ABI version four）
- Backend 业务代码差异: 新增适配器期望状态、实际容器状态、独立权限、在途任务锁和市场/Worker 调用保护；n8n 与 Dify 默认关闭并由节点管理页分别控制。

### 变更范围

- Docker Compose 将 n8n 与 Dify Worker 放入 `plugin-adapters` profile，默认不启动；Backend 不再把两个 Worker 作为启动健康依赖。
- 新增隔离的 Go `adapter-manager`，使用内部令牌、固定来源白名单和 Docker Compose CLI 启停两个 Worker；Backend 本身不挂载 Docker Socket。
- 新增两个系统托管开关，首次启动默认均为 `false`，并增加 `workflow:adapter:manage` 独立权限及节点管理页双开关。
- 所有 Worker 探测、删除与执行请求持有来源读锁；关闭操作必须取得写锁，存在在途任务时返回冲突且不停止容器。
- 关闭状态禁止浏览或导入对应市场，并拒绝需要该 Worker 的插件调用；启停失败保留明确状态且由定时对账恢复期望状态。
- Dify ABI 现有改动使用 Pydantic v2 扩展点，生产依赖固定为 Pydantic 2.13.4；无数据库表迁移或业务数据迁移。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 默认不启动两个 Worker | Compose 契约与真实重建 | 不启用 profile 执行标准 Compose 重建 | 两个 Worker 最终均为 Exited/STOPPED，五个基础服务 healthy；通过 | 正常、部署、兼容 |
| n8n 与 Dify 分别控制 | manager 单元测试与真实运行态 | 依次启动/停止 N8N、DIFY 并查询另一来源 | 目标来源独立 ENABLING→RUNNING→STOPPED，另一来源始终 STOPPED；通过 | 正常、分支、回归 |
| 在途任务阻止关闭 | Backend 并发单元测试；一个请求持有来源读锁 | 在任务未释放时关闭同一来源 | 返回 HTTP 409 业务异常，不调用 manager、不写 false；通过 | 状态冲突、并发、副作用 |
| 关闭后不得访问适配能力 | Backend Service/Client 测试 | 关闭 Dify 执行 Worker 动作；关闭 N8N 浏览市场 | 在访问 Worker 或第三方市场前返回 adapterDisabled；通过 | 异常、安全、回归 |
| 权限和命令输入受限 | Controller/Client/manager 测试 | 无令牌、错误权限、恶意来源、额外 JSON 字段 | 独立管理权限生效；令牌常量时间校验；任意服务名和命令注入被拒绝；通过 | 权限、安全、恶意输入 |
| 启停失败不伪造持久状态 | Backend 和 manager 失败测试 | manager 拒绝、命令失败、畸形状态响应 | 不提前保存期望值，仅暴露有限错误码且不返回 Compose 输出；通过 | 异常、安全、兼容 |
| 页面正确展示实际状态 | Frontend 完整与源码契约测试 | 来源切换、启停过渡、失败、离开页面 | 独立开关、权限、轮询清理、运行前禁用市场按钮均符合预期；通过 | 正常、边界、回归 |

### 测试执行结果

- 正式自动化用例总数：708；通过 708，通过率 100%；失败 0，错误 0，跳过 0。
- Backend 完整回归：567/567；适配器、系统参数、市场和 Worker Client 定向回归：47/47，最终新增 manager/lifecycle 定向复测：7/7。
- Frontend 完整回归：114/114，其中适配器与 Compose 新增契约测试 3/3。
- Dify Worker：17/17；n8n Worker：6/6；adapter-manager Go 测试：4/4。
- 标准 Compose 统一构建成功，构建内 Backend 再次 567/567，Frontend 生产构建和 adapter-manager 镜像内测试通过。
- 最终运行态：Adapter Manager、Backend、Frontend、Python Worker、Caddy 全部 healthy；n8n 与 Dify Worker 均为 Exited/STOPPED；HTTPS health 与 readiness 均返回 `UP`。

### 关键模块测试

- Lifecycle Service：默认值、来源隔离、关闭保护、读写锁竞态、失败不落库和实际/期望状态合并。
- Adapter Manager：固定命令白名单、异步操作串行化、Compose JSON 兼容解析、令牌鉴权、请求体限制和错误脱敏。
- Controller/权限：状态读取复用节点查看权限，启停使用独立 `workflow:adapter:manage` 权限，非法命令和来源被拒绝。
- Marketplace/Worker Client：关闭时不访问第三方或 Worker，开启时所有探测与插件调用计入在途任务。
- Frontend：双开关、实际状态标签、过渡轮询、页面卸载清理、权限控制和未运行时禁止市场导入。
- 部署：默认 profile、manager Docker Socket 隔离边界、独立实际启停、基础服务健康和 HTTPS readiness。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Backend 定向 | Maven 3.9.9 / Java 17 容器执行 Adapter Lifecycle/Manager Client、Marketplace、Worker Client、Controller、System Configuration 和 Data Initializer | 47/47 通过；新增最终复测 7/7 通过 |
| Backend 完整 | Maven 3.9.9 / Java 17 容器执行 `mvn test -B -ntp` | 567/567 通过，0 失败、0 错误、0 跳过 |
| Frontend 完整 | `cd frontend && npm test` | 114/114 通过 |
| n8n Worker | `cd n8n-plugin-worker && npm test` | 6/6 通过 |
| Dify Worker | Python 3.12 Dify 镜像执行 `python -m unittest discover -s tests` | 17/17 通过 |
| Adapter Manager | 固定 Go 1.26.5 镜像与 manager Dockerfile 内执行 `go test ./...` | 4/4 通过；镜像构建成功 |
| Compose 静态 | 默认及 `plugin-adapters` profile 执行 `docker compose config --quiet` | 均通过；默认服务清单不含两个 Worker |
| 统一重建 | `docker compose up --build -d`，异步替换完成后执行 `docker compose up -d` | 五个基础服务 healthy；构建内 Backend 567/567 通过 |
| 实际独立启停 | 通过受鉴权 manager 接口依次控制 N8N、DIFY 并轮询状态 | 两个来源均独立完成启动与停止，未联动另一 Worker |
| 健康与最终状态 | HTTPS health/readiness、manager 状态、`docker compose ps -a` | 两个端点 `UP`；两个 Worker STOPPED；基础服务 healthy |
| 差异与提交 | `git diff --check`、`git diff --cached --check`、路径限定暂存 | 检查通过；功能提交未包含并行测试报告和无关依赖改动 |

### 测试过程问题与处理

- 宿主机未安装 Maven 和 Go 命令，按项目固定 digest 的 Maven/Go 容器执行，未降低测试范围。
- 首次前端完整测试因既有契约要求保留 `onBeforeUnmount(stopMarketplacePolling)` 调用形式失败；改为注册两个等价卸载钩子后 114/114 通过。
- Dify 完整测试最初因既有 ABI 代码使用 Pydantic 但生产镜像未声明依赖而 16/17；经用户确认扩大范围，固定 Pydantic 2.13.4 后 17/17 通过。
- 统一重建完成镜像后容器仍处于异步替换和健康依赖等待；再次执行 `docker compose up -d` 后依赖链全部 healthy，未发生端口冲突。
- 本任务期间 master 有并行提交进入；提交前重新基于最新 HEAD 执行完整测试和 Compose 重建，并通过路径限定暂存避免夹带并行改动。
- 未创建持久调试脚本或仓库内临时文件。

### 已知问题与限制

- manager 必须挂载 Docker Socket 才能让网页开关真正启停容器；Socket 等同宿主机高权限，风险通过独立服务、内部令牌、固定服务白名单、只读项目挂载和不暴露宿主机端口进行收敛，但不能完全消除。
- Worker 首次启用会按需构建镜像，耗时取决于镜像缓存和依赖网络；页面通过异步状态轮询展示过程，未运行完成前禁止市场操作。
- 在途锁以当前单 Backend 实例为边界；现有 Compose 使用固定 Backend 容器且不支持水平扩容。未来若支持多实例，必须改为分布式租约或统一任务计数后才能保持关闭保护语义。
- 关闭只保护由 Backend 发起并持锁的探测和插件任务；管理员绕过应用直接操作 Docker 不受该业务锁约束。

### 下次测试建议

1. 在受控 CI Docker 主机增加 manager 真实启停集成测试，并模拟构建失败、Docker Daemon 不可用和容器健康超时。
2. 若计划支持多 Backend 实例，先增加 Redis/数据库分布式在途计数与原子关闭协议，再开放扩容配置。
3. 为节点管理页补充浏览器级测试，覆盖启用耗时、失败重试、在途任务冲突提示和权限不足状态。

### 重测触发条件与回滚

- 修改适配器开关键、manager 协议、Docker profile/服务名、Worker Client 调用边界、在途锁、权限或节点管理开关时，必须重跑 Backend/Frontend/两个 Worker/manager 完整测试、Compose 重建及实际双来源启停。
- 代码可回退提交 `848d815`，随后停止并移除遗留 adapter-manager/Worker 容器，再执行回退版本的 `docker compose up --build -d`；本次无数据库表迁移，两个系统托管参数可保留或在确认无新版本使用后删除。

## 📋 Dify 模型 ABI 与 n8n 声明式路由兼容性测试结果（2026-08-11）

### Git 基准点

Commit: 47eb917b8f55d60733b10575bde06edd5a8382b5
- 提交说明: Improve Dify and n8n plugin compatibility
- 测试日期: 2026-08-11
- 分支: master
- Backend 业务代码差异: 插件探测结果增加 Worker ABI 版本校验和旧缓存重探测，并将内部失败细节聚合为稳定、安全的公开原因码。

### 变更范围

- Dify Worker ABI 3 支持模型提供方源码，覆盖 LLM、文本嵌入、语音转文本、内容审核、文本转语音和重排序模型的发现、Schema 与调用参数适配。
- n8n Worker ABI 3 支持声明式 routing 的安全表达式子集、`preSend`/`postReceive` hook、输出整理指令、二进制输入输出和 multipart 请求；解释器未使用 `eval` 或动态函数构造。
- Backend 拒绝低于当前来源要求的 Worker ABI，读取旧持久化 JSON 时自动排队重探测；探测失败仅向前端暴露白名单原因码。
- 前端为包大小、内容限制、依赖拒绝、包结构、routing、运行时 ABI 和依赖不可用提供中英文提示。
- 未新增依赖、数据库迁移、公开接口或配置；未修改第三方代码。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| Dify 模型插件可发现并调用 | Dify Worker 单元/集成测试；构造模型提供方包 | LLM、Embedding 等模型声明和标准消息参数 | 生成稳定组件 ID、Schema，并由隔离子进程完成参数适配；通过 | 正常、分支、兼容 |
| n8n 声明式 routing 可安全执行 | n8n Worker 集成测试；本地 HTTP 服务 | 条件表达式、JSON.parse、preSend/postReceive、输出指令、二进制和 multipart | 请求与响应符合声明，危险表达式和非函数 hook 被拒绝；通过 | 正常、边界、异常、安全 |
| Worker 升级后旧缓存失效 | Backend Service/Client 测试；旧 ABI JSON | Dify/N8N 低版本结果和 ABI 3 结果 | 低版本拒绝或重新排队，ABI 3 可复用；通过 | 兼容、状态、副作用 |
| 前端只展示安全原因 | Backend 聚合测试、Marketplace 回归、Frontend 双语测试 | 原始路径/依赖错误、routing 与 ABI 错误 | 返回稳定白名单原因码，不泄露路径、URL 或堆栈，并有双语文案；通过 | 异常、安全、回归 |
| 真实市场包兼容性改善 | 运行态 MySQL 探测缓存；确认抽检包均未安装 | 3 个 Dify 包、5 个 n8n 包重新探测 | 8/8 为 COMPLETE/SUPPORTED，结果 ABI 均为 3；通过 | 真实数据、兼容、回归 |
| 既有功能和部署无回归 | Backend/Frontend 完整测试与 Compose 重建 | 全套自动化测试、生产构建和 HTTPS 入口 | 856/856 通过，六服务 healthy，HTTPS 200；通过 | 回归、部署 |

### 测试执行结果

- 正式自动化用例总数：856；通过 856，通过率 100%；失败 0，错误 0，跳过 0。
- Backend 完整回归：556/556；最终 ABI 定向回归：17/17。
- Frontend 应用回归：111/111；前端 Node 回归：168/168。
- Dify Worker：15/15；n8n Worker：6/6。
- Compose 统一构建和启动成功；Backend 镜像构建执行完整测试，Frontend 生产构建成功，最终六个服务全部 healthy，HTTPS 首页返回 200。

### 关键模块测试

- Dify Worker：模型源码发现、六类模型组件、消息/工具/模型参数转换、隔离调用、无效包和缓存版本。
- n8n Worker：routing 兼容性分析、安全表达式、请求 hook、响应 hook、输出指令、二进制、multipart、危险输入拒绝和缓存指纹。
- Backend：ABI 响应验证、旧 JSON 重探测、依赖状态、并发领取、重试、公开失败原因与兼容性聚合。
- Frontend：新增公开原因码的中英文完整性及现有模板目录回归。
- 运行态：真实 Worker、MySQL 探测队列、Caddy HTTPS 与六服务健康检查。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Dify Worker | `python3.12 -m unittest discover -s tests` | 15/15 通过 |
| n8n Worker | `cd n8n-plugin-worker && npm test` | 6/6 通过 |
| Backend 定向 | Maven 3.9.9 / Java 容器执行 Worker Client 与 Probe Service | 17/17 通过 |
| Backend 完整 | Maven 3.9.9 / Java 容器执行 `mvn -B -ntp test` | 556/556 通过，0 失败、0 错误、0 跳过 |
| Frontend 完整 | `cd frontend && npm test`；`node --test test/*.mjs` | 111/111、168/168 通过 |
| Compose 重建 | `docker compose up --build -d` | 六镜像构建成功，六服务 healthy |
| 真实插件抽检 | 重置 8 条未安装插件的探测缓存并等待后台重新探测 | Dify DeepSeek/Gemini/OpenAI 与 n8n ElevenLabs/Netgsm/AIScraper/JigsawStack/Templated 全部 ABI 3、SUPPORTED |
| 入口与差异 | `curl -k https://localhost/`、`docker compose ps`、`git diff --check` | HTTP 200、六服务 healthy、差异检查通过 |

### 测试过程问题与处理

- 首次统一启动时 80/443 端口被 `domestic-trade-caddy` 占用；按项目规则停止占用容器后重新执行 Compose，最终启动成功。
- routing 实现扩展后发现仍沿用 n8n ABI 2，可能复用旧缓存；提升至 ABI 3 后重跑定向、完整、Compose 和真实插件测试，旧结果已正确失效。
- 主机未直接使用 Maven，采用项目既有 Maven 3.9.9 / Java 容器执行完整测试；Python Worker 使用 3.12。
- 未创建持久调试脚本或临时仓库文件。

### 已知问题与限制

- n8n 声明式表达式只实现已审计的常用安全子集；需要任意 JavaScript、未支持 hook 形态或复杂运行时上下文的节点仍会安全标记为不支持。
- Dify 模型抽检验证了真实包加载、组件契约和隔离参数转换，但没有使用生产密钥调用外部收费模型 API；供应商侧鉴权、配额和实时响应仍由部署环境决定。
- 真实抽检只覆盖 8 个代表包，不能证明市场全部历史版本均兼容；其余包会按 ABI 版本和访问节奏逐步重探测。

### 下次测试建议

1. 基于仍为 `ROUTING_UNSUPPORTED` 的真实包样本，逐项增加经过安全审计的表达式或 hook 语义，并保持拒绝测试。
2. 在具备测试密钥和预算隔离的环境增加 Dify 六类模型的供应商沙箱调用测试。
3. 监控 ABI 3 重探测后的支持率、失败原因分布和探测耗时，优先处理高使用量插件。

### 重测触发条件与回滚

- 修改 Worker ABI、Dify 模型适配、n8n routing 解释器、探测缓存、公开原因码或插件调用参数时，必须重跑两个 Worker、Backend/Frontend 完整回归、Compose 重建和代表包抽检。
- 代码可回退提交 `47eb917`；运行态无需反向数据库迁移，旧 ABI 结果会按当前 Backend 要求重新探测。

## 📋 市场插件按实际能力分类测试结果（2026-08-11）

### Git 基准点

Commit: fa785c9aa313d7ca0b283bf247b2ba3d5d05d4ed
- 提交说明: Classify marketplace plugins by capability；Align marketplace preview categories
- 测试日期: 2026-08-11
- 分支: master
- 上一测试报告基准点: `a1236abde93d3ccce5ca516c6cd212fedd397e5f`
- Backend 业务代码差异: 市场插件模板不再仅按通用节点类型落入“网络与接口”；导入和市场预览统一依据组件技术类型、包身份、组件身份、名称与说明推导受控功能分类。

### 变更范围

- `TRIGGER` 固定归入触发器，`MODEL` 与 `AGENT_STRATEGY` 固定归入 AI 能力，`DATASOURCE` 固定归入数据库、缓存与存储。
- `ACTION`、`TOOL` 与 `EXTENSION` 按高可信语义依次识别消息队列、通知通信、数据存储、文本文档、数据转换、AI 和网络接口；无法可靠判断时归入基础节点，不再默认归入网络接口。
- 市场列表在插件完成探测后使用与导入草稿相同的分类入口，单组件插件的预览分类和最终模板分类保持一致。
- V17 只处理来源为 N8N/DIFY、当前仍为 `NETWORK_API` 且节点类型为通用插件类型的历史模板；管理员已调整到其他分类的模板、系统模板和 Tavily 等原生市场节点保持不变。
- 未新增功能分类、依赖、公开接口或前端分类配置；插件执行、权限、凭据、探测状态和固定版本语义保持不变。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 技术类型优先分类 | Backend Catalog 参数化测试 | TRIGGER、MODEL、AGENT_STRATEGY、DATASOURCE 及普通名称 | 分别进入 TRIGGER、AI、AI、DATA_STORAGE；通过 | 正常、分支、兼容 |
| 动作按实际能力分类 | Backend Catalog 参数化测试 | Slack、RabbitMQ、PostgreSQL、PDF、JSON/YAML、OpenAI、Tavily | 分别进入通知、消息队列、存储、文档、转换、AI、网络接口；通过 | 正常、参数化、业务结果 |
| 未知或空元数据不再误入网络 | Backend Catalog 与 Marketplace Service 测试 | 空值、未知生产力助手、无语义 Example 动作 | 安全回退 BASIC；导入草稿保存 BASIC；通过 | 边界、异常、兼容 |
| 市场预览与导入规则一致 | Backend Marketplace Service；插件已 COMPLETE/SUPPORTED | 浏览 Slack 组件并导入通用组件 | 预览返回 NOTIFICATION，导入调用同一分类器；通过 | 正常、接口、回归 |
| 历史误分类安全整理 | H2 MySQL 模式执行真实 V17 SQL | N8N Slack、Dify Datasource、未知动作、管理员分类、系统模板、Tavily | 前三项分别为 NOTIFICATION、DATA_STORAGE、BASIC；后三项保持原分类；通过 | 数据迁移、边界、安全、兼容 |
| 原有市场和模板能力无回归 | Backend 完整测试、Frontend 完整测试、Compose 重建 | 市场浏览、探测、导入、注册、模板维护及页面目录 | Backend 552/552、Frontend 110/110；六服务 healthy；通过 | 回归、权限、部署 |

### 测试执行结果

- Backend 正式自动化回归：552/552 通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 分类与市场定向回归：49/49 通过；并行市场状态变更合入后再次执行 Marketplace Service 11/11 通过。
- Frontend 完整回归：110/110 通过；本次分类功能未修改前端代码。
- 缺陷复现阶段新增分类测试按预期编译失败，明确缺少 `marketplaceCategory` 能力入口；实现后全部通过。
- Compose 统一构建成功，构建阶段完成 Backend 编译与测试、Frontend 生产构建；最终六个服务全部 healthy。

### 关键模块测试

- Catalog 层：覆盖技术类型优先、七类语义规则、大小写与分隔符规范化、空值和未知能力回退。
- Marketplace Service 层：验证通用插件草稿分类、市场预览分类、固定组件身份、敏感配置不泄漏、原生 n8n 和 Dify Tavily 路径回归。
- 数据迁移层：V17 在可执行 H2 MySQL 兼容数据库中验证更新结果，并由 Schema Resource 测试验证来源、旧分类和插件节点类型约束。
- 权限与安全层：分类只消费已下载市场条目和 Worker 返回的非敏感组件元数据，不读取凭据、参数值或执行第三方代码；既有权限测试完整通过。
- 部署层：真实 MySQL Flyway 日志确认从 V16 成功应用 V17，随后重建确认当前 Schema 为 V17；HTTPS readiness 返回 `UP`。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 缺陷复现 | Maven 3.9.9 / Java 17 容器执行 Catalog 与 Marketplace Service 新增测试 | 测试编译按预期失败，缺少实际能力分类入口 |
| Backend 定向回归 | Maven 容器执行 Catalog、Marketplace Service、Schema、V17 Migration、Service Access | 49/49 通过；最终 Marketplace Service 11/11 通过 |
| Backend 完整回归 | Maven 容器执行 `mvn test -B -ntp` | 552/552 通过，0 失败、0 错误、0 跳过 |
| Frontend 完整回归 | `cd frontend && npm test` | 110/110 通过 |
| Compose 重建 | `docker compose up --build -d` | 六个镜像构建成功；Backend 与 Frontend 构建成功；六服务 healthy |
| Flyway 与运行态 | Backend 启动日志、`docker compose ps`、HTTPS readiness | V17 在真实 MySQL 成功应用；Schema 当前为 17；readiness HTTP 200 / UP |
| 差异与清理 | `git diff --check`、隔离索引与路径限定提交、临时索引检查 | 本任务两个代码提交未夹带并行 Worker 改动；临时索引已删除 |

### 测试过程问题与处理

- 主机未安装 Maven，按项目既有方式改用固定 Maven 3.9.9 / Java 17 容器执行，未降低测试范围。
- 并行市场“已导入状态”功能一度处于测试先于生产代码的中间状态，并有测试夹具使用不存在的 `INTEGRATION` 分类；其代码完成并把夹具改为合法 BASIC 后，本任务重新执行定向和完整回归。
- Compose 重建期间另一个并行任务再次触发统一重建；最终等待第二次重建完全结束，并重新确认六服务 healthy、MySQL Schema 为 V17 和 HTTPS readiness 为 UP。
- 未创建持久调试脚本或仓库内临时文件；提交隔离使用的临时 Git 索引已经清理。

### 已知问题与限制

- 分类是确定性的高可信规则，不使用不透明模型推断；市场元数据过少或能力跨多个业务域时会回退到基础节点，管理员仍可在导入后手工调整。
- 通用插件卡片当前以首个受支持组件展示预览类型；同一插件包含多个不同能力组件时，各导入模板仍会分别按自身组件元数据分类。
- V17 是前向数据迁移，只自动整理仍在网络接口分类的通用插件模板；已经手工放入其他分类的模板不会自动重新判断。
- V17 已在当前运行数据库执行；若需回滚历史分类，只能依据迁移前数据库备份或业务确认后的反向 SQL 恢复，单纯回退 Java 提交不会反转已迁移数据。

### 下次测试建议

1. 收集回退到 BASIC 的真实插件样本，基于明确业务语义增补高可信词条并为每个新词条增加参数化用例。
2. 若后续市场接口支持组件级卡片，分别展示每个组件的实际分类，避免多组件插件只显示首个组件类型。
3. 对管理员手工调整过的分类保留人工优先策略；如增加“重新自动分类”操作，应提供变更预览、选择范围和审计记录。

### 重测触发条件与回滚

- 修改市场分类词条、优先级、组件类型映射、市场预览聚合、导入草稿分类、模板分类枚举或 V17 后续数据整理时，必须重跑 Catalog、Marketplace Service、Migration、Schema 定向测试、Backend/Frontend 完整回归和 Compose 重建。
- 代码回滚可依次回退 `fa785c9` 和 `111c658`；V17 数据需从备份恢复或另行确认反向迁移，禁止直接删除 Flyway 历史记录。

## 📋 插件市场完整导入状态修复测试结果（2026-08-11）

### Git 基准点

Commit: fa785c9aa313d7ca0b283bf247b2ba3d5d05d4ed
- 提交说明: Classify marketplace plugins by capability；Mark fully imported marketplace plugins；Align marketplace preview categories
- 本功能提交: `a1236ab84e780bccf29ec390fc9c37ba13cb7751`
- 测试日期: 2026-08-11
- 分支: master
- 上一测试报告基准点: `9750a2c483ebc8cc6cc9b5ce8da0fab29d157be1`
- Backend 业务代码差异: 市场查询聚合未作废模板的外部键和指纹；只有当前插件全部受支持组件均存在且指纹一致时返回 `imported=true`；同一基准范围还包含插件能力分类与市场预览分类对齐。

### 变更范围

- 市场节点和 Dify 子能力响应新增 `imported` 状态；原生节点按单模板判定，通用插件按当前全部 `SUPPORTED` 组件聚合，Dify 父卡片按兼容子能力聚合。
- 导入状态查询只读取未作废模板的 `external_key` 和 `external_fingerprint`，不读取或返回加密配置；模板仅停用但未删除时仍视为已导入。
- 任一组件被软删除、缺失或指纹与当前包版本/Schema 不一致时，市场项保持可导入，以便恢复组件或进入既有版本更新确认流程。
- Frontend 对完整导入的卡片和能力显示“已导入”并禁止重复勾选；轮询刷新会清理已经导入或不兼容的临时选择。
- 本次未新增依赖、配置、数据库迁移或数据修复；接口仅向现有市场响应追加布尔字段，现有字段和权限保持兼容。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 全部组件未删除时显示已导入 | Backend Marketplace Service；当前包含两个受支持组件且模板指纹一致 | 再次查询同一市场插件 | 父卡片 `imported=true`；通过 | 正常、核心验收、回归 |
| 删除任一组件后恢复可导入 | Backend Marketplace/Template Service；两组件中一条模板软删除 | 再次查询市场插件 | 父卡片 `imported=false`，允许重新导入恢复；通过 | 边界、状态恢复 |
| 停用不等同于删除 | Backend H2 Service；模板 `enabled=false`、`voided=false` | 查询当前来源导入指纹 | 停用模板仍返回，已删除模板被排除；通过 | 状态边界、兼容 |
| 版本或 Schema 变化不误标 | Backend Marketplace Service；全部外部键存在但一个指纹变化 | 查询当前市场版本 | 父卡片 `imported=false`，保留更新确认入口；通过 | 状态冲突、兼容 |
| 原生与 Dify 子能力正确聚合 | Backend Marketplace Service；原生节点和含两个动作的 Dify 插件 | 分别只导入一个动作、再导入全部动作 | 原生节点正确标记；子能力分别标记，全部完成后父卡片已导入；通过 | 分支、兼容、回归 |
| 已导入项不可重复选择 | Frontend 契约测试；市场响应带 `imported=true` | 渲染卡片并刷新选择集合 | 显示中英文“已导入”，节点和动作复选框禁用且选择被过滤；通过 | 交互、幂等 |
| 完整构建与部署无回归 | 当前基准点、功能提交、集成工作区和 Compose | 完整自动化、六镜像重建、健康检查 | 当前基准点 849/849、功能提交 848/848；六服务 healthy，readiness 为 UP；通过 | 构建、部署、回归 |

### 测试执行结果

- 当前基准点 `fa785c9` 自动化回归共 849 项，849 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- 本功能提交 `a1236ab` 自动化回归共 848 项，848 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：当前基准点 553/553、功能提交 552/552 通过；本功能市场定向回归 18/18 通过。
- Frontend 两套完整回归：110/110、168/168 通过。
- Dify Worker 使用 Python 3.12：14/14 通过；n8n Worker：4/4 通过。
- 包含并行未提交 Worker/探测改动的集成快照中，Dify Worker 15/15、n8n Worker 5/5 通过；这些新增用例不归因于本提交，且并行工作在此后仍继续变化，因此不把该快照总数作为 Git 基准点总数。
- 缺陷复现阶段 Frontend 新用例稳定失败，实际值缺少“已导入”文案；实现后通过。Backend 主机无 Maven，后续统一使用项目固定 Maven 3.9.9 / Java 17 容器执行。

### 关键模块测试

- Service 层：验证只返回指定来源、未作废且具有指纹的模板；停用模板保留，软删除模板排除。
- Marketplace 层：覆盖原生模板、通用一插件多组件、缺组件、指纹变化、Dify 子能力部分/全部导入和探测中状态。
- Frontend 层：覆盖状态文案、禁选逻辑、刷新后选择过滤以及既有市场兼容、探测、布局和表单回归。
- 权限与安全层：市场接口权限不变；新增查询不读取 `config_encrypted`，不返回组件凭据或内部 Worker 错误。
- 兼容层：版本或 ABI Schema 变化继续进入原有 `UPDATE_AVAILABLE` 确认流程；未兼容和探测中条目行为不变。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 缺陷复现 | `node --test tests/workflowTemplateCatalog.test.js` | 新增用例按预期失败：中文 `workflowNodes.imported` 不存在 |
| Backend 定向回归 | Maven 3.9.9 / Java 17 容器执行 `WorkflowServiceAccessTest`、`WorkflowNodeMarketplaceServiceTest` | 18/18 通过，BUILD SUCCESS |
| 当前基准点 Backend 完整回归 | `fa785c9` 临时 detached worktree 执行 `mvn -B -ntp test` | 553/553 通过，BUILD SUCCESS |
| 功能提交 Backend 完整回归 | `a1236ab` 临时 detached worktree 执行 `mvn -B -ntp test` | 552/552 通过，BUILD SUCCESS |
| 精确提交 Frontend 完整回归 | `npm test`；`node --test test/*.mjs` | 110/110、168/168 通过 |
| 精确提交双 Worker | `python3.12 -m unittest discover -s tests -v`；`npm test` | 14/14、4/4 通过 |
| 当前集成工作区双 Worker | 相同 Python 3.12 与 npm 命令 | 15/15、5/5 通过；包含并行未提交改动 |
| 统一重建 | `docker compose up --build -d` | 六镜像构建完成并重新创建服务，命令成功 |
| 运行态健康 | `docker compose ps`；容器内 `/api/open/health/ready` | 六服务全部 healthy；readiness 返回 `{"status":"UP"}` |
| 差异与提交检查 | `git diff --check`、精确暂存检查、独立 worktree 验证 | 本任务代码提交仅含 9 个相关文件；临时 worktree 已删除 |

### 测试过程问题与处理

- 主机未安装 Maven，首次 Backend 定向命令返回 `mvn: command not found`；未据此判定完成，改用项目 Dockerfile 固定的 Maven 3.9.9 / Java 17 镜像并完成定向、完整和精确提交回归。
- 新 Frontend 规则使一条旧契约仍要求 `:disabled="!item.compatible"`；将其加强为“不兼容或已导入均禁用”后全部通过，未删除或弱化断言。
- 首次 Backend 数据层测试使用了与插件节点类型不匹配的功能分类，触发现有分类校验；修正测试数据为合法分类后通过，业务实现未绕过校验。
- 外部 HTTPS readiness 路径因当前入口保护返回 401；依据 Compose 实际 Backend 健康检查路径，在容器内验证 `/api/open/health/ready` 返回 UP，Caddy 随后也进入 healthy。
- 实施期间并行任务先后提交了插件分类与预览分类变更，并继续修改 Worker/探测文件；本任务重新基于新 HEAD 精确暂存，并分别用 detached worktree 验证功能提交和最终基准点。未覆盖、清理或提交并行工作。

### 已知问题与限制

- “已导入”要求当前市场包探测完成后才能校验全部组件及指纹；探测缓存缺失时会先显示探测中，完成轮询后再显示已导入。
- 完整导入项不可重复勾选；如果需要恢复已删除组件，任一组件软删除后整包会恢复可导入。仅停用组件不会触发恢复入口，符合本次“删除组件”边界。
- 当前工作区仍有与本任务无关的 Worker、探测服务和 Frontend 文案测试改动；它们未包含在 `a1236ab` 或基准点 `fa785c9`，需由对应任务自行验证和提交。

### 下次测试建议

1. 增加登录态浏览器 E2E，覆盖导入多组件插件、关闭并重开市场、删除单个模板后重新导入恢复的完整交互。
2. 为市场目录增加批量状态查询的真实 MySQL 集成测试，观察模板数量增长后的查询耗时并按需增加只覆盖查询条件的索引。
3. 如未来希望“停用”也允许重新导入，应先区分停用与删除的产品语义，并调整卡片状态和恢复入口。

### 重测触发条件与回滚

- 修改市场模板软删除语义、外部指纹算法、插件组件聚合规则、市场响应模型或 Frontend 选择过滤时，必须重跑本节 Backend 定向测试、完整回归、Frontend 两套测试和 Compose 重建。
- 应用代码可撤销提交 `a1236ab84e780bccf29ec390fc9c37ba13cb7751` 后执行 `docker compose up --build -d`；本次无数据库迁移，无需数据回滚。

## 📋 Dify 市场插件探测性能优化测试结果（2026-08-11）

### Git 基准点

Commit: 9750a2c483ebc8cc6cc9b5ce8da0fab29d157be1
- 提交说明: Speed up Dify plugin probing
- 测试日期: 2026-08-11
- 分支: master
- 上一测试报告基准点: `09b6757792727088fec142590882c6265759c523`
- Backend 业务代码差异: 市场探测默认并发由 2 提升为 4，并增加队列、下载和 Worker 阶段耗时日志；市场接口、状态机、重试次数和兼容聚合规则保持不变。

### 变更范围

- Dify Worker 使用插件持久卷下的共享 pip 下载缓存，继续把每个插件安装到独立 `.deps` 目录；插件运行时依赖不共享，临时目录仍位于对应插件目录，不占用 64 MiB tmpfs。
- `.env` 的 `PIP_INDEX_URL` 与 `PIP_TRUSTED_HOST` 在构建期和 Worker 运行期均显式生效；未输出或写入镜像代理的具体部署值。
- Backend 市场探测默认并发从 2 提升为 4；Dify Worker 默认资源边界提升为 2 CPU、1 GiB，并兼容既有通用 `PLUGIN_WORKER_MEMORY_LIMIT` 与 `PLUGIN_WORKER_CPU_LIMIT` 覆盖项。
- Backend 记录排队、市场下载、Worker 和总耗时；Dify Worker 记录缓存命中、依赖安装、组件加载、组件数和总耗时，日志只使用任务 ID 或包指纹前缀，不包含插件内容、凭据或代理地址。
- 未修改数据库结构、前端、市场接口、导入行为、组件兼容规则、最大尝试次数或单组件隔离策略；未导入任何实测插件，也未删除或重置已有探测数据。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| 重复依赖复用持久下载缓存 | Dify Worker Python 3.12 单元测试；临时插件卷 | 安装含安全 requirements 的插件 | pip 使用持久 `PIP_CACHE_DIR` 且不带 `--no-cache-dir`，插件 `.deps` 仍隔离；通过 | 正常、性能、兼容 |
| 缓存和临时目录不突破安全边界 | Dify Worker 单元测试；卷内与卷外缓存路径 | 使用合法缓存路径、卷外逃逸路径、非法依赖源和 Dify SDK 依赖 | 合法缓存持久化、临时目录调用后删除；卷外路径和非法依赖拒绝；通过 | 边界、安全、异常 |
| 当前页最多四个包同时探测 | Backend Probe Service；默认配置与显式并发 1 | 构造默认线程池及阻塞探测队列 | 默认核心/最大线程均为 4；显式并发 1 时仍仅 1 条 PROBING；通过 | 并发、配置、回归 |
| 探测状态与兼容结果不变 | Backend/双 Worker 定向与完整回归 | 正常、混合兼容、依赖失败、市场拒绝、重试耗尽 | 原有 COMPLETE/PARTIAL/UNSUPPORTED/QUEUED/FAILED/REJECTED 语义保持；通过 | 正常、异常、兼容、回归 |
| 运行配置和镜像代理生效 | Compose 配置、统一重建和容器运行态 | 构建六个镜像并检查容器配置、健康与 readiness | Backend 并发 4；Dify Worker 2 CPU/1 GiB、缓存与镜像变量生效；六服务 healthy，readiness 为 UP；通过 | 构建、部署、配置 |
| 冷页探测总耗时下降 | 真实 Dify 市场第 7 页；20 个此前未缓存固定版本 | 管理员只读打开并每 2 秒轮询，不调用导入 | 100 秒全部收敛；修改前相同规模最近两批为 145 秒和 241 秒，分别缩短约 31% 和 59%；通过 | 性能、外部依赖、端到端 |

### 测试执行结果

- 正式自动化回归共 828 项，828 项通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：533/533 通过；统一镜像构建阶段再次执行 533/533 并完成打包。
- Frontend 两套完整回归：109/109、168/168 通过；本次未修改前端。
- Dify Worker 使用 Python 3.12：14/14 通过；n8n Worker：4/4 通过。
- Backend 定向回归：24/24 通过，覆盖 Probe Service 13、Worker Client 1、Marketplace Service 7、Marketplace Client 3。
- 缺陷复现阶段两项新增测试均稳定失败：pip 命令仍包含 `--no-cache-dir`；默认线程池实际为 2。实现后两项及完整回归全部通过。

### 关键模块测试

- Dify 依赖层：验证持久 pip 下载缓存、镜像代理变量、插件级 `.deps` 隔离、卷内临时目录、缓存路径逃逸拒绝、禁止 SDK 和外部依赖源、失败与超时清理。
- Dify ABI 层：覆盖 Tool、Model、Agent Strategy、Datasource、Trigger、Extension、未知 Dify SDK 子模块、包内相对导入、OAuth 生命周期、旧 ABI 缓存重建和调用输出。
- Backend 队列层：覆盖默认 4 并发、显式资源边界、幂等入队、立即执行槽位、固定版本直下、租约、依赖故障重试、重试耗尽和安全拒绝。
- 配置与部署层：验证 Compose 解析、Python 3.12 镜像、运行期 pip 镜像配置、2 CPU/1 GiB 资源限制、六服务健康与 HTTPS readiness。

### 真实性能验收

- 修改前运行态基线：Dify 默认并发 2；最近两个 20 项规模批次从入队到整页收敛约 145 秒和 241 秒；69 个包中共 197 个组件，插件依赖目录约 4.2 GiB，`requests`、`pydantic` 等依赖在约 60 个包中重复声明。
- 修改后选择市场第 7 页，20 个包此前均无数据库探测记录；17 秒时为 1 COMPLETE/4 PROBING/15 QUEUED，34 秒时 6 COMPLETE，51 秒时 12 COMPLETE，68 秒时 16 COMPLETE，100 秒全部进入终态。
- 运行态始终最多 4 个 PROBING；观察峰值约 195% CPU、333 MiB/1 GiB 内存，未触发 OOM、容器重启、tmpfs 占满或线程池拒绝。
- 17 个成功进入 Dify Worker 的包全部返回规范结果；缓存变热后，多数重复依赖安装阶段约 3–5 秒。共享 pip 缓存新增约 169 MiB。
- 最终状态为 17 COMPLETE、2 REJECTED、1 FAILED。两个 REJECTED 为官方市场下载返回无效包；FAILED 包连续三次触发 8 秒市场下载超时，稍后只读直连同一固定版本在 1.4 秒返回 HTTP 200，确认是瞬态外部市场波动，不是 Worker 缓存或并发失败。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| 缺陷复现 | Python 3.12 单用例；Maven 容器执行新增默认并发用例 | 2 项按预期失败，分别捕获禁用缓存和默认并发 2 |
| Backend 定向回归 | Maven 3.9.9 / Java 17 容器执行 Probe、Worker Client、Marketplace Service 和 Marketplace Client | 24/24 通过，BUILD SUCCESS |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn test -B -ntp` | 533/533 通过，BUILD SUCCESS |
| Frontend 完整回归 | `npm test`；`node --test test/*.mjs` | 109/109、168/168 通过 |
| Dify Worker | `python3.12 -m unittest discover -s tests -v` | 14/14 通过 |
| n8n Worker | `npm test` | 4/4 通过 |
| Compose 配置与重建 | `docker compose config --quiet`；`docker compose up --build -d` | 解析成功；Backend 构建内 533/533；六服务 healthy |
| 真实 Dify 冷页 | 登录后只读请求此前未缓存的第 7 页并轮询状态 | 20 项在 100 秒收敛；未导入、删除或重置数据 |
| 运行态健康与资源 | `docker compose ps`、HTTPS readiness、容器配置和 `docker stats` | readiness 为 UP；并发 4；Dify 2 CPU/1 GiB；峰值约 195%/333 MiB |
| 差异与清理 | `git diff --check`、路径限定暂存、Cookie trap、`__pycache__` 检查 | 本任务提交未夹带并发工作树变更；临时 Cookie 与 Python 缓存均已清理 |

### 测试过程问题与处理

- 首轮 Python 定向回归在 macOS 上因 `/var` 与 `/private/var` 指向同一真实目录而出现 1 个路径字符串断言失败；改为比较 `resolve()` 后的真实路径，安全边界断言未弱化，14/14 通过。
- 首次真实验收脚本把统一 API 成功码 200 误按 0 判断并在市场请求前退出；Cookie trap 正常清理且未产生探测任务。修正响应契约后重新执行完整冷页验收。
- 冷页中的 1 个包因 Dify 市场连续三次 8 秒超时进入 FAILED；随后同一下载地址 1.4 秒成功，已按外部瞬态波动记录，未通过删除记录、重置状态或修改重试上限掩盖结果。
- 测试报告提交前出现另一项任务的未提交 Backend/Frontend 变更；本任务使用路径限定提交，不暂存、不覆盖，也不把这些并发变更计入上述测试基准。

### 已知问题与限制

- pip 缓存减少重复下载，但每个插件仍保留独立运行依赖；真实冷页后共享缓存约 169 MiB，插件卷从约 4.2 GiB 增至约 5.7 GiB，依赖较重的插件仍会占用明显磁盘空间。
- 当前共享 pip 缓存没有自动容量上限或独立清理周期；部署方可通过 `PLUGIN_PIP_CACHE_DIR` 放置在受监控的插件持久卷中，删除缓存属于数据清理操作，需另行确认。
- 四并发默认需要比原配置更多资源；Dify Worker 默认提高到 2 CPU/1 GiB，资源较小的环境可用 `WORKFLOW_MARKETPLACE_PROBE_CONCURRENCY`、`DIFY_PLUGIN_WORKER_CPU_LIMIT` 和 `DIFY_PLUGIN_WORKER_MEMORY_LIMIT` 下调。
- 外部市场下载仍使用 8 秒请求超时和最多三次尝试；短时网络波动可能让单包进入 FAILED，本次未扩大到自动恢复耗尽任务或修改下载超时。
- 性能结果来自当前网络、镜像代理和官方市场包集合，不能保证所有环境固定为 100 秒；确定性验收是持久缓存生效、并发 4、资源不越界和状态机保持。

### 下次测试建议

1. 为共享 pip 缓存增加可配置容量上限、空闲期 LRU 清理和磁盘水位监控，避免长期运行后无界增长。
2. 评估对 `workflow.marketplaceUnavailable` 耗尽任务增加带冷却的页面访问恢复机制，避免短时市场抖动永久保留 FAILED。
3. 在稳定隔离网络中连续测试多个完全未缓存页面，统计 P50/P95、下载缓存命中率、依赖安装耗时和峰值磁盘增长。

### 重测触发条件与回滚

- 修改 pip 缓存目录/清理、依赖过滤、插件隔离、Dify 镜像代理、探测并发、Worker 资源、队列领取、租约、阶段日志、市场下载超时或失败恢复时，必须重跑 Backend/Dify Worker 定向测试、完整回归、Compose 重建和真实冷页验收。
- 应用代码可撤销提交 `9750a2c483ebc8cc6cc9b5ce8da0fab29d157be1`，默认并发与资源恢复为原值；回滚后执行 `docker compose up --build -d`。共享 pip 缓存可以保留且不会被旧代码加载；如需释放空间，必须另行确认后再删除缓存目录。

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

## 📋 Tongyi 与 Volcengine 市场插件兼容性修复测试结果（2026-08-11）

### Git 基准点

Commit: 28cb1901a0feafc1bd39fd8f0ea8ff1514bb0fb2
- 提交说明: Complete plugin adapter compatibility；包含包限制、ABI 兼容和旧探测结果重试修复。
- 测试日期: 2026-08-11
- 分支: master
- 上一测试报告基准点: `484c6bf`

### 变更范围

- Dify 市场包文件数上限从 128 提升到 512，仍保持压缩包 5 MiB、解压后 10 MiB、路径穿越、软链接和依赖来源限制。
- Dify 自研 ABI 补齐 Tongyi 与 Volcengine 使用的 Pydantic 模型实体、枚举、工具消息和兼容的构造参数；Worker ABI 版本升至 5，n8n Worker ABI 版本升至 4。
- 宿主按 Worker ABI 重新探测旧缓存；对历史 `PACKAGE_CONTENT_LIMIT` 和 `workflow.pluginWorkerRejected` 拒绝结果最多自动重新排队一次，避免旧结论永久阻塞市场导入。
- 文件数超限与解压体积超限使用独立公开原因码，便于用户区分包结构问题和大小问题。
- 未改变真实模型调用、凭据存储、网络策略或第三方 SDK 安装策略；本次未新增数据库迁移。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| Tongyi 组件可被识别为完全兼容 | 官方 Tongyi 0.2.9 包；Python 3.12；默认依赖安装超时 | 下载 748084 字节、143 文件的官方包并探测 5 个模型组件 | `tongyi.llm`、`rerank`、`text-embedding`、`tts`、`speech2text` 均 `SUPPORTED`；通过 | 正常、兼容、真实包 |
| Volcengine 组件可被识别为完全兼容 | 官方 Volcengine MaaS 0.0.53 包；Python 3.12 | 探测 295468 字节、33 文件的官方包 | LLM、文本向量、语音转文字 3 个组件均 `SUPPORTED`；通过 | 正常、兼容、真实包 |
| 包限制仍安全且边界明确 | Dify Worker PackageStore 单元测试和 Backend 解析测试 | 512/513 文件、5 MiB 压缩包、10 MiB 解压体积、路径穿越和软链接 | 临界值通过，超限返回独立原因，恶意路径被拒绝；通过 | 边界、异常、安全 |
| 旧缓存不会永久保留部分兼容 | Dify Worker ABI 缓存测试、Backend Probe Service 定向测试 | 旧 ABI 缓存、历史内容限制拒绝记录和达到最大尝试次数的记录 | ABI 变化重新探测；历史拒绝只重试一次，已达上限不重复排队；通过 | 回归、状态冲突、兼容 |
| 现有服务和插件路径无回归 | 两个 Worker、Backend/Frontend 完整测试、Compose 重建 | 完整测试套件及 manager 启停 DIFY/N8N | 所有自动化测试通过，核心服务健康，两个 Worker 可启停并健康；通过 | 回归、部署、权限 |

### 测试执行结果

- Dify Worker 完整回归：19/19 通过，通过率 100%，失败 0，错误 0，跳过 0。
- n8n Worker 完整回归：6/6 通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 完整回归：570/570 通过，通过率 100%，失败 0，错误 0，跳过 0。
- Backend 兼容性定向回归：24/24 通过，覆盖包解析、探测重试和 Worker ABI 客户端。
- Frontend 完整回归：114/114 通过，通过率 100%，失败 0，错误 0，跳过 0。
- 官方包只读探测：Tongyi 5/5、Volcengine 3/3 均 `SUPPORTED`；未使用 API Key，也未执行付费模型请求。
- Compose 构建与运行态：`docker compose up --build -d` 成功；Backend、Frontend、Python Worker、Adapter Manager、Caddy 均 healthy；DIFY/N8N 通过 manager 启动后健康，再按 on-demand 设计停止。

### 实际执行记录

| 范围 | 执行命令或方式 | 结果 |
| --- | --- | --- |
| Dify Worker 完整回归 | `PYTHONPATH=. python3.12 -m unittest discover -s tests -v`（在 `dify-plugin-worker/`） | 19 通过，0 失败，0 错误，0 跳过 |
| n8n Worker 完整回归 | `npm test`（在 `n8n-plugin-worker/`） | 6 通过，0 失败，0 错误，0 跳过 |
| Backend 完整回归 | Maven 3.9.9 / Java 17 容器执行 `mvn test -B -ntp` | 570 通过，0 失败，0 错误，0 跳过 |
| Backend 兼容性定向回归 | Maven 容器执行 `-Dtest=WorkflowMarketplacePackageParserTest,WorkflowPluginProbeServiceTest,WorkflowPluginWorkerClientTest test` | 24 通过，0 失败，0 错误，0 跳过 |
| Frontend 完整回归 | `npm test -- --runInBand`（在 `frontend/`） | 114 通过，0 失败，0 错误，0 跳过 |
| 官方 Tongyi 包 | 公共 Marketplace 下载后在临时目录用 Python 3.12 PackageStore 探测 | 默认 240 秒依赖安装上限下 5/5 `SUPPORTED` |
| 官方 Volcengine 包 | 公共 Marketplace 下载后在临时目录用 Python 3.12 PackageStore 探测 | 3/3 `SUPPORTED` |
| Compose 重建 | `docker compose up --build -d` | 镜像构建和启动成功 |
| 运行态启停 | 通过 Backend 容器调用 Adapter Manager DIFY/N8N 固定来源接口 | 两个 Worker 均可 `RUNNING`，健康检查通过，随后 `STOPPED` |
| 服务就绪 | Backend 容器和 HTTPS 入口请求 `/api/open/health/ready` | 两次均返回 `{"status":"UP"}` |

### 测试过程问题与处理

- 首次官方 Tongyi 探测人为将依赖安装超时设为 60 秒，因首次下载完整依赖锁定集返回 `DEPENDENCY_INSTALL_TIMEOUT`；恢复项目默认上限后重新探测，5 个组件全部 `SUPPORTED`。这属于依赖下载环境限制，不是组件导入失败。
- Marketplace 下载接口对默认 Python User-Agent 返回 403，改用公开浏览器 User-Agent 后下载成功；未使用认证凭据。
- Compose 默认启用 on-demand Worker profile，因此构建后两个 Worker 初始为停止状态；通过 Adapter Manager 启动并完成健康检查后再停止，符合设计。

### 覆盖范围与已知限制

- 正常、边界、异常、安全和兼容性均有可执行测试；路径穿越、软链接、非法依赖来源、缓存指纹和 ABI 版本分支均覆盖。
- 兼容性验证包含官方包的声明解析、真实模块导入和组件构造；未执行需要供应商 API Key 的真实模型计费调用。
- 依赖首次安装受网络和镜像速度影响，生产环境应使用持久 PIP 缓存并保留足够的安装超时；缓存命中后探测不重复安装。
- 包仍受 5 MiB 压缩、10 MiB 解压和 512 文件硬限制；超过限制的插件需要拆包或缩减资源。

### 重测触发条件与回滚

- 修改 Dify/n8n ABI 实体、Worker ABI 版本、包大小限制、依赖安全校验、探测缓存或拒绝重试逻辑时，必须重跑两个 Worker、Backend/Frontend 完整回归、Compose 重建和官方代表包探测。
- 代码可回退到 `484c6bf` 之前的兼容性基线；回退后旧 Worker 缓存应按 ABI 版本重新探测，不应手工修改缓存目录。

### 下次测试建议

1. 在 CI 中预热官方插件依赖缓存，增加 Tongyi/Volcengine 每个版本的定期探测矩阵。
2. 在配置供应商测试凭据的隔离环境补充一次非付费或沙箱模型调用，验证调用 ABI 与导入 ABI 的一致性。
3. 对接 Marketplace API 的集成测试使用固定下载快照，避免网络 403、版本漂移和上游包变更影响回归结果。

## 📋 Worker 短暂不可用后的插件探测恢复测试结果（2026-08-12）

### Git 基准点

Commit: 474cd6a
- 提交说明: Retry cached plugin probes after worker recovery
- 测试日期: 2026-08-12
- 分支: master
- 上一测试报告基准点: `28cb190`

### 变更范围

- 当 DIFY/N8N Worker 因适配器停止、连接失败等瞬时原因耗尽重试后，下一次用户访问市场会把历史 `FAILED` 记录重新置为 `QUEUED`，并清除旧错误、结果和尝试次数。
- 只处理 `workflow.pluginWorkerUnavailable` 与 `workflow.adapterDisabled`；安全拒绝、包格式错误、ABI 不兼容等永久性失败不自动放行。
- 重试仍由既有最大尝试次数、租约和异步探测队列控制，不执行同步下载或绕过适配器开关。

### 验收标准—测试用例映射

| 验收标准 | 测试层级与前置条件 | 输入或操作 | 预期与实际结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| Worker 恢复后旧失败可重新探测 | Backend `WorkflowPluginProbeServiceTest` 参数化测试 | `workflow.pluginWorkerUnavailable`、`workflow.adapterDisabled` 且已达到最大尝试次数 | 页面访问将记录置为 `QUEUED/PROBING`，attempt 重置为 0；19/19 定向测试通过 | 异常恢复、状态冲突、兼容 |
| 安全拒绝不被错误重试 | 既有探测拒绝回归测试 | 路径穿越、依赖来源、非法包内容 | 保持 `REJECTED/UNSUPPORTED`；通过 | 安全、回归 |
| 正常探测和旧 ABI/依赖恢复不回归 | Backend 完整回归、两个 Worker 回归 | 完整市场探测、ABI 缓存和依赖重试测试 | Backend 572/572，Dify 19/19，n8n 6/6；通过 | 正常、兼容、回归 |
| 服务重建后核心运行态正常 | 干净提交快照 Compose 重建 | `docker compose up --build -d`、readiness 检查 | 镜像构建成功，Backend/Frontend/Python Worker/Adapter Manager/Caddy healthy，readiness `UP`；通过 | 部署、运行态 |

### 测试执行结果

- Backend 定向探测恢复：19/19 通过。
- Backend 完整回归（干净提交快照）：572/572 通过，失败 0，错误 0，跳过 0。
- Dify Worker：19/19 通过；n8n Worker：6/6 通过。
- Compose 干净快照重建成功，核心服务健康；DIFY/N8N 默认停止，符合 on-demand profile 设计。
- 运行数据库中确认 Tongyi 已恢复为 `COMPLETE/SUPPORTED`；Volcengine 旧失败来自 Worker 停止期间的瞬时不可用，下一次访问会按本次逻辑重新排队。

### 测试过程问题与处理

- 主工作区同时存在未提交的插件准入功能改动，其新增准入表测试夹具尚未合入，因此在主工作区直接跑完整 Backend 测试出现 2 个 H2 表缺失错误和 1 个旧 Mock 预期失败；该结果不属于本提交，已在干净提交快照上重新执行并通过 572/572。
- 临时 Compose 工作树首次启动因缺少 `.env` 挂载点失败；补充临时 symlink 后重建成功，验证完成后已删除工作树和 symlink。
- 未执行任何供应商付费模型调用；本修复只改变探测失败后的队列恢复。

### 重测触发条件与回滚

- 修改探测状态机、错误公开原因、适配器生命周期、Worker 客户端重试或最大尝试次数时，必须重跑探测定向/完整测试、两个 Worker 回归、Compose 重建和运行态健康检查。
- 可回退提交 `474cd6a`；回退不会修改数据库结构，已有失败记录仍保留原状态。

## 本次变更：凭据类型互斥（2026-09-11）

- 类型改为 `PASSWORD`（账号密码）与 `KEY`（秘钥），后端拒绝混合材料。
- 测试：`mvn test -B` 未执行，环境缺少 Maven（command not found）。
- 重建：`docker compose up --build -d` 未完成，缺少 `APP_IMAGE_REVISION` 环境变量。
- 已知限制：需在具备 Maven 与 Compose 环境变量的环境补跑完整测试。
- Git 基准点：ea2e1d5。
