package com.webtoon.fold;

import android.app.Activity;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * WEBTOON（com.naver.linewebtoon）26.102.0 的 LSPosed 模块。
 *
 * 折叠锁定章节：拦截界面实际观察的 LiveData.setValue / postValue。
 * 列表元素是未混淆的 OriginalEpisodeUiModel / CanvasEpisodeUiModel，
 * 锁定与否看 EpisodeUiStatus（已购、已看完广告的章节会变成 Has*Right，保留）。
 *
 * 关闭截图限制：抹掉 Window 的 FLAG_SECURE，以及播放器 SurfaceView.setSecure(true)。
 */
public class XposedMod implements IXposedHookLoadPackage {

    private static final String TAG = "WebtoonFold";

    private static final String TARGET_PKG = "com.naver.linewebtoon";
    private static final String MODULE_PKG = "com.webtoon.fold";

    /** 正版标题详情页章节行。类名在 26.102.0 未被混淆。 */
    private static final String ORIGINAL_UI =
            "com.naver.linewebtoon.episode.list.model.OriginalEpisodeUiModel";
    /** Canvas / 同人标题详情页章节行。 */
    private static final String CANVAS_UI =
            "com.naver.linewebtoon.episode.list.model.CanvasEpisodeUiModel";

    /**
     * GetEpisodeStatusUseCase 在「还没解锁」时返回的三个状态。
     * HasInfiniteRight / HasRewardedAdRight / HasTimeDealRight / Free 都要留下。
     */
    private static final String STATUS_COIN =
            "com.naver.linewebtoon.episode.list.model.EpisodeUiStatus$Coin";
    private static final String STATUS_REWARD_AD =
            "com.naver.linewebtoon.episode.list.model.EpisodeUiStatus$RewardedAd";
    private static final String STATUS_TIME_DEAL =
            "com.naver.linewebtoon.episode.list.model.EpisodeUiStatus$TimeDeal";

    /**
     * 26.102.0 里 androidx LiveData / MutableLiveData 被混淆成这两个类。
     * setValue = i0.j，postValue = l0.k。优先从 ViewModel 字段动态找，找不到再用这里。
     */
    private static final String LIVE_DATA = "androidx.lifecycle.i0";
    private static final String MUTABLE_LIVE_DATA = "androidx.lifecycle.l0";
    private static final String ORIGINAL_VM =
            "com.naver.linewebtoon.episode.list.viewmodel.webtoon.OriginalTitleHomeEpisodesViewModelImpl";

    private static final int FLAG_SECURE = 0x2000;

