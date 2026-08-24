<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'

import {
  deleteUserMemory,
  getUserMemory,
  listUserMemories,
  updateUserMemory,
} from '@/api/contextPilot'
import { errorMessage } from '@/api/client'
import type {
  UserMemory,
  UserMemoryDetail,
  UserMemoryStatus,
  UserMemoryUpdate,
} from '@/api/types'

const memories = ref<UserMemory[]>([])
const allMemories = ref<UserMemory[]>([])
const loading = ref(false)
const saving = ref(false)
const activeOperationId = ref('')
const dialogVisible = ref(false)
const detail = ref<UserMemoryDetail | null>(null)
const originalExpiresAtInput = ref('')
const statusFilter = ref<'ALL' | UserMemoryStatus>('ALL')
const expiryFilter = ref<'ALL' | 'CURRENT' | 'EXPIRED'>('ALL')
const form = reactive({ content: '', expiresAt: '' })

const activeCount = computed(
  () => allMemories.value.filter((memory) => memory.status === 'ACTIVE' && !memory.expired).length,
)
const disabledCount = computed(
  () => allMemories.value.filter((memory) => memory.status === 'DISABLED').length,
)
const expiredCount = computed(() => allMemories.value.filter((memory) => memory.expired).length)

onMounted(() => void loadMemories())

async function loadMemories(): Promise<void> {
  loading.value = true
  try {
    const filters = {
      status: statusFilter.value === 'ALL' ? undefined : statusFilter.value,
      expired: expiryFilter.value === 'ALL' ? undefined : expiryFilter.value === 'EXPIRED',
    }
    const [all, filtered] = await Promise.all([
      listUserMemories(),
      statusFilter.value === 'ALL' && expiryFilter.value === 'ALL'
        ? Promise.resolve<UserMemory[]>([])
        : listUserMemories(filters),
    ])
    allMemories.value = all
    memories.value = filtered.length || statusFilter.value !== 'ALL' || expiryFilter.value !== 'ALL'
      ? filtered
      : all
  } catch (error) {
    ElMessage.error(errorMessage(error, '回答偏好加载失败'))
  } finally {
    loading.value = false
  }
}

async function openEditor(memory: UserMemory): Promise<void> {
  activeOperationId.value = memory.id
  try {
    detail.value = await getUserMemory(memory.id)
    form.content = detail.value.content
    form.expiresAt = toDateTimeLocal(detail.value.expiresAt)
    originalExpiresAtInput.value = form.expiresAt
    dialogVisible.value = true
  } catch (error) {
    ElMessage.error(errorMessage(error, '回答偏好详情加载失败'))
  } finally {
    activeOperationId.value = ''
  }
}

