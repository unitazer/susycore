use std::any::Any;
use std::time::Instant;

use itertools::Itertools;
use log::info;
use rapier3d::math::Vec3;
use rapier3d::parry::query::details::NormalConstraints;
use rapier3d::parry::query::{
  ClosestPoints, Contact, ContactManifold, ContactManifoldsWorkspace, DefaultQueryDispatcher,
  NonlinearRigidMotion, PersistentQueryDispatcher, QueryDispatcher, ShapeCastHit, ShapeCastOptions,
  Unsupported,
};
use rapier3d::prelude::{ContactData, ContactManifoldData, Cuboid, Pose, Shape, ShapeType};
use rapier3d::utils::PoseOps;

use crate::block_collisions::{BlockColliderInfoHandle, COLLIDERS};
use crate::chunklet::Chunklet;
use crate::{IVec3, Real};

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
        (g1 as &dyn Any)
          .downcast_ref()
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
        (g2 as &dyn Any)
          .downcast_ref()
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
        (mins.x.floor() as i32).clamp(0, 15),
        (mins.y.floor() as i32).clamp(0, 15),
        (mins.z.floor() as i32).clamp(0, 15),
      ),
      IVec3::new(
        (maxs.x.ceil() as i32).clamp(0, 15),
        (maxs.y.ceil() as i32).clamp(0, 15),
        (maxs.z.ceil() as i32).clamp(0, 15),
      ),
    )
  };
  let mut manifold_index = 0;

  let colliders = &*COLLIDERS.read().unwrap();

  let qmins = [mins.x as f32, mins.y as f32, mins.z as f32];
  let qmaxs = [
    (maxs.x + 1) as f32,
    (maxs.y + 1) as f32,
    (maxs.z + 1) as f32,
  ];
  g1.tree.query_aabb(qmins, qmaxs, |handle, x, y, z| {
    if handle == 0 {
      return;
    }
    let handle = BlockColliderInfoHandle(handle);
    if let Some(block_collider) = colliders.get(handle) {
      for aabb in block_collider.boxes.iter() {
        let center = aabb.center() + Vec3::new(x as Real, y as Real, z as Real);
        let half_extents = aabb.half_extents();
        let mut block_isometry = *pos12;
        block_isometry.translation -= center;

        if manifolds.len() <= manifold_index {
          manifolds.push(ContactManifold::new());
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
    }
  });
  for m in &mut manifolds[manifold_index..] {
    m.points.clear();
  }
  // log::info!(
  //   "{} manifolds area covered: {:?} -> {:?} ({} blocks)",
  //   manifolds.len(),
  //   mins,
  //   maxs,
  //   (maxs - mins).product()
  // );
}
