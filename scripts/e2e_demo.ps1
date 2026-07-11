param(
    [string]$BaseUrl = "http://localhost:8080",
    [long]$UserId = 1,
    [long]$WorkspaceId = 1,
    [string]$RepoName = "repolens-demo-e2e",
    [string]$RepoUrl = "file:///E:/shixi_xiangmu/RepoLens/test-repos/repolens-demo-service",
    [string]$BranchName = "main"
)

$ErrorActionPreference = "Stop"

function Invoke-RepoLensApi {
    param(
        [string]$Method,
        [string]$Path,
        [object]$Body = $null
    )

    $headers = @{ "X-User-Id" = "$UserId" }
    $uri = "$BaseUrl$Path"

    if ($null -eq $Body) {
        return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -TimeoutSec 60
    }

    $json = $Body | ConvertTo-Json -Depth 20
    return Invoke-RestMethod -Method $Method -Uri $uri -Headers $headers -ContentType "application/json" -Body $json -TimeoutSec 60
}

Write-Host "== RepoLens E2E Demo =="

$createReq = @{
    workspaceId = $WorkspaceId
    repoName = "$RepoName-$(Get-Date -Format 'yyyyMMddHHmmss')"
    repoUrl = $RepoUrl
    branchName = $BranchName
}
$createResp = Invoke-RepoLensApi -Method Post -Path "/api/repos" -Body $createReq
if ($createResp.code -ne 0) { throw "Create repo failed: $($createResp.message)" }
$repoId = [long]$createResp.data.id
Write-Host "[OK] create repo, repoId=$repoId"

$importResp = Invoke-RepoLensApi -Method Post -Path "/api/repos/$repoId/import"
Write-Host "[OK] import status=$($importResp.data.status)"

$parseResp = Invoke-RepoLensApi -Method Post -Path "/api/repos/$repoId/parse"
Write-Host "[OK] parse status=$($parseResp.data.status), parsedFileCount=$($parseResp.data.parsedFileCount)"

$chunkResp = Invoke-RepoLensApi -Method Post -Path "/api/repos/$repoId/chunks/build"
Write-Host "[OK] chunks status=$($chunkResp.data.status), totalChunkCount=$($chunkResp.data.totalChunkCount)"

$vectorResp = Invoke-RepoLensApi -Method Post -Path "/api/repos/$repoId/vectors/build"
Write-Host "[OK] vectors status=$($vectorResp.data.status), embedded=$($vectorResp.data.embeddedChunkCount)"

$ragReq = @{ query = "创建用户接口在哪里"; topK = 5 }
$ragResp = Invoke-RepoLensApi -Method Post -Path "/api/repos/$repoId/rag/search" -Body $ragReq
Write-Host "[OK] rag hitCount=$($ragResp.data.hitCount), degraded=$($ragResp.data.degraded)"

$chatReq = @{ question = "创建用户接口在哪里？"; topK = 5 }
$chatResp = Invoke-RepoLensApi -Method Post -Path "/api/repos/$repoId/chat/answer" -Body $chatReq
Write-Host "[OK] chat sessionId=$($chatResp.data.sessionId), degraded=$($chatResp.data.degraded)"

$asyncResp = Invoke-RepoLensApi -Method Post -Path "/api/repos/$repoId/index/async"
Write-Host "[OK] async firstTaskId=$($asyncResp.data.firstTaskId), traceId=$($asyncResp.data.traceId), status=$($asyncResp.data.status)"

Write-Host "== E2E Done, repoId=$repoId =="
