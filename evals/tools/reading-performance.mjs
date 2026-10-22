import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import os from 'node:os';
import { createHash } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
export const p95 = values => values.length ? [...values].sort((a,b) => a-b)[Math.ceil(values.length * .95)-1] : null;
export function summarize(samples, expectedParagraph = 9610) {
  const groups = {};
  for (const s of samples) {
    const key = `${s.viewport.width}x${s.viewport.height}/${s.kind}`;
    const g = groups[key] ??= { count: 0, failed: 0, samplesMs: [], maxMs: null, p95Ms: null, withinTwoSeconds: false };
    g.count++;
    const wrongResume = s.kind === 'continue-reading' && (s.selectedParagraph !== `第 ${expectedParagraph} 段`
      || s.firstParagraph !== s.selectedParagraph || s.paragraphCount !== 40 || !s.readingStatus?.includes('已读边界：0 段'));
    const missingResources = !s.resources?.some(r => r.path?.startsWith('/api/reading/'));
    if (s.status !== 'READY' || s.documentVisibility !== 'visible' || s.error || s.horizontalOverflow || wrongResume || missingResources || !Number.isFinite(s.elapsedMs) || s.elapsedMs < 0) g.failed++;
    else g.samplesMs.push(s.elapsedMs);
    g.maxMs = g.samplesMs.length ? Math.max(...g.samplesMs) : null;
    g.p95Ms = p95(g.samplesMs);
    g.withinTwoSeconds = g.failed === 0 && g.count > 0 && g.maxMs <= 2000;
  }
  // A single fast run cannot be reported as a completed browser benchmark.
  const widths = new Set(samples.filter(s => s.kind === 'continue-reading').map(s => s.viewport.width));
  const complete = widths.size >= 2 && [...widths].some(w => w <= 480) && [...widths].some(w => w >= 1000)
    && [...widths].every(w => ['shelf-return', 'continue-reading'].every(kind =>
      Object.entries(groups).some(([key,g]) => key.startsWith(`${w}x`) && key.endsWith(`/${kind}`) && g.count >= 30 && g.withinTwoSeconds)))
    && samples.some(s => s.kind === 'shelf-navigation')
    && Object.values(groups).every(g => g.withinTwoSeconds);
  return { status: complete ? 'BROWSER_SAMPLES_PASS' : 'INCOMPLETE_OR_FAILED', groups };
}

