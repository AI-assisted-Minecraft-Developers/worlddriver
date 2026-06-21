package net.magicterra.agent.bot.debug;

import net.magicterra.agent.bot.pathfinder.PathTrace;
import net.minecraft.core.BlockPos;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.util.List;

/**
 * Pure renderer: {@link PathSession} → {@link BufferedImage}. No I/O, no Minecraft world
 * access. Layout = a top-down X/Z map (top), an elevation profile (mid), and two
 * time-series strips (speed; actual-vs-target heading). Headless-safe (AWT, like
 * Screenshots.java).
 */
public final class PathChartRenderer {
    private PathChartRenderer() {}

    public record Opts(int width, int height, double blocksPerPixel, boolean includeCandidates) {
        public static Opts defaults() { return new Opts(1280, 960, 0, true); } // bpp 0 = auto-fit
    }

    private static final Color BG = new Color(18, 18, 22);
    private static final Color PANEL = new Color(28, 28, 34);
    private static final Color GRID = new Color(60, 60, 70);
    private static final Color TEXT = new Color(220, 220, 225);
    private static final Color PLAN_LATEST = new Color(90, 170, 255);
    private static final Color PLAN_OLD = new Color(90, 170, 255, 70);
    private static final Color PLAN_FAILED = new Color(255, 140, 60);
    private static final Color START = new Color(80, 220, 120);
    private static final Color GOAL = new Color(240, 80, 80);
    private static final Color CUR = new Color(80, 230, 230);
    private static final Color YAW_ACTUAL = new Color(120, 220, 140);
    private static final Color YAW_TARGET = new Color(230, 200, 90);

    public static BufferedImage render(PathSession s, Opts opts) {
        int W = opts.width(), H = opts.height();
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG);
        g.fillRect(0, 0, W, H);

        int header = 64;
        int mapH = (H - header) * 55 / 100;
        int elevH = (H - header) * 20 / 100;
        int tsH = (H - header) - mapH - elevH;
        int mapY = header, elevY = header + mapH, tsY = elevY + elevH;

        drawHeader(g, s, W, header);
        drawMap(g, s, opts, 0, mapY, W, mapH);
        drawElevation(g, s, 0, elevY, W, elevH);
        drawTimeSeries(g, s, 0, tsY, W, tsH);

