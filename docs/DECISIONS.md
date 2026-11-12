# 决策记录

## D-001 · 产品定位：通用网页播放器，不是某站客户端

**日期**：2026-11-06

最初方案是「粤漫之家专用 TV 客户端」，靠 spider 解析站点接口拿播放地址。
站点侦察后发现 `/allocate/playlist` 对任何请求都返回 `200 + Content-Length: 0`，
要拿到 m3u8 必须伪造设备指纹（`BROWSER_CODE`）、绕过 300 次免费额度或登录墙。

**决策**：把产品改成**不绑定站点的通用网页播放器**——
用户自己添加任意网址，App 负责解析成 TV 界面、负责把遥控器操作翻译成网页操作。

理由：
- 不需要绕过任何风控：页面在真实 WebView 里跑，指纹/额度/登录按站点规则正常执行
- 不承担站点适配的维护成本：换站不用改代码、不用发版
- 不涉及内容分发：App 不内置、不代理、不转存任何内容

---

## D-002 · 不 fork webtv，独立实现

**日期**：2026-11-06

原方案要求 fork `motao123/webtv`。评估后放弃：

| | webtv | 本项目需要 |
|---|---|---|
| 架构 | CatVod spider，围绕 JSON 内容源 | WebView 浏览器 |
| 体积 | 含 MPV + Python 运行时 | 一个 WebView 就够 |
| 许可 | GPL-3.0（传染性） | 无约束 |
| 合并成本 | 上游活跃，需定期跟进 | 无 |

webtv 的核心能力（spider、网盘检测、站源配置）本项目一个都用不上，
fork 过来只会背 GPL 义务和无休止的上游合并。

**决策**：独立搭一个轻量 App（`TVWebPlayer/`），只依赖 androidx。

---

## D-003 · 解析策略：结构启发式，不写站点规则

**日期**：2026-11-06　⚠️ **部分被 D-007 取代**：解析结果（整理视图）不再作为主界面，
只作为辅助浏览方式；网页原文不再被"翻译掉"，站点自身功能一律保留在网页视图里。

> 以下启发式规则本身仍然有效，继续用于 T5 的整理视图改进。

通用播放器最大的难题是「任意网站的 DOM 结构都不一样」。三种可选方案：

1. **每个站点写一套 CSS 选择器**——识别率高，但每加一个站都要写规则，违背"通用"定位
2. **机器学习/视觉模型**——效果好，但包体和依赖太重，TV 盒子跑不动
3. **结构启发式**——找重复模式，与站点无关

**决策**：选 3。

核心判据：某父节点下有 ≥3 个同构子节点（tag + class 相同），
每个子节点含链接，且多数带图 → 这构成一组卡片。

补充规则：
- 带图比例 ≥50% → 海报卡片；否则 → 文字块（选集/链接列表）
- 成组的「第 N 集 / EP N / 纯数字」链接 → 自动归入「选集」行
- 完全识别不出 → 退化为「页面链接」兜底；仍为空 → 提示 + 菜单键打开原始网页

**代价**：纯 Canvas 渲染、需要滚动才加载的无限列表，识别率会下降。
**兜底**：菜单键随时可以直接打开原始网页，用户不会被困住。

---

## D-004 · 播放：WebView + 真实触摸事件，不做原生解析

**日期**：2026-11-06　⚠️ **部分被 D-007 取代**：网页视图成为主界面后，播放页不再承担
"把网页重排成 App"的职责；真实 `MotionEvent` 这条原则继续沿用，并扩展到网页视图的全部点击。
（D-004 关于「是否允许切到原生播放器」的修订属于 T4，本轮 T1 未改动。）

App 不解析、不解密、不代理任何视频地址。播放时把页面原样交给 WebView。

遥控器映射的关键点：**用真实 `MotionEvent`，不用 JS `click()`**。

原因：浏览器对 `requestFullscreen()`、媒体自动播放有「用户手势」校验，
JS 合成事件过不了这层校验，会导致点不动、进不了全屏。
通过 `WebView.dispatchTouchEvent()` 派发真实的 ACTION_DOWN/ACTION_UP，
对 WebView 来说就是一次真实触摸。

---

## D-005 · 构建环境：全部装在 workspace 内

**日期**：2026-11-06

本机原本没有 JDK / Android SDK。为避免污染系统、也避免动 E/F 盘，
工具链全部装在 `<workspace>/.workbuddy/tools/`：

| 组件 | 路径 |
|---|---|
| JDK 21 | `tools/jdk21/jdk-21.0.13+9` |
| Android SDK | `tools/android-sdk`（platforms;android-34、build-tools;34.0.0） |
| Gradle 8.9 | `tools/gradle-8.9` |
| Gradle 缓存 | `tools/gradle-home` |

Maven 走阿里云镜像，Gradle 发行包走华为云镜像（官方源在本机不通）。

---

## D-006 · 构建踩过的坑

**日期**：2026-11-06

1. **`java.util.Properties()` 在 build.gradle.kts 里报 Unresolved reference: util**
   → Gradle 的 `java` 扩展遮蔽了包名。改为文件顶部 `import java.util.Properties`。

2. **Gradle 写 `.tmp/gradle_download*.bin` 和 `caches/build-cache-1/*.part` 报"拒绝访问"**
   → 疑似安全软件拦截无扩展名/临时后缀文件。
   → 用 `--no-build-cache` 绕过。

