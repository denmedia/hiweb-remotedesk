using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Text;
using System.Runtime.InteropServices;
using System.Windows.Forms;
using Microsoft.Win32;

namespace HiWebAgent
{
    /// <summary>Цвета окна агента. Тёмная палитра — та же, что у приложения на планшете (Surface/Accent/DangerRed).</summary>
    sealed class Palette
    {
        public bool Dark;
        /// <summary>Фон окна.</summary>
        public Color Bg;
        /// <summary>Поверхность списков и карточек.</summary>
        public Color Card;
        /// <summary>Фон меню в трее.</summary>
        public Color Menu;
        /// <summary>Таблетка и обычные кнопки; при наведении и нажатии.</summary>
        public Color Fill, FillHover, FillDown;
        public Color Outline;
        public Color Text, Muted;
        public Color Accent, AccentHover, OnAccent;
        public Color Danger, DangerHover;
        public Color Ok, Warn;
        /// <summary>Выделенная строка списка.</summary>
        public Color Select;
    }

    /// <summary>
    /// Тема окон агента: светлая или тёмная по настройке Windows «Режим приложений по умолчанию»
    /// (<c>AppsUseLightTheme</c>). Смена настройки доходит до открытых окон событием <see cref="Changed"/>.
    /// </summary>
    static class Theme
    {
        static readonly Palette light = MakeLight();
        static readonly Palette dark = MakeDark();
        static bool isDark;
        static Control sync;

        /// <summary>Тема сменилась; вызывается в потоке интерфейса (после <see cref="Init"/>).</summary>
        public static event Action Changed;

        static Theme()
        {
            isDark = ReadDark();
            SystemEvents.UserPreferenceChanged += OnPreferenceChanged;
        }

        public static Palette P { get { return isDark ? dark : light; } }

        public static bool Dark { get { return isDark; } }

        /// <summary>
        /// Вызывать из потока интерфейса до показа окон: события системы приходят из чужого потока, и их нужно перенести сюда.
        /// Заодно все меню процесса получают оформление агента.
        /// </summary>
        public static void Init()
        {
            if (sync != null) return;
            sync = new Control();
            IntPtr handle = sync.Handle;
            ToolStripManager.Renderer = new ThemedMenuRenderer();
        }

        /// <summary>Принудительно выбрать тему (проверочная обвязка).</summary>
        public static void Force(bool useDark)
        {
            isDark = useDark;
            Raise();
        }

        static void OnPreferenceChanged(object sender, UserPreferenceChangedEventArgs e)
        {
            if (e.Category != UserPreferenceCategory.General && e.Category != UserPreferenceCategory.Color) return;
            bool now = ReadDark();
            if (now == isDark) return;
            isDark = now;
            Control target = sync;
            if (target != null && target.IsHandleCreated) target.BeginInvoke(new Action(Raise));
            else Raise();
        }

        static void Raise()
        {
            Action handler = Changed;
            if (handler != null) handler();
        }

        static bool ReadDark()
        {
            try
            {
                using (var k = Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"))
                {
                    object v = k == null ? null : k.GetValue("AppsUseLightTheme");
                    return v is int && (int)v == 0;
                }
            }
            catch (Exception)
            {
                return false;
            }
        }

        static Palette MakeDark()
        {
            var p = new Palette();
            p.Dark = true;
            p.Bg = Color.FromArgb(0x0D, 0x1B, 0x33);
            p.Card = Color.FromArgb(0x11, 0x23, 0x42);
            p.Menu = Color.FromArgb(0x11, 0x23, 0x42);
            p.Fill = Color.FromArgb(0x14, 0x28, 0x4B);
            p.FillHover = Color.FromArgb(0x1D, 0x38, 0x66);
            p.FillDown = Color.FromArgb(0x25, 0x45, 0x7C);
            p.Outline = Color.FromArgb(0x1D, 0x31, 0x57);
            p.Text = Color.White;
            p.Muted = Color.FromArgb(0x8D, 0x9B, 0xB8);
            p.Accent = Color.FromArgb(0x1F, 0x6B, 0xF2);
            p.AccentHover = Color.FromArgb(0x3B, 0x80, 0xF5);
            p.OnAccent = Color.White;
            p.Danger = Color.FromArgb(0xE5, 0x57, 0x5F);
            p.DangerHover = Color.FromArgb(0xEC, 0x72, 0x79);
            p.Ok = Color.FromArgb(0x4C, 0xC3, 0x84);
            p.Warn = Color.FromArgb(0xF0, 0xB2, 0x4B);
            p.Select = Color.FromArgb(0x1C, 0x3A, 0x72);
            return p;
        }

        static Palette MakeLight()
        {
            var p = new Palette();
            p.Dark = false;
            p.Bg = Color.White;
            p.Card = Color.FromArgb(0xF5, 0xF7, 0xFC);
            p.Menu = Color.White;
            p.Fill = Color.FromArgb(0xE9, 0xEE, 0xF8);
            p.FillHover = Color.FromArgb(0xDB, 0xE4, 0xF5);
            p.FillDown = Color.FromArgb(0xC9, 0xD7, 0xEF);
            p.Outline = Color.FromArgb(0xD8, 0xE0, 0xEE);
            p.Text = Color.FromArgb(0x0D, 0x1B, 0x33);
            p.Muted = Color.FromArgb(0x5E, 0x6E, 0x8F);
            p.Accent = Color.FromArgb(0x1F, 0x6B, 0xF2);
            p.AccentHover = Color.FromArgb(0x17, 0x5B, 0xD8);
            p.OnAccent = Color.White;
            p.Danger = Color.FromArgb(0xD9, 0x43, 0x4C);
            p.DangerHover = Color.FromArgb(0xC3, 0x35, 0x3E);
            p.Ok = Color.FromArgb(0x1E, 0x8E, 0x4E);
            p.Warn = Color.FromArgb(0xC7, 0x77, 0x00);
            p.Select = Color.FromArgb(0xD6, 0xE4, 0xFC);
            return p;
        }
    }

