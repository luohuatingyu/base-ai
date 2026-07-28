<!-- 登录页面：收集账号密码并建立平台会话。 -->
<template>
  <div class="login-page">
    <div class="login-page-actions">
      <router-link class="login-open-platform-link" to="/open-platform">
        <el-icon><Promotion /></el-icon>
        <span>{{ t('login.openPlatform') }}</span>
      </router-link>
      <LanguageSwitcher />
    </div>
    <el-card class="login-card">
      <div class="brand-mark">{{ appConfig.shortName }}</div>
      <h1>{{ getLocalizedPlatformName(locale.value) }}</h1>
      <p>{{ t('login.description') }}</p>
      <el-form @submit.prevent="submit">
        <el-form-item><el-input v-model="form.username" size="large" :placeholder="t('login.username')" /></el-form-item>
        <el-form-item><el-input v-model="form.password" size="large" type="password" show-password :placeholder="t('login.password')" /></el-form-item>
        <el-button class="full" size="large" type="primary" :loading="loading" @click="submit">{{ t('login.submit') }}</el-button>
      </el-form>
    </el-card>
  </div>
</template>

<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Promotion } from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { appConfig, getLocalizedPlatformName } from '../config'
import { useI18n } from 'vue-i18n'
import LanguageSwitcher from '../components/LanguageSwitcher.vue'

const { locale, t } = useI18n()
const form = reactive({ username: '', password: '' })
const loading = ref(false)
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

/** 提交登录并跳转原目标页面。 */
async function submit() {
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    await router.replace(route.query.redirect || '/dashboard')
  } catch (error) {
    ElMessage.error(error.response?.data?.message || t('login.loginFailed'))
  } finally { loading.value = false }
}
</script>

<style scoped>
.login-page {
  position: relative;
  padding-top: 88px;
}

.login-page-actions {
  position: absolute;
  top: 24px;
  right: 24px;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 5px;
  border: 1px solid rgb(214 223 237 / 85%);
  border-radius: 14px;
  background: rgb(255 255 255 / 78%);
  box-shadow: 0 10px 28px rgb(32 59 105 / 9%);
  backdrop-filter: blur(12px);
}

.login-open-platform-link {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  height: 36px;
  padding: 0 12px;
  border-radius: 10px;
  color: var(--app-muted);
  font-size: 14px;
  font-weight: 600;
  text-decoration: none;
  transition: color 0.2s ease, background-color 0.2s ease;
}

.login-open-platform-link:hover,
.login-open-platform-link:focus-visible {
  color: var(--app-primary-dark);
  background: var(--el-color-primary-light-9);
  outline: none;
}

@media (max-width: 640px) {
  .login-page {
    padding: 76px 16px 24px;
  }

  .login-page-actions {
    top: 16px;
    right: 16px;
  }
}
</style>
