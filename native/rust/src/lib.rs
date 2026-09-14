//! The cljrs half of hive-kdenlive's native MLT bridge.
//!
//! A cdylib whose `cljrs_init` interns `hive-kdenlive.native/*` into a running
//! clojurust environment. Each function calls libmlt-7, the C engine Kdenlive
//! itself renders with, so a cljrs process can probe media and render a whole
//! MLT/Kdenlive project in-process, with no `melt` subprocess and no JVM.
//!
//! Why libmlt is dlopened rather than linked: the distribution ships the
//! library but not its headers (libmlt-dev is not installed on the machines
//! this was built for), and resolving it at run time keeps the crate buildable
//! and the failure diagnosable when it is absent. The signatures below are the
//! MLT 7 framework API (mlt_factory.h, mlt_producer.h, mlt_consumer.h,
//! mlt_properties.h, mlt_profile.h), declared by hand.
//!
//! Every entry point answers ONE JSON string, `{"ok": ...}` or
//! `{"error": "<code>", "detail": ...}`, the envelope hive-k8s's native
//! transport uses, so the Clojure side reads one shape and never an errno.

use std::ffi::{CStr, CString, c_void};
use std::os::raw::{c_char, c_int};
use std::path::Path;
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

use cljrs_interop::{FromValue, Registry, wrap_fn0, wrap_fn1, wrap_fn_variadic};
use cljrs_value::Value;
use serde_json::{Map, Value as Json, json};

type Ptr = *mut c_void;

/// MLT_LOG_ERROR in mlt_log.h. Warnings from probing ordinary media are noise
/// on stderr of a process whose stdout is a protocol.
const MLT_LOG_ERROR: c_int = 16;

/// `struct mlt_profile_s` from mlt_profile.h, MLT 7. Read, never allocated
/// here: MLT owns every profile this crate touches.
#[repr(C)]
struct MltProfile {
    description: *mut c_char,
    frame_rate_num: c_int,
    frame_rate_den: c_int,
    width: c_int,
    height: c_int,
    progressive: c_int,
    sample_aspect_num: c_int,
    sample_aspect_den: c_int,
    display_aspect_num: c_int,
    display_aspect_den: c_int,
    colorspace: c_int,
    is_explicit: c_int,
}

/// The symbols of libmlt-7 this bridge calls.
struct Mlt {
    _lib: libloading::Library,
    factory_init: unsafe extern "C" fn(*const c_char) -> Ptr,
    profile_init: unsafe extern "C" fn(*const c_char) -> Ptr,
    profile_close: unsafe extern "C" fn(Ptr),
    profile_from_producer: unsafe extern "C" fn(Ptr, Ptr),
    factory_producer: unsafe extern "C" fn(Ptr, *const c_char, *const c_void) -> Ptr,
    factory_consumer: unsafe extern "C" fn(Ptr, *const c_char, *const c_void) -> Ptr,
    producer_get_length: unsafe extern "C" fn(Ptr) -> i32,
    producer_set_in_and_out: unsafe extern "C" fn(Ptr, i32, i32) -> c_int,
    producer_properties: unsafe extern "C" fn(Ptr) -> Ptr,
    producer_service: unsafe extern "C" fn(Ptr) -> Ptr,
    producer_close: unsafe extern "C" fn(Ptr),
    consumer_properties: unsafe extern "C" fn(Ptr) -> Ptr,
    consumer_connect: unsafe extern "C" fn(Ptr, Ptr) -> c_int,
    consumer_start: unsafe extern "C" fn(Ptr) -> c_int,
    consumer_stop: unsafe extern "C" fn(Ptr) -> c_int,
    consumer_is_stopped: unsafe extern "C" fn(Ptr) -> c_int,
    consumer_close: unsafe extern "C" fn(Ptr),
    properties_get: unsafe extern "C" fn(Ptr, *const c_char) -> *const c_char,
    properties_set: unsafe extern "C" fn(Ptr, *const c_char, *const c_char) -> c_int,
    version_get_string: unsafe extern "C" fn() -> *const c_char,
    log_set_level: unsafe extern "C" fn(c_int),
}

