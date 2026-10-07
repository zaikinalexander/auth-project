<template>
  <div>
    <h1>Личный кабинет</h1>

    <div class="card" v-if="me">
      <h2>Профиль</h2>
      <table class="kv">
        <tbody>
          <tr><th>Email</th><td>{{ me.email }}</td></tr>
          <tr><th>Роль</th><td>{{ me.role }}</td></tr>
          <tr>
            <th>Доступные страницы</th>
            <td>
              <span v-if="!effectivePages.length">нет</span>
              <router-link v-for="code in effectivePages" :key="code"
                           :to="'/pages/' + code" class="page-link">{{ code }}</router-link>
            </td>
          </tr>
        </tbody>
      </table>
      <p v-if="me.role === 'ADMIN'" class="hint">
        Роль ADMIN даёт доступ ко всем страницам независимо от выданного списка.
      </p>
    </div>

    <div class="card">
      <h2>Мои активные сессии ({{ sessions.length }})</h2>
      <p v-if="sessionError" class="error">{{ sessionError }}</p>
      <table>
        <thead>
          <tr><th></th><th>Создана</th><th>Последняя активность</th><th>IP</th><th>User-Agent</th></tr>
        </thead>
        <tbody>
          <tr v-for="s in sessions" :key="s.familyId">
            <td>{{ s.current ? 'текущая' : '' }}</td>
            <td>{{ formatDate(s.createdAt) }}</td>
            <td>{{ formatDate(s.lastUsedAt) }}</td>
            <td>{{ s.ip || '—' }}</td>
            <td class="ua" :title="s.userAgent">{{ s.userAgent || '—' }}</td>
          </tr>
          <tr v-if="!sessions.length"><td colspan="5">Сведений об активных сессиях нет</td></tr>
        </tbody>
      </table>
      <p>
        <button class="danger" :disabled="busySessions" @click="revokeOthers">
          Завершить все, кроме текущей
        </button>
      </p>
      <p class="hint">
        Пригодится, если входили с чужого устройства и забыли выйти.
        Остальные сессии завершаются: их refresh-токены удаляются сразу,
        а уже выданные access-токены доживут до 15 минут — продлить их
        устройства не смогут.
      </p>
    </div>

    <div class="card">
      <h2>Смена пароля</h2>
      <p>
        <input v-model="pwd.current" type="password" placeholder="Текущий пароль"
               autocomplete="current-password" />
      </p>
      <p>
        <input v-model="pwd.next" type="password" placeholder="Новый пароль (мин. 10 символов)"
               minlength="10" maxlength="128" autocomplete="new-password" />
      </p>
      <p>
        <input v-model="pwd.repeat" type="password" placeholder="Новый пароль ещё раз"
               minlength="10" maxlength="128" autocomplete="new-password" />
      </p>
      <p v-if="pwdError" class="error">{{ pwdError }}</p>
      <p v-if="pwdDone" class="ok">{{ pwdDone }}</p>
      <p>
        <button class="primary" :disabled="busyPwd" @click="changePassword">
          {{ busyPwd ? 'Смена…' : 'Сменить пароль' }}
        </button>
      </p>
      <p class="hint">
        После смены пароля <strong>все</strong> сессии на всех устройствах
        завершаются — придётся войти заново. Так задумано: если пароль меняют,
        прежний считается скомпрометированным.
      </p>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { http } from '../api/http'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

const me = ref(null)
const sessions = ref([])
const sessionError = ref('')
const busySessions = ref(false)
const pwd = reactive({ current: '', next: '', repeat: '' })
const pwdError = ref('')
const pwdDone = ref('')
const busyPwd = ref(false)

const MIN_PASSWORD_LENGTH = 10

// Администратору доступны все страницы, поэтому список для меню и ссылок
// берём из справочника страниц, а не только из выданных прав.
const allPages = ref([])
const effectivePages = computed(() => {
  if (!me.value) return []
  if (me.value.role === 'ADMIN' && allPages.value.length) {
    return allPages.value.map(p => p.code)
  }
  return me.value.pages || []
})

function formatDate(ts) {
  return ts ? new Date(ts).toLocaleString('ru-RU') : '—'
}

async function loadMe() {
  try {
    me.value = (await http.get('/auth/me')).data
  } catch (e) {
    me.value = null
  }
}

async function loadSessions() {
  sessionError.value = ''
  try {
    sessions.value = (await http.get('/me/sessions')).data
  } catch (e) {
    sessionError.value = e.response && e.response.data && e.response.data.error
      ? e.response.data.error
      : 'Не удалось загрузить список сессий'
  }
}

async function loadAllPages() {
  if (!auth.isAdmin) return
  try {
    allPages.value = (await http.get('/admin/pages')).data
  } catch (e) {
    allPages.value = []
  }
}

async function revokeOthers() {
  if (!window.confirm('Завершить все сессии, кроме текущей?')) return
  busySessions.value = true
  try {
    await http.delete('/me/sessions/other')
    await loadSessions()
  } catch (e) {
    sessionError.value = e.response && e.response.data && e.response.data.error
      ? e.response.data.error
      : 'Не удалось завершить сессии'
  } finally {
    busySessions.value = false
  }
}

async function changePassword() {
  pwdError.value = ''
  pwdDone.value = ''
  if (!pwd.current) {
    pwdError.value = 'Введите текущий пароль'
    return
  }
  if (!pwd.next || pwd.next.length < MIN_PASSWORD_LENGTH) {
    pwdError.value = 'Новый пароль должен содержать не менее ' + MIN_PASSWORD_LENGTH + ' символов'
    return
  }
  if (pwd.next !== pwd.repeat) {
    pwdError.value = 'Новые пароли не совпадают'
    return
  }
  busyPwd.value = true
  try {
    await http.post('/auth/password', { currentPassword: pwd.current, newPassword: pwd.next })
    pwd.current = ''
    pwd.next = ''
    pwd.repeat = ''
    // Сервер завершил все сессии и погасил cookie, поэтому уходим на страницу входа.
    auth.clearSession()
    router.push('/login')
  } catch (e) {
    pwdError.value = e.response && e.response.data && e.response.data.error
      ? e.response.data.error
      : 'Не удалось сменить пароль'
  } finally {
    busyPwd.value = false
  }
}

onMounted(async () => {
  await Promise.all([loadMe(), loadSessions(), loadAllPages()])
})
</script>

<style scoped>
table.kv th {
  text-align: left;
  width: 200px;
  color: #52606d;
  font-weight: 500;
}
.page-link {
  display: inline-block;
  margin-right: 10px;
}
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
.ok {
  color: #1f6f43;
  font-size: 14px;
}
input {
  width: 100%;
  max-width: 380px;
  box-sizing: border-box;
}
</style>
