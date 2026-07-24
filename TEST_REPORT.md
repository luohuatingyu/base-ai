# 最近分支覆盖测试报告

## 📋 Git 基准点

- Commit: 68c8031
- 提交说明: Refine capability route synchronization
- 测试日期: 2026-07-24
- 分支: master

## 🎯 本次变更范围

- 路由同步页面取消模型供应商选择，只测试当前功能路由已配置供应商下的启用模型。
- 空供应商池和空候选模型不再解释为全局供应商，直接以空路由同步成功。
- 健康检查结果仅作为同步提示；测试失败但未删除的模型仍按数据库配置进入内存路由。
- 删除当前模型供应时同步清理路由数据库关联和内存候选，但保留供应商与模型主数据。
- 模型、供应商和路由配置修改后继续使用旧内存快照，直到用户再次执行路由同步。
- 保留路由同步请求中的旧 `providerIds` 字段兼容性，但后端不再允许它缩小路由测试范围。

## 📋 本次变更测试结果（2026-07-24）

**变更范围**：能力路由同步范围、空配置语义、失败模型内存同步、模型供应删除和前端同步交互。

**测试执行结果**：
- 总测试用例：105 个
- 通过：105 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- Domain 层：未修改，通过相关 Service 回归间接覆盖
- Service 层：30/30，通过
- Controller 层：12/12，通过
- 后端基础设施层：3/3，通过
- Python Worker：12/12，通过
- 前端回归：48/48，通过
- 前端生产构建：通过
- Compose 服务健康检查：Backend、Frontend、Python Worker 全部 healthy

**缺陷复现与修复确认**：
- 修复前后端完整测试共 43 项，其中新增用例出现 2 个失败和 1 个错误，稳定复现供应商筛选、空配置测试全局模型和失败模型未进入内存的问题。
- 修复前前端定向测试共 4 项，其中 1 项失败，稳定复现同步窗口仍要求选择供应商的问题。
- 修复后后端 45 项、前端 48 项和 Python Worker 12 项测试全部通过。

**Git 基准点**：68c8031

## 📊 测试执行结果

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 后端缺陷复现 | `docker build --target build -t base-ai-backend-route-sync-test ./backend` | 修复前 43 项中 2 个失败、1 个错误，稳定复现缺陷 |
| 前端缺陷复现 | `node --test frontend/test/model-route-health.test.mjs` | 修复前 4 项中 1 项失败，稳定复现供应商选择问题 |
| 后端完整测试 | `docker build --target build -t base-ai-backend-route-sync-test ./backend` | 45 通过，0 失败，0 错误，0 跳过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs` | 48 通过，0 失败，0 跳过 |
| Python Worker 回归 | `docker run --rm -v "$PWD/python-worker:/workspace" -w /workspace base-ai-python-worker:latest python -m pytest -q` | 12 通过，0 失败，0 跳过 |
| Compose 配置校验 | `docker compose config --quiet` | 通过 |
| 服务重建与启动 | `docker compose up --build -d` | 通过；三个服务均重新构建并启动 |
| 前端生产构建 | `docker compose up --build -d`（Frontend 镜像内执行 `npm run build`） | 通过 |
| 服务健康检查 | `docker compose ps --format json`、Backend/Frontend 健康接口 | Backend、Frontend、Python Worker 全部 healthy，接口返回 `UP` |
| 差异格式检查 | `git diff --check` | 通过 |

## 🔄 覆盖范围与结果

- 后端：覆盖请求供应商筛选被忽略、路由全部已配置供应商测试、空配置成功、失败模型保留、同步前后快照隔离、删除供应数据库与内存一致性、主数据保留及旧路由回归。
- Worker：完整测试通过，本次未修改 Worker 业务代码。
- 前端：覆盖同步窗口取消供应商选择、请求仅提交路由 ID、状态颜色、删除按钮规则，以及既有 API Key、Trace、任务、布局、导航和分页回归。
- 运行环境：Compose 已基于当前代码重新构建，Backend、Frontend、Python Worker 均处于 healthy 状态。

## ⚠️ 已知问题与限制

- 健康检查失败的模型在用户未删除时仍会进入真实调用候选池，这是本次确认的业务行为；实际调用可能继续失败并触发现有候选回退逻辑。
- 空路由同步后内存候选为空，对应功能调用会提示没有可用模型；配置模型供应后必须再次同步才会生效。
- 路由同步请求仍兼容接收旧 `providerIds` 字段，但该字段在路由级同步中被忽略。
- 后端编译仍提示 `TaskTraceService` 存在既有未检查泛型操作，未影响测试结果。
- 前端构建仍有既有 runtime-config、第三方 PURE 注释和包体积警告，不影响构建成功。
- 尚未增加真实浏览器端到端测试，本次前端交互通过源码级 Node 测试和生产构建验证。

## 📝 下次测试建议

1. 后续修改能力路由同步、候选回退或模型健康状态逻辑时，基于本报告基准点重新执行完整测试。
2. 补充浏览器端到端测试，覆盖空路由同步、失败模型保留、删除模型供应和修改后再次同步的完整操作链路。
3. 补充集成测试验证多个失败候选参与真实调用时的回退顺序和错误展示。
4. 评估前端按路由拆分主包，并在合适时处理后端未检查泛型警告。
