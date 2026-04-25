use jni::{EnvUnowned, objects::JClass};

fn logger_setup() {
  env_logger::builder()
    .filter_level(log::LevelFilter::Trace)
    .init();
}
fn goog() {
  log::info!("goog...");
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_common_SusyCoreNative_goog(
  _env: EnvUnowned,
  _class: JClass,
) {
  goog();
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_common_SusyCoreNative_init(
  _env: EnvUnowned,
  _class: JClass,
) {
  logger_setup();
}
