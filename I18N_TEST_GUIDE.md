# 国际化功能测试指南

## 🔍 问题修复

已修复 `Globe` 图标导入错误。Element Plus Icons 中没有 `Globe` 图标，已改用 `Operation` 图标（齿轮/设置图标）。

## ✅ 如何测试

### 1. 访问应用
打开浏览器访问：**http://localhost:5175/**

### 2. 测试语言切换
1. 在顶部导航栏右上角找到**齿轮图标**按钮（用户头像左侧）
2. 点击按钮，会出现语言选择下拉菜单：
   - 🇨🇳 简体中文
   - 🇺🇸 English
3. 选择一个语言，页面会立即切换

### 3. 验证功能
- ✅ 页面标题和描述应该改变
- ✅ 所有按钮文本应该改变
- ✅ 表单标签和占位符应该改变
- ✅ 表格列标题应该改变
- ✅ 状态标签应该翻译（RUNNING → 运行中/Running）
- ✅ 刷新页面后语言设置应该保持

### 4. 测试页面
导航到 **任务调度** 页面（TasksView），这是唯一完全翻译的页面：
- 中文：任务调度、查询、重置、刷新等
- 英文：Task Scheduling、Query、Reset、Refresh等

## 🐛 已修复的问题

**错误**: `SyntaxError: Importing binding name 'Globe' is not found.`

**原因**: Element Plus Icons v2.3.1 中没有 `Globe` 图标

**解决方案**: 使用 `Operation` 图标（齿轮图标）代替

## 🎨 图标替代方案（可选）

如果需要更换图标，可以使用以下任一图标：
- `Operation` - 齿轮/设置图标 ✅ 当前使用
- `Setting` - 设置图标
- `Tools` - 工具图标
- `Menu` - 菜单图标
- 或者使用 Unicode 地球符号：🌐（纯文本，无需导入）

### 使用 Unicode 符号版本
```vue
<el-button circle class="language-switcher">
  🌐
</el-button>
```

## 📝 浏览器控制台检查

打开浏览器开发者工具（F12），检查：
1. 没有 JavaScript 错误
2. `localStorage` 中有 `locale` 键（zh-CN 或 en-US）
3. 切换语言时 `locale` 值会改变

## 🔄 如果仍有问题

1. 清除浏览器缓存和 localStorage
2. 重启前端开发服务器：
   ```bash
   cd /Users/xyzc/github/base-ai/frontend
   npm run dev
   ```
3. 检查浏览器控制台是否有其他错误
