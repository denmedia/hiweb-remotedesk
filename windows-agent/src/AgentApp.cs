using System;
using System.Collections.Concurrent;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Threading;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;
using Windows.UI.Notifications.Management;

namespace HiWebAgent
{
    /// <summary>
    /// Агент в трее. Пока к сеансу подключён планшет (приложение HiWeb RemoteDesk), держит с ним канал RDP:
    /// показывает заряд планшета значком и пересылает уведомления Windows, когда приложение на планшете свёрнуто.
    /// Протокол сообщений описан в PROTOCOL.md.
    /// </summary>
    sealed class AgentApp : ApplicationContext
    {
        /// <summary>Попытки открыть канал: часто сразу после подключения сеанса, потом редко.</summary>
        const int FAST_RETRY_MS = 3000;
        const int SLOW_RETRY_MS = 30000;
        const int FAST_ATTEMPTS = 10;
        const int SCREEN_INTERVAL_MS = 15 * 60 * 1000;

        readonly SynchronizationContext ui;
        readonly NotifyIcon tray = new NotifyIcon();
        readonly ContextMenuStrip menu = new ContextMenuStrip();
        readonly ToolStripMenuItem switchItem = new ToolStripMenuItem("Переключиться на Планшет");
        readonly ToolStripMenuItem devicesItem = new ToolStripMenuItem("Устройства…");
        readonly ToolStripMenuItem statusItem = new ToolStripMenuItem();
        readonly ToolStripMenuItem forwardItem = new ToolStripMenuItem("Пересылать уведомления на планшет");
        readonly ToolStripMenuItem accessItem = new ToolStripMenuItem("Разрешить доступ к уведомлениям…");
        readonly ToolStripMenuItem checkItem = new ToolStripMenuItem("Проверить пересылку уведомлений…");
        NotificationCheckForm checkForm;
        readonly ToolStripMenuItem touchItem = new ToolStripMenuItem("Сенсорная клавиатура, если у планшета нет клавиатуры");
        readonly System.Windows.Forms.Timer connectTimer = new System.Windows.Forms.Timer();
        readonly System.Windows.Forms.Timer statusTimer = new System.Windows.Forms.Timer();
        readonly ToolStripMenuItem screenItem = new ToolStripMenuItem("Обновлять превью ПК на планшете (снимок экрана раз в 15 мин)");
        readonly System.Windows.Forms.Timer screenTimer = new System.Windows.Forms.Timer();
        readonly System.Windows.Forms.Timer screenSoon = new System.Windows.Forms.Timer();
        readonly ToolStripMenuItem guardItem = new ToolStripMenuItem("Защита подключений…");
        readonly ToolStripMenuItem guardWatchItem = new ToolStripMenuItem("Следить за подключениями по RDP и предупреждать о подозрительных");
        readonly ConnectionGuard guard;
        GuardForm guardForm;
        // Последнее состояние защиты для связи по локальной сети: читается из её потоков, поэтому готовое сообщение.
        volatile Dictionary<string, object> guardState;
        // Щелчок по уведомлению открывает окно защиты, только если показано уведомление об угрозе.
        bool balloonIsThreat;
        string lastThreatId;
        bool exiting;
        volatile bool screenBusy;
        readonly PcStatus pcStatus = new PcStatus();
        string lastStatusKey;
        readonly NotificationWatcher watcher = new NotificationWatcher();
        readonly TouchKeyboard touchKeyboard = new TouchKeyboard();
        readonly CursorWatcher cursorWatcher = new CursorWatcher();
        readonly JavaScriptSerializer json = new JavaScriptSerializer();
        readonly SessionEvents sessionEvents;
        readonly RegisteredWaitHandle exitWait;
        readonly EventWaitHandle exitEvent;
        readonly BlockingCollection<Tuple<RdpChannel, string>> outbox = new BlockingCollection<Tuple<RdpChannel, string>>();

        RdpChannel channel;
        readonly LanLink lan;
        bool switching;
        bool connecting;
        int failedAttempts;
        Icon currentIcon;
        // Устройство, с которым открыт канал RDP (ключ в списке устройств); null — канала нет.
        string currentDeviceKey;
        DevicesForm devicesForm;

        // Состояние планшета, пока канал открыт.
        string tabletName;
        bool tabletForeground = true;
        // Старые версии приложения не сообщают о клавиатуре: считаем, что она есть.
        bool tabletKeyboard = true;
        // Режим сенсорной клавиатуры Windows по кнопке в приложении (поле kbmode сообщения keyboard): "auto" — по полю ввода
        // и только без физической клавиатуры (так было всегда, его же ждут старые приложения), "show" — открыта и остаётся,
        // "hide" — скрыта.
        string tabletKbMode = "auto";
        int? battery;
        bool charging;

