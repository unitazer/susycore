use std::collections::HashMap;
use std::hash::Hash;
use std::mem::{self};
use std::sync::{Arc, LazyLock, Mutex, RwLock};

use jni::EnvUnowned;
use jni::objects::{JClass, JDoubleArray, JIntArray, JObjectArray};
use jni::sys::{jdouble, jfloat, jint, jlong};
use log::info;
use ordered_float::OrderedFloat;
use rapier3d::data::Index;
use rapier3d::glamx::{Quat, usize};
use rapier3d::math::{Pose, Vec3};
use rapier3d::parry::bounding_volume::Aabb;
use rapier3d::parry::shape::Cuboid;
use rapier3d::prelude::{Collider, ColliderBuilder, ColliderHandle, RigidBodyBuilder, SharedShape};

use crate::Real;
use crate::chunklet::Chunklet;
use crate::scene::Scene;
//TODO callbacks

pub struct ShapeCache(
  pub HashMap<(OrderedFloat<f32>, OrderedFloat<f32>, OrderedFloat<f32>), SharedShape>,
);

pub static SHAPE_CACHE: LazyLock<Mutex<ShapeCache>> =
  LazyLock::new(|| Mutex::new(ShapeCache::new()));
pub static COLLIDERS: RwLock<Vec<MinecraftBlockColliderInfo>> = RwLock::new(Vec::new());
pub const AIR_HANDLE: BlockColliderInfoHandle = BlockColliderInfoHandle(0);
#[derive(Eq, PartialOrd, Ord, PartialEq, Hash, Copy, Clone)]
pub struct BlockColliderInfoHandle(pub(crate) u32);

#[derive(PartialEq, Clone, Debug)]
pub struct MinecraftBlockColliderInfo {
  pub friction: Real,
  pub restitution: Real,
  pub density: Real,
  pub mass: Real,
  pub boxes: Vec<Aabb>,
}

impl MinecraftBlockColliderInfo {
  pub fn new(friction: Real, density: Real, restitution: Real, boxes: Vec<Aabb>) -> Self {
    debug_assert!(boxes.len() >= 1);
    Self {
      friction,
      density,
      restitution,
      mass: boxes.iter().map(|x| x.volume()).sum(),
      boxes,
    }
  }
  pub fn handle_deref<T>(
    h: BlockColliderInfoHandle,
    f: impl FnOnce(&MinecraftBlockColliderInfo) -> T,
  ) -> Option<T> {
    let colliders = COLLIDERS.read().expect("rust bug not mine");
    colliders
      .get((h.0.overflowing_sub(1).0) as usize)
      .map(|x| f(x))
  }
  pub fn handle(self) -> BlockColliderInfoHandle {
    let colliders = &mut *COLLIDERS.write().expect("rust bug not mine");
    let index = colliders.len();
    //TODO fix this
    //this is a very bad thing to do because PartialEq for this is going to be expensive and
    //not vectorized at all
    if let Some(x) = colliders.iter().position(|x| x == &self) {
      return BlockColliderInfoHandle(x as u32);
    }
    colliders.push(self);
    let out = BlockColliderInfoHandle(index as u32 + 1);
    //im going insane
    debug_assert!(out != AIR_HANDLE);
    out
  }

  //either a cuboid or a compound  shape, not for turning entire chunks into colliders but only a
  //single one
  pub fn into_collider(self) -> Collider {
    if self.boxes.len() == 1 {
      let aabb = &self.boxes[0];
      let cuboid = Cuboid::new(aabb.half_extents());
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

  pub fn from_aabb(&mut self, aabb: Aabb) -> (Pose, SharedShape) {
    let pose = Pose::from_parts(aabb.center(), Quat::IDENTITY);
    let half = aabb.half_extents();
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
  debug_assert!(!buffer.iter().flatten().all(|x| *x == 0));
  info!(
    "addChunk: world={}, chunk=({}, {}), data received",
    world_id, x, z
  );
  let _world = Scene::with_scene_mut(0, |xs| {
    (0..16) //.par_bridge()
      .for_each(|i| {
        if buffer[i].iter().all(|x| *x == 0) {
          info!("buffer {i} is empty");
        } else {
          let b = buffer[i]
            .map(|x| x as u32)
            .map(|x| BlockColliderInfoHandle(x));
          let c = Chunklet::new_with_blockhandle(b);
          xs.add_chunklet(x, i as u8, z, c);
          info!("shape built yay");
        }
      });
  });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_RbInfo(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  handle: jlong,
) {
  let handle = handle as u64;
  let handle = ColliderHandle(unsafe { mem::transmute(handle) });
  log::info!("handle:{handle:?}");
  Scene::with_scene(world_id as usize, |xs| {
    let c = xs.collider_set.get(handle);
    if let Some(collider) = c {
      log::info!("collider obtained\nc:{collider:#?}");
      if let Some(rb) = collider
        .parent()
        .map(|x| xs.rigid_body_set.get(x))
        .flatten()
      {
        log::info!(
          "rb obtained from handle {:?}\nrb:{rb:#?}",
          collider.parent()
        );
        log::info!("pos:{}", rb.position().translation);
      }
    }
  });
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_initialize(
  _env: EnvUnowned,
  _class: JClass,
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
  _class: JClass,
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
  //try_into because if this overflows it will definitely get corrupted and should crash
  collider.handle().0.try_into().unwrap()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_removeChunk(
  _env: EnvUnowned,
  _class: JClass,
  _dimension: jint,
  _x: jint,
  _z: jint,
) {
  todo!()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_debuggingBall(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  x: jint,
  y: jint,
  z: jint,
) -> jlong {
  let index = Scene::with_scene_mut(world_id as usize, |xs| {
    let ball = ColliderBuilder::ball(1.0).restitution(1.0).build();
    let ball_rb = RigidBodyBuilder::new(rapier3d::prelude::RigidBodyType::Dynamic)
      .translation(Vec3::new(x as Real, y as Real, z as Real))
      .build();

    xs.collider_set.iter_enabled().for_each(|(_, x)| {
      log::info!("{x:#?}");
    });
    xs.collider_set.insert_with_parent(
      ball,
      xs.rigid_body_set.insert(ball_rb),
      &mut xs.rigid_body_set,
    )
  });
  let index = index.unwrap().0;
  unsafe { mem::transmute(index) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_partialSubchunkUpdate(
  _env: EnvUnowned,
  _class: JClass,
  dimension: jint,
  _chunk_x: jint,
  _chunk_z: jint,
  _chunk_y: jint,
  x: jint,
  y: jint,
  z: jint,
  _new_data: jint,
) {
  debug_assert!(dimension >= 0);
  debug_assert!(x < 16 && x >= 0);
  debug_assert!(y < 16 && y >= 0);
  debug_assert!(z < 16 && z >= 0);
  todo!()
}
