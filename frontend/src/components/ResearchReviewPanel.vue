<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import type { ResearchTask } from '@/api/researchTasks'
import { cancelReview, createReview, deleteReview, getReview, listReviews, type ResearchReview } from '@/api/researchReviews'
import { errorMessage } from '@/api/client'

const props = defineProps<{ task: ResearchTask; historical: boolean }>()
const emit = defineEmits<{ supplement: [text: string] }>()
const reviews = ref<ResearchReview[]>([])
const selected = ref<ResearchReview | null>(null)
const busy = ref(false)
const error = ref('')
const page = ref(1)
let timer: ReturnType<typeof setTimeout> | undefined
let epoch = 0
let disposed = false
let requestKey = crypto.randomUUID()
let requestFingerprint = ''
const active = computed(() => selected.value?.status === 'QUEUED' || selected.value?.status === 'RUNNING')
const labels = { QUEUED: '等待审查', RUNNING: '正在审查', COMPLETED: '审查完成', FAILED: '审查失败', CANCELLED: '已取消' }
const verdicts = { SUPPORTED: '证据支持', PARTIAL: '部分支持', UNSUPPORTED: '缺少支持', CONTRADICTED: '与证据矛盾' }
const overall = { ISSUES_FOUND: '发现需要核对的问题', NEEDS_MORE_EVIDENCE: '需要补充证据', NO_ISSUES_FOUND: '本次审查未发现问题' }
const current = computed(() => selected.value?.freshness === 'CURRENT' && selected.value.taskRevision === props.task.revision)
function valid(ticket: number) { return !disposed && ticket === epoch }
async function reload(ticket = epoch) {
  const items = await listReviews(props.task.id, page.value)
  if (valid(ticket)) reviews.value = items
}
function poll(ticket: number) {
  clearTimeout(timer)
  if (!valid(ticket) || !active.value) return
  timer = setTimeout(async () => {
    try {
      const result = await getReview(selected.value!.id)
      if (!valid(ticket)) return
      selected.value = result
      if (active.value) poll(ticket)
      else await reload(ticket)
    } catch (e) { if (valid(ticket)) error.value = errorMessage(e, '审查状态更新失败，请刷新。') }
  }, 1500)
}
async function select(id: string) {
  const ticket = ++epoch; clearTimeout(timer); error.value = ''
  try { const result = await getReview(id); if (valid(ticket)) { selected.value = result; poll(ticket) } }
  catch (e) { if (valid(ticket)) error.value = errorMessage(e) }
}
async function refresh() {
  const ticket = ++epoch; clearTimeout(timer); error.value = ''
  try {
    await reload(ticket)
    if (!valid(ticket)) return
    const id = reviews.value.find(r => r.id === selected.value?.id)?.id ?? reviews.value[0]?.id
    if (id) await select(id); else selected.value = null
  } catch (e) { if (valid(ticket)) error.value = errorMessage(e) }
}
async function start() {
  const ticket = epoch; busy.value = true; error.value = ''
  const fingerprint = `${props.task.id}:${props.task.revision}`
  if (fingerprint !== requestFingerprint) { requestFingerprint = fingerprint; requestKey = crypto.randomUUID() }
  try {
    const result = await createReview(props.task.id, props.task.revision, requestKey)
    if (!valid(ticket)) return
    requestKey = crypto.randomUUID(); page.value = 1
    await reload(ticket); await select(result.id)
  } catch (e) { if (valid(ticket)) error.value = errorMessage(e) }
  finally { busy.value = false }
}
async function mutate(operation: 'cancel' | 'delete') {
  if (!selected.value) return
  busy.value = true; error.value = ''
  const ticket = epoch
  try {
    if (operation === 'cancel') await cancelReview(selected.value)
    else await deleteReview(selected.value)
    if (valid(ticket)) await refresh()
  } catch (e) { if (valid(ticket)) error.value = errorMessage(e) }
  finally { busy.value = false }
}
async function changePage(delta: number) { page.value += delta; selected.value = null; await refresh() }
watch(() => [props.task.id, props.task.revision], async () => {
  ++epoch; clearTimeout(timer); selected.value = null; reviews.value = []; page.value = 1
  await refresh()
}, { immediate: true })
onUnmounted(() => { disposed = true; ++epoch; clearTimeout(timer) })
</script>

