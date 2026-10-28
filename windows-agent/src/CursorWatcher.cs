using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;

namespace HiWebAgent
{
    /// <summary>
    /// Форма курсора мыши для планшета. Windows 11 присылает по RDP только «стрелку по умолчанию» (проверено:
    /// в сеансе пользователя сервер шлёт PTR_DEFAULT при любой смене курсора), поэтому агент, работающий в этом
    /// же сеансе, сам следит за курсором (<c>GetCursorInfo</c>) и при смене рисует его в PNG с точкой привязки.
    /// Работает, только пока включено (<see cref="Active"/>).
    /// </summary>
    sealed class CursorWatcher : IDisposable
    {
        const int POLL_MS = 40;
        const int CURSOR_SHOWING = 1;
        const int DI_NORMAL = 3;

        [StructLayout(LayoutKind.Sequential)]
        struct CursorInfo
        {
            public int Size, Flags;
            public IntPtr Cursor;
            public int X, Y;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct IconInfo
        {
            public bool IsIcon;
            public int HotX, HotY;
            public IntPtr Mask, Color;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct BitmapInfo
        {
            public int Type, Width, Height, WidthBytes;
            public short Planes, BitsPixel;
            public IntPtr Bits;
        }

        [DllImport("user32.dll")]
        static extern bool GetCursorInfo(ref CursorInfo info);

        [DllImport("user32.dll")]
        static extern bool GetIconInfo(IntPtr icon, out IconInfo info);

        [DllImport("user32.dll")]
        static extern bool DrawIconEx(IntPtr dc, int x, int y, IntPtr icon, int width, int height, int step, IntPtr brush, int flags);

        [DllImport("gdi32.dll")]
        static extern int GetObject(IntPtr bitmap, int size, out BitmapInfo info);

        [DllImport("gdi32.dll")]
        static extern bool DeleteObject(IntPtr obj);

        readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
        bool active;
        IntPtr last = new IntPtr(-1);

        /// <summary>Сообщение <c>cursor</c> для планшета (вызывается в потоке интерфейса).</summary>
        public event Action<Dictionary<string, object>> Changed;

        public CursorWatcher()
        {
            timer.Interval = POLL_MS;
            timer.Tick += delegate { Poll(); };
        }

        public bool Active
        {
            get { return active; }
            set
            {
                if (active == value) return;
                active = value;
                // При включении планшет ещё не знает курсор: первое сообщение уходит сразу.
                last = new IntPtr(-1);
                if (active) timer.Start();
                else timer.Stop();
            }
        }

        void Poll()
        {
            var ci = new CursorInfo();
            ci.Size = Marshal.SizeOf(typeof(CursorInfo));
            // Не получилось (например, защищённый рабочий стол с запросом UAC) — прежний курсор остаётся.
            if (!GetCursorInfo(ref ci)) return;
            IntPtr key = (ci.Flags & CURSOR_SHOWING) != 0 ? ci.Cursor : IntPtr.Zero;
            if (key == last) return;
            Dictionary<string, object> msg = key == IntPtr.Zero ? Hidden() : Render(key);
            // Не удалось нарисовать — не запоминаем, повторим на следующем опросе.
            if (msg == null) return;
            last = key;
            Action<Dictionary<string, object>> changed = Changed;
            if (changed != null) changed(msg);
        }

        static Dictionary<string, object> Hidden()
        {
            var msg = new Dictionary<string, object>();
            msg["t"] = "cursor";
            msg["hidden"] = true;
            return msg;
        }

        /// <summary>Курсор в PNG: <c>w</c>, <c>h</c>, <c>hx</c>, <c>hy</c> (точка привязки), <c>png</c> (base64).</summary>
        static Dictionary<string, object> Render(IntPtr cursor)
        {
            IconInfo ii;
            if (!GetIconInfo(cursor, out ii)) return null;
            try
            {
                int w, h;
                BitmapInfo bi;
                if (ii.Color != IntPtr.Zero)
                {
                    if (GetObject(ii.Color, Marshal.SizeOf(typeof(BitmapInfo)), out bi) == 0) return null;
                    w = bi.Width;
                    h = bi.Height;
                }
                else
                {
                    // Чёрно-белый курсор: маска И и маска XOR лежат друг под другом.
                    if (GetObject(ii.Mask, Marshal.SizeOf(typeof(BitmapInfo)), out bi) == 0) return null;
                    w = bi.Width;
                    h = bi.Height / 2;
                }
                if (w <= 0 || h <= 0 || w > 512 || h > 512) return null;

                // GDI не пишет альфа-канал: рисуем курсор на чёрном и на белом фоне и восстанавливаем прозрачность
                // по разнице (на чёрном цвет умножен на альфу, на белом добавлено 255 * (1 - альфа)).
                byte[] onBlack = Draw(cursor, w, h, Color.Black);
                byte[] onWhite = Draw(cursor, w, h, Color.White);
                if (onBlack == null || onWhite == null) return null;
                var result = new Bitmap(w, h, PixelFormat.Format32bppArgb);
                try
                {
                    var px = new byte[w * h * 4];
                    for (int i = 0; i < px.Length; i += 4)
                    {
                        int diff = (onWhite[i] - onBlack[i] + onWhite[i + 1] - onBlack[i + 1] + onWhite[i + 2] - onBlack[i + 2]) / 3;
                        int alpha = 255 - diff;
                        if (alpha >= 255)
                        {
                            // Инвертирующий пиксель (XOR в чёрно-белом курсоре) или непрозрачный — рисуем как есть.
                            alpha = 255;
                            px[i] = onBlack[i]; px[i + 1] = onBlack[i + 1]; px[i + 2] = onBlack[i + 2];
                        }
                        else if (alpha <= 0)
                        {
                            px[i] = px[i + 1] = px[i + 2] = 0;
                            alpha = 0;
                        }
                        else
                        {
                            px[i] = (byte)Math.Min(255, onBlack[i] * 255 / alpha);
                            px[i + 1] = (byte)Math.Min(255, onBlack[i + 1] * 255 / alpha);
                            px[i + 2] = (byte)Math.Min(255, onBlack[i + 2] * 255 / alpha);
                        }
                        px[i + 3] = (byte)alpha;
                    }
                    BitmapData data = result.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.WriteOnly, PixelFormat.Format32bppArgb);
                    try
                    {
                        for (int y = 0; y < h; y++) Marshal.Copy(px, y * w * 4, new IntPtr(data.Scan0.ToInt64() + (long)y * data.Stride), w * 4);
                    }
                    finally { result.UnlockBits(data); }
                    using (var ms = new MemoryStream())
                    {
                        result.Save(ms, ImageFormat.Png);
                        var msg = new Dictionary<string, object>();
                        msg["t"] = "cursor";
                        msg["w"] = w;
                        msg["h"] = h;
                        msg["hx"] = ii.HotX;
                        msg["hy"] = ii.HotY;
                        msg["png"] = Convert.ToBase64String(ms.ToArray());
                        return msg;
                    }
                }
                finally { result.Dispose(); }
            }
            catch (Exception e)
            {
                Log.Write("cursor: " + e.Message);
                return null;
            }
            finally
            {
                if (ii.Color != IntPtr.Zero) DeleteObject(ii.Color);
                if (ii.Mask != IntPtr.Zero) DeleteObject(ii.Mask);
            }
        }

        /// <summary>Пиксели BGRA курсора, нарисованного поверх сплошного фона.</summary>
        static byte[] Draw(IntPtr cursor, int w, int h, Color background)
        {
            using (var bmp = new Bitmap(w, h, PixelFormat.Format32bppArgb))
            {
                using (Graphics g = Graphics.FromImage(bmp))
                {
                    g.Clear(background);
                    IntPtr dc = g.GetHdc();
                    try { if (!DrawIconEx(dc, 0, 0, cursor, w, h, 0, IntPtr.Zero, DI_NORMAL)) return null; }
                    finally { g.ReleaseHdc(dc); }
                }
                BitmapData data = bmp.LockBits(new Rectangle(0, 0, w, h), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
                try
                {
                    var px = new byte[w * h * 4];
                    for (int y = 0; y < h; y++) Marshal.Copy(new IntPtr(data.Scan0.ToInt64() + (long)y * data.Stride), px, y * w * 4, w * 4);
                    return px;
                }
                finally { bmp.UnlockBits(data); }
            }
        }

        public void Dispose()
        {
            timer.Dispose();
        }
    }
}
