import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import AdminLayout from '../views/AdminLayout.vue'
import LoginView from '../views/LoginView.vue'
import DashboardView from '../views/DashboardView.vue'
import AiChatView from '../views/AiChatView.vue'
import UsersView from '../views/UsersView.vue'
import RolesView from '../views/RolesView.vue'
import MenusView from '../views/MenusView.vue'
import TasksView from '../views/TasksView.vue'
import ApiTriggerView from '../views/ApiTriggerView.vue'
import DepartmentsView from '../views/DepartmentsView.vue'
import PositionsView from '../views/PositionsView.vue'
import DictionariesView from '../views/DictionariesView.vue'
import SettingsView from '../views/SettingsView.vue'
import OnlineUsersView from '../views/OnlineUsersView.vue'
import OperationLogsView from '../views/OperationLogsView.vue'
import LoginLogsView from '../views/LoginLogsView.vue'
import ModelProvidersView from '../views/ModelProvidersView.vue'
import ModelsView from '../views/ModelsView.vue'
import ModelRoutesView from '../views/ModelRoutesView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    {
      path: '/', component: AdminLayout, redirect: '/dashboard', children: [
        { path: 'dashboard', component: DashboardView, meta: { navigable: true } },
        { path: 'ai-chat', component: AiChatView, meta: { permission: 'ai:chat:invoke', navigable: true } },
        { path: 'users', component: UsersView, meta: { permission: 'system:user:list', navigable: true } },
        { path: 'roles', component: RolesView, meta: { permission: 'system:role:list', navigable: true } },
        { path: 'menus', component: MenusView, meta: { permission: 'system:menu:list', navigable: true } },
        { path: 'departments', component: DepartmentsView, meta: { permission: 'system:department:list', navigable: true } },
        { path: 'positions', component: PositionsView, meta: { permission: 'system:position:list', navigable: true } },
        { path: 'dictionaries', component: DictionariesView, meta: { permission: 'system:dictionary:list', navigable: true } },
        { path: 'settings', component: SettingsView, meta: { permission: 'system:setting:list', navigable: true } },
        { path: 'online-users', component: OnlineUsersView, meta: { permission: 'system:session:list', navigable: true } },
        { path: 'operation-logs', component: OperationLogsView, meta: { permission: 'system:audit:operation:list', navigable: true } },
        { path: 'login-logs', component: LoginLogsView, meta: { permission: 'system:audit:login:list', navigable: true } },
        { path: 'model-providers', component: ModelProvidersView, meta: { permission: 'model:provider:list', navigable: true } },
        { path: 'models', component: ModelsView, meta: { permission: 'model:model:list', navigable: true } },
        { path: 'model-routes', component: ModelRoutesView, meta: { permission: 'model:route:list', navigable: true } },
        { path: 'tasks', component: TasksView, meta: { permission: 'system:task:view', navigable: true } },
        { path: 'automation/api-triggers', component: ApiTriggerView, meta: { permission: 'automation:api-trigger:list', navigable: true } }
      ]
    }
  ]
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (to.meta.public) return auth.isLoggedIn ? '/dashboard' : true
  if (!auth.isLoggedIn) return `/login?redirect=${encodeURIComponent(to.fullPath)}`
  if (!auth.user) {
    try { await auth.fetchMe() } catch { return '/login' }
  }
  return auth.hasPermission(to.meta.permission) ? true : '/dashboard'
})

export default router
