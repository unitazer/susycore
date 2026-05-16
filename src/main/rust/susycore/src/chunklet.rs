use core::f32;
use rapier3d::math::{Pose, Vec3};
use rapier3d::parry::bounding_volume::{Aabb, BoundingSphere};
use rapier3d::parry::query::{PointProjection, Ray, RayIntersection};
use rapier3d::parry::shape::Cuboid;
use rapier3d::prelude::{
  ColliderBuilder, ColliderHandle, ColliderSet, FeatureId, IslandManager, MassProperties,
  PointQuery, RayCast, RigidBodySet, Shape, ShapeType, SharedShape, TypedShape,
};
use std::collections::HashMap;
use std::num::NonZeroU32;
use std::sync::Arc;

use crate::Real;
use crate::block_collisions::{BlockColliderInfoHandle, COLLIDERS};
use crate::octree::Octree;

//the minecraft world is (6*10^7)^2 * 256 blocks which is ~60^2 so it can be packed
#[derive(Hash, PartialEq, PartialOrd, Ord, Eq, Clone, Copy)]
pub struct PackedChunkletCoords(u64);
impl PackedChunkletCoords {
  //chunk space
  #[inline(always)]
  pub fn from_xyz(x: i32, y: u8, z: i32) -> Self {
    debug_assert!(x.abs() < (3_000_000 >> 4));
    debug_assert!(z.abs() < (3_000_000 >> 4));
    let packed = ((x < 0) as u64) << 63
      | ((z < 0) as u64) << 62
      | (x.abs() as u64) << 38
      | (z.abs() as u64) << 13
      | y as u64;
    Self(packed)
  }
  pub fn to_xyz(p: u64) -> (i32, u8, i32) {
    let y = p as u8;
    let x = if (p >> 63) & 1 != 0 {
      -((p >> 38 & 0xFFFFFF) as i32)
    } else {
      (p >> 38 & 0xFFFFFF) as i32
    };
    let z = if (p >> 62) & 1 != 0 {
      -((p >> 13 & 0x1FFFFFF) as i32)
    } else {
      (p >> 13 & 0x1FFFFFF) as i32
    };
    (x, y, z)
  }
  pub fn inner(self) -> u64 {
    self.0
  }
}
pub const CHUNK_SIDE: usize = 16;
pub const CHUNK_SIDE_LOG2: usize = CHUNK_SIDE.ilog2() as usize;
pub const CHUNK_VOLUME: usize = CHUNK_SIDE.pow(3);

pub struct TerrainData {
  pub chunklets: HashMap<PackedChunkletCoords, Arc<Chunklet>>,
  pub colliders: HashMap<PackedChunkletCoords, ColliderHandle>,
}

impl TerrainData {
  pub fn new() -> Self {
    Self {
      chunklets: HashMap::new(),
      colliders: HashMap::new(),
    }
  }