        g.dispose();
        return img;
    }

    // ---- Header --------------------------------------------------------------
    private static void drawHeader(Graphics2D g, PathSession s, int W, int h) {
        g.setColor(PANEL);
        g.fillRect(0, 0, W, h);
        g.setColor(TEXT);
        g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 14));
        PathSession.PlannedRoute last = lastPlan(s);
        double[] sp = speedStats(s.trajectory());
        double maxErr = maxYawError(s.trajectory());
        String l1 = String.format("goal=%s  outcome=%s  reason=%s",
                s.goalDesc(), s.outcome() == null ? "RUNNING" : s.outcome(),
                s.reason() == null ? "-" : s.reason());
        String l2 = String.format("plans=%d  lastPathLen=%d  reached=%s  expanded=%d  ms=%d  cost=%.0f  candidates=%d  samples=%d  avgSpd=%.2f peakSpd=%.2f b/s  maxYawErr=%.0f deg",
                s.plannedRoutes().size(),
                last == null ? 0 : last.path().size(),
                last == null ? "-" : String.valueOf(last.goalReached()),
                last == null ? 0 : last.expanded(),
                last == null ? 0 : last.ms(),
                last == null ? 0.0 : last.finalCost(),
                s.candidates().size(), s.trajectory().size(),
                sp[0], sp[1], maxErr);
        g.drawString(l1, 8, 22);
        g.drawString(l2, 8, 44);
    }

    // ---- Top-down map --------------------------------------------------------
    private static void drawMap(Graphics2D g, PathSession s, Opts opts, int x0, int y0, int w, int h) {
        g.setColor(PANEL); g.fillRect(x0, y0, w, h);
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (PathSession.Candidate c : s.candidates()) { minX = Math.min(minX, c.x()); maxX = Math.max(maxX, c.x()); minZ = Math.min(minZ, c.z()); maxZ = Math.max(maxZ, c.z()); }
        for (PathSession.PlannedRoute r : s.plannedRoutes()) for (BlockPos p : r.path()) { minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX()); minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ()); }
        for (PathTrace.WalkerSample t : s.trajectory()) { minX = Math.min(minX, t.x()); maxX = Math.max(maxX, t.x()); minZ = Math.min(minZ, t.z()); maxZ = Math.max(maxZ, t.z()); }
        if (!Double.isFinite(minX)) { g.setColor(TEXT); g.drawString("(no path data captured — enable pathDebug then run a goto)", x0 + 12, y0 + 24); return; }

        double pad = 3;
        minX -= pad; maxX += pad; minZ -= pad; maxZ += pad;
        double spanX = Math.max(1, maxX - minX), spanZ = Math.max(1, maxZ - minZ);
        double scale = Math.min((w - 20) / spanX, (h - 20) / spanZ);
        final double fMinX = minX, fMinZ = minZ, fScale = scale;
        final int fx0 = x0 + 10, fy0 = y0 + 10;
        java.util.function.DoubleUnaryOperator px = wx -> fx0 + (wx - fMinX) * fScale;
        java.util.function.DoubleUnaryOperator pz = wz -> fy0 + (wz - fMinZ) * fScale;

        // candidate heat
        if (opts.includeCandidates() && !s.candidates().isEmpty()) {
            double gmin = Double.POSITIVE_INFINITY, gmax = Double.NEGATIVE_INFINITY;
            for (PathSession.Candidate c : s.candidates()) { gmin = Math.min(gmin, c.g()); gmax = Math.max(gmax, c.g()); }
            double gspan = Math.max(1e-9, gmax - gmin);
            for (PathSession.Candidate c : s.candidates()) {
                float t = (float) ((c.g() - gmin) / gspan);
                g.setColor(new Color(60 + (int) (150 * t), 60, 160 - (int) (120 * t), 90));
                int cx = (int) px.applyAsDouble(c.x()), cz = (int) pz.applyAsDouble(c.z());
                g.fillRect(cx, cz, 2, 2);
            }
        }
        // planned routes
        List<PathSession.PlannedRoute> plans = s.plannedRoutes();
        for (int i = 0; i < plans.size(); i++) {
            PathSession.PlannedRoute r = plans.get(i);
            boolean latest = (i == plans.size() - 1);
            g.setColor(!r.goalReached() ? PLAN_FAILED : latest ? PLAN_LATEST : PLAN_OLD);
            g.setStroke(latest ? new BasicStroke(2.5f)
                    : !r.goalReached() ? new BasicStroke(1.6f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{5f, 5f}, 0f)
                    : new BasicStroke(1.0f));
            drawPolyline(g, r.path(), px, pz);
        }
        // actual trajectory, speed-coloured
        drawTrajectory(g, s.trajectory(), px, pz);
        // markers
        if (s.start() != null) marker(g, START, px.applyAsDouble(s.start().getX() + 0.5), pz.applyAsDouble(s.start().getZ() + 0.5), 6);
        if (s.goalMarker() != null) {
            double gx = px.applyAsDouble(s.goalMarker().getX() + 0.5), gz = pz.applyAsDouble(s.goalMarker().getZ() + 0.5);
            boolean reached = lastPlan(s) != null && lastPlan(s).goalReached();
            marker(g, GOAL, gx, gz, 6);
            if (!reached) { g.setColor(GOAL); g.setStroke(new BasicStroke(2f)); g.draw(new Line2D.Double(gx - 7, gz - 7, gx + 7, gz + 7)); g.draw(new Line2D.Double(gx - 7, gz + 7, gx + 7, gz - 7)); }
        }
        if (!s.trajectory().isEmpty()) {
            PathTrace.WalkerSample cur = s.trajectory().get(s.trajectory().size() - 1);
            marker(g, CUR, px.applyAsDouble(cur.x()), pz.applyAsDouble(cur.z()), 5);
        }
        // heading arrows every N samples
        drawHeadingArrows(g, s.trajectory(), px, pz);
        g.setColor(TEXT); g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString("top-down X/Z  (blue=plan, orange=failed plan, green=start, red=goal, cyan=now, arrows=heading)", x0 + 12, y0 + h - 8);
    }

    private static void drawPolyline(Graphics2D g, List<BlockPos> path, java.util.function.DoubleUnaryOperator px, java.util.function.DoubleUnaryOperator pz) {
        for (int i = 1; i < path.size(); i++) {
            BlockPos a = path.get(i - 1), b = path.get(i);
            g.draw(new Line2D.Double(px.applyAsDouble(a.getX() + 0.5), pz.applyAsDouble(a.getZ() + 0.5),
                    px.applyAsDouble(b.getX() + 0.5), pz.applyAsDouble(b.getZ() + 0.5)));
        }
    }

    private static void drawTrajectory(Graphics2D g, List<PathTrace.WalkerSample> tr, java.util.function.DoubleUnaryOperator px, java.util.function.DoubleUnaryOperator pz) {
        g.setStroke(new BasicStroke(2.0f));
        for (int i = 1; i < tr.size(); i++) {
            PathTrace.WalkerSample a = tr.get(i - 1), b = tr.get(i);
            double bps = speedBetween(a, b);
            g.setColor(speedColor(bps));
            g.draw(new Line2D.Double(px.applyAsDouble(a.x()), pz.applyAsDouble(a.z()), px.applyAsDouble(b.x()), pz.applyAsDouble(b.z())));
        }
    }

    private static void drawHeadingArrows(Graphics2D g, List<PathTrace.WalkerSample> tr, java.util.function.DoubleUnaryOperator px, java.util.function.DoubleUnaryOperator pz) {
        int n = tr.size();
        if (n == 0) return;
        int stepEvery = Math.max(1, n / 30);
        for (int i = 0; i < n; i += stepEvery) {
            PathTrace.WalkerSample t = tr.get(i);
            double ox = px.applyAsDouble(t.x()), oz = pz.applyAsDouble(t.z());
            // actual heading (yaw): MC yaw 0=+Z, 90=-X. dx=-sin(yaw), dz=cos(yaw)
            double ar = Math.toRadians(t.yawActual());
            arrow(g, YAW_ACTUAL, ox, oz, -Math.sin(ar), Math.cos(ar), 10);
            if (!Double.isNaN(t.targetX())) {
                double dx = t.targetX() - t.x(), dz = t.targetZ() - t.z();
                double len = Math.hypot(dx, dz);
                if (len > 1e-3) arrow(g, YAW_TARGET, ox, oz, dx / len, dz / len, 10);
            }
        }
    }

    private static void arrow(Graphics2D g, Color c, double ox, double oz, double dx, double dz, double len) {
        g.setColor(c); g.setStroke(new BasicStroke(1.4f));
        double ex = ox + dx * len, ez = oz + dz * len;
        g.draw(new Line2D.Double(ox, oz, ex, ez));
    }

    private static void marker(Graphics2D g, Color c, double x, double y, int r) {
        g.setColor(c); g.fillOval((int) x - r, (int) y - r, r * 2, r * 2);
    }

    // ---- Elevation profile ---------------------------------------------------
    private static void drawElevation(Graphics2D g, PathSession s, int x0, int y0, int w, int h) {
        g.setColor(PANEL); g.fillRect(x0, y0, w, h);
        g.setColor(TEXT); g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString("elevation: Y vs distance  (green=actual, blue=latest plan)", x0 + 12, y0 + 14);

        List<PathTrace.WalkerSample> tr = s.trajectory();
        PathSession.PlannedRoute plan = lastPlan(s);
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        for (PathTrace.WalkerSample t : tr) { minY = Math.min(minY, t.y()); maxY = Math.max(maxY, t.y()); }
        if (plan != null) for (BlockPos p : plan.path()) { minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY()); }
        if (!Double.isFinite(minY)) return;
        if (maxY - minY < 1) { maxY = minY + 1; }
        int top = y0 + 22, bot = y0 + h - 10;
        final double fMinY = minY, fMaxY = maxY; final int ftop = top, fbot = bot;
        java.util.function.DoubleUnaryOperator py = wy -> fbot - (wy - fMinY) / (fMaxY - fMinY) * (fbot - ftop);

        if (plan != null && plan.path().size() > 1) {
            g.setColor(PLAN_LATEST); g.setStroke(new BasicStroke(1.8f));
            double total = planDist(plan.path());
            double acc = 0; double prevX = x0 + 12; double prevY = py.applyAsDouble(plan.path().get(0).getY());
            for (int i = 1; i < plan.path().size(); i++) {
                acc += horiz(plan.path().get(i - 1), plan.path().get(i));
                double xx = x0 + 12 + (acc / Math.max(1, total)) * (w - 24);
                double yy = py.applyAsDouble(plan.path().get(i).getY());
                g.draw(new Line2D.Double(prevX, prevY, xx, yy)); prevX = xx; prevY = yy;
            }
        }
        if (tr.size() > 1) {
            g.setColor(YAW_ACTUAL); g.setStroke(new BasicStroke(1.8f));
            double total = trajDist(tr); double acc = 0;
            double prevX = x0 + 12; double prevY = py.applyAsDouble(tr.get(0).y());
            for (int i = 1; i < tr.size(); i++) {
                acc += Math.hypot(tr.get(i).x() - tr.get(i - 1).x(), tr.get(i).z() - tr.get(i - 1).z());
                double xx = x0 + 12 + (acc / Math.max(1, total)) * (w - 24);
                double yy = py.applyAsDouble(tr.get(i).y());
                g.draw(new Line2D.Double(prevX, prevY, xx, yy)); prevX = xx; prevY = yy;
            }
        }
    }

    // ---- Time series ---------------------------------------------------------
    private static void drawTimeSeries(Graphics2D g, PathSession s, int x0, int y0, int w, int h) {
        int half = h / 2;
        drawSpeedSeries(g, s, x0, y0, w, half);
        drawYawSeries(g, s, x0, y0 + half, w, h - half);
    }

    private static void drawSpeedSeries(Graphics2D g, PathSession s, int x0, int y0, int w, int h) {
        g.setColor(PANEL); g.fillRect(x0, y0, w, h);
        g.setColor(TEXT); g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString("speed (b/s)  refs: sprint~5.6 walk~4.3", x0 + 12, y0 + 14);
        List<PathTrace.WalkerSample> tr = s.trajectory();
        int top = y0 + 20, bot = y0 + h - 6;
        double maxV = 6.5;
        g.setColor(GRID); g.setStroke(new BasicStroke(1f));
        for (double ref : new double[]{4.3, 5.6}) {
            int yy = (int) (bot - ref / maxV * (bot - top));
            g.draw(new Line2D.Double(x0 + 12, yy, x0 + w - 12, yy));
        }
        if (tr.size() < 2) return;
        g.setColor(new Color(110, 200, 255)); g.setStroke(new BasicStroke(1.6f));
        // One speed value per interval i (between sample i-1 and i); step plot.
        double prevX = Double.NaN, prevY = Double.NaN;
        for (int i = 1; i < tr.size(); i++) {
            double bps = Math.min(maxV, speedBetween(tr.get(i - 1), tr.get(i)));
            double xL = x0 + 12 + (double) (i - 1) / (tr.size() - 1) * (w - 24);
            double xR = x0 + 12 + (double) i / (tr.size() - 1) * (w - 24);
            double y = bot - bps / maxV * (bot - top);
            g.draw(new Line2D.Double(xL, y, xR, y));            // flat over the interval
            if (!Double.isNaN(prevX)) g.draw(new Line2D.Double(prevX, prevY, xL, y)); // riser
            prevX = xR; prevY = y;
        }
    }

    private static void drawYawSeries(Graphics2D g, PathSession s, int x0, int y0, int w, int h) {
        g.setColor(PANEL); g.fillRect(x0, y0, w, h);
        g.setColor(TEXT); g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        g.drawString("heading: actual yaw (green) vs target bearing (yellow), -180..180", x0 + 12, y0 + 14);
        List<PathTrace.WalkerSample> tr = s.trajectory();
        int top = y0 + 20, bot = y0 + h - 6;
        java.util.function.DoubleUnaryOperator py = v -> bot - (v + 180) / 360.0 * (bot - top);
        g.setColor(GRID); g.draw(new Line2D.Double(x0 + 12, py.applyAsDouble(0), x0 + w - 12, py.applyAsDouble(0)));
        if (tr.isEmpty()) return;
        plotYaw(g, tr, x0, w, py, YAW_ACTUAL, true);
        plotYaw(g, tr, x0, w, py, YAW_TARGET, false);
    }

    private static void plotYaw(Graphics2D g, List<PathTrace.WalkerSample> tr, int x0, int w,
                                java.util.function.DoubleUnaryOperator py, Color c, boolean actual) {
        g.setColor(c); g.setStroke(new BasicStroke(1.4f));
        double prevX = Double.NaN, prevY = Double.NaN;
        double prevVal = Double.NaN;
        for (int i = 0; i < tr.size(); i++) {
            // NB: keep this as separate statements — a `actual ? (double)x : Double`
            // ternary has type double and would unbox a null targetBearing() → NPE.
            Double v;
            if (actual) v = wrap180(tr.get(i).yawActual());
            else v = targetBearing(tr.get(i));
            if (v == null) { prevX = Double.NaN; prevVal = Double.NaN; continue; }
            double x = x0 + 12 + (tr.size() == 1 ? 0 : (double) i / (tr.size() - 1) * (w - 24));
            double y = py.applyAsDouble(v);
            // Skip the connector across a -180/+180 wrap jump to avoid a full-height streak.
            if (!Double.isNaN(prevX) && Math.abs(v - prevVal) < 180.0) g.draw(new Line2D.Double(prevX, prevY, x, y));
            prevX = x; prevY = y; prevVal = v;
        }
    }

    // ---- math helpers --------------------------------------------------------
    private static PathSession.PlannedRoute lastPlan(PathSession s) {
        return s.plannedRoutes().isEmpty() ? null : s.plannedRoutes().get(s.plannedRoutes().size() - 1);
    }
    private static double horiz(BlockPos a, BlockPos b) { double dx = a.getX() - b.getX(), dz = a.getZ() - b.getZ(); return Math.hypot(dx, dz); }
    private static double planDist(List<BlockPos> p) { double d = 0; for (int i = 1; i < p.size(); i++) d += horiz(p.get(i - 1), p.get(i)); return d; }
    private static double trajDist(List<PathTrace.WalkerSample> t) { double d = 0; for (int i = 1; i < t.size(); i++) d += Math.hypot(t.get(i).x() - t.get(i - 1).x(), t.get(i).z() - t.get(i - 1).z()); return d; }
    private static double speedBetween(PathTrace.WalkerSample a, PathTrace.WalkerSample b) {
        long dt = Math.max(1, b.tick() - a.tick());
        double dist = Math.hypot(b.x() - a.x(), b.z() - a.z());
        return dist / dt * 20.0; // blocks per second (20 tps)
    }
    private static double[] speedStats(List<PathTrace.WalkerSample> tr) {
        double sum = 0, peak = 0; int n = 0;
        for (int i = 1; i < tr.size(); i++) { double v = speedBetween(tr.get(i - 1), tr.get(i)); sum += v; peak = Math.max(peak, v); n++; }
        return new double[]{n == 0 ? 0 : sum / n, peak};
    }
    private static Double targetBearing(PathTrace.WalkerSample t) {
        if (Double.isNaN(t.targetX())) return null;
        double dx = t.targetX() - t.x(), dz = t.targetZ() - t.z();
        // The recorded target is the current WAYPOINT centre; as the bot reaches/passes
        // it the bearing degenerates and flips ~180° (a node-passage artifact, not a real
        // heading error). Skip samples within ~0.75 blk (just past the step-advance gate)
        // so the yellow plot and maxYawError reflect actual travel heading, not those flips.
        if (Math.hypot(dx, dz) < 0.75) return null;
        return wrap180(Math.toDegrees(Math.atan2(-dx, dz)));
    }
    private static double maxYawError(List<PathTrace.WalkerSample> tr) {
        double m = 0;
        for (PathTrace.WalkerSample t : tr) { Double b = targetBearing(t); if (b == null) continue; m = Math.max(m, Math.abs(wrap180(t.yawActual() - b))); }
        return m;
    }
    private static double wrap180(double deg) { double d = ((deg + 180) % 360 + 360) % 360 - 180; return d; }
    private static Color speedColor(double bps) {
        double t = Math.max(0, Math.min(1, bps / 6.0)); // 0=red(slow) → 1=green(fast)
        return new Color((int) (230 * (1 - t)) + 20, (int) (210 * t) + 20, 40);
    }

    // ==== Three-view (orthographic projections) ===============================
    /**
     * Engineering-style three-view of the path: FRONT (X/Y), SIDE (Z/Y) and TOP (X/Z)
     * orthographic projections, each overlaying the PLANNED route(s) (blue) and the ACTUAL
     * executed trajectory (speed-coloured). The horizontal world axes (X, Z) share a scale
     * across the views that use them, as does the vertical Y axis, so the panels align like a
     * real third-angle drawing — FRONT sits above TOP (shared X) and left of SIDE (shared Y).
     * X/Z and Y may use different scales (a path is long but shallow, so Y is magnified for
     * legibility — each panel labels its own axes). Headless-safe (AWT, like {@link #render}).
     */
    public static BufferedImage renderThreeView(PathSession s, Opts opts) {
        int W = opts.width(), H = opts.height();
        BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(BG); g.fillRect(0, 0, W, H);

        int header = 64;
        drawHeader(g, s, W, header);

        double[] b = bounds3(s);   // {minX,maxX,minY,maxY,minZ,maxZ}
        if (b == null) {
            g.setColor(TEXT); g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
            g.drawString("(no path data captured — enable pathDebug then run a goto)", 16, header + 30);
            g.dispose(); return img;
        }
        double pad = 2;
        double minX = b[0] - pad, minY = b[2] - pad, minZ = b[4] - pad;
        double spanX = Math.max(1, (b[1] + pad) - minX);
        double spanY = Math.max(1, (b[3] + pad) - minY);
        double spanZ = Math.max(1, (b[5] + pad) - minZ);

        int gap = 10, m = 26;          // m = inner panel margin for axis labels
        int contentY = header + gap;
        int colW = (W - 3 * gap) / 2;
        int rowH = (H - header - 3 * gap) / 2;
        int frontX = gap,          frontY = contentY;                // X/Y  top-left
        int sideX  = gap * 2 + colW, sideY = contentY;               // Z/Y  top-right (shares Y with FRONT)
        int topX   = gap,          topY  = contentY + rowH + gap;    // X/Z  bottom-left (shares X with FRONT)
        int legX   = gap * 2 + colW, legY = contentY + rowH + gap;   // legend/stats bottom-right

        // Shared anisotropic scales (px per block): X & Z horizontal, Y magnified.
        double scaleX = (colW - 2 * m) / spanX;
        double scaleY = (rowH - 2 * m) / spanY;
        double scaleZ = Math.min((colW - 2 * m) / spanZ, (rowH - 2 * m) / spanZ);

        drawProjection(g, s, frontX, frontY, colW, rowH, m, "FRONT  X→ / Y↑  (looking +Z)",
                0, minX, scaleX, 1, minY, scaleY, true);
        drawProjection(g, s, sideX, sideY, colW, rowH, m, "SIDE  Z→ / Y↑  (looking +X)",
                2, minZ, scaleZ, 1, minY, scaleY, true);
        drawProjection(g, s, topX, topY, colW, rowH, m, "TOP  X→ / Z↓  (looking -Y)",
                0, minX, scaleX, 2, minZ, scaleZ, false);
        drawThreeViewLegend(g, s, legX, legY, colW, rowH);

        g.dispose();
        return img;
    }

    /** One orthographic projection panel. hAxis/vAxis: 0=X,1=Y,2=Z; vUp=true → larger value higher. */
    private static void drawProjection(Graphics2D g, PathSession s, int px, int py, int pw, int ph, int m,
            String label, int hAxis, double hMin, double hScale, int vAxis, double vMin, double vScale, boolean vUp) {
        g.setColor(PANEL); g.fillRect(px, py, pw, ph);
        g.setColor(GRID); g.setStroke(new BasicStroke(1f)); g.drawRect(px, py, pw - 1, ph - 1);
        final int fpx = px, fpy = py, fph = ph, fm = m;
        final double fhMin = hMin, fhScale = hScale, fvMin = vMin, fvScale = vScale; final boolean fvUp = vUp;
        java.util.function.DoubleUnaryOperator hx = wh -> fpx + fm + (wh - fhMin) * fhScale;
        java.util.function.DoubleUnaryOperator vy = wv -> fvUp
                ? fpy + fph - fm - (wv - fvMin) * fvScale
                : fpy + fm + (wv - fvMin) * fvScale;

        // PLANNED routes (blue / orange), oldest faint → latest solid.
        List<PathSession.PlannedRoute> plans = s.plannedRoutes();
        for (int i = 0; i < plans.size(); i++) {
            PathSession.PlannedRoute r = plans.get(i);
            boolean latest = (i == plans.size() - 1);
            g.setColor(!r.goalReached() ? PLAN_FAILED : latest ? PLAN_LATEST : PLAN_OLD);
            g.setStroke(latest ? new BasicStroke(2.2f)
                    : !r.goalReached() ? new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1f, new float[]{5f, 5f}, 0f)
                    : new BasicStroke(1.0f));
            List<BlockPos> path = r.path();
            for (int k = 1; k < path.size(); k++) {
                BlockPos a = path.get(k - 1), c = path.get(k);
                g.draw(new Line2D.Double(
                        hx.applyAsDouble(coord(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5, hAxis)),
                        vy.applyAsDouble(coord(a.getX() + 0.5, a.getY() + 0.5, a.getZ() + 0.5, vAxis)),
                        hx.applyAsDouble(coord(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, hAxis)),
                        vy.applyAsDouble(coord(c.getX() + 0.5, c.getY() + 0.5, c.getZ() + 0.5, vAxis))));
            }
        }
        // ACTUAL trajectory, speed-coloured.
        List<PathTrace.WalkerSample> tr = s.trajectory();
        g.setStroke(new BasicStroke(2.0f));
        for (int k = 1; k < tr.size(); k++) {
            PathTrace.WalkerSample a = tr.get(k - 1), c = tr.get(k);
            g.setColor(speedColor(speedBetween(a, c)));
            g.draw(new Line2D.Double(
                    hx.applyAsDouble(coord(a.x(), a.y(), a.z(), hAxis)), vy.applyAsDouble(coord(a.x(), a.y(), a.z(), vAxis)),
                    hx.applyAsDouble(coord(c.x(), c.y(), c.z(), hAxis)), vy.applyAsDouble(coord(c.x(), c.y(), c.z(), vAxis))));
        }
        // markers
        if (s.start() != null) marker(g, START,
                hx.applyAsDouble(coord(s.start().getX() + 0.5, s.start().getY() + 0.5, s.start().getZ() + 0.5, hAxis)),
                vy.applyAsDouble(coord(s.start().getX() + 0.5, s.start().getY() + 0.5, s.start().getZ() + 0.5, vAxis)), 5);
        if (s.goalMarker() != null) marker(g, GOAL,
                hx.applyAsDouble(coord(s.goalMarker().getX() + 0.5, s.goalMarker().getY() + 0.5, s.goalMarker().getZ() + 0.5, hAxis)),
                vy.applyAsDouble(coord(s.goalMarker().getX() + 0.5, s.goalMarker().getY() + 0.5, s.goalMarker().getZ() + 0.5, vAxis)), 5);
        if (!tr.isEmpty()) {
            PathTrace.WalkerSample cur = tr.get(tr.size() - 1);
            marker(g, CUR, hx.applyAsDouble(coord(cur.x(), cur.y(), cur.z(), hAxis)),
                    vy.applyAsDouble(coord(cur.x(), cur.y(), cur.z(), vAxis)), 4);
        }
        g.setColor(TEXT); g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 12));
        g.drawString(label, px + 8, py + 16);
    }

    private static void drawThreeViewLegend(Graphics2D g, PathSession s, int px, int py, int pw, int ph) {
        g.setColor(PANEL); g.fillRect(px, py, pw, ph);
        g.setColor(GRID); g.setStroke(new BasicStroke(1f)); g.drawRect(px, py, pw - 1, ph - 1);
        g.setColor(TEXT); g.setFont(new Font(Font.MONOSPACED, Font.BOLD, 13));
        g.drawString("PLAN  vs  ACTUAL", px + 14, py + 24);
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        int lx = px + 18, yy = py + 50;
        Object[][] rows = {
            {PLAN_LATEST, "plan — latest route"},
            {PLAN_OLD,    "plan — earlier replans"},
            {PLAN_FAILED, "plan — best-effort / not reached"},
            {START,       "start"},
            {GOAL,        "goal"},
            {CUR,         "current position"},
        };
        for (Object[] r : rows) {
            g.setColor((Color) r[0]);
            if (r[0] == START || r[0] == GOAL || r[0] == CUR) g.fillOval(lx, yy - 9, 10, 10);
            else g.fillRect(lx, yy - 8, 24, 6);
            g.setColor(TEXT); g.drawString((String) r[1], lx + 32, yy);
            yy += 24;
        }
        yy += 10;
        g.setColor(TEXT); g.drawString("actual path colour = speed", lx, yy);
        yy += 10;
        for (int i = 0; i < 120; i++) { g.setColor(speedColor(i / 120.0 * 6.0)); g.fillRect(lx + i, yy, 1, 10); }
        g.setColor(TEXT); g.drawString("slow", lx, yy + 24); g.drawString("fast", lx + 96, yy + 24);
        yy += 48;
        PathSession.PlannedRoute last = lastPlan(s);
        g.drawString(String.format("plans=%d   samples=%d", s.plannedRoutes().size(), s.trajectory().size()), lx, yy);
        if (last != null) g.drawString(String.format("planLen=%d   reached=%s", last.path().size(), last.goalReached()), lx, yy + 20);
        g.drawString(String.format("outcome=%s", s.outcome() == null ? "RUNNING" : s.outcome()), lx, yy + 40);
    }

    private static double coord(double x, double y, double z, int axis) { return axis == 0 ? x : axis == 1 ? y : z; }

    private static double[] bounds3(PathSession s) {
        double minX = Double.POSITIVE_INFINITY, maxX = Double.NEGATIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (PathSession.PlannedRoute r : s.plannedRoutes()) for (BlockPos p : r.path()) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        for (PathTrace.WalkerSample t : s.trajectory()) {
            minX = Math.min(minX, t.x()); maxX = Math.max(maxX, t.x());
            minY = Math.min(minY, t.y()); maxY = Math.max(maxY, t.y());
            minZ = Math.min(minZ, t.z()); maxZ = Math.max(maxZ, t.z());
        }
        if (!Double.isFinite(minX)) return null;
        return new double[]{minX, maxX, minY, maxY, minZ, maxZ};
    }
}
