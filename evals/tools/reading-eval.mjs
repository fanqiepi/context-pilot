import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {execFileSync} from 'node:child_process';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const DATA = 'evals/datasets/reading/acceptance-v1.json';
const CONFIG = 'evals/configs/reading/acceptance-v8.json';
const LOCK = 'evals/configs/reading/acceptance-v8.lock.json';
export const hash = value => createHash('sha256').update(value).digest('hex');
const json = value => JSON.stringify(value, null, 2) + '\n';
const read = file => JSON.parse(fs.readFileSync(path.join(ROOT, file), 'utf8').replace(/^\uFEFF/, ''));
function check(value, message) { if (!value) throw new Error(message); }
function unique(values, label) { check(new Set(values).size === values.length, `Duplicate ${label}`); }
const ratio = (n, d) => ({numerator: n, denominator: d, rate: d ? n / d : null});
const present = value => typeof value === 'string' && value.trim().length > 0;
const finite = value => typeof value === 'number' && Number.isFinite(value) && value >= 0;

export function loadAssets() { return {dataset: read(DATA), config: read(CONFIG)}; }

export function validateAssets(dataset, config, readFile = file => fs.readFileSync(path.join(ROOT, file))) {
  check(dataset.version === (config.datasetVersion ?? config.version), 'Version mismatch');
  unique(dataset.corpora.map(c => c.id), 'corpus');
  unique(dataset.cases.map(c => c.caseId), 'case');
  const families = new Map();
  const texts = new Map();
  for (const corpus of dataset.corpora) {
    check(['DEBUG', 'TEST'].includes(corpus.split), 'Invalid split');
    check(!families.has(corpus.family) || families.get(corpus.family) === corpus.split, 'Source family crosses splits');
    families.set(corpus.family, corpus.split);
    check(present(corpus.provenance.permission), 'Missing provenance');
    const bytes = readFile(corpus.file);
    check(hash(bytes) === corpus.sha256, `Corpus drift: ${corpus.id}`);
    const lines = bytes.toString('utf8').split('\n').filter(Boolean);
    const parsed = [];
    let chapter = 0;
    for (const line of lines) {
      if (/^(第\d+章|Chapter \d+)/.test(line)) chapter++;
      else parsed.push({text: line, chapter});
    }
    check(parsed.length === corpus.paragraphs.length && chapter === corpus.chapterCount, 'Parser layout mismatch');
    corpus.paragraphs.forEach((p, i) => {
      check(p.ordinal === i + 1 && p.id === `${corpus.id}:p${i + 1}`, 'Noncontiguous paragraph');
      check(parsed[i].text === p.text && parsed[i].chapter === p.chapter && hash(p.text) === p.sha256, 'Paragraph drift');
      check(!texts.has(p.sha256) || texts.get(p.sha256) === corpus.split, 'Identical passage crosses splits');
      texts.set(p.sha256, corpus.split);
    });
  }
  const seenRequests = new Set();
  for (const c of dataset.cases) {
    const corpus = dataset.corpora.find(b => b.id === c.corpusId);
    check(corpus && c.split === corpus.split && c.corpusSha256 === corpus.sha256, `Invalid corpus: ${c.caseId}`);
    check(c.book === corpus.family && c.version === corpus.id, 'Book/version mismatch');
    check(Number.isInteger(c.boundary) && c.boundary >= 0 && c.boundary <= corpus.paragraphs.length, 'Invalid boundary');
    check(present(c.input) && ['RECAP', 'CHARACTER_LOOKUP'].includes(c.taskType) && ['DIRECT', 'MULTI_QUERY'].includes(c.mode), 'Invalid input');
    const key = JSON.stringify([c.corpusId, c.boundary, c.input, c.mode, c.chapterCount]);
    check(!seenRequests.has(key), 'Duplicate request');
    seenRequests.add(key);
    unique(c.expectedFacts.map(f => f.id), 'expected fact');
    unique(c.expectedEvidence.map(e => e.paragraphId), 'evidence');
    const evidence = new Set(c.expectedEvidence.map(e => e.paragraphId));
    for (const e of c.expectedEvidence) {
      const p = corpus.paragraphs.find(p => p.id === e.paragraphId);
      check(p && p.ordinal === e.ordinal && p.sha256 === e.sha256 && p.ordinal <= c.boundary, 'Out-of-scope or wrong evidence');
    }
    for (const f of c.expectedFacts) {
      check(present(f.text) && f.evidence.length && f.evidence.every(id => evidence.has(id)), 'Missing expected evidence');
    }
    check(['ANSWER', 'INSUFFICIENT_EVIDENCE'].includes(c.expectedOutcome), 'Invalid expected outcome');
    check((c.expectedOutcome === 'ANSWER') === (c.expectedFacts.length > 0), 'Expected outcome has no facts');
    if (c.taskType === 'RECAP') {
      check([1, 2, 3].includes(c.chapterCount) && c.boundary > 0, 'Invalid recap window');
      const chapter = corpus.paragraphs[c.boundary - 1].chapter;
      const window = corpus.paragraphs.filter(p => p.ordinal <= c.boundary && p.chapter >= chapter - c.chapterCount + 1);
      check(JSON.stringify(window.map(p => p.id)) === JSON.stringify(c.expectedEvidence.map(e => e.paragraphId)), 'Recap omits window evidence');
    }
    for (const f of c.forbiddenFacts) {
      check(present(f.text), 'Empty forbidden fact');
      if (f.reason === 'UNREAD') {
        const p = corpus.paragraphs.find(p => p.id === f.paragraphId);
        check(p && p.ordinal > c.boundary && p.text === f.text, 'Invalid forbidden fact');
      }
    }
  }
  const counts = {};
  for (const c of dataset.cases) counts[`${c.split}/${c.group}`] = (counts[`${c.split}/${c.group}`] ?? 0) + 1;
  check(JSON.stringify(Object.entries(counts).sort()) === JSON.stringify(Object.entries({
    'TEST/RECAP':16, 'TEST/GROUNDED':16, 'TEST/UNANSWERABLE':8, 'TEST/ADVERSARIAL':8,
    'DEBUG/RECAP':4, 'DEBUG/CHARACTER':8,
  }).sort()), 'Frozen group counts mismatch');
  check(dataset.cases.filter(c => c.group === 'GROUNDED' && c.mode === 'DIRECT').length === 8, 'Grounded mode split mismatch');
  check(dataset.cases.filter(c => c.split === 'DEBUG' && c.mode === 'MULTI_QUERY').length === 4, 'Debug mode split mismatch');
  check(config.repetitions.DEBUG === 1 && config.repetitions.TEST === 2, 'Repetitions mismatch');
  return {cases: dataset.cases.length, corpora: dataset.corpora.length, counts};
}

