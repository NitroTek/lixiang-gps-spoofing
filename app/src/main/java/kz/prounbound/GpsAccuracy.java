package kz.prounbound;

/** Slider values are stored as integers; speed and bearing use tenths. */
public enum GpsAccuracy {
    ALTITUDE("vertical_accuracy_meters", 5, 5, 1),
    SPEED("speed_accuracy_tenths_mps", 50, 1, 10),
    BEARING("bearing_accuracy_tenths_degrees", 50, 10, 10);

    public final String key;
    public final int maxProgress;
    public final int defaultProgress;
    private final int scale;

    GpsAccuracy(String key, int maxProgress, int defaultProgress, int scale) {
        this.key = key;
        this.maxProgress = maxProgress;
        this.defaultProgress = defaultProgress;
        this.scale = scale;
    }

    public int clamp(int progress) { return Math.max(0, Math.min(maxProgress, progress)); }
    public float value(int progress) { return progress / (float) scale; }
}
