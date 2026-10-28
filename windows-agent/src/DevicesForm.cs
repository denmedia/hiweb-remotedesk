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
    sealed class DevicesForm : Form
    {
        readonly Func<string, bool> isConnected;
        readonly Action<string> onSwitch;
        readonly Action onChanged;
        readonly ListView list = new ListView();
        string selectedKey;

        /// <summary>[onSwitch] вызывается с идентификатором устройства, [onChanged] — после переименования или удаления.</summary>
        public DevicesForm(Func<string, bool> isConnected, Action<string> onSwitch, Action onChanged)
        {
            this.isConnected = isConnected;
            this.onSwitch = onSwitch;
            this.onChanged = onChanged;

            Text = "Устройства — " + AgentInfo.Product;
            StartPosition = FormStartPosition.CenterScreen;
            MinimizeBox = false;
            ShowIcon = false;
            ClientSize = new Size(600, 320);
            MinimumSize = new Size(480, 260);
            Font = SystemFonts.MessageBoxFont;

            list.View = View.Details;
            list.FullRowSelect = true;
            list.MultiSelect = false;
            list.HideSelection = false;
            list.Dock = DockStyle.Fill;
            list.Columns.Add("Устройство", 220);
            list.Columns.Add("Модель", 150);
            list.Columns.Add("Связь", 90);
            list.Columns.Add("Последнее подключение", 140);
            list.DoubleClick += delegate { SwitchSelected(); };
            list.SelectedIndexChanged += delegate
            {
                PairedDevice d = Selected();
                selectedKey = d == null ? null : d.Key;
                UpdateButtons();
            };

            var buttons = new FlowLayoutPanel();
            buttons.Dock = DockStyle.Bottom;
            buttons.FlowDirection = FlowDirection.LeftToRight;
            buttons.AutoSize = true;
            buttons.Padding = new Padding(8);
            buttons.WrapContents = false;

            Button switchButton = MakeButton("Переключиться", delegate { SwitchSelected(); });
            Button renameButton = MakeButton("Переименовать…", delegate { RenameSelected(); });
            Button removeButton = MakeButton("Удалить", delegate { RemoveSelected(); });
            Button closeButton = MakeButton("Закрыть", delegate { Close(); });
            buttons.Controls.Add(switchButton);
            buttons.Controls.Add(renameButton);
            buttons.Controls.Add(removeButton);
            buttons.Controls.Add(new Label { AutoSize = false, Width = 40 });
            buttons.Controls.Add(closeButton);

            var hint = new Label();
            hint.Dock = DockStyle.Bottom;
            hint.AutoSize = false;
            hint.Height = 40;
            hint.Padding = new Padding(10, 6, 10, 6);
            hint.Text = "В списке появляются устройства, которые подключались к этому ПК. Удаление убирает запись "
                + "на этом ПК; чтобы она вернулась, подключитесь к ПК с устройства снова.";

            Controls.Add(list);
            Controls.Add(hint);
            Controls.Add(buttons);
            switchButton.Name = "switchButton";
            renameButton.Name = "renameButton";
            removeButton.Name = "removeButton";
            AcceptButton = switchButton;
            RefreshList();
            UpdateButtons();
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
                    item.ForeColor = isConnected(d.Key) ? SystemColors.ControlText : SystemColors.GrayText;
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
            foreach (Control c in Controls)
            {
                var panel = c as FlowLayoutPanel;
                if (panel == null) continue;
                foreach (Control b in panel.Controls)
                {
                    if (b.Name == "switchButton" || b.Name == "renameButton" || b.Name == "removeButton") b.Enabled = has;
                }
            }
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
            var answer = MessageBox.Show(
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
    sealed class RenameDialog : Form
    {
        readonly TextBox box = new TextBox();

        RenameDialog(string title, string value)
        {
            Text = title;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition = FormStartPosition.CenterParent;
            MinimizeBox = false;
            MaximizeBox = false;
            ShowIcon = false;
            ClientSize = new Size(380, 110);
            Font = SystemFonts.MessageBoxFont;

            box.Text = value ?? "";
            box.SelectAll();
            box.Location = new Point(12, 20);
            box.Width = ClientSize.Width - 24;
            box.MaxLength = 40;
            box.Anchor = AnchorStyles.Left | AnchorStyles.Right | AnchorStyles.Top;

            var ok = new Button();
            ok.Text = "ОК";
            ok.DialogResult = DialogResult.OK;
            ok.AutoSize = true;
            ok.Location = new Point(ClientSize.Width - 170, 60);
            ok.Anchor = AnchorStyles.Right | AnchorStyles.Bottom;

            var cancel = new Button();
            cancel.Text = "Отмена";
            cancel.DialogResult = DialogResult.Cancel;
            cancel.AutoSize = true;
            cancel.Location = new Point(ClientSize.Width - 88, 60);
            cancel.Anchor = AnchorStyles.Right | AnchorStyles.Bottom;

            var hint = new Label();
            hint.Text = "Так устройство называется в меню агента.";
            hint.AutoSize = true;
            hint.Location = new Point(12, 46);

            Controls.Add(box);
            Controls.Add(hint);
            Controls.Add(ok);
            Controls.Add(cancel);
            AcceptButton = ok;
            CancelButton = cancel;
        }

        /// <summary>Новое имя или null, если пользователь отменил ввод.</summary>
        public static string Ask(IWin32Window owner, string title, string value)
        {
            using (var dialog = new RenameDialog(title, value))
            {
                return dialog.ShowDialog(owner) == DialogResult.OK ? dialog.box.Text.Trim() : null;
            }
        }
    }
}
