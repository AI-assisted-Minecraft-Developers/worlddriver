// Prelude loaded into every mc.script.eval scope. Defines the global console,
// javaClassName, DriverApiError and Driver.invoke; every Driver.<group>.<verb>
// comes from the generated wrappers loaded after it, one per method, taking that
// method's own params. Nothing here reshapes a method's params: a shape that
// needs sugar is a contract to fix.

var __log = [];
var __result = null,
    __error = null;

function __fmt(v) {
    try {
        return typeof v === "string" ? v : JSON.stringify(v);
    } catch (_) {
        return String(v);
    }
}

var console = {
    log: function (m) {
        __log.push(__fmt(m));
    },
    error: function (m) {
        __log.push("[err] " + __fmt(m));
    },
};

// The Java class name of a wrapped Java object, e.g. "java.util.ArrayList"; null for
// null/undefined and for plain JS values. Scripts cannot call obj.getClass() in this
// Rhino: it never exposes methods only java.lang.Object declares (see ScriptJava).
// A value typeof calls a string, number or boolean is a JS value here even when Java
// produced it (a List element, a method returning long): it behaves as one and cannot
// be told apart from a literal, so it is null too. A Minecraft class's name depends on
// the loader's runtime mappings (net.minecraft.class_1657 in a Fabric production game).
function javaClassName(v) {
    if (v === null || v === undefined) return null;
    var t = typeof v;
    if (t === "string" || t === "number" || t === "boolean") return null;
    var name = __java.className(v);
    return name === null ? null : String(name);
}

var Driver = {};

// The error every Driver call throws: code, issues and execution as the API reported them,
// message for a person. `e.code === 'invalid_params'` is how a script tells a bad request
// from anything else; the message is not parsed.
function DriverApiError(error) {
    this.name = "DriverApiError";
    this.message = String(error.message);
    this.code = String(error.code);
    this.issues = error.issues || [];
    this.execution = String(error.execution);
}
DriverApiError.prototype = Object.create(Error.prototype);
DriverApiError.prototype.constructor = DriverApiError;

// The one generic call. Omitted params are sent as none, never as {} made up here: a method
// that takes nothing accepts that, and null, false or an array are still reported as what they are.
Driver.invoke = function (method, params) {
    var reply = JSON.parse(String(__api.invokeJson(method, params === undefined ? null : JSON.stringify(params))));
    if (reply.error) throw new DriverApiError(reply.error);
    return reply.result;
};
