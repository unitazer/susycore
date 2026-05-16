use std::time::Instant;

use itertools::Itertools;
use jni::sys::jdouble;
use jni::{JValue, jni_sig, jni_str};
use log::info;
use rapier3d::math::Vec3;
use rapier3d::parry::query::details::NormalConstraints;
use rapier3d::parry::query::{
  ClosestPoints, Contact, ContactManifold, ContactManifoldsWorkspace, DefaultQueryDispatcher,
  NonlinearRigidMotion, PersistentQueryDispatcher, QueryDispatcher, ShapeCastHit, ShapeCastOptions,
  Unsupported,
};
use rapier3d::prelude::{Aabb, ContactData, ContactManifoldData, Cuboid, Pose, Shape, ShapeType};
use rapier3d::utils::PoseOps;

use crate::block_collisions::{self, COLLIDERS};
use crate::chunklet::Chunklet;
use crate::{IVec3, JResult, Real, logger};

pub struct ChunkletDispatcher;
impl QueryDispatcher for ChunkletDispatcher {
  fn intersection_test(
    &self,
    _pos12: &Pose,
    g1: &dyn Shape,
    g2: &dyn Shape,
  ) -> Result<bool, Unsupported> {
    info!("intersect {:?} <-> {:?}", g1.shape_type(), g2.shape_type());
    Err(Unsupported)
  }

  fn distance(&self, _pos12: &Pose, g1: &dyn Shape, g2: &dyn Shape) -> Result<f32, Unsupported> {
    info!("distance {:?} <-> {:?}", g1.shape_type(), g2.shape_type());
    Err(Unsupported)
  }

  fn contact(
    &self,
    _pos12: &Pose,
    g1: &dyn Shape,
    g2: &dyn Shape,
    _prediction: f32,
  ) -> Result<Option<Contact>, Unsupported> {
    info!("contact {:?} <-> {:?}", g1.shape_type(), g2.shape_type());
    Err(Unsupported)
  }

  fn closest_points(
    &self,
    _pos12: &Pose,
    g1: &dyn Shape,
    g2: &dyn Shape,
    _max_dist: f32,
  ) -> Result<ClosestPoints, Unsupported> {
    info!(
      "closest points {:?} <-> {:?}",
      g1.shape_type(),
      g2.shape_type()
    );
    Err(Unsupported)
  }

  fn cast_shapes(
    &self,
    _pos12: &Pose,
    _local_vel12: Vec3,
    _g1: &dyn Shape,
    _g2: &dyn Shape,
    _options: ShapeCastOptions,
  ) -> Result<Option<ShapeCastHit>, Unsupported> {
    Err(Unsupported)
  }

  fn cast_shapes_nonlinear(
    &self,
    _motion1: &NonlinearRigidMotion,
    _g1: &dyn Shape,
    _motion2: &NonlinearRigidMotion,
    _g2: &dyn Shape,
    _start_time: f32,
    _end_time: f32,
    _stop_at_penetration: bool,
  ) -> Result<Option<ShapeCastHit>, Unsupported> {
    Err(Unsupported)
  }
}
impl PersistentQueryDispatcher<ContactManifoldData, ContactData> for ChunkletDispatcher {
  fn contact_manifolds(
    &self,
    pos12: &Pose,
    g1: &dyn Shape,
    g2: &dyn Shape,
    prediction: f32,
    manifolds: &mut Vec<ContactManifold<ContactManifoldData, ContactData>>,
    _workspace: &mut Option<ContactManifoldsWorkspace>,
  ) -> Result<(), Unsupported> {
    if g1.shape_type() != ShapeType::Custom && g2.shape_type() != ShapeType::Custom {
      return Err(Unsupported);
    } else if g1.shape_type() == ShapeType::Custom && g2.shape_type() != ShapeType::Custom {
      manifolds_chunklet_shape(
        pos12,
        g1.downcast_ref()
          .expect("chunklet expected to be the only custom type"),
        g2,
        prediction,
        manifolds,
        false,
      );
    } else if g1.shape_type() != ShapeType::Custom && g2.shape_type() == ShapeType::Custom {
      let _now = Instant::now();
      manifolds_chunklet_shape(
        &pos12.inverse(),
        g2.downcast_ref()
          .expect("chunklet expected to be the only custom type"),
        g1,
        prediction,
        manifolds,
        true,
      );
      // log::info!("manifolds took {:?}", now.elapsed());
    } else {
      todo!("g1:{:?}, g2:{:?}", g1.shape_type(), g2.shape_type());
    }
    Ok(())
  }

