# M2 体积与资源

- 双 ABI 分包；每包只含自身 proot/native ABI 和对应 rootfs。
- 原 dsh 及必要传递依赖按锁文件闭包选入；保留运行所需 YAML 代码和原生依赖，源码/文档过滤不改上游逻辑。
- ffmpeg、7zip、ImageMagick、Python 文档工具、编译工具链在 proot 按需下载，不塞入主 APK。
- 主 APK 带受限的公开源码工作副本 ZIP，排除密钥、APK、node_modules、构建缓存及大型历史验证数据。
- 自身配置快照和工作区检查点各保留最多 20 份并有约 512 MiB 的清理预算；旧 APK 最多三个版本。检查点单次超过 512 MiB 会拒绝。
- 文件保存/下载/ZIP 采用流式 I/O。正文网页 3 MiB、PDF 100 MiB、单附件 1 GiB、更新 APK 300 MiB 上限均显式报错。

最终 APK 大小与 SHA256 由 `tools/package-m2.py` 从真实产物计算。参考构建曾产生约 74.50/77.33 MB APK，但该轮签名门槛失败，不能作为发布产物；交付以最终 `m2-apk-sizes.json` 为准。

工具来源：[Alpine 7zip](https://pkgs.alpinelinux.org/packages?branch=v3.23&name=7zip)、[Poppler 工具](https://pkgs.alpinelinux.org/package/v3.23/main/x86_64/poppler-utils)。它们作为独立程序运行，不链接到主 Android 应用。
