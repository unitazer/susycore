use std::collections::HashMap;

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JDoubleArray, JIntArray};
use jni::sys::{jboolean, jdouble, jint, jlong};
use rapier3d::dynamics::RigidBodyHandle;
use rapier3d::glamx::Quat;
use rapier3d::math::{Pose, Vec3};
use rapier3d::parry::query::Ray;
use rapier3d::prelude::{
  ColliderBuilder, ColliderHandle, QueryFilter, RigidBodyBuilder, SharedShape,
};

use crate::Real;
use crate::block_collisions::{AIR_HANDLE, BlockColliderInfoHandle};
use crate::chunklet::Chunklet;
use crate::scene::Scene;
use crate::terrain::{CHUNK_VOLUME, PackedChunkletCoords};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct EntityId(pub i32);

pub struct BodyEntry {
  pub rb: RigidBodyHandle,
  pub colliders: HashMap<PackedChunkletCoords, ColliderHandle>,
}

pub const BLOCK_DENSITY: Real = 1000.0;
pub const POSE_BUFFER_LEN: usize = 11;

pub struct RayHit {
  pub toi: Real,
  pub normal: Vec3,
  pub owner: Option<EntityId>,
}

impl Scene {
  pub fn create_chunklet_body(
    &mut self,
    entity: EntityId,
    pos: Vec3,
    rot: Quat,
  ) -> RigidBodyHandle {
    if self.chunklet_bodies.contains_key(&entity) {
      log::warn!("chunklet body for {} already exists, replacing", entity.0);
      self.remove_chunklet_body(entity);
    }
    let rb = RigidBodyBuilder::dynamic()
      .pose(Pose::from_parts(pos, rot))
      .build();
    let handle = self.world.bodies.insert(rb);
    self.chunklet_bodies.insert(
      entity,
      BodyEntry {
        rb: handle,
        colliders: HashMap::new(),
      },
    );
    handle
  }

  pub fn add_body_chunk(
    &mut self,
    entity: EntityId,
    coords: PackedChunkletCoords,
    offset: Vec3,
    data: [BlockColliderInfoHandle; CHUNK_VOLUME],
  ) -> Option<ColliderHandle> {
    if data.iter().all(|h| *h == AIR_HANDLE) {
      self.remove_body_chunk(entity, coords);
      return None;
    }
    self.remove_body_chunk(entity, coords);
    let Some(entry) = self.chunklet_bodies.get_mut(&entity) else {
      log::error!("add_body_chunk on missing body {}", entity.0);
      return None;
    };
    let chunklet = Chunklet::new_with_blockhandle(data);
    let collider = ColliderBuilder::new(SharedShape::new(chunklet))
      .density(BLOCK_DENSITY)
      .translation(offset)
      .build();
    let handle =
      self
        .world
        .colliders
        .insert_with_parent(collider, entry.rb, &mut self.world.bodies);
    entry.colliders.insert(coords, handle);
    self.collider_owners.insert(handle, entity);
    Some(handle)
  }

  pub fn update_body_block(
    &mut self,
    entity: EntityId,
    coords: PackedChunkletCoords,
    x: u8,
    y: u8,
    z: u8,
    new_block: BlockColliderInfoHandle,
  ) -> bool {
    let Some(&handle) = self
      .chunklet_bodies
      .get(&entity)
      .and_then(|e| e.colliders.get(&coords))
    else {
      log::error!(
        "update_body_block on missing chunk {coords:?} of body {}",
        entity.0
      );
      return false;
    };
    let Some(collider) = self.world.colliders.get_mut(handle) else {
      log::error!(
        "stale collider {handle:?} for chunk {coords:?} of body {}",
        entity.0
      );
      return false;
    };
    let Some(shape) = collider.shape_mut().as_shape_mut::<Chunklet>() else {
      log::error!("body chunklet collider {handle:?} is not a Chunklet");
      return false;
    };
    shape.set(x, y, z, new_block.into_block());
    let emptied = shape.count() == 0;
    if emptied {
      self.remove_body_chunk(entity, coords);
    }
    !emptied
  }

