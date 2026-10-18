package net.magicterra.worlddriver.debug;

import net.magicterra.worlddriver.api.schema.ApiError;
import net.magicterra.worlddriver.api.schema.ApiException;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.Chart;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.ChartOutcome;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.ChartParams;
import net.magicterra.worlddriver.api.methods.debug.DebugContracts.ChartView;
import net.magicterra.worlddriver.debug.PathChartRenderer;
import net.magicterra.worlddriver.debug.PathChartWriter;
import net.magicterra.worlddriver.debug.PathDebugRecorder;
import net.magicterra.worlddriver.debug.PathSession;

import java.util.Map;
import java.util.Optional;

/**
 * {@code mc.debug.pathChart}. Reads the current recorder snapshot, renders the chart, writes a PNG, and
 * answers its metadata with a small stats summary. Pure data → image → file; no Minecraft world access, so
 * it runs on the calling worker with no thread hop.
 */
final class PathChartTool {
    private PathChartTool() {}

    private static volatile PathDebugRecorder recorder;
    static void bind(PathDebugRecorder r) { recorder = r; }

    static Chart render(ChartParams p) {
        PathDebugRecorder r = recorder;
        if (r == null) throw ApiException.of(ApiError.Code.CAPABILITY_UNAVAILABLE, "path-debug is not initialised");
        PathSession s = r.snapshot();
        PathChartRenderer.Opts opts = new PathChartRenderer.Opts(p.width(), p.height(), 0, p.includeCandidates());
        var img = p.view() == ChartView.THREE_VIEW ? PathChartRenderer.renderThreeView(s, opts) : PathChartRenderer.render(s, opts);
        ChartOutcome outcome = s.outcome() == null ? ChartOutcome.RUNNING : ChartOutcome.valueOf(s.outcome().name());
        if (!p.save()) {
            return new Chart(p.view(), outcome, s.plannedRoutes().size(), s.candidates().size(), s.trajectory().size(),
                    img.getWidth(), img.getHeight(), Optional.empty(), Optional.empty());
        }
        Map<String, Object> file;
        try {
            file = PathChartWriter.write(img, p.name().orElse(null));
        } catch (Exception e) {
            throw ApiException.of(ApiError.Code.INTERNAL_ERROR, "writing the chart failed: " + e);
        }
        return new Chart(p.view(), outcome, s.plannedRoutes().size(), s.candidates().size(), s.trajectory().size(),
                img.getWidth(), img.getHeight(), Optional.of((String) file.get("path")),
                Optional.of(((Number) file.get("bytes")).longValue()));
    }
}
