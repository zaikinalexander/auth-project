<template>
  <div>
    <h1>Управление пользователями</h1>

    <div class="card">
      <h2>Создать пользователя</h2>
      <p>
        <input v-model="createForm.email" type="email" placeholder="Email" />
        <input v-model="createForm.password" type="password" minlength="10" maxlength="128"
               placeholder="Пароль (мин. 10 символов)" autocomplete="new-password" />
      </p>
      <p>
        <select v-model="createForm.role">
          <option value="USER">USER</option>
          <option value="ADMIN">ADMIN</option>
        </select>
      </p>
      <p>
        <label v-for="code in allPages" :key="code" class="check">
          <input type="checkbox" :value="code" v-model="createForm.pages" />
          {{ code }}
        </label>
      </p>
      <p v-if="createError" class="error">{{ createError }}</p>
      <p>
        <button class="primary" @click="createUser">Создать</button>
      </p>
    </div>

    <div class="card">
      <h2>Пользователи</h2>
      <p v-if="listError" class="error">{{ listError }}</p>
      <table>
        <thead>
          <tr>
            <th>Email</th>
            <th>Роль</th>
            <th>Статус</th>
            <th>Страницы</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="user in users" :key="user.id">
            <td>{{ user.email }}</td>
            <td>{{ user.role }}</td>
            <td>{{ user.enabled ? 'активен' : 'отключён' }}</td>
            <td>{{ user.pages.join(', ') || '—' }}</td>
            <td>
              <button class="primary" @click="startEdit(user)">Изменить</button>
              <button class="danger" @click="removeUser(user)">Удалить</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="editingId" class="card">
      <h2>Редактирование пользователя</h2>
      <p>
        Роль:
        <select v-model="editForm.role">
          <option value="USER">USER</option>
          <option value="ADMIN">ADMIN</option>
        </select>
        <label class="check">
          <input type="checkbox" v-model="editForm.enabled" />
          активен
        </label>
      </p>
      <p>
        Доступ к страницам:
        <label v-for="code in allPages" :key="code" class="check">
          <input type="checkbox" :value="code" v-model="editForm.pages" />
          {{ code }}
        </label>
      </p>
      <p v-if="editError" class="error">{{ editError }}</p>
      <p>
        <button class="primary" @click="saveEdit">Сохранить</button>
        <button @click="editingId = null">Отмена</button>
      </p>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { http } from '../api/http'

const allPages = ['TEST_PAGE_1', 'TEST_PAGE_2', 'TEST_PAGE_3']
const MIN_PASSWORD_LENGTH = 10
const MAX_PASSWORD_LENGTH = 128

const users = ref([])
const listError = ref('')
const createError = ref('')
const editError = ref('')
const editingId = ref(null)

const createForm = reactive({ email: '', password: '', role: 'USER', pages: [] })
const editForm = reactive({ role: 'USER', enabled: true, pages: [] })

function errorMessage(e, fallback) {
  return e.response && e.response.data && e.response.data.error
    ? e.response.data.error
    : fallback
}

async function load() {
  listError.value = ''
  try {
    const resp = await http.get('/admin/users')
    users.value = resp.data
  } catch (e) {
    listError.value = errorMessage(e, 'Не удалось загрузить список пользователей')
  }
}

// Сервер требует не менее 10 символов, поэтому проверяем и здесь,
// чтобы пользователь не получал 400 на ровном месте.
function validateCreateForm() {
  if (!createForm.email || createForm.email.indexOf('@') === -1) {
    return 'Укажите корректный email'
  }
  if (!createForm.password || createForm.password.length < MIN_PASSWORD_LENGTH) {
    return 'Пароль должен содержать не менее ' + MIN_PASSWORD_LENGTH + ' символов'
  }
  if (createForm.password.length > MAX_PASSWORD_LENGTH) {
    return 'Пароль не должен быть длиннее ' + MAX_PASSWORD_LENGTH + ' символов'
  }
  return ''
}

async function createUser() {
  createError.value = validateCreateForm()
  if (createError.value) {
    return
  }
  try {
    await http.post('/admin/users', {
      email: createForm.email,
      password: createForm.password,
      role: createForm.role,
      pages: createForm.pages
    })
    createForm.email = ''
    createForm.password = ''
    createForm.role = 'USER'
    createForm.pages = []
    await load()
  } catch (e) {
    createError.value = errorMessage(e, 'Не удалось создать пользователя')
  }
}

function startEdit(user) {
  editingId.value = user.id
  editError.value = ''
  editForm.role = user.role
  editForm.enabled = user.enabled
  editForm.pages = [...user.pages]
}

async function saveEdit() {
  editError.value = ''
  try {
    await http.patch('/admin/users/' + editingId.value, {
      role: editForm.role,
      enabled: editForm.enabled
    })
    await http.put('/admin/users/' + editingId.value + '/pages', {
      pages: editForm.pages
    })
    editingId.value = null
    await load()
  } catch (e) {
    editError.value = errorMessage(e, 'Не удалось сохранить изменения')
  }
}

async function removeUser(user) {
  if (!window.confirm('Удалить пользователя ' + user.email + '?')) {
    return
  }
  try {
    await http.delete('/admin/users/' + user.id)
    if (editingId.value === user.id) {
      editingId.value = null
    }
    await load()
  } catch (e) {
    listError.value = errorMessage(e, 'Не удалось удалить пользователя')
  }
}

onMounted(load)
</script>