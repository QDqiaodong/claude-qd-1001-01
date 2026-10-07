<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：纸机状态动作 / 当前检修单 / 产出准入提示放在导轨上方，
// 导轨继续展示该纸机全部母卷；检修只改变准入状态，不移动、不删除既有母卷。
// ------------------------------------------------------------------

const routeQuery = computed(() => router.currentRoute.value.query || {})
const machines = ref([])
const rolls = ref([])
const error = ref('')
const tip = ref('')
const selectedMachineId = ref(null)
const selectedRollId = ref(null)
const consumeLength = ref(500)

const operator = ref('')
const maintenanceReason = ref('')
const expectedFinish = ref('')

const productionForm = ref({
  rollNo: '',
  grammageGsm: 70,
  widthMm: 1600,
  lengthM: 5000,
  weightKg: 800,
  targetGsm: 70
})
let createToken = ''
let markToken = ''

// 切裁领料追溯：选中母卷被哪些切纸订单领走过料，以及最近的领料失败留痕
const rollRequisitions = ref([])
const rollAttempts = ref([])

const selectedMachine = computed(() =>
  machines.value.find((m) => m.id === selectedMachineId.value) || machines.value[0] || null)

const selected = computed(() => {
  const r = rolls.value.find((x) => x.id === selectedRollId.value)
  if (r) return r
  return rolls.value.find((x) => machineIdOf(x) === selectedMachineId.value) || null
})

const rollsByMachine = computed(() => {
  const map = {}
  for (const r of rolls.value) {
    const mid = machineIdOf(r)
    if (!map[mid]) map[mid] = []
    map[mid].push(r)
  }
  return map
})

const maxLength = computed(() => {
  let m = 1
  for (const r of rolls.value) m = Math.max(m, Number(r.lengthM || 0))
  return m
})

const rollLastFails = computed(() => rollAttempts.value.filter((a) => a.result === 'FAILED'))

function machineIdOf(r) {
  return r.machineId ?? (r.machine && r.machine.id) ?? r.machine_id
}

function newToken(prefix) {
  return `${prefix}-${Date.now()}-${Math.random().toString(16).slice(2, 10)}`
}

function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : ''
}

function barWidth(r) {
  return Math.max(6, (Number(r.lengthM) / maxLength.value) * 100) + '%'
}

async function load() {
  error.value = ''
  try {
    const q = routeQuery.value.q
    machines.value = await api.listMachines(['运行', '停机', '检修'].includes(q) ? { status: q } : {})
    const rollQuery = q && !['运行', '停机', '检修'].includes(q) ? { status: q } : {}
    rolls.value = await api.listRolls(rollQuery)
    if (!rolls.value.length && rollQuery.status) rolls.value = await api.listRolls()

    if (!selectedMachineId.value && machines.value.length) {
      selectedMachineId.value = machines.value[0].id
    }
    if (!selectedRollId.value && rolls.value.length) {
      const first = rolls.value.find((r) => machineIdOf(r) === selectedMachineId.value) || rolls.value[0]
      selectedRollId.value = first.id
    }
    await loadTrace()
  } catch (e) {
    error.value = e.message
  }
}

async function loadTrace() {
  if (!selected.value) { rollRequisitions.value = []; rollAttempts.value = []; return }
  try {
    const [ledger, logs] = await Promise.all([
      api.rollRequisitions(selected.value.id),
      api.rollRequisitionAttempts(selected.value.id)
    ])
    rollRequisitions.value = ledger
    rollAttempts.value = logs
  } catch (e) {
    rollRequisitions.value = []
    rollAttempts.value = []
  }
}

function selectMachine(m) {
  selectedMachineId.value = m.id
  const first = rolls.value.find((r) => machineIdOf(r) === m.id)
  if (first) selectedRollId.value = first.id
}

function selectRoll(r) {
  selectedRollId.value = r.id
  selectedMachineId.value = machineIdOf(r)
}

function actionableItems(m) {
  if (!m) return []
  return m.allowedActions.filter((a) => a !== '开始检修')
}

