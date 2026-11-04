package net.magicterra.worlddriver.api.game;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

/**
 * What a mod registers so {@code mc.client.screen.tree} lists the elements inside a widget of its own: one walker per
 * widget class.
 *
 * <p>The tree follows vanilla's children, which a UI library that keeps its own element tree inside one widget does not
 * expose: such a screen reads as that single widget. A walker lists the elements in it, each with the box it is drawn
 * in, and each becomes a node of the tree with a ref like any widget's. A click on that ref lands at the centre of the
 * element's box, through the screen, as a human's click there would, so the widget dispatches it to the element itself;
 * text typed at that ref goes to the element after such a click has focused it.
 *
 * <p>Registered from the client's setup; the widget class is named as a {@code Class} so that this type holds no client
 * class and can be loaded on a dedicated server. One registry per game process. A walker runs on the client thread,
 * inside the tree's read, and one that throws costs its own widget's node, which reports the error.
 */
public final class ScreenTrees {

    private static final Map<Class<?>, Walker<Object>> BY_CLASS = new ConcurrentHashMap<>();

    private ScreenTrees() {}

    /** What lists the elements of one kind of widget. */
    @FunctionalInterface
    public interface Walker<T> {
        /** List the elements of {@code widget} into {@code out}, as it is drawn now. */
        void walk(T widget, Sink out);
    }

    /** Where a walker lists elements, each under the widget, or under an element added before. */
    public interface Sink {
        /**
         * List {@code element} here and answer the sink for the elements it holds. An element is named by its identity,
         * so the same object gets the same ref on every read for as long as it lives; one already listed in this read
         * is not listed again, and what is added under it then goes nowhere.
         */
        Sink add(Object element, Element info);
    }

    /**
     * What a node says of one element. Every part but its type may be left out, and is then absent from the node; an
     * element with no box cannot be clicked by ref.
     */
    public record Element(
            String type,
            Optional<Box> box,
            Optional<Boolean> visible,
            Optional<Boolean> active,
            Optional<Boolean> focused,
            Optional<Boolean> selected,
            Optional<String> message,
            Optional<String> key,
            Optional<String> value) {

        /** Every part given; {@link #of} and the methods after it are the shorter way. */
        public Element {
            Objects.requireNonNull(type, "type");
        }

        /** An element of {@code type}, as the node's type names it (a class's simple name, for vanilla's widgets). */
        public static Element of(String type) {
            return new Element(
                    type,
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty(),
                    Optional.empty());
        }

        /** Drawn at GUI {@code x}/{@code y}, the screen's coordinates, {@code width} by {@code height}. */
        public Element box(int x, int y, int width, int height) {
            return new Element(
                    type,
                    Optional.of(new Box(x, y, width, height)),
                    visible,
                    active,
                    focused,
                    selected,
                    message,
                    key,
                    value);
        }

        /** Shown or hidden; a hidden element, and one under a hidden element, is refused a click. */
        public Element visible(boolean shown) {
            return new Element(type, box, Optional.of(shown), active, focused, selected, message, key, value);
        }

        /** Whether it takes input now. */
        public Element active(boolean takes) {
            return new Element(type, box, visible, Optional.of(takes), focused, selected, message, key, value);
        }

        /** Whether it has the keyboard's focus, as a text field being typed into does. */
        public Element focused(boolean has) {
            return new Element(type, box, visible, active, Optional.of(has), selected, message, key, value);
        }

        /** Chosen among its kind: a tab that is open, a row that is picked. */
        public Element selected(boolean chosen) {
            return new Element(type, box, visible, active, focused, Optional.of(chosen), message, key, value);
        }

        /** Its label, in the player's language, with the translation key when the label has one. */
        public Element label(Component text) {
            Optional<String> k =
                    text.getContents() instanceof TranslatableContents tc ? Optional.of(tc.getKey()) : Optional.empty();
            return new Element(type, box, visible, active, focused, selected, Optional.of(text.getString()), k, value);
        }

        /** Its label as plain text, when there is no component to give. */
        public Element label(String text) {
            return new Element(type, box, visible, active, focused, selected, Optional.of(text), key, value);
        }

        /** What a text field holds. */
        public Element value(String text) {
            return new Element(type, box, visible, active, focused, selected, message, key, Optional.of(text));
        }
    }

    /** A box in GUI coordinates. */
    public record Box(int x, int y, int width, int height) {}

    /**
     * List the elements of every widget that is a {@code type}, a subclass included, with {@code walker}; for such a
     * widget it replaces the walk of vanilla's children. A second registration for the same class is refused.
     */
    @SuppressWarnings("unchecked")
    public static <T> void register(Class<T> type, Walker<? super T> walker) {
        Objects.requireNonNull(walker, "walker");
        Walker<Object> w = (widget, out) -> walker.walk((T) widget, out);
        if (BY_CLASS.putIfAbsent(Objects.requireNonNull(type, "type"), w) != null) {
            throw new IllegalStateException("a screen tree walker for " + type.getName() + " is already registered");
        }
    }

    /**
     * The walker for {@code type}: the one registered for it, else for its nearest superclass that has one, else for an
     * interface it implements.
     */
    public static Optional<Walker<Object>> forClass(Class<?> type) {
        if (BY_CLASS.isEmpty()) {
            return Optional.empty();
        }
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            Walker<Object> w = BY_CLASS.get(c);
            if (w != null) {
                return Optional.of(w);
            }
        }
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                Walker<Object> w = BY_CLASS.get(i);
                if (w != null) {
                    return Optional.of(w);
                }
            }
        }
        return Optional.empty();
    }
}
