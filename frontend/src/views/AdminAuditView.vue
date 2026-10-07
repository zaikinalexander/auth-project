<template>
  <div>
    <h1>Журнал аудита</h1>

    <div class="card">
      <h2>Фильтры</h2>
      <p>
        <select v-model="filters.event">
          <option value="">Все события</option>
          <option v-for="name in eventNames" :key="name" :value="name">{{ name }}</option>
        </select>
        <input v-model="filters.actor" type="text" placeholder="Email актёра (подстрока)" />
      </p>
      <p>
        <label class="check">с <input v-model="filters.from" type="date" /></label>
        <label class="check">по <input v-model="filters.to" type="date" /></label>
      </p>
      <p v-if="error" class="error">{{ error }}</p>
      <p>
        <button class="primary" @click="apply">Применить</button>
        <button @click="reset">Сбросить</button>
        <button v-if="auth.isAdmin" class="danger clear-btn" @click="askClear">Очистить журнал</button>
      </p>
    </div>

    <div class="card">
      <h2>События: {{ total }}</h2>
      <table>
        <thead>
          <tr>
            <th></th>
            <th>Время</th>
            <th>Событие</th>
            <th>Актёр</th>
            <th>IP</th>
            <th>Цель</th>
            <th>Успех</th>
          </tr>
        </thead>
        <tbody>
          <template v-for="row in items" :key="row.id">
            <tr>
              <td>
                <button @click="toggle(row.id)">{{ expanded === row.id ? '−' : '+' }}</button>
              </td>
              <td>{{ formatTime(row.ts) }}</td>
              <td><code>{{ row.event }}</code></td>
              <td>{{ row.actorEmail || '—' }}</td>
              <td>{{ row.ip || '—' }}</td>
              <td>{{ row.target || '—' }}</td>
              <td>{{ formatSuccess(row.success) }}</td>
            </tr>
            <!-- Детали выводятся интерполяцией как обычный текст, без вставки разметки -->
            <tr v-if="expanded === row.id" class="details">
              <td></td>
              <td colspan="6">
                <div v-if="row.actorRole">Роль актёра: {{ row.actorRole }}</div>
                <div v-if="row.userAgent">User-Agent: {{ row.userAgent }}</div>
                <div v-for="(value, key) in row.details || {}" :key="key">{{ key }}: {{ formatValue(value) }}</div>
                <div v-if="!row.details || !Object.keys(row.details).length">Деталей нет</div>
              </td>
            </tr>
          </template>
          <tr v-if="!items.length">
            <td colspan="7">Событий по заданным условиям нет</td>
          </tr>
        </tbody>
      </table>
      <p>
        <button :disabled="page === 0" @click="go(page - 1)">Назад</button>
        <span class="pager">Страница {{ page + 1 }} из {{ Math.max(totalPages, 1) }}</span>
        <button :disabled="page + 1 >= totalPages" @click="go(page + 1)">Вперёд</button>
        <select v-model.number="size" @change="go(0)">
          <option :value="25">25</option>
          <option :value="50">50</option>
          <option :value="100">100</option>
        </select>
      </p>
    </div>
    <div v-if="confirmVisible" class="overlay" @click.self="closeConfirm">
      <div class="card modal">
        <h2>Очистка журнала аудита</h2>
        <p>Будут удалены все записи журнала (сейчас их {{ total }}). Действие необратимо.</p>
        <p>
          После очистки в журнале останется одна запись <code>AUDIT_CLEAR</code>
          с указанием, кто, когда и с какого адреса её выполнил, — скрыть сам
          факт очистки нельзя.
        </p>
        <p>
          Для подтверждения введите слово <strong>ОЧИСТИТЬ</strong>
          <input v-model="confirmWord" type="text" autocomplete="off" spellcheck="false" />
        </p>
        <p v-if="clearError" class="error">{{ clearError }}</p>
        <p>
          <button class="danger" :disabled="confirmWord !== CONFIRM_WORD || clearing" @click="doClear">
            {{ clearing ? 'Очистка…' : 'Подтвердить очистку' }}
          </button>
          <button :disabled="clearing" @click="closeConfirm">Отмена</button>
        </p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const CONFIRM_WORD = 'ОЧИСТИТЬ'

