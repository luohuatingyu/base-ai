import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import './styles.css'
import './automation.css'
import App from './App.vue'
import { appConfig } from './config'
import router from './router'
import i18n from './locales'

document.title = appConfig.nameEn
createApp(App)
  .use(createPinia())
  .use(router)
  .use(ElementPlus)
  .use(i18n)
  .mount('#app')
