
  use super::*;
  use crate::block_collisions::{AIR_HANDLE, BlockColliderInfoHandle};
  use crate::chunklet::Chunklet;
  use crate::terrain::{CHUNK_VOLUME, PackedChunkletCoords};
  use crate::test_util::collider_handle_for_box;

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
      {
        let handle = solid_handle();
        [handle; CHUNK_VOLUME]
      },
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
    let mut data = [0.0; JOINT_WIRE_BUF_LEN];
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
      Chunklet::new_with_blockhandle({
        let handle = solid_handle();
        [handle; CHUNK_VOLUME]
      }),
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
    let mut data = [0.0; JOINT_WIRE_BUF_LEN];
    let flags = 1 << 12;
    data[0] = 2.0;
    data[1] = 1.0;
    assert!(JointSpec::from_wire(flags, &data).is_none());
    let mut data = [0.0; JOINT_WIRE_BUF_LEN];
    data[51] = f64::NAN;
    assert!(JointSpec::from_wire(0, &data).is_none());
    let mut data = [0.0; JOINT_WIRE_BUF_LEN];
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
    let mut data = [0.0; JOINT_WIRE_BUF_LEN];
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
    let mut data = [0.0; JOINT_WIRE_BUF_LEN];
    data[16] = f64::NAN;
    assert!(JointSpec::from_wire(flags, &data).is_none());
    let mut data = [0.0; JOINT_WIRE_BUF_LEN];
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