3. **ViewBinding 根节点带 id 时字段名不确定**
   → `item_card_chip.xml` 去掉根节点 id，改用 `binding.root`。

4. **Bash 环境缺 `ls`/`cat`/`head`/`mkdir`**
   → 全部改用 PowerShell，并且输出写文件后再读（stdout 经常不回显）。

5. **`Invoke-WebRequest` 触发交互式凭据提示**
   → 改用 `curl.exe` 或 Python/Node 发请求。

6. **sdkmanager 无法通过管道接受许可证**
   → 直接写 `licenses/android-sdk-license` 哈希文件。

---

## D-007 · 产品定位修正：网页视图为主角，整理视图为辅

**日期**：2026-11-06

### 变化

D-001 把产品定义为「把网页解析成 TV 界面再呈现」，D-003 为此选了结构启发式，
D-004 把播放页也做成一个单独的 WebView 页面。真机验证后发现问题：

- 粤漫之家的**搜索、分类导航、登录/账号、简中与内容限制模式**都在原网页里，
  解析成卡片后这些功能全部丢失；
- 选择内容限制模式、看完整视频都**依赖登录会话**，而 App 没有登录通道；
- 用户希望在电视上用的是「网页原有功能」，不是「App 替网页重做的界面」。

**新决策**：

| | 之前 | 现在 |
|---|---|---|
| 网页视图（真实网页 + 遥控器增强） | 兜底（菜单键才打开） | **主角，站点默认进入** |
| 整理视图（解析成海报卡片） | 主界面 | **辅助**，只负责更舒服地浏览列表和选集 |
| 站点功能 | 解析丢掉 | 一律保留在网页视图里 |
| 登录 | 无 | 用户在网页视图里自己登录，会话留在 WebView 的 Cookie/DOM Storage |

### 为什么

1. 只有真实网页才能完整承载搜索、分类、登录、站点设置，以及站点自己的播放器；
2. 不解析、不代理、不重放，就不需要碰风控；会话与校验完全由站点按自己的规则执行；
3. 整理视图仍有用（大屏扫海报、选集更舒服），但应该是「换个看法」，不是「换掉网页」。

### 对既有决策的影响

- **D-001 不变**：依旧是不绑定站点的通用工具，只是"通用"落在网页视图上。
- **D-003 部分被取代**：启发式解析不再是主链路，降级为辅助视图，规则本身继续用于 T5。
- **D-004 部分被取代**：真实 `MotionEvent` 原则保留并扩展到网页视图；
  「是否允许把观察到的媒体请求交给原生播放器」由 T4 单独修订，本轮不动。
- **硬性约束不变**：不伪造设备指纹、不绕过额度/登录/风控、不自己调用站点播放接口、
  不保存账号密码、Cookie 只在 WebView 自己的存储里。

### 与现有代码的差异（以代码为准）

现有代码里 `MainActivity.openSite()` 直接跳 `BrowseActivity`（整理视图），
`PlayerActivity` 是一个独立的"播放页 WebView"。T1 起新增 `WebActivity` 作为网页视图，
首页站点默认进它，整理视图改为显式入口。此前文档里"菜单键打开原始网页"的兜底描述
不再准确，以本条为准。

---

## D-008 · T1 空间导航方案：不用 WICG polyfill，自研精简实现

**日期**：2026-11-06

### 评估过程

实际下载了 WICG 官方 polyfill（`wicg.github.io/spatial-navigation/polyfill/`，
71,809 字节，MIT，纯 JS 无外部依赖）并读了源码：

| 观察 | 结论 |
|---|---|
| `window.addEventListener('keydown')` 只认 keyCode 37/38/39/40，并 `preventDefault()` | 会抢占按键，和返回键、菜单键、缩放、光标模式的分配冲突 |
| `isFocusable()` 判据是 `tabIndex >= 0` / 可滚动容器 / iframe | **漏掉** `[onclick]`、`[role=button]`、无 href 的 `a`、自定义 `div` 控件——而任务明确要求覆盖这些 |
| 自带 `window.navigate()` + `keyMode` 全局状态 | 想从 Android 侧精确控制（输入框内方向键留给光标、确认键派发真实触摸）要绕过它的 handler，改造成本高于收益 |
| 1700 行、71.8KB，用 `CSS.registerProperty`、`DOMRect`、`flat()` | 每个 frame 注入一份，TV 盒子开销大；`parent.__spatialNavigation__` 在跨域 iframe 会抛异常 |
| 只管方向键移动焦点 | 确认键点击、焦点框、hover 展开、输入法、缩放、回退栈全都还要自己写 |

**决策**：不直接集成。借鉴它的「方向过滤 + 几何最近邻」思路，自研约 200 行 JS，
候选元素集合按任务要求自定义，按键全部由 Android 侧统一分发。
polyfill 保留在 `.workbuddy/tmp/` 仅作评估依据，不进 APK。

### iframe 覆盖

用 `androidx.webkit:webkit:1.11.0`（已在依赖里）的
`WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)` 做特性检测：

- 支持 → `WebViewCompat.addDocumentStartJavaScript()`，脚本在所有 frame 的文档开始阶段执行，
  每个 frame 自带焦点框 CSS 和导航能力；
