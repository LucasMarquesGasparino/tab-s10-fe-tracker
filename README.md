# Tab S10 FE Tracker

Rastreador de preços do **Galaxy Tab S10 FE (128 GB)** em 12 lojas
brasileiras: verifica periodicamente, notifica quedas e mostra o histórico —
tudo no aparelho.

- Pacote: `com.tabpreco.s10fe` (nome: **Tab S10 FE Preços**)
- Lojas: Samsung Oficial, Amazon Brasil, Magazine Luiza, Casas Bahia, Ponto,
  Americanas, KaBuM!, Fast Shop, Extra, Carrefour, Mercado Livre, Shopee.
- Modelo-alvo: SM-X520 (Wi-Fi, 128 GB, 8 GB RAM, tela 10,9" 90 Hz).

## Funcionalidades

- Checagem periódica (`PriceCheckService` + alarmes + boot) com parser por
  loja (`PriceChecker`: detecção de domínio + extração de preço).
- Alerta de queda de preço via notificação (`NotificationHelper`).
- Histórico por loja (`Store`, `PriceResult`, `AlarmScheduler`).
- Painel WebView (`assets/tracker/index.html`) com ponte `NativeBridge`.

## Permissões

`INTERNET`, `ACCESS_NETWORK_STATE`, notificações, alarmes exatos, foreground
service, boot. Sem localização.

## Compilar

```sh
cd ~/projects/tab-s10-fe-tracker
bash build.sh
```

APK em `build/` (+ cópia em `Documents/`). Keystore ignorada pelo git.

## Estrutura

```
tab-s10-fe-tracker/
├── src/com/tabpreco/s10fe/
│   ├── MainActivity.java
│   ├── PriceChecker.java / PriceResult.java / Store.java
│   ├── PriceCheckService.java / AlarmScheduler.java / AlarmReceiver.java / BootReceiver.java
│   └── NotificationHelper.java
├── assets/tracker/ / res/ / tools/
```
