use std::collections::HashMap;
use std::sync::Mutex;

use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jdouble, jint};
use rapier3d::geometry::ContactPair;
use rapier3d::math::Vec3;
use rapier3d::prelude::{
  ColliderHandle, ColliderSet, CollisionEvent, EventHandler, Real, RigidBodySet,
};

use crate::body::EntityId;
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
    if let Some(owner) = self.collider_owners.get(&handle) {
      return Some(owner.0);
    }
    let collider = colliders.get(handle)?;
    collider.parent().is_none().then_some(TERRAIN_TAG)
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
}

impl Scene {
  pub fn step(&mut self) {
    let collector = ForceEventCollector::new(&self.collider_owners);
    self.world.step_with_events(&(), &collector);
    self.pending_contact_forces.extend(collector.take());
    let len = self.pending_contact_forces.len();
    if len > MAX_PENDING_CONTACT_FORCES {
      let excess = len - MAX_PENDING_CONTACT_FORCES;
      log::warn!("dropping {excess} undrained contact force events");
      self.pending_contact_forces.drain(0..excess);
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

#[cfg(test)]
mod tests {
  use super::*;
  use crate::terrain::PackedChunkletCoords;
  use crate::{
    chunklet::Chunklet,
    test_util::{collider_handle_for_box, full_chunklet_data},
  };
  use rapier3d::glamx::Quat;

  fn scene() -> Scene {
    Scene::new(Vec3::new(0.0, -10.0, 0.0))
  }

  fn terrain_scene() -> Scene {
    let mut s = scene();
    let h = collider_handle_for_box([0.0; 3], [1.0; 3]);
    s.add_chunklet(
      0,
      0,
      0,
      Chunklet::new_with_blockhandle(full_chunklet_data(h)),
    );
    s
  }

  fn drop_body(s: &mut Scene, tag: i32, y: f32) {
    let h = collider_handle_for_box([0.0; 3], [1.0; 3]);
    s.create_chunklet_body(EntityId(tag), Vec3::new(0.0, y, 0.0), Quat::IDENTITY);
    s.add_body_chunk(
      EntityId(tag),
      PackedChunkletCoords::from_xyz(0, 0, 0),
      Vec3::ZERO,
      full_chunklet_data(h),
    );
  }

  #[test]
  fn dropped_body_emits_force_event_with_sane_contact_points() {
    let mut s = terrain_scene();
    drop_body(&mut s, 1, 32.0);
    let mut events = Vec::new();
    for _ in 0..300 {
      s.step();
      events = std::mem::take(&mut s.pending_contact_forces);
      if !events.is_empty() {
        break;
      }
    }
    assert!(!events.is_empty(), "expected at least one force event");
    let first = events.first().unwrap();
    assert!(
      first.force.is_finite() && first.force > 0.0,
      "force {} should be finite and positive",
      first.force
    );
    let owners = [first.tag_a, first.tag_b];
    assert!(
      owners.contains(&1) && owners.contains(&TERRAIN_TAG),
      "one side should be the body, the other terrain: {owners:?}"
    );
    for p in [first.point_a, first.point_b] {
      assert!(
        (-0.5..16.5).contains(&p.x) && (-0.5..16.5).contains(&p.z),
        "contact point should sit over the terrain chunklet: {p:?}"
      );
      assert!(
        (p.y - 16.0).abs() < 1.0,
        "contact point should be near the terrain surface: {p:?}"
      );
    }
  }

  #[test]
  fn resting_body_keeps_emitting_events() {
    let mut s = terrain_scene();
    drop_body(&mut s, 2, 16.0);
    for _ in 0..5 {
      s.step();
      let events = std::mem::take(&mut s.pending_contact_forces);
      assert!(
        !events.is_empty(),
        "a resting heavy body exceeds the threshold every step"
      );
      assert!(events.iter().all(|e| e.force.is_finite() && e.force > 0.0));
    }
  }

  #[test]
  fn separated_bodies_emit_no_events() {
    let mut s = scene();
    drop_body(&mut s, 3, 100.0);
    for _ in 0..20 {
      s.step();
    }
    assert!(s.pending_contact_forces.is_empty());
  }

  #[test]
  fn unregistered_collider_drops_its_force_events() {
    use rapier3d::prelude::{ActiveEvents, ColliderBuilder, RigidBodyBuilder};
    let mut s = Scene::new(Vec3::new(0.0, -10.0, 0.0));
    let platform = s.world.bodies.insert(RigidBodyBuilder::fixed().build());
    s.world.colliders.insert_with_parent(
      ColliderBuilder::cuboid(8.0, 1.0, 8.0)
        .active_events(ActiveEvents::CONTACT_FORCE_EVENTS)
        .contact_force_event_threshold(CONTACT_FORCE_EVENT_THRESHOLD)
        .build(),
      platform,
      &mut s.world.bodies,
    );
    drop_body(&mut s, 6, 32.0);
    for _ in 0..120 {
      s.step();
    }
    assert!(
      s.pending_contact_forces.is_empty(),
      "a collider with no owner entry must not be misattributed as terrain: {:?}",
      s.pending_contact_forces
    );
  }
}
