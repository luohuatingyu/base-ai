<template>
  <el-sub-menu v-if="children.length" :index="String(item.id)">
    <template #title>
      <el-icon><component :is="menuIcon" /></el-icon>
      <span>{{ menuName }}</span>
    </template>
    <MenuNode v-for="child in children" :key="child.id" :item="child" />
  </el-sub-menu>
  <el-menu-item v-else-if="item.type === 'MENU' && item.path" :index="item.path">
    <el-icon><component :is="menuIcon" /></el-icon>
    <span>{{ menuName }}</span>
  </el-menu-item>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  Avatar, Briefcase, ChatDotRound, Collection, Connection, Cpu, Document, Guide, Link, List,
  MagicStick, Menu as MenuIcon, OfficeBuilding, Operation, Promotion, Setting, Tickets, Tools, User
} from '@element-plus/icons-vue'

defineOptions({ name: 'MenuNode' })
const props = defineProps({ item: { type: Object, required: true } })
const { t } = useI18n()
const children = computed(() => props.item.children || [])
const iconMap = { Avatar, Briefcase, ChatDotRound, Collection, Connection, Cpu, Document, Guide, Link, List, MagicStick, Menu: MenuIcon, OfficeBuilding, Operation, Promotion, Setting, Tickets, Tools, User }

/** 优先展示菜单配置图标；配置缺失或错误时回退为通用菜单图标。 */
const menuIcon = computed(() => iconMap[props.item.icon] || MenuIcon)
const menuKeyMap = { '/ai': 'nav.items.ai', '/ai-chat': 'nav.items.aiChat', '/system': 'nav.items.system', '/users': 'nav.items.users', '/roles': 'nav.items.roles', '/menus': 'nav.items.menus', '/departments': 'nav.items.departments', '/positions': 'nav.items.positions', '/dictionaries': 'nav.items.dictionaries', '/settings': 'nav.items.settings', '/online-users': 'nav.items.onlineUsers', '/operation-logs': 'nav.items.operationLogs', '/login-logs': 'nav.items.loginLogs', '/tasks': 'nav.items.tasks', '/models': 'nav.items.models', '/model-providers': 'nav.items.providers', '/model-routes': 'nav.items.routes', '/automation': 'nav.items.automation', '/automation/api-triggers': 'nav.items.apiTriggers' }
const menuName = computed(() => menuKeyMap[props.item.path] ? t(menuKeyMap[props.item.path]) : props.item.name)
</script>
