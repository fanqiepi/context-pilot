import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
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
  ],
})

export default router
