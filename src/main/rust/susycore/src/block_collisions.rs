use std::mem::{self};
use std::sync::{Arc, RwLock};

use jni::EnvUnowned;
use jni::objects::{JClass, JDoubleArray, JIntArray};
use jni::sys::{jdouble, jfloat, jint, jlong};
use rapier3d::glamx::Quat;
use rapier3d::math::{Pose, Vec3};
use rapier3d::parry::bounding_volume::Aabb;
use rapier3d::parry::shape::Cuboid;
use rapier3d::prelude::{Collider, ColliderBuilder, QueryFilter, RigidBodyHandle, SharedShape};

use crate::chunklet::Chunklet;
use crate::scene::Scene;
use crate::{IHateJava, Real};
//TODO callbacks

pub static COLLIDERS: RwLock<ColliderStore> = RwLock::new(ColliderStore::new());

pub fn clear_caches() {
  COLLIDERS.write().unwrap().inner.clear();
}

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

pub struct ColliderStore {
  pub inner: Vec<MinecraftBlockColliderInfo>,
}

impl Default for ColliderStore {
  fn default() -> Self {
    Self::new()
  }
}

impl ColliderStore {
  pub const fn new() -> Self {
    Self { inner: Vec::new() }
  }

  pub fn get(&self, handle: BlockColliderInfoHandle) -> Option<&MinecraftBlockColliderInfo> {
    if handle.0 == 0 {
      return None;
    }
    self.inner.get((handle.0 - 1) as usize)
  }

  pub fn find_or_insert(&mut self, info: MinecraftBlockColliderInfo) -> BlockColliderInfoHandle {
    if let Some(pos) = self.inner.iter().position(|x| x == &info) {
      return BlockColliderInfoHandle(pos as u32 + 1);
    }
    let index = self.inner.len();
    self.inner.push(info);
    let out = BlockColliderInfoHandle(index as u32 + 1);
    debug_assert!(out != AIR_HANDLE);
    out
  }

  pub fn len(&self) -> usize {
    self.inner.len()
  }

  pub fn is_empty(&self) -> bool {
    self.inner.is_empty()
  }
}

impl MinecraftBlockColliderInfo {
  pub fn new(friction: Real, density: Real, restitution: Real, boxes: Vec<Aabb>) -> Self {
    debug_assert!(!boxes.is_empty());
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
    colliders.get(h).map(f)
  }
  pub fn handle(self) -> BlockColliderInfoHandle {
    let colliders = &mut *COLLIDERS.write().expect("rust bug not mine");
    colliders.find_or_insert(self)
  }

