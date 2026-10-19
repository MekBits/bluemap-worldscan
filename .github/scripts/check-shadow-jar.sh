#!/bin/bash
# Fails when the shadow jar in build/libs is not shaped the way BlueMap loads an addon.
set -eu

jars=$(find build/libs -name 'bluemap-worldscan-*.jar' ! -name '*-plain.jar')
if [ "$(printf '%s\n' "$jars" | grep -c .)" -ne 1 ]; then
  echo "expected exactly one shadow jar, found:"
  echo "$jars"
  exit 1
fi

entries=$(unzip -Z1 "$jars")
fail=0

# check <what is wrong> <the offending entries>
check() {
  if [ -n "$2" ]; then
    echo "$1:"
    echo "$2"
    fail=1
  fi
}

check "assets/ or data/ entries" "$(echo "$entries" | grep -E '^(assets|data)/' || true)"
check "native libraries" "$(echo "$entries" | grep -E '\.(so|dylib|dll|jnilib)$' || true)"
check "module-info.class entries" "$(echo "$entries" | grep -E '(^|/)module-info\.class$' || true)"
check "classes outside dk/mekbits/bluemapworldscan/" "$(echo "$entries" | grep -E '\.class$' | grep -v '^dk/mekbits/bluemapworldscan/' || true)"

for required in bluemap.addon.json META-INF/LICENSE META-INF/THIRD_PARTY_NOTICES.md; do
  if ! echo "$entries" | grep -qx "$required"; then
    echo "$required is missing from the jar"
    fail=1
  fi
done

exit "$fail"
