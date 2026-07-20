<template>
  <el-sub-menu v-if="children.length" :index="String(item.id)">
    <template #title><span>{{ item.name }}</span></template>
    <MenuNode v-for="child in children" :key="child.id" :item="child" />
  </el-sub-menu>
  <el-menu-item v-else-if="item.type === 'MENU' && item.path" :index="item.path">{{ item.name }}</el-menu-item>
</template>

<script setup>
import { computed } from 'vue'

defineOptions({ name: 'MenuNode' })
const props = defineProps({ item: { type: Object, required: true } })
const children = computed(() => (props.item.children || []).filter(child => child.visible !== false && child.type !== 'BUTTON'))
</script>
