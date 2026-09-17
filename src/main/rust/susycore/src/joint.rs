use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jboolean, jdouble, jint, jlong};
use rapier3d::glamx::Quat;
use rapier3d::math::{Pose, Vec3};
use rapier3d::prelude::{
  GenericJoint, GenericJointBuilder, ImpulseJointHandle, JointAxesMask, JointAxis, MotorModel,
};

use crate::Real;
use crate::body::EntityId;
use crate::scene::Scene;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct JointId(pub u32);

#[derive(Debug, Clone, Copy)]
pub struct JointRecord {
  pub handle: ImpulseJointHandle,
  pub a: EntityId,
  pub b: EntityId,
}

const AXIS_COUNT: usize = 6;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Axis {
  LinX,
  LinY,
  LinZ,
  AngX,
  AngY,
  AngZ,
}

impl Axis {
  pub const ALL: [Axis; AXIS_COUNT] = [
    Axis::LinX,
    Axis::LinY,
    Axis::LinZ,
    Axis::AngX,
    Axis::AngY,
    Axis::AngZ,
  ];

  pub fn index(self) -> usize {
    self as usize
  }

  fn from_index(index: usize) -> Option<Self> {
    Self::ALL.get(index).copied()
  }

  fn rapier_axis(self) -> JointAxis {
    match self {
      Axis::LinX => JointAxis::LinX,
      Axis::LinY => JointAxis::LinY,
      Axis::LinZ => JointAxis::LinZ,
      Axis::AngX => JointAxis::AngX,
      Axis::AngY => JointAxis::AngY,
      Axis::AngZ => JointAxis::AngZ,
    }
  }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct MotorSpec {
  pub force_based: bool,
  pub target_pos: Real,
  pub target_vel: Real,
  pub stiffness: Real,
  pub damping: Real,
  pub max_force: Real,
}

fn narrow_to_real(value: f64) -> Real {
  value.clamp(-(f32::MAX as f64), f32::MAX as f64) as Real
}

impl MotorSpec {
  fn validated(
    force_based: bool,
    target_pos: f64,
    target_vel: f64,
    stiffness: f64,
    damping: f64,
    max_force: f64,
  ) -> Option<Self> {
    let finite = [target_pos, target_vel, stiffness, damping, max_force]
      .into_iter()
      .all(|value| value.is_finite());
    if !finite || max_force < 0.0 {
      log::warn!(
        "joint motor is not finite or has negative max_force: force_based={force_based} \
         target_pos={target_pos} target_vel={target_vel} stiffness={stiffness} damping={damping} \
         max_force={max_force}"
      );
      return None;
    }
    Some(Self {
      force_based,
      target_pos: narrow_to_real(target_pos),
      target_vel: narrow_to_real(target_vel),
      stiffness: narrow_to_real(stiffness),
      damping: narrow_to_real(damping),
      max_force: narrow_to_real(max_force),
    })
  }

