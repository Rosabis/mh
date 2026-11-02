package com.webtoon.fold;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Xml;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;

/**
 * Simple settings UI. Preferences are stored in a world-readable-ish shared pref
 * ("config") that the Xposed hook reads via XSharedPreferences.
 */
public class SettingsActivity extends Activity {

    public static final String PREFS = "config";
    public static final String KEY_FOLD = "fold_locked";
    public static final String KEY_SHOT = "disable_screenshot";

    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = openModulePrefs();

        Switch swFold = findViewById(R.id.sw_fold);
        Switch swShot = findViewById(R.id.sw_shot);
        TextView tvInfo = findViewById(R.id.tv_info);

        swFold.setChecked(prefs.getBoolean(KEY_FOLD, true));
        swShot.setChecked(prefs.getBoolean(KEY_SHOT, true));

        CompoundButton.OnCheckedChangeListener save = (buttonView, isChecked) -> {
            SharedPreferences.Editor editor = prefs.edit();
            if (buttonView == swFold) {
                editor.putBoolean(KEY_FOLD, isChecked);
            } else if (buttonView == swShot) {
                editor.putBoolean(KEY_SHOT, isChecked);
            }
            editor.commit();
            makePrefsReadable();
            tvInfo.setText(getString(R.string.hint_restart));
        };
        makePrefsReadable();
        swFold.setOnCheckedChangeListener(save);
        swShot.setOnCheckedChangeListener(save);

        tvInfo.setText(getString(R.string.hint_restart));
    }

    /**
     * LSPosed 只在 MODE_WORLD_READABLE 时把配置重定向到
     * /data/misc/apexdata/.../prefs/，目标进程里的 XSharedPreferences 才能读到。
     * 普通 MODE_PRIVATE 写在本应用私有目录，Android 16 上 WEBTOON 读不到，开关会一直是默认值。
     */
    @SuppressWarnings("deprecation")
    private SharedPreferences openModulePrefs() {
        SharedPreferences opened;
        try {
            opened = getSharedPreferences(PREFS, Context.MODE_WORLD_READABLE);
        } catch (SecurityException e) {
            opened = getSharedPreferences(PREFS, MODE_PRIVATE);
        }
        if (!opened.contains(KEY_FOLD) && !opened.contains(KEY_SHOT)) {
            migrateLegacy(opened);
        }
        return opened;
    }

    /** 把上一版写在私有目录里的 config.xml 拷进 LSPosed 可共享目录。 */
    private void migrateLegacy(SharedPreferences target) {
        File legacy = new File(getApplicationInfo().dataDir, "shared_prefs/" + PREFS + ".xml");
        if (!legacy.isFile()) {
            return;
        }
        FileInputStream in = null;
        try {
            XmlPullParser parser = Xml.newPullParser();
            in = new FileInputStream(legacy);
            parser.setInput(in, "utf-8");
            Boolean fold = null;
            Boolean shot = null;
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event != XmlPullParser.START_TAG || !"boolean".equals(parser.getName())) {
                    continue;
                }
                String name = parser.getAttributeValue(null, "name");
                boolean value = Boolean.parseBoolean(parser.getAttributeValue(null, "value"));
                if (KEY_FOLD.equals(name)) {
                    fold = value;
                } else if (KEY_SHOT.equals(name)) {
                    shot = value;
                }
            }
            if (fold == null && shot == null) {
                return;
            }
            SharedPreferences.Editor editor = target.edit();
            if (fold != null) {
                editor.putBoolean(KEY_FOLD, fold);
            }
            if (shot != null) {
                editor.putBoolean(KEY_SHOT, shot);
            }
            editor.commit();
        } catch (Throwable ignored) {
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 旧版 Xposed 直接读文件；新版 LSPosed 不依赖这个，失败也无所谓。 */
    private void makePrefsReadable() {
        try {
            File prefsFile = new File(getApplicationInfo().dataDir, "shared_prefs/" + PREFS + ".xml");
            File dir = prefsFile.getParentFile();
            if (dir != null) {
                dir.setReadable(true, false);
                dir.setExecutable(true, false);
            }
            prefsFile.setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }
}
