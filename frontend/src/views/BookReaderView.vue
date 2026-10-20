<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue'
import { onBeforeRouteLeave, RouterLink, useRoute } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { errorMessage } from '@/api/client'
import ReadingAssistant from '@/components/ReadingAssistant.vue'
import { readingGet, readingPost, readingPut, readingPatch, readingDelete, type Book, type Version, type Chapter, type Paragraph, type Position, type ReadingState, type ReadingSource, type Note, type Bookmark, type Page } from '@/api/reading'

const route = useRoute(), bookId = String(route.params.bookId)
const book = ref<Book>(), versions = ref<Version[]>([]), versionId = ref('')
const state = ref<ReadingState>(), chapters = ref<Chapter[]>([]), chapterId = ref(''), chapterTitle = ref<string | null>(null)
const paragraphs = ref<Paragraph[]>([]), nextCursor = ref<string | null>(null), chapterPage = ref(1)
const notes = ref<Note[]>([]), bookmarks = ref<Bookmark[]>([]), notesPage = ref(1), notesTotal = ref(0), bookmarkPage = ref(1), bookmarkTotal = ref(0)
const loading = ref(false), error = ref(''), saving = ref(false), saveStatus = ref(''), conflict = ref(false)
const directory = ref(false), records = ref(route.path.endsWith('/notes')), settings = ref(false)
const assistant = ref(false)
const selected = ref<Paragraph>(), pending = ref<Position | null>(null)
const font = ref(20), lineHeight = ref(1.9), dark = ref(false)
const noteDialog = ref(false), noteTitle = ref(''), noteBody = ref(''), noteKind = ref('MANUAL'), editingNote = ref<Note | null>(null)
const noteAnchor = ref<{ id: string; start: number; end: number } | null>(null)
const noteKey = ref(crypto.randomUUID())
const retainedNoteEvidence = ref<string[]>([])
const validNoteText = computed(() => noteBody.value.trim().length > 0 && Array.from(noteBody.value.trim()).length <= 5000 && Array.from(noteTitle.value.trim()).length <= 120)
const noteKindLabel = (kind: string | null) => ({ MANUAL: '手写笔记', EXCERPT: '原文摘录', AI_RECAP: '已确认 AI 回顾', AI_CHARACTER: '已确认 AI 人物查证' }[kind || 'MANUAL'] || '笔记')
const sourceDialog = ref(false), source = ref<ReadingSource>(), sourceLoading = ref(false), scopeReady = ref(false)
const sourceChoices = ref<ReadingSource[]>([])
let sourcePath = '', scopeGeneration = 0, sourceRequest = 0, recordsRequest = 0, chaptersRequest = 0
let events: EventSource | undefined
let scopeRefresh = 0
let readerRequest = 0
let suspendedDraft: { version: string; epoch: number; title: string; body: string; kind: string; note: Note | null; anchor: typeof noteAnchor.value; evidence: string[] } | null = null
const currentVersion = computed(() => versions.value.find(v => v.id === versionId.value))
let saveTimer: ReturnType<typeof setTimeout> | undefined
let savePromise: Promise<void> | undefined, disposed = false, navigating = false
let channel: BroadcastChannel | undefined
let failedRequest: { clientRequestId: string; expectedResumeRevision: number; paragraphId: string; offset: number } | null = null
type ParagraphPage = { paragraphs: Paragraph[]; nextCursor: string | null; title: string | null }
function base() { return `/versions/${versionId.value}` }

