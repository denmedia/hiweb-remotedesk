using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;

namespace HiWebAgent
{
    /// <summary>Тексты о подключениях и угрозах — одни и те же в окне агента и в уведомлении Windows.</summary>
    static class GuardText
    {
        public static string Time(long ms)
        {
            return new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc).AddMilliseconds(ms).ToLocalTime().ToString("dd.MM.yyyy HH:mm:ss");
        }

        public static string ThreatTitle(Threat t)
        {
            return t.Rule == Threat.BruteForce ? "Подбор пароля по RDP" : "Вход по RDP с неизвестного адреса";
        }

        public static string ThreatText(Threat t)
        {
            if (t.Rule == Threat.BruteForce)
            {
                return t.Count + " неудачных попыток входа за 10 минут с " + t.Ip + " (подсеть " + t.Cidr + ")";
            }
            string who = string.IsNullOrEmpty(t.User) ? "Кто-то" : t.User;
            string text = who + " вошёл в сеанс с " + t.Ip + ", но планшет к сеансу не подключился.";
            if (t.AfterFail) text += " Перед этим было " + t.Count + " неудачных попыток входа — пароль могли подобрать!";
            return text;
        }

        public static string EntryText(GuardEntry e)
        {
            string user = string.IsNullOrEmpty(e.User) ? "" : " (" + e.User + ")";
            switch (e.Kind)
            {
                case GuardKind.Fail: return "Неверный пароль" + (e.Count > 1 ? " × " + e.Count : "");
                case GuardKind.Logon: return "Вход в сеанс" + user;
                case GuardKind.Logoff: return "Выход из сеанса" + user;
                case GuardKind.Disconnect: return "Отключение от сеанса" + user;
                default: return e.Kind;
            }
        }

