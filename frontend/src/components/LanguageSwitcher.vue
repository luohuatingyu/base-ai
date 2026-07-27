<template>
  <el-dropdown @command="handleCommand" trigger="click">
    <el-button circle class="language-switcher">
      <el-icon><Operation /></el-icon>
    </el-button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item v-for="option in locales" :key="option.code" :command="option.code"
                          :class="{ 'is-active': currentLocale === option.code }">
          <span class="flag">{{ option.flag }}</span> {{ t(option.labelKey) }}
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { Operation } from '@element-plus/icons-vue'
import { LOCALES } from '../locales/registry'

const { locale, t } = useI18n()

// 语言选项来源于注册表，新增语言自动出现在下拉列表
const locales = LOCALES

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
