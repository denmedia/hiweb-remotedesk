using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Windows.Forms;

namespace HiWebAgent
{
    /// <summary>
    /// Снимок основного монитора для превью ПК на планшете: уменьшен до ширины превью и сжат в JPEG.
    /// Агент PerMonitorV2, поэтому границы экрана — в физических пикселях.
    /// </summary>
    static class ScreenShot
    {
        /// <summary>Ширина превью на планшете (ThumbnailStore хранит 640 px).</summary>
        const int WIDTH = 640;
        const long QUALITY = 75;

        /// <summary>
        /// JPEG снимка или null, если снять не удалось или кадр чёрный (заблокированный или отключённый сеанс
        /// отдаёт чёрный кадр вместо ошибки). [size] — размер JPEG в пикселях.
        /// </summary>
        public static byte[] Capture(out Size size)
        {
            size = Size.Empty;
            Rectangle bounds = Screen.PrimaryScreen.Bounds;
            if (bounds.Width <= 0 || bounds.Height <= 0) return null;
            using (var full = new Bitmap(bounds.Width, bounds.Height, PixelFormat.Format32bppRgb))
            {
                using (Graphics g = Graphics.FromImage(full))
                {
                    g.CopyFromScreen(bounds.Location, Point.Empty, bounds.Size, CopyPixelOperation.SourceCopy);
                }
                int w = Math.Min(WIDTH, bounds.Width);
                int h = Math.Max(1, (int)Math.Round(bounds.Height * (double)w / bounds.Width));
                using (var small = new Bitmap(w, h, PixelFormat.Format24bppRgb))
                {
                    using (Graphics g = Graphics.FromImage(small))
                    {
                        g.InterpolationMode = InterpolationMode.HighQualityBicubic;
                        g.PixelOffsetMode = PixelOffsetMode.HighQuality;
                        g.DrawImage(full, 0, 0, w, h);
                    }
                    if (IsBlank(small)) return null;
                    size = new Size(w, h);
                    return Jpeg(small);
                }
            }
        }

        /// <summary>Почти все точки сетки 16×16 чёрные — снимать было нечего.</summary>
        static bool IsBlank(Bitmap bmp)
        {
            int lit = 0;
            for (int y = 0; y < 16; y++)
            {
                for (int x = 0; x < 16; x++)
                {
                    Color c = bmp.GetPixel(x * (bmp.Width - 1) / 15, y * (bmp.Height - 1) / 15);
                    if (c.R + c.G + c.B > 24) lit++;
                }
            }
            return lit < 4;
        }

        static byte[] Jpeg(Bitmap bmp)
        {
            ImageCodecInfo codec = null;
            foreach (ImageCodecInfo c in ImageCodecInfo.GetImageEncoders())
            {
                if (c.FormatID == ImageFormat.Jpeg.Guid) codec = c;
            }
            using (var ms = new MemoryStream())
            {
                if (codec == null)
                {
                    bmp.Save(ms, ImageFormat.Jpeg);
                }
                else
                {
                    using (var ps = new EncoderParameters(1))
                    {
                        ps.Param[0] = new EncoderParameter(System.Drawing.Imaging.Encoder.Quality, QUALITY);
                        bmp.Save(ms, codec, ps);
                    }
                }
                return ms.ToArray();
            }
        }
    }
}