  fn rapier_model(self) -> MotorModel {
    if self.force_based {
      MotorModel::ForceBased
    } else {
      MotorModel::AccelerationBased
    }
  }
}

#[derive(Debug, Clone, Copy, PartialEq, Default)]
pub struct AxisSpec {
  pub locked: bool,
  pub coupled: bool,
  pub limits: Option<(Real, Real)>,
  pub motor: Option<MotorSpec>,
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub struct JointSpec {
  pub axes: [AxisSpec; AXIS_COUNT],
  pub frame_a: Pose,
  pub frame_b: Pose,
  pub contacts_enabled: bool,
}

const QUAT_NORMALIZED_TOLERANCE: Real = 0.01;
const MOTOR_WIRE_OFFSET: usize = 12;
const MOTOR_WIRE_STRIDE: usize = 6;
const FRAME_WIRE_OFFSET: usize = 48;
const FRAME_WIRE_LEN: usize = 7;
pub const JOINT_WIRE_LEN: usize = 62;

fn axis_mask(flags: jint, shift: i32) -> u8 {
  ((flags >> shift) & 0x3F) as u8
}

fn motor_from_wire(
  data: &[jdouble; JOINT_WIRE_LEN],
  base: usize,
  force_based: bool,
) -> Option<MotorSpec> {
  MotorSpec::validated(
    force_based,
    data[base],
    data[base + 1],
    data[base + 2],
    data[base + 3],
    data[base + 4],
  )
}

fn frame_from_wire(data: &[jdouble; JOINT_WIRE_LEN], offset: usize) -> Pose {
  Pose::from_parts(
    Vec3::new(
      data[offset] as Real,
      data[offset + 1] as Real,
      data[offset + 2] as Real,
    ),
    Quat::from_xyzw(
      data[offset + 3] as Real,
      data[offset + 4] as Real,
      data[offset + 5] as Real,
      data[offset + 6] as Real,
    ),
  )
}

impl JointSpec {
  #[allow(clippy::new_without_default)]
  pub fn new() -> Self {
    Self {
      axes: [AxisSpec::default(); AXIS_COUNT],
      frame_a: Pose::IDENTITY,
      frame_b: Pose::IDENTITY,
      contacts_enabled: false,
    }
  }

  pub fn fixed() -> Self {
    let mut spec = Self::new();
    for axis in &mut spec.axes {
      axis.locked = true;
    }
    spec
  }

  pub fn lock(mut self, axis: Axis) -> Self {
    self.axes[axis.index()].locked = true;
    self
  }

  pub fn motor(mut self, axis: Axis, motor: MotorSpec) -> Self {
    self.axes[axis.index()].motor = Some(motor);
    self
  }

