param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 65535)] [int] $RedisPort,
    [string] $MavenExecutable,
    [string] $MavenRepository,
    [switch] $Offline,
    [switch] $AllowExperimentalByteBuddy
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot
try {
    $maven = if ($MavenExecutable) { $MavenExecutable } elseif ($IsLinux -or $IsMacOS) { './mvnw' } else { '.\mvnw.cmd' }
    $arguments = @('--batch-mode', '--no-transfer-progress', "-Dredis.test.port=$RedisPort")
    if ($MavenRepository) { $arguments += "-Dmaven.repo.local=$MavenRepository" }
    if ($Offline) { $arguments += '--offline' }
    if ($AllowExperimentalByteBuddy) { $arguments += '-Dnet.bytebuddy.experimental=true' }
    & $maven @arguments clean verify
    if ($LASTEXITCODE -ne 0) { throw "Maven verification failed (exit $LASTEXITCODE)." }

    $reports = @(Get-ChildItem -LiteralPath 'target/surefire-reports' -Filter 'TEST-*.xml')
    if ($reports.Count -eq 0) { throw 'No JUnit reports were generated.' }
    $suites = @($reports | ForEach-Object {
        [xml] $report = Get-Content -LiteralPath $_.FullName -Raw
        [ordered]@{
            name = [string] $report.testsuite.name
            tests = [int] $report.testsuite.tests
            failures = [int] $report.testsuite.failures
            errors = [int] $report.testsuite.errors
            skipped = [int] $report.testsuite.skipped
        }
    })
    $redisSuite = @($suites | Where-Object { $_.name -eq 'com.echomind.memory.MemoryManagerRedisTest' })
    if ($redisSuite.Count -ne 1 -or $redisSuite[0].tests -lt 4 -or $redisSuite[0].skipped -ne 0) {
        throw 'Required Redis integration tests did not all execute.'
    }
    $totals = [ordered]@{}
    foreach ($field in @('tests', 'failures', 'errors', 'skipped')) {
        $totals[$field] = [int](($suites | ForEach-Object { $_[$field] } | Measure-Object -Sum).Sum)
    }
    if ($totals.failures -gt 0 -or $totals.errors -gt 0 -or $totals.skipped -gt 0) {
        throw 'Verification requires zero failures, errors and skipped tests.'
    }
    $jar = Get-Item -LiteralPath 'target/echomind-java-0.1.0.jar'
    $summary = [ordered]@{
        generated_at_utc = [DateTime]::UtcNow.ToString('o')
        scope = 'Automated tests with real loopback Redis and deterministic model stubs; no live LLM quality claim.'
        redis_port = $RedisPort
        experimental_byte_buddy = [bool]$AllowExperimentalByteBuddy
        totals = $totals
        suites = $suites
        jar_sha256 = (Get-FileHash -LiteralPath $jar.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
    $summary | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath 'target/verification-summary.json' -Encoding utf8
    Write-Output ($totals | ConvertTo-Json -Compress)
    Write-Output 'Evidence: target/verification-summary.json and target/surefire-reports/'
}
finally { Pop-Location }
