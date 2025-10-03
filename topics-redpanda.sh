#!/usr/bin/env bash
set -euo pipefail

# Create topics using rpk
create_topic() {
  local name="$1"; local parts="$2"; local repl="$3"; shift 3
  docker exec redpanda rpk topic create "$name"     --partitions "$parts"     --replicas "$repl"     "$@"
}

create_topic ticks 3 1   --config retention.ms=172800000   --config cleanup.policy=delete   --config segment.ms=3600000

create_topic option_chain 3 1   --config retention.ms=86400000   --config cleanup.policy=delete

create_topic advice 3 1   --config retention.ms=604800000   --config cleanup.policy=delete

create_topic trade 3 1   --config retention.ms=2592000000   --config cleanup.policy=delete

create_topic risk 3 1   --config retention.ms=604800000   --config cleanup.policy=delete

create_topic audit 3 1   --config retention.ms=2592000000   --config cleanup.policy=delete

create_topic decision 3 1   --config retention.ms=604800000   --config cleanup.policy=delete

echo "All topics created (or already existed)."
