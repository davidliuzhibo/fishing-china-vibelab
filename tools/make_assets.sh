#!/bin/bash
# Builds FishingChina's assets (run from the workspace root): the fish and gear (prepared), the
# five panoramas and their image-based lights, the painting and the effects.
set -eu
A=Pico_Vibe_Lab/FishingChina/app/app/src/main/assets
L=assets_lab/fishing
B=~/.local/blender-dl/blender-4.2.3-linux-x64/blender
CMGEN=~/.local/filament-dl/filament/bin/cmgen
mkdir -p $A/models $A/textures $A/audio
tools/blender/prep_round5.sh fishing
for f in $L/ai/*.glb; do [ -f "$f" ] && cp "$f" $A/models/; done
cp Pico_Vibe_Lab/FarSight/app/app/src/main/assets/models/sky.glb $A/models/sky.glb
for k in xihu qiandao lijiang chagan xihu_night; do
  P=$L/pano/${k}_pano.jpeg
  [ -f $P ] || continue
  ffmpeg -loglevel error -y -i $P -vf scale=4096:2048 -q:v 3 $A/textures/sky_$k.jpg
  if [ ! -f $A/textures/ibl_$k.ktx ]; then
    T=$(mktemp -d)
    $B -b --factory-startup --python Pico_Vibe_Lab/ClockShop/tools/ibl_source.py -- $P $T/$k.hdr 1.5 >/dev/null 2>&1
    (cd $T && $CMGEN --format=ktx --size=256 --deploy=$T/out $k.hdr >/dev/null 2>&1)
    cp $T/out/out_ibl.ktx $A/textures/ibl_$k.ktx
    rm -rf $T
  fi
done
[ -f $L/tex/water.jpeg ] && ffmpeg -loglevel error -y -i $L/tex/water.jpeg -vf scale=1024:1024 -q:v 3 $A/textures/water.jpg
[ -f $L/tex/treasure.jpeg ] && ffmpeg -loglevel error -y -i $L/tex/treasure.jpeg -vf scale=1600:-1 -q:v 3 $A/textures/treasure.jpg
uv run -q --with numpy python -I tools/sfx_synth.py $A/audio whoosh:0.45=cast plop=plop pop=nibble splash:0.3=bite whoosh:0.2=strike reel=reel splash:0.5=run crack=snap splash:0.9=splash fanfare=landed ding:88=mission click:90=bait click:55=wrong >/dev/null
du -sh $A
