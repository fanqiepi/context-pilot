<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'

import {
  createKnowledgeBase,
  deleteDocument,
  deleteKnowledgeBase,
  listDocuments,
  listKnowledgeBases,
  retryDocument,
  updateKnowledgeBase,
  uploadDocument,
} from '@/api/contextPilot'
import { errorMessage } from '@/api/client'
import type {
  DocumentStatus,
  KnowledgeBase,
  KnowledgeBaseInput,
  SourceDocument,
} from '@/api/types'

const router = useRouter()
const knowledgeBases = ref<KnowledgeBase[]>([])
const activeKnowledgeBaseId = ref('')
const documents = ref<SourceDocument[]>([])
const loadingKnowledgeBases = ref(false)
const loadingDocuments = ref(false)
const uploading = ref(false)
const savingKnowledgeBase = ref(false)
const fileInput = ref<HTMLInputElement>()
const knowledgeDialogVisible = ref(false)
const editingKnowledgeBaseId = ref<string>()
const knowledgeForm = reactive<KnowledgeBaseInput>({ name: '', description: '' })
let pollingTimer: number | undefined

const activeKnowledgeBase = computed(
  () => knowledgeBases.value.find((item) => item.id === activeKnowledgeBaseId.value) ?? null,
)

const processingCount = computed(
  () =>
    documents.value.filter((document) =>
      ['PENDING', 'PROCESSING', 'DELETING'].includes(document.status),
    ).length,
)

const statusMeta: Record<
  DocumentStatus,
  { label: string; type: 'info' | 'primary' | 'success' | 'warning' | 'danger' }
> = {
  PENDING: { label: '等待处理', type: 'info' },
  PROCESSING: { label: '处理中', type: 'primary' },
  SUCCEEDED: { label: '已就绪', type: 'success' },
  FAILED: { label: '处理失败', type: 'danger' },
  DELETING: { label: '删除中', type: 'warning' },
}

onMounted(() => {
  void loadKnowledgeBases()
})

onBeforeUnmount(() => {
  stopPolling()
})

async function loadKnowledgeBases(): Promise<void> {
  loadingKnowledgeBases.value = true
  try {
    knowledgeBases.value = await listKnowledgeBases()
    if (!knowledgeBases.value.some((item) => item.id === activeKnowledgeBaseId.value)) {
      activeKnowledgeBaseId.value = knowledgeBases.value[0]?.id ?? ''
    }
    await loadActiveDocuments()
  } catch (error) {
    ElMessage.error(errorMessage(error, '知识库加载失败'))
  } finally {
    loadingKnowledgeBases.value = false
  }
}

async function selectKnowledgeBase(id: string): Promise<void> {
  if (activeKnowledgeBaseId.value === id) {
    return
  }
  activeKnowledgeBaseId.value = id
  documents.value = []
  stopPolling()
  await loadActiveDocuments()
}

async function loadActiveDocuments(silent = false): Promise<void> {
  const knowledgeBaseId = activeKnowledgeBaseId.value
  if (!knowledgeBaseId) {
    documents.value = []
    stopPolling()
    return
  }
  if (!silent) {
    loadingDocuments.value = true
  }
  try {
    const result = await listDocuments(knowledgeBaseId)
    if (knowledgeBaseId === activeKnowledgeBaseId.value) {
      documents.value = result
      syncPolling()
    }
  } catch (error) {
    if (!silent) {
      ElMessage.error(errorMessage(error, '文档列表加载失败'))
    }
  } finally {
    if (!silent) {
      loadingDocuments.value = false
    }
  }
}

function syncPolling(): void {
  stopPolling()
  if (processingCount.value > 0) {
    pollingTimer = window.setInterval(() => void loadActiveDocuments(true), 2_500)
  }
}

function stopPolling(): void {
  if (pollingTimer !== undefined) {
    window.clearInterval(pollingTimer)
    pollingTimer = undefined
  }
}

function openCreateDialog(): void {
  editingKnowledgeBaseId.value = undefined
  knowledgeForm.name = ''
  knowledgeForm.description = ''
  knowledgeDialogVisible.value = true
}

