package kz.prounbound.route;

import java.util.Random;

/** Stable IDs for the original loop and ten offline road sprints. */
public final class RouteCatalog {
    private static final Entry[] ENTRIES = {
            new Entry("g30-loop", "G30 · исходный круг", "G30", true),
            new Entry("sprint-01", "01 · G0321 · на север", "G0321", false),
            new Entry("sprint-02", "02 · G42 · на восток", "G42", false),
            new Entry("sprint-03", "03 · G50 · на север", "G50", false),
            new Entry("sprint-04", "04 · S09 · на север", "S09", false),
            new Entry("sprint-05", "05 · S25 · на север", "S25", false),
            new Entry("sprint-06", "06 · S13 · на восток", "S13", false),
            new Entry("sprint-07", "07 · S22 · на юг", "S22", false),
            new Entry("sprint-08", "08 · S08 · на запад", "S08", false),
            new Entry("sprint-09", "09 · S12 · на восток", "S12", false),
            new Entry("sprint-10", "10 · S01 · на восток", "S01", false)
    };
    private RouteCatalog() {}
    public static int size() { return ENTRIES.length; }
    public static Entry at(int index) { return ENTRIES[index]; }
    public static int indexOf(String id) {
        for (int i = 0; i < ENTRIES.length; i++) if (ENTRIES[i].id.equals(id)) return i;
        return 0;
    }
    public static Entry find(String id) { return at(indexOf(id)); }
    public static Entry randomSprint(Random random, String currentId) {
        int current = indexOf(currentId);
        int next = 1 + random.nextInt(current == 0 ? ENTRIES.length - 1 : ENTRIES.length - 2);
        return at(current != 0 && next >= current ? next + 1 : next);
    }
    public static String[] titles() {
        String[] titles = new String[ENTRIES.length];
        for (int i = 0; i < titles.length; i++) titles[i] = ENTRIES[i].title;
        return titles;
    }
    public static final class Entry {
        public final String id, title, road;
        public final boolean closed;
        private Entry(String id, String title, String road, boolean closed) {
            this.id = id; this.title = title; this.road = road; this.closed = closed;
        }
        public String assetName() { return id + ".gpx"; }
    }
}
