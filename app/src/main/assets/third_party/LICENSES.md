# 第三方组件与许可证通知

本文件随 APK 一并打包，供终端用户查阅。

本项目自身代码适用 MIT License（见根目录 LICENSE）。
本应用在运行时包含下列第三方组件，其版权归各自作者所有，
并按各自许可条款使用。

---

## Google ZXing — core 3.5.3

- 用途：生成二维码（上传配对码）。
- 坐标：`com.google.zxing:core:3.5.3`
- 项目主页：https://github.com/zxing/zxing
- 许可：**Apache License, Version 2.0**
- 许可全文：https://www.apache.org/licenses/LICENSE-2.0

ZXing 是 Google 开发的开源条码/二维码库，本项目只使用了其中的
`core` 模块（`QRCodeWriter`），未使用任何图形渲染后端。
zxing 项目同时提供 Apache-2.0 之外的独立分发选项，
本项目按标准 Apache-2.0 条款使用。

---

## Noto Serif SC（思源宋体）

- 用途：应用内中文宋体显示。
- 许可：**SIL Open Font License, Version 1.1**
- 许可全文：见 `fonts/OFL.txt`

字体文件随 APK 打包，与应用代码的 MIT 许可相互独立。

---

## Unsplash 照片

- 用途：内置壁纸。
- 许可：**Unsplash License**（不适用本项目的 MIT 许可）
- 逐张署名、原始页面与处理方式见仓库内 `docs/WALLPAPERS.md`，
  同一条款也记录在 APK 的 `assets/wallpapers/UNSPLASH-NOTICE.txt`。

---

如需本应用的完整来源清单，请查阅仓库中的 `NOTICE.md`。