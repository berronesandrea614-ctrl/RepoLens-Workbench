param(
    [string]$BaseUrl = "http://localhost:8080",
    [long]$RepoId = 5,
    [long]$UserId = 1,
    [int]$Iterations = 50,
    [int]$TopK = 5,
    [string]$Query = "创建用户接口在哪里"
)

$ErrorActionPreference = "Stop"

$headers = @{ "X-User-Id" = "$UserId" }
$latencies = New-Object System.Collections.Generic.List[double]
$success = 0
$fail = 0
$swTotal = [System.Diagnostics.Stopwatch]::StartNew()

for ($i = 1; $i -le $Iterations; $i++) {
    $body = @{ query = $Query; topK = $TopK } | ConvertTo-Json
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        $resp = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/repos/$RepoId/rag/search" -Headers $headers -ContentType "application/json" -Body $body -TimeoutSec 30
        $sw.Stop()
        $latencies.Add($sw.Elapsed.TotalMilliseconds)
        if ($resp.code -eq 0) { $success++ } else { $fail++ }
    } catch {
        $sw.Stop()
        $latencies.Add($sw.Elapsed.TotalMilliseconds)
        $fail++
    }
}

$swTotal.Stop()
$avg = if ($latencies.Count -gt 0) { ($latencies | Measure-Object -Average).Average } else { 0 }
$sorted = $latencies | Sort-Object
$p95Index = [Math]::Min([Math]::Max([int][Math]::Ceiling($sorted.Count * 0.95) - 1, 0), [Math]::Max($sorted.Count - 1, 0))
$p95 = if ($sorted.Count -gt 0) { $sorted[$p95Index] } else { 0 }

Write-Host "== benchmark_rag =="
Write-Host "Iterations : $Iterations"
Write-Host "Success    : $success"
Write-Host "Fail       : $fail"
Write-Host ("Total(ms)  : {0:N2}" -f $swTotal.Elapsed.TotalMilliseconds)
Write-Host ("Avg(ms)    : {0:N2}" -f $avg)
Write-Host ("P95(ms)    : {0:N2}" -f $p95)
