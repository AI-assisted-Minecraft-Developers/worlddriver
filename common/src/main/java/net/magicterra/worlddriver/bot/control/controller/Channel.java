package net.magicterra.worlddriver.bot.control.controller;

/** The four inputs a walking tick decides. Each has at most one {@link Controller} per tick. */
public enum Channel {
    /** The push or forward key, and sprint. */
    MOVE,
    /** Where the head points. */
    LOOK,
    /** The jump key. */
    JUMP,
    /** Breaking, placing and the held item. */
    HANDS
}
