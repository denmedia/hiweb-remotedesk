# HiWeb RemoteDesk

Android-клиент удалённого рабочего стола (RDP) для планшета Samsung Tab S9 Ultra с чехлом-клавиатурой
и тачпадом, плюс агент для Windows (HiWeb Agent). Пользователь общается по-русски.

> **Этот файл поддерживается автоматически (требование пользователя).** В конце любой задачи, которая
> меняет структуру проекта, добавляет/удаляет/переименовывает файлы или компоненты, меняет команды сборки,
> протоколы, соглашения или правила работы с устройствами, — обнови соответствующий раздел здесь в том же
> ходе, не дожидаясь просьбы, и коротко скажи об этом в ответе. Пиши только устойчивые факты; статус проверок
> и история — в памяти Claude, не здесь. Мелкие правки внутри файлов обновления не требуют.

## Устройство

- **`README.md`** — презентация приложения для читателя (что умеет, как начать), внизу краткая
  техническая документация. Держать в согласии с кодом: возможности, порты, команды сборки. Глубокие
  детали — по-прежнему здесь и в `windows-agent/PROTOCOL.md`.
- **RDP-клиент написан с нуля на Kotlin**, без FreeRDP/NDK (готовой сборки FreeRDP для Android нет).
  Всё в `app/src/main/java/com/hiweb/hiwebremotedesk/rdp/`:
  - `RdpClient.kt` — X.224 → TLS → CredSSP/NTLM (`CredSsp.kt`, `Ntlm.kt`) → MCS → возможности → графика;
    статические каналы и их нарезка на фрагменты по 1600 байт.
  - Каналы: `ClipboardChannel` (cliprdr, текст и файлы), `DriveChannel` (rdpdr: диски, несколько
    устройств, `attach/detach`), `SoundChannel` (rdpsnd), `DynamicChannel` (drdynvc; фрагментация
    DATA_FIRST, `DvcHandler`), `MouseCursorChannel` (DVC курсора), `DisplayControlChannel` (RDPEDISP:
    смена размера стола без переподключения, только вместе с GFX — сервер отвечает ResetGraphics),
    `GfxChannel` + `Zgfx` + `RfxProgressive` (RDPEGFX, нужен GNOME Remote Desktop).
  - Байтовые структуры — `Bytes.kt` (`ByteWriter`/`ByteReader`). Раскладки PDU брались из исходников FreeRDP.
- **Экран сеанса** — `RemoteActivity.kt` (Compose-оболочка, панель инструментов, баннеры, жизненный цикл)
  и `RemoteView.kt` (отрисовка, касания). Экран сеанса поворачивается (`fullUser`, учитывает блокировку
  поворота): в книжной ориентации стол 1920 по высоте. При повороте с GFX — `RdpClient.resizeDesktop`,
  без GFX (Windows) — переподключение с новым размером (сеанс Windows сохраняется; события старого клиента
  отсекаются по `sessionSeq`). Windows без GFX по DisplayControl меняет размер у себя, но не сообщает его клиенту. Ввод: `TouchpadEngine` (захват указателя), `TouchpadGestures`,
  `TouchscreenGestures`, `ScrollFling`, `KeyCaptureService` (служба спец. возможностей для Win/Alt+Tab).
- **Данные** — `data/`: `Device` + `DeviceRepository` (настройки каждого ПК, пароли через `SecretBox`),
  `Network.kt` (определение ОС), `LinuxSsh.kt` (питание Ubuntu по SSH), SAF-диск.
