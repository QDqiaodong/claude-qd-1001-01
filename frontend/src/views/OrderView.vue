<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：进度环 + 母卷匹配清单打勾 + 切裁领料闭环。
// 每张订单是一枚环形进度（完成率）卡片，选中后右侧列出所有候选母卷，
// 每一条右侧直接给出「✓ 可上机」或「✗ 幅宽不足 / 定量偏差 / 状态不符」的打勾结论
// （结论由后端校验接口算，不是前端猜的）；勾中的母卷点一下就排产上机。
// 订单进入「切裁中」后，下方出现领料登记：可领上限来自后端真实余量，提交带
// requestToken 幂等令牌，成功在同一业务动作内扣减母卷余量并留痕，失败原因落库，
// 订单详情与母卷导轨都能追溯每一次领料。
// ------------------------------------------------------------------

// 本仓 main.js 不安装 router 插件，直接读 router 实例上的当前路由
const routeQuery = computed(() => router.currentRoute.value.query || {})
const orders = ref([])
const rolls = ref([])
const error = ref('')
const tip = ref('')
const selectedId = ref(null)
const verdicts = ref({})
const checking = ref(false)

// ---------------- 切裁领料闭环状态 ----------------
const requisitions = ref([])      // 本订单成功领料账本（只增不改）
const attempts = ref([])          // 本订单领料留痕（含失败原因，刷新仍可见）
const reqRollNo = ref('')
const reqLength = ref(100)
const reqWeight = ref(0)
const operator = ref(localStorage.getItem('paper_mill_operator') || '')
const submitting = ref(false)     // 提交中锁定
const reqToken = ref('')          // 一次点击一个令牌；网络未确认时保留以便同令牌重试
const reqError = ref('')
const reqTip = ref('')

const selected = computed(() => orders.value.find((o) => o.id === selectedId.value) || null)

// 候选母卷：按订单目标定量筛选
const candidates = computed(() => {
  if (!selected.value) return []
  return rolls.value.filter(
    (r) => Math.abs(r.grammageGsm - selected.value.targetGrammage) <= 12
  )
})

// 可领母卷（后端校验通过：可用 + 幅宽够 + 定量匹配）
const requirable = computed(() =>
  candidates.value.filter((r) => verdicts.value[r.rollNo] && verdicts.value[r.rollNo].ok)
)

// 选中待领母卷的后端校验结论（里面有真实余量与可领上限）
const reqVerdict = computed(() => verdicts.value[reqRollNo.value] || null)

// 最近一次失败留痕（持久化在后端，刷新/重开页面仍显示）
const lastFail = computed(() => attempts.value.find((a) => a.result === 'FAILED') || null)

const overdue = computed(() =>
  orders.value.filter((o) => o.daysToDeadline !== null && o.daysToDeadline < 0).length
)

async function load() {
  error.value = ''
  try {
    const q = routeQuery.value.q
    orders.value = await api.listOrders(q ? { status: q } : {})
    if (!orders.value.length && q) orders.value = await api.listOrders()
    rolls.value = await api.listRolls()
    if (!selectedId.value && orders.value.length) selectedId.value = orders.value[0].id
    await runCheck()
    await loadRequisitionData()
  } catch (e) {
    error.value = e.message
  }
}

// 逐个候选母卷问后端校验结论（含可领上限）
async function runCheck() {
  if (!selected.value) { verdicts.value = {}; return }
  checking.value = true
  const out = {}
  for (const r of candidates.value) {
    try {
      const v = await api.checkRoll(selected.value.id, r.rollNo)
      out[r.rollNo] = v
    } catch (e) {
      out[r.rollNo] = { ok: false, problems: [e.message] }
    }
  }
  verdicts.value = out
  checking.value = false
  // 默认选中第一卷可领母卷；原先选中的卷仍可领则保持不变
  if (!requirable.value.some((r) => r.rollNo === reqRollNo.value)) {
    reqRollNo.value = requirable.value.length ? requirable.value[0].rollNo : ''
  }
}

