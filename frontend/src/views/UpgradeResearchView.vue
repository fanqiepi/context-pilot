<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listKnowledgeBases, listDocuments, confirmActionRequest, rejectActionRequest, getActionRequest } from '@/api/contextPilot'
import type { KnowledgeBase, SourceDocument, ActionRequest } from '@/api/types'
import { errorMessage } from '@/api/client'
import * as api from '@/api/upgradeResearch'
import { renderResearchMarkdown } from '@/utils/markdown'

const route = useRoute(), router = useRouter()
const enabled = ref(false), busy = ref(false), error = ref(''), connection = ref('')
const knowledgeBases = ref<KnowledgeBase[]>([]), kb = ref(''), documents = ref<SourceDocument[]>([])
const selected = ref<string[]>([]), tags = ref(''), question = ref(''), title = ref('')
const runs = ref<api.UpgradeRun[]>([]), reports = ref<api.ResearchReport[]>([])
const view = ref<api.UpgradeView | null>(null), report = ref<api.ResearchReport | null>(null), sourcesCurrent = ref(true)
const proposal = ref<ActionRequest | null>(null)
let stream: EventSource | null = null
let startKey = '', startId = '', saveKey = '', saveId = ''
const terminal = (status: string) => !['QUEUED', 'RUNNING'].includes(status)
const active = computed(() => view.value && !terminal(view.value.run.status))
const eligible = computed(() => documents.value.filter(d => d.status === 'SUCCEEDED' && d.embeddingIndexCompatibility === 'CURRENT'))
const parseArray = <T,>(text: string | undefined | null): T[] => { try { const value: unknown = JSON.parse(text || '[]'); return Array.isArray(value) ? value as T[] : [] } catch { return [] } }
const sources = computed(() => report.value ? parseArray<api.UpgradeSource>(report.value.sources) : view.value?.sources ?? [])
const gaps = computed(() => parseArray<string>(report.value?.gaps ?? view.value?.run.gaps))
const content = computed(() => report.value?.body ?? view.value?.run.draft ?? '')
const statusName = (status: string) => ({ QUEUED: '等待执行', RUNNING: '研究中', SUCCEEDED: '已完成', PARTIAL: '部分完成', REFUSED: '依据不足', FAILED: '失败', CANCELLED: '已取消', PENDING_CONFIRMATION: '等待确认', EXECUTING: '保存中', REJECTED: '已拒绝', EXPIRED: '已过期' }[status] ?? status)
const safeSourceUrl = (source: api.UpgradeSource) => {
  const url = source.sourceUrl ?? source.url ?? ''
  return /^https:\/\/github\.com\/spring-projects\/spring-ai\/releases\/tag\/[A-Za-z0-9._-]{1,100}$/.test(url) ? url : undefined
}
function closeStream() { stream?.close(); stream = null; connection.value = '' }
async function guarded(action: () => Promise<void>) { busy.value = true; error.value = ''; try { await action() } catch (e) { error.value = errorMessage(e) } finally { busy.value = false } }
async function loadLists() {
  if (!kb.value) return
  const selectedKb = kb.value
  const [docs, history, saved] = await Promise.all([listDocuments(selectedKb), api.listUpgradeRuns(selectedKb), api.listReports(selectedKb)])
  if (kb.value !== selectedKb) return
  documents.value = docs; runs.value = history; reports.value = saved
}
async function changeKnowledgeBase() {
  closeStream(); view.value = null; report.value = null; proposal.value = null; selected.value = []
  await router.replace({ query: {} }); await guarded(loadLists)
}
function observe(id: string) {
  closeStream(); stream = new EventSource(`/api/upgrade-research/runs/${id}/events`)
  const receive = (event: MessageEvent<string>) => {
    if (view.value?.run.id !== id) return
    try { const update = JSON.parse(event.data) as api.UpgradeView
      if (update.run.revision >= view.value.run.revision) view.value = update
      connection.value = ''
    } catch { connection.value = '进度暂不可用，可刷新状态。' }
  }
  for (const name of ['snapshot', 'progress', 'result']) stream.addEventListener(name, receive as EventListener)
  stream.addEventListener('done', () => { closeStream(); void loadLists().catch(() => {}) })
  stream.onerror = () => { connection.value = '连接中断，正在重连。研究不会因此取消，可刷新状态。' }
}
async function openRun(id: string) {
  closeStream(); proposal.value = null; report.value = null
  const result = await api.getUpgradeRun(id)
  kb.value = result.run.knowledgeBaseId; view.value = result; title.value = '升级影响研究报告'
  selected.value = parseArray<{ id: string }>(result.run.documentSnapshots).map(d => d.id)
  tags.value = parseArray<string>(result.run.releaseTags).join(', '); question.value = result.run.question
  await router.replace({ query: { run: id } }); await loadLists()
  if (result.saveActionId) proposal.value = await getActionRequest(result.saveActionId)
  if (!terminal(result.run.status)) observe(id)
}
async function start(retry = false) {
  await guarded(async () => {
    const releaseTags = [...new Set(tags.value.split(/[,，\s]+/).filter(Boolean))]
    if (!kb.value || selected.value.length < 1 || selected.value.length > 3 || !question.value.trim()
      || releaseTags.length < 1 || releaseTags.length > 3 || releaseTags.some(tag => !/^[A-Za-z0-9._-]{1,100}$/.test(tag) || ['.', '..'].includes(tag))) throw new Error('请选择 1–3 份合格文档、填写 1–3 个准确发布标签和研究问题。')
    const input = { knowledgeBaseId: kb.value, documentIds: selected.value, repositoryKey: 'spring-ai', releaseTags, question: question.value.trim(), ...(retry && view.value ? { retryOfRunId: view.value.run.id } : {}) }
    const key = JSON.stringify(input)
    if (startKey !== key || retry) { startKey = key; startId = crypto.randomUUID() }
    const run = await api.startUpgradeRun({ ...input, clientRequestId: startId })
    startKey = ''; await openRun(run.id)
  })
}
async function saveProposal() {
  await guarded(async () => {
    if (!view.value) return
    const key = view.value.run.id + title.value.trim()
    if (saveKey !== key || (proposal.value && ['REJECTED', 'EXPIRED', 'FAILED'].includes(proposal.value.status))) { saveKey = key; saveId = crypto.randomUUID() }
    proposal.value = await api.proposeReport(view.value.run.id, { clientRequestId: saveId, title: title.value.trim() })
  })
}
async function decide(confirm: boolean) {
  await guarded(async () => {
    if (!proposal.value) return
    proposal.value = await (confirm ? confirmActionRequest(proposal.value.id) : rejectActionRequest(proposal.value.id))
    if (proposal.value.status === 'SUCCEEDED') { ElMessage.success('报告已保存'); await loadLists() }
  })
}
async function openReport(id: string) {
  closeStream(); const result = await api.getReport(id); report.value = result.report; sourcesCurrent.value = result.sourcesCurrent
  view.value = null; proposal.value = null; kb.value = result.report.knowledgeBaseId
  await router.replace({ query: { report: id } }); await loadLists()
}
async function removeReport(id: string) {
  try { await ElMessageBox.confirm('删除后报告将从列表隐藏，原记录保留。', '删除报告') } catch { return }
  await guarded(async () => { await api.deleteReport(id); if (report.value?.id === id) { report.value = null; await router.replace({ query: {} }) } await loadLists() })
}
onMounted(() => { void guarded(async () => {
  const [bases, available] = await Promise.all([listKnowledgeBases(), api.upgradeEnabled()]); knowledgeBases.value = bases; enabled.value = available
  if (typeof route.query.run === 'string') await openRun(route.query.run)
  else if (typeof route.query.report === 'string') await openReport(route.query.report)
  else if (bases[0]) { kb.value = bases[0].id; await loadLists() }
}) })
onUnmounted(closeStream)
</script>

