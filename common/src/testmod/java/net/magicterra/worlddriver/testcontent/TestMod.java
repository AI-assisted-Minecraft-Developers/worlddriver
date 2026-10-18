package net.magicterra.worlddriver.testcontent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The id the testmod's content registers under, the driver's own, and the log it writes to, the driver's too. */
public final class TestMod {
    private TestMod() {}

    public static final String MOD_ID = "worlddriver";
    public static final Logger LOG = LoggerFactory.getLogger("WorldDriver");
}
