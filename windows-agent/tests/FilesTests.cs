using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Text;
using HiWebAgent;

namespace HiWebAgent
{
    /// <summary>Заглушка журнала агента: проверки не пишут в agent.log работающего агента.</summary>
    static class Log
    {
        public static void Write(string line)
        {
        }
    }
}

/// <summary>
/// Проверки доступа к файлам (FilesPolicy.cs, FileService.cs) на временной папке: настройки, разбор путей, защита от выхода
/// за корень, чтение, запись через временный файл, права «только чтение»; запуск — tests\test.cmd.
/// </summary>
static class FilesTests
{
    static int failed;
    static int passed;

    static void Check(bool ok, string name)
    {
        if (ok) { passed++; return; }
        failed++;
        Console.WriteLine("FAIL: " + name);
    }

    static void Eq(object expected, object actual, string name)
    {
        Check(Equals(expected, actual), name + " (ожидалось «" + expected + "», получено «" + actual + "»)");
    }

    static int Main()
    {
        string temp = Path.Combine(Path.GetTempPath(), "hiweb-files-test-" + Guid.NewGuid().ToString("N").Substring(0, 8));
        Directory.CreateDirectory(temp);
        try
        {
            Names();
            Splitting();
            Config(temp);
            Resolving(temp);
            Service(temp);
            Wire();
        }
        finally
        {
            try { Directory.Delete(temp, true); }
            catch (Exception) { }
        }
        Console.WriteLine("files: passed " + passed + ", failed " + failed);
        return failed == 0 ? 0 : 1;
    }

    // ------------------------------------------------------------------ имена и пути

    static void Names()
    {
        Check(SharePaths.ValidName("Отчёт 2026.docx"), "обычное имя");
        Check(SharePaths.ValidName(".gitignore"), "имя с точки в начале");
        Check(!SharePaths.ValidName(""), "пустое имя");
        Check(!SharePaths.ValidName("a/b") && !SharePaths.ValidName("a\\b"), "разделители пути");
        Check(!SharePaths.ValidName("a:b") && !SharePaths.ValidName("file.txt:stream"), "двоеточие (альтернативный поток)");
        Check(!SharePaths.ValidName("a*b") && !SharePaths.ValidName("a?b") && !SharePaths.ValidName("a|b") && !SharePaths.ValidName("a<b") && !SharePaths.ValidName("\"q\""), "запрещённые знаки");
        Check(!SharePaths.ValidName("name.") && !SharePaths.ValidName("name "), "точка и пробел в конце");
        Check(!SharePaths.ValidName(".") && !SharePaths.ValidName(".."), "точки");
        Check(!SharePaths.ValidName("CON") && !SharePaths.ValidName("nul.txt") && !SharePaths.ValidName("Com1") && !SharePaths.ValidName("LPT9.log"), "имена устройств Windows");
        Check(SharePaths.ValidName("CONSOLE") && SharePaths.ValidName("com10"), "похожие, но обычные имена");
        Check(!SharePaths.ValidName("tab\there"), "управляющий знак");
        Check(!SharePaths.ValidName(new string('a', 256)), "слишком длинное имя");
    }

    static void Splitting()
    {
        Eq(0, SharePaths.Split("").Length, "пустой путь — корень");
        Eq(0, SharePaths.Split(null).Length, "null — корень");
        Eq(0, SharePaths.Split("/").Length, "«/» — корень");
        Eq("a|b", string.Join("|", SharePaths.Split("a/b")), "два звена");
        Eq("a|b", string.Join("|", SharePaths.Split("/a/b/")), "лишние «/» по краям");
        Check(SharePaths.Split("a/../b") == null, "«..» внутри пути");
        Check(SharePaths.Split("..") == null, "«..» один");
        Check(SharePaths.Split("a//b") == null, "пустое звено");
        Check(SharePaths.Split("a/./b") == null, "«.» внутри пути");
    }

    // ------------------------------------------------------------------ настройки

