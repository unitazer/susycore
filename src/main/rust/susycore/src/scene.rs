use std::mem;
use std::sync::{Arc, LazyLock, Mutex};

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JDoubleArray, JFloatArray, JIntArray};
use jni::sys::{jdouble, jint, jlong};
use rapier3d::math::Vec3;
use rapier3d::parry::query::{DefaultQueryDispatcher, QueryDispatcher};
use rapier3d::prelude::*;

use crate::JResult;
use crate::chunklet::{Chunklet, TerrainData};
use crate::dispatcher::ChunkletDispatcher;

//dimension specific information, root structure for the physics simulation
pub struct Scene {
  pub pipeline: PhysicsPipeline,
  pub rigid_body_set: RigidBodySet,
  pub collider_set: ColliderSet,
  pub island_manager: IslandManager,
  pub broad_phase: DefaultBroadPhase,
  pub narrow_phase: NarrowPhase,
  pub impulse_joint_set: ImpulseJointSet,
  pub multibody_joint_set: MultibodyJointSet,
  pub ccd_solver: CCDSolver,
  pub integration: IntegrationParameters,
  pub terrain: TerrainData,
  pub gravity: Vec3,
}
static SCENES: LazyLock<Mutex<Vec<Scene>>> = LazyLock::new(|| Mutex::new(Vec::new()));
impl Scene {
  pub fn with_scenes<F, R>(f: F) -> R
  where
    F: FnOnce(&mut Vec<Scene>) -> R,
  {
    let mut v = SCENES.lock().unwrap();
    f(&mut *v)
  }
  pub fn with_scene<F, R>(i: usize, f: F) -> Option<R>
  where
    F: FnOnce(&Scene) -> R,
  {
    let v = SCENES.lock().unwrap();
    v.get(i).map(|s| f(s))
  }
  pub fn with_scene_mut<F, R>(i: usize, f: F) -> Option<R>
  where
    F: FnOnce(&mut Scene) -> R,
  {
    let mut v = SCENES.lock().unwrap();
    v.get_mut(i).map(|s| f(s))
  }
  pub fn add_chunklet(&mut self, x: i32, y: u8, z: i32, c: Chunklet) {
    if let (_, Some(old_handle)) = self.terrain.remove(x, y, z) {
      self.collider_set.remove(
        old_handle,
        &mut self.island_manager,
        &mut self.rigid_body_set,
        true,
      );
    }
    let arc = Arc::new(c);
    let shared = SharedShape(arc.clone() as Arc<dyn Shape>);
    let handle = self.collider_set.insert(
      ColliderBuilder::new(shared)
        .translation(Vec3::new((x * 16) as f32, (y * 16) as f32, (z * 16) as f32))
        .build(),
    );
    self.terrain.put(x, y, z, arc);
    self.terrain.put_collider(x, y, z, handle);
  }