function openEditDialog(knowledgeBase: KnowledgeBase): void {
  editingKnowledgeBaseId.value = knowledgeBase.id
  knowledgeForm.name = knowledgeBase.name
  knowledgeForm.description = knowledgeBase.description ?? ''
  knowledgeDialogVisible.value = true
}

async function saveKnowledgeBase(): Promise<void> {
  const name = knowledgeForm.name.trim()
  if (!name) {
    ElMessage.warning('请输入知识库名称')
    return
  }
  savingKnowledgeBase.value = true
  try {
    const input = { name, description: knowledgeForm.description.trim() }
    const saved = editingKnowledgeBaseId.value
      ? await updateKnowledgeBase(editingKnowledgeBaseId.value, input)
      : await createKnowledgeBase(input)
    knowledgeDialogVisible.value = false
    await loadKnowledgeBases()
    activeKnowledgeBaseId.value = saved.id
    await loadActiveDocuments()
    ElMessage.success(editingKnowledgeBaseId.value ? '知识库已更新' : '知识库已创建')
  } catch (error) {
    ElMessage.error(errorMessage(error, '知识库保存失败'))
  } finally {
    savingKnowledgeBase.value = false
  }
}

async function removeKnowledgeBase(knowledgeBase: KnowledgeBase): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `确定删除“${knowledgeBase.name}”吗？知识库内仍有文档时后端会拒绝删除。`,
      '删除知识库',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
    await deleteKnowledgeBase(knowledgeBase.id)
    if (activeKnowledgeBaseId.value === knowledgeBase.id) {
      activeKnowledgeBaseId.value = ''
    }
    await loadKnowledgeBases()
    ElMessage.success('知识库已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(errorMessage(error, '知识库删除失败'))
    }
  }
}

function chooseFile(): void {
  fileInput.value?.click()
}

async function handleFileChange(event: Event): Promise<void> {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file || !activeKnowledgeBaseId.value) {
    return
  }
  uploading.value = true
  try {
    await uploadDocument(activeKnowledgeBaseId.value, file)
    await loadActiveDocuments()
    ElMessage.success('文档已上传，正在后台处理')
  } catch (error) {
    ElMessage.error(errorMessage(error, '文档上传失败'))
  } finally {
    uploading.value = false
  }
}

async function retry(document: SourceDocument): Promise<void> {
  try {
    await retryDocument(document.id)
    await loadActiveDocuments()
    ElMessage.success('已重新提交处理')
  } catch (error) {
    ElMessage.error(errorMessage(error, '重试失败'))
  }
}

async function removeDocument(document: SourceDocument): Promise<void> {
  try {
    await ElMessageBox.confirm(`确定删除“${document.originalFilename}”吗？`, '删除文档', {
      type: 'warning',
      confirmButtonText: '删除',
      cancelButtonText: '取消',
    })
    await deleteDocument(document.id)
    await loadActiveDocuments()
    ElMessage.success('文档已删除')
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') {
      ElMessage.error(errorMessage(error, '文档删除失败'))
    }
  }
}

function goToChat(): void {
  if (activeKnowledgeBaseId.value) {
    void router.push({ name: 'chat', query: { knowledgeBaseId: activeKnowledgeBaseId.value } })
  }
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}
</script>

