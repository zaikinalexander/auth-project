<template>
  <div class="card">
    <p v-if="loading">Загрузка...</p>
    <p v-else-if="error" class="error">{{ error }}</p>
    <template v-else-if="page">
      <h1>{{ page.title }}</h1>
      <p>Код страницы: {{ page.code }}</p>
      <p>{{ page.content }}</p>
    </template>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { http } from '../api/http'

const route = useRoute()
const router = useRouter()

const page = ref(null)
const loading = ref(false)
const error = ref('')

async function loadPage(code) {
  loading.value = true
  error.value = ''
  page.value = null
  try {
    const resp = await http.get('/pages/' + code)
    page.value = resp.data
  } catch (e) {
    if (e.response && e.response.status === 403) {
      router.push('/forbidden')
      return
    }
    error.value = e.response && e.response.data && e.response.data.error
      ? e.response.data.error
      : 'Не удалось загрузить страницу'
  } finally {
    loading.value = false
  }
}

watch(() => route.params.code, (code) => loadPage(code), { immediate: true })
</script>