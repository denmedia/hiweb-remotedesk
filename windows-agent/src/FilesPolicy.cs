using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Web.Script.Serialization;

namespace HiWebAgent
{
    /// <summary>
    /// Папка ПК, открытая для планшета как отдельный «диск». Идентификатор [Id] идёт в протокол вместо пути:
    /// устройство не знает и не выбирает путь на ПК, оно видит только имя и режим.
    /// </summary>
    sealed class ShareDef
    {
        public string Id;
        public string Name;
        public string Path;
        public bool Write;

        public ShareDef Clone()
        {
            var c = new ShareDef();
            c.Id = Id;
            c.Name = Name;
            c.Path = Path;
            c.Write = Write;
            return c;
        }
    }

    /// <summary>
    /// Настройки доступа к файлам: общий выключатель и список папок. Выключено по умолчанию; режим записи — у каждой
    /// папки свой. Чистая логика без реестра и интерфейса (хранение — <c>FileSharing</c>), её проверяют тесты.
    /// </summary>
    sealed class ShareConfig
    {
        public const int MaxShares = 16;
        public const int MaxName = 40;

        public bool Enabled;
        public readonly List<ShareDef> Shares = new List<ShareDef>();

        public ShareConfig Clone()
        {
            var c = new ShareConfig();
            c.Enabled = Enabled;
            foreach (ShareDef s in Shares) c.Shares.Add(s.Clone());
            return c;
        }

        public ShareDef Find(string id)
        {
            if (id == null) return null;
            foreach (ShareDef s in Shares) if (s.Id == id) return s;
            return null;
        }

        /// <summary>Значение поля <c>fs</c> в caps: включён ли доступ и какие «диски» видит устройство.</summary>
        public Dictionary<string, object> CapsValue()
        {
            var value = new Dictionary<string, object>();
            value["on"] = Enabled;
            var list = new List<object>();
            if (Enabled)
            {
                foreach (ShareDef s in Shares)
                {
                    var item = new Dictionary<string, object>();
                    item["id"] = s.Id;
                    item["name"] = s.Name;
                    item["w"] = s.Write;
                    list.Add(item);
                }
            }
            value["shares"] = list;
            return value;
        }

        public string ToJson()
        {
            var root = new Dictionary<string, object>();
            root["enabled"] = Enabled;
            var list = new List<object>();
            foreach (ShareDef s in Shares)
            {
                var item = new Dictionary<string, object>();
                item["id"] = s.Id;
                item["name"] = s.Name;
                item["path"] = s.Path;
                item["write"] = s.Write;
                list.Add(item);
            }
            root["shares"] = list;
            return new JavaScriptSerializer().Serialize(root);
        }

        /// <summary>Читает настройки; испорченный или пустой текст даёт выключенный доступ без папок.</summary>
        public static ShareConfig FromJson(string text)
        {
            var config = new ShareConfig();
            if (string.IsNullOrEmpty(text)) return config;
            try
            {
                var root = new JavaScriptSerializer().Deserialize<Dictionary<string, object>>(text);
                object enabled;
                if (root != null && root.TryGetValue("enabled", out enabled) && enabled is bool) config.Enabled = (bool)enabled;
                object list;
                if (root == null || !root.TryGetValue("shares", out list)) return config;
                var items = list as System.Collections.IEnumerable;
                if (items == null) return config;
                foreach (object o in items)
                {
                    var item = o as Dictionary<string, object>;
                    if (item == null) continue;
                    var s = new ShareDef();
                    s.Id = Text(item, "id");
                    s.Name = Text(item, "name");
                    s.Path = Text(item, "path");
                    object write;
                    s.Write = item.TryGetValue("write", out write) && write is bool && (bool)write;
                    if (string.IsNullOrEmpty(s.Id) || string.IsNullOrEmpty(s.Path) || config.Find(s.Id) != null) continue;
                    if (string.IsNullOrEmpty(s.Name)) s.Name = LastName(s.Path);
                    config.Shares.Add(s);
                }
            }
            catch (Exception)
            {
                return new ShareConfig();
            }
            return config;
        }

        static string Text(Dictionary<string, object> d, string key)
        {
            object v;
            return d.TryGetValue(key, out v) && v != null ? v.ToString() : null;
        }

