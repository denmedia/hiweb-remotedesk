using System;
using System.Drawing;
using System.Threading.Tasks;
using System.Windows.Forms;
using Windows.Data.Xml.Dom;
using Windows.UI.Notifications;
using Windows.UI.Notifications.Management;

namespace HiWebAgent
{
    /// <summary>Состояние агента, которое читает проверка пересылки уведомлений.</summary>
    sealed class CheckEnv
    {
        public UserNotificationListenerAccessStatus Access;
        /// <summary>Сколько секунд назад удался опрос центра уведомлений; -1 — ни разу.</summary>
        public double PollAgeSec;
        public string PollError;
        public bool ForwardOn;
        public bool ChannelOpen;
        public bool TabletForeground;
        public string TabletName;
    }

    /// <summary>
    /// Настоящее уведомление Windows для проверки пересылки. Показывается от имени Windows PowerShell: его
    /// AppUserModelId есть в каждой Windows, поэтому ярлык с идентификатором для агента не нужен, а агент такое
    /// уведомление не отфильтрует (свои он не пересылает) — оно проходит тот же путь, что и любое чужое.
    /// </summary>
    static class ToastProbe
    {
        const string AppId = "{1AC14E77-02E7-4E5D-B744-2EB1AE5198B7}\\WindowsPowerShell\\v1.0\\powershell.exe";

        public static ToastNotification Show(string title, string text)
        {
            var doc = new XmlDocument();
            doc.LoadXml("<toast><visual><binding template=\"ToastGeneric\"><text>" + System.Security.SecurityElement.Escape(title) +
                "</text><text>" + System.Security.SecurityElement.Escape(text) + "</text></binding></visual></toast>");
            var toast = new ToastNotification(doc);
            ToastNotificationManager.CreateToastNotifier(AppId).Show(toast);
            return toast;
        }

        /// <summary>Убирает тестовое уведомление с экрана и из центра уведомлений.</summary>
        public static void Hide(ToastNotification toast)
        {
            try { ToastNotificationManager.CreateToastNotifier(AppId).Hide(toast); }
            catch (Exception e) { Log.Write("toast hide: " + e.GetBaseException().Message); }
        }
    }

    /// <summary>
    /// Окно «Проверить пересылку уведомлений»: по шагам показывает, где обрывается путь уведомления
    /// Windows → агент → канал сеанса → планшет. Последние шаги — настоящий тестовый тост Windows, который проходит
    /// путь целиком, и ответ планшета (`notif_ack`), показал ли он его.
    /// </summary>
    sealed class NotificationCheckForm : Form
    {
        enum Mark { Pending, Running, Ok, Fail, Skip }

        const int ACCESS = 0, POLL = 1, FORWARD = 2, CHANNEL = 3, BACKGROUND = 4, SEEN = 5, SENT = 6, SHOWN = 7;
        const int WAIT_BACKGROUND_MS = 30000;
        const int SEEN_MS = 10000;
        const int ACK_MS = 8000;

        static readonly string[] Titles =
        {
            "Windows разрешила агенту читать уведомления",
            "Агент следит за центром уведомлений",
            "Пересылка включена в меню агента",
            "Планшет подключён к сеансу (канал HiWeb)",
            "Приложение HiWeb на планшете свёрнуто",
            "Тестовое уведомление Windows замечено агентом",
            "Уведомление отправлено на планшет",
            "Планшет показал уведомление",
        };

        readonly Func<CheckEnv> readEnv;
        readonly Label[] glyphs = new Label[Titles.Length];
        readonly Label[] texts = new Label[Titles.Length];
        readonly Label verdict = new Label();
        readonly Button again = new Button();
        bool running;
        string problem;

        // Что известно о тестовом уведомлении; заполняется из потока интерфейса вызовами OnToast и OnAck.
        string token;
        ToastInfo seen;
        string sentSkip;
        bool acked;
        bool ackPosted;
        string ackReason;

