using System;
using System.Collections.Generic;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace HiWebAgent
{
    /// <summary>Ответ на файловый запрос: заголовок (поле <c>ok</c> обязательно) и необязательные данные.</summary>
    sealed class FsReply
    {
        public Dictionary<string, object> Header = new Dictionary<string, object>();
        public byte[] Payload;
    }

    /// <summary>
    /// Тело файлового сообщения до шифрования: 4 байта длины заголовка (big-endian), заголовок JSON в UTF-8, затем
    /// данные как есть. Так содержимое файла не проходит base64 дважды. Шифрование и подпись строки — в <c>SecureLines</c>.
    /// </summary>
    static class FsWire
    {
        public static byte[] Pack(Dictionary<string, object> header, byte[] payload)
        {
            byte[] h = Encoding.UTF8.GetBytes(Serializer().Serialize(header));
            byte[] data = payload ?? new byte[0];
            var all = new byte[4 + h.Length + data.Length];
            all[0] = (byte)(h.Length >> 24);
            all[1] = (byte)(h.Length >> 16);
            all[2] = (byte)(h.Length >> 8);
            all[3] = (byte)h.Length;
            Buffer.BlockCopy(h, 0, all, 4, h.Length);
            Buffer.BlockCopy(data, 0, all, 4 + h.Length, data.Length);
            return all;
        }

        public static bool Unpack(byte[] plain, out Dictionary<string, object> header, out byte[] payload)
        {
            header = null;
            payload = null;
            if (plain == null || plain.Length < 4) return false;
            long length = ((long)plain[0] << 24) | ((long)plain[1] << 16) | ((long)plain[2] << 8) | plain[3];
            if (length < 2 || 4 + length > plain.Length) return false;
            try
            {
                header = Serializer().Deserialize<Dictionary<string, object>>(Encoding.UTF8.GetString(plain, 4, (int)length));
            }
            catch (Exception)
            {
                return false;
            }
            if (header == null) return false;
            int rest = plain.Length - 4 - (int)length;
            payload = new byte[rest];
            Buffer.BlockCopy(plain, 4 + (int)length, payload, 0, rest);
            return true;
        }

        static JavaScriptSerializer Serializer()
        {
            var s = new JavaScriptSerializer();
            s.MaxJsonLength = int.MaxValue;
            return s;
        }
    }

    /// <summary>
    /// Файловые операции для планшета над открытыми папками (<see cref="ShareConfig"/>): список, чтение кусками,
    /// запись через временный файл, создание папки, переименование, удаление в корзину. Работает от имени
    /// вошедшего пользователя Windows, поэтому видит только то, что доступно ему. Все пути проверяет
    /// <see cref="SharePaths"/>; доступ выключен или папка только для чтения — запись отклоняется здесь, а не в приложении.
    ///
    /// Запрос — заголовок с полем <c>op</c>: <c>list</c>, <c>stat</c>, <c>read</c>, <c>put_begin</c>, <c>put</c>,
    /// <c>put_end</c>, <c>put_abort</c>, <c>mkdir</c>, <c>rename</c>, <c>delete</c>, <c>space</c>; подробности — PROTOCOL.md.
    /// </summary>
    sealed class FileService
    {
        /// <summary>Самый большой кусок чтения и записи за одно сообщение.</summary>
        public const int MaxChunk = 256 * 1024;
        const int MaxEntries = 20000;
        const int MaxUploadsPerDevice = 8;
        const long UploadIdleMs = 120000;

        sealed class Upload
        {
            public string Id;
            public string Device;
            public string ShareId;
            public string Target;
            public string Temp;
            public bool Overwrite;
            public FileStream Stream;
            public long Offset;
            public long LastUse;
        }

        readonly Func<ShareConfig> config;
        readonly Dictionary<string, Upload> uploads = new Dictionary<string, Upload>();
        readonly object sync = new object();
        readonly Timer sweeper;

        public FileService(Func<ShareConfig> config)
        {
            this.config = config;
            sweeper = new Timer(delegate { Sweep(); }, null, 30000, 30000);
        }

        /// <summary>Выполняет запрос устройства [device]; никогда не бросает исключений — ошибка уходит в ответе.</summary>
        public FsReply Handle(string device, Dictionary<string, object> req, byte[] payload)
        {
            try
            {
                return Dispatch(device, req, payload ?? new byte[0]);
            }
            catch (Exception e)
            {
                return Fail(e);
            }
        }

        FsReply Dispatch(string device, Dictionary<string, object> req, byte[] payload)
        {
            string op = Str(req, "op");
            ShareConfig cfg = config();
            if (cfg == null || !cfg.Enabled) return Error("off", "Доступ к файлам на ПК выключен.");
            ShareDef share = cfg.Find(Str(req, "share"));
            if (share == null) return Error("nf", "Такой папки на ПК больше нет.");
            // Папку могли убрать или сделать «только чтение» посреди загрузки: такая запись не должна завершиться.
            if (op == "put" || op == "put_end")
            {
                if (!share.Write) { AbortFor(Str(req, "up")); return Error("ro", "Папка открыта только для чтения."); }
            }
            switch (op)
            {
                case "list": return List(share, Str(req, "path"));
                case "stat": return Stat(share, Str(req, "path"));
                case "read": return Read(share, Str(req, "path"), Num(req, "off", 0), (int)Math.Min(MaxChunk, Math.Max(0, Num(req, "len", MaxChunk))));
                case "space": return Space(share);
                case "put":
                case "put_end":
                case "put_abort":
                    return Put(op, device, req, payload);
            }
            if (op == "put_begin" || op == "mkdir" || op == "rename" || op == "delete")
            {
                if (!share.Write) return Error("ro", "Папка открыта только для чтения.");
                switch (op)
                {
                    case "put_begin": return PutBegin(device, share, Str(req, "path"), Bool(req, "overwrite", false));
                    case "mkdir": return Mkdir(share, Str(req, "path"));
                    case "rename": return Rename(share, Str(req, "path"), Str(req, "name"));
                    default: return Delete(share, Str(req, "path"));
                }
            }
            return Error("bad", "Неизвестная операция.");
        }

        // ------------------------------------------------------------------ чтение

        FsReply List(ShareDef share, string rel)
        {
            string error;
            string full = SharePaths.Resolve(share, rel, out error);
            if (full == null) return Error(error, null);
            if (!Directory.Exists(full)) return Error(File.Exists(full) ? "bad" : "nf", "Папка не найдена.");
            var items = new List<object>();
            bool truncated = false;
            foreach (FileSystemInfo fi in new DirectoryInfo(full).EnumerateFileSystemInfos())
            {
                if ((fi.Attributes & FileAttributes.ReparsePoint) != 0 && SharePaths.IsLink(fi.FullName)) continue;
                if (items.Count >= MaxEntries)
                {
                    truncated = true;
                    break;
                }
                items.Add(Item(fi, fi.Name));
            }
            var reply = Ok();
            reply.Header["items"] = items;
            if (truncated) reply.Header["trunc"] = true;
            return reply;
        }

        FsReply Stat(ShareDef share, string rel)
        {
            string error;
            string full = SharePaths.Resolve(share, rel, out error);
            if (full == null) return Error(error, null);
            FileSystemInfo fi = Directory.Exists(full) ? (FileSystemInfo)new DirectoryInfo(full) : new FileInfo(full);
            if (!fi.Exists) return Error("nf", "Файл не найден.");
            var reply = Ok();
            reply.Header["item"] = Item(fi, rel == null || rel.Trim('/').Length == 0 ? share.Name : fi.Name);
            return reply;
        }

        static Dictionary<string, object> Item(FileSystemInfo fi, string name)
        {
            bool dir = (fi.Attributes & FileAttributes.Directory) != 0;
            var item = new Dictionary<string, object>();
            item["n"] = name;
            item["d"] = dir;
            long size = 0;
            long modified = 0;
            try
            {
                if (!dir) size = ((FileInfo)fi).Length;
                modified = UnixMs(fi.LastWriteTimeUtc);
            }
            catch (Exception)
            {
                // Файл исчез или недоступен посреди перечисления: он остаётся в списке без размера и даты.
            }
            item["s"] = size;
            item["m"] = modified;
            if ((fi.Attributes & (FileAttributes.Hidden | FileAttributes.System)) != 0) item["h"] = true;
            return item;
        }

        FsReply Read(ShareDef share, string rel, long offset, int length)
        {
            string error;
            string full = SharePaths.Resolve(share, rel, out error);
            if (full == null) return Error(error, null);
            if (offset < 0) return Error("bad", null);
            if (!File.Exists(full)) return Error(Directory.Exists(full) ? "bad" : "nf", "Файл не найден.");
            // Файл может быть открыт программой на ПК: читаем, не мешая ей.
            using (var stream = new FileStream(full, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
            {
                long size = stream.Length;
                var buffer = new byte[offset >= size ? 0 : (int)Math.Min(length, size - offset)];
                int got = 0;
                if (buffer.Length > 0)
                {
                    stream.Seek(offset, SeekOrigin.Begin);
                    while (got < buffer.Length)
                    {
                        int n = stream.Read(buffer, got, buffer.Length - got);
                        if (n <= 0) break;
                        got += n;
                    }
                    if (got < buffer.Length) Array.Resize(ref buffer, got);
                }
                var reply = Ok();
                reply.Header["size"] = size;
                reply.Header["eof"] = offset + got >= size;
                reply.Payload = buffer;
                return reply;
            }
        }

        FsReply Space(ShareDef share)
        {
            var reply = Ok();
            long free = -1;
            long total = -1;
            try
            {
                string root = Path.GetPathRoot(share.Path);
                if (!string.IsNullOrEmpty(root) && !root.StartsWith("\\\\", StringComparison.Ordinal))
                {
                    var drive = new DriveInfo(root);
                    free = drive.AvailableFreeSpace;
                    total = drive.TotalSize;
                }
            }
            catch (Exception)
            {
            }
            reply.Header["free"] = free;
            reply.Header["total"] = total;
            return reply;
        }

        // ------------------------------------------------------------------ запись

        /// <summary>
        /// Начало загрузки файла: данные копятся во временном файле рядом с целью и заменяют её только в <c>put_end</c>,
        /// поэтому оборванная загрузка не портит существующий файл.
        /// </summary>
        FsReply PutBegin(string device, ShareDef share, string rel, bool overwrite)
        {
            string error;
            string full = SharePaths.Resolve(share, rel, out error);
            if (full == null) return Error(error, null);
            if (SharePaths.Split(rel).Length == 0) return Error("bad", null);
            string parent = Path.GetDirectoryName(full);
            if (!Directory.Exists(parent)) return Error("nf", "Папки назначения нет.");
            if (Directory.Exists(full)) return Error("exists", "На этом месте уже есть папка.");
            if (File.Exists(full) && !overwrite) return Error("exists", "Файл с таким именем уже есть.");
            lock (sync)
            {
                int mine = 0;
                foreach (Upload u in uploads.Values) if (u.Device == device) mine++;
                if (mine >= MaxUploadsPerDevice) return Error("busy", "Слишком много незавершённых загрузок.");
            }
            string id = Guid.NewGuid().ToString("N").Substring(0, 12);
            string temp = Path.Combine(parent, ".hiweb-" + id + ".part");
            var upload = new Upload();
            upload.Id = id;
            upload.Device = device;
            upload.ShareId = share.Id;
            upload.Target = full;
            upload.Temp = temp;
            upload.Overwrite = overwrite;
            upload.Stream = new FileStream(temp, FileMode.CreateNew, FileAccess.Write, FileShare.None);
            upload.LastUse = Environment.TickCount;
            lock (sync) uploads[id] = upload;
            var reply = Ok();
            reply.Header["up"] = id;
            return reply;
        }

        FsReply Put(string op, string device, Dictionary<string, object> req, byte[] payload)
        {
            string id = Str(req, "up");
            Upload upload = null;
            lock (sync)
            {
                if (id == null || !uploads.TryGetValue(id, out upload) || upload.Device != device) upload = null;
            }
            if (upload == null) return Error("nf", "Загрузка не найдена или устарела.");
            lock (upload)
            {
                if (upload.Stream == null) return Error("nf", "Загрузка уже закончена.");
                upload.LastUse = Environment.TickCount;
                if (op == "put_abort")
                {
                    Discard(upload);
                    return Ok();
                }
                if (op == "put")
                {
                    long offset = Num(req, "off", -1);
                    if (offset != upload.Offset || payload.Length > MaxChunk)
                    {
                        Discard(upload);
                        return Error("bad", "Нарушен порядок кусков загрузки.");
                    }
                    try
                    {
                        upload.Stream.Write(payload, 0, payload.Length);
                    }
                    catch (Exception)
                    {
                        Discard(upload);
                        throw;
                    }
                    upload.Offset += payload.Length;
                    return Ok();
                }
                // put_end
                long expected = Num(req, "size", -1);
                if (expected >= 0 && expected != upload.Offset)
                {
                    Discard(upload);
                    return Error("bad", "Размер файла не сошёлся.");
                }
                try
                {
                    upload.Stream.Flush();
                    upload.Stream.Close();
                    upload.Stream = null;
                    if (File.Exists(upload.Target))
                    {
                        if (!upload.Overwrite) throw new IOException("exists");
                        File.Replace(upload.Temp, upload.Target, null);
                    }
                    else
                    {
                        File.Move(upload.Temp, upload.Target);
                    }
                }
                catch (Exception)
                {
                    Discard(upload);
                    throw;
                }
                lock (sync) uploads.Remove(upload.Id);
                Log.Write("files: put " + Display(upload.ShareId, upload.Target) + " (" + upload.Offset + " байт) от " + upload.Device);
                return Ok();
            }
        }

        FsReply Mkdir(ShareDef share, string rel)
        {
            string error;
            string full = SharePaths.Resolve(share, rel, out error);
            if (full == null) return Error(error, null);
            if (SharePaths.Split(rel).Length == 0) return Error("exists", null);
            if (Directory.Exists(full) || File.Exists(full)) return Error("exists", "Такое имя уже занято.");
            if (!Directory.Exists(Path.GetDirectoryName(full))) return Error("nf", "Папки назначения нет.");
            Directory.CreateDirectory(full);
            Log.Write("files: mkdir " + share.Name + "/" + rel);
            return Ok();
        }

        FsReply Rename(ShareDef share, string rel, string name)
        {
            string error;
            string full = SharePaths.Resolve(share, rel, out error);
            if (full == null) return Error(error, null);
            if (SharePaths.Split(rel).Length == 0) return Error("bad", "Корень папки переименовать нельзя.");
            if (!SharePaths.ValidName(name)) return Error("bad", "Недопустимое имя.");
            string target = Path.Combine(Path.GetDirectoryName(full), name);
            string check;
            if (SharePaths.Resolve(share, ParentOf(rel) + name, out check) == null) return Error(check, null);
            bool dir = Directory.Exists(full);
            if (!dir && !File.Exists(full)) return Error("nf", "Файл не найден.");
            bool sameName = string.Equals(full, target, StringComparison.OrdinalIgnoreCase);
            if (!sameName && (File.Exists(target) || Directory.Exists(target))) return Error("exists", "Такое имя уже занято.");
            if (dir) Directory.Move(full, target);
            else File.Move(full, target);
            Log.Write("files: rename " + share.Name + "/" + rel + " -> " + name);
            return Ok();
        }

        FsReply Delete(ShareDef share, string rel)
        {
            string error;
            string full = SharePaths.Resolve(share, rel, out error);
            if (full == null) return Error(error, null);
            if (SharePaths.Split(rel).Length == 0) return Error("bad", "Корень открытой папки удалить нельзя.");
            if (!File.Exists(full) && !Directory.Exists(full)) return Error("nf", "Файл не найден.");
            string failure = Recycle.Delete(full);
            if (failure != null) return Error("io", failure);
            Log.Write("files: delete " + share.Name + "/" + rel);
            return Ok();
        }

        // ------------------------------------------------------------------ жизненный цикл загрузок

        /// <summary>Устройство отключилось: его незавершённые загрузки отбрасываются вместе с временными файлами.</summary>
        public void CloseDevice(string device)
        {
            var mine = new List<Upload>();
            lock (sync)
            {
                foreach (Upload u in uploads.Values) if (u.Device == device) mine.Add(u);
            }
            foreach (Upload u in mine) lock (u) Discard(u);
        }

        void AbortFor(string id)
        {
            Upload u = null;
            lock (sync)
            {
                if (id == null || !uploads.TryGetValue(id, out u)) return;
            }
            lock (u) Discard(u);
        }

        void Sweep()
        {
            var old = new List<Upload>();
            lock (sync)
            {
                foreach (Upload u in uploads.Values)
                {
                    if (unchecked(Environment.TickCount - (int)u.LastUse) > UploadIdleMs) old.Add(u);
                }
            }
            foreach (Upload u in old) lock (u) Discard(u);
        }

        /// <summary>Закрывает загрузку и стирает временный файл; зовётся под <c>lock (upload)</c>.</summary>
        void Discard(Upload u)
        {
            try { if (u.Stream != null) u.Stream.Close(); }
            catch (Exception) { }
            u.Stream = null;
            try { if (File.Exists(u.Temp)) File.Delete(u.Temp); }
            catch (Exception) { }
            lock (sync) uploads.Remove(u.Id);
        }

        // ------------------------------------------------------------------ вспомогательное

        string Display(string shareId, string full)
        {
            ShareConfig cfg = config();
            ShareDef s = cfg == null ? null : cfg.Find(shareId);
            return s == null ? full : s.Name + "/" + full.Substring(Math.Min(full.Length, s.Path.TrimEnd('\\').Length)).TrimStart('\\').Replace('\\', '/');
        }

        static string ParentOf(string rel)
        {
            string t = (rel ?? "").Trim('/');
            int slash = t.LastIndexOf('/');
            return slash < 0 ? "" : t.Substring(0, slash + 1);
        }

        static FsReply Ok()
        {
            var r = new FsReply();
            r.Header["ok"] = true;
            return r;
        }

        static FsReply Error(string code, string message)
        {
            var r = new FsReply();
            r.Header["ok"] = false;
            r.Header["err"] = code ?? "io";
            if (message != null) r.Header["msg"] = message;
            return r;
        }

        /// <summary>Исключение файловой системы → код ошибки протокола и понятный текст.</summary>
        static FsReply Fail(Exception e)
        {
            if (e is UnauthorizedAccessException) return Error("denied", "Windows не даёт доступ к этому файлу или папке.");
            if (e is FileNotFoundException || e is DirectoryNotFoundException) return Error("nf", "Файл не найден.");
            if (e is PathTooLongException) return Error("io", "Слишком длинный путь.");
            if (e is IOException)
            {
                int code = Marshal.GetHRForException(e) & 0xFFFF;
                if (code == 80 || code == 183) return Error("exists", "Такое имя уже занято.");
                if (code == 112) return Error("io", "На диске ПК не хватает места.");
                if (code == 32 || code == 33) return Error("busy", "Файл занят другой программой на ПК.");
                return Error("io", e.Message);
            }
            return Error("io", e.Message);
        }

        static string Str(Dictionary<string, object> d, string key)
        {
            object v;
            return d != null && d.TryGetValue(key, out v) && v != null ? v.ToString() : null;
        }

        /// <summary>Целое из JSON: JavaScriptSerializer отдаёт int, long или decimal в зависимости от величины.</summary>
        static long Num(Dictionary<string, object> d, string key, long fallback)
        {
            object v;
            if (d == null || !d.TryGetValue(key, out v) || v == null) return fallback;
            if (v is int) return (int)v;
            if (v is long) return (long)v;
            if (v is double) return (long)(double)v;
            if (v is decimal) return (long)(decimal)v;
            return fallback;
        }

        static bool Bool(Dictionary<string, object> d, string key, bool fallback)
        {
            object v;
            return d != null && d.TryGetValue(key, out v) && v is bool ? (bool)v : fallback;
        }

        static long UnixMs(DateTime utc)
        {
            return (long)(utc - new DateTime(1970, 1, 1, 0, 0, 0, DateTimeKind.Utc)).TotalMilliseconds;
        }
    }

    /// <summary>
    /// Удаление в корзину Windows без диалогов: SHFileOperation с «Отменить» (FOF_ALLOWUNDO). Файл, который в корзину
    /// не помещается, или файл на сетевом диске Windows удаляет сразу — как при удалении из проводника с подтверждением.
    /// Структура SHFILEOPSTRUCT в 32-битном процессе упакована по 1 байту, в 64-битном — по умолчанию.
    /// </summary>
    static class Recycle
    {
        const uint FO_DELETE = 3;
        const ushort FOF_SILENT = 0x4;
        const ushort FOF_NOCONFIRMATION = 0x10;
        const ushort FOF_ALLOWUNDO = 0x40;
        const ushort FOF_NOERRORUI = 0x400;

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode, Pack = 1)]
        struct Op32
        {
            public IntPtr Window;
            public uint Func;
            public string From;
            public string To;
            public ushort Flags;
            public int Aborted;
            public IntPtr Mappings;
            public string Title;
        }

        [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
        struct Op64
        {
            public IntPtr Window;
            public uint Func;
            public string From;
            public string To;
            public ushort Flags;
            public int Aborted;
            public IntPtr Mappings;
            public string Title;
        }

        [DllImport("shell32.dll", CharSet = CharSet.Unicode, EntryPoint = "SHFileOperationW")]
        static extern int Run(ref Op32 op);

        [DllImport("shell32.dll", CharSet = CharSet.Unicode, EntryPoint = "SHFileOperationW")]
        static extern int Run(ref Op64 op);

        /// <summary>Удаляет файл или папку; null — готово, иначе текст ошибки.</summary>
        public static string Delete(string path)
        {
            ushort flags = FOF_SILENT | FOF_NOCONFIRMATION | FOF_ALLOWUNDO | FOF_NOERRORUI;
            int result;
            bool aborted;
            if (IntPtr.Size == 4)
            {
                var op = new Op32();
                op.Func = FO_DELETE;
                op.From = path + "\0";
                op.Flags = flags;
                result = Run(ref op);
                aborted = op.Aborted != 0;
            }
            else
            {
                var op = new Op64();
                op.Func = FO_DELETE;
                op.From = path + "\0";
                op.Flags = flags;
                result = Run(ref op);
                aborted = op.Aborted != 0;
            }
            if (result != 0 || aborted) return "Windows не смогла удалить файл (код " + result + ").";
            return null;
        }
    }
}