        public AgentApp(bool justInstalled)
        {
            WindowsFormsSynchronizationContext.AutoInstall = true;
            if (SynchronizationContext.Current == null) SynchronizationContext.SetSynchronizationContext(new WindowsFormsSynchronizationContext());
            ui = SynchronizationContext.Current;
            // Тема окон и меню (светлая/тёмная по настройке Windows) — до создания любых окон.
            Theme.Init();
            Ui.StyleMenu(menu);

            statusItem.Enabled = false;
            forwardItem.CheckOnClick = true;
            forwardItem.Checked = ReadSetting("ForwardNotifications", 1) != 0;
            forwardItem.CheckedChanged += delegate { WriteSetting("ForwardNotifications", forwardItem.Checked ? 1 : 0); };
            accessItem.Click += delegate { OpenNotificationSettings(); };
            checkItem.Click += delegate { ShowNotificationCheck(); };
            touchItem.CheckOnClick = true;
            touchItem.Checked = ReadSetting("TouchKeyboard", 1) != 0;
            touchItem.CheckedChanged += delegate { WriteSetting("TouchKeyboard", touchItem.Checked ? 1 : 0); UpdateTouchKeyboard(); };
            screenItem.CheckOnClick = true;
            screenItem.Checked = ReadSetting("ScreenPreview", 1) != 0;
            screenItem.CheckedChanged += delegate
            {
                WriteSetting("ScreenPreview", screenItem.Checked ? 1 : 0);
                if (screenItem.Checked) RequestScreen(1000);
            };
            guardItem.Click += delegate { ShowGuard(null); };
            guardWatchItem.CheckOnClick = true;
            guardWatchItem.Checked = ReadSetting("GuardRdp", 1) != 0;
            guardWatchItem.CheckedChanged += delegate
            {
                WriteSetting("GuardRdp", guardWatchItem.Checked ? 1 : 0);
                if (guardWatchItem.Checked) guard.Start();
                else guard.Stop();
            };
            var versionItem = new ToolStripMenuItem(AgentInfo.Product + " " + AgentInfo.Version);
            versionItem.Enabled = false;
            var uninstallItem = new ToolStripMenuItem("Удалить " + AgentInfo.Product + "…");
            uninstallItem.Click += delegate { UninstallFromMenu(); };
            var exitItem = new ToolStripMenuItem("Закрыть до следующего входа в Windows");
            exitItem.Click += delegate { ExitThread(); };
            switchItem.Font = new Font(switchItem.Font, FontStyle.Bold);
            switchItem.Click += delegate { SwitchToCurrent(); };
            devicesItem.Click += delegate { ShowDevices(); };
            menu.Items.AddRange(new ToolStripItem[] { switchItem, devicesItem, guardItem, new ToolStripSeparator(), statusItem, forwardItem, accessItem, checkItem, touchItem, screenItem, guardWatchItem, new ToolStripSeparator(), versionItem, uninstallItem, exitItem });
            menu.Opening += delegate
            {
                accessItem.Visible = watcher.Status != UserNotificationListenerAccessStatus.Allowed;
                FillSwitchMenu();
            };

            tray.ContextMenuStrip = menu;
            // Щелчок левой кнопкой тоже открывает меню: главное действие — переключение на планшет.
            tray.MouseUp += (s, e) =>
            {
                if (e.Button != MouseButtons.Left) return;
                var show = typeof(NotifyIcon).GetMethod("ShowContextMenu", System.Reflection.BindingFlags.Instance | System.Reflection.BindingFlags.NonPublic);
                if (show != null) show.Invoke(tray, null);
            };
            tray.Visible = true;
            UpdateTray();
            // Значок перекрашивается под тему панели задач: тема могла смениться.
            SystemEvents.UserPreferenceChanged += OnUserPreferenceChanged;

            // Установщик новой версии просит этот экземпляр завершиться через именованное событие.
            exitEvent = new EventWaitHandle(false, EventResetMode.AutoReset, Installer.ExitEvent);
            exitWait = ThreadPool.RegisterWaitForSingleObject(exitEvent, delegate { ui.Post(delegate { ExitThread(); }, null); }, null, -1, true);

            sessionEvents = new SessionEvents(OnSessionChanged, OnPowerEvent);
            var sender = new Thread(SendLoop);
            sender.IsBackground = true;
            sender.Name = "channel-send";
            sender.Start();

            var access = watcher.RequestAccess();
            Log.Write("notification access: " + access);
            watcher.NewNotification += info => ui.Post(delegate { Forward(info); }, null);
            watcher.Start();

            touchKeyboard.PaneChanged += rect => ui.Post(delegate { SendKeyboardPane(rect); }, null);
            cursorWatcher.Changed += msg => Send(msg);

            lan = new LanLink(() => PairedDevices.All(), key => PairedDevices.Get(key), d => PairedDevices.Save(d), pcStatus.Snapshot);
            lan.Changed += delegate
            {
                ui.Post(delegate
                {
                    UpdateTray();
                    if (devicesForm != null) devicesForm.RefreshList();
                    // Свежий кадр сразу после подключения: иначе превью ждало бы до 15 минут.
                    if (lan.Connected().Length > 0) RequestScreen(3000);
                }, null);
            };
            lan.Greeting = delegate
            {
                var list = new List<Dictionary<string, object>>();
                Dictionary<string, object> state = guardState;
                if (state != null) list.Add(state);
                return list;
            };
            lan.GuardCommand = (msg, reply) => ui.Post(delegate { OnGuardCommand(msg, reply); }, null);
            lan.Start();

            guard = new ConnectionGuard(ui);
            guard.Updated += OnGuardUpdated;
            guardState = guard.StateMessage();
            tray.BalloonTipClicked += delegate { if (balloonIsThreat) ShowGuard(lastThreatId); };
            if (guardWatchItem.Checked) guard.Start();
            UpdateGuardItem();

            screenTimer.Tick += delegate { SendScreen(); };
            screenTimer.Interval = SCREEN_INTERVAL_MS;
            screenTimer.Start();
            screenSoon.Tick += delegate { screenSoon.Stop(); SendScreen(); };

            connectTimer.Tick += delegate { TryConnect(); };
            connectTimer.Interval = 1000;
            connectTimer.Start();

            // Простой за ПК меняется без событий: раз в 10 секунд проверяем, не изменилось ли состояние для планшета.
            statusTimer.Tick += delegate { PushStatus(false); };
            statusTimer.Interval = 10000;
            statusTimer.Start();

            if (justInstalled)
            {
                balloonIsThreat = false;
                tray.ShowBalloonTip(5000, AgentInfo.Product, "Агент установлен и будет запускаться вместе с Windows.", ToolTipIcon.Info);
            }
        }

