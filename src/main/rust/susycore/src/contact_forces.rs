use std::collections::HashMap;
use std::sync::Mutex;
use std::time::Instant;

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jdouble, jint};
use rapier3d::geometry::ContactPair;
use rapier3d::math::Vec3;
use rapier3d::prelude::{
  ColliderHandle, ColliderSet, CollisionEvent, EventHandler, Real, RigidBodySet,
};

use crate::EntityId;
use crate::scene::Scene;

pub const CONTACT_FORCE_EVENT_THRESHOLD: Real = 1.0e5;
pub const MAX_PENDING_CONTACT_FORCES: usize = 1024;
const CONTACT_POINT_SKIN: Real = 1.0e-3;
const DOUBLES_PER_EVENT: usize = 9;
const TERRAIN_TAG: jint = -1;

#[derive(Clone, Copy, Debug)]
pub struct ContactForceRecord {
  pub force: Real,
  pub tag_a: jint,
  pub tag_b: jint,
  pub point_a: Vec3,
  pub point_b: Vec3,
}

pub struct ForceEventCollector<'a> {
  collider_owners: &'a HashMap<ColliderHandle, EntityId>,
  events: Mutex<Vec<ContactForceRecord>>,
}

impl<'a> ForceEventCollector<'a> {
  pub fn new(collider_owners: &'a HashMap<ColliderHandle, EntityId>) -> Self {
    Self {
      collider_owners,
      events: Mutex::new(Vec::new()),
    }
  }

  fn tag_of(&self, colliders: &ColliderSet, handle: ColliderHandle) -> Option<jint> {
    let collider = colliders.get(handle)?;
    if collider.parent().is_some() {
      return self.collider_owners.get(&handle).copied().map(|o| o.0);
    }
    Some(TERRAIN_TAG)
  }

  pub fn take(self) -> Vec<ContactForceRecord> {
    self.events.into_inner().unwrap_or_default()
  }
}

impl EventHandler for ForceEventCollector<'_> {
  fn handle_collision_event(
    &self,
    _bodies: &RigidBodySet,
    _colliders: &ColliderSet,
    _event: CollisionEvent,
    _pair: Option<&ContactPair>,
  ) {
  }

  fn handle_contact_force_event(
    &self,
    _dt: Real,
    _bodies: &RigidBodySet,
    colliders: &ColliderSet,
    pair: &ContactPair,
    total_force_magnitude: Real,
  ) {
    let (Some(c1), Some(c2)) = (colliders.get(pair.collider1), colliders.get(pair.collider2))
    else {
      return;
    };
    let (Some(tag_a), Some(tag_b)) = (
      self.tag_of(colliders, pair.collider1),
      self.tag_of(colliders, pair.collider2),
    ) else {
      return;
    };
    let (pose1, pose2) = (*c1.position(), *c2.position());
    let mut best: Option<(Real, Vec3, Vec3)> = None;
    for manifold in pair.solver_manifolds() {
      let normal = manifold.data.normal;
      for contact in &manifold.points {
        let impulse = contact.data.impulse;
        if best.is_some_and(|(b, _, _)| impulse <= b) {
          continue;
        }
        let local1 = manifold
          .subshape_pos1()
          .map_or(contact.local_p1, |s| s.transform_point(contact.local_p1));
        let local2 = manifold
          .subshape_pos2()
          .map_or(contact.local_p2, |s| s.transform_point(contact.local_p2));
        let p1 = pose1.transform_point(local1) - normal * CONTACT_POINT_SKIN;
        let p2 = pose2.transform_point(local2) + normal * CONTACT_POINT_SKIN;
        best = Some((impulse, p1, p2));
      }
    }
    let Some((_, point_a, point_b)) = best else {
      return;
    };
    self.events.lock().unwrap().push(ContactForceRecord {
      force: total_force_magnitude,
      tag_a,
      tag_b,
      point_a,
      point_b,
    });
  }

  fn handle_soft_body_tear_event(
    &self,
    _soft_bodies: &rapier3d::prelude::SoftBodySet,
    _event: &rapier3d::prelude::SoftBodyTearEvent,
  ) {
    todo!()
  }
}

impl Scene {
  pub fn step(&mut self) {
    let t0 = Instant::now();
    let collector = ForceEventCollector::new(&self.collider_owners);
    self.world.step_with_events(&(), &collector);
    let step_ms = t0.elapsed().as_secs_f64() * 1e3;
    let drain_t = Instant::now();
    self.pending_contact_forces.extend(collector.take());
    let len = self.pending_contact_forces.len();
    if len > MAX_PENDING_CONTACT_FORCES {
      let excess = len - MAX_PENDING_CONTACT_FORCES;
      log::warn!("dropping {excess} undrained contact force events");
      self.pending_contact_forces.drain(0..excess);
    }
    let drain_ms = drain_t.elapsed().as_secs_f64() * 1e3;
    if step_ms + drain_ms > 10.0 {
      log::warn!(
        "rust phys step detail: sim={:.3}ms drain={:.3}ms bodies={} colliders={} chunk_bodies={} terrain_chunks={}",
        step_ms,
        drain_ms,
        self.world.bodies.len(),
        self.world.colliders.len(),
        self.chunklet_bodies.len(),
        self.terrain.chunks.len(),
      );
    }
  }

  fn write_contact_forces(&mut self, out: &mut Vec<jdouble>, out_len: usize) -> jint {
    let count = self.pending_contact_forces.len();
    if count * DOUBLES_PER_EVENT > out_len {
      return -1;
    }
    let events = std::mem::take(&mut self.pending_contact_forces);
    for ev in &events {
      out.push(ev.force as f64);
      out.push(ev.tag_a as jdouble);
      out.push(ev.tag_b as jdouble);
      out.extend([
        ev.point_a.x as f64,
        ev.point_a.y as f64,
        ev.point_a.z as f64,
      ]);
      out.extend([
        ev.point_b.x as f64,
        ev.point_b.y as f64,
        ev.point_b.z as f64,
      ]);
    }
    count as jint
  }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_drainContactForces(
  mut env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  out: JDoubleArray,
) -> jint {
  debug_assert!(world_id >= 0);
  let out_len = env
    .with_env(|env| out.len(env))
    .resolve::<ThrowRuntimeExAndDefault>();
  let mut flat: Vec<jdouble> = Vec::with_capacity(out_len);
  let count = Scene::with_scene_mut(world_id as usize, |scene| {
    scene.write_contact_forces(&mut flat, out_len)
  })
  .unwrap_or(0);
  if count >= 0 && !flat.is_empty() {
    env
      .with_env(|env| out.set_region(env, 0, &flat))
      .resolve::<ThrowRuntimeExAndDefault>();
  }
  count
}
