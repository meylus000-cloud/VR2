using NAudio.CoreAudioApi;

namespace VRCable;

/// <summary>
/// Mutes the laptop speakers while the phone is streaming, so sound comes only from the phone.
/// WASAPI loopback captures the sound before the endpoint mute, so the phone still gets it.
/// A small flag file makes sure the laptop is un-muted again even after a crash.
/// </summary>
static class AudioMute
{
    static MMDevice device;
    static bool changed;

    static string FlagPath => Path.Combine(Adb.BaseDir, "muted.flag");

    public static void Apply(MMDevice d)
    {
        try
        {
            if (d.AudioEndpointVolume.Mute) return;       // user had it muted already: leave it alone
            File.WriteAllText(FlagPath, d.ID);
            d.AudioEndpointVolume.Mute = true;
            device = d;
            changed = true;
        }
        catch { }
    }

    public static void Restore()
    {
        try { if (changed && device != null) device.AudioEndpointVolume.Mute = false; } catch { }
        changed = false;
        device = null;
        try { File.Delete(FlagPath); } catch { }
    }

    /// <summary>Called at start-up: if the last run ended without un-muting, un-mute now.</summary>
    public static void RestoreLeftover()
    {
        try
        {
            if (!File.Exists(FlagPath)) return;
            string id = File.ReadAllText(FlagPath).Trim();
            using var en = new MMDeviceEnumerator();
            en.GetDevice(id).AudioEndpointVolume.Mute = false;
        }
        catch { }
        try { File.Delete(FlagPath); } catch { }
    }
}