  pub fn get(&self, x: i32, y: u8, z: i32) -> Option<&Arc<Chunklet>> {
    self.chunklets.get(&PackedChunkletCoords::from_xyz(x, y, z))
  }
  pub fn remove(
    &mut self,
    x: i32,
    y: u8,
    z: i32,
    colliders: &mut ColliderSet,
    islands: &mut IslandManager,
    bodies: &mut RigidBodySet,
  ) -> Option<Arc<Chunklet>> {
    let key = PackedChunkletCoords::from_xyz(x, y, z);
    let chunklet = self.chunklets.remove(&key);
    if let Some(handle) = self.colliders.remove(&key) {
      colliders.remove(handle, islands, bodies, true);
    }
    chunklet
  }
  pub fn put(
    &mut self,
    x: i32,
    y: u8,
    z: i32,
    c: Chunklet,
    colliders: &mut ColliderSet,
  ) -> ColliderHandle {
    let arc = Arc::new(c);
    let shared = SharedShape(arc.clone() as Arc<dyn Shape>);
    let handle = colliders.insert(
      ColliderBuilder::new(shared)
        .translation(Vec3::new(
          (x * 16) as Real,
          (y * 16) as Real,
          (z * 16) as Real,
        ))
        .build(),
    );
    self
      .chunklets
      .insert(PackedChunkletCoords::from_xyz(x, y, z), arc);
    self
      .colliders
      .insert(PackedChunkletCoords::from_xyz(x, y, z), handle);
    handle
  }
}
//Option<NonZeroU32> is 32 bits
type BlockHandle = Option<NonZeroU32>;
//16x16x16 block grid
pub struct Chunklet {
  blocks: [BlockHandle; CHUNK_VOLUME],
  aabb: Aabb,
  pub tree: Octree,
  pub cx: i32,
  pub cy: i32,
  pub cz: i32,
}
impl Chunklet {
  pub fn new_with_blockhandle(
    cx: i32,
    cy: i32,
    cz: i32,
    blocks: [BlockColliderInfoHandle; CHUNK_VOLUME],
  ) -> Self {
    // i believe that this has no runtime cost, if it somehow does, either stop using NonZeroU32 or just
    // mem::transmute this array

    assert!(
      size_of::<[BlockColliderInfoHandle; CHUNK_VOLUME]>()
        == size_of::<[BlockHandle; CHUNK_VOLUME]>()
    );

    Self::new(cx, cy, cz, blocks.map(|x| NonZeroU32::new(x.0)))
  }
  pub fn new(cx: i32, cy: i32, cz: i32, blocks: [BlockHandle; CHUNK_VOLUME]) -> Self {
    debug_assert!(!blocks.iter().all(|x| x.is_none()));
    let mut min = [u8::MAX, u8::MAX, u8::MAX];
    let mut max = [u8::MIN, u8::MIN, u8::MIN];
    let mut tree = Octree::new(CHUNK_SIDE_LOG2 as u32);

    for x in 0..CHUNK_SIDE as u8 {
      for y in 0..CHUNK_SIDE as u8 {
        for z in 0..CHUNK_SIDE as u8 {
          let i = Self::index(x, y, z) as usize;
          if let Some(handle) = blocks[i] {
            min[0] = min[0].min(x);
            min[1] = min[1].min(y);
            min[2] = min[2].min(z);
            max[0] = max[0].max(x);
            max[1] = max[1].max(y);
            max[2] = max[2].max(z);

            // depth 4
            let octant = usize::from(x & 8 != 0)
              | (usize::from(y & 8 != 0) << 1)
              | (usize::from(z & 8 != 0) << 2);
            let mut node = tree.root_index();
            if tree.is_empty(node) {
              tree.initialize_branch(node);
            }
            node = tree.child_index(node, octant);

            // depth 3
            let octant = usize::from(x & 4 != 0)
              | (usize::from(y & 4 != 0) << 1)
              | (usize::from(z & 4 != 0) << 2);
            if tree.is_empty(node) {
              tree.initialize_branch(node);
            }
            node = tree.child_index(node, octant);

            // depth 2
            let octant = usize::from(x & 2 != 0)
              | (usize::from(y & 2 != 0) << 1)
              | (usize::from(z & 2 != 0) << 2);
            if tree.is_empty(node) {
              tree.initialize_branch(node);
            }
            node = tree.child_index(node, octant);

            // depth 1 (leaf)
            let octant = usize::from(x & 1 != 0)
              | (usize::from(y & 1 != 0) << 1)
              | (usize::from(z & 1 != 0) << 2);
            if tree.is_empty(node) {
              tree.initialize_branch(node);
            }
            tree.set_leaf(tree.child_index(node, octant), handle.get());
          }
        }
      }
    }

    let [minx, miny, minz] = min;
    let [maxx, maxy, maxz] = max;
    let bounds = Aabb::new(
      Vec3::new(minx as f32, miny as f32, minz as f32),
      Vec3::new((maxx + 1) as f32, (maxy + 1) as f32, (maxz + 1) as f32),
    );

    Self {
      blocks,
      aabb: bounds,
      tree,
      cx,
      cy,
      cz,
    }
  }
  #[inline(always)]
  pub fn get(&self, x: u8, y: u8, z: u8) -> BlockColliderInfoHandle {
    BlockColliderInfoHandle(
      self.blocks[Self::index(x, y, z) as usize]
        .map(|x| x.get())
        .unwrap_or(0),
    )
  }
  #[inline(always)]
  fn index(x: u8, y: u8, z: u8) -> u16 {
    (y as u16) << 8 | (z as u16) << 4 | x as u16
  }
  #[inline(always)]
  fn index_decode(index: u16) -> (u8, u8, u8) {
    let x = (index & 0xF) as u8;
    let z = ((index >> 4) & 0xF) as u8;
    let y = ((index >> 8) & 0xF) as u8;
    (x, y, z)
  }
}
impl Shape for Chunklet {
  fn compute_local_aabb(&self) -> Aabb {
    self.aabb
  }

