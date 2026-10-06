<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：库区货位网格 + 勾选批量出库。
// A / B / C 三个库区各是一块货位网格，每个成品件占一个格子（已出库灰掉）。
// 格子上直接勾选，选中的格子会在底部浮出一条操作条显示「已选 N 件 / 合计重量」，
// 点一次「批量出库」整批发运；入库走上方一条常驻录入条。
// ------------------------------------------------------------------

// 本仓 main.js 不安装 router 插件，直接读 router 实例上的当前路由
const routeQuery = computed(() => router.currentRoute.value.query || {})
const bundles = ref([])
const orders = ref([])
const stock = ref([])
const error = ref('')
const tip = ref('')
const checked = ref([])
const inbound = ref({ bundleNo: '', orderId: null, grammageGsm: null, widthMm: null, sheetCount: null, weightKg: null, warehouseZone: 'A区' })

const zones = ['A区', 'B区', 'C区']

const selectedWeight = computed(() =>
  bundles.value.filter((b) => checked.value.includes(b.id)).reduce((s, b) => s + Number(b.weightKg), 0).toFixed(2)
)

const byZone = computed(() => {
  const map = {}
  for (const z of zones) map[z] = bundles.value.filter((b) => b.warehouseZone === z)
  return map
})

async function load() {
  error.value = ''
  try {
    const q = routeQuery.value.q
    bundles.value = await api.listBundles(q ? { zone: q } : {})
    if (!bundles.value.length && q) bundles.value = await api.listBundles()
    orders.value = await api.listOrders()
    stock.value = await api.stockByZone()
    checked.value = checked.value.filter((id) => bundles.value.some((b) => b.id === id && b.status === '已入库'))
  } catch (e) {
    error.value = e.message
  }
}

async function doInbound() {
  error.value = ''; tip.value = ''
  try {
    const payload = { ...inbound.value, order: { id: inbound.value.orderId } }
    await api.inboundBundle(payload)
    tip.value = `成品件 ${inbound.value.bundleNo} 已入库到 ${inbound.value.warehouseZone}`
    inbound.value = { bundleNo: '', orderId: null, grammageGsm: null, widthMm: null, sheetCount: null, weightKg: null, warehouseZone: 'A区' }
    await load()
  } catch (e) { error.value = e.message }
}

async function outboundOne(id) {
  error.value = ''; tip.value = ''
  try {
    await api.outboundBundle(id)
    tip.value = '已出库'
    await load()
  } catch (e) { error.value = e.message }
}

async function outboundBatch() {
  if (!checked.value.length) return
  error.value = ''; tip.value = ''
  let ok = 0
  const fails = []
  for (const id of checked.value) {
    try { await api.outboundBundle(id); ok++ } catch (e) { fails.push(e.message) }
  }
  tip.value = `批量出库完成：成功 ${ok} 件` + (fails.length ? `，失败 ${fails.length} 件（${fails[0]}）` : '')
  checked.value = []
  await load()
}

function toggle(id, status) {
  if (status !== '已入库') return
  const i = checked.value.indexOf(id)
  if (i >= 0) checked.value.splice(i, 1)
  else checked.value.push(id)
}

const anyChecked = computed(() => checked.value.length > 0)

