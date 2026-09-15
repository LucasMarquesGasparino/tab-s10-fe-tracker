const STORES = [
  {id:"samsung", name:"Samsung Oficial", url:"https://www.samsung.com/br/search/?searchvalue=galaxy+tab+s10+fe", color:"#1428A0", short:"SAM"},
  {id:"amazon", name:"Amazon Brasil", url:"https://www.amazon.com.br/s?k=Samsung+Galaxy+Tab+S10+FE", color:"#FF9900", short:"AMZ"},
  {id:"magal", name:"Magazine Luiza", url:"https://www.magazineluiza.com.br/busca/galaxy+tab+s10+fe/", color:"#0086FF", short:"MAG"},
  {id:"casasbahia", name:"Casas Bahia", url:"https://www.casasbahia.com.br/busca?strBusca=galaxy+tab+s10+fe", color:"#CC0000", short:"CB"},
  {id:"ponto", name:"Ponto", url:"https://www.pontofrio.com.br/busca?strBusca=galaxy+tab+s10+fe", color:"#FF6600", short:"PTO"},
  {id:"americanas", name:"Americanas", url:"https://www.americanas.com.br/busca/galaxy-tab-s10-fe", color:"#E60014", short:"AME"},
  {id:"kabum", name:"KaBuM!", url:"https://www.kabum.com.br/busca/galaxy-tab-s10-fe", color:"#FF6500", short:"KAB"},
  {id:"fastshop", name:"Fast Shop", url:"https://secure3.fastshop.com.br/s?q=galaxy+tab+s10+fe", color:"#000000", short:"FAST"},
  {id:"extra", name:"Extra", url:"https://www.extra.com.br/tablet-s10-fe/b", color:"#E30613", short:"EXT"},
  {id:"carrefour", name:"Carrefour", url:"https://www.carrefour.com.br/busca/galaxy-tab-s10-fe", color:"#004E9E", short:"CAR"},
  {id:"mercadolivre", name:"Mercado Livre", url:"https://lista.mercadolivre.com.br/galaxy-tab-s10-fe#D[A:galaxy-tab-s10-fe]", color:"#FFE600", short:"ML", darkText:true},
  {id:"shopee", name:"Shopee", url:"https://shopee.com.br/search?keyword=galaxy%20tab%20s10%20fe%20128gb", color:"#EE4D2D", short:"SPE"},
];

let lastData = null;
let checking = false;
let progressTimer = null;

const $ = s => document.querySelector(s);
const storesList = $("#storesList");
const btnCheck = $("#btnCheck");
const statLast = $("#statLast");
const statBest = $("#statBest");
const metaStatus = $("#metaStatus");
const metaAlarm = $("#metaAlarm");
const switchAuto = $("#switchAuto");
const bestCard = $("#bestCard");
const bestPrice = $("#bestPrice");
const bestStore = $("#bestStore");
const btnBestOpen = $("#btnBestOpen");
const btnBestShare = $("#btnBestShare");
const btnDiscover = $("#btnDiscover");
const countAvail = $("#countAvail");
const progress = $("#progress");
const progressBar = $("#progressBar");
const notifBanner = $("#notifBanner");
const btnEnableNotif = $("#btnEnableNotif");
const linkExact = $("#linkExact");
const metaExact = $("#metaExact");

function hasNative(){ return typeof NativeBridge !== 'undefined' && NativeBridge.getLastResults; }

function formatDate(ts){
  if(!ts) return "nunca";
  try{ return new Date(ts).toLocaleString('pt-BR', {day:'2-digit', month:'2-digit', hour:'2-digit', minute:'2-digit'}) }catch(e){ return new Date(ts).toString() }
}

