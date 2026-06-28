[CmdletBinding()]
param(
  [int]$Port = 8000,
  [switch]$UsbOnly,
  [string]$DeviceSerial
)

$ErrorActionPreference = "Stop"
$ServerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent $ServerDir
$VenvPython = Join-Path $ServerDir ".venv\Scripts\python.exe"
$EnvFile = Join-Path $ServerDir ".env"
$LocalProperties = Join-Path $RepoRoot "local.properties"
$AdbPath = $null
$SelectedDevice = $null
$ReverseConfigured = $false

function Resolve-AdbPath {
  $adbCommand = Get-Command adb -ErrorAction SilentlyContinue
  if ($adbCommand) { return $adbCommand.Source }

  $candidates = @()
  if ($env:ANDROID_HOME) {
    $candidates += Join-Path $env:ANDROID_HOME "platform-tools\adb.exe"
  }
  if ($env:ANDROID_SDK_ROOT) {
    $candidates += Join-Path $env:ANDROID_SDK_ROOT "platform-tools\adb.exe"
  }
  if (Test-Path $LocalProperties) {
    $sdkLine =
      Get-Content -LiteralPath $LocalProperties |
      Where-Object { $_ -like 'sdk.dir=*' } |
      Select-Object -First 1
    if ($sdkLine) {
      $sdkPath = $sdkLine.Substring('sdk.dir='.Length).Replace('\:', ':').Replace('\\', '\')
      $candidates += Join-Path $sdkPath "platform-tools\adb.exe"
    }
  }
  $candidates += Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"

  $match = $candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) } | Select-Object -First 1
  if (-not $match) {
    throw "adb.exe was not found. In Android Studio, open Tools > SDK Manager > SDK Tools and install Android SDK Platform-Tools."
  }
  return $match
}

function Select-UsbDevice([string]$adb, [string]$requestedSerial) {
  & $adb start-server | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "ADB could not start." }

  $rows = @(& $adb devices) | Select-Object -Skip 1 | Where-Object { $_.Trim() }
  $emulators = @($rows | Where-Object { $_ -match '^emulator-\S+\s+' })
  if ($emulators.Count -gt 0) {
    throw "An Android emulator is running. Close all emulators so the USB tunnel can target only the physical phone."
  }

  $unauthorized = @($rows | Where-Object { $_ -match '\s+unauthorized$' })
  if ($unauthorized.Count -gt 0) {
    throw "The phone has not authorized USB debugging. Unlock it, approve 'Allow USB debugging', select 'Always allow from this computer', and retry."
  }

  $offline = @($rows | Where-Object { $_ -match '\s+offline$' })
  if ($offline.Count -gt 0) {
    throw "ADB reports the phone as offline. Reconnect the USB cable, unlock the phone, and retry."
  }

  $devices =
    @(
      $rows |
      Where-Object { $_ -match '^\S+\s+device$' } |
      ForEach-Object { ($_ -split '\s+')[0] }
    )

  if ($requestedSerial) {
    if ($devices -notcontains $requestedSerial) {
      throw "The requested device '$requestedSerial' is not connected and authorized."
    }
    return $requestedSerial
  }
  if ($devices.Count -eq 0) {
    throw "No authorized Android phone was found. Connect a data-capable USB cable and enable USB debugging."
  }
  if ($devices.Count -gt 1) {
    throw "More than one physical Android device is connected. Disconnect the extras or pass -DeviceSerial <serial>."
  }
  return $devices[0]
}

if ($DeviceSerial -and -not $UsbOnly) {
  throw "-DeviceSerial can only be used together with -UsbOnly."
}
if (-not (Test-Path $VenvPython)) {
  throw "The local AI environment is missing. Run inference_server\setup_windows.ps1 first."
}
if (-not (Test-Path $EnvFile)) {
  throw "inference_server\.env is missing. Run setup_windows.ps1 first."
}

