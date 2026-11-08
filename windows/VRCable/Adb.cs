using System.Diagnostics;
using System.Text;

namespace VRCable;

/// <summary>Thin wrapper around adb.exe (bundled in the "tools" folder).</summary>
static class Adb
{
    public const int Port = 5599;

    public static string BaseDir => Path.GetDirectoryName(Environment.ProcessPath) ?? AppContext.BaseDirectory;
    public static string ToolsDir => Path.Combine(BaseDir, "tools");
    public static string AdbExe => Path.Combine(ToolsDir, "adb.exe");
    public static string FfmpegExe => Path.Combine(ToolsDir, "ffmpeg.exe");
    public static string ApkPath => Path.Combine(BaseDir, "VRCable.apk");

    public static (int Code, string Output) Run(string args, int timeoutMs = 15000)
    {
        if (!File.Exists(AdbExe)) return (-1, "adb.exe не знайдено у папці tools");
        var psi = new ProcessStartInfo(AdbExe, args)
        {
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true,
            StandardOutputEncoding = Encoding.UTF8,
            StandardErrorEncoding = Encoding.UTF8,
        };
        using var p = Process.Start(psi);
        var sb = new StringBuilder();
        p.OutputDataReceived += (_, e) => { if (e.Data != null) lock (sb) sb.AppendLine(e.Data); };
        p.ErrorDataReceived += (_, e) => { if (e.Data != null) lock (sb) sb.AppendLine(e.Data); };
        p.BeginOutputReadLine();
        p.BeginErrorReadLine();
        if (!p.WaitForExit(timeoutMs))
        {
            try { p.Kill(true); } catch { }
            return (-2, "adb не відповів (таймаут)");
        }
        p.WaitForExit();
        lock (sb) return (p.ExitCode, sb.ToString().Trim());
    }

    /// <summary>Returns "ok", "unauthorized", "none" or an error text.</summary>
    public static string DeviceState()
    {
        var (code, output) = Run("devices");
        if (code != 0) return output;
        int ok = 0;
        bool unauthorized = false;
        foreach (var line in output.Split('\n'))
        {
            var l = line.Trim();
            if (l.EndsWith("\tdevice")) ok++;
            else if (l.EndsWith("\tunauthorized")) unauthorized = true;
        }
        if (ok > 1) return "Підключено кілька пристроїв — залиш тільки телефон";
        if (ok == 1) return "ok";
        return unauthorized ? "unauthorized" : "none";
    }

    public static bool EnsureReverse()
    {
        var (_, list) = Run("reverse --list");
        if (list.Contains($"tcp:{Port}")) return true;
        return Run($"reverse tcp:{Port} tcp:{Port}").Code == 0;
    }

    public static (int Code, string Output) Install() => Run($"install -r \"{ApkPath}\"", 120000);

    public static (int Code, string Output) LaunchApp() =>
        Run("shell am start -n com.vrcable/.MainActivity");
}
