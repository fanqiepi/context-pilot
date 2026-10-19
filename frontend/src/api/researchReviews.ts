import { apiClient } from './client'
import type { TaskState } from './researchTasks'

export interface ResearchReview {
  id: string
  taskId: string
  taskRevision: number
  round: number
  status: 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'CANCELLED'
  revision: number
  overallVerdict: 'ISSUES_FOUND' | 'NEEDS_MORE_EVIDENCE' | 'NO_ISSUES_FOUND' | null
  errorCode: string | null
  freshness: 'CURRENT' | 'HISTORICAL' | 'SOURCE_CHANGED'
  snapshot: { answer: string | null; evidence: TaskState['evidence'] } | null
  result: {
    claims: { claim: string; verdict: 'SUPPORTED' | 'PARTIAL' | 'UNSUPPORTED' | 'CONTRADICTED'; evidenceIds: string[]; explanation: string }[]
    gaps: string[]
    suggestedSupplement: string
  } | null
  usage: TaskState['calls'][number] | null
  traceId: string
  promptVersion: string
  createdAt: string
}
export async function listReviews(taskId: string, page = 1) {
  return (await apiClient.get<ResearchReview[]>(`/research-tasks/${taskId}/reviews`, { params: { page, size: 20 } })).data
}
export async function createReview(taskId: string, expectedTaskRevision: number, clientRequestId: string) {
  return (await apiClient.post<ResearchReview>(`/research-tasks/${taskId}/reviews`, { expectedTaskRevision, clientRequestId })).data
}
export async function getReview(id: string) {
  return (await apiClient.get<ResearchReview>(`/research-reviews/${id}`)).data
}
export async function cancelReview(review: ResearchReview) {
  return (await apiClient.post<ResearchReview>(`/research-reviews/${review.id}/cancel`, { expectedRevision: review.revision })).data
}
export async function deleteReview(review: ResearchReview) {
  await apiClient.delete(`/research-reviews/${review.id}`, { params: { expectedRevision: review.revision } })
}