    /// <summary>Рисование и системные вызовы, общие для всех окон.</summary>
    static class Ui
    {
        [DllImport("dwmapi.dll")]
        static extern int DwmSetWindowAttribute(IntPtr hwnd, int attribute, ref int value, int size);

        static string iconFamily;
        static bool iconFamilyChecked;

        public static bool Windows11
        {
            get { return Environment.OSVersion.Version.Build >= 22000; }
        }

        /// <summary>Во сколько раз увеличить шрифт окон (проверочная обвязка имитирует масштаб экрана); в работе 1.</summary>
        public static double FontScale = 1.0;

        /// <summary>Шрифт окон: системный шрифт сообщений (уже учитывает масштаб экрана и размер текста в Windows).</summary>
        public static Font BaseFont()
        {
            Font system = SystemFonts.MessageBoxFont;
            if (Math.Abs(FontScale - 1.0) < 0.01) return system;
            var scaled = new Font(system.FontFamily, (float)(system.Size * FontScale), system.Style, system.Unit);
            system.Dispose();
            return scaled;
        }

        /// <summary>
        /// Масштаб интерфейса: высота системного шрифта к 15 пикселям (Segoe UI 9 пт при 100%). Размеры окон считаются от шрифта,
        /// а не от DeviceDpi: при масштабе экрана шрифт крупнее, а DeviceDpi у процесса остаётся 96, и окно не вмещало содержимое.
        /// </summary>
        public static double Scale
        {
            get
            {
                using (Font f = BaseFont()) return Math.Max(1.0, f.Height / 15.0);
            }
        }

        /// <summary>Пиксели окна для размера [value] при 100% масштабе.</summary>
        public static int Px(Control c, int value)
        {
            return (int)Math.Round(value * Scale);
        }

        /// <summary>Шрифт значков Windows (Segoe Fluent Icons или Segoe MDL2 Assets); null, если ни одного нет.</summary>
        public static string IconFamily
        {
            get
            {
                if (iconFamilyChecked) return iconFamily;
                iconFamilyChecked = true;
                try
                {
                    using (var fonts = new InstalledFontCollection())
                    {
                        foreach (string name in new[] { "Segoe Fluent Icons", "Segoe MDL2 Assets" })
                        {
                            foreach (FontFamily f in fonts.Families)
                            {
                                if (f.Name == name) { iconFamily = name; return iconFamily; }
                            }
                        }
                    }
                }
                catch (Exception) { }
                return iconFamily;
            }
        }

        public static GraphicsPath Rounded(RectangleF r, float radius)
        {
            var path = new GraphicsPath();
            float d = Math.Min(radius * 2, Math.Min(r.Width, r.Height));
            if (d <= 0)
            {
                path.AddRectangle(r);
                return path;
            }
            path.AddArc(r.Left, r.Top, d, d, 180, 90);
            path.AddArc(r.Right - d, r.Top, d, d, 270, 90);
            path.AddArc(r.Right - d, r.Bottom - d, d, d, 0, 90);
            path.AddArc(r.Left, r.Bottom - d, d, d, 90, 90);
            path.CloseFigure();
            return path;
        }

        public static Color Mix(Color a, Color b, double t)
        {
            return Color.FromArgb(
                (int)Math.Round(a.R + (b.R - a.R) * t),
                (int)Math.Round(a.G + (b.G - a.G) * t),
                (int)Math.Round(a.B + (b.B - a.B) * t));
        }

        static int ColorRef(Color c)
        {
            return c.R | (c.G << 8) | (c.B << 16);
        }

        /// <summary>Заголовок окна под тему: тёмный режим, на Windows 11 ещё цвет заголовка и рамки.</summary>
        public static void StyleWindow(IntPtr handle)
        {
            try
            {
                Palette p = Theme.P;
                int dark = p.Dark ? 1 : 0;
                DwmSetWindowAttribute(handle, 19, ref dark, 4);
                DwmSetWindowAttribute(handle, 20, ref dark, 4);
                if (Windows11)
                {
                    int caption = ColorRef(p.Bg);
                    int text = ColorRef(p.Text);
                    int border = ColorRef(p.Outline);
                    DwmSetWindowAttribute(handle, 35, ref caption, 4);
                    DwmSetWindowAttribute(handle, 36, ref text, 4);
                    DwmSetWindowAttribute(handle, 34, ref border, 4);
                }
            }
            catch (Exception)
            {
            }
        }

        /// <summary>Скругляет окно меню: на Windows 11 средствами системы (со сглаживанием), раньше — областью.</summary>
        public static void RoundWindow(Control window, int radius)
        {
            try
            {
                if (Windows11)
                {
                    int round = 2;
                    DwmSetWindowAttribute(window.Handle, 33, ref round, 4);
                }
                else if (window.Width > 0 && window.Height > 0)
                {
                    using (var path = Rounded(new RectangleF(0, 0, window.Width, window.Height), radius))
                    {
                        window.Region = new Region(path);
                    }
                }
            }
            catch (Exception)
            {
            }
        }

        public static TextFormatFlags TextFlags(bool center)
        {
            return TextFormatFlags.NoPadding | TextFormatFlags.SingleLine | TextFormatFlags.NoPrefix
                | TextFormatFlags.VerticalCenter | TextFormatFlags.EndEllipsis
                | (center ? TextFormatFlags.HorizontalCenter : TextFormatFlags.Left);
        }

        /// <summary>Оформляет меню значка в трее.</summary>
        public static void StyleMenu(ContextMenuStrip menu)
        {
            menu.Renderer = new ThemedMenuRenderer();
            menu.ShowImageMargin = false;
            menu.ShowCheckMargin = true;
            menu.Padding = new Padding(4);
            menu.Font = new Font(SystemFonts.MessageBoxFont.FontFamily, 9.75f, FontStyle.Regular, GraphicsUnit.Point);
            menu.Opening += delegate { PadItems(menu.Items); };
            menu.Opened += delegate { RoundWindow(menu, 10); };
        }