const eventNames = [
  'AUTH_LOGIN_SUCCESS', 'AUTH_LOGIN_FAIL', 'AUTH_LOGOUT',
  'AUTH_REFRESH_OK', 'AUTH_REFRESH_REUSE', 'AUTH_REFRESH_INVALID',
  'RATE_LIMIT_HIT',
  'ADMIN_USER_CREATE', 'ADMIN_USER_UPDATE', 'ADMIN_USER_PAGES', 'ADMIN_USER_DELETE',
  'PAGE_VIEW', 'PAGE_DENIED', 'AUDIT_READ'
]

const items = ref([])
const total = ref(0)
const totalPages = ref(0)
const page = ref(0)
const size = ref(50)
const expanded = ref(null)
const error = ref('')
const filters = reactive({ event: '', actor: '', from: '', to: '' })
const confirmVisible = ref(false)
const confirmWord = ref('')
const clearing = ref(false)
const clearError = ref('')

function formatTime(ts) {
  if (!ts) return '—'
  return new Date(ts).toLocaleString('ru-RU')
}

function formatSuccess(value) {
  if (value === null || value === undefined) return '—'
  return value ? 'да' : 'нет'
}

function formatValue(value) {
  if (Array.isArray(value)) return value.join(', ')
  if (value && typeof value === 'object') return JSON.stringify(value)
  return String(value)
}

function toggle(id) {
  expanded.value = expanded.value === id ? null : id
}

async function load() {
  error.value = ''
  const params = { page: page.value, size: size.value }
  if (filters.event) params.event = filters.event
  if (filters.actor) params.actor = filters.actor
  if (filters.from) params.from = filters.from
  // дата «по» включает весь день, поэтому добавляем конец суток
  if (filters.to) params.to = filters.to + 'T23:59:59Z'
  try {
    const resp = await http.get('/admin/audit', { params })
    items.value = resp.data.items
    total.value = resp.data.total
    totalPages.value = resp.data.totalPages
  } catch (e) {
    items.value = []
    error.value = e.response && e.response.data && e.response.data.error
      ? e.response.data.error
      : 'Не удалось загрузить журнал'
  }
}

function go(target) {
  page.value = Math.max(target, 0)
  expanded.value = null
  load()
}

function apply() {
  go(0)
}

function reset() {
  filters.event = ''
  filters.actor = ''
  filters.from = ''
  filters.to = ''
  go(0)
}

function askClear() {
  confirmWord.value = ''
  clearError.value = ''
  confirmVisible.value = true
}

function closeConfirm() {
  if (!clearing.value) confirmVisible.value = false
}

async function doClear() {
  if (confirmWord.value !== CONFIRM_WORD || clearing.value) return
  clearing.value = true
  clearError.value = ''
  try {
    await http.post('/admin/audit/clear')
    confirmVisible.value = false
    expanded.value = null
    page.value = 0
    await load()
  } catch (e) {
    clearError.value = e.response && e.response.data && e.response.data.error
      ? e.response.data.error
      : 'Не удалось очистить журнал'
  } finally {
    clearing.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.details td {
  background: #f4f6f8;
  font-size: 13px;
  color: #3e4c59;
}
.details code, td code {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.pager {
  margin: 0 12px;
  font-size: 14px;
}
.clear-btn {
  margin-left: 12px;
}
.overlay {
  position: fixed;
  inset: 0;
  background: rgba(31, 41, 51, 0.55);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 10;
}
.modal {
  max-width: 480px;
  margin: 0;
}
.modal input {
  display: block;
  width: 100%;
  box-sizing: border-box;
  margin-top: 6px;
}
.modal button {
  margin-right: 8px;
}
table button {
  border: 1px solid #cbd2d9;
  background: #ffffff;
  border-radius: 4px;
  width: 26px;
  cursor: pointer;
}
</style>
