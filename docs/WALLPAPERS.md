# 内置壁纸与许可

默认打包九张壁纸：四张按项目所有者要求生成的壁纸，以及五张 Unsplash 照片。
下表五张照片的原页面已核对作者与「Free to use under the Unsplash License」标记。

图片遵循 [Unsplash License](https://unsplash.com/license)，不属于项目代码的 MIT 授权。可免费复制、修改及分发用于本桌面；不得将未作显著修改的照片单独出售，或汇编成与 Unsplash 相似或竞争的服务。

**开发者自用的收藏壁纸不会随源码分发。** `.gitignore` 精确列出了上面五个文件名，
往 `bundled/` 里新增的图片默认被忽略，不会误提交；`catalog.json` 同步收录全部条目。
想换成自己的照片，用 `scripts/prepare_wallpapers.py` 生成到被忽略的 `local/` 目录即可。

| 预览 | 桌面名称 | 摄影师 | 原始页面 |
| --- | --- | --- | --- |
| ![雾中雪岭](../app/src/main/assets/wallpapers/bundled/rSYsP5TlEAk-thumb.jpg) | 雾中雪岭 | [Arina Avdzhian](https://unsplash.com/@yallwacky) | [Unsplash](https://unsplash.com/photos/a-black-and-white-photo-of-a-snow-covered-mountain-rSYsP5TlEAk) |
| ![雨林山路](../app/src/main/assets/wallpapers/bundled/hqzyUL3M21k-thumb.jpg) | 雨林山路 | [Geio Tischler](https://unsplash.com/@oww) | [Unsplash](https://unsplash.com/photos/a-winding-road-in-the-middle-of-a-forest-hqzyUL3M21k) |
| ![热带绿叶](../app/src/main/assets/wallpapers/bundled/4axNezh03UA-thumb.jpg) | 热带绿叶 | [Łukasz Rawa](https://unsplash.com/@lukasz_rawa) | [Unsplash](https://unsplash.com/photos/green-leaves-and-foliage-create-a-dark-and-lush-scene-4axNezh03UA) |
| ![山间彩虹](../app/src/main/assets/wallpapers/bundled/vwFvhJf6u_I-thumb.jpg) | 山间彩虹 | [Pascal Debrunner](https://unsplash.com/@debrupas) | [Unsplash](https://unsplash.com/photos/a-rainbow-arches-over-a-majestic-mountain-landscape-vwFvhJf6u_I) |
| ![流光花影](../app/src/main/assets/wallpapers/bundled/JtF_FvXVQQI-thumb.jpg) | 流光花影 | [Patrick Shaun](https://unsplash.com/@xiexianghua) | [Unsplash](https://unsplash.com/photos/a-blurry-photo-of-a-bunch-of-flowers-JtF_FvXVQQI) |

五张原图合计 18,776,212 bytes；显示副本与缩略图共 1,923,618 bytes，缩减 89.8%。

处理：显示副本最长边 1920 像素、JPEG quality 85；缩略图 320 × 180、quality 78；启用 optimize/progressive。原图不变，副本不写入 EXIF 或 GPS 信息。默认每 5 分钟顺序轮播，固定选择功能不变。

作者与来源也记录在 APK 内的 `assets/wallpapers/catalog.json`；资源大小、尺寸及校验值见 [媒体索引](../app/src/main/assets/wallpapers/bundled/MEDIA-INDEX.md)。

## 新增纯色与玻璃海

纯黑（#000000）、纯蓝（#0000FF）、天空蓝（#87CEEB）均为 3840 × 2160 程序生成 PNG；
玻璃海为 OpenAI ImageGen 生成的 1672 × 941 PNG，保留原始分辨率，不标为 4K。
四张排在默认列表前面，原五张照片保留，原有选中 ID、轮播偏好和移除记录不重置。

这四张不使用 Unsplash 授权，来源与分发说明见
[生成素材说明](../app/src/main/assets/wallpapers/generated/GENERATED-NOTICE.md)。
内置 4K 图片运行时采样到最长边不超过 1920，缩略图为 320 × 180，避免额外占用电视内存。

壁纸面板、屏保候选列表和未配置屏保时的默认播放列表共用同一个 catalog；
默认屏保同时遵守内置壁纸移除记录。已手动选择的屏保文件列表保持原选择，不自动插入新图。
