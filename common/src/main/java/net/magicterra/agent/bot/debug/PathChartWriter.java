package net.magicterra.agent.bot.debug;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Encodes a chart to PNG and writes it under {@code config/agent_driver/debug/}. Returns
 * metadata (path/width/height/bytes) for the tool response. Headless via ImageIO (same as
 * Screenshots.java). Pure side-effect class; no Minecraft refs.
 */
public final class PathChartWriter {
    private PathChartWriter() {}

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final Path DIR = Path.of("config", "agent_driver", "debug");

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
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("path", out.toAbsolutePath().toString());
        meta.put("width", img.getWidth());
        meta.put("height", img.getHeight());
        meta.put("bytes", bytes.length);
        return meta;
    }
}
