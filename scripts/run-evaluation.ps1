param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$Dataset = "evaluation/intent-holdout-candidate-v1.json",
    [string]$Output = "",
    [switch]$SaveAsBaseline,
    [switch]$Offline,
    [string]$Jar = "target/echomind-java-0.1.0.jar"
)

$ErrorActionPreference = "Stop"
if (-not $Output) {
    $Output = if ($Offline) { "target/offline-intent-baseline.json" } else { "target/evaluation-report.json" }
}
if ($Offline) {
    if ($SaveAsBaseline) { throw "Offline baseline is a separate artifact; do not overwrite the application pipeline baseline." }
    if (-not (Test-Path -LiteralPath $Jar)) { throw "Package the application first (mvn package); missing JAR: $Jar" }
    & java '-Dloader.main=com.echomind.evaluation.OfflineIntentBaseline' -cp $Jar org.springframework.boot.loader.launch.PropertiesLauncher $Dataset $Output
    if ($LASTEXITCODE -ne 0) { throw "Offline evaluation failed with exit code $LASTEXITCODE" }
    return
}

$datasetObject = Get-Content -Raw -Encoding utf8 -LiteralPath $Dataset | ConvertFrom-Json
$datasetObject | Add-Member -NotePropertyName save_as_baseline -NotePropertyValue ([bool]$SaveAsBaseline) -Force
$body = $datasetObject | ConvertTo-Json -Depth 30
$report = Invoke-RestMethod -Method Post -Uri "$BaseUrl/eval/run" -ContentType "application/json; charset=utf-8" -Body ([Text.Encoding]::UTF8.GetBytes($body))
$outputDirectory = Split-Path -Parent $Output
if ($outputDirectory) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}
$report | ConvertTo-Json -Depth 30 | Set-Content -Encoding utf8 -LiteralPath $Output
$report | Select-Object evaluation_mode, total, passed, pass_rate, avg_scores, intent_failure_count, intent_llm_failure_count, dialog_invalid_count, baseline_comparison, regressions | Format-List
Write-Host "Full report: $Output"