  fn compute_local_bounding_sphere(&self) -> BoundingSphere {
    self.aabb.bounding_sphere()
  }

  fn clone_dyn(&self) -> Box<dyn Shape> {
    todo!()
  }

  fn scale_dyn(
    &self,
    _scale: rapier3d::prelude::Vector,
    _num_subdivisions: u32,
  ) -> Option<Box<dyn Shape>> {
    todo!()
  }

  fn mass_properties(&self, density: f32) -> MassProperties {
    let lock = COLLIDERS.read().unwrap();
    self
      .blocks
      .iter()
      .enumerate()
      .filter(|x| x.1.is_some())
      .map(|(i, block)| {
        let (x, y, z) = Self::index_decode(i as u16);
        let pose1 = Pose::translation(x as f32, y as f32, z as f32);
        let block = block.and_then(|x| lock.get(BlockColliderInfoHandle(x.get())));
        (pose1, block)
      })
      .filter(|x| x.1.is_some())
      .map(|x| (x.0, x.1.unwrap()))
      .flat_map(|(pose, block_info)| {
        block_info
          .boxes
          .iter()
          .map(|x| {
            (
              Pose::from_translation(x.center()),
              Cuboid::new(x.half_extents()),
            )
          })
          .map(move |x| (x.0 * pose.clone(), x.1))
          .map(|s| s.1.mass_properties(density).transform_by(&s.0))
      })
      .fold(MassProperties::default(), |acc, mp| acc + mp)
  }

  fn shape_type(&self) -> ShapeType {
    ShapeType::Custom
  }

  fn as_typed_shape(&self) -> TypedShape<'_> {
    TypedShape::Custom(self)
  }

  fn ccd_thickness(&self) -> Real {
    0.25
  }

  fn ccd_angular_thickness(&self) -> Real {
    f32::consts::PI / 8.0
  }
}
impl RayCast for Chunklet {
  fn cast_local_ray_and_get_normal(
    &self,
    _ray: &Ray,
    _max_time_of_impact: f32,
    _solid: bool,
  ) -> Option<RayIntersection> {
    todo!()
  }
}
impl PointQuery for Chunklet {
  fn project_local_point(&self, _pt: Vec3, _solid: bool) -> PointProjection {
    todo!()
    // let mut best_sq_dist = Real::MAX;
    // let mut best = PointProjection::new(false, pt);
    //
    // let mut stack: Vec<(usize, u8, u8, u8, u32)> = Vec::new();
    // stack.push((self.tree.root_index(), 0, 0, 0, CHUNK_SIDE_LOG2 as u32));
    //
    // while let Some((node, cx, cy, cz, log2_sz)) = stack.pop() {
    //   if self.tree.is_empty(node) {
    //     continue;
    //   }
    //
    //   if self.tree.is_leaf(node) {
    //     let aabb = Aabb::new(
    //       Vec3::new(cx as f32, cy as f32, cz as f32),
    //       Vec3::new(cx as f32 + 1.0, cy as f32 + 1.0, cz as f32 + 1.0),
    //     );
    //     let proj = aabb.project_local_point(pt, solid);
    //     let d2 = (proj.point - pt).length_squared();
    //     if d2 < best_sq_dist {
    //       best_sq_dist = d2;
    //       best = proj;
    //       if best_sq_dist == 0.0 {
    //         break;
    //       }
    //     }
    //   } else if self.tree.is_branch(node) {
    //     let child_log2 = log2_sz - 1;
    //     let step = 1 << child_log2;
    //
    //     for octant in 0..8 {
    //       let child = self.tree.child_index(node, octant);
    //       if self.tree.is_empty(child) {
    //         continue;
    //       }
    //
    //       let child_cx = cx + ((octant & 1) as u8) * step;
    //       let child_cy = cy + (((octant >> 1) & 1) as u8) * step;
    //       let child_cz = cz + (((octant >> 2) & 1) as u8) * step;
    //
    //       if child_log2 == 0 {
    //         stack.push((child, child_cx, child_cy, child_cz, child_log2));
    //       } else {
    //         let sz = step as f32;
    //         let child_aabb = Aabb::new(
    //           Vec3::new(child_cx as f32, child_cy as f32, child_cz as f32),
    //           Vec3::new(
    //             child_cx as f32 + sz,
    //             child_cy as f32 + sz,
    //             child_cz as f32 + sz,
    //           ),
    //         );
    //         let d2 = child_aabb.distance_to_local_point(pt, true);
    //         if d2 * d2 < best_sq_dist {
    //           stack.push((child, child_cx, child_cy, child_cz, child_log2));
    //         }
    //       }
    //     }
    //   }
    // }
    //
    // best
  }

