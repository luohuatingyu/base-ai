# 系统内置数据国际化方案

## 问题范围

用户报告了 8 个本地化缺失问题：

1. **用户页面** Name 字段和 Department 显示中文种子数据
2. **权限界面** Name 下的角色/菜单字段显示中文
3. **菜单页面** Type 为 BUTTON 的 Name 字段未适配；Actions 列宽度不足导致英文换行
4. **部门页面** Name 字段显示中文（需检查其他类似位置）
5. **Login Logs 页面** Message 字段显示中文
6. **Task Scheduling** Task Type 字段显示中文
7. **Model Configuration** Model Type 字段显示中文字典标签
8. **Capability Routes** Name 字段显示中文

## 技术方案

### 总体策略

**后端本地化枚举类值，前端映射实体名称**：

- **后端改动**（触发测试）：任务类型、模型类型、登录消息 API 返回 i18n key
- **前端映射**（无需后端测试）：角色名、部门名、菜单按钮名、路由名、用户 displayName 通过值映射+回退
- **适配现有逻辑**：扩展 `localizeMenuName` 的 `menuKeysByPermission` 覆盖 BUTTON 权限

### 实施细节

#### 一、后端改动（需重新测试）

##### 1.1 任务类型本地化

**文件**：`backend/src/main/java/com/baseai/platform/trace/TraceType.java`

**现状**：`@TraceType(value = "AI 对话")` 等注解使用中文字符串，存储到数据库并返回给前端。

**改动**：
- 修改 `value` 为 i18n key 格式（如 `tasks.types.aiChat`）
- 更新所有使用 `@TraceType` 的地方（9 处）

**涉及文件**：
- `com/baseai/platform/controller/AiChatController.java`
- `com/baseai/platform/automation/ApiTriggerController.java`
- `com/baseai/platform/automation/ApiTriggerSecurityController.java`
- `com/baseai/platform/automation/ApiTriggerScheduler.java`

**i18n key 映射**：
```
AI 对话 → tasks.types.aiChat
新增接口触发配置 → tasks.types.createTrigger
更新接口触发配置 → tasks.types.updateTrigger
停用接口触发配置 → tasks.types.disableTrigger
作废接口触发配置 → tasks.types.voidTrigger
手动执行接口触发 → tasks.types.executeTrigger
接口定时触发 → tasks.types.cronTrigger
临时测试接口调用 → tasks.types.testTrigger
更新接口触发安全配置 → tasks.types.updateTriggerSecurity
```

##### 1.2 模型类型本地化

**文件**：`backend/src/main/java/com/baseai/platform/service/LlmManagementService.java`

**现状**：`modelTypes()` 方法返回 `ModelTypeOption(value, label)`，label 从字典表读取中文标签。

**改动**：
- `label` 字段改为返回 i18n key（如 `models.types.text`）
- 回退逻辑中的硬编码标签 `"文本模型"` `"视觉模型"` 改为 key

**字典种子数据**（DataInitializer）：
```java
seedModelType("text_model", "models.types.text", 10);
seedModelType("vision_model", "models.types.vision", 20);
```

##### 1.3 登录消息本地化

**文件**：`backend/src/main/java/com/baseai/platform/service/AuthService.java`

**现状**：
```java
loginAuditService.save(user.getUsername(), metadata, true, "登录成功");
loginAuditService.save(normalized, metadata, false, exception.getMessage());
```

成功消息硬编码中文，失败消息从 `BusinessException.getMessage()` 获取（已解析为中文）。

**改动**：
```java
loginAuditService.save(user.getUsername(), metadata, true, "auth.loginSuccess");
loginAuditService.save(normalized, metadata, false, exception.getMessageKey());
```

**注意**：`BusinessException` 已包含 `getMessageKey()` 方法返回未解析的 key。

#### 二、前端映射（无需后端测试）

##### 2.1 创建共享本地化工具

**文件**：`frontend/src/utils/localization.js`（新建）

