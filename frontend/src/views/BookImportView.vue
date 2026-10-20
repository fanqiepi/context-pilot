<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter, RouterLink } from 'vue-router'
import { errorMessage } from '@/api/client'
import { readingGet, readingPost, type Preview } from '@/api/reading'
const route = useRoute(), router = useRouter()
const file = ref<File | null>(null), title = ref(''), source = ref('')
const rule = ref('TXT_CN_CHAPTER_V1'), level = ref(1), busy = ref(false), error = ref('')
const preview = ref<Preview | null>(null), chapters = ref<{ ordinal: number; title: string; paragraphCount: number }[]>([])
const page = ref(1)
let key = crypto.randomUUID(), timer: ReturnType<typeof setTimeout> | undefined, disposed = false
const rules = [{ value: 'TXT_CN_CHAPTER_V1', label: 'TXT：第…章 / 回 / 节' }, { value: 'TXT_EN_CHAPTER_V1', label: 'TXT：Chapter + 数字' }, { value: 'MARKDOWN_ATX_V1', label: 'Markdown：同级标题' }, { value: 'SINGLE_CHAPTER_V1', label: '作为单章导入' }]
const reasons: Record<string, string> = { CHAPTER_RULE_NO_MATCH: '没有匹配到章节，请更换规则或选择单章。', EMPTY_CHAPTER: '发现空章节，请核对文本或更换规则。', INVALID_UTF8: '文件不是有效的 UTF-8 编码，请转换后重新导入。', IMPORT_LIMIT_EXCEEDED: '正文或章节超过容量限制，请拆分文本后重新导入。', QUEUE_FULL: '导入队列已满，可稍后重新预览。', PARSE_INTERRUPTED: '上次解析因重启中断，可重新预览。', PARSE_TIMEOUT: '解析超时，可缩小文件后重试。' }
function selectFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
  if (file.value) { title.value = file.value.name.replace(/\.(txt|md)$/i, ''); if (file.value.name.endsWith('.md')) rule.value = 'MARKDOWN_ATX_V1' }
  key = crypto.randomUUID()
}
async function refresh() {
  const id = preview.value?.id || String(route.query.preview || '')
  if (!id || disposed) return
  try {
    const result = await readingGet<Preview>(`/imports/${id}`)
    if (disposed) return
    if (!preview.value) { title.value = result.title; rule.value = result.parserId; level.value = result.headingLevel || 1 }
    preview.value = result
    if (result.status === 'PARSING') timer = setTimeout(refresh, 700)
    else if (result.status === 'PREVIEW_READY') await loadChapters()
  } catch (e) { error.value = errorMessage(e) }
}
async function loadChapters() {
  if (!preview.value) return
  try { chapters.value = await readingGet(`/imports/${preview.value.id}/chapters`, { page: page.value }) } catch (e) { error.value = errorMessage(e) }
}
async function upload() {
  if (!file.value) return
  busy.value = true; error.value = ''
  try {
    const body = new FormData()
    body.set('file', file.value); body.set('title', title.value); body.set('sourceDescription', source.value); body.set('parserId', rule.value); body.set('clientRequestId', key)
    if (rule.value === 'MARKDOWN_ATX_V1') body.set('headingLevel', String(level.value))
    if (route.query.book) body.set('targetBookId', String(route.query.book))
    preview.value = await readingPost<Preview>('/imports', body)
    await router.replace({ query: { ...route.query, preview: preview.value.id } })
    page.value = 1; await refresh()
  } catch (e) { error.value = errorMessage(e) } finally { busy.value = false }
}
async function reparse() {
  if (!preview.value) return
  busy.value = true; error.value = ''; clearTimeout(timer)
  try {
    preview.value = await readingPost<Preview>(`/imports/${preview.value.id}/reparse`, { clientRequestId: crypto.randomUUID(), parserId: rule.value, headingLevel: rule.value === 'MARKDOWN_ATX_V1' ? level.value : null })
    await router.replace({ query: { ...route.query, preview: preview.value.id } }); page.value = 1; await refresh()
  } catch (e) { error.value = errorMessage(e) } finally { busy.value = false }
}
async function confirm() {
  if (!preview.value) return
  busy.value = true; error.value = ''
  try {
    const result = await readingPost<{ bookId: string; versionId: string }>(`/imports/${preview.value.id}/confirm`, { previewHash: preview.value.previewHash })
    await router.push({ path: `/books/${result.bookId}/read`, query: { version: result.versionId } })
  } catch (e) { error.value = errorMessage(e) } finally { busy.value = false }
}
onMounted(refresh)
onUnmounted(() => { disposed = true; clearTimeout(timer) })
</script>
<template>
  <section class="reading-library reading-import">
    <RouterLink to="/books">← 返回书架</RouterLink><h1>导入小说</h1>
    <p>仅导入你有权使用的 UTF-8 TXT / Markdown。目录及首尾预览可能包含尚未读到的内容。</p>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
    <el-form label-position="top" @submit.prevent="upload">
      <el-form-item v-if="!preview" label="文件（最多 20 MiB）"><input type="file" accept=".txt,.md" aria-label="选择小说文件" @change="selectFile" /></el-form-item>
      <el-form-item v-if="!preview" label="书名"><el-input v-model="title" maxlength="120" /></el-form-item>
      <el-form-item v-if="!preview" label="来源与许可说明"><el-input v-model="source" type="textarea" maxlength="1000" /></el-form-item>
      <el-form-item label="分章规则"><el-select v-model="rule"><el-option v-for="item in rules" :key="item.value" :value="item.value" :label="item.label" /></el-select></el-form-item>
      <el-form-item v-if="rule === 'MARKDOWN_ATX_V1'" label="标题级别"><el-select v-model="level"><el-option v-for="n in 3" :key="n" :value="n" :label="`H${n}`" /></el-select></el-form-item>
      <el-button v-if="!preview" type="primary" :disabled="!file || !title.trim()" :loading="busy" @click="upload">解析并预览</el-button>
      <el-button v-else :disabled="preview.status === 'CONFIRMED'" :loading="busy" @click="reparse">按当前规则重新预览</el-button>
    </el-form>
    <section v-if="preview" class="reading-preview">
      <h2>{{ preview.title }} · {{ { PARSING: '正在解析', PREVIEW_READY: '目录待确认', CONFIRMED: '已导入', FAILED: '解析失败', EXPIRED: '预览已过期' }[preview.status] || preview.status }}</h2>
      <p v-if="preview.errorCode">{{ reasons[preview.errorCode] || `无法完成预览：${preview.errorCode}` }}</p>
      <template v-if="preview.status === 'PREVIEW_READY'">
        <p>{{ preview.chapterCount }} 章 · {{ preview.paragraphCount }} 段 · {{ preview.characterCount.toLocaleString() }} 字符</p>
        <p v-if="preview.splitCount">有 {{ preview.splitCount }} 处超长段落被确定性拆分，请核对位置。</p>
        <el-alert v-if="preview.duplicateVersionId" title="这个文件与解析版本已存在，确认将打开已有版本。" type="info" :closable="false" />
        <ol><li v-for="chapter in chapters" :key="chapter.ordinal">{{ chapter.ordinal }} · {{ chapter.title }}（{{ chapter.paragraphCount }} 段）</li></ol>
        <el-pagination v-if="preview.chapterCount > 50" v-model:current-page="page" :total="preview.chapterCount" :page-size="50" layout="prev, pager, next" @current-change="loadChapters" />
        <h3>开头样本</h3><pre>{{ preview.firstSample }}</pre><h3>结尾样本</h3><pre>{{ preview.lastSample }}</pre>
        <el-button type="primary" :loading="busy" @click="confirm">确认目录并导入</el-button>
      </template>
      <el-button v-if="preview.status === 'CONFIRMED'" type="primary" @click="confirm">打开已导入版本</el-button>
    </section>
    <p class="reading-footnote">不会自动建立 AI 索引或产生模型费用。正文版本确认后不可变，更换文本或规则需新版本。</p>
  </section>
</template>
