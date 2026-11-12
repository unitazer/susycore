use std::collections::HashMap;

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JDoubleArray, JIntArray};
use jni::sys::{jboolean, jdouble, jint};
use rapier3d::dynamics::RigidBodyHandle;
use rapier3d::glamx::Quat;
use rapier3d::math::{Pose, Vec3};
use rapier3d::prelude::{
  ActiveEvents, ColliderBuilder, ColliderHandle, Group, InteractionGroups, InteractionTestMode,
  RigidBodyBuilder,
};

use crate::block_collisions::{AIR_HANDLE, BlockColliderInfoHandle, COLLIDERS};
use crate::chunklet::Chunklet;
use crate::contact_forces::CONTACT_FORCE_EVENT_THRESHOLD;
use crate::scene::Scene;
use crate::terrain::{CHUNK_VOLUME, PackedChunkletCoords};
use crate::{EntityId, Real};

pub struct BodyEntry {
  pub rb: RigidBodyHandle,
  pub chunks: HashMap<PackedChunkletCoords, (Chunklet, ColliderHandle)>,
  pub anchor: Option<ColliderHandle>,
}

pub const BLOCK_DENSITY: Real = 1000.0;
pub const POSE_BUFFER_LEN: usize = 11;
pub const ANCHOR_COLLIDER_HALF_EXTENT: Real = 0.0625;
const SOFT_CCD_PREDICTION: Real = 4.0;

impl Scene {
  pub fn create_chunklet_body(
    &mut self,
    entity: EntityId,
    pos: Vec3,
    rot: Quat,
  ) -> RigidBodyHandle {
    if self.chunklet_bodies.contains_key(&entity) {
      log::warn!("chunklet body for {:?} already exists, replacing", entity);
      self.remove_chunklet_body(entity);
    }
    let handle = self.world.bodies.insert(
      RigidBodyBuilder::dynamic()
        .pose(Pose::from_parts(pos, rot))
        .linear_damping(0.1)
        .angular_damping(0.3)
        .ccd_enabled(true)
        .soft_ccd_prediction(SOFT_CCD_PREDICTION)
        .build(),
    );
    self.chunklet_bodies.insert(
      entity,
      BodyEntry {
        rb: handle,
        chunks: HashMap::new(),
        anchor: None,
      },
    );
    handle
  }

  pub fn set_body_damping(&mut self, entity: EntityId, linear: Real, angular: Real) -> bool {
    let Some(rb) = self.rb_of(entity) else {
      return false;
    };
    let Some(body) = self.world.bodies.get_mut(rb) else {
      return false;
    };
    body.set_linear_damping(linear);
    body.set_angular_damping(angular);
    true
  }

  pub fn set_body_gravity_scale(&mut self, entity: EntityId, scale: Real, wake_up: bool) -> bool {
    let Some(rb) = self.rb_of(entity) else {
      return false;
    };
    let Some(body) = self.world.bodies.get_mut(rb) else {
      return false;
    };
    body.set_gravity_scale(scale, wake_up);
    true
  }

  pub fn create_anchor_body(&mut self, entity: EntityId, pos: Vec3) -> RigidBodyHandle {
    if self.chunklet_bodies.contains_key(&entity) {
      log::warn!("chunklet body for {:?} already exists, replacing", entity);
      self.remove_chunklet_body(entity);
    }
    let handle = self.world.bodies.insert(
      RigidBodyBuilder::fixed()
        .pose(Pose::from_parts(pos, Quat::IDENTITY))
        .build(),
    );
    let anchor = self.world.colliders.insert_with_parent(
      ColliderBuilder::cuboid(
        ANCHOR_COLLIDER_HALF_EXTENT,
        ANCHOR_COLLIDER_HALF_EXTENT,
        ANCHOR_COLLIDER_HALF_EXTENT,
      )
      .collision_groups(InteractionGroups::new(
        Group::NONE,
        Group::NONE,
        InteractionTestMode::And,
      ))
      .build(),
      handle,
      &mut self.world.bodies,
    );
    self.chunklet_bodies.insert(
      entity,
      BodyEntry {
        rb: handle,
        chunks: HashMap::new(),
        anchor: Some(anchor),
      },
    );
    handle
  }

