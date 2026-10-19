$ErrorActionPreference = 'Stop'
$base = 'http://127.0.0.1:18089'
$evidence = [ordered]@{}
function PostJson($path, $body) {
  Invoke-RestMethod "$base$path" -Method Post -ContentType 'application/json' -Body ($body | ConvertTo-Json -Depth 10)
}
try {
  $evidence.initialKnowledgeBases = (Invoke-RestMethod "$base/api/knowledge-bases")
  if ($evidence.initialKnowledgeBases.Count -ne 0) { throw 'Database is not empty' }
  $kb = PostJson '/api/knowledge-bases' @{name='Clean replay public sample'; description='Disposable offline smoke verification'}
  $evidence.knowledgeBase = $kb
  $greeting = PostJson '/api/chat' @{knowledgeBaseId=$kb.id; question='你好'}
  $evidence.greeting = $greeting
  if ($greeting.capabilityId -ne 'SIMPLE_CHAT' -or $greeting.refused) { throw 'Greeting mismatch' }
  $refusal = PostJson '/api/chat' @{knowledgeBaseId=$kb.id; question='Solstice 项目的部署要求是什么？'}
  $evidence.emptyKnowledgeBaseRefusal = $refusal
  if (-not $refusal.refused) { throw 'Missing refusal' }
  $evidence.history = (Invoke-RestMethod "$base/api/conversations/$($refusal.conversationId)/messages")
  $proposal = PostJson '/api/chat' @{knowledgeBaseId=$kb.id; question='创建知识库：Clean replay confirmed'}
  $evidence.proposal = $proposal
  if ($proposal.actionRequest.status -ne 'PENDING_CONFIRMATION') { throw 'Proposal is not pending' }
  $before = (Invoke-RestMethod "$base/api/knowledge-bases")
  if ($before.Count -ne 1) { throw 'Action executed before confirmation' }
  $evidence.confirmed = PostJson "/api/action-requests/$($proposal.actionRequest.id)/confirm" @{}
  $evidence.repeatedConfirmation = PostJson "/api/action-requests/$($proposal.actionRequest.id)/confirm" @{}
  $after = (Invoke-RestMethod "$base/api/knowledge-bases")
  if ($after.Count -ne 2) { throw 'Confirmation is not idempotent' }
  'Solstice is a public fictional project. Its deployment requires Java 21. Its scheduled maintenance window is Sunday 02:00 UTC.' | Set-Content "$PSScriptRoot/public-sample.txt" -Encoding utf8
  $doc = Invoke-RestMethod "$base/api/knowledge-bases/$($kb.id)/documents" -Method Post -Form @{file=Get-Item "$PSScriptRoot/public-sample.txt"}
  for ($attempt=0; $attempt -lt 45; $attempt++) {
    $doc = Invoke-RestMethod "$base/api/documents/$($doc.id)"
    if ($doc.status -in @('SUCCEEDED','FAILED')) { break }
    Start-Sleep -Seconds 1
  }
  $evidence.document = $doc
  if ($doc.status -ne 'SUCCEEDED' -or $doc.embeddingIndexCompatibility -ne 'CURRENT') { throw 'Document ingestion failed' }
  $sse = Invoke-WebRequest "$base/api/chat/stream" -Method Post -ContentType 'application/json' -Body (@{knowledgeBaseId=$kb.id; question='你好'} | ConvertTo-Json)
  $evidence.sse = $sse.Content
  if ($sse.Content -notmatch 'event:done') { throw 'SSE did not finish' }
  Invoke-RestMethod "$base/api/documents/$($doc.id)" -Method Delete | Out-Null
  $remaining = (Invoke-RestMethod "$base/api/knowledge-bases/$($kb.id)/documents")
  if ($remaining.Count -ne 0) { throw 'Deleted document still listed' }
  $evidence.result = 'PASSED'
} catch {
  $evidence.result = 'FAILED'
  $evidence.error = $_.Exception.Message
  throw
} finally {
  $evidence | ConvertTo-Json -Depth 30 | Set-Content "$PSScriptRoot/smoke-results.json" -Encoding utf8
}
