import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import AdminLayout from '../views/AdminLayout.vue'
import LoginView from '../views/LoginView.vue'
import DashboardView from '../views/DashboardView.vue'
import AiChatView from '../views/AiChatView.vue'
import AiGenerationView from '../views/AiGenerationView.vue'
import AiVideoView from '../views/AiVideoView.vue'
import UsersView from '../views/UsersView.vue'
import RolesView from '../views/RolesView.vue'
import MenusView from '../views/MenusView.vue'
import TasksView from '../views/TasksView.vue'
import ApiTriggerView from '../views/ApiTriggerView.vue'
import ApiTriggerSecurityView from '../views/ApiTriggerSecurityView.vue'
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
import ApiKeysView from '../views/ApiKeysView.vue'
import OpenPlatformView from '../views/OpenPlatformView.vue'
import MailAccountsView from '../views/MailAccountsView.vue'
import MailRoutesView from '../views/MailRoutesView.vue'
import WorkflowNodesView from '../views/WorkflowNodesView.vue'
import WorkflowCanvasView from '../views/WorkflowCanvasView.vue'
import DataSourcesView from '../views/DataSourcesView.vue'
import KnowledgeBasesView from '../views/KnowledgeBasesView.vue'
import RagRetrievalDebugView from '../views/RagRetrievalDebugView.vue'
import WorkflowNodeDocsView from '../views/WorkflowNodeDocsView.vue'
import DataSyncView from '../views/DataSyncView.vue'
import ServersView from '../views/ServersView.vue'
import DeviceAgentsView from '../views/DeviceAgentsView.vue'
import DeviceAgentGuideView from '../views/DeviceAgentGuideView.vue'
import DeviceAgentOnboardingView from '../views/DeviceAgentOnboardingView.vue'
import PackageAdjustmentView from '../views/PackageAdjustmentView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { guestOnly: true } },
    { path: '/open-platform', component: OpenPlatformView, meta: { public: true } },
    {
      path: '/', component: AdminLayout, redirect: '/dashboard', children: [
        { path: 'dashboard', component: DashboardView, meta: { navigable: true, desc: 'dashboard.description' } },
        { path: 'ai-video/dashboard', component: AiVideoView, meta: { permission: 'ai:video:dashboard:list', navigable: true, desc: 'aiVideo.sections.dashboard.description' } },
        { path: 'ai-video/projects', component: AiVideoView, meta: { permission: 'ai:video:project:list', navigable: true, desc: 'aiVideo.sections.projects.description' } },
        { path: 'ai-video/scripts', component: AiVideoView, meta: { permission: 'ai:video:script:list', navigable: true, desc: 'aiVideo.sections.scripts.description' } },
        { path: 'ai-video/storyboards', component: AiVideoView, meta: { permission: 'ai:video:storyboard:list', navigable: true, desc: 'aiVideo.sections.storyboards.description' } },
        { path: 'ai-video/assets/characters', component: AiVideoView, meta: { permission: 'ai:video:character:list', navigable: true, desc: 'aiVideo.sections.characters.description' } },
        { path: 'ai-video/assets/scenes', component: AiVideoView, meta: { permission: 'ai:video:scene:list', navigable: true, desc: 'aiVideo.sections.scenes.description' } },
        { path: 'ai-video/assets/items', component: AiVideoView, meta: { permission: 'ai:video:item:list', navigable: true, desc: 'aiVideo.sections.items.description' } },
        { path: 'ai-video/assets/costumes', component: AiVideoView, meta: { permission: 'ai:video:costume:list', navigable: true, desc: 'aiVideo.sections.costumes.description' } },
        { path: 'ai-video/assets/motions', component: AiVideoView, meta: { permission: 'ai:video:motion:list', navigable: true, desc: 'aiVideo.sections.motions.description' } },
        { path: 'ai-video/assets/styles', component: AiVideoView, meta: { permission: 'ai:video:style:list', navigable: true, desc: 'aiVideo.sections.styles.description' } },
        { path: 'ai-video/assets/voices', component: AiVideoView, meta: { permission: 'ai:video:voice:list', navigable: true, desc: 'aiVideo.sections.voices.description' } },
        { path: 'ai-video/assets/sounds', component: AiVideoView, meta: { permission: 'ai:video:sound:list', navigable: true, desc: 'aiVideo.sections.sounds.description' } },
        { path: 'ai-video/tasks', component: AiVideoView, meta: { permission: 'ai:video:task:list', navigable: true, desc: 'aiVideo.sections.tasks.description' } },
        { path: 'ai-video/renders', component: AiVideoView, meta: { permission: 'ai:video:render:list', navigable: true, desc: 'aiVideo.sections.renders.description' } },
        { path: 'ai-video/videos', component: AiVideoView, meta: { permission: 'ai:video:video:list', navigable: true, desc: 'aiVideo.sections.videos.description' } },
        { path: 'ai-video/reviews', component: AiVideoView, meta: { permission: 'ai:video:review:list', navigable: true, desc: 'aiVideo.sections.reviews.description' } },
        { path: 'ai-video/publishes', component: AiVideoView, meta: { permission: 'ai:video:publish:list', navigable: true, desc: 'aiVideo.sections.publishes.description' } },
        { path: 'ai-video/analytics', component: AiVideoView, meta: { permission: 'ai:video:analytics:list', navigable: true, desc: 'aiVideo.sections.analytics.description' } },
        { path: 'ai-chat', component: AiChatView, meta: { permission: 'ai:chat:invoke', navigable: true, desc: 'chat.description' } },
        { path: 'ai-generation', component: AiGenerationView, meta: { permission: 'ai:generation:invoke', navigable: true, desc: 'generation.subtitle' } },
        { path: 'package-adjustment', component: PackageAdjustmentView, meta: { permission: 'ai:package-adjustment:list', navigable: true, desc: 'packageAdjustment.description' } },
        { path: 'users', component: UsersView, meta: { permission: 'system:user:list', navigable: true, desc: 'users.description' } },
        { path: 'roles', component: RolesView, meta: { permission: 'system:role:list', navigable: true, desc: 'roles.description' } },
        { path: 'menus', component: MenusView, meta: { permission: 'system:menu:list', navigable: true, desc: 'menus.description' } },
        { path: 'departments', component: DepartmentsView, meta: { permission: 'system:department:list', navigable: true, desc: 'departments.description' } },
        { path: 'positions', component: PositionsView, meta: { permission: 'system:position:list', navigable: true, desc: 'positions.description' } },
        { path: 'dictionaries', component: DictionariesView, meta: { permission: 'system:dictionary:list', navigable: true, desc: 'dictionaries.description' } },
        { path: 'settings', component: SettingsView, meta: { permission: 'system:setting:list', navigable: true, desc: 'settings.description' } },
        { path: 'online-users', component: OnlineUsersView, meta: { permission: 'operations:session:list', navigable: true, desc: 'logs.onlineDescription' } },
        { path: 'operation-logs', component: OperationLogsView, meta: { permission: 'operations:audit:operation:list', navigable: true, desc: 'logs.operationDescription' } },
        { path: 'login-logs', component: LoginLogsView, meta: { permission: 'operations:audit:login:list', navigable: true, desc: 'logs.loginDescription' } },
        { path: 'mail/accounts', component: MailAccountsView, meta: { permission: 'system:mail:account:list', navigable: true, desc: 'mailAccounts.description' } },
        { path: 'mail/routes', component: MailRoutesView, meta: { permission: 'system:mail:route:list', navigable: true, desc: 'mailRoutes.description' } },
        { path: 'model-providers', component: ModelProvidersView, meta: { permission: 'ai:model:provider:list', navigable: true, desc: 'providers.description' } },
        { path: 'models', component: ModelsView, meta: { permission: 'ai:model:model:list', navigable: true, desc: 'models.description' } },
        { path: 'model-routes', component: ModelRoutesView, meta: { permission: 'ai:model:route:list', navigable: true, desc: 'routes.description' } },
        { path: 'knowledge-bases', component: KnowledgeBasesView, meta: { permission: 'ai:model:knowledge-base:list', navigable: true, desc: 'knowledgeBases.description' } },
        { path: 'knowledge-bases/:knowledgeBaseId/debug', component: RagRetrievalDebugView, props: true, meta: { permission: 'ai:model:knowledge-base:list', desc: 'RAG 检索调试台' } },
        { path: 'tasks', component: TasksView, meta: { permission: 'operations:task:view', navigable: true, desc: 'tasks.description' } },
        { path: 'data-sources', component: DataSourcesView, meta: { permission: 'operations:data-source:list', navigable: true, desc: 'dataSources.description' } },
        { path: 'data-sync', component: DataSyncView, meta: { permission: 'operations:data-sync:list', navigable: true, desc: 'dataSync.description' } },
        { path: 'servers', component: ServersView, meta: { permission: 'operations:server:list', navigable: true, desc: 'servers.description' } },
        { path: 'api-keys', component: ApiKeysView, meta: { permission: 'system:api-key:list', navigable: true, desc: 'apiKeys.description' } },
        { path: 'automation/api-triggers', component: ApiTriggerView, meta: { permission: 'automation:api-trigger:list', navigable: true, desc: 'apiTrigger.description' } },
        { path: 'automation/api-trigger-security', component: ApiTriggerSecurityView, meta: { permission: 'automation:api-trigger-security:view', navigable: true, desc: 'apiTriggerSecurity.description' } },
        { path: 'automation/device-agents', component: DeviceAgentsView, meta: { permission: 'automation:device-agent:list', navigable: true, desc: 'deviceAgents.description' } },
        { path: 'automation/device-agents/config-guide', component: DeviceAgentGuideView, meta: { permission: 'automation:device-agent:list', desc: 'deviceAgentGuide.description' } },
        { path: 'automation/device-agents/onboarding', component: DeviceAgentOnboardingView, meta: { permission: 'automation:device-agent:list', desc: 'deviceAgentOnboarding.title' } },
        { path: 'workflow/nodes', component: WorkflowNodesView, meta: { permission: 'automation:workflow:node:list', navigable: true, desc: 'workflowNodes.description' } },
        { path: 'workflow/node-docs', component: WorkflowNodeDocsView, meta: { permission: 'automation:workflow:node:docs', navigable: true, desc: 'workflowNodeDocs.description' } },
        { path: 'workflow/canvases', component: WorkflowCanvasView, meta: { permission: 'automation:workflow:canvas:list', navigable: true, desc: 'workflowCanvas.description' } }
      ]
    }
  ]
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) {
    try { await auth.fetchMe(true) } catch { /* 未登录访客保持匿名状态。 */ }
  }
  if (to.meta.guestOnly) return auth.isLoggedIn ? '/dashboard' : true
  if (to.meta.public) return true
  if (!auth.isLoggedIn) return `/login?redirect=${encodeURIComponent(to.fullPath)}`
  return auth.hasPermission(to.meta.permission) ? true : '/dashboard'
})

export default router
