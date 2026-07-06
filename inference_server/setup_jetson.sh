#!/usr/bin/env bash
set -euo pipefail

readonly SERVER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly REPO_ROOT="$(cd "${SERVER_DIR}/.." && pwd)"
readonly ENV_FILE="${SERVER_DIR}/.env"
readonly MODEL="${OLLAMA_MODEL:-gemma3:4b-it-q4_K_M}"
readonly MIN_FREE_KB=$((30 * 1024 * 1024))
readonly SERVICE_USER="${SUDO_USER:-$USER}"
readonly SERVICE_GROUP="$(id -gn "$SERVICE_USER")"
readonly USER_HOME="$(getent passwd "$SERVICE_USER" | cut -d: -f6)"

fail() { echo "ERROR: $*" >&2; exit 1; }
require() { command -v "$1" >/dev/null || fail "Required command not found: $1"; }

(( EUID != 0 )) || fail "Run this script as the deployment user, not as root; it invokes sudo when needed."
require sudo
require awk
require sed
chmod +x "${SERVER_DIR}/adb_reverse_manager.sh" "${SERVER_DIR}/verify_jetson.sh"

[[ "$(uname -m)" == "aarch64" ]] || fail "This setup requires ARM64 (aarch64)."
compatible_ids="$(tr '\0' '\n' </proc/device-tree/compatible 2>/dev/null || true)"
grep -qx 'nvidia,p3767-0003' <<<"$compatible_ids" || fail "Expected the p3767-0003 Jetson Orin Nano 8GB module."
total_kb="$(awk '/MemTotal/ {print $2}' /proc/meminfo)"
(( total_kb >= 7000000 && total_kb <= 8500000 )) || fail "Expected an 8GB Jetson; MemTotal=${total_kb}kB."
[[ -f /etc/nv_tegra_release ]] || fail "JetPack/L4T was not detected."
grep -q 'R36.*REVISION: 4' /etc/nv_tegra_release || fail "JetPack 6.2 / L4T 36.4.x is required."
command -v nvcc >/dev/null || [[ -d /usr/local/cuda ]] || fail "CUDA was not detected."

root_source="$(findmnt -no SOURCE /)"
[[ "$root_source" == *nvme* ]] || fail "The root filesystem must be on NVMe (found $root_source)."
free_kb="$(df -Pk "$REPO_ROOT" | awk 'NR==2 {print $4}')"
(( free_kb >= MIN_FREE_KB )) || fail "At least 30GB free is required."
sudo nvpmodel -q --verbose | grep -q '25W' || fail "The 25W mode is unavailable; flash JetPack 6.2 with the Super configuration."
[[ -d /sys/devices/platform/pwm-fan ]] || compgen -G '/sys/class/hwmon/hwmon*/pwm1' >/dev/null || fail "Active fan control was not detected."

echo "Installing Jetson runtime packages..."
sudo apt-get update
sudo apt-get install -y android-sdk-platform-tools-common adb curl openssl python3-venv python3-pip
if ! command -v ollama >/dev/null; then
  tmp_installer="$(mktemp)"
  trap 'rm -f "$tmp_installer"' EXIT
  curl --fail --silent --show-error --location https://ollama.com/install.sh --output "$tmp_installer"
  sudo sh "$tmp_installer"
fi

python3 -m venv "${SERVER_DIR}/.venv"
"${SERVER_DIR}/.venv/bin/python" -m pip install --upgrade pip
"${SERVER_DIR}/.venv/bin/python" -m pip install -r "${SERVER_DIR}/requirements.txt"

if [[ ! -f "$ENV_FILE" ]]; then
  read -r -s -p "LOCAL_AI_TOKEN (leave blank to generate): " local_token
  echo
  if [[ -z "$local_token" ]]; then
    local_token="$(openssl rand -hex 32)"
  fi
  [[ "$local_token" =~ ^[A-Za-z0-9_-]{32,}$ ]] || fail "Token must contain at least 32 letters, digits, underscores, or hyphens."
  umask 077
  printf 'LOCAL_AI_TOKEN=%s\nOLLAMA_URL=http://127.0.0.1:11434\nOLLAMA_MODEL=%s\nOLLAMA_CONTEXT_LENGTH=4096\n' \
    "$local_token" "$MODEL" >"$ENV_FILE"
else
  chmod 600 "$ENV_FILE"
  grep -q '^LOCAL_AI_TOKEN=.' "$ENV_FILE" || fail "Existing .env has no LOCAL_AI_TOKEN."
  existing_token="$(sed -n 's/^LOCAL_AI_TOKEN=//p' "$ENV_FILE" | tail -n 1)"
  [[ "$existing_token" =~ ^[A-Za-z0-9_-]{32,}$ ]] || fail "Existing LOCAL_AI_TOKEN has an unsafe format."
  if grep -q '^OLLAMA_MODEL=' "$ENV_FILE"; then
    sed -i "s|^OLLAMA_MODEL=.*|OLLAMA_MODEL=${MODEL}|" "$ENV_FILE"
  else
    printf 'OLLAMA_MODEL=%s\n' "$MODEL" >>"$ENV_FILE"
  fi
  if grep -q '^OLLAMA_CONTEXT_LENGTH=' "$ENV_FILE"; then
    sed -i 's|^OLLAMA_CONTEXT_LENGTH=.*|OLLAMA_CONTEXT_LENGTH=4096|' "$ENV_FILE"
  else
    printf 'OLLAMA_CONTEXT_LENGTH=4096\n' >>"$ENV_FILE"
  fi
fi
chmod 600 "$ENV_FILE"
install -d -m 0700 "${USER_HOME}/.android"

sudo mkdir -p /etc/systemd/system/ollama.service.d
sudo install -m 0644 "${SERVER_DIR}/systemd/ollama-loopback.conf" /etc/systemd/system/ollama.service.d/metax-local.conf
for unit in metax-gateway.service metax-adb-reverse.service; do
  temp_unit="$(mktemp)"
  sed -e "s|@REPO_ROOT@|${REPO_ROOT}|g" -e "s|@SERVICE_USER@|${SERVICE_USER}|g" \
      -e "s|@SERVICE_GROUP@|${SERVICE_GROUP}|g" -e "s|@USER_HOME@|${USER_HOME}|g" \
      "${SERVER_DIR}/systemd/${unit}" >"$temp_unit"
  sudo install -m 0644 "$temp_unit" "/etc/systemd/system/${unit}"
  rm -f "$temp_unit"
done
sudo usermod -aG plugdev "$SERVICE_USER"
sudo systemctl daemon-reload
sudo systemctl enable --now ollama.service
sleep 3
OLLAMA_HOST=127.0.0.1:11434 ollama pull "$MODEL"
sudo systemctl enable --now metax-gateway.service metax-adb-reverse.service
sleep 5
"${SERVER_DIR}/verify_jetson.sh"

echo "Setup complete. Re-login once if this user was newly added to plugdev."
