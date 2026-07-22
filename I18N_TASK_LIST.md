# 国际化翻译任务清单

## 📋 任务分配模板

### 任务信息
- **项目路径**: `/Users/xyzc/github/base-ai/frontend`
- **开发服务器**: http://localhost:5175/
- **框架**: Vue 3 + Vue I18n v11
- **UI 组件**: Element Plus

## ✅ 已完成
- AdminLayout.vue (导航栏)
- TasksView.vue (任务调度) ⭐️ **翻译参考模板**

## 📝 待翻译页面 (18个)

### 批次 1 - 核心页面 (优先级最高)

#### 1. LoginView.vue - 登录页面
**位置**: `frontend/src/views/LoginView.vue`

**待翻译内容**:
- 页面描述: "统一模型能力与系统管理平台"
- 表单占位符: "账号"、"密码"
- 按钮: "登录"
- 错误消息: "登录失败"

**翻译键建议**:
```javascript
login: {
  description: '...',
  username: '...',
  password: '...',
  submit: '...',
  loginFailed: '...'
}
```

---

#### 2. DashboardView.vue - 工作台
**位置**: `frontend/src/views/DashboardView.vue`

**需要先读取文件以确定翻译内容**

**执行命令**:
```bash
cat frontend/src/views/DashboardView.vue
```

---

#### 3. UsersView.vue - 用户管理
**位置**: `frontend/src/views/UsersView.vue`

**预期翻译内容**:
- 页面标题: "用户管理"
- 表格列: 用户名、姓名、邮箱、角色、状态、创建时间
- 按钮: 新增用户、编辑、删除、重置密码
- 对话框: 新增用户、编辑用户、确认删除
- 状态: 启用、禁用
- 消息: 创建成功、更新成功、删除成功

**翻译键建议**:
```javascript
users: {
  title: '...',
  username: '...',
  displayName: '...',
  email: '...',
  role: '...',
  status: '...',
  createdAt: '...',
  createUser: '...',
  editUser: '...',
  deleteUser: '...',
  resetPassword: '...',
  // ... 更多
}
```

---

#### 4. RolesView.vue - 角色管理
**位置**: `frontend/src/views/RolesView.vue`

**预期翻译内容**:
- 页面标题: "角色管理"
- 表格列: 角色名称、角色代码、描述、权限数量
- 按钮: 新增角色、编辑、删除、分配权限
- 对话框: 新增角色、编辑角色、权限分配

**翻译键建议**:
```javascript
roles: {
  title: '...',
  name: '...',
  code: '...',
  description: '...',
  permissions: '...',
  // ... 更多
}
```

---

### 批次 2 - 管理页面

#### 5. MenusView.vue - 菜单管理
**位置**: `frontend/src/views/MenusView.vue`

---

#### 6. DepartmentsView.vue - 部门管理
**位置**: `frontend/src/views/DepartmentsView.vue`

---

#### 7. PositionsView.vue - 职位管理
**位置**: `frontend/src/views/PositionsView.vue`

---

#### 8. DictionariesView.vue - 字典管理
**位置**: `frontend/src/views/DictionariesView.vue`

---

#### 9. SettingsView.vue - 系统设置
**位置**: `frontend/src/views/SettingsView.vue`

---

### 批次 3 - 日志和监控

#### 10. LoginLogsView.vue - 登录日志
**位置**: `frontend/src/views/LoginLogsView.vue`

---

#### 11. OperationLogsView.vue - 操作日志
**位置**: `frontend/src/views/OperationLogsView.vue`

---

#### 12. OnlineUsersView.vue - 在线用户
**位置**: `frontend/src/views/OnlineUsersView.vue`

---

### 批次 4 - AI 功能

#### 13. AiChatView.vue - AI 聊天
**位置**: `frontend/src/views/AiChatView.vue`

---

#### 14. ModelsView.vue - 模型管理
**位置**: `frontend/src/views/ModelsView.vue`

---

#### 15. ModelProvidersView.vue - 模型供应商
**位置**: `frontend/src/views/ModelProvidersView.vue`

---

#### 16. ModelRoutesView.vue - 模型路由
**位置**: `frontend/src/views/ModelRoutesView.vue`

---

#### 17. ApiTriggerView.vue - API 触发器
**位置**: `frontend/src/views/ApiTriggerView.vue`

