# 前端页面国际化翻译任务方案

## 📋 任务概述

本文档提供了一份完整的前端页面国际化翻译方案。项目已完成国际化框架搭建，现需要翻译剩余的 18 个视图页面。

## ✅ 已完成的工作

- ✅ Vue I18n 框架集成
- ✅ 中英文语言包结构创建
- ✅ 语言切换组件实现
- ✅ Element Plus 组件国际化配置
- ✅ AdminLayout（导航栏）完全翻译
- ✅ TasksView（任务调度页面）完全翻译 ⭐️ **参考模板**

## 📝 待翻译的页面清单

### 优先级 1 - 核心页面（建议优先翻译）
1. **LoginView.vue** - 登录页面
2. **DashboardView.vue** - 工作台
3. **UsersView.vue** - 用户管理
4. **RolesView.vue** - 角色管理

### 优先级 2 - 管理页面
5. **MenusView.vue** - 菜单管理
6. **DepartmentsView.vue** - 部门管理
7. **PositionsView.vue** - 职位管理
8. **DictionariesView.vue** - 字典管理
9. **SettingsView.vue** - 系统设置

### 优先级 3 - 日志和监控
10. **LoginLogsView.vue** - 登录日志
11. **OperationLogsView.vue** - 操作日志
12. **OnlineUsersView.vue** - 在线用户

### 优先级 4 - AI 功能
13. **AiChatView.vue** - AI 聊天
14. **ModelsView.vue** - 模型管理
15. **ModelProvidersView.vue** - 模型供应商
16. **ModelRoutesView.vue** - 模型路由
17. **ApiTriggerView.vue** - API 触发器

## 🎯 翻译标准流程

### 第一步：阅读源文件并提取文本

1. 读取视图文件（例如 `LoginView.vue`）
2. 识别所有需要翻译的文本：
   - 页面标题、描述
   - 按钮文本（登录、保存、取消等）
   - 表单标签和占位符
   - 表格列标题
   - 提示消息（成功、失败、警告）
   - 对话框标题和内容
   - 空状态提示
   - 验证错误消息

3. **参考已完成的 TasksView.vue** 作为翻译模板

### 第二步：在语言包中添加翻译

#### 文件位置
- 中文：`frontend/src/locales/zh-CN.js`
- 英文：`frontend/src/locales/en-US.js`

#### 命名规范
使用模块化的键名结构：
```javascript
{
  moduleName: {
    sectionName: {
      itemName: '翻译文本'
    }
  }
}
```

#### 示例：LoginView 翻译键

```javascript
// zh-CN.js
export default {
  // ... 现有内容
  login: {
    title: '用户登录',
    description: '统一模型能力与系统管理平台',
    username: '账号',
    password: '密码',
    submit: '登录',
    usernamePlaceholder: '请输入账号',
    passwordPlaceholder: '请输入密码',
    loginSuccess: '登录成功',
    loginFailed: '登录失败',
    usernameRequired: '请输入账号',
    passwordRequired: '请输入密码'
  }
}

// en-US.js
export default {
  // ... 现有内容
  login: {
    title: 'User Login',
    description: 'Unified Model Capability and System Management Platform',
    username: 'Username',
    password: 'Password',
    submit: 'Sign In',
    usernamePlaceholder: 'Enter username',
    passwordPlaceholder: 'Enter password',
    loginSuccess: 'Login successful',
    loginFailed: 'Login failed',
    usernameRequired: 'Username is required',
    passwordRequired: 'Password is required'
  }
}
```

### 第三步：修改 Vue 组件

#### 1. 导入 i18n
```vue
<script setup>
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
// ... 其他导入和代码
</script>
```

#### 2. 替换硬编码文本

**原代码：**
```vue
<template>
  <h1>用户登录</h1>
  <el-input placeholder="账号" v-model="form.username" />
  <el-button>登录</el-button>
</template>
```

**修改后：**
```vue
<template>
  <h1>{{ t('login.title') }}</h1>
  <el-input :placeholder="t('login.usernamePlaceholder')" v-model="form.username" />
  <el-button>{{ t('login.submit') }}</el-button>
</template>
```

#### 3. 替换 JavaScript 中的文本

**原代码：**
```javascript
ElMessage.success('操作成功')
ElMessage.error('操作失败')
```

**修改后：**
```javascript
ElMessage.success(t('common.success'))
ElMessage.error(t('common.failed'))
```

#### 4. 替换对话框和确认框

