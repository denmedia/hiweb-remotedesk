using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Windows.Forms;

namespace HiWebAgent
{
    /// <summary>
    /// Окно «Доступ к файлам»: выключатель и список папок ПК, которые планшет видит как отдельные «диски» без трансляции
    /// экрана. У каждой папки свой режим — только чтение или чтение и запись. Изменения применяются сразу и сразу уходят
    /// устройствам на связи.
    /// </summary>
    sealed class SharesForm : ThemedForm
    {
        readonly ThemedList list = new ThemedList();
        readonly SegmentTabs enableTabs = new SegmentTabs();
        readonly Panel enableRow = new Panel();
        readonly Label enableLabel;
        readonly PillButton renameButton;
        readonly PillButton modeButton;
        readonly PillButton removeButton;
        string selectedId;
        bool refreshing;

        public SharesForm()
        {
            Text = "Доступ к файлам — " + AgentInfo.Product;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(Ui.Px(this, 720), Ui.Px(this, 470));
            MinimumSize = new Size(Ui.Px(this, 600), Ui.Px(this, 380));
            Padding = new Padding(Ui.Px(this, 20), Ui.Px(this, 16), Ui.Px(this, 20), Ui.Px(this, 16));

            list.Dock = DockStyle.Fill;
            list.FillColumn = 1;
            list.Columns.Add("Название", Ui.Px(this, 170));
            list.Columns.Add("Папка на ПК", Ui.Px(this, 300));
            list.Columns.Add("Доступ", Ui.Px(this, 150));
            list.SelectedIndexChanged += delegate
            {
                ShareDef s = Selected();
                if (!refreshing) selectedId = s == null ? null : s.Id;
                UpdateButtons();
            };
            list.DoubleClick += delegate { RenameSelected(); };

            var card = new Card();
            card.Dock = DockStyle.Fill;
            card.Controls.Add(list);

            var header = MakeLabel("Доступ к файлам", false);
            header.Font = new Font(Font.FontFamily, Font.Size * 1.65f, FontStyle.Bold, Font.Unit);
            header.Dock = DockStyle.Top;
            header.Height = Ui.Px(this, 36);

            var hint = MakeLabel("Открытые папки планшет увидит как отдельные «диски» в приложении «Файлы» — даже когда экран ПК не "
                + "транслируется. Агент работает от вашей учётной записи Windows: планшету доступно не больше, чем вам. "
                + "Удалённые с планшета файлы попадают в корзину Windows.", true);
            hint.Dock = DockStyle.Top;
            hint.Height = Ui.Px(this, 64);

            enableTabs.Items = new[] { "Выключен", "Включён" };
            enableTabs.SelectedIndexChanged += delegate { if (!refreshing) ChangeEnabled(); };
            enableRow.Dock = DockStyle.Top;
            enableRow.BackColor = Color.Transparent;
            enableRow.Height = enableTabs.Height + Ui.Px(this, 6);
            enableLabel = MakeLabel("Доступ к файлам с планшета", false);
            enableLabel.TextAlign = ContentAlignment.MiddleLeft;
            enableLabel.SetBounds(Ui.Px(this, 8), 0, Ui.Px(this, 250), enableTabs.Height);
            enableTabs.Location = new Point(enableLabel.Right + Ui.Px(this, 8), 0);
            enableRow.Controls.Add(enableLabel);
            enableRow.Controls.Add(enableTabs);
            // Высота таблетки зависит от шрифта, который окно передаёт после добавления на форму.
            enableTabs.SizeChanged += delegate { FitRow(); };

            var bar = new ButtonBar();
            var addButton = new PillButton("Добавить папку…", PillKind.Primary, "", delegate { AddFolder(); });
            addButton.Margin = new Padding(0, 0, Ui.Px(this, 10), 0);
            var pill = new Pill();
            renameButton = new PillButton("Название…", PillKind.Ghost, "", delegate { RenameSelected(); });
            modeButton = new PillButton("Режим", PillKind.Ghost, "", delegate { ToggleMode(); });
            renameButton.Margin = modeButton.Margin = new Padding(2, 0, 2, 0);
            pill.Controls.Add(renameButton);
            pill.Controls.Add(modeButton);
            removeButton = new PillButton("Убрать", PillKind.Danger, "", delegate { RemoveSelected(); });
            removeButton.Margin = new Padding(Ui.Px(this, 10), 0, 0, 0);
            var closeButton = new PillButton("Закрыть", PillKind.Normal, null, delegate { Close(); });
            closeButton.Margin = new Padding(0);
            bar.LeftFlow.Controls.Add(addButton);
            bar.LeftFlow.Controls.Add(pill);
            bar.LeftFlow.Controls.Add(removeButton);
            bar.RightFlow.Controls.Add(closeButton);

            // Порядок добавления задаёт порядок стыковки: последний добавленный прижимается к краю первым.
            Controls.Add(card);
            Controls.Add(Spacer(DockStyle.Bottom, 12));
            Controls.Add(bar);
            Controls.Add(Spacer(DockStyle.Top, 10));
            Controls.Add(enableRow);
            Controls.Add(hint);
            Controls.Add(header);
            AcceptButton = addButton;
            CancelButton = closeButton;
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

        void FitRow()
        {
            enableRow.Height = enableTabs.Height + Ui.Px(this, 6);
            enableLabel.Height = enableTabs.Height;
        }

        protected override void OnShown(EventArgs e)
        {
            base.OnShown(e);
            FitRow();
        }

        protected override void OnThemeChanged()
        {
            RefreshAll();
        }

        /// <summary>Перечитывает настройки; выделение остаётся на прежней папке, если она ещё есть.</summary>
        public void RefreshAll()
        {
            ShareConfig config = FileSharing.Current();
            refreshing = true;
            try
            {
                enableTabs.SelectedIndex = config.Enabled ? 1 : 0;
                list.BeginUpdate();
                try
                {
                    list.Items.Clear();
                    foreach (ShareDef s in config.Shares)
                    {
                        bool exists = Directory.Exists(s.Path);
                        var item = new ListViewItem(s.Name);
                        item.SubItems.Add(s.Path);
                        item.SubItems.Add(!exists ? "папки нет на ПК" : s.Write ? "чтение и запись" : "только чтение");
                        item.Tag = s.Id;
                        item.ForeColor = exists ? Theme.P.Text : Theme.P.Muted;
                        list.Items.Add(item);
                    }
                }
                finally
                {
                    list.EndUpdate();
                }
                SelectId(selectedId);
                if (list.SelectedItems.Count == 0 && list.Items.Count == 1) list.Items[0].Selected = true;
            }
            finally
            {
                refreshing = false;
            }
            UpdateButtons();
        }

        void SelectId(string id)
        {
            if (id == null) return;
            foreach (ListViewItem item in list.Items)
            {
                if (Convert.ToString(item.Tag) == id)
                {
                    item.Selected = true;
                    item.Focused = true;
                    return;
                }
            }
        }

        ShareDef Selected()
        {
            if (list.SelectedItems.Count == 0) return null;
            return FileSharing.Current().Find(Convert.ToString(list.SelectedItems[0].Tag));
        }

        void UpdateButtons()
        {
            ShareDef s = Selected();
            renameButton.Enabled = modeButton.Enabled = removeButton.Enabled = s != null;
            modeButton.Text = s != null && s.Write ? "Только чтение" : "Разрешить запись";
        }

        // ------------------------------------------------------------------ действия

        void ChangeEnabled()
        {
            ShareConfig config = FileSharing.Current();
            bool on = enableTabs.SelectedIndex == 1;
            if (config.Enabled == on) return;
            if (on && config.Shares.Count == 0)
            {
                // Включать нечего: сначала папка. Пустой «диск» планшету ни к чему.
                ThemedBox.Show(this, "Сначала добавьте папку, которую увидит планшет (кнопка «Добавить папку…»).", AgentInfo.Product, MessageBoxButtons.OK, MessageBoxIcon.Information);
                RefreshAll();
                return;
            }
            config.Enabled = on;
            FileSharing.Set(config);
            Log.Write("files: access " + (on ? "enabled" : "disabled"));
        }

        void AddFolder()
        {
            string path;
            using (var dialog = new FolderBrowserDialog())
            {
                dialog.Description = "Выберите папку, которую увидит планшет";
                dialog.ShowNewFolderButton = false;
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                path = dialog.SelectedPath;
            }
            string full;
            try { full = ShareConfig.Normalize(path); }
            catch (Exception) { full = null; }
            if (full == null) return;

            string root = Path.GetPathRoot(full);
            string profile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            bool wide = string.Equals(full.TrimEnd('\\'), (root ?? "").TrimEnd('\\'), StringComparison.OrdinalIgnoreCase)
                || string.Equals(full.TrimEnd('\\'), profile.TrimEnd('\\'), StringComparison.OrdinalIgnoreCase);
            if (wide)
            {
                var sure = ThemedBox.Show(this,
                    "Вы открываете для планшета «" + full + "» целиком: там личные документы, переписка и другие данные. "
                        + "Лучше открыть отдельную папку. Всё равно открыть?",
                    AgentInfo.Product, MessageBoxButtons.YesNo, MessageBoxIcon.Warning);
                if (sure != DialogResult.Yes) return;
            }
            var mode = ThemedBox.Show(this,
                "Разрешить планшету изменять файлы в «" + ShareConfig.LastName(full) + "»?" + Environment.NewLine
                    + "Да — чтение и запись (создавать, переименовывать, удалять, загружать)." + Environment.NewLine
                    + "Нет — только чтение: планшет сможет смотреть и скачивать файлы.",
                AgentInfo.Product, MessageBoxButtons.YesNo, MessageBoxIcon.Question);

            ShareConfig config = FileSharing.Current();
            string error;
            ShareDef added = config.Add(full, mode == DialogResult.Yes, out error);
            if (added == null)
            {
                ThemedBox.Show(this, error, AgentInfo.Product, MessageBoxButtons.OK, MessageBoxIcon.Information);
                return;
            }
            config.Enabled = true;
            FileSharing.Set(config);
            Log.Write("files: share added " + added.Name + (added.Write ? " (запись)" : " (чтение)"));
            selectedId = added.Id;
            RefreshAll();
        }

        void RenameSelected()
        {
            ShareDef s = Selected();
            if (s == null) return;
            string name = RenameDialog.Ask(this, "Название диска на планшете", s.Name, "Так папка называется в приложении «Файлы» на планшете.");
            if (name == null) return;
            ShareConfig config = FileSharing.Current();
            ShareDef target = config.Find(s.Id);
            if (target == null) return;
            config.Rename(target, name);
            FileSharing.Set(config);
            selectedId = s.Id;
            RefreshAll();
        }

        void ToggleMode()
        {
            ShareDef s = Selected();
            if (s == null) return;
            if (!s.Write)
            {
                var sure = ThemedBox.Show(this,
                    "Разрешить планшету изменять файлы в «" + s.Name + "»?" + Environment.NewLine
                        + "Он сможет создавать, переименовывать, заменять и удалять файлы и папки (удалённое уходит в корзину Windows).",
                    AgentInfo.Product, MessageBoxButtons.YesNo, MessageBoxIcon.Question);
                if (sure != DialogResult.Yes) return;
            }
            ShareConfig config = FileSharing.Current();
            ShareDef target = config.Find(s.Id);
            if (target == null) return;
            target.Write = !target.Write;
            FileSharing.Set(config);
            Log.Write("files: share " + target.Name + (target.Write ? " -> запись" : " -> только чтение"));
            selectedId = s.Id;
            RefreshAll();
        }

        void RemoveSelected()
        {
            ShareDef s = Selected();
            if (s == null) return;
            var answer = ThemedBox.Show(this,
                "Убрать «" + s.Name + "» из доступных планшету?" + Environment.NewLine
                    + "Файлы в самой папке останутся на месте.",
                AgentInfo.Product, MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (answer != DialogResult.Yes) return;
            ShareConfig config = FileSharing.Current();
            ShareDef target = config.Find(s.Id);
            if (target == null) return;
            config.Shares.Remove(target);
            // Без папок включённый доступ ничего не открывает: выключаем его, чтобы переключатель говорил правду.
            if (config.Shares.Count == 0) config.Enabled = false;
            FileSharing.Set(config);
            Log.Write("files: share removed " + s.Name);
            selectedId = null;
            RefreshAll();
        }
    }
}
