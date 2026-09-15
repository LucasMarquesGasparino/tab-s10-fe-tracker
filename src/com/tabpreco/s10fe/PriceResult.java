package com.tabpreco.s10fe;

import org.json.JSONObject;

public final class PriceResult {
    public String storeId;
    public String storeName;
    /** URL exata do produto quando tracked=true; URL de busca enquanto não houver descoberta. */
    public String url;
    public String searchUrl;
    public String productTitle = "";
    public double price = -1;
    public double previousPrice = -1;
    public double priceChange = 0;
    public String priceText = "";
    public boolean available = false;
    public boolean tracked = false;
    public int historyCount = 0;
    public String error = "";
    public long timestamp = 0;

    public PriceResult() {}

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("storeId", storeId);
            o.put("storeName", storeName);
            o.put("url", url);
            o.put("searchUrl", searchUrl);
            o.put("productTitle", productTitle);
            o.put("price", price);
            o.put("previousPrice", previousPrice);
            o.put("priceChange", priceChange);
            o.put("priceText", priceText);
            o.put("available", available);
            o.put("tracked", tracked);
            o.put("historyCount", historyCount);
            o.put("error", error);
            o.put("timestamp", timestamp);
        } catch (Exception ignored) {}
        return o;
    }

    public static PriceResult fromJson(JSONObject o) {
        PriceResult r = new PriceResult();
        try {
            r.storeId = o.optString("storeId");
            r.storeName = o.optString("storeName");
            r.url = o.optString("url");
            r.searchUrl = o.optString("searchUrl");
            r.productTitle = o.optString("productTitle");
            r.price = o.optDouble("price", -1);
            r.previousPrice = o.optDouble("previousPrice", -1);
            r.priceChange = o.optDouble("priceChange", 0);
            r.priceText = o.optString("priceText");
            r.available = o.optBoolean("available");
            r.tracked = o.optBoolean("tracked");
            r.historyCount = o.optInt("historyCount", 0);
            r.error = o.optString("error");
            r.timestamp = o.optLong("timestamp");
        } catch (Exception ignored) {}
        return r;
    }
}
