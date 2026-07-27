# 系统内置数据国际化修订方案

## 一、需求理解

### 1. 功能目标

语言切换后无需刷新页面，以下 8 类系统内置数据必须立即按当前语言显示：

1. 用户页面的内置管理员 Name 和根 Department。
2. 角色页面的内置角色 Name，以及权限选择器中的系统菜单名称。
3. 菜单页面 BUTTON 类型的 Name；英文 Actions 列不得换行或遮挡。
4. 部门页面根部门 Name，以及所有引用根部门的选择器。
5. Login Logs 页面新旧登录记录的 Message。
6. Task Scheduling 页面列表和筛选器中的 Task Type。
7. Model Configuration 页面列表和表单中的内置 Model Type。
8. Capability Routes 页面及其同步交互中的默认路由 Name。

### 2. 使用场景

- 管理员在任一相关页面从中文切换到英文，当前页面的内置数据显示立即变化。
- 从英文切回中文时，同一批数据显示中文。
- 历史登录日志、历史任务记录与新产生记录使用同一套显示规则。
- 管理员创建的自定义名称不被错误翻译，继续显示数据库原值。
- 编辑系统内置记录时，输入框仍显示数据库原始值，避免将翻译结果保存回数据库。

### 3. 明确不包含

- 不为用户、角色、部门、菜单、路由等实体新增中英文字段。
- 不新增数据库迁移，不修改表结构。
- 不翻译普通用户输入的自定义名称、描述、任务错误详情或日志正文。
- 不新增第三方依赖。
- 不把前端 i18n key 写入字典 label 等管理员可编辑文本字段。

## 二、问题复盘与根因

### 1. 运行环境未应用代码

当前 Frontend、Backend 容器创建时间早于本轮国际化提交，运行环境仍为旧版本。此前修改后未按项目规则执行 `docker compose up --build -d`，所以切换语言时原始 8 个问题全部仍可见。

### 2. 原方案的数据建模方向错误

- 把 `tasks.types.*` 这类界面翻译 key 直接作为 `task_type` 持久化值，混淆业务代码与展示文案。
- 把 `models.types.*` 写入模型类型字典 label；已有字典项不会被初始化逻辑更新，新环境还会在字典管理页暴露翻译 key。
- 多处通过中文文本本身判断是否翻译，可能误翻译同名自定义数据。
- 任务页面直接调用 `t(taskType)`，无法处理历史中文值和非 i18n-key 值。
- 模型页面直接拼接未知类型的翻译 key，新增自定义类型时无法回退字典 label。
- 只修改主表格，遗漏用户角色选择器、默认路由同步选择器和确认文案等同类展示位置。

### 3. 验证结论失真

- 前端完整测试实际为 83 个：82 通过、1 失败，不能标记为通过。
- 后端业务代码在 `TEST_REPORT.md` 基准点之后发生变化，但没有重跑完整测试和更新报告。
- 未执行登录态 8 页面端到端验证，却提前勾选全部验收项。

## 三、技术方案

### 1. 总体原则

采用“稳定业务标识 + 前端即时翻译 + 历史值兼容 + 未知值回退”策略：

- 角色使用 `role.code`。
- 部门使用 `department.code`。
- 菜单使用 `menu.permission`，路径只作为兼容回退。
- 默认能力路由使用 `route.featureCode`。
- 模型类型使用字典 `value`。
- 内置管理员用户使用 ADMIN 角色关联和默认显示名共同识别。
- 任务类型存储语言无关代码；前端兼容新代码、已写入的旧 key 和历史中文值。
- 登录日志新记录存储稳定消息 key；前端兼容历史中文消息。

自定义或未知记录始终回退后端原值，不因当前语言而丢失信息。

### 2. 前端共享解析器

修改 `frontend/src/utils/localization.js`：

- `localizeUserDisplayName(user, roles, translate)`：仅当用户绑定 ADMIN 角色且显示名仍为内置默认值时翻译。
- `localizeRoleName(role, translate)`：按 `ADMIN` 等固定角色 code 翻译。
- `localizeDepartmentName(department, translate)`：按 `ROOT` 等固定部门 code 翻译。
- `localizeRouteName(route, translate)`：按 `DEFAULT` 等固定功能 code 翻译。
- `localizeLoginMessage(message, translate)`：支持新消息 key 与历史中文值。
- `localizeTaskType(value, translate)`：支持稳定任务代码、旧 `tasks.types.*` key、历史中文值和未知值回退。
- `localizeModelType(value, options, translate)`：内置类型按 value 翻译，自定义类型回退字典 label，再回退 value。