        public static string StatusText(string status)
        {
            switch (status)
            {
                case Threat.StatusBlocked: return "заблокировано";
                case Threat.StatusTrusted: return "свой адрес";
                case Threat.StatusDismissed: return "оставлено";
                default: return "требует решения";
            }
        }
    }

    /// <summary>
    /// Окно защиты: угрозы с кнопками «Заблокировать подсеть» / «Это я» / «Оставить», журнал подключений и список
    /// заблокированных подсетей. Блокировка и разблокировка спрашивают права администратора (UAC).
    /// </summary>
    sealed class GuardForm : ThemedForm
    {
        readonly ConnectionGuard guard;
        readonly SegmentTabs tabs = new SegmentTabs();
        readonly ThemedList threatList = new ThemedList();
        readonly ThemedList entryList = new ThemedList();
        readonly ThemedList blockedList = new ThemedList();
        readonly Label firewallNote = new Label();
        readonly PillButton blockButton;
        readonly PillButton trustButton;
        readonly PillButton dismissButton;
        readonly PillButton unblockButton;
        bool busy;

        public GuardForm(ConnectionGuard guard)
        {
            this.guard = guard;
            Text = "Защита подключений — " + AgentInfo.Product;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(Ui.Px(this, 820), Ui.Px(this, 500));
            MinimumSize = new Size(Ui.Px(this, 680), Ui.Px(this, 400));
            Padding = new Padding(Ui.Px(this, 20), Ui.Px(this, 16), Ui.Px(this, 20), Ui.Px(this, 16));

            Setup(threatList, new[] { "Время", "Что произошло", "Адрес", "Решение" }, new[] { 140, 380, 120, 130 });
            Setup(entryList, new[] { "Время", "Событие", "Адрес", "" }, new[] { 140, 320, 150, 110 });
            Setup(blockedList, new[] { "Подсеть", "Состояние" }, new[] { 260, 260 });
            threatList.SelectedIndexChanged += delegate { UpdateButtons(); };
            blockedList.SelectedIndexChanged += delegate { UpdateButtons(); };
            entryList.DoubleClick += delegate { TrustSelectedEntry(); };
            tabs.Items = new[] { "Угрозы", "Журнал подключений", "Заблокировано" };
            tabs.SelectedIndexChanged += delegate { ShowPage(); UpdateButtons(); };

            var card = new Card();
            card.Dock = DockStyle.Fill;
            card.Controls.Add(threatList);
            card.Controls.Add(entryList);
            card.Controls.Add(blockedList);

            var header = MakeLabel("Защита подключений", false);
            header.Font = new Font(Font.FontFamily, Font.Size * 1.65f, FontStyle.Bold, Font.Unit);
            header.Dock = DockStyle.Top;
            header.Height = Ui.Px(this, 36);

            var tabsRow = new Panel();
            tabsRow.Dock = DockStyle.Top;
            tabsRow.BackColor = Color.Transparent;
            tabs.Location = new Point(0, 0);
            tabsRow.Controls.Add(tabs);
            // Высота таблетки зависит от шрифта, который окно передаёт после добавления.
            tabs.SizeChanged += delegate { tabsRow.Height = tabs.Height + Ui.Px(this, 10); };
            tabsRow.Height = tabs.Height + Ui.Px(this, 10);

            firewallNote.Dock = DockStyle.Bottom;
            firewallNote.AutoSize = false;
            firewallNote.Height = Ui.Px(this, 44);
            firewallNote.Padding = new Padding(0, Ui.Px(this, 6), 0, 0);
            firewallNote.BackColor = Color.Transparent;

            var bar = new ButtonBar();
            blockButton = new PillButton("Заблокировать подсеть…", PillKind.Danger, "\uE72E", delegate { BlockSelected(); });
            trustButton = new PillButton("Это я", PillKind.Primary, "\uE8FB", delegate { TrustSelected(); });
            dismissButton = new PillButton("Оставить", PillKind.Normal, null, delegate { DismissSelected(); });
            unblockButton = new PillButton("Разблокировать…", PillKind.Primary, "\uE785", delegate { UnblockSelected(); });
            var closeButton = new PillButton("Закрыть", PillKind.Normal, null, delegate { Close(); });
            foreach (PillButton b in new[] { blockButton, trustButton, dismissButton, unblockButton })
            {
                bar.LeftFlow.Controls.Add(b);
            }
            bar.RightFlow.Controls.Add(closeButton);

            // Порядок добавления задаёт порядок стыковки: последний добавленный прижимается к краю первым.
            Controls.Add(card);
            Controls.Add(Spacer(DockStyle.Bottom, 8));
            Controls.Add(firewallNote);
            Controls.Add(bar);
            Controls.Add(Spacer(DockStyle.Top, 4));
            Controls.Add(tabsRow);
            Controls.Add(header);
            CancelButton = closeButton;
            ShowPage();
            RefreshAll();
        }

        Control Spacer(DockStyle dock, int height)
        {
            var p = new Panel();
            p.Dock = dock;
            p.Height = Ui.Px(this, height);
            p.BackColor = Color.Transparent;
            return p;
        }

        void Setup(ThemedList list, string[] titles, int[] widths)
        {
            list.Dock = DockStyle.Fill;
            list.FillColumn = titles.Length > 2 ? 1 : 0;
            for (int i = 0; i < titles.Length; i++) list.Columns.Add(titles[i], Ui.Px(this, widths[i]));
        }

        /// <summary>Показывает список выбранной вкладки (остальные скрыты).</summary>
        void ShowPage()
        {
            threatList.Visible = tabs.SelectedIndex == 0;
            entryList.Visible = tabs.SelectedIndex == 1;
            blockedList.Visible = tabs.SelectedIndex == 2;
        }

        protected override void OnThemeChanged()
        {
            RefreshAll();
        }

        /// <summary>Открывает вкладку с угрозами и выделяет нужную (по щелчку на уведомлении — самую свежую нерешённую).</summary>
        public void ShowThreats(string threatId)
        {
            tabs.SelectedIndex = 0;
            foreach (ListViewItem item in threatList.Items)
            {
                if ((string)item.Tag != threatId) continue;
                item.Selected = true;
                item.EnsureVisible();
                break;
            }
        }

        public void RefreshAll()
        {
            string selectedThreat = SelectedTag(threatList);
            string selectedBlocked = SelectedTag(blockedList);
            threatList.BeginUpdate();
            threatList.Items.Clear();
            List<Threat> threats = guard.Threats;
            for (int i = threats.Count - 1; i >= 0; i--)
            {
                Threat t = threats[i];
                var item = new ListViewItem(GuardText.Time(t.Time));
                item.SubItems.Add(GuardText.ThreatTitle(t) + ": " + GuardText.ThreatText(t));
                item.SubItems.Add(t.Cidr);
                item.SubItems.Add(GuardText.StatusText(t.Status));
                item.Tag = t.Id;
                if (t.Status == Threat.StatusNew) item.ForeColor = Theme.P.Danger;
                else item.ForeColor = Theme.P.Muted;
                threatList.Items.Add(item);
            }
            threatList.EndUpdate();
            Reselect(threatList, selectedThreat);

            entryList.BeginUpdate();
            entryList.Items.Clear();
            List<GuardEntry> entries = guard.Entries;
            for (int i = entries.Count - 1; i >= 0; i--)
            {
                GuardEntry e = entries[i];
                var item = new ListViewItem(GuardText.Time(e.Time));
                item.SubItems.Add(GuardText.EntryText(e));
                item.SubItems.Add(e.Ip);
                item.SubItems.Add(e.Local ? "своя сеть" : e.Trusted ? "свой адрес" : "");
                item.Tag = e.Ip;
                if (e.Kind == GuardKind.Fail && !e.Local && !e.Trusted) item.ForeColor = Theme.P.Danger;
                else if (e.Local || e.Trusted) item.ForeColor = Theme.P.Muted;
                entryList.Items.Add(item);
            }
            entryList.EndUpdate();

            blockedList.BeginUpdate();
            blockedList.Items.Clear();
            foreach (BlockedRange b in guard.Blocked)
            {
                var item = new ListViewItem(b.Cidr);
                item.SubItems.Add(b.Enabled ? (guard.FirewallOn ? "заблокирована" : "правило есть, брандмауэр выключен") : "правило отключено");
                item.Tag = b.Cidr;
                blockedList.Items.Add(item);
            }
            blockedList.EndUpdate();
            Reselect(blockedList, selectedBlocked);

            firewallNote.Text = guard.FirewallOn
                ? "Двойной щелчок по записи журнала с неудачным входом или входом — пометить адрес как свой."
                : "Брандмауэр Windows выключен: правила блокировки не действуют, пока он не включён. При блокировке агент предложит включить его (RDP останется доступен).";
            firewallNote.ForeColor = guard.FirewallOn ? Theme.P.Muted : Theme.P.Danger;
            UpdateButtons();
        }

        static string SelectedTag(ListView list)
        {
            return list.SelectedItems.Count == 0 ? null : (string)list.SelectedItems[0].Tag;
        }

        static void Reselect(ListView list, string tag)
        {
            if (tag == null) return;
            foreach (ListViewItem item in list.Items)
            {
                if ((string)item.Tag != tag) continue;
                item.Selected = true;
                return;
            }
        }

        Threat SelectedThreat()
        {
            string id = SelectedTag(threatList);
            if (id == null) return null;
            foreach (Threat t in guard.Threats) if (t.Id == id) return t;
            return null;
        }

        void UpdateButtons()
        {
            Threat t = SelectedThreat();
            bool onThreats = tabs.SelectedIndex == 0;
            bool open = t != null && t.Status == Threat.StatusNew;
            blockButton.Visible = trustButton.Visible = dismissButton.Visible = onThreats;
            unblockButton.Visible = tabs.SelectedIndex == 2;
            blockButton.Enabled = open && !busy;
            trustButton.Enabled = open && !busy;
            dismissButton.Enabled = open && !busy;
            unblockButton.Enabled = SelectedTag(blockedList) != null && !busy;
        }

        void BlockSelected()
        {
            Threat t = SelectedThreat();
            if (t == null || busy) return;
            bool enableFirewall = false;
            if (!guard.FirewallOn)
            {
                DialogResult answer = ThemedBox.Show(this, 
                    "Брандмауэр Windows на этом ПК выключен, поэтому правило блокировки не будет работать.\n\n"
                    + "Включить брандмауэр и заблокировать " + t.Cidr + "?\n"
                    + "Входящий RDP останется разрешён; остальные входящие подключения, которые не разрешены правилами, будут блокироваться.\n\n"
                    + "Да — включить брандмауэр и заблокировать\nНет — только добавить правило (заработает, когда брандмауэр включат)",
                    AgentInfo.Product, MessageBoxButtons.YesNoCancel, MessageBoxIcon.Warning);
                if (answer == DialogResult.Cancel) return;
                enableFirewall = answer == DialogResult.Yes;
            }
            else if (ThemedBox.Show(this, "Заблокировать подсеть " + t.Cidr + "? Подключения с неё к этому ПК перестанут проходить.\nWindows запросит права администратора.",
                AgentInfo.Product, MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK)
            {
                return;
            }
            Run(delegate(Action<string> done) { guard.Block(t.Cidr, enableFirewall, done); });
        }

        void UnblockSelected()
        {
            string cidr = SelectedTag(blockedList);
            if (cidr == null || busy) return;
            if (ThemedBox.Show(this, "Разблокировать " + cidr + "?\nWindows запросит права администратора.", AgentInfo.Product,
                MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
            Run(delegate(Action<string> done) { guard.Unblock(cidr, done); });
        }

        void Run(Action<Action<string>> action)
        {
            busy = true;
            UpdateButtons();
            UseWaitCursor = true;
            action(delegate(string error)
            {
                busy = false;
                UseWaitCursor = false;
                if (IsDisposed) return;
                RefreshAll();
                if (error != null) ThemedBox.Show(this, error, AgentInfo.Product, MessageBoxButtons.OK, MessageBoxIcon.Warning);
            });
        }

        void TrustSelected()
        {
            Threat t = SelectedThreat();
            if (t == null || t.Status != Threat.StatusNew) return;
            guard.Trust(t.Ip);
        }

        void DismissSelected()
        {
            Threat t = SelectedThreat();
            if (t != null) guard.Dismiss(t.Id);
        }

        void TrustSelectedEntry()
        {
            string ip = SelectedTag(entryList);
            if (ip == null) return;
            if (ThemedBox.Show(this, "Считать адрес " + ip + " своим? Входы и неудачные попытки с него больше не будут считаться подозрительными.",
                AgentInfo.Product, MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
            guard.Trust(ip);
        }
    }
}
