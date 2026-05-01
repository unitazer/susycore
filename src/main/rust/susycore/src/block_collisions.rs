use std::collections::HashMap;
use std::hash::Hash;
use std::mem::{self, transmute};
use std::os::raw::c_void;
use std::sync::{Arc, LazyLock, Mutex, RwLock};

use glow::{Context, HasContext};
use itertools::Itertools;
use jni::EnvUnowned;
use jni::objects::{JClass, JDoubleArray, JIntArray, JObjectArray};
use jni::sys::{jdouble, jdoubleArray, jfloat, jfloatArray, jint, jobjectArray};
use log::info;
use ordered_float::OrderedFloat;
use rapier3d::glamx::{Quat, usize};
use rapier3d::math::{Pose, Vec3, Vec3A, Vector3};
use rapier3d::parry::bounding_volume::Aabb;
use rapier3d::parry::shape::Cuboid;
use rapier3d::prelude::{
  ActiveHooks, Collider, ColliderBuilder, ColliderHandle, Compound, MassProperties, RigidBody,
  RigidBodyBuilder, SharedShape,
};

use crate::Real;
use crate::scene::Scene;

pub struct TerrainData {
  //TODO align to 64 bits
  subchunk_map: HashMap<(i32, u8, i32), ColliderHandle>,
}

impl TerrainData {
  //xyz are subchunk coordinates in chunk space
  fn add_subchunk(&mut self, scene: &mut Scene, x: i32, y: i32, z: i32, subchunk: Collider) {
    let mut subchunk = subchunk;
    //a dumb cast is good here because 𝑓₃₂ ⊇ 𝑖₃₂ im pretty sure
    subchunk.set_translation(Vec3::new(
      (x * 16) as Real,
      (y * 16) as Real,
      (z * 16) as Real,
    ));
    let handle = scene.collider_set.insert(subchunk);
    self
      .subchunk_map
      .insert((x, y.try_into().expect("no cubic chunks"), z), handle);
  }
  //TODO this is the dumbest possible implementation, the AABBs should definitely be merged
  fn make_subchunk_collider_from(
    subchunk_data: &[i32; 4096],
    f: impl Fn(i32) -> MinecraftBlockColliderInfo,
  ) -> SharedShape {
    let mut shape_cache = SHAPE_CACHE.lock().expect("rust bug not mine");
    let mut compound_elements = vec![];
    for y in 0..16 {
      for z in 0..16 {
        for x in 0..16 {
          let index = y << 8 | z << 4 | x;
          debug_assert!(index < 4096);
          let block = subchunk_data[index];
          let block = f(block);
          for cbox in block.boxes {
            let (pose, cbox) = shape_cache.from_aabb(cbox);
            let pose = pose.append_translation(Vec3::new(x as Real, y as Real, z as Real));
            compound_elements.push((pose, cbox));
          }
        }
      }
    }
    SharedShape::compound(compound_elements)
  }
}
#[derive(PartialEq)]
pub struct MinecraftBlockColliderInfo {
  friction: Real,
  restitution: Real,
  density: Real,
  boxes: Vec<Aabb>,
}
pub struct ShapeCache(
  pub HashMap<(OrderedFloat<f32>, OrderedFloat<f32>, OrderedFloat<f32>), SharedShape>,
);

static SHAPE_CACHE: LazyLock<Mutex<ShapeCache>> = LazyLock::new(|| Mutex::new(ShapeCache::new()));
static COLLIDERS: RwLock<Vec<MinecraftBlockColliderInfo>> = RwLock::new(Vec::new());
impl MinecraftBlockColliderInfo {
  pub fn new(friction: Real, density: Real, restitution: Real, boxes: Vec<Aabb>) -> Self {
    debug_assert!(boxes.len() >= 1);
    Self {
      friction,
      density,
      restitution,
      boxes,
    }
  }
  pub fn handle(self) -> usize {
    let colliders = &mut *COLLIDERS.write().expect("rust bug not mine");
    let index = colliders.len();
    //TODO fix this
    //this is a very bad thing to do because PartialEq for this is going to be expensive and
    //not vectorized at all
    if let Some(x) = colliders.iter().position(|x| x == &self) {
      return x;
    }
    colliders.push(self);
    index
  }

  //either a cuboid or a compound  shape, not for turning entire chunks into colliders but only a
  //single one
  pub fn into_collider(self) -> Collider {
    if self.boxes.len() == 1 {
      let aabb = &self.boxes[0];
      let cuboid = Cuboid::new(aabb.translated(-aabb.center()).half_extents());
      let shape = SharedShape(Arc::new(cuboid));
      return Self::to_collider(shape, self.friction, self.restitution, self.density);
    } else {
      return Self::to_collider(
        Self::into_compound(self.boxes),
        self.friction,
        self.restitution,
        self.density,
      );
    }
  }

