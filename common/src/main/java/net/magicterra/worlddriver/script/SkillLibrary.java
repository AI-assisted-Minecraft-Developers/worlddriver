package net.magicterra.worlddriver.script;

import net.magicterra.worlddriver.api.call.CallContext;
import net.magicterra.worlddriver.protocol.JsonCodec;
import net.magicterra.worlddriver.api.contract.ApiError;
import net.magicterra.worlddriver.api.contract.ApiException;
import net.magicterra.worlddriver.api.contract.ApiIssue;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.DeleteSkill;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.GetSkill;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.ListSkills;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.RunSkill;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.SaveSkill;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.SkillDeleted;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.SkillEntry;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.SkillList;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.SkillOp;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.SkillRan;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.SkillReply;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.SkillSaved;
import net.magicterra.worlddriver.api.methods.script.ScriptContracts.SkillSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.stream.Stream;

/**
 * A persistent skill library (the Voyager pattern): the agent writes a JS skill, it is syntax-checked
 * and saved to disk, then listed, run or deleted by name across sessions. A skill is an ordinary script
 * run by the same {@link ScriptEvaluator} as {@code mc.script.eval}, under the same budget; it reads its
 * call args from an injected {@code SKILL} global. A skill that does not parse is never saved, so the
 * library holds no broken skill.
 */
public final class SkillLibrary {
    private final ScriptEvaluator evaluator;
    private final Path dir;

    public SkillLibrary(ScriptEvaluator evaluator, Path dir) {
        this.evaluator = evaluator;
        this.dir = dir;
    }

    public CompletionStage<SkillReply> handle(SkillOp op, CallContext ctx) {
        return switch (op) {
            case SaveSkill s -> CompletableFuture.completedFuture(save(s));
            case ListSkills l -> CompletableFuture.completedFuture(list());
            case GetSkill g -> CompletableFuture.completedFuture(new SkillSource(g.name(), read(g.name())));
            case RunSkill r -> {
                // On the source's first line, so the lines an error names are the skill's own.
                String full = "var SKILL = " + JsonCodec.encode(r.args().map(a -> a.tree()).orElse(Map.of())) + "; "
                        + read(r.name());
                yield evaluator.run(full, r.timeoutMs(), ctx, "skill-" + r.name())
                        .thenApply(outcome -> new SkillRan(r.name(), outcome));
            }
            case DeleteSkill d -> CompletableFuture.completedFuture(delete(d));
        };
    }

    private SkillReply save(SaveSkill s) {
        String syntaxErr = evaluator.checkSyntax(s.source());
        if (syntaxErr != null) {
            throw ApiException.invalidParam("/source", ApiIssue.Code.INVALID_VALUE, "syntax error: " + syntaxErr);
        }
        try {
            Files.createDirectories(dir);
            Files.writeString(file(s.name()), s.source(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ApiException.of(ApiError.Code.INTERNAL_ERROR, "writing skill " + s.name() + " failed: " + e.getMessage());
        }
        return new SkillSaved(s.name(), s.source().getBytes(StandardCharsets.UTF_8).length);
    }

    private SkillReply list() {
        List<SkillEntry> skills = new ArrayList<>();
        if (!Files.isDirectory(dir)) return new SkillList(skills);
        try (Stream<Path> s = Files.list(dir)) {
            for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".js"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList()) {
                String fn = p.getFileName().toString();
                skills.add(new SkillEntry(fn.substring(0, fn.length() - 3), Files.size(p)));
            }
        } catch (IOException e) {
            throw ApiException.of(ApiError.Code.INTERNAL_ERROR, "listing skills failed: " + e.getMessage());
        }
        return new SkillList(skills);
    }

    private SkillReply delete(DeleteSkill d) {
        try {
            return new SkillDeleted(d.name(), Files.deleteIfExists(file(d.name())));
        } catch (IOException e) {
            throw ApiException.of(ApiError.Code.INTERNAL_ERROR, "deleting skill " + d.name() + " failed: " + e.getMessage());
        }
    }

    private Path file(String name) { return dir.resolve(name + ".js"); }

    /** The saved source of {@code name}; a name that is not saved is invalid_params at /name. */
    private String read(String name) {
        Path f = file(name);
        if (!Files.isRegularFile(f)) throw ApiException.invalidParam("/name", ApiIssue.Code.INVALID_VALUE, "no skill named " + name);
        try {
            return Files.readString(f, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw ApiException.of(ApiError.Code.INTERNAL_ERROR, "reading skill " + name + " failed: " + e.getMessage());
        }
    }
}
