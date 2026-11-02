package com.webtoon.fold;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;

import java.io.File;

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

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        Switch swFold = findViewById(R.id.sw_fold);
        Switch swShot = findViewById(R.id.sw_shot);
        TextView tvInfo = findViewById(R.id.tv_info);

        swFold.setChecked(prefs.getBoolean(KEY_FOLD, false));
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

    /** 旧版 Xposed 直接读文件；LSPosed 不依赖这个，失败也无所谓。 */
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