// Loaded once and never unloaded: MLT keeps a process-wide repository and the
// function pointers above outlive any single call.
unsafe impl Send for Mlt {}
unsafe impl Sync for Mlt {}

static LIB: OnceLock<Result<Mlt, String>> = OnceLock::new();

/// MLT's factory, repository and service registry are process-wide and not
/// documented as safe for concurrent construction, so every call that touches
/// them is serialised. A render holds the lock for its whole duration.
static CALLS: Mutex<()> = Mutex::new(());

fn lib_candidates() -> Vec<String> {
    let mut out = Vec::new();
    if let Ok(p) = std::env::var("HIVE_MLT_LIB") {
        out.push(p);
    }
    out.push("libmlt-7.so.7".to_string());
    out.push("libmlt-7.so".to_string());
    out
}

/// Resolve SYMBOL from LIB as a function pointer of type T.
macro_rules! sym {
    ($lib:expr, $name:literal) => {
        *$lib
            .get(concat!($name, "\0").as_bytes())
            .map_err(|e| format!("{}: {e}", $name))?
    };
}

fn open(path: &str) -> Result<Mlt, String> {
    let lib = unsafe { libloading::Library::new(path) }.map_err(|e| format!("{path}: {e}"))?;
    let mlt = unsafe {
        Mlt {
            factory_init: sym!(lib, "mlt_factory_init"),
            profile_init: sym!(lib, "mlt_profile_init"),
            profile_close: sym!(lib, "mlt_profile_close"),
            profile_from_producer: sym!(lib, "mlt_profile_from_producer"),
            factory_producer: sym!(lib, "mlt_factory_producer"),
            factory_consumer: sym!(lib, "mlt_factory_consumer"),
            producer_get_length: sym!(lib, "mlt_producer_get_length"),
            producer_set_in_and_out: sym!(lib, "mlt_producer_set_in_and_out"),
            producer_properties: sym!(lib, "mlt_producer_properties"),
            producer_service: sym!(lib, "mlt_producer_service"),
            producer_close: sym!(lib, "mlt_producer_close"),
            consumer_properties: sym!(lib, "mlt_consumer_properties"),
            consumer_connect: sym!(lib, "mlt_consumer_connect"),
            consumer_start: sym!(lib, "mlt_consumer_start"),
            consumer_stop: sym!(lib, "mlt_consumer_stop"),
            consumer_is_stopped: sym!(lib, "mlt_consumer_is_stopped"),
            consumer_close: sym!(lib, "mlt_consumer_close"),
            properties_get: sym!(lib, "mlt_properties_get"),
            properties_set: sym!(lib, "mlt_properties_set"),
            version_get_string: sym!(lib, "mlt_version_get_string"),
            log_set_level: sym!(lib, "mlt_log_set_level"),
            _lib: lib,
        }
    };
    let repository = unsafe { (mlt.factory_init)(std::ptr::null()) };
    if repository.is_null() {
        return Err(format!("{path}: mlt_factory_init returned NULL (no module repository)"));
    }
    unsafe { (mlt.log_set_level)(MLT_LOG_ERROR) };
    Ok(mlt)
}

fn load() -> &'static Result<Mlt, String> {
    LIB.get_or_init(|| {
        let mut tried = Vec::new();
        for path in lib_candidates() {
            match open(&path) {
                Ok(m) => return Ok(m),
                Err(e) => tried.push(e),
            }
        }
        Err(format!("libmlt-7 not loadable. Tried: {}", tried.join("; ")))
    })
}

fn mlt() -> Result<&'static Mlt, String> {
    load().as_ref().map_err(|e| e.clone())
}

fn ok(body: Json) -> String {
    json!({ "ok": body }).to_string()
}

fn err(code: &str, detail: impl Into<Json>) -> String {
    json!({ "error": code, "detail": detail.into() }).to_string()
}

