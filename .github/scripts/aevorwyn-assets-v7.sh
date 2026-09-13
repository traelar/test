#!/usr/bin/env bash
set -euo pipefail

base='https://raw.githubusercontent.com/agentkaerf/FreeModels/main'
char='aevorwyn/assets/vendor/quaternius/character'
anim='aevorwyn/assets/vendor/quaternius/animations'
props='aevorwyn/assets/vendor/quaternius/props'
nature='aevorwyn/assets/vendor/quaternius/nature'
licenses='aevorwyn/assets/vendor/quaternius/licenses'
mkdir -p "$char" "$anim" "$props" "$nature" "$licenses"

curl -L --fail --retry 3 "$base/Modular%20Character%20Outfits%20-%20Fantasy%5BStandard%5D/Exports/glTF%20(Godot-Unreal)/Outfits/Male_Ranger.gltf" -o "$char/Male_Ranger.gltf"
curl -L --fail --retry 3 "$base/Modular%20Character%20Outfits%20-%20Fantasy%5BStandard%5D/Exports/glTF%20(Godot-Unreal)/Outfits/Male_Ranger.bin" -o "$char/Male_Ranger.bin"
for f in T_Ranger_BaseColor.png T_Ranger_Normal.png T_Ranger_ORM.png T_Regular_Male_Dark_BaseColor.png T_Regular_Male_Normal.png T_Regular_Male_Roughness.png; do
  curl -L --fail --retry 3 "$base/Modular%20Character%20Outfits%20-%20Fantasy%5BStandard%5D/Exports/glTF%20(Godot-Unreal)/Outfits/$f" -o "$char/$f"
done
curl -L --fail --retry 3 "$base/Modular%20Character%20Outfits%20-%20Fantasy%5BStandard%5D/License_Standard.txt" -o "$licenses/character-CC0.txt"

curl -L --fail --retry 3 "$base/Universal%20Animation%20Library%202%5BStandard%5D/Unreal-Godot/UAL2_Standard.glb" -o "$anim/UAL2_Standard.glb"
curl -L --fail --retry 3 "$base/Universal%20Animation%20Library%202%5BStandard%5D/License.txt" -o "$licenses/animations-CC0.txt"

curl -L --fail --retry 3 "$base/Fantasy%20Props%20MegaKit%5BStandard%5D/Exports/glTF/Axe_Bronze.gltf" -o "$props/Axe_Bronze.gltf"
curl -L --fail --retry 3 "$base/Fantasy%20Props%20MegaKit%5BStandard%5D/Exports/glTF/Axe_Bronze.bin" -o "$props/Axe_Bronze.bin"
for f in T_Trim_Props_BaseColor.png T_Trim_Props_Normal.png T_Trim_Props_ORM.png; do
  curl -L --fail --retry 3 "$base/Fantasy%20Props%20MegaKit%5BStandard%5D/Exports/glTF/$f" -o "$props/$f"
done
curl -L --fail --retry 3 "$base/Fantasy%20Props%20MegaKit%5BStandard%5D/License_Standard.txt" -o "$licenses/props-CC0.txt"

curl -L --fail --retry 3 "$base/Stylized%20Nature%20MegaKit%5BStandard%5D/glTF/CommonTree_1.gltf" -o "$nature/CommonTree_1.gltf"
curl -L --fail --retry 3 "$base/Stylized%20Nature%20MegaKit%5BStandard%5D/glTF/CommonTree_1.bin" -o "$nature/CommonTree_1.bin"
curl -L --fail --retry 3 "$base/Stylized%20Nature%20MegaKit%5BStandard%5D/glTF/Rock_Medium_1.gltf" -o "$nature/Rock_Medium_1.gltf"
curl -L --fail --retry 3 "$base/Stylized%20Nature%20MegaKit%5BStandard%5D/glTF/Rock_Medium_1.bin" -o "$nature/Rock_Medium_1.bin"
curl -L --fail --retry 3 "$base/Stylized%20Nature%20MegaKit%5BStandard%5D/glTF/Bush_Common.gltf" -o "$nature/Bush_Common.gltf"
curl -L --fail --retry 3 "$base/Stylized%20Nature%20MegaKit%5BStandard%5D/glTF/Bush_Common.bin" -o "$nature/Bush_Common.bin"
for f in Bark_NormalTree.png Bark_NormalTree_Normal.png Leaves_NormalTree_C.png Rocks_Diffuse.png Leaves_TwistedTree_C.png; do
  curl -L --fail --retry 3 "$base/Stylized%20Nature%20MegaKit%5BStandard%5D/glTF/$f" -o "$nature/$f"
done
curl -L --fail --retry 3 "$base/Stylized%20Nature%20MegaKit%5BStandard%5D/License_Standard.txt" -o "$licenses/nature-CC0.txt"

test -s "$char/Male_Ranger.gltf"
test -s "$char/Male_Ranger.bin"
test -s "$anim/UAL2_Standard.glb"
test -s "$props/Axe_Bronze.gltf"
test -s "$nature/CommonTree_1.gltf"
find aevorwyn/assets/vendor/quaternius -type f -size 0 -print -quit | grep -q . && exit 1 || true
