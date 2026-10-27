package com.tvshell.moontv;

import android.content.Context;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Comparator;

/**
 * FTP 命令/响应桥接器（嵌入版）。
 *
 * 【核心机制】本机（调试器）通过 FTP 在 /sdcard/Download/moontv_cmd/ 目录下
 * 放 JSON 命令文件，本类每 2s 轮询一次，发现命令后执行、把结果写回同目录。
 *
 * 协议：
 *   命令文件: cmd_NNNNN.json  {"id":"...","action":"tap","params":{...}}
 *   响应文件: resp_NNNNN.json {"id":"...","status":"ok|error","result":{...}}
 *
 * Android 4.x 兼容：仅用 File API（API 1+），无网络依赖。
 */
public class FtpCmdBridge {

    private static final String TAG = "moontv-bridge";
    private static final long POLL_INTERVAL_MS = 2000;
    private static final int MAX_CONCURRENT = 4;

    private final CmdExecutor mExecutor;
    private final File mCmdDir;
    private volatile boolean mRunning = true;

    public FtpCmdBridge(Context ctx, CmdExecutor executor) {
        mExecutor = executor;
        File diagDir = Diag.getFtpSharedDir(ctx);
        if (diagDir == null) {
            mCmdDir = new File("/sdcard/Download/moontv_cmd");
        } else {
            mCmdDir = new File(diagDir, "moontv_cmd");
        }
        mCmdDir.mkdirs();
    }

    /** 开始轮询（后台线程） */
    public void start() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Diag.i("BRIDGE", "cmd dir=" + mCmdDir.getAbsolutePath());
                while (mRunning) {
                    try {
                        pollOnce();
                    } catch (Throwable t) {
                        Diag.e("BRIDGE", "poll fail", t);
                    }
                    try { Thread.sleep(POLL_INTERVAL_MS); } catch (InterruptedException e) { break; }
                }
            }
        }, "moontv-bridge").start();
    }

    /** 停止轮询 */
    public void stop() { mRunning = false; }

    /** 清空命令目录（用于重置） */
    public void clearCmdDir() {
        File[] files = mCmdDir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.getName().startsWith("cmd_")) f.delete();
            }
        }
    }

    private void pollOnce() {
        File[] cmdFiles = mCmdDir.listFiles(new FileFilter());
        if (cmdFiles == null || cmdFiles.length == 0) return;

        Arrays.sort(cmdFiles, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareTo(b.getName());
            }
        });

        int processed = 0;
        for (File cmdFile : cmdFiles) {
            if (processed >= MAX_CONCURRENT) break;
            try {
                String json = readFile(cmdFile);
                if (json == null || json.isEmpty()) continue;

                String id = extractString(json, "\"id\"");
                String action = extractString(json, "\"action\"");

                if (id == null || action == null) {
                    writeResponse(id, "error", "malformed command");
                    cmdFile.delete();
                    continue;
                }

                Diag.i("BRIDGE", "cmd id=" + id + " action=" + action);
                Object result = mExecutor.execute(action, json);
                writeResponse(id, "ok", result != null ? result.toString() : "");
                cmdFile.delete();
                processed++;
            } catch (Throwable t) {
                Diag.e("BRIDGE", "exec fail: " + cmdFile.getName(), t);
                cmdFile.delete();
            }
        }
    }

    private String extractString(String json, String key) {
        int idx = json.indexOf(key);
        if (idx < 0) return null;
        int start = json.indexOf("\"", idx + key.length() + 1);
        if (start < 0) return null;
        int end = json.indexOf("\"", start + 1);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private void writeResponse(String id, String status, String result) {
        if (id == null) id = "unknown";
        String resp = "{\"id\":\"" + escape(id) + "\",\"status\":\"" + status + "\"";
        if (result != null && !result.isEmpty()) {
            resp += ",\"result\":\"" + escape(result) + "\"";
        }
        resp += "}";
        File respFile = new File(mCmdDir, "resp_" + id + ".json");
        try (FileWriter w = new FileWriter(respFile)) {
            w.write(resp);
        } catch (Exception ignore) {}
    }

    private String readFile(File f) {
        try {
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream(f)));
            String l;
            while ((l = r.readLine()) != null) sb.append(l);
            r.close();
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 文件过滤器（Java 6 兼容，不用 lambda） */
    private static class FileFilter implements java.io.FileFilter {
        @Override
        public boolean accept(File f) {
            return f.getName().startsWith("cmd_") && f.getName().endsWith(".json");
        }
    }
}