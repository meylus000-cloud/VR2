using System.Diagnostics;
using System.Net;
using System.Net.Sockets;
using NAudio.CoreAudioApi;
using NAudio.Wave;

namespace VRCable;

/// <summary>
/// Listens on 127.0.0.1:5599 (the phone reaches it through "adb reverse").
/// While a phone is connected it captures the screens and the system audio and sends them.
/// When nobody is connected nothing is captured, so the laptop is not loaded at all.
/// </summary>
class StreamServer
{
    public event Action<string> Log;
    public event Action<bool> ClientChanged;

    public int Fps = 60;
    public int BitrateMbps = 12;
    public bool SwapMonitors = false;
    public bool MuteLaptop = true;

    TcpListener listener;
    volatile bool running;
    Session current;

    public void Start()
    {
        listener = new TcpListener(IPAddress.Loopback, Adb.Port);
        listener.Start();
        running = true;
        new Thread(AcceptLoop) { IsBackground = true, Name = "accept" }.Start();
    }

    public void Stop()
    {
        running = false;
        try { listener.Stop(); } catch { }
        current?.Close();
    }

    void AcceptLoop()
    {
        while (running)
        {
            try
            {
                var c = listener.AcceptTcpClient();
                c.NoDelay = true;
                current?.Close();
                var s = new Session(this, c);
                current = s;
                new Thread(s.Run) { IsBackground = true, Name = "session" }.Start();
            }
            catch
            {
                if (!running) return;
            }
        }
    }

    internal void Say(string m) => Log?.Invoke(m);
    internal void Connected(bool on) => ClientChanged?.Invoke(on);

    // =====================================================================================
    class Session
    {
        readonly StreamServer owner;
        readonly TcpClient client;
        readonly NetworkStream net;
        readonly object sendLock = new();
        readonly List<Process> procs = new();
        WasapiLoopbackCapture audio;
        int closed;

        public Session(StreamServer o, TcpClient c)
        {
            owner = o;
            client = c;
            net = c.GetStream();
        }

        bool Dead => closed != 0;

        public void Run()
        {
            owner.Connected(true);
            owner.Say("Телефон підключився, запускаю трансляцію.");
            try
            {
                if (!File.Exists(Adb.FfmpegExe)) { owner.Say("Немає tools\\ffmpeg.exe"); Close(); return; }
                var enc = Encoder.Get(owner.Say);
                if (enc == null) { Close(); return; }

                // ---- which screens (max 2), left one first ----
                var all = Screen.AllScreens;
                int count = Math.Min(2, all.Length);
                var list = new List<(int Out, Screen Scr)>();
                for (int j = 0; j < count; j++)
                    list.Add((j, owner.SwapMonitors && count == 2 ? all[count - 1 - j] : all[j]));
                list.Sort((a, b) => a.Scr.Bounds.X.CompareTo(b.Scr.Bounds.X));

                var sizes = new List<(int W, int H, bool Scale)>();
                foreach (var (_, scr) in list)
                {
                    int w = scr.Bounds.Width, h = scr.Bounds.Height;
                    if (w > 1920)
                    {
                        h = (int)Math.Round(1920.0 * h / w / 2.0) * 2;
                        w = 1920;
                        sizes.Add((w, h, true));
                    }
                    else sizes.Add((w & ~1, h & ~1, false));
                }

                // ---- config packets first, so the phone builds its decoders ----
                var cfg = new byte[1 + count * 4];
                cfg[0] = (byte)count;
                for (int i = 0; i < count; i++)
                {
                    cfg[1 + i * 4] = (byte)(sizes[i].W >> 8);
                    cfg[2 + i * 4] = (byte)sizes[i].W;
                    cfg[3 + i * 4] = (byte)(sizes[i].H >> 8);
                    cfg[4 + i * 4] = (byte)sizes[i].H;
                }
                Send(0x01, cfg, cfg.Length);
                owner.Say($"Екранів: {count}. Розмір: " + string.Join(", ", sizes.Select(s => $"{s.W}x{s.H}")) +
                          $", {owner.Fps} fps, {owner.BitrateMbps} Мбіт/с");

                StartAudio();

                for (int i = 0; i < count; i++)
                    StartVideo(enc, i, list[i].Out, sizes[i]);
            }
            catch (Exception e)
            {
                owner.Say("Помилка: " + e.Message);
                Close();
            }
        }

        // ---- video ------------------------------------------------------------------------
        void StartVideo(Encoder enc, int vrIndex, int outputIdx, (int W, int H, bool Scale) size)
        {
            var psi = new ProcessStartInfo(Adb.FfmpegExe,
                enc.BuildArgs(outputIdx, owner.Fps, owner.BitrateMbps, size.W, size.H, size.Scale, false))
            {
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true,
            };
            var p = Process.Start(psi);
            lock (procs) procs.Add(p);

            int errLines = 0;
            p.ErrorDataReceived += (_, e) =>
            {
                if (e.Data != null && errLines++ < 5) owner.Say("ffmpeg: " + e.Data);
            };
            p.BeginErrorReadLine();

            new Thread(() => ReadVideo(p, (byte)(0x10 + vrIndex))) { IsBackground = true, Name = "video" + vrIndex }.Start();
        }

