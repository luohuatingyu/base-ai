# Trace 全量改名测试报告

## 📋 Git 基准点

- Commit: 7eefcab
- 提交说明: Trace naming migration
- 测试日期: 2026-07-22
- 分支: master

## 🎯 本次变更范围

- 将全局追踪标识、运行时上下文、日志链路、接口路径、配置项和数据库字段统一命名为 Trace。
- 将排除配置文件更名为 `trace-tracking-exclusions.yml`。
- 新版本仅识别 Trace 数据库结构；历史数据结构需由运维人员在数据库可连接后按无损方案完成一次性调整。

## 📊 测试执行结果

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 后端完整测试 | `docker run --rm -v "$PWD:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 15 通过，0 失败，0 错误，0 跳过 |
| Python Worker 测试 | `docker run --rm -v "$PWD:/app" -w /app base-ai-python-worker:latest python -m pytest tests -q` | 3 通过 |
| 前端追踪接口测试 | `node --test test/api-trigger.test.mjs` | 13 通过 |
| 前端完整回归测试 | `node --test test/*.test.mjs`（隔离工作树） | 22 通过 |
| 前端构建 | `npm run build`（隔离工作树） | 通过 |
| 静态命名扫描 | 大小写不敏感的遗留命名扫描 | 无匹配 |
| 差异格式检查 | `git diff --check` | 通过 |

## 🔄 服务重建验证

- 已执行 `docker compose up --build -d`；前端与后端镜像构建成功，后端镜像构建阶段的 15 个测试全部通过。
- 官方 Python 基础镜像元数据拉取受 Docker Hub 网络超时影响。随后使用本地 Python 3.12 基础镜像执行 `PYTHON_IMAGE=base-ai-python-worker:latest docker compose up --build -d`，三个服务镜像均已重新构建。
- Python Worker 容器健康检查通过。后端容器因远端 MySQL 连接被拒绝而无法健康启动，前端因此未能启动。

## ⚠️ 已知问题与限制

- 远端 MySQL 和 PostgreSQL 当前不可连接，无法执行已确认的手工无损数据库结构调整，也无法完成依赖真实数据库的端到端验证。
- 当前工作树存在三个用户原有的前端布局改动；完整前端回归在隔离工作树中执行，以避免将无关改动纳入本次验证。
- Docker Hub 网络超时为环境网络问题，不影响已经使用本地 Python 3.12 镜像完成的应用镜像重建。

## 📝 后续建议

1. 恢复远端数据库网络连通性后，先备份并核对行数，再执行一次性无损 Trace 结构调整。
2. 数据库调整完成后，重新启动 Compose 服务并验证后端健康检查、Trace 查询接口和日志写入。
3. 下次业务代码变更前，以本报告的 Git 基准点检查是否需要重新执行完整测试。
