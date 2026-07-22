# Trace 与平台配置测试报告

## 📋 Git 基准点

- Commit: 078b9fc
- 提交说明: Rename platform configuration semantics
- 测试日期: 2026-07-22
- 分支: master

## 🎯 本次变更范围

- 追踪标识、运行时上下文、日志链路、接口路径、配置项和数据库字段统一采用 Trace 命名。
- 平台配置统一采用 `APP_PLATFORM_*` 环境变量与 `app.platform` 配置结构。
- 新增 `/api/open/platform`，并保留既有公开路径作为兼容别名；两者返回相同的平台配置。
- 排除配置文件使用 `trace-tracking-exclusions.yml`。

## 📊 测试执行结果

| 测试范围 | 执行命令 | 结果 |
| --- | --- | --- |
| 后端完整测试 | `docker run --rm -v "$PWD:/workspace" -v "$HOME/.m2:/root/.m2" -w /workspace maven:3.9.9-eclipse-temurin-17 mvn test -B` | 16 通过，0 失败，0 错误，0 跳过 |
| Python Worker 测试 | `docker run --rm -v "$PWD:/app" -w /app base-ai-python-worker:latest python -m pytest tests -q` | 3 通过 |
| 前端完整回归 | `node --test test/*.test.mjs`（隔离工作树） | 22 通过 |
| 前端构建 | `npm run build`（隔离工作树） | 通过 |
| Compose 配置校验 | `docker compose config --quiet` | 通过 |
| 静态命名扫描 | 旧环境变量前缀与旧平台配置访问器扫描 | 无匹配 |
| 差异格式检查 | `git diff --check` | 通过 |

## 🔄 运行时验证

- 先执行 `docker compose up --build -d`；该命令仅因 Docker Hub 拉取 Python 基础镜像元数据返回 EOF 而中断。
- 使用本地 Python 3.12 Worker 镜像作为构建基座后，完成镜像构建并强制重建后端、前端容器。
- 后端、前端、Python Worker 均为 healthy。
- 后端开放健康接口返回 `UP`；前端运行时配置正确输出平台配置。
- 新平台公开接口和兼容别名均返回一致的平台配置。
- 远端 MySQL、PostgreSQL 均已连接成功；MySQL 已初始化平台基础数据与 Trace 表结构。

## ⚠️ 已知问题与限制

- Docker Hub 的 Python 基础镜像元数据请求偶发 EOF；使用已有本地 Python 3.12 镜像可完成等价的应用镜像重建。
- 前端构建保留既有的脚本引用和大包体积警告，但构建成功。
- 当前工作树保留三项用户原有的前端布局暂存改动；本次前端全量测试在隔离工作树中执行，未将其纳入提交。

## 📝 下次测试建议

1. 在 Docker Hub 网络稳定时再次执行标准全量 Compose 重建。
2. 对真实业务请求执行一次 Trace 创建、查询与日志写入端到端回归。
3. 下次业务代码变更前，以本报告的 Git 基准点判断是否需要重新执行完整测试。
