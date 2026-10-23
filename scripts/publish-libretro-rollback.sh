#!/usr/bin/env bash
# Publishes the engine (0.1.0-SNAPSHOT) to ~/.m2 so pokemon-client-rollback can use it.
set -euo pipefail
cd "$(dirname "$0")/.."
./gradlew \
  :libretro-rollback:publishJvmPublicationToMavenLocal \
  :libretro-rollback:publishKotlinMultiplatformPublicationToMavenLocal
