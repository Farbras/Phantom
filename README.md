# Phantom для Android

Личный VPN-клиент Kotlin/Jetpack Compose с Xray-core 26.11.9 и настоящим путём
`VpnService → HEV TUN/SOCKS → Xray`. Чёрно-фиолетовый интерфейс, адаптивная маска,
белый матричный дождь на Canvas. Приложение не содержит встроенных серверов или URL
подписки. Фактически выполненные проверки и аппаратные ограничения — в
[docs/VERIFICATION.md](docs/VERIFICATION.md).

## Установка 

1. Перенесите `dist/Phantom-1.0.0-arm64.apk` на телефон и откройте файл. Разрешите
   установку из выбранного источника. APK подписан отдельным личным ключом Phantom.
2. Добавьте свою подписку через «+». Для получения **полных составных JSON** этого
   провайдера после добавления откройте меню подписки → Редактировать →
   «Запрашивать формат Happ JSON» → Сохранить → Обновить. По умолчанию используется
   собственный User-Agent Phantom, который может получать другой список серверов.
3. Выберите сервер и нажмите кнопку. Разрешите VPN и уведомления в системных диалогах.
   «Туннель работает» и «Интернет через VPN подтверждён» — разные состояния.
4. Для постоянной защиты откройте Настройки → Туннель и DNS → настройки Android,
   включите постоянную VPN и блокировку соединений без VPN. В настройках питания
   vivo разрешите Phantom работу в фоне. Эти аппаратные сценарии требуют проверки.
5. Добавьте плитку Phantom через системный редактор быстрых настроек.

## Что реализовано

- URL/буфер/QR/файл/ручные URI и полные JSON; несколько подписок, редактирование,
  обновление, закрепление, удаление, раскрытие, метаданные и экспорт JSON.
- Поиск, избранное, фильтр, три порядка сортировки, измеренные задержки и ошибки.
- HTTP GET/HEAD, Double/Keepalive в отдельном процессе, прямые TCP/ICMP тесты;
  автоматическая проверка интернета через реальный TUN и счётчики адаптера.
- Исходные составные DNS/routing/balancers; пользовательские domain/IP профили
  Proxy/Direct/Block; выбор приложений; DNS и IPv6; локальная сеть.
- SOCKS5/HTTP, порты и авторизация, LAN-доступ с паролем; совместимые Mux и TLS
  fragmentation; reconnect, foreground-уведомление, плитка, системный always-on.
- WorkManager, интервалы и тайм-ауты, запусковые действия, User-Agent и UUID установки.
- Реальные счётчики/скорости/длительность, безопасный журнал, оформление и анимации,
  справка, лицензии и подтверждение сброса настроек.

Полная таблица сопоставления с 14 фото Happ: [FEATURE-MATRIX](docs/FEATURE-MATRIX.md).
Закрытые криптоссылки/HWID Happ, история DNS и точная семантика iOS On Demand не
воспроизводятся. Это явно описано в UI и документации; неработающих переключателей нет.

## Сборка из исходников

Нужны JDK 17, Android SDK platform 36 и build-tools 36.0.0. Android Studio необязательна.
Укажите SDK через `ANDROID_HOME` или `local.properties` (`sdk.dir=C\:/path/to/Android/Sdk`).
Нативные AAR уже включены; SHA-256 и исходники upstream сохранены в проекте.

```powershell
$env:JAVA_HOME = 'C:\path\to\jdk-17'
$env:ANDROID_HOME = 'C:\path\to\Android\Sdk'
python scripts/check_dependencies.py
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`. Для личной release-сборки
создайте keystore и `signing.local.properties` по примеру. В текущем рабочем каталоге
личный ключ сохранён в `.tools/phantom-signing.p12`, параметры — в игнорируемом
`signing.local.properties`. Сохраните их для установки обновлений с той же подписью.
**Они исключены из исходного ZIP и Git.** Без этих файлов Gradle создаст unsigned release.

```powershell
.\gradlew.bat :app:assembleRelease -PphantomAbi=arm64-v8a
# APK: app/build/outputs/apk/release/app-release.apk
adb install -r app/build/outputs/apk/release/app-release.apk
```

Стандартная сборка включает arm64-v8a и x86_64; свойство phantomAbi позволяет
собрать отдельный ARM64 APK для телефона. Проект имеет minSdk 29, compile/targetSdk 36;
Android 17 работает в режиме совместимости target 36 и требует аппаратной проверки.

Версии: AGP 8.13.2, Gradle 8.13, Kotlin/Compose compiler 2.2.21, Compose BOM
2025.10.01, Activity 1.11.0, Lifecycle 2.9.4, WorkManager 2.10.5, Coroutines 1.10.2,
OkHttp 4.12.0, ZXing Embedded 4.3.0. Транзитивные версии зафиксированы gradle.lockfile;
нативные: libXray/Xray-core 26.11.9, HEV 2.19.3.

## Проверка на Android

```powershell
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest :trafficprobe:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb install -r -t trafficprobe/build/outputs/apk/debug/trafficprobe-debug.apk
adb shell appops set app.phantom ACTIVATE_VPN allow
adb shell am instrument -w app.phantom.test/androidx.test.runner.AndroidJUnitRunner
```

Два теста проверяют native URI и Keystore. Третий — opt-in: ему нужен файл
`files/audit.json` в приватном каталоге debug-приложения. Он не включён в поставку.
Тест запускает реальный VPN, HTTPS из **отдельного UID** trafficprobe, reconnect,
отдельный core проверки и Block-правило. Без private fixture он помечается skipped.
Приложение trafficprobe — только тестовый стенд, не часть устанавливаемого Phantom.

## Документация

- [Архитектура, DNS и lifecycle](docs/ARCHITECTURE.md)
- [Анализ подписки без секретов](docs/SUBSCRIPTION-COMPATIBILITY.md)
- [Функции и ограничения](docs/FEATURE-MATRIX.md)
- [Результаты проверки и список тестов телефона](docs/VERIFICATION.md)
- [Исходники и сборка нативных библиотек](native-sources/README.md)
