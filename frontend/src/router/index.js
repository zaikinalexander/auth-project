import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import LoginView from '../views/LoginView.vue'
import AdminUsersView from '../views/AdminUsersView.vue'
import TestPageView from '../views/TestPageView.vue'
import ForbiddenView from '../views/ForbiddenView.vue'

const routes = [
  {
    path: '/login',
    name: 'login',
    component: LoginView,
    meta: { guestOnly: true }
  },
  {
    path: '/admin/users',
    name: 'admin-users',
    component: AdminUsersView,
    meta: { requiresAuth: true, role: 'ADMIN' }
  },
  {
    path: '/pages/:code',
    name: 'test-page',
    component: TestPageView,
    meta: { requiresAuth: true }
  },
  {
    path: '/forbidden',
    name: 'forbidden',
    component: ForbiddenView
  },
  {
    path: '/',
    redirect: '/login'
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/forbidden'
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  // один раз за загрузку страницы пробуем восстановить сессию
  if (!auth.ready) {
    await auth.restore()
    auth.ready = true
  }

  if (to.meta.guestOnly && auth.isAuthenticated) {
    return auth.homeRoute()
  }

  if (to.meta.requiresAuth && !auth.isAuthenticated) {
    return '/login'
  }

  if (to.meta.role === 'ADMIN' && !auth.isAdmin) {
    return '/forbidden'
  }

  if (to.name === 'test-page' && !auth.hasPage(to.params.code)) {
    return '/forbidden'
  }

  return true
})

export default router