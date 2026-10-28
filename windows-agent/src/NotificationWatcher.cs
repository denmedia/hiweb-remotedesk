using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Windows.Foundation;
using Windows.UI.Notifications;
using Windows.UI.Notifications.Management;

namespace HiWebAgent
{
    sealed class ToastInfo
    {
        public uint Id;
        public string App;
        public string AppId;
        public string Title;
        public string Text;
        public long TimeMs;
    }

    /// <summary>
    /// Следит за центром уведомлений Windows (UserNotificationListener). Событие NotificationChanged обычному
    /// exe недоступно (нужен пакет MSIX), поэтому список опрашивается раз в секунду и сравнивается с прежним.
    /// Уведомления, бывшие на момент запуска, новыми не считаются.
    /// </summary>
    sealed class NotificationWatcher
    {
        const int POLL_MS = 1000;

        readonly UserNotificationListener listener = UserNotificationListener.Current;
        readonly HashSet<uint> seen = new HashSet<uint>();
        volatile bool stopped;
        bool baseline;
        // Для проверки пересылки: когда опрос в последний раз удался и чем кончился последний неудачный.
        int lastPollTick;
        volatile bool polled;
        volatile string lastError;

        /// <summary>Сколько секунд назад центр уведомлений опрашивался успешно; -1 — ещё ни разу.</summary>
        public double PollAgeSec
        {
            get { return polled ? unchecked(Environment.TickCount - lastPollTick) / 1000.0 : -1; }
        }

        /// <summary>Ошибка последнего опроса; null, если он удался.</summary>
        public string LastError
        {
            get { return lastError; }
        }

        /// <summary>Новое уведомление (кроме отфильтрованных). Вызывается из потока опроса.</summary>
        public event Action<ToastInfo> NewNotification;

        /// <summary>Состояние доступа; вызывать из потока интерфейса, где создан [listener].</summary>
        public UserNotificationListenerAccessStatus Status
        {
            get
            {
                try { return listener.GetAccessStatus(); }
                catch (Exception) { return UserNotificationListenerAccessStatus.Unspecified; }
            }
        }

        /// <summary>Запрашивает доступ (Windows может показать вопрос пользователю). Вызывать из потока интерфейса.</summary>
        public UserNotificationListenerAccessStatus RequestAccess()
        {
            try { return Wait(listener.RequestAccessAsync()); }
            catch (Exception e)
            {
                Log.Write("RequestAccess: " + e.GetBaseException().Message);
                return UserNotificationListenerAccessStatus.Unspecified;
            }
        }

        public void Start()
        {
            var t = new Thread(Loop);
            t.IsBackground = true;
            t.Name = "notifications";
            t.Start();
        }

        public void Stop()
        {
            stopped = true;
        }

        void Loop()
        {
            // Объект слушателя привязан к потоку, где он создан: из потока интерфейса (STA) его нельзя вызывать
            // здесь (RPC_E_WRONG_THREAD), поэтому опрос берёт свой.
            UserNotificationListener pollListener = UserNotificationListener.Current;
            while (!stopped)
            {
                try
                {
                    if (pollListener.GetAccessStatus() == UserNotificationListenerAccessStatus.Allowed)
                    {
                        Poll(pollListener);
                        if (!polled) Log.Write("notifications: опрос центра уведомлений идёт");
                        lastPollTick = Environment.TickCount;
                        polled = true;
                        lastError = null;
                    }
                }
                catch (Exception e)
                {
                    string message = e.GetBaseException().Message;
                    // Одна и та же ошибка каждую секунду засорила бы журнал.
                    if (message != lastError) Log.Write("poll: " + message);
                    lastError = message;
                }
                Thread.Sleep(POLL_MS);
            }
        }

        void Poll(UserNotificationListener source)
        {
            IReadOnlyList<UserNotification> list = Wait(source.GetNotificationsAsync(NotificationKinds.Toast));
            var current = new HashSet<uint>();
            var fresh = new List<UserNotification>();
            foreach (UserNotification n in list)
            {
                current.Add(n.Id);
                if (!seen.Contains(n.Id)) fresh.Add(n);
            }
            seen.Clear();
            seen.UnionWith(current);
            if (!baseline)
            {
                baseline = true;
                return;
            }
            var handler = NewNotification;
            if (handler == null) return;
            foreach (UserNotification n in fresh)
            {
                ToastInfo info = Describe(n);
                if (info == null) continue;
                if (IsFiltered(info)) Log.Write("notif: " + (info.App ?? info.AppId) + " — не отправлено: свои и Phone Link не пересылаются");
                else handler(info);
            }
        }

        /// <summary>
        /// Phone Link показывает на ПК уведомления самого планшета: пересылка их обратно зациклила бы уведомления.
        /// Собственные подсказки агента тоже не пересылаются.
        /// </summary>
        static bool IsFiltered(ToastInfo info)
        {
            string id = info.AppId ?? "";
            string app = info.App ?? "";
            if (id.StartsWith("Microsoft.YourPhone", StringComparison.OrdinalIgnoreCase)) return true;
            if (app.Equals("Phone Link", StringComparison.OrdinalIgnoreCase)) return true;
            if (app.Equals("Связь с телефоном", StringComparison.OrdinalIgnoreCase)) return true;
            if (id.IndexOf("HiWebAgent", StringComparison.OrdinalIgnoreCase) >= 0) return true;
            if (app.Equals(AgentInfo.Product, StringComparison.OrdinalIgnoreCase)) return true;
            return false;
        }

        static ToastInfo Describe(UserNotification n)
        {
            var info = new ToastInfo();
            info.Id = n.Id;
            try
            {
                info.AppId = n.AppInfo.AppUserModelId;
                info.App = n.AppInfo.DisplayInfo.DisplayName;
            }
            catch (Exception)
            {
                if (string.IsNullOrEmpty(info.App)) info.App = info.AppId;
            }
            try { info.TimeMs = n.CreationTime.ToUnixTimeMilliseconds(); } catch (Exception) { }
            try
            {
                NotificationBinding binding = n.Notification.Visual.GetBinding(KnownNotificationBindings.ToastGeneric);
                if (binding == null) return info;
                IReadOnlyList<AdaptiveNotificationText> texts = binding.GetTextElements();
                var body = new List<string>();
                for (int i = 0; i < texts.Count; i++)
                {
                    string s = texts[i].Text;
                    if (string.IsNullOrWhiteSpace(s)) continue;
                    if (info.Title == null) info.Title = s;
                    else body.Add(s);
                }
                info.Text = string.Join("\n", body);
            }
            catch (Exception e)
            {
                Log.Write("toast text: " + e.GetBaseException().Message);
            }
            return info;
        }

        /// <summary>Ожидание операции WinRT без расширений System.Runtime.WindowsRuntime (их нет у компилятора .NET Framework).</summary>
        static T Wait<T>(IAsyncOperation<T> op)
        {
            var done = new TaskCompletionSource<T>();
            op.Completed = (o, status) =>
            {
                if (status == AsyncStatus.Completed) done.TrySetResult(o.GetResults());
                else if (status == AsyncStatus.Error) done.TrySetException(o.ErrorCode);
                else done.TrySetException(new OperationCanceledException());
            };
            return done.Task.Result;
        }
    }
}