- 不支持 → 退回 `onPageFinished` + `evaluateJavascript`，**只覆盖主 frame**，界面明确提示。

同源 iframe 的候选元素纳入主 frame 的几何计算（坐标按 frame 偏移换算）；
跨域 iframe 受同源策略限制，主 frame 无法枚举其 DOM，只能把 iframe 整体当成一个焦点目标，
进入后由该 frame 自己注入的脚本接管方向键（通过 `postMessage` 转发）。
**这是浏览器安全边界，不做任何绕过。**

### 交互一律用真实 MotionEvent

沿用 D-004：确认键在焦点元素中心派发真实 `ACTION_DOWN/ACTION_UP`，
而不是 `el.click()`。这样站点的全屏、播放、登录表单提交都拿得到有效的用户手势。
hover 菜单另派发一次真实 `ACTION_HOVER_MOVE` 触发 CSS `:hover`——
纯 JS 合成 `mouseover` 对 CSS `:hover` 无效，这一点不能靠 JS 假装解决。

### 缩放记忆

`WebSettings.textZoom` 档位 100 / 125 / 150，按站点 host 存 SharedPreferences，
`WebActivity` 启动时读取；切站点互不影响。

---

## D-009 · T1 输入文字：系统输入法在 TV/模拟器上不可靠，改用 App 内置遥控器键盘

**日期**：2026-11-06

### 实测结论（不是推测）

在 MuMu（`127.0.0.1:16384`）上，焦点进入网页搜索框后：

| 触发方式 | `dumpsys input_method` 的 `mInputShown` |
|---|---|
| App 内 `rootView.dispatchTouchEvent()` 派发 `ACTION_DOWN/UP` + `showSoftInput(SHOW_FORCED)` | `false` |
| 系统级 `adb shell input tap` 点同一坐标 | `true` |

差异在于**应用内派发的 MotionEvent 不经过系统 InputDispatcher**，WebView / Chromium
不会建立 Android 编辑态，`mServedView` 虽是 WebView 但 `mDecorViewVisible=false`。
搜狗输入法已安装并设为默认，`show_ime_with_hard_keyboard` 置 1 也无效。
这属于平台输入通道限制，第三方应用无法用 `injectInputEvent`（需 signature 级
`INJECT_EVENTS` 权限）绕过。**因此「输入框唤起系统输入法」这一条不宣称完成。**

试过并排除的路径：`SHOW_IMPLICIT→SHOW_FORCED`、Manifest 去 Fullscreen 主题 +
`adjustResize`、JS `el.focus()`、输入框聚焦时自动派发真实点击、补
`SOURCE_TOUCHSCREEN`、hover 避让输入框、从 `rootView` 分发触摸、修正
`ACTION_UP` 的 `downTime`（顺带修掉一个真实手势时间轴 bug）。

### 决策

不降低「遥控器能输入文字」这个目标，换一条不依赖 IME 的实现：

- 焦点进入输入框 → 弹出 **App 内置遥控器键盘**（数字/字母/符号、空格、退格、
  清空、粘贴、搜索、收起）；方向键走位交给系统 View 焦点，确认键输入。
- 文字由 `tvnav.js` 的 `setNativeValue()` 用原型链上的原生 `value` setter 写入，
  再派发 `input` / `change`，React / Vue 控制的输入框也能感知；`contenteditable`
  走 `textContent` + `InputEvent`。
- 「搜索」按钮按站点真实结构依次尝试，**不自己拼 URL**：
  ① 完整 `keydown/keypress/keyup` Enter 序列（Vue 2 的 `.enter` 读 `keyCode`，
  构造器设不了，用 getter 补 13；Vue 3 / 原生读 `key`）；
  ② 就近找放大镜 / `type=submit` 并 `.click()`；
  ③ 最后才 `form.requestSubmit()`，且跳过 `onsubmit="return false"` 的表单。
  粤漫之家正好是 `<form onsubmit="return false">` + `@keydown.enter` +
  `<i class="search-icon" @click>`，最初只走 ③ 时点了「搜索」毫无反应，
  抓首页 HTML 确认结构后才改成这个顺序。
- 保留「系统键盘」按钮：真机上系统输入法可用时仍可切过去，中文输入走这条。
- 返回顺序相应改为 **遥控器/系统键盘 → 弹层 → 网页历史 → 站点首页 → 站点列表**。
  多出「站点首页」一层，是因为实测搜索结果页用 `location.replace`/SPA 路由，
  `WebView.canGoBack()` 为 false，旧逻辑一按返回就直接退出网页视图；
  现在先 `loadUrl(站点首页)`，只有已经在首页时才 `finish()`。

### 已知限制（如实记录）

- 内置键盘只能直接输入 ASCII；**中文**需要「粘贴」按钮读剪贴板，或用系统输入法。
  没有做拼音/手写，那需要真正的 IME 引擎，超出 T1 范围。
- 若站点输入框被跨域 iframe 包裹，主 frame 拿不到其 DOM，`typeText` 会返回 `0`；
  与 D-008 的 iframe 边界一致，不绕过同源策略。

### 顺带修掉的两个缺陷

- `candidates()` 会把「有尺寸、可点击、但没有任何可见内容」的占位块（懒加载空壳、
  广告预留位）当候选，焦点框落在纯黑块上，看起来像遥控器失灵。
  新增 `hasVisibleContent()`：文字 / 图片（含 `src`/`data-src` 懒加载）/ 背景图 /
  伪元素内容 / 原生控件，至少满足一项才保留。
