<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：容量计量条 + 补浆滑杆。
// 每个浆池画成一条横向容量条（已存量深色填充、本次补浆半透明叠加、超限段红色），
// 补浆量不是手填数字，而是拖一根滑杆；滑杆一动，条上的预览段实时变化，
// 一旦存量 + 补浆量越过容量，整条变红且「补浆」按钮置灰。
// 全页没有一个弹窗，补浆/抽浆/转状态都是就地完成。
// ------------------------------------------------------------------

// 本仓 main.js 不安装 router 插件（路由由 App.vue 直接驱动），
// 所以不能用 useRoute()，改为直接读 router 实例上的当前路由。
const routeQuery = computed(() => router.currentRoute.value.query || {})
const chests = ref([])
const error = ref('')
const tip = ref('')
const keyword = ref('')
const selectedId = ref(null)
const amount = ref(10)
const busyId = ref(null)

const selected = computed(() => chests.value.find((c) => c.id === selectedId.value) || null)

// 本次补浆后的存量与是否超限
const preview = computed(() => {
  if (!selected.value) return null
  const after = Number(selected.value.currentM3) + Number(amount.value)
  const cap = Number(selected.value.capacityM3)
  return {
    after: after.toFixed(2),
    cap: cap.toFixed(2),
    over: after > cap,
    rate: Math.min(100, (after / cap) * 100)
  }
})

const canFill = computed(() => {
  if (!selected.value) return false
  if (selected.value.status !== '在用') return false
  return preview.value && !preview.value.over && Number(amount.value) > 0
})

async function load() {
  error.value = ''
  try {
    // q 可能来自顶部 Mega 面板的快捷筛选（浆种或状态）
    const q = routeQuery.value.q || keyword.value
    const data = await api.listChests(q ? { status: q } : {})
    chests.value = (data && data.length) ? data : (q ? await api.listChests() : data)
    if (!selectedId.value && chests.value.length) selectedId.value = chests.value[0].id
  } catch (e) {
    error.value = e.message
  }
}

async function fill() {
  if (!canFill.value) return
  error.value = ''; tip.value = ''
  busyId.value = selectedId.value
  try {
    const saved = await api.fillChest(selectedId.value, amount.value)
    tip.value = `已补浆 ${amount.value} 立方米，${saved.chestName} 当前 ${saved.currentM3} 立方米`
    await load()
  } catch (e) {
    error.value = e.message
  } finally {
    busyId.value = null
  }
}

async function draw() {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    const saved = await api.drawChest(selectedId.value, amount.value)
    tip.value = `已抽浆 ${amount.value} 立方米，剩余 ${saved.currentM3} 立方米`
    await load()
  } catch (e) {
    error.value = e.message
  }
}

async function transfer(target) {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    await api.transferChest(selectedId.value, target)
    tip.value = `${selected.value.chestName} 已转为「${target}」`
    await load()
  } catch (e) {
    error.value = e.message
  }
}

function barWidth(cur, cap) {
  return Math.min(100, (Number(cur) / Number(cap)) * 100) + '%'
}

