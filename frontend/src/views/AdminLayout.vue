<template>
  <el-container class="shell">
    <el-aside width="236px" class="sidebar">
      <div class="logo"><span>BA</span><strong>Base AI</strong></div>
      <el-menu router :default-active="$route.path" class="nav">
        <el-menu-item index="/dashboard">工作台</el-menu-item>
        <el-menu-item v-if="auth.hasPermission('ai:chat:invoke')" index="/ai-chat">AI 对话</el-menu-item>
        <el-menu-item v-if="auth.hasPermission('system:user:manage')" index="/users">用户管理</el-menu-item>
        <el-menu-item v-if="auth.hasPermission('system:role:manage')" index="/roles">角色管理</el-menu-item>
        <el-menu-item v-if="auth.hasPermission('system:menu:manage')" index="/menus">权限菜单</el-menu-item>
        <el-menu-item v-if="auth.hasPermission('system:task:view')" index="/tasks">任务调度</el-menu-item>
        <el-menu-item v-if="auth.hasPermission('automation:api-trigger:list')" index="/automation/api-triggers">接口触发</el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="topbar">
        <div><strong>{{ title }}</strong><small>基础平台</small></div>
        <el-dropdown @command="handleCommand">
          <span class="user-chip">{{ auth.user?.displayName || auth.user?.username }}</span>
          <template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
        </el-dropdown>
      </el-header>
      <el-main class="main"><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const titles = { '/dashboard': '工作台', '/ai-chat': 'AI 对话', '/users': '用户管理', '/roles': '角色管理', '/menus': '权限菜单', '/tasks': '任务调度', '/automation/api-triggers': '接口触发' }
const title = computed(() => titles[route.path] || 'Base AI')

/** 处理用户菜单命令。 */
async function handleCommand(command) {
  if (command === 'logout') { await auth.logout(); await router.replace('/login') }
}
</script>
