export type KnowledgeBaseStatus = 'ACTIVE'

export interface KnowledgeBase {
  id: string
  name: string
  description: string | null
  status: KnowledgeBaseStatus
  createdAt: string
  updatedAt: string
}

export interface KnowledgeBaseInput {
  name: string
  description: string
}

export type DocumentStatus = 'PENDING' | 'PROCESSING' | 'SUCCEEDED' | 'FAILED' | 'DELETING'
export type EmbeddingIndexCompatibility = 'CURRENT' | 'OUTDATED' | 'UNKNOWN' | 'NOT_INDEXED'

export interface EmbeddingIndexInfo {
  profileId: string
  provider: string
  model: string
  dimensions: number
  profileVersion: string
  indexedAt: string
}

export interface SourceDocument {
  id: string
  knowledgeBaseId: string
  originalFilename: string
  fileType: 'TXT' | 'MARKDOWN' | 'PDF'
  mediaType: string
  sizeBytes: number
  sha256: string
  status: DocumentStatus
  errorSummary: string | null
  processingAttempts: number
  embeddingIndex: EmbeddingIndexInfo | null
  embeddingIndexCompatibility: EmbeddingIndexCompatibility
  createdAt: string
  updatedAt: string
}

export interface Citation {
  rank: number
  chunkId: string
  documentId: string
  originalFilename: string
  chunkIndex: number
  pageNumber: number | null
  score: number | null
  excerpt: string
}

export interface ConversationSummary {
  id: string
  knowledgeBaseId: string
  title: string
  createdAt: string
  updatedAt: string
}

export type ChatMessageRole = 'USER' | 'ASSISTANT'
export type ChatMessageStatus = 'PENDING' | 'COMPLETED' | 'FAILED'
export type CapabilityId = 'SIMPLE_CHAT' | 'KNOWLEDGE_QA' | 'BUSINESS_ACTION'
export type CapabilityMatchReason =
  | 'SIMPLE_INTERACTION_WHITELIST'
  | 'EXPLICIT_CREATE_KNOWLEDGE_BASE'
  | 'EXPLICIT_KNOWLEDGE_BASE_HEALTH'
  | 'HEALTH_REPORT_ISSUE_SELECTED'
  | 'DEFAULT_KNOWLEDGE_QA'

export type KnowledgeBaseHealthStatus =
  | 'EMPTY'
  | 'HEALTHY'
  | 'IN_PROGRESS'
  | 'ATTENTION_REQUIRED'
  | 'UNKNOWN'
export type HealthCheckCompleteness = 'COMPLETE' | 'PARTIAL' | 'TRUNCATED'
export type KnowledgeBaseHealthIssueType =
  | 'DOCUMENT_PROCESSING_FAILED'
  | 'EMBEDDING_PROFILE_UNKNOWN'
  | 'EMBEDDING_PROFILE_OUTDATED'
  | 'VECTOR_INDEX_MISSING'
export type HealthIssueSeverity = 'ERROR' | 'WARNING'
export type HealthRecommendedActionType = 'RETRY_DOCUMENT_PROCESSING' | 'REINDEX_DOCUMENT'
export type HealthIneligibilityReasonCode =
  | 'DOCUMENT_PROCESSING_DISABLED'
  | 'DOCUMENT_RETRY_LIMIT_REACHED'
  | 'VECTOR_INDEX_CHECK_UNAVAILABLE'

export interface EmbeddingIndexProfile {
  id: string
  provider: string
  model: string
  dimensions: number
  version: string
}

export interface DocumentStatusCounts {
  total: number
  pending: number
  processing: number
  succeeded: number
  failed: number
  deleting: number
}

export interface KnowledgeBaseHealthIssue {
  id: string
  reportId: string
  documentId: string
  originalFilename: string
  issueType: KnowledgeBaseHealthIssueType
  severity: HealthIssueSeverity
  observedDocumentStatus: DocumentStatus
  observedProcessingAttempts: number
  observedErrorSummary: string | null
  observedEmbeddingProfileId: string | null
  observedVectorCount: number | null
  sourceDocumentUpdatedAt: string
  recommendedActionType: HealthRecommendedActionType | null
  actionEligible: boolean
  ineligibilityReasonCode: HealthIneligibilityReasonCode | null
  ineligibilitySummary: string | null
}

