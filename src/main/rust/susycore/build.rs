use std::env;
use std::fs::File;
use std::path::Path;

use gl_generator::Registry;
use gl_generator::*;

fn main() {
  let dest = env::var("OUT_DIR").unwrap();
  let mut file = File::create(Path::new(&dest).join("gl_bindings.rs")).unwrap();
  Registry::new(Api::Gl, (2, 1), Profile::Compatibility, Fallbacks::All, [])
    .write_bindings(gl_generator::GlobalGenerator, &mut file)
    .unwrap();
}
