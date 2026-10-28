#!/bin/bash
# Run after boot.sh (root/ is the extracted arm64 rootfs). Imports a plugin the way the ZIP picker does.
set -ex
id=11111111-2222-4333-8444-555555555555
sudo mkdir -p root/root/.hoshino/plugin-sources/$id/hello-plugin
printf 'export const name="hello-zip";\nexport function apply(ctx){}\n' | sudo tee root/root/.hoshino/plugin-sources/$id/hello-plugin/index.js
cat > /tmp/t.mjs <<'J'
import {installPluginZip} from '/opt/hoshino/node-host/dist/plugins/hoshino-m2/operations.js';
import {Configuration} from '/opt/hoshino/node-host/dist/plugins/hoshino-m2/config.js';
import {readFile} from 'node:fs/promises';
const r=await installPluginZip({},new Configuration('/root/.hoshino'),'plugin-sources/11111111-2222-4333-8444-555555555555','Hello Plugin.zip');
console.log(JSON.stringify(r));if(!r.ok)process.exit(1);
console.log(await readFile('/root/.hoshino/plugins/'+r.id+'/index.js','utf8'));
const m=await import(r.entry);if(m.name!=='hello-zip')process.exit(2);
J
sudo cp /tmp/t.mjs root/tmp/t.mjs
sudo timeout 600 chroot root /usr/bin/env -i HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin LD_PRELOAD=/opt/hoshino/libandroid-compat.so /usr/bin/node /tmp/t.mjs | tee zip.log
ok=false; grep -q '"ok":true' zip.log && ok=true
[ -n "$1" ] && echo "{\"zipImport\": $ok}" > "$1"
$ok
