# Исходники нативных компонентов

В каталоге сохранены неизменённые upstream-архивы:

- `xray-src.tar.gz`: XTLS/Xray-core, tag v26.11.9.
- `libxray-src.tar.gz`: XTLS/libXray, tag v26.11.9, API v3.
- `hev-src.tar.xz`: полный release-source HEV 2.19.3 с third-part.
- `libxray-go.mod`: upstream manifest для сверки версий Go-зависимостей.
- `go-modules/`: исходные ZIP 60 зависимостей из официального proxy.golang.org;
  версии и SHA-256 — в `go-source-manifest.json`. Лицензии извлечены в assets/licenses.

Готовые официальные Android AAR находятся в `app/libs` и зафиксированы SHA-256 в
`dependencies.sha256`. Они не требуют установки Go/NDK для обычной сборки APK.

Для собственной пересборки libXray распакуйте архив, установите Go версии из go.mod,
Android NDK и выполните официальный `python build/main.py android`. Скрипт upstream
рекомендует именно этот путь; для воспроизводимого build также фиксируйте gomobile
из go.mod через LIBXRAY_GOMOBILE_VERSION. Для HEV используйте исходники release и
`ndk-build` согласно upstream README; сохраняйте JNI-класс `hev.htproxy.TProxyService`.
Phantom не изменяет исходники этих библиотек. Не загружайте два независимо собранных
Go runtime в один процесс.

GeoIP/GeoSite извлечены из официального Xray Windows release 26.11.9, их точные байты
зафиксированы SHA-256. Источник генератора: https://github.com/XTLS/Xray-rules-dat.
Лицензия датасета и исходников компонентов сохранена в assets/licenses.

Оригинальные источники:

- https://github.com/XTLS/Xray-core/releases/tag/v26.11.9
- https://github.com/XTLS/libXray/releases/tag/v26.11.9
- https://github.com/heiher/hev-socks5-tunnel/releases/tag/2.19.3
