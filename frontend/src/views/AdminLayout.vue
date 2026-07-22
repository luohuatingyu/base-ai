<template>
  <el-container class="shell">
    <el-aside :width="sidebarCollapsed ? '64px' : '272px'" class="sidebar" :class="{ 'sidebar--collapsed': sidebarCollapsed }">
      <div class="sidebar-brand">
        <div class="logo">
          <span>{{ appConfig.shortName }}</span>
          <strong v-show="!sidebarCollapsed">{{ appConfig.nameEn }}</strong>
        </div>
        <p v-show="!sidebarCollapsed">{{ t('nav.operations') }}</p>
      </div>
      <div v-show="!sidebarCollapsed" class="nav-caption">{{ t('nav.title') }}</div>
      <el-scrollbar class="nav-scroll">
        <el-menu router :default-active="$route.path" class="nav" :collapse="sidebarCollapsed" :collapse-transition="false">
          <el-menu-item v-if="dashboardAvailable" index="/dashboard"><el-icon><HomeFilled /></el-icon><span>{{ t('nav.dashboard') }}</span></el-menu-item>
          <MenuNode v-for="item in menuTree" :key="item.id" :item="item" />
        </el-menu>
      </el-scrollbar>
      <div v-show="!sidebarCollapsed" class="sidebar-footer"><span>{{ t('nav.permissionEnabled') }}</span><small>{{ t('nav.permissionDesc') }}</small></div>
      <button class="collapse-trigger" @click="sidebarCollapsed = !sidebarCollapsed" :title="sidebarCollapsed ? t('nav.expand') : t('nav.collapse')">
        <el-icon><component :is="sidebarCollapsed ? Expand : Fold" /></el-icon>
      </button>
    </el-aside>
    <el-container class="body-container">
      <el-header class="topbar">
        <div class="topbar-title">
          <el-button class="nav-trigger" text @click="mobileNavigationOpen = true"><el-icon><Menu /></el-icon></el-button>
          <div><strong>{{ title }}</strong><small v-if="route.meta.desc">{{ route.meta.desc }}</small></div>
        </div>
        <div class="topbar-actions">
          <LanguageSwitcher />
          <el-dropdown @command="handleCommand">
            <span class="user-chip">{{ auth.user?.displayName || auth.user?.username }}</span>
            <template #dropdown><el-dropdown-menu><el-dropdown-item command="logout">{{ t('nav.logout') }}</el-dropdown-item></el-dropdown-menu></template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main"><router-view /></el-main>
    </el-container>
    <el-drawer v-model="mobileNavigationOpen" class="mobile-nav-drawer" direction="ltr" size="272px" :with-header="false">
      <div class="sidebar-brand">
        <div class="logo"><span>{{ appConfig.shortName }}</span><strong>{{ appConfig.nameEn }}</strong></div>
        <p>{{ t('nav.operations') }}</p>
      </div>
      <div class="nav-caption">{{ t('nav.title') }}</div>
      <el-scrollbar class="nav-scroll">
        <el-menu router :default-active="$route.path" class="nav" @select="mobileNavigationOpen = false">
          <el-menu-item v-if="dashboardAvailable" index="/dashboard"><el-icon><HomeFilled /></el-icon><span>{{ t('nav.dashboard') }}</span></el-menu-item>
          <MenuNode v-for="item in menuTree" :key="item.id" :item="item" />
        </el-menu>
      </el-scrollbar>
      <div class="sidebar-footer"><span>{{ t('nav.permissionEnabled') }}</span><small>{{ t('nav.permissionDesc') }}</small></div>
    </el-drawer>
  </el-container>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { HomeFilled, Menu, Fold, Expand } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import { appConfig } from '../config'
import MenuNode from '../components/MenuNode.vue'
import LanguageSwitcher from '../components/LanguageSwitcher.vue'
import { buildAccessibleNavigation, getNavigablePaths } from '../utils/navigation'

const { t } = useI18n()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const mobileNavigationOpen = ref(false)
const sidebarCollapsed = ref(false)
const navigablePaths = computed(() => getNavigablePaths(router.getRoutes()))
const dashboardAvailable = computed(() => navigablePaths.value.has('/dashboard'))
const menuTree = computed(() => buildAccessibleNavigation(auth.user?.menus, navigablePaths.value, permission => auth.hasPermission(permission)))
const title = computed(() => findNavigationItem(menuTree.value, route.path)?.name || (route.path === '/dashboard' ? t('nav.dashboard') : appConfig.nameEn))

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
