<template>
  <div
    class="sidebar-theme-switcher"
    :class="{ 'sidebar-theme-switcher--collapsed': collapsed }"
  >
    <template v-if="collapsed">
      <el-tooltip :content="currentThemeLabel" effect="dark" placement="right" :enterable="true" popper-class="copyable-tooltip">
        <button type="button" class="sidebar-theme-cycle" :aria-label="currentThemeLabel" @click="selectNextTheme">
          <span class="sidebar-theme-dots" aria-hidden="true">
            <i
              v-for="theme in SIDEBAR_THEMES"
              :key="theme.id"
              :class="[`sidebar-theme-dot--${theme.id}`, { 'is-active': theme.id === currentTheme.id }]"
            />
          </span>
        </button>
      </el-tooltip>
    </template>
    <template v-else>
      <span class="sidebar-theme-label">{{ t('nav.theme') }}</span>
      <div class="sidebar-theme-options" role="radiogroup" :aria-label="t('nav.theme')">
        <el-tooltip
          v-for="theme in SIDEBAR_THEMES"
          :key="theme.id"
          :content="t(theme.labelKey)"
          effect="dark"
          placement="top"
          :enterable="true"
          popper-class="copyable-tooltip"
        >
          <button
            type="button"
            role="radio"
            :aria-checked="theme.id === currentTheme.id"
            :aria-label="t(theme.labelKey)"
            class="sidebar-theme-option"
            :class="{ 'is-active': theme.id === currentTheme.id }"
            @click="selectTheme(theme.id)"
          >
            <span class="sidebar-theme-preview" :class="`sidebar-theme-preview--${theme.id}`" aria-hidden="true"><i /></span>
            <el-icon v-if="theme.id === currentTheme.id" class="sidebar-theme-check"><Check /></el-icon>
          </button>
        </el-tooltip>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Check } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { SIDEBAR_THEMES, findNextSidebarTheme, resolveSidebarTheme } from '../utils/sidebarTheme'

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
const currentThemeLabel = computed(() => `${t('nav.theme')} · ${t(currentTheme.value.labelKey)}`)

/** 将用户选择的有效主题同步给布局组件。 */
function selectTheme(theme) {
  emit('update:modelValue', resolveSidebarTheme(theme))
}

/** 折叠态按注册顺序循环切换主题，避免引入额外浮层。 */
function selectNextTheme() {
  emit('update:modelValue', findNextSidebarTheme(props.modelValue))
}
</script>

<style scoped>
.sidebar-theme-switcher {
  position: relative;
  z-index: 2;
  display: flex;
  height: 44px;
  align-items: center;
  gap: 8px;
  margin: 10px 4px 0;
  padding: 5px 6px 5px 11px;
  border: 1px solid var(--sidebar-control-border, rgb(169 195 247 / 18%));
  border-radius: 12px;
  background: var(--sidebar-control-background, rgb(255 255 255 / 6%));
}

.sidebar-theme-switcher--collapsed {
  width: 100%;
  height: 40px;
  margin: 8px 0 0;
  padding: 0;
  justify-content: center;
  border-radius: 10px;
}

.sidebar-theme-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--sidebar-control-muted, #8098c5);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.6px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sidebar-theme-options {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 3px;
}

.sidebar-theme-option {
  position: relative;
  display: grid;
  width: 32px;
  height: 32px;
  padding: 3px;
  border: 1px solid transparent;
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  place-items: center;
  transition: border-color 0.2s ease, background-color 0.2s ease, transform 0.2s ease;
}

.sidebar-theme-option:hover,
.sidebar-theme-option:focus-visible,
.sidebar-theme-option.is-active {
  border-color: var(--sidebar-control-hover-border, rgb(169 195 247 / 36%));
  background: var(--sidebar-control-hover-background, rgb(255 255 255 / 12%));
  outline: none;
}

.sidebar-theme-option:hover { transform: translateY(-1px); }

.sidebar-theme-preview {
  position: relative;
  display: inline-flex;
  width: 24px;
  height: 24px;
  align-items: flex-end;
  padding: 4px;
  overflow: hidden;
  border: 1px solid rgb(255 255 255 / 30%);
  border-radius: 7px;
  box-shadow: 0 3px 8px rgb(24 36 61 / 16%);
}

.sidebar-theme-preview i { width: 10px; height: 2px; border-radius: 2px; background: rgb(255 255 255 / 82%); box-shadow: 0 -5px 0 rgb(255 255 255 / 44%); }
.sidebar-theme-preview--midnight { background: linear-gradient(145deg, #0d172a, #315b96); }
.sidebar-theme-preview--cloud { border-color: #cfd9e8; background: linear-gradient(145deg, #fff, #dce6f5); }
.sidebar-theme-preview--cloud i { background: #356ae6; box-shadow: 0 -5px 0 rgb(53 106 230 / 34%); }
.sidebar-theme-preview--aurora { background: linear-gradient(145deg, #7c3aed, #18b9cf); }

.sidebar-theme-check {
  position: absolute;
  top: -2px;
  right: -2px;
  width: 14px;
  height: 14px;
  border: 2px solid var(--sidebar-control-background, #15294a);
  border-radius: 50%;
  background: var(--sidebar-active-background, #356ae6);
  color: #fff;
  font-size: 9px;
}

.sidebar-theme-cycle {
  display: grid;
  width: 100%;
  height: 100%;
  padding: 0;
  border: 0;
  border-radius: 9px;
  background: transparent;
  cursor: pointer;
  place-items: center;
  transition: background-color 0.2s ease, transform 0.2s ease;
}

.sidebar-theme-cycle:hover,
.sidebar-theme-cycle:focus-visible {
  background: var(--sidebar-control-hover-background, rgb(255 255 255 / 12%));
  outline: none;
  transform: translateY(-1px);
}

.sidebar-theme-dots {
  display: flex;
  align-items: center;
  gap: 3px;
}

.sidebar-theme-dots i {
  width: 6px;
  height: 6px;
  border: 1px solid rgb(255 255 255 / 36%);
  border-radius: 50%;
  opacity: 0.58;
  transition: width 0.2s ease, opacity 0.2s ease, box-shadow 0.2s ease;
}

.sidebar-theme-dots i.is-active {
  width: 12px;
  border-radius: 5px;
  opacity: 1;
  box-shadow: 0 0 0 2px rgb(255 255 255 / 12%);
}

.sidebar-theme-dot--midnight { background: #315b96; }
.sidebar-theme-dots .sidebar-theme-dot--cloud { border-color: #b8c6db; background: #f1f5fb; }
.sidebar-theme-dot--aurora { background: linear-gradient(135deg, #8b5cf6, #22c4d6); }
</style>
