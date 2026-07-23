# 最近分支覆盖测试报告

## 📋 Git 基准点

- Commit: 94cdf3144f017e8bf40ba3214a7d50b7edd4c3f9
- 提交说明: Add thinking level model routing
- 测试日期: 2026-07-23
- 分支: master

## 🎯 本次变更范围

- 模型支持六档标准思考等级到供应商实际值的映射；能力路由可按供应商池、能力等级和思考等级筛选模型。
- Python Worker 支持按供应商字段下发思考值；YAML 默认模型池支持同一映射结构且保持旧标量格式兼容。

## 📊 测试执行结果

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 服务重建与启动 | `docker compose up --build -d` | 通过；Backend、Frontend、Python Worker 均 healthy |
| Compose 配置校验 | `docker compose config --quiet` | 通过 |
| 后端完整测试 | `docker compose up --build -d`（Backend 镜像内执行 `mvn -B -ntp package`） | 19 通过，0 失败，0 错误，0 跳过 |
| Python Worker 完整测试 | Docker 镜像中执行 `pytest -q -p no:cacheprovider /tests` | 9 通过，0 失败 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs` | 30 通过，0 失败，0 跳过 |
| 前端生产构建 | `docker compose up --build -d`（Frontend 镜像内执行 `npm run build`） | 通过 |
| 差异格式检查 | `git diff --check` | 通过 |

## 🔄 覆盖范围与结果

- 后端：供应商池路由、能力/思考等级过滤，以及 Worker 请求下发兼容性测试通过。
- Worker：标准 JSON、供应商思考字段、空候选不回退和异常响应解析测试通过。
- 前端：既有 API Key、Trace、任务、布局、导航和分页回归测试全部通过；生产构建通过。
- 运行环境：Compose 服务已重新构建并启动，三个服务健康检查均通过。

## ⚠️ 已知问题与限制

- 后端编译提示 `TaskTraceService` 存在未检查泛型操作；测试通过，未影响本次结果。
- 前端构建提示运行时配置脚本不会被 Vite 打包、第三方库 PURE 注释位置不可解析，以及主包体积超过 500 kB；均为非阻塞警告，构建成功。
- 路由升级保留旧候选模型 ID 模式；新供应商池模式若没有能力或思考等级匹配模型，会明确拒绝回退。

## 📝 下次测试建议

1. 后续修改后端业务逻辑、模型池 YAML 或 Worker 路由逻辑时，基于本报告基准点重新执行完整测试。
2. 补充浏览器端到端测试，覆盖供应商池筛选和思考等级映射编辑。
3. 评估前端按路由拆分主包，并在合适时处理后端的未检查泛型警告。