        /// <summary>Cuts ffmpeg's H.264 byte stream into frames at the access-unit delimiters (00 00 01 09).</summary>
        void ReadVideo(Process p, byte type)
        {
            try
            {
                var s = p.StandardOutput.BaseStream;
                var rd = new byte[1 << 18];
                var buf = new byte[1 << 20];
                int len = 0, scan = 1;
                while (!Dead)
                {
                    int n = s.Read(rd, 0, rd.Length);
                    if (n <= 0) break;
                    if (len + n > buf.Length) Array.Resize(ref buf, Math.Max(buf.Length * 2, len + n));
                    Buffer.BlockCopy(rd, 0, buf, len, n);
                    len += n;

                    int i = Math.Max(scan, 1);
                    while (i + 3 < len)
                    {
                        if (buf[i] == 0 && buf[i + 1] == 0 && buf[i + 2] == 1 && buf[i + 3] == 9)
                        {
                            int start = buf[i - 1] == 0 ? i - 1 : i;
                            if (start > 0)
                            {
                                Send(type, buf, start);
                                int rest = len - start;
                                Buffer.BlockCopy(buf, start, buf, 0, rest);
                                len = rest;
                                i = (i - start) + 4;
                                continue;
                            }
                            i += 4;
                            continue;
                        }
                        i++;
                    }
                    scan = Math.Max(1, len - 3);
                }
            }
            catch { }
            if (!Dead) { owner.Say("Захоплення екрана зупинилося."); Close(); }
        }

        // ---- audio ------------------------------------------------------------------------
        void StartAudio()
        {
            try
            {
                var enumerator = new MMDeviceEnumerator();
                var dev = enumerator.GetDefaultAudioEndpoint(DataFlow.Render, Role.Multimedia);
                audio = new WasapiLoopbackCapture(dev);
                int rate = audio.WaveFormat.SampleRate;
                var ac = new byte[4];
                ac[0] = (byte)(rate >> 24); ac[1] = (byte)(rate >> 16); ac[2] = (byte)(rate >> 8); ac[3] = (byte)rate;
                Send(0x02, ac, 4);
                audio.DataAvailable += OnAudio;
                audio.StartRecording();
                if (owner.MuteLaptop) AudioMute.Apply(dev);   // sound only in the phone
            }
            catch (Exception e)
            {
                owner.Say("Звук недоступний: " + e.Message);
            }
        }

        void OnAudio(object sender, WaveInEventArgs e)
        {
            if (Dead || e.BytesRecorded == 0) return;
            var wf = audio.WaveFormat;
            int ch = wf.Channels, bytes = wf.BitsPerSample / 8, align = wf.BlockAlign;
            int frames = e.BytesRecorded / align;
            var outb = new byte[frames * 8];     // stereo float32
            for (int f = 0; f < frames; f++)
            {
                for (int c = 0; c < 2; c++)
                {
                    int off = f * align + Math.Min(c, ch - 1) * bytes;
                    float v = bytes == 4 ? BitConverter.ToSingle(e.Buffer, off)
                            : bytes == 2 ? BitConverter.ToInt16(e.Buffer, off) / 32768f
                            : 0f;
                    BitConverter.TryWriteBytes(new Span<byte>(outb, (f * 2 + c) * 4, 4), v);
                }
            }
            Send(0x20, outb, outb.Length);
        }

        // ---- sending ------------------------------------------------------------------------
        void Send(byte type, byte[] data, int len)
        {
            if (Dead) return;
            var pkt = new byte[5 + len];
            pkt[0] = type;
            pkt[1] = (byte)(len >> 24); pkt[2] = (byte)(len >> 16); pkt[3] = (byte)(len >> 8); pkt[4] = (byte)len;
            Buffer.BlockCopy(data, 0, pkt, 5, len);
            bool failed = false;
            lock (sendLock)
            {
                if (Dead) return;
                try { net.Write(pkt, 0, pkt.Length); }
                catch { failed = true; }
            }
            if (failed) Close();
        }

        public void Close()
        {
            if (Interlocked.Exchange(ref closed, 1) != 0) return;
            try { audio?.StopRecording(); audio?.Dispose(); } catch { }
            AudioMute.Restore();
            lock (procs)
                foreach (var p in procs)
                    try { p.Kill(true); } catch { }
            try { client.Close(); } catch { }
            owner.Say("Телефон відключився, захоплення зупинено.");
            owner.Connected(false);
        }
    }
}