  fn from_wire(flags: jint, data: &[jdouble; JOINT_WIRE_LEN]) -> Option<Self> {
    let locked_bits = axis_mask(flags, 0);
    let coupled_bits = axis_mask(flags, 6);
    let limit_bits = axis_mask(flags, 12);
    let motor_bits = axis_mask(flags, 18);
    let force_bits = axis_mask(flags, 24);
    let mut axes = [AxisSpec::default(); AXIS_COUNT];
    for (index, axis) in axes.iter_mut().enumerate() {
      axis.locked = locked_bits & (1 << index) != 0;
      axis.coupled = coupled_bits & (1 << index) != 0;
      if limit_bits & (1 << index) != 0 {
        let min = data[2 * index] as Real;
        let max = data[2 * index + 1] as Real;
        if !min.is_finite() || !max.is_finite() || min > max {
          log::warn!("joint limit on axis {index} is invalid: [{min}, {max}]");
          return None;
        }
        axis.limits = Some((min, max));
      }
      if motor_bits & (1 << index) != 0 {
        axis.motor = Some(motor_from_wire(
          data,
          MOTOR_WIRE_OFFSET + index * MOTOR_WIRE_STRIDE,
          force_bits & (1 << index) != 0,
        )?);
      }
    }
    let spec = Self {
      axes,
      frame_a: frame_from_wire(data, FRAME_WIRE_OFFSET),
      frame_b: frame_from_wire(data, FRAME_WIRE_OFFSET + FRAME_WIRE_LEN),
      contacts_enabled: flags & (1 << 30) != 0,
    };
    for frame in [spec.frame_a, spec.frame_b] {
      let quat = frame.rotation;
      if !frame.translation.is_finite() {
        log::warn!(
          "joint frame translation is not finite: {:?}",
          frame.translation
        );
        return None;
      }
      if !quat.is_finite() || (quat.length() - 1.0).abs() > QUAT_NORMALIZED_TOLERANCE {
        log::warn!("joint frame quaternion is not normalized: {quat:?}");
        return None;
      }
    }
    Some(spec)
  }
}

fn apply_motor(joint: &mut GenericJoint, axis: Axis, motor: MotorSpec) {
  let rapier_axis = axis.rapier_axis();
  joint.set_motor(
    rapier_axis,
    motor.target_pos,
    motor.target_vel,
    motor.stiffness,
    motor.damping,
  );
  joint.set_motor_max_force(rapier_axis, motor.max_force);
  joint.set_motor_model(rapier_axis, motor.rapier_model());
}

impl Scene {
  pub fn add_joint(&mut self, a: EntityId, b: EntityId, spec: &JointSpec) -> Option<JointId> {
    if a == b {
      log::warn!("add_joint with identical endpoints {}", a.0);
      return None;
    }
    let (Some(rb_a), Some(rb_b)) = (self.rb_of(a), self.rb_of(b)) else {
      log::warn!("add_joint on missing body {} or {}", a.0, b.0);
      return None;
    };
    for entity in [a, b] {
      if self
        .chunklet_bodies
        .get(&entity)
        .is_some_and(|entry| entry.colliders.is_empty())
      {
        log::warn!("add_joint on colliderless body {}", entity.0);
        return None;
      }
    }
    let mut locked_mask = JointAxesMask::empty();
    let mut coupled_mask = JointAxesMask::empty();
    let mut builder = GenericJointBuilder::new(locked_mask)
      .contacts_enabled(spec.contacts_enabled)
      .local_frame1(spec.frame_a)
      .local_frame2(spec.frame_b);
    for axis in Axis::ALL {
      let rapier_axis = axis.rapier_axis();
      let axis_spec = &spec.axes[axis.index()];
      if axis_spec.locked {
        locked_mask |= JointAxesMask::from(rapier_axis);
        if axis_spec.limits.is_some() || axis_spec.motor.is_some() {
          log::warn!(
            "joint axis {} is locked, ignoring limit and motor settings",
            axis.index()
          );
        }
        continue;
      }
      if axis_spec.coupled {
        coupled_mask |= JointAxesMask::from(rapier_axis);
      }
      if let Some((min, max)) = axis_spec.limits {
        builder = builder.limits(rapier_axis, [min, max]);
      }
      if let Some(motor) = axis_spec.motor {
        apply_motor(&mut builder.0, axis, motor);
      }
    }
    let builder = builder.locked_axes(locked_mask).coupled_axes(coupled_mask);
    let Some(next_id) = self.next_joint_id.checked_add(1) else {
      log::warn!("joint id space exhausted");
      return None;
    };
    let id = JointId(self.next_joint_id);
    self.next_joint_id = next_id;
    let handle = self.world.impulse_joints.insert(rb_a, rb_b, builder, true);
    self.joints.insert(id, JointRecord { handle, a, b });
    Some(id)
  }

  pub fn remove_joint(&mut self, id: JointId) -> bool {
    let Some(record) = self.joints.remove(&id) else {
      return false;
    };
    self.world.remove_impulse_joint(record.handle).is_some()
  }

  pub fn set_joint_motor(&mut self, id: JointId, axis_index: usize, spec: MotorSpec) -> bool {
    let Some(record) = self.joints.get(&id) else {
      log::warn!("set_joint_motor on missing joint {:?}", id);
      return false;
    };
    let Some(axis) = Axis::from_index(axis_index) else {
      log::warn!("set_joint_motor on invalid axis {}", axis_index);
      return false;
    };
    let Some(joint) = self.world.impulse_joints.get_mut(record.handle, true) else {
      log::warn!("set_joint_motor on stale handle {:?}", record.handle);
      return false;
    };
    let rapier_axis = axis.rapier_axis();
    if joint
      .data
      .locked_axes
      .contains(JointAxesMask::from(rapier_axis))
    {
      log::warn!("set_joint_motor on locked axis {}", axis_index);
      return false;
    }
    apply_motor(&mut joint.data, axis, spec);
    true
  }