    static void Config(string temp)
    {
        string docs = Directory.CreateDirectory(Path.Combine(temp, "Документы")).FullName;
        string pics = Directory.CreateDirectory(Path.Combine(temp, "Фото")).FullName;
        string other = Directory.CreateDirectory(Path.Combine(temp, "a", "Документы")).FullName;

        var c = new ShareConfig();
        Check(!c.Enabled && c.Shares.Count == 0, "по умолчанию доступ выключен, папок нет");
        string error;
        ShareDef d = c.Add(docs, false, out error);
        Check(d != null && error == null, "папка добавлена");
        Eq("Документы", d.Name, "название — имя папки");
        Check(!d.Write, "режим записи у новой папки задан вызовом");
        Check(!string.IsNullOrEmpty(d.Id) && d.Id.Length == 6, "идентификатор из шести знаков");
        Check(c.Add(docs.ToUpperInvariant() + "\\", true, out error) == null && error != null, "та же папка (регистр и слэш) повторно не добавляется");
        ShareDef p = c.Add(pics, true, out error);
        Check(p != null && p.Write, "вторая папка с записью");
        Check(p.Id != d.Id, "идентификаторы разные");
        ShareDef o = c.Add(other, false, out error);
        Eq("Документы (2)", o.Name, "одинаковое название получает номер");
        Check(c.Add(Path.Combine(temp, "нет-такой"), false, out error) == null, "несуществующая папка");
        Check(c.Add("", false, out error) == null, "пустой путь");

        c.Rename(o, "Работа");
        Eq("Работа", o.Name, "переименование");
        c.Rename(o, "документы");
        Eq("документы (2)", o.Name, "переименование в занятое название (без учёта регистра) — с номером");
        c.Rename(o, "   ");
        Eq("Документы (2)", o.Name, "пустое имя возвращает имя папки");
        c.Rename(d, "Документы");
        Eq("Документы", d.Name, "своё же название не считается занятым");
        c.Rename(p, new string('я', 100));
        Eq(ShareConfig.MaxName, p.Name.Length, "длинное название обрезается");

        Check(ShareConfig.LastName("C:\\") == "Диск C", "корень диска называется «Диск C»");
        Eq("Фото", ShareConfig.LastName("D:\\Фото\\"), "имя папки со слэшем на конце");

        // Сохранение и чтение.
        c.Enabled = true;
        ShareConfig back = ShareConfig.FromJson(c.ToJson());
        Check(back.Enabled && back.Shares.Count == 3, "JSON: включено и три папки");
        ShareDef bp = back.Find(p.Id);
        Check(bp != null && bp.Write && bp.Path == p.Path && bp.Name == p.Name, "JSON: папка с записью сохранилась целиком");
        Check(back.Find(d.Id) != null && !back.Find(d.Id).Write, "JSON: «только чтение» сохранилось");
        Check(!ShareConfig.FromJson("не json").Enabled, "испорченный JSON — доступ выключен");
        Check(!ShareConfig.FromJson("").Enabled && !ShareConfig.FromJson(null).Enabled, "пустое значение — доступ выключен");
        Eq(0, ShareConfig.FromJson("{\"enabled\":true,\"shares\":[{\"id\":\"\",\"path\":\"C:\\\\\"},{\"id\":\"x\"}]}").Shares.Count, "записи без идентификатора или пути пропускаются");

        // Что видит устройство.
        var caps = c.CapsValue();
        Eq(true, caps["on"], "caps: включено");
        Eq(3, ((List<object>)caps["shares"]).Count, "caps: три диска");
        var first = (Dictionary<string, object>)((List<object>)caps["shares"])[0];
        Check(first.ContainsKey("id") && first.ContainsKey("name") && first.ContainsKey("w") && !first.ContainsKey("path"), "caps: путь на ПК устройству не показывается");
        c.Enabled = false;
        Eq(0, ((List<object>)c.CapsValue()["shares"]).Count, "caps: выключено — дисков нет");
        Eq(false, c.CapsValue()["on"], "caps: выключено");

        var many = new ShareConfig();
        for (int i = 0; i < ShareConfig.MaxShares; i++)
        {
            string dir = Directory.CreateDirectory(Path.Combine(temp, "m" + i)).FullName;
            Check(many.Add(dir, false, out error) != null, "папка " + i + " в пределах предела");
        }
        Check(many.Add(temp, false, out error) == null && error != null, "больше предела папок не добавить");
    }

