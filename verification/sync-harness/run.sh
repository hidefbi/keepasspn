#!/bin/sh
# Compiles the real sync/PIN-format sources with minimal Android/JUnit stubs and runs the scenarios.
# Usage: KOTLINC=/path/to/kotlinc/bin/kotlinc ./verification/sync-harness/run.sh
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
KOTLINC="${KOTLINC:-kotlinc}"
OUT="$(mktemp -d)"
"$KOTLINC" \
  "$ROOT/database/src/main/java/com/keepasspn/database/pin/PinEnvelope.kt" \
  "$ROOT/database/src/main/java/com/keepasspn/database/backup/BackupSyncPolicy.kt" \
  "$ROOT/app/src/main/java/com/keepasspn/utils/NoOpLog.kt" \
  "$ROOT/app/src/main/java/com/keepasspn/utils/LocalDatabaseBackupManager.kt" \
  "$ROOT/database/src/test/java/com/keepasspn/tests/pin/PinEnvelopeTest.kt" \
  "$ROOT/database/src/test/java/com/keepasspn/tests/backup/BackupSyncPolicyTest.kt" \
  "$ROOT/verification/sync-harness/Uri.kt" \
  "$ROOT/verification/sync-harness/Context.kt" \
  "$ROOT/verification/sync-harness/Test.kt" \
  "$ROOT/verification/sync-harness/Assert.kt" \
  "$ROOT/verification/sync-harness/Main.kt" \
  -jvm-target 17 -include-runtime -d "$OUT/harness.jar"
java -jar "$OUT/harness.jar"
