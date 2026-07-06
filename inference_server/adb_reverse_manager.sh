#!/usr/bin/env bash
set -uo pipefail

readonly PORT="${ADB_REVERSE_PORT:-8000}"
readonly POLL_SECONDS="${ADB_POLL_SECONDS:-2}"
last_state=""

log_state() {
  if [[ "$1" != "$last_state" ]]; then
    printf '%s\n' "$1"
    last_state="$1"
  fi
}

while true; do
  mapfile -t rows < <(adb devices | tail -n +2 | sed '/^[[:space:]]*$/d')
  authorized=()
  blocked=0
  for row in "${rows[@]}"; do
    serial="${row%%[[:space:]]*}"
    state="$(awk '{print $2}' <<<"$row")"
    [[ "$serial" == emulator-* ]] && continue
    if [[ "$state" == "device" ]]; then
      authorized+=("$serial")
    else
      blocked=$((blocked + 1))
    fi
  done

  if (( blocked > 0 )); then
    log_state "Waiting: unlock the phone and authorize this Jetson for USB debugging."
  elif (( ${#authorized[@]} == 0 )); then
    log_state "Waiting for one authorized physical Android phone."
  elif (( ${#authorized[@]} > 1 )); then
    log_state "Waiting: more than one authorized physical Android phone is connected."
  else
    serial="${authorized[0]}"
    if ! adb -s "$serial" reverse --list 2>/dev/null | awk -v port="tcp:${PORT}" '$2 == port && $3 == port {found=1} END {exit !found}'; then
      if adb -s "$serial" reverse "tcp:${PORT}" "tcp:${PORT}" >/dev/null; then
        log_state "ADB reverse active for ${serial}: phone tcp:${PORT} -> Jetson tcp:${PORT}."
      else
        log_state "ADB reverse failed for ${serial}; retrying."
      fi
    else
      log_state "ADB reverse active for ${serial}: phone tcp:${PORT} -> Jetson tcp:${PORT}."
    fi
  fi
  sleep "$POLL_SECONDS"
done
