#!/bin/bash
# Запускає сервер «Допомога» на Mac і відкриває пульт у браузері.
#
#  • Телефон підключається через публічну HTTPS-адресу Tailscale Funnel
#    (https://<ваш-mac>.<tailnet>.ts.net) — вона постійна, її вшивають у додаток один раз.
#  • Пульт помічника відкривається лише локально: http://localhost:8081 — з інтернету
#    до нього дістатися неможливо.
#  • Поки скрипт працює, Mac не засинає (caffeinate). Кришку не закривайте.
#
# Змінні: PORT (8080), CONSOLE_PORT (8081), TUNNEL=tailscale|none
set -euo pipefail
cd "$(dirname "$0")/.."

PORT="${PORT:-8080}"
CONSOLE_PORT="${CONSOLE_PORT:-8081}"
TUNNEL="${TUNNEL:-tailscale}"

if ! command -v node >/dev/null 2>&1; then
  echo "❌ Потрібен Node.js. Встановіть: brew install node   (або https://nodejs.org)"
  exit 1
fi
[ -d node_modules ] || npm install --omit=dev --silent

SERVER_PID=""; TUNNEL_PID=""
cleanup() {
  [ -n "$TUNNEL_PID" ] && kill "$TUNNEL_PID" 2>/dev/null || true
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

PORT="$PORT" CONSOLE_PORT="$CONSOLE_PORT" node server.js &
SERVER_PID=$!
command -v caffeinate >/dev/null && { caffeinate -i -w "$SERVER_PID" & }
sleep 1

URL=""
if [ "$TUNNEL" = "tailscale" ]; then
  TS="$(command -v tailscale || true)"
  [ -z "$TS" ] && [ -x /Applications/Tailscale.app/Contents/MacOS/Tailscale ] && TS=/Applications/Tailscale.app/Contents/MacOS/Tailscale
  if [ -z "$TS" ]; then
    echo "❌ Не знайдено Tailscale. Встановіть з https://tailscale.com/download/mac і увійдіть,"
    echo "   або запустіть з TUNNEL=none, якщо у вас власний тунель/проброс порту $PORT."
    exit 1
  fi
  "$TS" funnel "$PORT" >/tmp/remote-help-funnel.log 2>&1 &
  TUNNEL_PID=$!
  sleep 3
  URL="$("$TS" funnel status 2>/dev/null | grep -o 'https://[^ ]*' | head -1 | sed 's#/$##' || true)"
  if [ -z "$URL" ]; then
    echo "⚠️  Funnel не запустився. Деталі: /tmp/remote-help-funnel.log"
    echo "   Перший раз Tailscale може попросити увімкнути Funnel за посиланням у цьому лозі."
  fi
fi

echo
echo "✅ Сервер працює."
[ -n "$URL" ] && echo "📱 Адреса для телефону (вкажіть її в додатку один раз):  $URL"
echo "🖥  Пульт помічника:  http://localhost:$CONSOLE_PORT"
echo "   Зупинити: Ctrl+C або закрийте це вікно."
echo
open "http://localhost:$CONSOLE_PORT" 2>/dev/null || true
wait "$SERVER_PID"
