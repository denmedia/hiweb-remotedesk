using System;
using System.Collections.Generic;
using System.Net;
using HiWebAgent;

/// <summary>Проверки IpTools и ThreatDetector без интерфейса и журнала Windows; запуск — tests\test.cmd.</summary>
static class GuardRulesTests
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

    const long MIN = 60 * 1000;
    const long T0 = 1700000000000L;

    static Whitelist Listed(params string[] cidrs)
    {
        var w = new Whitelist();
        foreach (string c in cidrs) w.Add(c, WhitelistEntry.Manual, T0);
        return w;
    }

    static ThreatDetector Detector(Whitelist list, GuardSettings settings, List<string> learned)
    {
        return new ThreatDetector(list, settings, delegate(string cidr) { learned.Add(cidr); });
    }

    static ThreatDetector Detector(Whitelist list, List<string> learned)
    {
        return Detector(list, new GuardSettings(), learned);
    }

    static GuardSettings Strict(bool autoLearn, bool learnSubnet)
    {
        var s = new GuardSettings();
        s.Mode = GuardMode.Whitelist;
        s.AutoLearn = autoLearn;
        s.LearnSubnet = learnSubnet;
        return s;
    }

    static RdpEvent Ev(string kind, long time, string ip)
    {
        var e = new RdpEvent();
        e.Kind = kind;
        e.Time = time;
        e.Ip = ip;
        e.User = "PC\\user";
        return e;
    }

    static void Main()
    {
        Console.OutputEncoding = System.Text.Encoding.UTF8;
        Addresses();
        Blocking();
        BruteForce();
        Logons();
        WhitelistStorage();
        Learning();
        WhitelistMode();
        Settings();
        Console.WriteLine("passed " + passed + ", failed " + failed);
        Environment.Exit(failed == 0 ? 0 : 1);
    }

    static void Addresses()
    {
        Eq("198.51.100.71", IpTools.Normalize("198.51.100.71:39342"), "порт IPv4 отбрасывается");
        Eq("2001:db8::1", IpTools.Normalize("[2001:db8::1]:3389"), "IPv6 в скобках с портом");
        Eq("1.2.3.4", IpTools.Normalize("::ffff:1.2.3.4"), "IPv4 в IPv6 сворачивается");
        Eq(null, IpTools.Normalize("LOCAL"), "LOCAL не адрес");
        Eq(null, IpTools.Normalize("ЛОКАЛЬНЫЕ"), "ЛОКАЛЬНЫЕ не адрес");
        Eq(null, IpTools.Normalize("1"), "«1» не адрес");
        Eq(null, IpTools.Normalize(""), "пустая строка");
        foreach (string local in new[] { "192.168.1.1", "10.1.2.3", "172.16.0.1", "172.31.255.1", "127.0.0.1", "169.254.1.1", "100.64.0.1", "100.127.1.1", "::1", "fe80::1", "fd00::1" })
        {
            IPAddress ip;
            Check(IpTools.TryParse(local, out ip) && IpTools.IsLocal(ip), local + " — свой");
        }
        foreach (string pub in new[] { "198.51.100.71", "172.32.0.1", "172.15.0.1", "100.63.0.1", "100.128.0.1", "8.8.8.8", "2001:db8::1" })
        {
            IPAddress ip;
            Check(IpTools.TryParse(pub, out ip) && !IpTools.IsLocal(ip), pub + " — чужой");
        }
        IPAddress a;
        IpTools.TryParse("198.51.100.71", out a);
        Eq("198.51.100.0/24", IpTools.Subnet(a), "подсеть IPv4");
        Eq("198.51.100.71/32", IpTools.Single(a), "один адрес");
        IPAddress v6;
        IpTools.TryParse("2001:db8:1:2:3:4:5:6", out v6);
        Eq("2001:db8:1:2::/64", IpTools.Subnet(v6), "подсеть IPv6");
        Eq("2001:db8:1:2:3:4:5:6/128", IpTools.Single(v6), "один адрес IPv6");
    }

    static void Blocking()
    {
        Eq("198.51.100.0/24", IpTools.NormalizeBlock("198.51.100.71/24"), "host-биты обнуляются");
        Eq("198.51.100.0/24", IpTools.NormalizeBlock("198.51.100.0/255.255.255.0"), "маска вместо длины");
        Eq("198.51.100.71/32", IpTools.NormalizeBlock("198.51.100.71"), "адрес без длины — /32");
        Eq("198.51.0.0/16", IpTools.NormalizeBlock("198.51.100.71/16"), "/16 — предел");
        Eq(null, IpTools.NormalizeBlock("198.0.0.0/8"), "шире /16 нельзя");
        Eq(null, IpTools.NormalizeBlock("0.0.0.0/0"), "весь интернет нельзя");
        Eq(null, IpTools.NormalizeBlock("192.168.1.0/24"), "локальную сеть нельзя");
        Eq(null, IpTools.NormalizeBlock("10.0.0.5"), "частный адрес нельзя");
        Eq(null, IpTools.NormalizeBlock("198.51.100.0/33"), "длина больше 32");
        Eq(null, IpTools.NormalizeBlock("198.51.100.0/255.0.255.0"), "неверная маска");
        Eq(null, IpTools.NormalizeBlock("мусор"), "не адрес");
        Eq(null, IpTools.NormalizeBlock("2001:db8::/32"), "IPv6 шире /48 нельзя");
        Eq("2001:db8:1:2::/64", IpTools.NormalizeBlock("2001:db8:1:2:3:4:5:6/64"), "IPv6 /64");
        IPAddress ip;
        IpTools.TryParse("198.51.100.71", out ip);
        Check(IpTools.InCidr(ip, "198.51.100.0/24"), "адрес в своей подсети");
        Check(!IpTools.InCidr(ip, "198.51.101.0/24"), "адрес не в соседней подсети");
        Check(IpTools.InCidr(ip, "198.51.100.71/32"), "адрес в /32");
        Check(IpTools.InCidr(ip, "198.51.100.0/255.255.255.0"), "подсеть с маской, как её отдаёт брандмауэр");
        Check(IpTools.InCidr(ip, "198.48.0.0/12"), "не по границе октета");
        Check(!IpTools.InCidr(ip, "198.64.0.0/12"), "не по границе октета: вне");
    }

    static void BruteForce()
    {
        var d = Detector(new Whitelist(), new List<string>());
        var got = new List<Threat>();
        // Две попытки — ещё не повод; третья из той же подсети (другой адрес) — подбор.
        for (int i = 0; i < 2; i++) got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + i * 30000, "198.51.100." + (70 + i))));
        Eq(0, got.Count, "две попытки не тревожат");
        got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + 2 * 30000, "198.51.100.99")));
        Eq(1, got.Count, "третья попытка из подсети — тревога");
        Eq(Threat.BruteForce, got[0].Rule, "правило");
        Eq("198.51.100.0/24", got[0].Cidr, "предлагается подсеть");
        Eq(3, got[0].Count, "число попыток");
        Eq(3, ThreatDetector.FailThreshold, "порог — три попытки");
        // Продолжение той же атаки в течение часа не повторяет тревогу.
        got.Clear();
        for (int i = 0; i < 50; i++) got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + 3 * MIN + i * 30000, "198.51.100.71")));
        Eq(0, got.Count, "та же атака не тревожит повторно");
        // Через час, если атака идёт, — новая тревога (блокировка могла не подействовать).
        got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + 70 * MIN, "198.51.100.71")));
        got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + 70 * MIN + 1000, "198.51.100.71")));
        Eq(0, got.Count, "две попытки через час — окно ещё не набралось");
        got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + 70 * MIN + 2000, "198.51.100.71")));
        Eq(1, got.Count, "через час атака снова заметна");

        // Попытки реже порога окна не складываются.
        var slow = Detector(new Whitelist(), new List<string>());
        got.Clear();
        for (int i = 0; i < 20; i++) got.AddRange(slow.Feed(Ev(GuardKind.Fail, T0 + i * 6 * MIN, "8.8.8.8")));
        Eq(0, got.Count, "одна попытка в 6 минут не подбор");

        // Свои адреса и локальная сеть не тревожат.
        var own = Detector(Listed("7.7.7.7"), new List<string>());
        got.Clear();
        for (int i = 0; i < 10; i++)
        {
            got.AddRange(own.Feed(Ev(GuardKind.Fail, T0 + i * 1000, "7.7.7.7")));
            got.AddRange(own.Feed(Ev(GuardKind.Fail, T0 + i * 1000, "192.168.1.50")));
        }
        Eq(0, got.Count, "свой адрес и LAN не тревожат");

        // В подсети есть свой адрес: блокировать предлагается только нападающего.
        var mixed = Detector(Listed("198.51.100.10"), new List<string>());
        got.Clear();
        for (int i = 0; i < 3; i++) got.AddRange(mixed.Feed(Ev(GuardKind.Fail, T0 + i * 1000, "198.51.100.71")));
        Eq(1, got.Count, "тревога при своём адресе в подсети");
        Eq("198.51.100.71/32", got[0].Cidr, "в подсети есть свой — блокируем один адрес");

        // Запомненная до перезапуска угроза не повторяется.
        var restarted = Detector(new Whitelist(), new List<string>());
        var old = new Threat();
        old.Rule = Threat.BruteForce;
        old.Cidr = "198.51.100.0/24";
        old.Time = T0;
        restarted.Remember(old);
        got.Clear();
        for (int i = 0; i < 5; i++) got.AddRange(restarted.Feed(Ev(GuardKind.Fail, T0 + 10 * MIN + i * 1000, "198.51.100.71")));
        Eq(0, got.Count, "после перезапуска о той же атаке молчим");
    }

    static void Logons()
    {
        // Вход, после которого открылся канал планшета, — свой: адрес запоминается.
        var list = new Whitelist();
        var learned = new List<string>();
        var d = Detector(list, learned);
        d.Feed(Ev(GuardKind.Logon, T0, "9.9.9.9"));
        d.TabletOpened(T0 + 8000);
        Eq(0, d.Tick(T0 + 10000).Count, "до конца ожидания решения нет");
        Eq(0, d.Tick(T0 + 31000).Count, "канал открылся — не тревога");
        Eq(1, learned.Count, "адрес запомнен");
        Eq("9.9.9.9/32", learned[0], "запомнен один адрес");
        Check(list.Contains("9.9.9.9"), "адрес стал знакомым");
        Eq(WhitelistEntry.Auto, list.Find("9.9.9.9/32").Source, "источник — автоматически");

        // Вход без планшета — тревога.
        var d2 = Detector(new Whitelist(), new List<string>());
        d2.Feed(Ev(GuardKind.Logon, T0, "9.9.9.9"));
        List<Threat> t = d2.Tick(T0 + 31000);
        Eq(1, t.Count, "вход без планшета — тревога");
        Eq(Threat.UnknownLogon, t[0].Rule, "правило");
        Check(!t[0].AfterFail, "без предшествующих неудач");

        // Планшет открывал канал давно, это не оправдывает новый вход.
        var d3 = Detector(new Whitelist(), new List<string>());
        d3.TabletOpened(T0 - 10 * MIN);
        d3.Feed(Ev(GuardKind.Logon, T0, "9.9.9.9"));
        Eq(1, d3.Tick(T0 + 31000).Count, "старый канал планшета не оправдывает вход");

        // История: канал планшета открывался дважды; вход, рядом с которым канала не было, — чужой.
        var learnedHistory = new List<string>();
        var d6 = Detector(new Whitelist(), learnedHistory);
        d6.TabletOpened(T0 + 60 * MIN + 5000);
        d6.TabletOpened(T0 + 5000);
        d6.Feed(Ev(GuardKind.Logon, T0, "9.9.9.9"));
        d6.Feed(Ev(GuardKind.Logon, T0 + 30 * MIN, "6.6.6.6"));
        d6.Feed(Ev(GuardKind.Logon, T0 + 60 * MIN, "7.7.7.7"));
        t = d6.Tick(T0 + 70 * MIN);
        Eq(1, t.Count, "из трёх входов чужой один");
        Eq("6.6.6.6", t[0].Ip, "чужой — тот, у которого не было канала");
        Eq(2, learnedHistory.Count, "два других адреса запомнены");

        // Вход после подбора — отмечается.
        var d4 = Detector(new Whitelist(), new List<string>());
        for (int i = 0; i < 3; i++) d4.Feed(Ev(GuardKind.Fail, T0 - 60000 + i * 1000, "198.51.100.71"));
        d4.Feed(Ev(GuardKind.Logon, T0, "198.51.100.71"));
        t = d4.Tick(T0 + 31000);
        Eq(1, t.Count, "вход после неудач — тревога");
        Check(t[0].AfterFail, "отмечено, что вход после подбора");

        // Свой адрес и локальная сеть входят без вопросов.
        var d5 = Detector(Listed("9.9.9.9"), new List<string>());
        d5.Feed(Ev(GuardKind.Logon, T0, "9.9.9.9"));
        d5.Feed(Ev(GuardKind.Logon, T0, "192.168.1.1"));
        Eq(0, d5.Tick(T0 + 31000).Count, "свои адреса не тревожат");
    }

    static void WhitelistStorage()
    {
        var w = new Whitelist();
        Eq("198.51.100.71/32", w.Add("198.51.100.71", WhitelistEntry.Auto, T0), "адрес — /32");
        Eq("198.51.100.0/24", w.Add("198.51.100.5/24", WhitelistEntry.Manual, T0), "подсеть приводится к границе");
        Eq("203.0.0.0/16", w.Add("203.0.113.9/16", WhitelistEntry.Manual, T0), "/16 — предел");
        Eq(null, w.Add("203.0.0.0/8", WhitelistEntry.Manual, T0), "шире /16 нельзя");
        Eq(null, w.Add("0.0.0.0/0", WhitelistEntry.Manual, T0), "весь интернет нельзя");
        Eq(null, w.Add("192.168.1.0/24", WhitelistEntry.Manual, T0), "своя сеть и так своя");
        Eq(null, w.Add("мусор", WhitelistEntry.Manual, T0), "не адрес");
        Eq("2001:db8:1:2::/64", w.Add("2001:db8:1:2:3:4:5:6/64", WhitelistEntry.Manual, T0), "IPv6 /64");
        Eq(null, w.Add("2001:db8::/32", WhitelistEntry.Manual, T0), "IPv6 шире /48 нельзя");
        Eq(4, w.Count, "в списке четыре записи");
        Check(w.Contains("198.51.100.200"), "адрес из подсети знаком");
        Check(w.Contains("203.0.55.1"), "адрес из /16 знаком");
        Check(w.Contains("2001:db8:1:2:ffff::1"), "адрес из IPv6-подсети знаком");
        Check(!w.Contains("198.51.101.1"), "соседняя подсеть не знакома");
        Check(!w.Contains("[не адрес]"), "не адрес не знаком");
        Check(w.Contains("::ffff:198.51.100.7"), "IPv4 в записи IPv6 тоже узнаётся");

        // Повторное добавление не плодит записей; ручное закрепляет автоматическую.
        w.Add("198.51.100.71", WhitelistEntry.Manual, T0 + 1);
        Eq(4, w.Count, "повтор не добавляет запись");
        Eq(WhitelistEntry.Manual, w.Find("198.51.100.71/32").Source, "ручное добавление закрепляет автоматическую");
        w.Add("198.51.100.71", WhitelistEntry.Auto, T0 + 2);
        Eq(WhitelistEntry.Manual, w.Find("198.51.100.71/32").Source, "автоматическое ручную не понижает");

        // Удаление.
        Check(w.Remove("198.51.100.71"), "адрес удаляется по записи без длины");
        Check(!w.Remove("198.51.100.71"), "повторно — нечего удалять");
        Check(!w.Contains("198.51.100.71") || w.Contains("198.51.100.200"), "после удаления адрес всё ещё покрыт подсетью");
        Check(w.Remove("198.51.100.0/24"), "подсеть удаляется");
        Check(!w.Contains("198.51.100.200"), "адрес больше не знаком");

        // Пересечение с блокировкой.
        var b = Listed("198.51.100.10", "203.0.0.0/16");
        Check(b.Overlaps("198.51.100.0/24"), "в подсети есть знакомый адрес");
        Check(b.Overlaps("198.51.100.10/32"), "блокировка самого знакомого адреса");
        Check(b.Overlaps("203.0.113.0/24"), "подсеть внутри знакомой подсети");
        Check(b.Overlaps("203.0.0.0/16"), "та же подсеть");
        Check(!b.Overlaps("198.51.101.0/24"), "чужая подсеть не пересекается");
        Check(!b.Overlaps("2001:db8:1:2::/64"), "другое семейство адресов");
        Check(IpTools.Overlaps("10.0.0.0/8", "10.1.2.0/24"), "вложенность в обе стороны");
        Check(!IpTools.Overlaps("10.0.0.0/24", "10.0.1.0/24"), "соседние подсети");

        // Переполнение: первой уходит самая старая автоматическая запись, ручные берегутся.
        var full = new Whitelist();
        full.Add("1.1.1.1", WhitelistEntry.Manual, 1);
        for (int i = 0; i < Whitelist.MaxEntries; i++) full.Add("20.0." + (i / 250) + "." + (i % 250 + 1), WhitelistEntry.Auto, 100 + i);
        Eq(Whitelist.MaxEntries, full.Count, "размер списка ограничен");
        Check(full.Contains("1.1.1.1"), "старая ручная запись пережила переполнение");
        Check(!full.Contains("20.0.0.1"), "ушла самая старая автоматическая");

        // Подсеть запоминается целиком, если так настроено.
        IPAddress ip;
        IpTools.TryParse("198.51.100.71", out ip);
        var learn = new Whitelist();
        Eq("198.51.100.0/24", learn.Learn(ip, true, T0), "запомнена подсеть");
        Eq(WhitelistEntry.Auto, learn.Find("198.51.100.0/24").Source, "запомнена автоматически");
    }

    static void Learning()
    {
        // Запоминать подсеть: вход из одной подсети знакомит всю подсеть.
        var list = new Whitelist();
        var learned = new List<string>();
        var settings = new GuardSettings();
        settings.LearnSubnet = true;
        var d = Detector(list, settings, learned);
        d.Feed(Ev(GuardKind.Logon, T0, "198.51.100.71"));
        d.TabletOpened(T0 + 5000);
        d.Tick(T0 + 31000);
        Eq("198.51.100.0/24", learned[0], "запомнена подсеть, а не адрес");
        Check(list.Contains("198.51.100.5"), "соседний адрес подсети знаком");
        var got = new List<Threat>();
        for (int i = 0; i < 10; i++) got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + 40 * MIN + i * 1000, "198.51.100.200")));
        Eq(0, got.Count, "попытки с запомненной подсети не тревожат");

        // Автоматическое занесение выключено: адрес не запоминается; в обычном режиме тревоги нет, но и списка нет.
        var off = new GuardSettings();
        off.AutoLearn = false;
        var list2 = new Whitelist();
        var learned2 = new List<string>();
        var d2 = Detector(list2, off, learned2);
        d2.Feed(Ev(GuardKind.Logon, T0, "9.9.9.9"));
        d2.TabletOpened(T0 + 5000);
        Eq(0, d2.Tick(T0 + 31000).Count, "обычный режим: планшет подключился — не тревога");
        Eq(0, learned2.Count, "ничего не запоминается");
        Check(!list2.Contains("9.9.9.9"), "адреса в списке нет");
    }

    static void WhitelistMode()
    {
        var got = new List<Threat>();

        // Первая же попытка с адреса вне списка — тревога; дойдя до порога, она становится подбором пароля.
        var d = Detector(Listed("7.7.7.7"), Strict(true, false), new List<string>());
        got.AddRange(d.Feed(Ev(GuardKind.Fail, T0, "198.51.100.71")));
        Eq(1, got.Count, "режим белого списка: первая попытка тревожит");
        Eq(Threat.Unlisted, got[0].Rule, "правило «вне белого списка»");
        Eq(1, got[0].Count, "одна попытка");
        got.Clear();
        got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + 1000, "198.51.100.72")));
        Eq(0, got.Count, "вторая попытка из той же подсети не повторяет тревогу");
        got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + 2000, "198.51.100.73")));
        Eq(1, got.Count, "на пороге — подбор пароля");
        Eq(Threat.BruteForce, got[0].Rule, "правило «подбор»");
        Eq(3, got[0].Count, "три попытки");

        // Знакомые адреса и подсети, а также своя сеть не тревожат.
        var known = Detector(Listed("7.7.7.7", "198.51.100.0/24"), Strict(true, false), new List<string>());
        got.Clear();
        got.AddRange(known.Feed(Ev(GuardKind.Fail, T0, "7.7.7.7")));
        got.AddRange(known.Feed(Ev(GuardKind.Fail, T0, "198.51.100.99")));
        got.AddRange(known.Feed(Ev(GuardKind.Fail, T0, "192.168.1.9")));
        Eq(0, got.Count, "знакомое и локальное не тревожит и в строгом режиме");
        known.Feed(Ev(GuardKind.Logon, T0, "198.51.100.99"));
        Eq(0, known.Tick(T0 + 31000).Count, "вход со знакомой подсети — без вопросов");

        // Тот же адрес в обычном режиме одной попыткой не тревожит.
        var normal = Detector(new Whitelist(), new List<string>());
        Eq(0, normal.Feed(Ev(GuardKind.Fail, T0, "198.51.100.71")).Count, "обычный режим: одна попытка не тревожит");

        // Вход с планшетом, автоматическое занесение включено: адрес запоминается, тревоги нет.
        var learnedList = new List<string>();
        var auto = Detector(new Whitelist(), Strict(true, false), learnedList);
        auto.Feed(Ev(GuardKind.Logon, T0, "9.9.9.9"));
        auto.TabletOpened(T0 + 3000);
        Eq(0, auto.Tick(T0 + 31000).Count, "строгий режим, автозанесение: вход планшета не тревожит");
        Eq(1, learnedList.Count, "адрес занесён в список");

        // Вход с планшетом, автоматическое занесение выключено: вопрос «занести?».
        var manual = Detector(new Whitelist(), Strict(false, false), new List<string>());
        manual.Feed(Ev(GuardKind.Logon, T0, "9.9.9.9"));
        manual.TabletOpened(T0 + 3000);
        List<Threat> t = manual.Tick(T0 + 31000);
        Eq(1, t.Count, "строгий режим без автозанесения: нужен ответ пользователя");
        Eq(Threat.NewAddress, t[0].Rule, "правило «новый адрес»");

        // Вход без планшета — по-прежнему «неизвестный вход».
        var stranger = Detector(new Whitelist(), Strict(true, false), new List<string>());
        stranger.Feed(Ev(GuardKind.Logon, T0, "9.9.9.9"));
        t = stranger.Tick(T0 + 31000);
        Eq(1, t.Count, "вход без планшета в строгом режиме — тревога");
        Eq(Threat.UnknownLogon, t[0].Rule, "правило");

        // Предлагаемая блокировка: подсеть, а если в ней знакомый адрес или подсеть поменьше — один адрес.
        var offer = Detector(Listed("198.51.100.128/25"), Strict(true, false), new List<string>());
        IPAddress ip;
        IpTools.TryParse("198.51.100.5", out ip);
        Eq("198.51.100.5/32", offer.OfferedBlock(ip), "в подсети есть знакомая подсеть поменьше — блокируем один адрес");
        IpTools.TryParse("203.0.113.5", out ip);
        Eq("203.0.113.0/24", offer.OfferedBlock(ip), "в чужой подсети блокируем подсеть");
    }

    static void Settings()
    {
        Check(GuardMode.IsValid("normal") && GuardMode.IsValid("whitelist"), "режимы normal и whitelist допустимы");
        Check(!GuardMode.IsValid("") && !GuardMode.IsValid("strict") && !GuardMode.IsValid(null), "остальное недопустимо");
        var s = new GuardSettings();
        Check(!s.WhitelistMode && s.AutoLearn && !s.LearnSubnet, "по умолчанию: обычный режим, запоминать адреса планшета, подсети не запоминать");
        s.Mode = GuardMode.Whitelist;
        Check(s.WhitelistMode, "режим белого списка включён");
    }
}
