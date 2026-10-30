package net.magicterra.worlddriver.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The mod's id and its log, which every layer may use without reaching up to the loader wiring. */
public final class Mod {

    public static final String ID = "worlddriver";

    public static final Logger LOG = LoggerFactory.getLogger("WorldDriver");

    private Mod() {}
}