  fn project_local_point_and_get_feature(&self, _pt: Vec3) -> (PointProjection, FeatureId) {
    //   let mut best_sq_dist = Real::MAX;
    //   let mut best = PointProjection::new(false, pt);
    //   let mut best_voxel = 0u16;
    //
    //   let mut stack: Vec<(usize, u8, u8, u8, u32)> = Vec::new();
    //   stack.push((self.tree.root_index(), 0, 0, 0, CHUNK_SIDE_LOG2 as u32));
    //
    //   while let Some((node, cx, cy, cz, log2_sz)) = stack.pop() {
    //     if self.tree.is_empty(node) {
    //       continue;
    //     }
    //
    //     if self.tree.is_leaf(node) {
    //       let aabb = Aabb::new(
    //         Vec3::new(cx as f32, cy as f32, cz as f32),
    //         Vec3::new(cx as f32 + 1.0, cy as f32 + 1.0, cz as f32 + 1.0),
    //       );
    //       let proj = aabb.project_local_point(pt, false);
    //       let d2 = (proj.point - pt).length_squared();
    //       if d2 < best_sq_dist {
    //         best_sq_dist = d2;
    //         best = proj;
    //         best_voxel = Self::index(cx, cy, cz);
    //         if best_sq_dist == 0.0 {
    //           break;
    //         }
    //       }
    //     } else if self.tree.is_branch(node) {
    //       let child_log2 = log2_sz - 1;
    //       let step = 1 << child_log2;
    //
    //       for octant in 0..8 {
    //         let child = self.tree.child_index(node, octant);
    //         if self.tree.is_empty(child) {
    //           continue;
    //         }
    //
    //         let child_cx = cx + ((octant & 1) as u8) * step;
    //         let child_cy = cy + (((octant >> 1) & 1) as u8) * step;
    //         let child_cz = cz + (((octant >> 2) & 1) as u8) * step;
    //
    //         if child_log2 == 0 {
    //           stack.push((child, child_cx, child_cy, child_cz, child_log2));
    //         } else {
    //           let sz = step as f32;
    //           let child_aabb = Aabb::new(
    //             Vec3::new(child_cx as f32, child_cy as f32, child_cz as f32),
    //             Vec3::new(
    //               child_cx as f32 + sz,
    //               child_cy as f32 + sz,
    //               child_cz as f32 + sz,
    //             ),
    //           );
    //           let d2 = child_aabb.distance_to_local_point(pt, true);
    //           if d2 * d2 < best_sq_dist {
    //             stack.push((child, child_cx, child_cy, child_cz, child_log2));
    //           }
    //         }
    //       }
    //     }
    //   }
    //
    //   (best, FeatureId::Face(best_voxel as u32))
    // }
    todo!()
  }
}