```javascript
/** 按已知中文种子值映射角色名，未知值回退原值。 */
export function localizeRoleName(role, translate) {
  const nameMap = { '系统管理员': 'roles.admin' }
  const key = role?.code === 'ADMIN' ? 'roles.admin' : nameMap[role?.name]
  return key ? translate(key) : (role?.name || '')
}

/** 按已知中文种子值映射部门名，未知值回退原值。 */
export function localizeDepartmentName(department, translate) {
  const nameMap = { 'AI平台': 'departments.root' }
  const key = department?.code === 'ROOT' ? 'departments.root' : nameMap[department?.name]
  return key ? translate(key) : (department?.name || '')
}

/** 按已知中文种子值映射用户显示名，未知值回退原值。 */
export function localizeUserDisplayName(username, displayName, translate) {
  const nameMap = { '系统管理员': 'users.systemAdmin' }
  const key = nameMap[displayName]
  return key ? translate(key) : (displayName || username || '')
}

/** 按已知中文种子值映射路由名，未知值回退原值。 */
export function localizeRouteName(route, translate) {
  const nameMap = { '默认能力路由': 'routes.default' }
  const key = route?.featureCode === 'DEFAULT' ? 'routes.default' : nameMap[route?.name]
  return key ? translate(key) : (route?.name || '')
}

/** 按已知中文消息映射登录日志，未知值回退原值。 */
export function localizeLoginMessage(message, translate) {
  // 如果是 i18n key 格式（auth.xxx），直接翻译
  if (message?.startsWith('auth.')) return translate(message)
  
  // 否则按中文值映射（兼容历史数据）
  const messageMap = {
    '登录成功': 'auth.loginSuccess',
    '用户名或密码错误': 'auth.loginFailed',
    '账号或密码错误': 'auth.invalidCredentials',
    '账号已停用': 'auth.accountDisabled',
    '登录用户不存在': 'auth.userNotFound'
  }
  const key = messageMap[message]
  return key ? translate(key) : (message || '')
}
```

##### 2.2 扩展菜单权限映射

**文件**：`frontend/src/utils/navigation.js`

**改动**：在 `menuKeysByPermission` 中补全所有 BUTTON 权限的映射：

```javascript
const menuKeysByPermission = Object.freeze({
  // ... 现有映射 ...
  
  // BUTTON 权限补充
  'system:user:create': 'menus.buttons.createUser',
  'system:user:update': 'menus.buttons.updateUser',
  'system:user:delete': 'menus.buttons.deleteUser',
  'system:role:create': 'menus.buttons.createRole',
  'system:role:update': 'menus.buttons.updateRole',
  'system:role:delete': 'menus.buttons.deleteRole',
  // ... 其他 CRUD 按钮 ...
  'system:session:terminate': 'menus.buttons.forceLogout',
  'system:task:manage': 'menus.buttons.manageTask',
  'system:api-key:create': 'menus.buttons.createApiKey',
  'system:api-key:update': 'menus.buttons.updateApiKey',
  'system:api-key:delete': 'menus.buttons.revokeApiKey',
  'system:api-key:rotate': 'menus.buttons.rotateApiKey',
  'automation:api-trigger:create': 'menus.buttons.createTrigger',
  'automation:api-trigger:update': 'menus.buttons.updateTrigger',
  'automation:api-trigger:delete': 'menus.buttons.deleteTrigger',
  'automation:api-trigger:trigger': 'menus.buttons.executeTrigger',
  'automation:api-trigger:logs': 'menus.buttons.triggerLogs',
  'automation:api-trigger-security:update': 'menus.buttons.updateTriggerSecurity',
  // 兼容权限
  'system:user:manage': 'menus.buttons.manageUser',
  'system:role:manage': 'menus.buttons.manageRole',
  'system:menu:manage': 'menus.buttons.manageMenu'
})
```

##### 2.3 更新各页面视图

**UsersView.vue**：
```vue
<el-table-column prop="displayName" :label="t('common.name')">
  <template #default="scope">{{ localizeUserDisplayName(scope.row.username, scope.row.displayName, t) }}</template>
</el-table-column>
<el-table-column :label="t('users.department')">
  <template #default="s">{{ localizeDepartmentName(departments.find(d => d.id === s.row.departmentId), t) }}</template>
</el-table-column>
```

**RolesView.vue**：
```vue
<el-table-column prop="name" :label="t('common.name')">
  <template #default="scope">{{ localizeRoleName(scope.row, t) }}</template>
</el-table-column>
<!-- 权限选择器 -->
<el-select v-model="form.menuIds" multiple filterable>
  <el-option v-for="menu in menus" :key="menu.id" 
    :label="localizeMenuName(menu, t) + (menu.permission ? ' · ' + menu.permission : '')" 
    :value="menu.id"/>
</el-select>
```

