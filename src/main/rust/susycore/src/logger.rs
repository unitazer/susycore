use std::sync::Mutex;

use jni::vm::JavaVM;
use jni::{JValue, jni_sig, jni_str};
use log::Log;
use once_cell::sync::Lazy;

pub struct SusycoreJavaLogger;

pub static JVM: Lazy<Mutex<Option<JavaVM>>> = Lazy::new(|| Mutex::new(None));
impl Log for SusycoreJavaLogger {
  fn enabled(&self, _metadata: &log::Metadata) -> bool {
    true
  }

  fn log(&self, record: &log::Record) {
    if self.enabled(record.metadata()) {
      let jvm_lock = JVM.lock().unwrap();
      if let Some(jvm) = jvm_lock.as_ref() {
        jvm
          .attach_current_thread(|env| -> Result<(), jni::errors::Error> {
            let level = match record.level() {
              log::Level::Error => 1,
              log::Level::Warn => 2,
              log::Level::Info => 3,
              log::Level::Debug => 4,
              log::Level::Trace => 5,
            };
            let class = env
              .find_class(jni_str!("supersymmetry/common/Native"))
              .unwrap();
            let str = env.new_string(format!("{}", record.args())).unwrap();
            env
              .call_static_method(
                class,
                jni_str!("log"),
                jni_sig!(sig = (arg1:jint,arg2:JString)),
                &[JValue::from(level), JValue::Object(&str)],
              )
              .unwrap();

            Ok(())
          })
          .unwrap();
      }
    }
  }

  fn flush(&self) {
    // todo!()
  }
}
