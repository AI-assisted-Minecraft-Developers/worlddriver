package net.magicterra.worlddriver.bot;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a {@link BotConfig} field as state the bot rewrites for itself every tick, not a setting.
 *
 * <p>Such a field is left off the {@code mc.bot.setting} surface (schema, snapshot and writes) and
 * out of the saved config. A caller's write would be overwritten within a tick while the reply said
 * {@code applied}, and a saved value would come back on the next start as if it were configuration.
 * The field stays {@code public static volatile} because the ticks that write it live in other packages.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface RuntimeState {}
