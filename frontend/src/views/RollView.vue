<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：纸机导轨 + 状态/检修/产出准入规则链 + 纸卷长度条。
// 操作员先看到每台纸机当前可执行动作、当前检修单和禁止产出原因；
// 再在同一台纸机的规则链内发起检修、确认复机或登记母卷产出。
// ------------------------------------------------------------------

const routeQuery = computed(() => router.currentRoute.value.query || {})
const machines = ref([])
const rolls = ref([])
const rails = ref({})
const error = ref('')
const tip = ref('')
const selectedMachineId = ref(null)
const selectedRollId = ref(null)
const consumeLength = ref(500)

// 顶部联动控制
const operator = ref('王建国')
const actionMode = ref('')
const pendingStatusAction = ref('')
const maintenanceReason = ref('')
const maintenanceStart = ref('')
const maintenanceExpectedEnd = ref('')

// 母卷产出登记
const rollForm = ref({
  rollNo: '',
  grammageGsm: 70,
  widthMm: null,
  lengthM: 1000,
  weightKg: null,
  targetGsm: 70
})

// 切裁领料追溯
const rollRequisitions = ref([])
const rollAttempts = ref([])

const rollsByMachine = computed(() => {
  const map = {}
  for (const r of rolls.value) {
    const mid = r.machineId ?? (r.machine && r.machine.id) ?? r.machine_id
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

const selectedMachine = computed(() =>
  machines.value.find((m) => m.id === selectedMachineId.value) || machines.value[0] || null)
const selectedRail = computed(() =>
  selectedMachine.value ? rails.value[selectedMachine.value.id] : null)
const selected = computed(() => rolls.value.find((r) => r.id === selectedRollId.value) || null)

const pendingRolls = computed(() => rolls.value.filter((r) => r.status === '待检'))
const maintenanceBlocked = computed(() => selectedRail.value?.productionBlocked === true)
const currentOrder = computed(() => selectedRail.value?.currentMaintenance || null)

async function load() {
  error.value = ''
  try {
    const q = routeQuery.value.q
    machines.value = await api.listMachines()
    rolls.value = await api.listRolls(q ? { status: q } : {})
    if (!rolls.value.length && q) rolls.value = await api.listRolls()

    const railEntries = await Promise.all(machines.value.map(async (m) => {
      try {
        return [m.id, await api.machineRail(m.id)]
      } catch (e) {
        return [m.id, {
          machine: m,
          rollCount: (rollsByMachine.value[m.id] || []).length,
          pendingRollCount: 0,
          pendingRollNos: [],
          availableActions: [],
          actionReasons: [e.message],
          productionBlocked: m.status === '检修',
          productionAttempts: [],
          maintenanceHistory: []
        }]
      }
    }))
    rails.value = Object.fromEntries(railEntries)

    if (!selectedMachineId.value || !machines.value.some((m) => m.id === selectedMachineId.value)) {
      const railList = machines.value.map((m) => rails.value[m.id]).filter(Boolean)
      selectedMachineId.value = (
        railList.find((r) => r.machine?.status === '检修')
        || railList.find((r) => r.pendingRollCount > 0)
        || machines.value[0]
      )?.id || null
    }
    if (!selectedRollId.value || !rolls.value.some((r) => r.id === selectedRollId.value)) {
      selectedRollId.value = (rollsByMachine.value[selectedMachineId.value] || rolls.value)[0]?.id || null
    }
    const selected = machines.value.find((m) => m.id === selectedMachineId.value)
    if (selected && (rollForm.value.widthMm == null || rollForm.value.widthMm > selected.designWidthMm)) {
      rollForm.value.widthMm = selected.designWidthMm
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

const rollLastFails = computed(() => rollAttempts.value.filter((a) => a.result === 'FAILED'))

function fmtTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : ''
}
function localNow(offsetDays = 0) {
  const d = new Date()
  d.setDate(d.getDate() + offsetDays)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}
function barWidth(r) {
  return Math.max(6, (Number(r.lengthM) / maxLength.value) * 100) + '%'
}
function selectMachine(id) {
  selectedMachineId.value = id
  actionMode.value = ''
  const m = machines.value.find((item) => item.id === id)
  if (m && (rollForm.value.widthMm == null || rollForm.value.widthMm > m.designWidthMm)) {
    rollForm.value.widthMm = m.designWidthMm
  }
  const first = (rollsByMachine.value[id] || [])[0]
  if (first) selectedRollId.value = first.id
}
function hasAction(action) {
  return (selectedRail.value?.availableActions || []).includes(action)
}
function chooseAction(action) {
  error.value = ''; tip.value = ''
  if (action === '发起检修') {
    actionMode.value = 'start'
    if (!maintenanceStart.value) maintenanceStart.value = localNow(0)
    if (!maintenanceExpectedEnd.value) maintenanceExpectedEnd.value = localNow(1)
    return
  }
  if (action === '检修完成') actionMode.value = 'finish'
  if (action === '经办人确认复机') actionMode.value = 'resume'
  if (['运行', '停机'].includes(action)) {
    pendingStatusAction.value = action
    actionMode.value = 'transfer'
  }
}

async function submitMaintenanceAction() {
  if (!selectedMachine.value) return
  error.value = ''; tip.value = ''
  try {
    const id = selectedMachine.value.id
    if (actionMode.value === 'start') {
      await api.startMaintenance(id, {
        reason: maintenanceReason.value,
        startedAt: maintenanceStart.value,
        expectedEndAt: maintenanceExpectedEnd.value,
        operator: operator.value
      })
      tip.value = '检修已发起：母卷新建与标记可用均已锁定，未完成产出登记已检查'
    } else if (actionMode.value === 'finish') {
      await api.finishMaintenance(id, { operator: operator.value })
      tip.value = '检修已完成，纸机已回到停机；需经办人再次确认后才能运行'
    } else if (actionMode.value === 'resume') {
      await api.resumeMaintenance(id, { operator: operator.value })
      tip.value = '经办人已确认，纸机恢复运行，母卷产出准入已打开'
    } else if (actionMode.value === 'transfer') {
      await api.transferMachine(id, pendingStatusAction.value)
      tip.value = `纸机已转为${pendingStatusAction.value}`
    }
    actionMode.value = ''
    maintenanceReason.value = ''
    await load()
  } catch (e) { error.value = e.message }
}

async function produceRoll() {
  if (!selectedMachine.value) return
  error.value = ''; tip.value = ''
  try {
    const created = await api.produceRoll(selectedMachine.value.id, {
      ...rollForm.value,
      operator: operator.value
    })
    tip.value = `${created.rollNo} 已产出并归属 ${selectedMachine.value.machineCode}，初始状态为「待检」`
    rollForm.value.rollNo = ''
    rollForm.value.widthMm = null
    rollForm.value.lengthM = 1000
    rollForm.value.weightKg = null
    await load()
    selectedRollId.value = created.id
  } catch (e) { error.value = e.message }
}

async function inspect(target) {
  if (!selected.value) return
  error.value = ''; tip.value = ''
  try {
    await api.inspectRoll(selected.value.id, { target, operator: operator.value })
    tip.value = `${selected.value.rollNo} 已检验为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

async function transferRoll(target) {
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
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">抄纸工段 · 纸机检修状态与母卷产出准入</span>
        <span class="panel-desc">先看可执行动作、当前检修单和禁止产出原因；纸机、检修单、母卷台账在同一条规则链上联动</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">纸机</div><div class="v">{{ machines.length }}</div></div>
        <div class="stat"><div class="k">纸卷</div><div class="v">{{ rolls.length }}</div></div>
        <div class="stat"><div class="k">可用</div><div class="v">{{ rolls.filter(r => r.status === '可用').length }}</div></div>
        <div class="stat warn-stat"><div class="k">未完成产出登记</div><div class="v">{{ pendingRolls.length }}</div></div>
      </div>

      <div v-for="m in machines" :key="m.id" class="rail" :class="{ selectedRail: m.id === selectedMachineId }" @click="selectMachine(m.id)">
        <div class="rail-head">
          <span class="rail-name">{{ m.machineName }}</span>
          <span class="rail-code">{{ m.machineCode }}</span>
          <span class="rail-chip">设计幅宽 {{ m.designWidthMm }}mm</span>
          <span class="rail-chip" :class="'status-' + m.status">{{ m.status }}</span>
          <span class="rail-chip" v-if="rails[m.id]?.pendingRollCount">待检 {{ rails[m.id].pendingRollCount }}</span>
          <span class="rail-count">纸卷 {{ rails[m.id]?.rollCount ?? (rollsByMachine[m.id] || []).length }}</span>
        </div>

        <div class="rail-ruleline">
          <div v-if="rails[m.id]?.activeMaintenance" class="order-line">
            当前检修单 {{ rails[m.id].activeMaintenance.maintenanceNo }} ·
            {{ rails[m.id].activeMaintenance.reason }} ·
            经办人 {{ rails[m.id].activeMaintenance.operator }} ·
            {{ fmtTime(rails[m.id].activeMaintenance.startedAt) }} 至
            预计 {{ fmtTime(rails[m.id].activeMaintenance.expectedEndAt) }}
          </div>
          <div v-else-if="rails[m.id]?.awaitingResumeMaintenance" class="order-line waiting">
            检修单 {{ rails[m.id].awaitingResumeMaintenance.maintenanceNo }} 已完成，纸机停机；
            需经办人 {{ rails[m.id].awaitingResumeMaintenance.operator }} 确认后才能恢复运行
          </div>
          <div v-else-if="rails[m.id]?.pendingRollCount" class="order-line blocked">
            不能发起检修：有 {{ rails[m.id].pendingRollCount }} 条未完成产出登记
            （{{ (rails[m.id].pendingRollNos || []).join('、') }}）
          </div>
          <div v-else class="order-line idle">当前无检修单；母卷归属和历史台账保留不变</div>

          <div v-if="rails[m.id]?.productionBlocked" class="block-line">
            禁止产出：检修中不能新建纸卷，也不能把新纸卷标记为「可用」；已有母卷仍归属本机并可追溯。
          </div>
          <div v-if="rails[m.id]?.latestProductionAttempt" class="block-line">
            最近阻止：{{ fmtTime(rails[m.id].latestProductionAttempt.attemptedAt) }} ·
            {{ rails[m.id].latestProductionAttempt.actionType }} ·
            {{ rails[m.id].latestProductionAttempt.operator || '未登记操作人' }} ·
            {{ rails[m.id].latestProductionAttempt.blockReason }}
          </div>
          <div class="history-mini">
            <span v-for="h in (rails[m.id]?.maintenanceHistory || []).slice(0, 2)" :key="h.id" class="history-chip">
              {{ h.maintenanceNo }} · {{ h.recordStatus }} · {{ fmtTime(h.startedAt) }} · {{ h.reason }}
            </span>
          </div>
        </div>

        <div class="rail-track">
          <div
            v-for="r in (rollsByMachine[m.id] || [])"
            :key="r.id"
            class="roll-bar"
            :class="[ 'g-' + r.grade, { sel: r.id === selectedRollId, done: r.status === '已切完' } ]"
            :style="{ width: barWidth(r) }"
            :title="`${r.rollNo} · ${r.grammageGsm}g · ${r.widthMm}mm · 余 ${r.lengthM}m · ${r.grade}`"
            @click.stop="selectedRollId = r.id; selectedMachineId = m.id"
          >
            <span class="roll-inner">{{ r.lengthM }}m</span>
          </div>
          <div v-if="!(rollsByMachine[m.id] || []).length" class="rail-empty">本台暂无纸卷</div>
        </div>
      </div>
    </div>

    <!-- ============ 当前纸机：可执行动作优先 ============ -->
    <div class="panel" v-if="selectedMachine">
      <div class="panel-head">
        <span class="panel-title">当前纸机 · {{ selectedMachine.machineName }}（{{ selectedMachine.machineCode }}）</span>
        <span class="panel-desc">状态 {{ selectedMachine.status }} · 先选择动作，再填写对应单据</span>
      </div>

      <div class="operator-row">
        <label>当前操作员/经办人</label>
        <input v-model="operator" class="text-input operator-input" placeholder="姓名或工号" />
        <span class="note-inline">检修完成和复机必须由检修单经办人确认</span>
      </div>

      <div class="action-grid">
        <button
          v-for="a in (selectedRail?.availableActions || [])"
          :key="a"
          class="action-card"
          :class="{ primary: a.includes('检修') || a.includes('复机') }"
          @click="chooseAction(a)"
        >{{ a }}</button>
        <div v-if="!(selectedRail?.availableActions || []).length" class="empty">暂无可执行状态动作</div>
      </div>

      <div v-for="reason in (selectedRail?.actionReasons || [])" :key="reason" class="reason-box">{{ reason }}</div>
      <div v-if="selectedRail?.pendingRollCount" class="reason-box">
        未完成产出登记：{{ selectedRail.pendingRollCount }} 条（{{ (selectedRail.pendingRollNos || []).join('、') }}）。
        发起检修前必须将这些待检母卷检验为「可用」或判废。
      </div>

      <div v-if="actionMode" class="action-form">
        <template v-if="actionMode === 'start'">
          <div class="form-title">发起检修单</div>
          <label>检修原因<textarea v-model="maintenanceReason" rows="2" placeholder="如：更换成形网 / 烘缸检查"></textarea></label>
          <label>开始时间<input v-model="maintenanceStart" type="datetime-local" /></label>
          <label>预计结束时间<input v-model="maintenanceExpectedEnd" type="datetime-local" /></label>
          <div class="form-actions">
            <button class="act primary" :disabled="!operator || !maintenanceReason || !maintenanceStart || !maintenanceExpectedEnd" @click="submitMaintenanceAction">提交检修并关闭产出准入</button>
            <button class="act" @click="actionMode = ''">取消</button>
          </div>
        </template>

        <template v-else-if="actionMode === 'finish'">
          <div class="form-title">检修完成确认</div>
          <div class="confirm-text">提交后纸机只能回到「停机」，不会直接运行；请经办人 {{ currentOrder?.operator }} 确认。</div>
          <div class="form-actions">
            <button class="act primary" @click="submitMaintenanceAction">确认检修完成，回到停机</button>
            <button class="act" @click="actionMode = ''">取消</button>
          </div>
        </template>

        <template v-else-if="actionMode === 'resume'">
          <div class="form-title">经办人复机确认</div>
          <div class="confirm-text">检修单 {{ currentOrder?.maintenanceNo }} 已完成；只有经办人 {{ currentOrder?.operator }} 确认后才允许恢复运行。</div>
          <div class="form-actions">
            <button class="act primary" @click="submitMaintenanceAction">确认恢复运行</button>
            <button class="act" @click="actionMode = ''">取消</button>
          </div>
        </template>

        <template v-else>
          <div class="form-title">状态动作</div>
          <div class="confirm-text">将纸机转为「{{ pendingStatusAction }}」。进入检修请选择「发起检修」并填写检修单。</div>
          <div class="form-actions">
            <button class="act primary" @click="submitMaintenanceAction">确认转为{{ pendingStatusAction }}</button>
            <button class="act" @click="actionMode = ''">取消</button>
          </div>
        </template>
      </div>
    </div>

    <!-- ============ 母卷产出登记：准入状态紧随当前纸机 ============ -->
    <div class="panel" v-if="selectedMachine">
      <div class="panel-head">
        <span class="panel-title">母卷产出登记 · {{ selectedMachine.machineCode }}</span>
        <span class="panel-desc">新母卷固定归属当前纸机；检修中按钮锁定，绕过前端的请求也会被后端拒绝并持久化原因</span>
      </div>
      <div v-if="maintenanceBlocked" class="reason-box strong-reason">
        当前纸机检修中：禁止新建母卷、禁止把新母卷标成「可用」。请先完成检修并经经办人复机确认。
      </div>
      <div class="produce-grid">
        <label>母卷号<input v-model="rollForm.rollNo" class="text-input" placeholder="R-xxxxxxx" :disabled="maintenanceBlocked" /></label>
        <label>实测定量<input v-model.number="rollForm.grammageGsm" type="number" min="1" class="text-input" :disabled="maintenanceBlocked" /></label>
        <label>目标定量<input v-model.number="rollForm.targetGsm" type="number" min="1" class="text-input" :disabled="maintenanceBlocked" /></label>
        <label>幅宽 mm<input v-model.number="rollForm.widthMm" type="number" min="1" :max="selectedMachine.designWidthMm" class="text-input" :disabled="maintenanceBlocked" /></label>
        <label>长度 m<input v-model.number="rollForm.lengthM" type="number" min="1" class="text-input" :disabled="maintenanceBlocked" /></label>
        <label>重量 kg<input v-model.number="rollForm.weightKg" type="number" min="0.01" step="0.01" class="text-input" :disabled="maintenanceBlocked" /></label>
      </div>
      <div class="form-actions">
        <button class="act primary" :disabled="maintenanceBlocked || !rollForm.rollNo || !operator" @click="produceRoll">登记新母卷</button>
        <span class="note-inline">幅宽不得超过 {{ selectedMachine.designWidthMm }}mm；新卷先进入「待检」。</span>
      </div>
    </div>

    <div class="panel" v-if="selected">
      <div class="panel-head">
        <span class="panel-title">选中母卷 · {{ selected.rollNo }}</span>
        <span class="panel-desc">归属 {{ selected.machineCode }} · {{ selected.grammageGsm }}g/m² · 幅宽 {{ selected.widthMm }}mm · 余长 {{ selected.lengthM }}m · {{ selected.grade }} · {{ selected.status }}</span>
      </div>

      <div class="op-row">
        <button class="act primary" :disabled="selected.status !== '待检'" @click="inspect('可用')">检验合格</button>
        <button class="act" :disabled="selected.status !== '待检'" @click="inspect('报废')">判废</button>
        <button class="act" :disabled="selected.status !== '可用'" @click="transferRoll('已切完')">切完</button>
        <span class="spacer"></span>
        <span class="op-label">取用长度</span>
        <input type="number" min="1" v-model.number="consumeLength" class="num" />
        <button class="act" :disabled="selected.status !== '可用'" @click="consume">登记取用</button>
      </div>
      <div class="note">检修中不能检验为「可用」，但允许把待检母卷判废以清理未完成产出登记；已有母卷的纸机归属不会因检修消失。</div>
      <div class="note">切裁领料请在「切纸工段」订单上登记；本页只做导轨侧追溯——下方账本与订单侧是同一批记录。</div>

      <div class="trace-head">
        <span class="trace-title">本卷领料追溯</span>
        <span class="trace-desc">被切纸订单领走 {{ rollRequisitions.length }} 次；母卷转「已切完」或纸机检修后记录仍保留</span>
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

    <!-- ============ 当前纸机检修历史与准入阻止记录 ============ -->
    <div class="panel" v-if="selectedRail">
      <div class="panel-head">
        <span class="panel-title">检修与产出准入历史 · {{ selectedMachine?.machineCode }}</span>
        <span class="panel-desc">刷新页面后仍一致：检修记录保留，被阻止的产出原因也保留</span>
      </div>
      <table class="ledger">
        <thead>
          <tr><th>检修单号</th><th>原因</th><th>经办人</th><th>开始</th><th>预计结束</th><th>完成</th><th>复机</th><th>状态</th></tr>
        </thead>
        <tbody>
          <tr v-for="h in selectedRail.maintenanceHistory" :key="h.id">
            <td class="mono">{{ h.maintenanceNo }}</td>
            <td>{{ h.reason }}</td>
            <td>{{ h.operator }}</td>
            <td>{{ fmtTime(h.startedAt) }}</td>
            <td>{{ fmtTime(h.expectedEndAt) }}</td>
            <td>{{ fmtTime(h.finishedAt) }}</td>
            <td>{{ fmtTime(h.resumedAt) }}</td>
            <td>{{ h.recordStatus }}</td>
          </tr>
          <tr v-if="!selectedRail.maintenanceHistory.length"><td colspan="8" class="trace-empty">暂无检修记录</td></tr>
        </tbody>
      </table>

      <div class="trace-head">
        <span class="trace-title">被检修准入阻止的产出动作</span>
      </div>
      <table class="ledger">
        <thead><tr><th>时间</th><th>动作</th><th>母卷号</th><th>操作员</th><th>阻止原因</th></tr></thead>
        <tbody>
          <tr v-for="a in selectedRail.productionAttempts" :key="a.id">
            <td>{{ fmtTime(a.attemptedAt) }}</td>
            <td>{{ a.actionType }}</td>
            <td>{{ a.rollNo || '-' }}</td>
            <td>{{ a.operator || '-' }}</td>
            <td class="reason-cell">{{ a.blockReason }}</td>
          </tr>
          <tr v-if="!selectedRail.productionAttempts.length"><td colspan="5" class="trace-empty">暂无被阻止的产出动作</td></tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<style scoped>
.rail { margin-bottom: 12px; cursor: pointer; }
.rail.selectedRail { box-shadow: 0 0 0 2px var(--primary-soft); border-radius: 8px; }

.rail-head {
  display: flex; align-items: center; gap: 8px;
  padding: 6px 10px;
  background: var(--primary-wash);
  border: 1px solid #cde6e2;
  border-radius: 8px 8px 0 0;
}
.rail-name { font-weight: 800; color: var(--primary-deep); }
.rail-code { font-size: 11px; color: var(--muted); }
.rail-chip {
  font-size: 11px; padding: 1px 8px; border-radius: 9px;
  background: #fff; border: 1px solid #cde6e2; color: var(--primary-soft);
}
.rail-chip.status-检修 { background: #fff4e5; border-color: #f3c88f; color: #a15c00; font-weight: 800; }
.rail-chip.status-运行 { background: #e8f5e9; border-color: #a5d6a7; color: #1b5e20; }
.rail-chip.status-停机 { background: #eceff1; border-color: #cfd8dc; color: #455a64; }
.rail-count { margin-left: auto; font-size: 12px; font-weight: 800; color: var(--primary-deep); }

.rail-ruleline { border-left: 1px solid var(--line); border-right: 1px solid var(--line); background: #fbfdfc; padding: 7px 10px; }
.order-line { font-size: 12px; color: var(--primary-deep); }
.order-line.waiting { color: #a15c00; font-weight: 700; }
.order-line.blocked { color: #a32b20; font-weight: 800; }
.order-line.idle { color: var(--muted); }
.block-line { margin-top: 4px; font-size: 12px; color: #a32b20; }
.history-mini { display: flex; flex-direction: column; gap: 3px; margin-top: 5px; }
.history-chip { font-size: 11px; color: var(--muted); }

.rail-track {
  display: flex; flex-wrap: wrap; gap: 6px;
  padding: 10px;
  border: 1px solid var(--line);
  border-top: none;
  border-radius: 0 0 8px 8px;
  background: #fff;
  min-height: 52px;
}

.roll-bar {
  height: 32px;
  border-radius: 6px;
  cursor: pointer;
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 12px; font-weight: 700;
  border: 2px solid transparent;
  transition: transform 0.12s, box-shadow 0.12s;
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

.operator-row, .op-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 10px; }
.text-input, .action-form textarea, .action-form input {
  padding: 7px 9px; border: 1px solid var(--line); border-radius: 6px; font: inherit;
}
.operator-input { width: 180px; }
.note-inline, .op-label { font-size: 12px; color: var(--muted); }
.warn-stat { background: #fff4e5 !important; border-color: #f3c88f !important; }
.warn-stat .k { color: #a15c00 !important; }
.warn-stat .v { color: #8a4b00 !important; }

.action-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 8px; margin: 10px 0; }
.action-card {
  border: 1px solid #b8d8d2; background: #fff; color: var(--primary-deep);
  border-radius: 8px; padding: 10px 12px; font-weight: 800; cursor: pointer;
}
.action-card:hover { background: var(--primary-wash); }
.action-card.primary { background: var(--primary); border-color: var(--primary); color: #fff; }
.reason-box {
  margin-top: 7px; padding: 8px 10px; border-radius: 8px;
  background: #fdecea; border: 1px solid #f5c6c2; color: #a32b20; font-size: 12px;
}
.strong-reason { font-weight: 800; }
.action-form {
  margin-top: 12px; padding: 12px; border: 1px dashed #9bc7bf;
  border-radius: 8px; background: #fbfdfc;
}
.form-title { font-weight: 800; color: var(--primary-deep); margin-bottom: 8px; }
.action-form label { display: inline-flex; flex-direction: column; gap: 4px; margin: 0 10px 10px 0; font-size: 12px; color: var(--muted); }
.action-form input { width: 220px; }
.action-form textarea { width: 360px; }
.confirm-text { font-size: 13px; color: var(--ink); margin-bottom: 10px; }
.form-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }

.produce-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; }
.produce-grid label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--muted); }
.num { width: 92px; padding: 6px 8px; border: 1px solid var(--line); border-radius: 6px; }
.act {
  padding: 7px 14px; border-radius: 7px; cursor: pointer; font-size: 13px;
  border: 1px solid var(--line); background: #fff; color: var(--ink);
}
.act:hover { border-color: var(--primary-soft); color: var(--primary-deep); }
.act.primary { background: var(--primary); border-color: var(--primary); color: #fff; font-weight: 700; }
.act:disabled { opacity: 0.45; cursor: not-allowed; }
.note { font-size: 12px; color: var(--muted); margin-top: 8px; }

.trace-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; margin: 14px 0 8px; padding-top: 12px; border-top: 1px dashed var(--line); }
.trace-title { font-size: 14px; font-weight: 800; color: var(--primary-deep); }
.trace-desc { font-size: 12px; color: var(--muted); }
.trace-empty { font-size: 13px; color: var(--muted); padding: 8px 2px; }
.ledger { width: 100%; border-collapse: collapse; font-size: 13px; margin-bottom: 10px; }
.ledger th, .ledger td { border: 1px solid var(--line); padding: 7px 9px; text-align: left; vertical-align: top; }
.ledger th { background: var(--primary-wash); color: var(--primary-deep); font-weight: 700; }
.ledger tr:nth-child(even) td { background: #fafcfb; }
.mono { font-family: ui-monospace, Menlo, Consolas, monospace; font-weight: 700; color: var(--primary-deep); }
.reason-cell { color: #a32b20; min-width: 260px; }
.fail-persist { margin: 0 0 10px; padding: 8px 12px; background: #fdecea; border: 1px solid #f5c6c2; border-radius: 8px; font-size: 12px; }
.fp-tag { font-weight: 800; color: #a32b20; margin-bottom: 4px; }
.fp-row { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 3px; }
.fp-time { color: var(--muted); flex: none; }
.fp-reason { color: #a32b20; }
</style>
