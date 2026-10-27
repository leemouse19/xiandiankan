package com.tvshell.moontv;

import android.app.Activity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;

/**
 * 遥控「确定键」适配（TV 盒子必踩坑）。
 *
 * 背景：焦点停在某个 View 上时，平台只保证「可见/可聚焦」，并不保证按下确定键会触发点击。
 *  - KEYCODE_ENTER：View.onKeyUp 只在「clickable」时才 performClick；
 *  - KEYCODE_DPAD_CENTER：平台默认几乎什么都不做（部分盒子遥控发的是这个码）。
 *
 * 这里的做法：在 Activity 的 dispatchKeyEvent 里拦截确定键，对当前焦点控件主动 performClick()，
 * 并把 DOWN/UP 一起消费掉，避免平台再补一次造成「按一次触发两次」。
 */
public class OkKey {

    private boolean mHandled;

    /** 返回 true 表示本次按键已被本处理器消费。 */
    public boolean handle(Activity act, KeyEvent event) {
        int k = event.getKeyCode();
        if (k != KeyEvent.KEYCODE_DPAD_CENTER && k != KeyEvent.KEYCODE_ENTER) return false;
        int action = event.getAction();
        if (action == KeyEvent.ACTION_DOWN) {
            View f = act.getCurrentFocus();
            if (f == null) return false;
            // 输入框保留平台默认行为（确定键用于唤起输入法/提交）
            if (f instanceof EditText) return false;
            mHandled = f.performClick();
            return mHandled;
        }
        if (action == KeyEvent.ACTION_UP) {
            if (mHandled) {
                mHandled = false;
                return true;
            }
        }
        return false;
    }
}
