#!/usr/bin/env bash
set -euo pipefail
cd aevorwyn

fatal_pattern='SCRIPT ERROR:|Parse Error:|Compile Error:|Failed to load script|Invalid call\.|TEST FAILURES|Condition "!is_inside_tree\(\)" is true'

python3 tools/static_verify.py

set -o pipefail
Godot_v4.7.2-stable_linux.x86_64 --headless --editor --path . --quit 2>&1 | tee godot-import.log
if grep -E "$fatal_pattern" godot-import.log; then
  echo 'Godot import/compile validation found fatal errors.' >&2
  exit 1
fi

set -o pipefail
timeout 45s Godot_v4.7.2-stable_linux.x86_64 --headless --path . -s tests/test_runner.gd 2>&1 | tee godot-tests.log
grep -q 'ALL TESTS PASSED' godot-tests.log
if grep -E "$fatal_pattern" godot-tests.log; then
  echo 'Godot test validation found fatal errors.' >&2
  exit 1
fi

set -o pipefail
Godot_v4.7.2-stable_linux.x86_64 --headless --path . --quit-after 3 2>&1 | tee godot-smoke.log
if grep -E "$fatal_pattern" godot-smoke.log; then
  echo 'Godot main-scene smoke validation found fatal errors.' >&2
  exit 1
fi

# The overhaul must actually import its character, animation, axe and nature assets.
for imported in \
  assets/vendor/quaternius/character/Male_Peasant.gltf \
  assets/vendor/quaternius/animations/UAL2_Standard.glb \
  assets/vendor/quaternius/props/Axe_Bronze.gltf \
  assets/vendor/quaternius/nature/CommonTree_1.gltf; do
  test -f "$imported"
done

echo 'STRICT AEVORWYN VALIDATION PASSED'
