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
    sealed class GuardForm : Form
    {
        readonly ConnectionGuard guard;
        readonly TabControl tabs = new TabControl();
        readonly ListView threatList = new ListView();
        readonly ListView entryList = new ListView();
        readonly ListView blockedList = new ListView();
        readonly Label firewallNote = new Label();
        readonly Button blockButton;
        readonly Button trustButton;
        readonly Button dismissButton;
        readonly Button unblockButton;
        bool busy;

        public GuardForm(ConnectionGuard guard)
        {
            this.guard = guard;
            Text = "Защита подключений — " + AgentInfo.Product;
            StartPosition = FormStartPosition.CenterScreen;
            MinimizeBox = false;
            ShowIcon = false;
            ClientSize = new Size(760, 440);
            MinimumSize = new Size(620, 340);
            Font = SystemFonts.MessageBoxFont;

            Setup(threatList, new[] { "Время", "Что произошло", "Адрес", "Решение" }, new[] { 130, 360, 120, 120 });
            Setup(entryList, new[] { "Время", "Событие", "Адрес", "" }, new[] { 130, 300, 140, 100 });
            Setup(blockedList, new[] { "Подсеть", "Состояние" }, new[] { 260, 200 });
            threatList.SelectedIndexChanged += delegate { UpdateButtons(); };
            blockedList.SelectedIndexChanged += delegate { UpdateButtons(); };
            tabs.SelectedIndexChanged += delegate { UpdateButtons(); };
            entryList.DoubleClick += delegate { TrustSelectedEntry(); };

            AddTab("Угрозы", threatList);
            AddTab("Журнал подключений", entryList);
            AddTab("Заблокировано", blockedList);
            tabs.Dock = DockStyle.Fill;

            firewallNote.Dock = DockStyle.Bottom;
            firewallNote.AutoSize = false;
            firewallNote.Height = 44;
            firewallNote.Padding = new Padding(10, 6, 10, 4);
            firewallNote.ForeColor = Color.FromArgb(190, 50, 30);

            var buttons = new FlowLayoutPanel();
            buttons.Dock = DockStyle.Bottom;
            buttons.FlowDirection = FlowDirection.LeftToRight;
            buttons.AutoSize = true;
            buttons.Padding = new Padding(8);
            buttons.WrapContents = false;
            blockButton = MakeButton("Заблокировать подсеть…", delegate { BlockSelected(); });
            trustButton = MakeButton("Это я", delegate { TrustSelected(); });
            dismissButton = MakeButton("Оставить", delegate { DismissSelected(); });
            unblockButton = MakeButton("Разблокировать…", delegate { UnblockSelected(); });
            buttons.Controls.Add(blockButton);
            buttons.Controls.Add(trustButton);
            buttons.Controls.Add(dismissButton);
            buttons.Controls.Add(unblockButton);
            buttons.Controls.Add(new Label { AutoSize = false, Width = 30 });
            buttons.Controls.Add(MakeButton("Закрыть", delegate { Close(); }));

            Controls.Add(tabs);
            Controls.Add(firewallNote);
            Controls.Add(buttons);
            RefreshAll();
        }

        static void Setup(ListView list, string[] titles, int[] widths)
        {
            list.View = View.Details;
            list.FullRowSelect = true;
            list.MultiSelect = false;
            list.HideSelection = false;
            list.Dock = DockStyle.Fill;
            for (int i = 0; i < titles.Length; i++) list.Columns.Add(titles[i], widths[i]);
        }

        void AddTab(string title, Control content)
        {
            var page = new TabPage(title);
            page.Controls.Add(content);
            tabs.TabPages.Add(page);
        }

        static Button MakeButton(string text, EventHandler click)
        {
            var b = new Button();
            b.Text = text;
            b.AutoSize = true;
            b.AutoSizeMode = AutoSizeMode.GrowAndShrink;
            b.Padding = new Padding(8, 2, 8, 2);
            b.Click += click;
            return b;
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
                if (t.Status == Threat.StatusNew) item.ForeColor = Color.FromArgb(190, 50, 30);
                else item.ForeColor = SystemColors.GrayText;
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
                if (e.Kind == GuardKind.Fail && !e.Local && !e.Trusted) item.ForeColor = Color.FromArgb(190, 50, 30);
                else if (e.Local || e.Trusted) item.ForeColor = SystemColors.GrayText;
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
            firewallNote.ForeColor = guard.FirewallOn ? SystemColors.GrayText : Color.FromArgb(190, 50, 30);
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
                DialogResult answer = MessageBox.Show(
                    "Брандмауэр Windows на этом ПК выключен, поэтому правило блокировки не будет работать.\n\n"
                    + "Включить брандмауэр и заблокировать " + t.Cidr + "?\n"
                    + "Входящий RDP останется разрешён; остальные входящие подключения, которые не разрешены правилами, будут блокироваться.\n\n"
                    + "Да — включить брандмауэр и заблокировать\nНет — только добавить правило (заработает, когда брандмауэр включат)",
                    AgentInfo.Product, MessageBoxButtons.YesNoCancel, MessageBoxIcon.Warning);
                if (answer == DialogResult.Cancel) return;
                enableFirewall = answer == DialogResult.Yes;
            }
            else if (MessageBox.Show("Заблокировать подсеть " + t.Cidr + "? Подключения с неё к этому ПК перестанут проходить.\nWindows запросит права администратора.",
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
            if (MessageBox.Show("Разблокировать " + cidr + "?\nWindows запросит права администратора.", AgentInfo.Product,
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
                if (error != null) MessageBox.Show(error, AgentInfo.Product, MessageBoxButtons.OK, MessageBoxIcon.Warning);
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
            if (MessageBox.Show("Считать адрес " + ip + " своим? Входы и неудачные попытки с него больше не будут считаться подозрительными.",
                AgentInfo.Product, MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
            guard.Trust(ip);
        }
    }
}