function walk(relative) {
  return fs.readdirSync(path.join(ROOT, relative), {withFileTypes: true}).sort((a,b) => a.name.localeCompare(b.name))
    .flatMap(e => e.isDirectory() ? walk(`${relative}/${e.name}`) : [`${relative}/${e.name}`]);
}
function sourceFiles() {
  return ['book', 'readingstate', 'readingnote', 'readingtask'].flatMap(capability =>
    walk(`backend/src/main/java/io/github/fanqiepi/contextpilot/${capability}`))
    .concat(['backend/pom.xml', 'backend/src/main/resources/application.yml',
      'backend/src/main/resources/db/migration/V29__create_reading_library.sql',
      'backend/src/main/resources/db/migration/V30__create_reading_tasks.sql',
      'backend/src/main/resources/db/migration/V31__create_reading_note_proposals.sql',
      'backend/src/main/resources/db/migration/V32__bind_reading_live_authorization.sql',
      'backend/src/main/resources/db/migration/V33__record_reading_budget_alerts.sql',
      'backend/src/main/resources/db/migration/V34__link_reading_budget_successor.sql',
      'frontend/src/api/reading.ts', 'frontend/src/styles/reading.css',
      'backend/src/test/java/io/github/fanqiepi/contextpilot/book/ReadingLibraryPersistenceTests.java',
      'backend/src/test/java/io/github/fanqiepi/contextpilot/readingnote/ReadingNoteProposalPersistenceTests.java',
      'backend/src/test/java/io/github/fanqiepi/contextpilot/readingtask/ReadingTaskPolicyTests.java',
      'backend/src/test/java/io/github/fanqiepi/contextpilot/readingtask/ReadingLookupCompletenessTests.java',
      'backend/src/test/java/io/github/fanqiepi/contextpilot/readingtask/ReadingTaskPersistenceTests.java',
      'backend/src/test/java/io/github/fanqiepi/contextpilot/readingtask/ReadingProviderProtocolTests.java',
      'backend/src/test/java/io/github/fanqiepi/contextpilot/readingtask/ReadingLiveAuthorizationTests.java',
      ...walk('frontend/src/components').filter(f=>/Reading/.test(f)),
      ...walk('frontend/src/views').filter(f=>/Book/.test(f))]);
}
export function freeze(dataset, config, output) {
  validateAssets(dataset, config);
  const assets = [DATA, CONFIG, config.scoring, config.roadmap, config.evaluation,
    'evals/tools/reading-eval.mjs', 'evals/tools/reading-assets.mjs', 'evals/tools/reading-live.mjs', 'evals/tools/reading-live.test.mjs',
    'evals/results/reading/acceptance-v5/debug-v5.json', 'evals/results/reading/acceptance-v7/debug-v7.json',
    ...walk('evals/corpora/reading/acceptance-v1')];
  const fingerprints = files => Object.fromEntries([...new Set(files)].sort().map(file => [file, hash(fs.readFileSync(path.join(ROOT, file)))]));
  const lock = {version: config.version, datasetVersion: dataset.version, createdAt: new Date().toISOString(),
    gitHead: execFileSync('git', ['rev-parse', 'HEAD'], {cwd:ROOT, encoding:'utf8'}).trim(),
    model: config.generationModel, embedding: {model:config.embeddingModel, dimensions:config.embeddingDimensions},
    promptVersion: config.promptVersion, assetHashes: fingerprints(assets),
    sourceHashes: fingerprints(sourceFiles()), liveReadiness: config.liveReadiness,
    authorization: config.authorization};
  fs.writeFileSync(output, json(lock), {flag:'wx'});
  return lock;
}
export function verifyLock(lock, {source = false} = {}) {
  for (const [file, expected] of Object.entries({...lock.assetHashes, ...(source ? lock.sourceHashes : {})})) {
    check(hash(fs.readFileSync(path.join(ROOT, file))) === expected, `Frozen file changed: ${file}; issue a new version, do not rewrite the lock`);
  }
  return {assets:'MATCH', source:source ? 'MATCH' : 'NOT_CHECKED'};
}
export function template(dataset, config, mode = 'OFFLINE_FIXTURE') {
  check(['OFFLINE_FIXTURE','LIVE_END_TO_END'].includes(mode), 'Invalid mode');
  return {version: config.version, mode, manifestSha256: null, environment: null, bindings: [],
    results: dataset.cases.flatMap(c => Array.from({length: config.repetitions[c.split]}, (_, i) => ({
      caseId:c.caseId, repeat:i + 1, status:'NOT_RUN', observation:null, review:null,
    })))};
}
export const observationHash = observation => hash(JSON.stringify(observation));
export function reviewTemplate(observation) {
  return {observationSha256:observationHash(observation), reviewer:'', reviewedAt:'', rationale:'',
    allClaimsReviewed:false, spoiler:null, fatalError:null, correctRefusal:null, facts:[]};
}
function reviewCounts(c, row) {
  const r = row.review, o = row.observation;
  check(r.observationSha256 === observationHash(o), 'Review does not match observation');
  check(present(r.reviewer) && present(r.rationale) && present(r.reviewedAt) && Number.isFinite(Date.parse(r.reviewedAt)), 'Incomplete reviewer identity');
  check(r.allClaimsReviewed === true && ['spoiler','fatalError','correctRefusal'].every(k => typeof r[k] === 'boolean'), 'Incomplete review');
  check(Array.isArray(r.facts), 'Missing fact review');
  const covered = new Set(), expected = new Set(c.expectedFacts.map(f => f.id));
  let supported = 0, links = 0, supportedLinks = 0, spoiler = r.spoiler;
  for (const f of r.facts) {
    check(present(f.text) && ['supported','unsupported','contradictory','spoiler'].includes(f.verdict), 'Invalid fact verdict');
    if (f.verdict === 'supported') supported++;
    if (f.verdict === 'spoiler') spoiler = true;
    check(Array.isArray(f.citations) && Array.isArray(f.coveredExpectedFactIds), 'Missing fact associations');
    unique(f.citations.map(link => link.id), 'fact citation');
    unique(f.coveredExpectedFactIds, 'covered fact');
    for (const id of f.coveredExpectedFactIds) {
      check(expected.has(id) && f.verdict === 'supported', 'Invalid covered fact');
      covered.add(id);
    }
    links += Math.max(1, f.citations.length);
    for (const link of f.citations) {
      check(o.citations.some(citation => citation.id === link.id), 'Review references absent citation');
      check(['supported','unsupported'].includes(link.verdict), 'Invalid citation verdict');
      if (link.verdict === 'supported') supportedLinks++;
    }
  }
  const usable = c.expectedOutcome === 'ANSWER' && covered.size > 0 && !r.fatalError && !spoiler;
  const refused = c.expectedOutcome === 'INSUFFICIENT_EVIDENCE' && o.terminal === 'INSUFFICIENT_EVIDENCE'
    && r.correctRefusal && !r.fatalError && !spoiler && supported === r.facts.length && links === supportedLinks;
  return {facts:ratio(supported,r.facts.length), citations:ratio(supportedLinks,links),
    recall:ratio(covered.size,c.expectedFacts.length), spoiler, usable, refused};
}
export function report(dataset, config, raw) {
  check(raw.version === config.version && ['OFFLINE_FIXTURE','LIVE_END_TO_END'].includes(raw.mode), 'Run version/mode mismatch');
  const expected = template(dataset, config).results.map(r => `${r.caseId}/${r.repeat}`);
  check(Array.isArray(raw.results), 'Missing results');
  const keys = raw.results.map(r => `${r.caseId}/${r.repeat}`);
  unique(keys, 'run');
  check(keys.length === expected.length && keys.every(k => expected.includes(k)), 'Missing/unknown runs; retain NOT_RUN rows');
  if (raw.mode === 'LIVE_END_TO_END' && raw.results.some(r=>r.status!=='NOT_RUN')) {
    check(raw.environment && ['codeFingerprint','model','embeddingProfile','os','java','database'].every(k=>present(raw.environment[k])), 'Missing live environment');
    check(Array.isArray(raw.bindings), 'Missing live bindings');
    unique(raw.bindings.map(b=>b.corpusId), 'corpus binding');
    for (const c of dataset.corpora.filter(c=>raw.results.some(r=>r.status!=='NOT_RUN' && dataset.cases.find(t=>t.caseId===r.caseId).corpusId===c.id))) {
      const b=raw.bindings.find(b=>b.corpusId===c.id);
      check(b && present(b.bookId) && present(b.versionId) && b.corpusSha256===c.sha256 && Array.isArray(b.paragraphs), 'Missing live corpus binding');
      check(b.paragraphs.length===c.paragraphs.length,'Incomplete live paragraph binding');
      unique(b.paragraphs.map(p=>p.id),'bound paragraph');
      c.paragraphs.forEach((p,i)=>check(b.paragraphs[i].ordinal===p.ordinal && b.paragraphs[i].sha256===p.sha256
        && present(b.paragraphs[i].id),'Invalid live paragraph binding'));
    }
  }
  const rows = raw.results.map(row => {
    check(['NOT_RUN','OBSERVED','FAILED'].includes(row.status), 'Unknown run status');
    const c = dataset.cases.find(c => c.caseId === row.caseId);
    if (row.status === 'NOT_RUN') {
      check(row.observation === null && row.review === null, 'NOT_RUN cannot carry evidence');
      return {...row, c, score:null};
    }
    const o = row.observation;
    check(o && typeof o.answer === 'string' && present(o.terminal) && Array.isArray(o.citations) && Array.isArray(o.calls), 'Incomplete observation');
    if (raw.mode === 'LIVE_END_TO_END') check((present(o.taskId) && present(o.traceId))
      || (row.status==='FAILED' && o.errorCode==='COLLECTOR_OBSERVATION_INCOMPLETE' && o.provenanceStatus==='UNRESOLVED' && present(o.clientRequestId)), 'Missing live task provenance');
    unique(o.citations.map(citation => citation.id), 'observed citation');
    for (const citation of o.citations) check(present(citation.id) && present(citation.text), 'Invalid citation');
    check(o.elapsedMs === null || finite(o.elapsedMs), 'Invalid timing');
    for (const call of o.calls) {
      check(['GENERATION','EMBEDDING'].includes(call.kind) && ['SUCCEEDED','FAILED','UNKNOWN'].includes(call.status), 'Invalid call record');
      for (const k of ['inputTokens','outputTokens','reservedCny','settledCny']) check(call[k] === null || finite(call[k]), 'Invalid usage; unknown must be null');
      if (call.status === 'UNKNOWN') check(call.settledCny === null, 'Unknown calls cannot be settled');
    }
    if (row.status === 'FAILED') {
      check(present(o.errorCode) && row.review === null, 'Technical failure needs error code, not semantic review');
      return {...row, c, score:null};
    }
    check(['COMPLETED','PARTIAL','INSUFFICIENT_EVIDENCE'].includes(o.terminal),'Non-answer terminal must be FAILED');
    return {...row, c, score:row.review ? reviewCounts(c,row) : null};
  });
  const groups = [];
  for (const split of ['DEBUG','TEST']) for (let repeat=1; repeat<=config.repetitions[split]; repeat++)
    for (const taskType of ['RECAP','CHARACTER_LOOKUP']) {
      const group = rows.filter(r => r.c.split === split && r.repeat === repeat && r.c.taskType === taskType);
      const scored = group.filter(r => r.score);
      const metric = name => {
        const measures = scored.map(r => r.score[name]);
        const rated = measures.filter(m => m.rate !== null);
        return {pooled:ratio(measures.reduce((n,m)=>n+m.numerator,0),measures.reduce((n,m)=>n+m.denominator,0)),
          macro: rated.length ? rated.reduce((n,m)=>n+m.rate,0)/rated.length : null,
          rated: rated.length, empty: measures.length-rated.length, unreviewed:group.length-scored.length};
      };
      const answerable = group.filter(r => r.c.expectedOutcome === 'ANSWER');
      const unanswerable = group.filter(r => r.c.expectedOutcome === 'INSUFFICIENT_EVIDENCE');
      const timings = group.filter(r => r.observation?.elapsedMs !== null && finite(r.observation?.elapsedMs)).map(r => r.observation.elapsedMs).sort((a,b)=>a-b);
      groups.push({split, repeat, taskType, total:group.length, observed:group.filter(r=>r.status==='OBSERVED').length,
        failed:group.filter(r=>r.status==='FAILED').length, notRun:group.filter(r=>r.status==='NOT_RUN').length,
        reviewed:scored.length, facts:metric('facts'), citations:metric('citations'),
        usable:ratio(answerable.filter(r=>r.score?.usable).length,answerable.length),
        correctRefusal:ratio(unanswerable.filter(r=>r.score?.refused).length,unanswerable.length),
        recapRecall:taskType === 'RECAP' ? {overall:metric('recall'),
          perCase:group.map(r=>({caseId:r.caseId,status:r.status,recall:r.score?.recall??null}))} : null,
        latency:{samples:timings.length,missing:group.length-timings.length,p95Ms:timings.length ? timings[Math.ceil(timings.length*.95)-1] : null}});
    }
  const indexObservations=raw.indexObservations??[];
  check(Array.isArray(indexObservations),'Invalid index audit');unique(indexObservations.map(i=>i.jobId),'index job audit');
  for(const index of indexObservations) {
    check(present(index.jobId)&&Array.isArray(index.calls),'Incomplete index audit');
    check(index.calls.every(c=>c.kind==='EMBEDDING'&&c.indexJobId===index.jobId),'Invalid index attribution');
    if(index.attributedTo) {
      const attributed=rows.find(r=>`${r.caseId}/${r.repeat}`===index.attributedTo);
      check(attributed?.observation&&JSON.stringify(attributed.observation.calls.filter(c=>c.indexJobId===index.jobId))===JSON.stringify(index.calls),'Index audit attribution mismatch');
    }
  }
  const orphanIndexCalls=indexObservations.filter(i=>!i.attributedTo).flatMap(i=>i.calls);
  const calls = [...rows.flatMap(r=>r.observation?.calls ?? []),...orphanIndexCalls];
  for(const call of calls) {
    check(['GENERATION','EMBEDDING'].includes(call.kind)&&['SUCCEEDED','FAILED','UNKNOWN'].includes(call.status),'Invalid call audit');
    for(const k of ['inputTokens','outputTokens','reservedCny','settledCny'])check(call[k]===null||finite(call[k]),'Invalid audit amount');
    if(call.status==='UNKNOWN')check(call.settledCny===null,'Unknown index calls cannot be settled');
  }
  const usage = Object.fromEntries(['inputTokens','outputTokens','reservedCny','settledCny'].map(k => [k,{
    knownSubtotal:calls.reduce((n,c)=>n+(c[k]??0),0),unknownCalls:calls.filter(c=>c[k]===null).length}]));
  const spoilers = rows.filter(r=>r.score?.spoiler).map(r=>`${r.caseId}/${r.repeat}`);
  const latencyByMode = [];
  for(const split of ['DEBUG','TEST']) for(let repeat=1;repeat<=config.repetitions[split];repeat++)
    for(const mode of ['RECAP','DIRECT','MULTI_QUERY']) {
      const group=rows.filter(r=>r.c.split===split && r.repeat===repeat && (r.c.taskType==='RECAP'?'RECAP':r.c.mode)===mode);
      const timings=group.map(r=>r.observation?.elapsedMs).filter(finite).sort((a,b)=>a-b);
      latencyByMode.push({split,repeat,mode,planned:group.length,samples:timings.length,
        missing:group.length-timings.length,technicalFailures:group.filter(r=>r.status==='FAILED').length,
        p95Ms:timings.length?timings[Math.ceil(timings.length*.95)-1]:null});
    }
  return {version:raw.version, mode:raw.mode, total:rows.length, groups,
    latencyByMode,
    reviewStatus:spoilers.length ? 'BLOCKED_SPOILER' : rows.every(r=>r.score) ? 'REVIEW_COMPLETE' : 'INCOMPLETE',
    unresolvedAttempts:rows.filter(r=>r.observation?.provenanceStatus==='UNRESOLVED').length,
    unassignedIndexCalls:orphanIndexCalls.length,
    spoilers, calls:{total:calls.length,generation:calls.filter(c=>c.kind==='GENERATION').length,
      embedding:calls.filter(c=>c.kind==='EMBEDDING').length,unknown:calls.filter(c=>c.status==='UNKNOWN').length,usage},
    acceptance:'NOT_DETERMINED',
    limitations:['Manual annotations are not independently verified by this tool.',
      'OFFLINE_FIXTURE is never real-model semantic evidence. Terminal matches are not quality scores.',
      'No automatic acceptance: compare the active roadmap and retain reader, protocol, budget and performance gates.',
      'Known cost subtotals exclude unknown usage; they are not a final bill.']};
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const [command, arg, extra] = process.argv.slice(2), {dataset, config} = loadAssets();
  validateAssets(dataset, config);
  if (command === 'freeze') {
    check(arg, 'freeze requires a new output path');
    freeze(dataset,config,arg);
    console.log('Frozen to ' + arg);
  } else {
    const lock = read(LOCK);
    verifyLock(lock);
    if (command === 'validate') console.log(json({...validateAssets(dataset,config), lock:verifyLock(lock,{source:true})}));
    else if (command === 'template') {
      const result = template(dataset,config,arg);
      result.manifestSha256 = hash(fs.readFileSync(path.join(ROOT,LOCK)));
      console.log(json(result));
    } else if (command === 'report') {
      check(arg, 'report requires raw JSON path');
      const raw = JSON.parse(fs.readFileSync(arg,'utf8').replace(/^\uFEFF/,''));
      check(raw.manifestSha256 === hash(fs.readFileSync(path.join(ROOT,LOCK))), 'Run manifest mismatch');
      const result = report(dataset,config,raw);
      result.rawSha256 = hash(fs.readFileSync(arg));
      if (extra) fs.writeFileSync(extra,json(result),{flag:'wx'});
      else console.log(json(result));
    } else throw new Error('Usage: reading-eval.mjs validate | freeze <new-lock> | template [OFFLINE_FIXTURE|LIVE_END_TO_END] | report <raw.json> [new-report.json]');
  }
}