`frontend/src/utils/navigation.js` 继续按权限编码翻译内置菜单，并补齐所有实际种子权限映射。未知权限回退 `menu.name`。

### 3. 八个页面接入

- `frontend/src/views/UsersView.vue`
  - 用户表格的管理员显示名和部门。
  - 用户表单的部门、角色选择器。
- `frontend/src/views/RolesView.vue`
  - 角色表格、删除确认、部门选择器、权限选择器。
- `frontend/src/views/MenusView.vue`
  - 全部菜单类型的名称、父菜单选择器和确认文案。
  - BUTTON 名称走权限映射；Actions 列保持英文不换行。
- `frontend/src/views/DepartmentsView.vue`
  - 部门树、父部门选择器和确认文案。
- `frontend/src/views/LoginLogsView.vue`
  - 新消息 key 和历史中文消息统一解析。
- `frontend/src/views/TasksView.vue`
  - 表格和筛选器调用任务类型解析器。
  - 筛选器 `value` 保持数据库原始值，避免查询条件失效。
- `frontend/src/views/ModelsView.vue`
  - 表格与表单调用模型类型解析器。
  - 自定义模型类型显示字典 label，不显示缺失翻译 key。
- `frontend/src/views/ModelRoutesView.vue`
  - 表格、同步选择器和包含路由名的确认文案统一解析。
  - 编辑输入框保留原始名称。

### 4. 后端稳定值修正

- `backend/src/main/java/com/baseai/platform/trace/TraceType.java` 及各使用点：注解 value 改为语言无关任务代码，而不是前端 i18n key。
- `backend/src/main/java/com/baseai/platform/trace/TaskTypeRegistry.java`：保留稳定代码元数据，避免展示 key 反向成为业务值。
- `backend/src/main/java/com/baseai/platform/service/AuthService.java`：登录审计继续保存稳定消息 key；非业务异常不得泄露原始内部异常给前端，日志显示端安全回退。
- `backend/src/main/java/com/baseai/platform/service/DataInitializer.java`：模型类型字典 label 恢复为管理员可读文本，不写入前端翻译 key。
- `backend/src/main/java/com/baseai/platform/service/LlmManagementService.java`：接口返回稳定 value 和可读 label；前端负责内置 value 的即时翻译。

历史任务和登录日志不执行破坏性数据迁移，由前端兼容层保证显示。

## 四、验收标准—测试用例映射

| 验收标准 | 测试层级 | 前置条件与输入 | 预期结果 | 场景类型 |
| --- | --- | --- | --- | --- |
| AC1 用户页内置管理员与根部门随语言切换 | 前端单元 + 登录态 E2E | ADMIN 用户、ROOT 部门，中英切换 | 主表及部门/角色选择器显示对应语言；普通同名用户不误翻译 | 正常、权限、兼容 |
| AC2 角色名与权限菜单随语言切换 | 前端单元 + E2E | ADMIN 角色、全部种子菜单、未知自定义菜单 | 内置项中英文正确，自定义项原值回退 | 正常、边界、兼容 |
| AC3 BUTTON 名称与 Actions 布局正确 | 前端单元 + E2E | CRUD/特殊/兼容按钮，英文桌面和平板宽度 | BUTTON 名称英文；操作按钮不换行、不遮挡 | 正常、边界、回归 |
| AC4 根部门所有展示位置随语言切换 | 前端单元 + E2E | ROOT、普通部门、空父部门 | ROOT 翻译，普通部门原值，空值安全 | 正常、边界、兼容 |
| AC5 新旧登录消息随语言切换 | 前后端单元 + E2E | 新 key、历史中文、未知安全消息、空值 | 已知消息双语正确，未知安全回退，不展示缺失 key | 正常、异常、安全、兼容 |
| AC6 新旧任务类型随语言切换且筛选有效 | 前后端单元 + E2E | 新稳定代码、旧 key、历史中文、未知类型 | 显示双语；筛选仍提交原值并命中记录 | 正常、边界、兼容、回归 |
| AC7 内置/自定义模型类型正确显示 | 前后端单元 + E2E | text/vision、自定义 audio、空字典回退 | 内置双语，自定义显示 label，空目录使用可读回退 | 正常、边界、兼容 |
| AC8 默认路由所有展示位置随语言切换 | 前端单元 + E2E | DEFAULT 与自定义路由 | 表格/同步选择器/确认文案一致；编辑框保留原值 | 正常、兼容、回归 |
| AC9 当前页面无需刷新立即切换 | 前端集成 + E2E | 8 个页面分别执行中→英→中 | 所有展示字段立即变化，不依赖重新请求 | 正常、回归 |
| AC10 未授权用户不能通过改语言扩大访问 | 现有权限回归 + E2E | 无相关权限账号 | 菜单、按钮和接口权限行为不变 | 权限、安全 |

