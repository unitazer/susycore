use rapier3d::dynamics::SoftBodySet;
use rapier3d::math::Vec3;
use rapier3d::prelude::{
  ColliderBuilder, ColliderHandle, ColliderSet, IslandManager, RigidBodySet,
};
use std::collections::HashMap;

use crate::Real;
use crate::block_collisions::BlockColliderInfoHandle;
use crate::chunklet::Chunklet;

#[derive(Debug, Hash, PartialEq, PartialOrd, Ord, Eq, Clone, Copy)]
pub struct PackedChunkletCoords(u64);
impl PackedChunkletCoords {
  #[inline(always)]
  pub fn from_xyz(x: i32, y: u8, z: i32) -> Self {
    Self::try_from_xyz(x, y as i32, z).expect("chunklet coords out of range")
  }
  #[inline(always)]
  pub fn try_from_xyz(x: i32, y: i32, z: i32) -> Option<Self> {
    if x.unsigned_abs() >= (3_000_000 >> 4)
      || z.unsigned_abs() >= (3_000_000 >> 4)
      || !(0..=u8::MAX as i32).contains(&y)
    {
      return None;
    }
    let packed = ((x < 0) as u64) << 63
      | ((z < 0) as u64) << 62
      | (x.unsigned_abs() as u64) << 38
      | (z.unsigned_abs() as u64) << 13
      | y as u64;
    Some(Self(packed))
  }
  #[inline(always)]
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
  pub chunks: HashMap<PackedChunkletCoords, (Chunklet, ColliderHandle)>,
}

impl Default for TerrainData {
  fn default() -> Self {
    Self::new()
  }
}

impl TerrainData {
  pub fn new() -> Self {
    Self {
      chunks: HashMap::new(),
    }
  }

  #[allow(clippy::too_many_arguments)]
  pub fn update(
    &mut self,
    cx: i32,
    cy: u8,
    cz: i32,
    x: u8,
    y: u8,
    z: u8,
    new: BlockColliderInfoHandle,
    colliders: &mut ColliderSet,
    islands: &mut IslandManager,
    bodies: &mut RigidBodySet,
    softbodies: &mut SoftBodySet,
  ) {
    let coords = PackedChunkletCoords::from_xyz(cx, cy, cz);
    let Some((chunk, handle)) = self.chunks.get_mut(&coords) else {
      log::debug!("no chunk at {cx} {cy} {cz}");
      return;
    };
    let handle = *handle;
    let colliders_store = &*crate::block_collisions::COLLIDERS.read().unwrap();
    let outcome = chunk.set(x, y, z, new.into_block(), colliders_store);
    if !outcome.changed {
      return;
    }
    if outcome.emptied {
      self.chunks.remove(&coords);
      colliders.remove(handle, islands, bodies, softbodies, true);
      return;
    }
    if let Some(shape) = chunk.compose() {
      if let Some(co) = colliders.get_mut(handle) {
        co.set_shape(shape);
      }
    } else {
      self.chunks.remove(&coords);
      colliders.remove(handle, islands, bodies, softbodies, true);
    }
  }

  pub fn remove(
    &mut self,
    x: i32,
    y: u8,
    z: i32,
    colliders: &mut ColliderSet,
    islands: &mut IslandManager,
    bodies: &mut RigidBodySet,
    softbodies: &mut SoftBodySet,
  ) {
    let coords = PackedChunkletCoords::from_xyz(x, y, z);
    if let Some((_, handle)) = self.chunks.remove(&coords) {
      colliders.remove(handle, islands, bodies, softbodies, true);
    }
  }

  pub fn put(
    &mut self,
    x: i32,
    y: u8,
    z: i32,
    mut c: Chunklet,
    colliders: &mut ColliderSet,
    islands: &mut IslandManager,
    bodies: &mut RigidBodySet,
    softbodies: &mut SoftBodySet,
  ) -> Option<ColliderHandle> {
    let coords = PackedChunkletCoords::from_xyz(x, y, z);
    let Some(shape) = c.compose() else {
      log::warn!(
        "chunklet {x} {y} {z} compose() returned None (count={} cubes={} has_voxels={})",
        c.count(),
        c.cube_count,
        c.voxels.is_some()
      );
      return None;
    };
    if let Some((_, old)) = self.chunks.remove(&coords) {
      colliders.remove(old, islands, bodies, softbodies, true);
    }
    let handle = colliders.insert(
      ColliderBuilder::new(shape)
        .translation(Vec3::new(
          (x * 16) as Real,
          (y * 16) as Real,
          (z * 16) as Real,
        ))
        .build(),
    );
    self.chunks.insert(coords, (c, handle));
    Some(handle)
  }
}

#[cfg(test)]
mod tests {
  use std::mem::size_of;
  use std::num::NonZeroU32;

  use super::*;

  #[test]
  fn option_nonzero_is_not_costing_memory() {
    assert_eq!(
      size_of::<BlockColliderInfoHandle>(),
      size_of::<Option<NonZeroU32>>()
    );
  }

  #[test]
  fn air_handle_maps_to_none() {
    assert_eq!(BlockColliderInfoHandle(0).into_block(), None);
  }

  #[test]
  fn nonzero_handle_maps_to_some() {
    assert_eq!(BlockColliderInfoHandle(1).into_block(), NonZeroU32::new(1));
    assert_eq!(
      BlockColliderInfoHandle(u32::MAX).into_block(),
      NonZeroU32::new(u32::MAX)
    );
    assert!(BlockColliderInfoHandle(u32::MAX).into_block().is_some());
  }

  const LIMIT: i32 = (3_000_000 >> 4) - 1;

  #[test]
  fn coords_out_of_range_rejected() {
    assert!(PackedChunkletCoords::try_from_xyz(LIMIT + 1, 0, 0).is_none());
    assert!(PackedChunkletCoords::try_from_xyz(0, 0, LIMIT + 1).is_none());
    assert!(PackedChunkletCoords::try_from_xyz(0, 0, LIMIT).is_some());
    assert!(PackedChunkletCoords::try_from_xyz(0, -1, 0).is_none());
    assert!(PackedChunkletCoords::try_from_xyz(0, 256, 0).is_none());
    assert!(PackedChunkletCoords::try_from_xyz(0, 255, 0).is_some());
  }

  #[test]
  fn coords_round_trip() {
    for x in [-LIMIT, -1, 0, 1, LIMIT].into_iter() {
      for z in [-LIMIT, -1, 0, 1, LIMIT].into_iter() {
        for y in [0u8, 1, 16, 127, 255] {
          let packed = PackedChunkletCoords::from_xyz(x, y, z);
          assert_eq!(PackedChunkletCoords::to_xyz(packed.inner()), (x, y, z));
        }
      }
    }
  }
}
