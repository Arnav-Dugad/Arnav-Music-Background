/**
 * CI emulator-only workaround for Android 17 goldfish mapper DMA readback assertions.
 * Uses the WindowManager service's snapshot toggle; this is never packaged with the app.
 * System UI, notifications and normal application platform restrictions remain enabled.
 */
public final class DisableTaskSnapshots {
    public static void main(String[] args) throws Exception {
        Object service = Class.forName("android.view.WindowManagerGlobal")
                .getMethod("getWindowManagerService").invoke(null);
        Class.forName("android.view.IWindowManager")
                .getMethod("setTaskSnapshotEnabled", boolean.class).invoke(service, false);
        System.out.println("Disabled emulator recent-task snapshots; System UI remains enabled.");
    }
}
