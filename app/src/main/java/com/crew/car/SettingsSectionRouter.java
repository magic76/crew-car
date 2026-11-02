package com.crew.car;

/** Categorizes original settings form chunks without changing field/listener ownership. */
public final class SettingsSectionRouter {
    public static final int VEHICLE = 0;
    public static final int CONTROLS = 1;
    public static final int AI_CAMERA = 2;
    public static final int ADVANCED = 3;
    private SettingsSectionRouter() {}

    public static int sectionForChunk(int chunk) {
        switch (chunk) {
            case 0: case 2: return VEHICLE;
            case 3: case 5: return CONTROLS;
            case 1: case 6: return AI_CAMERA;
            case 4: case 7: return ADVANCED;
            default: throw new IllegalArgumentException("unsupported settings chunk");
        }
    }

    /** Validate contiguous nondecreasing boundaries against the original view count. */
    public static boolean validCuts(int[] cuts, int numberOfViews) {
        if (cuts == null || cuts.length != 8 || numberOfViews < 0 ||
                cuts[7] != numberOfViews) return false;
        int previous = 0;
        for (int cut : cuts) {
            if (cut < previous || cut > numberOfViews) return false;
            previous = cut;
        }
        return true;
    }
}
