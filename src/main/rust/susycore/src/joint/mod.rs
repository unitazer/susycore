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

use crate::scene::Scene;
use crate::{EntityId, Real};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct JointId(pub u32);

fn parse_joint_id(value: jlong) -> Option<JointId> {
  u32::try_from(value).ok().filter(|&v| v != 0).map(JointId)
}

#[derive(Debug, Clone, Copy)]
pub struct JointRecord {
  pub handle: ImpulseJointHandle,
  pub a: EntityId,
  pub b: EntityId,
}

const AXIS_COUNT: usize = 6;
pub const JOINT_WIRE_BUF_LEN: usize = 62;

const WIRE_LOCKED_SHIFT: i32 = 0;
const WIRE_COUPLED_SHIFT: i32 = 6;
const WIRE_LIMIT_SHIFT: i32 = 12;
const WIRE_MOTOR_SHIFT: i32 = 18;
const WIRE_FORCE_SHIFT: i32 = 24;
const WIRE_CONTACTS_BIT: jint = 1 << 30;
const WIRE_MOTOR_OFFSET: usize = 12;
const WIRE_MOTOR_STRIDE: usize = 6;
const WIRE_FRAME_OFFSET: usize = 48;
const WIRE_FRAME_LEN: usize = 7;

const QUAT_NORM_TOLERANCE: Real = 0.01;

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

  fn rapier(self) -> JointAxis {
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

fn narrow_real(value: f64) -> Real {
  value.clamp(f64::from(f32::MIN), f64::from(f32::MAX)) as Real
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
      .all(f64::is_finite);
    if !finite || max_force < 0.0 {
      log::warn!(
        "joint motor invalid: force_based={force_based} pos={target_pos} vel={target_vel} stiffness={stiffness} damping={damping} max_force={max_force}"
      );
      return None;
    }
    Some(Self {
      force_based,
      target_pos: narrow_real(target_pos),
      target_vel: narrow_real(target_vel),
      stiffness: narrow_real(stiffness),
      damping: narrow_real(damping),
      max_force: narrow_real(max_force),
    })
  }

  fn from_wire(data: &[jdouble; JOINT_WIRE_BUF_LEN], base: usize, force_based: bool) -> Option<Self> {
    Self::validated(
      force_based,
      data[base],
      data[base + 1],
      data[base + 2],
      data[base + 3],
      data[base + 4],
    )
  }

  fn model(self) -> MotorModel {
    if self.force_based {
      MotorModel::ForceBased
    } else {
      MotorModel::AccelerationBased
    }
  }

  fn apply(self, joint: &mut GenericJoint, axis: JointAxis) {
    joint.set_motor(axis, self.target_pos, self.target_vel, self.stiffness, self.damping);
    joint.set_motor_max_force(axis, self.max_force);
    joint.set_motor_model(axis, self.model());
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

impl Default for JointSpec {
  fn default() -> Self {
    Self {
      axes: [AxisSpec::default(); AXIS_COUNT],
      frame_a: Pose::IDENTITY,
      frame_b: Pose::IDENTITY,
      contacts_enabled: false,
    }
  }
}

fn axis_bits(flags: jint, shift: i32) -> u8 {
  ((flags >> shift) & 0x3F) as u8
}

fn bit_set(bits: u8, index: usize) -> bool {
  bits & (1 << index) != 0
}

fn wire_frame(data: &[jdouble; JOINT_WIRE_BUF_LEN], offset: usize) -> Pose {
  Pose::from_parts(
    Vec3::new(data[offset] as Real, data[offset + 1] as Real, data[offset + 2] as Real),
    Quat::from_xyzw(
      data[offset + 3] as Real,
      data[offset + 4] as Real,
      data[offset + 5] as Real,
      data[offset + 6] as Real,
    ),
  )
}

fn valid_frame(frame: Pose) -> bool {
  let q = frame.rotation;
  if !frame.translation.is_finite() {
    log::warn!("joint frame translation not finite: {:?}", frame.translation);
    return false;
  }
  if !q.is_finite() || (q.length() - 1.0).abs() > QUAT_NORM_TOLERANCE {
    log::warn!("joint frame quaternion not normalized: {q:?}");
    return false;
  }
  true
}

impl JointSpec {
  pub fn new() -> Self {
    Self::default()
  }

  pub fn fixed() -> Self {
    let mut spec = Self::default();
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
    let locked = axis_bits(flags, WIRE_LOCKED_SHIFT);
    let coupled = axis_bits(flags, WIRE_COUPLED_SHIFT);
    let limit = axis_bits(flags, WIRE_LIMIT_SHIFT);
    let motor = axis_bits(flags, WIRE_MOTOR_SHIFT);
    let force = axis_bits(flags, WIRE_FORCE_SHIFT);
    let mut axes = [AxisSpec::default(); AXIS_COUNT];
    for (i, axis) in axes.iter_mut().enumerate() {
      axis.locked = bit_set(locked, i);
      axis.coupled = bit_set(coupled, i);
      if bit_set(limit, i) {
        let min = data[2 * i] as Real;
        let max = data[2 * i + 1] as Real;
        if !min.is_finite() || !max.is_finite() || min > max {
          log::warn!("joint limit on axis {i} invalid: [{min}, {max}]");
          return None;
        }
        axis.limits = Some((min, max));
      }
      if bit_set(motor, i) {
        axis.motor = Some(MotorSpec::from_wire(
          data,
          WIRE_MOTOR_OFFSET + i * WIRE_MOTOR_STRIDE,
          bit_set(force, i),
        )?);
      }
    }
    let spec = Self {
      axes,
      frame_a: wire_frame(data, WIRE_FRAME_OFFSET),
      frame_b: wire_frame(data, WIRE_FRAME_OFFSET + WIRE_FRAME_LEN),
      contacts_enabled: flags & WIRE_CONTACTS_BIT != 0,
    };
    (valid_frame(spec.frame_a) && valid_frame(spec.frame_b)).then_some(spec)
  }
}

impl Scene {
  fn joint_endpoint(&self, entity: EntityId) -> Option<RigidBodyHandle> {
    let Some(body) = self.chunklet_bodies.get(&entity) else {
      log::warn!("add_joint on missing body {}", entity.0);
      return None;
    };
    if body.chunks.is_empty() && body.anchor.is_none() {
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
    let mut locked = JointAxesMask::empty();
    let mut coupled = JointAxesMask::empty();
    let mut joint = GenericJointBuilder::new(JointAxesMask::empty())
      .contacts_enabled(spec.contacts_enabled)
      .local_frame1(spec.frame_a)
      .local_frame2(spec.frame_b)
      .0;
    for (axis, cfg) in Axis::ALL.into_iter().zip(spec.axes.iter()) {
      let rapier_axis = axis.rapier();
      let bit = JointAxesMask::from(rapier_axis);
      if cfg.locked {
        locked |= bit;
        if cfg.limits.is_some() || cfg.motor.is_some() {
          log::warn!("joint axis {} locked, ignoring limit/motor", axis.index());
        }
        continue;
      }
      if cfg.coupled {
        coupled |= bit;
      }
      if let Some((min, max)) = cfg.limits {
        joint.set_limits(rapier_axis, [min, max]);
      }
      if let Some(motor) = cfg.motor {
        motor.apply(&mut joint, rapier_axis);
      }
    }
    joint.lock_axes(locked);
    joint.coupled_axes = coupled;
    let id = JointId(self.next_joint_id);
    self.next_joint_id = self.next_joint_id.checked_add(1).or_else(|| {
      log::warn!("joint id space exhausted");
      None
    })?;
    let handle = self
      .world
      .impulse_joints
      .insert(rb_a, rb_b, GenericJointBuilder(joint), true);
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
      log::warn!("set_joint_motor on invalid axis {axis_index}");
      return false;
    };
    let Some(joint) = self.world.impulse_joints.get_mut(record.handle, true) else {
      log::warn!("set_joint_motor on stale handle {:?}", record.handle);
      return false;
    };
    let rapier_axis = axis.rapier();
    if joint.data.locked_axes.contains(JointAxesMask::from(rapier_axis)) {
      log::warn!("set_joint_motor on locked axis {axis_index}");
      return false;
    }
    spec.apply(&mut joint.data, rapier_axis);
    true
  }

  pub(crate) fn prune_joints_of(&mut self, entity: EntityId) {
    let dead: Vec<JointId> = self
      .joints
      .iter()
      .filter_map(|(&id, r)| (r.a == entity || r.b == entity).then_some(id))
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
  let ok = env
    .with_env(|env| -> Result<bool, jni::errors::Error> {
      if data.len(env)? != JOINT_WIRE_BUF_LEN {
        return Ok(false);
      }
      data.get_region(env, 0, &mut buffer)?;
      Ok(true)
    })
    .resolve::<ThrowRuntimeExAndDefault>();
  if !ok {
    log::warn!("addJoint data must be exactly {JOINT_WIRE_BUF_LEN} doubles");
    return 0;
  }
  let Some(spec) = JointSpec::from_wire(flags, &buffer) else {
    log::warn!("addJoint rejected malformed joint spec for bodies {tag_a} and {tag_b}");
    return 0;
  };
  match Scene::with_scene_mut(world_id as usize, |s| s.add_joint(EntityId(tag_a), EntityId(tag_b), &spec)).flatten() {
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
  Scene::with_scene_mut(world_id as usize, |s| s.remove_joint(id)).unwrap_or(false)
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
  let Some(spec) = MotorSpec::validated(force_based, target_pos, target_vel, stiffness, damping, max_force) else {
    return false;
  };
  Scene::with_scene_mut(world_id as usize, |s| s.set_joint_motor(id, axis_index, spec)).unwrap_or(false)
}