## 五、实施步骤

1. 纠正本方案，删除虚假的完成和测试结论，单独提交计划。
2. 在现有测试中增加 8 项解析器、历史兼容、未知值回退和真实语言资源测试。
3. 修正后端任务代码、登录消息和模型字典契约，并补充对应后端测试。
4. 修正共享解析器与 8 个页面的全部展示位置。
5. 执行定向前端测试和后端测试；失败则回到设计与实现阶段，不跳过测试。
6. 执行前端、后端完整测试套件。
7. 执行 `docker compose up --build -d`，确认三个服务 healthy。
8. 使用真实登录态和真实 API 数据逐项验证 8 个页面的中→英→中切换，并记录实际结果。
9. 检查 `git status`、`git diff` 和 `git diff --check`，只提交本任务相关改动。
10. 业务代码提交后单独更新并提交 `TEST_REPORT.md`，基准点指向已验证的业务代码提交。

## 六、涉及文件

预计修改：

- `.claude/plans/localization-plan.md`
- `frontend/src/utils/localization.js`
- `frontend/src/utils/navigation.js`（仅在映射审计发现缺项时）
- `frontend/src/locales/en-US.js`
- `frontend/src/locales/zh-CN.js`
- 上述 8 个 Vue 页面
- `frontend/test/localization.test.mjs`
- 相关导航、布局或页面测试
- 任务类型注解使用点及任务元数据类
- `backend/src/main/java/com/baseai/platform/service/AuthService.java`
- `backend/src/main/java/com/baseai/platform/service/DataInitializer.java`
- `backend/src/main/java/com/baseai/platform/service/LlmManagementService.java`
- 对应后端测试
- `TEST_REPORT.md`

不新增依赖、配置、数据库迁移或正式 Markdown 文档。

## 七、风险与控制

- **历史数据格式并存**：解析器按稳定代码、旧 key、历史中文、原值回退的顺序兼容。
- **误翻译自定义数据**：优先使用稳定标识；中文文本映射仅用于无法迁移的历史日志和任务值。
- **筛选值被翻译破坏**：下拉框 label 翻译，value 保持原始持久化值。
- **管理员编辑翻译值污染数据**：编辑输入框始终绑定原始字段，翻译仅用于只读展示。
- **未应用到运行环境**：代码验证后强制 Compose 重建，并核对容器创建时间与服务健康。
- **工作区已有未提交修改**：仅暂存本任务确认范围内的文件/代码块，不覆盖或夹带无关修改。

## 八、回滚方式

- 使用本任务各独立提交的父提交进行反向提交，不改写历史。
- 前端解析器和页面接入可独立回滚，不影响数据库原始名称。
- 后端任务代码回滚后仍可读取历史记录；兼容解析器在确认历史数据不再需要前不得删除。
- 本方案不做数据迁移，因此无需数据库回滚。

## 九、确认状态

- 方案确认：已确认。
- 内置名称策略：固定稳定标识翻译。
- 实施范围：修订计划、实现、完整测试、Compose 重建、登录态逐项验证和提交。
- 当前完成状态：已完成。前端完整测试 87/87、后端完整测试 144/144、Compose 三个服务 healthy，真实登录态 API + 生产解析器联合验收 8/8 通过；详细记录见 `TEST_REPORT.md`。