        static void PadItems(ToolStripItemCollection items)
        {
            foreach (ToolStripItem item in items)
            {
                if (item is ToolStripSeparator) continue;
                item.Padding = new Padding(4, 5, 4, 5);
                var parent = item as ToolStripMenuItem;
                if (parent != null && parent.HasDropDownItems)
                {
                    parent.DropDown.Font = parent.Font;
                    PadItems(parent.DropDownItems);
                }
            }
        }
    }

    /// <summary>Меню значка в трее: скруглённая подсветка пункта, цвета темы, без полосы значков.</summary>
    sealed class ThemedMenuRenderer : ToolStripProfessionalRenderer
    {
        public ThemedMenuRenderer()
        {
            RoundedEdges = false;
        }

        protected override void OnRenderToolStripBackground(ToolStripRenderEventArgs e)
        {
            e.Graphics.Clear(Theme.P.Menu);
        }

        protected override void OnRenderToolStripBorder(ToolStripRenderEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var r = new RectangleF(0.5f, 0.5f, e.ToolStrip.Width - 1, e.ToolStrip.Height - 1);
            using (GraphicsPath path = Ui.Rounded(r, 9))
            using (var pen = new Pen(Theme.P.Outline))
            {
                g.DrawPath(pen, path);
            }
        }

        protected override void OnRenderImageMargin(ToolStripRenderEventArgs e)
        {
        }

        protected override void OnRenderMenuItemBackground(ToolStripItemRenderEventArgs e)
        {
            if (!e.Item.Enabled || !(e.Item.Selected || e.Item.Pressed)) return;
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var r = new RectangleF(2, 1, e.Item.Width - 4, e.Item.Height - 2);
            using (GraphicsPath path = Ui.Rounded(r, 7))
            using (var brush = new SolidBrush(Theme.P.FillHover))
            {
                g.FillPath(brush, path);
            }
        }

        protected override void OnRenderItemText(ToolStripItemTextRenderEventArgs e)
        {
            e.TextColor = e.Item.Enabled ? Theme.P.Text : Theme.P.Muted;
            base.OnRenderItemText(e);
        }

        protected override void OnRenderSeparator(ToolStripSeparatorRenderEventArgs e)
        {
            int y = e.Item.Height / 2;
            using (var pen = new Pen(Theme.P.Outline))
            {
                e.Graphics.DrawLine(pen, 12, y, e.Item.Width - 12, y);
            }
        }

        protected override void OnRenderItemCheck(ToolStripItemImageRenderEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            Rectangle r = e.ImageRectangle;
            float cx = r.Left + r.Width / 2f, cy = r.Top + r.Height / 2f;
            using (var pen = new Pen(Theme.P.Accent, 2f))
            {
                pen.StartCap = LineCap.Round;
                pen.EndCap = LineCap.Round;
                pen.LineJoin = LineJoin.Round;
                g.DrawLines(pen, new[] { new PointF(cx - 4.5f, cy), new PointF(cx - 1.5f, cy + 3.5f), new PointF(cx + 4.5f, cy - 3.5f) });
            }
        }

        protected override void OnRenderArrow(ToolStripArrowRenderEventArgs e)
        {
            e.ArrowColor = Theme.P.Muted;
            base.OnRenderArrow(e);
        }
    }

    enum PillKind { Normal, Primary, Danger, Ghost }

    /// <summary>
    /// Кнопка в стиле панели сеанса на планшете: полностью скруглённая «таблетка» (Normal — цвет таблетки,
    /// Primary — акцент, Danger — красная, Ghost — без фона, подсвечивается при наведении, для кнопок внутри <see cref="Pill"/>).
    /// <see cref="Square"/> — красная квадратная кнопка с одним значком, как «Отключиться» в панели сеанса.
    /// </summary>
    sealed class PillButton : Control, IButtonControl
    {
        PillKind kind = PillKind.Normal;
        string glyph;
        bool square;
        bool hover, down, isDefault;
        DialogResult dialogResult = DialogResult.None;

        public PillButton()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer
                | ControlStyles.ResizeRedraw | ControlStyles.SupportsTransparentBackColor | ControlStyles.Selectable, true);
            BackColor = Color.Transparent;
            TabStop = true;
            Cursor = Cursors.Hand;
            Margin = new Padding(4);
        }

        public PillButton(string text, PillKind kind, string glyph, EventHandler click) : this()
        {
            Text = text;
            this.kind = kind;
            this.glyph = glyph;
            if (click != null) Click += click;
            UpdateSize();
        }

        public PillKind Kind
        {
            get { return kind; }
            set { kind = value; Invalidate(); }
        }

        /// <summary>Значок из шрифта Segoe Fluent Icons / MDL2 Assets, например "".</summary>
        public string Glyph
        {
            get { return glyph; }
            set { glyph = value; UpdateSize(); Invalidate(); }
        }

        /// <summary>Квадрат только со значком (без текста).</summary>
        public bool Square
        {
            get { return square; }
            set { square = value; UpdateSize(); Invalidate(); }
        }

        public DialogResult DialogResult
        {
            get { return dialogResult; }
            set { dialogResult = value; }
        }

        public void NotifyDefault(bool value)
        {
            isDefault = value;
            Invalidate();
        }

        public void PerformClick()
        {
            if (Enabled) OnClick(EventArgs.Empty);
        }

        bool HasGlyph
        {
            get { return !string.IsNullOrEmpty(glyph) && Ui.IconFamily != null; }
        }