  pub(crate) fn prune_joints_of(&mut self, entity: EntityId) {
    if self.joints.is_empty() {
      return;
    }
    let dead: Vec<JointId> = self
      .joints
      .iter()
      .filter(|(_, record)| record.a == entity || record.b == entity)
      .map(|(id, _)| *id)
      .collect();
    for id in dead {
      if let Some(record) = self.joints.remove(&id) {
        self.world.remove_impulse_joint(record.handle);
      }
    }
  }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_addJoint(
  mut env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  tag_a: jint,
  tag_b: jint,
  flags: jint,
  data: JDoubleArray,
) -> jlong {
  debug_assert!(world_id >= 0);
  let mut buffer = [0.0; JOINT_WIRE_LEN];
  let read = env
    .with_env(|env| -> Result<bool, jni::errors::Error> {
      if data.len(env)? != JOINT_WIRE_LEN {
        return Ok(false);
      }
      data.get_region(env, 0, &mut buffer)?;
      Ok(true)
    })
    .resolve::<ThrowRuntimeExAndDefault>();
  if !read {
    log::warn!("addJoint data must be exactly {JOINT_WIRE_LEN} doubles");
    return 0;
  }
  let Some(spec) = JointSpec::from_wire(flags, &buffer) else {
    log::warn!("addJoint rejected malformed joint spec for bodies {tag_a} and {tag_b}");
    return 0;
  };
  let id = Scene::with_scene_mut(world_id as usize, |scene| {
    scene.add_joint(EntityId(tag_a), EntityId(tag_b), &spec)
  })
  .flatten();
  match id {
    Some(id) => id.0 as jlong,
    None => {
      log::warn!("addJoint failed for bodies {tag_a} and {tag_b} in world {world_id}");
      0
    }
  }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_removeJoint(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  joint: jlong,
) -> bool {
  debug_assert!(world_id >= 0);
  if joint <= 0 || joint > u32::MAX as i64 {
    log::warn!("removeJoint on invalid joint id {joint}");
    return false;
  }
  Scene::with_scene_mut(world_id as usize, |scene| {
    scene.remove_joint(JointId(joint as u32))
  })
  .unwrap_or(false)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_supersymmetry_api_phys_Rapier_setJointMotor(
  _env: EnvUnowned,
  _class: JClass,
  world_id: jint,
  joint: jlong,
  axis: jint,
  target_pos: jdouble,
  target_vel: jdouble,
  stiffness: jdouble,
  damping: jdouble,
  max_force: jdouble,
  force_based: jboolean,
) -> bool {
  debug_assert!(world_id >= 0);
  if joint <= 0 || joint > u32::MAX as i64 {
    log::warn!("setJointMotor on invalid joint id {joint}");
    return false;
  }
  let Ok(axis_index) = usize::try_from(axis) else {
    log::warn!("setJointMotor on invalid axis {axis}");
    return false;
  };
  let Some(spec) = MotorSpec::validated(
    force_based,
    target_pos,
    target_vel,
    stiffness,
    damping,
    max_force,
  ) else {
    return false;
  };
  Scene::with_scene_mut(world_id as usize, |scene| {
    scene.set_joint_motor(JointId(joint as u32), axis_index, spec)
  })
  .unwrap_or(false)
}

#[cfg(test)]
mod tests {
  use super::*;
  use crate::block_collisions::{AIR_HANDLE, BlockColliderInfoHandle};
  use crate::chunklet::Chunklet;
  use crate::terrain::{CHUNK_VOLUME, PackedChunkletCoords};
  use crate::test_util::{collider_handle_for_box, full_chunklet_data};

  fn solid_handle() -> BlockColliderInfoHandle {
    collider_handle_for_box([0.0; 3], [1.0; 3])
  }

  fn single_block_data(h: BlockColliderInfoHandle) -> [BlockColliderInfoHandle; CHUNK_VOLUME] {
    let mut data = [AIR_HANDLE; CHUNK_VOLUME];
    data[Chunklet::index(0, 0, 0) as usize] = h;
    data
  }

  fn add_solid_body(s: &mut Scene, entity: EntityId, pos: Vec3) {
    s.create_chunklet_body(entity, pos, Quat::IDENTITY);
    s.add_body_chunk(
      entity,
      PackedChunkletCoords::from_xyz(0, 0, 0),
      Vec3::ZERO,
      full_chunklet_data(solid_handle()),
    );
  }

  fn add_light_body(s: &mut Scene, entity: EntityId, pos: Vec3) {
    s.create_chunklet_body(entity, pos, Quat::IDENTITY);
    s.add_body_chunk(
      entity,
      PackedChunkletCoords::from_xyz(0, 0, 0),
      Vec3::ZERO,
      single_block_data(solid_handle()),
    );
  }

  fn body_pose(s: &Scene, entity: EntityId) -> (Vec3, Quat) {
    let rb = s.chunklet_bodies.get(&entity).unwrap().rb;
    let body = s.world.bodies.get(rb).unwrap();
    let pose = body.position();
    (pose.translation, pose.rotation)
  }

  fn vel_of(s: &Scene, entity: EntityId) -> Vec3 {
    let rb = s.chunklet_bodies.get(&entity).unwrap().rb;
    s.world.bodies.get(rb).unwrap().linvel()
  }

  #[test]
  fn wire_flags_and_data_parse() {
    let mut data = [0.0; JOINT_WIRE_LEN];
    data[0] = -1.0;
    data[1] = 2.0;
    data[12] = 5.0;
    data[13] = 1.0;
    data[14] = 2.0;
    data[15] = 3.0;
    data[16] = 4.0;
    data[48] = 1.0;
    data[51] = 0.5;
    data[54] = 0.8660254;
    data[55] = 9.0;
    data[61] = 1.0;
    let flags = 0x3F | (0b000011 << 6) | (1 << 12) | (1 << 18) | (1 << 24) | (1 << 30);
    let spec = JointSpec::from_wire(flags, &data).unwrap();
    let lin_x = &spec.axes[Axis::LinX.index()];
    assert!(lin_x.locked && lin_x.coupled && spec.contacts_enabled);
    assert_eq!(lin_x.limits, Some((-1.0, 2.0)));
    let motor = lin_x.motor.unwrap();
    assert!(motor.force_based);
    assert_eq!(
      (
        motor.target_pos,
        motor.target_vel,
        motor.stiffness,
        motor.damping,
        motor.max_force
      ),
      (5.0, 1.0, 2.0, 3.0, 4.0)
    );
    let lin_y = &spec.axes[Axis::LinY.index()];
    assert!(lin_y.coupled && lin_y.limits.is_none() && lin_y.motor.is_none());
    assert!(spec.axes[Axis::LinZ.index()].motor.is_none());
    assert!(spec.axes.iter().all(|axis| axis.locked));
    assert_eq!(spec.frame_a.translation.x, 1.0);
    assert_eq!(spec.frame_a.rotation.x, 0.5);
    assert_eq!(spec.frame_a.rotation.w, 0.8660254);
    assert_eq!(spec.frame_b.translation.x, 9.0);
    assert_eq!(spec.frame_b.rotation.w, 1.0);
  }

  #[test]
  fn fixed_joint_holds_stack_under_gravity() {
    let mut s = Scene::new(Vec3::new(0.0, -10.0, 0.0));
    s.add_chunklet(
      0,
      0,
      0,
      Chunklet::new_with_blockhandle(full_chunklet_data(solid_handle())),
    );
    add_solid_body(&mut s, EntityId(1), Vec3::new(0.0, 16.0, 0.0));
    add_solid_body(&mut s, EntityId(2), Vec3::new(0.0, 32.0, 0.0));
    let mut spec = JointSpec::fixed();
    spec.frame_a = Pose::from_parts(Vec3::new(0.0, 16.0, 0.0), Quat::IDENTITY);
    let id = s.add_joint(EntityId(1), EntityId(2), &spec).expect("joint");
    assert_eq!(id.0, 1);
    for _ in 0..200 {
      s.world.step();
    }
    let (pos_a, rot_a) = body_pose(&s, EntityId(1));
    let (pos_b, rot_b) = body_pose(&s, EntityId(2));
    assert!(
      (pos_b.y - (pos_a.y + 16.0)).abs() < 0.5,
      "stack must stay connected, a={pos_a:?} b={pos_b:?}"
    );
    assert!(
      pos_b.y > 25.0,
      "upper body must not fall, a={pos_a:?} b={pos_b:?}"
    );
    assert!(rot_a.angle_between(rot_b) < 0.05);
  }

  #[test]
  fn locked_axis_stays_fixed_while_free_axes_rotate() {
    let mut s = Scene::new(Vec3::ZERO);
    add_light_body(&mut s, EntityId(1), Vec3::ZERO);
    add_light_body(&mut s, EntityId(2), Vec3::new(0.0, 5.0, 0.0));
    let mut spec = JointSpec::new()
      .lock(Axis::LinX)
      .lock(Axis::LinY)
      .lock(Axis::LinZ)
      .lock(Axis::AngZ);
    spec.frame_b = Pose::from_parts(Vec3::new(0.0, -5.0, 0.0), Quat::IDENTITY);
    s.add_joint(EntityId(1), EntityId(2), &spec).expect("joint");
    let rb_a = s.chunklet_bodies.get(&EntityId(1)).unwrap().rb;
    let rb_b = s.chunklet_bodies.get(&EntityId(2)).unwrap().rb;
    s.world
      .bodies
      .get_mut(rb_a)
      .unwrap()
      .apply_torque_impulse(Vec3::new(-40.0, 0.0, 0.0), true);
    s.world
      .bodies
      .get_mut(rb_b)
      .unwrap()
      .apply_torque_impulse(Vec3::new(40.0, 0.0, 0.0), true);
    for _ in 0..20 {
      s.world.step();
    }
    let (pos_a, rot_a) = body_pose(&s, EntityId(1));
    let (pos_b, rot_b) = body_pose(&s, EntityId(2));
    let rel = rot_a.inverse() * rot_b;
    assert!(rel.x.abs() > 0.05, "free axis must rotate, rot={rel:?}");
    assert!(
      rel.z.abs() < 1e-2,
      "locked axis must stay fixed, rot={rel:?}"
    );
    assert!(
      (pos_b.y - pos_a.y - 5.0).abs() < 1e-2,
      "locked linear axis must stay fixed, a={pos_a:?} b={pos_b:?}"
    );
  }

  #[test]
  fn force_based_spring_pulls_child_to_rest_offset() {
    let mut s = Scene::new(Vec3::ZERO);
    add_light_body(&mut s, EntityId(1), Vec3::ZERO);
    add_light_body(&mut s, EntityId(2), Vec3::new(14.0, 0.0, 0.0));
    let spring = MotorSpec {
      force_based: true,
      target_pos: 0.0,
      target_vel: 0.0,
      stiffness: 5000.0,
      damping: 4000.0,
      max_force: 1.0e9,
    };
    let spec = JointSpec::new()
      .motor(
        Axis::LinX,
        MotorSpec {
          target_pos: 10.0,
          ..spring
        },
      )
      .motor(Axis::LinY, spring)
      .motor(Axis::LinZ, spring);
    let id = s.add_joint(EntityId(1), EntityId(2), &spec).expect("joint");
    let handle = s.joints.get(&id).map(|r| r.handle).expect("handle");
    for axis in [Axis::LinX, Axis::LinY, Axis::LinZ] {
      let motor = s
        .world
        .impulse_joints
        .get(handle)
        .unwrap()
        .data
        .motor(axis.rapier_axis())
        .unwrap();
      assert_eq!(motor.model, MotorModel::ForceBased);
    }
    for _ in 0..60 {
      s.world.step();
    }
    let (pos_a, _) = body_pose(&s, EntityId(1));
    let (pos_b, _) = body_pose(&s, EntityId(2));
    let separation = pos_b.x - pos_a.x;
    assert!(
      (separation - 10.0).abs() < 2.0,
      "spring must pull toward rest offset, separation={separation}"
    );
  }

  #[test]
  fn joint_pruned_when_endpoint_body_removed() {
    let mut s = Scene::new(Vec3::ZERO);
    add_light_body(&mut s, EntityId(1), Vec3::ZERO);
    add_light_body(&mut s, EntityId(2), Vec3::new(5.0, 0.0, 0.0));
    let spec = JointSpec::new();
    let id = s.add_joint(EntityId(1), EntityId(2), &spec).expect("joint");
    let handle = s.joints.get(&id).map(|r| r.handle).expect("handle");
    assert_eq!(s.joints.len(), 1);
    s.remove_chunklet_body(EntityId(1));
    assert!(s.joints.is_empty());
    assert!(s.world.impulse_joints.get(handle).is_none());
    s.remove_chunklet_body(EntityId(2));
    assert!(s.joints.is_empty());
  }

  #[test]
  fn removing_last_collider_keeps_body_and_prunes_joints() {
    let mut s = Scene::new(Vec3::ZERO);
    add_light_body(&mut s, EntityId(4), Vec3::new(50.0, 0.0, 0.0));
    add_light_body(&mut s, EntityId(5), Vec3::new(70.0, 0.0, 0.0));
    let spec = JointSpec::new();
    let id = s.add_joint(EntityId(4), EntityId(5), &spec).expect("joint");
    let coords = PackedChunkletCoords::from_xyz(0, 0, 0);
    let pose_before = body_pose(&s, EntityId(4));
    let rb_before = s.chunklet_bodies.get(&EntityId(4)).unwrap().rb;
    s.update_body_block(EntityId(4), coords, 0, 0, 0, AIR_HANDLE);
    assert!(s.chunklet_bodies.contains_key(&EntityId(4)));
    assert!(!s.joints.contains_key(&id));
    assert!(
      s.add_joint(EntityId(4), EntityId(5), &spec).is_none(),
      "add_joint must reject a colliderless body"
    );
    assert!(
      s.add_body_chunk(
        EntityId(4),
        coords,
        Vec3::ZERO,
        single_block_data(solid_handle())
      )
      .is_some()
    );
    assert_eq!(body_pose(&s, EntityId(4)), pose_before);
    assert_eq!(s.chunklet_bodies.get(&EntityId(4)).unwrap().rb, rb_before);
    let id = s.add_joint(EntityId(4), EntityId(5), &spec).expect("joint");
    assert!(s.joints.contains_key(&id));
    s.remove_body_chunk(EntityId(4), coords);
    assert!(s.chunklet_bodies.contains_key(&EntityId(4)));
    assert!(!s.joints.contains_key(&id));
  }

  #[test]
  fn replacing_last_chunklet_keeps_joints() {
    let mut s = Scene::new(Vec3::ZERO);
    add_light_body(&mut s, EntityId(1), Vec3::ZERO);
    add_light_body(&mut s, EntityId(2), Vec3::new(5.0, 0.0, 0.0));
    let spec = JointSpec::new();
    let id = s.add_joint(EntityId(1), EntityId(2), &spec).expect("joint");
    let coords = PackedChunkletCoords::from_xyz(0, 0, 0);
    assert!(
      s.add_body_chunk(
        EntityId(1),
        coords,
        Vec3::ZERO,
        single_block_data(solid_handle())
      )
      .is_some()
    );
    assert!(s.joints.contains_key(&id));
  }

  #[test]
  fn from_wire_rejects_bad_frames_and_limits() {
    let mut data = [0.0; JOINT_WIRE_LEN];
    let flags = 1 << 12;
    data[0] = 2.0;
    data[1] = 1.0;
    assert!(JointSpec::from_wire(flags, &data).is_none());
    let mut data = [0.0; JOINT_WIRE_LEN];
    data[51] = f64::NAN;
    assert!(JointSpec::from_wire(0, &data).is_none());
    let mut data = [0.0; JOINT_WIRE_LEN];
    data[51] = 5.0;
    assert!(JointSpec::from_wire(0, &data).is_none());
  }

  #[test]
  fn set_joint_motor_retunes() {
    let mut s = Scene::new(Vec3::ZERO);
    add_light_body(&mut s, EntityId(1), Vec3::ZERO);
    add_light_body(&mut s, EntityId(2), Vec3::new(3.0, 0.0, 0.0));
    let hold = MotorSpec {
      force_based: false,
      target_pos: 0.0,
      target_vel: 0.0,
      stiffness: 0.0,
      damping: 10.0,
      max_force: 1.0e6,
    };
    let id = s
      .add_joint(
        EntityId(1),
        EntityId(2),
        &JointSpec::new().motor(Axis::LinX, hold),
      )
      .expect("joint");
    let handle = s.joints.get(&id).map(|r| r.handle).expect("handle");
    for _ in 0..10 {
      s.world.step();
    }
    let relative_vel = |s: &Scene| vel_of(s, EntityId(2)).x - vel_of(s, EntityId(1)).x;
    assert!(
      relative_vel(&s).abs() < 0.5,
      "motor at rest target must hold the bodies still"
    );
    assert!(s.set_joint_motor(
      id,
      0,
      MotorSpec {
        target_vel: 2.0,
        ..hold
      }
    ));
    let motor = s
      .world
      .impulse_joints
      .get(handle)
      .unwrap()
      .data
      .motor(Axis::LinX.rapier_axis())
      .unwrap();
    assert_eq!(motor.target_vel, 2.0);
    for _ in 0..40 {
      s.world.step();
    }
    assert!(
      relative_vel(&s) > 1.0,
      "retuned motor must drive relative velocity, got {}",
      relative_vel(&s)
    );
    assert!(!s.set_joint_motor(id, 9, hold));
    assert!(!s.set_joint_motor(JointId(999), 0, hold));
  }

  #[test]
  fn wire_motor_survives_unlimited_force_sentinel() {
    let flags = (1 << 18) | (1 << 24);
    let mut data = [0.0; JOINT_WIRE_LEN];
    data[16] = 1.7976931348623157e308;
    data[54] = 1.0;
    data[61] = 1.0;
    let spec = JointSpec::from_wire(flags, &data).expect("unlimited-force motor must parse");
    let motor = spec.axes[Axis::LinX.index()].motor.unwrap();
    assert_eq!(motor.max_force, Real::MAX);
    assert!(motor.max_force.is_finite());

    let mut s = Scene::new(Vec3::ZERO);
    add_light_body(&mut s, EntityId(1), Vec3::ZERO);
    add_light_body(&mut s, EntityId(2), Vec3::new(3.0, 0.0, 0.0));
    let id = s.add_joint(EntityId(1), EntityId(2), &spec).expect("joint");
    let retune =
      MotorSpec::validated(false, 0.0, 0.0, 0.0, 5.0, 1.7976931348623157e308).expect("retune");
    assert_eq!(retune.max_force, Real::MAX);
    assert!(s.set_joint_motor(id, 0, retune));
  }

  #[test]
  fn wire_motor_rejects_nan_and_negative_max_force() {
    let flags = (1 << 18) | (1 << 24);
    let mut data = [0.0; JOINT_WIRE_LEN];
    data[16] = f64::NAN;
    assert!(JointSpec::from_wire(flags, &data).is_none());
    let mut data = [0.0; JOINT_WIRE_LEN];
    data[16] = -1.0;
    assert!(JointSpec::from_wire(flags, &data).is_none());
    assert!(
      MotorSpec::validated(false, 0.0, 0.0, 0.0, 0.0, f64::NAN).is_none(),
      "retune path must reject NaN max_force"
    );
    assert!(
      MotorSpec::validated(false, 0.0, 0.0, 0.0, 0.0, -1.0).is_none(),
      "retune path must reject negative max_force"
    );
  }
}
