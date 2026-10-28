#!/bin/bash
# usage: boot.sh <apk-url> <abi>
set -x
curl -sL -o app.apk "$1"; unzip -o -q app.apk "assets/kernel/$2.txz"
mkdir -p root && sudo tar -xJf assets/kernel/$2.txz -C root
for d in dev proc sys; do sudo mkdir -p root/$d; sudo mount --bind /$d root/$d; done
sudo mkdir -p root/root/workspace root/home/hoshino root/root/.hoshino
sudo cp /etc/resolv.conf root/etc/resolv.conf
python3 cb.py > cb.log 2>&1 & sleep 1
echo '{"callbackPort":47123,"callbackToken":"t","workspace":"/root/workspace","home":"/home/hoshino","selfHome":"/root/.hoshino","safeMode":false}' > boot.json
( sudo timeout 240 chroot root /usr/bin/env -i HOME=/root PATH=/usr/sbin:/usr/bin:/sbin:/bin DSH_TELEMETRY_DISABLED=1 LD_PRELOAD=/opt/hoshino/libandroid-compat.so /usr/bin/node /opt/hoshino/node-host/dist/node-host/src/main.js < boot.json > node.log 2>&1; echo "EXIT $?" >> node.log ) &
for i in $(seq 1 240); do [ -f /tmp/ready ] && break; sleep 1; done
sleep 5
echo "==== waited ${i}s"; cat cb.log; echo "==== node"; cat node.log | tail -80
[ -f /tmp/ready ]
