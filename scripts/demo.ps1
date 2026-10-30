<#
.SYNOPSIS
  End-to-end demo of the SDLC orchestrator through its REST API (Windows PowerShell 5.1+ / PowerShell 7).

.DESCRIPTION
  Starts a catalog scenario, drives it to completion by approving gates and answering clarification
  questions as a human operator would, then prints the audit trail, artifacts and reliability metrics.
  Prerequisite: the orchestrator is running (.\mvnw.cmd -pl orchestrator-app spring-boot:run).

.EXAMPLE
  .\scripts\demo.ps1
  .\scripts\demo.ps1 -Scenario ambiguous
  .\scripts\demo.ps1 -Scenario brownfield -Provider anthropic
#>
param(
  [string]$Scenario = "greenfield",
  [string]$BaseUrl = $(if ($env:ORCHESTRATOR_URL) { $env:ORCHESTRATOR_URL } else { "http://localhost:8090" }),
  [string]$Operator = "demo-operator",
  [string]$Provider = "",
  [int]$TimeoutSeconds = 300
)

$ErrorActionPreference = "Stop"
$headers = @{ "X-Operator-Id" = $Operator }
# When the control plane runs in api-key mode, define SDLC_API_KEY (or SDLC_SECURITY_API_KEY, the variable
# docker-compose.yml passes to the control plane) to authenticate.
$apiKey = if ($env:SDLC_API_KEY) { $env:SDLC_API_KEY } else { $env:SDLC_SECURITY_API_KEY }
if ($apiKey) { $headers["X-Api-Key"] = $apiKey }

function Invoke-Api {
  param([string]$Method, [string]$Path, $Body = $null)
  $uri = "$BaseUrl$Path"
  if ($null -ne $Body) {
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType "application/json" -Body ($Body | ConvertTo-Json -Depth 10 -Compress)
  }
  return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers
}

function Get-AnswerFor([string]$question) {
  $q = $question.ToLowerInvariant()
  if ($q -match "faster|latency|operations") { return "Redirect endpoint: p95 latency under 30 ms at 500 requests per second; create endpoint under 100 ms." }
  if ($q -match "reliab|availab") { return "Availability 99.9 percent monthly, error rate below 0.1 percent, no data loss on restart." }
  return "Scope is the redirect and create endpoints of the existing URL shortener; no infrastructure changes beyond caching."
}

Write-Host "== SDLC Orchestrator demo =="
Write-Host "server:   $BaseUrl"
Write-Host "scenario: $Scenario"
Write-Host "operator: $Operator"
if ($Provider) { Write-Host "provider: $Provider" }
Write-Host ""

Write-Host "-- health"
$health = Invoke-RestMethod -Uri "$BaseUrl/actuator/health"
Write-Host "  status: $($health.status)"

Write-Host "-- scenario"
$scenarios = Invoke-Api GET "/api/v1/scenarios"
$chosen = $scenarios | Where-Object { $_.key -eq $Scenario }
if ($null -eq $chosen) { throw "Unknown scenario '$Scenario'. Available: $(($scenarios | ForEach-Object { $_.key }) -join ', ')" }
Write-Host "  $($chosen.title)"
Write-Host "  $($chosen.requirement)"

Write-Host "-- starting run"
$query = if ($Provider) { "?provider=$Provider" } else { "" }
$run = Invoke-Api POST "/api/v1/scenarios/$Scenario/run$query" @{}
$runId = $run.id
Write-Host "  run id: $runId"
Write-Host ""

$started = Get-Date
$lastLine = ""
$view = $null
while ($true) {
  $view = Invoke-Api GET "/api/v1/runs/$runId"
  $summary = ($view.stages | ForEach-Object { "$($_.id)=$($_.status)" }) -join " "
  $line = "[$($view.status)] $summary"
  if ($line -ne $lastLine) { Write-Host $line; $lastLine = $line }

  if ($view.status -in @("COMPLETED", "ABORTED", "SAFE_STOPPED")) { break }

  if ($view.status -eq "WAITING_HUMAN") {
    foreach ($action in @($view.pendingActions)) {
      if ($action.type -eq "APPROVAL") {
        Write-Host "  -> approving gate at stage '$($action.stageId)' as $Operator (demo auto-approval; a real operator reviews the artifact first)"
        Invoke-Api POST "/api/v1/runs/$runId/stages/$($action.stageId)/approve" @{ comment = "Auto-approved by scripts/demo.ps1 for demonstration purposes; not a human review" } | Out-Null
      }
      elseif ($action.type -eq "CLARIFICATION") {
        $answers = @{}
        foreach ($q in @($action.questions)) {
          $a = Get-AnswerFor $q.question
          Write-Host "  -> clarifying $($q.id): $a"
          $answers[$q.id] = $a
        }
        Invoke-Api POST "/api/v1/runs/$runId/clarify" @{ answers = $answers } | Out-Null
      }
    }
  }

  if (((Get-Date) - $started).TotalSeconds -gt $TimeoutSeconds) { Write-Host "timed out after ${TimeoutSeconds}s"; break }
  Start-Sleep -Seconds 1
}

Write-Host ""
Write-Host "-- final state: $($view.status)"
Write-Host "  halt reason: $($view.haltReason)"
Write-Host "  plan version: $($view.planVersion)"
Write-Host "  counters: $(($view.counters.PSObject.Properties | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ', ')"
Write-Host "  timing: $(($view.timing.PSObject.Properties | Where-Object { $null -ne $_.Value } | ForEach-Object { "$($_.Name)=$($_.Value)" }) -join ', ')"

Write-Host ""
Write-Host "-- audit trail (last 15 events)"
$audit = Invoke-Api GET "/api/v1/runs/$runId/audit"
$audit | Select-Object -Last 15 | ForEach-Object {
  $stage = if ($_.stageId) { $_.stageId } else { "-" }
  Write-Host ("  {0,4}  {1}  {2,-18} {3,-24} {4}:{5}  {6}" -f $_.seq, $_.timestamp, $stage, $_.type, $_.actor.type, $_.actor.id, $_.message)
}

Write-Host ""
Write-Host "-- artifacts"
$artifacts = @(Invoke-Api GET "/api/v1/runs/$runId/artifacts")
foreach ($a in $artifacts) {
  Write-Host ("  {0,-18} v{1}  {2,-22} {3}  accepted={4}" -f $a.stageId, $a.version, $a.kind, $a.hash.Substring(0, 12), $a.accepted)
}

Write-Host ""
Write-Host "-- reliability metrics"
Invoke-Api GET "/api/v1/metrics/reliability" | ConvertTo-Json -Depth 6

Write-Host ""
Write-Host "Open the console at $BaseUrl/ and select run $runId to inspect the graph, lineage and artifacts."