**原代码：**
```javascript
await ElMessageBox.confirm('确定要删除吗？', '提示', {
  confirmButtonText: '确定',
  cancelButtonText: '取消',
  type: 'warning'
})
```

**修改后：**
```javascript
await ElMessageBox.confirm(
  t('users.deleteConfirm'), 
  t('common.warning'), 
  {
    confirmButtonText: t('common.confirm'),
    cancelButtonText: t('common.cancel'),
    type: 'warning'
  }
)
```

### 第四步：验证和测试

1. 启动开发服务器：`npm run dev`
2. 访问页面并测试两种语言
3. 检查所有文本是否正确显示
4. 确保切换语言时所有内容都更新
5. 检查浏览器控制台是否有错误

## 📖 翻译参考模板

### TasksView.vue - 完整示例

TasksView 已经完全翻译，可以作为参考：

**查看位置：**
- 源文件：`frontend/src/views/TasksView.vue`
- 中文语言包：`frontend/src/locales/zh-CN.js` 中的 `tasks` 部分
- 英文语言包：`frontend/src/locales/en-US.js` 中的 `tasks` 部分

**包含的翻译类型：**
- ✅ 页面标题和描述
- ✅ 按钮文本
- ✅ 表单控件（输入框、选择器、开关）
- ✅ 表格列标题
- ✅ 状态标签
- ✅ 对话框标题和内容
- ✅ 抽屉标题
- ✅ 提示消息
- ✅ 空状态提示
- ✅ 占位符文本

## 🔧 常用翻译键（已定义）

### common（通用）
```javascript
t('common.confirm')    // 确认 / Confirm
t('common.cancel')     // 取消 / Cancel
t('common.save')       // 保存 / Save
t('common.delete')     // 删除 / Delete
t('common.edit')       // 编辑 / Edit
t('common.add')        // 添加 / Add
t('common.search')     // 搜索 / Search
t('common.reset')      // 重置 / Reset
t('common.refresh')    // 刷新 / Refresh
t('common.query')      // 查询 / Query
t('common.detail')     // 详情 / Detail
t('common.actions')    // 操作 / Actions
t('common.success')    // 操作成功 / Success
t('common.failed')     // 操作失败 / Failed
```

### nav（导航）
```javascript
t('nav.dashboard')     // 工作台 / Dashboard
t('nav.logout')        // 退出登录 / Logout
```

## 📐 翻译规范

### 1. 键名规范
- 使用小驼峰命名：`userName` 而不是 `user_name`
- 模块名使用单数：`user` 而不是 `users`
- 动作使用动词：`createUser` 而不是 `userCreate`

### 2. 文本规范
- **中文**：
  - 使用简体中文
  - 按钮文字简洁（2-4个字）
  - 提示消息完整（包含主语和谓语）
  
- **英文**：
  - 使用美式英语拼写
  - 按钮使用祈使句（动词开头）：Save、Delete、Create
  - 标题使用标题大小写（Title Case）：User Management
  - 提示消息使用完整句子：User created successfully

### 3. 特殊情况处理

#### 带参数的翻译
```javascript
// 语言包
{
  deleteConfirm: '确定要删除 {name} 吗？',
  totalRecords: '共 {count} 条记录'
}

// 使用
t('users.deleteConfirm', { name: user.name })
t('users.totalRecords', { count: 100 })
```

#### 复数形式
```javascript
// 语言包
{
  itemCount: '没有项目 | 1个项目 | {n}个项目'
}

// 使用（Vue I18n 自动选择）
t('users.itemCount', 0)  // 没有项目
t('users.itemCount', 1)  // 1个项目
t('users.itemCount', 5)  // 5个项目
```

## 🚀 快速开始示例

### 翻译 LoginView.vue

#### 1. 读取源文件
```bash
cat frontend/src/views/LoginView.vue
```

识别需要翻译的文本：
- "统一模型能力与系统管理平台"
- "账号"（占位符）
- "密码"（占位符）
- "登录"（按钮）
- "登录失败"（错误消息）

#### 2. 添加到语言包

**zh-CN.js：**
```javascript
export default {
  // ... 现有内容
  login: {
    title: '用户登录',
    description: '统一模型能力与系统管理平台',
    username: '账号',
    password: '密码',
    submit: '登录',
    loginFailed: '登录失败'
  }
}
```

