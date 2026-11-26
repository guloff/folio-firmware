# FolioLink для Android

Приложение-компаньон для читалки с прошивкой Folio: Kotlin, Jetpack Compose, Material 3.
*Android companion app for the Folio firmware. Documentation is in Russian.*

**[⬇ Скачать APK](https://github.com/guloff/folio-firmware/releases/download/android-1.1.3/FolioLink-1.1.3.apk)**
· [все версии](https://github.com/guloff/folio-firmware/releases?q=android) · для iPhone —
[FolioLink в App Store](https://apps.apple.com/app/id6816375030)

По функциям повторяет iOS-версию 1.1: статистика и итоги года, библиотека и полки, цитаты и
личный словарь (карточки SM-2, экспорт в Markdown и Readwise), студия заставок и экран сна,
скриншоты, шрифты, словари, настройки читалки и их профили, обновление прошивки с проверкой
подписи, сопряжение по PIN, очередь отправки, синхронизация по Bluetooth, виджеты,
«Поделиться → FolioLink», демо-режим. Языки — английский и русский.

- Android 8.0 и новее (minSdk 26), target SDK 37.
- Package: `com.rustamguloff.foliolink`.
- Нужна прошивка Folio 1.1 или новее: [релизы прошивки](https://github.com/guloff/folio-firmware/releases/latest).

## Установка APK

1. Скачайте `FolioLink-<версия>.apk` из релиза на телефоне.
2. Откройте файл и разрешите установку из этого источника (браузера или файлового менеджера).
3. При первом подключении разрешите доступ к устройствам в локальной сети — без него
   Android 17 молча блокирует связь с читалкой по Wi‑Fi.

Новая версия ставится поверх старой, данные сохраняются. Проверить, что файл не подменён,
можно по `FolioLink-<версия>.apk.sha256` в том же релизе. APK подписан ключом автора; если
у вас стояла версия из Google Play, её сначала нужно удалить — подписи у них разные.

## Сборка

Нужны JDK 17 и Android SDK (путь — в `local.properties`, `sdk.dir=…`).

```sh
cd android
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew assembleRelease bundleRelease   # R8; подпись — из keystore.properties
```

Без `keystore.properties` релиз собирается неподписанным. Чтобы подписать своим ключом,
положите рядом `keystore.properties` с полями `storeFile`, `storePassword`, `keyAlias`,
`keyPassword` (файл и ключ в git не попадают — см. `.gitignore`).

## Проверка без читалки

Демо-режим в самом приложении («Попробовать без читалки») или симулятор прошивки на компьютере
(раздел «Сборка» в [README репозитория](../README.md), порт `CROSSPOINT_SIM_HTTP_PORT`).
Из эмулятора компьютер доступен по адресу `10.0.2.2`. Отладочная сборка принимает параметры:

```sh
adb shell am start -n com.rustamguloff.foliolink/.MainActivity --es autoConnect 10.0.2.2:18080 --es tab stats
adb shell am start -n com.rustamguloff.foliolink/.MainActivity --ez demo true --es open screensaver
```

`tab`: home | stats | library | notes | more; `open`: screensaver, sleepImages, screenshots,
fonts, deviceSettings, appSettings, firmware, device, outbox, wrapped, readwise, addArticle.

## Устройство кода

```
app/src/main/java/com/rustamguloff/foliolink/
├── core/model         модели API и локальные, правила слияния синхронизации
├── core/net           HTTP-клиент, поиск читалки (NSD + UDP), демо-читалка, очередь отправки
├── core/ble           протокол Bluetooth LE, взаимная аутентификация, синхронизация
├── core/store         снимок данных, очередь в файлах, токен в Android Keystore
├── core/security      проверка подписи прошивки (Ed25519)
├── core/dictionaries  StarDict и каталог словарей
├── core/stats, imaging, epub, export, services
├── app                AppModel — центр состояния, настройки, тексты ошибок
└── ui                 экраны Compose, тема, навигация, виджеты Glance, «Поделиться»
```

`core/*` почти не зависит от Android и покрыт JVM-тестами (`app/src/test`). Протокол с
прошивкой: Wi‑Fi API `/api/inklink/*` с токеном `X-InkLink-Token`, Bluetooth LE с HMAC.

## Что важно знать

- Bluetooth работает, пока жив процесс приложения: фоновой службы нет.
- Скриншоты сохраняются в `Pictures/FolioLink`.
- Правило R8 для Room/WorkManager (его тянет Glance) в `app/proguard-rules.pro` обязательно —
  без него релиз падает при запуске.

## Лицензия

MIT, как и весь репозиторий (см. `../LICENSE`).
