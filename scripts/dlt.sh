#!/usr/bin/env bash
# DLT 조회·재처리 도구(tools/dlt-replay) 실행 (docs/scenarios/06-poison-message-dlt.md)
#
#   scripts/dlt.sh list   inventory.command.DLT
#   scripts/dlt.sh replay inventory.command.DLT --offset 1:0[,2:3] [--dry-run]
#   scripts/dlt.sh replay inventory.command.DLT --all [--dry-run]
#
# KAFKA_BOOTSTRAP_SERVERS (기본 localhost:9092). JDK 25 가 필요하다 (PATH 의 java 또는 JAVA_HOME)
set -euo pipefail

cd "$(dirname "$0")/.."
./gradlew -q :tools:dlt-replay:installDist
exec tools/dlt-replay/build/install/dlt/bin/dlt "$@"
