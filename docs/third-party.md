# 第三方组件

主项目 MIT。各依赖保留其自身许可，不把 GPL/LGPL 组件重新授权为 MIT。

- DeepSeek Harness / Cordis：MIT，锁定 0.2.1-rc.2 发布树；npm 安装内容保留原样。
- UI 原型提供者文件保留；其中引用的 DeepSeek UI 设计来源按文件声明为 DeepSeek MIT，原型不是 Android 运行时。
- Kotlin、AndroidX Compose、Material、coroutines：Apache-2.0；OkHttp Apache-2.0；Commons Compress Apache-2.0。
- Alpine 官方系统与 Node 包：包含 MIT、BSD、Apache、LGPL 等独立文件许可。打包系统保留 `/usr/share/licenses` 等许可文件。
- proot：GPL-2.0，独立外部程序；源码固定为 [termux/proot ee654333](https://github.com/termux/proot/tree/ee6543334a67261d19e7023ae5eb30af5a7ad4f3)，完整归档 URL 和 SHA256 在 `rootfs/proot-source-lock.json`。`rootfs/build-proot.py` 公开 NDK 版本、完整编译参数、ashmem 单元的缺失头文件兼容与动态依赖名 `libtalloc.so.2` → `libtalloc.so` 调整。正式构建不启用可选的栈偏移诊断。Termux 5.1.107.99 预编译包仅作为已验证失败的对照，最终 APK 使用源码构建结果。
- talloc 2.5.0：LGPL-3.0-or-later，独立 proot 的动态依赖；源码 [talloc](https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz)。未链接入主项目。
- libandroid-shmem 0.7：BSD-2-Clause；源码 [termux/libandroid-shmem](https://github.com/termux/libandroid-shmem/tree/v0.7)。

Termux 原始包 URL、版本及 SHA256 见 `rootfs/termux-lock.json`；包中 copyright 文件在准备资产时随 APK 保存。发布前应同时提供与所分发二进制对应的完整源码归档、构建 recipe 和许可证，不能仅以主项目 MIT 文件替代各自义务。M0 未打正式发布 tag。