    // ------------------------------------------------------------------ защита путей

    static void Resolving(string temp)
    {
        string root = Directory.CreateDirectory(Path.Combine(temp, "root")).FullName;
        Directory.CreateDirectory(Path.Combine(root, "sub"));
        File.WriteAllText(Path.Combine(root, "sub", "f.txt"), "x");
        string outside = Directory.CreateDirectory(Path.Combine(temp, "outside")).FullName;
        File.WriteAllText(Path.Combine(outside, "secret.txt"), "secret");
        var share = new ShareDef();
        share.Id = "r1";
        share.Name = "root";
        share.Path = root;

        string error;
        Eq(root, SharePaths.Resolve(share, "", out error), "корень");
        Eq(Path.Combine(root, "sub", "f.txt"), SharePaths.Resolve(share, "sub/f.txt", out error), "файл внутри");
        Check(SharePaths.Resolve(share, "../outside/secret.txt", out error) == null && error == "bad", "«..» отклоняется как неверный путь");
        Check(SharePaths.Resolve(share, "sub/..", out error) == null && error == "bad", "«..» в конце");
        Check(SharePaths.Resolve(share, "sub/f.txt:stream", out error) == null && error == "bad", "альтернативный поток");
        Check(SharePaths.Resolve(share, "C:/Windows", out error) == null && error == "bad", "диск внутри пути");
        Check(SharePaths.Resolve(share, "sub\\f.txt", out error) == null && error == "bad", "обратный слэш");
        Check(SharePaths.Resolve(share, "nul", out error) == null && error == "bad", "имя устройства");
        Eq(Path.Combine(root, "нет", "файла"), SharePaths.Resolve(share, "нет/файла", out error), "несуществующий путь допустим (его проверяет операция)");

        // Переход (junction) внутри папки, ведущий наружу, закрыт.
        string junction = Path.Combine(root, "link");
        bool made = MakeJunction(junction, outside);
        if (made)
        {
            Check(SharePaths.IsLink(junction), "junction распознан как ссылка");
            Check(!SharePaths.IsLink(root) && !SharePaths.IsLink(Path.Combine(root, "sub")), "обычные папки — не ссылки");
            Check(SharePaths.Resolve(share, "link/secret.txt", out error) == null && error == "denied", "путь через junction закрыт");
            Check(SharePaths.Resolve(share, "link", out error) == null && error == "denied", "сам junction закрыт");

            // Корень, сам являющийся junction, допустим: его выбрал владелец.
            var viaLink = new ShareDef();
            viaLink.Id = "r2";
            viaLink.Name = "via";
            viaLink.Path = junction;
            Check(SharePaths.Resolve(viaLink, "secret.txt", out error) != null, "корень-ссылка, выбранный владельцем, работает");

            // В списке ссылка не показывается.
            var service = new FileService(delegate { return Cfg(true, share); });
            var list = service.Handle("dev", Req("list", "r1", ""), null);
            Eq(true, list.Header["ok"], "список корня с junction");
            Check(!Names(list).Contains("link"), "junction не попал в список");
            Check(Names(list).Contains("sub"), "обычная папка в списке");
            Directory.Delete(junction);
        }
        else
        {
            Console.WriteLine("пропущено: не удалось создать junction");
        }

        // Данные самого агента закрыты, даже внутри открытой папки.
        SharePaths.Forbidden.Add(Path.Combine(root, "sub"));
        Check(SharePaths.Resolve(share, "sub/f.txt", out error) == null && error == "denied", "каталог агента внутри открытой папки закрыт");
        Check(SharePaths.Resolve(share, "sub", out error) == null && error == "denied", "сам каталог агента закрыт");
        Check(SharePaths.Resolve(share, "", out error) != null, "остальная папка открыта");
        SharePaths.Forbidden.Clear();

        Check(SharePaths.IsInside(root, Path.Combine(root, "a")) && SharePaths.IsInside(root + "\\", root), "IsInside: внутри и сам корень");
        Check(!SharePaths.IsInside(root, root + "2") && !SharePaths.IsInside(root, Path.Combine(temp, "ro")), "IsInside: соседняя папка с тем же началом имени снаружи");
    }