fn cstr(s: &str) -> Result<CString, String> {
    CString::new(s).map_err(|e| format!("argument holds a NUL byte: {e}"))
}

/// A property of PROPS as an owned string; None when unset.
unsafe fn prop(m: &Mlt, props: Ptr, name: &str) -> Option<String> {
    let key = CString::new(name).ok()?;
    let p = unsafe { (m.properties_get)(props, key.as_ptr()) };
    if p.is_null() {
        None
    } else {
        Some(unsafe { CStr::from_ptr(p) }.to_string_lossy().into_owned())
    }
}

unsafe fn profile_json(profile: Ptr) -> Json {
    let p = unsafe { &*(profile as *const MltProfile) };
    let description = if p.description.is_null() {
        Json::Null
    } else {
        Json::String(unsafe { CStr::from_ptr(p.description) }.to_string_lossy().into_owned())
    };
    json!({
        "description": description,
        "width": p.width,
        "height": p.height,
        "frame_rate_num": p.frame_rate_num,
        "frame_rate_den": p.frame_rate_den,
        "progressive": p.progressive != 0,
        "explicit": p.is_explicit != 0,
    })
}

/// A profile plus a producer for RESOURCE, owned together and closed together.
///
/// Mirrors what `melt` does with an implicit profile: load against the
/// default, and when the resource did not itself fix the profile (a media file
/// rather than an MLT document), adopt the resource's format and load again so
/// the producer is built against the profile it will be rendered with.
struct Loaded<'a> {
    m: &'a Mlt,
    profile: Ptr,
    producer: Ptr,
}

impl<'a> Loaded<'a> {
    fn new(m: &'a Mlt, resource: &str) -> Result<Self, String> {
        let res = cstr(resource)?;
        let profile = unsafe { (m.profile_init)(std::ptr::null()) };
        if profile.is_null() {
            return Err("mlt_profile_init returned NULL".into());
        }
        let load = |profile: Ptr| unsafe {
            (m.factory_producer)(profile, std::ptr::null(), res.as_ptr() as *const c_void)
        };
        let mut producer = load(profile);
        if producer.is_null() {
            unsafe { (m.profile_close)(profile) };
            return Err(format!("no MLT producer could open {resource:?}"));
        }
        let explicit = unsafe { (*(profile as *const MltProfile)).is_explicit } != 0;
        if !explicit {
            unsafe {
                (m.profile_from_producer)(profile, producer);
                (m.producer_close)(producer);
            }
            producer = load(profile);
            if producer.is_null() {
                unsafe { (m.profile_close)(profile) };
                return Err(format!("{resource:?} opened once and not again under its own profile"));
            }
        }
        Ok(Loaded { m, profile, producer })
    }
}

impl Drop for Loaded<'_> {
    fn drop(&mut self) {
        unsafe {
            (self.m.producer_close)(self.producer);
            (self.m.profile_close)(self.profile);
        }
    }
}

fn call_version() -> String {
    match mlt() {
        Ok(m) => {
            let v = unsafe { CStr::from_ptr((m.version_get_string)()) };
            ok(json!({ "mlt": v.to_string_lossy() }))
        }
        Err(e) => err("mlt/unavailable", e),
    }
}

fn call_probe(resource: String) -> String {
    let m = match mlt() {
        Ok(m) => m,
        Err(e) => return err("mlt/unavailable", e),
    };
    let _guard = CALLS.lock().unwrap_or_else(|p| p.into_inner());
    let loaded = match Loaded::new(m, &resource) {
        Ok(l) => l,
        Err(e) => return err("mlt/unreadable", e),
    };
    unsafe {
        let props = (m.producer_properties)(loaded.producer);
        let pick = |k: &str| prop(m, props, k).map(Json::String).unwrap_or(Json::Null);
        ok(json!({
            "resource": resource,
            "service": pick("mlt_service"),
            "length": (m.producer_get_length)(loaded.producer),
            "profile": profile_json(loaded.profile),
            "media": {
                "width": pick("meta.media.width"),
                "height": pick("meta.media.height"),
                "frame_rate_num": pick("meta.media.frame_rate_num"),
                "frame_rate_den": pick("meta.media.frame_rate_den"),
                "streams": pick("meta.media.nb_streams"),
                "video_index": pick("video_index"),
                "audio_index": pick("audio_index"),
            },
        }))
    }
}