- `tapAt()` 的 `ACTION_UP` 原本把 `downTime` 设成了抬起时刻，DOWN/UP 不构成同一次
  手势；已改为共用按下时刻。
- 分类页连按 6 次下键，焦点框看起来一直停在筛选区的「空黑方块」上、走不到海报
  （w66 / w67 / w68 三张截图像素完全相同）。先误判成「装饰性占位被当候选」，
  加诊断日志后 **证伪**：`[tvnav] focus=A text="全部" size=28x20`，
  175 个候选全是主目标，焦点其实一直在正常推进（`全部` → `神魔`）。
  真因是两条叠加：
  ① `scrollIntoView({block:'center',behavior:'smooth'})` 每次换焦点都把页面
     滚到居中，视觉上原地不动；
  ② 焦点框自带 `transition:left/top`，平滑滚动期间 `getBoundingClientRect()`
     持续变化，框就滞后漂到文字旁边，看起来像框住一块空白。
  修法：去掉 CSS 过渡；滚动改成「只在元素快出视口时即时滚一点」并立刻重算框；
  框对 28×20 这类小文字链接给 56×40 最小尺寸、按元素中心外扩，文字一定在框里。
  教训：这类「焦点框落错位置」的 UI 缺陷不能靠截图猜 DOM，必须先打日志。
  期间新增的 `isPrimaryTarget()` / 小尺寸候选过滤保留为无害的兜底。
- 修完滚动后框仍停在两行文字之间，说明还有**被遮挡的重复 DOM**（站点同时保留
  桌面 / 移动两套筛选器，副本 `display` 不为 none 但盖在别的层下）。
  `usable()` 再补一条 `document.elementFromPoint(中心)` 命中检查：
  该位置点不到这个元素（也点不到它的父子）就不算候选。

---

## D-010 · 局域网导入：手写 ServerSocket + 只编码的二维码，页面级生命周期

**日期**：2026-11-06

需求：电视上给一个网址和二维码，手机扫码后能上传播放列表文件，或发送网址 / 链接，
电视端直接导入到自己的存储，不用在电视上敲一长串 URL。

### 为什么不引 nanohttpd / ktor

项目从 D-005 起全部用 `--offline` 编译，工具链缓存在 workspace 内。
nanohttpd / ktor / AndroidAsync 都要联网拉依赖，且 Ktor 会带一串 kotlinx 传递依赖。
实际只需要 5 个路由（上传页、历史、上传、链接、404），
用 JDK 自带的 `java.net.ServerSocket` 写不到 500 行就能覆盖，
**运行时零新增服务依赖**。

**决策**：`LanServer.kt` 手写 HTTP/1.1 骨架 —— 读请求行/头、按 `Content-Length`
读完整 body、手写 multipart 边界切分、`Connection: close` 的短连接响应。
accept 一条线程 + `Executors.newCachedThreadPool()` 处理并发；端口 8099 被占用时
顺延到 8110，全占满则取系统随机端口并在页面显示真实端口。

### 二维码只用 ZXing 的「编码」半边

`com.google.zxing:core:3.5.3` 是纯 Java、无传递依赖、不需要相机权限：
只用 `QRCodeWriter` + `BitMatrix` 生成 Bitmap，`MultiFormatReader` 只在本机 QA
脚本里用来回读验证，**不进 APK 的运行时链路**。
不引 `zxing-android-embedded` / `javase`，避免拉进相机、Activity 和 AWT。

生成方式：请求 0×0 尺寸拿到「一像素一模块」的最小 BitMatrix →
整数倍最近邻放大 → `BitmapDrawable.setFilterBitmap(false)`，
保证 TV 上放大后仍是硬边方块。静区用 `EncodeHintType.MARGIN = 2`。

### 生命周期：页面级，不是常驻服务

**决策**：`LanImportActivity.onCreate()` 起端口，`onDestroy()` 关端口，
不做 `Service`、不做前台通知、不加开机自启。
理由：这是一个「临时收件口」，用户退出后不该继续留一个**无鉴权**的写端口。
实测：页面打开时 `GET /` 返回 200 → 按返回键退出后连接被拒 → 再次进入恢复 200。

代价：切到「立即播放」的播放器页面时服务仍在（Activity 只是 stop，未 destroy），
从播放器返回可继续传。这个是想要的行为。

### 数据落点（复用既有存储，不新造一套）

| 手机发来的 | 电视怎么处理 | 落到哪 |
|---|---|---|
| M3U / M3U8 / DPL / TXT 文件 · 播放列表 | `PlaylistParser.parse()` | `PlaylistStore`（同名覆盖，不重复堆积） |
| 文件 · 站点清单 | 逐行 `Site.normalizeUrl()` + 按 URL 去重 | `SiteStore` |
| 链接 · 添加为站点 | 同上 | `SiteStore` |
| 链接 · 导入播放列表 | **电视自己** HTTP GET 该 URL 再解析 | `PlaylistStore` |
| 链接 · 立即播放 | `MediaLauncher.open()` | 拉起 `NativePlayerActivity` / `PlayerActivity` |
| 链接 · 自动 | 按扩展名分流 | 见下 |