export interface KnowledgeBaseHealthReport {
  id: string
  knowledgeBaseId: string
  conversationId: string
  userMessageId: string
  assistantMessageId: string
  capabilityId: 'KNOWLEDGE_QA'
  capabilityVersion: string
  healthStatus: KnowledgeBaseHealthStatus
  completeness: HealthCheckCompleteness
  completenessReason: string | null
  dataAsOf: string
  currentEmbeddingProfile: EmbeddingIndexProfile
  documentCounts: DocumentStatusCounts
  issueCount: number
  returnedIssueCount: number
  summary: string
  issues: KnowledgeBaseHealthIssue[]
  traceId: string
  createdAt: string
}

export type ActionType =
  | 'CREATE_KNOWLEDGE_BASE'
  | 'RETRY_DOCUMENT_PROCESSING'
  | 'REINDEX_DOCUMENT'
export type ActionRequestStatus =
  | 'PENDING_CONFIRMATION'
  | 'EXECUTING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'REJECTED'
  | 'EXPIRED'

export interface CreateKnowledgeBaseActionParameters {
  name: string
  description: string | null
}

export interface RetryDocumentProcessingActionParameters {
  documentId: string
  originalFilenameSnapshot: string
  observedDocumentStatus: 'FAILED'
  healthReportId: string
  healthIssueId: string
}

export interface ReindexDocumentActionParameters {
  documentId: string
  originalFilenameSnapshot: string
  observedDocumentStatus: 'SUCCEEDED'
  observedEmbeddingProfileId: string | null
  healthReportId: string
  healthIssueId: string
}

interface ActionRequestBase<TActionType extends ActionType, TParameters> {
  id: string
  conversationId: string
  userMessageId: string
  assistantMessageId: string
  capabilityId: 'BUSINESS_ACTION'
  capabilityVersion: string
  actionType: TActionType
  parameters: TParameters
  displaySummary: string
  status: ActionRequestStatus
  resultSummary: string | null
  errorSummary: string | null
  traceId: string
  expiresAt: string
  confirmedAt: string | null
  executedAt: string | null
  createdAt: string
  updatedAt: string
}

export type ActionRequest =
  | ActionRequestBase<'CREATE_KNOWLEDGE_BASE', CreateKnowledgeBaseActionParameters>
  | ActionRequestBase<'RETRY_DOCUMENT_PROCESSING', RetryDocumentProcessingActionParameters>
  | ActionRequestBase<'REINDEX_DOCUMENT', ReindexDocumentActionParameters>

export interface ConversationMessage {
  id: string
  conversationId: string
  role: ChatMessageRole
  content: string
  status: ChatMessageStatus
  errorSummary: string | null
  traceId: string
  capabilityId: CapabilityId | null
  capabilityVersion: string | null
  capabilityMatchReason: CapabilityMatchReason | null
  healthReport: KnowledgeBaseHealthReport | null
  actionRequest: ActionRequest | null
  citations: Citation[]
  helpful: boolean
  createdAt: string
  updatedAt: string
}

export interface HealthReportActionProposal {
  reusedExistingProposal: boolean
  userMessage: ConversationMessage
  assistantMessage: ConversationMessage
  actionRequest: ActionRequest
}

export interface AnswerFeedback {
  id: string
  messageId: string
  knowledgeBaseId: string
  traceId: string
  helpful: true
  createdAt: string
  updatedAt: string
}

export interface ChatRequest {
  knowledgeBaseId: string
  question: string
  conversationId?: string
}

export interface StreamMessageEvent {
  conversationId: string
  userMessageId: string
  assistantMessageId: string
  traceId: string
  capabilityId: CapabilityId
  capabilityVersion: string
  capabilityMatchReason: CapabilityMatchReason
}

export interface StreamDeltaEvent {
  content: string
}

export interface StreamRouteEvent {
  capabilityId: CapabilityId
  capabilityVersion: string
  capabilityMatchReason: CapabilityMatchReason
  traceId: string
}

export type StreamHealthReportEvent = KnowledgeBaseHealthReport

export type StreamActionRequiredEvent = ActionRequest extends infer TActionRequest
  ? TActionRequest extends ActionRequest
    ? Omit<TActionRequest, 'id'> & { actionRequestId: string }
    : never
  : never

export interface StreamUsageEvent {
  model: string
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  latencyMs: number
}

export interface StreamDoneEvent {
  status: 'COMPLETED' | 'REFUSED' | 'AWAITING_CONFIRMATION'
  traceId: string
}

export interface StreamErrorEvent {
  code: string
  message: string
  traceId: string
}

export interface ApiErrorPayload {
  code?: string
  message?: string
  requestId?: string
  traceId?: string
}
