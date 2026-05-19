# TurnFree

TurnFree — Android-приложение для запуска локального TURN/DTLS-клиента и поднятия WireGuard-туннеля поверх него.

## Что умеет

- Импорт конфигурации из файла или буфера обмена
- Поддержка `turnbridge://`, Base64, JSON и WireGuard `.conf`
- Автоматический запуск TURN-сессии и WireGuard-туннеля
- Подробный прогресс подключения с живыми этапами
- Обработка VK-капчи: авто-попытка, ручной fallback и понятные ошибки
- Работа с несколькими транспортными потоками
- Раздельное туннелирование:
  - исключить выбранные приложения
  - или туннелировать только выбранные
- Фоновая работа через foreground service
- Сохранение профиля и настроек между запусками

## Поддерживаемые конфиги

- `turnbridge://<base64>`
- Base64-строка
- JSON-конфиг
- WireGuard `.conf`

## Совместимость

- Android 7.0+
- Архитектура `arm64-v8a`

## Основано на

- `vk-turn-proxy`: https://github.com/cacggghp/vk-turn-proxy
- `WireGuard Android tunnel library`: https://github.com/WireGuard/wireguard-android
- `turnbridge`: https://github.com/nullcstring/turnbridge

## Релизы

Готовые APK публикуются в разделе `Releases`: https://github.com/StoneFree2011/TurnFree/releases