        /// <summary>
        /// Добавляет папку. Путь должен существовать; одну и ту же папку дважды не добавить. Название по умолчанию —
        /// имя папки (у корня диска — «Диск C»); повторяющееся название получает номер.
        /// </summary>
        public ShareDef Add(string path, bool write, out string error)
        {
            error = null;
            if (Shares.Count >= MaxShares)
            {
                error = "Можно открыть не больше " + MaxShares + " папок.";
                return null;
            }
            string full;
            try
            {
                full = Normalize(path);
            }
            catch (Exception)
            {
                error = "Такого пути нет.";
                return null;
            }
            if (full == null || !Directory.Exists(full))
            {
                error = "Папка не найдена.";
                return null;
            }
            foreach (ShareDef s in Shares)
            {
                if (string.Equals(s.Path, full, StringComparison.OrdinalIgnoreCase))
                {
                    error = "Эта папка уже в списке.";
                    return null;
                }
            }
            var def = new ShareDef();
            def.Id = NewId();
            def.Path = full;
            def.Write = write;
            def.Name = UniqueName(LastName(full), null);
            Shares.Add(def);
            return def;
        }

        /// <summary>Переименовывает «диск»: пустое имя возвращает имя папки; одинаковые названия различаются номером.</summary>
        public void Rename(ShareDef share, string name)
        {
            string wanted = (name ?? "").Trim();
            if (wanted.Length == 0) wanted = LastName(share.Path);
            share.Name = UniqueName(wanted, share);
        }

        string UniqueName(string wanted, ShareDef except)
        {
            string name = Clip(wanted);
            string candidate = name;
            for (int n = 2; NameTaken(candidate, except); n++)
            {
                string suffix = " (" + n + ")";
                candidate = Clip(name, MaxName - suffix.Length) + suffix;
            }
            return candidate;
        }

        bool NameTaken(string name, ShareDef except)
        {
            foreach (ShareDef s in Shares)
            {
                if (s != except && string.Equals(s.Name, name, StringComparison.CurrentCultureIgnoreCase)) return true;
            }
            return false;
        }

        static string Clip(string name)
        {
            return Clip(name, MaxName);
        }

        static string Clip(string name, int max)
        {
            string t = (name ?? "").Trim();
            if (t.Length == 0) t = "Папка";
            return t.Length > max ? t.Substring(0, max).TrimEnd() : t;
        }

        /// <summary>Идентификатор «диска» — шесть шестнадцатеричных цифр; не повторяет уже занятые.</summary>
        string NewId()
        {
            while (true)
            {
                string id = Guid.NewGuid().ToString("N").Substring(0, 6);
                if (Find(id) == null) return id;
            }
        }

        /// <summary>Полный путь без завершающего разделителя (кроме корня диска); null — путь пустой.</summary>
        public static string Normalize(string path)
        {
            if (string.IsNullOrWhiteSpace(path)) return null;
            string full = System.IO.Path.GetFullPath(path.Trim());
            string root = System.IO.Path.GetPathRoot(full);
            if (full.Length > root.Length) full = full.TrimEnd('\\', '/');
            return full;
        }

        /// <summary>Название по пути: имя папки, у корня диска — «Диск C».</summary>
        public static string LastName(string path)
        {
            string full = path ?? "";
            string root = System.IO.Path.GetPathRoot(full);
            if (!string.IsNullOrEmpty(root) && full.TrimEnd('\\', '/').Length <= root.TrimEnd('\\', '/').Length)
            {
                string letter = root.TrimEnd('\\', '/').TrimEnd(':');
                return letter.StartsWith("\\\\", StringComparison.Ordinal) ? letter.TrimStart('\\') : "Диск " + letter.ToUpperInvariant();
            }
            string name = System.IO.Path.GetFileName(full.TrimEnd('\\', '/'));
            return string.IsNullOrEmpty(name) ? "Папка" : name;
        }
    }

    /// <summary>
    /// Пути внутри «диска»: устройство присылает путь относительно корня папки через «/». Здесь он разбирается и
    /// проверяется так, чтобы выйти за корень (через «..», «:поток», зарезервированные имена и ссылки-переходы) было нельзя.
    /// </summary>
    static class SharePaths
    {
        /// <summary>Каталоги, куда доступ закрыт всегда (данные самого агента), даже если лежат внутри открытой папки.</summary>
        public static readonly List<string> Forbidden = new List<string>();

