<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute } from 'vue-router'

import {
  confirmActionRequest,
  listConversationMessages,
  listConversations,
  listKnowledgeBases,
  markMessageHelpful,
  removeMessageHelpful,
  rejectActionRequest,
  streamChat,
} from '@/api/contextPilot'
import { errorMessage } from '@/api/client'
import type {
  ActionRequest,
  ActionRequestStatus,
  ActionType,
  Citation,
  ConversationMessage,
  ConversationSummary,
  HealthCheckCompleteness,
  HealthRecommendedActionType,
  KnowledgeBase,
  KnowledgeBaseHealthIssueType,
  KnowledgeBaseHealthStatus,
  StreamDoneEvent,
  StreamErrorEvent,
  StreamUsageEvent,
} from '@/api/types'
import {
  createStreamTextRenderer,
  type StreamTextRenderer,
} from '@/utils/streamTextRenderer'

interface UiMessage extends ConversationMessage {
  usage?: StreamUsageEvent
}

const route = useRoute()
const knowledgeBases = ref<KnowledgeBase[]>([])
const activeKnowledgeBaseId = ref('')
const conversations = ref<ConversationSummary[]>([])
const activeConversationId = ref('')
const messages = ref<UiMessage[]>([])
const draft = ref('')
const loadingWorkspace = ref(false)
const loadingMessages = ref(false)
const sending = ref(false)
const feedbackSavingIds = reactive(new Set<string>())
const actionSavingIds = reactive(new Set<string>())
const messageScroller = ref<HTMLElement>()
let streamController: AbortController | undefined
let activeTextRenderer: StreamTextRenderer | undefined

const activeKnowledgeBase = computed(
  () => knowledgeBases.value.find((item) => item.id === activeKnowledgeBaseId.value) ?? null,
)

const activeConversation = computed(
  () => conversations.value.find((item) => item.id === activeConversationId.value) ?? null,
)

const canSend = computed(
  () => Boolean(activeKnowledgeBaseId.value && draft.value.trim() && !sending.value),
)

const suggestions = [
  '检查这个知识库有没有异常',
  '概括这组资料的核心内容',
  '项目采用了哪些关键技术？',
  '列出资料中最值得关注的结论',
]

onMounted(() => {
  void initialize()
})

onBeforeUnmount(() => {
  streamController?.abort()
  activeTextRenderer?.cancel()
})

async function initialize(): Promise<void> {
  loadingWorkspace.value = true
  try {
    knowledgeBases.value = await listKnowledgeBases()
    const queryValue = Array.isArray(route.query.knowledgeBaseId)
      ? route.query.knowledgeBaseId[0]
      : route.query.knowledgeBaseId
    activeKnowledgeBaseId.value =
      knowledgeBases.value.find((item) => item.id === queryValue)?.id ??
      knowledgeBases.value[0]?.id ??
      ''
    await refreshConversations(true)
  } catch (error) {
    ElMessage.error(errorMessage(error, '问答工作台加载失败'))
  } finally {
    loadingWorkspace.value = false
  }
}

async function selectKnowledgeBase(id: string): Promise<void> {
  if (sending.value) {
    ElMessage.warning('请先停止当前回答')
    return
  }
  activeKnowledgeBaseId.value = id
  activeConversationId.value = ''
  messages.value = []
  await refreshConversations(true)
}

async function refreshConversations(openLatest = false): Promise<void> {
  if (!activeKnowledgeBaseId.value) {
    conversations.value = []
    return
  }
  try {
    conversations.value = await listConversations(activeKnowledgeBaseId.value)
    const currentExists = conversations.value.some(
      (item) => item.id === activeConversationId.value,
    )
    if (!currentExists) {
      activeConversationId.value = ''
    }
    if (openLatest && !activeConversationId.value && conversations.value[0]) {
      await openConversation(conversations.value[0].id)
    }
  } catch (error) {
    ElMessage.error(errorMessage(error, '会话列表加载失败'))
  }
}

async function openConversation(conversationId: string): Promise<void> {
  if (sending.value) {
    ElMessage.warning('请先停止当前回答')
    return
  }
  activeConversationId.value = conversationId
  loadingMessages.value = true
  try {
    messages.value = await listConversationMessages(conversationId)
    await scrollToBottom()
  } catch (error) {
    ElMessage.error(errorMessage(error, '历史消息加载失败'))
  } finally {
    loadingMessages.value = false
  }
}

function startNewConversation(): void {
  if (sending.value) {
    ElMessage.warning('请先停止当前回答')
    return
  }
  activeConversationId.value = ''
  messages.value = []
  draft.value = ''
}

function useSuggestion(suggestion: string): void {
  draft.value = suggestion
}

