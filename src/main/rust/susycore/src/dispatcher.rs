use std::any::Any;

use log::trace;
use rapier3d::math::Vec3;
use rapier3d::parry::bounding_volume::Aabb;
use rapier3d::parry::query::details::NormalConstraints;
use rapier3d::parry::query::{
  ClosestPoints, Contact, ContactManifold, ContactManifoldsWorkspace, DefaultQueryDispatcher,
  NonlinearRigidMotion, PersistentQueryDispatcher, QueryDispatcher, ShapeCastHit, ShapeCastOptions,
  TypedWorkspaceData, Unsupported, WorkspaceData,
};
use rapier3d::prelude::{ContactData, ContactManifoldData, Cuboid, Pose, Shape, ShapeType};

use crate::block_collisions::{BlockColliderInfoHandle, COLLIDERS, ColliderStore};
use crate::chunklet::Chunklet;
use crate::terrain::CHUNK_SIDE;
use crate::{IVec3, Real};

struct ChunkletWorkspace;

impl WorkspaceData for ChunkletWorkspace {
  fn as_typed_workspace_data(&self) -> TypedWorkspaceData<'_> {
    TypedWorkspaceData::Custom
  }
  fn clone_dyn(&self) -> Box<dyn WorkspaceData> {
    Box::new(Self)
  }
}

pub struct ChunkletDispatcher;
impl QueryDispatcher for ChunkletDispatcher {
  fn intersection_test(
    &self,
    _pos12: &Pose,
    g1: &dyn Shape,
    g2: &dyn Shape,
  ) -> Result<bool, Unsupported> {
    trace!("intersect {:?} <-> {:?}", g1.shape_type(), g2.shape_type());
    Err(Unsupported)
  }

  fn distance(&self, _pos12: &Pose, g1: &dyn Shape, g2: &dyn Shape) -> Result<f32, Unsupported> {
    trace!("distance {:?} <-> {:?}", g1.shape_type(), g2.shape_type());
    Err(Unsupported)
  }

  fn contact(
    &self,
    _pos12: &Pose,
    g1: &dyn Shape,
    g2: &dyn Shape,
    _prediction: f32,
  ) -> Result<Option<Contact>, Unsupported> {
    trace!("contact {:?} <-> {:?}", g1.shape_type(), g2.shape_type());
    Err(Unsupported)
  }

