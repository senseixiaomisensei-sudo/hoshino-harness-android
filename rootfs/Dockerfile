FROM alpine:3.23@sha256:ff7f0c727dd796aa36787bfcd9a64bf35a6ce72a73451b3359fde849b6703d63 AS compatibility
RUN apk add --no-cache gcc musl-dev
COPY rootfs/android-compat.c /build/android-compat.c
RUN cc -shared -fPIC -O2 -Wall -Wextra -Werror -o /build/libandroid-compat.so /build/android-compat.c

FROM alpine:3.23@sha256:ff7f0c727dd796aa36787bfcd9a64bf35a6ce72a73451b3359fde849b6703d63
ARG NODE_ARCH
COPY rootfs/node-apk/node-${NODE_ARCH}.apk /tmp/node.apk
RUN for attempt in 1 2 3; do apk add --no-cache /tmp/node.apk npm=11.11.0-r0 libstdc++ ca-certificates && rm /tmp/node.apk && exit 0; done; exit 1
WORKDIR /opt/hoshino/node-host
COPY node-host/package.json node-host/package-lock.json ./
RUN npm ci --omit=dev --omit=optional --ignore-scripts --no-audit --no-fund
RUN npm install --no-save --omit=dev --omit=optional --ignore-scripts --no-audit --no-fund \
    @deepseek-ai/node-addon-system-linux-${NODE_ARCH}@0.1.2
COPY node-host/dist ./dist
COPY --from=compatibility /build/libandroid-compat.so /opt/hoshino/libandroid-compat.so
RUN mkdir -p /workspace /home/hoshino && rm -rf /root/.npm /usr/lib/node_modules/npm /usr/share/man /usr/share/doc /usr/share/locale /usr/include /var/cache/apk/* /build
WORKDIR /workspace
