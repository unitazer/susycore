use std::sync::{LazyLock, Mutex};

use color::{AlphaColor, Hsl, Srgb};
use jni::{EnvUnowned, objects::JClass};
use rapier3d::{
  math::Vec3,
  pipeline::{
    DebugRenderBackend, DebugRenderMode, DebugRenderObject, DebugRenderPipeline, DebugRenderStyle,
  },
};

use crate::{gl, glsmit::ensure_gl_loaded};
use crate::{gl::types::GLuint, scene::Scene};

static PIPELINE: LazyLock<Mutex<DebugRenderPipeline>> = LazyLock::new(|| {
  Mutex::new(DebugRenderPipeline::new(
    DebugRenderStyle::default(),
    DebugRenderMode::COLLIDER_SHAPES
      | DebugRenderMode::RIGID_BODY_AXES
      | DebugRenderMode::IMPULSE_JOINTS
      | DebugRenderMode::SOLVER_CONTACTS, // | DebugRenderMode::COLLIDER_AABBS
  ))
});
static BACKEND: LazyLock<Mutex<GlBackend>> = LazyLock::new(|| Mutex::new(GlBackend::new()));

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_common_Native_frametest(
  _env: EnvUnowned,
  _class: JClass,
) {
  ensure_gl_loaded();

  let mut pipeline = PIPELINE.lock().expect("expected the mutex to work");
  let mut backend = BACKEND.lock().expect("expected the mutex to work");

  Scene::with_scenes(|scenes| {
    for scene in scenes.iter() {
      backend.stuff.clear();
      pipeline.render(
        &mut *backend,
        &scene.rigid_body_set,
        &scene.collider_set,
        &scene.impulse_joint_set,
        &scene.multibody_joint_set,
        &scene.narrow_phase,
      );
      backend.render();
    }
  });
}

struct GlBackend {
  pub stuff: Vec<(Vec3, Vec3, [f32; 4])>,
  vertices: Vec<f32>,
  colors: Vec<f32>,
  vbo_vert: GLuint,
  vbo_col: GLuint,
  ready: bool,
}

impl GlBackend {
  fn new() -> Self {
    Self {
      stuff: Vec::new(),
      vertices: Vec::new(),
      colors: Vec::new(),
      vbo_vert: 0,
      vbo_col: 0,
      ready: false,
    }
  }

  fn render(&mut self) {
    if self.stuff.is_empty() {
      return;
    }
    self.vertices.clear();
    self.colors.clear();
    for (from, to, color) in &self.stuff {
      self
        .vertices
        .extend([from.x, from.y, from.z, to.x, to.y, to.z]);
      let mut hsla = *color;
      hsla[1] *= 255.0;
      hsla[2] *= 255.0;
      let hsl: AlphaColor<Hsl> = AlphaColor::new(hsla);
      let rgb: AlphaColor<Srgb> = hsl.convert();
      let rgba = rgb.components;
      self.colors.extend([
        rgba[0], rgba[1], rgba[2], rgba[3], rgba[0], rgba[1], rgba[2], rgba[3],
      ]);
    }
    unsafe {
      if !self.ready {
        gl::GenBuffers(1, &mut self.vbo_vert);
        gl::GenBuffers(1, &mut self.vbo_col);
        self.ready = true;
      }
      let num_verts = self.vertices.len() as gl::types::GLsizei / 3;

      gl::BindBuffer(gl::ARRAY_BUFFER, self.vbo_vert);
      gl::BufferData(
        gl::ARRAY_BUFFER,
        (self.vertices.len() * std::mem::size_of::<f32>()) as gl::types::GLsizeiptr,
        self.vertices.as_ptr() as *const _,
        gl::STREAM_DRAW,
      );

      gl::BindBuffer(gl::ARRAY_BUFFER, self.vbo_col);
      gl::BufferData(
        gl::ARRAY_BUFFER,
        (self.colors.len() * std::mem::size_of::<f32>()) as gl::types::GLsizeiptr,
        self.colors.as_ptr() as *const _,
        gl::STREAM_DRAW,
      );

      gl::Disable(gl::LIGHTING);
      gl::Disable(gl::DEPTH_TEST);

      gl::EnableClientState(gl::VERTEX_ARRAY);
      gl::EnableClientState(gl::COLOR_ARRAY);

      gl::BindBuffer(gl::ARRAY_BUFFER, self.vbo_vert);
      gl::VertexPointer(3, gl::FLOAT, 0, std::ptr::null());

      gl::BindBuffer(gl::ARRAY_BUFFER, self.vbo_col);
      gl::ColorPointer(4, gl::FLOAT, 0, std::ptr::null());

      gl::DrawArrays(gl::LINES, 0, num_verts);

      gl::DisableClientState(gl::COLOR_ARRAY);
      gl::DisableClientState(gl::VERTEX_ARRAY);

      gl::BindBuffer(gl::ARRAY_BUFFER, 0);

      gl::Enable(gl::DEPTH_TEST);
      gl::Enable(gl::LIGHTING);
    }
  }
}

impl DebugRenderBackend for GlBackend {
  fn draw_line(
    &mut self,
    _object: DebugRenderObject,
    from: rapier3d::prelude::Vector,
    to: rapier3d::prelude::Vector,
    color: rapier3d::prelude::DebugColor,
  ) {
    self.stuff.push((from, to, color));
  }
}
