import { apiClient } from './client'

export interface ProjectPreview {
  id: string; repository: string; requestedRef: string; resolvedCommit: string | null
  status: 'PENDING' | 'RUNNING' | 'READY' | 'FAILED'; errorCode: string | null
  errorPath: string | null; expiresAt: string; traceId: string
  items: { path: string; mediaType: string; byteCount: number; sha256: string; fetchedAt: string; content: string }[]
}
export interface ImportProposal {
  id: string; displaySummary: string; status: string; expiresAt: string
  resultSummary: string | null; errorSummary: string | null
  parameters: { previewId: string; knowledgeBaseId: string }
}
export async function sourceCatalog() { return (await apiClient.get<{ enabled: boolean }>('/project-sources')).data }
export async function createPreview(ref: string, paths: string[], clientRequestId: string) {
  return (await apiClient.post<ProjectPreview>('/project-source-previews', { sourceId: 'spring-ai', ref, paths, clientRequestId })).data
}
export async function getPreview(id: string) { return (await apiClient.get<ProjectPreview>(`/project-source-previews/${id}`)).data }
export async function proposeImport(id: string, knowledgeBaseId: string, clientRequestId: string) {
  return (await apiClient.post<ImportProposal>(`/project-source-previews/${id}/import-proposals`, { knowledgeBaseId, clientRequestId })).data
}
export async function getImport(id: string) { return (await apiClient.get<ImportProposal>(`/action-requests/${id}`)).data }
export async function decideImport(id: string, decision: 'confirm' | 'reject') {
  return (await apiClient.post<ImportProposal>(`/action-requests/${id}/${decision}`)).data
}
export async function importDocuments(id: string) { return (await apiClient.get<string[]>(`/project-imports/${id}/documents`)).data }
export interface ProjectOrigin { repository: string; commit: string; path: string; sha256: string; fetchedAt: string }
export async function documentOrigin(id: string) { return (await apiClient.get<ProjectOrigin | null>(`/documents/${id}/origin`)).data }