    static bool MakeJunction(string link, string target)
    {
        try
        {
            var psi = new ProcessStartInfo("cmd.exe", "/c mklink /J \"" + link + "\" \"" + target + "\"");
            psi.CreateNoWindow = true;
            psi.UseShellExecute = false;
            psi.RedirectStandardOutput = true;
            using (Process p = Process.Start(psi))
            {
                p.StandardOutput.ReadToEnd();
                p.WaitForExit();
                return p.ExitCode == 0 && Directory.Exists(link);
            }
        }
        catch (Exception)
        {
            return false;
        }
    }

    // ------------------------------------------------------------------ сервис

    static ShareConfig Cfg(bool enabled, params ShareDef[] shares)
    {
        var c = new ShareConfig();
        c.Enabled = enabled;
        c.Shares.AddRange(shares);
        return c;
    }

    static Dictionary<string, object> Req(string op, string share, string path)
    {
        var r = new Dictionary<string, object>();
        r["op"] = op;
        r["share"] = share;
        r["path"] = path;
        return r;
    }

    static List<string> Names(FsReply list)
    {
        var names = new List<string>();
        foreach (object o in (List<object>)list.Header["items"]) names.Add((string)((Dictionary<string, object>)o)["n"]);
        return names;
    }

    static ShareDef Share(string id, string path, bool write)
    {
        var s = new ShareDef();
        s.Id = id;
        s.Name = id;
        s.Path = path;
        s.Write = write;
        return s;
    }

    static string Err(FsReply r)
    {
        return (bool)r.Header["ok"] ? null : (string)r.Header["err"];
    }

    static bool Ok(FsReply r)
    {
        return (bool)r.Header["ok"];
    }

    static FsReply Put(FileService s, string share, string path, byte[] data, bool overwrite, int chunk)
    {
        var begin = Req("put_begin", share, path);
        begin["overwrite"] = overwrite;
        FsReply b = s.Handle("dev", begin, null);
        if (!Ok(b)) return b;
        string id = (string)b.Header["up"];
        for (int off = 0; off < data.Length; off += chunk)
        {
            int n = Math.Min(chunk, data.Length - off);
            var part = new byte[n];
            Buffer.BlockCopy(data, off, part, 0, n);
            var put = Req("put", share, path);
            put["up"] = id;
            put["off"] = (long)off;
            FsReply r = s.Handle("dev", put, part);
            if (!Ok(r)) return r;
        }
        var end = Req("put_end", share, path);
        end["up"] = id;
        end["size"] = (long)data.Length;
        return s.Handle("dev", end, null);
    }

