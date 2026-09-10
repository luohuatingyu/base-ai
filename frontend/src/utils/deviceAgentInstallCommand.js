// Mac Agent 一键安装命令构建：配对码对话框与接入向导共用，保证两处生成的命令一致

// 平台内部根证书的固定下载路径：Caddy 内部 CA 部署可访问，使用正式证书的部署此文件不存在
const ROOT_CA_PATH = '/agent-dist/root-ca.crt'
// 探测超时：控制台与平台同源，3 秒足够；超时一律按非自签名处理，绝不阻塞界面
const DETECT_TIMEOUT_MS = 3000

// 单引号包裹并转义，防止账号标识中的空格或特殊字符破坏安装命令
export const shellQuote = (value) => `'${String(value).replace(/'/g, `'\\''`)}'`

/**
 * 判定控制台来源是否为本机或私网地址。
 *
 * 仅用于决定是否展示证书排查提示，不作为自动开启自签名开关的依据：
 * 私网部署也可能使用受信任的正式证书，据此自动开关会生成错误的安装命令。
 *
 * @param {string} origin 控制台源站地址（含协议）
 * @returns {boolean} 是否为本机或私网来源
 */
export function isPrivateOrigin(origin) {
  let hostname
  try {
    hostname = new URL(origin).hostname
  } catch {
    return false
  }
  // URL 解析会保留 IPv6 字面量的方括号，去掉后再比对
  const host = hostname.replace(/^\[|\]$/g, '').toLowerCase()
  if (host === 'localhost' || host === '::1') return true
  if (host.endsWith('.local') || host.endsWith('.internal') || host.endsWith('.localhost')) return true
  // 无点的裸主机名只可能来自内网 DNS 或 hosts 映射
  if (!host.includes('.')) return true
  const ipv4 = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(host)
  if (!ipv4) return false
  const first = Number(ipv4[1])
  const second = Number(ipv4[2])
  return first === 10 || first === 127
    || (first === 192 && second === 168)
    || (first === 172 && second >= 16 && second <= 31)
}

/**
 * 探测平台是否使用内部自签名证书。
 *
 * 判定依据是平台是否暴露内部根证书：Caddy 内部 CA 部署下 `/agent-dist/root-ca.crt`
 * 返回 200，使用正式证书的部署该文件不存在、返回 404。只有确认可下载才返回 true——
 * 否则安装命令中的 `--ca-file` 会指向 404，反而让安装器取不到证书而失败。
 * 任何异常（网络错误、超时、非法来源）一律按 false 处理，绝不阻塞界面。
 *
 * @param {string} origin 控制台源站地址（含协议）
 * @param {Object} [options] 可选项
 * @param {Function} [options.fetchImpl] 请求实现，测试注入
 * @param {number} [options.timeoutMs] 探测超时毫秒数
 * @returns {Promise<boolean>} 平台是否使用可下载根证书的内部自签名证书
 */
export async function detectSelfSignedDeployment(origin, { fetchImpl, timeoutMs = DETECT_TIMEOUT_MS } = {}) {
  let rootCaUrl
  try {
    const url = new URL(origin)
    // http 部署不存在证书校验问题，无需探测
    if (url.protocol !== 'https:') return false
    // 基础地址可能挂载在 /ai 等子路径下，根证书必须沿用该前缀而不是退回源站根路径
    const basePath = url.pathname.replace(/\/+$/, '')
    rootCaUrl = new URL(`${basePath}${ROOT_CA_PATH}`, url.origin).toString()
  } catch {
    return false
  }
  // 全局 fetch 必须包一层调用，直接取引用在浏览器中会因脱离 window 抛 Illegal invocation
  const request = fetchImpl || (typeof fetch === 'function' ? (...args) => fetch(...args) : null)
  if (!request) return false
  const controller = typeof AbortController === 'function' ? new AbortController() : null
  const timer = controller ? setTimeout(() => controller.abort(), timeoutMs) : null
  try {
    // HEAD 即可确认存在性，无需下载证书内容
    const response = await request(rootCaUrl, {
      method: 'HEAD',
      cache: 'no-store',
      signal: controller ? controller.signal : undefined
    })
    return Boolean(response && response.ok)
  } catch {
    return false
  } finally {
    if (timer) clearTimeout(timer)
  }
}

/**
 * 拼装一键安装命令。
 *
 * 内网自签名环境（selfSigned）同时追加两类参数：
 * - curl -k 与 --insecure：安装期下载脚本与代码包时跳过证书校验（此时根证书尚未落地）；
 * - --ca-file <origin>/agent-dist/root-ca.crt：安装器下载平台根证书并写入 Agent 运行环境，
 *   常驻进程始终做完整 TLS 校验，绝不长期跳过证书。
 *
 * 命令一律携带 --force-pair：目标 Mac 上可能残留一份服务端已不再认识的旧身份
 * （注册记录被删除或撤销后旧密钥即失效），不加该参数安装器会走「复用现有身份」分支，
 * 新配对码永远用不上，Agent 装完即持续认证失败。既然是拿着新配对码执行安装，
 * 重新配对本就是意图，无条件附加不会带来意外。
 *
 * 地址一律取 backendUrl（该 Agent 绑定的回连地址），未绑定时才回退控制台 origin：
 * 命令里的三处地址——脚本与代码包下载、--backend-url、根证书下载——都必须是目标 Mac 连得上的地址。
 * 控制台部署在 Linux 时管理员往往从内网入口访问，直接套用 origin 会装出一台永远连不上后端的 Agent。
 *
 * @param {Object} options 构建参数
 * @param {string} options.origin 控制台源站地址（含协议）
 * @param {string} [options.backendUrl] 该 Agent 绑定的回连地址，缺省回退 origin
 * @param {string} options.pairingCode 配对码明文
 * @param {boolean} [options.selfSigned] 内网自签名环境
 * @param {string} [options.npmRegistry] 安装 Appium 使用的 npm 镜像
 * @returns {string} 完整安装命令；配对码缺失时返回空串
 */
export function buildInstallCommand({ origin, backendUrl = '', pairingCode, selfSigned = false,
                                      npmRegistry = '' }) {
  if (!pairingCode) return ''
  // 末尾斜杠会拼出 //agent-dist 这类双斜杠路径，与后端归一化规则保持一致先去掉
  const server = (backendUrl && backendUrl.trim() ? backendUrl.trim() : origin).replace(/\/+$/, '')
  const curlInsecure = selfSigned ? ' -k' : ''
  const registry = npmRegistry && npmRegistry.trim() ? ` --npm-registry ${shellQuote(npmRegistry.trim())}` : ''
  const selfSignedArgs = selfSigned
    ? ` --ca-file ${shellQuote(`${server}/agent-dist/root-ca.crt`)} --insecure`
    : ''
  const script = '/tmp/base-ai-device-agent.sh'
  return `curl -fsSL${curlInsecure} ${shellQuote(`${server}/agent-dist/bootstrap.sh`)} -o ${script} && ` +
    `sh ${script} --backend-url ${shellQuote(server)} --pairing-code ${shellQuote(pairingCode)}` +
    `${registry} --force-pair${selfSignedArgs} && rm -f ${script}`
}

/**
 * 拼装本机改址命令：后端地址已失效时，在目标 Mac 上粘贴执行即可改道。
 *
 * 地址一旦失效，后端就没有任何通道能通知到 Agent——它只会去连本地记着的旧地址，
 * 自动下发的改址命令永远送不到。这条命令是那种情况下唯一的修复路径，
 * 也是自动下发填错地址后的自救手段。
 *
 * 脚本从**新地址**下载：旧地址此刻多半已经连不上，而新地址必然可达，否则改也没有意义。
 * 执行用安装时落地的私有 Python 运行时，不依赖系统 Python 版本。
 *
 * 与重装的区别是只做「探活 → 改写 → 重启」，不重下代码包、不重装 Appium、不轮换密钥。
 *
 * @param {Object} options 构建参数
 * @param {string} options.backendUrl 新的回连地址
 * @param {boolean} [options.selfSigned] 内网自签名环境
 * @returns {string} 可直接粘贴执行的改址命令；地址缺失时返回空串
 */
export function buildSetServerCommand({ backendUrl, selfSigned = false }) {
  if (!backendUrl || !backendUrl.trim()) return ''
  const server = backendUrl.trim().replace(/\/+$/, '')
  const curlInsecure = selfSigned ? ' -k' : ''
  const python = '~/Library/Application\\ Support/BaseAI/DeviceAgent/venv/bin/python'
  const caPath = '$HOME/.base-ai/device-agent/root-ca.crt'
  const caCommand = selfSigned
    ? `mkdir -p "$HOME/.base-ai/device-agent" && curl -fsSL${curlInsecure} ` +
      `${shellQuote(`${server}/agent-dist/root-ca.crt`)} -o "${caPath}" && `
    : ''
  const caArg = selfSigned ? ` --ca-file "${caPath}"` : ''
  return `${caCommand}${python} -m device_agent.main set-server ` +
    `--backend-url ${shellQuote(server)}${caArg} && ` +
    `launchctl kickstart -k "gui/$(id -u)/com.baseai.device-agent"`
}

/**
 * 拼装本机排查命令：Agent 起不来时，用户在目标 Mac 上粘贴执行即可看到真实报错。
 *
 * 凭据失效等场景下 Agent 没有任何可用上行通道，控制台看不到本机侧的错误，
 * 这条命令直接给出 launchd 状态与日志尾部，省去用户自己找日志路径。
 *
 * @returns {string} 可直接粘贴执行的排查命令
 */
export function buildDiagnoseCommand() {
  const logDir = '~/Library/Application\\ Support/BaseAI/DeviceAgent/logs'
  return `launchctl list | grep com.baseai.device-agent; ` +
    `tail -n 50 ${logDir}/agent.err.log ${logDir}/agent.log`
}
