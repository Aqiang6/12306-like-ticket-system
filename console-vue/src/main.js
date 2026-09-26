import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import 'dayjs/locale/zh-cn'
import dayjs from 'dayjs'
import '@/styles/main.css'

dayjs.locale('zh-cn')

const app = createApp(App)

app.config.errorHandler = (err) => {
  console.error('Vue Error:', err)
}

app.use(router).mount('#app')
