param([ValidateRange(1024,65535)][int]$Port = 5214)
$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$backend = Join-Path $repository 'backend'
$frontend = Join-Path $repository 'frontend'
if (!$env:JAVA_HOME -or !(Test-Path (Join-Path $env:JAVA_HOME 'release')) -or !((Get-Content (Join-Path $env:JAVA_HOME 'release')) -match '^JAVA_VERSION="21\.')) {
    $env:JAVA_HOME = 'F:\jdk21weizhi\microsoft-jdk-21.0.3-windows-x64\jdk-21.0.3+9'
}
if (!(Test-Path (Join-Path $env:JAVA_HOME 'bin/java.exe'))) { throw '请设置 JAVA_HOME 为 JDK 21。' }
if (!(Test-Path (Join-Path $frontend 'node_modules/vite/bin/vite.js'))) { throw '请先在 frontend 运行 npm ci。' }
& node.exe (Join-Path $repository 'evals/tools/reading-eval.mjs') validate | Out-Null
if ($LASTEXITCODE -ne 0) { throw '当前实现或资产与验收锁不匹配；请先核对版本，不能用旧锁标记新实现。' }
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "端口 $Port 已占用。" }
& docker info --format '{{.ServerVersion}}'
if ($LASTEXITCODE -ne 0) { throw '请先启动 Docker。' }
$session = Join-Path $backend ('target/reading-performance-' + [Guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $session | Out-Null
$runner = Join-Path $session 'backend.ps1'
@'
param([string]$Backend,[string]$Session)
$env:READING_LIVE_ENABLED = 'false'
$env:READING_LIVE_PLAN = ''
$env:READING_LIVE_APPROVAL = ''
Set-Location -LiteralPath $Backend
& .\mvnw.cmd --batch-mode --no-transfer-progress '-Dtest=ReadingPerformanceFixtureTests' '-Dreading.performance=true' "-Dreading.performance.directory=$Session" '-Dcontextpilot.reading.live-enabled=false' test
exit $LASTEXITCODE
'@ | Set-Content -LiteralPath $runner -Encoding utf8
$quote = { param($value) '"' + $value + '"' }
$backendProcess = $null
$frontendProcess = $null
$done = Join-Path $session 'done'
try {
    $backendProcess = Start-Process -FilePath (Get-Process -Id $PID).Path -WindowStyle Hidden -PassThru -ArgumentList @('-NoProfile','-File',(& $quote $runner),'-Backend',(& $quote $backend),'-Session',(& $quote $session)) -RedirectStandardOutput (Join-Path $session 'backend.log') -RedirectStandardError (Join-Path $session 'backend-error.log')
    Write-Host "正在导入隔离长篇样本：$session"
    $until = [DateTime]::UtcNow.AddMinutes(4)
    while (!(Test-Path (Join-Path $session 'fixture.json'))) {
        if ($backendProcess.HasExited -or [DateTime]::UtcNow -gt $until) { throw "夹具启动失败或超时，见 $session/backend.log" }
        Start-Sleep -Milliseconds 500
    }
    Push-Location -LiteralPath $frontend
    try { & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw '前端构建失败。' } } finally { Pop-Location }
    $frontendProcess = Start-Process -FilePath (Get-Command node.exe).Source -WindowStyle Hidden -PassThru -WorkingDirectory $repository -ArgumentList @('evals/tools/reading-performance.mjs',(& $quote $session),"$Port") -RedirectStandardOutput (Join-Path $session 'frontend.log') -RedirectStandardError (Join-Path $session 'frontend-error.log')
    $until = [DateTime]::UtcNow.AddSeconds(15)
    while (!(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue | Where-Object OwningProcess -eq $frontendProcess.Id)) {
        if ($frontendProcess.HasExited -or [DateTime]::UtcNow -gt $until) { throw '性能采集服务未启动，检查日志。' }
        Start-Sleep -Milliseconds 250
    }
    Write-Host "性能入口：http://127.0.0.1:$Port/books"
    Write-Host "采集报告：http://127.0.0.1:$Port/__performance/report"
    Write-Host '桌面和窄屏分别执行 30 次：继续阅读 → 等待右下角计时完成 → 书架。首次导航单独记录，不自动认定操作系统冷启动。'
    Write-Host "结束：Set-Content -LiteralPath '$done' -Value done"
    while (!(Test-Path $done) -and !$backendProcess.HasExited -and !$frontendProcess.HasExited) { Start-Sleep -Milliseconds 500 }
    if (!(Test-Path $done)) { throw '性能会话提前退出，请检查日志。' }
} finally {
    Set-Content -LiteralPath $done -Value 'done'
    if ($frontendProcess -and !$frontendProcess.HasExited) { Stop-Process -Id $frontendProcess.Id }
    if ($backendProcess -and !$backendProcess.HasExited -and !$backendProcess.WaitForExit(20000)) { Write-Warning '后端退出超时；完成信号已写入，请检查日志。' }
}
if ($backendProcess -and $backendProcess.HasExited -and $backendProcess.ExitCode -ne 0) { throw '性能夹具检查未通过，见 backend.log。' }
Write-Host "原始测量与最终位置保留在 $session。未采齐或失败时报告不宣称通过。"
