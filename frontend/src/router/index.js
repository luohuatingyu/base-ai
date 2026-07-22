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
        { path: 'dashboard', component: DashboardView, meta: { navigable: true, desc: '系统概览，快速了解各组件运行状态' } },
        { path: 'ai-chat', component: AiChatView, meta: { permission: 'ai:chat:invoke', navigable: true, desc: '通用 AI 对话，请求经权限和任务层转发至 Python Worker' } },
        { path: 'users', component: UsersView, meta: { permission: 'system:user:list', navigable: true, desc: '维护账号、部门、岗位和角色' } },
        { path: 'roles', component: RolesView, meta: { permission: 'system:role:list', navigable: true, desc: '配置菜单权限和数据可见范围' } },
        { path: 'menus', component: MenusView, meta: { permission: 'system:menu:list', navigable: true, desc: '维护目录、页面路由和按钮权限' } },
        { path: 'departments', component: DepartmentsView, meta: { permission: 'system:department:list', navigable: true, desc: '部门树用于用户归属和数据权限' } },
        { path: 'positions', component: PositionsView, meta: { permission: 'system:position:list', navigable: true, desc: '维护用户岗位信息' } },
        { path: 'dictionaries', component: DictionariesView, meta: { permission: 'system:dictionary:list', navigable: true, desc: '统一维护系统枚举与展示标签' } },
        { path: 'settings', component: SettingsView, meta: { permission: 'system:setting:list', navigable: true, desc: '敏感参数加密保存并以脱敏内容展示' } },
        { path: 'online-users', component: OnlineUsersView, meta: { permission: 'system:session:list', navigable: true, desc: '查看 Redis 会话并执行强制下线' } },
        { path: 'operation-logs', component: OperationLogsView, meta: { permission: 'system:audit:operation:list', navigable: true, desc: '系统写操作的脱敏审计记录' } },
        { path: 'login-logs', component: LoginLogsView, meta: { permission: 'system:audit:login:list', navigable: true, desc: '记录登录成功与失败事件' } },
        { path: 'model-providers', component: ModelProvidersView, meta: { permission: 'model:provider:list', navigable: true, desc: 'API Key 加密保存，列表仅显示脱敏值' } },
        { path: 'models', component: ModelsView, meta: { permission: 'model:model:list', navigable: true, desc: '维护供应商下的模型标识和能力等级' } },
        { path: 'model-routes', component: ModelRoutesView, meta: { permission: 'model:route:list', navigable: true, desc: '候选模型按顺序执行故障切换' } },
        { path: 'tasks', component: TasksView, meta: { permission: 'system:task:view', navigable: true, desc: 'AOP 统一跟踪控制器、定时任务和跨服务调用日志' } },
        { path: 'automation/api-triggers', component: ApiTriggerView, meta: { permission: 'automation:api-trigger:list', navigable: true, desc: '配置 HTTP 触发器，执行同步进入任务调度' } }
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
