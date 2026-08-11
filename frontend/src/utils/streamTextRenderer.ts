export interface StreamTextRenderer {
  enqueue: (content: string) => void
  drain: () => Promise<void>
  flush: () => void
  cancel: () => void
}

const DEFAULT_INTERVAL_MS = 20

export function createStreamTextRenderer(
  append: (content: string) => void,
  intervalMs = DEFAULT_INTERVAL_MS,
): StreamTextRenderer {
  let buffer = ''
  let generation = 0
  let pumpPromise: Promise<void> | undefined

  async function pump(token: number): Promise<void> {
    while (token === generation && buffer) {
      const symbols = Array.from(buffer)
      const chunkLength = nextChunkLength(symbols.length)
      append(symbols.slice(0, chunkLength).join(''))
      buffer = symbols.slice(chunkLength).join('')
      await delay(intervalMs)
    }
    if (token === generation) {
      pumpPromise = undefined
    }
  }

  function startPump(): void {
    if (!pumpPromise) {
      pumpPromise = pump(generation)
    }
  }

  function finishBuffer(appendRemaining: boolean): void {
    generation += 1
    const remaining = buffer
    buffer = ''
    pumpPromise = undefined
    if (appendRemaining && remaining) {
      append(remaining)
    }
  }

  return {
    enqueue(content) {
      if (!content) {
        return
      }
      buffer += content
      startPump()
    },
    async drain() {
      while (pumpPromise) {
        await pumpPromise
      }
    },
    flush() {
      finishBuffer(true)
    },
    cancel() {
      finishBuffer(false)
    },
  }
}

function nextChunkLength(remaining: number): number {
  if (remaining > 800) return 16
  if (remaining > 240) return 8
  if (remaining > 80) return 4
  return 2
}

function delay(milliseconds: number): Promise<void> {
  return new Promise((resolve) => globalThis.setTimeout(resolve, milliseconds))
}
