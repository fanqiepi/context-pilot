<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { listDocuments, listKnowledgeBases } from '@/api/contextPilot'
import { errorMessage } from '@/api/client'
import type { KnowledgeBase, SourceDocument } from '@/api/types'
import { createPreview, getPreview, sourceCatalog, proposeImport, getImport, decideImport, importDocuments,
  type ProjectPreview, type ImportProposal } from '@/api/projectSources'

const route = useRoute(), router = useRouter()
const enabled = ref(false), busy = ref(false), error = ref('')
const version = ref(''), paths = ref('README.md'), base = ref('')
const bases = ref<KnowledgeBase[]>([]), documents = ref<SourceDocument[]>([])
const preview = ref<ProjectPreview | null>(null), proposal = ref<ImportProposal | null>(null)
const active = computed(() => preview.value?.status === 'PENDING' || preview.value?.status === 'RUNNING')
const expired = computed(() => !preview.value || new Date(preview.value.expiresAt).getTime() <= Date.now())
const labels: Record<string, string> = { PENDING: '排队中', RUNNING: '正在读取', READY: '可预览', FAILED: '失败',
  PENDING_CONFIRMATION: '等待确认', EXECUTING: '正在登记', SUCCEEDED: '登记成功', REJECTED: '已拒绝', EXPIRED: '已过期' }
let timer: ReturnType<typeof setTimeout> | undefined
let disposed = false
let createKey = crypto.randomUUID(), createFingerprint = ''
let proposalKey = crypto.randomUUID(), proposalFingerprint = ''

async function run(task: () => Promise<void>) {
  if (busy.value) return
  busy.value = true; error.value = ''; clearTimeout(timer)
  try { await task() } catch (e) { error.value = errorMessage(e) }
  finally { busy.value = false; schedule() }
}
async function refresh() {
  if (preview.value) preview.value = await getPreview(preview.value.id)
  if (proposal.value) {
    const action = await getImport(proposal.value.id)
    if (action.parameters.previewId !== preview.value?.id) throw new Error('导入提案与当前预览不匹配')
    proposal.value = action
    if (action.status === 'SUCCEEDED') {
      const ids = await importDocuments(action.id)
      documents.value = (await listDocuments(action.parameters.knowledgeBaseId)).filter(d => ids.includes(d.id))
    }
  }
}
function schedule() {
  clearTimeout(timer)
  if (disposed || error.value) return
  const processing = documents.value.some(d => d.status === 'PENDING' || d.status === 'PROCESSING')
  if (active.value || proposal.value?.status === 'EXECUTING' || processing) {
    timer = setTimeout(() => run(refresh), 1500)
  }
}
async function create() {
  await run(async () => {
    const filePaths = paths.value.split('\n').map(p => p.trim()).filter(Boolean)
    const fingerprint = JSON.stringify([version.value.trim(), filePaths])
    if (fingerprint !== createFingerprint || preview.value?.status === 'FAILED' || preview.value?.status === 'READY') {
      createKey = crypto.randomUUID(); createFingerprint = fingerprint
    }
    const value = await createPreview(version.value.trim(), filePaths, createKey)
    preview.value = value; proposal.value = null; documents.value = []
    await router.replace({ query: { preview: value.id } })
  })
}
async function propose() {
  await run(async () => {
    if (!preview.value || !base.value) return
    const fingerprint = `${preview.value.id}:${base.value}`
    if (fingerprint !== proposalFingerprint) { proposalKey = crypto.randomUUID(); proposalFingerprint = fingerprint }
    proposal.value = await proposeImport(preview.value.id, base.value, proposalKey)
    await router.replace({ query: { preview: preview.value.id, action: proposal.value.id } })
  })
}
async function decide(decision: 'confirm' | 'reject') {
  await run(async () => {
    if (!proposal.value) return
    proposal.value = await decideImport(proposal.value.id, decision)
    await refresh()
  })
}
onMounted(() => run(async () => {
  const [catalog, list] = await Promise.all([sourceCatalog(), listKnowledgeBases()])
  enabled.value = catalog.enabled; bases.value = list
  if (typeof route.query.preview === 'string') preview.value = await getPreview(route.query.preview)
  if (typeof route.query.action === 'string' && preview.value) {
    const action = await getImport(route.query.action)
    if (action.parameters.previewId !== preview.value.id) throw new Error('导入提案与当前预览不匹配')
    proposal.value = action; base.value = action.parameters.knowledgeBaseId
  }
  await refresh()
}))
onUnmounted(() => { disposed = true; clearTimeout(timer) })
</script>

