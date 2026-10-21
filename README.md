# 斯文鱼TV · Sivenyu TV

一个给国内小米电视用的简洁桌面：大字、卡片、壁纸与屏保，以及常用应用的直达入口。

**这是一个二次开发项目。** 代码源头是 [Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)（MIT License），
实际起点是[归藏电视](https://github.com/op7418/guizang-tv)发布的教程与整理包，本项目在此基础上重构了整套界面与媒体管理。
上游作者与原始版权归原作者所有，本项目与归藏电视、cleanyourtv 均无隶属关系。
完整来源链条、改动范围与许可归属见 [NOTICE.md](NOTICE.md)。

## 界面

**「聚焦才展开」的卡片首页。** 桌面常态只显示应用名，不铺满大图；遥控器移动焦点时，
该应用的横幅卡片才淡入浮现，其余卡片轻微压暗。这样一屏能放下十几个应用，
又不会让首页变成一片花花绿绿的图标墙。

- 卡片为自绘 View，四边等弧度圆角，横幅图由 `BitmapShader` 裁切填充。
- 网格列数按应用数量自动切换（≤6 用 3 列 / ≤12 用 4 列 / >12 用 5 列），格子尺寸随之调整。
- 底部 dock 只保留文字，聚焦项才套玻璃胶囊，常态无条状背板。

**应用管理页。** 5 列网格浏览全部已安装应用，勾选要上首页的、调整顺序，长按确认键进入排序模式。

**独立的批量卸载模式。** 从管理页页脚进入，标记与「上首页」的选择完全分开，互不干扰。
卸载走系统 `ACTION_DELETE` 逐个确认，不做静默操作；结果以包是否真的消失来判定，而不是靠返回码。

**壁纸与屏保共用一套媒体库。** 本地图片与视频统一放在 `files/media/`，壁纸和屏保都能用。
内置壁纸可在面板里移除，屏保内容页共享同一份隐藏名单，两边不会打架。
竖拍照片按 EXIF 方向摆正后再显示，可调焦点与缩放。

## 其他功能

- 全应用思源宋体，随包分发，不依赖系统字体。
- 开机自动进入：默认关闭，需在应用内开启并完成一次 ADB 配置，可随时关掉。
- 壁纸固定选择或自动轮播，间隔 1 / 5 / 15 / 30 分钟，离开桌面自动暂停计时。
- 横幅图可在运行期用 U 盘替换，无需重编：`U盘根目录/aptv-banners/包名.png`。
- 不需要账号、不装无障碍服务、不预装或下载视频 App。
- **无遥测、不联网上报**：应用不收集任何数据，也没有后台服务或统计上报。
  但源码声明了 `INTERNET` 权限——用于一个**由用户主动打开**的功能：
  在壁纸/屏保面板里点「从手机上传」，应用会在局域网上临时开一个网页，
  让你用手机浏览器把照片传到电视。**只有你主动打开这个窗口时才会监听端口**，
  窗口关闭即停止。
  同局域网内的其他设备理论上能访问该窗口，因此请在可信网络下使用，
  完成后及时关闭。

## 适配情况

主要在 **Redmi X65（MiTV-MFTP0，Android 9，1920×1080）** 上开发与验证，
已在 **MiTV-MTSU0** 上测试。固件差异较大，其他机型不保证可用。

部分功能（HDMI 直达、开机自启、小米系统设置项）依赖小米特有接口，集中在
[XiaomiControls.java](app/src/main/java/io/github/op7418/sivenyutv/XiaomiControls.java)。
**请不要只加一个机型名就当作适配完成。**

开机自启需要开启应用内开关并设置系统目标，这与「设为默认桌面」是两回事。
已测试的 HyperOS 固件会忽略第三方默认桌面选择，因此本项目不主动接管 Home 键。
需要完整恢复步骤请看 [启用与恢复说明](docs/BOOT-START.md)。

### 已知问题：调节音量没有音量条

桌面为隐藏原电视的系统状态栏和导航栏，采用全屏沉浸式窗口。
代价是**按音量键时看不到音量悬浮条**——音量键在被系统组件消费之前不会到达应用，
应用层拦不到，也就无法自绘音量条。音量调节本身是正常的，只是没有视觉反馈，
需要确认当前音量请在电视的系统设置里查看。

## 安装

从 [Releases](https://github.com/ilps2/sivenyu-tv/releases) 下载 APK，
通过电视支持的本地安装方式安装。也可以在开启电视 ADB 调试、电脑连同一网络后：

```sh
adb connect <电视IP>:5555
adb install -r sivenyu-tv.apk
```

端口与调试方式取决于电视固件。首次连接需在电视上确认调试授权，用完可以关掉。
安装后在应用列表中打开 **斯文鱼TV**。

| 遥控器操作 | 行为 |
| --- | --- |
| 上 / 下 | 在当前区域内移动焦点 |
| 右 | 从应用区进入 dock |
| 左 | 从 dock 返回应用区 |
| 确认 | 打开选中项；管理页中切换勾选 |
| 返回 | 关闭面板；从 dock 回到应用区 |
| Home | 交回系统，不拦截 |

**建议先作为普通应用试用**，不满意直接卸载即可，不需要先禁用原桌面。

## 从源码构建

需要 **JDK 17、Gradle 8.9、Android SDK Platform 34、Build Tools 34.0.0**。

仓库只包含源码，不含 Gradle 发行版，请从[官网](https://gradle.org/releases/)安装，
或用 Android Studio 打开并选择本地 Gradle 8.9。

```sh
git clone https://github.com/ilps2/sivenyu-tv.git
cd sivenyu-tv
gradle :app:assembleDebug
```

发布版签名不写在仓库里。设置以下环境变量后 `assembleRelease` 才会签名，
未设置时产出的是未签名包：

| 变量 | 说明 |
| --- | --- |
| `GUIZANG_KEYSTORE` | keystore 文件绝对路径 |
| `GUIZANG_STORE_PASSWORD` | keystore 密码 |
| `GUIZANG_KEY_ALIAS` | 密钥别名，默认 `guizang-tv` |
| `GUIZANG_KEY_PASSWORD` | 密钥密码，默认同 keystore 密码 |

### 换用自己的壁纸

```sh
python3 -m venv .venv
.venv/bin/pip install Pillow
.venv/bin/python scripts/prepare_wallpapers.py /path/to/your/wallpapers
gradle :app:assembleDebug
```

脚本保留原图，为电视生成最长边 1920 像素的副本与缩略图并去除 EXIF，
输出到 Git 忽略的 `app/src/main/assets/wallpapers/local/`。
重复运行时上一份结果会移到 `.wallpaper-backups/`，避免旧图混进新包。
**请只分发你有权公开使用的图片。**

## 内置照片与许可

代码采用 MIT License，保留上游版权声明。
打包的思源宋体适用 SIL Open Font License 1.1，全文见
[`app/src/main/assets/fonts/OFL.txt`](app/src/main/assets/fonts/OFL.txt)。
内置照片适用 Unsplash License，不适用代码的 MIT 许可。

仓库**不包含**任何第三方产品商标图：横幅需自行制作或在运行期放入 U 盘目录。
用户自行加入的照片、视频不因放入本工程而自动获得 MIT 许可。

## 反馈

欢迎提 Issue，请附电视型号、Android / 固件版本和复现步骤。
**提交前请隐去局域网地址、序列号、账号与任何调试密钥。**

## 来源与致谢

这个项目站在几个人的工作之上，在这里明确说清各自的贡献：

- **[Gathin23/cleanyourtv](https://github.com/Gathin23/cleanyourtv)** —— MIT License，**代码源头**。
  本项目的绝大部分代码来自这里，按 MIT 要求保留了原始版权声明。
- **[归藏电视](https://github.com/op7418/guizang-tv)** —— 提供了面向国内小米电视的**教程与整理后的 APK**，是本次二次开发的实际起点。
  本项目在其 `v0.2.0` 发布包的基础上调试改造，界面与交互随后完全重写。
  感谢归藏电视作者把开源项目整理成了能直接上手的版本，让这个项目有了起步的基础。
  *（本项目与归藏电视无隶属关系，名称、包名与代码均已独立，未使用其商标资源。）*
- **[nthnclrk/CleanFrame](https://github.com/nthnclrk/CleanFrame)** —— Nathan Clark，**界面方向参考**。
  未移植其 Tizen 运行代码，也未附带其照片。

代码 MIT 许可并保留上游署名，完整来源链条见 [NOTICE.md](NOTICE.md)。