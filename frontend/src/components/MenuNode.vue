<template>
  <el-sub-menu v-if="children.length" :index="String(item.id)">
    <template #title>
      <el-icon><component :is="menuIcon" /></el-icon>
      <span>{{ item.name }}</span>
    </template>
    <MenuNode v-for="child in children" :key="child.id" :item="child" />
  </el-sub-menu>
  <el-menu-item v-else-if="item.type === 'MENU' && item.path" :index="item.path">
    <el-icon><component :is="menuIcon" /></el-icon>
    <span>{{ item.name }}</span>
  </el-menu-item>
</template>

<script setup>
import { computed } from 'vue'
import * as ElementPlusIcons from '@element-plus/icons-vue'

defineOptions({ name: 'MenuNode' })
const props = defineProps({ item: { type: Object, required: true } })
const children = computed(() => props.item.children || [])

/** 优先展示菜单配置图标；配置缺失或错误时回退为通用菜单图标。 */
const menuIcon = computed(() => ElementPlusIcons[props.item.icon] || ElementPlusIcons.Menu)
</script>
