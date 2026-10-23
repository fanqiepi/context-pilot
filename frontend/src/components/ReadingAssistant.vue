<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import ReadingNoteProposal from '@/components/ReadingNoteProposal.vue'
import { errorMessage } from '@/api/client'
import { readingGet, readingPost, readingDelete, type ReadingState, type ReadingSource } from '@/api/reading'

const props = defineProps<{ versionId: string; state?: ReadingState; ready: boolean; open: boolean }>()
const emit = defineEmits<{ continue: [source: ReadingSource]; close: []; saved: [] }>()
type Task = {
  id: string; type?: string; status: string; revision: number; visibility: string; errorCode?: string
  question?: string; message?: string; currentScopeRevision?: number; visibilityEpoch?: number
  scope?: { boundaryOrdinal: number }
  validatedResult?: { facts: { text: string; evidenceIds: string[] }[]; partial: boolean }
  coverage?: { requestedRange: number[]; completeness: string; completedParagraph: number; targetBoundary: number }
  budget?: { modelCalls: number; embeddingCalls: number; activeMillis: number; limitMillis: number }
}
type Authorization = { expiresAt: string; reason: string; stopReason?: string; providers: Record<string, { limitCny: number; budgetValuedCny: number; reservedCny: number }> }
type Index = { ready: boolean; readyBoundary: number; requestedBoundary: number; liveAllowed: boolean; executionMode: 'OFFLINE_FIXTURE' | 'DIRECT_LIVE' | 'READER_TRIAL' | 'EVALUATION_ONLY' | 'DISABLED'; activeJob: Task | null; authorization?: Authorization }
type Estimate = { fromBoundary: number; targetBoundary: number; requestedBoundary: number; segments: number; characters: number; batches: number; estimateHash: string; liveAllowed: boolean; estimatedCost: string | null }
type Citation = { sources: ReadingSource[]; scopeRevision: number; visibilityEpoch: number }
const tasks = ref<Task[]>([]), current = ref<Task>(), indexJob = ref<Task>(), index = ref<Index>(), estimate = ref<Estimate>()
const page = ref(1), question = ref(''), mode = ref('DIRECT'), chapterCount = ref(3), busy = ref(false), error = ref('')
const activeTab = ref('recap'), loaded = ref(false)
const canCreate = computed(() => !!index.value?.liveAllowed && ['OFFLINE_FIXTURE', 'READER_TRIAL', 'DIRECT_LIVE'].includes(index.value.executionMode))
const coverageLabels: Record<string, string> = { FULL_WINDOW: '已覆盖本次回顾窗口', SEARCH_MATCHES: '基于已读范围内的检索依据', PARTIAL: '部分问题仍缺少依据', NONE: '尚无可展示的回答' }
const sources = ref<ReadingSource[]>([]), preview = ref(false), previewPath = ref(''), connected = ref(true)
let generation = 0, selection = 0, citationRequest = 0, historyRequest = 0, events: EventSource | undefined, timer: ReturnType<typeof setTimeout> | undefined
const terminal = (t: Task) => ['COMPLETED', 'PARTIAL', 'INSUFFICIENT_EVIDENCE', 'FAILED', 'CANCELLED', 'INVALIDATED', 'READY'].includes(t.status)
const labels: Record<string, string> = { QUEUED: '排队中', RUNNING: '正在整理', PAUSED: '已暂停', CANCELLED: '已取消', INVALIDATED: '已读范围已变化', FAILED: '暂未完成', COMPLETED: '已完成', PARTIAL: '部分回答', INSUFFICIENT_EVIDENCE: '证据不足', READY: '已读内容已准备好' }
const errors: Record<string, string> = {
  COVERAGE_LIMIT: '窗口超过完整回顾上限，请选择一章或两章。',
  INDEX_NOT_READY: '请先为当前已读范围补齐索引。',
  INTERRUPTED_CALL: '调用结果未形成可恢复检查点，不能自动重发。请核对调用记录后主动创建新任务。',
  TIMEOUT: '本任务已到执行截止时间。', SCOPE_CHANGED: '已读范围曾回退，请重新确认范围后创建任务。',
  LIVE_AI_NOT_AUTHORIZED: '当前未开放新的 AI 任务。你仍可阅读正文、查看已有结果和保存笔记。',
  LIVE_BATCH_MISMATCH: '当前调用仅限已批准的固定评测任务，请继续阅读或查看已有结果。',
  LIVE_AUTHORIZATION_STOPPED: '本次试读授权已到期或停止，请查看费用状态。不能自动重启。',
  BUDGET_EXHAUSTED: '剩余额度不足以预留下一次调用，或已达到调用次数上限。本次试读已停止。',
}
function clear() {
  generation++; selection++; citationRequest++; historyRequest++; events?.close(); clearTimeout(timer)
  current.value = undefined; tasks.value = []; sources.value = []; preview.value = false
  index.value = undefined; estimate.value = undefined; indexJob.value = undefined; question.value = ''; error.value = ''; connected.value = true; loaded.value = false
}
function selectLatest() {
  const type = activeTab.value === 'character' ? 'CHARACTER_LOOKUP' : 'RECAP'
  const latest = tasks.value.find(t => t.visibility === 'VISIBLE' && t.type === type)
  if (latest) void select(latest)
  else { events?.close(); selection++; current.value = undefined; sources.value = []; preview.value = false }
}
function valid(g: number) { return g === generation && props.ready && props.open }
function visible(t: Task) {
  return t.visibility !== 'VISIBLE' || (t.currentScopeRevision === props.state?.scopeRevision && t.visibilityEpoch === props.state?.visibilityEpoch)
}
async function load() {
  if (!props.ready || !props.open) return
  const g = generation, request = ++historyRequest
  try {
    const [history, status] = await Promise.all([
      readingGet<{ items: Task[] }>('/tasks', { versionId: props.versionId, page: page.value }),
      readingGet<Index>(`/versions/${props.versionId}/index`),
    ])
    if (!valid(g) || request !== historyRequest) return
    tasks.value = history.items.filter(visible); index.value = status
    if (!loaded.value && !current.value) selectLatest()
    loaded.value = true
    if (status.activeJob) {
      indexJob.value = status.activeJob
      if (status.activeJob.status !== 'PAUSED') pollIndex(status.activeJob.id, g)
    }
  } catch (e) { if (valid(g)) error.value = errorMessage(e) }
}
async function select(task: Task) {
  events?.close(); sources.value = []; preview.value = false
  current.value = undefined
  const g = generation, request = ++selection
  try {
    const t = await readingGet<Task>(`/tasks/${task.id}`)
    if (!valid(g) || request !== selection || !visible(t)) return
    current.value = t
    if (!terminal(t) && t.visibility === 'VISIBLE') subscribe(t.id, g)
  } catch (e) { if (valid(g)) error.value = errorMessage(e) }
}
function subscribe(id: string, g: number) {
  events?.close(); events = new EventSource(`/api/reading/tasks/${id}/events`)
  const update = (event: MessageEvent<string>) => {
    if (!valid(g)) return
    try {
      const t = JSON.parse(event.data) as Task
      if (!visible(t)) { current.value = undefined; sources.value = []; preview.value = false; return }
      connected.value = true; current.value = t
      if (terminal(t)) { events?.close(); void load() }
    } catch { current.value = undefined; connected.value = false }
  }
  for (const name of ['snapshot', 'progress', 'result']) events.addEventListener(name, update)
  events.onerror = () => {
    if (!valid(g)) return
    connected.value = false; current.value = undefined; sources.value = []; preview.value = false
    // Native reconnect gets a fresh authorized snapshot; never recreate the task.
  }
}
async function create(type: string) {
  if (!props.ready || !props.state || !canCreate.value) return
  const g = generation
  try {
    await ElMessageBox.confirm(`允许使用正文开头至第 ${props.state.readBoundary} 段。${type === 'RECAP' ? `回顾最近 ${chapterCount.value} 个已读章节，一次生成。` : `人物查证：${mode.value === 'DIRECT' ? '一次检索、一次回答' : '一次规划、最多三次检索、一次回答'}。`}${index.value?.executionMode === 'READER_TRIAL' ? '问题和所需已读正文会发送至已配置的模型供应商，费用从本次试读预算扣除。' : index.value?.executionMode === 'DIRECT_LIVE' ? '问题和所需已读正文会发送至已配置的模型供应商，按供应商实际用量计费。' : '这是固定离线演示，不产生模型费用。'}`, '确认任务范围', { confirmButtonText: '创建任务', cancelButtonText: '返回阅读' })
    if (!valid(g)) return
    busy.value = true; error.value = ''
    const t = await readingPost<Task>('/tasks', { clientRequestId: crypto.randomUUID(), versionId: props.versionId, expectedScopeRevision: props.state.scopeRevision, type,
      ...(type === 'RECAP' ? { chapterCount: chapterCount.value } : { question: question.value, mode: mode.value }) })
    if (!valid(g)) return
    await select(t); await load()
  } catch (e) { if (valid(g) && e !== 'cancel' && e !== 'close') error.value = errorMessage(e) } finally { busy.value = false }
}
async function estimateIndex() {
  const g = generation
  try {
    const e = await readingGet<Estimate>(`/versions/${props.versionId}/index-estimate`, { expectedScopeRevision: props.state?.scopeRevision })
    if (valid(g)) estimate.value = e
  } catch (e) { if (valid(g)) error.value = errorMessage(e) }
}
async function createIndex() {
  if (!estimate.value || !props.state) return
  const g = generation; busy.value = true
  try {
    const t = await readingPost<Task>(`/versions/${props.versionId}/index-jobs`, { clientRequestId: crypto.randomUUID(), expectedScopeRevision: props.state.scopeRevision, estimateHash: estimate.value.estimateHash })
    if (valid(g)) { indexJob.value = t; pollIndex(t.id, g) }
  } catch (e) { if (valid(g)) error.value = errorMessage(e) } finally { busy.value = false }
}
function pollIndex(id: string, g: number) {
  clearTimeout(timer)
  timer = setTimeout(async () => {
    try {
      const t = await readingGet<Task>(`/index-jobs/${id}`)
      if (!valid(g)) return
      indexJob.value = t
      if (!terminal(t) && t.status !== 'PAUSED') pollIndex(id, g)
      else await load()
    } catch (e) { if (valid(g)) error.value = errorMessage(e) }
  }, 1500)
}
async function command(t: Task, command: string, isIndex = false) {
  const g = generation
  try {
    const updated = await readingPost<Task>(`/${isIndex ? 'index-jobs' : 'tasks'}/${t.id}/commands`, { clientRequestId: crypto.randomUUID(), expectedRevision: t.revision, command })
    if (!valid(g)) return
    if (isIndex) { indexJob.value = updated; pollIndex(t.id, g) } else { await select(updated); await load() }
  } catch (e) { if (valid(g)) error.value = errorMessage(e) }
}
async function citation(key: string) {
  if (!current.value) return
  const g = generation, request = ++citationRequest, selected = selection, path = `/tasks/${current.value.id}/citations/${key}`
  sources.value = []; preview.value = true
  try {
    const result = await readingGet<Citation>(path)
    if (valid(g) && request === citationRequest && selected === selection && preview.value && result.scopeRevision === props.state?.scopeRevision && result.visibilityEpoch === props.state.visibilityEpoch) {
      sources.value = result.sources; previewPath.value = path
    }
  } catch (e) { if (valid(g) && request === citationRequest && selected === selection) { preview.value = false; error.value = errorMessage(e) } }
}
async function continueReading(source: ReadingSource) {
  const g = generation, request = citationRequest, selected = selection, path = previewPath.value
  try {
    const result = await readingGet<Citation>(path)
    const verified = result.sources.find(s => s.paragraphId === source.paragraphId && s.startOffset === source.startOffset)
    if (valid(g) && request === citationRequest && selected === selection && preview.value && verified && result.scopeRevision === props.state?.scopeRevision && result.visibilityEpoch === props.state.visibilityEpoch) {
      preview.value = false; emit('continue', verified)
    }
  } catch (e) { if (valid(g) && request === citationRequest && selected === selection) error.value = errorMessage(e) }
}
async function remove(t: Task) {
  try { await readingDelete(`/tasks/${t.id}`, t.revision); if (current.value?.id === t.id) current.value = undefined; await load() } catch (e) { error.value = errorMessage(e) }
}
watch(activeTab, () => { if (activeTab.value !== 'history' && loaded.value) selectLatest() })
watch(() => [props.versionId, props.ready, props.state?.scopeRevision, props.open], () => { clear(); page.value = 1; void load() }, { immediate: true })
onUnmounted(clear)
</script>
<template>
  <div class="reading-assistant">
    <div class="reading-assistant-scope"><strong>只看你已读过的内容</strong><p>从开头至第 {{ state?.readBoundary || 0 }} 段。回顾和查证不会推进阅读进度。</p></div>
    <el-alert v-if="index?.executionMode === 'OFFLINE_FIXTURE'" title="固定离线演示：结果用于体验流程，不是现场模型生成，也不代表语义验收。" type="warning" :closable="false" />
    <el-alert v-else-if="index?.executionMode === 'READER_TRIAL'" title="真人试读：确认后调用真实模型，仅使用你确认的已读范围。回答质量仍需你核对。" type="info" :closable="false" />
    <el-alert v-else-if="index?.executionMode === 'DIRECT_LIVE'" title="真实模型已启用：仅使用你确认的已读范围，按供应商实际用量计费。" type="info" :closable="false" />
    <el-alert v-else-if="index" :title="index.executionMode === 'EVALUATION_ONLY' ? '阅读 AI 正在固定评测中，暂不开放自由提问；已有结果仍可查看。' : '阅读 AI 仍在验证中，当前未开放新任务。你可以查看已有结果，或直接回到正文。'" type="info" :closable="false" />
    <div v-if="index?.authorization" class="reading-trial-budget">
      <p>试读授权截至 {{ new Date(index.authorization.expiresAt).toLocaleString() }}；状态：{{ index.authorization.stopReason || index.authorization.reason }}</p>
      <p v-for="(provider, name) in index.authorization.providers" :key="name">{{ name === 'DEEPSEEK' ? 'DeepSeek' : '阿里向量' }}：上限 ¥{{ provider.limitCny }}，已计价 ¥{{ provider.budgetValuedCny }}，预留 ¥{{ provider.reservedCny }}</p>
      <small>按保守单价计价，非供应商账单。到期、费用或次数不足、调用异常后停止，不自动重试。</small>
    </div>
    <el-alert v-if="error" :title="error" type="error" @close="error = ''" />
    <el-alert v-if="!ready || !connected" title="连接或范围正在重验，结果暂时隐藏。" type="warning" :closable="false" />
    <template v-if="ready">
      <el-alert v-if="!state?.readBoundary" title="先在正文中选择“已读到这里”，明确允许回顾的范围。" type="info" :closable="false" />
      <el-tabs v-model="activeTab" class="reading-assistant-tabs"><el-tab-pane label="前情回顾" name="recap" /><el-tab-pane label="人物查证" name="character" /><el-tab-pane label="历史记录" name="history" /></el-tabs>
      <section v-show="activeTab === 'recap'" aria-label="前情回顾设置">
      <h3>前情回顾</h3>
      <p class="reading-assistant-hint">先捡起最近的事件，再带着上下文继续阅读。</p>
      <el-select v-model="chapterCount" aria-label="回顾章节数量"><el-option v-for="n in 3" :key="n" :value="n" :label="`最近 ${n} 个已读章节`" /></el-select>
      <el-button :disabled="!state?.readBoundary || !canCreate" :loading="busy" @click="create('RECAP')">确认范围并回顾</el-button>
      </section>
      <section v-show="activeTab === 'character'" aria-label="人物查证设置">
      <h3>人物查证</h3>
      <el-input v-model="question" type="textarea" :rows="3" maxlength="2000" placeholder="例如：林舟与青禾是什么关系？" aria-label="人物问题" />
      <small>{{ Array.from(question).length }} / 1000 字符</small>
      <el-radio-group v-model="mode"><el-radio value="DIRECT">直接查证</el-radio><el-radio value="MULTI_QUERY">多线索查证</el-radio></el-radio-group>
      <el-button :disabled="!canCreate || !question.trim() || Array.from(question).length > 1000 || !index?.ready" :loading="busy" @click="create('CHARACTER_LOOKUP')">确认范围并查证</el-button>
      <details class="reading-task-details"><summary>已读内容准备情况</summary>
      <p>可检索至第 {{ index?.readyBoundary || 0 }} 段 / 已读 {{ state?.readBoundary || 0 }} 段。前情回顾无需等待检索准备。</p>
      <el-button :disabled="!state?.readBoundary || !canCreate" @click="estimateIndex">查看准备范围与费用</el-button>
      <div v-if="estimate">
        <p v-if="estimate.segments">本批处理第 {{ estimate.fromBoundary + 1 }}–{{ estimate.targetBoundary }} 段，{{ estimate.characters }} 字、{{ estimate.segments }} 片段、{{ estimate.batches }} 次 embedding 请求。</p>
        <p v-else>当前已读范围的索引已完整就绪，无需重复建立。</p>
        <p v-if="index?.executionMode === 'DIRECT_LIVE'">按供应商实际用量计费；未设置金额预算。</p>
        <p v-else>费用上界：{{ estimate.estimatedCost || '尚无已核实计价与授权，不能开启真实调用' }}</p>
        <el-button :disabled="!estimate.liveAllowed || estimate.segments === 0" :loading="busy" @click="createIndex">确认建立本批索引</el-button>
      </div>
      <div v-if="indexJob">
        <p>索引：{{ labels[indexJob.status] || indexJob.status }} {{ indexJob.errorCode || '' }}</p>
        <template v-if="!terminal(indexJob)"><el-button v-if="indexJob.status === 'PAUSED'" @click="command(indexJob, 'RESUME', true)">恢复</el-button><el-button v-else @click="command(indexJob, 'PAUSE', true)">暂停</el-button><el-button @click="command(indexJob, 'CANCEL', true)">取消</el-button></template>
      </div>
      </details>
      </section>
      <section v-show="activeTab === 'history'" aria-label="任务历史">
      <h3>本版本任务</h3>
      <el-button @click="load">刷新历史</el-button>
      <div v-for="t in tasks" :key="t.id" class="reading-record">
        <el-button text @click="select(t)">{{ t.visibility === 'HIDDEN_BY_SCOPE' ? '超出当前已读范围' : t.type === 'RECAP' ? '前情回顾' : '人物查证' }} · {{ labels[t.status] || t.status }}</el-button>
        <p v-if="t.visibility === 'VISIBLE' && t.question" class="reading-history-question">{{ t.question }}</p>
        <el-button v-if="terminal(t)" text @click="remove(t)">移除</el-button>
      </div>
      <el-button :disabled="page === 1" @click="page--; load()">上一页</el-button><el-button :disabled="tasks.length < 20" @click="page++; load()">下一页</el-button>
      </section>
      <el-empty v-if="loaded && !current && activeTab !== 'history'" :description="activeTab === 'recap' ? '这个版本还没有可查看的回顾' : '这个版本还没有可查看的人物查证'" :image-size="80" />
      <el-empty v-if="loaded && !tasks.length && activeTab === 'history'" description="任务记录会保存在这里" :image-size="80" />
      <article v-if="current && connected" class="reading-answer">
        <p v-if="current.visibility !== 'VISIBLE'">此任务超出当前已读范围。</p>
        <template v-else>
          <h3>{{ current.type === 'RECAP' ? '前情回顾' : '人物查证' }} · {{ labels[current.status] || current.status }}</h3>
          <p class="reading-assistant-hint">截至第 {{ current.scope?.boundaryOrdinal }} 段 · {{ coverageLabels[current.coverage?.completeness || 'NONE'] }}</p>
          <p v-if="current.question">{{ current.question }}</p>
          <p v-if="current.errorCode">{{ errors[current.errorCode] || current.errorCode }}</p><p v-if="current.message">{{ current.message }}</p>
          <p v-for="(fact, n) in current.validatedResult?.facts" :key="n">{{ fact.text }} <el-button v-for="id in fact.evidenceIds" :key="id" text @click="citation(id)">[{{ id }}] 原文</el-button></p>
          <ReadingNoteProposal v-if="state && ['COMPLETED', 'PARTIAL'].includes(current.status) && current.validatedResult?.facts.length" :key="current.id" :task-id="current.id" :task-type="current.type || 'RECAP'" :facts="current.validatedResult.facts" :state="state" @saved="emit('saved')" />
          <details v-if="current.budget" class="reading-task-details"><summary>任务详情</summary><p>生成 {{ current.budget.modelCalls }} 次、检索向量调用 {{ current.budget.embeddingCalls }} 次；累计执行 {{ (current.budget.activeMillis / 1000).toFixed(1) }} 秒。未知用量不按零费用记账。</p></details>
          <template v-if="!terminal(current)"><el-button v-if="current.status === 'PAUSED'" @click="command(current, 'RESUME')">恢复任务</el-button><el-button v-else @click="command(current, 'PAUSE')">暂停</el-button><el-button @click="command(current, 'CANCEL')">取消任务</el-button></template>
        </template>
      </article>
    </template>
    <el-dialog v-model="preview" title="任务引用原文" width="min(92vw, 640px)" append-to-body @close="sources = []; citationRequest++">
      <div v-for="s in sources" :key="`${s.paragraphId}/${s.startOffset}`"><p>第 {{ s.chapterOrdinal }} 章 {{ s.chapterTitle || '' }} · 第 {{ s.paragraphOrdinal }} 段</p><blockquote style="white-space: pre-wrap">{{ s.text }}</blockquote><el-button @click="continueReading(s)">从这里继续读</el-button></div>
      <template #footer><el-button @click="preview = false">关闭预览，返回原位</el-button></template>
    </el-dialog>
  </div>
</template>
