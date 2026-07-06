[CmdletBinding()]
param(
  [Parameter(Mandatory = $true)]
  [ValidatePattern('^http://.+:\d+$')]
  [string]$BaseUrl,
  [string]$EnvFile
)

$ErrorActionPreference = "Stop"
$ServerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ServerDir
if (-not $EnvFile) { $EnvFile = Join-Path $ServerDir ".env" }
$LocalProperties = Join-Path $RepoRoot "local.properties"

if (-not (Test-Path $EnvFile)) { throw "Jetson environment file '$EnvFile' was not found. Copy it securely from the Jetson or pass -EnvFile <path>." }
$tokenLine = Get-Content -LiteralPath $EnvFile | Where-Object { $_ -like 'LOCAL_AI_TOKEN=*' } | Select-Object -First 1
if (-not $tokenLine) { throw "LOCAL_AI_TOKEN is missing from inference_server\.env." }
$token = $tokenLine.Substring('LOCAL_AI_TOKEN='.Length)

$lines = if (Test-Path $LocalProperties) { @(Get-Content -LiteralPath $LocalProperties) } else { @() }
$removeKeys = @(
  'LOCAL_AI_BASE_URL', 'LOCAL_AI_TOKEN',
  'GEMINI_API_KEY', 'GEMMA_MODEL_ID', 'GEMINI_SESSION_MODEL_ID',
  'GEMINI_DOCUMENT_GROUNDING_MODEL_ID', 'GROQ_API_KEY',
  'GROQ_DOCUMENT_GROUNDING_MODEL_ID', 'XAI_API_KEY',
  'XAI_DOCUMENT_GROUNDING_MODEL_ID'
)
$filtered = $lines | Where-Object {
  $line = $_
  -not ($removeKeys | Where-Object { $line -match "^$([Regex]::Escape($_))=" })
}
$filtered += "LOCAL_AI_BASE_URL=$($BaseUrl.TrimEnd('/'))"
$filtered += "LOCAL_AI_TOKEN=$token"
$filtered | Set-Content -LiteralPath $LocalProperties -Encoding ASCII

Write-Host "Android local AI configuration updated:" -ForegroundColor Green
Write-Host "  $($BaseUrl.TrimEnd('/'))"
Write-Host "Cloud AI keys were removed; the GitHub package token was preserved."
