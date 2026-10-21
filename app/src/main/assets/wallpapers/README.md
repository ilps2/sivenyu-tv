# 壁纸资源

公开版默认打包 `bundled/` 下的五张 Unsplash 照片，每 5 分钟轮播。
作者、来源与校验值见 `catalog.json`，许可见 `UNSPLASH-NOTICE.txt`。
目录为空时，应用自动回退到五种源码绘制的渐变背景。

自行打包照片请使用项目根目录的 `scripts/prepare_wallpapers.py`。
它将图片和目录写入被 Git 忽略的 `local/`，应用优先读取这个目录。
原图不变；生成副本去除 EXIF、最长边 1920 像素，缩略图 320 × 180。
不要公开发布自己没有再分发权的照片或包含私人信息的素材。