        // ------------------------------------------------------------------ канал

        void TryConnect()
        {
            if (channel != null || connecting) return;
            if (!SystemInformation.TerminalServerSession)
            {
                // Сеанс на локальной консоли: планшета нет, проверяем снова чуть позже.
                connectTimer.Interval = FAST_RETRY_MS;
                failedAttempts = 0;
                return;
            }
            connecting = true;
            ThreadPool.QueueUserWorkItem(delegate
            {
                RdpChannel opened = null;
                try { opened = RdpChannel.TryOpen(); }
                catch (Exception e) { Log.Write("open: " + e.Message); }
                ui.Post(delegate { OnOpenResult(opened); }, null);
            });
        }

        void OnOpenResult(RdpChannel opened)
        {
            connecting = false;
            if (opened == null)
            {
                failedAttempts++;
                connectTimer.Interval = failedAttempts < FAST_ATTEMPTS ? FAST_RETRY_MS : SLOW_RETRY_MS;
                return;
            }
            failedAttempts = 0;
            channel = opened;
            tabletForeground = true;
            tabletKeyboard = true;
            tabletKbMode = "auto";
            opened.MessageReceived += text => ui.Post(delegate { OnMessage(opened, text); }, null);
            opened.Closed += () => ui.Post(delegate { OnClosed(opened); }, null);
            opened.Start();
            Log.Write("channel opened");
            var hello = new Dictionary<string, object>();
            hello["t"] = "hello";
            hello["proto"] = AgentInfo.Protocol;
            hello["ver"] = AgentInfo.Version;
            hello["host"] = Environment.MachineName;
            hello["user"] = Environment.UserName;
            hello["notifAccess"] = watcher.Status.ToString();
            Send(hello);
            guard.TabletOpened();
            Send(guardState);
            pcStatus.TabletSession = true;
            PushStatus(true);
            UpdateTray();
        }

        void OnClosed(RdpChannel closed)
        {
            if (channel != closed) return;
            channel = null;
            tabletName = null;
            currentDeviceKey = null;
            battery = null;
            tabletForeground = true;
            tabletKeyboard = true;
            tabletKbMode = "auto";
            connectTimer.Interval = FAST_RETRY_MS;
            Log.Write("channel closed");
            pcStatus.TabletSession = false;
            PushStatus(false);
            UpdateTray();
            UpdateTouchKeyboard();
            UpdateCursor();
        }

        void OnMessage(RdpChannel from, string text)
        {
            if (channel != from) return;
            Dictionary<string, object> msg;
            try { msg = json.Deserialize<Dictionary<string, object>>(text); }
            catch (Exception) { Log.Write("bad message"); return; }
            if (msg == null) return;
            switch (Str(msg, "t"))
            {
                case "hello":
                    tabletName = Str(msg, "device");
                    // Идентификатор устройства в приложении: отличает планшет от смартфона и от прежних версий.
                    string key = Str(msg, "id");
                    if (!string.IsNullOrEmpty(key)) currentDeviceKey = PairedDevice.SafeName(key);
                    break;
                case "state":
                    tabletForeground = Bool(msg, "fg", true);
                    break;
                case "keyboard":
                    tabletKeyboard = Bool(msg, "physical", true);
                    tabletKbMode = Str(msg, "kbmode");
                    if (tabletKbMode != "show" && tabletKbMode != "hide") tabletKbMode = "auto";
                    break;
                case "pair":
                    SavePairing(msg);
                    break;
                case "guard":
                    OnGuardCommand(msg, reply => Send(reply));
                    break;
                case "notif_ack":
                    long ackId = msg.ContainsKey("id") && msg["id"] != null ? Convert.ToInt64(msg["id"]) : -1;
                    bool posted = Bool(msg, "posted", false);
                    Log.Write("notif: планшет " + (posted ? "показал уведомление" : "не показал уведомление: " + Str(msg, "reason")));
                    if (checkForm != null) checkForm.OnAck(ackId, posted, Str(msg, "reason"));
                    break;
                case "battery":
                    object level;
                    if (msg.TryGetValue("level", out level) && level is int) battery = Math.Max(0, Math.Min(100, (int)level));
                    charging = Bool(msg, "charging", false);
                    break;
            }
            UpdateTray();
            UpdateTouchKeyboard();
            UpdateCursor();
        }

        /// <summary>Форму курсора шлём, пока планшет показывает этот сеанс.</summary>
        void UpdateCursor()
        {
            cursorWatcher.Active = channel != null && tabletForeground;
        }

