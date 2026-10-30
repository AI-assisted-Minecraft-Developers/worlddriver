package net.magicterra.worlddriver.api.schema;

/** An enum constant's spelling on the wire, which is not always its Java name lower-cased. */
public interface WireName {
    /** The spelling this constant has on the wire. */
    String wireName();
}
