<template>
  <div>
    <h1>Страницы</h1>

    <div class="card">
      <h2>{{ editing ? 'Правка страницы ' + editing : 'Создать страницу' }}</h2>
      <p>
        <input v-if="!editing" v-model="form.code" type="text" placeholder="КОД_СТРАНИЦЫ"
               maxlength="64" pattern="[A-Z0-9_]{3,64}" />
        <input v-model="form.title" type="text" placeholder="Заголовок" maxlength="200" />
      </p>
      <p>
        <textarea v-model="form.content" rows="6" placeholder="Содержимое страницы"></textarea>
      </p>
      <p class="hint">
        Код: от 3 до 64 символов, только заглавные латинские буквы, цифры и подчёркивание.
        После создания код изменить нельзя — на него ссылаются выданные права.
      </p>
      <p v-if="error" class="error">{{ error }}</p>
      <p>
        <button class="primary" @click="submit">{{ editing ? 'Сохранить' : 'Создать' }}</button>
        <button v-if="editing" @click="cancel">Отмена</button>
      </p>
    </div>

    <div class="card">
      <h2>Список страниц ({{ pages.length }})</h2>
      <p v-if="listError" class="error">{{ listError }}</p>
      <table>
        <thead>
          <tr><th>Код</th><th>Заголовок</th><th>Создана</th><th>Действия</th></tr>
        </thead>
        <tbody>
          <tr v-for="p in pages" :key="p.code">
            <td><code>{{ p.code }}</code></td>
            <td>{{ p.title }}</td>
            <td>{{ formatDate(p.createdAt) }}</td>
            <td>
              <button class="primary" @click="startEdit(p)">Изменить</button>
              <button class="danger" @click="remove(p)">Удалить</button>
            </td>
          </tr>
          <tr v-if="!pages.length"><td colspan="4">Страниц пока нет</td></tr>
        </tbody>
      </table>
      <p class="hint">
        Удаление страницы каскадно снимает её у всех пользователей: право исчезает
        немедленно, а обращение к коду удалённой страницы даёт 403 у пользователя
        и 404 у администратора.
      </p>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { http } from '../api/http'

const pages = ref([])
const error = ref('')
const listError = ref('')
const editing = ref(null)
const form = reactive({ code: '', title: '', content: '' })

function formatDate(ts) {
  return ts ? new Date(ts).toLocaleString('ru-RU') : '—'
}

function message(e, fallback) {
  if (e.response && e.response.data) {
    if (e.response.data.fields) {
      const first = Object.values(e.response.data.fields)[0]
      return first ? fallback + ': ' + first : fallback
    }
    if (e.response.data.error) return e.response.data.error
  }
  return fallback
}

async function load() {
  listError.value = ''
  try {
    pages.value = (await http.get('/admin/pages')).data
  } catch (e) {
    listError.value = message(e, 'Не удалось загрузить список страниц')
  }
}

function startEdit(p) {
  editing.value = p.code
  form.code = p.code
  form.title = p.title
  form.content = p.content || ''
  error.value = ''
}

function cancel() {
  editing.value = null
  form.code = ''
  form.title = ''
  form.content = ''
  error.value = ''
}

async function submit() {
  error.value = ''
  try {
    if (editing.value) {
      await http.put('/admin/pages/' + encodeURIComponent(editing.value),
        { title: form.title, content: form.content })
    } else {
      await http.post('/admin/pages',
        { code: form.code, title: form.title, content: form.content })
    }
    cancel()
    await load()
  } catch (e) {
    error.value = message(e, editing.value ? 'Не удалось сохранить страницу' : 'Не удалось создать страницу')
  }
}

async function remove(p) {
  const warning = 'Удалить страницу ' + p.code + '?\n\n'
    + 'Право на неё будет снято у всех пользователей безвозвратно.'
  if (!window.confirm(warning)) return
  try {
    await http.delete('/admin/pages/' + encodeURIComponent(p.code))
    if (editing.value === p.code) cancel()
    await load()
  } catch (e) {
    listError.value = message(e, 'Не удалось удалить страницу')
  }
}

onMounted(load)
</script>

<style scoped>
textarea {
  width: 100%;
  box-sizing: border-box;
  padding: 8px 10px;
  border: 1px solid #cbd2d9;
  border-radius: 4px;
  font-family: inherit;
  font-size: 14px;
}
input[type="text"] {
  margin-right: 8px;
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