watch(() => routeQuery.value.q, () => load())
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">制浆工段 · 浆池</span>
        <span class="panel-desc">拖滑杆定补浆量，容量条实时预览，越过容量会标红并锁住补浆</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">浆池总数</div><div class="v">{{ chests.length }}</div></div>
        <div class="stat"><div class="k">在用</div><div class="v">{{ chests.filter(c => c.status === '在用').length }}</div></div>
        <div class="stat"><div class="k">总存量(m³)</div>
          <div class="v">{{ chests.reduce((s, c) => s + Number(c.currentM3), 0).toFixed(1) }}</div>
        </div>
        <div class="stat"><div class="k">平均占用率</div>
          <div class="v">{{ chests.length ? (chests.reduce((s, c) => s + c.usageRate, 0) / chests.length).toFixed(1) : 0 }}%</div>
        </div>
      </div>

      <div class="chest-list">
        <div
          v-for="c in chests"
          :key="c.id"
          class="chest-row"
          :class="{ on: c.id === selectedId,停用: c.status === '停用' }"
          @click="selectedId = c.id"
        >
          <div class="chest-meta">
            <div class="chest-name">
              {{ c.chestName }}
              <span class="code">{{ c.chestCode }}</span>
            </div>
            <div class="chest-sub">{{ c.pulpType }} · 状态 {{ c.status }}</div>
          </div>

          <div class="meter" :class="{ over: c.id === selectedId && preview && preview.over }">
            <div class="meter-fill" :style="{ width: barWidth(c.current_m3 ?? c.currentM3, c.capacity_m3 ?? c.capacityM3) }">
              <span class="meter-text">{{ c.currentM3 }} / {{ c.capacityM3 }} m³</span>
            </div>
            <div
              v-if="c.id === selectedId"
              class="meter-preview"
              :style="{ width: Math.min(100 - Number(barWidth(c.currentM3, c.capacityM3).replace('%','')), (Number(amount) / Number(c.capacityM3)) * 100) + '%' }"
            ></div>
          </div>

          <div class="chest-rate">{{ c.usageRate }}%</div>
        </div>
      </div>
    </div>

    <div class="panel" v-if="selected">
      <div class="panel-head">
        <span class="panel-title">补浆 / 抽浆 · {{ selected.chestName }}</span>
        <span class="panel-desc">单次不超过 50 m³，且补浆后不得超过容量</span>
      </div>

      <div class="slider-row">
        <span class="slider-label">补浆量</span>
        <input type="range" min="1" max="60" step="1" v-model.number="amount" class="slider" />
        <span class="slider-value">{{ amount }} m³</span>
      </div>

      <div class="preview-line" :class="{ over: preview && preview.over }">
        <template v-if="preview">
          补浆后 <b>{{ preview.after }}</b> / {{ preview.cap }} m³
          <span v-if="preview.over" class="warn">超出容量 {{ (Number(preview.after) - Number(preview.cap)).toFixed(2) }} m³</span>
          <span v-else class="fine">可补</span>
        </template>
      </div>

      <div class="btn-row">
        <button class="act primary" :disabled="!canFill || busyId === selectedId" @click="fill">补浆</button>
        <button class="act" :disabled="selected.status !== '在用'" @click="draw">抽浆</button>
        <button class="act" @click="transfer('检修')">转检修</button>
        <button class="act" @click="transfer('停用')">停用</button>
        <button class="act" @click="transfer('在用')">转在用</button>
        <span v-if="selected.status !== '在用'" class="hint">非「在用」浆池不能补浆 / 抽浆</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.chest-list { display: flex; flex-direction: column; gap: 8px; }

.chest-row {
  display: grid;
  grid-template-columns: 260px 1fr 62px;
  align-items: center;
  gap: 14px;
  padding: 10px 12px;
  border: 1px solid var(--line);
  border-radius: 9px;
  cursor: pointer;
  background: #fff;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.chest-row:hover { border-color: var(--primary-soft); }
.chest-row.on { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }
.chest-row.停用 { opacity: 0.62; }

.chest-name { font-weight: 700; color: var(--primary-deep); }
.chest-name .code { font-size: 11px; color: var(--muted); margin-left: 6px; font-weight: 500; }
.chest-sub { font-size: 12px; color: var(--muted); margin-top: 2px; }

.meter {
  position: relative;
  height: 26px;
  background: #eef3f2;
  border-radius: 6px;
  overflow: hidden;
  border: 1px solid var(--line);
}
.meter-fill {
  height: 100%;
  background: linear-gradient(90deg, var(--primary), var(--primary-soft));
  display: flex; align-items: center; justify-content: flex-end;
  padding-right: 8px;
  transition: width 0.2s;
}
.meter-text { font-size: 11px; color: #fff; font-weight: 700; white-space: nowrap; }
.meter-preview {
  position: absolute; top: 0; height: 100%;
  background: repeating-linear-gradient(45deg, rgba(255,193,7,0.75), rgba(255,193,7,0.75) 6px, rgba(255,193,7,0.35) 6px, rgba(255,193,7,0.35) 12px);
}
.meter.over .meter-fill { background: linear-gradient(90deg, #c62828, #e53935); }
.meter.over { border-color: #c62828; }

.chest-rate { text-align: right; font-weight: 800; color: var(--primary-deep); font-size: 13px; }

.slider-row { display: flex; align-items: center; gap: 14px; margin-bottom: 10px; }
.slider-label { font-size: 13px; color: var(--muted); width: 56px; }
.slider { flex: 1; accent-color: var(--primary); }
.slider-value { font-weight: 800; color: var(--primary-deep); width: 64px; text-align: right; }

.preview-line { font-size: 13px; color: var(--muted); margin-bottom: 12px; }
.preview-line b { color: var(--primary-deep); font-size: 15px; }
.preview-line .fine { color: #2e7d32; margin-left: 8px; font-weight: 700; }
.preview-line .warn { color: #c62828; margin-left: 8px; font-weight: 700; }
.preview-line.over b { color: #c62828; }

.btn-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
.act {
  padding: 7px 14px; border-radius: 7px; cursor: pointer; font-size: 13px;
  border: 1px solid var(--line); background: #fff; color: var(--ink);
}
.act:hover { border-color: var(--primary-soft); color: var(--primary-deep); }
.act.primary { background: var(--primary); border-color: var(--primary); color: #fff; font-weight: 700; }
.act.primary:hover { background: var(--primary-deep); color: #fff; }
.act:disabled { opacity: 0.45; cursor: not-allowed; }
.hint { font-size: 12px; color: #c62828; }
</style>
