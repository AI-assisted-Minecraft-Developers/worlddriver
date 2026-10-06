package net.magicterra.worlddriver.api.methods;

import java.util.Optional;

import net.magicterra.worlddriver.api.contract.ApiField;
import net.magicterra.worlddriver.api.contract.ApiType;
import net.magicterra.worlddriver.api.contract.ApiTypes;
import net.magicterra.worlddriver.api.contract.JsonValue;
import net.magicterra.worlddriver.api.contract.MethodSpec;
import net.magicterra.worlddriver.api.contract.UnionType;
import net.magicterra.worlddriver.api.methods.ActionContract.ActionSnapshot;

/**
 * {@code mc.wait.result}: read, without waiting, either an action by its {@code actionId} or a
 * background wait by its {@code waitId}. The two ids are different things and are never mixed.
 */
public final class WaitResultContract {
    private WaitResultContract() {}

    public static final String METHOD = "mc.wait.result";

    public sealed interface Query permits ActionQuery, WaitQuery {}

    /** Reading an action never consumes it; it is kept until it ages out. */
    public record ActionQuery(String actionId) implements Query {}

    /** {@code consume}: drop the stored result once it is read. */
    public record WaitQuery(String waitId, boolean consume) implements Query {}

    public sealed interface Reply permits ActionReply, WaitReply {}

    public record ActionReply(ActionSnapshot<Object> action) implements Reply {}

    /**
     * Once {@code pending} is false, exactly one of {@code result} (the wait's {@code {outcome:'done', …}}
     * reply) and {@code error} (the ApiError that ended it) is present.
     */
    public record WaitReply(String waitId, boolean pending, Optional<JsonValue> result, Optional<JsonValue> error)
            implements Reply {}

    public static final UnionType<Query> PARAMS = ApiTypes.taggedUnion("WaitResultParams", "kind",
            ApiTypes.variant("action", ActionQuery.class, ApiTypes.record(null,
                    ApiField.field("actionId", ActionContract.ACTION_ID).required().forGetter(ActionQuery::actionId),
                    ActionQuery::new)),
            ApiTypes.variant("wait", WaitQuery.class, ApiTypes.record(null,
                    ApiField.field("waitId", ApiTypes.string(1, 128)).required().forGetter(WaitQuery::waitId),
                    ApiField.field("consume", ApiTypes.bool()).defaulted(true).forGetter(WaitQuery::consume),
                    WaitQuery::new)));

    public static final ApiType<ActionSnapshot<Object>> ANY_ACTION = ActionContract.snapshot("Any", ActionResults.UNION);

    public static final UnionType<Reply> REPLY = ApiTypes.taggedUnion("WaitResultReply", "kind",
            ApiTypes.variant("action", ActionReply.class, ApiTypes.record(null,
                    ApiField.field("action", ANY_ACTION).required().forGetter(ActionReply::action),
                    ActionReply::new)),
            ApiTypes.variant("wait", WaitReply.class, ApiTypes.record(null,
                    ApiField.field("waitId", ApiTypes.string(1, 128)).required().forGetter(WaitReply::waitId),
                    ApiField.field("pending", ApiTypes.bool()).required().forGetter(WaitReply::pending),
                    ApiField.field("result", ApiTypes.jsonObject()).optional().forGetter(WaitReply::result),
                    ApiField.field("error", ApiTypes.jsonObject()).optional().forGetter(WaitReply::error),
                    WaitReply::new)));

    public static final MethodSpec<Query, Reply> SPEC = MethodSpec.of(METHOD, PARAMS, REPLY,
            "Read an action ({kind:'action', actionId}) or a background wait ({kind:'wait', waitId, consume?}) "
                    + "now, without waiting. An action is kept 10 minutes or 128 endings after it ends; an unknown "
                    + "or expired actionId is action_not_found.");
}
