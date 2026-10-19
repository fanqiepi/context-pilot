import { apiClient } from './client'

export type TaskStatus = 'PLANNING' | 'READY' | 'WAITING_INPUT' | 'RUNNING' | 'PAUSED' | 'FAILED' | 'CANCELLED' | 'COMPLETED' | 'INSUFFICIENT_EVIDENCE'
export type TaskOperation = 'START' | 'PAUSE' | 'RESUME' | 'CANCEL' | 'SUPPLEMENT'
export interface TaskState {
  goal: string
  supplements: string[]
  sources: { id: string; filename: string }[]
  round: number
  activations: number
  phase: string
  traceId: string
  plan: { questions: string[]; steps: { title: string; tool: string; query: string; documentIds: string[] }[] } | null
  completed: { index: number; hits: number; status: string }[]
  evidence: { id: string; documentId: string; filename: string; page: number | null; text: string }[]
  calls: { phase: string; status: string; model: string | null; totalTokens: number | null; latencyMs: number }[]
  answer: string | null
  errorCode: string | null
}
export interface ResearchTask {
  id: string
  knowledgeBaseId: string
  status: TaskStatus
  revision: number
  state: TaskState
  createdAt: string
  updatedAt: string
}
export interface TaskEvent {
  id: string
  revision: number
  kind: string
  status: string
  checkpoint: string
  createdAt: string
}
export async function listResearchTasks(page = 1) {
  return (await apiClient.get<ResearchTask[]>('/research-tasks', { params: { page, size: 20 } })).data
}
export async function getResearchTask(id: string) {
  return (await apiClient.get<ResearchTask>(`/research-tasks/${id}`)).data
}
export async function createResearchTask(input: { clientRequestId: string; knowledgeBaseId: string; documentIds: string[]; goal: string }) {
  return (await apiClient.post<ResearchTask>('/research-tasks', input)).data
}
export async function commandResearchTask(task: ResearchTask, operation: TaskOperation, text?: string) {
  return (await apiClient.post<ResearchTask>(`/research-tasks/${task.id}/commands`, { operation, expectedRevision: task.revision, text })).data
}
export async function researchTaskEvents(id: string) {
  return (await apiClient.get<{ items: TaskEvent[]; truncated: boolean }>(`/research-tasks/${id}/events`)).data
}
export async function deleteResearchTask(task: ResearchTask) {
  await apiClient.delete(`/research-tasks/${task.id}`, { params: { expectedRevision: task.revision } })
}
