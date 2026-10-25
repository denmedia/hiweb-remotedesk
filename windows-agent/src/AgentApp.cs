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
        readonly ToolStripMenuItem statusItem = new ToolStripMenuItem();
        readonly ToolStripMenuItem forwardItem = new ToolStripMenuItem("Пересылать уведомления на планшет");
        readonly ToolStripMenuItem accessItem = new ToolStripMenuItem("Разрешить доступ к уведомлениям…");
        readonly ToolStripMenuItem touchItem = new ToolStripMenuItem("Сенсорная клавиатура, если у планшета нет клавиатуры");
        readonly System.Windows.Forms.Timer connectTimer = new System.Windows.Forms.Timer();
        readonly System.Windows.Forms.Timer statusTimer = new System.Windows.Forms.Timer();
        readonly ToolStripMenuItem screenItem = new ToolStripMenuItem("Обновлять превью ПК на планшете (снимок экрана раз в 15 мин)");
        readonly System.Windows.Forms.Timer screenTimer = new System.Windows.Forms.Timer();
        readonly System.Windows.Forms.Timer screenSoon = new System.Windows.Forms.Timer();
        volatile bool screenBusy;
        readonly PcStatus pcStatus = new PcStatus();
        string lastStatusKey;
        readonly NotificationWatcher watcher = new NotificationWatcher();
        readonly TouchKeyboard touchKeyboard = new TouchKeyboard();
        readonly JavaScriptSerializer json = new JavaScriptSerializer();
        readonly SessionEvents sessionEvents;
        readonly RegisteredWaitHandle exitWait;
        readonly EventWaitHandle exitEvent;
        readonly BlockingCollection<Tuple<RdpChannel, string>> outbox = new BlockingCollection<Tuple<RdpChannel, string>>();

        RdpChannel channel;
        volatile TabletPairing pairing = TabletPairing.Load();
        readonly LanLink lan;
        bool lanConnected;
        bool switching;
        bool connecting;
        int failedAttempts;
        Icon currentIcon;

        // Состояние планшета, пока канал открыт.
        string tabletName;
        bool tabletForeground = true;
        // Старые версии приложения не сообщают о клавиатуре: считаем, что она есть.
        bool tabletKeyboard = true;
        int? battery;
        bool charging;

        public AgentApp(bool justInstalled)
        {
            WindowsFormsSynchronizationContext.AutoInstall = true;
            if (SynchronizationContext.Current == null) SynchronizationContext.SetSynchronizationContext(new WindowsFormsSynchronizationContext());
            ui = SynchronizationContext.Current;

            statusItem.Enabled = false;
            forwardItem.CheckOnClick = true;
            forwardItem.Checked = ReadSetting("ForwardNotifications", 1) != 0;
            forwardItem.CheckedChanged += delegate { WriteSetting("ForwardNotifications", forwardItem.Checked ? 1 : 0); };
            accessItem.Click += delegate { OpenNotificationSettings(); };
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
            var versionItem = new ToolStripMenuItem(AgentInfo.Product + " " + AgentInfo.Version);
            versionItem.Enabled = false;
            var uninstallItem = new ToolStripMenuItem("Удалить " + AgentInfo.Product + "…");
            uninstallItem.Click += delegate { UninstallFromMenu(); };
            var exitItem = new ToolStripMenuItem("Закрыть до следующего входа в Windows");
            exitItem.Click += delegate { ExitThread(); };
            switchItem.Font = new Font(switchItem.Font, FontStyle.Bold);
            switchItem.Click += delegate { SwitchToTablet(); };
            menu.Items.AddRange(new ToolStripItem[] { switchItem, new ToolStripSeparator(), statusItem, forwardItem, accessItem, touchItem, screenItem, new ToolStripSeparator(), versionItem, uninstallItem, exitItem });
            menu.Opening += delegate
            {
                accessItem.Visible = watcher.Status != UserNotificationListenerAccessStatus.Allowed;
                switchItem.Enabled = !switching && (channel != null || pairing != null);
                switchItem.ToolTipText = pairing == null && channel == null
                    ? "Сначала подключитесь к этому ПК с планшета и установите агент"
                    : null;
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

            lan = new LanLink(() => pairing, p => p.Save(), pcStatus.Snapshot);
            lan.ConnectedChanged += connected => ui.Post(delegate
            {
                lanConnected = connected;
                UpdateTray();
                // Свежий кадр сразу после подключения: иначе превью ждало бы до 15 минут.
                if (connected) RequestScreen(3000);
            }, null);
            lan.Start();

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
            pcStatus.TabletSession = true;
            PushStatus(true);
            UpdateTray();
        }

        void OnClosed(RdpChannel closed)
        {
            if (channel != closed) return;
            channel = null;
            tabletName = null;
            battery = null;
            tabletForeground = true;
            tabletKeyboard = true;
            connectTimer.Interval = FAST_RETRY_MS;
            Log.Write("channel closed");
            pcStatus.TabletSession = false;
            PushStatus(false);
            UpdateTray();
            UpdateTouchKeyboard();
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
                    break;
                case "state":
                    tabletForeground = Bool(msg, "fg", true);
                    break;
                case "keyboard":
                    tabletKeyboard = Bool(msg, "physical", true);
                    break;
                case "pair":
                    SavePairing(msg);
                    break;
                case "battery":
                    object level;
                    if (msg.TryGetValue("level", out level) && level is int) battery = Math.Max(0, Math.Min(100, (int)level));
                    charging = Bool(msg, "charging", false);
                    break;
            }
            UpdateTray();
            UpdateTouchKeyboard();
        }

        /// <summary>Сенсорная клавиатура нужна, пока планшет без клавиатуры показывает этот сеанс.</summary>
        void UpdateTouchKeyboard()
        {
            touchKeyboard.Active = channel != null && !tabletKeyboard && tabletForeground && touchItem.Checked;
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
            if (!screenItem.Checked || !lanConnected || screenBusy || !pcStatus.DesktopVisible) return;
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

        // ------------------------------------------------------------------ переключение на планшет

        void SavePairing(Dictionary<string, object> msg)
        {
            try
            {
                var p = new TabletPairing();
                p.DeviceId = Str(msg, "pc");
                p.Name = Str(msg, "device") ?? tabletName;
                object port;
                p.Port = msg.TryGetValue("port", out port) && port is int ? (int)port : 0;
                var ips = new List<string>();
                object raw;
                if (msg.TryGetValue("ips", out raw) && raw is System.Collections.IEnumerable)
                {
                    foreach (object ip in (System.Collections.IEnumerable)raw) if (ip != null) ips.Add(ip.ToString());
                }
                p.Addresses = ips.ToArray();
                string secret = Str(msg, "secret");
                if (string.IsNullOrEmpty(p.DeviceId) || p.Port <= 0 || string.IsNullOrEmpty(secret)) return;
                p.Secret = Convert.FromBase64String(secret);
                p.Save();
                pairing = p;
                Log.Write("paired with tablet " + p.Name + " port " + p.Port + " at " + string.Join(",", p.Addresses));
            }
            catch (Exception e)
            {
                Log.Write("pair: " + e.Message);
            }
        }

        /// <summary>
        /// Открыть сеанс этого ПК на планшете: подключённому планшету — по каналу RDP, иначе — командой по
        /// локальной сети. Когда планшет подтвердил, мониторы ПК гаснут.
        /// </summary>
        void SwitchToTablet()
        {
            if (switching) return;
            if (channel != null)
            {
                var msg = new Dictionary<string, object>();
                msg["t"] = "activate";
                Send(msg);
                TurnOffDisplaySoon();
                return;
            }
            TabletPairing p = pairing;
            if (p == null) return;
            switching = true;
            tray.Text = AgentInfo.Product + "\nВызов планшета…";
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
                        tray.ShowBalloonTip(8000, AgentInfo.Product,
                            "Планшет не ответил. Проверьте, что он включён, в той же сети Wi-Fi, а приложению HiWeb разрешена работа в фоне.",
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

        void Forward(ToastInfo info)
        {
            if (channel == null || tabletForeground || !forwardItem.Checked) return;
            var msg = new Dictionary<string, object>();
            msg["t"] = "notif";
            msg["id"] = (long)info.Id;
            msg["app"] = info.App ?? "";
            msg["title"] = info.Title ?? "";
            msg["text"] = info.Text ?? "";
            msg["time"] = info.TimeMs;
            Send(msg);
        }

        static void OpenNotificationSettings()
        {
            try { Process.Start("ms-settings:privacy-notifications"); }
            catch (Exception e) { Log.Write("settings: " + e.Message); }
        }

        // ------------------------------------------------------------------ трей

        void UpdateTray()
        {
            string status;
            if (channel == null && lanConnected) status = "Планшет в сети" + (pairing != null && !string.IsNullOrEmpty(pairing.Name) ? " (" + pairing.Name + ")" : "");
            else if (channel == null) status = "Планшет не подключён" + (pairing != null && !string.IsNullOrEmpty(pairing.Name) ? " (" + pairing.Name + ")" : "");
            else if (battery == null) status = "Планшет подключён";
            else status = "Планшет: " + battery.Value + "%" + (charging ? ", заряжается" : "");
            if (channel != null && !string.IsNullOrEmpty(tabletName)) status += " · " + tabletName;
            statusItem.Text = status;
            string tip = AgentInfo.Product + "\n" + status;
            tray.Text = tip.Length > 63 ? tip.Substring(0, 63) : tip;

            Icon icon = TrayIcons.Render(channel != null, battery, charging);
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
            var answer = MessageBox.Show(
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
                MessageBox.Show("Не удалось удалить: " + e.Message, AgentInfo.Product, MessageBoxButtons.OK, MessageBoxIcon.Error);
                return;
            }
            ExitThread();
        }

        protected override void ExitThreadCore()
        {
            SystemEvents.UserPreferenceChanged -= OnUserPreferenceChanged;
            watcher.Stop();
            touchKeyboard.Dispose();
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
