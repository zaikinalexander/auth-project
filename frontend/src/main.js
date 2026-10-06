import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)

// Если access-токен протух, а refresh недействителен,
// interceptor Axios дёрнет этот обработчик и уведёт на /login.
useAuthStore(pinia).installUnauthorizedHandler(router)

app.mount('#app')