「导入播放列表」由电视去下载，手机只发一个 URL —— 手机不用把文件内容转发一遍，
也避免手机熄屏 / 切后台导致上传中断。

### 自动识别规则

- `.m3u` / `.dpl` / `.txt` / `.json` → 下载并当播放列表导入
- `.mp4` / `.mkv` / `.ts` / `.mpd` / `.webm` / `.flv` / `.avi` → 立即播放
- `.m3u8` 有歧义（既可能是频道清单，也可能是 HLS 媒体清单）→
  先拉前 8 KB：含 `#EXTINF` 且不含 `#EXT-X-` 判为频道清单导入，否则当直播流播放
- 其余 → 补 `https://` 后添加为站点；不像网址的行跳过并在结果里说明
- 一次收到多个视频地址只播第 1 个，避免活动栈叠一堆

### 编码：UTF-8 失败退回 GB18030

PotPlayer 导出的 DPL / 老 M3U 常是 Windows ANSI（GBK）。`decodeText()` 先用
严格 UTF-8 解码器（`CodingErrorAction.REPORT`），抛
`CharacterCodingException` 就退回 GB18030。实测中文频道名「中文频道」「另一个频道」
写入 `playlists.xml` 无乱码。文件名按 UTF-8 解（RFC 7578），中文文件名也正常。

### 安全边界（如实记录，不宣称「安全」）

- **无鉴权**：同一局域网内任何能访问该端口的设备都能写 App 的站点/播放列表。
  页面、手机页、文档都明确写了「请勿在公共 WiFi 使用」。
- 只 `ServerSocket` 监听，不写外部存储、不代理 / 不转存媒体内容、不开上传目录，
  所以不会变成文件服务器；所有内容只进 App 自己的 SharedPreferences。
- 请求体上限 20 MB、单份播放列表 8 MB、HTTP 超时 8/12 秒，防内存与线程被拖死。
- 端口只对局域网开放，没有做 TLS（自建证书的二维码扫描体验更差）。
- `android:usesCleartextTraffic="true"` 是既有配置，与本功能无关。

### 已知限制

- 模拟器（MuMu）只有 `10.0.2.15` 这个 NAT 地址，宿主机扫不出来；
  真机/盒子会拿到 `192.168.x.x`。多网卡时「换一个地址」按钮逐个切换。
- 「立即播放」分支这次用的是假 MP4（返回文本），播放器确实被拉起但报
  `ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`；**该分支只验证到「Activity 被拉起」，
  未验证真实视频能播完**（播放能力本身属于既有 T4 范围）。
- 二维码回读验证是在宿主机用 ZXing 解码器解电视截图完成的，
  不是用手机相机真扫。内容确认为 `http://10.0.2.15:8099/`。
- 验证过程中往模拟器 debug 包写入了测试站点与测试播放列表，长按磁贴可删。

---

## D-011 · 黑屏 / 对比度 / 多余侧栏：三处一起改，全部按「看得见、够得着」定标准

用户实测报告三条：打开其他网址（如 bilibili.com）会黑屏；字体与背景对比度不便阅读；
粤漫之家右侧控制栏多余且没有用处。三条是三个独立成因，不是一处 bug 的三种表现。

### 1 · 黑屏：不是渲染失败，是三件事叠在一起

先澄清事实：logcat 里 WebView 确实把 bilibili 渲染出来了，那些
`shared_image_*` 的 E 级日志是 Chromium 自己的调试噪声，不是故障。

真正的成因有三层：

- **手机版 UA**。`MOBILE_UA`（`Linux; Android 12; TV`）让 bilibili 返回移动版页面，
  移动版只做列表和「App 内打开」，播放器根本不挂载 —— 用户看到的「不能正常观看视频」。
  现在 `WebActivity` 与 `PlayerActivity` 都默认 `DESKTOP_UA`，并且把「桌面版 / 手机版」
  做成工具条里的一项，按域名记忆（`web_view_prefs.desktop_<host>`）。
- **没有错误回调**。`WebViewClient` 原来只写了 `onPageFinished`，
  域名解析失败 / 超时 / 证书错误 / 4xx 时 WebView 只留一片纯黑，没有任何文字。
  现在补了 `onReceivedError`（仅主文档）和 `onReceivedHttpError`（≥400），
  把错误码翻译成人话，并给「重试 / 桌面版打开 / 返回站点列表」三个按钮。
  `PlayerActivity` 同样补了错误面板。
- **没有全屏容器**。站点播放器 `requestFullscreen()` 时 Chromium 交出 Surface View，
  没有 `onShowCustomView` 就没人挂它，全屏瞬间变黑。
  `WebActivity` 新增 `videoStage` 容器，`WebChromeClient` 负责挂载 / 交还。

对照证据：改前进 bilibili 是移动版（`1-焦点在bilibili` 那组截图），
改后是桌面版首页且视频页能真正解码出画面（`3-bilibili桌面版`、`4-网页播放器起播`）。

### 2 · 对比度：`text_tertiary` 36% 白在电视上等于看不见

原配色是 iOS dark 那套「靠不透明度拉层级」：`text_secondary` 60%、
`text_tertiary` 36%。手机上一臂距离没问题，电视是 2~3 米，36% 白在纯黑底上
实测基本读不出（首页副标题、磁贴 URL 都是这个颜色）。

改法只动不透明度，不动色相：