**DepartmentsView.vue**：
```vue
<el-table-column :label="t('common.name')">
  <template #default="scope">{{ localizeDepartmentName(scope.row, t) }}</template>
</el-table-column>
<!-- 父部门选择器 -->
<el-select v-model="form.parentId" clearable>
  <el-option v-for="item in rows" :key="item.id" 
    :label="localizeDepartmentName(item, t)" 
    :value="item.id"/>
</el-select>
```

**MenusView.vue**：
```vue
<!-- Type 列本地化 -->
<el-table-column :label="t('common.type')" width="100">
  <template #default="scope">{{ t(`menus.types.${scope.row.type.toLowerCase()}`) }}</template>
</el-table-column>
<!-- Actions 列宽度调整 -->
<el-table-column :label="t('common.operation')" width="240">
```

**LoginLogsView.vue**：
```vue
<el-table-column :label="t('logs.message')">
  <template #default="scope">{{ localizeLoginMessage(scope.row.message, t) }}</template>
</el-table-column>
```

**TasksView.vue**：
```vue
<el-table-column :label="t('tasks.taskType')" min-width="150">
  <template #default="scope">{{ t(scope.row.task_type) }}</template>
</el-table-column>
<!-- 筛选器 -->
<el-select v-model="query.taskType" clearable filterable :placeholder="t('tasks.taskType')">
  <el-option v-for="item in taskTypes" :key="item" :label="t(item)" :value="item"/>
</el-select>
```

**ModelsView.vue**：
```vue
<el-table-column :label="t('models.modelType')">
  <template #default="scope">
    <el-tag v-for="type in scope.row.supportedModelTypes" :key="type" size="small">
      {{ t(`models.types.${type}`) }}
    </el-tag>
  </template>
</el-table-column>
<!-- 表单中使用 typeLabel 改为直接翻译 value -->
<el-checkbox v-for="type in modelTypes" :key="type.value" :label="type.value">
  {{ t(`models.types.${type.value}`) }}
</el-checkbox>
```

**ModelRoutesView.vue**：
```vue
<el-table-column :label="t('common.name')">
  <template #default="scope">{{ localizeRouteName(scope.row, t) }}</template>
</el-table-column>
```

##### 2.4 更新国际化资源

**en-US.js**：
```javascript
roles: { 
  admin: 'System Administrator',
  // ...
},
departments: { 
  root: 'AI Platform',
  // ...
},
users: { 
  systemAdmin: 'System Administrator',
  // ...
},
routes: { 
  default: 'Default Route',
  // ...
},
menus: {
  types: { catalog: 'Catalog', menu: 'Menu', button: 'Button' },
  buttons: {
    createUser: 'Create User',
    updateUser: 'Edit User',
    deleteUser: 'Delete User',
    // ... 其他按钮
    forceLogout: 'Force Logout',
    manageTask: 'Task Management',
    // ...
  }
},
tasks: {
  types: {
    aiChat: 'AI Chat',
    createTrigger: 'Create Trigger',
    updateTrigger: 'Update Trigger',
    disableTrigger: 'Disable Trigger',
    voidTrigger: 'Void Trigger',
    executeTrigger: 'Execute Trigger',
    cronTrigger: 'Scheduled Trigger',
    testTrigger: 'Temporary Test',
    updateTriggerSecurity: 'Update Trigger Security'
  }
},
models: {
  types: {
    text_model: 'Text Model',
    vision_model: 'Vision Model'
  }
}
```

**zh-CN.js**：对应补充中文 key。

**messages_en_US.properties**（后端）：
```properties
# 已存在，无需修改
auth.loginSuccess=Login successful
auth.loginFailed=Incorrect username or password
# ...
```

#### 三、测试与验证

##### 3.1 前端测试

**命令**：`cd frontend && node --test test/*.test.mjs`

