import { createApp } from 'vue'
import {
  Alert,
  Avatar,
  Badge,
  Breadcrumb,
  Button,
  Checkbox,
  ConfigProvider,
  Descriptions,
  Dropdown,
  Empty,
  Form,
  Input,
  InputNumber,
  Layout,
  Menu,
  Modal,
  Pagination,
  Radio,
  Segmented,
  Select,
  Spin,
  Switch,
  Table,
  Tag,
} from 'ant-design-vue'
import 'ant-design-vue/dist/reset.css'
import './style.css'
import App from './App.vue'
import { createPinia } from 'pinia'
import { router } from './router'
import { clearCsrfToken, setUnauthorizedHandler } from './api/client'
import { setSessionCleanupHandler, useAuthStore } from './stores/auth'
import { resetSessionStores } from './stores/reset'

const app = createApp(App)
const pinia = createPinia()

app.use(pinia)
app.use(router)
setSessionCleanupHandler(() => {
  clearCsrfToken()
  resetSessionStores(pinia)
})
setUnauthorizedHandler(() => {
  useAuthStore(pinia).expireSession()
  const currentRoute = router.currentRoute.value
  if (currentRoute.name !== 'login') {
    void router.replace({
      name: 'login',
      query: { redirect: currentRoute.fullPath, expired: '1' },
    })
  }
})
;[
  Alert,
  Avatar,
  Badge,
  Breadcrumb,
  Button,
  Checkbox,
  ConfigProvider,
  Descriptions,
  Dropdown,
  Empty,
  Form,
  Input,
  InputNumber,
  Layout,
  Menu,
  Modal,
  Pagination,
  Radio,
  Segmented,
  Select,
  Spin,
  Switch,
  Table,
  Tag,
].forEach((component) => app.use(component))
app.mount('#app')
