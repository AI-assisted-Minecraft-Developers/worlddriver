package net.magicterra.worlddriver.testcontent;

/**
 * A mob whose legs a driver can take. It has to be the mob's own class that implements this: see
 * {@link DrivenPiglin} for what a driver has to switch off and why only a subclass can.
 */
public interface DrivenMob {
    /** Take the legs ({@code true}) or hand them back to the mob's own AI. */
    void setDriven(boolean driven);

    boolean isDriven();

    /** One tick while driven, with this step's input already written onto the entity's own fields. */
    void pump();
}
