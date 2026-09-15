package com.tabpreco.s10fe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

public final class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(final Context ctx, Intent intent) {
        Log.i(TAG, "Alarm disparado: " + intent.getAction());

        // reagendar para o próximo dia imediatamente
        AlarmScheduler.scheduleDailyAt10(ctx);

        // executar verificação em thread separada com wakelock
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override public void run() {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wl = null;
                try {
                    if (pm != null) {
                        wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "tab-s10fe:pricecheck");
                        wl.acquire(60000);
                    }
                    // garantir notificação channel
                    NotificationHelper.ensureChannel(ctx);

                    // verificar preços
                    PriceChecker.checkAllSync(ctx.getApplicationContext());

                } catch (Exception e) {
                    Log.e(TAG, "erro check", e);
                } finally {
                    if (wl != null && wl.isHeld()) wl.release();
                    pr.finish();
                }
            }
        }).start();
    }
}
