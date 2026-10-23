#!/usr/bin/env bash
# Publishes the local libretro-kmp fork (0.1.3-SNAPSHOT, with BufferedSerialization) to ~/.m2.
set -euo pipefail
cd "$(dirname "$0")/../../libretro-kmp"
./gradlew \
  :libretro-kmp:publishJvmPublicationToMavenLocal \
  :libretro-kmp:publishKotlinMultiplatformPublicationToMavenLocal
