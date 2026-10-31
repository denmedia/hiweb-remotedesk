using System;
using System.Collections.Generic;
using System.Drawing;
using System.Windows.Forms;

namespace HiWebAgent
{
    /// <summary>
    /// Список устройств, которые подключались к этому ПК: видно, какое из них сейчас в сети (агент держит с ним
    /// связь по локальной сети), можно переключиться на любое, переименовать его в списке или удалить.
    /// Устройства заводятся только подключением к ПК — в этом окне их не добавить, зато можно убрать.
    /// </summary>
    sealed class DevicesForm : ThemedForm
    {
        readonly Func<string, bool> isConnected;
        readonly Action<string> onSwitch;
        readonly Action onChanged;
        readonly ThemedList list = new ThemedList();
        readonly PillButton switchButton;
        readonly PillButton renameButton;
        readonly PillButton removeButton;
        string selectedKey;

        /// <summary>[onSwitch] вызывается с идентификатором устройства, [onChanged] — после переименования или удаления.</summary>
        public DevicesForm(Func<string, bool> isConnected, Action<string> onSwitch, Action onChanged)
        {
            this.isConnected = isConnected;
            this.onSwitch = onSwitch;
            this.onChanged = onChanged;

            Text = "Устройства — " + AgentInfo.Product;
            StartPosition = FormStartPosition.CenterScreen;
            ClientSize = new Size(Ui.Px(this, 660), Ui.Px(this, 400));
            MinimumSize = new Size(Ui.Px(this, 540), Ui.Px(this, 320));
            Padding = new Padding(Ui.Px(this, 20), Ui.Px(this, 16), Ui.Px(this, 20), Ui.Px(this, 16));

            list.Dock = DockStyle.Fill;
            list.FillColumn = 0;
            list.Columns.Add("Устройство", Ui.Px(this, 220));
            list.Columns.Add("Модель", Ui.Px(this, 150));
            list.Columns.Add("Связь", Ui.Px(this, 90));
            list.Columns.Add("Последнее подключение", Ui.Px(this, 150));
            list.DoubleClick += delegate { SwitchSelected(); };
            list.SelectedIndexChanged += delegate
            {
                PairedDevice d = Selected();
                selectedKey = d == null ? null : d.Key;
                UpdateButtons();
            };

            var card = new Card();
            card.Dock = DockStyle.Fill;
            card.Controls.Add(list);

            var header = MakeLabel("Устройства", false);
            header.Font = new Font(Font.FontFamily, Font.Size * 1.65f, FontStyle.Bold, Font.Unit);
            header.Dock = DockStyle.Top;
            header.Height = Ui.Px(this, 36);

            var hint = MakeLabel("В списке появляются устройства, которые подключались к этому ПК. Удаление убирает запись "
                + "на этом ПК; чтобы она вернулась, подключитесь к ПК с устройства снова.", true);
            hint.Dock = DockStyle.Top;
            hint.Height = Ui.Px(this, 44);

            var bar = new ButtonBar();
            var pill = new Pill();
            switchButton = new PillButton("Переключиться", PillKind.Ghost, "\uE8AB", delegate { SwitchSelected(); });
            renameButton = new PillButton("Переименовать…", PillKind.Ghost, "\uE8AC", delegate { RenameSelected(); });
            switchButton.Margin = renameButton.Margin = new Padding(2, 0, 2, 0);
            pill.Controls.Add(switchButton);
            pill.Controls.Add(renameButton);
            removeButton = new PillButton("Удалить", PillKind.Danger, "\uE74D", delegate { RemoveSelected(); });
            removeButton.Margin = new Padding(Ui.Px(this, 10), 0, 0, 0);
            var closeButton = new PillButton("Закрыть", PillKind.Normal, null, delegate { Close(); });
            // Как у таблетки и «Удалить»: без поля сверху, иначе кнопка стоит ниже остальных.
            closeButton.Margin = new Padding(0);
            bar.LeftFlow.Controls.Add(pill);
            bar.LeftFlow.Controls.Add(removeButton);
            bar.RightFlow.Controls.Add(closeButton);

            // Порядок добавления задаёт порядок стыковки: последний добавленный прижимается к краю первым.
            Controls.Add(card);
            Controls.Add(Spacer(DockStyle.Bottom, 12));
            Controls.Add(bar);
            Controls.Add(Spacer(DockStyle.Top, 10));
            Controls.Add(hint);
            Controls.Add(header);
            AcceptButton = switchButton;
            CancelButton = closeButton;
            RefreshList();
            UpdateButtons();
        }

        Control Spacer(DockStyle dock, int height)
        {
            var p = new Panel();
            p.Dock = dock;
            p.Height = Ui.Px(this, height);
            p.BackColor = Color.Transparent;
            return p;
        }

        protected override void OnThemeChanged()
        {
            RefreshList();
        }

