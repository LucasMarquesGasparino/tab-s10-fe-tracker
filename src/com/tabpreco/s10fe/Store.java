package com.tabpreco.s10fe;

public final class Store {
    public final String id;
    public final String name;
    /** Página usada somente para descobrir o anúncio correto. */
    public final String searchUrl;
    /** Link conhecido do produto, usado como primeira tentativa de descoberta. */
    public final String seedProductUrl;
    /** Mantido como alias para compatibilidade com a UI antiga. */
    public final String url;
    public final String color;

    public Store(String id, String name, String url, String color) {
        this(id, name, url, null, color);
    }

    public Store(String id, String name, String searchUrl, String seedProductUrl, String color) {
        this.id = id;
        this.name = name;
        this.searchUrl = searchUrl;
        this.seedProductUrl = seedProductUrl;
        this.url = searchUrl;
        this.color = color;
    }

    // Lista sincronizada com assets/tracker/app.js → todas as lojas aparecem após verificar.
    // Removidas: submarino (domínio morto), shoptime (sem o produto no catálogo),
    // pichau/terabyte/colombo (não vendem o Tab S10 FE — busca retorna só Lenovo/genéricos).
    // Seeds validados via busca: shopee antigo estava banido (item_status=banned), americanas/
    // casasbahia/shoptime ganharam seeds reais, ponto/extra atualizados para IDs ativos.
    public static final Store[] ALL = new Store[]{
        new Store("samsung", "Samsung Oficial", "https://www.samsung.com/br/search/?searchvalue=galaxy+tab+s10+fe", "https://www.samsung.com/br/tablets/galaxy-tab-s/galaxy-tab-s10-fe-gray-128gb-sm-x520nzadzto/", "#1428A0"),
        new Store("amazon", "Amazon Brasil", "https://www.amazon.com.br/s?k=Samsung+Galaxy+Tab+S10+FE", "https://www.amazon.com.br/dp/B0F3LZ6RHF", "#FF9900"),
        new Store("magal", "Magazine Luiza", "https://www.magazineluiza.com.br/busca/galaxy+tab+s10+fe/", "https://www.magazineluiza.com.br/tablet-samsung-galaxy-tab-s10-fe-wifi-128gb-8gb-tela-10-9-90hz-s-pen-e-capa-inclusas/p/ecaj4a44c2/tb/sams/", "#0086FF"),
        new Store("casasbahia", "Casas Bahia", "https://www.casasbahia.com.br/busca?strBusca=galaxy+tab+s10+fe", "https://www.casasbahia.com.br/tablet-samsung-galaxy-tab-s10-fe-10-9-android-15-128gb-exynos-1580-wi-fi-13mp-caneta-s-pen-capa-protetora-cinza/p/55069338", "#CC0000"),
        new Store("ponto", "Ponto", "https://www.pontofrio.com.br/busca?strBusca=galaxy+tab+s10+fe", "https://www.pontofrio.com.br/tablet-samsung-galaxy-tab-s10-fe-wifi-128gb-8gb-tela-109-quot-90hz-s-pen-e-capa-inclusas-prata/p/1571942273", "#FF6600"),
        new Store("americanas", "Americanas", "https://www.americanas.com.br/busca/galaxy-tab-s10-fe", "https://www.americanas.com.br/tablet-samsung-galaxy-tab-s10-fe-com-capa-e-caneta--s-pen-8gb-ram-128gb-109--android-15-exynos-1580-wi-fi-8297275/p", "#E60014"),
        new Store("kabum", "KaBuM!", "https://www.kabum.com.br/busca/galaxy-tab-s10-fe", "https://www.kabum.com.br/produto/1004924/tablet-samsung-galaxy-tab-s10-fe-128gb-8gb-ram-tela-10-9-90hz-camera-traseira-13mp-frontal-12mp-ultra-wide-wifi-prata", "#FF6500"),
        new Store("fastshop", "Fast Shop", "https://secure3.fastshop.com.br/s?q=galaxy+tab+s10+fe", "https://secure3.fastshop.com.br/tablet-samsung-galaxy--s10-fe--128gb--8gb-ram--tela-imersiva-10-9---90hz--wifi-6--ip68--android-15---sm-x520nlbdzto-sgsmx520nlazl_prd/p", "#000000"),
        new Store("extra", "Extra", "https://www.extra.com.br/tablet-s10-fe/b", "https://www.extra.com.br/tablet-samsung-galaxy-tab-s10-fe-10-9-android-15-128gb-exynos-1580-wi-fi-13mp-caneta-s-pen-capa-protetora-cinza/p/55069338", "#E30613"),
        new Store("carrefour", "Carrefour", "https://www.carrefour.com.br/busca/galaxy-tab-s10-fe", "https://www.carrefour.com.br/produto/galaxy-tab-s10-fe-wifi-109-8gb-ram-128gb-cinza-334552877", "#004E9E"),
        new Store("mercadolivre", "Mercado Livre", "https://lista.mercadolivre.com.br/galaxy-tab-s10-fe#D[A:galaxy-tab-s10-fe]", "https://www.mercadolivre.com.br/tablet-samsung-galaxy-tab-s10-fe-wifi-128gb-8gb-tela-109-90hz-s-pen-e-capa-inclusas-cinza/p/MLB47885021", "#FFE600"),
        new Store("shopee", "Shopee", "https://shopee.com.br/search?keyword=galaxy%20tab%20s10%20fe%20128gb", "https://shopee.com.br/Tablet-Samsung-Galaxy-Tab-S10-FE-128GB-8GB-RAM-Wi-Fi-SM-X520-i.1676312660.22194906588", "#EE4D2D")
    };

    public static Store findById(String id) {
        for (Store s : ALL) if (s.id.equals(id)) return s;
        return null;
    }
}
