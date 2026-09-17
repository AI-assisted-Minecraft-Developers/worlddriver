package net.magicterra.worlddriver.client.internal;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

import java.io.IOException;
import java.util.Base64;
import java.util.Map;

import static net.magicterra.worlddriver.client.internal.ClientThread.runOnClient;

/**
 * Framebuffer capture + downscale/transcode for {@code mc.client.screenshot}.
 * Stateless; extracted from {@code ClientDriverApiImpl}.
 */
public final class Screenshots {
    private Screenshots() {}

    public static Map<String, Object> screenshot(Map<String, Object> opts) {
        final int maxW = (opts != null && opts.get("maxWidth") instanceof Number n) ? n.intValue() : 0;
        final int maxH = (opts != null && opts.get("maxHeight") instanceof Number n) ? n.intValue() : 0;
        final String fmt = (opts != null && opts.get("format") instanceof String s) ? s.toLowerCase() : "png";
        final int quality = clampInt((opts != null && opts.get("quality") instanceof Number n) ? n.intValue() : 85, 1, 100);
        if (!fmt.equals("png") && !fmt.equals("jpeg") && !fmt.equals("jpg")) {
            return Map.of("ok", false, "error", "unsupported format: " + fmt + " (png|jpeg)");
        }

        return runOnClient(() -> {
            Minecraft mc = Minecraft.getInstance();
            RenderTarget rt = mc.getMainRenderTarget();
            NativeImage img = Screenshot.takeScreenshot(rt);
            try {
                int srcW = img.getWidth();
                int srcH = img.getHeight();
                int dstW = srcW, dstH = srcH;
                if (maxW > 0 || maxH > 0) {
                    double sx = (maxW > 0) ? (double) maxW / srcW : 1.0;
                    double sy = (maxH > 0) ? (double) maxH / srcH : 1.0;
                    double s = Math.min(sx, sy);
                    if (s < 1.0) {
                        dstW = Math.max(1, (int) Math.round(srcW * s));
                        dstH = Math.max(1, (int) Math.round(srcH * s));
                    }
                }

                // PNG, no resize → fast path uses NativeImage's own encoder.
                if (fmt.equals("png") && dstW == srcW && dstH == srcH) {
                    return reply("png", srcW, srcH, img.asByteArray());
                }

                // Resize/transcoding path via AWT: copy ARGB pixels into a
                // BufferedImage so we can scale with Graphics2D and pass to
                // ImageIO. AWT initialises in headless mode fine — ImageIO PNG
                // and JPEG encoders don't need a display.
                java.awt.image.BufferedImage src = new java.awt.image.BufferedImage(
                        srcW, srcH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                int[] argb = new int[srcW * srcH];
                for (int y = 0; y < srcH; y++) {
                    for (int x = 0; x < srcW; x++) {
                        // NativeImage stores ABGR little-endian; convert to AARRGGBB for AWT.
                        int p = img.getPixelRGBA(x, y);
                        int r = p & 0xFF;
                        int g = (p >> 8) & 0xFF;
                        int b = (p >> 16) & 0xFF;
                        int a = (p >> 24) & 0xFF;
                        argb[y * srcW + x] = (a << 24) | (r << 16) | (g << 8) | b;
                    }
                }
                src.setRGB(0, 0, srcW, srcH, argb, 0, srcW);

                java.awt.image.BufferedImage out;
                if (dstW != srcW || dstH != srcH) {
                    out = new java.awt.image.BufferedImage(
                            dstW, dstH,
                            fmt.equals("png") ? java.awt.image.BufferedImage.TYPE_INT_ARGB
                                              : java.awt.image.BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g2 = out.createGraphics();
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                            java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                            java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                    g2.drawImage(src, 0, 0, dstW, dstH, null);
                    g2.dispose();
                } else if (fmt.equals("jpeg") || fmt.equals("jpg")) {
                    // JPEG doesn't carry alpha; collapse onto opaque RGB.
                    out = new java.awt.image.BufferedImage(srcW, srcH, java.awt.image.BufferedImage.TYPE_INT_RGB);
                    java.awt.Graphics2D g2 = out.createGraphics();
                    g2.drawImage(src, 0, 0, null);
                    g2.dispose();
                } else {
                    out = src;
                }

                String mime = fmt.equals("png") ? "png" : "jpeg";
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                if (mime.equals("jpeg")) {
                    writeJpeg(out, baos, quality);
                } else {
                    javax.imageio.ImageIO.write(out, "png", baos);
                }
                return reply(mime, dstW, dstH, baos.toByteArray());
            } catch (IOException e) {
                throw new RuntimeException("screenshot encode failed", e);
            } finally {
                img.close();
            }
        });
    }

    /**
     * The capture, plus what it is a capture of.
     *
     * <p>A window nothing is presenting keeps its last frame: the framebuffer still reads, the
     * image is the right size and looks entirely plausible, and it can be minutes old — three
     * captures seven seconds apart of a rainy world came back byte-identical. So the reply carries
     * the two measurements that tell a live frame from a retained one rather than a verdict:
     * whether the compositor considers the window active, and how many frames the client believes
     * it drew in the last second. Identical captures with {@code windowActive:false} are a stale
     * frame, not a still world.
     */
    private static Map<String, Object> reply(String format, int width, int height, byte[] bytes) {
        Minecraft mc = Minecraft.getInstance();
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("format", format);
        m.put("width", width);
        m.put("height", height);
        m.put("windowActive", mc.isWindowActive());
        m.put("fps", mc.getFps());
        m.put("base64", Base64.getEncoder().encodeToString(bytes));
        return m;
    }

    private static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    private static void writeJpeg(java.awt.image.BufferedImage img, java.io.OutputStream out, int quality)
            throws IOException {
        var writers = javax.imageio.ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            // Fallback: default-quality JPEG via ImageIO.
            javax.imageio.ImageIO.write(img, "jpeg", out);
            return;
        }
        javax.imageio.ImageWriter w = writers.next();
        javax.imageio.ImageWriteParam p = w.getDefaultWriteParam();
        p.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality / 100f);
        try (javax.imageio.stream.ImageOutputStream ios = javax.imageio.ImageIO.createImageOutputStream(out)) {
            w.setOutput(ios);
            w.write(null, new javax.imageio.IIOImage(img, null, null), p);
        } finally {
            w.dispose();
        }
    }
}
