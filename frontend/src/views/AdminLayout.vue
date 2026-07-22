<template>
  <el-container class="shell">
    <el-aside width="272px" class="sidebar">
      <div class="sidebar-brand">
        <div class="logo"><span>{{ appConfig.shortName }}</span><strong>{{ appConfig.nameEn }}</strong></div>
        <p>INTELLIGENT OPERATIONS</p>
      </div>
      <div class="nav-caption">导航菜单</div>
      <el-scrollbar class="nav-scroll">
        <el-menu router :default-active="$route.path" class="nav">
          <el-menu-item v-if="dashboardAvailable" index="/dashboard"><el-icon><HomeFilled /></el-icon><span>工作台</span></el-menu-item>
          <MenuNode v-for="item in menuTree" :key="item.id" :item="item" />
        </el-menu>
      </el-scrollbar>
      <div class="sidebar-footer"><span>权限导航已启用</span><small>仅展示可访问页面</small></div>
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
      <div class="sidebar-brand">
        <div class="logo"><span>{{ appConfig.shortName }}</span><strong>{{ appConfig.nameEn }}</strong></div>
        <p>INTELLIGENT OPERATIONS</p>
      </div>
      <div class="nav-caption">导航菜单</div>
      <el-scrollbar class="nav-scroll">
        <el-menu router :default-active="$route.path" class="nav" @select="mobileNavigationOpen = false">
          <el-menu-item v-if="dashboardAvailable" index="/dashboard"><el-icon><HomeFilled /></el-icon><span>工作台</span></el-menu-item>
          <MenuNode v-for="item in menuTree" :key="item.id" :item="item" />
        </el-menu>
      </el-scrollbar>
      <div class="sidebar-footer"><span>权限导航已启用</span><small>仅展示可访问页面</small></div>
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
const navigablePaths = computed(() => new Set(router.getRoutes()
  .filter(item => item.meta.navigable === true && item.components?.default)
  .map(item => item.path)))
const dashboardAvailable = computed(() => navigablePaths.value.has('/dashboard'))
const menuTree = computed(() => buildAccessibleNavigation(auth.user?.menus, navigablePaths.value, permission => auth.hasPermission(permission)))
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