async function sendQuestion(): Promise<void> {
  const question = draft.value.trim()
  if (!question || !activeKnowledgeBaseId.value || sending.value) {
    return
  }

  const now = new Date().toISOString()
  const temporaryConversationId = activeConversationId.value || 'pending'
  const userMessage = reactive<UiMessage>({
    id: `user-${crypto.randomUUID()}`,
    conversationId: temporaryConversationId,
    role: 'USER',
    content: question,
    status: 'COMPLETED',
    errorSummary: null,
    traceId: '',
    capabilityId: null,
    capabilityVersion: null,
    capabilityMatchReason: null,
    healthReport: null,
    actionRequest: null,
    citations: [],
    helpful: false,
    createdAt: now,
    updatedAt: now,
  })
  const assistantMessage = reactive<UiMessage>({
    id: `assistant-${crypto.randomUUID()}`,
    conversationId: temporaryConversationId,
    role: 'ASSISTANT',
    content: '',
    status: 'PENDING',
    errorSummary: null,
    traceId: '',
    capabilityId: null,
    capabilityVersion: null,
    capabilityMatchReason: null,
    healthReport: null,
    actionRequest: null,
    citations: [],
    helpful: false,
    createdAt: now,
    updatedAt: now,
  })
  messages.value.push(userMessage, assistantMessage)
  draft.value = ''
  sending.value = true
  const requestController = new AbortController()
  streamController = requestController
  const textRenderer = createStreamTextRenderer((content) => {
    assistantMessage.content += content
    void scrollToBottom()
  })
  activeTextRenderer = textRenderer
  const pendingCitations: Citation[] = []
  let pendingUsage: StreamUsageEvent | undefined
  let doneEvent: StreamDoneEvent | undefined
  let streamErrorEvent: StreamErrorEvent | undefined
  await scrollToBottom()

  try {
    await streamChat(
      {
        knowledgeBaseId: activeKnowledgeBaseId.value,
        question,
        ...(activeConversationId.value
          ? { conversationId: activeConversationId.value }
          : {}),
      },
      {
        onMessage(event) {
          activeConversationId.value = event.conversationId
          userMessage.id = event.userMessageId
          userMessage.conversationId = event.conversationId
          userMessage.traceId = event.traceId
          userMessage.capabilityId = event.capabilityId
          userMessage.capabilityVersion = event.capabilityVersion
          userMessage.capabilityMatchReason = event.capabilityMatchReason
          assistantMessage.id = event.assistantMessageId
          assistantMessage.conversationId = event.conversationId
          assistantMessage.traceId = event.traceId
          assistantMessage.capabilityId = event.capabilityId
          assistantMessage.capabilityVersion = event.capabilityVersion
          assistantMessage.capabilityMatchReason = event.capabilityMatchReason
        },
        onActionRequired(event) {
          const { actionRequestId, ...actionRequest } = event
          assistantMessage.actionRequest = { id: actionRequestId, ...actionRequest }
        },
        onHealthReport(event) {
          assistantMessage.healthReport = event
        },
        onDelta(event) {
          textRenderer.enqueue(event.content)
        },
        onCitation(event) {
          pendingCitations.push(event)
        },
        onUsage(event) {
          pendingUsage = event
        },
        onDone(event) {
          doneEvent = event
        },
        onError(event) {
          streamErrorEvent = event
        },
      },
      requestController.signal,
    )

    if (requestController.signal.aborted) {
      textRenderer.cancel()
      assistantMessage.status = 'FAILED'
      assistantMessage.errorSummary = '回答已停止'
      return
    }
    streamController = undefined
    await textRenderer.drain()
    assistantMessage.citations.push(...pendingCitations)
    assistantMessage.usage = pendingUsage
    if (streamErrorEvent) {
      assistantMessage.status = 'FAILED'
      assistantMessage.errorSummary = streamErrorEvent.message
      assistantMessage.traceId = streamErrorEvent.traceId
      assistantMessage.updatedAt = new Date().toISOString()
    } else if (doneEvent) {
      assistantMessage.status = 'COMPLETED'
      assistantMessage.traceId = doneEvent.traceId
      assistantMessage.updatedAt = new Date().toISOString()
    } else {
      assistantMessage.status = 'FAILED'
      assistantMessage.errorSummary = '流式连接提前结束，请重试'
    }
  } catch (error) {
    const aborted = requestController.signal.aborted
    if (aborted) {
      textRenderer.cancel()
    } else {
      await textRenderer.drain()
    }
    assistantMessage.status = 'FAILED'
    assistantMessage.errorSummary = aborted
      ? '回答已停止'
      : errorMessage(error, '回答生成失败')
    if (!aborted) {
      ElMessage.error(assistantMessage.errorSummary)
    }
  } finally {
    sending.value = false
    streamController = undefined
    if (activeTextRenderer === textRenderer) {
      activeTextRenderer = undefined
    }
    await refreshConversations(false)
    await scrollToBottom()
  }
}

function stopGeneration(): void {
  if (streamController) {
    activeTextRenderer?.cancel()
    streamController.abort()
    return
  }
  activeTextRenderer?.flush()
}

async function toggleHelpful(message: UiMessage): Promise<void> {
  if (
    message.role !== 'ASSISTANT' ||
    message.status !== 'COMPLETED' ||
    feedbackSavingIds.has(message.id)
  ) {
    return
  }

  const wasHelpful = message.helpful
  feedbackSavingIds.add(message.id)
  message.helpful = !wasHelpful
  try {
    if (wasHelpful) {
      await removeMessageHelpful(message.id)
    } else {
      await markMessageHelpful(message.id)
    }
  } catch (error) {
    message.helpful = wasHelpful
    ElMessage.error(errorMessage(error, wasHelpful ? '取消反馈失败' : '提交反馈失败'))
  } finally {
    feedbackSavingIds.delete(message.id)
  }
}

