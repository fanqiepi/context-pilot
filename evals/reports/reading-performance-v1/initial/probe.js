// Injected only by the disposable performance server, never included in frontend/dist.
(() => {
  const runId = crypto.randomUUID();
  let pending = { kind: 'shelf-navigation', start: 0, path: '/books' };
  let scheduled = false;
  const panel = document.createElement('output');
  panel.id = 'reading-performance';
  panel.setAttribute('aria-label', '离线性能测量');
  panel.style.cssText = 'position:fixed;bottom:0;right:0;z-index:9999;background:#fff;color:#333;border:1px solid #888;padding:4px;font:12px monospace;max-width:95vw';
  document.addEventListener('DOMContentLoaded', () => {
    document.body.append(panel);
    panel.textContent = '离线性能测量：等待页面可操作';
    new MutationObserver(check).observe(document.querySelector('#app'), { subtree: true, childList: true, attributes: true, characterData: true });
    check();
  });
  function visibleMask() {
    return [...document.querySelectorAll('.el-loading-mask')].some(n => getComputedStyle(n).display !== 'none');
  }
  function ready() {
    if (!pending || location.pathname !== pending.path || visibleMask()) return false;
    if (pending.kind.startsWith('shelf')) return !!document.querySelector('.reading-book-card a.reading-primary');
    return !!document.querySelector('.reading-paragraph') && document.querySelector('.reading-status')?.textContent.includes('已恢复上次保存的位置');
  }
  function check() {
    if (!ready() || scheduled) return;
    scheduled = true;
    requestAnimationFrame(() => requestAnimationFrame(() => {
      scheduled = false;
      if (ready()) finish(document.hidden ? 'HIDDEN' : 'READY');
    }));
  }
  async function finish(status) {
    const current = pending;
    if (!current) return;
    pending = null;
    const sample = {
      runId, kind: current.kind, status, elapsedMs: performance.now() - current.start,
      viewport: { width: innerWidth, height: innerHeight }, userAgent: navigator.userAgent,
      documentVisibility: document.visibilityState,
      resources: performance.getEntriesByType('resource').filter(r => r.startTime >= current.start && !r.name.includes('/__performance/')).map(r => ({
        path: new URL(r.name).pathname, durationMs: r.duration, transferBytes: r.transferSize, bodyBytes: r.encodedBodySize,
      })),
      paragraphCount: document.querySelectorAll('.reading-paragraph').length,
      firstParagraph: document.querySelector('.reading-paragraph small')?.textContent ?? null,
      selectedParagraph: document.querySelector('.reading-selected small')?.textContent ?? null,
      readingStatus: document.querySelector('.reading-status')?.textContent ?? null,
      horizontalOverflow: document.documentElement.scrollWidth > innerWidth,
      error: document.querySelector('.el-alert--error')?.textContent ?? null,
    };
    panel.textContent = `${sample.kind}: ${status} ${sample.elapsedMs.toFixed(1)} ms · ${sample.viewport.width}×${sample.viewport.height}`;
    panel.dataset.sample = JSON.stringify(sample);
    try {
      const response = await fetch('/__performance/sample', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(sample) });
      if (!response.ok) throw new Error('采集被拒绝');
      panel.dataset.saved = 'true';
    } catch { panel.textContent += ' · 采集失败，请保留页面'; }
  }
  document.addEventListener('click', event => {
    const anchor = event.target.closest?.('a');
    if (!anchor || event.button !== 0 || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    const url = new URL(anchor.href);
    if (url.origin !== location.origin || url.pathname === location.pathname) return;
    const kind = url.pathname === '/books' ? 'shelf-return' : /\/books\/[^/]+\/read$/.test(url.pathname) && anchor.textContent.trim() === '继续阅读' ? 'continue-reading' : null;
    if (!kind) return;
    if (pending) void finish('INTERRUPTED');
    pending = { kind, start: performance.now(), path: url.pathname };
    panel.dataset.saved = 'false';panel.textContent = '离线性能测量：等待页面可操作';
  }, true);
  setInterval(() => { if (pending && performance.now() - pending.start > 30000) void finish('TIMEOUT'); }, 500);
})();
