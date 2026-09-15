package com.tabpreco.s10fe;

import android.Manifest;
import android.app.Activity;
import android.app.AlarmManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

public final class MainActivity extends Activity {

    private WebView web;
    private WebView scraper;
    private final Object scraperLock = new Object();
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final int REQ_NOTIF = 101;
    private static final String PREF = "tab_s10fe_prefs";

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(12, 18, 48));
        getWindow().setNavigationBarColor(Color.rgb(12, 18, 48));
        if (Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(0);

        NotificationHelper.ensureChannel(this);

        // agendar alarme se ainda não estiver e se estiver habilitado
        if (AlarmScheduler.isEnabled(this) && !AlarmScheduler.isScheduled(this)) {
            AlarmScheduler.scheduleDailyAt10(this);
        }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        if (Build.VERSION.SDK_INT >= 21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        web.setBackgroundColor(Color.rgb(12, 18, 48));
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, String url) {
                if (url != null && (url.startsWith("http://") || url.startsWith("https://"))) {
                    // abrir externo se for loja
                    try {
                        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                        startActivity(i);
                        return true;
                    } catch (Exception e) { return false; }
                }
                return false;
            }
        });
        web.setWebChromeClient(new WebChromeClient());
        web.addJavascriptInterface(new NativeBridge(), "NativeBridge");

        web.loadUrl("file:///android_asset/tracker/index.html");
        setContentView(web);

        setupScraper();

        // solicitar permissão de notificação se necessário
        checkAndRequestNotificationPermission(false);
        // verificar exact alarm permission
        checkExactAlarmPermission();
    }

    /**
     * WebView oculta com Chrome real (TLS + JS + cookies) para furar anti-bot Akamai
     * (Magalu/Via) e SPAs (Shopee). O PriceChecker usa via Http primeiro (rápido) e só
     * chama aqui quando dá 403/challenge ou preço ausente.
     */
    private void setupScraper() {
        try {
            scraper = new WebView(this);
            WebSettings s = scraper.getSettings();
            s.setJavaScriptEnabled(true);
            s.setDomStorageEnabled(true);
            s.setDatabaseEnabled(true);
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
            s.setMediaPlaybackRequiresUserGesture(false);
            s.setJavaScriptCanOpenWindowsAutomatically(false);
            s.setUserAgentString("Mozilla/5.0 (Linux; Android 13; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36");
            android.webkit.CookieManager cm = android.webkit.CookieManager.getInstance();
            cm.setAcceptCookie(true);
            if (Build.VERSION.SDK_INT >= 21) cm.setAcceptThirdPartyCookies(scraper, true);

            PriceChecker.setWebViewProvider(new PriceChecker.WebViewProvider() {
                @Override public String fetchRenderedHtml(final String url) {
                    synchronized (scraperLock) {
                        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                        final String[] out = new String[1];
                        final boolean[] done = new boolean[1];
                        mainHandler.post(new Runnable() {
                            @Override public void run() {
                                try {
                                    scraper.setWebViewClient(new WebViewClient() {
                                        @Override public void onPageFinished(WebView view, String u) {
                                            // Aguarda JS tardio (Shopee/VTEX/Akamai) antes de extrair.
                                            mainHandler.postDelayed(new Runnable() {
                                                @Override public void run() {
                                                    if (done[0]) return;
                                                    try {
                                                        scraper.evaluateJavascript(
                                                            "(function(){try{return document.documentElement.outerHTML;}catch(e){return '';}})()",
                                                            new android.webkit.ValueCallback<String>() {
                                                                @Override public void onReceiveValue(String value) {
                                                                    if (done[0]) return;
                                                                    done[0] = true;
                                                                    out[0] = unescapeJsString(value);
                                                                    latch.countDown();
                                                                }
                                                            });
                                                    } catch (Exception e) {
                                                        if (!done[0]) { done[0] = true; latch.countDown(); }
                                                    }
                                                }
                                            }, 3500);
                                        }
                                        @Override public void onReceivedError(WebView view, int code, String desc, String failingUrl) {
                                            if (!done[0]) { done[0] = true; latch.countDown(); }
                                        }
                                    });
                                    scraper.loadUrl(url);
                                    // Segurança: nunca trava a thread de fundo para sempre.
                                    mainHandler.postDelayed(new Runnable() {
                                        @Override public void run() {
                                            if (!done[0]) { done[0] = true; latch.countDown(); }
                                        }
                                    }, 18000);
                                } catch (Exception e) {
                                    if (!done[0]) { done[0] = true; latch.countDown(); }
                                }
                            }
                        });
                        try {
                            latch.await(20, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return null;
                        }
                        if (out[0] == null || out[0].length() < 500) return null;
                        return out[0];
                    }
                }
            });
        } catch (Exception e) {
            android.util.Log.w("MainActivity", "scraper indisponível: " + e.getMessage());
        }
    }

    /** evaluateJavascript retorna string JSON-escaped ("..."); desfaz o escape. */
    private static String unescapeJsString(String v) {
        if (v == null || v.length() < 2) return null;
        v = v.trim();
        if ("null".equals(v)) return null;
        if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
            v = v.substring(1, v.length() - 1);
        }
        // Desescapes mais comuns do JSON string.
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(i + 1);
                if (n == 'n') { sb.append('\n'); i++; }
                else if (n == 't') { sb.append('\t'); i++; }
                else if (n == 'r') { sb.append('\r'); i++; }
                else if (n == '"') { sb.append('"'); i++; }
                else if (n == '\'') { sb.append('\''); i++; }
                else if (n == '\\') { sb.append('\\'); i++; }
                else if (n == 'u' && i + 5 < v.length()) {
                    try {
                        int code = Integer.parseInt(v.substring(i + 2, i + 6), 16);
                        sb.append((char) code);
                        i += 5;
                    } catch (Exception ignored) { sb.append(c); }
                } else { sb.append(n); i++; }
            } else {
                sb.append(c);
            }
        }
        return sb.toString().replace("\\/", "/");
    }

    private void checkExactAlarmPermission() {
        if (Build.VERSION.SDK_INT >= 31) {
            AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
            if (am != null && !am.canScheduleExactAlarms()) {
                // não bloqueia, mas avisa JS
                // usuário pode conceder em configurações
            }
        }
    }

    private void checkAndRequestNotificationPermission(boolean force) {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                if (force || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                    requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                } else {
                    // primeira vez - pedir direto se ainda não negou 2x
                    SharedPreferences sp = getSharedPreferences(PREF, MODE_PRIVATE);
                    boolean alreadyAsked = sp.getBoolean("asked_notif", false);
                    if (!alreadyAsked) {
                        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
                        sp.edit().putBoolean("asked_notif", true).apply();
                    }
                }
            }
        }
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQ_NOTIF) {
            boolean granted = grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED;
            String js = "javascript:try{ window.onNativePermissionResult && window.onNativePermissionResult(" + granted + ")}catch(e){}";
            if (web != null) web.evaluateJavascript(js, null);
            if (granted) Toast.makeText(this, "Notificações ativadas!", Toast.LENGTH_SHORT).show();
            else Toast.makeText(this, "Notificações negadas - ative nas configurações", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        // atualizar UI com próximo alarme
        mainHandler.postDelayed(new Runnable(){ @Override public void run(){ pushAlarmInfoToJs(); }}, 800);
    }

    @Override protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override protected void onDestroy() {
        try {
            PriceChecker.setWebViewProvider(null);
            if (scraper != null) { scraper.stopLoading(); scraper.destroy(); scraper = null; }
            if (web != null) { web.destroy(); web = null; }
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public void onBackPressed() {
        if (web != null && web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    private void pushAlarmInfoToJs() {
        if (web == null) return;
        long next = AlarmScheduler.getNextAlarmMillis(this);
        boolean enabled = AlarmScheduler.isEnabled(this);
        boolean scheduled = AlarmScheduler.isScheduled(this);
        String js = "javascript:try{ window.onAlarmInfo && window.onAlarmInfo({enabled:" + enabled + ",scheduled:" + scheduled + ",next:" + next + "})}catch(e){}";
        web.evaluateJavascript(js, null);
    }

    public final class NativeBridge {

        @JavascriptInterface public String getLastResults() {
            return PriceChecker.getLastResultsJson(MainActivity.this);
        }

        @JavascriptInterface public long getLastCheckTime() {
            return PriceChecker.getLastCheckTime(MainActivity.this);
        }

        @JavascriptInterface public String getAlarmInfo() {
            long next = AlarmScheduler.getNextAlarmMillis(MainActivity.this);
            boolean enabled = AlarmScheduler.isEnabled(MainActivity.this);
            boolean scheduled = AlarmScheduler.isScheduled(MainActivity.this);
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("enabled", enabled);
                o.put("scheduled", scheduled);
                o.put("next", next);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface public void setAlarmEnabled(final boolean enabled) {
            mainHandler.post(new Runnable(){ @Override public void run(){
                if (enabled) AlarmScheduler.scheduleDailyAt10(MainActivity.this);
                else AlarmScheduler.cancel(MainActivity.this);
                pushAlarmInfoToJs();
                Toast.makeText(MainActivity.this, enabled ? "Verificação diária às 10h ativada" : "Verificação diária desativada", Toast.LENGTH_SHORT).show();
            }});
        }

        @JavascriptInterface public void openStore(final String url) {
            mainHandler.post(new Runnable(){ @Override public void run(){
                try {
                    Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(i);
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "Não foi possível abrir: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }});
        }

        @JavascriptInterface public void openExactAlarmSettings() {
            mainHandler.post(new Runnable(){ @Override public void run(){
                try {
                    if (Build.VERSION.SDK_INT >= 31) {
                        Intent i = new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM);
                        i.setData(Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    }
                } catch (Exception e) {
                    try {
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                    } catch (Exception ignored) {}
                }
            }});
        }

        @JavascriptInterface public void requestNotificationPermission() {
            mainHandler.post(new Runnable(){ @Override public void run(){
                checkAndRequestNotificationPermission(true);
            }});
        }

        @JavascriptInterface public boolean hasNotificationPermission() {
            if (Build.VERSION.SDK_INT >= 33) {
                return checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            }
            return true;
        }

        @JavascriptInterface public boolean canScheduleExactAlarms() {
            if (Build.VERSION.SDK_INT >= 31) {
                AlarmManager am = (AlarmManager) getSystemService(ALARM_SERVICE);
                return am != null && am.canScheduleExactAlarms();
            }
            return true;
        }

        @JavascriptInterface public void checkPricesNow() {
            startPriceCheck(false);
        }

        @JavascriptInterface public void rediscoverProducts() {
            startPriceCheck(true);
        }

        private void startPriceCheck(final boolean forceDiscovery) {
            mainHandler.post(new Runnable(){ @Override public void run(){
                Toast.makeText(MainActivity.this,
                        forceDiscovery ? "Redescobrindo links dos produtos..." : "Verificando preços nos links salvos...",
                        Toast.LENGTH_SHORT).show();
                // notificar JS que começou
                web.evaluateJavascript("javascript:try{ window.onCheckStarted && window.onCheckStarted(" + forceDiscovery + ")}catch(e){}", null);
            }});
            new Thread(new Runnable(){ @Override public void run(){
                try {
                    final java.util.List<PriceResult> results = PriceChecker.checkAllSync(getApplicationContext(), forceDiscovery);
                    // calcular melhor
                    PriceResult best = null;
                    for (PriceResult r: results) if (r.available && (best==null || r.price < best.price)) best=r;

                    final PriceResult fBest = best;
                    mainHandler.post(new Runnable(){ @Override public void run(){
                        // enviar resultado para JS
                        String json = PriceChecker.getLastResultsJson(MainActivity.this);
                        // JSONObject.quote evita quebrar a chamada quando título/URL contiver aspas.
                        String js = "javascript:try{ window.onCheckFinished && window.onCheckFinished(JSON.parse(" + org.json.JSONObject.quote(json) + "))}catch(e){ console.error(e); window.onCheckFinished && window.onCheckFinished(null)}";
                        web.evaluateJavascript(js, null);

                        if (fBest != null) {
                            Toast.makeText(MainActivity.this, "Menor preço: "+fBest.priceText+" na "+fBest.storeName, Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(MainActivity.this, "Concluído - nenhum anúncio com preço válido", Toast.LENGTH_LONG).show();
                        }
                        pushAlarmInfoToJs();
                    }});
                } catch (final Exception e) {
                    mainHandler.post(new Runnable(){ @Override public void run(){
                        Toast.makeText(MainActivity.this, "Erro: "+e.getMessage(), Toast.LENGTH_LONG).show();
                        web.evaluateJavascript("javascript:try{ window.onCheckFinished && window.onCheckFinished(null)}catch(e){}", null);
                    }});
                }
            }}).start();
        }

        @JavascriptInterface public String getDeviceInfo() {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("sdk", Build.VERSION.SDK_INT);
                o.put("model", Build.MODEL);
                o.put("brand", Build.BRAND);
                return o.toString();
            } catch (Exception e) { return "{}"; }
        }
    }
}
