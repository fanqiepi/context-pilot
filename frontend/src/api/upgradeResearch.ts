import { apiClient } from './client'
import type { ActionRequest } from './types'

export interface UpgradeRun {
  id: string; knowledgeBaseId: string; question: string; status: string; revision: number
  traceId: string; modelCalls: number; toolRequests: number; retrievalCalls: number; httpCalls: number
  draft: string | null; draftHash: string | null; gaps: string; errorCode: string | null
  releaseTags: string; documentSnapshots: string; createdAt: string; deadline: string
}
export interface UpgradeSource {
  sourceId: string; sourceType: string; documentId?: string; excerpt: string
  sourceUrl?: string; url?: string; releaseTag?: string; tag?: string; truncated: boolean; fetchedAt: string
}
export interface UpgradeView {
  saveActionId: string | null
  run: UpgradeRun; sources: UpgradeSource[]
  tools: Array<{ ordinal: number; toolName: string; status: string; parameterSummary: string; cacheHit: boolean; errorCode?: string }>
  modelCalls: Array<{ ordinal: number; status: string; model: string; totalTokens?: number; latencyMs?: number }>
}
export interface ResearchReport {
  id: string; knowledgeBaseId: string; runId: string; title: string; body: string; sources: string; gaps: string; createdAt: string
}
export const upgradeEnabled = async () => (await apiClient.get<{ enabled: boolean }>('/upgrade-research/repositories')).data.enabled
export const listUpgradeRuns = async (knowledgeBaseId: string) => (await apiClient.get<UpgradeRun[]>('/upgrade-research/runs', { params: { knowledgeBaseId, size: 50 } })).data
export const getUpgradeRun = async (id: string) => (await apiClient.get<UpgradeView>(`/upgrade-research/runs/${id}`)).data
export const startUpgradeRun = async (input: { clientRequestId: string; knowledgeBaseId: string; documentIds: string[]; repositoryKey: string; releaseTags: string[]; question: string; retryOfRunId?: string }) => (await apiClient.post<UpgradeRun>('/upgrade-research/runs', input)).data
export const cancelUpgradeRun = async (id: string) => (await apiClient.post<UpgradeRun>(`/upgrade-research/runs/${id}/cancel`)).data
export const deleteUpgradeRun = async (id: string) => { await apiClient.delete(`/upgrade-research/runs/${id}`) }
export const proposeReport = async (id: string, input: { clientRequestId: string; title: string }) => (await apiClient.post<ActionRequest>(`/upgrade-research/runs/${id}/save-proposals`, input)).data
export const listReports = async (knowledgeBaseId: string) => (await apiClient.get<ResearchReport[]>('/research-reports', { params: { knowledgeBaseId, size: 50 } })).data
export const getReport = async (id: string) => (await apiClient.get<{ report: ResearchReport; sourcesCurrent: boolean }>(`/research-reports/${id}`)).data
export const deleteReport = async (id: string) => { await apiClient.delete(`/research-reports/${id}`) }
