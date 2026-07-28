<template>
  <el-config-provider :locale="elementLocale" :table="tableConfig">
    <router-view />
  </el-config-provider>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElConfigProvider } from 'element-plus'
import { findLocale } from './locales/registry'

const { locale } = useI18n()

// 按当前语言从注册表解析 Element Plus 语言包，新增语言无需改动此处
const elementLocale = computed(() => findLocale(locale.value).element)

// 全局表格仅在内容溢出时展示深色提示，并允许用户进入提示内容选中复制
const tableConfig = Object.freeze({
  showOverflowTooltip: true,
  tooltipEffect: 'dark',
  tooltipOptions: {
    enterable: true,
    popperClass: 'copyable-tooltip'
  }
})
</script>