/// Consumer properties from an OPTS JSON object. Strings and numbers become
/// MLT property strings; the keys `in`, `out` and `timeout_ms` are the
/// bridge's own and are not forwarded.
fn consumer_opts(opts: &Map<String, Json>) -> Vec<(String, String)> {
    opts.iter()
        .filter(|(k, _)| !matches!(k.as_str(), "in" | "out" | "timeout_ms"))
        .filter_map(|(k, v)| match v {
            Json::String(s) => Some((k.clone(), s.clone())),
            Json::Number(n) => Some((k.clone(), n.to_string())),
            Json::Bool(b) => Some((k.clone(), if *b { "1".into() } else { "0".into() })),
            _ => None,
        })
        .collect()
}

fn call_render(resource: String, target: String, opts_json: String) -> String {
    let m = match mlt() {
        Ok(m) => m,
        Err(e) => return err("mlt/unavailable", e),
    };
    let opts: Map<String, Json> = if opts_json.trim().is_empty() {
        Map::new()
    } else {
        match serde_json::from_str::<Json>(&opts_json) {
            Ok(Json::Object(o)) => o,
            Ok(_) => return err("render/opts-not-an-object", opts_json),
            Err(e) => return err("render/opts-unparseable", e.to_string()),
        }
    };
    let timeout = Duration::from_millis(opts.get("timeout_ms").and_then(Json::as_u64).unwrap_or(600_000));

    let _guard = CALLS.lock().unwrap_or_else(|p| p.into_inner());
    let loaded = match Loaded::new(m, &resource) {
        Ok(l) => l,
        Err(e) => return err("mlt/unreadable", e),
    };
    let length = unsafe { (m.producer_get_length)(loaded.producer) };
    let first = opts.get("in").and_then(Json::as_i64).unwrap_or(0) as i32;
    let last = opts.get("out").and_then(Json::as_i64).map(|v| v as i32).unwrap_or(length - 1);
    if first < 0 || last < first || last >= length {
        return err("render/range", json!({ "in": first, "out": last, "length": length }));
    }
    unsafe { (m.producer_set_in_and_out)(loaded.producer, first, last) };

    let target_c = match cstr(&target) {
        Ok(c) => c,
        Err(e) => return err("render/target", e),
    };
    let avformat = CString::new("avformat").unwrap();
    let consumer = unsafe {
        (m.factory_consumer)(loaded.profile, avformat.as_ptr(), target_c.as_ptr() as *const c_void)
    };
    if consumer.is_null() {
        return err("render/no-consumer", "the avformat consumer is not available in this MLT build");
    }

    let props = unsafe { (m.consumer_properties)(consumer) };
    // real_time -1: render every frame, in order, on one worker thread.
    // terminate_on_pause: stop at the end of the producer instead of idling.
    let mut settings = vec![
        ("real_time".to_string(), "-1".to_string()),
        ("terminate_on_pause".to_string(), "1".to_string()),
    ];
    settings.extend(consumer_opts(&opts));
    for (k, v) in &settings {
        if let (Ok(k), Ok(v)) = (CString::new(k.as_str()), CString::new(v.as_str())) {
            unsafe { (m.properties_set)(props, k.as_ptr(), v.as_ptr()) };
        }
    }

    let started = Instant::now();
    let outcome = unsafe {
        if (m.consumer_connect)(consumer, (m.producer_service)(loaded.producer)) != 0 {
            Err(err("render/connect", "mlt_consumer_connect refused the producer"))
        } else if (m.consumer_start)(consumer) != 0 {
            Err(err("render/start", "mlt_consumer_start failed"))
        } else {
            let mut timed_out = false;
            while (m.consumer_is_stopped)(consumer) == 0 {
                if started.elapsed() > timeout {
                    (m.consumer_stop)(consumer);
                    timed_out = true;
                    break;
                }
                std::thread::sleep(Duration::from_millis(20));
            }
            if timed_out { Err(err("render/timeout", timeout.as_millis() as u64)) } else { Ok(()) }
        }
    };
    // Stop even when the consumer already reports stopped, exactly as melt
    // does. `is_stopped` turns true when the render loop leaves, not when the
    // consumer's threads are joined; stop is what joins them. Closing without
    // it frees a consumer whose encoder thread may still be flushing, and the
    // NEXT render in the same process then dies intermittently (measured:
    // SIGFPE in 2 of 4 runs of native_probe, 0 of 8 with this call).
    unsafe {
        (m.consumer_stop)(consumer);
        (m.consumer_close)(consumer);
    }
    if let Err(e) = outcome {
        return e;
    }

    let bytes = std::fs::metadata(Path::new(&target)).map(|md| md.len()).ok();
    match bytes {
        Some(n) if n > 0 => ok(json!({
            "resource": resource,
            "target": target,
            "frames": last - first + 1,
            "in": first,
            "out": last,
            "bytes": n,
            "elapsed_ms": started.elapsed().as_millis() as u64,
            "profile": unsafe { profile_json(loaded.profile) },
        })),
        _ => err("render/no-output", json!({ "target": target, "bytes": bytes })),
    }
}

