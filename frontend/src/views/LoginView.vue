<template>
  <div class="card login-card">
    <h1>Вход в систему</h1>
    <form @submit.prevent="submit">
      <p>
        <input v-model="email" type="email" placeholder="Email" autocomplete="username" required />
      </p>
      <p>
        <input v-model="password" type="password" placeholder="Пароль" autocomplete="current-password" required />
      </p>
      <p v-if="error" class="error">{{ error }}</p>
      <p>
        <button class="primary" type="submit" :disabled="busy">
          {{ busy ? 'Проверка...' : 'Войти' }}
        </button>
      </p>
    </form>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

const email = ref('')
const password = ref('')
const error = ref('')
const busy = ref(false)

async function submit() {
  busy.value = true
  error.value = ''
  try {
    await auth.login(email.value, password.value)
    router.push(auth.homeRoute())
  } catch (e) {
    error.value = e.response && e.response.data && e.response.data.error
      ? e.response.data.error
      : 'Не удалось выполнить вход'
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.login-card {
  max-width: 380px;
  margin: 60px auto;
}
.login-card input {
  width: 100%;
  box-sizing: border-box;
}
</style>