<template>
  <section>
    <div class="page-heading">
      <div>
        <p class="page-kicker">Library</p>
        <h1>资料库</h1>
        <p class="page-description">
          组织知识库、上传资料并观察处理状态。文档就绪后，即可进入知识问答获取带来源的回答。
        </p>
      </div>
      <ElButton type="primary" size="large" @click="openCreateDialog">新建知识库</ElButton>
    </div>

    <div class="library-layout">
      <aside class="surface knowledge-panel" v-loading="loadingKnowledgeBases">
        <div class="panel-header">
          <div>
            <h2>知识库</h2>
            <p>{{ knowledgeBases.length }} 个工作空间</p>
          </div>
          <ElButton text type="primary" @click="loadKnowledgeBases">刷新</ElButton>
        </div>

        <div v-if="knowledgeBases.length" class="knowledge-list">
          <div
            v-for="knowledgeBase in knowledgeBases"
            :key="knowledgeBase.id"
            class="knowledge-card"
            :class="{ active: knowledgeBase.id === activeKnowledgeBaseId }"
            role="button"
            tabindex="0"
            @click="selectKnowledgeBase(knowledgeBase.id)"
            @keydown.enter="selectKnowledgeBase(knowledgeBase.id)"
          >
            <div class="knowledge-card-head">
              <span class="knowledge-initial">{{ knowledgeBase.name.slice(0, 1).toUpperCase() }}</span>
              <div class="knowledge-actions" @click.stop>
                <ElButton text size="small" @click="openEditDialog(knowledgeBase)">编辑</ElButton>
                <ElButton text size="small" type="danger" @click="removeKnowledgeBase(knowledgeBase)">
                  删除
                </ElButton>
              </div>
            </div>
            <strong>{{ knowledgeBase.name }}</strong>
            <p>{{ knowledgeBase.description || '暂未填写描述' }}</p>
            <small>{{ formatDate(knowledgeBase.updatedAt) }} 更新</small>
          </div>
        </div>

        <div v-else class="empty-panel compact-empty">
          <div>
            <span class="empty-symbol">＋</span>
            <strong>还没有知识库</strong>
            <span>先创建一个工作空间。</span>
          </div>
        </div>
      </aside>

      <section class="surface document-panel">
        <template v-if="activeKnowledgeBase">
          <div class="panel-header document-header">
            <div>
              <h2>{{ activeKnowledgeBase.name }}</h2>
              <p>
                {{ documents.length }} 份文档
                <template v-if="processingCount"> · {{ processingCount }} 份处理中</template>
              </p>
            </div>
            <div class="header-actions">
              <ElButton @click="goToChat">进入问答</ElButton>
              <ElButton type="primary" :loading="uploading" @click="chooseFile">上传文档</ElButton>
              <input
                ref="fileInput"
                class="file-input"
                type="file"
                accept=".txt,.md,.markdown,.pdf,text/plain,text/markdown,application/pdf"
                @change="handleFileChange"
              />
            </div>
          </div>

          <div v-loading="loadingDocuments" class="document-content">
            <div v-if="documents.length" class="document-list">
              <article v-for="document in documents" :key="document.id" class="document-row">
                <div class="file-badge">{{ document.fileType }}</div>
                <div class="document-main">
                  <div class="document-title-line">
                    <strong>{{ document.originalFilename }}</strong>
                    <ElTag :type="statusMeta[document.status].type" effect="light" round>
                      {{ statusMeta[document.status].label }}
                    </ElTag>
                  </div>
                  <p v-if="document.errorSummary" class="document-error">
                    {{ document.errorSummary }}
                  </p>
                  <p v-else>
                    {{ formatBytes(document.sizeBytes) }} · 第 {{ document.processingAttempts }} 次处理 ·
                    {{ formatDate(document.updatedAt) }}
                  </p>
                </div>
                <div class="document-actions">
                  <ElButton
                    v-if="document.status === 'FAILED'"
                    text
                    type="primary"
                    @click="retry(document)"
                  >
                    重试
                  </ElButton>
                  <ElButton text type="danger" @click="removeDocument(document)">删除</ElButton>
                </div>
              </article>
            </div>

            <div v-else class="empty-panel">
              <div>
                <span class="empty-symbol">↥</span>
                <strong>上传第一份资料</strong>
                <span>支持 UTF-8 TXT、Markdown 和文本型 PDF，单文件不超过 20 MiB。</span>
              </div>
            </div>
          </div>
        </template>

        <div v-else class="empty-panel large-empty">
          <div>
            <span class="empty-symbol">◇</span>
            <strong>选择或创建知识库</strong>
            <span>文档会在选定的知识库中独立处理和检索。</span>
          </div>
        </div>
      </section>
    </div>

    <ElDialog
      v-model="knowledgeDialogVisible"
      :title="editingKnowledgeBaseId ? '编辑知识库' : '新建知识库'"
      width="min(520px, 92vw)"
    >
      <ElForm label-position="top" @submit.prevent="saveKnowledgeBase">
        <ElFormItem label="名称" required>
          <ElInput
            v-model="knowledgeForm.name"
            maxlength="100"
            show-word-limit
            placeholder="例如：ContextPilot 项目资料"
          />
        </ElFormItem>
        <ElFormItem label="描述">
          <ElInput
            v-model="knowledgeForm.description"
            type="textarea"
            :rows="4"
            maxlength="1000"
            show-word-limit
            placeholder="说明这个知识库收录什么内容"
          />
        </ElFormItem>
      </ElForm>
      <template #footer>
        <ElButton @click="knowledgeDialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="savingKnowledgeBase" @click="saveKnowledgeBase">
          保存
        </ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.library-layout {
  display: grid;
  grid-template-columns: 340px minmax(0, 1fr);
  gap: 22px;
  min-height: 610px;
}

