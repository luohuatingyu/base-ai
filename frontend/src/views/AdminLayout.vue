<template>
  <el-container class="shell">
    <el-aside width="236px" class="sidebar">
      <div class="logo"><span>{{ appConfig.shortName }}</span><strong>{{ appConfig.nameEn }}</strong></div>
      <el-menu router :default-active="$route.path" class="nav">
        <el-menu-item index="/dashboard">工作台</el-menu-item>
        <MenuNode v-for="item in menuTree" :key="item.id" :item="item" />
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="topbar">
        <div><strong>{{ title }}</strong><small>{{ appConfig.nameZh }}</small></div>
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
import { appConfig } from '../config'
import MenuNode from '../components/MenuNode.vue'
import { buildTree } from '../utils/tree'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const menuTree = computed(() => buildTree((auth.user?.menus || []).filter(item => item.visible !== false && item.type !== 'BUTTON')))
const title = computed(() => auth.user?.menus?.find(item => item.path === route.path)?.name || (route.path === '/dashboard' ? '工作台' : appConfig.nameEn))

/** 处理用户菜单命令。 */
async function handleCommand(command) {
  if (command === 'logout') { await auth.logout(); await router.replace('/login') }
}
</script>