| token | 原值 | 现值 |
|---|---|---|
| `text_secondary` | `#99FFFFFF`（60%） | `#D0FFFFFF`（82%） |
| `text_tertiary` | `#5CFFFFFF`（36%） | `#9EFFFFFF`（62%） |

同时 `Text.Caption` 从 13sp/`text_tertiary` 改成 15sp/`text_secondary`——
说明文字是「给人读的」，不该是「气氛组」。层级感靠字号和字重区分，
不靠把字压到看不清。

### 3 · 「右侧控制栏」：是粤漫之家网页自带的 `#side-toolbar`

用户澄清「右侧控制栏说的是粤漫之家的」。抓 `https://www.ymvid.com` 的 HTML 确认：

```html
<div id="side-toolbar" class="side-toolbar side-toolbar-show">
  <div class="side_toolbar_inner">
    <div class="side_toolbar_top day-model">
      ... 站点首页 / 切换主题 / 下载APP二维码 / 给我留言 / 返回顶部
```

这是站点自己的浮动侧栏（5 个图标），在 TV 上占掉右侧一整条内容宽度，
而且 `tvnav.js` 的空间导航会把里面 5 个图标当成候选元素，抢走焦点。

处理方式：**只隐藏，不删 DOM、不改站点逻辑**。`WebActivity.applyTidy()`
在每次 `onPageFinished` 后注入一段 JS，按 id 精确命中 `side-toolbar` / `roll-to-top`，
以及移动版站点的 `.m-open-app` / `.fixed-openapp` 等「App 内打开」浮层，
统一 `display:none !important`。为什么每次加载都要重跑：站点会重建 DOM。

不做模糊匹配（`.side-toolbar*` 这类）——匹配错一次就会把站点主干道藏掉，
代价远高于收益。

### 4 · 工具条本身：删掉两个「跨过去才够得着」的按钮

原来的工具条是：首页 / 整理视图 / 缩放 / 指针模式 / 刷新。

- 「首页」→ `finish()`：按返回键本来就回得去，属于重复入口。
- 「整理视图」→ `BrowseActivity`：站点列表长按菜单里也有，同样重复。

两个都删了，换成真正缺失的「桌面版 / 手机版」切换，并把字号从 14sp 提到 16sp。
现在的工具条：**桌面版 · 缩放 · 指针模式 · 刷新**，打开时焦点落在第一项。

### 验证（MuMu，debug 包）

- 首页：副标题、磁贴 URL 可读（`1-首页-对比度改后`）
- bilibili：桌面版首页 + 视频页真实解码（`3-`、`4-`）
- 粤漫之家：`#side-toolbar` 已消失，海报区向右扩展（`2-粤漫之家-侧栏已隐藏`）
- 工具条：只剩 4 项（`5-工具条精简`）
- 错误面板：故意给一个不存在的主机，显示「找不到这个网址的主机名」+ 三个按钮（`6-加载失败面板`）

### 已知限制

- `applyTidy()` 只按 id / class 精确命中，站点改版（换 id）就会失效，
  需要更新 `TIDY_IDS` / `TIDY_SELECTORS`；不会自动适配。
- 桌面版不是万能的：部分站点桌面版在 1080p 下字号偏小，需要配合「缩放 125%」。
- 全屏容器只覆盖 `onShowCustomView` 路径；纯 `<video>` 元素靠浏览器自己的
  fullscreen，不受影响。

---

## D-012 · 大转向：blbl 当主体，本项目功能作为 ext 附加模块

**日期**：2026-11-06

用户三次明确要求：

1. 「直接移植，不要你自己写」
2. 「或者在 blbl 的基础上加入我的粤漫之家和播放列表功能」
3. 在 A / B 两个方案中选择 **A**

### 三个候选上游的核实结果

| 上游 | 实际内容 | 结论 |
|---|---|---|
| `cat3399/blbl` | 267 个 Kotlin 文件、61 个布局、完整 B 站 TV 客户端（protobuf/gRPC、ijkplayer、media3、WBI 签名） | ✅ **可移植，作为主体** |
| `xiaye13579/BBLL` | **只有 README，无任何源码** | ❌ 无法移植 |
| `motao123/webtv` | CatVod spider 架构 | ❌ 弃用（见 D-002） |

BBLL 这条线是死路：仓库里只有说明文档，没有可搬的代码。

### 方案 A 的具体做法

- **blbl 原样作为 app 主模块**：包名保留 `blbl.cat3399`，267 个文件一个不改地放进来
- **本项目原有的 24 个功能文件**迁到 `blbl.cat3399.ext.*` 包下
- **在 blbl 侧边栏新增第 8 项「扩展」**（`SidebarNavAdapter.ID_EXT = 7`），
  指向 `ExtHubFragment`，从那里进入粤漫之家 / 播放列表 / 收藏夹 / 局域网传片

这样 blbl 的全部能力（推荐、分类、动态、直播、番剧、登录、弹幕、播放器）
都是原生可用的，本项目只贡献「站点网页浏览 + 播放列表管理 + 局域网传片」这层增量。

### 依赖与 SDK 升级（必须一起做）

