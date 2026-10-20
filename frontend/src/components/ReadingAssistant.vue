<script setup lang="ts">
import { onUnmounted, ref, watch } from 'vue'
import { ElMessageBox } from 'element-plus'
import { errorMessage } from '@/api/client'
import { readingGet, readingPost, readingDelete, type ReadingState, type ReadingSource } from '@/api/reading'

const props = defineProps<{ versionId: string; state?: ReadingState; ready: boolean; open: boolean }>()
const emit = defineEmits<{ continue: [source: ReadingSource]; close: [] }>()
type Task = {
  id: string; type?: string; status: string; revision: number; visibility: string; errorCode?: string
  question?: string; message?: string; currentScopeRevision?: number; visibilityEpoch?: number
  scope?: { boundaryOrdinal: number }
  validatedResult?: { facts: { text: string; evidenceIds: string[] }[]; partial: boolean }
  coverage?: { requestedRange: number[]; completeness: string; completedParagraph: number; targetBoundary: number }
  budget?: { modelCalls: number; embeddingCalls: number; activeMillis: number; limitMillis: number }
}
type Index = { ready: boolean; readyBoundary: number; requestedBoundary: number; liveAllowed: boolean; activeJob: Task | null }
type Estimate = { fromBoundary: number; targetBoundary: number; requestedBoundary: number; segments: number; characters: number; batches: number; estimateHash: string; liveAllowed: boolean; estimatedCost: string | null }
type Citation = { sources: ReadingSource[]; scopeRevision: number; visibilityEpoch: number }
const tasks = ref<Task[]>([]), current = ref<Task>(), indexJob = ref<Task>(), index = ref<Index>(), estimate = ref<Estimate>()
const page = ref(1), question = ref(''), mode = ref('DIRECT'), chapterCount = ref(3), busy = ref(false), error = ref('')
const sources = ref<ReadingSource[]>([]), preview = ref(false), previewPath = ref(''), connected = ref(true)
let generation = 0, selection = 0, citationRequest = 0, historyRequest = 0, events: EventSource | undefined, timer: ReturnType<typeof setTimeout> | undefined
const terminal = (t: Task) => ['COMPLETED', 'PARTIAL', 'INSUFFICIENT_EVIDENCE', 'FAILED', 'CANCELLED', 'INVALIDATED', 'READY'].includes(t.status)
const labels: Record<string, string> = { QUEUED: '排队中', RUNNING: '执行中', PAUSED: '已暂停', CANCELLED: '已取消', INVALIDATED: '范围失效', FAILED: '技术失败', COMPLETED: '已完成结构校验', PARTIAL: '部分回答', INSUFFICIENT_EVIDENCE: '证据不足', READY: '本批索引就绪' }
const errors: Record<string, string> = {
  COVERAGE_LIMIT: '窗口超过完整回顾上限，请选择一章或两章。',
  INDEX_NOT_READY: '请先为当前已读范围补齐索引。',
  INTERRUPTED_CALL: '调用结果未形成可恢复检查点，不能自动重发。请核对调用记录后主动创建新任务。',
  TIMEOUT: '本任务已到执行截止时间。', SCOPE_CHANGED: '已读范围曾回退，请重新确认范围后创建任务。',
  LIVE_AI_NOT_AUTHORIZED: '真实阅读 AI 尚未启用，授权额度为零。正文、书签与手写笔记仍可使用。',
}
function clear() {
  generation++; selection++; citationRequest++; historyRequest++; events?.close(); clearTimeout(timer)
  current.value = undefined; tasks.value = []; sources.value = []; preview.value = false
  index.value = undefined; estimate.value = undefined; indexJob.value = undefined; question.value = ''; error.value = ''; connected.value = true
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
    if (status.activeJob) {
      indexJob.value = status.activeJob
      if (status.activeJob.status !== 'PAUSED') pollIndex(status.activeJob.id, g)
    }
  } catch (e) { if (valid(g)) error.value = errorMessage(e) }
}
async function select(task: Task) {
  events?.close(); sources.value = []; preview.value = false
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
  if (!props.ready || !props.state) return
  const g = generation
  try {
    await ElMessageBox.confirm(`允许使用正文开头至第 ${props.state.readBoundary} 段。${type === 'RECAP' ? `回顾最近 ${chapterCount.value} 个已读章节，一次生成。` : `人物查证：${mode.value === 'DIRECT' ? '一次检索、一次回答' : '一次规划、最多三次检索、一次回答'}。`}真实调用须另获费用授权。`, '确认任务范围', { confirmButtonText: '创建任务', cancelButtonText: '返回阅读' })
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
watch(() => [props.versionId, props.ready, props.state?.scopeRevision, props.open], () => { clear(); page.value = 1; void load() }, { immediate: true })
onUnmounted(clear)
</script>
<template>
  <div class="reading-assistant">
    <p>已读范围：开头至第 {{ state?.readBoundary || 0 }} 段。续读位置不会自动扩大此范围。</p>
    <el-alert title="阅读 AI 尚未完成真实模型与语义验收。真实调用默认关闭，授权额度为零。" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" @close="error = ''" />
    <el-alert v-if="!ready || !connected" title="连接或范围正在重验，结果暂时隐藏。" type="warning" :closable="false" />
    <template v-if="ready">
      <h3>前情回顾</h3>
      <el-select v-model="chapterCount" aria-label="回顾章节数量"><el-option v-for="n in 3" :key="n" :value="n" :label="`最近 ${n} 个已读章节`" /></el-select>
      <el-button :disabled="!state?.readBoundary" :loading="busy" @click="create('RECAP')">确认范围并回顾</el-button>
      <h3>人物查证</h3>
      <el-input v-model="question" type="textarea" :rows="3" maxlength="2000" placeholder="例如：林舟与青禾是什么关系？" aria-label="人物问题" />
      <small>{{ Array.from(question).length }} / 1000 字符</small>
      <el-radio-group v-model="mode"><el-radio value="DIRECT">直接查证</el-radio><el-radio value="MULTI_QUERY">多线索查证</el-radio></el-radio-group>
      <el-button :disabled="!question.trim() || Array.from(question).length > 1000 || !index?.ready" :loading="busy" @click="create('CHARACTER_LOOKUP')">确认范围并查证</el-button>
      <p>连续索引就绪至第 {{ index?.readyBoundary || 0 }} 段 / 已读 {{ state?.readBoundary || 0 }} 段。回顾无需索引。</p>
      <el-button :disabled="!state?.readBoundary" @click="estimateIndex">估算已读部分索引</el-button>
      <div v-if="estimate">
        <p v-if="estimate.segments">本批处理第 {{ estimate.fromBoundary + 1 }}–{{ estimate.targetBoundary }} 段，{{ estimate.characters }} 字、{{ estimate.segments }} 片段、{{ estimate.batches }} 次 embedding 请求。</p>
        <p v-else>当前已读范围的索引已完整就绪，无需重复建立。</p>
        <p>费用上界：{{ estimate.estimatedCost || '尚无已核实计价与授权，不能开启真实调用' }}</p>
        <el-button :disabled="!estimate.liveAllowed || estimate.segments === 0" :loading="busy" @click="createIndex">确认建立本批索引</el-button>
      </div>
      <div v-if="indexJob">
        <p>索引：{{ labels[indexJob.status] || indexJob.status }} {{ indexJob.errorCode || '' }}</p>
        <template v-if="!terminal(indexJob)"><el-button v-if="indexJob.status === 'PAUSED'" @click="command(indexJob, 'RESUME', true)">恢复</el-button><el-button v-else @click="command(indexJob, 'PAUSE', true)">暂停</el-button><el-button @click="command(indexJob, 'CANCEL', true)">取消</el-button></template>
      </div>
      <h3>本版本任务</h3>
      <el-button @click="load">刷新历史</el-button>
      <div v-for="t in tasks" :key="t.id" class="reading-record">
        <el-button text @click="select(t)">{{ t.visibility === 'HIDDEN_BY_SCOPE' ? '超出当前已读范围' : t.type === 'RECAP' ? '前情回顾' : '人物查证' }} · {{ labels[t.status] || t.status }}</el-button>
        <el-button v-if="terminal(t)" text @click="remove(t)">移除</el-button>
      </div>
      <el-button :disabled="page === 1" @click="page--; load()">上一页</el-button><el-button :disabled="tasks.length < 20" @click="page++; load()">下一页</el-button>
      <article v-if="current && connected">
        <p v-if="current.visibility !== 'VISIBLE'">此任务超出当前已读范围。</p>
        <template v-else>
          <h3>{{ labels[current.status] || current.status }}</h3>
          <p>冻结边界：第 {{ current.scope?.boundaryOrdinal }} 段 · 覆盖：{{ current.coverage?.completeness }}</p>
          <p v-if="current.question">{{ current.question }}</p>
          <p v-if="current.errorCode">{{ errors[current.errorCode] || current.errorCode }}</p><p v-if="current.message">{{ current.message }}</p>
          <p v-for="(fact, n) in current.validatedResult?.facts" :key="n">{{ fact.text }} <el-button v-for="id in fact.evidenceIds" :key="id" text @click="citation(id)">[{{ id }}] 原文</el-button></p>
          <p v-if="current.budget">已预留生成 {{ current.budget.modelCalls }} 次、embedding {{ current.budget.embeddingCalls }} 次；累计执行 {{ (current.budget.activeMillis / 1000).toFixed(1) }} 秒。未知 usage 不按零费用记账。</p>
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
