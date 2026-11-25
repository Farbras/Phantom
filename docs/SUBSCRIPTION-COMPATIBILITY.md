# Анализ личной подписки — без персональных значений

Проверка выполнена 9 ноября 2026 года. URL, UUID, пароли, ключи, адреса узлов,
точные значения счётчиков и срок действия намеренно не включены в этот документ.

| Запрос | Фактический ответ |
|---|---|
| User-Agent `Phantom/1.0 (Android)` | HTTP 200, text/plain, Base64 списка URI, 75 записей |
| User-Agent `Happ/5.5.0/android` | HTTP 200, application/json, массив 80 полных конфигураций |

URI: 49 VLESS, 5 Shadowsocks, 19 Trojan, 2 Hysteria2. По фактическим URI-параметрам:
46 TCP/default, 13 WebSocket, 11 XHTTP, 5 gRPC; 33 Reality, 35 TLS, 7 none.

JSON содержит 69 VLESS outbound, 23 Trojan, 4 Shadowsocks, 2 Hysteria, а также
служебные Freedom, DNS и Blackhole. Это число outbound, а не число серверов UI.
В массиве 9 конфигураций с `leastLoad`-балансировщиком и burstObservatory. Среди
транспортов реально присутствуют TCP, WS, gRPC, XHTTP и Hysteria, а также FinalMask.
Reality встречается в 35 outbound. Проекция одного outbound потеряла бы балансировку.

Phantom сохраняет полные JSON, включая DNS, routing, burstObservatory, настройки
транспортов, FinalMask и метаданные описания. Для URI использует конвертер libXray
26.11.9 отдельно для каждой строки: отклонённые элементы остаются видны с ошибкой.

Чтобы получить полный формат этого провайдера, в меню подписки выберите
«Редактировать → Запрашивать формат Happ JSON», сохраните и обновите. По умолчанию
Phantom использует собственный User-Agent; формат не меняется скрыто от пользователя.

## Метаданные и идентификация

Сервер передаёт Profile-Title, Announce, Subscription-Userinfo с полями upload,
download, total, expire, Profile-Update-Interval, Support-Url, Providerid,
Check-Url-Via-Proxy, Ping-Type, Proxy-Ping-Mode и дополнительные параметры других
клиентов. Название, описание, трафик и срок отображаются. Ссылки поддержки хранятся
в приватном каталоге; автоматическое открытие не выполняется.

Обновление и проверки настраивает пользователь. Управляющие команды провайдера не
применяются автоматически; наличие Providerid не означает реализацию платной панели
Happ. Поля других клиентов не превращаются в неработающие переключатели.

Оба проверенных ответа получены **без HWID-заголовков**. Это доказывает лишь отсутствие
обязательной проверки HWID в этих запросах. Не проверялись лимит устройств, привязка
аккаунта, будущие изменения провайдера и идентичность HWID Happ. Опциональный HWID
Phantom — случайный UUID установки с заголовками x-hwid/x-device-os/x-ver-os/
x-device-model. Его совместимость требует разрешения провайдера.

## Ограниченные и зашифрованные форматы

В проверенных ответах encrypt-tag отсутствует. Обычные HTTPS, URI, Base64 URI и
полный Xray JSON поддерживаются. Happ crypt/limited/HWID-обёртки отклоняются с
объяснением, нужен исходный URL. Наличие алгоритма AES-GCM в документации Happ не
предоставляет закрытый ключ провайдера. Ответ с encrypt-tag отклоняется до разбора.
Age/YAML/Mihomo и собственные схемы клиентов не заявлены как поддерживаемые.

## Что подтверждено

- Все 80 исходных JSON приняты `xray run -test` версии 26.11.9.
- Все 80 JSON **после ConfigBuilder Phantom** также приняты этой версией ядра.
- Сетевые результаты отдельных комбинаций протокол/транспорт сохранены в
  `host-network-results.json`; это тест на Windows через SOCKS, а не тест Android TUN.
- Названия узлов не использовались для определения протоколов и транспортов.

## Источники Happ

- [Управление настройками и метаданные](https://github.com/HappDev/happ_su/blob/main/dev-docs/app-management.md)
- [Маршрутизация и составные JSON](https://github.com/HappDev/happ_su/blob/main/dev-docs/routing.md)
- [HWID links](https://github.com/HappDev/happ_su/blob/main/dev-docs/hwid-links.md)
- [Шифрование содержимого](https://github.com/HappDev/happ_su/blob/main/dev-docs/encrypting-subscription-content.md)