async function confirmAction(message: UiMessage): Promise<void> {
  const actionRequest = message.actionRequest
  if (!actionRequest || actionRequest.status !== 'PENDING_CONFIRMATION') {
    return
  }
  actionSavingIds.add(actionRequest.id)
  try {
    const updated = await confirmActionRequest(actionRequest.id)
    message.actionRequest = updated
    if (updated.status === 'SUCCEEDED') {
      if (updated.actionType === 'CREATE_KNOWLEDGE_BASE') {
        knowledgeBases.value = await listKnowledgeBases()
      }
      ElMessage.success(updated.resultSummary ?? '操作已完成')
    } else if (updated.status === 'FAILED') {
      ElMessage.error(updated.errorSummary ?? '操作执行失败')
    } else if (updated.status === 'EXPIRED') {
      ElMessage.warning('提案已过期，未执行任何操作')
    }
  } catch (error) {
    ElMessage.error(errorMessage(error, '确认操作失败'))
  } finally {
    actionSavingIds.delete(actionRequest.id)
  }
}

async function rejectAction(message: UiMessage): Promise<void> {
  const actionRequest = message.actionRequest
  if (!actionRequest || actionRequest.status !== 'PENDING_CONFIRMATION') {
    return
  }
  actionSavingIds.add(actionRequest.id)
  try {
    message.actionRequest = await rejectActionRequest(actionRequest.id)
    ElMessage.info('已取消操作，未执行任何变更')
  } catch (error) {
    ElMessage.error(errorMessage(error, '取消操作失败'))
  } finally {
    actionSavingIds.delete(actionRequest.id)
  }
}

function actionStatusLabel(status: ActionRequestStatus): string {
  return {
    PENDING_CONFIRMATION: '等待确认',
    EXECUTING: '执行中',
    SUCCEEDED: '已完成',
    FAILED: '执行失败',
    REJECTED: '已取消',
    EXPIRED: '已过期',
  }[status]
}

function actionStatusType(
  status: ActionRequestStatus,
): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  if (status === 'SUCCEEDED') return 'success'
  if (status === 'FAILED') return 'danger'
  if (status === 'PENDING_CONFIRMATION') return 'warning'
  if (status === 'EXECUTING') return 'primary'
  return 'info'
}

function actionTitle(actionType: ActionType): string {
  return {
    CREATE_KNOWLEDGE_BASE: '创建知识库',
    RETRY_DOCUMENT_PROCESSING: '重试文档处理',
    REINDEX_DOCUMENT: '重建文档索引',
  }[actionType]
}

function actionConfirmLabel(actionType: ActionType): string {
  return {
    CREATE_KNOWLEDGE_BASE: '确认创建',
    RETRY_DOCUMENT_PROCESSING: '确认重试',
    REINDEX_DOCUMENT: '确认重建',
  }[actionType]
}

function actionParameterRows(actionRequest: ActionRequest): Array<{ label: string; value: string }> {
  switch (actionRequest.actionType) {
    case 'CREATE_KNOWLEDGE_BASE':
      return [
        { label: '名称', value: actionRequest.parameters.name },
        { label: '描述', value: actionRequest.parameters.description || '未填写' },
      ]
    case 'RETRY_DOCUMENT_PROCESSING':
      return [
        { label: '目标文档', value: actionRequest.parameters.originalFilenameSnapshot },
        { label: '检查时状态', value: documentStatusLabel(actionRequest.parameters.observedDocumentStatus) },
      ]
    case 'REINDEX_DOCUMENT':
      return [
        { label: '目标文档', value: actionRequest.parameters.originalFilenameSnapshot },
        { label: '检查时状态', value: documentStatusLabel(actionRequest.parameters.observedDocumentStatus) },
        {
          label: '检查时 Profile',
          value: actionRequest.parameters.observedEmbeddingProfileId || '来源未知',
        },
      ]
  }
}

function healthStatusLabel(status: KnowledgeBaseHealthStatus): string {
  return {
    EMPTY: '暂无文档',
    HEALTHY: '健康',
    IN_PROGRESS: '处理中',
    ATTENTION_REQUIRED: '需要关注',
    UNKNOWN: '状态未知',
  }[status]
}

function healthStatusType(
  status: KnowledgeBaseHealthStatus,
): 'success' | 'warning' | 'danger' | 'info' | 'primary' {
  if (status === 'HEALTHY') return 'success'
  if (status === 'ATTENTION_REQUIRED') return 'danger'
  if (status === 'IN_PROGRESS') return 'primary'
  if (status === 'EMPTY') return 'info'
  return 'warning'
}

function completenessLabel(completeness: HealthCheckCompleteness): string {
  return {
    COMPLETE: '检查完整',
    PARTIAL: '部分检查',
    TRUNCATED: '明细已截断',
  }[completeness]
}

function healthIssueLabel(issueType: KnowledgeBaseHealthIssueType): string {
  return {
    DOCUMENT_PROCESSING_FAILED: '文档处理失败',
    EMBEDDING_PROFILE_UNKNOWN: '索引来源未知',
    EMBEDDING_PROFILE_OUTDATED: '索引版本已过期',
    VECTOR_INDEX_MISSING: '当前索引缺失',
  }[issueType]
}

