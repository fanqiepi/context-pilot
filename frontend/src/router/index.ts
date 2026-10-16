import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/research-tasks', name: 'research-tasks', component: () => import('@/views/ResearchTasksView.vue') },
    { path: '/upgrade-research', name: 'upgrade-research', component: () => import('@/views/UpgradeResearchView.vue') },
    {
      path: '/',
      redirect: '/library',
    },
    {
      path: '/library',
      name: 'library',
      component: () => import('@/views/KnowledgeBaseView.vue'),
    },
    {
      path: '/chat',
      name: 'chat',
      component: () => import('@/views/ChatView.vue'),
    },
    {
      path: '/memories',
      name: 'memories',
      component: () => import('@/views/MemoryView.vue'),
    },
  ],
})

export default router
