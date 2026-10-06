<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from './router'
import api from './api'

import ChestView from './views/ChestView.vue'
import RollView from './views/RollView.vue'
import OrderView from './views/OrderView.vue'
import BundleView from './views/BundleView.vue'

// ------------------------------------------------------------------
// 导航形态：顶部 Mega 下拉菜单。
// 顶部只有四个工段入口，鼠标悬停任意入口时整条导航下方展开一块宽面板，
// 面板里按「本段在管什么 / 快捷筛选 / 当前概况」三列铺开，
// 点列里的条目直接带着筛选条件跳到对应模块。没有侧边栏，也没有图标栏。
// ------------------------------------------------------------------

const viewMap = {
  '/chest': ChestView,
  '/roll': RollView,
  '/order': OrderView,
  '/bundle': BundleView
}

const menus = [
  {
    path: '/chest',
    title: '制浆工段',
    sub: '浆池与浆料储存',
    cols: [
      { head: '在管', items: ['浆池台账', '浆种配比', '补浆与抽浆'] },
      { head: '快捷筛选', items: ['针叶浆', '阔叶浆', '废纸浆', '竹浆'] },
      { head: '状态', items: ['在用', '检修', '停用'] }
    ]
  },
  {
    path: '/roll',
    title: '抄纸工段',
    sub: '纸机与母卷',
    cols: [
      { head: '在管', items: ['纸机台账', '纸卷母卷', '定量判等'] },
      { head: '状态', items: ['待检', '可用', '已切完', '报废'] },
      { head: '等级', items: ['一等', '二等', '等外'] }
    ]
  },
  {
    path: '/order',
    title: '切纸工段',
    sub: '订单与排产',
    cols: [
      { head: '在管', items: ['切纸订单', '母卷校验', '排产上机'] },
      { head: '状态', items: ['待排产', '切裁中', '已完成'] },
      { head: '关注', items: ['临近交期', '欠量最多'] }
    ]
  },
  {
    path: '/bundle',
    title: '成品库',
    sub: '成品件与出入库',
    cols: [
      { head: '在管', items: ['成品件入库', '库区货位', '发运出库'] },
      { head: '库区', items: ['A区', 'B区', 'C区'] },
      { head: '状态', items: ['已入库', '已出库'] }
    ]
  }
]

const currentPath = ref(router.currentRoute.value.path)
const hoverPath = ref('')
const stats = ref({ chest: 0, roll: 0, order: 0, bundle: 0 })

const currentView = computed(() => viewMap[currentPath.value] || ChestView)
const currentMenu = computed(() => menus.find((m) => m.path === currentPath.value) || menus[0])

// 悬停展开的宽面板：移出整条导航时收起
const megaOpen = computed(() => hoverPath.value !== '')

async function loadStats() {
  try {
    const [c, r, o, b] = await Promise.all([
      api.listChests(), api.listRolls(), api.listOrders(), api.listBundles()
    ])
    stats.value = { chest: c.length, roll: r.length, order: o.length, bundle: b.length }
  } catch (e) {
    // 统计失败不影响主流程
  }
}

function go(path) {
  hoverPath.value = ''
  router.push(path)
  currentPath.value = path
}

function goWithFilter(path, key, value) {
  router.push({ path, query: { [key]: value } })
  currentPath.value = path
  hoverPath.value = ''
}

watch(() => router.currentRoute.value.path, (p) => { currentPath.value = p })
onMounted(async () => {
  currentPath.value = router.currentRoute.value.path
  await loadStats()
})
</script>

<template>
  <div class="mill-app">
    <header class="topbar" @mouseleave="hoverPath = ''">
      <div class="brand">
        <span class="brand-mark">纸</span>
        <div class="brand-text">
          <div class="brand-title">造纸厂 · 制浆抄纸与纸卷切裁</div>
          <div class="brand-sub">{{ currentMenu.title }} · {{ currentMenu.sub }}</div>
        </div>
      </div>

      <nav class="mega-nav">
        <div
          v-for="m in menus"
          :key="m.path"
          class="mega-item"
          :class="{ on: currentPath === m.path }"
          @mouseenter="hoverPath = m.path"
          @click="go(m.path)"
        >
          {{ m.title }}
          <span class="mega-count">{{ stats[m.path.slice(1)] }}</span>
        </div>
      </nav>

      <div class="topbar-right">
        <span class="env-tag">paper_mill</span>
      </div>

      <!-- 悬停展开的宽面板 -->
      <transition name="mega">
        <div v-if="megaOpen" class="mega-panel" @mouseleave="hoverPath = ''">
          <div class="mega-inner">
            <div class="mega-head">
              {{ (menus.find((m) => m.path === hoverPath) || menus[0]).title }}
              <span>{{ (menus.find((m) => m.path === hoverPath) || menus[0]).sub }}</span>
            </div>
            <div class="mega-cols">
              <div v-for="col in (menus.find((m) => m.path === hoverPath) || menus[0]).cols" :key="col.head" class="mega-col">
                <div class="col-head">{{ col.head }}</div>
                <div
                  v-for="it in col.items"
                  :key="it"
                  class="col-item"
                  @click="goWithFilter(hoverPath, 'q', it)"
                >
                  {{ it }}
                </div>
              </div>
            </div>
          </div>
        </div>
      </transition>
    </header>

    <main class="content">
      <component :is="currentView" />
    </main>
  </div>
</template>