        /// <summary>Сенсорная клавиатура нужна, пока планшет без клавиатуры показывает этот сеанс.</summary>
        void UpdateTouchKeyboard()
        {
            // «Показана» не зависит ни от физической клавиатуры, ни от пункта меню агента: так выбрал владелец на планшете.
            touchKeyboard.Mode = tabletKbMode;
            bool wanted = tabletKbMode == "show" || (tabletKbMode == "auto" && !tabletKeyboard && touchItem.Checked);
            touchKeyboard.Active = channel != null && tabletForeground && wanted;
        }

        /// <summary>Где сенсорная клавиатура: планшет повторяет нажатие клавиши при долгом касании внутри неё.</summary>
        void SendKeyboardPane(int[] rect)
        {
            var msg = new Dictionary<string, object>();
            msg["t"] = "tkb";
            msg["visible"] = rect != null;
            if (rect != null)
            {
                msg["left"] = rect[0];
                msg["top"] = rect[1];
                msg["right"] = rect[2];
                msg["bottom"] = rect[3];
            }
            Send(msg);
        }

        /// <summary>Сообщения уходят по очереди из одного потока: запись в канал может ждать клиента.</summary>
        void Send(Dictionary<string, object> msg)
        {
            RdpChannel c = channel;
            if (c == null) return;
            outbox.Add(Tuple.Create(c, json.Serialize(msg)));
        }

        void SendLoop()
        {
            foreach (Tuple<RdpChannel, string> item in outbox.GetConsumingEnumerable())
            {
                if (!item.Item1.Send(item.Item2)) item.Item1.Close();
            }
        }

        void OnSessionChanged(int reason)
        {
            // Подключение по RDP или разблокировка: планшет мог только что появиться — пробуем сразу.
            if (reason == SessionEvents.WTS_REMOTE_CONNECT || reason == SessionEvents.WTS_SESSION_UNLOCK || reason == SessionEvents.WTS_SESSION_LOGON)
            {
                failedAttempts = 0;
                connectTimer.Interval = 1500;
            }
            else if (reason == SessionEvents.WTS_REMOTE_DISCONNECT && channel != null)
            {
                channel.Close();
            }
            if (reason == SessionEvents.WTS_SESSION_LOCK) pcStatus.ScreenLocked = true;
            else if (reason == SessionEvents.WTS_SESSION_UNLOCK || reason == SessionEvents.WTS_SESSION_LOGON) pcStatus.ScreenLocked = false;
            if (reason == SessionEvents.WTS_REMOTE_DISCONNECT || reason == SessionEvents.WTS_CONSOLE_DISCONNECT) pcStatus.Disconnected = true;
            else if (reason == SessionEvents.WTS_REMOTE_CONNECT || reason == SessionEvents.WTS_CONSOLE_CONNECT) pcStatus.Disconnected = false;
            PushStatus(false);
            // После разблокировки на экране снова рабочий стол: превью обновляем, когда он отрисуется.
            if (reason == SessionEvents.WTS_SESSION_UNLOCK || reason == SessionEvents.WTS_SESSION_LOGON) RequestScreen(10000);
        }

        // ------------------------------------------------------------------ превью ПК на планшете

        /// <summary>Снимок через [delayMs]; повторный запрос переносит уже назначенный.</summary>
        void RequestScreen(int delayMs)
        {
            screenSoon.Stop();
            screenSoon.Interval = delayMs;
            screenSoon.Start();
        }

        /// <summary>
        /// Снимает экран и шлёт планшету по локальной сети. Экран блокировки, отключённый сеанс и уход в сон не
        /// снимаем — на планшете остаётся последний нормальный кадр.
        /// </summary>
        void SendScreen()
        {
            if (!screenItem.Checked || lan.Connected().Length == 0 || screenBusy || !pcStatus.DesktopVisible) return;
            screenBusy = true;
            ThreadPool.QueueUserWorkItem(delegate
            {
                try
                {
                    Size size;
                    byte[] jpeg = ScreenShot.Capture(out size);
                    if (jpeg == null) Log.Write("screen: nothing to capture");
                    else if (lan.SendScreen(jpeg, size.Width, size.Height)) Log.Write("screen: sent " + jpeg.Length + " bytes");
                }
                catch (Exception e)
                {
                    // Безопасный рабочий стол (UAC, Ctrl+Alt+Del) не даёт себя снять.
                    Log.Write("screen: " + e.Message);
                }
                finally
                {
                    screenBusy = false;
                }
            });
        }

        // ------------------------------------------------------------------ состояние ПК для планшета

        /// <summary>
        /// Windows засыпает, выключается или пользователь выходит: планшет узнаёт об этом сейчас, пока сеть
        /// ещё работает, — иначе он увидел бы только пропавшую связь и не отличил бы сон от выключения.
        /// </summary>
        void OnPowerEvent(string kind)
        {
            string requested = Power.Requested;
            switch (kind)
            {
                case SessionEvents.POWER_SUSPEND:
                    pcStatus.Leaving = requested == Power.Hibernate ? PcStatus.Hibernate : PcStatus.Sleep;
                    break;
                case SessionEvents.POWER_SHUTDOWN:
                    pcStatus.Leaving = requested == Power.Restart ? PcStatus.Restart : PcStatus.Shutdown;
                    break;
                case SessionEvents.POWER_LOGOFF:
                    pcStatus.Leaving = PcStatus.Logoff;
                    break;
                default:
                    // Пробуждение или отменённое выключение.
                    pcStatus.Leaving = null;
                    break;
            }
            Log.Write("power event " + kind);
            Dictionary<string, object> snapshot = pcStatus.Snapshot();
            lastStatusKey = PcStatus.Key(snapshot);
            Send(snapshot);
            lan.SendStatus(snapshot);
        }

