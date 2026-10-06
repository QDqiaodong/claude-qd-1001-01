import { createRouter, createWebHashHistory } from 'vue-router'

// 注意：main.js 里没有 app.use(router)，路由实例由 App.vue 直接驱动，
// 所以这里全部用静态 import —— <component :is> 拿到的是组件对象本身。
import ChestView from '../views/ChestView.vue'
import RollView from '../views/RollView.vue'
import OrderView from '../views/OrderView.vue'
import BundleView from '../views/BundleView.vue'

const routes = [
  { path: '/', redirect: '/chest' },
  { path: '/chest', name: 'chest', component: ChestView },
  { path: '/roll', name: 'roll', component: RollView },
  { path: '/order', name: 'order', component: OrderView },
  { path: '/bundle', name: 'bundle', component: BundleView },
  { path: '/:pathMatch(.*)*', redirect: '/chest' }
]

export default createRouter({
  history: createWebHashHistory(),
  routes
})