function recommendedActionLabel(actionType: HealthRecommendedActionType | null): string {
  if (actionType === 'RETRY_DOCUMENT_PROCESSING') return '重试文档处理'
  if (actionType === 'REINDEX_DOCUMENT') return '重建文档索引'
  return '暂无建议动作'
}

function documentStatusLabel(status: string): string {
  return {
    PENDING: '等待处理',
    PROCESSING: '处理中',
    SUCCEEDED: '处理成功',
    FAILED: '处理失败',
    DELETING: '删除中',
  }[status] ?? status
}

async function scrollToBottom(): Promise<void> {
  await nextTick()
  if (messageScroller.value) {
    messageScroller.value.scrollTop = messageScroller.value.scrollHeight
  }
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat('zh-CN', {
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value))
}

function formatScore(score: number | null): string {
  return score == null ? '—' : `${(score * 100).toFixed(1)}%`
}
</script>

<template>
  <section v-loading="loadingWorkspace">
    <div class="page-heading chat-page-heading">
      <div>
        <p class="page-kicker">Grounded chat</p>
        <h1>知识问答</h1>
        <p class="page-description">
          回答严格基于选定知识库中的资料生成。展开引用即可核对文件、片段与相关度。
        </p>
      </div>
    </div>

    <div class="chat-layout">
      <aside class="surface conversation-panel">
        <div class="workspace-select">
          <label for="knowledge-base-select">当前知识库</label>
          <ElSelect
            id="knowledge-base-select"
            :model-value="activeKnowledgeBaseId"
            placeholder="选择知识库"
            :disabled="sending"
            @change="selectKnowledgeBase"
          >
            <ElOption
              v-for="knowledgeBase in knowledgeBases"
              :key="knowledgeBase.id"
              :label="knowledgeBase.name"
              :value="knowledgeBase.id"
            />
          </ElSelect>
          <ElButton type="primary" plain class="new-chat-button" @click="startNewConversation">
            ＋ 新对话
          </ElButton>
        </div>

        <div class="conversation-heading">
          <span>历史会话</span>
          <small>{{ conversations.length }}</small>
        </div>

        <div v-if="conversations.length" class="conversation-list">
          <button
            v-for="conversation in conversations"
            :key="conversation.id"
            type="button"
            class="conversation-item"
            :class="{ active: conversation.id === activeConversationId }"
            @click="openConversation(conversation.id)"
          >
            <span>{{ conversation.title }}</span>
            <small>{{ formatDate(conversation.updatedAt) }}</small>
          </button>
        </div>
        <div v-else class="conversation-empty">还没有历史会话</div>
      </aside>

      <section class="surface chat-panel">
        <header class="chat-header">
          <div>
            <p>{{ activeKnowledgeBase?.name || '未选择知识库' }}</p>
            <h2>{{ activeConversation?.title || '开始一段新的知识问答' }}</h2>
          </div>
          <ElTag v-if="sending" type="primary" effect="light" round>正在生成</ElTag>
          <ElTag v-else type="success" effect="plain" round>引用可追踪</ElTag>
        </header>

        <div ref="messageScroller" v-loading="loadingMessages" class="message-scroller">
          <div v-if="!messages.length" class="chat-welcome">
            <span class="welcome-mark">C</span>
            <h2>{{ activeKnowledgeBase ? '从资料中寻找答案' : '请先创建或选择知识库' }}</h2>
            <p v-if="activeKnowledgeBase">
              当前使用“{{ activeKnowledgeBase.name }}”。每个回答都会保留来源和 trace ID。
            </p>
            <p v-else>前往资料库创建知识库并上传文档，文档处理完成后再开始问答。</p>
            <div v-if="activeKnowledgeBase" class="suggestion-list">
              <button
                v-for="suggestion in suggestions"
                :key="suggestion"
                type="button"
                @click="useSuggestion(suggestion)"
              >
                {{ suggestion }}
              </button>
            </div>
          </div>

          <div v-else class="message-list">
            <article
              v-for="message in messages"
              :key="message.id"
              class="message-row"
              :class="message.role.toLowerCase()"
            >
              <div class="message-avatar">{{ message.role === 'USER' ? '你' : 'CP' }}</div>
              <div class="message-body">
                <div class="message-meta">
                  <strong>{{ message.role === 'USER' ? '你' : 'ContextPilot' }}</strong>
                  <span>{{ formatDate(message.createdAt) }}</span>
                  <ElTag v-if="message.status === 'FAILED'" size="small" type="danger">
                    失败
                  </ElTag>
                </div>

                <div v-if="message.content" class="message-content">{{ message.content }}</div>
                <div v-else-if="message.status === 'PENDING'" class="typing-indicator">
                  <i></i><i></i><i></i>
                </div>
                <div v-if="message.errorSummary" class="message-error">
                  {{ message.errorSummary }}
                </div>

                <section v-if="message.healthReport" class="health-card">
                  <header>
                    <div>
                      <small>知识库健康报告</small>
                      <strong>{{ healthStatusLabel(message.healthReport.healthStatus) }}</strong>
                    </div>
                    <ElTag
                      :type="healthStatusType(message.healthReport.healthStatus)"
                      effect="light"
                      round
                    >
                      {{ completenessLabel(message.healthReport.completeness) }}
                    </ElTag>
                  </header>

                  <div class="health-overview">
                    <div>
                      <span>数据时间</span>
                      <strong>{{ formatDate(message.healthReport.dataAsOf) }}</strong>
                    </div>
                    <div>
                      <span>当前索引</span>
                      <strong>{{ message.healthReport.currentEmbeddingProfile.id }}</strong>
                    </div>
                    <div>
                      <span>问题</span>
                      <strong>{{ message.healthReport.issueCount }}</strong>
                    </div>
                  </div>

                  <p
                    v-if="message.healthReport.completenessReason"
                    class="health-completeness-notice"
                  >
                    {{ message.healthReport.completenessReason }}
                  </p>

                  <div class="health-counts">
                    <div><strong>{{ message.healthReport.documentCounts.total }}</strong><span>全部</span></div>
                    <div><strong>{{ message.healthReport.documentCounts.pending }}</strong><span>等待</span></div>
                    <div><strong>{{ message.healthReport.documentCounts.processing }}</strong><span>处理中</span></div>
                    <div><strong>{{ message.healthReport.documentCounts.succeeded }}</strong><span>成功</span></div>
                    <div><strong>{{ message.healthReport.documentCounts.failed }}</strong><span>失败</span></div>
                    <div><strong>{{ message.healthReport.documentCounts.deleting }}</strong><span>删除中</span></div>
                  </div>

                  <div v-if="message.healthReport.issues.length" class="health-issue-list">
                    <article v-for="issue in message.healthReport.issues" :key="issue.id">
                      <header>
                        <div>
                          <strong>{{ issue.originalFilename }}</strong>
                          <span>{{ healthIssueLabel(issue.issueType) }}</span>
                        </div>
                        <ElTag
                          :type="issue.severity === 'ERROR' ? 'danger' : 'warning'"
                          size="small"
                          effect="plain"
                        >
                          {{ issue.severity === 'ERROR' ? '错误' : '警告' }}
                        </ElTag>
                      </header>
                      <dl>
                        <div>
                          <dt>观察状态</dt>
                          <dd>{{ documentStatusLabel(issue.observedDocumentStatus) }}</dd>
                        </div>
                        <div>
                          <dt>处理次数</dt>
                          <dd>{{ issue.observedProcessingAttempts }}</dd>
                        </div>
                        <div>
                          <dt>索引 Profile</dt>
                          <dd class="mono">{{ issue.observedEmbeddingProfileId || '未知' }}</dd>
                        </div>
                        <div>
                          <dt>向量数量</dt>
                          <dd>{{ issue.observedVectorCount ?? '未检查' }}</dd>
                        </div>
                      </dl>
                      <p v-if="issue.observedErrorSummary" class="health-issue-error">
                        {{ issue.observedErrorSummary }}
                      </p>
                      <footer>
                        <span>建议：{{ recommendedActionLabel(issue.recommendedActionType) }}</span>
                        <ElTag v-if="issue.actionEligible" type="success" size="small" effect="plain">
                          可生成提案
                        </ElTag>
                        <span v-else class="health-ineligible">
                          {{ issue.ineligibilitySummary || '当前不可生成提案' }}
                        </span>
                      </footer>
                    </article>
                  </div>
                  <p v-else class="health-empty-issues">未发现文档处理或索引异常。</p>

                  <footer class="health-source">
                    检查时 Profile：{{ message.healthReport.currentEmbeddingProfile.provider }} /
                    {{ message.healthReport.currentEmbeddingProfile.model }} /
                    {{ message.healthReport.currentEmbeddingProfile.dimensions }} 维 · 报告快照不会自动刷新
                  </footer>
                </section>

                <section v-if="message.actionRequest" class="action-card">
                  <header>
                    <div>
                      <small>受控业务操作</small>
                      <strong>{{ actionTitle(message.actionRequest.actionType) }}</strong>
                    </div>
                    <ElTag
                      :type="actionStatusType(message.actionRequest.status)"
                      effect="light"
                      round
                    >
                      {{ actionStatusLabel(message.actionRequest.status) }}
                    </ElTag>
                  </header>
                  <dl>
                    <div
                      v-for="row in actionParameterRows(message.actionRequest)"
                      :key="row.label"
                    >
                      <dt>{{ row.label }}</dt>
                      <dd>{{ row.value }}</dd>
                    </div>
                    <div>
                      <dt>确认期限</dt>
                      <dd>{{ formatDate(message.actionRequest.expiresAt) }}</dd>
                    </div>
                  </dl>
                  <p class="action-impact">{{ message.actionRequest.displaySummary }}</p>
                  <p v-if="message.actionRequest.resultSummary" class="action-result success">
                    {{ message.actionRequest.resultSummary }}
                  </p>
                  <p v-if="message.actionRequest.errorSummary" class="action-result failure">
                    {{ message.actionRequest.errorSummary }}
                  </p>
                  <footer v-if="message.actionRequest.status === 'PENDING_CONFIRMATION'">
                    <ElButton
                      type="primary"
                      :loading="actionSavingIds.has(message.actionRequest.id)"
                      @click="confirmAction(message)"
                    >
                      {{ actionConfirmLabel(message.actionRequest.actionType) }}
                    </ElButton>
                    <ElButton
                      :disabled="actionSavingIds.has(message.actionRequest.id)"
                      @click="rejectAction(message)"
                    >
                      取消
                    </ElButton>
                  </footer>
                </section>

                <div v-if="message.citations.length" class="citation-list">
                  <details v-for="citation in message.citations" :key="citation.rank">
                    <summary>
                      <span>[{{ citation.rank }}] {{ citation.originalFilename }}</span>
                      <small>相关度 {{ formatScore(citation.score) }}</small>
                    </summary>
                    <p>{{ citation.excerpt }}</p>
                    <footer>
                      片段 {{ citation.chunkIndex }}
                      <template v-if="citation.pageNumber"> · 第 {{ citation.pageNumber }} 页</template>
                      · <span class="mono">{{ citation.chunkId }}</span>
                    </footer>
                  </details>
                </div>

                <div
                  v-if="
                    message.role === 'ASSISTANT' &&
                    message.status === 'COMPLETED' &&
                    !message.actionRequest
                  "
                  class="feedback-actions"
                >
                  <ElButton
                    size="small"
                    round
                    plain
                    :type="message.helpful ? 'success' : 'default'"
                    :loading="feedbackSavingIds.has(message.id)"
                    :aria-pressed="message.helpful"
                    :aria-label="message.helpful ? '取消有用标记' : '标记这个回答有用'"
                    @click="toggleHelpful(message)"
                  >
                    👍 {{ message.helpful ? '已标记有用' : '有用' }}
                  </ElButton>
                </div>

                <div v-if="message.usage" class="usage-line">
                  {{ message.usage.model }} · {{ message.usage.totalTokens ?? '—' }} tokens ·
                  {{ message.usage.latencyMs }} ms
                </div>
                <div v-if="message.traceId" class="trace-line mono">
                  trace {{ message.traceId }}
                </div>
              </div>
            </article>
          </div>
        </div>

        <footer class="composer-shell">
          <div class="composer">
            <ElInput
              v-model="draft"
              type="textarea"
              resize="none"
              :autosize="{ minRows: 2, maxRows: 6 }"
              maxlength="2000"
              placeholder="基于当前知识库提问…"
              :disabled="!activeKnowledgeBase || sending"
              @keydown.enter.exact.prevent="sendQuestion"
            />
            <ElButton v-if="sending" class="send-button" type="danger" @click="stopGeneration">
              停止
            </ElButton>
            <ElButton
              v-else
              class="send-button"
              type="primary"
              :disabled="!canSend"
              @click="sendQuestion"
            >
              发送
            </ElButton>
          </div>
          <p>Enter 发送 · Shift + Enter 换行 · 回答仅供参考，请核对引用原文</p>
        </footer>
      </section>
    </div>
  </section>
