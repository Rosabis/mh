package com.webtoon.fold;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
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
 * 折叠锁定章节：不从列表里删掉。免费、已购、已看完广告的章节留在原位。
 * 连续的锁定章节收成一行，点击展开，长按锁定章节再收起。
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

    private static final int FLAG_SECURE = 0x2000;
    /** 避免和 App 自己的 view tag 撞车。 */
    private static final int TAG_FOLD = 0x6A0F1001;
    private static final int TAG_PREV_VIS = 0x6A0F1002;
    private static final int TAG_PREV_MARGIN = 0x6A0F1003;
    private static final String HEADER_TAG = "webtoon_fold_bar";
    private static final int GREEN = 0xFF00C73C;

    private final Set<String> expanded = new HashSet<>();

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
    // 折叠：不删数据。免费章留在原位，锁定章收成可展开的一行。
    // ------------------------------------------------------------------

    private final View.OnTouchListener foldTouch = new View.OnTouchListener() {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            Object tag = v.getTag(TAG_FOLD);
            if (!(tag instanceof FoldMark)) {
                return false;
            }
            if (event.getAction() == MotionEvent.ACTION_UP) {
                final FoldMark mark = (FoldMark) tag;
                if (mark.expanded) {
                    expanded.remove(mark.anchor);
                } else {
                    expanded.add(mark.anchor);
                }
                v.post(new Runnable() {
                    @Override
                    public void run() {
                        notifyAdapter(mark.adapter);
                    }
                });
            }
            return true;
        }
    };

    private void hookEpisodeLists(XC_LoadPackage.LoadPackageParam lpparam) {
        hookBind(lpparam.classLoader, "xq.d");
        hookBind(lpparam.classLoader, "xq.a");
    }

    private void hookBind(ClassLoader loader, String adapterClass) {
        try {
            XposedHelpers.findAndHookMethod(adapterClass, loader, "onBindViewHolder",
                    "androidx.recyclerview.widget.RecyclerView$e0", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            foldRow(param.thisObject, param.args[0], (Integer) param.args[1]);
                        }
                    });
            log("hook " + adapterClass + "#onBindViewHolder");
        } catch (Throwable t) {
            log(t);
        }
    }

    private void foldRow(Object adapter, Object holder, int position) {
        try {
            Object binding = XposedHelpers.getObjectField(holder, "W");
            View root = (View) XposedHelpers.getObjectField(binding, "V");
            View click = clickTarget(binding);
            if (!foldEnabled()) {
                clearHeader(root);
                showRow(root);
                if (click != null) {
                    click.setTag(TAG_FOLD, null);
                }
                return;
            }
            List<?> list = (List<?>) XposedHelpers.callMethod(adapter, "getCurrentList");
            Group group = groupAt(list, position);
            if (group == null) {
                clearHeader(root);
                showRow(root);
                if (click != null) {
                    click.setTag(TAG_FOLD, null);
                    click.setOnLongClickListener(null);
                }
                return;
            }
            boolean open = expanded.contains(group.anchor);
            if (position != group.start) {
                clearHeader(root);
                if (click != null) {
                    click.setTag(TAG_FOLD, null);
                }
                if (open) {
                    showRow(root);
                } else {
                    hideRow(root);
                }
                return;
            }
            showRow(root);
            if (click != null) {
                click.setTag(TAG_FOLD, null);
            }
            showHeader(root, list, group, new FoldMark(adapter, group.anchor, open), open);
        } catch (Throwable t) {
            if (!loggedStatusFailure) {
                loggedStatusFailure = true;
                log(t);
            }
        }
    }

    /** 当前行如果落在一段连续锁定章节里，返回这一段；免费章返回 null。 */
    private Group groupAt(List<?> list, int position) {
        if (list == null || position < 0 || position >= list.size()) {
            return null;
        }
        if (!isLocked(list.get(position))) {
            return null;
        }
        int start = position;
        while (start > 0 && isLocked(list.get(start - 1))) {
            start--;
        }
        int end = position;
        while (end + 1 < list.size() && isLocked(list.get(end + 1))) {
            end++;
        }
        Object anchorItem = list.get(start);
        String anchor = anchorItem.getClass().getName() + "#" + episodeNo(anchorItem);
        return new Group(start, end - start + 1, anchor);
    }

    /**
     * 只认界面上会画出锁的三种状态。Free 和 Has*Right（已购、已看完广告、已买限时）都不折叠。
     */
    private boolean isLocked(Object episode) {
        if (episode == null) {
            return false;
        }
        try {
            String type = episode.getClass().getName();
            if (ORIGINAL_UI.equals(type)) {
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
            if (CANVAS_UI.equals(type)) {
                if (Boolean.TRUE.equals(XposedHelpers.callMethod(episode, "isPlaceHolder"))) {
                    return false;
                }
                boolean rewardAd = Boolean.TRUE.equals(
                        XposedHelpers.callMethod(episode, "getRewardAdEpisode"));
                boolean rewarded = Boolean.TRUE.equals(
                        XposedHelpers.callMethod(episode, "getRewarded"));
                return rewardAd && !rewarded;
            }
        } catch (Throwable t) {
            if (!loggedStatusFailure) {
                loggedStatusFailure = true;
                log(t);
            }
        }
        return false;
    }

    private static int episodeNo(Object episode) {
        try {
            Object value = XposedHelpers.callMethod(episode, "getEpisodeNo");
            if (value instanceof Integer) {
                return (Integer) value;
            }
        } catch (Throwable ignored) {
        }
        return System.identityHashCode(episode);
    }

    /** 正版行点击区域是 a0，Canvas 是 Z。 */
    private static View clickTarget(Object binding) {
        for (String field : new String[]{"a0", "Z"}) {
            try {
                Object view = XposedHelpers.getObjectField(binding, field);
                if (view instanceof View) {
                    return (View) view;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private void showHeader(View root, List<?> list, Group group, FoldMark mark, boolean open) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) root;
        View bar = parent.findViewWithTag(HEADER_TAG);
        if (bar == null) {
            bar = createHeader(parent);
            parent.addView(bar);
        }
        if (open) {
            restoreContent(parent);
            offsetContent(parent, bar, dp(parent.getContext(), 60));
        } else {
            hideContent(parent, bar);
            resetOffset(parent);
        }
        bindHeader(bar, list, group, open);
        bar.setTag(TAG_FOLD, mark);
        bar.setOnTouchListener(foldTouch);
        bar.setClickable(true);
        parent.bringChildToFront(bar);
    }

    private static void clearHeader(View root) {
        if (!(root instanceof ViewGroup)) {
            return;
        }
        ViewGroup parent = (ViewGroup) root;
        restoreContent(parent);
        resetOffset(parent);
        View bar = parent.findViewWithTag(HEADER_TAG);
        if (bar != null) {
            parent.removeView(bar);
        }
    }

    private static void hideContent(ViewGroup root, View bar) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == bar) {
                continue;
            }
            if (child.getTag(TAG_PREV_VIS) == null) {
                child.setTag(TAG_PREV_VIS, child.getVisibility());
            }
            child.setVisibility(View.GONE);
        }
    }

    private static void restoreContent(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            Object prev = child.getTag(TAG_PREV_VIS);
            if (prev instanceof Integer) {
                child.setVisibility((Integer) prev);
                child.setTag(TAG_PREV_VIS, null);
            }
        }
    }

    /** 展开时把原来的章节行往下挪，折叠条留在上面。 */
    private static void offsetContent(ViewGroup root, View bar, int headerHeight) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            if (child == bar) {
                continue;
            }
            ViewGroup.LayoutParams raw = child.getLayoutParams();
            if (!(raw instanceof ViewGroup.MarginLayoutParams)) {
                continue;
            }
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) raw;
            if (child.getTag(TAG_PREV_MARGIN) == null) {
                child.setTag(TAG_PREV_MARGIN, lp.topMargin);
            }
            int base = (Integer) child.getTag(TAG_PREV_MARGIN);
            lp.topMargin = base + headerHeight;
            child.setLayoutParams(lp);
        }
    }

    private static void resetOffset(ViewGroup root) {
        for (int i = 0; i < root.getChildCount(); i++) {
            View child = root.getChildAt(i);
            Object prev = child.getTag(TAG_PREV_MARGIN);
            if (!(prev instanceof Integer)) {
                continue;
            }
            ViewGroup.LayoutParams raw = child.getLayoutParams();
            if (raw instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) raw).topMargin = (Integer) prev;
                child.setLayoutParams(raw);
            }
            child.setTag(TAG_PREV_MARGIN, null);
        }
    }

    private static View createHeader(ViewGroup parent) {
        Context context = parent.getContext();
        LinearLayout bar = new LinearLayout(context);
        bar.setTag(HEADER_TAG);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(Color.WHITE);
        int padH = dp(context, 16);
        int padV = dp(context, 12);
        bar.setPadding(padH, padV, padH, padV);
        FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.gravity = Gravity.TOP;
        bar.setLayoutParams(barLp);

        FrameLayout stack = new FrameLayout(context);
        bar.addView(stack, new LinearLayout.LayoutParams(dp(context, 84), dp(context, 36)));
        for (int i = 0; i < 3; i++) {
            FrameLayout ring = new FrameLayout(context);
            ring.setTag("webtoon_fold_ring_" + i);
            GradientDrawable disc = new GradientDrawable();
            disc.setShape(GradientDrawable.OVAL);
            disc.setColor(Color.WHITE);
            ring.setBackground(disc);
            FrameLayout.LayoutParams ringLp = new FrameLayout.LayoutParams(dp(context, 36), dp(context, 36));
            ringLp.leftMargin = i * dp(context, 24);
            stack.addView(ring, ringLp);

            ImageView avatar = new ImageView(context);
            avatar.setTag("webtoon_fold_avatar_" + i);
            avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
            avatar.setClipToOutline(true);
            avatar.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setOval(0, 0, view.getWidth(), view.getHeight());
                }
            });
            FrameLayout.LayoutParams avatarLp = new FrameLayout.LayoutParams(dp(context, 32), dp(context, 32));
            avatarLp.gravity = Gravity.CENTER;
            ring.addView(avatar, avatarLp);
        }

        TextView label = new TextView(context);
        label.setTag("webtoon_fold_label");
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        label.setTextColor(0xFF222222);
        label.setMaxLines(1);
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        labelLp.leftMargin = dp(context, 12);
        labelLp.rightMargin = dp(context, 8);
        bar.addView(label, labelLp);

        TextView chevron = new TextView(context);
        chevron.setTag("webtoon_fold_chevron");
        chevron.setText("›");
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        chevron.setTextColor(0xFFB0B0B0);
        chevron.setIncludeFontPadding(false);
        chevron.setGravity(Gravity.CENTER);
        bar.addView(chevron, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    private static void bindHeader(View bar, List<?> list, Group group, boolean open) {
        TextView label = bar.findViewWithTag("webtoon_fold_label");
        if (label != null) {
            String count = String.valueOf(group.count);
            String text = count + " 個最新話次在等著你唷！";
            SpannableString styled = new SpannableString(text);
            styled.setSpan(new ForegroundColorSpan(GREEN), 0, count.length(),
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            styled.setSpan(new StyleSpan(Typeface.BOLD), 0, count.length(),
                    SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
            label.setText(styled);
        }
        TextView chevron = bar.findViewWithTag("webtoon_fold_chevron");
        if (chevron != null) {
            // 收起时箭头横着指向右边，展开后转到朝下。
            chevron.setRotation(open ? 90f : 0f);
        }
        String[] urls = new String[3];
        int filled = 0;
        for (int i = group.start; i < group.start + group.count && filled < 3; i++) {
            String url = thumbnail(list.get(i));
            if (url != null && url.length() > 0) {
                urls[filled++] = url;
            }
        }
        for (int i = 0; i < 3; i++) {
            View ring = bar.findViewWithTag("webtoon_fold_ring_" + i);
            ImageView avatar = bar.findViewWithTag("webtoon_fold_avatar_" + i);
            if (ring == null) {
                continue;
            }
            if (i >= filled || avatar == null) {
                ring.setVisibility(View.GONE);
                continue;
            }
            ring.setVisibility(View.VISIBLE);
            loadCircle(avatar, urls[i]);
        }
    }

    private static String thumbnail(Object episode) {
        try {
            Object value = XposedHelpers.callMethod(episode, "getEpisodeThumbnail");
            return value instanceof String ? (String) value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void loadCircle(ImageView avatar, String url) {
        try {
            Class<?> loader = XposedHelpers.findClass(
                    "com.naver.linewebtoon.util.j", avatar.getContext().getClassLoader());
            XposedHelpers.callStaticMethod(loader, "d", 0, 2, url, avatar);
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void showRow(View root) {
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        if (lp != null) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            root.setLayoutParams(lp);
        }
        root.setVisibility(View.VISIBLE);
    }

    private static void hideRow(View root) {
        ViewGroup.LayoutParams lp = root.getLayoutParams();
        if (lp != null) {
            lp.height = 0;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            root.setLayoutParams(lp);
        }
        root.setVisibility(View.GONE);
    }

    private static void notifyAdapter(Object adapter) {
        try {
            XposedHelpers.callMethod(adapter, "notifyDataSetChanged");
        } catch (Throwable t) {
            log(t);
        }
    }

    private static final class Group {
        final int start;
        final int count;
        final String anchor;

        Group(int start, int count, String anchor) {
            this.start = start;
            this.count = count;
            this.anchor = anchor;
        }
    }

    private static final class FoldMark {
        final Object adapter;
        final String anchor;
        final boolean expanded;

        FoldMark(Object adapter, String anchor, boolean expanded) {
            this.adapter = adapter;
            this.anchor = anchor;
            this.expanded = expanded;
        }
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    private static void log(Throwable t) {
        XposedBridge.log("[" + TAG + "] " + t);
        XposedBridge.log(t);
    }
}
