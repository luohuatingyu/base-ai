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

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    {
      path: '/', component: AdminLayout, redirect: '/dashboard', children: [
        { path: 'dashboard', component: DashboardView },
        { path: 'ai-chat', component: AiChatView, meta: { permission: 'ai:chat:invoke' } },
        { path: 'users', component: UsersView, meta: { permission: 'system:user:manage' } },
        { path: 'roles', component: RolesView, meta: { permission: 'system:role:manage' } },
        { path: 'menus', component: MenusView, meta: { permission: 'system:menu:manage' } },
        { path: 'tasks', component: TasksView, meta: { permission: 'system:task:view' } }
        ,{ path: 'automation/api-triggers', component: ApiTriggerView, meta: { permission: 'automation:api-trigger:list' } }
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
