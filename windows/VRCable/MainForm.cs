namespace VRCable;

class MainForm : Form
{
    readonly StreamServer server = new();
    readonly Label status = new();
    readonly Button btnInstall = new();
    readonly Button btnStart = new();
    readonly ComboBox cbFps = new();
    readonly ComboBox cbRate = new();
    readonly CheckBox chkSwap = new();
    readonly CheckBox chkMute = new();
    readonly TextBox log = new();
    readonly System.Windows.Forms.Timer timer = new();
    bool clientOn;
    bool busy;

    public MainForm()
    {
        Text = "VR Cable — ноутбук → телефон";
        ClientSize = new Size(560, 460);
        StartPosition = FormStartPosition.CenterScreen;
        Font = new Font("Segoe UI", 10f);

        status.SetBounds(12, 10, 536, 40);
        status.Text = "Перевіряю телефон...";
        Controls.Add(status);

        btnInstall.SetBounds(12, 56, 262, 36);
        btnInstall.Text = "1. Встановити додаток на телефон";
        btnInstall.Click += (_, _) => RunBg(InstallApp);
        Controls.Add(btnInstall);

        btnStart.SetBounds(286, 56, 262, 36);
        btnStart.Text = "2. Запустити VR на телефоні";
        btnStart.Click += (_, _) => RunBg(StartVr);
        Controls.Add(btnStart);

        var l1 = new Label { Text = "Кадрів/с:", Left = 12, Top = 108, AutoSize = true };
        cbFps.SetBounds(90, 104, 70, 28);
        cbFps.DropDownStyle = ComboBoxStyle.DropDownList;
        cbFps.Items.AddRange(new object[] { "30", "60" });
        cbFps.SelectedItem = "60";
        cbFps.SelectedIndexChanged += (_, _) => server.Fps = int.Parse((string)cbFps.SelectedItem);

        var l2 = new Label { Text = "Бітрейт, Мбіт/с:", Left = 180, Top = 108, AutoSize = true };
        cbRate.SetBounds(310, 104, 70, 28);
        cbRate.DropDownStyle = ComboBoxStyle.DropDownList;
        cbRate.Items.AddRange(new object[] { "8", "12", "20", "30" });
        cbRate.SelectedItem = "12";
        cbRate.SelectedIndexChanged += (_, _) => server.BitrateMbps = int.Parse((string)cbRate.SelectedItem);

        chkMute.Text = "Вимикати звук на ноутбуці під час VR";
        chkMute.Checked = true;
        chkMute.AutoSize = true;
        chkMute.Location = new Point(12, 138);
        chkMute.CheckedChanged += (_, _) => server.MuteLaptop = chkMute.Checked;

        chkSwap.Text = "Поміняти монітори місцями";
        chkSwap.AutoSize = true;
        chkSwap.Location = new Point(330, 138);
        chkSwap.CheckedChanged += (_, _) => server.SwapMonitors = chkSwap.Checked;

        Controls.AddRange(new Control[] { l1, cbFps, l2, cbRate, chkMute, chkSwap });

        log.SetBounds(12, 172, 536, 276);
        log.Multiline = true;
        log.ReadOnly = true;
        log.ScrollBars = ScrollBars.Vertical;
        log.Font = new Font("Consolas", 9f);
        Controls.Add(log);

        server.Log += Say;
        server.ClientChanged += on => BeginInvoke(() => { clientOn = on; Refresh(); });

        Shown += (_, _) =>
        {
            AudioMute.RestoreLeftover();      // un-mute if the last run ended abnormally
            try { server.Start(); Say("Сервер працює на 127.0.0.1:" + Adb.Port); }
            catch (Exception e) { Say("Не вдалося запустити сервер (чи не відкрита друга копія програми?): " + e.Message); }
            Say("Увімкни на телефоні «Налагодження по USB» і підключи кабель.");
            timer.Interval = 3000;
            timer.Tick += (_, _) => RunBg(Poll, quiet: true);
            timer.Start();
            RunBg(Poll, quiet: true);
        };
        FormClosing += (_, _) => { timer.Stop(); server.Stop(); AudioMute.Restore(); };
    }

    // ---- actions (run on a worker thread so the window never freezes) -------------------------

    void RunBg(Action a, bool quiet = false)
    {
        if (busy) { if (!quiet) Say("Зачекай, попередня дія ще виконується..."); return; }
        busy = true;
        Task.Run(() =>
        {
            try { a(); }
            catch (Exception e) { Say("Помилка: " + e.Message); }
            finally { busy = false; }
        });
    }

    void Poll()
    {
        string st = Adb.DeviceState();
        string text = st switch
        {
            "ok" => "Телефон підключено кабелем.",
            "none" => "Телефон не знайдено. Підключи кабель і увімкни «Налагодження по USB».",
            "unauthorized" => "Підтверди на телефоні запит «Дозволити налагодження по USB».",
            _ => st,
        };
        if (st == "ok")
        {
            if (!Adb.EnsureReverse()) text += " (не вдалося налаштувати канал adb reverse)";
            else if (clientOn) text += " Трансляція йде.";
        }
        BeginInvoke(() => status.Text = text);
    }

    void InstallApp()
    {
        if (!File.Exists(Adb.ApkPath)) { Say("Файл VRCable.apk не знайдено поруч із програмою."); return; }
        Say("Встановлюю додаток на телефон (може знадобитися підтвердження на екрані телефона)...");
        var (code, output) = Adb.Install();
        Say(code == 0 ? "Додаток встановлено." : "Помилка встановлення: " + output);
    }

    void StartVr()
    {
        if (Adb.DeviceState() != "ok") { Say("Спочатку підключи телефон кабелем."); return; }
        Adb.EnsureReverse();
        var (code, output) = Adb.LaunchApp();
        Say(code == 0 ? "Додаток запущено. Вдягай шолом." : "Не вдалося запустити (чи встановлено додаток?): " + output);
    }

    void Say(string m)
    {
        if (IsDisposed) return;
        if (InvokeRequired) { try { BeginInvoke(() => Say(m)); } catch { } return; }
        log.AppendText(DateTime.Now.ToString("HH:mm:ss  ") + m + Environment.NewLine);
    }
}