  //either a cuboid or a compound  shape, not for turning entire chunks into colliders but only a
  //single one
  pub fn into_collider(self) -> Collider {
    if self.boxes.len() == 1 {
      let aabb = &self.boxes[0];
      let cuboid = Cuboid::new(aabb.half_extents());
      let shape = SharedShape(Arc::new(cuboid));
      Self::to_collider(shape, self.friction, self.restitution, self.density)
    } else {
      Self::to_collider(
        Self::into_compound(self.boxes),
        self.friction,
        self.restitution,
        self.density,
      )
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
    SharedShape::compound(
      boxes
        .into_iter()
        .map(|x| {
          let pose = Pose::from_parts(x.center(), Quat::IDENTITY);
          (
            pose,
            SharedShape::cuboid(x.half_extents().x, x.half_extents().y, x.half_extents().z),
          )
        })
        .collect(),
    )
  }
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_addChunk(
  mut env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  x: jint,
  y: jint,
  z: jint,
  data: JIntArray,
) {
  debug_assert!((0..=16).contains(&y), "xyz: {x} {y} {z}");
  // let mut buffer: [[i32; 4096]; 16] = [[0; 4096]; 16];
  let mut buffer: [i32; 4096] = [0; 4096];
  env
    .with_env(|env| -> Result<(), jni::errors::Error> {
      debug_assert!(data.len(env)? == 4096);
      data.get_region(env, 0, &mut buffer)?;
      Ok(())
    })
    .resolve::<jni::errors::ThrowRuntimeExAndDefault>();
  let non_zero_count = buffer.iter().filter(|x| **x != 0).count();
  if non_zero_count == 0 {
    log::error!("empty subchunk supplied, shouldve been filtered out");
    return;
  }

  Scene::with_scene_mut(world_id as usize, |xs| {
    let b = buffer.map(|x| x as u32).map(BlockColliderInfoHandle);
    let c = Chunklet::new_with_blockhandle(x, y, z, b);
    xs.add_chunklet(x, y as u8, z, c);
  });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_RbInfo(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  handle: jlong,
) {
  let handle = handle.collider_handle();

  log::info!("handle:{handle:?}");
  Scene::with_scene(world_id as usize, |xs| {
    let c = xs.world.colliders.get(handle);
    if let Some(collider) = c {
      log::info!("collider obtained\nc:{collider:#?}");
      if let Some(rb) = collider.parent().and_then(|x| xs.world.bodies.get(x)) {
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
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_destroyWorld(
  _env: EnvUnowned,
  _class: JClass,
  dimension: jint,
) {
  Scene::destroy_scene(dimension as usize);
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_reset(_env: EnvUnowned, _class: JClass) {
  Scene::reset_all();
  clear_caches();
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
  debug_assert!(aabbs_data.len().is_multiple_of(6));
  debug_assert!(aabbs_data.capacity().is_multiple_of(6));
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
  world_id: jint,
  x: jint,
  y: jint,
  z: jint,
) {
  debug_assert!(
    (0..=16).contains(&y),
    "removeChunk: xyz=({}, {}, {})",
    x,
    y,
    z
  );
  Scene::with_scene_mut(world_id as usize, |xs| {
    xs.terrain.remove(
      x,
      y as u8,
      z,
      &mut xs.world.colliders,
      &mut xs.world.islands,
      &mut xs.world.bodies,
    );
  });
}

//TODO make this called less often from the java side somehow
#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_partialSubchunkUpdate(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  cx: jint,
  cz: jint,
  cy: jint,
  x: jint,
  y: jint,
  z: jint,
  new_data: jint,
) {
  debug_assert!(world_id >= 0);
  debug_assert!((0..16).contains(&x));
  debug_assert!((0..16).contains(&y));
  debug_assert!((0..16).contains(&z));
  debug_assert!((0..16).contains(&cy));

  Scene::with_scene_mut(world_id as usize, |xs| {
    xs.terrain.update(
      cx,
      cy as u8,
      cz,
      x as u8,
      y as u8,
      z as u8,
      BlockColliderInfoHandle(new_data as u32),
      &mut xs.world.colliders,
      &mut xs.world.islands,
      &mut xs.world.bodies,
    );

    let block_world_x = (cx * 16 + x) as f32;
    let block_world_y = (cy * 16 + y) as f32;
    let block_world_z = (cz * 16 + z) as f32;

    let block_aabb = Aabb::new(
      Vec3::new(block_world_x, block_world_y, block_world_z),
      Vec3::new(
        block_world_x + 1.0,
        block_world_y + 1.0,
        block_world_z + 1.0,
      ),
    );

    let query_pipeline = xs.world.broad_phase.as_query_pipeline(
      xs.world.narrow_phase.query_dispatcher(),
      &xs.world.bodies,
      &xs.world.colliders,
      QueryFilter::default(),
    );

    let to_wake: Vec<RigidBodyHandle> = query_pipeline
      .intersect_aabb_conservative(block_aabb)
      .filter_map(|(_, co)| co.parent())
      .collect();

    for handle in to_wake {
      if let Some(rb) = xs.world.bodies.get_mut(handle) {
        rb.wake_up(true);
      }
    }
  });
}