watch(() => routeQuery.value.q, () => load())
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">成品库 · 成品件入库</span>
        <span class="panel-desc">规格必须与订单目标一致，入库重量不得超过订单欠量</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="inbound-row">
        <input v-model="inbound.bundleNo" class="fld" placeholder="件号 FB-…" />
        <select v-model="inbound.orderId" class="fld">
          <option :value="null">选择订单</option>
          <option v-for="o in orders" :key="o.id" :value="o.id">
            {{ o.orderNo }} · {{ o.customerName }}（欠 {{ o.gapWeightKg }}kg）
          </option>
        </select>
        <input v-model.number="inbound.grammageGsm" class="fld n" placeholder="定量 g" />
        <input v-model.number="inbound.widthMm" class="fld n" placeholder="幅宽 mm" />
        <input v-model.number="inbound.sheetCount" class="fld n" placeholder="张数" />
        <input v-model.number="inbound.weightKg" class="fld n" placeholder="重量 kg" />
        <select v-model="inbound.warehouseZone" class="fld">
          <option v-for="z in zones" :key="z" :value="z">{{ z }}</option>
        </select>
        <button class="act primary" @click="doInbound">入库</button>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">库区货位</span>
        <span class="panel-desc">点格子勾选，底部浮条批量出库</span>
      </div>

      <div class="stat-row">
        <div v-for="s in stock" :key="s.zone" class="stat">
          <div class="k">{{ s.zone }} 在库</div>
          <div class="v">{{ s.bundleCount }} 件</div>
        </div>
      </div>

      <div class="zone-wrap">
        <div v-for="z in zones" :key="z" class="zone">
          <div class="zone-head">{{ z }}<span>{{ (byZone[z] || []).length }} 件</span></div>
          <div class="slot-grid">
            <div
              v-for="b in (byZone[z] || [])"
              :key="b.id"
              class="slot"
              :class="{ picked: checked.includes(b.id), out: b.status === '已出库' }"
              @click="toggle(b.id, b.status)"
            >
              <div class="slot-no">{{ b.bundleNo }}</div>
              <div class="slot-spec">{{ b.grammageGsm }}g · {{ b.widthMm }}mm</div>
              <div class="slot-w">{{ b.weightKg }}kg</div>
              <div class="slot-tag">{{ b.status }}</div>
              <span v-if="checked.includes(b.id)" class="slot-tick">✓</span>
            </div>
            <div v-if="!(byZone[z] || []).length" class="zone-empty">本区暂无成品件</div>
          </div>
        </div>
      </div>
    </div>

    <!-- 底部浮条：勾选后出现 -->
    <transition name="bar">
      <div v-if="anyChecked" class="float-bar">
        <span class="fb-text">已选 <b>{{ checked.length }}</b> 件 · 合计 <b>{{ selectedWeight }}</b> kg</span>
        <button class="act light" @click="checked = []">清空</button>
        <button class="act primary" @click="outboundBatch">批量出库</button>
      </div>
    </transition>
  </div>
</template>

<style scoped>
.inbound-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
.fld { padding: 7px 9px; border: 1px solid var(--line); border-radius: 7px; font-size: 13px; background: #fff; }
.fld.n { width: 92px; }

.zone-wrap { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.zone { border: 1px solid var(--line); border-radius: 9px; overflow: hidden; }
.zone-head {
  background: var(--primary-wash); color: var(--primary-deep);
  font-weight: 800; font-size: 13px; padding: 7px 10px;
  display: flex; justify-content: space-between;
}
.zone-head span { font-weight: 600; font-size: 12px; color: var(--primary-soft); }

.slot-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 7px; padding: 9px; min-height: 84px; }
.zone-empty { grid-column: 1 / -1; font-size: 12px; color: var(--muted); padding: 8px; }

.slot {
  position: relative;
  border: 1px solid var(--line); border-radius: 8px;
  padding: 8px; cursor: pointer; background: #fff;
  transition: border-color 0.14s, box-shadow 0.14s;
}
.slot:hover { border-color: var(--primary-soft); }
.slot.picked { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); background: #f4fbf9; }
.slot.out { opacity: 0.48; cursor: not-allowed; background: #f2f4f3; }
.slot-no { font-weight: 800; font-size: 12px; color: var(--primary-deep); }
.slot-spec { font-size: 11px; color: var(--muted); margin-top: 2px; }
.slot-w { font-size: 13px; font-weight: 700; margin-top: 3px; }
.slot-tag { font-size: 10px; color: var(--primary-soft); }
.slot-tick { position: absolute; top: 4px; right: 6px; color: var(--primary); font-weight: 800; }

.float-bar {
  position: fixed; left: 50%; bottom: 22px; transform: translateX(-50%);
  display: flex; align-items: center; gap: 12px;
  padding: 10px 18px; border-radius: 26px;
  background: var(--primary-deep); color: #fff;
  box-shadow: 0 8px 24px rgba(0, 0, 0, 0.28);
  z-index: 60;
}
.fb-text { font-size: 13px; }
.fb-text b { font-size: 15px; }
.act.light { background: rgba(255,255,255,0.16); border-color: rgba(255,255,255,0.3); color: #fff; }
.act.light:hover { background: rgba(255,255,255,0.28); color: #fff; }

.bar-enter-active, .bar-leave-active { transition: opacity 0.16s, transform 0.16s; }
.bar-enter-from, .bar-leave-to { opacity: 0; transform: translate(-50%, 8px); }
</style>
