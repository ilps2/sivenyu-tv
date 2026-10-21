package io.github.op7418.sivenyutv;

import org.junit.Test;
import static org.junit.Assert.*;

public class BootStartPolicyTest {
    @Test public void newBootCanEnter() {
        assertNull(BootStartPolicy.skipReason(true, true, 20, 19, 75000));
    }
    @Test public void disabledCannotEnterEvenDuringBoot() {
        assertNotNull(BootStartPolicy.skipReason(true, false, 20, 19, 75000));
    }
    @Test public void unsupportedModelCannotEnter() {
        assertNotNull(BootStartPolicy.skipReason(false, true, 20, 19, 75000));
    }
    @Test public void duplicateCannotTakeFocusFromHomeOrHdmi() {
        assertNotNull(BootStartPolicy.skipReason(true, true, 20, 20, 76000));
    }
    @Test public void unknownBootIsSkipped() {
        assertNotNull(BootStartPolicy.skipReason(true, true, -1, -1, 75000));
    }
    @Test public void lateDeliveryCannotInterruptUse() {
        assertNull(BootStartPolicy.skipReason(true, true, 20, 19, 600000));
        assertNotNull(BootStartPolicy.skipReason(true, true, 20, 19, 600001));
    }
    @Test public void invalidClockIsSkipped() {
        assertNotNull(BootStartPolicy.skipReason(true, true, 20, 19, -1));
    }
}