async function machineAction(action, extra = {}) {
  if (!selectedMachine.value) return
  error.value = ''; tip.value = ''
  if (!operator.value.trim()) {
    error.value = '请先填写经办人，再执行纸机状态动作'
    return
  }
  try {
    await api.machineAction(selectedMachine.value.id, { action, operator: operator.value.trim(), ...extra })
    tip.value = `${selectedMachine.value.machineName} 已执行「${action}」`
    if (action === '开始检修') {
      maintenanceReason.value = ''
      expectedFinish.value = ''
    }
    await load()
  } catch (e) {
    error.value = e.message
    await load()
  }
}

function submitStartMaintenance() {
  if (!maintenanceReason.value.trim()) {
    error.value = '请填写检修原因'
    return
  }
  if (!expectedFinish.value) {
    error.value = '请选择预计结束时间'
    return
  }
  machineAction('开始检修', {
    reason: maintenanceReason.value.trim(),
    expectedFinishAt: expectedFinish.value
  })
}

async function submitProduction() {
  if (!selectedMachine.value) return
  error.value = ''; tip.value = ''
  if (!operator.value.trim()) {
    error.value = '请先填写经办人，再登记母卷'
    return
  }
  createToken = createToken || newToken('prod')
  try {
    const roll = await api.createRoll({
      requestToken: createToken,
      machineId: selectedMachine.value.id,
      operator: operator.value.trim(),
      ...productionForm.value
    })
    tip.value = `${roll.rollNo} 已登记为待检母卷，归属 ${selectedMachine.value.machineCode}`
    createToken = ''
    productionForm.value.rollNo = ''
    await load()
    selectedRollId.value = roll.id
    selectedMachineId.value = selectedMachine.value.id
  } catch (e) {
    error.value = e.message
    if (!e.networkError) createToken = ''
    await load()
  }
}

async function markUsable() {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  if (!operator.value.trim()) {
    error.value = '请先填写经办人，再确认检验合格'
    return
  }
  markToken = markToken || newToken('usable')
  try {
    await api.markRollUsable(selected.value.id, {
      requestToken: markToken,
      operator: operator.value.trim()
    })
    tip.value = `${selected.value.rollNo} 已检验为可用`
    markToken = ''
    await load()
  } catch (e) {
    error.value = e.message
    if (!e.networkError) markToken = ''
    await load()
  }
}

async function rejectRoll() {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    await api.rejectRoll(selected.value.id)
    tip.value = `${selected.value.rollNo} 已判废；这是未完成产出登记的一种处理结果`
    await load()
  } catch (e) { error.value = e.message }
}

