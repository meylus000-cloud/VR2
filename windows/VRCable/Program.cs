namespace VRCable;

static class Program
{
    [STAThread]
    static void Main()
    {
        ApplicationConfiguration.Initialize();   // also turns on PerMonitorV2 DPI mode (see .csproj)
        Application.Run(new MainForm());
    }
}
