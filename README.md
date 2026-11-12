# TV 网页播放器

Android TV 上**让普通网页能用遥控器操作**的通用播放器。不内置任何内容，
用户自己添加任意网址。默认打开**网页视图**：站点原样呈现，搜索 / 分类 / 登录 /
站点设置 / 内容限制模式 / 自带播放器全部保留，App 只把遥控器翻译成网页能理解的操作。

另有**整理视图**（把网页列表重排成原生卡片）作为辅助，站点卡片长按可切换。
早期版本以整理视图为主，实测会丢掉站点的搜索、分类、登录等功能，
因此定位已改为网页视图为主角（见 `docs/DECISIONS.md` D-007）。

首次启动会预置一个默认站点（「粤漫之家」）作为开箱可用的示例——
它只是一个书签，不含任何内容或接口，长按即可删除，删掉后不会自动恢复。

> 界面效果见 `docs/ui-prototype.html`（整理视图原型，用真实抓取的数据渲染）。

---

## 功能

- **站点管理**：添加 / 编辑 / 删除任意网址
- **网页视图（默认）**：站点原样加载，功能一个不少
- **桌面版 / 手机版切换**：默认桌面版（视频站只有桌面版才有能用的播放器），
  工具条一键切换，按域名记住；视频站的「App 内打开」劝装浮层会被隐藏
- **空间导航**：方向键按几何最近邻移动焦点，明显焦点框，确认键派发真实触摸
- **遥控器键盘**：焦点进输入框自动弹出面板，方向键选字符、确认键输入，
  文字经原生 setter + `input` 事件写入（React/Vue 也能感知），支持退格/清空/粘贴/搜索
- **指针模式备用**：方向键移动虚拟光标，确认键点击，够不到的控件用这个
- **hover 菜单**：真实 `ACTION_HOVER_MOVE` 展开 CSS `:hover` 下拉
- **缩放记忆**：100 / 125 / 150 档，按站点 host 分别记住
- **加载失败提示**：域名不通 / 超时 / 证书错误 / 4xx 时显示可读原因 + 重试，
  不再是一片纯黑
- **网页视频全屏**：站点播放器全屏时有承接容器，不会黑屏
- **返回顺序**：键盘 → 弹层 → 网页历史 → 站点首页 → 站点列表
- **整理视图（辅助）**：网页列表结构识别成原生卡片，遥控器直接选、长按收藏
- **播放列表**：导入 M3U8 / M3U / PotPlayer DPL，用原生播放器播放
- **局域网传输**：首页显示网址 + 二维码，手机扫码上传播放列表文件，或发送站点 /
  播放列表 / 视频直链，电视直接导入（不用在电视上敲长网址）
- **播放**：HLS 与 HTTP-TS 走 ExoPlayer；网页播放交给站点自己的播放器

---

## 播放能力

播放列表里的地址形态很杂，入口会先「认」再分发：

| 地址形态 | 判定方式 | 交给谁 |
|---|---|---|
| `.m3u8` / `#EXTM3U` | 扩展名 + 响应头 + 首行 | ExoPlayer（HLS，含 master 与直播） |
| `.mp4` / `.ts` / `.mkv` | 扩展名或 Content-Type | ExoPlayer（渐进式，解封装器嗅探） |
| `http://host/udp/…`、`.php?id=` | 读前 2 KB：TS 同步字节 `0x47` | ExoPlayer（TS 解封装） |
| 返回 `text/html` | Content-Type | WebView 播放器 |
| `udp://` `rtp://` 裸组播 | 协议 | 直接提示不支持（需运营商内网 + 组播权限） |

一个细节：如果带着探测出的 MIME 播放失败，会**去掉 MIME 再试一次**，
让 ExoPlayer 自己嗅探容器——服务器谎报 Content-Type 很常见。

---

## 它解决什么问题

电视上用遥控器操作普通网站非常难受：网页是为鼠标和手指设计的，
方向键在里面几乎点不到东西，视频也常常进不了全屏。

这个 App 的主线做法（网页视图）：

1. **不改写网页**——站点原样加载，搜索、分类、登录、设置、播放器照常工作
2. **翻译遥控器**——注入 `tvnav.js` 做空间导航；确认键用真实 `MotionEvent` 点击，
   保证全屏 / 播放 / 表单提交拿得到用户手势；焦点进输入框给遥控器键盘
3. **兜底通道**——指针模式覆盖注入脚本够不到的控件；整理视图作为辅助重排

因为不绑定任何站点、不解析站点接口，换一个网站不需要改代码、不需要发版。

---

## 工作方式

**主线：网页视图（WebActivity）**

```
点站点 → WebActivity 原样加载网页
      │
      ├─ addDocumentStartJavaScript 注入 tvnav.js（每个 frame 一份）
      │     特性不支持时退回 onPageFinished，只覆盖主 frame 并提示
      │
      ├─ 方向键 → __tvNav.move() 几何最近邻 → 焦点框
      ├─ 确认键 → center() 取坐标 → rootView 派发真实 ACTION_DOWN/UP
      ├─ 焦点进输入框 → 遥控器键盘 → typeText() 原生 setter + input 事件
      ├─ 菜单键 → 工具条（首页 / 整理视图 / 缩放 / 指针模式 / 刷新）
      └─ 返回键 → 键盘 → 弹层 → goBack() → 站点首页 → finish()
```

