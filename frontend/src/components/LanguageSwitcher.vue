<template>
  <el-dropdown @command="handleCommand" trigger="click">
    <el-button circle class="language-switcher">
      <el-icon><Operation /></el-icon>
    </el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item command="zh-CN" :class="{ 'is-active': currentLocale === 'zh-CN' }">
          <span class="flag">🇨🇳</span> {{ t('language.zhCN') }}
        </el-dropdown-item>
        <el-dropdown-item command="en-US" :class="{ 'is-active': currentLocale === 'en-US' }">
          <span class="flag">🇺🇸</span> {{ t('language.enUS') }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Operation } from '@element-plus/icons-vue'

const { locale, t } = useI18n()

const currentLocale = computed(() => locale.value)

function handleCommand(command) {
  locale.value = command
  localStorage.setItem('locale', command)
  // 可选：刷新页面以确保所有组件都更新
  // location.reload()
}
</script>

<style scoped>
.language-switcher {
  border: none;
  background: transparent;
}

.language-switcher:hover {
  background: rgba(0, 0, 0, 0.05);
}

.flag {
  font-size: 16px;
  margin-right: 6px;
}

.is-active {
  color: var(--el-color-primary);
  font-weight: 600;
}
</style>
