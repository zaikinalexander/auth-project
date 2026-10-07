<template>
  <div class="card">
    <p v-if="loading">Загрузка...</p>
    <p v-else-if="notFound">
      <strong>Страница не найдена</strong><br />
      Страницы с кодом <code>{{ route.params.code }}</code> не существует.
      Возможно, её удалил администратор.
    </p>
    <p v-else-if="error" class="error">{{ error }}</p>
    <template v-else-if="page">
      <h1>{{ page.title }}</h1>
      <p>Код страницы: {{ page.code }}</p>
      <!-- Содержимое из базы выводится только интерполяцией; white-space
           сохраняет переносы строк, разметка не интерпретируется. -->
      <p class="content">{{ page.content }}</p>
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
const notFound = ref(false)

async function loadPage(code) {
  loading.value = true
  error.value = ''
  notFound.value = false
  page.value = null
  try {
    const resp = await http.get('/pages/' + encodeURIComponent(code))
    page.value = resp.data
  } catch (e) {
    const status = e.response ? e.response.status : null
    if (status === 403) {
      router.push('/forbidden')
      return
    }
    if (status === 404) {
      notFound.value = true
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

<style scoped>
.content {
  white-space: pre-line;
}
code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
}
</style>
