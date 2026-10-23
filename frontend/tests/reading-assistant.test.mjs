import { readFileSync } from 'node:fs'
import vm from 'node:vm'
import test from 'node:test'
import assert from 'node:assert/strict'
import ts from 'typescript'
import { computed, ref, reactive, watch, nextTick, effectScope } from 'vue'

// Exercise the actual component script with real Vue watchers and an offline API.
const source = readFileSync(new URL('../src/components/ReadingAssistant.vue', import.meta.url), 'utf8')
  .split('<script setup lang="ts">')[1].split('</script>')[0].replace(/^import .*$/gm, '')
const script = ts.transpile(source + '\nglobalThis.component = {create, index, targetBoundary, useResume, busy};', { target: ts.ScriptTarget.ES2022 })
async function fixture(t, { cancel = false, failBoundary = false, boundary = 0, closeDuringSave = false } = {}) {
  const calls = [], confirmations = []
  const props = reactive({ versionId: 'v1', ready: true, open: true,
    state: { scopeRevision: 1, readBoundary: boundary, visibilityEpoch: 0, resumePosition: { paragraphId: 'p8', paragraphOrdinal: 8 } },
    confirmBoundary: async (id, revision) => {
      calls.push(['boundary', id, revision])
      if (failBoundary) throw new Error('conflict')
      props.ready = false; await nextTick()
      props.state = { ...props.state, readBoundary: 8, scopeRevision: 2 }
      props.ready = true
      if (closeDuringSave) props.open = false
      await nextTick()
      return props.state
    },
  })
  const scope = effectScope(); t.after(() => scope.stop())
  const context = vm.createContext({ computed, ref, watch, onUnmounted: () => {}, defineProps: () => props, defineEmits: () => () => {},
    crypto: { randomUUID: () => 'request' }, setTimeout, clearTimeout,
    errorMessage: e => e.message, ElMessageBox: { confirm: async text => { confirmations.push(text); if (cancel) throw 'cancel' } },
    readingGet: async path => path.endsWith('/index') ? { liveAllowed: true, executionMode: 'DIRECT_LIVE' } : path === '/tasks' ? { items: [] } : { id: 'task', status: 'COMPLETED', visibility: 'VISIBLE', currentScopeRevision: props.state.scopeRevision, visibilityEpoch: 0 },
    readingPost: async (path, body) => { calls.push(['task', body]); return { id: 'task' } },
  })
  scope.run(() => vm.runInContext(script, context))
  await nextTick(); await new Promise(resolve => setImmediate(resolve))
  return { ...context.component, calls, confirmations, props }
}
test('first recap confirms the saved position and creates exactly one task at the new revision', async t => {
  const f = await fixture(t); await f.create('RECAP')
  assert.deepEqual(f.calls[0], ['boundary', 'p8', 1])
  assert.equal(f.calls.length, 2); assert.equal(f.calls[1][1].expectedScopeRevision, 2)
  assert.match(f.confirmations[0], /当前整段及跳过的内容/)
})
test('cancel does not change scope or call the model', async t => {
  const f = await fixture(t, { cancel: true }); await f.create('RECAP'); assert.equal(f.calls.length, 0)
})
test('scope conflict stops before task creation without retry', async t => {
  const f = await fixture(t, { failBoundary: true }); await f.create('RECAP'); assert.equal(f.calls.length, 1)
})
test('existing confirmed boundary is preserved despite a later resume position', async t => {
  const f = await fixture(t, { boundary: 3 }); await f.create('RECAP')
  assert.equal(f.calls.length, 1); assert.equal(f.calls[0][0], 'task'); assert.equal(f.calls[0][1].expectedScopeRevision, 1)
})
test('closing the assistant during boundary save prevents a late paid task', async t => {
  const f = await fixture(t, { closeDuringSave: true }); await f.create('RECAP'); assert.equal(f.calls.length, 1)
})