**辅助：整理视图（BrowseActivity）** —— 由站点卡片长按或工具条进入。

```
隐藏 WebView 真实加载页面 → 注入 extract.js 识别内容区块
      → 返回 { title, poster, hasVideo, sections[] }
      → 原生 TV 界面渲染（行 = 区块，卡片 = 条目）
      ├─ 点卡片 → 同域则继续解析深入
      └─ 有视频 → PlayerActivity（WebView）
```

### 整理视图的解析规则（不认站点，只认结构）

一个区块要同时满足：

- 某个父节点下有 **≥3 个同构子节点**（tag + class 相同）
- 每个子节点里**能找到链接**
- 多数子节点**带图片**

满足就是一组卡片。带图比例 ≥50% 渲染成海报卡片，否则渲染成文字块（选集/链接列表）。

再加两条兜底：

- 页面上成组的「第 N 集 / EP N / 纯数字」链接 → 自动归为「选集」行
- 一条都识别不出 → 退化为「页面链接」行；仍为空则提示并可用菜单键打开原始网页

### 遥控器

| 位置 | 操作 |
|---|---|
| 站点列表 | 方向键移动焦点，确认进入网页视图，长按切换整理视图/编辑/删除 |
| 网页视图 | 方向键空间导航，确认键点击，菜单键工具条 |
| 网页视图 · 工具条 | 桌面版/手机版 · 缩放 · 指针模式 · 刷新（焦点默认落在「版式」） |
| 网页视图 · 输入框 | 自动弹遥控器键盘：方向键选字符、确认键输入、搜索/退格/清空/粘贴/收起 |
| 网页视图 · 指针模式 | 方向键移动虚拟光标，确认键在光标位置派发真实触摸 |
| 网页视频全屏 | 返回键退出全屏（回到网页，不退出网页视图） |
| 加载失败 | 三个按钮：重试 / 桌面版打开 / 返回站点列表 |
| 整理视图 | 方向键切换行/卡片，确认进入，长按收藏 |
| 播放页 | 方向键移动虚拟指针，确认键派发**真实触摸事件** |

点击一律用真实 `MotionEvent` 而不是 JS `click()`，是因为浏览器对
`requestFullscreen()`、媒体自动播放、表单提交等有「用户手势」校验，
合成事件过不了。遥控器键盘的文字写入则走 JS 原生 setter，不涉及手势校验。

### 版式与页面清理（D-011）

视频站（bilibili / 优酷等）在手机版 UA 下只给列表 + 「App 内打开」，
播放器根本不挂载，所以**默认使用桌面版 UA**。桌面版 / 手机版可在工具条切换，
选择按域名记进 `SharedPreferences`（`desktop_<host>`）。网页播放器
（`PlayerActivity`）同样走桌面版。

页面加载完成后会注入一段清理脚本，只**隐藏**下面两类元素，不删 DOM、不改站点逻辑：

| 目标 | 例子 |
|---|---|
| 站点自带的浮动侧栏 | 粤漫之家 `#side-toolbar`（站点首页 / 切换主题 / 下载APP / 留言 / 返回顶部） |
| 移动版站的「App 内打开」浮层 | `.m-open-app` / `.fixed-openapp` |

只按 id / class **精确**命中，不做模糊匹配——匹配错一次会藏掉站点主干道。
站点改版换了 id 就会失效，需要更新 `WebActivity` 里的 `TIDY_IDS` / `TIDY_SELECTORS`。

加载失败（域名不通 / 超时 / 证书错误 / 4xx / 5xx）会显示可读原因和三按钮面板，
而不是一片纯黑。网页视频全屏由 `videoStage` 容器承接，返回键退出全屏。

### 局域网传输（LanImportActivity）

首页第 3 个磁贴「局域网传输」→ 电视上起一个只在**这个页面存活**的 HTTP 服务，
同时把网址和二维码显示出来。手机扫码打开上传页后可以：

| 手机上发什么 | 电视做什么 |
|---|---|
| 上传 M3U / M3U8 / DPL / TXT 文件 | `PlaylistParser` 解析 → 存进播放列表 |
| 上传「站点清单」（一行一个网址） | 逐行去重 → 存进站点列表 |
| 发站点链接 | 补 `https://` → 存进站点列表 |
| 发播放列表链接 | **电视自己**下载该 URL 再解析，手机只发一个地址 |
| 发视频直链 | `MediaLauncher` 直接拉起播放器 |
| 选「自动识别」 | 按扩展名分流；`.m3u8` 嗅探是频道清单还是 HLS 直播流 |

```
首页「局域网传输」→ LanServer.start(8099) → QRCodeWriter 生成二维码
                                  │
手机扫码 → GET / 拿到 assets/lan_upload.html
        ├─ POST /api/upload（multipart）→ PlaylistStore / SiteStore
        ├─ POST /api/link（JSON）      → SiteStore / PlaylistStore / MediaLauncher
        └─ GET  /api/history          → 手机页显示「电视上最近收到的」
                                  │
电视页实时日志 + 「已收到 N 条」；按返回键退出 → onDestroy 关端口
```

