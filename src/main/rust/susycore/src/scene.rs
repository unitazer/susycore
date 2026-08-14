use std::mem;
use std::sync::{LazyLock, Mutex};

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JDoubleArray, JFloatArray, JIntArray};
use jni::sys::{jdouble, jint, jlong};
use rapier3d::math::Vec3;
use rapier3d::parry::query::{DefaultQueryDispatcher, QueryDispatcher};
use rapier3d::prelude::*;

use crate::chunklet::Chunklet;
use crate::dispatcher::ChunkletDispatcher;
use crate::terrain::TerrainData;
use crate::{IHateJava, JResult};

use rapier3d::na::{Isometry3, Quaternion, Translation3, UnitQuaternion};
//dimension specific information, root structure for the physics simulation
pub struct Scene {
  pub world: PhysicsWorld,
  pub terrain: TerrainData,
  pub gravity: Vec3,
}
//TODO maybe there is a less stupid way of storing these?
static SCENES: LazyLock<Mutex<Vec<Scene>>> = LazyLock::new(|| Mutex::new(Vec::new()));
impl Scene {
  pub fn with_scenes<F, R>(f: F) -> R
  where
    F: FnOnce(&mut Vec<Scene>) -> R,
  {
    let mut v = SCENES.lock().unwrap();
    f(&mut v)
  }
  pub fn with_scene<F, R>(i: usize, f: F) -> Option<R>
  where
    F: FnOnce(&Scene) -> R,
  {
    let v = SCENES.lock().unwrap();
    v.get(i).map(f)
  }
  pub fn with_scene_mut<F, R>(i: usize, f: F) -> Option<R>
  where
    F: FnOnce(&mut Scene) -> R,
  {
    let mut v = SCENES.lock().unwrap();
    v.get_mut(i).map(f)
  }
  pub fn add_chunklet(&mut self, x: i32, y: u8, z: i32, c: Chunklet) {
    self.terrain.remove(
      x,
      y,
      z,
      &mut self.world.colliders,
      &mut self.world.islands,
      &mut self.world.bodies,
    );

    if c.blocks.iter().all(|b| b.is_none()) {
      log::error!("empty chunklet supplied, shouldve been filtered out");
      return;
    }
    self.terrain.put(x, y, z, c, &mut self.world.colliders);
  }

  pub fn initialize_scene(dim: usize, gravity: Vec3) {
    Self::with_scenes(|x| {
      if dim < x.len() {
        x[dim] = Self::new(gravity);
      } else if dim == x.len() {
        x.push(Self::new(gravity));
      } else {
        panic!("dimension out of sequence");
      }
    });
  }
  pub fn destroy_scene(dim: usize) {
    Self::with_scenes(|x| {
      if dim < x.len() {
        x[dim] = Self::new(Vec3::new(0., 0., 0.));
      }
    });
  }
  pub fn reset_all() {
    Self::with_scenes(|x| {
      x.clear();
    });
  }
  fn new(gravity: Vec3) -> Self {
    let world = PhysicsWorld {
      gravity,
      integration_parameters: IntegrationParameters {
        dt: 1. / 20.,
        min_ccd_dt: 1. / 100.,
        normalized_allowed_linear_error: 0.0025,
        normalized_max_corrective_velocity: 50.0,
        normalized_prediction_distance: 0.005,
        num_solver_iterations: 5,
        max_ccd_substeps: 5,
        friction_model: FrictionModel::Simplified,
        contact_clustering: false,
        ..Default::default()
      },
      narrow_phase: NarrowPhase::with_query_dispatcher(
        ChunkletDispatcher.chain(DefaultQueryDispatcher),
      ),
      ..Default::default()
    };
    let terrain = TerrainData::new();
    Self {
      gravity,
      world,
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
  let handle: ColliderHandle = handle.collider_handle();
  let mut buf = [0.0; 10];
  Scene::with_scene(world_id as usize, |x| {
    let collider = x.world.colliders.get(handle);
    if let Some(collider) = collider {
      collider
        .position()
        .translation
        .write_to_slice(&mut buf[0..3]);
      collider.position().rotation.write_to_slice(&mut buf[3..7]);
      if let Some(parent) = collider.parent()
        && let Some(rb) = x.world.bodies.get(parent)
      {
        let vel = rb.linvel();
        buf[7] = vel.x;
        buf[8] = vel.y;
        buf[9] = vel.z;
      }
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
  //TODO dont do this
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
    let rb_handle = x.world.bodies.insert(rb);
    x.world
      .colliders
      .insert_with_parent(collider, rb_handle, &mut x.world.bodies)
  });
  super::jlong_handle(handle.expect("invalid world id"))
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
  let handle: ColliderHandle = handle.collider_handle();
  Scene::with_scene_mut(world_id as usize, |x| {
    let collider = x.world.colliders.get(handle);
    if let Some(collider) = collider
      && let Some(parent) = collider.parent()
      && let Some(rb) = x.world.bodies.get_mut(parent)
    {
      rb.apply_impulse(Vec3::new(fx as Real, fy as Real, fz as Real) * 1000., true);
    }
  });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_setEntityPose(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  handle: jlong,
  x: jdouble,
  y: jdouble,
  z: jdouble,
  qw: jdouble,
  qx: jdouble,
  qy: jdouble,
  qz: jdouble,
  vx: jdouble,
  vy: jdouble,
  vz: jdouble,
) {
  let handle: ColliderHandle = handle.collider_handle();
  Scene::with_scene_mut(world_id as usize, |scene| {
    let collider = scene.world.colliders.get(handle);
    if let Some(collider) = collider
      && let Some(parent) = collider.parent()
      && let Some(rb) = scene.world.bodies.get_mut(parent)
    {
      let position = Isometry3::from_parts(
        Translation3::new(x as Real, y as Real, z as Real),
        UnitQuaternion::new_unchecked(Quaternion::new(
          qw as Real, qx as Real, qy as Real, qz as Real,
        )),
      )
      .into();
      rb.set_position(position, true);
      rb.set_linvel(Vec3::new(vx as Real, vy as Real, vz as Real), true);
    }
  });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_removeEntity(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  handle: jlong,
) {
  let handle: ColliderHandle = handle.collider_handle();
  Scene::with_scene_mut(world_id as usize, |x| {
    x.world
      .colliders
      .remove(handle, &mut x.world.islands, &mut x.world.bodies, true);
  });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_step(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
) {
  Scene::with_scene_mut(world_id as usize, |x| {
    x.world.step_with_events(&(), &());
  });
}
