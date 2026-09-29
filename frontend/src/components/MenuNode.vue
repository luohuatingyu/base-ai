<template>
  <el-sub-menu v-if="children.length" :index="String(item.id)">
    <template #title>
      <el-icon><component :is="menuIcon" /></el-icon>
      <el-tooltip :content="menuName" effect="dark" placement="right" :enterable="true" popper-class="copyable-tooltip">
        <span>{{ menuName }}</span>
      </el-tooltip>
    </template>
    <MenuNode v-for="child in children" :key="child.id" :item="child" />
  </el-sub-menu>
  <el-menu-item v-else-if="item.type === 'MENU' && item.path" :index="item.path">
    <el-icon><component :is="menuIcon" /></el-icon>
    <el-tooltip :content="menuName" effect="dark" placement="right" :enterable="true" popper-class="copyable-tooltip">
      <span>{{ menuName }}</span>
    </el-tooltip>
  </el-menu-item>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import {
  Aim, Avatar, Briefcase, Brush, ChatDotRound, Collection, Connection, Cpu, DataAnalysis, Document,
  DocumentChecked, EditPen, Files, Film, FolderOpened, Goods, Guide, Headset, House, Link, List,
  MagicStick, Menu as MenuIcon, Microphone, OfficeBuilding, Operation, Picture, Promotion, Setting,
  Suitcase, Tickets, Tools, User, VideoCamera, VideoPlay
} from '@element-plus/icons-vue'
import { localizeMenuName } from '../utils/navigation'

defineOptions({ name: 'MenuNode' })
const props = defineProps({ item: { type: Object, required: true } })
const { t } = useI18n()
const children = computed(() => props.item.children || [])
const iconMap = { Aim, Avatar, Briefcase, Brush, ChatDotRound, Collection, Connection, Cpu, DataAnalysis, Document, DocumentChecked, EditPen, Files, Film, FolderOpened, Goods, Guide, Headset, House, Link, List, MagicStick, Menu: MenuIcon, Microphone, OfficeBuilding, Operation, Picture, Promotion, Setting, Suitcase, Tickets, Tools, User, VideoCamera, VideoPlay }

/** 优先展示菜单配置图标；配置缺失或错误时回退为通用菜单图标。 */
const menuIcon = computed(() => iconMap[props.item.icon] || MenuIcon)
const menuName = computed(() => localizeMenuName(props.item, t))
</script>
