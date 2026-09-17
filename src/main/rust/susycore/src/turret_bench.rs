use crate::Real;
use crate::block_collisions::AIR_HANDLE as AIR;
use crate::block_collisions::BlockColliderInfoHandle;
use crate::body::POSE_BUFFER_LEN;
use crate::body::{BLOCK_DENSITY, EntityId};
use crate::chunklet::Chunklet;
use crate::joint::{Axis, JointSpec, MotorSpec};
use crate::scene::Scene;
use crate::terrain::{CHUNK_VOLUME, PackedChunkletCoords};
use crate::test_util;
use rapier3d::glamx::Quat;
use rapier3d::math::Vec3;

fn casing_handle() -> BlockColliderInfoHandle {
  crate::block_collisions::MinecraftBlockColliderInfo::new(
    0.5,
    BLOCK_DENSITY,
    0.0,
    vec![rapier3d::parry::bounding_volume::Aabb::new(
      Vec3::ZERO,
      Vec3::splat(1.0),
    )],
  )
  .handle()
}

fn disc_layer(handle: BlockColliderInfoHandle) -> [BlockColliderInfoHandle; CHUNK_VOLUME] {
  let mut data = [AIR; CHUNK_VOLUME];
  for y in 0..1u32 {
    for z in 0..16u32 {
      for x in 0..16u32 {
        data[((y << 8) | (z << 4) | x) as usize] = handle;
      }
    }
  }
  data
}

fn gun_column(handle: BlockColliderInfoHandle) -> [BlockColliderInfoHandle; CHUNK_VOLUME] {
  let mut data = [AIR; CHUNK_VOLUME];
  for y in 0..10u32 {
    for z in 8..10u32 {
      for x in 8..10u32 {
        data[((y << 8) | (z << 4) | x) as usize] = handle;
      }
    }
  }
  data
}

#[test]
#[ignore = "performance benchmark: cargo test --release -- --ignored --nocapture turret_step_cost"]
fn turret_step_cost() {
  let h = casing_handle();
  let mut s = Scene::new(Vec3::new(0.0, -10.0, 0.0));

  let ground = test_util::full_chunklet_data(h);
  for cx in 0..3i32 {
    for cz in 0..3i32 {
      s.add_chunklet(cx, 0, cz, Chunklet::new_with_blockhandle(ground));
    }
  }

  s.create_chunklet_body(EntityId(1), Vec3::new(0.0, 16.0, 0.0), Quat::IDENTITY);
  for cx in 0..2i32 {
    for cz in 0..2i32 {
      s.add_body_chunk(
        EntityId(1),
        PackedChunkletCoords::from_xyz(cx, 0, cz),
        Vec3::new((cx * 16) as Real, 0.0, (cz * 16) as Real),
        disc_layer(h),
      );
    }
  }

  s.create_chunklet_body(EntityId(2), Vec3::new(24.0, 17.0, 24.0), Quat::IDENTITY);
  s.add_body_chunk(
    EntityId(2),
    PackedChunkletCoords::from_xyz(0, 0, 0),
    Vec3::ZERO,
    gun_column(h),
  );

  let unlimited = Real::MAX as f64;
  let spec = JointSpec::new()
    .lock(Axis::LinX)
    .lock(Axis::LinY)
    .lock(Axis::LinZ)
    .lock(Axis::AngZ)
    .motor(
      Axis::AngX,
      MotorSpec {
        force_based: true,
        target_pos: 0.0,
        target_vel: 0.0,
        stiffness: 30.0,
        damping: 8.0,
        max_force: unlimited as Real,
      },
    )
    .motor(
      Axis::AngY,
      MotorSpec {
        force_based: true,
        target_pos: 0.0,
        target_vel: 0.0,
        stiffness: 30.0,
        damping: 8.0,
        max_force: unlimited as Real,
      },
    );
  let joint = s.add_joint(EntityId(1), EntityId(2), &spec);
  assert!(joint.is_some(), "turret joint must instantiate");

  for _ in 0..60 {
    s.step();
  }

  let mut pose = [0.0f64; POSE_BUFFER_LEN];
  s.body_pose_into(EntityId(1), &mut pose);
  let mut awake_steps = 0u32;
  let steps = 300;
  let mut total = 0.0f64;
  let mut worst = 0.0f64;
  for _ in 0..steps {
    let t = std::time::Instant::now();
    s.step();
    let dt = t.elapsed().as_secs_f64() * 1000.0;
    if !s.is_body_sleeping(EntityId(1)) {
      awake_steps += 1;
    }
    total += dt;
    worst = worst.max(dt);
  }
  let avg = total / steps as f64;
  println!("disc y after settle: {:.4}", pose[1]);
  println!("awake steps: {awake_steps}/{steps}");
  println!("events pending at end: {}", s.pending_contact_forces.len());
  println!("avg step: {avg:.3} ms, worst: {worst:.3} ms over {steps} steps");
  assert!(
    avg < 15.0,
    "avg step {avg:.3} ms is way over the 15 ms budget"
  );
}