</template>

<style scoped>
.chat-page-heading {
  margin-bottom: 20px;
}

.chat-layout {
  display: grid;
  grid-template-columns: 290px minmax(0, 1fr);
  gap: 20px;
  min-height: 680px;
  height: calc(100vh - 235px);
}

.conversation-panel,
.chat-panel {
  overflow: hidden;
}

.conversation-panel {
  display: flex;
  min-height: 0;
  flex-direction: column;
}

.workspace-select {
  display: grid;
  gap: 10px;
  padding: 18px;
  border-bottom: 1px solid #e4e9e1;
}

.workspace-select label,
.conversation-heading {
  color: #6b756e;
  font-size: 11px;
  font-weight: 750;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.workspace-select .el-select {
  width: 100%;
}

.new-chat-button {
  width: 100%;
}

.conversation-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 18px 18px 9px;
}

.conversation-heading small {
  display: grid;
  min-width: 22px;
  height: 22px;
  place-items: center;
  border-radius: 999px;
  color: var(--accent);
  background: var(--accent-soft);
}

.conversation-list {
  display: grid;
  gap: 6px;
  padding: 7px 10px 16px;
  overflow: auto;
}

.conversation-item {
  display: grid;
  gap: 6px;
  width: 100%;
  padding: 13px 14px;
  border: 1px solid transparent;
  border-radius: 13px;
  color: var(--ink);
  background: transparent;
  cursor: pointer;
  text-align: left;
  transition: 150ms ease;
}

