import { apiClient } from './client'

export interface Page<T> { items: T[]; page: number; size: number; total: number }
export interface Book { id: string; title: string; sourceDescription: string; latestVersionId: string; readingVersionId?: string; revision: number; lastReadAt?: string }
export interface Version { id: string; chapterCount: number; paragraphCount: number; characterCount: number; parserId: string; createdAt: string }
export interface Chapter { id: string; chapterOrdinal: number; title: string | null; titleHidden: boolean; firstParagraphOrdinal: number; lastParagraphOrdinal: number }
export interface Paragraph { id: string; chapterId: string; paragraphOrdinal: number; chapterParagraphOrdinal: number; text: string; codePointCount: number }
export interface Position { paragraphId: string; chapterId: string; paragraphOrdinal: number; offset: number }
export interface ReadingState { versionId: string; resumePosition: Position | null; resumeRevision: number; readBoundary: number; scopeRevision: number; visibilityEpoch: number }
export interface ReadingSource { versionId: string; chapterId: string; chapterOrdinal: number; chapterTitle: string | null; paragraphId: string; paragraphOrdinal: number; startOffset: number; endOffset: number; text: string; textHash: string; requiredBoundary: number; scopeRevision: number; visibilityEpoch: number }
export interface Preview { id: string; status: string; errorCode: string | null; title: string; parserId: string; headingLevel: number | null; previewHash: string; chapterCount: number; paragraphCount: number; characterCount: number; splitCount: number; firstSample: string; lastSample: string; duplicateVersionId: string | null }
export interface Note { id: string; versionId: string; visibility: string; kind: string | null; title: string | null; body: string | null; anchorParagraphId: string | null; chapterId: string | null; anchorStart: number | null; anchorEnd: number | null; revision: number; userEdited: boolean; originalExcerpt: string | null }
export interface Bookmark { id: string; paragraphId: string; chapterId: string; offset: number; label: string | null; visibility: string; revision: number }
const root = '/reading'
export const readingGet = async <T>(path: string, params?: Record<string, unknown>) => (await apiClient.get<T>(root + path, { params })).data
export const readingPost = async <T>(path: string, body: unknown) => (await apiClient.post<T>(root + path, body, { timeout: 130_000 })).data
export const readingPut = async <T>(path: string, body: unknown) => (await apiClient.put<T>(root + path, body)).data
export const readingPatch = async <T>(path: string, body: unknown) => (await apiClient.patch<T>(root + path, body)).data
export const readingDelete = async (path: string, revision: number) => { await apiClient.delete(root + path, { params: { expectedRevision: revision } }) }
