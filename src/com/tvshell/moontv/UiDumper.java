package com.tvshell.moontv;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;

/**
 * UI 树导出器 + 应用内交互（嵌入版，不依赖无障碍服务）。
 *
 * 核心思路：被调试应用 dump 自己的 View 树，不需要跨应用访问权限。
 * 与 ADB `uiautomator dump` 原理相同，只是输出走 FTP。
 */
public class UiDumper {

    private static final String TAG = "moontv-ui";
    private static final int MAX_DEPTH = 50;
    private static final int MAX_NODES = 500;

    private final Context mCtx;
    private int mNodeCount = 0;

    /** 当前前台 Activity（由各 Activity 生命周期设置） */
    public static android.app.Activity sCurrentActivity;

    public UiDumper(Context ctx) {
        mCtx = ctx;
    }

    /** 导出当前 Activity 的 UI 树为 JSON */
    public String dumpCurrent() {
        mNodeCount = 0;
        try {
            android.app.Activity act = getCurrentActivity();
            if (act == null) return null;
            View root = act.getWindow().getDecorView();
            if (root == null) return null;
            JSONObject json = new JSONObject();
            dumpView(root, json, 0);
            return json.toString();
        } catch (Throwable t) {
            Diag.e("UI", "dump fail", t);
            return null;
        }
    }

    private void dumpView(View v, JSONObject parent, int depth) {
        if (v == null || depth > MAX_DEPTH || mNodeCount >= MAX_NODES) return;
        mNodeCount++;
        try {
            parent.put("class", v.getClass().getSimpleName());
            parent.put("id", v.getId());
            try {
                parent.put("id_name", v.getContext().getResources().getResourceName(v.getId()));
            } catch (Exception ignore) {
                parent.put("id_name", "");
            }

            if (v instanceof android.widget.TextView) {
                android.widget.TextView tv = (android.widget.TextView) v;
                parent.put("text", tv.getText() != null ? tv.getText().toString() : "");
                parent.put("hint", tv.getHint() != null ? tv.getHint().toString() : "");
            }

            int[] loc = new int[2];
            v.getLocationOnScreen(loc);
            parent.put("bounds", "[" + loc[0] + "," + loc[1] + "," +
                       (loc[0] + v.getWidth()) + "," + (loc[1] + v.getHeight()) + "]");
            parent.put("visible", v.getVisibility() == View.VISIBLE);
            parent.put("clickable", v.isClickable());
            parent.put("focusable", v.isFocusable());
            parent.put("focused", v.isFocused());
            parent.put("enabled", v.isEnabled());
            parent.put("desc", v.getContentDescription() != null ? v.getContentDescription().toString() : "");

            JSONArray children = new JSONArray();
            if (v instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) v;
                for (int i = 0; i < group.getChildCount(); i++) {
                    JSONObject child = new JSONObject();
                    dumpView(group.getChildAt(i), child, depth + 1);
                    children.put(child);
                }
            }
            parent.put("children", children);
        } catch (Exception ignore) {}
    }

    private android.app.Activity getCurrentActivity() {
        return sCurrentActivity;
    }

    /** 截图当前 Activity 的 UI */
    public String takeScreenshot() {
        try {
            android.app.Activity act = getCurrentActivity();
            if (act == null) return null;
            View root = act.getWindow().getDecorView();
            if (root == null) return null;
            root.setDrawingCacheEnabled(true);
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(root.getDrawingCache());
            root.setDrawingCacheEnabled(false);
            if (bitmap == null) return null;

            File dir = Diag.getFtpSharedDir(mCtx);
            if (dir == null) return null;
            File shot = new File(dir, "moontv_shot.png");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
            bitmap.recycle();
            try (FileOutputStream fos = new FileOutputStream(shot)) {
                fos.write(out.toByteArray());
            }
            return shot.getAbsolutePath();
        } catch (Throwable t) {
            Diag.e("UI", "screenshot fail", t);
            return null;
        }
    }

    /** 应用内模拟点击（通过当前 Activity 的窗口） */
    public boolean tap(int x, int y) {
        android.app.Activity act = getCurrentActivity();
        if (act == null) return false;
        return tapView(act, x, y);
    }

    private boolean tapView(android.app.Activity act, int x, int y) {
        View root = act.getWindow().getDecorView();
        if (root == null) return false;
        View target = findViewByBounds(root, x, y);
        if (target != null && target.isClickable()) {
            target.performClick();
            return true;
        }
        return false;
    }

    private View findViewByBounds(View v, int x, int y) {
        int[] loc = new int[2];
        v.getLocationOnScreen(loc);
        int left = loc[0], top = loc[1];
        int right = left + v.getWidth(), bottom = top + v.getHeight();
        if (x >= left && x <= right && y >= top && y <= bottom) {
            if (v.isClickable()) return v;
            if (v instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) v;
                for (int i = 0; i < group.getChildCount(); i++) {
                    View found = findViewByBounds(group.getChildAt(i), x, y);
                    if (found != null) return found;
                }
            }
            return v; // 返回最深层的可点击 View
        }
        return null;
    }

    /** 应用内模拟按键 */
    public boolean key(int keyCode) {
        android.app.Activity act = getCurrentActivity();
        if (act == null) return false;
        // 通过 InputManager 注入按键事件（需要 INJECT_EVENTS 权限，仅限系统应用）
        // fallback: 用 Activity 的 dispatchKeyEvent
        return act.dispatchKeyEvent(new android.view.KeyEvent(0, keyCode));
    }

    /** 应用内文本注入 */
    public boolean inputText(String text) {
        android.app.Activity act = getCurrentActivity();
        if (act == null) return false;
        View root = act.getWindow().getDecorView();
        if (root == null) return false;
        View focused = findFocusedInput(root);
        if (focused instanceof android.widget.EditText) {
            ((android.widget.EditText) focused).setText(text);
            return true;
        }
        return false;
    }

    private View findFocusedInput(View v) {
        if (v.isFocused() && v instanceof android.widget.EditText) return v;
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findFocusedInput(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }
}