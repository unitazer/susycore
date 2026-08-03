use core::f32;
use rapier3d::math::{Pose, Vec3};
use rapier3d::parry::bounding_volume::{Aabb, BoundingSphere};
use rapier3d::parry::query::{PointProjection, Ray, RayIntersection};
use rapier3d::parry::shape::Cuboid;
use rapier3d::prelude::{
  FeatureId, MassProperties, PointQuery, RayCast, Shape, ShapeType, TypedShape,
};
use std::mem::size_of;
use std::num::NonZeroU32;

use crate::Real;
use crate::block_collisions::{BlockColliderInfoHandle, COLLIDERS};
use crate::octree::Octree;
use crate::terrain::{CHUNK_SIDE, CHUNK_SIDE_LOG2, CHUNK_VOLUME};

//Option<NonZeroU32> is 32 bits
type BlockHandle = Option<NonZeroU32>;
//16x16x16 block grid
pub struct Chunklet {
  pub blocks: [BlockHandle; CHUNK_VOLUME],
  pub aabb: Aabb,
  pub tree: Octree,
  pub cx: i32,
  pub cy: i32,
  pub cz: i32,
  block_count: u32,
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
    let mut block_count = 0u32;

    for x in 0..CHUNK_SIDE as u8 {
      for y in 0..CHUNK_SIDE as u8 {
        for z in 0..CHUNK_SIDE as u8 {
          let i = Self::index(x, y, z) as usize;
          if let Some(handle) = blocks[i] {
            block_count += 1;
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
      block_count,
    }
  }
  pub fn count(&self) -> u32 {
    self.block_count
  }
  fn on_boundary(&self, x: u8, y: u8, z: u8) -> bool {
    let min = self.aabb.mins;
    let max = self.aabb.maxs;
    (x as f32) == min.x
      || (x as f32 + 1.0) == max.x
      || (y as f32) == min.y
      || (y as f32 + 1.0) == max.y
      || (z as f32) == min.z
      || (z as f32 + 1.0) == max.z
  }
  fn recompute_aabb(&mut self) {
    let mut min = [u8::MAX, u8::MAX, u8::MAX];
    let mut max = [u8::MIN, u8::MIN, u8::MIN];
    let mut found = false;
    for i in 0..CHUNK_VOLUME {
      if self.blocks[i].is_some() {
        found = true;
        let (x, y, z) = Self::index_decode(i as u16);
        min[0] = min[0].min(x);
        min[1] = min[1].min(y);
        min[2] = min[2].min(z);
        max[0] = max[0].max(x);
        max[1] = max[1].max(y);
        max[2] = max[2].max(z);
      }
    }
    if found {
      self.aabb = Aabb::new(
        Vec3::new(min[0] as f32, min[1] as f32, min[2] as f32),
        Vec3::new(
          (max[0] + 1) as f32,
          (max[1] + 1) as f32,
          (max[2] + 1) as f32,
        ),
      );
    }
  }

  #[inline(always)]
  pub fn set(&mut self, x: u8, y: u8, z: u8, value: Option<NonZeroU32>) {
    let i = Self::index(x, y, z) as usize;
    let old = self.blocks[i];
    self.blocks[i] = value;
    self.tree.set_block(x, y, z, value.map(|x| x.get()));
    match (old, value) {
      (Some(_), Some(_)) => {}
      (None, None) => {}
      (None, Some(_)) => {
        self.block_count += 1;
        let p = Vec3::new(x as f32, y as f32, z as f32);
        self.aabb.take_point(p);
        self.aabb.take_point(p + Vec3::new(1.0, 1.0, 1.0));
      }
      (Some(_), None) => {
        self.block_count -= 1;
        if self.block_count > 0 && self.on_boundary(x, y, z) {
          self.recompute_aabb();
        }
      }
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
  pub fn index(x: u8, y: u8, z: u8) -> u16 {
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
    //this could actually be very nice to have as an option
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
          .map(move |x| (x.0 * pose, x.1))
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
