import DOMPurify from 'dompurify'
import { marked } from 'marked'

const FORBID_TAGS = [
  'audio',
  'button',
  'embed',
  'form',
  'iframe',
  'img',
  'input',
  'math',
  'object',
  'script',
  'select',
  'source',
  'style',
  'svg',
  'textarea',
  'video',
]

export function renderMarkdown(source: string): string {
  const rendered = marked.parse(source, {
    async: false,
    breaks: true,
    gfm: true,
  }) as string

  return DOMPurify.sanitize(rendered, {
    FORBID_ATTR: ['style'],
    FORBID_TAGS,
  })
}
