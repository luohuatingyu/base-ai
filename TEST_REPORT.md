# 最近分支覆盖测试报告

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
