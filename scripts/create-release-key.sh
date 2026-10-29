#!/usr/bin/env bash
# Run once on the publisher's computer, in an interactive terminal.
set -euo pipefail
if ! command -v keytool >/dev/null 2>&1; then
  echo 'Не найден keytool. Установите JDK 17 и добавьте его в PATH.' >&2
  exit 1
fi
if [[ ! -t 0 ]]; then
  echo 'Нужен интерактивный терминал: keytool запросит пароль, не показывая его на экране.' >&2
  exit 1
fi
umask 077
directory=${1:-"$HOME/opencode-signing"}
mkdir -p "$directory"
directory=$(cd "$directory" && pwd -P)
repo=$(git -C "$(dirname "$0")" rev-parse --show-toplevel 2>/dev/null || true)
if [[ -n "$repo" && ( "$directory" == "$repo" || "$directory" == "$repo/"* ) ]]; then
  echo 'Выберите папку вне репозитория для приватного ключа.' >&2
  exit 1
fi
keystore="$directory/upload-key.p12"
certificate="$directory/upload-certificate.pem"
if [[ -e "$keystore" || -e "$certificate" ]]; then
  echo "Файл ключа или сертификата уже существует в $directory; ничего не перезаписано." >&2
  exit 1
fi
keytool -genkeypair -v -keystore "$keystore" -storetype PKCS12 \
  -alias opencode-upload -keyalg RSA -keysize 3072 -validity 10000
keytool -exportcert -rfc -keystore "$keystore" -alias opencode-upload -file "$certificate"
echo "Публичный сертификат: $certificate"
echo "Приватный ключ: $keystore — храните в секрете и сделайте резервную копию."