async function transfer(target) {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    await api.transferRoll(selected.value.id, target)
    tip.value = `${selected.value.rollNo} 已转为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

async function consume() {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    await api.consumeRoll(selected.value.id, consumeLength.value)
    tip.value = `已从 ${selected.value.rollNo} 取用 ${consumeLength.value} 米`
    await load()
  } catch (e) { error.value = e.message }
}

watch(() => routeQuery.value.q, () => load())
watch(selectedRollId, () => loadTrace())
onMounted(load)
</script>

<template>
  <div>
    <!-- ===================== 纸机状态、检修单、产出准入规则链 ===================== -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">抄纸工段 · 纸机检修与母卷产出准入</span>
        <span class="panel-desc">先执行纸机动作，再看当前检修单与准入结果；检修中不新建母卷、不判可用，既有母卷台账不消失</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div v-if="selectedMachine" class="control-grid">
        <div class="control-card">
          <div class="card-title">可执行的状态动作</div>
          <div class="machine-line">
            <b>{{ selectedMachine.machineName }}</b>
            <span>{{ selectedMachine.machineCode }}</span>
            <span class="status-pill" :class="'s-' + selectedMachine.status">{{ selectedMachine.status }}</span>
          </div>
          <label class="operator-line">
            经办人
            <input v-model="operator" maxlength="32" placeholder="姓名/工号" />
          </label>
          <div class="action-wrap">
            <button
              v-for="action in actionableItems(selectedMachine)"
              :key="action"
              class="act"
              :class="{ primary: action === '恢复运行' }"
              @click="machineAction(action)"
            >{{ action }}</button>
            <span v-if="!selectedMachine.allowedActions.length" class="muted">当前没有可执行动作</span>
          </div>

          <div v-if="selectedMachine.allowedActions.includes('开始检修')" class="maintain-form">
            <div class="form-title">发起检修登记</div>
            <textarea v-model="maintenanceReason" maxlength="256" rows="3" placeholder="检修原因，如：真空伏辊轴承磨损、更换筛鼓"></textarea>
            <div class="form-row">
              <label>预计结束时间</label>
              <input v-model="expectedFinish" type="datetime-local" />
              <button class="act danger" @click="submitStartMaintenance">提交检修</button>
            </div>
            <div v-if="selectedMachine.pendingRollCount" class="hard-block">
              未完成产出登记 {{ selectedMachine.pendingRollCount }} 条：{{ selectedMachine.pendingRollNos.join('、') }}。
              必须先判可用或判废，否则不能进入检修。
            </div>
          </div>
        </div>

        <div class="control-card">
          <div class="card-title">当前检修单</div>
          <template v-if="selectedMachine.currentMaintenance">
            <div class="ticket-no">{{ selectedMachine.currentMaintenance.maintenanceNo }}
              <span class="ticket-status">{{ selectedMachine.currentMaintenance.status }}</span>
            </div>
            <dl class="kv">
              <div><dt>检修原因</dt><dd>{{ selectedMachine.currentMaintenance.reason }}</dd></div>
              <div><dt>开始时间</dt><dd>{{ fmtTime(selectedMachine.currentMaintenance.startedAt) }}</dd></div>
              <div><dt>预计结束</dt><dd>{{ fmtTime(selectedMachine.currentMaintenance.expectedFinishAt) }}</dd></div>
              <div><dt>发起经办人</dt><dd>{{ selectedMachine.currentMaintenance.startOperator }}</dd></div>
              <div v-if="selectedMachine.currentMaintenance.finishedAt"><dt>完成时间</dt><dd>{{ fmtTime(selectedMachine.currentMaintenance.finishedAt) }}（{{ selectedMachine.currentMaintenance.finishOperator }}）</dd></div>
              <div v-if="selectedMachine.currentMaintenance.resumedAt"><dt>恢复时间</dt><dd>{{ fmtTime(selectedMachine.currentMaintenance.resumedAt) }}（{{ selectedMachine.currentMaintenance.resumeOperator }}）</dd></div>
            </dl>
            <div v-if="selectedMachine.currentMaintenance.status === '待恢复'" class="await-resume">
              检修已完成，纸机已回到「停机」。请核对现场后，由经办人点击左侧「恢复运行」。
            </div>
          </template>
          <div v-else class="empty-box">当前没有检修中或待恢复的检修单。</div>
        </div>

        <div class="control-card">
          <div class="card-title">母卷产出准入</div>
          <div :class="['access-banner', selectedMachine.productionAllowed ? 'allow' : 'deny']">
            <strong>{{ selectedMachine.productionAllowed ? '允许登记产出' : '禁止登记产出 / 禁止判可用' }}</strong>
            <span>{{ selectedMachine.productionAllowed
              ? '该纸机不在检修状态；新母卷仍须经过待检，检验合格后才可用。'
              : selectedMachine.productionBlockReason }}</span>
          </div>
          <div v-if="selectedMachine.productionBlocks?.length" class="block-log">
            <div class="block-title">最近被阻止的产出原因（刷新后仍保留）</div>
            <div v-for="b in selectedMachine.productionBlocks" :key="b.id" class="block-row">
              <span>{{ fmtTime(b.attemptedAt) }} · {{ b.actionType === 'CREATE_ROLL' ? '新建母卷' : '判可用' }} · {{ b.operator || '-' }}</span>
              <p>{{ b.failReason }}</p>
            </div>
          </div>
        </div>
      </div>
    </div>

    <!-- ===================== 母卷产出表单 ===================== -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">母卷产出登记 · {{ selectedMachine?.machineName }}</span>
        <span class="panel-desc">检修中提交会被后端拒绝并留痕；成功后的母卷固定归属当前纸机</span>
      </div>
      <div class="production-form">
        <label>母卷号<input v-model="productionForm.rollNo" maxlength="32" placeholder="R-2026xxx" /></label>
        <label>实测定量<input v-model.number="productionForm.grammageGsm" type="number" min="1" /></label>
        <label>目标定量<input v-model.number="productionForm.targetGsm" type="number" min="1" /></label>
        <label>幅宽mm<input v-model.number="productionForm.widthMm" type="number" min="1" :max="selectedMachine?.designWidthMm" /></label>
        <label>长度m<input v-model.number="productionForm.lengthM" type="number" min="1" /></label>
        <label>重量kg<input v-model.number="productionForm.weightKg" type="number" min="0.01" step="0.01" /></label>
        <button class="act primary" :disabled="!selectedMachine || !operator.trim() || !productionForm.rollNo.trim()" @click="submitProduction">
          登记母卷
        </button>
      </div>
      <div class="note">纸机设计幅宽 {{ selectedMachine?.designWidthMm }}mm；待检卷在母卷操作区判可用，检修状态会同时锁住这条动作。</div>
    </div>

    <!-- ===================== 纸机导轨与历史 ===================== -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">纸机导轨与母卷台账</span>
        <span class="panel-desc">条子长度 = 母卷剩余长度；检修状态和检修历史直接挂在对应纸机导轨上</span>
      </div>

      <div class="stat-row">
        <div class="stat"><div class="k">纸机</div><div class="v">{{ machines.length }}</div></div>
        <div class="stat"><div class="k">纸卷</div><div class="v">{{ rolls.length }}</div></div>
        <div class="stat"><div class="k">可用</div><div class="v">{{ rolls.filter(r => r.status === '可用').length }}</div></div>
        <div class="stat"><div class="k">待检</div><div class="v">{{ rolls.filter(r => r.status === '待检').length }}</div></div>
      </div>

      <div v-for="m in machines" :key="m.id" class="rail" :class="{ selected: selectedMachine?.id === m.id }" @click="selectMachine(m)">
        <div class="rail-head">
          <span class="rail-name">{{ m.machineName }}</span>
          <span class="rail-code">{{ m.machineCode }}</span>
          <span class="rail-chip">设计幅宽 {{ m.designWidthMm }}mm</span>
          <span class="rail-chip" :class="'s-' + m.status">{{ m.status }}</span>
          <span v-if="m.currentMaintenance" class="rail-chip repair">
            {{ m.currentMaintenance.maintenanceNo }} · {{ m.currentMaintenance.status }}
          </span>
          <span class="rail-count">纸卷 {{ m.rollCount }} / 待检 {{ m.pendingRollCount }}</span>
        </div>

        <div v-if="m.currentMaintenance" class="rail-repair">
          当前检修：{{ m.currentMaintenance.reason }}；开始 {{ fmtTime(m.currentMaintenance.startedAt) }}，
          预计 {{ fmtTime(m.currentMaintenance.expectedFinishAt) }}，经办人 {{ m.currentMaintenance.startOperator }}
          <b v-if="m.currentMaintenance.status === '待恢复'">；已回停机，待确认恢复运行</b>
        </div>

        <div class="rail-track" @click.stop>
          <div
            v-for="r in (rollsByMachine[m.id] || [])"
            :key="r.id"
            class="roll-bar"
            :class="[ 'g-' + r.grade, { sel: r.id === selectedRollId, done: r.status === '已切完' } ]"
            :style="{ width: barWidth(r) }"
            :title="`${r.rollNo} · ${r.grammageGsm}g · ${r.widthMm}mm · 余 ${r.lengthM}m · ${r.grade}`"
            @click="selectRoll(r)"
          >
            <span class="roll-inner">{{ r.lengthM }}m</span>
          </div>
          <div v-if="!(rollsByMachine[m.id] || []).length" class="rail-empty">本台暂无纸卷；检修不会删除既有母卷归属</div>
        </div>

        <div v-if="m.productionBlocks?.length" class="mini-block">
          最近阻止：{{ fmtTime(m.productionBlocks[0].attemptedAt) }}，{{ m.productionBlocks[0].failReason }}
        </div>

        <details class="history-box" :open="selectedMachine?.id === m.id">
          <summary>检修历史（{{ m.maintenanceHistory.length }}）</summary>
          <table class="mini-table">
            <thead><tr><th>检修单号</th><th>原因</th><th>开始</th><th>预计结束</th><th>完成/恢复</th><th>经办人</th><th>状态</th></tr></thead>
            <tbody>
              <tr v-for="h in m.maintenanceHistory" :key="h.id">
                <td class="mono">{{ h.maintenanceNo }}</td>
                <td>{{ h.reason }}</td>
                <td>{{ fmtTime(h.startedAt) }}</td>
                <td>{{ fmtTime(h.expectedFinishAt) }}</td>
                <td>{{ fmtTime(h.finishedAt) }}<span v-if="h.resumedAt"> / {{ fmtTime(h.resumedAt) }}</span></td>
                <td>{{ h.startOperator }}<template v-if="h.resumeOperator"> → {{ h.resumeOperator }}</template></td>
                <td>{{ h.status }}</td>
              </tr>
            </tbody>
          </table>
        </details>
      </div>
    </div>

    <!-- ===================== 选中母卷操作与追溯 ===================== -->
    <div class="panel" v-if="selected">
      <div class="panel-head">
        <span class="panel-title">选中母卷 · {{ selected.rollNo }}</span>
        <span class="panel-desc">{{ selected.grammageGsm }}g/m² · 幅宽 {{ selected.widthMm }}mm · 余长 {{ selected.lengthM }}m · {{ selected.grade }} · {{ selected.status }}</span>
      </div>

      <div v-if="selectedMachine && !selectedMachine.productionAllowed && selected.status === '待检'" class="hard-block">
        {{ selectedMachine.productionBlockReason }}
      </div>

      <div class="op-row">
        <button class="act primary" :disabled="selected.status !== '待检'" @click="markUsable">检验合格</button>
        <button class="act" :disabled="selected.status !== '待检'" @click="rejectRoll">判废</button>
        <button class="act" :disabled="selected.status !== '可用'" @click="transfer('已切完')">切完</button>
        <span class="spacer"></span>
        <span class="op-label">取用长度</span>
        <input type="number" min="1" v-model.number="consumeLength" class="num" />
        <button class="act" :disabled="selected.status !== '可用'" @click="consume">登记取用</button>
      </div>
      <div class="note">检验合格走产出准入链；判废用于清理「待检」未完成登记。既有母卷及其切裁账本不会因纸机检修而消失。</div>

      <div class="trace-head">
        <span class="trace-title">本卷领料追溯</span>
        <span class="trace-desc">被切纸订单领走 {{ rollRequisitions.length }} 次；母卷或纸机状态变化后记录仍保留</span>
      </div>

      <div v-if="rollLastFails.length" class="fail-persist">
        <div class="fp-tag">本卷最近的领料失败留痕（重开页面仍在，可对照真实余量）</div>
        <div v-for="a in rollLastFails.slice(0, 3)" :key="a.id" class="fp-row">
          <span class="fp-time">{{ fmtTime(a.attemptedAt) }} · 订单 {{ a.orderNo || '-' }} · {{ a.operator || '-' }}</span>
          <span class="fp-reason">{{ a.failReason }}</span>
        </div>
      </div>

      <div v-if="!rollRequisitions.length" class="trace-empty">本卷还没有被切纸订单领过料</div>
      <table v-else class="ledger">
        <thead>
          <tr>
            <th>领料单号</th><th>切纸订单</th><th>客户</th>
            <th>本次长度(m)</th><th>本次重量(kg)</th><th>操作时间</th><th>操作人</th>
            <th>扣后余长(m)</th><th>扣后余重(kg)</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="q in rollRequisitions" :key="q.id">
            <td class="mono">{{ q.requisitionNo }}</td>
            <td>{{ q.orderNo }}</td>
            <td>{{ q.order && q.order.customerName }}</td>
            <td>{{ q.lengthM }}</td>
            <td>{{ q.weightKg }}</td>
            <td>{{ fmtTime(q.operatedAt) }}</td>
            <td>{{ q.operator }}</td>
            <td>{{ q.remainLengthM }}</td>
            <td>{{ q.remainWeightKg }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.control-grid {
  display: grid;
  grid-template-columns: minmax(280px, 1.1fr) minmax(300px, 1fr) minmax(280px, 0.9fr);
  gap: 12px;
}
.control-card {
  border: 1px solid var(--line);
  border-radius: 10px;
  padding: 12px;
  background: #fcfefd;
  min-width: 0;
}
.card-title, .form-title { font-weight: 800; color: var(--primary-deep); margin-bottom: 9px; }
.machine-line { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 9px; }
.machine-line span { color: var(--muted); font-size: 12px; }
.operator-line { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--muted); }
.operator-line input, .maintain-form input, .maintain-form textarea, .production-form input {
  width: 100%; padding: 7px 9px; border: 1px solid var(--line); border-radius: 7px; font: inherit;
}
.operator-line input { max-width: 180px; }
.action-wrap { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 10px; }
.status-pill, .s-运行, .s-停机, .s-检修 {
  padding: 2px 9px; border-radius: 99px; font-size: 12px; font-weight: 800;
  border: 1px solid transparent;
}
.s-运行 { background: #e8f5e9; color: #2e7d32; border-color: #b9dfbc; }
.s-停机 { background: #eceff1; color: #546e7a; border-color: #cfd8dc; }
.s-检修 { background: #fff3e0; color: #ef6c00; border-color: #ffcc80; }

.act {
  padding: 7px 14px; border-radius: 7px; cursor: pointer; font-size: 13px;
  border: 1px solid var(--line); background: #fff; color: var(--ink);
}
.act:hover { border-color: var(--primary-soft); color: var(--primary-deep); }
.act.primary { background: var(--primary); border-color: var(--primary); color: #fff; font-weight: 700; }
.act.danger { background: #fff; border-color: #ef6c00; color: #e65100; font-weight: 700; }
.act:disabled { opacity: 0.45; cursor: not-allowed; }
.muted { color: var(--muted); font-size: 13px; }

.maintain-form { margin-top: 12px; padding-top: 10px; border-top: 1px dashed var(--line); }
.maintain-form textarea { resize: vertical; margin: 6px 0; }
.form-row { display: grid; grid-template-columns: auto 1fr auto; gap: 8px; align-items: center; font-size: 12px; color: var(--muted); }
.hard-block, .mini-block {
  margin-top: 9px; padding: 8px 10px; border-radius: 8px;
  background: #fdecea; border: 1px solid #f5c6c2; color: #a32b20; font-size: 12px;
}
.ticket-no { font-family: ui-monospace, Menlo, Consolas, monospace; font-weight: 800; color: var(--primary-deep); }
.ticket-status { margin-left: 8px; font-family: inherit; color: #ef6c00; font-size: 12px; }
.kv { margin: 9px 0 0; display: grid; gap: 6px; }
.kv div { display: grid; grid-template-columns: 76px 1fr; gap: 8px; font-size: 13px; }
.kv dt { color: var(--muted); }
.kv dd { margin: 0; min-width: 0; }
.empty-box { color: var(--muted); font-size: 13px; padding: 18px 0; }
.await-resume { margin-top: 10px; padding: 9px; border-radius: 8px; background: #fff8e1; border: 1px solid #ffe082; color: #8d5c00; font-size: 13px; }

.access-banner { border-radius: 9px; padding: 10px; display: grid; gap: 5px; font-size: 13px; line-height: 1.5; }
.access-banner.allow { background: #e8f5e9; border: 1px solid #b9dfbc; color: #1b5e20; }
.access-banner.deny { background: #fdecea; border: 1px solid #f5c6c2; color: #a32b20; }
.block-log { margin-top: 10px; display: grid; gap: 7px; }
.block-title { font-size: 12px; font-weight: 800; color: #a32b20; }
.block-row { padding: 7px; border: 1px solid #f5c6c2; border-radius: 7px; background: #fff; font-size: 12px; }
.block-row span { color: var(--muted); }
.block-row p { margin: 3px 0 0; color: #a32b20; }

.production-form { display: grid; grid-template-columns: repeat(6, minmax(110px, 1fr)) auto; gap: 9px; align-items: end; }
.production-form label { display: grid; gap: 4px; font-size: 12px; color: var(--muted); }
.production-form .act { height: 36px; }
.note { font-size: 12px; color: var(--muted); margin-top: 8px; }

.rail { margin-bottom: 12px; border: 1px solid var(--line); border-radius: 8px; cursor: pointer; }
.rail.selected { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }
.rail-head {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 10px;
  background: var(--primary-wash);
  border-radius: 8px 8px 0 0;
}
.rail-name { font-weight: 800; color: var(--primary-deep); }
.rail-code { font-size: 11px; color: var(--muted); }
.rail-chip {
  font-size: 11px; padding: 1px 8px; border-radius: 9px;
  background: #fff; border: 1px solid #cde6e2; color: var(--primary-soft);
}
.rail-chip.repair { background: #fff3e0; border-color: #ffcc80; color: #ef6c00; font-weight: 800; }
.rail-count { margin-left: auto; font-size: 12px; font-weight: 800; color: var(--primary-deep); }
.rail-repair { padding: 7px 10px; background: #fff8e1; border-top: 1px solid #ffe082; color: #8d5c00; font-size: 12px; }
.rail-track {
  display: flex; flex-wrap: wrap; gap: 6px;
  padding: 10px;
  border-top: 1px solid var(--line);
  background: #fff;
  min-height: 52px;
}
.roll-bar {
  height: 32px; border-radius: 6px; cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 12px; font-weight: 700;
  border: 2px solid transparent; transition: transform 0.12s, box-shadow 0.12s;
  min-width: 58px;
}
.roll-bar:hover { transform: translateY(-2px); box-shadow: 0 4px 10px rgba(0,0,0,0.15); }
.roll-bar.sel { border-color: var(--primary-deep); box-shadow: 0 0 0 2px var(--primary-wash); }
.roll-bar.done { opacity: 0.5; }
.roll-bar.g-一等 { background: linear-gradient(120deg, #00695c, #00897b); }
.roll-bar.g-二等 { background: linear-gradient(120deg, #558b2f, #7cb342); }
.roll-bar.g-等外 { background: linear-gradient(120deg, #8d6e63, #a1887f); }
.roll-inner { white-space: nowrap; }
.rail-empty { font-size: 12px; color: var(--muted); padding: 8px 4px; }
.mini-block { margin: 0; border-radius: 0; border-left: none; border-right: none; }
.history-box { background: #fff; border-top: 1px solid var(--line); padding: 8px 10px; font-size: 12px; }
.history-box summary { cursor: pointer; font-weight: 700; color: var(--primary-deep); }
.mini-table { width: 100%; border-collapse: collapse; margin-top: 8px; font-size: 12px; }
.mini-table th, .mini-table td { border: 1px solid var(--line); padding: 6px; text-align: left; vertical-align: top; }
.mini-table th { background: var(--primary-wash); color: var(--primary-deep); }
.mono { font-family: ui-monospace, Menlo, Consolas, monospace; font-weight: 700; color: var(--primary-deep); }

.op-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.op-label { font-size: 13px; color: var(--muted); }
.num { width: 92px; padding: 6px 8px; border: 1px solid var(--line); border-radius: 6px; }
.trace-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; margin: 14px 0 8px; padding-top: 12px; border-top: 1px dashed var(--line); }
.trace-title { font-size: 14px; font-weight: 800; color: var(--primary-deep); }
.trace-desc { font-size: 12px; color: var(--muted); }
.trace-empty { font-size: 13px; color: var(--muted); padding: 8px 2px; }
.ledger { width: 100%; border-collapse: collapse; font-size: 13px; }
.ledger th, .ledger td { border: 1px solid var(--line); padding: 7px 9px; text-align: left; }
.ledger th { background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
.ledger tr:nth-child(even) td { background: #fafcfb; }
.fail-persist { margin: 0 0 10px; padding: 8px 12px; background: #fdecea; border: 1px solid #f5c6c2; border-radius: 8px; font-size: 12px; }
.fp-tag { font-weight: 800; color: #a32b20; margin-bottom: 4px; }
.fp-row { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 3px; }
.fp-time { color: var(--muted); flex: none; }
.fp-reason { color: #a32b20; }

@media (max-width: 1100px) {
  .control-grid { grid-template-columns: 1fr; }
  .production-form { grid-template-columns: repeat(2, minmax(120px, 1fr)); }
}
</style>