.knowledge-panel,
.document-panel {
  overflow: hidden;
}

.knowledge-list {
  display: grid;
  gap: 10px;
  max-height: 680px;
  padding: 14px;
  overflow: auto;
}

.knowledge-card {
  padding: 15px;
  border: 1px solid transparent;
  border-radius: 16px;
  background: #f6f8f3;
  cursor: pointer;
  outline: none;
  transition: 160ms ease;
}

.knowledge-card:hover,
.knowledge-card:focus-visible {
  border-color: #b9d2c3;
  transform: translateY(-1px);
}

.knowledge-card.active {
  border-color: #8db8a0;
  background: #e8f2eb;
  box-shadow: inset 3px 0 var(--accent);
}

.knowledge-card-head,
.document-title-line,
.header-actions {
  display: flex;
  align-items: center;
}

.knowledge-card-head {
  justify-content: space-between;
  margin-bottom: 10px;
}

.knowledge-initial {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 10px;
  color: var(--accent);
  background: #fff;
  font-size: 12px;
  font-weight: 800;
}

.knowledge-actions {
  opacity: 0;
  transition: opacity 160ms ease;
}

.knowledge-card:hover .knowledge-actions,
.knowledge-card.active .knowledge-actions,
.knowledge-card:focus-within .knowledge-actions {
  opacity: 1;
}

.knowledge-card > strong {
  display: block;
  margin-bottom: 5px;
  font-size: 15px;
}

.knowledge-card p {
  display: -webkit-box;
  min-height: 36px;
  margin: 0 0 9px;
  overflow: hidden;
  color: var(--muted);
  font-size: 12px;
  line-height: 1.5;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.knowledge-card small {
  color: #8b958e;
  font-size: 10px;
}

.compact-empty {
  min-height: 440px;
}

.large-empty {
  min-height: 610px;
}

.document-header {
  min-height: 84px;
  padding-inline: 24px;
}

.header-actions {
  gap: 10px;
}

.file-input {
  display: none;
}

.document-content {
  min-height: 500px;
}

.document-list {
  display: grid;
  gap: 1px;
  background: #e7ebe4;
}

.document-row {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 16px;
  min-height: 92px;
  padding: 17px 22px;
  background: rgb(255 255 252 / 96%);
  transition: background 150ms ease;
}

.document-row:hover {
  background: #fafbf7;
}

.file-badge {
  display: grid;
  width: 48px;
  height: 48px;
  place-items: center;
  border: 1px solid #d6e1d8;
  border-radius: 14px;
  color: var(--accent);
  background: #eef5ef;
  font-size: 10px;
  font-weight: 800;
}

.document-main {
  min-width: 0;
}

.document-title-line {
  gap: 10px;
}

.document-title-line strong {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.document-main p {
  margin: 8px 0 0;
  color: var(--muted);
  font-size: 12px;
}

.document-main .document-error {
  color: #b84a43;
}

.document-actions {
  display: flex;
}

@media (max-width: 960px) {
  .library-layout {
    grid-template-columns: 1fr;
  }

  .knowledge-list {
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
    max-height: none;
  }

  .compact-empty {
    min-height: 220px;
  }
}

@media (max-width: 640px) {
  .document-header {
    align-items: flex-start;
    flex-direction: column;
  }

  .header-actions {
    width: 100%;
  }

  .header-actions .el-button {
    flex: 1;
  }

  .document-row {
    grid-template-columns: auto minmax(0, 1fr);
  }

  .document-actions {
    grid-column: 2;
  }

  .knowledge-actions {
    opacity: 1;
  }
}
</style>