.conversation-item:hover {
  background: #f3f6f1;
}

.conversation-item.active {
  border-color: #bad1c1;
  background: #e7f1ea;
}

.conversation-item span {
  overflow: hidden;
  font-size: 13px;
  font-weight: 650;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.conversation-item small {
  color: #8a938d;
  font-size: 10px;
}

.conversation-empty {
  padding: 36px 18px;
  color: #8a938d;
  font-size: 12px;
  text-align: center;
}

.chat-panel {
  display: grid;
  min-width: 0;
  min-height: 0;
  grid-template-rows: auto minmax(0, 1fr) auto;
}

.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  min-height: 74px;
  padding: 15px 24px;
  border-bottom: 1px solid #e4e9e1;
}

.chat-header p {
  margin: 0 0 4px;
  color: var(--accent);
  font-size: 11px;
  font-weight: 700;
}

.chat-header h2 {
  margin: 0;
  font-size: 15px;
}

.message-scroller {
  min-height: 0;
  overflow: auto;
  scroll-behavior: smooth;
}

.chat-welcome {
  display: grid;
  min-height: 100%;
  align-content: center;
  justify-items: center;
  padding: 48px 24px;
  text-align: center;
}

.welcome-mark {
  display: grid;
  width: 58px;
  height: 58px;
  margin-bottom: 20px;
  place-items: center;
  border-radius: 20px;
  color: #fff;
  background: var(--accent);
  box-shadow: 0 16px 30px rgb(23 107 77 / 22%);
  font-family: Georgia, serif;
  font-size: 27px;
}

