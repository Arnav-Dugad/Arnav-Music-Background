/**
 * CI emulator-only workaround for Android 17 goldfish mapper DMA readback assertions.
 * Uses the WindowManager Binder interface directly, avoiding UI/Looper initialization.
 * This class is never packaged with the app.
 */
public final class DisableTaskSnapshots {
    public static void main(String[] args) {
        try {
            Class<?> binderType = Class.forName("android.os.IBinder");
            Object binder = Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "window");
            if (binder == null) throw new IllegalStateException("WindowManager service unavailable");
            Object service = Class.forName("android.view.IWindowManager$Stub")
                    .getMethod("asInterface", binderType).invoke(null, binder);
            Class.forName("android.view.IWindowManager")
                    .getMethod("setTaskSnapshotEnabled", boolean.class).invoke(service, false);
            System.out.println("Disabled emulator recent-task snapshots; System UI remains enabled.");
        } catch (Throwable failure) {
            // app_process otherwise reports only 'Killed' for an uncaught Java exception.
            failure.printStackTrace(System.err);
            System.exit(1);
        }
    }
}