  pub fn remove_body_chunk(&mut self, entity: EntityId, coords: PackedChunkletCoords) {
    let Some(handle) = self
      .chunklet_bodies
      .get_mut(&entity)
      .and_then(|e| e.colliders.remove(&coords))
    else {
      return;
    };
    self.collider_owners.remove(&handle);
    self.world.colliders.remove(
      handle,
      &mut self.world.islands,
      &mut self.world.bodies,
      true,
    );
  }

  pub fn remove_chunklet_body(&mut self, entity: EntityId) {
    let Some(entry) = self.chunklet_bodies.remove(&entity) else {
      return;
    };
    for handle in entry.colliders.into_values() {
      self.collider_owners.remove(&handle);
    }
    self.world.bodies.remove(
      entry.rb,
      &mut self.world.islands,
      &mut self.world.colliders,
      &mut self.world.impulse_joints,
      &mut self.world.multibody_joints,
      true,
    );
  }

  pub fn set_body_pose(&mut self, entity: EntityId, pos: Vec3, rot: Quat, vel: Vec3) {
    let Some(rb) = self.rb_of(entity) else {
      log::error!("set_body_pose on missing body {}", entity.0);
      return;
    };
    let Some(body) = self.world.bodies.get_mut(rb) else {
      log::error!("stale rigid body for body {}", entity.0);
      return;
    };
    body.set_position(Pose::from_parts(pos, rot), true);
    body.set_linvel(vel, true);
  }

  pub fn body_pose_into(&self, entity: EntityId, out: &mut [f64; POSE_BUFFER_LEN]) -> bool {
    let Some(entry) = self.chunklet_bodies.get(&entity) else {
      return false;
    };
    let Some(rb) = self.world.bodies.get(entry.rb) else {
      return false;
    };
    let pos = rb.position().translation;
    out[0] = pos.x as f64;
    out[1] = pos.y as f64;
    out[2] = pos.z as f64;
    let rot = rb.position().rotation;
    out[3] = rot.x as f64;
    out[4] = rot.y as f64;
    out[5] = rot.z as f64;
    out[6] = rot.w as f64;
    let vel = rb.linvel();
    out[7] = vel.x as f64;
    out[8] = vel.y as f64;
    out[9] = vel.z as f64;
    out[10] = rb.mass() as f64;
    true
  }

  pub fn impulse_at_point(&mut self, entity: EntityId, point: Vec3, impulse: Vec3) {
    let Some(rb) = self.rb_of(entity) else {
      log::error!("impulse_at_point on missing body {}", entity.0);
      return;
    };
    if let Some(body) = self.world.bodies.get_mut(rb) {
      body.apply_impulse_at_point(impulse, point, true);
    }
  }

  fn rb_of(&self, entity: EntityId) -> Option<RigidBodyHandle> {
    self.chunklet_bodies.get(&entity).map(|e| e.rb)
  }

  pub fn cast_ray(
    &self,
    origin: Vec3,
    dir: Vec3,
    max_toi: Real,
    solid: bool,
  ) -> Option<(ColliderHandle, RayHit)> {
    let pipeline = self.world.broad_phase.as_query_pipeline(
      self.world.narrow_phase.query_dispatcher(),
      &self.world.bodies,
      &self.world.colliders,
      QueryFilter::default(),
    );
    let (handle, hit) = pipeline.cast_ray_and_get_normal(&Ray::new(origin, dir), max_toi, solid)?;
    let owner = self.collider_owners.get(&handle).copied();
    Some((
      handle,
      RayHit {
        toi: hit.time_of_impact,
        normal: hit.normal,
        owner,
      },
    ))
  }
}