    static void Service(string temp)
    {
        string rw = Directory.CreateDirectory(Path.Combine(temp, "rw")).FullName;
        string ro = Directory.CreateDirectory(Path.Combine(temp, "ro")).FullName;
        File.WriteAllBytes(Path.Combine(rw, "data.bin"), Sequence(1000));
        Directory.CreateDirectory(Path.Combine(rw, "folder"));
        File.WriteAllText(Path.Combine(ro, "readme.txt"), "только чтение", Encoding.UTF8);

        bool enabled = true;
        ShareDef rwShare = Share("rw", rw, true);
        ShareDef roShare = Share("ro", ro, false);
        var service = new FileService(delegate { return Cfg(enabled, rwShare, roShare); });

        // Выключатель.
        enabled = false;
        Eq("off", Err(service.Handle("dev", Req("list", "rw", ""), null)), "доступ выключен — list отклонён");
        Eq("off", Err(service.Handle("dev", Req("read", "rw", "data.bin"), null)), "доступ выключен — read отклонён");
        enabled = true;
        Eq("nf", Err(service.Handle("dev", Req("list", "zzz", ""), null)), "неизвестная папка");
        Eq("bad", Err(service.Handle("dev", Req("explode", "rw", ""), null)), "неизвестная операция");

        // Список и stat.
        FsReply list = service.Handle("dev", Req("list", "rw", ""), null);
        Check(Ok(list), "list корня");
        Check(Names(list).Contains("data.bin") && Names(list).Contains("folder"), "в списке файл и папка");
        foreach (object o in (List<object>)list.Header["items"])
        {
            var item = (Dictionary<string, object>)o;
            if ((string)item["n"] == "data.bin")
            {
                Eq(1000L, item["s"], "размер файла в списке");
                Eq(false, item["d"], "файл — не папка");
                Check((long)item["m"] > 1500000000000L, "дата изменения в миллисекундах Unix");
            }
            if ((string)item["n"] == "folder") Eq(true, item["d"], "папка помечена");
        }
        Eq("nf", Err(service.Handle("dev", Req("list", "rw", "нет"), null)), "list несуществующей папки");
        Eq("bad", Err(service.Handle("dev", Req("list", "rw", "data.bin"), null)), "list файла");
        Eq("bad", Err(service.Handle("dev", Req("list", "rw", "../ro"), null)), "list с «..»");
        FsReply root = service.Handle("dev", Req("stat", "rw", ""), null);
        Eq("rw", ((Dictionary<string, object>)root.Header["item"])["n"], "stat корня называет папку её названием");
        Eq(true, ((Dictionary<string, object>)root.Header["item"])["d"], "stat корня — папка");
        Eq(1000L, ((Dictionary<string, object>)service.Handle("dev", Req("stat", "rw", "data.bin"), null).Header["item"])["s"], "stat файла");
        Eq("nf", Err(service.Handle("dev", Req("stat", "rw", "нет.txt"), null)), "stat несуществующего");

        // Чтение кусками.
        var read = Req("read", "rw", "data.bin");
        read["off"] = 0L;
        read["len"] = 300L;
        FsReply r1 = service.Handle("dev", read, null);
        Check(Ok(r1) && r1.Payload.Length == 300 && Equal(Sequence(1000), 0, r1.Payload), "read: первый кусок");
        Eq(false, r1.Header["eof"], "read: конец файла ещё не достигнут");
        Eq(1000L, r1.Header["size"], "read: размер файла");
        read["off"] = 900L;
        read["len"] = 300L;
        FsReply r2 = service.Handle("dev", read, null);
        Check(r2.Payload.Length == 100 && Equal(Sequence(1000), 900, r2.Payload), "read: хвост короче запрошенного");
        Eq(true, r2.Header["eof"], "read: конец файла");
        read["off"] = 5000L;
        FsReply r3 = service.Handle("dev", read, null);
        Check(Ok(r3) && r3.Payload.Length == 0 && (bool)r3.Header["eof"], "read за концом файла — пусто и eof");
        read["off"] = -1L;
        Eq("bad", Err(service.Handle("dev", read, null)), "read с отрицательным смещением");
        Eq("nf", Err(service.Handle("dev", Req("read", "rw", "нет.bin"), null)), "read несуществующего");
        Eq("bad", Err(service.Handle("dev", Req("read", "rw", "folder"), null)), "read папки");
        var big = Req("read", "rw", "data.bin");
        big["len"] = 99999999L;
        Check(service.Handle("dev", big, null).Payload.Length == 1000, "слишком большой len ограничивается размером файла");

        // Файл, открытый программой на ПК, читается.
        using (var busy = new FileStream(Path.Combine(rw, "data.bin"), FileMode.Open, FileAccess.ReadWrite, FileShare.ReadWrite))
        {
            Check(Ok(service.Handle("dev", Req("read", "rw", "data.bin"), null)), "файл, открытый другой программой, читается");
        }

        // Запись: новый файл кусками, потом замена.
        byte[] content = Sequence(700000);
        FsReply put = Put(service, "rw", "folder/new.bin", content, false, FileService.MaxChunk);
        Check(Ok(put), "загрузка нового файла");
        Check(File.Exists(Path.Combine(rw, "folder", "new.bin")) && Equal(content, 0, File.ReadAllBytes(Path.Combine(rw, "folder", "new.bin"))), "содержимое загруженного файла совпало");
        Check(Directory.GetFiles(Path.Combine(rw, "folder"), ".hiweb-*").Length == 0, "временных файлов не осталось");
        Eq("exists", Err(Put(service, "rw", "folder/new.bin", Sequence(10), false, 100)), "загрузка поверх существующего без overwrite");
        Check(Equal(content, 0, File.ReadAllBytes(Path.Combine(rw, "folder", "new.bin"))), "отклонённая загрузка не тронула файл");
        Check(Directory.GetFiles(Path.Combine(rw, "folder"), ".hiweb-*").Length == 0, "после отказа временного файла нет");
        Check(Ok(Put(service, "rw", "folder/new.bin", Sequence(10), true, 100)), "загрузка поверх существующего с overwrite");
        Eq(10, File.ReadAllBytes(Path.Combine(rw, "folder", "new.bin")).Length, "файл заменён");
        Check(Ok(Put(service, "rw", "empty.txt", new byte[0], false, 100)), "пустой файл");
        Eq(0L, new FileInfo(Path.Combine(rw, "empty.txt")).Length, "пустой файл создан");
        Eq("exists", Err(Put(service, "rw", "folder", new byte[1], true, 10)), "нельзя записать файл на место папки");
        Eq("nf", Err(Put(service, "rw", "нет/файл.txt", new byte[1], false, 10)), "папки назначения нет");
        Eq("bad", Err(Put(service, "rw", "con", new byte[1], false, 10)), "запись в имя устройства");
        Eq("bad", Err(Put(service, "rw", "", new byte[1], false, 10)), "запись в корень");

        // Порядок кусков и размер.
        var begin = Req("put_begin", "rw", "order.bin");
        string id = (string)service.Handle("dev", begin, null).Header["up"];
        var wrong = Req("put", "rw", "order.bin");
        wrong["up"] = id;
        wrong["off"] = 5L;
        Eq("bad", Err(service.Handle("dev", wrong, new byte[3])), "кусок не по порядку отклоняется");
        Check(!File.Exists(Path.Combine(rw, "order.bin")) && Directory.GetFiles(rw, ".hiweb-*").Length == 0, "после сбоя порядка загрузка отброшена целиком");
        Eq("nf", Err(service.Handle("dev", wrong, new byte[3])), "отброшенная загрузка больше не принимает куски");
        string id2 = (string)service.Handle("dev", Req("put_begin", "rw", "size.bin"), null).Header["up"];
        var ok1 = Req("put", "rw", "size.bin");
        ok1["up"] = id2;
        ok1["off"] = 0L;
        Check(Ok(service.Handle("dev", ok1, new byte[4])), "кусок принят");
        var end = Req("put_end", "rw", "size.bin");
        end["up"] = id2;
        end["size"] = 9L;
        Eq("bad", Err(service.Handle("dev", end, null)), "итоговый размер не сошёлся");
        Check(!File.Exists(Path.Combine(rw, "size.bin")), "файл с неверным размером не создан");

        // Отмена и отключение устройства.
        string id3 = (string)service.Handle("dev", Req("put_begin", "rw", "abort.bin"), null).Header["up"];
        Check(Directory.GetFiles(rw, ".hiweb-*").Length == 1, "во время загрузки рядом лежит временный файл");
        var abort = Req("put_abort", "rw", "abort.bin");
        abort["up"] = id3;
        Check(Ok(service.Handle("dev", abort, null)) && Directory.GetFiles(rw, ".hiweb-*").Length == 0, "put_abort стирает временный файл");
        service.Handle("dev", Req("put_begin", "rw", "gone.bin"), null);
        service.Handle("other", Req("put_begin", "rw", "kept.bin"), null);
        service.CloseDevice("dev");
        Eq(1, Directory.GetFiles(rw, ".hiweb-*").Length, "отключение устройства стирает только его загрузки");
        service.CloseDevice("other");
        Eq(0, Directory.GetFiles(rw, ".hiweb-*").Length, "и загрузки второго устройства");
        string idOther = (string)service.Handle("dev", Req("put_begin", "rw", "mine.bin"), null).Header["up"];
        var steal = Req("put", "rw", "mine.bin");
        steal["up"] = idOther;
        steal["off"] = 0L;
        Eq("nf", Err(service.Handle("other", steal, new byte[1])), "чужую загрузку продолжить нельзя");
        service.CloseDevice("dev");
        var many = new List<string>();
        for (int i = 0; i < 8; i++) many.Add((string)service.Handle("dev", Req("put_begin", "rw", "m" + i + ".bin"), null).Header["up"]);
        Eq("busy", Err(service.Handle("dev", Req("put_begin", "rw", "m9.bin"), null)), "не больше восьми незавершённых загрузок на устройство");
        service.CloseDevice("dev");

        // Папка, права.
        Check(Ok(service.Handle("dev", Req("mkdir", "rw", "folder/inner"), null)) && Directory.Exists(Path.Combine(rw, "folder", "inner")), "mkdir");
        Eq("exists", Err(service.Handle("dev", Req("mkdir", "rw", "folder/inner"), null)), "mkdir поверх существующей");
        Eq("exists", Err(service.Handle("dev", Req("mkdir", "rw", "data.bin"), null)), "mkdir на месте файла");
        Eq("nf", Err(service.Handle("dev", Req("mkdir", "rw", "нет/внутри"), null)), "mkdir без родителя");
        Eq("bad", Err(service.Handle("dev", Req("mkdir", "rw", "a:b"), null)), "mkdir с недопустимым именем");

        // Переименование.
        var rename = Req("rename", "rw", "folder/inner");
        rename["name"] = "renamed";
        Check(Ok(service.Handle("dev", rename, null)) && Directory.Exists(Path.Combine(rw, "folder", "renamed")) && !Directory.Exists(Path.Combine(rw, "folder", "inner")), "переименование папки");
        rename = Req("rename", "rw", "empty.txt");
        rename["name"] = "EMPTY.txt";
        Check(Ok(service.Handle("dev", rename, null)), "смена регистра имени");
        Eq("EMPTY.txt", new List<string>(Names(service.Handle("dev", Req("list", "rw", ""), null))).Find(delegate(string n) { return n.ToLowerInvariant() == "empty.txt"; }), "имя изменилось");
        rename = Req("rename", "rw", "EMPTY.txt");
        rename["name"] = "data.bin";
        Eq("exists", Err(service.Handle("dev", rename, null)), "переименование в занятое имя");
        rename["name"] = "sub/x";
        Eq("bad", Err(service.Handle("dev", rename, null)), "имя с «/» — не имя");
        rename["name"] = "nul";
        Eq("bad", Err(service.Handle("dev", rename, null)), "имя устройства");
        rename = Req("rename", "rw", "");
        rename["name"] = "x";
        Eq("bad", Err(service.Handle("dev", rename, null)), "корень не переименовать");
        rename = Req("rename", "rw", "нет.txt");
        rename["name"] = "x";
        Eq("nf", Err(service.Handle("dev", rename, null)), "переименование несуществующего");

        // Удаление (в корзину).
        File.WriteAllText(Path.Combine(rw, "trash.txt"), "в корзину");
        Check(Ok(service.Handle("dev", Req("delete", "rw", "trash.txt"), null)) && !File.Exists(Path.Combine(rw, "trash.txt")), "удаление файла");
        Directory.CreateDirectory(Path.Combine(rw, "dir-to-delete", "x"));
        File.WriteAllText(Path.Combine(rw, "dir-to-delete", "x", "f.txt"), "1");
        Check(Ok(service.Handle("dev", Req("delete", "rw", "dir-to-delete"), null)) && !Directory.Exists(Path.Combine(rw, "dir-to-delete")), "удаление папки с содержимым");
        Eq("nf", Err(service.Handle("dev", Req("delete", "rw", "trash.txt"), null)), "повторное удаление");
        Eq("bad", Err(service.Handle("dev", Req("delete", "rw", ""), null)), "корень открытой папки не удалить");
        Check(Directory.Exists(rw), "корень на месте");

        // Только чтение: ничего, что меняет файлы.
        Check(Ok(service.Handle("dev", Req("list", "ro", ""), null)), "чтение в «только чтение» работает");
        Check(Ok(service.Handle("dev", Req("read", "ro", "readme.txt"), null)), "read в «только чтение»");
        Eq("ro", Err(Put(service, "ro", "new.txt", new byte[1], false, 10)), "put_begin в «только чтение»");
        Eq("ro", Err(service.Handle("dev", Req("mkdir", "ro", "d"), null)), "mkdir в «только чтение»");
        rename = Req("rename", "ro", "readme.txt");
        rename["name"] = "x.txt";
        Eq("ro", Err(service.Handle("dev", rename, null)), "rename в «только чтение»");
        Eq("ro", Err(service.Handle("dev", Req("delete", "ro", "readme.txt"), null)), "delete в «только чтение»");
        Check(File.Exists(Path.Combine(ro, "readme.txt")) && Directory.GetFileSystemEntries(ro).Length == 1, "«только чтение» ничего не изменило");

        // Права меняют посреди загрузки: загрузка не завершается.
        string idMid = (string)service.Handle("dev", Req("put_begin", "rw", "mid.bin"), null).Header["up"];
        rwShare.Write = false;
        var midPut = Req("put", "rw", "mid.bin");
        midPut["up"] = idMid;
        midPut["off"] = 0L;
        Eq("ro", Err(service.Handle("dev", midPut, new byte[1])), "папку сделали «только чтение» посреди загрузки");
        Check(Directory.GetFiles(rw, ".hiweb-*").Length == 0 && !File.Exists(Path.Combine(rw, "mid.bin")), "загрузка отброшена");
        rwShare.Write = true;

        // Место на диске.
        FsReply space = service.Handle("dev", Req("space", "rw", ""), null);
        Check(Ok(space) && (long)space.Header["free"] > 0 && (long)space.Header["total"] >= (long)space.Header["free"], "space");
    }

