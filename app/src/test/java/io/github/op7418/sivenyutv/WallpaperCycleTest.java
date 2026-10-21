package io.github.op7418.sivenyutv;

import org.junit.Test;
import static org.junit.Assert.*;

public class WallpaperCycleTest {
    @Test public void wrapsThirteenImagesWithoutRepeatingLast() {
        assertEquals(0, WallpaperCycle.next(12, 13));
        assertEquals(6, WallpaperCycle.next(5, 13));
    }
    @Test public void removedOrInvalidSelectionStartsAtFirst() {
        assertEquals(0, WallpaperCycle.index(13, 13));
        assertEquals(0, WallpaperCycle.index(-1, 13));
    }
    @Test public void emptyAndSingleCatalogAreSafe() {
        assertEquals(0, WallpaperCycle.next(0, 0));
        assertEquals(0, WallpaperCycle.next(0, 1));
    }
    @Test public void badPersistedIntervalReturnsDefault() {
        assertEquals(5, WallpaperCycle.minutes(0));
        assertEquals(5, WallpaperCycle.minutes(Integer.MAX_VALUE));
        assertEquals(1, WallpaperCycle.minutes(1));
        assertEquals(30, WallpaperCycle.minutes(30));
    }
}
