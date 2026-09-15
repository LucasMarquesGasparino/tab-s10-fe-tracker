package com.tabpreco.s10fe;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import java.util.Calendar;

public final class AlarmScheduler {
    private static final int REQ_CODE = 9001;
    private static final String PREF = "tab_s10fe_prefs";
    private static final String KEY_ENABLED = "alarm_enabled";

    public static void scheduleDailyAt10(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = getPending(ctx);

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, 10);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        if (cal.getTimeInMillis() <= System.currentTimeMillis()) {
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }
        long trigger = cal.getTimeInMillis();

        // tentar setExactAndAllowWhileIdle, fallback setExact, fallback set
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                // verificar se pode agendar exato
                if (am.canScheduleExactAlarms()) {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
                } else {
                    // usar inexact mas ainda vai acordar
                    am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
                    // pedir permissão ao usuário via intent (tratado na Activity)
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi);
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, trigger, pi);
            }
        } catch (SecurityException se) {
            // fallback inexact
            try {
                am.set(AlarmManager.RTC_WAKEUP, trigger, pi);
            } catch (Exception ignored) {}
        }

        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, true).putLong("next_alarm", trigger).apply();
    }

    public static void cancel(Context ctx) {
        AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
        PendingIntent pi = getPending(ctx);
        try { am.cancel(pi); } catch (Exception ignored) {}
        pi.cancel();
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, false).apply();
    }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true); // padrão ligado
    }

    public static long getNextAlarmMillis(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("next_alarm", 0);
    }

    public static boolean isScheduled(Context ctx) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        PendingIntent pi = PendingIntent.getBroadcast(ctx, REQ_CODE, i, PendingIntent.FLAG_NO_CREATE | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
        return pi != null;
    }

    private static PendingIntent getPending(Context ctx) {
        Intent i = new Intent(ctx, AlarmReceiver.class);
        i.setAction("com.tabpreco.s10fe.DAILY_CHECK");
        return PendingIntent.getBroadcast(ctx, REQ_CODE, i, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));
    }

    public static void rescheduleIfEnabled(Context ctx) {
        if (isEnabled(ctx)) scheduleDailyAt10(ctx);
    }
}