- `compileSdk` / `targetSdk` 34 → **35**：blbl 的 media3 1.8.0 强制要求 ≥35
- AGP 8.5.2 → **8.7.3**：AGP 8.5.2 最高只支持 compileSdk 34
- 新增 `android-35` platform（此前只有 34）
- 新增 `androidx.webkit:webkit:1.12.1`：ext 的遥控器导航脚本注入依赖
  `WebViewCompat.addDocumentStartJavaScript`，blbl 本身不用这个库

### 资源冲突处理

逐项比对后真冲突只有 3 个键：

| 类型 | 冲突 | 处理 |
|---|---|---|
| layout | `activity_main` | 我方弃用（blbl 的主界面接管），删除我方版本 |
| layout | `activity_player` | 改名 `ext_activity_player.xml`，我方 `PlayerActivity` 绑定 `ExtActivityPlayerBinding` |
| string | `app_name` | 保留 blbl 的「Blbl」，删除我方定义 |

其余 8 个同名 values 文件（colors / dimens / ids / strings / themes）合并为
`ext_colors.xml` 等，但**内部资源名不加前缀**——因为两边的键（`bg`、`text_primary`、
`screen_margin`）本来就不重名。之前误加的一轮 `ext_` 前缀已全部回退。

### 编译验证

- blbl 267 文件 + ext 24 文件 → `:app:compileDebugKotlin` **BUILD SUCCESSFUL**
- 修复的 3 类 ext 编译错误：
  1. 布局里自定义 View 仍写 `com.tvweb.player.ui.CursorView` → 改 `blbl.cat3399.ext.ui.CursorView`
  2. `BiliClient.cookies.importFromWebView()` 不存在 → 改用 blbl 的 `CookieStore.upsertAll()`
  3. 缺 `androidx.webkit` 依赖 → 已加


---

## D-013 网页播放器全屏：不走 Fullscreen API，自己搬进满屏浮层

**现象**：粤漫之家点播放后画面只占页面左侧一块，站点播放器右下角那颗「全屏」
按了没反应（只会弹一个 tooltip），用户的实际感受是「播放没办法全屏」。

**为什么站点自己的全屏按钮没用**：
- 站点的全屏按钮依赖 Fullscreen API，而 Android WebView 对「非 video 元素」
  的 `requestFullscreen()` 支持很差，元素型全屏基本不生效；
- 就算生效，遥控器也点不到那颗 20px 的小图标——TV 上这是硬伤。

**做法**（`tvnav.js`）：
1. `enterFullscreen()` 找到页面上面积最大的 `<video>`，从它往上最多 4 层找
   「播放器容器」（高度超过视频 2.2 倍就停，避免把整块内容区搬走）；
2. 把容器搬进一个 `position:fixed;100vw×100vh;z-index:2147483646` 的浮层，
   同时把容器和 video 的固定尺寸 / max-width / transform 全部用 `!important` 压平；
3. 退出时按原 parent / nextSibling / style 原样塞回，**并撤掉 video 上的内联尺寸**，
   否则退回原位还会撑满容器，看起来像没退出；
4. 播放器在跨域 iframe 里时，主文档拿不到里面的 video：改成把 **iframe 元素自己**
   原地设成 fixed 铺满（绝不能把 iframe 搬进浮层——搬动 iframe 会让它重新加载，
   视频从头开始），再 `postMessage` 让子 frame 把自己的 video 铺满。

**触发方式**（按可靠性排序）：
- **视频开始播放就自动全屏**（`play` 事件捕获阶段）。电视上离屏两三米，
  这是唯一不需要用户找按钮的路径，实测可用；
- 工具栏「全屏」按钮（确认键点击）；
- 长按确认键：用 550ms 定时器判定，不依赖 `repeatCount`
  （很多遥控器/`input` 注入只发一对 DOWN/UP）。

**坑**：
- `KEYCODE_MENU` 在部分盒子被系统吃掉，`dispatchKeyEvent` 收不到，
  **不能把关键功能只挂在菜单键上**；
- 确认键的点击改到 `ACTION_UP` 才触发，否则长按会先点一下再全屏。

---

## D-014 内置两份播放列表 · 去掉焦点浮框 · 弹幕默认关闭

**1. 内置 gdty.dpl + tvb.dpl**

- `app/src/main/assets/builtin/` 现在有 `gdty.dpl`（4687B，广东体育）与
  `tvb.dpl`（24093B，166 条 / 128 个频道）。都是 PotPlayer 的 `DAUMPLAYLIST` 格式。
- `PlaylistStore.seedBuiltinIfNeeded()` 改成遍历 `BUILTINS` 列表播种。
- **升级路径**：老用户 `seed_builtin_playlist_done` 已经是 true，如果还按「首次启动」
  处理，会把他们早就删掉的广东体育又塞回来。所以新增 `seed_builtin_playlist_v2`
  标记：v1 已完成时只补 v2 新增的 TVB，并按列表名去重。
- **解析器的坑**：PotPlayer 会把「2026-06-07 07:21:12」这种日期当分隔行写进
  `file` 字段，不过滤就会多出一个永远播不了的假频道。`PlaylistParser.looksPlayable()`
  只接受带 scheme 的地址或本地/UNC 绝对路径。

**2. 去掉跟随焦点的白色浮框**

原实现在页面上叠一个 `#__tvnav_box__` 白框（4px 白边 + 蓝色辉光）。它的问题：
- 一直挂在画面上，挡住字幕；
- z-index 比全屏浮层高，全屏播放时浮在视频上面，看起来像底下多了个空输入框；
- 不会自动隐藏。

