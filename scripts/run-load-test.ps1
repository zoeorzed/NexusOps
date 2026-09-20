param(
    [ValidateRange(2, 16)] [int] $AgentThreads = 4,
    [ValidateRange(1, 64)] [int] $QueueCapacity = 8,
    [ValidateRange(200, 3000)] [int] $TimeoutMs = 1000,
    [ValidateRange(1, 100)] [int] $ModelDelayMs = 30,
    [ValidateRange(2, 100)] [int] $SteadyRequests = 40,
    [ValidateRange(1, 50)] [int] $FaultRequests = 12,
    [ValidateRange(1, 32)] [int] $BurstOverflow = 8,
    [string] $OutputPath = 'target/load-test-result.json',
    [string] $MavenExecutable,
    [string] $MavenRepository,
    [switch] $Offline,
    [switch] $AllowExperimentalByteBuddy
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    if ($SteadyRequests -lt $AgentThreads) { throw 'SteadyRequests must be at least AgentThreads.' }
    $maven = if ($MavenExecutable) { $MavenExecutable } elseif ($IsLinux -or $IsMacOS) { './mvnw' } else { '.\mvnw.cmd' }
    $outputFile = if ([IO.Path]::IsPathRooted($OutputPath)) {
        [IO.Path]::GetFullPath($OutputPath)
    } else {
        [IO.Path]::GetFullPath((Join-Path $projectRoot $OutputPath))
    }
    $arguments = @('--batch-mode', '--no-transfer-progress', '-Dtest=AgentLoadBenchmark',
        "-Dload.threads=$AgentThreads", "-Dload.queueCapacity=$QueueCapacity", "-Dload.timeoutMs=$TimeoutMs",
        "-Dload.modelDelayMs=$ModelDelayMs", "-Dload.steadyRequests=$SteadyRequests", "-Dload.faultRequests=$FaultRequests",
        "-Dload.burstOverflow=$BurstOverflow", "-Dload.output=$outputFile")
    if ($MavenRepository) { $arguments += "-Dmaven.repo.local=$MavenRepository" }
    if ($Offline) { $arguments += '--offline' }
    if ($AllowExperimentalByteBuddy) { $arguments += '-Dnet.bytebuddy.experimental=true' }
    & $maven @arguments test
    if ($LASTEXITCODE -ne 0) { throw "Load benchmark failed (exit $LASTEXITCODE)." }
    $result = Get-Content -LiteralPath $outputFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if (-not $result.all_checks_passed) { throw 'Load benchmark invariant checks did not all pass.' }
    Write-Output "Evidence: $outputFile"
    Write-Output 'Scope: in-process orchestration with deterministic model substitutes; no HTTP or external LLM calls.'
}
finally { Pop-Location }