- **UI списка устройств** — `ui/` (`HomeScreen`, `Dialogs`, `HomeViewModel`).
- **HiWeb Agent** (Windows): исходники `windows-agent/` (C# 5), протокол `windows-agent/PROTOCOL.md`.
  Связь — DVC `HiWeb::Agent` (JSON). Android-сторона — `agent/`: `AgentLink` (канал), `AgentInstaller`
  (установка через временный диск `\\tsclient\HiWeb` + Win+R), `PcNotifications`, `SwitchListenerService`
  и `SwitchCommand` (UDP 47853: «switch» — переключиться на планшет, «where» — агент ищет планшет; HMAC).
  `SessionService` держит сеанс в свёрнутом приложении.
  Иконка агента — ноутбук: `windows-agent/app.ico` (иконка exe) и `laptop.png` (вшит ресурсом
  `HiWebAgent.laptop.png`); значок в трее (`TrayIcons` в `Support.cs`) — серый силуэт ноутбука под тему
  панели задач (`SystemUsesLightTheme`: на тёмной светлый, на светлой тёмный; перерисовка по
  `UserPreferenceChanged`), экран и тачпад вырезаны, на экране — батарейка с зарядом планшета (серый контур,
  цветная заливка по уровню, молния при зарядке); без планшета силуэт полупрозрачный. Если меняется картинка,
  пересчитать в `TrayIcons` долю белого экрана (`SCREEN_*`) и проверить пороги вырезания в `Mask`.
- **Связь агента с планшетом без RDP**: агент (`LanLink.cs`) сам подключается к планшету по TCP 47853
  (исходящее соединение — брандмауэр Windows не мешает), рукопожатие и подписанные строки — `SecureLines`
  (C# и Kotlin). На планшете сервер — `AgentHub` (запускает `SwitchListenerService`), он публикует
  `presence` для экрана списка ПК: карточка ПК с агентом на связи показывает кнопки питания
  (`AgentPower`: выключить, перезагрузить, сон, гибернация — только доступные по `Power.cs`/GetPwrCapabilities).
  Без агента остаются прежние кнопки питания через сеанс RDP (`PowerKeys`) или SSH для Ubuntu.
  Превью ПК без сеанса: агент (`ScreenShot.cs`) раз в 15 мин шлёт по LAN `screen` — JPEG экрана, зашифрованный
  `SecureLines.Encrypt` (AES); `AgentHub` расшифровывает и кладёт в `ThumbnailStore.saveJpeg`.
- **Состояние ПК и пробуждение**: агент (`PcStatus.cs`) шлёт `status` (блокировка, кто в сеансе, простой, уход в
  сон/гибернацию/выключение/перезагрузку/выход, MAC для WoL) и по LAN, и по DVC. Планшет хранит последнее
  сообщение (`data/PcStateStore.kt`), состояние карточки выводит `PcStatusRules` (`data/PcStatus.kt`) из него,
  связи с агентом и проверки порта RDP. Подключение к недоступному ПК с известным MAC (`Device.wakeMac`) сначала
  будит его (`data/WakeOnLan.kt`: UDP 9 и 7 на 255.255.255.255, широковещательные адреса подсетей и адрес ПК),
  затем ждёт порт RDP (`RemoteActivity.connectWaking`).
- **Сенсорная клавиатура Windows**: планшет шлёт агенту `keyboard` (`physical` — по `Configuration.keyboard`/
  `hardKeyboardHidden` — учитывает и чехол, и Bluetooth; не по списку устройств: `sec_touchpad` чехла виден и без него); без клавиатуры агент
  (`TouchKeyboard.cs`: ловушка мыши + UI Automation + `ITipInvocation`) показывает TabTip при щелчке в поле ввода
  и шлёт её прямоугольник (`tkb`); долгое касание внутри него на планшете — автоповтор клавиши, а не правая кнопка.

## Сборка и тесты

- JDK нет в PATH: `export JAVA_HOME="/d/Program Files/Android/Android Studio/jbr"` перед `./gradlew.bat`.
- Юнит-тесты и APK: `./gradlew.bat --console=plain -q :app:testDebugUnitTest :app:assembleDebug`.
  В JVM-тестах `android.util.Log` и `org.json` не работают: каналы получают логгер параметром (`log = {}`).
- Агент: `windows-agent\build.cmd` (компилятор C# из .NET Framework 4.8, есть в Windows) → кладёт
  `HiWebAgent.exe` и `version.txt` в `app/src/main/assets/agent/`. Чтобы приложение предложило обновление,
  увеличить `windows-agent/VERSION`, пересобрать агент, затем APK. Только синтаксис C# 5 (без `$""`, `?.`, `=>`-членов).
  Ссылки на UIAutomationClient/Types берутся из `Framework64\v4.0.30319\WPF`.
- Репозиторий: https://github.com/denmedia/hiweb-remotedesk (публичный, ветка `main`). Релиз: поднять
  `versionCode`/`versionName`, `:app:assembleRelease`, APK `HiWebRemoteDesk-<версия>.apk` → `gh release create v<версия>`.
  Подпись release — путь к файлу с паролями ключа в `local.properties` (`signing.properties=…`, файл в `D:/MY/keys`,
  ключ общий с IP Cam) или `keystore.properties` в корне; пароли не копировать и не коммитить. Без файла release
  неподписан. Release-подпись отличается от debug: поверх debug-сборки на планшете не ставится. R8 выключен.
- Python не установлен; для скриптов — node. Большие heredoc в Bash ломаются: скрипт писать файлом.

## Планшет и adb — осторожно

- adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`, планшет по беспроводному adb.
- В приложении сохранён **настоящий ПК пользователя**: при открытом сеансе любой ввод через adb уходит на ПК.
  Перед `adb input` и перед `adb install -r` проверять `adb shell dumpsys window | grep mCurrentFocus`;
  если приложение на экране — не трогать, спросить пользователя.
- Не запускать `connectedAndroidTest` (удаляет приложение и сохранённые ПК). Ставить `adb install -r -t`.
- `am force-stop` выключает службу перехвата клавиш (`install -r` её сохраняет).
- Не вводить пароль пользователя самому; учётка Windows блокируется после 10 ошибок за 10 минут.
- **На этом ПК стоит настоящий HiWeb Agent пользователя** (привязан к планшету). Тестовый запуск exe из
  другой папки переустанавливает его; не трогать `HKCU\Software\HiWebAgent\Tablet` и не удалять агент
  без спроса. Команды питания на этом ПК не выполнять (только проверять доступность).

## Соглашения

- Комментарии, KDoc и строки интерфейса — по-русски; плотность комментариев как в соседнем коде
  (KDoc у классов и неочевидных функций, короткие «почему» в коде).
- Новые зависимости не добавлять без нужды; протоколы реализуются вручную.
- В отчётах честно разделять «проверено на устройстве/ПК» и «только юнит-тесты». Многое из протоколов
  проверено лишь на синтетических PDU — актуальный статус в памяти Claude (`MEMORY.md`), а не здесь.