export function startServer(session, port, dist = path.join(ROOT, 'frontend/dist')) {
  const fixture = JSON.parse(fs.readFileSync(path.join(session, 'fixture.json'), 'utf8'));
  if (!Number.isInteger(fixture.port) || fixture.port < 1024 || fixture.port > 65535 || fixture.mode !== 'OFFLINE_ENGINEERING') throw Error('Invalid isolated fixture');
  const html = fs.readFileSync(path.join(dist, 'index.html'), 'utf8').replace('<head>', '<head><script src="/__performance/probe.js"></script>');
  const samplesFile = path.join(session, 'browser-samples.jsonl');
  fs.writeFileSync(samplesFile, '', { flag: 'wx' });
  const samples = [];
  const sha = data => createHash('sha256').update(data).digest('hex');
  const report = () => ({ version: 'reading-performance-v1', recordedAt: new Date().toISOString(), fixture,
    environment: { os: os.version(), cpu: os.cpus()[0]?.model, memoryBytes: os.totalmem(), node: process.version },
    implementationLockSha256: sha(fs.readFileSync(path.join(ROOT, 'evals/configs/reading/acceptance-v10.lock.json'))),
    builtIndexSha256: sha(fs.readFileSync(path.join(dist, 'index.html'))),
    service: { paragraphP95Ms: p95(fixture.paragraphSamplesMs), positionWriteP95Ms: p95(fixture.positionWriteSamplesMs) },
    browser: summarize(samples,fixture.fixture.resumeParagraph), samples,
    limitations: ['First navigation uses a fresh localhost origin; inspect resource transfer sizes for browser cache evidence.',
      'Warm application/DB and OS; not a cold machine or throttled network.',
      'Browser readiness = rendered controls and restored position, no loading mask, followed by two animation frames. Includes loopback HTTP and browser work.',
      'No AI performance, semantic acceptance, reader feedback or video recording. Failed/hidden/timeout samples are retained.'] });
  const save = () => fs.writeFileSync(path.join(session, 'report.json'), JSON.stringify(report(), null, 2)+'\n');
  const send = (res, code, type, body) => { res.writeHead(code, { 'Content-Type': type, 'Cache-Control': 'no-store' });res.end(body); };
  const server = http.createServer(async (req,res) => {
    const origin = `http://127.0.0.1:${server.address().port}`;
    if (req.headers.host !== new URL(origin).host) return send(res,403,'text/plain','Loopback host required');
    const url = new URL(req.url, origin);
    if (req.method === 'POST' && url.pathname === '/__performance/sample') {
      if (req.headers.origin !== url.origin) return send(res,403,'text/plain','Same origin required');
      try {
        let body='';for await (const chunk of req) { body+=chunk;if(Buffer.byteLength(body)>128*1024)throw Error('Sample too large'); }
        const sample=JSON.parse(body);
        if (!['shelf-navigation','shelf-return','continue-reading'].includes(sample.kind) || !['READY','HIDDEN','INTERRUPTED','TIMEOUT'].includes(sample.status)
          || !Number.isFinite(sample.elapsedMs) || sample.elapsedMs < 0 || !Number.isInteger(sample.viewport?.width) || !Number.isInteger(sample.viewport?.height) || !Array.isArray(sample.resources)) throw Error('Invalid sample');
        fs.appendFileSync(samplesFile, JSON.stringify(sample)+'\n');samples.push(sample);save();
        return send(res,201,'text/plain','Recorded');
      } catch { return send(res,400,'text/plain','Invalid sample'); }
    }
    if (req.method === 'GET' && url.pathname === '/__performance/report') return send(res,200,'application/json',JSON.stringify(report(),null,2));
    if (req.method === 'GET' && url.pathname === '/__performance/probe.js') return send(res,200,'text/javascript',fs.readFileSync(path.join(ROOT,'evals/tools/reading-performance-probe.js')));
    if (url.pathname.startsWith('/api/reading/')) {
      // The harness cannot start an index, AI task, change a boundary or confirm a note.
      if (!(req.method === 'GET' || req.method === 'PUT' && url.pathname === `/api/reading/versions/${fixture.versionId}/resume-position`)) return send(res,403,'text/plain','Read/position-only benchmark');
      const upstream = http.request({ hostname:'127.0.0.1', port:fixture.port, path:url.pathname+url.search, method:req.method,
        headers:{ 'Content-Type': req.headers['content-type'] || 'application/json', Accept:req.headers.accept || 'application/json' } }, response => {
        res.writeHead(response.statusCode, response.headers);response.pipe(res);
      });
      upstream.on('error', () => { if (!res.headersSent) send(res,502,'text/plain','Fixture unavailable');else res.destroy(); });
      res.on('close', () => upstream.destroy());req.pipe(upstream);return;
    }
    if (req.method !== 'GET') return send(res,405,'text/plain','GET required');
    if (url.pathname === '/books' || /^\/books\/[^/]+\/read$/.test(url.pathname)) return send(res,200,'text/html; charset=utf-8',html);
    // Only built assets are served; arbitrary paths, source files and local secrets are unavailable.
    if (!/^\/assets\/[\w.-]+$/.test(url.pathname)) return send(res,404,'text/plain','Not found');
    const file=path.join(dist,url.pathname.slice(1));
    if (!fs.existsSync(file)) return send(res,404,'text/plain','Not found');
    const type=file.endsWith('.js')?'text/javascript':file.endsWith('.css')?'text/css':'application/octet-stream';
    res.writeHead(200,{'Content-Type':type,'Cache-Control':'public, max-age=3600'});fs.createReadStream(file).pipe(res);
  });
  server.listen(port,'127.0.0.1',save);
  return server;
}
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [session, rawPort] = process.argv.slice(2);
  if (session === 'report') {
    const report=JSON.parse(fs.readFileSync(rawPort,'utf8').replace(/^\uFEFF/,''));
    console.log(JSON.stringify({browser:summarize(report.samples,report.fixture.fixture.resumeParagraph),
      service:{paragraphP95Ms:p95(report.fixture.paragraphSamplesMs),positionWriteP95Ms:p95(report.fixture.positionWriteSamplesMs)},
      scope:'Offline engineering only; review fixture, cache state, final-state and limitations separately.'},null,2));
  } else {
    const port=Number(rawPort);
    if (!session || !Number.isInteger(port) || port<1024 || port>65535) throw Error('Usage: node reading-performance.mjs SESSION PORT | report REPORT.json');
    startServer(path.resolve(session),port);
  }
}
