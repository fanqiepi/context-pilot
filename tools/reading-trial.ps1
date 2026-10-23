param([ValidateSet('Start','Stop','Status')][string]$Action = 'Start')
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$backend = Join-Path $repository 'backend'
$session = Join-Path $repository 'data/reading-trial/runtime'
$approval = Join-Path $repository 'data/reading-trial/approval.json'
$done = Join-Path $session 'stop'
$metadata = Join-Path $session 'processes.json'
if ($Action -eq 'Stop') {
    if (Test-Path $session) { Set-Content -LiteralPath $done -Value stop }
    Write-Host '已请求停止试读服务；数据库、正文和费用账本保留。'
    exit
}
if ($Action -eq 'Status') {
    Invoke-RestMethod 'http://127.0.0.1:18081/api/reading/evaluation/status' | ConvertTo-Json -Depth 8
    exit
}
if (!(Test-Path $approval)) { throw '缺少独立试读批准文件 data/reading-trial/approval.json；启动脚本不会创建或续期授权。' }
$approved = Get-Content -LiteralPath $approval -Raw | ConvertFrom-Json
if ($approved.status -ne 'APPROVED' -or [DateTimeOffset]::Parse($approved.expiresAt) -le [DateTimeOffset]::UtcNow) { throw '试读授权未批准或已过期。' }
foreach ($port in @(18081,5201)) {
    if (Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue) { throw "端口 $port 已占用；不会停止既有进程。" }
}
if (!(Test-Path (Join-Path $backend 'config/application-local-secrets.yml'))) { throw '请先配置本地 application-local-secrets.yml 中的两个 API Key。' }
$java = 'F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9\bin\java.exe'
if (!(Test-Path $java)) { throw '请将脚本中的 Java 路径设置为本机 JDK 21。' }
$jar = Join-Path $backend 'target/contextpilot-backend-0.0.1-SNAPSHOT.jar'
if (!(Test-Path $jar)) { throw '请先在 backend 执行 .\mvnw.cmd package。' }
& node (Join-Path $repository 'evals/tools/reading-eval.mjs') validate --source
if ($LASTEXITCODE -ne 0) { throw '实现锁校验失败，不能使用当前试读授权。' }
& docker compose -p contextpilot-reader-trial -f (Join-Path $repository 'deploy/reading-trial.compose.yml') up -d --wait
if ($LASTEXITCODE -ne 0) { throw '试读数据库启动失败。' }
New-Item -ItemType Directory -Path $session -Force | Out-Null
if (Test-Path $done) { Remove-Item -LiteralPath $done }
$backendProcess = $null
$frontendProcess = $null
$oldProxy = $env:VITE_API_PROXY_TARGET
try {
    # Explicit arguments keep unrelated local-secrets AI switches and daily data out of the trial.
    $arguments = @('-jar', ('"' + $jar + '"'), '--spring.profiles.active=local-secrets', '--server.address=127.0.0.1', '--server.port=18081',
        '--spring.datasource.url=jdbc:postgresql://127.0.0.1:15434/context_pilot_reader_trial', '--spring.datasource.username=context_pilot',
        '--spring.datasource.password=reader_trial_local_only', '--spring.ai.model.chat=none', '--spring.ai.model.embedding=none', '--spring.ai.vectorstore.type=none',
        '--contextpilot.document.processing.enabled=false', '--contextpilot.task-workspace.enabled=false', '--contextpilot.upgrade-research.enabled=false',
        '--contextpilot.reading.ai-enabled=false', '--contextpilot.reading.live-enabled=false', '--contextpilot.reading.trial-enabled=true',
        ('"--contextpilot.reading.live-repository=' + $repository + '"'), ('"--contextpilot.reading.trial-approval=' + $approval + '"'),
        ('"--contextpilot.storage.root=' + (Join-Path $repository 'data/reading-trial/uploads') + '"'))
    $backendProcess = Start-Process -FilePath $java -WindowStyle Hidden -PassThru -WorkingDirectory $backend -ArgumentList $arguments -RedirectStandardOutput (Join-Path $session 'backend.log') -RedirectStandardError (Join-Path $session 'backend-error.log')
    $until = [DateTime]::UtcNow.AddMinutes(2)
    do {
        if ($backendProcess.HasExited -or [DateTime]::UtcNow -gt $until) { throw "试读后端未启动，请查看 $session/backend.log" }
        Start-Sleep -Milliseconds 500
        try { $health = Invoke-RestMethod 'http://127.0.0.1:18081/actuator/health' } catch { $health = $null }
    } until ($health.status -eq 'UP')
    $status = Invoke-RestMethod 'http://127.0.0.1:18081/api/reading/evaluation/status'
    if (!$status.liveAllowed -or $status.executionScope -ne 'READER_TRIAL_LOCAL') { throw "试读未就绪：$($status.reason)" }
    $env:VITE_API_PROXY_TARGET = 'http://127.0.0.1:18081'
    $frontend = Join-Path $repository 'frontend'
    $frontendProcess = Start-Process -FilePath (Get-Command node.exe).Source -WindowStyle Hidden -PassThru -WorkingDirectory $frontend -ArgumentList @('node_modules/vite/bin/vite.js','--host','127.0.0.1','--port','5201','--strictPort') -RedirectStandardOutput (Join-Path $session 'frontend.log') -RedirectStandardError (Join-Path $session 'frontend-error.log')
    $until = [DateTime]::UtcNow.AddSeconds(20)
    while (!(Get-NetTCPConnection -LocalPort 5201 -State Listen -ErrorAction SilentlyContinue | Where-Object OwningProcess -eq $frontendProcess.Id)) {
        if ($frontendProcess.HasExited -or [DateTime]::UtcNow -gt $until) { throw '试读前端启动失败。' }
        Start-Sleep -Milliseconds 250
    }
    @{ backendPid=$backendProcess.Id; frontendPid=$frontendProcess.Id; url='http://127.0.0.1:5201/books'; expiresAt=$status.expiresAt } | ConvertTo-Json | Set-Content -LiteralPath $metadata -Encoding utf8
    Write-Host "真人试读已就绪：http://127.0.0.1:5201/books；授权截至 $($status.expiresAt)"
    Write-Host '首次使用请导入小说、确认已读边界。只有人工确认任务才调用模型。'
    Write-Host '结束前等待任务完成，再运行 .\tools\reading-trial.ps1 Stop。重启使用原账本，不重置预算。'
    while (!(Test-Path $done) -and !$backendProcess.HasExited -and !$frontendProcess.HasExited) { Start-Sleep -Milliseconds 500 }
} finally {
    $env:VITE_API_PROXY_TARGET = $oldProxy
    if ($frontendProcess -and !$frontendProcess.HasExited) { Stop-Process -Id $frontendProcess.Id }
    if ($backendProcess -and !$backendProcess.HasExited) { Stop-Process -Id $backendProcess.Id }
    # Intentionally keep the persistent database and files. Never reset ledgers or delete the Docker volume.
}
