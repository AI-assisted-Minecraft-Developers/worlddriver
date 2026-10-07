package net.magicterra.worlddriver.api.contract;

/**
 * A number token in a value tree that the parser would not turn into a value: too long, or with an
 * exponent too large to spell out. The parse goes on, so the request's id stays readable, and every
 * typed decode reports this value as invalid at its own path; nothing converts it to a number.
 */
public record RejectedNumber(String token, String reason) {}