function escapeHtml(value){
  return String(value == null ? "" : value).replace(/[&<>\"']/g, c=>({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]));
}

function renderStores(data){
  // data: {results:[], timestamp, dateISO, bestStoreName, bestPriceText}
  const results = data && data.results ? data.results : [];
  const map = {};
  results.forEach(r=> map[r.storeId]=r);

  storesList.innerHTML = "";
  let availCount = 0;
  let best = null;
  if(data && data.bestPrice) {
    best = {price:data.bestPrice, priceText:data.bestPriceText, name:data.bestStoreName, url:data.bestUrl, id:data.bestStoreId};
  } else {
    // achar menor manualmente
    results.filter(r=>r.available).sort((a,b)=>a.price-b.price);
    if(results.filter(r=>r.available).length) {
      const b = results.filter(r=>r.available).sort((a,b)=>a.price-b.price)[0];
      best = {price:b.price, priceText:b.priceText, name:b.storeName, url:b.url, id:b.storeId};
    }
  }

  STORES.forEach(store=>{
    const r = map[store.id] || {storeId:store.id, storeName:store.name, url:store.url, price:-1, priceText:"", available:false, error:"Nunca verificado"};
    if(r.available) availCount++;
    const isBest = best && r.storeId===best.id && r.available;
    const direct = !!r.tracked && /^https?:\/\//i.test(r.url || "");
    const displayUrl = direct ? r.url : (r.searchUrl || store.url);
    const trend = r.previousPrice > 0 && r.priceChange != null
      ? (r.priceChange < -0.009 ? ` • ↓ ${Math.abs(r.priceChange).toLocaleString('pt-BR',{style:'currency',currency:'BRL'})}`
        : (r.priceChange > 0.009 ? ` • ↑ ${r.priceChange.toLocaleString('pt-BR',{style:'currency',currency:'BRL'})}` : " • estável"))
      : "";
    const priceLabel = r.available ? escapeHtml(r.priceText) : escapeHtml(r.error || '—');
    const div = document.createElement("div");
    div.className = "store" + (isBest? " best":"");
    div.innerHTML = `
      <div class="store-icon" style="background:${store.color}; ${store.darkText?'color:#111':''}">${store.short}</div>
      <div class="store-main">
        <h4>${escapeHtml(store.name)} ${isBest?'🏆':''}</h4>
        <p class="product-link-label">${direct?'🔗 Link direto monitorado':'🔎 Link de busca — ainda não descoberto'}<br>${escapeHtml(displayUrl)}</p>
        ${r.productTitle ? `<p class="product-title">${escapeHtml(r.productTitle)}</p>` : ''}
        <div class="store-actions">
          <button class="btn-mini" data-open="${store.id}">${direct?'Abrir anúncio':'Abrir busca'}</button>
          <button class="btn-mini primary" data-check="${store.id}" style="display:none">Ver preço</button>
        </div>
      </div>
      <div class="price-col">
        <div class="price-val ${r.available?'avail':'unavail'}">${priceLabel}</div>
        <div class="price-sub">${r.available? 'link direto • '+formatDate(r.timestamp)+trend : (direct ? 'link salvo • '+formatDate(r.timestamp) : 'toque em verificar')}</div>
      </div>
    `;
    storesList.appendChild(div);
    // bind abrir
    div.querySelector("[data-open]").addEventListener("click", ()=> openStore(displayUrl));
  });
  const trackedCount = results.filter(r=>r.tracked && r.url).length;
  countAvail.textContent = availCount + " preços • " + trackedCount + " links diretos • " + (results.length? results.length : STORES.length) + " lojas";

  // best card
  if(best){
    bestCard.classList.add("show");
    bestPrice.textContent = best.priceText;
    bestStore.textContent = best.name + " • " + best.url;
    statBest.textContent = best.priceText + " na " + best.name;
    btnBestOpen.onclick = ()=> openStore(best.url);
    btnBestShare.onclick = async ()=>{
      const text = `Menor preço Galaxy Tab S10 FE: ${best.priceText} na ${best.name} — ${best.url}`;
      if(navigator.share){ try{ await navigator.share({title:"Tab S10 FE", text}); }catch(e){} }
      else if(navigator.clipboard){ await navigator.clipboard.writeText(text); alert("Copiado: "+text); }
      else { prompt("Copie:", text); }
    };
  } else {
    bestCard.classList.remove("show");
    statBest.textContent = "—";
  }

  // última verificação
  if(data && data.timestamp){
    statLast.textContent = data.dateISO || formatDate(data.timestamp);
  }
}

function openStore(url){
  if(hasNative()){
    try{ NativeBridge.openStore(url); return; }catch(e){}
  }
  window.open(url, "_blank");
}

function loadLast(){
  try{
    if(hasNative()){
      const json = NativeBridge.getLastResults();
      const data = JSON.parse(json);
      lastData = data;
      renderStores(data);
      updateAlarmUI();
      checkNotifPermissionUI();
      return;
    }
  }catch(e){ console.error(e); }
  // fallback localStorage browser
  try{
    const ls = localStorage.getItem("tab_s10_last");
    if(ls){ lastData = JSON.parse(ls); renderStores(lastData); }
    else {
      renderStores({results: STORES.map(s=>({storeId:s.id, storeName:s.name, url:s.url, price:-1, available:false, error:"Nunca verificado"})), timestamp:0});
    }
  }catch(e){
    renderStores({results:[]});
  }
}

function updateAlarmUI(){
  try{
    let info = null;
    if(hasNative()) info = JSON.parse(NativeBridge.getAlarmInfo());
    else {
      const en = localStorage.getItem("alarm_enabled") !== "0";
      info = {enabled:en, scheduled:en, next: Date.now()+ 86400000};
    }
    switchAuto.classList.toggle("on", !!info.enabled);
    switchAuto.setAttribute("aria-checked", String(!!info.enabled));
    if(info.enabled){
      if(info.next){
        const d = new Date(info.next);
        metaAlarm.textContent = "Próxima: " + d.toLocaleString('pt-BR', {weekday:'short', day:'2-digit', month:'2-digit', hour:'2-digit', minute:'2-digit'});
      } else metaAlarm.textContent = "Ativo — todo dia às 10:00";
    } else metaAlarm.textContent = "Desativado";
    // exact alarm warning
    if(hasNative()){
      const canExact = NativeBridge.canScheduleExactAlarms();
      metaExact.style.display = canExact? "none":"block";
    }
  }catch(e){}
}

function checkNotifPermissionUI(){
  try{
    if(hasNative()){
      const has = NativeBridge.hasNotificationPermission();
      notifBanner.classList.toggle("show", !has);
    } else {
      if("Notification" in window) notifBanner.classList.toggle("show", Notification.permission!=="granted");
    }
  }catch(e){}
}

function setChecking(v, discovering){
  checking=v;
  btnCheck.disabled=v;
  if(btnDiscover) btnDiscover.disabled=v;
  btnCheck.classList.toggle("loading", v);
  btnCheck.querySelector(".txt").textContent = v? (discovering ? "🔗 Descobrindo links..." : "Verificando links salvos...") : "🔍 Verificar preços agora";
  progress.style.display = v? "block":"none";
  metaStatus.textContent = v? "Consultando lojas em paralelo..." : "Pronto";
  if(v){
    let w=8;
    progressBar.style.width=w+"%";
    progressTimer=setInterval(()=>{
      w=Math.min(94, w+ Math.random()*9);
      progressBar.style.width=w+"%";
    }, 650);
  } else {
    clearInterval(progressTimer);
    progressBar.style.width="100%";
    setTimeout(()=> progress.style.display="none", 900);
  }
}

// botões
btnCheck.addEventListener("click", ()=>{
  if(checking) return;
  if(hasNative()){
    // pedir permissão notif se ainda não tem
    try{ if(!NativeBridge.hasNotificationPermission()) NativeBridge.requestNotificationPermission(); }catch(e){}
    setChecking(true, false);
    try{ NativeBridge.checkPricesNow(); }catch(e){ setChecking(false); alert("Erro: "+e); }
    // timeout fallback 160s (WebView anti-bot pode levar ~12s por loja em 5 lojas)
    setTimeout(()=>{ if(checking) { setChecking(false); loadLast(); metaStatus.textContent="Tempo limite - tente novamente"; } }, 160000);
  } else {
    setChecking(false);
    metaStatus.textContent="Abra o APK para consultar as lojas; o navegador não gera preços fictícios.";
  }
});

if(btnDiscover) btnDiscover.addEventListener("click", ()=>{
  if(checking) return;
  if(!hasNative()){
    metaStatus.textContent="Abra o APK para redescobrir links reais dos produtos.";
    return;
  }
  setChecking(true, true);
  try{ NativeBridge.rediscoverProducts(); }
  catch(e){ setChecking(false); alert("Erro: "+e); }
  setTimeout(()=>{ if(checking) { setChecking(false); loadLast(); metaStatus.textContent="Tempo limite - tente novamente"; } }, 160000);
});

switchAuto.addEventListener("click", ()=>{
  const nowOn = switchAuto.classList.contains("on");
  const next = !nowOn;
  switchAuto.classList.toggle("on", next);
  if(hasNative()){
    NativeBridge.setAlarmEnabled(next);
  } else {
    localStorage.setItem("alarm_enabled", next?"1":"0");
    updateAlarmUI();
  }
});
switchAuto.addEventListener("keydown", e=>{
  if(e.key==="Enter"||e.key===" "){ e.preventDefault(); switchAuto.click(); }
});

btnEnableNotif.addEventListener("click", ()=>{
  if(hasNative()){
    NativeBridge.requestNotificationPermission();
    setTimeout(checkNotifPermissionUI, 900);
  } else {
    if("Notification" in window) Notification.requestPermission().then(()=> checkNotifPermissionUI());
  }
});
if(linkExact) linkExact.addEventListener("click", e=>{
  e.preventDefault();
  if(hasNative()) NativeBridge.openExactAlarmSettings();
});

window.onCheckStarted = function(discovering){
  setChecking(true, !!discovering);
};
window.onCheckFinished = function(data){
  setChecking(false);
  if(data){
    lastData=data;
    // se veio string escapada já é objeto
    renderStores(data);
    metaStatus.textContent="Atualizado em "+ (data.dateISO || formatDate(data.timestamp));
    // também notificar no browser se permitido
    if(!hasNative() && "Notification" in window && Notification.permission==="granted" && data.bestPriceText){
      try{ new Notification("Menor preço: "+data.bestPriceText+" na "+data.bestStoreName);}catch(e){}
    }
  } else {
    // fallback recarregar do prefs
    loadLast();
    metaStatus.textContent="Verificação concluída (sem dados novos)";
  }
  try{ if(!hasNative()) localStorage.setItem("tab_s10_last", JSON.stringify(data)); }catch(e){}
};
window.onAlarmInfo = function(info){
  // info exato vindo do nativo
  if(info){
    switchAuto.classList.toggle("on", !!info.enabled);
    if(info.next) metaAlarm.textContent = "Próxima: " + new Date(info.next).toLocaleString('pt-BR', {weekday:'short', day:'2-digit', month:'2-digit', hour:'2-digit', minute:'2-digit'});
    else metaAlarm.textContent = info.enabled? "Ativo — todo dia às 10:00" : "Desativado";
  }
};
window.onNativePermissionResult = function(granted){
  notifBanner.classList.toggle("show", !granted);
};

// init
loadLast();
// pedir notif no browser se ainda não
if(!hasNative() && "Notification" in window && Notification.permission==="default"){
  // não pedir automaticamente, só mostrar banner
}
checkNotifPermissionUI();
updateAlarmUI();

// atualizar alarm info a cada 30s
setInterval(updateAlarmUI, 30000);
