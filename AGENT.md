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
   图文笔记的三条下载通道:`AppBridge.download`(静图,DownloadManager,图片 mime 落 Pictures)+ `AppBridge.saveLivePhoto(still, video, baseName)`(实况,字节级取流合成动态照片,进度事件**按静帧地址回报**,页面任务行必须以静帧地址注册)+ `AppBridge.fetchNoteHtml(url)`(异步抓笔记页,回调 `window.__nativeNote({ok, html})`)。三条通道浏览器预览都无桥,必须保留 `<a download>` / 忽略增强的回退路径。
5. **剪贴板读写必须优先走 `AppBridge.readClipboard / writeClipboard`**。
   `navigator.clipboard.readText` 在 MIUI 等国产 ROM 的 WebView 里直接被拒,与系统权限无关。浏览器回退路径保留。
6. **AppBridge 的域名守卫(`appassets.androidplatform.net`)不允许删除**。
   JS 桥对所有页面可见,守卫防止外部页面调用桥读写剪贴板/触发下载。
7. **桥回调运行在后台线程**:任何 WebView / UI 调用必须先 `mainHandler.post(...)`,禁止在 `@JavascriptInterface` 方法内直接操作 WebView。
8. **原生轮询周期 600ms;STATUS_PENDING 与 STATUS_RUNNING 都必须发 progress 事件**,进度未知(total≤0,含 PENDING 排队——上游限流时可能持续数分钟)时页面显示不定进度动画,而不是停在 0% 或冻结。
9. **不要使用隐藏 API**:公开常量是 `DownloadManager.COLUMN_MEDIA_TYPE`(不存在 `COLUMN_MIMETYPE`,写它无法编译)。完成任务的「打开查看」走 **FileProvider**:从 `COLUMN_LOCAL_URI` 读取实际文件路径(仅作路径用,禁止把 file:// 拼进 Intent——API 24+ 直接抛 FileUriExposedException)→ `androidx.core.content.FileProvider` 的 content URI + `FLAG_GRANT_READ_URI_PERMISSION`。`content://downloads/my_downloads/<id>` 只作兜底:实测部分系统播放器(AOSP MovieActivity)经 downloads provider 读不了同一文件(file:// 直开能播),Android 15 模拟器必现,别再当软解问题。
10. **下载目录按媒体类型分流**:视频存 `Movies/Video Downloader/`、音频存 `Music/Video Downloader/`、**图片存 `Pictures/Video Downloader/`**(相册必扫的媒体集合,保证系统相册可见),其余存 `Download/Video Downloader/`。实况合成件不走 DownloadManager:API 29+ 由 MediaStore(`MediaStore.Images` + `RELATIVE_PATH`)直接落 Pictures,API 26–28 无公开写路径(不上 WRITE 权限),退化为静图 + mp4 两条 DownloadManager 任务。
11. **`usesCleartextTraffic` 必须为 true**:上游 API 会返回 http:// 直链(原画字段),DownloadManager 以发起应用的身份执行请求并执行该应用的明文策略,false 时 http 任务秒挂(reason=400)。
12. **上游返回 200 + text/* 或 application/json 时判定为坏内容**(限流/过期链接的错误体):删除任务并提示失败,防止错误体被存成假 .mp4。此外 Content-Type 不可信(有代理以 200 + application/octet-stream 返回错误体),完成任务后必须做**文件头魔数校验**(ftyp/EBML/RIFF/ID3/OggS/fLaC/FLV 及常见图片头;avif/heic 是 ISOBMFF,已被 ftyp 检查覆盖,bmp 需 `BM` 头),非已知媒体容器签名的一律判坏内容删除。
13. **历史页「打开下载目录」走 `AppBridge.openDownloadDir` 原生桥**:原生按 Movies→Music→Download 的 `Video Downloader` 子目录依次尝试 DocumentsUI 目录 Intent,都不行回退 `DownloadManager.ACTION_VIEW_DOWNLOADS`;浏览器无桥回退为 toast 提示。
14. **下载文件名里禁止出现 `#`**:DownloadManager→MediaStore 的目标路径管线会把 `#` 当 URI fragment 起始符,`#` 起的全部内容(含拼好的扩展名)被截断,落盘成无后缀文件(打开时拿不到 MIME,无法播放)。页面在拼接扩展名前先把标题里的 `#话题` 剔除(视频、音频、**图片文件名一视同仁**);原生 `enqueueDownload` 兜底把 `#` 替换为空格,`saveLivePhoto` 对 baseName 同样兜底,替换后为空则回退默认名。**同类坑:`sanitizeFilename` 的 80 字上限只能作用在标题上,扩展名与第 N 张的 `-N` 必须预留**(所以它有第 2 参 `keep`)。Instagram 把整段帖子简介当 `title`(实测 560 字),先拼接再截断会把 `.mp4` 一起切掉(无后缀文件,相册打不开),图片那边则让一组图全部同名。
15. **下载任务岛(后台任务)**:任务 UI 默认收成**右下角悬浮 dot**(进度环 + 数量角标;总大小未知时环走不定角度旋转),**单击展开任务面板**(可滚动,逐行 暂停/继续/取消),点收起按钮或任务清空自动缩回并隐藏——整个状态仍由真实事件驱动(`__nativeDownload.{start,progress,done,cancelled}`),显示文件名/大小(cur/total)/百分比,关闭解析弹窗后任务照跑。dot⇄面板是**同一容器的 width/height/border-radius 形状过渡**(内容层固定设计尺寸锚定容器右下角,收起过程只被裁剪不回流);**面板高度在 renderTasks 里同步测量,但必须量「内容本身」——累加各行 `offsetHeight` + 行间距 + 上下 padding,禁止用 `list.scrollHeight`**(不依赖 rAF,后台标签 rAF 冻结是壳的已知坑)。**`scrollHeight` 是这里的地雷**:`.tb-list` 是 `flex:1` 的滚动容器,它的 `scrollHeight` 恒等于当前 `clientHeight`,拿它加头部和 padding 会恒定比当前面板高 20px,而 progress 事件每 600ms 重渲染一次 → 面板每帧长高 20px 一路顶到上限,越出 sheet 顶边盖住上层内容(2026-11 真机截图反馈的「任务抽屉慢慢超出上一层」,实测 380px→512px 漂移 76px 越界)。高度上限取 `min(56vh, 视口高 − 底部留白 − TOP_SAFE_GAP(72px))`,下限 120px(单行任务真实高度约 131px,旧下限 150px 会多留一截空底色;不要设更高下限)。任务清空时 `renderTasks` 还要清空 `#tb-list` 的 innerHTML,否则收起后的 dot 里留着上一轮任务的「下载中 0%」文本。任务岛 z 层在 sheet(90) 之上:**解析结果弹窗开着也能点开任务列表**。`AppBridge.cancelDownload(url)` 取消 DownloadManager 行,**还要中断在飞的字节级任务**(置 cancelled 标志 + disconnect 当前连接,HttpURLConnection 的阻塞读不响应 interrupt);**「暂停」=取消行(页面标记 paused),「继续」=同名重新入队从零开始**——DownloadManager 公开 API 无真暂停,不要假装能断点续传。任务会话级,应用重启即清(完成件在相册不受影响)。下载真正开始后,解析弹窗的提示条(`#result-notice.is-active`)改为「已开始下载。可以关闭本窗口,进度见底部任务条。」并保持可关闭(关闭只 `stopPreview`,不影响下载);**进度归属任务岛,不要写成「在历史页查看进度」**——历史列表只记录完成态(`已下载` 徽章),写成历史会误导用户。
16. **小红书图文的数据源与实况契约**:图文判定只能用 `data.kind === 'image'` 或 `data.noteType === 'image'`(`data.type` 在图文笔记上是 `video`,不可信);`images` 元素字符串/对象两种形态都要吃(OpenAPI 声明 `string[]`,实测 `{index,url,downloadUrl}`)。**默认抓笔记页,仅限小红书链接**(plat 判定来自粘贴的 URL;原生 `fetchNoteHtml`,强制移动 UA——桌面 UA 只拿到 36KB 空壳、零 `livePhoto`;不注入 cookie;跟随 xhslink 短链重定向;分享链现走 `xhslink.cn` 新域名,extractUrl/detectPlatform 都要认)。**静态抓取越来越常拿到客户端水合空壳(有 `__INITIAL_STATE__` 但 `noteData.data` 为空,风控所致)**,此时降级到**离屏 WebView 渲染兜底**:`startNoteRenderFetch` 用隐藏 WebView 真实加载笔记页、让小红书自己的 JS 完成水合,轮询序列化 `__INITIAL_STATE__`(ready 条件:`"imageList":[{` 或非空 `noteDetailMap`,1s 间隔最多 14 次,绝对超时 22s),再包成合成 html 走同一 `__nativeNote` 回调,页面解析器不变;轮询 JS 同时探测删除标记(当前笔记暂时无法浏览/笔记不存在/仅作者可见/已被删除 → 报「原文已失效」而非笼统超时);`onDestroy` 必须 teardown。,从 `__INITIAL_STATE__` 提取 `imageList`(字符串感知配平扫描;**它是 JS 对象字面量而非纯 JSON,实测含裸 `undefined`(`jsAssetsList:undefined`),必须先做字符串外的 `undefined`/`NaN`/`Infinity`→`null` 消毒再 JSON.parse,否则实况数据整份丢失 —— 真机踩过的坑**;现行路径 `noteData.data.noteData.imageList`,旧版 `note.noteDetailMap[<id>].note.imageList` 都要兼容),**state 里的 CDN 地址(静帧 `url`、`infoList`、`masterUrl`/`backupUrls`)存的是 `http://` 协议——官方前端渲染前统一升级 https(浏览器 DOM 实测),页面解析必须同样升级 `https:`:壳内页面是 https 源 + 原生 `MIXED_CONTENT_NEVER_ALLOW`,http 静帧的 `<img>` 被静默拦成空白,而原生字节下载不受该策略管 → 「下载成功但预览空白」(v2.8 真机踩坑;CDN 双协议均 200)**。PC 端水合形态 `imageList[i].url` 可能是空串(真地址只在 `infoList` 的 WB_DFT/WB_PRV 场景里),仍按 `url`→`infoList` 兜底。**静帧下载地址一律优先 `fileId` 拼 `https://sns-img-qc.xhscdn.com/<fileId>` 原图直链**(2026-10 用户实测:`!h5_1080jpg` 渲染版对**普通图文也盖中心小红书水印**,且 ≤1080 宽,不能再当下载地址;fileId 是 CDN 存储路径,**必须原样含 `notes_pre_post/` 等目录前缀**,去了前缀 404;sns-img 直链实测无签名不过期、支持 Range、DownloadManager 无 Referer 可下,旧水合形态 `url` 本身是 sns-img 原图时直接用,两者皆无才退回渲染版——有水印但至少能下);**渲染版兜底与 API 兜底图都必须先做原图还原**:`xhsOriginalFromUrl` 从 webpic 地址 `…/<时间戳>/<32hex签名>/<存储路径>!<渲染后缀>` 里剥出存储路径(即 fileId,实测含 `notes_pre_post/`、`note_pre_post_uhdr/` 前缀,14 图实况笔记 14/14 还原成功)拼 sns-img 直链 —— 解析 API 代理地址(`image-proxy?url=<内层 webpic>`)的内层同样带 fileId,所以笔记页增强彻底失败(风控/超时)时 API 兜底也能升回无水印原图,不再退回带右下角水印的 ≤1080 渲染版(2026-10 真机反馈「图文又有水印又不清晰」的根因:增强失败静默降级 API 渲染图);`normalizeImages` 产出 `{src,dl,orig}`,兜底 items 以 `orig||src` 为 still/display、代理地址为 fallback(预览 onerror 与下载 done 回调各自动切换一次);还原不出才原样用代理;**页面侧 `fetchNoteHtmlText` 超时必须宽于原生全部预算(静态抓取+离屏渲染 22s+轮询),现为 26s** —— 旧值 12s 会在水合慢的设备上先放弃,静默降级成 API 水印渲染图);合成封面依旧优先取 mp4 第一帧(`MediaMetadataRetriever`,框架 API,取帧失败退回静帧,静帧现为无水印原图);实况判定 `livePhoto` 布尔位与 h264 流存在**取或**(渲染水合后的 state 可能丢布尔位,v2.6 真机踩过:徽标消失);实况 mp4 取 `stream.h264[0].masterUrl`(带签名会过期;`saveLivePhoto` 第 3 参传 `backupUrls[0]` 无签名长期有效,原生取视频失败自动换 backup 重试一次);**实况合成任务串行执行(单线程 executor)——「全部下载」逐张入队时并发拉大 mp4 会触发视频 CDN 同 IP 限速(v2.8 真机:第一张成功、第二张读超时);拉流超时放宽为连接 15s/读 60s(读超时是包间间隔预算,移动网络停顿超过旧值 15s 会误报「超时」),传输层失败(非 HTTP 状态错误)自动换新连接重试一次,进度事件必须按静帧地址回报(视频段 keyUrl 曾误传视频地址,任务条永不前进)**;**`start` 事件必须在任务真正出队执行时才发(入队即发会让页面无法区分「下载中」与「排队等待」),页面在入队时即把实况行标为「排队等待(避免限速)」,第一张排队行按运行中任务的实时剩余量÷实测速率显示「预计约 N 秒后开始」;实况任务行要存 videoUrl/backup/base,「继续」必须重新走 `saveLivePhoto`(走 `AppBridge.download` 会把静帧当普通图片下)**;抓取失败静默降级 API 的 webp(无实况)。**解析 API 故障时(如上游 yt-dlp 对图文报 No video formats found,502/PARSE_FAILED),小红书图文走 `xhsDirectFromHtml` 直取:标题/图片/实况全部来自笔记页状态,视频笔记接不了这条路**。**解析失败一律关窗 + toast 如实报错(透出 API 错误码;fetch 网络层失败显示「无法连接解析服务」);演示假数据(buildDemoResult/_demo)已整体移除** —— 上游故障被包装成「示例视频」会让用户以为应用坏了(真机踩过两次的坑),任何环境都不再出演示数据。实况合成 = Google Motion Photo,**V1+V2 双格式 XMP 写进同一个 APP1**(Pixel 原厂做法):V1 `GCamera:MicroVideo=1` + `MicroVideoOffset`=**追加的 mp4 字节数**(读取方按 文件末尾−offset 定位视频)兼容旧相册;V2 `GCamera:MotionPhoto=1` + `Container:Directory`(Item:Length 精确字节)兼容 Android 12+/三星 One UI/新版 MIUI;尾部追加 mp4,存成 `.jpg` 落 Pictures;合成器 `MotionPhoto.java` 是纯字节操作,配套 JUnit(`:app:testDebugUnitTest`),已用真实笔记素材在 PC 端验证合成件结构。小米/OPPO/三星私有格式未做,真机识别效果待补测。**图片(含实况合成件)下载完成只弹 toast(`saved_toast`),不弹「打开查看」对话框**——图文批量下载会连环弹窗(真机反馈);视频/音频维持对话框。
17. **抖音图文只有解析 API 一条数据源,不做笔记页增强**(2026-10 实测):`iesdouyin.com/share/note/<id>` 匿名移动 UA 是水合空壳(`_ROUTER_DATA` 有 `note_(id)/page` 但无 `noteInfoDetail`),蜘蛛 UA 的 SSR 只渲染第一张图,经典 `iteminfo` 接口已死,Web API 全要签名 —— 壳内没有可直取的路。API 对抖音图文正常返回 `kind/noteType: image` + `images[]`(`url` 是 douyinpic 全分辨率无水印 webp,`downloadUrl` 代理同字节;`type` 字段照旧不可信)。`startParse` 的 `enrichImageNote` 必须用 `plat === 'xiaohongshu'` 门控:旧版对抖音图文也跑增强,原生静态抓取在页面里找不到小红书标记 → 掉进 XHS 离屏渲染兜底 → 空转约 22s 超时后才出图(壳内必现)。**`v.douyin.com` 分享短链必须先在原生侧还原再交给 API**(2026-10 用户实测:图文短链被上游解析成 `kind=audio`,下载出来不是图片;同一条笔记用还原后的 `/share/note/<id>/` 直链就是正确的 `kind=image`):`AppBridge.resolveRedirect(url)` 手动走重定向链(只取响应头,6 跳上限,WebView 同款移动 UA),回调 `window.__nativeResolved({ok,url})` 只到一次;失败/超时(页面 12s)一律回退原链接,不得让解析失败。还原结果经 `normalizeDouyinShare` 清洗成 `https://www.iesdouyin.com/share/<note|video>/<id>/`(剥分享签名/时间戳等跟踪参数,API 实测认这个干净形态),解析与历史记录都用清洗后的地址(短链会过期,直链更可靠)。`douyin.com/note/<id>` 形态 API 本身就认,无需还原。多图笔记 API 返回已证实全量(2026-10 实测 13 图笔记返回 13 张,全部可直下、WEBP 魔数合法);抖音图片扩展名按 `.webp`(与 XHS API 降级路径同一 `imageItemExt` 契约)。**图片网格预览必须带 onerror 回退**:API 每张图的直链镜像主机随机下发(p3/p9/p11/p26-sign…),个别主机在部分运营商网络不通(真机实测 13 张里恰好 `p11-sign` 的 2 张预览挂、其余正常),`<img>` error 时切到 API 代理地址(`downloadUrl`)重试一次,两条都挂才放弃。**下载同样直链优先 + 失败自动换通道重试一次**(2026-10 真机:代理每次请求都服务端现解析,单张 7~83s,且首次请求偶发 200+错误体触发坏内容守卫——用户看到「第一张永远失败」):`still` 取直链 `a.src`(解析刚签发、CDN 直下秒级),`fallback` 存代理 `a.dl`;`__nativeDownload.done(url,0)` 时 `tryImageFallback` 换备用地址重下一次(同文件名,任务行以新地址为键),`_triedFallback` 保证只重试一次,备用再失败回落原生失败 toast。

18. **Instagram 的数据契约与「转发优先」策略**:平台判定用**主机名匹配**(`hostMatches(url,['instagram.com','instagr.am','cdninstagram.com'])`),不用子串。**`/share/<kind>/<id>/` 必须先重写成 `/<kind>/<id>/`**(`normalizeInstagramUrl`,纯字符串重写、不走原生桥,`igsh` 跟踪参数一并剥掉)—— 实测同一条有效帖子 `/share/reel/…` 被解析 API 判 `BAD_REQUEST`、`/reel/…` 正常;历史记录存还原后的地址。**Instagram 的媒体与封面都在 `scontent.cdninstagram.com` / `d.rapidcdn.app`,大陆网络整域不可达**(实测本机 scontent / instagram.com / google 全部不通,只有解析 API 通),所以**图片的预览与下载主地址一律取 API 转发地址 `images[i].downloadUrl`,直链降为备用**,`getPreviewVideoUrl` 对 Instagram 也优先 `downloadVideoUrl` —— **与小红书「直链优先」刚好相反,不要照搬**;失败重试由 `download.js` 的 done 回调自动换直链。图片元素 `{index,url,downloadUrl}` 与小红书同形,`normalizeImages` 直接吃;`kind`/`noteType === 'image'` 才出网格。**代理地址不带扩展名、URL 推不出格式,Instagram 图片必须显式给 `ext: '.jpg'`**(漏了 `imageItemExt` 会按 `fromNote=false` 默认成 `.webp`,而转发回来的是 JPEG)。封面 `onerror` 回退品牌渐变(不留破图),历史列表封面由容器上**捕获阶段**的 error 代理藏掉(error 不冒泡,不能靠冒泡代理)。**已知边界(不为此加特判)**:解析服务对混合轮播(图+视频同帖)按视频返回且只给其中一个视频;上游偶发兜底 provider 会把纯图片帖标成 `kind=video`(同一 URL 同一时刻,curl 侧 10/10 返回 `kind=image`、浏览器侧连中三次,疑与边缘节点有关),此时转发回来的其实是 JPEG 字节。

19. **抖音视频必须 CDN 直链优先,代理只作兜底**(2026-11 实测,抖音下载慢的根因与修复):解析 API 对抖音**同时下发** `originDownloadVideoUrl`(CDN 直链 `aweme.snssdk.com/aweme/v1/play/?video_id=…`,302 跳到 `*.douyinvod.com`)与 `downloadVideoUrl`(解析服务自己的 `/api/download?raw=1` 代理)。**代理是 Cloudflare Worker 现解析转发的单连接管道,实测 84~600 KB/s;直链单连接 2~4.3 MB/s —— 同一视频同一字节数差 3~25 倍**(`Content-Length` 两者完全一致,切直链不掉画质;直链也不要求 Referer/UA,DownloadManager 默认 UA 直接 206)。**抖音下载地址必须直链优先**,`download.js` 的 `pickDownloadUrl` 用 `DIRECT_FIRST_PLATFORMS` 按平台分流(`App.detectPlatform` 已导出到命名空间供它用):抖音取 `origin` → 兜底代理;其余平台(B 站/小红书/Instagram/YouTube/X)**保持代理优先不变** —— B 站直链有防盗链(裸请求 403,带 `Referer: https://www.bilibili.com/` 才 206),照搬抖音策略会直接下不动。两条通道互备:`doDownload` 登记 `mediaFallbacks[主地址] = {url: 备用地址, name}`;`__nativeDownload.done(url, 0)` 时 `tryMediaFallback` 换另一条通道重下一次(同文件名、任务行以新地址为键、`mediaRetried` 保证只重试一次),备用再失败才回落原生失败 toast —— 与图片的 `tryImageFallback` 同一契约。**失效/已删除视频的直链是空 `video_id`**(`…/play/?video_id=&ratio=720p`,实测 200 + 0 字节),`isUsableMediaUrl` 必须把这种地址当「没有」,否则任务空转;主地址因此会直接落到代理。真机实测修复后 2.6~4 MB 视频 4.5~5.3 秒下完(修复前同片走代理 30~48 秒),落盘 `ftyp` 魔数合法、字节数与直链一致。

20. **无障碍基线:文字对比度、触摸目标、reduced-motion、ARIA 状态**(2026-11 impeccable audit,31 find → 5):**① 文字色必须按「它能落上去的最深背景」验 AA**,不能只对白色验 —— `--text-3` 会落在 `surface-3` 上,所以浅色取 `#66636F`(surface-3 上 4.7:1)、暗色取 `#9B96AD`(4.9:1);`--text-2` 必须始终比 `--text-3` 更深一档以保住层级;`--danger` 只作文本色(填色走 `--danger-wash`),已调至 `#B92B31` / `#EE6A6E`。**② accent 不作小字文本色**:`--accent`(#6C5CE7)在 `--bg` 上只有 4.4:1,12px 的「展开」这类文本一律用 `--accent-ink`;accent 留给填充、边框、进度环、图标。**③ 小字号下限 11px**:功能性标签(底部导航、平台筛选、任务副行、时间戳)不得低于 11px,已把全站 10/10.5px 提到 11px。**④ 触摸目标 ≥36px**:chip 40、tool-btn 40、icon-btn 40、任务行 暂停/取消 36、tb-close 与 hist-act 36;`.tb-close` 必须 `flex-shrink:0`(否则被标题挤到 31px)。**⑤ reduced-motion 不许把进度反馈一起冻掉**:全局 `animation-duration:0.01ms` 会把不定进度扫描压成静止色块、让用户以为卡死,必须为 `.dl-btn .prog.indeterminate`(低频透明度脉冲 `prog-indet-calm`)和 `.taskbar.indet .tb-ring`(`spin-calm`)单独保留语义等价、低刺激的动画。**⑥ 任务岛 ARIA**:`#tb-dot` 带 `role=button` + `tabindex=0` + `aria-controls=tb-panel`,`aria-expanded` 由 `setTaskbarExpanded()` 同步;可聚焦控件必须响应 Enter/Space(否则比不可聚焦更糟)。**⑦ 无 src 的 `<img>` 不绘制**:封面用 `.preview-wrap img:not([src]) { visibility: hidden }`,避免 WebView 画破图占位。**验证脚本**在 `.debug/`:`a11y-verify.mjs`(对比度按半透明背景合成 + 触摸尺寸按 `offsetWidth` 量,不能用 `getBoundingClientRect` —— 任务岛收起态 `scale(0.88)` 会让 36px 读成 31.7px)、`dark-verify.mjs`(双主题对比度)、`taskbar-drift.mjs`(面板高度漂移)。检测器 `impeccable detect app/src/main/assets/` 可用源码模式;**`--cdp` 模式在安卓 WebView 上不可用**(缺 `/json/version` 端点)。剩余 3 条是已核实的误报:`marquee`(不定进度是标准做法)、`dark-glow`(品牌色装饰光晕)、`broken-image`(封面色块由 CSS 隐藏)。

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
