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

    static ThreatDetector Detector(HashSet<string> trusted, List<string> learned)
    {
        return new ThreatDetector(trusted.Contains, delegate { return trusted; }, delegate(string ip) { trusted.Add(ip); learned.Add(ip); });
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
        var trusted = new HashSet<string>();
        var d = Detector(trusted, new List<string>());
        var got = new List<Threat>();
        // Четыре попытки — ещё не повод; пятая из той же подсети (другой адрес) — подбор.
        for (int i = 0; i < 4; i++) got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + i * 30000, "198.51.100." + (70 + i))));
        Eq(0, got.Count, "четыре попытки не тревожат");
        got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + 4 * 30000, "198.51.100.99")));
        Eq(1, got.Count, "пятая попытка из подсети — тревога");
        Eq(Threat.BruteForce, got[0].Rule, "правило");
        Eq("198.51.100.0/24", got[0].Cidr, "предлагается подсеть");
        Eq(5, got[0].Count, "число попыток");
        // Продолжение той же атаки в течение часа не повторяет тревогу.
        got.Clear();
        for (int i = 0; i < 50; i++) got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + 3 * MIN + i * 30000, "198.51.100.71")));
        Eq(0, got.Count, "та же атака не тревожит повторно");
        // Через час, если атака идёт, — новая тревога (блокировка могла не подействовать).
        got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + 70 * MIN, "198.51.100.71")));
        Eq(0, got.Count, "одна попытка через час — окно пусто");
        for (int i = 1; i < 6; i++) got.AddRange(d.Feed(Ev(GuardKind.Fail, T0 + 70 * MIN + i * 1000, "198.51.100.71")));
        Eq(1, got.Count, "через час атака снова заметна");

        // Попытки реже порога окна не складываются.
        var slow = Detector(new HashSet<string>(), new List<string>());
        got.Clear();
        for (int i = 0; i < 20; i++) got.AddRange(slow.Feed(Ev(GuardKind.Fail, T0 + i * 3 * MIN, "8.8.8.8")));
        Eq(0, got.Count, "одна попытка в 3 минуты не подбор");

        // Свои адреса и локальная сеть не тревожат.
        var own = Detector(new HashSet<string> { "7.7.7.7" }, new List<string>());
        got.Clear();
        for (int i = 0; i < 10; i++)
        {
            got.AddRange(own.Feed(Ev(GuardKind.Fail, T0 + i * 1000, "7.7.7.7")));
            got.AddRange(own.Feed(Ev(GuardKind.Fail, T0 + i * 1000, "192.168.1.50")));
        }
        Eq(0, got.Count, "свой адрес и LAN не тревожат");

        // В подсети есть свой адрес: блокировать предлагается только нападающего.
        var mixed = Detector(new HashSet<string> { "198.51.100.10" }, new List<string>());
        got.Clear();
        for (int i = 0; i < 5; i++) got.AddRange(mixed.Feed(Ev(GuardKind.Fail, T0 + i * 1000, "198.51.100.71")));
        Eq(1, got.Count, "тревога при своём адресе в подсети");
        Eq("198.51.100.71/32", got[0].Cidr, "в подсети есть свой — блокируем один адрес");

        // Запомненная до перезапуска угроза не повторяется.
        var restarted = Detector(new HashSet<string>(), new List<string>());
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
        var trusted = new HashSet<string>();
        var learned = new List<string>();
        var d = Detector(trusted, learned);
        d.Feed(Ev(GuardKind.Logon, T0, "9.9.9.9"));
        d.TabletOpened(T0 + 8000);
        Eq(0, d.Tick(T0 + 10000).Count, "до конца ожидания решения нет");
        Eq(0, d.Tick(T0 + 31000).Count, "канал открылся — не тревога");
        Eq(1, learned.Count, "адрес запомнен");
        Check(trusted.Contains("9.9.9.9"), "адрес стал своим");

        // Вход без планшета — тревога.
        var d2 = Detector(new HashSet<string>(), new List<string>());
        d2.Feed(Ev(GuardKind.Logon, T0, "9.9.9.9"));
        List<Threat> t = d2.Tick(T0 + 31000);
        Eq(1, t.Count, "вход без планшета — тревога");
        Eq(Threat.UnknownLogon, t[0].Rule, "правило");
        Check(!t[0].AfterFail, "без предшествующих неудач");

        // Планшет открывал канал давно, это не оправдывает новый вход.
        var d3 = Detector(new HashSet<string>(), new List<string>());
        d3.TabletOpened(T0 - 10 * MIN);
        d3.Feed(Ev(GuardKind.Logon, T0, "9.9.9.9"));
        Eq(1, d3.Tick(T0 + 31000).Count, "старый канал планшета не оправдывает вход");

        // История: канал планшета открывался дважды; вход, рядом с которым канала не было, — чужой.
        var learnedHistory = new List<string>();
        var d6 = Detector(new HashSet<string>(), learnedHistory);
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
        var d4 = Detector(new HashSet<string>(), new List<string>());
        for (int i = 0; i < 3; i++) d4.Feed(Ev(GuardKind.Fail, T0 - 60000 + i * 1000, "198.51.100.71"));
        d4.Feed(Ev(GuardKind.Logon, T0, "198.51.100.71"));
        t = d4.Tick(T0 + 31000);
        Eq(1, t.Count, "вход после неудач — тревога");
        Check(t[0].AfterFail, "отмечено, что вход после подбора");

        // Свой адрес и локальная сеть входят без вопросов.
        var d5 = Detector(new HashSet<string> { "9.9.9.9" }, new List<string>());
        d5.Feed(Ev(GuardKind.Logon, T0, "9.9.9.9"));
        d5.Feed(Ev(GuardKind.Logon, T0, "192.168.1.1"));
        Eq(0, d5.Tick(T0 + 31000).Count, "свои адреса не тревожат");
    }
}
