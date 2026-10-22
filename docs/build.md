# Сборка из исходников

## Что нужно

- **JDK 17** (именно 17; 8 не соберёт).
- **Android SDK** (путь в `local.properties`).
- На выходе — `gateway-magisk.zip`: APK `com.callagent.gateway` + права priv-app + `tinymix`.

## Быстрый путь

```bash
cd gsm2sip
./build.sh release        # → gateway-magisk.zip
```

`build.sh` собирает release-APK (`:app:assembleRelease`) и пакует Magisk-модуль. Release
подписывается **тем же debug-ключом**, что и debug-сборка — намеренно: подпись не меняется,
поэтому release-APK можно подменить в Magisk-модуле без ошибки PackageManager о несовпадении
подписи. Смысл release-сборки здесь не секретность, а `debuggable=false` (ART компилирует
debuggable-приложение в деоптимизируемом режиме со слабым инлайнингом — это заметный CPU в
покадровом аудио-цикле).

## Грабли на Windows / MSYS (запомнить)

Собиралось в Git Bash / MSYS. Набор проблем, на которые уходит время:

1. **32-битная JVM в `JAVA_HOME`.** Gradle-демон берёт JVM из `JAVA_HOME`; если там 32-битная
   (corretto8 и т.п.) — падает на куче. Перед сборкой задать JDK17 коротким путём без пробелов:
   ```bash
   export JAVA_HOME='C:\PROGRA~1\ECLIPS~1\JDK-17~1.101'
   export PATH="/c/PROGRA~1/ECLIPS~1/JDK-17~1.101/bin:$PATH"
   ```
2. **Обратные слэши в `local.properties`.** Путь к SDK — только **прямыми** слэшами:
   ```
   sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
   ```
   Обратные слэши в `.properties` — escape-последовательности (`\U`, `\A`, `\L`, `\S`) → при
   сборке `IOException: синтаксическая ошибка в имени файла` на `compileReleaseJavaWithJavac`.
3. **`lintVital` падает по пути.** Собирать с пропуском:
   ```bash
   ./gradlew :app:assembleRelease -x lintVitalReportRelease -x lintVitalAnalyzeRelease
   ```
4. **adb с Unix-путями** (`/sdcard`, `/data/...`) — только с `MSYS_NO_PATHCONV=1`, иначе MSYS
   конвертирует пути и `push`/`cp` уходят мимо.

## Деплой в Magisk-модуль при разработке (без полной перепрошивки)

```bash
# подложить новый APK в уже установленный модуль (подпись должна совпадать):
adb push app/build/outputs/apk/release/app-release.apk /data/local/tmp/new.apk
adb shell su -mm -c 'cp /data/local/tmp/new.apk \
  /data/adb/modules/sip-gsm-gateway/system/priv-app/Gateway/Gateway.apk && \
  chmod 644 /data/adb/modules/sip-gsm-gateway/system/priv-app/Gateway/Gateway.apk && \
  chown root:root /data/adb/modules/sip-gsm-gateway/system/priv-app/Gateway/Gateway.apk && \
  chcon u:object_r:system_file:s0 /data/adb/modules/sip-gsm-gateway/system/priv-app/Gateway/Gateway.apk'
# затем перезагрузка; на следующей загрузке service.sh синхронизирует APK в priv-app
adb reboot
```

> `su -mm` не видит `/sdcard` — копировать через `/data/local/tmp`. После ребута в баннере
> приложения видно версию, напр. `[v1.3.11] … registrar on <IP>:5062` — убедиться, что встал
> нужный APK (сверить md5 в модуле).

## ABI tinymix

`tinymix` собран под обе архитектуры (`tinymix` — arm64, `tinymix32` — armeabi-v7a); `install.sh`
кладёт нужный по `ro.product.cpu.abi`. S4 mini — **32-битный** (`armeabi-v7a`).

## Перед «тиражом» (убрать отладку)

В текущем коде намеренно включена отладка для живых тестов — вернуть перед релизом:

- подробные SIP-логи `SipWire` в `SipClient.doSend/handlePacket` — убрать/заглушить;
- таймаут тишины RTP поднят до 1 часа → вернуть **30 с** (`RTP_TIMEOUT_MS` в `RtpSession.kt`);
- снять неиспользуемый параметр `network` у `SipClient`/`RtpSession` (после `preboundSocket`).
