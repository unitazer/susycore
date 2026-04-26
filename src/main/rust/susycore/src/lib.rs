pub mod logger;

use jni::errors::{Error, ThrowRuntimeExAndDefault};
use jni::{EnvUnowned, objects::JClass};

use self::logger::SusycoreJavaLogger;

fn goog() {
  log::info!("logger goog...");
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
    .resolve::<ThrowRuntimeExAndDefault>()
}