  fn closest_points(
    &self,
    _pos12: &Pose,
    g1: &dyn Shape,
    g2: &dyn Shape,
    _max_dist: f32,
  ) -> Result<ClosestPoints, Unsupported> {
    trace!(
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
    workspace: &mut Option<ContactManifoldsWorkspace>,
  ) -> Result<(), Unsupported> {
    if g1.shape_type() != ShapeType::Custom && g2.shape_type() != ShapeType::Custom {
      return Err(Unsupported);
    }
    *workspace = Some(ChunkletWorkspace.into());
    if g1.shape_type() == ShapeType::Custom && g2.shape_type() != ShapeType::Custom {
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
    } else {
      let g1 = (g1 as &dyn Any)
        .downcast_ref()
        .expect("chunklet expected to be the only custom type");
      let g2 = (g2 as &dyn Any)
        .downcast_ref()
        .expect("chunklet expected to be the only custom type");
      manifolds_chunklet_chunklet(pos12, g1, g2, prediction, manifolds);
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
    Err(Unsupported)
  }
}
const CELL_EPS: Real = 0.001;

fn take_manifold_slot(
  manifolds: &mut Vec<ContactManifold<ContactManifoldData, ContactData>>,
  index: usize,
) -> &mut ContactManifold<ContactManifoldData, ContactData> {
  if manifolds.len() <= index {
    manifolds.push(ContactManifold::new());
  }
  let manifold = &mut manifolds[index];
  manifold.points.clear();
  manifold
}

fn cell_aabb_to_range(aabb: &Aabb) -> (IVec3, IVec3) {
  let mins = aabb.mins;
  let maxs = aabb.maxs;
  let limit = CHUNK_SIDE as i32 - 1;
  (
    IVec3::new(
      (mins.x.floor() as i32).clamp(0, limit),
      (mins.y.floor() as i32).clamp(0, limit),
      (mins.z.floor() as i32).clamp(0, limit),
    ),
    IVec3::new(
      (maxs.x.ceil() as i32).clamp(0, limit),
      (maxs.y.ceil() as i32).clamp(0, limit),
      (maxs.z.ceil() as i32).clamp(0, limit),
    ),
  )
}

fn is_interior(chunklet: &Chunklet, colliders: &ColliderStore, x: u8, y: u8, z: u8) -> bool {
  let (nx, ny, nz) = (x as i8, y as i8, z as i8);
  let full = |x: i8, y: i8, z: i8| {
    (0..CHUNK_SIDE as i8).contains(&x)
      && (0..CHUNK_SIDE as i8).contains(&y)
      && (0..CHUNK_SIDE as i8).contains(&z)
      && colliders
        .get(chunklet.get(x as u8, y as u8, z as u8))
        .is_some_and(|info| is_full_cell(&info.boxes))
  };
  full(nx - 1, ny, nz)
    && full(nx + 1, ny, nz)
    && full(nx, ny - 1, nz)
    && full(nx, ny + 1, nz)
    && full(nx, ny, nz - 1)
    && full(nx, ny, nz + 1)
}

fn is_full_cell(boxes: &[Aabb]) -> bool {
  boxes.len() == 1 && boxes[0].mins == Vec3::ZERO && boxes[0].maxs == Vec3::splat(1.0)
}

fn manifolds_chunklet_chunklet(
  pos12: &Pose,
  g1: &Chunklet,
  g2: &Chunklet,
  prediction: Real,
  manifolds: &mut Vec<ContactManifold<ContactManifoldData, ContactData>>,
) {
  let aabb2 = g2
    .compute_local_aabb()
    .transform_by(pos12)
    .add_half_extents(Vec3::splat(prediction + CELL_EPS));
  if aabb2.maxs.x < 0.0
    || aabb2.maxs.y < 0.0
    || aabb2.maxs.z < 0.0
    || aabb2.mins.x > CHUNK_SIDE as Real
    || aabb2.mins.y > CHUNK_SIDE as Real
    || aabb2.mins.z > CHUNK_SIDE as Real
  {
    for m in &mut manifolds[..] {
      m.points.clear();
    }
    return;
  }
  let (mins, maxs) = cell_aabb_to_range(&aabb2);

  let colliders = &*COLLIDERS.read().unwrap();
  let pos12_inv = pos12.inverse();
  let mut manifold_index = 0;

  g1.tree.query_aabb(
    [mins.x as f32, mins.y as f32, mins.z as f32],
    [
      (maxs.x + 1) as f32,
      (maxs.y + 1) as f32,
      (maxs.z + 1) as f32,
    ],
    |handle1, x, y, z| {
      let Some(info1) = colliders.get(BlockColliderInfoHandle(handle1)) else {
        return;
      };
      if is_full_cell(&info1.boxes) && is_interior(g1, colliders, x, y, z) {
        return;
      }
      let cell_min = Vec3::new(x as Real, y as Real, z as Real);
      let cell_max = cell_min + Vec3::splat(1.0);
      let cell_local = Aabb::new(cell_min, cell_max)
        .transform_by(&pos12_inv)
        .add_half_extents(Vec3::splat(CELL_EPS));
      let (cmins, cmaxs) = cell_aabb_to_range(&cell_local);
      for cy in cmins.y..=cmaxs.y {
        for cz in cmins.z..=cmaxs.z {
          for cx in cmins.x..=cmaxs.x {
            let handle2 = g2.get(cx as u8, cy as u8, cz as u8);
            if handle2.0 == 0 {
              continue;
            }
            let Some(info2) = colliders.get(BlockColliderInfoHandle(handle2.0)) else {
              continue;
            };
            for aabb2 in info2.boxes.iter() {
              let center2 = aabb2.center() + Vec3::new(cx as Real, cy as Real, cz as Real);
              for aabb1 in info1.boxes.iter() {
                let center1 = aabb1.center() + cell_min;
                let iso = Pose::from_parts(
                  pos12.translation + pos12.rotation * center2 - center1,
                  pos12.rotation,
                );
                let manifold = take_manifold_slot(manifolds, manifold_index);
                DefaultQueryDispatcher
                  .contact_manifold_convex_convex(
                    &iso,
                    &Cuboid::new(aabb1.half_extents()),
                    &Cuboid::new(aabb2.half_extents()),
                    None,
                    None,
                    prediction,
                    manifold,
                  )
                  .expect("cuboid cuboid manifold failed");
                for point in &mut manifold.points {
                  point.local_p1 += center1;
                  point.local_p2 += center2;
                }
                manifold_index += 1;
              }
            }
          }
        }
      }
    },
  );
  for m in &mut manifolds[manifold_index..] {
    m.points.clear();
  }
}

fn manifolds_chunklet_shape(
  pos12: &Pose,
  g1: &Chunklet,
  g2: &dyn Shape,
  prediction: Real,
  manifolds: &mut Vec<ContactManifold<ContactManifoldData, ContactData>>,
  swap: bool,
) {
  let shape_aabb = g2.compute_aabb(pos12);
  let shape_aabb = shape_aabb.add_half_extents(Vec3::splat(prediction + CELL_EPS));
  let (mins, maxs) = cell_aabb_to_range(&shape_aabb);
  let mut manifold_index = 0;

  let colliders = &*COLLIDERS.read().unwrap();

  let qmins = [mins.x as f32, mins.y as f32, mins.z as f32];
  let qmaxs = [
    (maxs.x + 1) as f32,
    (maxs.y + 1) as f32,
    (maxs.z + 1) as f32,
  ];
  g1.tree.query_aabb(qmins, qmaxs, |handle, x, y, z| {
    let handle = BlockColliderInfoHandle(handle);
    if let Some(block_collider) = colliders.get(handle) {
      for aabb in block_collider.boxes.iter() {
        let center = aabb.center() + Vec3::new(x as Real, y as Real, z as Real);
        let half_extents = aabb.half_extents();
        let mut block_isometry = *pos12;
        block_isometry.translation -= center;

        let manifold = take_manifold_slot(manifolds, manifold_index);
        let result = if !swap {
          DefaultQueryDispatcher.contact_manifold_convex_convex(
            &block_isometry,
            &Cuboid::new(half_extents),
            g2,
            None,
            None,
            prediction,
            manifold,
          )
        } else {
          DefaultQueryDispatcher.contact_manifold_convex_convex(
            &block_isometry.inverse(),
            g2,
            &Cuboid::new(half_extents),
            None,
            None,
            prediction,
            manifold,
          )
        };
        if let Err(e) = result {
          log::warn!("chunklet manifold unsupported: {e:?}");
          manifold.points.clear();
        }
        for point in &mut manifold.points {
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
}