    private XSharedPreferences prefs;
    private boolean loggedStatusFailure;
    private boolean loggedSecureStrip;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!TARGET_PKG.equals(lpparam.packageName)) {
            return;
        }

        prefs = new XSharedPreferences(MODULE_PKG, SettingsActivity.PREFS);
        File prefFile = prefs.getFile();
        boolean readable = prefFile != null && prefFile.canRead();
        if (!readable) {
            prefs.makeWorldReadable();
            readable = prefFile != null && prefFile.canRead();
        }
        prefs.reload();
        log("loaded fold=" + foldEnabled() + " shot=" + shotEnabled()
                + " prefs=" + prefFile + " readable=" + readable);

        hookScreenshot();
        hookEpisodeLists(lpparam);
    }

    private boolean foldEnabled() {
        if (prefs != null) {
            prefs.reload();
            return prefs.getBoolean(SettingsActivity.KEY_FOLD, true);
        }
        return true;
    }

    private boolean shotEnabled() {
        if (prefs != null) {
            prefs.reload();
            return prefs.getBoolean(SettingsActivity.KEY_SHOT, true);
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 截图：只影响当前进程（handleLoadPackage 已按包名返回）
    // ------------------------------------------------------------------

    private void hookScreenshot() {
        XC_MethodHook stripFlags = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!shotEnabled() || !(param.args[0] instanceof Integer)) {
                    return;
                }
                int flags = (Integer) param.args[0];
                if ((flags & FLAG_SECURE) != 0) {
                    param.args[0] = flags & ~FLAG_SECURE;
                    if (!loggedSecureStrip) {
                        loggedSecureStrip = true;
                        log("cleared FLAG_SECURE");
                    }
                }
            }
        };

        try {
            XposedHelpers.findAndHookMethod(Window.class, "setFlags", int.class, int.class, stripFlags);
        } catch (Throwable t) {
            log(t);
        }
        try {
            XposedHelpers.findAndHookMethod(Window.class, "addFlags", int.class, stripFlags);
        } catch (Throwable t) {
            log(t);
        }
        try {
            XposedHelpers.findAndHookMethod(Window.class, "setAttributes",
                    WindowManager.LayoutParams.class, new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!shotEnabled()) {
                                return;
                            }
                            if (param.args[0] instanceof WindowManager.LayoutParams) {
                                WindowManager.LayoutParams lp =
                                        (WindowManager.LayoutParams) param.args[0];
                                lp.flags &= ~FLAG_SECURE;
                            }
                        }
                    });
        } catch (Throwable t) {
            log(t);
        }
        try {
            XposedHelpers.findAndHookMethod(SurfaceView.class, "setSecure", boolean.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (shotEnabled() && Boolean.TRUE.equals(param.args[0])) {
                                param.args[0] = false;
                            }
                        }
                    });
        } catch (Throwable t) {
            log(t);
        }
        // 阅读器在 onCreate 里 addFlags(FLAG_SECURE)。onResume 再清一次，覆盖已经加上的窗口。
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!shotEnabled()) {
                        return;
                    }
                    Window window = ((Activity) param.thisObject).getWindow();
                    if (window != null) {
                        window.clearFlags(FLAG_SECURE);
                    }
                }
            });
        } catch (Throwable t) {
            log(t);
        }
    }

    // ------------------------------------------------------------------
    // 折叠：标题详情页（正版 + Canvas）真正显示的那份列表
    // ------------------------------------------------------------------

    private void hookEpisodeLists(XC_LoadPackage.LoadPackageParam lpparam) {
        Set<Method> targets = new HashSet<>();
        collectFromViewModel(lpparam.classLoader, targets);
        if (targets.isEmpty()) {
            addObjectConsumer(lpparam.classLoader, LIVE_DATA, targets);
            addObjectConsumer(lpparam.classLoader, MUTABLE_LIVE_DATA, targets);
        }
        if (targets.isEmpty()) {
            log("no LiveData setValue/postValue to hook; fold disabled");
            return;
        }

        XC_MethodHook filter = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!foldEnabled() || !(param.args[0] instanceof List)) {
                    return;
                }
                List<?> filtered = filterEpisodes((List<?>) param.args[0]);
                if (filtered != null) {
                    param.args[0] = filtered;
                }
            }
        };

        int hooked = 0;
        for (Method method : targets) {
            try {
                XposedBridge.hookMethod(method, filter);
                hooked++;
                log("hook " + method.getDeclaringClass().getName() + "#" + method.getName());
            } catch (Throwable t) {
                log(t);
            }
        }
        if (hooked == 0) {
            log("episode list hooks failed");
        }
    }

    /**
     * 从正版章节 ViewModel 的字段签名找到 LiveData 类，再挂上 setValue / postValue。
     * Canvas 用的是同一个被混淆的 LiveData 类，所以一处 hook 两边都生效。
     */
    private void collectFromViewModel(ClassLoader loader, Set<Method> targets) {
        try {
            Class<?> vm = XposedHelpers.findClass(ORIGINAL_VM, loader);
            Class<?> liveData = null;
            for (Field field : vm.getDeclaredFields()) {
                Type generic = field.getGenericType();
                if (generic != null && generic.getTypeName().contains("OriginalEpisodeUiModel")) {
                    liveData = field.getType();
                    break;
                }
            }
            if (liveData == null) {
                return;
            }
            Class<?> type = liveData;
            while (type != null && type != Object.class) {
                addObjectConsumers(type, targets);
                type = type.getSuperclass();
            }
        } catch (Throwable t) {
            log("viewmodel discovery failed, using 26.102.0 names: " + t.getMessage());
        }
    }

    private void addObjectConsumer(ClassLoader loader, String className, Set<Method> targets) {
        try {
            addObjectConsumers(XposedHelpers.findClass(className, loader), targets);
        } catch (Throwable t) {
            log(t);
        }
    }

    private static void addObjectConsumers(Class<?> type, Set<Method> targets) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getReturnType() == void.class
                    && method.getParameterCount() == 1
                    && method.getParameterTypes()[0] == Object.class) {
                method.setAccessible(true);
                targets.add(method);
            }
        }
    }

    /**
     * @return 过滤后的新列表；不需要改（不是章节列表，或没有锁章）时返回 null
     */
    private List<?> filterEpisodes(List<?> source) {
        Kind kind = Kind.NONE;
        for (Object item : source) {
            if (item == null) {
                continue;
            }
            String name = item.getClass().getName();
            if (ORIGINAL_UI.equals(name)) {
                kind = Kind.ORIGINAL;
            } else if (CANVAS_UI.equals(name)) {
                kind = Kind.CANVAS;
            }
            break;
        }
        if (kind == Kind.NONE) {
            return null;
        }

        ArrayList<Object> kept = new ArrayList<>(source.size());
        int removed = 0;
        for (Object item : source) {
            if (item != null && isLocked(kind, item)) {
                removed++;
            } else {
                kept.add(item);
            }
        }
        if (removed == 0) {
            return null;
        }
        log("folded " + removed + " " + kind.name().toLowerCase() + " episode(s)");
        return Collections.unmodifiableList(kept);
    }

    private boolean isLocked(Kind kind, Object episode) {
        try {
            if (kind == Kind.ORIGINAL) {
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(episode, "isPlaceHolder"))) {
                    return false;
                }
                Object status = XposedHelpers.callMethod(episode, "getEpisodeStatus");
                if (status == null) {
                    return false;
                }
                String name = status.getClass().getName();
                return STATUS_COIN.equals(name)
                        || STATUS_REWARD_AD.equals(name)
                        || STATUS_TIME_DEAL.equals(name);
            }
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(episode, "isPlaceHolder"))) {
                return false;
            }
            boolean rewardAd = Boolean.TRUE.equals(
                    XposedHelpers.callMethod(episode, "getRewardAdEpisode"));
            boolean rewarded = Boolean.TRUE.equals(
                    XposedHelpers.callMethod(episode, "getRewarded"));
            return rewardAd && !rewarded;
        } catch (Throwable t) {
            if (!loggedStatusFailure) {
                loggedStatusFailure = true;
                log(t);
            }
            return false;
        }
    }

    private enum Kind {
        NONE, ORIGINAL, CANVAS
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    private static void log(Throwable t) {
        XposedBridge.log("[" + TAG + "] " + t);
        XposedBridge.log(t);
    }
}
