package net.magicterra.worlddriver.application.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import java.io.IOException;
import java.util.Base64;
import java.util.concurrent.CompletionStage;
import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.api.methods.client.ClientContracts;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ImageFormat;
import net.magicterra.worlddriver.api.methods.client.ClientContracts.ScreenshotParams;
import net.magicterra.worlddriver.game.ClientHop;
import net.magicterra.worlddriver.game.ui.FrameClock;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;

/**
 * Framebuffer capture + downscale/transcode for {@code mc.client.screenshot}. Stateless; extracted from
 * {@code ClientDriverApiImpl}.
 */
public final class Screenshots {

    /** How long a capture waits for a frame of its own before reporting that none was drawn. */
    private static final long FRESH_FRAME_MS = 1_000;

    /** The PNG format name, as ImageIO and the reply both spell it. */
    private static final String PNG = "png";

    /** The JPEG format name, as ImageIO and the reply both spell it. */
    private static final String JPEG = "jpeg";

    private Screenshots() {}

    /**
     * Waits for a frame drawn AFTER this request arrived, so the capture cannot be the frame that predates whatever the
     * caller did just before asking for it. A timeout is not an error — it means the client is not drawing, which
     * `frameWaited:false` reports rather than hides. No thread is held for the wait.
     *
     * <p>The capture runs on the client thread; the encode, which takes longer than a frame on a large window, runs off
     * it. The hop is queued from a background thread rather than from the frame hook that ends the wait, so the capture
     * runs at the client's next task drain like any other task.
     */
    public static CompletionStage<ClientContracts.Screenshot> screenshot(ScreenshotParams opts, CallContext ctx) {
        long before = FrameClock.drawn();
        return FrameClock.after(before, FRESH_FRAME_MS)
                .thenComposeAsync(
                        reached -> ClientHop.hop().submit(() -> capture(reached > before), ctx),
                        Util.backgroundExecutor())
                .thenApplyAsync(cap -> encode(cap, opts), Util.backgroundExecutor());
    }

    /** A frame read off the main render target, and what the reply says about it. */
    private record Capture(NativeImage img, long frame, boolean frameWaited, boolean windowActive, int fps) {}

    /** On the client thread. */
    private static Capture capture(boolean waited) {
        Minecraft mc = Minecraft.getInstance();
        RenderTarget rt = mc.getMainRenderTarget();
        return new Capture(Screenshot.takeScreenshot(rt), FrameClock.drawn(), waited, mc.isWindowActive(), mc.getFps());
    }

    /** Off the client thread: the image is plain native memory once read back. Closes it. */
    @SuppressWarnings("PMD.AvoidThrowingRawExceptionTypes") // kept: callers of the stage see this exact type today
    private static ClientContracts.Screenshot encode(Capture cap, ScreenshotParams opts) {
        final int maxW = opts.maxWidth().orElse(0);
        final int maxH = opts.maxHeight().orElse(0);
        final String fmt = opts.format() == ImageFormat.JPEG ? JPEG : PNG;
        final int quality = opts.quality();
        try (NativeImage img = cap.img()) {
            int srcW = img.getWidth();
            int srcH = img.getHeight();
            int dstW = srcW;
            int dstH = srcH;
            if (maxW > 0 || maxH > 0) {
                double sx = maxW > 0 ? (double) maxW / srcW : 1.0;
                double sy = maxH > 0 ? (double) maxH / srcH : 1.0;
                double s = Math.min(sx, sy);
                if (s < 1.0) {
                    dstW = Math.max(1, (int) Math.round(srcW * s));
                    dstH = Math.max(1, (int) Math.round(srcH * s));
                }
            }

            // PNG, no resize → fast path uses NativeImage's own encoder.
            if (PNG.equals(fmt) && dstW == srcW && dstH == srcH) {
                return reply(PNG, srcW, srcH, img.asByteArray(), cap);
            }

            // Resize/transcoding path via AWT: copy ARGB pixels into a
            // BufferedImage so we can scale with Graphics2D and pass to
            // ImageIO. AWT initialises in headless mode fine — ImageIO PNG
            // and JPEG encoders don't need a display.
            java.awt.image.BufferedImage src = toAwt(img, srcW, srcH);

            java.awt.image.BufferedImage out;
            boolean resized = dstW != srcW || dstH != srcH;
            if (resized) {
                out = new java.awt.image.BufferedImage(
                        dstW,
                        dstH,
                        PNG.equals(fmt)
                                ? java.awt.image.BufferedImage.TYPE_INT_ARGB
                                : java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g2 = out.createGraphics();
                g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_INTERPOLATION,
                        java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.setRenderingHint(
                        java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
                g2.drawImage(src, 0, 0, dstW, dstH, null);
                g2.dispose();
            } else if (JPEG.equals(fmt)) {
                // JPEG doesn't carry alpha; collapse onto opaque RGB.
                out = new java.awt.image.BufferedImage(srcW, srcH, java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g2 = out.createGraphics();
                g2.drawImage(src, 0, 0, null);
                g2.dispose();
            } else {
                out = src;
            }

            String mime = PNG.equals(fmt) ? PNG : JPEG;
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            if (JPEG.equals(mime)) {
                writeJpeg(out, baos, quality);
            } else {
                javax.imageio.ImageIO.write(out, PNG, baos);
            }
            return reply(mime, dstW, dstH, baos.toByteArray(), cap);
        } catch (IOException e) {
            throw new RuntimeException("screenshot encode failed", e);
        }
    }

    /** {@code img}'s pixels as an AWT ARGB image of the same size. */
    private static java.awt.image.BufferedImage toAwt(NativeImage img, int srcW, int srcH) {
        java.awt.image.BufferedImage src =
                new java.awt.image.BufferedImage(srcW, srcH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
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
        return src;
    }

    /**
     * The capture, plus what it is a capture of.
     *
     * <p>A window nothing is presenting keeps its last frame: the framebuffer still reads, the image is the right size
     * and looks entirely plausible, and it can be minutes old — three captures seven seconds apart of a rainy world
     * came back byte-identical.
     *
     * <p>{@code frame} is the one field that settles it, because it is the same quantity the capture is made of: two
     * replies carrying the same number are the same image, whatever the picture shows. {@code frameWaited} says this
     * capture waited for a frame of its own, so anything the caller caused before asking is in it. {@code windowActive}
     * and {@code fps} stay as context — note that {@code fps} above zero only rules out a renderer that has stopped,
     * not a frame that is one round-trip out of date.
     */
    private static ClientContracts.Screenshot reply(String format, int width, int height, byte[] bytes, Capture cap) {
        return new ClientContracts.Screenshot(
                PNG.equals(format) ? ImageFormat.PNG : ImageFormat.JPEG,
                width,
                height,
                cap.frame(),
                cap.frameWaited(),
                cap.windowActive(),
                cap.fps(),
                Base64.getEncoder().encodeToString(bytes));
    }

    private static void writeJpeg(java.awt.image.BufferedImage img, java.io.OutputStream out, int quality)
            throws IOException {
        var writers = javax.imageio.ImageIO.getImageWritersByFormatName(JPEG);
        if (!writers.hasNext()) {
            // Fallback: default-quality JPEG via ImageIO.
            javax.imageio.ImageIO.write(img, JPEG, out);
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