        /// <summary>Шлёт состояние по каналу RDP и по локальной сети, если оно изменилось (или [force]).</summary>
        void PushStatus(bool force)
        {
            Dictionary<string, object> snapshot = pcStatus.Snapshot();
            string key = PcStatus.Key(snapshot);
            if (!force && key == lastStatusKey) return;
            lastStatusKey = key;
            Send(snapshot);
            ThreadPool.QueueUserWorkItem(delegate { lan.SendStatus(snapshot); });
        }

        // ------------------------------------------------------------------ защита подключений

        /// <summary>Защита что-то заметила или изменила: сообщаем устройствам, обновляем окно и, если нужно, предупреждаем.</summary>
        void OnGuardUpdated(GuardUpdate u)
        {
            guardState = guard.StateMessage();
            if (u.State) Broadcast(guardState);
            if (u.Entries.Count > 0) Broadcast(guard.EntriesMessage(u.Entries));
            if (u.Threats.Count > 0) Broadcast(guard.ThreatsMessage(u.Threats));
            foreach (Threat t in u.Announce)
            {
                lastThreatId = t.Id;
                balloonIsThreat = true;
                string text = GuardText.ThreatText(t) + "\nЩёлкните, чтобы заблокировать или оставить.";
                tray.ShowBalloonTip(20000, GuardText.ThreatTitle(t), text.Length > 250 ? text.Substring(0, 250) : text, ToolTipIcon.Warning);
            }
            UpdateGuardItem();
            if (guardForm != null) guardForm.RefreshAll();
        }

        /// <summary>Сообщение по каналу RDP и по локальной сети всем устройствам, которые на связи.</summary>
        void Broadcast(Dictionary<string, object> msg)
        {
            Send(msg);
            ThreadPool.QueueUserWorkItem(delegate { lan.Send(msg); });
        }

        void UpdateGuardItem()
        {
            int open = guard.OpenThreats;
            guardItem.Text = open > 0 ? "Защита подключений… (угроз: " + open + ")" : "Защита подключений…";
            guardItem.Font = new Font(guardItem.Font, open > 0 ? FontStyle.Bold : FontStyle.Regular);
        }

        void ShowGuard(string threatId)
        {
            if (guardForm == null)
            {
                guardForm = new GuardForm(guard);
                guardForm.FormClosed += delegate { guardForm = null; };
                guardForm.Show();
            }
            else
            {
                guardForm.RefreshAll();
                if (guardForm.WindowState == FormWindowState.Minimized) guardForm.WindowState = FormWindowState.Normal;
            }
            guardForm.Activate();
            if (threatId != null) guardForm.ShowThreats(threatId);
        }

        /// <summary>Команда устройства (сообщение guard): sync, block, unblock, trust, dismiss. Выполняется в потоке интерфейса.</summary>
        void OnGuardCommand(Dictionary<string, object> msg, Action<Dictionary<string, object>> reply)
        {
            string action = Str(msg, "do");
            switch (action)
            {
                case "sync":
                    long since = 0;
                    object v;
                    if (msg.TryGetValue("since", out v) && v != null) since = Convert.ToInt64(v);
                    foreach (Dictionary<string, object> m in guard.SyncMessages(since)) reply(m);
                    break;
                case "block":
                    string cidr = Str(msg, "cidr");
                    guard.Block(cidr, Bool(msg, "enableFw", false), error => reply(GuardResult(action, cidr, error)));
                    break;
                case "unblock":
                    string unblock = Str(msg, "cidr");
                    guard.Unblock(unblock, error => reply(GuardResult(action, unblock, error)));
                    break;
                case "trust":
                    guard.Trust(Str(msg, "ip"));
                    break;
                case "dismiss":
                    guard.Dismiss(Str(msg, "id"));
                    break;
            }
        }

        Dictionary<string, object> GuardResult(string action, string cidr, string error)
        {
            var result = new Dictionary<string, object>();
            result["t"] = "guard_result";
            result["do"] = action;
            result["cidr"] = cidr ?? "";
            result["ok"] = error == null;
            result["error"] = error ?? "";
            result["fw"] = guard.FirewallOn;
            return result;
        }

        // ------------------------------------------------------------------ устройства и переключение

        /// <summary>
        /// Устройство подключилось по каналу RDP и прислало свои данные. Оно заводится в списке агента только
        /// так — подключением к этому ПК; повторное подключение обновляет адреса, порт и ключ.
        /// </summary>
        void SavePairing(Dictionary<string, object> msg)
        {
            try
            {
                string pcId = Str(msg, "pc");
                string model = Str(msg, "device") ?? tabletName;
                string kind = Str(msg, "kind");
                string id = Str(msg, "id");
                object port;
                int portValue = msg.TryGetValue("port", out port) && port is int ? (int)port : 0;
                var ips = new List<string>();
                object raw;
                if (msg.TryGetValue("ips", out raw) && raw is System.Collections.IEnumerable)
                {
                    foreach (object ip in (System.Collections.IEnumerable)raw) if (ip != null) ips.Add(ip.ToString());
                }
                string secret = Str(msg, "secret");
                if (string.IsNullOrEmpty(pcId) || portValue <= 0 || string.IsNullOrEmpty(secret)) return;
                byte[] key = Convert.FromBase64String(secret);
                // Идентификатор есть только в новых версиях приложения: без него устройство различается по модели.
                PairedDevice d = PairedDevices.Upsert(id, pcId, model, kind, portValue, ips.ToArray(), key);
                if (string.IsNullOrEmpty(id)) currentDeviceKey = d.Key;
                else currentDeviceKey = d.Key;
                if (devicesForm != null) devicesForm.RefreshList();
                UpdateTray();
            }
            catch (Exception e)
            {
                Log.Write("pair: " + e.Message);
            }
        }

