param([ValidateRange(1024,65535)][int]$Port = 8091)
$ErrorActionPreference = 'Stop'
if (-not $env:JAVA_HOME) { throw 'Set JAVA_HOME to JDK 21 before starting the project source service.' }
$javaExecutable = Join-Path $env:JAVA_HOME 'bin/java.exe'
if (-not (Test-Path -LiteralPath $javaExecutable)) { throw 'JAVA_HOME does not contain bin/java.exe.' }
Push-Location $PSScriptRoot
try {
    & .\mvnw.cmd compile dependency:build-classpath '-Dmdep.outputFile=target/project-source-classpath.txt' -q
    if ($LASTEXITCODE -ne 0) { throw 'Project source service build failed.' }
    $dependencyClasspath = (Get-Content -Raw -LiteralPath 'target/project-source-classpath.txt').Trim()
    $env:PROJECT_SOURCE_MCP_PORT = "$Port"
    & $javaExecutable -cp "target/classes;$dependencyClasspath" io.github.fanqiepi.contextpilot.projectsource.ProjectSourceMcpServer
    if ($LASTEXITCODE -ne 0) { throw 'Project source service exited with an error.' }
} finally { Pop-Location }
