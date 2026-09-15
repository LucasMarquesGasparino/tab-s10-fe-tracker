package com.tabpreco.s10fe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    @Override public void onReceive(Context ctx, Intent intent) {
        String act = intent != null ? intent.getAction() : "";
        Log.i(TAG, "BootReceiver: " + act);
        if (Intent.ACTION_BOOT_COMPLETED.equals(act) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(act) || "android.intent.action.QUICKBOOT_POWERON".equals(act)) {
            if (AlarmScheduler.isEnabled(ctx)) {
                AlarmScheduler.scheduleDailyAt10(ctx);
                Log.i(TAG, "Alarme reagendado após boot");
            }
        }
    }
}
