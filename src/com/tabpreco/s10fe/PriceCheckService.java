package com.tabpreco.s10fe;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

public final class PriceCheckService extends Service {
    private static final String TAG = "PriceCheckService";

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Log.i(TAG, "Service start");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    PriceChecker.checkAllSync(getApplicationContext());
                } catch (Exception e) {
                    Log.e(TAG, "erro", e);
                } finally {
                    stopSelf();
                }
            }
        }).start();
        return START_NOT_STICKY;
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
