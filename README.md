# WEBTOON 折叠 / 去截图限制模块 (Xposed / LSPosed)

针对 **WEBTOON**（`com.naver.linewebtoon`，已验证版本 **26.102.0**）的 Xposed / LSPosed 模块，提供两个独立开关：

| 开关 | 作用 |
| --- | --- |
| **折叠锁定章节** | 在章节列表里隐藏「需要看广告解锁」或「需要付费解锁」的漫画章节 |
| **关闭截图限制** | 移除 `FLAG_SECURE`，允许对 App 内任何界面截图 / 录屏 |

两个开关互不影响，可在模块设置里随时勾选，修改后重启 WEBTOON 生效。

---

## 实现原理（基于反编译，26.102.0）

标题详情页真正画出来的列表不是网络模型 `d00.h`，而是：

| 页面 | 界面模型 | 列表怎么发出去 |
| --- | --- | --- |
| 正版 | `OriginalEpisodeUiModel` | `OriginalTitleHomeEpisodesViewModelImpl` 的 LiveData，`setValue`（混淆方法 `androidx.lifecycle.i0.j`） |
| Canvas / 同人 | `CanvasEpisodeUiModel` | 同一个被混淆的 LiveData 类 |

正版章节的锁定状态由 `GetEpisodeStatusUseCase` 写成 `EpisodeUiStatus`：

- 隐藏：`Coin`（付费）、`RewardedAd`（要看广告）、`TimeDeal`（限时付费，还没买）
- 保留：`Free`、`HasInfiniteRight`（已购）、`HasRewardedAdRight`（广告已看完）、`HasTimeDealRight`

个人数据（是否已购）是在 `applyPersonalDataToList` 之后才写进这份列表的，所以在 `setValue` 上过滤，已购章节不会被误藏。占位行（`isPlaceHolder`）会留下。

Canvas 没有 `EpisodeUiStatus`，用 `rewardAdEpisode && !rewarded` 判断还没看完广告的章节。

截图限制已核对调用点：`WebtoonViewerActivity.onCreate` 调用 `Window.addFlags(0x2000)`，播放器 `SurfaceView.setSecure(true)`。模块拦截 `setFlags` / `addFlags` / `setAttributes`，并在 `Activity.onResume` 里再清一次 `FLAG_SECURE`。

---

## 构建

### 方式一：GitHub Actions（推荐）

1. 把本目录（`webtoon-fold/`）作为独立 git 仓库推到 GitHub。
2. 仓库里已包含 `.github/workflows/build.yml`：
   - 使用 **JDK 25**（Oracle 发行版，对应你本地的 `D:\Program Files\Java\jdk-25.0.2`）；
   - 安装 Android SDK（`platforms;android-35`、`build-tools;36.0.0`）；
   - 自动生成 Gradle wrapper（Gradle 9.4.1，AGP 9.2.0）并 `assembleRelease`。
     Gradle 8.14 不能在 JDK 25 上运行。
3. 在 Actions 页面手动触发（workflow_dispatch）或 push 后自动构建，产物 APK 在 Artifacts 里下载。

> 若 CI 报 Gradle / AGP 版本不兼容，把 `gradle-wrapper.properties` 的 Gradle 版本
> 和 `app/build.gradle.kts` 的 `com.android.application` 版本一起往上调，并保持官方兼容表。

### 方式二：本地构建（JDK 25）

```bat
set JAVA_HOME=D:\Program Files\Java\jdk-25.0.2
cd webtoon-fold
:: 没有 wrapper 就先生成；装了 gradle 也可直接 gradle wrapper --gradle-version 9.4.1
gradle wrapper --gradle-version 9.4.1
gradlew assembleRelease
:: 产物在 app\build\outputs\apk\release\app-release.apk
```

> 本地需要一份 Android SDK（含 platform-35、build-tools 36.0.0），并配置 `ANDROID_HOME`。
> Release 包用 debug 证书签名，可以直接安装；这是个人模块，不要拿这个签名去发商店。

---

## 安装 / 使用

1. 设备已安装 **LSPosed**（或兼容的 Xposed 框架，API ≥ 82）。
2. 安装模块 APK，在 LSPosed 中**勾选启用**并**作用域选 WEBTOON**。
3. 打开模块（桌面图标或 LSPosed 里的模块设置），勾选需要的开关。
4. 重启 WEBTOON。

---

## 已知限制 / 注意事项

- 折叠只作用于标题详情页的正版列表和 Canvas 列表。推荐流、阅读器里的「下一话」入口不在这两份 LiveData 里。
- 界面模型类名（`OriginalEpisodeUiModel`、`EpisodeUiStatus`）在 26.102.0 没有被混淆。LiveData 被混淆成 `androidx.lifecycle.i0` / `l0`。模块会先从 `OriginalTitleHomeEpisodesViewModelImpl` 的字段签名找 `setValue`，找不到再用这两个名字。App 大版本更新后如果折叠失效，重新反编译核对这几个类。
- 开关写入后，模块在每次回调里重新读 prefs。已经打开的 WEBTOON 进程要重新进入章节列表或阅读器（或直接划掉进程）才会看到变化。
- 本模块仅用于学习与研究，请遵守你所在地区关于修改应用的相关法律法规。