现在删掉整个浮框，只用元素自身的 `.__tvnav_focus__` outline（2px）表示焦点。
不会挡住画面，进入全屏时也会被浮层自然盖住。

**3. 弹幕默认关闭**

- Web 页面：`tvnav.js` 的 `hideDanmaku()` 只隐藏「铺在画面上」的大覆盖层
  （`[class*=danmaku|danmu]` 且面积 > 35% 视口宽、> 12% 视口高），
  控制条里的弹幕开关不动，用户想开回来还有入口。
  在 `activate` / `play` / `enterFullscreen` 以及 0.4s/1.2s/3s 三个时间点重扫
  （弹幕层通常是播放器初始化后才插进来的）。
- 原生播放器：`AppPrefs.danmakuEnabled` 默认值 `true` → `false`。

### D-014 补充：播种函数根本没被调用（装完看不到内置列表的真正原因）

`PlaylistStore.seedBuiltinIfNeeded()` 写好了却**从来没有调用点**——
扩展页 `ExtHubFragment` 当时只调了 `SiteStore.seedDefaultsIfNeeded()`（站点），
播放列表那一半漏了。所以 APK 里明明有 dpl，装上去一份列表都没有。

修法（三处，都是幂等的）：
1. `BlblApp.onCreate` 里 IO 线程播种——「装完就有」，不依赖用户先打开扩展页；
2. `ExtHubFragment.buildEntries()` 里补上（顺带让计数正确）；
3. `PlaylistActivity.onCreate` 兜底（直接进列表页也能拿到）。

预期结果：广东体育 40 条、TVB 127 条（`tvb.dpl` 原始 166 行 file，
去掉 1 条 PotPlayer 日期分隔行 + 按 url 去重后为 127）。

---

## D-015 直播时移：照 PotPlayer 的做法，本地缓存文件 + 本地 HTTP(Range) 代理

**为什么现在不能拖**：ExoPlayer 只能在上游声明的 seekable 窗口里 seek。
`udp/239.x` 这类裸 TS 代理流没有分段信息，窗口为空，所以左右键无窗口可落。

**PotPlayer 为什么能随便拖**：它不直接播网络流，而是边下边写进本地缓存，
播放器读的是「一个已经下载了多少就能拖到多少」的本地文件。

**最终落地（2026-11-06，MuMu 实测通过）**：

与上面草案的差别：没做「单文件 + Range」——单文件边播边长会让 ExoPlayer
反复触发 EOF / 长度快照失效。改成**分片 TS + 本机 127.0.0.1 的 live HLS**。

1. `LiveTimeshift.kt`（`com.tvblplayer.ext.timeshift`）：后台线程按 188 字节
   TS 包边界切 `seg_%06d.ts`（约 2 MB/片），写入**设备 `cacheDir/live_ts/`**；
   `ServerSocket(0,16,127.0.0.1)` 提供 `/live.m3u8`（不加 ENDLIST，保持 live）
   和 `/seg_*`。窗口按 `maxBytes` 淘汰最旧分片；分片用绝对编号，
   淘汰只删文件、不改变已有 URL，因此不会破坏播放器手里的分片引用。
2. `NativePlayerActivity.start()`：直播源先 `timeshift.start(url)`，把本地
   `live.m3u8` 交给 ExoPlayer；本地播不起来自动回退直连（`timeshiftFallback`）。
3. **容量口径**：缓存占的是**安卓设备自己的内部存储**，与编译/开发用的电脑
   磁盘无关。菜单键 `NumberPicker` 由用户自己定分钟数，`0 = 直连不缓存`，
   界面显示「预计占用 / 设备可用空间」。上限**不写死**：按
   `设备可用空间 × 90% / 每分钟估算字节` 动态算（本机 118.98 GB → 6132 分钟），
   兜底不超过 7 天；`TimeshiftPrefs` 只保证 `>= 0`，不再 coerce 到 240。
4. **seek**：live HLS 的 `duration` 恒为 `TIME_UNSET`，不能拿它当窗口。
   `seekableWindow()` 在时移激活时直接返回 `0..(minutes*60_000)`，
   `seekBy(±10s)` 在窗口内 clamp。实测 `seek 87845 -> 77845 (win=0..1800000)`；
   把配置改成 600 分钟后窗口为 `0..36000000`。
5. **黑框根因**：`controls` 层的 `scrim_top`(150dp→#99000000)、
   `scrim_bottom`(190dp→#E6000000) 两条渐变遮罩 + 顶部 `npTitle`，
   与 `chBanner` 无关。两者和标题全部删除，底部文字改用
   `shadowColor="#B3000000"` 保证可读。
6. 播放页主题是 `Theme.AppCompat.Light`，`AppToast` 的 `MaterialCardView` 会崩；
   播放页内统一改用系统 `Toast`。

**验证**：`BUILD SUCCESSFUL`（`_verify/build_timeshift7.log`）；MuMu 上左键 3 次
各回退 10s、右键 1 次前进 10s；设备生成 19+ 个 2MB 分片；
`_verify/ts_final_clean.png` 画面无任何黑框/渐变；`_verify/ts_dialog5.png`
显示 600 分钟 / 预计 10.48 GB / 设备可用 118.98 GB / 最多可设 6132 分钟。