  pub fn initialize_scene(dim: usize, gravity: Vec3) {
    Self::with_scenes(|x| {
      if dim == x.len() {
        x.push(Self::new(gravity));
      } else {
        panic!("?????");
      }
    });
  }
  fn new(gravity: Vec3) -> Self {
    let rigid_body_set = RigidBodySet::new();
    let collider_set = ColliderSet::new();
    let integration = IntegrationParameters {
      dt: 1. / 20.,
      min_ccd_dt: 1. / 100.,
      normalized_allowed_linear_error: 0.0025,
      normalized_max_corrective_velocity: 50.0,
      normalized_prediction_distance: 0.005,
      num_solver_iterations: 5,
      max_ccd_substeps: 5,
      friction_model: FrictionModel::Simplified,
      ..Default::default()
    };
    let pipeline = PhysicsPipeline::new();
    let island_manager = IslandManager::new();
    let broad_phase = DefaultBroadPhase::new();
    let narrow_phase =
      NarrowPhase::with_query_dispatcher(ChunkletDispatcher.chain(DefaultQueryDispatcher));
    let impulse_joint_set = ImpulseJointSet::new();
    let multibody_joint_set = MultibodyJointSet::new();
    let ccd_solver = CCDSolver::new();
    let terrain = TerrainData::new();
    Self {
      broad_phase,
      pipeline,
      rigid_body_set,
      collider_set,
      island_manager,
      narrow_phase,
      impulse_joint_set,
      multibody_joint_set,
      ccd_solver,
      integration,
      gravity,
      terrain,
    }
  }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_getEntityPose(
  mut env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  handle: jlong,
  arr: JDoubleArray,
) {
  debug_assert!(world_id >= 0);
  let handle: ColliderHandle = unsafe { mem::transmute(handle) };
  let mut buf = [0.0; 7];
  Scene::with_scene(world_id as usize, |x| {
    let collider = x.collider_set.get(handle);
    if let Some(collider) = collider {
      collider
        .position()
        .translation
        .write_to_slice(&mut buf[0..3]);
      collider.position().rotation.write_to_slice(&mut buf[3..7]);
    }
  });
  env
    .with_env(|env| -> JResult<()> {
      if arr.is_null() {
        panic!("null cache array");
      }
      let buf = buf.map(|x| x as f64);
      arr.set_region(env, 0, &buf)?;
      Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>();
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_addEntity(
  mut env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  shape_type: jint,
  restitution: jdouble,
  friction: jdouble,
  x: jdouble,
  y: jdouble,
  z: jdouble,
  data: JFloatArray,
  _indicies: JIntArray,
) -> jlong {
  debug_assert!(world_id >= 0);
  assert!(shape_type >= 0 && shape_type <= ShapeType::Custom as i32);
  let shape_type: ShapeType = unsafe { mem::transmute(shape_type as u8) };

  let data = env
    .with_env(|env| -> Result<Vec<f32>, jni::errors::Error> {
      if data.is_null() {
        panic!("float array in addEntity is null");
      }
      let len = data.len(env)?;
      let mut v = Vec::with_capacity(len);
      v.resize(len, 0.0);
      data.get_region(env, 0, &mut v)?;
      Ok(v)
    })
    .resolve::<jni::errors::ThrowRuntimeExAndDefault>();
  let shape = match shape_type {
    ShapeType::Ball => SharedShape::ball(data[0]),
    ShapeType::Cuboid => SharedShape::cuboid(data[0], data[1], data[2]),
    ShapeType::Capsule => todo!(),
    ShapeType::Segment => todo!(),
    ShapeType::Triangle => todo!(),
    ShapeType::Voxels => todo!(),
    ShapeType::TriMesh => todo!(),
    ShapeType::Polyline => todo!(),
    ShapeType::HalfSpace => todo!(),
    ShapeType::HeightField => todo!(),
    ShapeType::Compound => todo!(),
    ShapeType::ConvexPolyhedron => todo!(),
    ShapeType::Cylinder => todo!(),
    ShapeType::Cone => todo!(),
    ShapeType::RoundCuboid => todo!(),
    ShapeType::RoundTriangle => todo!(),
    ShapeType::RoundCylinder => todo!(),
    ShapeType::RoundCone => todo!(),
    ShapeType::RoundConvexPolyhedron => todo!(),
    ShapeType::Custom => todo!(),
  };
  let collider = ColliderBuilder::new(shape)
    .restitution(restitution as Real)
    .friction(friction as Real)
    .build();
  let rb = RigidBodyBuilder::dynamic().translation(Vec3::new(x as Real, y as Real, z as Real));
  let handle = Scene::with_scene_mut(world_id as usize, |x| {
    let rb_handle = x.rigid_body_set.insert(rb);
    x.collider_set
      .insert_with_parent(collider, rb_handle, &mut x.rigid_body_set)
  });
  unsafe { mem::transmute(handle.expect("invalid world id")) }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_addForceDebug(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  handle: jlong,
  fx: jdouble,
  fy: jdouble,
  fz: jdouble,
) {
  let handle: ColliderHandle = unsafe { mem::transmute(handle) };
  Scene::with_scene_mut(world_id as usize, |x| {
    let collider = x.collider_set.get(handle);
    if let Some(collider) = collider {
      if let Some(parent) = collider.parent() {
        if let Some(rb) = x.rigid_body_set.get_mut(parent) {
          rb.apply_impulse(Vec3::new(fx as Real, fy as Real, fz as Real) * 1000., true);
        }
      }
    }
  });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_step(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
) {
  Scene::with_scene_mut(world_id as usize, |x| {
    x.pipeline.step(
      x.gravity,
      &x.integration,
      &mut x.island_manager,
      &mut x.broad_phase,
      &mut x.narrow_phase,
      &mut x.rigid_body_set,
      &mut x.collider_set,
      &mut x.impulse_joint_set,
      &mut x.multibody_joint_set,
      &mut x.ccd_solver,
      &(),
      &(),
    );
  });
}
