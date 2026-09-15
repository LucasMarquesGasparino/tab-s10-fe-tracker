package com.tabpreco.s10fe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.text.Normalizer;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Iterator;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

public final class PriceChecker {

    private static final String TAG = "PriceChecker";
    private static final String PREF = "tab_s10fe_prefs";
    private static final String KEY_TRACKED = "tracked_products_json";
    private static final String KEY_HISTORY = "price_history_json";
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_HTML_CHARS = 900000;
    private static final int MAX_DISCOVERY_CANDIDATES = 8;
    private static final int MAX_HISTORY = 60;
    private static final double MIN_PRICE = 1000.0;
    private static final double MAX_PRICE = 10000.0;

    static {
        try {
            CookieManager cm = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
            CookieHandler.setDefault(cm);
        } catch (Exception ignored) {}
    }

    private static final Pattern JSON_LD = Pattern.compile(
            "<script\\b[^>]*type\\s*=\\s*[\\\"']application/ld\\+json[\\\"'][^>]*>(.*?)</script\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern NEXT_DATA = Pattern.compile(
            "<script\\b[^>]*\\bid\\s*=\\s*[\\\"']__NEXT_DATA__[\\\"'][^>]*>(.*?)</script\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern ANCHOR = Pattern.compile(
            "<a\\b[^>]*\\bhref\\s*=\\s*([\\\"'])(.*?)\\1[^>]*>(.*?)</a\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern URL_IN_TEXT = Pattern.compile(
            "https?://[^\\\"'\\s<>\\\\]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HTML_TAG = Pattern.compile("<[^>]+>", Pattern.DOTALL);
    private static final Pattern SCRIPT = Pattern.compile("<script\\b[^>]*>.*?</script\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern STYLE = Pattern.compile("<style\\b[^>]*>.*?</style\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title\\b[^>]*>(.*?)</title\\s*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern META_TAG = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern LINK_TAG = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern CURRENT_PRICE = Pattern.compile(
            "(?i)(?:por|[aà]\\s*vista|pix|oferta|pre[cç]o)[^R\\n]{0,180}R\\$\\s*([0-9]{1,3}(?:\\.[0-9]{3})*,[0-9]{2}|[0-9]+,[0-9]{2})");
    private static final Pattern PRICE_BEFORE_PAYMENT = Pattern.compile(
            "(?i)R\\$\\s*([0-9]{1,3}(?:\\.[0-9]{3})*,[0-9]{2}|[0-9]+,[0-9]{2})[^\\n]{0,60}(?:[aà]\\s*vista|pix)");
    private static final Pattern LABELED_PRICE = Pattern.compile(
            "(?i)(?:pre[cç]o|valor)\\s*:?[^R\\n]{0,35}R\\$\\s*([0-9]{1,3}(?:\\.[0-9]{3})*,[0-9]{2}|[0-9]+,[0-9]{2})");
    private static final Pattern GLOBAL_SHOP_PRICE = Pattern.compile(
            "(?is)globalShopInfo\\s*=\\s*\\{.*?\\\"price\\\"\\s*:\\s*\\\"?([0-9.,]+)\\\"?");
    private static final Pattern BRL_JSON_PRICE = Pattern.compile(
            "(?is)\\\"priceCurrency\\\"\\s*:\\s*\\\"BRL\\\".*?\\\"price\\\"\\s*:\\s*\\\"?([0-9.,]+)");
    private static final Pattern VTEX_PRICE = Pattern.compile(
            "(?is)\\\"price\\\"\\s*:\\s*([0-9]+\\.[0-9]{2})[^}]{0,300}\\\"priceCurrency\\\"\\s*:\\s*\\\"BRL\\\"|\\\"priceCurrency\\\"\\s*:\\s*\\\"BRL\\\"[^}]{0,300}\\\"price\\\"\\s*:\\s*([0-9]+\\.?[0-9]*)");
    // Shopee SPA embute os dados em <script type="text/mfe-initial-data"> (JSON com PDP_BFF_DATA).
    // Via Http o BFF vem vazio/bloqueado, mas via WebView ele vem preenchido — o parser abaixo
    // extrai product_price/item.price/models[].price (em centavos → /100000 ou /100).
    private static final Pattern SHOPEE_MFE = Pattern.compile(
            "<script\\b[^>]*type\\s*=\\s*[\\\"']text/mfe-initial-data[\\\"'][^>]*>(.*?)</script\\s*>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private PriceChecker() {}

    public static List<PriceResult> checkAllSync(Context ctx) {
        return checkAllSync(ctx, false);
    }

    public static List<PriceResult> discoverAllSync(Context ctx) {
        return checkAllSync(ctx, true);
    }

    public static List<PriceResult> checkAllSync(final Context ctx, final boolean forceDiscovery) {
        final Map<String, TrackedProduct> tracked = forceDiscovery
                ? new HashMap<String, TrackedProduct>() : loadTrackedProducts(ctx);
        List<Callable<PriceResult>> tasks = new ArrayList<Callable<PriceResult>>();
        ExecutorService exec = Executors.newFixedThreadPool(4);

        for (final Store store : Store.ALL) {
            tasks.add(new Callable<PriceResult>() {
                @Override public PriceResult call() {
                    return checkStore(store, tracked.get(store.id));
                }
            });
        }

        List<PriceResult> results = new ArrayList<PriceResult>();
        List<Future<PriceResult>> futures;
        try {
            // 150s: WebView (1 por vez) pode somar ~5 lojas × ~12s nos fallbacks anti-bot.
            futures = exec.invokeAll(tasks, 150, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            futures = new ArrayList<Future<PriceResult>>();
        }
        for (int i = 0; i < tasks.size(); i++) {
            try {
                if (i >= futures.size() || futures.get(i).isCancelled()) {
                    results.add(errorResult(Store.ALL[i], "Tempo esgotado nesta loja"));
                } else {
                    results.add(futures.get(i).get());
                }
            } catch (Exception e) {
                Log.w(TAG, "timeout/erro na loja " + Store.ALL[i].id + ": " + e.getMessage());
                results.add(errorResult(Store.ALL[i], "Tempo esgotado nesta loja"));
            }
        }
        exec.shutdownNow();

        Collections.sort(results, new Comparator<PriceResult>() {
            @Override public int compare(PriceResult a, PriceResult b) {
                if (a.available && !b.available) return -1;
                if (!a.available && b.available) return 1;
                if (a.available && b.available) return Double.compare(a.price, b.price);
                return a.storeName.compareToIgnoreCase(b.storeName);
            }
        });

        saveTrackedProducts(ctx, results);
        saveResults(ctx, results);
        return results;
    }

    private static PriceResult checkStore(Store store, TrackedProduct tracked) {
        if (tracked != null && isHttpUrl(tracked.url)) {
            // Migração de seeds: se o link salvo for um seed antigo já substituído
            // (ex: Shopee banido 1306226862.58208610906), força redescoberta para o seed novo.
            if (isHttpUrl(store.seedProductUrl) && !tracked.url.equals(store.seedProductUrl)
                    && isKnownDeadUrl(tracked.url)) {
                return discoverStore(store);
            }
            FetchResult page = fetchPage(tracked.url, store);
            if (page.statusCode == 404 || page.statusCode == 410) return discoverStore(store);
            if (!page.ok) {
                if (page.statusCode == 403) {
                    FetchResult seedRetry = isHttpUrl(store.seedProductUrl) ? fetchPage(store.seedProductUrl, store) : null;
                    if (seedRetry != null && seedRetry.ok) {
                        ProductData pd = parseProductPage(seedRetry.html, seedRetry.finalUrl, tracked.title);
                        if (pd.target && pd.price > 0) return resultFromProduct(store, pd, seedRetry.finalUrl, true);
                    }
                }
                PriceResult blocked = baseResult(store);
                blocked.url = tracked.url;
                blocked.productTitle = tracked.title;
                blocked.tracked = true;
                if (page.statusCode == 403) blocked.error = "Loja com proteção anti-bot (HTTP 403) - abra no navegador";
                else blocked.error = page.error;
                return blocked;
            }

            ProductData product = parseProductPage(page.html, page.finalUrl, tracked.title);
            if (!product.target) return discoverStore(store);
            // Seed antigo/banido (ex: Shopee) abre 200 mas sem preço (price<=0):
            // tenta redescobrir o link novo em vez de travar em "Preço não informado".
            if (product.price <= 0 && isHttpUrl(store.seedProductUrl)
                    && !page.finalUrl.equals(store.seedProductUrl)) {
                PriceResult rediscovered = discoverStore(store);
                if (rediscovered.available) return rediscovered;
            }
            return resultFromProduct(store, product, page.finalUrl, true);
        }
        return discoverStore(store);
    }

    /** URLs de seeds antigos sabidamente mortos (banidos/removidos) → força redescoberta. */
    private static boolean isKnownDeadUrl(String url) {
        if (url == null) return false;
        // Shopee antigo: item_status=banned, todos os preços null.
        if (url.contains("1306226862.58208610906")) return true;
        return false;
    }

    private static PriceResult discoverStore(Store store) {
        if (isHttpUrl(store.seedProductUrl)) {
            FetchResult seedPage = fetchPage(store.seedProductUrl, store);
            // Seed via Http sem preço (ex: Shopee BFF bloqueado, Via challenge 200): tenta WebView
            // com Chrome real antes de desistir para a busca.
            if (seedPage.ok) {
                ProductData probe = parseProductPage(seedPage.html, seedPage.finalUrl, "");
                if (probe.target && probe.price <= 0) {
                    FetchResult viaWeb = fetchViaWebView(store.seedProductUrl);
                    if (viaWeb != null && viaWeb.ok) seedPage = viaWeb;
                }
            } else if (seedPage.statusCode == 403) {
                FetchResult viaWeb = fetchViaWebView(store.seedProductUrl);
                if (viaWeb != null && viaWeb.ok) seedPage = viaWeb;
            }
            if (seedPage.ok) {
                ProductData seedProduct = parseProductPage(seedPage.html, seedPage.finalUrl, "");
                if (seedProduct.target && seedProduct.price > 0) return resultFromProduct(store, seedProduct, seedPage.finalUrl, true);
                if (seedProduct.target && seedProduct.inStock && seedProduct.price > 0) {
                    return resultFromProduct(store, seedProduct, seedPage.finalUrl, true);
                }
            } else if (seedPage.statusCode == 403) {
                // ainda tenta busca, mas guarda seed para fallback
            }
        }

        FetchResult search = fetchPage(store.searchUrl, store);
        // Busca bloqueada por anti-bot mas com WebView disponível: tenta renderizar a busca
        // (Via/Magalu retornam 403 ou challenge Akamai no HttpURLConnection, mas passam no Chrome real).
        if (!search.ok && search.statusCode == 403) {
            FetchResult viaWeb = fetchViaWebView(store.searchUrl);
            if (viaWeb != null && viaWeb.ok) search = viaWeb;
        }
        if (!search.ok) {
            PriceResult r = errorResult(store, "Busca bloqueada ou indisponível");
            if (search.statusCode == 403) r.error = "Busca bloqueada por anti-bot (HTTP 403)";
            else r.error = search.error.length() > 0 ? search.error : "HTTP " + search.statusCode;
            if (isHttpUrl(store.seedProductUrl)) {
                r.url = store.seedProductUrl;
                r.tracked = true;
                if (search.statusCode == 403) r.error = "Loja com proteção anti-bot (HTTP 403) - abra o anúncio direto";
            }
            return r;
        }

        List<Candidate> candidates = extractCandidates(search.html, store, search.finalUrl);
        for (int i = 0; i < candidates.size() && i < MAX_DISCOVERY_CANDIDATES; i++) {
            Candidate c = candidates.get(i);
            FetchResult page = fetchPage(c.url, store);
            if (!page.ok && page.statusCode == 403) {
                FetchResult viaWeb = fetchViaWebView(c.url);
                if (viaWeb != null && viaWeb.ok) page = viaWeb;
            }
            if (!page.ok) continue;
            ProductData product = parseProductPage(page.html, page.finalUrl, c.hint);
            if (product.target && product.price > 0) return resultFromProduct(store, product, page.finalUrl, true);
        }

        if (isHttpUrl(store.seedProductUrl)) {
            FetchResult seed = fetchPage(store.seedProductUrl, store);
            if (seed.ok) {
                ProductData p = parseProductPage(seed.html, seed.finalUrl, "");
                if (p.target) {
                    return resultFromProduct(store, p, seed.finalUrl, true);
                }
            }
            PriceResult r = baseResult(store);
            r.url = store.seedProductUrl;
            r.tracked = true;
            r.error = "Anúncio não confirmado - abra o link direto";
            return r;
        }

        PriceResult r = errorResult(store, "Anúncio do aparelho não encontrado");
        r.error = "Anúncio do Galaxy Tab S10 FE 128GB Wi-Fi não encontrado";
        return r;
    }

    private static PriceResult resultFromProduct(Store store, ProductData product, String fallbackUrl, boolean tracked) {
        PriceResult r = baseResult(store);
        String productUrl = isAllowedHost(product.canonicalUrl, store.searchUrl) ? product.canonicalUrl : fallbackUrl;
        r.url = isAllowedHost(productUrl, store.searchUrl) ? productUrl : store.searchUrl;
        if (!isAllowedHost(r.url, store.searchUrl) && isHttpUrl(fallbackUrl)) r.url = fallbackUrl;
        r.tracked = tracked && isHttpUrl(r.url) && !r.url.equals(store.searchUrl);
        r.productTitle = product.title == null ? "" : product.title;

        if (!product.inStock) {
            r.error = "Produto fora de estoque";
            return r;
        }
        if (product.price <= 0) {
            r.error = "Preço não informado na página do produto";
            return r;
        }

        r.price = product.price;
        r.priceText = formatBRL(product.price);
        r.available = true;
        r.error = "";
        return r;
    }

    private static PriceResult baseResult(Store store) {
        PriceResult r = new PriceResult();
        r.storeId = store.id;
        r.storeName = store.name;
        r.url = store.searchUrl;
        r.searchUrl = store.searchUrl;
        r.timestamp = System.currentTimeMillis();
        return r;
    }

    private static PriceResult errorResult(Store store, String message) {
        PriceResult r = baseResult(store);
        r.error = message;
        return r;
    }

    private static FetchResult fetchPage(String rawUrl) {
        return fetchPage(rawUrl, null);
    }

    /** Provedor opcional de HTML renderizado via WebView (Chrome real com JS). */
    public interface WebViewProvider {
        /** Deve retornar o outerHTML renderizado ou null se falhar/timeout. Chamado de thread de fundo. */
        String fetchRenderedHtml(String url);
    }

    private static volatile WebViewProvider sWebViewProvider = null;

    public static void setWebViewProvider(WebViewProvider p) {
        sWebViewProvider = p;
    }

    private static FetchResult fetchPage(String rawUrl, Store store) {
        FetchResult out = new FetchResult();
        out.finalUrl = rawUrl;
        if (!isHttpUrl(rawUrl)) {
            out.error = "URL inválida";
            return out;
        }
        FetchResult first = doFetch(rawUrl, store, false);
        if (first.ok) return first;
        if (first.statusCode == 403 && store != null) {
            FetchResult retry = doFetch(rawUrl, store, true);
            if (retry.ok) return retry;
            // Último recurso: WebView com Chrome real resolve challenge Akamai/JS.
            FetchResult viaWeb = fetchViaWebView(rawUrl);
            if (viaWeb != null && viaWeb.ok) return viaWeb;
            return first;
        }
        // Falha não-403 (timeout, 503...): WebView ainda pode salvar (ex: fastshop DNS instável).
        if (!first.ok) {
            FetchResult viaWeb = fetchViaWebView(rawUrl);
            if (viaWeb != null && viaWeb.ok) return viaWeb;
        }
        return first;
    }

    /** Tenta obter o HTML via WebView renderizado. Retorna null se sem provedor/falha. */
    private static FetchResult fetchViaWebView(String rawUrl) {
        WebViewProvider p = sWebViewProvider;
        if (p == null || !isHttpUrl(rawUrl)) return null;
        try {
            String html = p.fetchRenderedHtml(rawUrl);
            if (html == null || html.length() < 500) return null;
            String low = html.toLowerCase(Locale.US);
            // Se até o WebView caiu no challenge, não adianta.
            if (low.contains("sec-if-cpt-container")) return null;
            FetchResult out = new FetchResult();
            out.ok = true;
            out.statusCode = 200;
            out.html = html;
            out.finalUrl = rawUrl;
            out.error = "";
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static FetchResult doFetch(String rawUrl, Store store, boolean retry) {
        FetchResult out = new FetchResult();
        out.finalUrl = rawUrl;
        HttpURLConnection conn = null;
        try {
            URL url = new URL(rawUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setUseCaches(false);

            String ua = getUserAgent(store, retry);
            conn.setRequestProperty("User-Agent", ua);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7");
            conn.setRequestProperty("Accept-Language", "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7");
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
            conn.setRequestProperty("Cache-Control", "no-cache");
            conn.setRequestProperty("Pragma", "no-cache");
            conn.setRequestProperty("Upgrade-Insecure-Requests", "1");
            conn.setRequestProperty("Sec-Fetch-Dest", "document");
            conn.setRequestProperty("Sec-Fetch-Mode", "navigate");
            conn.setRequestProperty("Sec-Fetch-Site", "none");
            conn.setRequestProperty("Sec-Fetch-User", "?1");
            conn.setRequestProperty("Connection", "keep-alive");
            if (ua.contains("Chrome/124") || ua.contains("Chrome/127")) {
                conn.setRequestProperty("Sec-Ch-Ua", "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Google Chrome\";v=\"127\"");
                conn.setRequestProperty("Sec-Ch-Ua-Mobile", ua.contains("Mobile") ? "?1" : "?0");
                conn.setRequestProperty("Sec-Ch-Ua-Platform", ua.contains("Android") ? "\"Android\"" : "\"Windows\"");
            }
            String referer = "https://www.google.com/";
            if (store != null && isHttpUrl(store.searchUrl)) {
                try {
                    String host = new URL(store.searchUrl).getHost();
                    referer = "https://" + host + "/";
                } catch (Exception ignored) {}
            }
            conn.setRequestProperty("Referer", referer);
            conn.setRequestProperty("DNT", "1");

            out.statusCode = conn.getResponseCode();
            out.finalUrl = conn.getURL().toExternalForm();
            if (out.statusCode >= 400) {
                out.error = "HTTP " + out.statusCode;
                return out;
            }

            String encoding = conn.getContentEncoding();
            InputStream rawInput = conn.getInputStream();
            if (rawInput == null) {
                out.error = "Sem conteúdo";
                return out;
            }
            // Lê bytes brutos primeiro para detectar gzip mesmo sem header (ex: Colombo).
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(65536);
            byte[] tmp = new byte[8192];
            int n;
            int totalBytes = 0;
            try {
                while ((n = rawInput.read(tmp)) != -1 && totalBytes < MAX_HTML_CHARS * 2) {
                    bos.write(tmp, 0, n);
                    totalBytes += n;
                }
            } finally {
                try { rawInput.close(); } catch (Exception ignored) {}
            }
            byte[] rawBytes = bos.toByteArray();
            // Descomprime se header indicar OU se magic bytes gzip (0x1f 0x8b) estiverem presentes.
            boolean isGzip = "gzip".equalsIgnoreCase(encoding)
                    || "x-gzip".equalsIgnoreCase(encoding)
                    || (rawBytes.length > 2 && (rawBytes[0] & 0xFF) == 0x1F && (rawBytes[1] & 0xFF) == 0x8B);
            byte[] decodedBytes = rawBytes;
            if (isGzip && rawBytes.length > 0) {
                try {
                    java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(rawBytes);
                    GZIPInputStream gis = new GZIPInputStream(bis);
                    java.io.ByteArrayOutputStream out2 = new java.io.ByteArrayOutputStream(rawBytes.length * 2);
                    byte[] b2 = new byte[8192];
                    int r2;
                    while ((r2 = gis.read(b2)) != -1) out2.write(b2, 0, r2);
                    try { gis.close(); } catch (Exception ignored) {}
                    decodedBytes = out2.toByteArray();
                } catch (Exception ignoredGzip) {
                    // Se falhar, tenta deflate como fallback.
                    try {
                        java.io.ByteArrayInputStream bis2 = new java.io.ByteArrayInputStream(rawBytes);
                        InflaterInputStream iis2 = new InflaterInputStream(bis2);
                        java.io.ByteArrayOutputStream out3 = new java.io.ByteArrayOutputStream(rawBytes.length * 2);
                        byte[] b3 = new byte[8192];
                        int r3;
                        while ((r3 = iis2.read(b3)) != -1) out3.write(b3, 0, r3);
                        try { iis2.close(); } catch (Exception ignoredClose) {}
                        decodedBytes = out3.toByteArray();
                    } catch (Exception ignored2) {
                        decodedBytes = rawBytes;
                    }
                }
            } else if ("deflate".equalsIgnoreCase(encoding)) {
                try {
                    java.io.ByteArrayInputStream bis3 = new java.io.ByteArrayInputStream(rawBytes);
                    InflaterInputStream iis3 = new InflaterInputStream(bis3);
                    java.io.ByteArrayOutputStream out4 = new java.io.ByteArrayOutputStream(rawBytes.length * 2);
                    byte[] b4 = new byte[8192];
                    int r4;
                    while ((r4 = iis3.read(b4)) != -1) out4.write(b4, 0, r4);
                    try { iis3.close(); } catch (Exception ignoredClose2) {}
                    decodedBytes = out4.toByteArray();
                } catch (Exception ignoredDeflate) {
                    decodedBytes = rawBytes;
                }
            }

            String contentType = conn.getContentType();
            String charsetName = "UTF-8";
            if (contentType != null) {
                Matcher cm = Pattern.compile("charset\\s*=\\s*([^;\\s]+)", Pattern.CASE_INSENSITIVE).matcher(contentType);
                if (cm.find()) charsetName = cm.group(1).replace("\"", "").trim();
            }

            Charset charset;
            try { charset = Charset.forName(charsetName); }
            catch (Exception ignored) { charset = Charset.forName("UTF-8"); }

            String htmlStr;
            try {
                // Limita chars após decode.
                String full = new String(decodedBytes, charset.name());
                htmlStr = full.length() > MAX_HTML_CHARS ? full.substring(0, MAX_HTML_CHARS) : full;
            } catch (Exception ignored) {
                htmlStr = new String(decodedBytes, java.nio.charset.StandardCharsets.UTF_8);
                if (htmlStr.length() > MAX_HTML_CHARS) htmlStr = htmlStr.substring(0, MAX_HTML_CHARS);
            }
            // Detecta páginas de desafio Akamai / bot que retornam 200 mas sem conteúdo real
            String lowHtml = htmlStr.toLowerCase(Locale.US);
            if (lowHtml.contains("sec-if-cpt-container") || lowHtml.contains("akamai") && lowHtml.contains("protected by")
                    || lowHtml.contains("reference id:") && lowHtml.contains("client ip:")
                    || (htmlStr.length() < 8000 && lowHtml.contains("power") && lowHtml.contains("akamai"))) {
                // Trata como bloqueio 403 para que o app mostre mensagem amigável e ofereça link direto
                out.html = htmlStr;
                out.ok = false;
                out.statusCode = 403;
                out.error = "HTTP 403";
                return out;
            }
            out.html = htmlStr;
            out.ok = true;
            return out;
        } catch (Exception e) {
            out.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            if (out.error.length() > 160) out.error = out.error.substring(0, 160);
            return out;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String getUserAgent(Store store, boolean retry) {
        if (store != null && "mercadolivre".equals(store.id)) {
            return "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)";
        }
        if (retry) {
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";
        }
        return "Mozilla/5.0 (Linux; Android 13; SM-S928B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36";
    }

    private static ProductData parseProductPage(String html, String pageUrl, String hint) {
        if (html == null) html = "";
        ProductData selected = null;
        double nextDataPrice = -1;
        String nextDataTitle = "";

        Matcher jm = JSON_LD.matcher(html);
        while (jm.find()) {
            String jsonRaw = jm.group(1);
            String json = decodeJsonScript(jsonRaw);
            try {
                Object root;
                String trimmed = json.trim();
                if (trimmed.startsWith("[")) root = new JSONArray(trimmed);
                else root = new JSONObject(trimmed);
                ProductData p = findProduct(root);
                if (p != null && p.target) {
                    if (selected == null || (selected.price <= 0 && p.price > 0) || (p.inStock && !selected.inStock)) {
                        selected = p;
                    }
                }
                if (selected == null) {
                    ProductData generic = findProductByBRLPrice(root);
                    if (generic != null && generic.target) selected = generic;
                }
            } catch (Exception ignored) {
            }
        }

        try {
            Matcher nm = NEXT_DATA.matcher(html);
            if (nm.find()) {
                String json = nm.group(1);
                try {
                    JSONObject root = new JSONObject(json);
                    ProductData nd = extractFromNextData(root);
                    if (nd != null && nd.price > 0) {
                        nextDataPrice = nd.price;
                        nextDataTitle = nd.title;
                        if (selected == null || selected.price <= 0) {
                            selected = nd;
                        } else if (nd.inStock && !selected.inStock) {
                            selected.price = nd.price;
                            selected.inStock = nd.inStock;
                            if (nd.title != null && nd.title.length() > selected.title.length()) selected.title = nd.title;
                        }
                    } else {
                        double generic = findPriceInJson(root);
                        if (generic > 0) nextDataPrice = generic;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}

        String metaTitle = firstNonEmpty(readMeta(html, "og:title"), readMeta(html, "twitter:title"), readTitle(html));
        String title = selected == null ? metaTitle : firstNonEmpty(selected.title, metaTitle);
        if (title.length() == 0) title = firstNonEmpty(hint, pageUrl);
        if (nextDataTitle.length() > title.length()) title = nextDataTitle;
        // Shopee: og:title é genérico ("Shopee Brasil..."); o título real está no mfe-initial-data.
        if (pageUrl != null && pageUrl.contains("shopee.com.br")) {
            String shopeeTitle = extractShopeeTitle(html);
            if (shopeeTitle.length() > title.length()) title = shopeeTitle;
        }

        boolean target = selected != null && selected.target;
        if (!target) target = isTargetProduct(title + " " + hint + " " + pageUrl);
        if (!target) {
            String all = (title + " " + hint + " " + visibleText(html).substring(0, Math.min(2000, visibleText(html).length()))).toLowerCase();
            if (all.contains("s10") && all.contains("fe") && (all.contains("128") || all.contains("x520"))) {
                target = true;
            } else {
                return ProductData.notTarget();
            }
        }

        ProductData result = selected == null ? new ProductData() : selected;
        result.target = true;
        result.title = cleanText(title);
        String openGraphUrl = absoluteUrl(pageUrl, readMeta(html, "og:url"));
        if (!isAllowedHost(openGraphUrl, pageUrl)) openGraphUrl = "";
        result.canonicalUrl = firstNonEmpty(safeCanonical(html, pageUrl), openGraphUrl, pageUrl);

        if (result.price <= 0) {
            if (nextDataPrice > 0) result.price = nextDataPrice;
            else {
                result.price = firstValidPrice(
                        parsePriceValue(readMeta(html, "product:price:amount")),
                        parsePriceValue(readMeta(html, "og:price:amount")),
                        parsePriceValue(readItemProp(html, "price")),
                        extractEmbeddedPrice(html),
                        extractVtexPrice(html),
                        extractNextDataPrice(html),
                        extractShopeePrice(html),
                        extractContextPrice(html));
            }
        }

        // Verificação de estoque: compara texto visível (normalizado, sem acento) com
        // sinais de ruptura vs. sinais de compra. O LD manda, mas era sobrescrito por
        // "tem preço → em estoque", o que gerava falso disponível (Via mostra preço antigo
        // em produto indisponível). Agora ruptura força false e LD-OutOfStock só vira true
        // com botão de compra explícito.
        String allVisible = visibleText(html);
        String normVisible = normalize(allVisible);
        boolean outSig = hasOutOfStockSignals(normVisible);
        boolean buySig = hasBuySignals(normVisible);

        if (outSig) {
            result.inStock = false;
        } else if (!result.inStock && result.price > 0 && buySig) {
            // Corrige LD falso-negativo (ex: KaBuM marca OutOfStock com compra liberada).
            result.inStock = true;
        } else if (!result.inStock && result.price > 0 && nextDataPrice > 0 && buySig) {
            result.inStock = true;
        }

        // Grupo Via/Magalu: preço obsoleto aparece mesmo indisponível e o LD às vezes some;
        // sem botão de compra na página renderizada, não marca como disponível.
        if (result.price > 0 && result.inStock && isViaMagaluUrl(pageUrl) && !buySig) {
            result.inStock = false;
        }

        return result;
    }

    /**
     * Ruptura FORTE: frases que falam do produto principal, não de acessórios/rodapé.
     * Palavras isoladas ("esgotado", "indisponível", "avise-me") dão falso-positivo:
     * Samsung lista "ESGOTADO" em capinhas e tem modal "Avise-me", Amazon tem templates
     * "${cardName} indisponível" — e ambos estão EM estoque.
     */
    private static boolean hasOutOfStockSignals(String norm) {
        if (norm == null || norm.length() == 0) return false;
        return norm.contains("produto esgotado")
                || norm.contains("este produto esta sem estoque")
                || norm.contains("produto indisponivel")
                || norm.contains("fora de estoque")
                || norm.contains("out of stock")
                || norm.contains("sold out")
                || norm.contains("nao disponivel")
                || norm.contains("nao esta mais disponivel")
                || norm.contains("sem estoque")
                || norm.contains("estoque esgotado")
                || norm.contains("avise quando o produto chegar")
                || norm.contains("produto nao disponivel")
                || norm.contains("esse produto nao esta mais disponivel")
                || norm.contains("anuncio pausado")
                || norm.contains("anuncio finalizado");
    }

    /** Sinais de compra liberada no texto visível normalizado (ignora menus de navegação). */
    private static boolean hasBuySignals(String norm) {
        if (norm == null || norm.length() == 0) return false;
        if (norm.contains("adicionar ao carrinho")
                || norm.contains("adicionar a sacola")
                || norm.contains("adicionar sacola")
                || norm.contains("adicionar a cesta")
                || norm.contains("adicionar cesta")
                || norm.contains("comprar agora")
                || norm.contains("finalizar compra")
                || norm.contains("add to cart")) return true;
        // "comprar" genérico, mas sem contar navegação ("comprar por categoria", "como comprar").
        String n2 = norm.replace("comprar por categoria", " ").replace("como comprar", " ")
                .replace("compre por departamento", " ").replace("compre pelo", " ");
        return n2.contains("comprar");
    }

    /** Grupo Via (Casas/Ponto/Extra) + Magalu: anti-bot e preço obsoleto em ruptura. */
    private static boolean isViaMagaluUrl(String url) {
        if (url == null) return false;
        String u = url.toLowerCase(Locale.US);
        return u.contains("casasbahia.") || u.contains("pontofrio.") || u.contains("ponto.")
                || u.contains("extra.") || u.contains("magazineluiza.");
    }

    private static ProductData findProduct(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            if (isType(o.opt("@type"), "Product")) {
                ProductData p = new ProductData();
                p.title = cleanText(o.optString("name", ""));
                String sku = o.optString("sku", "") + " " + o.optString("mpn", "") + " " + o.optString("gtin", "");
                p.target = isTargetProduct(p.title + " " + sku);
                if (!p.target) {
                    String desc = o.optString("description", "");
                    p.target = isTargetProduct(desc + " " + p.title);
                }
                p.canonicalUrl = o.optString("url", "");
                OfferData offer = findOffer(o.opt("offers"));
                if (offer != null) {
                    p.price = offer.price;
                    p.inStock = offer.inStock;
                } else {
                    p.price = firstValidPrice(parsePriceValue(o.opt("price")), parsePriceValue(o.opt("lowPrice")));
                }
                if (p.target) return p;
            } else {
                if (o.has("offers") || o.has("price")) {
                    String name = cleanText(o.optString("name", ""));
                    if (name.length() > 10 && isTargetProduct(name)) {
                        ProductData p = new ProductData();
                        p.title = name;
                        p.target = true;
                        p.canonicalUrl = o.optString("url", "");
                        OfferData offer = findOffer(o.opt("offers"));
                        if (offer != null) {
                            p.price = offer.price;
                            p.inStock = offer.inStock;
                        } else {
                            p.price = firstValidPrice(parsePriceValue(o.opt("price")));
                        }
                        if (p.price > 0) return p;
                    }
                }
            }

            Iterator<String> keys = o.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if ("review".equals(key) || "aggregateRating".equals(key) || "breadcrumb".equalsIgnoreCase(key)) continue;
                try {
                    ProductData p = findProduct(o.opt(key));
                    if (p != null && p.target) return p;
                } catch (Exception ignored) {}
            }
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i = 0; i < a.length(); i++) {
                try {
                    ProductData p = findProduct(a.opt(i));
                    if (p != null && p.target) return p;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static ProductData findProductByBRLPrice(Object node) {
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            try {
                Iterator<String> keys = o.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    ProductData p = findProductByBRLPrice(o.opt(k));
                    if (p != null) return p;
                }
            } catch (Exception ignored) {}
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            for (int i=0;i<a.length();i++) {
                ProductData p = findProductByBRLPrice(a.opt(i));
                if (p!=null) return p;
            }
        }
        return null;
    }

    private static ProductData extractFromNextData(JSONObject root) {
        try {
            JSONObject props = root.optJSONObject("props");
            if (props != null) {
                JSONObject pageProps = props.optJSONObject("pageProps");
                if (pageProps != null) {
                    JSONObject product = pageProps.optJSONObject("product");
                    if (product != null) {
                        ProductData pd = new ProductData();
                        pd.title = cleanText(product.optString("title", product.optString("name", "")));
                        pd.target = isTargetProduct(pd.title);
                        JSONObject prices = product.optJSONObject("prices");
                        double price = -1;
                        if (prices != null) {
                            price = firstValidPrice(parsePriceValue(prices.opt("priceWithDiscount")), parsePriceValue(prices.opt("price")));
                        }
                        if (price <=0) price = firstValidPrice(parsePriceValue(product.opt("price")));
                        pd.price = price;
                        pd.inStock = price > 0;
                        String avail = product.optString("availability", "");
                        if (avail.toLowerCase().contains("outofstock")) pd.inStock = false;
                        if (pd.target && pd.price >0) return pd;
                    }
                    double generic = findPriceInJson(pageProps);
                    if (generic >0) {
                        String t = pageProps.optString("name", pageProps.optString("title", ""));
                        ProductData pd = new ProductData();
                        pd.title = cleanText(t);
                        pd.target = isTargetProduct(t);
                        pd.price = generic;
                        pd.inStock = true;
                        if (pd.target) return pd;
                    }
                }
            }
            double generic = findPriceInJson(root);
            if (generic >0) {
                ProductData pd = new ProductData();
                pd.price = generic;
                pd.inStock = true;
                pd.target = true;
                pd.title = "";
                return pd;
            }
        } catch (Exception ignored) {}
        return null;
    }

    private static double findPriceInJson(Object node) {
        return findPriceInJson(node, 0);
    }

    private static double findPriceInJson(Object node, int depth) {
        if (depth > 8) return -1;
        if (node instanceof JSONObject) {
            JSONObject o = (JSONObject) node;
            String[] priceKeys = {"priceWithDiscount","price","lowPrice","currentPrice","salePrice","bestPrice","amount","value"};
            for (String k : priceKeys) {
                if (o.has(k)) {
                    double v = parsePriceValue(o.opt(k));
                    if (v > 0) {
                        return v;
                    }
                }
            }
            Iterator<String> keys = o.keys();
            double best = -1;
            while (keys.hasNext()) {
                String key = keys.next();
                if ("review".equals(key) || "aggregateRating".equals(key)) continue;
                double v = findPriceInJson(o.opt(key), depth+1);
                if (v > 0 && (best < 0 || v < best)) best = v;
            }
            return best;
        } else if (node instanceof JSONArray) {
            JSONArray a = (JSONArray) node;
            double best = -1;
            for (int i=0;i<a.length();i++) {
                double v = findPriceInJson(a.opt(i), depth+1);
                if (v>0 && (best<0 || v < best)) best = v;
            }
            return best;
        }
        return -1;
    }

    private static OfferData findOffer(Object value) {
        if (value instanceof JSONObject) {
            JSONObject o = (JSONObject) value;
            String type = o.optString("@type", "");
            boolean isAggregate = "AggregateOffer".equalsIgnoreCase(type);
            OfferData out = new OfferData();
            String availability = o.optString("availability", "").toLowerCase(Locale.US);
            if (isAggregate) {
                out.price = firstValidPrice(parsePriceValue(o.opt("lowPrice")), parsePriceValue(o.opt("price")));
                Object inner = o.opt("offers");
                if (inner instanceof JSONArray) {
                    OfferData bestInner = findOffer(inner);
                    if (bestInner != null && bestInner.price >0) {
                        if (bestInner.inStock) {
                            out.price = bestInner.price;
                            out.inStock = bestInner.inStock;
                        }
                    }
                } else if (inner instanceof JSONObject) {
                    OfferData innerOffer = findOffer(inner);
                    if (innerOffer != null && innerOffer.price >0) out.price = innerOffer.price;
                }
                out.inStock = !(availability.contains("outofstock") || availability.contains("soldout")
                        || availability.contains("discontinued"));
                if (out.price >0 && availability.length()==0) out.inStock = true;
                return out;
            }
            out.inStock = !(availability.contains("outofstock") || availability.contains("soldout")
                    || availability.contains("discontinued"));
            out.price = firstValidPrice(parsePriceValue(o.opt("price")), parsePriceValue(o.opt("lowPrice")));
            if (out.price <=0) out.price = parsePriceValue(o.opt("highPrice"));
            return out;
        }
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            OfferData best = null;
            for (int i = 0; i < a.length(); i++) {
                OfferData item = findOffer(a.opt(i));
                if (item != null && item.price > 0
                        && (best == null || (item.inStock && !best.inStock)
                        || (item.inStock == best.inStock && item.price < best.price))) best = item;
            }
            return best;
        }
        return null;
    }

    public static List<Double> extractPrices(String html) {
        List<Double> result = new ArrayList<Double>();
        ProductData p = parseProductPage(html == null ? "" : html, "", "Galaxy Tab S10 FE 128GB Wi-Fi");
        if (p.target && p.price > 0) result.add(p.price);
        return result;
    }

    private static List<Candidate> extractCandidates(String html, Store store, String baseUrl) {
        Map<String, Candidate> unique = new LinkedHashMap<String, Candidate>();
        Matcher am = ANCHOR.matcher(html == null ? "" : html);
        while (am.find()) addCandidate(unique, store, baseUrl, decodeHtml(am.group(2)), cleanText(am.group(3)));

        Matcher um = URL_IN_TEXT.matcher(html == null ? "" : html);
        while (um.find()) addCandidate(unique, store, baseUrl, decodeHtml(um.group()), "");

        Matcher jsonUrl = Pattern.compile("(https?://[^\\\"'\\s<>]+/(?:dp|p|produto|item|product)/[^\\\"'\\s<>]+)", Pattern.CASE_INSENSITIVE).matcher(html == null ? "" : html);
        while (jsonUrl.find()) addCandidate(unique, store, baseUrl, decodeHtml(jsonUrl.group(1)), "");

        List<Candidate> result = new ArrayList<Candidate>(unique.values());
        Collections.sort(result, new Comparator<Candidate>() {
            @Override public int compare(Candidate a, Candidate b) { return b.score - a.score; }
        });
        return result;
    }

    private static void addCandidate(Map<String, Candidate> unique, Store store, String baseUrl, String rawUrl, String hint) {
        String url = absoluteUrl(baseUrl, rawUrl);
        if (!isAllowedHost(url, store.searchUrl) || !isProductCandidate(url)) return;

        String context = normalize(hint + " " + url);
        if (!context.contains("s10") || !context.contains("fe") || containsExcludedVariant(context)) return;

        int score = 0;
        if (context.contains("galaxy tab s10 fe")) score += 40;
        if (context.contains("128gb") || context.contains("128 gb")) score += 30;
        if (context.contains("wifi") || context.contains("wi fi") || context.contains("wi-fi")) score += 15;
        if (context.contains("sm x520")) score += 25;
        if (url.toLowerCase(Locale.US).contains("/dp/")) score += 12;
        if (url.toLowerCase(Locale.US).contains("/p/")) score += 12;
        if (context.contains("tablet")) score += 5;
        if (context.contains("s10 fe")) score += 10;

        Candidate old = unique.get(url);
        if (old == null || score > old.score) unique.put(url, new Candidate(url, hint, score));
    }

    private static boolean isProductCandidate(String url) {
        String low = url.toLowerCase(Locale.US);
        if (low.contains("/search") || low.contains("/busca") || low.contains("/lista")
                || low.contains("?q=") || low.contains("?k=") || low.contains("/login")
                || low.contains("/cart") || low.contains("/carrinho") || low.contains("/category")) return false;
        return low.contains("/dp/") || low.contains("/p/") || low.contains("/produto")
                || low.contains("/item/") || low.contains("/mlb-") || low.contains("/product/") || low.contains("/tablet");
    }

    private static boolean isTargetProduct(String text) {
        String n = normalize(text);
        if (!n.contains("s10") || !n.matches(".*s10\\s*fe.*")) return false;
        if (containsExcludedVariant(n)) return false;
        if (n.startsWith("capa ") || n.startsWith("case ") || n.startsWith("teclado ")
                || n.startsWith("pelicula ") || n.startsWith("suporte ")
                || n.contains("capa para") || n.contains("case para")
                || n.contains("teclado para") || n.contains("pelicula para")) return false;
        return n.contains("128gb") || n.contains("128 gb") || n.contains("sm x520");
    }

    private static boolean containsExcludedVariant(String text) {
        String n = normalize(text);
        return n.contains("s10 fe+") || n.contains("s10 fe plus") || n.contains("s10fe+")
                || n.contains("5g") || n.contains("5 g") || n.contains("s10 lite")
                || n.contains("s9 fe") || n.contains("s10 ultra") || n.contains("s10+");
    }

    private static String normalize(String text) {
        String value = text == null ? "" : text.toLowerCase(Locale.US);
        value = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return value.replaceAll("[^a-z0-9+]+", " ").trim();
    }

    private static String extractContextPrice(String html) {
        String text = visibleText(html);
        Matcher m = CURRENT_PRICE.matcher(text);
        while (m.find()) {
            double price = parsePriceValue(m.group(1));
            if (price > 0) return priceAsString(price);
        }
        m = PRICE_BEFORE_PAYMENT.matcher(text);
        double paymentPrice = -1;
        while (m.find()) {
            double price = parsePriceValue(m.group(1));
            if (price > 0) paymentPrice = price;
        }
        if (paymentPrice > 0) return priceAsString(paymentPrice);
        m = LABELED_PRICE.matcher(text);
        double lastLabeled = -1;
        while (m.find()) {
            double price = parsePriceValue(m.group(1));
            if (price > 0) lastLabeled = price;
        }
        if (lastLabeled > 0) return priceAsString(lastLabeled);
        Matcher allBRL = Pattern.compile("R\\$\\s*([0-9]{1,3}(?:\\.[0-9]{3})*,[0-9]{2})").matcher(text);
        double last = -1;
        while (allBRL.find()) {
            double p = parsePriceValue(allBRL.group(1));
            if (p >0) last = p;
        }
        if (last >0) return priceAsString(last);
        return "";
    }

    private static double extractEmbeddedPrice(String html) {
        if (html == null) html = "";
        Matcher m = GLOBAL_SHOP_PRICE.matcher(html);
        if (m.find()) {
            double price = parsePriceValue(m.group(1));
            if (price > 0) return price;
        }
        m = BRL_JSON_PRICE.matcher(html);
        if (m.find()) {
            double price = parsePriceValue(m.group(1));
            if (price > 0) return price;
        }
        return -1;
    }

    private static double extractVtexPrice(String html) {
        if (html == null) return -1;
        Matcher m = VTEX_PRICE.matcher(html);
        while (m.find()) {
            String g1 = m.group(1);
            String g2 = m.group(2);
            String val = g1 != null && g1.length()>0 ? g1 : g2;
            double price = parsePriceValue(val);
            if (price >0) return price;
        }
        Matcher m2 = Pattern.compile("\"price\"\\s*:\\s*([0-9]+\\.?[0-9]*)").matcher(html);
        while (m2.find()) {
            double price = parsePriceValue(m2.group(1));
            if (price >0) return price;
        }
        return -1;
    }

    private static double extractNextDataPrice(String html) {
        if (html == null) return -1;
        Matcher m = NEXT_DATA.matcher(html);
        if (m.find()) {
            String json = m.group(1);
            try {
                JSONObject root = new JSONObject(json);
                double p = findPriceInJson(root);
                if (p >0) return p;
            } catch (Exception ignored) {}
        }
        return -1;
    }

    private static double extractShopeePrice(String html) {
        if (html == null) return -1;
        // 1) Dados renderizados do WebView/SPA: text/mfe-initial-data → PDP_BFF_DATA.cachedMap
        //    → product_price / item.price(_min) / models[].price (em centavos: /100000).
        try {
            Matcher fm = SHOPEE_MFE.matcher(html);
            while (fm.find()) {
                String raw = fm.group(1);
                if (raw == null || raw.length() < 100) continue;
                try {
                    JSONObject root = new JSONObject(raw.trim());
                    double p = extractShopeeMfePrice(root);
                    if (p > 0) return p;
                } catch (Exception ignored) {
                    // JSON gigante pode vir com escape; tenta extração por regex como fallback abaixo.
                }
            }
        } catch (Exception ignored) {}
        Matcher m = Pattern.compile("\"priceMin\"\\s*:\\s*([0-9]+)").matcher(html);
        while (m.find()) {
            try {
                long cents = Long.parseLong(m.group(1));
                double price = cents / 100.0;
                if (price >= MIN_PRICE && price <= MAX_PRICE) return price;
                double price2 = cents / 100000.0;
                if (price2 >= MIN_PRICE && price2 <= MAX_PRICE) return price2;
            } catch (Exception ignored) {}
        }
        m = Pattern.compile("\"price\"\\s*:\\s*([0-9]+)").matcher(html);
        while (m.find()) {
            try {
                long cents = Long.parseLong(m.group(1));
                double price = cents / 100.0;
                if (price >= MIN_PRICE && price <= MAX_PRICE) return price;
                double priceMicro = cents / 100000.0;
                if (priceMicro >= MIN_PRICE && priceMicro <= MAX_PRICE) return priceMicro;
            } catch (Exception ignored) {}
        }
        return -1;
    }

    /** Extrai preço do JSON do mfe-initial-data da Shopee. Retorna -1 se banido/vazio. */
    private static double extractShopeeMfePrice(JSONObject root) {
        try {
            JSONObject initialState = root.optJSONObject("initialState");
            if (initialState == null) return -1;
            JSONObject domainPdp = initialState.optJSONObject("DOMAIN_PDP");
            if (domainPdp == null) return -1;
            JSONObject data = domainPdp.optJSONObject("data");
            if (data == null) return -1;
            JSONObject bff = data.optJSONObject("PDP_BFF_DATA");
            if (bff == null) return -1;
            JSONObject cachedMap = bff.optJSONObject("cachedMap");
            if (cachedMap == null) return -1;
            Iterator<String> keys = cachedMap.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject entry = cachedMap.optJSONObject(k);
                if (entry == null) continue;
                // product_price direto (quando disponível)
                JSONObject pp = entry.optJSONObject("product_price");
                if (pp != null) {
                    double p = firstValidPrice(
                            parseShopeeMicroPrice(pp.opt("price_min")),
                            parseShopeeMicroPrice(pp.opt("price_max")),
                            parseShopeeMicroPrice(pp.opt("price")));
                    if (p > 0) return p;
                }
                JSONObject item = entry.optJSONObject("item");
                if (item != null) {
                    // Item banido → todos null; ignora e tenta próxima entrada.
                    String status = String.valueOf(item.opt("item_status"));
                    if ("banned".equalsIgnoreCase(status)) continue;
                    double p = firstValidPrice(
                            parseShopeeMicroPrice(item.opt("price_min")),
                            parseShopeeMicroPrice(item.opt("price_max")),
                            parseShopeeMicroPrice(item.opt("price")),
                            parseShopeeMicroPrice(item.opt("price_before_discount")));
                    if (p > 0) return p;
                    JSONArray models = item.optJSONArray("models");
                    if (models != null) {
                        for (int i = 0; i < models.length(); i++) {
                            JSONObject mo = models.optJSONObject(i);
                            if (mo == null) continue;
                            double mp = firstValidPrice(
                                    parseShopeeMicroPrice(mo.opt("price")),
                                    parseShopeeMicroPrice(mo.opt("price_before_discount")));
                            if (mp > 0) return mp;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /** Título real do anúncio Shopee dentro do mfe-initial-data (og:title é genérico). */
    private static String extractShopeeTitle(String html) {
        if (html == null) return "";
        try {
            Matcher fm = SHOPEE_MFE.matcher(html);
            while (fm.find()) {
                String raw = fm.group(1);
                if (raw == null || raw.length() < 100) continue;
                try {
                    JSONObject root = new JSONObject(raw.trim());
                    JSONObject initialState = root.optJSONObject("initialState");
                    if (initialState == null) continue;
                    JSONObject domainPdp = initialState.optJSONObject("DOMAIN_PDP");
                    if (domainPdp == null) continue;
                    JSONObject data = domainPdp.optJSONObject("data");
                    if (data == null) continue;
                    JSONObject bff = data.optJSONObject("PDP_BFF_DATA");
                    if (bff == null) continue;
                    JSONObject cachedMap = bff.optJSONObject("cachedMap");
                    if (cachedMap == null) continue;
                    Iterator<String> keys = cachedMap.keys();
                    while (keys.hasNext()) {
                        JSONObject entry = cachedMap.optJSONObject(keys.next());
                        if (entry == null) continue;
                        JSONObject item = entry.optJSONObject("item");
                        if (item == null) continue;
                        String t = cleanText(item.optString("title", ""));
                        if (t.length() > 10) return t;
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** Preços Shopee vêm em micro-unidades (ex: 284188000 → R$ 2841.88). Tenta /100000 e /100. */
    private static double parseShopeeMicroPrice(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return -1;
        try {
            String s = String.valueOf(raw).trim();
            if (s.length() == 0 || "null".equalsIgnoreCase(s)) return -1;
            // Pode vir como double já em reais (ex: 2841.88) — aceita direto.
            if (s.contains(".")) {
                double direct = parsePriceValue(s);
                if (direct > 0) return direct;
            }
            long micro = Long.parseLong(s.replaceAll("[^0-9]", ""));
            double byMicro = micro / 100000.0;
            if (byMicro >= MIN_PRICE && byMicro <= MAX_PRICE) return byMicro;
            double byCent = micro / 100.0;
            if (byCent >= MIN_PRICE && byCent <= MAX_PRICE) return byCent;
        } catch (Exception ignored) {}
        return -1;
    }

    private static String priceAsString(double value) {
        return Double.toString(value);
    }

    private static double firstValidPrice(Object... values) {
        for (Object value : values) {
            double p = parsePriceValue(value);
            if (p > 0) return p;
        }
        return -1;
    }

    private static double parsePriceValue(Object raw) {
        if (raw == null || raw == JSONObject.NULL) return -1;
        String value = String.valueOf(raw).trim().replace("R$", "").replace(" ", "").replace(" ", "");
        value = value.replaceAll("[^0-9.,-]", "");
        if (value.length() == 0) return -1;
        try {
            int comma = value.lastIndexOf(',');
            int dot = value.lastIndexOf('.');
            if (comma >= 0 && dot >= 0) {
                if (comma > dot) value = value.replace(".", "").replace(',', '.');
                else value = value.replace(",", "");
            } else if (comma >= 0) {
                if (value.contains(".")) value = value.replace(".", "").replace(',', '.');
                else value = value.replace(',', '.');
            } else if (dot >= 0 && value.length() - dot - 1 == 3) {
                if (value.indexOf('.') != value.lastIndexOf('.')) value = value.replace(".", "");
            }
            double parsed = Double.parseDouble(value);
            return parsed >= MIN_PRICE && parsed <= MAX_PRICE ? parsed : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static String readMeta(String html, String key) {
        Matcher m = META_TAG.matcher(html == null ? "" : html);
        while (m.find()) {
            String tag = m.group();
            String property = firstNonEmpty(readAttribute(tag, "property"), readAttribute(tag, "name"));
            if (key.equalsIgnoreCase(property)) return decodeHtml(readAttribute(tag, "content"));
        }
        return "";
    }

    private static String readItemProp(String html, String key) {
        Matcher m = META_TAG.matcher(html == null ? "" : html);
        while (m.find()) {
            String tag = m.group();
            if (key.equalsIgnoreCase(readAttribute(tag, "itemprop"))) {
                String content = readAttribute(tag, "content");
                if (content.length() > 0) return content;
            }
        }
        return "";
    }

    private static String readTitle(String html) {
        Matcher m = TITLE.matcher(html == null ? "" : html);
        return m.find() ? cleanText(m.group(1)) : "";
    }

    private static String safeCanonical(String html, String baseUrl) {
        Matcher m = LINK_TAG.matcher(html == null ? "" : html);
        while (m.find()) {
            String tag = m.group();
            if (readAttribute(tag, "rel").toLowerCase(Locale.US).contains("canonical")) {
                String url = absoluteUrl(baseUrl, decodeHtml(readAttribute(tag, "href")));
                if (isHttpUrl(url) && (baseUrl.length() == 0 || isAllowedHost(url, baseUrl))) return url;
            }
        }
        return "";
    }

    private static String readAttribute(String tag, String name) {
        Matcher m = Pattern.compile("(?i)\\b" + Pattern.quote(name) + "\\s*=\\s*([\\\"'])(.*?)\\1").matcher(tag);
        return m.find() ? m.group(2) : "";
    }

    private static String visibleText(String html) {
        String text = SCRIPT.matcher(html == null ? "" : html).replaceAll(" ");
        text = STYLE.matcher(text).replaceAll(" ");
        text = HTML_TAG.matcher(text).replaceAll(" ");
        return cleanText(text);
    }

    private static String cleanText(String text) {
        return decodeHtml(text == null ? "" : text).replaceAll("\\s+", " ").trim();
    }

    private static String decodeJsonScript(String text) {
        if (text == null) return "";
        return text.trim()
                .replace("\\/", "/")
                .replace("&quot;", "'")
                .replace("&#34;", "'")
                .replace("&#x22;", "'")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ");
    }

    private static String decodeHtml(String text) {
        if (text == null) return "";
        return text.replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#39;", "'").replace("&#x27;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ");
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) if (value != null && value.trim().length() > 0) return value.trim();
        return "";
    }

    private static String absoluteUrl(String base, String raw) {
        if (raw == null || raw.trim().length() == 0) return "";
        String value = raw.trim().replace("\\/", "/");
        try {
            if (value.startsWith("//")) return new URL(base).getProtocol() + ":" + value;
            return new URL(new URL(base), value).toExternalForm();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static boolean isHttpUrl(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }

    private static boolean isAllowedHost(String candidate, String expected) {
        try {
            String a = rootHost(new URL(candidate).getHost());
            String b = rootHost(new URL(expected).getHost());
            if (a.equals(b) || a.endsWith("." + b) || b.endsWith("." + a)) return true;
            // Aliases do mesmo grupo: ponto.com.br ↔ pontofrio.com.br (rebrand Ponto Frio).
            if (isPontoHost(a) && isPontoHost(b)) return true;
            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isPontoHost(String root) {
        return "ponto.com.br".equals(root) || "pontofrio.com.br".equals(root);
    }

    private static String rootHost(String host) {
        String h = host == null ? "" : host.toLowerCase(Locale.US);
        if (h.startsWith("www.")) h = h.substring(4);
        if (h.startsWith("lista.")) h = h.substring(6);
        if (h.startsWith("produto.")) h = h.substring(8);
        if (h.startsWith("secure3.")) h = h.substring(8);
        if (h.startsWith("m.")) h = h.substring(2);
        return h;
    }

    private static Map<String, TrackedProduct> loadTrackedProducts(Context ctx) {
        Map<String, TrackedProduct> result = new HashMap<String, TrackedProduct>();
        try {
            String raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_TRACKED, "{}");
            JSONObject all = new JSONObject(raw);
            for (Store store : Store.ALL) {
                JSONObject o = all.optJSONObject(store.id);
                if (o == null) continue;
                String url = o.optString("url", "");
                if (isHttpUrl(url)) result.put(store.id, new TrackedProduct(url, o.optString("title", "")));
            }
        } catch (Exception e) {
            Log.w(TAG, "não foi possível ler links salvos", e);
        }
        return result;
    }

    private static void saveTrackedProducts(Context ctx, List<PriceResult> results) {
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            JSONObject all;
            try { all = new JSONObject(sp.getString(KEY_TRACKED, "{}")); }
            catch (Exception ignored) { all = new JSONObject(); }
            for (PriceResult r : results) {
                if (r.tracked && isHttpUrl(r.url)) {
                    JSONObject o = new JSONObject();
                    o.put("url", r.url);
                    o.put("title", r.productTitle);
                    o.put("updatedAt", r.timestamp);
                    all.put(r.storeId, o);
                }
            }
            sp.edit().putString(KEY_TRACKED, all.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "saveTrackedProducts erro", e);
        }
    }

    private static void updateHistory(Context ctx, List<PriceResult> results) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        JSONObject all;
        try { all = new JSONObject(sp.getString(KEY_HISTORY, "{}")); }
        catch (Exception e) { all = new JSONObject(); }

        try {
            for (PriceResult r : results) {
                JSONArray old = all.optJSONArray(r.storeId);
                if (old == null) old = new JSONArray();
                r.historyCount = old.length();
                if (old.length() > 0) {
                    JSONObject last = old.optJSONObject(old.length() - 1);
                    if (last != null) r.previousPrice = last.optDouble("price", -1);
                }
                if (r.available && r.tracked) {
                    if (r.previousPrice > 0) r.priceChange = r.price - r.previousPrice;
                    JSONArray next = new JSONArray();
                    int start = Math.max(0, old.length() - MAX_HISTORY + 1);
                    for (int i = start; i < old.length(); i++) next.put(old.opt(i));
                    JSONObject point = new JSONObject();
                    point.put("timestamp", r.timestamp);
                    point.put("price", r.price);
                    point.put("url", r.url);
                    next.put(point);
                    r.historyCount = next.length();
                    all.put(r.storeId, next);
                }
            }
            sp.edit().putString(KEY_HISTORY, all.toString()).apply();
        } catch (Exception e) {
            Log.e(TAG, "updateHistory erro", e);
        }
    }

    public static void saveResults(Context ctx, List<PriceResult> results) {
        try {
            updateHistory(ctx, results);
            JSONArray arr = new JSONArray();
            for (PriceResult r : results) arr.put(r.toJson());
            JSONObject wrapper = new JSONObject();
            wrapper.put("results", arr);
            wrapper.put("timestamp", System.currentTimeMillis());
            wrapper.put("dateISO", java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT,
                    new Locale("pt", "BR")).format(new java.util.Date()));

            PriceResult best = null;
            for (PriceResult r : results) if (r.available && (best == null || r.price < best.price)) best = r;
            if (best != null) {
                wrapper.put("bestStoreId", best.storeId);
                wrapper.put("bestStoreName", best.storeName);
                wrapper.put("bestPrice", best.price);
                wrapper.put("bestPriceText", best.priceText);
                wrapper.put("bestUrl", best.url);
            }

            SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
            sp.edit().putString("last_results_json", wrapper.toString())
                    .putLong("last_check", System.currentTimeMillis()).apply();

            if (best != null) {
                PriceResult prevBest = getBestFromPrefs(ctx);
                long lastNotify = sp.getLong("last_notify_time", 0);
                long now = System.currentTimeMillis();
                boolean shouldNotify = prevBest == null;
                if (prevBest != null) {
                    boolean same = best.storeId.equals(prevBest.storeId)
                            && Math.abs(best.price - prevBest.price) < 0.01;
                    shouldNotify = !same || (now - lastNotify) >= 20 * 60 * 60 * 1000L;
                }
                if (shouldNotify) {
                    NotificationHelper.showBestPriceNotification(ctx, best, results.size());
                    sp.edit().putLong("last_notify_time", now).apply();
                }
                sp.edit().putString("prev_best_json", best.toJson().toString()).apply();
            }
        } catch (Exception e) {
            Log.e(TAG, "saveResults erro", e);
        }
    }

    public static PriceResult getBestFromPrefs(Context ctx) {
        try {
            String j = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString("prev_best_json", null);
            return j == null ? null : PriceResult.fromJson(new JSONObject(j));
        } catch (Exception e) { return null; }
    }

    public static String getLastResultsJson(Context ctx) {
        SharedPreferences sp = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        String value = sp.getString("last_results_json", null);
        if (value != null) return value;
        try {
            JSONObject wrapper = new JSONObject();
            JSONArray arr = new JSONArray();
            for (Store s : Store.ALL) {
                JSONObject o = new JSONObject();
                o.put("storeId", s.id);
                o.put("storeName", s.name);
                o.put("url", s.searchUrl);
                o.put("searchUrl", s.searchUrl);
                o.put("productTitle", "");
                o.put("price", -1);
                o.put("priceText", "");
                o.put("available", false);
                o.put("tracked", false);
                o.put("error", "Nunca verificado");
                o.put("timestamp", 0);
                arr.put(o);
            }
            wrapper.put("results", arr);
            wrapper.put("timestamp", 0);
            wrapper.put("dateISO", "nunca");
            return wrapper.toString();
        } catch (Exception e) { return "{\"results\":[]}"; }
    }

    public static long getLastCheckTime(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getLong("last_check", 0);
    }

    public static String formatBRL(double value) {
        NumberFormat nf = NumberFormat.getCurrencyInstance(new Locale("pt", "BR"));
        return nf.format(value);
    }

    private static final class FetchResult {
        boolean ok;
        int statusCode;
        String html = "";
        String finalUrl = "";
        String error = "";
    }

    private static final class ProductData {
        boolean target;
        boolean inStock = true;
        double price = -1;
        String title = "";
        String canonicalUrl = "";
        static ProductData notTarget() { ProductData p = new ProductData(); p.target = false; return p; }
    }

    private static final class OfferData {
        double price = -1;
        boolean inStock = true;
    }

    private static final class Candidate {
        final String url;
        final String hint;
        final int score;
        Candidate(String url, String hint, int score) { this.url = url; this.hint = hint; this.score = score; }
    }

    private static final class TrackedProduct {
        final String url;
        final String title;
        TrackedProduct(String url, String title) { this.url = url; this.title = title; }
    }

    private static boolean isType(Object value, String wanted) {
        if (value == null || value == JSONObject.NULL) return false;
        if (value instanceof JSONArray) {
            JSONArray a = (JSONArray) value;
            for (int i = 0; i < a.length(); i++) if (wanted.equalsIgnoreCase(a.optString(i))) return true;
            return false;
        }
        return wanted.equalsIgnoreCase(String.valueOf(value));
    }
}
