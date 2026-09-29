# AGENT.md — 开发规则

给在本项目中工作的 AI 代理与开发者的规则手册。改动前先读完本文;违反这些规则的代码即使能跑也不允许合入。

## 项目定位

- 本项目是「Video Downloader」的**安卓 WebView 壳工程**,把单文件 H5 应用(`app/src/main/assets/index.html`)打包为 APK。
- 本项目**参考** galaxy-downloader 项目搭建,页面源码(`index.html` / `css` / `js` / `img`)仍在上游维护;壳(Java/资源/签名/构建)只在这里改。出处说明见 `README.md`。
- 纯 Java 壳,**禁止引入 Kotlin / Cordova / Capacitor / 任何 native 库**(保持 universal APK,一套产物通吃 ARM)。

## 硬性架构约束(踩过坑的,不许动)

1. **必须用 WebViewAssetLoader 加载页面**(`https://appassets.androidplatform.net/assets/…`)。
   禁止改回 `file://`:file 源下 IndexedDB / localStorage / fetch 全部不可用,页面会静默坏掉。
2. **系统栏 insets 由页面接管,容器只给键盘留 padding**。
   容器只按 IME 高度 pad(`ime.bottom`),状态栏/导航栏的 inset 交给页面:原生用 `injectSafeInsets()` 把 `bars.top` / `bars.bottom` 写成页面的 `--safe-t` / `--safe-b` 内联 CSS 变量。页面顶栏于是能一路画到状态栏底下(内容用自己的 padding 下移),底栏背景也铺到屏幕最底——这就是「别的软件」的边到边观感。
   **`getInsets()` 返回的是物理像素,页面吃的是 CSS 像素**(本页 `initial-scale=1`,1 CSS px = 1dp),所以 `pushSafeInsets()` 里必须除以 `displayMetrics.density`。漏掉这一步,顶栏会被撑成屏幕密度倍(约 2.7 倍)高——状态栏下面是好大一片空白,而不是一条状态栏。
   **不要改回把系统栏 inset pad 在容器上**:那样状态栏那块露的是容器背景色,和页面顶栏之间会出现一条色差带(旧版硬编码的暖白 `#FAF9F5` 就是这么暴露的,暗色模式下尤其刺眼),而且页面永远画不到状态栏底下。
   **也不要 pad WebView 自身**——硬件加速下会被忽略(Android 已知怪癖,底部导航会被系统键遮挡)。
3. **`--safe-t` / `--safe-b` 在壳内只有一路来源,不要再写 UA 检测把它们置 0**(index.html 头部原来的 `wv` 脚本已删)。
   壳内走原生注入的内联自定义属性;浏览器与未来 iOS 端没有注入,自动回落 `:root` 的 `env(safe-area-inset-*)`。两条路只会有一条生效(内联样式优先于 `:root`),所以不会双重留白。这两个变量名同时被原生注入和 CSS 消费,改名等于两处一起断。
4. **下载必须走 `AppBridge.download(url, filename)` 原生桥**。
   禁止改回 `<a download target="_blank">`:WebView 里对无后缀的 CDN 地址(抖音视频直链)不可靠,会静默失败。进度由原生 `window.__nativeDownload.{start,progress,done}` 事件驱动,**禁止使用假进度(setInterval 动画)冒充真实进度**(浏览器预览回退路径除外,且必须被原生 start 事件接管)。点击下载按钮后立即显示不定进度动画(按钮底边的细进度条来回扫描,不是假百分比),由原生事件接管:total>0 显示真实百分比,total≤0 保持不定进度。
5. **剪贴板读写必须优先走 `AppBridge.readClipboard / writeClipboard`**。
   `navigator.clipboard.readText` 在 MIUI 等国产 ROM 的 WebView 里直接被拒,与系统权限无关。浏览器回退路径保留。
6. **AppBridge 的域名守卫(`appassets.androidplatform.net`)不允许删除**。
   JS 桥对所有页面可见,守卫防止外部页面调用桥读写剪贴板/触发下载。
