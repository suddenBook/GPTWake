#!/usr/bin/env bash
# Prepare generated APK assets; weights stay out of the source checkout and git history.
set -euo pipefail
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
ja_dir="${1:-$repo_root/app/build/generated/japaneseAssets/ja}"
checksums="$repo_root/app/src/main/assets/ja/SHA256SUMS"
if [[ -d "$ja_dir" ]] && (cd "$ja_dir" && sha256sum --check --status "$checksums"); then
  echo "Japanese models already verified."
  exit 0
fi
download_dir="$(mktemp -d)"
trap 'rm -rf "$download_dir"' EXIT
model_name=sherpa-onnx-moonshine-tiny-ja-quantized-2026-02-27
release_url=https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models
curl --fail --location --retry 3 --connect-timeout 20 --max-time 600 \
  "$release_url/$model_name.tar.bz2" -o "$download_dir/model.tar.bz2"
curl --fail --location --retry 3 --connect-timeout 20 --max-time 120 \
  "$release_url/silero_vad.onnx" -o "$download_dir/silero_vad.onnx"
(
  cd "$download_dir"
  printf '%s\n' \
    '880305c9a6c33572ab269ff9731977ea42ca34c8cffcdd5d99558a9ea2b47cc2  model.tar.bz2' \
    '9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6  silero_vad.onnx' \
    | sha256sum --check
  tar -xjf model.tar.bz2
)
mkdir -p "$download_dir/verified"
for file in encoder_model.ort decoder_model_merged.ort tokens.txt; do
  cp "$download_dir/$model_name/$file" "$download_dir/verified/$file"
done
cp "$download_dir/silero_vad.onnx" "$download_dir/verified/silero_vad.onnx"
(cd "$download_dir/verified" && sha256sum --check "$checksums")
mkdir -p "$ja_dir"
for file in encoder_model.ort decoder_model_merged.ort tokens.txt silero_vad.onnx; do
  cp "$download_dir/verified/$file" "$ja_dir/$file.tmp"
  mv "$ja_dir/$file.tmp" "$ja_dir/$file"
done
