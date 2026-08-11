import { fetchEventSource } from '@microsoft/fetch-event-source'

import { apiClient, responseError } from './client'
import type {
  ChatRequest,
  Citation,
  ConversationMessage,
  ConversationSummary,
  KnowledgeBase,
  KnowledgeBaseInput,
  SourceDocument,
  StreamDeltaEvent,
  StreamDoneEvent,
  StreamErrorEvent,
  StreamMessageEvent,
  StreamUsageEvent,
} from './types'

export async function listKnowledgeBases(): Promise<KnowledgeBase[]> {
  const response = await apiClient.get<KnowledgeBase[]>('/knowledge-bases')
  return response.data
}

export async function createKnowledgeBase(input: KnowledgeBaseInput): Promise<KnowledgeBase> {
  const response = await apiClient.post<KnowledgeBase>('/knowledge-bases', input)
  return response.data
}

export async function updateKnowledgeBase(
  id: string,
  input: Partial<KnowledgeBaseInput>,
): Promise<KnowledgeBase> {
  const response = await apiClient.patch<KnowledgeBase>(`/knowledge-bases/${id}`, input)
  return response.data
}

export async function deleteKnowledgeBase(id: string): Promise<void> {
  await apiClient.delete(`/knowledge-bases/${id}`)
}

export async function listDocuments(knowledgeBaseId: string): Promise<SourceDocument[]> {
  const response = await apiClient.get<SourceDocument[]>(
    `/knowledge-bases/${knowledgeBaseId}/documents`,
  )
  return response.data
}

export async function uploadDocument(
  knowledgeBaseId: string,
  file: File,
): Promise<SourceDocument> {
  const formData = new FormData()
  formData.append('file', file)
  const response = await apiClient.post<SourceDocument>(
    `/knowledge-bases/${knowledgeBaseId}/documents`,
    formData,
  )
  return response.data
}

export async function retryDocument(documentId: string): Promise<SourceDocument> {
  const response = await apiClient.post<SourceDocument>(`/documents/${documentId}/retry`)
  return response.data
}

export async function deleteDocument(documentId: string): Promise<void> {
  await apiClient.delete(`/documents/${documentId}`)
}

export async function listConversations(knowledgeBaseId: string): Promise<ConversationSummary[]> {
  const response = await apiClient.get<ConversationSummary[]>('/conversations', {
    params: { knowledgeBaseId },
  })
  return response.data
}

export async function listConversationMessages(
  conversationId: string,
): Promise<ConversationMessage[]> {
  const response = await apiClient.get<ConversationMessage[]>(
    `/conversations/${conversationId}/messages`,
  )
  return response.data
}

export interface ChatStreamHandlers {
  onMessage: (event: StreamMessageEvent) => void
  onDelta: (event: StreamDeltaEvent) => void
  onCitation: (event: Citation) => void
  onUsage: (event: StreamUsageEvent) => void
  onDone: (event: StreamDoneEvent) => void
  onError: (event: StreamErrorEvent) => void
}

export async function streamChat(
  request: ChatRequest,
  handlers: ChatStreamHandlers,
  signal: AbortSignal,
): Promise<void> {
  await fetchEventSource('/api/chat/stream', {
    method: 'POST',
    headers: {
      Accept: 'text/event-stream',
      'Content-Type': 'application/json',
    },
    body: JSON.stringify(request),
    signal,
    openWhenHidden: true,
    async onopen(response) {
      const contentType = response.headers.get('content-type') ?? ''
      if (!response.ok || !contentType.includes('text/event-stream')) {
        throw await responseError(response)
      }
    },
    onmessage(message) {
      if (!message.data) {
        return
      }
      const payload = JSON.parse(message.data) as unknown
      switch (message.event) {
        case 'message':
          handlers.onMessage(payload as StreamMessageEvent)
          break
        case 'delta':
          handlers.onDelta(payload as StreamDeltaEvent)
          break
        case 'citation':
          handlers.onCitation(payload as Citation)
          break
        case 'usage':
          handlers.onUsage(payload as StreamUsageEvent)
          break
        case 'done':
          handlers.onDone(payload as StreamDoneEvent)
          break
        case 'error':
          handlers.onError(payload as StreamErrorEvent)
          break
      }
    },
    onerror(error) {
      throw error
    },
  })
}