<template>
  <section class="upgrade-page">
    <header><p class="eyebrow">UPGRADE RESEARCH</p><h1>升级影响研究</h1><p>对照旧方案与指定发布记录，形成可追溯的迁移清单。</p></header>
    <el-alert v-if="!enabled" title="升级研究当前未启用，仍可查看已有研究和报告。" type="info" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" role="alert" />
    <div class="upgrade-layout">
      <aside class="upgrade-panel">
        <el-form label-position="top">
          <el-form-item label="知识库"><el-select v-model="kb" placeholder="选择知识库" :disabled="busy" @change="changeKnowledgeBase"><el-option v-for="base in knowledgeBases" :key="base.id" :label="base.name" :value="base.id" /></el-select></el-form-item>
          <el-form-item label="旧版方案（1–3 份）"><el-select v-model="selected" multiple :multiple-limit="3" placeholder="选择已处理的当前索引文档" :disabled="busy || !!active"><el-option v-for="document in eligible" :key="document.id" :label="document.originalFilename" :value="document.id" /></el-select></el-form-item>
          <el-form-item label="发布记录来源"><span>spring-projects/spring-ai · 公开 GitHub Releases</span></el-form-item>
          <el-form-item label="发布标签（最多 3 个，逗号分隔）"><el-input v-model="tags" placeholder="例如 v2.0.0" :disabled="busy || !!active" /></el-form-item>
          <el-form-item label="研究问题"><el-input v-model="question" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="说明这次升级需要关注的变化" :disabled="busy || !!active" /></el-form-item>
          <p class="notice">仅覆盖所选文档和标签，不自动查阅中间版本。命中文档片段将发送给模型；发布正文仅作为不可信资料。</p>
          <el-button type="primary" :disabled="!enabled || !kb || !!active" :loading="busy" @click="start()">开始研究</el-button>
        </el-form>
        <h2>研究历史</h2><p v-if="!runs.length" class="muted">尚无研究记录</p>
        <button v-for="run in runs" :key="run.id" class="history-item" @click="guarded(() => openRun(run.id))"><strong>{{ run.question }}</strong><span>{{ statusName(run.status) }}</span></button>
        <h2>已保存报告</h2><p v-if="!reports.length" class="muted">确认保存后，报告会出现在这里。</p>
        <div v-for="saved in reports" :key="saved.id" class="saved-item"><button class="history-item" @click="guarded(() => openReport(saved.id))">{{ saved.title }}</button><el-button link type="danger" @click="removeReport(saved.id)">删除</el-button></div>
      </aside>
      <main class="upgrade-panel result-panel" aria-live="polite">
        <template v-if="view">
          <div class="result-heading"><h2>研究草稿</h2><el-tag>{{ statusName(view.run.status) }}</el-tag></div>
          <p class="muted">模型 {{ view.run.modelCalls }}/4 · 工具请求 {{ view.run.toolRequests }}/8 · 截止 {{ new Date(view.run.deadline).toLocaleTimeString() }}</p>
          <div class="actions"><el-button :disabled="busy" @click="guarded(() => openRun(view!.run.id))">刷新状态</el-button><el-button v-if="active" type="danger" :disabled="busy" @click="guarded(async () => { await api.cancelUpgradeRun(view!.run.id); await openRun(view!.run.id) })">取消研究</el-button><el-button v-if="['FAILED','CANCELLED'].includes(view.run.status)" :disabled="busy || !enabled" @click="start(true)">重新研究</el-button><el-button v-if="terminal(view.run.status)" link :disabled="busy" @click="guarded(async () => { await api.deleteUpgradeRun(view!.run.id); closeStream(); view = null; proposal = null; await router.replace({ query: {} }); await loadLists() })">隐藏运行</el-button></div>
          <el-alert v-if="connection" :title="connection" type="warning" :closable="false" />
          <el-alert v-if="view.run.errorCode" :title="`本次研究已结束：${view.run.errorCode}`" type="error" :closable="false" />
          <p v-if="view.run.status === 'REFUSED'">本地方案与发布记录不足以共同支持结论，未生成可保存草稿。</p>
          <ol v-if="view.tools.length" class="tool-list"><li v-for="tool in view.tools" :key="tool.ordinal"><strong>{{ tool.toolName }}</strong> · {{ statusName(tool.status) }}<span v-if="tool.cacheHit"> · 复用结果</span><small>{{ tool.parameterSummary }} {{ tool.errorCode }}</small></li></ol>
        </template>
        <template v-else-if="report"><div class="result-heading"><h2>{{ report.title }}</h2><el-tag type="success">已保存报告</el-tag></div><el-alert v-if="!sourcesCurrent" title="原始来源已变化或不可用；以下展示保存时的来源快照。" type="warning" :closable="false" /></template>
        <el-empty v-else description="开始研究，或从左侧选择已有记录" />
        <article v-if="content" class="answer-content" v-html="renderResearchMarkdown(content)" />
        <section v-if="gaps.length"><h3>资料缺口</h3><ul><li v-for="gap in gaps" :key="gap">{{ gap }}</li></ul></section>
        <section v-if="sources.length"><h3>来源证据</h3><details v-for="source in sources" :key="source.sourceId"><summary>[{{ source.sourceId }}] {{ source.sourceType === 'LOCAL' ? '本地方案' : '发布记录' }} {{ source.releaseTag ?? source.tag }} <span v-if="source.truncated">· 摘录已截断</span></summary><p>{{ source.excerpt }}</p><a v-if="safeSourceUrl(source)" :href="safeSourceUrl(source)" target="_blank" rel="noopener noreferrer">查看发布来源</a><small>读取于 {{ source.fetchedAt }}</small></details></section>
        <section v-if="view?.run.draft" class="save-panel"><h3>保存为研究报告</h3><p v-if="proposal?.status === 'SUCCEEDED'">报告已保存，可从“已保存报告”列表查看。</p><template v-else><p>草稿尚未保存为报告。请先创建提案，再单独确认。</p><el-input v-model="title" maxlength="120" placeholder="报告标题" aria-label="报告标题" /><el-button :disabled="busy || !title.trim()" @click="saveProposal">创建保存提案</el-button></template>
          <div v-if="proposal" class="proposal"><p>{{ proposal.displaySummary }}</p><el-tag>{{ statusName(proposal.status) }}</el-tag><div v-if="proposal.status === 'PENDING_CONFIRMATION'" class="actions"><el-button type="primary" :disabled="busy" @click="decide(true)">确认保存报告</el-button><el-button :disabled="busy" @click="decide(false)">拒绝</el-button></div><p v-if="proposal.errorSummary">{{ proposal.errorSummary }}</p><p v-if="proposal.resultSummary">{{ proposal.resultSummary }}</p></div>
        </section>
        <small v-if="view" class="trace">追踪标识：{{ view.run.traceId }}</small>
      </main>
    </div>
  </section>