<template>
  <section class="review-panel">
    <h3>研究成果审查</h3>
    <p class="helper">审查当前结论与本轮证据的支持关系。模型审查可能遗漏或误判，需要你结合原文核对。</p>
    <div class="review-actions">
      <el-button type="primary" plain :disabled="busy || historical || task.status !== 'COMPLETED' || reviews.some(r => r.status === 'RUNNING' || r.status === 'QUEUED')" @click="start">审查当前结果</el-button>
      <el-button :disabled="busy" @click="refresh">刷新审查</el-button>
    </div>
    <p v-if="task.status !== 'COMPLETED'" class="helper">研究完成且有证据后可以发起审查；资料不足不会被标为审查通过。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <p v-if="!reviews.length" class="helper">暂无审查记录。每个结果快照最多尝试三次，失败和取消也计入。</p>
    <div class="review-history"><button v-for="review in reviews" :key="review.id" :class="{ chosen: selected?.id === review.id }" @click="select(review.id)">第 {{ review.round }} 轮 · {{ labels[review.status] }} · {{ new Date(review.createdAt).toLocaleString() }}</button></div>
    <div v-if="reviews.length || page > 1" class="review-actions"><el-button size="small" :disabled="page === 1 || busy" @click="changePage(-1)">上一页</el-button><span>{{ page }}</span><el-button size="small" :disabled="reviews.length < 20 || busy" @click="changePage(1)">下一页</el-button></div>
    <template v-if="selected">
      <h4>{{ labels[selected.status] }}<span v-if="selected.overallVerdict"> · {{ overall[selected.overallVerdict] }}</span></h4>
      <el-alert v-if="!current" :title="selected.freshness === 'SOURCE_CHANGED' ? '来源已变化或删除，原文及审查内容已隐藏。请基于有效资料重新研究。' : '这是历史结果的审查，不代表当前轮次。'" type="warning" :closable="false" />
      <p v-if="selected.errorCode" class="helper">执行提示：{{ selected.errorCode }}。请刷新状态核对；重试会创建新的审查记录。</p>
      <div class="review-actions"><el-button v-if="active" :disabled="busy" @click="mutate('cancel')">取消审查</el-button><el-button v-else type="danger" plain :disabled="busy" @click="mutate('delete')">删除审查</el-button></div>
      <details v-if="selected.snapshot?.answer"><summary>被审查的原始结果</summary><p class="review-text">{{ selected.snapshot.answer }}</p></details>
      <template v-if="selected.result">
        <article v-for="(claim, index) in selected.result.claims" :key="index" class="review-claim">
          <strong>{{ verdicts[claim.verdict] }}</strong><blockquote>{{ claim.claim }}</blockquote><p class="review-text">{{ claim.explanation }}</p>
          <details v-for="id in claim.evidenceIds" :key="id"><summary>[{{ id }}] {{ selected.snapshot?.evidence.find(e => e.id === id)?.filename }}</summary><p class="review-text">{{ selected.snapshot?.evidence.find(e => e.id === id)?.text }}</p></details>
        </article>
        <section v-if="selected.result.gaps.length"><h4>问题与资料缺口</h4><ul><li v-for="gap in selected.result.gaps" :key="gap">{{ gap }}</li></ul></section>
        <section v-if="selected.result.suggestedSupplement"><h4>建议补充</h4><p class="review-text">{{ selected.result.suggestedSupplement }}</p><el-button :disabled="!current || historical || task.state.round >= 5 || task.status !== 'COMPLETED'" @click="emit('supplement', selected.result.suggestedSupplement)">填入补充框，编辑后提交</el-button><p class="helper">此按钮只填写建议。提交补充后才开始新一轮研究，审查结果不会成为证据。</p></section>
      </template>
      <p v-if="selected.usage" class="helper">审查调用：{{ selected.usage.status }} · {{ selected.usage.model ?? '未知模型' }} · {{ selected.usage.totalTokens ?? '未知' }} tokens</p>
      <p class="helper">追踪编号：{{ selected.traceId }}</p>
    </template>
  </section>
</template>

<style scoped>
.review-panel{margin-top:28px;border-top:1px solid #e2e8f0;padding-top:12px;overflow-wrap:anywhere}.helper{font-size:13px;color:#64748b;line-height:1.7}.review-actions{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:12px 0}.review-actions .el-button{margin-left:0}.review-history{display:grid;gap:6px;max-height:180px;overflow:auto}.review-history button{text-align:left;padding:10px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:6px;cursor:pointer}.review-history .chosen{border-color:#0d9488}.review-text,blockquote{white-space:pre-wrap;line-height:1.8;overflow-wrap:anywhere}.review-claim{padding:14px;border:1px solid #e2e8f0;border-radius:8px;margin:12px 0}.review-claim blockquote{margin:12px 0;padding-left:12px;border-left:3px solid #cbd5e1}details{margin:10px 0}summary{cursor:pointer}.review-panel li{line-height:1.8}
</style>
