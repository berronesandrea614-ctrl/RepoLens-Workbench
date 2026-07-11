param(
    [string]$ProjectRoot = "E:/shixi_xiangmu/RepoLens",
    [string]$BaseUrl = "http://localhost:8080",
    [int]$RepoId = 5
)

$ErrorActionPreference = "Stop"

function Write-CheckResult {
    param(
        [string]$Name,
        [bool]$Ok,
        [string]$Detail
    )
    $status = if ($Ok) { "PASS" } else { "FAIL" }
    Write-Host ("[{0}] {1} - {2}" -f $status, $Name, $Detail)
}

Write-Host "== RepoLens Environment Check =="

$requiredContainers = @(
    "repolens-mysql",
    "repolens-redis",
    "repolens-milvus",
    "repolens-rocketmq-namesrv",
    "repolens-rocketmq-broker"
)

$dockerLines = docker ps --format "{{.Names}}|{{.Status}}"
$containerMap = @{}
foreach ($line in $dockerLines) {
    if (-not [string]::IsNullOrWhiteSpace($line)) {
        $parts = $line.Split("|", 2)
        if ($parts.Count -eq 2) {
            $containerMap[$parts[0]] = $parts[1]
        }
    }
}

foreach ($name in $requiredContainers) {
    $exists = $containerMap.ContainsKey($name)
    $detail = if ($exists) { $containerMap[$name] } else { "not running" }
    Write-CheckResult -Name "Docker:$name" -Ok $exists -Detail $detail
}

$mysqlOk = $false
try {
    $mysqlOut = docker exec -e MYSQL_PWD=repolens123 repolens-mysql mysql -urepolens -Drepolens -Nse "SELECT 1;" 2>$null
    $mysqlOk = ($LASTEXITCODE -eq 0 -and ($mysqlOut -join "").Trim() -eq "1")
} catch {
    $mysqlOk = $false
}
Write-CheckResult -Name "MySQL query" -Ok $mysqlOk -Detail "SELECT 1"

$redisPortOk = Test-NetConnection -ComputerName localhost -Port 6379 -InformationLevel Quiet
Write-CheckResult -Name "Redis port" -Ok $redisPortOk -Detail "localhost:6379"

$milvusPortOk = Test-NetConnection -ComputerName localhost -Port 19530 -InformationLevel Quiet
Write-CheckResult -Name "Milvus port" -Ok $milvusPortOk -Detail "localhost:19530"

$namesrvPortOk = Test-NetConnection -ComputerName localhost -Port 9876 -InformationLevel Quiet
Write-CheckResult -Name "RocketMQ NameServer port" -Ok $namesrvPortOk -Detail "localhost:9876"

$brokerPortOk = Test-NetConnection -ComputerName localhost -Port 10911 -InformationLevel Quiet
Write-CheckResult -Name "RocketMQ Broker port" -Ok $brokerPortOk -Detail "localhost:10911"

$apiOk = $false
$apiDetail = ""
try {
    $headers = @{ "X-User-Id" = "1" }
    $resp = Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/repos/$RepoId" -Headers $headers -TimeoutSec 10
    $apiOk = ($resp.code -eq 0)
    $apiDetail = "code=$($resp.code), repoId=$RepoId"
} catch {
    $apiOk = $false
    $apiDetail = $_.Exception.Message
}
Write-CheckResult -Name "API /api/repos/$RepoId" -Ok $apiOk -Detail $apiDetail

Write-Host "== Check Completed =="