7. **桥回调运行在后台线程**:任何 WebView / UI 调用必须先 `mainHandler.post(...)`,禁止在 `@JavascriptInterface` 方法内直接操作 WebView。
8. **原生轮询周期 600ms;STATUS_PENDING 与 STATUS_RUNNING 都必须发 progress 事件**,进度未知(total≤0,含 PENDING 排队——上游限流时可能持续数分钟)时页面显示不定进度动画,而不是停在 0% 或冻结。
9. **不要使用隐藏 API**:公开常量是 `DownloadManager.COLUMN_MEDIA_TYPE`(不存在 `COLUMN_MIMETYPE`,写它无法编译)。完成任务的「打开查看」走 **FileProvider**:从 `COLUMN_LOCAL_URI` 读取实际文件路径(仅作路径用,禁止把 file:// 拼进 Intent——API 24+ 直接抛 FileUriExposedException)→ `androidx.core.content.FileProvider` 的 content URI + `FLAG_GRANT_READ_URI_PERMISSION`。`content://downloads/my_downloads/<id>` 只作兜底:实测部分系统播放器(AOSP MovieActivity)经 downloads provider 读不了同一文件(file:// 直开能播),Android 15 模拟器必现,别再当软解问题。
10. **下载目录按媒体类型分流**:视频存 `Movies/Video Downloader/`、音频存 `Music/Video Downloader/`(相册必扫的媒体集合,保证系统相册可见),其余存 `Download/Video Downloader/`。
11. **`usesCleartextTraffic` 必须为 true**:上游 API 会返回 http:// 直链(原画字段),DownloadManager 以发起应用的身份执行请求并执行该应用的明文策略,false 时 http 任务秒挂(reason=400)。
12. **上游返回 200 + text/* 或 application/json 时判定为坏内容**(限流/过期链接的错误体):删除任务并提示失败,防止错误体被存成假 .mp4。此外 Content-Type 不可信(有代理以 200 + application/octet-stream 返回错误体),完成任务后必须做**文件头魔数校验**(ftyp/EBML/RIFF/ID3/OggS/fLaC/FLV 及常见图片头),非已知媒体容器签名的一律判坏内容删除。
13. **历史页「打开下载目录」走 `AppBridge.openDownloadDir` 原生桥**:原生按 Movies→Music→Download 的 `Video Downloader` 子目录依次尝试 DocumentsUI 目录 Intent,都不行回退 `DownloadManager.ACTION_VIEW_DOWNLOADS`;浏览器无桥回退为 toast 提示。
14. **下载文件名里禁止出现 `#`**:DownloadManager→MediaStore 的目标路径管线会把 `#` 当 URI fragment 起始符,`#` 起的全部内容(含拼好的扩展名)被截断,落盘成无后缀文件(打开时拿不到 MIME,无法播放)。页面在拼接扩展名前先把标题里的 `#话题` 剔除;原生 `enqueueDownload` 兜底把 `#` 替换为空格,替换后为空则回退 `video.mp4`。
15. **下载任务栏(后台任务)**:页面底部常驻任务条由真实事件驱动(`__nativeDownload.{start,progress,done,cancelled}`),显示文件名/大小(cur/total)/百分比,关闭解析弹窗后任务照跑。`AppBridge.cancelDownload(url)` 取消 DownloadManager 行;**「暂停」=取消行(页面标记 paused),「继续」=同名重新入队从零开始**——DownloadManager 公开 API 无真暂停,不要假装能断点续传。任务会话级,应用重启即清(完成件在相册不受影响)。下载真正开始后,解析弹窗的提示条(`#result-notice.is-active`)改为「已开始下载。可以关闭本窗口,进度见底部任务条。」并保持可关闭(关闭只 `stopPreview`,不影响下载);**进度归属底部任务条,不要写成「在历史页查看进度」**——历史列表只记录完成态(`已下载` 徽章),写成历史会误导用户。

## 页面更新流程

H5 源码在上游 galaxy-downloader 项目维护,**不要直接改本项目的 `assets/index.html` 再单向漂移**。页面已拆分:`index.html`(标记)+ `css/style.css` + `js/shared.js`(全局 `$`/转义 + `window.App` 命名空间)+ `js/download.js`(原生桥事件/任务栏/进度条/doDownload)+ `js/app.js`(解析/渲染/历史/tab),同步必须整树拷贝:

```bash
# <upstream> = 上游 galaxy-downloader 的检出目录,按本机替换
cp <upstream>/index.html app/src/main/assets/index.html
cp -r <upstream>/css <upstream>/js <upstream>/img app/src/main/assets/
./gradlew :app:assembleDebug
```

改完页面必须跑 JS 语法检查(替代品:node 一行脚本 `new Function(scriptBody)`),有语法错误整个壳会白屏。

## 构建命令

```bash
# 需要 JDK 17;依赖缓存位置可用 GRADLE_USER_HOME 覆盖(不设则用默认 ~/.gradle)
./gradlew :app:assembleDebug
# 产物: app/build/outputs/apk/debug/Video Downloader.apk
```

- 构建需要 JDK 17:设 `JAVA_HOME`,或在 `gradle.properties` 里写 `org.gradle.java.home`。

- Gradle wrapper 8.11.1,distributionUrl 指向腾讯镜像;Maven 走阿里云镜像(见 `settings.gradle.kts`),不要删镜像配置。
- `local.properties` 不入库(被 .gitignore 排除),新机器自建 `sdk.dir=...`。
- APK 是 universal 包(无 native 库),ARM 全系可装;`applicationId` 固定 `com.galaxy.downloader`(覆盖升级旧版),改包名等于放弃升级兼容,需用户明确要求才能改。
- 版本号在 `app/build.gradle.kts` 的 `versionCode / versionName`,对外交付前递增。
- **对外交付一律用 release 包**(`./scripts/build.sh release`)。debug 包由公开的 `Android Debug` 密钥签名、且合并清单里带 `android:debuggable="true"`,设备安全检测会直接判「调试版本 / 存在风险」。
- release 签名读仓库根的 `keystore.properties`(**不入库**),其中 `storeFile` 相对仓库根解析;该文件缺失时 release 产出未签名包,而不是让构建失败(保证新克隆仍可 `assembleDebug`)。**keystore 丢失 = 无法再给已发布应用做覆盖升级**,别放进仓库,也别把口令写进任何受版本控制的文件。
- 换签名密钥后必须先卸载旧版本才能安装,Android 不允许跨密钥覆盖安装。

## 调试手段

- **浏览器预览必须走本地静态服务器**(页面拆分后有相对路径的 css/js/img,预览面板把 HTML 当字符串注入会全部断链):本机 `.claude/launch.json` 配了 `page-preview`(python http.server :8123,根目录指向上游 galaxy-downloader 的检出目录,路径按本机改;该文件不入库)。壳内不受影响(WebViewAssetLoader 正常解析相对路径)。

- debug 构建已开启 WebView 远程调试:
  ```bash
  adb forward tcp:9333 localabstract:webview_devtools_remote_$(adb shell pidof com.galaxy.downloader)
  # Node ≥22 用全局 WebSocket 连 /json 返回的 webSocketDebuggerUrl 跑 Runtime.evaluate
  ```
- **后台标签节流陷阱**:WebView 页面在后台时 rAF 冻结、渲染暂停。页面内逻辑验证要用 `el.dispatchEvent(new Event('scroll'))` 同步触发,不要依赖 rAF/异步事件回调。壳里的滚动监听也因此**不用 rAF 节流**,保持现状。
- **模拟器专属坑(不要当代码 bug 追查)**:Android 15 AOSP 镜像里 `com.android.providers.downloads` 默认 `enabled=0`,且 captive portal 探测地址(google.com)被墙 → DownloadManager 所有任务永远 PENDING。真机无此问题。真机 MIUI 上读剪贴板会弹系统提示,属正常。

## 交付前检查清单

1. `assets/index.html` 与上游 `galaxy-downloader/index.html` 一致(或有意领先)。
2. JS 语法检查通过;模拟器安装后实际打开截图确认(不是只看 BUILD SUCCESSFUL)。
3. `aapt dump badging` 确认 `application-label` 正确。
4. 改过图标时:源图在 `art/icon-source.png`,按多密度重生成 mipmap(参考对话中的 make-icon.ps1 做法),自适应前景 + 同色背景组合不可拆。
5. 新增任何 JS↔原生交互,必须同时给出浏览器无桥回退路径(预览模式可用)。

## 已知边界(如需修复,先改这里再动代码)

- 浏览器回退路径下的下载没有真实进度反馈(真下载只发生在壳内),该路径保留占位动画。
- 下载地址时效性依赖上游 API;`/api/download` 上游限流时任务会长时间 PENDING(页面此时显示不定进度动画),限流错误体由壳侧坏内容守卫拦截。
- 坏内容任务 `dm.remove` 后,错误体文件可能因 MediaStore 对目标名的改名而残留(几十字节、媒体库不显示),属已知边界。
- HLS(m3u8)流 WebView 不保证可播;多 P 视频暂无切换 UI。