        /// <summary>
        /// Заново собирает меню переключения: одно устройство — пункт «Переключиться на …», несколько — подменю
        /// со списком (в сети — первым, подключённое по RDP — с галочкой).
        /// </summary>
        void FillSwitchMenu()
        {
            switchItem.DropDownItems.Clear();
            PairedDevice[] devices = PairedDevices.All();
            string cid = channel == null ? null : currentDeviceKey;
            PairedDevice current = cid == null ? null : PairedDevices.Get(cid);
            if (channel != null && current == null)
            {
                // Старое приложение не сообщает идентификатор: подпись — модель из hello.
                switchItem.Text = "Переключиться на " + (string.IsNullOrEmpty(tabletName) ? "Планшет" : tabletName);
                switchItem.Enabled = !switching;
                switchItem.ToolTipText = null;
                return;
            }
            if (current != null)
            {
                switchItem.Text = "Переключиться на " + current.Title;
                switchItem.Enabled = !switching;
                switchItem.ToolTipText = null;
                return;
            }
            switchItem.Text = "Переключиться на Планшет";
            if (devices.Length == 0)
            {
                switchItem.Enabled = false;
                switchItem.ToolTipText = "Сначала подключитесь к этому ПК с планшета или смартфона и установите агент";
                return;
            }
            switchItem.Enabled = !switching;
            switchItem.ToolTipText = null;
            foreach (PairedDevice d in OrderForMenu(devices))
            {
                PairedDevice device = d;
                var item = new ToolStripMenuItem(device.Title);
                item.Checked = device.Key == currentDeviceKey;
                bool online = lan.IsConnected(device.Key);
                item.Image = null;
                item.Text = device.Title + (online ? "  ·  в сети" : "");
                item.Font = new Font(switchItem.Font, online ? FontStyle.Bold : FontStyle.Regular);
                item.Click += delegate { SwitchToDevice(device.Key); };
                switchItem.DropDownItems.Add(item);
            }
        }

        /// <summary>Устройства на связи — сверху, дальше по алфавиту.</summary>
        PairedDevice[] OrderForMenu(PairedDevice[] devices)
        {
            var online = new List<PairedDevice>();
            var offline = new List<PairedDevice>();
            foreach (PairedDevice d in devices)
            {
                if (lan.IsConnected(d.Key)) online.Add(d);
                else offline.Add(d);
            }
            online.AddRange(offline);
            return online.ToArray();
        }

        /// <summary>Окно со списком устройств: переключение, переименование, удаление.</summary>
        void ShowDevices()
        {
            if (devicesForm == null)
            {
                devicesForm = new DevicesForm(
                    key => lan.IsConnected(key),
                    key => SwitchToDevice(key),
                    delegate { UpdateTray(); });
                devicesForm.FormClosed += delegate { devicesForm = null; };
                devicesForm.Show();
            }
            else
            {
                devicesForm.RefreshList();
                if (devicesForm.WindowState == FormWindowState.Minimized) devicesForm.WindowState = FormWindowState.Normal;
                devicesForm.Activate();
            }
        }

        /// <summary>Пункт меню без выбора устройства: подключённому по RDP — activate, иначе текущее из списка.</summary>
        void SwitchToCurrent()
        {
            string key = channel != null ? currentDeviceKey : null;
            if (key == null)
            {
                // Одно устройство — прямо на него; несколько — пусть пользователь выберет в подменю.
                PairedDevice[] devices = PairedDevices.All();
                if (devices.Length == 1) key = devices[0].Key;
                else if (devices.Length == 0) return;
                else
                {
                    switchItem.ShowDropDown();
                    return;
                }
            }
            SwitchToDevice(key);
        }

        /// <summary>
        /// Открыть сеанс этого ПК на выбранном устройстве: подключённому — по каналу RDP, иначе — командой по
        /// локальной сети. Когда устройство подтвердило, мониторы ПК гаснут.
        /// </summary>
        void SwitchToDevice(string key)
        {
            if (switching) return;
            PairedDevice p = PairedDevices.Get(key);
            if (p == null) return;
            if (channel != null && key == currentDeviceKey)
            {
                var msg = new Dictionary<string, object>();
                msg["t"] = "activate";
                Send(msg);
                TurnOffDisplaySoon();
                return;
            }
            switching = true;
            tray.Text = AgentInfo.Product + "\nВызов устройства…";
            ThreadPool.QueueUserWorkItem(delegate
            {
                bool ok = false;
                try { ok = TabletSwitch.Call(p); }
                catch (Exception e) { Log.Write("switch: " + e.Message); }
                ui.Post(delegate
                {
                    switching = false;
                    UpdateTray();
                    if (ok)
                    {
                        TurnOffDisplaySoon();
                    }
                    else
                    {
                        balloonIsThreat = false;
                        tray.ShowBalloonTip(8000, AgentInfo.Product,
                            "«" + p.Title + "» не ответило. Проверьте, что устройство включено, в той же сети Wi-Fi, а приложению HiWeb разрешена работа в фоне.",
                            ToolTipIcon.Warning);
                    }
                }, null);
            });
        }