async function loadRequisitionData() {
  if (!selected.value) { requisitions.value = []; attempts.value = []; return }
  try {
    const [ledger, logs] = await Promise.all([
      api.orderRequisitions(selected.value.id),
      api.orderRequisitionAttempts(selected.value.id)
    ])
    requisitions.value = ledger
    attempts.value = logs
  } catch (e) {
    // 追溯数据加载失败不阻断主页面
    requisitions.value = []
    attempts.value = []
  }
}

async function assign(rollNo) {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    await api.assignRoll(selected.value.id, rollNo)
    tip.value = `订单 ${selected.value.orderNo} 已排产，母卷 ${rollNo} 上机`
    await load()
  } catch (e) { error.value = e.message }
}

async function transfer(target) {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    await api.transferOrder(selected.value.id, target)
    tip.value = `订单 ${selected.value.orderNo} 已转为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

// 按长度折算建议重量：kg = L(m) × 定量(g/m2) × 幅宽(mm) / 1e6
function suggestWeight() {
  const roll = rolls.value.find((r) => r.rollNo === reqRollNo.value)
  if (!roll || !reqLength.value) return
  const kg = (Number(reqLength.value) * roll.grammageGsm * roll.widthMm) / 1e6
  reqWeight.value = Math.round(kg * 100) / 100
}

// 折算值仅为便利填写，最终以后端按真实余量校验为准
watch(reqRollNo, () => suggestWeight())
watch(reqLength, () => suggestWeight())

// ---------------- 切裁领料提交（幂等 + 锁定） ----------------
function newToken() {
  const raw = (typeof crypto !== 'undefined' && crypto.randomUUID)
    ? crypto.randomUUID()
    : `t-${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `web-${raw}`
}

async function submitRequisition() {
  if (!selected.value || submitting.value) return
  reqError.value = ''; reqTip.value = ''

  // ---- 前端只做体验性预校验，真正的闸在后端事务里 ----
  const v = reqVerdict.value
  if (!reqRollNo.value) { reqError.value = '请选择要领的母卷'; return }
  if (!v || !v.ok) { reqError.value = `该母卷当前不可领：${(v && v.problems || []).join('；') || '请重新选择'}`; return }
  if (!(Number(reqLength.value) > 0)) { reqError.value = '本次领料长度必须大于 0'; return }
  if (Number(reqLength.value) > v.maxTakeLengthM) {
    reqError.value = `本次领料长度 ${reqLength.value} 米超过可领上限 ${v.maxTakeLengthM} 米（母卷真实余量）`
    return
  }
  if (!(Number(reqWeight.value) > 0)) { reqError.value = '本次领料重量必须大于 0'; return }
  if (Number(reqWeight.value) > Number(v.maxTakeWeightKg)) {
    reqError.value = `本次领料重量 ${reqWeight.value} 千克超过可领上限 ${v.maxTakeWeightKg} 千克（母卷真实余量）`
    return
  }
  if (!operator.value.trim()) { reqError.value = '请填写操作人'; return }

  // 一次点击领一个令牌；网络未确认结果时沿用旧令牌，由后端幂等返回原结果
  if (!reqToken.value) reqToken.value = newToken()
  submitting.value = true
  try {
    const data = await api.submitRequisition(selected.value.id, {
      requestToken: reqToken.value,
      rollNo: reqRollNo.value,
      lengthM: Number(reqLength.value),
      weightKg: Number(reqWeight.value),
      operator: operator.value.trim()
    })
    localStorage.setItem('paper_mill_operator', operator.value.trim())
    const r = data.requisition || {}
    reqTip.value = (data.replayed ? '重复请求，返回的是原结果（未重复扣减）：' : '领料已登记：')
      + `${r.requisitionNo}，母卷 ${reqRollNo.value} 新余量 ${data.remainLengthM}m / ${data.remainWeightKg}kg`
      + (data.rollStatus === '已切完' ? '；余长低于 200m 阈值，母卷已同步转为「已切完」' : '')
    reqToken.value = ''
    await load()
  } catch (e) {
    if (e.networkError) {
      // 请求是否到达/是否已扣减无法确认：保留同一令牌，让操作员原样重试，后端只会返回原结果
      reqError.value = `${e.message}（请求结果未确认，已锁定本次提交，请点「原样重试」；后端按同一令牌幂等，不会重复领料或重复扣量）`
    } else {
      // 明确的业务失败：原因已在后端留痕。换新点击、刷新真实余量与失败原因
      reqError.value = e.message
      reqToken.value = ''
      await load()
    }
  } finally {
    submitting.value = false
  }
}

// 网络未确认后的原样重试：沿用同一令牌
async function retryRequisition() {
  if (submitting.value) return
  await submitRequisition()
}

function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : ''
}