        public override Size GetPreferredSize(Size proposedSize)
        {
            int unit = Font.Height;
            int h = (int)Math.Round(unit * 2.5);
            if (square) return new Size(h, h);
            string text = Text ?? "";
            int w = text.Length == 0 ? 0 : TextRenderer.MeasureText(text, Font, new Size(int.MaxValue, h),
                TextFormatFlags.NoPadding | TextFormatFlags.SingleLine | TextFormatFlags.NoPrefix).Width;
            int gw = HasGlyph ? (int)Math.Round(unit * 1.3) + (text.Length > 0 ? unit / 2 : 0) : 0;
            return new Size(w + gw + (int)Math.Round(unit * 1.2) * 2, h);
        }

        void UpdateSize()
        {
            Size = GetPreferredSize(Size.Empty);
        }

        protected override void OnTextChanged(EventArgs e)
        {
            base.OnTextChanged(e);
            UpdateSize();
            Invalidate();
        }

        protected override void OnFontChanged(EventArgs e)
        {
            base.OnFontChanged(e);
            UpdateSize();
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            UpdateSize();
        }

        protected override void OnEnabledChanged(EventArgs e)
        {
            base.OnEnabledChanged(e);
            if (!Enabled) { hover = false; down = false; }
            Cursor = Enabled ? Cursors.Hand : Cursors.Default;
            Invalidate();
        }

