<template>
  <div class="layout">
    <header v-if="auth.isAuthenticated" class="topbar">
      <nav>
        <router-link v-if="auth.isAdmin" to="/admin/users">Пользователи</router-link>
        <template v-for="code in allPages" :key="code">
          <router-link v-if="auth.hasPage(code)" :to="'/pages/' + code">
            {{ code }}
          </router-link>
        </template>
        <button class="logout" @click="logout">Выйти</button>
      </nav>
    </header>
    <main class="content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()
const router = useRouter()
const allPages = ['TEST_PAGE_1', 'TEST_PAGE_2', 'TEST_PAGE_3']

async function logout() {
  await auth.logout()
  router.push('/login')
}
</script>

<style>
body {
  margin: 0;
  font-family: system-ui, -apple-system, "Segoe UI", Roboto, sans-serif;
  background: #f4f6f8;
  color: #1f2933;
}
.topbar {
  background: #1f2933;
  padding: 10px 20px;
}
.topbar nav {
  display: flex;
  gap: 16px;
  align-items: center;
}
.topbar a {
  color: #e4e7eb;
  text-decoration: none;
  font-size: 14px;
}
.topbar a.router-link-active {
  color: #ffffff;
  font-weight: 600;
}
.logout {
  margin-left: auto;
  background: #3e4c59;
  color: #ffffff;
  border: 0;
  padding: 6px 12px;
  border-radius: 4px;
  cursor: pointer;
}
.content {
  max-width: 960px;
  margin: 24px auto;
  padding: 0 16px;
}
.card {
  background: #ffffff;
  border-radius: 8px;
  padding: 20px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1);
}
input, select {
  padding: 8px 10px;
  border: 1px solid #cbd2d9;
  border-radius: 4px;
  font-size: 14px;
}
button.primary {
  background: #1f6f43;
  color: #ffffff;
  border: 0;
  padding: 8px 16px;
  border-radius: 4px;
  cursor: pointer;
}
button.danger {
  background: #a02c2c;
  color: #ffffff;
  border: 0;
  padding: 6px 10px;
  border-radius: 4px;
  cursor: pointer;
}
.error {
  color: #a02c2c;
  font-size: 14px;
}
table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}
th, td {
  text-align: left;
  padding: 8px 10px;
  border-bottom: 1px solid #e4e7eb;
}
label.check {
  margin-right: 12px;
  font-size: 13px;
  white-space: nowrap;
}
</style>