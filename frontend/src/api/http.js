import axios from 'axios'

// Access-токен живёт только в памяти страницы: из localStorage его забрал бы любой XSS.
export const authToken = { current: null }

let unauthorizedHandler = null

export function setUnauthorizedHandler(fn) {
  unauthorizedHandler = fn
}

export const http = axios.create({
  baseURL: '/api',
  withCredentials: true
})

http.interceptors.request.use((config) => {
  if (authToken.current) {
    config.headers.Authorization = `Bearer ${authToken.current}`
  }
  return config
})

// Один одновременный запрос обновления на все ошибочные 401.
let refreshing = null

function doRefresh() {
  if (!refreshing) {
    const promise = axios.post('/api/auth/refresh', null, { withCredentials: true })
    refreshing = promise
    promise.finally(() => {
      if (refreshing === promise) {
        refreshing = null
      }
    })
  }
  return refreshing
}

http.interceptors.response.use(
  (response) => response,
  async (error) => {
    const original = error.config
    const status = error.response ? error.response.status : null
    const url = original && original.url ? original.url : ''
    const isAuthCall = url.indexOf('/auth/login') !== -1 || url.indexOf('/auth/refresh') !== -1

    if (status === 401 && original && !original._retry && !isAuthCall) {
      original._retry = true
      try {
        const resp = await doRefresh()
        authToken.current = resp.data.accessToken
        original.headers.Authorization = `Bearer ${authToken.current}`
        return http(original)
      } catch (refreshError) {
        authToken.current = null
        if (unauthorizedHandler) {
          unauthorizedHandler()
        }
        return Promise.reject(refreshError)
      }
    }

    return Promise.reject(error)
  }
)