**新增测试**（`frontend/test/localization.test.mjs`）：
```javascript
import assert from 'node:assert/strict'
import test from 'node:test'
import { localizeRoleName, localizeDepartmentName, localizeUserDisplayName, 
         localizeRouteName, localizeLoginMessage } from '../src/utils/localization.js'

const t = key => ({ 
  'roles.admin': 'System Administrator', 
  'departments.root': 'AI Platform',
  'users.systemAdmin': 'System Administrator',
  'routes.default': 'Default Route',
  'auth.loginSuccess': 'Login successful'
}[key] || key)

test('角色名按 code 和值映射', () => {
  assert.equal(localizeRoleName({ code: 'ADMIN', name: '系统管理员' }, t), 'System Administrator')
  assert.equal(localizeRoleName({ code: 'CUSTOM', name: '自定义角色' }, t), '自定义角色')
})

test('部门名按 code 和值映射', () => {
  assert.equal(localizeDepartmentName({ code: 'ROOT', name: 'AI平台' }, t), 'AI Platform')
  assert.equal(localizeDepartmentName({ code: 'DEPT_001', name: '研发部' }, t), '研发部')
})

test('登录消息兼容 key 和中文值', () => {
  assert.equal(localizeLoginMessage('auth.loginSuccess', t), 'Login successful')
  assert.equal(localizeLoginMessage('登录成功', t), 'Login successful')
  assert.equal(localizeLoginMessage('未知错误', t), '未知错误')
})
```

##### 3.2 后端测试

**触发条件**：业务代码变更（AuthService、LlmManagementService、TraceType 注解）

**执行**：
```bash
docker compose up --build -d
cd backend && mvn test -B
```

**测试重点**：
- 登录成功/失败后 message 字段是否为 key
- `/api/system/tasks/task-types` 返回 key 列表
- `/api/models/model-types` 的 label 字段为 key

##### 3.3 端到端验证

1. 切换到英文，检查用户页 Name 和 Department 是否显示英文
2. 角色页 Name 列和权限下拉菜单是否英文
3. 菜单页 Type 为 BUTTON 的 Name 是否翻译，Actions 列是否足够宽
4. 部门页 Name 列和父部门下拉菜单是否英文
5. 登录日志 Message 是否翻译（新登录记录）
6. 任务调度 Task Type 是否翻译
7. 模型配置 Model Type 是否显示英文
8. 能力路由 Name 列默认路由是否翻译

#### 四、风险与回滚

**风险**：
1. 后端改动破坏现有 @TraceType 注解使用方
2. 登录日志历史数据无法翻译（可接受）
3. 字典表中已有中文标签的模型类型记录需手动更新

**回滚**：
- 前端：恢复原视图文件
- 后端：恢复 AuthService、LlmManagementService、TraceType 注解
- 数据库：无需回滚（key 和中文值共存不影响功能）

## 实施步骤

1. ✅ 需求理解与影响分析（已完成）
2. ⏳ 方案确认（待用户批准）
3. 后端改动：
   - 修改 @TraceType 注解 value 为 key
   - 修改 LlmManagementService.modelTypes() 返回 key
   - 修改 AuthService 登录日志存储 key
   - 更新 messages_en_US.properties
4. 前端改动：
   - 创建 localization.js 工具
   - 扩展 menuKeysByPermission
   - 更新 8 个视图组件
   - 更新 en-US.js 和 zh-CN.js
   - 新增 localization.test.mjs
5. 测试验证：
   - 运行前端测试
   - 重新构建并运行后端测试
   - 端到端验证 8 个页面
6. 提交变更：
   - 后端变更独立提交
   - 前端变更独立提交
   - 更新 TEST_REPORT.md

## 验收标准

- [ ] 用户页 Name 和 Department 在英文下显示 "System Administrator" 和 "AI Platform"
- [ ] 角色页 Name 和权限菜单在英文下正确翻译
- [ ] 菜单页 BUTTON 类型 Name 翻译，Actions 列无换行
- [ ] 部门页 Name 和父部门选择器在英文下翻译
- [ ] 登录日志 Message 在英文下翻译（新记录）
- [ ] 任务调度 Task Type 在英文下翻译
- [ ] 模型配置 Model Type 在英文下显示 "Text Model" / "Vision Model"
- [ ] 能力路由 DEFAULT 路由 Name 在英文下显示 "Default Route"
- [ ] 切换回中文所有字段正常显示
- [ ] 前端测试通过
- [ ] 后端测试通过
