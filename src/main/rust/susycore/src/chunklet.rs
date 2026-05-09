use core::f32;
use std::collections::HashMap;
use std::num::NonZeroU32;

use itertools::Itertools;
use rapier3d::math::{Pose, Vec3};
use rapier3d::parry::bounding_volume::{Aabb, BoundingSphere};
use rapier3d::parry::query::{PointProjection, Ray, RayIntersection};
use rapier3d::parry::shape::Cuboid;
use rapier3d::prelude::{
  FeatureId, MassProperties, PointQuery, RayCast, Shape, ShapeType, TypedShape,
};

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
  pub map: HashMap<PackedChunkletCoords, Chunklet>,
}

impl TerrainData {
  pub fn new() -> Self {
    Self {
      map: HashMap::new(),
    }
  }

  pub fn get(&self, x: i32, y: u8, z: i32) -> Option<&Chunklet> {
    self.map.get(&PackedChunkletCoords::from_xyz(x, y, z))
  }
  pub fn get_mut(&mut self, x: i32, y: u8, z: i32) -> Option<&mut Chunklet> {
    self.map.get_mut(&PackedChunkletCoords::from_xyz(x, y, z))
  }
  pub fn remove(&mut self, x: i32, y: u8, z: i32) -> Option<Chunklet> {
    self.map.remove(&PackedChunkletCoords::from_xyz(x, y, z))
  }
  pub fn put(&mut self, x: i32, y: u8, z: i32, chunklet: Chunklet) {
    self
      .map
      .insert(PackedChunkletCoords::from_xyz(x, y, z), chunklet);
  }
}
//Option<NonZeroU32> is 32 bits
type BlockHandle = Option<NonZeroU32>;
//16x16x16 block grid
pub struct Chunklet {
  blocks: [BlockHandle; CHUNK_VOLUME],
  aabb: Aabb,
 pub tree: Octree,
}
impl Chunklet {
  pub fn new_with_blockhandle(blocks: [BlockColliderInfoHandle; CHUNK_VOLUME]) -> Self {
    // i believe that this has no runtime cost, if it somehow does, either stop using NonZeroU32 or just
    // mem::transmute this array

    assert!(
      size_of::<[BlockColliderInfoHandle; CHUNK_VOLUME]>()
        == size_of::<[BlockHandle; CHUNK_VOLUME]>()
    );

    Self::new(blocks.map(|x| NonZeroU32::new(x.0)))
  }
  pub fn new(blocks: [BlockHandle; CHUNK_VOLUME]) -> Self {
    debug_assert!(!blocks.iter().all(|x| x.is_none()));
    let mut min = [u8::MAX, u8::MAX, u8::MAX];
    let mut max = [u8::MIN, u8::MIN, u8::MIN];
    for x in 0..16 {
      for y in 0..16 {
        for z in 0..16 {
          let i = Self::index(x, y, z);
          let pos = [x, y, z];
          if blocks[i as usize].is_some() {
            for axis in 0..3 {
              min[axis] = min[axis].min(pos[axis]);
              max[axis] = max[axis].max(pos[axis]);
            }
          }
        }
      }
    }
    let [minx, miny, minz] = min;
    let [maxx, maxy, maxz] = max;
    let bounds = Aabb::new(
      Vec3::new(minx as f32, miny as f32, minz as f32),
      Vec3::new(maxx as f32, maxy as f32, maxz as f32),
    );

    let mut tree = Octree::new(CHUNK_SIDE_LOG2 as u32);
    for x in 0..CHUNK_SIDE as u8 {
      for y in 0..CHUNK_SIDE as u8 {
        for z in 0..CHUNK_SIDE as u8 {
          let i = Self::index(x, y, z) as usize;
          if let Some(handle) = blocks[i] {
            let mut node = tree.root_index();
            for depth in (1..=CHUNK_SIDE_LOG2).rev() {
              let bit = 1u8 << (depth - 1);
              let octant = usize::from(x & bit != 0)
                | (usize::from(y & bit != 0) << 1)
                | (usize::from(z & bit != 0) << 2);
              if tree.is_empty(node) {
                tree.initialize_branch(node);
              }
              if depth == 1 {
                tree.set_leaf(tree.child_index(node, octant), handle.get());
              } else {
                node = tree.child_index(node, octant);
              }
            }
          }
        }
      }
    }

    Self {
      blocks,
      aabb: bounds,
      tree,
    }
  }
  #[inline(always)]
  pub fn get(&self, x: u8, y: u8, z: u8) -> &BlockHandle {
    &self.blocks[Self::index(x, y, z) as usize]
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
        let block = block.map(|x| lock.get(x.get() as usize)).flatten();
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
  }

  fn project_local_point_and_get_feature(&self, _pt: Vec3) -> (PointProjection, FeatureId) {
    todo!()
  }
}