<template>
  <section class="project-sources">
    <header><div><p class="eyebrow">项目资料</p><h1>把项目资料带进研究工作台</h1>
      <p>读取 Spring AI 公开仓库中的指定版本，预览后确认加入知识库。</p></div>
      <RouterLink to="/research-tasks">进入研究任务 →</RouterLink></header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-alert v-if="!enabled" title="项目资料接入尚未启用，请按本地开发文档启动资料服务并开启功能。" type="info" :closable="false" />
    <div class="source-grid">
      <el-card shadow="never">
        <template #header><strong>1. 选择资料</strong></template>
        <el-form label-position="top" @submit.prevent="create">
          <el-form-item label="公开项目"><el-input model-value="spring-projects/spring-ai" disabled /></el-form-item>
          <el-form-item label="发布标签或完整提交 SHA"><el-input v-model="version" placeholder="例如 v1.0.0" maxlength="100" :disabled="busy || active" /></el-form-item>
          <el-form-item label="文件路径（每行一个，最多 5 个）"><el-input v-model="paths" type="textarea" :rows="5" placeholder="README.md" :disabled="busy || active" /></el-form-item>
          <p class="hint">支持 TXT、Markdown；每份最多 128 KiB。不读取目录、符号链接或其他仓库。</p>
          <el-button type="primary" native-type="submit" :loading="busy" :disabled="!enabled || active || !version.trim() || !paths.trim()">读取并预览</el-button>
        </el-form>
      </el-card>
      <el-card shadow="never">
        <template #header><strong>2. 预览并确认导入</strong></template>
        <el-empty v-if="!preview" description="读取后在这里查看资料与来源" />
        <template v-else>
          <el-tag>{{ labels[preview.status] || preview.status }}</el-tag>
          <p class="source-meta">{{ preview.repository }} · {{ preview.requestedRef }}</p>
          <p v-if="preview.resolvedCommit" class="source-meta">固定提交：{{ preview.resolvedCommit }}</p>
          <el-alert v-if="preview.errorCode" :title="`读取未完成：${preview.errorCode}`" :description="preview.errorPath ? `文件：${preview.errorPath}` : undefined" type="error" :closable="false" />
          <el-collapse>
            <el-collapse-item v-for="item in preview.items" :key="item.path" :title="`${item.path} · ${item.byteCount} 字节`" :name="item.path">
              <p class="source-meta">SHA-256：{{ item.sha256 }}<br />获取时间：{{ item.fetchedAt }}</p>
              <pre>{{ item.content }}</pre>
            </el-collapse-item>
          </el-collapse>
          <p class="hint">预览有效期至 {{ new Date(preview.expiresAt).toLocaleString() }}。确认后导入当前快照。</p>
          <el-select v-model="base" placeholder="选择目标知识库" :disabled="busy || !!proposal" aria-label="目标知识库">
            <el-option v-for="kb in bases" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
          <el-button v-if="!proposal" type="primary" :disabled="busy || preview.status !== 'READY' || expired || !base" @click="propose">生成导入提案</el-button>
          <div v-if="proposal" class="confirmation">
            <strong>{{ labels[proposal.status] || proposal.status }}</strong><p>{{ proposal.displaySummary }}</p>
            <p v-if="proposal.resultSummary">{{ proposal.resultSummary }}</p><p v-if="proposal.errorSummary">{{ proposal.errorSummary }}</p>
            <template v-if="proposal.status === 'PENDING_CONFIRMATION'">
              <el-button type="primary" :loading="busy" @click="decide('confirm')">确认导入知识库</el-button>
              <el-button :disabled="busy" @click="decide('reject')">拒绝</el-button>
            </template>
          </div>
          <el-table v-if="documents.length" :data="documents"><el-table-column prop="originalFilename" label="文档" />
            <el-table-column label="处理状态"><template #default="{ row }">{{ ({ PENDING: '等待处理', PROCESSING: '处理中', SUCCEEDED: '可用于研究', FAILED: '处理失败' } as Record<string, string>)[row.status] || row.status }}</template></el-table-column>
          </el-table>
          <p v-if="documents.length"><RouterLink to="/library">查看文档和维护状态</RouterLink> · <RouterLink to="/research-tasks">选择已处理文档开始研究</RouterLink></p>
          <el-button text :disabled="busy" @click="run(refresh)">刷新状态</el-button>
        </template>
      </el-card>
    </div>
  </section>
</template>

<style scoped>
.project-sources { max-width: 1280px; margin: auto; padding: 24px; }
header { display: flex; justify-content: space-between; gap: 24px; align-items: center; margin-bottom: 24px; }
h1 { font-size: 28px; margin: 8px 0 12px; } .eyebrow { color: #26786a; font-weight: 600; }
.source-grid { display: grid; grid-template-columns: minmax(280px, 0.8fr) minmax(0, 1.2fr); gap: 20px; margin-top: 20px; }
.hint, .source-meta { color: #65716e; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
pre { max-height: 360px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; background: #f5f7f6; padding: 16px; }
.confirmation { margin-top: 20px; padding: 18px; border: 1px solid #d8e7e1; border-radius: 10px; background: #f7fbf9; }
.el-select { margin: 8px 0 12px; width: 100%; }
@media (max-width: 800px) { .source-grid { grid-template-columns: 1fr; } header { align-items: flex-start; flex-direction: column; } .project-sources { padding: 12px; } }
</style>
