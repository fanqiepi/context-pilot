<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listKnowledgeBases, listDocuments } from '@/api/contextPilot'
import { errorMessage } from '@/api/client'
import type { KnowledgeBase, SourceDocument } from '@/api/types'
import { commandResearchTask, createResearchTask, deleteResearchTask, getResearchTask, listResearchTasks,
  researchTaskEvents, type ResearchTask, type TaskEvent, type TaskOperation, type TaskState, type TaskStatus } from '@/api/researchTasks'

const route = useRoute()
const router = useRouter()
const bases = ref<KnowledgeBase[]>([])
const documents = ref<SourceDocument[]>([])
const knowledgeBaseId = ref('')
const documentIds = ref<string[]>([])
const goal = ref('')
const supplement = ref('')
const tasks = ref<ResearchTask[]>([])
const selected = ref<ResearchTask | null>(null)
const events = ref<TaskEvent[]>([])
const historical = ref<TaskState | null>(null)
const historyTruncated = ref(false)
const busy = ref(false)
const error = ref('')
const page = ref(1)
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let selection = 0
let createKey = crypto.randomUUID()
let createFingerprint = ''
const labels: Record<TaskStatus, string> = { PLANNING: '正在规划', READY: '计划待执行', WAITING_INPUT: '等待补充', RUNNING: '研究中', PAUSED: '已暂停', FAILED: '执行失败', CANCELLED: '已取消', COMPLETED: '已完成', INSUFFICIENT_EVIDENCE: '资料不足' }
const active = computed(() => selected.value?.status === 'PLANNING' || selected.value?.status === 'RUNNING')
const canSupplement = computed(() => selected.value && !active.value && selected.value.status !== 'CANCELLED' && selected.value.state.round < 5)
const eligible = computed(() => documents.value.filter(d => d.status === 'SUCCEEDED' && d.embeddingIndexCompatibility === 'CURRENT'))
const displayed = computed(() => historical.value ?? selected.value?.state)

async function refreshList() { tasks.value = await listResearchTasks(page.value) }
async function loadDocuments() {
  const kb = knowledgeBaseId.value
  documentIds.value = []
  try { const result = await listDocuments(kb); if (knowledgeBaseId.value === kb) documents.value = result }
  catch (e) { error.value = errorMessage(e) }
}
async function history(id: string) {
  const result = await researchTaskEvents(id)
  if (selected.value?.id === id) { events.value = result.items; historyTruncated.value = result.truncated }
}
function poll(id: string, ticket: number) {
  clearTimeout(timer)
  if (disposed || ticket !== selection || !active.value) return
  timer = setTimeout(async () => {
    try {
      const task = await getResearchTask(id)
      if (disposed || ticket !== selection) return
      selected.value = task
      if (!active.value) { await refreshList(); await history(id) }
      else poll(id, ticket)
    } catch (e) { error.value = errorMessage(e, '状态更新失败，请重新选择任务刷新。') }
  }, 1200)
}
async function selectTask(id: string) {
  const ticket = ++selection
  clearTimeout(timer); historical.value = null; error.value = ''; events.value = []
  try {
    const task = await getResearchTask(id)
    if (disposed || ticket !== selection) return
    selected.value = task
    await router.replace({ query: { task: id } })
    await history(id); poll(id, ticket)
  } catch (e) { error.value = errorMessage(e) }
}
async function create() {
  if (!knowledgeBaseId.value || !documentIds.value.length || !goal.value.trim()) return
  busy.value = true; error.value = ''
  const fingerprint = JSON.stringify([knowledgeBaseId.value, documentIds.value, goal.value.trim()])
  if (fingerprint !== createFingerprint) { createKey = crypto.randomUUID(); createFingerprint = fingerprint }
  try {
    const task = await createResearchTask({ clientRequestId: createKey, knowledgeBaseId: knowledgeBaseId.value, documentIds: documentIds.value, goal: goal.value.trim() })
    await refreshList(); await selectTask(task.id)
    createKey = crypto.randomUUID(); createFingerprint = ''
  } catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}
async function command(operation: TaskOperation) {
  const task = selected.value
  if (!task) return
  busy.value = true; error.value = ''; historical.value = null
  try {
    selected.value = await commandResearchTask(task, operation, operation === 'SUPPLEMENT' ? supplement.value.trim() : undefined)
    if (operation === 'SUPPLEMENT') supplement.value = ''
    await refreshList(); await history(task.id); poll(task.id, selection)
  } catch (e) { error.value = errorMessage(e); selected.value = await getResearchTask(task.id).catch(() => task); poll(task.id, selection) }
  finally { busy.value = false }
}
async function remove() {
  if (!selected.value) return
  busy.value = true
  try {
    await deleteResearchTask(selected.value); selected.value = null; events.value = []; historical.value = null
    ++selection; clearTimeout(timer); await router.replace({ query: {} }); await refreshList()
  } catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false }
}
async function changePage(delta: number) {
  page.value += delta
  try { await refreshList() } catch (e) { error.value = errorMessage(e) }
}
function showSnapshot(event: TaskEvent) {
  try { historical.value = JSON.parse(event.checkpoint) as TaskState } catch { error.value = '历史记录无法读取。' }
}
onMounted(async () => {
  try {
    bases.value = await listKnowledgeBases()
    await refreshList()
    if (typeof route.query.task === 'string') await selectTask(route.query.task)
  } catch (e) { error.value = errorMessage(e, '工作台暂不可用，请确认后端已开启任务工作台。') }
})
onUnmounted(() => { disposed = true; ++selection; clearTimeout(timer) })
</script>