        protected override void OnMouseEnter(EventArgs e) { hover = true; Invalidate(); base.OnMouseEnter(e); }
        protected override void OnMouseLeave(EventArgs e) { hover = false; down = false; Invalidate(); base.OnMouseLeave(e); }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            if (e.Button == MouseButtons.Left) { down = true; Focus(); Invalidate(); }
            base.OnMouseDown(e);
        }

        protected override void OnMouseUp(MouseEventArgs e)
        {
            down = false;
            Invalidate();
            base.OnMouseUp(e);
        }

        protected override void OnClick(EventArgs e)
        {
            if (dialogResult != DialogResult.None)
            {
                Form form = FindForm();
                if (form != null) form.DialogResult = dialogResult;
            }
            base.OnClick(e);
        }

        protected override bool IsInputKey(Keys keyData)
        {
            return keyData == Keys.Space || base.IsInputKey(keyData);
        }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Space) { down = true; Invalidate(); }
            base.OnKeyDown(e);
        }

        protected override void OnKeyUp(KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Space && down) { down = false; Invalidate(); PerformClick(); }
            base.OnKeyUp(e);
        }

        protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
        protected override void OnLostFocus(EventArgs e) { down = false; Invalidate(); base.OnLostFocus(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            Palette p = Theme.P;

            Color fill, fore;
            switch (kind)
            {
                case PillKind.Primary:
                    fill = down ? Ui.Mix(p.Accent, Color.Black, 0.2) : hover ? p.AccentHover : p.Accent;
                    fore = p.OnAccent;
                    break;
                case PillKind.Danger:
                    fill = down ? Ui.Mix(p.Danger, Color.Black, 0.2) : hover ? p.DangerHover : p.Danger;
                    fore = Color.White;
                    break;
                case PillKind.Ghost:
                    fill = down ? p.FillDown : hover ? p.FillHover : Color.Transparent;
                    fore = p.Text;
                    break;
                default:
                    fill = down ? p.FillDown : hover ? p.FillHover : p.Fill;
                    fore = p.Text;
                    break;
            }
            if (!Enabled)
            {
                fill = fill == Color.Transparent ? fill : Ui.Mix(fill, p.Bg, 0.6);
                fore = Ui.Mix(fore, p.Bg, 0.6);
            }

            // Кнопка внутри таблетки (Ghost) ниже самой таблетки: подсветка отступает сверху и снизу, а сама таблетка
            // по высоте равна обычным кнопкам рядом.
            float inset = kind == PillKind.Ghost ? Font.Height / 6f : 0f;
            var rect = new RectangleF(0.5f, 0.5f + inset, Width - 1, Height - 1 - inset * 2);
            float radius = square ? Font.Height * 0.95f : rect.Height / 2f;
            if (fill != Color.Transparent)
            {
                using (GraphicsPath path = Ui.Rounded(rect, radius))
                using (var brush = new SolidBrush(fill))
                {
                    g.FillPath(brush, path);
                }
            }
            if (Focused && ShowFocusCues || isDefault && Enabled && kind == PillKind.Normal)
            {
                var inner = new RectangleF(rect.X + 1.5f, rect.Y + 1.5f, rect.Width - 3, rect.Height - 3);
                using (GraphicsPath path = Ui.Rounded(inner, Math.Max(2, radius - 1.5f)))
                using (var pen = new Pen(Focused && ShowFocusCues ? p.Accent : p.Outline, 2f))
                {
                    g.DrawPath(pen, path);
                }
            }

            // Содержимое: значок и текст по центру.
            string text = Text ?? "";
            Color back = fill == Color.Transparent ? Color.Transparent : fill;
            Rectangle area = new Rectangle(0, 0, Width, Height);
            if (square)
            {
                DrawGlyph(g, area, fore, Font.Size * 1.5f, back);
                return;
            }
            int unit = Font.Height;
            int gw = HasGlyph ? (int)Math.Round(unit * 1.3) : 0;
            int gap = HasGlyph && text.Length > 0 ? unit / 2 : 0;
            int tw = text.Length == 0 ? 0 : TextRenderer.MeasureText(text, Font, new Size(int.MaxValue, Height), TextFormatFlags.NoPadding | TextFormatFlags.SingleLine | TextFormatFlags.NoPrefix).Width;
            int x = Math.Max(0, (Width - (gw + gap + tw)) / 2);
            if (gw > 0) DrawGlyph(g, new Rectangle(x, 0, gw, Height), fore, Font.Size * 1.1f, back);
            if (tw > 0) TextRenderer.DrawText(g, text, Font, new Rectangle(x + gw + gap, 0, Width - x - gw - gap, Height), fore, back, Ui.TextFlags(false));
        }

        void DrawGlyph(Graphics g, Rectangle area, Color fore, float size, Color back)
        {
            if (!HasGlyph) return;
            using (var font = new Font(Ui.IconFamily, size, FontStyle.Regular, GraphicsUnit.Point))
            {
                TextRenderer.DrawText(g, glyph, font, area, fore, back, Ui.TextFlags(true) & ~TextFormatFlags.EndEllipsis);
            }
        }
    }

    /// <summary>Таблетка-контейнер, как в панели сеанса: в ней лежат кнопки <see cref="PillKind.Ghost"/>.</summary>
    sealed class Pill : FlowLayoutPanel
    {
        public Pill()
        {
            SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.ResizeRedraw | ControlStyles.OptimizedDoubleBuffer, true);
            BackColor = Color.Transparent;
            AutoSize = true;
            WrapContents = false;
            FlowDirection = FlowDirection.LeftToRight;
            Margin = new Padding(0);
        }

        protected override void OnFontChanged(EventArgs e)
        {
            base.OnFontChanged(e);
            // Только по бокам: высота таблетки равна высоте кнопки, чтобы стоять в ряду с обычными кнопками вровень.
            Padding = new Padding(Font.Height / 3, 0, Font.Height / 3, 0);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var rect = new RectangleF(0.5f, 0.5f, Width - 1, Height - 1);
            using (GraphicsPath path = Ui.Rounded(rect, Height / 2f))
            using (var brush = new SolidBrush(Theme.P.Fill))
            {
                g.FillPath(brush, path);
            }
        }
    }

    /// <summary>Скруглённая карточка под список или группу элементов.</summary>
    sealed class Card : Panel
    {
        public Card()
        {
            SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.ResizeRedraw | ControlStyles.OptimizedDoubleBuffer, true);
            BackColor = Color.Transparent;
            Padding = new Padding(6);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var rect = new RectangleF(0.5f, 0.5f, Width - 1, Height - 1);
            using (GraphicsPath path = Ui.Rounded(rect, Font.Height * 0.9f))
            using (var brush = new SolidBrush(Theme.P.Card))
            using (var pen = new Pen(Theme.P.Outline))
            {
                g.FillPath(brush, path);
                g.DrawPath(pen, path);
            }
        }
    }

    /// <summary>Переключатель вкладок в виде таблетки: выбранный сегмент залит акцентом (как включённая кнопка панели сеанса).</summary>
    sealed class SegmentTabs : Control
    {
        string[] items = new string[0];
        int selected;
        int hover = -1;

        public event EventHandler SelectedIndexChanged;

        public SegmentTabs()
        {
            SetStyle(ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint | ControlStyles.OptimizedDoubleBuffer
                | ControlStyles.ResizeRedraw | ControlStyles.SupportsTransparentBackColor | ControlStyles.Selectable, true);
            BackColor = Color.Transparent;
            TabStop = true;
            Cursor = Cursors.Hand;
        }

        public string[] Items
        {
            get { return items; }
            set { items = value ?? new string[0]; UpdateSize(); Invalidate(); }
        }

        public int SelectedIndex
        {
            get { return selected; }
            set
            {
                if (value < 0 || value >= items.Length || value == selected) return;
                selected = value;
                Invalidate();
                EventHandler handler = SelectedIndexChanged;
                if (handler != null) handler(this, EventArgs.Empty);
            }
        }

        int Inset { get { return Font.Height / 3; } }

        int SegmentWidth(int i)
        {
            return TextRenderer.MeasureText(items[i], Font, new Size(int.MaxValue, Height), TextFormatFlags.NoPadding | TextFormatFlags.SingleLine | TextFormatFlags.NoPrefix).Width
                + (int)Math.Round(Font.Height * 2.2);
        }

        void UpdateSize()
        {
            int w = Inset * 2;
            for (int i = 0; i < items.Length; i++) w += SegmentWidth(i);
            Size = new Size(w, (int)Math.Round(Font.Height * 2.5) + Inset);
        }

        protected override void OnFontChanged(EventArgs e)
        {
            base.OnFontChanged(e);
            UpdateSize();
        }

        Rectangle SegmentRect(int i)
        {
            int x = Inset;
            for (int k = 0; k < i; k++) x += SegmentWidth(k);
            return new Rectangle(x, Inset, SegmentWidth(i), Height - Inset * 2);
        }

        int HitTest(Point pt)
        {
            for (int i = 0; i < items.Length; i++) if (SegmentRect(i).Contains(pt)) return i;
            return -1;
        }

        protected override void OnMouseMove(MouseEventArgs e)
        {
            int h = HitTest(e.Location);
            if (h != hover) { hover = h; Invalidate(); }
            base.OnMouseMove(e);
        }

        protected override void OnMouseLeave(EventArgs e)
        {
            hover = -1;
            Invalidate();
            base.OnMouseLeave(e);
        }

        protected override void OnMouseDown(MouseEventArgs e)
        {
            int h = HitTest(e.Location);
            if (h >= 0) { Focus(); SelectedIndex = h; }
            base.OnMouseDown(e);
        }

        protected override bool IsInputKey(Keys keyData)
        {
            return keyData == Keys.Left || keyData == Keys.Right || base.IsInputKey(keyData);
        }

        protected override void OnKeyDown(KeyEventArgs e)
        {
            if (e.KeyCode == Keys.Left) SelectedIndex = selected - 1;
            else if (e.KeyCode == Keys.Right) SelectedIndex = selected + 1;
            base.OnKeyDown(e);
        }

        protected override void OnGotFocus(EventArgs e) { Invalidate(); base.OnGotFocus(e); }
        protected override void OnLostFocus(EventArgs e) { Invalidate(); base.OnLostFocus(e); }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            Palette p = Theme.P;
            var rect = new RectangleF(0.5f, 0.5f, Width - 1, Height - 1);
            using (GraphicsPath path = Ui.Rounded(rect, Height / 2f))
            using (var brush = new SolidBrush(p.Fill))
            {
                g.FillPath(brush, path);
            }
            if (Focused && ShowFocusCues)
            {
                using (GraphicsPath path = Ui.Rounded(new RectangleF(1.5f, 1.5f, Width - 3, Height - 3), Height / 2f - 1.5f))
                using (var pen = new Pen(p.Accent, 2f))
                {
                    g.DrawPath(pen, path);
                }
            }
            for (int i = 0; i < items.Length; i++)
            {
                Rectangle r = SegmentRect(i);
                bool on = i == selected;
                if (on)
                {
                    using (GraphicsPath path = Ui.Rounded(r, r.Height / 2f))
                    using (var brush = new SolidBrush(p.Accent))
                    {
                        g.FillPath(brush, path);
                    }
                }
                else if (i == hover)
                {
                    using (GraphicsPath path = Ui.Rounded(r, r.Height / 2f))
                    using (var brush = new SolidBrush(p.FillHover))
                    {
                        g.FillPath(brush, path);
                    }
                }
                TextRenderer.DrawText(g, items[i], Font, r, on ? p.OnAccent : p.Text, on ? p.Accent : (i == hover ? p.FillHover : p.Fill), Ui.TextFlags(true));
            }
        }
    }

    /// <summary>Поле ввода в скруглённой рамке; рамка подсвечивается акцентом, пока в поле фокус.</summary>
    sealed class ThemedInput : Control
    {
        public readonly TextBox Box = new TextBox();

        public ThemedInput()
        {
            SetStyle(ControlStyles.SupportsTransparentBackColor | ControlStyles.ResizeRedraw | ControlStyles.OptimizedDoubleBuffer | ControlStyles.UserPaint | ControlStyles.AllPaintingInWmPaint, true);
            BackColor = Color.Transparent;
            Box.BorderStyle = BorderStyle.None;
            Box.GotFocus += delegate { Invalidate(); };
            Box.LostFocus += delegate { Invalidate(); };
            Controls.Add(Box);
            ApplyTheme();
        }

        public void ApplyTheme()
        {
            Box.BackColor = Theme.P.Card;
            Box.ForeColor = Theme.P.Text;
            Invalidate();
        }

        protected override void OnFontChanged(EventArgs e)
        {
            base.OnFontChanged(e);
            Box.Font = Font;
            Height = (int)Math.Round(Font.Height * 2.4);
            LayoutBox();
        }

        protected override void OnResize(EventArgs e)
        {
            base.OnResize(e);
            LayoutBox();
        }

        void LayoutBox()
        {
            int pad = (int)Math.Round(Font.Height * 0.9);
            Box.Location = new Point(pad, (Height - Box.Height) / 2);
            Box.Width = Math.Max(10, Width - pad * 2);
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var rect = new RectangleF(0.5f, 0.5f, Width - 1, Height - 1);
            using (GraphicsPath path = Ui.Rounded(rect, Height / 2f))
            using (var brush = new SolidBrush(Theme.P.Card))
            using (var pen = new Pen(Box.Focused ? Theme.P.Accent : Theme.P.Outline, Box.Focused ? 2f : 1f))
            {
                g.FillPath(brush, path);
                g.DrawPath(pen, path);
            }
        }
    }

    /// <summary>Список в стиле окна: строки со скруглённым выделением, заголовок без системной рамки, цвета темы.</summary>
    sealed class ThemedList : ListView
    {
        bool fitting;

        /// <summary>Колонка, которая занимает свободную ширину (чтобы не появлялась горизонтальная прокрутка); -1 — последняя.</summary>
        public int FillColumn = -1;

        public ThemedList()
        {
            View = View.Details;
            FullRowSelect = true;
            MultiSelect = false;
            HideSelection = false;
            BorderStyle = BorderStyle.None;
            HeaderStyle = ColumnHeaderStyle.Nonclickable;
            OwnerDraw = true;
            DoubleBuffered = true;
            ApplyTheme();
        }

        public void ApplyTheme()
        {
            BackColor = Theme.P.Card;
            ForeColor = Theme.P.Text;
            Invalidate();
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            SetRowHeight();
        }

        protected override void OnFontChanged(EventArgs e)
        {
            base.OnFontChanged(e);
            if (IsHandleCreated) SetRowHeight();
        }

        protected override void OnClientSizeChanged(EventArgs e)
        {
            base.OnClientSizeChanged(e);
            FitColumns();
        }

        public void FitColumns()
        {
            if (fitting || Columns.Count == 0) return;
            fitting = true;
            try
            {
                int fill = FillColumn >= 0 && FillColumn < Columns.Count ? FillColumn : Columns.Count - 1;
                int others = 0;
                for (int i = 0; i < Columns.Count; i++) if (i != fill) others += Columns[i].Width;
                int width = Math.Max(Ui.Px(this, 120), ClientSize.Width - others - 1);
                if (Columns[fill].Width != width) Columns[fill].Width = width;
            }
            finally
            {
                fitting = false;
            }
        }

        /// <summary>Высоту строки списка задаёт только картинка в ImageList: ставим пустую нужной высоты.</summary>
        void SetRowHeight()
        {
            ImageList old = SmallImageList;
            var list = new ImageList();
            list.ImageSize = new Size(1, (int)Math.Round(Font.Height * 2.4));
            SmallImageList = list;
            if (old != null) old.Dispose();
        }

        protected override void OnDrawColumnHeader(DrawListViewColumnHeaderEventArgs e)
        {
            Palette p = Theme.P;
            using (var brush = new SolidBrush(p.Card))
            {
                e.Graphics.FillRectangle(brush, e.Bounds);
            }
            using (var pen = new Pen(p.Outline))
            {
                e.Graphics.DrawLine(pen, e.Bounds.Left, e.Bounds.Bottom - 1, e.Bounds.Right, e.Bounds.Bottom - 1);
            }
            var r = new Rectangle(e.Bounds.Left + 10, e.Bounds.Top, e.Bounds.Width - 12, e.Bounds.Height - 1);
            TextRenderer.DrawText(e.Graphics, e.Header.Text, Font, r, p.Muted, Color.Transparent, Ui.TextFlags(false));
        }

        protected override void OnDrawItem(DrawListViewItemEventArgs e)
        {
            if (!e.Item.Selected) return;
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            var row = new RectangleF(3, e.Bounds.Top + 1, ClientSize.Width - 7, e.Bounds.Height - 2);
            using (GraphicsPath path = Ui.Rounded(row, Font.Height * 0.7f))
            using (var brush = new SolidBrush(Theme.P.Select))
            {
                g.FillPath(brush, path);
            }
        }

        protected override void OnDrawSubItem(DrawListViewSubItemEventArgs e)
        {
            var r = new Rectangle(e.Bounds.Left + 10, e.Bounds.Top, Math.Max(0, e.Bounds.Width - 14), e.Bounds.Height);
            TextRenderer.DrawText(e.Graphics, e.SubItem.Text, Font, r, e.Item.ForeColor, Color.Transparent, Ui.TextFlags(false));
        }
    }

    /// <summary>Основа окон агента: тема Windows (светлая/тёмная, смена на лету), заголовок окна под тему, шрифт системы.</summary>
    class ThemedForm : Form
    {
        readonly Action themeHandler;

        protected ThemedForm()
        {
            Theme.Init();
            Font = Ui.BaseFont();
            ShowIcon = false;
            MinimizeBox = false;
            DoubleBuffered = true;
            BackColor = Theme.P.Bg;
            ForeColor = Theme.P.Text;
            themeHandler = delegate { if (!IsDisposed) ApplyTheme(); };
            Theme.Changed += themeHandler;
        }

        protected override void OnHandleCreated(EventArgs e)
        {
            base.OnHandleCreated(e);
            Ui.StyleWindow(Handle);
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing) Theme.Changed -= themeHandler;
            base.Dispose(disposing);
        }

        /// <summary>Применяет цвета текущей темы ко всему окну; подписи с Tag "muted" — приглушённые.</summary>
        public void ApplyTheme()
        {
            BackColor = Theme.P.Bg;
            ForeColor = Theme.P.Text;
            if (IsHandleCreated) Ui.StyleWindow(Handle);
            Recolor(this);
            OnThemeChanged();
            Invalidate(true);
        }

        /// <summary>Окно перекрашивает то, что цвет которого задаёт само (метки состояния, строки списков).</summary>
        protected virtual void OnThemeChanged()
        {
        }

        static void Recolor(Control parent)
        {
            foreach (Control c in parent.Controls)
            {
                var label = c as Label;
                if (label != null)
                {
                    label.ForeColor = "muted".Equals(label.Tag as string) ? Theme.P.Muted : Theme.P.Text;
                }
                var list = c as ThemedList;
                if (list != null) list.ApplyTheme();
                var input = c as ThemedInput;
                if (input != null) input.ApplyTheme();
                if (!(c is ThemedInput)) Recolor(c);
                c.Invalidate();
            }
        }

        /// <summary>Подпись окна: приглушённая, если [muted].</summary>
        protected static Label MakeLabel(string text, bool muted)
        {
            var label = new Label();
            label.Text = text;
            label.AutoSize = false;
            label.BackColor = Color.Transparent;
            label.ForeColor = muted ? Theme.P.Muted : Theme.P.Text;
            if (muted) label.Tag = "muted";
            return label;
        }

        /// <summary>Нижняя строка кнопок: слева таблетка и красная кнопка, справа «Закрыть».</summary>
        protected sealed class ButtonBar : Panel
        {
            public readonly FlowLayoutPanel LeftFlow = new FlowLayoutPanel();
            public readonly FlowLayoutPanel RightFlow = new FlowLayoutPanel();

            public ButtonBar()
            {
                Dock = DockStyle.Bottom;
                BackColor = Color.Transparent;
                foreach (FlowLayoutPanel flow in new[] { LeftFlow, RightFlow })
                {
                    flow.AutoSize = true;
                    flow.WrapContents = false;
                    flow.BackColor = Color.Transparent;
                    flow.Margin = new Padding(0);
                    // Обе группы начинаются на одной высоте (запас сверху и снизу — на фокусную рамку).
                    flow.Padding = new Padding(0, 4, 0, 4);
                }
                LeftFlow.Dock = DockStyle.Left;
                RightFlow.Dock = DockStyle.Right;
                RightFlow.FlowDirection = FlowDirection.RightToLeft;
                Controls.Add(LeftFlow);
                Controls.Add(RightFlow);
            }

            protected override void OnFontChanged(EventArgs e)
            {
                base.OnFontChanged(e);
                // Кнопки и таблетка — по 2,5 высоты шрифта; запас на фокусную рамку.
                Height = (int)Math.Ceiling(Font.Height * 2.5) + 8;
            }

            /// <summary>Ширина, при которой обе группы кнопок помещаются в ряд.</summary>
            public int NeededWidth()
            {
                return LeftFlow.GetPreferredSize(Size.Empty).Width + RightFlow.GetPreferredSize(Size.Empty).Width + Font.Height;
            }
        }

        /// <summary>Окно не уже нижнего ряда кнопок: при крупном шрифте оно растёт, а не обрезает кнопки.</summary>
        protected override void OnLoad(EventArgs e)
        {
            base.OnLoad(e);
            foreach (Control c in Controls)
            {
                var bar = c as ButtonBar;
                if (bar == null) continue;
                int need = Padding.Horizontal + bar.NeededWidth();
                if (ClientSize.Width < need) ClientSize = new Size(need, ClientSize.Height);
                Size minimum = SizeFromClientSize(new Size(need, 0));
                if (MinimumSize.Width < minimum.Width) MinimumSize = new Size(minimum.Width, MinimumSize.Height);
            }
        }
    }

    /// <summary>
    /// Диалог вместо <see cref="MessageBox"/>: те же вопросы и кнопки, но в теме агента. Возвращает тот же
    /// <see cref="DialogResult"/>; Esc отвечает «Отмена» / «Нет» (или «ОК», если других кнопок нет).
    /// </summary>
    sealed class ThemedBox : ThemedForm
    {
        readonly MessageBoxIcon icon;

        public static DialogResult Show(string text, string caption, MessageBoxButtons buttons, MessageBoxIcon icon)
        {
            return Show(null, text, caption, buttons, icon);
        }

        public static DialogResult Show(IWin32Window owner, string text, string caption, MessageBoxButtons buttons, MessageBoxIcon icon)
        {
            using (var box = new ThemedBox(owner != null, text, caption, buttons, icon))
            {
                return owner == null ? box.ShowDialog() : box.ShowDialog(owner);
            }
        }

        ThemedBox(bool hasOwner, string text, string caption, MessageBoxButtons buttons, MessageBoxIcon icon)
        {
            this.icon = icon;
            Text = caption;
            FormBorderStyle = FormBorderStyle.FixedDialog;
            StartPosition = hasOwner ? FormStartPosition.CenterParent : FormStartPosition.CenterScreen;
            MaximizeBox = false;
            ShowInTaskbar = !hasOwner;

            int m = Ui.Px(this, 22);
            int badge = Ui.Px(this, 40);
            int gap = Ui.Px(this, 16);
            int maxText = Ui.Px(this, 380);
            Size measured = TextRenderer.MeasureText(text, Font, new Size(maxText, 0), TextFormatFlags.WordBreak | TextFormatFlags.NoPrefix | TextFormatFlags.NoPadding);

            var body = new Label();
            body.Text = text;
            body.AutoSize = false;
            body.BackColor = Color.Transparent;
            body.UseMnemonic = false;
            int textHeight = Math.Max(measured.Height + 2, badge);
            body.SetBounds(m + badge + gap, m, Math.Min(maxText, measured.Width + 4), textHeight);

            var buttonList = new List<PillButton>();
            switch (buttons)
            {
                case MessageBoxButtons.YesNo:
                    buttonList.Add(Make("Да", PillKind.Primary, DialogResult.Yes));
                    buttonList.Add(Make("Нет", PillKind.Normal, DialogResult.No));
                    break;
                case MessageBoxButtons.YesNoCancel:
                    buttonList.Add(Make("Да", PillKind.Primary, DialogResult.Yes));
                    buttonList.Add(Make("Нет", PillKind.Normal, DialogResult.No));
                    buttonList.Add(Make("Отмена", PillKind.Normal, DialogResult.Cancel));
                    break;
                case MessageBoxButtons.OKCancel:
                    buttonList.Add(Make("ОК", PillKind.Primary, DialogResult.OK));
                    buttonList.Add(Make("Отмена", PillKind.Normal, DialogResult.Cancel));
                    break;
                default:
                    buttonList.Add(Make("ОК", PillKind.Primary, DialogResult.OK));
                    break;
            }

            int buttonsWidth = 0;
            foreach (PillButton b in buttonList) buttonsWidth += b.Width + gap / 2;
            int contentWidth = Math.Max(m + badge + gap + body.Width, buttonsWidth) + m;
            int top = m + textHeight + m;
            int height = top + buttonList[0].Height + m;
            ClientSize = new Size(contentWidth, height);
            int x = contentWidth - m;
            foreach (PillButton b in buttonList)
            {
                x -= b.Width;
                b.Location = new Point(x, top);
                x -= gap / 2;
            }
            AcceptButton = buttonList[0];
            foreach (PillButton b in buttonList)
            {
                if (b.DialogResult == DialogResult.Cancel || b.DialogResult == DialogResult.No && CancelButton == null) CancelButton = b;
            }
            if (CancelButton == null) CancelButton = buttonList[buttonList.Count - 1];

            Controls.Add(body);
            foreach (PillButton b in buttonList) Controls.Add(b);
            badgeBounds = new Rectangle(m, m, badge, badge);
        }

        Rectangle badgeBounds;

        PillButton Make(string text, PillKind kind, DialogResult result)
        {
            var b = new PillButton(text, kind, null, null);
            b.Font = Font;
            b.DialogResult = result;
            b.Size = b.GetPreferredSize(Size.Empty);
            // Кнопки одинаковой минимальной ширины.
            b.Width = Math.Max(b.Width, Ui.Px(this, 92));
            return b;
        }

        protected override void OnPaint(PaintEventArgs e)
        {
            base.OnPaint(e);
            Graphics g = e.Graphics;
            g.SmoothingMode = SmoothingMode.AntiAlias;
            Palette p = Theme.P;
            Color color;
            string mark;
            switch (icon)
            {
                case MessageBoxIcon.Error: color = p.Danger; mark = "×"; break;
                case MessageBoxIcon.Warning: color = p.Warn; mark = "!"; break;
                case MessageBoxIcon.Question: color = p.Accent; mark = "?"; break;
                default: color = p.Accent; mark = "i"; break;
            }
            Rectangle r = badgeBounds;
            using (var brush = new SolidBrush(Ui.Mix(p.Bg, color, 0.22)))
            {
                g.FillEllipse(brush, r);
            }
            using (var font = new Font(Font.FontFamily, Font.Size * 1.6f, FontStyle.Bold, GraphicsUnit.Point))
            {
                TextRenderer.DrawText(g, mark, font, r, color, Ui.Mix(p.Bg, color, 0.22), Ui.TextFlags(true) & ~TextFormatFlags.EndEllipsis);
            }
        }

        protected override void OnThemeChanged()
        {
            Invalidate(true);
        }
    }
}