.chat-welcome h2 {
  margin: 0;
  font-family: Georgia, "Songti SC", serif;
  font-size: 25px;
  font-weight: 600;
}

.chat-welcome > p {
  max-width: 540px;
  margin: 12px 0 24px;
  color: var(--muted);
  font-size: 13px;
  line-height: 1.7;
}

.suggestion-list {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 9px;
}

.suggestion-list button {
  padding: 9px 13px;
  border: 1px solid #d8e1d8;
  border-radius: 999px;
  color: #536058;
  background: #fff;
  cursor: pointer;
  font-size: 12px;
  transition: 150ms ease;
}

.suggestion-list button:hover {
  border-color: #8db8a0;
  color: var(--accent);
  background: #f0f7f2;
}

.message-list {
  display: grid;
  gap: 26px;
  width: min(900px, 100%);
  margin: 0 auto;
  padding: 34px 32px 42px;
}

.message-row {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr);
  gap: 13px;
}

.message-row.user {
  grid-template-columns: minmax(0, 1fr) 38px;
}

.message-row.user .message-avatar {
  grid-column: 2;
  grid-row: 1;
}

.message-row.user .message-body {
  display: grid;
  grid-column: 1;
  grid-row: 1;
  justify-items: end;
}

.message-row.user .message-meta {
  justify-content: flex-end;
}

.message-row.user .message-content {
  max-width: min(680px, 88%);
  padding: 10px 14px;
  border-radius: 16px 4px 16px 16px;
  background: #f2e2d1;
  text-align: left;
}

.message-row.user .trace-line {
  text-align: right;
}

.message-avatar {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border-radius: 12px;
  color: #fff;
  background: var(--accent);
  font-size: 11px;
  font-weight: 800;
}

.message-row.user .message-avatar {
  color: #6f4a26;
  background: #f1d8bd;
}

.message-body {
  min-width: 0;
  padding-top: 2px;
}

.message-meta {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 9px;
}

.message-meta strong {
  font-size: 13px;
}

.message-meta span {
  color: #8a938d;
  font-size: 10px;
}

