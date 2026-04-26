# TurnFree

Android-приложение для запуска локального TURN/DTLS-клиента и последующего поднятия WireGuard-туннеля поверх него.

## Возможности

- Импорт конфигурации из файла или из буфера обмена.
- Поддержка `turnbridge://`, raw Base64, JSON и WireGuard `.conf`.
- Автоматический запуск TURN-сессии и WireGuard-туннеля.
- Фоновая работа через `Foreground Service`.
- Статус подключения и ошибки в интерфейсе и уведомлении.
- Действия из уведомления: открыть приложение или остановить соединение.
- Сохранение профиля и настроек между запусками.

## Совместимость

Приложение поддерживает импорт конфигов, экспортированных из `turnbridge`:

- https://github.com/nullcstring/turnbridge

## Основано на

- `vk-turn-proxy`: https://github.com/cacggghp/vk-turn-proxy
- `WireGuard` Android tunnel library: https://github.com/WireGuard/wireguard-android

## Требования

- Android 24+
- `arm64-v8a`

## Сборка

Debug:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew :app:assembleDebug
```

Release:

```bash
GRADLE_USER_HOME=/tmp/gradle-home ./gradlew :app:assembleRelease
```

Релизный APK будет собран в:

- `app/build/outputs/apk/release/app-release.apk`

## Формат конфигов

Поддерживаются:

- `turnbridge://<base64>`
- raw Base64
- JSON
- WireGuard `.conf`
