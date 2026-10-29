#!/usr/bin/env bash
# Installs a debug APK without deleting existing app data.
set -euo pipefail
apk=${1:-app/build/outputs/apk/debug/app-debug.apk}
app_id=${APP_ID:-dev.opencode.mobile.debug}

if ! command -v adb >/dev/null 2>&1; then
  echo 'Не найден adb. Установите Android SDK Platform Tools и добавьте их в PATH.' >&2
  exit 1
fi
if [[ ! -f "$apk" || "$apk" != *.apk ]]; then
  echo "APK не найден: $apk. Если вы скачали артефакт Actions, сначала распакуйте ZIP и укажите путь к app-debug.apk." >&2
  exit 1
fi
printf 'Устройство: '; adb shell getprop ro.product.model | tr -d '\r'
printf 'Android: '; adb shell getprop ro.build.version.release | tr -d '\r'
printf 'API: '; adb shell getprop ro.build.version.sdk | tr -d '\r'
printf 'ABI: '; adb shell getprop ro.product.cpu.abilist | tr -d '\r'
echo "Установка $apk…"
set +e
result=$(adb install -r "$apk" 2>&1)
status=$?
set -e
printf '%s\n' "$result"
if (( status != 0 )); then
  case "$result" in
    *INSTALL_FAILED_UPDATE_INCOMPATIBLE*|*INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES*)
      echo "Подпись отличается от уже установленной версии $app_id. Не удаляйте приложение, если нужны его данные. Инструкция: docs/RELEASE.md." >&2 ;;
    *INSTALL_FAILED_OLDER_SDK*)
      echo 'Нужен Android 7.0 (API 24) или новее. Создайте эмулятор с более новой версией Android.' >&2 ;;
    *INSTALL_FAILED_VERSION_DOWNGRADE*)
      echo 'Установлена версия с большим versionCode. Используйте более новую сборку; удаление приложения сотрёт его данные.' >&2 ;;
    *INSTALL_FAILED_INSUFFICIENT_STORAGE*)
      echo 'На устройстве недостаточно места.' >&2 ;;
    *) echo 'Пришлите полный текст ошибки установки и версию Android.' >&2 ;;
  esac
  exit "$status"
fi
adb shell am start -n "$app_id/dev.opencode.mobile.MainActivity"
echo "Запущено: $app_id"
