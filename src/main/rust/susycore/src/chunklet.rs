use rapier3d::math::{IVector, Pose, Real, Vector};
use rapier3d::parry::shape::{Cuboid, Voxels};
use rapier3d::prelude::SharedShape;
use std::num::NonZeroU32;

use crate::block_collisions::{BlockColliderInfoHandle, COLLIDERS, ColliderStore};
use crate::terrain::CHUNK_VOLUME;

type BlockHandle = Option<NonZeroU32>;

pub struct Chunklet {
  pub blocks: [BlockHandle; CHUNK_VOLUME],
  block_count: u32,
  pub(crate) cube_count: u32,
  pub(crate) voxels: Option<Voxels>,
  cube_bits: Box<[u64; 64]>,
  non_cube: Box<[Option<Vec<(Pose, SharedShape)>>; CHUNK_VOLUME]>,
  dirty: bool,
  composed: Option<SharedShape>,
}

impl Clone for Chunklet {
  fn clone(&self) -> Self {
    Self {
      blocks: self.blocks,
      block_count: self.block_count,
      cube_count: self.cube_count,
      voxels: self.voxels.clone(),
      cube_bits: self.cube_bits.clone(),
      non_cube: self.non_cube.clone(),
      dirty: self.dirty,
      composed: self.composed.clone(),
    }
  }
}

pub struct SetOutcome {
  pub changed: bool,
  pub emptied: bool,
}

impl Chunklet {
  pub fn new_with_blockhandle(blocks: [BlockColliderInfoHandle; CHUNK_VOLUME]) -> Self {
    Self::new(blocks.map(BlockColliderInfoHandle::into_block))
  }

  pub fn new(blocks: [BlockHandle; CHUNK_VOLUME]) -> Self {
    let mut c = Self {
      blocks,
      block_count: 0,
      cube_count: 0,
      voxels: None,
      cube_bits: Box::new([0u64; 64]),
      non_cube: Box::new(std::array::from_fn(|_| None)),
      dirty: false,
      composed: None,
    };
    let colliders = &*COLLIDERS.read().unwrap();
    for i in 0..CHUNK_VOLUME {
      let Some(handle) = blocks[i] else { continue };
      c.block_count += 1;
      let Some(info) = colliders.get(BlockColliderInfoHandle(handle.get())) else {
        continue;
      };
      let (x, y, z) = Self::index_decode(i as u16);
      if info.is_cube {
        c.set_cube_bit(i, true);
        c.cube_count += 1;
      } else {
        c.non_cube[i] = Some(Self::cuboids_for_cell(x, y, z, &info.boxes));
      }
    }
    c.rebuild_voxels();
    c.rebuild_composed();
    c
  }

  fn set_cube_bit(&mut self, i: usize, on: bool) {
    let w = i >> 6;
    let b = i & 63;
    if on {
      self.cube_bits[w] |= 1u64 << b;
    } else {
      self.cube_bits[w] &= !(1u64 << b);
    }
  }

  fn rebuild_voxels(&mut self) {
    if self.cube_count == 0 {
      self.voxels = None;
      return;
    }
    let mut coords = Vec::with_capacity(self.cube_count as usize);
    for (w, &word) in self.cube_bits.iter().enumerate() {
      let mut bits = word;
      while bits != 0 {
        let b = bits.trailing_zeros() as usize;
        bits &= bits - 1;
        let i = (w << 6) | b;
        let (x, y, z) = Self::index_decode(i as u16);
        coords.push(IVector::new(x as i32, y as i32, z as i32));
      }
    }
    self.voxels = Some(Voxels::new(Vector::splat(1.0 as Real), &coords));
  }

  fn cuboids_for_cell(
    x: u8,
    y: u8,
    z: u8,
    boxes: &[rapier3d::parry::bounding_volume::Aabb],
  ) -> Vec<(Pose, SharedShape)> {
    let cell = Vector::new(x as Real, y as Real, z as Real);
    boxes
      .iter()
      .map(|aabb| (
        Pose::from_translation(cell + aabb.center()),
        SharedShape::new(Cuboid::new(aabb.half_extents())),
      ))
      .collect()
  }