        static readonly string[] Reserved =
        {
            "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        };

        /// <summary>Допустимое имя одного файла или папки Windows (без зарезервированных имён, точки и пробела в конце).</summary>
        public static bool ValidName(string name)
        {
            if (string.IsNullOrEmpty(name) || name.Length > 255) return false;
            if (name == "." || name == "..") return false;
            if (name.EndsWith(".", StringComparison.Ordinal) || name.EndsWith(" ", StringComparison.Ordinal)) return false;
            foreach (char c in name)
            {
                if (c < 32 || "<>:\"/\\|?*".IndexOf(c) >= 0) return false;
            }
            int dot = name.IndexOf('.');
            string stem = (dot >= 0 ? name.Substring(0, dot) : name).TrimEnd(' ');
            foreach (string r in Reserved) if (string.Equals(stem, r, StringComparison.OrdinalIgnoreCase)) return false;
            return true;
        }

        /// <summary>Части пути «a/b/c»; пустой путь — корень (пустой массив); null — путь недопустим (пустая часть, «.» или «..»).</summary>
        public static string[] Split(string rel)
        {
            if (rel == null) return new string[0];
            string t = rel.Trim('/');
            if (t.Length == 0) return new string[0];
            string[] parts = t.Split('/');
            foreach (string p in parts) if (p.Length == 0 || p == "." || p == "..") return null;
            return parts;
        }

        /// <summary>
        /// Полный путь на ПК для пути [rel] внутри папки [share] или null с кодом [error]: <c>bad</c> — путь
        /// недопустим, <c>denied</c> — путь ведёт в закрытое место (ссылка-переход, данные агента).
        /// </summary>
        public static string Resolve(ShareDef share, string rel, out string error)
        {
            error = null;
            string[] parts = Split(rel);
            if (parts == null)
            {
                error = "bad";
                return null;
            }
            string current = share.Path;
            foreach (string part in parts)
            {
                if (!ValidName(part))
                {
                    error = "bad";
                    return null;
                }
                current = System.IO.Path.Combine(current, part);
                // Ссылка внутри папки может вести куда угодно: симлинки и переходы не открываем ни на чтение, ни на запись.
                if (IsLink(current))
                {
                    error = "denied";
                    return null;
                }
            }
            string full = System.IO.Path.GetFullPath(current);
            if (!IsInside(share.Path, full) || IsForbidden(full))
            {
                error = "denied";
                return null;
            }
            return full;
        }

        /// <summary>Лежит ли [path] в каталоге [root] или совпадает с ним (без учёта регистра).</summary>
        public static bool IsInside(string root, string path)
        {
            string r = root.TrimEnd('\\', '/');
            if (string.Equals(path.TrimEnd('\\', '/'), r, StringComparison.OrdinalIgnoreCase)) return true;
            return path.StartsWith(r + "\\", StringComparison.OrdinalIgnoreCase);
        }

        static bool IsForbidden(string full)
        {
            lock (Forbidden)
            {
                foreach (string f in Forbidden) if (IsInside(f, full)) return true;
            }
            return false;
        }

        // ------------------------------------------------------------------ ссылки

        const uint ReparsePoint = 0x400;
        const uint TagSymlink = 0xA000000C;
        const uint TagMountPoint = 0xA0000003;

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct FindData
        {
            public uint Attributes;
            public System.Runtime.InteropServices.ComTypes.FILETIME Created;
            public System.Runtime.InteropServices.ComTypes.FILETIME Accessed;
            public System.Runtime.InteropServices.ComTypes.FILETIME Written;
            public uint SizeHigh;
            public uint SizeLow;
            public uint Reserved0;
            public uint Reserved1;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 260)] public string FileName;
            [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 14)] public string AlternateName;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        static extern IntPtr FindFirstFileW(string name, out FindData data);

        [DllImport("kernel32.dll")]
        static extern bool FindClose(IntPtr handle);

        /// <summary>
        /// Символическая ссылка или точка соединения (junction). Облачные заглушки OneDrive и подобные тоже «точки
        /// повторной обработки», но у них другой тег, и они остаются доступными. Несуществующий путь — не ссылка.
        /// </summary>
        public static bool IsLink(string path)
        {
            FindData data;
            IntPtr handle = FindFirstFileW(path, out data);
            if (handle == new IntPtr(-1)) return false;
            FindClose(handle);
            if ((data.Attributes & ReparsePoint) == 0) return false;
            return data.Reserved0 == TagSymlink || data.Reserved0 == TagMountPoint;
        }
    }
}