  fn to_collider(shape: SharedShape, friction: Real, restitution: Real, density: Real) -> Collider {
    ColliderBuilder::new(shape)
      .friction(friction)
      .restitution(restitution)
      .density(density)
      .build()
  }
  fn into_compound(boxes: Vec<Aabb>) -> SharedShape {
    let shape_cache = &mut *SHAPE_CACHE.lock().expect("rust bug not mine");
    SharedShape::compound(
      boxes
        .into_iter()
        .map(|x| shape_cache.from_aabb(x))
        .collect(),
    )
  }
}
impl ShapeCache {
  pub fn new() -> Self {
    Self(Default::default())
  }

  fn from_aabb(&mut self, aabb: Aabb) -> (Pose, SharedShape) {
    let pose = Pose::from_parts(aabb.center(), Quat::IDENTITY);
    let half = aabb.translated(-aabb.center()).half_extents();
    let key = (
      OrderedFloat(half.x),
      OrderedFloat(half.y),
      OrderedFloat(half.z),
    );
    if let Some(shape) = self.0.get(&key) {
      return (pose, shape.clone());
    }
    let shape = SharedShape::cuboid(half.x, half.y, half.z);
    self.0.insert(key, shape.clone());
    (pose, shape)
  }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_addChunk(
  mut env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  x: jint,
  z: jint,
  data: JObjectArray,
) {
  let mut buffer: [[i32; 4096]; 16] = [[0; 4096]; 16];
  env
    .with_env(|env| -> Result<(), jni::errors::Error> {
      debug_assert!(data.len(env)? == 16);
      for i in 0..16 {
        let arr: jni::objects::JObject<'_> = data.get_element(env, i)?;
        let arr = JIntArray::cast_local(env, arr)?;
        arr.get_region(env, 0, &mut buffer[i])?;
      }
      Ok(())
    })
    .resolve::<jni::errors::ThrowRuntimeExAndDefault>();
  info!(
    "addChunk: world={}, chunk=({}, {}), data received",
    world_id, x, z
  );
  for i in 0..16 {}
  todo!();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_initialize(
  env: EnvUnowned,
  class: JClass,
  dimension: jint,
  gravity: jfloat,
  _drag: jdouble,
) {
  debug_assert!(dimension >= 0);
  Scene::initialize_scene(dimension as usize, Vec3::new(0., gravity, 0.));
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_addColliderInfo(
  mut env: EnvUnowned,
  class: JClass,
  friction: jdouble,
  restitution: jdouble,
  density: jdouble,
  aabbs: JDoubleArray,
) -> jint {
  let aabbs_data = env
    .with_env(|env| -> Result<Vec<jdouble>, jni::errors::Error> {
      let len = aabbs.len(env)?;
      let mut buffer = vec![0f64; len];
      aabbs.get_region(env, 0, &mut buffer)?;
      Ok(buffer)
    })
    .resolve::<jni::errors::ThrowRuntimeExAndDefault>();
  info!("aabb recovered: {:?}", aabbs_data);

  //TODO a Vec<f64> can be transmuted into Vec<Aabb> if Real = f64
  debug_assert!(aabbs_data.len() % 6 == 0);
  debug_assert!(aabbs_data.capacity() % 6 == 0);
  let mut boxes: Vec<Real> = aabbs_data.into_iter().map(|x| x as Real).collect();

  #[cfg(debug_assertions)]
  let boxes_slow: Vec<Aabb> = boxes
    .chunks(6)
    .map(|x| {
      let from = Vec3::from_slice(&x[0..3]);
      let to = Vec3::from_slice(&x[3..6]);
      Aabb::new(from, to)
    })
    .collect();

  assert!(size_of::<Aabb>() == size_of::<[Real; 6]>());
  let len = boxes.len() / 6;
  let cap = boxes.capacity() / 6;
  let boxes_cast = unsafe { Vec::from_raw_parts(boxes.as_mut_ptr() as *mut Aabb, len, cap) };
  #[cfg(debug_assertions)]
  debug_assert!(boxes_cast == boxes_slow);
  mem::forget(boxes); // prevents a double drop

  let collider = MinecraftBlockColliderInfo::new(
    friction as Real,
    density as Real,
    restitution as Real,
    boxes_cast,
  );
  //try_into because if this overflows it will definitely crash
  collider.handle().try_into().unwrap()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_render_test() {
  let gl = unsafe {
    glow::Context::from_loader_function(|_| transmute(0usize))
  };
  unsafe  {
      gl.blend_color(1.0, 1.0, 0.0, 1.0);
  }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_removeChunk(
  env: EnvUnowned,
  class: JClass,
  dimension: jint,
  x: jint,
  z: jint,
) {
  todo!()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_partialSubchunkUpdate(
  env: EnvUnowned,
  class: JClass,
  dimension: jint,
  chunk_x: jint,
  chunk_z: jint,
  chunk_y: jint,
  x: jint,
  y: jint,
  z: jint,
  new_data: jint,
) {
  debug_assert!(dimension >= 0);
  debug_assert!(x < 16 && x >= 0);
  debug_assert!(y < 16 && y >= 0);
  debug_assert!(z < 16 && z >= 0);
  todo!()
}
