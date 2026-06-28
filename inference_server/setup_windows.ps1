[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$ServerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ServerDir
$VenvPython = Join-Path $ServerDir ".venv\Scripts\python.exe"
$EnvFile = Join-Path $ServerDir ".env"

function Find-Python311 {
  if (Get-Command py -ErrorAction SilentlyContinue) {
    & py -3.11 -c "import sys; assert sys.version_info[:2] == (3, 11)" 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { return @("py", "-3.11") }
  }
  if (Get-Command python -ErrorAction SilentlyContinue) {
    $version = & python -c "import sys; print(f'{sys.version_info.major}.{sys.version_info.minor}')"
    if ($version -eq "3.11") { return @("python") }
  }
  throw "Python 3.11 was not found. Install 64-bit Python 3.11 from https://www.python.org/downloads/windows/ and select 'Add python.exe to PATH'."
}

function New-RandomToken {
  $bytes = New-Object byte[] 32
  $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
  try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
  return ($bytes | ForEach-Object { $_.ToString("x2") }) -join ""
}

Write-Host "[1/6] Checking prerequisites..." -ForegroundColor Cyan
if (-not (Get-Command ollama -ErrorAction SilentlyContinue)) {
  throw "Ollama was not found. Install OllamaSetup.exe from https://ollama.com/download/windows and reopen PowerShell."
}
$PythonCommand = Find-Python311

Write-Host "[2/6] Configuring Ollama for local-only, single-model operation..." -ForegroundColor Cyan
$OllamaVariables = @{
  OLLAMA_NO_CLOUD = "1"
  OLLAMA_KEEP_ALIVE = "-1"
  OLLAMA_MAX_LOADED_MODELS = "1"
  OLLAMA_NUM_PARALLEL = "1"
  OLLAMA_CONTEXT_LENGTH = "8192"
}
foreach ($entry in $OllamaVariables.GetEnumerator()) {
  [Environment]::SetEnvironmentVariable($entry.Key, $entry.Value, "User")
  Set-Item -Path "Env:$($entry.Key)" -Value $entry.Value
}

Write-Host "[3/6] Downloading Gemma 3 4B Q4 through Ollama..." -ForegroundColor Cyan
& ollama pull gemma3:4b-it-q4_K_M
if ($LASTEXITCODE -ne 0) { throw "Ollama could not download Gemma." }

Write-Host "[4/6] Creating the Python 3.11 environment..." -ForegroundColor Cyan
if (-not (Test-Path $VenvPython)) {
  if ($PythonCommand.Count -eq 2) {
    & $PythonCommand[0] $PythonCommand[1] -m venv (Join-Path $ServerDir ".venv")
  } else {
    & $PythonCommand[0] -m venv (Join-Path $ServerDir ".venv")
  }
}
& $VenvPython -m pip install --upgrade pip
& $VenvPython -m pip install paddlepaddle==3.3.0 --index-url https://www.paddlepaddle.org.cn/packages/stable/cpu/
& $VenvPython -m pip install -r (Join-Path $ServerDir "requirements.txt")

Write-Host "[5/6] Creating the private LAN token..." -ForegroundColor Cyan
if (-not (Test-Path $EnvFile)) {
  $token = New-RandomToken
  @(
    "LOCAL_AI_TOKEN=$token"
    "OLLAMA_URL=http://127.0.0.1:11434"
    "OLLAMA_MODEL=gemma3:4b-it-q4_K_M"
    "OLLAMA_CONTEXT_LENGTH=8192"
    "OCR_DETECTION_MODEL=PP-OCRv5_server_det"
  ) | Set-Content -LiteralPath $EnvFile -Encoding ASCII
}
Get-Content -LiteralPath $EnvFile | ForEach-Object {
  if ($_ -match '^([^#=]+)=(.*)$') { Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2] }
}

Write-Host "[6/6] Downloading and warming the OCR models..." -ForegroundColor Cyan
$env:OMP_NUM_THREADS = "8"
$env:MKL_NUM_THREADS = "8"
$env:FLAGS_use_mkldnn = "0"
Push-Location $RepoRoot
try {
  & $VenvPython -m inference_server.preload
  if ($LASTEXITCODE -ne 0) { throw "Model warm-up failed." }
} finally {
  Pop-Location
}

Write-Host ""
Write-Host "Setup complete." -ForegroundColor Green
Write-Host "Quit and reopen Ollama once so it inherits the saved local-only settings."
Write-Host "Then run: inference_server\start_local_ai.ps1"
