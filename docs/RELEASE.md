# Установка, сертификат и выпуск

## Установка на эмулятор

Новая сборка поддерживает Android **7.0 / API 24 и новее**, без root. В Android Studio создайте эмулятор API 24 или выше и дождитесь его полной загрузки. Для установки нужен именно **APK**: скачанный артефакт GitHub Actions представляет собой ZIP, его необходимо распаковать.

```bash
adb devices
./gradlew :app:assembleDebug
./scripts/install-emulator.sh app/build/outputs/apk/debug/app-debug.apk
```

Если запущено несколько устройств, выберите одно: `export ANDROID_SERIAL=emulator-5554` (используйте номер из `adb devices`). Скрипт выводит версию Android и исходную ошибку, ничего не удаляет и после успешной установки открывает приложение. Отладочный пакет — `dev.opencode.mobile.debug`, его значок подписан `OpenCode Mobile (debug)`. Он может находиться рядом со старым приложением `dev.opencode.mobile`.

Частые причины:

- `INSTALL_FAILED_OLDER_SDK`: версия Android ниже 7.0. Сборка из старого релиза v0.1.0 требовала Android 8.0 / API 26.
- `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / `INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES`: уже установлен пакет с тем же именем, подписанный **другим** ключом. Нужен APK с прежним ключом. Отладочный ключ на новом CI-агенте может меняться, поэтому обновить предыдущую CI debug-сборку получится не всегда. Если данные не нужны, можно удалить только отладочный пакет командой `adb uninstall dev.opencode.mobile.debug` и установить заново. **Удаление стирает данные этого пакета.** Не удаляйте старую версию, если нужна её история.
- `INSTALL_FAILED_VERSION_DOWNGRADE`: versionCode установленного пакета выше. Используйте более новую сборку; удаление также стирает данные.
- `INSTALL_FAILED_INSUFFICIENT_STORAGE`: освободите место в образе эмулятора.
- `no devices` / `more than one device`: запустите эмулятор или укажите `ANDROID_SERIAL`.

Если установка всё ещё не проходит, сохраните полный вывод `adb install -r путь/к/app-debug.apk`, а также `adb shell getprop ro.build.version.sdk`. Если установилось, но закрывается: `adb logcat -d -s AndroidRuntime:E` после запуска. Перед передачей лога удалите из него токены и личные данные.

Для сервера модели на компьютере стандартный Android Emulator видит хост по адресу `10.0.2.2`, например `http://10.0.2.2:11434/v1`; `127.0.0.1` внутри эмулятора — сам эмулятор. На сторонних эмуляторах адрес может отличаться. HTTP разрешён только для локальных адресов и без API-ключа; содержимое запросов по HTTP **не шифруется**.

Если ключ не вставляется, скопируйте его заново и в форме провайдера нажмите «Вставить из буфера» либо удерживайте поле и выберите «Вставить». При использовании Android Emulator проверьте **Enable clipboard sharing** в настройках эмулятора (расположение зависит от версии Android Studio/эмулятора). Не передавайте ключ в чат или лог и не вводите его в shell-команду. HTTP 401 также может означать неподходящий ключ, адрес или отсутствие доступа к модели — проверьте настройки и права у провайдера.

## Что такое сертификат подписи

Android требует, чтобы APK был подписан. Внешний платный сертификат и ручная установка сертификата в эмулятор **не требуются**: владелец приложения создаёт ключ, а `keytool` формирует самоподписанный сертификат. Подпись определяет возможность будущих обновлений, но не означает проверку Google Play и не гарантирует соответствие его правилам.

**Храните приватный keystore и пароли под своим контролем и сделайте защищённую резервную копию.** Не отправляйте их в чат, не коммитьте в Git, не прикладывайте к релизу. Потеря ключа может лишить возможности обновлять установленные вне Google Play приложения. Не создавайте новый ключ для каждого выпуска. Если приложение уже опубликовано, используйте существующий ключ или процедуру ротации соответствующего магазина. У старого debug APK ключ, созданный одноразовым GitHub runner, мог не сохраниться; новую подпись нельзя просто выдать за старую.

### Создание постоянного upload/release-ключа (один раз)

Нужен JDK 17. Запустите скрипт в интерактивном терминале **на своём компьютере**:

```bash
./scripts/create-release-key.sh
```

