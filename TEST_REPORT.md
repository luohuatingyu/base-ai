# 最近分支覆盖测试报告

## 📋 Git 基准点

- Commit: 22538ca
- 提交说明: Deduplicate batch route health checks
- 测试日期: 2026-07-24
- 分支: master

## 🎯 本次变更范围

- 新增能力路由批量同步接口，一次接收多个路由编号并按路由返回独立结果。
- 批量同步按“模型 ID、是否启用思考、思考等级”组合复用健康检查结果。
- 相同模型在未启用思考或选择相同思考等级时只测试一次；未启用、不同等级之间分别测试。
- 失败的健康检查结果同样复用到所有命中相同组合的能力路由。
- 前端同步弹窗改为一次批量请求，并将结果按路由编号分发到对应功能 Tab。
- 原单路由同步接口、权限、供应商自动清理和内存路由刷新行为保持兼容。

## 📋 本次变更测试结果（2026-07-24）

**变更范围**：能力路由批量同步、跨路由模型测试去重、思考配置区分、失败结果复用和前端 Tab 结果分发。

**测试执行结果**：
- 总测试用例：118 个
- 通过：118 个（100%）
- 失败：0 个
- 错误：0 个
- 跳过：0 个

**关键模块测试**：
- Domain 层：未修改，通过 Service 回归间接覆盖
- 后端完整测试：54/54，通过
- LLM Service 层：32/32，通过
- Controller 层：13/13，通过
- Python Worker：12/12，通过
- 前端回归：52/52，通过
- 前端生产构建：通过
- Compose 服务健康检查：Backend、Frontend、Python Worker 全部 healthy

**缺陷复现与修复确认**：
- 实现前新增前端批量请求断言，定向测试 7 项中 1 项按预期失败，确认页面仍逐路由重复请求。
- 本机未安装 `mvn`，后端实现前失败测试未能在宿主机执行；实现后使用 Maven 3.9.9 Docker 环境完成定向和完整验证。
- 修复后后端定向测试 36 项、后端完整测试 54 项、前端定向测试 8 项、前端完整回归 52 项和 Python Worker 12 项全部通过。

**Git 基准点**：22538ca

## 📊 测试执行结果

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 前端缺陷复现 | `node --test frontend/test/model-route-health.test.mjs` | 实现前 7 项中 1 项失败，确认仍逐路由发送同步请求 |
| 后端定向测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn -B -Dtest=LlmManagementServiceTest,LlmManagementControllerTest test` | 36 通过，0 失败，0 错误，0 跳过 |
| 后端完整测试 | `docker run --rm -v "$PWD/backend:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 54 通过，0 失败，0 错误，0 跳过 |
| 前端定向测试 | `node --test frontend/test/model-route-health.test.mjs` | 8 通过，0 失败，0 跳过 |
| 前端完整回归 | `node --test frontend/test/*.test.mjs` | 52 通过，0 失败，0 跳过 |
| Python Worker 回归 | `docker run --rm -v "$PWD/python-worker:/workspace" -w /workspace python:3.12-slim sh -c "pip install --no-cache-dir -q -r requirements.txt pytest && python -m pytest -q"` | Python 3.12；12 通过，0 失败，0 跳过 |
| 服务重建与启动 | `docker compose up --build -d` | 通过；三个服务均重新构建并启动 |
| 前端生产构建 | `docker compose up --build -d`（Frontend 镜像内执行 `npm run build`） | 通过 |
| 服务健康检查 | `docker compose ps` | Backend、Frontend、Python Worker 全部 healthy |
| 差异格式检查 | `git diff --check` | 通过 |

## 🔄 覆盖范围与结果

- 后端正常场景：相同模型及相同思考配置只执行一次测试，结果同时返回给多个能力路由。
- 后端思考场景：未启用思考、相同思考等级和不同思考等级使用不同去重组合，按预期分别复用或重新测试。
- 后端异常场景：健康检查失败时仍缓存失败结果，同一组合不会重复请求外部模型供应。
- 后端权限与兼容：批量接口继续要求 `model:route:update`；原单路由接口、供应商清理和内存快照行为保持兼容。
- 前端：覆盖单次批量请求、按路由编号分发 Tab 结果、行内入口兼容、状态颜色和删除按钮，以及既有 API Key、Trace、任务、布局、导航和分页回归。
- Worker：完整测试通过，本次未修改 Worker 业务代码。
- 运行环境：Compose 已基于最终代码重新构建，Backend、Frontend、Python Worker 均处于 healthy 状态。

## ⚠️ 已知问题与限制

- 供应商被自动移除后，即使后续新增了匹配模型，也需要用户重新将该供应商加入能力路由并再次同步。
- 健康检查失败的匹配模型在用户未删除时仍会进入真实调用候选池，这是已确认的业务行为；实际调用可能继续失败并触发现有候选回退逻辑。
- 旧候选模型路由继续按显式模型列表同步，不执行供应商池自动清理。
- 去重仅在同一次批量同步请求内生效；用户分别发起的两次同步不会共享历史测试结果。
- 同一模型以不同思考配置测试时，模型主表的最近健康状态记录最后一次测试；本次批量响应仍分别保留各路由对应结果。
- 后端编译仍提示 `TaskTraceService` 存在既有未检查泛型操作，未影响测试结果。
- 前端构建仍有既有 runtime-config、第三方 PURE 注释和包体积警告，不影响构建成功。
- 尚未增加真实浏览器端到端测试，本次前端交互通过源码级 Node 测试和生产构建验证。

## 📝 下次测试建议

1. 补充数据库集成测试，验证批量同步事务内的供应商自动清理和健康状态最终落库结果。
2. 补充并发测试，验证多个批量同步请求同时执行时的串行锁和外部模型调用次数。
3. 补充浏览器端到端测试，验证多个功能 Tab 展示复用结果和异常模型移除流程。
4. 后续修改思考等级映射、批量结果结构或模型健康状态逻辑时，基于本报告基准点重新执行完整测试。
