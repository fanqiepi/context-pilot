import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/books', name: 'books', component: () => import('@/views/BooksView.vue') },
    { path: '/books/import', name: 'book-import', component: () => import('@/views/BookImportView.vue') },
    { path: '/books/:bookId', redirect: to => `/books/${to.params.bookId}/read` },
    { path: '/books/:bookId/read', name: 'book-reader', component: () => import('@/views/BookReaderView.vue') },
    { path: '/books/:bookId/notes', name: 'book-notes', component: () => import('@/views/BookReaderView.vue') },
    { path: '/project-sources', name: 'project-sources', component: () => import('@/views/ProjectSourcesView.vue') },
    { path: '/research-tasks', name: 'research-tasks', component: () => import('@/views/ResearchTasksView.vue') },
    { path: '/upgrade-research', name: 'upgrade-research', component: () => import('@/views/UpgradeResearchView.vue') },
    {
      path: '/',
      redirect: '/books',
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
