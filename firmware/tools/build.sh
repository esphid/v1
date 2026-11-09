#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
variant=${1:?Usage: firmware/tools/build.sh s3-n8|s3-n8r2|s3-n8r8}
case "$variant" in s3-n8|s3-n8r2|s3-n8r8) ;; *) echo "Unknown variant: $variant" >&2; exit 2;; esac
if ! command -v idf.py >/dev/null; then
  exec bash -c 'source "$1" >/dev/null; export -f idf.py; shift; exec "$@"' bash "$HOME/.espressif/tools/activate_idf_v6.0.2.sh" "$PWD/tools/build.sh" "$variant"
fi
export IDF_TARGET=esp32s3
if [[ -z ${ESPHID_VERSION:-} ]]; then
  ESPHID_VERSION=$(git describe --tags --always --dirty 2>/dev/null || true)
  export ESPHID_VERSION=${ESPHID_VERSION:-0.0.0-dev}
fi
build="$PWD/build/$variant"
mkdir -p "$build/dist"
# Pass repository via a last overlay; never edit a developer's persistent configuration.
python - "$build/ota.defaults" "${ESPHID_OTA_REPO:-}" <<'PY'
import json,sys
with open(sys.argv[1],'w') as f: f.write('CONFIG_ESPHID_OTA_REPO='+json.dumps(sys.argv[2])+'\n')
PY
# Release builds always regenerate the variant configuration from its overlays.
rm -f "$build/sdkconfig" "$build/sdkconfig.old"
export SDKCONFIG_DEFAULTS="sdkconfig.defaults;variants/$variant/sdkconfig.defaults;$build/ota.defaults"
idf.py -B "$build" -D "SDKCONFIG=$build/sdkconfig" -D "SDKCONFIG_DEFAULTS=$SDKCONFIG_DEFAULTS" reconfigure build size
cp "$build/esphid.bin" "$build/dist/esphid-$variant.bin"
python - "$build" "$variant" "$ESPHID_VERSION" <<'PY'
import json,sys,subprocess,pathlib
b=pathlib.Path(sys.argv[1]);variant=sys.argv[2];version=sys.argv[3]
args=json.loads((b/'flasher_args.json').read_text());dest=b/'dist'/f'esphid-{variant}-factory.bin'
cmd=[sys.executable,'-m','esptool','--chip','esp32s3','merge-bin','-o',str(dest)]
for key,val in args.get('flash_settings',{}).items():
    cmd+=['--'+key.replace('_','-'),str(val)]
for offset,file in args['flash_files'].items(): cmd+=[offset,str(b/file)]
subprocess.run(cmd,check=True)
manifest={'name':f'ESPHID {variant}','version':version,'new_install_prompt_erase':True,'builds':[{'chipFamily':'ESP32-S3','parts':[{'path':dest.name,'offset':0}]}]}
(b/'dist'/f'manifest-{variant}.json').write_text(json.dumps(manifest,indent=2)+'\n')
size=(b/'esphid.bin').stat().st_size
print(f'ESPHID {variant}: app={size} bytes, OTA slot={0x3e0000} bytes, free={0x3e0000-size} bytes')
print(f'Release outputs: {b / "dist"}')
PY