        /// <summary>Перечитывает список; выделение остаётся на прежнем устройстве, если оно ещё есть.</summary>
        public void RefreshList()
        {
            PairedDevice[] devices = PairedDevices.All();
            list.BeginUpdate();
            try
            {
                list.Items.Clear();
                foreach (PairedDevice d in devices)
                {
                    var item = new ListViewItem(d.Title);
                    item.SubItems.Add(d.Model ?? "");
                    item.SubItems.Add(isConnected(d.Key) ? "в сети" : "не в сети");
                    item.SubItems.Add(PairedDevice.TimeText(d.LastSeen));
                    item.Tag = d.Key;
                    item.ForeColor = isConnected(d.Key) ? Theme.P.Text : Theme.P.Muted;
                    list.Items.Add(item);
                }
            }
            finally
            {
                list.EndUpdate();
            }
            SelectKey(selectedKey);
            if (list.SelectedItems.Count == 0 && list.Items.Count == 1) list.Items[0].Selected = true;
            UpdateButtons();
        }

        void SelectKey(string key)
        {
            if (key == null) return;
            foreach (ListViewItem item in list.Items)
            {
                if (Convert.ToString(item.Tag) == key)
                {
                    item.Selected = true;
                    item.Focused = true;
                    return;
                }
            }
        }

        PairedDevice Selected()
        {
            if (list.SelectedItems.Count == 0) return null;
            return PairedDevices.Get(Convert.ToString(list.SelectedItems[0].Tag));
        }

        void UpdateButtons()
        {
            bool has = Selected() != null;
            switchButton.Enabled = renameButton.Enabled = removeButton.Enabled = has;
        }

        void SwitchSelected()
        {
            PairedDevice d = Selected();
            if (d == null) return;
            onSwitch(d.Key);
            Close();
        }

        void RenameSelected()
        {
            PairedDevice d = Selected();
            if (d == null) return;
            string name = RenameDialog.Ask(this, "Переименовать устройство", d.Title);
            if (name == null) return;
            PairedDevices.Rename(d.Key, name);
            selectedKey = d.Key;
            RefreshList();
            onChanged();
        }

        void RemoveSelected()
        {
            PairedDevice d = Selected();
            if (d == null) return;
            var answer = ThemedBox.Show(this,
                "Удалить устройство «" + d.Title + "» из списка?" + Environment.NewLine
                    + "К этому ПК оно сможет подключиться, но звать его командой «Переключиться на …» агент больше не будет.",
                AgentInfo.Product, MessageBoxButtons.YesNo, MessageBoxIcon.Question);
            if (answer != DialogResult.Yes) return;
            PairedDevices.Remove(d.Key);
            selectedKey = null;
            RefreshList();
            onChanged();
        }
    }

    /// <summary>Однострочный диалог ввода: имя устройства, не длиннее 40 символов.</summary>
    sealed class RenameDialog : ThemedForm
    {
        readonly ThemedInput input = new ThemedInput();

        RenameDialog(string title, string value)
        {
            Text = title;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition = FormStartPosition.CenterParent;
            MaximizeBox = false;
            int m = Ui.Px(this, 22);
            ClientSize = new Size(Ui.Px(this, 440), Ui.Px(this, 190));

            var hint = MakeLabel("Так устройство называется в меню агента.", true);
            hint.SetBounds(m, m, ClientSize.Width - m * 2, Font.Height + 4);

            input.Font = Font;
            input.Box.Text = value ?? "";
            input.Box.MaxLength = 40;
            input.SetBounds(m, hint.Bottom + Ui.Px(this, 10), ClientSize.Width - m * 2, input.Height);

            var ok = new PillButton("ОК", PillKind.Primary, null, null);
            ok.DialogResult = DialogResult.OK;
            var cancel = new PillButton("Отмена", PillKind.Normal, null, null);
            cancel.DialogResult = DialogResult.Cancel;
            ok.Width = cancel.Width = Math.Max(ok.Width, Ui.Px(this, 96));
            int y = ClientSize.Height - m - ok.Height;
            cancel.Location = new Point(ClientSize.Width - m - cancel.Width, y);
            ok.Location = new Point(cancel.Left - Ui.Px(this, 8) - ok.Width, y);

            Controls.Add(hint);
            Controls.Add(input);
            Controls.Add(ok);
            Controls.Add(cancel);
            AcceptButton = ok;
            CancelButton = cancel;
            Shown += delegate { input.Box.Focus(); input.Box.SelectAll(); };
        }

        /// <summary>Новое имя или null, если пользователь отменил ввод.</summary>
        public static string Ask(IWin32Window owner, string title, string value)
        {
            using (var dialog = new RenameDialog(title, value))
            {
                return dialog.ShowDialog(owner) == DialogResult.OK ? dialog.input.Box.Text.Trim() : null;
            }
        }
    }
}