const NO_HIT: jlong = -1;

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_createChunkletBody(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  tag: jint,
  x: jdouble,
  y: jdouble,
  z: jdouble,
  qw: jdouble,
  qx: jdouble,
  qy: jdouble,
  qz: jdouble,
) -> bool {
  debug_assert!(world_id >= 0);
  let entity = EntityId(tag);
  let rot = Quat::from_xyzw(qx as f32, qy as f32, qz as f32, qw as f32);
  let handle = Scene::with_scene_mut(world_id as usize, |scene| {
    scene.create_chunklet_body(entity, Vec3::new(x as Real, y as Real, z as Real), rot)
  });
  match handle {
    Some(_) => true,
    None => {
      log::error!("createChunkletBody on missing scene {world_id}");
      false
    }
  }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_addBodyChunk(
  mut env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  tag: jint,
  lx: jint,
  ly: jint,
  lz: jint,
  ox: jdouble,
  oy: jdouble,
  oz: jdouble,
  data: JIntArray,
) {
  debug_assert!(world_id >= 0);
  let mut buffer: [i32; CHUNK_VOLUME] = [0; CHUNK_VOLUME];
  env
    .with_env(|env| -> Result<(), jni::errors::Error> {
      debug_assert!(data.len(env)? == buffer.len());
      data.get_region(env, 0, &mut buffer)?;
      Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>();
  let entity = EntityId(tag);
  let coords = PackedChunkletCoords::from_xyz(lx, ly as u8, lz);
  let data = buffer.map(|h| BlockColliderInfoHandle(h as u32));
  Scene::with_scene_mut(world_id as usize, |scene| {
    scene.add_body_chunk(
      entity,
      coords,
      Vec3::new(ox as Real, oy as Real, oz as Real),
      data,
    );
  });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_updateBodyChunkBlock(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  tag: jint,
  lx: jint,
  ly: jint,
  lz: jint,
  x: jint,
  y: jint,
  z: jint,
  new_data: jint,
) -> bool {
  debug_assert!(world_id >= 0);
  debug_assert!((0..16).contains(&x));
  debug_assert!((0..16).contains(&y));
  debug_assert!((0..16).contains(&z));
  let entity = EntityId(tag);
  let coords = PackedChunkletCoords::from_xyz(lx, ly as u8, lz);
  Scene::with_scene_mut(world_id as usize, |scene| {
    scene.update_body_block(
      entity,
      coords,
      x as u8,
      y as u8,
      z as u8,
      BlockColliderInfoHandle(new_data as u32),
    )
  })
  .unwrap_or(false)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_removeBodyChunk(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  tag: jint,
  lx: jint,
  ly: jint,
  lz: jint,
) {
  debug_assert!(world_id >= 0);
  let entity = EntityId(tag);
  let coords = PackedChunkletCoords::from_xyz(lx, ly as u8, lz);
  Scene::with_scene_mut(world_id as usize, |scene| {
    scene.remove_body_chunk(entity, coords);
  });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_removeChunkletBody(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  tag: jint,
) {
  debug_assert!(world_id >= 0);
  Scene::with_scene_mut(world_id as usize, |scene| {
    scene.remove_chunklet_body(EntityId(tag));
  });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_setChunkletBodyPose(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  tag: jint,
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
  debug_assert!(world_id >= 0);
  let entity = EntityId(tag);
  let rot = Quat::from_xyzw(qx as f32, qy as f32, qz as f32, qw as f32);
  Scene::with_scene_mut(world_id as usize, |scene| {
    scene.set_body_pose(
      entity,
      Vec3::new(x as Real, y as Real, z as Real),
      rot,
      Vec3::new(vx as Real, vy as Real, vz as Real),
    );
  });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_getChunkletBodyPose(
  mut env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  tag: jint,
  arr: JDoubleArray,
) {
  debug_assert!(world_id >= 0);
  let mut out = [0.0; POSE_BUFFER_LEN];
  Scene::with_scene(world_id as usize, |scene| {
    scene.body_pose_into(EntityId(tag), &mut out)
  });
  env
    .with_env(|env| -> Result<(), jni::errors::Error> {
      arr.set_region(env, 0, &out)?;
      Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_applyImpulseAtPoint(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  tag: jint,
  px: jdouble,
  py: jdouble,
  pz: jdouble,
  ix: jdouble,
  iy: jdouble,
  iz: jdouble,
) {
  debug_assert!(world_id >= 0);
  Scene::with_scene_mut(world_id as usize, |scene| {
    scene.impulse_at_point(
      EntityId(tag),
      Vec3::new(px as Real, py as Real, pz as Real),
      Vec3::new(ix as Real, iy as Real, iz as Real),
    );
  });
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_castRay(
  mut env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  ox: jdouble,
  oy: jdouble,
  oz: jdouble,
  dx: jdouble,
  dy: jdouble,
  dz: jdouble,
  max_toi: jdouble,
  solid: jboolean,
  out_arr: JDoubleArray,
) -> jlong {
  debug_assert!(world_id >= 0);
  let hit = Scene::with_scene(world_id as usize, |scene| {
    scene.cast_ray(
      Vec3::new(ox as Real, oy as Real, oz as Real),
      Vec3::new(dx as Real, dy as Real, dz as Real),
      max_toi as Real,
      solid,
    )
  })
  .flatten();
  let mut out = [0.0; 5];
  let handle = match hit {
    Some((handle, hit)) => {
      out[0] = hit.toi as f64;
      out[1] = hit.normal.x as f64;
      out[2] = hit.normal.y as f64;
      out[3] = hit.normal.z as f64;
      out[4] = hit.owner.map(|e| e.0 as f64).unwrap_or(-1.0);
      crate::jlong_handle(handle)
    }
    None => NO_HIT,
  };
  env
    .with_env(|env| -> Result<(), jni::errors::Error> {
      out_arr.set_region(env, 0, &out)?;
      Ok(())
    })
    .resolve::<ThrowRuntimeExAndDefault>();
  handle
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::block_collisions::MinecraftBlockColliderInfo;
  use crate::dispatcher::ChunkletDispatcher;
  use rapier3d::parry::bounding_volume::Aabb;
  use rapier3d::parry::query::PersistentQueryDispatcher;
  use rapier3d::prelude::{ContactManifold, PointQuery, RayCast};

  type Manifold = ContactManifold;

  fn full_chunklet_data(
    handle: BlockColliderInfoHandle,
  ) -> [BlockColliderInfoHandle; CHUNK_VOLUME] {
    [handle; CHUNK_VOLUME]
  }

  fn scene() -> Scene {
    Scene::new(Vec3::new(0.0, -10.0, 0.0))
  }

  fn collider_handle_for_box(min: [f32; 3], max: [f32; 3]) -> BlockColliderInfoHandle {
    MinecraftBlockColliderInfo::new(
      0.5,
      BLOCK_DENSITY,
      0.0,
      vec![Aabb::new(Vec3::from_slice(&min), Vec3::from_slice(&max))],
    )
    .handle()
  }

  fn cast(s: &Scene, origin: Vec3, dir: Vec3, max_toi: Real) -> Option<(Real, Vec3, i32)> {
    s.cast_ray(origin, dir, max_toi, true)
      .map(|(_, hit)| (hit.toi, hit.normal, hit.owner.map(|e| e.0).unwrap_or(-1)))
  }

  #[test]
  fn create_and_remove_body() {
    let mut s = scene();
    let rb = s.create_chunklet_body(EntityId(1), Vec3::new(1.0, 2.0, 3.0), Quat::IDENTITY);
    assert!(s.world.bodies.get(rb).is_some());
    assert!(s.chunklet_bodies.contains_key(&EntityId(1)));
    s.remove_chunklet_body(EntityId(1));
    assert!(s.world.bodies.get(rb).is_none());
    assert!(!s.chunklet_bodies.contains_key(&EntityId(1)));
  }

  #[test]
  fn create_replaces_existing_tag() {
    let mut s = scene();
    s.create_chunklet_body(EntityId(1), Vec3::ZERO, Quat::IDENTITY);
    let first = s.chunklet_bodies.get(&EntityId(1)).unwrap().rb;
    s.create_chunklet_body(EntityId(1), Vec3::ZERO, Quat::IDENTITY);
    assert!(s.world.bodies.get(first).is_none());
    assert_eq!(s.chunklet_bodies.len(), 1);
  }

  #[test]
  fn add_chunk_creates_collider_and_owner_map() {
    let mut s = scene();
    s.create_chunklet_body(EntityId(7), Vec3::ZERO, Quat::IDENTITY);
    let h = collider_handle_for_box([0.0; 3], [1.0; 3]);
    let key = PackedChunkletCoords::from_xyz(0, 0, 0);
    let col = s
      .add_body_chunk(EntityId(7), key, Vec3::ZERO, full_chunklet_data(h))
      .expect("collider");
    assert_eq!(s.collider_owners.get(&col), Some(&EntityId(7)));
    s.remove_chunklet_body(EntityId(7));
    assert!(!s.collider_owners.contains_key(&col));
    assert!(s.world.colliders.get(col).is_none());
  }

  #[test]
  fn add_empty_chunk_removes_existing() {
    let mut s = scene();
    s.create_chunklet_body(EntityId(7), Vec3::ZERO, Quat::IDENTITY);
    let h = collider_handle_for_box([0.0; 3], [1.0; 3]);
    let key = PackedChunkletCoords::from_xyz(1, 2, 3);
    let col = s
      .add_body_chunk(EntityId(7), key, Vec3::ZERO, full_chunklet_data(h))
      .unwrap();
    assert!(
      s.add_body_chunk(EntityId(7), key, Vec3::ZERO, [AIR_HANDLE; CHUNK_VOLUME])
        .is_none()
    );
    assert!(s.world.colliders.get(col).is_none());
    assert!(!s.collider_owners.contains_key(&col));
  }

  #[test]
  fn update_block_changes_count_and_removes_empty_chunklet() {
    let mut s = scene();
    s.create_chunklet_body(EntityId(7), Vec3::ZERO, Quat::IDENTITY);
    let h = collider_handle_for_box([0.0; 3], [1.0; 3]);
    let key = PackedChunkletCoords::from_xyz(0, 0, 0);
    let mut data = [AIR_HANDLE; CHUNK_VOLUME];
    data[Chunklet::index(1, 2, 3) as usize] = h;
    let col = s
      .add_body_chunk(EntityId(7), key, Vec3::ZERO, data)
      .unwrap();
    let count = |s: &Scene| {
      s.world
        .colliders
        .get(col)
        .unwrap()
        .shape()
        .as_shape::<Chunklet>()
        .unwrap()
        .count()
    };
    assert_eq!(count(&s), 1);
    s.update_body_block(EntityId(7), key, 4, 5, 6, h);
    assert_eq!(count(&s), 2);
    s.update_body_block(EntityId(7), key, 4, 5, 6, AIR_HANDLE);
    assert_eq!(count(&s), 1);
    s.update_body_block(EntityId(7), key, 1, 2, 3, AIR_HANDLE);
    assert!(s.world.colliders.get(col).is_none());
    assert!(
      s.chunklet_bodies
        .get(&EntityId(7))
        .unwrap()
        .colliders
        .is_empty()
    );
  }

  #[test]
  fn pose_roundtrip() {
    let mut s = scene();
    s.create_chunklet_body(EntityId(3), Vec3::ZERO, Quat::IDENTITY);
    let rot = Quat::from_xyzw(0.0, 0.0, 0.3826834, 0.9238795);
    s.set_body_pose(
      EntityId(3),
      Vec3::new(10.0, 20.0, 30.0),
      rot,
      Vec3::new(1.0, 2.0, 3.0),
    );
    let mut out = [0.0; POSE_BUFFER_LEN];
    assert!(s.body_pose_into(EntityId(3), &mut out));
    assert!((out[0] - 10.0).abs() < 1e-4);
    assert!((out[1] - 20.0).abs() < 1e-4);
    assert!((out[2] - 30.0).abs() < 1e-4);
    assert!((out[3] - 0.0).abs() < 1e-5);
    assert!((out[6] - 0.9238795).abs() < 1e-4);
    assert!((out[7] - 1.0).abs() < 1e-4);
    assert!((out[8] - 2.0).abs() < 1e-4);
    assert!((out[9] - 3.0).abs() < 1e-4);
    assert!(!s.body_pose_into(EntityId(99), &mut out));
  }

  #[test]
  fn body_mass_from_chunks() {
    let mut s = scene();
    s.create_chunklet_body(EntityId(3), Vec3::ZERO, Quat::IDENTITY);
    let h = collider_handle_for_box([0.0; 3], [1.0; 3]);
    s.add_body_chunk(
      EntityId(3),
      PackedChunkletCoords::from_xyz(0, 0, 0),
      Vec3::ZERO,
      full_chunklet_data(h),
    );
    let mut out = [0.0; POSE_BUFFER_LEN];
    s.body_pose_into(EntityId(3), &mut out);
    assert!((out[10] - 16f64.powi(3) * BLOCK_DENSITY as f64).abs() < 10.0);
  }

  #[test]
  fn impulse_changes_velocity() {
    let mut s = scene();
    s.create_chunklet_body(EntityId(3), Vec3::ZERO, Quat::IDENTITY);
    let h = collider_handle_for_box([0.0; 3], [1.0; 3]);
    s.add_body_chunk(
      EntityId(3),
      PackedChunkletCoords::from_xyz(0, 0, 0),
      Vec3::ZERO,
      full_chunklet_data(h),
    );
    let mass = {
      let rb = s.chunklet_bodies.get(&EntityId(3)).unwrap().rb;
      s.world.bodies.get(rb).unwrap().mass()
    };
    s.impulse_at_point(
      EntityId(3),
      Vec3::new(8.0, 8.0, 3.0),
      Vec3::new(mass, 0.0, 0.0),
    );
    let rb = s.chunklet_bodies.get(&EntityId(3)).unwrap().rb;
    let body = s.world.bodies.get(rb).unwrap();
    assert!((body.linvel().x - 1.0).abs() < 1e-3);
    assert!(body.angvel().y.abs() > 0.0);
  }

  #[test]
  fn cast_ray_hits_terrain_and_body() {
    let mut s = Scene::new(Vec3::ZERO);
    let h = collider_handle_for_box([0.0; 3], [1.0; 3]);
    s.add_chunklet(
      0,
      0,
      0,
      Chunklet::new_with_blockhandle(full_chunklet_data(h)),
    );
    s.create_chunklet_body(EntityId(5), Vec3::new(100.0, 0.0, 0.0), Quat::IDENTITY);
    s.add_body_chunk(
      EntityId(5),
      PackedChunkletCoords::from_xyz(0, 0, 0),
      Vec3::new(0.0, 0.0, 0.0),
      full_chunklet_data(h),
    );
    s.world.step();

    let down = Vec3::new(0.0, -1.0, 0.0);
    let (toi, _, owner) =
      cast(&s, Vec3::new(0.5, 100.0, 0.5), down, 1000.0).expect("terrain hit expected");
    assert!((toi - 84.0).abs() < 1e-3, "toi {toi}");
    assert_eq!(owner, -1);

    let (_, _, owner) =
      cast(&s, Vec3::new(100.5, 100.0, 0.5), down, 1000.0).expect("body hit expected");
    assert_eq!(owner, 5, "body tag expected");

    assert!(cast(&s, Vec3::new(1000.5, 100.0, 0.5), down, 1000.0).is_none());
  }

  #[test]
  fn ray_hits_chunklet_shape_directly() {
    let h = collider_handle_for_box([0.0; 3], [1.0; 3]);
    let mut data = [AIR_HANDLE; CHUNK_VOLUME];
    data[Chunklet::index(3, 3, 3) as usize] = h;
    let chunklet = Chunklet::new_with_blockhandle(data);

    let hit = chunklet.cast_local_ray_and_get_normal(
      &Ray::new(Vec3::new(3.5, 10.0, 3.5), Vec3::new(0.0, -1.0, 0.0)),
      100.0,
      true,
    );
    let hit = hit.expect("expected a hit");
    assert!((hit.time_of_impact - (10.0 - 4.0)).abs() < 1e-4);
    assert!(hit.normal.y > 0.99);

    let miss = chunklet.cast_local_ray_and_get_normal(
      &Ray::new(Vec3::new(10.5, 10.0, 3.5), Vec3::new(0.0, -1.0, 0.0)),
      100.0,
      true,
    );
    assert!(miss.is_none());

    let inside = chunklet.project_local_point(Vec3::new(3.5, 3.5, 3.5), true);
    assert!(inside.is_inside);
    let outside = chunklet.project_local_point(Vec3::new(10.5, 3.5, 3.5), true);
    assert!(!outside.is_inside);
  }

  #[test]
  fn body_comes_to_rest_on_terrain() {
    let mut s = Scene::new(Vec3::new(0.0, -10.0, 0.0));
    let h = collider_handle_for_box([0.0; 3], [1.0; 3]);
    s.add_chunklet(
      0,
      0,
      0,
      Chunklet::new_with_blockhandle(full_chunklet_data(h)),
    );
    s.create_chunklet_body(EntityId(9), Vec3::new(0.0, 20.0, 0.0), Quat::IDENTITY);
    s.add_body_chunk(
      EntityId(9),
      PackedChunkletCoords::from_xyz(0, 0, 0),
      Vec3::ZERO,
      full_chunklet_data(h),
    );
    s.world.step();
    for _ in 0..120 {
      s.world.step();
      s.world.step();
      s.world.step();
      s.world.step();
      s.world.step();
      s.world.step();
      s.world.step();
      s.world.step();
      s.world.step();
      s.world.step();
    }
    let rb = s.chunklet_bodies.get(&EntityId(9)).unwrap().rb;
    let body = s.world.bodies.get(rb).unwrap();
    let y = body.position().translation.y;
    assert!(
      (y - 16.0).abs() < 0.1,
      "body should rest on the surface, y={y}"
    );
    assert!(
      body.linvel().length() < 0.5,
      "body should be at rest, linvel={:?}",
      body.linvel()
    );
  }

  #[test]
  fn block_update_refreshes_mass_and_broad_phase() {
    let mut s = Scene::new(Vec3::ZERO);
    let h = collider_handle_for_box([0.0; 3], [1.0; 3]);
    s.create_chunklet_body(EntityId(4), Vec3::new(50.0, 0.0, 0.0), Quat::IDENTITY);
    let mut data = [AIR_HANDLE; CHUNK_VOLUME];
    data[Chunklet::index(0, 0, 0) as usize] = h;
    s.add_body_chunk(
      EntityId(4),
      PackedChunkletCoords::from_xyz(0, 0, 0),
      Vec3::ZERO,
      data,
    );
    s.world.step();
    let mass_before = {
      let rb = s.chunklet_bodies.get(&EntityId(4)).unwrap().rb;
      s.world.bodies.get(rb).unwrap().mass()
    };
    assert!((mass_before - BLOCK_DENSITY).abs() < 1.0);

    s.update_body_block(
      EntityId(4),
      PackedChunkletCoords::from_xyz(0, 0, 0),
      1,
      0,
      0,
      h,
    );
    s.world.step();
    let rb = s.chunklet_bodies.get(&EntityId(4)).unwrap().rb;
    let mass_after = s.world.bodies.get(rb).unwrap().mass();
    assert!((mass_after - 2.0 * BLOCK_DENSITY).abs() < 1.0);

    let (_, _, owner) = cast(
      &s,
      Vec3::new(51.5, 10.0, 0.5),
      Vec3::new(0.0, -1.0, 0.0),
      100.0,
    )
    .expect("newly added block must be visible to raycasts");
    assert_eq!(owner, 4);
  }

  #[test]
  fn rotated_body_raycast() {
    let mut s = Scene::new(Vec3::ZERO);
    let h = collider_handle_for_box([0.0; 3], [1.0; 3]);
    let rot = Quat::from_rotation_y(std::f32::consts::FRAC_PI_2);
    s.create_chunklet_body(EntityId(6), Vec3::new(100.0, 0.0, 0.0), rot);
    let mut data = [AIR_HANDLE; CHUNK_VOLUME];
    data[Chunklet::index(0, 0, 0) as usize] = h;
    s.add_body_chunk(
      EntityId(6),
      PackedChunkletCoords::from_xyz(0, 0, 0),
      Vec3::ZERO,
      data,
    );
    s.world.step();

    let center_local = Vec3::new(0.5, 0.5, 0.5);
    let center_world = Vec3::new(100.0, 0.0, 0.0) + rot * center_local;
    let (toi, normal, owner) = cast(
      &s,
      Vec3::new(center_world.x, 10.0, center_world.z),
      Vec3::new(0.0, -1.0, 0.0),
      100.0,
    )
    .expect("rotated block must be hit at its rotated position");
    assert!((toi - 9.0).abs() < 1e-3, "toi {toi}");
    assert_eq!(owner, 6);
    assert!(normal.y > 0.99, "normal should point up, {normal:?}");
  }

  #[test]
  fn chunklet_chunklet_manifolds() {
    let h = collider_handle_for_box([0.0; 3], [1.0; 3]);
    let a = Chunklet::new_with_blockhandle(full_chunklet_data(h));
    let mut data = [AIR_HANDLE; CHUNK_VOLUME];
    data[Chunklet::index(0, 0, 0) as usize] = h;
    data[Chunklet::index(0, 0, 1) as usize] = h;
    let b = Chunklet::new_with_blockhandle(data);

    let mut manifolds: Vec<Manifold> = Vec::new();
    ChunkletDispatcher
      .contact_manifolds(
        &Pose::translation(0.5, 0.0, 0.0),
        &a,
        &b,
        0.001,
        &mut manifolds,
        &mut None,
      )
      .expect("chunklet chunklet manifolds");
    let points: usize = manifolds.iter().map(|m| m.points.len()).sum();
    assert!(
      points > 0,
      "expected contact points for overlapping chunklets"
    );

    let mut separated: Vec<Manifold> = Vec::new();
    ChunkletDispatcher
      .contact_manifolds(
        &Pose::translation(100.0, 0.0, 0.0),
        &a,
        &b,
        0.001,
        &mut separated,
        &mut None,
      )
      .expect("chunklet chunklet manifolds");
    assert!(separated.iter().all(|m| m.points.is_empty()));

    let count_after_first = manifolds.len();
    ChunkletDispatcher
      .contact_manifolds(
        &Pose::translation(100.0, 0.0, 0.0),
        &a,
        &b,
        0.001,
        &mut manifolds,
        &mut None,
      )
      .expect("chunklet chunklet manifolds");
    assert!(manifolds.len() >= count_after_first);
    assert!(manifolds.iter().all(|m| m.points.is_empty()));
  }

  #[test]
  fn interior_skip_keeps_exposed_faces_of_partial_neighbors() {
    let full = collider_handle_for_box([0.0, 0.0, 0.0], [1.0, 1.0, 1.0]);
    let slab = collider_handle_for_box([0.0, 0.0, 0.0], [1.0, 0.5, 1.0]);
    let probe = collider_handle_for_box([0.0, 0.75, 0.25], [1.0, 1.0, 0.75]);

    let idx = |x: usize, y: usize, z: usize| (y << 8) | (z << 4) | x;
    let mut data = [AIR_HANDLE; CHUNK_VOLUME];
    for cell in [
      (1usize, 1usize, 1usize),
      (0, 1, 1),
      (2, 1, 1),
      (1, 0, 1),
      (1, 2, 1),
      (1, 1, 0),
      (1, 1, 2),
    ] {
      data[idx(cell.0, cell.1, cell.2)] = if cell == (2, 1, 1) { slab } else { full };
    }
    let g1 = Chunklet::new_with_blockhandle(data);

    let mut probe_data = [AIR_HANDLE; CHUNK_VOLUME];
    probe_data[0] = probe;
    let g2 = Chunklet::new_with_blockhandle(probe_data);

    let mut manifolds: Vec<Manifold> = Vec::new();
    ChunkletDispatcher
      .contact_manifolds(
        &Pose::translation(1.9, 0.98, 1.0),
        &g1,
        &g2,
        0.001,
        &mut manifolds,
        &mut None,
      )
      .expect("chunklet chunklet manifolds");
    let points: usize = manifolds.iter().map(|m| m.points.len()).sum();
    assert!(
      points > 0,
      "full cell next to a partial neighbor must still make contact"
    );
  }
}
