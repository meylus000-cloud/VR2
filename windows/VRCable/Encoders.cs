using System.Diagnostics;

namespace VRCable;

/// <summary>Chooses the fastest working H.264 encoder (NVIDIA / AMD / Intel / CPU) and builds ffmpeg arguments.</summary>
class Encoder
{
    public string Name;        // ffmpeg encoder name
    public string Tail;        // extra filter after "format=bgra" (null = none)
    public string Options;     // low-latency options of that encoder

    public string Label => Name + (Tail != null ? " (" + Tail + ")" : "");

    static readonly Encoder[] Candidates =
    {
        new Encoder { Name = "h264_nvenc", Tail = null,
            Options = "-preset p1 -tune ll -rc cbr -zerolatency 1 -bf 0 -forced-idr 1 -profile:v high" },
        new Encoder { Name = "h264_nvenc", Tail = "format=nv12",
            Options = "-preset p1 -tune ll -rc cbr -zerolatency 1 -bf 0 -forced-idr 1 -profile:v high" },
        new Encoder { Name = "h264_amf", Tail = null,
            Options = "-usage ultralowlatency -quality speed -rc cbr -bf 0" },
        new Encoder { Name = "h264_amf", Tail = "format=nv12",
            Options = "-usage ultralowlatency -quality speed -rc cbr -bf 0" },
        new Encoder { Name = "h264_qsv", Tail = "format=nv12",
            Options = "-preset veryfast -look_ahead 0 -bf 0" },
        new Encoder { Name = "libx264", Tail = "format=yuv420p",
            Options = "-preset ultrafast -tune zerolatency" },
    };

    static Encoder cached;
    static readonly object probeLock = new();

    static string CachePath => Path.Combine(Adb.BaseDir, "encoder.cfg");

    public static Encoder Get(Action<string> log)
    {
        lock (probeLock)
        {
            if (cached != null) return cached;

            // result of an earlier probe
            try
            {
                if (File.Exists(CachePath) && int.TryParse(File.ReadAllText(CachePath).Trim(), out int saved)
                    && saved >= 0 && saved < Candidates.Length)
                {
                    cached = Candidates[saved];
                    log("Кодек (збережений): " + cached.Label);
                    return cached;
                }
            }
            catch { }

            log("Перевіряю, який кодек працює на цьому ноутбуці (разово, до 20 с)...");
            for (int i = 0; i < Candidates.Length; i++)
            {
                if (Probe(Candidates[i]))
                {
                    cached = Candidates[i];
                    try { File.WriteAllText(CachePath, i.ToString()); } catch { }
                    log("Кодек: " + cached.Label + (cached.Name == "libx264" ? "  (процесорний, буде більше навантаження)" : "  (апаратний)"));
                    return cached;
                }
            }
            log("Жоден кодек не запрацював. Перевір, що ffmpeg.exe лежить у папці tools і що екран не в RDP-сесії.");
            return null;
        }
    }

    /// <summary>Delete the cached choice so the probe runs again.</summary>
    public static void ForgetCache()
    {
        lock (probeLock)
        {
            cached = null;
            try { File.Delete(CachePath); } catch { }
        }
    }

    public string BuildArgs(int outputIdx, int fps, int mbps, int outW, int outH, bool scale, bool forProbe)
    {
        string vf = $"ddagrab=output_idx={outputIdx}:framerate={fps},hwdownload,format=bgra";
        if (scale) vf += $",scale={outW}:{outH}:flags=fast_bilinear";
        if (Tail != null) vf += "," + Tail;
        int bufK = Math.Max(200, mbps * 1000 * 2 / Math.Max(1, fps));   // ~2 frames of buffer: lowest latency
        string rate = $"-b:v {mbps}M -maxrate {mbps}M -bufsize {bufK}k";
        if (forProbe)
            return $"-hide_banner -loglevel error -filter_complex \"{vf}[v]\" -map [v] -frames:v 5 -c:v {Name} {Options} {rate} -f null -";
        return $"-hide_banner -loglevel error -fflags nobuffer -flags low_delay -filter_complex \"{vf}[v]\" -map [v] " +
               $"-c:v {Name} {Options} {rate} -g {Math.Max(1, fps / 2)} " +
               "-bsf:v dump_extra=freq=keyframe,h264_metadata=aud=insert -flush_packets 1 -f h264 pipe:1";
    }

    static bool Probe(Encoder e)
    {
        try
        {
            var psi = new ProcessStartInfo(Adb.FfmpegExe, e.BuildArgs(0, 30, 8, 0, 0, false, true))
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardError = true,
                RedirectStandardOutput = true,
            };
            using var p = Process.Start(psi);
            p.ErrorDataReceived += (_, _) => { };
            p.BeginErrorReadLine();
            p.BeginOutputReadLine();
            if (!p.WaitForExit(10000))
            {
                try { p.Kill(true); } catch { }
                return false;
            }
            return p.ExitCode == 0;
        }
        catch
        {
            return false;
        }
    }
}
