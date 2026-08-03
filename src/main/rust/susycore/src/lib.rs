pub mod block_collisions;
pub mod chunklet;
pub mod dispatcher;
pub mod logger;
pub mod octree;
pub mod rendering;
pub mod scene;
pub mod terrain;

use std::env;
use std::time::Instant;

use jni::errors::{Error, ThrowRuntimeExAndDefault};
use jni::sys::jlong;
use jni::{EnvUnowned, objects::JClass};
use rapier3d::geometry::ColliderHandle;
use rapier3d::na::Vector3;

use self::logger::SusycoreJavaLogger;

pub type IVec3 = Vector3<i32>;
pub type Real = rapier3d::math::Real;
pub type JResult<T> = Result<T, jni::errors::Error>;
#[allow(
  unsafe_op_in_unsafe_fn,
  clippy::all,
  non_camel_case_types,
  non_snake_case,
  non_upper_case_globals,
  unused,
  improper_ctypes,
  dead_code
)]
mod gl {
  include!(concat!(env!("OUT_DIR"), "/gl_bindings.rs"));
}

#[cfg(all(target_os = "linux", target_arch = "x86_64"))]
mod glsmit {
  use std::{
    ffi::{CStr, CString, c_void},
    str::FromStr,
    sync::Once,
  };

  use crate::gl;

  #[link(name = "GL")]
  unsafe extern "C" {
    fn glXGetProcAddress(procname: *const u8) -> *const c_void;
  }
  fn glx_load(name: &CStr) -> *const c_void {
    unsafe { glXGetProcAddress(name.as_ptr() as *const u8) }
  }
  static INIT: Once = Once::new();
  pub fn ensure_gl_loaded() {
    INIT.call_once(|| {
      gl::load_with(|x| glx_load(CString::from_str(x).unwrap().as_c_str()));
    });
  }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_common_Native_goog(_env: EnvUnowned, _class: JClass) {
  let now = Instant::now();
  log::info!("logger goog...");
  log::info!("logger took {:?} to goog...", now.elapsed());
  #[cfg(not(debug_assertions))]
  let build = "release";
  #[cfg(debug_assertions)]
  let build = "debug";
  log::info!(
    "{} {} {} build",
    env!("CARGO_PKG_NAME"),
    env!("CARGO_PKG_VERSION"),
    build
  );
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_common_Native_init(mut env: EnvUnowned, _class: JClass) {
  env
    .with_env(|env| -> Result<(), Error> {
      if let Ok(jvm) = env.get_java_vm() {
        let mut lock = logger::JVM.lock().map_err(|_x| Error::TryLock)?;
        *lock = Some(jvm);
      }

      log::set_logger(&SusycoreJavaLogger)
        .map(|()| {
          log::set_max_level(log::LevelFilter::Info);
        })
        .expect("Failed to initialize logger");

      Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>();
}
pub fn jlong_handle(h: ColliderHandle) -> jlong {
  let (left, right) = h.into_raw_parts();
  ((left as u64) << 32 | right as u64) as jlong
}
pub trait IHateJava {
  fn collider_handle(self) -> ColliderHandle;
}

impl IHateJava for jni::sys::jlong {
  fn collider_handle(self) -> ColliderHandle {
    let v = self as u64;
    ColliderHandle::from_raw_parts((v >> 32) as u32, v as u32)
  }
}
