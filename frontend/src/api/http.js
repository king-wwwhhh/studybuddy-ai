import axios from 'axios'

/**
 * 统一请求封装：
 * - 自动带 Bearer token（存 localStorage，同时后端也写了 HttpOnly Cookie 作为兜底）
 * - 401 自动跳登录，403 提示无权
 */
const http = axios.create({ baseURL: '/api', timeout: 30000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (resp) => resp,
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      localStorage.removeItem('token')
      if (!location.hash.includes('/login')) location.hash = '#/login'
    }
    return Promise.reject(error)
  }
)

export default http