  pub fn count(&self) -> u32 {
    self.block_count
  }

  pub fn set(
    &mut self,
    x: u8,
    y: u8,
    z: u8,
    value: Option<NonZeroU32>,
    colliders: &ColliderStore,
  ) -> SetOutcome {
    let i = Self::index(x, y, z) as usize;
    let old = self.blocks[i];
    if old == value {
      return SetOutcome { changed: false, emptied: self.block_count == 0 };
    }

    let mut cubes_changed = false;
    if old.is_some() {
      if self.non_cube[i].take().is_none() {
        self.set_cube_bit(i, false);
        self.cube_count = self.cube_count.saturating_sub(1);
        cubes_changed = true;
      }
      self.block_count = self.block_count.saturating_sub(1);
    }

    if let Some(h) = value {
      self.block_count += 1;
      if let Some(info) = colliders.get(BlockColliderInfoHandle(h.get())) {
        if info.is_cube {
          self.set_cube_bit(i, true);
          self.cube_count += 1;
          self.non_cube[i] = None;
          cubes_changed = true;
        } else {
          self.non_cube[i] = Some(Self::cuboids_for_cell(x, y, z, &info.boxes));
        }
      } else {
        self.non_cube[i] = None;
      }
    }

    self.blocks[i] = value;
    self.dirty = true;
    self.composed = None;
    if cubes_changed {
      self.rebuild_voxels();
    }
    SetOutcome { changed: true, emptied: self.block_count == 0 }
  }

  fn rebuild_composed(&mut self) {
    let has_non_cube = self.non_cube.iter().any(|c| c.is_some());
    if self.cube_count == 0 && !has_non_cube {
      self.composed = None;
      self.dirty = false;
      return;
    }
    if self.cube_count > 0 && !has_non_cube {
      self.composed = Some(SharedShape::new(Voxels::new(
        Vector::splat(1.0 as Real),
        &self.cube_coords(),
      )));
      self.dirty = false;
      return;
    }
    let mut children: Vec<(Pose, SharedShape)> = Vec::new();
    if self.cube_count > 0 {
      children.push((Pose::identity(), SharedShape::new(Voxels::new(
        Vector::splat(1.0 as Real),
        &self.cube_coords(),
      ))));
    }
    for cell in self.non_cube.iter().flatten() {
      children.extend(cell.iter().cloned());
    }
    self.composed = Some(SharedShape::compound(children));
    self.dirty = false;
  }

  fn cube_coords(&self) -> Vec<IVector> {
    let mut coords = Vec::with_capacity(self.cube_count as usize);
    for (w, &word) in self.cube_bits.iter().enumerate() {
      let mut bits = word;
      while bits != 0 {
        let b = bits.trailing_zeros() as usize;
        bits &= bits - 1;
        let i = (w << 6) | b;
        let (x, y, z) = Self::index_decode(i as u16);
        coords.push(IVector::new(x as i32, y as i32, z as i32));
      }
    }
    coords
  }

  pub fn compose(&mut self) -> Option<SharedShape> {
    if self.dirty {
      self.rebuild_composed();
    }
    self.composed.clone()
  }

  pub fn get(&self, x: u8, y: u8, z: u8) -> BlockColliderInfoHandle {
    BlockColliderInfoHandle(
      self.blocks[Self::index(x, y, z) as usize].map(NonZeroU32::get).unwrap_or(0),
    )
  }

  #[inline(always)]
  pub fn index(x: u8, y: u8, z: u8) -> u16 {
    (y as u16) << 8 | (z as u16) << 4 | x as u16
  }

  #[inline(always)]
  pub fn index_decode(index: u16) -> (u8, u8, u8) {
    ((index & 0xF) as u8, ((index >> 8) & 0xF) as u8, ((index >> 4) & 0xF) as u8)
  }
}
