<template>
  <el-dropdown
    class="sidebar-theme-switcher"
    :class="{ 'sidebar-theme-switcher--collapsed': collapsed }"
    trigger="click"
    placement="top-start"
    popper-class="sidebar-theme-popper"
    @command="handleCommand"
  >
    <el-tooltip
      :disabled="!collapsed"
      :content="t('nav.theme')"
      effect="dark"
      placement="right"
      :enterable="true"
      popper-class="copyable-tooltip"
    >
      <button
        type="button"
        class="sidebar-theme-trigger"
        :class="{ 'sidebar-theme-trigger--collapsed': collapsed }"
        :aria-label="t('nav.theme')"
      >
        <span class="sidebar-theme-preview" :class="`sidebar-theme-preview--${currentTheme.id}`" aria-hidden="true"><i /></span>
        <span v-if="!collapsed" class="sidebar-theme-copy">
          <small>{{ t('nav.theme') }}</small>
          <strong>{{ t(currentTheme.labelKey) }}</strong>
        </span>
        <el-icon v-if="!collapsed" class="sidebar-theme-arrow"><ArrowUp /></el-icon>
      </button>
    </el-tooltip>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item
          v-for="theme in SIDEBAR_THEMES"
          :key="theme.id"
          :command="theme.id"
          class="sidebar-theme-option"
          :class="{ 'is-active': theme.id === currentTheme.id }"
        >
          <span class="sidebar-theme-preview" :class="`sidebar-theme-preview--${theme.id}`" aria-hidden="true"><i /></span>
          <span>{{ t(theme.labelKey) }}</span>
          <el-icon v-if="theme.id === currentTheme.id"><Check /></el-icon>
        </el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup>
import { computed } from 'vue'
import { ArrowUp, Check } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { SIDEBAR_THEMES, resolveSidebarTheme } from '../utils/sidebarTheme'

const props = defineProps({
  modelValue: {
    type: String,
    required: true
  },
  collapsed: {
    type: Boolean,
    default: false
  }
})
const emit = defineEmits(['update:modelValue'])
const { t } = useI18n()
const currentTheme = computed(() => {
  const themeId = resolveSidebarTheme(props.modelValue)
  return SIDEBAR_THEMES.find(theme => theme.id === themeId)
})

/** 将用户选择的有效主题同步给布局组件。 */
function handleCommand(theme) {
  emit('update:modelValue', resolveSidebarTheme(theme))
}
</script>

<style scoped>
.sidebar-theme-switcher {
  position: relative;
  z-index: 2;
  display: block;
  width: auto;
  margin: 10px 4px 0;
}

.sidebar-theme-switcher--collapsed {
  width: 100%;
  margin: 8px 0 0;
}

.sidebar-theme-trigger {
  display: flex;
  align-items: center;
  width: 100%;
  height: 48px;
  gap: 10px;
  padding: 0 12px;
  border: 1px solid var(--sidebar-control-border, rgb(169 195 247 / 18%));
  border-radius: 12px;
  color: var(--sidebar-control-text, #c9d9f6);
  background: var(--sidebar-control-background, rgb(255 255 255 / 6%));
  cursor: pointer;
  text-align: left;
  transition: border-color 0.2s ease, background-color 0.2s ease, transform 0.2s ease;
}

.sidebar-theme-trigger:hover,
.sidebar-theme-trigger:focus-visible {
  border-color: var(--sidebar-control-hover-border, rgb(169 195 247 / 36%));
  background: var(--sidebar-control-hover-background, rgb(255 255 255 / 12%));
  outline: none;
  transform: translateY(-1px);
}

.sidebar-theme-trigger--collapsed {
  height: 40px;
  padding: 0;
  justify-content: center;
  border-radius: 10px;
}

.sidebar-theme-copy {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
  gap: 2px;
}

.sidebar-theme-copy small {
  color: var(--sidebar-control-muted, #8098c5);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.8px;
}

.sidebar-theme-copy strong {
  overflow: hidden;
  font-size: 12px;
  line-height: 1.3;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-theme-arrow {
  flex: 0 0 auto;
  color: var(--sidebar-control-muted, #8098c5);
  font-size: 13px;
}
</style>
