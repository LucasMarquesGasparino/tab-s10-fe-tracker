package com.tabpreco.s10fe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;

public final class NotificationHelper {
    private static final String CHANNEL_ID = "tab_s10fe_prices";
    private static final String CHANNEL_NAME = "Preços Tab S10 FE";
    private static final int NOTIF_ID = 1001;

    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            NotificationChannel ch = nm.getNotificationChannel(CHANNEL_ID);
            if (ch == null) {
                ch = new NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH);
                ch.setDescription("Notificações de menor preço do Galaxy Tab S10 FE");
                ch.enableLights(true);
                ch.setLightColor(Color.rgb(20, 40, 160));
                ch.enableVibration(true);
                ch.setShowBadge(true);
                ch.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
                nm.createNotificationChannel(ch);
            }
        }
    }

    public static void showBestPriceNotification(Context ctx, PriceResult best, int totalStores) {
        ensureChannel(ctx);
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);

        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        // ação abrir loja
        Intent storeIntent = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(best.url));
        PendingIntent piStore = PendingIntent.getActivity(ctx, 1, storeIntent, PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0));

        String title = "Menor preço: " + best.priceText + " na " + best.storeName;
        String text = "Galaxy Tab S10 FE encontrado em " + totalStores + " lojas. Toque para ver todos os preços.";

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(ctx, CHANNEL_ID);
        else b = new Notification.Builder(ctx);

        b.setContentTitle(title)
         .setContentText(text)
         .setStyle(new Notification.BigTextStyle().bigText(text + "\n" + best.storeName + ": " + best.priceText + "\nVerifique agora antes que mude!"))
         .setSmallIcon(android.R.drawable.ic_dialog_info) // fallback ícone sistema (será substituído)
         .setContentIntent(pi)
         .setAutoCancel(true)
         .setPriority(Notification.PRIORITY_HIGH)
         .setCategory(Notification.CATEGORY_RECOMMENDATION)
         .setVisibility(Notification.VISIBILITY_PUBLIC)
         .setShowWhen(true)
         .setWhen(System.currentTimeMillis());

        // tentar usar ícone da app se existir
        try {
            int iconRes = ctx.getResources().getIdentifier("ic_launcher", "drawable", ctx.getPackageName());
            if (iconRes != 0) b.setSmallIcon(iconRes);
        } catch (Exception ignored) {}

        // ação
        b.addAction(new Notification.Action.Builder(null, "Abrir loja", piStore).build());
        b.addAction(new Notification.Action.Builder(null, "Ver todos", pi).build());

        // heads-up
        b.setDefaults(Notification.DEFAULT_ALL);

        try {
            nm.notify(NOTIF_ID, b.build());
        } catch (SecurityException se) {
            // permissão não concedida
        }
    }

    public static void showProgressNotification(Context ctx, String msg) {
        // opcional - notificação de progresso curta
        ensureChannel(ctx);
    }

    public static void cancel(Context ctx) {
        NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
        nm.cancel(NOTIF_ID);
    }
}
