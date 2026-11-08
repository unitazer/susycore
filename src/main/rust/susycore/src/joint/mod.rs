use jni::EnvUnowned;
use jni::errors::ThrowRuntimeExAndDefault;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jboolean, jdouble, jint, jlong};
use rapier3d::glamx::Quat;
use rapier3d::math::{Pose, Vec3};
use rapier3d::prelude::{
  GenericJoint, GenericJointBuilder, ImpulseJointHandle, JointAxesMask, JointAxis, MotorModel,
  RigidBodyHandle,
};

use crate::{EntityId, Real};
use crate::scene::Scene;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct JointId(pub u32);

fn parse_joint_id(value: jlong) -> Option<JointId> {
  (1..=u32::MAX as i64)
    .contains(&value)
    .then_some(JointId(value as u32))
}

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

  fn from_wire(
    data: &[jdouble; JOINT_WIRE_BUF_LEN],
    base: usize,
    force_based: bool,
  ) -> Option<Self> {
    Self::validated(
      force_based,
      data[base],
      data[base + 1],
      data[base + 2],
      data[base + 3],
      data[base + 4],
    )
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
pub const JOINT_WIRE_BUF_LEN: usize = 62;

fn axis_mask(flags: jint, shift: i32) -> u8 {
  ((flags >> shift) & 0x3F) as u8
}

fn frame_from_wire(data: &[jdouble; JOINT_WIRE_BUF_LEN], offset: usize) -> Pose {
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

  fn from_wire(flags: jint, data: &[jdouble; JOINT_WIRE_BUF_LEN]) -> Option<Self> {
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
        axis.motor = Some(MotorSpec::from_wire(
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
  fn joint_endpoint(&self, entity: EntityId) -> Option<RigidBodyHandle> {
    let Some(body) = self.chunklet_bodies.get(&entity) else {
      log::warn!("add_joint on missing body {}", entity.0);
      return None;
    };
    if body.colliders.is_empty() {
      log::warn!("add_joint on colliderless body {}", entity.0);
      return None;
    }
    Some(body.rb)
  }

  pub fn add_joint(&mut self, a: EntityId, b: EntityId, spec: &JointSpec) -> Option<JointId> {
    if a == b {
      log::warn!("add_joint with identical endpoints {}", a.0);
      return None;
    }
    let rb_a = self.joint_endpoint(a)?;
    let rb_b = self.joint_endpoint(b)?;
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
  let mut buffer = [0.0; JOINT_WIRE_BUF_LEN];
  let read = env
    .with_env(|env| -> Result<bool, jni::errors::Error> {
      if data.len(env)? != JOINT_WIRE_BUF_LEN {
        return Ok(false);
      }
      data.get_region(env, 0, &mut buffer)?;
      Ok(true)
    })
    .resolve::<ThrowRuntimeExAndDefault>();
  if !read {
    log::warn!("addJoint data must be exactly {JOINT_WIRE_BUF_LEN} doubles");
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
  let Some(id) = parse_joint_id(joint) else {
    log::warn!("removeJoint on invalid joint id {joint}");
    return false;
  };
  Scene::with_scene_mut(world_id as usize, |scene| scene.remove_joint(id)).unwrap_or(false)
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
  let Some(id) = parse_joint_id(joint) else {
    log::warn!("setJointMotor on invalid joint id {joint}");
    return false;
  };
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
    scene.set_joint_motor(id, axis_index, spec)
  })
  .unwrap_or(false)
}

#[cfg(test)]
mod tests;