    static byte[] Sequence(int length)
    {
        var data = new byte[length];
        for (int i = 0; i < length; i++) data[i] = (byte)(i * 7 + (i >> 8));
        return data;
    }

    static bool Equal(byte[] full, int offset, byte[] part)
    {
        if (offset + part.Length > full.Length) return false;
        for (int i = 0; i < part.Length; i++) if (full[offset + i] != part[i]) return false;
        return true;
    }

    // ------------------------------------------------------------------ формат сообщения

    static void Wire()
    {
        var header = new Dictionary<string, object>();
        header["op"] = "read";
        header["path"] = "Папка/файл 1.txt";
        header["off"] = 5000000000L;
        byte[] payload = Sequence(300);
        byte[] packed = FsWire.Pack(header, payload);
        Dictionary<string, object> h;
        byte[] p;
        Check(FsWire.Unpack(packed, out h, out p), "Unpack принимает свой Pack");
        Eq("Папка/файл 1.txt", h["path"], "заголовок: юникод");
        Eq(5000000000L, Convert.ToInt64(h["off"]), "заголовок: число больше 32 бит");
        Check(Equal(payload, 0, p) && p.Length == 300, "данные дошли без изменений");
        Check(FsWire.Unpack(FsWire.Pack(header, null), out h, out p) && p.Length == 0, "сообщение без данных");
        Eq(0, (int)packed[0], "длина заголовка: старший байт");
        Eq(Encoding.UTF8.GetBytes(new System.Web.Script.Serialization.JavaScriptSerializer().Serialize(header)).Length, (packed[2] << 8) | packed[3], "длина заголовка big-endian");
        Check(!FsWire.Unpack(null, out h, out p) && !FsWire.Unpack(new byte[3], out h, out p), "слишком короткое сообщение");
        Check(!FsWire.Unpack(new byte[] { 0, 0, 1, 0, 1, 2 }, out h, out p), "длина заголовка больше сообщения");
        Check(!FsWire.Unpack(new byte[] { 0, 0, 0, 3, (byte)'x', (byte)'y', (byte)'z' }, out h, out p), "заголовок — не JSON");
    }
}
