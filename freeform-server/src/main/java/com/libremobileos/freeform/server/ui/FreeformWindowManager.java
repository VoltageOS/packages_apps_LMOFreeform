package com.libremobileos.freeform.server.ui;

import static com.libremobileos.freeform.server.Debug.dlog;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.util.ArrayMap;
import android.util.Slog;

import java.util.concurrent.ConcurrentHashMap;

public class FreeformWindowManager {
    private static final ConcurrentHashMap<String, FreeformWindow> freeformWindows = new ConcurrentHashMap<>(1);
    private static final String TAG = "FreeformWindowManager";

    public static void addWindow(
            Handler handler, Context context,
            String packageName, String activityName, int userId, int taskId,
            PendingIntent pendingIntent, int width, int height, int densityDpi) {
        String freeformId = packageName + "," + activityName + "," + userId;
        FreeformWindow oldWindow = freeformWindows.get(freeformId);
        if (oldWindow != null) {
            runCatchingOldWindow(oldWindow);
        }
        AppConfig appConfig = new AppConfig(packageName, activityName, pendingIntent, userId, taskId);
        FreeformConfig freeformConfig = new FreeformConfig(width, height, densityDpi);
        FreeformWindow window = new FreeformWindow(handler, context, appConfig, freeformConfig);
        dlog(TAG, "addWindow: " + packageName + "/" + activityName + ", freeformId=" + window.getFreeformId()
                + ", existing freeformWindows=" + freeformWindows);
        freeformWindows.put(window.getFreeformId(), window);
    }

    private static void runCatchingOldWindow(FreeformWindow oldWindow) {
        try {
            oldWindow.close();
        } catch (Exception e) {
            Slog.w(TAG, "close old window failed: " + e);
        }
        try {
            oldWindow.destroy("addWindow", false);
        } catch (Exception e) {
            Slog.w(TAG, "destroy old window failed: " + e);
        }
    }

    /**
     * @param freeformId packageName,activityName,userId
     */
    public static void removeWindow(String freeformId, Boolean close) {
        FreeformWindow removedWindow = freeformWindows.remove(freeformId);
        if (close && removedWindow != null)
            removedWindow.close();
    }

    public static void dumpLocked(java.io.PrintWriter pw) {
        pw.println("LMOFreeform windows (" + freeformWindows.size() + "):");
        java.util.Map<String, FreeformWindow> snapshot =
                new java.util.HashMap<>(freeformWindows);
        for (FreeformWindow window : snapshot.values()) {
            try {
                window.dumpState(pw);
            } catch (Exception e) {
                pw.println("  <window dump failed: " + e + ">");
            }
        }
        pw.flush();
    }

    public static void removeWindow(String freeformId) {
        removeWindow(freeformId, false /*close*/);
    }
}