  pub fn create_carriage_body(&mut self, entity: EntityId, pos: Vec3) -> RigidBodyHandle {
    if self.chunklet_bodies.contains_key(&entity) {
      log::warn!("chunklet body for {:?} already exists, replacing", entity);
      self.remove_chunklet_body(entity);
    }
    let handle = self.world.bodies.insert(
      RigidBodyBuilder::dynamic()
        .pose(Pose::from_parts(pos, Quat::IDENTITY))
        .lock_translations()
        .gravity_scale(0.0)
        .linear_damping(0.5)
        .angular_damping(0.5)
        .build(),
    );
    let anchor = self.world.colliders.insert_with_parent(
      ColliderBuilder::cuboid(
        ANCHOR_COLLIDER_HALF_EXTENT,
        ANCHOR_COLLIDER_HALF_EXTENT,
        ANCHOR_COLLIDER_HALF_EXTENT,
      )
      .density(10000.0)
      .collision_groups(InteractionGroups::new(
        Group::NONE,
        Group::NONE,
        InteractionTestMode::And,
      ))
      .build(),
      handle,
      &mut self.world.bodies,
    );
    self.chunklet_bodies.insert(
      entity,
      BodyEntry {
        rb: handle,
        chunks: HashMap::new(),
        anchor: Some(anchor),
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
    let mut chunklet = Chunklet::new_with_blockhandle(data);
    let shape = chunklet.compose()?;
    let Some(entry) = self.chunklet_bodies.get_mut(&entity) else {
      log::error!("add_body_chunk on missing body {}", entity.0);
      return None;
    };
    let handle = self.world.colliders.insert_with_parent(
      ColliderBuilder::new(shape)
        .density(BLOCK_DENSITY)
        .translation(offset)
        .active_events(ActiveEvents::CONTACT_FORCE_EVENTS)
        .contact_force_event_threshold(CONTACT_FORCE_EVENT_THRESHOLD)
        .build(),
      entry.rb,
      &mut self.world.bodies,
    );
    if let Some((_, old)) = entry.chunks.insert(coords, (chunklet, handle)) {
      self.remove_collider(old);
    }
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
    let Some(entry) = self.chunklet_bodies.get_mut(&entity) else {
      log::error!("update_body_block on missing body {}", entity.0);
      return false;
    };
    let Some((chunk, handle)) = entry.chunks.get_mut(&coords) else {
      log::error!(
        "update_body_block on missing chunk {coords:?} of body {}",
        entity.0
      );
      return false;
    };
    let handle = *handle;
    let outcome = {
      let guard = COLLIDERS.read().unwrap();
      chunk.set(x, y, z, new_block.into_block(), &guard)
    };
    if !outcome.changed {
      return !outcome.emptied;
    }
    if outcome.emptied {
      self.remove_body_chunk(entity, coords);
      return false;
    }
    let Some(shape) = chunk.compose() else {
      self.remove_body_chunk(entity, coords);
      return false;
    };
    if let Some(co) = self.world.colliders.get_mut(handle) {
      co.set_shape(shape);
    }
    true
  }

  pub fn remove_body_chunk(&mut self, entity: EntityId, coords: PackedChunkletCoords) {
    let (handle, prune) = {
      let Some(entry) = self.chunklet_bodies.get_mut(&entity) else {
        return;
      };
      let Some((_, handle)) = entry.chunks.remove(&coords) else {
        return;
      };
      (handle, entry.chunks.is_empty() && entry.anchor.is_none())
    };
    self.remove_collider(handle);
    if prune {
      self.prune_joints_of(entity);
    }
  }

  fn remove_collider(&mut self, handle: ColliderHandle) {
    self.collider_owners.remove(&handle);
    self.world.colliders.remove(
      handle,
      &mut self.world.islands,
      &mut self.world.bodies,
      &mut self.world.soft_bodies,
      true,
    );
  }

  pub fn is_body_sleeping(&self, entity: EntityId) -> bool {
    self
      .rb_of(entity)
      .and_then(|rb| self.world.bodies.get(rb))
      .is_some_and(|body| body.is_sleeping())
  }

  pub fn remove_chunklet_body(&mut self, entity: EntityId) {
    let Some(entry) = self.chunklet_bodies.remove(&entity) else {
      return;
    };
    for (_, handle) in entry.chunks.into_values() {
      self.collider_owners.remove(&handle);
    }
    if let Some(handle) = entry.anchor {
      self.collider_owners.remove(&handle);
    }
    self.world.bodies.remove(
      entry.rb,
      &mut self.world.islands,
      &mut self.world.colliders,
      &mut self.world.impulse_joints,
      &mut self.world.multibody_joints,
      &mut self.world.soft_bodies,
      true,
    );
    self.prune_joints_of(entity);
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

  pub(crate) fn rb_of(&self, entity: EntityId) -> Option<RigidBodyHandle> {
    self.chunklet_bodies.get(&entity).map(|e| e.rb)
  }
}

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
  let Some(coords) = PackedChunkletCoords::try_from_xyz(lx, ly, lz) else {
    log::warn!("addBodyChunk on out-of-range chunklet coords {lx} {ly} {lz}");
    return;
  };
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
  if !(0..16).contains(&x) || !(0..16).contains(&y) || !(0..16).contains(&z) {
    log::warn!("updateBodyChunkBlock on out-of-range block coords {x} {y} {z}");
    return false;
  }
  let entity = EntityId(tag);
  let Some(coords) = PackedChunkletCoords::try_from_xyz(lx, ly, lz) else {
    log::warn!("updateBodyChunkBlock on out-of-range chunklet coords {lx} {ly} {lz}");
    return false;
  };
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
  let Some(coords) = PackedChunkletCoords::try_from_xyz(lx, ly, lz) else {
    log::warn!("removeBodyChunk on out-of-range chunklet coords {lx} {ly} {lz}");
    return;
  };
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
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_addStaticAnchor(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  tag: jint,
  x: jdouble,
  y: jdouble,
  z: jdouble,
) -> jint {
  debug_assert!(world_id >= 0);
  if tag <= 0 {
    log::warn!("addStaticAnchor on invalid tag {tag}");
    return 0;
  }
  let handle = Scene::with_scene_mut(world_id as usize, |scene| {
    scene.create_anchor_body(EntityId(tag), Vec3::new(x as Real, y as Real, z as Real))
  });
  match handle {
    Some(_) => tag,
    None => {
      log::error!("addStaticAnchor on missing scene {world_id}");
      0
    }
  }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_removeStaticAnchor(
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
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_addCarriageBody(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  tag: jint,
  x: jdouble,
  y: jdouble,
  z: jdouble,
) -> jint {
  debug_assert!(world_id >= 0);
  if tag <= 0 {
    log::warn!("addCarriageBody on invalid tag {tag}");
    return 0;
  }
  let handle = Scene::with_scene_mut(world_id as usize, |scene| {
    scene.create_carriage_body(EntityId(tag), Vec3::new(x as Real, y as Real, z as Real))
  });
  match handle {
    Some(_) => tag,
    None => {
      log::error!("addCarriageBody on missing scene {world_id}");
      0
    }
  }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_removeCarriageBody(
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
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_setBodyDamping(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  tag: jint,
  linear: jdouble,
  angular: jdouble,
) -> bool {
  debug_assert!(world_id >= 0);
  Scene::with_scene_mut(world_id as usize, |scene| {
    scene.set_body_damping(EntityId(tag), linear as Real, angular as Real)
  })
  .unwrap_or(false)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_setBodyGravityScale(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  tag: jint,
  scale: jdouble,
  wake_up: jboolean,
) -> bool {
  debug_assert!(world_id >= 0);
  Scene::with_scene_mut(world_id as usize, |scene| {
    scene.set_body_gravity_scale(EntityId(tag), scale as Real, wake_up)
  })
  .unwrap_or(false)
}
