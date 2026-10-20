<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { errorMessage } from '@/api/client'
import { readingGet, readingDelete, type Book, type Page } from '@/api/reading'
const books = ref<Book[]>([])
const page = ref(1)
const total = ref(0)
const error = ref('')
const loading = ref(false)
async function load() {
  loading.value = true; error.value = ''
  try { const result = await readingGet<Page<Book>>('/books', { page: page.value }); books.value = result.items; total.value = result.total }
  catch (e) { error.value = errorMessage(e) }
  finally { loading.value = false }
}
async function remove(book: Book) {
  try { await ElMessageBox.confirm(`从书架移除《${book.title}》？原文件与记录会保留。`, '移除书籍', { type: 'warning', confirmButtonText: '移除', cancelButtonText: '取消' }) }
  catch { return }
  try { await readingDelete(`/books/${book.id}`, book.revision); await load() } catch (e) { error.value = errorMessage(e) }
}
onMounted(load)
</script>
<template>
  <section class="reading-library">
    <div class="reading-heading"><div><p class="reading-eyebrow">个人阅读空间</p><h1>回来，接着读。</h1><p>你的书籍、阅读位置和笔记，保存在这里。</p></div><RouterLink to="/books/import" class="reading-primary">导入小说</RouterLink></div>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" />
    <div v-loading="loading" class="reading-book-grid">
      <article v-for="book in books" :key="book.id" class="reading-book-card">
        <div class="reading-book-cover" aria-hidden="true">{{ Array.from(book.title)[0] }}</div>
        <div><h2>{{ book.title }}</h2><p>{{ book.lastReadAt ? `最近阅读 ${new Date(book.lastReadAt).toLocaleString()}` : '还未开始阅读' }}</p><p class="reading-source">{{ book.sourceDescription || '个人导入文本' }}</p></div>
        <div class="reading-actions"><RouterLink :to="{ path: `/books/${book.id}/read`, query: { version: book.readingVersionId || book.latestVersionId } }" class="reading-primary">继续阅读</RouterLink><RouterLink :to="`/books/${book.id}/notes`">笔记</RouterLink><el-button text @click="remove(book)">移除</el-button></div>
      </article>
    </div>
    <el-empty v-if="!loading && !books.length" description="导入一本 UTF-8 TXT 或 Markdown 小说，开始阅读。" />
    <el-pagination v-if="total > 20" v-model:current-page="page" :total="total" :page-size="20" layout="prev, pager, next" @current-change="load" />
    <p class="reading-footnote">基础阅读无需 AI。阅读助手已接入已读范围与笔记确认流程，真实 AI 调用尚未开放。</p>
  </section>
</template>
