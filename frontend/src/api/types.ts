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

export interface ConversationMessage {
  id: string
  conversationId: string
  role: ChatMessageRole
  content: string
  status: ChatMessageStatus
  errorSummary: string | null
  traceId: string
  citations: Citation[]
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
}

export interface StreamDeltaEvent {
  content: string
}

export interface StreamUsageEvent {
  model: string
  promptTokens: number | null
  completionTokens: number | null
  totalTokens: number | null
  latencyMs: number
}

export interface StreamDoneEvent {
  status: 'COMPLETED' | 'REFUSED'
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
