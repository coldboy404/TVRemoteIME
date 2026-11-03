# 小盒精灵（TVRemoteIME）

电视盒子的管理精灵，可跨屏远程输入、跨屏远程控制盒子、远程文件管理、HTTP/RTMP/MMS网络视频直播、ED2K/种子文件的视频文件边下边播、视频投屏（DLNA）

> 本项目 Fork 自 [newPersonKing/TVRemoteIME](https://github.com/newPersonKing/TVRemoteIME)

---

## 更新日志（Fork 版本）

### 2026-11 优化（v2.1.6）

安全修复：

- 堵住 ADB shell 命令直通：此前远程文本只要以 `shell:` 开头就会被当作原始 ADB shell 命令执行，局域网内任何人可借 `POST /text` 在盒子上执行任意命令。现远程文本一律只走 `input text`（并修复了转义顺序错误、补 `$`/反引号转义），原始命令只保留给内部鼠标回退的强类型入口（参数均为整数）。
- Web 服务增加 Host/Origin 校验，挡住恶意网页的跨站请求伪造与 DNS rebinding（浏览器打开恶意页面即可打盒子的经典攻击面）；无 Origin 的局域网直连客户端不受影响。注意这只是兜底，服务本身仍无登录鉴权（见文末说明）。
- 更新弹窗显示失败时不再无条件自动安装 APK（旧逻辑等于未经用户确认就安装）；待装 APK 除包名外还要校验签名证书与已装应用一致，version.json 若提供 `apkSha256` 也会一并校验。
- 文件删除/剪切/复制拒绝以存储根目录为源，防止一条 `paths=/` 请求递归删光整个外置存储；`/runSystem` 只允许打开系统设置类动作，不再能启动任意 Intent。
- 移除全局信任所有 HTTPS 证书的 `HTTPSTrustManager`：此前自更新检查会关闭全进程的证书与主机名校验，同一局域网/网络路径上的攻击者可伪造版本信息并推送恶意 APK。现在恢复系统默认校验，仅允许 HTTPS，并给请求加了连接/读取超时；下载失败不再留下半截文件冒充完整包。
- `SecurityManager` 路径穿越防护改成按路径段拦截 `..`（含多重 URL 编码），并修复基目录前缀判断可被同前缀兄弟目录（如 `/sdcard_evil`）绕过的问题。

功能修复：

- APK 安装改用 FileProvider 的 content URI：targetSdk 24+ 下原来的 `file://` URI 会抛 `FileUriExposedException`，导致自更新与 Web 上传的自动安装在较新的盒子上静默失败。
- 应用图标接口不再把 `loadIcon()` 强转为 `BitmapDrawable`：Android 8+ 的自适应图标会让 `/icon/` 请求崩溃，现改为通用 Drawable 光栅化。
- 通过 Web 启动应用时补 `FLAG_ACTIVITY_NEW_TASK`，解决从服务上下文启动无此标志直接失败的问题。
- 文件下载与电视直播源接口改用真实文件长度作为响应长度（此前误用 `InputStream.available()`，大文件会被截断）；目录列表为空/不可读时返回 404 而不是崩溃，并修复 `parent` 计算的字符串引用比较等空指针隐患。
- 「清除缓存」此前误删应用文件目录且从不清理临时目录（变量写错），已修复；文件复制改用 try-with-resources 并加大缓冲，目录遍历补空指针防护。
- 获取本地 IP 时对无 WiFi 的纯网线盒子补空指针防护；清单移除无用的 `MOUNT_UNMOUNT_FILESYSTEMS` 权限、关闭 `allowBackup`、开机广播接收器改为不导出。
- 补 `QUERY_ALL_PACKAGES`（否则 Android 11+ 包可见性过滤会让 Web 端应用列表几乎为空）与 `REQUEST_INSTALL_PACKAGES`（Android 8+ 安装未知应用所需）权限；广播里后台启动服务加异常兜底，避免 Android 8+/12+ 后台启动限制让接收器崩溃。
- DLNA 服务绑定改为统一管理并在停止时解绑（此前每次启动都新建连接且从不解绑，反复启动/改名会泄漏绑定并叠加渲染器）；ADB 发送队列改为锁内入队、`running` 改 volatile；删除无人调用的截屏死代码 `ScreenShotActivity`（含清单登记）。

工程：

- 新增 GitHub Actions 构建流水线（`.github/workflows/build.yml`）：推送/PR 时自动做 Web 脚本语法检查并构建 Debug 与 Release APK。

> 已知未改（需要产品决策）：控制服务本身仍无登录鉴权，明文 HTTP 且监听所有网卡——这是同类局域网遥控工具的常见取舍，本轮只加了上面的兜底防护。若盒子所在网络不可信（公共/访客 WiFi、端口映射到公网），请勿开启本服务；后续可考虑加配对令牌（二维码带 token）方案。

### 2026-01 更新

#### 新增功能：触控板/鼠标控制（基于辅助功能服务）

使用 Android AccessibilityService 实现远程触控板功能

**API 端点：**

| 端点              | 方法 | 说明                                 |
| ----------------- | ---- | ------------------------------------ |
| `/mouse/status` | GET  | 获取服务状态和鼠标位置               |
| `/mouse/move`   | POST | 移动鼠标 (参数: dx, dy)              |
| `/mouse/click`  | POST | 点击 (参数: button - 0=左键, 1=右键) |
| `/mouse/scroll` | POST | 滚动 (参数: dy)                      |
| `/mouse/show`   | POST | 显示光标                             |
| `/mouse/hide`   | POST | 隐藏光标                             |

**使用方法：**

1. 安装应用后，进入系统设置 → 辅助功能
2. 找到"小盒精灵"并启用
3. 通过网页控制端即可使用触控板功能，屏幕上会显示鼠标光标

#### 构建环境升级，进行了一些现代化改造

- Gradle 升级至 8.13
- Android Gradle Plugin 升级至 8.13.2
- compileSdkVersion 升级至 36
- 修复 Android 5.0+ 显式 Intent 要求的兼容性问题
- 修复 ProGuard 配置兼容性问题

**构建命令：**

```bash
# 构建 Debug 版本（已签名，可直接安装）
./gradlew assembleDebug

# 构建 Release 版本（未签名，需要签名后才能安装）
./gradlew assembleRelease
```

---

# 应用的诞生

自从家里有电视盒子以来，电视收看、电影播放、娱乐小游戏什么的都是直接在盒子里运行，因为电视屏幕比起手机屏幕大，玩起来那效果是手机没法比的，但是在娱乐的过程中也总是有一些不便，比如玩游戏过程中想聊天什么的，在电视盒子里输入文字用遥控器按？只有用过才知道痛苦！外挂物理键盘，可惜很多输入法都不支持物理键盘的同时输入，远远达不到电脑的效果！于是找了很多遥控与跨屏输入的软件，但可惜没有一款是比较理想的，特别家里的一个创维Q+二代盒子，只要一进游戏的聊天界面，不管外面设置了什么跨屏输入法，都会自动切换为厂家自带的百度输入法，非常的可恶！于是就有了自己做一款远程跨屏的输入法，于是这小盒精灵（TVRemoteIME）就这样诞生了…………

# 它能做什么

它能帮助盒子实现跨屏输入，以后在盒子里聊天打字要多快就有多快；它能代替盒子遥控器，用手机，电脑，IPAD摇控盒子都不是问题；它能管理盒子的应用与文件，一键快速启动/卸载应用；它能跨屏安装应用与传送文件到盒子；还能实现HTTP/RTMP/MMS网络视频直播、ED2K/种子文件的视频文件边下边播；还支持DLNA方式的视频投屏，手机、IPAD或者电脑的视频可直接投屏到电视上播放。

# 安装方法

下载最新版本的APK包：https://github.com/kingthy/TVRemoteIME/raw/master/released/IMEService-release.apk

## 一、通过adb命令安装应用  

1、电视盒子开启adb调试

2、电脑通过adb命令连接电视盒子（假如电视盒子的内网ip为：192.168.1.100）
`adb connect 192.168.1.100:5555`
注意,手机要与盒子在同一个WIFI网络(内网网络)  执行 `adb devices`命令显示有device列表，则表示已连接上盒子，可继续下一步

3、通过以下命令安装输入法apk包
`adb install IMEService-release.apk`

4、设置为系统默认输入法
`adb shell ime set com.android.tvremoteime/.IMEService`  

注：如果无法设置为系统的默认输入法，则先启动TVRemoteIME应用后再手动启动服务，如需使用远程输入及远程遥控功能还需要开启盒子的ADB模式。

5、电脑或者手机浏览器访问远程输入法的控制页面
`http://192.168.1.100:9978/`

## 二、通过U盘或者其它方式安装  

1、安装后在盒子应用列表里找到TVRemoteIME的图标点击运行

2、根据应用的提示进行设置即可。

## 视频播放功能说明

1、本地视频文件： 通过传送功能将手机、电脑等控制端的视频文件传送到盒子后会自动播放

2、种子文件：通过传送功能将种子文件传送到盒子后会自动播放种子里的第一个视频文件

3、网络视频（http/rtmp/mms协议的直播或者thunder/ed2k协议的视频）： 直接在网络视频地址框输入视频URL，点击“远程播放”按钮盒子会自动开始播放

注：对于种子文件及非直播的网络视频，本应用采用的是边下边播方式，所以会占用盒子的大量空间（根据视频大小而定），如果盒子的可用空间不够，视频会播放失败。正常播放结束时应用会自动删除边下边播放的缓存文件，或者你可以点击控制界面里的“清除缓存”删除。

## 视频播放控制说明

在视频播放时，点击控制器进行控制，左右键用于控制快进或者快退（非直播情况下可用）、上下键用于选择需要播放的视频文件（播放种子文件且里面包含多个视频时可用），在播放单视频（非种子视频）时长按下键1秒左右会重头开始播放视频、确定键用于暂停或者恢复播放功能。

## 电视直播源列表  

需要更改直接源列表有两种方式：
1、直接在控制端界面修改
2、通过文件管理界面进入“/Android/data/com.android.tvremoteime/files/” 目录，对里面的tv.txt文件进行修改（通过下载与上传）即可。如tv.txt文件不存在，新建即可覆盖默认的直播源，此文件格式为ini文件格式，格式如下：  

<pre><code>[电视台名称]  
源名称1 = 源地址  
源名称2 = 源地址 

[电视台名称]  
源名称 = 源地址  
</pre></code>

## 视频投屏说明

只要支持DLNA协议的播放器（如手机迅雷、爱奇艺等）都可投屏到小盒精灵在电视上播放

## 控制界面示例截图  

注： 输入控制端不需要安装任何APK应用，直接浏览器操作

![示例截图](https://raw.githubusercontent.com/kingthy/TVRemoteIME/master/released/screenshot.png "控制界面示例截图")

![示例截图](https://raw.githubusercontent.com/kingthy/TVRemoteIME/master/released/screenshot_2.png "控制界面示例截图")

![示例截图](https://raw.githubusercontent.com/kingthy/TVRemoteIME/master/released/screenshot_3.png "控制界面示例截图")

![示例截图](https://raw.githubusercontent.com/kingthy/TVRemoteIME/master/released/screenshot_4.png "控制界面示例截图")

# 版本历史

1、 V1.0.0版本
于2018-1-11发布，本应用的第一个版本，实现盒子跨屏输入、远程遥控、APP包管理、文件传送等功能

2、 V1.1.0版本
于2018-2-4发布，增加视频的播放功能，支持播放多种不同网络协议的网络视频及网络直播源，采用边下载边播方式实现，

3、 V1.2.0版本  （完全开源版本）
于2018-3-3发布，增加盒子的文件管理功能、优化了控制端的UI设计、优化了播放器的功能、增加电视频道数据，

4、 V1.2.1版本
于2018-3-5发布，增加手动启动服务功能。

5、 V1.3.0版本
于2018-3-8发布，增加视频播放时可选择调用系统播放器、播放种子文件视频时可在控制端界面选择要播放的视频文件、无法设置为系统默认输入法时通过ADB模式实现遥控及简单输入功能、修复BUG等。

6、 V1.3.1版本
于2018-3-25发布，增加快进调速功能、在文件管理界面增加本地视频文件的播放功能、修复文件传送功能无法播放视频的BUG、修复其它已知BUG。

7、 V1.4.0版本
于2018-4-11发布，APP正式更名为“小盒精灵”，增加视频投屏功能、增加自更新功能、修复其它已知BUG。

8、 V1.4.1版本
于2018-4-30发布，支持更改投屏名称、TV端键盘添加隐藏键、修复已知BUG。

**小盒精灵因抄袭事件，以后不会再维护更新开源代码，也就是说V1.4.1版本属于最后的开源版本**
[关于小盒精灵被抄袭的事件申明] (https://www.v2ex.com/t/452320)

# 引用第三方包/资源说明

1、[NanoHttpd](https://github.com/NanoHttpd/nanohttpd "NanoHttpd")  用于实现HTTP WEB服务

2、[ZXing](https://github.com/zxing/zxing/ "QRCode") 用于实现二维码的输出

3、[悟空遥控](http://www.wukongtv.com/views/input.html "悟空遥控")  非常棒的一款遥控软件。  
注：本软件远程控制端的遥控导航面板设计图和小部分CSS代码参考于它。

4、[AFAP Player](https://github.com/AFAP/Player "AFAP Player") 用于实现视频播放，采用[ijkplayer](https://github.com/Bilibili/ijkplayer "ijkplayer")播放器核心

5、[MiniThunder](https://github.com/oceanzhang01/MiniThunder "MiniThunder") 用于实现视频文件下载功能

6、[AdbLib](https://github.com/cgutman/AdbLib "AdbLib") 用于连接adb服务，在非输入法状态下的实现遥控功能

7、[DroidDLNA](https://github.com/offbye/DroidDLNA "DroidDLNA") 用于实现DLNA视频投屏服务，内部核心采用[Cling](https://github.com/4thline/cling "cling") DLNA库

# 衍生项目

由小盒精灵衍生出来的APP项目
1、[云播投屏](https://github.com/yunbotouping/- "云播投屏")

# 交流QQ群

QQ群号：7149348， 加入QQ群可以分享直播源、反馈问题及建议。

# 赞赏一下

如果您觉得本应用项目对你有帮助，可以赞赏一下作者：）。万水千山总是情，一块十块都是情！

![赞赏二维码](https://raw.githubusercontent.com/kingthy/TVRemoteIME/master/released/reward.png "赞赏二维码")
