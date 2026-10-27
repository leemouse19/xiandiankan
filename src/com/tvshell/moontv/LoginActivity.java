package com.tvshell.moontv;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 原生登录界面（FR：登录画面用接口方式，不依赖 H5 登录页）。
 *
 * 流程：原生表单收集账号密码 → 直接 POST {@code /api/login} → 取回 {@code Set-Cookie: auth=...}
 * 注入 WebView 的 CookieManager → 启动主页时 H5 自动带登录态。
 *
 * 内存：网络在后台线程；Handler 仅切回主线程更新 UI；不持有大对象。
 */
public class LoginActivity extends Activity {

    private static final String TAG = "moontv-login";
    // 浏览器 UA，降低被 Cloudflare 等风控按「非浏览器客户端」拦截的概率
    private static final String LOGIN_UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36 MOONTV-Android/1.0";

    private EditText mUser;
    private EditText mPass;
    private Button mBtn;
    private TextView mStatus;
    private TextView mFallback;
    private TextView mClear;
    private final Handler mHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        ThemeManager.applyDarkChrome(this);

        mUser = findViewById(R.id.login_user);
        mPass = findViewById(R.id.login_pass);
        mBtn = findViewById(R.id.login_btn);
        mStatus = findViewById(R.id.login_status);
        mFallback = findViewById(R.id.login_web_fallback);
        mClear = findViewById(R.id.login_clear);

        if (AuthHelper.isLoggedIn(this)) {
            mStatus.setText("已登录，可直接进入或切换账号");
            mClear.setVisibility(View.VISIBLE);
            mFallback.setVisibility(View.VISIBLE);
        }

        // 预填上次登录的用户名（redis 模式每次都要用户名）
        String last = AuthHelper.getLastUser(this);
        if (last != null && !last.isEmpty()) mUser.setText(last);

        // 探测站点存储模式：localstorage 只需密码，redis/upstash/kvrocks 必须用户名+密码
        detectStorageMode();

        mBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { doLogin(); }
        });

        mClear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                AuthHelper.clear(LoginActivity.this);
                mClear.setVisibility(View.GONE);
                mFallback.setVisibility(View.GONE);
                mStatus.setText("已清除登录信息，请重新登录");
                mPass.setText("");
                mUser.setText("");
            }
        });

        mFallback.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Diag.i("LOGIN", "user chose web fallback");
                proceedToMain();
            }
        });
    }

    private void doLogin() {
        final String user = mUser.getText().toString().trim();
        final String pass = mPass.getText().toString();
        if (pass.isEmpty()) {
            mStatus.setText("请输入密码");
            return;
        }
        mBtn.setEnabled(false);
        mStatus.setText("登录中…");
        Diag.i("LOGIN", "attempt user=" + (user.isEmpty() ? "(empty)" : user));

        new Thread(new Runnable() {
            @Override public void run() {
                final String err = performLogin(user, pass);
                mHandler.post(new Runnable() {
                    @Override public void run() {
                        mBtn.setEnabled(true);
                        if (err == null) {
                            Diag.i("LOGIN", "success");
                            mStatus.setText("登录成功");
                            proceedToMain();
                        } else {
                            Diag.e("LOGIN", "failed: " + err, null);
                            mStatus.setText("登录失败：" + err + "\n（可点下方「跳过登录」先进入首页）");
                        }
                    }
                });
            }
        }).start();
    }

    /** 探测站点存储模式，用于提示"是否需要填用户名"。 */
    private void detectStorageMode() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String type = "";
                String ver = "";
                try {
                    org.json.JSONObject cfg = MoontvApi.serverConfig();
                    type = cfg.optString("StorageType", "");
                    ver = cfg.optString("Version", "");
                } catch (Throwable t) {
                    Diag.i(TAG, "server-config failed: " + t.getMessage());
                }
                if (type.isEmpty()) return;
                final String ft = type, fv = ver;
                Diag.i(TAG, "server storage=" + ft + " version=" + fv);
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        if (AuthHelper.isLoggedIn(LoginActivity.this)) return;  // 已登录不打扰
                        if ("localstorage".equalsIgnoreCase(ft)) {
                            mStatus.setText("站点模式：localstorage（只需密码）· v" + fv);
                        } else {
                            mStatus.setText("站点模式：" + ft + "（需填用户名+密码）· v" + fv);
                        }
                    }
                });
            }
        }, "server-config").start();
    }

    /** 直接调后端接口登录。返回 null 表示成功，否则返回错误描述。 */
    private String performLogin(String user, String pass) {
        try {
            MoontvApi.AuthResult r = MoontvApi.login(user, pass);
            if (r.ok) {
                // 登录成功：把 cookie 注入 java.net 的 CookieManager，后续接口自动带登录态
                AuthHelper.seedJavaNet(this);
                AuthHelper.saveLastUser(this, user);
                return null;
            }
            String msg = r.message;
            if (msg == null || msg.trim().isEmpty()) msg = "登录失败";
            return msg;
        } catch (Exception e) {
            Log.e(TAG, "login error", e);
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    private void proceedToMain() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(i);
        finish();
    }
}