async function init() {
  loading.value = true; error.value = ''
  try {
    book.value = await readingGet<Book>(`/books/${bookId}`)
    versions.value = await readingGet<Version[]>(`/books/${bookId}/versions`)
    versionId.value = String(route.query.version || book.value.readingVersionId || book.value.latestVersionId)
    if (!versions.value.some(v => v.id === versionId.value)) versionId.value = book.value.latestVersionId
    await loadVersion()
  } catch (e) { error.value = errorMessage(e) } finally { loading.value = false }
}
async function loadVersion() {
  events?.close(); invalidateScope()
  suspendedDraft = null
  const generation = scopeGeneration
  readerRequest++; navigating = false
  paragraphs.value = []; chapters.value = []; notes.value = []; bookmarks.value = []; pending.value = null
  failedRequest = null; conflict.value = false; selected.value = undefined
  state.value = await readingGet<ReadingState>(`${base()}/state`)
  chapterPage.value = 1; notesPage.value = 1; bookmarkPage.value = 1
  await loadChapters()
  const resume = state.value.resumePosition
  const first = chapters.value[0]
  if (resume) await openChapter(resume.chapterId, resume.paragraphId, resume.offset, false)
  else if (first) await openChapter(first.id, undefined, 0, false)
  await loadRecords()
  if (generation === scopeGeneration) scopeReady.value = true
  subscribeScope()
  saveStatus.value = resume ? '已恢复上次保存的位置' : '开始阅读后自动保存位置'
}
async function switchVersion(next: string) {
  const old = versionId.value
  await flush()
  if (pending.value || conflict.value) { error.value = '请先处理未保存的位置，再切换版本'; return }
  versionId.value = next
  try { await loadVersion() } catch (e) { versionId.value = old; error.value = errorMessage(e) }
}
async function loadChapters() {
  const generation = scopeGeneration, version = versionId.value, request = ++chaptersRequest
  try {
    const result = await readingGet<Page<Chapter>>(`${base()}/chapters`, { page: chapterPage.value })
    if (generation === scopeGeneration && version === versionId.value && request === chaptersRequest) {
      chapters.value = result.items
      const current = result.items.find(c => c.id === chapterId.value)
      if (current) chapterTitle.value = current.title
    }
  }
  catch (e) { error.value = errorMessage(e) }
}
async function openChapter(id: string, paragraphId?: string, offset = 0, persist = true) {
  if (navigating) return
  navigating = true
  const generation = scopeGeneration, version = versionId.value, request = ++readerRequest
  try {
    if (persist) { await flush(); if (pending.value || conflict.value) throw new Error('请先处理未保存的位置') }
    const result = await readingGet<ParagraphPage>(`${base()}/chapters/${id}/paragraphs`, paragraphId ? { atParagraphId: paragraphId } : {})
    if (version !== versionId.value || request !== readerRequest || disposed) return
    chapterId.value = id; chapterTitle.value = generation === scopeGeneration ? result.title : null; paragraphs.value = result.paragraphs; nextCursor.value = result.nextCursor
    selected.value = result.paragraphs[0]; directory.value = false
    await nextTick()
    if (paragraphId) scrollToParagraph(paragraphId, offset)
    else if (persist) document.querySelector('.reading-text')?.scrollIntoView({ block: 'start' })
    if (persist && selected.value) position(selected.value, offset)
  } catch (e) { if (request === readerRequest) error.value = errorMessage(e) } finally { if (request === readerRequest) navigating = false }
}
async function loadMore() {
  if (!nextCursor.value || loading.value) return
  const version = versionId.value, chapter = chapterId.value, request = readerRequest
  loading.value = true
  try {
    const result = await readingGet<ParagraphPage>(`${base()}/chapters/${chapterId.value}/paragraphs`, { cursor: nextCursor.value })
    if (version !== versionId.value || chapter !== chapterId.value || request !== readerRequest || disposed) return
    paragraphs.value.push(...result.paragraphs); nextCursor.value = result.nextCursor
  } catch (e) { error.value = errorMessage(e) } finally { loading.value = false }
}
function scrollToParagraph(id: string, offset = 0) {
  const text = document.querySelector<HTMLElement>(`[data-paragraph="${id}"] [data-text]`)
  text?.scrollIntoView({ block: 'center' })
  if (text?.firstChild && offset) {
    const range = document.createRange()
    range.setStart(text.firstChild, Array.from(text.textContent || '').slice(0, offset).join('').length); range.collapse(true)
    const rect = range.getBoundingClientRect()
    if (rect.height) window.scrollBy({ top: rect.top - window.innerHeight / 3 })
  }
}
function position(paragraph: Paragraph, offset = 0) {
  selected.value = paragraph
  if (conflict.value || disposed) return
  pending.value = { paragraphId: paragraph.id, chapterId: paragraph.chapterId, paragraphOrdinal: paragraph.paragraphOrdinal, offset }
  saveStatus.value = '位置待保存'
  clearTimeout(saveTimer); saveTimer = setTimeout(() => void flush(), 2000)
}
function selectedOffset(paragraph: Paragraph) {
  const selection = window.getSelection(), span = document.querySelector(`[data-paragraph="${paragraph.id}"] [data-text]`)
  if (!selection?.focusNode || !span?.contains(selection.focusNode)) return 0
  const range = document.createRange(); range.selectNodeContents(span); range.setEnd(selection.focusNode, selection.focusOffset)
  return Math.min(paragraph.codePointCount, Array.from(range.toString()).length)
}
function onScroll() {
  if (navigating || loading.value || conflict.value || disposed || sourceDialog.value || records.value || directory.value || noteDialog.value || assistant.value) return
  const nodes = Array.from(document.querySelectorAll<HTMLElement>('[data-paragraph]'))
  const node = nodes.find(n => { const r = n.getBoundingClientRect(); return r.bottom > 140 && r.top < window.innerHeight })
  const paragraph = paragraphs.value.find(p => p.id === node?.dataset.paragraph)
  if (paragraph && paragraph.id !== selected.value?.id) position(paragraph)
}
async function flush() {
  clearTimeout(saveTimer)
  if (savePromise) { await savePromise; if (pending.value && !conflict.value && !failedRequest) await flush(); return }
  if (!pending.value || !state.value || conflict.value || disposed) return
  const target = { ...pending.value }
  const request = failedRequest || { clientRequestId: crypto.randomUUID(), expectedResumeRevision: state.value.resumeRevision, paragraphId: target.paragraphId, offset: target.offset }
  saving.value = true
  savePromise = (async () => {
    try {
      const result = await readingPut<{ state: ReadingState }>(`${base()}/resume-position`, request)
      mergeState(result.state); failedRequest = null
      if (pending.value?.paragraphId === request.paragraphId && pending.value?.offset === request.offset) pending.value = null
      saveStatus.value = pending.value ? '位置待保存' : '阅读位置已保存'
    } catch (e) {
      const failure = e as { response?: { status?: number } }
      if (failure.response?.status === 409) { conflict.value = true; failedRequest = null }
      else failedRequest = request
      saveStatus.value = errorMessage(e, '保存失败，位置仍待保存')
    } finally { saving.value = false }
  })()
  await savePromise; savePromise = undefined
  if (pending.value && !conflict.value && !failedRequest) { saveTimer = setTimeout(() => void flush(), 2000) }
}
async function resolveConflict(useHere: boolean) {
  try {
    const target = pending.value
    state.value = await readingGet<ReadingState>(`${base()}/state`)
    conflict.value = false; failedRequest = null; pending.value = null
    if (useHere && target) { pending.value = target; await flush() }
    else { const saved = state.value.resumePosition; if (saved) await openChapter(saved.chapterId, saved.paragraphId, saved.offset, false) }
  } catch (e) { error.value = errorMessage(e) }
}
async function markRead(paragraph: Paragraph | null) {
  if (!state.value) return
  const expectedScopeRevision = state.value.scopeRevision
  const rollback = (paragraph?.paragraphOrdinal || 0) < state.value.readBoundary
  try { await ElMessageBox.confirm(paragraph ? `将正文开头到第 ${paragraph.paragraphOrdinal} 段全部视为已读？跳过的内容也会计入。${rollback ? '这会回退范围，较后笔记、标题和原文预览将隐藏。' : ''}` : '清空已读范围后，较后笔记和标签将隐藏。', rollback ? '重设已读范围' : '确认已读范围', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' }) } catch { return }
  try {
    invalidateScope()
    const result = await readingPut<{ state: ReadingState }>(`${base()}/read-boundary`, { clientRequestId: crypto.randomUUID(), expectedScopeRevision, lastReadParagraphId: paragraph?.id ?? null, confirmContiguousPrefix: true })
    mergeState(result.state)
    channel?.postMessage(versionId.value); await focus()
  } catch (e) { error.value = errorMessage(e); await focus() }
}
async function loadRecords() {
  const generation = scopeGeneration, version = versionId.value, request = ++recordsRequest
  const [n, b] = await Promise.all([readingGet<Page<Note>>(`${base()}/notes`, { page: notesPage.value }), readingGet<Page<Bookmark>>(`${base()}/bookmarks`, { page: bookmarkPage.value })])
  if (generation !== scopeGeneration || version !== versionId.value || request !== recordsRequest || disposed) return
  notes.value = n.items; notesTotal.value = n.total; bookmarks.value = b.items; bookmarkTotal.value = b.total
}
async function bookmark() {
  if (!selected.value) return
  try { await readingPost(`${base()}/bookmarks`, { clientRequestId: crypto.randomUUID(), paragraphId: selected.value.id, offset: selectedOffset(selected.value), label: '' }); await loadRecords(); records.value = true }
  catch (e) { error.value = errorMessage(e) }
}
function newNote(excerpt = false) {
  editingNote.value = null; noteTitle.value = ''; noteBody.value = ''; noteKind.value = excerpt ? 'EXCERPT' : 'MANUAL'; noteAnchor.value = null; noteKey.value = crypto.randomUUID()
  if (excerpt) {
    const selection = window.getSelection()
    if (!selection?.rangeCount || selection.isCollapsed) { error.value = '请先在同一段内选择需要摘录的文字'; return }
    const range = selection.getRangeAt(0)
    const paragraph = paragraphs.value.find(p => {
      const span = document.querySelector(`[data-paragraph="${p.id}"] [data-text]`)
      return span?.contains(range.startContainer) && span.contains(range.endContainer)
    })
    if (!paragraph) { error.value = '首版摘录支持同一段内的选文'; return }
    const span = document.querySelector(`[data-paragraph="${paragraph.id}"] [data-text]`)!
    const before = document.createRange(); before.selectNodeContents(span); before.setEnd(range.startContainer, range.startOffset)
    const start = Array.from(before.toString()).length, text = range.toString()
    noteBody.value = text; noteAnchor.value = { id: paragraph.id, start, end: start + Array.from(text).length }
  }
  noteDialog.value = true
}
function editNote(note: Note) { editingNote.value = note; noteTitle.value = note.title || ''; noteBody.value = note.body || ''; noteKind.value = note.kind || 'MANUAL'; retainedNoteEvidence.value = [...note.retainedEvidenceIds]; noteDialog.value = true }
async function saveNote() {
  if (!state.value || !validNoteText.value) return
  try {
    const content = { title: noteTitle.value, body: noteBody.value, expectedScopeRevision: state.value.scopeRevision }
    if (editingNote.value) await readingPatch(`/notes/${editingNote.value.id}`, { ...content, expectedRevision: editingNote.value.revision, retainedEvidenceIds: retainedNoteEvidence.value })
    else await readingPost(`${base()}/notes`, { ...content, clientRequestId: noteKey.value, kind: noteKind.value, anchorParagraphId: noteAnchor.value?.id ?? null, anchorStart: noteAnchor.value?.start ?? null, anchorEnd: noteAnchor.value?.end ?? null })
    noteDialog.value = false; await loadRecords(); records.value = true
  } catch (e) { error.value = errorMessage(e) }
}
async function removeRecord(kind: 'notes' | 'bookmarks', item: Note | Bookmark) {
  try { await ElMessageBox.confirm('确认移除这条记录？', '移除记录', { confirmButtonText: '移除', cancelButtonText: '取消' }) } catch { return }
  try { await readingDelete(`/${kind}/${item.id}`, item.revision); await loadRecords() } catch (e) { error.value = errorMessage(e) }
}
function mergeState(current: ReadingState) {
  if (state.value?.versionId === current.versionId) {
    if (current.scopeRevision < state.value.scopeRevision) {
      current = { ...current, scopeRevision: state.value.scopeRevision, readBoundary: state.value.readBoundary, visibilityEpoch: state.value.visibilityEpoch }
    }
    if (current.resumeRevision < state.value.resumeRevision) current = { ...current, resumeRevision: state.value.resumeRevision, resumePosition: state.value.resumePosition }
  }
  state.value = current
}
function invalidateScope() {
  if (noteDialog.value && state.value) suspendedDraft = { version: versionId.value, epoch: state.value.visibilityEpoch, title: noteTitle.value, body: noteBody.value, kind: noteKind.value, note: editingNote.value, anchor: noteAnchor.value, evidence: [...retainedNoteEvidence.value] }
  scopeGeneration++; scopeReady.value = false
  notes.value = []; bookmarks.value = []; chapters.value = []; chapterTitle.value = null
  sourceRequest++; source.value = undefined; sourceChoices.value = []; sourceDialog.value = false; sourceLoading.value = false
  noteDialog.value = false; noteTitle.value = ''; noteBody.value = ''; editingNote.value = null; noteAnchor.value = null
}
async function focus() {
  if (!versionId.value || disposed) return
  const request = ++scopeRefresh, version = versionId.value
  invalidateScope()
  const generation = scopeGeneration
  try {
    const current = await readingGet<ReadingState>(`${base()}/state`)
    if (request !== scopeRefresh || generation !== scopeGeneration || version !== versionId.value || disposed) return
    if (state.value && current.resumeRevision !== state.value.resumeRevision && pending.value) conflict.value = true
    if (!saving.value && !pending.value) mergeState(current)
    else if (state.value && current.scopeRevision >= state.value.scopeRevision) {
      state.value = { ...state.value, scopeRevision: current.scopeRevision, readBoundary: current.readBoundary, visibilityEpoch: current.visibilityEpoch }
    }
    await loadChapters(); await loadRecords()
    if (generation === scopeGeneration && request === scopeRefresh) {
      scopeReady.value = true
      if (suspendedDraft?.version === version && suspendedDraft.epoch === current.visibilityEpoch) {
        noteTitle.value = suspendedDraft.title; noteBody.value = suspendedDraft.body; noteKind.value = suspendedDraft.kind
        editingNote.value = suspendedDraft.note; noteAnchor.value = suspendedDraft.anchor; retainedNoteEvidence.value = suspendedDraft.evidence; noteDialog.value = true
      }
      suspendedDraft = null
    }
  } catch (e) { error.value = errorMessage(e) }
}
function subscribeScope() {
  events?.close()
  const version = versionId.value
  events = new EventSource(`/api/reading/versions/${version}/state/events`)
  const changed = (event: MessageEvent<string>) => {
    if (version !== versionId.value || disposed) return
    try {
      const snapshot = JSON.parse(event.data) as { versionId: string; scopeRevision: number }
      if (snapshot.versionId !== version) return
      if (!scopeReady.value || snapshot.scopeRevision > (state.value?.scopeRevision ?? -1)) void focus()
    } catch { invalidateScope() }
  }
  events.addEventListener('snapshot', changed)
  events.addEventListener('scope_changed', changed)
  events.onerror = () => { if (version === versionId.value && !disposed) invalidateScope() }
}
async function previewSource(path: string) {
  if (!scopeReady.value) return
  const request = ++sourceRequest, generation = scopeGeneration, version = versionId.value
  source.value = undefined; sourceChoices.value = []; sourcePath = path; sourceDialog.value = true; sourceLoading.value = true
  try {
    const result = await readingGet<ReadingSource | { sources: ReadingSource[]; scopeRevision: number; visibilityEpoch: number }>(path)
    if (request !== sourceRequest || generation !== scopeGeneration || version !== versionId.value || !sourceDialog.value) return
    if (result.visibilityEpoch !== state.value?.visibilityEpoch || result.scopeRevision !== state.value?.scopeRevision) { await focus(); return }
    sourceChoices.value = 'sources' in result ? result.sources : [result]
    source.value = sourceChoices.value[0]
  } catch (e) {
    if (request === sourceRequest) { sourceDialog.value = false; error.value = errorMessage(e) }
  } finally { if (request === sourceRequest) sourceLoading.value = false }
}
function closeSource() { sourceRequest++; source.value = undefined; sourceChoices.value = [] }
async function continueFromSource() {
  if (!source.value) return
  const request = sourceRequest, generation = scopeGeneration, selectedSource = source.value
  try {
    const result = await readingGet<ReadingSource | { sources: ReadingSource[]; scopeRevision: number; visibilityEpoch: number }>(sourcePath)
    if (request !== sourceRequest || generation !== scopeGeneration || !sourceDialog.value) return
    if (result.scopeRevision !== state.value?.scopeRevision || result.visibilityEpoch !== state.value?.visibilityEpoch) { await focus(); return }
    const verified = ('sources' in result ? result.sources : [result]).find(s => s.paragraphId === selectedSource.paragraphId && s.startOffset === selectedSource.startOffset)
    if (!verified) return
    sourceDialog.value = false; source.value = undefined; records.value = false
    await openChapter(verified.chapterId, verified.paragraphId, verified.startOffset)
  } catch (e) { sourceDialog.value = false; source.value = undefined; error.value = errorMessage(e) }
}
function visibilityChanged() {
  if (document.hidden) invalidateScope()
  else void focus()
}
function saveSettings() { localStorage.setItem('contextpilot.reading.settings', JSON.stringify({ font: font.value, lineHeight: lineHeight.value, dark: dark.value })) }
function unload(event: BeforeUnloadEvent) { if (pending.value) { void flush(); event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(async () => { await flush(); if (!pending.value) return true; try { await ElMessageBox.confirm('位置尚未保存，仍要离开？', '未保存的位置', { confirmButtonText: '离开', cancelButtonText: '继续保存' }); return true } catch { return false } })
onMounted(async () => {
  try { const s = JSON.parse(localStorage.getItem('contextpilot.reading.settings') || '{}'); if (s.font >= 14 && s.font <= 28) font.value = s.font; if (s.lineHeight >= 1.4 && s.lineHeight <= 2.2) lineHeight.value = s.lineHeight; dark.value = s.dark === true } catch { /* use defaults */ }
  window.addEventListener('scroll', onScroll, { passive: true }); window.addEventListener('beforeunload', unload); window.addEventListener('focus', focus)
  document.addEventListener('visibilitychange', visibilityChanged)
  if ('BroadcastChannel' in window) { channel = new BroadcastChannel('contextpilot.reading.scope'); channel.onmessage = event => { if (event.data === versionId.value) void focus() } }
  await init()
})
onUnmounted(() => { disposed = true; events?.close(); clearTimeout(saveTimer); channel?.close(); document.removeEventListener('visibilitychange', visibilityChanged); window.removeEventListener('scroll', onScroll); window.removeEventListener('beforeunload', unload); window.removeEventListener('focus', focus) })
</script>
<template>
  <section class="reading-reader" :class="{ 'reading-dark': dark }">
    <header class="reading-toolbar">
      <RouterLink to="/books">← 书架</RouterLink><h1>{{ book?.title || '阅读' }}</h1>
      <el-button @click="directory = true">目录</el-button><el-button @click="assistant = true">阅读助手</el-button><el-button @click="records = true">书签与笔记</el-button><el-button @click="settings = true">阅读设置</el-button>
    </header>
    <el-alert v-if="error" :title="error" type="error" show-icon @close="error = ''" />
    <div class="reading-status"><span>{{ saveStatus }}</span><el-button v-if="pending && !conflict" text :loading="saving" @click="flush">立即保存</el-button><span>已读边界：{{ state?.readBoundary || 0 }} 段</span></div>
    <el-alert v-if="!scopeReady && !loading" title="正在重新校验已读范围，相关记录与预览暂时隐藏。" type="info" :closable="false" />
    <el-alert v-if="conflict" title="另一个页面已更新阅读位置。请选择如何继续。" type="warning" :closable="false">
      <el-button @click="resolveConflict(false)">采用已存位置</el-button><el-button @click="resolveConflict(true)">从此处继续</el-button>
    </el-alert>
    <div class="reading-inline-actions"><el-button :disabled="!selected" @click="bookmark">添加书签</el-button><el-button @click="newNote(false)">手写笔记</el-button><el-button @mousedown.prevent @click="newNote(true)">选文摘录</el-button></div>
    <article v-loading="loading" class="reading-text" :style="{ fontSize: `${font}px`, lineHeight }">
      <h2>{{ (scopeReady && chapterTitle) || '正文 · 章节标题默认隐藏' }}</h2>
      <div v-for="p in paragraphs" :key="p.id" :data-paragraph="p.id" class="reading-paragraph" :class="{ 'reading-selected': selected?.id === p.id }">
        <p data-text @mouseup="position(p, selectedOffset(p))">{{ p.text }}</p>
        <div class="reading-paragraph-actions"><small>第 {{ p.paragraphOrdinal }} 段</small><button @click="markRead(p)">{{ p.paragraphOrdinal < (state?.readBoundary || 0) ? '重设已读范围到这里' : '已读到这里' }}</button><button v-if="scopeReady && p.paragraphOrdinal <= (state?.readBoundary || 0)" @click="previewSource(`${base()}/paragraphs/${p.id}/preview`)">查看原文</button></div>
      </div>
      <el-button v-if="nextCursor" :loading="loading" @click="loadMore">加载本章后续段落</el-button>
      <p v-else class="reading-chapter-end">本章正文已加载完 <template v-if="paragraphs.length && paragraphs[paragraphs.length - 1]!.paragraphOrdinal > (state?.readBoundary || 0)">· <button @click="markRead(paragraphs[paragraphs.length - 1]!)">读完本章</button></template> · <button @click="directory = true">选择下一章</button></p>
    </article>
    <el-drawer v-model="assistant" title="回顾前情与人物查证" size="min(94vw, 560px)">
      <ReadingAssistant :version-id="versionId" :state="state" :ready="scopeReady" :open="assistant" @continue="s => { assistant = false; void openChapter(s.chapterId, s.paragraphId, s.startOffset) }" @close="assistant = false" @saved="loadRecords" />
    </el-drawer>
    <el-drawer v-model="directory" title="目录与版本" direction="ltr" size="min(90vw, 380px)">
      <el-select :model-value="versionId" @change="switchVersion"><el-option v-for="(v, index) in versions" :key="v.id" :value="v.id" :label="`版本 ${versions.length - index} · ${v.chapterCount} 章`" /></el-select>
      <p>版本之间的进度独立。未读章仅显示序号。</p><RouterLink :to="{ path: '/books/import', query: { book: bookId } }">导入本书新版本</RouterLink>
      <ol class="reading-chapter-list"><li v-for="c in chapters" :key="c.id"><button :class="{ active: chapterId === c.id }" @click="openChapter(c.id)">第 {{ c.chapterOrdinal }} 章 {{ (scopeReady && c.title) || '' }}</button></li></ol>
      <el-pagination v-model:current-page="chapterPage" :total="currentVersion?.chapterCount || 0" :page-size="50" layout="prev, pager, next" @current-change="loadChapters" />
      <el-button text @click="markRead(null)">清空已读范围</el-button>
    </el-drawer>
    <el-drawer v-model="records" title="本书书签与笔记" size="min(92vw, 480px)">
      <h3>书签</h3><div v-for="b in bookmarks" :key="b.id" class="reading-record"><button @click="openChapter(b.chapterId, b.paragraphId, b.offset); records = false">{{ (scopeReady && b.label) || '保留的阅读位置' }}</button><el-button text @click="removeRecord('bookmarks', b)">移除</el-button></div>
      <el-pagination v-if="bookmarkTotal > 20" v-model:current-page="bookmarkPage" :total="bookmarkTotal" :page-size="20" layout="prev, pager, next" @current-change="loadRecords" />
      <h3>笔记</h3><el-button @click="newNote(false)">新建手写笔记</el-button>
      <div v-for="n in notes" :key="n.id" class="reading-record">
        <template v-if="scopeReady && n.visibility === 'VISIBLE'"><h4>{{ n.title || noteKindLabel(n.kind) }}{{ n.userEdited ? ' · 已编辑' : '' }}</h4><small>{{ noteKindLabel(n.kind) }}</small><p style="white-space: pre-wrap">{{ n.body }}</p><blockquote v-if="n.userEdited && n.originalExcerpt">原选文：{{ n.originalExcerpt }}</blockquote><p v-if="n.sourceTaskId">保留整个来源任务的范围；用户编辑不代表小说事实已获验证。</p><el-button text @click="editNote(n)">编辑</el-button><el-button v-if="n.chapterId && n.anchorParagraphId" text @click="previewSource(`/notes/${n.id}/source`)">查看出处</el-button><el-button v-for="key in n.retainedEvidenceIds" :key="key" text @click="previewSource(`/notes/${n.id}/citations/${key}`)">[{{ key }}] 原文</el-button></template>
        <p v-else>此笔记超出当前已读范围</p><el-button text @click="removeRecord('notes', n)">移除</el-button>
      </div>
      <el-pagination v-if="notesTotal > 20" v-model:current-page="notesPage" :total="notesTotal" :page-size="20" layout="prev, pager, next" @current-change="loadRecords" />
      <el-empty v-if="!notes.length && !bookmarks.length" description="留下书签、摘录或手写笔记。" />
    </el-drawer>
    <el-drawer v-model="settings" title="阅读设置" size="min(90vw, 340px)">
      <label>字号 {{ font }} px</label><el-slider v-model="font" :min="14" :max="28" @change="saveSettings" />
      <label>行距 {{ lineHeight }}</label><el-slider v-model="lineHeight" :min="1.4" :max="2.2" :step="0.1" @change="saveSettings" />
      <el-switch v-model="dark" active-text="深色阅读" @change="saveSettings" /><p>设置仅保存在当前浏览器。</p>
    </el-drawer>
    <el-dialog v-model="sourceDialog" title="原文预览" width="min(92vw, 640px)" @close="closeSource">
      <div v-if="sourceChoices.length > 1"><el-button v-for="s in sourceChoices" :key="`${s.paragraphId}/${s.startOffset}`" @click="source = s">第 {{ s.paragraphOrdinal }} 段 · {{ s.startOffset }}</el-button></div>
      <div v-loading="sourceLoading">
        <template v-if="source"><p>第 {{ source.chapterOrdinal }} 章 {{ source.chapterTitle || '' }} · 第 {{ source.paragraphOrdinal }} 段</p><blockquote style="white-space: pre-wrap">{{ source.text }}</blockquote><p>仅显示当前已读范围内原文。关闭后回到原阅读位置。</p></template>
      </div>
      <template #footer><el-button @click="sourceDialog = false">关闭预览</el-button><el-button type="primary" :disabled="!source" @click="continueFromSource">从这里继续读</el-button></template>
    </el-dialog>
    <el-dialog v-model="noteDialog" :title="editingNote ? '编辑笔记' : noteKind === 'EXCERPT' ? '保存原文摘录' : '新建手写笔记'" width="min(92vw, 580px)">
      <el-form label-position="top"><el-form-item label="标题"><el-input v-model="noteTitle" maxlength="240" /><small>{{ Array.from(noteTitle.trim()).length }} / 120 字符</small></el-form-item><el-form-item label="内容"><el-input v-model="noteBody" type="textarea" :rows="8" maxlength="10000" :readonly="noteKind === 'EXCERPT' && !editingNote" /><small>{{ Array.from(noteBody.trim()).length }} / 5000 字符</small></el-form-item></el-form>
      <p v-if="noteAnchor">如果出处超出已读边界，保存后将暂时隐藏；可先明确确认已读范围。</p>
      <el-checkbox-group v-if="editingNote?.sourceTaskId" v-model="retainedNoteEvidence" aria-label="保留笔记引用"><el-checkbox v-for="key in editingNote.retainedEvidenceIds" :key="key" :value="key">{{ key }}</el-checkbox></el-checkbox-group>
      <p v-if="editingNote?.sourceTaskId">引用只能保留或删减。编辑不会降低此笔记的已读范围标签。</p>
      <template #footer><el-button @click="noteDialog = false">取消</el-button><el-button type="primary" :disabled="!validNoteText" @click="saveNote">保存笔记</el-button></template>
    </el-dialog>
  </section>
</template>
