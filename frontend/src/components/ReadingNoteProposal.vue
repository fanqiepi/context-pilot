<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { errorMessage } from '@/api/client'
import { readingGet, readingPost, type ReadingState } from '@/api/reading'

const props = defineProps<{ taskId: string; taskType: string; facts: { text: string; evidenceIds: string[] }[]; state: ReadingState }>()
const emit = defineEmits<{ saved: [] }>()
type Proposal = {
  id: string; status: string; visibility: string; title?: string; body?: string; retainedEvidenceIds?: string[]
  contentHash?: string; expiresAt?: string; requiredBoundary?: number; errorCode?: string
  resultNoteId?: string; resultNoteRemoved?: boolean; userEdited?: boolean; scopeRevision?: number; visibilityEpoch?: number
}
const proposal = ref<Proposal | null>(null), editing = ref(false), loaded = ref(false), busy = ref(false), error = ref('')
const title = ref(''), body = ref(''), retained = ref<string[]>([])
const evidenceIds = computed(() => [...new Set(props.facts.flatMap(f => f.evidenceIds))])
const validDraft = computed(() => body.value.trim().length > 0 && Array.from(body.value.trim()).length <= 5000 && Array.from(title.value.trim()).length <= 120)
const pending = computed(() => proposal.value?.status === 'PENDING_CONFIRMATION')
const labels: Record<string, string> = { PENDING_CONFIRMATION: '待确认', SUCCEEDED: '已保存', FAILED: '保存校验未通过', EXPIRED: '预览已过期', REJECTED: '已拒绝或替代' }
let generation = 0, requestKey = '', requestFingerprint = ''
function fresh(g: number) { return g === generation }
function accept(p: Proposal | null) {
  if (p?.visibility === 'VISIBLE' && (p.scopeRevision !== props.state.scopeRevision || p.visibilityEpoch !== props.state.visibilityEpoch)) return false
  proposal.value = p; loaded.value = true
  return true
}
async function reload() {
  const g = generation
  try {
    const result = await readingGet<{ proposal: Proposal | null }>(`/tasks/${props.taskId}/note-proposal`)
    if (fresh(g)) accept(result.proposal)
  } catch (e) { if (fresh(g)) error.value = errorMessage(e) }
}
function edit() {
  title.value = pending.value ? proposal.value?.title || '' : props.taskType === 'RECAP' ? '前情回顾' : '人物查证'
  body.value = pending.value ? proposal.value?.body || '' : props.facts.map(f => f.text).join('\n')
  retained.value = pending.value ? [...proposal.value?.retainedEvidenceIds || []] : [...evidenceIds.value]
  editing.value = true; error.value = ''
}
async function preview() {
  if (!validDraft.value || busy.value) return
  const g = generation
  const content = { title: title.value, body: body.value, retainedEvidenceIds: retained.value, expectedScopeRevision: props.state.scopeRevision, supersedesProposalId: pending.value ? proposal.value?.id : null }
  const fingerprint = JSON.stringify(content)
  if (fingerprint !== requestFingerprint) { requestKey = crypto.randomUUID(); requestFingerprint = fingerprint }
  busy.value = true; error.value = ''
  try {
    const p = await readingPost<Proposal>(`/tasks/${props.taskId}/note-proposals`, { ...content, clientRequestId: requestKey })
    if (fresh(g) && accept(p)) editing.value = false
  } catch (e) { if (fresh(g)) { error.value = errorMessage(e); await reload() } }
  finally { if (fresh(g)) busy.value = false }
}
async function decide(action: 'confirm' | 'reject') {
  const p = proposal.value
  if (!p || !pending.value || p.visibility !== 'VISIBLE' || busy.value || editing.value) return
  const g = generation
  busy.value = true; error.value = ''
  try {
    const result = await readingPost<Proposal>(`/note-proposals/${p.id}/${action}`, action === 'confirm' ? { contentHash: p.contentHash } : {})
    if (fresh(g) && accept(result) && result.status === 'SUCCEEDED') emit('saved')
  } catch (e) {
    if (fresh(g)) {
      error.value = errorMessage(e)
      // On an ambiguous response, read the durable decision; never create another note.
      await reload()
      if (proposal.value?.status === 'SUCCEEDED') emit('saved')
    }
  } finally { if (fresh(g)) busy.value = false }
}
watch(() => [props.taskId, props.state.scopeRevision, props.state.visibilityEpoch], () => {
  generation++; loaded.value = false; proposal.value = null; editing.value = false; title.value = ''; body.value = ''; retained.value = []
  busy.value = false; error.value = ''; requestKey = ''; requestFingerprint = ''; void reload()
}, { immediate: true })
onUnmounted(() => { generation++; proposal.value = null; title.value = ''; body.value = '' })
</script>
<template>
  <section class="note-proposal">
    <h3>保存为本书笔记</h3>
    <p>可编辑文字并删减引用。保存不调用模型；编辑内容不会自动变成已验证的小说事实。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-button :disabled="busy" @click="reload">读取保存状态</el-button>
    <p v-if="proposal?.visibility === 'HIDDEN_BY_SCOPE'">保存预览超出当前已读范围，内容已隐藏。</p>
    <template v-else-if="loaded">
      <p v-if="proposal">{{ labels[proposal.status] || proposal.status }}<span v-if="proposal.errorCode"> · {{ proposal.errorCode }}</span></p>
      <template v-if="proposal?.status === 'SUCCEEDED'">
        <p v-if="proposal.resultNoteRemoved">该任务的笔记已移除，不能重复创建。</p>
        <p v-else>已保存到“本书书签与笔记”。后续修改请在笔记中编辑，同一任务只保存一份。</p>
      </template>
      <template v-else>
        <el-button v-if="!editing" :disabled="busy" @click="edit">{{ pending ? '编辑并替代此预览' : '编辑要保存的笔记' }}</el-button>
        <el-form v-if="editing" label-position="top">
          <el-form-item label="笔记标题"><el-input v-model="title" aria-label="AI 笔记标题" maxlength="240" :disabled="busy" /></el-form-item>
          <el-form-item label="笔记正文"><el-input v-model="body" aria-label="AI 笔记正文" type="textarea" :rows="7" maxlength="10000" :disabled="busy" /></el-form-item>
          <small>{{ Array.from(body.trim()).length }} / 5000 字符</small>
          <el-form-item label="保留的来源引用">
            <el-checkbox-group v-model="retained" :disabled="busy"><el-checkbox v-for="id in evidenceIds" :key="id" :value="id">{{ id }}</el-checkbox></el-checkbox-group>
          </el-form-item>
          <p v-if="pending">生成新预览后，旧预览将被拒绝，不能再确认旧内容。</p>
          <el-button :disabled="busy" @click="editing = false">返回</el-button>
          <el-button type="primary" :loading="busy" :disabled="!validDraft" @click="preview">生成最终保存预览</el-button>
        </el-form>
        <article v-if="pending && !editing && proposal" class="final-note-preview">
          <h4>最终保存预览</h4>
          <p>{{ proposal.title || '无标题笔记' }}</p><p style="white-space: pre-wrap">{{ proposal.body }}</p>
          <p>来源引用：{{ proposal.retainedEvidenceIds?.join('、') || '未保留引用' }} · 安全范围至第 {{ proposal.requiredBoundary }} 段</p>
          <p>有效期至 {{ new Date(proposal.expiresAt!).toLocaleString() }}。确认将只保存以上内容。</p>
          <el-button :disabled="busy" @click="decide('reject')">拒绝保存</el-button>
          <el-button type="primary" :loading="busy" @click="decide('confirm')">确认保存这份笔记</el-button>
        </article>
      </template>
    </template>
  </section>
</template>
<style scoped>
.note-proposal { margin-top: 20px; border-top: 1px solid var(--el-border-color); padding-top: 12px; overflow-wrap: anywhere; }
.final-note-preview { padding: 12px; background: var(--el-fill-color-light); border-radius: 8px; }
</style>