function ringStyle(rate) {
  const deg = Math.min(360, (Number(rate) / 100) * 360)
  return { background: `conic-gradient(var(--primary) ${deg}deg, #e6eeec ${deg}deg)` }
}

watch(() => routeQuery.value.q, () => load())
watch(selectedId, () => {
  // 切单即视为一次新的操作上下文：清掉提交中锁定/未确认令牌/提示，防止残留状态串单
  submitting.value = false
  reqToken.value = ''
  reqError.value = ''
  reqTip.value = ''
  runCheck()
  loadRequisitionData()
})
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">切纸工段 · 订单与排产</span>
        <span class="panel-desc">环内是完成率；选中订单后右侧母卷清单会给出可否上机的打勾结论</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">订单</div><div class="v">{{ orders.length }}</div></div>
        <div class="stat"><div class="k">切裁中</div><div class="v">{{ orders.filter(o => o.status === '切裁中').length }}</div></div>
        <div class="stat"><div class="k">待排产</div><div class="v">{{ orders.filter(o => o.status === '待排产').length }}</div></div>
        <div class="stat"><div class="k">已逾期</div><div class="v">{{ overdue }}</div></div>
      </div>

      <div class="order-grid">
        <div
          v-for="o in orders"
          :key="o.id"
          class="order-card"
          :class="{ on: o.id === selectedId }"
          @click="selectedId = o.id"
        >
          <div class="ring" :style="ringStyle(o.progressRate)">
            <div class="ring-inner">
              <div class="ring-v">{{ o.progressRate }}%</div>
              <div class="ring-k">完成</div>
            </div>
          </div>
          <div class="order-info">
            <div class="order-no">{{ o.orderNo }}</div>
            <div class="order-cust">{{ o.customerName }}</div>
            <div class="order-spec">{{ o.targetGrammage }}g · {{ o.targetWidthMm }}mm · {{ o.targetWeightKg }}kg</div>
            <div class="order-meta">
              <span class="tag">{{ o.status }}</span>
              <span class="gap">欠 {{ o.gapWeightKg }}kg</span>
            </div>
          </div>
        </div>
      </div>
    </div>

    <div class="panel" v-if="selected">
      <div class="panel-head">
        <span class="panel-title">母卷校验 · {{ selected.orderNo }}</span>
        <span class="panel-desc">目标 {{ selected.targetGrammage }}g / {{ selected.targetWidthMm }}mm</span>
        <span v-if="checking" class="checking">校验中…</span>
      </div>

      <div class="roll-check">
        <div
          v-for="r in candidates"
          :key="r.rollNo"
          class="check-row"
          :class="{ good: verdicts[r.rollNo] && verdicts[r.rollNo].ok, bad: verdicts[r.rollNo] && !verdicts[r.rollNo].ok }"
        >
          <span class="tick">{{ verdicts[r.rollNo] ? (verdicts[r.rollNo].ok ? '✓' : '✗') : '…' }}</span>
          <span class="rno">{{ r.rollNo }}</span>
          <span class="rspec">{{ r.grammageGsm }}g · {{ r.widthMm }}mm · 余 {{ r.lengthM }}m / {{ r.weightKg }}kg</span>
          <span class="rstate">{{ r.status }}</span>
          <span class="rwhy">
            {{ verdicts[r.rollNo] && verdicts[r.rollNo].problems && verdicts[r.rollNo].problems.length
               ? verdicts[r.rollNo].problems.join('；') : '可上机 / 可领料' }}
          </span>
          <button
            class="act sm"
            :disabled="!verdicts[r.rollNo] || !verdicts[r.rollNo].ok || selected.status !== '待排产'"
            @click="assign(r.rollNo)"
          >排产</button>
        </div>
        <div v-if="!candidates.length" class="empty">没有定量接近的候选母卷</div>
      </div>

      <div class="op-row">
        <button class="act primary" :disabled="selected.status !== '待排产'" @click="transfer('切裁中')">推进到切裁中</button>
        <button class="act" :disabled="selected.status !== '切裁中'" @click="transfer('已完成')">标记已完成</button>
        <button class="act" @click="transfer('已取消')">取消订单</button>
      </div>
    </div>

    <!-- ============ 切裁领料登记（闭环：订单 + 记录 + 余量 同一业务动作） ============ -->
    <div class="panel" v-if="selected && selected.status === '切裁中'">
      <div class="panel-head">
        <span class="panel-title">切裁领料登记 · {{ selected.orderNo }}</span>
        <span class="panel-desc">
          从已排产且仍「可用」、规格匹配的母卷领料；提交后在同一动作内扣减余长与余重，
          余长低于 200m 母卷自动转「已切完」
        </span>
      </div>

      <div v-if="reqError" class="err">
        {{ reqError }}
        <button v-if="reqToken" class="act sm retry-btn" :disabled="submitting" @click="retryRequisition">
          {{ submitting ? '重试中…' : '原样重试（同一令牌）' }}
        </button>
      </div>
      <div v-if="reqTip" class="ok-tip">{{ reqTip }}</div>

      <div class="req-form">
        <label class="fld">
          <span class="fld-k">领料母卷</span>
          <select v-model="reqRollNo" class="inp" :disabled="submitting">
            <option value="" disabled>请选择母卷</option>
            <option
              v-for="r in candidates" :key="r.rollNo" :value="r.rollNo"
              :disabled="!(verdicts[r.rollNo] && verdicts[r.rollNo].ok)"
            >
              {{ r.rollNo }} · {{ r.grammageGsm }}g/{{ r.widthMm }}mm · 余 {{ r.lengthM }}m
              {{ verdicts[r.rollNo] && verdicts[r.rollNo].ok ? '' : '（不可领）' }}
            </option>
          </select>
        </label>

        <div class="limit-box" v-if="reqVerdict">
          <template v-if="reqVerdict.ok">
            <div class="limit-line">可领上限（母卷真实余量，提交前看清）：</div>
            <div class="limit-v">长度 ≤ <b>{{ reqVerdict.maxTakeLengthM }}</b> 米</div>
            <div class="limit-v">重量 ≤ <b>{{ reqVerdict.maxTakeWeightKg }}</b> 千克</div>
            <div class="limit-note">当前母卷状态：{{ reqVerdict.status }}</div>
          </template>
          <template v-else>
            <div class="limit-line bad">该母卷当前不可领：{{ (reqVerdict.problems || []).join('；') }}</div>
          </template>
        </div>

        <label class="fld">
          <span class="fld-k">本次长度（米）</span>
          <input v-model.number="reqLength" type="number" min="1" step="1" class="inp sm" :disabled="submitting" />
        </label>
        <label class="fld">
          <span class="fld-k">本次重量（千克）</span>
          <input v-model.number="reqWeight" type="number" min="0.01" step="0.01" class="inp sm" :disabled="submitting" />
        </label>
        <label class="fld">
          <span class="fld-k">操作人</span>
          <input v-model="operator" maxlength="32" class="inp" placeholder="当班操作员姓名/工号" :disabled="submitting" />
        </label>

        <div class="req-actions">
          <button
            class="act primary"
            :disabled="submitting || !reqRollNo || !(reqVerdict && reqVerdict.ok) || !operator.trim()"
            @click="submitRequisition"
          >
            {{ submitting ? '提交中…已锁定，请勿重复点击' : (reqToken ? '原样重试（同一令牌）' : '登记领料并扣减余量') }}
          </button>
          <span v-if="submitting" class="locking">正在由后端原子完成：校验 → 写领料记录 → 扣减余量，期间重复点击只会返回同一结果</span>
        </div>
        <div class="rule-note">
          后端在同一事务内强校验：订单须为「切裁中」、母卷须为「可用」且规格仍匹配（幅宽≥目标、定量偏差≤3）、
          长度/重量为正且不超过余量；任一不满足只返回原因、不写记录不动量。扣后余长低于 200m 母卷自动转「已切完」。
        </div>
      </div>

      <div v-if="lastFail" class="fail-persist">
        <span class="fp-tag">最近一次领料失败（已留痕，重开页面仍在）</span>
        <span class="fp-time">{{ fmtTime(lastFail.attemptedAt) }} · {{ lastFail.operator || '未登记操作人' }} · 母卷 {{ lastFail.rollNo || '-' }}</span>
        <div class="fp-reason">{{ lastFail.failReason }}</div>
      </div>
    </div>

    <!-- ============ 领料追溯：订单详情侧（成功记录不随状态变化消失） ============ -->
    <div class="panel" v-if="selected">
      <div class="panel-head">
        <span class="panel-title">领料追溯 · {{ selected.orderNo }}</span>
        <span class="panel-desc">
          共 {{ requisitions.length }} 次成功领料；即使母卷已切完、订单已完成/取消，账本仍保留
        </span>
      </div>

      <div v-if="!requisitions.length" class="empty">本订单还没有领料记录</div>
      <table v-else class="ledger">
        <thead>
          <tr>
            <th>领料单号</th><th>母卷</th><th>本次长度(m)</th><th>本次重量(kg)</th>
            <th>操作时间</th><th>操作人</th><th>扣后余长(m)</th><th>扣后余重(kg)</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="q in requisitions" :key="q.id">
            <td class="mono">{{ q.requisitionNo }}</td>
            <td>{{ q.rollNo }}</td>
            <td>{{ q.lengthM }}</td>
            <td>{{ q.weightKg }}</td>
            <td>{{ fmtTime(q.operatedAt) }}</td>
            <td>{{ q.operator }}</td>
            <td>{{ q.remainLengthM }}</td>
            <td>{{ q.remainWeightKg }}</td>
          </tr>
        </tbody>
      </table>

      <details v-if="attempts.some(a => a.result === 'FAILED')" class="attempts">
        <summary>查看领料失败留痕（{{ attempts.filter(a => a.result === 'FAILED').length }}）</summary>
        <table class="ledger fail">
          <thead>
            <tr><th>时间</th><th>母卷</th><th>长度(m)</th><th>重量(kg)</th><th>操作人</th><th>失败原因</th></tr>
          </thead>
          <tbody>
            <tr v-for="a in attempts.filter(a => a.result === 'FAILED')" :key="a.id">
              <td>{{ fmtTime(a.attemptedAt) }}</td>
              <td>{{ a.rollNo || '-' }}</td>
              <td>{{ a.lengthM ?? '-' }}</td>
              <td>{{ a.weightKg ?? '-' }}</td>
              <td>{{ a.operator || '-' }}</td>
              <td class="reason">{{ a.failReason }}</td>
            </tr>
          </tbody>
        </table>
      </details>
    </div>
  </div>