        /// <summary>Пауза, чтобы отпускание кнопки мыши после щелчка по меню не разбудило монитор сразу же.</summary>
        void TurnOffDisplaySoon()
        {
            var t = new System.Windows.Forms.Timer();
            t.Interval = 1500;
            t.Tick += delegate
            {
                t.Stop();
                t.Dispose();
                Display.TurnOff();
            };
            t.Start();
        }

        // ------------------------------------------------------------------ уведомления

        /// <summary>Почему уведомление не уйдёт на планшет; null — уйдёт.</summary>
        string ForwardSkipReason()
        {
            if (!forwardItem.Checked) return "пересылка выключена в меню агента";
            if (channel == null) return "нет канала сеанса RDP с планшетом (уведомления идут только по нему)";
            if (tabletForeground) return "приложение на планшете на экране";
            return null;
        }

        void Forward(ToastInfo info)
        {
            string skip = ForwardSkipReason();
            // Тестовое уведомление из проверки: планшет должен ответить, показал ли он его.
            bool probe = checkForm != null && checkForm.OnToast(info, skip);
            // В журнал — только приложение: заголовки и тексты уведомлений личные.
            Log.Write("notif: " + (info.App ?? info.AppId) + " — " + (skip == null ? "отправлено планшету" : "не отправлено: " + skip));
            if (skip != null) return;
            var msg = new Dictionary<string, object>();
            msg["t"] = "notif";
            if (probe) msg["ack"] = true;
            msg["id"] = (long)info.Id;
            msg["app"] = info.App ?? "";
            msg["title"] = info.Title ?? "";
            msg["text"] = info.Text ?? "";
            msg["time"] = info.TimeMs;
            // Иконка приложения для карточки на планшете; без неё планшет покажет только значок RemoteDesk.
            string icon = AppIcons.PngBase64(info.AppId);
            if (icon != null) msg["icon"] = icon;
            Send(msg);
        }

        /// <summary>Окно проверки пересылки: что уведомление Windows доходит до планшета, а если нет — где обрывается.</summary>
        void ShowNotificationCheck()
        {
            if (checkForm == null)
            {
                checkForm = new NotificationCheckForm(ReadCheckEnv);
                checkForm.FormClosed += delegate { checkForm = null; };
                checkForm.Show();
            }
            else
            {
                if (checkForm.WindowState == FormWindowState.Minimized) checkForm.WindowState = FormWindowState.Normal;
                checkForm.Activate();
            }
        }

        CheckEnv ReadCheckEnv()
        {
            var env = new CheckEnv();
            env.Access = watcher.Status;
            env.PollAgeSec = watcher.PollAgeSec;
            env.PollError = watcher.LastError;
            env.ForwardOn = forwardItem.Checked;
            env.ChannelOpen = channel != null;
            env.TabletForeground = tabletForeground;
            env.TabletName = tabletName;
            return env;
        }

        static void OpenNotificationSettings()
        {
            try { Process.Start("ms-settings:privacy-notifications"); }
            catch (Exception e) { Log.Write("settings: " + e.Message); }
        }

        // ------------------------------------------------------------------ трей

        void UpdateTray()
        {
            // Отложенные вызовы (связь с устройством, меню) могут прийти уже после закрытия значка.
            if (exiting) return;
            // Первый вызов — из конструктора, до создания связи по локальной сети.
            PairedDevice[] online = lan == null ? new PairedDevice[0] : lan.Connected();
            PairedDevice current = channel == null || currentDeviceKey == null ? null : PairedDevices.Get(currentDeviceKey);
            // Подпись: имя устройства, если оно известно; иначе — как раньше, «Планшет».
            string currentName = current != null ? current.Title : (string.IsNullOrEmpty(tabletName) ? null : tabletName);
            string status;
            if (channel != null)
            {
                string who = currentName ?? "Устройство";
                if (battery == null) status = who + " подключено";
                else status = who + ": " + battery.Value + "%" + (charging ? ", заряжается" : "");
            }
            else if (online.Length == 1) status = online[0].Title + " в сети";
            else if (online.Length > 1) status = "Устройства в сети: " + online.Length;
            else
            {
                int total = PairedDevices.All().Length;
                status = total > 0 ? "Устройства не подключены (" + total + ")" : "Устройства не подключены";
            }
            statusItem.Text = status;
            string tip = AgentInfo.Product + "\n" + status;
            tray.Text = tip.Length > 63 ? tip.Substring(0, 63) : tip;

            // Значок активен, пока есть связь хотя бы с одним устройством.
            bool linked = channel != null || online.Length > 0;
            Icon icon = TrayIcons.Render(linked, channel == null ? null : battery, charging);
            tray.Icon = icon;
            if (currentIcon != null) currentIcon.Dispose();
            currentIcon = icon;
        }

        void OnUserPreferenceChanged(object sender, UserPreferenceChangedEventArgs e)
        {
            if (e.Category == UserPreferenceCategory.General) ui.Post(delegate { UpdateTray(); }, null);
        }

