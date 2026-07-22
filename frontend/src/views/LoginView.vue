<!-- 登录页面：收集账号密码并建立平台会话。 -->
<template>
  <div class="login-page">
    <el-card class="login-card">
      <div class="brand-mark">{{ appConfig.shortName }}</div>
      <h1>{{ appConfig.nameEn }}</h1>
      <p>{{ appConfig.nameZh }} · {{ t('login.description') }}</p>
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
import { useAuthStore } from '../stores/auth'
import { appConfig } from '../config'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
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