<template>
  <section class="task-page">
    <header class="page-intro"><p class="eyebrow">RESEARCH WORKSPACE</p><h1>研究任务工作台</h1><p>明确目标，审阅计划，逐步形成有依据的研究结论。</p></header>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <div class="workspace-grid">
      <aside class="task-sidebar">
        <section class="task-card">
          <h2>新建研究</h2>
          <el-form label-position="top" @submit.prevent="create">
            <el-form-item label="知识库"><el-select v-model="knowledgeBaseId" aria-label="知识库" placeholder="选择资料所在知识库" @change="loadDocuments"><el-option v-for="base in bases" :key="base.id" :value="base.id" :label="base.name" /></el-select></el-form-item>
            <el-form-item label="研究资料（1–5 份）"><el-select v-model="documentIds" aria-label="研究资料" multiple :multiple-limit="5" :disabled="!knowledgeBaseId" placeholder="选择已完成索引的文档"><el-option v-for="doc in eligible" :key="doc.id" :value="doc.id" :label="doc.originalFilename" /></el-select></el-form-item>
            <el-form-item label="研究目标"><el-input v-model="goal" aria-label="研究目标" type="textarea" :rows="4" :maxlength="2000" show-word-limit placeholder="例如：比较两份部署方案，重点分析运维成本与迁移风险。" /></el-form-item>
            <p class="helper">系统先生成研究计划，或向你询问必要条件。检索仅限所选资料。</p>
            <el-button type="primary" native-type="submit" :loading="busy" :disabled="!documentIds.length || !goal.trim()">生成研究计划</el-button>
          </el-form>
        </section>
        <section class="task-card"><h2>我的研究任务</h2>
          <p v-if="!tasks.length" class="helper">暂无研究任务。</p>
          <button v-for="task in tasks" :key="task.id" class="task-item" :class="{ selected: selected?.id === task.id }" @click="selectTask(task.id)"><strong>{{ task.state.goal }}</strong><span>{{ labels[task.status] }} · 第 {{ task.state.round }} 轮</span></button>
          <div class="actions"><el-button size="small" :disabled="page === 1" @click="changePage(-1)">上一页</el-button><span>{{ page }}</span><el-button size="small" :disabled="tasks.length < 20" @click="changePage(1)">下一页</el-button></div>
        </section>
      </aside>
      <main class="task-card task-detail">
        <div v-if="!selected" class="empty-state"><h2>从一个具体问题开始</h2><p>计划、检索步骤和研究结果会保存在任务中。你可以暂停，也可以补充条件后继续。</p></div>
        <template v-else>
          <div class="task-heading"><h2>{{ selected.state.goal }}</h2><el-tag>{{ labels[selected.status] }}</el-tag></div>
          <p class="helper">第 {{ selected.state.round }} 轮 · {{ selected.state.sources.map(s => s.filename).join('、') }}</p>
          <div class="actions">
            <el-button v-if="selected.status === 'READY'" type="primary" :loading="busy" @click="command('START')">按计划开始研究</el-button>
            <el-button v-if="active" :disabled="busy" @click="command('PAUSE')">暂停</el-button>
            <el-button v-if="selected.status === 'PAUSED' || selected.status === 'FAILED'" type="primary" :disabled="busy || selected.state.activations >= 5" @click="command('RESUME')">从检查点继续</el-button>
            <el-button v-if="selected.status !== 'CANCELLED'" :disabled="busy" @click="command('CANCEL')">取消任务</el-button>
            <el-button v-if="!active" type="danger" plain :disabled="busy" @click="remove">删除任务</el-button>
            <el-button :disabled="busy" @click="selectTask(selected.id)">刷新</el-button>
          </div>
          <el-alert v-if="selected.state.errorCode" :title="`执行提示：${selected.state.errorCode}。可刷新状态，或补充条件开启新一轮。`" type="warning" :closable="false" />
          <el-alert v-if="historical" title="正在查看历史检查点，下面的计划和结果不是当前执行状态。" type="info" :closable="false"><el-button text @click="historical = null">返回当前轮次</el-button></el-alert>
          <template v-if="displayed">
            <section v-if="displayed.supplements.length"><h3>用户补充条件</h3><ul><li v-for="(text, i) in displayed.supplements" :key="i">{{ text }}</li></ul></section>
            <section v-if="displayed.plan?.questions.length"><h3>需要你补充</h3><ul><li v-for="question in displayed.plan.questions" :key="question">{{ question }}</li></ul></section>
            <section v-if="displayed.plan?.steps.length"><h3>研究计划</h3><ol class="steps"><li v-for="(step, i) in displayed.plan.steps" :key="i"><div><strong>{{ step.title }}</strong><el-tag size="small" :type="displayed.completed.some(s => s.index === i) ? 'success' : 'info'">{{ displayed.completed.some(s => s.index === i) ? '已完成' : '待执行' }}</el-tag></div><p>{{ step.query }}</p><small>{{ step.documentIds.map(id => displayed?.sources.find(s => s.id === id)?.filename ?? id).join('、') }}</small></li></ol></section>
            <p v-else-if="selected.status === 'PLANNING'" class="helper">正在分析研究目标并生成有限计划……</p>
            <section v-if="displayed.answer"><h3>研究结果</h3><div class="answer">{{ displayed.answer }}</div></section>
            <section v-if="displayed.evidence.length"><h3>引用依据</h3><details v-for="e in displayed.evidence" :key="e.id"><summary>[{{ e.id }}] {{ e.filename }} <span v-if="e.page">· 第 {{ e.page }} 页</span></summary><p class="excerpt">{{ e.text }}</p></details></section>
            <section v-if="displayed.calls.length"><h3>模型调用</h3><p v-for="(call, i) in displayed.calls" :key="i" class="helper">{{ call.phase === 'PLANNING' ? '计划' : '综合' }} · {{ call.status }} · {{ call.model ?? '模型信息待返回' }} · {{ call.totalTokens ?? '未知' }} tokens</p></section>
          </template>
          <section v-if="canSupplement" class="supplement"><h3>补充条件，继续研究</h3><el-input v-model="supplement" aria-label="补充条件" type="textarea" :rows="3" :maxlength="2000" show-word-limit placeholder="回答澄清问题，或补充新的约束。系统会保留历史，生成新一轮计划。" /><el-button type="primary" :disabled="busy || !supplement.trim()" @click="command('SUPPLEMENT')">补充并重新规划</el-button></section>
          <section v-if="events.length"><h3>任务历史</h3><p v-if="historyTruncated" class="helper">仅展示最近 100 个检查点。</p><div class="history"><button v-for="event in events" :key="event.id" @click="showSnapshot(event)">#{{ event.revision }} · {{ event.kind }} · {{ new Date(event.createdAt).toLocaleString() }}</button></div></section>
          <p class="trace">追踪编号：{{ selected.state.traceId }}</p>
        </template>
      </main>
    </div>
  </section>
