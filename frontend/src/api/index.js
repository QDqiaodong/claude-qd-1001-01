import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 20000 })

// 后端统一返回 {ok, message, data}；HTTP 400 的业务异常也要把中文提示带出来
http.interceptors.response.use(
  (response) => response,
  (error) => {
    const message = error?.response?.data?.message || error?.message || '请求失败'
    const wrapped = new Error(message)
    // 没有 response 说明请求没到达后端（断网/超时/连接被重置），前端据此决定：
    // 这种失败保留同一 requestToken 允许原样重试，由后端幂等兜底防重复领料
    wrapped.networkError = !error?.response
    return Promise.reject(wrapped)
  }
)

async function call(promise) {
  const response = await promise
  const body = response.data
  if (!body || body.ok === false) {
    throw new Error((body && body.message) || '请求失败')
  }
  return body.data
}

const params = (o) => ({ params: o })

export default {
  // ---------------- 浆池 ----------------
  listChests: (p) => call(http.get('/pulp-chests', params(p))),
  getChest: (id) => call(http.get(`/pulp-chests/${id}`)),
  createChest: (d) => call(http.post('/pulp-chests', d)),
  updateChest: (id, d) => call(http.put(`/pulp-chests/${id}`, d)),
  fillChest: (id, amount) => call(http.post(`/pulp-chests/${id}/fill`, null, params({ amount }))),
  drawChest: (id, amount) => call(http.post(`/pulp-chests/${id}/draw`, null, params({ amount }))),
  transferChest: (id, target) => call(http.post(`/pulp-chests/${id}/transfer`, null, params({ target }))),

  // ---------------- 纸机 ----------------
  listMachines: (p) => call(http.get('/paper-machines', params(p))),
  createMachine: (d) => call(http.post('/paper-machines', d)),
  updateMachine: (id, d) => call(http.put(`/paper-machines/${id}`, d)),
  transferMachine: (id, target) => call(http.post(`/paper-machines/${id}/transfer`, null, params({ target }))),
  machineSummary: (id) => call(http.get(`/paper-machines/${id}/summary`)),

  // ---------------- 纸卷 ----------------
  listRolls: (p) => call(http.get('/paper-rolls', params(p))),
  createRoll: (d, targetGsm) => call(http.post('/paper-rolls', d, params({ targetGsm }))),
  updateRoll: (id, d) => call(http.put(`/paper-rolls/${id}`, d)),
  inspectRoll: (id, target, grade) =>
    call(http.post(`/paper-rolls/${id}/inspect`, null, params({ target, grade }))),
  transferRoll: (id, target) => call(http.post(`/paper-rolls/${id}/transfer`, null, params({ target }))),
  consumeRoll: (id, lengthM, weightKg) =>
    call(http.post(`/paper-rolls/${id}/consume`, null, params({ lengthM, weightKg }))),

  // ---------------- 切纸订单 ----------------
  listOrders: (p) => call(http.get('/cutting-orders', params(p))),
  createOrder: (d) => call(http.post('/cutting-orders', d)),
  updateOrder: (id, d) => call(http.put(`/cutting-orders/${id}`, d)),
  checkRoll: (id, rollNo) => call(http.get(`/cutting-orders/${id}/check-roll`, params({ rollNo }))),
  assignRoll: (id, rollNo) => call(http.post(`/cutting-orders/${id}/assign-roll`, null, params({ rollNo }))),
  transferOrder: (id, target) => call(http.post(`/cutting-orders/${id}/transfer`, null, params({ target }))),

  // 切裁领料闭环：登记（请求体带 requestToken 幂等令牌）、订单侧追溯、母卷侧追溯
  submitRequisition: (id, d) => call(http.post(`/cutting-orders/${id}/requisitions`, d)),
  orderRequisitions: (id) => call(http.get(`/cutting-orders/${id}/requisitions`)),
  orderRequisitionAttempts: (id) => call(http.get(`/cutting-orders/${id}/requisition-attempts`)),
  rollRequisitions: (id) => call(http.get(`/paper-rolls/${id}/requisitions`)),
  rollRequisitionAttempts: (id) => call(http.get(`/paper-rolls/${id}/requisition-attempts`)),

  // ---------------- 成品件 ----------------
  listBundles: (p) => call(http.get('/finished-bundles', params(p))),
  inboundBundle: (d) => call(http.post('/finished-bundles/inbound', d)),
  outboundBundle: (id) => call(http.post(`/finished-bundles/${id}/outbound`)),
  stockByZone: () => call(http.get('/finished-bundles/stock-by-zone'))
}