---

## 🔧 执行步骤（针对每个页面）

### Step 1: 读取源文件
```bash
cat frontend/src/views/XxxView.vue
```

### Step 2: 提取需要翻译的文本
识别所有硬编码的中文文本：
- 页面标题、描述
- 按钮文本
- 表单标签和占位符
- 表格列标题
- 提示消息
- 对话框内容

### Step 3: 添加到语言包

编辑 `frontend/src/locales/zh-CN.js` 和 `frontend/src/locales/en-US.js`

### Step 4: 修改 Vue 组件

在 `<script setup>` 中添加：
```javascript
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
```

替换硬编码文本为 `t('key.path')`

### Step 5: 测试验证

1. 启动开发服务器（已运行）
2. 访问页面
3. 切换中英文测试
4. 检查控制台错误

---

## 📊 进度跟踪

| 页面 | 状态 | 负责人 | 完成时间 |
|-----|------|--------|---------|
| LoginView | ⏳ 待翻译 | - | - |
| DashboardView | ⏳ 待翻译 | - | - |
| UsersView | ⏳ 待翻译 | - | - |
| RolesView | ⏳ 待翻译 | - | - |
| MenusView | ⏳ 待翻译 | - | - |
| DepartmentsView | ⏳ 待翻译 | - | - |
| PositionsView | ⏳ 待翻译 | - | - |
| DictionariesView | ⏳ 待翻译 | - | - |
| SettingsView | ⏳ 待翻译 | - | - |
| LoginLogsView | ⏳ 待翻译 | - | - |
| OperationLogsView | ⏳ 待翻译 | - | - |
| OnlineUsersView | ⏳ 待翻译 | - | - |
| AiChatView | ⏳ 待翻译 | - | - |
| ModelsView | ⏳ 待翻译 | - | - |
| ModelProvidersView | ⏳ 待翻译 | - | - |
| ModelRoutesView | ⏳ 待翻译 | - | - |
| ApiTriggerView | ⏳ 待翻译 | - | - |

**总进度**: 0 / 18 (0%)

---

## 📝 提示词模板（给其他 AI 模型）

### 提示词 1: 翻译单个页面

```
我需要你帮我翻译一个 Vue 3 页面的国际化文本。

项目信息：
- 框架：Vue 3 + Vue I18n v11
- UI：Element Plus
- 已完成示例：frontend/src/views/TasksView.vue

任务：
1. 读取 frontend/src/views/LoginView.vue
2. 识别所有需要翻译的中文文本
3. 在 frontend/src/locales/zh-CN.js 和 en-US.js 中添加翻译键
4. 修改 LoginView.vue，使用 t() 函数替换硬编码文本
5. 确保代码可以正常运行

参考：
- 查看 frontend/src/views/TasksView.vue 作为翻译示例
- 查看 I18N_TRANSLATION_GUIDE.md 了解详细步骤

请按照以下格式输出：
1. 提取的翻译文本列表（中英对照）
2. 修改后的语言包代码
3. 修改后的 Vue 组件代码
```

### 提示词 2: 批量翻译

```
我需要你批量翻译多个 Vue 页面的国际化文本。

任务列表：
1. LoginView.vue - 登录页面
2. DashboardView.vue - 工作台
3. UsersView.vue - 用户管理

要求：
- 保持翻译键命名一致性
- 复用 common 模块的通用翻译
- 参考 TasksView.vue 的实现方式

请依次完成每个页面，并在完成后进行测试验证。
```

---

## 🎯 质量标准

每个翻译完成的页面应满足：

✅ **功能完整性**
- 所有可见文本都已翻译
- 中英文切换正常
- 功能无影响

✅ **代码质量**
- 使用统一的翻译键命名规范
- 代码格式规范
- 无控制台错误

✅ **翻译质量**
- 中文表达准确、简洁
- 英文语法正确、专业
- 上下文一致

---

## 📚 参考资源

- **翻译指南**: `I18N_TRANSLATION_GUIDE.md`
- **完整示例**: `frontend/src/views/TasksView.vue`
- **语言包**: `frontend/src/locales/zh-CN.js` 和 `en-US.js`
- **导航栏示例**: `frontend/src/views/AdminLayout.vue`

---

**准备就绪！可以开始分配翻译任务了。**