Get-Content -LiteralPath $EnvFile | ForEach-Object {
  if ($_ -match '^([^#=]+)=(.*)$') { Set-Item -Path "Env:$($Matches[1])" -Value $Matches[2] }
}
$env:OLLAMA_NO_CLOUD = "1"
$env:OLLAMA_KEEP_ALIVE = "-1"
$env:OLLAMA_MAX_LOADED_MODELS = "1"
$env:OLLAMA_NUM_PARALLEL = "1"
$env:OLLAMA_CONTEXT_LENGTH = "8192"
$env:OMP_NUM_THREADS = "8"
$env:MKL_NUM_THREADS = "8"
$env:FLAGS_use_mkldnn = "0"

try {
  Invoke-RestMethod -Uri "http://127.0.0.1:11434/api/tags" -TimeoutSec 3 | Out-Null
} catch {
  throw "Ollama is not running. Open Ollama from the Windows Start menu, then run this script again."
}

if ($UsbOnly) {
  $AdbPath = Resolve-AdbPath
  $SelectedDevice = Select-UsbDevice -adb $AdbPath -requestedSerial $DeviceSerial

  try {
    & $AdbPath -s $SelectedDevice reverse --remove "tcp:$Port" 2>$null | Out-Null
  } catch {
    # A first-time run has no previous tunnel to remove.
  }
  & $AdbPath -s $SelectedDevice reverse "tcp:$Port" "tcp:$Port" | Out-Null
  if ($LASTEXITCODE -ne 0) {
    throw "ADB could not create the USB reverse tunnel for port $Port."
  }
  $ReverseConfigured = $true
  $BindAddress = "127.0.0.1"
  $HealthUrl = "http://127.0.0.1:$Port/health"
} else {
  $BindAddress = "0.0.0.0"
  $lanAddress =
    Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object {
      $_.IPAddress -notlike '127.*' -and
      $_.IPAddress -notlike '169.254.*' -and
      $_.AddressState -eq 'Preferred'
    } |
    Select-Object -First 1 -ExpandProperty IPAddress
  if (-not $lanAddress) { $lanAddress = "<laptop-ip>" }
  $HealthUrl = "http://${lanAddress}:$Port/health"
}

try {
  Write-Host "Preloading Gemma into GPU memory..." -ForegroundColor Cyan
  $preloadBody = @{
    model = "gemma3:4b-it-q4_K_M"
    keep_alive = -1
  } | ConvertTo-Json
  try {
    Invoke-RestMethod `
      -Method Post `
      -Uri "http://127.0.0.1:11434/api/chat" `
      -ContentType "application/json" `
      -Body $preloadBody `
      -TimeoutSec 90 | Out-Null
  } catch {
    throw "Gemma could not be preloaded. Run 'ollama list' and confirm gemma3:4b-it-q4_K_M is installed. $($_.Exception.Message)"
  }

  Write-Host "Local AI gateway starting" -ForegroundColor Green
  if ($UsbOnly) {
    Write-Host "USB phone: $SelectedDevice"
    Write-Host "ADB reverse tunnel: phone tcp:$Port -> laptop tcp:$Port"
    Write-Host "Phone health URL: $HealthUrl"
    Write-Host "Keep this USB cable connected. USB tethering must remain off."
  } else {
    Write-Host "Laptop health URL: $HealthUrl"
  }
  Write-Host "Gateway bind address: $BindAddress`:$Port"
  Write-Host "Ollama remains private at http://127.0.0.1:11434"
  Write-Host "Press Ctrl+C to stop the gateway."

  Push-Location $RepoRoot
  try {
    & $VenvPython -m uvicorn inference_server.app:app --host $BindAddress --port $Port
  } finally {
    Pop-Location
  }
} finally {
  if ($ReverseConfigured -and $AdbPath -and $SelectedDevice) {
    try {
      & $AdbPath -s $SelectedDevice reverse --remove "tcp:$Port" 2>$null | Out-Null
    } catch {
      # The cable may already be disconnected, which also removes the tunnel.
    }
    Write-Host "USB reverse tunnel removed."
  }
}
