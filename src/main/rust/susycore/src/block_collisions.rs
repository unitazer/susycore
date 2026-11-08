use std::num::NonZeroU32;
use std::sync::RwLock;

use jni::EnvUnowned;
use jni::objects::{JClass, JDoubleArray, JIntArray};
use jni::sys::{jboolean, jdouble, jfloat, jint};
use rapier3d::math::Vec3;
use rapier3d::parry::bounding_volume::{Aabb, BoundingVolume};
use rapier3d::prelude::{QueryFilter, RigidBodyHandle};

use crate::Real;
use crate::chunklet::Chunklet;
use crate::scene::Scene;
use crate::terrain::PackedChunkletCoords;
//TODO callbacks

pub static COLLIDERS: RwLock<ColliderStore> = RwLock::new(ColliderStore::new());
pub const AIR_HANDLE: BlockColliderInfoHandle = BlockColliderInfoHandle(0);

pub fn clear_caches() {
  COLLIDERS.write().unwrap().inner.clear();
}

#[derive(Eq, PartialOrd, Ord, PartialEq, Hash, Copy, Clone)]
pub struct BlockColliderInfoHandle(pub(crate) u32);

impl BlockColliderInfoHandle {
  #[inline(always)]
  pub fn into_block(self) -> Option<NonZeroU32> {
    NonZeroU32::new(self.0)
  }
}

pub const BOX: Aabb = Aabb {
  mins: Vec3::new(0.0, 0.0, 0.0),
  maxs: Vec3::new(1.0, 1.0, 1.0),
};

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
}
#[derive(PartialEq, Clone, Debug)]
pub struct MinecraftBlockColliderInfo {
  pub friction: Real,
  pub restitution: Real,
  pub density: Real,
  pub mass: Real,
  pub boxes: Vec<Aabb>,
  //TODO this is for a future test, rapier Voxels + Compound([Cuboid]) might be more efficient for
  //collisions in general
  pub is_cube: bool,
}

impl MinecraftBlockColliderInfo {
  pub fn new(
    friction: Real,
    density: Real,
    restitution: Real,
    boxes: Vec<Aabb>,
    is_box: bool,
  ) -> Self {
    debug_assert!(!boxes.is_empty());
    Self {
      friction,
      density,
      restitution,
      mass: boxes.iter().map(|x| x.volume()).sum(),
      boxes,
      is_cube: is_box,
    }
  }
  pub fn handle(self) -> BlockColliderInfoHandle {
    let colliders = &mut *COLLIDERS.write().expect("rust bug not mine");
    colliders.find_or_insert(self)
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
  if PackedChunkletCoords::try_from_xyz(x, y, z).is_none() {
    log::warn!("addChunk on out-of-range chunklet coords {x} {y} {z}");
    return;
  }
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
    let c = Chunklet::new_with_blockhandle(b);
    xs.add_chunklet(x, y as u8, z, c);
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
  is_cube: jboolean,
) -> jint {
  let aabbs_data = if is_cube {
    Vec::default()
  } else {
    env
      .with_env(|env| -> Result<Vec<jdouble>, jni::errors::Error> {
        let len = aabbs.len(env)?;
        let mut buffer = vec![0f64; len];
        aabbs.get_region(env, 0, &mut buffer)?;
        Ok(buffer)
      })
      .resolve::<jni::errors::ThrowRuntimeExAndDefault>()
  };
  debug_assert!(is_cube || aabbs_data.len().is_multiple_of(6));

  let boxes_cast = if is_cube {
    vec![BOX]
  } else {
    aabbs_data
      .chunks_exact(6)
      .map(|x| {
        Aabb::new(
          Vec3::new(x[0] as Real, x[1] as Real, x[2] as Real),
          Vec3::new(x[3] as Real, x[4] as Real, x[5] as Real),
        )
      })
      .collect()
  };

  let collider = MinecraftBlockColliderInfo::new(
    friction as Real,
    density as Real,
    restitution as Real,
    boxes_cast,
    is_cube,
  );
  let handle = collider.handle().0;
  match jint::try_from(handle) {
    Ok(v) => v,
    Err(_) => {
      log::error!("collider handle {handle} does not fit in jint");
      0
    }
  }
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
  if PackedChunkletCoords::try_from_xyz(x, y, z).is_none() {
    log::warn!("removeChunk on out-of-range chunklet coords {x} {y} {z}");
    return;
  }
  Scene::with_scene_mut(world_id as usize, |xs| {
    xs.terrain.remove(
      x,
      y as u8,
      z,
      &mut xs.world.colliders,
      &mut xs.world.islands,
      &mut xs.world.bodies,
      &mut xs.world.soft_bodies,
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
  if PackedChunkletCoords::try_from_xyz(cx, cy, cz).is_none() {
    log::warn!("partialSubchunkUpdate on out-of-range chunk coords {cx} {cy} {cz}");
    return;
  }
  if !(0..16).contains(&x) || !(0..16).contains(&y) || !(0..16).contains(&z) {
    log::warn!("partialSubchunkUpdate on out-of-range block coords {x} {y} {z}");
    return;
  }

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
      &mut xs.world.soft_bodies,
    );

    let block_world_x = (cx * 16 + x) as f32;
    let block_world_y = (cy * 16 + y) as f32;
    let block_world_z = (cz * 16 + z) as f32;

    const WAKE_MARGIN: Real = 1.0;
    let block_aabb = Aabb::new(
      Vec3::new(block_world_x, block_world_y, block_world_z),
      Vec3::new(
        block_world_x + 1.0,
        block_world_y + 1.0,
        block_world_z + 1.0,
      ),
    )
    .loosened(WAKE_MARGIN);

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
