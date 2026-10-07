<template>
  <div>
    <h1>Активные сессии</h1>

    <div class="card">
      <h2>Фильтр</h2>
      <p>
        <input v-model="filter" type="text" placeholder="Email пользователя (подстрока)" @keyup.enter="load" />
        <button class="primary" @click="load">Применить</button>
        <button @click="reset">Сбросить</button>
      </p>
      <p v-if="error" class="error">{{ error }}</p>
      <p class="hint">
        Завершение сессии немедленно удаляет её refresh-токен: обновить
        access-токен устройство больше не сможет. Уже выданный access-токен
        доживает свои 15 минут — он stateless и по хранилищу не проверяется.
        Так устроена схема из технического задания, это не ошибка.
      </p>
    </div>

    <div class="card">
      <h2>Сессии ({{ sessions.length }})</h2>
      <table>
        <thead>
          <tr>
            <th>Пользователь</th><th>Семейство</th><th>Создана</th>
            <th>Последняя активность</th><th>IP</th><th>User-Agent</th><th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in sessions" :key="s.familyId">
            <td>{{ s.userEmail || '(пользователь удалён)' }}</td>
            <td><code>{{ short(s.familyId) }}</code></td>
            <td>{{ formatDate(s.createdAt) }}</td>
            <td>{{ formatDate(s.lastUsedAt) }}</td>
            <td>{{ s.ip || '—' }}</td>
            <td class="ua" :title="s.userAgent">{{ s.userAgent || '—' }}</td>
            <td><button class="danger" @click="revoke(s)">Завершить</button></td>
          </tr>
          <tr v-if="!sessions.length"><td colspan="7">Активных сессий нет</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { http } from '../api/http'

const sessions = ref([])
const filter = ref('')
const error = ref('')

function short(id) {
  return id ? id.slice(0, 8) + '…' : '—'
}

function formatDate(ts) {
  return ts ? new Date(ts).toLocaleString('ru-RU') : '—'
}

async function load() {
  error.value = ''
  try {
    const params = {}
    if (filter.value) params.user = filter.value
    sessions.value = (await http.get('/admin/sessions', { params })).data
  } catch (e) {
    error.value = e.response && e.response.data && e.response.data.error
      ? e.response.data.error
      : 'Не удалось загрузить список сессий'
  }
}

function reset() {
  filter.value = ''
  load()
}

async function revoke(s) {
  const who = s.userEmail || 'неизвестного пользователя'
  if (!window.confirm('Завершить сессию ' + who + ' (' + short(s.familyId) + ')?')) return
  try {
    await http.delete('/admin/sessions/' + encodeURIComponent(s.familyId))
    await load()
  } catch (e) {
    error.value = e.response && e.response.data && e.response.data.error
      ? e.response.data.error
      : 'Не удалось завершить сессию'
  }
}

onMounted(load)
</script>

<style scoped>
.ua {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 12px;
}
.hint {
  color: #52606d;
  font-size: 13px;
}
code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
</style>