服务用 JDK `ServerSocket` 手写（约 500 行），不引 nanohttpd / ktor，保持 `--offline`
可编译；二维码只用 ZXing `core` 的编码半边，不要相机权限。
UTF-8 解码失败会退回 GB18030，兼容 PotPlayer 导出的 ANSI 中文列表。

> ⚠️ **无鉴权**：同一局域网内任何设备都能访问该端口并写入站点/播放列表。
> 只在自己家的 WiFi 用，请勿在公共 WiFi 打开这个页面。页面与手机页均已提示。

详见 `docs/DECISIONS.md` D-010 与 `docs/局域网传输-验证步骤.md`。

---

## 关于播放

App **不解析、不解密、不代理**任何视频地址。

播放时把页面原样交给 WebView，站点自己的播放器、自己的权限校验、
自己的设备指纹和播放额度全部照常执行。App 做的只是：
把遥控器按键翻译成屏幕上的点击。

因此：

- 站点要登录，用户就在页面里登录
- 站点有播放额度，就按站点规则消耗
- 站点改播放器，App 不用改

---

## 构建

环境要求：

| 依赖 | 版本 |
|---|---|
| JDK | 21 |
| Android SDK | `platforms;android-34`、`build-tools;34.0.0` |
| Gradle | 8.9 |

```bash
# local.properties
sdk.dir=/path/to/android-sdk

./gradlew assembleDebug          # 调试包
./gradlew assembleRelease        # 发布包（需配置签名）
```

二维码依赖 `com.google.zxing:core:3.5.3`（纯 Java、无传递依赖、已缓存，
`--offline` 也能编），只用编码部分，不需要相机权限。

签名从 `local.properties` 读取，keystore 不入库：

```properties
storeFile=/absolute/path/to/release.keystore
storePassword=***
keyAlias=***
keyPassword=***
```

产物：`app/build/outputs/apk/<variant>/`

---

## 目录

```
TVWebPlayer/
├── app/src/main/
│   ├── assets/
│   │   ├── tvnav.js               空间导航 + 遥控器键盘的 JS 侧（网页视图核心）
│   │   ├── extract.js             整理视图的解析器
│   │   └── lan_upload.html        手机端局域网上传页
│   ├── java/com/tvweb/player/
│   │   ├── data/                  Site、SiteStore、Playlist 及解析器
│   │   ├── lan/                   LanServer（手写 HTTP）、LanAddresses
│   │   ├── ui/                    MainActivity、WebActivity、LanImportActivity、
│   │   │                          BrowseActivity、PlayerActivity、NativePlayerActivity
│   │   └── util/ImageLoader.kt    带 Referer 的图片加载
│   └── res/
├── dist/                           最新 debug APK + SHA256
└── docs/
    ├── ui-prototype.html          整理视图界面原型
    ├── DECISIONS.md               决策记录（D-007~D-010）
    ├── T1-验证步骤.md             网页视图遥控器验证
    ├── 局域网传输-验证步骤.md      扫码上传 / 发链接验证
    ├── t1-evidence/               T1 实测截图
    └── lan-evidence/              局域网传输实测截图
```

---

## 已知限制

- **系统软键盘**：MuMu / 部分盒子上应用内派发的触摸不经过系统输入通道，
  WebView 不建立 Android 编辑态，`showSoftInput` 唤不起 IME。因此改用 App 内置
  遥控器键盘；该面板只能直接输入 ASCII，中文需「粘贴」读剪贴板或点「系统键盘」
  切到真机上可用的 IME。详见 `docs/DECISIONS.md` D-009。
- **跨域 iframe**：受同源策略限制，主 frame 无法枚举其 DOM，只能把 iframe 整体
  当焦点目标、进入后由该 frame 自己注入的脚本接管。不做任何绕过。
- **`DOCUMENT_START_SCRIPT` 特性**：老系统 WebView 不支持时，注入退回
  `onPageFinished`，只覆盖主 frame，界面会提示。
- WebView 的 HLS/DASH 支持取决于设备系统 WebView 版本，老盒子可能放不了 m3u8
- 整理视图解析靠启发式，纯 Canvas 渲染的站点、需要滚动才加载的无限列表识别率会下降
- **局域网传输无鉴权**：同网段任何设备都能写入站点/播放列表，别在公共 WiFi 用；
  服务只在传输页打开时运行，退出即关闭端口
- **模拟器地址扫不出来**：MuMu 只有 `10.0.2.15`（NAT），宿主机连不上，
  真机/盒子会拿到 `192.168.x.x`；多网卡用「换一个地址」切换。
  模拟器调试用 `adb forward tcp:18099 tcp:8099` + `http://127.0.0.1:18099/`
- **局域网是纯 HTTP 无 TLS**，只传地址和文本列表，不传账号密码；App 不代理、不转存媒体
- 未适配竖屏/手机（目标只有 TV）

---

## 协议

本项目为独立实现，未使用任何第三方播放器内核或站点适配脚本。