  fn contact_manifold_convex_convex(
    &self,
    _pos12: &Pose,
    _g1: &dyn Shape,
    _g2: &dyn Shape,
    _normal_constraints1: Option<&dyn NormalConstraints>,
    _normal_constraints2: Option<&dyn NormalConstraints>,
    _prediction: f32,
    _manifold: &mut ContactManifold<ContactManifoldData, ContactData>,
  ) -> Result<(), Unsupported> {
    todo!()
  }
}
fn manifolds_chunklet_shape(
  pos12: &Pose,
  g1: &Chunklet,
  g2: &dyn Shape,
  prediction: Real,
  manifolds: &mut Vec<ContactManifold<ContactManifoldData, ContactData>>,
  swap: bool, // scene:&Scene
) {
  let shape_aabb = g2.compute_aabb(pos12);
  let shape_aabb = shape_aabb.add_half_extents(Vec3::splat(prediction + 0.001));
  let (mins, maxs) = {
    let mins = shape_aabb.mins;
    let maxs = shape_aabb.maxs;
    (
      IVec3::new(
        (mins.x.floor() as i32).max(0).min(15),
        (mins.y.floor() as i32).max(0).min(15),
        (mins.z.floor() as i32).max(0).min(15),
      ),
      IVec3::new(
        (maxs.x.ceil() as i32).max(0).min(15),
        (maxs.y.ceil() as i32).max(0).min(15),
        (maxs.z.ceil() as i32).max(0).min(15),
      ),
    )
  };
  let mut manifold_index = 0;

  let colliders = &*COLLIDERS.read().unwrap();
  #[allow(unused)]
  let entity_half_extents = g2.compute_aabb(&Pose::identity()).half_extents();
  for y in mins.y..=maxs.y {
    for z in mins.z..=maxs.z {
      for x in mins.x..=maxs.x {
        let handle = g1.get((x & 0xf) as u8, (y & 0xf) as u8, (z & 0xf) as u8);
        if handle == block_collisions::AIR_HANDLE {
          continue;
        }
        if let Some(block_collider) = colliders.get(handle) {
          for aabb in block_collider.boxes.iter() {
            let center = aabb.center() + Vec3::new(x as Real, y as Real, z as Real);
            let half_extents = aabb.half_extents();
            let mut block_isometry = *pos12;
            block_isometry.translation -= center;
            //TODO check if this actually does anything good with a benchmark
            #[cfg(not(debug_assertions))]
            {
              let sphere_center_in_obb =
                block_isometry.rotation.inverse() * -block_isometry.translation;
              let clamped = sphere_center_in_obb.clamp(-entity_half_extents, entity_half_extents);
              let dist =
                (sphere_center_in_obb - clamped).length() - aabb.bounding_sphere().radius();
              if dist > prediction {
                continue;
              }
            }

            if manifolds.len() <= manifold_index {
              manifolds.push(ContactManifold::new());
            }

            {
              let world_offset = Vec3::new(
                g1.cx as Real * 16.0,
                g1.cy as Real * 16.0,
                g1.cz as Real * 16.0,
              );
              add_box_java(
                aabb.translated(Vec3::new(x as Real, y as Real, z as Real) + world_offset),
              );
            }

            if !swap {
              DefaultQueryDispatcher
                .contact_manifold_convex_convex(
                  &block_isometry,
                  &Cuboid::new(half_extents),
                  g2,
                  None,
                  None,
                  prediction,
                  &mut manifolds[manifold_index],
                )
                .expect("uh oh");
            } else {
              DefaultQueryDispatcher
                .contact_manifold_convex_convex(
                  &block_isometry.inverse(),
                  g2,
                  &Cuboid::new(half_extents),
                  None,
                  None,
                  prediction,
                  &mut manifolds[manifold_index],
                )
                .expect("uh oh");
            }
            for point in &mut manifolds[manifold_index].points {
              match swap {
                true => point.local_p2 += center,
                false => point.local_p1 += center,
              }
            }
            manifold_index += 1;
          }
        } else {
          panic!("uh oh");
        }
      }
    }
  }
  if manifolds.len() > manifold_index {
    manifolds.truncate(manifold_index);
  }
  // log::info!(
  //   "{} manifolds area covered: {:?} -> {:?} ({} blocks)",
  //   manifolds.len(),
  //   mins,
  //   maxs,
  //   (maxs - mins).product()
  // );
}

// TODO remove/feature flag it
fn add_box_java(aabb: Aabb) {
  let jvm = logger::JVM.lock().unwrap();
  if let Some(jvm) = jvm.as_ref() {
    jvm
      .attach_current_thread(|env| -> JResult<()> {
        let class = env
          .find_class(jni_str!(
            "supersymmetry/client/renderer/handler/PhysicsDebugRenderer"
          ))
          .unwrap();
        let args: [JValue; 6] = [aabb.mins, aabb.maxs]
          .iter()
          .map(|x| {
            let mut buf = [0.0; 3];
            x.write_to_slice(&mut buf);
            buf
          })
          .flat_map(|x| x.map(|y| y as jdouble))
          .map(|x| JValue::from(x))
          .collect_array()
          .unwrap();
        env
          .call_static_method(class, jni_str!("add_box"), jni_sig!("(DDDDDD)V"), &args)
          .unwrap();

        Ok(())
      })
      .unwrap();
  }
}
pub fn clear_boxes_java() {
  let jvm = logger::JVM.lock().unwrap();
  if let Some(jvm) = jvm.as_ref() {
    jvm
      .attach_current_thread(|env| -> JResult<()> {
        let class = env
          .find_class(jni_str!(
            "supersymmetry/client/renderer/handler/PhysicsDebugRenderer"
          ))
          .unwrap();
        env
          .call_static_method(class, jni_str!("clear_boxes"), jni_sig!(sig = ()), &[])
          .unwrap();

        Ok(())
      })
      .unwrap();
  }
}
