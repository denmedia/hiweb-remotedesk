using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.IO;
using System.Runtime.InteropServices;

namespace HiWebAgent
{
    /// <summary>
    /// Иконка приложения, приславшего уведомление, — для карточки уведомления на планшете. Берётся у оболочки Windows
    /// по AppUserModelId (так же, как её показывает меню «Пуск»; для классических программ API уведомлений логотипа
    /// не даёт) и держится только в памяти процесса: файлов на диске нет. Вызывать из потока интерфейса (STA).
    /// </summary>
    static class AppIcons
    {
        const int SIZE = 128;
        // Результат «иконки нет» (системные уведомления) тоже запоминается, чтобы не спрашивать оболочку каждый раз.
        static readonly Dictionary<string, string> cache = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

        [ComImport, Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        interface IShellItemImageFactory
        {
            [PreserveSig]
            int GetImage(NativeSize size, int flags, out IntPtr hbitmap);
        }

        [StructLayout(LayoutKind.Sequential)]
        struct NativeSize
        {
            public int cx, cy;
        }

        [StructLayout(LayoutKind.Sequential)]
        struct NativeBitmap
        {
            public int bmType, bmWidth, bmHeight, bmWidthBytes;
            public short bmPlanes, bmBitsPixel;
            public IntPtr bmBits;
        }

        [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = false)]
        static extern void SHCreateItemFromParsingName(string path, IntPtr bc, ref Guid riid,
            [MarshalAs(UnmanagedType.Interface)] out IShellItemImageFactory item);

        [DllImport("gdi32.dll")]
        static extern int GetObject(IntPtr handle, int size, out NativeBitmap bitmap);

        [DllImport("gdi32.dll")]
        static extern bool DeleteObject(IntPtr handle);

        /// <summary>PNG иконки в base64 или null, если у оболочки её нет.</summary>
        public static string PngBase64(string appId)
        {
            if (string.IsNullOrEmpty(appId)) return null;
            string cached;
            if (cache.TryGetValue(appId, out cached)) return cached;
            string result = null;
            try { result = Render(appId); }
            catch (Exception e) { Log.Write("icon " + appId + ": " + e.GetBaseException().Message); }
            cache[appId] = result;
            return result;
        }

        static string Render(string appId)
        {
            Guid iid = new Guid("bcc18b79-ba16-442f-80c4-8a59c30c463b");
            IShellItemImageFactory factory;
            SHCreateItemFromParsingName("shell:AppsFolder\\" + appId, IntPtr.Zero, ref iid, out factory);
            var size = new NativeSize();
            size.cx = SIZE;
            size.cy = SIZE;
            IntPtr hbitmap;
            // ICONONLY | SCALEUP: именно иконка приложения, нужного размера.
            if (factory.GetImage(size, 0x4 | 0x20, out hbitmap) != 0 || hbitmap == IntPtr.Zero) return null;
            try
            {
                NativeBitmap raw;
                if (GetObject(hbitmap, Marshal.SizeOf(typeof(NativeBitmap)), out raw) == 0 || raw.bmBits == IntPtr.Zero || raw.bmBitsPixel != 32) return null;
                // Image.FromHbitmap теряет прозрачность, поэтому читаем точки напрямую (оболочка отдаёт 32 бита ARGB).
                using (var view = new Bitmap(raw.bmWidth, raw.bmHeight, raw.bmWidthBytes, PixelFormat.Format32bppArgb, raw.bmBits))
                using (var own = new Bitmap(view))
                using (var ms = new MemoryStream())
                {
                    own.Save(ms, ImageFormat.Png);
                    return Convert.ToBase64String(ms.ToArray());
                }
            }
            finally
            {
                DeleteObject(hbitmap);
            }
        }
    }
}
