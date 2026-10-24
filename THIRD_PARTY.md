# 第三方组件与来源

主项目 MIT；第三方代码保留原许可，不将 GPL/LGPL 组件重新授权为 MIT。用户提供的 UI ZIP 来源及摘要见 `docs/m1-ui-origin.json`。

| 组件 | 本工程版本 / 来源 | 许可与使用方式 |
|---|---|---|
| DeepSeek Harness | @deepseek-ai/dsh 0.2.1-rc.2，源码提交 687f282e8625c6b8d11ff58211a90524be2e4317 | MIT；原版 loop、模型适配、工具与 JSONL 持久化，未修改实现 |
| Cordis | @deepseek-ai/cordis 4.0.5，完整树由 node-host/package-lock.json 固定 | MIT |
| Kotlin / kotlinx.coroutines | 2.2.20 / 1.10.2 | Apache-2.0 |
| AndroidX Compose / Material 3 | BOM 2025.04.01 | Apache-2.0 |
| AndroidX Activity / Lifecycle / Core | 1.10.1 / 2.9.0 / 1.16.0 | Apache-2.0 |
| AndroidX Navigation Compose / SplashScreen | 2.9.5 / 1.2.0 | Apache-2.0；[Navigation](https://developer.android.com/jetpack/androidx/releases/navigation)、[Core](https://developer.android.com/jetpack/androidx/releases/core) |
| OkHttp / Commons Compress | 4.12.0 / 1.27.1 | Apache-2.0 |
| XZ for Java | 1.10 | 0BSD；纯 Java 解压 |
| Shizuku API / Provider | 13.1.5 | MIT；[官方源码和接入文档](https://github.com/RikkaApps/Shizuku-API)，安装、Binder 状态、授权检测及经审批的独立 UserService 系统命令 |
| Node | Alpine nodejs 24.18.1-r0 | MIT 等独立通知；官方 APK 与构建用头文件的 SHA256 见 rootfs/node-apk-lock.json |
| node-pty | 1.2.0-beta.15 | MIT；为目标 Linux musl ABI 从源码编译，未使用桌面预编译 PTY |
| Sharp | 0.35.5 | Apache-2.0；原版 dsh 图片附件使用 |
| @img/sharp-libvips-linuxmusl-* | 1.3.4 | LGPL-3.0-or-later；独立 Node 进程内动态库，保留包内许可 |
| proot | termux/proot ee6543334a67261d19e7023ae5eb30af5a7ad4f3 | GPL-2.0，独立可执行程序，不链接主程序；[完整来源](https://github.com/termux/proot/tree/ee6543334a67261d19e7023ae5eb30af5a7ad4f3)和校验见 rootfs/proot-source-lock.json |
| talloc | 2.5.0 | LGPL-3.0-or-later；独立 proot 的动态依赖；[源码](https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz) |
| libandroid-shmem | 0.7 | BSD-2-Clause；[源码](https://github.com/termux/libandroid-shmem/tree/v0.7) |
| Alpine / musl / BusyBox / bash / coreutils / git / curl / ripgrep | Alpine 3.23 固定基础镜像与官方包 | 各包独立 MIT、BSD、GPL、LGPL、curl、Unlicense 等许可；以实际包数据库 `/lib/apk/db/installed`、包内通知及 [Alpine 包来源](https://pkgs.alpinelinux.org/packages?branch=v3.23) 为准 |

完整 npm 解析树在 `node-host/package-lock.json`。仅裁剪部署文件，不改 dsh；所选 npm 包中的 LICENSE/COPYING/COPYRIGHT/NOTICE 保留，APK 中 proot、talloc 与 shmem 的版权文件按 ABI 打包。SDK 依赖使用各自的官方开源制品，不引入 Google Play Services、Firebase、广告或统计 SDK。

proot 的完整来源归档、NDK 版本与构建步骤在 `rootfs/build-proot.py` 和锁文件中。M1 的 PTY 编译步骤与图片二进制来源在 `rootfs/Dockerfile`，构建阶段 Python/编译器和头文件不作为手机运行依赖保留。本文记录已选择的依赖，运行验证状态以 `docs/m1-report.md` 为准。
