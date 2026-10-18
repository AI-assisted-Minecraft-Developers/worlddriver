package net.magicterra.worlddriver.bot.control;

/** What a tick phase decided; {@link Walker#tick} turns it into a {@link Locomotor.Status}. */
public enum TickStep { WALKING, ARRIVED, FAILED }
