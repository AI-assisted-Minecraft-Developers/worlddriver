package net.magicterra.worlddriver.api.schema;

/**
 * A contract record with a rule no single field can enforce. {@link #check} runs once every field has decoded and the
 * record is built; it reports at the field it blames ({@code at.field("target").fail(...)}) and may report several
 * problems in one go, so a caller sees all of them at once.
 */
public interface SelfChecked {
    void check(DecodeContext at);
}
