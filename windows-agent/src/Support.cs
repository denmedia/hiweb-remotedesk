using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.Drawing.Text;
using System.IO;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace HiWebAgent
{
    static class AgentInfo
    {
        public const string Product = "HiWeb Agent";
        public const string Version = BuildInfo.Version;

        /// <summary>Версия протокола канала; растёт при несовместимых изменениях сообщений.</summary>
        public const int Protocol = 1;

        public static string InstallDir
        {
            get { return Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "HiWebAgent"); }
        }

        public static string InstalledExe
        {
            get { return Path.Combine(InstallDir, "HiWebAgent.exe"); }
        }
    }

    /// <summary>Короткий журнал рядом с программой; при превышении размера начинается заново.</summary>
    static class Log
    {
        const long MAX_SIZE = 512 * 1024;
        static readonly object sync = new object();

        public static void Write(string line)
        {
            try
            {
                lock (sync)
                {
                    Directory.CreateDirectory(AgentInfo.InstallDir);
                    string path = Path.Combine(AgentInfo.InstallDir, "agent.log");
                    var fi = new FileInfo(path);
                    if (fi.Exists && fi.Length > MAX_SIZE) fi.Delete();
                    File.AppendAllText(path, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss ") + line + Environment.NewLine);
                }
            }
            catch (Exception)
            {
            }
        }
    }

    /// <summary>
    /// Значок в трее: силуэт ноутбука (laptop.png, вшит в exe) одним серым цветом под тему панели задач —
    /// светлый на тёмной, тёмный на светлой, как системные значки. Экран и тачпад вырезаны; на экране —
    /// батарейка с зарядом планшета: контур серый, заливка цветная по уровню, при зарядке — молния.
    /// </summary>
    static class TrayIcons
    {
        [DllImport("user32.dll")]
        static extern bool DestroyIcon(IntPtr handle);

        // Белый экран ноутбука в долях картинки (по исходнику 878×726: x 146..729, y 60..406).
        const float SCREEN_X = 146f / 878, SCREEN_Y = 60f / 726, SCREEN_W = 584f / 878, SCREEN_H = 347f / 726;

        static readonly Color OnDark = Color.FromArgb(222, 222, 222);
        static readonly Color OnLight = Color.FromArgb(64, 64, 64);
        static readonly Color Critical = Color.FromArgb(232, 56, 56);
        static readonly Color Low = Color.FromArgb(245, 135, 25);
        static readonly Color Medium = Color.FromArgb(230, 190, 0);
        static readonly Color Good = Color.FromArgb(48, 180, 80);

        static Bitmap mask;

        /// <summary>
        /// Силуэт ноутбука: белый с альфой исходника, белый экран и тёмный тачпад прозрачны. Цвет задаётся
        /// при отрисовке матрицей цвета.
        /// </summary>
        static Bitmap Mask
        {
            get
            {
                if (mask != null) return mask;
                using (Stream s = typeof(TrayIcons).Assembly.GetManifestResourceStream("HiWebAgent.laptop.png"))
                using (var src = new Bitmap(s))
                {
                    var m = new Bitmap(src.Width, src.Height, PixelFormat.Format32bppArgb);
                    var screen = Rectangle.Round(new RectangleF(SCREEN_X * src.Width, SCREEN_Y * src.Height, SCREEN_W * src.Width, SCREEN_H * src.Height));
                    for (int y = 0; y < src.Height; y++)
                    {
                        for (int x = 0; x < src.Width; x++)
                        {
                            Color c = src.GetPixel(x, y);
                            int a = c.A;
                            int min = Math.Min(c.R, Math.Min(c.G, c.B));
                            int luma = (c.R * 30 + c.G * 59 + c.B * 11) / 100;
                            // Внутри экрана белое — вырез; на краю экрана альфа плавно убывает к белому.
                            if (screen.Contains(x, y) && min > 160) a = a * Math.Max(0, 255 - (min - 160) * 255 / 70) / 255;
                            // Тачпад тёмно-синий, рамка нигде не тёмная.
                            if (luma < 110) a = a * Math.Max(0, luma - 50) * 255 / 60 / 255;
                            m.SetPixel(x, y, Color.FromArgb(Math.Max(0, Math.Min(255, a)), 255, 255, 255));
                        }
                    }
                    mask = m;
                }
                return mask;
            }
        }

        /// <summary>Панель задач тёмная (по умолчанию в Windows 11 светлая).</summary>
        public static bool DarkTaskbar()
        {
            try
            {
                using (var k = Microsoft.Win32.Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"))
                {
                    object v = k == null ? null : k.GetValue("SystemUsesLightTheme");
                    return v is int && (int)v == 0;
                }
            }
            catch (Exception)
            {
                return false;
            }
        }

        static Color LevelColor(int level)
        {
            if (level <= 15) return Critical;
            if (level <= 30) return Low;
            if (level <= 50) return Medium;
            return Good;
        }

        /// <summary>
        /// connected == false — планшет не подключён: силуэт полупрозрачный, без батарейки.
        /// level == null при подключении — заряд ещё не пришёл: экран пустой.
        /// </summary>
        public static Icon Render(bool connected, int? level, bool charging)
        {
            return Render(Math.Max(16, SystemInformation.SmallIconSize.Width), DarkTaskbar(), connected, level, charging);
        }

        public static Icon Render(int size, bool dark, bool connected, int? level, bool charging)
        {
            using (Bitmap bmp = RenderBitmap(size, dark, connected, level, charging))
            {
                IntPtr hIcon = bmp.GetHicon();
                try
                {
                    using (Icon tmp = Icon.FromHandle(hIcon)) return (Icon)tmp.Clone();
                }
                finally
                {
                    DestroyIcon(hIcon);
                }
            }
        }

        public static Bitmap RenderBitmap(int size, bool dark, bool connected, int? level, bool charging)
        {
            Color ink = dark ? OnDark : OnLight;
            var bmp = new Bitmap(size, size);
            using (Graphics g = Graphics.FromImage(bmp))
            {
                g.Clear(Color.Transparent);
                g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                Bitmap src = Mask;
                float w = size, h = size * (float)src.Height / src.Width;
                var dest = new RectangleF(0, (size - h) / 2, w, h);
                // Белый силуэт перекрашивается в цвет темы; без планшета — полупрозрачный.
                var cm = new ColorMatrix(new float[][]
                {
                    new float[] { 0, 0, 0, 0, 0 },
                    new float[] { 0, 0, 0, 0, 0 },
                    new float[] { 0, 0, 0, 0, 0 },
                    new float[] { 0, 0, 0, connected ? 1f : 0.45f, 0 },
                    new float[] { ink.R / 255f, ink.G / 255f, ink.B / 255f, 0, 1 },
                });
                using (var attrs = new ImageAttributes())
                {
                    attrs.SetColorMatrix(cm);
                    attrs.SetWrapMode(WrapMode.TileFlipXY);
                    g.DrawImage(src, Rectangle.Round(dest), 0, 0, src.Width, src.Height, GraphicsUnit.Pixel, attrs);
                }
                if (connected && level != null)
                {
                    var screen = new RectangleF(dest.X + SCREEN_X * w, dest.Y + SCREEN_Y * h, SCREEN_W * w, SCREEN_H * h);
                    DrawBattery(g, screen, ink, level.Value, charging);
                }
            }
            return bmp;
        }

        /// <summary>Батарейка по целым пикселям: на 16–24 px сглаживание превращает её в пятно.</summary>
        static void DrawBattery(Graphics g, RectangleF screen, Color ink, int level, bool charging)
        {
            g.SmoothingMode = SmoothingMode.None;
            g.PixelOffsetMode = PixelOffsetMode.None;
            int stroke = Math.Max(1, (int)Math.Round(screen.Height / 9));
            int left = (int)Math.Ceiling(screen.X + screen.Width * 0.08f);
            int right = (int)Math.Floor(screen.Right - screen.Width * 0.06f);
            int top = (int)Math.Ceiling(screen.Y + screen.Height * 0.12f);
            int bottom = (int)Math.Floor(screen.Bottom - screen.Height * 0.12f);
            int tipW = Math.Max(1, (int)Math.Round((right - left) * 0.08f));
            int bodyRight = right - tipW;
            int bodyH = bottom - top;
            if (bodyRight - left < 2 * stroke + 2 || bodyH < 2 * stroke + 1) return;

            using (var outline = new SolidBrush(ink))
            using (var fill = new SolidBrush(LevelColor(level)))
            {
                var body = new Rectangle(left, top, bodyRight - left, bodyH);
                var inner = new Rectangle(left + stroke, top + stroke, body.Width - 2 * stroke, bodyH - 2 * stroke);
                // Контур рамкой: пустая часть батарейки прозрачна, как вырезанный экран.
                g.FillRectangle(outline, body.X, body.Y, body.Width, stroke);
                g.FillRectangle(outline, body.X, body.Bottom - stroke, body.Width, stroke);
                g.FillRectangle(outline, body.X, body.Y, stroke, bodyH);
                g.FillRectangle(outline, body.Right - stroke, body.Y, stroke, bodyH);
                int tipH = Math.Max(1, (int)Math.Round(bodyH * 0.45f));
                g.FillRectangle(outline, bodyRight, top + (bodyH - tipH) / 2, tipW, tipH);
                int filled = (int)Math.Round(inner.Width * level / 100f);
                if (level > 0 && filled == 0) filled = 1;
                if (filled > 0) g.FillRectangle(fill, inner.X, inner.Y, filled, inner.Height);

                if (charging && inner.Height >= 5)
                {
                    // Молния по центру: белая с тёмной обводкой, чтобы читалась и на заливке, и на панели задач.
                    g.SmoothingMode = SmoothingMode.AntiAlias;
                    float cx = inner.X + inner.Width / 2f, cy = inner.Y + inner.Height / 2f;
                    float bh = inner.Height + stroke, bw = bh * 0.6f;
                    var bolt = new PointF[]
                    {
                        new PointF(cx + bw * 0.15f, cy - bh / 2),
                        new PointF(cx - bw * 0.45f, cy + bh * 0.08f),
                        new PointF(cx - bw * 0.02f, cy + bh * 0.08f),
                        new PointF(cx - bw * 0.15f, cy + bh / 2),
                        new PointF(cx + bw * 0.45f, cy - bh * 0.08f),
                        new PointF(cx + bw * 0.02f, cy - bh * 0.08f),
                    };
                    using (var white = new SolidBrush(Color.White))
                    using (var pen = new Pen(Color.FromArgb(40, 40, 40), Math.Max(1f, stroke * 0.8f)))
                    {
                        pen.LineJoin = LineJoin.Round;
                        g.DrawPolygon(pen, bolt);
                        g.FillPolygon(white, bolt);
                    }
                }
            }
        }
    }
}
