//! Small JNI boundary. Networking is owned by remote session workers; inspect
//! is a bounded background operation invoked by the Java executor.
use crate::remote;
use jni::{
    JNIEnv, JavaVM,
    objects::{GlobalRef, JClass, JObject, JString},
    sys::{jboolean, jlong, jstring},
};
use std::sync::{Mutex, OnceLock};
use zeroize::Zeroizing;

struct AndroidContext {
    _vm: JavaVM,
    _application: GlobalRef,
}
static ANDROID_CONTEXT: OnceLock<AndroidContext> = OnceLock::new();
static INITIALIZE: Mutex<()> = Mutex::new(());

fn bounded_string(
    env: &mut JNIEnv<'_>,
    value: &JString<'_>,
    limit: usize,
) -> jni::errors::Result<Option<Zeroizing<String>>> {
    if value.is_null() {
        return Ok(None);
    }
    let length = env.call_method(value, "length", "()I", &[])?.i()?;
    if length < 0 || length as usize > limit {
        return Ok(None);
    }
    let value = Zeroizing::new(String::from(env.get_string(value)?));
    Ok((value.len() <= limit).then_some(value))
}
fn fail(env: &mut JNIEnv<'_>, message: &str) {
    if !env.exception_check().unwrap_or(true) {
        let _ = env.throw_new("java/lang/IllegalStateException", message);
    }
}
fn result_string(env: &mut JNIEnv<'_>, result: remote::Result<String>) -> jstring {
    match result {
        Ok(value) => match env.new_string(value) {
            Ok(value) => value.into_raw(),
            Err(_) => {
                fail(env, "Cannot return remote result");
                std::ptr::null_mut()
            }
        },
        Err(error) => {
            fail(env, &error.to_string());
            std::ptr::null_mut()
        }
    }
}
fn read(env: &mut JNIEnv<'_>, value: &JString<'_>, limit: usize) -> Option<Zeroizing<String>> {
    match bounded_string(env, value, limit) {
        Ok(Some(value)) => Some(value),
        _ => {
            fail(env, "Invalid or oversized remote argument");
            None
        }
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_RemoteNative_initialize(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    context: JObject<'_>,
) {
    let _guard = INITIALIZE
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner);
    if ANDROID_CONTEXT.get().is_some() {
        return;
    }
    let initialize = || -> jni::errors::Result<AndroidContext> {
        let application = env
            .call_method(
                context,
                "getApplicationContext",
                "()Landroid/content/Context;",
                &[],
            )?
            .l()?;
        if application.is_null() {
            return Err(jni::errors::Error::NullPtr("application context"));
        }
        let application = env.new_global_ref(application)?;
        let vm = env.get_java_vm()?;
        // SAFETY: JavaVM is process-lived and application is a retained global
        // ref. INITIALIZE plus OnceLock serialize this sole initialization.
        // SurfaceView GPUI uses its own activity global and does not initialize
        // ndk-context; no NativeActivity entry point is used by this application.
        unsafe {
            ndk_context::initialize_android_context(
                vm.get_java_vm_pointer().cast(),
                application.as_obj().as_raw().cast(),
            );
        }
        Ok(AndroidContext {
            _vm: vm,
            _application: application,
        })
    };
    match initialize() {
        Ok(context) => {
            let _ = ANDROID_CONTEXT.set(context);
        }
        Err(_) => fail(&mut env, "Could not initialize Android application context"),
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_RemoteNative_inspect(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config: JString<'_>,
) -> jstring {
    let Some(config) = read(&mut env, &config, 8192) else {
        return std::ptr::null_mut();
    };
    result_string(&mut env, remote::inspect(&config))
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_RemoteNative_connect(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    config: JString<'_>,
    credentials: JString<'_>,
) -> jlong {
    let Some(config) = read(&mut env, &config, 8192) else {
        return 0;
    };
    let Some(credentials) = read(&mut env, &credentials, remote::MAX_CREDENTIAL_JSON) else {
        return 0;
    };
    match remote::connect_credentials(&config, &credentials) {
        Ok(handle) => handle as jlong,
        Err(error) => {
            fail(&mut env, &error.to_string());
            0
        }
    }
}

#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_RemoteNative_poll(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    since: jlong,
    mirror: jboolean,
) -> jstring {
    result_string(&mut env, remote::poll(handle as u64, since as u64, mirror != 0))
}
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_RemoteNative_input(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    pane: JString<'_>,
    command: JString<'_>,
) -> jstring {
    let Some(pane) = read(&mut env, &pane, 1024) else {
        return std::ptr::null_mut();
    };
    let Some(command) = read(&mut env, &command, crate::model::MAX_COMMAND_JSON) else {
        return std::ptr::null_mut();
    };
    result_string(&mut env, remote::input(handle as u64, &pane, &command))
}
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_RemoteNative_focus(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    pane: JString<'_>,
) -> jstring {
    let Some(pane) = read(&mut env, &pane, 1024) else {
        return std::ptr::null_mut();
    };
    result_string(&mut env, remote::focus(handle as u64, &pane))
}
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_RemoteNative_focusTab(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    tab: JString<'_>,
) -> jstring {
    let Some(tab) = read(&mut env, &tab, 1024) else {
        return std::ptr::null_mut();
    };
    result_string(&mut env, remote::focus_tab(handle as u64, &tab))
}
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_RemoteNative_focusWorkspace(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    workspace: JString<'_>,
) -> jstring {
    let Some(workspace) = read(&mut env, &workspace, 1024) else {
        return std::ptr::null_mut();
    };
    result_string(&mut env, remote::focus_workspace(handle as u64, &workspace))
}
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_RemoteNative_sessionAction(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    action: JString<'_>,
) -> jstring {
    let Some(action) = read(&mut env, &action, 64) else {
        return std::ptr::null_mut();
    };
    result_string(&mut env, remote::session_action(handle as u64, &action))
}
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_RemoteNative_scrollTo(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
    offset: jlong,
) -> jstring {
    result_string(&mut env, remote::scroll_to(handle as u64, offset))
}
#[allow(unsafe_code)]
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_herdr_android_RemoteNative_close(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) {
    remote::close(handle as u64);
}