**en-US.js：**
```javascript
export default {
  // ... 现有内容
  login: {
    title: 'User Login',
    description: 'Unified Model Capability and System Management Platform',
    username: 'Username',
    password: 'Password',
    submit: 'Sign In',
    loginFailed: 'Login failed'
  }
}
```

#### 3. 修改 LoginView.vue

```vue
<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="brand-mark">{{ appConfig.shortName }}</div>
      <h1>{{ appConfig.nameEn }}</h1>
      <p>{{ t('login.description') }}</p>
      <el-form @submit.prevent="submit">
        <el-form-item>
          <el-input 
            v-model="form.username" 
            size="large" 
            :placeholder="t('login.username')" 
          />
        </el-form-item>
        <el-form-item>
          <el-input 
            v-model="form.password" 
            size="large" 
            type="password" 
            show-password 
            :placeholder="t('login.password')" 
          />
        </el-form-item>
        <el-button 
          class="full" 
          size="large" 
          type="primary" 
          :loading="loading" 
          @click="submit"
        >
          {{ t('login.submit') }}
        </el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import { appConfig } from '../config'

const { t } = useI18n()
const form = reactive({ username: '', password: '' })
const loading = ref(false)
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

async function submit() {
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    await router.replace(route.query.redirect || '/dashboard')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || t('login.loginFailed'))
  } finally { 
    loading.value = false 
  }
}
</script>
```

## 📋 翻译检查清单

每个页面翻译完成后，请检查：

- [ ] 所有可见文本都使用 `t()` 函数
- [ ] 语言包中的键名清晰且有意义
- [ ] 中英文翻译都准确完整
- [ ] 按钮文本简洁明了
- [ ] 错误消息清晰易懂
- [ ] 在组件中导入了 `useI18n`
- [ ] 测试了中英文两种语言
- [ ] 检查了浏览器控制台无错误
- [ ] 动态文本（如状态标签）也已翻译
- [ ] 对话框和提示消息已翻译

## 🔍 常见问题

### Q1: 如何处理从后端返回的文本？
A: 后端返回的固定文本（如状态、类型）应该在前端翻译。动态内容（如用户名、描述）不需要翻译。

### Q2: 表格列标题如何翻译？
A: 使用 `:label="t('key')"`：
```vue
<el-table-column :label="t('users.username')" prop="username"/>
```

### Q3: 如何翻译枚举值（如状态）？
A: 创建映射对象：
```javascript
// 语言包
status: {
  ACTIVE: '激活',
  INACTIVE: '停用',
  PENDING: '待审核'
}

// 使用
{{ t(`users.status.${row.status}`) }}
```

### Q4: 验证规则如何国际化？
A: 在 rules 中使用 computed 或函数：
```javascript
const rules = computed(() => ({
  username: [
    { required: true, message: t('users.usernameRequired'), trigger: 'blur' }
  ]
}))
```

## 📦 输出要求

完成每个页面翻译后，请确保：

1. **修改的文件**：
   - `frontend/src/views/XxxView.vue` - 视图文件
   - `frontend/src/locales/zh-CN.js` - 中文语言包
   - `frontend/src/locales/en-US.js` - 英文语言包

2. **测试验证**：
   - 页面可以正常访问
   - 中英文切换正常
   - 所有功能正常工作

3. **代码质量**：
   - 代码格式规范
   - 无 ESLint 错误
   - 无控制台警告

## 🎯 批量翻译建议

### 方法 1：逐个页面翻译（推荐新手）
- 优点：每个页面独立，容易理解和测试
- 缺点：较慢
- 适合：不熟悉项目结构的开发者

### 方法 2：模块批量翻译（推荐有经验者）
- 优点：可以复用相似的翻译键，效率高
- 缺点：需要理解整体结构
- 适合：熟悉项目的开发者

建议顺序：
1. 先翻译所有页面的标题、描述
2. 再翻译所有表单和按钮
3. 最后翻译提示消息和对话框

## 📞 技术支持

### 参考文档
- Vue I18n 官方文档：https://vue-i18n.intlify.dev/
- Element Plus 国际化：https://element-plus.org/zh-CN/guide/i18n.html

### 已完成的示例
- `frontend/src/views/TasksView.vue` - 完整翻译示例
- `frontend/src/views/AdminLayout.vue` - 导航栏翻译示例

### 语言包位置
- `frontend/src/locales/zh-CN.js`
- `frontend/src/locales/en-US.js`

---

**祝翻译顺利！如有问题，请参考 TasksView.vue 的实现方式。**