        public NotificationCheckForm(Func<CheckEnv> readEnv)
        {
            this.readEnv = readEnv;
            Text = "Проверка уведомлений — " + AgentInfo.Product;
            StartPosition = FormStartPosition.CenterScreen;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            MinimizeBox = false;
            MaximizeBox = false;
            ShowIcon = false;
            ClientSize = new Size(580, 660);
            Font = SystemFonts.MessageBoxFont;

            var intro = new Label();
            intro.Text = "Агент покажет тестовое уведомление Windows и проследит, дойдёт ли оно до планшета. " +
                "Для этого приложение HiWeb на планшете должно быть открытым сеансом и свёрнутым.";
            intro.Dock = DockStyle.Top;
            intro.Height = 52;
            intro.Padding = new Padding(10, 8, 10, 0);

            var table = new TableLayoutPanel();
            table.Dock = DockStyle.Fill;
            table.AutoScroll = true;
            table.Padding = new Padding(10, 6, 10, 6);
            table.ColumnCount = 2;
            table.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute, 28));
            table.ColumnStyles.Add(new ColumnStyle(SizeType.Percent, 100));
            for (int i = 0; i < Titles.Length; i++)
            {
                var glyph = new Label();
                glyph.AutoSize = true;
                glyph.Font = new Font(Font, FontStyle.Bold);
                glyph.Margin = new Padding(0, 0, 0, 8);
                var text = new Label();
                text.AutoSize = true;
                text.MaximumSize = new Size(520, 0);
                text.Margin = new Padding(0, 0, 0, 8);
                glyphs[i] = glyph;
                texts[i] = text;
                table.RowStyles.Add(new RowStyle(SizeType.AutoSize));
                table.Controls.Add(glyph, 0, i);
                table.Controls.Add(text, 1, i);
            }

            verdict.Dock = DockStyle.Bottom;
            verdict.Height = 64;
            verdict.Padding = new Padding(10, 6, 10, 0);
            verdict.Font = new Font(Font, FontStyle.Bold);

            var buttons = new FlowLayoutPanel();
            buttons.Dock = DockStyle.Bottom;
            buttons.FlowDirection = FlowDirection.RightToLeft;
            buttons.AutoSize = true;
            buttons.Padding = new Padding(8);
            var close = new Button();
            close.Text = "Закрыть";
            close.AutoSize = true;
            close.Click += delegate { Close(); };
            again.Text = "Проверить ещё раз";
            again.AutoSize = true;
            again.Click += delegate { Run(); };
            buttons.Controls.Add(close);
            buttons.Controls.Add(again);
            CancelButton = close;