</template>

<style scoped>
.task-page{max-width:1320px;margin:auto;padding:28px 24px}.page-intro{margin-bottom:24px}.page-intro h1{font-size:30px;margin:8px 0}.page-intro p{color:#64748b}.eyebrow{font-size:11px;letter-spacing:.16em}.workspace-grid{display:grid;grid-template-columns:330px minmax(0,1fr);gap:22px;margin-top:20px}.task-sidebar{display:flex;flex-direction:column;gap:18px}.task-card{border:1px solid #e2e8f0;border-radius:14px;padding:22px;background:white;min-width:0}.task-card h2{font-size:18px;margin:0 0 18px}.task-card h3{font-size:15px;margin:24px 0 12px}.helper{color:#64748b;font-size:13px;line-height:1.7}.task-item{display:block;text-align:left;width:100%;border:1px solid #e2e8f0;border-radius:8px;padding:12px;background:white;margin:8px 0;cursor:pointer}.task-item.selected{border-color:#0d9488;background:#f0fdfa}.task-item strong{display:block;overflow-wrap:anywhere;line-height:1.5}.task-item span{display:block;font-size:12px;color:#64748b;margin-top:6px}.actions{display:flex;gap:8px;align-items:center;flex-wrap:wrap;margin:16px 0}.actions .el-button+.el-button{margin-left:0}.task-heading{display:flex;justify-content:space-between;gap:12px;align-items:start}.task-heading h2{overflow-wrap:anywhere;line-height:1.6}.steps{padding-left:22px}.steps li{padding:12px 0;border-bottom:1px solid #f1f5f9}.steps li div{display:flex;justify-content:space-between;gap:8px}.steps p,.steps small{color:#64748b;overflow-wrap:anywhere}.answer,.excerpt{white-space:pre-wrap;overflow-wrap:anywhere;line-height:1.85}.answer{background:#f8fafc;padding:18px;border-radius:10px}.excerpt{font-size:13px}details{border-bottom:1px solid #e2e8f0;padding:12px 0}summary{cursor:pointer;overflow-wrap:anywhere}.supplement .el-button{margin-top:12px}.history{max-height:220px;overflow:auto;display:grid;gap:6px}.history button{border:0;background:#f8fafc;text-align:left;padding:10px;border-radius:6px;cursor:pointer;font-size:12px}.trace{font-size:11px;color:#94a3b8;overflow-wrap:anywhere;margin-top:28px}.empty-state{padding:60px 20px;color:#64748b;line-height:1.8}.task-detail li{overflow-wrap:anywhere}.el-select{width:100%}@media(max-width:850px){.workspace-grid{grid-template-columns:1fr}.task-page{padding:20px 12px}.task-heading{flex-wrap:wrap}.task-card{padding:16px}}
</style>
