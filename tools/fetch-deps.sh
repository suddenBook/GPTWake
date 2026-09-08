#!/bin/bash
# 拉取并放置 sherpa-onnx v1.13.4 与 KWS 模型。
# wrapper 与 native .so 必须来自同一个 release 产物，这里统一取官方 AAR。
set -e
VER=1.13.4
MODEL=sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP=$(mktemp -d)

echo "==> sherpa-onnx v$VER AAR"
gh release download "v$VER" --repo k2-fsa/sherpa-onnx \
  --pattern "sherpa-onnx-$VER.aar" --dir "$TMP" --clobber

echo "==> KWS 模型"
gh release download kws-models --repo k2-fsa/sherpa-onnx \
  --pattern "$MODEL.tar.bz2" --dir "$TMP" --clobber

( cd "$TMP" && unzip -o -q "sherpa-onnx-$VER.aar" -d aar && tar xf "$MODEL.tar.bz2" )

mkdir -p "$ROOT/app/libs" "$ROOT/app/src/main/jniLibs/arm64-v8a" "$ROOT/app/src/main/assets/kws"
cp "$TMP/aar/classes.jar" "$ROOT/app/libs/sherpa-onnx-$VER-classes.jar"
cp "$TMP/aar/jni/arm64-v8a/"*.so "$ROOT/app/src/main/jniLibs/arm64-v8a/"
cp "$TMP/$MODEL/encoder-epoch-13-avg-2-chunk-16-left-64.int8.onnx" \
   "$TMP/$MODEL/decoder-epoch-13-avg-2-chunk-16-left-64.onnx" \
   "$TMP/$MODEL/joiner-epoch-13-avg-2-chunk-16-left-64.int8.onnx" \
   "$TMP/$MODEL/tokens.txt" "$TMP/$MODEL/en.phone" "$ROOT/app/src/main/assets/kws/"

echo "==> 生成汉字→token 词典（需要 pypinyin）"
python3 "$ROOT/tools/gen_pinyin_tokens.py" \
  "$TMP/$MODEL/tokens.txt" "$ROOT/app/src/main/assets/kws/pinyin_tokens.txt"

rm -rf "$TMP"
bash "$ROOT/tools/fetch-japanese-deps.sh"
echo "完成。"