            Controls.Add(table);
            Controls.Add(intro);
            Controls.Add(verdict);
            Controls.Add(buttons);
            Shown += delegate { Run(); };
        }

        /// <summary>
        /// Агент заметил новое уведомление Windows. Возвращает true, если это тестовое, и тогда агент просит планшет
        /// ответить. [skipReason] — почему агент не будет его пересылать (null — будет).
        /// </summary>
        public bool OnToast(ToastInfo info, string skipReason)
        {
            if (token == null || seen != null || info.Title == null || info.Title.IndexOf(token, StringComparison.Ordinal) < 0) return false;
            seen = info;
            sentSkip = skipReason;
            return true;
        }

        /// <summary>Ответ планшета на тестовое уведомление.</summary>
        public void OnAck(long id, bool posted, string reason)
        {
            if (seen == null || id != seen.Id) return;
            acked = true;
            ackPosted = posted;
            ackReason = reason;
        }

        public void Rerun()
        {
            Run();
        }

        async void Run()
        {
            if (running) return;
            running = true;
            again.Enabled = false;
            problem = null;
            verdict.Text = "";
            for (int i = 0; i < Titles.Length; i++) Set(i, Mark.Pending, null);
            try
            {
                await Check();
            }
            catch (Exception e)
            {
                Log.Write("check: " + e);
                problem = "Проверка прервана: " + e.GetBaseException().Message;
            }
            running = false;
            if (IsDisposed) return;
            again.Enabled = true;
            bool good = problem == null;
            verdict.ForeColor = good ? Color.FromArgb(0, 128, 0) : Color.FromArgb(192, 0, 0);
            verdict.Text = good ? "Всё работает: уведомления Windows доходят до планшета." : "Причина: " + problem;
        }

        async Task Check()
        {
            token = new Random().Next(1000, 9999).ToString();
            seen = null;
            sentSkip = null;
            acked = false;
            ackPosted = false;
            ackReason = null;
            CheckEnv env = readEnv();
            // Сразу после запуска агента первый опрос ещё не прошёл: даём ему несколько секунд.
            for (int i = 0; i < 12 && env.Access == UserNotificationListenerAccessStatus.Allowed && !(env.PollAgeSec >= 0 && env.PollAgeSec < 5); i++)
            {
                await Task.Delay(250);
                env = readEnv();
            }

            bool access = env.Access == UserNotificationListenerAccessStatus.Allowed;
            Result(ACCESS, access, "Разрешён",
                "Windows не даёт агенту читать уведомления (" + env.Access + "). Включите доступ: пункт «Разрешить доступ к уведомлениям…» в меню значка агента.");
            bool polling = access && env.PollAgeSec >= 0 && env.PollAgeSec < 5;
            if (access)
            {
                Result(POLL, polling, "Центр уведомлений опрашивается раз в секунду",
                    env.PollError != null ? "Опрос завершается ошибкой: " + env.PollError : "Опрос не идёт. Закройте агент через меню значка и запустите заново.");
            }
            else
            {
                Set(POLL, Mark.Skip, "не проверялось");
            }
            Result(FORWARD, env.ForwardOn, "Включена",
                "Пересылка выключена. Включите пункт «Пересылать уведомления на планшет» в меню значка агента.");
            Result(CHANNEL, env.ChannelOpen, env.TabletName == null ? "Канал открыт" : "Канал открыт: " + env.TabletName,
                "Канала нет. Уведомления Windows уходят на планшет только по каналу открытого сеанса RDP (по локальной сети без сеанса они не пересылаются). Откройте сеанс этого ПК на планшете, сверните приложение и повторите проверку.");

            if (!env.ChannelOpen)
            {
                Set(BACKGROUND, Mark.Skip, "не проверялось: нет сеанса");
            }
            else if (!env.TabletForeground)
            {
                Set(BACKGROUND, Mark.Ok, "Свёрнуто");
            }
            else
            {
                // Приложение на экране: настоящие уведомления в это время не пересылаются, даём время его свернуть.
                int left = WAIT_BACKGROUND_MS;
                while (left > 0 && !IsDisposed)
                {
                    Set(BACKGROUND, Mark.Running, "Сверните приложение HiWeb на планшете: осталось " + (left / 1000) + " с");
                    await Task.Delay(500);
                    left -= 500;
                    CheckEnv now = readEnv();
                    if (!now.ChannelOpen || !now.TabletForeground) break;
                }
                CheckEnv after = readEnv();
                Result(BACKGROUND, after.ChannelOpen && !after.TabletForeground, "Свёрнуто",
                    after.ChannelOpen
                        ? "Приложение осталось на экране планшета: пока оно там, уведомления не пересылаются (на экране и так виден ПК). Сверните его и повторите проверку."
                        : "Канал сеанса закрылся во время проверки.");
            }
            if (IsDisposed) return;

            if (!polling)
            {
                Set(SEEN, Mark.Skip, "не проверялось");
                Set(SENT, Mark.Skip, "не проверялось");
                Set(SHOWN, Mark.Skip, "не проверялось");
                return;
            }

            ToastNotification toast;
            try
            {
                toast = ToastProbe.Show("Проверка уведомлений HiWeb " + token, "Если вы видите это на планшете, пересылка работает.");
            }
            catch (Exception e)
            {
                Result(SEEN, false, null, "Windows не приняла тестовое уведомление: " + e.GetBaseException().Message);
                Set(SENT, Mark.Skip, "не проверялось");
                Set(SHOWN, Mark.Skip, "не проверялось");
                return;
            }
            try
            {
                Set(SEEN, Mark.Running, "Жду, пока уведомление появится в центре уведомлений…");
                for (int waited = 0; seen == null && waited < SEEN_MS && !IsDisposed; waited += 200) await Task.Delay(200);
                Result(SEEN, seen != null, "Замечено",
                    "За " + (SEEN_MS / 1000) + " с агент не увидел тестовое уведомление. Проверьте в параметрах Windows (Система → Уведомления), что уведомления включены, в том числе от Windows PowerShell, и не включён режим «Не беспокоить».");
                if (seen == null)
                {
                    Set(SENT, Mark.Skip, "не проверялось");
                    Set(SHOWN, Mark.Skip, "не проверялось");
                    return;
                }
                Result(SENT, sentSkip == null, "Отправлено по каналу сеанса", "Не отправлено: " + sentSkip + ".");
                if (sentSkip != null)
                {
                    Set(SHOWN, Mark.Skip, "не проверялось");
                    return;
                }
                Set(SHOWN, Mark.Running, "Жду ответа планшета…");
                for (int waited = 0; !acked && waited < ACK_MS && !IsDisposed; waited += 200) await Task.Delay(200);
                if (!acked)
                {
                    Result(SHOWN, false, null,
                        "Планшет не ответил за " + (ACK_MS / 1000) + " с. Возможно, канал оборвался или на планшете старая версия приложения (она не отвечает на проверку) — посмотрите, пришло ли уведомление.");
                }
                else
                {
                    Result(SHOWN, ackPosted, "Уведомление показано на планшете", AckText(ackReason));
                }
            }
            finally
            {
                ToastProbe.Hide(toast);
            }
        }

        /// <summary>Что значит причина, по которой планшет не показал уведомление.</summary>
        static string AckText(string reason)
        {
            switch (reason)
            {
                case "visible":
                    return "Приложение в этот момент было на экране планшета.";
                case "permission":
                    return "На планшете для HiWeb RemoteDesk запрещены уведомления: Настройки Android → Приложения → HiWeb RemoteDesk → Уведомления.";
                case "channel":
                    return "На планшете выключен канал «Уведомления с ПК»: Настройки Android → Приложения → HiWeb RemoteDesk → Уведомления → Уведомления с ПК.";
                default:
                    return "Планшет не показал уведомление (" + reason + ").";
            }
        }

        /// <summary>Отмечает шаг как пройденный или проваленный; первая неудача становится итоговой причиной.</summary>
        void Result(int step, bool ok, string okText, string failText)
        {
            Set(step, ok ? Mark.Ok : Mark.Fail, ok ? okText : failText);
            if (!ok && problem == null) problem = failText;
        }

        void Set(int step, Mark mark, string detail)
        {
            if (IsDisposed) return;
            Label glyph = glyphs[step];
            switch (mark)
            {
                case Mark.Ok:
                    glyph.Text = "✔";
                    glyph.ForeColor = Color.FromArgb(0, 128, 0);
                    break;
                case Mark.Fail:
                    glyph.Text = "✘";
                    glyph.ForeColor = Color.FromArgb(192, 0, 0);
                    break;
                case Mark.Running:
                    glyph.Text = "…";
                    glyph.ForeColor = Color.FromArgb(0, 90, 200);
                    break;
                case Mark.Skip:
                    glyph.Text = "–";
                    glyph.ForeColor = SystemColors.GrayText;
                    break;
                default:
                    glyph.Text = "";
                    break;
            }
            texts[step].ForeColor = mark == Mark.Skip || mark == Mark.Pending ? SystemColors.GrayText : SystemColors.ControlText;
            texts[step].Text = Titles[step] + (string.IsNullOrEmpty(detail) ? "" : "\n" + detail);
        }
    }
}
