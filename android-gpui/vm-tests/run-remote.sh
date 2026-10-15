#!/usr/bin/env bash
# Only an explicitly named disposable emulator and the checked-in synthetic peer.
set -euo pipefail
HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
if [[ $# -lt 3 || $# -gt 4 ]]; then
  echo "Usage: $0 <confirmed-emulator-serial> <target-x86_64-debug.apk> <synthetic-fixture-metadata.json> [evidence-directory]" >&2
  exit 2
fi
# Validate before run.sh performs any installation or launches an Activity.
python3 "$HERE/remote-fixture-config.py" --route "${HERDR_VM_FIXTURE_ROUTE:-host}" "$3" >/dev/null
export HERDR_VM_REMOTE_METADATA_FILE="$3"
exec "$HERE/run.sh" "$1" "$2" "${4:-$HERE/build/remote-evidence}"