/// A positional argument as a string; nil and absent read as "".
fn arg(args: &[Value], i: usize) -> Result<String, String> {
    match args.get(i) {
        None | Some(Value::Nil) => Ok(String::new()),
        Some(v) => String::from_value(v)
            .map_err(|e| format!("argument {i}: expected a string, got {} ({e})", v.type_name())),
    }
}

/// Register `hive-kdenlive.native/*`.
///
/// # Safety
/// `registry` must be a valid, non-null pointer to a `Registry` that outlives
/// this call. The cljrs loader guarantees both.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn cljrs_init(registry: *mut Registry) {
    if registry.is_null() {
        eprintln!("hive-kdenlive.native: cljrs_init received a NULL registry");
        return;
    }
    let reg = unsafe { &mut *registry };
    let ns = "hive-kdenlive.native";

    reg.define_in(ns, "available?", wrap_fn0("available?", || Ok::<bool, String>(mlt().is_ok())));

    reg.define_in(ns, "version", wrap_fn0("version", || Ok::<String, String>(call_version())));

    // (probe resource): a media file, an image, a colour spec like
    // "color:#ff0000", or an MLT/Kdenlive document.
    reg.define_in(
        ns,
        "probe",
        wrap_fn1("probe", |resource: String| Ok::<String, String>(call_probe(resource))),
    );

    // (render resource target) or (render resource target opts-json).
    reg.define_in(
        ns,
        "render",
        wrap_fn_variadic("render", 2, |args: &[Value]| {
            Ok::<String, String>(call_render(arg(args, 0)?, arg(args, 1)?, arg(args, 2)?))
        }),
    );

    // (stdin-line) -> the next line of standard input without its newline, or
    // nil at end of input. clojurust has no reader over stdin, and the sidecar
    // (native_sidecar.cljrs) speaks one EDN request per line on it.
    reg.define_in(
        ns,
        "stdin-line",
        wrap_fn0("stdin-line", || {
            let mut line = String::new();
            match std::io::stdin().read_line(&mut line) {
                Ok(0) => Ok::<Option<String>, String>(None),
                Ok(_) => Ok(Some(line.trim_end_matches(['\n', '\r']).to_string())),
                Err(e) => Err(format!("stdin: {e}")),
            }
        }),
    );

    // The namespace is supplied by this library, not by a source file.
    reg.env().mark_loaded(ns);
}
