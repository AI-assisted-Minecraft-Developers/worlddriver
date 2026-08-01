package net.magicterra.worlddriver.bot.debug;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Handler for {@code mc.debug.pathChart}. Reads the current recorder snapshot, renders the
 * dashboard, writes a PNG, and returns its metadata + a small stats summary. Pure data →
 * image → file; no Minecraft world access, so it runs fine on the RPC thread (no need to
 * bounce to the client thread).
 */
public final class PathChartTool {
    private PathChartTool() {}

    private static volatile PathDebugRecorder recorder;
    static void bind(PathDebugRecorder r) { recorder = r; }

    public static Map<String, Object> render(Map<String, Object> p) {
        PathDebugRecorder r = recorder;
        if (r == null) return Map.of("ok", false, "error", "path-debug not initialised");
        // Clamp width/height so a bad value can't make BufferedImage throw (schema allows [256,4096]).
        int w = Math.max(256, Math.min(4096, intOpt(p, "width", 1280)));
        int h = Math.max(256, Math.min(4096, intOpt(p, "height", 960)));
        boolean cands = !(p != null && Boolean.FALSE.equals(p.get("includeCandidates")));
        boolean save = !(p != null && Boolean.FALSE.equals(p.get("save")));
        // view="threeview" (aka 3view/three) → orthographic FRONT/SIDE/TOP projections, plan vs
        // actual; anything else → the default time-series dashboard.
        String view = (p != null && p.get("view") instanceof String v) ? v.toLowerCase() : "dashboard";
        boolean threeView = view.contains("three") || view.contains("3view") || view.equals("3");
        PathSession s = r.snapshot();
        PathChartRenderer.Opts opts = new PathChartRenderer.Opts(w, h, 0, cands);
        var img = threeView ? PathChartRenderer.renderThreeView(s, opts) : PathChartRenderer.render(s, opts);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("view", threeView ? "threeview" : "dashboard");
        out.put("outcome", s.outcome() == null ? "RUNNING" : s.outcome().toString());
        out.put("plans", s.plannedRoutes().size());
        out.put("candidates", s.candidates().size());
        out.put("samples", s.trajectory().size());
        if (save) {
            try { out.putAll(PathChartWriter.write(img, p != null && p.get("name") instanceof String n ? n : null)); }
            catch (Exception e) { return Map.of("ok", false, "error", "write failed: " + e); }
        } else {
            out.put("width", img.getWidth());
            out.put("height", img.getHeight());
        }
        return out;
    }

    private static int intOpt(Map<String, Object> p, String k, int def) {
        return (p != null && p.get(k) instanceof Number n) ? n.intValue() : def;
    }
}
