package net.magicterra.agent.bot.debug;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Encodes a chart to PNG and writes it under {@code config/agent_driver/debug/}. Returns
 * metadata (path/width/height/bytes) for the tool response. Headless via ImageIO (same as
 * Screenshots.java). Pure side-effect class; no Minecraft refs.
 */
public final class PathChartWriter {
    private PathChartWriter() {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Path DIR = Path.of("config", "agent_driver", "debug");
    /** Keep at most this many chart PNGs; oldest are pruned so the dir can't grow unbounded. */
    private static final int MAX_FILES = 1000;

    /** Write the image. {@code nameOverride} optional (without extension); else pathchart-NNNN. */
    public static Map<String, Object> write(BufferedImage img, String nameOverride) throws Exception {
        Files.createDirectories(DIR);
        String name = (nameOverride != null && !nameOverride.isBlank())
                ? nameOverride
                : String.format("pathchart-%04d-%d", SEQ.incrementAndGet(), System.currentTimeMillis());
        Path out = DIR.resolve(name + ".png");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        byte[] bytes = baos.toByteArray();
        Files.write(out, bytes);
        prune();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("path", out.toAbsolutePath().toString());
        meta.put("width", img.getWidth());
        meta.put("height", img.getHeight());
        meta.put("bytes", bytes.length);
        return meta;
    }

    /**
     * Cap the debug dir at {@link #MAX_FILES} PNGs by deleting the oldest (by last-modified
     * time) once the count exceeds the cap. Best-effort: any I/O hiccup is swallowed so a
     * failed prune never breaks the write that just succeeded.
     */
    private static void prune() {
        try (Stream<Path> s = Files.list(DIR)) {
            List<Path> pngs = new ArrayList<>(s
                    .filter(p -> p.getFileName().toString().endsWith(".png"))
                    .toList());
            if (pngs.size() <= MAX_FILES) return;
            pngs.sort(Comparator.comparing(PathChartWriter::mtime));   // oldest first
            for (int i = 0, n = pngs.size() - MAX_FILES; i < n; i++) {
                try { Files.deleteIfExists(pngs.get(i)); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
            // dir missing / listing failed — nothing to prune
        }
    }

    private static FileTime mtime(Path p) {
        try { return Files.getLastModifiedTime(p); }
        catch (Exception e) { return FileTime.fromMillis(0); }   // unreadable → treat as oldest
    }
}
