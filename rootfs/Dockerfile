FROM alpine:3.23@sha256:ff7f0c727dd796aa36787bfcd9a64bf35a6ce72a73451b3359fde849b6703d63 AS compatibility
RUN apk add --no-cache gcc musl-dev
COPY rootfs/android-compat.c /build/android-compat.c
RUN cc -shared -fPIC -O2 -Wall -Wextra -Werror -o /build/libandroid-compat.so /build/android-compat.c

FROM alpine:3.23@sha256:ff7f0c727dd796aa36787bfcd9a64bf35a6ce72a73451b3359fde849b6703d63
ARG NODE_ARCH
COPY rootfs/node-apk/node-${NODE_ARCH}.apk /tmp/node.apk
COPY rootfs/node-apk/node-dev-${NODE_ARCH}.apk /tmp/node-dev.apk
COPY rootfs/node-apk/node-libs-${NODE_ARCH}.apk /tmp/node-libs.apk
RUN for attempt in 1 2 3; do apk add --no-cache /tmp/node.apk npm=11.11.0-r0 libstdc++ ca-certificates bash coreutils git curl ripgrep && rm /tmp/node.apk && exit 0; done; exit 1
WORKDIR /opt/hoshino/node-host
COPY node-host/package.json node-host/package-lock.json ./
RUN npm ci --omit=dev --omit=optional --ignore-scripts --no-audit --no-fund
RUN npm install --no-save --omit=dev --omit=optional --ignore-scripts --no-audit --no-fund \
    @deepseek-ai/node-addon-system-linux-${NODE_ARCH}@0.1.2 \
    @img/sharp-linuxmusl-${NODE_ARCH}@0.35.5 @img/sharp-libvips-linuxmusl-${NODE_ARCH}@1.3.4
RUN apk add --no-cache --virtual .pty-build python3 make g++ /tmp/node-dev.apk /tmp/node-libs.apk \
    && npm rebuild node-pty --build-from-source --nodedir=/usr \
    && apk del .pty-build npm && rm /tmp/node-dev.apk /tmp/node-libs.apk \
    && node -e 'if(process.versions.node!=="24.18.1")process.exit(1)' \
    && node -e 'const p=require("node-pty").spawn("/bin/bash",["--noprofile","--norc","-c","printf HOSHINO_PTY_OK"],{cols:80,rows:24,cwd:"/tmp"});let s="";p.onData(d=>s+=d);p.onExit(e=>{if(e.exitCode!==0||!s.includes("HOSHINO_PTY_OK"))process.exitCode=1});setTimeout(()=>process.exit(1),15000).unref()' \
    && node -e 'require("sharp")({create:{width:2,height:2,channels:3,background:"white"}}).png().toBuffer().then(b=>{if(b.length<10)process.exitCode=1})'
COPY node-host/dist ./dist
COPY --from=compatibility /build/libandroid-compat.so /opt/hoshino/libandroid-compat.so
RUN mkdir -p /workspace /root/workspace /root/.hoshino /home/hoshino && rm -rf /root/.npm /usr/share/man /usr/share/doc /usr/share/locale /usr/include /var/cache/apk/* /build
WORKDIR /workspace
