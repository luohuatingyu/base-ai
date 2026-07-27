<template>
  <el-container class="shell">
    <el-aside :width="sidebarCollapsed ? '64px' : expandedSidebarWidth" class="sidebar" :class="{ 'sidebar--collapsed': sidebarCollapsed }">
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
          <div><strong>{{ title }}</strong><small v-if="route.meta.desc">{{ t(route.meta.desc) }}</small></div>
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
    <el-drawer v-model="mobileNavigationOpen" class="mobile-nav-drawer" direction="ltr" :size="expandedSidebarWidth" :with-header="false">
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
import { computed, ref, watchEffect } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { HomeFilled, Menu, Fold, Expand } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { useAuthStore } from '../stores/auth'
import { appConfig } from '../config'
import MenuNode from '../components/MenuNode.vue'
import LanguageSwitcher from '../components/LanguageSwitcher.vue'
import { findLocale } from '../locales/registry'
import { buildAccessibleNavigation, findNavigationItem, getNavigablePaths, localizeMenuName } from '../utils/navigation'

const { locale, t } = useI18n()
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const mobileNavigationOpen = ref(false)
const sidebarCollapsed = ref(false)
// 侧栏展开宽度由语言注册表提供，长名称语言可配置更宽空间，新增语言无需改此处
const expandedSidebarWidth = computed(() => findLocale(locale.value).sidebarExpandedWidth)

// 将侧栏宽度写入根节点 CSS 变量，供 Teleport 渲染的移动端抽屉在窄视口下按语言收缩
watchEffect(() => {
  document.documentElement.style.setProperty('--sidebar-expanded-width', expandedSidebarWidth.value)
})
const navigablePaths = computed(() => getNavigablePaths(router.getRoutes()))
const dashboardAvailable = computed(() => navigablePaths.value.has('/dashboard'))
const menuTree = computed(() => buildAccessibleNavigation(auth.user?.menus, navigablePaths.value, permission => auth.hasPermission(permission)))
const title = computed(() => {
  if (route.path === '/dashboard') return t('nav.dashboard')
  const item = findNavigationItem(menuTree.value, route.path)
  return localizeMenuName(item || { path: route.path, name: appConfig.nameEn }, t)
})

/** 处理用户菜单命令。 */
async function handleCommand(command) {
  if (command === 'logout') { await auth.logout(); await router.replace('/login') }
}
</script>