</template>

<style scoped>
.upgrade-page{max-width:1440px;margin:auto;padding:24px}.upgrade-page header{margin-bottom:24px}.eyebrow{font-size:12px;letter-spacing:.16em;color:#64748b}.upgrade-layout{display:grid;grid-template-columns:340px minmax(0,1fr);gap:24px;margin-top:20px;align-items:start}.upgrade-panel{min-width:0;background:white;border:1px solid #dce4ed;border-radius:14px;padding:24px}.upgrade-panel h2{font-size:18px;margin-top:24px}.upgrade-panel .el-select{width:100%}.notice,.muted{font-size:13px;color:#64748b;line-height:1.7}.history-item{display:flex;flex-direction:column;gap:6px;text-align:left;background:#f8fafc;border:0;border-radius:8px;padding:12px;width:100%;margin:8px 0;cursor:pointer;overflow-wrap:anywhere;color:#334155}.history-item span{font-size:12px;color:#64748b}.saved-item{display:flex;gap:8px;align-items:center}.result-heading,.actions{display:flex;align-items:center;gap:12px;flex-wrap:wrap}.result-heading h2{margin:0}.actions{margin:16px 0}.tool-list{padding-left:22px}.tool-list li{padding:8px 0;overflow-wrap:anywhere}small{display:block;color:#64748b;font-size:12px;margin-top:6px}.answer-content{line-height:1.85;overflow-wrap:anywhere;margin:24px 0}.answer-content :deep(pre){overflow:auto}.answer-content :deep(table){display:block;overflow:auto}.save-panel{margin-top:28px;border-top:1px solid #dce4ed;padding-top:18px}.save-panel>.el-button{margin:12px 0}.proposal{background:#f0f7ff;border:1px solid #c6def7;border-radius:10px;padding:16px}.trace{margin-top:24px;overflow-wrap:anywhere}details{border:1px solid #e2e8f0;border-radius:8px;padding:12px;margin:8px 0;overflow-wrap:anywhere}summary{cursor:pointer;font-weight:600}.upgrade-page>.el-alert{margin:12px 0}@media(max-width:760px){.upgrade-page{padding:12px}.upgrade-layout{grid-template-columns:1fr;gap:14px}.upgrade-panel{padding:16px}.result-panel{min-height:300px}}
</style>