        void UninstallFromMenu()
        {
            var answer = ThemedBox.Show(
                "Удалить " + AgentInfo.Product + " с этого компьютера?\nУстановить заново можно кнопкой в приложении на планшете.",
                AgentInfo.Product, MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (answer != DialogResult.Yes) return;
            try
            {
                Installer.Uninstall(true);
            }
            catch (Exception e)
            {
                Log.Write("uninstall: " + e);
                ThemedBox.Show("Не удалось удалить: " + e.Message, AgentInfo.Product, MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
            ExitThread();
        }

        protected override void ExitThreadCore()
        {
            exiting = true;
            SystemEvents.UserPreferenceChanged -= OnUserPreferenceChanged;
            watcher.Stop();
            guard.Dispose();
            touchKeyboard.Dispose();
            cursorWatcher.Dispose();
            lan.Stop();
            connectTimer.Stop();
            statusTimer.Stop();
            screenTimer.Stop();
            screenSoon.Stop();
            if (channel != null) channel.Close();
            outbox.CompleteAdding();
            exitWait.Unregister(null);
            exitEvent.Close();
            sessionEvents.Dispose();
            tray.Visible = false;
            tray.Dispose();
            if (currentIcon != null) currentIcon.Dispose();
            base.ExitThreadCore();
        }

        // ------------------------------------------------------------------ вспомогательное

        static string Str(Dictionary<string, object> msg, string key)
        {
            object v;
            return msg.TryGetValue(key, out v) && v != null ? v.ToString() : null;
        }

        static bool Bool(Dictionary<string, object> msg, string key, bool fallback)
        {
            object v;
            return msg.TryGetValue(key, out v) && v is bool ? (bool)v : fallback;
        }

        static int ReadSetting(string name, int fallback)
        {
            using (RegistryKey k = Registry.CurrentUser.OpenSubKey(Installer.SettingsKey))
            {
                object v = k == null ? null : k.GetValue(name);
                return v is int ? (int)v : fallback;
            }
        }

        static void WriteSetting(string name, int value)
        {
            using (RegistryKey k = Registry.CurrentUser.CreateSubKey(Installer.SettingsKey))
            {
                k.SetValue(name, value, RegistryValueKind.DWord);
            }
        }
    }

    /// <summary>Скрытое окно для уведомлений о сеансе (подключение/отключение RDP, блокировка) и о питании.</summary>
    sealed class SessionEvents : NativeWindow, IDisposable
    {
        public const int WTS_CONSOLE_CONNECT = 0x1;
        public const int WTS_CONSOLE_DISCONNECT = 0x2;
        public const int WTS_REMOTE_CONNECT = 0x3;
        public const int WTS_REMOTE_DISCONNECT = 0x4;
        public const int WTS_SESSION_LOGON = 0x5;
        public const int WTS_SESSION_LOCK = 0x7;
        public const int WTS_SESSION_UNLOCK = 0x8;
        public const string POWER_SUSPEND = "suspend";
        public const string POWER_RESUME = "resume";
        public const string POWER_SHUTDOWN = "shutdown";
        public const string POWER_LOGOFF = "logoff";
        public const string POWER_CANCELLED = "cancelled";
        const int WM_WTSSESSION_CHANGE = 0x02B1;
        const int WM_POWERBROADCAST = 0x0218;
        const int WM_QUERYENDSESSION = 0x0011;
        const int WM_ENDSESSION = 0x0016;
        const int PBT_APMSUSPEND = 0x4;
        const int PBT_APMRESUMESUSPEND = 0x7;
        const int PBT_APMRESUMEAUTOMATIC = 0x12;
        const long ENDSESSION_LOGOFF = 0x80000000L;
        const int NOTIFY_FOR_THIS_SESSION = 0;

        [System.Runtime.InteropServices.DllImport("wtsapi32.dll")]
        static extern bool WTSRegisterSessionNotification(IntPtr hwnd, int flags);

        [System.Runtime.InteropServices.DllImport("wtsapi32.dll")]
        static extern bool WTSUnRegisterSessionNotification(IntPtr hwnd);

        readonly Action<int> onChange;
        readonly Action<string> onPower;

        public SessionEvents(Action<int> onChange, Action<string> onPower)
        {
            this.onChange = onChange;
            this.onPower = onPower;
            CreateHandle(new CreateParams());
            WTSRegisterSessionNotification(Handle, NOTIFY_FOR_THIS_SESSION);
        }

        protected override void WndProc(ref Message m)
        {
            if (m.Msg == WM_WTSSESSION_CHANGE) onChange(m.WParam.ToInt32());
            else if (m.Msg == WM_POWERBROADCAST)
            {
                int ev = m.WParam.ToInt32();
                if (ev == PBT_APMSUSPEND) onPower(POWER_SUSPEND);
                else if (ev == PBT_APMRESUMEAUTOMATIC || ev == PBT_APMRESUMESUSPEND) onPower(POWER_RESUME);
            }
            else if (m.Msg == WM_QUERYENDSESSION)
            {
                onPower((m.LParam.ToInt64() & ENDSESSION_LOGOFF) != 0 ? POWER_LOGOFF : POWER_SHUTDOWN);
            }
            else if (m.Msg == WM_ENDSESSION && m.WParam == IntPtr.Zero)
            {
                // Выключение или выход отменены (другая программа не дала закрыть себя).
                onPower(POWER_CANCELLED);
            }
            base.WndProc(ref m);
        }

        public void Dispose()
        {
            if (Handle == IntPtr.Zero) return;
            WTSUnRegisterSessionNotification(Handle);
            DestroyHandle();
        }
    }
}
