import axios from 'axios'

import type { ApiErrorPayload } from './types'

export const apiClient = axios.create({
  baseURL: '/api',
  timeout: 30_000,
})

export class ApiClientError extends Error {
  readonly code?: string
  readonly requestId?: string

  constructor(message: string, code?: string, requestId?: string) {
    super(message)
    this.name = 'ApiClientError'
    this.code = code
    this.requestId = requestId
  }
}

export function errorMessage(error: unknown, fallback = '请求失败，请稍后重试'): string {
  if (error instanceof ApiClientError) {
    return error.message
  }
  if (axios.isAxiosError<ApiErrorPayload>(error)) {
    return error.response?.data?.message ?? error.message ?? fallback
  }
  if (error instanceof Error && error.message) {
    return error.message
  }
  return fallback
}

export async function responseError(response: Response): Promise<ApiClientError> {
  let payload: ApiErrorPayload | undefined
  try {
    payload = (await response.json()) as ApiErrorPayload
  } catch {
    payload = undefined
  }
  return new ApiClientError(
    payload?.message ?? `请求失败（HTTP ${response.status}）`,
    payload?.code,
    payload?.requestId ?? payload?.traceId,
  )
}
