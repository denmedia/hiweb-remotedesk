using System;
using System.Collections.Generic;
using System.Drawing;
using System.Net;
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
            switch (t.Rule)
            {
                case Threat.BruteForce: return "Подбор пароля по RDP";
                case Threat.Unlisted: return "Попытка входа с адреса вне белого списка";
                case Threat.NewAddress: return "Вход с нового адреса: занести в белый список?";
                default: return "Вход по RDP с неизвестного адреса";
            }
        }

        public static string ThreatText(Threat t)
        {
            if (t.Rule == Threat.BruteForce)
            {
                return t.Count + " неудачных попыток входа за 10 минут с " + t.Ip + " (подсеть " + t.Cidr + ")";
            }
            if (t.Rule == Threat.Unlisted)
            {
                return "Неудачных попыток входа за 10 минут: " + t.Count + ". Адрес " + t.Ip + " (подсеть " + t.Cidr + ") не входит в белый список";
            }
            string who = string.IsNullOrEmpty(t.User) ? "Кто-то" : t.User;
            if (t.Rule == Threat.NewAddress)
            {
                return who + " вошёл в сеанс с " + t.Ip + ", планшет к сеансу подключился. Адреса нет в белом списке; если это вы, занесите его.";
            }
            string text = who + " вошёл в сеанс с " + t.Ip + ", но планшет к сеансу не подключился.";
            if (t.AfterFail) text += " Перед этим было " + t.Count + " неудачных попыток входа — пароль могли подобрать!";
            return text;
        }

        /// <summary>Адрес для списка: у одного адреса длину «/32» и «/128» не показываем.</summary>
        public static string Cidr(string cidr)
        {
            return cidr.EndsWith("/32") ? cidr.Substring(0, cidr.Length - 3) : cidr.EndsWith("/128") ? cidr.Substring(0, cidr.Length - 4) : cidr;
        }

        public static string Source(string source)
        {
            return source == WhitelistEntry.Manual ? "вручную" : "автоматически (по подключению планшета)";
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
                case Threat.StatusTrusted: return "в белом списке";
                case Threat.StatusDismissed: return "оставлено";
                default: return "требует решения";
            }
        }
    }

    /// <summary>
    /// Окно защиты: угрозы с кнопками «Заблокировать подсеть» / «Это я» / «Оставить», журнал подключений, список
    /// заблокированных подсетей и белый список знакомых адресов с настройками режима. Блокировка и разблокировка
    /// спрашивают права администратора (UAC).
    /// </summary>
    sealed class GuardForm : ThemedForm
    {
        readonly ConnectionGuard guard;
        readonly SegmentTabs tabs = new SegmentTabs();
        readonly ThemedList threatList = new ThemedList();
        readonly ThemedList entryList = new ThemedList();
        readonly ThemedList blockedList = new ThemedList();
        readonly ThemedList whiteList = new ThemedList();
        readonly SegmentTabs modeTabs = new SegmentTabs();
        readonly SegmentTabs learnTabs = new SegmentTabs();
        readonly Panel whiteSettings = new Panel();
        readonly Label firewallNote = new Label();
        readonly PillButton blockButton;
        readonly PillButton trustButton;
        readonly PillButton dismissButton;
        readonly PillButton trustSubnetButton;
        readonly PillButton unblockButton;
        readonly PillButton addWhiteButton;
        readonly PillButton removeWhiteButton;
        bool busy;
        bool refreshing;

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
            Setup(whiteList, new[] { "Адрес или подсеть", "Как добавлен", "Добавлен" }, new[] { 260, 260, 160 });
            whiteList.FillColumn = 0;
            whiteList.SelectedIndexChanged += delegate { UpdateButtons(); };
            threatList.SelectedIndexChanged += delegate { UpdateButtons(); };
            blockedList.SelectedIndexChanged += delegate { UpdateButtons(); };
            entryList.DoubleClick += delegate { TrustSelectedEntry(); };
            tabs.Items = new[] { "Угрозы", "Журнал подключений", "Заблокировано", "Белый список" };
            tabs.SelectedIndexChanged += delegate { ShowPage(); UpdateButtons(); UpdateNote(); };

            modeTabs.Items = new[] { "Обычный", "Белый список" };
            modeTabs.SelectedIndexChanged += delegate { if (!refreshing) ChangeMode(); };
            learnTabs.Items = new[] { "Не запоминать", "Адрес", "Подсеть" };
            learnTabs.SelectedIndexChanged += delegate { if (!refreshing) ChangeLearn(); };
            whiteSettings.Dock = DockStyle.Top;
            whiteSettings.BackColor = Color.Transparent;
            // Строки добавляются снизу вверх: последняя добавленная прижимается к верхнему краю первой.
            whiteSettings.Controls.Add(SettingRow("Запоминать адрес планшета", learnTabs));
            whiteSettings.Controls.Add(SettingRow("Режим защиты", modeTabs));

            var card = new Card();
            card.Dock = DockStyle.Fill;
            card.Controls.Add(threatList);
            card.Controls.Add(entryList);
            card.Controls.Add(blockedList);
            card.Controls.Add(whiteList);
            card.Controls.Add(whiteSettings);

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
            trustButton = new PillButton("Это я", PillKind.Primary, "\uE8FB", delegate { TrustSelected(false); });
            trustSubnetButton = new PillButton("Это я: вся подсеть", PillKind.Normal, null, delegate { TrustSelected(true); });
            dismissButton = new PillButton("Оставить", PillKind.Normal, null, delegate { DismissSelected(); });
            unblockButton = new PillButton("Разблокировать…", PillKind.Primary, "\uE785", delegate { UnblockSelected(); });
            addWhiteButton = new PillButton("Добавить…", PillKind.Primary, "\uE710", delegate { AddToWhitelist(); });
            removeWhiteButton = new PillButton("Убрать из списка", PillKind.Normal, "\uE74D", delegate { RemoveFromWhitelist(); });
            var closeButton = new PillButton("Закрыть", PillKind.Normal, null, delegate { Close(); });
            foreach (PillButton b in new[] { blockButton, trustButton, trustSubnetButton, dismissButton, unblockButton, addWhiteButton, removeWhiteButton })
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
            FitSettings();
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
            whiteList.Visible = whiteSettings.Visible = tabs.SelectedIndex == 3;
        }

        /// <summary>Строка настроек: подпись слева, переключатель справа (подпись одной ширины в обеих строках).</summary>
        Control SettingRow(string caption, SegmentTabs control)
        {
            var row = new Panel();
            row.Dock = DockStyle.Top;
            row.BackColor = Color.Transparent;
            row.Height = control.Height + Ui.Px(this, 6);
            var label = MakeLabel(caption, false);
            label.TextAlign = ContentAlignment.MiddleLeft;
            label.SetBounds(Ui.Px(this, 8), 0, Ui.Px(this, 230), control.Height);
            control.Location = new Point(label.Right + Ui.Px(this, 8), 0);
            row.Controls.Add(label);
            row.Controls.Add(control);
            row.Tag = control;
            // Высота таблетки зависит от шрифта, который окно передаёт после добавления на форму.
            control.SizeChanged += delegate { FitSettings(); };
            return row;
        }

        void FitSettings()
        {
            int total = 0;
            foreach (Control row in whiteSettings.Controls)
            {
                var control = row.Tag as SegmentTabs;
                if (control == null) continue;
                row.Height = control.Height + Ui.Px(this, 6);
                row.Controls[0].Height = control.Height;
                total += row.Height;
            }
            whiteSettings.Height = total;
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            FitSettings();
        }

        void UpdateNote()
        {
            if (tabs.SelectedIndex == 3)
            {
                firewallNote.Text = guard.Mode == GuardMode.Whitelist
                    ? "Белый список: тревога на любую попытку входа с адреса, которого нет в списке. Знакомые адреса и подсети (и своя сеть) не проверяются. Блокировка — через брандмауэр, с подтверждением на ПК."
                    : "Обычный режим: тревога после " + ThreatDetector.FailThreshold + " неудачных попыток за 10 минут и при входе, к которому планшет не подключился. Адреса из списка не проверяются.";
                firewallNote.ForeColor = Theme.P.Muted;
                return;
            }
            firewallNote.Text = guard.FirewallOn
                ? "Двойной щелчок по записи журнала с неудачным входом или входом — занести адрес в белый список."
                : "Брандмауэр Windows выключен: правила блокировки не действуют, пока он не включён. При блокировке агент предложит включить его (RDP останется доступен).";
            firewallNote.ForeColor = guard.FirewallOn ? Theme.P.Muted : Theme.P.Danger;
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

            string selectedWhite = SelectedTag(whiteList);
            whiteList.BeginUpdate();
            whiteList.Items.Clear();
            List<WhitelistEntry> white = guard.WhitelistEntries;
            white.Sort(delegate(WhitelistEntry a, WhitelistEntry b) { return b.Time.CompareTo(a.Time); });
            foreach (WhitelistEntry w in white)
            {
                var item = new ListViewItem(GuardText.Cidr(w.Cidr));
                item.SubItems.Add(GuardText.Source(w.Source));
                item.SubItems.Add(w.Time > 0 ? GuardText.Time(w.Time) : "");
                item.Tag = w.Cidr;
                whiteList.Items.Add(item);
            }
            whiteList.EndUpdate();
            Reselect(whiteList, selectedWhite);

            // Переключатели показывают состояние агента; их собственные события в этот момент игнорируются.
            refreshing = true;
            modeTabs.SelectedIndex = guard.Mode == GuardMode.Whitelist ? 1 : 0;
            learnTabs.SelectedIndex = !guard.AutoLearn ? 0 : guard.LearnSubnet ? 2 : 1;
            refreshing = false;
            UpdateNote();
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
            blockButton.Visible = trustButton.Visible = trustSubnetButton.Visible = dismissButton.Visible = onThreats;
            unblockButton.Visible = tabs.SelectedIndex == 2;
            addWhiteButton.Visible = removeWhiteButton.Visible = tabs.SelectedIndex == 3;
            blockButton.Enabled = open && !busy;
            trustButton.Enabled = open && !busy;
            trustSubnetButton.Enabled = open && !busy;
            dismissButton.Enabled = open && !busy;
            unblockButton.Enabled = SelectedTag(blockedList) != null && !busy;
            removeWhiteButton.Enabled = SelectedTag(whiteList) != null;
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

        void TrustSelected(bool wholeSubnet)
        {
            Threat t = SelectedThreat();
            if (t == null || t.Status != Threat.StatusNew) return;
            IPAddress ip;
            if (!IpTools.TryParse(t.Ip, out ip)) return;
            Trust(wholeSubnet ? IpTools.Subnet(ip) : t.Ip);
        }

        void Trust(string target)
        {
            string error = guard.Trust(target);
            if (error != null) ThemedBox.Show(this, error, AgentInfo.Product, MessageBoxButtons.OK, MessageBoxIcon.Warning);
        }

        void ChangeMode()
        {
            bool toWhitelist = modeTabs.SelectedIndex == 1;
            if (toWhitelist && guard.Mode != GuardMode.Whitelist)
            {
                int n = guard.WhitelistEntries.Count;
                string text = "В режиме белого списка любая попытка входа с неизвестного адреса считается угрозой и сразу вызывает тревогу.\n\n"
                    + (n == 0 ? "Сейчас в списке пусто: тревога будет и на ваши обычные подключения, пока адреса не занесены (планшет заносит свой адрес сам, если включено «Запоминать адрес планшета»)."
                              : "Сейчас в списке записей: " + n + ". Если вы подключаетесь с другого места, добавьте адрес заранее или подтвердите его при первом входе.")
                    + "\n\nВключить режим белого списка?";
                if (ThemedBox.Show(this, text, AgentInfo.Product, MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK)
                {
                    RefreshAll();
                    return;
                }
            }
            guard.Configure(toWhitelist ? GuardMode.Whitelist : GuardMode.Normal, null, null);
        }

        void ChangeLearn()
        {
            guard.Configure(null, learnTabs.SelectedIndex != 0, learnTabs.SelectedIndex == 2);
        }

        void AddToWhitelist()
        {
            string text = AddressPrompt.Ask(this);
            if (text != null) Trust(text);
        }

        void RemoveFromWhitelist()
        {
            string cidr = SelectedTag(whiteList);
            if (cidr == null) return;
            if (ThemedBox.Show(this, "Убрать " + GuardText.Cidr(cidr) + " из белого списка? Подключения с этого адреса снова будут проверяться.", AgentInfo.Product,
                MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
            string error = guard.Untrust(cidr);
            if (error != null) ThemedBox.Show(this, error, AgentInfo.Product, MessageBoxButtons.OK, MessageBoxIcon.Warning);
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
            if (ThemedBox.Show(this, "Занести адрес " + ip + " в белый список? Входы и неудачные попытки с него больше не будут считаться подозрительными.",
                AgentInfo.Product, MessageBoxButtons.OKCancel, MessageBoxIcon.Question) != DialogResult.OK) return;
            Trust(ip);
        }
    }

    /// <summary>Вопрос «какой адрес или подсеть занести в белый список»: поле ввода с проверкой записи на месте.</summary>
    sealed class AddressPrompt : ThemedForm
    {
        readonly ThemedInput input = new ThemedInput();
        readonly Label error;
        string value;

        public static string Ask(IWin32Window owner)
        {
            using (var form = new AddressPrompt())
            {
                return form.ShowDialog(owner) == DialogResult.OK ? form.value : null;
            }
        }

        AddressPrompt()
        {
            Text = "Белый список — " + AgentInfo.Product;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition = FormStartPosition.CenterParent;
            MaximizeBox = false;
            ShowInTaskbar = false;

            int m = Ui.Px(this, 22);
            int gap = Ui.Px(this, 12);
            int width = Ui.Px(this, 500);
            int inner = width - 2 * m;

            var caption = MakeLabel("Адрес, например 198.51.100.71, или подсеть: 198.51.100.0/24 (IPv6: 2001:db8:1:2::/64). "
                + "Подсети шире /16 (IPv6 — /48) не принимаются; адреса своей сети и так считаются своими.", false);
            caption.SetBounds(m, m, inner, Font.Height * 4);
            Controls.Add(caption);

            input.Width = inner;
            Controls.Add(input);
            input.Location = new Point(m, caption.Bottom + gap);

            error = MakeLabel("", false);
            error.ForeColor = Theme.P.Danger;
            error.SetBounds(m, input.Bottom + gap / 2, inner, Font.Height * 2);
            Controls.Add(error);

            var ok = new PillButton("Добавить", PillKind.Primary, null, delegate { Accept(); });
            var cancel = new PillButton("Отмена", PillKind.Normal, null, delegate { DialogResult = DialogResult.Cancel; });
            int top = error.Bottom + gap;
            cancel.Location = new Point(width - m - cancel.Width, top);
            ok.Location = new Point(cancel.Left - gap - ok.Width, top);
            Controls.Add(ok);
            Controls.Add(cancel);
            ClientSize = new Size(width, top + ok.Height + m);
            AcceptButton = ok;
            CancelButton = cancel;
            input.Box.TextChanged += delegate { error.Text = ""; };
        }

        void Accept()
        {
            string text = input.Box.Text.Trim();
            if (IpTools.NormalizeBlock(text) == null)
            {
                error.Text = "Это не публичный адрес и не подходящая подсеть. Проверьте запись.";
                return;
            }
            value = text;
            DialogResult = DialogResult.OK;
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            input.Box.Focus();
        }
    }
}
