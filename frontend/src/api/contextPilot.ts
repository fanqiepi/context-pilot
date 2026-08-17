import { fetchEventSource } from '@microsoft/fetch-event-source'

import { apiClient, responseError } from './client'
import type {
  ActionRequest,
  AnswerFeedback,
  ChatRequest,
  Citation,
  ConversationMessage,
  ConversationSummary,
  HealthReportActionProposal,
  KnowledgeBase,
  KnowledgeBaseHealthReport,
  KnowledgeBaseInput,
  SourceDocument,
  StreamDeltaEvent,
  StreamDoneEvent,
  StreamErrorEvent,
  StreamHealthReportEvent,
  StreamActionRequiredEvent,
  StreamMessageEvent,
  StreamRouteEvent,
  StreamUsageEvent,
  ResearchRun,
  StreamResearchPlanEvent,
  StreamResearchStepEvent,
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

export async function reindexDocument(documentId: string): Promise<SourceDocument> {
  const response = await apiClient.post<SourceDocument>(`/documents/${documentId}/reindex`)
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

export async function markMessageHelpful(messageId: string): Promise<AnswerFeedback> {
  const response = await apiClient.put<AnswerFeedback>(`/messages/${messageId}/feedback`)
  return response.data
}

export async function removeMessageHelpful(messageId: string): Promise<void> {
  await apiClient.delete(`/messages/${messageId}/feedback`)
}

export async function getActionRequest(id: string): Promise<ActionRequest> {
  const response = await apiClient.get<ActionRequest>(`/action-requests/${id}`)
  return response.data
}

export async function getKnowledgeBaseHealthReport(
  id: string,
): Promise<KnowledgeBaseHealthReport> {
  const response = await apiClient.get<KnowledgeBaseHealthReport>(
    `/knowledge-base-health-reports/${id}`,
  )
  return response.data
}

export async function proposeHealthReportIssueAction(
  reportId: string,
  issueId: string,
): Promise<HealthReportActionProposal> {
  const response = await apiClient.post<HealthReportActionProposal>(
    `/knowledge-base-health-reports/${reportId}/issues/${issueId}/action-request`,
  )
  return response.data
}

export async function confirmActionRequest(id: string): Promise<ActionRequest> {
  const response = await apiClient.post<ActionRequest>(`/action-requests/${id}/confirm`)
  return response.data
}

export async function rejectActionRequest(id: string): Promise<ActionRequest> {
  const response = await apiClient.post<ActionRequest>(`/action-requests/${id}/reject`)
  return response.data
}

export async function getResearchRun(id: string): Promise<ResearchRun> {
  const response = await apiClient.get<ResearchRun>(`/research-runs/${id}`)
  return response.data
}

export async function cancelResearchRun(id: string): Promise<ResearchRun> {
  const response = await apiClient.post<ResearchRun>(`/research-runs/${id}/cancel`)
  return response.data
}

export interface ChatStreamHandlers {
  onMessage: (event: StreamMessageEvent) => void
  onRoute?: (event: StreamRouteEvent) => void
  onActionRequired: (event: StreamActionRequiredEvent) => void
  onHealthReport: (event: StreamHealthReportEvent) => void
  onResearchPlan?: (event: StreamResearchPlanEvent) => void
  onResearchStep?: (event: StreamResearchStepEvent) => void
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
        case 'route':
          handlers.onRoute?.(payload as StreamRouteEvent)
          break
        case 'action_required':
          handlers.onActionRequired(payload as StreamActionRequiredEvent)
          break
        case 'health_report':
          handlers.onHealthReport(payload as StreamHealthReportEvent)
          break
        case 'research_plan':
          handlers.onResearchPlan?.(payload as StreamResearchPlanEvent)
          break
        case 'research_step':
          handlers.onResearchStep?.(payload as StreamResearchStepEvent)
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
