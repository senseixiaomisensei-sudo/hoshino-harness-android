FROM alpine:3.23@sha256:ff7f0c727dd796aa36787bfcd9a64bf35a6ce72a73451b3359fde849b6703d63
RUN for attempt in 1 2 3; do apk add --no-cache nodejs=24.18.1-r0 npm=11.11.0-r0 libstdc++ bash ca-certificates && exit 0; done; exit 1
WORKDIR /opt/hoshino/node-host
COPY node-host/package.json node-host/package-lock.json ./
RUN npm ci --omit=dev --omit=optional --ignore-scripts --no-audit --no-fund
ARG NODE_ARCH
RUN npm install --no-save --omit=dev --omit=optional --ignore-scripts --no-audit --no-fund \
    @deepseek-ai/node-addon-system-linux-${NODE_ARCH}@0.1.2 \
    @img/sharp-linuxmusl-${NODE_ARCH}@0.35.5 \
    @img/sharp-libvips-linuxmusl-${NODE_ARCH}@1.3.4
COPY node-host/dist ./dist
RUN mkdir -p /workspace /home/hoshino && rm -rf /root/.npm /usr/lib/node_modules/npm /usr/share/man /var/cache/apk/*
WORKDIR /workspace
