pub mod block_collisions;
pub mod chunklet;
pub mod logger;
pub mod octree;
pub mod scene;

use std::time::Instant;

use jni::errors::{Error, ThrowRuntimeExAndDefault};
use jni::{EnvUnowned, objects::JClass};

use self::logger::SusycoreJavaLogger;

pub type Real = rapier3d::math::Real;

fn goog() {
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
pub extern "system" fn Java_supersymmetry_common_Native_goog(_env: EnvUnowned, _class: JClass) {
  goog();
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
// #[cfg(target_os = "linux")]
// mod gl_current {
//
//   use std::ffi::{CStr, c_void};
//
//   #[link(name = "GL")]
//   unsafe extern "system" {
//     // fn glXGetCurrentContext() -> *mut c_void;
//     // fn glXGetCurrentDisplay() -> *mut c_void;
//     fn glXGetProcAddress(procname: *const u8) -> *const c_void;
//     // fn glxGetProcAddressARB(procname:*const u8) -> *const c_void;
//   }
//   fn glx_load(name: &CStr) -> *const c_void {
//     unsafe { glXGetProcAddress(name.as_ptr() as *const u8) }
//   }
//   pub unsafe fn make_glow() -> glow::Context {
//     unsafe { glow::Context::from_loader_function_cstr(glx_load) }
//   }
// }
