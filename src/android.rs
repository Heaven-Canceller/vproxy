//! Android JNI adapters over the existing business services and compiler.

mod management;

use jni::objects::{JClass, JString};
use jni::sys::jstring;
use jni::JNIEnv;

fn string_result(
    mut env: JNIEnv,
    operation: impl FnOnce(&mut JNIEnv) -> anyhow::Result<String>,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| operation(&mut env)));
    let value = match result {
        Ok(Ok(value)) => value,
        Ok(Err(error)) => serde_json::json!({"error": error.to_string()}).to_string(),
        Err(_) => serde_json::json!({"error": "Native operation failed"}).to_string(),
    };
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_io_github_heavencanceller_vproxy_NativeCore_parse(
    env: JNIEnv,
    _class: JClass,
    content: JString,
) -> jstring {
    string_result(env, |env| {
        let content: String = env.get_string(&content)?.into();
        Ok(serde_json::to_string(&crate::parser::parse_subscription(
            &content, None,
        ))?)
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_heavencanceller_vproxy_NativeCore_compile(
    env: JNIEnv,
    _class: JClass,
    node_json: JString,
    kernel: JString,
) -> jstring {
    string_result(env, |env| {
        let node_json: String = env.get_string(&node_json)?.into();
        let kernel: String = env.get_string(&kernel)?.into();
        let node = std::ffi::CString::new(node_json)?;
        let kernel = std::ffi::CString::new(kernel)?;
        let options = crate::proxy::MihomoConfigOptions {
            mode: crate::proxy::ProxyMode::None,
            routing_mode: crate::proxy::RoutingMode::Global,
            controller_secret: uuid::Uuid::new_v4().to_string(),
            ..Default::default()
        };
        let options = std::ffi::CString::new(serde_json::to_string(&options)?)?;
        unsafe {
            let ptr =
                crate::ffi::vproxy_compile_config(kernel.as_ptr(), node.as_ptr(), options.as_ptr());
            if ptr.is_null() {
                anyhow::bail!("Configuration compilation failed");
            }
            let value = std::ffi::CStr::from_ptr(ptr).to_string_lossy().into_owned();
            crate::ffi::vproxy_free_string(ptr);
            if kernel.as_bytes() == b"mihomo" {
                let mut config: serde_json::Value = serde_yaml::from_str(&value)?;
                config["secret"] = serde_json::json!(uuid::Uuid::new_v4().to_string());
                Ok(serde_json::to_string_pretty(&config)?)
            } else {
                Ok(value)
            }
        }
    })
}

#[no_mangle]
pub extern "system" fn Java_io_github_heavencanceller_vproxy_NativeCore_request(
    env: JNIEnv,
    _class: JClass,
    root: JString,
    native_dir: JString,
    method: JString,
    path: JString,
    body: JString,
) -> jstring {
    string_result(env, |env| {
        let root: String = env.get_string(&root)?.into();
        let native_dir: String = env.get_string(&native_dir)?.into();
        let method: String = env.get_string(&method)?.into();
        let path: String = env.get_string(&path)?.into();
        let body: String = env.get_string(&body)?.into();
        management::request(&root, &native_dir, &method, &path, &body)
    })
}
