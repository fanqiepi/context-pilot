param([ValidateRange(1024,65535)][int]$Port = 5212)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$backend = Join-Path $repository 'backend'
$frontend = Join-Path $repository 'frontend'
$java21 = 'F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9'
if (!$env:JAVA_HOME -or !(Test-Path (Join-Path $env:JAVA_HOME 'release')) -or !((Get-Content (Join-Path $env:JAVA_HOME 'release')) -match '^JAVA_VERSION="21\.')) { $env:JAVA_HOME = $java21 }
if (!(Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { throw '请设置 JAVA_HOME 为 JDK 21 路径。' }
if (!(Test-Path (Join-Path $frontend 'node_modules/vite/bin/vite.js'))) { throw '请先在 frontend 运行 npm ci。' }
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "端口 $Port 已占用，请使用 -Port 选择其他端口。" }
& docker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw '请先启动 Docker。' }
$session = Join-Path $backend ('target/reading-demo-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $session -Force | Out-Null
$runner = Join-Path $session 'backend.ps1'
@'
param([string]$Backend,[string]$Session)
$ErrorActionPreference = 'Stop'
$env:READING_LIVE_ENABLED = 'false'
$env:READING_LIVE_PLAN = ''
$env:READING_LIVE_APPROVAL = ''
Set-Location -LiteralPath $Backend
& .\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=ReadingNoteProposalPersistenceTests#offlineBrowserFixture' '-Dreading.note.ui=true' "-Dreading.note.ui.directory=$Session" '-Dcontextpilot.reading.live-enabled=false' test
exit $LASTEXITCODE
'@ | Set-Content -LiteralPath $runner -Encoding utf8
$quote = { param($value) '"' + $value + '"' }
$shell = (Get-Process -Id $PID).Path
$backendProcess = $null
$frontendProcess = $null
$previousProxyTarget = $env:VITE_API_PROXY_TARGET
$done = Join-Path $session 'reading-note-ui.done'
try {
    $backendProcess = Start-Process -FilePath $shell -WindowStyle Hidden -PassThru -ArgumentList @('-NoProfile','-File',(& $quote $runner),'-Backend',(& $quote $backend),'-Session',(& $quote $session)) -RedirectStandardOutput (Join-Path $session 'backend.log') -RedirectStandardError (Join-Path $session 'backend-error.log')
    Write-Host "正在准备独立的离线演示。日志：$session"
    $metadata = Join-Path $session 'reading-note-ui.json'
    $until = [DateTime]::UtcNow.AddMinutes(3)
    while (!(Test-Path $metadata)) {
        if ($backendProcess.HasExited) { throw "演示后端启动失败，请查看 $session/backend.log" }
        if ([DateTime]::UtcNow -gt $until) { throw '演示后端启动超时，请查看日志。' }
        Start-Sleep -Milliseconds 500
    }
    $fixture = Get-Content -LiteralPath $metadata -Raw | ConvertFrom-Json
    $env:VITE_API_PROXY_TARGET = 'http://127.0.0.1:' + $fixture.port
    Push-Location -LiteralPath $frontend
    try { & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw '前端构建失败。' } } finally { Pop-Location }
    $node = (Get-Command node.exe).Source
    $frontendProcess = Start-Process -FilePath $node -WindowStyle Hidden -PassThru -WorkingDirectory $frontend -ArgumentList @('node_modules/vite/bin/vite.js','preview','--host','127.0.0.1','--port',"$Port",'--strictPort') -RedirectStandardOutput (Join-Path $session 'frontend.log') -RedirectStandardError (Join-Path $session 'frontend-error.log')
    $frontendReady = [DateTime]::UtcNow.AddSeconds(15)
    while (!(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Where-Object OwningProcess -eq $frontendProcess.Id)) {
        if ($frontendProcess.HasExited -or [DateTime]::UtcNow -gt $frontendReady) { throw "演示前端未启动，请查看 $session/frontend-error.log" }
        Start-Sleep -Milliseconds 250
    }
    Write-Host "演示地址：http://127.0.0.1:$Port/books"
    Write-Host '固定离线结果；不会调用供应商。关闭后演示数据库自动回收，不修改日常数据。'
    Write-Host "结束演示：在另一个 PowerShell 执行 Set-Content -LiteralPath '$done' -Value done，或在此按 Ctrl+C。"
    while (!(Test-Path $done) -and !$backendProcess.HasExited -and !$frontendProcess.HasExited) { Start-Sleep -Milliseconds 500 }
    if (!(Test-Path $done)) { throw "演示进程提前退出，请查看 $session 中的日志。" }
} finally {
    $env:VITE_API_PROXY_TARGET = $previousProxyTarget
    Set-Content -LiteralPath $done -Value 'done'
    if ($frontendProcess -and !$frontendProcess.HasExited) { Stop-Process -Id $frontendProcess.Id }
    if ($backendProcess -and !$backendProcess.HasExited -and !$backendProcess.WaitForExit(15000)) {
        Write-Warning '后端尚未退出；完成信号已写入，请保留日志并检查该演示进程。'
    }
}
