<template>
  <el-container class="shell">
    <el-aside width="236px" class="sidebar">
      <div class="logo"><span>{{ appConfig.shortName }}</span><strong>{{ appConfig.nameEn }}</strong></div>
      <el-scrollbar class="nav-scroll">
        <el-menu router :default-active="$route.path" class="nav">
          <el-menu-item index="/dashboard"><el-icon><HomeFilled /></el-icon><span>工作台</span></el-menu-item>
          <MenuNode v-for="item in menuTree" :key="item.id" :item="item" />
        </el-menu>
      </el-scrollbar>
    </el-aside>
    <el-container>
      <el-header class="topbar">
        <div class="topbar-title">
          <el-button class="nav-trigger" text @click="mobileNavigationOpen = true"><el-icon><Menu /></el-icon></el-button>
          <div><strong>{{ title }}</strong><small>{{ appConfig.nameZh }}</small></div>
        </div>
        <el-dropdown @command="handleCommand">
          <span class="user-chip">{{ auth.user?.displayName || auth.user?.username }}</span>
          <template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">退出登录</el-dropdown-item></el-dropdown-menu></template>
        </el-dropdown>
      </el-header>
      <el-main class="main"><router-view /></el-main>
    </el-container>
    <el-drawer v-model="mobileNavigationOpen" class="mobile-nav-drawer" direction="ltr" size="272px" :with-header="false">
      <div class="logo"><span>{{ appConfig.shortName }}</span><strong>{{ appConfig.nameEn }}</strong></div>
      <el-scrollbar class="nav-scroll">
        <el-menu router :default-active="$route.path" class="nav" @select="mobileNavigationOpen = false">
          <el-menu-item index="/dashboard"><el-icon><HomeFilled /></el-icon><span>工作台</span></el-menu-item>
          <MenuNode v-for="item in menuTree" :key="item.id" :item="item" />
        </el-menu>
      </el-scrollbar>
    </el-drawer>
  </el-container>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { HomeFilled, Menu } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { appConfig } from '../config'
import MenuNode from '../components/MenuNode.vue'
import { buildAccessibleNavigation } from '../utils/navigation'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const mobileNavigationOpen = ref(false)
const availablePaths = computed(() => new Set(router.getRoutes().map(item => item.path)))
const menuTree = computed(() => buildAccessibleNavigation(auth.user?.menus, availablePaths.value, permission => auth.hasPermission(permission)))
const title = computed(() => findNavigationItem(menuTree.value, route.path)?.name || (route.path === '/dashboard' ? '工作台' : appConfig.nameEn))

/** 从已裁剪的导航树中查找当前页面，避免显示无效菜单名称。 */
function findNavigationItem(items, path) {
  for (const item of items) {
    if (item.path === path) return item
    const child = findNavigationItem(item.children || [], path)
    if (child) return child
  }
  return null
}

/** 处理用户菜单命令。 */
async function handleCommand(command) {
  if (command === 'logout') { await auth.logout(); await router.replace('/login') }
}
</script>
