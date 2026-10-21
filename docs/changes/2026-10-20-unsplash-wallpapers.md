# 打包五张经核对的 Unsplash 壁纸

- Change-Id: `guizang-tv-20261020-unsplash-wallpapers`
- 执行者：Codex
- 授权：用户通过截图明确选择五张 Unsplash 图片，要求压缩后纳入公开版，排除其他版权不明确的图片。

## 范围与来源

仅加入 rSYsP5TlEAk、hqzyUL3M21k、4axNezh03UA、vwFvhJf6u_I、JtF_FvXVQQI。
五张原页面均已通过浏览器逐张核对作者及 Unsplash License 标记。普通 HTTP 抓取失败没有被视为已核对，最终依据是可见的官方照片页面。

压缩副本及缩略图进入 `app/src/main/assets/wallpapers/bundled/`，本次用户明确授权构成仅针对这五张素材的公开打包范围。原图、其余八张 Wallhaven 图片及私人电视数据未改动、未公开。

图像沿用 Unsplash License，与代码 MIT 许可分开；作者、链接和照片哈希写入 catalog 与文档，许可通知随 APK 打包。Geio Tischler 的照片原页面说明为巴厘岛雨林道路，公开目录使用「雨林山路」命名。

五张原图共 18,776,212 bytes；显示副本和缩略图共 1,923,618 bytes，减少 89.8%。显示副本最长边 1920、JPEG quality 85，缩略图 320 × 180、quality 78，去除 EXIF。

版本更新为 0.1.1 / versionCode 2，沿用 0.1.0 的公开发布签名。桌面、遥控器导航、HDMI 行为及轮播逻辑不变；默认每 5 分钟轮播这五张照片。本次无需改动电视上的私人版本。

## 验证

公开目录检查通过：恰好五张指定照片与五张缩略图，catalog 文件、尺寸、EXIF 与 SHA-256 均吻合。Release 构建、4 项 Java 测试、4 项 Python 测试通过；Android lint 为 0 errors / 7 warnings（与前版相同）。发布前还会检查 APK 的相同素材集合与签名；远端自动构建结果以 GitHub Actions 为准。