async function saveMemory(): Promise<void> {
  const current = detail.value
  if (!current) return

  const normalizedContent = form.content.trim().replace(/\s+/g, ' ')
  const input: UserMemoryUpdate = { version: current.version }
  if (normalizedContent !== current.content) input.content = form.content
  if (form.expiresAt !== originalExpiresAtInput.value) {
    input.expiresAt = form.expiresAt ? new Date(form.expiresAt).toISOString() : null
  }
  if (input.content === undefined && input.expiresAt === undefined) {
    ElMessage.info('没有需要保存的修改')
    return
  }

  try {
    await ElMessageBox.confirm(
      '保存后会生成新的不可变版本；旧版本只保留审计摘要。确定继续吗？',
      '确认纠正回答偏好',
      { type: 'warning', confirmButtonText: '确认保存', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  saving.value = true
  try {
    detail.value = await updateUserMemory(current.id, input)
    dialogVisible.value = false
    await loadMemories()
    ElMessage.success('回答偏好已更新')
  } catch (error) {
    ElMessage.error(errorMessage(error, '回答偏好更新失败，请刷新后重试'))
    await refreshDetail(current.id)
  } finally {
    saving.value = false
  }
}

async function toggleStatus(memory: UserMemory): Promise<void> {
  const nextStatus: UserMemoryStatus = memory.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  const action = nextStatus === 'ACTIVE' ? '启用' : '禁用'
  try {
    await ElMessageBox.confirm(
      `${action}“${memory.content}”？${nextStatus === 'DISABLED' ? '禁用后不会再用于新回答。' : '启用后将进入后续记忆注入候选。'}`,
      `确认${action}`,
      { type: 'warning', confirmButtonText: `确认${action}`, cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  activeOperationId.value = memory.id
  try {
    await updateUserMemory(memory.id, { version: memory.version, status: nextStatus })
    await loadMemories()
    ElMessage.success(`回答偏好已${action}`)
  } catch (error) {
    ElMessage.error(errorMessage(error, `${action}失败，请刷新后重试`))
    await loadMemories()
  } finally {
    activeOperationId.value = ''
  }
}

async function removeMemory(memory: UserMemory): Promise<void> {
  try {
    await ElMessageBox.confirm(
      `删除“${memory.content}”？当前采用逻辑删除，删除后不会再用于新回答。`,
      '确认删除回答偏好',
      { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }

  activeOperationId.value = memory.id
  try {
    await deleteUserMemory(memory.id)
    await loadMemories()
    ElMessage.success('回答偏好已删除')
  } catch (error) {
    ElMessage.error(errorMessage(error, '删除回答偏好失败'))
  } finally {
    activeOperationId.value = ''
  }
}

async function refreshDetail(id: string): Promise<void> {
  try {
    detail.value = await getUserMemory(id)
    form.content = detail.value.content
    form.expiresAt = toDateTimeLocal(detail.value.expiresAt)
    originalExpiresAtInput.value = form.expiresAt
  } catch {
    dialogVisible.value = false
  }
}

function statusLabel(memory: UserMemory): string {
  if (memory.expired) return '已过期'
  return memory.status === 'ACTIVE' ? '已启用' : '已禁用'
}

function statusType(memory: UserMemory): 'success' | 'info' | 'warning' {
  if (memory.expired) return 'warning'
  return memory.status === 'ACTIVE' ? 'success' : 'info'
}

function sourceLabel(source: UserMemory['sourceType']): string {
  return source === 'CHAT_CONFIRMED' ? '聊天确认' : '管理页面'
}

function changeReasonLabel(reason: string): string {
  const labels: Record<string, string> = {
    'Created from confirmed chat action': '由已确认的聊天操作创建',
    'Management update: content corrected': '纠正内容',
    'Management update: status changed': '变更状态',
    'Management update: expiry changed': '调整过期时间',
  }
  return labels[reason] ?? reason.replace('Management update: ', '管理更新：')
}

function formatDate(value: string | null): string {
  if (!value) return '永不过期'
  return new Intl.DateTimeFormat('zh-CN', {
    dateStyle: 'medium',
    timeStyle: 'short',
  }).format(new Date(value))
}

function toDateTimeLocal(value: string | null): string {
  if (!value) return ''
  const date = new Date(value)
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000)
  return local.toISOString().slice(0, 16)
}

function disablePastDates(date: Date): boolean {
  return date.getTime() < Date.now() - 60_000
}
</script>

<template>
  <section>
    <div class="page-heading">
      <div>
        <p class="page-kicker">Explicit memory</p>
        <h1>回答偏好</h1>
        <p class="page-description">
          管理你明确确认保存的全局表达偏好。当前为本机或可信网络单用户模式，不提供登录隔离。
        </p>
      </div>
    </div>

    <div class="memory-stats" aria-label="回答偏好统计">
      <article><strong>{{ activeCount }}</strong><span>当前启用</span></article>
      <article><strong>{{ disabledCount }}</strong><span>已禁用</span></article>
      <article><strong>{{ expiredCount }}</strong><span>已过期</span></article>
    </div>

    <section class="surface memory-panel">
      <header class="panel-header memory-toolbar">
        <div>
          <h2>已保存偏好</h2>
          <p>内容更新与状态操作均使用版本号防止覆盖较新的修改。</p>
        </div>
        <div class="memory-filters">
          <ElSelect v-model="statusFilter" aria-label="状态筛选" @change="loadMemories">
            <ElOption label="全部状态" value="ALL" />
            <ElOption label="已启用" value="ACTIVE" />
            <ElOption label="已禁用" value="DISABLED" />
          </ElSelect>
          <ElSelect v-model="expiryFilter" aria-label="过期筛选" @change="loadMemories">
            <ElOption label="全部时效" value="ALL" />
            <ElOption label="未过期" value="CURRENT" />
            <ElOption label="已过期" value="EXPIRED" />
          </ElSelect>
        </div>
      </header>

      <div v-loading="loading" class="memory-content">
        <div v-if="memories.length" class="memory-list">
          <article v-for="memory in memories" :key="memory.id" class="memory-card">
            <div class="memory-card-main">
              <div class="memory-card-tags">
                <ElTag :type="statusType(memory)" effect="plain">{{ statusLabel(memory) }}</ElTag>
                <ElTag type="info" effect="plain">{{ sourceLabel(memory.sourceType) }}</ElTag>
                <span>版本 {{ memory.currentRevision }}</span>
              </div>
              <p>{{ memory.content }}</p>
              <dl>
                <div><dt>更新时间</dt><dd>{{ formatDate(memory.updatedAt) }}</dd></div>
                <div><dt>过期时间</dt><dd>{{ formatDate(memory.expiresAt) }}</dd></div>
              </dl>
            </div>
            <div class="memory-actions">
              <ElButton text type="primary" :loading="activeOperationId === memory.id" @click="openEditor(memory)">
                纠正与详情
              </ElButton>
              <ElButton text :type="memory.status === 'ACTIVE' ? 'warning' : 'success'" :loading="activeOperationId === memory.id" @click="toggleStatus(memory)">
                {{ memory.status === 'ACTIVE' ? '禁用' : '启用' }}
              </ElButton>
              <ElButton text type="danger" :loading="activeOperationId === memory.id" @click="removeMemory(memory)">
                删除
              </ElButton>
            </div>
          </article>
        </div>
        <div v-else class="empty-panel">
          <div>
            <span class="empty-symbol">◇</span>
            <strong>暂无匹配的回答偏好</strong>
            <span>可在知识问答中输入“请记住我的偏好：…”并确认操作。</span>
          </div>
        </div>
      </div>
    </section>

    <ElDialog v-model="dialogVisible" title="纠正回答偏好" width="min(620px, 94vw)">
      <template v-if="detail">
        <ElForm label-position="top" @submit.prevent="saveMemory">
          <ElFormItem label="偏好内容" required>
            <ElInput v-model="form.content" type="textarea" :rows="4" maxlength="300" show-word-limit />
          </ElFormItem>
          <ElFormItem label="过期时间">
            <ElDatePicker
              v-model="form.expiresAt"
              type="datetime"
              value-format="YYYY-MM-DDTHH:mm"
              placeholder="留空表示永不过期"
              :disabled-date="disablePastDates"
              clearable
            />
          </ElFormItem>
        </ElForm>

        <section class="revision-panel">
          <h3>版本摘要</h3>
          <ol>
            <li v-for="revision in detail.revisions" :key="revision.revision">
              <strong>版本 {{ revision.revision }}</strong>
              <span>{{ sourceLabel(revision.sourceType) }} · {{ formatDate(revision.createdAt) }}</span>
              <small>{{ changeReasonLabel(revision.changeReason) }}</small>
            </li>
          </ol>
        </section>
      </template>
      <template #footer>
        <ElButton @click="dialogVisible = false">取消</ElButton>
        <ElButton type="primary" :loading="saving" @click="saveMemory">保存修改</ElButton>
      </template>
    </ElDialog>
  </section>
</template>

<style scoped>
.memory-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 20px;
}

.memory-stats article {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 18px 20px;
  border: 1px solid var(--line);
  border-radius: 18px;
  background: rgb(255 255 252 / 72%);
}

.memory-stats strong {
  color: var(--accent);
  font-family: Georgia, serif;
  font-size: 30px;
}

.memory-stats span {
  color: var(--muted);
  font-size: 13px;
}

.memory-panel {
  overflow: hidden;
}

.memory-toolbar,
.memory-filters,
.memory-card-tags,
.memory-actions {
  display: flex;
  align-items: center;
}

.memory-filters {
  gap: 10px;
}

.memory-filters .el-select {
  width: 132px;
}

.memory-content {
  min-height: 300px;
}

.memory-list {
  display: grid;
  gap: 1px;
  background: #e7ebe4;
}

.memory-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 24px;
  padding: 22px 24px;
  background: rgb(255 255 252 / 96%);
}

.memory-card-main {
  min-width: 0;
}

.memory-card-tags {
  flex-wrap: wrap;
  gap: 8px;
}

.memory-card-tags > span:last-child {
  color: var(--muted);
  font-size: 12px;
}

.memory-card p {
  overflow-wrap: anywhere;
  margin: 15px 0;
  font-size: 16px;
  line-height: 1.7;
}

.memory-card dl {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 24px;
  margin: 0;
  color: var(--muted);
  font-size: 12px;
}

.memory-card dl div {
  display: flex;
  gap: 7px;
}

.memory-card dd {
  margin: 0;
}

.memory-actions {
  align-self: center;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: flex-end;
}

.memory-actions .el-button {
  margin-left: 0;
}

.revision-panel {
  margin-top: 22px;
  padding-top: 18px;
  border-top: 1px solid var(--line);
}

.revision-panel h3 {
  margin: 0 0 12px;
  font-size: 14px;
}

.revision-panel ol {
  display: grid;
  gap: 10px;
  max-height: 210px;
  margin: 0;
  padding: 0;
  overflow: auto;
  list-style: none;
}

.revision-panel li {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  gap: 3px 12px;
  padding: 11px 13px;
  border-radius: 12px;
  background: #f3f6f1;
}

.revision-panel li span,
.revision-panel li small {
  color: var(--muted);
  font-size: 12px;
}

.revision-panel li small {
  grid-column: 1 / -1;
}

@media (max-width: 760px) {
  .memory-toolbar,
  .memory-card {
    align-items: stretch;
    grid-template-columns: 1fr;
  }

  .memory-toolbar {
    flex-direction: column;
  }

  .memory-filters,
  .memory-filters .el-select {
    width: 100%;
  }

  .memory-actions {
    justify-content: flex-start;
  }
}

@media (max-width: 520px) {
  .memory-stats {
    grid-template-columns: 1fr;
  }

  .memory-filters {
    flex-direction: column;
  }

  .memory-card {
    gap: 12px;
    padding: 18px 16px;
  }

  .revision-panel li {
    grid-template-columns: 1fr;
  }

  .revision-panel li small {
    grid-column: auto;
  }
}
</style>
