#!/bin/bash
# Run after boot.sh: real arm64 rootfs reads both default plugin indexes and installs one npm and one git (subdirectory) plugin from them.
set -ex
cat > /tmp/m.mjs <<'J'
import {marketInvoke} from '/opt/hoshino/node-host/dist/plugins/hoshino-m2/market.js';
import {Configuration} from '/opt/hoshino/node-host/dist/plugins/hoshino-m2/config.js';
const c=new Configuration('/root/.hoshino');await c.reload();
const {sources}=await marketInvoke({},c,'sources',{});const out={sources:sources.length};
for(const s of sources){const q=await marketInvoke({},c,'query',{url:s.url,limit:30});out[s.url]=q.total;if(!(q.total>0&&q.plugins.length===30))throw Error('empty index '+s.url);}
const fuzzy=await marketInvoke({},c,'query',{url:sources[0].url,q:'memroy',limit:5});out.fuzzy=fuzzy.total;if(!fuzzy.total)throw Error('fuzzy search');
const npm=await marketInvoke({},c,'install',{url:'https://cdn.jsdelivr.net/npm/dsh-plugin-catalog/index.json',name:'dsh-tg-bridge'});out.npm=npm.ok;if(!npm.ok)throw Error('npm '+npm.error+npm.detail);
const git=await marketInvoke({},c,'install',{url:'https://dshopen.org/index.json',name:'dsh-plugin-session-file-purge'});out.git=git.ok;if(!git.ok)throw Error('git '+git.error+git.detail);
const after=await marketInvoke({},c,'query',{url:'https://cdn.jsdelivr.net/npm/dsh-plugin-catalog/index.json',q:'dsh-tg-bridge',limit:1});out.status=after.plugins[0]?.status;if(out.status!=='installed')throw Error('status '+out.status);
console.log('MARKET '+JSON.stringify(out));
J
sudo cp /tmp/m.mjs root/tmp/m.mjs
sudo timeout 900 chroot root /usr/bin/env -i HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin LD_PRELOAD=/opt/hoshino/libandroid-compat.so /usr/bin/node /tmp/m.mjs | tee market.log
ok=false; grep -q '^MARKET ' market.log && ok=true
[ -n "$1" ] && echo "{\"market\": $ok, \"detail\": $(grep '^MARKET ' market.log | cut -c8- || echo null)}" > "$1"
$ok