Скрипт создаст `~/opencode-signing/upload-key.p12` и публичный `~/opencode-signing/upload-certificate.pem`. `keytool` сам спросит пароль и сведения для сертификата. Скрипт не перезаписывает существующие файлы и не создаёт ключ внутри репозитория. Сохраните пароль и файл в защищённом хранилище и создайте резервную копию. Для PKCS12 используйте один пароль для хранилища и ключа. Показать SHA-256 сертификата:

```bash
keytool -list -v -keystore "$HOME/opencode-signing/upload-key.p12" -alias opencode-upload
```

Если ключ создан, но экспорт сертификата не завершился, повторите только экспорт:

```bash
keytool -exportcert -rfc -keystore "$HOME/opencode-signing/upload-key.p12" \
  -alias opencode-upload -file "$HOME/opencode-signing/upload-certificate.pem"
```

`upload-certificate.pem` содержит только публичный сертификат; `upload-key.p12` — секретный приватный ключ. Для Google Play App Signing этот ключ обычно является **upload key**; конечный сертификат подписи приложения может управляться Google Play и отличаться. Настройка аккаунта Play и принятие приложения требуют действий владельца.

### Подписанная локальная сборка

Задайте путь и alias, затем введите пароль в терминале без отображения (bash):

```bash
export RELEASE_STORE_FILE="$HOME/opencode-signing/upload-key.p12"
export RELEASE_KEY_ALIAS=opencode-upload
read -rsp 'Пароль хранилища: ' RELEASE_STORE_PASSWORD; echo
export RELEASE_STORE_PASSWORD
export RELEASE_KEY_PASSWORD="$RELEASE_STORE_PASSWORD"
./gradlew :app:assembleRelease :app:bundleRelease
unset RELEASE_STORE_PASSWORD RELEASE_KEY_PASSWORD
```

Для JKS с разными паролями задайте `RELEASE_KEY_PASSWORD` отдельно таким же способом. Не запускайте `env` и не печатайте переменные с паролями. При отсутствии любого из четырёх параметров сборка release завершается с ошибкой, а не выдаёт неподписанный файл под видом готового релиза.

Результаты: `app/build/outputs/apk/release/app-release.apk` и `app/build/outputs/bundle/release/app-release.aab`. Проверка:

```bash
"$ANDROID_HOME/build-tools/35.0.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
sha256sum app/build/outputs/apk/release/app-release.apk app/build/outputs/bundle/release/app-release.aab
```

Для установки release APK используйте `APP_ID=dev.opencode.mobile ./scripts/install-emulator.sh путь/к/app-release.apk`. AAB загружается в магазин, напрямую через `adb install` его не установить.

### GitHub Actions

В **Settings → Secrets and variables → Actions** добавьте секреты `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`. Например, при установленном и авторизованном GitHub CLI можно передать значения через stdin, не выводя приватный ключ на экран. Если вы уже выполнили `unset` после локальной сборки, сначала повторно введите пароль:

```bash
export RELEASE_STORE_FILE="$HOME/opencode-signing/upload-key.p12"
export RELEASE_KEY_ALIAS=opencode-upload
read -rsp 'Пароль хранилища: ' RELEASE_STORE_PASSWORD; echo
export RELEASE_STORE_PASSWORD
export RELEASE_KEY_PASSWORD="$RELEASE_STORE_PASSWORD"
base64 < "$RELEASE_STORE_FILE" | tr -d '\n' | gh secret set RELEASE_KEYSTORE_BASE64
printf '%s' "$RELEASE_STORE_PASSWORD" | gh secret set RELEASE_STORE_PASSWORD
printf '%s' "$RELEASE_KEY_ALIAS" | gh secret set RELEASE_KEY_ALIAS
printf '%s' "$RELEASE_KEY_PASSWORD" | gh secret set RELEASE_KEY_PASSWORD
unset RELEASE_STORE_PASSWORD RELEASE_KEY_PASSWORD
```

Проверяйте, что `gh` работает с правильным репозиторием. Не вставляйте значения секретов в чат. Workflow тестирует debug, установку на API 24 и 35 и отдельно проверяет подписание **временным тестовым ключом**, который не используется для публикации. При push тега `v*` release job использует секреты владельца, сверяет тег с `versionName`, проверяет подписи APK/AAB и только после прохождения тестов создаёт GitHub Release с публичным отпечатком сертификата и SHA-256. Без секретов выпуск остановится. Тестовый и debug-ключи не являются production-ключами.

Перед первой публикацией проверьте актуальные требования магазина к target API, разрешению `MANAGE_EXTERNAL_STORAGE`, root-функциям, политике конфиденциальности и обработке пользовательских данных; сами тесты и подпись не заменяют этот аудит.