</template>

<style scoped>
.order-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 10px; }

.order-card {
  display: flex; gap: 12px; align-items: center;
  padding: 12px; border: 1px solid var(--line); border-radius: 10px;
  cursor: pointer; background: #fff;
}
.order-card:hover { border-color: var(--primary-soft); }
.order-card.on { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }

.ring { width: 66px; height: 66px; border-radius: 50%; display: flex; align-items: center; justify-content: center; flex: none; }
.ring-inner {
  width: 50px; height: 50px; border-radius: 50%; background: #fff;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.ring-v { font-size: 14px; font-weight: 800; color: var(--primary-deep); }
.ring-k { font-size: 10px; color: var(--muted); }

.order-no { font-weight: 800; color: var(--primary-deep); }
.order-cust { font-size: 12px; color: var(--ink); margin-top: 1px; }
.order-spec { font-size: 12px; color: var(--muted); margin-top: 2px; }
.order-meta { margin-top: 5px; display: flex; gap: 8px; align-items: center; }
.tag { font-size: 11px; padding: 1px 8px; border-radius: 9px; background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
.gap { font-size: 11px; color: #c62828; }

.checking { margin-left: auto; font-size: 12px; color: var(--muted); }

.roll-check { display: flex; flex-direction: column; gap: 6px; margin-bottom: 12px; }
.check-row {
  display: grid; grid-template-columns: 26px 110px 1fr 70px 1fr 70px;
  align-items: center; gap: 10px;
  padding: 8px 10px; border: 1px solid var(--line); border-radius: 8px; font-size: 13px;
}
.check-row.good { border-color: #a5d6a7; background: #f1f8f2; }
.check-row.bad { border-color: #efc9c5; background: #fdf3f2; }
.tick { font-weight: 800; text-align: center; }
.check-row.good .tick { color: #2e7d32; }
.check-row.bad .tick { color: #c62828; }
.rno { font-weight: 700; color: var(--primary-deep); }
.rspec, .rstate { color: var(--muted); font-size: 12px; }
.rwhy { font-size: 12px; color: var(--ink); }

.empty { font-size: 13px; color: var(--muted); padding: 10px; }
.op-row { display: flex; gap: 8px; flex-wrap: wrap; }
.act {
  padding: 7px 14px; border-radius: 7px; cursor: pointer; font-size: 13px;
  border: 1px solid var(--line); background: #fff; color: var(--ink);
}
.act.sm { padding: 4px 10px; font-size: 12px; }
.act:hover { border-color: var(--primary-soft); color: var(--primary-deep); }
.act.primary { background: var(--primary); border-color: var(--primary); color: #fff; font-weight: 700; }
.act:disabled { opacity: 0.45; cursor: not-allowed; }

/* ---------------- 切裁领料表单 ---------------- */
.req-form {
  display: flex; align-items: flex-end; gap: 14px; flex-wrap: wrap;
  padding: 12px; border: 1px solid var(--line); border-radius: 8px; background: #fbfdfc;
}
.fld { display: flex; flex-direction: column; gap: 4px; }
.fld-k { font-size: 12px; color: var(--muted); font-weight: 700; }
.inp { padding: 7px 9px; border: 1px solid var(--line); border-radius: 6px; font-size: 13px; min-width: 200px; }
.inp.sm { min-width: 130px; }
.inp:disabled { background: #f0f3f2; cursor: not-allowed; }

.limit-box {
  flex-basis: 100%;
  display: flex; gap: 16px; align-items: center; flex-wrap: wrap;
  background: var(--primary-wash); border: 1px solid #cde6e2; border-radius: 8px;
  padding: 8px 12px; font-size: 13px;
}
.limit-line { font-weight: 700; color: var(--primary-deep); }
.limit-line.bad { color: #a32b20; }
.limit-v { color: var(--ink); }
.limit-v b { color: var(--primary-deep); font-size: 15px; }
.limit-note { color: var(--muted); font-size: 12px; }

.req-actions { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.locking { font-size: 12px; color: var(--muted); }
.rule-note {
  flex-basis: 100%; font-size: 12px; color: var(--muted);
  padding-top: 8px; border-top: 1px dashed var(--line); line-height: 1.6;
}
.retry-btn { margin-left: 10px; }

.fail-persist {
  margin-top: 10px; padding: 8px 12px;
  background: #fdecea; border: 1px solid #f5c6c2; border-radius: 8px; font-size: 12px;
}
.fp-tag { font-weight: 800; color: #a32b20; margin-right: 10px; }
.fp-time { color: var(--muted); }
.fp-reason { margin-top: 4px; color: #a32b20; }

/* ---------------- 追溯账本表 ---------------- */
.ledger { width: 100%; border-collapse: collapse; font-size: 13px; }
.ledger th, .ledger td {
  border: 1px solid var(--line); padding: 7px 9px; text-align: left;
}
.ledger th { background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
.ledger tr:nth-child(even) td { background: #fafcfb; }
.mono { font-family: ui-monospace, Menlo, Consolas, monospace; font-weight: 700; color: var(--primary-deep); }
.ledger.fail .reason { color: #a32b20; }
.attempts { margin-top: 10px; font-size: 13px; }
.attempts summary { cursor: pointer; color: var(--muted); padding: 4px 0; }
</style>