.message-content {
  color: #2e3831;
  font-size: 14px;
  line-height: 1.85;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.message-error {
  margin-top: 10px;
  padding: 10px 12px;
  border: 1px solid #f1c9c6;
  border-radius: 10px;
  color: #a63f38;
  background: #fff1f0;
  font-size: 12px;
}

.action-card {
  display: grid;
  gap: 14px;
  max-width: 680px;
  margin-top: 14px;
  padding: 17px;
  border: 1px solid #cbdccf;
  border-radius: 15px;
  background: linear-gradient(145deg, #f7fbf7, #eef6f0);
  box-shadow: 0 10px 24px rgb(43 91 66 / 8%);
}

.health-card {
  display: grid;
  gap: 15px;
  max-width: 760px;
  margin-top: 14px;
  padding: 18px;
  border: 1px solid #c9d9cf;
  border-radius: 16px;
  background: linear-gradient(145deg, #f8fbf8, #eef5f0);
  box-shadow: 0 12px 28px rgb(43 91 66 / 9%);
}

.health-card > header,
.health-issue-list article > header,
.health-issue-list article > footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.health-card > header > div,
.health-issue-list article > header > div {
  display: grid;
  gap: 3px;
}

.health-card > header small {
  color: var(--accent);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.health-card > header strong {
  color: #26392d;
  font-size: 16px;
}

.health-overview,
.health-counts {
  display: grid;
  gap: 8px;
}

.health-overview {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.health-overview > div {
  display: grid;
  gap: 4px;
  min-width: 0;
  padding: 11px 12px;
  border-radius: 11px;
  background: rgb(255 255 255 / 72%);
}

.health-overview span,
.health-counts span {
  color: #758178;
  font-size: 10px;
}

.health-overview strong {
  color: #35463b;
  font-size: 12px;
  overflow-wrap: anywhere;
}

.health-completeness-notice,
.health-empty-issues {
  margin: 0;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.6;
}

.health-completeness-notice {
  color: #7a5724;
  background: #fff3d6;
}

.health-counts {
  grid-template-columns: repeat(6, minmax(0, 1fr));
}

.health-counts > div {
  display: grid;
  gap: 2px;
  justify-items: center;
  padding: 9px 5px;
  border: 1px solid #dce6de;
  border-radius: 10px;
  background: #fff;
}

.health-counts strong {
  color: #2d4d3a;
  font-size: 15px;
}

.health-issue-list {
  display: grid;
  gap: 10px;
}

.health-issue-list article {
  display: grid;
  gap: 11px;
  padding: 13px;
  border: 1px solid #d9e2da;
  border-radius: 12px;
  background: #fff;
}

.health-issue-list article > header strong {
  color: #33443a;
  font-size: 13px;
  overflow-wrap: anywhere;
}

.health-issue-list article > header span,
.health-issue-list article > footer,
.health-ineligible {
  color: #748078;
  font-size: 11px;
}

.health-issue-list dl {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 7px 15px;
  margin: 0;
}

.health-issue-list dl div {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr);
  gap: 8px;
}

.health-issue-list dt,
.health-issue-list dd {
  margin: 0;
  font-size: 11px;
  line-height: 1.5;
}

.health-issue-list dt {
  color: #849087;
}

.health-issue-list dd {
  color: #435047;
  overflow-wrap: anywhere;
}

.health-issue-error {
  margin: 0;
  padding: 9px 10px;
  border-radius: 9px;
  color: #9e433c;
  background: #fff1f0;
  font-size: 11px;
  line-height: 1.55;
}

.health-empty-issues {
  color: #246b45;
  background: #e5f4e9;
}

.health-source {
  padding-top: 2px;
  color: #839087;
  font-size: 10px;
  line-height: 1.6;
}

.action-card > header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
}

.action-card > header div {
  display: grid;
  gap: 3px;
}

.action-card > header small {
  color: var(--accent);
  font-size: 10px;
  font-weight: 750;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.action-card > header strong {
  color: #26392d;
  font-size: 15px;
}

.action-card dl {
  display: grid;
  gap: 8px;
  margin: 0;
}

.action-card dl div {
  display: grid;
  grid-template-columns: 76px minmax(0, 1fr);
  gap: 10px;
}

.action-card dt,
.action-card dd {
  margin: 0;
  font-size: 12px;
  line-height: 1.55;
}

.action-card dt {
  color: #748178;
}

.action-card dd {
  color: #35463b;
  overflow-wrap: anywhere;
}

.action-impact,
.action-result {
  margin: 0;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.6;
}

.action-impact {
  color: #5b4b2f;
  background: #fff6df;
}

.action-result.success {
  color: #226a43;
  background: #e2f4e8;
}

.action-result.failure {
  color: #a63f38;
  background: #fff1f0;
}

.action-card > footer {
  display: flex;
  gap: 8px;
}

.typing-indicator {
  display: flex;
  gap: 5px;
  padding: 10px 0;
}

.typing-indicator i {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #7aa28c;
  animation: pulse 1.2s infinite ease-in-out;
}

.typing-indicator i:nth-child(2) {
  animation-delay: 120ms;
}

.typing-indicator i:nth-child(3) {
  animation-delay: 240ms;
}

@keyframes pulse {
  0%,
  70%,
  100% {
    opacity: 0.3;
    transform: translateY(0);
  }
  35% {
    opacity: 1;
    transform: translateY(-3px);
  }
}

.citation-list {
  display: grid;
  gap: 7px;
  margin-top: 16px;
}

.citation-list details {
  border: 1px solid #dce4da;
  border-radius: 12px;
  background: #f7f9f5;
}

.citation-list summary {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 11px 13px;
  color: #425047;
  cursor: pointer;
  font-size: 12px;
  font-weight: 650;
}

.citation-list summary small {
  color: var(--accent);
  white-space: nowrap;
}

.citation-list details p {
  margin: 0;
  padding: 0 13px 10px;
  color: #5e6961;
  font-size: 12px;
  line-height: 1.65;
  white-space: pre-wrap;
}

.citation-list details footer {
  padding: 9px 13px;
  border-top: 1px solid #e0e6dd;
  color: #7e8981;
  font-size: 10px;
  overflow-wrap: anywhere;
}

.feedback-actions {
  display: flex;
  margin-top: 14px;
}

.feedback-actions :deep(.el-button) {
  min-width: 78px;
  transition: 150ms ease;
}

.usage-line,
.trace-line {
  margin-top: 10px;
  color: #89928c;
  font-size: 10px;
}

.trace-line {
  overflow-wrap: anywhere;
}

.composer-shell {
  padding: 14px 20px 12px;
  border-top: 1px solid #e4e9e1;
  background: rgb(252 253 249 / 92%);
}

.composer {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: end;
  gap: 10px;
  width: min(900px, 100%);
  margin: 0 auto;
}

.composer :deep(.el-textarea__inner) {
  padding: 13px 14px;
  border: 1px solid #ccd8ce;
  border-radius: 14px;
  box-shadow: none;
  line-height: 1.6;
}

.composer :deep(.el-textarea__inner:focus) {
  border-color: #76a78d;
  box-shadow: 0 0 0 3px rgb(23 107 77 / 8%);
}

.send-button {
  min-width: 76px;
  min-height: 44px;
  border-radius: 12px;
}

.composer-shell > p {
  margin: 8px 0 0;
  color: #919a94;
  font-size: 10px;
  text-align: center;
}

@media (max-width: 900px) {
  .chat-layout {
    grid-template-columns: 1fr;
    height: auto;
  }

  .conversation-panel {
    max-height: 330px;
  }

  .conversation-list {
    grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  }

  .chat-panel {
    min-height: 680px;
  }
}

@media (max-width: 600px) {
  .message-list {
    padding: 26px 16px 34px;
  }

  .message-row {
    grid-template-columns: 32px minmax(0, 1fr);
  }

  .message-row.user {
    grid-template-columns: minmax(0, 1fr) 32px;
  }

  .message-avatar {
    width: 30px;
    height: 30px;
    border-radius: 10px;
  }

  .chat-header,
  .composer-shell {
    padding-inline: 14px;
  }

  .health-overview,
  .health-issue-list dl {
    grid-template-columns: 1fr;
  }

  .health-counts {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }
}
</style>
