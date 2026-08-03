use rapier3d::math::Vec3;
use rapier3d::prelude::{
  ColliderBuilder, ColliderHandle, ColliderSet, IslandManager, RigidBodySet, SharedShape,
};
use std::collections::HashMap;
use std::mem::size_of;
use std::num::NonZeroU32;

use crate::Real;
use crate::block_collisions::BlockColliderInfoHandle;
use crate::chunklet::Chunklet;

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
      | (x.unsigned_abs() as u64) << 38
      | (z.unsigned_abs() as u64) << 13
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
  pub colliders: HashMap<PackedChunkletCoords, ColliderHandle>,
}

impl Default for TerrainData {
  fn default() -> Self {
    Self::new()
  }
}

impl TerrainData {
  pub fn new() -> Self {
    Self {
      colliders: HashMap::new(),
    }
  }

  fn collider_for(&self, x: i32, y: u8, z: i32) -> Option<ColliderHandle> {
    self
      .colliders
      .get(&PackedChunkletCoords::from_xyz(x, y, z))
      .copied()
  }

  //a very unsafe function btw
  fn handle_to_blockhandle(h: BlockColliderInfoHandle) -> Option<NonZeroU32> {
    NonZeroU32::new(h.0)
  }

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
  ) {
    let Some(handle) = self.collider_for(cx, cy, cz) else {
      log::error!("no chunk at {cx} {cy} {cz}");
      return;
    };

    assert_eq!(
      size_of::<BlockColliderInfoHandle>(),
      size_of::<Option<NonZeroU32>>()
    );
    assert_eq!(
      Self::handle_to_blockhandle(BlockColliderInfoHandle(0)),
      None
    );
    assert_eq!(
      Self::handle_to_blockhandle(BlockColliderInfoHandle(1)),
      NonZeroU32::new(1)
    );

    let value = Self::handle_to_blockhandle(new);
    let removed = {
      let Some(chunk) = colliders
        .get_mut(handle)
        .and_then(|c| c.shape_mut().as_shape_mut::<Chunklet>())
      else {
        log::error!("chunk collider {handle:?} is not a Chunklet");
        return;
      };
      chunk.set(x, y, z, value);
      chunk.count() == 0
    };

    if removed {
      self
        .colliders
        .remove(&PackedChunkletCoords::from_xyz(cx, cy, cz));
      colliders.remove(handle, islands, bodies, true);
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
  ) {
    if let Some(handle) = self
      .colliders
      .remove(&PackedChunkletCoords::from_xyz(x, y, z))
    {
      colliders.remove(handle, islands, bodies, true);
    }
  }
  pub fn put(
    &mut self,
    x: i32,
    y: u8,
    z: i32,
    c: Chunklet,
    colliders: &mut ColliderSet,
  ) -> ColliderHandle {
    let handle = colliders.insert(
      ColliderBuilder::new(SharedShape::new(c))
        .translation(Vec3::new(
          (x * 16) as Real,
          (y * 16) as Real,
          (z * 16) as Real,
        ))
        .build(),
    );
    self
      .colliders
      .insert(PackedChunkletCoords::from_xyz(x, y, z), handle);
    handle
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
    assert_eq!(
      TerrainData::handle_to_blockhandle(BlockColliderInfoHandle(0)),
      None
    );
  }

  #[test]
  fn nonzero_handle_maps_to_some() {
    assert_eq!(
      TerrainData::handle_to_blockhandle(BlockColliderInfoHandle(1)),
      NonZeroU32::new(1)
    );
    assert_eq!(
      TerrainData::handle_to_blockhandle(BlockColliderInfoHandle(u32::MAX)),
      NonZeroU32::new(u32::MAX)
    );
    assert!(TerrainData::handle_to_blockhandle(BlockColliderInfoHandle(u32::MAX)).is_some());
  }

  const LIMIT: i32 = (3_000_000 >> 4) - 1;

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
